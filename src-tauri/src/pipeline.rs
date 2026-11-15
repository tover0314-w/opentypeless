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
use crate::voice_operation::{OnDrop, VoiceOperationToken, VoiceOperations};
use crate::SessionTokenStore;

fn claim_pipeline_state(state: &AtomicU8, from: PipelineState, to: PipelineState) -> bool {
    state
        .compare_exchange(from.as_u8(), to.as_u8(), Ordering::SeqCst, Ordering::SeqCst)
        .is_ok()
}

/// Claims `from → to` and publishes it under the operation lock, so no abort can slip between.
fn claim_pipeline_operation(
    operations: &VoiceOperations,
    state: &AtomicU8,
    from: PipelineState,
    to: PipelineState,
    publish: impl FnOnce(),
) -> Option<VoiceOperationToken> {
    let claim = || {
        let claimed = claim_pipeline_state(state, from, to);
        if claimed {
            publish();
        }
        claimed
    };
    if from == PipelineState::Idle {
        operations.begin_if(claim)
    } else {
        operations.adopt_if(claim)
    }
}

fn acquire_voice_start_guard(
    lock: &Arc<tokio::sync::Mutex<()>>,
) -> std::result::Result<tokio::sync::OwnedMutexGuard<()>, String> {
    lock.clone()
        .try_lock_owned()
        .map_err(|_| "Another voice operation is busy".to_string())
}

async fn acquire_voice_stop_guard(
    lock: &Arc<tokio::sync::Mutex<()>>,
    practice_busy: bool,
) -> Option<tokio::sync::OwnedMutexGuard<()>> {
    if practice_busy {
        return None;
    }
    Some(lock.clone().lock_owned().await)
}

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

pub(crate) fn llm_polish_user_error(error: &crate::error::AppError) -> crate::error::UserError {
    crate::error::UserError {
        code: if matches!(error, crate::error::AppError::LlmQuota(_)) {
            "llm_quota_exceeded"
        } else {
            "llm_failed"
        }
        .to_string(),
        details: Some(polish_history_error(error)),
        retry_count: 0,
    }
}

fn polish_history_error(error: &crate::error::AppError) -> String {
    let reason = match error {
        crate::error::AppError::Api { status, .. } => format!("HTTP {status}"),
        crate::error::AppError::LlmQuota(_) => "llm_quota_exceeded".into(),
        crate::error::AppError::Quota(_) => "quota_exceeded".into(),
        crate::error::AppError::Timeout(_) => "timeout".into(),
        crate::error::AppError::Network(_) => "network".into(),
        crate::error::AppError::Auth(_) => "authentication".into(),
        crate::error::AppError::CloudSessionInvalid => "session_invalid".into(),
        _ => "llm_failed".into(),
    };
    format!("LLM polish failed: {reason}")
}

fn can_use_polish_provider(
    config: &storage::AppConfig,
    credential_read_failed: bool,
    api_key: &str,
) -> bool {
    config.polish_enabled
        && !credential_read_failed
        && (config.llm_provider == "cloud"
            || llm::has_usable_provider_credentials(&config.llm_provider, api_key))
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

pub(crate) fn effective_strategy_for_accessibility(
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
    operation: VoiceOperationToken,
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
            operation,
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
    operation: VoiceOperationToken,
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
        operation,
        context_detector,
        strategy,
        windows_sendinput_options,
        expected_target_guard,
        expected_target_label,
    } = context;
    let mut report = StreamingInsertReport::new(strategy);

    while let Some(Some(chunk)) =
        crate::retry_audio::cancellable_retry(operation.cancel_receiver(), receiver.recv()).await
    {
        if operation.is_cancelled() {
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
        let guard_operation = operation.clone();
        let guard_detector = context_detector.clone();
        let guard_target = expected_target_guard.clone();
        let guard = Arc::new(move || {
            !guard_operation.is_cancelled()
                && guard_detector.target_still_matches_now(&guard_target)
        });
        match output::output_stream_chunk_with_guard(
            &app_handle,
            &chunk,
            strategy,
            windows_sendinput_options,
            guard,
        )
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
    id: String,
    target: String,
    phase: TranslationOperationPhase,
}

impl TranslationOperationState {
    fn new(target: String) -> Self {
        Self {
            id: uuid::Uuid::new_v4().to_string(),
            target,
            phase: TranslationOperationPhase::Capturing,
        }
    }

    fn switch_target(&mut self, target: String) -> std::result::Result<String, &'static str> {
        if self.phase != TranslationOperationPhase::Capturing {
            return Err("translation_operation_finished");
        }
        self.id = uuid::Uuid::new_v4().to_string();
        Ok(std::mem::replace(&mut self.target, target))
    }

    fn finalize(&mut self) -> String {
        self.phase = TranslationOperationPhase::Finalizing;
        self.target.clone()
    }

    fn switch_owned_target(
        &mut self,
        expected: &str,
        target: String,
    ) -> std::result::Result<String, &'static str> {
        if self.id != expected {
            return Err("translation_operation_finished");
        }
        self.switch_target(target)
    }
}

fn apply_pipeline_start_options(
    mut config: storage::AppConfig,
    options: PipelineStartOptions,
) -> storage::AppConfig {
    if options.force_translate {
        config.translate_enabled = true;
        config.polish_enabled = true;
    }
    config
}

struct LearningOwner {
    session_epoch: String,
    generation: Option<u64>,
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
    operations: VoiceOperations,
    preloaded_config: Arc<Mutex<Option<storage::AppConfig>>>,
    preloaded_app_ctx: Arc<Mutex<Option<RecordingContext>>>,
    preloaded_dictionary: Arc<Mutex<Option<Vec<String>>>>,
    preloaded_correction_rules: Arc<Mutex<Option<Vec<llm::CorrectionRule>>>>,
    preloaded_selected_text: Arc<Mutex<Option<String>>>,
    preloaded_voice_mode: Arc<Mutex<Option<crate::voice_intent::VoiceMode>>>,
    preloaded_learning_owner: Arc<Mutex<Option<LearningOwner>>>,
    cloud_operation_id: Arc<Mutex<Option<String>>>,
    recording_start: Arc<Mutex<Option<std::time::Instant>>>,
    active_translation_operation: Arc<Mutex<Option<TranslationOperationState>>>,
    shared_client: reqwest::Client,
    retry_audio: Arc<Mutex<Option<crate::retry_audio::RetryAudio>>>,
    history_retry_operation: Arc<Mutex<Option<VoiceOperationToken>>>,
    recording_archive: Arc<Mutex<Option<RecordingArchive>>>,
    /// Serializes start()/stop() so that stop() waits for start() to finish
    /// its setup before reading shared state (preloaded_config, audio_handle, etc.).
    /// Without this, a quick press-release in hold mode causes stop() to run
    /// while start() is still connecting to STT, finding empty fields.
    pipeline_lock: Arc<tokio::sync::Mutex<()>>,
}

struct PolishTextInput<'a> {
    operation: &'a VoiceOperationToken,
    raw_text: &'a str,
    voice_mode: crate::voice_intent::VoiceMode,
    config: &'a storage::AppConfig,
    app_ctx: &'a RecordingContext,
    dictionary_words: Vec<String>,
    correction_rules: Vec<llm::CorrectionRule>,
    selected_text: Option<String>,
    session_token: String,
    session_epoch: Option<String>,
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

#[derive(Debug, Clone, PartialEq, Eq)]
struct HistoryOutputMetadata {
    status: Option<String>,
    error: Option<String>,
}

impl HistoryOutputMetadata {
    fn dismissed() -> Self {
        Self {
            status: Some(storage::HISTORY_STATUS_DISMISSED.to_string()),
            error: None,
        }
    }

    fn silent() -> Self {
        Self {
            status: Some(storage::HISTORY_STATUS_SILENT.to_string()),
            error: None,
        }
    }

    fn recognition_failed() -> Self {
        Self {
            status: Some(storage::HISTORY_STATUS_RECOGNITION_FAILED.to_string()),
            error: Some("Recognition did not produce a completed transcript".to_string()),
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
enum SttWaitOutcome {
    Text(String),
    NoSpeech,
    /// Aborted, superseded, timed out or failed before a transcript completed.
    Interrupted,
}

/// History status for a take that ended without a transcript: dismissed by the user, silent, or
/// interrupted. Superseded or system-aborted takes (`keeps_failed_take == false`) are not kept.
/// Saved recordings can only be re-recognized by providers that accept a whole file.
fn supports_archived_audio_recognition(provider: &str) -> bool {
    provider == stt::config::APPLE_SPEECH_PROVIDER
        || provider == stt::config::CUSTOM_WHISPER_PROVIDER
        || stt::config::get_whisper_config(provider).is_some()
}

/// Typeless offers Undo on a cancelled take and Retry on a failed one; both re-run the stored
/// recording, so they need a file-based provider and are not offered for Ask.
fn take_recovery_event(
    status: Option<&str>,
    voice_mode: crate::voice_intent::VoiceMode,
    provider: &str,
) -> Option<&'static str> {
    if voice_mode == crate::voice_intent::VoiceMode::Ask
        || !supports_archived_audio_recognition(provider)
    {
        return None;
    }
    match status {
        Some(storage::HISTORY_STATUS_DISMISSED) => Some("pipeline:dismissed"),
        Some(storage::HISTORY_STATUS_RECOGNITION_FAILED) => Some("pipeline:failed_take"),
        _ => None,
    }
}

fn voice_mode_from_history(mode: &str) -> crate::voice_intent::VoiceMode {
    match mode {
        "translate" => crate::voice_intent::VoiceMode::Translate,
        "ask" => crate::voice_intent::VoiceMode::Ask,
        _ => crate::voice_intent::VoiceMode::Dictate,
    }
}

fn unfinished_take_status(
    outcome: &SttWaitOutcome,
    dismissed: bool,
    keeps_failed_take: bool,
) -> Option<HistoryOutputMetadata> {
    match outcome {
        SttWaitOutcome::Text(_) => None,
        _ if dismissed => Some(HistoryOutputMetadata::dismissed()),
        _ if !keeps_failed_take => None,
        SttWaitOutcome::NoSpeech => Some(HistoryOutputMetadata::silent()),
        SttWaitOutcome::Interrupted => Some(HistoryOutputMetadata::recognition_failed()),
    }
}

type RecordingArchive = Arc<Mutex<crate::audio::archive::ArchiveBuffer>>;

struct DismissedTake {
    session_id: Option<u64>,
    archive: RecordingArchive,
    config: storage::AppConfig,
    app_ctx: RecordingContext,
    voice_mode: crate::voice_intent::VoiceMode,
    duration_ms: Option<i64>,
}

struct TakeRecoveryContext {
    duration_ms: Option<i64>,
    session_id: Option<u64>,
}

fn take_archived_wav(archive: Option<RecordingArchive>) -> Option<Vec<u8>> {
    archive.and_then(|archive| {
        archive
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take_wav()
    })
}

struct HistorySaveContext {
    voice_mode: crate::voice_intent::VoiceMode,
    output: HistoryOutputMetadata,
    audio: Option<Vec<u8>>,
}

fn prepare_history_retention(
    config: &storage::AppConfig,
    current: Option<(storage::HistoryRetentionPolicy, bool)>,
    audio: Option<Vec<u8>>,
    status: Option<&str>,
) -> Option<(storage::HistoryRetentionPolicy, Option<Vec<u8>>)> {
    let (policy, save_audio) = current?;
    if !config.history_enabled || !policy.enabled {
        return None;
    }
    let audio = if save_audio && config.save_recording_audio {
        audio
    } else {
        None
    };
    if audio.is_none() && storage::is_recording_only_status(status) {
        return None;
    }
    Some((policy, audio))
}

fn history_output_metadata_for_insert_result(
    insert_result: &output::InsertResult,
    intentional_clipboard_copy: bool,
) -> HistoryOutputMetadata {
    let status = match insert_result.status {
        output::InsertStatus::PartiallyInserted => "partial",
        output::InsertStatus::Failed => "failed",
        output::InsertStatus::CopiedFallback
            if intentional_clipboard_copy && insert_result.warning_code.is_none() =>
        {
            "copied"
        }
        output::InsertStatus::CopiedFallback => "clipboard_fallback",
        output::InsertStatus::Inserted if insert_result.warning_code.is_some() => "fallback",
        output::InsertStatus::Inserted => "inserted",
    };

    HistoryOutputMetadata {
        status: Some(status.to_string()),
        error: insert_result.message.clone(),
    }
}

fn history_output_metadata_for_output_result(
    output_result: std::result::Result<&output::InsertResult, &anyhow::Error>,
    intentional_clipboard_copy: bool,
) -> HistoryOutputMetadata {
    match output_result {
        Ok(insert_result) => {
            history_output_metadata_for_insert_result(insert_result, intentional_clipboard_copy)
        }
        Err(error) => HistoryOutputMetadata {
            status: Some("failed".to_string()),
            error: Some(error.to_string()),
        },
    }
}

fn history_output_metadata_with_prior_error(
    output_result: std::result::Result<&output::InsertResult, &anyhow::Error>,
    intentional_clipboard_copy: bool,
    prior_error: &str,
) -> HistoryOutputMetadata {
    let mut metadata =
        history_output_metadata_for_output_result(output_result, intentional_clipboard_copy);
    if matches!(metadata.status.as_deref(), Some("inserted" | "copied")) {
        metadata.status = Some("fallback".to_string());
    }
    metadata.error = Some(match metadata.error {
        Some(output_error) => format!("{prior_error}; raw output: {output_error}"),
        None => format!("{prior_error}; output raw text"),
    });
    metadata
}

/// True when the dictation ended up typed into the focused field, so the user may correct it.
fn outcome_inserted_into_field(outcome: &PolishTextOutcome) -> bool {
    use crate::voice_intent::executor::VoiceExecutionStatus;
    use crate::voice_intent::VoiceOutputPlacement;
    if outcome.final_text.trim().is_empty() {
        return false;
    }
    match &outcome.voice_execution {
        Some(execution) => {
            execution.status == VoiceExecutionStatus::Completed
                && matches!(
                    execution.actual_placement,
                    Some(
                        VoiceOutputPlacement::InsertAtCursor
                            | VoiceOutputPlacement::ReplaceSelection
                    )
                )
        }
        None => matches!(
            outcome.history_output_status.as_deref(),
            None | Some("inserted")
        ),
    }
}

struct PipelineVoiceExecutionBackend<'a> {
    pipeline: &'a PipelineHandle,
    operation: &'a VoiceOperationToken,
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
        if self.operation.is_cancelled() {
            return Err("voice operation was cancelled".to_string());
        }
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
            .output_text(
                self.operation,
                text,
                self.app_name,
                self.target_guard,
                self.config,
            )
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
        if self.operation.is_cancelled() {
            return Err("voice operation was cancelled".to_string());
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
                self.operation,
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
            operations: VoiceOperations::default(),
            preloaded_config: Arc::new(Mutex::new(None)),
            preloaded_app_ctx: Arc::new(Mutex::new(None)),
            preloaded_dictionary: Arc::new(Mutex::new(None)),
            preloaded_correction_rules: Arc::new(Mutex::new(None)),
            preloaded_selected_text: Arc::new(Mutex::new(None)),
            preloaded_voice_mode: Arc::new(Mutex::new(None)),
            preloaded_learning_owner: Arc::new(Mutex::new(None)),
            cloud_operation_id: Arc::new(Mutex::new(None)),
            recording_start: Arc::new(Mutex::new(None)),
            active_translation_operation: Arc::new(Mutex::new(None)),
            shared_client,
            retry_audio: Arc::new(Mutex::new(None)),
            history_retry_operation: Arc::new(Mutex::new(None)),
            recording_archive: Arc::new(Mutex::new(None)),
            pipeline_lock: Arc::new(tokio::sync::Mutex::new(())),
        }
    }

