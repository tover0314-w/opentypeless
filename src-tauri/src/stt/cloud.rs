use async_trait::async_trait;
use std::collections::HashMap;
use std::hash::{Hash, Hasher};
use std::sync::{Mutex, OnceLock};

use crate::error::{managed_cloud_error, AppError};
use crate::with_desktop_client_version;

use super::managed_audio::{
    encode_pcm_to_ogg_opus, select_managed_payload, ManagedAudioEncoderWorker,
    ManagedAudioPayloadKind,
};
use super::whisper_compat::WhisperCompatProvider;
use super::{SttConfig, SttProvider, TranscriptEvent};

/// Cloud STT provider that proxies audio through the talkmore-web API.
/// Auth token is passed via the api_key field. Quota is enforced server-side.
pub struct CloudSttProvider {
    stt_config: Option<SttConfig>,
    audio_buffer: Vec<u8>,
    managed_audio_worker: Option<ManagedAudioEncoderWorker>,
    managed_audio_failed: bool,
    client: reqwest::Client,
    api_base_url: String,
}

/// Max audio buffer: ~24 MB PCM ≈ 12.5 min at 16kHz 16-bit mono.
const MAX_AUDIO_BYTES: usize = 24 * 1024 * 1024;
const WAV_HEADER_BYTES: u64 = 44;
const MANAGED_INTENT_WARMUP_INTERVAL: std::time::Duration = std::time::Duration::from_secs(4 * 60);
const MANAGED_INTENT_WARMUP_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(5);

#[derive(Default)]
struct ManagedIntentWarmupGate {
    sessions: HashMap<u64, ManagedIntentWarmupEntry>,
}

struct ManagedIntentWarmupEntry {
    last_attempt: std::time::Instant,
    in_flight: bool,
}

impl ManagedIntentWarmupGate {
    fn try_begin(&mut self, session_key: u64, now: std::time::Instant) -> bool {
        self.sessions.retain(|_, entry| {
            entry.in_flight
                || now.saturating_duration_since(entry.last_attempt)
                    < MANAGED_INTENT_WARMUP_INTERVAL * 2
        });
        if let Some(entry) = self.sessions.get_mut(&session_key) {
            if entry.in_flight
                || now.saturating_duration_since(entry.last_attempt)
                    < MANAGED_INTENT_WARMUP_INTERVAL
            {
                return false;
            }
            entry.last_attempt = now;
            entry.in_flight = true;
        } else {
            self.sessions.insert(
                session_key,
                ManagedIntentWarmupEntry {
                    last_attempt: now,
                    in_flight: true,
                },
            );
        }
        true
    }

    fn finish(&mut self, session_key: u64) {
        if let Some(entry) = self.sessions.get_mut(&session_key) {
            entry.in_flight = false;
        }
    }
}

fn managed_intent_warmup_gate() -> &'static Mutex<ManagedIntentWarmupGate> {
    static GATE: OnceLock<Mutex<ManagedIntentWarmupGate>> = OnceLock::new();
    GATE.get_or_init(|| Mutex::new(ManagedIntentWarmupGate::default()))
}

/// Wake the managed account/quota compute only for real Cloud use. This is
/// deliberately detached from audio readiness and recording finalization.
pub fn warm_managed_cloud_on_intent(client: reqwest::Client, session_token: String) {
    if session_token.trim().is_empty() {
        return;
    }
    let mut token_hasher = std::collections::hash_map::DefaultHasher::new();
    session_token.hash(&mut token_hasher);
    let session_key = token_hasher.finish();
    let should_start = managed_intent_warmup_gate()
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .try_begin(session_key, std::time::Instant::now());
    if !should_start {
        tracing::debug!("Managed Cloud intent warm-up skipped (recent or already running)");
        return;
    }

    tokio::spawn(async move {
        let result = with_desktop_client_version(
            client.get(format!("{}/api/subscription/status", crate::api_base_url())),
        )
        .header("Authorization", format!("Bearer {session_token}"))
        .timeout(MANAGED_INTENT_WARMUP_TIMEOUT)
        .send()
        .await;
        match result {
            Ok(response) => {
                let status = response.status();
                let _ = response.bytes().await;
                tracing::debug!(%status, "Managed Cloud intent warm-up completed");
            }
            Err(error) => {
                tracing::debug!(
                    "Managed Cloud intent warm-up failed without blocking capture: {error}"
                );
            }
        }
        managed_intent_warmup_gate()
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .finish(session_key);
    });
}

