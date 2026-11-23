#[path = "../src/native_dispatch_gate.rs"]
mod native_dispatch_gate;

use native_dispatch_gate::NativeDispatchGate;

#[test]
fn default_or_already_committed_identity_cannot_authorize_installation() {
    let gate = NativeDispatchGate::default();
    assert!(!gate.finish_install(0));
    let installation = gate.begin_install();
    assert!(gate.finish_install(installation));
    assert!(!gate.finish_install(installation & !1));
    assert!(!gate.is_paused());
}

#[test]
fn pause_invalidates_install_completion_even_when_install_was_already_paused() {
    let gate = NativeDispatchGate::default();
    let installation = gate.begin_install();
    assert!(gate.is_paused());
    gate.pause();
    assert!(!gate.finish_install(installation));
    assert!(gate.is_paused());
}

#[test]
fn current_install_can_enable_actions_but_old_install_cannot() {
    let gate = NativeDispatchGate::default();
    let first = gate.begin_install();
    let second = gate.begin_install();
    assert!(!gate.finish_install(first));
    assert!(gate.finish_install(second));
    assert!(!gate.is_paused());
    assert!(!gate.finish_install(second));
}

#[test]
fn pause_after_commit_disables_actions() {
    let gate = NativeDispatchGate::default();
    let installation = gate.begin_install();
    assert!(gate.finish_install(installation));
    gate.pause();
    assert!(gate.is_paused());
}

#[test]
fn later_install_can_resume_after_pause() {
    let gate = NativeDispatchGate::default();
    gate.pause();
    let installation = gate.begin_install();
    assert!(gate.finish_install(installation));
    assert!(!gate.is_paused());
}
