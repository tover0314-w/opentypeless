use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use std::time::Duration;
use tokio_tungstenite::{connect_async, tungstenite::Message};

use crate::error::AppError;

use super::{SttConfig, SttProvider, TranscriptEvent};

type WsStream =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

// AssemblyAI Universal Streaming (v3) rejects binary frames outside 50-1000 ms.
// OpenTypeless capture defaults to 20 ms chunks, so we re-buffer before send.
const MIN_CHUNK_MS: u32 = 50;
const TARGET_CHUNK_MS: u32 = 100;
const MAX_CHUNK_MS: u32 = 1000;
const BYTES_PER_SAMPLE: u32 = 2; // PCM s16le mono
const SPEECH_MODEL: &str = "universal-3-5-pro";
const TERMINATION_TIMEOUT: Duration = Duration::from_secs(5);

pub struct AssemblyAiProvider {
    ws: Option<WsStream>,
    pending: Vec<u8>,
    sample_rate: u32,
    url_override: Option<String>,
}

impl Default for AssemblyAiProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl AssemblyAiProvider {
    pub fn new() -> Self {
        Self {
            ws: None,
            pending: Vec::new(),
            sample_rate: 16000,
            url_override: None,
        }
    }

    #[cfg(test)]
    fn with_url(url: String) -> Self {
        Self {
            url_override: Some(url),
            ..Self::new()
        }
    }

    fn resolved_sample_rate(config: &SttConfig) -> u32 {
        if config.sample_rate == 0 {
            16000
        } else {
            config.sample_rate
        }
    }

    fn build_url(sample_rate: u32) -> String {
        format!(
            "wss://streaming.assemblyai.com/v3/ws?\
             sample_rate={}&\
             encoding=pcm_s16le&\
             speech_model={}&\
             format_turns=true",
            sample_rate, SPEECH_MODEL
        )
    }

    fn connection_url(&self, sample_rate: u32) -> String {
        self.url_override
            .clone()
            .unwrap_or_else(|| Self::build_url(sample_rate))
    }

    fn bytes_for_ms(&self, ms: u32) -> usize {
        let rate = self.sample_rate.max(1);
        (rate as usize) * (ms as usize) * (BYTES_PER_SAMPLE as usize) / 1000
    }

    async fn flush_ready(&mut self, force: bool) -> Result<(), AppError> {
        let min_bytes = self.bytes_for_ms(MIN_CHUNK_MS);
        let target_bytes = self.bytes_for_ms(TARGET_CHUNK_MS);
        let max_bytes = self.bytes_for_ms(MAX_CHUNK_MS);

        while self.pending.len() >= min_bytes || (force && !self.pending.is_empty()) {
            if !force && self.pending.len() < target_bytes {
                break;
            }

            let take = if force {
                self.pending.len().min(max_bytes)
            } else {
                target_bytes.min(self.pending.len()).min(max_bytes)
            };

            // Never send a final undersized frame if we can avoid it; pad only on force
            // when residual audio is shorter than the minimum (end of utterance).
            let mut frame = self.pending[..take].to_vec();
            if take < min_bytes {
                if !force {
                    break;
                }
                // Pad short residual with silence so AssemblyAI accepts the last frame.
                frame.resize(min_bytes, 0);
                self.send_frame(&frame).await?;
                self.pending.drain(..take);
                break;
            }

            self.send_frame(&frame).await?;
            self.pending.drain(..take);
        }

        Ok(())
    }

    async fn send_frame(&mut self, frame: &[u8]) -> Result<(), AppError> {
        if let Some(ws) = &mut self.ws {
            ws.send(Message::Binary(frame.to_vec()))
                .await
                .map_err(|e| AppError::Network(e.to_string()))?;
        }
        Ok(())
    }
}

