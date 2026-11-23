use crate::app_detector::types::{BrowserAccessStatus, ContextFamily};
use crate::storage::{
    self, CorrectionRule, DictionaryEntry, HistoryEntry, HistoryProviderKind,
    DEFAULT_HISTORY_MAX_ENTRIES,
};
use serde::{Deserialize, Serialize};
use tauri::Manager;

const MAX_HISTORY_TEXT_CHARS: usize = 1_000_000;

#[derive(Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudBackupSnapshot {
    config: storage::AppConfig,
    history: Vec<HistoryEntry>,
    dictionary: Vec<DictionaryEntry>,
    correction_rules: Vec<CorrectionRule>,
}

fn backup_snapshot_config(mut config: storage::AppConfig) -> storage::AppConfig {
    config.stt_api_key.clear();
    config.stt_custom_api_key.clear();
    config.llm_api_key.clear();
    for endpoint in [&mut config.llm_base_url, &mut config.stt_custom_base_url] {
        let safe = endpoint.is_empty()
            || reqwest::Url::parse(endpoint).is_ok_and(|url| {
                matches!(url.scheme(), "http" | "https")
                    && url.username().is_empty()
                    && url.password().is_none()
                    && url.fragment().is_none()
                    && url.query_pairs().all(|(key, _)| {
                        !matches!(
                            key.to_lowercase().replace(['_', '-'], "").as_str(),
                            "apikey"
                                | "accesstoken"
                                | "authorization"
                                | "token"
                                | "password"
                                | "secret"
                                | "clientsecret"
                                | "signature"
                                | "sig"
                                | "key"
                        )
                    })
            });
        if !safe {
            endpoint.clear();
        }
    }
    config
}

