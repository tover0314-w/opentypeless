use anyhow::Result;
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::Mutex;
use tauri_plugin_store::StoreExt;

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(default)]
pub struct AppConfig {
    pub stt_provider: String,
    pub stt_api_key: String,
    pub stt_custom_api_key: String,
    pub stt_language: String,
    pub stt_custom_preset: String,
    pub stt_custom_base_url: String,
    pub stt_custom_model: String,
    pub llm_provider: String,
    pub llm_api_key: String,
    pub llm_model: String,
    pub llm_base_url: String,
    pub polish_enabled: bool,
    pub translate_enabled: bool,
    pub target_lang: String,
    pub hotkey: String,
    pub hotkey_mode: String,
    pub output_mode: String,
    pub selected_text_enabled: bool,
    pub theme: String,
    pub auto_start: bool,
    pub close_to_tray: bool,
    pub start_minimized: bool,
    pub max_recording_seconds: u32,
    pub ui_language: String,
    pub capsule_auto_hide: bool,
    pub save_recordings: bool,
    pub recording_format: String,
    /// Max number of audio recordings to keep on disk. 0 = unlimited.
    pub max_saved_recordings: u32,
}

impl Default for AppConfig {
    fn default() -> Self {
        Self {
            stt_provider: "glm-asr".to_string(),
            stt_api_key: String::new(),
            stt_custom_api_key: String::new(),
            stt_language: "multi".to_string(),
            stt_custom_preset: crate::stt::config::CUSTOM_WHISPER_PRESET_SPEACHES.to_string(),
            stt_custom_base_url: crate::stt::config::DEFAULT_CUSTOM_WHISPER_BASE_URL.to_string(),
            stt_custom_model: crate::stt::config::DEFAULT_CUSTOM_WHISPER_MODEL.to_string(),
            llm_provider: "openrouter".to_string(),
            llm_api_key: String::new(),
            llm_model: "google/gemini-2.5-flash".to_string(),
            llm_base_url: "https://openrouter.ai/api/v1".to_string(),
            polish_enabled: true,
            translate_enabled: false,
            target_lang: "en".to_string(),
            #[cfg(target_os = "macos")]
            hotkey: "Option+/".to_string(),
            #[cfg(not(target_os = "macos"))]
            hotkey: "Ctrl+/".to_string(),
            hotkey_mode: "hold".to_string(),
            output_mode: "keyboard".to_string(),
            selected_text_enabled: false,
            theme: "system".to_string(),
            auto_start: false,
            close_to_tray: true,
            start_minimized: false,
            max_recording_seconds: 30,
            ui_language: "en".to_string(),
            capsule_auto_hide: false,
            save_recordings: false,
            recording_format: "flac".to_string(),
            max_saved_recordings: 0,
        }
    }
}

impl AppConfig {
    pub fn new_install_default() -> Self {
        Self {
            capsule_auto_hide: true,
            ..Self::default()
        }
    }

    fn normalize_platform_hotkey(&mut self) {
        #[cfg(target_os = "macos")]
        if self.hotkey == "Alt+/" {
            self.hotkey = "Option+/".to_string();
        }
    }

    pub fn from_stored_value(value: serde_json::Value) -> Result<Self, serde_json::Error> {
        let has_capsule_auto_hide = value
            .as_object()
            .is_some_and(|object| object.contains_key("capsule_auto_hide"));
        let mut config: Self = serde_json::from_value(value)?;
        if !has_capsule_auto_hide {
            config.capsule_auto_hide = false;
        }
        config.normalize_platform_hotkey();
        Ok(config)
    }
}

// ─── ConfigManager (tauri-plugin-store backed) ───

pub struct ConfigManager {
    app_handle: tauri::AppHandle,
    cache: Mutex<Option<AppConfig>>,
}

impl ConfigManager {
    pub fn new(app_handle: tauri::AppHandle) -> Self {
        Self {
            app_handle,
            cache: Mutex::new(None),
        }
    }

