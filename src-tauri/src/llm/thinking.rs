//! Explicit provider + model + existing route allow-list, shared with the settings UI.
//! Default is deliberately a no-op, including existing provider-specific overrides.
use serde::{Deserialize, Serialize};
use serde_json::{json, Value};
use std::sync::OnceLock;

#[derive(Debug, Clone, Copy, Default, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum ThinkingStrength {
    #[default]
    Default,
    Off,
    Minimal,
    Low,
    Medium,
    High,
    Xhigh,
    Max,
    #[serde(rename = "budget_1024")]
    Budget1024,
    #[serde(rename = "budget_2048")]
    Budget2048,
}

#[derive(Debug, Deserialize)]
struct Registry {
    entries: Vec<Capability>,
}

#[derive(Debug, Deserialize)]
pub struct Capability {
    pub id: String,
    providers: Vec<String>,
    host: String,
    paths: Vec<String>,
    models: Vec<String>,
    adapter: Adapter,
    pub options: Vec<ThinkingStrength>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "snake_case")]
enum Adapter {
    Deepseek,
    Openai,
    Gemini,
    AnthropicManual,
    AnthropicAdaptive,
}

fn registry() -> &'static Registry {
    static REGISTRY: OnceLock<Registry> = OnceLock::new();
    REGISTRY.get_or_init(|| {
        serde_json::from_str(include_str!("../../../src/lib/thinking-capabilities.json"))
            .expect("bundled thinking capability registry must be valid")
    })
}

pub fn capability(provider: &str, base_url: &str, model: &str) -> Option<&'static Capability> {
    let url = url::Url::parse(base_url.trim()).ok()?;
    if url.scheme() != "https"
        || url.port_or_known_default() != Some(443)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
    {
        return None;
    }
    let provider = provider.trim().to_ascii_lowercase();
    let model = model.trim();
    // The existing native Messages route accepts this namespace; do not strip it elsewhere.
    let model = if matches!(provider.as_str(), "claude" | "anthropic") {
        model.strip_prefix("anthropic/").unwrap_or(model)
    } else {
        model
    };
    let path = url.path().trim_end_matches('/');
    registry().entries.iter().find(|entry| {
        entry.providers.contains(&provider)
            && url.host_str() == Some(entry.host.as_str())
            && entry.paths.iter().any(|allowed| allowed == path)
            && entry.models.iter().any(|allowed| allowed == model)
    })
}

pub const UNAVAILABLE_CHOICE_MESSAGE: &str = "Saved thinking strength is unavailable for this provider, model or API route. Choose Default or a supported value in LLM settings.";

/// Give dictation users a specific recovery path without changing pipeline fallback policy.
pub fn unavailable_choice_user_error(
    error: &crate::error::AppError,
) -> Option<crate::error::UserError> {
    match error {
        crate::error::AppError::Config(message) if message == UNAVAILABLE_CHOICE_MESSAGE => {
            Some(crate::error::UserError {
                code: "llm_thinking_unavailable".to_string(),
                details: Some(message.clone()),
                retry_count: 0,
            })
        }
        _ => None,
    }
}

pub fn validate(
    provider: &str,
    base_url: &str,
    model: &str,
    strength: ThinkingStrength,
) -> Result<(), String> {
    if strength == ThinkingStrength::Default
        || capability(provider, base_url, model)
            .is_some_and(|entry| entry.options.contains(&strength))
    {
        return Ok(());
    }
    Err(UNAVAILABLE_CHOICE_MESSAGE.to_string())
}

