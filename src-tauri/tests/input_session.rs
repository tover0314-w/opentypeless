#[path = "../src/input_session.rs"]
pub(crate) mod input_session;

use input_session::{InputAction, InputBinding, InputMode, InputSession, SessionPhase, VoiceRole};

fn binding(mode: InputMode, revision: u64) -> InputBinding {
    InputBinding {
        role: VoiceRole::Dictation,
        binding_id: "Fn".into(),
        mode,
        revision,
    }
}

#[test]
fn configuration_parser_rejects_unknown_modes_without_reinterpreting_owned_session() {
    for (value, expected) in [
        ("auto", Some(InputMode::Auto)),
        ("toggle", Some(InputMode::Toggle)),
        ("hold", Some(InputMode::Hold)),
        ("unknown", None),
    ] {
        assert_eq!(InputMode::from_config(value), expected);
    }
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Hold, 7), 10));
    assert_eq!(runtime.session_revision(session_id), Some(7));
    assert_eq!(runtime.session_revision(session_id + 1), None);
    assert_eq!(
        runtime.session_binding(),
        Some(&binding(InputMode::Hold, 7))
    );
    runtime.suspend();
    assert!(runtime.requires_release_observation());
    assert!(runtime.finish(session_id));
    assert_eq!(runtime.session_binding(), None);
}

fn start_id(actions: &[InputAction]) -> u64 {
    match actions {
        [InputAction::Start { session_id, .. }] => *session_id,
        _ => panic!("expected one session start, got {actions:?}"),
    }
}

fn ask_binding(mode: InputMode) -> InputBinding {
    InputBinding {
        role: VoiceRole::Ask,
        binding_id: "Fn+Space".into(),
        mode,
        revision: 1,
    }
}

#[test]
fn ask_starts_on_press_and_toggles_even_in_hold_mode() {
    for mode in [InputMode::Auto, InputMode::Toggle, InputMode::Hold] {
        let mut runtime = InputSession::default();
        let session_id = start_id(&runtime.press(ask_binding(mode), 10));
        assert!(runtime.ready(session_id).is_empty());
        assert!(runtime.release("Fn+Space", 100).is_empty());
        assert_eq!(
            runtime.press(ask_binding(mode), 200),
            vec![InputAction::Stop { session_id }]
        );
        assert!(runtime.release("Fn+Space", 280).is_empty());
    }
}

#[test]
fn ask_auto_long_hold_cancels_owned_start_and_shows_guide() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(ask_binding(InputMode::Auto), 10));
    let press_id = runtime.press_id().unwrap();
    assert_eq!(runtime.guide_delay(press_id, 20), Some(590));
    let actions = runtime.long_press_due(press_id, 610);
    assert_eq!(actions.len(), 2);
    assert!(actions.contains(&InputAction::Cancel { session_id }));
    assert!(actions.contains(&InputAction::ShowGuide { press_id }));
    assert!(runtime.ready(session_id).is_empty());
    assert_eq!(
        runtime.release("Fn+Space", 700),
        vec![InputAction::HideGuide { press_id }]
    );
    assert_eq!(runtime.session_id(), None);
}

#[test]
fn ask_auto_release_after_threshold_cancels_without_late_guide() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(ask_binding(InputMode::Auto), 10));
    let press_id = runtime.press_id().unwrap();
    let actions = runtime.release("Fn+Space", 700);
    assert_eq!(actions.len(), 1);
    assert_eq!(actions, vec![InputAction::Cancel { session_id }]);
    assert_eq!(runtime.session_id(), None);
    assert!(runtime.long_press_due(press_id, 710).is_empty());
}

#[test]
fn ask_stop_during_preparation_is_owned_until_ready() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(ask_binding(InputMode::Toggle), 10));
    runtime.release("Fn+Space", 100);
    assert!(runtime
        .press(ask_binding(InputMode::Toggle), 200)
        .is_empty());
    runtime.release("Fn+Space", 250);
    assert_eq!(
        runtime.ready(session_id),
        vec![InputAction::Stop { session_id }]
    );
}

#[test]
fn ask_stopping_press_cannot_become_a_long_hold_cancel() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(ask_binding(InputMode::Auto), 10));
    runtime.ready(session_id);
    runtime.release("Fn+Space", 100);
    assert_eq!(
        runtime.press(ask_binding(InputMode::Auto), 200),
        vec![InputAction::Stop { session_id }]
    );
    let press_id = runtime.press_id().unwrap();
    assert_eq!(runtime.guide_delay(press_id, 220), None);
    assert!(runtime.long_press_due(press_id, 800).is_empty());
    assert!(runtime.release("Fn+Space", 900).is_empty());
    assert_eq!(runtime.session_id(), Some(session_id));
}

