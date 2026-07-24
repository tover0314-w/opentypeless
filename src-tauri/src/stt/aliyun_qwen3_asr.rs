use std::{collections::HashSet, time::Duration};

use async_trait::async_trait;
use base64::{engine::general_purpose::STANDARD, Engine as _};
use futures_util::{SinkExt, StreamExt};
use tokio_tungstenite::{
    connect_async,
    tungstenite::{Error as WsError, Message},
};
use uuid::Uuid;

use crate::error::AppError;

use super::{SttConfig, SttProvider, TranscriptEvent};

type WsStream =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

pub const ALIYUN_QWEN3_ASR_PROVIDER: &str = "aliyun-qwen3-asr";
pub const ALIYUN_QWEN3_ASR_URL: &str =
    "wss://dashscope.aliyuncs.com/api-ws/v1/realtime?model=qwen3-asr-flash-realtime";
const AUDIO_CHUNK_BYTES: usize = 3200;
const INITIAL_RESPONSE_TIMEOUT: Duration = Duration::from_secs(5);
const FINISH_RESPONSE_TIMEOUT: Duration = Duration::from_secs(8);

enum ServerEvent {
    Transcript(TranscriptEvent),
    SessionCreated,
    SessionUpdated,
    SessionFinished,
    Ignored,
}

pub struct AliyunQwen3AsrProvider {
    ws: Option<WsStream>,
    pending_audio: Vec<u8>,
    completed_item_ids: HashSet<String>,
    url: String,
}

impl Default for AliyunQwen3AsrProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl AliyunQwen3AsrProvider {
    pub fn new() -> Self {
        Self {
            ws: None,
            pending_audio: Vec::with_capacity(AUDIO_CHUNK_BYTES),
            completed_item_ids: HashSet::new(),
            url: ALIYUN_QWEN3_ASR_URL.to_string(),
        }
    }

    #[cfg(test)]
    fn with_url(url: String) -> Self {
        let mut provider = Self::new();
        provider.url = url;
        provider
    }

    fn build_request(&self, api_key: &str) -> Result<http::Request<()>, AppError> {
        http::Request::builder()
            .uri(&self.url)
            .header("Authorization", format!("Bearer {api_key}"))
            .header("User-Agent", "OpenTypeless/1")
            .header("Host", "dashscope.aliyuncs.com")
            .header("Connection", "Upgrade")
            .header("Upgrade", "websocket")
            .header("Sec-WebSocket-Version", "13")
            .header(
                "Sec-WebSocket-Key",
                tokio_tungstenite::tungstenite::handshake::client::generate_key(),
            )
            .body(())
            .map_err(|error| AppError::Config(error.to_string()))
    }

    async fn send_json(ws: &mut WsStream, value: serde_json::Value) -> Result<(), AppError> {
        ws.send(Message::Text(value.to_string()))
            .await
            .map_err(|error| AppError::Network(error.to_string()))
    }

    async fn send_session_update(&mut self, config: &SttConfig) -> Result<(), AppError> {
        let mut session = serde_json::json!({
            "input_audio_format": "pcm",
            "sample_rate": config.sample_rate,
            "turn_detection": {
                "type": "server_vad",
                "threshold": 0.0,
                "silence_duration_ms": 400
            }
        });
        if let Some(language) = qwen_language(config.language.as_deref()) {
            session["input_audio_transcription"] = serde_json::json!({ "language": language });
        }

        let ws = self
            .ws
            .as_mut()
            .ok_or_else(|| AppError::Network("Aliyun Qwen3 ASR is not connected".to_string()))?;
        Self::send_json(
            ws,
            serde_json::json!({
                "event_id": event_id(),
                "type": "session.update",
                "session": session,
            }),
        )
        .await
    }

    async fn append_audio(&mut self, audio: &[u8]) -> Result<(), AppError> {
        let ws = self
            .ws
            .as_mut()
            .ok_or_else(|| AppError::Network("Aliyun Qwen3 ASR is not connected".to_string()))?;
        Self::send_json(
            ws,
            serde_json::json!({
                "event_id": event_id(),
                "type": "input_audio_buffer.append",
                "audio": STANDARD.encode(audio),
            }),
        )
        .await
    }

    async fn flush_pending_audio(&mut self) -> Result<(), AppError> {
        if self.pending_audio.is_empty() {
            return Ok(());
        }
        let audio = std::mem::take(&mut self.pending_audio);
        self.append_audio(&audio).await
    }