/// Apply only an explicitly supported choice. Unsupported saved choices fail before HTTP;
/// they are retained and visibly marked in settings rather than silently ignored.
pub fn apply(
    body: &mut Value,
    provider: &str,
    base_url: &str,
    model: &str,
    strength: ThinkingStrength,
) -> Result<(), String> {
    validate(provider, base_url, model, strength)?;
    if strength == ThinkingStrength::Default {
        return Ok(());
    }
    let entry = capability(provider, base_url, model).expect("validated non-default capability");
    let object = body
        .as_object_mut()
        .ok_or("LLM request must be an object")?;
    let effort = if strength == ThinkingStrength::Off {
        json!("none")
    } else {
        serde_json::to_value(strength).map_err(|error| error.to_string())?
    };
    match entry.adapter {
        Adapter::Deepseek => {
            object.insert("thinking".into(), json!({"type": if strength == ThinkingStrength::Off { "disabled" } else { "enabled" }}));
            if strength != ThinkingStrength::Off {
                object.insert("reasoning_effort".into(), effort);
            }
        }
        Adapter::Openai => {
            object.insert("reasoning_effort".into(), effort);
            // Explicit reasoning choices must not inherit an incompatible sampling control.
            object.remove("temperature");
        }
        Adapter::Gemini => {
            object.insert("reasoning_effort".into(), effort);
        }
        Adapter::AnthropicManual => {
            if strength == ThinkingStrength::Off {
                object.insert("thinking".into(), json!({"type": "disabled"}));
            } else {
                let budget = match strength {
                    ThinkingStrength::Budget1024 => 1024,
                    ThinkingStrength::Budget2048 => 2048,
                    _ => unreachable!("registry limits manual choices to budgets"),
                };
                if object
                    .get("max_tokens")
                    .and_then(Value::as_u64)
                    .unwrap_or(0)
                    <= budget
                {
                    return Err(format!("Thinking budget {budget} must be less than max_tokens; choose Default or Off for this request."));
                }
                object.insert(
                    "thinking".into(),
                    json!({"type": "enabled", "budget_tokens": budget}),
                );
                object.remove("temperature");
            }
        }
        Adapter::AnthropicAdaptive => {
            object.remove("temperature");
            if strength == ThinkingStrength::Off {
                object.insert("thinking".into(), json!({"type": "disabled"}));
            } else {
                object.insert("thinking".into(), json!({"type": "adaptive"}));
                object.insert("output_config".into(), json!({"effort": effort}));
                object.remove("temperature");
            }
        }
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::llm::protocol::build_chat_body;

    #[test]
    fn unsupported_choice_has_a_specific_runtime_recovery_code() {
        let reason = validate(
            "openai",
            "https://api.openai.com/v1",
            "gpt-5",
            ThinkingStrength::Off,
        )
        .unwrap_err();
        let error = crate::error::AppError::Config(reason.clone());
        let visible = unavailable_choice_user_error(&error).unwrap();
        assert_eq!(visible.code, "llm_thinking_unavailable");
        assert_eq!(visible.details.as_deref(), Some(reason.as_str()));
        assert!(
            unavailable_choice_user_error(&crate::error::AppError::Config("unrelated".into()))
                .is_none()
        );
    }

    #[test]
    fn default_is_a_byte_equivalent_noop_even_for_existing_overrides() {
        let mut body = json!({"thinking":{"type":"enabled"},"temperature":1.0,"top_p":0.95});
        let before = serde_json::to_vec(&body).unwrap();
        apply(
            &mut body,
            "zhipu",
            "https://open.bigmodel.cn/api/paas/v4",
            "glm-4.7",
            ThinkingStrength::Default,
        )
        .unwrap();
        assert_eq!(serde_json::to_vec(&body).unwrap(), before);
    }

    #[test]
    fn every_allowlisted_choice_has_a_stream_and_nonstream_serialization() {
        for entry in &registry().entries {
            for model in &entry.models {
                for strength in &entry.options {
                    for stream in [false, true] {
                        let base = format!("https://{}{}", entry.host, entry.paths[0]);
                        let mut body = build_chat_body(
                            &entry.providers[0],
                            &base,
                            model,
                            vec![],
                            4096,
                            0.3,
                            stream,
                        );
                        let before = body.clone();
                        apply(&mut body, &entry.providers[0], &base, model, *strength).unwrap();
                        let encoded: Value =
                            serde_json::from_slice(&serde_json::to_vec(&body).unwrap()).unwrap();
                        assert_eq!(encoded["stream"], stream);
                        if *strength == ThinkingStrength::Default {
                            assert_eq!(encoded, before);
                        }
                        if *strength == ThinkingStrength::Off {
                            assert_ne!(encoded["reasoning_effort"], "low");
                        }
                    }
                }
            }
        }
    }

    #[test]
    fn unknown_model_route_or_provider_never_gets_parameters() {
        for (p, url, model) in [
            ("openai", "https://api.openai.com/v1", "gpt-5-future"),
            ("openai", "https://proxy.example/v1", "gpt-5.1"),
            ("openai", "http://api.openai.com/v1", "gpt-5.1"),
            ("openai", "https://api.openai.com:8443/v1", "gpt-5.1"),
            ("custom", "https://api.openai.com/v1", "gpt-5.1"),
            ("openai", "https://api.openai.com/v1/responses", "gpt-5.1"),
            (
                "deepseek",
                "https://api.deepseek.com/anthropic",
                "deepseek-flash",
            ),
            (
                "gemini",
                "https://generativelanguage.googleapis.com/v1beta",
                "gemini-2.5-flash",
            ),
            ("openai", "https://api.openai.com.evil.test/v1", "gpt-5.1"),
            ("openai", "https://api.openai.com/v1?proxy=1", "gpt-5.1"),
            ("cloud", "https://api.openai.com/v1", "gpt-5.1"),
        ] {
            assert!(capability(p, url, model).is_none());
            let mut body = json!({"neutral":true});
            let before = body.clone();
            assert!(apply(&mut body, p, url, model, ThinkingStrength::Low).is_err());
            assert_eq!(body, before);
            apply(&mut body, p, url, model, ThinkingStrength::Default).unwrap();
            assert_eq!(body, before);
        }
    }

    #[test]
    fn off_is_available_only_for_documented_disable_support() {
        assert!(validate(
            "openai",
            "https://api.openai.com/v1",
            "gpt-5",
            ThinkingStrength::Off
        )
        .is_err());
        assert!(validate(
            "gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-pro",
            ThinkingStrength::Off
        )
        .is_err());
        assert!(validate(
            "gemini",
            "https://generativelanguage.googleapis.com/v1beta/openai",
            "gemini-2.5-flash",
            ThinkingStrength::Off
        )
        .is_ok());
    }

    #[test]
    fn manual_budget_preserves_output_limit_and_checks_inequality() {
        let mut body = json!({"max_tokens":1024,"temperature":0.3});
        assert!(apply(
            &mut body,
            "claude",
            "https://api.anthropic.com/v1",
            "claude-sonnet-4-0",
            ThinkingStrength::Budget1024
        )
        .is_err());
        body["max_tokens"] = json!(4096);
        apply(
            &mut body,
            "claude",
            "https://api.anthropic.com/v1",
            "claude-sonnet-4-0",
            ThinkingStrength::Budget2048,
        )
        .unwrap();
        assert_eq!(body["max_tokens"], 4096);
        assert_eq!(
            body["thinking"],
            json!({"type":"enabled","budget_tokens":2048})
        );
        assert!(body.get("temperature").is_none());
    }
}
