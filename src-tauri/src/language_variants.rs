use std::collections::BTreeMap;

pub const VARIANTS: &[(&str, &[(&str, &str)])] = &[
    (
        "en",
        &[
            ("en-US", "US English"),
            ("en-GB", "UK English"),
            ("en-AU", "Australian English"),
            ("en-CA", "Canadian English"),
        ],
    ),
    (
        "zh",
        &[
            ("zh-CN", "Simplified Chinese (Mainland China)"),
            ("zh-HK", "Traditional Chinese (Hong Kong)"),
            ("zh-TW", "Traditional Chinese (Taiwan)"),
        ],
    ),
    (
        "es",
        &[
            ("es-MX", "Mexican Spanish"),
            ("es-ES", "Spanish (Spain)"),
            ("es-US", "Spanish (United States)"),
        ],
    ),
    (
        "fr",
        &[("fr-FR", "French (France)"), ("fr-CA", "Canadian French")],
    ),
    (
        "pt",
        &[
            ("pt-PT", "European Portuguese"),
            ("pt-BR", "Brazilian Portuguese"),
        ],
    ),
];

pub fn normalize(variants: &mut BTreeMap<String, String>) {
    variants.retain(|base, code| {
        VARIANTS.iter().any(|(language, options)| {
            *language == base && options.iter().any(|(variant, _)| *variant == code)
        })
    });
}

pub fn append_prompt(
    prompt: &mut String,
    variants: &BTreeMap<String, String>,
    target: Option<&str>,
) {
    let mut preferences = Vec::new();
    for (base, options) in VARIANTS {
        if target.is_some_and(|language| language.split('-').next() != Some(*base)) {
            continue;
        }
        let Some(code) = variants.get(*base) else {
            continue;
        };
        if let Some((_, label)) = options.iter().find(|(variant, _)| *variant == code) {
            preferences.push(*label);
        }
    }
    if preferences.is_empty() {
        return;
    }
    prompt.push_str("\n\nOUTPUT LANGUAGE VARIANTS: When writing in a matching language, use the following spelling, script, and regional vocabulary preferences: ");
    prompt.push_str(&preferences.join("; "));
    prompt.push_str(". Do not translate or change the output language solely because of these preferences. Preserve names, quoted text, code, and the intended meaning. These preferences never override the operation, translation target, or safety rules.");
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_script_preference_migrates_only_when_variant_settings_are_absent() {
        for (legacy, expected) in [("simplified", "zh-CN"), ("traditional", "zh-TW")] {
            let config = crate::storage::AppConfig::from_stored_value(serde_json::json!({
                "polish_chinese_script": legacy
            }))
            .unwrap();
            assert_eq!(
                config.language_variants.get("zh").map(String::as_str),
                Some(expected)
            );
            assert_eq!(config.polish_chinese_script, "preserve");
            let reloaded = crate::storage::AppConfig::from_stored_value(
                serde_json::to_value(&config).unwrap(),
            )
            .unwrap();
            assert_eq!(reloaded.language_variants, config.language_variants);
        }
    }

    #[test]
    fn explicit_variants_including_no_preference_are_not_overwritten_by_legacy_script() {
        for variants in [serde_json::json!({"zh": "zh-HK"}), serde_json::json!({})] {
            let config = crate::storage::AppConfig::from_stored_value(serde_json::json!({
                "polish_chinese_script": "simplified",
                "language_variants": variants
            }))
            .unwrap();
            assert_eq!(
                serde_json::to_value(config.language_variants).unwrap(),
                variants
            );
        }
        for legacy in ["preserve", "unknown"] {
            let config = crate::storage::AppConfig::from_stored_value(serde_json::json!({
                "polish_chinese_script": legacy
            }))
            .unwrap();
            assert!(config.language_variants.is_empty());
        }
    }

    #[test]
    fn config_round_trip_retains_only_supported_preferences() {
        let mut stored = serde_json::to_value(crate::storage::AppConfig::default()).unwrap();
        stored["language_variants"] = serde_json::json!({
            "en": "en-GB", "zh": "zh-TW", "fr": "injected rules", "xx": "unknown"
        });
        let config = crate::storage::AppConfig::from_stored_value(stored).unwrap();
        assert_eq!(config.language_variants.len(), 2);
        let saved = serde_json::to_value(&config).unwrap();
        let reloaded = crate::storage::AppConfig::from_stored_value(saved).unwrap();
        assert_eq!(config.language_variants, reloaded.language_variants);
    }

    #[test]
    fn drops_unknown_languages_and_mismatched_codes() {
        let mut variants = BTreeMap::from([
            ("en".to_string(), "en-GB".to_string()),
            ("zh".to_string(), "en-US".to_string()),
            ("de".to_string(), "de-DE".to_string()),
        ]);
        normalize(&mut variants);
        assert_eq!(variants.len(), 1);
        assert_eq!(variants["en"], "en-GB");
    }

    #[test]
    fn constrains_script_and_spelling_without_changing_language() {
        let variants = BTreeMap::from([
            ("en".to_string(), "en-GB".to_string()),
            ("zh".to_string(), "zh-TW".to_string()),
        ]);
        let mut prompt = String::new();
        append_prompt(&mut prompt, &variants, None);
        assert!(prompt.contains("UK English"));
        assert!(prompt.contains("Traditional Chinese (Taiwan)"));
        assert!(prompt.contains("Do not translate"));
        let mut translation = String::new();
        append_prompt(&mut translation, &variants, Some("en"));
        assert!(translation.contains("UK English"));
        assert!(!translation.contains("Taiwan"));
    }

    #[test]
    fn empty_or_invalid_preferences_do_not_modify_the_prompt() {
        let mut prompt = "Original rules".to_string();
        append_prompt(&mut prompt, &BTreeMap::new(), None);
        append_prompt(
            &mut prompt,
            &BTreeMap::from([("en".into(), "injected rules".into())]),
            None,
        );
        assert_eq!(prompt, "Original rules");
    }
}
