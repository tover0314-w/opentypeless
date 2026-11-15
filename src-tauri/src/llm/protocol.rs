use reqwest::RequestBuilder;
use serde_json::{json, Value};
use std::time::Duration;

const ANTHROPIC_API_HOST: &str = "api.anthropic.com";
const OPENAI_API_HOST: &str = "api.openai.com";
const ANTHROPIC_VERSION: &str = "2023-06-01";

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum LlmApiKind {
    OpenAiCompatible,
    AnthropicMessages,
}

#[derive(Debug, Default, PartialEq, Eq)]
pub struct StreamEvent {
    pub text: Option<String>,
    pub reasoning: Option<String>,
    pub error: Option<String>,
    pub done: bool,
}

pub fn detect_api_kind(provider: &str, base_url: &str) -> LlmApiKind {
    let provider = provider.trim().to_ascii_lowercase();
    let host = url::Url::parse(base_url.trim())
        .ok()
        .and_then(|url| url.host_str().map(str::to_ascii_lowercase));

    if matches!(provider.as_str(), "claude" | "anthropic")
        && host.as_deref() == Some(ANTHROPIC_API_HOST)
    {
        LlmApiKind::AnthropicMessages
    } else {
        LlmApiKind::OpenAiCompatible
    }
}

fn parse_http_url(base_url: &str) -> Result<url::Url, String> {
    let mut url = url::Url::parse(base_url.trim())
        .map_err(|error| format!("Invalid LLM base URL: {error}"))?;
    if !matches!(url.scheme(), "http" | "https") {
        return Err("LLM base URL must use http or https scheme".to_string());
    }
    if !url.username().is_empty() || url.password().is_some() {
        return Err("LLM base URL must not include credentials".to_string());
    }
    if url.fragment().is_some() {
        return Err("LLM base URL must not include a fragment".to_string());
    }
    url.set_fragment(None);
    Ok(url)
}

fn replace_or_append_path(url: &mut url::Url, current_suffix: &str, target_suffix: &str) {
    let path = url.path().trim_end_matches('/');
    let root = path.strip_suffix(current_suffix).unwrap_or(path);
    url.set_path(&format!("{root}{target_suffix}"));
}

fn anthropic_api_root(path: &str) -> String {
    let path = path.trim_end_matches('/');
    if let Some(root) = path.strip_suffix("/messages") {
        return root.to_string();
    }
    if let Some(root) = path.strip_suffix("/models") {
        return root.to_string();
    }
    if path.is_empty() {
        "/v1".to_string()
    } else {
        path.to_string()
    }
}

pub fn chat_endpoint(provider: &str, base_url: &str) -> Result<String, String> {
    let kind = detect_api_kind(provider, base_url);
    let mut url = parse_http_url(base_url)?;
    match kind {
        LlmApiKind::AnthropicMessages => {
            let root = anthropic_api_root(url.path());
            url.set_path(&format!("{root}/messages"));
        }
        LlmApiKind::OpenAiCompatible => {
            let path = url.path().trim_end_matches('/');
            if !path.ends_with("/chat/completions") {
                url.set_path(&format!("{path}/chat/completions"));
            }
        }
    }
    Ok(url.to_string())
}

pub fn models_endpoint(provider: &str, base_url: &str) -> Result<String, String> {
    let kind = detect_api_kind(provider, base_url);
    let mut url = parse_http_url(base_url)?;
    match kind {
        LlmApiKind::AnthropicMessages => {
            let root = anthropic_api_root(url.path());
            url.set_path(&format!("{root}/models"));
        }
        LlmApiKind::OpenAiCompatible => {
            replace_or_append_path(&mut url, "/chat/completions", "/models");
        }
    }
    Ok(url.to_string())
}

fn is_direct_openai(provider: &str, base_url: &str) -> bool {
    provider.trim().eq_ignore_ascii_case("openai")
        && url::Url::parse(base_url.trim())
            .ok()
            .and_then(|url| url.host_str().map(str::to_ascii_lowercase))
            .as_deref()
            == Some(OPENAI_API_HOST)
}

