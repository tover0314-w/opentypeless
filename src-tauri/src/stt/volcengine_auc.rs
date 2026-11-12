//! Doubao (Volcengine) "recording file" ASR — v3 AUC big-model REST API.
//!
//! This is a different product from the streaming SAUC WebSocket provider in
//! `volcengine.rs`: audio is uploaded as one file, not as a live PCM stream.
//!
//! Two shapes are supported, chosen by resource id:
//!
//! * `volc.bigasr.auc_turbo` (极速版): one `recognize/flash` POST returns the
//!   transcript synchronously.
//! * `volc.seedasr.auc` / `volc.bigasr.auc` (标准版): `submit` creates a task
//!   keyed by our own `X-Api-Request-Id`, then `query` is polled until it
//!   returns a result.
//!
//! Auth accepts either a new-console API key (`X-Api-Key`) or the old
//! console `app_id:access_token` pair.

use async_trait::async_trait;
use base64::Engine as _;
use std::time::Duration;

use crate::error::AppError;

use super::whisper_compat::WhisperCompatProvider;
use super::{SttConfig, SttProvider, TranscriptEvent};

pub const VOLCENGINE_AUC_PROVIDER: &str = "volcengine-auc";
pub const VOLCENGINE_AUC_RESOURCE_SEED_ASR: &str = "volc.seedasr.auc";
pub const VOLCENGINE_AUC_RESOURCE_BIG_ASR: &str = "volc.bigasr.auc";
pub const VOLCENGINE_AUC_RESOURCE_TURBO: &str = "volc.bigasr.auc_turbo";

const AUC_BASE_URL: &str = "https://openspeech.bytedance.com";
const SUBMIT_PATH: &str = "/api/v3/auc/bigmodel/submit";
const QUERY_PATH: &str = "/api/v3/auc/bigmodel/query";
const FLASH_PATH: &str = "/api/v3/auc/bigmodel/recognize/flash";

const STATUS_OK: &str = "20000000";
const STATUS_PROCESSING: [&str; 2] = ["20000001", "20000002"];
/// Submitted fine, but the clip carried no speech (silence / mic muted).
const STATUS_NO_SPEECH: &str = "20000003";

/// Maximum buffered PCM: ~24 MB ≈ 12.5 min at 16 kHz 16-bit mono.
const MAX_AUDIO_BYTES: usize = 24 * 1024 * 1024;
const SUBMIT_TIMEOUT: Duration = Duration::from_secs(30);
const FLASH_TIMEOUT: Duration = Duration::from_secs(120);
const QUERY_TIMEOUT: Duration = Duration::from_secs(30);
const POLL_INTERVAL: Duration = Duration::from_millis(400);
/// 标准版 normally answers short clips within a couple of seconds, but the
/// service promises "3h内返回", so keep a hard ceiling and fail loudly.
const POLL_DEADLINE: Duration = Duration::from_secs(90);
/// Outer bound used by the pipeline finalize wait.
pub const FINALIZE_TIMEOUT: Duration = Duration::from_secs(110);

pub struct VolcengineAucProvider {
    stt_config: Option<SttConfig>,
    audio_buffer: Vec<u8>,
    client: reqwest::Client,
    base_url: String,
}

impl Default for VolcengineAucProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl VolcengineAucProvider {
    pub fn new() -> Self {
        Self {
            stt_config: None,
            audio_buffer: Vec::new(),
            client: reqwest::Client::new(),
            base_url: AUC_BASE_URL.to_string(),
        }
    }

    #[cfg(test)]
    fn with_base_url(base_url: String) -> Self {
        let mut provider = Self::new();
        provider.base_url = base_url;
        provider
    }

    fn endpoint(&self, path: &str) -> String {
        format!("{}{}", self.base_url.trim_end_matches('/'), path)
    }
}

/// Streaming (SAUC) resource ids are a different product; if a config still
/// carries one, map it to the equivalent recording-file resource instead of
/// failing at the API.
fn normalize_resource_id(resource_id: &str) -> String {
    match resource_id {
        "volc.seedasr.sauc.duration" => VOLCENGINE_AUC_RESOURCE_SEED_ASR.to_string(),
        "volc.bigasr.sauc.duration" => VOLCENGINE_AUC_RESOURCE_BIG_ASR.to_string(),
        other => other.to_string(),
    }
}

