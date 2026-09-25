use anyhow::Result;
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use cpal::Sample;
use std::sync::{Arc, Mutex};
use tokio::sync::{mpsc, oneshot};

struct CaptureStartupNotifier {
    sender: Option<
        oneshot::Sender<std::result::Result<crate::recording_deadline::CaptureReadyAt, String>>,
    >,
}

struct CaptureStartupWaiter {
    receiver:
        oneshot::Receiver<std::result::Result<crate::recording_deadline::CaptureReadyAt, String>>,
}

fn capture_startup_channel() -> (CaptureStartupNotifier, CaptureStartupWaiter) {
    let (sender, receiver) = oneshot::channel();
    (
        CaptureStartupNotifier {
            sender: Some(sender),
        },
        CaptureStartupWaiter { receiver },
    )
}

impl CaptureStartupNotifier {
    fn ready(&mut self, ready_at: crate::recording_deadline::CaptureReadyAt) {
        if let Some(sender) = self.sender.take() {
            let _ = sender.send(Ok(ready_at));
        }
    }

    fn failed(&mut self, message: String) {
        if let Some(sender) = self.sender.take() {
            let _ = sender.send(Err(message));
        }
    }
}

impl CaptureStartupWaiter {
    async fn wait(self) -> std::result::Result<crate::recording_deadline::CaptureReadyAt, String> {
        self.receiver.await.unwrap_or_else(|_| {
            Err("Audio capture thread ended before reporting readiness".to_string())
        })
    }
}

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum CaptureState {
    Idle,
    Starting,
    Recording,
}

fn initial_capture_state() -> CaptureState {
    CaptureState::Starting
}

#[derive(Debug, Clone)]
pub struct AudioConfig {
    pub sample_rate: u32,
    pub channels: u16,
    pub chunk_duration_ms: u32,
}

impl Default for AudioConfig {
    fn default() -> Self {
        Self {
            sample_rate: 16000,
            channels: 1,
            chunk_duration_ms: 20,
        }
    }
}

/// Maximum audio buffer size in samples before we stop accumulating.
/// ~24 MB of i16 samples ≈ 12.5 min at 16kHz mono, matching the STT provider limits.
const MAX_BUFFER_SAMPLES: usize = 12 * 1024 * 1024;
const AUDIO_CHANNEL_BUFFER_DURATION_MS: u32 = 60_000;

fn audio_channel_capacity(config: &AudioConfig) -> usize {
    let chunk_duration_ms = config.chunk_duration_ms.max(1);
    AUDIO_CHANNEL_BUFFER_DURATION_MS.div_ceil(chunk_duration_ms) as usize
}

/// Handle to control audio capture running on a dedicated thread.
/// This is Send + Sync safe because it only holds channels and atomic state.
pub struct AudioCaptureHandle {
    stop_tx: Option<std::sync::mpsc::Sender<()>>,
    startup_waiter: Option<CaptureStartupWaiter>,
    volume: Arc<Mutex<f32>>,
    state: Arc<Mutex<CaptureState>>,
}

impl AudioCaptureHandle {
    /// Start audio capture on a dedicated thread. Returns a handle and a receiver for audio chunks.
    pub fn start(config: AudioConfig) -> Result<(Self, mpsc::Receiver<Vec<u8>>)> {
        let (audio_tx, audio_rx) = mpsc::channel::<Vec<u8>>(audio_channel_capacity(&config));
        let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
        let volume = Arc::new(Mutex::new(0.0f32));
        let state = Arc::new(Mutex::new(initial_capture_state()));
        let (mut startup_notifier, startup_waiter) = capture_startup_channel();

        let vol_clone = volume.clone();
        let state_clone = state.clone();
        let failed_state = state.clone();

        // Audio capture must run on a dedicated OS thread because cpal::Stream is !Send
        std::thread::spawn(move || {
            if let Err(e) = run_capture(
                config,
                audio_tx,
                stop_rx,
                vol_clone,
                state_clone,
                &mut startup_notifier,
            ) {
                *failed_state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = CaptureState::Idle;
                startup_notifier.failed(e.to_string());
                tracing::error!("Audio capture thread error: {}", e);
            }
        });

        Ok((
            Self {
                stop_tx: Some(stop_tx),
                startup_waiter: Some(startup_waiter),
                volume,
                state,
            },
            audio_rx,
        ))
    }