    pub async fn load(&self) -> Result<AppConfig> {
        if let Some(config) = self.cache.lock().unwrap_or_else(|e| e.into_inner()).clone() {
            return Ok(config);
        }

        let config = match self.app_handle.store("settings.json") {
            Ok(store) => match store.get("app_config") {
                Some(val) => AppConfig::from_stored_value(val.clone())
                    .unwrap_or_else(|_| AppConfig::new_install_default()),
                None => AppConfig::new_install_default(),
            },
            Err(_) => AppConfig::new_install_default(),
        };

        *self.cache.lock().unwrap_or_else(|e| e.into_inner()) = Some(config.clone());
        Ok(config)
    }

    pub async fn save(&self, config: &AppConfig) -> Result<()> {
        *self.cache.lock().unwrap_or_else(|e| e.into_inner()) = Some(config.clone());

        let store = self
            .app_handle
            .store("settings.json")
            .map_err(|e| anyhow::anyhow!("Failed to open store: {}", e))?;
        let val = serde_json::to_value(config)?;
        store.set("app_config", val);
        store.save().map_err(|e| anyhow::anyhow!("{}", e))?;

        Ok(())
    }
}

// ─── HistoryStore (SQLite backed) ───

/// Maximum number of history entries to retain. Older entries are pruned on insert.
const MAX_HISTORY_ENTRIES: u32 = 5000;

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub id: i64,
    pub created_at: String,
    pub app_name: String,
    pub app_type: String,
    pub raw_text: String,
    pub polished_text: String,
    pub language: Option<String>,
    pub duration_ms: Option<i64>,
    /// Absolute path to the saved audio file, if recording-to-disk was enabled.
    pub recording_file: Option<String>,
}

/// Columns selected for a `HistoryEntry`, in struct-field order.
const HISTORY_COLUMNS: &str =
    "id, created_at, app_name, app_type, raw_text, polished_text, language, duration_ms, recording_file";

fn map_history_row(row: &rusqlite::Row) -> rusqlite::Result<HistoryEntry> {
    Ok(HistoryEntry {
        id: row.get(0)?,
        created_at: row.get(1)?,
        app_name: row.get(2)?,
        app_type: row.get(3)?,
        raw_text: row.get(4)?,
        polished_text: row.get(5)?,
        language: row.get(6)?,
        duration_ms: row.get(7)?,
        recording_file: row.get(8)?,
    })
}

pub struct HistoryStore {
    conn: Mutex<Connection>,
}

