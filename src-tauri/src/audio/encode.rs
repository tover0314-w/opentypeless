//! Encoding of raw 16 kHz mono s16le PCM into a saved recording file.
//!
//! The capture pipeline produces little-endian signed-16-bit mono PCM. When the
//! user enables "save recordings", that PCM is encoded here into WAV, FLAC, or
//! MP3 before being written to disk.

use anyhow::Result;

use crate::stt::whisper_compat::WhisperCompatProvider;

/// Output container for a saved recording. Parsed from `AppConfig.recording_format`.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecordingFormat {
    Wav,
    Flac,
    Mp3,
}

impl RecordingFormat {
    /// Parse the `recording_format` config string. Unknown values fall back to
    /// FLAC (the default), so a malformed config never breaks recording.
    pub fn from_config_str(value: &str) -> Self {
        match value.trim().to_ascii_lowercase().as_str() {
            "wav" => Self::Wav,
            "mp3" => Self::Mp3,
            _ => Self::Flac,
        }
    }

    /// Lower-case file extension (without dot) for this format.
    pub fn ext(self) -> &'static str {
        match self {
            Self::Wav => "wav",
            Self::Flac => "flac",
            Self::Mp3 => "mp3",
        }
    }
}

/// Decode interleaved s16le PCM bytes into i16 samples. A trailing odd byte
/// (incomplete sample) is dropped.
fn pcm_to_i16(pcm: &[u8]) -> Vec<i16> {
    pcm.chunks_exact(2)
        .map(|b| i16::from_le_bytes([b[0], b[1]]))
        .collect()
}

/// Encode raw s16le mono PCM into the requested container format.
///
/// Returns the encoded bytes and the matching file extension (without dot).
pub fn encode_pcm(
    pcm: &[u8],
    sample_rate: u32,
    format: RecordingFormat,
) -> Result<(Vec<u8>, &'static str)> {
    match format {
        RecordingFormat::Wav => Ok((WhisperCompatProvider::build_wav(pcm, sample_rate), "wav")),
        RecordingFormat::Flac => Ok((encode_flac(pcm, sample_rate)?, "flac")),
        RecordingFormat::Mp3 => Ok((encode_mp3(pcm, sample_rate)?, "mp3")),
    }
}

/// Encode s16le mono PCM into FLAC using the pure-Rust `flacenc` encoder.
fn encode_flac(pcm: &[u8], sample_rate: u32) -> Result<Vec<u8>> {
    use flacenc::component::BitRepr;

    // flacenc operates on i32 samples regardless of the declared bit depth.
    let samples: Vec<i32> = pcm_to_i16(pcm).into_iter().map(i32::from).collect();

    // The default encoder configuration is static and always valid.
    let config = {
        use flacenc::error::Verify;
        flacenc::config::Encoder::default()
            .into_verified()
            .expect("default flac encoder config is valid")
    };

    let source = flacenc::source::MemSource::from_samples(
        &samples,
        1,  // channels (mono)
        16, // bits per sample
        sample_rate as usize,
    );

    let stream = flacenc::encode_with_fixed_block_size(&config, source, config.block_size)
        .map_err(|e| anyhow::anyhow!("FLAC encode failed: {e:?}"))?;

    let mut sink = flacenc::bitsink::ByteSink::new();
    stream
        .write(&mut sink)
        .map_err(|_| anyhow::anyhow!("FLAC bitstream write failed"))?;

    Ok(sink.as_slice().to_vec())
}

