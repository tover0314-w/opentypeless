pub mod assemblyai;
pub mod cloud;
pub mod config;
pub mod deepgram;
pub mod whisper_compat;

use async_trait::async_trait;
use serde::{Deserialize, Serialize};

use crate::error::AppError;

use whisper_compat::{WhisperCompatConfig, WhisperCompatProvider};

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct SttConfig {
    pub api_key: String,
    pub language: Option<String>,
    pub smart_format: bool,
    pub sample_rate: u32,
}

impl Default for SttConfig {
    fn default() -> Self {
        Self {
            api_key: String::new(),
            language: None,
            smart_format: true,
            sample_rate: 16000,
        }
    }
}

#[derive(Debug, Clone)]
pub enum TranscriptEvent {
    Partial { text: String },
    Final { text: String, confidence: f32 },
    SpeechStarted,
    SpeechEnded,
    Error { message: String },
}

#[async_trait]
pub trait SttProvider: Send + Sync {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError>;
    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError>;
    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError>;
    /// Disconnect and optionally return a final transcript (for file-based providers).
    async fn disconnect(&mut self) -> Result<Option<String>, AppError>;
    fn name(&self) -> &str;
}

pub fn create_provider(
    provider_name: &str,
    custom_whisper_config: Option<WhisperCompatConfig>,
    client: Option<reqwest::Client>,
) -> Result<Box<dyn SttProvider>, AppError> {
    match provider_name {
        "cloud" => {
            let api_base_url = crate::api_base_url();
            Ok(match client {
                Some(ref c) => Box::new(cloud::CloudSttProvider::with_client(
                    api_base_url,
                    c.clone(),
                )),
                None => Box::new(cloud::CloudSttProvider::new(api_base_url)),
            })
        }
        "assemblyai" => Ok(Box::new(assemblyai::AssemblyAiProvider::new())),
        "deepgram" => Ok(Box::new(deepgram::DeepgramProvider::new())),
        config::CUSTOM_WHISPER_PROVIDER => {
            let wc = custom_whisper_config.ok_or_else(|| {
                AppError::Config("Local / Custom Whisper is missing base URL or model".to_string())
            })?;
            Ok(match client {
                Some(ref c) => Box::new(WhisperCompatProvider::with_client(wc, c.clone())),
                None => Box::new(WhisperCompatProvider::new(wc)),
            })
        }
        name => {
            // All Whisper-compatible providers share the same HTTP upload logic.
            // Config is centralised in config::build_known_whisper_config.
            let wc = config::build_known_whisper_config(name)
                .ok_or_else(|| AppError::Config(format!("Unknown STT provider: {}", name)))?;
            Ok(match client {
                Some(ref c) => Box::new(WhisperCompatProvider::with_client(wc, c.clone())),
                None => Box::new(WhisperCompatProvider::new(wc)),
            })
        }
    }
}

/// Re-transcribe a saved recording by uploading the encoded file directly to
/// the configured Whisper-compatible STT provider. Returns the new transcript.
///
/// Only Whisper-compatible providers (glm-asr, openai-whisper, groq-whisper,
/// siliconflow, custom-whisper) are supported; streaming providers (deepgram,
/// assemblyai, cloud) return an error.
pub async fn retranscribe_file(
    config: &crate::storage::AppConfig,
    audio: Vec<u8>,
    file_ext: &str,
    client: reqwest::Client,
) -> Result<String, AppError> {
    let provider_name = config.stt_provider.as_str();

    let wc_config = if provider_name == config::CUSTOM_WHISPER_PROVIDER {
        config::build_custom_whisper_config(&config.stt_custom_base_url, &config.stt_custom_model)
            .map_err(AppError::Config)?
    } else {
        config::build_known_whisper_config(provider_name).ok_or_else(|| {
            AppError::Config(format!(
                "Re-transcription is only supported for Whisper-compatible providers; '{provider_name}' is not supported"
            ))
        })?
    };

    let api_key = if provider_name == config::CUSTOM_WHISPER_PROVIDER {
        config.stt_custom_api_key.clone()
    } else {
        config.stt_api_key.clone()
    };

    let stt_config = SttConfig {
        api_key,
        language: if config.stt_language == "multi" {
            None
        } else {
            Some(config.stt_language.clone())
        },
        smart_format: true,
        sample_rate: 16000,
    };

    let (filename, mime) = match file_ext.to_ascii_lowercase().as_str() {
        "flac" => ("audio.flac", "audio/flac"),
        "mp3" => ("audio.mp3", "audio/mpeg"),
        _ => ("audio.wav", "audio/wav"),
    };

    let provider = WhisperCompatProvider::with_client(wc_config, client);
    let text = provider
        .transcribe_encoded(audio, filename, mime, &stt_config)
        .await?;
    Ok(text.unwrap_or_default())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn custom_whisper_requires_explicit_config() {
        let result = create_provider(config::CUSTOM_WHISPER_PROVIDER, None, None);
        assert!(result.is_err());
    }

    #[test]
    fn custom_whisper_uses_explicit_config() {
        let cfg = config::build_custom_whisper_config(
            "http://localhost:8000/v1",
            "Systran/faster-whisper-large-v3",
        )
        .unwrap();

        let provider = create_provider(config::CUSTOM_WHISPER_PROVIDER, Some(cfg), None).unwrap();
        assert_eq!(provider.name(), config::CUSTOM_WHISPER_PROVIDER);
    }

    #[test]
    fn unknown_stt_provider_returns_error() {
        let result = create_provider("not-a-provider", None, None);
        assert!(result.is_err());
    }

    #[tokio::test]
    async fn retranscribe_rejects_streaming_provider() {
        let config = crate::storage::AppConfig {
            stt_provider: "deepgram".to_string(),
            ..crate::storage::AppConfig::default()
        };
        // Errors at provider construction, before any network call.
        let result =
            retranscribe_file(&config, vec![1, 2, 3], "flac", reqwest::Client::new()).await;
        assert!(result.is_err());
    }
}
