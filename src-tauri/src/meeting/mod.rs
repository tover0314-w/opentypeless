//! Meeting / long-recording mode (MEL-75 — recording half).
//!
//! Layout mirrors the MEL-74 design §5.4 module boundaries:
//! - [`engine`]  — UI-agnostic core (state machine + segmentation timing).
//!                 Zero Tauri / SQLite / cpal dependencies; portable to a
//!                 future wearable host as `meeting_core`.
//! - [`adapters`] — desktop glue (cpal capture channel, Tauri event bus).
//! - [`MeetingHandle`] — Tauri state object that owns the engine, drives the
//!                 segment timer, and enforces mic exclusivity with the
//!                 instant-transcription pipeline.
//!
//! Transcription + SQLite persistence are MEL-76. MEL-75 wires the engine with
//! the in-memory stubs (`engine::stub`) so the recording path runs end-to-end
//! today; MEL-76 swaps in the real `Transcriber` / `MeetingStore`.

pub mod adapters;
pub mod engine;

use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

use tauri::Manager;

use crate::audio::{AudioCaptureHandle, AudioConfig};
use engine::{MeetingConfig, MeetingState, SessionMeta};

/// How often the segment timer pulls audio and checks the cut / cap
/// thresholds. 250 ms is fine-grained enough that a 5-min boundary is hit
/// within a quarter second while costing almost nothing.
const TICK_INTERVAL_MS: u64 = 250;

/// The concrete engine type the desktop host runs: real cpal audio, real
/// `SttProvider`-backed transcriber, real SQLite store, and Tauri events
/// (MEL-76 swapped in the real transcriber + store over MEL-75's stubs).
type DesktopEngine = engine::MeetingEngine<
    adapters::ChannelAudioSource,
    adapters::SttTranscriber,
    crate::storage::meeting::MeetingDbStore,
    adapters::TauriMeetingEvents,
>;

/// Tauri-managed handle for the meeting recording mode. Lives alongside
/// `PipelineHandle` in app state; the two never record at the same time
/// (design §1.2).
pub struct MeetingHandle {
    app_handle: tauri::AppHandle,
    config: MeetingConfig,
    /// Mirrors the engine state without locking the engine, so the instant
    /// pipeline's exclusivity guard is a cheap atomic read.
    active: Arc<AtomicBool>,
    /// The running engine + its capture handle. `None` when idle.
    inner: Arc<Mutex<Option<RunningMeeting>>>,
    /// Serializes start()/stop() the same way `PipelineHandle::pipeline_lock`
    /// does, so a fast start→stop can't read half-initialized state.
    op_lock: tokio::sync::Mutex<()>,
}

struct RunningMeeting {
    engine: DesktopEngine,
    capture: AudioCaptureHandle,
}

impl MeetingHandle {
    pub fn new(app_handle: tauri::AppHandle) -> Self {
        Self {
            app_handle,
            config: MeetingConfig::default(),
            active: Arc::new(AtomicBool::new(false)),
            inner: Arc::new(Mutex::new(None)),
            op_lock: tokio::sync::Mutex::new(()),
        }
    }

    /// True while a meeting is recording or finalizing. The instant pipeline
    /// reads this to refuse starting (design §1.2).
    pub fn is_active(&self) -> bool {
        self.active.load(Ordering::SeqCst)
    }

    /// Build the real STT transcriber for this meeting from the app's STT
    /// settings. Mirrors the instant pipeline's provider/key/custom-config
    /// resolution (`pipeline::run` §P0-3) so meeting transcription uses the
    /// exact same provider the user configured.
    async fn build_transcriber(
        &self,
        provider_name: &str,
        language: Option<String>,
    ) -> Result<adapters::SttTranscriber, String> {
        use crate::stt::config;

        let cfg = self
            .app_handle
            .state::<crate::storage::ConfigManager>()
            .load()
            .await
            .map_err(|e| format!("Failed to load config: {e}"))?;

        // Custom-Whisper needs its base URL + model resolved into a config.
        let custom_whisper_config = if provider_name == config::CUSTOM_WHISPER_PROVIDER {
            Some(
                config::build_custom_whisper_config(&cfg.stt_custom_base_url, &cfg.stt_custom_model)?,
            )
        } else {
            None
        };

        // API key source matches the pipeline: cloud → session token,
        // custom-whisper → its own key, everything else → the hosted key.
        let api_key = if provider_name == "cloud" {
            self.app_handle
                .state::<crate::SessionTokenStore>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone()
        } else if provider_name == config::CUSTOM_WHISPER_PROVIDER {
            cfg.stt_custom_api_key.clone()
        } else {
            cfg.stt_api_key.clone()
        };

        if config::stt_provider_requires_api_key(provider_name) && api_key.is_empty() {
            return Err(
                "STT API key is not configured. Please set it in Settings -> Speech Recognition."
                    .to_string(),
            );
        }

        let stt_config = crate::stt::SttConfig {
            api_key,
            language,
            smart_format: true,
            sample_rate: AudioConfig::default().sample_rate,
        };

        let client = self
            .app_handle
            .try_state::<reqwest::Client>()
            .map(|c| (*c).clone())
            .unwrap_or_default();

        Ok(adapters::SttTranscriber::new(
            provider_name.to_string(),
            stt_config,
            custom_whisper_config,
            client,
        ))
    }