fn is_reasoning_model_without_sampling_controls(model: &str) -> bool {
    let model = model.trim().to_ascii_lowercase();
    model.starts_with("gpt-5")
        || model == "o1"
        || model.starts_with("o1-")
        || model == "o3"
        || model.starts_with("o3-")
        || model == "o4"
        || model.starts_with("o4-")
}

pub fn request_timeout(provider: &str, base_url: &str, model: &str) -> Duration {
    if detect_api_kind(provider, base_url) == LlmApiKind::AnthropicMessages
        || is_reasoning_model_without_sampling_controls(model)
    {
        Duration::from_secs(60)
    } else {
        Duration::from_secs(30)
    }
}

fn normalize_anthropic_model(model: &str) -> String {
    let model = model
        .trim()
        .strip_prefix("anthropic/")
        .unwrap_or(model.trim());
    match model {
        "claude-sonnet-4" => "claude-sonnet-4-0".to_string(),
        "claude-opus-4" => "claude-opus-4-0".to_string(),
        _ => model.to_string(),
    }
}

pub fn build_chat_body(
    provider: &str,
    base_url: &str,
    model: &str,
    messages: Vec<Value>,
    max_tokens: u32,
    temperature: f64,
    stream: bool,
) -> Value {
    match detect_api_kind(provider, base_url) {
        LlmApiKind::AnthropicMessages => {
            let mut system_parts = Vec::new();
            let mut anthropic_messages = Vec::new();
            for message in messages {
                if message["role"].as_str() == Some("system") {
                    if let Some(content) = message["content"].as_str() {
                        if !content.trim().is_empty() {
                            system_parts.push(content.to_string());
                        }
                    }
                } else {
                    anthropic_messages.push(message);
                }
            }

            let mut body = json!({
                "model": normalize_anthropic_model(model),
                "messages": anthropic_messages,
                "max_tokens": max_tokens,
                "temperature": temperature.clamp(0.0, 1.0),
                "stream": stream
            });
            if !system_parts.is_empty() {
                body.as_object_mut().unwrap().insert(
                    "system".to_string(),
                    Value::String(system_parts.join("\n\n")),
                );
            }
            body
        }
        LlmApiKind::OpenAiCompatible => {
            let mut body = json!({
                "model": model,
                "messages": messages,
                "stream": stream
            });
            let object = body.as_object_mut().unwrap();
            if is_direct_openai(provider, base_url) {
                object.insert("max_completion_tokens".to_string(), json!(max_tokens));
                if !is_reasoning_model_without_sampling_controls(model) {
                    object.insert("temperature".to_string(), json!(temperature));
                }
            } else {
                object.insert("max_tokens".to_string(), json!(max_tokens));
                object.insert("temperature".to_string(), json!(temperature));
            }
            body
        }
    }
}

pub fn apply_auth_headers(
    request: RequestBuilder,
    provider: &str,
    base_url: &str,
    api_key: &str,
) -> RequestBuilder {
    let api_key = api_key.trim();
    match detect_api_kind(provider, base_url) {
        LlmApiKind::AnthropicMessages => request
            .header("x-api-key", api_key)
            .header("anthropic-version", ANTHROPIC_VERSION),
        LlmApiKind::OpenAiCompatible => {
            if super::provider_requires_api_key(provider) || !api_key.is_empty() {
                request.header("Authorization", format!("Bearer {api_key}"))
            } else {
                request
            }
        }
    }
}

pub fn response_text(kind: LlmApiKind, body: &Value) -> String {
    match kind {
        LlmApiKind::AnthropicMessages => body["content"]
            .as_array()
            .into_iter()
            .flatten()
            .filter(|block| block["type"].as_str() == Some("text"))
            .filter_map(|block| block["text"].as_str())
            .collect::<Vec<_>>()
            .join(""),
        LlmApiKind::OpenAiCompatible => {
            let message = &body["choices"][0]["message"];
            message["content"]
                .as_str()
                .filter(|content| !content.is_empty())
                .or_else(|| message["reasoning_content"].as_str())
                .unwrap_or("")
                .to_string()
        }
    }
}

