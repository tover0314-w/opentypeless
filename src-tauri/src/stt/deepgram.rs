use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use std::collections::HashSet;
use tokio_tungstenite::{connect_async, tungstenite::Message};

use crate::error::AppError;

use super::{SttConfig, SttProvider, TranscriptEvent};

type WsStream =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

pub struct DeepgramProvider {
    ws: Option<WsStream>,
    final_segments: FinalSegmentTracker,
}

impl Default for DeepgramProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl DeepgramProvider {
    pub fn new() -> Self {
        Self {
            ws: None,
            final_segments: FinalSegmentTracker::default(),
        }
    }

    fn build_url(config: &SttConfig) -> String {
        let lang = config.language.as_deref().unwrap_or("multi");
        format!(
            "wss://api.deepgram.com/v1/listen?\
             model=nova-3&\
             smart_format={}&\
             language={}&\
             punctuate=true&\
             utterances=true&\
             interim_results=true&\
             endpointing=150&\
             encoding=linear16&\
             sample_rate={}&\
             channels=1",
            config.smart_format, lang, config.sample_rate
        )
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct DeepgramFinalSegment {
    text: String,
    start_millis: i64,
    duration_millis: i64,
}

impl DeepgramFinalSegment {
    fn key(&self) -> String {
        format!(
            "{}:{}:{}",
            self.start_millis, self.duration_millis, self.text
        )
    }
}

#[derive(Default)]
struct FinalSegmentTracker {
    seen: HashSet<String>,
}

impl FinalSegmentTracker {
    fn record(&mut self, segment: &DeepgramFinalSegment) -> bool {
        self.seen.insert(segment.key())
    }
}

struct ParsedDeepgramMessage {
    event: Option<TranscriptEvent>,
    final_segment: Option<DeepgramFinalSegment>,
}

fn seconds_to_millis(value: Option<f64>) -> i64 {
    (value.unwrap_or_default() * 1000.0).round() as i64
}

fn parse_deepgram_message(text: &str) -> Result<ParsedDeepgramMessage, AppError> {
    let v: serde_json::Value =
        serde_json::from_str(text).map_err(|e| AppError::Config(e.to_string()))?;

    if v.get("type").and_then(|t| t.as_str()) == Some("Error") {
        let msg = v["message"].as_str().unwrap_or("Unknown error").to_string();
        return Ok(ParsedDeepgramMessage {
            event: Some(TranscriptEvent::Error { message: msg }),
            final_segment: None,
        });
    }

    let transcript = v["channel"]["alternatives"][0]["transcript"]
        .as_str()
        .unwrap_or("")
        .to_string();

    if transcript.is_empty() {
        return Ok(ParsedDeepgramMessage {
            event: None,
            final_segment: None,
        });
    }

    let is_final = v["is_final"].as_bool().unwrap_or(false);

    if is_final {
        let confidence = v["channel"]["alternatives"][0]["confidence"]
            .as_f64()
            .unwrap_or(0.0) as f32;

        let segment = DeepgramFinalSegment {
            text: transcript.clone(),
            start_millis: seconds_to_millis(v["start"].as_f64()),
            duration_millis: seconds_to_millis(v["duration"].as_f64()),
        };

        return Ok(ParsedDeepgramMessage {
            event: Some(TranscriptEvent::Final {
                text: transcript,
                confidence,
            }),
            final_segment: Some(segment),
        });
    }

    Ok(ParsedDeepgramMessage {
        event: Some(TranscriptEvent::Partial { text: transcript }),
        final_segment: None,
    })
}

#[async_trait]
impl SttProvider for DeepgramProvider {
    async fn connect(&mut self, config: &SttConfig) -> Result<(), AppError> {
        let url = Self::build_url(config);

        let mut attempt = 0u32;
        loop {
            let request = http::Request::builder()
                .uri(&url)
                .header("Authorization", format!("Token {}", config.api_key))
                .header("Host", "api.deepgram.com")
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
                    tracing::info!("Deepgram WebSocket connected");
                    return Ok(());
                }
                Err(e) if attempt < 2 => {
                    tracing::warn!("Deepgram connect failed (attempt {}/3): {}", attempt + 1, e);
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
        if let Some(ws) = &mut self.ws {
            ws.send(Message::Binary(chunk.to_vec()))
                .await
                .map_err(|e| AppError::Network(e.to_string()))?;
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
                let message = parse_deepgram_message(&text)?;
                if message
                    .final_segment
                    .as_ref()
                    .is_some_and(|segment| !self.final_segments.record(segment))
                {
                    return Ok(None);
                }
                Ok(message.event)
            }
            Some(Ok(Message::Close(_))) => {
                tracing::info!("Deepgram WebSocket closed");
                Ok(None)
            }
            Some(Err(e)) => {
                tracing::error!("Deepgram WebSocket error: {}", e);
                Ok(Some(TranscriptEvent::Error {
                    message: e.to_string(),
                }))
            }
            _ => Ok(None),
        }
    }

    async fn disconnect(&mut self) -> Result<Option<String>, AppError> {
        if let Some(ws) = &mut self.ws {
            let close_msg = serde_json::json!({"type": "CloseStream"});
            let _ = ws.send(Message::Text(close_msg.to_string())).await;
            let _ = ws.close(None).await;
        }
        self.ws = None;
        tracing::info!("Deepgram disconnected");
        Ok(None)
    }

    fn name(&self) -> &str {
        "Deepgram Nova-3"
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn final_message(text: &str, start: f64, duration: f64) -> String {
        serde_json::json!({
            "type": "Results",
            "start": start,
            "duration": duration,
            "is_final": true,
            "speech_final": true,
            "channel": {
                "alternatives": [{
                    "transcript": text,
                    "confidence": 0.97
                }]
            }
        })
        .to_string()
    }

    #[test]
    fn parses_speech_final_message_as_final_transcript() {
        let message = serde_json::json!({
            "is_final": true,
            "speech_final": true,
            "channel": {
                "alternatives": [{
                    "transcript": "hello world",
                    "confidence": 0.97
                }]
            }
        })
        .to_string();

        let event = parse_deepgram_message(&message).unwrap().event;

        match event {
            Some(TranscriptEvent::Final { text, confidence }) => {
                assert_eq!(text, "hello world");
                assert!((confidence - 0.97).abs() < f32::EPSILON);
            }
            other => panic!("expected final transcript, got {other:?}"),
        }
    }

    #[test]
    fn parses_empty_transcript_as_none() {
        let message = serde_json::json!({
            "is_final": true,
            "speech_final": true,
            "channel": {
                "alternatives": [{
                    "transcript": "",
                    "confidence": 0.0
                }]
            }
        })
        .to_string();

        assert!(parse_deepgram_message(&message).unwrap().event.is_none());
    }

    #[test]
    fn final_segment_key_is_stable_for_equivalent_json_numbers() {
        let first = parse_deepgram_message(&final_message("tail", 1.25, 0.5)).unwrap();
        let second =
            parse_deepgram_message(&final_message("tail", 1.250_000_1, 0.500_000_1)).unwrap();

        assert_eq!(
            first.final_segment.unwrap().key(),
            second.final_segment.unwrap().key()
        );
    }

    #[test]
    fn tracker_returns_each_final_segment_once() {
        let mut tracker = FinalSegmentTracker::default();
        let segment = DeepgramFinalSegment {
            text: "last words".to_string(),
            start_millis: 1250,
            duration_millis: 500,
        };

        assert!(tracker.record(&segment));
        assert!(!tracker.record(&segment));
    }
}
