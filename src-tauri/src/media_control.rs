//! Pauses system media while dictating and resumes only what we paused.
//!
//! Windows only (System Media Transport Controls, the same sessions the media keys
//! and browser/player overlays use); a no-op elsewhere.

use crate::pipeline::PipelineState;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum MediaAction {
    Pause,
    Resume,
}

/// Pause as soon as dictation starts preparing, resume once the microphone is closed.
pub fn action_for_state(state: PipelineState) -> MediaAction {
    match state {
        PipelineState::Preparing | PipelineState::Recording | PipelineState::AskRecording => {
            MediaAction::Pause
        }
        _ => MediaAction::Resume,
    }
}

pub fn apply(action: MediaAction) {
    #[cfg(target_os = "windows")]
    imp::send(action);
    #[cfg(not(target_os = "windows"))]
    let _ = action;
}

#[cfg(target_os = "windows")]
mod imp {
    use super::MediaAction;
    use std::sync::{mpsc, OnceLock};
    use windows::Media::Control::{
        GlobalSystemMediaTransportControlsSessionManager as Manager,
        GlobalSystemMediaTransportControlsSessionPlaybackStatus as Status,
    };
    use windows::Win32::System::WinRT::{RoInitialize, RO_INIT_MULTITHREADED};

    // One worker thread keeps pause/resume ordered; WinRT calls block, so never run them
    // on the async runtime.
    static TX: OnceLock<mpsc::Sender<MediaAction>> = OnceLock::new();

    pub fn send(action: MediaAction) {
        let tx = TX.get_or_init(|| {
            let (tx, rx) = mpsc::channel();
            std::thread::spawn(move || {
                // Already-initialized apartments return an error we can ignore.
                let _ = unsafe { RoInitialize(RO_INIT_MULTITHREADED) };
                let mut paused: Vec<String> = Vec::new();
                for action in rx {
                    let result = match action {
                        MediaAction::Pause => pause(&mut paused),
                        MediaAction::Resume => resume(&mut paused),
                    };
                    if let Err(error) = result {
                        tracing::warn!("Media control {:?} failed: {}", action, error);
                    }
                }
            });
            tx
        });
        let _ = tx.send(action);
    }

    fn pause(paused: &mut Vec<String>) -> windows::core::Result<()> {
        // Already holding sessions we paused: a second Pause must not forget them.
        let manager = Manager::RequestAsync()?.get()?;
        for session in manager.GetSessions()? {
            if session.GetPlaybackInfo()?.PlaybackStatus()? != Status::Playing {
                continue;
            }
            if session.TryPauseAsync()?.get()? {
                paused.push(session.SourceAppUserModelId()?.to_string());
            }
        }
        Ok(())
    }

    fn resume(paused: &mut Vec<String>) -> windows::core::Result<()> {
        if paused.is_empty() {
            return Ok(());
        }
        let manager = Manager::RequestAsync()?.get()?;
        for session in manager.GetSessions()? {
            let id = session.SourceAppUserModelId()?.to_string();
            if paused.contains(&id)
                && session.GetPlaybackInfo()?.PlaybackStatus()? == Status::Paused
            {
                session.TryPlayAsync()?.get()?;
            }
        }
        paused.clear();
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn pauses_while_dictating_and_resumes_when_the_mic_closes() {
        for state in [
            PipelineState::Preparing,
            PipelineState::Recording,
            PipelineState::AskRecording,
        ] {
            assert_eq!(action_for_state(state), MediaAction::Pause, "{state:?}");
        }
        for state in [
            PipelineState::Idle,
            PipelineState::Transcribing,
            PipelineState::Polishing,
            PipelineState::Outputting,
            PipelineState::AskThinking,
        ] {
            assert_eq!(action_for_state(state), MediaAction::Resume, "{state:?}");
        }
    }
}
