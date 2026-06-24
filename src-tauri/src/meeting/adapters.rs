//! Desktop (Tauri / cpal) implementations of the `meeting::engine` trait
//! boundaries. These are the host-specific glue; the engine itself stays
//! UI-agnostic.
//!
//! MEL-75 shipped the audio + events adapters. MEL-76 adds [`SttTranscriber`],
//! the real `Transcriber` that wraps the existing `SttProvider` family
//! (glm-asr / cloud / whisper-compat / deepgram / …) to turn one segment's PCM
//! into text. The SQLite `MeetingStore` impl lives in
//! `crate::storage::meeting::MeetingDbStore`.

use async_trait::async_trait;
use tauri::Emitter;
use tokio::sync::mpsc;

use super::engine::{MeetingEvent, MeetingEvents, PcmChunk, SegStatus, Transcriber};
use crate::stt::{self, SttConfig, TranscriptEvent};
use crate::stt::whisper_compat::WhisperCompatConfig;

/// Adapts the existing cpal capture channel (`AudioCaptureHandle` →
/// `mpsc::Receiver<Vec<u8>>`) to the engine's non-blocking `AudioSource`.
///
/// The meeting core reuses the *same* dedicated-thread capture pipeline as
/// instant transcription (design §0.2): it only swaps the consumer end.
pub struct ChannelAudioSource {
    rx: mpsc::Receiver<Vec<u8>>,
    sample_rate: u32,
}

impl ChannelAudioSource {
    pub fn new(rx: mpsc::Receiver<Vec<u8>>, sample_rate: u32) -> Self {
        Self { rx, sample_rate }
    }
}

impl super::engine::AudioSource for ChannelAudioSource {
    fn try_recv(&mut self) -> Option<PcmChunk> {
        match self.rx.try_recv() {
            Ok(bytes) => Some(PcmChunk {
                bytes,
                sample_rate: self.sample_rate,
            }),
            Err(_) => None, // Empty or Disconnected → nothing ready this tick.
        }
    }
}

/// Forwards engine events onto the Tauri event bus as `meeting:*` events,
/// reusing the existing `app_handle.emit` pattern (design §1.4).
pub struct TauriMeetingEvents {
    app_handle: tauri::AppHandle,
}

impl TauriMeetingEvents {
    pub fn new(app_handle: tauri::AppHandle) -> Self {
        Self { app_handle }
    }
}

fn seg_status_str(status: SegStatus) -> &'static str {
    match status {
        SegStatus::Pending => "pending",
        SegStatus::Transcribing => "transcribing",
        SegStatus::Done => "done",
        SegStatus::Failed => "failed",
    }
}

impl MeetingEvents for TauriMeetingEvents {
    fn emit(&self, ev: MeetingEvent) {
        match ev {
            MeetingEvent::State(state) => {
                let _ = self.app_handle.emit("meeting:state", state);
            }
            MeetingEvent::Segment { idx, status, text } => {
                let _ = self.app_handle.emit(
                    "meeting:segment",
                    serde_json::json!({
                        "index": idx,
                        "status": seg_status_str(status),
                        "text": text,
                    }),
                );
            }
            MeetingEvent::Progress { done, total } => {
                let _ = self.app_handle.emit(
                    "meeting:progress",
                    serde_json::json!({ "done": done, "total": total }),
                );
            }
            MeetingEvent::Error(message) => {
                let _ = self.app_handle.emit("meeting:error", message);
            }
        }
    }
}

/// Real transcriber: wraps the existing `SttProvider` family. Each segment is
/// transcribed as an independent one-shot (connect → send whole segment →
/// drain), mirroring the instant-pipeline lifecycle but for a closed buffer.
///
/// `is_streaming()` distinguishes realtime websocket providers (deepgram /
/// assemblyai — transcribe each segment the moment it is cut, design §3B) from
/// file-upload providers (glm-asr / cloud / whisper — text comes back from
/// `disconnect()`, deferred to finalize, design §3A).
pub struct SttTranscriber {
    provider_name: String,
    config: SttConfig,
    custom_whisper_config: Option<WhisperCompatConfig>,
    client: reqwest::Client,
}

