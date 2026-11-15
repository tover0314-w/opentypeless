#[path = "../src/input_configuration.rs"]
mod input_configuration;
#[path = "../src/input_session.rs"]
mod input_session;

use input_configuration::InputConfiguration;
use input_session::{InputAction, InputBinding, InputMode, VoiceRole};
use std::sync::Arc;

fn binding(mode: InputMode) -> InputBinding {
    InputBinding {
        role: VoiceRole::Dictation,
        binding_id: "Fn".into(),
        mode,
        revision: 0,
    }
}

#[test]
fn blocked_installation_release_keeps_observing_until_a_trusted_empty_snapshot() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        1,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(1, 1, &"toggle".into(), "original".into());
    let reservation = runtime.reserve_installation().unwrap();
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    assert!(runtime.requires_release_observation());
    runtime.observe_physical_keys(Some(vec![63]));
    assert!(runtime.release_installation(reservation));
    assert!(runtime.release("Fn", 20).is_empty());
    assert!(runtime.requires_release_observation());
    assert!(!runtime.permits_reconfiguration());
    assert!(!runtime.needs_installation(&"hold".into()));
    runtime.observe_physical_keys(None);
    assert!(runtime.requires_release_observation());
    assert!(!runtime.needs_installation(&"hold".into()));
    runtime.observe_physical_keys(Some(vec![49]));
    assert!(runtime.requires_release_observation());
    assert!(!runtime.needs_installation(&"hold".into()));
    runtime.observe_physical_keys(Some(Vec::new()));
    assert!(!runtime.requires_release_observation());
    assert!(runtime.needs_installation(&"hold".into()));
}

#[test]
fn startup_revision_zero_activates_installed_fn_after_reservation_is_released() {
    let mut runtime = InputConfiguration::<String, String>::default();
    let reservation = runtime.reserve_installation().unwrap();
    assert!(runtime.stage_installation(
        1,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    ));
    assert!(runtime.publish_saved_revision(1, 0, &"toggle".into(), "startup".into()));
    assert_eq!(runtime.active_revision(), Some(1));
    assert!(runtime.release_installation(reservation));
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start {
        session_id,
        binding,
    }] = actions.as_slice()
    else {
        panic!("startup Fn did not reserve a session: {actions:?}");
    };
    assert_eq!(
        runtime.owned_snapshot(*session_id, binding.revision),
        Some(&"startup".into())
    );
}

#[test]
fn configured_combo_preserves_translation_identity_and_guide_ownership() {
    let mut runtime = InputConfiguration::<String, String>::default();
    let mode = InputMode::from_config("auto").unwrap();
    let translation = InputBinding {
        role: VoiceRole::Translate,
        binding_id: "Fn+LeftShift".into(),
        mode,
        revision: 0,
    };
    runtime.stage_installation(
        7,
        "auto".into(),
        vec![binding(mode), translation],
        vec!["Fn".into(), "Fn+LeftShift".into()],
    );
    runtime.publish_saved(7, &"auto".into(), "original".into());
    let revision = runtime.active_revision().unwrap();
    assert!(runtime.confirm_installation(revision));
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    assert!(runtime.owns_press_binding("Fn", VoiceRole::Dictation));
    let press_id = runtime.press_id().unwrap();
    assert_eq!(runtime.guide_delay(press_id, 20), Some(590));
    assert_eq!(
        runtime.press_combo_committed("Fn", "Fn+LeftShift", VoiceRole::Translate, 250),
        vec![]
    );
    assert!(!runtime.owns_press_binding("Fn", VoiceRole::Dictation));
    assert!(runtime.owns_press_binding("Fn+LeftShift", VoiceRole::Translate));
    let actions = runtime.release("Fn+LeftShift", 300);
    let [InputAction::Start { session_id, .. }] = actions.as_slice() else {
        panic!("expected translation start");
    };
    let session_id = *session_id;
    assert_eq!(
        runtime.phase(),
        Some(input_session::SessionPhase::Preparing)
    );
    assert_eq!(
        runtime.session_binding().unwrap().role,
        VoiceRole::Translate
    );
    assert!(runtime.owns_session_binding("Fn+LeftShift", VoiceRole::Translate));
    assert!(std::ops::DerefMut::deref_mut(&mut runtime).cancel(session_id));
    assert_eq!(runtime.session_binding(), None);
}

