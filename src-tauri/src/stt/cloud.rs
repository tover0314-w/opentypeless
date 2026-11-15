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
const MAX_TRANSCRIPT_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
const MAX_ERROR_RESPONSE_BYTES: usize = 65536;
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

fn parse_cloud_transcript(body: &str) -> Result<Option<String>, AppError> {
    let invalid = || AppError::Config("Invalid cloud transcription response".into());
    let value: serde_json::Value = serde_json::from_str(body).map_err(|_| invalid())?;
    let object = value.as_object().ok_or_else(invalid)?;
    if object.contains_key("error") {
        return Err(invalid());
    }
    let text = object
        .get("text")
        .and_then(serde_json::Value::as_str)
        .ok_or_else(invalid)?
        .trim()
        .to_string();
    Ok(if text.is_empty() { None } else { Some(text) })
}

fn stream_serial(operation_id: Option<&str>) -> u32 {
    let mut hash = 0x811c_9dc5u32;
    for byte in operation_id.unwrap_or("opentypeless-cloud-stt").bytes() {
        hash ^= u32::from(byte);
        hash = hash.wrapping_mul(0x0100_0193);
    }
    hash.max(1)
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

fn contains_quota_marker(value: &str) -> bool {
    let value = value.to_ascii_lowercase();
    value.contains("quota")
        || value.contains("limit exceeded")
        || value.contains("usage exceeded")
        || value.contains("byok")
}

fn has_quota_marker(value: &serde_json::Value) -> bool {
    for field in ["code", "error_code", "type"] {
        if value
            .get(field)
            .and_then(|v| v.as_str())
            .is_some_and(contains_quota_marker)
        {
            return true;
        }
    }

    for field in ["error", "message"] {
        if let Some(item) = value.get(field) {
            if let Some(message) = item.as_str() {
                if contains_quota_marker(message) {
                    return true;
                }
            } else if has_quota_marker(item) {
                return true;
            }
        }
    }

    false
}

fn cloud_stt_forbidden_error(body: &str) -> AppError {
    let parsed = serde_json::from_str::<serde_json::Value>(body).ok();

    if let Some(value) = parsed.as_ref() {
        if has_quota_marker(value) {
            return AppError::Quota("Cloud STT quota exceeded".into());
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
            client: crate::build_shared_http_client(),
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
                if encoded.original_samples != input_samples {
                    return Err(AppError::Config(
                        "managed_audio_too_large: recording exceeds the supported audio limit"
                            .into(),
                    ));
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
        self.stt_config = None;
        self.audio_buffer.clear();
        self.managed_audio_worker.take();
        self.managed_audio_failed = false;
        if config.api_key.trim().is_empty() {
            return Err(AppError::Auth(
                "Cloud STT: session token is missing. Please sign in first.".to_string(),
            ));
        }
        self.stt_config = Some(config.clone());
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
        if self.stt_config.is_none() {
            return Err(AppError::Config("Cloud STT is not connected".into()));
        }
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
        let config = match self.stt_config.take() {
            Some(config) => config,
            None => return Ok(None),
        };

        if self.audio_buffer.is_empty() {
            self.managed_audio_worker.take();
            tracing::info!("Cloud STT: no audio buffered, skipping");
            return Ok(None);
        }

        let pcm = std::mem::take(&mut self.audio_buffer);
        let audio_len_secs = pcm.len() as f64 / (config.sample_rate as f64 * 2.0);
        let payload = self.build_payload(pcm, &config).await?;
        let request_timeout = crate::cloud_request::stt_timeout(payload.bytes.len());
        tracing::info!(
            "Cloud STT: sending {:.1}s of audio for transcription as {} ({} bytes)",
            audio_len_secs,
            payload.mime_type,
            payload.bytes.len()
        );

        let request = async {
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
                        let response_limit = if status.is_success() {
                            MAX_TRANSCRIPT_RESPONSE_BYTES
                        } else {
                            MAX_ERROR_RESPONSE_BYTES
                        };
                        let bytes =
                            crate::cloud_request::read_response_with_limit(resp, response_limit)
                                .await?;
                        let body = if status.is_success() {
                            String::from_utf8(bytes).map_err(|_| {
                                AppError::Config("Invalid cloud transcription response".into())
                            })?
                        } else {
                            String::from_utf8_lossy(&bytes).into_owned()
                        };

                        if status.is_success() {
                            let text = parse_cloud_transcript(&body)?;
                            tracing::info!(
                                "Cloud STT transcription: {} chars",
                                text.as_ref().map_or(0, String::len)
                            );
                            return Ok(text);
                        } else if let Some(error) = managed_cloud_error(status.as_u16(), &body) {
                            return Err(error);
                        } else if status.as_u16() == 403 {
                            return Err(cloud_stt_forbidden_error(&body));
                        } else if status.as_u16() >= 500 && attempt < 2 {
                            tracing::warn!(
                                "Cloud STT server error {} (attempt {}/3)",
                                status,
                                attempt + 1
                            );
                            attempt += 1;
                            tokio::time::sleep(std::time::Duration::from_millis(
                                1000 * 2u64.pow(attempt - 1),
                            ))
                            .await;
                            continue;
                        } else {
                            tracing::error!("Cloud STT HTTP {}", status);
                            return Err(AppError::Api {
                                status: status.as_u16(),
                                body: String::new(),
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
        };
        tokio::time::timeout(request_timeout, request)
            .await
            .unwrap_or(Err(AppError::Timeout(request_timeout)))
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

    #[derive(Clone, Default)]
    struct CapturedLog(std::sync::Arc<Mutex<Vec<u8>>>);

    impl std::io::Write for CapturedLog {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(bytes);
            Ok(bytes.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    async fn read_test_upload(socket: &mut tokio::net::TcpStream) -> Vec<u8> {
        use tokio::io::AsyncReadExt;

        let mut request = Vec::new();
        let mut buffer = [0u8; 4096];
        loop {
            let read = socket.read(&mut buffer).await.unwrap();
            assert!(read > 0);
            request.extend_from_slice(&buffer[..read]);
            assert!(request.len() < 65536);
            if let Some(header_end) = request.windows(4).position(|part| part == b"\r\n\r\n") {
                let headers = String::from_utf8_lossy(&request[..header_end]);
                let length = headers
                    .lines()
                    .find_map(|line| {
                        let (name, value) = line.split_once(':')?;
                        name.eq_ignore_ascii_case("content-length")
                            .then(|| value.trim().parse::<usize>().unwrap())
                    })
                    .unwrap();
                if request.len() >= header_end + 4 + length {
                    return request;
                }
            }
        }
    }

    #[tokio::test]
    async fn cancelled_cloud_response_read_releases_provider_state() {
        use tokio::io::AsyncWriteExt;

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let (headers_sent, received_headers) = tokio::sync::oneshot::channel();
        let (release_server, server_release) = tokio::sync::oneshot::channel::<()>();
        let server = tokio::spawn(async move {
            let (mut socket, _) = listener.accept().await.unwrap();
            read_test_upload(&mut socket).await;
            socket
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n")
                .await
                .unwrap();
            headers_sent.send(()).unwrap();
            let _ = server_release.await;
        });
        let client = reqwest::Client::builder().no_proxy().build().unwrap();
        let mut provider = CloudSttProvider::with_client(format!("http://{address}"), client);
        provider
            .connect(&SttConfig {
                managed_audio: None,
                ..stt_config()
            })
            .await
            .unwrap();
        provider.send_audio(&[0; 32]).await.unwrap();
        let pending = async {
            let cancellation = async {
                received_headers.await.unwrap();
                tokio::task::yield_now().await;
            };
            tokio::select! {
                result = provider.disconnect() => panic!("stalled response completed: {result:?}"),
                _ = cancellation => {}
            }
        };
        let result = tokio::time::timeout(std::time::Duration::from_secs(5), pending).await;
        let _ = release_server.send(());
        server.await.unwrap();
        result.unwrap();
        assert!(provider.stt_config.is_none());
        assert!(provider.audio_buffer.is_empty());
        assert!(provider.managed_audio_worker.is_none());
        assert!(provider.send_audio(&[0; 32]).await.is_err());
        assert_eq!(provider.disconnect().await.unwrap(), None);
    }

    #[tokio::test]
    async fn reused_cloud_provider_uploads_only_the_current_token_and_audio() {
        use tokio::io::AsyncWriteExt;

        let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            for (token, samples, answer) in [
                ("synthetic-first-token", vec![1u8; 32], "first"),
                ("synthetic-second-token", vec![2u8; 48], "second"),
            ] {
                let (mut socket, _) = listener.accept().await.unwrap();
                let request = read_test_upload(&mut socket).await;
                let header_end = request
                    .windows(4)
                    .position(|part| part == b"\r\n\r\n")
                    .unwrap();
                let headers = String::from_utf8_lossy(&request[..header_end]).to_ascii_lowercase();
                assert!(headers.contains(&format!("authorization: bearer {token}\r\n")));
                let wav = WhisperCompatProvider::build_wav(&samples, 16000);
                let body = &request[header_end + 4..];
                let audio_start = body.windows(4).position(|part| part == b"RIFF").unwrap();
                assert_eq!(&body[audio_start..audio_start + wav.len()], wav.as_slice());
                assert!(body[audio_start + wav.len()..].starts_with(b"\r\n--"));
                let response = format!(r#"{{"text":"{answer}"}}"#);
                socket
                    .write_all(
                        format!(
                            "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}",
                            response.len()
                        )
                        .as_bytes(),
                    )
                    .await
                    .unwrap();
            }
        });
        let client = reqwest::Client::builder().no_proxy().build().unwrap();
        let mut provider = CloudSttProvider::with_client(format!("http://{address}"), client);
        let run = async {
            for (token, samples, answer) in [
                ("synthetic-first-token", vec![1u8; 32], "first"),
                ("synthetic-second-token", vec![2u8; 48], "second"),
            ] {
                let config = SttConfig {
                    api_key: token.into(),
                    managed_audio: None,
                    ..stt_config()
                };
                provider.connect(&config).await.unwrap();
                provider.send_audio(&samples).await.unwrap();
                assert_eq!(
                    provider.disconnect().await.unwrap().as_deref(),
                    Some(answer)
                );
                assert!(provider.stt_config.is_none());
                assert!(provider.audio_buffer.is_empty());
            }
            server.await.unwrap();
        };
        tokio::time::timeout(std::time::Duration::from_secs(5), run)
            .await
            .unwrap();
    }

    #[tokio::test]
    async fn cloud_disconnect_rejects_oversized_response_headers_before_reading_body() {
        use tokio::io::AsyncWriteExt;

        for (status, chunked, at_limit) in [
            (200, false, false),
            (200, true, false),
            (200, true, true),
            (403, false, false),
            (403, true, false),
            (403, true, true),
        ] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let server = tokio::spawn(async move {
                let (mut socket, _) = listener.accept().await.unwrap();
                read_test_upload(&mut socket).await;
                if chunked {
                    let mut body = if at_limit && status == 200 {
                        r#"{"text":"synthetic boundary transcript"}"#.to_string()
                    } else if at_limit {
                        r#"{"error":{"code":"stt_quota_exceeded"}}"#.to_string()
                    } else {
                        String::new()
                    };
                    let limit = if status == 200 {
                        MAX_TRANSCRIPT_RESPONSE_BYTES
                    } else {
                        MAX_ERROR_RESPONSE_BYTES
                    };
                    let length = limit + usize::from(!at_limit);
                    body.push_str(&" ".repeat(length - body.len()));
                    let response = format!(
                        "HTTP/1.1 {status} Response\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n{:x}\r\n{body}\r\n0\r\n\r\n",
                        body.len()
                    );
                    let _ = socket.write_all(response.as_bytes()).await;
                } else {
                    socket
                        .write_all(
                            format!(
                                "HTTP/1.1 {status} Response\r\nContent-Length: 8388609\r\nConnection: close\r\n\r\n"
                            )
                            .as_bytes(),
                        )
                        .await
                        .unwrap();
                }
            });
            let client = reqwest::Client::builder().no_proxy().build().unwrap();
            let mut provider = CloudSttProvider::with_client(format!("http://{address}"), client);
            provider
                .connect(&SttConfig {
                    managed_audio: None,
                    ..stt_config()
                })
                .await
                .unwrap();
            provider.send_audio(&[0; 32]).await.unwrap();
            let result =
                tokio::time::timeout(std::time::Duration::from_secs(5), provider.disconnect())
                    .await
                    .unwrap();
            server.await.unwrap();
            if at_limit && status == 200 {
                assert_eq!(
                    result.unwrap().as_deref(),
                    Some("synthetic boundary transcript")
                );
            } else if at_limit {
                assert!(matches!(result.unwrap_err(), AppError::Quota(_)));
            } else {
                assert_eq!(
                    result.unwrap_err().to_string(),
                    "Config error: Service response too large"
                );
            }
            assert!(provider.stt_config.is_none());
        }
    }

    #[tokio::test]
    async fn cloud_disconnect_validates_the_actual_http_transcript_response() {
        use tokio::io::AsyncWriteExt;
        use tracing::instrument::WithSubscriber;

        for (status, body, expected) in [
            (
                200,
                r#"{"text":" synthetic words "}"#,
                Some(Some("synthetic words")),
            ),
            (200, r#"{"text":""}"#, Some(None)),
            (200, r#"{"text":null}"#, None),
            (200, "synthetic-invalid-utf8", None),
            (400, "synthetic-private-transcript-detail", None),
            (500, "synthetic-private-transcript-detail", None),
            (
                503,
                "synthetic-private-transcript-detail",
                Some(Some("recovered")),
            ),
            (503, "synthetic-invalid-utf8", Some(Some("recovered"))),
            (
                401,
                r#"{"error":{"code":"AUTH_SESSION_INVALID","message":"synthetic-private-transcript-detail"}}"#,
                None,
            ),
            (
                403,
                r#"{"error":{"code":"stt_quota_exceeded","message":"synthetic-private-transcript-detail"}}"#,
                None,
            ),
            (
                403,
                r#"{"error":"synthetic-private-transcript-detail"}"#,
                None,
            ),
        ] {
            let listener = tokio::net::TcpListener::bind("127.0.0.1:0").await.unwrap();
            let address = listener.local_addr().unwrap();
            let mut server = tokio::spawn(async move {
                for (response_status, response_body) in if status == 503 {
                    vec![(503, body), (200, r#"{"text":"recovered"}"#)]
                } else if status == 500 {
                    vec![(500, body); 3]
                } else {
                    vec![(status, body)]
                } {
                    let (mut socket, _) = listener.accept().await.unwrap();
                    let request = read_test_upload(&mut socket).await;
                    let header_end = request
                        .windows(4)
                        .position(|part| part == b"\r\n\r\n")
                        .unwrap();
                    let headers = String::from_utf8_lossy(&request[..header_end]);
                    assert!(headers.starts_with("POST /api/proxy/stt "));
                    assert!(headers
                        .to_ascii_lowercase()
                        .contains("bearer synthetic-token"));
                    let response_bytes = if response_body == "synthetic-invalid-utf8" {
                        vec![0xff, 0xfe]
                    } else {
                        response_body.as_bytes().to_vec()
                    };
                    socket
                    .write_all(
                        format!(
                            "HTTP/1.1 {response_status} Response\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                            response_bytes.len()
                        )
                        .as_bytes(),
                    )
                    .await
                    .unwrap();
                    socket.write_all(&response_bytes).await.unwrap();
                }
                if status == 500 {
                    assert!(tokio::time::timeout(
                        std::time::Duration::from_millis(100),
                        listener.accept()
                    )
                    .await
                    .is_err());
                }
            });
            let client = reqwest::Client::builder().no_proxy().build().unwrap();
            let mut provider = CloudSttProvider::with_client(format!("http://{address}"), client);
            let config = SttConfig {
                api_key: "synthetic-token".into(),
                managed_audio: None,
                ..stt_config()
            };
            provider.connect(&config).await.unwrap();
            provider.send_audio(&[0; 32]).await.unwrap();
            let logs = CapturedLog::default();
            let writer = logs.clone();
            let subscriber = tracing_subscriber::fmt()
                .without_time()
                .with_ansi(false)
                .with_max_level(tracing::Level::INFO)
                .with_writer(move || writer.clone())
                .finish();
            let result = tokio::time::timeout(
                std::time::Duration::from_secs(5),
                provider.disconnect().with_subscriber(subscriber),
            )
            .await
            .unwrap();
            let server_result =
                tokio::time::timeout(std::time::Duration::from_secs(2), &mut server).await;
            if server_result.is_err() {
                server.abort();
                let _ = server.await;
                panic!("server did not receive the expected retries: {result:?}");
            }
            server_result.unwrap().unwrap();
            assert!(provider.stt_config.is_none());
            assert!(provider.audio_buffer.is_empty());
            assert!(provider.send_audio(&[0; 32]).await.is_err());
            let output = String::from_utf8(logs.0.lock().unwrap().clone()).unwrap();
            assert!(!output.contains("synthetic-private-transcript-detail"));
            assert!(!output.contains("synthetic-token"));
            if status == 503 {
                assert!(
                    output.contains("Cloud STT server error 503 Service Unavailable (attempt 1/3)"),
                    "{output}"
                );
            }
            match expected {
                Some(text) => assert_eq!(result.unwrap().as_deref(), text),
                None if status == 500 => {
                    assert!(matches!(
                        result.unwrap_err(),
                        AppError::Api { status: 500, ref body } if body.is_empty()
                    ));
                    assert_eq!(output.matches("Cloud STT server error 500").count(), 2);
                    assert!(output.contains("Cloud STT HTTP 500"));
                }
                None if status == 401 => {
                    assert!(matches!(result.unwrap_err(), AppError::CloudSessionInvalid));
                }
                None if status == 403 => {
                    let error = result.unwrap_err();
                    if body.contains("stt_quota_exceeded") {
                        assert!(
                            matches!(error, AppError::Quota(ref message) if message == "Cloud STT quota exceeded")
                        );
                    } else {
                        assert!(
                            matches!(error, AppError::Auth(ref message) if message == "Cloud STT access denied")
                        );
                    }
                }
                None if status == 400 => {
                    let error = result.unwrap_err();
                    assert!(
                        matches!(error, AppError::Api { status: 400, ref body } if body.is_empty())
                    );
                    assert!(output.contains("Cloud STT HTTP 400"));
                }
                None => assert_eq!(
                    result.unwrap_err().to_string(),
                    "Config error: Invalid cloud transcription response"
                ),
            }
        }
    }

    #[test]
    fn malformed_cloud_transcript_is_not_treated_as_silence() {
        for body in [
            "null",
            "[]",
            "{}",
            r#"{"text":null}"#,
            r#"{"text":42}"#,
            r#"{"text":{}}"#,
            r#"{"text":"synthetic text","error":"synthetic private detail"}"#,
            "synthetic invalid JSON",
        ] {
            let error = parse_cloud_transcript(body).unwrap_err();
            assert_eq!(
                error.to_string(),
                "Config error: Invalid cloud transcription response"
            );
        }
    }

    #[test]
    fn cloud_transcript_preserves_valid_text_and_empty_speech() {
        assert_eq!(
            parse_cloud_transcript(r#"{"text":"  synthetic words  ","language":"en"}"#).unwrap(),
            Some("synthetic words".into())
        );
        assert_eq!(parse_cloud_transcript(r#"{"text":""}"#).unwrap(), None);
        assert_eq!(parse_cloud_transcript(r#"{"text":"   "}"#).unwrap(), None);
    }

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

    #[tokio::test]
    async fn empty_cloud_disconnect_releases_state_and_allows_a_new_connection() {
        let mut provider = CloudSttProvider::new("https://example.test".into());
        let config = SttConfig {
            managed_audio: None,
            ..stt_config()
        };
        provider.connect(&config).await.unwrap();
        assert_eq!(provider.disconnect().await.unwrap(), None);
        assert!(provider.stt_config.is_none());
        assert!(provider.managed_audio_worker.is_none());
        assert!(provider.send_audio(&[0; 32]).await.is_err());
        assert_eq!(provider.disconnect().await.unwrap(), None);
        let next = SttConfig {
            api_key: "synthetic-next-token".into(),
            ..config
        };
        provider.connect(&next).await.unwrap();
        provider.send_audio(&[0; 32]).await.unwrap();
        assert_eq!(provider.audio_buffer.len(), 32);
        assert_eq!(
            provider.stt_config.as_ref().unwrap().api_key,
            "synthetic-next-token"
        );
    }

    #[tokio::test]
    async fn disconnected_cloud_provider_rejects_audio_without_buffering_it() {
        let mut provider = CloudSttProvider::new("https://example.test".into());
        assert!(provider.send_audio(&[0; 32]).await.is_err());
        assert!(provider.audio_buffer.is_empty());
        let config = SttConfig {
            managed_audio: None,
            ..stt_config()
        };
        provider.connect(&config).await.unwrap();
        provider.send_audio(&[0; 32]).await.unwrap();
        assert_eq!(provider.audio_buffer.len(), 32);
        let invalid = SttConfig {
            api_key: String::new(),
            ..config
        };
        assert!(provider.connect(&invalid).await.is_err());
        assert!(provider.send_audio(&[0; 32]).await.is_err());
        assert!(provider.audio_buffer.is_empty());
    }

    #[tokio::test]
    async fn failed_cloud_reconnect_does_not_retain_previous_credentials_or_audio() {
        let mut provider = CloudSttProvider::new("https://example.test".into());
        let config = SttConfig {
            managed_audio: None,
            ..stt_config()
        };
        provider.connect(&config).await.unwrap();
        provider.send_audio(&[0; 32]).await.unwrap();
        let invalid = SttConfig {
            api_key: " ".into(),
            ..config
        };
        assert!(provider.connect(&invalid).await.is_err());
        assert!(provider.stt_config.is_none());
        assert!(provider.audio_buffer.is_empty());
        assert!(provider.managed_audio_worker.is_none());
    }

    #[tokio::test]
    async fn cloud_connect_rejects_blank_tokens_before_preparing_audio() {
        for token in ["", " ", "\t\n"] {
            let mut provider = CloudSttProvider::new("https://example.test".into());
            let config = SttConfig {
                api_key: token.into(),
                ..stt_config()
            };
            assert!(matches!(
                provider.connect(&config).await,
                Err(AppError::Auth(_))
            ));
            assert!(provider.stt_config.is_none());
            assert!(provider.managed_audio_worker.is_none());
            assert!(provider.audio_buffer.is_empty());
        }
    }

    #[test]
    fn helper_boundaries_preserve_short_wav_and_scale_long_request_timeout() {
        let config = stt_config();
        assert_eq!(wav_safe_seconds(&config), Some(109));
        assert_eq!(crate::cloud_request::stt_timeout(32_000).as_secs(), 226);
        assert_eq!(crate::cloud_request::stt_timeout(4_000_000).as_secs(), 350);
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
    fn forbidden_quota_error_does_not_retain_private_server_details() {
        for body in [
            r#"{"code":"stt_quota_exceeded","error":"synthetic-private-account-detail"}"#,
            r#"{"error":{"code":"stt_quota_exceeded","message":"synthetic-private-account-detail"}}"#,
            r#"{"error":"quota exceeded for synthetic-private-account-detail"}"#,
        ] {
            let error = cloud_stt_forbidden_error(body);
            assert!(
                matches!(error, AppError::Quota(ref message) if message == "Cloud STT quota exceeded")
            );
            assert_eq!(error.to_user_error().code, "stt_quota_exceeded");
        }
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
