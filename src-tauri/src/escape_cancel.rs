//! Typeless watches Escape only while a take is recording (REA: `reloadKeyboardShortcuts([["Escape"]])`
//! in `recording_active`) and treats it as cancel. We mirror that with a temporary global shortcut.

use crate::hotkey::HotkeyRegistrationPlan;
use crate::pipeline::{PipelineHandle, PipelineState};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{mpsc, Mutex};
use tauri::{Emitter, Manager};
use tauri_plugin_global_shortcut::{Code, GlobalShortcutExt, Shortcut, ShortcutState};

pub struct EscapeCancel {
    recording: AtomicBool,
    paused: AtomicBool,
    wake: Mutex<mpsc::Sender<()>>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum CancelTarget {
    Dictation,
    Ask,
}

fn escape_shortcut() -> Shortcut {
    Shortcut::new(None, Code::Escape)
}

fn is_plain_escape(shortcut: &Shortcut) -> bool {
    let escape = escape_shortcut();
    shortcut.mods == escape.mods && shortcut.key == escape.key
}

/// A user who bound plain Escape to a role keeps it; the temporary cancel key steps aside.
fn user_owns_escape(plan: &HotkeyRegistrationPlan) -> bool {
    crate::hotkey::role_for_global_shortcut(plan, &escape_shortcut()).is_some()
}

fn cancel_target(pipeline: PipelineState, ask_recording: bool) -> Option<CancelTarget> {
    if pipeline == PipelineState::Recording {
        Some(CancelTarget::Dictation)
    } else if ask_recording {
        Some(CancelTarget::Ask)
    } else {
        None
    }
}

/// The hotkey recorder pauses global shortcuts and needs Escape for itself.
fn wants_escape(recording: bool, paused: bool) -> bool {
    recording && !paused
}

fn current_plan(app: &tauri::AppHandle) -> HotkeyRegistrationPlan {
    app.try_state::<crate::HotkeyRoleCache>()
        .map(|cache| cache.0.lock().unwrap_or_else(|e| e.into_inner()).clone())
        .unwrap_or_default()
}

/// Registration blocks on the main thread and the shortcut handler runs under the plugin lock,
/// so the temporary key is (un)registered from this worker, never from those contexts.
pub fn install(app: &tauri::AppHandle) {
    let (tx, rx) = mpsc::channel::<()>();
    app.manage(EscapeCancel {
        recording: AtomicBool::new(false),
        paused: AtomicBool::new(false),
        wake: Mutex::new(tx),
    });
    let app = app.clone();
    let spawned = std::thread::Builder::new()
        .name("opentypeless-escape-cancel".to_string())
        .spawn(move || {
            while rx.recv().is_ok() {
                while rx.try_recv().is_ok() {}
                sync_registration(&app);
            }
        });
    if let Err(error) = spawned {
        tracing::warn!("Failed to start the Escape cancel worker: {error}");
    }
}

fn sync_registration(app: &tauri::AppHandle) {
    let Some(state) = app.try_state::<EscapeCancel>() else {
        return;
    };
    let want = wants_escape(
        state.recording.load(Ordering::SeqCst),
        state.paused.load(Ordering::SeqCst),
    );
    if user_owns_escape(&current_plan(app)) {
        return;
    }
    let shortcuts = app.global_shortcut();
    let escape = escape_shortcut();
    if want == shortcuts.is_registered(escape) {
        return;
    }
    let result = if want {
        shortcuts.register(escape)
    } else {
        shortcuts.unregister(escape)
    };
    if let Err(error) = result {
        tracing::warn!(want, "Failed to update the Escape cancel shortcut: {error}");
    }
}

fn wake<R: tauri::Runtime>(app: &tauri::AppHandle<R>) {
    if let Some(state) = app.try_state::<EscapeCancel>() {
        let _ = state
            .wake
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .send(());
    }
}

/// Called whenever the dictation or Ask pipeline changes state.
pub fn on_recording_changed(app: &tauri::AppHandle, recording: bool) {
    if let Some(state) = app.try_state::<EscapeCancel>() {
        state.recording.store(recording, Ordering::SeqCst);
        wake(app);
    }
}

/// Re-registering the configured hotkeys drops every global shortcut, including this one,
/// and ends any hotkey-recorder pause.
pub fn resync(app: &tauri::AppHandle) {
    set_paused(app, false);
}

pub fn set_paused<R: tauri::Runtime>(app: &tauri::AppHandle<R>, paused: bool) {
    if let Some(state) = app.try_state::<EscapeCancel>() {
        state.paused.store(paused, Ordering::SeqCst);
        wake(app);
    }
}

/// Returns true when the event was the temporary Escape key, so it never falls back to a role.
pub fn handle_shortcut(app: &tauri::AppHandle, shortcut: &Shortcut, state: ShortcutState) -> bool {
    if !is_plain_escape(shortcut) || user_owns_escape(&current_plan(app)) {
        return false;
    }
    if state != ShortcutState::Pressed {
        return true;
    }
    let pipeline = app.state::<PipelineHandle>();
    let ask = app.state::<crate::commands::ask::AskDictationState>();
    let Some(target) = cancel_target(
        pipeline.current_state(),
        ask.is_recording() || ask.is_starting(),
    ) else {
        return true;
    };
    // Shown first so the stored take's Undo can attach to this alert.
    let _ = app.emit(
        "capsule:alert",
        serde_json::json!({ "kind": "transcript_cancelled" }),
    );
    match target {
        CancelTarget::Dictation => pipeline.dismiss(),
        CancelTarget::Ask => {
            let _ = crate::commands::ask::abort_ask_dictation(app.clone(), ask);
        }
    }
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use tauri_plugin_global_shortcut::Modifiers;

    #[test]
    fn only_a_recording_take_is_cancelled_by_escape() {
        assert_eq!(
            cancel_target(PipelineState::Recording, false),
            Some(CancelTarget::Dictation)
        );
        assert_eq!(
            cancel_target(PipelineState::Idle, true),
            Some(CancelTarget::Ask)
        );
        for state in [
            PipelineState::Idle,
            PipelineState::Preparing,
            PipelineState::Transcribing,
            PipelineState::Polishing,
            PipelineState::Outputting,
        ] {
            assert_eq!(cancel_target(state, false), None, "{state:?}");
        }
    }

    #[test]
    fn the_hotkey_recorder_pause_keeps_escape_free() {
        assert!(wants_escape(true, false));
        assert!(!wants_escape(true, true));
        assert!(!wants_escape(false, false));
        assert!(!wants_escape(false, true));
    }

    #[test]
    fn modified_escape_is_not_the_cancel_key() {
        assert!(is_plain_escape(&Shortcut::new(None, Code::Escape)));
        assert!(!is_plain_escape(&Shortcut::new(
            Some(Modifiers::SHIFT),
            Code::Escape
        )));
        assert!(!is_plain_escape(&Shortcut::new(None, Code::Space)));
    }

    #[test]
    fn a_user_escape_binding_keeps_priority_over_cancel() {
        let plan = |dictation: &str| {
            crate::hotkey::hotkey_registration_plan_from_config_for_platform(
                &crate::storage::HotkeyConfig::from_legacy(dictation, "", "toggle"),
                "linux",
            )
            .unwrap()
        };
        assert!(!user_owns_escape(&plan("Alt+Space")));
        assert!(user_owns_escape(&plan("Escape")));
    }
}