#[test]
fn configuration_pause_requires_release_and_preserves_suppressed_press_identity() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "auto".into(),
        vec![binding(InputMode::Auto)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"auto".into(), "original".into());
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    runtime.suspend();
    assert!(runtime.owns_suppressed_release("Fn", VoiceRole::Dictation));
    runtime.observe_physical_keys(None);
    assert!(runtime.resume());
    assert!(runtime.requires_release_observation());
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 15)
        .is_empty());
    runtime.observe_physical_keys(Some(vec![]));
    assert!(runtime.resume());
    assert!(!runtime.owns_suppressed_release("Fn", VoiceRole::Dictation));
    let reservation = runtime.reserve_installation().unwrap();
    runtime.press_committed("Fn", VoiceRole::Dictation, 20);
    assert!(runtime.owns_installation_blocked_release("Fn"));
    runtime.release("Fn", 30);
    assert!(!runtime.owns_installation_blocked_release("Fn"));
    runtime.release_installation(reservation);
}

#[test]
fn ask_keeps_original_settings_and_mode_until_its_owned_session_finishes() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "auto".into(),
        vec![InputBinding {
            role: VoiceRole::Ask,
            binding_id: "Fn+Space".into(),
            mode: InputMode::Auto,
            revision: 0,
        }],
        vec!["Fn+Space".into()],
    );
    assert!(runtime.publish_saved_revision(7, 1, &"auto".into(), "original-model".into()));
    let actions = runtime.press_committed("Fn+Space", VoiceRole::Ask, 10);
    let [InputAction::Start {
        session_id,
        binding,
    }] = actions.as_slice()
    else {
        panic!("expected owned Ask start");
    };
    let session_id = *session_id;
    let revision = binding.revision;
    runtime.ready(session_id);
    runtime.release("Fn+Space", 100);
    assert!(runtime.refresh_saved_snapshot(2, &"auto".into(), "replacement-model".into()));
    assert_eq!(
        runtime.owned_snapshot(session_id, revision),
        Some(&"original-model".into())
    );
    assert_eq!(
        runtime.press_committed("Fn+Space", VoiceRole::Ask, 200),
        vec![InputAction::Stop { session_id }]
    );
    runtime.release("Fn+Space", 250);
    runtime.finish(session_id);
    let actions = runtime.press_committed("Fn+Space", VoiceRole::Ask, 300);
    let [InputAction::Start {
        session_id,
        binding,
    }] = actions.as_slice()
    else {
        panic!("expected next Ask start");
    };
    assert_eq!(
        runtime.owned_snapshot(*session_id, binding.revision),
        Some(&"replacement-model".into())
    );
}

#[test]
fn installation_reservation_blocks_new_press_until_its_owner_releases_it() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"hold".into(), "original".into());
    let reservation = runtime.reserve_installation().unwrap();
    assert!(runtime.reserve_installation().is_none());
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    assert!(!runtime.release_installation(reservation + 1));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 20)
        .is_empty());
    assert!(runtime.release_installation(reservation));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 30)
        .is_empty());
    assert!(runtime.release("Fn", 35).is_empty());
    assert!(!runtime
        .press_committed("Fn", VoiceRole::Dictation, 40)
        .is_empty());
}