    async fn wait_for_session_event(
        &mut self,
        expected: fn(&ServerEvent) -> bool,
    ) -> Result<(), AppError> {
        let ws = self
            .ws
            .as_mut()
            .ok_or_else(|| AppError::Network("Aliyun Qwen3 ASR is not connected".to_string()))?;
        loop {
            let next = tokio::time::timeout(INITIAL_RESPONSE_TIMEOUT, ws.next())
                .await
                .map_err(|_| {
                    AppError::Network("Aliyun Qwen3 ASR session setup timed out".to_string())
                })?;
            match next {
                Some(Ok(Message::Text(text))) => {
                    let event = parse_server_message(&text)?;
                    if let ServerEvent::Transcript(TranscriptEvent::Error { message }) = &event {
                        return Err(AppError::Config(message.clone()));
                    }
                    if expected(&event) {
                        return Ok(());
                    }
                }
                Some(Ok(Message::Close(_))) | None => {
                    return Err(AppError::Network(
                        "Aliyun Qwen3 ASR WebSocket closed during session setup".to_string(),
                    ));
                }
                Some(Err(error)) => return Err(AppError::Network(error.to_string())),
                _ => {}
            }
        }
    }
}

fn event_id() -> String {
    format!("event_{}", Uuid::new_v4())
}

fn qwen_language(language: Option<&str>) -> Option<&str> {
    let language = language?.trim();
    match language {
        "zh" | "yue" | "en" | "ja" | "de" | "ko" | "ru" | "fr" | "pt" | "ar" | "it" | "es"
        | "hi" | "id" | "th" | "tr" | "uk" | "vi" | "cs" | "da" | "fil" | "fi" | "is" | "ms"
        | "no" | "pl" | "sv" => Some(language),
        _ => None,
    }
}

fn parse_server_message(text: &str) -> Result<ServerEvent, AppError> {
    let value: serde_json::Value =
        serde_json::from_str(text).map_err(|error| AppError::Config(error.to_string()))?;
    match value["type"].as_str().unwrap_or_default() {
        "session.created" => Ok(ServerEvent::SessionCreated),
        "session.updated" => Ok(ServerEvent::SessionUpdated),
        "session.finished" => Ok(ServerEvent::SessionFinished),
        "input_audio_buffer.speech_started" => {
            Ok(ServerEvent::Transcript(TranscriptEvent::SpeechStarted))
        }
        "input_audio_buffer.speech_stopped" => {
            Ok(ServerEvent::Transcript(TranscriptEvent::SpeechEnded))
        }
        "conversation.item.input_audio_transcription.text" => {
            let text = format!(
                "{}{}",
                value["text"].as_str().unwrap_or_default(),
                value["stash"].as_str().unwrap_or_default()
            );
            if text.trim().is_empty() {
                Ok(ServerEvent::Ignored)
            } else {
                Ok(ServerEvent::Transcript(TranscriptEvent::Partial { text }))
            }
        }
        "conversation.item.input_audio_transcription.completed" => {
            let text = value["transcript"].as_str().unwrap_or_default().to_string();
            if text.trim().is_empty() {
                Ok(ServerEvent::Ignored)
            } else {
                Ok(ServerEvent::Transcript(TranscriptEvent::Final {
                    text,
                    confidence: 1.0,
                }))
            }
        }
        "conversation.item.input_audio_transcription.failed" | "error" => {
            let message = value["error"]["message"]
                .as_str()
                .or_else(|| value["message"].as_str())
                .unwrap_or("Unknown Aliyun Qwen3 ASR error")
                .to_string();
            Ok(ServerEvent::Transcript(TranscriptEvent::Error { message }))
        }
        _ => Ok(ServerEvent::Ignored),
    }
}

fn completed_item_id(message: &str) -> Option<String> {
    let value: serde_json::Value = serde_json::from_str(message).ok()?;
    (value["type"].as_str() == Some("conversation.item.input_audio_transcription.completed"))
        .then(|| value["item_id"].as_str().unwrap_or_default().to_string())
        .filter(|item_id| !item_id.is_empty())
}

fn map_connect_error(error: WsError) -> AppError {
    match error {
        WsError::Http(response) if response.status() == 401 || response.status() == 403 => {
            AppError::Auth("Aliyun Qwen3 ASR authentication failed. Check your DashScope API key and realtime ASR entitlement.".to_string())
        }
        WsError::Http(response) => AppError::Api {
            status: response.status().as_u16(),
            body: response.status().to_string(),
        },
        other => AppError::Network(other.to_string()),
    }
}

