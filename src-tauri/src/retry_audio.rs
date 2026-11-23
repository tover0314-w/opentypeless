use crate::stt::{whisper_compat::WhisperCompatConfig, SttConfig};
use std::time::{Duration, Instant};

const MAX_RETRY_AUDIO_BYTES: usize = 24 * 1024 * 1024;
const RETENTION: Duration = Duration::from_secs(600);

pub async fn cancellable_retry<F, T>(
    mut cancel: tokio::sync::watch::Receiver<bool>,
    operation: F,
) -> Option<T>
where
    F: std::future::Future<Output = T>,
{
    if *cancel.borrow() {
        return None;
    }
    tokio::select! {
        biased;
        _ = cancel.changed() => None,
        result = operation => {
            if *cancel.borrow() { None } else { Some(result) }
        }
    }
}

pub struct RetryAudio {
    pub provider: WhisperCompatConfig,
    pub config: SttConfig,
    pub pcm: Vec<u8>,
    captured_at: Instant,
}

impl RetryAudio {
    pub fn new(provider: WhisperCompatConfig, mut config: SttConfig, pcm: Vec<u8>) -> Option<Self> {
        if pcm.is_empty() || pcm.len() > MAX_RETRY_AUDIO_BYTES {
            return None;
        }
        config.api_key.clear();
        Some(Self {
            provider,
            config,
            pcm,
            captured_at: Instant::now(),
        })
    }

    pub fn is_expired(&self) -> bool {
        self.captured_at.elapsed() >= RETENTION
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn cancellation_ends_idle_chunk_wait_while_producer_remains_open() {
        let (cancel, cancellation) = tokio::sync::watch::channel(false);
        let (producer, mut chunks) = tokio::sync::mpsc::unbounded_channel::<String>();
        let mut pending = Box::pin(cancellable_retry(cancellation, chunks.recv()));
        let mut context = std::task::Context::from_waker(std::task::Waker::noop());
        assert!(std::future::Future::poll(pending.as_mut(), &mut context).is_pending());
        cancel.send(true).unwrap();
        assert!(pending.await.is_none());
        producer.send("Synthetic next chunk".into()).unwrap();
        assert_eq!(chunks.recv().await.as_deref(), Some("Synthetic next chunk"));
    }

    #[tokio::test]
    async fn cancelled_retry_drops_the_pending_operation() {
        let (sender, receiver) = tokio::sync::watch::channel(false);
        let task = tokio::spawn(cancellable_retry(
            receiver,
            std::future::pending::<String>(),
        ));
        sender.send(true).unwrap();
        assert!(tokio::time::timeout(Duration::from_millis(100), task)
            .await
            .unwrap()
            .unwrap()
            .is_none());
    }

    #[tokio::test]
    async fn completed_retry_returns_its_value_without_cancellation() {
        let (_sender, receiver) = tokio::sync::watch::channel(false);
        assert_eq!(
            cancellable_retry(receiver, async { "text" }).await,
            Some("text")
        );
    }

    #[tokio::test]
    async fn cancellation_before_polling_does_not_run_the_operation() {
        let (sender, receiver) = tokio::sync::watch::channel(false);
        sender.send(true).unwrap();
        let ran = std::sync::atomic::AtomicBool::new(false);
        let result = cancellable_retry(receiver, async {
            ran.store(true, std::sync::atomic::Ordering::SeqCst);
            "late text"
        })
        .await;
        assert!(result.is_none());
        assert!(!ran.load(std::sync::atomic::Ordering::SeqCst));
    }

    fn provider() -> WhisperCompatConfig {
        WhisperCompatConfig {
            provider_name: "custom-whisper".to_string(),
            endpoint: "http://localhost:8000/v1/audio/transcriptions".to_string(),
            model: "test".to_string(),
            extra_fields: vec![],
            api_key_required: false,
        }
    }

    #[test]
    fn retains_audio_but_not_credentials() {
        let config = SttConfig {
            api_key: "secret".to_string(),
            ..SttConfig::default()
        };
        let retry = RetryAudio::new(provider(), config, vec![1, 2]).unwrap();
        assert_eq!(retry.pcm, [1, 2]);
        assert!(retry.config.api_key.is_empty());
        assert!(!retry.is_expired());
    }

    #[test]
    fn rejects_empty_or_oversized_audio() {
        assert!(RetryAudio::new(provider(), SttConfig::default(), vec![]).is_none());
        assert!(RetryAudio::new(
            provider(),
            SttConfig::default(),
            vec![0; MAX_RETRY_AUDIO_BYTES + 1],
        )
        .is_none());
    }

    #[test]
    fn expires_after_ten_minutes() {
        let mut retry = RetryAudio::new(provider(), SttConfig::default(), vec![0, 0]).unwrap();
        retry.captured_at = Instant::now() - RETENTION;
        assert!(retry.is_expired());
    }
}
