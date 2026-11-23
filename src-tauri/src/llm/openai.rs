use async_trait::async_trait;
use futures_util::StreamExt;
use reqwest::Client;

use crate::error::AppError;

use super::{
    prompt, protocol, ChunkCallback, LlmConfig, LlmProvider, PolishRequest, PolishResponse,
};

pub struct OpenAiProvider {
    client: Client,
}

impl Default for OpenAiProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl OpenAiProvider {
    pub fn new() -> Self {
        Self {
            client: crate::build_shared_http_client(),
        }
    }

    pub fn with_client(client: Client) -> Self {
        Self { client }
    }
}

#[async_trait]
impl LlmProvider for OpenAiProvider {
    async fn polish(
        &self,
        config: &LlmConfig,
        req: &PolishRequest,
        on_chunk: Option<&ChunkCallback>,
    ) -> Result<PolishResponse, AppError> {
        let has_selected_text = req
            .selected_text
            .as_ref()
            .is_some_and(|s| !s.trim().is_empty());

        let mut system_prompt = prompt::build_context_system_prompt(prompt::ContextPromptOptions {
            context: &req.context,
            dictionary: &req.dictionary,
            correction_rules: &req.correction_rules,
            polish_style: &req.polish_style,
            personal_style_prompt: "",
            mapped_scene_prompt: &req.mapped_scene_prompt,
            active_scene_prompt: &req.active_scene_prompt,
            polish_custom_prompt: &req.polish_custom_prompt,
            translate_enabled: req.translation_enabled(),
            target_lang: &req.target_lang,
            has_selected_text,
            voice_intent: Some(&req.voice_intent),
        });
        req.apply_language_variants(&mut system_prompt);

        let mut messages = vec![serde_json::json!({ "role": "system", "content": system_prompt })];
        if has_selected_text {
            messages.push(serde_json::json!({
                "role": "user",
                "content": format!("<selected_text>\n{}\n</selected_text>", req.selected_text.as_ref().unwrap())
            }));
        }
        messages.push(serde_json::json!({
            "role": "user",
            "content": format!("<transcription>\n{}\n</transcription>", req.raw_text)
        }));

        let api_kind = protocol::detect_api_kind(&config.provider, &config.base_url);
        let endpoint = protocol::chat_endpoint(&config.provider, &config.base_url)
            .map_err(AppError::Config)?;
        let mut body = protocol::build_chat_body(
            &config.provider,
            &config.base_url,
            &config.model,
            messages,
            config.max_tokens,
            config.temperature,
            on_chunk.is_some(),
        );

        // GLM-4.7/4.5/5 default to thinking mode, but without explicitly enabling it
        // the API may return content in reasoning_content only, leaving content empty.
        // Explicitly enable thinking so both fields are properly populated.
        // Thinking mode also requires temperature >= 0.6 (recommended 1.0).
        if config.model.starts_with("glm-") {
            if let Some(obj) = body.as_object_mut() {
                obj.insert(
                    "thinking".to_string(),
                    serde_json::json!({"type": "enabled"}),
                );
                obj.insert("temperature".to_string(), serde_json::json!(1.0));
                obj.insert("top_p".to_string(), serde_json::json!(0.95));
            }
        }

        // Retry the initial connection (not once streaming starts)
        #[allow(unused_assignments)]
        let mut response = None;
        let mut last_error: Option<AppError> = None;
        let mut attempt = 0u32;

        loop {
            let request = self
                .client
                .post(&endpoint)
                .header("Content-Type", "application/json");
            match protocol::apply_auth_headers(
                request,
                &config.provider,
                &config.base_url,
                &config.api_key,
            )
            .json(&body)
            .timeout(protocol::request_timeout(
                &config.provider,
                &config.base_url,
                &config.model,
            ))
            .send()
            .await
            {
                Ok(resp) => {
                    let status = resp.status();
                    if status.is_success() {
                        response = Some(resp);
                        break;
                    } else if status.as_u16() >= 500 && attempt < 2 {
                        let body_text = read_polish_error_body(resp, &config.api_key).await;
                        tracing::warn!(
                            "LLM server error {} (attempt {}/3), retrying",
                            status,
                            attempt + 1
                        );
                        last_error = Some(AppError::Api {
                            status: status.as_u16(),
                            body: body_text,
                        });
                        attempt += 1;
                        tokio::time::sleep(std::time::Duration::from_millis(
                            1000 * 2u64.pow(attempt - 1),
                        ))
                        .await;
                        continue;
                    } else {
                        let status = resp.status();
                        let text = read_polish_error_body(resp, &config.api_key).await;
                        // Truncate at a valid UTF-8 char boundary to avoid panic on multi-byte chars
                        let truncate_at = text
                            .char_indices()
                            .take_while(|&(i, _)| i < 200)
                            .last()
                            .map(|(i, c)| i + c.len_utf8())
                            .unwrap_or(text.len());
                        let sanitized = &text[..truncate_at];
                        return Err(AppError::Api {
                            status: status.as_u16(),
                            body: sanitized.to_string(),
                        });
                    }
                }
                Err(e) if e.is_timeout() && attempt < 2 => {
                    tracing::warn!(
                        "LLM connection timeout (attempt {}/3), retrying",
                        attempt + 1
                    );
                    last_error = Some(e.into());
                    attempt += 1;
                    tokio::time::sleep(std::time::Duration::from_millis(
                        1000 * 2u64.pow(attempt - 1),
                    ))
                    .await;
                    continue;
                }
                Err(e) if e.is_connect() && attempt < 2 => {
                    tracing::warn!(
                        "LLM connection failed (attempt {}/3), retrying",
                        attempt + 1
                    );
                    last_error = Some(e.into());
                    attempt += 1;
                    tokio::time::sleep(std::time::Duration::from_millis(
                        1000 * 2u64.pow(attempt - 1),
                    ))
                    .await;
                    continue;
                }
                Err(e) => return Err(e.into()),
            }
        }

        let response = response.ok_or_else(|| last_error.unwrap())?;

        if let Some(callback) = on_chunk {
            // Streaming mode
            let mut full_text = String::new();
            let mut reasoning_text = String::new();
            let mut stream = response.bytes_stream();

            let mut decoder = super::sse::SseDecoder::new(8 * 1024 * 1024);
            let mut stream_done = false;
            let mut completion_seen = false;
            while !stream_done {
                let Some(chunk) = stream.next().await else {
                    break;
                };
                let chunk = chunk?;
                decoder.push(&chunk)?;

                // Process SSE lines
                while let Some(data) = decoder.next_event()? {
                    if data.is_empty() {
                        continue;
                    }
                    if data == "[DONE]" {
                        if api_kind == protocol::LlmApiKind::AnthropicMessages {
                            return Err(AppError::Config(
                                "Anthropic LLM stream is incomplete".to_string(),
                            ));
                        }
                        stream_done = true;
                        break;
                    }
                    let parsed = serde_json::from_str::<serde_json::Value>(&data)
                        .map_err(|_| AppError::Config("Invalid LLM stream event".to_string()))?;
                    {
                        let v = parsed;
                        if api_kind == protocol::LlmApiKind::OpenAiCompatible
                            && v["choices"][0]["finish_reason"].as_str() == Some("stop")
                        {
                            completion_seen = true;
                        }
                        let event = protocol::parse_stream_event(api_kind, &v);
                        if let Some(error) = event.error {
                            return Err(AppError::Config(redact_polish_error(
                                error,
                                &config.api_key,
                            )));
                        }
                        if let Some(content) = event.text {
                            if !content.is_empty() {
                                check_polish_stream_size(full_text.len(), content.len())?;
                                full_text.push_str(&content);
                                callback(&content);
                            }
                        }

                        // Collect reasoning_content as fallback for thinking-mode models
                        // where all output may land in this field instead of content
                        if let Some(rc) = event.reasoning {
                            if !rc.is_empty() {
                                check_polish_stream_size(reasoning_text.len(), rc.len())?;
                                reasoning_text.push_str(&rc);
                            }
                        }
                        stream_done = event.done || completion_seen;
                        if stream_done {
                            break;
                        }
                    }
                }
            }

            if api_kind == protocol::LlmApiKind::AnthropicMessages && !stream_done {
                return Err(AppError::Config(
                    "Anthropic LLM stream is incomplete".to_string(),
                ));
            }
            if api_kind == protocol::LlmApiKind::OpenAiCompatible
                && !stream_done
                && !completion_seen
            {
                return Err(AppError::Config("LLM stream is incomplete".to_string()));
            }

            // If content was empty but reasoning_content had text, use it as output.
            // This handles GLM thinking-mode where the API puts all output in reasoning_content.
            if !req.translation_enabled() && full_text.is_empty() && !reasoning_text.is_empty() {
                ensure_polish_answer(&reasoning_text)?;
                tracing::warn!(
                    "LLM content empty, using reasoning_content ({} chars) as output",
                    reasoning_text.len()
                );
                callback(&reasoning_text);
                full_text = reasoning_text;
            }
            ensure_polish_answer(&full_text)?;

            Ok(PolishResponse {
                polished_text: full_text,
            })
        } else {
            // Non-streaming mode
            let v = read_polish_response(response).await?;
            if let Some(error) = protocol::response_completion_error(api_kind, &v) {
                return Err(AppError::Config(error.to_string()));
            }
            let text = if req.translation_enabled()
                && api_kind == protocol::LlmApiKind::OpenAiCompatible
            {
                v["choices"][0]["message"]["content"]
                    .as_str()
                    .unwrap_or("")
                    .to_string()
            } else {
                protocol::response_text(api_kind, &v)
            };

            ensure_polish_answer(&text)?;

            Ok(PolishResponse {
                polished_text: text,
            })
        }
    }

