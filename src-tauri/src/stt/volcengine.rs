use async_trait::async_trait;
use flate2::{read::GzDecoder, write::GzEncoder, Compression};
use futures_util::{SinkExt, StreamExt};
use std::io::{Read, Write};
use tokio_tungstenite::{
    connect_async,
    tungstenite::{Error as WsError, Message},
};

use crate::error::AppError;

use super::{SttConfig, SttProvider, TranscriptEvent};

type WsStream =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

pub const VOLCENGINE_DOUBAO_PROVIDER: &str = "volcengine-doubao";
pub const VOLCENGINE_SEEDASR_RESOURCE_ID: &str = "volc.seedasr.sauc.duration";
pub const VOLCENGINE_BIGASR_RESOURCE_ID: &str = "volc.bigasr.sauc.duration";
const VOLCENGINE_ASR_URL: &str = "wss://openspeech.bytedance.com/api/v3/sauc/bigmodel_async";
const VOLCENGINE_ASR_HOST: &str = "openspeech.bytedance.com";

const FULL_CLIENT_REQUEST: u8 = 0x1;
const AUDIO_ONLY_REQUEST: u8 = 0x2;
const FULL_SERVER_RESPONSE: u8 = 0x9;
const ERROR_RESPONSE: u8 = 0xf;
const POS_SEQUENCE: u8 = 0x1;
const NEG_SEQUENCE: u8 = 0x2;
const NEG_WITH_SEQUENCE: u8 = 0x3;
const JSON_SERIALIZATION: u8 = 0x1;
const NO_SERIALIZATION: u8 = 0x0;
const GZIP_COMPRESSION: u8 = 0x1;
const INITIAL_RESPONSE_TIMEOUT_SECS: u64 = 5;
const MAX_RESPONSE_BYTES: usize = 1_048_576;

pub struct VolcengineDoubaoProvider {
    ws: Option<WsStream>,
    sequence: i32,
    sent_audio: bool,
    url: String,
}

impl Default for VolcengineDoubaoProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl VolcengineDoubaoProvider {
    pub fn new() -> Self {
        Self {
            ws: None,
            sequence: 1,
            sent_audio: false,
            url: VOLCENGINE_ASR_URL.to_string(),
        }
    }

    #[cfg(test)]
    fn with_url(url: String) -> Self {
        let mut provider = Self::new();
        provider.url = url;
        provider
    }
}

fn gzip_payload(payload: &[u8]) -> Result<Vec<u8>, AppError> {
    let mut encoder = GzEncoder::new(Vec::new(), Compression::default());
    encoder
        .write_all(payload)
        .map_err(|e| AppError::Config(e.to_string()))?;
    encoder
        .finish()
        .map_err(|e| AppError::Config(e.to_string()))
}

fn ungzip_payload(payload: &[u8]) -> Result<Vec<u8>, AppError> {
    let decoder = GzDecoder::new(payload);
    let mut out = Vec::new();
    decoder
        .take((MAX_RESPONSE_BYTES + 1) as u64)
        .read_to_end(&mut out)
        .map_err(|e| AppError::Config(e.to_string()))?;
    if out.len() > MAX_RESPONSE_BYTES {
        return Err(AppError::Config(
            "Volcengine ASR response limit exceeded".to_string(),
        ));
    }
    Ok(out)
}

fn volcengine_language(language: Option<&str>) -> String {
    match language.unwrap_or("zh").trim() {
        "" | "multi" | "zh" | "zh-CN" => "zh-CN".to_string(),
        "en" | "en-US" => "en-US".to_string(),
        "ja" | "ja-JP" => "ja-JP".to_string(),
        "ko" | "ko-KR" => "ko-KR".to_string(),
        other => other.to_string(),
    }
}

fn resolve_resource_id(config: &SttConfig) -> &str {
    config
        .resource_id
        .as_deref()
        .map(str::trim)
        .filter(|resource_id| !resource_id.is_empty())
        .unwrap_or(VOLCENGINE_SEEDASR_RESOURCE_ID)
}

fn header(message_type: u8, flags: u8, serialization: u8, compression: u8) -> [u8; 4] {
    [
        0x11,
        (message_type << 4) | flags,
        (serialization << 4) | compression,
        0x00,
    ]
}

