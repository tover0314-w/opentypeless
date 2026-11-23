#[path = "../src/hotkey_registration.rs"]
mod hotkey_registration;

use hotkey_registration::register_adapters;
use std::cell::RefCell;

#[test]
fn ordinary_conflict_does_not_skip_other_bindings_or_native_monitor() {
    let events = RefCell::new(Vec::new());
    let report = register_adapters(
        &[0, 1],
        || {
            events.borrow_mut().push("reset");
            Ok(())
        },
        |binding| {
            events
                .borrow_mut()
                .push(if *binding == 0 { "first" } else { "second" });
            if *binding == 0 {
                Err("occupied".into())
            } else {
                Ok(())
            }
        },
        || {
            events.borrow_mut().push("native");
            Ok(())
        },
    );
    assert_eq!(*events.borrow(), ["reset", "first", "second", "native"]);
    assert_eq!(report.global, [Err("occupied".into()), Ok(())]);
    assert_eq!(report.native, Ok(()));
    assert_eq!(report.error().as_deref(), Some("occupied"));
}

#[test]
fn reset_failure_blocks_unsafe_ordinary_changes_but_still_attempts_native() {
    let report = register_adapters(
        &[0, 1],
        || Err("reset failed".into()),
        |_| panic!("ordinary registration ran after reset failure"),
        || Ok(()),
    );
    assert_eq!(
        report.global,
        [Err("reset failed".into()), Err("reset failed".into())]
    );
    assert_eq!(report.native, Ok(()));
    assert_eq!(report.error().as_deref(), Some("reset failed"));
}

#[test]
fn native_failure_does_not_erase_successful_ordinary_results() {
    let report = register_adapters(&[0, 1], || Ok(()), |_| Ok(()), || Err("no tap".into()));
    assert_eq!(report.global, [Ok(()), Ok(())]);
    assert_eq!(report.native, Err("no tap".into()));
    assert_eq!(report.error().as_deref(), Some("no tap"));
}

#[test]
fn all_adapter_errors_are_retained_without_duplicate_reset_messages() {
    let report = register_adapters(
        &[0, 1],
        || Err("reset failed".into()),
        |_| panic!("reset failed"),
        || Err("native failed".into()),
    );
    assert_eq!(
        report.error().as_deref(),
        Some("reset failed; native failed")
    );
}
