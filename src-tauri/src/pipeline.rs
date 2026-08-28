use anyhow::Result;
use std::sync::atomic::{AtomicBool, AtomicU64, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use tauri::Emitter;
use tauri::Manager;
use tokio::sync::{mpsc, Notify};

use crate::app_detector;
use crate::app_detector::types::{RecordingContext, TargetAppGuard};
use crate::audio::{AudioCaptureHandle, AudioConfig};
use crate::credentials::{
    resolve_llm_config_secret, resolve_stt_config_secret, SystemCredentialVault,
};
use crate::llm::{self, LlmConfig, PolishRequest};
use crate::output;
use crate::storage;
use crate::stt::{self, SttConfig, TranscriptEvent};
use crate::SessionTokenStore;

// ─── Timing constants ───

/// On macOS, verify whether the process has been granted Accessibility (Assistive Access)
/// permission. enigo uses CGEventPost under the hood, which requires this permission;
/// without it all synthesised key events are silently dropped by the OS.
/// Returns true on all non-macOS platforms (no permission needed).
pub fn is_accessibility_trusted() -> bool {
    #[cfg(target_os = "macos")]
    {
        #[link(name = "ApplicationServices", kind = "framework")]
        extern "C" {
            fn AXIsProcessTrusted() -> u8;
        }
        unsafe { AXIsProcessTrusted() != 0 }
    }
    #[cfg(not(target_os = "macos"))]
    {
        true
    }
}

/// On macOS, request Accessibility permission and open the system Privacy pane.
/// Returns true if permission is already granted or on non-macOS platforms.
pub fn request_accessibility_permission() -> bool {
    #[cfg(target_os = "macos")]
    {
        if is_accessibility_trusted() {
            return true;
        }

        let trusted_after_prompt = request_accessibility_permission_prompt();

        if let Err(e) = std::process::Command::new("/usr/bin/open")
            .arg("x-apple.systempreferences:com.apple.preference.security?Privacy_Accessibility")
            .spawn()
        {
            tracing::warn!("Failed to open macOS Accessibility settings: {}", e);
        }

        trusted_after_prompt
    }
    #[cfg(not(target_os = "macos"))]
    {
        true
    }
}

#[cfg(target_os = "macos")]
fn request_accessibility_permission_prompt() -> bool {
    use std::ffi::c_void;
    use std::ptr;

    type Boolean = u8;
    type CFDictionaryRef = *const c_void;
    type CFStringRef = *const c_void;
    type CFBooleanRef = *const c_void;

    #[link(name = "ApplicationServices", kind = "framework")]
    extern "C" {
        fn AXIsProcessTrustedWithOptions(options: CFDictionaryRef) -> Boolean;
        static kAXTrustedCheckOptionPrompt: CFStringRef;
    }

    #[link(name = "CoreFoundation", kind = "framework")]
    extern "C" {
        static kCFBooleanTrue: CFBooleanRef;
        fn CFDictionaryCreate(
            allocator: *const c_void,
            keys: *const *const c_void,
            values: *const *const c_void,
            num_values: isize,
            key_callbacks: *const c_void,
            value_callbacks: *const c_void,
        ) -> CFDictionaryRef;
        fn CFRelease(cf: *const c_void);
    }

    unsafe {
        let keys = [kAXTrustedCheckOptionPrompt];
        let values = [kCFBooleanTrue];
        let options = CFDictionaryCreate(
            ptr::null(),
            keys.as_ptr(),
            values.as_ptr(),
            1,
            ptr::null(),
            ptr::null(),
        );

        if options.is_null() {
            return false;
        }

        let trusted = AXIsProcessTrustedWithOptions(options) != 0;
        CFRelease(options);
        trusted
    }
}

/// Delay before capturing selected text to ensure hotkey modifiers are released.
const SELECTED_TEXT_CAPTURE_DELAY_MS: u64 = 60;
/// Interval for polling audio volume during recording.
const VOLUME_POLL_INTERVAL_MS: u64 = 50;
/// Timeout for STT finalization after recording stops.
const STT_FINALIZE_TIMEOUT_SECS: u64 = 120;

fn generate_cloud_operation_id() -> String {
    static COUNTER: AtomicU64 = AtomicU64::new(1);
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();
    let counter = COUNTER.fetch_add(1, Ordering::Relaxed) as u128;
    let mixed = now ^ (counter << 64);

    format!(
        "{:08x}-{:04x}-{:04x}-{:04x}-{:012x}",
        (mixed >> 96) as u32,
        (mixed >> 80) as u16,
        (mixed >> 64) as u16,
        (mixed >> 48) as u16,
        mixed & 0x0000_ffff_ffff_ffff_ffffu128
    )
}

#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PipelineState {
    Idle,
    Preparing,
    Recording,
    Transcribing,
    Polishing,
    Outputting,
    AskRecording,
    AskThinking,
}

impl PipelineState {
    fn as_u8(self) -> u8 {
        match self {
            Self::Idle => 0,
            Self::Preparing => 1,
            Self::Recording => 2,
            Self::Transcribing => 3,
            Self::Polishing => 4,
            Self::Outputting => 5,
            Self::AskRecording => 6,
            Self::AskThinking => 7,
        }
    }

    fn from_u8(v: u8) -> Self {
        match v {
            1 => Self::Preparing,
            2 => Self::Recording,
            3 => Self::Transcribing,
            4 => Self::Polishing,
            5 => Self::Outputting,
            6 => Self::AskRecording,
            7 => Self::AskThinking,
            _ => Self::Idle,
        }
    }
}

#[derive(Clone)]
struct SttTaskControl {
    id: u64,
    done: Arc<Notify>,
    abort: Arc<Notify>,
}

fn should_finalize_stt_task(
    abort_flag: &AtomicBool,
    active_session_id: &AtomicU64,
    task_session_id: u64,
) -> bool {
    !abort_flag.load(Ordering::SeqCst)
        && active_session_id.load(Ordering::SeqCst) == task_session_id
}

fn no_speech_user_error() -> crate::error::UserError {
    crate::error::UserError {
        code: "stt_no_speech_detected".to_string(),
        details: None,
        retry_count: 0,
    }
}

fn llm_polish_user_error(error: &crate::error::AppError) -> crate::error::UserError {
    if matches!(error, crate::error::AppError::LlmQuota(_)) {
        return error.to_user_error();
    }

    crate::error::UserError {
        code: "llm_failed".to_string(),
        details: Some(error.to_string()),
        retry_count: 0,
    }
}

fn output_user_error(error: &anyhow::Error) -> crate::error::UserError {
    let details = error.to_string();
    let is_accessibility_error = details.contains("ACCESSIBILITY_REQUIRED");

    crate::error::UserError {
        code: if is_accessibility_error {
            "accessibility_required"
        } else {
            "output_failed"
        }
        .to_string(),
        details: if is_accessibility_error {
            None
        } else {
            Some(details)
        },
        retry_count: 0,
    }
}

fn accessibility_required_user_error() -> crate::error::UserError {
    crate::error::UserError {
        code: "accessibility_required".to_string(),
        details: None,
        retry_count: 0,
    }
}

fn strategy_uses_automated_input(strategy: output::InsertionStrategy) -> bool {
    matches!(
        strategy,
        output::InsertionStrategy::Auto
            | output::InsertionStrategy::Keyboard
            | output::InsertionStrategy::ClipboardPaste
            | output::InsertionStrategy::WindowsSendInput
    )
}

fn effective_strategy_for_accessibility(
    strategy: output::InsertionStrategy,
    accessibility_trusted: bool,
) -> (output::InsertionStrategy, Option<crate::error::UserError>) {
    if !accessibility_trusted && strategy_uses_automated_input(strategy) {
        return (
            output::InsertionStrategy::ClipboardCopyOnly,
            Some(accessibility_required_user_error()),
        );
    }

    (strategy, None)
}

fn streaming_insert_user_error(details: Option<String>) -> crate::error::UserError {
    crate::error::UserError {
        code: "output_streaming_partial".to_string(),
        details,
        retry_count: 0,
    }
}

fn selected_text_has_content(selected_text: Option<&str>) -> bool {
    selected_text.is_some_and(|text| !text.trim().is_empty())
}

fn selected_text_command_requires_llm(selected_text: Option<&str>) -> bool {
    selected_text_has_content(selected_text)
}

fn voice_intent_requires_generated_output(kind: crate::voice_intent::VoiceIntentKind) -> bool {
    !matches!(
        kind,
        crate::voice_intent::VoiceIntentKind::DictateInsert
            | crate::voice_intent::VoiceIntentKind::Search
    )
}

fn history_provider_kind(config: &storage::AppConfig) -> storage::HistoryProviderKind {
    let provider = if config.polish_enabled {
        config.llm_provider.as_str()
    } else {
        config.stt_provider.as_str()
    };
    if provider == "cloud" {
        return storage::HistoryProviderKind::ManagedCloud;
    }
    if provider == "ollama"
        || provider == "apple-speech"
        || (provider == "custom-whisper"
            && (config.stt_custom_base_url.contains("localhost")
                || config.stt_custom_base_url.contains("127.0.0.1")))
    {
        return storage::HistoryProviderKind::Local;
    }
    storage::HistoryProviderKind::Byok
}

fn route_pipeline_voice_intent(
    mode: crate::voice_intent::VoiceMode,
    raw_text: &str,
    selected_text: Option<&str>,
    config: &storage::AppConfig,
) -> crate::voice_intent::VoiceIntent {
    let speech_language = if config.stt_language == "multi" {
        crate::voice_intent::SpeechLanguageMode::Automatic
    } else {
        crate::voice_intent::SpeechLanguageMode::Explicit(&config.stt_language)
    };
    crate::voice_intent::VoiceIntentRouter::route(crate::voice_intent::VoiceRouteRequest {
        mode,
        utterance: raw_text,
        has_selected_text: selected_text_has_content(selected_text),
        speech_language,
        flags: config.voice_routing_flags,
    })
}

fn streaming_insert_strategy_for_config(
    config: &storage::AppConfig,
    has_selected_text: bool,
    accessibility_trusted: bool,
    keyboard_available: bool,
) -> Option<output::InsertionStrategy> {
    if !config.streaming_insert_enabled || has_selected_text || !accessibility_trusted {
        return None;
    }

    let strategy = output::InsertionStrategy::from_config_value(&config.insertion_strategy)
        .direct_streaming_strategy()?;
    if strategy == output::InsertionStrategy::Keyboard && !keyboard_available {
        return None;
    }

    Some(strategy)
}

