use std::sync::OnceLock;

use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize)]
#[serde(rename_all = "snake_case")]
pub enum ModelCapability {
    Certified,
    BestEffort,
    Unknown,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CapabilityFixture {
    prompt_version: String,
    models: Vec<CertifiedModel>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
struct CertifiedModel {
    provider: String,
    base_url: String,
    model: String,
    capability: String,
}

fn fixture() -> &'static CapabilityFixture {
    static FIXTURE: OnceLock<CapabilityFixture> = OnceLock::new();
    FIXTURE.get_or_init(|| {
        serde_json::from_str(include_str!("../../tests/fixtures/certified_models.json"))
            .expect("certified model fixture must be valid")
    })
}

pub fn model_capability(
    provider: &str,
    base_url: &str,
    model: &str,
    prompt_version: &str,
) -> ModelCapability {
    let provider = provider.trim().to_ascii_lowercase();
    let Some(base_url) = normalize_base_url(base_url) else {
        return ModelCapability::Unknown;
    };
    let model = model.trim();
    let prompt_version = prompt_version.trim();
    if provider.is_empty() || model.is_empty() || prompt_version.is_empty() {
        return ModelCapability::Unknown;
    }

    let fixture = fixture();
    for entry in &fixture.models {
        if entry.capability != "certified"
            || entry.provider.trim().to_ascii_lowercase() != provider
            || normalize_base_url(&entry.base_url).as_deref() != Some(base_url.as_str())
        {
            continue;
        }

        if entry.model == model && fixture.prompt_version == prompt_version {
            return ModelCapability::Certified;
        }
        return ModelCapability::BestEffort;
    }

    ModelCapability::Unknown
}

fn normalize_base_url(value: &str) -> Option<String> {
    let mut parsed = url::Url::parse(value.trim()).ok()?;
    if !matches!(parsed.scheme(), "http" | "https")
        || parsed.host_str().is_none()
        || !parsed.username().is_empty()
        || parsed.password().is_some()
        || parsed.query().is_some()
        || parsed.fragment().is_some()
    {
        return None;
    }
    parsed.set_query(None);
    parsed.set_fragment(None);
    Some(parsed.as_str().trim_end_matches('/').to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::llm::prompt::CONTEXT_PROMPT_VERSION;

    const CERTIFIED_BASE: &str = "https://openrouter.ai/api/v1";
    const CERTIFIED_MODEL: &str = "google/gemini-2.5-flash";

    #[test]
    fn model_capabilities_requires_exact_certified_tuple() {
        assert_eq!(
            model_capability(
                "openrouter",
                CERTIFIED_BASE,
                CERTIFIED_MODEL,
                CONTEXT_PROMPT_VERSION
            ),
            ModelCapability::Certified
        );
    }

    #[test]
    fn model_capabilities_marks_same_provider_surface_as_best_effort() {
        assert_eq!(
            model_capability(
                "openrouter",
                CERTIFIED_BASE,
                "another-model",
                CONTEXT_PROMPT_VERSION
            ),
            ModelCapability::BestEffort
        );
        assert_eq!(
            model_capability("openrouter", CERTIFIED_BASE, CERTIFIED_MODEL, "context-v2"),
            ModelCapability::BestEffort
        );
    }

    #[test]
    fn model_capabilities_treats_custom_blank_and_unlisted_as_unknown() {
        assert_eq!(
            model_capability(
                "openrouter",
                "https://custom.example/v1",
                CERTIFIED_MODEL,
                CONTEXT_PROMPT_VERSION,
            ),
            ModelCapability::Unknown
        );
        assert_eq!(
            model_capability("openrouter", CERTIFIED_BASE, "", CONTEXT_PROMPT_VERSION),
            ModelCapability::Unknown
        );
        assert_eq!(
            model_capability(
                "openai",
                "https://api.openai.com/v1",
                "unlisted/model",
                CONTEXT_PROMPT_VERSION,
            ),
            ModelCapability::Unknown
        );
    }
}
