use async_trait::async_trait;

use crate::error::AppError;

use super::whisper_compat::WhisperCompatProvider;
use super::{SttConfig, SttProvider, TranscriptEvent};

/// 60db (https://60db.ai) speech-to-text provider.
///
/// 60db exposes a batch `multipart/form-data` REST endpoint that accepts a WAV
/// file and returns a JSON transcript with a top-level `text` field. This mirrors
/// the Whisper-compatible / Cloud providers: audio is buffered while recording and
/// uploaded in `disconnect()` once the user stops talking.
///
/// API reference: https://docs.60db.ai/api-reference/stt/speech-to-text
pub struct SixtyDbProvider {
    stt_config: Option<SttConfig>,
    audio_buffer: Vec<u8>,
    client: reqwest::Client,
}

/// 60db caps uploads at 10 MB. Keep the buffered PCM under that so the WAV
/// (PCM + 44-byte header) stays within the limit. ~10 MB ≈ 5.4 min at 16kHz 16-bit mono.
const MAX_AUDIO_BYTES: usize = 10 * 1024 * 1024 - 4096;

const ENDPOINT: &str = "https://api.60db.ai/stt";

impl Default for SixtyDbProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl SixtyDbProvider {
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
}

#[async_trait]
impl SttProvider for SixtyDbProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        if config.api_key.trim().is_empty() {
            return Err(AppError::Auth("60db API key is empty".to_string()));
        }
        self.stt_config = Some(config.clone());
        self.audio_buffer.clear();
        tracing::info!("60db provider ready (buffering mode)");
        Ok(())
    }

    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        if self.audio_buffer.len() + chunk.len() > MAX_AUDIO_BYTES {
            return Err(AppError::Config(
                "60db: audio exceeds maximum length (~5 min / 10 MB)".to_string(),
            ));
        }
        self.audio_buffer.extend_from_slice(chunk);
        Ok(())
    }

    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError> {
        // File-based provider: the transcript is produced in disconnect(). Keep this
        // future pending so the pipeline select loop parks on audio chunks instead of
        // busy-spinning while recording.
        std::future::pending().await
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        let config = match &self.stt_config {
            Some(c) => c.clone(),
            None => return Ok(None),
        };

        if self.audio_buffer.is_empty() {
            tracing::info!("60db: no audio buffered, skipping");
            return Ok(None);
        }

        let audio_len_secs = self.audio_buffer.len() as f64 / (config.sample_rate as f64 * 2.0);
        let wav_data = WhisperCompatProvider::build_wav(&self.audio_buffer, config.sample_rate);
        self.audio_buffer.clear();
        tracing::info!("60db: sending {:.1}s of audio for transcription", audio_len_secs);

        let mut attempt = 0u32;
        loop {
            let file_part = reqwest::multipart::Part::bytes(wav_data.clone())
                .file_name("audio.wav")
                .mime_str("audio/wav")
                .map_err(|e| AppError::Config(e.to_string()))?;

            let mut form = reqwest::multipart::Form::new().part("file", file_part);

            // Language hint. The app stores "multi" as None (auto-detect); 60db
            // auto-detects when `language` is omitted, so only send a concrete code.
            if let Some(ref lang) = config.language {
                if lang != "multi" {
                    form = form.text("language", lang.clone());
                }
            }

            let resp_result = self
                .client
                .post(ENDPOINT)
                .header("Authorization", format!("Bearer {}", config.api_key))
                .multipart(form)
                .timeout(std::time::Duration::from_secs(60))
                .send()
                .await;

            match resp_result {
                Ok(resp) => {
                    let status = resp.status();
                    let body = resp.text().await.unwrap_or_default();

                    if status.is_success() {
                        let v: serde_json::Value = serde_json::from_str(&body)
                            .map_err(|e| AppError::Config(e.to_string()))?;
                        let text = v["text"].as_str().unwrap_or("").trim().to_string();

                        tracing::info!("60db transcription: {} chars", text.len());

                        return Ok(if text.is_empty() { None } else { Some(text) });
                    } else if status.as_u16() == 401 {
                        return Err(AppError::Auth("60db: invalid API key".to_string()));
                    } else if status.as_u16() == 402 {
                        return Err(AppError::Quota(
                            "60db: insufficient credits. Please top up your 60db wallet.".to_string(),
                        ));
                    } else if (status.as_u16() == 429 || status.as_u16() >= 500) && attempt < 2 {
                        // Concurrency / rate-limit (429) and upstream errors (5xx) are
                        // transient — retry with exponential backoff.
                        tracing::warn!(
                            "60db transient error {} (attempt {}/3): {}",
                            status,
                            attempt + 1,
                            truncate_body(&body)
                        );
                        attempt += 1;
                        tokio::time::sleep(std::time::Duration::from_millis(
                            1000 * 2u64.pow(attempt - 1),
                        ))
                        .await;
                        continue;
                    } else {
                        let sanitized = truncate_body(&body);
                        tracing::error!("60db HTTP {}: {}", status, sanitized);
                        return Err(AppError::Api {
                            status: status.as_u16(),
                            body: sanitized.to_string(),
                        });
                    }
                }
                Err(e) if e.is_timeout() && attempt < 2 => {
                    tracing::warn!("60db timeout (attempt {}/3)", attempt + 1);
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
        "60dB"
    }
}

/// Truncate a response body to ~200 bytes at a valid UTF-8 char boundary so logged
/// errors never panic on multi-byte characters.
fn truncate_body(body: &str) -> &str {
    let truncate_at = body
        .char_indices()
        .take_while(|&(i, _)| i < 200)
        .last()
        .map(|(i, c)| i + c.len_utf8())
        .unwrap_or(body.len());
    &body[..truncate_at]
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn connect_rejects_empty_api_key() {
        let mut provider = SixtyDbProvider::new();
        let result = provider
            .connect(&SttConfig {
                api_key: String::new(),
                language: None,
                smart_format: true,
                sample_rate: 16000,
            })
            .await;
        assert!(matches!(result, Err(AppError::Auth(_))));
    }

    #[tokio::test]
    async fn connect_accepts_api_key() {
        let mut provider = SixtyDbProvider::new();
        let result = provider
            .connect(&SttConfig {
                api_key: "sk-test".to_string(),
                language: Some("en".to_string()),
                smart_format: true,
                sample_rate: 16000,
            })
            .await;
        assert!(result.is_ok());
        assert_eq!(provider.name(), "60dB");
    }

    #[tokio::test]
    async fn send_audio_rejects_oversized_buffer() {
        let mut provider = SixtyDbProvider::new();
        provider
            .connect(&SttConfig {
                api_key: "sk-test".to_string(),
                language: None,
                smart_format: true,
                sample_rate: 16000,
            })
            .await
            .unwrap();

        let oversized = vec![0u8; MAX_AUDIO_BYTES + 1];
        let result = provider.send_audio(&oversized).await;
        assert!(matches!(result, Err(AppError::Config(_))));
    }

    #[tokio::test]
    async fn recv_transcript_waits_for_buffered_provider() {
        let mut provider = SixtyDbProvider::new();
        let result = tokio::time::timeout(
            std::time::Duration::from_millis(20),
            provider.recv_transcript(),
        )
        .await;
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn disconnect_with_empty_buffer_returns_none() {
        let mut provider = SixtyDbProvider::new();
        provider
            .connect(&SttConfig {
                api_key: "sk-test".to_string(),
                language: None,
                smart_format: true,
                sample_rate: 16000,
            })
            .await
            .unwrap();
        let result = provider.disconnect().await.unwrap();
        assert!(result.is_none());
    }
}