#[test]
fn installation_blocked_press_recovers_only_after_confirmed_physical_release() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "auto".into(),
        vec![binding(InputMode::Auto)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"auto".into(), "original".into());
    let reservation = runtime.reserve_installation().unwrap();
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    runtime.release_installation(reservation);
    assert!(runtime.requires_release_observation());
    runtime.observe_physical_keys(None);
    assert!(runtime.requires_release_observation());
    runtime.observe_physical_keys(Some(vec![63]));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 20)
        .is_empty());
    runtime.observe_physical_keys(Some(vec![]));
    assert!(!runtime.requires_release_observation());
    runtime.press_committed("Fn", VoiceRole::Dictation, 30);
    assert!(!runtime.release("Fn", 100).is_empty());
}

#[test]
fn stale_installation_release_cannot_unlock_a_replacement_reservation() {
    let mut runtime = InputConfiguration::<String, String>::default();
    let original = runtime.reserve_installation().unwrap();
    assert!(runtime.release_installation(original));
    let replacement = runtime.reserve_installation().unwrap();
    assert!(!runtime.release_installation(original));
    assert!(runtime.reserve_installation().is_none());
    assert!(runtime.release_installation(replacement));
}

#[test]
fn concurrent_press_and_installation_have_only_one_owner() {
    for _ in 0..32 {
        let mut runtime = InputConfiguration::<String, String>::default();
        runtime.stage_installation(
            7,
            "hold".into(),
            vec![binding(InputMode::Hold)],
            vec!["Fn".into()],
        );
        runtime.publish_saved(7, &"hold".into(), "original".into());
        let runtime = Arc::new(std::sync::Mutex::new(runtime));
        let barrier = Arc::new(std::sync::Barrier::new(2));
        let registration_runtime = runtime.clone();
        let registration_barrier = barrier.clone();
        let registration = std::thread::spawn(move || {
            registration_barrier.wait();
            registration_runtime.lock().unwrap().reserve_installation()
        });
        barrier.wait();
        let actions = runtime
            .lock()
            .unwrap()
            .press_committed("Fn", VoiceRole::Dictation, 10);
        let reservation = registration.join().unwrap();
        assert_ne!(reservation.is_some(), !actions.is_empty());
        let mut runtime = runtime.lock().unwrap();
        if let Some(reservation) = reservation {
            assert_eq!(runtime.session_id(), None);
            assert!(runtime.release_installation(reservation));
            runtime.release("Fn", 100);
        } else {
            assert!(runtime.session_id().is_some());
        }
    }
}

#[test]
fn active_press_wins_over_an_installation_reservation() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "auto".into(),
        vec![binding(InputMode::Auto)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"auto".into(), "original".into());
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    assert!(runtime.reserve_installation().is_none());
    assert!(!runtime.release("Fn", 100).is_empty());
    assert!(runtime.reserve_installation().is_none());
}

#[test]
fn saved_revision_receipt_is_pending_until_the_original_session_finishes() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"hold".into(), "original".into());
    assert!(runtime.saved_revision_is_active(1, &"hold".into()));
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start { session_id, .. }] = actions.as_slice() else {
        panic!("expected session");
    };
    let session_id = *session_id;
    runtime.refresh_saved_snapshot(2, &"hold".into(), "replacement".into());
    assert!(!runtime.saved_revision_is_active(2, &"hold".into()));
    runtime.ready(session_id);
    runtime.release("Fn", 100);
    runtime.finish(session_id);
    assert!(runtime.saved_revision_is_active(2, &"hold".into()));
    assert!(!runtime.saved_revision_is_active(1, &"hold".into()));
    runtime.monitor_lost(runtime.active_revision().unwrap());
    assert!(!runtime.saved_revision_is_active(2, &"hold".into()));
}

#[test]
fn installation_alone_cannot_publish_an_unsaved_configuration() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    assert_eq!(runtime.active_revision(), None);
}

#[test]
fn mismatched_saved_config_cannot_publish_candidate_bindings() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    assert!(!runtime.publish_saved(7, &"toggle".into(), "saved toggle".into()));
    assert_eq!(runtime.active_revision(), None);
}