#[test]
fn ask_repeat_does_not_toggle_or_arm_a_replacement_guide() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(ask_binding(InputMode::Auto), 10));
    let press_id = runtime.press_id().unwrap();
    runtime.ready(session_id);
    assert!(runtime.press(ask_binding(InputMode::Auto), 40).is_empty());
    assert_eq!(runtime.press_id(), Some(press_id));
    assert_eq!(runtime.phase(), Some(SessionPhase::Recording));
    runtime.release("Fn+Space", 100);
    assert!(runtime.long_press_due(press_id, 700).is_empty());
}

#[test]
fn ask_combo_takes_over_idle_auto_base_without_starting_dictation() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(
        1,
        vec![binding(InputMode::Auto, 1), ask_binding(InputMode::Auto)],
    );
    runtime.confirm_installation(1);
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    let session_id =
        start_id(&runtime.press_combo_committed("Fn", "Fn+Space", VoiceRole::Ask, 250));
    assert!(runtime.release("Fn", 300).is_empty());
    assert!(runtime.release("Fn+Space", 350).is_empty());
    assert!(runtime.owns_session_binding("Fn+Space", VoiceRole::Ask));
    assert_eq!(runtime.session_id(), Some(session_id));
}

#[test]
fn auto_tap_starts_once_and_next_tap_finishes_the_same_session() {
    let mut runtime = InputSession::default();
    assert!(runtime.press(binding(InputMode::Auto, 1), 10).is_empty());
    let session_id = start_id(&runtime.release("Fn", 100));
    assert!(runtime.ready(session_id).is_empty());
    assert!(runtime.press(binding(InputMode::Auto, 1), 200).is_empty());
    assert_eq!(
        runtime.release("Fn", 280),
        vec![InputAction::Stop { session_id }]
    );
    assert_eq!(runtime.phase(), Some(SessionPhase::Stopping));
}

#[test]
fn auto_long_press_shows_guide_without_starting_a_recording() {
    let mut runtime = InputSession::default();
    assert!(runtime.press(binding(InputMode::Auto, 1), 10).is_empty());
    let press_id = runtime.press_id().unwrap();
    assert!(runtime.long_press_due(press_id, 609).is_empty());
    assert_eq!(
        runtime.long_press_due(press_id, 610),
        vec![InputAction::ShowGuide { press_id }]
    );
    assert_eq!(
        runtime.release("Fn", 700),
        vec![InputAction::HideGuide { press_id }]
    );
    assert_eq!(runtime.phase(), None);
}

#[test]
fn hold_release_during_preparation_is_remembered_until_ready() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Hold, 1), 10));
    assert_eq!(runtime.phase(), Some(SessionPhase::Preparing));
    assert!(runtime.release("Fn", 100).is_empty());
    assert_eq!(
        runtime.ready(session_id),
        vec![InputAction::Stop { session_id }]
    );
    assert_eq!(runtime.phase(), Some(SessionPhase::Stopping));
    assert!(runtime.ready(session_id).is_empty());
}

#[test]
fn toggle_release_and_long_press_never_finish_or_show_a_guide() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Toggle, 1), 10));
    let press_id = runtime.press_id().unwrap();
    assert!(runtime.long_press_due(press_id, 900).is_empty());
    assert!(runtime.release("Fn", 1000).is_empty());
    assert!(runtime.ready(session_id).is_empty());
    assert_eq!(
        runtime.press(binding(InputMode::Toggle, 1), 1100),
        vec![InputAction::Stop { session_id }]
    );
}

#[test]
fn configuration_changes_cannot_reinterpret_an_active_hold_release() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Hold, 1), 10));
    assert!(runtime.ready(session_id).is_empty());
    assert!(runtime
        .press(
            InputBinding {
                binding_id: "Ctrl+D".into(),
                ..binding(InputMode::Toggle, 2)
            },
            30
        )
        .is_empty());
    assert_eq!(
        runtime.release("Fn", 100),
        vec![InputAction::Stop { session_id }]
    );
}

#[test]
fn repeats_and_stale_timers_cannot_start_or_cancel_another_session() {
    let mut runtime = InputSession::default();
    runtime.press(binding(InputMode::Auto, 1), 10);
    let stale_press = runtime.press_id().unwrap();
    assert!(runtime.press(binding(InputMode::Auto, 1), 20).is_empty());
    let first_session = start_id(&runtime.release("Fn", 100));
    assert!(runtime.cancel(first_session));
    assert!(runtime.ready(first_session).is_empty());
    let next_session = start_id(&runtime.press(binding(InputMode::Hold, 2), 200));
    assert!(runtime.long_press_due(stale_press, 900).is_empty());
    assert!(runtime.ready(first_session).is_empty());
    assert_eq!(runtime.session_id(), Some(next_session));
}

