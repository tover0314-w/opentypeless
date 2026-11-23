use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex, MutexGuard};
use tokio::sync::watch;

struct OperationState {
    aborted: Arc<AtomicBool>,
    dismissed: AtomicBool,
    finished: AtomicBool,
    cancelled: watch::Sender<bool>,
}

impl Default for OperationState {
    fn default() -> Self {
        Self {
            aborted: Arc::default(),
            dismissed: AtomicBool::default(),
            finished: AtomicBool::default(),
            cancelled: watch::channel(false).0,
        }
    }
}

impl OperationState {
    fn is_active(&self) -> bool {
        !self.aborted.load(Ordering::SeqCst) && !self.finished.load(Ordering::SeqCst)
    }

    fn abort(&self) {
        self.aborted.store(true, Ordering::SeqCst);
        self.cancelled.send_replace(true);
    }
}

/// Each voice operation owns its abort flag, so starting the next operation can never clear an
/// abort that an older operation has not observed yet. Callbacks run under a short std lock and
/// must not wait on the main thread; tray updates belong after the call returns.
#[derive(Clone, Default)]
pub(crate) struct VoiceOperations {
    current: Arc<Mutex<Arc<OperationState>>>,
}

#[derive(Clone)]
pub(crate) struct VoiceOperationToken {
    state: Arc<OperationState>,
}

impl VoiceOperations {
    pub(crate) fn current_token(&self) -> Option<VoiceOperationToken> {
        let current = self.lock();
        current.is_active().then(|| VoiceOperationToken {
            state: current.clone(),
        })
    }

    pub(crate) fn adopt_token_if(
        &self,
        expected: &VoiceOperationToken,
        claim: impl FnOnce() -> bool,
    ) -> Option<VoiceOperationToken> {
        let current = self.lock();
        if !Arc::ptr_eq(&current, &expected.state) || !current.is_active() || !claim() {
            return None;
        }
        Some(VoiceOperationToken {
            state: current.clone(),
        })
    }

    pub(crate) fn begin_if(&self, claim: impl FnOnce() -> bool) -> Option<VoiceOperationToken> {
        let mut current = self.lock();
        if !claim() {
            return None;
        }
        if current.is_active() {
            current.abort();
        }
        *current = Arc::new(OperationState::default());
        Some(VoiceOperationToken {
            state: current.clone(),
        })
    }

    /// Joins the running operation, e.g. when releasing the hotkey moves recording to transcribing.
    pub(crate) fn adopt_if(&self, claim: impl FnOnce() -> bool) -> Option<VoiceOperationToken> {
        let current = self.lock();
        if !current.is_active() || !claim() {
            return None;
        }
        Some(VoiceOperationToken {
            state: current.clone(),
        })
    }

    pub(crate) fn abort_current(&self, cleanup: impl FnOnce()) {
        let current = self.lock();
        if current.is_active() {
            current.abort();
        }
        cleanup();
    }

    /// Aborts like `abort_current`, but records that the user cancelled the take on purpose, so the
    /// run keeps its recording in History. Returns whether a running operation was dismissed.
    pub(crate) fn dismiss_current(&self, cleanup: impl FnOnce()) -> bool {
        let current = self.lock();
        let active = current.is_active();
        if active {
            current.dismissed.store(true, Ordering::SeqCst);
            current.abort();
        }
        cleanup();
        active
    }

    pub(crate) fn cancel(&self, operation: &VoiceOperationToken, cleanup: impl FnOnce()) -> bool {
        let _current = self.lock();
        if operation.is_cancelled() {
            return false;
        }
        operation.state.abort();
        cleanup();
        true
    }

    pub(crate) fn cancel_if_current(
        &self,
        operation: &VoiceOperationToken,
        cleanup: impl FnOnce(),
    ) -> bool {
        let current = self.lock();
        if !Arc::ptr_eq(&current, &operation.state) || !current.is_active() {
            return false;
        }
        current.abort();
        cleanup();
        true
    }

