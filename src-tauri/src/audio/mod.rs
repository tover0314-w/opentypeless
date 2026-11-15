pub mod archive;
pub(crate) mod better_microphone;
pub mod capture;
pub(crate) mod device_notice;
pub mod devices;
pub mod output_mute;
pub mod preview;

pub use capture::{AudioCaptureHandle, AudioConfig, CaptureState};
pub use devices::{list_input_devices, InputDeviceInfo};

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
    async fn cancelled_startup_drops_pending_provider_and_releases_the_start_lock() {
        let operations = crate::voice_operation::VoiceOperations::default();
        let operation = operations.begin_if(|| true).unwrap();
        let start_lock = Arc::new(tokio::sync::Mutex::new(()));
        let guard = start_lock.clone().lock_owned().await;
        let provider_dropped = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let provider_started = Arc::new(Notify::new());
        let observed_drop = Arc::clone(&provider_dropped);
        let observed_start = Arc::clone(&provider_started);
        let cancellation = operation.cancel_receiver();
        let startup = tokio::spawn(async move {
            let _guard = guard;
            crate::retry_audio::cancellable_retry(cancellation, async {
                await_recording_startup(async { Ok::<_, &'static str>(42) }, async {
                    let _cleanup = crate::voice_operation::OnDrop::new(move || {
                        observed_drop.store(true, std::sync::atomic::Ordering::SeqCst);
                    });
                    observed_start.notify_one();
                    std::future::pending::<Result<(), &'static str>>().await
                })
                .await
            })
            .await
        });
        tokio::time::timeout(Duration::from_secs(2), provider_started.notified())
            .await
            .unwrap();
        assert!(start_lock.try_lock().is_err());
        operations.abort_current(|| {});
        assert_eq!(
            tokio::time::timeout(Duration::from_secs(2), startup)
                .await
                .unwrap()
                .unwrap(),
            None
        );
        assert!(provider_dropped.load(std::sync::atomic::Ordering::SeqCst));
        assert!(start_lock.try_lock().is_ok());
    }

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