#[test]
fn active_hold_keeps_its_config_while_latest_saved_version_waits() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    assert!(runtime.publish_saved(7, &"hold".into(), "original".into()));
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start {
        session_id,
        binding,
    }] = actions.as_slice()
    else {
        panic!("expected hold start");
    };
    let session_id = *session_id;
    let revision = binding.revision;
    runtime.ready(session_id);
    runtime.stage_installation(
        8,
        "toggle".into(),
        vec![binding_for_toggle()],
        vec!["Fn".into()],
    );
    assert!(runtime.publish_saved(8, &"toggle".into(), "replacement".into()));
    assert_eq!(
        runtime.snapshot(revision).map(String::as_str),
        Some("original")
    );
    assert_eq!(
        runtime.release("Fn", 20),
        vec![InputAction::Stop { session_id }]
    );
    runtime.finish(session_id);
    assert_eq!(runtime.snapshot(revision), None);
    let next = runtime.press_committed("Fn", VoiceRole::Dictation, 30);
    let [InputAction::Start { binding, .. }] = next.as_slice() else {
        panic!("expected replacement start");
    };
    assert_eq!(binding.mode, InputMode::Toggle);
    assert_eq!(
        runtime.snapshot(binding.revision).map(String::as_str),
        Some("replacement")
    );
}

fn binding_for_toggle() -> InputBinding {
    binding(InputMode::Toggle)
}

#[test]
fn stale_installation_result_cannot_replace_a_newer_saved_configuration() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        8,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    assert!(runtime.publish_saved(8, &"toggle".into(), "new".into()));
    assert!(!runtime.stage_installation(
        7,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()]
    ));
    assert!(!runtime.publish_saved(7, &"hold".into(), "old".into()));
}

#[test]
fn changed_binding_installation_waits_for_active_press_and_session() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        1,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(1, &"hold".into(), "original".into());
    assert!(runtime.needs_installation(&"toggle".into()));
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start { session_id, .. }] = actions.as_slice() else {
        panic!("expected start");
    };
    assert!(!runtime.needs_installation(&"toggle".into()));
    runtime.ready(*session_id);
    runtime.release("Fn", 20);
    assert!(!runtime.needs_installation(&"toggle".into()));
    runtime.finish(*session_id);
    assert!(runtime.needs_installation(&"toggle".into()));
}

#[test]
fn unknown_installation_binding_cannot_replace_a_valid_candidate() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        1,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    assert!(!runtime.stage_installation(
        2,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["missing".into()],
    ));
    assert!(runtime.publish_saved(1, &"toggle".into(), "original".into()));
    assert_eq!(runtime.pending_revision(), None);
    assert!(matches!(
        runtime
            .press_committed("Fn", VoiceRole::Dictation, 10)
            .as_slice(),
        [InputAction::Start { .. }]
    ));
}

#[test]
fn unknown_physical_release_blocks_reinstallation_until_confirmed() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.observe_physical_keys(None);
    assert!(!runtime.needs_installation(&"hold".into()));
    runtime.observe_physical_keys(Some(vec![63]));
    assert!(!runtime.needs_installation(&"hold".into()));
    runtime.observe_physical_keys(Some(Vec::new()));
    assert!(runtime.needs_installation(&"hold".into()));
}

#[test]
fn shutdown_drops_snapshots_and_rejects_late_installations() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        1,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(1, &"toggle".into(), "original".into());
    let revision = runtime.active_revision().unwrap();
    runtime.shutdown();
    assert_eq!(runtime.snapshot(revision), None);
    assert!(!runtime.needs_installation(&"hold".into()));
    assert!(!runtime.stage_installation(
        2,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    ));
    assert!(!runtime.publish_saved(1, &"toggle".into(), "late".into()));
}

