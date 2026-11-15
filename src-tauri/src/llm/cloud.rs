use async_trait::async_trait;
use futures_util::StreamExt;
use reqwest::Client;

use crate::error::{managed_cloud_error, AppError};
use crate::with_desktop_client_version;

use super::{
    prompt, protocol, ChunkCallback, LlmConfig, LlmProvider, PolishRequest, PolishResponse,
};

/// Cloud LLM provider that proxies requests through the talkmore-web API.
/// Auth token is passed via the api_key field in LlmConfig. Quota is enforced server-side.
pub struct CloudLlmProvider {
    client: Client,
}

impl Default for CloudLlmProvider {
    fn default() -> Self {
        Self::new()
    }
}

impl CloudLlmProvider {
    pub fn new() -> Self {
        Self {
            client: crate::build_shared_http_client(),
        }
    }

    pub fn with_client(client: Client) -> Self {
        Self { client }
    }
}

fn contains_quota_marker(value: &str) -> bool {
    let value = value.to_ascii_lowercase();
    value.contains("quota")
        || value.contains("limit exceeded")
        || value.contains("usage exceeded")
        || value.contains("byok")
}

fn has_quota_marker(value: &serde_json::Value) -> bool {
    for field in ["code", "error_code", "type"] {
        if value
            .get(field)
            .and_then(|v| v.as_str())
            .is_some_and(contains_quota_marker)
        {
            return true;
        }
    }

    for field in ["error", "message"] {
        if let Some(item) = value.get(field) {
            if let Some(message) = item.as_str() {
                if contains_quota_marker(message) {
                    return true;
                }
            } else if has_quota_marker(item) {
                return true;
            }
        }
    }

    false
}

fn redact_cloud_error(body: String, token: &str) -> String {
    if token.is_empty() {
        body
    } else {
        body.replace(token, "[redacted]")
    }
}

const MAX_CLOUD_STREAM_BYTES: usize = 8 * 1024 * 1024;

fn check_cloud_stream_size(current: usize, additional: usize) -> Result<(), AppError> {
    if current > MAX_CLOUD_STREAM_BYTES
        || additional > MAX_CLOUD_STREAM_BYTES.saturating_sub(current)
    {
        return Err(AppError::Config("Cloud LLM response too large".to_string()));
    }
    Ok(())
}

fn require_cloud_response_text(text: &str) -> Result<(), AppError> {
    if text.trim().is_empty() {
        return Err(AppError::Config("Cloud LLM response is empty".to_string()));
    }
    Ok(())
}

fn check_cloud_finish_reason(body: &serde_json::Value) -> Result<(), AppError> {
    match &body["choices"][0]["finish_reason"] {
        serde_json::Value::Null => Ok(()),
        serde_json::Value::String(reason) if reason == "stop" => Ok(()),
        serde_json::Value::String(reason) if reason == "length" => Err(AppError::Config(
            "Cloud LLM answer was truncated".to_string(),
        )),
        serde_json::Value::String(reason) if reason == "content_filter" => Err(AppError::Config(
            "Cloud LLM answer was filtered".to_string(),
        )),
        _ => Err(AppError::Config(
            "Cloud LLM returned an unsupported completion reason".to_string(),
        )),
    }
}

async fn read_cloud_error_body(response: reqwest::Response, token: &str) -> String {
    let bytes = crate::cloud_request::read_response_with_limit(response, 65536)
        .await
        .unwrap_or_default();
    redact_cloud_error(String::from_utf8_lossy(&bytes).into_owned(), token)
}

async fn read_cloud_response(
    response: reqwest::Response,
    token: &str,
) -> Result<serde_json::Value, AppError> {
    let bytes = crate::cloud_request::read_response_with_limit(response, 8 * 1024 * 1024).await?;
    let body = serde_json::from_slice(&bytes)
        .map_err(|error| AppError::Network(format!("Invalid cloud LLM response: {error}")))?;
    let event = protocol::parse_stream_event(protocol::LlmApiKind::OpenAiCompatible, &body);
    if let Some(error) = event.error {
        return Err(AppError::Config(redact_cloud_error(error, token)));
    }
    check_cloud_finish_reason(&body)?;
    Ok(body)
}

fn cloud_llm_forbidden_error(body: &str) -> AppError {
    let parsed = serde_json::from_str::<serde_json::Value>(body).ok();

    if let Some(value) = parsed.as_ref() {
        if has_quota_marker(value) {
            return AppError::LlmQuota("Cloud LLM quota exceeded".into());
        }
    }

    AppError::Auth("Cloud LLM access denied".to_string())
}

fn cloud_context_metadata(
    context: &crate::app_detector::types::ContextProfileSummary,
) -> serde_json::Value {
    serde_json::json!({
        "profileId": context.profile_id,
        "family": context.family,
        "overrideId": context.override_id,
        "promptVersion": prompt::CONTEXT_PROMPT_VERSION,
    })
}

fn cloud_voice_intent_metadata(intent: &crate::voice_intent::VoiceIntent) -> serde_json::Value {
    serde_json::to_value(crate::voice_intent::VoiceIntentMetadata::from(intent))
        .expect("voice intent metadata must serialize")
}

