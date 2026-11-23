use serde::{Deserialize, Serialize};

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VoiceMode {
    Dictate,
    Ask,
    Translate,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VoiceIntentKind {
    DictateInsert,
    DraftInsert,
    RewriteSelection,
    TranslateInsert,
    TranslateSelection,
    AskSelection,
    OpenQuestion,
    Search,
}

impl VoiceIntentKind {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::DictateInsert => "dictate_insert",
            Self::DraftInsert => "draft_insert",
            Self::RewriteSelection => "rewrite_selection",
            Self::TranslateInsert => "translate_insert",
            Self::TranslateSelection => "translate_selection",
            Self::AskSelection => "ask_selection",
            Self::OpenQuestion => "open_question",
            Self::Search => "search",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VoiceOutputPlacement {
    InsertAtCursor,
    ReplaceSelection,
    PopupAnswer,
    OpenUrl,
}

impl VoiceOutputPlacement {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::InsertAtCursor => "insert_at_cursor",
            Self::ReplaceSelection => "replace_selection",
            Self::PopupAnswer => "popup_answer",
            Self::OpenUrl => "open_url",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum SearchProvider {
    Google,
    #[serde(rename = "youtube")]
    YouTube,
    Amazon,
    #[serde(rename = "github")]
    GitHub,
}

impl SearchProvider {
    pub fn display_name(self) -> &'static str {
        match self {
            Self::Google => "Google",
            Self::YouTube => "YouTube",
            Self::Amazon => "Amazon",
            Self::GitHub => "GitHub",
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum CommandLocale {
    En,
    ZhHans,
    ZhHant,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SpeechLanguageMode<'a> {
    Explicit(&'a str),
    Automatic,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum RouteFallbackReason {
    UnsupportedLocale,
    MissingPayload,
    Negated,
    QuotedOrReported,
    CodeOrIdentifier,
    Ambiguous,
    FeatureDisabled,
}

#[derive(Clone, Debug, PartialEq, Serialize, Deserialize)]
pub struct VoiceIntent {
    pub kind: VoiceIntentKind,
    pub placement: VoiceOutputPlacement,
    pub confidence: f32,
    pub search_provider: Option<SearchProvider>,
    pub payload: Option<String>,
    pub grammar_locale: Option<CommandLocale>,
    pub fallback_reason: Option<RouteFallbackReason>,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum VoiceConfidenceBand {
    Exact,
    Guarded,
    Fallback,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct VoiceIntentMetadata {
    pub kind: VoiceIntentKind,
    pub placement: VoiceOutputPlacement,
    pub grammar_locale: Option<CommandLocale>,
    pub confidence_band: VoiceConfidenceBand,
}

impl From<&VoiceIntent> for VoiceIntentMetadata {
    fn from(intent: &VoiceIntent) -> Self {
        let confidence_band = if intent.fallback_reason.is_some() {
            VoiceConfidenceBand::Fallback
        } else if intent.confidence >= 0.99 {
            VoiceConfidenceBand::Exact
        } else {
            VoiceConfidenceBand::Guarded
        };

        Self {
            kind: intent.kind,
            placement: intent.placement,
            grammar_locale: intent.grammar_locale,
            confidence_band,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VoiceIntentError {
    InvalidPlacement,
    MissingSearchProvider,
    UnexpectedSearchProvider,
    MissingPayload,
}

impl std::fmt::Display for VoiceIntentError {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str(match self {
            Self::InvalidPlacement => "voice intent kind does not allow this output placement",
            Self::MissingSearchProvider => "search intent requires a provider",
            Self::UnexpectedSearchProvider => "non-search intent cannot carry a search provider",
            Self::MissingPayload => "voice intent requires a non-empty payload",
        })
    }
}

impl std::error::Error for VoiceIntentError {}

impl VoiceIntent {
    pub fn translation_target<'target>(&'target self, configured: &'target str) -> &'target str {
        if self.kind == VoiceIntentKind::TranslateSelection {
            if let Some(target) = self.payload.as_deref().filter(|target| {
                matches!(
                    *target,
                    "en" | "zh"
                        | "ja"
                        | "ko"
                        | "fr"
                        | "de"
                        | "es"
                        | "pt"
                        | "ru"
                        | "ar"
                        | "hi"
                        | "th"
                        | "vi"
                        | "it"
                        | "nl"
                        | "tr"
                        | "pl"
                        | "uk"
                        | "id"
                        | "ms"
                )
            }) {
                return target;
            }
        }
        configured
    }

    #[allow(clippy::too_many_arguments)]
    pub fn from_parts(
        kind: VoiceIntentKind,
        placement: VoiceOutputPlacement,
        confidence: f32,
        search_provider: Option<SearchProvider>,
        payload: Option<String>,
        grammar_locale: Option<CommandLocale>,
        fallback_reason: Option<RouteFallbackReason>,
    ) -> Result<Self, VoiceIntentError> {
        let required_placement = match kind {
            VoiceIntentKind::DictateInsert
            | VoiceIntentKind::DraftInsert
            | VoiceIntentKind::TranslateInsert => VoiceOutputPlacement::InsertAtCursor,
            VoiceIntentKind::RewriteSelection | VoiceIntentKind::TranslateSelection => {
                VoiceOutputPlacement::ReplaceSelection
            }
            VoiceIntentKind::AskSelection | VoiceIntentKind::OpenQuestion => {
                VoiceOutputPlacement::PopupAnswer
            }
            VoiceIntentKind::Search => VoiceOutputPlacement::OpenUrl,
        };
        if placement != required_placement {
            return Err(VoiceIntentError::InvalidPlacement);
        }

        match (kind, search_provider) {
            (VoiceIntentKind::Search, None) => return Err(VoiceIntentError::MissingSearchProvider),
            (VoiceIntentKind::Search, Some(_)) | (_, None) => {}
            (_, Some(_)) => return Err(VoiceIntentError::UnexpectedSearchProvider),
        }

        let payload = payload.map(|value| value.trim().to_string());
        if matches!(kind, VoiceIntentKind::DraftInsert | VoiceIntentKind::Search)
            && payload.as_deref().is_none_or(str::is_empty)
        {
            return Err(VoiceIntentError::MissingPayload);
        }

        let confidence = if confidence.is_finite() {
            confidence.clamp(0.0, 1.0)
        } else {
            0.0
        };

        Ok(Self {
            kind,
            placement,
            confidence,
            search_provider,
            payload,
            grammar_locale,
            fallback_reason,
        })
    }
}

const fn default_true() -> bool {
    true
}

#[derive(Clone, Copy, Debug, PartialEq, Eq, Serialize, Deserialize)]
pub struct VoiceRoutingFlags {
    #[serde(default = "default_true")]
    pub draft_insert: bool,
    #[serde(default = "default_true")]
    pub rewrite_selection: bool,
    #[serde(default = "default_true")]
    pub translate_selection: bool,
    #[serde(default = "default_true")]
    pub search: bool,
}

impl Default for VoiceRoutingFlags {
    fn default() -> Self {
        Self {
            draft_insert: true,
            rewrite_selection: true,
            translate_selection: true,
            search: true,
        }
    }
}

pub struct VoiceRouteRequest<'a> {
    pub mode: VoiceMode,
    pub utterance: &'a str,
    pub has_selected_text: bool,
    pub speech_language: SpeechLanguageMode<'a>,
    pub flags: VoiceRoutingFlags,
}

#[cfg(test)]
mod tests {
    use std::collections::HashSet;

    use serde::Deserialize;

    use super::*;

    #[derive(Debug, Deserialize)]
    #[serde(rename_all = "camelCase")]
    struct FixtureCase {
        id: String,
        mode: VoiceMode,
        locale: String,
        utterance: String,
        has_selection: bool,
        flags: VoiceRoutingFlags,
        expected_kind: VoiceIntentKind,
        expected_placement: VoiceOutputPlacement,
        expected_provider: Option<SearchProvider>,
        expected_payload: Option<String>,
        destructive_blocker: bool,
    }

    fn assert_fixture_contract(source: &str) {
        let cases: Vec<FixtureCase> = serde_json::from_str(source).unwrap();
        assert!(cases.len() >= 250, "fixture has only {} cases", cases.len());
        assert!(
            cases.iter().filter(|case| case.destructive_blocker).count() >= 100,
            "fixture needs at least 100 destructive blockers"
        );

        let mut ids = HashSet::new();
        for case in &cases {
            assert!(
                ids.insert(case.id.as_str()),
                "duplicate fixture id: {}",
                case.id
            );
            assert!(!case.locale.trim().is_empty());
            assert!(!case.utterance.trim().is_empty());
            let _ = (
                case.mode,
                case.has_selection,
                case.flags,
                case.expected_kind,
                case.expected_placement,
                case.expected_provider,
                case.expected_payload.as_deref(),
            );
        }
    }

    #[test]
    fn voice_intent_types_fixture_contract_has_release_scale_corpora() {
        assert_fixture_contract(include_str!("../../tests/fixtures/voice_intent_en.json"));
        assert_fixture_contract(include_str!(
            "../../tests/fixtures/voice_intent_zh_hans.json"
        ));
        assert_fixture_contract(include_str!(
            "../../tests/fixtures/voice_intent_zh_hant.json"
        ));
    }

    #[test]
    fn voice_intent_types_use_stable_wire_values() {
        assert_eq!(serde_json::to_value(VoiceMode::Dictate).unwrap(), "dictate");
        assert_eq!(
            serde_json::to_value(VoiceIntentKind::RewriteSelection).unwrap(),
            "rewrite_selection"
        );
        assert_eq!(
            serde_json::to_value(VoiceOutputPlacement::ReplaceSelection).unwrap(),
            "replace_selection"
        );
        assert_eq!(
            serde_json::to_value(SearchProvider::YouTube).unwrap(),
            "youtube"
        );
        assert_eq!(
            serde_json::to_value(SearchProvider::GitHub).unwrap(),
            "github"
        );
        assert_eq!(
            serde_json::to_value(CommandLocale::ZhHant).unwrap(),
            "zh_hant"
        );
        assert_eq!(
            serde_json::to_value(RouteFallbackReason::FeatureDisabled).unwrap(),
            "feature_disabled"
        );
    }

    #[test]
    fn voice_intent_types_reject_invalid_kind_and_placement_pairs() {
        assert!(VoiceIntent::from_parts(
            VoiceIntentKind::RewriteSelection,
            VoiceOutputPlacement::PopupAnswer,
            1.0,
            None,
            None,
            Some(CommandLocale::En),
            None,
        )
        .is_err());

        assert!(VoiceIntent::from_parts(
            VoiceIntentKind::Search,
            VoiceOutputPlacement::InsertAtCursor,
            1.0,
            Some(SearchProvider::Google),
            Some("rust".to_string()),
            Some(CommandLocale::En),
            None,
        )
        .is_err());
    }

    #[test]
    fn voice_intent_types_enforce_provider_and_payload_invariants() {
        assert!(VoiceIntent::from_parts(
            VoiceIntentKind::Search,
            VoiceOutputPlacement::OpenUrl,
            1.0,
            None,
            Some("rust".to_string()),
            Some(CommandLocale::En),
            None,
        )
        .is_err());
        assert!(VoiceIntent::from_parts(
            VoiceIntentKind::Search,
            VoiceOutputPlacement::OpenUrl,
            1.0,
            Some(SearchProvider::Google),
            Some("  ".to_string()),
            Some(CommandLocale::En),
            None,
        )
        .is_err());
        assert!(VoiceIntent::from_parts(
            VoiceIntentKind::DraftInsert,
            VoiceOutputPlacement::InsertAtCursor,
            1.0,
            None,
            None,
            Some(CommandLocale::En),
            None,
        )
        .is_err());
        assert!(VoiceIntent::from_parts(
            VoiceIntentKind::OpenQuestion,
            VoiceOutputPlacement::PopupAnswer,
            1.0,
            Some(SearchProvider::GitHub),
            None,
            Some(CommandLocale::En),
            None,
        )
        .is_err());
    }

    #[test]
    fn voice_intent_types_clamp_confidence_and_serialize_safe_metadata() {
        let intent = VoiceIntent::from_parts(
            VoiceIntentKind::DictateInsert,
            VoiceOutputPlacement::InsertAtCursor,
            4.0,
            None,
            None,
            None,
            Some(RouteFallbackReason::UnsupportedLocale),
        )
        .unwrap();

        assert_eq!(intent.confidence, 1.0);
        let value = serde_json::to_value(intent).unwrap();
        assert_eq!(value["kind"], "dictate_insert");
        assert_eq!(value["placement"], "insert_at_cursor");
        assert_eq!(value["fallback_reason"], "unsupported_locale");
        for forbidden in ["utterance", "selected_text", "query", "url"] {
            assert!(value.get(forbidden).is_none());
        }
    }

    #[test]
    fn voice_intent_types_flags_default_every_independent_route_on() {
        assert_eq!(
            serde_json::from_value::<VoiceRoutingFlags>(serde_json::json!({})).unwrap(),
            VoiceRoutingFlags::default()
        );
        assert_eq!(
            serde_json::from_value::<VoiceRoutingFlags>(serde_json::json!({
                "draft_insert": false
            }))
            .unwrap(),
            VoiceRoutingFlags {
                draft_insert: false,
                rewrite_selection: true,
                translate_selection: true,
                search: true,
            }
        );
    }

    #[test]
    fn voice_intent_metadata_is_closed_banded_and_never_contains_operation_payload() {
        let exact = VoiceIntent::from_parts(
            VoiceIntentKind::DraftInsert,
            VoiceOutputPlacement::InsertAtCursor,
            1.0,
            None,
            Some("private launch details".to_string()),
            Some(CommandLocale::En),
            None,
        )
        .unwrap();
        let exact_value = serde_json::to_value(VoiceIntentMetadata::from(&exact)).unwrap();
        assert_eq!(
            exact_value,
            serde_json::json!({
                "kind": "draft_insert",
                "placement": "insert_at_cursor",
                "grammarLocale": "en",
                "confidenceBand": "exact"
            })
        );
        let serialized = exact_value.to_string();
        for forbidden in [
            "private launch details",
            "payload",
            "utterance",
            "selectedText",
            "query",
            "searchUrl",
        ] {
            assert!(!serialized.contains(forbidden));
        }

        let guarded = VoiceIntent::from_parts(
            VoiceIntentKind::OpenQuestion,
            VoiceOutputPlacement::PopupAnswer,
            0.72,
            None,
            None,
            Some(CommandLocale::ZhHans),
            None,
        )
        .unwrap();
        assert_eq!(
            VoiceIntentMetadata::from(&guarded).confidence_band,
            VoiceConfidenceBand::Guarded
        );

        let fallback = VoiceIntent::from_parts(
            VoiceIntentKind::DictateInsert,
            VoiceOutputPlacement::InsertAtCursor,
            1.0,
            None,
            None,
            None,
            Some(RouteFallbackReason::Ambiguous),
        )
        .unwrap();
        assert_eq!(
            VoiceIntentMetadata::from(&fallback).confidence_band,
            VoiceConfidenceBand::Fallback
        );
    }
}
