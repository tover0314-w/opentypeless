use anyhow::Result;
use cpal::traits::{DeviceTrait, HostTrait, StreamTrait};
use std::sync::{Arc, Mutex};
use tokio::sync::mpsc;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum CaptureState {
    Idle,
    Recording,
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

/// Handle to control audio capture running on a dedicated thread.
/// This is Send + Sync safe because it only holds channels and atomic state.
pub struct AudioCaptureHandle {
    stop_tx: Option<std::sync::mpsc::Sender<()>>,
    volume: Arc<Mutex<f32>>,
    state: Arc<Mutex<CaptureState>>,
}

impl AudioCaptureHandle {
    /// Start audio capture on a dedicated thread. Returns a handle and a receiver for audio chunks.
    pub fn start(config: AudioConfig) -> Result<(Self, mpsc::Receiver<Vec<u8>>)> {
        let (audio_tx, audio_rx) = mpsc::channel::<Vec<u8>>(200);
        let (stop_tx, stop_rx) = std::sync::mpsc::channel::<()>();
        let volume = Arc::new(Mutex::new(0.0f32));
        let state = Arc::new(Mutex::new(CaptureState::Recording));

        let vol_clone = volume.clone();
        let state_clone = state.clone();

        // Audio capture must run on a dedicated OS thread because cpal::Stream is !Send
        std::thread::spawn(move || {
            if let Err(e) = run_capture(config, audio_tx, stop_rx, vol_clone, state_clone) {
                tracing::error!("Audio capture thread error: {}", e);
            }
        });

        Ok((
            Self {
                stop_tx: Some(stop_tx),
                volume,
                state,
            },
            audio_rx,
        ))
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

fn run_capture(
    config: AudioConfig,
    sender: mpsc::Sender<Vec<u8>>,
    stop_rx: std::sync::mpsc::Receiver<()>,
    volume: Arc<Mutex<f32>>,
    state: Arc<Mutex<CaptureState>>,
) -> Result<()> {
    let host = cpal::default_host();
    let device = host
        .default_input_device()
        .ok_or_else(|| anyhow::anyhow!("No input device available"))?;

    let device_description = device
        .description()
        .map(|description| description.to_string())
        .unwrap_or_else(|_| "unknown input device".to_string());
    tracing::info!("Using input device: {}", device_description);

    // Use the device's default config instead of forcing 16kHz mono
    let default_config = device.default_input_config()?;
    let device_sample_rate = default_config.sample_rate();
    let device_channels = default_config.channels();

    tracing::info!(
        "Device default config: {}Hz, {} channels, format: {:?}",
        device_sample_rate,
        device_channels,
        default_config.sample_format()
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

    let stream = device.build_input_stream(
        &stream_config,
        move |data: &[f32], _: &cpal::InputCallbackInfo| {
            // Calculate RMS volume from raw data
            let rms = (data.iter().map(|s| s * s).sum::<f32>() / data.len() as f32).sqrt();
            if let Ok(mut v) = volume.lock() {
                *v = rms.min(1.0);
            }

            // Convert to mono if needed
            let mono = if device_channels > target_channels {
                to_mono(data, device_channels)
            } else {
                data.to_vec()
            };

            // Downsample to target rate if needed
            let resampled = if device_sample_rate != target_rate {
                downsample(&mono, device_sample_rate, target_rate)
            } else {
                mono
            };

            // Convert f32 to i16 PCM and buffer
            let mut buf = buffer.lock().unwrap_or_else(|e| e.into_inner());
            for &sample in &resampled {
                if buf.len() >= MAX_BUFFER_SAMPLES {
                    break;
                }
                let s = (sample * 32767.0).clamp(-32768.0, 32767.0) as i16;
                buf.push(s);
            }

            // Send complete chunks
            while buf.len() >= samples_per_chunk {
                let chunk: Vec<i16> = buf.drain(..samples_per_chunk).collect();
                let bytes: Vec<u8> = chunk.iter().flat_map(|s| s.to_le_bytes()).collect();
                let _ = sender.try_send(bytes);
            }
        },
        |err| {
            tracing::error!("Audio capture error: {}", err);
        },
        None,
    )?;

    stream.play()?;
    *state.lock().unwrap_or_else(|e| e.into_inner()) = CaptureState::Recording;
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