#[test]
fn cancelled_start_cannot_read_configuration_owned_by_its_replacement() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        1,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(1, &"toggle".into(), "original".into());
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start {
        session_id,
        binding,
    }] = actions.as_slice()
    else {
        panic!("expected start");
    };
    assert_eq!(
        runtime
            .owned_snapshot(*session_id, binding.revision)
            .map(String::as_str),
        Some("original")
    );
    runtime.cancel(*session_id);
    runtime.release("Fn", 20);
    runtime.press_committed("Fn", VoiceRole::Dictation, 30);
    assert_eq!(runtime.owned_snapshot(*session_id, binding.revision), None);
}

#[test]
fn active_session_cannot_read_a_different_configuration_revision() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        1,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(1, &"toggle".into(), "original".into());
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start {
        session_id,
        binding,
    }] = actions.as_slice()
    else {
        panic!("expected start");
    };
    runtime.publish_saved(1, &"toggle".into(), "replacement".into());
    assert_eq!(
        runtime.owned_snapshot(*session_id, binding.revision + 1),
        None
    );
}

#[test]
fn installed_but_unpublished_configuration_remains_pending_publication() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    assert_eq!(runtime.pending_publication(&"toggle".into()), Some(7));
    assert_eq!(runtime.pending_publication(&"hold".into()), None);
    assert!(!runtime.publish_saved(7, &"hold".into(), "wrong".into()));
    assert_eq!(runtime.pending_publication(&"toggle".into()), Some(7));
    assert!(runtime.publish_saved(7, &"toggle".into(), "saved".into()));
    assert_eq!(runtime.pending_publication(&"toggle".into()), None);
}

#[test]
fn saved_non_binding_settings_apply_next_session_without_reinstalling() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"toggle".into(), "old microphone".into());
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start {
        session_id,
        binding,
    }] = actions.as_slice()
    else {
        panic!("expected start");
    };
    assert!(runtime.refresh_saved_snapshot(1, &"toggle".into(), "new microphone".into()));
    assert_eq!(
        runtime
            .owned_snapshot(*session_id, binding.revision)
            .map(String::as_str),
        Some("old microphone")
    );
    runtime.release("Fn", 20);
    runtime.cancel(*session_id);
    let next = runtime.press_committed("Fn", VoiceRole::Dictation, 30);
    let [InputAction::Start {
        session_id,
        binding,
    }] = next.as_slice()
    else {
        panic!("expected next start");
    };
    assert_eq!(
        runtime
            .owned_snapshot(*session_id, binding.revision)
            .map(String::as_str),
        Some("new microphone")
    );
    assert!(!runtime.needs_installation(&"toggle".into()));
}

#[test]
fn saved_snapshot_does_not_activate_an_uncommitted_installation_candidate() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"toggle".into(), "original".into());
    runtime.stage_installation(
        8,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    assert!(!runtime.refresh_saved_snapshot(1, &"hold".into(), "candidate".into()));
    assert!(runtime.refresh_saved_snapshot(2, &"toggle".into(), "saved original".into()));
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start { binding, .. }] = actions.as_slice() else {
        panic!("expected committed start");
    };
    assert_eq!(binding.mode, InputMode::Toggle);
}

#[test]
fn late_saved_snapshot_cannot_overwrite_a_newer_commit() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"toggle".into(), "original".into());
    assert!(runtime.refresh_saved_snapshot(2, &"toggle".into(), "new".into()));
    assert!(!runtime.refresh_saved_snapshot(1, &"toggle".into(), "late".into()));
    assert_eq!(
        runtime
            .snapshot(runtime.active_revision().unwrap())
            .map(String::as_str),
        Some("new")
    );
}

#[test]
fn ordinary_save_cannot_revalidate_a_lost_monitor() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"toggle".into(), "original".into());
    let revision = runtime.active_revision().unwrap();
    assert!(runtime.monitor_lost(revision));
    runtime.refresh_saved_snapshot(1, &"toggle".into(), "new microphone".into());
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    assert!(runtime.needs_installation(&"toggle".into()));
}

