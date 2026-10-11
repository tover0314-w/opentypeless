use async_trait::async_trait;
use futures_util::StreamExt;
use reqwest::Client;

use crate::error::{managed_cloud_error, AppError};
use crate::with_desktop_client_version;

use super::{prompt, ChunkCallback, LlmConfig, LlmProvider, PolishRequest, PolishResponse};

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
            client: Client::new(),
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

fn forbidden_error_message(value: &serde_json::Value) -> Option<String> {
    value
        .get("error")
        .and_then(|v| v.as_str())
        .or_else(|| value.get("message").and_then(|v| v.as_str()))
        .or_else(|| {
            value
                .get("error")
                .and_then(|v| v.get("message"))
                .and_then(|v| v.as_str())
        })
        .map(String::from)
}

fn quota_message_from_value(value: &serde_json::Value) -> Option<String> {
    for field in ["code", "error_code", "type"] {
        if value
            .get(field)
            .and_then(|v| v.as_str())
            .is_some_and(contains_quota_marker)
        {
            return Some(
                forbidden_error_message(value)
                    .unwrap_or_else(|| "Cloud LLM quota exceeded".to_string()),
            );
        }
    }

    for field in ["error", "message"] {
        if let Some(item) = value.get(field) {
            if let Some(message) = item.as_str() {
                if contains_quota_marker(message) {
                    return Some(message.to_string());
                }
            } else if let Some(message) = quota_message_from_value(item) {
                return Some(message);
            }
        }
    }

    None
}

fn cloud_llm_forbidden_error(body: &str) -> AppError {
    let parsed = serde_json::from_str::<serde_json::Value>(body).ok();

    if let Some(value) = parsed.as_ref() {
        if let Some(message) = quota_message_from_value(value) {
            return AppError::LlmQuota(message);
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
        if config.api_key.is_empty() {
            return Err(AppError::Auth(
                "Cloud LLM: session token is missing. Please sign in first.".to_string(),
            ));
        }

        let has_selected_text = req
            .selected_text
            .as_ref()
            .is_some_and(|s| !s.trim().is_empty());

        let system_prompt = prompt::build_context_system_prompt(prompt::ContextPromptOptions {
            context: &req.context,
            dictionary: &req.dictionary,
            correction_rules: &req.correction_rules,
            polish_style: &req.polish_style,
            personal_style_prompt: "",
            mapped_scene_prompt: &req.mapped_scene_prompt,
            active_scene_prompt: &req.active_scene_prompt,
            polish_custom_prompt: &req.polish_custom_prompt,
            translate_enabled: req.translate_enabled,
            target_lang: &req.target_lang,
            has_selected_text,
            voice_intent: Some(&req.voice_intent),
        });

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
            "translateEnabled": req.translate_enabled,
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
                        let text = resp.text().await.unwrap_or_default();
                        if let Some(error) = managed_cloud_error(status.as_u16(), &text) {
                            return Err(error);
                        }
                        return Err(AppError::Api {
                            status: status.as_u16(),
                            body: text,
                        });
                    } else if status.as_u16() == 403 {
                        let text = resp.text().await.unwrap_or_default();
                        return Err(cloud_llm_forbidden_error(&text));
                    } else if status.as_u16() >= 500 && attempt < 2 {
                        let body_text = resp.text().await.unwrap_or_default();
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
                        let text = resp.text().await.unwrap_or_default();
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
            let mut buffer = String::new();

            while let Some(chunk) = stream.next().await {
                let chunk = chunk?;
                buffer.push_str(&String::from_utf8_lossy(&chunk));

                while let Some(line_end) = buffer.find('\n') {
                    let line = buffer[..line_end].trim().to_string();
                    buffer = buffer[line_end + 1..].to_string();

                    if let Some(data) = line.strip_prefix("data: ") {
                        if data == "[DONE]" {
                            break;
                        }
                        if let Ok(v) = serde_json::from_str::<serde_json::Value>(data) {
                            if let Some(content) = v["choices"][0]["delta"]["content"].as_str() {
                                if !content.is_empty() {
                                    full_text.push_str(content);
                                    callback(content);
                                }
                            }
                        }
                    }
                }
            }

            Ok(PolishResponse {
                polished_text: full_text,
            })
        } else {
            let v: serde_json::Value = response.json().await?;
            let text = v["text"]
                .as_str()
                .or_else(|| v["choices"][0]["message"]["content"].as_str())
                .unwrap_or("")
                .to_string();

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
