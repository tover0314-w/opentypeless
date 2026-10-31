use crate::app_detector::types::{BrowserAccessStatus, ContextFamily};
use crate::storage::{
    self, CorrectionRule, DictionaryEntry, HistoryEntry, HistoryProviderKind,
    DEFAULT_HISTORY_MAX_ENTRIES,
};
use serde::{Deserialize, Serialize};

const MAX_HISTORY_TEXT_CHARS: usize = 1_000_000;

#[derive(Debug, Deserialize)]
pub struct BackupHistoryEntry {
    #[serde(default)]
    created_at: Option<String>,
    #[serde(default)]
    context_profile_id: Option<String>,
    #[serde(default)]
    context_label: Option<String>,
    #[serde(default)]
    context_icon_key: Option<String>,
    #[serde(default)]
    context_family: Option<ContextFamily>,
    #[serde(default)]
    browser_access_status: Option<BrowserAccessStatus>,
    #[serde(default)]
    provider_kind: Option<HistoryProviderKind>,
    #[serde(default, alias = "text")]
    raw_text: Option<String>,
    #[serde(default)]
    polished_text: Option<String>,
    #[serde(default)]
    language: Option<String>,
    #[serde(default)]
    duration_ms: Option<i64>,
    #[serde(default)]
    active_scene_id: Option<String>,
    #[serde(default)]
    active_scene_source: Option<String>,
    #[serde(default)]
    active_scene_name: Option<String>,
    #[serde(default)]
    active_scene_prompt_chars: Option<i64>,
    #[serde(default)]
    active_scene_prompt_truncated: bool,
    #[serde(default)]
    output_status: Option<String>,
    #[serde(default)]
    output_error: Option<String>,
    // Pre-context backups used app_name instead of a normalized context label.
    #[serde(default)]
    app_name: Option<String>,
    #[serde(default)]
    mode: Option<String>,
    #[serde(default)]
    answer_text: Option<String>,
}

impl BackupHistoryEntry {
    fn into_storage(self, fallback_timestamp: &str) -> Result<HistoryEntry, String> {
        let raw_text = validated_backup_string(
            self.raw_text.unwrap_or_default(),
            MAX_HISTORY_TEXT_CHARS,
            "backup_history_raw_text",
            true,
        )?;
        let polished_text = validated_backup_string(
            self.polished_text.unwrap_or_else(|| raw_text.clone()),
            MAX_HISTORY_TEXT_CHARS,
            "backup_history_polished_text",
            true,
        )?;
        if raw_text.is_empty() && polished_text.is_empty() {
            return Err("backup_history_text_empty".to_string());
        }

        let context_label = optional_backup_string(
            self.context_label.or(self.app_name),
            200,
            "backup_history_context_label",
        )?
        .unwrap_or_else(|| "General".to_string());

        Ok(HistoryEntry {
            id: 0,
            created_at: normalize_timestamp(self.created_at, fallback_timestamp)?,
            context_profile_id: optional_backup_string(
                self.context_profile_id,
                200,
                "backup_history_context_profile_id",
            )?
            .unwrap_or_else(|| "general.native".to_string()),
            context_label,
            context_icon_key: optional_backup_string(
                self.context_icon_key,
                100,
                "backup_history_context_icon_key",
            )?
            .unwrap_or_else(|| "general".to_string()),
            context_family: self.context_family.unwrap_or(ContextFamily::General),
            browser_access_status: self
                .browser_access_status
                .unwrap_or(BrowserAccessStatus::NotApplicable),
            provider_kind: self.provider_kind.unwrap_or(HistoryProviderKind::Local),
            raw_text,
            polished_text,
            language: optional_backup_string(self.language, 100, "backup_history_language")?,
            duration_ms: self.duration_ms.filter(|value| *value >= 0),
            active_scene_id: optional_backup_string(
                self.active_scene_id,
                200,
                "backup_history_scene_id",
            )?,
            active_scene_source: optional_backup_string(
                self.active_scene_source,
                100,
                "backup_history_scene_source",
            )?,
            active_scene_name: optional_backup_string(
                self.active_scene_name,
                200,
                "backup_history_scene_name",
            )?,
            active_scene_prompt_chars: self.active_scene_prompt_chars.filter(|value| *value >= 0),
            active_scene_prompt_truncated: self.active_scene_prompt_truncated,
            output_status: optional_backup_string(
                self.output_status,
                100,
                "backup_history_output_status",
            )?,
            output_error: optional_backup_string(
                self.output_error,
                2_000,
                "backup_history_output_error",
            )?,
            mode: match self.mode.as_deref() {
                Some("ask") => "ask".to_string(),
                Some("translate") => "translate".to_string(),
                _ => "dictate".to_string(),
            },
            answer_text: optional_backup_string(self.answer_text, 20_000, "backup_history_answer")?,
        })
    }
}

#[derive(Debug, Deserialize)]
pub struct BackupDictionaryEntry {
    word: String,
    #[serde(default)]
    pronunciation: Option<String>,
}

impl From<BackupDictionaryEntry> for DictionaryEntry {
    fn from(entry: BackupDictionaryEntry) -> Self {
        Self {
            id: 0,
            word: entry.word,
            pronunciation: entry.pronunciation,
        }
    }
}

#[derive(Debug, Deserialize)]
pub struct BackupCorrectionRule {
    pattern: String,
    replacement: String,
    #[serde(default = "default_true")]
    enabled: bool,
}

impl From<BackupCorrectionRule> for CorrectionRule {
    fn from(rule: BackupCorrectionRule) -> Self {
        Self {
            id: 0,
            pattern: rule.pattern,
            replacement: rule.replacement,
            enabled: rule.enabled,
        }
    }
}

