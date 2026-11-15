use crate::error::AppError;
use crate::output::{InsertResult, OutputMode, TextOutput};
use std::sync::Arc;

pub(crate) type OutputGuard = Arc<dyn Fn() -> bool + Send + Sync>;

pub(crate) struct GuardedOutput {
    backend: Box<dyn TextOutput>,
    guard: OutputGuard,
}

impl GuardedOutput {
    pub(crate) fn new(backend: Box<dyn TextOutput>, guard: OutputGuard) -> Self {
        Self { backend, guard }
    }
}

#[async_trait::async_trait]
impl TextOutput for GuardedOutput {
    async fn type_text(&self, text: &str) -> Result<InsertResult, AppError> {
        if !(self.guard)() {
            return Err(AppError::OutputUnconfirmed(
                "Output operation is no longer permitted".into(),
            ));
        }
        self.backend.type_text(text).await
    }

    fn mode(&self) -> OutputMode {
        self.backend.mode()
    }
}
