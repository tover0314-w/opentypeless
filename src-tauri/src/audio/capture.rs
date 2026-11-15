use anyhow::Result;
use cpal::traits::{DeviceTrait, StreamTrait};
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

fn start_capture_stream(
    state: &Mutex<CaptureState>,
    play: impl FnOnce() -> Result<()>,
) -> Result<()> {
    let mut current = state.lock().unwrap_or_else(|error| error.into_inner());
    if *current != CaptureState::Starting {
        return Err(anyhow::anyhow!("Audio capture startup cancelled"));
    }
    play()?;
    *current = CaptureState::Recording;
    Ok(())
}

#[derive(Debug, Clone)]
pub struct AudioConfig {
    pub sample_rate: u32,
    pub channels: u16,
    pub chunk_duration_ms: u32,
    /// Input device name chosen in settings; `None` records from the system default.
    pub device_name: Option<String>,
}

impl Default for AudioConfig {
    fn default() -> Self {
        Self {
            sample_rate: 16000,
            channels: 1,
            chunk_duration_ms: 20,
            device_name: None,
        }
    }
}

impl AudioConfig {
    /// Default capture format recording from the microphone selected in settings.
    pub fn for_device(microphone_device: &str) -> Self {
        let name = microphone_device.trim();
        Self {
            device_name: (!name.is_empty()).then(|| name.to_string()),
            ..Self::default()
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
    device_name: Arc<Mutex<Option<String>>>,
    stop_tx: Option<std::sync::mpsc::Sender<()>>,
    startup_waiter: Option<CaptureStartupWaiter>,
    volume: Arc<Mutex<f32>>,
    status: CaptureStatus,
}

#[derive(Clone)]
pub(crate) struct CaptureStatus {
    state: Arc<Mutex<CaptureState>>,
    failure: Arc<Mutex<Option<String>>>,
}

impl CaptureStatus {
    fn new() -> Self {
        Self {
            state: Arc::new(Mutex::new(initial_capture_state())),
            failure: Arc::new(Mutex::new(None)),
        }
    }

    pub(crate) fn error(&self) -> Option<crate::error::AppError> {
        self.failure
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .map(|message| {
                crate::error::AppError::Config(format!("Audio capture failed: {message}"))
            })
    }
}

#[derive(Clone)]
pub(crate) struct CaptureMeter {
    volume: Arc<Mutex<f32>>,
    state: Arc<Mutex<CaptureState>>,
}

impl CaptureMeter {
    pub(crate) fn level(&self) -> Option<f32> {
        if *self.state.lock().unwrap_or_else(|error| error.into_inner()) != CaptureState::Recording
        {
            return None;
        }
        Some(
            *self
                .volume
                .lock()
                .unwrap_or_else(|error| error.into_inner()),
        )
    }
}

impl AudioCaptureHandle {
    #[cfg(test)]
    pub(crate) fn synthetic_recording() -> (Self, std::sync::mpsc::Receiver<()>) {
        let (stop_tx, stop_rx) = std::sync::mpsc::channel();
        (
            Self {
                device_name: Arc::new(Mutex::new(None)),
                stop_tx: Some(stop_tx),
                startup_waiter: None,
                volume: Arc::new(Mutex::new(0.5)),
                status: CaptureStatus {
                    state: Arc::new(Mutex::new(CaptureState::Recording)),
                    failure: Arc::new(Mutex::new(None)),
                },
            },
            stop_rx,
        )
    }

    /// Start audio capture on a dedicated thread. Returns a handle and a receiver for audio chunks.
    pub fn start(config: AudioConfig) -> Result<(Self, mpsc::Receiver<Vec<u8>>)> {
        let (audio_tx, audio_rx) = mpsc::channel::<Vec<u8>>(audio_channel_capacity(&config));
        let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
        let volume = Arc::new(Mutex::new(0.0f32));
        let status = CaptureStatus::new();
        let (mut startup_notifier, startup_waiter) = capture_startup_channel();

        let vol_clone = volume.clone();
        let status_clone = status.clone();
        let failed_status = status.clone();
        let failed_volume = volume.clone();
        let device_name = Arc::new(Mutex::new(None));
        let captured_device_name = device_name.clone();

        // Audio capture must run on a dedicated OS thread because cpal::Stream is !Send
        std::thread::spawn(move || {
            if let Err(e) = run_capture(
                config,
                audio_tx,
                stop_rx,
                vol_clone,
                status_clone,
                &mut startup_notifier,
                captured_device_name,
            ) {
                mark_capture_failed(&failed_status, &failed_volume, e.to_string());
                startup_notifier.failed(e.to_string());
                tracing::error!("Audio capture thread error: {}", e);
            }
        });

        Ok((
            Self {
                device_name,
                stop_tx: Some(stop_tx),
                startup_waiter: Some(startup_waiter),
                volume,
                status,
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
        *self.status.state.lock().unwrap_or_else(|e| e.into_inner()) = CaptureState::Idle;
    }

    pub fn get_volume(&self) -> f32 {
        *self.volume.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub fn device_name(&self) -> Option<String> {
        self.device_name
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone()
    }

    pub(crate) fn meter(&self) -> CaptureMeter {
        CaptureMeter {
            volume: self.volume.clone(),
            state: self.status.state.clone(),
        }
    }

    pub fn state(&self) -> CaptureState {
        *self.status.state.lock().unwrap_or_else(|e| e.into_inner())
    }

    pub(crate) fn status(&self) -> CaptureStatus {
        self.status.clone()
    }
}

impl Drop for AudioCaptureHandle {
    fn drop(&mut self) {
        self.stop();
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
    status: CaptureStatus,
    device_sample_rate: u32,
    device_channels: u16,
    target_rate: u32,
    target_channels: u16,
    samples_per_chunk: usize,
    sender: mpsc::Sender<Vec<u8>>,
    volume: Arc<Mutex<f32>>,
    buffer: Arc<Mutex<Vec<i16>>>,
}

fn mark_capture_failed(status: &CaptureStatus, volume: &Mutex<f32>, message: String) {
    let mut state = status
        .state
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if *state == CaptureState::Idle {
        return;
    }
    let mut failure = status
        .failure
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if failure.is_none() {
        *failure = Some(message);
    }
    *state = CaptureState::Idle;
    *volume.lock().unwrap_or_else(|error| error.into_inner()) = 0.0;
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
    if *context
        .status
        .state
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        == CaptureState::Idle
    {
        return;
    }
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

    send_complete_chunks(&mut buffer, context.samples_per_chunk, &context.sender);
}

fn send_complete_chunks(
    buffer: &mut Vec<i16>,
    samples_per_chunk: usize,
    sender: &mpsc::Sender<Vec<u8>>,
) {
    let chunk_size = samples_per_chunk.max(1);
    while buffer.len() >= chunk_size {
        let bytes = buffer[..chunk_size]
            .iter()
            .flat_map(|sample| sample.to_le_bytes())
            .collect();
        match sender.try_send(bytes) {
            Ok(()) => {
                buffer.drain(..chunk_size);
            }
            Err(mpsc::error::TrySendError::Full(_)) => break,
            Err(mpsc::error::TrySendError::Closed(_)) => {
                buffer.clear();
                break;
            }
        }
    }
}

fn flush_remaining_samples(
    buffer: &mut Vec<i16>,
    samples_per_chunk: usize,
    sender: &mpsc::Sender<Vec<u8>>,
) {
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
    while !buffer.is_empty() {
        let chunk_size = buffer.len().min(samples_per_chunk.max(1));
        let bytes = buffer[..chunk_size]
            .iter()
            .flat_map(|sample| sample.to_le_bytes())
            .collect();
        match sender.try_send(bytes) {
            Ok(()) => {
                buffer.drain(..chunk_size);
            }
            Err(mpsc::error::TrySendError::Closed(_)) => {
                buffer.clear();
                break;
            }
            Err(mpsc::error::TrySendError::Full(_)) => {
                if std::time::Instant::now() >= deadline {
                    tracing::warn!(
                        "Audio tail could not be delivered before shutdown: {} samples pending",
                        buffer.len()
                    );
                    buffer.clear();
                    break;
                }
                std::thread::sleep(std::time::Duration::from_millis(5));
            }
        }
    }
}

fn build_input_stream_for_sample<T>(
    device: &cpal::Device,
    stream_config: &cpal::StreamConfig,
    context: InputProcessingContext,
) -> std::result::Result<cpal::Stream, cpal::Error>
where
    T: cpal::SizedSample,
    f32: cpal::FromSample<T>,
{
    let failure_status = context.status.clone();
    let failure_volume = context.volume.clone();
    device.build_input_stream(
        *stream_config,
        move |data: &[T], _: &cpal::InputCallbackInfo| {
            let samples = samples_to_f32(data);
            process_input_samples(&samples, &context);
        },
        move |error| {
            mark_capture_failed(&failure_status, &failure_volume, error.to_string());
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
    status: CaptureStatus,
    startup_notifier: &mut CaptureStartupNotifier,
    captured_device_name: Arc<Mutex<Option<String>>>,
) -> Result<()> {
    let (device, fell_back) = super::devices::select_input_device(config.device_name.as_deref())
        .ok_or_else(|| anyhow::anyhow!("No input device available"))?;
    if fell_back {
        tracing::info!("Selected microphone is unavailable; recording from the system default");
    }

    let device_description = device
        .description()
        .map(|description| description.name().to_string())
        .unwrap_or_else(|_| "Default microphone".to_string());
    tracing::info!("Using input device: {}", device_description);
    *captured_device_name
        .lock()
        .unwrap_or_else(|error| error.into_inner()) = device
        .description()
        .ok()
        .map(|description| description.name().to_string());

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
    let samples_per_chunk = ((target_rate * config.chunk_duration_ms / 1000) as usize).max(1);
    let buffer: Arc<Mutex<Vec<i16>>> = Arc::new(Mutex::new(Vec::with_capacity(samples_per_chunk)));
    let final_buffer = buffer.clone();
    let final_sender = sender.clone();

    let processing_context = InputProcessingContext {
        status: status.clone(),
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

    start_capture_stream(&status.state, || stream.play().map_err(anyhow::Error::from))?;
    let capture_ready_at = crate::recording_deadline::CaptureReadyAt::now();
    startup_notifier.ready(capture_ready_at);
    tracing::info!(
        "Audio capture started (device: {}Hz {}ch -> target: {}Hz {}ch)",
        device_sample_rate,
        device_channels,
        target_rate,
        target_channels
    );

    // Block until stop signal (sender dropped)
    loop {
        match stop_rx.recv_timeout(std::time::Duration::from_millis(50)) {
            Ok(()) | Err(std::sync::mpsc::RecvTimeoutError::Disconnected) => break,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout) => {
                if *status
                    .state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    == CaptureState::Idle
                {
                    break;
                }
            }
        }
    }

    // Stream is dropped here, stopping capture
    drop(stream);
    flush_remaining_samples(
        &mut final_buffer
            .lock()
            .unwrap_or_else(|error| error.into_inner()),
        samples_per_chunk,
        &final_sender,
    );
    *status.state.lock().unwrap_or_else(|e| e.into_inner()) = CaptureState::Idle;
    tracing::info!("Audio capture stopped");
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::time::Duration;

    #[test]
    fn capture_failure_survives_stop_and_is_bound_to_its_recording() {
        let (stop_tx, _) = std::sync::mpsc::channel();
        let mut capture = AudioCaptureHandle {
            device_name: Arc::new(Mutex::new(None)),
            stop_tx: Some(stop_tx),
            startup_waiter: None,
            volume: Arc::new(Mutex::new(0.5)),
            status: CaptureStatus::new(),
        };
        let status = capture.status();
        mark_capture_failed(&status, &capture.volume, "device disconnected".into());
        capture.stop();
        drop(capture);

        let error = status
            .error()
            .expect("capture failure must survive shutdown");
        assert_eq!(
            error.to_string(),
            "Config error: Audio capture failed: device disconnected"
        );
        assert!(CaptureStatus::new().error().is_none());
    }

    #[test]
    fn ordinary_capture_stop_does_not_become_a_failure() {
        let (stop_tx, _) = std::sync::mpsc::channel();
        let mut capture = AudioCaptureHandle {
            device_name: Arc::new(Mutex::new(None)),
            stop_tx: Some(stop_tx),
            startup_waiter: None,
            volume: Arc::new(Mutex::new(0.5)),
            status: CaptureStatus::new(),
        };
        let status = capture.status();
        capture.stop();
        mark_capture_failed(&status, &capture.volume, "stream teardown".into());
        assert!(status.error().is_none());
    }

    #[test]
    fn capture_error_disables_meter_and_clears_its_level() {
        let state = Arc::new(Mutex::new(CaptureState::Recording));
        let status = CaptureStatus {
            state: state.clone(),
            failure: Arc::new(Mutex::new(None)),
        };
        let volume = Arc::new(Mutex::new(0.5));
        let meter = CaptureMeter {
            state: state.clone(),
            volume: volume.clone(),
        };
        mark_capture_failed(&status, &volume, "backend failed".into());
        assert_eq!(meter.level(), None);
        assert_eq!(*volume.lock().unwrap(), 0.0);
    }

    #[test]
    fn stopped_startup_never_plays_or_returns_to_recording() {
        let state = Mutex::new(CaptureState::Idle);
        let mut played = false;
        let result = start_capture_stream(&state, || {
            played = true;
            Ok(())
        });
        assert!(result.is_err());
        assert!(!played);
        assert_eq!(*state.lock().unwrap(), CaptureState::Idle);
    }

    #[test]
    fn failed_stream_start_keeps_capture_out_of_recording() {
        let state = Mutex::new(CaptureState::Starting);
        assert!(start_capture_stream(&state, || Err(anyhow::anyhow!("backend failed"))).is_err());
        assert_eq!(*state.lock().unwrap(), CaptureState::Starting);
    }

    #[test]
    fn dropping_capture_stops_its_meter_and_disconnects_the_worker() {
        let (stop_tx, stop_rx) = std::sync::mpsc::channel();
        let capture = AudioCaptureHandle {
            device_name: Arc::new(Mutex::new(Some("USB".into()))),
            stop_tx: Some(stop_tx),
            startup_waiter: None,
            volume: Arc::new(Mutex::new(0.2)),
            status: CaptureStatus {
                state: Arc::new(Mutex::new(CaptureState::Recording)),
                failure: Arc::new(Mutex::new(None)),
            },
        };
        let meter = capture.meter();
        assert_eq!(meter.level(), Some(0.2));
        drop(capture);
        assert_eq!(meter.level(), None);
        assert_eq!(
            stop_rx.try_recv(),
            Err(std::sync::mpsc::TryRecvError::Disconnected)
        );
    }

    #[tokio::test]
    async fn full_queue_preserves_pending_audio_until_it_can_be_sent() {
        let (sender, mut receiver) = mpsc::channel(1);
        sender.try_send(vec![9, 0]).unwrap();
        let mut pending = vec![1_i16, 2];
        send_complete_chunks(&mut pending, 2, &sender);
        assert_eq!(pending, vec![1, 2]);
        assert_eq!(receiver.recv().await.unwrap(), vec![9, 0]);
        send_complete_chunks(&mut pending, 2, &sender);
        assert!(pending.is_empty());
        assert_eq!(receiver.recv().await.unwrap(), vec![1, 0, 2, 0]);
    }

    #[test]
    fn final_partial_chunk_is_sent_after_capture_stops() {
        let (sender, mut receiver) = mpsc::channel(2);
        let mut pending = vec![1_i16, 2, 3];
        flush_remaining_samples(&mut pending, 2, &sender);
        assert!(pending.is_empty());
        assert_eq!(receiver.try_recv().unwrap(), vec![1, 0, 2, 0]);
        assert_eq!(receiver.try_recv().unwrap(), vec![3, 0]);
    }

    #[test]
    fn shutdown_flush_waits_for_a_slow_consumer_and_preserves_order() {
        let (sender, mut receiver) = mpsc::channel(1);
        sender.try_send(vec![9, 0]).unwrap();
        let consumer = std::thread::spawn(move || {
            std::thread::sleep(Duration::from_millis(20));
            let mut received = Vec::new();
            while let Some(chunk) = receiver.blocking_recv() {
                received.push(chunk);
            }
            received
        });
        let mut pending = vec![1_i16, 2, 3];
        flush_remaining_samples(&mut pending, 2, &sender);
        drop(sender);
        assert!(pending.is_empty());
        assert_eq!(
            consumer.join().unwrap(),
            vec![vec![9, 0], vec![1, 0, 2, 0], vec![3, 0]]
        );
    }

    #[test]
    fn closed_receiver_does_not_block_shutdown() {
        let (sender, receiver) = mpsc::channel(1);
        drop(receiver);
        let mut pending = vec![1_i16, 2];
        send_complete_chunks(&mut pending, 2, &sender);
        assert!(pending.is_empty());
        pending.push(3);
        flush_remaining_samples(&mut pending, 2, &sender);
        assert!(pending.is_empty());
    }

    #[test]
    fn capture_does_not_report_recording_before_the_backend_is_ready() {
        assert_eq!(initial_capture_state(), CaptureState::Starting);
    }

    #[test]
    fn audio_config_for_device_treats_blank_names_as_system_default() {
        assert_eq!(AudioConfig::for_device("").device_name, None);
        assert_eq!(AudioConfig::for_device("   ").device_name, None);
        let usb = AudioConfig::for_device(" USB Mic ");
        assert_eq!(usb.device_name.as_deref(), Some("USB Mic"));
        assert_eq!(usb.sample_rate, 16000);
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