struct CloudAudioPayload {
    bytes: Vec<u8>,
    file_name: &'static str,
    mime_type: &'static str,
}

fn stream_serial(operation_id: Option<&str>) -> u32 {
    let mut hash = 0x811c_9dc5u32;
    for byte in operation_id.unwrap_or("opentypeless-cloud-stt").bytes() {
        hash ^= u32::from(byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash.max(1)
}

fn pcm_duration_seconds(pcm_bytes: usize, sample_rate: u32) -> u32 {
    let bytes_per_second = u64::from(sample_rate).saturating_mul(2).max(1);
    (pcm_bytes as u64)
        .div_ceil(bytes_per_second)
        .min(u64::from(u32::MAX)) as u32
}

fn wav_safe_seconds(config: &SttConfig) -> Option<u32> {
    let managed = config.managed_audio?;
    let pcm_budget = managed
        .preferred_wav_max_bytes
        .saturating_sub(WAV_HEADER_BYTES);
    let bytes_per_second = u64::from(config.sample_rate).checked_mul(2)?;
    u32::try_from(pcm_budget / bytes_per_second)
        .ok()
        .filter(|seconds| *seconds > 0)
}

fn cloud_request_timeout(duration_seconds: u32, payload_bytes: usize) -> std::time::Duration {
    if duration_seconds <= 60 {
        return std::time::Duration::from_secs(60);
    }
    let upload_allowance = (payload_bytes as u64).div_ceil(32_000);
    std::time::Duration::from_secs((60 + upload_allowance).clamp(60, 180))
}

fn contains_quota_marker(value: &str) -> bool {
    let value = value.to_ascii_lowercase();
    value.contains("quota")
        || value.contains("limit exceeded")
        || value.contains("usage exceeded")
        || value.contains("byok")
}

fn forbidden_error_message(value: &serde_json::Value) -> Option<String> {
    value
        .get("error")
        .and_then(|v| v.as_str())
        .or_else(|| value.get("message").and_then(|v| v.as_str()))
        .or_else(|| {
            value
                .get("error")
                .and_then(|v| v.get("message"))
                .and_then(|v| v.as_str())
        })
        .map(String::from)
}

fn quota_message_from_value(value: &serde_json::Value) -> Option<String> {
    for field in ["code", "error_code", "type"] {
        if value
            .get(field)
            .and_then(|v| v.as_str())
            .is_some_and(contains_quota_marker)
        {
            return Some(
                forbidden_error_message(value)
                    .unwrap_or_else(|| "Cloud STT quota exceeded".to_string()),
            );
        }
    }

    for field in ["error", "message"] {
        if let Some(item) = value.get(field) {
            if let Some(message) = item.as_str() {
                if contains_quota_marker(message) {
                    return Some(message.to_string());
                }
            } else if let Some(message) = quota_message_from_value(item) {
                return Some(message);
            }
        }
    }

    None
}

fn cloud_stt_forbidden_error(body: &str) -> AppError {
    let parsed = serde_json::from_str::<serde_json::Value>(body).ok();

    if let Some(value) = parsed.as_ref() {
        if let Some(message) = quota_message_from_value(value) {
            return AppError::Quota(message);
        }
    }

    AppError::Auth("Cloud STT access denied".to_string())
}

impl CloudSttProvider {
    pub fn new(api_base_url: String) -> Self {
        Self {
            stt_config: None,
            audio_buffer: Vec::new(),
            managed_audio_worker: None,
            managed_audio_failed: false,
            client: reqwest::Client::new(),
            api_base_url,
        }
    }

    pub fn with_client(api_base_url: String, client: reqwest::Client) -> Self {
        Self {
            stt_config: None,
            audio_buffer: Vec::new(),
            managed_audio_worker: None,
            managed_audio_failed: false,
            client,
            api_base_url,
        }
    }

    async fn build_payload(
        &mut self,
        pcm: Vec<u8>,
        config: &SttConfig,
    ) -> Result<CloudAudioPayload, AppError> {
        let wav = WhisperCompatProvider::build_wav(&pcm, config.sample_rate);
        let Some(managed_config) = config.managed_audio else {
            self.managed_audio_worker.take();
            return Ok(CloudAudioPayload {
                bytes: wav,
                file_name: "audio.wav",
                mime_type: "audio/wav",
            });
        };

        match select_managed_payload(wav.len(), managed_config) {
            ManagedAudioPayloadKind::Wav => {
                // Preserve the historical short-recording payload byte for byte.
                // Dropping the sender lets the worker exit without delaying stop().
                self.managed_audio_worker.take();
                Ok(CloudAudioPayload {
                    bytes: wav,
                    file_name: "audio.wav",
                    mime_type: "audio/wav",
                })
            }
            ManagedAudioPayloadKind::OggOpus => {
                let input_samples = (pcm.len() / 2) as u64;
                let worker_result = match self.managed_audio_worker.take() {
                    Some(worker) if !self.managed_audio_failed => Some(worker.finish().await),
                    Some(_) | None => None,
                };
                let encoded = match worker_result {
                    Some(Ok(encoded)) => encoded,
                    Some(Err(error)) => {
                        tracing::warn!(
                            code = error.code,
                            "Managed Opus worker failed; retrying once from retained PCM: {}",
                            error
                        );
                        encode_managed_audio_fallback(
                            pcm,
                            stream_serial(config.operation_id.as_deref()),
                            managed_config,
                        )
                        .await?
                    }
                    None => {
                        encode_managed_audio_fallback(
                            pcm,
                            stream_serial(config.operation_id.as_deref()),
                            managed_config,
                        )
                        .await?
                    }
                };
                if encoded.original_samples < input_samples {
                    tracing::warn!(
                        accepted_samples = encoded.original_samples,
                        input_samples,
                        "Managed Opus reached its negotiated byte cap; submitting one valid prefix"
                    );
                }
                Ok(CloudAudioPayload {
                    bytes: encoded.bytes,
                    file_name: "audio.ogg",
                    mime_type: "audio/ogg; codecs=opus",
                })
            }
        }
    }
}

async fn encode_managed_audio_fallback(
    pcm: Vec<u8>,
    serial: u32,
    config: super::managed_audio::ManagedAudioEncodingConfig,
) -> Result<super::managed_audio::EncodedManagedAudio, AppError> {
    tokio::task::spawn_blocking(move || encode_pcm_to_ogg_opus(&pcm, serial, config))
        .await
        .map_err(|error| {
            AppError::Config(format!(
                "managed_audio_encode_failed: fallback task failed: {error}"
            ))
        })?
        .map_err(|error| AppError::Config(format!("{}: {}", error.code, error)))
}

#[async_trait]
impl SttProvider for CloudSttProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        if config.api_key.is_empty() {
            return Err(AppError::Auth(
                "Cloud STT: session token is missing. Please sign in first.".to_string(),
            ));
        }
        self.stt_config = Some(config.clone());
        self.audio_buffer.clear();
        self.managed_audio_worker.take();
        self.managed_audio_failed = false;
        if let Some(managed_config) = config.managed_audio {
            if config.sample_rate != 16_000 {
                tracing::warn!(
                    sample_rate = config.sample_rate,
                    "Managed Opus requires 16 kHz input; retaining the safe WAV fallback"
                );
                self.managed_audio_failed = true;
            } else {
                match ManagedAudioEncoderWorker::start(
                    stream_serial(config.operation_id.as_deref()),
                    managed_config,
                ) {
                    Ok(worker) => self.managed_audio_worker = Some(worker),
                    Err(error) => {
                        tracing::warn!(
                            code = error.code,
                            "Managed Opus initialization failed; retaining the safe WAV fallback: {}",
                            error
                        );
                        self.managed_audio_failed = true;
                    }
                }
            }
        }
        tracing::info!("Cloud STT provider ready (buffering mode)");
        Ok(())
    }

    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        if self.audio_buffer.len() + chunk.len() > MAX_AUDIO_BYTES {
            return Err(AppError::Config(
                "Cloud STT: audio exceeds maximum length (~12 min)".to_string(),
            ));
        }
        self.audio_buffer.extend_from_slice(chunk);
        if let Some(worker) = self.managed_audio_worker.as_ref() {
            if let Err(error) = worker.try_send_pcm(chunk) {
                tracing::warn!(
                    code = error.code,
                    "Managed Opus worker fell behind; retaining PCM for one stop-time retry: {}",
                    error
                );
                self.managed_audio_failed = true;
                self.managed_audio_worker.take();
            }
        }
        Ok(())
    }

    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError> {
        // Cloud STT is file-upload based and returns the final transcript from
        // disconnect(); keep this future pending so the pipeline select loop
        // waits for audio chunks instead of busy-spinning while recording.
        std::future::pending().await
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        let config = match &self.stt_config {
            Some(c) => c.clone(),
            None => return Ok(None),
        };

        if self.audio_buffer.is_empty() {
            self.managed_audio_worker.take();
            tracing::info!("Cloud STT: no audio buffered, skipping");
            return Ok(None);
        }

        let pcm = std::mem::take(&mut self.audio_buffer);
        let audio_len_secs = pcm.len() as f64 / (config.sample_rate as f64 * 2.0);
        let duration_seconds = pcm_duration_seconds(pcm.len(), config.sample_rate);
        let payload = self.build_payload(pcm, &config).await?;
        let request_timeout = cloud_request_timeout(duration_seconds, payload.bytes.len());
        tracing::info!(
            "Cloud STT: sending {:.1}s of audio for transcription as {} ({} bytes)",
            audio_len_secs,
            payload.mime_type,
            payload.bytes.len()
        );

        let mut attempt = 0u32;
        loop {
            let file_part = reqwest::multipart::Part::bytes(payload.bytes.clone())
                .file_name(payload.file_name)
                .mime_str(payload.mime_type)
                .map_err(|e| AppError::Config(e.to_string()))?;

            let mut form = reqwest::multipart::Form::new().part("audio", file_part);

            if let Some(ref lang) = config.language {
                form = form.text("language", lang.clone());
            }
            if let Some(operation_id) = config.operation_id.as_deref() {
                form = form
                    .text("operationId", operation_id.to_string())
                    .text("stageKey", format!("{operation_id}:stt"))
                    .text("requestType", "voice_pipeline")
                    .text("clientVersion", crate::desktop_client_version());
            }

            let resp_result = with_desktop_client_version(
                self.client
                    .post(format!("{}/api/proxy/stt", self.api_base_url)),
            )
            .header("Authorization", format!("Bearer {}", config.api_key))
            .multipart(form)
            .timeout(request_timeout)
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

                        tracing::info!("Cloud STT transcription: {} chars", text.len());

                        return Ok(if text.is_empty() { None } else { Some(text) });
                    } else if let Some(error) = managed_cloud_error(status.as_u16(), &body) {
                        return Err(error);
                    } else if status.as_u16() == 403 {
                        return Err(cloud_stt_forbidden_error(&body));
                    } else if status.as_u16() >= 500 && attempt < 2 {
                        let truncate_at = body
                            .char_indices()
                            .take_while(|&(i, _)| i < 200)
                            .last()
                            .map(|(i, c)| i + c.len_utf8())
                            .unwrap_or(body.len());
                        tracing::warn!(
                            "Cloud STT server error {} (attempt {}/3): {}",
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
                        let truncate_at = body
                            .char_indices()
                            .take_while(|&(i, _)| i < 200)
                            .last()
                            .map(|(i, c)| i + c.len_utf8())
                            .unwrap_or(body.len());
                        let sanitized = &body[..truncate_at];
                        tracing::error!("Cloud STT HTTP {}: {}", status, sanitized);
                        return Err(AppError::Api {
                            status: status.as_u16(),
                            body: sanitized.to_string(),
                        });
                    }
                }
                Err(e) if e.is_timeout() && attempt < 2 => {
                    tracing::warn!("Cloud STT timeout (attempt {}/3)", attempt + 1);
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
        "Cloud"
    }

    fn recording_limit_override_seconds(&self) -> Option<u32> {
        let config = self.stt_config.as_ref()?;
        let worker_unavailable =
            config.managed_audio.is_some() && self.managed_audio_worker.is_none();
        (self.managed_audio_failed || worker_unavailable)
            .then(|| wav_safe_seconds(config).unwrap_or(30))
    }

    fn recording_limit_override_explanation_key(&self) -> Option<&'static str> {
        self.recording_limit_override_seconds()
            .map(|_| "recordingLimits.reasons.encoderFallback")
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn managed_config() -> super::super::managed_audio::ManagedAudioEncodingConfig {
        super::super::managed_audio::ManagedAudioEncodingConfig::default()
    }

    fn stt_config() -> SttConfig {
        SttConfig {
            api_key: "session-token".to_string(),
            language: None,
            smart_format: true,
            sample_rate: 16_000,
            resource_id: None,
            operation_id: Some("operation-1".to_string()),
            managed_audio: Some(managed_config()),
            provider_region: None,
        }
    }

    #[test]
    fn helper_boundaries_preserve_short_wav_and_scale_long_request_timeout() {
        let config = stt_config();
        assert_eq!(wav_safe_seconds(&config), Some(109));
        assert_eq!(pcm_duration_seconds(32_000, 16_000), 1);
        assert_eq!(pcm_duration_seconds(32_001, 16_000), 2);
        assert_eq!(cloud_request_timeout(60, 4_000_000).as_secs(), 60);
        assert_eq!(cloud_request_timeout(600, 4_000_000).as_secs(), 180);
    }

    #[test]
    fn managed_intent_warmup_is_single_flight_and_rate_limited_to_real_use() {
        let start = std::time::Instant::now();
        let mut gate = ManagedIntentWarmupGate::default();

        assert!(gate.try_begin(1, start));
        assert!(!gate.try_begin(1, start + std::time::Duration::from_secs(10)));
        assert!(gate.try_begin(2, start + std::time::Duration::from_secs(10)));
        gate.finish(1);
        assert!(!gate.try_begin(1, start + std::time::Duration::from_secs(239)));
        assert!(gate.try_begin(1, start + std::time::Duration::from_secs(240)));
    }

    #[tokio::test]
    async fn managed_payload_keeps_short_audio_as_exact_historical_wav() {
        let config = stt_config();
        let pcm = vec![0; 32_000];
        let historical = WhisperCompatProvider::build_wav(&pcm, 16_000);
        let mut provider = CloudSttProvider::new("https://example.test".to_string());
        provider.connect(&config).await.unwrap();
        provider.send_audio(&pcm).await.unwrap();

        let payload = provider.build_payload(pcm, &config).await.unwrap();
        assert_eq!(payload.file_name, "audio.wav");
        assert_eq!(payload.mime_type, "audio/wav");
        assert_eq!(payload.bytes, historical);
    }

    #[tokio::test]
    async fn managed_payload_uses_bounded_ogg_opus_above_the_wav_threshold() {
        let config = stt_config();
        let pcm = vec![0; 3_500_000];
        let mut provider = CloudSttProvider::new("https://example.test".to_string());
        provider.connect(&config).await.unwrap();
        provider.send_audio(&pcm).await.unwrap();

        let payload = provider.build_payload(pcm, &config).await.unwrap();
        assert_eq!(payload.file_name, "audio.ogg");
        assert_eq!(payload.mime_type, "audio/ogg; codecs=opus");
        assert!(payload.bytes.starts_with(b"OggS"));
        assert!(payload.bytes.len() <= managed_config().max_audio_bytes as usize);
    }

    #[test]
    fn forbidden_error_uses_quota_code() {
        let err = cloud_stt_forbidden_error(r#"{"code":"stt_quota_exceeded","error":"limit hit"}"#);
        assert!(matches!(err, AppError::Quota(_)));
    }

    #[test]
    fn forbidden_error_uses_quota_message() {
        let err = cloud_stt_forbidden_error(
            r#"{"error":"STT quota exceeded. Please switch to BYOK mode."}"#,
        );
        assert!(matches!(err, AppError::Quota(_)));
    }

    #[test]
    fn forbidden_error_uses_nested_quota_message() {
        let err = cloud_stt_forbidden_error(
            r#"{"error":{"code":"stt_quota_exceeded","message":"STT quota exceeded"}}"#,
        );
        assert!(matches!(err, AppError::Quota(_)));
    }

    #[test]
    fn forbidden_error_empty_body_is_auth_not_quota() {
        let err = cloud_stt_forbidden_error("");
        assert!(matches!(err, AppError::Auth(_)));
    }

    #[test]
    fn forbidden_error_unknown_json_is_auth_not_quota() {
        let err = cloud_stt_forbidden_error(r#"{"error":"Forbidden"}"#);
        assert!(matches!(err, AppError::Auth(_)));
    }

    #[tokio::test]
    async fn recv_transcript_waits_for_buffered_cloud_provider() {
        let mut provider = CloudSttProvider::new("https://example.test".to_string());

        let result = tokio::time::timeout(
            std::time::Duration::from_millis(20),
            provider.recv_transcript(),
        )
        .await;

        assert!(result.is_err());
    }
}
