pub mod capture;

pub use capture::{AudioCaptureHandle, AudioConfig, CaptureState};

use std::{future::Future, time::Duration};

pub(crate) const STARTUP_TIMEOUT: Duration = Duration::from_secs(30);

#[derive(Debug, PartialEq, Eq)]
pub(crate) enum RecordingStartupError<AudioError, SttError> {
    Audio(AudioError),
    Stt(SttError),
    Timeout,
}

pub(crate) async fn await_recording_startup<
    AudioFuture,
    SttFuture,
    AudioReady,
    AudioError,
    SttError,
>(
    audio_ready: AudioFuture,
    stt_ready: SttFuture,
) -> Result<AudioReady, RecordingStartupError<AudioError, SttError>>
where
    AudioFuture: Future<Output = Result<AudioReady, AudioError>>,
    SttFuture: Future<Output = Result<(), SttError>>,
{
    await_recording_startup_with_timeout(audio_ready, stt_ready, STARTUP_TIMEOUT).await
}

async fn await_recording_startup_with_timeout<
    AudioFuture,
    SttFuture,
    AudioReady,
    AudioError,
    SttError,
>(
    audio_ready: AudioFuture,
    stt_ready: SttFuture,
    timeout: Duration,
) -> Result<AudioReady, RecordingStartupError<AudioError, SttError>>
where
    AudioFuture: Future<Output = Result<AudioReady, AudioError>>,
    SttFuture: Future<Output = Result<(), SttError>>,
{
    tokio::time::timeout(timeout, async {
        let (audio_ready, ()) = tokio::try_join!(
            async { audio_ready.await.map_err(RecordingStartupError::Audio) },
            async { stt_ready.await.map_err(RecordingStartupError::Stt) }
        )?;
        Ok(audio_ready)
    })
    .await
    .unwrap_or(Err(RecordingStartupError::Timeout))
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;
    use std::time::Duration;
    use tokio::sync::Notify;

    #[tokio::test]
    async fn audio_initialization_and_stt_connection_are_polled_concurrently() {
        let audio_started = Arc::new(Notify::new());
        let provider_connected = Arc::new(Notify::new());

        let audio_ready = {
            let audio_started = audio_started.clone();
            let provider_connected = provider_connected.clone();
            async move {
                audio_started.notify_one();
                provider_connected.notified().await;
                Ok::<u32, &'static str>(42)
            }
        };
        let stt_ready = async move {
            audio_started.notified().await;
            provider_connected.notify_one();
            Ok::<(), &'static str>(())
        };

        let result = tokio::time::timeout(
            Duration::from_millis(100),
            await_recording_startup(audio_ready, stt_ready),
        )
        .await;

        assert!(
            result.is_ok(),
            "audio initialization was not polled while STT was connecting"
        );
        assert_eq!(result.unwrap().unwrap(), 42);
    }

    #[tokio::test]
    async fn recording_startup_times_out_once() {
        let result = await_recording_startup_with_timeout(
            std::future::pending::<Result<u32, &'static str>>(),
            std::future::pending::<Result<(), &'static str>>(),
            Duration::from_millis(20),
        )
        .await;

        assert_eq!(result, Err(RecordingStartupError::Timeout));
    }

    #[tokio::test]
    async fn audio_error_wins_when_both_sides_are_ready_with_errors() {
        let result = await_recording_startup_with_timeout(
            async { Err::<u32, _>("audio") },
            async { Err::<(), _>("stt") },
            Duration::from_millis(20),
        )
        .await;

        assert_eq!(result, Err(RecordingStartupError::Audio("audio")));
    }
}