    /// Wait until the platform backend has opened the input stream and
    /// `play()` has succeeded. The CPAL boundary is shared by CoreAudio,
    /// WASAPI, ALSA and PipeWire, so callers do not need platform delays.
    pub async fn wait_until_ready(&mut self) -> Result<crate::recording_deadline::CaptureReadyAt> {
        let waiter = self
            .startup_waiter
            .take()
            .ok_or_else(|| anyhow::anyhow!("Audio capture readiness was already consumed"))?;
        waiter.wait().await.map_err(anyhow::Error::msg)
    }

    pub fn stop(&mut self) {
        // Signal the capture thread to stop
        self.stop_tx = None;
        *self.volume.lock().unwrap_or_else(|e| e.into_inner()) = 0.0;
        *self.state.lock().unwrap_or_else(|e| e.into_inner()) = CaptureState::Idle;
    }

    pub fn get_volume(&self) -> f32 {
        *self.volume.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn state(&self) -> CaptureState {
        *self.state.lock().unwrap_or_else(|e| e.into_inner())
    }
}

/// Downsample audio from `from_rate` to `to_rate` (simple linear interpolation, mono).
fn downsample(samples: &[f32], from_rate: u32, to_rate: u32) -> Vec<f32> {
    if from_rate == to_rate {
        return samples.to_vec();
    }
    let ratio = from_rate as f64 / to_rate as f64;
    let out_len = (samples.len() as f64 / ratio) as usize;
    let mut out = Vec::with_capacity(out_len);
    for i in 0..out_len {
        let src_idx = i as f64 * ratio;
        let idx = src_idx as usize;
        let frac = src_idx - idx as f64;
        let s = if idx + 1 < samples.len() {
            samples[idx] as f64 * (1.0 - frac) + samples[idx + 1] as f64 * frac
        } else {
            samples[idx.min(samples.len() - 1)] as f64
        };
        out.push(s as f32);
    }
    out
}

/// Mix multi-channel audio down to mono by averaging channels.
fn to_mono(samples: &[f32], channels: u16) -> Vec<f32> {
    if channels <= 1 {
        return samples.to_vec();
    }
    let ch = channels as usize;
    samples
        .chunks(ch)
        .map(|frame| frame.iter().sum::<f32>() / ch as f32)
        .collect()
}

fn samples_to_f32<T>(samples: &[T]) -> Vec<f32>
where
    T: cpal::SizedSample,
    f32: cpal::FromSample<T>,
{
    samples.iter().copied().map(f32::from_sample).collect()
}

struct InputProcessingContext {
    device_sample_rate: u32,
    device_channels: u16,
    target_rate: u32,
    target_channels: u16,
    samples_per_chunk: usize,
    sender: mpsc::Sender<Vec<u8>>,
    volume: Arc<Mutex<f32>>,
    buffer: Arc<Mutex<Vec<i16>>>,
}

fn normalized_rms(data: &[f32]) -> f32 {
    if data.is_empty() {
        return 0.0;
    }

    let rms = (data.iter().map(|sample| sample * sample).sum::<f32>() / data.len() as f32).sqrt();
    if rms.is_finite() {
        rms.min(1.0)
    } else {
        0.0
    }
}

fn process_input_samples(data: &[f32], context: &InputProcessingContext) {
    // Calculate RMS volume from raw data
    if let Ok(mut volume) = context.volume.lock() {
        *volume = normalized_rms(data);
    }
    if data.is_empty() {
        return;
    }

    // Convert to mono if needed
    let mono = if context.device_channels > context.target_channels {
        to_mono(data, context.device_channels)
    } else {
        data.to_vec()
    };

    // Downsample to target rate if needed
    let resampled = if context.device_sample_rate != context.target_rate {
        downsample(&mono, context.device_sample_rate, context.target_rate)
    } else {
        mono
    };

    // Convert f32 to i16 PCM and buffer
    let mut buffer = context
        .buffer
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    for &sample in &resampled {
        if buffer.len() >= MAX_BUFFER_SAMPLES {
            break;
        }
        let sample = (sample * 32767.0).clamp(-32768.0, 32767.0) as i16;
        buffer.push(sample);
    }

    // Send complete chunks
    while buffer.len() >= context.samples_per_chunk {
        let chunk: Vec<i16> = buffer.drain(..context.samples_per_chunk).collect();
        let bytes: Vec<u8> = chunk
            .iter()
            .flat_map(|sample| sample.to_le_bytes())
            .collect();
        let _ = context.sender.try_send(bytes);
    }
}

fn build_input_stream_for_sample<T>(
    device: &cpal::Device,
    stream_config: &cpal::StreamConfig,
    context: InputProcessingContext,
) -> std::result::Result<cpal::Stream, cpal::BuildStreamError>
where
    T: cpal::SizedSample,
    f32: cpal::FromSample<T>,
{
    device.build_input_stream(
        stream_config,
        move |data: &[T], _: &cpal::InputCallbackInfo| {
            let samples = samples_to_f32(data);
            process_input_samples(&samples, &context);
        },
        |error| {
            tracing::error!("Audio capture error: {}", error);
        },
        None,
    )
}

fn run_capture(
    config: AudioConfig,
    sender: mpsc::Sender<Vec<u8>>,
    stop_rx: std::sync::mpsc::Receiver<()>,
    volume: Arc<Mutex<f32>>,
    state: Arc<Mutex<CaptureState>>,
    startup_notifier: &mut CaptureStartupNotifier,
) -> Result<()> {
    let host = cpal::default_host();
    let device = host
        .default_input_device()
        .ok_or_else(|| anyhow::anyhow!("No input device available"))?;

    let device_description = device
        .description()
        .map(|description| description.name().to_string())
        .unwrap_or_else(|_| "Default microphone".to_string());
    tracing::info!("Using input device: {}", device_description);

    // Use the device's default config instead of forcing 16kHz mono
    let default_config = device.default_input_config()?;
    let device_sample_rate = default_config.sample_rate();
    let device_channels = default_config.channels();
    let device_sample_format = default_config.sample_format();

    tracing::info!(
        "Device default config: {}Hz, {} channels, format: {:?}",
        device_sample_rate,
        device_channels,
        device_sample_format
    );

    let stream_config = cpal::StreamConfig {
        channels: device_channels,
        sample_rate: device_sample_rate,
        buffer_size: cpal::BufferSize::Default,
    };

    let target_rate = config.sample_rate;
    let target_channels = config.channels;
    let samples_per_chunk = (target_rate * config.chunk_duration_ms / 1000) as usize;
    let buffer: Arc<Mutex<Vec<i16>>> = Arc::new(Mutex::new(Vec::with_capacity(samples_per_chunk)));

    let processing_context = InputProcessingContext {
        device_sample_rate,
        device_channels,
        target_rate,
        target_channels,
        samples_per_chunk,
        sender,
        volume,
        buffer,
    };

    let stream = match device_sample_format {
        cpal::SampleFormat::F32 => {
            build_input_stream_for_sample::<f32>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::F64 => {
            build_input_stream_for_sample::<f64>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::I8 => {
            build_input_stream_for_sample::<i8>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::I16 => {
            build_input_stream_for_sample::<i16>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::I24 => {
            build_input_stream_for_sample::<cpal::I24>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::I32 => {
            build_input_stream_for_sample::<i32>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::I64 => {
            build_input_stream_for_sample::<i64>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::U8 => {
            build_input_stream_for_sample::<u8>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::U16 => {
            build_input_stream_for_sample::<u16>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::U24 => {
            build_input_stream_for_sample::<cpal::U24>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::U32 => {
            build_input_stream_for_sample::<u32>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::U64 => {
            build_input_stream_for_sample::<u64>(&device, &stream_config, processing_context)
        }
        cpal::SampleFormat::DsdU8 | cpal::SampleFormat::DsdU16 | cpal::SampleFormat::DsdU32 => {
            return Err(anyhow::anyhow!(
                "Unsupported DSD input sample format: {device_sample_format}"
            ));
        }
        _ => {
            return Err(anyhow::anyhow!(
                "Unsupported input sample format: {device_sample_format}"
            ));
        }
    }?;

    stream.play()?;
    let capture_ready_at = crate::recording_deadline::CaptureReadyAt::now();
    *state.lock().unwrap_or_else(|e| e.into_inner()) = CaptureState::Recording;
    startup_notifier.ready(capture_ready_at);
    tracing::info!(
        "Audio capture started (device: {}Hz {}ch -> target: {}Hz {}ch)",
        device_sample_rate,
        device_channels,
        target_rate,
        target_channels
    );

    // Block until stop signal (sender dropped)
    let _ = stop_rx.recv();

    // Stream is dropped here, stopping capture
    drop(stream);
    *state.lock().unwrap_or_else(|e| e.into_inner()) = CaptureState::Idle;
    tracing::info!("Audio capture stopped");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn capture_does_not_report_recording_before_the_backend_is_ready() {
        assert_eq!(initial_capture_state(), CaptureState::Starting);
    }

    #[test]
    fn audio_queue_preserves_a_minute_while_the_provider_connects() {
        assert_eq!(audio_channel_capacity(&AudioConfig::default()), 3_000);
    }

    #[test]
    fn converts_f32_input_samples_without_changing_values() {
        assert_eq!(
            samples_to_f32(&[-1.0_f32, 0.0, 0.5, 1.0]),
            vec![-1.0, 0.0, 0.5, 1.0]
        );
    }

    #[test]
    fn converts_i16_input_samples_to_normalized_f32() {
        assert_eq!(
            samples_to_f32(&[i16::MIN, 0, i16::MAX]),
            vec![-1.0, 0.0, i16::MAX as f32 / 32768.0]
        );
    }

    #[test]
    fn converts_u16_input_samples_around_unsigned_equilibrium() {
        assert_eq!(
            samples_to_f32(&[u16::MIN, 32768, u16::MAX]),
            vec![-1.0, 0.0, (u16::MAX as f32 - 32768.0) / 32768.0]
        );
    }

    #[test]
    fn empty_input_reports_zero_volume() {
        assert_eq!(normalized_rms(&[]), 0.0);
    }

    #[test]
    fn non_finite_input_reports_zero_volume() {
        assert_eq!(normalized_rms(&[f32::NAN]), 0.0);
        assert_eq!(normalized_rms(&[f32::INFINITY]), 0.0);
    }

    #[tokio::test]
    async fn capture_startup_waits_for_the_backend_ready_signal() {
        let (_notifier, waiter) = capture_startup_channel();

        assert!(
            tokio::time::timeout(Duration::from_millis(20), waiter.wait())
                .await
                .is_err(),
            "capture startup completed before the backend reported readiness"
        );
    }

    #[tokio::test]
    async fn capture_startup_completes_after_the_backend_is_ready() {
        let (mut notifier, waiter) = capture_startup_channel();
        let ready_at = crate::recording_deadline::CaptureReadyAt::now();
        notifier.ready(ready_at);

        let observed = waiter.wait().await.unwrap();
        assert_eq!(observed.unix_millis, ready_at.unix_millis);
        assert_eq!(observed.monotonic, ready_at.monotonic);
    }

    #[tokio::test]
    async fn capture_startup_propagates_backend_failure() {
        let (mut notifier, waiter) = capture_startup_channel();
        notifier.failed("input device unavailable".to_string());

        assert_eq!(
            waiter.wait().await,
            Err("input device unavailable".to_string())
        );
    }
}
