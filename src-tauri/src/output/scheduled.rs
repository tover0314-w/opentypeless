pub(crate) fn execute_if_waiting<Result>(
    sender: tokio::sync::oneshot::Sender<Result>,
    execute: impl FnOnce(&dyn Fn() -> bool) -> Result,
) {
    if sender.is_closed() {
        return;
    }
    let result = execute(&|| !sender.is_closed());
    let _ = sender.send(result);
}