#[test]
fn rapid_toggle_stop_before_ready_belongs_to_the_reserved_session() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Toggle, 1), 10));
    runtime.release("Fn", 20);
    assert!(runtime.press(binding(InputMode::Toggle, 1), 30).is_empty());
    runtime.release("Fn", 40);
    assert_eq!(
        runtime.ready(session_id),
        vec![InputAction::Stop { session_id }]
    );
}

#[test]
fn stale_completion_does_not_clear_the_next_session() {
    let mut runtime = InputSession::default();
    let previous = start_id(&runtime.press(binding(InputMode::Toggle, 1), 10));
    runtime.release("Fn", 20);
    assert!(runtime.finish(previous));
    let next = start_id(&runtime.press(binding(InputMode::Hold, 2), 30));
    assert!(!runtime.finish(previous));
    assert_eq!(runtime.session_id(), Some(next));
}

#[test]
fn cancelled_finishing_press_cannot_start_another_auto_session_on_release() {
    let mut runtime = InputSession::default();
    runtime.press(binding(InputMode::Auto, 1), 10);
    let session_id = start_id(&runtime.release("Fn", 100));
    runtime.ready(session_id);
    runtime.press(binding(InputMode::Auto, 1), 200);
    assert!(runtime.cancel(session_id));
    assert!(runtime.release("Fn", 280).is_empty());
    assert_eq!(runtime.session_id(), None);
}

#[test]
fn translation_role_is_preserved_in_the_reserved_session() {
    let mut runtime = InputSession::default();
    let translation = InputBinding {
        role: VoiceRole::Translate,
        binding_id: "Fn+LeftShift".into(),
        ..binding(InputMode::Hold, 3)
    };
    let actions = runtime.press(translation.clone(), 10);
    let session_id = start_id(&actions);
    assert_eq!(
        actions,
        vec![InputAction::Start {
            session_id,
            binding: translation,
        }]
    );
    runtime.release("Fn+LeftShift", 20);
    assert_eq!(
        runtime.ready(session_id),
        vec![InputAction::Stop { session_id }]
    );
}

#[test]
fn ask_binding_does_not_end_a_dictation_session() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Toggle, 1), 10));
    runtime.release("Fn", 20);
    runtime.ready(session_id);
    assert!(runtime
        .press(
            InputBinding {
                role: VoiceRole::Ask,
                binding_id: "Fn+Space".into(),
                ..binding(InputMode::Toggle, 1)
            },
            30
        )
        .is_empty());
    assert_eq!(runtime.session_id(), Some(session_id));
}

#[test]
fn cancelled_session_cannot_turn_its_finishing_press_into_a_guide() {
    let mut runtime = InputSession::default();
    runtime.press(binding(InputMode::Auto, 1), 10);
    let session_id = start_id(&runtime.release("Fn", 100));
    runtime.ready(session_id);
    runtime.press(binding(InputMode::Auto, 1), 200);
    let press_id = runtime.press_id().unwrap();
    runtime.cancel(session_id);

    assert!(runtime.long_press_due(press_id, 900).is_empty());
    assert!(runtime.release("Fn", 950).is_empty());
}

#[test]
fn old_hold_release_cannot_stop_a_newly_reserved_session() {
    let mut runtime = InputSession::default();
    let previous = start_id(&runtime.press(binding(InputMode::Hold, 1), 10));
    runtime.cancel(previous);
    assert!(runtime.press(binding(InputMode::Hold, 2), 20).is_empty());
    assert!(runtime.release("Fn", 30).is_empty());
    let next = start_id(&runtime.press(binding(InputMode::Hold, 2), 40));
    assert!(runtime.ready(previous).is_empty());
    assert!(runtime.ready(next).is_empty());
    assert_eq!(
        runtime.release("Fn", 50),
        vec![InputAction::Stop { session_id: next }]
    );
}

#[test]
fn mode_cycles_use_the_new_revision_only_after_the_old_session_finishes() {
    let mut runtime = InputSession::default();
    for (revision, mode) in [
        (1, InputMode::Auto),
        (2, InputMode::Toggle),
        (3, InputMode::Hold),
        (4, InputMode::Auto),
    ] {
        let started_at = revision * 1000;
        let requested = binding(mode, revision);
        let pressed = runtime.press(requested.clone(), started_at);
        let actions = if mode == InputMode::Auto {
            assert!(pressed.is_empty());
            runtime.release("Fn", started_at + 50)
        } else {
            pressed
        };
        let session_id = start_id(&actions);
        assert_eq!(
            actions,
            vec![InputAction::Start {
                session_id,
                binding: requested,
            }]
        );
        runtime.ready(session_id);
        let stop_actions = match mode {
            InputMode::Hold => runtime.release("Fn", started_at + 100),
            InputMode::Toggle => {
                runtime.release("Fn", started_at + 100);
                runtime.press(binding(InputMode::Hold, revision + 1), started_at + 200)
            }
            InputMode::Auto => {
                runtime.press(binding(InputMode::Hold, revision + 1), started_at + 200);
                runtime.release("Fn", started_at + 250)
            }
        };
        assert_eq!(stop_actions, vec![InputAction::Stop { session_id }]);
        assert!(runtime.finish(session_id));
        assert!(runtime.release("Fn", started_at + 300).is_empty());
    }
}