    /// Serialized with aborts, so no update can land after an abort has returned.
    pub(crate) fn update_if_active(
        &self,
        operation: &VoiceOperationToken,
        update: impl FnOnce(),
    ) -> bool {
        let _current = self.lock();
        if operation.is_cancelled() {
            return false;
        }
        update();
        true
    }

    pub(crate) fn finish(&self, operation: &VoiceOperationToken, on_finish: impl FnOnce()) -> bool {
        let _current = self.lock();
        if operation.is_cancelled() {
            return false;
        }
        operation.state.finished.store(true, Ordering::SeqCst);
        operation.state.cancelled.send_replace(true);
        on_finish();
        true
    }

    fn lock(&self) -> MutexGuard<'_, Arc<OperationState>> {
        self.current
            .lock()
            .unwrap_or_else(|error| error.into_inner())
    }
}

impl VoiceOperationToken {
    /// True once the operation was aborted, superseded, or has finished.
    pub(crate) fn is_cancelled(&self) -> bool {
        !self.state.is_active()
    }

    pub(crate) fn is_aborted(&self) -> bool {
        self.state.aborted.load(Ordering::SeqCst)
    }

    pub(crate) fn is_dismissed(&self) -> bool {
        self.state.dismissed.load(Ordering::SeqCst)
    }

    pub(crate) fn abort_flag(&self) -> Arc<AtomicBool> {
        self.state.aborted.clone()
    }

    /// Becomes true when the operation is aborted, so pending work can stop waiting at once.
    pub(crate) fn cancel_receiver(&self) -> watch::Receiver<bool> {
        self.state.cancelled.subscribe()
    }
}

/// Runs its action when dropped, including when the owning future is dropped mid-await.
pub(crate) struct OnDrop<F: FnOnce()>(Option<F>);

impl<F: FnOnce()> OnDrop<F> {
    pub(crate) fn new(action: F) -> Self {
        Self(Some(action))
    }
}

