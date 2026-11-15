use std::collections::VecDeque;

use crate::error::AppError;

pub(super) struct SseDecoder {
    bytes: VecDeque<u8>,
    line: Vec<u8>,
    data: String,
    has_data: bool,
    first_line: bool,
    skip_lf: bool,
    limit: usize,
}

impl SseDecoder {
    pub(super) fn new(limit: usize) -> Self {
        Self {
            bytes: VecDeque::new(),
            line: Vec::new(),
            data: String::new(),
            has_data: false,
            first_line: true,
            skip_lf: false,
            limit,
        }
    }

    pub(super) fn push(&mut self, chunk: &[u8]) -> Result<(), AppError> {
        let retained = self.bytes.len() + self.line.len() + self.data.len();
        if chunk.len() > self.limit.saturating_sub(retained) {
            return Err(AppError::Config("Cloud LLM response too large".to_string()));
        }
        self.bytes.extend(chunk);
        Ok(())
    }

    pub(super) fn next_event(&mut self) -> Result<Option<String>, AppError> {
        while let Some(byte) = self.bytes.pop_front() {
            if self.skip_lf {
                self.skip_lf = false;
                if byte == b'\n' {
                    continue;
                }
            }
            if byte != b'\r' && byte != b'\n' {
                self.line.push(byte);
                continue;
            }
            self.skip_lf = byte == b'\r';
            let raw_line = std::mem::take(&mut self.line);
            let mut line = std::str::from_utf8(&raw_line)
                .map_err(|_| AppError::Config("Invalid cloud LLM stream encoding".to_string()))?;
            if self.first_line {
                self.first_line = false;
                line = line.strip_prefix('\u{feff}').unwrap_or(line);
            }
            if line.is_empty() {
                if self.has_data {
                    self.has_data = false;
                    self.data.pop();
                    return Ok(Some(std::mem::take(&mut self.data)));
                }
                continue;
            }
            if line.starts_with(':') {
                continue;
            }
            let (field, value) = line.split_once(':').unwrap_or((line, ""));
            if field == "data" {
                let value = value.strip_prefix(' ').unwrap_or(value);
                let additional = value.len().saturating_add(1);
                if additional > self.limit.saturating_sub(self.data.len()) {
                    return Err(AppError::Config("Cloud LLM response too large".to_string()));
                }
                self.data.push_str(value);
                self.data.push('\n');
                self.has_data = true;
            }
        }
        Ok(None)
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn decodes_multiline_events_with_every_byte_split_and_newline_style() {
        for newline in ["\n", "\r\n", "\r"] {
            let source = format!("\u{feff}: heartbeat{newline}data: 你好{newline}data:😀{newline}{newline}data:[DONE]{newline}{newline}");
            for split in 0..=source.len() {
                let mut decoder = SseDecoder::new(1024);
                let mut events = Vec::new();
                for chunk in [&source.as_bytes()[..split], &source.as_bytes()[split..]] {
                    decoder.push(chunk).unwrap();
                    while let Some(event) = decoder.next_event().unwrap() {
                        events.push(event);
                    }
                }
                assert_eq!(events, ["你好\n😀", "[DONE]"]);
            }
        }
    }

    #[test]
    fn keeps_event_spaces_and_does_not_dispatch_unterminated_eof_data() {
        let mut decoder = SseDecoder::new(1024);
        decoder
            .push(b"data:  spaces  \n\n data:ignored\n\ndata:partial\n")
            .unwrap();
        assert_eq!(decoder.next_event().unwrap(), Some(" spaces  ".to_string()));
        assert_eq!(decoder.next_event().unwrap(), None);
    }

    #[test]
    fn rejects_oversized_pending_data_and_invalid_encoding() {
        let mut decoder = SseDecoder::new(4);
        assert!(decoder.push(b"12345").is_err());
        let mut decoder = SseDecoder::new(1024);
        decoder.push(b"data:\xff\n\n").unwrap();
        assert!(decoder.next_event().is_err());
    }
}