impl SttTranscriber {
    pub fn new(
        provider_name: String,
        config: SttConfig,
        custom_whisper_config: Option<WhisperCompatConfig>,
        client: reqwest::Client,
    ) -> Self {
        Self {
            provider_name,
            config,
            custom_whisper_config,
            client,
        }
    }

    /// Streaming providers expose a realtime websocket and yield transcripts
    /// via `recv_transcript`; everything else uploads a finished buffer.
    fn provider_is_streaming(name: &str) -> bool {
        matches!(name, "deepgram" | "assemblyai")
    }
}

#[async_trait]
impl Transcriber for SttTranscriber {
    async fn transcribe_segment(&self, pcm: &[u8], _sample_rate: u32) -> Result<String, String> {
        let mut provider = stt::create_provider(
            &self.provider_name,
            self.custom_whisper_config.clone(),
            Some(self.client.clone()),
        )
        .map_err(|e| e.to_string())?;

        provider
            .connect(&self.config)
            .await
            .map_err(|e| e.to_string())?;

        // Feed the whole segment. cpal capture and the engine both run 16 kHz
        // mono 16-bit, so the bytes go straight through.
        provider
            .send_audio(pcm)
            .await
            .map_err(|e| e.to_string())?;

        if Self::provider_is_streaming(&self.provider_name) {
            // Streaming provider: drain final transcripts, then close. The
            // websocket has no EOF, so we poll until the buffer is exhausted
            // or it reports an error.
            let mut text = String::new();
            loop {
                match provider.recv_transcript().await {
                    Ok(Some(TranscriptEvent::Final { text: t, .. })) => {
                        if !t.is_empty() {
                            if !text.is_empty() {
                                text.push(' ');
                            }
                            text.push_str(&t);
                        }
                    }
                    Ok(Some(TranscriptEvent::Error { message })) => {
                        let _ = provider.disconnect().await;
                        return Err(message);
                    }
                    Ok(Some(_)) => continue, // Partial / speech markers: ignore.
                    Ok(None) => break,       // Stream closed.
                    Err(e) => {
                        let _ = provider.disconnect().await;
                        return Err(e.to_string());
                    }
                }
            }
            let _ = provider.disconnect().await;
            Ok(text)
        } else {
            // File-upload provider: the transcript comes from disconnect().
            let result = provider.disconnect().await.map_err(|e| e.to_string())?;
            Ok(result.unwrap_or_default())
        }
    }

    fn is_streaming(&self) -> bool {
        Self::provider_is_streaming(&self.provider_name)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::meeting::engine::AudioSource;

    #[test]
    fn channel_source_yields_chunks_then_none() {
        let (tx, rx) = mpsc::channel::<Vec<u8>>(4);
        tx.try_send(vec![1, 2, 3, 4]).unwrap();
        let mut src = ChannelAudioSource::new(rx, 16000);

        let chunk = src.try_recv().expect("first chunk present");
        assert_eq!(chunk.bytes, vec![1, 2, 3, 4]);
        assert_eq!(chunk.sample_rate, 16000);

        // Channel now empty → None (non-blocking).
        assert!(src.try_recv().is_none());
    }

    #[test]
    fn streaming_providers_are_classified_correctly() {
        assert!(SttTranscriber::provider_is_streaming("deepgram"));
        assert!(SttTranscriber::provider_is_streaming("assemblyai"));
        // File-upload providers are not streaming.
        assert!(!SttTranscriber::provider_is_streaming("glm-asr"));
        assert!(!SttTranscriber::provider_is_streaming("cloud"));
        assert!(!SttTranscriber::provider_is_streaming("custom-whisper"));
        assert!(!SttTranscriber::provider_is_streaming("openai-whisper"));
    }

    #[test]
    fn seg_status_strings_are_stable() {
        assert_eq!(seg_status_str(SegStatus::Pending), "pending");
        assert_eq!(seg_status_str(SegStatus::Transcribing), "transcribing");
        assert_eq!(seg_status_str(SegStatus::Done), "done");
        assert_eq!(seg_status_str(SegStatus::Failed), "failed");
    }
}
