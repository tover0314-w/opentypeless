use anyhow::Result;
use enigo::{Direction, Enigo, Key, Keyboard, Settings as EnigoSettings};
use std::sync::atomic::{AtomicU8, Ordering};
use std::sync::{Arc, Mutex};
use tauri::Emitter;
use tauri::Manager;
use tokio::sync::Notify;

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
fn is_accessibility_trusted() -> bool {
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

#[cfg(target_os = "macos")]
pub(crate) fn output_mode_requires_accessibility(mode: OutputMode) -> bool {
    matches!(mode, OutputMode::Keyboard)
}

#[cfg(not(target_os = "macos"))]
pub(crate) fn output_mode_requires_accessibility(_mode: OutputMode) -> bool {
    false
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
    preloaded_config: Arc<Mutex<Option<storage::AppConfig>>>,
    preloaded_app_ctx: Arc<Mutex<Option<app_detector::AppContext>>>,
    preloaded_dictionary: Arc<Mutex<Option<Vec<String>>>>,
    preloaded_selected_text: Arc<Mutex<Option<String>>>,
    recording_start: Arc<Mutex<Option<std::time::Instant>>>,
    shared_client: reqwest::Client,
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
            preloaded_config: Arc::new(Mutex::new(None)),
            preloaded_app_ctx: Arc::new(Mutex::new(None)),
            preloaded_dictionary: Arc::new(Mutex::new(None)),
            preloaded_selected_text: Arc::new(Mutex::new(None)),
            recording_start: Arc::new(Mutex::new(None)),
            shared_client: reqwest::Client::new(),
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
        let (handle, mut audio_rx) = AudioCaptureHandle::start(config)?;

        // Store the audio handle's volume reference
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

        // STT streaming task — provider is already connected
        let app_handle = self.app_handle.clone();
        let accumulated = self.accumulated_text.clone();
        let stt_done = self.stt_done.clone();

        tokio::spawn(async move {
            // Forward audio to STT and receive transcripts
            loop {
                tokio::select! {
                    chunk = audio_rx.recv() => {
                        match chunk {
                            Some(data) => {
                                let _ = provider.send_audio(&data).await;
                            }
                            None => {
                                // Audio channel closed — disconnect and capture final transcript
                                match provider.disconnect().await {
                                    Ok(Some(text)) => {
                                        let mut acc = accumulated.lock().unwrap_or_else(|e| e.into_inner());
                                        acc.push_str(&text);
                                        let current = acc.clone();
                                        drop(acc);
                                        let _ = app_handle.emit("stt:final", &current);
                                    }
                                    Ok(None) => {}
                                    Err(e) => {
                                        tracing::error!("STT disconnect error: {}", e);
                                        let _ = app_handle.emit("pipeline:error", format!("STT error: {e}"));
                                    }
                                }
                                break;
                            }
                        }
                    }
                    transcript = provider.recv_transcript() => {
                        match transcript {
                            Ok(Some(TranscriptEvent::Partial { text })) => {
                                let _ = app_handle.emit("stt:partial", &text);
                            }
                            Ok(Some(TranscriptEvent::Final { text, .. })) => {
                                let mut acc = accumulated.lock().unwrap_or_else(|e| e.into_inner());
                                acc.push_str(&text);
                                acc.push(' ');
                                let current = acc.clone();
                                drop(acc);
                                let _ = app_handle.emit("stt:final", &current);
                            }
                            Ok(Some(TranscriptEvent::Error { message })) => {
                                tracing::error!("STT error: {}", message);
                                let _ = app_handle.emit("pipeline:error", format!("STT error: {message}"));
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

            // Signal that STT processing is complete
            stt_done.notify_one();
        });

        Ok(())
    }

    pub async fn stop(&self) -> Result<()> {
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

        // Always use batch output: keyboard mode uses output_text() after full LLM
        // response arrives. Streaming chunk-by-chunk clipboard paste was unreliable
        // on Windows — each Ctrl+V is async and the next set_text() could overwrite
        // the clipboard before the target app processed the previous paste, producing
        // garbled output that differed from what History recorded.

        // Pre-build LLM provider and Enigo while STT is still processing
        let pre_llm = if config.polish_enabled
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

        let raw_text = self
            .accumulated_text
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .trim()
            .to_string();

        if raw_text.is_empty() {
            let _ = self
                .app_handle
                .emit("pipeline:error", "No speech detected. Please try again.");
            self.set_state(PipelineState::Idle);
            return Ok(());
        }

        let final_text;
        let llm_elapsed;

        // Polish with LLM (resources already pre-built)
        if let Some((llm_config, provider)) = pre_llm {
            self.set_state(PipelineState::Polishing);
            let llm_start = std::time::Instant::now();

            // on_chunk only drives the UI transcript display; actual output happens
            // in batch after the full response arrives (see output_text below).
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

        // On macOS, only direct keyboard simulation relies on CGEventPost.
        // Clipboard mode uses osascript paste and should remain available
        // without Accessibility permission.
        if output_mode_requires_accessibility(mode) && !is_accessibility_trusted() {
            anyhow::bail!(
                "Accessibility permission is required to type text. \
                 Please go to System Settings → Privacy & Security → Accessibility \
                 and enable OpenTypeless."
            );
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn clipboard_output_accessibility_requirement_matches_platform_rules() {
        assert!(!output_mode_requires_accessibility(OutputMode::Clipboard));
    }

    #[test]
    fn keyboard_output_accessibility_requirement_matches_platform_rules() {
        #[cfg(target_os = "macos")]
        assert!(output_mode_requires_accessibility(OutputMode::Keyboard));

        #[cfg(not(target_os = "macos"))]
        assert!(!output_mode_requires_accessibility(OutputMode::Keyboard));
    }
}
