#[path = "../src/voice_operation.rs"]
mod voice_operation;

pub use opentypeless_lib::retry_audio;

#[tokio::test]
async fn normal_completion_wakes_idle_work_without_marking_the_operation_aborted() {
    let operations = voice_operation::VoiceOperations::default();
    let operation = operations.begin_if(|| true).unwrap();
    let mut waiting = Box::pin(retry_audio::cancellable_retry(
        operation.cancel_receiver(),
        std::future::pending::<()>(),
    ));
    let mut context = std::task::Context::from_waker(std::task::Waker::noop());
    assert!(std::future::Future::poll(waiting.as_mut(), &mut context).is_pending());
    assert!(operations.finish(&operation, || {}));
    assert!(std::future::Future::poll(waiting.as_mut(), &mut context).is_ready());
    assert!(operation.is_cancelled());
    assert!(!operation.is_aborted());
}