#[test]
fn failure_before_ready_requires_release_before_accepting_the_next_press() {
    let mut runtime = InputSession::default();
    let failed = start_id(&runtime.press(binding(InputMode::Hold, 1), 10));
    assert!(runtime.finish(failed));
    assert!(runtime.press(binding(InputMode::Toggle, 2), 20).is_empty());
    assert!(runtime.release("Fn", 30).is_empty());
    let next = start_id(&runtime.press(binding(InputMode::Toggle, 2), 40));
    assert!(runtime.ready(failed).is_empty());
    assert_eq!(runtime.phase(), Some(SessionPhase::Preparing));
    assert!(runtime.ready(next).is_empty());
    assert_eq!(runtime.phase(), Some(SessionPhase::Recording));
}

#[test]
fn a_long_hold_cannot_become_a_tap_when_its_timer_is_delayed() {
    let mut runtime = InputSession::default();
    assert!(runtime.press(binding(InputMode::Auto, 1), 10).is_empty());
    let press_id = runtime.press_id().unwrap();
    assert!(runtime.release("Fn", 610).is_empty());
    assert!(runtime.long_press_due(press_id, 700).is_empty());
    assert_eq!(runtime.session_id(), None);
}

#[test]
fn committed_binding_changes_wait_for_session_completion_and_physical_release() {
    let mut runtime = InputSession::default();
    assert!(runtime.commit_bindings(1, vec![binding(InputMode::Hold, 1)]));
    assert!(runtime.confirm_installation(1));
    let previous = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    let next_binding = InputBinding {
        binding_id: "Ctrl+D".into(),
        ..binding(InputMode::Toggle, 2)
    };
    assert!(runtime.commit_bindings(2, vec![next_binding.clone()]));
    assert!(runtime.confirm_installation(2));
    assert_eq!(runtime.active_revision(), Some(1));
    assert_eq!(runtime.pending_revision(), Some(2));
    assert!(runtime
        .press_committed("Ctrl+D", VoiceRole::Dictation, 20)
        .is_empty());
    runtime.ready(previous);
    assert_eq!(
        runtime.release("Fn", 30),
        vec![InputAction::Stop {
            session_id: previous
        }]
    );
    assert_eq!(runtime.active_revision(), Some(1));
    assert!(runtime.finish(previous));
    assert_eq!(runtime.active_revision(), Some(2));
    let actions = runtime.press_committed("Ctrl+D", VoiceRole::Dictation, 40);
    let session_id = start_id(&actions);
    assert_eq!(
        actions,
        vec![InputAction::Start {
            session_id,
            binding: next_binding
        }]
    );
}

#[test]
fn superseded_configurations_cannot_replace_the_latest_committed_revision() {
    let mut runtime = InputSession::default();
    assert!(runtime.commit_bindings(1, vec![binding(InputMode::Hold, 1)]));
    assert!(runtime.confirm_installation(1));
    let previous = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    assert!(runtime.commit_bindings(3, vec![binding(InputMode::Auto, 3)]));
    assert!(runtime.confirm_installation(3));
    assert!(!runtime.commit_bindings(2, vec![binding(InputMode::Toggle, 2)]));
    assert_eq!(runtime.pending_revision(), Some(3));
    runtime.cancel(previous);
    assert_eq!(runtime.active_revision(), Some(1));
    runtime.release("Fn", 20);
    assert_eq!(runtime.active_revision(), Some(3));
    assert_eq!(runtime.pending_revision(), None);
}

#[test]
fn invalid_configuration_does_not_change_saved_or_active_bindings() {
    let mut runtime = InputSession::default();
    assert!(runtime.commit_bindings(1, vec![binding(InputMode::Hold, 1)]));
    assert!(runtime.confirm_installation(1));
    assert!(!runtime.commit_bindings(2, vec![binding(InputMode::Auto, 3)]));
    assert!(!runtime.commit_bindings(
        2,
        vec![binding(InputMode::Auto, 2), binding(InputMode::Auto, 2)]
    ));
    assert_eq!(runtime.active_revision(), Some(1));
    assert_eq!(runtime.pending_revision(), None);
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
}