fn build_frame(
    message_type: u8,
    flags: u8,
    serialization: u8,
    payload: &[u8],
    sequence: i32,
) -> Result<Vec<u8>, AppError> {
    let compressed = gzip_payload(payload)?;
    let mut frame = Vec::with_capacity(12 + compressed.len());
    frame.extend_from_slice(&header(
        message_type,
        flags,
        serialization,
        GZIP_COMPRESSION,
    ));
    frame.extend_from_slice(&sequence.to_be_bytes());
    frame.extend_from_slice(&(compressed.len() as u32).to_be_bytes());
    frame.extend_from_slice(&compressed);
    Ok(frame)
}

fn build_full_client_request_frame(config: &SttConfig, sequence: i32) -> Result<Vec<u8>, AppError> {
    let payload = serde_json::json!({
        "user": {
            "uid": "opentypeless"
        },
        "audio": {
            "format": "pcm",
            "codec": "raw",
            "rate": config.sample_rate,
            "bits": 16,
            "channel": 1,
            "language": volcengine_language(config.language.as_deref())
        },
        "request": {
            "model_name": "bigmodel",
            "enable_itn": config.smart_format,
            "enable_ddc": false,
            "enable_punc": config.smart_format,
            "show_utterances": true
        }
    });
    build_frame(
        FULL_CLIENT_REQUEST,
        POS_SEQUENCE,
        JSON_SERIALIZATION,
        payload.to_string().as_bytes(),
        sequence,
    )
}

fn build_audio_request_frame(chunk: &[u8], sequence: i32) -> Result<Vec<u8>, AppError> {
    let flags = if sequence < 0 {
        NEG_WITH_SEQUENCE
    } else {
        POS_SEQUENCE
    };
    build_frame(AUDIO_ONLY_REQUEST, flags, NO_SERIALIZATION, chunk, sequence)
}

fn extract_transcript(value: &serde_json::Value) -> String {
    value["result"]["text"]
        .as_str()
        .filter(|s| !s.trim().is_empty())
        .map(str::to_string)
        .or_else(|| {
            value["result"]["utterances"]
                .as_array()
                .and_then(|utterances| utterances.last())
                .and_then(|item| item["text"].as_str())
                .filter(|s| !s.trim().is_empty())
                .map(str::to_string)
        })
        .unwrap_or_default()
}

fn response_message(value: &serde_json::Value) -> String {
    value["message"]
        .as_str()
        .or_else(|| value["error"].as_str())
        .or_else(|| value["error"]["message"].as_str())
        .unwrap_or("Unknown Volcengine ASR error")
        .to_string()
}