#[test]
fn fresh_installation_after_monitor_loss_uses_latest_saved_configuration() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"toggle".into(), "original".into());
    let previous_revision = runtime.active_revision().unwrap();
    runtime.monitor_lost(previous_revision);
    assert!(!runtime.refresh_saved_snapshot(1, &"toggle".into(), "new microphone".into()));
    runtime.stage_installation(
        8,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    assert!(runtime.publish_saved(8, &"toggle".into(), "new microphone".into()));
    assert!(!runtime.monitor_lost(previous_revision));
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start {
        session_id,
        binding,
    }] = actions.as_slice()
    else {
        panic!("expected recovered start");
    };
    assert_eq!(
        runtime
            .owned_snapshot(*session_id, binding.revision)
            .map(String::as_str),
        Some("new microphone")
    );
}

#[test]
fn monitor_loss_cannot_activate_a_previously_confirmed_pending_snapshot() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved(7, &"toggle".into(), "original".into());
    let revision = runtime.active_revision().unwrap();
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start { session_id, .. }] = actions.as_slice() else {
        panic!("expected start");
    };
    runtime.refresh_saved_snapshot(1, &"toggle".into(), "pending".into());
    runtime.monitor_lost(revision);
    runtime.release("Fn", 20);
    runtime.cancel(*session_id);
    runtime.observe_physical_keys(Some(vec![]));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 30)
        .is_empty());
    assert!(runtime.needs_installation(&"toggle".into()));
}

#[test]
fn delayed_installation_publication_cannot_replace_a_newer_saved_snapshot() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    assert!(runtime.publish_saved_revision(7, 1, &"toggle".into(), "original".into()));
    assert!(runtime.refresh_saved_snapshot(3, &"toggle".into(), "new microphone".into()));
    assert!(!runtime.publish_saved_revision(7, 2, &"toggle".into(), "stale microphone".into()));
    assert_eq!(
        runtime
            .snapshot(runtime.active_revision().unwrap())
            .map(String::as_str),
        Some("new microphone")
    );
}

#[test]
fn saved_binding_change_blocks_older_publication_before_new_installation() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"toggle".into(), "original".into());
    assert!(!runtime.refresh_saved_snapshot(4, &"hold".into(), "saved hold".into()));
    assert!(!runtime.publish_saved_revision(7, 3, &"toggle".into(), "late toggle".into()));
    runtime.stage_installation(
        8,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    assert!(runtime.publish_saved_revision(8, 4, &"hold".into(), "saved hold".into()));
}

#[test]
fn same_saved_revision_cannot_publish_a_conflicting_binding_fingerprint() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"toggle".into(), "original".into());
    assert!(!runtime.refresh_saved_snapshot(4, &"hold".into(), "saved hold".into()));
    assert!(!runtime.publish_saved_revision(7, 4, &"toggle".into(), "wrong binding".into()));
    assert_eq!(
        runtime
            .snapshot(runtime.active_revision().unwrap())
            .map(String::as_str),
        Some("original")
    );
}

#[test]
fn cancelled_session_releases_its_retired_configuration_resource() {
    let mut runtime = InputConfiguration::<String, Arc<()>>::default();
    let original = Arc::new(());
    let retired = Arc::downgrade(&original);
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"toggle".into(), original);
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start { session_id, .. }] = actions.as_slice() else {
        panic!("expected start");
    };
    runtime.refresh_saved_snapshot(2, &"toggle".into(), Arc::new(()));
    assert!(retired.upgrade().is_some());
    runtime.cancel(*session_id);
    assert!(retired.upgrade().is_some());
    runtime.release("Fn", 20);
    assert!(retired.upgrade().is_none());
}