impl HistoryStore {
    pub fn new(db_path: PathBuf) -> Result<Self> {
        let conn = Connection::open(&db_path)?;
        conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL,
                app_name TEXT NOT NULL DEFAULT '',
                app_type TEXT NOT NULL DEFAULT '',
                raw_text TEXT NOT NULL DEFAULT '',
                polished_text TEXT NOT NULL DEFAULT '',
                language TEXT,
                duration_ms INTEGER
            );",
        )?;
        Self::ensure_recording_file_column(&conn)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    /// Idempotently add the `recording_file` column to pre-existing databases.
    /// `ALTER TABLE ADD COLUMN` errors if the column already exists, so guard on
    /// the current schema first.
    fn ensure_recording_file_column(conn: &Connection) -> Result<()> {
        let mut has_column = false;
        {
            let mut stmt = conn.prepare("PRAGMA table_info(history)")?;
            let names = stmt.query_map([], |row| row.get::<_, String>(1))?;
            for name in names {
                if name? == "recording_file" {
                    has_column = true;
                    break;
                }
            }
        }
        if !has_column {
            conn.execute("ALTER TABLE history ADD COLUMN recording_file TEXT", [])?;
        }
        Ok(())
    }

    pub async fn add(&self, entry: HistoryEntry) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "INSERT INTO history (created_at, app_name, app_type, raw_text, polished_text, language, duration_ms, recording_file)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7, ?8)",
            rusqlite::params![
                entry.created_at,
                entry.app_name,
                entry.app_type,
                entry.raw_text,
                entry.polished_text,
                entry.language,
                entry.duration_ms,
                entry.recording_file,
            ],
        )?;

        // Prune old entries beyond the retention limit
        conn.execute(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT ?1)",
            rusqlite::params![MAX_HISTORY_ENTRIES],
        )?;

        Ok(())
    }

    pub async fn list(&self, limit: u32, offset: u32) -> Result<Vec<HistoryEntry>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(&format!(
            "SELECT {HISTORY_COLUMNS} FROM history ORDER BY id DESC LIMIT ?1 OFFSET ?2"
        ))?;
        let rows = stmt.query_map(rusqlite::params![limit, offset], map_history_row)?;
        let mut entries = Vec::new();
        for row in rows {
            entries.push(row?);
        }
        Ok(entries)
    }

    /// Total number of history rows, independent of any page limit. Used for
    /// the "total recordings" stat, which must reflect the real count rather
    /// than the capped size of a `list()` page.
    pub async fn count(&self) -> Result<u32> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let total: u32 = conn.query_row("SELECT COUNT(*) FROM history", [], |row| row.get(0))?;
        Ok(total)
    }

    /// List history rows that have a saved audio file, newest first.
    pub async fn list_recordings(&self, limit: u32, offset: u32) -> Result<Vec<HistoryEntry>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(&format!(
            "SELECT {HISTORY_COLUMNS} FROM history
             WHERE recording_file IS NOT NULL
             ORDER BY id DESC LIMIT ?1 OFFSET ?2"
        ))?;
        let rows = stmt.query_map(rusqlite::params![limit, offset], map_history_row)?;
        let mut entries = Vec::new();
        for row in rows {
            entries.push(row?);
        }
        Ok(entries)
    }

    pub async fn find_by_id(&self, id: i64) -> Result<Option<HistoryEntry>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(&format!(
            "SELECT {HISTORY_COLUMNS} FROM history WHERE id = ?1"
        ))?;
        let mut rows = stmt.query_map(rusqlite::params![id], map_history_row)?;
        match rows.next() {
            Some(row) => Ok(Some(row?)),
            None => Ok(None),
        }
    }

    /// Record the saved audio file path for a history row.
    pub async fn set_recording_file(&self, id: i64, path: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE history SET recording_file = ?1 WHERE id = ?2",
            rusqlite::params![path, id],
        )?;
        Ok(())
    }

    /// Clear the saved audio file path for a history row, keeping the transcript.
    pub async fn clear_recording_file(&self, id: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE history SET recording_file = NULL WHERE id = ?1",
            rusqlite::params![id],
        )?;
        Ok(())
    }

    /// Keep only the newest `max` saved recordings; clear the audio reference on
    /// older ones and return their file paths so the caller can delete them.
    ///
    /// Transcripts are preserved (only `recording_file` is nulled), mirroring a
    /// manual delete. `max == 0` means unlimited — nothing is pruned.
    pub async fn prune_recordings_over(&self, max: u32) -> Result<Vec<String>> {
        if max == 0 {
            return Ok(Vec::new());
        }
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        // All saved recordings beyond the newest `max` (LIMIT -1 = "no limit").
        let mut stmt = conn.prepare(
            "SELECT id, recording_file FROM history
             WHERE recording_file IS NOT NULL
             ORDER BY id DESC LIMIT -1 OFFSET ?1",
        )?;
        let stale: Vec<(i64, String)> = stmt
            .query_map(rusqlite::params![max], |row| {
                Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
            })?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        drop(stmt);

        let mut paths = Vec::with_capacity(stale.len());
        for (id, path) in stale {
            conn.execute(
                "UPDATE history SET recording_file = NULL WHERE id = ?1",
                rusqlite::params![id],
            )?;
            paths.push(path);
        }
        Ok(paths)
    }

    /// Replace both the raw and polished transcript for a history row.
    ///
    /// Used after re-transcription, which produces a fresh STT transcript that
    /// supersedes the previous text. Both columns are set so the new transcript
    /// survives a reload — the Recordings list renders `polished_text ||
    /// raw_text`, so leaving a stale `polished_text` would revert the displayed
    /// text. Mirrors the "polish off" pipeline path where the two are equal.
    pub async fn update_transcript(&self, id: i64, text: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE history SET raw_text = ?1, polished_text = ?1 WHERE id = ?2",
            rusqlite::params![text, id],
        )?;
        Ok(())
    }

    pub async fn clear(&self) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute("DELETE FROM history", [])?;
        Ok(())
    }
}

