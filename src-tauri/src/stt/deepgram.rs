use async_trait::async_trait;

use crate::error::AppError;

use super::{SttConfig, SttProvider, TranscriptEvent};

/// Max audio buffer: ~24 MB PCM, about 12.5 minutes at 16 kHz 16-bit mono.
const MAX_AUDIO_BYTES: usize = 24 * 1024 * 1024;

pub struct DeepgramProvider {
    stt_config: Option<SttConfig>,
    audio_buffer: Vec<u8>,
    client: reqwest::Client,
}

impl Default for DeepgramProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl DeepgramProvider {
    pub fn new() -> Self {
        Self {
            stt_config: None,
            audio_buffer: Vec::new(),
            client: reqwest::Client::new(),
        }
    }

    pub fn with_client(client: reqwest::Client) -> Self {
        Self {
            stt_config: None,
            audio_buffer: Vec::new(),
            client,
        }
    }

    fn build_wav(pcm: &[u8], sample_rate: u32) -> Vec<u8> {
        let data_len = pcm.len() as u32;
        let channels: u16 = 1;
        let bits_per_sample: u16 = 16;
        let byte_rate = sample_rate * channels as u32 * bits_per_sample as u32 / 8;
        let block_align = channels * bits_per_sample / 8;
        let file_size = 36 + data_len;

        let mut wav = Vec::with_capacity(44 + pcm.len());
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&file_size.to_le_bytes());
        wav.extend_from_slice(b"WAVE");
        wav.extend_from_slice(b"fmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes());
        wav.extend_from_slice(&channels.to_le_bytes());
        wav.extend_from_slice(&sample_rate.to_le_bytes());
        wav.extend_from_slice(&byte_rate.to_le_bytes());
        wav.extend_from_slice(&block_align.to_le_bytes());
        wav.extend_from_slice(&bits_per_sample.to_le_bytes());
        wav.extend_from_slice(b"data");
        wav.extend_from_slice(&data_len.to_le_bytes());
        wav.extend_from_slice(pcm);
        wav
    }

    fn build_rest_url(config: &SttConfig) -> String {
        let lang = config.language.as_deref().unwrap_or("multi");
        format!(
            "https://api.deepgram.com/v1/listen?model=nova-3&smart_format={}&language={}&punctuate=true",
            config.smart_format, lang
        )
    }

    fn truncate_body(body: &str) -> String {
        body.chars().take(200).collect()
    }
}

#[async_trait]
impl SttProvider for DeepgramProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        if config.api_key.is_empty() {
            return Err(AppError::Auth("Deepgram API key is empty".to_string()));
        }

        self.stt_config = Some(config.clone());
        self.audio_buffer.clear();
        tracing::info!("Deepgram provider ready in batch REST mode");
        Ok(())
    }

    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        if self.audio_buffer.len() + chunk.len() > MAX_AUDIO_BYTES {
            return Err(AppError::Config(
                "Deepgram audio exceeds maximum supported length".to_string(),
            ));
        }

        self.audio_buffer.extend_from_slice(chunk);
        Ok(())
    }

    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError> {
        std::future::pending().await
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        let config = match self.stt_config.clone() {
            Some(config) => config,
            None => return Ok(None),
        };

        if self.audio_buffer.is_empty() {
            self.stt_config = None;
            tracing::info!("Deepgram disconnect skipped because no audio was buffered");
            return Ok(None);
        }

        let audio_seconds = self.audio_buffer.len() as f64 / (config.sample_rate as f64 * 2.0);
        let wav_data = Self::build_wav(&self.audio_buffer, config.sample_rate);
        self.audio_buffer.clear();
        self.stt_config = None;

        tracing::info!(
            "Deepgram sending {:.1}s of audio via batch REST transcription",
            audio_seconds
        );

        let url = Self::build_rest_url(&config);
        let mut attempt = 0u32;
        loop {
            let response = self
                .client
                .post(&url)
                .header("Authorization", format!("Token {}", config.api_key))
                .header("Content-Type", "audio/wav")
                .body(wav_data.clone())
                .timeout(std::time::Duration::from_secs(60))
                .send()
                .await;

            match response {
                Ok(resp) => {
                    let status = resp.status();
                    let body = resp.text().await.unwrap_or_default();

                    if status.is_success() {
                        let value: serde_json::Value = serde_json::from_str(&body)
                            .map_err(|e| AppError::Config(e.to_string()))?;
                        let transcript = value["results"]["channels"][0]["alternatives"][0]
                            ["transcript"]
                            .as_str()
                            .unwrap_or("")
                            .trim()
                            .to_string();
                        tracing::info!("Deepgram batch transcript length: {}", transcript.len());
                        return Ok((!transcript.is_empty()).then_some(transcript));
                    }

                    let sanitized = Self::truncate_body(&body);
                    if status.as_u16() >= 500 && attempt < 2 {
                        tracing::warn!(
                            "Deepgram server error {} (attempt {}/3): {}",
                            status,
                            attempt + 1,
                            sanitized
                        );
                        attempt += 1;
                        tokio::time::sleep(std::time::Duration::from_millis(
                            1000 * 2u64.pow(attempt - 1),
                        ))
                        .await;
                        continue;
                    }

                    return Err(AppError::Api {
                        status: status.as_u16(),
                        body: sanitized,
                    });
                }
                Err(e) if e.is_timeout() && attempt < 2 => {
                    tracing::warn!("Deepgram timeout (attempt {}/3)", attempt + 1);
                    attempt += 1;
                    tokio::time::sleep(std::time::Duration::from_millis(
                        1000 * 2u64.pow(attempt - 1),
                    ))
                    .await;
                    continue;
                }
                Err(e) => return Err(e.into()),
            }
        }
    }

    fn name(&self) -> &str {
        "Deepgram Nova-3"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn build_wav_wraps_pcm_audio_with_valid_header() {
        let pcm = [0x01, 0x02, 0x03, 0x04];

        let wav = DeepgramProvider::build_wav(&pcm, 16_000);

        assert_eq!(&wav[0..4], b"RIFF");
        assert_eq!(&wav[8..12], b"WAVE");
        assert_eq!(&wav[12..16], b"fmt ");
        assert_eq!(&wav[36..40], b"data");
        assert_eq!(u32::from_le_bytes(wav[24..28].try_into().unwrap()), 16_000);
        assert_eq!(u16::from_le_bytes(wav[34..36].try_into().unwrap()), 16);
        assert_eq!(
            u32::from_le_bytes(wav[40..44].try_into().unwrap()),
            pcm.len() as u32
        );
        assert_eq!(&wav[44..], pcm);
    }

    #[tokio::test]
    async fn send_audio_rejects_recordings_over_buffer_limit() {
        let mut provider = DeepgramProvider::new();
        provider
            .connect(&SttConfig {
                api_key: "test-key".to_string(),
                language: Some("en".to_string()),
                smart_format: true,
                sample_rate: 16_000,
            })
            .await
            .unwrap();

        let almost_full = vec![0; MAX_AUDIO_BYTES];
        provider.send_audio(&almost_full).await.unwrap();

        let err = provider.send_audio(&[1]).await.unwrap_err();
        assert!(matches!(err, AppError::Config(_)));
    }

    #[tokio::test]
    async fn recv_transcript_waits_in_batch_mode() {
        let mut provider = DeepgramProvider::new();

        let result = tokio::time::timeout(
            std::time::Duration::from_millis(20),
            provider.recv_transcript(),
        )
        .await;

        assert!(result.is_err());
    }
}
