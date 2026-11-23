#[path = "../src/shortcut_lease.rs"]
mod shortcut_lease;

use shortcut_lease::{CaptureLeaseState, LeasePhase};

#[test]
fn current_owner_query_does_not_accept_another_capture() {
    let mut state = CaptureLeaseState::default();
    assert!(state.registration_allowed());
    assert!(!state.owned_by("main", "first"));
    state.acquire("main", "first").unwrap();
    assert!(!state.registration_allowed());
    assert!(state.owned_by("main", "first"));
    assert!(!state.owned_by("main", "second"));
    assert!(!state.owned_by("ask", "first"));
}

#[test]
fn concurrent_recovery_is_rejected_and_new_capture_supersedes_it() {
    let mut state = CaptureLeaseState::default();
    let first = state.acquire("main", "first").unwrap();
    assert_eq!(
        state.begin_resume(Some("first")).unwrap(),
        Some(first.generation),
    );
    assert!(state.begin_resume(Some("first")).is_err());
    assert!(state.recovery_is_current(first.generation));
    let next = state.acquire("main", "second").unwrap();
    assert!(!state.recovery_is_current(first.generation));
    assert!(!state.confirm_released(first.generation, Some(false)));
    assert_eq!(
        state.begin_resume(Some("second")).unwrap(),
        Some(next.generation),
    );
    assert!(state.recovery_is_current(next.generation));
}

#[test]
fn window_lifecycle_releases_only_its_owner_and_retains_recovery_identity() {
    let mut state = CaptureLeaseState::default();
    let lease = state.acquire("main", "first").unwrap();
    assert_eq!(state.release_current_window("ask"), None);
    assert!(state.is_current(&lease));
    assert_eq!(state.release_current_window("main"), Some(lease.clone()));
    assert!(!state.actions_allowed());
    assert_eq!(state.release_current_window("main"), None);
    assert_eq!(
        state.begin_resume(Some("first")).unwrap(),
        Some(lease.generation),
    );
}

#[test]
fn action_dispatch_requires_completed_registration_not_just_a_released_editor() {
    let mut state = CaptureLeaseState::default();
    assert!(state.actions_allowed());
    let lease = state.acquire("main", "first").unwrap();
    assert!(!state.actions_allowed());
    assert!(state.release(&lease));
    assert!(!state.actions_allowed());
    assert!(state.confirm_released(lease.generation, Some(false)));
    assert!(!state.actions_allowed());
    assert!(state.finish_registration(lease.generation, Some("failed".to_string())));
    assert!(!state.actions_allowed());
    assert_eq!(state.failed_released_capture(), Some(lease.clone()));
    assert_eq!(
        state.begin_resume(Some("first")).unwrap(),
        Some(lease.generation)
    );
    assert!(state.confirm_released(lease.generation, Some(false)));
    assert_eq!(state.failed_released_capture(), None);
    assert!(state.finish_registration(lease.generation, None));
    assert!(state.actions_allowed());
    assert_eq!(state.failed_released_capture(), None);
}

#[test]
fn recovery_identity_survives_failure_but_cannot_resume_a_new_editor() {
    let mut state = CaptureLeaseState::default();
    let first = state.acquire("main", "first").unwrap();
    assert!(state.begin_resume(None).is_err());
    assert!(state.begin_resume(Some("wrong")).is_err());
    assert_eq!(
        state.begin_resume(Some("first")).unwrap(),
        Some(first.generation)
    );
    assert!(state.recovery_failed(first.generation, "observation unavailable".to_string()));
    assert_eq!(state.last_error(), Some("observation unavailable"));
    assert_eq!(
        state.begin_resume(Some("first")).unwrap(),
        Some(first.generation)
    );
    assert!(state.confirm_released(first.generation, Some(false)));
    assert!(state.begin_resume(Some("first")).is_err());
    assert!(state.finish_registration(first.generation, None));
    assert_eq!(state.begin_resume(Some("first")).unwrap(), None);
    assert_eq!(state.begin_resume(None).unwrap(), None);
    state.acquire("main", "second").unwrap();
    assert!(state.begin_resume(Some("first")).is_err());
    assert!(!state.recovery_failed(first.generation, "old failure".to_string()));
}

#[test]
fn release_confirmation_cannot_enable_actions_while_capture_is_active() {
    let mut state = CaptureLeaseState::default();
    let lease = state.acquire("main", "first").unwrap();
    assert!(!state.confirm_released(lease.generation, Some(false)));
    assert_eq!(state.phase(), LeasePhase::Capturing);
    assert!(state.is_current(&lease));
}