// ─── DictionaryStore (SQLite backed) ───

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DictionaryEntry {
    pub id: i64,
    pub word: String,
    pub pronunciation: Option<String>,
}

pub struct DictionaryStore {
    conn: Mutex<Connection>,
}

impl DictionaryStore {
    pub fn new(db_path: PathBuf) -> Result<Self> {
        let conn = Connection::open(&db_path)?;
        conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS dictionary (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                word TEXT NOT NULL,
                pronunciation TEXT
            );",
        )?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub async fn add(&self, word: &str, pronunciation: Option<&str>) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "INSERT INTO dictionary (word, pronunciation) VALUES (?1, ?2)",
            rusqlite::params![word, pronunciation],
        )?;
        Ok(())
    }

    pub async fn remove(&self, id: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "DELETE FROM dictionary WHERE id = ?1",
            rusqlite::params![id],
        )?;
        Ok(())
    }

    pub async fn list(&self) -> Result<Vec<DictionaryEntry>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare("SELECT id, word, pronunciation FROM dictionary")?;
        let rows = stmt.query_map([], |row| {
            Ok(DictionaryEntry {
                id: row.get(0)?,
                word: row.get(1)?,
                pronunciation: row.get(2)?,
            })
        })?;
        let mut entries = Vec::new();
        for row in rows {
            entries.push(row?);
        }
        Ok(entries)
    }

    pub async fn words(&self) -> Vec<String> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = match conn.prepare("SELECT word FROM dictionary") {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = match stmt.query_map([], |row| row.get::<_, String>(0)) {
            Ok(r) => r,
            Err(_) => return Vec::new(),
        };
        rows.filter_map(|r| r.ok()).collect()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn app_config_defaults_missing_custom_stt_api_key() {
        let value = serde_json::json!({
            "stt_provider": "deepgram",
            "stt_api_key": "hosted-secret"
        });

        let config: AppConfig = serde_json::from_value(value).unwrap();

        assert_eq!(config.stt_provider, "deepgram");
        assert_eq!(config.stt_api_key, "hosted-secret");
        assert_eq!(config.stt_custom_api_key, "");
    }

    #[test]
    fn app_config_new_install_defaults_capsule_auto_hide_true() {
        let config = AppConfig::new_install_default();
        assert!(config.capsule_auto_hide);
    }

    #[test]
    fn app_config_existing_missing_capsule_auto_hide_defaults_false() {
        let value = serde_json::json!({
            "stt_provider": "deepgram",
            "stt_api_key": "hosted-secret"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.stt_provider, "deepgram");
        assert_eq!(config.stt_api_key, "hosted-secret");
        assert!(!config.capsule_auto_hide);
    }

    #[test]
    fn app_config_existing_explicit_capsule_auto_hide_is_preserved() {
        let value = serde_json::json!({
            "capsule_auto_hide": true
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert!(config.capsule_auto_hide);
    }

    #[test]
    fn app_config_defaults_save_recordings_off_and_flac() {
        let config = AppConfig::default();
        assert!(!config.save_recordings);
        assert_eq!(config.recording_format, "flac");
    }

    #[test]
    fn app_config_existing_missing_recording_fields_use_defaults() {
        let value = serde_json::json!({ "stt_provider": "deepgram" });
        let config = AppConfig::from_stored_value(value).unwrap();
        assert!(!config.save_recordings);
        assert_eq!(config.recording_format, "flac");
    }

    fn history_entry(raw_text: &str) -> HistoryEntry {
        HistoryEntry {
            id: 0,
            created_at: "2026-06-22T10:00:00".to_string(),
            app_name: "Test".to_string(),
            app_type: "Unknown".to_string(),
            raw_text: raw_text.to_string(),
            polished_text: String::new(),
            language: None,
            duration_ms: Some(1234),
            recording_file: None,
        }
    }

    #[tokio::test]
    async fn history_recording_file_lifecycle() {
        let store = HistoryStore::new(PathBuf::from(":memory:")).unwrap();
        store.add(history_entry("hello")).await.unwrap();

        let rows = store.list(10, 0).await.unwrap();
        assert_eq!(rows.len(), 1);
        let id = rows[0].id;
        assert!(rows[0].recording_file.is_none());
        assert!(store.list_recordings(10, 0).await.unwrap().is_empty());

        store
            .set_recording_file(id, "/tmp/rec.flac")
            .await
            .unwrap();
        let found = store.find_by_id(id).await.unwrap().unwrap();
        assert_eq!(found.recording_file.as_deref(), Some("/tmp/rec.flac"));
        assert_eq!(found.duration_ms, Some(1234));

        let recs = store.list_recordings(10, 0).await.unwrap();
        assert_eq!(recs.len(), 1);
        assert_eq!(recs[0].id, id);

        // Clearing the file keeps the transcript row.
        store.clear_recording_file(id).await.unwrap();
        assert!(store.find_by_id(id).await.unwrap().unwrap().recording_file.is_none());
        assert_eq!(store.list(10, 0).await.unwrap().len(), 1);
    }

    #[tokio::test]
    async fn update_transcript_overwrites_raw_and_polished() {
        let store = HistoryStore::new(PathBuf::from(":memory:")).unwrap();
        store.add(history_entry("old")).await.unwrap();
        let id = store.list(1, 0).await.unwrap()[0].id;

        store.update_transcript(id, "new transcript").await.unwrap();

        let entry = store.find_by_id(id).await.unwrap().unwrap();
        assert_eq!(entry.raw_text, "new transcript");
        // polished_text must also update, else the list reverts to stale text.
        assert_eq!(entry.polished_text, "new transcript");
    }

    #[tokio::test]
    async fn prune_recordings_over_keeps_newest_and_keeps_transcripts() {
        let store = HistoryStore::new(PathBuf::from(":memory:")).unwrap();
        for i in 0..4 {
            store.add(history_entry(&format!("rec {i}"))).await.unwrap();
        }
        let ids: Vec<i64> = store
            .list(10, 0)
            .await
            .unwrap()
            .iter()
            .map(|e| e.id)
            .collect();
        for id in &ids {
            store
                .set_recording_file(*id, &format!("/tmp/{id}.mp3"))
                .await
                .unwrap();
        }

        // Keep newest 2 → the 2 oldest audio refs are cleared and returned.
        let pruned = store.prune_recordings_over(2).await.unwrap();
        assert_eq!(pruned.len(), 2);
        assert_eq!(store.list_recordings(10, 0).await.unwrap().len(), 2);
        // Transcripts of pruned rows are kept (history row count unchanged).
        assert_eq!(store.list(10, 0).await.unwrap().len(), 4);
    }

    #[tokio::test]
    async fn prune_recordings_over_zero_is_unlimited() {
        let store = HistoryStore::new(PathBuf::from(":memory:")).unwrap();
        store.add(history_entry("rec")).await.unwrap();
        let id = store.list(1, 0).await.unwrap()[0].id;
        store.set_recording_file(id, "/tmp/x.mp3").await.unwrap();

        let pruned = store.prune_recordings_over(0).await.unwrap();
        assert!(pruned.is_empty());
        assert_eq!(store.list_recordings(10, 0).await.unwrap().len(), 1);
    }

    #[tokio::test]
    async fn find_by_id_returns_none_for_missing_row() {
        let store = HistoryStore::new(PathBuf::from(":memory:")).unwrap();
        assert!(store.find_by_id(999).await.unwrap().is_none());
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn app_config_migrates_legacy_mac_alt_slash_label() {
        let value = serde_json::json!({
            "hotkey": "Alt+/"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.hotkey, "Option+/");
    }
}
