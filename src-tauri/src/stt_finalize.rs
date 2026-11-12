use std::time::Duration;
use tokio::sync::{watch, Notify};

use crate::error::AppError;

pub fn finalize_timeout(provider: &str, streaming_timeout: Duration) -> Duration {
    if provider == "cloud" {
        crate::cloud_request::STT_MAX_TIMEOUT + Duration::from_secs(15)
    } else if provider == crate::stt::config::CUSTOM_WHISPER_PROVIDER
        || crate::stt::config::get_whisper_config(provider).is_some()
    {
        crate::stt::whisper_compat::FINALIZE_TIMEOUT
    } else if provider == crate::stt::volcengine_auc::VOLCENGINE_AUC_PROVIDER {
        crate::stt::volcengine_auc::FINALIZE_TIMEOUT
    } else {
        streaming_timeout
    }
}

pub async fn wait_for_completion(done: &Notify, timeout: Duration) -> Result<(), AppError> {
    tokio::time::timeout(timeout, done.notified())
        .await
        .map_err(|_| AppError::Timeout(timeout))
}

#[derive(Clone)]
pub struct SttCancellation(watch::Sender<bool>);

impl Default for SttCancellation {
    fn default() -> Self {
        Self(watch::channel(false).0)
    }
}

impl SttCancellation {
    pub fn cancel(&self) {
        self.0.send_replace(true);
    }

    pub fn is_cancelled(&self) -> bool {
        *self.0.borrow()
    }

    pub async fn cancelled(&self) {
        let mut changes = self.0.subscribe();
        loop {
            if *changes.borrow_and_update() {
                return;
            }
            if changes.changed().await.is_err() {
                return;
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    async fn delayed_cloud_completion(former_timeout: u64) {
        let done = Arc::new(Notify::new());
        let worker_done = done.clone();
        let worker = tokio::spawn(async move {
            tokio::time::sleep(Duration::from_secs(former_timeout + 1)).await;
            worker_done.notify_one();
        });
        wait_for_completion(
            &done,
            finalize_timeout("cloud", Duration::from_secs(former_timeout)),
        )
        .await
        .unwrap();
        worker.await.unwrap();
    }

    #[tokio::test(start_paused = true)]
    async fn cloud_ask_keeps_waiting_after_twelve_seconds() {
        delayed_cloud_completion(12).await;
    }

    #[tokio::test(start_paused = true)]
    async fn cloud_dictation_keeps_waiting_after_two_minutes() {
        delayed_cloud_completion(120).await;
    }

    #[tokio::test(start_paused = true)]
    async fn file_upload_ask_does_not_stop_after_twelve_seconds() {
        for provider in ["groq-whisper", "openai-whisper", "custom-whisper"] {
            let done = Arc::new(Notify::new());
            let worker_done = done.clone();
            let worker = tokio::spawn(async move {
                tokio::time::sleep(Duration::from_secs(13)).await;
                worker_done.notify_one();
            });
            wait_for_completion(&done, finalize_timeout(provider, Duration::from_secs(12)))
                .await
                .unwrap();
            worker.await.unwrap();
        }
        assert_eq!(
            finalize_timeout("deepgram", Duration::from_secs(12)),
            Duration::from_secs(12)
        );
    }

    #[tokio::test(start_paused = true)]
    async fn completion_before_stop_is_not_lost() {
        let done = Notify::new();
        done.notify_one();
        let before = tokio::time::Instant::now();
        wait_for_completion(&done, Duration::from_secs(12))
            .await
            .unwrap();
        assert_eq!(before.elapsed(), Duration::ZERO);
    }

    #[tokio::test(start_paused = true)]
    async fn unfinished_capture_reports_timeout_instead_of_no_speech() {
        let error = wait_for_completion(&Notify::new(), Duration::from_secs(2))
            .await
            .unwrap_err();
        assert_eq!(error.to_user_error().code, "stt_timeout");
    }

    #[tokio::test(start_paused = true)]
    async fn cancellation_before_subscription_wakes_every_phase() {
        let cancellation = SttCancellation::default();
        cancellation.cancel();
        let before = tokio::time::Instant::now();
        tokio::join!(cancellation.cancelled(), cancellation.cancelled());
        assert_eq!(before.elapsed(), Duration::ZERO);
    }
}