#[test]
fn duplicate_release_and_confirmation_do_not_create_an_extra_transition() {
    let mut state = CaptureLeaseState::default();
    let lease = state.acquire("main", "first").unwrap();
    assert!(state.release(&lease));
    assert!(!state.release(&lease));
    assert_eq!(state.phase(), LeasePhase::AwaitingRelease);
    assert!(state.confirm_released(lease.generation, Some(false)));
    assert!(!state.confirm_released(lease.generation, Some(false)));
    assert!(!state.release(&lease));
    assert_eq!(state.phase(), LeasePhase::Registering);
}

#[test]
fn matching_generation_alone_does_not_authorize_a_different_owner() {
    let mut state = CaptureLeaseState::default();
    let lease = state.acquire("main", "first").unwrap();
    let mut wrong_capture = lease.clone();
    wrong_capture.capture_id = "other".to_string();
    assert!(!state.release(&wrong_capture));
    let mut wrong_window = lease.clone();
    wrong_window.window_label = "capsule".to_string();
    assert!(!state.release(&wrong_window));
    assert!(state.is_current(&lease));
}

#[test]
fn duplicate_acquisition_is_idempotent() {
    let mut state = CaptureLeaseState::default();
    let first = state.acquire("main", "first").unwrap();
    assert_eq!(state.acquire("main", "first").unwrap(), first);
    assert_eq!(state.phase(), LeasePhase::Capturing);
}

#[test]
fn stale_release_cannot_end_a_new_capture() {
    let mut state = CaptureLeaseState::default();
    let first = state.acquire("main", "first").unwrap();
    let second = state.acquire("main", "second").unwrap();
    assert!(!state.release(&first));
    assert!(state.is_current(&second));
    assert_eq!(state.phase(), LeasePhase::Capturing);
}

#[test]
fn release_requires_observed_full_key_release_before_activation() {
    let mut state = CaptureLeaseState::default();
    let lease = state.acquire("main", "first").unwrap();
    assert!(state.release(&lease));
    assert_eq!(state.phase(), LeasePhase::AwaitingRelease);
    assert!(!state.confirm_released(lease.generation, None));
    assert!(!state.confirm_released(lease.generation, Some(true)));
    assert!(state.confirm_released(lease.generation, Some(false)));
    assert_eq!(state.phase(), LeasePhase::Registering);
}

#[test]
fn a_new_capture_invalidates_pending_release_confirmation() {
    let mut state = CaptureLeaseState::default();
    let first = state.acquire("main", "first").unwrap();
    assert!(state.release(&first));
    let second = state.acquire("main", "second").unwrap();
    assert!(!state.confirm_released(first.generation, Some(false)));
    assert!(state.is_current(&second));
}

#[test]
fn delayed_window_cleanup_cannot_clear_a_new_generation() {
    let mut state = CaptureLeaseState::default();
    let first = state.acquire("main", "first").unwrap();
    let second = state.acquire("main", "second").unwrap();
    assert!(!state.release_window("main", first.generation));
    assert!(state.is_current(&second));
    assert!(state.release_window("main", second.generation));
    assert_eq!(state.phase(), LeasePhase::AwaitingRelease);
}

#[test]
fn only_the_main_window_can_acquire_a_nonempty_capture() {
    let mut state = CaptureLeaseState::default();
    assert!(state.acquire("capsule", "first").is_err());
    assert!(state.acquire("ask", "first").is_err());
    assert!(state.acquire("main", "").is_err());
    assert_eq!(state.phase(), LeasePhase::Ready);
}

#[test]
fn only_current_successful_registration_restores_ready() {
    let mut state = CaptureLeaseState::default();
    let lease = state.acquire("main", "first").unwrap();
    assert!(!state.finish_registration(lease.generation, None));
    assert!(state.release(&lease));
    assert!(state.confirm_released(lease.generation, Some(false)));
    assert!(state.finish_registration(lease.generation, None));
    assert_eq!(state.phase(), LeasePhase::Ready);
    assert_eq!(state.last_error(), None);
}

#[test]
fn registration_failure_is_retained_and_cannot_be_overwritten_by_late_success() {
    let mut state = CaptureLeaseState::default();
    let lease = state.acquire("main", "first").unwrap();
    assert!(state.release(&lease));
    assert!(state.confirm_released(lease.generation, Some(false)));
    assert!(state.finish_registration(lease.generation, Some("monitor unavailable".to_string())));
    assert_eq!(state.phase(), LeasePhase::Failed);
    assert_eq!(state.last_error(), Some("monitor unavailable"));
    assert!(!state.finish_registration(lease.generation, None));
    let next = state.acquire("main", "second").unwrap();
    assert!(!state.finish_registration(lease.generation, None));
    assert!(state.is_current(&next));
    assert_eq!(state.last_error(), None);
}