fn parse_response_frame(frame: &[u8]) -> Result<Option<TranscriptEvent>, AppError> {
    if frame.len() < 8 {
        return Err(AppError::Config(
            "Volcengine ASR frame is too short".to_string(),
        ));
    }

    if frame[0] >> 4 != 1 || frame[0] & 0x0f == 0 {
        return Err(AppError::Config(
            "Unsupported Volcengine ASR protocol header".to_string(),
        ));
    }
    let header_size = ((frame[0] & 0x0f) as usize) * 4;
    if frame.len() < header_size + 4 {
        return Err(AppError::Config(
            "Volcengine ASR frame header is incomplete".to_string(),
        ));
    }

    let message_type = frame[1] >> 4;
    let flags = frame[1] & 0x0f;
    let serialization = frame[2] >> 4;
    let compression = frame[2] & 0x0f;
    if !matches!(compression, 0 | GZIP_COMPRESSION) {
        return Err(AppError::Config(
            "Unsupported Volcengine ASR response compression".to_string(),
        ));
    }

    let mut offset = header_size;
    let sequence = if flags & POS_SEQUENCE != 0 {
        if frame.len() < offset + 4 {
            return Err(AppError::Config(
                "Volcengine ASR frame sequence is incomplete".to_string(),
            ));
        }
        let seq = i32::from_be_bytes(frame[offset..offset + 4].try_into().unwrap());
        offset += 4;
        Some(seq)
    } else {
        None
    };
    let is_last = flags & NEG_SEQUENCE != 0;

    if flags & 0x04 != 0 {
        if frame.len() < offset + 4 {
            return Err(AppError::Config(
                "Volcengine ASR frame event is incomplete".to_string(),
            ));
        }
        offset += 4;
    }

    let error_code = if message_type == ERROR_RESPONSE {
        if frame.len() < offset + 8 {
            return Err(AppError::Config(
                "Volcengine ASR error frame is incomplete".to_string(),
            ));
        }
        let code = i32::from_be_bytes(frame[offset..offset + 4].try_into().unwrap());
        offset += 4;
        Some(code)
    } else {
        None
    };

    if frame.len() < offset + 4 {
        return Err(AppError::Config(
            "Volcengine ASR frame payload size is incomplete".to_string(),
        ));
    }
    let payload_size = u32::from_be_bytes(frame[offset..offset + 4].try_into().unwrap()) as usize;
    offset += 4;

    if payload_size > MAX_RESPONSE_BYTES {
        return Err(AppError::Config(
            "Volcengine ASR response limit exceeded".to_string(),
        ));
    }

    if payload_size == 0 && message_type == ERROR_RESPONSE {
        let message = error_code
            .map(|code| format!("Volcengine ASR error {}", code))
            .unwrap_or_else(|| "Unknown Volcengine ASR error".to_string());
        return Ok(Some(TranscriptEvent::Error { message }));
    }

    if frame.len() < offset + payload_size {
        return Err(AppError::Config(
            "Volcengine ASR frame payload is incomplete".to_string(),
        ));
    }
    let payload = &frame[offset..offset + payload_size];
    let payload = if compression == GZIP_COMPRESSION {
        ungzip_payload(payload)?
    } else {
        payload.to_vec()
    };

    if serialization != JSON_SERIALIZATION {
        if message_type == ERROR_RESPONSE {
            let message = error_code
                .map(|code| format!("Volcengine ASR error {}", code))
                .unwrap_or_else(|| "Unknown Volcengine ASR error".to_string());
            return Ok(Some(TranscriptEvent::Error { message }));
        }
        return Ok(None);
    }

    let value: serde_json::Value =
        serde_json::from_slice(&payload).map_err(|e| AppError::Config(e.to_string()))?;

    if message_type == ERROR_RESPONSE
        || value["code"]
            .as_i64()
            .is_some_and(|code| code != 0 && code != 20000000)
    {
        let message = response_message(&value);
        return Ok(Some(TranscriptEvent::Error {
            message: if message == "Unknown Volcengine ASR error" {
                error_code
                    .map(|code| format!("Volcengine ASR error {}", code))
                    .unwrap_or(message)
            } else {
                message
            },
        }));
    }

    if message_type != FULL_SERVER_RESPONSE {
        return Ok(None);
    }

    let text = extract_transcript(&value);
    if text.trim().is_empty() {
        return Ok(None);
    }

    let is_final = is_last || sequence.is_some_and(|seq| seq < 0);
    if is_final {
        Ok(Some(TranscriptEvent::Final {
            text,
            confidence: 1.0,
        }))
    } else {
        Ok(Some(TranscriptEvent::Partial { text }))
    }
}

async fn read_initial_response(ws: &mut WsStream) -> Result<(), AppError> {
    let next = tokio::time::timeout(
        std::time::Duration::from_secs(INITIAL_RESPONSE_TIMEOUT_SECS),
        ws.next(),
    )
    .await
    .map_err(|_| {
        AppError::Network(format!(
            "Volcengine ASR initial response timed out after {}s",
            INITIAL_RESPONSE_TIMEOUT_SECS
        ))
    })?;

    match next {
        Some(Ok(Message::Binary(data))) => match parse_response_frame(&data)? {
            Some(TranscriptEvent::Error { message }) => Err(AppError::Config(message)),
            _ => Ok(()),
        },
        Some(Ok(Message::Text(text))) => Err(AppError::Config(text)),
        Some(Ok(Message::Close(_))) | None => Err(AppError::Network(
            "Volcengine ASR WebSocket closed before initial response".to_string(),
        )),
        Some(Ok(_)) => Ok(()),
        Some(Err(e)) => Err(AppError::Network(e.to_string())),
    }
}

fn connect_id() -> String {
    format!(
        "opentypeless-{}-{}",
        std::process::id(),
        chrono::Utc::now().timestamp_millis()
    )
}

fn auth_error_message() -> String {
    "Volcengine Doubao ASR authentication failed. Use a Volcengine Speech API key, or old-console app_id:access_token; Ark LLM keys are separate and only work for Doubao LLM."
        .to_string()
}

fn map_connect_error(error: WsError) -> AppError {
    match error {
        WsError::Http(response) if response.status() == 401 || response.status() == 403 => {
            AppError::Auth(auth_error_message())
        }
        WsError::Http(response) => AppError::Api {
            status: response.status().as_u16(),
            body: String::new(),
        },
        other => AppError::Network(other.to_string()),
    }
}

