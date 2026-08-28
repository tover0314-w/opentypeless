use std::fmt;
use std::io;
use std::sync::mpsc;
use std::thread;

use ogg::{PacketWriteEndInfo, PacketWriter};
use opusic_c::{Application, Bitrate, Channels, Encoder, SampleRate};

pub const MANAGED_OPUS_BITRATE: u32 = 48_000;
pub const MANAGED_OPUS_FRAME_MS: u32 = 20;
pub const MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME: usize = 320;
pub const MANAGED_OPUS_GRANULE_SAMPLES_PER_FRAME: u64 = 960;
pub const DEFAULT_WAV_SWITCH_BYTES: u64 = 3_500_000;
pub const DEFAULT_MAX_AUDIO_BYTES: u64 = 4_000_000;
const OGG_CONTAINER_HEADROOM_BYTES: u64 = 256;
const OGG_BOUNDED_BYTES_PER_FRAME: u64 = 128;

#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
pub struct ManagedAudioEncodingConfig {
    pub preferred_wav_max_bytes: u64,
    pub max_audio_bytes: u64,
    pub bitrate_bits_per_second: u32,
}

impl Default for ManagedAudioEncodingConfig {
    fn default() -> Self {
        Self {
            preferred_wav_max_bytes: DEFAULT_WAV_SWITCH_BYTES,
            max_audio_bytes: DEFAULT_MAX_AUDIO_BYTES,
            bitrate_bits_per_second: MANAGED_OPUS_BITRATE,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ManagedAudioPayloadKind {
    Wav,
    OggOpus,
}

pub fn select_managed_payload(
    wav_bytes: usize,
    config: ManagedAudioEncodingConfig,
) -> ManagedAudioPayloadKind {
    if (wav_bytes as u64) <= config.preferred_wav_max_bytes.min(config.max_audio_bytes) {
        ManagedAudioPayloadKind::Wav
    } else {
        ManagedAudioPayloadKind::OggOpus
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct EncodedManagedAudio {
    pub bytes: Vec<u8>,
    pub original_samples: u64,
    pub final_granule: u64,
    pub pre_skip: u16,
    pub packet_count: u32,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct ManagedAudioError {
    pub code: &'static str,
    pub message: String,
}

impl ManagedAudioError {
    fn new(code: &'static str, message: impl Into<String>) -> Self {
        Self {
            code,
            message: message.into(),
        }
    }
}

impl From<io::Error> for ManagedAudioError {
    fn from(error: io::Error) -> Self {
        Self::new("managed_audio_encode_failed", error.to_string())
    }
}

impl fmt::Display for ManagedAudioError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(&self.message)
    }
}

impl std::error::Error for ManagedAudioError {}

pub fn encode_pcm_to_ogg_opus(
    pcm: &[u8],
    stream_serial: u32,
    config: ManagedAudioEncodingConfig,
) -> Result<EncodedManagedAudio, ManagedAudioError> {
    let mut encoder = OggOpusEncoder::new(stream_serial, config)?;
    encoder.feed_pcm_bytes(pcm)?;
    encoder.finish()
}

enum WorkerCommand {
    Pcm(Vec<u8>),
    Finish(mpsc::Sender<Result<EncodedManagedAudio, ManagedAudioError>>),
}

pub struct ManagedAudioEncoderWorker {
    sender: Option<mpsc::SyncSender<WorkerCommand>>,
    join: Option<thread::JoinHandle<()>>,
}

impl ManagedAudioEncoderWorker {
    pub fn start(
        stream_serial: u32,
        config: ManagedAudioEncodingConfig,
    ) -> Result<Self, ManagedAudioError> {
        Self::start_inner(stream_serial, config, 64, None)
    }

    fn start_inner(
        stream_serial: u32,
        config: ManagedAudioEncodingConfig,
        queue_capacity: usize,
        start_gate: Option<mpsc::Receiver<()>>,
    ) -> Result<Self, ManagedAudioError> {
        let encoder = OggOpusEncoder::new(stream_serial, config)?;
        let (sender, receiver) = mpsc::sync_channel(queue_capacity);
        let join = thread::Builder::new()
            .name("managed-opus-encoder".to_string())
            .spawn(move || {
                if let Some(gate) = start_gate {
                    let _ = gate.recv();
                }
                run_encoder_worker(encoder, receiver);
            })
            .map_err(|error| {
                ManagedAudioError::new(
                    "managed_audio_encode_failed",
                    format!("failed to start managed Opus worker: {error}"),
                )
            })?;
        Ok(Self {
            sender: Some(sender),
            join: Some(join),
        })
    }

    pub fn try_send_pcm(&self, pcm: &[u8]) -> Result<(), ManagedAudioError> {
        if pcm.len() % 2 != 0 {
            return Err(ManagedAudioError::new(
                "managed_audio_invalid_pcm",
                "managed PCM input must contain aligned 16-bit samples",
            ));
        }
        let sender = self.sender.as_ref().ok_or_else(|| {
            ManagedAudioError::new(
                "managed_audio_encode_failed",
                "managed Opus worker is already closed",
            )
        })?;
        sender
            .try_send(WorkerCommand::Pcm(pcm.to_vec()))
            .map_err(|error| match error {
                mpsc::TrySendError::Full(_) => ManagedAudioError::new(
                    "managed_audio_queue_full",
                    "managed Opus worker could not keep up with audio capture",
                ),
                mpsc::TrySendError::Disconnected(_) => ManagedAudioError::new(
                    "managed_audio_encode_failed",
                    "managed Opus worker stopped unexpectedly",
                ),
            })
    }

    pub async fn finish(mut self) -> Result<EncodedManagedAudio, ManagedAudioError> {
        let sender = self.sender.take().ok_or_else(|| {
            ManagedAudioError::new(
                "managed_audio_encode_failed",
                "managed Opus worker is already closed",
            )
        })?;
        let join = self.join.take();
        tokio::task::spawn_blocking(move || {
            let (result_sender, result_receiver) = mpsc::channel();
            sender
                .send(WorkerCommand::Finish(result_sender))
                .map_err(|_| {
                    ManagedAudioError::new(
                        "managed_audio_encode_failed",
                        "managed Opus worker stopped before finalization",
                    )
                })?;
            let result = result_receiver.recv().map_err(|_| {
                ManagedAudioError::new(
                    "managed_audio_encode_failed",
                    "managed Opus worker did not return a final payload",
                )
            })?;
            join_worker(join)?;
            result
        })
        .await
        .map_err(|error| {
            ManagedAudioError::new(
                "managed_audio_encode_failed",
                format!("managed Opus finalization task failed: {error}"),
            )
        })?
    }

    pub async fn cancel(mut self) -> Result<(), ManagedAudioError> {
        self.sender.take();
        let join = self.join.take();
        tokio::task::spawn_blocking(move || join_worker(join))
            .await
            .map_err(|error| {
                ManagedAudioError::new(
                    "managed_audio_encode_failed",
                    format!("managed Opus cancellation task failed: {error}"),
                )
            })?
    }
}

fn run_encoder_worker(mut encoder: OggOpusEncoder, receiver: mpsc::Receiver<WorkerCommand>) {
    let mut terminal_error = None;
    while let Ok(command) = receiver.recv() {
        match command {
            WorkerCommand::Pcm(pcm) => {
                if terminal_error.is_none() {
                    terminal_error = encoder.feed_pcm_bytes(&pcm).err();
                }
            }
            WorkerCommand::Finish(result_sender) => {
                let result = match terminal_error {
                    Some(error) => Err(error),
                    None => encoder.finish(),
                };
                let _ = result_sender.send(result);
                return;
            }
        }
    }
}

fn join_worker(join: Option<thread::JoinHandle<()>>) -> Result<(), ManagedAudioError> {
    match join {
        Some(join) => join.join().map_err(|_| {
            ManagedAudioError::new(
                "managed_audio_encode_failed",
                "managed Opus worker panicked",
            )
        }),
        None => Ok(()),
    }
}

struct OggOpusEncoder {
    encoder: Encoder,
    writer: PacketWriter<'static, Vec<u8>>,
    stream_serial: u32,
    config: ManagedAudioEncodingConfig,
    pending_samples: Vec<u16>,
    held_packet: Option<(Vec<u8>, u64)>,
    original_samples: u64,
    pre_skip: u16,
    packet_count: u32,
    max_audio_frames: u64,
}

impl OggOpusEncoder {
    fn new(
        stream_serial: u32,
        config: ManagedAudioEncodingConfig,
    ) -> Result<Self, ManagedAudioError> {
        if config.bitrate_bits_per_second != MANAGED_OPUS_BITRATE {
            return Err(ManagedAudioError::new(
                "managed_audio_unsupported_config",
                "managed Opus bitrate must be 48 kbit/s",
            ));
        }
        let max_audio_frames = config
            .max_audio_bytes
            .saturating_sub(OGG_CONTAINER_HEADROOM_BYTES)
            / OGG_BOUNDED_BYTES_PER_FRAME;
        if max_audio_frames == 0 {
            return Err(ManagedAudioError::new(
                "managed_audio_too_large",
                "managed Ogg/Opus byte cap is too small for a valid audio stream",
            ));
        }

        let mut encoder = Encoder::new(Channels::Mono, SampleRate::Hz16000, Application::Voip)
            .map_err(opus_error)?;
        encoder
            .set_bitrate(Bitrate::Value(config.bitrate_bits_per_second))
            .map_err(opus_error)?;
        encoder.set_vbr(false).map_err(opus_error)?;
        encoder.set_complexity(5).map_err(opus_error)?;
        let look_ahead = encoder.get_look_ahead().map_err(opus_error)?;
        let pre_skip = look_ahead
            .checked_mul(3)
            .and_then(|samples| u16::try_from(samples).ok())
            .ok_or_else(|| {
                ManagedAudioError::new(
                    "managed_audio_encode_failed",
                    "Opus look-ahead does not fit the Ogg pre-skip field",
                )
            })?;

        let mut writer = PacketWriter::new(Vec::new());
        writer.write_packet(
            opus_head(pre_skip),
            stream_serial,
            PacketWriteEndInfo::EndPage,
            0,
        )?;
        writer.write_packet(opus_tags(), stream_serial, PacketWriteEndInfo::EndPage, 0)?;

        Ok(Self {
            encoder,
            writer,
            stream_serial,
            config,
            pending_samples: Vec::with_capacity(MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME * 2),
            held_packet: None,
            original_samples: 0,
            pre_skip,
            packet_count: 0,
            max_audio_frames,
        })
    }

    fn feed_pcm_bytes(&mut self, pcm: &[u8]) -> Result<(), ManagedAudioError> {
        if pcm.len() % 2 != 0 {
            return Err(ManagedAudioError::new(
                "managed_audio_invalid_pcm",
                "managed PCM input must contain aligned 16-bit samples",
            ));
        }
        for bytes in pcm.chunks_exact(2) {
            if self.pending_samples.is_empty()
                && u64::from(self.packet_count) >= self.max_audio_frames
            {
                // Preserve a complete, decodable prefix instead of corrupting
                // the Ogg tail when an unexpected negotiated byte cap is hit.
                break;
            }
            self.pending_samples
                .push(u16::from_le_bytes([bytes[0], bytes[1]]));
            self.original_samples = self.original_samples.checked_add(1).ok_or_else(|| {
                ManagedAudioError::new(
                    "managed_audio_invalid_pcm",
                    "managed PCM sample count overflowed",
                )
            })?;

            if self.pending_samples.len() == MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME {
                let mut frame = [0u16; MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME];
                frame.copy_from_slice(&self.pending_samples);
                self.pending_samples.clear();
                self.encode_frame(&frame)?;
            }
        }
        Ok(())
    }

    fn encode_frame(
        &mut self,
        frame: &[u16; MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME],
    ) -> Result<(), ManagedAudioError> {
        let mut output = [0u8; 1_275];
        let written = self
            .encoder
            .encode_to_slice(frame, &mut output)
            .map_err(opus_error)?;
        self.packet_count = self.packet_count.checked_add(1).ok_or_else(|| {
            ManagedAudioError::new(
                "managed_audio_encode_failed",
                "managed Opus packet count overflowed",
            )
        })?;
        let packet_granule = u64::from(self.pre_skip)
            .saturating_add(u64::from(self.packet_count) * MANAGED_OPUS_GRANULE_SAMPLES_PER_FRAME);

        if let Some((packet, granule)) = self.held_packet.take() {
            self.writer.write_packet(
                packet,
                self.stream_serial,
                PacketWriteEndInfo::NormalPacket,
                granule,
            )?;
            self.ensure_size_limit()?;
        }
        self.held_packet = Some((output[..written].to_vec(), packet_granule));
        Ok(())
    }

    fn ensure_size_limit(&self) -> Result<(), ManagedAudioError> {
        if self.writer.inner().len() as u64 > self.config.max_audio_bytes {
            return Err(ManagedAudioError::new(
                "managed_audio_too_large",
                "managed Ogg/Opus payload exceeds the negotiated audio byte cap",
            ));
        }
        Ok(())
    }

    fn finish(mut self) -> Result<EncodedManagedAudio, ManagedAudioError> {
        if self.original_samples == 0 {
            return Err(ManagedAudioError::new(
                "managed_audio_invalid_pcm",
                "managed PCM input is empty",
            ));
        }
        if !self.pending_samples.is_empty() {
            let mut final_frame = [0u16; MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME];
            final_frame[..self.pending_samples.len()].copy_from_slice(&self.pending_samples);
            self.pending_samples.clear();
            self.encode_frame(&final_frame)?;
        }

        let (final_packet, _) = self.held_packet.take().ok_or_else(|| {
            ManagedAudioError::new(
                "managed_audio_encode_failed",
                "managed Opus stream has no audio packet",
            )
        })?;
        let final_granule = u64::from(self.pre_skip)
            .checked_add(self.original_samples.saturating_mul(3))
            .ok_or_else(|| {
                ManagedAudioError::new(
                    "managed_audio_encode_failed",
                    "managed Opus final granule overflowed",
                )
            })?;
        self.writer.write_packet(
            final_packet,
            self.stream_serial,
            PacketWriteEndInfo::EndStream,
            final_granule,
        )?;
        self.ensure_size_limit()?;
        let bytes = self.writer.into_inner();

        Ok(EncodedManagedAudio {
            bytes,
            original_samples: self.original_samples,
            final_granule,
            pre_skip: self.pre_skip,
            packet_count: self.packet_count,
        })
    }
}

fn opus_error(error: opusic_c::ErrorCode) -> ManagedAudioError {
    ManagedAudioError::new(
        "managed_audio_encode_failed",
        format!("Opus encoder failed: {}", error.message()),
    )
}

fn opus_head(pre_skip: u16) -> Vec<u8> {
    let mut packet = Vec::with_capacity(19);
    packet.extend_from_slice(b"OpusHead");
    packet.push(1);
    packet.push(1);
    packet.extend_from_slice(&pre_skip.to_le_bytes());
    packet.extend_from_slice(&16_000u32.to_le_bytes());
    packet.extend_from_slice(&0i16.to_le_bytes());
    packet.push(0);
    packet
}

fn opus_tags() -> Vec<u8> {
    const VENDOR: &[u8] = b"OpenTypeless";
    let mut packet = Vec::with_capacity(16 + VENDOR.len());
    packet.extend_from_slice(b"OpusTags");
    packet.extend_from_slice(&(VENDOR.len() as u32).to_le_bytes());
    packet.extend_from_slice(VENDOR);
    packet.extend_from_slice(&0u32.to_le_bytes());
    packet
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::Cursor;

    fn silent_pcm(samples: usize) -> Vec<u8> {
        vec![0; samples * 2]
    }

    fn read_packets(bytes: &[u8]) -> Vec<ogg::Packet> {
        let mut reader = ogg::PacketReader::new(Cursor::new(bytes));
        let mut packets = Vec::new();
        while let Some(packet) = reader.read_packet().unwrap() {
            packets.push(packet);
        }
        packets
    }

    #[test]
    fn payload_selection_preserves_the_exact_reviewed_wav_boundary() {
        let config = ManagedAudioEncodingConfig::default();
        assert_eq!(
            select_managed_payload(3_500_000, config),
            ManagedAudioPayloadKind::Wav
        );
        assert_eq!(
            select_managed_payload(3_500_001, config),
            ManagedAudioPayloadKind::OggOpus
        );
    }

    #[test]
    fn encoder_writes_canonical_headers_cbr_packets_and_final_granule() {
        let encoded = encode_pcm_to_ogg_opus(
            &silent_pcm(MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME),
            0x1020_3040,
            ManagedAudioEncodingConfig::default(),
        )
        .unwrap();
        let packets = read_packets(&encoded.bytes);

        assert_eq!(&packets[0].data[..8], b"OpusHead");
        assert_eq!(&packets[1].data[..8], b"OpusTags");
        assert_eq!(packets.len(), 3);
        assert_eq!(packets[2].data.len(), 120);
        assert!(packets[2].last_in_stream());
        assert_eq!(encoded.packet_count, 1);
        assert_eq!(
            encoded.final_granule,
            u64::from(encoded.pre_skip) + MANAGED_OPUS_GRANULE_SAMPLES_PER_FRAME
        );
        assert_eq!(packets[2].absgp_page(), encoded.final_granule);
    }

    #[test]
    fn encoder_pads_only_the_last_frame_and_trims_it_with_the_final_granule() {
        let original_samples = MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME + 1;
        let encoded = encode_pcm_to_ogg_opus(
            &silent_pcm(original_samples),
            7,
            ManagedAudioEncodingConfig::default(),
        )
        .unwrap();
        let packets = read_packets(&encoded.bytes);

        assert_eq!(encoded.packet_count, 2);
        assert_eq!(packets.len(), 4);
        assert_eq!(encoded.original_samples, original_samples as u64);
        assert_eq!(
            encoded.final_granule,
            u64::from(encoded.pre_skip) + original_samples as u64 * 3
        );
    }

    #[test]
    fn encoder_aggregates_audio_packets_into_ogg_pages() {
        let frames = 100;
        let encoded = encode_pcm_to_ogg_opus(
            &silent_pcm(MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME * frames),
            8,
            ManagedAudioEncodingConfig::default(),
        )
        .unwrap();
        let page_count = encoded
            .bytes
            .windows(4)
            .filter(|window| *window == b"OggS")
            .count();

        assert_eq!(encoded.packet_count, frames as u32);
        assert!(
            page_count < frames / 2,
            "audio was written one page per packet"
        );
    }

    #[test]
    fn ten_minutes_at_the_negotiated_bitrate_stays_below_the_audio_cap() {
        let samples = 16_000 * 600;
        let config = ManagedAudioEncodingConfig::default();
        let encoded = encode_pcm_to_ogg_opus(&silent_pcm(samples), 14, config).unwrap();

        assert_eq!(encoded.original_samples, samples as u64);
        assert_eq!(encoded.packet_count, 30_000);
        assert!(encoded.bytes.len() <= config.max_audio_bytes as usize);
        assert!(encoded.bytes.len() > 3_500_000);
    }

    #[test]
    fn encoder_rejects_unaligned_pcm_and_oversized_output_with_stable_codes() {
        let unaligned =
            encode_pcm_to_ogg_opus(&[0], 9, ManagedAudioEncodingConfig::default()).unwrap_err();
        assert_eq!(unaligned.code, "managed_audio_invalid_pcm");

        let oversized = encode_pcm_to_ogg_opus(
            &silent_pcm(MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME),
            10,
            ManagedAudioEncodingConfig {
                max_audio_bytes: 100,
                ..ManagedAudioEncodingConfig::default()
            },
        )
        .unwrap_err();
        assert_eq!(oversized.code, "managed_audio_too_large");
    }

    #[test]
    fn encoder_returns_a_valid_prefix_before_reaching_the_negotiated_cap() {
        let input_samples = MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME * 100;
        let config = ManagedAudioEncodingConfig {
            max_audio_bytes: 1_000,
            ..ManagedAudioEncodingConfig::default()
        };
        let encoded = encode_pcm_to_ogg_opus(&silent_pcm(input_samples), 13, config).unwrap();
        let packets = read_packets(&encoded.bytes);

        assert!(encoded.original_samples < input_samples as u64);
        assert!(encoded.bytes.len() <= config.max_audio_bytes as usize);
        assert!(packets.last().unwrap().last_in_stream());
        assert_eq!(
            encoded.final_granule,
            u64::from(encoded.pre_skip) + encoded.original_samples * 3
        );
    }

    #[tokio::test]
    async fn worker_encodes_ordered_chunks_and_finalizes_off_the_async_runtime() {
        let worker =
            ManagedAudioEncoderWorker::start(11, ManagedAudioEncodingConfig::default()).unwrap();
        worker
            .try_send_pcm(&silent_pcm(MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME / 2))
            .unwrap();
        worker
            .try_send_pcm(&silent_pcm(MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME / 2))
            .unwrap();

        let encoded = worker.finish().await.unwrap();
        assert_eq!(
            encoded.original_samples,
            MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME as u64
        );
        assert_eq!(encoded.packet_count, 1);
    }

    #[tokio::test]
    async fn bounded_worker_queue_reports_overflow_without_blocking_capture() {
        let (release_sender, release_receiver) = mpsc::channel();
        let worker = ManagedAudioEncoderWorker::start_inner(
            12,
            ManagedAudioEncodingConfig::default(),
            1,
            Some(release_receiver),
        )
        .unwrap();
        let chunk = silent_pcm(MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME);

        worker.try_send_pcm(&chunk).unwrap();
        let overflow = worker.try_send_pcm(&chunk).unwrap_err();
        assert_eq!(overflow.code, "managed_audio_queue_full");

        release_sender.send(()).unwrap();
        let encoded = worker.finish().await.unwrap();
        assert_eq!(encoded.packet_count, 1);
    }

    #[tokio::test]
    async fn cancelling_a_worker_drops_partial_audio_without_a_payload() {
        let worker =
            ManagedAudioEncoderWorker::start(13, ManagedAudioEncodingConfig::default()).unwrap();
        worker
            .try_send_pcm(&silent_pcm(MANAGED_OPUS_INPUT_SAMPLES_PER_FRAME))
            .unwrap();

        worker.cancel().await.unwrap();
    }
}