/// Encode s16le mono PCM into ~64 kbps MP3 via libmp3lame.
#[cfg(feature = "encode-mp3")]
fn encode_mp3(pcm: &[u8], sample_rate: u32) -> Result<Vec<u8>> {
    use mp3lame_encoder::{Bitrate, Builder, FlushNoGap, MonoPcm, Quality};

    let samples = pcm_to_i16(pcm);

    let builder = Builder::new().ok_or_else(|| anyhow::anyhow!("failed to create LAME builder"))?;
    let builder = builder
        .with_num_channels(1)
        .map_err(|e| anyhow::anyhow!("LAME set channels: {e:?}"))?;
    let builder = builder
        .with_sample_rate(sample_rate)
        .map_err(|e| anyhow::anyhow!("LAME set sample rate: {e:?}"))?;
    let builder = builder
        .with_brate(Bitrate::Kbps64)
        .map_err(|e| anyhow::anyhow!("LAME set bitrate: {e:?}"))?;
    let builder = builder
        .with_quality(Quality::Best)
        .map_err(|e| anyhow::anyhow!("LAME set quality: {e:?}"))?;
    let mut encoder = builder
        .build()
        .map_err(|e| anyhow::anyhow!("LAME init: {e:?}"))?;

    let mut mp3 = Vec::with_capacity(mp3lame_encoder::max_required_buffer_size(samples.len()));
    let written = encoder
        .encode(MonoPcm(&samples), mp3.spare_capacity_mut())
        .map_err(|e| anyhow::anyhow!("MP3 encode: {e:?}"))?;
    // SAFETY: `written` bytes were just initialised by `encode`.
    unsafe { mp3.set_len(mp3.len() + written) };

    // Flush trailing frames. `flush` needs up to ~7200 bytes of headroom.
    mp3.reserve(7200);
    let flushed = encoder
        .flush::<FlushNoGap>(mp3.spare_capacity_mut())
        .map_err(|e| anyhow::anyhow!("MP3 flush: {e:?}"))?;
    // SAFETY: `flushed` bytes were just initialised by `flush`.
    unsafe { mp3.set_len(mp3.len() + flushed) };

    Ok(mp3)
}

/// MP3 fallback when the `encode-mp3` feature is disabled at build time.
#[cfg(not(feature = "encode-mp3"))]
fn encode_mp3(_pcm: &[u8], _sample_rate: u32) -> Result<Vec<u8>> {
    anyhow::bail!("MP3 recording requires the 'encode-mp3' build feature")
}

#[cfg(test)]
mod tests {
    use super::*;

    /// 0.1 s of silent 16 kHz mono PCM (1600 samples × 2 bytes).
    fn sample_pcm() -> Vec<u8> {
        vec![0u8; 1600 * 2]
    }

    #[test]
    fn from_config_str_parses_known_formats() {
        assert_eq!(
            RecordingFormat::from_config_str("wav"),
            RecordingFormat::Wav
        );
        assert_eq!(
            RecordingFormat::from_config_str("FLAC"),
            RecordingFormat::Flac
        );
        assert_eq!(
            RecordingFormat::from_config_str("mp3"),
            RecordingFormat::Mp3
        );
    }

    #[test]
    fn from_config_str_defaults_unknown_to_flac() {
        assert_eq!(
            RecordingFormat::from_config_str("ogg"),
            RecordingFormat::Flac
        );
        assert_eq!(RecordingFormat::from_config_str(""), RecordingFormat::Flac);
    }

    #[test]
    fn encode_wav_has_riff_wave_header() {
        let (bytes, ext) = encode_pcm(&sample_pcm(), 16000, RecordingFormat::Wav).unwrap();
        assert_eq!(ext, "wav");
        assert_eq!(&bytes[0..4], b"RIFF");
        assert_eq!(&bytes[8..12], b"WAVE");
    }

    #[test]
    fn encode_flac_has_flac_magic() {
        let (bytes, ext) = encode_pcm(&sample_pcm(), 16000, RecordingFormat::Flac).unwrap();
        assert_eq!(ext, "flac");
        assert_eq!(&bytes[0..4], b"fLaC");
    }

    #[cfg(feature = "encode-mp3")]
    #[test]
    fn encode_mp3_is_non_empty() {
        let (bytes, ext) = encode_pcm(&sample_pcm(), 16000, RecordingFormat::Mp3).unwrap();
        assert_eq!(ext, "mp3");
        assert!(!bytes.is_empty());
    }

    #[cfg(not(feature = "encode-mp3"))]
    #[test]
    fn encode_mp3_errors_without_feature() {
        assert!(encode_pcm(&sample_pcm(), 16000, RecordingFormat::Mp3).is_err());
    }
}