#[async_trait]
impl SttProvider for VolcengineDoubaoProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        if config.api_key.trim().is_empty() {
            return Err(AppError::Auth(
                "Volcengine Doubao ASR API key is empty".to_string(),
            ));
        }

        let mut attempt = 0u32;
        loop {
            let mut builder = http::Request::builder()
                .uri(&self.url)
                .header("Host", VOLCENGINE_ASR_HOST)
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Sec-WebSocket-Version", "13")
                .header(
                    "Sec-WebSocket-Key",
                    tokio_tungstenite::tungstenite::handshake::client::generate_key(),
                )
                .header("X-Api-Resource-Id", resolve_resource_id(config))
                .header("X-Api-Connect-Id", connect_id());

            if let Some((app_key, access_key)) = config.api_key.split_once(':') {
                builder = builder
                    .header("X-Api-App-Key", app_key.trim())
                    .header("X-Api-Access-Key", access_key.trim());
            } else {
                builder = builder.header("X-Api-Key", config.api_key.trim());
            }

            let request = builder
                .body(())
                .map_err(|e| AppError::Config(e.to_string()))?;

            match connect_async(request).await {
                Ok((mut ws, _)) => {
                    self.sequence = 1;
                    self.sent_audio = false;
                    let frame = build_full_client_request_frame(config, self.sequence)?;
                    ws.send(Message::Binary(frame))
                        .await
                        .map_err(|e| AppError::Network(e.to_string()))?;
                    read_initial_response(&mut ws).await?;
                    self.ws = Some(ws);
                    tracing::info!("Volcengine Doubao ASR WebSocket connected");
                    return Ok(());
                }
                Err(e) => {
                    let error = map_connect_error(e);
                    if !error.is_retryable() {
                        return Err(error);
                    }
                    if attempt >= 2 {
                        return Err(error);
                    }

                    tracing::warn!(
                        "Volcengine Doubao ASR connect failed (attempt {}/3): {}",
                        attempt + 1,
                        error
                    );
                    attempt += 1;
                    tokio::time::sleep(std::time::Duration::from_millis(
                        1000 * 2u64.pow(attempt - 1),
                    ))
                    .await;
                }
            }
        }
    }

    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        if let Some(ws) = &mut self.ws {
            self.sequence += 1;
            let frame = build_audio_request_frame(chunk, self.sequence)?;
            ws.send(Message::Binary(frame))
                .await
                .map_err(|e| AppError::Network(e.to_string()))?;
            self.sent_audio = true;
        }
        Ok(())
    }

    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError> {
        let ws = match &mut self.ws {
            Some(ws) => ws,
            None => return Ok(None),
        };

        match ws.next().await {
            Some(Ok(Message::Binary(data))) => parse_response_frame(&data),
            Some(Ok(Message::Text(text))) => Ok(Some(TranscriptEvent::Error { message: text })),
            Some(Ok(Message::Close(_))) => {
                tracing::info!("Volcengine Doubao ASR WebSocket closed");
                Ok(None)
            }
            Some(Err(e)) => {
                tracing::error!("Volcengine Doubao ASR WebSocket error: {}", e);
                Ok(Some(TranscriptEvent::Error {
                    message: e.to_string(),
                }))
            }
            _ => Ok(None),
        }
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        let Some(mut ws) = self.ws.take() else {
            return Ok(None);
        };

        if !self.sent_audio {
            let _ = ws.close(None).await;
            return Ok(None);
        }

        self.sequence += 1;
        let final_frame = build_audio_request_frame(&[], -self.sequence.abs())?;
        ws.send(Message::Binary(final_frame))
            .await
            .map_err(|e| AppError::Network(e.to_string()))?;

        let mut final_text: Option<String> = None;
        let deadline = tokio::time::Instant::now() + std::time::Duration::from_secs(8);

        while tokio::time::Instant::now() < deadline {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            let next = tokio::time::timeout(remaining, ws.next()).await;
            match next {
                Ok(Some(Ok(Message::Binary(data)))) => match parse_response_frame(&data)? {
                    Some(TranscriptEvent::Final { text, .. }) => {
                        final_text = Some(text);
                        break;
                    }
                    Some(TranscriptEvent::Partial { text }) => {
                        final_text = Some(text);
                    }
                    Some(TranscriptEvent::Error { message }) => {
                        return Err(AppError::Config(message));
                    }
                    _ => {}
                },
                Ok(Some(Ok(Message::Close(_)))) | Ok(None) => break,
                Ok(Some(Ok(_))) => {}
                Ok(Some(Err(e))) => return Err(AppError::Network(e.to_string())),
                Err(_) => break,
            }
        }

        let _ = ws.close(None).await;
        tracing::info!("Volcengine Doubao ASR disconnected");
        Ok(final_text)
    }

    fn name(&self) -> &str {
        "Volcengine Doubao Realtime ASR"
    }
}