    fn name(&self) -> &str {
        "OpenAI"
    }
}

#[cfg(test)]
fn take_sse_line(buffer: &mut Vec<u8>) -> Option<String> {
    let line_end = buffer.iter().position(|byte| *byte == b'\n')?;
    let line = String::from_utf8_lossy(&buffer[..line_end])
        .trim()
        .to_string();
    buffer.drain(..=line_end);
    Some(line)
}

fn ensure_polish_answer(text: &str) -> Result<(), AppError> {
    if text.trim().is_empty() {
        return Err(AppError::Config("LLM returned an empty answer".to_string()));
    }
    Ok(())
}

fn check_polish_stream_size(current: usize, additional: usize) -> Result<(), AppError> {
    const LIMIT: usize = 8 * 1024 * 1024;
    if current > LIMIT || additional > LIMIT.saturating_sub(current) {
        return Err(AppError::Config("LLM response too large".to_string()));
    }
    Ok(())
}

fn redact_polish_error(body: String, api_key: &str) -> String {
    if api_key.is_empty() {
        body
    } else {
        body.replace(api_key, "[redacted]")
    }
}

async fn read_polish_error_body(response: reqwest::Response, api_key: &str) -> String {
    let bytes = crate::cloud_request::read_response_with_limit(response, 65536)
        .await
        .unwrap_or_default();
    redact_polish_error(String::from_utf8_lossy(&bytes).into_owned(), api_key)
}