pub fn response_completion_error(kind: LlmApiKind, body: &Value) -> Option<&'static str> {
    match kind {
        LlmApiKind::AnthropicMessages => match &body["stop_reason"] {
            Value::Null => None,
            Value::String(reason) => match reason.as_str() {
                "end_turn" | "stop_sequence" => None,
                "max_tokens" => Some("Anthropic LLM response was truncated"),
                "tool_use" => Some("Anthropic LLM response requires an unsupported tool call"),
                "pause_turn" => Some("Anthropic LLM response requires continuation"),
                "refusal" => Some("Anthropic LLM refused the request"),
                _ => Some("Anthropic LLM returned an unsupported completion reason"),
            },
            _ => Some("Anthropic LLM returned an unsupported completion reason"),
        },
        LlmApiKind::OpenAiCompatible => match &body["choices"][0]["finish_reason"] {
            Value::Null => None,
            Value::String(reason) => match reason.as_str() {
                "stop" => None,
                "length" => Some("LLM response was truncated"),
                "content_filter" => Some("LLM response was blocked by the content filter"),
                "tool_calls" | "function_call" => {
                    Some("LLM response requires an unsupported tool call")
                }
                _ => Some("LLM returned an unsupported completion reason"),
            },
            _ => Some("LLM returned an unsupported completion reason"),
        },
    }
}