#[cfg(test)]
fn build_test_server_response_frame(sequence: i32, payload: &[u8]) -> Vec<u8> {
    let mut frame = Vec::with_capacity(12 + payload.len());
    frame.extend_from_slice(&header(
        FULL_SERVER_RESPONSE,
        if sequence < 0 { 0x03 } else { POS_SEQUENCE },
        JSON_SERIALIZATION,
        GZIP_COMPRESSION,
    ));
    frame.extend_from_slice(&sequence.to_be_bytes());
    frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
    frame.extend_from_slice(payload);
    frame
}

#[cfg(test)]
mod tests {
    use crate::stt::{SttConfig, TranscriptEvent};
    use futures_util::{SinkExt, StreamExt};
    use tokio::net::TcpListener;
    use tokio_tungstenite::accept_async;

    use super::*;

    #[test]
    fn unrelated_message_types_do_not_publish_transcripts() {
        let payload = gzip_payload(br#"{"result":{"text":"synthetic unrelated text"}}"#).unwrap();
        for message_type in 0u8..=15 {
            if matches!(message_type, FULL_SERVER_RESPONSE | ERROR_RESPONSE) {
                continue;
            }
            let mut frame = build_test_server_response_frame(-1, &payload);
            frame[1] = (message_type << 4) | NEG_WITH_SEQUENCE;
            assert!(
                parse_response_frame(&frame).unwrap().is_none(),
                "unexpected transcript from message type {message_type}"
            );
        }
    }

    #[test]
    fn unsupported_compression_cannot_be_parsed_as_plain_json() {
        let payload = br#"{"result":{"text":"synthetic transcript"}}"#;
        let mut frame = vec![
            0x11,
            FULL_SERVER_RESPONSE << 4,
            (JSON_SERIALIZATION << 4) | 0x02,
            0,
        ];
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(payload);
        assert!(parse_response_frame(&frame).is_err());
    }

    #[test]
    fn unsupported_protocol_version_is_rejected_before_transcript_parsing() {
        let payload = gzip_payload(br#"{"result":{"text":"synthetic transcript"}}"#).unwrap();
        let mut frame = build_test_server_response_frame(-1, &payload);
        frame[0] = 0x21;
        assert!(parse_response_frame(&frame).is_err());
    }

    #[test]
    fn compressed_response_cannot_expand_past_response_budget() {
        let compressed = gzip_payload(&vec![b' '; 1_048_577]).unwrap();
        assert!(compressed.len() < 4096);
        assert!(ungzip_payload(&compressed).is_err());
    }

    #[test]
    fn response_at_budget_remains_readable() {
        let original = vec![b' '; 1_048_576];
        let compressed = gzip_payload(&original).unwrap();
        assert_eq!(ungzip_payload(&compressed).unwrap(), original);
    }

    #[test]
    fn uncompressed_response_is_rejected_before_copying_oversized_payload() {
        let payload = vec![b' '; 1_048_577];
        let mut frame = vec![0x11, FULL_SERVER_RESPONSE << 4, JSON_SERIALIZATION << 4, 0];
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(&payload);
        let error = parse_response_frame(&frame).unwrap_err();
        assert!(error.to_string().contains("response limit"));
    }

    fn test_config(language: Option<&str>) -> SttConfig {
        SttConfig {
            api_key: "test-key".to_string(),
            language: language.map(str::to_string),
            smart_format: true,
            sample_rate: 16000,
            resource_id: None,
            operation_id: None,
            managed_audio: None,
            provider_region: None,
        }
    }

    #[test]
    fn resolves_default_and_custom_resource_id() {
        let mut config = test_config(None);
        assert_eq!(resolve_resource_id(&config), "volc.seedasr.sauc.duration");

        config.resource_id = Some(" volc.bigasr.sauc.duration ".to_string());
        assert_eq!(resolve_resource_id(&config), "volc.bigasr.sauc.duration");
    }

    #[test]
    fn builds_full_client_request_with_sequence_and_gzipped_json_payload() {
        let frame = build_full_client_request_frame(&test_config(Some("zh")), 1).unwrap();

        assert_eq!(frame[0], 0x11);
        assert_eq!(frame[1], 0x11);
        assert_eq!(frame[2], 0x11);
        assert_eq!(frame[3], 0x00);
        assert_eq!(i32::from_be_bytes(frame[4..8].try_into().unwrap()), 1);

        let payload_size = u32::from_be_bytes(frame[8..12].try_into().unwrap()) as usize;
        let payload = ungzip_payload(&frame[12..12 + payload_size]).unwrap();
        let value: serde_json::Value = serde_json::from_slice(&payload).unwrap();

        assert_eq!(value["audio"]["format"], "pcm");
        assert_eq!(value["audio"]["rate"], 16000);
        assert_eq!(value["audio"]["bits"], 16);
        assert_eq!(value["audio"]["channel"], 1);
        assert_eq!(value["audio"]["language"], "zh-CN");
        assert_eq!(value["request"]["model_name"], "bigmodel");
        assert_eq!(value["request"]["enable_itn"], true);
        assert_eq!(value["request"]["enable_punc"], true);
        assert_eq!(value["request"]["show_utterances"], true);
    }

    #[test]
    fn builds_final_audio_frame_with_negative_sequence() {
        let frame = build_audio_request_frame(b"\x01\x02", -2).unwrap();

        assert_eq!(frame[0], 0x11);
        assert_eq!(frame[1], 0x23);
        assert_eq!(frame[2], 0x01);
        assert_eq!(frame[3], 0x00);
        assert_eq!(i32::from_be_bytes(frame[4..8].try_into().unwrap()), -2);

        let payload_size = u32::from_be_bytes(frame[8..12].try_into().unwrap()) as usize;
        assert_eq!(
            ungzip_payload(&frame[12..12 + payload_size]).unwrap(),
            b"\x01\x02"
        );
    }

    fn build_test_error_response_frame(code: i32, payload: &[u8]) -> Vec<u8> {
        let mut frame = Vec::with_capacity(12 + payload.len());
        frame.extend_from_slice(&header(
            ERROR_RESPONSE,
            0x00,
            JSON_SERIALIZATION,
            GZIP_COMPRESSION,
        ));
        frame.extend_from_slice(&code.to_be_bytes());
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(payload);
        frame
    }

    #[test]
    fn parses_error_response_code_and_message_payload() {
        let payload = gzip_payload(
            serde_json::json!({
                "message": "invalid resource"
            })
            .to_string()
            .as_bytes(),
        )
        .unwrap();
        let frame = build_test_error_response_frame(400, &payload);

        let event = parse_response_frame(&frame).unwrap().unwrap();

        match event {
            TranscriptEvent::Error { message } => assert_eq!(message, "invalid resource"),
            other => panic!("expected error response, got {other:?}"),
        }
    }

    #[test]
    fn parses_final_response_without_sequence_flag() {
        let payload = gzip_payload(
            serde_json::json!({
                "result": { "text": "hello world" }
            })
            .to_string()
            .as_bytes(),
        )
        .unwrap();
        let mut frame = Vec::with_capacity(8 + payload.len());
        frame.extend_from_slice(&header(
            FULL_SERVER_RESPONSE,
            0x02,
            JSON_SERIALIZATION,
            GZIP_COMPRESSION,
        ));
        frame.extend_from_slice(&(payload.len() as u32).to_be_bytes());
        frame.extend_from_slice(&payload);

        let event = parse_response_frame(&frame).unwrap().unwrap();

        match event {
            TranscriptEvent::Final { text, confidence } => {
                assert_eq!(text, "hello world");
                assert_eq!(confidence, 1.0);
            }
            other => panic!("expected final transcript, got {other:?}"),
        }
    }

    #[test]
    fn parses_final_server_response_text() {
        let payload = gzip_payload(
            serde_json::json!({
                "result": {
                    "text": "hello world",
                    "utterances": [
                        {"text": "hello world", "definite": true}
                    ]
                }
            })
            .to_string()
            .as_bytes(),
        )
        .unwrap();
        let frame = build_test_server_response_frame(-3, &payload);

        let event = parse_response_frame(&frame).unwrap().unwrap();

        match event {
            TranscriptEvent::Final { text, confidence } => {
                assert_eq!(text, "hello world");
                assert_eq!(confidence, 1.0);
            }
            other => panic!("expected final transcript, got {other:?}"),
        }
    }

    #[tokio::test]
    async fn connect_fails_when_initial_server_response_is_error() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let addr = listener.local_addr().unwrap();

        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();
            let _ = ws.next().await.unwrap().unwrap();

            let payload = gzip_payload(
                serde_json::json!({
                    "message": "invalid resource"
                })
                .to_string()
                .as_bytes(),
            )
            .unwrap();
            let frame = build_test_error_response_frame(400, &payload);
            ws.send(Message::Binary(frame)).await.unwrap();
        });