#[test]
fn superseded_pending_configuration_releases_only_the_pending_resource() {
    let mut runtime = InputConfiguration::<String, Arc<()>>::default();
    let original = Arc::new(());
    let active = Arc::downgrade(&original);
    runtime.stage_installation(
        7,
        "toggle".into(),
        vec![binding(InputMode::Toggle)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"toggle".into(), original);
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let pending = Arc::new(());
    let superseded = Arc::downgrade(&pending);
    runtime.refresh_saved_snapshot(2, &"toggle".into(), pending);
    runtime.refresh_saved_snapshot(3, &"toggle".into(), Arc::new(()));
    assert!(superseded.upgrade().is_none());
    assert!(active.upgrade().is_some());
    runtime.shutdown();
    assert!(active.upgrade().is_none());
}

#[test]
fn lost_active_monitor_session_can_reinstall_after_owned_finish_and_release() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"hold".into(), "original".into());
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let [InputAction::Start { session_id, .. }] = actions.as_slice() else {
        panic!("expected owned start");
    };
    let session_id = *session_id;
    runtime.ready(session_id);
    runtime.monitor_lost(runtime.active_revision().unwrap());
    runtime.finish(session_id);
    runtime.observe_physical_keys(None);
    assert!(!runtime.needs_installation(&"hold".into()));
    runtime.observe_physical_keys(Some(vec![]));
    assert!(runtime.needs_installation(&"hold".into()));
    runtime.stage_installation(
        8,
        "hold".into(),
        vec![binding(InputMode::Hold)],
        vec!["Fn".into()],
    );
    assert!(runtime.publish_saved_revision(8, 1, &"hold".into(), "original".into()));
    assert!(!runtime
        .press_committed("Fn", VoiceRole::Dictation, 30)
        .is_empty());
}

#[test]
fn lost_monitor_idle_press_clears_only_after_confirmed_release_without_starting() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "auto".into(),
        vec![binding(InputMode::Auto)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"auto".into(), "original".into());
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    let press_id = runtime.press_id().unwrap();
    runtime.monitor_lost(runtime.active_revision().unwrap());
    assert!(runtime.requires_release_observation());
    runtime.observe_physical_keys(None);
    assert_eq!(runtime.press_id(), Some(press_id));
    runtime.observe_physical_keys(Some(vec![63]));
    assert_eq!(runtime.press_id(), Some(press_id));
    runtime.observe_physical_keys(Some(Vec::new()));
    assert_eq!(runtime.press_id(), None);
    assert_eq!(runtime.session_id(), None);
    assert!(runtime.release("Fn", 40).is_empty());
    assert!(runtime.needs_installation(&"auto".into()));
}

#[test]
fn confirmed_release_dismisses_only_the_lost_monitors_owned_guide() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "auto".into(),
        vec![binding(InputMode::Auto)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"auto".into(), "original".into());
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let press_id = runtime.press_id().unwrap();
    assert_eq!(
        runtime.long_press_due(press_id, 610),
        vec![InputAction::ShowGuide { press_id }]
    );
    runtime.monitor_lost(runtime.active_revision().unwrap());
    assert!(runtime.observe_physical_keys(None).is_empty());
    assert!(runtime.observe_physical_keys(Some(vec![63])).is_empty());
    assert_eq!(
        runtime.observe_physical_keys(Some(Vec::new())),
        vec![InputAction::HideGuide { press_id }]
    );
    assert!(runtime.observe_physical_keys(Some(Vec::new())).is_empty());
    assert_eq!(runtime.session_id(), None);
}

#[test]
fn physical_release_before_recovery_snapshot_dismisses_the_owned_guide_once() {
    let mut runtime = InputConfiguration::<String, String>::default();
    runtime.stage_installation(
        7,
        "auto".into(),
        vec![binding(InputMode::Auto)],
        vec!["Fn".into()],
    );
    runtime.publish_saved_revision(7, 1, &"auto".into(), "original".into());
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let press_id = runtime.press_id().unwrap();
    runtime.long_press_due(press_id, 610);
    runtime.monitor_lost(runtime.active_revision().unwrap());
    assert_eq!(
        runtime.release("Fn", 700),
        vec![InputAction::HideGuide { press_id }]
    );
    assert!(runtime.observe_physical_keys(Some(Vec::new())).is_empty());
    assert_eq!(runtime.session_id(), None);
}