#[test]
fn configuration_committed_during_a_guide_activates_when_the_key_is_released() {
    let mut runtime = InputSession::default();
    assert!(runtime.commit_bindings(1, vec![binding(InputMode::Auto, 1)]));
    assert!(runtime.confirm_installation(1));
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let press_id = runtime.press_id().unwrap();
    runtime.long_press_due(press_id, 610);
    assert!(runtime.commit_bindings(2, vec![binding(InputMode::Hold, 2)]));
    assert!(runtime.confirm_installation(2));
    assert_eq!(runtime.active_revision(), Some(1));
    assert_eq!(
        runtime.release("Fn", 700),
        vec![InputAction::HideGuide { press_id }]
    );
    assert_eq!(runtime.active_revision(), Some(2));
    assert_eq!(runtime.pending_revision(), None);
}

#[test]
fn configuration_committed_during_a_delayed_long_hold_activates_on_release() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Auto, 1)]);
    assert!(runtime.confirm_installation(1));
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    runtime.commit_bindings(2, vec![binding(InputMode::Toggle, 2)]);
    assert!(runtime.confirm_installation(2));
    assert!(runtime.release("Fn", 610).is_empty());
    assert_eq!(runtime.active_revision(), Some(2));
}

#[test]
fn saved_configuration_cannot_start_a_session_before_installation_is_confirmed() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);

    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    assert_eq!(runtime.session_id(), None);
}

#[test]
fn stale_installation_confirmation_cannot_activate_a_superseded_configuration() {
    let mut runtime = InputSession::default();
    assert!(runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]));
    assert!(runtime.commit_bindings(2, vec![binding(InputMode::Hold, 2)]));
    assert!(!runtime.confirm_installation(1));
    assert_eq!(runtime.active_revision(), None);
    assert_eq!(runtime.pending_revision(), Some(2));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    assert!(runtime.confirm_installation(2));
    let actions = runtime.press_committed("Fn", VoiceRole::Dictation, 20);
    let session_id = start_id(&actions);
    assert_eq!(
        actions,
        vec![InputAction::Start {
            session_id,
            binding: binding(InputMode::Hold, 2),
        }]
    );
}

#[test]
fn old_binding_remains_usable_until_the_new_installation_is_confirmed() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    runtime.commit_bindings(
        2,
        vec![InputBinding {
            binding_id: "Ctrl+D".into(),
            ..binding(InputMode::Hold, 2)
        }],
    );
    assert!(runtime
        .press_committed("Ctrl+D", VoiceRole::Dictation, 10)
        .is_empty());
    let previous = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 20));
    runtime.confirm_installation(2);
    assert_eq!(runtime.active_revision(), Some(1));
    runtime.cancel(previous);
    assert_eq!(runtime.active_revision(), Some(1));
    runtime.release("Fn", 30);
    assert_eq!(runtime.active_revision(), Some(2));
}

#[test]
fn failed_installation_keeps_new_bindings_blocked_until_explicit_success() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    assert!(runtime.installation_failed(1));
    assert_eq!(runtime.active_revision(), None);
    assert_eq!(runtime.pending_revision(), Some(1));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    assert!(runtime.confirm_installation(1));
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 20));
}

#[test]
fn completion_press_after_monitor_loss_is_retired_after_confirmed_release() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    let session_id = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    runtime.release("Fn", 20);
    runtime.ready(session_id);
    runtime.monitor_lost(1);
    assert_eq!(
        runtime.press_committed("Fn", VoiceRole::Dictation, 30),
        vec![InputAction::Stop { session_id }]
    );
    assert!(runtime.requires_release_observation());
    runtime.finish(session_id);
    runtime.observe_physical_keys(None);
    assert!(runtime.press_id().is_some());
    runtime.observe_physical_keys(Some(vec![]));
    assert_eq!(runtime.press_id(), None);
    assert!(runtime.permits_reconfiguration());
}

#[test]
fn lost_monitor_observes_release_after_an_active_session_finishes() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Hold, 1)]);
    runtime.confirm_installation(1);
    let session_id = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    runtime.ready(session_id);
    runtime.monitor_lost(1);
    assert!(runtime.requires_release_observation());
    runtime.observe_physical_keys(None);
    assert_eq!(runtime.session_id(), Some(session_id));
    runtime.finish(session_id);
    runtime.observe_physical_keys(Some(vec![63]));
    assert!(runtime.press_id().is_some());
    runtime.observe_physical_keys(Some(vec![]));
    assert_eq!(runtime.press_id(), None);
    assert!(runtime.permits_reconfiguration());
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 30)
        .is_empty());
}

#[test]
fn release_observed_before_lost_monitor_session_finishes_clears_retired_press() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    let session_id = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    runtime.monitor_lost(1);
    runtime.observe_physical_keys(Some(vec![]));
    assert_eq!(runtime.session_id(), Some(session_id));
    runtime.finish(session_id);
    assert_eq!(runtime.press_id(), None);
    assert!(runtime.permits_reconfiguration());
}

