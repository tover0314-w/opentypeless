#[path = "../src/ask_stop_request.rs"]
mod ask_stop_request;

use ask_stop_request::{resolve_stop_request, AskStopDisposition};

#[test]
fn preparation_retains_stop_for_the_owned_generation() {
    assert_eq!(
        resolve_stop_request(7, 7, true, None, false),
        AskStopDisposition::Pending
    );
}

#[test]
fn preparation_completion_returns_the_same_recording_identity() {
    assert_eq!(
        resolve_stop_request(7, 7, false, Some(42), false),
        AskStopDisposition::Recording(42)
    );
}

#[test]
fn replacement_startup_rejects_the_old_stop_request() {
    for starting in [true, false] {
        assert_eq!(
            resolve_stop_request(7, 8, starting, Some(43), false),
            AskStopDisposition::Rejected
        );
    }
}

#[test]
fn completed_or_processing_operations_do_not_accept_another_stop() {
    assert_eq!(
        resolve_stop_request(7, 7, false, None, false),
        AskStopDisposition::Rejected
    );
    assert_eq!(
        resolve_stop_request(7, 7, false, Some(42), true),
        AskStopDisposition::Rejected
    );
}
