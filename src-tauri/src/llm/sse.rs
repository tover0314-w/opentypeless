//! Byte-oriented SSE data framing, with LF, CR and CRLF delimiters.
//! Decode only complete lines; valid codepoints split across HTTP chunks are preserved.

#[derive(Default)]
pub struct Decoder {
    line: Vec<u8>,
    data: String,
    seen_line: bool,
    skip_lf: bool,
}

impl Decoder {
    /// Consume one byte so the caller can stop exactly at a terminal event,
    /// without decoding arbitrary trailing bytes in the same HTTP chunk.
    pub fn push_byte(&mut self, byte: u8) -> Result<Option<String>, String> {
        if self.skip_lf {
            self.skip_lf = false;
            if byte == b'\n' {
                return Ok(None);
            }
        }
        if byte != b'\n' && byte != b'\r' {
            self.line.push(byte);
            return Ok(None);
        }
        self.skip_lf = byte == b'\r';
        // Preserve replacement decoding for genuinely invalid wire bytes, but never
        // replace a valid codepoint merely because HTTP split it across chunks.
        let decoded = String::from_utf8_lossy(&self.line);
        let line = decoded.as_ref();
        let line = if self.seen_line {
            line
        } else {
            line.strip_prefix('\u{feff}').unwrap_or(line)
        };
        self.seen_line = true;
        let mut event = None;
        if line.is_empty() {
            if !self.data.is_empty() {
                self.data.pop();
                event = Some(std::mem::take(&mut self.data));
            }
        } else if line == "data" {
            self.data.push('\n');
        } else if let Some(value) = line.strip_prefix("data:") {
            self.data.push_str(value.strip_prefix(' ').unwrap_or(value));
            self.data.push('\n');
        }
        self.line.clear();
        Ok(event)
    }

    #[cfg(test)]
    fn push(&mut self, bytes: &[u8]) -> Result<Vec<String>, String> {
        let mut events = Vec::new();
        for &byte in bytes {
            if let Some(event) = self.push_byte(byte)? {
                events.push(event);
            }
        }
        Ok(events)
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::llm::protocol::{parse_stream_event, LlmApiKind};

    #[test]
    fn every_split_preserves_cjk_emoji_and_actual_protocol_parse() {
        let wire = "data: {\"choices\":[{\"delta\":{\"content\":\"中文🙂é\"}}]}\r\n\r\ndata:[DONE]\r\n\r\n";
        for split in 0..=wire.len() {
            let mut decoder = Decoder::default();
            let mut events = decoder.push(&wire.as_bytes()[..split]).unwrap();
            events.extend(decoder.push(&wire.as_bytes()[split..]).unwrap());
            assert_eq!(events.len(), 2, "split {split}");
            let json = serde_json::from_str(&events[0]).unwrap();
            let event = parse_stream_event(LlmApiKind::OpenAiCompatible, &json);
            assert_eq!(event.text.as_deref(), Some("中文🙂é"));
            assert_eq!(events[1], "[DONE]");
        }
    }

    #[test]
    fn bytewise_comments_multiline_and_multiple_events() {
        let wire =
            "\u{feff}: keepalive\ndata: {\ndata: \"ok\":true}\n\nevent: message\ndata: second\n\n";
        let mut decoder = Decoder::default();
        let mut events = Vec::new();
        for byte in wire.as_bytes() {
            events.extend(decoder.push(&[*byte]).unwrap());
        }
        assert_eq!(events, ["{\n\"ok\":true}", "second"]);
    }

    #[test]
    fn invalid_wire_bytes_use_replacement_and_incomplete_event_is_not_dispatched() {
        let mut decoder = Decoder::default();
        assert_eq!(decoder.push(b"data: \xff\n\n").unwrap(), ["\u{fffd}"]);
        for bytes in [
            b"data: incomplete".as_slice(),
            b"data: pending\n".as_slice(),
        ] {
            assert!(Decoder::default().push(bytes).unwrap().is_empty());
        }
    }

    #[test]
    fn all_legal_line_delimiters_and_empty_data_fields() {
        for newline in ["\n", "\r", "\r\n"] {
            let wire =
                format!("data:{newline}{newline}data{newline}{newline}data: ok{newline}{newline}");
            for split in 0..=wire.len() {
                let mut decoder = Decoder::default();
                let mut events = decoder.push(&wire.as_bytes()[..split]).unwrap();
                events.extend(decoder.push(&wire.as_bytes()[split..]).unwrap());
                assert_eq!(events, ["", "", "ok"]);
            }
        }
    }

    #[test]
    fn large_event_is_not_rejected_by_a_new_policy_limit() {
        let mut wire = b"data: ".to_vec();
        wire.extend(vec![b'a'; 1024 * 1024 + 1]);
        wire.extend(b"\n\n");
        assert_eq!(
            Decoder::default().push(&wire).unwrap()[0].len(),
            1024 * 1024 + 1
        );
    }
}