#[async_trait]
impl LlmProvider for CloudLlmProvider {
    async fn polish(
        &self,
        config: &LlmConfig,
        req: &PolishRequest,
        on_chunk: Option<&ChunkCallback>,
    ) -> Result<PolishResponse, AppError> {
        if config.api_key.trim().is_empty() {
            return Err(AppError::Auth(
                "Cloud LLM: session token is missing. Please sign in first.".to_string(),
            ));
        }

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

        let api_base_url = crate::api_base_url();

        let mut context = serde_json::json!({
            "hasSelectedText": has_selected_text,
            "translateEnabled": req.translation_enabled(),
            "polishStyle": req.polish_style,
            "correctionRuleCount": req.correction_rules.iter().filter(|rule| rule.enabled).count(),
            "rawTextChars": req.raw_text.chars().count(),
            "selectedTextChars": req
                .selected_text
                .as_ref()
                .map(|s| s.chars().count())
                .unwrap_or(0)
        });
        if let Some(operation_id) = req.operation_id.as_deref() {
            context["operationId"] = serde_json::json!(operation_id);
            context["stageKey"] = serde_json::json!(format!("{operation_id}:llm"));
            context["requestType"] = serde_json::json!("voice_pipeline");
            context["clientVersion"] = serde_json::json!(crate::desktop_client_version());
        }

        let body = serde_json::json!({
            "messages": messages,
            "stream": on_chunk.is_some(),
            "context": context,
            "contextMetadata": cloud_context_metadata(&req.context),
            "voiceIntentMetadata": cloud_voice_intent_metadata(&req.voice_intent)
        });

        // Retry the initial connection (not once streaming starts)
        #[allow(unused_assignments)]
        let mut response = None;
        let mut last_error: Option<AppError> = None;
        let mut attempt = 0u32;

        loop {
            match with_desktop_client_version(
                self.client.post(format!("{}/api/proxy/llm", api_base_url)),
            )
            .header("Authorization", format!("Bearer {}", config.api_key))
            .header("Content-Type", "application/json")
            .json(&body)
            .timeout(crate::cloud_request::LLM_TIMEOUT)
            .send()
            .await
            {
                Ok(resp) => {
                    let status = resp.status();
                    if status.is_success() {
                        response = Some(resp);
                        break;
                    } else if status.as_u16() == 401 {
                        let text = read_cloud_error_body(resp, &config.api_key).await;
                        if let Some(error) = managed_cloud_error(status.as_u16(), &text) {
                            return Err(error);
                        }
                        return Err(AppError::Api {
                            status: status.as_u16(),
                            body: text,
                        });
                    } else if status.as_u16() == 403 {
                        let text = read_cloud_error_body(resp, &config.api_key).await;
                        return Err(cloud_llm_forbidden_error(&text));
                    } else if status.as_u16() >= 500 && attempt < 2 {
                        let body_text = read_cloud_error_body(resp, &config.api_key).await;
                        tracing::warn!(
                            "Cloud LLM server error {} (attempt {}/3), retrying",
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
                        let text = read_cloud_error_body(resp, &config.api_key).await;
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
                        "Cloud LLM connection timeout (attempt {}/3), retrying",
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
                        "Cloud LLM connection failed (attempt {}/3), retrying",
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
            let mut full_text = String::new();
            let mut stream = response.bytes_stream();
            let mut decoder = super::sse::SseDecoder::new(MAX_CLOUD_STREAM_BYTES);
            let mut completed = false;

            while let Some(chunk) = stream.next().await {
                let chunk = chunk?;
                if completed {
                    continue;
                }
                decoder.push(&chunk)?;
                while let Some(data) = decoder.next_event()? {
                    if data.is_empty() {
                        continue;
                    }
                    if data == "[DONE]" {
                        completed = true;
                        break;
                    }
                    let value = serde_json::from_str::<serde_json::Value>(&data).map_err(|_| {
                        AppError::Config("Invalid cloud LLM stream event".to_string())
                    })?;
                    let event = protocol::parse_stream_event(
                        protocol::LlmApiKind::OpenAiCompatible,
                        &value,
                    );
                    if let Some(error) = event.error {
                        return Err(AppError::Config(redact_cloud_error(error, &config.api_key)));
                    }
                    check_cloud_finish_reason(&value)?;
                    if let Some(content) = event.text {
                        if !content.is_empty() {
                            check_cloud_stream_size(full_text.len(), content.len())?;
                            full_text.push_str(&content);
                            callback(&content);
                        }
                    }
                }
            }

            if !completed {
                return Err(AppError::Config(
                    "Cloud LLM stream is incomplete".to_string(),
                ));
            }
            require_cloud_response_text(&full_text)?;
            Ok(PolishResponse {
                polished_text: full_text,
            })
        } else {
            let v = read_cloud_response(response, &config.api_key).await?;
            let text = v["text"]
                .as_str()
                .or_else(|| v["choices"][0]["message"]["content"].as_str())
                .unwrap_or("")
                .to_string();

            require_cloud_response_text(&text)?;
            Ok(PolishResponse {
                polished_text: text,
            })
        }
    }

    fn name(&self) -> &str {
        "Cloud"
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::app_detector::types::{ContextFamily, ContextProfileSummary};

    #[tokio::test]
    async fn blank_cloud_tokens_are_rejected_before_model_requests() {
        let request = PolishRequest {
            language_variants: Default::default(),
            raw_text: "Synthetic dictation".into(),
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
                crate::voice_intent::VoiceIntentKind::DictateInsert,
                crate::voice_intent::VoiceOutputPlacement::InsertAtCursor,
                1.0,
                None,
                None,
                None,
                None,
            )
            .unwrap(),
        };
        let provider = CloudLlmProvider::with_client(
            Client::builder()
                .no_proxy()
                .resolve("www.opentypeless.com", "127.0.0.1:9".parse().unwrap())
                .timeout(std::time::Duration::from_millis(100))
                .build()
                .unwrap(),
        );
        for token in ["", " ", "\t\n"] {
            let config = LlmConfig {
                provider: "cloud".into(),
                api_key: token.into(),
                ..Default::default()
            };
            assert!(matches!(
                provider.polish(&config, &request, None).await,
                Err(AppError::Auth(_))
            ));
        }
    }

    #[test]
    fn cloud_finish_reason_rejects_non_text_and_malformed_termination() {
        for reason in [
            serde_json::json!("tool_calls"),
            serde_json::json!("function_call"),
            serde_json::json!("synthetic-private-reason"),
            serde_json::json!(42),
            serde_json::json!({}),
        ] {
            let result = check_cloud_finish_reason(&serde_json::json!({
                "choices": [{"finish_reason": reason}]
            }));
            let error = result.unwrap_err();
            assert!(!error.to_string().contains("synthetic-private-reason"));
        }
        for body in [
            serde_json::json!({"text": "Answer"}),
            serde_json::json!({"choices": [{"finish_reason": null}]}),
            serde_json::json!({"choices": [{"finish_reason": "stop"}]}),
        ] {
            assert!(check_cloud_finish_reason(&body).is_ok());
        }
    }

    #[test]
    fn cloud_response_requires_text_without_changing_its_whitespace() {
        for text in ["", " \n\t", "\u{3000}"] {
            assert!(require_cloud_response_text(text).is_err());
        }
        assert!(require_cloud_response_text(" 你好😀 \n").is_ok());
        let source = include_str!("cloud.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        assert!(implementation.contains("require_cloud_response_text(&text)?;"));
        assert!(implementation.contains("require_cloud_response_text(&full_text)?;"));
    }

    #[test]
    fn cloud_stream_size_allows_the_boundary_and_rejects_overflow() {
        assert!(check_cloud_stream_size(MAX_CLOUD_STREAM_BYTES - 1, 1).is_ok());
        assert!(check_cloud_stream_size(MAX_CLOUD_STREAM_BYTES, 0).is_ok());
        assert!(check_cloud_stream_size(MAX_CLOUD_STREAM_BYTES, 1).is_err());
        assert!(check_cloud_stream_size(0, MAX_CLOUD_STREAM_BYTES + 1).is_err());
        assert!(check_cloud_stream_size(usize::MAX, 1).is_err());
    }

    #[test]
    fn cloud_stream_checks_size_before_buffering_and_delivery() {
        let source = include_str!("cloud.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        assert!(implementation.contains("decoder.push(&chunk)?;"));
        assert!(
            implementation.contains("check_cloud_stream_size(full_text.len(), content.len())?;")
        );
    }

    #[test]
    fn cloud_stream_checks_protocol_errors_before_delivering_text() {
        let source = include_str!("cloud.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        let parser = implementation
            .find("protocol::parse_stream_event")
            .unwrap_or(usize::MAX);
        let error_guard = implementation
            .find("if let Some(error) = event.error")
            .unwrap_or(usize::MAX);
        let delivery = implementation.find("callback(").unwrap();
        assert!(parser < error_guard);
        assert!(error_guard < delivery);
    }

    #[test]
    fn cloud_protocol_error_excludes_same_event_text_and_redacts_message() {
        let token = "synthetic-cloud-event-token";
        let event = protocol::parse_stream_event(
            protocol::LlmApiKind::OpenAiCompatible,
            &serde_json::json!({
                "error": {"message": format!("Denied {token}")},
                "choices": [{"delta": {"content": "Must not deliver"}}]
            }),
        );
        assert!(event.text.is_none());
        let error = AppError::Config(redact_cloud_error(event.error.unwrap(), token));
        assert!(!error.to_string().contains(token));
        assert!(error.to_string().contains("[redacted]"));
    }

    #[test]
    fn cloud_success_response_uses_bounded_json_reader() {
        let source = include_str!("cloud.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        assert!(!implementation.contains("response.json().await"));
        assert!(implementation.contains("read_cloud_response(response, &config.api_key).await?"));
    }

    #[test]
    fn cloud_http_error_branches_use_bounded_body_reads() {
        let source = include_str!("cloud.rs");
        let implementation = source.split("#[cfg(test)]").next().unwrap();
        assert!(!implementation.contains("resp.text().await"));
        assert_eq!(
            implementation
                .matches("read_cloud_error_body(resp,")
                .count(),
            4
        );
    }

    #[tokio::test]
    async fn cloud_json_reader_rejects_error_envelopes_before_answer_text() {
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
            let body =
                r#"{"error":{"message":"Denied synthetic-json-token"},"text":"Must not deliver"}"#;
            let response = format!(
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            );
            let _ = socket.write_all(response.as_bytes());
        });
        let response = Client::builder()
            .no_proxy()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap()
            .get(format!("http://{address}"))
            .send()
            .await
            .unwrap();
        let result = read_cloud_response(response, "synthetic-json-token").await;
        server.join().unwrap();
        let error = result.unwrap_err();
        assert!(matches!(error, AppError::Config(_)));
        assert!(!error.to_string().contains("synthetic-json-token"));
        assert!(error.to_string().contains("[redacted]"));
    }

    #[tokio::test]
    async fn cloud_json_reader_preserves_unicode_and_rejects_oversized_responses() {
        use std::io::{Read, Write};
        for oversized in [false, true] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0_u8; 4096];
                assert!(socket.read(&mut request).unwrap() > 0);
                let body = if oversized {
                    ""
                } else {
                    r#"{"text":"你好😀","choices":[{"message":{"content":"备用"}}]}"#
                };
                let length = if oversized {
                    8 * 1024 * 1024 + 1
                } else {
                    body.len()
                };
                let response = format!(
                    "HTTP/1.1 200 OK\r\nContent-Length: {length}\r\nConnection: close\r\n\r\n{body}"
                );
                let _ = socket.write_all(response.as_bytes());
            });
            let response = Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(std::time::Duration::from_secs(5))
                .build()
                .unwrap()
                .get(format!("http://{address}"))
                .send()
                .await
                .unwrap();
            let result = read_cloud_response(response, "").await;
            server.join().unwrap();
            if oversized {
                assert!(matches!(result, Err(AppError::Config(_))));
            } else {
                let value = result.unwrap();
                assert_eq!(value["text"], "你好😀");
                assert_eq!(value["choices"][0]["message"]["content"], "备用");
            }
        }
    }

    #[tokio::test]
    async fn cloud_error_body_bounds_real_responses_and_redacts_token_echoes() {
        use std::io::{Read, Write};
        for oversized in [false, true] {
            let token = "synthetic-cloud-error-token";
            let body = if oversized {
                "x".repeat(65537)
            } else {
                format!("Denied {token} and {token}")
            };
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0_u8; 4096];
                assert!(socket.read(&mut request).unwrap() > 0);
                let response = format!(
                    "HTTP/1.1 401 Unauthorized\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                );
                let _ = socket.write_all(response.as_bytes());
            });
            let response = Client::builder()
                .no_proxy()
                .redirect(reqwest::redirect::Policy::none())
                .timeout(std::time::Duration::from_secs(5))
                .build()
                .unwrap()
                .get(format!("http://{address}"))
                .send()
                .await
                .unwrap();
            let status = response.status().as_u16();
            let body = read_cloud_error_body(response, token).await;
            server.join().unwrap();
            assert_eq!(status, 401);
            assert!(!body.contains(token));
            if oversized {
                assert!(body.is_empty());
            } else {
                assert_eq!(body, "Denied [redacted] and [redacted]");
            }
            assert!(!AppError::Api { status, body }.is_retryable());
        }
    }