fn parse_transcript_message(text: &str) -> Result<Option<TranscriptEvent>, AppError> {
    let v: serde_json::Value =
        serde_json::from_str(text).map_err(|e| AppError::Config(e.to_string()))?;
    let msg_type = v["type"].as_str().unwrap_or("");

    match msg_type {
        "Begin" => {
            tracing::info!(
                "AssemblyAI session started: {}",
                v["id"].as_str().unwrap_or("")
            );
            Ok(None)
        }
        "Turn" => {
            let transcript = v["transcript"].as_str().unwrap_or("").to_string();
            if transcript.is_empty() {
                return Ok(None);
            }

            let end_of_turn = v["end_of_turn"].as_bool().unwrap_or(false);
            let turn_is_formatted = v
                .get("turn_is_formatted")
                .and_then(|value| value.as_bool())
                .unwrap_or(false);

            if end_of_turn && turn_is_formatted {
                Ok(Some(TranscriptEvent::Final {
                    text: transcript,
                    confidence: 1.0,
                }))
            } else {
                Ok(Some(TranscriptEvent::Partial { text: transcript }))
            }
        }
        "Termination" => {
            tracing::info!("AssemblyAI session terminated");
            Ok(Some(TranscriptEvent::SpeechEnded))
        }
        "Error" => {
            let msg = v["error"]
                .as_str()
                .or_else(|| v["message"].as_str())
                .unwrap_or("Unknown error")
                .to_string();
            Ok(Some(TranscriptEvent::Error { message: msg }))
        }
        _ => Ok(None),
    }
}

fn append_final_text(final_text: &mut String, text: &str) {
    let trimmed = text.trim();
    if trimmed.is_empty() {
        return;
    }
    if !final_text.is_empty() {
        final_text.push(' ');
    }
    final_text.push_str(trimmed);
}

async fn read_until_termination_with_timeout(
    ws: &mut WsStream,
    timeout: Duration,
) -> Result<Option<String>, AppError> {
    let deadline = tokio::time::Instant::now() + timeout;
    let mut final_text = String::new();

    loop {
        let now = tokio::time::Instant::now();
        if now >= deadline {
            return Err(AppError::Timeout(timeout));
        }

        let next = tokio::time::timeout(deadline - now, ws.next()).await;
        match next {
            Ok(Some(Ok(Message::Text(text)))) => match parse_transcript_message(&text)? {
                Some(TranscriptEvent::Final { text, .. }) => {
                    append_final_text(&mut final_text, &text);
                }
                Some(TranscriptEvent::SpeechEnded) => break,
                Some(TranscriptEvent::Error { message }) => {
                    return Err(AppError::Config(message));
                }
                _ => {}
            },
            Ok(Some(Ok(Message::Close(_))) | None) => {
                return Err(AppError::Network(
                    "AssemblyAI WebSocket closed before Termination".to_string(),
                ));
            }
            Ok(Some(Err(e))) => return Err(AppError::Network(e.to_string())),
            Ok(Some(Ok(_))) => {}
            Err(_) => return Err(AppError::Timeout(timeout)),
        }
    }

    Ok((!final_text.is_empty()).then_some(final_text))
}

async fn read_until_termination(ws: &mut WsStream) -> Result<Option<String>, AppError> {
    read_until_termination_with_timeout(ws, TERMINATION_TIMEOUT).await
}

