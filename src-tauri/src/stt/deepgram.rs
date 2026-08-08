use async_trait::async_trait;
use futures_util::{SinkExt, StreamExt};
use std::collections::HashSet;
use std::time::Duration;
use tokio::io::{AsyncRead, AsyncWrite};
use tokio::time::{timeout, timeout_at, Instant};
use tokio_tungstenite::{connect_async, tungstenite::Message};

use crate::error::AppError;

use super::{SttConfig, SttProvider, TranscriptEvent};

type WsStream =
    tokio_tungstenite::WebSocketStream<tokio_tungstenite::MaybeTlsStream<tokio::net::TcpStream>>;

const FINALIZE_DRAIN_TIMEOUT: Duration = Duration::from_millis(2500);
const FINALIZE_QUIET_PERIOD: Duration = Duration::from_millis(100);
const CLOSE_HANDSHAKE_TIMEOUT: Duration = Duration::from_millis(500);

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
    from_finalize: bool,
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
            from_finalize: false,
        });
    }

    let from_finalize = v["from_finalize"].as_bool().unwrap_or(false);

    let transcript = v["channel"]["alternatives"][0]["transcript"]
        .as_str()
        .unwrap_or("")
        .to_string();

    if transcript.is_empty() {
        return Ok(ParsedDeepgramMessage {
            event: None,
            final_segment: None,
            from_finalize,
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
            from_finalize,
        });
    }

    Ok(ParsedDeepgramMessage {
        event: Some(TranscriptEvent::Partial { text: transcript }),
        final_segment: None,
        from_finalize,
    })
}

fn collect_new_final_text(
    messages: impl IntoIterator<Item = ParsedDeepgramMessage>,
    tracker: &mut FinalSegmentTracker,
) -> String {
    messages
        .into_iter()
        .filter_map(|message| message.final_segment)
        .filter(|segment| tracker.record(segment))
        .map(|segment| segment.text)
        .collect::<Vec<_>>()
        .join(" ")
}

