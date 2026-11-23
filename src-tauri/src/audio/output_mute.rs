//! Mutes system output while the user dictates (Typeless `audio:mute` / `audio:unmute`), so
//! music or video playback doesn't bleed into the microphone. Only state we changed is
//! restored, and a marker file lets the next launch undo a mute left behind by a crash.

use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::Mutex;
use std::time::Duration;
use tauri::Manager;

/// Typeless waits ~500 ms after the start cue so the cue itself isn't silenced.
const MUTE_DELAY: Duration = Duration::from_millis(500);
const MARKER_FILE: &str = "background-mute.marker";

#[derive(Default)]
pub struct BackgroundMute {
    muted_by_us: AtomicBool,
    session: AtomicU64,
    recording: AtomicBool,
    command_lock: Mutex<()>,
}

#[derive(Debug, PartialEq, Eq)]
enum MuteAction {
    MuteAfterDelay(u64),
    Unmute,
    Nothing,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum MuteOutcome {
    Applied,
    NotAttempted,
    Uncertain,
}

impl BackgroundMute {
    fn on_recording_changed(&self, recording: bool) -> MuteAction {
        let _guard = self
            .command_lock
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        self.recording.store(recording, Ordering::SeqCst);
        let session = self.session.fetch_add(1, Ordering::SeqCst) + 1;
        if recording {
            return MuteAction::MuteAfterDelay(session);
        }
        if self.muted_by_us.load(Ordering::SeqCst) {
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

    fn restore_with(&self, restore: impl FnOnce() -> bool) -> bool {
        let _guard = self
            .command_lock
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if self.recording.load(Ordering::SeqCst) || !self.muted_by_us.load(Ordering::SeqCst) {
            return false;
        }
        if !restore() {
            return false;
        }
        self.release_claim();
        true
    }

    fn mute_with(
        &self,
        session: u64,
        already_muted: impl FnOnce() -> bool,
        mute: impl FnOnce() -> MuteOutcome,
    ) {
        let _guard = self
            .command_lock
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if !self.recording.load(Ordering::SeqCst)
            || self.session.load(Ordering::SeqCst) != session
            || !self.claim_mute(session, already_muted())
        {
            return;
        }
        if mute() == MuteOutcome::NotAttempted {
            self.release_claim();
        }
    }
}

pub fn is_supported() -> bool {
    cfg!(target_os = "macos")
}

#[cfg(target_os = "macos")]
fn run_osascript(script: &str) -> Option<String> {
    let mut child = std::process::Command::new("/usr/bin/osascript")
        .args(["-e", script])
        .stdout(std::process::Stdio::piped())
        .stderr(std::process::Stdio::null())
        .spawn()
        .ok()?;
    let deadline = std::time::Instant::now() + Duration::from_secs(2);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => {
                if !status.success() {
                    return None;
                }
                let output = child.wait_with_output().ok()?;
                return Some(String::from_utf8_lossy(&output.stdout).trim().to_string());
            }
            Ok(None) if std::time::Instant::now() < deadline => {
                std::thread::sleep(Duration::from_millis(10));
            }
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return None;
            }
        }
    }
}

#[cfg(target_os = "macos")]
fn system_output_muted() -> Option<bool> {
    run_osascript("output muted of (get volume settings)")
        .and_then(|value| parse_system_mute_query(&value))
}

#[cfg(any(target_os = "macos", test))]
fn parse_system_mute_query(value: &str) -> Option<bool> {
    match value.trim() {
        "true" => Some(true),
        "false" => Some(false),
        _ => None,
    }
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

fn unmute_now(app: &tauri::AppHandle) -> bool {
    restore_current_output(
        system_output_muted,
        || set_system_output_muted(false),
        || marker_path(app).is_some_and(|path| remove_restore_marker(&path)),
    )
}

fn restore_current_output(
    mut read_state: impl FnMut() -> Option<bool>,
    restore: impl FnOnce() -> bool,
    clear_marker: impl FnOnce() -> bool,
) -> bool {
    match read_state() {
        Some(false) => clear_marker(),
        Some(true) => confirmed_unmute(restore, read_state, clear_marker),
        None => false,
    }
}

fn confirmed_unmute(
    restore: impl FnOnce() -> bool,
    read_state: impl FnOnce() -> Option<bool>,
    clear_marker: impl FnOnce() -> bool,
) -> bool {
    if !restore() || read_state() != Some(false) {
        return false;
    }
    clear_marker()
}

fn remove_restore_marker(path: &std::path::Path) -> bool {
    match std::fs::remove_file(path) {
        Ok(()) => true,
        Err(error) => error.kind() == std::io::ErrorKind::NotFound,
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
            tauri::async_runtime::spawn_blocking(move || {
                app.state::<BackgroundMute>()
                    .restore_with(|| unmute_now(&app));
            });
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
                    state.mute_with(
                        session,
                        || system_output_muted().unwrap_or(true),
                        || {
                            let Some(path) = marker_path(&app) else {
                                return MuteOutcome::NotAttempted;
                            };
                            if std::fs::write(&path, b"1").is_err() {
                                return MuteOutcome::NotAttempted;
                            }
                            if set_system_output_muted(true) {
                                MuteOutcome::Applied
                            } else {
                                MuteOutcome::Uncertain
                            }
                        },
                    );
                })
                .await;
            });
        }
    }
}