#[tauri::command]
pub async fn get_cloud_backup_snapshot<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
) -> Result<CloudBackupSnapshot, String> {
    if window.label() != "main" {
        return Err("Backup requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)?;
    tauri::async_runtime::spawn_blocking(move || {
        let app = window.app_handle();
        let settings = app.state::<storage::ConfigManager>();
        let history = app.state::<storage::HistoryStore>();
        settings
            .with_saved_backup_config(|config| {
                let (mut history, dictionary, correction_rules) =
                    history.cloud_backup_data_snapshot()?;
                for entry in &mut history {
                    entry.output_error = None;
                }
                let snapshot = CloudBackupSnapshot {
                    config: backup_snapshot_config(config.clone()),
                    history,
                    dictionary,
                    correction_rules,
                };
                storage::ensure_backup_snapshot_size(&snapshot)?;
                Ok(snapshot)
            })
            .map_err(|error| error.to_string())
    })
    .await
    .map_err(|_| "Backup snapshot worker unavailable".to_string())?
}

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
    #[serde(default)]
    ask_intent: Option<String>,
    #[serde(default)]
    citations: Option<serde_json::Value>,
    #[serde(default)]
    citation_supports: Option<serde_json::Value>,
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
            answer_text: optional_backup_string(
                self.answer_text,
                MAX_HISTORY_TEXT_CHARS,
                "backup_history_answer",
            )?,
            citations: self.citations,
            citation_supports: self.citation_supports,
            ask_intent: self.ask_intent,
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
                .map(|entry| {
                    let entry = entry.into_storage(fallback_timestamp)?;
                    storage::validated_history_citations(&entry)
                        .map_err(|_| "backup_history_citations_invalid".to_string())?;
                    storage::validated_history_supports(&entry)
                        .map_err(|_| "backup_history_citation_supports_invalid".to_string())?;
                    storage::validate_history_ask_intent(&entry)
                        .map_err(|_| "backup_history_ask_intent_invalid".to_string())?;
                    Ok(entry)
                })
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
pub async fn validate_backup_data<R: tauri::Runtime>(
    window: tauri::Window<R>,
    history: Option<Vec<BackupHistoryEntry>>,
    dictionary: Option<DictionaryBackupPayload>,
) -> Result<(), String> {
    ensure_backup_window(window.label())?;
    ensure_backup_content(&window)?;
    let fallback_timestamp = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
    tauri::async_runtime::spawn_blocking(move || {
        prepare_backup_payload(history, dictionary, &fallback_timestamp).map(|_| ())
    })
    .await
    .map_err(|_| "Backup validation worker unavailable".to_string())??;
    ensure_backup_content(&window)?;
    Ok(())
}

#[tauri::command]
pub async fn restore_backup_data(
    window: tauri::Window,
    history: Option<Vec<BackupHistoryEntry>>,
    dictionary: Option<DictionaryBackupPayload>,
) -> Result<RestoreBackupResult, String> {
    ensure_backup_window(window.label())?;
    ensure_backup_content(&window)?;
    let app = window.app_handle().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let history_state = app
            .try_state::<storage::HistoryStore>()
            .ok_or("Backup history state unavailable")?;
        let _restoration = history_state
            .reserve_backup_restore()
            .map_err(|error| error.to_string())?;
        let dictionary_state = app
            .try_state::<storage::DictionaryStore>()
            .ok_or("Backup dictionary state unavailable")?;
        let config_state = app
            .try_state::<storage::ConfigManager>()
            .ok_or("Backup configuration state unavailable")?;
        let fallback_timestamp = chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string();
        let PreparedBackupPayload {
            history,
            dictionary,
            correction_rules,
        } = prepare_backup_payload(history, dictionary, &fallback_timestamp)?;
        ensure_backup_content(&window)?;
        config_state
            .with_current_history_privacy(|privacy| {
                let (policy, _save_audio) = privacy.ok_or_else(|| {
                    anyhow::anyhow!("Application history settings are unavailable")
                })?;
                history_state.restore_backup_data_sync(
                    history,
                    dictionary,
                    correction_rules,
                    &policy,
                    &fallback_timestamp,
                )
            })
            .map_err(|error| error.to_string())?;

        let result = tauri::async_runtime::block_on(async {
            Ok::<_, String>(RestoreBackupResult {
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
        })?;
        ensure_backup_content(&window)?;
        Ok(result)
    })
    .await
    .map_err(|_| "Backup restoration worker unavailable".to_string())?
}

fn ensure_backup_content<R: tauri::Runtime>(window: &tauri::Window<R>) -> Result<(), String> {
    let webview = window
        .app_handle()
        .get_webview_window(window.label())
        .ok_or("Backup local content unavailable")?;
    crate::update_install_request::ensure_local_window(&webview)
        .map_err(|_| "Backup access requires local application content".into())
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
    #[test]
    fn aggregate_backup_config_excludes_credentials_and_sensitive_endpoints() {
        let config = storage::AppConfig {
            stt_api_key: "synthetic-stt-secret".into(),
            stt_custom_api_key: "synthetic-custom-secret".into(),
            llm_api_key: "synthetic-llm-secret".into(),
            llm_base_url: "https://example.invalid/v1?api_key=synthetic-secret".into(),
            stt_custom_base_url: "http://localhost:8000/v1".into(),
            ..storage::AppConfig::default()
        };
        let safe = super::backup_snapshot_config(config);
        assert!(safe.stt_api_key.is_empty());
        assert!(safe.stt_custom_api_key.is_empty());
        assert!(safe.llm_api_key.is_empty());
        assert!(safe.llm_base_url.is_empty());
        assert_eq!(safe.stt_custom_base_url, "http://localhost:8000/v1");
    }

    use super::*;

    #[test]
    fn backup_restoration_and_readback_run_inside_a_blocking_worker() {
        let command = include_str!("backup.rs")
            .split("pub async fn restore_backup_data(")
            .nth(1)
            .unwrap()
            .split("fn ensure_backup_content")
            .next()
            .unwrap();
        let worker = command
            .find("spawn_blocking")
            .expect("Restore needs a worker");
        assert!(command.find("ensure_backup_window").unwrap() < worker);
        assert!(worker < command.find("prepare_backup_payload").unwrap());
        assert!(worker < command.find(".with_current_history_privacy(").unwrap());
        assert!(worker < command.find(".restore_backup_data_sync(").unwrap());
        assert!(worker < command.find(".correction_rules()").unwrap());
    }

    #[tokio::test]
    async fn backup_preflight_rejects_remote_main_content_without_database_access() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(
            &app,
            "main",
            tauri::WebviewUrl::External("https://example.invalid/".parse().unwrap()),
        )
        .build()
        .unwrap();
        let result = validate_backup_data(window.as_ref().window().clone(), None, None).await;
        assert_eq!(
            result.unwrap_err(),
            "Backup access requires local application content"
        );
    }

    #[test]
    fn backup_preflight_parsing_runs_inside_a_blocking_worker() {
        let command = include_str!("backup.rs")
            .split("fn validate_backup_data")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let worker = command
            .find("spawn_blocking")
            .expect("Preflight needs a worker");
        assert!(command.find("ensure_backup_window").unwrap() < worker);
        assert!(worker < command.find("prepare_backup_payload").unwrap());
    }

    #[tokio::test]
    async fn restored_response_matches_shared_desktop_fixture() {
        let entry: BackupHistoryEntry = serde_json::from_value(serde_json::json!({
            "created_at": "2026-11-20T00:00:00",
            "raw_text": "Synthetic question",
            "mode": "ask",
            "answer_text": "Synthetic answer",
            "ask_intent": "ask_selection",
            "citations": [{"url":"https://example.invalid/source","title":"Source"}]
        }))
        .unwrap();
        let dictionary: DictionaryBackupPayload = serde_json::from_value(serde_json::json!({
            "entries": [{"word":"Synthetic word","pronunciation":null,"auto":false}],
            "correction_rules": [{"pattern":"old","replacement":"new","enabled":true}]
        }))
        .unwrap();
        let prepared =
            prepare_backup_payload(Some(vec![entry]), Some(dictionary), "2026-11-20T00:00:00")
                .unwrap();
        let directory =
            std::env::temp_dir().join(format!("otl-restore-contract-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir(&directory).unwrap();
        let path = directory.join("history.sqlite");
        let history = storage::HistoryStore::new(path.clone()).unwrap();
        let dictionary = storage::DictionaryStore::new(path).unwrap();
        history
            .restore_backup_data_sync(
                prepared.history,
                prepared.dictionary,
                prepared.correction_rules,
                &storage::HistoryRetentionPolicy::default(),
                "2026-11-20T00:00:00",
            )
            .unwrap();
        let response = RestoreBackupResult {
            history: history.list(DEFAULT_HISTORY_MAX_ENTRIES, 0).await.unwrap(),
            dictionary: dictionary.list().await.unwrap(),
            correction_rules: dictionary.correction_rules().await.unwrap(),
        };
        let actual = serde_json::to_value(response).unwrap();
        if let Some(output) = std::env::var_os("OPENTYPELESS_RESTORE_FIXTURE_OUTPUT") {
            use std::io::Write;
            let mut file = std::fs::OpenOptions::new()
                .write(true)
                .create_new(true)
                .open(output)
                .unwrap();
            file.write_all(serde_json::to_string_pretty(&actual).unwrap().as_bytes())
                .unwrap();
        }
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../tests/fixtures/backup_restore_response.json"
        ))
        .unwrap();
        assert_eq!(actual, fixture);
        drop(dictionary);
        drop(history);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn backup_preflight_preserves_verified_supports_and_rejects_invalid_positions() {
        let url = "https://example.invalid/source";
        let answer = format!("Read {url}");
        let value = serde_json::json!({
            "raw_text":"Question", "mode":"ask", "answer_text":answer,
            "citations":[{"url":url,"title":"Source"}],
            "citation_supports":[{"start":5,"end":answer.len(),"sourceIndex":0}]
        });
        let entry = serde_json::from_value(value.clone()).unwrap();
        let prepared =
            prepare_backup_payload(Some(vec![entry]), None, "2026-11-21T00:00:00").unwrap();
        assert_eq!(
            prepared.history.unwrap()[0].citation_supports,
            Some(value["citation_supports"].clone())
        );
        let mut invalid = value;
        invalid["citation_supports"][0]["end"] = serde_json::json!(6);
        let entry = serde_json::from_value(invalid).unwrap();
        assert!(prepare_backup_payload(Some(vec![entry]), None, "2026-11-21T00:00:00").is_err());
    }

    #[test]
    fn long_answer_backup_preparation_restores_into_sqlite_and_reopens_sources() {
        let answer = "字😀".repeat(15_000);
        let entry: BackupHistoryEntry = serde_json::from_value(serde_json::json!({
            "raw_text": "Synthetic question",
            "mode": "ask",
            "answer_text": answer,
            "citations": [{
                "url": "https://example.invalid/source",
                "title": "Synthetic source"
            }]
        }))
        .unwrap();
        let prepared =
            prepare_backup_payload(Some(vec![entry]), None, "2026-11-20T00:00:00").unwrap();
        let path = std::env::temp_dir().join(format!(
            "otl-long-answer-restore-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let history = storage::HistoryStore::new(path.clone()).unwrap();
        history
            .restore_backup_data_sync(
                prepared.history,
                prepared.dictionary,
                prepared.correction_rules,
                &storage::HistoryRetentionPolicy::default(),
                "2026-11-20T00:00:00",
            )
            .unwrap();
        drop(history);
        let history = storage::HistoryStore::new(path.clone()).unwrap();
        let connection = rusqlite::Connection::open(&path).unwrap();
        let id: i64 = connection
            .query_row("SELECT id FROM history", [], |row| row.get(0))
            .unwrap();
        let snapshot = history
            .read_saved_answer_sync(id, "Synthetic question", &answer)
            .unwrap()
            .unwrap();
        assert_eq!(snapshot.answer, answer);
        assert_eq!(
            snapshot.citations,
            serde_json::json!([{
                "url": "https://example.invalid/source",
                "title": "Synthetic source"
            }])
        );
        drop(connection);
        drop(history);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn backup_preserves_long_saved_answers_without_truncation() {
        let answer = "字".repeat(20_001);
        let entry: BackupHistoryEntry = serde_json::from_value(serde_json::json!({
            "raw_text": "Synthetic question",
            "mode": "ask",
            "answer_text": answer
        }))
        .unwrap();
        let restored = entry.into_storage("2026-11-20T00:00:00").unwrap();
        assert_eq!(restored.answer_text.as_deref(), Some(answer.as_str()));
    }

    #[test]
    fn backup_rejects_answers_above_the_history_text_limit() {
        let entry: BackupHistoryEntry = serde_json::from_value(serde_json::json!({
            "raw_text": "Synthetic question",
            "mode": "ask",
            "answer_text": "x".repeat(MAX_HISTORY_TEXT_CHARS + 1)
        }))
        .unwrap();
        assert_eq!(
            entry.into_storage("2026-11-20T00:00:00").unwrap_err(),
            "backup_history_answer_too_long"
        );
    }

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
            "pub async fn validate_backup_data",
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