pub fn parse_stream_event(kind: LlmApiKind, body: &Value) -> StreamEvent {
    match kind {
        LlmApiKind::AnthropicMessages => {
            if body["type"].as_str() == Some("error") {
                return StreamEvent {
                    error: Some(
                        body["error"]["message"]
                            .as_str()
                            .filter(|message| !message.trim().is_empty())
                            .unwrap_or("LLM streaming returned an error")
                            .to_string(),
                    ),
                    ..StreamEvent::default()
                };
            }
            if body["type"].as_str() == Some("message_stop") {
                return StreamEvent {
                    done: true,
                    ..StreamEvent::default()
                };
            }
            if body["type"].as_str() == Some("message_delta") {
                if let Some(error) = response_completion_error(kind, &body["delta"]) {
                    return StreamEvent {
                        error: Some(error.to_string()),
                        ..StreamEvent::default()
                    };
                }
            }
            if body["type"].as_str() == Some("content_block_delta")
                && body["delta"]["type"].as_str() == Some("text_delta")
            {
                return StreamEvent {
                    text: body["delta"]["text"].as_str().map(str::to_string),
                    ..StreamEvent::default()
                };
            }
            StreamEvent::default()
        }
        LlmApiKind::OpenAiCompatible => {
            if let Some(error) = body.get("error").filter(|error| !error.is_null()) {
                return StreamEvent {
                    error: Some(
                        error
                            .get("message")
                            .and_then(Value::as_str)
                            .or_else(|| error.as_str())
                            .filter(|message| !message.trim().is_empty())
                            .unwrap_or("LLM streaming returned an error")
                            .to_string(),
                    ),
                    ..StreamEvent::default()
                };
            }
            if let Some(error) = response_completion_error(kind, body) {
                return StreamEvent {
                    error: Some(error.to_string()),
                    ..StreamEvent::default()
                };
            }
            let delta = &body["choices"][0]["delta"];
            StreamEvent {
                text: delta["content"].as_str().map(str::to_string),
                reasoning: delta["reasoning_content"].as_str().map(str::to_string),
                ..StreamEvent::default()
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn messages() -> Vec<Value> {
        vec![
            json!({"role": "system", "content": "Be concise."}),
            json!({"role": "user", "content": "Hello"}),
        ]
    }

    #[test]
    fn completion_checks_reject_unknown_and_malformed_reasons_without_echoing_them() {
        for reason in [
            json!("synthetic-private-reason"),
            json!(1),
            json!(true),
            json!({}),
        ] {
            for (kind, body) in [
                (
                    LlmApiKind::AnthropicMessages,
                    json!({"stop_reason": reason}),
                ),
                (
                    LlmApiKind::OpenAiCompatible,
                    json!({"choices": [{"finish_reason": reason}]}),
                ),
            ] {
                let error = response_completion_error(kind, &body).unwrap();
                assert!(!error.contains("synthetic-private-reason"));
                assert!(error.contains("unsupported completion reason"));
            }
        }
    }

    #[test]
    fn compatible_completion_check_preserves_normal_and_absent_reasons() {
        for body in [
            json!({}),
            json!({"choices": [{"finish_reason": null}]}),
            json!({"choices": [{"finish_reason": "stop"}]}),
        ] {
            assert!(response_completion_error(LlmApiKind::OpenAiCompatible, &body).is_none());
            let event = parse_stream_event(LlmApiKind::OpenAiCompatible, &body);
            assert!(event.error.is_none());
        }
        for reason in ["length", "content_filter"] {
            let event = parse_stream_event(
                LlmApiKind::OpenAiCompatible,
                &json!({"choices": [{
                    "finish_reason": reason,
                    "delta": {"content": "Must not deliver", "reasoning_content": "Neither"}
                }]}),
            );
            assert!(event.error.is_some());
            assert!(event.text.is_none());
            assert!(event.reasoning.is_none());
        }
    }

    #[test]
    fn anthropic_completion_check_preserves_normal_and_missing_stop_reasons() {
        for body in [
            json!({}),
            json!({"stop_reason": null}),
            json!({"stop_reason": "end_turn"}),
            json!({"stop_reason": "stop_sequence"}),
        ] {
            assert!(response_completion_error(LlmApiKind::AnthropicMessages, &body).is_none());
        }
        assert_eq!(
            response_completion_error(
                LlmApiKind::AnthropicMessages,
                &json!({"stop_reason": "max_tokens"})
            ),
            Some("Anthropic LLM response was truncated")
        );
        assert!(response_completion_error(
            LlmApiKind::OpenAiCompatible,
            &json!({"stop_reason": "max_tokens"})
        )
        .is_none());
    }

    #[test]
    fn anthropic_error_event_without_message_still_reports_failure() {
        for error in [
            json!({"type": "overloaded_error"}),
            json!({"message": " "}),
            Value::Null,
        ] {
            let event = parse_stream_event(
                LlmApiKind::AnthropicMessages,
                &json!({"type": "error", "error": error}),
            );
            assert_eq!(
                event.error.as_deref(),
                Some("LLM streaming returned an error")
            );
            assert!(event.text.is_none());
            assert!(!event.done);
        }
        let event = parse_stream_event(
            LlmApiKind::AnthropicMessages,
            &json!({"type": "error", "error": {"message": "Provider unavailable"}}),
        );
        assert_eq!(event.error.as_deref(), Some("Provider unavailable"));
    }

    #[test]
    fn compatible_stream_error_takes_precedence_over_content() {
        for (error, expected) in [
            (
                json!({"message": "Provider unavailable"}),
                "Provider unavailable",
            ),
            (json!("Provider unavailable"), "Provider unavailable"),
            (json!({"code": "failed"}), "LLM streaming returned an error"),
        ] {
            let event = parse_stream_event(
                LlmApiKind::OpenAiCompatible,
                &json!({
                    "error": error,
                    "choices": [{"delta": {"content": "Must not deliver"}}]
                }),
            );
            assert_eq!(event.error.as_deref(), Some(expected));
            assert!(event.text.is_none());
            assert!(event.reasoning.is_none());
        }
        let event = parse_stream_event(
            LlmApiKind::OpenAiCompatible,
            &json!({"error": null, "choices": [{"delta": {"content": "Normal"}}]}),
        );
        assert_eq!(event.text.as_deref(), Some("Normal"));
        assert!(event.error.is_none());
    }

    #[test]
    fn native_anthropic_uses_messages_endpoint_without_double_appending() {
        for base_url in [
            "https://api.anthropic.com",
            "https://api.anthropic.com/v1",
            "https://api.anthropic.com/v1/messages",
        ] {
            assert_eq!(
                chat_endpoint("claude", base_url).unwrap(),
                "https://api.anthropic.com/v1/messages"
            );
        }
        assert_eq!(
            models_endpoint("claude", "https://api.anthropic.com/v1/messages").unwrap(),
            "https://api.anthropic.com/v1/models"
        );
    }

    #[test]
    fn claude_on_openrouter_stays_openai_compatible() {
        assert_eq!(
            detect_api_kind("claude", "https://openrouter.ai/api/v1"),
            LlmApiKind::OpenAiCompatible
        );
        assert_eq!(
            chat_endpoint("claude", "https://openrouter.ai/api/v1").unwrap(),
            "https://openrouter.ai/api/v1/chat/completions"
        );
    }

    #[test]
    fn native_anthropic_body_moves_system_prompt_and_normalizes_old_default_model() {
        let body = build_chat_body(
            "claude",
            "https://api.anthropic.com/v1/messages",
            "anthropic/claude-sonnet-4",
            messages(),
            256,
            0.3,
            true,
        );

        assert_eq!(body["model"], "claude-sonnet-4-0");
        assert_eq!(body["system"], "Be concise.");
        assert_eq!(body["messages"].as_array().unwrap().len(), 1);
        assert_eq!(body["max_tokens"], 256);
        assert!(body.get("max_completion_tokens").is_none());
    }

    #[test]
    fn direct_openai_gpt5_uses_compatible_token_field_and_omits_temperature() {
        let body = build_chat_body(
            "openai",
            "https://api.openai.com/v1",
            "gpt-5",
            messages(),
            4096,
            0.3,
            false,
        );

        assert_eq!(body["max_completion_tokens"], 4096);
        assert!(body.get("max_tokens").is_none());
        assert!(body.get("temperature").is_none());
    }

    #[test]
    fn openai_compatible_proxies_keep_legacy_fields_for_compatibility() {
        let body = build_chat_body(
            "openrouter",
            "https://openrouter.ai/api/v1",
            "openai/gpt-5",
            messages(),
            4096,
            0.3,
            false,
        );

        assert_eq!(body["max_tokens"], 4096);
        assert_eq!(body["temperature"], 0.3);
        assert!(body.get("max_completion_tokens").is_none());
    }

    #[test]
    fn slow_reasoning_apis_get_a_longer_timeout_without_a_new_setting() {
        assert_eq!(
            request_timeout("openai", "https://api.openai.com/v1", "gpt-5"),
            Duration::from_secs(60)
        );
        assert_eq!(
            request_timeout(
                "claude",
                "https://api.anthropic.com/v1",
                "claude-sonnet-4-0"
            ),
            Duration::from_secs(60)
        );
        assert_eq!(
            request_timeout(
                "openrouter",
                "https://openrouter.ai/api/v1",
                "gemini-2.5-flash"
            ),
            Duration::from_secs(30)
        );
    }

    #[test]
    fn auth_headers_match_native_anthropic_and_openai_protocols() {
        let anthropic = apply_auth_headers(
            reqwest::Client::new().post("https://api.anthropic.com/v1/messages"),
            "claude",
            "https://api.anthropic.com/v1",
            "anthropic-key",
        )
        .build()
        .unwrap();
        assert_eq!(anthropic.headers()["x-api-key"], "anthropic-key");
        assert_eq!(anthropic.headers()["anthropic-version"], ANTHROPIC_VERSION);
        assert!(anthropic.headers().get("Authorization").is_none());

        let openai = apply_auth_headers(
            reqwest::Client::new().post("https://api.openai.com/v1/chat/completions"),
            "openai",
            "https://api.openai.com/v1",
            "openai-key",
        )
        .build()
        .unwrap();
        assert_eq!(openai.headers()["Authorization"], "Bearer openai-key");
    }

    #[test]
    fn response_parsers_support_anthropic_json_and_streaming_events() {
        let response = json!({
            "content": [
                {"type": "text", "text": "Hello"},
                {"type": "text", "text": " world"}
            ]
        });
        assert_eq!(
            response_text(LlmApiKind::AnthropicMessages, &response),
            "Hello world"
        );

        let event = parse_stream_event(
            LlmApiKind::AnthropicMessages,
            &json!({
                "type": "content_block_delta",
                "delta": {"type": "text_delta", "text": "Hello"}
            }),
        );
        assert_eq!(event.text.as_deref(), Some("Hello"));
        assert!(!event.done);
    }
}
