//! Production final-output path shared by the provider and regression tests.
//! Transport, framing and final-channel acceptance remain separate decisions.
use super::{protocol, sse, ChunkCallback};
use protocol::LlmApiKind;
use serde_json::Value;

pub fn non_streaming_final(kind: LlmApiKind, body: &Value) -> Result<String, String> {
    protocol::require_final_text(protocol::response_text(kind, body))
}

pub struct FinalStream {
    kind: LlmApiKind,
    decoder: sse::Decoder,
    text: String,
    done: bool,
}

impl FinalStream {
    pub fn new(kind: LlmApiKind) -> Self {
        Self {
            kind,
            decoder: sse::Decoder::default(),
            text: String::new(),
            done: false,
        }
    }

    pub fn is_done(&self) -> bool {
        self.done
    }

    pub fn push(&mut self, bytes: &[u8], callback: &ChunkCallback) -> Result<(), String> {
        for &byte in bytes {
            if self.done {
                break;
            }
            let Some(data) = self.decoder.push_byte(byte)? else {
                continue;
            };
            // Legal empty data events are not model JSON payloads.
            if data.trim().is_empty() {
                continue;
            }
            if self.kind == LlmApiKind::OpenAiCompatible && data == "[DONE]" {
                self.done = true;
                break;
            }
            // Preserve the upstream tolerant-payload contract. Framing correctness does
            // not require turning previously ignored non-JSON events into failures.
            let Ok(value) = serde_json::from_str::<Value>(&data) else {
                continue;
            };
            let event = protocol::parse_stream_event(self.kind, &value);
            if let Some(error) = event.error {
                return Err(error);
            }
            if let Some(content) = event.text {
                if !content.is_empty() {
                    self.text.push_str(&content);
                    callback(&content);
                }
            }
            if event.done {
                self.done = true;
            }
        }
        Ok(())
    }