        let mut provider = VolcengineDoubaoProvider::with_url(format!("ws://{}", addr));
        let err = provider.connect(&test_config(None)).await.unwrap_err();

        assert!(err.to_string().contains("invalid resource"));
        server.await.unwrap();
    }

    #[test]
    fn maps_websocket_unauthorized_to_actionable_auth_error() {
        let response = http::Response::builder().status(401).body(None).unwrap();

        let error = map_connect_error(WsError::Http(response));

        match error {
            AppError::Auth(message) => {
                assert!(message.contains("Volcengine Speech API key"));
                assert!(message.contains("Ark LLM keys"));
            }
            other => panic!("expected auth error, got {other}"),
        }
    }

    #[test]
    fn handshake_errors_preserve_status_without_echoing_private_response_body() {
        for status in [400, 429, 500, 503] {
            let response = http::Response::builder()
                .status(status)
                .body(Some(b"synthetic-api-key private spoken words".to_vec()))
                .unwrap();
            let error = map_connect_error(WsError::Http(response));
            assert!(!error.to_string().contains("synthetic-api-key"));
            assert!(!error.to_string().contains("private spoken words"));
            assert_eq!(error.is_retryable(), status >= 500);
            match error {
                AppError::Api {
                    status: actual,
                    body,
                } => {
                    assert_eq!(actual, status);
                    assert!(body.is_empty());
                }
                other => panic!("expected API status, got {other}"),
            }
        }
    }

    #[tokio::test]
    async fn retry_logs_exclude_http_handshake_response_body() {
        use tokio::io::{AsyncReadExt, AsyncWriteExt};
        use tracing::instrument::WithSubscriber;

        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let private_body = "synthetic-api-key private spoken words";
        let server = tokio::spawn(async move {
            for _attempt in 0..3 {
                let (mut socket, _) = listener.accept().await.unwrap();
                let mut request = Vec::new();
                while !request.ends_with(b"\r\n\r\n") {
                    let mut byte = [0u8; 1];
                    socket.read_exact(&mut byte).await.unwrap();
                    request.push(byte[0]);
                }
                let response = format!(
                    "HTTP/1.1 503 Service Unavailable\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    private_body.len(),
                    private_body
                );
                socket.write_all(response.as_bytes()).await.unwrap();
            }
        });
        let logs = std::sync::Arc::new(std::sync::Mutex::new(Vec::new()));
        let writer = logs.clone();
        let subscriber = tracing_subscriber::fmt()
            .without_time()
            .with_ansi(false)
            .with_max_level(tracing::Level::WARN)
            .with_writer(move || TestLogWriter(writer.clone()))
            .finish();
        let mut provider = VolcengineDoubaoProvider::with_url(format!("ws://{address}"));
        let error = provider
            .connect(&test_config(None))
            .with_subscriber(subscriber)
            .await
            .unwrap_err();
        server.await.unwrap();
        let text = String::from_utf8(logs.lock().unwrap().clone()).unwrap();
        assert_eq!(text.matches("connect failed").count(), 2);
        assert!(text.contains("503"));
        assert!(!text.contains(private_body));
        assert!(!text.contains("synthetic-api-key"));
        assert!(!error.to_string().contains(private_body));
    }

    struct TestLogWriter(std::sync::Arc<std::sync::Mutex<Vec<u8>>>);

    impl Write for TestLogWriter {
        fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
            self.0.lock().unwrap().extend_from_slice(bytes);
            Ok(bytes.len())
        }

        fn flush(&mut self) -> std::io::Result<()> {
            Ok(())
        }
    }
}