#[async_trait]
impl SttProvider for AliyunQwen3AsrProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        if config.api_key.trim().is_empty() {
            return Err(AppError::Auth(
                "Aliyun Qwen3 ASR API key is empty".to_string(),
            ));
        }

        let request = self.build_request(config.api_key.trim())?;
        let (ws, _) = connect_async(request).await.map_err(map_connect_error)?;
        self.ws = Some(ws);
        self.pending_audio.clear();
        self.completed_item_ids.clear();
        self.wait_for_session_event(|event| matches!(event, ServerEvent::SessionCreated))
            .await?;
        self.send_session_update(config).await?;
        self.wait_for_session_event(|event| matches!(event, ServerEvent::SessionUpdated))
            .await?;
        tracing::info!("Aliyun Qwen3 ASR WebSocket connected");
        Ok(())
    }

    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        self.pending_audio.extend_from_slice(chunk);
        while self.pending_audio.len() >= AUDIO_CHUNK_BYTES {
            let audio: Vec<u8> = self.pending_audio.drain(..AUDIO_CHUNK_BYTES).collect();
            self.append_audio(&audio).await?;
        }
        Ok(())
    }

    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError> {
        let ws = match &mut self.ws {
            Some(ws) => ws,
            None => return Ok(None),
        };
        match ws.next().await {
            Some(Ok(Message::Text(text))) => {
                let item_id = completed_item_id(&text);
                match parse_server_message(&text)? {
                    ServerEvent::Transcript(TranscriptEvent::Final { text, confidence }) => {
                        if let Some(item_id) = item_id {
                            if !self.completed_item_ids.insert(item_id) {
                                return Ok(None);
                            }
                        }
                        Ok(Some(TranscriptEvent::Final { text, confidence }))
                    }
                    ServerEvent::Transcript(event) => Ok(Some(event)),
                    _ => Ok(None),
                }
            }
            Some(Ok(Message::Close(_))) | None => Ok(None),
            Some(Err(error)) => Ok(Some(TranscriptEvent::Error {
                message: error.to_string(),
            })),
            _ => Ok(None),
        }
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        self.flush_pending_audio().await?;
        let Some(mut ws) = self.ws.take() else {
            return Ok(None);
        };
        Self::send_json(
            &mut ws,
            serde_json::json!({
                "event_id": event_id(),
                "type": "session.finish",
            }),
        )
        .await?;

        let deadline = tokio::time::Instant::now() + FINISH_RESPONSE_TIMEOUT;
        let mut final_texts = Vec::new();
        while tokio::time::Instant::now() < deadline {
            let remaining = deadline.saturating_duration_since(tokio::time::Instant::now());
            match tokio::time::timeout(remaining, ws.next()).await {
                Ok(Some(Ok(Message::Text(text)))) => {
                    let item_id = completed_item_id(&text);
                    match parse_server_message(&text)? {
                        ServerEvent::SessionFinished => break,
                        ServerEvent::Transcript(TranscriptEvent::Final { text, .. }) => {
                            let is_new = item_id
                                .map(|item_id| self.completed_item_ids.insert(item_id))
                                .unwrap_or(true);
                            if is_new {
                                final_texts.push(text);
                            }
                        }
                        ServerEvent::Transcript(TranscriptEvent::Error { message }) => {
                            return Err(AppError::Config(message));
                        }
                        _ => {}
                    }
                }
                Ok(Some(Ok(Message::Close(_)))) | Ok(None) | Err(_) => break,
                Ok(Some(Ok(_))) => {}
                Ok(Some(Err(error))) => return Err(AppError::Network(error.to_string())),
            }
        }
        let _ = ws.close(None).await;
        tracing::info!("Aliyun Qwen3 ASR disconnected");
        Ok((!final_texts.is_empty()).then(|| final_texts.join(" ")))
    }

    fn name(&self) -> &str {
        "Aliyun Qwen3 Realtime ASR"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use futures_util::{SinkExt, StreamExt};
    use tokio::net::TcpListener;
    use tokio_tungstenite::accept_async;

    fn test_config(language: Option<&str>) -> SttConfig {
        SttConfig {
            api_key: "test-key".to_string(),
            language: language.map(str::to_string),
            smart_format: true,
            sample_rate: 16_000,
            resource_id: None,
            operation_id: None,
            managed_audio: None,
        }
    }

    #[test]
    fn builds_authorized_beijing_realtime_request() {
        let provider = AliyunQwen3AsrProvider::new();
        let request = provider.build_request("test-key").unwrap();

        assert_eq!(request.uri(), ALIYUN_QWEN3_ASR_URL);
        assert_eq!(request.headers()["authorization"], "Bearer test-key");
        assert_eq!(request.headers()["user-agent"], "OpenTypeless/1");
    }

    #[test]
    fn maps_supported_languages_and_falls_back_to_auto_detect() {
        assert_eq!(qwen_language(Some("zh")), Some("zh"));
        assert_eq!(qwen_language(Some("nl")), None);
        assert_eq!(qwen_language(None), None);
    }

    #[test]
    fn parses_partial_using_confirmed_text_and_stash() {
        let event = parse_server_message(
            r#"{"type":"conversation.item.input_audio_transcription.text","text":"hello ","stash":"world"}"#,
        )
        .unwrap();

        match event {
            ServerEvent::Transcript(TranscriptEvent::Partial { text }) => {
                assert_eq!(text, "hello world");
            }
            _ => panic!("expected partial transcript"),
        }
    }

    #[test]
    fn parses_completed_transcript_and_error() {
        let completed = parse_server_message(
            r#"{"type":"conversation.item.input_audio_transcription.completed","transcript":"hello world"}"#,
        )
        .unwrap();
        let error =
            parse_server_message(r#"{"type":"error","error":{"message":"bad key"}}"#).unwrap();

        assert!(matches!(
            completed,
            ServerEvent::Transcript(TranscriptEvent::Final { text, .. }) if text == "hello world"
        ));
        assert!(matches!(
            error,
            ServerEvent::Transcript(TranscriptEvent::Error { message }) if message == "bad key"
        ));
    }

    #[tokio::test]
    async fn streams_100ms_audio_and_drains_the_final_transcript() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();
            ws.send(Message::Text(
                serde_json::json!({ "type": "session.created" }).to_string(),
            ))
            .await
            .unwrap();

            let update = ws.next().await.unwrap().unwrap().into_text().unwrap();
            assert_eq!(
                serde_json::from_str::<serde_json::Value>(&update).unwrap()["type"],
                "session.update"
            );
            ws.send(Message::Text(
                serde_json::json!({ "type": "session.updated" }).to_string(),
            ))
            .await
            .unwrap();

            let append = ws.next().await.unwrap().unwrap().into_text().unwrap();
            let append: serde_json::Value = serde_json::from_str(&append).unwrap();
            assert_eq!(append["type"], "input_audio_buffer.append");
            assert_eq!(
                STANDARD
                    .decode(append["audio"].as_str().unwrap())
                    .unwrap()
                    .len(),
                3200
            );
            ws.send(Message::Text(
                serde_json::json!({
                    "type": "conversation.item.input_audio_transcription.text",
                    "text": "hello ",
                    "stash": "world"
                })
                .to_string(),
            ))
            .await
            .unwrap();

            let finish = ws.next().await.unwrap().unwrap().into_text().unwrap();
            assert_eq!(
                serde_json::from_str::<serde_json::Value>(&finish).unwrap()["type"],
                "session.finish"
            );
            ws.send(Message::Text(
                serde_json::json!({
                    "type": "conversation.item.input_audio_transcription.completed",
                    "item_id": "item-1",
                    "transcript": "hello world"
                })
                .to_string(),
            ))
            .await
            .unwrap();
            ws.send(Message::Text(
                serde_json::json!({ "type": "session.finished" }).to_string(),
            ))
            .await
            .unwrap();
        });

        let mut provider = AliyunQwen3AsrProvider::with_url(format!("ws://{address}"));
        provider.connect(&test_config(Some("zh"))).await.unwrap();
        for _ in 0..5 {
            provider.send_audio(&vec![1; 640]).await.unwrap();
        }
        assert!(matches!(
            provider.recv_transcript().await.unwrap(),
            Some(TranscriptEvent::Partial { text }) if text == "hello world"
        ));
        assert_eq!(
            provider.disconnect().await.unwrap().as_deref(),
            Some("hello world")
        );
        server.await.unwrap();
    }

    #[test]
    fn maps_unauthorized_websocket_upgrade_to_auth_error() {
        let response = http::Response::builder().status(401).body(None).unwrap();
        assert!(matches!(
            map_connect_error(WsError::Http(response)),
            AppError::Auth(_)
        ));
    }
}