fn streaming_insert_strategy_for_runtime(
    config: &storage::AppConfig,
    selected_text: Option<&str>,
) -> Option<output::InsertionStrategy> {
    let keyboard_available = output::keyboard::check_keyboard_available().is_ok();
    streaming_insert_strategy_for_config(
        config,
        selected_text_has_content(selected_text),
        is_accessibility_trusted(),
        keyboard_available,
    )
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ActiveSceneHistoryDiagnostics {
    id: Option<String>,
    source: Option<String>,
    name: Option<String>,
    prompt_chars: Option<i64>,
    prompt_truncated: bool,
}

fn active_scene_history_diagnostics(
    active_scene: Option<&storage::ActiveScene>,
) -> ActiveSceneHistoryDiagnostics {
    match active_scene {
        Some(scene) => {
            let sanitized_prompt = scene.prompt_template.replace('\0', "").trim().to_string();
            let prompt_chars = sanitized_prompt.chars().count();
            ActiveSceneHistoryDiagnostics {
                id: Some(scene.id.clone()),
                source: Some(scene.source.clone()),
                name: Some(scene.name.clone()),
                prompt_chars: Some(prompt_chars.min(storage::SCENE_PROMPT_MAX_CHARS) as i64),
                prompt_truncated: prompt_chars > storage::SCENE_PROMPT_MAX_CHARS,
            }
        }
        None => ActiveSceneHistoryDiagnostics {
            id: None,
            source: None,
            name: None,
            prompt_chars: None,
            prompt_truncated: false,
        },
    }
}

#[derive(Debug, Clone)]
struct StreamingInsertReport {
    strategy: output::InsertionStrategy,
    inserted_text: String,
    attempted_chunks: usize,
    failed: bool,
    target_lost: bool,
    error_message: Option<String>,
}

impl StreamingInsertReport {
    fn new(strategy: output::InsertionStrategy) -> Self {
        Self {
            strategy,
            inserted_text: String::new(),
            attempted_chunks: 0,
            failed: false,
            target_lost: false,
            error_message: None,
        }
    }

    fn chars_inserted(&self) -> usize {
        self.inserted_text.chars().count()
    }

    fn has_inserted_text(&self) -> bool {
        !self.inserted_text.is_empty()
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum StreamingRecoveryAction {
    AlreadyComplete,
    InsertSuffix { suffix: String },
    CopyFullToClipboard { reason: String },
    CopyPartialToClipboard { reason: String },
    NoRecoveryNeeded,
}

fn streaming_recovery_action(
    report: &StreamingInsertReport,
    polished_text: Option<&str>,
    llm_succeeded: bool,
    target_still_trusted: bool,
) -> StreamingRecoveryAction {
    if !report.has_inserted_text() {
        return StreamingRecoveryAction::NoRecoveryNeeded;
    }

    if !llm_succeeded {
        return StreamingRecoveryAction::CopyPartialToClipboard {
            reason: "LLM failed after partial streaming insert".to_string(),
        };
    }

    let Some(polished_text) = polished_text else {
        return StreamingRecoveryAction::CopyFullToClipboard {
            reason: "missing polished text after successful LLM response".to_string(),
        };
    };

    if report.inserted_text == polished_text {
        return StreamingRecoveryAction::AlreadyComplete;
    }

    if polished_text.starts_with(&report.inserted_text) {
        if !target_still_trusted {
            return StreamingRecoveryAction::CopyFullToClipboard {
                reason: "target app changed after partial streaming insert".to_string(),
            };
        }

        let suffix: String = polished_text
            .chars()
            .skip(report.inserted_text.chars().count())
            .collect();
        return StreamingRecoveryAction::InsertSuffix { suffix };
    }

    StreamingRecoveryAction::CopyFullToClipboard {
        reason: "streaming prefix did not match final output".to_string(),
    }
}

struct StreamingInsertWorker {
    sender: mpsc::UnboundedSender<String>,
    handle: tokio::task::JoinHandle<StreamingInsertReport>,
}

impl StreamingInsertWorker {
    async fn finish(self) -> Option<StreamingInsertReport> {
        drop(self.sender);
        match self.handle.await {
            Ok(report) => Some(report),
            Err(error) => {
                tracing::warn!("Streaming insert worker failed to join: {error}");
                None
            }
        }
    }
}

fn spawn_streaming_insert_worker(
    app_handle: tauri::AppHandle,
    abort_flag: Arc<AtomicBool>,
    context_detector: app_detector::ContextDetectorHandle,
    strategy: output::InsertionStrategy,
    windows_sendinput_options: output::windows_sendinput::WindowsSendInputOptions,
    expected_target_guard: TargetAppGuard,
    expected_target_label: String,
) -> StreamingInsertWorker {
    let (sender, receiver) = mpsc::unbounded_channel();
    let handle = tokio::spawn(run_streaming_insert_worker(
        StreamingInsertWorkerContext {
            app_handle,
            abort_flag,
            context_detector,
            strategy,
            windows_sendinput_options,
            expected_target_guard,
            expected_target_label,
        },
        receiver,
    ));
    StreamingInsertWorker { sender, handle }
}

struct StreamingInsertWorkerContext {
    app_handle: tauri::AppHandle,
    abort_flag: Arc<AtomicBool>,
    context_detector: app_detector::ContextDetectorHandle,
    strategy: output::InsertionStrategy,
    windows_sendinput_options: output::windows_sendinput::WindowsSendInputOptions,
    expected_target_guard: TargetAppGuard,
    expected_target_label: String,
}

async fn run_streaming_insert_worker(
    context: StreamingInsertWorkerContext,
    mut receiver: mpsc::UnboundedReceiver<String>,
) -> StreamingInsertReport {
    let StreamingInsertWorkerContext {
        app_handle,
        abort_flag,
        context_detector,
        strategy,
        windows_sendinput_options,
        expected_target_guard,
        expected_target_label,
    } = context;
    let mut report = StreamingInsertReport::new(strategy);

    while let Some(chunk) = receiver.recv().await {
        if abort_flag.load(Ordering::SeqCst) {
            break;
        }
        if chunk.is_empty() {
            continue;
        }

        if !context_detector.target_still_matches(&expected_target_guard) {
            report.failed = true;
            report.target_lost = true;
            report.error_message = Some(format!(
                "Target app changed while streaming output for '{}'",
                expected_target_label
            ));
            break;
        }

        report.attempted_chunks += 1;
        match output::output_stream_chunk(&app_handle, &chunk, strategy, windows_sendinput_options)
            .await
        {
            Ok(insert_result)
                if insert_result.status == output::InsertStatus::Inserted
                    && insert_result.chars_inserted == chunk.chars().count() =>
            {
                report.inserted_text.push_str(&chunk);
            }
            Ok(insert_result) if insert_result.chars_inserted > 0 => {
                let partial_text: String =
                    chunk.chars().take(insert_result.chars_inserted).collect();
                report.inserted_text.push_str(&partial_text);
                report.failed = true;
                report.error_message = insert_result.message.or_else(|| {
                    Some(format!(
                        "Streaming insert stopped with status {:?}",
                        insert_result.status
                    ))
                });
                break;
            }
            Ok(insert_result) => {
                report.failed = true;
                report.error_message = insert_result.message.or_else(|| {
                    Some(format!(
                        "Streaming insert stopped with status {:?}",
                        insert_result.status
                    ))
                });
                break;
            }
            Err(error) => {
                report.failed = true;
                report.error_message = Some(error);
                break;
            }
        }
    }

    report
}

fn take_matching_stt_error(
    stt_error: &Mutex<Option<(u64, crate::error::UserError)>>,
    session_id: u64,
) -> Option<crate::error::UserError> {
    let mut guard = stt_error.lock().unwrap_or_else(|e| e.into_inner());
    if guard
        .as_ref()
        .is_some_and(|(latched_session_id, _)| *latched_session_id == session_id)
    {
        return guard.take().map(|(_, error)| error);
    }
    None
}

fn latch_stt_task_error_if_active(
    abort_flag: &AtomicBool,
    active_session_id: &AtomicU64,
    stt_error: &Mutex<Option<(u64, crate::error::UserError)>>,
    task_session_id: u64,
    error: &crate::error::AppError,
) -> Option<crate::error::UserError> {
    if !should_finalize_stt_task(abort_flag, active_session_id, task_session_id) {
        return None;
    }
    let user_error = error.to_user_error();
    *stt_error.lock().unwrap_or_else(|e| e.into_inner()) =
        Some((task_session_id, user_error.clone()));
    Some(user_error)
}

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
pub struct PipelineStartOptions {
    pub force_translate: bool,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum TranslationOperationPhase {
    Capturing,
    Finalizing,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct TranslationOperationState {
    target: String,
    phase: TranslationOperationPhase,
}

impl TranslationOperationState {
    fn new(target: String) -> Self {
        Self {
            target,
            phase: TranslationOperationPhase::Capturing,
        }
    }

    fn switch_target(&mut self, target: String) -> std::result::Result<String, &'static str> {
        if self.phase != TranslationOperationPhase::Capturing {
            return Err("translation_operation_finished");
        }
        Ok(std::mem::replace(&mut self.target, target))
    }

    fn finalize(&mut self) -> String {
        self.phase = TranslationOperationPhase::Finalizing;
        self.target.clone()
    }
}

fn apply_pipeline_start_options(
    mut config: storage::AppConfig,
    options: PipelineStartOptions,
) -> storage::AppConfig {
    if options.force_translate {
        config.translate_enabled = true;
    }
    config
}

#[derive(Clone)]
pub struct PipelineHandle {
    app_handle: tauri::AppHandle,
    context_detector: app_detector::ContextDetectorHandle,
    state: Arc<AtomicU8>,
    audio_handle: Arc<Mutex<Option<AudioCaptureHandle>>>,
    audio_volume: Arc<Mutex<f32>>,
    accumulated_text: Arc<Mutex<String>>,
    stt_session: Arc<Mutex<Option<SttTaskControl>>>,
    stt_error: Arc<Mutex<Option<(u64, crate::error::UserError)>>>,
    active_stt_session_id: Arc<AtomicU64>,
    active_deadline_session_id: Arc<AtomicU64>,
    abort_flag: Arc<AtomicBool>,
    preloaded_config: Arc<Mutex<Option<storage::AppConfig>>>,
    preloaded_app_ctx: Arc<Mutex<Option<RecordingContext>>>,
    preloaded_dictionary: Arc<Mutex<Option<Vec<String>>>>,
    preloaded_correction_rules: Arc<Mutex<Option<Vec<llm::CorrectionRule>>>>,
    preloaded_selected_text: Arc<Mutex<Option<String>>>,
    preloaded_voice_mode: Arc<Mutex<Option<crate::voice_intent::VoiceMode>>>,
    cloud_operation_id: Arc<Mutex<Option<String>>>,
    recording_start: Arc<Mutex<Option<std::time::Instant>>>,
    active_translation_operation: Arc<Mutex<Option<TranslationOperationState>>>,
    shared_client: reqwest::Client,
    /// Serializes start()/stop() so that stop() waits for start() to finish
    /// its setup before reading shared state (preloaded_config, audio_handle, etc.).
    /// Without this, a quick press-release in hold mode causes stop() to run
    /// while start() is still connecting to STT, finding empty fields.
    pipeline_lock: Arc<tokio::sync::Mutex<()>>,
}

struct PolishTextInput<'a> {
    raw_text: &'a str,
    voice_mode: crate::voice_intent::VoiceMode,
    config: &'a storage::AppConfig,
    app_ctx: &'a RecordingContext,
    dictionary_words: Vec<String>,
    correction_rules: Vec<llm::CorrectionRule>,
    selected_text: Option<String>,
    session_token: String,
    operation_id: Option<String>,
    voice_intent: crate::voice_intent::VoiceIntent,
    popup_fallback_enabled: bool,
}

#[derive(Debug, Clone)]
struct PolishTextOutcome {
    final_text: String,
    llm_elapsed: std::time::Duration,
    history_output_status: Option<String>,
    history_output_error: Option<String>,
    voice_execution: Option<crate::voice_intent::executor::VoiceExecutionResult>,
}

pub(crate) struct AskVoiceDraftOutcome {
    pub text: String,
    pub execution: crate::voice_intent::executor::VoiceExecutionResult,
}

struct HistoryOutputMetadata {
    status: Option<String>,
    error: Option<String>,
}

struct PipelineVoiceExecutionBackend<'a> {
    pipeline: &'a PipelineHandle,
    app_name: &'a str,
    question: &'a str,
    intent_kind: crate::voice_intent::VoiceIntentKind,
    target_guard: &'a TargetAppGuard,
    config: &'a storage::AppConfig,
    already_copied: bool,
    popup_fallback_enabled: bool,
}

#[async_trait::async_trait]
impl crate::voice_intent::executor::VoiceExecutionBackend for PipelineVoiceExecutionBackend<'_> {
    fn target_matches(&mut self, guard: &TargetAppGuard) -> bool {
        self.pipeline
            .context_detector
            .target_still_matches_now(guard)
    }

    async fn restore_target(
        &mut self,
        guard: &TargetAppGuard,
    ) -> std::result::Result<bool, String> {
        if let Some(window) = self.pipeline.app_handle.get_webview_window("ask") {
            let _ = window.hide();
        }
        let detector = self.pipeline.context_detector.clone();
        let guard = guard.clone();
        tokio::task::spawn_blocking(move || detector.restore_target_application(&guard))
            .await
            .map_err(|error| error.to_string())
    }

    async fn insert_at_cursor(&mut self, text: &str) -> std::result::Result<(), String> {
        let result = self
            .pipeline
            .output_text(text, self.app_name, self.target_guard, self.config)
            .await
            .map_err(|error| error.to_string())?;
        if result.status == output::InsertStatus::Inserted {
            Ok(())
        } else if result.status == output::InsertStatus::CopiedFallback {
            self.already_copied = true;
            Err("output copied instead of inserted".to_string())
        } else {
            Err("output was not inserted".to_string())
        }
    }

    async fn replace_selection(&mut self, text: &str) -> std::result::Result<(), String> {
        self.insert_at_cursor(text).await
    }

    async fn popup_answer(&mut self, text: &str) -> std::result::Result<(), String> {
        if !self.popup_fallback_enabled {
            return Err("popup fallback is owned by the Ask caller".to_string());
        }
        crate::commands::ask::show_answer_window_with_metadata(
            &self.pipeline.app_handle,
            self.question.to_string(),
            text.to_string(),
            self.intent_kind,
            true,
            false,
        )
    }

    async fn copy_to_clipboard(&mut self, text: &str) -> std::result::Result<(), String> {
        if self.already_copied {
            return Ok(());
        }
        let mut copy_config = self.config.clone();
        copy_config.insertion_strategy = "clipboardCopyOnly".to_string();
        let result = self
            .pipeline
            .output_text(
                text,
                self.app_name,
                &TargetAppGuard::default(),
                &copy_config,
            )
            .await
            .map_err(|error| error.to_string())?;
        if result.status == output::InsertStatus::CopiedFallback {
            self.already_copied = true;
            Ok(())
        } else {
            Err("clipboard copy did not complete".to_string())
        }
    }

    async fn open_search(
        &mut self,
        _url: &crate::voice_intent::search::SearchUrl,
    ) -> std::result::Result<(), String> {
        Err("search is not available in the dictation pipeline".to_string())
    }
}

impl PolishTextOutcome {
    fn normal(final_text: String, llm_elapsed: std::time::Duration) -> Self {
        Self {
            final_text,
            llm_elapsed,
            history_output_status: None,
            history_output_error: None,
            voice_execution: None,
        }
    }

    fn with_history_status(
        final_text: String,
        llm_elapsed: std::time::Duration,
        status: &'static str,
        error: impl Into<String>,
    ) -> Self {
        Self {
            final_text,
            llm_elapsed,
            history_output_status: Some(status.to_string()),
            history_output_error: Some(error.into()),
            voice_execution: None,
        }
    }

    fn with_execution(
        final_text: String,
        llm_elapsed: std::time::Duration,
        execution: crate::voice_intent::executor::VoiceExecutionResult,
        history_output_status: Option<String>,
        history_output_error: Option<String>,
    ) -> Self {
        Self {
            final_text,
            llm_elapsed,
            history_output_status,
            history_output_error,
            voice_execution: Some(execution),
        }
    }
}

impl PipelineHandle {
    pub fn new(
        app_handle: tauri::AppHandle,
        shared_client: reqwest::Client,
        context_detector: app_detector::ContextDetectorHandle,
    ) -> Self {
        Self {
            app_handle,
            context_detector,
            state: Arc::new(AtomicU8::new(PipelineState::Idle.as_u8())),
            audio_handle: Arc::new(Mutex::new(None)),
            audio_volume: Arc::new(Mutex::new(0.0)),
            accumulated_text: Arc::new(Mutex::new(String::new())),
            stt_session: Arc::new(Mutex::new(None)),
            stt_error: Arc::new(Mutex::new(None)),
            active_stt_session_id: Arc::new(AtomicU64::new(0)),
            active_deadline_session_id: Arc::new(AtomicU64::new(0)),
            abort_flag: Arc::new(AtomicBool::new(false)),
            preloaded_config: Arc::new(Mutex::new(None)),
            preloaded_app_ctx: Arc::new(Mutex::new(None)),
            preloaded_dictionary: Arc::new(Mutex::new(None)),
            preloaded_correction_rules: Arc::new(Mutex::new(None)),
            preloaded_selected_text: Arc::new(Mutex::new(None)),
            preloaded_voice_mode: Arc::new(Mutex::new(None)),
            cloud_operation_id: Arc::new(Mutex::new(None)),
            recording_start: Arc::new(Mutex::new(None)),
            active_translation_operation: Arc::new(Mutex::new(None)),
            shared_client,
            pipeline_lock: Arc::new(tokio::sync::Mutex::new(())),
        }
    }

    fn set_state(&self, new_state: PipelineState) {
        self.state.store(new_state.as_u8(), Ordering::SeqCst);
        if new_state == PipelineState::Idle {
            *self
                .active_translation_operation
                .lock()
                .unwrap_or_else(|error| error.into_inner()) = None;
            let _ = self.app_handle.emit(
                "pipeline:voice_mode",
                Option::<crate::voice_intent::VoiceMode>::None,
            );
        }
        let _ = self.app_handle.emit("pipeline:state", new_state);

        // Update tray tooltip + menu to reflect pipeline state
        if let Some(tray_handle) = self.app_handle.try_state::<crate::TrayHandle>() {
            let tooltip = match new_state {
                PipelineState::Preparing => "OpenTypeless - Preparing...",
                PipelineState::Recording => "OpenTypeless - Recording...",
                PipelineState::Transcribing => "OpenTypeless - Transcribing...",
                PipelineState::Polishing => "OpenTypeless - Polishing...",
                PipelineState::Outputting => "OpenTypeless - Outputting...",
                PipelineState::AskRecording => "OpenTypeless - Ask...",
                PipelineState::AskThinking => "OpenTypeless - Answering...",
                PipelineState::Idle => "OpenTypeless",
            };
            if let Ok(t) = tray_handle.tray.lock() {
                let _ = t.set_tooltip(Some(tooltip));
            }
        }
        crate::refresh_tray(&self.app_handle);
    }

    pub fn current_state(&self) -> PipelineState {
        PipelineState::from_u8(self.state.load(Ordering::SeqCst))
    }

    pub(crate) fn switch_active_translation_target(
        &self,
        target: String,
    ) -> std::result::Result<String, String> {
        if self.current_state() != PipelineState::Recording {
            return Err("translation_operation_finished".to_string());
        }
        let mut operation = self
            .active_translation_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        operation
            .as_mut()
            .ok_or_else(|| "translation_not_recording".to_string())?
            .switch_target(target)
            .map_err(str::to_string)
    }

    /// Immediately abort the pipeline regardless of current state.
    /// Stops audio capture, forces state to Idle, and signals any
    /// ongoing stop() to exit early via abort_flag.
    pub fn abort(&self) {
        tracing::info!(
            "Pipeline abort requested (current state: {:?})",
            self.current_state()
        );

        // Set abort flag so any running stop() exits early
        self.abort_flag.store(true, Ordering::SeqCst);
        self.active_stt_session_id.fetch_add(1, Ordering::SeqCst);
        self.active_deadline_session_id.store(0, Ordering::SeqCst);

        // Stop audio capture (closes channel → STT task terminates naturally)
        {
            let mut handle = self.audio_handle.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(ref mut h) = *handle {
                h.stop();
            }
            *handle = None;
        }
        if let Some(control) = self
            .stt_session
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
        {
            control.abort.notify_one();
            control.done.notify_one();
        }

        // Clear accumulated text
        self.accumulated_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        *self.stt_error.lock().unwrap_or_else(|e| e.into_inner()) = None;
        *self
            .cloud_operation_id
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = None;

        // Force state to Idle — emits pipeline:state event to sync frontend
        self.set_state(PipelineState::Idle);
    }

    fn clear_stt_session(&self, session_id: u64) {
        let mut session = self.stt_session.lock().unwrap_or_else(|e| e.into_inner());
        if session.as_ref().map(|control| control.id) == Some(session_id) {
            *session = None;
        }
    }

    /// Capture selected text from the foreground app by simulating Ctrl+C / Cmd+C.
    /// Must be called when no hotkey modifier keys are physically held down.
    /// Called from async context via block_in_place, so std::thread::sleep is acceptable.
    fn capture_selected_text(&self) -> Option<String> {
        crate::selection::capture_selected_text()
    }

    async fn load_config(&self) -> storage::AppConfig {
        self.app_handle
            .state::<storage::ConfigManager>()
            .load()
            .await
            .unwrap_or_default()
    }

    pub async fn start(&self) -> Result<()> {
        self.start_with_options(PipelineStartOptions::default())
            .await
    }

    pub async fn start_with_options(&self, options: PipelineStartOptions) -> Result<()> {
        // Hold pipeline_lock for the entire setup so stop() cannot read
        // partially-initialised state (preloaded_config, audio_handle, etc.).
        let _guard = self.pipeline_lock.lock().await;

        // Reset abort flag for new recording
        self.abort_flag.store(false, Ordering::SeqCst);

        // Atomic CAS: only one caller can transition Idle → Preparing. Recording is emitted only
        // after audio capture is ready, so the capsule does not tell users to speak too early.
        if self
            .state
            .compare_exchange(
                PipelineState::Idle.as_u8(),
                PipelineState::Preparing.as_u8(),
                Ordering::SeqCst,
                Ordering::SeqCst,
            )
            .is_err()
        {
            return Ok(());
        }
        self.set_state(PipelineState::Preparing);

        // Clear accumulated text
        self.accumulated_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        *self.stt_error.lock().unwrap_or_else(|e| e.into_inner()) = None;

        // P0-2: Load config BEFORE starting audio capture — fail fast on missing API key
        let config_data = apply_pipeline_start_options(self.load_config().await, options);
        let voice_mode = if options.force_translate {
            crate::voice_intent::VoiceMode::Translate
        } else {
            crate::voice_intent::VoiceMode::Dictate
        };
        *self
            .preloaded_voice_mode
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(voice_mode);
        *self
            .preloaded_config
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(config_data.clone());
        *self
            .preloaded_app_ctx
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(
            self.context_detector
                .snapshot_for_recording_enabled(config_data.context_adaptation_enabled),
        );
        let dictionary_store = self.app_handle.state::<storage::DictionaryStore>();
        let dict_words = dictionary_store.words().await;
        let correction_rules = dictionary_store
            .enabled_correction_rules()
            .await
            .into_iter()
            .map(|rule| llm::CorrectionRule {
                id: rule.id,
                pattern: rule.pattern,
                replacement: rule.replacement,
                enabled: rule.enabled,
            })
            .collect::<Vec<_>>();
        *self
            .preloaded_dictionary
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(dict_words);
        *self
            .preloaded_correction_rules
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(correction_rules);

        let stt_api_key = if config_data.stt_provider == "cloud" {
            self.app_handle
                .state::<SessionTokenStore>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        } else {
            match resolve_stt_config_secret(&config_data, &SystemCredentialVault) {
                Ok(secret) => secret,
                Err(error) => {
                    tracing::warn!("Failed to read STT credential: {error}");
                    let _ = self.app_handle.emit(
                        "pipeline:error",
                        "Failed to read STT credential from the system vault.",
                    );
                    *self
                        .preloaded_config
                        .lock()
                        .unwrap_or_else(|e| e.into_inner()) = None;
                    *self
                        .preloaded_app_ctx
                        .lock()
                        .unwrap_or_else(|e| e.into_inner()) = None;
                    *self
                        .preloaded_dictionary
                        .lock()
                        .unwrap_or_else(|e| e.into_inner()) = None;
                    *self
                        .preloaded_correction_rules
                        .lock()
                        .unwrap_or_else(|e| e.into_inner()) = None;
                    self.set_state(PipelineState::Idle);
                    return Ok(());
                }
            }
        };

        tracing::debug!(
            "Pipeline using config: stt_provider={}, stt_key_len={}, stt_lang={}",
            config_data.stt_provider,
            stt_api_key.len(),
            config_data.stt_language
        );

        // Guard: empty API key - bail before starting audio when provider requires one.
        if stt::config::stt_provider_requires_api_key(&config_data.stt_provider)
            && stt_api_key.is_empty()
        {
            let _ = self.app_handle.emit(
                "pipeline:error",
                "STT API key is not configured. Please set it in Settings -> Speech Recognition.",
            );
            *self
                .preloaded_config
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_app_ctx
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_dictionary
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_correction_rules
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            self.set_state(PipelineState::Idle);
            return Ok(());
        }

        let custom_whisper_config =
            if config_data.stt_provider == stt::config::CUSTOM_WHISPER_PROVIDER {
                match stt::config::build_custom_whisper_config(
                    &config_data.stt_custom_base_url,
                    &config_data.stt_custom_model,
                ) {
                    Ok(cfg) => Some(cfg),
                    Err(e) => {
                        let _ = self.app_handle.emit("pipeline:error", e);
                        *self
                            .preloaded_config
                            .lock()
                            .unwrap_or_else(|e| e.into_inner()) = None;
                        *self
                            .preloaded_app_ctx
                            .lock()
                            .unwrap_or_else(|e| e.into_inner()) = None;
                        *self
                            .preloaded_dictionary
                            .lock()
                            .unwrap_or_else(|e| e.into_inner()) = None;
                        *self
                            .preloaded_correction_rules
                            .lock()
                            .unwrap_or_else(|e| e.into_inner()) = None;
                        self.set_state(PipelineState::Idle);
                        return Ok(());
                    }
                }
            } else {
                None
            };

        // Prepare STT configuration before starting the shared audio/STT readiness phase.
        let cloud_operation_id = generate_cloud_operation_id();
        *self
            .cloud_operation_id
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(cloud_operation_id.clone());

        let stt_config = SttConfig {
            api_key: stt_api_key,
            language: if config_data.stt_language == "multi" {
                None
            } else {
                Some(config_data.stt_language.clone())
            },
            smart_format: true,
            sample_rate: 16000,
            resource_id: if config_data.stt_provider == stt::volcengine::VOLCENGINE_DOUBAO_PROVIDER
            {
                Some(config_data.stt_volcengine_resource_id.clone())
            } else {
                None
            },
            operation_id: Some(cloud_operation_id),
            managed_audio: stt::capabilities::managed_audio_encoding_config(
                &config_data,
                chrono::Utc::now().timestamp(),
            ),
            provider_region: (config_data.stt_provider
                == stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_PROVIDER)
                .then(|| config_data.stt_aliyun_qwen_region.clone()),
        };
        let managed_cloud_session_token =
            (config_data.stt_provider == "cloud").then(|| stt_config.api_key.clone());
        if let Some(session_token) = managed_cloud_session_token.clone() {
            stt::cloud::warm_managed_cloud_on_intent(self.shared_client.clone(), session_token);
        }

        let mut provider = match stt::create_provider(
            &config_data.stt_provider,
            custom_whisper_config,
            Some(self.shared_client.clone()),
        ) {
            Ok(provider) => provider,
            Err(e) => {
                tracing::error!("STT provider creation failed: {}", e);
                let _ = self
                    .app_handle
                    .emit("pipeline:error", format!("STT configuration failed: {e}"));
                *self
                    .preloaded_config
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_app_ctx
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_dictionary
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_correction_rules
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                self.set_state(PipelineState::Idle);
                return Ok(());
            }
        };
        // Start the platform audio backend before connecting STT. Both readiness
        // operations are then polled concurrently, so speech captured while a
        // network provider connects remains queued instead of being clipped.
        let config = AudioConfig::default();
        let (mut handle, mut audio_rx) = match AudioCaptureHandle::start(config) {
            Ok(result) => result,
            Err(e) => {
                tracing::error!("Audio capture failed: {}", e);
                let _ = self
                    .app_handle
                    .emit("pipeline:error", format!("Audio capture failed: {e}"));
                *self
                    .preloaded_config
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_app_ctx
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_dictionary
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_correction_rules
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                self.set_state(PipelineState::Idle);
                return Ok(());
            }
        };
        let startup_result = crate::audio::await_recording_startup(
            handle.wait_until_ready(),
            provider.connect(&stt_config),
        )
        .await;
        let capture_ready_at = match startup_result {
            Ok(capture_ready_at) => capture_ready_at,
            Err(error) => {
                let message = match error {
                    crate::audio::RecordingStartupError::Audio(error) => {
                        format!("Audio capture failed: {error}")
                    }
                    crate::audio::RecordingStartupError::Stt(error) => {
                        format!("STT connection failed: {error}")
                    }
                    crate::audio::RecordingStartupError::Timeout => {
                        "Recording startup timed out after 30 seconds. Please try again."
                            .to_string()
                    }
                };
                tracing::error!("Recording startup failed: {}", message);
                handle.stop();
                let _ = self.app_handle.emit("pipeline:error", message);
                *self
                    .preloaded_config
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_app_ctx
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_dictionary
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                *self
                    .preloaded_correction_rules
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()) = None;
                self.set_state(PipelineState::Idle);
                return Ok(());
            }
        };

        // Store the audio handle's volume reference.
        // Check abort_flag first — if abort() was called while we were connecting
        // to STT, don't store the handle (it would be orphaned with nobody to stop it).
        if self.abort_flag.load(Ordering::SeqCst) {
            tracing::info!("Pipeline aborted during setup, discarding audio capture");
            // handle drops here, stopping the capture thread
            *self
                .preloaded_config
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_app_ctx
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_dictionary
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_correction_rules
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            self.set_state(PipelineState::Idle);
            return Ok(());
        }
        let audio_vol = handle.get_volume();
        *self.audio_volume.lock().unwrap_or_else(|e| e.into_inner()) = audio_vol;
        *self.audio_handle.lock().unwrap_or_else(|e| e.into_inner()) = Some(handle);
        if self.abort_flag.load(Ordering::SeqCst) {
            tracing::info!("Pipeline aborted after storing audio capture, stopping capture");
            {
                let mut handle = self.audio_handle.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(ref mut h) = *handle {
                    h.stop();
                }
                *handle = None;
            }
            *self
                .preloaded_config
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_app_ctx
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_dictionary
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            *self
                .preloaded_correction_rules
                .lock()
                .unwrap_or_else(|e| e.into_inner()) = None;
            self.set_state(PipelineState::Idle);
            return Ok(());
        }

        let session_id = self.active_stt_session_id.fetch_add(1, Ordering::SeqCst) + 1;
        let resolved_limit = stt::capabilities::resolve_recording_limit(
            &config_data,
            None,
            chrono::Utc::now().timestamp(),
        );
        let effective_max_seconds = provider
            .recording_limit_override_seconds()
            .map_or(resolved_limit.effective_max_seconds, |override_seconds| {
                resolved_limit.effective_max_seconds.min(override_seconds)
            });
        let deadline_explanation_key = provider
            .recording_limit_override_explanation_key()
            .unwrap_or(&resolved_limit.capability.explanation_key)
            .to_string();
        let recording_deadline = crate::recording_deadline::RecordingDeadline::new(
            session_id,
            crate::recording_deadline::RecordingKind::Dictation,
            capture_ready_at,
            effective_max_seconds,
        );
        self.active_deadline_session_id
            .store(session_id, Ordering::SeqCst);
        if let Some(session_token) = managed_cloud_session_token {
            let warmup_client = self.shared_client.clone();
            let warmup_active_session_id = self.active_deadline_session_id.clone();
            tokio::spawn(async move {
                for elapsed_seconds in [240u64, 480] {
                    if elapsed_seconds >= u64::from(effective_max_seconds) {
                        break;
                    }
                    tokio::time::sleep_until(tokio::time::Instant::from_std(
                        capture_ready_at.monotonic
                            + std::time::Duration::from_secs(elapsed_seconds),
                    ))
                    .await;
                    if warmup_active_session_id.load(Ordering::SeqCst) != session_id {
                        break;
                    }
                    stt::cloud::warm_managed_cloud_on_intent(
                        warmup_client.clone(),
                        session_token.clone(),
                    );
                }
            });
        }
        *self
            .recording_start
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(capture_ready_at.monotonic);
        *self
            .active_translation_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = options
            .force_translate
            .then(|| TranslationOperationState::new(config_data.translation.active_target.clone()));
        self.set_state(PipelineState::Recording);
        let _ = self.app_handle.emit("pipeline:voice_mode", voice_mode);
        let _ = self
            .app_handle
            .emit("recording:deadline", recording_deadline.event);

        // Volume monitoring task
        let app_handle = self.app_handle.clone();
        let audio_handle_ref = self.audio_handle.clone();
        let state_ref = self.state.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(std::time::Duration::from_millis(VOLUME_POLL_INTERVAL_MS)).await;
                let current = PipelineState::from_u8(state_ref.load(Ordering::SeqCst));
                if current != PipelineState::Recording {
                    break;
                }
                let vol = audio_handle_ref
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .as_ref()
                    .map(|h| h.get_volume())
                    .unwrap_or(0.0);
                let _ = app_handle.emit("audio:volume", vol);
            }
        });

        // Selected text will be captured in stop() after hotkey is released,
        // so Ctrl+C simulation won't conflict with held keys.
        *self
            .preloaded_selected_text
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = None;

        // STT streaming task — provider is already connected
        let app_handle = self.app_handle.clone();
        let accumulated = self.accumulated_text.clone();
        let stt_control = SttTaskControl {
            id: session_id,
            done: Arc::new(Notify::new()),
            abort: Arc::new(Notify::new()),
        };
        *self.stt_session.lock().unwrap_or_else(|e| e.into_inner()) = Some(stt_control.clone());
        let abort_flag_ref = self.abort_flag.clone();
        let active_session_id_ref = self.active_stt_session_id.clone();
        let stt_error_ref = self.stt_error.clone();

        tokio::spawn(async move {
            // Forward audio to STT and receive transcripts
            loop {
                if !should_finalize_stt_task(
                    abort_flag_ref.as_ref(),
                    active_session_id_ref.as_ref(),
                    stt_control.id,
                ) {
                    break;
                }

                tokio::select! {
                    _ = stt_control.abort.notified() => {
                        tracing::info!("STT task received abort signal");
                        break;
                    }
                    chunk = audio_rx.recv() => {
                        match chunk {
                            Some(data) => {
                                if let Err(error) = provider.send_audio(&data).await {
                                    tracing::error!("STT send audio error: {}", error);
                                    crate::error::emit_cloud_session_invalid(&app_handle, &error);
                                    if let Some(user_error) = latch_stt_task_error_if_active(
                                        abort_flag_ref.as_ref(),
                                        active_session_id_ref.as_ref(),
                                        stt_error_ref.as_ref(),
                                        stt_control.id,
                                        &error,
                                    ) {
                                        let _ = app_handle.emit("pipeline:error", user_error);
                                    }
                                    break;
                                }
                            }
                            None => {
                                // Audio channel closed — disconnect and capture final transcript
                                if !should_finalize_stt_task(
                                    abort_flag_ref.as_ref(),
                                    active_session_id_ref.as_ref(),
                                    stt_control.id,
                                ) {
                                    break;
                                }

                                let disconnect_result = tokio::select! {
                                    _ = stt_control.abort.notified() => None,
                                    result = provider.disconnect() => Some(result),
                                };

                                match disconnect_result {
                                    Some(Ok(Some(text))) => {
                                        if should_finalize_stt_task(
                                            abort_flag_ref.as_ref(),
                                            active_session_id_ref.as_ref(),
                                            stt_control.id,
                                        ) {
                                            let mut acc = accumulated.lock().unwrap_or_else(|e| e.into_inner());
                                            acc.push_str(&text);
                                            let current = acc.clone();
                                            drop(acc);
                                            let _ = app_handle.emit("stt:final", &current);
                                        }
                                    }
                                    Some(Ok(None)) => {}
                                    Some(Err(e)) => {
                                        tracing::error!("STT disconnect error: {}", e);
                                        if should_finalize_stt_task(
                                            abort_flag_ref.as_ref(),
                                            active_session_id_ref.as_ref(),
                                            stt_control.id,
                                        ) {
                                            crate::error::emit_cloud_session_invalid(
                                                &app_handle,
                                                &e,
                                            );
                                            let user_error = e.to_user_error();
                                            *stt_error_ref.lock().unwrap_or_else(|e| e.into_inner()) =
                                                Some((stt_control.id, user_error.clone()));
                                            let _ = app_handle.emit("pipeline:error", user_error);
                                        }
                                    }
                                    None => {}
                                }
                                break;
                            }
                        }
                    }
                    transcript = provider.recv_transcript() => {
                        match transcript {
                            Ok(Some(TranscriptEvent::Partial { text })) => {
                                if should_finalize_stt_task(
                                    abort_flag_ref.as_ref(),
                                    active_session_id_ref.as_ref(),
                                    stt_control.id,
                                ) {
                                    let _ = app_handle.emit("stt:partial", &text);
                                }
                            }
                            Ok(Some(TranscriptEvent::Final { text, .. })) => {
                                if should_finalize_stt_task(
                                    abort_flag_ref.as_ref(),
                                    active_session_id_ref.as_ref(),
                                    stt_control.id,
                                ) {
                                    let mut acc = accumulated.lock().unwrap_or_else(|e| e.into_inner());
                                    acc.push_str(&text);
                                    acc.push(' ');
                                    let current = acc.clone();
                                    drop(acc);
                                    let _ = app_handle.emit("stt:final", &current);
                                }
                            }
                            Ok(Some(TranscriptEvent::Error { message })) => {
                                tracing::error!("STT error: {}", message);
                                if should_finalize_stt_task(
                                    abort_flag_ref.as_ref(),
                                    active_session_id_ref.as_ref(),
                                    stt_control.id,
                                ) {
                                    let user_error =
                                        crate::error::AppError::Config(message.clone()).to_user_error();
                                    *stt_error_ref.lock().unwrap_or_else(|e| e.into_inner()) =
                                        Some((stt_control.id, user_error.clone()));
                                    let _ = app_handle.emit("pipeline:error", user_error);
                                }
                                // Break out of the loop — STT has failed, no point
                                // continuing. Without break, the loop keeps running
                                // and the pipeline stays stuck in Recording forever.
                                break;
                            }
                            Err(e) => {
                                tracing::error!("STT recv error: {}", e);
                                if should_finalize_stt_task(
                                    abort_flag_ref.as_ref(),
                                    active_session_id_ref.as_ref(),
                                    stt_control.id,
                                ) {
                                    crate::error::emit_cloud_session_invalid(&app_handle, &e);
                                    let user_error = e.to_user_error();
                                    *stt_error_ref.lock().unwrap_or_else(|e| e.into_inner()) =
                                        Some((stt_control.id, user_error.clone()));
                                    let _ = app_handle.emit("pipeline:error", user_error);
                                }
                                break;
                            }
                            _ => {}
                        }
                    }
                }
            }

            // Signal that STT processing is complete
            stt_control.done.notify_one();
        });

        let deadline_pipeline = self.clone();
        let deadline_app = self.app_handle.clone();
        let active_deadline_session_id = self.active_deadline_session_id.clone();
        let deadline_provider_id = resolved_limit.capability.provider_id;
        tokio::spawn(async move {
            let reached = crate::recording_deadline::drive_recording_deadline(
                recording_deadline,
                || active_deadline_session_id.load(Ordering::SeqCst) == session_id,
                |signal| match signal {
                    crate::recording_deadline::RecordingDeadlineSignal::Warning {
                        seconds_remaining,
                    } => {
                        let _ = deadline_app.emit(
                            "recording:deadline-warning",
                            serde_json::json!({
                                "sessionId": session_id,
                                "recordingKind": "dictation",
                                "secondsRemaining": seconds_remaining,
                                "effectiveMaxSeconds": effective_max_seconds,
                                "providerId": deadline_provider_id.as_str(),
                                "explanationKey": deadline_explanation_key.as_str(),
                            }),
                        );
                    }
                    crate::recording_deadline::RecordingDeadlineSignal::Reached => {
                        let _ = deadline_app.emit(
                            "recording:deadline-reached",
                            serde_json::json!({
                                "sessionId": session_id,
                                "recordingKind": "dictation",
                                "effectiveMaxSeconds": effective_max_seconds,
                                "providerId": deadline_provider_id.as_str(),
                                "explanationKey": deadline_explanation_key.as_str(),
                            }),
                        );
                    }
                },
            )
            .await;
            if reached {
                if let Err(error) = deadline_pipeline.stop().await {
                    tracing::error!("Failed to stop recording at the provider deadline: {error}");
                }
            }
        });

        Ok(())
    }

    pub async fn stop(&self) -> Result<()> {
        // Acquire pipeline_lock so we wait for start() to finish its setup
        // (load config, initialize audio and connect STT) before reading shared state.
        // Released before the long stt_done wait so start() isn't blocked 120s.
        let guard = self.pipeline_lock.lock().await;

        // Atomic CAS: only one caller can transition Recording → Transcribing
        if self
            .state
            .compare_exchange(
                PipelineState::Recording.as_u8(),
                PipelineState::Transcribing.as_u8(),
                Ordering::SeqCst,
                Ordering::SeqCst,
            )
            .is_err()
        {
            return Ok(());
        }
        self.active_deadline_session_id.store(0, Ordering::SeqCst);
        let _ = self
            .app_handle
            .emit("pipeline:state", PipelineState::Transcribing);
        let finalized_translation_target = self
            .active_translation_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_mut()
            .map(TranslationOperationState::finalize);
        // Update tray for transcribing state
        if let Some(tray_handle) = self.app_handle.try_state::<crate::TrayHandle>() {
            if let Ok(t) = tray_handle.tray.lock() {
                let _ = t.set_tooltip(Some("OpenTypeless - Transcribing..."));
            }
        }
        crate::refresh_tray(&self.app_handle);

        let stop_start = std::time::Instant::now();

        // Capture selected text now — hotkey is released so Ctrl+C won't conflict.
        // Small delay to ensure hotkey modifiers are fully released (especially in toggle mode).
        let config_data = self
            .preloaded_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .unwrap_or_default();
        let selected_text = if config_data.selected_text_enabled {
            tokio::time::sleep(std::time::Duration::from_millis(
                SELECTED_TEXT_CAPTURE_DELAY_MS,
            ))
            .await;
            tokio::task::block_in_place(|| self.capture_selected_text())
        } else {
            None
        };
        tracing::info!(
            "Selected text result: len={}",
            selected_text.as_deref().map(|s| s.len()).unwrap_or(0)
        );
        *self
            .preloaded_selected_text
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = selected_text;

        // Stop audio capture (this drops the channel, signaling STT task to stop)
        {
            let mut handle = self.audio_handle.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(ref mut h) = *handle {
                h.stop();
            }
            *handle = None;
        }
        let stt_control = self
            .stt_session
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();

        // P2-1: Pre-build LLM resources while waiting for STT
        let preloaded_config = self
            .preloaded_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        let mut config = match preloaded_config {
            Some(c) => c,
            None => self.load_config().await,
        };
        if let Some(target) = finalized_translation_target {
            config.translation.active_target = target.clone();
            config.target_lang = target;
        }
        let app_ctx = self
            .preloaded_app_ctx
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .unwrap_or_else(|| {
                self.context_detector
                    .snapshot_for_recording_enabled(config.context_adaptation_enabled)
            });
        let dictionary_words = self
            .preloaded_dictionary
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .unwrap_or_default();
        let correction_rules = self
            .preloaded_correction_rules
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .unwrap_or_default();
        let selected_text = self
            .preloaded_selected_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        let operation_id = self
            .cloud_operation_id
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        let voice_mode = self
            .preloaded_voice_mode
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .unwrap_or(crate::voice_intent::VoiceMode::Dictate);

        // Extract session token before releasing guard (for cloud LLM)
        let session_token = if config.llm_provider == "cloud" {
            self.app_handle
                .state::<SessionTokenStore>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        } else {
            String::new()
        };

        // All shared state has been taken — release the lock so a new start()
        // isn't blocked by the long stt_done wait that follows.
        drop(guard);

        // ── Phase 1: Wait for STT ──────────────────────────────────────
        let raw_text = match self.wait_for_stt(stt_control.clone()).await? {
            Some(text) => text,
            None => {
                if let Some(control) = &stt_control {
                    self.clear_stt_session(control.id);
                }
                return Ok(());
            } // aborted or no speech detected
        };
        let voice_intent =
            route_pipeline_voice_intent(voice_mode, &raw_text, selected_text.as_deref(), &config);
        let stt_elapsed = stop_start.elapsed();
        tracing::info!(
            "[Pipeline Timing] STT finalize: {}ms",
            stt_elapsed.as_millis()
        );

        // Check abort before entering LLM polish and output
        if self.abort_flag.load(Ordering::SeqCst) {
            tracing::info!("Pipeline aborted before LLM/output");
            if let Some(control) = &stt_control {
                self.clear_stt_session(control.id);
            }
            return Ok(());
        }

        // ── Phase 2: LLM polish + output ───────────────────────────────
        let polish_outcome = self
            .polish_text(PolishTextInput {
                raw_text: &raw_text,
                voice_mode,
                config: &config,
                app_ctx: &app_ctx,
                dictionary_words,
                correction_rules,
                selected_text,
                session_token,
                operation_id,
                voice_intent,
                popup_fallback_enabled: true,
            })
            .await;
        let final_text = polish_outcome.final_text;
        let llm_elapsed = polish_outcome.llm_elapsed;

        // ── Phase 3: Timing, history, cleanup ──────────────────────────
        let total_elapsed = stop_start.elapsed();

        // Compute recording duration
        let duration_ms = self
            .recording_start
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .map(|start| start.elapsed().as_millis() as i64);

        tracing::info!(
            "[Pipeline Timing] Total stop(): {}ms (STT: {}ms, LLM: {}ms, Output+Save: {}ms)",
            total_elapsed.as_millis(),
            stt_elapsed.as_millis(),
            llm_elapsed.as_millis(),
            total_elapsed.as_millis() - stt_elapsed.as_millis() - llm_elapsed.as_millis(),
        );

        // Emit timing to frontend
        let _ = self.app_handle.emit(
            "pipeline:timing",
            serde_json::json!({
                "stt_ms": stt_elapsed.as_millis() as u64,
                "llm_ms": llm_elapsed.as_millis() as u64,
                "total_ms": total_elapsed.as_millis() as u64,
                "recording_ms": duration_ms,
            }),
        );

        let _ = self.app_handle.emit("pipeline:context", app_ctx.summary());

        // Save to history
        self.save_history(
            &raw_text,
            &final_text,
            &app_ctx,
            duration_ms,
            &config,
            HistoryOutputMetadata {
                status: polish_outcome.history_output_status,
                error: polish_outcome.history_output_error,
            },
        )
        .await;

        if let Some(control) = &stt_control {
            self.clear_stt_session(control.id);
        }
        self.set_state(PipelineState::Idle);
        Ok(())
    }

    /// Wait for the STT task to complete and return the transcribed text.
    /// Returns `Ok(Some(text))` on success, `Ok(None)` if aborted or no speech,
    /// or `Err` on failure.
    async fn wait_for_stt(&self, stt_control: Option<SttTaskControl>) -> Result<Option<String>> {
        if let Some(control) = &stt_control {
            tokio::select! {
                _ = control.done.notified() => {
                    tracing::debug!("STT task completed");
                }
                _ = tokio::time::sleep(std::time::Duration::from_secs(STT_FINALIZE_TIMEOUT_SECS)) => {
                    tracing::warn!("STT timed out after {}s", STT_FINALIZE_TIMEOUT_SECS);
                }
            }

            if !should_finalize_stt_task(
                self.abort_flag.as_ref(),
                self.active_stt_session_id.as_ref(),
                control.id,
            ) {
                tracing::info!("Ignoring stale or aborted STT task");
                return Ok(None);
            }
            if take_matching_stt_error(&self.stt_error, control.id).is_some() {
                self.set_state(PipelineState::Idle);
                return Ok(None);
            }
        } else {
            tracing::warn!("No STT session was available to wait for");
        }

        if self.abort_flag.load(Ordering::SeqCst) {
            tracing::info!("Pipeline aborted after STT wait");
            return Ok(None);
        }

        let raw_text = self
            .accumulated_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .trim()
            .to_string();

        if raw_text.is_empty() {
            let _ = self
                .app_handle
                .emit("pipeline:error", no_speech_user_error());
            self.set_state(PipelineState::Idle);
            return Ok(None);
        }

        Ok(Some(raw_text))
    }

    /// Polish raw text with LLM and output the result.
    /// Returns (final_text, llm_elapsed_duration).
    async fn polish_text(&self, input: PolishTextInput<'_>) -> PolishTextOutcome {
        let PolishTextInput {
            raw_text,
            voice_mode,
            config,
            app_ctx,
            dictionary_words,
            correction_rules,
            selected_text,
            session_token,
            operation_id,
            voice_intent,
            popup_fallback_enabled,
        } = input;
        let provider_plan =
            crate::voice_intent::plan_voice_provider_work(voice_mode, raw_text, &voice_intent);
        let Some(provider_text) = provider_plan.provider_input.as_deref() else {
            let message = "Search must bypass the language model and use the safe search executor.";
            let _ = self.app_handle.emit(
                "pipeline:error",
                crate::error::UserError {
                    code: "voice_route_failed".to_string(),
                    details: Some(message.to_string()),
                    retry_count: 0,
                },
            );
            return PolishTextOutcome::with_history_status(
                String::new(),
                std::time::Duration::ZERO,
                "fallback",
                message,
            );
        };
        let llm_api_key = if config.llm_provider == "cloud" {
            session_token
        } else {
            match resolve_llm_config_secret(config, &SystemCredentialVault) {
                Ok(secret) => secret,
                Err(error) => {
                    tracing::warn!("Failed to read LLM credential: {error}");
                    String::new()
                }
            }
        };

        // Check if polish is enabled and API key / token is available
        if !config.polish_enabled
            || (config.llm_provider != "cloud"
                && !llm::has_usable_provider_credentials(&config.llm_provider, &llm_api_key))
        {
            if selected_text_command_requires_llm(selected_text.as_deref())
                || voice_intent_requires_generated_output(voice_intent.kind)
            {
                let message = if !config.polish_enabled {
                    "This voice command needs AI polish. Enable AI polish before drafting, translating, or editing."
                } else {
                    "This voice command needs a configured LLM provider or Cloud sign-in."
                };
                if let Err(error) =
                    crate::commands::ask::show_error_window(&self.app_handle, message.to_string())
                {
                    tracing::warn!("Failed to show selected-text no-LLM Ask error window: {error}");
                    let _ = self.app_handle.emit(
                        "pipeline:error",
                        crate::error::UserError {
                            code: "llm_failed".to_string(),
                            details: Some(message.to_string()),
                            retry_count: 0,
                        },
                    );
                }
                return PolishTextOutcome::with_history_status(
                    String::new(),
                    std::time::Duration::ZERO,
                    "fallback",
                    message,
                );
            }

            // No polishing — output raw text directly
            if let Err(e) = self
                .output_text(
                    provider_text,
                    &app_ctx.profile.app_label,
                    &app_ctx.target_guard,
                    config,
                )
                .await
            {
                tracing::error!("Output failed: {}", e);
                let _ = self
                    .app_handle
                    .emit("pipeline:error", output_user_error(&e));
            }
            return PolishTextOutcome::normal(provider_text.to_string(), std::time::Duration::ZERO);
        }

        self.set_state(PipelineState::Polishing);
        let llm_start = std::time::Instant::now();

        let llm_config = LlmConfig {
            provider: config.llm_provider.clone(),
            api_key: llm_api_key,
            model: config.llm_model.clone(),
            base_url: config.llm_base_url.clone(),
            max_tokens: 4096,
            temperature: 0.3,
        };
        let provider = llm::create_provider(&config.llm_provider, Some(self.shared_client.clone()));

        let streaming_strategy = provider_plan
            .allow_streaming
            .then(|| streaming_insert_strategy_for_runtime(config, selected_text.as_deref()))
            .flatten();
        let mut streaming_worker = streaming_strategy.map(|strategy| {
            spawn_streaming_insert_worker(
                self.app_handle.clone(),
                self.abort_flag.clone(),
                self.context_detector.clone(),
                strategy,
                output::windows_sendinput::WindowsSendInputOptions {
                    newline_mode:
                        output::windows_sendinput::WindowsSendInputNewlineMode::from_config_value(
                            &config.windows_sendinput_newline_mode,
                        ),
                },
                app_ctx.target_guard.clone(),
                app_ctx.profile.app_label.clone(),
            )
        });
        let streaming_sender = streaming_worker
            .as_ref()
            .map(|worker| worker.sender.clone());

        // The callback remains synchronous for the LLM stream. UI updates happen
        // immediately; optional target-app insertion is drained by a worker.
        let app_handle = self.app_handle.clone();
        let on_chunk: llm::ChunkCallback = Box::new(move |chunk: &str| {
            let _ = app_handle.emit("llm:chunk", chunk);
            if let Some(sender) = streaming_sender.as_ref() {
                let _ = sender.send(chunk.to_string());
            }
        });

        let selected_text_for_execution = selected_text.clone();
        let mapped_scene_prompt = storage::automatic_scene_prompt(
            config,
            app_ctx.profile.family,
            app_ctx.mapped_scene_id.as_deref(),
        )
        .unwrap_or_default();
        let req = PolishRequest {
            raw_text: provider_text.to_string(),
            context: app_ctx.summary(),
            dictionary: dictionary_words,
            correction_rules,
            polish_style: config.polish_style.clone(),
            mapped_scene_prompt,
            active_scene_prompt: config
                .active_scene
                .as_ref()
                .map(|scene| scene.prompt_template.clone())
                .unwrap_or_default(),
            polish_custom_prompt: config.polish_custom_prompt.clone(),
            translate_enabled: config.translate_enabled,
            target_lang: config.translation.active_target.clone(),
            selected_text,
            operation_id,
            voice_intent: voice_intent.clone(),
        };

        let polish_result = provider.polish(&llm_config, &req, Some(&on_chunk)).await;
        drop(on_chunk);
        let streaming_report = match streaming_worker.take() {
            Some(worker) => worker.finish().await,
            None => None,
        };

        let polish_outcome = match polish_result {
            Ok(response) => {
                let elapsed = llm_start.elapsed();
                if let Some(report) = streaming_report.as_ref() {
                    if report.has_inserted_text() {
                        let mut streaming_history_status: Option<(&'static str, String)> = None;
                        match streaming_recovery_action(
                            report,
                            Some(&response.polished_text),
                            true,
                            !report.failed && !report.target_lost,
                        ) {
                            StreamingRecoveryAction::AlreadyComplete => {
                                self.emit_streaming_insert_result(
                                    report,
                                    &app_ctx.profile.app_label,
                                );
                            }
                            StreamingRecoveryAction::InsertSuffix { suffix } => {
                                let mut recovered_report = report.clone();
                                if !suffix.is_empty() {
                                    match output::output_stream_chunk(
                                        &self.app_handle,
                                        &suffix,
                                        report.strategy,
                                        output::windows_sendinput::WindowsSendInputOptions {
                                            newline_mode: output::windows_sendinput::WindowsSendInputNewlineMode::from_config_value(
                                                &config.windows_sendinput_newline_mode,
                                            ),
                                        },
                                    )
                                    .await
                                    {
                                        Ok(insert_result)
                                            if insert_result.status == output::InsertStatus::Inserted
                                                && insert_result.chars_inserted
                                                    == suffix.chars().count() =>
                                        {
                                            recovered_report.inserted_text =
                                                response.polished_text.clone();
                                            recovered_report.failed = false;
                                            recovered_report.error_message = None;
                                            recovered_report.attempted_chunks =
                                                recovered_report.attempted_chunks.saturating_add(1);
                                            self.emit_streaming_insert_result(
                                                &recovered_report,
                                                &app_ctx.profile.app_label,
                                            );
                                        }
                                        Ok(insert_result) => {
                                            let reason = format!(
                                                "streaming suffix insert stopped with status {:?}",
                                                insert_result.status
                                            );
                                            recovered_report.failed = true;
                                            recovered_report.error_message = Some(reason.clone());
                                            streaming_history_status =
                                                Some(("clipboard_fallback", reason.clone()));
                                            self.emit_streaming_insert_result(
                                                &recovered_report,
                                                &app_ctx.profile.app_label,
                                            );
                                            self.copy_streaming_recovery_to_clipboard(
                                                &response.polished_text,
                                                reason,
                                                &app_ctx.profile.app_label,
                                                config,
                                            )
                                            .await;
                                        }
                                        Err(error) => {
                                            recovered_report.failed = true;
                                            recovered_report.error_message = Some(error.clone());
                                            streaming_history_status =
                                                Some(("clipboard_fallback", error.clone()));
                                            self.emit_streaming_insert_result(
                                                &recovered_report,
                                                &app_ctx.profile.app_label,
                                            );
                                            self.copy_streaming_recovery_to_clipboard(
                                                &response.polished_text,
                                                error,
                                                &app_ctx.profile.app_label,
                                                config,
                                            )
                                            .await;
                                        }
                                    }
                                } else {
                                    self.emit_streaming_insert_result(
                                        &recovered_report,
                                        &app_ctx.profile.app_label,
                                    );
                                }
                            }
                            StreamingRecoveryAction::CopyFullToClipboard { reason } => {
                                let mut partial_report = report.clone();
                                partial_report.failed = true;
                                partial_report.error_message = Some(reason.clone());
                                streaming_history_status =
                                    Some(("clipboard_fallback", reason.clone()));
                                self.emit_streaming_insert_result(
                                    &partial_report,
                                    &app_ctx.profile.app_label,
                                );
                                self.copy_streaming_recovery_to_clipboard(
                                    &response.polished_text,
                                    reason,
                                    &app_ctx.profile.app_label,
                                    config,
                                )
                                .await;
                            }
                            StreamingRecoveryAction::CopyPartialToClipboard { reason } => {
                                let mut partial_report = report.clone();
                                partial_report.failed = true;
                                partial_report.error_message = Some(reason.clone());
                                streaming_history_status = Some(("partial", reason.clone()));
                                self.emit_streaming_insert_result(
                                    &partial_report,
                                    &app_ctx.profile.app_label,
                                );
                                self.copy_streaming_recovery_to_clipboard(
                                    &partial_report.inserted_text,
                                    reason,
                                    &app_ctx.profile.app_label,
                                    config,
                                )
                                .await;
                            }
                            StreamingRecoveryAction::NoRecoveryNeeded => {}
                        }
                        if let Some((status, error)) = streaming_history_status {
                            return PolishTextOutcome::with_history_status(
                                response.polished_text,
                                elapsed,
                                status,
                                error,
                            );
                        }
                        return PolishTextOutcome::normal(response.polished_text, elapsed);
                    }
                    if report.target_lost {
                        let reason = report.error_message.clone().unwrap_or_else(|| {
                            "target app changed before streaming insert".to_string()
                        });
                        self.copy_streaming_recovery_to_clipboard(
                            &response.polished_text,
                            reason,
                            &app_ctx.profile.app_label,
                            config,
                        )
                        .await;
                        return PolishTextOutcome::with_history_status(
                            response.polished_text,
                            elapsed,
                            "clipboard_fallback",
                            "Target app changed before streaming insert; copied full result to clipboard",
                        );
                    }
                    if report.failed {
                        tracing::warn!(
                            "Streaming insert failed before typing; falling back to one-shot output"
                        );
                    }
                }

                // Check abort after LLM returns — skip output if cancelled during polish.
                if self.abort_flag.load(Ordering::SeqCst) {
                    tracing::info!("Pipeline aborted after LLM polish, skipping output");
                    return PolishTextOutcome::normal(raw_text.to_string(), elapsed);
                }

                let selected_text_available = if voice_intent.placement
                    == crate::voice_intent::VoiceOutputPlacement::ReplaceSelection
                {
                    selected_text_for_execution
                        .as_deref()
                        .is_some_and(|original| {
                            tokio::task::block_in_place(crate::selection::capture_selected_text)
                                .is_some_and(|current| current.trim() == original.trim())
                        })
                } else {
                    selected_text_has_content(selected_text_for_execution.as_deref())
                };
                let mut backend = PipelineVoiceExecutionBackend {
                    pipeline: self,
                    app_name: &app_ctx.profile.app_label,
                    question: raw_text,
                    intent_kind: voice_intent.kind,
                    target_guard: &app_ctx.target_guard,
                    config,
                    already_copied: false,
                    popup_fallback_enabled,
                };
                let execution = crate::voice_intent::executor::execute_voice_intent(
                    crate::voice_intent::executor::VoiceExecutionRequest {
                        intent: &voice_intent,
                        generated_output: &response.polished_text,
                        target_guard: &app_ctx.target_guard,
                        selected_text_available,
                        restore_target_before_insert: provider_plan.restore_target_before_insert,
                        flags: config.voice_routing_flags,
                    },
                    &mut backend,
                )
                .await;
                let _ = self.app_handle.emit("pipeline:voice_execution", &execution);

                let (history_status, history_error) = match execution.status {
                    crate::voice_intent::executor::VoiceExecutionStatus::Completed => (None, None),
                    crate::voice_intent::executor::VoiceExecutionStatus::CopiedFallback => (
                        Some("clipboard_fallback".to_string()),
                        Some(format!(
                            "Voice output fallback: {:?}",
                            execution.fallback_reason
                        )),
                    ),
                    crate::voice_intent::executor::VoiceExecutionStatus::PopupFallback
                    | crate::voice_intent::executor::VoiceExecutionStatus::Prevented
                    | crate::voice_intent::executor::VoiceExecutionStatus::Failed => (
                        Some("fallback".to_string()),
                        Some(format!(
                            "Voice output fallback: {:?}",
                            execution.fallback_reason
                        )),
                    ),
                };
                PolishTextOutcome::with_execution(
                    response.polished_text,
                    elapsed,
                    execution,
                    history_status,
                    history_error,
                )
            }
            Err(e) => {
                crate::error::emit_cloud_session_invalid(&self.app_handle, &e);
                let elapsed = llm_start.elapsed();
                if let Some(report) = streaming_report.as_ref() {
                    if report.has_inserted_text() {
                        tracing::error!("LLM polish failed after partial streaming insert: {}", e);
                        let _ = self
                            .app_handle
                            .emit("pipeline:error", llm_polish_user_error(&e));
                        let mut partial_report = report.clone();
                        partial_report.failed = true;
                        partial_report.error_message = Some(format!("LLM polish failed: {}", e));
                        self.emit_streaming_insert_result(
                            &partial_report,
                            &app_ctx.profile.app_label,
                        );
                        if let StreamingRecoveryAction::CopyPartialToClipboard { reason } =
                            streaming_recovery_action(&partial_report, None, false, false)
                        {
                            self.copy_streaming_recovery_to_clipboard(
                                &partial_report.inserted_text,
                                reason,
                                &app_ctx.profile.app_label,
                                config,
                            )
                            .await;
                        }
                        return PolishTextOutcome::with_history_status(
                            partial_report.inserted_text,
                            elapsed,
                            "partial",
                            format!("LLM polish failed after partial streaming insert: {e}"),
                        );
                    }
                }

                // Check abort after LLM error — skip fallback output if cancelled.
                if self.abort_flag.load(Ordering::SeqCst) {
                    tracing::info!("Pipeline aborted after LLM error, skipping output");
                    return PolishTextOutcome::normal(String::new(), elapsed);
                }
                tracing::error!("LLM polish failed: {}", e);

                let _ = self
                    .app_handle
                    .emit("pipeline:error", llm_polish_user_error(&e));
                if selected_text_has_content(selected_text_for_execution.as_deref())
                    || voice_intent_requires_generated_output(voice_intent.kind)
                {
                    return PolishTextOutcome::with_history_status(
                        String::new(),
                        elapsed,
                        "fallback",
                        "LLM generation failed; no application text was changed",
                    );
                }
                if let Err(e) = self
                    .output_text(
                        provider_text,
                        &app_ctx.profile.app_label,
                        &app_ctx.target_guard,
                        config,
                    )
                    .await
                {
                    tracing::error!("Output failed: {}", e);
                    let _ = self
                        .app_handle
                        .emit("pipeline:error", output_user_error(&e));
                }
                PolishTextOutcome::with_history_status(
                    provider_text.to_string(),
                    elapsed,
                    "fallback",
                    format!("LLM polish failed; output raw text: {e}"),
                )
            }
        };

        tracing::info!(
            "[Pipeline Timing] LLM polish: {}ms",
            polish_outcome.llm_elapsed.as_millis()
        );

        polish_outcome
    }

    pub(crate) async fn run_ask_draft(
        &self,
        config: &storage::AppConfig,
        app_ctx: &RecordingContext,
        utterance: &str,
        operation_id: &str,
        voice_intent: crate::voice_intent::VoiceIntent,
    ) -> std::result::Result<AskVoiceDraftOutcome, String> {
        if voice_intent.kind != crate::voice_intent::VoiceIntentKind::DraftInsert {
            return Err("Ask draft execution requires a draft intent".to_string());
        }
        if self.current_state() != PipelineState::Idle {
            return Err("Another voice operation is already active".to_string());
        }
        self.abort_flag.store(false, Ordering::SeqCst);

        let dictionary_store = self.app_handle.state::<storage::DictionaryStore>();
        let dictionary_words = dictionary_store.words().await;
        let correction_rules = dictionary_store
            .enabled_correction_rules()
            .await
            .into_iter()
            .map(|rule| llm::CorrectionRule {
                id: rule.id,
                pattern: rule.pattern,
                replacement: rule.replacement,
                enabled: rule.enabled,
            })
            .collect::<Vec<_>>();
        let session_token = if config.llm_provider == "cloud" {
            self.app_handle
                .state::<SessionTokenStore>()
                .0
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .clone()
        } else {
            String::new()
        };

        let outcome = self
            .polish_text(PolishTextInput {
                raw_text: utterance,
                voice_mode: crate::voice_intent::VoiceMode::Ask,
                config,
                app_ctx,
                dictionary_words,
                correction_rules,
                selected_text: None,
                session_token,
                operation_id: Some(operation_id.to_string()),
                voice_intent,
                popup_fallback_enabled: false,
            })
            .await;
        self.set_state(PipelineState::Idle);

        let execution = outcome.voice_execution.ok_or_else(|| {
            "Draft was not generated; no application text was changed".to_string()
        })?;
        if outcome.final_text.trim().is_empty() {
            return Err("Draft generation returned empty output".to_string());
        }
        Ok(AskVoiceDraftOutcome {
            text: outcome.final_text,
            execution,
        })
    }

    /// Save the transcription to history.
    async fn save_history(
        &self,
        raw_text: &str,
        final_text: &str,
        app_ctx: &RecordingContext,
        duration_ms: Option<i64>,
        config: &storage::AppConfig,
        output: HistoryOutputMetadata,
    ) {
        let policy = config.history_retention_policy();
        if !policy.enabled {
            tracing::debug!("History save skipped because history is disabled");
            return;
        }

        let now = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
        let scene_diagnostics = active_scene_history_diagnostics(config.active_scene.as_ref());
        let entry = storage::HistoryEntry {
            id: 0, // auto-increment
            created_at: now,
            context_profile_id: app_ctx.profile.id.clone(),
            context_label: app_ctx.profile.app_label.clone(),
            context_icon_key: app_ctx.profile.icon_key.clone(),
            context_family: app_ctx.profile.family,
            browser_access_status: app_ctx.browser_access_status,
            provider_kind: history_provider_kind(config),
            raw_text: raw_text.to_string(),
            polished_text: final_text.to_string(),
            language: None,
            duration_ms,
            active_scene_id: scene_diagnostics.id,
            active_scene_source: scene_diagnostics.source,
            active_scene_name: scene_diagnostics.name,
            active_scene_prompt_chars: scene_diagnostics.prompt_chars,
            active_scene_prompt_truncated: scene_diagnostics.prompt_truncated,
            output_status: output.status,
            output_error: output.error,
        };
        if let Err(e) = self
            .app_handle
            .state::<storage::HistoryStore>()
            .add_with_policy(entry, &policy)
            .await
        {
            tracing::error!("Failed to save history: {}", e);
        }
    }

    fn emit_streaming_insert_result(&self, report: &StreamingInsertReport, app_name: &str) {
        let chars_inserted = report.chars_inserted();
        let mut insert_result = if report.failed {
            output::InsertResult::partially_inserted(report.strategy, chars_inserted)
        } else {
            output::InsertResult::inserted(report.strategy, chars_inserted)
        };

        let warning = report
            .failed
            .then(|| streaming_insert_user_error(report.error_message.clone()));
        if let Some(user_error) = warning.as_ref() {
            insert_result = insert_result.with_warning(user_error);
        }

        tracing::info!(
            "Streaming insert completed with failed={}, chunks={}, chars_inserted={}",
            report.failed,
            report.attempted_chunks,
            chars_inserted
        );
        let _ = self
            .app_handle
            .emit("pipeline:insert_result", &insert_result);
        if let Some(user_error) = warning {
            let _ = self.app_handle.emit("pipeline:warning", &user_error);
        }
        let _ = self.app_handle.emit("pipeline:target_app", app_name);
    }

    async fn copy_streaming_recovery_to_clipboard(
        &self,
        text: &str,
        reason: String,
        app_name: &str,
        config: &storage::AppConfig,
    ) {
        self.copy_text_to_clipboard_with_warning(
            text,
            app_name,
            config,
            streaming_insert_user_error(Some(format!("Full result copied to clipboard: {reason}"))),
        )
        .await;
    }

    async fn copy_text_to_clipboard_with_warning(
        &self,
        text: &str,
        app_name: &str,
        config: &storage::AppConfig,
        warning: crate::error::UserError,
    ) {
        let clipboard_options = output::clipboard::ClipboardOutputOptions {
            restore_after_paste: false,
            paste_shortcut: output::clipboard::PasteShortcut::from_config_value(
                &config.paste_shortcut,
            ),
            auto_paste: false,
        };

        match output::output_with_strategy(
            &self.app_handle,
            text,
            output::InsertionStrategy::ClipboardCopyOnly,
            clipboard_options,
            output::windows_sendinput::WindowsSendInputOptions {
                newline_mode:
                    output::windows_sendinput::WindowsSendInputNewlineMode::from_config_value(
                        &config.windows_sendinput_newline_mode,
                    ),
            },
        )
        .await
        {
            Ok(mut outcome) => {
                outcome.insert_result = outcome.insert_result.with_warning(&warning);
                let _ = self
                    .app_handle
                    .emit("pipeline:insert_result", &outcome.insert_result);
                let _ = self.app_handle.emit("pipeline:warning", &warning);
                let _ = self.app_handle.emit("pipeline:target_app", app_name);
            }
            Err(error) => {
                tracing::error!("Failed to copy streaming recovery text to clipboard: {error}");
                let error = anyhow::anyhow!(error);
                let _ = self
                    .app_handle
                    .emit("pipeline:error", output_user_error(&error));
            }
        }
    }

    async fn output_text(
        &self,
        text: &str,
        app_name: &str,
        target_guard: &TargetAppGuard,
        config: &storage::AppConfig,
    ) -> Result<output::InsertResult> {
        self.set_state(PipelineState::Outputting);

        let target_warning =
            (!self.context_detector.target_still_matches_now(target_guard)).then(|| {
                crate::error::UserError {
                    code: "output_target_changed".to_string(),
                    details: Some(
                        "The target app changed before output; the full text was copied instead."
                            .to_string(),
                    ),
                    retry_count: 0,
                }
            });
        let requested_strategy = if target_warning.is_some() {
            output::InsertionStrategy::ClipboardCopyOnly
        } else {
            output::InsertionStrategy::from_config_value(&config.insertion_strategy)
        };
        let (strategy, accessibility_warning) =
            effective_strategy_for_accessibility(requested_strategy, is_accessibility_trusted());

        // Linux: check keyboard availability before attempting
        let effective_strategy = if strategy.needs_keyboard_access() {
            if let Err(reason) = output::keyboard::check_keyboard_available() {
                if reason == "wayland_unsupported" {
                    tracing::warn!(
                        "Keyboard output not supported on Wayland, falling back to clipboard"
                    );
                    let ue = crate::error::UserError {
                        code: "output_wayland_unsupported".to_string(),
                        details: None,
                        retry_count: 0,
                    };
                    let _ = self.app_handle.emit("pipeline:warning", &ue);
                    output::InsertionStrategy::ClipboardPaste
                } else {
                    tracing::warn!("xdotool not found, keyboard output may fail: {}", reason);
                    strategy
                }
            } else {
                strategy
            }
        } else {
            strategy
        };

        let clipboard_options = output::clipboard::ClipboardOutputOptions {
            restore_after_paste: config.restore_clipboard_after_paste,
            paste_shortcut: output::clipboard::PasteShortcut::from_config_value(
                &config.paste_shortcut,
            ),
            auto_paste: true,
        };

        let mut output_outcome = match output::output_with_strategy(
            &self.app_handle,
            text,
            effective_strategy,
            clipboard_options,
            output::windows_sendinput::WindowsSendInputOptions {
                newline_mode:
                    output::windows_sendinput::WindowsSendInputNewlineMode::from_config_value(
                        &config.windows_sendinput_newline_mode,
                    ),
            },
        )
        .await
        {
            Ok(outcome) => outcome,
            Err(e) => anyhow::bail!("{}", e),
        };

        if let Some(user_error) = target_warning.or(accessibility_warning) {
            output_outcome.insert_result = output_outcome.insert_result.with_warning(&user_error);
            output_outcome.warning.get_or_insert(user_error);
        }

        tracing::info!(
            "Output completed with status={:?}, strategy={:?}, chars_inserted={}",
            output_outcome.insert_result.status,
            output_outcome.insert_result.strategy_used,
            output_outcome.insert_result.chars_inserted
        );
        let insert_result = output_outcome.insert_result.clone();
        let _ = self
            .app_handle
            .emit("pipeline:insert_result", &insert_result);

        if let Some(user_error) = output_outcome.warning {
            tracing::info!("Output completed with warning: {}", user_error.code);
            let _ = self.app_handle.emit("pipeline:warning", &user_error);
        }

        let _ = self.app_handle.emit("pipeline:target_app", app_name);
        Ok(insert_result)
    }

    async fn pre_warm_endpoint(&self, endpoint: &str, managed_cloud: bool) {
        tracing::debug!("Pre-warming HTTP connection to {}", endpoint);
        let request = self.shared_client.head(endpoint);
        let request = if managed_cloud {
            crate::with_desktop_client_version(request)
        } else {
            request
        };
        if let Err(error) = request
            .timeout(std::time::Duration::from_secs(5))
            .send()
            .await
        {
            tracing::debug!("HTTP connection pre-warm did not complete: {error}");
        }
    }

    /// Pre-warm the active STT and LLM hosts concurrently so the next dictation can reuse TLS.
    /// Called after startup and whenever provider connection settings change.
    pub async fn pre_warm(&self) {
        let config = self.load_config().await;

        let stt_endpoint = match config.stt_provider.as_str() {
            "cloud" => {
                let base = crate::api_base_url();
                Some((format!("{}/api/proxy/stt", base), true))
            }
            "glm-asr" => Some((
                "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions".to_string(),
                false,
            )),
            "openai-whisper" => Some((
                "https://api.openai.com/v1/audio/transcriptions".to_string(),
                false,
            )),
            "groq-whisper" => Some((
                "https://api.groq.com/openai/v1/audio/transcriptions".to_string(),
                false,
            )),
            "siliconflow" => Some((
                "https://api.siliconflow.cn/v1/audio/transcriptions".to_string(),
                false,
            )),
            "deepgram" => Some(("https://api.deepgram.com/v1/listen".to_string(), false)),
            "assemblyai" => Some((
                "https://api.assemblyai.com/v2/transcript".to_string(),
                false,
            )),
            stt::volcengine::VOLCENGINE_DOUBAO_PROVIDER => Some((
                "https://openspeech.bytedance.com/api/v3/sauc/bigmodel_async".to_string(),
                false,
            )),
            stt::config::CUSTOM_WHISPER_PROVIDER => {
                stt::config::normalize_custom_whisper_endpoint(&config.stt_custom_base_url)
                    .ok()
                    .map(|endpoint| (endpoint, false))
            }
            _ => {
                tracing::debug!(
                    "Unknown STT provider '{}', skipping pre-warm",
                    config.stt_provider
                );
                None
            }
        };

        let llm_endpoint = if config.polish_enabled {
            if config.llm_provider == "cloud" {
                let base = crate::api_base_url();
                Some((format!("{}/api/proxy/llm", base), true))
            } else {
                crate::llm::protocol::chat_endpoint(&config.llm_provider, &config.llm_base_url)
                    .ok()
                    .map(|endpoint| (endpoint, false))
            }
        } else {
            None
        };

        let warm_stt = async {
            if let Some((endpoint, managed_cloud)) = stt_endpoint {
                self.pre_warm_endpoint(&endpoint, managed_cloud).await;
            }
        };
        let warm_llm = async {
            if let Some((endpoint, managed_cloud)) = llm_endpoint {
                self.pre_warm_endpoint(&endpoint, managed_cloud).await;
            }
        };
        tokio::join!(warm_stt, warm_llm);
        tracing::debug!("Provider connection pre-warm complete");
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicU64};

    #[test]
    fn preparing_state_serializes_for_frontend() {
        assert_eq!(
            serde_json::to_value(PipelineState::Preparing).unwrap(),
            serde_json::json!("preparing")
        );
        assert_eq!(
            PipelineState::from_u8(PipelineState::Preparing.as_u8()),
            PipelineState::Preparing
        );
    }

    #[test]
    fn ask_states_serialize_for_frontend() {
        assert_eq!(
            serde_json::to_value(PipelineState::AskRecording).unwrap(),
            serde_json::json!("ask_recording")
        );
        assert_eq!(
            serde_json::to_value(PipelineState::AskThinking).unwrap(),
            serde_json::json!("ask_thinking")
        );
        assert_eq!(
            PipelineState::from_u8(PipelineState::AskRecording.as_u8()),
            PipelineState::AskRecording
        );
    }

    #[test]
    fn output_user_error_preserves_accessibility_required() {
        let err = anyhow::anyhow!("Output failed: ACCESSIBILITY_REQUIRED");
        let user_error = output_user_error(&err);

        assert_eq!(user_error.code, "accessibility_required");
        assert_eq!(user_error.details, None);
    }

    #[test]
    fn output_user_error_keeps_non_permission_details() {
        let err = anyhow::anyhow!("Both keyboard and clipboard output failed");
        let user_error = output_user_error(&err);

        assert_eq!(user_error.code, "output_failed");
        assert_eq!(
            user_error.details.as_deref(),
            Some("Both keyboard and clipboard output failed")
        );
    }

    #[test]
    fn accessibility_fallback_copies_when_auto_requires_accessibility() {
        let (strategy, warning) =
            effective_strategy_for_accessibility(output::InsertionStrategy::Auto, false);

        assert_eq!(strategy, output::InsertionStrategy::ClipboardCopyOnly);
        assert_eq!(
            warning.as_ref().map(|warning| warning.code.as_str()),
            Some("accessibility_required")
        );
    }

    #[test]
    fn accessibility_fallback_copies_when_clipboard_paste_requires_accessibility() {
        let (strategy, warning) =
            effective_strategy_for_accessibility(output::InsertionStrategy::ClipboardPaste, false);

        assert_eq!(strategy, output::InsertionStrategy::ClipboardCopyOnly);
        assert_eq!(
            warning.as_ref().map(|warning| warning.code.as_str()),
            Some("accessibility_required")
        );
    }

    #[test]
    fn accessibility_fallback_keeps_strategy_when_accessibility_is_trusted() {
        let (strategy, warning) =
            effective_strategy_for_accessibility(output::InsertionStrategy::Keyboard, true);

        assert_eq!(strategy, output::InsertionStrategy::Keyboard);
        assert!(warning.is_none());
    }

    #[test]
    fn accessibility_fallback_keeps_copy_only_without_accessibility() {
        let (strategy, warning) = effective_strategy_for_accessibility(
            output::InsertionStrategy::ClipboardCopyOnly,
            false,
        );

        assert_eq!(strategy, output::InsertionStrategy::ClipboardCopyOnly);
        assert!(warning.is_none());
    }

    #[test]
    fn streaming_insert_strategy_defaults_to_disabled() {
        let config = storage::AppConfig::default();

        assert_eq!(
            streaming_insert_strategy_for_config(&config, false, true, true),
            None
        );
    }

    #[test]
    fn streaming_insert_strategy_uses_keyboard_for_auto_when_safe() {
        let mut config = storage::AppConfig {
            streaming_insert_enabled: true,
            ..storage::AppConfig::default()
        };
        config.insertion_strategy = "auto".to_string();

        assert_eq!(
            streaming_insert_strategy_for_config(&config, false, true, true),
            Some(output::InsertionStrategy::Keyboard)
        );
    }

    #[test]
    fn streaming_insert_strategy_rejects_selected_text_and_clipboard_paths() {
        let mut config = storage::AppConfig {
            streaming_insert_enabled: true,
            ..storage::AppConfig::default()
        };

        assert_eq!(
            streaming_insert_strategy_for_config(&config, true, true, true),
            None
        );

        config.insertion_strategy = "clipboardPaste".to_string();
        assert_eq!(
            streaming_insert_strategy_for_config(&config, false, true, true),
            None
        );
    }

    #[test]
    fn streaming_insert_strategy_requires_accessibility_and_keyboard_availability() {
        let config = storage::AppConfig {
            streaming_insert_enabled: true,
            ..storage::AppConfig::default()
        };

        assert_eq!(
            streaming_insert_strategy_for_config(&config, false, false, true),
            None
        );
        assert_eq!(
            streaming_insert_strategy_for_config(&config, false, true, false),
            None
        );
    }

    #[test]
    fn streaming_insert_strategy_allows_windows_sendinput_without_keyboard_check() {
        let mut config = storage::AppConfig {
            streaming_insert_enabled: true,
            ..storage::AppConfig::default()
        };
        config.insertion_strategy = "windowsSendInput".to_string();

        assert_eq!(
            streaming_insert_strategy_for_config(&config, false, true, false),
            Some(output::InsertionStrategy::WindowsSendInput)
        );
    }

    fn streaming_report_for_test(inserted_text: &str, failed: bool) -> StreamingInsertReport {
        StreamingInsertReport {
            strategy: output::InsertionStrategy::Keyboard,
            inserted_text: inserted_text.to_string(),
            attempted_chunks: 1,
            failed,
            target_lost: false,
            error_message: failed.then(|| "streaming failed".to_string()),
        }
    }

    #[test]
    fn streaming_target_guard_blocks_process_changes() {
        let expected = TargetAppGuard {
            process_id: Some(42),
            native_identity: Some("com.example.notes".to_string()),
        };
        assert!(expected.matches(&expected));
        assert!(!expected.matches(&TargetAppGuard {
            process_id: Some(99),
            native_identity: Some("com.example.browser".to_string()),
        }));
    }

    #[test]
    fn streaming_recovery_copies_full_when_target_is_not_trusted() {
        let report = streaming_report_for_test("Hello", true);

        assert_eq!(
            streaming_recovery_action(&report, Some("Hello world"), true, false),
            StreamingRecoveryAction::CopyFullToClipboard {
                reason: "target app changed after partial streaming insert".to_string()
            }
        );
    }

    #[test]
    fn streaming_recovery_uses_char_safe_suffix_for_cjk_text() {
        let report = streaming_report_for_test("你好", true);

        assert_eq!(
            streaming_recovery_action(&report, Some("你好，世界"), true, true),
            StreamingRecoveryAction::InsertSuffix {
                suffix: "，世界".to_string()
            }
        );
    }

    #[test]
    fn streaming_recovery_copies_partial_when_llm_fails_after_insert() {
        let report = streaming_report_for_test("partial", true);

        assert_eq!(
            streaming_recovery_action(&report, None, false, false),
            StreamingRecoveryAction::CopyPartialToClipboard {
                reason: "LLM failed after partial streaming insert".to_string()
            }
        );
    }

    #[test]
    fn pipeline_start_options_force_translation_for_current_run_only() {
        let config = storage::AppConfig {
            translate_enabled: false,
            target_lang: "ja".to_string(),
            translation: storage::TranslationConfig {
                targets: vec!["ja".to_string()],
                active_target: "ja".to_string(),
            },
            ..storage::AppConfig::default()
        };

        let next_config = apply_pipeline_start_options(
            config.clone(),
            PipelineStartOptions {
                force_translate: true,
            },
        );

        assert!(next_config.translate_enabled);
        assert_eq!(next_config.target_lang, "ja");
        assert!(!config.translate_enabled);
    }

    #[test]
    fn switch_translation_target_updates_capture_without_restart_and_freezes_at_finalization() {
        let mut operation = TranslationOperationState::new("ja".to_string());
        let previous = operation.switch_target("fr".to_string()).unwrap();
        assert_eq!(previous, "ja");
        assert_eq!(operation.phase, TranslationOperationPhase::Capturing);
        assert_eq!(operation.finalize(), "fr");
        assert_eq!(operation.phase, TranslationOperationPhase::Finalizing);
        assert_eq!(
            operation.switch_target("de".to_string()),
            Err("translation_operation_finished")
        );
        assert_eq!(operation.target, "fr");
    }

    #[test]
    fn selected_text_output_policy_copies_non_destructive_questions() {
        let mut config = storage::AppConfig {
            stt_language: "zh-Hans".to_string(),
            ..Default::default()
        };
        let chinese = route_pipeline_voice_intent(
            crate::voice_intent::VoiceMode::Dictate,
            "这段是什么意思",
            Some("selected text"),
            &config,
        );
        assert_eq!(
            chinese.placement,
            crate::voice_intent::VoiceOutputPlacement::PopupAnswer
        );
        config.stt_language = "en".to_string();
        let english = route_pipeline_voice_intent(
            crate::voice_intent::VoiceMode::Dictate,
            "summarize this",
            Some("selected text"),
            &config,
        );
        assert_eq!(
            english.placement,
            crate::voice_intent::VoiceOutputPlacement::PopupAnswer
        );
    }

    #[test]
    fn selected_text_output_policy_replaces_for_explicit_editing() {
        let mut config = storage::AppConfig {
            stt_language: "zh-Hans".to_string(),
            ..Default::default()
        };
        let rewrite = route_pipeline_voice_intent(
            crate::voice_intent::VoiceMode::Dictate,
            "润色这段",
            Some("selected text"),
            &config,
        );
        assert_eq!(
            rewrite.placement,
            crate::voice_intent::VoiceOutputPlacement::ReplaceSelection
        );
        config.stt_language = "en".to_string();
        let translation = route_pipeline_voice_intent(
            crate::voice_intent::VoiceMode::Dictate,
            "translate this to English",
            Some("selected text"),
            &config,
        );
        assert_eq!(
            translation.placement,
            crate::voice_intent::VoiceOutputPlacement::ReplaceSelection
        );
    }

    #[test]
    fn selected_text_without_llm_polish_blocks_raw_instruction_output() {
        assert!(selected_text_command_requires_llm(Some("selected text")));
        assert!(!selected_text_command_requires_llm(None));
        assert!(!selected_text_command_requires_llm(Some(" \n\t ")));
    }

    #[test]
    fn shared_voice_router_pipeline_keeps_discussed_commands_nondestructive() {
        let config = storage::AppConfig {
            stt_language: "en".to_string(),
            ..Default::default()
        };

        let ordinary = route_pipeline_voice_intent(
            crate::voice_intent::VoiceMode::Dictate,
            "I need to draft tomorrow",
            None,
            &config,
        );
        assert_eq!(
            ordinary.kind,
            crate::voice_intent::VoiceIntentKind::DictateInsert
        );

        let selected = route_pipeline_voice_intent(
            crate::voice_intent::VoiceMode::Dictate,
            "do not rewrite this",
            Some("selected text"),
            &config,
        );
        assert_eq!(
            selected.kind,
            crate::voice_intent::VoiceIntentKind::AskSelection
        );
        assert_eq!(
            selected.placement,
            crate::voice_intent::VoiceOutputPlacement::PopupAnswer
        );
    }

    #[test]
    fn stt_task_should_not_finalize_when_abort_flag_is_set() {
        let abort_flag = AtomicBool::new(true);
        let active_session_id = AtomicU64::new(7);

        assert!(!should_finalize_stt_task(
            &abort_flag,
            &active_session_id,
            7
        ));
    }

    #[test]
    fn stt_task_should_not_finalize_when_session_is_stale() {
        let abort_flag = AtomicBool::new(false);
        let active_session_id = AtomicU64::new(8);

        assert!(!should_finalize_stt_task(
            &abort_flag,
            &active_session_id,
            7
        ));
    }

    #[test]
    fn stt_task_should_finalize_when_session_is_active_and_not_aborted() {
        let abort_flag = AtomicBool::new(false);
        let active_session_id = AtomicU64::new(7);

        assert!(should_finalize_stt_task(&abort_flag, &active_session_id, 7));
    }

    #[test]
    fn session_error_latch_returns_matching_error() {
        let latch = Mutex::new(Some((
            7,
            crate::error::UserError {
                code: "stt_quota_exceeded".to_string(),
                details: Some("quota".to_string()),
                retry_count: 0,
            },
        )));

        let err = take_matching_stt_error(&latch, 7).unwrap();
        assert_eq!(err.code, "stt_quota_exceeded");
        assert!(latch.lock().unwrap().is_none());
    }

    #[test]
    fn session_error_latch_ignores_stale_error() {
        let latch = Mutex::new(Some((
            6,
            crate::error::UserError {
                code: "stt_quota_exceeded".to_string(),
                details: Some("quota".to_string()),
                retry_count: 0,
            },
        )));

        assert!(take_matching_stt_error(&latch, 7).is_none());
        assert!(latch.lock().unwrap().is_some());
    }

    #[test]
    fn send_audio_error_is_latched_only_for_the_active_session() {
        let abort_flag = AtomicBool::new(false);
        let active_session_id = AtomicU64::new(7);
        let latch = Mutex::new(None);
        let error = crate::error::AppError::Network("socket closed".to_string());

        let user_error =
            latch_stt_task_error_if_active(&abort_flag, &active_session_id, &latch, 7, &error)
                .expect("active send failure should be surfaced");

        assert_eq!(user_error.code, "stt_timeout");
        assert_eq!(
            take_matching_stt_error(&latch, 7)
                .expect("matching error should be consumable")
                .code,
            "stt_timeout"
        );

        assert!(
            latch_stt_task_error_if_active(&abort_flag, &active_session_id, &latch, 6, &error,)
                .is_none()
        );
    }

    #[test]
    fn no_speech_user_error_has_localizable_code() {
        let err = no_speech_user_error();
        assert_eq!(err.code, "stt_no_speech_detected");
        assert_eq!(err.retry_count, 0);
    }

    #[test]
    fn llm_polish_user_error_keeps_quota_code() {
        let err = llm_polish_user_error(&crate::error::AppError::LlmQuota(
            "quota exceeded".to_string(),
        ));
        assert_eq!(err.code, "llm_quota_exceeded");
        assert_eq!(err.details.as_deref(), Some("quota exceeded"));
    }

    #[test]
    fn llm_polish_user_error_uses_localizable_fallback_code() {
        let err = llm_polish_user_error(&crate::error::AppError::Auth(
            "Cloud LLM access denied".to_string(),
        ));
        assert_eq!(err.code, "llm_failed");
        assert_eq!(
            err.details.as_deref(),
            Some("Auth error: Cloud LLM access denied")
        );
    }

    #[test]
    fn active_scene_history_diagnostics_record_scene_metadata() {
        let scene = storage::ActiveScene {
            id: "builtin_meeting_notes".to_string(),
            source: "builtin".to_string(),
            name: "Meeting Notes".to_string(),
            prompt_template: format!("{}{}", "x".repeat(4_000), "overflow"),
        };

        let diagnostics = active_scene_history_diagnostics(Some(&scene));

        assert_eq!(diagnostics.id.as_deref(), Some("builtin_meeting_notes"));
        assert_eq!(diagnostics.source.as_deref(), Some("builtin"));
        assert_eq!(diagnostics.name.as_deref(), Some("Meeting Notes"));
        assert_eq!(diagnostics.prompt_chars, Some(4_000));
        assert!(diagnostics.prompt_truncated);
    }

    #[test]
    fn active_scene_history_diagnostics_are_empty_without_scene() {
        let diagnostics = active_scene_history_diagnostics(None);

        assert_eq!(diagnostics.id, None);
        assert_eq!(diagnostics.source, None);
        assert_eq!(diagnostics.name, None);
        assert_eq!(diagnostics.prompt_chars, None);
        assert!(!diagnostics.prompt_truncated);
    }

    #[test]
    fn history_provider_kind_uses_only_provider_classification() {
        let mut config = storage::AppConfig {
            polish_enabled: true,
            llm_provider: "cloud".to_string(),
            ..Default::default()
        };
        assert_eq!(
            history_provider_kind(&config),
            storage::HistoryProviderKind::ManagedCloud
        );

        config.llm_provider = "openrouter".to_string();
        assert_eq!(
            history_provider_kind(&config),
            storage::HistoryProviderKind::Byok
        );

        config.llm_provider = "ollama".to_string();
        assert_eq!(
            history_provider_kind(&config),
            storage::HistoryProviderKind::Local
        );
    }
}
