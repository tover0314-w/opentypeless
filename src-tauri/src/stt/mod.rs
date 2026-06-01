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
    client: Option<reqwest::Client>,
) -> Box<dyn SttProvider> {
    match provider_name {
        "cloud" => {
            let api_base_url = crate::api_base_url();
            match client {
                Some(ref c) => Box::new(cloud::CloudSttProvider::with_client(
                    api_base_url,
                    c.clone(),
                )),
                None => Box::new(cloud::CloudSttProvider::new(api_base_url)),
            }
        }
        "assemblyai" => Box::new(assemblyai::AssemblyAiProvider::new()),
        "deepgram" => match client {
            Some(ref c) => Box::new(deepgram::DeepgramProvider::with_client(c.clone())),
            None => Box::new(deepgram::DeepgramProvider::new()),
        },
        name => {
            // All Whisper-compatible providers share the same HTTP upload logic.
            // Config is centralised in config::get_whisper_config.
            let cfg = config::get_whisper_config(name)
                .or_else(|| config::get_whisper_config("glm-asr"))
                .expect("glm-asr config must always exist");
            let wc = WhisperCompatConfig {
                provider_name: name.to_string(),
                endpoint: cfg.endpoint.to_string(),
                model: cfg.model.to_string(),
                extra_fields: cfg
                    .extra_fields
                    .iter()
                    .map(|(k, v)| (k.to_string(), v.to_string()))
                    .collect(),
            };
            match client {
                Some(ref c) => Box::new(WhisperCompatProvider::with_client(wc, c.clone())),
                None => Box::new(WhisperCompatProvider::new(wc)),
            }
        }
    }
}
