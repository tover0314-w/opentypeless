use async_trait::async_trait;

use crate::error::AppError;

use super::{SttConfig, SttProvider, TranscriptEvent};

/// Configuration for a Whisper-compatible HTTP file-upload STT provider.
#[derive(Debug, Clone)]
pub struct WhisperCompatConfig {
    pub provider_name: String,
    pub endpoint: String,
    pub model: String,
    /// Extra form text fields (e.g. GLM-ASR needs "stream"="false").
    pub extra_fields: Vec<(String, String)>,
    /// Local OpenAI-compatible servers often do not require authentication.
    pub api_key_required: bool,
}

/// Max audio buffer: ~24 MB PCM ≈ 12.5 min at 16kHz 16-bit mono.
/// Keeps the resulting WAV under 25 MB (OpenAI/Groq limit).
const MAX_AUDIO_BYTES: usize = 24 * 1024 * 1024;
const MAX_TRANSCRIPTION_RESPONSE_BYTES: usize = 8 * 1024 * 1024;
const REQUEST_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(60);
// Three HTTP attempts, two backoffs (1s + 2s), and a small finalization margin.
pub const FINALIZE_TIMEOUT: std::time::Duration = std::time::Duration::from_secs(198);

fn upload_error(error: reqwest::Error) -> AppError {
    if error.is_timeout() {
        AppError::Timeout(REQUEST_TIMEOUT)
    } else {
        error.into()
    }
}

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
            client: crate::build_shared_http_client(),
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

        let audio_len_secs = self.audio_buffer.len() as f64 / (config.sample_rate as f64 * 2.0);
        let wav_data = Self::build_wav(&self.audio_buffer, config.sample_rate);
        tracing::info!(
            "{}: sending {:.1}s of audio for transcription",
            self.provider_config.provider_name,
            audio_len_secs
        );

        let mut attempt = 0u32;
        loop {
            let file_part = reqwest::multipart::Part::bytes(wav_data.clone())
                .file_name("audio.wav")
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
                .timeout(REQUEST_TIMEOUT);

            if !config.api_key.trim().is_empty() {
                request = request.header("Authorization", format!("Bearer {}", config.api_key));
            }

            let resp_result = request.send().await;

            match resp_result {
                Ok(resp) => {
                    let status = resp.status();
                    if status.is_success() {
                        let v = crate::cloud_request::read_json_response_with_limit(
                            resp,
                            MAX_TRANSCRIPTION_RESPONSE_BYTES,
                        )
                        .await?;
                        if v.get("error").is_some_and(|error| !error.is_null()) {
                            return Err(AppError::Config(
                                "Transcription service returned an error response".to_string(),
                            ));
                        }
                        let text = v
                            .get("text")
                            .and_then(serde_json::Value::as_str)
                            .ok_or_else(|| {
                                AppError::Config(
                                    "Transcription response must contain a string text field"
                                        .to_string(),
                                )
                            })?
                            .trim()
                            .to_string();

                        tracing::info!(
                            "{} transcription: {} chars",
                            self.provider_config.provider_name,
                            text.len()
                        );

                        self.audio_buffer.clear();
                        return Ok(if text.is_empty() { None } else { Some(text) });
                    } else if status.as_u16() >= 500 && attempt < 2 {
                        tracing::warn!(
                            "{} server error {} (attempt {}/3)",
                            self.provider_config.provider_name,
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
                        tracing::error!("{} HTTP {}", self.provider_config.provider_name, status);
                        return Err(AppError::Api {
                            status: status.as_u16(),
                            body: String::new(),
                        });
                    }
                }
                Err(e) if e.is_timeout() && attempt < 2 => {
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
                Err(e) => return Err(upload_error(e)),
            }
        }
    }

    fn take_retry_audio(&mut self) -> Option<crate::retry_audio::RetryAudio> {
        crate::retry_audio::RetryAudio::new(
            self.provider_config.clone(),
            self.stt_config.clone()?,
            std::mem::take(&mut self.audio_buffer),
        )
    }

    fn name(&self) -> &str {
        &self.provider_config.provider_name
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[derive(Clone, Default)]
    struct CapturedLog(std::sync::Arc<std::sync::Mutex<Vec<u8>>>);

    impl std::io::Write for CapturedLog {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(bytes);
            Ok(bytes.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }

    impl CapturedLog {
        fn subscriber(&self, level: tracing::Level) -> impl tracing::Subscriber + Send + Sync {
            let writer = self.clone();
            tracing_subscriber::fmt()
                .without_time()
                .with_ansi(false)
                .with_max_level(level)
                .with_writer(move || writer.clone())
                .finish()
        }

        fn text(&self) -> String {
            String::from_utf8(self.0.lock().unwrap().clone()).unwrap()
        }
    }

    fn read_upload(socket: &mut std::net::TcpStream) -> Vec<u8> {
        use std::io::Read;
        socket
            .set_read_timeout(Some(std::time::Duration::from_secs(5)))
            .unwrap();
        let mut request = Vec::new();
        let mut buffer = [0; 4096];
        loop {
            let received = socket.read(&mut buffer).unwrap();
            assert!(received > 0, "upload ended before the complete request");
            request.extend_from_slice(&buffer[..received]);
            assert!(request.len() <= 64 * 1024, "test upload exceeded its bound");
            let Some(headers_end) = request.windows(4).position(|bytes| bytes == b"\r\n\r\n")
            else {
                continue;
            };
            let headers = String::from_utf8_lossy(&request[..headers_end]);
            let content_length = headers
                .lines()
                .find_map(|line| {
                    line.to_ascii_lowercase()
                        .strip_prefix("content-length:")
                        .map(|length| length.trim().parse::<usize>().unwrap())
                })
                .expect("multipart upload has a content length");
            if request.len() >= headers_end + 4 + content_length {
                return request;
            }
        }
    }

    #[tokio::test]
    async fn upload_preserves_model_language_auth_and_wav_contract() {
        use std::io::Write;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            let request = read_upload(&mut socket);
            let response = "{\"text\":\"  Test transcript  \"}";
            write!(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}",
                response.len()
            )
            .unwrap();
            request
        });
        let mut provider = WhisperCompatProvider::with_client(
            WhisperCompatConfig {
                provider_name: "test-whisper".into(),
                endpoint: format!("http://{address}/v1/audio/transcriptions"),
                model: "test-model".into(),
                extra_fields: vec![("stream".into(), "false".into())],
                api_key_required: true,
            },
            reqwest::Client::builder().no_proxy().build().unwrap(),
        );
        provider
            .connect(&SttConfig {
                api_key: "test-token".into(),
                language: Some("zh".into()),
                ..SttConfig::default()
            })
            .await
            .unwrap();
        let pcm = [1, 0, 2, 0];
        provider.send_audio(&pcm).await.unwrap();
        assert_eq!(
            provider.disconnect().await.unwrap().as_deref(),
            Some("Test transcript")
        );
        assert!(provider.take_retry_audio().is_none());
        let request = server.join().unwrap();
        let text = String::from_utf8_lossy(&request);
        assert!(text.starts_with("POST /v1/audio/transcriptions HTTP/1.1"));
        assert!(text
            .to_ascii_lowercase()
            .contains("authorization: bearer test-token"));
        for field in [
            "name=\"model\"\r\n\r\ntest-model",
            "name=\"language\"\r\n\r\nzh",
            "name=\"stream\"\r\n\r\nfalse",
            "filename=\"audio.wav\"",
            "Content-Type: audio/wav",
        ] {
            assert!(text.contains(field), "missing upload field: {field}");
        }
        let wav = WhisperCompatProvider::build_wav(&pcm, 16000);
        assert!(request.windows(wav.len()).any(|bytes| bytes == wav));
    }

    #[tokio::test]
    async fn error_response_excludes_echoed_secret_from_logs_and_internal_error() {
        use std::io::Write;
        use tracing::instrument::WithSubscriber;

        let captured = CapturedLog::default();
        let subscriber = captured.subscriber(tracing::Level::ERROR);

        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let synthetic_secret = "synthetic-whisper-secret";
        let response = format!("Denied {synthetic_secret}: synthetic private transcript");
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            read_upload(&mut socket);
            write!(
                socket,
                "HTTP/1.1 400 Bad Request\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}",
                response.len()
            )
            .unwrap();
        });
        let mut provider = WhisperCompatProvider::with_client(
            WhisperCompatConfig {
                provider_name: "test-whisper".into(),
                endpoint: format!("http://{address}/transcriptions"),
                model: "test-model".into(),
                extra_fields: vec![],
                api_key_required: true,
            },
            reqwest::Client::builder().no_proxy().build().unwrap(),
        );
        provider
            .connect(&SttConfig {
                api_key: synthetic_secret.into(),
                ..SttConfig::default()
            })
            .await
            .unwrap();
        provider.send_audio(&[1, 0, 2, 0]).await.unwrap();
        let error = provider
            .disconnect()
            .with_subscriber(subscriber)
            .await
            .unwrap_err();
        server.join().unwrap();
        let logs = captured.text();
        assert!(logs.contains("test-whisper HTTP 400"));
        assert!(!logs.contains(synthetic_secret));
        assert!(!logs.contains("synthetic private transcript"));
        assert!(matches!(error, AppError::Api { status: 400, .. }));
        assert!(!error.to_string().contains(synthetic_secret));
        assert!(!error.to_string().contains("synthetic private transcript"));
        assert_eq!(error.to_user_error().details.as_deref(), Some("HTTP 400"));
        assert_eq!(provider.take_retry_audio().unwrap().pcm, [1, 0, 2, 0]);
    }

    #[tokio::test]
    async fn server_error_retries_exclude_response_content_and_keep_audio() {
        use std::io::Write;
        use tracing::instrument::WithSubscriber;

        let captured = CapturedLog::default();
        let subscriber = captured.subscriber(tracing::Level::WARN);
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            for _attempt in 0..3 {
                let (mut socket, _) = listener.accept().unwrap();
                read_upload(&mut socket);
                let response = "synthetic-secret synthetic private transcript";
                write!(
                    socket,
                    "HTTP/1.1 503 Service Unavailable\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}",
                    response.len()
                )
                .unwrap();
            }
        });
        let mut provider = WhisperCompatProvider::with_client(
            WhisperCompatConfig {
                provider_name: "test-whisper".into(),
                endpoint: format!("http://{address}/transcriptions"),
                model: "test-model".into(),
                extra_fields: vec![],
                api_key_required: true,
            },
            reqwest::Client::builder().no_proxy().build().unwrap(),
        );
        provider
            .connect(&SttConfig {
                api_key: "synthetic-secret".into(),
                ..SttConfig::default()
            })
            .await
            .unwrap();
        provider.send_audio(&[1, 0, 2, 0]).await.unwrap();
        let error = provider
            .disconnect()
            .with_subscriber(subscriber)
            .await
            .unwrap_err();
        server.join().unwrap();
        let logs = captured.text();
        assert!(logs.contains("(attempt 1/3)"));
        assert!(logs.contains("(attempt 2/3)"));
        assert!(logs.contains("test-whisper HTTP 503"));
        assert!(!logs.contains("synthetic-secret"));
        assert!(!logs.contains("synthetic private transcript"));
        assert!(matches!(error, AppError::Api { status: 503, ref body } if body.is_empty()));
        assert_eq!(error.to_user_error().details.as_deref(), Some("HTTP 503"));
        assert_eq!(provider.take_retry_audio().unwrap().pcm, [1, 0, 2, 0]);
    }

    #[tokio::test]
    async fn oversized_success_rejects_response_and_preserves_retry_audio() {
        use std::io::Write;
        for declared_length in [true, false] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                read_upload(&mut socket);
                if declared_length {
                    let _ = socket.write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 8388609\r\nConnection: close\r\n\r\n",
                    );
                } else {
                    let body = serde_json::json!({"text": "x".repeat(8 * 1024 * 1024)}).to_string();
                    let _ = write!(socket, "HTTP/1.1 200 OK\r\nConnection: close\r\n\r\n{body}");
                }
            });
            let mut provider = WhisperCompatProvider::with_client(
                WhisperCompatConfig {
                    provider_name: "test-whisper".into(),
                    endpoint: format!("http://{address}/transcriptions"),
                    model: "test-model".into(),
                    extra_fields: vec![],
                    api_key_required: false,
                },
                reqwest::Client::builder().no_proxy().build().unwrap(),
            );
            provider.connect(&SttConfig::default()).await.unwrap();
            provider.send_audio(&[1, 0, 2, 0]).await.unwrap();
            let result = provider.disconnect().await;
            server.join().unwrap();
            let error = result.unwrap_err();
            assert!(matches!(error, AppError::Config(_)), "{error:?}");
            assert!(error.to_string().contains("too large"));
            assert_eq!(provider.take_retry_audio().unwrap().pcm, [1, 0, 2, 0]);
        }
    }

    #[tokio::test]
    async fn malformed_success_keeps_audio_for_retry() {
        use std::io::Write;
        for body in [
            "{}",
            "{\"text\":42}",
            "{\"text\":null}",
            "{\"text\":\"synthetic transcript\",\"error\":{\"message\":\"synthetic failure\"}}",
        ] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                read_upload(&mut socket);
                write!(
                    socket,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                )
                .unwrap();
            });
            let mut provider = WhisperCompatProvider::with_client(
                WhisperCompatConfig {
                    provider_name: "test-whisper".into(),
                    endpoint: format!("http://{address}/v1/audio/transcriptions"),
                    model: "test-model".into(),
                    extra_fields: vec![],
                    api_key_required: false,
                },
                reqwest::Client::builder().no_proxy().build().unwrap(),
            );
            provider.connect(&SttConfig::default()).await.unwrap();
            provider.send_audio(&[1, 0, 2, 0]).await.unwrap();
            let result = provider.disconnect().await;
            let retained = provider.take_retry_audio();
            server.join().unwrap();
            assert!(
                matches!(result, Err(AppError::Config(_))),
                "{body}: {result:?}"
            );
            assert_eq!(retained.unwrap().pcm, [1, 0, 2, 0]);
        }
    }

    #[tokio::test]
    async fn failed_audio_can_be_replayed_and_is_cleared_after_success() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            for response in [
                "HTTP/1.1 401 Unauthorized\r\nContent-Length: 0\r\nConnection: close\r\n\r\n",
                "HTTP/1.1 200 OK\r\nContent-Length: 20\r\nConnection: close\r\n\r\n{\"text\":\"Recovered\"}",
            ] {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(10)))
                    .unwrap();
                let mut request = vec![0; 8192];
                let mut received = 0;
                loop {
                    let read = socket.read(&mut request[received..]).unwrap();
                    assert!(read > 0);
                    received += read;
                    let Some(headers_end) = request[..received]
                        .windows(4)
                        .position(|bytes| bytes == b"\r\n\r\n")
                    else {
                        continue;
                    };
                    let headers = String::from_utf8_lossy(&request[..headers_end]);
                    let length = headers
                        .lines()
                        .find_map(|line| {
                            line.to_ascii_lowercase()
                                .strip_prefix("content-length:")
                                .map(|value| value.trim().parse::<usize>().unwrap())
                        })
                        .unwrap();
                    if received >= headers_end + 4 + length {
                        break;
                    }
                }
                assert!(request[..received].windows(4).any(|bytes| bytes == b"RIFF"));
                socket.write_all(response.as_bytes()).unwrap();
            }
        });
        let mut provider = WhisperCompatProvider::new(WhisperCompatConfig {
            provider_name: "custom-whisper".to_string(),
            endpoint: format!("http://{address}/transcriptions"),
            model: "test".to_string(),
            extra_fields: vec![],
            api_key_required: false,
        });
        provider.connect(&SttConfig::default()).await.unwrap();
        provider.send_audio(&[1, 0, 2, 0]).await.unwrap();
        assert!(provider.disconnect().await.is_err());
        let retained = provider.take_retry_audio().unwrap();
        let mut replay = WhisperCompatProvider::new(retained.provider);
        replay.connect(&retained.config).await.unwrap();
        replay.send_audio(&retained.pcm).await.unwrap();
        assert_eq!(
            replay.disconnect().await.unwrap(),
            Some("Recovered".to_string())
        );
        assert!(replay.take_retry_audio().is_none());
        server.join().unwrap();
    }

    #[tokio::test]
    async fn incomplete_response_body_is_reported_as_a_transport_error() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(10)))
                .unwrap();
            let mut request = [0; 4096];
            assert!(socket.read(&mut request).unwrap() > 0);
            socket.write_all(b"HTTP/1.1 200 OK\r\nContent-Length: 128\r\nConnection: close\r\n\r\n{\"text\":\"").unwrap();
            std::thread::sleep(std::time::Duration::from_millis(100));
        });
        let mut provider = WhisperCompatProvider::new(WhisperCompatConfig {
            provider_name: "custom-whisper".to_string(),
            endpoint: format!("http://{address}/transcriptions"),
            model: "test-model".to_string(),
            extra_fields: vec![],
            api_key_required: false,
        });
        provider.connect(&SttConfig::default()).await.unwrap();
        provider.send_audio(&[0, 0]).await.unwrap();
        let error = provider.disconnect().await.unwrap_err();
        assert!(matches!(error, AppError::Network(_)), "{error:?}");
        let retry = provider
            .take_retry_audio()
            .expect("failed upload keeps its audio");
        assert_eq!(retry.pcm, [0, 0]);
        assert!(provider.take_retry_audio().is_none());
        server.join().unwrap();
    }

    #[tokio::test]
    async fn connect_allows_empty_api_key_when_not_required() {
        let mut provider = WhisperCompatProvider::new(WhisperCompatConfig {
            provider_name: "custom-whisper".to_string(),
            endpoint: "http://localhost:8000/v1/audio/transcriptions".to_string(),
            model: "test-model".to_string(),
            extra_fields: vec![],
            api_key_required: false,
        });

        let result = provider
            .connect(&SttConfig {
                api_key: String::new(),
                language: None,
                smart_format: true,
                sample_rate: 16000,
                resource_id: None,
                operation_id: None,
                managed_audio: None,
                provider_region: None,
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
        });

        let result = tokio::time::timeout(
            std::time::Duration::from_millis(20),
            provider.recv_transcript(),
        )
        .await;

        assert!(result.is_err());
    }
}