#[test]
fn monitor_loss_blocks_new_starts_but_preserves_the_active_hold_release() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Hold, 1)]);
    runtime.confirm_installation(1);
    let session_id = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    runtime.ready(session_id);
    assert!(runtime.monitor_lost(1));
    assert_eq!(
        runtime.release("Fn", 20),
        vec![InputAction::Stop { session_id }]
    );
    runtime.finish(session_id);
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 30)
        .is_empty());
    runtime.observe_physical_keys(Some(vec![]));
    assert!(runtime.confirm_installation(1));
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 40));
}

#[test]
fn stale_monitor_loss_cannot_disable_the_new_installed_revision() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    runtime.commit_bindings(2, vec![binding(InputMode::Hold, 2)]);
    runtime.confirm_installation(2);
    assert!(!runtime.monitor_lost(1));
    assert!(!runtime.installation_failed(1));
    assert_eq!(runtime.active_revision(), Some(2));
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
}

#[test]
fn editing_pause_prevents_new_sessions_and_requires_physical_release_on_resume() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Auto, 1)]);
    runtime.confirm_installation(1);
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let press_id = runtime.press_id().unwrap();
    runtime.suspend();
    assert!(runtime.long_press_due(press_id, 700).is_empty());
    assert!(runtime.resume());
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 800)
        .is_empty());
    assert!(runtime.release("Fn", 810).is_empty());
    runtime.observe_physical_keys(Some(Vec::new()));
    runtime.press_committed("Fn", VoiceRole::Dictation, 900);
    start_id(&runtime.release("Fn", 950));
}

#[test]
fn editing_pause_keeps_the_active_session_release_usable() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Hold, 1), 10));
    runtime.ready(session_id);
    runtime.suspend();
    assert_eq!(
        runtime.release("Fn", 30),
        vec![InputAction::Stop { session_id }]
    );
    runtime.finish(session_id);
    assert!(runtime.press(binding(InputMode::Hold, 1), 40).is_empty());
    assert!(runtime.resume());
    runtime.observe_physical_keys(Some(Vec::new()));
    start_id(&runtime.press(binding(InputMode::Hold, 1), 50));
}

#[test]
fn shutdown_rejects_configuration_ready_and_press_events_permanently() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Hold, 1), 10));
    runtime.shutdown();
    assert!(runtime.ready(session_id).is_empty());
    assert!(runtime.release("Fn", 30).is_empty());
    assert!(!runtime.resume());
    assert!(!runtime.commit_bindings(2, vec![binding(InputMode::Toggle, 2)]));
    assert!(!runtime.confirm_installation(2));
    assert!(runtime.press(binding(InputMode::Hold, 2), 40).is_empty());
    assert_eq!(runtime.session_id(), None);
}

#[test]
fn continued_release_observation_preserves_active_session_and_stops_after_shutdown() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Hold, 1)]);
    runtime.confirm_installation(1);
    let session_id = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    runtime.ready(session_id);
    runtime.observe_physical_keys(Some(vec![63]));
    assert!(runtime.requires_release_observation());
    runtime.commit_bindings(2, vec![binding(InputMode::Toggle, 2)]);
    runtime.confirm_installation(2);
    assert!(runtime.observe_physical_keys(None).is_empty());
    assert!(runtime.requires_release_observation());
    assert!(runtime.observe_physical_keys(Some(Vec::new())).is_empty());
    assert!(!runtime.requires_release_observation());
    assert_eq!(runtime.session_id(), Some(session_id));
    assert_eq!(runtime.phase(), Some(SessionPhase::Recording));
    assert_eq!(runtime.active_revision(), Some(1));
    assert_eq!(runtime.pending_revision(), Some(2));
    runtime.shutdown();
    assert!(!runtime.requires_release_observation());
    assert!(runtime.observe_physical_keys(Some(vec![63])).is_empty());
    assert!(runtime.observe_physical_keys(Some(Vec::new())).is_empty());
    assert_eq!(runtime.session_id(), None);
    assert_eq!(runtime.active_revision(), None);
}

#[test]
fn other_held_keys_prevent_pending_configuration_activation() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Hold, 1)]);
    runtime.confirm_installation(1);
    let session_id = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    runtime.observe_physical_keys(Some(vec![63, 49]));
    runtime.commit_bindings(2, vec![binding(InputMode::Toggle, 2)]);
    runtime.confirm_installation(2);
    runtime.ready(session_id);
    runtime.release("Fn", 20);
    runtime.finish(session_id);
    runtime.observe_physical_keys(Some(vec![49]));
    assert_eq!(runtime.active_revision(), Some(1));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 30)
        .is_empty());
    runtime.observe_physical_keys(Some(Vec::new()));
    assert_eq!(runtime.active_revision(), Some(2));
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 40));
}

#[test]
fn unavailable_physical_snapshot_is_not_a_release_confirmation() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    runtime.observe_physical_keys(None);
    runtime.commit_bindings(2, vec![binding(InputMode::Hold, 2)]);
    runtime.confirm_installation(2);
    assert_eq!(runtime.active_revision(), Some(1));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    runtime.observe_physical_keys(Some(Vec::new()));
    assert_eq!(runtime.active_revision(), Some(2));
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 20));
}