#[async_trait]
impl SttProvider for AssemblyAiProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        self.sample_rate = Self::resolved_sample_rate(config);
        let url = self.connection_url(self.sample_rate);
        self.pending.clear();

        let mut attempt = 0u32;
        loop {
            let request = http::Request::builder()
                .uri(&url)
                .header("Authorization", &config.api_key)
                .header("Host", "streaming.assemblyai.com")
                .header("Connection", "Upgrade")
                .header("Upgrade", "websocket")
                .header("Sec-WebSocket-Version", "13")
                .header(
                    "Sec-WebSocket-Key",
                    tokio_tungstenite::tungstenite::handshake::client::generate_key(),
                )
                .body(())
                .map_err(|e| AppError::Config(e.to_string()))?;

            match connect_async(request).await {
                Ok((ws, _)) => {
                    self.ws = Some(ws);
                    tracing::info!(
                        "AssemblyAI WebSocket connected (re-buffer {}-{} ms for v3)",
                        MIN_CHUNK_MS,
                        TARGET_CHUNK_MS
                    );
                    return Ok(());
                }
                Err(e) if attempt < 2 => {
                    tracing::warn!(
                        "AssemblyAI connect failed (attempt {}/3): {}",
                        attempt + 1,
                        e
                    );
                    attempt += 1;
                    tokio::time::sleep(std::time::Duration::from_millis(
                        1000 * 2u64.pow(attempt - 1),
                    ))
                    .await;
                }
                Err(e) => return Err(AppError::Network(e.to_string())),
            }
        }
    }

    async fn send_audio(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        if chunk.is_empty() {
            return Ok(());
        }
        self.pending.extend_from_slice(chunk);
        self.flush_ready(false).await
    }

    async fn recv_transcript(&mut self) -> Result<Option<TranscriptEvent>, AppError> {
        let ws = match &mut self.ws {
            Some(ws) => ws,
            None => return Ok(None),
        };

        match ws.next().await {
            Some(Ok(Message::Text(text))) => parse_transcript_message(&text),
            Some(Ok(Message::Close(_))) => {
                tracing::info!("AssemblyAI WebSocket closed");
                Ok(None)
            }
            Some(Err(e)) => {
                tracing::error!("AssemblyAI WebSocket error: {}", e);
                Ok(Some(TranscriptEvent::Error {
                    message: e.to_string(),
                }))
            }
            _ => Ok(None),
        }
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        // Flush residual audio before Terminate so the last words are not dropped.
        self.flush_ready(true).await?;

        if let Some(mut ws) = self.ws.take() {
            let terminate = serde_json::json!({"type": "Terminate"});
            ws.send(Message::Text(terminate.to_string()))
                .await
                .map_err(|e| AppError::Network(e.to_string()))?;
            let final_text = read_until_termination(&mut ws).await?;
            let _ = ws.close(None).await;
            self.pending.clear();
            tracing::info!("AssemblyAI disconnected");
            return Ok(final_text);
        }
        self.ws = None;
        self.pending.clear();
        tracing::info!("AssemblyAI disconnected");
        Ok(None)
    }

    fn name(&self) -> &str {
        "AssemblyAI"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::net::TcpListener;
    use tokio_tungstenite::accept_async;

    fn test_config() -> SttConfig {
        SttConfig {
            api_key: "test-key".to_string(),
            language: None,
            smart_format: true,
            sample_rate: 16_000,
            resource_id: None,
            operation_id: None,
            managed_audio: None,
            provider_region: None,
        }
    }

    #[test]
    fn chunk_size_matches_assemblyai_pcm16_duration_bounds() {
        let provider = AssemblyAiProvider::new();

        assert_eq!(provider.bytes_for_ms(MIN_CHUNK_MS), 1600);
        assert_eq!(provider.bytes_for_ms(TARGET_CHUNK_MS), 3200);
        assert_eq!(provider.bytes_for_ms(MAX_CHUNK_MS), 32000);
    }

    #[test]
    fn build_url_pins_current_streaming_model_and_pcm_encoding() {
        let url = AssemblyAiProvider::build_url(16_000);

        assert!(url.contains("sample_rate=16000"));
        assert!(url.contains("encoding=pcm_s16le"));
        assert!(url.contains("speech_model=universal-3-5-pro"));
    }

    #[test]
    fn parses_partial_turn_when_not_end_of_turn() {
        let message = serde_json::json!({
            "type": "Turn",
            "end_of_turn": false,
            "turn_is_formatted": false,
            "transcript": "hello"
        })
        .to_string();

        let event = parse_transcript_message(&message).unwrap();

        match event {
            Some(TranscriptEvent::Partial { text }) => assert_eq!(text, "hello"),
            other => panic!("expected partial transcript, got {other:?}"),
        }
    }

    #[test]
    fn ignores_unformatted_end_of_turn_as_partial() {
        let message = serde_json::json!({
            "type": "Turn",
            "end_of_turn": true,
            "turn_is_formatted": false,
            "transcript": "hello world"
        })
        .to_string();

        let event = parse_transcript_message(&message).unwrap();

        match event {
            Some(TranscriptEvent::Partial { text }) => assert_eq!(text, "hello world"),
            other => panic!("expected partial transcript, got {other:?}"),
        }
    }

    #[test]
    fn missing_turn_is_formatted_does_not_finalize_turn() {
        let message = serde_json::json!({
            "type": "Turn",
            "end_of_turn": true,
            "transcript": "hello"
        })
        .to_string();

        let event = parse_transcript_message(&message).unwrap();

        match event {
            Some(TranscriptEvent::Partial { text }) => assert_eq!(text, "hello"),
            other => panic!("expected partial transcript, got {other:?}"),
        }
    }

    #[test]
    fn parses_formatted_end_of_turn_as_final() {
        let message = serde_json::json!({
            "type": "Turn",
            "end_of_turn": true,
            "turn_is_formatted": true,
            "transcript": "Hello world."
        })
        .to_string();

        let event = parse_transcript_message(&message).unwrap();

        match event {
            Some(TranscriptEvent::Final { text, confidence }) => {
                assert_eq!(text, "Hello world.");
                assert!((confidence - 1.0).abs() < f32::EPSILON);
            }
            other => panic!("expected final transcript, got {other:?}"),
        }
    }

    #[test]
    fn parses_termination_as_speech_ended() {
        let event = parse_transcript_message(r#"{"type":"Termination"}"#).unwrap();

        assert!(matches!(event, Some(TranscriptEvent::SpeechEnded)));
    }

    #[test]
    fn appends_final_text_with_single_spaces() {
        let mut final_text = String::new();

        append_final_text(&mut final_text, " hello ");
        append_final_text(&mut final_text, "");
        append_final_text(&mut final_text, "world ");

        assert_eq!(final_text, "hello world");
    }

    #[tokio::test]
    async fn streams_buffered_frames_and_drains_final_turns_until_termination() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();

            ws.send(Message::Text(
                serde_json::json!({"type": "Begin"}).to_string(),
            ))
            .await
            .unwrap();

            let audio = ws.next().await.unwrap().unwrap();
            match audio {
                Message::Binary(bytes) => assert_eq!(bytes.len(), 3200),
                other => panic!("expected binary audio frame, got {other:?}"),
            }

            let terminate = ws.next().await.unwrap().unwrap().into_text().unwrap();
            assert_eq!(
                serde_json::from_str::<serde_json::Value>(&terminate).unwrap()["type"],
                "Terminate"
            );

            ws.send(Message::Text(
                serde_json::json!({
                    "type": "Turn",
                    "end_of_turn": true,
                    "turn_is_formatted": true,
                    "transcript": "hello"
                })
                .to_string(),
            ))
            .await
            .unwrap();
            ws.send(Message::Text(
                serde_json::json!({
                    "type": "Turn",
                    "end_of_turn": true,
                    "turn_is_formatted": true,
                    "transcript": "world"
                })
                .to_string(),
            ))
            .await
            .unwrap();
            ws.send(Message::Text(
                serde_json::json!({"type": "Termination"}).to_string(),
            ))
            .await
            .unwrap();
        });

        let mut provider = AssemblyAiProvider::with_url(format!("ws://{address}"));
        provider.connect(&test_config()).await.unwrap();
        for _ in 0..5 {
            provider.send_audio(&vec![1; 640]).await.unwrap();
        }

        assert_eq!(
            provider.disconnect().await.unwrap().as_deref(),
            Some("hello world")
        );
        server.await.unwrap();
    }

    #[tokio::test]
    async fn disconnect_pads_short_residual_before_terminate() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();

            let audio = ws.next().await.unwrap().unwrap();
            match audio {
                Message::Binary(bytes) => {
                    assert_eq!(bytes.len(), 1600);
                    assert_eq!(&bytes[..640], vec![1; 640].as_slice());
                    assert!(bytes[640..].iter().all(|byte| *byte == 0));
                }
                other => panic!("expected padded binary audio frame, got {other:?}"),
            }

            let terminate = ws.next().await.unwrap().unwrap().into_text().unwrap();
            assert_eq!(
                serde_json::from_str::<serde_json::Value>(&terminate).unwrap()["type"],
                "Terminate"
            );
            ws.send(Message::Text(
                serde_json::json!({"type": "Termination"}).to_string(),
            ))
            .await
            .unwrap();
        });

        let mut provider = AssemblyAiProvider::with_url(format!("ws://{address}"));
        provider.connect(&test_config()).await.unwrap();
        provider.send_audio(&vec![1; 640]).await.unwrap();

        assert_eq!(provider.disconnect().await.unwrap(), None);
        server.await.unwrap();
    }

    #[tokio::test]
    async fn oversized_audio_is_split_into_provider_sized_frames() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();

            for _ in 0..11 {
                let audio = ws.next().await.unwrap().unwrap();
                match audio {
                    Message::Binary(bytes) => assert_eq!(bytes.len(), 3200),
                    other => panic!("expected binary audio frame, got {other:?}"),
                }
            }

            let terminate = ws.next().await.unwrap().unwrap().into_text().unwrap();
            assert_eq!(
                serde_json::from_str::<serde_json::Value>(&terminate).unwrap()["type"],
                "Terminate"
            );
            ws.send(Message::Text(
                serde_json::json!({"type": "Termination"}).to_string(),
            ))
            .await
            .unwrap();
        });

        let mut provider = AssemblyAiProvider::with_url(format!("ws://{address}"));
        provider.connect(&test_config()).await.unwrap();
        provider.send_audio(&vec![1; 35_200]).await.unwrap();

        assert_eq!(provider.disconnect().await.unwrap(), None);
        server.await.unwrap();
    }

    #[tokio::test]
    async fn server_error_during_termination_is_returned() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();
            let _ = ws.next().await.unwrap().unwrap();

            ws.send(Message::Text(
                serde_json::json!({
                    "type": "Error",
                    "error": "Input duration violation: 20 ms. Expected between 50 and 1000 ms"
                })
                .to_string(),
            ))
            .await
            .unwrap();
        });

        let mut provider = AssemblyAiProvider::with_url(format!("ws://{address}"));
        provider.connect(&test_config()).await.unwrap();

        let error = provider.disconnect().await.unwrap_err();
        assert!(error
            .to_string()
            .contains("Input duration violation: 20 ms"));
        server.await.unwrap();
    }

    #[tokio::test]
    async fn close_before_termination_is_incomplete_shutdown() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();
            let _ = ws.next().await.unwrap().unwrap();
            ws.close(None).await.unwrap();
        });

        let mut provider = AssemblyAiProvider::with_url(format!("ws://{address}"));
        provider.connect(&test_config()).await.unwrap();

        let error = provider.disconnect().await.unwrap_err();
        assert!(error.to_string().contains("closed before Termination"));
        server.await.unwrap();
    }

    #[tokio::test]
    async fn timeout_before_termination_is_incomplete_shutdown() {
        let listener = TcpListener::bind("127.0.0.1:0").await.unwrap();
        let address = listener.local_addr().unwrap();
        let server = tokio::spawn(async move {
            let (stream, _) = listener.accept().await.unwrap();
            let mut ws = accept_async(stream).await.unwrap();
            let _ = ws.next().await.unwrap().unwrap();
            tokio::time::sleep(Duration::from_millis(100)).await;
        });

        let mut provider = AssemblyAiProvider::with_url(format!("ws://{address}"));
        provider.connect(&test_config()).await.unwrap();
        let ws = provider.ws.as_mut().unwrap();
        ws.send(Message::Text(
            serde_json::json!({"type": "Terminate"}).to_string(),
        ))
        .await
        .unwrap();

        let error = read_until_termination_with_timeout(ws, Duration::from_millis(20))
            .await
            .unwrap_err();
        assert!(matches!(error, AppError::Timeout(_)));
        server.await.unwrap();
    }
}
