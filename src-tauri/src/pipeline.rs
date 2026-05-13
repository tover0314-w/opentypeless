use anyhow::Result;
use enigo::{Direction, Enigo, Key, Keyboard, Settings as EnigoSettings};
use std::sync::atomic::{AtomicBool, AtomicU32, AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use tauri::Emitter;
use tauri::Manager;
use tokio::sync::Notify;
use tokio::task::JoinHandle;

use crate::app_detector;
use crate::audio::{AudioCaptureHandle, AudioConfig};
use crate::llm::{self, LlmConfig, PolishRequest};
use crate::output::{self, OutputMode};
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

/// On macOS, request Accessibility permission by showing the system authorization dialog.
/// Uses AXIsProcessTrustedWithOptions with kAXTrustedCheckOptionPrompt = true.
/// Returns true if permission is already granted or on non-macOS platforms.
pub fn request_accessibility_permission() -> bool {
    #[cfg(target_os = "macos")]
    {
        #[link(name = "ApplicationServices", kind = "framework")]
        extern "C" {
            fn AXIsProcessTrustedWithOptions(options: *mut std::ffi::c_void) -> u8;
        }
        #[link(name = "CoreFoundation", kind = "framework")]
        extern "C" {
            fn CFDictionaryCreate(
                allocator: *mut std::ffi::c_void,
                keys: *const *mut std::ffi::c_void,
                values: *const *mut std::ffi::c_void,
                num_values: isize,
                key_callbacks: *const std::ffi::c_void,
                value_callbacks: *const std::ffi::c_void,
            ) -> *mut std::ffi::c_void;
            fn CFStringCreateWithCString(
                allocator: *mut std::ffi::c_void,
                c_str: *const i8,
                encoding: u32,
            ) -> *mut std::ffi::c_void;
            static kCFTypeDictionaryKeyCallBacks: std::ffi::c_void;
            static kCFTypeDictionaryValueCallBacks: std::ffi::c_void;
        }
        // kCFBooleanTrue — we link CoreFoundation and use the known address pattern
        #[link(name = "CoreFoundation", kind = "framework")]
        extern "C" {
            static kCFBooleanTrue: *mut std::ffi::c_void;
        }
        // kCFStringEncodingUTF8 = 0x08000100
        const K_CF_STRING_ENCODING_UTF8: u32 = 0x08000100;

        unsafe {
            let key = CFStringCreateWithCString(
                std::ptr::null_mut(),
                b"kAXTrustedCheckOptionPrompt\0".as_ptr() as *const i8,
                K_CF_STRING_ENCODING_UTF8,
            );
            let value = kCFBooleanTrue;

            let options = CFDictionaryCreate(
                std::ptr::null_mut(),
                &[key] as *const *mut std::ffi::c_void,
                &[value] as *const *mut std::ffi::c_void,
                1,
                &kCFTypeDictionaryKeyCallBacks as *const std::ffi::c_void,
                &kCFTypeDictionaryValueCallBacks as *const std::ffi::c_void,
            );

            let trusted = AXIsProcessTrustedWithOptions(options);
            // options is leaked (trivial — called at most a few times)
            trusted != 0
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        true
    }
}

/// Delay before capturing selected text to ensure hotkey modifiers are released.
const SELECTED_TEXT_CAPTURE_DELAY_MS: u64 = 60;
/// Delay after simulating Ctrl+C to let the clipboard update.
const CLIPBOARD_COPY_SETTLE_MS: u64 = 100;
/// Interval for polling audio volume during recording.
const VOLUME_POLL_INTERVAL_MS: u64 = 50;
/// Timeout for STT finalization after recording stops.
const STT_FINALIZE_TIMEOUT_SECS: u64 = 120;

#[derive(Debug, Clone, Copy, PartialEq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum PipelineState {
    Idle,
    Recording,
    Transcribing,
    Polishing,
    Outputting,
}

impl PipelineState {
    fn as_u8(self) -> u8 {
        match self {
            Self::Idle => 0,
            Self::Recording => 1,
            Self::Transcribing => 2,
            Self::Polishing => 3,
            Self::Outputting => 4,
        }
    }

    fn from_u8(v: u8) -> Self {
        match v {
            1 => Self::Recording,
            2 => Self::Transcribing,
            3 => Self::Polishing,
            4 => Self::Outputting,
            _ => Self::Idle,
        }
    }
}

pub struct PipelineHandle {
    app_handle: tauri::AppHandle,
    state: Arc<AtomicU8>,
    audio_handle: Arc<Mutex<Option<AudioCaptureHandle>>>,
    audio_volume: Arc<Mutex<f32>>,
    accumulated_text: Arc<Mutex<String>>,
    stt_done: Arc<Notify>,
    abort_flag: Arc<AtomicBool>,
    preloaded_config: Arc<Mutex<Option<storage::AppConfig>>>,
    preloaded_app_ctx: Arc<Mutex<Option<app_detector::AppContext>>>,
    preloaded_dictionary: Arc<Mutex<Option<Vec<String>>>>,
    preloaded_selected_text: Arc<Mutex<Option<String>>>,
    recording_start: Arc<Mutex<Option<std::time::Instant>>>,
    shared_client: reqwest::Client,
    /// Serializes start()/stop() so that stop() waits for start() to finish
    /// its setup before reading shared state (preloaded_config, audio_handle, etc.).
    /// Without this, a quick press-release in hold mode causes stop() to run
    /// while start() is still connecting to STT, finding empty fields.
    pipeline_lock: tokio::sync::Mutex<()>,

    // ─── Streaming segment processing ───

    /// Text of the most recent Final event from STT — used as segment text when
    /// SpeechEnded fires (streaming providers only).
    last_segment_text: Arc<Mutex<String>>,
    /// Running handles for per-segment LLM + Output background tasks.
    segment_handles: Arc<Mutex<Vec<JoinHandle<Result<String>>>>>,
    /// Count of completed segments (incremented atomically for frontend events).
    segment_count: Arc<AtomicU32>,
    /// Accumulator of polished segment texts for combined history entry.
    segment_polished_texts: Arc<Mutex<Vec<String>>>,
    /// True when the current recording uses a streaming WebSocket provider
    /// (Deepgram / AssemblyAI) that fires SpeechEnded events.
    is_streaming_provider: Arc<AtomicBool>,
}

impl PipelineHandle {
    pub fn new(app_handle: tauri::AppHandle) -> Self {
        Self {
            app_handle,
            state: Arc::new(AtomicU8::new(PipelineState::Idle.as_u8())),
            audio_handle: Arc::new(Mutex::new(None)),
            audio_volume: Arc::new(Mutex::new(0.0)),
            accumulated_text: Arc::new(Mutex::new(String::new())),
            stt_done: Arc::new(Notify::new()),
            abort_flag: Arc::new(AtomicBool::new(false)),
            preloaded_config: Arc::new(Mutex::new(None)),
            preloaded_app_ctx: Arc::new(Mutex::new(None)),
            preloaded_dictionary: Arc::new(Mutex::new(None)),
            preloaded_selected_text: Arc::new(Mutex::new(None)),
            recording_start: Arc::new(Mutex::new(None)),
            shared_client: reqwest::Client::new(),
            pipeline_lock: tokio::sync::Mutex::new(()),
            last_segment_text: Arc::new(Mutex::new(String::new())),
            segment_handles: Arc::new(Mutex::new(Vec::new())),
            segment_count: Arc::new(AtomicU32::new(0)),
            segment_polished_texts: Arc::new(Mutex::new(Vec::new())),
            is_streaming_provider: Arc::new(AtomicBool::new(false)),
        }
    }

    fn set_state(&self, new_state: PipelineState) {
        self.state.store(new_state.as_u8(), Ordering::SeqCst);
        let _ = self.app_handle.emit("pipeline:state", new_state);

        // Update tray tooltip + menu to reflect pipeline state
        if let Some(tray_handle) = self.app_handle.try_state::<crate::TrayHandle>() {
            let tooltip = match new_state {
                PipelineState::Recording => "OpenTypeless - Recording...",
                PipelineState::Transcribing => "OpenTypeless - Transcribing...",
                PipelineState::Polishing => "OpenTypeless - Polishing...",
                PipelineState::Outputting => "OpenTypeless - Outputting...",
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

    /// Immediately abort the pipeline regardless of current state.
    /// Stops audio capture, forces state to Idle, and signals any
    /// ongoing stop() to exit early via abort_flag.
    pub fn abort(&self) {
        tracing::info!("Pipeline abort requested (current state: {:?})", self.current_state());

        // Set abort flag so any running stop() or segment tasks exit early
        self.abort_flag.store(true, Ordering::SeqCst);

        // Stop audio capture (closes channel → STT task terminates naturally)
        {
            let mut handle = self.audio_handle.lock().unwrap_or_else(|e| e.into_inner());
            if let Some(ref mut h) = *handle {
                h.stop();
            }
            *handle = None;
        }

        // Cancel any running segment processing tasks
        {
            let mut handles = self.segment_handles.lock().unwrap_or_else(|e| e.into_inner());
            for h in handles.drain(..) {
                h.abort();
            }
        }

        // Unblock stop() if it's waiting on stt_done.notified()
        self.stt_done.notify_one();

        // Clear accumulated text and segment state
        self.accumulated_text.lock().unwrap_or_else(|e| e.into_inner()).clear();
        self.last_segment_text.lock().unwrap_or_else(|e| e.into_inner()).clear();
        self.segment_polished_texts.lock().unwrap_or_else(|e| e.into_inner()).clear();
        self.segment_count.store(0, Ordering::SeqCst);
        self.is_streaming_provider.store(false, Ordering::SeqCst);

        // Force state to Idle — emits pipeline:state event to sync frontend
        self.set_state(PipelineState::Idle);
    }

    /// Capture selected text from the foreground app by simulating Ctrl+C / Cmd+C.
    /// Must be called when no hotkey modifier keys are physically held down.
    /// Called from async context via block_in_place, so std::thread::sleep is acceptable.
    fn capture_selected_text(&self) -> Option<String> {
        let mut clipboard = arboard::Clipboard::new().ok()?;
        let backup = clipboard.get_text().ok();

        if let Ok(mut enigo) = Enigo::new(&EnigoSettings::default()) {
            #[cfg(target_os = "macos")]
            let modifier = Key::Meta;
            #[cfg(not(target_os = "macos"))]
            let modifier = Key::Control;

            let pressed = enigo.key(modifier, Direction::Press).is_ok();
            if pressed {
                let _ = enigo.key(Key::Unicode('c'), Direction::Click);
                let _ = enigo.key(modifier, Direction::Release);
            }
        }

        std::thread::sleep(std::time::Duration::from_millis(CLIPBOARD_COPY_SETTLE_MS));

        let selected = clipboard.get_text().ok();

        // Always restore clipboard
        if let Some(ref b) = backup {
            let _ = clipboard.set_text(b);
        }

        tracing::info!(
            "Selected text capture: backup_len={}, selected_len={}",
            backup.as_deref().map(|s| s.len()).unwrap_or(0),
            selected.as_deref().map(|s| s.len()).unwrap_or(0)
        );

        // On macOS, if Cmd+C had no effect (e.g., no Accessibility permission),
        // the clipboard is unchanged, so selected == backup — return None to avoid
        // passing stale clipboard content to the LLM as if it were selected text.
        match &selected {
            Some(s) if !s.trim().is_empty() => {
                if backup.as_deref() == Some(s.as_str()) {
                    tracing::debug!(
                        "Selected text equals clipboard backup — Cmd+C had no effect, ignoring"
                    );
                    None
                } else {
                    Some(s.clone())
                }
            }
            _ => None,
        }
    }

    async fn load_config(&self) -> storage::AppConfig {
        self.app_handle
            .state::<storage::ConfigManager>()
            .load()
            .await
            .unwrap_or_default()
    }

    pub async fn start(&self) -> Result<()> {
        // Hold pipeline_lock for the entire setup so stop() cannot read
        // partially-initialised state (preloaded_config, audio_handle, etc.).
        let _guard = self.pipeline_lock.lock().await;

        // Reset abort flag for new recording
        self.abort_flag.store(false, Ordering::SeqCst);

        // Atomic CAS: only one caller can transition Idle → Recording
        if self
            .state
            .compare_exchange(
                PipelineState::Idle.as_u8(),
                PipelineState::Recording.as_u8(),
                Ordering::SeqCst,
                Ordering::SeqCst,
            )
            .is_err()
        {
            return Ok(());
        }
        let _ = self
            .app_handle
            .emit("pipeline:state", PipelineState::Recording);
        // Update tray for recording state
        if let Some(tray_handle) = self.app_handle.try_state::<crate::TrayHandle>() {
            if let Ok(t) = tray_handle.tray.lock() {
                let _ = t.set_tooltip(Some("OpenTypeless - Recording..."));
            }
        }
        crate::refresh_tray(&self.app_handle);

        // Clear accumulated text
        self.accumulated_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clear();

        // P0-2: Load config BEFORE starting audio capture — fail fast on missing API key
        let config_data = self.load_config().await;
        *self
            .preloaded_config
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(config_data.clone());
        *self
            .preloaded_app_ctx
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(app_detector::detect_current_app());
        let dict_words = self
            .app_handle
            .state::<storage::DictionaryStore>()
            .words()
            .await;
        *self
            .preloaded_dictionary
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(dict_words);

        tracing::debug!(
            "Pipeline using config: stt_provider={}, stt_key_len={}, stt_lang={}",
            config_data.stt_provider,
            config_data.stt_api_key.len(),
            config_data.stt_language
        );

        // Guard: empty API key — bail before starting audio (skip for cloud provider)
        if config_data.stt_api_key.is_empty() && config_data.stt_provider != "cloud" {
            let _ = self.app_handle.emit(
                "pipeline:error",
                "STT API key is not configured. Please set it in Settings → Speech Recognition.",
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
            self.set_state(PipelineState::Idle);
            return Ok(());
        }

        // P0-3: Pre-connect STT provider before spawning task
        let stt_api_key = if config_data.stt_provider == "cloud" {
            self.app_handle
                .state::<SessionTokenStore>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        } else {
            config_data.stt_api_key.clone()
        };

        let stt_config = SttConfig {
            api_key: stt_api_key,
            language: if config_data.stt_language == "multi" {
                None
            } else {
                Some(config_data.stt_language.clone())
            },
            smart_format: true,
            sample_rate: 16000,
        };

        let mut provider =
            stt::create_provider(&config_data.stt_provider, Some(self.shared_client.clone()));
        if let Err(e) = provider.connect(&stt_config).await {
            tracing::error!("STT connect failed: {}", e);
            let _ = self
                .app_handle
                .emit("pipeline:error", format!("STT connection failed: {e}"));
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
            self.set_state(PipelineState::Idle);
            return Ok(());
        }

        // Start audio capture on dedicated thread
        let config = AudioConfig::default();
        let (handle, mut audio_rx) = match AudioCaptureHandle::start(config) {
            Ok(result) => result,
            Err(e) => {
                tracing::error!("Audio capture failed: {}", e);
                let _ = self.app_handle.emit(
                    "pipeline:error",
                    format!("Audio capture failed: {e}"),
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
            self.set_state(PipelineState::Idle);
            return Ok(());
        }
        let audio_vol = handle.get_volume();
        *self.audio_volume.lock().unwrap_or_else(|e| e.into_inner()) = audio_vol;
        *self.audio_handle.lock().unwrap_or_else(|e| e.into_inner()) = Some(handle);

        *self
            .recording_start
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(std::time::Instant::now());

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

        // Reset segment tracking state for new recording
        self.last_segment_text.lock().unwrap_or_else(|e| e.into_inner()).clear();
        self.segment_handles.lock().unwrap_or_else(|e| e.into_inner()).clear();
        self.segment_polished_texts.lock().unwrap_or_else(|e| e.into_inner()).clear();
        self.segment_count.store(0, Ordering::SeqCst);

        // ─── STT streaming task with streaming segment support ───
        let app_handle = self.app_handle.clone();
        let accumulated = self.accumulated_text.clone();
        let stt_done = self.stt_done.clone();

        // Clone segment-processing shared state
        let segment_handles = self.segment_handles.clone();
        let segment_count = self.segment_count.clone();
        let segment_polished_texts = self.segment_polished_texts.clone();
        let last_seg_text_shared = self.last_segment_text.clone();
        let is_streaming_provider = self.is_streaming_provider.clone();
        let abort_flag = self.abort_flag.clone();

        // Clone config data for per-segment LLM+Output processing
        let cfg = self
            .preloaded_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .unwrap_or_default();
        let app_ctx = self
            .preloaded_app_ctx
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .unwrap_or_else(app_detector::detect_current_app);
        let dictionary_words = self
            .preloaded_dictionary
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone()
            .unwrap_or_default();
        let shared_client = self.shared_client.clone();

        let polish_enabled = cfg.polish_enabled
            && (!cfg.llm_api_key.is_empty() || cfg.llm_provider == "cloud");
        let llm_provider_name = cfg.llm_provider.clone();
        let llm_api_key = if cfg.llm_provider == "cloud" {
            self.app_handle
                .state::<SessionTokenStore>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        } else {
            cfg.llm_api_key.clone()
        };
        let llm_config = if polish_enabled {
            Some(crate::llm::LlmConfig {
                api_key: llm_api_key,
                model: cfg.llm_model.clone(),
                base_url: cfg.llm_base_url.clone(),
                max_tokens: 4096,
                temperature: 0.3,
            })
        } else {
            None
        };
        let output_mode_str = cfg.output_mode.clone();

        // Determine if provider supports streaming (SpeechEnded events)
        let is_streaming = matches!(cfg.stt_provider.as_str(), "deepgram" | "assemblyai");
        is_streaming_provider.store(is_streaming, Ordering::SeqCst);

        let seg_config = SegProcessorConfig {
            app_handle: app_handle.clone(),
            abort_flag: abort_flag.clone(),
            segment_polished_texts: segment_polished_texts.clone(),
            polish_enabled,
            llm_config,
            llm_provider_name,
            shared_client,
            app_ctx,
            dictionary_words,
            translate_enabled: cfg.translate_enabled,
            target_lang: cfg.target_lang.clone(),
            output_mode_str,
        };

        tokio::spawn(async move {
            let mut last_seg_text = String::new();
            let mut has_streamed = false;

            // Forward audio to STT and receive transcripts
            loop {
                tokio::select! {
                    chunk = audio_rx.recv() => {
                        match chunk {
                            Some(data) => {
                                let _ = provider.send_audio(&data).await;
                            }
                            None => {
                                // Audio channel closed — flush last segment
                                let seg_text = std::mem::take(&mut last_seg_text);
                                if !seg_text.trim().is_empty() {
                                    segment_count.fetch_add(1, Ordering::SeqCst);
                                    let _ = app_handle.emit("pipeline:segment_count", segment_count.load(Ordering::SeqCst));
                                    let cfg = seg_config.clone_for_task();
                                    let h = tokio::spawn(async move {
                                        process_segment_text(seg_text, cfg).await
                                    });
                                    segment_handles.lock().unwrap_or_else(|e| e.into_inner()).push(h);
                                }

                                // For non-streaming providers: disconnect to get final transcript
                                if !has_streamed {
                                    match provider.disconnect().await {
                                        Ok(Some(text)) => {
                                            let mut acc = accumulated.lock().unwrap_or_else(|e| e.into_inner());
                                            acc.push_str(&text);
                                            let current = acc.clone();
                                            drop(acc);
                                            let _ = app_handle.emit("stt:final", &current);
                                            // Process as single batch segment
                                            segment_count.fetch_add(1, Ordering::SeqCst);
                                            let cfg = seg_config.clone_for_task();
                                            let h = tokio::spawn(async move {
                                                process_segment_text(text, cfg).await
                                            });
                                            segment_handles.lock().unwrap_or_else(|e| e.into_inner()).push(h);
                                        }
                                        Ok(None) => {}
                                        Err(e) => {
                                            tracing::error!("STT disconnect error: {}", e);
                                            let _ = app_handle.emit("pipeline:error", format!("STT error: {e}"));
                                        }
                                    }
                                }
                                break;
                            }
                        }
                    }
                    transcript = provider.recv_transcript() => {
                        match transcript {
                            Ok(Some(TranscriptEvent::Partial { text })) => {
                                *last_seg_text_shared.lock().unwrap_or_else(|e| e.into_inner()) = text.clone();
                                let _ = app_handle.emit("stt:partial", &text);
                            }
                            Ok(Some(TranscriptEvent::Final { text, .. })) => {
                                last_seg_text = text.clone();
                                let mut acc = accumulated.lock().unwrap_or_else(|e| e.into_inner());
                                acc.push_str(&text);
                                acc.push(' ');
                                let current = acc.clone();
                                drop(acc);
                                let _ = app_handle.emit("stt:final", &current);
                            }
                            Ok(Some(TranscriptEvent::SpeechEnded)) => {
                                has_streamed = true;
                                let seg_text = std::mem::take(&mut last_seg_text);
                                if seg_text.trim().is_empty() { continue; }

                                segment_count.fetch_add(1, Ordering::SeqCst);
                                let _ = app_handle.emit("pipeline:segment_count", segment_count.load(Ordering::SeqCst));

                                let cfg = seg_config.clone_for_task();
                                let h = tokio::spawn(async move {
                                    process_segment_text(seg_text, cfg).await
                                });
                                segment_handles.lock().unwrap_or_else(|e| e.into_inner()).push(h);
                            }
                            Ok(Some(TranscriptEvent::Error { message })) => {
                                tracing::error!("STT error: {}", message);
                                let _ = app_handle.emit("pipeline:error", format!("STT error: {message}"));
                                break;
                            }
                            Err(e) => {
                                tracing::error!("STT recv error: {}", e);
                                break;
                            }
                            _ => {}
                        }
                    }
                }
            }

            // Wait for all segment processing tasks to complete
            let handles = std::mem::take(&mut *segment_handles.lock().unwrap_or_else(|e| e.into_inner()));
            for h in handles {
                match h.await {
                    Ok(Ok(polished)) => {
                        segment_polished_texts.lock().unwrap_or_else(|e| e.into_inner()).push(polished);
                    }
                    Ok(Err(e)) => {
                        tracing::error!("Segment processing error: {}", e);
                        let _ = app_handle.emit("pipeline:error", format!("Output error: {e}"));
                    }
                    Err(_) => {
                        // Task was cancelled by abort() — ignore
                    }
                }
            }

            // Signal that all processing is done
            stt_done.notify_one();
        });

        Ok(())
    }

    pub async fn stop(&self) -> Result<()> {
        // Acquire pipeline_lock so we wait for start() to finish its setup
        // (load_config, connect STT, start audio) before reading shared state.
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
        let _ = self
            .app_handle
            .emit("pipeline:state", PipelineState::Transcribing);
        // Update tray for transcribing state
        if let Some(tray_handle) = self.app_handle.try_state::<crate::TrayHandle>() {
            if let Ok(t) = tray_handle.tray.lock() {
                let _ = t.set_tooltip(Some("OpenTypeless - Transcribing..."));
            }
        }
        crate::refresh_tray(&self.app_handle);

        let stop_start = std::time::Instant::now();

        // PTT guard: if recording was too short, treat as accidental press and bail.
        let should_discard = {
            let rec_start = self
                .recording_start
                .lock()
                .unwrap_or_else(|e| e.into_inner());
            let elapsed = rec_start.as_ref().map(|s| s.elapsed().as_millis() as u32);
            let min_ms = {
                let cfg = self
                    .preloaded_config
                    .lock()
                    .unwrap_or_else(|e| e.into_inner());
                cfg.as_ref().map(|c| c.min_recording_ms).unwrap_or(200)
            };
            elapsed.map(|e| e < min_ms).unwrap_or(false)
        };

        if should_discard {
            tracing::info!("Recording too short (< min_recording_ms), discarding");
            // Stop audio capture to unblock the STT task
            {
                let mut handle = self.audio_handle.lock().unwrap_or_else(|e| e.into_inner());
                if let Some(ref mut h) = *handle {
                    h.stop();
                }
                *handle = None;
            }
            self.set_state(PipelineState::Idle);
            return Ok(());
        }

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

        // P2-1: Pre-build LLM resources while waiting for STT
        let preloaded_config = self
            .preloaded_config
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();
        let config = match preloaded_config {
            Some(c) => c,
            None => self.load_config().await,
        };
        let app_ctx = self
            .preloaded_app_ctx
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .unwrap_or_else(app_detector::detect_current_app);
        let dictionary_words = self
            .preloaded_dictionary
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
            .unwrap_or_default();
        let selected_text = self
            .preloaded_selected_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take();

        // All shared state has been taken — release the lock so a new start()
        // isn't blocked by the long stt_done wait that follows.
        drop(guard);

        // Check if we're using streaming (segment-based) processing
        let is_streaming = self.is_streaming_provider.load(Ordering::SeqCst);

        // Pre-build LLM provider for non-streaming path (streaming does per-segment LLM)
        let pre_llm = if !is_streaming
            && config.polish_enabled
            && (!config.llm_api_key.is_empty() || config.llm_provider == "cloud")
        {
            let llm_api_key = if config.llm_provider == "cloud" {
                self.app_handle
                    .state::<SessionTokenStore>()
                    .0
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .clone()
            } else {
                config.llm_api_key.clone()
            };

            let llm_config = LlmConfig {
                api_key: llm_api_key,
                model: config.llm_model.clone(),
                base_url: config.llm_base_url.clone(),
                max_tokens: 4096,
                temperature: 0.3,
            };
            let provider =
                llm::create_provider(&config.llm_provider, Some(self.shared_client.clone()));
            Some((llm_config, provider))
        } else {
            None
        };

        // Wait for STT task to finish (handles both streaming and file-based providers)
        // Timeout after 120s to support long recordings
        let stt_done = self.stt_done.clone();
        tokio::select! {
            _ = stt_done.notified() => {
                tracing::debug!("STT task completed");
            }
            _ = tokio::time::sleep(std::time::Duration::from_secs(STT_FINALIZE_TIMEOUT_SECS)) => {
                tracing::warn!("STT task timed out after {}s, using accumulated text so far", STT_FINALIZE_TIMEOUT_SECS);
            }
        }

        let stt_elapsed = stop_start.elapsed();
        tracing::info!(
            "[Pipeline Timing] STT finalize: {}ms",
            stt_elapsed.as_millis()
        );

        // Check if pipeline was aborted while waiting for STT
        if self.abort_flag.load(Ordering::SeqCst) {
            tracing::info!("Pipeline aborted after STT wait, skipping LLM and output");
            return Ok(());
        }

        let raw_text = self
            .accumulated_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .trim()
            .to_string();

        // Handle streaming mode differently from batch mode
        let final_text: String;
        let llm_elapsed;

        if is_streaming {
            // For streaming: segments were already polished + output in background.
            // Collect polished results for history.
            let seg_results = std::mem::take(
                &mut *self.segment_polished_texts.lock().unwrap_or_else(|e| e.into_inner()),
            );
            final_text = if seg_results.is_empty() {
                raw_text.clone()
            } else {
                seg_results.join(" ")
            };
            llm_elapsed = std::time::Duration::ZERO;
        } else {
            // Non-streaming (file-upload providers): process everything as one batch
            // ── Current behavior: LLM + output ──

            if raw_text.is_empty() {
                let _ = self
                    .app_handle
                    .emit("pipeline:error", "No speech detected. Please try again.");
                self.set_state(PipelineState::Idle);
                return Ok(());
            }

            // Check abort before entering LLM polish and output
            if self.abort_flag.load(Ordering::SeqCst) {
                tracing::info!("Pipeline aborted before LLM/output");
                return Ok(());
            }

            if let Some((llm_config, provider)) = pre_llm {
                self.set_state(PipelineState::Polishing);
                let llm_start = std::time::Instant::now();

                let app_handle = self.app_handle.clone();
                let on_chunk: llm::ChunkCallback = Box::new(move |chunk: &str| {
                    let _ = app_handle.emit("llm:chunk", chunk);
                });

                let req = PolishRequest {
                    raw_text: raw_text.clone(),
                    app_type: app_ctx.app_type,
                    dictionary: dictionary_words,
                    translate_enabled: config.translate_enabled,
                    target_lang: config.target_lang.clone(),
                    selected_text,
                };

                match provider.polish(&llm_config, &req, Some(&on_chunk)).await {
                    Ok(response) => {
                        if self.abort_flag.load(Ordering::SeqCst) {
                            tracing::info!("Pipeline aborted after LLM polish, skipping output");
                            return Ok(());
                        }
                        final_text = response.polished_text;
                        llm_elapsed = llm_start.elapsed();

                        if let Err(e) = self
                            .output_text(&final_text, &app_ctx.app_name, &config)
                            .await
                        {
                            tracing::error!("Output failed: {}", e);
                            let _ = self
                                .app_handle
                                .emit("pipeline:error", format!("Output failed: {e}"));
                        }
                    }
                    Err(e) => {
                        if self.abort_flag.load(Ordering::SeqCst) {
                            tracing::info!("Pipeline aborted after LLM error, skipping output");
                            return Ok(());
                        }
                        tracing::error!("LLM polish failed: {}, outputting raw text", e);
                        final_text = raw_text.clone();
                        llm_elapsed = llm_start.elapsed();

                        let _ = self
                            .app_handle
                            .emit("pipeline:error", format!("LLM polishing failed: {e}"));
                        if let Err(e) = self
                            .output_text(&final_text, &app_ctx.app_name, &config)
                            .await
                        {
                            tracing::error!("Output failed: {}", e);
                            let _ = self
                                .app_handle
                                .emit("pipeline:error", format!("Output failed: {e}"));
                        }
                    }
                }

                tracing::info!(
                    "[Pipeline Timing] LLM polish: {}ms",
                    llm_elapsed.as_millis()
                );
            } else {
                llm_elapsed = std::time::Duration::ZERO;
                final_text = raw_text.clone();
                if let Err(e) = self
                    .output_text(&final_text, &app_ctx.app_name, &config)
                    .await
                {
                    tracing::error!("Output failed: {}", e);
                    let _ = self
                        .app_handle
                        .emit("pipeline:error", format!("Output failed: {e}"));
                }
            }
        }

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

        // Save to history
        let now = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
        let entry = storage::HistoryEntry {
            id: 0, // auto-increment
            created_at: now,
            app_name: app_ctx.app_name,
            app_type: format!("{:?}", app_ctx.app_type),
            raw_text,
            polished_text: final_text,
            language: None,
            duration_ms,
        };
        if let Err(e) = self
            .app_handle
            .state::<storage::HistoryStore>()
            .add(entry)
            .await
        {
            tracing::error!("Failed to save history: {}", e);
        }

        self.set_state(PipelineState::Idle);
        Ok(())
    }

    async fn output_text(
        &self,
        text: &str,
        app_name: &str,
        config: &storage::AppConfig,
    ) -> Result<()> {
        self.set_state(PipelineState::Outputting);

        let mode = if config.output_mode == "keyboard" {
            OutputMode::Keyboard
        } else {
            OutputMode::Clipboard
        };

        // On macOS, keyboard output uses CGEventPost via enigo which requires
        // Accessibility permission. Clipboard mode uses osascript which does not.
        if mode == OutputMode::Keyboard && !is_accessibility_trusted() {
            anyhow::bail!("ACCESSIBILITY_REQUIRED");
        }

        let output = output::create_output(mode);
        output.type_text(text).await?;

        let _ = self.app_handle.emit("pipeline:target_app", app_name);

        Ok(())
    }

    /// P1-2: Pre-warm HTTP connection pool by issuing a HEAD request to the STT endpoint.
    /// Call once after app startup to avoid cold-start TLS handshake on first recording.
    pub async fn pre_warm(&self) {
        let config = self.load_config().await;

        // Pre-warm STT endpoint
        let stt_endpoint = match config.stt_provider.as_str() {
            "cloud" => {
                let base = crate::api_base_url();
                format!("{}/api/proxy/stt", base)
            }
            "glm-asr" => "https://open.bigmodel.cn/api/paas/v4/audio/transcriptions".to_string(),
            "openai-whisper" => "https://api.openai.com/v1/audio/transcriptions".to_string(),
            "groq-whisper" => "https://api.groq.com/openai/v1/audio/transcriptions".to_string(),
            "siliconflow" => "https://api.siliconflow.cn/v1/audio/transcriptions".to_string(),
            "deepgram" => "https://api.deepgram.com/v1/listen".to_string(),
            "assemblyai" => "https://api.assemblyai.com/v2/transcript".to_string(),
            _ => {
                tracing::debug!(
                    "Unknown STT provider '{}', skipping pre-warm",
                    config.stt_provider
                );
                return;
            }
        };
        tracing::debug!("Pre-warming HTTP connection to {}", stt_endpoint);
        let _ = self
            .shared_client
            .head(&stt_endpoint)
            .timeout(std::time::Duration::from_secs(5))
            .send()
            .await;
        tracing::debug!("STT connection pre-warm complete");

        // Pre-warm LLM endpoint if polish is enabled
        if config.polish_enabled {
            let llm_url = if config.llm_provider == "cloud" {
                let base = crate::api_base_url();
                format!("{}/api/proxy/llm", base)
            } else {
                config.llm_base_url.clone()
            };
            tracing::debug!("Pre-warming LLM connection to {}", llm_url);
            let _ = self
                .shared_client
                .head(&llm_url)
                .timeout(std::time::Duration::from_secs(5))
                .send()
                .await;
            tracing::debug!("LLM connection pre-warm complete");
        }
    }
}

// ─── Streaming segment processing ───

/// Configuration that each segment-processing task needs to run LLM + Output.
/// Created once in start() and cheaply cloned via clone_for_task() for each
/// background task.
struct SegProcessorConfig {
    app_handle: tauri::AppHandle,
    abort_flag: Arc<AtomicBool>,
    segment_polished_texts: Arc<Mutex<Vec<String>>>,
    polish_enabled: bool,
    llm_config: Option<crate::llm::LlmConfig>,
    llm_provider_name: String,
    shared_client: reqwest::Client,
    app_ctx: app_detector::AppContext,
    dictionary_words: Vec<String>,
    translate_enabled: bool,
    target_lang: String,
    output_mode_str: String,
}

impl SegProcessorConfig {
    /// Clone the fields that the segment task needs, as a new owned copy.
    /// Arc fields share the underlying allocation; String/Vec fields clone their data.
    fn clone_for_task(&self) -> Self {
        Self {
            app_handle: self.app_handle.clone(),
            abort_flag: self.abort_flag.clone(),
            segment_polished_texts: self.segment_polished_texts.clone(),
            polish_enabled: self.polish_enabled,
            llm_config: self.llm_config.clone(),
            llm_provider_name: self.llm_provider_name.clone(),
            shared_client: self.shared_client.clone(),
            app_ctx: self.app_ctx.clone(),
            dictionary_words: self.dictionary_words.clone(),
            translate_enabled: self.translate_enabled,
            target_lang: self.target_lang.clone(),
            output_mode_str: self.output_mode_str.clone(),
        }
    }
}

/// Process a single segment: LLM polish → keyboard/clipboard output.
/// Runs in a background task, concurrent with recording of the next segment.
async fn process_segment_text(text: String, cfg: SegProcessorConfig) -> Result<String> {
    if cfg.abort_flag.load(Ordering::SeqCst) {
        return Ok(text);
    }

    let polished: String;

    if cfg.polish_enabled {
        if let Some(ref llm_cfg) = cfg.llm_config {
            let provider = crate::llm::create_provider(&cfg.llm_provider_name, Some(cfg.shared_client.clone()));
            let app_handle = cfg.app_handle.clone();
            let on_chunk: crate::llm::ChunkCallback = Box::new(move |chunk: &str| {
                let _ = app_handle.emit("llm:chunk", chunk);
            });

            let req = crate::llm::PolishRequest {
                raw_text: text.clone(),
                app_type: cfg.app_ctx.app_type,
                dictionary: cfg.dictionary_words,
                translate_enabled: cfg.translate_enabled,
                target_lang: cfg.target_lang,
                selected_text: None, // selected text captured in stop(); not available per-segment
            };

            match provider.polish(llm_cfg, &req, Some(&on_chunk)).await {
                Ok(response) => {
                    polished = response.polished_text;
                }
                Err(e) => {
                    tracing::error!("Segment LLM polish failed: {}, using raw text", e);
                    let _ = cfg.app_handle.emit("pipeline:error", format!("LLM polish error: {e}"));
                    polished = text;
                }
            }
        } else {
            polished = text;
        }
    } else {
        polished = text;
    }

    // Output (sequential: each segment outputs independently)
    if !cfg.abort_flag.load(Ordering::SeqCst) && !polished.trim().is_empty() {
        let mode = if cfg.output_mode_str == "keyboard" {
            crate::output::OutputMode::Keyboard
        } else {
            crate::output::OutputMode::Clipboard
        };

        // On macOS keyboard mode, check Accessibility permission
        if mode == crate::output::OutputMode::Keyboard && !is_accessibility_trusted() {
            let _ = cfg.app_handle.emit("pipeline:error", "ACCESSIBILITY_REQUIRED");
            return Ok(polished); // don't bail — let other segments and history continue
        }

        let output = crate::output::create_output(mode);
        if let Err(e) = output.type_text(&polished).await {
            tracing::error!("Segment output failed: {}", e);
            let _ = cfg.app_handle.emit("pipeline:error", format!("Output error: {e}"));
        }
    }

    Ok(polished)
}