    /// Never waits on the main thread, so it may run under the voice-operation lock.
    fn publish_state(&self, new_state: PipelineState) {
        self.state.store(new_state.as_u8(), Ordering::SeqCst);
        crate::audio::output_mute::on_recording_changed(
            &self.app_handle,
            new_state == PipelineState::Recording,
        );
        crate::escape_cancel::on_recording_changed(
            &self.app_handle,
            new_state == PipelineState::Recording,
        );
        if new_state == PipelineState::Idle {
            crate::hotkey::recording_input_finished(&self.app_handle);
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
    }

    fn refresh_tray_state(&self) {
        if let Some(tray_handle) = self.app_handle.try_state::<crate::TrayHandle>() {
            let tooltip = match self.current_state() {
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

    fn claim_operation(
        &self,
        from: PipelineState,
        to: PipelineState,
    ) -> Option<VoiceOperationToken> {
        let operation = claim_pipeline_operation(&self.operations, &self.state, from, to, || {
            self.publish_state(to)
        })?;
        self.refresh_tray_state();
        Some(operation)
    }

    fn begin_operation(&self, next: PipelineState) -> Option<VoiceOperationToken> {
        self.claim_operation(PipelineState::Idle, next)
    }

    /// Writes state only while `operation` still owns the pipeline, so an aborted or superseded
    /// run cannot overwrite the state of the run that replaced it.
    fn set_operation_state(
        &self,
        operation: &VoiceOperationToken,
        new_state: PipelineState,
    ) -> bool {
        let applied = self
            .operations
            .update_if_active(operation, || self.publish_state(new_state));
        if applied {
            self.refresh_tray_state();
        }
        applied
    }

    fn finish_operation(&self, operation: &VoiceOperationToken) {
        if self.operations.finish(operation, || {
            self.clear_learning_owner();
            self.publish_state(PipelineState::Idle);
        }) {
            self.refresh_tray_state();
        }
    }

    /// Returns the pipeline to Idle if the run is dropped mid-await, e.g. when Ask is cancelled.
    fn idle_when_dropped(&self, operation: &VoiceOperationToken) -> OnDrop<impl FnOnce()> {
        let pipeline = self.clone();
        let operation = operation.clone();
        OnDrop::new(move || {
            if pipeline.operations.cancel(&operation, || {
                pipeline.clear_learning_owner();
                pipeline.publish_state(PipelineState::Idle);
            }) {
                pipeline.refresh_tray_state();
            }
        })
    }

    pub(crate) fn try_voice_start_guard(
        &self,
    ) -> std::result::Result<tokio::sync::OwnedMutexGuard<()>, String> {
        acquire_voice_start_guard(&self.pipeline_lock)
    }

    fn clear_learning_owner(&self) {
        *self
            .preloaded_learning_owner
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = None;
    }

    fn clear_retry_audio(&self) {
        *self
            .retry_audio
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = None;
        let _ = self.app_handle.emit("pipeline:retry_available", false);
    }

    pub async fn retry_transcription(&self) -> Result<String> {
        use crate::stt::SttProvider;
        let _guard = self.try_voice_start_guard().map_err(anyhow::Error::msg)?;
        anyhow::ensure!(
            self.current_state() == PipelineState::Idle,
            "Pipeline is busy"
        );
        anyhow::ensure!(
            !self
                .app_handle
                .state::<crate::commands::ask::AskDictationState>()
                .is_busy(),
            "Ask is busy"
        );
        let config = self
            .app_handle
            .state::<storage::ConfigManager>()
            .load()
            .await?;
        let retained = self
            .retry_audio
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take();
        let Some(mut retained) = retained else {
            anyhow::bail!("No failed audio available");
        };
        if retained.is_expired() {
            self.clear_retry_audio();
            anyhow::bail!("Failed audio has expired");
        }
        let mut credential_config = config.clone();
        if config.stt_provider != retained.provider.provider_name {
            credential_config.stt_api_key.clear();
            credential_config.stt_custom_api_key.clear();
        }
        credential_config.stt_provider = retained.provider.provider_name.clone();
        retained.config.api_key =
            match resolve_stt_config_secret(&credential_config, &SystemCredentialVault) {
                Ok(secret) => secret,
                Err(error) => {
                    *self
                        .retry_audio
                        .lock()
                        .unwrap_or_else(|error| error.into_inner()) = Some(retained);
                    return Err(error);
                }
            };
        let mut provider = crate::stt::whisper_compat::WhisperCompatProvider::with_client(
            retained.provider.clone(),
            self.shared_client.clone(),
        );
        let Some(operation) = self.begin_operation(PipelineState::Transcribing) else {
            retained.config.api_key.clear();
            *self
                .retry_audio
                .lock()
                .unwrap_or_else(|error| error.into_inner()) = Some(retained);
            anyhow::bail!("Pipeline is busy");
        };
        let _idle_on_drop = self.idle_when_dropped(&operation);
        let _ = self.app_handle.emit("pipeline:retry_available", false);
        let result = crate::retry_audio::cancellable_retry(operation.cancel_receiver(), async {
            provider.connect(&retained.config).await?;
            provider.send_audio(&retained.pcm).await?;
            provider.disconnect().await
        })
        .await;
        retained.config.api_key.clear();
        let Some(result) = result.filter(|_| !operation.is_cancelled()) else {
            self.clear_retry_audio();
            anyhow::bail!("Retry cancelled");
        };
        let result = match result {
            Ok(Some(text)) if !text.trim().is_empty() => {
                use tauri_plugin_clipboard_manager::ClipboardExt;
                match self.app_handle.clipboard().write_text(text.clone()) {
                    Ok(()) => {
                        self.clear_retry_audio();
                        Ok(text)
                    }
                    Err(error) => {
                        *self
                            .retry_audio
                            .lock()
                            .unwrap_or_else(|error| error.into_inner()) = Some(retained);
                        let _ = self.app_handle.emit("pipeline:retry_available", true);
                        Err(anyhow::anyhow!(error))
                    }
                }
            }
            Ok(_) => {
                self.clear_retry_audio();
                Err(anyhow::anyhow!("No speech detected"))
            }
            Err(error) => {
                *self
                    .retry_audio
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = Some(retained);
                let _ = self.app_handle.emit("pipeline:retry_available", true);
                Err(anyhow::anyhow!(error))
            }
        };
        self.finish_operation(&operation);
        result
    }

    fn ensure_voice_idle(&self) -> Result<()> {
        anyhow::ensure!(
            self.current_state() == PipelineState::Idle,
            "Pipeline is busy"
        );
        anyhow::ensure!(
            !self
                .app_handle
                .state::<crate::commands::ask::AskDictationState>()
                .is_busy(),
            "Ask is busy"
        );
        Ok(())
    }

    /// Recognizes a saved History recording with the configured file-based provider. The
    /// returned operation owns the pipeline in Transcribing; `None` text means it was cancelled.
    async fn recognize_history_audio(
        &self,
        id: i64,
        config: &storage::AppConfig,
    ) -> Result<(
        VoiceOperationToken,
        Option<Result<Option<String>, crate::error::AppError>>,
    )> {
        let provider_name = config.stt_provider.as_str();
        anyhow::ensure!(
            supports_archived_audio_recognition(provider_name),
            "Choose Apple Speech or a file-based Whisper provider before retrying history"
        );
        let audio = self
            .app_handle
            .state::<storage::HistoryStore>()
            .read_audio(id)
            .await?
            .ok_or_else(|| anyhow::anyhow!("No saved recording for this entry"))?;
        let pcm = crate::audio::archive::decode_archive_wav(&audio).map_err(anyhow::Error::msg)?;
        let custom = if provider_name == stt::config::CUSTOM_WHISPER_PROVIDER {
            Some(
                stt::config::build_custom_whisper_config(
                    &config.stt_custom_base_url,
                    &config.stt_custom_model,
                )
                .map_err(anyhow::Error::msg)?,
            )
        } else {
            None
        };
        let mut provider =
            stt::create_provider(provider_name, custom, Some(self.shared_client.clone()))?;
        let stt_config = SttConfig {
            api_key: if provider_name == stt::config::APPLE_SPEECH_PROVIDER {
                String::new()
            } else {
                resolve_stt_config_secret(config, &SystemCredentialVault)?
            },
            language: (config.stt_language != "multi").then(|| config.stt_language.clone()),
            ..SttConfig::default()
        };
        let operation = self
            .begin_operation(PipelineState::Transcribing)
            .ok_or_else(|| anyhow::anyhow!("Pipeline is busy"))?;
        *self
            .history_retry_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = Some(operation.clone());
        let recognition =
            crate::retry_audio::cancellable_retry(operation.cancel_receiver(), async {
                provider.connect(&stt_config).await?;
                provider.send_audio(pcm).await?;
                provider.disconnect().await
            })
            .await;
        let recognition = recognition.filter(|_| !operation.is_cancelled());
        Ok((operation, recognition))
    }

    pub async fn retry_history_transcription(&self, id: i64) -> Result<String> {
        let _guard = self
            .pipeline_lock
            .try_lock()
            .map_err(|_| anyhow::anyhow!("Another voice operation is busy"))?;
        self.ensure_voice_idle()?;
        let config = self
            .app_handle
            .state::<storage::ConfigManager>()
            .load()
            .await?;
        let (operation, recognition) = self.recognize_history_audio(id, &config).await?;
        let _idle_on_drop = self.idle_when_dropped(&operation);
        let result = match recognition {
            Some(Ok(Some(text))) if !text.trim().is_empty() => {
                use tauri_plugin_clipboard_manager::ClipboardExt;
                self.app_handle
                    .clipboard()
                    .write_text(text.clone())
                    .map(|_| text)
                    .map_err(anyhow::Error::from)
            }
            Some(Ok(_)) => Err(anyhow::anyhow!("No speech detected")),
            Some(Err(error)) => Err(anyhow::anyhow!(error)),
            None => return Err(anyhow::anyhow!("History retry cancelled")),
        };
        if let Ok(text) = &result {
            match self
                .app_handle
                .state::<storage::HistoryStore>()
                .complete_recovered(id, text.trim())
                .await
            {
                Ok(true) => {
                    let _ = self.app_handle.emit("history:changed", ());
                }
                Ok(false) => {}
                Err(error) => tracing::warn!("Failed to store recovered transcript: {error}"),
            }
        }
        self.finish_operation(&operation);
        result
    }

    /// Typeless "Transcript cancelled → Undo" and "Something went wrong → Retry": re-recognize the
    /// stored take, polish and write it into the app that is focused now, and record the result
    /// on the same History row.
    pub async fn recover_unfinished_take(&self, id: i64) -> Result<()> {
        let _guard = self.try_voice_start_guard().map_err(anyhow::Error::msg)?;
        self.ensure_voice_idle()?;
        let history = self.app_handle.state::<storage::HistoryStore>();
        let entry = history
            .get(id)
            .await?
            .ok_or_else(|| anyhow::anyhow!("No saved recording for this entry"))?;
        let failed_take =
            entry.output_status.as_deref() == Some(storage::HISTORY_STATUS_RECOGNITION_FAILED);
        anyhow::ensure!(
            failed_take
                || entry.output_status.as_deref() == Some(storage::HISTORY_STATUS_DISMISSED),
            "This take was already restored"
        );
        let voice_mode = voice_mode_from_history(&entry.mode);
        anyhow::ensure!(
            voice_mode != crate::voice_intent::VoiceMode::Ask,
            "Ask takes cannot be restored"
        );
        let mut config = self
            .app_handle
            .state::<storage::ConfigManager>()
            .load()
            .await?;
        if voice_mode == crate::voice_intent::VoiceMode::Translate {
            config = apply_pipeline_start_options(
                config,
                PipelineStartOptions {
                    force_translate: true,
                },
            );
        }
        let app_ctx = self
            .context_detector
            .snapshot_for_recording_enabled(config.context_adaptation_enabled);
        let (operation, recognition) = self.recognize_history_audio(id, &config).await?;
        let _idle_on_drop = self.idle_when_dropped(&operation);
        self.history_retry_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take();
        let raw_text = match recognition {
            Some(Ok(Some(text))) if !text.trim().is_empty() => text.trim().to_string(),
            Some(Ok(_)) => {
                self.finish_operation(&operation);
                anyhow::bail!("No speech detected");
            }
            Some(Err(error)) => {
                self.finish_operation(&operation);
                return Err(anyhow::anyhow!(error));
            }
            None => anyhow::bail!("History retry cancelled"),
        };
        let _ = self.app_handle.emit("stt:final", &raw_text);

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
        let cloud_session = self.app_handle.state::<SessionTokenStore>().snapshot();
        let session_epoch =
            (config.llm_provider == "cloud").then(|| cloud_session.session_epoch.clone());
        let session_token = cloud_session.token.unwrap_or_default();
        let voice_intent = route_pipeline_voice_intent(voice_mode, &raw_text, None, &config);
        let outcome = self
            .polish_text(PolishTextInput {
                operation: &operation,
                raw_text: &raw_text,
                voice_mode,
                config: &config,
                app_ctx: &app_ctx,
                dictionary_words,
                correction_rules,
                selected_text: None,
                session_token,
                session_epoch,
                operation_id: Some(generate_cloud_operation_id()),
                voice_intent,
                popup_fallback_enabled: true,
            })
            .await;
        if operation.is_cancelled() {
            anyhow::bail!("History retry cancelled");
        }
        match history
            .complete_unfinished_take(
                id,
                &raw_text,
                &outcome.final_text,
                outcome.history_output_status.as_deref(),
                outcome.history_output_error.as_deref(),
            )
            .await
        {
            Ok(true) => {
                let _ = self.app_handle.emit("history:changed", ());
            }
            Ok(false) => {}
            Err(error) => tracing::warn!("Failed to store restored take: {error}"),
        }
        if failed_take {
            // The in-memory "retry and copy" audio belongs to the same failure.
            self.clear_retry_audio();
        }
        self.finish_operation(&operation);
        Ok(())
    }

    /// Cancels only the history retry; returns false when none is running.
    pub fn cancel_history_retry(&self) -> bool {
        let operation = self
            .history_retry_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take();
        let cancelled = operation.is_some_and(|operation| {
            self.operations
                .cancel(&operation, || self.publish_state(PipelineState::Idle))
        });
        if cancelled {
            self.refresh_tray_state();
        }
        cancelled
    }

    pub(crate) fn switch_active_translation_target(
        &self,
        expected: &str,
        target: String,
    ) -> std::result::Result<(String, String), String> {
        if self.current_state() != PipelineState::Recording {
            return Err("translation_operation_finished".to_string());
        }
        let mut operation = self
            .active_translation_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let operation = operation
            .as_mut()
            .ok_or_else(|| "translation_not_recording".to_string())?;
        let previous = operation
            .switch_owned_target(expected, target)
            .map_err(str::to_string)?;
        Ok((previous, operation.id.clone()))
    }

    pub(crate) fn active_translation_id(&self) -> Option<String> {
        self.active_translation_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .map(|operation| operation.id.clone())
    }

    /// Immediately abort the pipeline regardless of current state.
    /// Stops audio capture, forces state to Idle, and signals any
    /// ongoing stop() to exit early.
    pub fn abort(&self) {
        if self.cancel_history_retry() {
            return;
        }
        tracing::info!(
            "Pipeline abort requested (current state: {:?})",
            self.current_state()
        );
        // All cleanup is non-blocking and runs under the operation lock, so the next run cannot
        // begin until it is done, and a stale run cannot write state after this returns.
        self.operations.abort_current(|| self.abort_cleanup(true));
        self.refresh_tray_state();
    }

    /// The user cancelled the take (capsule cancel, or discarding a pending one). Like Typeless,
    /// the recording stays in History as a dismissed item that can be retried later. A take that
    /// is still recording is saved here; one already past recording is saved by its stop() run.
    pub fn dismiss(&self) {
        if self.cancel_history_retry() {
            return;
        }
        tracing::info!(
            "Pipeline dismiss requested (current state: {:?})",
            self.current_state()
        );
        let mut take = None;
        // Runs under the operation lock, so stop() cannot claim the take between these steps.
        self.operations.dismiss_current(|| {
            let state = self.current_state();
            if state == PipelineState::Recording {
                take = self.take_recording_for_history();
            }
            let stop_owns_recording = matches!(
                state,
                PipelineState::Transcribing | PipelineState::Polishing | PipelineState::Outputting
            );
            self.abort_cleanup(!stop_owns_recording);
        });
        self.refresh_tray_state();
        if let Some(take) = take {
            let pipeline = self.clone();
            tauri::async_runtime::spawn(async move {
                pipeline
                    .save_dismissed_take(
                        &take.app_ctx,
                        take.duration_ms,
                        &take.config,
                        take.voice_mode,
                        take_archived_wav(Some(take.archive)),
                        take.session_id,
                    )
                    .await;
            });
        }
    }

    /// The archive is taken last so a missing config or context leaves the recording untouched.
    fn take_recording_for_history(&self) -> Option<DismissedTake> {
        let config = self
            .preloaded_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()?;
        let app_ctx = self
            .preloaded_app_ctx
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()?;
        let archive = self
            .recording_archive
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take()?;
        Some(DismissedTake {
            session_id: Some(self.active_stt_session_id.load(Ordering::SeqCst)),
            archive,
            config,
            app_ctx,
            voice_mode: self
                .preloaded_voice_mode
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .unwrap_or(crate::voice_intent::VoiceMode::Dictate),
            duration_ms: self
                .recording_start
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .map(|start| start.elapsed().as_millis() as i64),
        })
    }

    /// `drop_archive` is false while a stop() run may still need the recording it is finishing.
    fn abort_cleanup(&self, drop_archive: bool) {
        crate::hotkey::invalidate_pending_recording_commands(&self.app_handle);
        self.clear_learning_owner();
        self.clear_retry_audio();
        if drop_archive {
            *self
                .recording_archive
                .lock()
                .unwrap_or_else(|error| error.into_inner()) = None;
        }
        self.active_stt_session_id.fetch_add(1, Ordering::SeqCst);
        self.active_deadline_session_id.store(0, Ordering::SeqCst);
        // Stopping capture closes the channel, so the STT task ends naturally.
        if let Some(mut handle) = self
            .audio_handle
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
        {
            handle.stop();
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
        self.accumulated_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        *self.stt_error.lock().unwrap_or_else(|e| e.into_inner()) = None;
        *self
            .cloud_operation_id
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = None;
        self.publish_state(PipelineState::Idle);
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

    pub async fn start(&self) -> Result<()> {
        self.start_with_options(PipelineStartOptions::default())
            .await
    }

    pub async fn start_with_options(&self, options: PipelineStartOptions) -> Result<()> {
        let config = self
            .app_handle
            .state::<storage::ConfigManager>()
            .load()
            .await?;
        self.start_with_config(config, options).await
    }

    pub(crate) async fn start_with_config(
        &self,
        config: storage::AppConfig,
        options: PipelineStartOptions,
    ) -> Result<()> {
        self.start_with_owned_config(config, options, |_| true)
            .await
    }

    pub(crate) async fn start_with_owned_config(
        &self,
        config: storage::AppConfig,
        options: PipelineStartOptions,
        claimed: impl FnOnce(VoiceOperationToken) -> bool + Send,
    ) -> Result<()> {
        // Hold pipeline_lock for the entire setup so stop() cannot read
        // partially-initialised state (preloaded_config, audio_handle, etc.).
        let _guard = self.try_voice_start_guard().map_err(anyhow::Error::msg)?;
        anyhow::ensure!(
            !self
                .app_handle
                .state::<crate::commands::ask::AskDictationState>()
                .is_busy(),
            "Ask is busy"
        );
        let config_data = apply_pipeline_start_options(config, options);
        crate::edit_learning::cancel(&self.app_handle);

        // Only one caller can move Idle → Preparing. Recording is emitted only after audio
        // capture is ready, so the capsule does not tell users to speak too early.
        let Some(operation) = self.begin_operation(PipelineState::Preparing) else {
            return Ok(());
        };
        if !claimed(operation.clone()) {
            self.operations
                .cancel(&operation, || self.publish_state(PipelineState::Idle));
            self.refresh_tray_state();
            return Ok(());
        }
        let learning_epoch = self
            .app_handle
            .state::<SessionTokenStore>()
            .snapshot()
            .session_epoch;
        let learning_generation = crate::edit_learning::current_generation(&self.app_handle);
        *self
            .preloaded_learning_owner
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = Some(LearningOwner {
            session_epoch: learning_epoch,
            generation: learning_generation,
        });
        self.clear_retry_audio();
        crate::audio::preview::stop_for_voice_operation(&self.app_handle);

        // Clear accumulated text
        self.accumulated_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();
        *self.stt_error.lock().unwrap_or_else(|e| e.into_inner()) = None;

        // P0-2: Load config BEFORE starting audio capture — fail fast on missing API key
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

        let stt_cloud_session = self.app_handle.state::<SessionTokenStore>().snapshot();
        let stt_session_epoch =
            (config_data.stt_provider == "cloud").then(|| stt_cloud_session.session_epoch.clone());
        let stt_api_key = if config_data.stt_provider == "cloud" {
            stt_cloud_session.token.unwrap_or_default()
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
                    self.finish_operation(&operation);
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
                "STT API key is not configured. Please set it in Models -> Speech Recognition.",
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
            self.finish_operation(&operation);
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
                        self.finish_operation(&operation);
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
                self.finish_operation(&operation);
                return Ok(());
            }
        };
        // Start the platform audio backend before connecting STT. Both readiness
        // operations are then polled concurrently, so speech captured while a
        // network provider connects remains queued instead of being clipped.
        let config = AudioConfig::for_device(&config_data.microphone_device);
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
                self.finish_operation(&operation);
                return Ok(());
            }
        };
        let startup_result =
            crate::retry_audio::cancellable_retry(operation.cancel_receiver(), async {
                crate::audio::await_recording_startup(
                    handle.wait_until_ready(),
                    provider.connect(&stt_config),
                )
                .await
            })
            .await;
        let Some(startup_result) = startup_result else {
            handle.stop();
            return Ok(());
        };
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
                self.finish_operation(&operation);
                return Ok(());
            }
        };

        // Store the audio handle's volume reference.
        // Check for abort first — if abort() was called while we were connecting
        // to STT, don't store the handle (it would be orphaned with nobody to stop it).
        if operation.is_cancelled() {
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
            self.finish_operation(&operation);
            return Ok(());
        }
        let audio_vol = handle.get_volume();
        let capture_status = handle.status();
        *self.audio_volume.lock().unwrap_or_else(|e| e.into_inner()) = audio_vol;
        *self.audio_handle.lock().unwrap_or_else(|e| e.into_inner()) = Some(handle);
        if operation.is_cancelled() {
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
            self.finish_operation(&operation);
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
        if !self.set_operation_state(&operation, PipelineState::Recording) {
            tracing::info!("Pipeline aborted before recording started");
            self.active_deadline_session_id.store(0, Ordering::SeqCst);
            *self
                .active_translation_operation
                .lock()
                .unwrap_or_else(|error| error.into_inner()) = None;
            return Ok(());
        }
        let _ = self.app_handle.emit("pipeline:voice_mode", voice_mode);
        let device_name = self
            .audio_handle
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .and_then(AudioCaptureHandle::device_name);
        crate::audio::device_notice::emit_changed(&self.app_handle, device_name);
        let capture_meter = self
            .audio_handle
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .and_then(|capture| capture.device_name().map(|name| (name, capture.meter())));
        if let Some((name, meter)) = capture_meter {
            crate::audio::better_microphone::schedule(self.app_handle.clone(), name, meter);
        }
        let _ = self
            .app_handle
            .emit("recording:deadline", recording_deadline.event);

        // Volume monitoring task
        let app_handle = self.app_handle.clone();
        let audio_handle_ref = self.audio_handle.clone();
        let state_ref = self.state.clone();
        let volume_operation = operation.clone();
        let volume_operations = self.operations.clone();
        let volume_abort = operation.abort_flag();
        let volume_session_id = self.active_stt_session_id.clone();
        tokio::spawn(async move {
            loop {
                tokio::time::sleep(std::time::Duration::from_millis(VOLUME_POLL_INTERVAL_MS)).await;
                let current = PipelineState::from_u8(state_ref.load(Ordering::SeqCst));
                if current != PipelineState::Recording
                    || !should_finalize_stt_task(&volume_abort, &volume_session_id, session_id)
                {
                    break;
                }
                let vol = audio_handle_ref
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .as_ref()
                    .map(|h| h.get_volume())
                    .unwrap_or(0.0);
                if !volume_operations.update_if_active(&volume_operation, || {
                    let _ = app_handle.emit("audio:volume", vol);
                    let _ = app_handle.emit(
                        "audio:volume-owned",
                        serde_json::json!({
                            "sessionId": session_id,
                            "recordingKind": "dictation",
                            "level": vol
                        }),
                    );
                }) {
                    break;
                }
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
        let abort_flag_ref = operation.abort_flag();
        let active_session_id_ref = self.active_stt_session_id.clone();
        let stt_error_ref = self.stt_error.clone();
        let retry_audio_ref = self.retry_audio.clone();
        let archive = (config_data.save_recording_audio && config_data.history_enabled)
            .then(|| Arc::new(Mutex::new(crate::audio::archive::ArchiveBuffer::default())));
        *self
            .recording_archive
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = archive.clone();
        let capture_failure_pipeline = self.clone();

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
                        if let Some(error) = capture_status.error() {
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
                        match chunk {
                            Some(data) => {
                                if let Some(archive) = &archive {
                                    archive.lock().unwrap_or_else(|error| error.into_inner())
                                        .append(&data);
                                }
                                if let Err(error) = provider.send_audio(&data).await {
                                    tracing::error!("STT send audio failed");
                                    if let Some(epoch) = stt_session_epoch.as_deref() {
                                        crate::error::emit_owned_cloud_session_invalid(
                                            &app_handle,
                                            &error,
                                            epoch,
                                        );
                                    }
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
                                        tracing::error!("STT disconnect failed");
                                        if should_finalize_stt_task(
                                            abort_flag_ref.as_ref(),
                                            active_session_id_ref.as_ref(),
                                            stt_control.id,
                                        ) {
                                            if let Some(audio) = provider.take_retry_audio() {
                                                *retry_audio_ref
                                                    .lock()
                                                    .unwrap_or_else(|error| error.into_inner()) =
                                                    Some(audio);
                                                let _ = app_handle
                                                    .emit("pipeline:retry_available", true);
                                                let retry_cache = retry_audio_ref.clone();
                                                let retry_app = app_handle.clone();
                                                tokio::spawn(async move {
                                                    tokio::time::sleep(
                                                        std::time::Duration::from_secs(600),
                                                    )
                                                    .await;
                                                    let mut cached = retry_cache
                                                        .lock()
                                                        .unwrap_or_else(|error| error.into_inner());
                                                    if cached
                                                        .as_ref()
                                                        .is_some_and(|audio| audio.is_expired())
                                                    {
                                                        *cached = None;
                                                        let _ = retry_app.emit(
                                                            "pipeline:retry_available",
                                                            false,
                                                        );
                                                    }
                                                });
                                            }
                                            if let Some(epoch) = stt_session_epoch.as_deref() {
                                                crate::error::emit_owned_cloud_session_invalid(
                                                    &app_handle,
                                                    &e,
                                                    epoch,
                                                );
                                            }
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
                                tracing::error!("STT provider reported an error");
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
                                tracing::error!("STT receive failed");
                                if should_finalize_stt_task(
                                    abort_flag_ref.as_ref(),
                                    active_session_id_ref.as_ref(),
                                    stt_control.id,
                                ) {
                                    if let Some(epoch) = stt_session_epoch.as_deref() {
                                        crate::error::emit_owned_cloud_session_invalid(
                                            &app_handle,
                                            &e,
                                            epoch,
                                        );
                                    }
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
            if capture_status.error().is_some() {
                if let Err(error) = capture_failure_pipeline
                    .stop_recording(Some(stt_control.id))
                    .await
                {
                    tracing::error!("Failed to clean up interrupted audio capture: {error}");
                }
            }
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
        self.stop_recording(None).await
    }

    pub(crate) async fn stop_operation(&self, operation: VoiceOperationToken) -> Result<()> {
        self.stop_recording_operation(None, operation).await
    }

    async fn stop_recording(&self, expected_session_id: Option<u64>) -> Result<()> {
        let Some(expected_operation) = self.operations.current_token() else {
            return Ok(());
        };
        self.stop_recording_operation(expected_session_id, expected_operation)
            .await
    }

    async fn stop_recording_operation(
        &self,
        expected_session_id: Option<u64>,
        expected_operation: VoiceOperationToken,
    ) -> Result<()> {
        // Acquire pipeline_lock so we wait for start() to finish its setup
        // (load config, initialize audio and connect STT) before reading shared state.
        // Released before the long stt_done wait so start() isn't blocked 120s.
        let practice_busy = self
            .app_handle
            .try_state::<crate::practice::PracticeState>()
            .is_some_and(|state| state.is_busy());
        let Some(guard) = acquire_voice_stop_guard(&self.pipeline_lock, practice_busy).await else {
            return Ok(());
        };
        if expected_session_id.is_some_and(|session_id| {
            self.active_stt_session_id.load(Ordering::SeqCst) != session_id
        }) {
            return Ok(());
        }

        // Only one caller can move Recording → Transcribing, and never for an aborted run.
        let Some(operation) = self.operations.adopt_token_if(&expected_operation, || {
            let claimed = claim_pipeline_state(
                &self.state,
                PipelineState::Recording,
                PipelineState::Transcribing,
            );
            if claimed {
                self.publish_state(PipelineState::Transcribing);
            }
            claimed
        }) else {
            return Ok(());
        };
        self.refresh_tray_state();
        let _idle_on_drop = self.idle_when_dropped(&operation);
        self.active_deadline_session_id.store(0, Ordering::SeqCst);
        let finalized_translation_target = self
            .active_translation_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_mut()
            .map(TranslationOperationState::finalize);

        let stop_start = std::time::Instant::now();
        let failed_recording_duration_ms = self
            .recording_start
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .map(|start| start.elapsed().as_millis() as i64);

        // Capture selected text now — hotkey is released so Ctrl+C won't conflict.
        // Small delay to ensure hotkey modifiers are fully released (especially in toggle mode).
        let config_snapshot = self
            .preloaded_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        let Some(config_data) = config_snapshot else {
            self.operations
                .cancel(&operation, || self.abort_cleanup(true));
            self.refresh_tray_state();
            anyhow::bail!("Recording settings are unavailable");
        };
        let capture_failed = self
            .audio_handle
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .is_some_and(|capture| capture.status().error().is_some());
        let selected_text = if config_data.selected_text_enabled && !capture_failed {
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
        if selected_text_has_content(selected_text.as_deref()) {
            // The capsule may use this to teach the Ask shortcut once (Typeless selected-text tip).
            let _ = self.app_handle.emit("capsule:selected_text", ());
        }
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
        let mut config = preloaded_config.unwrap_or(config_data);
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
        let cloud_session = self.app_handle.state::<SessionTokenStore>().snapshot();
        let session_epoch =
            (config.llm_provider == "cloud").then(|| cloud_session.session_epoch.clone());
        let learning_owner = self
            .preloaded_learning_owner
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take();
        let session_token = cloud_session.token.unwrap_or_default();

        let recording_archive = self
            .recording_archive
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take();

        // All shared state has been taken — release the lock so a new start()
        // isn't blocked by the long stt_done wait that follows.
        drop(guard);

        // ── Phase 1: Wait for STT ──────────────────────────────────────
        let finalize_timeout = crate::stt_finalize::finalize_timeout(
            &config.stt_provider,
            std::time::Duration::from_secs(STT_FINALIZE_TIMEOUT_SECS),
        );
        let outcome = self
            .wait_for_stt(&operation, stt_control.clone(), finalize_timeout)
            .await?;
        let raw_text = match outcome {
            SttWaitOutcome::Text(text) => text,
            SttWaitOutcome::NoSpeech | SttWaitOutcome::Interrupted => {
                let keeps_failed_take = stt_control.as_ref().is_some_and(|control| {
                    crate::audio::archive::should_save_failed_recording(
                        operation.is_aborted(),
                        control.id,
                        self.active_stt_session_id.load(Ordering::SeqCst),
                    )
                });
                if operation.is_dismissed() {
                    self.save_dismissed_take(
                        &app_ctx,
                        failed_recording_duration_ms,
                        &config,
                        voice_mode,
                        take_archived_wav(recording_archive),
                        stt_control.as_ref().map(|control| control.id),
                    )
                    .await;
                } else if let Some(output) =
                    unfinished_take_status(&outcome, false, keeps_failed_take)
                {
                    self.save_recoverable_take(
                        &app_ctx,
                        TakeRecoveryContext {
                            duration_ms: failed_recording_duration_ms,
                            session_id: stt_control.as_ref().map(|control| control.id),
                        },
                        &config,
                        voice_mode,
                        take_archived_wav(recording_archive),
                        output,
                    )
                    .await;
                }
                if let Some(control) = &stt_control {
                    self.clear_stt_session(control.id);
                }
                return Ok(());
            }
        };
        let voice_intent =
            route_pipeline_voice_intent(voice_mode, &raw_text, selected_text.as_deref(), &config);
        let stt_elapsed = stop_start.elapsed();
        tracing::info!(
            "[Pipeline Timing] STT finalize: {}ms",
            stt_elapsed.as_millis()
        );

        // Check abort before entering LLM polish and output
        if operation.is_cancelled() {
            tracing::info!("Pipeline aborted before LLM/output");
            if operation.is_dismissed() {
                self.save_dismissed_take(
                    &app_ctx,
                    failed_recording_duration_ms,
                    &config,
                    voice_mode,
                    take_archived_wav(recording_archive),
                    stt_control.as_ref().map(|control| control.id),
                )
                .await;
            }
            if let Some(control) = &stt_control {
                self.clear_stt_session(control.id);
            }
            return Ok(());
        }

        // ── Phase 2: LLM polish + output ───────────────────────────────
        let polish_outcome = self
            .polish_text(PolishTextInput {
                operation: &operation,
                raw_text: &raw_text,
                voice_mode,
                config: &config,
                app_ctx: &app_ctx,
                dictionary_words,
                correction_rules,
                selected_text,
                session_token,
                session_epoch,
                operation_id,
                voice_intent,
                popup_fallback_enabled: true,
            })
            .await;
        if config.auto_learn_dictionary
            && !operation.is_cancelled()
            && outcome_inserted_into_field(&polish_outcome)
        {
            if let Some(owner) = learning_owner {
                crate::edit_learning::track_after_insert(
                    &self.app_handle,
                    polish_outcome.final_text.clone(),
                    operation.abort_flag(),
                    owner.session_epoch,
                    owner.generation,
                )
                .await;
            }
        }
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

        if !operation.is_cancelled() {
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
        }

        // Save to history
        if operation.is_dismissed() {
            self.save_dismissed_take(
                &app_ctx,
                duration_ms,
                &config,
                voice_mode,
                take_archived_wav(recording_archive),
                stt_control.as_ref().map(|control| control.id),
            )
            .await;
        } else {
            self.save_history(
                &raw_text,
                &final_text,
                &app_ctx,
                duration_ms,
                &config,
                HistorySaveContext {
                    voice_mode,
                    audio: take_archived_wav(recording_archive),
                    output: HistoryOutputMetadata {
                        status: polish_outcome.history_output_status,
                        error: polish_outcome.history_output_error,
                    },
                },
            )
            .await;
        }

        if let Some(control) = &stt_control {
            self.clear_stt_session(control.id);
        }
        self.finish_operation(&operation);
        Ok(())
    }

    /// Wait for the STT task to complete and return the transcribed text.
    async fn wait_for_stt(
        &self,
        operation: &VoiceOperationToken,
        stt_control: Option<SttTaskControl>,
        timeout: std::time::Duration,
    ) -> Result<SttWaitOutcome> {
        if let Some(control) = &stt_control {
            if let Err(error) =
                crate::stt_finalize::wait_for_completion(&control.done, timeout).await
            {
                if self
                    .active_stt_session_id
                    .compare_exchange(control.id, 0, Ordering::SeqCst, Ordering::SeqCst)
                    .is_ok()
                {
                    control.abort.notify_one();
                    let _ = self
                        .app_handle
                        .emit("pipeline:error", error.to_user_error());
                    self.finish_operation(operation);
                }
                return Ok(SttWaitOutcome::Interrupted);
            }
            tracing::debug!("STT task completed");

            if !should_finalize_stt_task(
                operation.abort_flag().as_ref(),
                self.active_stt_session_id.as_ref(),
                control.id,
            ) {
                tracing::info!("Ignoring stale or aborted STT task");
                return Ok(SttWaitOutcome::Interrupted);
            }
            if take_matching_stt_error(&self.stt_error, control.id).is_some() {
                self.finish_operation(operation);
                return Ok(SttWaitOutcome::Interrupted);
            }
        } else {
            tracing::warn!("No STT session was available to wait for");
        }

        if operation.is_cancelled() {
            tracing::info!("Pipeline aborted after STT wait");
            return Ok(SttWaitOutcome::Interrupted);
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
            self.finish_operation(operation);
            return Ok(SttWaitOutcome::NoSpeech);
        }

        Ok(SttWaitOutcome::Text(raw_text))
    }

    /// Polish raw text with LLM and output the result.
    /// Returns (final_text, llm_elapsed_duration).
    async fn polish_text(&self, input: PolishTextInput<'_>) -> PolishTextOutcome {
        let PolishTextInput {
            operation,
            raw_text,
            voice_mode,
            config,
            app_ctx,
            dictionary_words,
            correction_rules,
            selected_text,
            session_token,
            session_epoch,
            operation_id,
            voice_intent,
            popup_fallback_enabled,
        } = input;
        if operation.is_cancelled() {
            return PolishTextOutcome::normal(raw_text.to_string(), std::time::Duration::ZERO);
        }
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
        let mut credential_read_failed = false;
        let llm_api_key = if !config.polish_enabled {
            String::new()
        } else if config.llm_provider == "cloud" {
            session_token
        } else {
            match resolve_llm_config_secret(config, &SystemCredentialVault) {
                Ok(secret) => secret,
                Err(error) => {
                    credential_read_failed = true;
                    tracing::warn!("Failed to read LLM credential: {error}");
                    String::new()
                }
            }
        };

        // Check if polish is enabled and API key / token is available
        if !can_use_polish_provider(config, credential_read_failed, &llm_api_key) {
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
            let output_result = self
                .output_text(
                    operation,
                    provider_text,
                    &app_ctx.profile.app_label,
                    &app_ctx.target_guard,
                    config,
                )
                .await;
            if let (Err(error), false) = (&output_result, operation.is_cancelled()) {
                tracing::error!("Output failed: {}", error);
                let _ = self
                    .app_handle
                    .emit("pipeline:error", output_user_error(error));
            }
            let intentional_copy =
                output::InsertionStrategy::from_config_value(&config.insertion_strategy)
                    == output::InsertionStrategy::ClipboardCopyOnly;
            let output_metadata = if config.polish_enabled && credential_read_failed {
                history_output_metadata_with_prior_error(
                    output_result.as_ref(),
                    intentional_copy,
                    "Could not read provider credential from the system vault",
                )
            } else {
                history_output_metadata_for_output_result(output_result.as_ref(), intentional_copy)
            };
            return PolishTextOutcome {
                final_text: provider_text.to_string(),
                llm_elapsed: std::time::Duration::ZERO,
                history_output_status: output_metadata.status,
                history_output_error: output_metadata.error,
                voice_execution: None,
            };
        }

        if !self.set_operation_state(operation, PipelineState::Polishing) {
            return PolishTextOutcome::normal(raw_text.to_string(), std::time::Duration::ZERO);
        }
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
                operation.clone(),
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
            language_variants: config.language_variants.clone(),
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
                // A cancelled run must not insert, recover, or copy anything after the LLM returns.
                if operation.is_cancelled() {
                    tracing::info!("Pipeline aborted after LLM polish, skipping output");
                    return PolishTextOutcome::normal(raw_text.to_string(), elapsed);
                }
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
                                    let suffix_operation = operation.clone();
                                    let suffix_detector = self.context_detector.clone();
                                    let suffix_target = app_ctx.target_guard.clone();
                                    let suffix_guard = Arc::new(move || {
                                        !suffix_operation.is_cancelled()
                                            && suffix_detector
                                                .target_still_matches_now(&suffix_target)
                                    });
                                    match output::output_stream_chunk_with_guard(
                                        &self.app_handle,
                                        &suffix,
                                        report.strategy,
                                        output::windows_sendinput::WindowsSendInputOptions {
                                            newline_mode: output::windows_sendinput::WindowsSendInputNewlineMode::from_config_value(
                                                &config.windows_sendinput_newline_mode,
                                            ),
                                        },
                                        suffix_guard,
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
                    operation,
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
                if !operation.is_cancelled() {
                    let _ = self.app_handle.emit("pipeline:voice_execution", &execution);
                }

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
                if let Some(epoch) = session_epoch.as_deref() {
                    crate::error::emit_owned_cloud_session_invalid(&self.app_handle, &e, epoch);
                }
                let elapsed = llm_start.elapsed();
                if operation.is_cancelled() {
                    tracing::info!("Pipeline aborted after LLM error, skipping output");
                    return PolishTextOutcome::normal(String::new(), elapsed);
                }
                if let Some(report) = streaming_report.as_ref() {
                    if report.has_inserted_text() {
                        tracing::error!("LLM polish failed after partial streaming insert");
                        let _ = self
                            .app_handle
                            .emit("pipeline:error", llm_polish_user_error(&e));
                        let mut partial_report = report.clone();
                        partial_report.failed = true;
                        partial_report.error_message = Some(polish_history_error(&e));
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
                            format!("{}; partial streaming insert", polish_history_error(&e)),
                        );
                    }
                }

                tracing::error!("LLM polish failed");

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
                let output_result = self
                    .output_text(
                        operation,
                        provider_text,
                        &app_ctx.profile.app_label,
                        &app_ctx.target_guard,
                        config,
                    )
                    .await;
                if let (Err(output_error), false) = (&output_result, operation.is_cancelled()) {
                    tracing::error!("Output failed: {}", output_error);
                    let _ = self
                        .app_handle
                        .emit("pipeline:error", output_user_error(output_error));
                }
                let output_metadata = history_output_metadata_with_prior_error(
                    output_result.as_ref(),
                    output::InsertionStrategy::from_config_value(&config.insertion_strategy)
                        == output::InsertionStrategy::ClipboardCopyOnly,
                    &polish_history_error(&e),
                );
                PolishTextOutcome {
                    final_text: provider_text.to_string(),
                    llm_elapsed: elapsed,
                    history_output_status: output_metadata.status,
                    history_output_error: output_metadata.error,
                    voice_execution: None,
                }
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
        let _guard = self.try_voice_start_guard()?;
        if voice_intent.kind != crate::voice_intent::VoiceIntentKind::DraftInsert {
            return Err("Ask draft execution requires a draft intent".to_string());
        }
        let operation = self
            .operations
            .begin_if(|| self.current_state() == PipelineState::Idle)
            .ok_or_else(|| "Another voice operation is already active".to_string())?;
        // Ask drops this future when the user cancels; the pipeline must not stay in Polishing.
        let _idle_on_drop = self.idle_when_dropped(&operation);

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
        let cloud_session = self.app_handle.state::<SessionTokenStore>().snapshot();
        let session_epoch =
            (config.llm_provider == "cloud").then(|| cloud_session.session_epoch.clone());
        let session_token = cloud_session.token.unwrap_or_default();

        let outcome = self
            .polish_text(PolishTextInput {
                operation: &operation,
                raw_text: utterance,
                voice_mode: crate::voice_intent::VoiceMode::Ask,
                config,
                app_ctx,
                dictionary_words,
                correction_rules,
                selected_text: None,
                session_token,
                session_epoch,
                operation_id: Some(operation_id.to_string()),
                voice_intent,
                popup_fallback_enabled: false,
            })
            .await;
        if operation.is_aborted() {
            return Err(crate::commands::ask::ASK_CANCELLED.to_string());
        }
        self.finish_operation(&operation);

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
    /// Typeless keeps dismissed, silent and interrupted takes as recoverable History rows; without
    /// saved audio there is nothing to retry, so no row is written.
    async fn save_recording_only_history(
        &self,
        app_ctx: &RecordingContext,
        duration_ms: Option<i64>,
        config: &storage::AppConfig,
        voice_mode: crate::voice_intent::VoiceMode,
        audio: Option<Vec<u8>>,
        output: HistoryOutputMetadata,
    ) -> Option<i64> {
        audio.as_ref()?;
        let id = self
            .save_history(
                "",
                "",
                app_ctx,
                duration_ms,
                config,
                HistorySaveContext {
                    voice_mode,
                    audio,
                    output,
                },
            )
            .await;
        let _ = self.app_handle.emit("history:changed", ());
        id
    }

    async fn save_dismissed_take(
        &self,
        app_ctx: &RecordingContext,
        duration_ms: Option<i64>,
        config: &storage::AppConfig,
        voice_mode: crate::voice_intent::VoiceMode,
        audio: Option<Vec<u8>>,
        session_id: Option<u64>,
    ) {
        self.save_recoverable_take(
            app_ctx,
            TakeRecoveryContext {
                duration_ms,
                session_id,
            },
            config,
            voice_mode,
            audio,
            HistoryOutputMetadata::dismissed(),
        )
        .await;
    }

    /// The capsule alert only offers Undo or Retry once the take is safely stored.
    async fn save_recoverable_take(
        &self,
        app_ctx: &RecordingContext,
        recovery: TakeRecoveryContext,
        config: &storage::AppConfig,
        voice_mode: crate::voice_intent::VoiceMode,
        audio: Option<Vec<u8>>,
        output: HistoryOutputMetadata,
    ) {
        let event = take_recovery_event(output.status.as_deref(), voice_mode, &config.stt_provider);
        let id = self
            .save_recording_only_history(
                app_ctx,
                recovery.duration_ms,
                config,
                voice_mode,
                audio,
                output,
            )
            .await;
        if let (Some(event), Some(history_id)) = (event, id) {
            let _ = self.app_handle.emit(
                event,
                serde_json::json!({
                    "historyId": history_id,
                    "sessionId": recovery.session_id,
                }),
            );
        }
    }

    async fn save_history(
        &self,
        raw_text: &str,
        final_text: &str,
        app_ctx: &RecordingContext,
        duration_ms: Option<i64>,
        config: &storage::AppConfig,
        save: HistorySaveContext,
    ) -> Option<i64> {
        let HistorySaveContext {
            voice_mode,
            output,
            audio,
        } = save;
        let now = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
        let scene_diagnostics = active_scene_history_diagnostics(config.active_scene.as_ref());
        let entry = storage::HistoryEntry {
            has_audio: audio.is_some(),
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
            mode: match voice_mode {
                crate::voice_intent::VoiceMode::Translate => "translate",
                crate::voice_intent::VoiceMode::Ask => "ask",
                crate::voice_intent::VoiceMode::Dictate => "dictate",
            }
            .to_string(),
            answer_text: None,
        };
        let config_manager = self.app_handle.state::<storage::ConfigManager>();
        let history = self.app_handle.state::<storage::HistoryStore>();
        match config_manager.with_current_history_privacy(|current_privacy| {
            let Some((policy, audio)) = prepare_history_retention(
                config,
                current_privacy,
                audio,
                entry.output_status.as_deref(),
            ) else {
                return Ok(None);
            };
            history.add_with_audio_sync(entry, &policy, audio)
        }) {
            Ok(id) => id,
            Err(e) => {
                tracing::error!("Failed to save history: {}", e);
                None
            }
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
        operation: &VoiceOperationToken,
        text: &str,
        app_name: &str,
        target_guard: &TargetAppGuard,
        config: &storage::AppConfig,
    ) -> Result<output::InsertResult> {
        if !self.set_operation_state(operation, PipelineState::Outputting) {
            anyhow::bail!("Voice operation was cancelled before output");
        }

        let configured_strategy =
            output::InsertionStrategy::from_config_value(&config.insertion_strategy);
        let target_warning = if strategy_uses_automated_input(configured_strategy) {
            match self.context_detector.observe_target_now(target_guard) {
                app_detector::cache::TargetObservation::Matched => None,
                app_detector::cache::TargetObservation::Changed => Some(crate::error::UserError {
                    code: "output_target_changed".into(),
                    details: Some(
                        "The target app changed before output; the full text was copied instead."
                            .into(),
                    ),
                    retry_count: 0,
                }),
                app_detector::cache::TargetObservation::Unavailable => {
                    Some(crate::error::UserError {
                        code: "output_fallback_clipboard".into(),
                        details: Some("The target app could not be verified before output.".into()),
                        retry_count: 0,
                    })
                }
            }
        } else {
            None
        };
        let requested_strategy = if target_warning.is_some() {
            output::InsertionStrategy::ClipboardCopyOnly
        } else {
            configured_strategy
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

        let owned_operation = operation.clone();
        let detector = self.context_detector.clone();
        let expected_target = target_guard.clone();
        let requires_target = strategy_uses_automated_input(effective_strategy);
        let execution_guard = Arc::new(move || {
            !owned_operation.is_cancelled()
                && (!requires_target || detector.target_still_matches_now(&expected_target))
        });
        let copy_operation = operation.clone();
        let output_result = crate::retry_audio::cancellable_retry(
            operation.cancel_receiver(),
            output::output_with_guard(
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
                output::OutputGuards {
                    insert: execution_guard,
                    copy: Arc::new(move || !copy_operation.is_cancelled()),
                },
            ),
        )
        .await;
        let Some(output_result) = output_result.filter(|_| !operation.is_cancelled()) else {
            anyhow::bail!("Voice operation was cancelled during output");
        };
        let mut output_outcome = match output_result {
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
        tracing::debug!("Pre-warming HTTP connection");
        let request = self.shared_client.head(endpoint);
        let request = if managed_cloud {
            crate::with_desktop_client_version(request)
        } else {
            request
        };
        if request
            .timeout(std::time::Duration::from_secs(5))
            .send()
            .await
            .is_err()
        {
            tracing::debug!("HTTP connection pre-warm did not complete");
        }
    }

    /// Pre-warm the active STT and LLM hosts concurrently so the next dictation can reuse TLS.
    /// Called after startup and whenever provider connection settings change.
    pub async fn pre_warm(&self) {
        let Ok(config) = self
            .app_handle
            .state::<storage::ConfigManager>()
            .load()
            .await
        else {
            tracing::debug!("Provider pre-warm skipped because settings could not be read");
            return;
        };

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
    #[test]
    fn recording_startup_is_owned_by_the_operation_cancellation() {
        let source = include_str!("pipeline.rs");
        let startup = source
            .split("pub async fn start_with_options(")
            .nth(1)
            .unwrap()
            .split("let capture_ready_at =")
            .next()
            .unwrap();
        assert!(startup.contains("cancellable_retry(operation.cancel_receiver()"));
    }

    #[test]
    fn polish_provider_policy_preserves_cloud_byok_and_disabled_modes() {
        for (provider, enabled, read_failed, key, expected) in [
            ("cloud", true, false, "", true),
            ("openai", true, false, "synthetic-key", true),
            ("openai", true, false, "", false),
            ("openai", true, false, " \n", false),
            ("openai", true, true, "synthetic-key", false),
            ("ollama", true, false, "", true),
            ("ollama", true, false, "synthetic-key", true),
            ("ollama", true, true, "", false),
            ("cloud", false, false, "", false),
            ("openai", false, false, "synthetic-key", false),
            ("ollama", false, false, "", false),
        ] {
            let config = storage::AppConfig {
                llm_provider: provider.to_string(),
                polish_enabled: enabled,
                ..storage::AppConfig::default()
            };
            assert_eq!(
                super::can_use_polish_provider(&config, read_failed, key),
                expected,
                "provider={provider}, enabled={enabled}, read_failed={read_failed}"
            );
        }
    }

    #[test]
    fn optional_key_provider_does_not_ignore_credential_read_failure() {
        let config = storage::AppConfig {
            llm_provider: "ollama".to_string(),
            polish_enabled: true,
            ..storage::AppConfig::default()
        };
        assert!(super::can_use_polish_provider(&config, false, ""));
        assert!(!super::can_use_polish_provider(&config, true, ""));
        assert!(!super::can_use_polish_provider(
            &config,
            true,
            "synthetic-key"
        ));
    }

    #[test]
    fn credential_read_fallback_preserves_failure_in_history_metadata() {
        let source = include_str!("pipeline.rs");
        let method = source
            .split("let mut credential_read_failed = false;")
            .nth(1)
            .unwrap()
            .split("let llm_start =")
            .next()
            .unwrap();
        assert!(method.contains("credential_read_failed = true"));
        assert!(method.contains("history_output_metadata_with_prior_error("));
    }

    #[test]
    fn disabled_polish_bypasses_llm_credentials_before_provider_selection() {
        let source = include_str!("pipeline.rs");
        let method = source
            .split("let mut credential_read_failed = false;")
            .nth(1)
            .unwrap()
            .split("// Check if polish is enabled")
            .next()
            .unwrap();
        let bypass = method.find("if !config.polish_enabled");
        assert!(
            bypass.is_some(),
            "disabled polish must skip credential reads"
        );
        assert!(bypass.unwrap() < method.find("resolve_llm_config_secret(").unwrap());
    }

    #[test]
    fn stop_requires_start_config_before_selection_and_cleans_only_its_operation() {
        let source = include_str!("pipeline.rs");
        let method = source
            .split("    pub async fn stop(")
            .nth(1)
            .unwrap()
            .split("    async fn save_history(")
            .next()
            .unwrap();
        assert!(method.contains("let Some(config_data)"));
        assert!(method.contains(".cancel(&operation, || self.abort_cleanup(true))"));
        assert!(!method.contains("self.load_config().await"));
        assert!(
            method.find("let Some(config_data)").unwrap()
                < method.find("self.capture_selected_text()").unwrap()
        );
    }

    #[test]
    fn volume_monitor_is_owned_by_its_recording_operation() {
        let source = include_str!("pipeline.rs");
        let monitor = source
            .split("// Volume monitoring task")
            .nth(1)
            .unwrap()
            .split("// Selected text will be captured")
            .next()
            .unwrap();
        assert!(monitor.contains("should_finalize_stt_task("));
        assert!(monitor.contains("session_id"));
        assert!(monitor.contains("update_if_active(&volume_operation"));
    }

    #[test]
    fn learning_owner_cleanup_precedes_idle_publication() {
        let source = include_str!("pipeline.rs");
        for (signature, boundary) in [
            ("    fn finish_operation(", "    fn idle_when_dropped("),
            (
                "    fn idle_when_dropped(",
                "    pub(crate) fn try_voice_start_guard(",
            ),
            ("    fn abort_cleanup(", "    fn clear_stt_session("),
        ] {
            let method = source
                .split(signature)
                .nth(1)
                .unwrap()
                .split(boundary)
                .next()
                .unwrap();
            let clear = method.find("clear_learning_owner()").unwrap();
            assert!(clear < method.find("publish_state(PipelineState::Idle)").unwrap());
        }
    }

    #[test]
    fn learning_owner_is_captured_during_start_and_consumed_during_stop() {
        let source = include_str!("pipeline.rs");
        let start = source
            .split("    pub async fn start_with_options(")
            .nth(1)
            .unwrap()
            .split("    pub async fn stop(")
            .next()
            .unwrap();
        let stop = source
            .split("    async fn stop_recording(")
            .nth(1)
            .unwrap()
            .split("    async fn save_history(")
            .next()
            .unwrap();
        assert!(start.contains(".preloaded_learning_owner"));
        assert!(start.contains("crate::edit_learning::current_generation"));
        assert!(stop.contains(".preloaded_learning_owner"));
        assert!(!stop.contains("crate::edit_learning::current_generation"));
    }

    #[test]
    fn recording_start_reads_config_before_mutating_previous_operation_state() {
        let source = include_str!("pipeline.rs");
        let method = source
            .split("    pub async fn start_with_options(")
            .nth(1)
            .unwrap()
            .split("    pub async fn stop(")
            .next()
            .unwrap();
        assert!(!method.contains("self.load_config().await"));
        let read = method.find(".load()").unwrap();
        for mutation in [
            "crate::edit_learning::cancel",
            "self.begin_operation(",
            "self.clear_retry_audio()",
        ] {
            assert!(read < method.find(mutation).unwrap());
        }
        assert!(method.contains(".await?"));
    }

    #[test]
    fn history_recovery_requires_config_before_recognition_or_context_capture() {
        let source = include_str!("pipeline.rs");
        for (signature, boundary) in [
            (
                "    pub async fn retry_history_transcription(",
                "    pub async fn recover_unfinished_take(",
            ),
            (
                "    pub async fn recover_unfinished_take(",
                "    pub fn abort(",
            ),
        ] {
            let method = source
                .split(signature)
                .nth(1)
                .unwrap()
                .split(boundary)
                .next()
                .unwrap();
            assert!(!method.contains("self.load_config().await"));
            let read = method.find(".load()").unwrap();
            assert!(read < method.find(".recognize_history_audio(").unwrap());
            if let Some(context) = method.find(".snapshot_for_recording_enabled(") {
                assert!(read < context);
            }
            assert!(method.contains(".await?"));
        }
    }

    #[test]
    fn retry_reads_fallible_config_before_taking_retained_audio() {
        let source = include_str!("pipeline.rs");
        let method = source
            .split("    pub async fn retry_transcription(&self)")
            .nth(1)
            .unwrap()
            .split("    fn ensure_voice_idle(")
            .next()
            .unwrap();
        assert!(!method.contains("self.load_config().await"));
        assert!(method.find(".load()").unwrap() < method.find(".take()").unwrap());
        assert!(method.contains(".await?"));
    }

    #[test]
    fn prewarm_does_not_use_default_config_after_a_read_failure() {
        let source = include_str!("pipeline.rs");
        let method = source
            .split("    pub async fn pre_warm(&self) {")
            .nth(1)
            .unwrap()
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(!method.contains("self.load_config().await"));
        assert!(method.contains("let Ok(config)"));
        assert!(method.contains("return;"));
    }

    #[test]
    fn history_save_reads_committed_privacy_before_storage() {
        let source = include_str!("pipeline.rs");
        let method = source
            .split("    async fn save_history(")
            .nth(1)
            .unwrap()
            .split("    fn emit_streaming_insert_result(")
            .next()
            .unwrap();
        assert!(method.contains(".with_current_history_privacy("));
        assert!(!method.contains("let policy = config.history_retention_policy();"));
        assert!(method.contains("prepare_history_retention("));
        assert!(method.contains("history.add_with_audio_sync(entry, &policy, audio)"));
    }

    #[test]
    fn history_retention_respects_current_and_start_privacy_without_changing_audio() {
        let config = crate::storage::AppConfig::default();
        let enabled = crate::storage::HistoryRetentionPolicy::default();
        let disabled = crate::storage::HistoryRetentionPolicy {
            enabled: false,
            ..enabled
        };
        let wav =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        assert!(super::prepare_history_retention(&config, None, Some(wav.clone()), None).is_none());
        assert!(super::prepare_history_retention(
            &config,
            Some((disabled, true)),
            Some(wav.clone()),
            None,
        )
        .is_none());
        let start_disabled = crate::storage::AppConfig {
            history_enabled: false,
            ..config.clone()
        };
        assert!(super::prepare_history_retention(
            &start_disabled,
            Some((enabled, true)),
            Some(wav.clone()),
            None,
        )
        .is_none());
        for status in [
            crate::storage::HISTORY_STATUS_DISMISSED,
            crate::storage::HISTORY_STATUS_SILENT,
            crate::storage::HISTORY_STATUS_RECOGNITION_FAILED,
        ] {
            assert!(super::prepare_history_retention(
                &config,
                Some((enabled, false)),
                Some(wav.clone()),
                Some(status),
            )
            .is_none());
        }
        assert!(super::prepare_history_retention(
            &config,
            Some((enabled, false)),
            Some(wav.clone()),
            None,
        )
        .unwrap()
        .1
        .is_none());
        let result = super::prepare_history_retention(
            &config,
            Some((enabled, true)),
            Some(wav.clone()),
            None,
        )
        .unwrap();
        assert_eq!(result.0, enabled);
        assert_eq!(result.1, Some(wav));
    }

    #[tokio::test]
    async fn current_audio_privacy_saves_text_without_a_recording_in_sqlite() {
        let store = crate::storage::HistoryStore::new(":memory:".into()).unwrap();
        let config = crate::storage::AppConfig::default();
        let wav =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let (policy, audio) = super::prepare_history_retention(
            &config,
            Some((crate::storage::HistoryRetentionPolicy::default(), false)),
            Some(wav),
            None,
        )
        .unwrap();
        let entry = crate::storage::HistoryEntry {
            id: 0,
            created_at: "2026-11-05T10:00:00".to_string(),
            context_profile_id: "general.native".to_string(),
            context_label: "Synthetic app".to_string(),
            context_icon_key: "general".to_string(),
            context_family: crate::app_detector::types::ContextFamily::General,
            browser_access_status: crate::app_detector::types::BrowserAccessStatus::NotApplicable,
            provider_kind: crate::storage::HistoryProviderKind::Local,
            raw_text: "Synthetic transcript".to_string(),
            polished_text: "Synthetic transcript.".to_string(),
            language: None,
            duration_ms: Some(1000),
            active_scene_id: None,
            active_scene_source: None,
            active_scene_name: None,
            active_scene_prompt_chars: None,
            active_scene_prompt_truncated: false,
            output_status: None,
            output_error: None,
            mode: "dictate".to_string(),
            answer_text: None,
            has_audio: audio.is_some(),
        };
        let id = store
            .add_with_audio(entry, &policy, audio)
            .await
            .unwrap()
            .unwrap();
        let stored = store.get(id).await.unwrap().unwrap();
        assert_eq!(stored.raw_text, "Synthetic transcript");
        assert_eq!(stored.polished_text, "Synthetic transcript.");
        assert!(!stored.has_audio);
        assert!(store.read_audio(id).await.unwrap().is_none());
        assert_eq!(store.list(10, 0).await.unwrap().len(), 1);
    }

    #[test]
    fn prewarm_logs_exclude_request_values_without_changing_request_options() {
        let source = include_str!("pipeline.rs");
        let method = source
            .split("    async fn pre_warm_endpoint(")
            .nth(1)
            .unwrap()
            .split("    pub async fn pre_warm(")
            .next()
            .unwrap();
        let logs = method
            .lines()
            .filter(|line| line.contains("tracing::debug!"))
            .collect::<Vec<_>>();
        assert_eq!(logs.len(), 2);
        for log in logs {
            assert!(!log.contains('{'), "Prewarm logs must use fixed messages");
            assert!(!log.contains(", endpoint"));
        }
        assert!(method.contains("self.shared_client.head(endpoint)"));
        assert!(method.contains("crate::with_desktop_client_version(request)"));
        assert!(method.contains("Duration::from_secs(5)"));
    }

    #[test]
    fn stt_error_event_logs_do_not_include_provider_message() {
        let source = include_str!("pipeline.rs");
        let branch = source
            .split("Ok(Some(TranscriptEvent::Error { message })) => {")
            .nth(1)
            .unwrap()
            .split("Err(e) => {")
            .next()
            .unwrap();
        assert!(branch.contains("tracing::error!(\"STT provider reported an error\");"));
        assert!(!branch.contains("tracing::error!(\"STT error: {}\", message)"));
        assert!(branch.contains("AppError::Config(message.clone()).to_user_error()"));
    }

    #[test]
    fn stt_transport_logs_do_not_format_provider_errors() {
        let source = include_str!("pipeline.rs");
        let worker = source
            .split("tracing::info!(\"STT task received abort signal\")")
            .nth(1)
            .unwrap()
            .split("// Signal that STT processing is complete")
            .next()
            .unwrap();
        for message in [
            "STT send audio failed",
            "STT disconnect failed",
            "STT receive failed",
        ] {
            assert!(worker.contains(&format!("tracing::error!(\"{message}\");")));
        }
        assert!(worker.contains("provider.take_retry_audio()"));
        assert!(worker.contains("e.to_user_error()"));
    }

    #[test]
    fn polish_failure_logs_exclude_provider_error_details() {
        let source = include_str!("pipeline.rs");
        let branch = source
            .split("Pipeline aborted after LLM error, skipping output")
            .nth(1)
            .unwrap()
            .split("PolishTextOutcome {")
            .next()
            .unwrap();
        assert!(branch
            .contains("tracing::error!(\"LLM polish failed after partial streaming insert\");"));
        assert!(branch.contains("tracing::error!(\"LLM polish failed\");"));
        assert!(branch.contains("llm_polish_user_error(&e)"));
        assert!(branch.contains("self.copy_streaming_recovery_to_clipboard("));
    }

    fn state_of(state: &AtomicU8) -> super::PipelineState {
        super::PipelineState::from_u8(state.load(Ordering::SeqCst))
    }

    fn claim(
        operations: &super::VoiceOperations,
        state: &AtomicU8,
        from: super::PipelineState,
        to: super::PipelineState,
    ) -> Option<super::VoiceOperationToken> {
        super::claim_pipeline_operation(operations, state, from, to, || {})
    }

    fn publish(
        operations: &super::VoiceOperations,
        operation: &super::VoiceOperationToken,
        state: &AtomicU8,
        next: super::PipelineState,
    ) -> bool {
        operations.update_if_active(operation, || state.store(next.as_u8(), Ordering::SeqCst))
    }

    #[test]
    fn aborted_stop_cannot_overwrite_the_next_recording() {
        use super::PipelineState::*;
        let operations = super::VoiceOperations::default();
        let state = AtomicU8::new(Idle.as_u8());
        let first = claim(&operations, &state, Idle, Preparing).unwrap();
        assert!(publish(&operations, &first, &state, Recording));
        let stopping = claim(&operations, &state, Recording, Transcribing).unwrap();

        operations.abort_current(|| state.store(Idle.as_u8(), Ordering::SeqCst));
        let next = claim(&operations, &state, Idle, Preparing).unwrap();
        assert!(publish(&operations, &next, &state, Recording));

        assert!(stopping.is_cancelled());
        assert!(stopping.abort_flag().load(Ordering::SeqCst));
        assert!(!publish(&operations, &stopping, &state, Polishing));
        assert!(!publish(&operations, &stopping, &state, Outputting));
        assert!(!operations.finish(&stopping, || state.store(Idle.as_u8(), Ordering::SeqCst)));
        assert_eq!(state_of(&state), Recording);
        assert!(!next.is_cancelled());
        assert!(!next.abort_flag().load(Ordering::SeqCst));
    }

    #[test]
    fn stop_after_abort_does_not_resume_the_aborted_recording() {
        use super::PipelineState::*;
        let operations = super::VoiceOperations::default();
        let state = AtomicU8::new(Idle.as_u8());
        let recording = claim(&operations, &state, Idle, Preparing).unwrap();
        assert!(publish(&operations, &recording, &state, Recording));
        operations.abort_current(|| {});

        assert!(claim(&operations, &state, Recording, Transcribing).is_none());
        assert_eq!(state_of(&state), Recording);
    }

    #[test]
    fn competing_start_is_rejected_while_a_run_owns_the_pipeline() {
        use super::PipelineState::*;
        let operations = super::VoiceOperations::default();
        let state = AtomicU8::new(Idle.as_u8());
        let running = claim(&operations, &state, Idle, Transcribing).unwrap();

        assert!(claim(&operations, &state, Idle, Preparing).is_none());
        assert!(!running.is_cancelled());
        assert_eq!(state_of(&state), Transcribing);
    }

    #[tokio::test]
    async fn dropped_ask_draft_returns_the_pipeline_to_idle() {
        use super::PipelineState::*;
        let operations = super::VoiceOperations::default();
        let state = Arc::new(AtomicU8::new(Idle.as_u8()));
        let draft = claim(&operations, &state, Idle, Idle).unwrap();
        let running = {
            let operations = operations.clone();
            let draft = draft.clone();
            let state = state.clone();
            async move {
                let _idle_on_drop = super::OnDrop::new({
                    let operations = operations.clone();
                    let draft = draft.clone();
                    let state = state.clone();
                    move || {
                        operations.cancel(&draft, || state.store(Idle.as_u8(), Ordering::SeqCst));
                    }
                });
                assert!(publish(&operations, &draft, &state, Polishing));
                std::future::pending::<()>().await;
            }
        };

        let cancelled = tokio::time::timeout(std::time::Duration::from_millis(5), running).await;

        assert!(cancelled.is_err());
        assert_eq!(state_of(&state), Idle);
        assert!(draft.is_aborted());
        assert!(claim(&operations, &state, Idle, Preparing).is_some());
    }

    #[tokio::test]
    async fn voice_start_guard_rejects_competing_starts_without_queueing() {
        let lock = std::sync::Arc::new(tokio::sync::Mutex::new(()));
        let first = super::acquire_voice_start_guard(&lock).unwrap();
        assert!(super::acquire_voice_start_guard(&lock).is_err());
        drop(first);
        assert!(super::acquire_voice_start_guard(&lock).is_ok());
    }

    #[tokio::test]
    async fn ordinary_stop_does_not_queue_behind_active_practice() {
        let lock = Arc::new(tokio::sync::Mutex::new(()));
        let practice_guard = lock.clone().lock_owned().await;
        let result = tokio::time::timeout(
            std::time::Duration::from_millis(20),
            super::acquire_voice_stop_guard(&lock, true),
        )
        .await;
        assert!(result.unwrap().is_none());
        drop(practice_guard);
    }

    #[tokio::test]
    async fn ordinary_stop_still_waits_for_normal_recording_setup() {
        let lock = Arc::new(tokio::sync::Mutex::new(()));
        let startup_guard = lock.clone().lock_owned().await;
        let mut stop = Box::pin(super::acquire_voice_stop_guard(&lock, false));
        assert!(
            tokio::time::timeout(std::time::Duration::from_millis(20), &mut stop)
                .await
                .is_err()
        );
        drop(startup_guard);
        assert!(stop.await.is_some());
    }

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
    fn history_metadata_records_successful_insert() {
        let metadata = history_output_metadata_for_insert_result(
            &output::InsertResult::inserted(output::InsertionStrategy::Keyboard, 5),
            false,
        );

        assert_eq!(metadata.status.as_deref(), Some("inserted"));
        assert!(metadata.error.is_none());
    }

    #[test]
    fn history_metadata_records_clipboard_fallback_warning() {
        let warning = crate::error::UserError {
            code: "output_fallback_clipboard".to_string(),
            details: Some("wtype failed".to_string()),
            retry_count: 0,
        };
        let result =
            output::InsertResult::copied_fallback(output::InsertionStrategy::ClipboardCopyOnly, 5)
                .with_warning(&warning);

        let metadata = history_output_metadata_for_insert_result(&result, false);

        assert_eq!(metadata.status.as_deref(), Some("clipboard_fallback"));
        assert_eq!(metadata.error.as_deref(), Some("wtype failed"));
    }

    #[test]
    fn history_metadata_records_fallback_even_when_clipboard_paste_succeeds() {
        let warning = crate::error::UserError {
            code: "output_fallback_clipboard".to_string(),
            details: Some("keyboard failed".to_string()),
            retry_count: 0,
        };
        let result = output::InsertResult::inserted(output::InsertionStrategy::ClipboardPaste, 5)
            .with_warning(&warning);

        let metadata = history_output_metadata_for_insert_result(&result, false);

        assert_eq!(metadata.status.as_deref(), Some("fallback"));
        assert_eq!(metadata.error.as_deref(), Some("keyboard failed"));
    }

    #[test]
    fn history_metadata_keeps_intentional_clipboard_copy_normal() {
        let result =
            output::InsertResult::copied_fallback(output::InsertionStrategy::ClipboardCopyOnly, 5);

        let metadata = history_output_metadata_for_insert_result(&result, true);

        assert_eq!(metadata.status.as_deref(), Some("copied"));
        assert!(metadata.error.is_none());
    }

    #[test]
    fn history_metadata_records_partial_insert() {
        let metadata = history_output_metadata_for_insert_result(
            &output::InsertResult::partially_inserted(
                output::InsertionStrategy::WindowsSendInput,
                2,
            ),
            false,
        );

        assert_eq!(metadata.status.as_deref(), Some("partial"));
        assert!(metadata.error.is_none());
    }

    #[test]
    fn history_metadata_records_failed_clipboard_paste_as_fallback() {
        let result =
            output::InsertResult::copied_fallback(output::InsertionStrategy::ClipboardPaste, 5);

        let metadata = history_output_metadata_for_insert_result(&result, false);

        assert_eq!(metadata.status.as_deref(), Some("clipboard_fallback"));
        assert!(metadata.error.is_none());
    }

    #[test]
    fn history_metadata_records_failed_output() {
        let result = output::InsertResult::failed(output::InsertionStrategy::Keyboard);

        let metadata = history_output_metadata_for_insert_result(&result, false);

        assert_eq!(metadata.status.as_deref(), Some("failed"));
        assert!(metadata.error.is_none());
    }

    #[test]
    fn history_metadata_records_output_error() {
        let error = anyhow::anyhow!("Both keyboard and clipboard output failed");

        let metadata = history_output_metadata_for_output_result(Err(&error), false);

        assert_eq!(metadata.status.as_deref(), Some("failed"));
        assert_eq!(
            metadata.error.as_deref(),
            Some("Both keyboard and clipboard output failed")
        );
    }

    #[test]
    fn only_cancelled_or_failed_takes_offer_recovery() {
        use crate::voice_intent::VoiceMode;
        let event = |status: &str, mode: VoiceMode, provider: &str| {
            take_recovery_event(Some(status), mode, provider)
        };
        assert_eq!(
            event("dismissed", VoiceMode::Dictate, "apple-speech"),
            Some("pipeline:dismissed")
        );
        assert_eq!(
            event("recognition_failed", VoiceMode::Translate, "openai-whisper"),
            Some("pipeline:failed_take")
        );
        assert_eq!(event("silent", VoiceMode::Dictate, "apple-speech"), None);
        assert_eq!(
            event("recognition_failed", VoiceMode::Ask, "apple-speech"),
            None
        );
        assert_eq!(
            event("recognition_failed", VoiceMode::Dictate, "deepgram"),
            None
        );
        assert_eq!(
            take_recovery_event(None, VoiceMode::Dictate, "apple-speech"),
            None
        );
    }

    #[test]
    fn dismissed_takes_offer_undo_only_with_file_based_recognition() {
        for provider in [
            "apple-speech",
            "custom-whisper",
            "openai-whisper",
            "groq-whisper",
        ] {
            assert!(supports_archived_audio_recognition(provider), "{provider}");
        }
        for provider in [
            "cloud",
            "deepgram",
            "assemblyai",
            "volcengine-doubao",
            "unknown",
        ] {
            assert!(!supports_archived_audio_recognition(provider), "{provider}");
        }
        assert_eq!(
            voice_mode_from_history("translate"),
            crate::voice_intent::VoiceMode::Translate
        );
        assert_eq!(
            voice_mode_from_history("ask"),
            crate::voice_intent::VoiceMode::Ask
        );
        assert_eq!(
            voice_mode_from_history("dictate"),
            crate::voice_intent::VoiceMode::Dictate
        );
        assert_eq!(
            voice_mode_from_history(""),
            crate::voice_intent::VoiceMode::Dictate
        );
    }

    #[test]
    fn unfinished_takes_map_to_typeless_history_item_states() {
        let status = |outcome: SttWaitOutcome, dismissed: bool, keep: bool| {
            unfinished_take_status(&outcome, dismissed, keep).and_then(|output| output.status)
        };
        assert_eq!(
            status(SttWaitOutcome::Interrupted, true, false).as_deref(),
            Some("dismissed")
        );
        assert_eq!(
            status(SttWaitOutcome::NoSpeech, true, true).as_deref(),
            Some("dismissed")
        );
        assert_eq!(
            status(SttWaitOutcome::NoSpeech, false, true).as_deref(),
            Some("silent")
        );
        assert_eq!(
            status(SttWaitOutcome::Interrupted, false, true).as_deref(),
            Some("recognition_failed")
        );
        assert_eq!(status(SttWaitOutcome::Interrupted, false, false), None);
        assert_eq!(status(SttWaitOutcome::Text("hi".into()), true, true), None);
    }

    #[test]
    fn history_metadata_keeps_actual_output_status_after_an_earlier_failure() {
        let result = output::InsertResult::partially_inserted(
            output::InsertionStrategy::WindowsSendInput,
            2,
        );

        let metadata = history_output_metadata_with_prior_error(
            Ok(&result),
            false,
            "LLM polish failed: provider unavailable",
        );

        assert_eq!(metadata.status.as_deref(), Some("partial"));
        assert_eq!(
            metadata.error.as_deref(),
            Some("LLM polish failed: provider unavailable; output raw text")
        );
    }

    #[test]
    fn history_metadata_keeps_prior_failure_visible_after_raw_insert_succeeds() {
        let result = output::InsertResult::inserted(output::InsertionStrategy::Keyboard, 5);

        let metadata = history_output_metadata_with_prior_error(
            Ok(&result),
            false,
            "LLM polish failed: provider unavailable",
        );

        assert_eq!(metadata.status.as_deref(), Some("fallback"));
        assert_eq!(
            metadata.error.as_deref(),
            Some("LLM polish failed: provider unavailable; output raw text")
        );
    }

    #[test]
    fn credential_failure_metadata_preserves_raw_output_status_and_normal_copy() {
        let credential_error = "Could not read provider credential from the system vault";
        for (result, intentional_copy, expected_status) in [
            (
                output::InsertResult::inserted(output::InsertionStrategy::Keyboard, 5),
                false,
                "fallback",
            ),
            (
                output::InsertResult::copied_fallback(
                    output::InsertionStrategy::ClipboardCopyOnly,
                    5,
                ),
                true,
                "fallback",
            ),
            (
                output::InsertResult::partially_inserted(
                    output::InsertionStrategy::WindowsSendInput,
                    2,
                ),
                false,
                "partial",
            ),
            (
                output::InsertResult::failed(output::InsertionStrategy::Keyboard),
                false,
                "failed",
            ),
        ] {
            let metadata = history_output_metadata_with_prior_error(
                Ok(&result),
                intentional_copy,
                credential_error,
            );
            assert_eq!(metadata.status.as_deref(), Some(expected_status));
            assert_eq!(
                metadata.error.as_deref(),
                Some("Could not read provider credential from the system vault; output raw text")
            );
        }
        let copied =
            output::InsertResult::copied_fallback(output::InsertionStrategy::ClipboardCopyOnly, 5);
        let normal = history_output_metadata_for_output_result(Ok(&copied), true);
        assert_eq!(normal.status.as_deref(), Some("copied"));
        assert!(normal.error.is_none());
    }

    #[test]
    fn history_metadata_keeps_both_failures_when_raw_output_also_fails() {
        let output_error = anyhow::anyhow!("clipboard unavailable");

        let metadata = history_output_metadata_with_prior_error(
            Err(&output_error),
            false,
            "LLM polish failed: provider unavailable",
        );

        assert_eq!(metadata.status.as_deref(), Some("failed"));
        assert_eq!(
            metadata.error.as_deref(),
            Some("LLM polish failed: provider unavailable; raw output: clipboard unavailable")
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
    fn copy_only_output_does_not_require_the_target_window() {
        assert!(!strategy_uses_automated_input(
            output::InsertionStrategy::ClipboardCopyOnly
        ));
        assert!(strategy_uses_automated_input(
            output::InsertionStrategy::ClipboardPaste
        ));
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
            polish_enabled: false,
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
        assert!(next_config.polish_enabled);
        assert_eq!(next_config.target_lang, "ja");
        assert!(!config.translate_enabled);
        assert!(!config.polish_enabled);
    }

    #[test]
    fn translation_target_can_be_restored_and_changed_again_while_capturing() {
        let mut operation = TranslationOperationState::new("ja".to_string());
        let previous = operation.switch_target("fr".to_string()).unwrap();
        assert_eq!(operation.switch_target(previous), Ok("fr".to_string()));
        assert_eq!(operation.target, "ja");
        assert_eq!(
            operation.switch_target("de".to_string()),
            Ok("ja".to_string())
        );
        assert_eq!(operation.finalize(), "de");
    }

    #[test]
    fn translation_routes_require_generated_output_and_do_not_stream_raw_fallback() {
        let config = storage::AppConfig::default();
        for selected in [None, Some("Synthetic selection")] {
            let intent = route_pipeline_voice_intent(
                crate::voice_intent::VoiceMode::Translate,
                "Synthetic speech",
                selected,
                &config,
            );
            assert!(voice_intent_requires_generated_output(intent.kind));
            let plan = crate::voice_intent::plan_voice_provider_work(
                crate::voice_intent::VoiceMode::Translate,
                "Synthetic speech",
                &intent,
            );
            assert!(!plan.allow_streaming);
            assert_eq!(plan.provider_input.as_deref(), Some("Synthetic speech"));
        }
    }

    #[test]
    fn translation_target_restore_cannot_change_a_finalized_operation() {
        let mut operation = TranslationOperationState::new("ja".to_string());
        let previous = operation.switch_target("fr".to_string()).unwrap();
        assert_eq!(operation.finalize(), "fr");
        assert_eq!(
            operation.switch_target(previous),
            Err("translation_operation_finished")
        );
        assert_eq!(operation.target, "fr");
    }

    #[test]
    fn translation_restore_cannot_change_a_new_recording_with_the_same_target() {
        let original = TranslationOperationState::new("ja".into());
        let owner = original.id.clone();
        let mut replacement = TranslationOperationState::new("ja".into());
        assert!(replacement
            .switch_owned_target(&owner, "fr".into())
            .is_err());
        assert_eq!(replacement.target, "ja");
        let current = replacement.id.clone();
        assert_eq!(
            replacement.switch_owned_target(&current, "fr".into()),
            Ok("ja".into())
        );
    }

    #[test]
    fn translation_restore_cannot_overwrite_a_later_switch_in_the_same_recording() {
        let mut operation = TranslationOperationState::new("ja".into());
        let first = operation.id.clone();
        operation.switch_owned_target(&first, "fr".into()).unwrap();
        let rollback_owner = operation.id.clone();
        operation.switch_target("de".into()).unwrap();
        assert!(operation
            .switch_owned_target(&rollback_owner, "ja".into())
            .is_err());
        assert_eq!(operation.target, "de");
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
        assert_eq!(
            err.details.as_deref(),
            Some("LLM polish failed: llm_quota_exceeded")
        );
    }

    #[test]
    fn polish_user_error_excludes_private_provider_messages() {
        for error in [
            crate::error::AppError::Api {
                status: 503,
                body: "synthetic-secret private spoken words".into(),
            },
            crate::error::AppError::Auth("synthetic-secret private spoken words".into()),
            crate::error::AppError::Config("synthetic-secret private spoken words".into()),
            crate::error::AppError::LlmQuota("synthetic-secret private spoken words".into()),
        ] {
            let serialized = serde_json::to_string(&llm_polish_user_error(&error)).unwrap();
            assert!(!serialized.contains("synthetic-secret"));
            assert!(!serialized.contains("private spoken words"));
        }
    }

    #[test]
    fn polish_history_errors_keep_classification_without_provider_body() {
        for error in [
            crate::error::AppError::Api {
                status: 503,
                body: "synthetic-secret private spoken words".into(),
            },
            crate::error::AppError::Config("synthetic-secret private spoken words".into()),
            crate::error::AppError::LlmQuota("synthetic-secret private spoken words".into()),
        ] {
            let summary = polish_history_error(&error);
            assert!(!summary.contains("synthetic-secret"));
            assert!(!summary.contains("private spoken words"));
            assert!(summary.starts_with("LLM polish failed"));
        }
        assert!(polish_history_error(&crate::error::AppError::Api {
            status: 503,
            body: String::new(),
        })
        .contains("503"));
        assert!(
            polish_history_error(&crate::error::AppError::LlmQuota(String::new()))
                .contains("llm_quota_exceeded")
        );
    }

    #[tokio::test]
    async fn sanitized_polish_failure_survives_history_storage_without_private_body() {
        let directory =
            std::env::temp_dir().join(format!("otl-polish-history-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("history.db");
        let store = storage::HistoryStore::new(filename).unwrap();
        let provider_error = crate::error::AppError::Api {
            status: 503,
            body: "synthetic-secret private provider echo".into(),
        };
        let inserted = output::InsertResult::inserted(output::InsertionStrategy::Keyboard, 20);
        let metadata = history_output_metadata_with_prior_error(
            Ok(&inserted),
            false,
            &polish_history_error(&provider_error),
        );
        let entry = storage::HistoryEntry {
            id: 0,
            created_at: "2026-11-12T10:00:00".into(),
            context_profile_id: "general.native".into(),
            context_label: "Synthetic app".into(),
            context_icon_key: "general".into(),
            context_family: app_detector::types::ContextFamily::General,
            browser_access_status: app_detector::types::BrowserAccessStatus::NotApplicable,
            provider_kind: storage::HistoryProviderKind::Local,
            raw_text: "Synthetic transcript".into(),
            polished_text: "Synthetic transcript".into(),
            language: None,
            duration_ms: Some(1000),
            active_scene_id: None,
            active_scene_source: None,
            active_scene_name: None,
            active_scene_prompt_chars: None,
            active_scene_prompt_truncated: false,
            output_status: metadata.status,
            output_error: metadata.error,
            mode: "dictate".into(),
            answer_text: None,
            has_audio: false,
        };
        let id = store
            .add_with_audio(entry, &storage::HistoryRetentionPolicy::default(), None)
            .await
            .unwrap()
            .unwrap();
        let stored = store.get(id).await.unwrap().unwrap();
        assert_eq!(stored.raw_text, "Synthetic transcript");
        assert_eq!(stored.output_status.as_deref(), Some("fallback"));
        assert_eq!(
            stored.output_error.as_deref(),
            Some("LLM polish failed: HTTP 503; output raw text")
        );
        let serialized = serde_json::to_string(&stored).unwrap();
        assert!(!serialized.contains("synthetic-secret"));
        assert!(!serialized.contains("private provider echo"));
        drop(store);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn llm_polish_user_error_uses_localizable_fallback_code() {
        let err = llm_polish_user_error(&crate::error::AppError::Auth(
            "Cloud LLM access denied".to_string(),
        ));
        assert_eq!(err.code, "llm_failed");
        assert_eq!(
            err.details.as_deref(),
            Some("LLM polish failed: authentication")
        );
    }

    #[test]
    fn incomplete_model_answers_keep_localizable_failure_and_partial_recovery() {
        for message in [
            "Anthropic LLM stream is incomplete",
            "Anthropic LLM response was truncated",
            "Anthropic LLM response requires an unsupported tool call",
            "Anthropic LLM response requires continuation",
            "Anthropic LLM refused the request",
            "LLM response was truncated",
            "LLM response was blocked by the content filter",
            "LLM response requires an unsupported tool call",
            "LLM returned an empty answer",
            "LLM returned an error response",
        ] {
            let error = crate::error::AppError::Config(message.to_string());
            let user_error = llm_polish_user_error(&error);
            assert_eq!(user_error.code, "llm_failed");
            assert_eq!(user_error.retry_count, 0);
            assert_eq!(
                user_error.details.as_deref(),
                Some("LLM polish failed: llm_failed")
            );
            let report = streaming_report_for_test("Synthetic partial text", true);
            assert_eq!(
                streaming_recovery_action(&report, None, false, false),
                StreamingRecoveryAction::CopyPartialToClipboard {
                    reason: "LLM failed after partial streaming insert".to_string()
                }
            );
            let untouched = streaming_report_for_test("", false);
            assert_eq!(
                streaming_recovery_action(&untouched, None, false, false),
                StreamingRecoveryAction::NoRecoveryNeeded
            );
        }
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