async fn finalize_drain_and_close<S>(
    ws: &mut tokio_tungstenite::WebSocketStream<S>,
    tracker: &mut FinalSegmentTracker,
    drain_timeout: Duration,
    quiet_period: Duration,
    close_timeout: Duration,
) -> Result<Option<String>, AppError>
where
    S: AsyncRead + AsyncWrite + Unpin,
{
    let finalize = serde_json::json!({"type": "Finalize"});
    if let Err(error) = ws.send(Message::Text(finalize.to_string())).await {
        tracing::warn!("Failed to send Deepgram Finalize: {error}");
        let _ = timeout(close_timeout, ws.close(None)).await;
        return Ok(None);
    }

    let hard_deadline = Instant::now() + drain_timeout;
    let mut quiet_deadline: Option<Instant> = None;
    let mut final_parts = Vec::new();
    let mut protocol_error = None;

    loop {
        let deadline = quiet_deadline
            .map(|quiet| quiet.min(hard_deadline))
            .unwrap_or(hard_deadline);

        match timeout_at(deadline, ws.next()).await {
            Err(_) | Ok(None) | Ok(Some(Ok(Message::Close(_)))) => break,
            Ok(Some(Ok(Message::Text(text)))) => match parse_deepgram_message(&text) {
                Ok(message) => {
                    if let Some(TranscriptEvent::Error { message }) = &message.event {
                        protocol_error = Some(AppError::Config(message.clone()));
                        break;
                    }
                    let from_finalize = message.from_finalize;
                    let new_final_text = collect_new_final_text(std::iter::once(message), tracker);
                    if from_finalize {
                        quiet_deadline = Some(Instant::now() + quiet_period);
                    }
                    if !new_final_text.is_empty() {
                        final_parts.push(new_final_text);
                        if quiet_deadline.is_some() {
                            quiet_deadline = Some(Instant::now() + quiet_period);
                        }
                    }
                }
                Err(error) => {
                    tracing::warn!("Ignoring malformed Deepgram finalize message: {error}");
                }
            },
            Ok(Some(Err(error))) => {
                tracing::warn!("Deepgram finalize drain socket ended: {error}");
                break;
            }
            Ok(Some(Ok(_))) => {}
        }
    }

    let close_stream = serde_json::json!({"type": "CloseStream"});
    if let Err(error) = ws.send(Message::Text(close_stream.to_string())).await {
        tracing::warn!("Failed to send Deepgram CloseStream: {error}");
    }
    match timeout(close_timeout, ws.close(None)).await {
        Ok(Err(error)) => tracing::warn!("Deepgram close handshake failed: {error}"),
        Err(_) => tracing::warn!("Deepgram close handshake timed out"),
        Ok(Ok(())) => {}
    }

    if let Some(error) = protocol_error {
        return Err(error);
    }

    let final_text = final_parts.join(" ");
    Ok((!final_text.is_empty()).then_some(final_text))
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
                    self.final_segments = FinalSegmentTracker::default();
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
        let result = match self.ws.take() {
            Some(mut ws) => {
                finalize_drain_and_close(
                    &mut ws,
                    &mut self.final_segments,
                    FINALIZE_DRAIN_TIMEOUT,
                    FINALIZE_QUIET_PERIOD,
                    CLOSE_HANDSHAKE_TIMEOUT,
                )
                .await
            }
            None => Ok(None),
        };
        tracing::info!("Deepgram disconnected");
        result
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

    #[test]
    fn finalize_collection_keeps_only_new_final_segments_in_order() {
        let mut tracker = FinalSegmentTracker::default();
        let first = parse_deepgram_message(&final_message("hello", 0.0, 0.5)).unwrap();
        tracker.record(first.final_segment.as_ref().unwrap());

        let messages = vec![
            parse_deepgram_message(&final_message("hello", 0.0, 0.5)).unwrap(),
            parse_deepgram_message(&final_message("last", 0.5, 0.25)).unwrap(),
            parse_deepgram_message(&final_message("words", 0.75, 0.25)).unwrap(),
        ];

        assert_eq!(collect_new_final_text(messages, &mut tracker), "last words");
    }

    #[test]
    fn finalize_collection_ignores_partials_and_empty_results() {
        let partial = serde_json::json!({
            "type": "Results",
            "is_final": false,
            "channel": {
                "alternatives": [{
                    "transcript": "still listening",
                    "confidence": 0.0
                }]
            }
        })
        .to_string();
        let empty = final_message("", 1.0, 0.25);
        let messages = vec![
            parse_deepgram_message(&partial).unwrap(),
            parse_deepgram_message(&empty).unwrap(),
        ];

        assert_eq!(
            collect_new_final_text(messages, &mut FinalSegmentTracker::default()),
            ""
        );
    }

    #[tokio::test]
    async fn finalize_lifecycle_sends_finalize_collects_only_tail_and_closes() {
        use tokio::io::duplex;
        use tokio_tungstenite::tungstenite::protocol::Role;

        let (client_io, server_io) = duplex(8192);
        let mut client =
            tokio_tungstenite::WebSocketStream::from_raw_socket(client_io, Role::Client, None)
                .await;
        let mut server =
            tokio_tungstenite::WebSocketStream::from_raw_socket(server_io, Role::Server, None)
                .await;

        let server_task = tokio::spawn(async move {
            let finalize = server.next().await.unwrap().unwrap();
            let Message::Text(finalize) = finalize else {
                panic!("expected Finalize text message");
            };
            assert_eq!(
                serde_json::from_str::<serde_json::Value>(&finalize).unwrap()["type"],
                "Finalize"
            );

            server
                .send(Message::Text(final_message("hello", 0.0, 0.5)))
                .await
                .unwrap();
            let tail = serde_json::json!({
                "type": "Results",
                "from_finalize": true,
                "start": 0.5,
                "duration": 0.5,
                "is_final": true,
                "speech_final": true,
                "channel": {
                    "alternatives": [{
                        "transcript": "tail",
                        "confidence": 0.96
                    }]
                }
            })
            .to_string();
            server.send(Message::Text(tail)).await.unwrap();

            let close_stream = server.next().await.unwrap().unwrap();
            let Message::Text(close_stream) = close_stream else {
                panic!("expected CloseStream text message");
            };
            assert_eq!(
                serde_json::from_str::<serde_json::Value>(&close_stream).unwrap()["type"],
                "CloseStream"
            );

            if matches!(server.next().await, Some(Ok(Message::Close(_)))) {
                let _ = server.close(None).await;
            }
        });

        let mut tracker = FinalSegmentTracker::default();
        let existing = parse_deepgram_message(&final_message("hello", 0.0, 0.5)).unwrap();
        tracker.record(existing.final_segment.as_ref().unwrap());

        let result = finalize_drain_and_close(
            &mut client,
            &mut tracker,
            std::time::Duration::from_millis(500),
            std::time::Duration::from_millis(20),
            std::time::Duration::from_millis(100),
        )
        .await
        .unwrap();

        assert_eq!(result.as_deref(), Some("tail"));
        server_task.await.unwrap();
    }

    #[tokio::test]
    async fn finalize_send_failure_does_not_discard_existing_transcript() {
        use tokio::io::duplex;
        use tokio_tungstenite::tungstenite::protocol::Role;

        let (client_io, server_io) = duplex(64);
        let mut client =
            tokio_tungstenite::WebSocketStream::from_raw_socket(client_io, Role::Client, None)
                .await;
        drop(server_io);

        let result = finalize_drain_and_close(
            &mut client,
            &mut FinalSegmentTracker::default(),
            std::time::Duration::from_millis(20),
            std::time::Duration::from_millis(5),
            std::time::Duration::from_millis(5),
        )
        .await;

        assert!(matches!(result, Ok(None)));
    }

    #[tokio::test]
    async fn finalize_lifecycle_surfaces_deepgram_protocol_errors() {
        use tokio::io::duplex;
        use tokio_tungstenite::tungstenite::protocol::Role;

        let (client_io, server_io) = duplex(1024);
        let mut client =
            tokio_tungstenite::WebSocketStream::from_raw_socket(client_io, Role::Client, None)
                .await;
        let mut server =
            tokio_tungstenite::WebSocketStream::from_raw_socket(server_io, Role::Server, None)
                .await;
        let server_task = tokio::spawn(async move {
            let _ = server.next().await;
            server
                .send(Message::Text(
                    serde_json::json!({
                        "type": "Error",
                        "message": "invalid token"
                    })
                    .to_string(),
                ))
                .await
                .unwrap();
        });

        let error = finalize_drain_and_close(
            &mut client,
            &mut FinalSegmentTracker::default(),
            std::time::Duration::from_millis(100),
            std::time::Duration::from_millis(5),
            std::time::Duration::from_millis(5),
        )
        .await
        .unwrap_err();

        assert!(error.to_string().contains("invalid token"));
        server_task.await.unwrap();
    }
}