#[test]
fn active_monitor_can_recover_while_a_new_configuration_waits_for_installation() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    runtime.commit_bindings(
        2,
        vec![InputBinding {
            binding_id: "Ctrl+D".into(),
            ..binding(InputMode::Hold, 2)
        }],
    );
    assert!(runtime.monitor_lost(1));
    assert!(runtime.confirm_installation(1));
    assert_eq!(runtime.active_revision(), Some(1));
    assert_eq!(runtime.pending_revision(), Some(2));
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    assert!(runtime
        .press_committed("Ctrl+D", VoiceRole::Dictation, 20)
        .is_empty());
}

#[test]
fn delayed_delivery_waits_only_the_remaining_physical_hold_duration() {
    let mut runtime = InputSession::default();
    runtime.press(binding(InputMode::Auto, 1), 100);
    let press_id = runtime.press_id().unwrap();
    assert_eq!(runtime.guide_delay(press_id, 300), Some(400));
    assert_eq!(runtime.guide_delay(press_id, 700), Some(0));
    runtime.release("Fn", 710);
    assert_eq!(runtime.guide_delay(press_id, 800), None);
}

#[test]
fn non_auto_and_finishing_presses_do_not_arm_guide_timers() {
    let mut runtime = InputSession::default();
    runtime.press(binding(InputMode::Toggle, 1), 100);
    assert_eq!(runtime.guide_delay(runtime.press_id().unwrap(), 200), None);
    let session_id = runtime.session_id().unwrap();
    runtime.release("Fn", 210);
    runtime.cancel(session_id);
    runtime.press(binding(InputMode::Auto, 2), 300);
    let session_id = start_id(&runtime.release("Fn", 350));
    runtime.ready(session_id);
    runtime.press(binding(InputMode::Auto, 2), 400);
    assert_eq!(runtime.guide_delay(runtime.press_id().unwrap(), 450), None);
}

#[test]
fn partial_installation_allows_only_the_binding_that_actually_registered() {
    let mut runtime = InputSession::default();
    let failed = InputBinding {
        binding_id: "Ctrl+D".into(),
        ..binding(InputMode::Hold, 1)
    };
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1), failed]);
    assert!(runtime.confirm_binding_installation(1, vec!["Fn".into()]));
    assert!(runtime
        .press_committed("Ctrl+D", VoiceRole::Dictation, 10)
        .is_empty());
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 20));
}

#[test]
fn failed_replacement_does_not_activate_a_stale_successful_binding() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    runtime.commit_bindings(2, vec![binding(InputMode::Hold, 2)]);
    assert!(runtime.confirm_binding_installation(2, Vec::new()));
    assert_eq!(runtime.active_revision(), Some(2));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
}

#[test]
fn partial_replacement_keeps_old_hold_ownership_until_it_finishes() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Hold, 1)]);
    runtime.confirm_installation(1);
    let session_id = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    runtime.ready(session_id);
    runtime.commit_bindings(2, vec![binding(InputMode::Toggle, 2)]);
    runtime.confirm_binding_installation(2, Vec::new());
    assert_eq!(
        runtime.release("Fn", 30),
        vec![InputAction::Stop { session_id }]
    );
    runtime.finish(session_id);
    assert_eq!(runtime.active_revision(), Some(2));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 40)
        .is_empty());
}

#[test]
fn installation_confirmation_rejects_unknown_bindings_without_publishing() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    assert!(!runtime.confirm_binding_installation(1, vec!["unknown".into()]));
    assert_eq!(runtime.active_revision(), None);
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
}

#[test]
fn monitor_loss_blocks_new_starts_but_not_the_owned_toggle_finish() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    let session_id = start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 10));
    runtime.release("Fn", 20);
    runtime.ready(session_id);
    runtime.monitor_lost(1);
    assert!(runtime.owns_session_binding("Fn", VoiceRole::Dictation));
    assert!(!runtime.owns_session_binding("Fn", VoiceRole::Translate));
    assert!(!runtime.owns_session_binding("other", VoiceRole::Dictation));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Translate, 30)
        .is_empty());
    assert_eq!(
        runtime.press_committed("Fn", VoiceRole::Dictation, 40),
        vec![InputAction::Stop { session_id }]
    );
    runtime.release("Fn", 50);
    runtime.finish(session_id);
    assert!(!runtime.owns_session_binding("Fn", VoiceRole::Dictation));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 60)
        .is_empty());
}

#[test]
fn monitor_loss_keeps_auto_finish_attached_to_the_original_session() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Auto, 1)]);
    runtime.confirm_installation(1);
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let session_id = start_id(&runtime.release("Fn", 20));
    runtime.ready(session_id);
    runtime.monitor_lost(1);
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 30)
        .is_empty());
    assert_eq!(
        runtime.release("Fn", 40),
        vec![InputAction::Stop { session_id }]
    );
}