    #[tokio::test]
    async fn cloud_stream_excludes_content_after_done() {
        run_cloud_stream_probe(CloudStreamScenario::Completed).await;
    }

    #[tokio::test]
    async fn cloud_stream_returns_redacted_error_after_partial_text() {
        run_cloud_stream_probe(CloudStreamScenario::ErrorAfterText).await;
    }

    #[tokio::test]
    async fn cloud_stream_returns_error_without_delivering_any_text() {
        run_cloud_stream_probe(CloudStreamScenario::ErrorBeforeText).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_oversized_unterminated_data_without_delivery() {
        run_cloud_stream_probe(CloudStreamScenario::OversizedBuffer).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_malformed_json_after_partial_text() {
        run_cloud_stream_probe(CloudStreamScenario::MalformedJson).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_invalid_utf8_without_delivering_corrupted_text() {
        run_cloud_stream_probe(CloudStreamScenario::InvalidUtf8).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_eof_without_completion_signal() {
        run_cloud_stream_probe(CloudStreamScenario::MissingCompletion).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_completion_without_text() {
        run_cloud_stream_probe(CloudStreamScenario::EmptyCompleted).await;
    }

    #[tokio::test]
    async fn cloud_nonstream_rejects_whitespace_answer() {
        run_cloud_stream_probe(CloudStreamScenario::WhitespaceJson).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_answer_growth_before_excess_text_is_delivered() {
        run_cloud_stream_probe(CloudStreamScenario::OversizedAnswer).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_length_limited_answer() {
        run_cloud_stream_probe(CloudStreamScenario::LengthLimited).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_filtered_answer() {
        run_cloud_stream_probe(CloudStreamScenario::Filtered).await;
    }

    #[tokio::test]
    async fn cloud_stream_rejects_tool_call_completion() {
        run_cloud_stream_probe(CloudStreamScenario::ToolCall).await;
    }

    #[tokio::test]
    async fn cloud_nonstream_rejects_incomplete_completion_reasons() {
        if std::env::var("OTL_CLOUD_STREAM_PROBE_CHILD").as_deref() == Ok("1") {
            let reason = match std::env::var("OTL_CLOUD_JSON_REASON").unwrap().as_str() {
                "length" => "length",
                "content_filter" => "content_filter",
                "tool_calls" => "tool_calls",
                _ => panic!("Unexpected synthetic completion reason"),
            };
            run_cloud_stream_probe(CloudStreamScenario::IncompleteJson(reason)).await;
            return;
        }
        for reason in ["length", "content_filter", "tool_calls"] {
            run_cloud_stream_probe(CloudStreamScenario::IncompleteJson(reason)).await;
        }
    }

    #[derive(Clone, Copy)]
    enum CloudStreamScenario {
        Completed,
        Translation,
        ErrorAfterText,
        ErrorBeforeText,
        OversizedBuffer,
        MalformedJson,
        InvalidUtf8,
        MissingCompletion,
        EmptyCompleted,
        WhitespaceJson,
        OversizedAnswer,
        LengthLimited,
        Filtered,
        ToolCall,
        IncompleteJson(&'static str),
    }

    #[tokio::test]
    async fn cloud_translation_sends_english_policy_and_returns_translated_chunks() {
        run_cloud_stream_probe(CloudStreamScenario::Translation).await;
    }

    async fn run_cloud_stream_probe(scenario: CloudStreamScenario) {
        const CHILD_MARKER: &str = "OTL_CLOUD_STREAM_PROBE_CHILD";
        if std::env::var(CHILD_MARKER).as_deref() == Ok("1") {
            let endpoint = url::Url::parse(&crate::api_base_url()).unwrap();
            assert_eq!(endpoint.scheme(), "http");
            assert_eq!(endpoint.host_str(), Some("127.0.0.1"));
            assert!(endpoint.port().is_some());
            let provider = CloudLlmProvider::with_client(
                Client::builder()
                    .no_proxy()
                    .redirect(reqwest::redirect::Policy::none())
                    .build()
                    .unwrap(),
            );
            let request = PolishRequest {
                language_variants: Default::default(),
                raw_text: if matches!(scenario, CloudStreamScenario::Translation) {
                    "请明天把会议记录发给我。".to_string()
                } else {
                    "Synthetic dictation".to_string()
                },
                context: crate::app_detector::types::ContextProfile::general_native().summary(),
                dictionary: Vec::new(),
                correction_rules: Vec::new(),
                polish_style: "clean".to_string(),
                mapped_scene_prompt: String::new(),
                active_scene_prompt: String::new(),
                polish_custom_prompt: String::new(),
                translate_enabled: false,
                target_lang: "en".to_string(),
                selected_text: None,
                operation_id: None,
                voice_intent: crate::voice_intent::VoiceIntent::from_parts(
                    if matches!(scenario, CloudStreamScenario::Translation) {
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
            let config = LlmConfig {
                provider: "cloud".to_string(),
                api_key: "synthetic-cloud-token".to_string(),
                ..LlmConfig::default()
            };
            let chunks = std::sync::Arc::new(std::sync::Mutex::new(Vec::<String>::new()));
            let captured = chunks.clone();
            let callback: ChunkCallback =
                Box::new(move |text| captured.lock().unwrap().push(text.to_string()));
            let response = tokio::time::timeout(
                std::time::Duration::from_secs(5),
                provider.polish(
                    &config,
                    &request,
                    if matches!(
                        scenario,
                        CloudStreamScenario::WhitespaceJson
                            | CloudStreamScenario::IncompleteJson(_)
                    ) {
                        None
                    } else {
                        Some(&callback)
                    },
                ),
            )
            .await
            .unwrap();
            match scenario {
                CloudStreamScenario::Translation => {
                    assert_eq!(
                        response.unwrap().polished_text,
                        "Please send me the meeting notes tomorrow."
                    );
                    assert_eq!(
                        chunks.lock().unwrap().concat(),
                        "Please send me the meeting notes tomorrow."
                    );
                }
                CloudStreamScenario::Completed => {
                    assert_eq!(response.unwrap().polished_text, "你好😀BeforeSpaced");
                    assert_eq!(*chunks.lock().unwrap(), vec!["你好😀Before", "Spaced"]);
                }
                CloudStreamScenario::IncompleteJson(_) => {
                    assert!(matches!(response, Err(AppError::Config(_))));
                    assert!(chunks.lock().unwrap().is_empty());
                }
                CloudStreamScenario::EmptyCompleted | CloudStreamScenario::WhitespaceJson => {
                    let error = response.unwrap_err();
                    assert!(matches!(error, AppError::Config(_)));
                    assert!(error.to_string().contains("empty"));
                    assert!(chunks.lock().unwrap().is_empty());
                }
                CloudStreamScenario::OversizedBuffer => {
                    let error = response.unwrap_err();
                    assert!(matches!(error, AppError::Config(_)));
                    assert!(error.to_string().contains("response too large"));
                    assert!(chunks.lock().unwrap().is_empty());
                }
                CloudStreamScenario::OversizedAnswer => {
                    let error = response.unwrap_err();
                    assert!(matches!(error, AppError::Config(_)));
                    assert!(error.to_string().contains("response too large"));
                    let delivered = chunks.lock().unwrap();
                    assert_eq!(delivered.len(), 128);
                    assert_eq!(
                        delivered.iter().map(String::len).sum::<usize>(),
                        MAX_CLOUD_STREAM_BYTES
                    );
                    assert!(delivered.iter().all(|text| text.len() == 65536));
                }
                CloudStreamScenario::MalformedJson | CloudStreamScenario::InvalidUtf8 => {
                    let error = response.unwrap_err();
                    assert!(matches!(error, AppError::Config(_)));
                    assert!(!error.to_string().contains("synthetic-cloud-token"));
                    assert_eq!(*chunks.lock().unwrap(), vec!["你好😀Before"]);
                }
                CloudStreamScenario::MissingCompletion => {
                    let error = response.unwrap_err();
                    assert!(matches!(error, AppError::Config(_)));
                    assert!(error.to_string().contains("incomplete"));
                    assert_eq!(*chunks.lock().unwrap(), vec!["你好😀Before"]);
                }
                CloudStreamScenario::LengthLimited
                | CloudStreamScenario::Filtered
                | CloudStreamScenario::ToolCall => {
                    let error = response.unwrap_err();
                    assert!(matches!(error, AppError::Config(_)));
                    assert_eq!(*chunks.lock().unwrap(), vec!["你好😀Before"]);
                }
                CloudStreamScenario::ErrorAfterText | CloudStreamScenario::ErrorBeforeText => {
                    let error = response.unwrap_err();
                    assert!(matches!(error, AppError::Config(_)));
                    assert!(!error.to_string().contains(&config.api_key));
                    assert!(error.to_string().contains("[redacted]"));
                    if matches!(scenario, CloudStreamScenario::ErrorBeforeText) {
                        assert!(chunks.lock().unwrap().is_empty());
                    } else {
                        assert_eq!(*chunks.lock().unwrap(), vec!["你好😀Before"]);
                    }
                }
            }
            return;
        }

        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let address = listener.local_addr().unwrap();
        let server = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(10);
            let mut socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(std::time::Instant::now() < deadline);
                        std::thread::sleep(std::time::Duration::from_millis(10));
                    }
                    Err(error) => panic!("probe connection failed: {error}"),
                }
            };
            socket.set_nonblocking(false).unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            socket
                .set_write_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            socket.set_nodelay(true).unwrap();
            let mut bytes = Vec::new();
            let mut buffer = [0_u8; 4096];
            loop {
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
                    assert!(headers.starts_with("POST /api/proxy/llm "));
                    let request: serde_json::Value =
                        serde_json::from_slice(&bytes[boundary + 4..boundary + 4 + length])
                            .unwrap();
                    if matches!(scenario, CloudStreamScenario::Translation) {
                        assert_eq!(request["context"]["translateEnabled"], true);
                        assert_eq!(request["voiceIntentMetadata"]["kind"], "translate_insert");
                        assert!(request["messages"][0]["content"]
                            .as_str()
                            .unwrap()
                            .contains("translate the entire result into English"));
                        assert!(request["messages"][1]["content"]
                            .as_str()
                            .unwrap()
                            .contains("请明天把会议记录发给我。"));
                    }
                    assert_eq!(
                        request["stream"],
                        !matches!(
                            scenario,
                            CloudStreamScenario::WhitespaceJson
                                | CloudStreamScenario::IncompleteJson(_)
                        )
                    );
                    break;
                }
            }
            if matches!(
                scenario,
                CloudStreamScenario::WhitespaceJson | CloudStreamScenario::IncompleteJson(_)
            ) {
                let body = match scenario {
                    CloudStreamScenario::IncompleteJson(reason) => serde_json::json!({
                        "text": "Must not deliver",
                        "choices": [{"message": {"content": "Must not deliver"}, "finish_reason": reason}]
                    }).to_string(),
                    _ => r#"{"text":" \n\t"}"#.to_string(),
                };
                write!(
                    socket,
                    "HTTP/1.1 200 OK\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                    body.len()
                )
                .unwrap();
                return;
            }
            socket
                .write_all(b"HTTP/1.1 200 OK\r\nContent-Type: text/event-stream\r\nTransfer-Encoding: chunked\r\nConnection: close\r\n\r\n")
                .unwrap();
            if matches!(scenario, CloudStreamScenario::Translation) {
                let event = "data:{\"choices\":[{\"delta\":{\"content\":\"Please send me the meeting notes tomorrow.\"}}]}\n\ndata:{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\ndata:[DONE]\n\n";
                write!(socket, "{:x}\r\n{event}\r\n0\r\n\r\n", event.len()).unwrap();
                return;
            }
            if matches!(scenario, CloudStreamScenario::OversizedAnswer) {
                let content = "x".repeat(65536);
                let event = format!(
                    "data:{{\"choices\":[{{\"delta\":{{\"content\":\"{content}\"}}}}]}}\n\n"
                );
                for _event_index in 0..129 {
                    if write!(socket, "{:x}\r\n{event}\r\n", event.len()).is_err() {
                        return;
                    }
                }
                let _ = socket.write_all(b"0\r\n\r\n");
                return;
            }
            if matches!(scenario, CloudStreamScenario::EmptyCompleted) {
                let event = "data:[DONE]\n\n";
                write!(socket, "{:x}\r\n{event}\r\n0\r\n\r\n", event.len()).unwrap();
                return;
            }
            if matches!(scenario, CloudStreamScenario::OversizedBuffer) {
                let payload = vec![b'x'; 65536];
                for _chunk_index in 0..129 {
                    if write!(socket, "{:x}\r\n", payload.len()).is_err()
                        || socket.write_all(&payload).is_err()
                        || socket.write_all(b"\r\n").is_err()
                    {
                        return;
                    }
                }
                let _ = socket.write_all(b"0\r\n\r\n");
                return;
            }
            let first_event = "\u{feff}data:{\"choices\":\r\ndata:[{\"delta\":{\"content\":\"你好😀Before\"}}]}\r\n\r\n";
            if !matches!(scenario, CloudStreamScenario::ErrorBeforeText) {
                for byte in first_event.as_bytes() {
                    if socket.write_all(b"1\r\n").is_err()
                        || socket.write_all(&[*byte]).is_err()
                        || socket.write_all(b"\r\n").is_err()
                        || socket.flush().is_err()
                    {
                        return;
                    }
                    std::thread::sleep(std::time::Duration::from_millis(2));
                }
            }
            if matches!(scenario, CloudStreamScenario::MalformedJson) {
                let malformed_event = "data:{invalid synthetic-cloud-token}\n\ndata:[DONE]\n\n";
                let _ = write!(
                    socket,
                    "{:x}\r\n{malformed_event}\r\n0\r\n\r\n",
                    malformed_event.len()
                );
                return;
            }
            if matches!(scenario, CloudStreamScenario::MissingCompletion) {
                socket.write_all(b"0\r\n\r\n").unwrap();
                return;
            }
            if matches!(
                scenario,
                CloudStreamScenario::LengthLimited
                    | CloudStreamScenario::Filtered
                    | CloudStreamScenario::ToolCall
            ) {
                let reason = if matches!(scenario, CloudStreamScenario::LengthLimited) {
                    "length"
                } else if matches!(scenario, CloudStreamScenario::ToolCall) {
                    "tool_calls"
                } else {
                    "content_filter"
                };
                let event = format!(
                    "data:{{\"choices\":[{{\"delta\":{{\"content\":\"Must not deliver\"}},\"finish_reason\":\"{reason}\"}}]}}\n\ndata:[DONE]\n\n"
                );
                let _ = write!(socket, "{:x}\r\n{event}\r\n0\r\n\r\n", event.len());
                return;
            }
            if matches!(scenario, CloudStreamScenario::InvalidUtf8) {
                let mut invalid_event = b"data:{\"choices\":[{\"delta\":{\"content\":\"".to_vec();
                invalid_event.push(0xff);
                invalid_event.extend_from_slice(b"\"}}]}\n\ndata:[DONE]\n\n");
                let _ = write!(socket, "{:x}\r\n", invalid_event.len());
                let _ = socket.write_all(&invalid_event);
                let _ = socket.write_all(b"\r\n0\r\n\r\n");
                return;
            }
            if matches!(
                scenario,
                CloudStreamScenario::ErrorAfterText | CloudStreamScenario::ErrorBeforeText
            ) {
                let error_event = "data:{\"error\":{\"message\":\"Denied synthetic-cloud-token\"},\"choices\":[{\"delta\":{\"content\":\"Must not deliver\"}}]}\n\ndata:[DONE]\n\n";
                let _ = write!(
                    socket,
                    "{:x}\r\n{error_event}\r\n0\r\n\r\n",
                    error_event.len()
                );
                return;
            }
            for chunk in [
                ": keepalive\n\ndata:\n\ndata: \n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"Spaced\"}}]}\n\n",
                "data:{\"choices\":[{\"delta\":{},\"finish_reason\":\"stop\"}]}\n\n",
                "data:[DONE]\n\ndata: {\"choices\":[{\"delta\":{\"content\":\"SameChunkTail\"}}]}\n\n",
                "data: {\"choices\":[{\"delta\":{\"content\":\"After\"}}]}\n\n",
            ] {
                if write!(socket, "{:x}\r\n{chunk}\r\n", chunk.len()).is_err()
                    || socket.flush().is_err()
                {
                    return;
                }
                std::thread::sleep(std::time::Duration::from_millis(50));
            }
            let _ = socket.write_all(b"0\r\n\r\n");
        });
        let test_name = match scenario {
            CloudStreamScenario::Translation => {
                "llm::cloud::tests::cloud_translation_sends_english_policy_and_returns_translated_chunks"
            }
            CloudStreamScenario::Completed => {
                "llm::cloud::tests::cloud_stream_excludes_content_after_done"
            }
            CloudStreamScenario::ErrorAfterText => {
                "llm::cloud::tests::cloud_stream_returns_redacted_error_after_partial_text"
            }
            CloudStreamScenario::ErrorBeforeText => {
                "llm::cloud::tests::cloud_stream_returns_error_without_delivering_any_text"
            }
            CloudStreamScenario::OversizedBuffer => {
                "llm::cloud::tests::cloud_stream_rejects_oversized_unterminated_data_without_delivery"
            }
            CloudStreamScenario::MalformedJson => {
                "llm::cloud::tests::cloud_stream_rejects_malformed_json_after_partial_text"
            }
            CloudStreamScenario::InvalidUtf8 => {
                "llm::cloud::tests::cloud_stream_rejects_invalid_utf8_without_delivering_corrupted_text"
            }
            CloudStreamScenario::MissingCompletion => {
                "llm::cloud::tests::cloud_stream_rejects_eof_without_completion_signal"
            }
            CloudStreamScenario::EmptyCompleted => {
                "llm::cloud::tests::cloud_stream_rejects_completion_without_text"
            }
            CloudStreamScenario::WhitespaceJson => {
                "llm::cloud::tests::cloud_nonstream_rejects_whitespace_answer"
            }
            CloudStreamScenario::OversizedAnswer => {
                "llm::cloud::tests::cloud_stream_rejects_answer_growth_before_excess_text_is_delivered"
            }
            CloudStreamScenario::LengthLimited => {
                "llm::cloud::tests::cloud_stream_rejects_length_limited_answer"
            }
            CloudStreamScenario::Filtered => {
                "llm::cloud::tests::cloud_stream_rejects_filtered_answer"
            }
            CloudStreamScenario::ToolCall => {
                "llm::cloud::tests::cloud_stream_rejects_tool_call_completion"
            }
            CloudStreamScenario::IncompleteJson(_) => {
                "llm::cloud::tests::cloud_nonstream_rejects_incomplete_completion_reasons"
            }
        };
        let child = std::process::Command::new(std::env::current_exe().unwrap())
            .arg("--exact")
            .arg(test_name)
            .arg("--nocapture")
            .env(CHILD_MARKER, "1")
            .env("API_BASE_URL", format!("http://{address}"))
            .env(
                "OTL_CLOUD_JSON_REASON",
                match scenario {
                    CloudStreamScenario::IncompleteJson(reason) => reason,
                    _ => "",
                },
            )
            .output()
            .unwrap();
        server.join().unwrap();
        assert!(
            child.status.success(),
            "isolated cloud probe failed: {} {}",
            String::from_utf8_lossy(&child.stdout),
            String::from_utf8_lossy(&child.stderr)
        );
    }

    #[test]
    fn forbidden_error_uses_llm_quota_code() {
        let err = cloud_llm_forbidden_error(
            r#"{"code":"llm_quota_exceeded","error":"LLM quota exceeded"}"#,
        );
        assert!(matches!(err, AppError::LlmQuota(_)));
    }

    #[test]
    fn forbidden_error_uses_nested_llm_quota_message() {
        let err = cloud_llm_forbidden_error(
            r#"{"error":{"code":"llm_quota_exceeded","message":"LLM quota exceeded"}}"#,
        );
        assert!(matches!(err, AppError::LlmQuota(_)));
    }

    #[test]
    fn cloud_quota_error_redacts_token_echo_in_display_and_details() {
        let token = "synthetic-cloud-token";
        let body = serde_json::json!({
            "code": "llm_quota_exceeded",
            "error": format!("Quota exceeded for {token} synthetic-private-account-detail")
        });
        let error = cloud_llm_forbidden_error(&body.to_string());

        assert!(matches!(error, AppError::LlmQuota(_)));
        assert!(!error.is_retryable());
        assert!(!error.to_string().contains(token));
        assert!(!error
            .to_string()
            .contains("synthetic-private-account-detail"));
        let user_error = error.to_user_error();
        assert_eq!(user_error.code, "llm_quota_exceeded");
        assert_eq!(
            user_error.details.as_deref(),
            Some("Cloud LLM quota exceeded")
        );
    }

    #[test]
    fn managed_context_metadata_excludes_labels_icons_and_raw_signals() {
        let metadata = cloud_context_metadata(&ContextProfileSummary {
            profile_id: "email.gmail".to_string(),
            family: ContextFamily::Email,
            app_label: "Gmail private label".to_string(),
            icon_key: "gmail".to_string(),
            override_id: Some("gmail".to_string()),
            browser_access_status: crate::app_detector::types::BrowserAccessStatus::NotApplicable,
            browser_target: None,
        });

        assert_eq!(metadata["profileId"], "email.gmail");
        assert_eq!(metadata["family"], "email");
        assert_eq!(metadata["promptVersion"], prompt::CONTEXT_PROMPT_VERSION);
        let serialized = metadata.to_string();
        for forbidden in [
            "private label",
            "iconKey",
            "processName",
            "windowTitle",
            "browserHost",
            "transcript",
        ] {
            assert!(!serialized.contains(forbidden));
        }
    }

    #[test]
    fn managed_voice_intent_metadata_excludes_payload_and_uses_closed_bands() {
        let intent = crate::voice_intent::VoiceIntent::from_parts(
            crate::voice_intent::VoiceIntentKind::DraftInsert,
            crate::voice_intent::VoiceOutputPlacement::InsertAtCursor,
            1.0,
            None,
            Some("private draft payload".to_string()),
            Some(crate::voice_intent::CommandLocale::En),
            None,
        )
        .unwrap();
        let metadata = cloud_voice_intent_metadata(&intent);

        assert_eq!(
            metadata,
            serde_json::json!({
                "kind": "draft_insert",
                "placement": "insert_at_cursor",
                "grammarLocale": "en",
                "confidenceBand": "exact"
            })
        );
        assert!(!metadata.to_string().contains("private draft payload"));
    }
}