    /// Start a meeting recording.
    ///
    /// Refuses if a meeting is already active **or** the instant pipeline is
    /// not idle — the two modes must never share the mic (design §1.2).
    pub async fn start(&self, stt_provider: String, language: Option<String>) -> Result<(), String> {
        let _guard = self.op_lock.lock().await;

        if self.is_active() {
            return Err("A meeting recording is already in progress".to_string());
        }

        // Mic exclusivity: the instant-transcription pipeline must be idle.
        if let Some(pipeline) = self
            .app_handle
            .try_state::<crate::pipeline::PipelineHandle>()
        {
            if pipeline.current_state() != crate::pipeline::PipelineState::Idle {
                return Err(
                    "Instant dictation is active; finish it before starting a meeting".to_string(),
                );
            }
        }

        // Build the real STT transcriber from the app's STT config before we
        // open the mic, so a misconfiguration fails fast.
        let transcriber = self
            .build_transcriber(&stt_provider, language.clone())
            .await?;

        // The SQLite meeting store is managed Tauri state; clone the handle.
        let store = self
            .app_handle
            .try_state::<crate::storage::meeting::MeetingDbStore>()
            .map(|s| (*s).clone())
            .ok_or_else(|| "Meeting store is not initialized".to_string())?;

        // Reuse the existing cpal capture pipeline; only the consumer differs.
        let (capture, audio_rx) = AudioCaptureHandle::start(AudioConfig::default())
            .map_err(|e| format!("Audio capture failed: {e}"))?;

        let sample_rate = AudioConfig::default().sample_rate;
        let audio = adapters::ChannelAudioSource::new(audio_rx, sample_rate);
        let events = adapters::TauriMeetingEvents::new(self.app_handle.clone());

        let mut eng =
            engine::MeetingEngine::new(audio, transcriber, store, events, self.config, sample_rate);

        let created_at = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
        eng.start(SessionMeta {
            created_at,
            stt_provider,
            language,
        })?;

        *self.inner.lock().unwrap_or_else(|e| e.into_inner()) = Some(RunningMeeting {
            engine: eng,
            capture,
        });
        self.active.store(true, Ordering::SeqCst);

        self.spawn_tick_loop();
        Ok(())
    }

    /// Manually stop and finalize the in-flight meeting (design §1.3).
    pub async fn stop(&self) -> Result<(), String> {
        let _guard = self.op_lock.lock().await;
        self.finalize_and_clear().await
    }

    /// Drive the engine tick loop on a timer until the engine leaves the
    /// `Recording` state (manual stop or the 3-hour auto-cap).
    fn spawn_tick_loop(&self) {
        let inner = self.inner.clone();
        let active = self.active.clone();
        let app_handle = self.app_handle.clone();
        tauri::async_runtime::spawn(async move {
            loop {
                tokio::time::sleep(std::time::Duration::from_millis(TICK_INTERVAL_MS)).await;

                // Take the engine out, tick it, put it back. The engine isn't
                // Send-bound across await inside the lock, so we briefly own it.
                let mut running = {
                    let mut guard = inner.lock().unwrap_or_else(|e| e.into_inner());
                    match guard.take() {
                        Some(r) => r,
                        None => break, // stopped elsewhere
                    }
                };

                if running.engine.state() != MeetingState::Recording {
                    // Auto-finalize already happened, or we're done.
                    running.capture.stop();
                    active.store(false, Ordering::SeqCst);
                    break;
                }

                let auto_finalized = match running.engine.tick().await {
                    Ok(v) => v,
                    Err(e) => {
                        tracing::error!("Meeting tick error: {}", e);
                        let _ = app_handle.emit_meeting_error(&e);
                        running.capture.stop();
                        active.store(false, Ordering::SeqCst);
                        break;
                    }
                };

                if auto_finalized {
                    running.capture.stop();
                    active.store(false, Ordering::SeqCst);
                    tracing::info!("Meeting auto-finalized at duration cap");
                    break;
                }

                // Put the engine back for the next tick.
                *inner.lock().unwrap_or_else(|e| e.into_inner()) = Some(running);
            }
        });
    }

    /// Finalize the current meeting (if any) and clear state.
    async fn finalize_and_clear(&self) -> Result<(), String> {
        let mut running = match self.inner.lock().unwrap_or_else(|e| e.into_inner()).take() {
            Some(r) => r,
            None => return Ok(()), // already idle
        };

        let result = running.engine.finalize().await;
        running.capture.stop();
        self.active.store(false, Ordering::SeqCst);
        result
    }
}

/// Small extension so the tick loop can emit a meeting error without pulling
/// `Emitter` into scope at every call site.
trait MeetingErrorEmit {
    fn emit_meeting_error(&self, message: &str) -> Result<(), tauri::Error>;
}

impl MeetingErrorEmit for tauri::AppHandle {
    fn emit_meeting_error(&self, message: &str) -> Result<(), tauri::Error> {
        use tauri::Emitter;
        self.emit("meeting:error", message)
    }
}