#[test]
fn unchanged_configuration_resume_waits_for_a_confirmed_physical_release() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    runtime.suspend();
    runtime.resume();
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 5)
        .is_empty());
    runtime.observe_physical_keys(None);
    runtime.resume();
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    runtime.observe_physical_keys(Some(vec![63]));
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 20)
        .is_empty());
    runtime.observe_physical_keys(Some(Vec::new()));
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 30));
}

#[test]
fn repress_during_pause_invalidates_an_earlier_release_confirmation() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    runtime.suspend();
    runtime.observe_physical_keys(Some(Vec::new()));
    runtime.observe_physical_keys(Some(vec![63]));
    runtime.resume();
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    runtime.observe_physical_keys(Some(Vec::new()));
    start_id(&runtime.press_committed("Fn", VoiceRole::Dictation, 20));
}

#[test]
fn pending_binding_activation_obeys_the_same_resume_release_barrier_as_start() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Toggle, 1)]);
    runtime.confirm_installation(1);
    runtime.suspend();
    runtime.commit_bindings(2, vec![binding(InputMode::Hold, 2)]);
    runtime.confirm_installation(2);
    runtime.resume();
    assert_eq!(runtime.active_revision(), Some(1));
    assert_eq!(runtime.pending_revision(), Some(2));
    runtime.observe_physical_keys(Some(Vec::new()));
    assert_eq!(runtime.active_revision(), Some(2));
    assert_eq!(runtime.pending_revision(), None);
}

#[test]
fn lost_monitor_release_cleanup_requires_the_original_press_role_and_binding() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Auto, 1)]);
    runtime.confirm_installation(1);
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    assert!(!runtime.owns_suppressed_release("Fn", VoiceRole::Dictation));
    runtime.monitor_lost(1);
    assert!(runtime.owns_press_binding("Fn", VoiceRole::Dictation));
    assert!(runtime.owns_suppressed_release("Fn", VoiceRole::Dictation));
    assert!(!runtime.owns_press_binding("Fn", VoiceRole::Translate));
    assert!(!runtime.owns_press_binding("other", VoiceRole::Dictation));
    assert!(runtime.release("Fn", 20).is_empty());
    assert!(!runtime.owns_press_binding("Fn", VoiceRole::Dictation));
    assert_eq!(runtime.session_id(), None);
}

#[test]
fn late_translation_combo_takes_over_the_pending_base_without_dictation_start() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(
        1,
        vec![
            binding(InputMode::Auto, 1),
            InputBinding {
                role: VoiceRole::Translate,
                binding_id: "Fn+LeftShift".into(),
                ..binding(InputMode::Auto, 1)
            },
        ],
    );
    runtime.confirm_installation(1);
    assert!(runtime
        .press_committed("Fn", VoiceRole::Dictation, 10)
        .is_empty());
    assert!(runtime
        .press_combo_committed("Fn", "Fn+LeftShift", VoiceRole::Translate, 250)
        .is_empty());
    assert!(runtime.release("Fn", 300).is_empty());
    let actions = runtime.release("Fn+LeftShift", 301);
    assert!(matches!(
        actions.as_slice(),
        [InputAction::Start { binding, .. }] if binding.role == VoiceRole::Translate
    ));
    assert!(runtime.release("Fn+LeftShift", 302).is_empty());
}

#[test]
fn combo_takeover_rejects_an_unrelated_parent_or_uninstalled_binding() {
    let mut runtime = InputSession::default();
    runtime.commit_bindings(1, vec![binding(InputMode::Auto, 1)]);
    runtime.confirm_installation(1);
    runtime.press_committed("Fn", VoiceRole::Dictation, 10);
    let press_id = runtime.press_id();
    assert!(runtime
        .press_combo_committed("Fn", "missing", VoiceRole::Translate, 250)
        .is_empty());
    assert_eq!(runtime.press_id(), press_id);
    assert!(runtime
        .press_combo_committed("other", "Fn", VoiceRole::Dictation, 260)
        .is_empty());
    assert_eq!(runtime.press_id(), press_id);
}

#[test]
fn combo_takeover_does_not_replace_an_active_recording() {
    let mut runtime = InputSession::default();
    let session_id = start_id(&runtime.press(binding(InputMode::Hold, 1), 10));
    runtime.ready(session_id);
    assert!(runtime
        .press_combo_committed("Fn", "Fn+LeftShift", VoiceRole::Translate, 250)
        .is_empty());
    assert_eq!(runtime.session_id(), Some(session_id));
    assert_eq!(
        runtime.release("Fn", 300),
        vec![InputAction::Stop { session_id }]
    );
}