#[derive(Debug, Deserialize)]
#[serde(untagged)]
pub enum DictionaryBackupPayload {
    Legacy(Vec<BackupDictionaryEntry>),
    Current {
        entries: Vec<BackupDictionaryEntry>,
        #[serde(default, alias = "correctionRules")]
        correction_rules: Vec<BackupCorrectionRule>,
    },
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RestoreBackupResult {
    history: Vec<HistoryEntry>,
    dictionary: Vec<DictionaryEntry>,
    correction_rules: Vec<CorrectionRule>,
}

#[tauri::command]
pub async fn restore_backup_data(
    history_state: tauri::State<'_, storage::HistoryStore>,
    dictionary_state: tauri::State<'_, storage::DictionaryStore>,
    config_state: tauri::State<'_, storage::ConfigManager>,
    history: Option<Vec<BackupHistoryEntry>>,
    dictionary: Option<DictionaryBackupPayload>,
) -> Result<RestoreBackupResult, String> {
    let fallback_timestamp = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
    let history = history
        .map(|entries| {
            if entries.len() > DEFAULT_HISTORY_MAX_ENTRIES as usize {
                return Err("backup_history_too_large".to_string());
            }
            entries
                .into_iter()
                .map(|entry| entry.into_storage(&fallback_timestamp))
                .collect::<Result<Vec<_>, _>>()
        })
        .transpose()?;
    let (dictionary, correction_rules) = match dictionary {
        Some(DictionaryBackupPayload::Legacy(entries)) => {
            (Some(entries.into_iter().map(Into::into).collect()), None)
        }
        Some(DictionaryBackupPayload::Current {
            entries,
            correction_rules,
        }) => (
            Some(entries.into_iter().map(Into::into).collect()),
            Some(correction_rules.into_iter().map(Into::into).collect()),
        ),
        None => (None, None),
    };
    let policy = config_state
        .load()
        .await
        .map_err(|error| error.to_string())?
        .history_retention_policy();

    history_state
        .restore_backup_data(
            history,
            dictionary,
            correction_rules,
            &policy,
            &fallback_timestamp,
        )
        .await
        .map_err(|error| error.to_string())?;

    Ok(RestoreBackupResult {
        history: history_state
            .list(DEFAULT_HISTORY_MAX_ENTRIES, 0)
            .await
            .map_err(|error| error.to_string())?,
        dictionary: dictionary_state
            .list()
            .await
            .map_err(|error| error.to_string())?,
        correction_rules: dictionary_state
            .correction_rules()
            .await
            .map_err(|error| error.to_string())?,
    })
}

fn default_true() -> bool {
    true
}

fn normalize_timestamp(value: Option<String>, fallback: &str) -> Result<String, String> {
    let Some(value) = value.filter(|value| !value.trim().is_empty()) else {
        return Ok(fallback.to_string());
    };
    let value = value.trim();
    if chrono::NaiveDateTime::parse_from_str(value, "%Y-%m-%dT%H:%M:%S").is_ok() {
        return Ok(value.to_string());
    }
    chrono::DateTime::parse_from_rfc3339(value)
        .map(|timestamp| {
            timestamp
                .naive_utc()
                .format("%Y-%m-%dT%H:%M:%S")
                .to_string()
        })
        .map_err(|_| "backup_history_created_at_invalid".to_string())
}

fn optional_backup_string(
    value: Option<String>,
    max_chars: usize,
    field: &str,
) -> Result<Option<String>, String> {
    value
        .map(|value| validated_backup_string(value, max_chars, field, false))
        .transpose()
        .map(|value| value.filter(|value| !value.is_empty()))
}

fn validated_backup_string(
    value: String,
    max_chars: usize,
    field: &str,
    allow_empty: bool,
) -> Result<String, String> {
    let value = value.replace('\0', "").trim().to_string();
    if !allow_empty && value.is_empty() {
        return Ok(String::new());
    }
    if value.chars().count() > max_chars {
        return Err(format!("{field}_too_long"));
    }
    Ok(value)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_history_uses_safe_context_defaults() {
        let entry: BackupHistoryEntry = serde_json::from_value(serde_json::json!({
            "created_at": "2026-07-12T08:30:00Z",
            "app_name": "Mail",
            "raw_text": "hello"
        }))
        .unwrap();

        let restored = entry.into_storage("2026-07-13T00:00:00").unwrap();

        assert_eq!(restored.created_at, "2026-07-12T08:30:00");
        assert_eq!(restored.context_label, "Mail");
        assert_eq!(restored.context_family, ContextFamily::General);
        assert_eq!(restored.polished_text, "hello");
    }

    #[test]
    fn current_dictionary_payload_includes_correction_rules() {
        let payload: DictionaryBackupPayload = serde_json::from_value(serde_json::json!({
            "entries": [{ "word": "OpenTypeless", "pronunciation": null }],
            "correction_rules": [{
                "pattern": "open type less",
                "replacement": "OpenTypeless",
                "enabled": false
            }]
        }))
        .unwrap();

        let DictionaryBackupPayload::Current {
            entries,
            correction_rules,
        } = payload
        else {
            panic!("expected current backup payload");
        };
        assert_eq!(entries.len(), 1);
        assert_eq!(correction_rules.len(), 1);
        assert!(!correction_rules[0].enabled);
    }

    #[test]
    fn invalid_history_timestamp_is_rejected_before_restore() {
        let entry: BackupHistoryEntry = serde_json::from_value(serde_json::json!({
            "created_at": "not-a-date",
            "raw_text": "hello"
        }))
        .unwrap();

        assert_eq!(
            entry.into_storage("2026-07-13T00:00:00").unwrap_err(),
            "backup_history_created_at_invalid"
        );
    }
}
