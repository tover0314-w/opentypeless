use async_trait::async_trait;
use std::path::PathBuf;

use crate::error::AppError;

use super::{SttConfig, SttProvider, TranscriptEvent};

/// Configuration for a Whisper-compatible HTTP file-upload STT provider.
#[derive(Debug)]
pub struct WhisperCompatConfig {
    pub provider_name: String,
    pub endpoint: String,
    pub model: String,
    /// Extra form text fields (e.g. GLM-ASR needs "stream"="false").
    pub extra_fields: Vec<(String, String)>,
    /// Local OpenAI-compatible servers often do not require authentication.
    pub api_key_required: bool,
    /// Some providers cap each transcription request by duration.
    /// When set, PCM audio is uploaded as multiple WAV chunks.
    pub max_request_duration_secs: Option<f64>,
}

/// Max audio buffer: ~24 MB PCM ≈ 12.5 min at 16kHz 16-bit mono.
/// Keeps the resulting WAV under 25 MB (OpenAI/Groq limit).
const MAX_AUDIO_BYTES: usize = 24 * 1024 * 1024;
const MIN_TRANSCRIPTION_TIMEOUT_SECS: u64 = 60;
const MAX_TRANSCRIPTION_TIMEOUT_SECS: u64 = 300;
const LONG_AUDIO_RETRY_THRESHOLD_SECS: f64 = 60.0;
const RECORDINGS_TO_KEEP: usize = 10;
pub(crate) const GLM_ASR_SAFE_CHUNK_SECS: f64 = 28.0;

/// Generic provider for any OpenAI Whisper-compatible transcription API.
/// Works with: OpenAI, Groq, SiliconFlow, GLM-ASR.
pub struct WhisperCompatProvider {
    provider_config: WhisperCompatConfig,
    stt_config: Option<SttConfig>,
    audio_buffer: Vec<u8>,
    client: reqwest::Client,
}

impl WhisperCompatProvider {
    pub fn new(provider_config: WhisperCompatConfig) -> Self {
        Self {
            provider_config,
            stt_config: None,
            audio_buffer: Vec::new(),
            client: reqwest::Client::new(),
        }
    }

    pub fn with_client(provider_config: WhisperCompatConfig, client: reqwest::Client) -> Self {
        Self {
            provider_config,
            stt_config: None,
            audio_buffer: Vec::new(),
            client,
        }
    }

    /// Build a WAV file from raw PCM 16-bit mono audio. Public so test helpers can reuse it.
    pub fn build_wav(pcm: &[u8], sample_rate: u32) -> Vec<u8> {
        let data_len = pcm.len() as u32;
        let channels: u16 = 1;
        let bits_per_sample: u16 = 16;
        let byte_rate = sample_rate * (channels as u32) * (bits_per_sample as u32) / 8;
        let block_align = channels * bits_per_sample / 8;
        let file_size = 36 + data_len;

        let mut wav = Vec::with_capacity(44 + pcm.len());
        wav.extend_from_slice(b"RIFF");
        wav.extend_from_slice(&file_size.to_le_bytes());
        wav.extend_from_slice(b"WAVE");
        wav.extend_from_slice(b"fmt ");
        wav.extend_from_slice(&16u32.to_le_bytes());
        wav.extend_from_slice(&1u16.to_le_bytes()); // PCM
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

    fn transcription_timeout(audio_len_secs: f64) -> std::time::Duration {
        let secs = (audio_len_secs.ceil() as u64 + 60).clamp(
            MIN_TRANSCRIPTION_TIMEOUT_SECS,
            MAX_TRANSCRIPTION_TIMEOUT_SECS,
        );
        std::time::Duration::from_secs(secs)
    }

    fn recordings_dir() -> Option<PathBuf> {
        #[cfg(target_os = "macos")]
        {
            std::env::var_os("HOME").map(|home| {
                PathBuf::from(home)
                    .join("Library")
                    .join("Application Support")
                    .join("com.opentypeless.app")
                    .join("recordings")
            })
        }

        #[cfg(not(target_os = "macos"))]
        {
            None
        }
    }

    fn save_wav_snapshot(provider_name: &str, wav_data: &[u8], audio_len_secs: f64) {
        let Some(dir) = Self::recordings_dir() else {
            return;
        };

        if let Err(e) = std::fs::create_dir_all(&dir) {
            tracing::warn!("Failed to create recordings dir {:?}: {}", dir, e);
            return;
        }

        let safe_provider = provider_name.replace(|c: char| !c.is_ascii_alphanumeric(), "_");
        let timestamp = chrono::Local::now().format("%Y%m%d_%H%M%S");
        let path = dir.join(format!("{}_{}.wav", timestamp, safe_provider));
        let latest_path = dir.join("latest.wav");

        match std::fs::write(&path, wav_data) {
            Ok(()) => {
                let _ = std::fs::write(&latest_path, wav_data);
                let _ = std::fs::write(
                    dir.join("latest.json"),
                    serde_json::json!({
                        "path": path,
                        "provider": provider_name,
                        "duration_seconds": audio_len_secs,
                        "created_at": chrono::Local::now().to_rfc3339(),
                    })
                    .to_string(),
                );
                tracing::info!("Saved transcription audio snapshot to {:?}", latest_path);
                Self::prune_recordings(&dir);
            }
            Err(e) => tracing::warn!("Failed to save transcription audio snapshot: {}", e),
        }
    }

    fn prune_recordings(dir: &PathBuf) {
        let Ok(entries) = std::fs::read_dir(dir) else {
            return;
        };

        let mut wavs: Vec<_> = entries
            .filter_map(|entry| {
                let entry = entry.ok()?;
                let path = entry.path();
                if path.file_name().and_then(|name| name.to_str()) == Some("latest.wav") {
                    return None;
                }
                if path.extension().and_then(|ext| ext.to_str()) != Some("wav") {
                    return None;
                }
                let modified = entry.metadata().ok()?.modified().ok()?;
                Some((modified, path))
            })
            .collect();

        wavs.sort_by_key(|(modified, _)| *modified);
        while wavs.len() > RECORDINGS_TO_KEEP {
            if let Some((_, path)) = wavs.first() {
                let _ = std::fs::remove_file(path);
            }
            wavs.remove(0);
        }
    }

    fn pcm_chunks<'a>(&self, pcm: &'a [u8], sample_rate: u32) -> Vec<&'a [u8]> {
        let Some(max_secs) = self.provider_config.max_request_duration_secs else {
            return vec![pcm];
        };