fn resolve_resource_id(config: &SttConfig) -> String {
    normalize_resource_id(
        config
            .resource_id
            .as_deref()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .unwrap_or(VOLCENGINE_AUC_RESOURCE_SEED_ASR),
    )
}

fn is_flash_resource(resource_id: &str) -> bool {
    resource_id == VOLCENGINE_AUC_RESOURCE_TURBO
}

fn apply_auth_headers(builder: reqwest::RequestBuilder, api_key: &str) -> reqwest::RequestBuilder {
    match api_key.split_once(':') {
        Some((app_key, access_key)) => builder
            .header("X-Api-App-Key", app_key.trim())
            .header("X-Api-Access-Key", access_key.trim()),
        None => builder.header("X-Api-Key", api_key.trim()),
    }
}

fn language_field(config: &SttConfig) -> Option<String> {
    match config.language.as_deref().map(str::trim) {
        Some("") | None | Some("multi") => None,
        Some("zh") => Some("zh-CN".to_string()),
        Some("en") => Some("en-US".to_string()),
        Some("ja") => Some("ja-JP".to_string()),
        Some("ko") => Some("ko-KR".to_string()),
        Some(other) => Some(other.to_string()),
    }
}

pub(crate) fn build_request_body(config: &SttConfig, wav: &[u8]) -> serde_json::Value {
    let audio_data = base64::engine::general_purpose::STANDARD.encode(wav);
    let language = language_field(config);

    let mut audio = serde_json::json!({
        "format": "wav",
        "codec": "raw",
        "rate": config.sample_rate,
        "bits": 16,
        "channel": 1,
        "data": audio_data,
    });
    if let Some(language) = &language {
        audio["language"] = serde_json::Value::String(language.clone());
    }

    // `enable_ddc` is the semantic smoothing pass: it drops filler words and
    // repeat/stutter artifacts, which is what dictation wants. It is only
    // requested when smart formatting is on so a raw transcript stays raw.
    let mut request = serde_json::json!({
        "model_name": "bigmodel",
        "enable_itn": config.smart_format,
        "enable_punc": true,
        "enable_ddc": config.smart_format,
        "show_utterances": true,
    });
    if language.is_none() {
        request["enable_auto_lang"] = serde_json::Value::Bool(true);
    }

    serde_json::json!({
        "user": { "uid": "opentypeless" },
        "audio": audio,
        "request": request,
    })
}