async fn read_polish_response(response: reqwest::Response) -> Result<serde_json::Value, AppError> {
    let bytes = crate::cloud_request::read_response_with_limit(response, 8 * 1024 * 1024).await?;
    let body: serde_json::Value = serde_json::from_slice(&bytes)
        .map_err(|_| AppError::Network("Invalid LLM response".to_string()))?;
    if body.get("error").is_some_and(|error| !error.is_null())
        || body["type"].as_str() == Some("error")
    {
        return Err(AppError::Config(
            "LLM returned an error response".to_string(),
        ));
    }
    Ok(body)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn streaming_output_checks_limits_before_appending_text_and_reasoning() {
        let source = include_str!("openai.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        assert!(
            implementation.contains("check_polish_stream_size(full_text.len(), content.len())?;")
        );
        assert!(
            implementation.contains("check_polish_stream_size(reasoning_text.len(), rc.len())?;")
        );
    }

    #[test]
    fn polish_stream_size_accepts_boundary_and_rejects_overflow() {
        let limit = 8 * 1024 * 1024;
        assert!(check_polish_stream_size(limit - 1, 1).is_ok());
        assert!(check_polish_stream_size(limit, 0).is_ok());
        assert!(check_polish_stream_size(limit, 1).is_err());
        assert!(check_polish_stream_size(0, limit + 1).is_err());
        assert!(check_polish_stream_size(usize::MAX, 1).is_err());
    }

    #[test]
    fn polish_error_redacts_repeated_keys_before_the_display_boundary() {
        let secret = "synthetic-private-key";
        let body = format!("{} {secret} repeated {secret}", "x".repeat(190));
        let redacted = redact_polish_error(body, secret);
        assert!(!redacted.contains(secret));
        assert_eq!(redacted.matches("[redacted]").count(), 2);
        assert!(!redacted
            .chars()
            .take(200)
            .collect::<String>()
            .contains("synthetic"));
        assert_eq!(redact_polish_error("模型错误😀".into(), ""), "模型错误😀");
    }

    #[tokio::test]
    async fn polish_error_body_rejects_oversized_content_before_reading() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut request = [0_u8; 4096];
            assert!(socket.read(&mut request).unwrap() > 0);
            let _ = socket.write_all(
                b"HTTP/1.1 503 Error\r\nContent-Length: 65537\r\nConnection: close\r\n\r\n",
            );
        });
        let response = Client::builder()
            .no_proxy()
            .timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap()
            .get(format!("http://{address}"))
            .send()
            .await
            .unwrap();
        let status = response.status().as_u16();
        let body = read_polish_error_body(response, "").await;
        server.join().unwrap();
        let error = AppError::Api { status, body };
        assert!(error.is_retryable());
        assert_eq!(error.to_user_error().details.as_deref(), Some("HTTP 503"));
        assert!(matches!(error, AppError::Api { body, .. } if body.is_empty()));
    }

    #[tokio::test]
    async fn polish_error_body_bounds_unannounced_content_and_preserves_valid_text() {
        use std::io::{Read, Write};
        for (body, expected) in [
            ("x".repeat(65537), String::new()),
            ("x".repeat(65536), "x".repeat(65536)),
            (
                "模型暂时不可用😀".to_string(),
                "模型暂时不可用😀".to_string(),
            ),
        ] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0_u8; 4096];
                assert!(socket.read(&mut request).unwrap() > 0);
                let _ = socket.write_all(b"HTTP/1.1 503 Error\r\nConnection: close\r\n\r\n");
                let _ = socket.write_all(body.as_bytes());
            });
            let response = Client::builder()
                .no_proxy()
                .timeout(std::time::Duration::from_secs(5))
                .build()
                .unwrap()
                .get(format!("http://{address}"))
                .send()
                .await
                .unwrap();
            assert!(response.content_length().is_none());
            let actual = read_polish_error_body(response, "").await;
            server.join().unwrap();
            assert_eq!(actual, expected);
        }
    }

    #[test]
    fn sse_line_decoder_preserves_multibyte_text_split_between_network_chunks() {
        let event = "data: {\"choices\":[{\"delta\":{\"content\":\"你好\"}}]}\r\n";
        let bytes = event.as_bytes();
        let split = event.find('你').unwrap() + 1;
        let mut buffer = bytes[..split].to_vec();
        assert!(take_sse_line(&mut buffer).is_none());
        buffer.extend_from_slice(&bytes[split..]);
        assert_eq!(take_sse_line(&mut buffer).as_deref(), Some(event.trim()));
        assert!(buffer.is_empty());
        assert!(take_sse_line(&mut buffer).is_none());
    }

    #[test]
    fn sse_line_decoder_preserves_every_split_of_unicode_and_consecutive_lines() {
        let source = "data: {\"text\":\"你好😀\"}\r\n\r\ndata:[DONE]\n\n";
        let expected = vec![
            "data: {\"text\":\"你好😀\"}".to_string(),
            String::new(),
            "data:[DONE]".to_string(),
            String::new(),
        ];
        for split in 0..=source.len() {
            let mut buffer = Vec::new();
            let mut lines = Vec::new();
            for chunk in [&source.as_bytes()[..split], &source.as_bytes()[split..]] {
                buffer.extend_from_slice(chunk);
                while let Some(line) = take_sse_line(&mut buffer) {
                    lines.push(line);
                }
            }
            assert_eq!(lines, expected, "byte split {split}");
            assert!(buffer.is_empty(), "byte split {split}");
        }
        let mut buffer = Vec::new();
        let mut lines = Vec::new();
        for byte in source.bytes() {
            buffer.push(byte);
            while let Some(line) = take_sse_line(&mut buffer) {
                lines.push(line);
            }
        }
        assert_eq!(lines, expected);
        assert!(buffer.is_empty());
    }

    #[tokio::test]
    async fn polish_response_rejects_oversized_json_before_decoding() {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut request = [0_u8; 4096];
            assert!(socket.read(&mut request).unwrap() > 0);
            let _ = socket.write_all(
                b"HTTP/1.1 200 OK\r\nContent-Length: 8388609\r\nConnection: close\r\n\r\n{}",
            );
        });
        let response = Client::builder()
            .no_proxy()
            .timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap()
            .get(format!("http://{address}"))
            .send()
            .await
            .unwrap();
        let error = read_polish_response(response).await.unwrap_err();
        server.join().unwrap();
        assert!(matches!(error, AppError::Config(_)));
        assert!(error.to_string().contains("Service response too large"));
    }

    #[tokio::test]
    async fn local_compatible_polish_sends_request_and_parses_response() {
        assert_compatible_polish_request("ollama", "", ResponseScenario::Json).await;
    }

    #[tokio::test]
    async fn byok_compatible_polish_sends_authentication_and_parses_response() {
        assert_compatible_polish_request("openai", "synthetic-byok-key", ResponseScenario::Json)
            .await;
    }

    #[tokio::test]
    async fn streaming_polish_preserves_split_unicode_in_callbacks_and_final_result() {
        assert_compatible_polish_request("openai", "synthetic-byok-key", ResponseScenario::Stream)
            .await;
    }

    #[tokio::test]
    async fn streaming_polish_accepts_data_fields_without_optional_space() {
        assert_compatible_polish_request(
            "openai",
            "synthetic-byok-key",
            ResponseScenario::CompactStream,
        )
        .await;
    }

    #[tokio::test]
    async fn byok_polish_error_does_not_expose_the_key_returned_by_the_server() {
        assert_compatible_polish_request(
            "openai",
            "synthetic-byok-key",
            ResponseScenario::Unauthorized,
        )
        .await;
    }

    #[tokio::test]
    async fn streaming_polish_rejects_malformed_events_without_echoing_payload() {
        assert_compatible_polish_request(
            "openai",
            "synthetic-byok-key",
            ResponseScenario::MalformedStream,
        )
        .await;
    }

    #[tokio::test]
    async fn streaming_polish_returns_redacted_failure_instead_of_error_event_content() {
        assert_compatible_polish_request(
            "openai",
            "synthetic-byok-key",
            ResponseScenario::StreamError,
        )
        .await;
    }

    #[tokio::test]
    async fn streaming_polish_reports_failure_after_partial_content_without_delivering_error_text()
    {
        assert_compatible_polish_request(
            "openai",
            "synthetic-byok-key",
            ResponseScenario::PartialStreamError,
        )
        .await;
    }

    #[tokio::test]
    async fn native_anthropic_polish_uses_headers_system_field_and_response_parser() {
        assert_compatible_polish_request(
            "claude",
            "synthetic-byok-key",
            ResponseScenario::AnthropicJson,
        )
        .await;
    }

    #[tokio::test]
    async fn native_anthropic_streaming_polish_preserves_unicode_and_message_stop() {
        assert_compatible_polish_request(
            "claude",
            "synthetic-byok-key",
            ResponseScenario::AnthropicStream,
        )
        .await;
    }

    #[tokio::test]
    async fn native_anthropic_stop_ignores_later_content_in_the_same_chunk() {
        assert_compatible_polish_request(
            "claude",
            "synthetic-byok-key",
            ResponseScenario::AnthropicTrailingContent,
        )
        .await;
    }

    #[tokio::test]
    async fn compatible_stream_without_completion_signal_returns_incomplete_error() {
        for provider in [
            "zhipu",
            "deepseek",
            "siliconflow",
            "openai",
            "gemini",
            "moonshot",
            "doubao",
            "qwen",
            "groq",
            "ollama",
            "openrouter",
        ] {
            let key = if provider == "ollama" {
                ""
            } else {
                "synthetic-byok-key"
            };
            assert_compatible_polish_request(
                provider,
                key,
                ResponseScenario::StreamWithoutCompletion,
            )
            .await;
        }
    }

    #[tokio::test]
    async fn compatible_stream_with_stop_reason_without_done_preserves_success() {
        for (provider, key) in [("openai", "synthetic-byok-key"), ("ollama", "")] {
            assert_compatible_polish_request(provider, key, ResponseScenario::StreamWithStopReason)
                .await;
        }
    }

    #[tokio::test]
    async fn compatible_stop_reason_ignores_later_content() {
        assert_compatible_polish_request(
            "openai",
            "synthetic-byok-key",
            ResponseScenario::StreamTrailingAfterStop,
        )
        .await;
    }

    #[tokio::test]
    async fn compatible_stop_reason_preserves_content_in_the_final_event() {
        assert_compatible_polish_request("ollama", "", ResponseScenario::StreamContentWithStop)
            .await;
    }

    #[tokio::test]
    async fn anthropic_stream_without_message_stop_returns_incomplete_error() {
        assert_compatible_polish_request(
            "claude",
            "synthetic-byok-key",
            ResponseScenario::AnthropicWithoutCompletion,
        )
        .await;
    }

    #[tokio::test]
    async fn anthropic_stream_with_compatible_done_returns_incomplete_error() {
        assert_compatible_polish_request(
            "claude",
            "synthetic-byok-key",
            ResponseScenario::AnthropicWrongDone,
        )
        .await;
    }

    #[tokio::test]
    async fn anthropic_stream_token_limit_does_not_return_partial_success() {
        assert_compatible_polish_request(
            "claude",
            "synthetic-byok-key",
            ResponseScenario::AnthropicTokenLimitStream,
        )
        .await;
    }

    #[tokio::test]
    async fn anthropic_json_token_limit_does_not_return_partial_success() {
        assert_compatible_polish_request(
            "claude",
            "synthetic-byok-key",
            ResponseScenario::AnthropicTokenLimitJson,
        )
        .await;
    }

    #[derive(Clone, Copy)]
    enum ResponseScenario {
        MalformedStream,
        Json,
        AnthropicJson,
        AnthropicStream,
        AnthropicTrailingContent,
        Stream,
        CompactStream,
        Unauthorized,
        StreamError,
        PartialStreamError,
        StreamWithoutCompletion,
        StreamWithStopReason,
        StreamTrailingAfterStop,
        StreamContentWithStop,
        AnthropicWithoutCompletion,
        AnthropicWrongDone,
        AnthropicTokenLimitStream,
        AnthropicTokenLimitJson,
        CompatibleTerminationStream(&'static str),
        CompatibleTerminationJson(&'static str),
        AnthropicTerminationStream(&'static str),
        AnthropicTerminationJson(&'static str),
        EmptyStream,
        EmptyJson,
        ReasoningStream(&'static str),
        ReasoningWithoutCompletion,
        ReasoningJson(&'static str),
        JsonError,
        AnthropicJsonError,
    }

    #[tokio::test]
    async fn nonstream_polish_rejects_error_envelopes_before_answer_text() {
        for (provider, scenario) in [
            ("openai", ResponseScenario::JsonError),
            ("claude", ResponseScenario::AnthropicJsonError),
        ] {
            assert_compatible_polish_request(provider, "synthetic-byok-key", scenario).await;
        }
    }

    #[tokio::test]
    async fn compatible_polish_rejects_empty_answers_in_stream_and_json() {
        for scenario in [ResponseScenario::EmptyStream, ResponseScenario::EmptyJson] {
            assert_compatible_polish_request("ollama", "", scenario).await;
        }
    }

    #[tokio::test]
    async fn compatible_polish_preserves_reasoning_fallback_and_rejects_blank_reasoning() {
        for reasoning in ["  你好\n ", " \n\t"] {
            for scenario in [
                ResponseScenario::ReasoningStream(reasoning),
                ResponseScenario::ReasoningJson(reasoning),
            ] {
                assert_compatible_polish_request("ollama", "", scenario).await;
            }
        }
    }

    #[tokio::test]
    async fn incomplete_reasoning_stream_does_not_emit_reasoning_as_an_answer() {
        assert_compatible_polish_request(
            "ollama",
            "",
            ResponseScenario::ReasoningWithoutCompletion,
        )
        .await;
    }

    #[test]
    fn polish_answer_check_preserves_nonempty_whitespace_and_rejects_blank() {
        for text in ["", " ", "\n\t"] {
            assert!(ensure_polish_answer(text).is_err());
        }
        let text = "  你好\n ";
        assert!(ensure_polish_answer(text).is_ok());
        assert_eq!(text, "  你好\n ");
    }

    #[tokio::test]
    async fn anthropic_polish_rejects_unhandled_continuation_and_refusal() {
        for reason in ["tool_use", "pause_turn", "refusal"] {
            for scenario in [
                ResponseScenario::AnthropicTerminationStream(reason),
                ResponseScenario::AnthropicTerminationJson(reason),
            ] {
                assert_compatible_polish_request("claude", "synthetic-byok-key", scenario).await;
            }
        }
    }

    #[tokio::test]
    async fn compatible_stream_rejects_explicit_incomplete_termination() {
        for reason in ["length", "content_filter"] {
            assert_compatible_polish_request(
                "openai",
                "synthetic-byok-key",
                ResponseScenario::CompatibleTerminationStream(reason),
            )
            .await;
        }
    }

    #[tokio::test]
    async fn compatible_json_rejects_explicit_incomplete_termination() {
        for reason in ["length", "content_filter"] {
            assert_compatible_polish_request(
                "ollama",
                "",
                ResponseScenario::CompatibleTerminationJson(reason),
            )
            .await;
        }
    }

    #[tokio::test]
    async fn compatible_polish_rejects_unhandled_tool_completion() {
        for reason in ["tool_calls", "function_call"] {
            for scenario in [
                ResponseScenario::CompatibleTerminationStream(reason),
                ResponseScenario::CompatibleTerminationJson(reason),
            ] {
                assert_compatible_polish_request("openai", "synthetic-byok-key", scenario).await;
            }
        }
    }

    #[tokio::test]
    async fn polish_rejects_unknown_completion_reasons_over_http() {
        for scenario in [
            ResponseScenario::CompatibleTerminationStream("unknown-synthetic-reason"),
            ResponseScenario::CompatibleTerminationJson("unknown-synthetic-reason"),
            ResponseScenario::AnthropicTerminationStream("unknown-synthetic-reason"),
            ResponseScenario::AnthropicTerminationJson("unknown-synthetic-reason"),
        ] {
            let provider = if matches!(
                scenario,
                ResponseScenario::AnthropicTerminationStream(_)
                    | ResponseScenario::AnthropicTerminationJson(_)
            ) {
                "claude"
            } else {
                "openai"
            };
            assert_compatible_polish_request(provider, "synthetic-byok-key", scenario).await;
        }
    }

    async fn assert_compatible_polish_request(
        provider_name: &str,
        api_key: &str,
        scenario: ResponseScenario,
    ) {
        assert_compatible_polish_request_with_translation(provider_name, api_key, scenario, false)
            .await;
    }

    #[tokio::test]
    async fn explicit_translation_rejects_reasoning_without_final_content() {
        for scenario in [
            ResponseScenario::ReasoningStream("Synthetic analysis, not a translation."),
            ResponseScenario::ReasoningJson("Synthetic analysis, not a translation."),
        ] {
            assert_compatible_polish_request_with_translation(
                "openrouter",
                "synthetic-byok-key",
                scenario,
                true,
            )
            .await;
        }
    }

    async fn assert_compatible_polish_request_with_translation(
        provider_name: &str,
        api_key: &str,
        scenario: ResponseScenario,
        explicit_translation: bool,
    ) {
        use std::io::{Read, Write};
        let anthropic = matches!(
            scenario,
            ResponseScenario::AnthropicJson
                | ResponseScenario::AnthropicStream
                | ResponseScenario::AnthropicTrailingContent
                | ResponseScenario::AnthropicWithoutCompletion
                | ResponseScenario::AnthropicWrongDone
                | ResponseScenario::AnthropicTokenLimitStream
                | ResponseScenario::AnthropicTokenLimitJson
                | ResponseScenario::AnthropicTerminationStream(_)
                | ResponseScenario::AnthropicTerminationJson(_)
                | ResponseScenario::AnthropicJsonError
        );
        let streaming = matches!(
            scenario,
            ResponseScenario::MalformedStream
                | ResponseScenario::Stream
                | ResponseScenario::AnthropicStream
                | ResponseScenario::AnthropicTrailingContent
                | ResponseScenario::CompactStream
                | ResponseScenario::StreamError
                | ResponseScenario::PartialStreamError
                | ResponseScenario::StreamWithoutCompletion
                | ResponseScenario::StreamWithStopReason
                | ResponseScenario::StreamTrailingAfterStop
                | ResponseScenario::StreamContentWithStop
                | ResponseScenario::AnthropicWithoutCompletion
                | ResponseScenario::AnthropicWrongDone
                | ResponseScenario::AnthropicTokenLimitStream
                | ResponseScenario::CompatibleTerminationStream(_)
                | ResponseScenario::AnthropicTerminationStream(_)
                | ResponseScenario::EmptyStream
                | ResponseScenario::ReasoningStream(_)
                | ResponseScenario::ReasoningWithoutCompletion
        );
        let authentication_failure = matches!(
            scenario,
            ResponseScenario::Unauthorized
                | ResponseScenario::StreamError
                | ResponseScenario::PartialStreamError
        );
        let partial_error = matches!(scenario, ResponseScenario::PartialStreamError);
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
            let mut socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(
                            std::time::Instant::now() < deadline,
                            "timed out waiting for the test request"
                        );
                        std::thread::sleep(std::time::Duration::from_millis(10));
                    }
                    Err(error) => panic!("failed to accept the test request: {error}"),
                }
            };
            socket.set_nonblocking(false).unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            socket
                .set_write_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut bytes = Vec::new();
            let mut chunk = [0_u8; 4096];
            let request = loop {
                let count = socket.read(&mut chunk).unwrap();
                assert!(count > 0);
                bytes.extend_from_slice(&chunk[..count]);
                let Some(boundary) = bytes.windows(4).position(|part| part == b"\r\n\r\n") else {
                    continue;
                };
                let headers = String::from_utf8_lossy(&bytes[..boundary]);
                let length = headers
                    .lines()
                    .find_map(|line| {
                        let (name, value) = line.split_once(':')?;
                        name.eq_ignore_ascii_case("content-length")
                            .then(|| value.trim().parse::<usize>().unwrap())
                    })
                    .unwrap();
                if bytes.len() >= boundary + 4 + length {
                    let body = serde_json::from_slice::<serde_json::Value>(
                        &bytes[boundary + 4..boundary + 4 + length],
                    )
                    .unwrap();
                    break (headers.into_owned(), body);
                }
            };
            if authentication_failure && !streaming {
                let body = r#"{"error":"Invalid synthetic-byok-key; repeated synthetic-byok-key"}"#;
                write!(
                    socket,
                    "HTTP/1.1 401 Unauthorized\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                )
                .unwrap();
            } else if streaming {
                socket.set_nodelay(true).unwrap();
                if matches!(scenario, ResponseScenario::MalformedStream) {
                    let body = "data:{invalid synthetic-byok-key}\n\ndata:[DONE]\n\n";
                    write!(
                        socket,
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    )
                    .unwrap();
                    return request;
                }
                if authentication_failure {
                    let partial = if partial_error {
                        "data: {\"choices\":[{\"delta\":{\"content\":\"Partial text\"}}]}\n\n"
                    } else {
                        ""
                    };
                    let body = format!("{partial}data: {{\"error\":{{\"message\":\"Invalid synthetic-byok-key\"}},\"choices\":[{{\"delta\":{{\"content\":\"Must not deliver\"}}}}]}}\n\n");
                    write!(
                        socket,
                        "HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                        body.len()
                    )
                    .unwrap();
                    return request;
                }
                socket
                    .write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                    .unwrap();
                let prefix = if matches!(scenario, ResponseScenario::CompactStream) {
                    "data:"
                } else {
                    "data: "
                };
                let event = if let ResponseScenario::ReasoningStream(reasoning) = scenario {
                    let payload = serde_json::json!({
                        "choices": [{"delta": {"reasoning_content": reasoning}}]
                    });
                    format!("{prefix}{payload}\n\n")
                } else if matches!(scenario, ResponseScenario::ReasoningWithoutCompletion) {
                    format!("{prefix}{{\"choices\":[{{\"delta\":{{\"reasoning_content\":\"Partial reasoning\"}}}}]}}\n\n")
                } else if matches!(scenario, ResponseScenario::EmptyStream) {
                    format!("{prefix}{{\"choices\":[{{\"delta\":{{\"content\":\"\"}}}}]}}\n\n")
                } else if matches!(scenario, ResponseScenario::StreamContentWithStop) {
                    format!("{prefix}{{\"choices\":[{{\"delta\":{{\"content\":\"你好\"}},\"finish_reason\":\"stop\"}}]}}\n\n")
                } else if anthropic {
                    format!("{prefix}{{\"type\":\"content_block_delta\",\"delta\":{{\"type\":\"text_delta\",\"text\":\"你好\"}}}}\n\n")
                } else {
                    format!("\u{feff}{prefix}{{\"choices\":\r\ndata:[{{\"delta\":{{\"content\":\"你好\"}}}}]}}\r\n\r\n")
                };
                let done = if matches!(
                    scenario,
                    ResponseScenario::StreamWithoutCompletion
                        | ResponseScenario::AnthropicWithoutCompletion
                        | ResponseScenario::StreamContentWithStop
                        | ResponseScenario::ReasoningWithoutCompletion
                ) {
                    String::new()
                } else if matches!(scenario, ResponseScenario::StreamWithStopReason) {
                    format!("{prefix}{{\"choices\":[{{\"delta\":{{}},\"finish_reason\":\"stop\"}}]}}\n\n")
                } else if matches!(scenario, ResponseScenario::StreamTrailingAfterStop) {
                    format!("{prefix}{{\"choices\":[{{\"delta\":{{}},\"finish_reason\":\"stop\"}}]}}\n\n{prefix}{{\"choices\":[{{\"delta\":{{\"content\":\"Must not deliver\"}}}}]}}\n\n{prefix}[DONE]\n\n")
                } else if matches!(scenario, ResponseScenario::AnthropicWrongDone) {
                    format!("{prefix}[DONE]\n\n")
                } else if matches!(scenario, ResponseScenario::AnthropicTokenLimitStream) {
                    format!("{prefix}{{\"type\":\"message_delta\",\"delta\":{{\"stop_reason\":\"max_tokens\"}}}}\n\n{prefix}{{\"type\":\"message_stop\"}}\n\n")
                } else if let ResponseScenario::AnthropicTerminationStream(reason) = scenario {
                    format!("{prefix}{{\"type\":\"message_delta\",\"delta\":{{\"stop_reason\":\"{reason}\"}}}}\n\n{prefix}{{\"type\":\"message_stop\"}}\n\n")
                } else if let ResponseScenario::CompatibleTerminationStream(reason) = scenario {
                    format!("{prefix}{{\"choices\":[{{\"delta\":{{\"content\":\"Must not deliver\"}},\"finish_reason\":\"{reason}\"}}]}}\n\n{prefix}[DONE]\n\n")
                } else if anthropic {
                    let trailing = if matches!(scenario, ResponseScenario::AnthropicTrailingContent)
                    {
                        "data: {\"type\":\"content_block_delta\",\"delta\":{\"type\":\"text_delta\",\"text\":\"Must not deliver\"}}\n\n"
                    } else {
                        ""
                    };
                    format!("{prefix}{{\"type\":\"message_stop\"}}\n\n{trailing}")
                } else {
                    format!("{prefix}[DONE]\n\n")
                };
                let split = event
                    .find('你')
                    .map_or(event.len() / 2, |position| position + 1);
                for chunk in [
                    &event.as_bytes()[..split],
                    &event.as_bytes()[split..],
                    done.as_bytes(),
                ] {
                    if chunk.is_empty() {
                        continue;
                    }
                    write!(socket, "{:x}\r\n", chunk.len()).unwrap();
                    socket.write_all(chunk).unwrap();
                    socket.write_all(b"\r\n").unwrap();
                    socket.flush().unwrap();
                    std::thread::sleep(std::time::Duration::from_millis(30));
                }
                if let Err(error) = socket.write_all(b"0\r\n\r\n") {
                    assert!(matches!(
                        error.kind(),
                        std::io::ErrorKind::BrokenPipe | std::io::ErrorKind::ConnectionReset
                    ));
                }
            } else {
                let compatible_body;
                let body = if matches!(scenario, ResponseScenario::JsonError) {
                    r#"{"error":{"message":"synthetic-byok-key"},"choices":[{"message":{"content":"Must not deliver"}}]}"#
                } else if matches!(scenario, ResponseScenario::AnthropicJsonError) {
                    r#"{"type":"error","error":{"message":"synthetic-byok-key"},"content":[{"type":"text","text":"Must not deliver"}]}"#
                } else if let ResponseScenario::ReasoningJson(reasoning) = scenario {
                    compatible_body = serde_json::json!({
                        "choices": [{"message": {"content": "", "reasoning_content": reasoning}}]
                    })
                    .to_string();
                    compatible_body.as_str()
                } else if matches!(scenario, ResponseScenario::EmptyJson) {
                    r#"{"choices":[{"message":{"content":" \n\t"}}]}"#
                } else if let ResponseScenario::AnthropicTerminationJson(reason) = scenario {
                    compatible_body = format!("{{\"content\":[{{\"type\":\"text\",\"text\":\"Must not deliver\"}}],\"stop_reason\":\"{reason}\"}}");
                    compatible_body.as_str()
                } else if let ResponseScenario::CompatibleTerminationJson(reason) = scenario {
                    compatible_body = format!("{{\"choices\":[{{\"message\":{{\"content\":\"Must not deliver\"}},\"finish_reason\":\"{reason}\"}}]}}");
                    compatible_body.as_str()
                } else if matches!(scenario, ResponseScenario::AnthropicTokenLimitJson) {
                    r#"{"content":[{"type":"text","text":"Clear local result."}],"stop_reason":"max_tokens"}"#
                } else if anthropic {
                    r#"{"content":[{"type":"text","text":"Clear local result."}]}"#
                } else {
                    r#"{"choices":[{"message":{"content":"Clear local result."}}]}"#
                };
                write!(
                    socket,
                    "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                )
                .unwrap();
            }
            request
        });
        let provider = OpenAiProvider::with_client(
            Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .resolve("api.anthropic.com", address)
                .timeout(std::time::Duration::from_secs(5))
                .build()
                .unwrap(),
        );
        let config = LlmConfig {
            provider: provider_name.into(),
            api_key: api_key.into(),
            model: "local-test-model".into(),
            base_url: if anthropic {
                format!("http://api.anthropic.com:{}/v1", address.port())
            } else {
                format!("http://{address}/v1")
            },
            ..LlmConfig::default()
        };
        let request = PolishRequest {
            language_variants: Default::default(),
            raw_text: "Artificial transcription".into(),
            context: crate::app_detector::types::ContextProfile::general_native().summary(),
            dictionary: Vec::new(),
            correction_rules: Vec::new(),
            polish_style: "clean".into(),
            mapped_scene_prompt: String::new(),
            active_scene_prompt: String::new(),
            polish_custom_prompt: String::new(),
            translate_enabled: false,
            target_lang: "en".into(),
            selected_text: None,
            operation_id: None,
            voice_intent: crate::voice_intent::VoiceIntent::from_parts(
                if explicit_translation {
                    crate::voice_intent::VoiceIntentKind::TranslateInsert
                } else {
                    crate::voice_intent::VoiceIntentKind::DictateInsert
                },
                crate::voice_intent::VoiceOutputPlacement::InsertAtCursor,
                1.0,
                None,
                None,
                None,
                None,
            )
            .unwrap(),
        };
        let chunks = std::sync::Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
        let captured = chunks.clone();
        let callback: ChunkCallback =
            Box::new(move |text| captured.lock().unwrap().push(text.to_string()));
        let result = provider
            .polish(&config, &request, streaming.then_some(&callback))
            .await;
        let (headers, body) = server.join().unwrap();
        if matches!(
            scenario,
            ResponseScenario::JsonError | ResponseScenario::AnthropicJsonError
        ) {
            let error = result.unwrap_err();
            assert!(matches!(error, AppError::Config(_)));
            assert_eq!(
                error.to_string(),
                "Config error: LLM returned an error response"
            );
            assert!(!error.to_string().contains(api_key));
        } else if let ResponseScenario::ReasoningStream(reasoning)
        | ResponseScenario::ReasoningJson(reasoning) = scenario
        {
            if reasoning.trim().is_empty() || explicit_translation {
                assert_eq!(
                    result.unwrap_err().to_string(),
                    "Config error: LLM returned an empty answer"
                );
                assert!(chunks.lock().unwrap().is_empty());
            } else {
                assert_eq!(result.unwrap().polished_text, reasoning);
                if streaming {
                    assert_eq!(*chunks.lock().unwrap(), vec![reasoning.to_string()]);
                } else {
                    assert!(chunks.lock().unwrap().is_empty());
                }
            }
        } else if matches!(
            scenario,
            ResponseScenario::EmptyStream | ResponseScenario::EmptyJson
        ) {
            let error = result.unwrap_err();
            assert!(matches!(error, AppError::Config(_)));
            assert_eq!(
                error.to_string(),
                "Config error: LLM returned an empty answer"
            );
        } else if let ResponseScenario::AnthropicTerminationStream(reason)
        | ResponseScenario::AnthropicTerminationJson(reason) = scenario
        {
            let error = result.unwrap_err();
            assert!(matches!(error, AppError::Config(_)));
            assert_eq!(
                error.to_string(),
                match reason {
                    "tool_use" =>
                        "Config error: Anthropic LLM response requires an unsupported tool call",
                    "pause_turn" => "Config error: Anthropic LLM response requires continuation",
                    "refusal" => "Config error: Anthropic LLM refused the request",
                    _ => "Config error: Anthropic LLM returned an unsupported completion reason",
                }
            );
        } else if let ResponseScenario::CompatibleTerminationStream(reason)
        | ResponseScenario::CompatibleTerminationJson(reason) = scenario
        {
            let error = result.unwrap_err();
            assert!(matches!(error, AppError::Config(_)));
            assert_eq!(
                error.to_string(),
                match reason {
                    "length" => "Config error: LLM response was truncated",
                    "content_filter" =>
                        "Config error: LLM response was blocked by the content filter",
                    "tool_calls" | "function_call" =>
                        "Config error: LLM response requires an unsupported tool call",
                    _ => "Config error: LLM returned an unsupported completion reason",
                }
            );
        } else if matches!(
            scenario,
            ResponseScenario::AnthropicTokenLimitStream | ResponseScenario::AnthropicTokenLimitJson
        ) {
            let error = result.unwrap_err();
            assert!(matches!(error, AppError::Config(_)));
            assert_eq!(
                error.to_string(),
                "Config error: Anthropic LLM response was truncated"
            );
        } else if matches!(
            scenario,
            ResponseScenario::AnthropicWithoutCompletion
                | ResponseScenario::AnthropicWrongDone
                | ResponseScenario::StreamWithoutCompletion
                | ResponseScenario::ReasoningWithoutCompletion
        ) {
            let error = result.unwrap_err();
            assert!(matches!(error, AppError::Config(_)));
            assert!(error.to_string().contains("incomplete"));
        } else if matches!(scenario, ResponseScenario::MalformedStream) {
            let error = result.unwrap_err();
            assert!(matches!(error, AppError::Config(_)));
            assert!(!error.to_string().contains(api_key));
            assert!(chunks.lock().unwrap().is_empty());
        } else if authentication_failure {
            let error = result.unwrap_err();
            assert!(!error.is_retryable());
            assert_eq!(
                error.to_user_error().code,
                if streaming {
                    "stt_failed"
                } else {
                    "stt_invalid_key"
                }
            );
            assert!(!error.to_string().contains(api_key));
            match (streaming, error) {
                (false, AppError::Api { status, body }) => {
                    assert_eq!(status, 401);
                    assert_eq!(body.matches("[redacted]").count(), 2);
                }
                (true, AppError::Config(message)) => {
                    assert_eq!(message, "Invalid [redacted]");
                }
                unexpected => panic!("expected HTTP error, got {unexpected:?}"),
            }
        } else {
            assert_eq!(
                result.unwrap().polished_text,
                if streaming {
                    "你好"
                } else {
                    "Clear local result."
                }
            );
        }
        if let ResponseScenario::ReasoningStream(reasoning) = scenario {
            assert_eq!(
                chunks.lock().unwrap().concat(),
                if reasoning.trim().is_empty() || explicit_translation {
                    ""
                } else {
                    reasoning
                }
            );
        } else if partial_error {
            assert_eq!(*chunks.lock().unwrap(), vec!["Partial text"]);
        } else if streaming
            && !authentication_failure
            && !matches!(scenario, ResponseScenario::MalformedStream)
            && !matches!(scenario, ResponseScenario::EmptyStream)
            && !matches!(scenario, ResponseScenario::ReasoningStream(_))
            && !matches!(scenario, ResponseScenario::ReasoningWithoutCompletion)
        {
            assert_eq!(chunks.lock().unwrap().concat(), "你好");
        } else {
            assert!(chunks.lock().unwrap().is_empty());
        }
        assert!(headers.starts_with(if anthropic {
            "POST /v1/messages "
        } else {
            "POST /v1/chat/completions "
        }));
        let authorization = headers.lines().find_map(|line| {
            let (name, value) = line.split_once(':')?;
            name.eq_ignore_ascii_case("authorization")
                .then(|| value.trim().to_string())
        });
        if anthropic {
            assert!(authorization.is_none());
            let header = |expected: &str| {
                headers.lines().find_map(|line| {
                    let (name, value) = line.split_once(':')?;
                    name.eq_ignore_ascii_case(expected)
                        .then(|| value.trim().to_string())
                })
            };
            assert_eq!(header("x-api-key"), Some(api_key.to_string()));
            assert_eq!(header("anthropic-version"), Some("2023-06-01".to_string()));
            assert!(body["system"].as_str().is_some_and(|text| !text.is_empty()));
            assert!(body["messages"]
                .as_array()
                .unwrap()
                .iter()
                .all(|message| message["role"] != "system"));
        } else if api_key.is_empty() {
            assert!(authorization.is_none());
        } else {
            assert_eq!(authorization, Some(format!("Bearer {api_key}")));
        }
        assert!(!body.to_string().contains("synthetic-byok-key"));
        assert_eq!(body["model"], "local-test-model");
        assert_eq!(body["stream"], streaming);
        assert!(body["messages"][if anthropic { 0 } else { 1 }]["content"]
            .as_str()
            .unwrap()
            .contains("Artificial transcription"));
    }

    #[tokio::test]
    async fn explicit_translation_sends_target_policy_and_returns_translation_over_http() {
        assert_explicit_translation_request(false).await;
    }

    #[tokio::test]
    async fn explicit_translation_stream_keeps_chunks_and_final_text_in_target_language() {
        assert_explicit_translation_request(true).await;
    }

    async fn assert_explicit_translation_request(streaming: bool) {
        assert_explicit_translation_response(streaming, false).await;
    }

    #[tokio::test]
    async fn explicit_translation_keeps_final_content_separate_from_reasoning() {
        for streaming in [false, true] {
            assert_explicit_translation_response(streaming, true).await;
        }
    }

    async fn assert_explicit_translation_response(streaming: bool, with_reasoning: bool) {
        assert_translation_response_with_target(streaming, with_reasoning, false).await;
    }

    #[tokio::test]
    async fn spoken_selection_target_reaches_http_without_changing_default() {
        assert_translation_response_with_target(false, false, true).await;
    }

    async fn assert_translation_response_with_target(
        streaming: bool,
        with_reasoning: bool,
        spoken_target: bool,
    ) {
        use std::io::{Read, Write};
        use std::sync::Arc;
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
            let mut socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(std::time::Instant::now() < deadline);
                        std::thread::sleep(std::time::Duration::from_millis(5));
                    }
                    Err(error) => panic!("local translation server: {error}"),
                }
            };
            socket.set_nonblocking(false).unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            socket
                .set_write_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut bytes = Vec::new();
            let mut buffer = [0_u8; 4096];
            let body = loop {
                let count = socket.read(&mut buffer).unwrap();
                assert!(count > 0);
                bytes.extend_from_slice(&buffer[..count]);
                let Some(boundary) = bytes.windows(4).position(|part| part == b"\r\n\r\n") else {
                    continue;
                };
                let headers = String::from_utf8_lossy(&bytes[..boundary]);
                let length = headers
                    .lines()
                    .find_map(|line| {
                        let (name, value) = line.split_once(':')?;
                        name.eq_ignore_ascii_case("content-length")
                            .then(|| value.trim().parse::<usize>().unwrap())
                    })
                    .unwrap();
                if bytes.len() >= boundary + 4 + length {
                    break serde_json::from_slice::<serde_json::Value>(
                        &bytes[boundary + 4..boundary + 4 + length],
                    )
                    .unwrap();
                }
            };
            let response = if streaming && with_reasoning {
                "data: {\"choices\":[{\"delta\":{\"reasoning_content\":\"Synthetic private analysis.\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"Please send me \",\"reasoning_content\":\"More analysis.\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"the meeting notes tomorrow.\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            } else if streaming {
                "data: {\"choices\":[{\"delta\":{\"content\":\"Please send me \"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"the meeting notes tomorrow.\"},\"finish_reason\":null}]}\n\ndata: {\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata: [DONE]\n\n"
            } else if with_reasoning {
                r#"{"choices":[{"message":{"content":"Please send me the meeting notes tomorrow.","reasoning_content":"Synthetic private analysis."},"finish_reason":"stop"}]}"#
            } else {
                r#"{"choices":[{"message":{"content":"Please send me the meeting notes tomorrow."},"finish_reason":"stop"}]}"#
            };
            let content_type = if streaming {
                "text/event-stream"
            } else {
                "application/json"
            };
            write!(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Type: {content_type}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{response}",
                response.len()
            )
            .unwrap();
            body
        });
        let provider = OpenAiProvider::with_client(
            Client::builder()
                .no_proxy()
                .timeout(std::time::Duration::from_secs(5))
                .build()
                .unwrap(),
        );
        let config = LlmConfig {
            provider: "openai".into(),
            api_key: "synthetic-translation-key".into(),
            model: "synthetic-translation-model".into(),
            base_url: format!("http://{address}/v1"),
            ..Default::default()
        };
        let mut request = PolishRequest {
            language_variants: Default::default(),
            raw_text: "请明天把会议记录发给我。".into(),
            context: crate::app_detector::types::ContextProfile::general_native().summary(),
            dictionary: Vec::new(),
            correction_rules: Vec::new(),
            polish_style: "clean".into(),
            mapped_scene_prompt: String::new(),
            active_scene_prompt: String::new(),
            polish_custom_prompt: String::new(),
            translate_enabled: false,
            target_lang: "en".into(),
            selected_text: None,
            operation_id: Some("synthetic-translation-operation".into()),
            voice_intent: crate::voice_intent::VoiceIntent::from_parts(
                crate::voice_intent::VoiceIntentKind::TranslateInsert,
                crate::voice_intent::VoiceOutputPlacement::InsertAtCursor,
                1.0,
                None,
                None,
                None,
                None,
            )
            .unwrap(),
        };
        if spoken_target {
            request.selected_text = Some("Synthetic selected text".to_string());
            request.raw_text = "translate this into Japanese".to_string();
            request.voice_intent = crate::voice_intent::VoiceIntentRouter::route(
                crate::voice_intent::VoiceRouteRequest {
                    mode: crate::voice_intent::VoiceMode::Dictate,
                    utterance: &request.raw_text,
                    has_selected_text: true,
                    speech_language: crate::voice_intent::SpeechLanguageMode::Explicit("en"),
                    flags: crate::voice_intent::VoiceRoutingFlags::default(),
                },
            );
        }
        let chunks = Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
        let captured = Arc::clone(&chunks);
        let callback: ChunkCallback = Box::new(move |text| {
            captured.lock().unwrap().push(text.to_string());
        });
        let result = provider
            .polish(&config, &request, streaming.then_some(&callback))
            .await;
        let body = server.join().unwrap();
        let policy = body["messages"][0]["content"].as_str().unwrap();
        if spoken_target {
            assert!(policy.contains("translate the final result into Japanese"));
            assert!(!policy.contains("translate the final result into English"));
            assert_eq!(request.target_lang, "en");
        } else {
            assert!(policy.contains("translate the entire result into English"));
        }
        assert!(
            body["messages"][if spoken_target { 2 } else { 1 }]["content"]
                .as_str()
                .unwrap()
                .contains(&request.raw_text)
        );
        assert_eq!(
            result.unwrap().polished_text,
            "Please send me the meeting notes tomorrow."
        );
        assert_eq!(body["stream"], streaming);
        if streaming {
            assert_eq!(
                chunks.lock().unwrap().concat(),
                "Please send me the meeting notes tomorrow."
            );
        } else {
            assert!(chunks.lock().unwrap().is_empty());
        }
    }
}