impl<F: FnOnce()> Drop for OnDrop<F> {
    fn drop(&mut self) {
        if let Some(action) = self.0.take() {
            action();
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicU8, AtomicUsize};

    const IDLE: u8 = 0;
    const POLISHING: u8 = 1;
    const RECORDING: u8 = 2;
    const TRANSCRIBING: u8 = 3;

    fn begin(operations: &VoiceOperations) -> VoiceOperationToken {
        operations.begin_if(|| true).unwrap()
    }

    #[test]
    fn a_reserved_stop_cannot_adopt_the_replacement_operation() {
        let operations = VoiceOperations::default();
        let first = begin(&operations);
        let reserved = operations.current_token().unwrap();
        operations.abort_current(|| {});
        let next = begin(&operations);
        assert!(operations.adopt_token_if(&reserved, || true).is_none());
        assert!(!next.is_cancelled());
        assert!(first.is_cancelled());
    }

    #[test]
    fn a_reserved_stop_adopts_only_its_still_active_operation() {
        let operations = VoiceOperations::default();
        let first = begin(&operations);
        let reserved = operations.current_token().unwrap();
        assert!(operations.adopt_token_if(&reserved, || false).is_none());
        assert!(operations.adopt_token_if(&reserved, || true).is_some());
        operations.finish(&first, || {});
        assert!(operations.current_token().is_none());
        assert!(operations.adopt_token_if(&reserved, || true).is_none());
    }

    fn claim(state: &AtomicU8, from: u8, to: u8) -> bool {
        state
            .compare_exchange(from, to, Ordering::SeqCst, Ordering::SeqCst)
            .is_ok()
    }

    #[test]
    fn aborted_operation_stays_cancelled_after_the_next_one_begins() {
        let operations = VoiceOperations::default();
        let old = begin(&operations);
        operations.abort_current(|| {});
        let new = begin(&operations);

        assert!(old.is_cancelled());
        assert!(old.abort_flag().load(Ordering::SeqCst));
        assert!(!new.is_cancelled());
        assert!(!new.abort_flag().load(Ordering::SeqCst));
    }

    #[test]
    fn dismissing_marks_only_the_running_operation() {
        let operations = VoiceOperations::default();
        let aborted = begin(&operations);
        operations.abort_current(|| {});
        assert!(aborted.is_aborted());
        assert!(!aborted.is_dismissed());

        let dismissed = begin(&operations);
        assert!(operations.dismiss_current(|| {}));
        assert!(dismissed.is_aborted());
        assert!(dismissed.is_dismissed());

        let finished = begin(&operations);
        assert!(operations.finish(&finished, || {}));
        assert!(!operations.dismiss_current(|| {}));
        assert!(!finished.is_dismissed());
    }

    #[test]
    fn beginning_supersedes_an_unfinished_operation() {
        let operations = VoiceOperations::default();
        let old = begin(&operations);
        let new = begin(&operations);

        assert!(old.is_aborted());
        assert!(!new.is_cancelled());
    }

    #[test]
    fn failed_claim_leaves_the_running_operation_alone() {
        let operations = VoiceOperations::default();
        let state = AtomicU8::new(IDLE);
        let running = operations
            .begin_if(|| claim(&state, IDLE, RECORDING))
            .unwrap();

        assert!(operations
            .begin_if(|| claim(&state, IDLE, POLISHING))
            .is_none());
        assert!(!running.is_cancelled());
        assert_eq!(state.load(Ordering::SeqCst), RECORDING);
    }

    #[test]
    fn stopping_adopts_the_running_operation_and_sees_its_abort() {
        let operations = VoiceOperations::default();
        let state = AtomicU8::new(IDLE);
        let started = operations
            .begin_if(|| claim(&state, IDLE, RECORDING))
            .unwrap();
        let adopted = operations
            .adopt_if(|| claim(&state, RECORDING, TRANSCRIBING))
            .unwrap();
        assert!(!adopted.is_cancelled());

        operations.abort_current(|| {});
        assert!(started.is_cancelled());
        assert!(adopted.is_cancelled());
    }

    #[test]
    fn stopping_cannot_adopt_an_aborted_operation() {
        let operations = VoiceOperations::default();
        let state = AtomicU8::new(IDLE);
        operations
            .begin_if(|| claim(&state, IDLE, RECORDING))
            .unwrap();
        operations.abort_current(|| {});

        assert!(operations
            .adopt_if(|| claim(&state, RECORDING, TRANSCRIBING))
            .is_none());
        assert_eq!(state.load(Ordering::SeqCst), RECORDING);
    }

    #[test]
    fn finishing_is_not_an_abort() {
        let operations = VoiceOperations::default();
        let finished = begin(&operations);
        assert!(operations.finish(&finished, || {}));
        operations.abort_current(|| {});
        begin(&operations);

        assert!(finished.is_cancelled());
        assert!(!finished.is_aborted());
    }

    #[test]
    fn aborted_operation_does_not_run_its_finish_action() {
        let operations = VoiceOperations::default();
        let operation = begin(&operations);
        let finishes = AtomicUsize::new(0);
        operations.abort_current(|| {});

        assert!(!operations.finish(&operation, || {
            finishes.fetch_add(1, Ordering::SeqCst);
        }));
        assert_eq!(finishes.load(Ordering::SeqCst), 0);
    }

    #[test]
    fn finish_runs_once() {
        let operations = VoiceOperations::default();
        let operation = begin(&operations);
        let finishes = AtomicUsize::new(0);

        assert!(operations.finish(&operation, || {
            finishes.fetch_add(1, Ordering::SeqCst);
        }));
        assert!(!operations.finish(&operation, || {
            finishes.fetch_add(1, Ordering::SeqCst);
        }));
        assert_eq!(finishes.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn stale_operation_cannot_overwrite_the_newer_operation_state() {
        let operations = VoiceOperations::default();
        let state = AtomicU8::new(IDLE);
        let old = operations
            .begin_if(|| claim(&state, IDLE, RECORDING))
            .unwrap();
        operations.abort_current(|| state.store(IDLE, Ordering::SeqCst));
        let new = operations
            .begin_if(|| claim(&state, IDLE, RECORDING))
            .unwrap();

        assert!(!operations.update_if_active(&old, || state.store(POLISHING, Ordering::SeqCst)));
        assert!(!operations.finish(&old, || state.store(IDLE, Ordering::SeqCst)));
        assert!(!operations.cancel(&old, || state.store(IDLE, Ordering::SeqCst)));
        assert_eq!(state.load(Ordering::SeqCst), RECORDING);
        assert!(!new.is_cancelled());
    }

    #[test]
    fn cancelling_after_finish_is_a_no_op() {
        let operations = VoiceOperations::default();
        let operation = begin(&operations);
        let cleanups = AtomicUsize::new(0);
        operations.finish(&operation, || {});

        assert!(!operations.cancel(&operation, || {
            cleanups.fetch_add(1, Ordering::SeqCst);
        }));
        assert_eq!(cleanups.load(Ordering::SeqCst), 0);
        assert!(!operation.is_aborted());
    }

    #[test]
    fn owned_cancel_cleans_only_the_current_operation() {
        let operations = VoiceOperations::default();
        let first = begin(&operations);
        let replacement = begin(&operations);
        let cleaned = std::cell::Cell::new(false);
        assert!(!operations.cancel_if_current(&first, || cleaned.set(true)));
        assert!(!cleaned.get());
        assert!(!replacement.is_cancelled());
        assert!(operations.cancel_if_current(&replacement, || cleaned.set(true)));
        assert!(cleaned.get());
        assert!(replacement.is_cancelled());
        assert!(!operations.cancel_if_current(&replacement, || panic!("cancel ran twice")));
    }

    #[test]
    fn no_update_lands_after_abort_returns() {
        for _ in 0..200 {
            let operations = VoiceOperations::default();
            let state = Arc::new(AtomicU8::new(IDLE));
            let operation = begin(&operations);
            let writer = {
                let operations = operations.clone();
                let state = state.clone();
                std::thread::spawn(move || {
                    for _ in 0..10_000 {
                        if !operations.update_if_active(&operation, || {
                            state.store(POLISHING, Ordering::SeqCst)
                        }) {
                            break;
                        }
                        std::thread::yield_now();
                    }
                })
            };
            std::thread::yield_now();
            operations.abort_current(|| state.store(IDLE, Ordering::SeqCst));
            writer.join().unwrap();

            assert_eq!(state.load(Ordering::SeqCst), IDLE);
        }
    }

    #[tokio::test]
    async fn pending_work_stops_waiting_when_aborted_or_superseded() {
        let operations = VoiceOperations::default();
        let aborted = begin(&operations);
        let waiting = tokio::spawn(crate::retry_audio::cancellable_retry(
            aborted.cancel_receiver(),
            std::future::pending::<()>(),
        ));
        tokio::task::yield_now().await;
        operations.abort_current(|| {});
        assert_eq!(waiting.await.unwrap(), None);

        let superseded = begin(&operations);
        let waiting = tokio::spawn(crate::retry_audio::cancellable_retry(
            superseded.cancel_receiver(),
            std::future::pending::<()>(),
        ));
        tokio::task::yield_now().await;
        let current = begin(&operations);
        assert_eq!(waiting.await.unwrap(), None);
        assert!(!*current.cancel_receiver().borrow());
    }

    #[tokio::test]
    async fn dropping_a_pending_operation_cancels_it_and_resets_state() {
        let operations = VoiceOperations::default();
        let operation = begin(&operations);
        let state = Arc::new(AtomicU8::new(POLISHING));
        let running = {
            let operations = operations.clone();
            let operation = operation.clone();
            let state = state.clone();
            async move {
                let _cancel = OnDrop::new(move || {
                    operations.cancel(&operation, || state.store(IDLE, Ordering::SeqCst));
                });
                std::future::pending::<()>().await;
            }
        };

        let dropped = tokio::time::timeout(std::time::Duration::from_millis(5), running).await;

        assert!(dropped.is_err());
        assert_eq!(state.load(Ordering::SeqCst), IDLE);
        assert!(operation.is_aborted());
    }
}
