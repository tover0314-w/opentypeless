#[path = "../src/output/scheduled.rs"]
mod scheduled;

use scheduled::execute_if_waiting;
use std::sync::atomic::{AtomicBool, Ordering};

#[test]
fn dropped_waiter_prevents_queued_output_execution() {
    let (sender, receiver) = tokio::sync::oneshot::channel::<()>();
    drop(receiver);
    let executed = AtomicBool::new(false);
    execute_if_waiting(sender, |_| {
        executed.store(true, Ordering::SeqCst);
    });
    assert!(!executed.load(Ordering::SeqCst));
}

#[tokio::test]
async fn timeout_prevents_late_queued_output_execution() {
    let (sender, receiver) = tokio::sync::oneshot::channel::<()>();
    assert!(tokio::time::timeout(std::time::Duration::ZERO, receiver)
        .await
        .is_err());
    let executed = AtomicBool::new(false);
    execute_if_waiting(sender, |_| {
        executed.store(true, Ordering::SeqCst);
    });
    assert!(!executed.load(Ordering::SeqCst));
}

#[tokio::test]
async fn active_waiter_receives_the_real_output_result() {
    let (sender, receiver) = tokio::sync::oneshot::channel::<Result<(), &'static str>>();
    execute_if_waiting(sender, |_| Err("synthetic output failure"));
    assert_eq!(receiver.await.unwrap(), Err("synthetic output failure"));
}

#[tokio::test]
async fn voice_cancellation_drops_the_waiter_before_queued_execution() {
    let (cancel, cancellation) = tokio::sync::watch::channel(false);
    let (sender, receiver) = tokio::sync::oneshot::channel::<()>();
    let mut output = Box::pin(opentypeless_lib::retry_audio::cancellable_retry(
        cancellation,
        receiver,
    ));
    let mut context = std::task::Context::from_waker(std::task::Waker::noop());
    assert!(std::future::Future::poll(output.as_mut(), &mut context).is_pending());
    cancel.send(true).unwrap();
    assert!(output.await.is_none());
    let executed = AtomicBool::new(false);
    execute_if_waiting(sender, |_| {
        executed.store(true, Ordering::SeqCst);
    });
    assert!(!executed.load(Ordering::SeqCst));
}

#[test]
fn executing_output_observes_waiter_cancellation_before_the_next_chunk() {
    let (sender, receiver) = tokio::sync::oneshot::channel::<()>();
    let mut receiver = Some(receiver);
    let mut chunks = Vec::new();
    execute_if_waiting(sender, |is_waiting| {
        for chunk in ["first", "second", "third"] {
            if !is_waiting() {
                break;
            }
            chunks.push(chunk);
            drop(receiver.take());
        }
    });
    assert_eq!(chunks, vec!["first"]);
}
