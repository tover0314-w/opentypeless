pub use opentypeless_lib::{error, output};

#[path = "../src/output/keyboard_progress.rs"]
mod keyboard_progress;

use error::AppError;
use keyboard_progress::finish_keyboard_attempt;
use output::{InsertStatus, InsertionStrategy};

#[test]
fn windows_keyboard_output_returns_chunk_progress_instead_of_replaying_failure() {
    let source = include_str!("../src/output/keyboard.rs");
    let worker = source
        .split("tokio::task::spawn_blocking(move || {")
        .nth(1)
        .unwrap()
        .split("    fn mode(")
        .next()
        .unwrap();
    assert!(worker.contains("finish_keyboard_attempt("));
    assert!(worker.contains("completed_chars.get()"));
}

#[test]
fn failure_after_completed_chunks_preserves_partial_progress() {
    let result =
        finish_keyboard_attempt(Err(AppError::Output("synthetic failure".into())), 200).unwrap();
    assert_eq!(result.status, InsertStatus::PartiallyInserted);
    assert_eq!(result.strategy_used, InsertionStrategy::Keyboard);
    assert_eq!(result.chars_inserted, 200);
}

#[test]
fn failure_before_any_completed_chunk_remains_a_failure() {
    assert!(finish_keyboard_attempt(Err(AppError::Output("synthetic failure".into())), 0).is_err());
}

#[test]
fn successful_attempt_reports_completed_progress() {
    let result = finish_keyboard_attempt(Ok(()), 201).unwrap();
    assert_eq!(result.status, InsertStatus::Inserted);
    assert_eq!(result.chars_inserted, 201);
}

#[test]
fn failed_newline_attempt_still_releases_the_pressed_modifier() {
    let events = std::cell::RefCell::new(Vec::new());
    let result = keyboard_progress::type_shifted_newline(
        || {
            events.borrow_mut().push("press");
            Ok::<(), &'static str>(())
        },
        || {
            events.borrow_mut().push("newline");
            Err("synthetic newline failure")
        },
        || {
            events.borrow_mut().push("release");
            Ok(())
        },
    );
    assert_eq!(result, Err("synthetic newline failure"));
    assert_eq!(*events.borrow(), vec!["press", "newline", "release"]);
}

#[test]
fn failed_modifier_press_does_not_attempt_newline() {
    let result = keyboard_progress::type_shifted_newline(
        || Err::<(), _>("synthetic modifier failure"),
        || panic!("newline cannot execute without modifier ownership"),
        || Ok(()),
    );
    assert_eq!(result, Err("synthetic modifier failure"));
}

#[test]
fn successful_newline_reports_modifier_release_failure() {
    let events = std::cell::RefCell::new(Vec::new());
    let result = keyboard_progress::type_shifted_newline(
        || {
            events.borrow_mut().push("press");
            Ok::<(), &'static str>(())
        },
        || {
            events.borrow_mut().push("newline");
            Ok(())
        },
        || {
            events.borrow_mut().push("release");
            Err("synthetic release failure")
        },
    );
    assert_eq!(result, Err("synthetic release failure"));
    assert_eq!(*events.borrow(), vec!["press", "newline", "release"]);
}

#[test]
fn newline_failure_is_preserved_when_release_also_fails() {
    let result = keyboard_progress::type_shifted_newline(
        || Ok::<(), &'static str>(()),
        || Err("synthetic newline failure"),
        || Err("synthetic release failure"),
    );
    assert_eq!(result, Err("synthetic newline failure"));
}