        let bytes_per_second = sample_rate as usize * 2;
        let mut max_chunk_bytes = (bytes_per_second as f64 * max_secs).floor() as usize;
        max_chunk_bytes -= max_chunk_bytes % 2;
        if max_chunk_bytes == 0 || pcm.len() <= max_chunk_bytes {
            return vec![pcm];
        }

        pcm.chunks(max_chunk_bytes).collect()
    }

    async fn transcribe_wav(
        &self,
        wav_data: &[u8],
        config: &SttConfig,
        audio_len_secs: f64,
        file_name: String,
    ) -> Result<Option<String>, AppError> {
        let mut attempt = 0u32;
        let request_timeout = Self::transcription_timeout(audio_len_secs);
        loop {
            let file_part = reqwest::multipart::Part::bytes(wav_data.to_vec())
                .file_name(file_name.clone())
                .mime_str("audio/wav")
                .map_err(|e| AppError::Config(e.to_string()))?;

            let mut form = reqwest::multipart::Form::new()
                .text("model", self.provider_config.model.to_string())
                .part("file", file_part);

            // Language hint (OpenAI/Groq support `language` field, others use `prompt`)
            if let Some(ref lang) = config.language {
                if lang != "multi" {
                    form = form.text("language", lang.clone());
                }
            }

            // Provider-specific extra fields
            for (key, value) in &self.provider_config.extra_fields {
                form = form.text(key.clone(), value.clone());
            }

            let mut request = self
                .client
                .post(&self.provider_config.endpoint)
                .multipart(form)
                .timeout(request_timeout);

            if !config.api_key.trim().is_empty() {
                request = request.header("Authorization", format!("Bearer {}", config.api_key));
            }

            let resp_result = request.send().await;

            match resp_result {
                Ok(resp) => {
                    let status = resp.status();
                    let body = resp.text().await.unwrap_or_default();

                    if status.is_success() {
                        let v: serde_json::Value = serde_json::from_str(&body)
                            .map_err(|e| AppError::Config(e.to_string()))?;
                        let text = v["text"].as_str().unwrap_or("").trim().to_string();

                        tracing::info!(
                            "{} transcription: {} chars",
                            self.provider_config.provider_name,
                            text.len()
                        );

                        return Ok(if text.is_empty() { None } else { Some(text) });
                    } else if status.as_u16() >= 500 && attempt < 2 {
                        let truncate_at = body
                            .char_indices()
                            .take_while(|&(i, _)| i < 200)
                            .last()
                            .map(|(i, c)| i + c.len_utf8())
                            .unwrap_or(body.len());
                        tracing::warn!(
                            "{} server error {} (attempt {}/3): {}",
                            self.provider_config.provider_name,
                            status,
                            attempt + 1,
                            &body[..truncate_at]
                        );
                        attempt += 1;
                        tokio::time::sleep(std::time::Duration::from_millis(
                            1000 * 2u64.pow(attempt - 1),
                        ))
                        .await;
                        continue;
                    } else {
                        // Truncate at a valid UTF-8 char boundary to avoid panic on multi-byte chars
                        let truncate_at = body
                            .char_indices()
                            .take_while(|&(i, _)| i < 200)
                            .last()
                            .map(|(i, c)| i + c.len_utf8())
                            .unwrap_or(body.len());
                        let sanitized = &body[..truncate_at];
                        tracing::error!(
                            "{} HTTP {}: {}",
                            self.provider_config.provider_name,
                            status,
                            sanitized
                        );
                        return Err(AppError::Api {
                            status: status.as_u16(),
                            body: sanitized.to_string(),
                        });
                    }
                }
                Err(e)
                    if e.is_timeout()
                        && audio_len_secs <= LONG_AUDIO_RETRY_THRESHOLD_SECS
                        && attempt < 2 =>
                {
                    tracing::warn!(
                        "{} timeout (attempt {}/3)",
                        self.provider_config.provider_name,
                        attempt + 1
                    );
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
}

#[async_trait]
impl SttProvider for WhisperCompatProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        if self.provider_config.api_key_required && config.api_key.is_empty() {
            return Err(AppError::Auth(format!(
                "{} API key is empty",
                self.provider_config.provider_name
            )));
        }
        self.stt_config = Some(config.clone());
        self.audio_buffer.clear();
        tracing::info!(
            "{} provider ready (buffering mode)",
            self.provider_config.provider_name
        );
        Ok(())
    }

    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        if self.audio_buffer.len() + chunk.len() > MAX_AUDIO_BYTES {
            return Err(AppError::Config(format!(
                "{}: audio exceeds maximum length (~12 min)",
                self.provider_config.provider_name
            )));
        }
        self.audio_buffer.extend_from_slice(chunk);
        Ok(())
    }

    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError> {
        // File-based providers transcribe in disconnect(); keep this future
        // pending so the pipeline select loop does not busy-spin while recording.
        std::future::pending().await
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        let config = match &self.stt_config {
            Some(c) => c.clone(),
            None => return Ok(None),
        };

        if self.audio_buffer.is_empty() {
            tracing::info!(
                "{}: no audio buffered, skipping",
                self.provider_config.provider_name
            );
            return Ok(None);
        }

        let pcm_data = std::mem::take(&mut self.audio_buffer);
        let audio_len_secs = pcm_data.len() as f64 / (config.sample_rate as f64 * 2.0);
        let wav_data = Self::build_wav(&pcm_data, config.sample_rate);
        Self::save_wav_snapshot(
            &self.provider_config.provider_name,
            &wav_data,
            audio_len_secs,
        );
        tracing::info!(
            "{}: sending {:.1}s of audio for transcription",
            self.provider_config.provider_name,
            audio_len_secs
        );

        let chunks = self.pcm_chunks(&pcm_data, config.sample_rate);
        if chunks.len() > 1 {
            tracing::info!(
                "{}: splitting {:.1}s audio into {} chunks",
                self.provider_config.provider_name,
                audio_len_secs,
                chunks.len()
            );
        }

        let mut texts = Vec::new();
        for (idx, chunk) in chunks.iter().enumerate() {
            let chunk_len_secs = chunk.len() as f64 / (config.sample_rate as f64 * 2.0);
            let chunk_wav = Self::build_wav(chunk, config.sample_rate);
            if let Some(text) = self
                .transcribe_wav(
                    &chunk_wav,
                    &config,
                    chunk_len_secs,
                    format!("audio_part_{}.wav", idx + 1),
                )
                .await?
            {
                texts.push(text);
            }
        }

        let text = texts.join(" ").trim().to_string();
        Ok(if text.is_empty() { None } else { Some(text) })
    }

    fn name(&self) -> &str {
        &self.provider_config.provider_name
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn connect_allows_empty_api_key_when_not_required() {
        let mut provider = WhisperCompatProvider::new(WhisperCompatConfig {
            provider_name: "custom-whisper".to_string(),
            endpoint: "http://localhost:8000/v1/audio/transcriptions".to_string(),
            model: "test-model".to_string(),
            extra_fields: vec![],
            api_key_required: false,
            max_request_duration_secs: None,
        });

        let result = provider
            .connect(&SttConfig {
                api_key: String::new(),
                language: None,
                smart_format: true,
                sample_rate: 16000,
                resource_id: None,
                operation_id: None,
            })
            .await;

        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn recv_transcript_waits_for_file_based_provider() {
        let mut provider = WhisperCompatProvider::new(WhisperCompatConfig {
            provider_name: "test-whisper".to_string(),
            endpoint: "https://example.test/transcriptions".to_string(),
            model: "test-model".to_string(),
            extra_fields: vec![],
            api_key_required: true,
            max_request_duration_secs: None,
        });

        let result = tokio::time::timeout(
            std::time::Duration::from_millis(20),
            provider.recv_transcript(),
        )
        .await;

        assert!(result.is_err());
    }
}
