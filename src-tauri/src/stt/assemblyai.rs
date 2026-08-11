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
const TERMINATION_TIMEOUT: Duration = Duration::from_secs(5);

pub struct AssemblyAiProvider {
    ws: Option<WsStream>,
    pending: Vec<u8>,
    sample_rate: u32,
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
        }
    }

    fn build_url(config: &SttConfig) -> String {
        format!(
            "wss://streaming.assemblyai.com/v3/ws?\
             sample_rate={}&\
             format_turns=true",
            config.sample_rate
        )
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
            if take < min_bytes {
                if !force {
                    break;
                }
                // Pad short residual with silence so AssemblyAI accepts the last frame.
                let mut frame = self.pending.drain(..).collect::<Vec<u8>>();
                frame.resize(min_bytes, 0);
                self.send_frame(&frame).await?;
                break;
            }

            let frame: Vec<u8> = self.pending.drain(..take).collect();
            self.send_frame(&frame).await?;
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
                .unwrap_or(end_of_turn);

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

async fn read_until_termination(ws: &mut WsStream) -> Result<Option<String>, AppError> {
    let deadline = tokio::time::Instant::now() + TERMINATION_TIMEOUT;
    let mut final_text = String::new();

    loop {
        let now = tokio::time::Instant::now();
        if now >= deadline {
            tracing::warn!("Timed out waiting for AssemblyAI Termination message");
            break;
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
            Ok(Some(Ok(Message::Close(_)))) | Ok(None) => break,
            Ok(Some(Err(e))) => return Err(AppError::Network(e.to_string())),
            Ok(Some(Ok(_))) => {}
            Err(_) => {
                tracing::warn!("Timed out waiting for AssemblyAI Termination message");
                break;
            }
        }
    }

    Ok((!final_text.is_empty()).then_some(final_text))
}

#[async_trait]
impl SttProvider for AssemblyAiProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        let url = Self::build_url(config);
        self.sample_rate = if config.sample_rate == 0 {
            16000
        } else {
            config.sample_rate
        };
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
        let _ = self.flush_ready(true).await;

        if let Some(mut ws) = self.ws.take() {
            let terminate = serde_json::json!({"type": "Terminate"});
            let _ = ws.send(Message::Text(terminate.to_string().into())).await;
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

    #[test]
    fn chunk_size_matches_assemblyai_pcm16_duration_bounds() {
        let provider = AssemblyAiProvider::new();

        assert_eq!(provider.bytes_for_ms(MIN_CHUNK_MS), 1600);
        assert_eq!(provider.bytes_for_ms(TARGET_CHUNK_MS), 3200);
        assert_eq!(provider.bytes_for_ms(MAX_CHUNK_MS), 32000);
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
}
