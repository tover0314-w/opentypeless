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
        if raw_text.is_empty()
            && polished_text.is_empty()
            && !storage::is_recording_only_status(self.output_status.as_deref())
        {
            return Err("backup_history_text_empty".to_string());
        }

        let context_label = optional_backup_string(
            self.context_label.or(self.app_name),
            200,
            "backup_history_context_label",
        )?
        .unwrap_or_else(|| "General".to_string());

        Ok(HistoryEntry {
            has_audio: false,
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
    #[serde(default)]
    auto: bool,
}

impl From<BackupDictionaryEntry> for DictionaryEntry {
    fn from(entry: BackupDictionaryEntry) -> Self {
        Self {
            id: 0,
            word: entry.word,
            pronunciation: entry.pronunciation,
            auto: entry.auto,
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

struct PreparedBackupPayload {
    history: Option<Vec<HistoryEntry>>,
    dictionary: Option<Vec<DictionaryEntry>>,
    correction_rules: Option<Vec<CorrectionRule>>,
}

fn prepare_backup_payload(
    history: Option<Vec<BackupHistoryEntry>>,
    dictionary: Option<DictionaryBackupPayload>,
    fallback_timestamp: &str,
) -> Result<PreparedBackupPayload, String> {
    let history = history
        .map(|entries| {
            if entries.len() > DEFAULT_HISTORY_MAX_ENTRIES as usize {
                return Err("backup_history_too_large".to_string());
            }
            entries
                .into_iter()
                .map(|entry| entry.into_storage(fallback_timestamp))
                .collect::<Result<Vec<_>, _>>()
        })
        .transpose()?;
    let (dictionary, correction_rules): (
        Option<Vec<DictionaryEntry>>,
        Option<Vec<CorrectionRule>>,
    ) = match dictionary {
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
    if let Some(entries) = dictionary.as_ref() {
        storage::prepare_backup_dictionary(entries.clone()).map_err(|error| error.to_string())?;
    }
    if let Some(rules) = correction_rules.as_ref() {
        storage::prepare_backup_correction_rules(rules.clone())
            .map_err(|error| error.to_string())?;
    }
    Ok(PreparedBackupPayload {
        history,
        dictionary,
        correction_rules,
    })
}

#[tauri::command]
pub fn validate_backup_data<R: tauri::Runtime>(
    window: tauri::Window<R>,
    history: Option<Vec<BackupHistoryEntry>>,
    dictionary: Option<DictionaryBackupPayload>,
) -> Result<(), String> {
    ensure_backup_window(window.label())?;
    let fallback_timestamp = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
    prepare_backup_payload(history, dictionary, &fallback_timestamp)?;
    Ok(())
}

#[tauri::command]
pub async fn restore_backup_data(
    window: tauri::Window,
    history_state: tauri::State<'_, storage::HistoryStore>,
    dictionary_state: tauri::State<'_, storage::DictionaryStore>,
    config_state: tauri::State<'_, storage::ConfigManager>,
    history: Option<Vec<BackupHistoryEntry>>,
    dictionary: Option<DictionaryBackupPayload>,
) -> Result<RestoreBackupResult, String> {
    ensure_backup_window(window.label())?;
    let fallback_timestamp = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
    let PreparedBackupPayload {
        history,
        dictionary,
        correction_rules,
    } = prepare_backup_payload(history, dictionary, &fallback_timestamp)?;
    config_state
        .with_current_history_privacy(|privacy| {
            let (policy, _save_audio) = privacy
                .ok_or_else(|| anyhow::anyhow!("Application history settings are unavailable"))?;
            history_state.restore_backup_data_sync(
                history,
                dictionary,
                correction_rules,
                &policy,
                &fallback_timestamp,
            )
        })
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

fn ensure_backup_window(label: &str) -> Result<(), String> {
    if label == "main" {
        Ok(())
    } else {
        Err("Backup restoration is only available in the main window".to_string())
    }
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
    fn native_backup_timestamp_accepts_extended_naive_years() {
        assert_eq!(
            normalize_timestamp(Some("+10000-01-01T10:00:00".to_string()), ""),
            Ok("+10000-01-01T10:00:00".to_string())
        );
    }

    #[test]
    fn validation_and_restore_share_backup_payload_preparation() {
        let production = include_str!("backup.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        for marker in [
            "pub fn validate_backup_data",
            "pub async fn restore_backup_data(",
        ] {
            let command = production
                .split(marker)
                .nth(1)
                .unwrap()
                .split("\n#[tauri::command]")
                .next()
                .unwrap();
            assert!(command.contains("prepare_backup_payload("));
        }
    }

    #[test]
    fn backup_validation_ipc_rejects_invalid_payloads_without_database_state() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![validate_backup_data])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let main = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        for (body, valid) in [
            (
                serde_json::json!({"history":[{"raw_text":"Words","created_at":"invalid"}]}),
                false,
            ),
            (
                serde_json::json!({"history":[{"raw_text":"Words","created_at":"2026-02-30T10:00:00"}]}),
                false,
            ),
            (
                serde_json::json!({"history":[{"raw_text":"Words","created_at":"2026-11-01T25:00:00Z"}]}),
                false,
            ),
            (serde_json::json!({"dictionary":[{"word":""}]}), false),
            (
                serde_json::json!({"history":[{"raw_text":"Words","created_at":"2026-11-10T10:00:00"}],"dictionary":[{"word":"Term"}]}),
                true,
            ),
            (
                serde_json::json!({"history":[{"raw_text":"Question","mode":"ask","answer_text":"Synthetic answer"}]}),
                true,
            ),
        ] {
            let result = tauri::test::get_ipc_response(
                &main,
                tauri::webview::InvokeRequest {
                    cmd: "validate_backup_data".to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(body),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            assert_eq!(result.is_ok(), valid);
        }
        for label in ["capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &webview,
                tauri::webview::InvokeRequest {
                    cmd: "validate_backup_data".to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "history": [{"raw_text": "Synthetic words"}]
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            assert_eq!(
                result.unwrap_err(),
                serde_json::json!("Backup restoration is only available in the main window")
            );
        }
    }

    #[test]
    fn recording_only_backup_rows_keep_status_without_fabricating_audio() {
        for status in ["dismissed", "silent", "recognition_failed"] {
            let entry: BackupHistoryEntry = serde_json::from_value(serde_json::json!({
                "created_at": "2026-11-10T10:00:00",
                "raw_text": "",
                "polished_text": "",
                "output_status": status,
                "has_audio": true
            }))
            .unwrap();
            let restored = entry.into_storage("2026-11-10T10:00:00").unwrap();
            assert_eq!(restored.output_status.as_deref(), Some(status));
            assert!(!restored.has_audio);
        }
        let entry: BackupHistoryEntry =
            serde_json::from_value(serde_json::json!({"raw_text": "", "output_status": "unknown"}))
                .unwrap();
        assert!(entry.into_storage("2026-11-10T10:00:00").is_err());
    }

    #[test]
    fn backup_restore_uses_guarded_current_privacy_for_database_write() {
        let source = include_str!("backup.rs");
        let command = source
            .split("pub async fn restore_backup_data(")
            .nth(1)
            .unwrap()
            .split("fn default_true")
            .next()
            .unwrap();
        assert!(command.contains(".with_current_history_privacy("));
        assert!(command.contains(".restore_backup_data_sync("));
        assert!(!command.contains(".history_retention_policy()"));
    }

    #[test]
    fn backup_restore_checks_main_window_before_processing_or_storage() {
        let source = include_str!("backup.rs");
        let command = source
            .split("pub async fn restore_backup_data(")
            .nth(1)
            .unwrap()
            .split("fn normalize_timestamp")
            .next()
            .unwrap();
        let guard = command
            .find("ensure_backup_window(window.label())?;")
            .unwrap();
        assert!(guard < command.find("let fallback_timestamp").unwrap());
    }

    #[test]
    fn backup_restore_window_policy_rejects_auxiliary_windows() {
        assert!(ensure_backup_window("main").is_ok());
        for label in ["capsule", "ask", "", "other"] {
            assert!(ensure_backup_window(label).is_err());
        }
    }

    #[test]
    fn dictionary_backup_rejects_duplicate_correction_aliases() {
        let value = serde_json::json!({
            "entries": [],
            "correction_rules": [],
            "correctionRules": []
        });
        assert!(serde_json::from_value::<DictionaryBackupPayload>(value).is_err());
    }

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