fn extract_transcript(value: &serde_json::Value) -> String {
    value["result"]["text"]
        .as_str()
        .map(str::trim)
        .filter(|text| !text.is_empty())
        .map(str::to_string)
        .or_else(|| {
            let utterances = value["result"]["utterances"].as_array()?;
            let joined: String = utterances
                .iter()
                .filter_map(|item| item["text"].as_str())
                .collect();
            (!joined.trim().is_empty()).then(|| joined.trim().to_string())
        })
        .unwrap_or_default()
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum ApiStatus {
    Ok,
    Processing,
    Failed { code: String, message: String },
}

fn classify(status_code: Option<&str>, message: Option<&str>) -> ApiStatus {
    let code = status_code.unwrap_or("").trim().to_string();
    let message = message.unwrap_or("").trim().to_string();

    if code.is_empty() {
        // Flash responses on some accounts omit the status header on success.
        return ApiStatus::Ok;
    }
    if code == STATUS_OK {
        return ApiStatus::Ok;
    }
    if STATUS_PROCESSING.contains(&code.as_str()) {
        return ApiStatus::Processing;
    }
    ApiStatus::Failed {
        code,
        message: if message.is_empty() {
            "Doubao ASR request failed".to_string()
        } else {
            message
        },
    }
}

fn header_value(response: &reqwest::Response, name: &str) -> Option<String> {
    response
        .headers()
        .get(name)
        .and_then(|value| value.to_str().ok())
        .map(str::to_string)
}

fn is_no_speech(status: &ApiStatus) -> bool {
    matches!(status, ApiStatus::Failed { code, .. } if code == STATUS_NO_SPEECH)
}

fn status_error(status: ApiStatus, resource_id: &str) -> AppError {
    match status {
        ApiStatus::Failed { code, message } => AppError::Config(format!(
            "Doubao ASR ({resource_id}) rejected the request: {message} [{code}]"
        )),
        _ => AppError::Config(format!("Doubao ASR ({resource_id}) failed")),
    }
}

fn http_error(status: reqwest::StatusCode, body: String) -> AppError {
    if status == reqwest::StatusCode::UNAUTHORIZED || status == reqwest::StatusCode::FORBIDDEN {
        // The service answers `{"header":{"code":45000010,"message":"Invalid X-Api-Key"}}`;
        // surfacing that verbatim saves a round of guessing.
        let detail = serde_json::from_str::<serde_json::Value>(&body)
            .ok()
            .and_then(|value| {
                value["header"]["message"]
                    .as_str()
                    .or_else(|| value["message"].as_str())
                    .map(str::to_string)
            });
        AppError::Auth(match detail {
            Some(detail) => format!(
                "Doubao ASR authentication failed: {detail}. Use a Volcengine Speech API key, or old-console app_id:access_token, and enable the recording-file ASR model for that app."
            ),
            None => "Doubao ASR authentication failed. Use a Volcengine Speech API key, or old-console app_id:access_token; and make sure the recording-file ASR model is enabled for that app."
                .to_string(),
        })
    } else {
        AppError::Api {
            status: status.as_u16(),
            body,
        }
    }
}

fn transport_error(error: reqwest::Error) -> AppError {
    if error.is_timeout() {
        AppError::Timeout(SUBMIT_TIMEOUT)
    } else {
        AppError::Network(error.to_string())
    }
}

#[derive(Debug, PartialEq, Eq)]
enum QueryOutcome {
    Pending,
    Transcript(String),
    NoSpeech,
}

#[derive(Debug)]
struct SubmittedTask {
    task_id: String,
    log_id: Option<String>,
}

impl VolcengineAucProvider {
    async fn submit(
        &self,
        config: &SttConfig,
        resource_id: &str,
        wav: &[u8],
    ) -> Result<SubmittedTask, AppError> {
        let task_id = uuid::Uuid::new_v4().to_string();
        let body = build_request_body(config, wav);

        let response = apply_auth_headers(
            self.client
                .post(self.endpoint(SUBMIT_PATH))
                .header("X-Api-Resource-Id", resource_id)
                .header("X-Api-Request-Id", &task_id)
                .header("X-Api-Sequence", "-1")
                .header("Content-Type", "application/json")
                .timeout(SUBMIT_TIMEOUT)
                .json(&body),
            &config.api_key,
        )
        .send()
        .await
        .map_err(transport_error)?;

        let status = response.status();
        let status_header = header_value(&response, "X-Api-Status-Code");
        let message_header = header_value(&response, "X-Api-Message");
        let log_id = header_value(&response, "X-Tt-Logid");
        let text = response.text().await.unwrap_or_default();

        if !status.is_success() {
            return Err(http_error(status, text));
        }

        match classify(status_header.as_deref(), message_header.as_deref()) {
            ApiStatus::Ok | ApiStatus::Processing => Ok(SubmittedTask { task_id, log_id }),
            failed => Err(status_error(failed, resource_id)),
        }
    }

    async fn query_once(
        &self,
        config: &SttConfig,
        resource_id: &str,
        task: &SubmittedTask,
    ) -> Result<QueryOutcome, AppError> {
        let mut builder = self
            .client
            .post(self.endpoint(QUERY_PATH))
            .header("X-Api-Resource-Id", resource_id)
            .header("X-Api-Request-Id", &task.task_id)
            .header("Content-Type", "application/json")
            .timeout(QUERY_TIMEOUT)
            .json(&serde_json::json!({}));
        if let Some(log_id) = &task.log_id {
            builder = builder.header("X-Tt-Logid", log_id);
        }

        let response = apply_auth_headers(builder, &config.api_key)
            .send()
            .await
            .map_err(transport_error)?;

        let status = response.status();
        let status_header = header_value(&response, "X-Api-Status-Code");
        let message_header = header_value(&response, "X-Api-Message");
        let text = response.text().await.unwrap_or_default();

        if !status.is_success() {
            return Err(http_error(status, text));
        }

        match classify(status_header.as_deref(), message_header.as_deref()) {
            ApiStatus::Processing => Ok(QueryOutcome::Pending),
            ApiStatus::Ok => {
                let value: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
                let transcript = extract_transcript(&value);
                if transcript.is_empty() {
                    Ok(QueryOutcome::NoSpeech)
                } else {
                    Ok(QueryOutcome::Transcript(transcript))
                }
            }
            status if is_no_speech(&status) => Ok(QueryOutcome::NoSpeech),
            failed => Err(status_error(failed, resource_id)),
        }
    }

    async fn recognize_flash(
        &self,
        config: &SttConfig,
        resource_id: &str,
        wav: &[u8],
    ) -> Result<Option<String>, AppError> {
        let body = build_request_body(config, wav);
        let request_id = uuid::Uuid::new_v4().to_string();

        let response = apply_auth_headers(
            self.client
                .post(self.endpoint(FLASH_PATH))
                .header("X-Api-Resource-Id", resource_id)
                .header("X-Api-Request-Id", &request_id)
                .header("X-Api-Sequence", "-1")
                .header("Content-Type", "application/json")
                .timeout(FLASH_TIMEOUT)
                .json(&body),
            &config.api_key,
        )
        .send()
        .await
        .map_err(transport_error)?;

        let status = response.status();
        let status_header = header_value(&response, "X-Api-Status-Code");
        let message_header = header_value(&response, "X-Api-Message");
        let text = response.text().await.unwrap_or_default();

        if !status.is_success() {
            return Err(http_error(status, text));
        }

        match classify(status_header.as_deref(), message_header.as_deref()) {
            ApiStatus::Ok | ApiStatus::Processing => {
                let value: serde_json::Value = serde_json::from_str(&text).unwrap_or_default();
                let transcript = extract_transcript(&value);
                Ok((!transcript.is_empty()).then_some(transcript))
            }
            status if is_no_speech(&status) => Ok(None),
            failed => Err(status_error(failed, resource_id)),
        }
    }
    /// Verify credentials/permissions without waiting for a transcript.
    ///
    /// A silent clip is submitted once; `20000003` (empty audio) still proves
    /// that the key, the resource id and the model entitlement are all valid.
    pub async fn probe(&self, config: &SttConfig) -> Result<(), AppError> {
        let resource_id = resolve_resource_id(config);
        let wav = WhisperCompatProvider::build_wav(&[0u8; 320], config.sample_rate);
        let path = if is_flash_resource(&resource_id) {
            FLASH_PATH
        } else {
            SUBMIT_PATH
        };
        let request_id = uuid::Uuid::new_v4().to_string();

        let response = apply_auth_headers(
            self.client
                .post(self.endpoint(path))
                .header("X-Api-Resource-Id", resource_id.as_str())
                .header("X-Api-Request-Id", &request_id)
                .header("X-Api-Sequence", "-1")
                .header("Content-Type", "application/json")
                .timeout(SUBMIT_TIMEOUT)
                .json(&build_request_body(config, &wav)),
            &config.api_key,
        )
        .send()
        .await
        .map_err(transport_error)?;

        let status = response.status();
        let status_header = header_value(&response, "X-Api-Status-Code");
        let message_header = header_value(&response, "X-Api-Message");
        let text = response.text().await.unwrap_or_default();

        if !status.is_success() {
            return Err(http_error(status, text));
        }

        match classify(status_header.as_deref(), message_header.as_deref()) {
            ApiStatus::Ok | ApiStatus::Processing => Ok(()),
            status if is_no_speech(&status) => Ok(()),
            failed => Err(status_error(failed, &resource_id)),
        }
    }
}

#[async_trait]
impl SttProvider for VolcengineAucProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        if config.api_key.trim().is_empty() {
            return Err(AppError::Auth("Doubao ASR API key is empty".to_string()));
        }
        self.stt_config = Some(config.clone());
        self.audio_buffer.clear();
        tracing::info!("Doubao recording-file ASR ready (buffering mode)");
        Ok(())
    }

    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        if self.audio_buffer.len() + chunk.len() > MAX_AUDIO_BYTES {
            return Err(AppError::Config(
                "Doubao ASR: audio exceeds the maximum buffered length (~12 min)".to_string(),
            ));
        }
        self.audio_buffer.extend_from_slice(chunk);
        Ok(())
    }

    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError> {
        // File-based provider: transcription happens in disconnect().
        std::future::pending().await
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        let Some(config) = self.stt_config.clone() else {
            return Ok(None);
        };
        if self.audio_buffer.is_empty() {
            return Ok(None);
        }

        let wav = WhisperCompatProvider::build_wav(&self.audio_buffer, config.sample_rate);
        self.audio_buffer.clear();
        let resource_id = resolve_resource_id(&config);
        tracing::info!(
            "Doubao ASR: uploading {:.1}s of audio with resource {}",
            wav.len() as f64 / (config.sample_rate as f64 * 2.0),
            resource_id
        );

        if is_flash_resource(&resource_id) {
            let transcript = self.recognize_flash(&config, &resource_id, &wav).await?;
            return Ok(transcript);
        }

        let task = self.submit(&config, &resource_id, &wav).await?;
        let deadline = tokio::time::Instant::now() + POLL_DEADLINE;
        loop {
            tokio::time::sleep(POLL_INTERVAL).await;
            match self.query_once(&config, &resource_id, &task).await? {
                QueryOutcome::Transcript(transcript) => return Ok(Some(transcript)),
                // Task finished without speech: stop polling instead of timing out.
                QueryOutcome::NoSpeech => return Ok(None),
                QueryOutcome::Pending => {}
            }
            if tokio::time::Instant::now() >= deadline {
                return Err(AppError::Timeout(POLL_DEADLINE));
            }
        }
    }

    fn name(&self) -> &str {
        "Doubao Recording File ASR"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::io::{AsyncReadExt, AsyncWriteExt};
    use tokio::net::TcpListener;

    fn test_config() -> SttConfig {
        SttConfig {
            api_key: "test-key".to_string(),
            language: None,
            smart_format: true,
            sample_rate: 16000,
            resource_id: None,
            operation_id: None,
            managed_audio: None,
            provider_region: None,
        }
    }

    #[test]
    fn defaults_to_seed_asr_and_keeps_custom_resource() {
        let config = test_config();
        assert_eq!(
            resolve_resource_id(&config),
            VOLCENGINE_AUC_RESOURCE_SEED_ASR
        );

        let mut custom = test_config();
        custom.resource_id = Some(" volc.bigasr.auc_turbo ".to_string());
        assert_eq!(resolve_resource_id(&custom), VOLCENGINE_AUC_RESOURCE_TURBO);
        assert!(is_flash_resource(&resolve_resource_id(&custom)));
        assert!(!is_flash_resource(VOLCENGINE_AUC_RESOURCE_SEED_ASR));
    }

    #[test]
    fn streaming_resource_ids_are_mapped_to_recording_file_ones() {
        let mut config = test_config();
        config.resource_id = Some("volc.seedasr.sauc.duration".to_string());
        assert_eq!(
            resolve_resource_id(&config),
            VOLCENGINE_AUC_RESOURCE_SEED_ASR
        );

        config.resource_id = Some("volc.bigasr.sauc.duration".to_string());
        assert_eq!(
            resolve_resource_id(&config),
            VOLCENGINE_AUC_RESOURCE_BIG_ASR
        );
    }

    #[test]
    fn request_body_embeds_wav_base64_and_dictation_flags() {
        let body = build_request_body(&test_config(), b"RIFFdata");

        assert_eq!(body["user"]["uid"], "opentypeless");
        assert_eq!(body["audio"]["format"], "wav");
        assert_eq!(body["audio"]["codec"], "raw");
        assert_eq!(body["audio"]["rate"], 16000);
        assert_eq!(body["audio"]["bits"], 16);
        assert_eq!(body["audio"]["channel"], 1);
        assert_eq!(body["audio"]["data"], "UklGRmRhdGE=");
        assert!(body["audio"]["language"].is_null());
        assert_eq!(body["request"]["model_name"], "bigmodel");
        assert_eq!(body["request"]["enable_itn"], true);
        assert_eq!(body["request"]["enable_punc"], true);
        assert_eq!(body["request"]["enable_ddc"], true);
        assert_eq!(body["request"]["enable_auto_lang"], true);
    }

    #[test]
    fn explicit_language_disables_auto_detection() {
        let mut config = test_config();
        config.language = Some("en".to_string());
        let body = build_request_body(&config, b"x");

        assert_eq!(body["audio"]["language"], "en-US");
        assert!(body["request"]["enable_auto_lang"].is_null());
    }

    #[test]
    fn classifies_processing_success_and_failure_codes() {
        assert_eq!(classify(Some("20000000"), Some("OK")), ApiStatus::Ok);
        assert_eq!(
            classify(Some("20000001"), Some("processing")),
            ApiStatus::Processing
        );
        assert_eq!(
            classify(Some("20000002"), Some("queued")),
            ApiStatus::Processing
        );
        assert_eq!(
            classify(Some("20000003"), Some("empty audio")),
            ApiStatus::Failed {
                code: "20000003".to_string(),
                message: "empty audio".to_string()
            }
        );
        // Flash success without the status header.
        assert_eq!(classify(None, None), ApiStatus::Ok);
    }

    #[test]
    fn extracts_result_text_or_joins_utterances() {
        let with_text = serde_json::json!({ "result": { "text": " 你好 " } });
        assert_eq!(extract_transcript(&with_text), "你好");

        let with_utterances = serde_json::json!({
            "result": { "utterances": [{ "text": "你好" }, { "text": "世界" }] }
        });
        assert_eq!(extract_transcript(&with_utterances), "你好世界");

        assert_eq!(extract_transcript(&serde_json::json!({})), "");
    }

    /// Status code, extra headers, and JSON body for one mocked response.
    type MockResponse = (u16, Vec<(&'static str, String)>, String);

    /// Minimal HTTP/1.1 server: replies with a canned response per request, in order.
    async fn spawn_mock_server(
        responses: Vec<MockResponse>,
    ) -> (String, tokio::task::JoinHandle<Vec<String>>) {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();
        let handle = tokio::spawn(async move {
            let mut seen = Vec::new();
            for (code, headers, body) in responses {
                let (mut stream, _) = listener.accept().await.unwrap();
                let mut buf = vec![0u8; 65536];
                let read = stream.read(&mut buf).await.unwrap();
                let request = String::from_utf8_lossy(&buf[..read]).to_lowercase();
                seen.push(request);

                let reason = if code == 200 { "OK" } else { "ERROR" };
                let mut response = format!("HTTP/1.1 {} {}\r\n", code, reason);
                for (key, value) in headers {
                    response.push_str(&format!("{}: {}\r\n", key, value));
                }
                response.push_str(&format!("Content-Length: {}\r\n", body.len()));
                response.push_str("Content-Type: application/json\r\n\r\n");
                response.push_str(&body);
                stream.write_all(response.as_bytes()).await.unwrap();
                stream.flush().await.unwrap();
            }
            seen
        });
        (format!("http://{}", addr), handle)
    }

    #[tokio::test]
    async fn submit_then_poll_returns_transcript() {
        let (base_url, server) = spawn_mock_server(vec![
            (
                200,
                vec![
                    ("X-Api-Status-Code", "20000000".to_string()),
                    ("X-Api-Message", "OK".to_string()),
                    ("X-Tt-Logid", "log-1".to_string()),
                ],
                "{}".to_string(),
            ),
            (
                200,
                vec![
                    ("X-Api-Status-Code", "20000002".to_string()),
                    ("X-Api-Message", "processing".to_string()),
                ],
                "{}".to_string(),
            ),
            (
                200,
                vec![
                    ("X-Api-Status-Code", "20000000".to_string()),
                    ("X-Api-Message", "OK".to_string()),
                ],
                serde_json::json!({ "result": { "text": "本地识别成功" } }).to_string(),
            ),
        ])
        .await;

        let mut provider = VolcengineAucProvider::with_base_url(base_url);
        let config = test_config();
        provider.connect(&config).await.unwrap();
        provider.send_audio(&[0u8; 320]).await.unwrap();
        let transcript = provider.disconnect().await.unwrap();

        assert_eq!(transcript.as_deref(), Some("本地识别成功"));

        let requests = server.await.unwrap();
        assert_eq!(requests.len(), 3);
        assert!(requests[0].contains("post /api/v3/auc/bigmodel/submit"));
        assert!(requests[0].contains("x-api-sequence: -1"));
        assert!(requests[0].contains("x-api-key: test-key"));
        assert!(requests[0].contains("x-api-resource-id: volc.seedasr.auc"));
        assert!(requests[1].contains("post /api/v3/auc/bigmodel/query"));
        assert!(requests[1].contains("x-tt-logid: log-1"));
    }

    #[tokio::test]
    async fn flash_resource_uses_single_synchronous_request() {
        let (base_url, server) = spawn_mock_server(vec![(
            200,
            vec![("X-Api-Status-Code", "20000000".to_string())],
            serde_json::json!({ "result": { "text": "极速版结果" } }).to_string(),
        )])
        .await;

        let mut provider = VolcengineAucProvider::with_base_url(base_url);
        let mut config = test_config();
        config.resource_id = Some(VOLCENGINE_AUC_RESOURCE_TURBO.to_string());
        provider.connect(&config).await.unwrap();
        provider.send_audio(&[1u8; 320]).await.unwrap();

        assert_eq!(
            provider.disconnect().await.unwrap().as_deref(),
            Some("极速版结果")
        );

        let requests = server.await.unwrap();
        assert_eq!(requests.len(), 1);
        assert!(requests[0].contains("post /api/v3/auc/bigmodel/recognize/flash"));
    }

    #[tokio::test]
    async fn silent_clip_finishes_as_no_speech_instead_of_erroring() {
        let (base_url, server) = spawn_mock_server(vec![
            (
                200,
                vec![("X-Api-Status-Code", "20000000".to_string())],
                "{}".to_string(),
            ),
            (
                200,
                vec![
                    ("X-Api-Status-Code", "20000003".to_string()),
                    ("X-Api-Message", "[Normal silence audio]".to_string()),
                ],
                serde_json::json!({ "result": { "text": "" } }).to_string(),
            ),
        ])
        .await;

        let mut provider = VolcengineAucProvider::with_base_url(base_url);
        let config = test_config();
        provider.connect(&config).await.unwrap();
        provider.send_audio(&[0u8; 320]).await.unwrap();

        assert_eq!(provider.disconnect().await.unwrap(), None);
        server.await.unwrap();
    }

    #[tokio::test]
    async fn unauthorized_response_reports_service_message() {
        let (base_url, server) = spawn_mock_server(vec![(
            401,
            vec![("X-Api-Status-Code", "45000010".to_string())],
            serde_json::json!({
                "header": { "code": 45000010, "message": "Invalid X-Api-Key" }
            })
            .to_string(),
        )])
        .await;

        let mut provider = VolcengineAucProvider::with_base_url(base_url);
        let config = test_config();
        provider.connect(&config).await.unwrap();
        provider.send_audio(&[0u8; 320]).await.unwrap();

        let error = provider.disconnect().await.unwrap_err();
        assert!(matches!(error, AppError::Auth(_)));
        assert!(error.to_string().contains("Invalid X-Api-Key"));
        server.await.unwrap();
    }

    #[tokio::test]
    async fn submit_rejection_surfaces_status_message() {
        let (base_url, server) = spawn_mock_server(vec![(
            200,
            vec![
                ("X-Api-Status-Code", "45000001".to_string()),
                ("X-Api-Message", "invalid audio format".to_string()),
            ],
            "{}".to_string(),
        )])
        .await;

        let mut provider = VolcengineAucProvider::with_base_url(base_url);
        let config = test_config();
        provider.connect(&config).await.unwrap();
        provider.send_audio(&[0u8; 320]).await.unwrap();

        let error = provider.disconnect().await.unwrap_err();
        assert!(error.to_string().contains("invalid audio format"));
        assert!(error.to_string().contains("45000001"));
        server.await.unwrap();
    }

    #[tokio::test]
    async fn empty_recording_skips_upload() {
        let mut provider = VolcengineAucProvider::new();
        provider.connect(&test_config()).await.unwrap();
        assert_eq!(provider.disconnect().await.unwrap(), None);
    }

    #[tokio::test]
    async fn legacy_app_id_access_token_uses_old_console_headers() {
        let (base_url, server) = spawn_mock_server(vec![(
            200,
            vec![("X-Api-Status-Code", "20000000".to_string())],
            serde_json::json!({ "result": { "text": "ok" } }).to_string(),
        )])
        .await;

        let mut provider = VolcengineAucProvider::with_base_url(base_url);
        let mut config = test_config();
        config.api_key = "app-123:token-456".to_string();
        config.resource_id = Some(VOLCENGINE_AUC_RESOURCE_TURBO.to_string());
        provider.connect(&config).await.unwrap();
        provider.send_audio(&[0u8; 320]).await.unwrap();
        provider.disconnect().await.unwrap();

        let requests = server.await.unwrap();
        assert!(requests[0].contains("x-api-app-key: app-123"));
        assert!(requests[0].contains("x-api-access-key: token-456"));
        assert!(!requests[0].contains("x-api-key:"));
    }
}
