//! Mutes system output while the user dictates (Typeless `audio:mute` / `audio:unmute`), so
//! music or video playback doesn't bleed into the microphone. Only state we changed is
//! restored, and a marker file lets the next launch undo a mute left behind by a crash.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::time::Duration;
use tauri::Manager;

/// Typeless waits ~500 ms after the start cue so the cue itself isn't silenced.
const MUTE_DELAY: Duration = Duration::from_millis(500);
const MARKER_FILE: &str = "background-mute.marker";

#[derive(Default)]
pub struct BackgroundMute {
    muted_by_us: AtomicBool,
    session: AtomicU64,
}

#[derive(Debug, PartialEq, Eq)]
enum MuteAction {
    MuteAfterDelay(u64),
    Unmute,
    Nothing,
}

impl BackgroundMute {
    fn on_recording_changed(&self, recording: bool) -> MuteAction {
        let session = self.session.fetch_add(1, Ordering::SeqCst) + 1;
        if recording {
            return MuteAction::MuteAfterDelay(session);
        }
        if self.muted_by_us.swap(false, Ordering::SeqCst) {
            MuteAction::Unmute
        } else {
            MuteAction::Nothing
        }
    }

    /// Claims the mute for `session` unless recording already ended or the user had muted.
    fn claim_mute(&self, session: u64, system_already_muted: bool) -> bool {
        if self.session.load(Ordering::SeqCst) != session || system_already_muted {
            return false;
        }
        !self.muted_by_us.swap(true, Ordering::SeqCst)
    }

    fn release_claim(&self) {
        self.muted_by_us.store(false, Ordering::SeqCst);
    }
}

pub fn is_supported() -> bool {
    cfg!(target_os = "macos")
}

#[cfg(target_os = "macos")]
fn run_osascript(script: &str) -> Option<String> {
    let output = std::process::Command::new("/usr/bin/osascript")
        .args(["-e", script])
        .output()
        .ok()?;
    output
        .status
        .success()
        .then(|| String::from_utf8_lossy(&output.stdout).trim().to_string())
}

#[cfg(target_os = "macos")]
fn system_output_muted() -> Option<bool> {
    run_osascript("output muted of (get volume settings)").map(|value| value == "true")
}

#[cfg(target_os = "macos")]
fn set_system_output_muted(muted: bool) -> bool {
    run_osascript(&format!("set volume output muted {muted}")).is_some()
}

#[cfg(not(target_os = "macos"))]
fn system_output_muted() -> Option<bool> {
    None
}

#[cfg(not(target_os = "macos"))]
fn set_system_output_muted(_muted: bool) -> bool {
    false
}

fn marker_path(app: &tauri::AppHandle) -> Option<std::path::PathBuf> {
    app.path()
        .app_data_dir()
        .ok()
        .map(|dir| dir.join(MARKER_FILE))
}

fn unmute_now(app: &tauri::AppHandle) {
    if set_system_output_muted(false) {
        if let Some(path) = marker_path(app) {
            let _ = std::fs::remove_file(path);
        }
    }
}

/// Undo a mute left behind if the app quit or crashed mid-recording.
pub fn restore_after_unclean_exit(app: &tauri::AppHandle) {
    if marker_path(app).is_some_and(|path| path.exists()) {
        tracing::info!("Restoring system audio muted by a previous session");
        unmute_now(app);
    }
}

/// Called whenever the dictation or Ask pipeline changes state.
pub fn on_recording_changed(app: &tauri::AppHandle, recording: bool) {
    if !is_supported() {
        return;
    }
    let Some(state) = app.try_state::<BackgroundMute>() else {
        return;
    };
    match state.on_recording_changed(recording) {
        MuteAction::Nothing => {}
        MuteAction::Unmute => {
            let app = app.clone();
            tauri::async_runtime::spawn_blocking(move || unmute_now(&app));
        }
        MuteAction::MuteAfterDelay(session) => {
            let app = app.clone();
            tauri::async_runtime::spawn(async move {
                let enabled = app
                    .state::<crate::storage::ConfigManager>()
                    .load()
                    .await
                    .map(|config| config.mute_background_audio)
                    .unwrap_or(false);
                if !enabled {
                    return;
                }
                tokio::time::sleep(MUTE_DELAY).await;
                let _ = tauri::async_runtime::spawn_blocking(move || {
                    let state = app.state::<BackgroundMute>();
                    let already_muted = system_output_muted().unwrap_or(true);
                    if !state.claim_mute(session, already_muted) {
                        return;
                    }
                    if set_system_output_muted(true) {
                        if let Some(path) = marker_path(&app) {
                            let _ = std::fs::write(path, b"1");
                        }
                    } else {
                        state.release_claim();
                    }
                })
                .await;
            });
        }
    }
}

/// Restores audio on quit if a recording was still muting it.
pub fn restore_on_exit(app: &tauri::AppHandle) {
    if let Some(state) = app.try_state::<BackgroundMute>() {
        if state.muted_by_us.swap(false, Ordering::SeqCst) {
            unmute_now(app);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn recording_schedules_a_mute_and_stopping_unmutes_only_what_we_muted() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected a scheduled mute");
        };
        assert!(mute.claim_mute(session, false));
        assert_eq!(mute.on_recording_changed(false), MuteAction::Unmute);
        assert_eq!(mute.on_recording_changed(false), MuteAction::Nothing);
    }

    #[test]
    fn a_user_mute_is_left_alone() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected a scheduled mute");
        };
        assert!(!mute.claim_mute(session, true));
        assert_eq!(mute.on_recording_changed(false), MuteAction::Nothing);
    }

    #[test]
    fn a_short_take_that_ends_before_the_delay_never_mutes() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected a scheduled mute");
        };
        assert_eq!(mute.on_recording_changed(false), MuteAction::Nothing);
        assert!(!mute.claim_mute(session, false));
    }

    #[test]
    fn a_failed_mute_does_not_trigger_an_unmute() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected a scheduled mute");
        };
        assert!(mute.claim_mute(session, false));
        mute.release_claim();
        assert_eq!(mute.on_recording_changed(false), MuteAction::Nothing);
    }
}
