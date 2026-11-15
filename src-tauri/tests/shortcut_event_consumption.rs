#[path = "../src/shortcut_event_consumption.rs"]
mod shortcut_event_consumption;

use shortcut_event_consumption::ShortcutEventConsumption;

#[test]
fn first_press_uses_the_business_acceptance_result_only_once() {
    let mut state = ShortcutEventConsumption::default();
    let calls = std::cell::Cell::new(0);
    assert!(!state.key_event_with_acceptance(49, true, || {
        calls.set(calls.get() + 1);
        false
    }));
    assert!(!state.key_event_with_acceptance(49, true, || {
        panic!("repeat must not attempt a new business claim")
    }));
    assert!(!state.key_event_with_acceptance(49, false, || {
        panic!("release must use original consumption ownership")
    }));
    assert_eq!(calls.get(), 1);
    assert!(state.key_event_with_acceptance(49, true, || true));
}

#[test]
fn accepted_press_owns_its_repeat_and_release_without_reclaiming_business_state() {
    let mut state = ShortcutEventConsumption::default();
    assert!(state.key_event_with_acceptance(49, true, || true));
    assert!(state.key_event_with_acceptance(49, true, || {
        panic!("repeat cannot dispatch another start")
    }));
    assert!(state.key_event_with_acceptance(49, false, || {
        panic!("release cannot take a replacement session")
    }));
    assert!(!state.key_event_with_acceptance(49, false, || {
        panic!("duplicate release cannot dispatch")
    }));
}

#[test]
fn matched_press_repeat_and_release_are_consumed_as_one_pair() {
    let mut state = ShortcutEventConsumption::default();
    assert!(state.key_event(49, true, true, true));
    assert!(state.key_event(49, true, false, true));
    assert!(state.key_event(49, false, false, true));
    assert!(!state.key_event(49, false, false, true));
}

#[test]
fn unmatched_space_and_other_keys_are_not_consumed() {
    let mut state = ShortcutEventConsumption::default();
    assert!(!state.key_event(49, true, false, true));
    assert!(!state.key_event(49, false, false, true));
    assert!(!state.key_event(0, true, false, true));
}

#[test]
fn editing_pause_does_not_take_ownership_of_a_new_press() {
    let mut state = ShortcutEventConsumption::default();
    assert!(!state.key_event(49, true, true, false));
    assert!(!state.key_event(49, false, true, true));
}

#[test]
fn consumed_press_release_is_still_consumed_after_pause_or_fn_release() {
    let mut state = ShortcutEventConsumption::default();
    assert!(state.key_event(49, true, true, true));
    assert!(state.key_event(49, false, false, false));
    assert!(!state.key_event(49, true, false, true));
}

#[test]
fn resuming_while_an_unconsumed_key_is_held_does_not_take_its_repeat() {
    let mut state = ShortcutEventConsumption::default();
    assert!(!state.key_event(49, true, true, false));
    assert!(!state.key_event(49, true, true, true));
    assert!(!state.key_event(49, false, true, true));
    assert!(state.key_event(49, true, true, true));
}
