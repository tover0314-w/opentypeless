pub use opentypeless_lib::{error, output};

#[path = "../src/output/guarded.rs"]
mod guarded;

use guarded::GuardedOutput;
use output::{InsertResult, InsertionStrategy, OutputMode, TextOutput};
use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
use std::sync::Arc;

struct SyntheticOutput(Arc<AtomicUsize>);

#[async_trait::async_trait]
impl TextOutput for SyntheticOutput {
    async fn type_text(&self, text: &str) -> Result<InsertResult, error::AppError> {
        self.0.fetch_add(1, Ordering::SeqCst);
        Ok(InsertResult::inserted(
            InsertionStrategy::Keyboard,
            text.chars().count(),
        ))
    }

    fn mode(&self) -> OutputMode {
        OutputMode::Keyboard
    }
}

#[tokio::test]
async fn changed_guard_blocks_later_backend_calls() {
    let allowed = Arc::new(AtomicBool::new(true));
    let permission = allowed.clone();
    let calls = Arc::new(AtomicUsize::new(0));
    let backend = GuardedOutput::new(
        Box::new(SyntheticOutput(calls.clone())),
        Arc::new(move || permission.load(Ordering::SeqCst)),
    );
    backend.type_text("synthetic").await.unwrap();
    allowed.store(false, Ordering::SeqCst);
    assert!(backend.type_text("synthetic").await.is_err());
    assert_eq!(calls.load(Ordering::SeqCst), 1);
}