    pub fn finish(self) -> Result<String, String> {
        // The caller reaches here only after a clean transport EOF or a model marker.
        // EOF is not a model completion claim. Preserve upstream nonempty-final success;
        // pending, unterminated SSE data is never dispatched as a complete event.
        protocol::require_final_text(self.text)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use serde_json::json;
    use std::sync::{Arc, Mutex};

    fn recorder() -> (ChunkCallback, Arc<Mutex<String>>) {
        let received = Arc::new(Mutex::new(String::new()));
        let sink = received.clone();
        let callback: ChunkCallback = Box::new(move |text| sink.lock().unwrap().push_str(text));
        (callback, received)
    }

    #[test]
    fn provider_output_path_does_not_promote_reasoning_or_accept_blank_final() {
        for final_content in [Value::Null, json!(""), json!("  ")] {
            let (callback, received) = recorder();
            let mut stream = FinalStream::new(LlmApiKind::OpenAiCompatible);
            let event = json!({"choices":[{"delta":{
                "content":final_content,"reasoning_content":"must never reach callback"
            }}]});
            stream
                .push(
                    format!("data: {event}\n\ndata: [DONE]\n\n").as_bytes(),
                    &callback,
                )
                .unwrap();
            assert!(stream.finish().is_err());
            assert!(!received.lock().unwrap().contains("must never"));
            assert!(received.lock().unwrap().trim().is_empty());
            let body = json!({"choices":[{"message":{
                "content":final_content,"reasoning_content":"must never reach final"
            }}]});
            assert!(non_streaming_final(LlmApiKind::OpenAiCompatible, &body).is_err());
        }
    }

    #[test]
    fn mixed_reasoning_and_final_uses_actual_callback_across_every_byte_split() {
        let wire = concat!(
            "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"secret\"}}]}\r\n\r\n",
            "data: {\"choices\":[{\"delta\":{\"content\":\"中文🙂\",\"reasoning_content\":\"secret\"}}]}\r\n\r\n",
            "data: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\r\n\r\n",
            "data: {\"choices\":[],\"usage\":{\"total_tokens\":5}}\r\n\r\n",
            "data: [DONE]\r\n\r\n"
        );
        for split in 0..=wire.len() {
            let (callback, received) = recorder();
            let mut stream = FinalStream::new(LlmApiKind::OpenAiCompatible);
            stream.push(&wire.as_bytes()[..split], &callback).unwrap();
            stream.push(&wire.as_bytes()[split..], &callback).unwrap();
            assert_eq!(stream.finish().unwrap(), "中文🙂");
            assert_eq!(*received.lock().unwrap(), "中文🙂");
        }
        let body =
            json!({"choices":[{"message":{"content":"中文🙂","reasoning_content":"secret"}}]});
        assert_eq!(
            non_streaming_final(LlmApiKind::OpenAiCompatible, &body).unwrap(),
            "中文🙂"
        );
    }

    #[test]
    fn empty_events_and_terminal_tail_do_not_become_json_errors() {
        let mut wire = b"data:\r\rdata\r\rdata: \t\r\rdata: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\r\rdata: [DONE]\r\r".to_vec();
        wire.extend_from_slice(b"data: \xff\n\nnot JSON");
        for split in 0..=wire.len() {
            let (callback, received) = recorder();
            let mut stream = FinalStream::new(LlmApiKind::OpenAiCompatible);
            stream.push(&wire[..split], &callback).unwrap();
            stream.push(&wire[split..], &callback).unwrap();
            assert_eq!(stream.finish().unwrap(), "ok");
            assert_eq!(*received.lock().unwrap(), "ok");
        }
    }

    #[test]
    fn anthropic_message_stop_stops_tail_and_delivers_only_text_delta() {
        let (callback, received) = recorder();
        let mut stream = FinalStream::new(LlmApiKind::AnthropicMessages);
        let wire = concat!(
            "event: ping\ndata: {\"type\":\"ping\"}\n\n",
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"thinking_delta\",\"thinking\":\"secret\"}}\n\n",
            "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"answer\"}}\n\n",
            "data: {\"type\":\"message_delta\",\"delta\":{\"stop_reason\":\"end_turn\"}}\n\n",
            "data: {\"type\":\"message_stop\"}\n\ndata: invalid trailing bytes\n\n"
        );
        stream.push(wire.as_bytes(), &callback).unwrap();
        assert_eq!(stream.finish().unwrap(), "answer");
        assert_eq!(*received.lock().unwrap(), "answer");
        assert_eq!(non_streaming_final(LlmApiKind::AnthropicMessages,
            &json!({"content":[{"type":"thinking","thinking":"secret"},{"type":"text","text":"answer"}]})).unwrap(), "answer");
    }

    #[test]
    fn malformed_json_is_ignored_but_explicit_provider_error_is_not() {
        let (callback, received) = recorder();
        let mut stream = FinalStream::new(LlmApiKind::OpenAiCompatible);
        stream
            .push(
                b"data: {bad json}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"ok\"}}]}\n\n",
                &callback,
            )
            .unwrap();
        assert_eq!(stream.finish().unwrap(), "ok");
        assert_eq!(*received.lock().unwrap(), "ok");
        let mut stream = FinalStream::new(LlmApiKind::OpenAiCompatible);
        assert!(stream
            .push(
                b"data: {\"error\":{\"message\":\"neutral upstream error\"}}\n\n",
                &callback
            )
            .is_err());
    }

    #[test]
    fn clean_eof_accepts_nonempty_final_without_claiming_model_completion() {
        for (kind, wire) in [
            (LlmApiKind::OpenAiCompatible,
             "data: {\"choices\":[{\"delta\":{\"content\":\"partial\"},\"finish_reason\":\"stop\"}]}\n\n"),
            (LlmApiKind::AnthropicMessages,
             "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"partial\"}}\n\n"),
        ] {
            let (callback, received) = recorder();
            let mut stream = FinalStream::new(kind);
            stream.push(wire.as_bytes(), &callback).unwrap();
            assert!(!stream.is_done());
            assert_eq!(stream.finish().unwrap(), "partial");
            assert_eq!(*received.lock().unwrap(), "partial");
        }
    }

    #[test]
    fn incomplete_event_does_not_dispatch_or_become_success() {
        let (callback, received) = recorder();
        let mut stream = FinalStream::new(LlmApiKind::OpenAiCompatible);
        stream
            .push(
                b"data: {\"choices\":[{\"delta\":{\"content\":\"not dispatched\"}}]}\n",
                &callback,
            )
            .unwrap();
        assert!(stream.finish().is_err());
        assert!(received.lock().unwrap().is_empty());
    }
    #[test]
    fn clean_eof_discards_unterminated_tail_after_complete_final() {
        let (callback, received) = recorder();
        let mut stream = FinalStream::new(LlmApiKind::OpenAiCompatible);
        stream.push(b"data: {\"choices\":[{\"delta\":{\"content\":\"complete event\"}}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"not dispatched\"}}]}\n", &callback).unwrap();
        assert_eq!(stream.finish().unwrap(), "complete event");
        assert_eq!(*received.lock().unwrap(), "complete event");
    }
}