/// Restores audio on quit if a recording was still muting it.
pub fn restore_on_exit(app: &tauri::AppHandle) {
    if let Some(state) = app.try_state::<BackgroundMute>() {
        state.on_recording_changed(false);
        state.restore_with(|| unmute_now(app));
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn restore_requires_confirmed_unmuted_state_before_clearing_marker() {
        let cleared = std::cell::Cell::new(false);
        assert!(!confirmed_unmute(
            || true,
            || Some(true),
            || {
                cleared.set(true);
                true
            },
        ));
        assert!(!cleared.get());
        assert!(!confirmed_unmute(
            || true,
            || None,
            || {
                cleared.set(true);
                true
            }
        ));
        assert!(!cleared.get());
        assert!(confirmed_unmute(
            || true,
            || Some(false),
            || {
                cleared.set(true);
                true
            },
        ));
        assert!(cleared.get());
    }

    #[test]
    fn failed_unmute_command_does_not_query_or_clear_marker() {
        assert!(!confirmed_unmute(
            || false,
            || panic!("failed command must not be treated as restored"),
            || panic!("marker must remain"),
        ));
    }

    #[test]
    fn marker_cleanup_failure_does_not_report_completed_restore() {
        assert!(!confirmed_unmute(|| true, || Some(false), || false));
    }

    #[test]
    fn already_unmuted_restore_only_cleans_marker() {
        assert!(restore_current_output(
            || Some(false),
            || panic!("already unmuted output must not be changed"),
            || true,
        ));
    }

    #[test]
    fn unknown_output_state_does_not_change_audio_or_clear_marker() {
        assert!(!restore_current_output(
            || None,
            || panic!("unknown output must not be changed"),
            || panic!("unknown output must retain marker"),
        ));
    }

    #[test]
    fn muted_output_is_restored_once_and_verified_before_cleanup() {
        let mut states = [Some(true), Some(false)].into_iter();
        let restored = std::cell::Cell::new(0);
        let cleared = std::cell::Cell::new(false);
        assert!(restore_current_output(
            || states.next().unwrap(),
            || {
                restored.set(restored.get() + 1);
                true
            },
            || {
                assert_eq!(restored.get(), 1);
                cleared.set(true);
                true
            },
        ));
        assert_eq!(restored.get(), 1);
        assert!(cleared.get());
        assert!(states.next().is_none());
    }

    #[test]
    fn unconfirmed_restore_readback_retains_marker() {
        for readback in [Some(true), None] {
            let mut states = [Some(true), readback].into_iter();
            assert!(!restore_current_output(
                || states.next().unwrap(),
                || true,
                || panic!("unconfirmed restore must retain marker"),
            ));
            assert!(states.next().is_none());
        }
    }

    #[test]
    fn marker_cleanup_accepts_absence_but_rejects_directory() {
        let directory =
            std::env::temp_dir().join(format!("opentypeless-mute-marker-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&directory).unwrap();
        let marker = directory.join("marker");
        assert!(remove_restore_marker(&marker));
        std::fs::write(&marker, b"1").unwrap();
        assert!(remove_restore_marker(&marker));
        assert!(!remove_restore_marker(&directory));
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn system_mute_query_rejects_unknown_output() {
        assert_eq!(parse_system_mute_query("true"), Some(true));
        assert_eq!(parse_system_mute_query(" false\n"), Some(false));
        for output in ["", "unknown", "FALSE", "true\nfalse", "permission denied"] {
            assert_eq!(parse_system_mute_query(output), None);
        }
    }

    #[test]
    fn uncertain_mute_retains_ownership_until_restore_succeeds() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected mute");
        };
        mute.mute_with(session, || false, || MuteOutcome::Uncertain);
        assert!(mute.muted_by_us.load(Ordering::SeqCst));
        assert_eq!(mute.on_recording_changed(false), MuteAction::Unmute);
        assert!(!mute.restore_with(|| false));
        assert!(mute.muted_by_us.load(Ordering::SeqCst));
        assert!(mute.restore_with(|| true));
    }

    #[test]
    fn unattempted_mute_does_not_claim_restore_ownership() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected mute");
        };
        mute.mute_with(session, || false, || MuteOutcome::NotAttempted);
        assert!(!mute.muted_by_us.load(Ordering::SeqCst));
        assert_eq!(mute.on_recording_changed(false), MuteAction::Nothing);
    }

    #[test]
    fn stopping_waits_for_inflight_mute_before_restoring() {
        let mute = std::sync::Arc::new(BackgroundMute::default());
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected mute");
        };
        let (entered_sender, entered_receiver) = std::sync::mpsc::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let worker_state = mute.clone();
        let worker = std::thread::spawn(move || {
            worker_state.mute_with(
                session,
                || false,
                || {
                    entered_sender.send(()).unwrap();
                    release_receiver
                        .recv_timeout(Duration::from_secs(2))
                        .unwrap();
                    MuteOutcome::Applied
                },
            );
        });
        entered_receiver
            .recv_timeout(Duration::from_secs(2))
            .unwrap();
        let (stopping_sender, stopping_receiver) = std::sync::mpsc::channel();
        let (stopped_sender, stopped_receiver) = std::sync::mpsc::channel();
        let stop_state = mute.clone();
        let stopper = std::thread::spawn(move || {
            stopping_sender.send(()).unwrap();
            stopped_sender
                .send(stop_state.on_recording_changed(false))
                .unwrap();
        });
        stopping_receiver
            .recv_timeout(Duration::from_secs(2))
            .unwrap();
        let premature = stopped_receiver.recv_timeout(Duration::from_millis(50));
        release_sender.send(()).unwrap();
        worker.join().unwrap();
        stopper.join().unwrap();
        assert!(matches!(
            premature,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout)
        ));
        assert_eq!(
            stopped_receiver
                .recv_timeout(Duration::from_secs(2))
                .unwrap(),
            MuteAction::Unmute
        );
        assert!(mute.restore_with(|| true));
    }

    #[test]
    fn expired_mute_does_not_query_or_change_system_state() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected mute");
        };
        mute.on_recording_changed(false);
        mute.mute_with(
            session,
            || panic!("expired task must not query system audio"),
            || panic!("expired task must not mute"),
        );
        assert!(!mute.muted_by_us.load(Ordering::SeqCst));
    }

    #[test]
    fn failed_restore_retains_ownership_for_retry() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected mute");
        };
        assert!(mute.claim_mute(session, false));
        assert_eq!(mute.on_recording_changed(false), MuteAction::Unmute);
        assert!(!mute.restore_with(|| false));
        assert!(mute.muted_by_us.load(Ordering::SeqCst));
        assert!(mute.restore_with(|| true));
        assert!(!mute.muted_by_us.load(Ordering::SeqCst));
    }

    #[test]
    fn stale_restore_does_not_unmute_a_new_recording() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected mute");
        };
        assert!(mute.claim_mute(session, false));
        mute.on_recording_changed(false);
        mute.on_recording_changed(true);
        assert!(!mute.restore_with(|| panic!("must not run stale unmute")));
        assert!(mute.muted_by_us.load(Ordering::SeqCst));
    }

    #[test]
    fn recording_schedules_a_mute_and_stopping_unmutes_only_what_we_muted() {
        let mute = BackgroundMute::default();
        let MuteAction::MuteAfterDelay(session) = mute.on_recording_changed(true) else {
            panic!("expected a scheduled mute");
        };
        assert!(mute.claim_mute(session, false));
        assert_eq!(mute.on_recording_changed(false), MuteAction::Unmute);
        assert!(mute.restore_with(|| true));
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
