//! Desktop (Tauri / cpal) implementations of the `meeting::engine` trait
//! boundaries. These are the host-specific glue; the engine itself stays
//! UI-agnostic. The `Transcriber` and `MeetingStore` adapters that wrap the
//! real `SttProvider` and SQLite are intentionally **out of scope for MEL-75**
//! and land in MEL-76 — this task ships only the audio + events adapters plus
//! the engine's in-memory stubs (`engine::stub`).

use tauri::Emitter;
use tokio::sync::mpsc;

use super::engine::{MeetingEvent, MeetingEvents, PcmChunk, SegStatus};

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
    fn seg_status_strings_are_stable() {
        assert_eq!(seg_status_str(SegStatus::Pending), "pending");
        assert_eq!(seg_status_str(SegStatus::Transcribing), "transcribing");
        assert_eq!(seg_status_str(SegStatus::Done), "done");
        assert_eq!(seg_status_str(SegStatus::Failed), "failed");
    }
}
