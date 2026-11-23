pub mod history_sync_ack;
pub(crate) mod history_sync_attempt;
pub mod history_sync_page;
pub mod history_sync_queue;
pub(crate) mod history_sync_record;
pub mod stats;

use crate::app_detector::registry::AppRegistry;
use crate::app_detector::types::{BrowserAccessStatus, ContextFamily, ContextProfile};
use crate::credentials::{migrate_legacy_config_secrets, SystemCredentialVault};
use anyhow::Result;
use rusqlite::{Connection, OptionalExtension};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::path::PathBuf;
use std::sync::Mutex;
use tauri_plugin_store::StoreExt;
use unicode_normalization::UnicodeNormalization;

const CUSTOM_SCENES_MAX_COUNT: usize = 100;
fn initialize_history_local_identity(connection: &Connection) -> Result<()> {
    let transaction = connection.unchecked_transaction()?;
    transaction.execute_batch(
        "CREATE TABLE IF NOT EXISTS history_identity_state (
            id INTEGER PRIMARY KEY CHECK(id = 1),
            rebuild_required INTEGER NOT NULL CHECK(rebuild_required IN (0, 1))
        );",
    )?;
    let rebuild_required: bool = transaction.query_row(
        "SELECT EXISTS (
            SELECT 1 FROM history_identity_state WHERE rebuild_required = 1
        )",
        [],
        |row| row.get(0),
    )?;
    if rebuild_required {
        anyhow::bail!("History synchronization baseline rebuild required");
    }
    transaction.execute_batch(
        "CREATE TABLE IF NOT EXISTS history_local_identity (
            history_id INTEGER PRIMARY KEY REFERENCES history(id) ON DELETE CASCADE,
            record_uuid TEXT NOT NULL UNIQUE
        );",
    )?;
    let has_revision = {
        let mut query = transaction.prepare("PRAGMA table_info(history_local_identity)")?;
        let columns = query
            .query_map([], |row| row.get::<_, String>(1))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        columns.iter().any(|column| column == "local_revision")
    };
    if !has_revision {
        transaction.execute_batch(
            "ALTER TABLE history_local_identity ADD COLUMN local_revision INTEGER NOT NULL
             DEFAULT 1 CHECK(local_revision BETWEEN 1 AND 9007199254740991);",
        )?;
    }
    let invalid_revision: bool = transaction.query_row(
        "SELECT EXISTS (
            SELECT 1 FROM history_local_identity
            WHERE typeof(local_revision) != 'integer'
               OR local_revision < 1 OR local_revision > 9007199254740991
        )",
        [],
        |row| row.get(0),
    )?;
    if invalid_revision {
        anyhow::bail!("Invalid existing history revision");
    }
    transaction.execute_batch(
        "CREATE TRIGGER IF NOT EXISTS validate_history_revision_insert
         BEFORE INSERT ON history_local_identity
         WHEN typeof(NEW.local_revision) != 'integer'
           OR NEW.local_revision < 1 OR NEW.local_revision > 9007199254740991
         BEGIN SELECT RAISE(ABORT, 'Invalid history revision'); END;
         CREATE TRIGGER IF NOT EXISTS validate_history_revision_update
         BEFORE UPDATE OF local_revision ON history_local_identity
         WHEN typeof(NEW.local_revision) != 'integer'
           OR NEW.local_revision < 1 OR NEW.local_revision > 9007199254740991
         BEGIN SELECT RAISE(ABORT, 'Invalid history revision'); END;
         DROP TRIGGER IF EXISTS track_history_text_revision;
         CREATE TRIGGER track_history_text_revision
         AFTER UPDATE OF raw_text, polished_text, mode, answer_text, created_at, duration_ms, language ON history
         WHEN OLD.raw_text IS NOT NEW.raw_text
           OR OLD.polished_text IS NOT NEW.polished_text
           OR OLD.mode IS NOT NEW.mode
           OR OLD.answer_text IS NOT NEW.answer_text
           OR OLD.created_at IS NOT NEW.created_at
           OR OLD.duration_ms IS NOT NEW.duration_ms
           OR OLD.language IS NOT NEW.language
         BEGIN
             SELECT CASE WHEN EXISTS (
                 SELECT 1 FROM history_local_identity
                 WHERE history_id = NEW.id AND local_revision >= 9007199254740991
             ) THEN RAISE(ABORT, 'History revision limit reached') END;
             UPDATE history_local_identity SET local_revision = local_revision + 1
             WHERE history_id = NEW.id;
         END;",
    )?;
    let missing = {
        let mut query = transaction.prepare(
            "SELECT id FROM history
             WHERE NOT EXISTS (
                 SELECT 1 FROM history_local_identity WHERE history_id = history.id
             )",
        )?;
        let rows = query
            .query_map([], |row| row.get::<_, i64>(0))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        rows
    };
    for id in missing {
        insert_history_local_identity(&transaction, id)?;
    }
    history_sync_queue::initialize(&transaction)?;
    transaction.commit()?;
    Ok(())
}

fn insert_history_local_identity(connection: &Connection, id: i64) -> Result<()> {
    connection.execute(
        "INSERT INTO history_local_identity(history_id, record_uuid) VALUES (?1, ?2)",
        rusqlite::params![id, uuid::Uuid::new_v4().to_string()],
    )?;
    Ok(())
}

const SCENE_ID_MAX_CHARS: usize = 120;
const SCENE_SOURCE_MAX_CHARS: usize = 24;
const SCENE_NAME_MAX_CHARS: usize = 80;
const SCENE_DESCRIPTION_MAX_CHARS: usize = 240;
pub(crate) const SCENE_PROMPT_MAX_CHARS: usize = 4000;
pub const DEFAULT_HISTORY_MAX_ENTRIES: u32 = 5000;
pub const MAX_BACKUP_DICTIONARY_ENTRIES: usize = 10_000;
pub const MAX_BACKUP_CORRECTION_RULES: usize = 10_000;
pub const MAX_HISTORY_RETENTION_DAYS: u32 = 3650;
/// History rows that kept a recording but never produced inserted text (Typeless `dismissed`,
/// empty and `error` items). They are excluded from stats and Paste last transcript.
pub const HISTORY_STATUS_DISMISSED: &str = "dismissed";
pub const HISTORY_STATUS_SILENT: &str = "silent";
pub const HISTORY_STATUS_RECOGNITION_FAILED: &str = "recognition_failed";
const RECORDING_ONLY_STATUSES_SQL: &str = "('dismissed', 'silent', 'recognition_failed')";

const HISTORY_ENTRY_SELECT: &str = "SELECT
                id,
                created_at,
                context_profile_id,
                context_label,
                context_icon_key,
                context_family,
                browser_access_status,
                provider_kind,
                raw_text,
                polished_text,
                language,
                duration_ms,
                active_scene_id,
                active_scene_source,
                active_scene_name,
                active_scene_prompt_chars,
                active_scene_prompt_truncated,
                output_status,
                output_error,
                mode,
                answer_text,
                EXISTS(SELECT 1 FROM history_audio WHERE history_audio.history_id = history.id),
                (SELECT sources FROM history_citations WHERE history_citations.history_id = history.id
                 AND length(sources) <= 100000),
                ask_intent,
                (SELECT supports FROM history_citation_supports
                 WHERE history_citation_supports.history_id = history.id)";

fn history_entry_from_row(row: &rusqlite::Row<'_>) -> rusqlite::Result<HistoryEntry> {
    Ok(HistoryEntry {
        has_audio: row.get(21)?,
        id: row.get(0)?,
        created_at: row.get(1)?,
        context_profile_id: row.get(2)?,
        context_label: row.get(3)?,
        context_icon_key: row.get(4)?,
        context_family: context_family_from_db(&row.get::<_, String>(5)?),
        browser_access_status: BrowserAccessStatus::from_history_value(
            row.get::<_, Option<String>>(6)?.as_deref(),
        ),
        provider_kind: HistoryProviderKind::from_db_value(&row.get::<_, String>(7)?),
        raw_text: row.get(8)?,
        polished_text: row.get(9)?,
        language: row.get(10)?,
        duration_ms: row.get(11)?,
        active_scene_id: row.get(12)?,
        active_scene_source: row.get(13)?,
        active_scene_name: row.get(14)?,
        active_scene_prompt_chars: row.get(15)?,
        active_scene_prompt_truncated: row.get(16)?,
        output_status: row.get(17)?,
        output_error: row.get(18)?,
        mode: row.get(19)?,
        answer_text: row.get(20)?,
        ask_intent: row.get(23)?,
        citation_supports: row
            .get::<_, Option<String>>(24)?
            .map(|value| {
                serde_json::from_str(&value).map_err(|error| {
                    rusqlite::Error::FromSqlConversionFailure(
                        24,
                        rusqlite::types::Type::Text,
                        Box::new(error),
                    )
                })
            })
            .transpose()?,
        citations: row
            .get::<_, Option<String>>(22)?
            .and_then(|sources| serde_json::from_str(&sources).ok()),
    })
}

pub fn is_recording_only_status(status: Option<&str>) -> bool {
    matches!(
        status,
        Some(HISTORY_STATUS_DISMISSED | HISTORY_STATUS_SILENT | HISTORY_STATUS_RECOGNITION_FAILED)
    )
}
pub const MAX_HOTKEY_BINDINGS_PER_ROLE: usize = 4;

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(default)]
pub struct CustomScene {
    pub id: String,
    pub name: String,
    pub description: String,
    pub prompt_template: String,
    pub created_at: String,
    pub updated_at: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(default)]
pub struct ActiveScene {
    pub id: String,
    pub source: String,
    pub name: String,
    pub prompt_template: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct FamilySceneAssignment {
    pub family: ContextFamily,
    pub scene_id: String,
}

impl Default for FamilySceneAssignment {
    fn default() -> Self {
        Self {
            family: ContextFamily::General,
            scene_id: String::new(),
        }
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq, Default)]
#[serde(default)]
pub struct SystemSceneOverride {
    pub id: String,
    pub prompt_template: String,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct ShortcutBinding {
    pub primary: String,
    pub modifiers: Vec<String>,
}

impl Default for ShortcutBinding {
    fn default() -> Self {
        binding_from_hotkey_string(default_dictation_hotkey()).unwrap_or_else(|| Self {
            primary: "/".to_string(),
            modifiers: vec!["Ctrl".to_string()],
        })
    }
}

impl ShortcutBinding {
    pub fn from_hotkey(value: &str) -> Option<Self> {
        binding_from_hotkey_string(value)
    }

    pub fn to_hotkey_string(&self) -> Option<String> {
        let mut binding = self.clone();
        if !binding.normalize() {
            return None;
        }

        let mut parts = binding.modifiers;
        parts.push(binding.primary);
        Some(parts.join("+"))
    }

    pub fn normalize(&mut self) -> bool {
        let Some(primary) = normalize_hotkey_primary(&self.primary) else {
            return false;
        };

        let mut seen_semantic = HashSet::new();
        let mut modifiers = Vec::new();
        for modifier in &self.modifiers {
            let Some((semantic, canonical)) = normalize_hotkey_modifier(modifier) else {
                return false;
            };
            if !seen_semantic.insert(semantic) {
                return false;
            }
            modifiers.push(canonical.to_string());
        }

        modifiers.sort_by_key(|modifier| hotkey_modifier_rank(modifier));
        self.primary = primary;
        self.modifiers = modifiers;
        true
    }
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default, rename_all = "camelCase")]
pub struct HotkeyConfig {
    pub dictation: ShortcutBinding,
    pub ask: Option<ShortcutBinding>,
    pub translate: Option<ShortcutBinding>,
    #[serde(default)]
    pub dictation_bindings: Vec<ShortcutBinding>,
    #[serde(default)]
    pub ask_bindings: Vec<ShortcutBinding>,
    #[serde(default)]
    pub translate_bindings: Vec<ShortcutBinding>,
    pub edit_selection: Option<ShortcutBinding>,
    pub switch_scene: Option<ShortcutBinding>,
    pub open_app: Option<ShortcutBinding>,
    /// Re-inserts the most recent transcript (Typeless "Paste last transcript").
    pub paste_last: Option<ShortcutBinding>,
    pub dictation_mode: String,
}

impl Default for HotkeyConfig {
    fn default() -> Self {
        let mut config = Self::from_legacy(
            default_dictation_hotkey(),
            default_ask_hotkey(),
            default_dictation_hotkey_mode(),
        );
        config.translate = default_translate_hotkey().and_then(ShortcutBinding::from_hotkey);
        config.translate_bindings = config.translate.clone().into_iter().collect();
        config.paste_last = ShortcutBinding::from_hotkey(default_paste_last_hotkey());
        config
    }
}

impl HotkeyConfig {
    pub fn from_legacy(dictation_hotkey: &str, ask_hotkey: &str, dictation_mode: &str) -> Self {
        let dictation = ShortcutBinding::from_hotkey(dictation_hotkey)
            .unwrap_or_else(|| ShortcutBinding::from_hotkey(default_dictation_hotkey()).unwrap());
        let ask = if ask_hotkey.trim().is_empty() {
            None
        } else {
            ShortcutBinding::from_hotkey(ask_hotkey)
                .or_else(|| ShortcutBinding::from_hotkey(default_ask_hotkey()))
        };
        let dictation_mode = normalize_hotkey_mode(dictation_mode).to_string();

        Self {
            dictation_bindings: vec![dictation.clone()],
            ask_bindings: ask.clone().into_iter().collect(),
            translate_bindings: Vec::new(),
            dictation,
            ask,
            translate: None,
            edit_selection: None,
            switch_scene: None,
            open_app: None,
            paste_last: None,
            dictation_mode,
        }
    }

    pub fn normalize(&mut self) {
        let had_dictation_list = !self.dictation_bindings.is_empty();
        let had_ask_list = !self.ask_bindings.is_empty();
        let had_translate_list = !self.translate_bindings.is_empty();

        normalize_binding_list(&mut self.dictation_bindings);
        normalize_binding_list(&mut self.ask_bindings);
        normalize_binding_list(&mut self.translate_bindings);

        if !had_dictation_list && self.dictation.normalize() {
            self.dictation_bindings.push(self.dictation.clone());
        }
        if self.dictation_bindings.is_empty() {
            self.dictation_bindings =
                vec![ShortcutBinding::from_hotkey(default_dictation_hotkey())
                    .expect("default dictation hotkey must be valid")];
        }

        if !had_ask_list {
            if let Some(mut ask) = self.ask.clone() {
                if ask.normalize() {
                    self.ask_bindings.push(ask);
                }
            }
        }
        if !had_translate_list {
            if let Some(mut translate) = self.translate.clone() {
                if translate.normalize() {
                    self.translate_bindings.push(translate);
                }
            }
        }

        self.dictation = self.dictation_bindings[0].clone();
        self.ask = self.ask_bindings.first().cloned();
        self.translate = self.translate_bindings.first().cloned();
        normalize_optional_binding(&mut self.edit_selection);
        normalize_optional_binding(&mut self.switch_scene);
        normalize_optional_binding(&mut self.open_app);
        normalize_optional_binding(&mut self.paste_last);
        self.dictation_mode = normalize_hotkey_mode(&self.dictation_mode).to_string();
    }
}

fn normalize_binding_list(bindings: &mut Vec<ShortcutBinding>) {
    let mut seen = HashSet::new();
    let mut normalized = Vec::new();
    for mut binding in bindings.drain(..) {
        if !binding.normalize() {
            continue;
        }
        let identity = shortcut_binding_identity(&binding);
        if !seen.insert(identity) {
            continue;
        }
        normalized.push(binding);
        if normalized.len() == MAX_HOTKEY_BINDINGS_PER_ROLE {
            break;
        }
    }
    *bindings = normalized;
}

fn shortcut_binding_identity(binding: &ShortcutBinding) -> String {
    let mut parts: Vec<&str> = binding
        .modifiers
        .iter()
        .map(|modifier| match modifier.as_str() {
            "Option" | "Alt" => "Alt",
            "Command" | "Super" => "Super",
            value => value,
        })
        .collect();
    parts.push(&binding.primary);
    parts.join("+")
}

pub const SUPPORTED_TRANSLATION_LANGUAGES: &[&str] = &[
    "en", "zh", "ja", "ko", "fr", "de", "es", "pt", "ru", "ar", "hi", "th", "vi", "it", "nl", "tr",
    "pl", "uk", "id", "ms",
];

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(default)]
pub struct TranslationConfig {
    pub targets: Vec<String>,
    pub active_target: String,
}

impl Default for TranslationConfig {
    fn default() -> Self {
        Self {
            targets: vec!["en".to_string()],
            active_target: "en".to_string(),
        }
    }
}

impl TranslationConfig {
    fn from_legacy(target_lang: &str) -> Self {
        let target = normalize_translation_code(target_lang).unwrap_or_else(|| "en".to_string());
        Self {
            targets: vec![target.clone()],
            active_target: target,
        }
    }

    fn normalize(&mut self, legacy_target: &str) {
        let mut normalized = Vec::new();
        for target in &self.targets {
            let Some(target) = normalize_translation_code(target) else {
                continue;
            };
            if !normalized.contains(&target) {
                normalized.push(target);
            }
            if normalized.len() == 5 {
                break;
            }
        }

        let legacy = normalize_translation_code(legacy_target);
        if normalized.is_empty() {
            normalized.push(legacy.clone().unwrap_or_else(|| "en".to_string()));
        }
        if let Some(legacy) = legacy.as_ref() {
            if !normalized.contains(legacy) && normalized.len() < 5 {
                normalized.push(legacy.clone());
            }
        }

        let requested_active = normalize_translation_code(&self.active_target);
        let active_target = legacy
            .filter(|target| normalized.contains(target))
            .or_else(|| requested_active.filter(|target| normalized.contains(target)))
            .unwrap_or_else(|| normalized[0].clone());

        self.targets = normalized;
        self.active_target = active_target;
    }
}

fn normalize_translation_code(value: &str) -> Option<String> {
    let normalized = value.trim().to_ascii_lowercase();
    SUPPORTED_TRANSLATION_LANGUAGES
        .contains(&normalized.as_str())
        .then_some(normalized)
}

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
    pub stt_volcengine_resource_id: String,
    pub stt_aliyun_qwen_region: String,
    pub llm_provider: String,
    pub llm_api_key: String,
    pub llm_model: String,
    pub llm_base_url: String,
    pub polish_enabled: bool,
    pub context_adaptation_enabled: bool,
    pub voice_routing_flags: crate::voice_intent::VoiceRoutingFlags,
    pub polish_style: String,
    pub polish_custom_prompt: String,
    pub polish_chinese_script: String,
    pub language_variants: std::collections::BTreeMap<String, String>,
    pub custom_scenes: Vec<CustomScene>,
    pub system_scene_overrides: Vec<SystemSceneOverride>,
    pub active_scene: Option<ActiveScene>,
    pub family_scene_assignments: Vec<FamilySceneAssignment>,
    pub translate_enabled: bool,
    pub target_lang: String,
    pub translation: TranslationConfig,
    pub hotkey: String,
    pub ask_hotkey: String,
    pub hotkey_mode: String,
    pub hotkeys: HotkeyConfig,
    pub output_mode: String,
    pub insertion_strategy: String,
    pub restore_clipboard_after_paste: bool,
    pub paste_shortcut: String,
    pub windows_sendinput_newline_mode: String,
    pub streaming_insert_enabled: bool,
    pub selected_text_enabled: bool,
    pub theme: String,
    pub auto_start: bool,
    pub close_to_tray: bool,
    pub start_minimized: bool,
    pub recording_limit_mode: crate::stt::capabilities::RecordingLimitMode,
    pub custom_recording_limit_seconds: u32,
    pub max_recording_seconds: u32,
    pub managed_stt_capability_state: Option<crate::stt::capabilities::ManagedSttCapabilityState>,
    pub history_enabled: bool,
    pub history_retention_days: u32,
    pub history_max_entries: u32,
    pub ui_language: String,
    pub capsule_auto_hide: bool,
    /// Input device name to record from; empty follows the system default microphone.
    pub microphone_device: String,
    /// Short start / stop / error cues played by the floating bar.
    pub interaction_sounds: bool,
    /// Mute the system output while recording so playback doesn't bleed into the mic.
    pub mute_background_audio: bool,
    /// macOS: keep the app icon in the Dock (Typeless `enableShowAppInDock`, default on).
    pub show_in_dock: bool,
    pub save_recording_audio: bool,
    /// macOS: learn names the user corrects right after dictating (Typeless auto-added words).
    pub auto_learn_dictionary: bool,
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
            stt_volcengine_resource_id: crate::stt::volcengine::VOLCENGINE_SEEDASR_RESOURCE_ID
                .to_string(),
            stt_aliyun_qwen_region:
                crate::stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_REGION_CHINA_MAINLAND.to_string(),
            llm_provider: "openrouter".to_string(),
            llm_api_key: String::new(),
            llm_model: "google/gemini-2.5-flash".to_string(),
            llm_base_url: "https://openrouter.ai/api/v1".to_string(),
            polish_enabled: true,
            context_adaptation_enabled: true,
            voice_routing_flags: crate::voice_intent::VoiceRoutingFlags::default(),
            polish_style: "clean".to_string(),
            polish_custom_prompt: String::new(),
            polish_chinese_script: "preserve".to_string(),
            language_variants: std::collections::BTreeMap::new(),
            custom_scenes: Vec::new(),
            system_scene_overrides: Vec::new(),
            active_scene: None,
            family_scene_assignments: Vec::new(),
            translate_enabled: false,
            target_lang: "en".to_string(),
            translation: TranslationConfig::default(),
            hotkey: default_dictation_hotkey().to_string(),
            ask_hotkey: default_ask_hotkey().to_string(),
            hotkey_mode: default_dictation_hotkey_mode().to_string(),
            hotkeys: HotkeyConfig::default(),
            output_mode: "keyboard".to_string(),
            insertion_strategy: "auto".to_string(),
            restore_clipboard_after_paste: true,
            paste_shortcut: "ctrlV".to_string(),
            windows_sendinput_newline_mode: "enter".to_string(),
            streaming_insert_enabled: false,
            selected_text_enabled: false,
            theme: "system".to_string(),
            auto_start: true,
            close_to_tray: true,
            start_minimized: false,
            recording_limit_mode: crate::stt::capabilities::RecordingLimitMode::Auto,
            custom_recording_limit_seconds: 600,
            max_recording_seconds: 30,
            managed_stt_capability_state: None,
            history_enabled: true,
            history_retention_days: 0,
            history_max_entries: DEFAULT_HISTORY_MAX_ENTRIES,
            ui_language: "en".to_string(),
            capsule_auto_hide: false,
            microphone_device: String::new(),
            interaction_sounds: true,
            mute_background_audio: false,
            show_in_dock: true,
            save_recording_audio: true,
            auto_learn_dictionary: true,
        }
    }
}

impl AppConfig {
    pub fn new_install_default() -> Self {
        let mut config = Self {
            capsule_auto_hide: true,
            mute_background_audio: true,
            ..Self::default()
        };
        // New installs get tap-to-talk with long-press guidance; existing users keep their mode.
        config.hotkeys.dictation_mode = "auto".to_string();
        config.hotkey_mode = "auto".to_string();
        config
    }

    fn migrate_legacy_platform_hotkeys(&mut self) {
        #[cfg(target_os = "macos")]
        if self.hotkey == "Alt+/" {
            self.hotkey = "Option+/".to_string();
        }
        #[cfg(target_os = "macos")]
        if self.hotkey == "Option+/" && self.hotkey_mode == "hold" {
            self.hotkey = "Fn".to_string();
            self.hotkey_mode = "toggle".to_string();
        }
        #[cfg(target_os = "macos")]
        if self.ask_hotkey == "Alt+Shift+/"
            || self.ask_hotkey == "Option+Shift+/"
            || self.ask_hotkey == "Command+Shift+/"
            || self.ask_hotkey == "Command+/"
            || self.ask_hotkey == "Command+。"
            || self.ask_hotkey == "Command+."
        {
            self.ask_hotkey = default_ask_hotkey().to_string();
        }
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        if self.ask_hotkey == "Ctrl+Shift+/"
            || self.ask_hotkey == "Control+Shift+/"
            || self.ask_hotkey == "Ctrl+/"
            || self.ask_hotkey == "Control+/"
            || self.ask_hotkey == "Ctrl+."
            || self.ask_hotkey == "Control+."
            || self.ask_hotkey == "RightAlt+Space"
        {
            self.ask_hotkey = default_ask_hotkey().to_string();
        }
    }

    fn normalize_hotkey_settings(&mut self) {
        self.hotkeys.dictation_mode =
            normalize_hotkey_mode(&self.hotkeys.dictation_mode).to_string();
        self.sync_legacy_hotkey_fields_from_typed();
    }

    fn sync_legacy_hotkey_fields_from_typed(&mut self) {
        self.hotkeys.normalize();
        self.hotkey = self
            .hotkeys
            .dictation
            .to_hotkey_string()
            .unwrap_or_else(|| default_dictation_hotkey().to_string());
        self.ask_hotkey = self
            .hotkeys
            .ask
            .as_ref()
            .and_then(ShortcutBinding::to_hotkey_string)
            .unwrap_or_default();
        self.hotkey_mode = self.hotkeys.dictation_mode.clone();
    }

    pub(crate) fn normalize_values(&mut self) {
        if !matches!(
            self.stt_aliyun_qwen_region.as_str(),
            crate::stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_REGION_CHINA_MAINLAND
                | crate::stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_REGION_INTERNATIONAL
        ) {
            self.stt_aliyun_qwen_region =
                crate::stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_REGION_CHINA_MAINLAND.to_string();
        }
        self.polish_style = normalize_polish_style(&self.polish_style).to_string();
        self.polish_custom_prompt = sanitize_polish_custom_prompt(&self.polish_custom_prompt);
        self.polish_chinese_script = "preserve".to_string();
        crate::language_variants::normalize(&mut self.language_variants);
        sanitize_custom_scenes(&mut self.custom_scenes);
        sanitize_system_scene_overrides(&mut self.system_scene_overrides);
        sanitize_active_scene(&mut self.active_scene);
        sanitize_family_scene_assignments(
            &mut self.family_scene_assignments,
            &self.custom_scenes,
            &self.system_scene_overrides,
        );
        self.translation.normalize(&self.target_lang);
        self.target_lang = self.translation.active_target.clone();
        self.normalize_insertion_strategy();
        self.normalize_paste_shortcut();
        self.normalize_windows_sendinput_newline_mode();
        self.normalize_hotkey_settings();
        self.normalize_history_settings();
        self.recompute_recording_limit_mirror();
    }

    fn normalize_insertion_strategy(&mut self) {
        if !matches!(
            self.insertion_strategy.as_str(),
            "auto" | "keyboard" | "clipboardPaste" | "clipboardCopyOnly" | "windowsSendInput"
        ) {
            self.insertion_strategy = "auto".to_string();
        }

        self.output_mode =
            legacy_output_mode_for_insertion_strategy(&self.insertion_strategy).to_string();
    }

    fn normalize_paste_shortcut(&mut self) {
        if !matches!(
            self.paste_shortcut.as_str(),
            "ctrlV" | "ctrlShiftV" | "shiftInsert"
        ) {
            self.paste_shortcut = "ctrlV".to_string();
        }
    }

    fn normalize_windows_sendinput_newline_mode(&mut self) {
        if !matches!(
            self.windows_sendinput_newline_mode.as_str(),
            "enter" | "shiftEnter" | "crlf"
        ) {
            self.windows_sendinput_newline_mode = "enter".to_string();
        }
    }

    fn normalize_history_settings(&mut self) {
        self.history_retention_days = self.history_retention_days.min(MAX_HISTORY_RETENTION_DAYS);
        self.history_max_entries = self
            .history_max_entries
            .clamp(1, DEFAULT_HISTORY_MAX_ENTRIES);
    }

    pub fn history_retention_policy(&self) -> HistoryRetentionPolicy {
        HistoryRetentionPolicy {
            enabled: self.history_enabled,
            max_entries: self.history_max_entries,
            retention_days: self.history_retention_days,
        }
    }

    pub(crate) fn recompute_recording_limit_mirror(&mut self) {
        let resolved = crate::stt::capabilities::resolve_recording_limit(
            self,
            None,
            chrono::Utc::now().timestamp(),
        );
        self.max_recording_seconds = resolved.effective_max_seconds;
    }

    pub(crate) fn clamp_recording_limit_intent_for_save(&mut self) {
        self.recompute_recording_limit_mirror();
        if self.recording_limit_mode == crate::stt::capabilities::RecordingLimitMode::Custom {
            self.custom_recording_limit_seconds = self.max_recording_seconds;
        } else if self.custom_recording_limit_seconds == 0 {
            self.custom_recording_limit_seconds = 600;
        }
    }

    pub fn from_stored_value(value: serde_json::Value) -> Result<Self, serde_json::Error> {
        if !value.is_object() {
            return Err(<serde_json::Error as serde::de::Error>::custom(
                "Application settings must be an object",
            ));
        }
        let mut value = value;
        if let Some(object) = value.as_object_mut() {
            if !object.contains_key("language_variants") {
                let variant = match object
                    .get("polish_chinese_script")
                    .and_then(serde_json::Value::as_str)
                {
                    Some("simplified") => Some("zh-CN"),
                    Some("traditional") => Some("zh-TW"),
                    _ => None,
                };
                if let Some(variant) = variant {
                    object.insert(
                        "language_variants".to_string(),
                        serde_json::json!({ "zh": variant }),
                    );
                }
            }
            if object
                .get("system_scene_overrides")
                .is_some_and(serde_json::Value::is_null)
            {
                object.insert(
                    "system_scene_overrides".to_string(),
                    serde_json::Value::Array(Vec::new()),
                );
            }
        }
        let has_capsule_auto_hide = value
            .as_object()
            .is_some_and(|object| object.contains_key("capsule_auto_hide"));
        let has_insertion_strategy = value
            .as_object()
            .is_some_and(|object| object.contains_key("insertion_strategy"));
        let has_hotkeys = value
            .as_object()
            .is_some_and(|object| object.contains_key("hotkeys"));
        let has_translation = value
            .as_object()
            .is_some_and(|object| object.contains_key("translation"));
        let has_legacy_target = value
            .as_object()
            .is_some_and(|object| object.contains_key("target_lang"));
        let has_recording_limit_mode = value
            .as_object()
            .is_some_and(|object| object.contains_key("recording_limit_mode"));
        let has_custom_recording_limit = value
            .as_object()
            .is_some_and(|object| object.contains_key("custom_recording_limit_seconds"));
        let mut config: Self = serde_json::from_value(value)?;
        if !has_recording_limit_mode {
            if config.max_recording_seconds == 0 || config.max_recording_seconds == 30 {
                config.recording_limit_mode = crate::stt::capabilities::RecordingLimitMode::Auto;
                config.custom_recording_limit_seconds = 600;
            } else {
                config.recording_limit_mode = crate::stt::capabilities::RecordingLimitMode::Custom;
                config.custom_recording_limit_seconds = config.max_recording_seconds;
            }
        } else if !has_custom_recording_limit {
            config.custom_recording_limit_seconds = 600;
        }
        if !has_translation {
            config.translation = TranslationConfig::from_legacy(&config.target_lang);
        } else if !has_legacy_target {
            config.target_lang = config.translation.active_target.clone();
        }
        if !has_capsule_auto_hide {
            config.capsule_auto_hide = false;
        }
        if !has_insertion_strategy {
            config.insertion_strategy =
                insertion_strategy_from_legacy_output_mode(&config.output_mode).to_string();
        }
        if !has_hotkeys {
            config.migrate_legacy_platform_hotkeys();
            config.hotkeys =
                HotkeyConfig::from_legacy(&config.hotkey, &config.ask_hotkey, &config.hotkey_mode);
        } else {
            config.sync_legacy_hotkey_fields_from_typed();
        }
        config.normalize_values();
        Ok(config)
    }
}

fn default_dictation_hotkey() -> &'static str {
    #[cfg(target_os = "macos")]
    {
        "Fn"
    }
    #[cfg(target_os = "windows")]
    {
        "RightAlt"
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        "Ctrl+/"
    }
}

fn default_dictation_hotkey_mode() -> &'static str {
    #[cfg(target_os = "macos")]
    {
        "toggle"
    }
    #[cfg(not(target_os = "macos"))]
    {
        "hold"
    }
}

fn default_ask_hotkey() -> &'static str {
    #[cfg(target_os = "macos")]
    {
        "Fn+Space"
    }
    #[cfg(target_os = "windows")]
    {
        "RightAlt+Space"
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        "Ctrl+."
    }
}

/// Typeless default: Ctrl+Cmd+V on macOS, LeftCtrl+RightShift+V elsewhere.
fn default_paste_last_hotkey() -> &'static str {
    #[cfg(target_os = "macos")]
    {
        "Ctrl+Command+V"
    }
    #[cfg(not(target_os = "macos"))]
    {
        "Ctrl+Shift+V"
    }
}

fn default_translate_hotkey() -> Option<&'static str> {
    #[cfg(target_os = "macos")]
    {
        Some("Fn+LeftShift")
    }
    #[cfg(target_os = "windows")]
    {
        Some("RightAlt+RightShift")
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        Some("Ctrl+Shift+/")
    }
}

fn normalize_hotkey_mode(value: &str) -> &'static str {
    match value {
        "toggle" => "toggle",
        "auto" => "auto",
        _ => "hold",
    }
}

fn binding_from_hotkey_string(value: &str) -> Option<ShortcutBinding> {
    let parts: Vec<&str> = value
        .split('+')
        .map(str::trim)
        .filter(|part| !part.is_empty())
        .collect();
    if parts.is_empty() {
        return None;
    }

    let primary = normalize_hotkey_primary(parts.last()?)?;
    let mut modifiers = Vec::new();
    let mut seen_semantic = HashSet::new();
    for part in &parts[..parts.len() - 1] {
        let (semantic, canonical) = normalize_hotkey_modifier(part)?;
        if !seen_semantic.insert(semantic) {
            return None;
        }
        modifiers.push(canonical.to_string());
    }

    modifiers.sort_by_key(|modifier| hotkey_modifier_rank(modifier));
    Some(ShortcutBinding { primary, modifiers })
}

fn normalize_optional_binding(binding: &mut Option<ShortcutBinding>) {
    if let Some(value) = binding.as_mut() {
        if !value.normalize() {
            *binding = None;
        }
    }
}

fn normalize_hotkey_modifier(value: &str) -> Option<(&'static str, &'static str)> {
    match value.trim().to_lowercase().as_str() {
        "fn" | "function" => Some(("fn", "Fn")),
        "rightalt" | "right_alt" | "right-alt" | "altright" | "alt_right" | "alt-right" => {
            Some(("rightalt", "RightAlt"))
        }
        "ctrl" | "control" => Some(("ctrl", "Ctrl")),
        "shift" => Some(("shift", "Shift")),
        "alt" => Some(("alt", "Alt")),
        "option" => Some(("alt", "Option")),
        "meta" | "super" | "win" => Some(("super", "Super")),
        "cmd" | "command" => Some(("super", "Command")),
        _ => None,
    }
}

fn hotkey_modifier_rank(value: &str) -> u8 {
    match value {
        "Fn" | "RightAlt" => 0,
        "Command" | "Super" => 0,
        "Ctrl" => 1,
        "Option" | "Alt" => 2,
        "Shift" => 3,
        _ => 9,
    }
}

fn normalize_hotkey_primary(value: &str) -> Option<String> {
    let trimmed = value.trim();
    if trimmed.is_empty() {
        return None;
    }

    let normalized = match trimmed.to_lowercase().as_str() {
        "space" => "Space".to_string(),
        "tab" => "Tab".to_string(),
        "enter" | "return" => "Enter".to_string(),
        "backspace" => "Backspace".to_string(),
        "escape" | "esc" => "Escape".to_string(),
        "delete" => "Delete".to_string(),
        "insert" => "Insert".to_string(),
        "home" => "Home".to_string(),
        "end" => "End".to_string(),
        "leftshift" | "left_shift" | "left-shift" | "shiftleft" | "shift_left" | "shift-left" => {
            "LeftShift".to_string()
        }
        "rightshift" | "right_shift" | "right-shift" | "shiftright" | "shift_right"
        | "shift-right" => "RightShift".to_string(),
        "pageup" => "PageUp".to_string(),
        "pagedown" => "PageDown".to_string(),
        "arrowup" | "up" => "Up".to_string(),
        "arrowdown" | "down" => "Down".to_string(),
        "arrowleft" | "left" => "Left".to_string(),
        "arrowright" | "right" => "Right".to_string(),
        "fn" | "function" => "Fn".to_string(),
        "rightalt" | "right_alt" | "right-alt" | "altright" | "alt_right" | "alt-right" => {
            "RightAlt".to_string()
        }
        "slash" | "/" => "/".to_string(),
        "backslash" | "\\" => "\\".to_string(),
        "period" | "." | "。" => ".".to_string(),
        "comma" | "," => ",".to_string(),
        "semicolon" | ";" => ";".to_string(),
        "quote" | "'" => "'".to_string(),
        "backquote" | "`" => "`".to_string(),
        "minus" | "-" => "-".to_string(),
        "equal" | "=" => "=".to_string(),
        "bracketleft" | "[" => "[".to_string(),
        "bracketright" | "]" => "]".to_string(),
        other
            if matches!(
                other,
                "f1" | "f2"
                    | "f3"
                    | "f4"
                    | "f5"
                    | "f6"
                    | "f7"
                    | "f8"
                    | "f9"
                    | "f10"
                    | "f11"
                    | "f12"
            ) =>
        {
            other.to_uppercase()
        }
        other if other.len() == 1 => {
            let ch = other.chars().next()?;
            if ch.is_ascii_alphanumeric() {
                ch.to_ascii_uppercase().to_string()
            } else {
                return None;
            }
        }
        _ => return None,
    };

    Some(normalized)
}

fn insertion_strategy_from_legacy_output_mode(output_mode: &str) -> &'static str {
    if output_mode == "clipboard" {
        "clipboardPaste"
    } else {
        "auto"
    }
}

fn legacy_output_mode_for_insertion_strategy(strategy: &str) -> &'static str {
    match strategy {
        "clipboardPaste" | "clipboardCopyOnly" => "clipboard",
        _ => "keyboard",
    }
}

const POLISH_CUSTOM_PROMPT_MAX_CHARS: usize = 2000;

fn normalize_polish_style(value: &str) -> &'static str {
    match value.trim() {
        "minimal" => "minimal",
        "clean" => "clean",
        "structured" => "structured",
        "professional" => "professional",
        "custom" => "custom",
        _ => "clean",
    }
}

fn sanitize_polish_custom_prompt(value: &str) -> String {
    value
        .replace('\0', "")
        .trim()
        .chars()
        .take(POLISH_CUSTOM_PROMPT_MAX_CHARS)
        .collect()
}

fn sanitize_scene_string(value: &str, max_chars: usize) -> String {
    value
        .replace('\0', "")
        .trim()
        .chars()
        .take(max_chars)
        .collect()
}

fn sanitize_custom_scenes(scenes: &mut Vec<CustomScene>) {
    for scene in scenes.iter_mut() {
        scene.id = sanitize_scene_string(&scene.id, SCENE_ID_MAX_CHARS);
        scene.name = sanitize_scene_string(&scene.name, SCENE_NAME_MAX_CHARS);
        scene.description = sanitize_scene_string(&scene.description, SCENE_DESCRIPTION_MAX_CHARS);
        scene.prompt_template =
            sanitize_scene_string(&scene.prompt_template, SCENE_PROMPT_MAX_CHARS);
        scene.created_at = sanitize_scene_string(&scene.created_at, SCENE_ID_MAX_CHARS);
        scene.updated_at = sanitize_scene_string(&scene.updated_at, SCENE_ID_MAX_CHARS);
    }
    scenes.retain(|scene| {
        !scene.id.is_empty() && !scene.name.is_empty() && !scene.prompt_template.is_empty()
    });
    scenes.truncate(CUSTOM_SCENES_MAX_COUNT);
}

fn system_scene_prompt(scene_id: &str) -> Option<&'static str> {
    match scene_id {
        "system_email" => Some(
            "Email system mode: produce an email body when there is enough content. Use a greeting when the recipient is spoken, concise body paragraphs, and a light closing when appropriate. Do not generate a subject unless explicitly requested.",
        ),
        "system_work_chat" => Some(
            "Work chat system mode: keep it casual and concise. Use short sentences or simple line breaks when helpful. No greeting or sign-off.",
        ),
        "system_personal_chat" => Some(
            "Personal chat system mode: keep the user's casual voice and short-message rhythm; do not turn it into business writing.",
        ),
        "system_document" => Some(
            "Document system mode: use coherent paragraphs. Use short headings or bullet points when the spoken structure has sections, takeaways, or multiple items.",
        ),
        "system_project_management" => Some(
            "Project update system mode: format as a compact update with bullets for progress, blockers, and next steps when spoken. Do not invent owners, deadlines, or ticket fields.",
        ),
        "system_developer_collaboration" => Some(
            "Engineering note system mode: format as a concise review or engineering note. Use bullets for issue, impact, and suggestion when helpful. Preserve technical identifiers exactly.",
        ),
        "system_prompt_or_code" => Some(
            "Prompt/code system mode: make the spoken request explicit and usable. Use compact bullets for goal, constraints, and output shape when implied, but never invent code or unstated requirements.",
        ),
        "system_support" => Some(
            "Support reply system mode: write a clear, empathetic reply. Use short paragraphs or numbered steps when next actions are spoken. Do not invent policy, refund, or resolution claims.",
        ),
        "system_social" => Some(
            "Social post system mode: keep the user's voice and make it readable as a short post. No hashtags, emoji, or calls to action unless spoken.",
        ),
        _ => None,
    }
}

fn sanitize_system_scene_overrides(overrides: &mut Vec<SystemSceneOverride>) {
    let mut seen = HashSet::new();
    for item in overrides.iter_mut() {
        item.id = sanitize_scene_string(&item.id, SCENE_ID_MAX_CHARS);
        item.prompt_template = sanitize_scene_string(&item.prompt_template, SCENE_PROMPT_MAX_CHARS);
    }
    overrides.retain(|item| {
        !item.prompt_template.is_empty()
            && system_scene_prompt(&item.id).is_some()
            && seen.insert(item.id.clone())
    });
}

fn sanitize_active_scene(active_scene: &mut Option<ActiveScene>) {
    if let Some(scene) = active_scene.as_mut() {
        scene.id = sanitize_scene_string(&scene.id, SCENE_ID_MAX_CHARS);
        scene.source = sanitize_scene_string(&scene.source, SCENE_SOURCE_MAX_CHARS);
        scene.name = sanitize_scene_string(&scene.name, SCENE_NAME_MAX_CHARS);
        scene.prompt_template =
            sanitize_scene_string(&scene.prompt_template, SCENE_PROMPT_MAX_CHARS);

        if scene.id.is_empty()
            || scene.source.is_empty()
            || scene.name.is_empty()
            || scene.prompt_template.is_empty()
        {
            *active_scene = None;
        }
    }
}

pub(crate) fn builtin_scene_prompt(scene_id: &str) -> Option<&'static str> {
    match scene_id {
        "builtin_clean_dictation" => Some(
            "Lightly clean the transcript for readability while preserving the speaker meaning, wording choices, and factual content. Do not add new information.",
        ),
        "builtin_meeting_notes" => Some(
            "Rewrite the transcript as concise meeting notes with clear bullets, decisions, and action items. Preserve factual content and do not invent details.",
        ),
        "builtin_professional_email" => Some(
            "Rewrite the transcript as a concise professional email body. Use a greeting when the recipient is spoken, clear body paragraphs, and a light closing when appropriate. Do not add facts or generate a subject unless requested.",
        ),
        "builtin_support_reply" => Some(
            "Rewrite the transcript as a helpful customer support reply. Acknowledge the issue, give clear next steps, and avoid promising anything not stated.",
        ),
        "builtin_technical_explanation" => Some(
            "Rewrite the transcript as a clear technical explanation. Preserve precise terms, organize the reasoning, and avoid oversimplifying important details.",
        ),
        "builtin_code_comment" => Some(
            "Rewrite the transcript as a concise code review comment or inline engineering note. Keep it specific, actionable, and respectful.",
        ),
        "builtin_product_spec_notes" => Some(
            "Rewrite the transcript as product spec notes with goals, requirements, edge cases, and open questions. Do not invent decisions that were not spoken.",
        ),
        _ => None,
    }
}

pub(crate) fn scene_prompt_for_id(config: &AppConfig, scene_id: &str) -> Option<String> {
    let scene_id = scene_id.trim();
    config
        .system_scene_overrides
        .iter()
        .find(|scene| scene.id == scene_id)
        .map(|scene| scene.prompt_template.clone())
        .or_else(|| system_scene_prompt(scene_id).map(str::to_string))
        .or_else(|| builtin_scene_prompt(scene_id).map(str::to_string))
        .or_else(|| {
            config
                .custom_scenes
                .iter()
                .find(|scene| scene.id == scene_id)
                .map(|scene| scene.prompt_template.clone())
        })
}

fn default_system_scene_id_for_family(family: ContextFamily) -> Option<&'static str> {
    match family {
        ContextFamily::Email => Some("system_email"),
        ContextFamily::WorkChat => Some("system_work_chat"),
        ContextFamily::PersonalChat => Some("system_personal_chat"),
        ContextFamily::Document => Some("system_document"),
        ContextFamily::ProjectManagement => Some("system_project_management"),
        ContextFamily::DeveloperCollaboration => Some("system_developer_collaboration"),
        ContextFamily::PromptOrCode => Some("system_prompt_or_code"),
        ContextFamily::Support => Some("system_support"),
        ContextFamily::Social => Some("system_social"),
        ContextFamily::General => None,
    }
}

pub(crate) fn family_scene_prompt(config: &AppConfig, family: ContextFamily) -> Option<String> {
    let scene_id = config
        .family_scene_assignments
        .iter()
        .find(|assignment| assignment.family == family)
        .map(|assignment| assignment.scene_id.as_str())
        .or_else(|| default_system_scene_id_for_family(family))?;
    scene_prompt_for_id(config, scene_id)
}

pub(crate) fn automatic_scene_prompt(
    config: &AppConfig,
    family: ContextFamily,
    mapped_scene_id: Option<&str>,
) -> Option<String> {
    if config.active_scene.is_some() {
        return None;
    }

    mapped_scene_id
        .and_then(|scene_id| scene_prompt_for_id(config, scene_id))
        .or_else(|| family_scene_prompt(config, family))
}

fn sanitize_family_scene_assignments(
    assignments: &mut Vec<FamilySceneAssignment>,
    custom_scenes: &[CustomScene],
    system_scene_overrides: &[SystemSceneOverride],
) {
    let valid_custom_ids: HashSet<&str> = custom_scenes
        .iter()
        .map(|scene| scene.id.as_str())
        .collect();
    let mut seen_families = HashSet::new();

    for assignment in assignments.iter_mut() {
        assignment.scene_id = sanitize_scene_string(&assignment.scene_id, SCENE_ID_MAX_CHARS);
    }
    assignments.retain(|assignment| {
        !assignment.scene_id.is_empty()
            && (builtin_scene_prompt(&assignment.scene_id).is_some()
                || system_scene_prompt(&assignment.scene_id).is_some()
                || system_scene_overrides
                    .iter()
                    .any(|scene| scene.id == assignment.scene_id)
                || valid_custom_ids.contains(assignment.scene_id.as_str()))
            && seen_families.insert(assignment.family)
    });
}

// ─── ConfigManager (tauri-plugin-store backed) ───

pub struct ConfigManager {
    app_handle: tauri::AppHandle,
    cache: Mutex<Option<AppConfig>>,
    history_privacy_verified: std::sync::atomic::AtomicBool,
    input_commit_revision: std::sync::atomic::AtomicU64,
    config_epoch: String,
}

fn checked_config_revision(current: u64) -> Result<u64> {
    current
        .checked_add(1)
        .filter(|revision| *revision <= 9_007_199_254_740_991)
        .ok_or_else(|| anyhow::anyhow!("Application settings revision limit reached"))
}

pub(crate) fn validate_settings_file(path: &std::path::Path) -> Result<()> {
    use std::io::Read;
    let file = match std::fs::File::open(path) {
        Ok(file) => file,
        Err(error) if error.kind() == std::io::ErrorKind::NotFound => return Ok(()),
        Err(_) => anyhow::bail!("Could not read application settings"),
    };
    let mut bytes = Vec::new();
    file.take(8 * 1024 * 1024 + 1)
        .read_to_end(&mut bytes)
        .map_err(|_| anyhow::anyhow!("Could not read application settings"))?;
    if bytes.len() > 8 * 1024 * 1024 {
        anyhow::bail!("Application settings are too large");
    }
    let value: serde_json::Value = serde_json::from_slice(&bytes)
        .map_err(|_| anyhow::anyhow!("Application settings are invalid"))?;
    let object = value
        .as_object()
        .ok_or_else(|| anyhow::anyhow!("Application settings are invalid"))?;
    if let Some(config) = object.get("app_config") {
        AppConfig::from_stored_value(config.clone())
            .map_err(|_| anyhow::anyhow!("Application settings are invalid"))?;
    }
    if object
        .get("onboarding_completed")
        .is_some_and(|marker| !marker.is_boolean())
    {
        anyhow::bail!("Application onboarding settings are invalid");
    }
    Ok(())
}

fn with_confirmed_retention_cache<Output>(
    cache: &Mutex<Option<AppConfig>>,
    verified: bool,
    enabled: bool,
    retention_days: u32,
    action: impl FnOnce(&HistoryRetentionPolicy) -> Result<Output>,
) -> Result<Output> {
    let guard = cache
        .lock()
        .map_err(|_| anyhow::anyhow!("Application settings are unavailable"))?;
    let current = guard
        .as_ref()
        .ok_or_else(|| anyhow::anyhow!("Application settings are unavailable"))?;
    if !verified
        || current.history_enabled != enabled
        || current.history_retention_days != retention_days
    {
        anyhow::bail!("History retention settings changed before cleanup");
    }
    action(&current.history_retention_policy())
}

fn history_privacy_from_cache(
    cache: Option<&AppConfig>,
    verified: bool,
) -> Option<(HistoryRetentionPolicy, bool)> {
    if !verified {
        return None;
    }
    cache.map(|config| {
        (
            config.history_retention_policy(),
            config.save_recording_audio,
        )
    })
}

fn with_history_privacy_cache<Output>(
    cache: &Mutex<Option<AppConfig>>,
    verified: bool,
    action: impl FnOnce(Option<(HistoryRetentionPolicy, bool)>) -> Result<Output>,
) -> Result<Output> {
    let guard = cache
        .lock()
        .map_err(|_| anyhow::anyhow!("Application settings are unavailable"))?;
    action(history_privacy_from_cache(guard.as_ref(), verified))
}

#[cfg(test)]
fn update_cached_config(
    cache: &Mutex<Option<AppConfig>>,
    update: impl FnOnce(&mut AppConfig),
    persist: impl FnOnce(&mut AppConfig) -> Result<()>,
) -> Result<AppConfig> {
    try_update_cached_config(
        cache,
        |config| {
            update(config);
            Ok(())
        },
        persist,
    )
}

#[cfg(test)]
fn try_update_cached_config(
    cache: &Mutex<Option<AppConfig>>,
    update: impl FnOnce(&mut AppConfig) -> Result<()>,
    persist: impl FnOnce(&mut AppConfig) -> Result<()>,
) -> Result<AppConfig> {
    let revision = std::sync::atomic::AtomicU64::new(0);
    try_update_cached_config_commit(cache, &revision, update, |config| {
        persist(config)?;
        Ok(0)
    })
    .map(|(_, config)| config)
}

#[cfg(test)]
fn try_update_cached_config_commit(
    cache: &Mutex<Option<AppConfig>>,
    revision: &std::sync::atomic::AtomicU64,
    update: impl FnOnce(&mut AppConfig) -> Result<()>,
    persist: impl FnOnce(&mut AppConfig) -> Result<u64>,
) -> Result<(u64, AppConfig)> {
    update_cached_config_commit(cache, revision, update, persist, false)
}

fn update_cached_config_commit(
    cache: &Mutex<Option<AppConfig>>,
    revision: &std::sync::atomic::AtomicU64,
    update: impl FnOnce(&mut AppConfig) -> Result<()>,
    persist: impl FnOnce(&mut AppConfig) -> Result<u64>,
    persist_unchanged: bool,
) -> Result<(u64, AppConfig)> {
    let mut guard = cache
        .lock()
        .map_err(|_| anyhow::anyhow!("Application settings are unavailable"))?;
    let mut config = guard
        .clone()
        .ok_or_else(|| anyhow::anyhow!("Application settings are unavailable"))?;
    let previous = serde_json::to_value(&config)?;
    update(&mut config)?;
    config.normalize_values();
    if !persist_unchanged && serde_json::to_value(&config)? == previous {
        return Ok((revision.load(std::sync::atomic::Ordering::SeqCst), config));
    }
    let committed_revision = persist(&mut config)?;
    *guard = Some(config.clone());
    Ok((committed_revision, config))
}

#[cfg(any(target_os = "macos", test))]
fn with_learning_setting_cache<Output>(
    cache: &Mutex<Option<AppConfig>>,
    action: impl FnOnce() -> Result<Output>,
) -> Result<Option<Output>> {
    let guard = cache
        .lock()
        .map_err(|_| anyhow::anyhow!("Application settings are unavailable"))?;
    let config = guard
        .as_ref()
        .ok_or_else(|| anyhow::anyhow!("Application settings are unavailable"))?;
    if !config.auto_learn_dictionary {
        return Ok(None);
    }
    action().map(Some)
}

pub(crate) fn config_with_native_managed_state(
    mut incoming: AppConfig,
    current: Option<&AppConfig>,
) -> AppConfig {
    incoming.managed_stt_capability_state =
        current.and_then(|config| config.managed_stt_capability_state.clone());
    incoming
}

pub(crate) fn validate_custom_scene_transition(
    current: Option<&AppConfig>,
    candidate: &AppConfig,
) -> Result<()> {
    let catalog = AppConfig::default();
    for scene in &candidate.custom_scenes {
        let existing = current.is_some_and(|config| {
            config
                .custom_scenes
                .iter()
                .any(|previous| previous.id == scene.id)
        });
        if !existing && scene_prompt_for_id(&catalog, &scene.id).is_some() {
            return Err(anyhow::anyhow!("Custom scene identifier is reserved"));
        }
    }
    Ok(())
}

impl ConfigManager {
    pub(crate) fn with_saved_backup_config<Output>(
        &self,
        action: impl FnOnce(&AppConfig) -> Result<Output>,
    ) -> Result<Output> {
        let cache = self
            .cache
            .lock()
            .map_err(|_| anyhow::anyhow!("Application settings are unavailable"))?;
        let config = cache
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("Application settings are unavailable"))?;
        action(config)
    }

    pub(crate) fn config_epoch(&self) -> &str {
        &self.config_epoch
    }

    pub(crate) fn cached_input_config(&self) -> Result<AppConfig> {
        self.cached_input_commit().map(|(_, config)| config)
    }

    pub(crate) fn cached_input_commit(&self) -> Result<(u64, AppConfig)> {
        let cache = self
            .cache
            .try_lock()
            .map_err(|_| anyhow::anyhow!("Application settings are being updated"))?;
        let config = cache
            .clone()
            .ok_or_else(|| anyhow::anyhow!("Application settings are unavailable"))?;
        Ok((
            self.input_commit_revision
                .load(std::sync::atomic::Ordering::SeqCst),
            config,
        ))
    }

    #[cfg(target_os = "macos")]
    pub(crate) fn with_current_learning_enabled<Output>(
        &self,
        action: impl FnOnce() -> Result<Output>,
    ) -> Result<Option<Output>> {
        with_learning_setting_cache(&self.cache, action)
    }
    pub(crate) fn with_microphone_scan_settings<Output>(
        &self,
        action: impl FnOnce(&tauri_plugin_store::Store<tauri::Wry>) -> Result<Output>,
    ) -> Result<Output> {
        let _guard = self
            .cache
            .lock()
            .map_err(|_| anyhow::anyhow!("Application settings are unavailable"))?;
        let store = self.validated_settings_store()?;
        action(&store)
    }

    pub(crate) fn load_onboarding_completed(&self) -> Result<bool> {
        let _guard = self
            .cache
            .lock()
            .map_err(|_| anyhow::anyhow!("Application settings are unavailable"))?;
        let store = self.validated_settings_store()?;
        match store.get("onboarding_completed") {
            None | Some(serde_json::Value::Bool(false)) => Ok(false),
            Some(serde_json::Value::Bool(true)) => Ok(true),
            Some(_) => anyhow::bail!("Application onboarding settings are invalid"),
        }
    }

    pub(crate) fn save_onboarding_completed(&self) -> Result<()> {
        let _guard = self
            .cache
            .lock()
            .map_err(|_| anyhow::anyhow!("Application settings are unavailable"))?;
        let store = self.validated_settings_store()?;
        persist_onboarding_marker(&store)
    }

    fn validated_settings_store(
        &self,
    ) -> Result<std::sync::Arc<tauri_plugin_store::Store<tauri::Wry>>> {
        let path = tauri_plugin_store::resolve_store_path(&self.app_handle, "settings.json")
            .map_err(|_| anyhow::anyhow!("Could not locate application settings"))?;
        validate_settings_file(&path)?;
        self.app_handle
            .store("settings.json")
            .map_err(|_| anyhow::anyhow!("Could not read application settings"))
    }

    pub(crate) fn with_current_history_privacy<Output>(
        &self,
        action: impl FnOnce(Option<(HistoryRetentionPolicy, bool)>) -> Result<Output>,
    ) -> Result<Output> {
        with_history_privacy_cache(
            &self.cache,
            self.history_privacy_verified
                .load(std::sync::atomic::Ordering::SeqCst),
            action,
        )
    }

    pub(crate) fn prune_confirmed_history(
        &self,
        history: &HistoryStore,
        enabled: bool,
        retention_days: u32,
        now_iso: &str,
    ) -> Result<()> {
        with_confirmed_retention_cache(
            &self.cache,
            self.history_privacy_verified
                .load(std::sync::atomic::Ordering::SeqCst),
            enabled,
            retention_days,
            |policy| history.prune_with_policy_sync(policy, now_iso),
        )
    }

    pub fn new(app_handle: tauri::AppHandle) -> Self {
        Self {
            app_handle,
            cache: Mutex::new(None),
            history_privacy_verified: std::sync::atomic::AtomicBool::new(false),
            input_commit_revision: std::sync::atomic::AtomicU64::new(0),
            config_epoch: uuid::Uuid::new_v4().to_string(),
        }
    }

    pub async fn load(&self) -> Result<AppConfig> {
        let mut cache = self.cache.lock().unwrap_or_else(|error| error.into_inner());
        if let Some(config) = cache.clone() {
            return Ok(config);
        }

        let path = tauri_plugin_store::resolve_store_path(&self.app_handle, "settings.json")
            .map_err(|_| anyhow::anyhow!("Could not locate application settings"))?;
        validate_settings_file(&path)?;
        let store = self
            .app_handle
            .store("settings.json")
            .map_err(|_| anyhow::anyhow!("Could not read application settings"))?;
        let loaded = match store.get("app_config") {
            Some(value) => Some(
                AppConfig::from_stored_value(value)
                    .map_err(|_| anyhow::anyhow!("Application settings are invalid"))?,
            ),
            None => None,
        };
        let privacy_verified = loaded.is_some();
        let mut config = loaded.unwrap_or_else(AppConfig::new_install_default);

        self.migrate_legacy_config_secrets_on_load(&mut config);

        *cache = Some(config.clone());
        self.history_privacy_verified
            .store(privacy_verified, std::sync::atomic::Ordering::SeqCst);
        Ok(config)
    }

    pub async fn save(&self, config: &AppConfig) -> Result<()> {
        let mut config = config.clone();
        config.normalize_values();
        let report = migrate_legacy_config_secrets(&mut config, &SystemCredentialVault)?;
        if !report.migrated.is_empty() {
            tracing::info!(
                migrated_credentials = report.migrated.len(),
                "Migrated legacy config credentials before saving settings"
            );
        }
        let mut cache = self.cache.lock().unwrap_or_else(|e| e.into_inner());
        self.next_input_commit_revision()?;
        self.persist_config(&config)?;
        *cache = Some(config.clone());
        self.history_privacy_verified
            .store(true, std::sync::atomic::Ordering::SeqCst);
        self.publish_input_commit(&config);

        Ok(())
    }

    pub async fn save_renderer_config(
        &self,
        incoming: AppConfig,
        prepare: impl FnOnce(AppConfig) -> std::result::Result<AppConfig, String>,
    ) -> Result<(u64, AppConfig)> {
        // Merge at commit time: an auth refresh or logout may have changed the
        // capability since the renderer read its settings. Keep this lock until
        // persistence completes so the renderer cannot resurrect an old state.
        let mut cache = self.cache.lock().unwrap_or_else(|error| error.into_inner());
        self.next_input_commit_revision()?;
        let mut current = cache.clone();
        if let Some(current) = current.as_mut() {
            // Do not discard the only legacy key copy if the vault is unavailable.
            migrate_legacy_config_secrets(current, &SystemCredentialVault)?;
        }
        let config = prepare(config_with_native_managed_state(incoming, current.as_ref()))
            .map_err(anyhow::Error::msg)?;
        validate_custom_scene_transition(current.as_ref(), &config)?;
        self.persist_config(&config)?;
        *cache = Some(config.clone());
        self.history_privacy_verified
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let revision = self.publish_input_commit(&config);
        Ok((revision, config))
    }

    pub(crate) fn migrate_current_credentials_commit(&self) -> Result<(u64, AppConfig)> {
        let mut cache = self.cache.lock().unwrap_or_else(|error| error.into_inner());
        let mut config = cache
            .clone()
            .ok_or_else(|| anyhow::anyhow!("Application settings are unavailable"))?;
        self.next_input_commit_revision()?;
        migrate_legacy_config_secrets(&mut config, &SystemCredentialVault)?;
        config.normalize_values();
        self.persist_config(&config)?;
        *cache = Some(config.clone());
        self.history_privacy_verified
            .store(true, std::sync::atomic::Ordering::SeqCst);
        let revision = self.publish_input_commit(&config);
        Ok((revision, config))
    }

    pub(crate) fn try_update_current_config_commit(
        &self,
        update: impl FnOnce(&mut AppConfig) -> Result<()>,
    ) -> Result<(u64, AppConfig)> {
        self.update_current_config_commit(update, false)
    }

    pub(crate) fn commit_current_config(
        &self,
        update: impl FnOnce(&mut AppConfig) -> Result<()>,
    ) -> Result<(u64, AppConfig)> {
        self.update_current_config_commit(update, true)
    }

    fn update_current_config_commit(
        &self,
        update: impl FnOnce(&mut AppConfig) -> Result<()>,
        persist_unchanged: bool,
    ) -> Result<(u64, AppConfig)> {
        update_cached_config_commit(
            &self.cache,
            &self.input_commit_revision,
            update,
            |config| {
                self.next_input_commit_revision()?;
                migrate_legacy_config_secrets(config, &SystemCredentialVault)?;
                self.persist_config(config)?;
                self.history_privacy_verified
                    .store(true, std::sync::atomic::Ordering::SeqCst);
                Ok(self.publish_input_commit(config))
            },
            persist_unchanged,
        )
    }

    fn next_input_commit_revision(&self) -> Result<u64> {
        checked_config_revision(
            self.input_commit_revision
                .load(std::sync::atomic::Ordering::SeqCst),
        )
    }

    fn publish_input_commit(&self, config: &AppConfig) -> u64 {
        let revision = self
            .input_commit_revision
            .fetch_add(1, std::sync::atomic::Ordering::SeqCst)
            + 1;
        crate::hotkey::refresh_committed_input_configuration(&self.app_handle, revision, config);
        revision
    }

    fn migrate_legacy_config_secrets_on_load(&self, config: &mut AppConfig) {
        match migrate_legacy_config_secrets(config, &SystemCredentialVault) {
            Ok(report) if report.legacy_fields_cleared => {
                tracing::info!(
                    migrated_credentials = report.migrated.len(),
                    "Migrated legacy config credentials to system credential vault"
                );
                if let Err(error) = self.persist_config(config) {
                    tracing::warn!("Failed to persist sanitized settings after migration: {error}");
                }
            }
            Ok(_) => {}
            Err(error) => {
                tracing::warn!(
                    "Failed to migrate legacy config credentials; keeping plaintext settings until a later retry: {error}"
                );
            }
        }
    }

    fn persist_config(&self, config: &AppConfig) -> Result<()> {
        let path = tauri_plugin_store::resolve_store_path(&self.app_handle, "settings.json")
            .map_err(|_| anyhow::anyhow!("Could not locate application settings"))?;
        validate_settings_file(&path)?;
        let store = self
            .app_handle
            .store("settings.json")
            .map_err(|e| anyhow::anyhow!("Failed to open store: {}", e))?;
        let val = serde_json::to_value(config)?;
        persist_config_value(&store, val)?;

        Ok(())
    }
}

fn persist_onboarding_marker<R: tauri::Runtime>(
    store: &tauri_plugin_store::Store<R>,
) -> Result<()> {
    persist_settings_marker(store, "onboarding_completed", serde_json::Value::Bool(true))
        .map_err(|_| anyhow::anyhow!("Could not save onboarding settings"))
}

pub(crate) fn persist_microphone_scan<R: tauri::Runtime>(
    store: &tauri_plugin_store::Store<R>,
    timestamp: i64,
) -> Result<()> {
    persist_settings_marker(
        store,
        "better_microphone_last_scan",
        serde_json::json!(timestamp),
    )
}

fn persist_settings_marker<R: tauri::Runtime>(
    store: &tauri_plugin_store::Store<R>,
    key: &str,
    value: serde_json::Value,
) -> Result<()> {
    let previous = store.get(key);
    let mut candidate: std::collections::HashMap<String, serde_json::Value> =
        store.entries().into_iter().collect();
    candidate.insert(key.to_string(), value.clone());
    let mut counter = SettingsSizeCounter {
        bytes: 0,
        limit: 8 * 1024 * 1024,
    };
    serde_json::to_writer_pretty(&mut counter, &candidate)
        .map_err(|_| anyhow::anyhow!("Application settings are too large"))?;
    store.set(key, value);
    if store.save().is_err() {
        if let Some(previous) = previous {
            store.set(key, previous);
        } else {
            store.delete(key);
        }
        anyhow::bail!("Could not save application settings");
    }
    Ok(())
}

struct SettingsSizeCounter {
    bytes: usize,
    limit: usize,
}

pub(crate) fn ensure_backup_snapshot_size(snapshot: &impl Serialize) -> Result<()> {
    let mut counter = SettingsSizeCounter {
        bytes: 0,
        limit: 20 * 1024 * 1024,
    };
    serde_json::to_writer(&mut counter, snapshot)
        .map_err(|_| anyhow::anyhow!("backup_snapshot_too_large"))
}

impl std::io::Write for SettingsSizeCounter {
    fn write(&mut self, buffer: &[u8]) -> std::io::Result<usize> {
        if buffer.len() > self.limit.saturating_sub(self.bytes) {
            return Err(std::io::Error::other("Application settings are too large"));
        }
        self.bytes += buffer.len();
        Ok(buffer.len())
    }

    fn flush(&mut self) -> std::io::Result<()> {
        Ok(())
    }
}

fn persist_config_value<R: tauri::Runtime>(
    store: &tauri_plugin_store::Store<R>,
    value: serde_json::Value,
) -> Result<()> {
    let mut candidate: std::collections::HashMap<String, serde_json::Value> =
        store.entries().into_iter().collect();
    candidate.insert("app_config".to_string(), value.clone());
    let mut counter = SettingsSizeCounter {
        bytes: 0,
        limit: 8 * 1024 * 1024,
    };
    serde_json::to_writer_pretty(&mut counter, &candidate)
        .map_err(|_| anyhow::anyhow!("Application settings are too large"))?;
    let previous = store.get("app_config");
    store.set("app_config", value);
    if let Err(error) = store.save() {
        match previous {
            Some(previous) => store.set("app_config", previous),
            None => {
                store.delete("app_config");
            }
        }
        return Err(anyhow::anyhow!("{error}"));
    }
    Ok(())
}

// ─── HistoryStore (SQLite backed) ───

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct HistoryEntry {
    pub id: i64,
    pub created_at: String,
    pub context_profile_id: String,
    pub context_label: String,
    pub context_icon_key: String,
    pub context_family: ContextFamily,
    pub browser_access_status: BrowserAccessStatus,
    pub provider_kind: HistoryProviderKind,
    pub raw_text: String,
    pub polished_text: String,
    pub language: Option<String>,
    pub duration_ms: Option<i64>,
    pub active_scene_id: Option<String>,
    pub active_scene_source: Option<String>,
    pub active_scene_name: Option<String>,
    pub active_scene_prompt_chars: Option<i64>,
    pub active_scene_prompt_truncated: bool,
    pub output_status: Option<String>,
    pub output_error: Option<String>,
    /// `dictate`, `translate` or `ask`; older rows default to `dictate`.
    #[serde(default = "default_history_mode")]
    pub mode: String,
    /// Ask Anything answer, shown again via History > View answer.
    #[serde(default)]
    pub answer_text: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub ask_intent: Option<String>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub citations: Option<serde_json::Value>,
    #[serde(default, skip_serializing_if = "Option::is_none")]
    pub citation_supports: Option<serde_json::Value>,
    #[serde(default)]
    pub has_audio: bool,
}

fn default_history_mode() -> String {
    "dictate".to_string()
}

pub(crate) fn validate_history_ask_intent(entry: &HistoryEntry) -> Result<()> {
    if let Some(intent) = &entry.ask_intent {
        if !matches!(intent.as_str(), "open_question" | "ask_selection")
            || entry.mode != "ask"
            || entry
                .answer_text
                .as_deref()
                .is_none_or(|answer| answer.trim().is_empty())
        {
            anyhow::bail!("Invalid history Ask intent");
        }
    }
    Ok(())
}

pub(crate) fn validated_history_citations(entry: &HistoryEntry) -> Result<Option<String>> {
    let Some(value) = entry.citations.as_ref() else {
        return Ok(None);
    };
    validated_citation_sources(value, &entry.mode, entry.answer_text.as_deref()).map(Some)
}

pub(crate) fn validated_history_supports(entry: &HistoryEntry) -> Result<Option<String>> {
    let Some(value) = entry.citation_supports.as_ref() else {
        return Ok(None);
    };
    let sources = entry.citations.as_ref().unwrap_or(&serde_json::Value::Null);
    let canonical_sources: serde_json::Value = serde_json::from_str(&validated_citation_sources(
        sources,
        &entry.mode,
        entry.answer_text.as_deref(),
    )?)?;
    if canonical_sources != *sources {
        anyhow::bail!("Citation supports require canonical sources");
    }
    let parsed_sources = crate::commands::ask_citations::parse_citations(sources);
    let parsed = crate::commands::ask_citations::parse_citation_supports(
        entry.answer_text.as_deref().unwrap_or(""),
        value,
        sources,
        &parsed_sources,
    );
    let serialized = serde_json::to_string(value)?;
    if entry.mode != "ask"
        || !value.is_array()
        || serde_json::to_value(parsed)? != *value
        || serialized.len() > 10000
    {
        anyhow::bail!("Invalid history citation supports");
    }
    Ok(Some(serialized))
}

fn validated_citation_sources(
    value: &serde_json::Value,
    mode: &str,
    answer: Option<&str>,
) -> Result<String> {
    let entries = value
        .as_array()
        .ok_or_else(|| anyhow::anyhow!("Invalid history citation metadata"))?;
    if entries.len() > 20
        || (mode != "ask" && !entries.is_empty())
        || (!entries.is_empty() && answer.is_none_or(|answer| answer.trim().is_empty()))
    {
        anyhow::bail!("Invalid history citation metadata");
    }
    let mut sources = Vec::new();
    let mut seen = HashSet::new();
    for citation in entries {
        let url = citation["url"]
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("Invalid history citation URL"))?;
        let title = citation["title"]
            .as_str()
            .ok_or_else(|| anyhow::anyhow!("Invalid history citation title"))?;
        if url.len() > 2048
            || title.trim().is_empty()
            || title.encode_utf16().count() > 500
            || url
                .chars()
                .any(|character| character <= ' ' || character == '\u{7f}' || character == '\\')
        {
            anyhow::bail!("Invalid history citation metadata");
        }
        let parsed = url::Url::parse(url)?;
        if !matches!(parsed.scheme(), "http" | "https")
            || !url
                .to_ascii_lowercase()
                .starts_with(&format!("{}://", parsed.scheme()))
            || parsed.host_str().is_none()
            || !parsed.username().is_empty()
            || parsed.password().is_some()
        {
            anyhow::bail!("Invalid history citation URL");
        }
        let normalized_url = parsed.to_string();
        if normalized_url.len() > 2048 {
            anyhow::bail!("Invalid history citation URL");
        }
        if seen.insert(normalized_url.clone()) {
            sources.push(serde_json::json!({
                "url": normalized_url,
                "title": title.trim(),
            }));
        }
    }
    Ok(serde_json::to_string(&sources)?)
}

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct SavedAnswerSnapshot {
    pub history_id: i64,
    pub question: String,
    pub answer: String,
    pub citations: serde_json::Value,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub citation_supports: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "Option::is_none")]
    pub ask_intent: Option<String>,
}

struct SavedAnswerRow {
    history_id: i64,
    question: String,
    answer: String,
    sources: Option<String>,
    oversized_sources: bool,
    ask_intent: Option<String>,
    supports: Option<String>,
    oversized_supports: bool,
}

pub(crate) const SAVED_ANSWER_MAX_BYTES: usize = 4 * 1024 * 1024;

pub(crate) fn validate_saved_answer_input(id: i64, question: &str, answer: &str) -> Result<()> {
    if id <= 0
        || answer.trim().is_empty()
        || question
            .len()
            .checked_add(answer.len())
            .is_none_or(|bytes| bytes > SAVED_ANSWER_MAX_BYTES)
    {
        anyhow::bail!("Saved answer input unavailable");
    }
    Ok(())
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum HistoryProviderKind {
    ManagedCloud,
    Byok,
    Local,
}

impl HistoryProviderKind {
    fn as_db_value(self) -> &'static str {
        match self {
            Self::ManagedCloud => "managed_cloud",
            Self::Byok => "byok",
            Self::Local => "local",
        }
    }

    fn from_db_value(value: &str) -> Self {
        match value {
            "managed_cloud" => Self::ManagedCloud,
            "byok" => Self::Byok,
            _ => Self::Local,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct HistoryRetentionPolicy {
    pub enabled: bool,
    pub max_entries: u32,
    /// 0 means keep indefinitely by age.
    pub retention_days: u32,
}

impl Default for HistoryRetentionPolicy {
    fn default() -> Self {
        Self {
            enabled: true,
            max_entries: DEFAULT_HISTORY_MAX_ENTRIES,
            retention_days: 0,
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HistoryLocalIdentity {
    pub record_uuid: String,
    pub local_revision: i64,
}

pub struct HistoryTextSnapshot {
    pub identity: HistoryLocalIdentity,
    pub raw_text: String,
    pub polished_text: String,
    pub mode: String,
    pub answer_text: Option<String>,
    pub created_at: String,
    pub duration_ms: Option<i64>,
    pub language: Option<String>,
}

pub struct HistoryStore {
    conn: Mutex<Connection>,
    local_identity_available: bool,
    backup_restore_active: std::sync::atomic::AtomicBool,
}

impl HistoryStore {
    pub(crate) fn acknowledge_history_sync_attempt(
        &self,
        account: &str,
        endpoint: &str,
        request: &str,
        commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
    ) -> Result<()> {
        let _reservation = self.reserve_history_sync()?;
        let connection = self
            .conn
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync storage unavailable"))?;
        history_sync_attempt::initialize(&connection)?;
        history_sync_attempt::acknowledge_with_commit(
            &connection,
            account,
            endpoint,
            request,
            commit,
        )
    }

    pub(crate) fn begin_history_sync_attempt(
        &self,
        account: &str,
        endpoint: &str,
        request: &str,
    ) -> Result<()> {
        let connection = self
            .conn
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync storage unavailable"))?;
        history_sync_attempt::initialize(&connection)?;
        history_sync_attempt::begin(&connection, account, endpoint, request)
    }

    pub(crate) fn finish_history_sync_attempt(
        &self,
        account: &str,
        endpoint: &str,
        request: &str,
        result: Option<&serde_json::Value>,
    ) -> Result<()> {
        let connection = self
            .conn
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync storage unavailable"))?;
        history_sync_attempt::finish(&connection, account, endpoint, request, result)
    }

    pub(crate) fn read_history_sync_attempt(
        &self,
        account: &str,
        endpoint: &str,
    ) -> Result<Option<history_sync_attempt::Attempt>> {
        let connection = self
            .conn
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync storage unavailable"))?;
        history_sync_attempt::initialize(&connection)?;
        history_sync_attempt::read(&connection, account, endpoint)
    }

    pub(crate) fn reserve_history_sync(&self) -> Result<impl Drop + '_> {
        use std::sync::atomic::Ordering;
        self.backup_restore_active
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .map_err(|_| {
                anyhow::anyhow!("History sync or backup restoration is already in progress")
            })?;
        Ok(crate::voice_operation::OnDrop::new(|| {
            self.backup_restore_active.store(false, Ordering::SeqCst);
        }))
    }

    pub(crate) fn reserve_backup_restore(&self) -> Result<impl Drop + '_> {
        use std::sync::atomic::Ordering;
        self.backup_restore_active
            .compare_exchange(false, true, Ordering::SeqCst, Ordering::SeqCst)
            .map_err(|_| anyhow::anyhow!("Backup restoration is already in progress"))?;
        Ok(crate::voice_operation::OnDrop::new(|| {
            self.backup_restore_active.store(false, Ordering::SeqCst);
        }))
    }
    pub fn apply_history_acknowledgements(
        &self,
        bytes: &[u8],
        account_id: &str,
        sent: &[history_sync_queue::PendingHistoryMutation],
        session: (&crate::SessionTokenStore, &crate::CloudSessionSnapshot),
    ) -> Result<usize> {
        self.apply_history_acknowledgements_with_gate(bytes, account_id, sent, session, || Ok(()))
    }

    pub(crate) fn apply_history_acknowledgements_with_gate(
        &self,
        bytes: &[u8],
        account_id: &str,
        sent: &[history_sync_queue::PendingHistoryMutation],
        session: (&crate::SessionTokenStore, &crate::CloudSessionSnapshot),
        before_commit: impl FnOnce() -> Result<()>,
    ) -> Result<usize> {
        self.apply_history_acknowledgements_with_commit(
            bytes,
            account_id,
            sent,
            session,
            |transaction| {
                before_commit()?;
                transaction.commit()?;
                Ok(())
            },
        )
    }

    pub(crate) fn apply_history_acknowledgements_with_commit(
        &self,
        bytes: &[u8],
        account_id: &str,
        sent: &[history_sync_queue::PendingHistoryMutation],
        session: (&crate::SessionTokenStore, &crate::CloudSessionSnapshot),
        commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
    ) -> Result<usize> {
        if !self.local_identity_available {
            anyhow::bail!("History sync queue unavailable");
        }
        let (session_store, expected) = session;
        crate::history_sync_session::with_current_session(session_store, expected, || Ok(()))?;
        let acknowledgements = history_sync_ack::parse_acknowledgements(bytes, account_id, sent)?;
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let transaction =
            connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let mut confirmed = 0;
        transaction.execute_batch(
            "CREATE TABLE IF NOT EXISTS history_sync_conflict (
                account_id TEXT NOT NULL,
                record_uuid TEXT NOT NULL,
                mutation_uuid TEXT NOT NULL,
                client_revision INTEGER NOT NULL CHECK(client_revision BETWEEN 1 AND 9007199254740991),
                server_revision INTEGER NOT NULL CHECK(server_revision BETWEEN 0 AND 9007199254740991),
                result TEXT NOT NULL CHECK(result IN ('conflict', 'rejected')),
                PRIMARY KEY(account_id, record_uuid),
                FOREIGN KEY(account_id, record_uuid, client_revision)
                    REFERENCES history_sync_pending(account_id, record_uuid, client_revision)
                    ON DELETE CASCADE
            );",
        )?;
        for acknowledgement in acknowledgements {
            if acknowledgement.result != history_sync_ack::AckResult::Accepted {
                let pending_matches: bool = transaction.query_row(
                    "SELECT EXISTS(SELECT 1 FROM history_sync_pending
                     WHERE account_id = ?1 AND record_uuid = ?2 AND mutation_uuid = ?3
                       AND client_revision = ?4)",
                    rusqlite::params![
                        account_id,
                        acknowledgement.record_id,
                        acknowledgement.mutation_id,
                        acknowledgement.client_revision
                    ],
                    |row| row.get(0),
                )?;
                if !pending_matches {
                    anyhow::bail!("History sync conflict source unavailable");
                }
                let result = match acknowledgement.result {
                    history_sync_ack::AckResult::Conflict => "conflict",
                    history_sync_ack::AckResult::Rejected => "rejected",
                    history_sync_ack::AckResult::Accepted => unreachable!(),
                };
                transaction.execute(
                    "INSERT INTO history_sync_conflict
                     (account_id, record_uuid, mutation_uuid, client_revision, server_revision, result)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6)
                     ON CONFLICT(account_id, record_uuid) DO UPDATE SET
                       mutation_uuid = excluded.mutation_uuid,
                       client_revision = excluded.client_revision,
                       server_revision = excluded.server_revision,
                       result = excluded.result",
                    rusqlite::params![
                        account_id,
                        acknowledgement.record_id,
                        acknowledgement.mutation_id,
                        acknowledgement.client_revision,
                        acknowledgement.server_revision,
                        result
                    ],
                )?;
                continue;
            }
            let mutation = sent
                .iter()
                .find(|mutation| {
                    mutation
                        .mutation_uuid
                        .eq_ignore_ascii_case(&acknowledgement.mutation_id)
                })
                .ok_or_else(|| anyhow::anyhow!("Invalid history sync acknowledgement"))?;
            if history_sync_queue::confirm_in_transaction(
                &transaction,
                mutation,
                acknowledgement.server_revision,
            )? {
                confirmed += 1;
            }
        }
        crate::history_sync_session::commit_with_current_session(session_store, expected, || {
            commit(transaction)
        })?;
        Ok(confirmed)
    }

    pub fn apply_history_sync_page_for_session(
        &self,
        bytes: &[u8],
        account_id: &str,
        cursor: i64,
        policy: &HistoryRetentionPolicy,
        session: (&crate::SessionTokenStore, &crate::CloudSessionSnapshot),
    ) -> Result<i64> {
        let (session_store, expected) = session;
        if !self.local_identity_available || !policy.enabled || policy.retention_days != 0 {
            anyhow::bail!("History sync import unavailable");
        }
        crate::history_sync_session::with_current_session(session_store, expected, || Ok(()))?;
        let page = history_sync_page::parse_page(bytes, account_id, cursor)?;
        self.apply_validated_sync_page(&page, policy, session, || Ok(()))
    }

    pub(crate) fn apply_validated_sync_page(
        &self,
        page: &history_sync_page::HistorySyncPage,
        policy: &HistoryRetentionPolicy,
        session: (&crate::SessionTokenStore, &crate::CloudSessionSnapshot),
        before_commit: impl FnOnce() -> Result<()>,
    ) -> Result<i64> {
        self.apply_validated_sync_page_with_commit(page, policy, session, |transaction| {
            before_commit()?;
            transaction.commit()?;
            Ok(())
        })
    }

    pub(crate) fn apply_validated_sync_page_with_commit(
        &self,
        page: &history_sync_page::HistorySyncPage,
        policy: &HistoryRetentionPolicy,
        session: (&crate::SessionTokenStore, &crate::CloudSessionSnapshot),
        commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
    ) -> Result<i64> {
        let (session_store, expected) = session;
        if !self.local_identity_available || !policy.enabled || policy.retention_days != 0 {
            anyhow::bail!("History sync import unavailable");
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_page::import_page_with_commit(
            &mut connection,
            page,
            policy.max_entries,
            |transaction| {
                crate::history_sync_session::commit_with_current_session(
                    session_store,
                    expected,
                    || commit(transaction),
                )
            },
        )
    }

    pub fn history_sync_pull_cursor(&self, account_id: &str) -> Result<i64> {
        if !self.local_identity_available {
            anyhow::bail!("History sync cursor unavailable");
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_page::read_cursor(&connection, account_id)
    }

    pub fn import_new_history_sync_page(
        &self,
        bytes: &[u8],
        account_id: &str,
        cursor: i64,
        policy: &HistoryRetentionPolicy,
    ) -> Result<i64> {
        if !self.local_identity_available || !policy.enabled || policy.retention_days != 0 {
            anyhow::bail!("History sync import unavailable");
        }
        let page = history_sync_page::parse_page(bytes, account_id, cursor)?;
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_page::import_new_page(&mut connection, &page, policy.max_entries)
    }

    pub fn build_pending_history_batch_for_session(
        &self,
        session_store: &crate::SessionTokenStore,
        expected: &crate::CloudSessionSnapshot,
        mutations: &[history_sync_queue::PendingHistoryMutation],
    ) -> Result<serde_json::Value> {
        crate::history_sync_session::with_current_session(session_store, expected, || {
            self.build_pending_history_batch_request(mutations)
        })
    }

    pub fn build_pending_history_batch_request(
        &self,
        mutations: &[history_sync_queue::PendingHistoryMutation],
    ) -> Result<serde_json::Value> {
        if !self.local_identity_available {
            anyhow::bail!("History sync batch unavailable");
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::build_batch_request(&mut connection, mutations)
    }

    pub fn build_pending_history_request(
        &self,
        mutation: &history_sync_queue::PendingHistoryMutation,
    ) -> Result<serde_json::Value> {
        if !self.local_identity_available {
            anyhow::bail!("History sync request source unavailable");
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::build_request(&connection, mutation)
    }

    pub fn confirm_pending_history_revision(
        &self,
        mutation: &history_sync_queue::PendingHistoryMutation,
        server_revision: i64,
    ) -> Result<bool> {
        if !self.local_identity_available {
            anyhow::bail!("History sync queue unavailable");
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::confirm(&mut connection, mutation, server_revision)
    }

    pub fn confirm_history_revision_for_session(
        &self,
        mutation: &history_sync_queue::PendingHistoryMutation,
        server_revision: i64,
        session: (&crate::SessionTokenStore, &crate::CloudSessionSnapshot),
    ) -> Result<bool> {
        if !self.local_identity_available {
            anyhow::bail!("History sync queue unavailable");
        }
        let (session_store, expected) = session;
        crate::history_sync_session::with_current_session(session_store, expected, || Ok(()))?;
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::confirm_with_commit(
            &mut connection,
            mutation,
            server_revision,
            |transaction| {
                crate::history_sync_session::commit_with_current_session(
                    session_store,
                    expected,
                    || {
                        transaction.commit()?;
                        Ok(())
                    },
                )
            },
        )
    }

    pub fn pending_history_revisions(
        &self,
        account_id: &str,
        limit: u32,
    ) -> Result<Vec<history_sync_queue::PendingHistoryQueueEntry>> {
        if !self.local_identity_available {
            anyhow::bail!("History sync queue unavailable");
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::pending(&connection, account_id, limit)
    }

    pub(crate) fn pending_sendable_history_revisions(
        &self,
        account_id: &str,
        limit: u32,
    ) -> Result<Vec<history_sync_queue::PendingHistoryMutation>> {
        if !self.local_identity_available || !(1..=200).contains(&limit) {
            anyhow::bail!("History sync queue unavailable");
        }
        let entries = {
            let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
            history_sync_queue::pending_for_selection(&connection, account_id)?
        };
        let mut latest =
            std::collections::HashMap::<String, history_sync_queue::PendingHistoryQueueEntry>::new(
            );
        for entry in entries {
            let record = entry.mutation.record_uuid.to_ascii_lowercase();
            if latest.get(&record).is_none_or(|current| {
                entry.mutation.client_revision > current.mutation.client_revision
            }) {
                latest.insert(record, entry);
            }
        }
        let mut entries = latest.into_values().collect::<Vec<_>>();
        entries.sort_by(|left, right| {
            left.mutation
                .client_revision
                .cmp(&right.mutation.client_revision)
                .then_with(|| left.mutation.record_uuid.cmp(&right.mutation.record_uuid))
        });
        entries
            .into_iter()
            .filter(|entry| {
                matches!(
                    entry.source,
                    history_sync_queue::PendingHistorySource::Current { .. }
                ) && !entry.deleted
                    || matches!(
                        entry.source,
                        history_sync_queue::PendingHistorySource::Missing
                    ) && entry.deleted
            })
            .take(limit.clamp(1, 200) as usize)
            .map(|entry| Ok(entry.mutation))
            .collect()
    }

    #[cfg(test)]
    pub(crate) fn pending_history_count(&self, account_id: &str) -> Result<usize> {
        if !self.local_identity_available {
            anyhow::bail!("History sync queue unavailable");
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        Ok(history_sync_queue::pending_for_selection(&connection, account_id)?.len())
    }

    pub(crate) fn pending_actionable_history_count(&self, account_id: &str) -> Result<usize> {
        let connection = self
            .conn
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync storage unavailable"))?;
        Ok(
            history_sync_queue::pending_for_selection(&connection, account_id)?
                .into_iter()
                .filter(|entry| {
                    entry.deleted
                        || !matches!(
                            entry.source,
                            history_sync_queue::PendingHistorySource::Missing
                        )
                })
                .count(),
        )
    }

    pub(crate) fn history_sync_conflict_count(&self, account_id: &str) -> Result<usize> {
        if !self.local_identity_available
            || account_id.is_empty()
            || account_id.trim() != account_id
            || account_id.len() > 200
            || account_id.chars().any(char::is_control)
        {
            anyhow::bail!("History sync conflict state unavailable");
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let exists: bool = connection.query_row(
            "SELECT EXISTS(SELECT 1 FROM sqlite_master
             WHERE type = 'table' AND name = 'history_sync_conflict')",
            [],
            |row| row.get(0),
        )?;
        if !exists {
            return Ok(0);
        }
        let count: i64 = connection.query_row(
            "SELECT COUNT(*) FROM history_sync_conflict AS conflict
             JOIN history_sync_pending AS pending
               ON pending.account_id = conflict.account_id
              AND pending.record_uuid = conflict.record_uuid
              AND pending.mutation_uuid = conflict.mutation_uuid
              AND pending.client_revision = conflict.client_revision
             WHERE conflict.account_id = ?1",
            [account_id],
            |row| row.get(0),
        )?;
        usize::try_from(count).map_err(anyhow::Error::from)
    }

    pub(crate) fn list_history_sync_conflicts(
        &self,
        account_id: &str,
    ) -> Result<Vec<history_sync_record::ConflictSummary>> {
        let mut summaries = Vec::new();
        let mut cursor = None;
        loop {
            let page = self.list_history_sync_conflict_page(account_id, cursor.as_deref())?;
            let finished = page.len() < 200;
            cursor = page.last().map(|summary| summary.record_id.clone());
            summaries.extend(page);
            if summaries.len() > 5000 {
                anyhow::bail!("History sync conflict capacity exceeded");
            }
            if finished {
                return Ok(summaries);
            }
        }
    }

    pub(crate) fn list_history_sync_conflict_page(
        &self,
        account_id: &str,
        after_record_id: Option<&str>,
    ) -> Result<Vec<history_sync_record::ConflictSummary>> {
        if after_record_id.is_some_and(|value| !history_sync_queue::valid_sync_uuid(value)) {
            anyhow::bail!("Invalid history sync conflict cursor");
        }
        if self.history_sync_conflict_count(account_id)? == 0 {
            return Ok(Vec::new());
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let mut query = connection.prepare(
            "SELECT conflict.record_uuid, conflict.mutation_uuid,
                    conflict.client_revision, conflict.server_revision, conflict.result
             FROM history_sync_conflict AS conflict
             JOIN history_sync_pending AS pending
               ON pending.account_id = conflict.account_id
              AND pending.record_uuid = conflict.record_uuid
              AND pending.mutation_uuid = conflict.mutation_uuid
              AND pending.client_revision = conflict.client_revision
             WHERE conflict.account_id = ?1
               AND (?2 IS NULL OR conflict.record_uuid > ?2)
             ORDER BY conflict.record_uuid LIMIT 200",
        )?;
        let rows = query.query_map(rusqlite::params![account_id, after_record_id], |row| {
            Ok(history_sync_record::ConflictSummary {
                record_id: row.get(0)?,
                mutation_id: row.get(1)?,
                client_revision: row.get(2)?,
                server_revision: row.get(3)?,
                result: row.get(4)?,
            })
        })?;
        let summaries = rows.collect::<rusqlite::Result<Vec<_>>>()?;
        for summary in &summaries {
            if !history_sync_queue::valid_sync_uuid(&summary.record_id)
                || !history_sync_queue::valid_sync_uuid(&summary.mutation_id)
                || !(1..=9007199254740991).contains(&summary.client_revision)
                || !(0..=9007199254740991).contains(&summary.server_revision)
                || !matches!(summary.result.as_str(), "conflict" | "rejected")
            {
                anyhow::bail!("Invalid history sync conflict");
            }
        }
        Ok(summaries)
    }

    pub(crate) fn read_history_sync_conflict_local(
        &self,
        account_id: &str,
        summary: &history_sync_record::ConflictSummary,
    ) -> Result<history_sync_record::LocalConflictRecord> {
        if !self.has_history_sync_conflict(account_id, &summary.record_id)? {
            anyhow::bail!("History sync conflict record unavailable");
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let transaction = connection.unchecked_transaction()?;
        let baseline: (i64, String) = transaction.query_row(
            "SELECT server_revision, result FROM history_sync_conflict
             WHERE account_id = ?1 AND record_uuid = ?2 AND mutation_uuid = ?3
               AND client_revision = ?4",
            rusqlite::params![
                account_id,
                summary.record_id,
                summary.mutation_id,
                summary.client_revision
            ],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )?;
        if baseline != (summary.server_revision, summary.result.clone()) {
            anyhow::bail!("History sync conflict changed");
        }
        let base_server_revision: i64 = transaction.query_row(
            "SELECT base_server_revision FROM history_sync_pending
             WHERE account_id = ?1 AND record_uuid = ?2 AND mutation_uuid = ?3
               AND client_revision = ?4",
            rusqlite::params![
                account_id,
                summary.record_id,
                summary.mutation_id,
                summary.client_revision
            ],
            |row| row.get(0),
        )?;
        let mutation = history_sync_queue::PendingHistoryMutation {
            account_id: account_id.into(),
            record_uuid: summary.record_id.clone(),
            mutation_uuid: summary.mutation_id.clone(),
            client_revision: summary.client_revision,
            base_server_revision,
        };
        let request = history_sync_queue::build_request(&transaction, &mutation)?;
        let local = history_sync_record::LocalConflictRecord {
            record_id: summary.record_id.clone(),
            client_revision: summary.client_revision,
            deleted: request["mutations"][0]["deleted"]
                .as_bool()
                .ok_or_else(|| anyhow::anyhow!("Invalid local conflict record"))?,
            payload: request["mutations"][0]["payload"].clone(),
        };
        transaction.commit()?;
        Ok(local)
    }

    pub(crate) fn keep_local_history_sync_conflict(
        &self,
        account_id: &str,
        summary: &history_sync_record::ConflictSummary,
        approved_remote_revision: i64,
        commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
    ) -> Result<history_sync_queue::PendingHistoryMutation> {
        self.read_history_sync_conflict_local(account_id, summary)?;
        if summary.result != "conflict"
            || approved_remote_revision < summary.server_revision
            || !(0..=9007199254740991).contains(&approved_remote_revision)
        {
            anyhow::bail!("History sync resolution unavailable");
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let transaction =
            connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let previous_base: i64 = transaction.query_row(
            "SELECT pending.base_server_revision FROM history_sync_pending AS pending
             JOIN history_sync_conflict AS conflict
               ON conflict.account_id = pending.account_id
              AND conflict.record_uuid = pending.record_uuid
              AND conflict.mutation_uuid = pending.mutation_uuid
              AND conflict.client_revision = pending.client_revision
             WHERE pending.account_id = ?1 AND pending.record_uuid = ?2
               AND pending.mutation_uuid = ?3 AND pending.client_revision = ?4
               AND conflict.server_revision = ?5 AND conflict.result = 'conflict'",
            rusqlite::params![
                account_id,
                summary.record_id,
                summary.mutation_id,
                summary.client_revision,
                summary.server_revision
            ],
            |row| row.get(0),
        )?;
        let original = history_sync_queue::PendingHistoryMutation {
            account_id: account_id.into(),
            record_uuid: summary.record_id.clone(),
            mutation_uuid: summary.mutation_id.clone(),
            client_revision: summary.client_revision,
            base_server_revision: previous_base,
        };
        history_sync_queue::build_request(&transaction, &original)?;
        let new_mutation_id = uuid::Uuid::new_v4().to_string();
        let known_original_base: Option<i64> = transaction
            .query_row(
                "SELECT previous_base FROM history_sync_resolution_base
                 WHERE account_id = ?1 AND record_uuid = ?2 AND client_revision = ?3
                   AND mutation_uuid = ?4",
                rusqlite::params![
                    account_id,
                    summary.record_id,
                    summary.client_revision,
                    summary.mutation_id
                ],
                |row| row.get(0),
            )
            .optional()?;
        transaction.execute(
            "INSERT INTO history_sync_resolution_base
             (account_id, record_uuid, client_revision, mutation_uuid, previous_base, approved_base)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(account_id, record_uuid, client_revision) DO UPDATE SET
               mutation_uuid = excluded.mutation_uuid, approved_base = excluded.approved_base",
            rusqlite::params![
                account_id,
                summary.record_id,
                summary.client_revision,
                new_mutation_id,
                known_original_base.unwrap_or(previous_base),
                approved_remote_revision
            ],
        )?;
        let changed = transaction.execute(
            "UPDATE history_sync_pending SET mutation_uuid = ?1, base_server_revision = ?2
             WHERE account_id = ?3 AND record_uuid = ?4 AND mutation_uuid = ?5
               AND client_revision = ?6",
            rusqlite::params![
                new_mutation_id,
                approved_remote_revision,
                account_id,
                summary.record_id,
                summary.mutation_id,
                summary.client_revision
            ],
        )?;
        if changed != 1 {
            anyhow::bail!("History sync conflict changed");
        }
        transaction.execute(
            "DELETE FROM history_sync_conflict WHERE account_id = ?1 AND record_uuid = ?2",
            rusqlite::params![account_id, summary.record_id],
        )?;
        let rebased = history_sync_queue::PendingHistoryMutation {
            mutation_uuid: new_mutation_id,
            base_server_revision: approved_remote_revision,
            ..original
        };
        history_sync_queue::build_request(&transaction, &rebased)?;
        commit(transaction)?;
        Ok(rebased)
    }

    pub(crate) fn keep_remote_history_sync_conflict(
        &self,
        account_id: &str,
        summary: &history_sync_record::ConflictSummary,
        remote: &history_sync_record::RemoteRecord,
        commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
    ) -> Result<()> {
        self.read_history_sync_conflict_local(account_id, summary)?;
        if summary.result != "conflict"
            || remote.record_id != summary.record_id
            || remote.server_revision < summary.server_revision
            || !(1..=9007199254740991).contains(&remote.server_revision)
        {
            anyhow::bail!("History sync remote version changed");
        }
        if remote.deleted {
            anyhow::ensure!(remote.payload.is_null(), "Invalid remote deletion");
        } else {
            history_sync_queue::validate_stored_payload(&serde_json::to_string(&remote.payload)?)?;
        }
        let mut connection = self
            .conn
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync storage unavailable"))?;
        let transaction =
            connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let source: (Option<i64>, Option<i64>, bool) = transaction.query_row(
            "SELECT identity.history_id, identity.local_revision, pending.deleted
             FROM history_sync_pending AS pending
             JOIN history_sync_conflict AS conflict
               ON conflict.account_id = pending.account_id AND conflict.record_uuid = pending.record_uuid
              AND conflict.mutation_uuid = pending.mutation_uuid AND conflict.client_revision = pending.client_revision
             JOIN history_sync_record_owner AS owner
               ON owner.record_uuid = pending.record_uuid AND owner.account_id = pending.account_id
             LEFT JOIN history_local_identity AS identity ON identity.record_uuid = pending.record_uuid
             WHERE pending.account_id = ?1 AND pending.record_uuid = ?2
               AND pending.mutation_uuid = ?3 AND pending.client_revision = ?4
               AND conflict.server_revision = ?5 AND conflict.result = 'conflict'",
            rusqlite::params![account_id, summary.record_id, summary.mutation_id, summary.client_revision, summary.server_revision],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )?;
        anyhow::ensure!(
            (source.2 && source.0.is_none() && source.1.is_none())
                || (!source.2 && source.0.is_some() && source.1 == Some(summary.client_revision)),
            "History sync local version changed"
        );
        let other_mutations: bool = transaction.query_row(
            "SELECT EXISTS(SELECT 1 FROM history_sync_pending WHERE record_uuid = ?1
             AND (account_id != ?2 OR client_revision > ?3))",
            rusqlite::params![summary.record_id, account_id, summary.client_revision],
            |row| row.get(0),
        )?;
        anyhow::ensure!(!other_mutations, "History sync local version changed");
        transaction.execute_batch(
            "CREATE TABLE IF NOT EXISTS history_sync_remote_baseline (
                record_uuid TEXT PRIMARY KEY, account_id TEXT NOT NULL,
                local_revision INTEGER NOT NULL CHECK(local_revision BETWEEN 1 AND 9007199254740991),
                server_revision INTEGER NOT NULL CHECK(server_revision BETWEEN 1 AND 9007199254740991)
             );
             CREATE TABLE IF NOT EXISTS history_sync_remote_tombstone (
                record_uuid TEXT PRIMARY KEY, account_id TEXT NOT NULL,
                server_revision INTEGER NOT NULL CHECK(server_revision BETWEEN 1 AND 9007199254740991)
             );",
        )?;
        transaction.execute(
            "DELETE FROM history_sync_pending WHERE account_id = ?1 AND record_uuid = ?2",
            rusqlite::params![account_id, summary.record_id],
        )?;
        for table in [
            "history_sync_conflict",
            "history_sync_resolution_base",
            "history_sync_confirmed",
            "history_sync_remote_baseline",
            "history_sync_remote_tombstone",
        ] {
            transaction.execute(
                &format!("DELETE FROM {table} WHERE account_id = ?1 AND record_uuid = ?2"),
                rusqlite::params![account_id, summary.record_id],
            )?;
        }
        if remote.deleted {
            if let Some(history_id) = source.0 {
                transaction.execute("DELETE FROM history WHERE id = ?1", [history_id])?;
                transaction.execute(
                    "DELETE FROM history_local_identity WHERE history_id = ?1",
                    [history_id],
                )?;
            }
            transaction.execute(
                "INSERT INTO history_sync_remote_tombstone(record_uuid, account_id, server_revision) VALUES (?1, ?2, ?3)",
                rusqlite::params![summary.record_id, account_id, remote.server_revision],
            )?;
        } else {
            let payload = &remote.payload;
            let history_id = if let Some(history_id) = source.0 {
                transaction.execute(
                    "UPDATE history SET created_at = ?1, raw_text = ?2, polished_text = ?3,
                     mode = ?4, answer_text = ?5, duration_ms = ?6, language = ?7,
                     output_status = NULL, output_error = NULL WHERE id = ?8",
                    rusqlite::params![
                        payload["createdAt"].as_str(),
                        payload["rawText"].as_str(),
                        payload["polishedText"].as_str(),
                        payload["mode"].as_str(),
                        payload["answerText"].as_str(),
                        payload["durationMs"].as_i64(),
                        payload["language"].as_str(),
                        history_id
                    ],
                )?;
                for table in ["history_citations", "history_citation_supports"] {
                    transaction.execute(
                        &format!("DELETE FROM {table} WHERE history_id = ?1"),
                        [history_id],
                    )?;
                }
                history_id
            } else {
                let count: i64 =
                    transaction.query_row("SELECT COUNT(*) FROM history", [], |row| row.get(0))?;
                anyhow::ensure!(
                    count < i64::from(DEFAULT_HISTORY_MAX_ENTRIES),
                    "History capacity reached"
                );
                transaction.execute(
                    "INSERT INTO history(created_at, raw_text, polished_text, mode, answer_text, duration_ms, language)
                     VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
                    rusqlite::params![payload["createdAt"].as_str(), payload["rawText"].as_str(),
                        payload["polishedText"].as_str(), payload["mode"].as_str(), payload["answerText"].as_str(),
                        payload["durationMs"].as_i64(), payload["language"].as_str()],
                )?;
                let history_id = transaction.last_insert_rowid();
                transaction.execute(
                    "INSERT INTO history_local_identity(history_id, record_uuid, local_revision) VALUES (?1, ?2, 1)",
                    rusqlite::params![history_id, summary.record_id],
                )?;
                history_id
            };
            let revision: i64 = transaction.query_row(
                "SELECT local_revision FROM history_local_identity WHERE history_id = ?1",
                [history_id],
                |row| row.get(0),
            )?;
            transaction.execute(
                "INSERT INTO history_sync_remote_baseline(record_uuid, account_id, local_revision, server_revision) VALUES (?1, ?2, ?3, ?4)",
                rusqlite::params![summary.record_id, account_id, revision, remote.server_revision],
            )?;
            transaction.execute(
                "INSERT INTO history_sync_confirmed(record_uuid, account_id, mutation_uuid, client_revision, server_revision) VALUES (?1, ?2, ?3, ?4, ?5)",
                rusqlite::params![summary.record_id, account_id, summary.mutation_id, revision, remote.server_revision],
            )?;
        }
        commit(transaction)
    }

    pub(crate) fn has_history_sync_conflict(
        &self,
        account_id: &str,
        record_id: &str,
    ) -> Result<bool> {
        if !history_sync_queue::valid_sync_uuid(record_id) {
            anyhow::bail!("Invalid history sync conflict record");
        }
        if self.history_sync_conflict_count(account_id)? == 0 {
            return Ok(false);
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        connection
            .query_row(
                "SELECT EXISTS(
                    SELECT 1 FROM history_sync_conflict AS conflict
                    JOIN history_sync_pending AS pending
                      ON pending.account_id = conflict.account_id
                     AND pending.record_uuid = conflict.record_uuid
                     AND pending.mutation_uuid = conflict.mutation_uuid
                     AND pending.client_revision = conflict.client_revision
                    WHERE conflict.account_id = ?1 AND conflict.record_uuid = ?2
                )",
                rusqlite::params![account_id, record_id],
                |row| row.get(0),
            )
            .map_err(anyhow::Error::from)
    }

    pub fn enqueue_local_history_revision(
        &self,
        account_id: &str,
        history_id: i64,
        expected: &HistoryLocalIdentity,
    ) -> Result<history_sync_queue::PendingHistoryMutation> {
        if !self.local_identity_available {
            anyhow::bail!("History sync queue unavailable");
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::enqueue(
            &mut connection,
            account_id,
            history_id,
            &expected.record_uuid,
            expected.local_revision,
        )
    }

    pub(crate) fn enqueue_history_revision_with_commit(
        &self,
        account_id: &str,
        history_id: i64,
        expected: &HistoryLocalIdentity,
        commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
    ) -> Result<history_sync_queue::PendingHistoryMutation> {
        if !self.local_identity_available {
            anyhow::bail!("History sync queue unavailable");
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::enqueue_with_commit(
            &mut connection,
            account_id,
            history_id,
            &expected.record_uuid,
            expected.local_revision,
            commit,
        )
    }

    pub fn local_text_snapshot(&self, id: i64) -> Result<Option<HistoryTextSnapshot>> {
        if !self.local_identity_available {
            anyhow::bail!("History synchronization text unavailable");
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let snapshot = connection
            .query_row(
                "SELECT EXISTS (
                     SELECT 1 FROM history_identity_state WHERE rebuild_required = 1
                 ), history.id IS NOT NULL, identity.record_uuid, identity.local_revision,
                 history.raw_text, history.polished_text, history.mode, history.answer_text,
                 COALESCE(length(CAST(history.raw_text AS BLOB)), 0)
                 + COALESCE(length(CAST(history.polished_text AS BLOB)), 0)
                 + COALESCE(length(CAST(history.mode AS BLOB)), 0)
                 + COALESCE(length(CAST(history.answer_text AS BLOB)), 0)
                 + COALESCE(length(CAST(history.created_at AS BLOB)), 0)
                 + COALESCE(length(CAST(history.language AS BLOB)), 0),
                 history.created_at, history.duration_ms, history.language
                 FROM (SELECT 1) AS anchor
                 LEFT JOIN history ON history.id = ?1
                 LEFT JOIN history_local_identity AS identity ON identity.history_id = history.id",
                [id],
                |row| {
                    if row.get::<_, bool>(0)? {
                        return Err(rusqlite::Error::InvalidQuery);
                    }
                    if !row.get::<_, bool>(1)? {
                        return Ok(None);
                    }
                    if row.get::<_, i64>(8)? > 1024 * 1024 {
                        return Err(rusqlite::Error::InvalidQuery);
                    }
                    Ok(Some(HistoryTextSnapshot {
                        identity: HistoryLocalIdentity {
                            record_uuid: row.get(2)?,
                            local_revision: row.get(3)?,
                        },
                        raw_text: row.get(4)?,
                        polished_text: row.get(5)?,
                        mode: row.get(6)?,
                        answer_text: row.get(7)?,
                        created_at: row.get(9)?,
                        duration_ms: row.get(10)?,
                        language: row.get(11)?,
                    }))
                },
            )
            .map_err(|_| anyhow::anyhow!("History synchronization text unavailable"))?;
        if let Some(snapshot) = snapshot.as_ref() {
            if uuid::Uuid::parse_str(&snapshot.identity.record_uuid)
                .ok()
                .is_none_or(|identity| identity.get_version_num() != 4)
                || !(1..=9007199254740991).contains(&snapshot.identity.local_revision)
            {
                anyhow::bail!("History synchronization text unavailable");
            }
        }
        Ok(snapshot)
    }

    pub fn local_identity_snapshot(&self, id: i64) -> Result<Option<HistoryLocalIdentity>> {
        if !self.local_identity_available {
            anyhow::bail!("History synchronization identity unavailable");
        }
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let (rebuild_required, exists, record_uuid, local_revision): (
            bool,
            bool,
            Option<String>,
            Option<i64>,
        ) = connection
            .query_row(
                "SELECT EXISTS (
                     SELECT 1 FROM history_identity_state WHERE rebuild_required = 1
                 ), history.id IS NOT NULL, identity.record_uuid, identity.local_revision
                 FROM (SELECT 1) AS anchor
                 LEFT JOIN history ON history.id = ?1
                 LEFT JOIN history_local_identity AS identity ON identity.history_id = history.id
                ",
                [id],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .map_err(|_| anyhow::anyhow!("History synchronization identity unavailable"))?;
        if rebuild_required {
            anyhow::bail!("History synchronization identity unavailable");
        }
        if !exists {
            return Ok(None);
        }
        let (Some(record_uuid), Some(local_revision)) = (record_uuid, local_revision) else {
            anyhow::bail!("History synchronization identity unavailable");
        };
        if uuid::Uuid::parse_str(&record_uuid)
            .ok()
            .is_none_or(|identity| identity.get_version_num() != 4)
            || !(1..=9007199254740991).contains(&local_revision)
        {
            anyhow::bail!("History synchronization identity unavailable");
        }
        Ok(Some(HistoryLocalIdentity {
            record_uuid,
            local_revision,
        }))
    }

    fn apply_retention_in_transaction(
        transaction: &rusqlite::Transaction<'_>,
        policy: &HistoryRetentionPolicy,
        now_iso: &str,
    ) -> Result<()> {
        let cutoff = if policy.enabled && policy.retention_days > 0 {
            let now = chrono::NaiveDateTime::parse_from_str(now_iso, "%Y-%m-%dT%H:%M:%S")?;
            let cutoff = now
                .checked_sub_signed(chrono::Duration::days(policy.retention_days as i64))
                .ok_or_else(|| anyhow::anyhow!("History retention cutoff is out of range"))?;
            Some(cutoff)
        } else {
            None
        };
        if !policy.enabled {
            transaction.execute("DELETE FROM history", [])?;
            return Ok(());
        }
        let max_entries = policy.max_entries.clamp(1, DEFAULT_HISTORY_MAX_ENTRIES);
        transaction.execute(
            "DELETE FROM history WHERE id NOT IN (SELECT id FROM history ORDER BY id DESC LIMIT ?1)",
            rusqlite::params![max_entries],
        )?;
        if let Some(cutoff) = cutoff {
            let mut statement = transaction.prepare("SELECT id, created_at FROM history")?;
            let entries = statement.query_map([], |row| {
                Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
            })?;
            let mut expired = Vec::new();
            for entry in entries {
                let (id, timestamp) = entry?;
                let created_at =
                    chrono::NaiveDateTime::parse_from_str(&timestamp, "%Y-%m-%dT%H:%M:%S")?;
                if created_at < cutoff {
                    expired.push(id);
                }
            }
            drop(statement);
            for id in expired {
                transaction.execute("DELETE FROM history WHERE id = ?1", [id])?;
            }
        }
        Ok(())
    }

    pub fn new(db_path: PathBuf) -> Result<Self> {
        let conn = Connection::open(&db_path)?;
        conn.execute_batch("PRAGMA journal_mode=WAL; PRAGMA foreign_keys=ON;")?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS history (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL,
                app_name TEXT NOT NULL DEFAULT '',
                app_type TEXT NOT NULL DEFAULT '',
                context_profile_id TEXT NOT NULL DEFAULT 'general.native',
                context_label TEXT NOT NULL DEFAULT 'General',
                context_icon_key TEXT NOT NULL DEFAULT 'general',
                context_family TEXT NOT NULL DEFAULT 'general',
                browser_access_status TEXT,
                provider_kind TEXT NOT NULL DEFAULT 'local',
                raw_text TEXT NOT NULL DEFAULT '',
                polished_text TEXT NOT NULL DEFAULT '',
                language TEXT,
                duration_ms INTEGER,
                active_scene_id TEXT,
                active_scene_source TEXT,
                active_scene_name TEXT,
                active_scene_prompt_chars INTEGER,
                active_scene_prompt_truncated INTEGER NOT NULL DEFAULT 0,
                output_status TEXT,
                output_error TEXT,
                mode TEXT NOT NULL DEFAULT 'dictate',
                answer_text TEXT
            );",
        )?;
        ensure_history_optional_columns(&conn)?;
        migrate_legacy_history_context(&conn)?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS history_audio (
                history_id INTEGER PRIMARY KEY REFERENCES history(id) ON DELETE CASCADE,
                wav BLOB NOT NULL
            );
            CREATE TABLE IF NOT EXISTS history_citations (
                history_id INTEGER PRIMARY KEY REFERENCES history(id) ON DELETE CASCADE,
                sources TEXT NOT NULL
            );
            CREATE TABLE IF NOT EXISTS history_citation_supports (
                history_id INTEGER PRIMARY KEY REFERENCES history_citations(history_id) ON DELETE CASCADE,
                supports TEXT NOT NULL CHECK(length(CAST(supports AS BLOB)) <= 10000)
            );
            CREATE TRIGGER IF NOT EXISTS invalidate_history_citations
            AFTER UPDATE OF raw_text, answer_text, mode ON history
            WHEN OLD.raw_text IS NOT NEW.raw_text
                OR OLD.answer_text IS NOT NEW.answer_text
                OR OLD.mode IS NOT NEW.mode
            BEGIN
                DELETE FROM history_citations WHERE history_id = NEW.id;
            END;
            CREATE TRIGGER IF NOT EXISTS invalidate_history_ask_intent
            AFTER UPDATE OF raw_text, answer_text, mode ON history
            WHEN OLD.raw_text IS NOT NEW.raw_text
                OR OLD.answer_text IS NOT NEW.answer_text
                OR OLD.mode IS NOT NEW.mode
            BEGIN
                UPDATE history SET ask_intent = NULL WHERE id = NEW.id;
            END;",
        )?;
        let local_identity_available = match initialize_history_local_identity(&conn) {
            Ok(()) => true,
            Err(_) => {
                let transaction = conn.unchecked_transaction()?;
                transaction.execute_batch(
                    "CREATE TABLE IF NOT EXISTS history_identity_state (
                         id INTEGER PRIMARY KEY CHECK(id = 1),
                         rebuild_required INTEGER NOT NULL CHECK(rebuild_required IN (0, 1))
                     );
                     INSERT INTO history_identity_state(id, rebuild_required) VALUES (1, 1)
                     ON CONFLICT(id) DO UPDATE SET rebuild_required = 1;
                     DROP TRIGGER IF EXISTS track_history_text_revision;
                     DROP TRIGGER IF EXISTS validate_history_revision_insert;
                     DROP TRIGGER IF EXISTS validate_history_revision_update;",
                )?;
                transaction.commit()?;
                tracing::warn!(
                    "History synchronization metadata unavailable; local history retained"
                );
                false
            }
        };
        Ok(Self {
            conn: Mutex::new(conn),
            local_identity_available,
            backup_restore_active: std::sync::atomic::AtomicBool::new(false),
        })
    }

    pub async fn add(&self, entry: HistoryEntry) -> Result<()> {
        self.add_with_policy(entry, &HistoryRetentionPolicy::default())
            .await
    }

    pub async fn add_with_policy(
        &self,
        entry: HistoryEntry,
        policy: &HistoryRetentionPolicy,
    ) -> Result<()> {
        self.add_with_audio(entry, policy, None).await.map(|_| ())
    }

    pub async fn add_with_audio(
        &self,
        entry: HistoryEntry,
        policy: &HistoryRetentionPolicy,
        audio: Option<Vec<u8>>,
    ) -> Result<Option<i64>> {
        self.add_with_audio_sync(entry, policy, audio)
    }

    pub(crate) fn add_with_audio_sync(
        &self,
        entry: HistoryEntry,
        policy: &HistoryRetentionPolicy,
        audio: Option<Vec<u8>>,
    ) -> Result<Option<i64>> {
        self.add_with_metadata_sync(entry, policy, audio, None)
    }

    pub(crate) fn add_with_metadata_sync(
        &self,
        entry: HistoryEntry,
        policy: &HistoryRetentionPolicy,
        audio: Option<Vec<u8>>,
        citations: Option<String>,
    ) -> Result<Option<i64>> {
        self.add_with_supports_sync(entry, policy, audio, citations, None)
    }

    pub(crate) fn add_with_supports_sync(
        &self,
        mut entry: HistoryEntry,
        policy: &HistoryRetentionPolicy,
        audio: Option<Vec<u8>>,
        citations: Option<String>,
        supports: Option<serde_json::Value>,
    ) -> Result<Option<i64>> {
        if !policy.enabled {
            return Ok(None);
        }
        if let Some(sources) = citations {
            if sources.len() > 100_000 {
                anyhow::bail!("Invalid history citation metadata");
            }
            entry.citations = Some(serde_json::from_str(&sources)?);
        }
        let citations = validated_history_citations(&entry)?;
        if supports.is_some() {
            entry.citation_supports = supports;
        }
        let supports = validated_history_supports(&entry)?;
        validate_history_ask_intent(&entry)?;
        if let Some(wav) = audio.as_ref() {
            crate::audio::archive::decode_archive_wav(wav)
                .map_err(|_| anyhow::anyhow!("Invalid or oversized history recording"))?;
        }

        let now_iso = entry.created_at.clone();
        let id = {
            let mut conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
            let transaction = conn.transaction()?;
            transaction.execute(
                "INSERT INTO history (
                    created_at,
                    app_name,
                    app_type,
                    context_profile_id,
                    context_label,
                    context_icon_key,
                    context_family,
                    browser_access_status,
                    provider_kind,
                    raw_text,
                    polished_text,
                    language,
                    duration_ms,
                    active_scene_id,
                    active_scene_source,
                    active_scene_name,
                    active_scene_prompt_chars,
                    active_scene_prompt_truncated,
                    output_status,
                    output_error,
                    mode,
                    answer_text
                )
             VALUES (?1, '', '', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20)",
                rusqlite::params![
                    entry.created_at,
                    entry.context_profile_id,
                    entry.context_label,
                    entry.context_icon_key,
                    context_family_db_value(entry.context_family),
                    entry.browser_access_status.as_history_value(),
                    entry.provider_kind.as_db_value(),
                    entry.raw_text,
                    entry.polished_text,
                    entry.language,
                    entry.duration_ms,
                    entry.active_scene_id,
                    entry.active_scene_source,
                    entry.active_scene_name,
                    entry.active_scene_prompt_chars,
                    entry.active_scene_prompt_truncated,
                    entry.output_status,
                    entry.output_error,
                    entry.mode,
                    entry.answer_text,
                ],
            )?;
            let id = transaction.last_insert_rowid();
            if let Some(intent) = &entry.ask_intent {
                transaction.execute(
                    "UPDATE history SET ask_intent = ?1 WHERE id = ?2",
                    rusqlite::params![intent, id],
                )?;
            }
            if self.local_identity_available {
                insert_history_local_identity(&transaction, id)?;
            }
            if let Some(sources) = citations {
                transaction.execute(
                    "INSERT INTO history_citations(history_id, sources) VALUES (?1, ?2)",
                    rusqlite::params![id, sources],
                )?;
            }
            if let Some(supports) = supports {
                transaction.execute(
                    "INSERT INTO history_citation_supports(history_id, supports) VALUES (?1, ?2)",
                    rusqlite::params![id, supports],
                )?;
            }
            if let Some(audio) = audio {
                transaction.execute(
                    "INSERT INTO history_audio(history_id, wav) VALUES (?1, ?2)",
                    rusqlite::params![id, audio],
                )?;
                transaction.execute(
                    "DELETE FROM history_audio WHERE history_id IN (
                        SELECT history_id FROM (
                            SELECT history_id,
                                SUM(length(wav)) OVER (ORDER BY history_id DESC) AS retained_bytes
                            FROM history_audio
                        ) WHERE retained_bytes > 268435456
                    )",
                    [],
                )?;
            }
            Self::apply_retention_in_transaction(&transaction, policy, &now_iso)?;
            transaction.commit()?;
            id
        };

        Ok(Some(id))
    }

    #[cfg(test)]
    pub(crate) fn read_citations_sync(
        &self,
        id: i64,
        question: &str,
        answer: &str,
    ) -> Result<Option<String>> {
        use rusqlite::OptionalExtension;
        let conn = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        Ok(conn
            .query_row(
                "SELECT sources FROM history_citations JOIN history ON history.id = history_citations.history_id
                 WHERE history_id = ?1 AND history.mode = 'ask' AND history.raw_text = ?2
                 AND history.answer_text = ?3 AND length(sources) <= 100000",
                rusqlite::params![id, question, answer],
                |row| row.get(0),
            )
            .optional()?)
    }

    pub(crate) fn read_saved_answer_sync(
        &self,
        id: i64,
        question: &str,
        answer: &str,
    ) -> Result<Option<SavedAnswerSnapshot>> {
        use rusqlite::OptionalExtension;
        if answer.trim().is_empty() {
            return Ok(None);
        }
        validate_saved_answer_input(id, question, answer)?;
        let conn = self
            .conn
            .lock()
            .map_err(|_| anyhow::anyhow!("Saved answer storage unavailable"))?;
        let saved: Option<SavedAnswerRow> = conn
            .query_row(
                "SELECT id, raw_text, answer_text,
                 (SELECT CASE WHEN length(CAST(sources AS BLOB)) <= 100000
                    THEN sources ELSE NULL END
                  FROM history_citations WHERE history_id = history.id),
                 EXISTS(SELECT 1 FROM history_citations WHERE history_id = history.id
                    AND length(CAST(sources AS BLOB)) > 100000),
                 ask_intent,
                 (SELECT CASE WHEN length(CAST(supports AS BLOB)) <= 10000
                    THEN supports ELSE NULL END FROM history_citation_supports WHERE history_id = history.id),
                 EXISTS(SELECT 1 FROM history_citation_supports WHERE history_id = history.id
                    AND length(CAST(supports AS BLOB)) > 10000)
                 FROM history WHERE id = ?1 AND mode = 'ask' AND raw_text = ?2 AND answer_text = ?3",
                rusqlite::params![id, question, answer],
                |row| {
                    Ok(SavedAnswerRow {
                        history_id: row.get(0)?,
                        question: row.get(1)?,
                        answer: row.get(2)?,
                        sources: row.get(3)?,
                        oversized_sources: row.get(4)?,
                        ask_intent: row.get(5)?,
                        supports: row.get(6)?,
                        oversized_supports: row.get(7)?,
                    })
                },
            )
            .optional()?;
        let Some(SavedAnswerRow {
            history_id,
            question,
            answer,
            sources,
            oversized_sources,
            ask_intent,
            supports,
            oversized_supports,
        }) = saved
        else {
            return Ok(None);
        };
        if oversized_sources || oversized_supports {
            anyhow::bail!("Saved answer sources unavailable");
        }
        if ask_intent
            .as_deref()
            .is_some_and(|intent| !matches!(intent, "open_question" | "ask_selection"))
        {
            anyhow::bail!("Saved answer intent unavailable");
        }
        let citations = match sources {
            Some(sources) => {
                if sources.len() > 100000 {
                    anyhow::bail!("Saved answer sources unavailable");
                }
                let value = serde_json::from_str(&sources)?;
                serde_json::from_str(&validated_citation_sources(&value, "ask", Some(&answer))?)?
            }
            None => serde_json::json!([]),
        };
        let citation_supports = if let Some(supports) = supports {
            if supports.len() > 10000 {
                anyhow::bail!("Saved answer supports unavailable");
            }
            let value: serde_json::Value = serde_json::from_str(&supports)?;
            let sources = crate::commands::ask_citations::parse_citations(&citations);
            let parsed = crate::commands::ask_citations::parse_citation_supports(
                &answer, &value, &citations, &sources,
            );
            if serde_json::to_value(parsed)? != value || !value.is_array() {
                anyhow::bail!("Saved answer supports unavailable");
            }
            Some(value)
        } else {
            None
        };
        let snapshot = SavedAnswerSnapshot {
            history_id,
            question,
            answer,
            citations,
            ask_intent,
            citation_supports,
        };
        let mut counter = SettingsSizeCounter {
            bytes: 0,
            limit: SAVED_ANSWER_MAX_BYTES,
        };
        serde_json::to_writer(&mut counter, &snapshot)
            .map_err(|_| anyhow::anyhow!("Saved answer response unavailable"))?;
        Ok(Some(snapshot))
    }

    #[cfg(test)]
    pub(crate) fn hold_citation_connection_for_test(&self, action: impl FnOnce()) {
        let _connection = self.conn.lock().unwrap();
        action();
    }

    pub async fn read_audio(&self, id: i64) -> Result<Option<Vec<u8>>> {
        use rusqlite::OptionalExtension;
        let conn = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let recording: Option<Option<Vec<u8>>> = conn
            .query_row(
                "SELECT CASE WHEN length(wav) BETWEEN 44 AND ?2 THEN wav ELSE NULL END
                 FROM history_audio WHERE history_id = ?1",
                rusqlite::params![id, crate::audio::archive::MAX_ARCHIVE_BYTES as i64],
                |row| row.get(0),
            )
            .optional()?;
        let Some(recording) = recording else {
            return Ok(None);
        };
        let audio =
            recording.ok_or_else(|| anyhow::anyhow!("Invalid or oversized history recording"))?;
        crate::audio::archive::decode_archive_wav(&audio)
            .map_err(|_| anyhow::anyhow!("Invalid or oversized history recording"))?;
        Ok(Some(audio))
    }

    pub async fn prune_with_policy(
        &self,
        policy: &HistoryRetentionPolicy,
        now_iso: &str,
    ) -> Result<()> {
        self.prune_with_policy_sync(policy, now_iso)
    }

    pub(crate) fn prune_with_policy_sync(
        &self,
        policy: &HistoryRetentionPolicy,
        now_iso: &str,
    ) -> Result<()> {
        let mut conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let transaction = conn.transaction()?;
        Self::apply_retention_in_transaction(&transaction, policy, now_iso)?;
        transaction.commit()?;
        Ok(())
    }

    pub async fn list(&self, limit: u32, offset: u32) -> Result<Vec<HistoryEntry>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(&format!(
            "{HISTORY_ENTRY_SELECT} FROM history ORDER BY id DESC LIMIT ?1 OFFSET ?2"
        ))?;
        let rows = stmt.query_map(rusqlite::params![limit, offset], history_entry_from_row)?;
        let mut entries = Vec::new();
        for row in rows {
            entries.push(row?);
        }
        Ok(entries)
    }

    pub(crate) fn cloud_backup_history_snapshot(&self) -> Result<Vec<HistoryEntry>> {
        let connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let mut query = connection.prepare(&format!(
            "{HISTORY_ENTRY_SELECT} FROM history ORDER BY id DESC LIMIT ?1"
        ))?;
        let rows = query.query_map([DEFAULT_HISTORY_MAX_ENTRIES + 1], history_entry_from_row)?;
        let entries = rows.collect::<rusqlite::Result<Vec<_>>>()?;
        if entries.len() > DEFAULT_HISTORY_MAX_ENTRIES as usize {
            anyhow::bail!("backup_history_too_large");
        }
        Ok(entries)
    }

    fn cloud_backup_data_snapshot_after_history(
        &self,
        after_history: impl FnOnce() -> Result<()>,
    ) -> Result<(Vec<HistoryEntry>, Vec<DictionaryEntry>, Vec<CorrectionRule>)> {
        let mut connection = self
            .conn
            .lock()
            .map_err(|_| anyhow::anyhow!("Backup storage unavailable"))?;
        let transaction = connection.transaction()?;
        let retained_text_bytes: i64 = transaction.query_row(
            "SELECT COALESCE(SUM(length(CAST(raw_text AS BLOB))
                + length(CAST(polished_text AS BLOB))
                + COALESCE(length(CAST(answer_text AS BLOB)), 0)), 0)
             FROM (SELECT raw_text, polished_text, answer_text FROM history ORDER BY id DESC LIMIT 5001)",
            [],
            |row| row.get(0),
        )?;
        anyhow::ensure!(
            (0..=20 * 1024 * 1024).contains(&retained_text_bytes),
            "backup_snapshot_too_large"
        );
        let history = {
            let mut query = transaction.prepare(&format!(
                "{HISTORY_ENTRY_SELECT} FROM history ORDER BY id DESC LIMIT ?1"
            ))?;
            let rows =
                query.query_map([DEFAULT_HISTORY_MAX_ENTRIES + 1], history_entry_from_row)?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        if history.len() > DEFAULT_HISTORY_MAX_ENTRIES as usize {
            anyhow::bail!("backup_history_too_large");
        }
        after_history()?;
        let dictionary = {
            let mut query = transaction.prepare(
                "SELECT id, word, pronunciation, auto FROM dictionary ORDER BY id ASC LIMIT 10001",
            )?;
            let rows = query.query_map([], |row| {
                Ok(DictionaryEntry {
                    id: row.get(0)?,
                    word: row.get(1)?,
                    pronunciation: row.get(2)?,
                    auto: row.get(3)?,
                })
            })?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        let correction_rules = {
            let mut query = transaction.prepare(
                "SELECT id, pattern, replacement, enabled FROM correction_rules ORDER BY id ASC LIMIT 10001",
            )?;
            let rows = query.query_map([], |row| {
                Ok(CorrectionRule {
                    id: row.get(0)?,
                    pattern: row.get(1)?,
                    replacement: row.get(2)?,
                    enabled: row.get::<_, i64>(3)? != 0,
                })
            })?;
            rows.collect::<rusqlite::Result<Vec<_>>>()?
        };
        if dictionary.len() > 10000 || correction_rules.len() > 10000 {
            anyhow::bail!("backup_dictionary_too_large");
        }
        transaction.commit()?;
        Ok((history, dictionary, correction_rules))
    }

    pub(crate) fn cloud_backup_data_snapshot(
        &self,
    ) -> Result<(Vec<HistoryEntry>, Vec<DictionaryEntry>, Vec<CorrectionRule>)> {
        self.cloud_backup_data_snapshot_after_history(|| Ok(()))
    }

    pub async fn search_page(
        &self,
        query: &str,
        mode: &str,
        limit: u32,
        before_id: Option<i64>,
    ) -> Result<Vec<HistoryEntry>> {
        anyhow::ensure!(
            matches!(mode, "all" | "dictate" | "ask"),
            "Invalid history mode"
        );
        let query = query.trim().to_lowercase();
        let conn = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let mut statement = conn.prepare(&format!(
            "{HISTORY_ENTRY_SELECT} FROM history
             WHERE (?1 IS NULL OR id < ?1)
             AND (?2 = 'all' OR (?2 = 'ask' AND mode = 'ask')
                  OR (?2 = 'dictate' AND mode != 'ask'))
             ORDER BY id DESC"
        ))?;
        let rows =
            statement.query_map(rusqlite::params![before_id, mode], history_entry_from_row)?;
        let mut entries = Vec::new();
        for row in rows {
            if entries.len() >= limit.min(DEFAULT_HISTORY_MAX_ENTRIES) as usize {
                break;
            }
            let entry = row?;
            if query.is_empty()
                || [
                    entry.raw_text.as_str(),
                    entry.polished_text.as_str(),
                    entry.context_label.as_str(),
                    entry.answer_text.as_deref().unwrap_or(""),
                ]
                .iter()
                .any(|text| text.to_lowercase().contains(&query))
            {
                entries.push(entry);
            }
        }
        Ok(entries)
    }

    pub async fn get(&self, id: i64) -> Result<Option<HistoryEntry>> {
        use rusqlite::OptionalExtension;
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        Ok(conn
            .query_row(
                &format!("{HISTORY_ENTRY_SELECT} FROM history WHERE id = ?1"),
                [id],
                history_entry_from_row,
            )
            .optional()?)
    }

    pub async fn latest_insertable_transcript(&self) -> Result<Option<String>> {
        let conn = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let mut statement = conn.prepare(
            "SELECT polished_text, raw_text, output_status FROM history
             WHERE mode != 'ask'
               AND (output_status IS NULL OR output_status NOT IN (?1, ?2, ?3))
             ORDER BY id DESC",
        )?;
        let rows = statement.query_map(
            rusqlite::params![
                HISTORY_STATUS_DISMISSED,
                HISTORY_STATUS_SILENT,
                HISTORY_STATUS_RECOGNITION_FAILED
            ],
            |row| {
                Ok((
                    row.get::<_, String>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, Option<String>>(2)?,
                ))
            },
        )?;
        for row in rows {
            let (polished, raw, status) = row?;
            if status.as_deref() == Some("fallback") && polished.trim().is_empty() {
                continue;
            }
            if let Some(text) = [polished.as_str(), raw.as_str()]
                .into_iter()
                .map(str::trim)
                .find(|text| !text.is_empty())
            {
                return Ok(Some(text.to_owned()));
            };
        }
        Ok(None)
    }

    pub async fn clear(&self) -> Result<()> {
        let _reservation = self.reserve_history_sync()?;
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute("DELETE FROM history", [])?;
        Ok(())
    }

    /// Typeless replaces a recovered row's text after a successful retry, so it becomes an ordinary
    /// transcript. Only recording-only rows are updated; finished transcripts are never rewritten.
    pub async fn complete_recovered(&self, id: i64, text: &str) -> Result<bool> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        Ok(conn.execute(
            &format!(
                "UPDATE history SET raw_text = ?1, polished_text = '', output_status = NULL,
                 output_error = NULL
                 WHERE id = ?2 AND output_status IN {RECORDING_ONLY_STATUSES_SQL}"
            ),
            rusqlite::params![text, id],
        )? > 0)
    }

    /// Fills a cancelled or failed take with the result of recovering it; false once it was
    /// already recovered.
    pub async fn complete_unfinished_take(
        &self,
        id: i64,
        raw_text: &str,
        polished_text: &str,
        output_status: Option<&str>,
        output_error: Option<&str>,
    ) -> Result<bool> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        Ok(conn.execute(
            "UPDATE history SET raw_text = ?1, polished_text = ?2, output_status = ?3,
             output_error = ?4
             WHERE id = ?5 AND output_status IN (?6, ?7)",
            rusqlite::params![
                raw_text,
                polished_text,
                output_status,
                output_error,
                id,
                HISTORY_STATUS_DISMISSED,
                HISTORY_STATUS_RECOGNITION_FAILED
            ],
        )? > 0)
    }

    pub async fn delete(&self, id: i64) -> Result<bool> {
        let _reservation = self.reserve_history_sync()?;
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        Ok(conn.execute("DELETE FROM history WHERE id = ?1", rusqlite::params![id])? > 0)
    }

    pub(crate) fn delete_history_with_local_commit(&self, id: i64) -> Result<bool> {
        if id <= 0 {
            return Ok(false);
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let transaction =
            connection.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        let deleted = transaction.execute("DELETE FROM history WHERE id = ?1", [id])? > 0;
        transaction.commit()?;
        Ok(deleted)
    }

    pub(crate) fn delete_history_with_sync_commit(
        &self,
        account_id: &str,
        id: i64,
        commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
    ) -> Result<Option<history_sync_queue::PendingHistoryMutation>> {
        if !self.local_identity_available {
            anyhow::bail!("History sync deletion unavailable");
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::delete_with_commit(&mut connection, account_id, id, commit)
    }

    pub(crate) fn clear_history_with_sync_commit(
        &self,
        account_id: &str,
        commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
    ) -> Result<()> {
        if !self.local_identity_available {
            anyhow::bail!("History sync deletion unavailable");
        }
        let mut connection = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        history_sync_queue::clear_with_commit(&mut connection, account_id, commit)
    }

    /// Usage insights over every stored dictation and translation (Ask questions excluded).
    pub async fn stats(&self, today: chrono::NaiveDate) -> Result<stats::UsageStats> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(&format!(
            "SELECT created_at, polished_text, raw_text, duration_ms FROM history
             WHERE mode <> 'ask' AND COALESCE(output_status, '') NOT IN {RECORDING_ONLY_STATUSES_SQL}"
        ))?;
        let samples = stmt
            .query_map([], |row| {
                let created_at: String = row.get(0)?;
                let polished: String = row.get(1)?;
                let raw: String = row.get(2)?;
                Ok((
                    created_at,
                    if polished.trim().is_empty() {
                        raw
                    } else {
                        polished
                    },
                    row.get(3)?,
                ))
            })?
            .filter_map(|row| {
                let (created_at, text, duration_ms) = row.ok()?;
                let date =
                    chrono::NaiveDate::parse_from_str(created_at.get(..10)?, "%Y-%m-%d").ok()?;
                Some(stats::StatsSample {
                    date,
                    text,
                    duration_ms,
                })
            })
            .collect::<Vec<_>>();
        Ok(stats::compute_usage_stats(&samples, today))
    }

    /// Restores cloud backup sections in one SQLite transaction. Dictionary and
    /// correction rows live in the same database, so using the history
    /// connection here prevents a partially restored local data set.
    pub async fn restore_backup_data(
        &self,
        history: Option<Vec<HistoryEntry>>,
        dictionary: Option<Vec<DictionaryEntry>>,
        correction_rules: Option<Vec<CorrectionRule>>,
        policy: &HistoryRetentionPolicy,
        now_iso: &str,
    ) -> Result<()> {
        self.restore_backup_data_sync(history, dictionary, correction_rules, policy, now_iso)
    }

    pub(crate) fn restore_backup_data_sync(
        &self,
        history: Option<Vec<HistoryEntry>>,
        dictionary: Option<Vec<DictionaryEntry>>,
        correction_rules: Option<Vec<CorrectionRule>>,
        policy: &HistoryRetentionPolicy,
        now_iso: &str,
    ) -> Result<()> {
        if history
            .as_ref()
            .is_some_and(|entries| entries.len() > DEFAULT_HISTORY_MAX_ENTRIES as usize)
        {
            anyhow::bail!("backup_history_too_large");
        }
        let dictionary = dictionary.map(prepare_backup_dictionary).transpose()?;
        let correction_rules = correction_rules
            .map(prepare_backup_correction_rules)
            .transpose()?;

        let mut conn = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let transaction = conn.transaction()?;

        if let Some(entries) = history {
            transaction.execute("DELETE FROM history", [])?;
            if policy.enabled {
                let max_entries = policy.max_entries.clamp(1, DEFAULT_HISTORY_MAX_ENTRIES) as usize;
                let cutoff = if policy.retention_days > 0 {
                    let now = chrono::NaiveDateTime::parse_from_str(now_iso, "%Y-%m-%dT%H:%M:%S")?;
                    Some(
                        now.checked_sub_signed(chrono::Duration::days(
                            policy.retention_days as i64,
                        ))
                        .ok_or_else(|| {
                            anyhow::anyhow!("History retention cutoff is out of range")
                        })?,
                    )
                } else {
                    None
                };
                let mut entries = entries
                    .into_iter()
                    .map(|entry| {
                        let created_at = chrono::NaiveDateTime::parse_from_str(
                            &entry.created_at,
                            "%Y-%m-%dT%H:%M:%S",
                        )?;
                        Ok((created_at, entry))
                    })
                    .collect::<Result<Vec<_>>>()?;
                entries.retain(|(created_at, _)| cutoff.is_none_or(|cutoff| *created_at >= cutoff));
                entries.sort_by_key(|entry| std::cmp::Reverse(entry.0));
                entries.truncate(max_entries);
                entries.reverse();
                for (_, entry) in entries {
                    let sources = validated_history_citations(&entry)?;
                    let supports = validated_history_supports(&entry)?;
                    validate_history_ask_intent(&entry)?;
                    transaction.execute(
                        "INSERT INTO history (
                            created_at,
                            app_name,
                            app_type,
                            context_profile_id,
                            context_label,
                            context_icon_key,
                            context_family,
                            browser_access_status,
                            provider_kind,
                            raw_text,
                            polished_text,
                            language,
                            duration_ms,
                            active_scene_id,
                            active_scene_source,
                            active_scene_name,
                            active_scene_prompt_chars,
                            active_scene_prompt_truncated,
                            output_status,
                            output_error,
                            mode,
                            answer_text
                        ) VALUES (?1, '', '', ?2, ?3, ?4, ?5, ?6, ?7, ?8, ?9, ?10, ?11, ?12, ?13, ?14, ?15, ?16, ?17, ?18, ?19, ?20)",
                        rusqlite::params![
                            entry.created_at,
                            entry.context_profile_id,
                            entry.context_label,
                            entry.context_icon_key,
                            context_family_db_value(entry.context_family),
                            entry.browser_access_status.as_history_value(),
                            entry.provider_kind.as_db_value(),
                            entry.raw_text,
                            entry.polished_text,
                            entry.language,
                            entry.duration_ms,
                            entry.active_scene_id,
                            entry.active_scene_source,
                            entry.active_scene_name,
                            entry.active_scene_prompt_chars,
                            entry.active_scene_prompt_truncated,
                            entry.output_status,
                            entry.output_error,
                            entry.mode,
                            entry.answer_text,
                        ],
                    )?;
                    let restored_id = transaction.last_insert_rowid();
                    if let Some(intent) = &entry.ask_intent {
                        transaction.execute(
                            "UPDATE history SET ask_intent = ?1 WHERE id = ?2",
                            rusqlite::params![intent, restored_id],
                        )?;
                    }
                    if self.local_identity_available {
                        insert_history_local_identity(&transaction, restored_id)?;
                    }
                    if let Some(sources) = sources {
                        transaction.execute(
                            "INSERT INTO history_citations(history_id, sources) VALUES (?1, ?2)",
                            rusqlite::params![restored_id, sources],
                        )?;
                    }
                    if let Some(supports) = supports {
                        transaction.execute(
                            "INSERT INTO history_citation_supports(history_id, supports) VALUES (?1, ?2)",
                            rusqlite::params![restored_id, supports],
                        )?;
                    }
                }
            }
        }

        if let Some(entries) = dictionary {
            transaction.execute("DELETE FROM dictionary", [])?;
            for (word, pronunciation, auto) in entries {
                transaction.execute(
                    "INSERT INTO dictionary (word, pronunciation, auto) VALUES (?1, ?2, ?3)",
                    rusqlite::params![word, pronunciation, auto],
                )?;
            }
        }

        if let Some(rules) = correction_rules {
            transaction.execute("DELETE FROM correction_rules", [])?;
            for (pattern, replacement, enabled) in rules {
                transaction.execute(
                    "INSERT INTO correction_rules (pattern, replacement, enabled) VALUES (?1, ?2, ?3)",
                    rusqlite::params![pattern, replacement, if enabled { 1 } else { 0 }],
                )?;
            }
        }

        transaction.commit()?;
        Ok(())
    }
}

type PreparedDictionaryRow = (String, Option<String>, bool);

pub(crate) fn prepare_backup_dictionary(
    entries: Vec<DictionaryEntry>,
) -> Result<Vec<PreparedDictionaryRow>> {
    if entries.len() > MAX_BACKUP_DICTIONARY_ENTRIES {
        anyhow::bail!("backup_dictionary_too_large");
    }
    let mut seen = HashSet::new();
    let mut prepared = Vec::with_capacity(entries.len());
    for entry in entries {
        let word = validate_dictionary_text(&entry.word, 100, "dictionary_word")?;
        let pronunciation = entry
            .pronunciation
            .as_deref()
            .map(|value| validate_dictionary_text(value, 100, "dictionary_pronunciation"))
            .transpose()?
            .filter(|value| !value.is_empty());
        if seen.insert(normalized_dictionary_identity(&word)) {
            prepared.push((word, pronunciation, entry.auto));
        }
    }
    Ok(prepared)
}

pub(crate) fn prepare_backup_correction_rules(
    entries: Vec<CorrectionRule>,
) -> Result<Vec<(String, String, bool)>> {
    if entries.len() > MAX_BACKUP_CORRECTION_RULES {
        anyhow::bail!("backup_corrections_too_large");
    }
    let mut seen = HashSet::new();
    let mut prepared = Vec::with_capacity(entries.len());
    for entry in entries {
        let pattern = validate_dictionary_text(&entry.pattern, 120, "correction_pattern")?;
        let replacement =
            validate_dictionary_text(&entry.replacement, 120, "correction_replacement")?;
        if seen.insert(normalized_correction_identity(&pattern, &replacement)) {
            prepared.push((pattern, replacement, entry.enabled));
        }
    }
    Ok(prepared)
}

fn ensure_history_optional_columns(conn: &Connection) -> Result<()> {
    let mut stmt = conn.prepare("PRAGMA table_info(history)")?;
    let columns = stmt
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<std::result::Result<std::collections::HashSet<_>, _>>()?;

    for (name, ddl) in [
        ("active_scene_id", "ALTER TABLE history ADD COLUMN active_scene_id TEXT"),
        (
            "active_scene_source",
            "ALTER TABLE history ADD COLUMN active_scene_source TEXT",
        ),
        (
            "active_scene_name",
            "ALTER TABLE history ADD COLUMN active_scene_name TEXT",
        ),
        (
            "active_scene_prompt_chars",
            "ALTER TABLE history ADD COLUMN active_scene_prompt_chars INTEGER",
        ),
        (
            "active_scene_prompt_truncated",
            "ALTER TABLE history ADD COLUMN active_scene_prompt_truncated INTEGER NOT NULL DEFAULT 0",
        ),
        (
            "output_status",
            "ALTER TABLE history ADD COLUMN output_status TEXT",
        ),
        (
            "output_error",
            "ALTER TABLE history ADD COLUMN output_error TEXT",
        ),
        (
            "context_profile_id",
            "ALTER TABLE history ADD COLUMN context_profile_id TEXT NOT NULL DEFAULT 'general.native'",
        ),
        (
            "context_label",
            "ALTER TABLE history ADD COLUMN context_label TEXT NOT NULL DEFAULT 'General'",
        ),
        (
            "context_icon_key",
            "ALTER TABLE history ADD COLUMN context_icon_key TEXT NOT NULL DEFAULT 'general'",
        ),
        (
            "context_family",
            "ALTER TABLE history ADD COLUMN context_family TEXT NOT NULL DEFAULT 'general'",
        ),
        (
            "browser_access_status",
            "ALTER TABLE history ADD COLUMN browser_access_status TEXT",
        ),
        (
            "provider_kind",
            "ALTER TABLE history ADD COLUMN provider_kind TEXT NOT NULL DEFAULT 'local'",
        ),
        (
            "mode",
            "ALTER TABLE history ADD COLUMN mode TEXT NOT NULL DEFAULT 'dictate'",
        ),
        ("answer_text", "ALTER TABLE history ADD COLUMN answer_text TEXT"),
        ("ask_intent", "ALTER TABLE history ADD COLUMN ask_intent TEXT"),
    ] {
        if !columns.contains(name) {
            conn.execute(ddl, [])?;
        }
    }

    Ok(())
}

fn migrate_legacy_history_context(conn: &Connection) -> Result<()> {
    let registry = AppRegistry::builtin().map_err(anyhow::Error::msg)?;
    let rows = {
        let mut statement = conn.prepare(
            "SELECT id, app_name, app_type FROM history WHERE app_name <> '' OR app_type <> ''",
        )?;
        let rows = statement
            .query_map([], |row| {
                Ok((
                    row.get::<_, i64>(0)?,
                    row.get::<_, String>(1)?,
                    row.get::<_, String>(2)?,
                ))
            })?
            .collect::<std::result::Result<Vec<_>, _>>()?;
        rows
    };
    if rows.is_empty() {
        return Ok(());
    }

    let transaction = conn.unchecked_transaction()?;
    for (id, app_name, _app_type) in rows {
        let profile = legacy_profile_for_name(&registry, &app_name)
            .unwrap_or_else(ContextProfile::general_native);
        transaction.execute(
            "UPDATE history SET
                context_profile_id = ?1,
                context_label = ?2,
                context_icon_key = ?3,
                context_family = ?4,
                provider_kind = 'local',
                app_name = '',
                app_type = ''
             WHERE id = ?5",
            rusqlite::params![
                profile.id,
                profile.app_label,
                profile.icon_key,
                context_family_db_value(profile.family),
                id,
            ],
        )?;
    }
    transaction.commit()?;
    Ok(())
}

fn legacy_profile_for_name(registry: &AppRegistry, app_name: &str) -> Option<ContextProfile> {
    let normalized = app_name.trim().to_ascii_lowercase();
    if normalized.is_empty() {
        return None;
    }

    registry.definitions().iter().find_map(|definition| {
        let matches = definition.app_label.eq_ignore_ascii_case(&normalized)
            || definition
                .native_identities
                .iter()
                .chain(definition.process_aliases.iter())
                .any(|value| value.eq_ignore_ascii_case(&normalized));
        matches.then(|| registry.profile(&definition.id)).flatten()
    })
}

fn context_family_db_value(family: ContextFamily) -> &'static str {
    match family {
        ContextFamily::Email => "email",
        ContextFamily::WorkChat => "work_chat",
        ContextFamily::PersonalChat => "personal_chat",
        ContextFamily::Document => "document",
        ContextFamily::ProjectManagement => "project_management",
        ContextFamily::DeveloperCollaboration => "developer_collaboration",
        ContextFamily::PromptOrCode => "prompt_or_code",
        ContextFamily::Support => "support",
        ContextFamily::Social => "social",
        ContextFamily::General => "general",
    }
}

fn context_family_from_db(value: &str) -> ContextFamily {
    match value {
        "email" => ContextFamily::Email,
        "work_chat" => ContextFamily::WorkChat,
        "personal_chat" => ContextFamily::PersonalChat,
        "document" => ContextFamily::Document,
        "project_management" => ContextFamily::ProjectManagement,
        "developer_collaboration" => ContextFamily::DeveloperCollaboration,
        "prompt_or_code" => ContextFamily::PromptOrCode,
        "support" => ContextFamily::Support,
        "social" => ContextFamily::Social,
        _ => ContextFamily::General,
    }
}

// ─── DictionaryStore (SQLite backed) ───

#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct DictionaryEntry {
    pub id: i64,
    pub word: String,
    pub pronunciation: Option<String>,
    #[serde(default)]
    pub auto: bool,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct CorrectionRule {
    pub id: i64,
    pub pattern: String,
    pub replacement: String,
    pub enabled: bool,
}

pub struct DictionaryStore {
    conn: Mutex<Connection>,
}

struct AutomaticDictionaryBusyBudget<'a> {
    connection: &'a Connection,
    previous_timeout: std::time::Duration,
}

impl<'a> AutomaticDictionaryBusyBudget<'a> {
    fn acquire(connection: &'a Connection) -> Result<Self> {
        let milliseconds: u32 =
            connection.query_row("PRAGMA busy_timeout", [], |row| row.get(0))?;
        let previous_timeout = std::time::Duration::from_millis(u64::from(milliseconds));
        connection.busy_timeout(previous_timeout.min(std::time::Duration::from_millis(250)))?;
        Ok(Self {
            connection,
            previous_timeout,
        })
    }
}

impl Drop for AutomaticDictionaryBusyBudget<'_> {
    fn drop(&mut self) {
        if self.connection.busy_timeout(self.previous_timeout).is_err() {
            tracing::warn!("Could not restore dictionary lock timeout");
        }
    }
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
            );
            CREATE TABLE IF NOT EXISTS correction_rules (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                pattern TEXT NOT NULL,
                replacement TEXT NOT NULL,
                enabled INTEGER NOT NULL DEFAULT 1,
                created_at TEXT NOT NULL DEFAULT (datetime('now'))
            );",
        )?;
        migrate_dictionary_columns(&conn)?;
        Ok(Self {
            conn: Mutex::new(conn),
        })
    }

    pub async fn add(&self, word: &str, pronunciation: Option<&str>) -> Result<()> {
        self.insert(word, pronunciation, false).map(|_| ())
    }

    /// Adds a word learned from the user's own edits; returns the new row id.
    pub async fn add_auto(&self, word: &str) -> Result<i64> {
        self.insert(word, None, true)
    }

    fn insert(&self, word: &str, pronunciation: Option<&str>, auto: bool) -> Result<i64> {
        self.insert_if_current(word, pronunciation, auto, || true)?
            .ok_or_else(|| anyhow::anyhow!("dictionary_insert_cancelled"))
    }

    #[cfg(any(target_os = "macos", test))]
    pub(crate) async fn add_auto_if_current(
        &self,
        word: &str,
        is_current: impl FnOnce() -> bool,
    ) -> Result<Option<i64>> {
        self.insert_if_current(word, None, true, is_current)
    }

    fn insert_if_current(
        &self,
        word: &str,
        pronunciation: Option<&str>,
        auto: bool,
        is_current: impl FnOnce() -> bool,
    ) -> Result<Option<i64>> {
        let word = validate_dictionary_text(word, 100, "dictionary_word")?;
        let pronunciation = pronunciation
            .map(|value| validate_dictionary_text(value, 100, "dictionary_pronunciation"))
            .transpose()?
            .filter(|value| !value.is_empty());
        let conn = if auto {
            self.conn
                .try_lock()
                .map_err(|_| anyhow::anyhow!("dictionary_busy"))?
        } else {
            self.conn.lock().unwrap_or_else(|e| e.into_inner())
        };
        let _busy_budget = auto
            .then(|| AutomaticDictionaryBusyBudget::acquire(&conn))
            .transpose()?;
        let transaction =
            rusqlite::Transaction::new_unchecked(&conn, rusqlite::TransactionBehavior::Immediate)?;
        if dictionary_identity_exists(&transaction, &normalized_dictionary_identity(&word), None)? {
            anyhow::bail!("dictionary_duplicate");
        }
        if !is_current() {
            return Ok(None);
        }
        transaction.execute(
            "INSERT INTO dictionary (word, pronunciation, auto) VALUES (?1, ?2, ?3)",
            rusqlite::params![word, pronunciation, auto],
        )?;
        let id = transaction.last_insert_rowid();
        transaction.commit()?;
        Ok(Some(id))
    }

    pub async fn update(&self, id: i64, word: &str, pronunciation: Option<&str>) -> Result<()> {
        let word = validate_dictionary_text(word, 100, "dictionary_word")?;
        let pronunciation = pronunciation
            .map(|value| validate_dictionary_text(value, 100, "dictionary_pronunciation"))
            .transpose()?
            .filter(|value| !value.is_empty());
        let mut conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let transaction =
            conn.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        if dictionary_identity_exists(
            &transaction,
            &normalized_dictionary_identity(&word),
            Some(id),
        )? {
            anyhow::bail!("dictionary_duplicate");
        }
        let updated = transaction.execute(
            "UPDATE dictionary SET word = ?2, pronunciation = ?3 WHERE id = ?1",
            rusqlite::params![id, word, pronunciation],
        )?;
        if updated == 0 {
            anyhow::bail!("dictionary_entry_not_found");
        }
        transaction.commit()?;
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

    pub async fn remove_many(&self, ids: &[i64]) -> Result<usize> {
        self.with_transaction(|transaction| {
            let mut deleted = 0;
            for id in ids {
                deleted += transaction.execute(
                    "DELETE FROM dictionary WHERE id = ?1",
                    rusqlite::params![id],
                )?;
            }
            Ok(deleted)
        })
    }

    pub async fn list(&self) -> Result<Vec<DictionaryEntry>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt =
            conn.prepare("SELECT id, word, pronunciation, auto FROM dictionary ORDER BY id ASC")?;
        let rows = stmt.query_map([], |row| {
            Ok(DictionaryEntry {
                id: row.get(0)?,
                word: row.get(1)?,
                pronunciation: row.get(2)?,
                auto: row.get(3)?,
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

    pub async fn add_correction(&self, pattern: &str, replacement: &str) -> Result<()> {
        let pattern = validate_dictionary_text(pattern, 120, "correction_pattern")?;
        let replacement = validate_dictionary_text(replacement, 120, "correction_replacement")?;
        let identity = normalized_correction_identity(&pattern, &replacement);
        let mut conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let transaction =
            conn.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        if correction_identity_exists(&transaction, &identity, None)? {
            anyhow::bail!("correction_duplicate");
        }
        transaction.execute(
            "INSERT INTO correction_rules (pattern, replacement, enabled) VALUES (?1, ?2, 1)",
            rusqlite::params![pattern, replacement],
        )?;
        transaction.commit()?;
        Ok(())
    }

    pub async fn update_correction(
        &self,
        id: i64,
        pattern: &str,
        replacement: &str,
        enabled: bool,
    ) -> Result<()> {
        let pattern = validate_dictionary_text(pattern, 120, "correction_pattern")?;
        let replacement = validate_dictionary_text(replacement, 120, "correction_replacement")?;
        let identity = normalized_correction_identity(&pattern, &replacement);
        let mut conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let transaction =
            conn.transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)?;
        if correction_identity_exists(&transaction, &identity, Some(id))? {
            anyhow::bail!("correction_duplicate");
        }
        let updated = transaction.execute(
            "UPDATE correction_rules
             SET pattern = ?2, replacement = ?3, enabled = ?4
             WHERE id = ?1",
            rusqlite::params![id, pattern, replacement, if enabled { 1 } else { 0 }],
        )?;
        if updated == 0 {
            anyhow::bail!("correction_rule_not_found");
        }
        transaction.commit()?;
        Ok(())
    }

    pub async fn remove_correction(&self, id: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "DELETE FROM correction_rules WHERE id = ?1",
            rusqlite::params![id],
        )?;
        Ok(())
    }

    pub async fn set_correction_enabled(&self, id: i64, enabled: bool) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE correction_rules SET enabled = ?2 WHERE id = ?1",
            rusqlite::params![id, if enabled { 1 } else { 0 }],
        )?;
        Ok(())
    }

    pub async fn correction_rules(&self) -> Result<Vec<CorrectionRule>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(
            "SELECT id, pattern, replacement, enabled FROM correction_rules ORDER BY id ASC",
        )?;
        let rows = stmt.query_map([], |row| {
            Ok(CorrectionRule {
                id: row.get(0)?,
                pattern: row.get(1)?,
                replacement: row.get(2)?,
                enabled: row.get::<_, i64>(3)? != 0,
            })
        })?;
        let mut entries = Vec::new();
        for row in rows {
            entries.push(row?);
        }
        Ok(entries)
    }

    pub async fn enabled_correction_rules(&self) -> Vec<CorrectionRule> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = match conn.prepare(
            "SELECT id, pattern, replacement, enabled FROM correction_rules WHERE enabled = 1 ORDER BY id ASC LIMIT 100",
        ) {
            Ok(s) => s,
            Err(_) => return Vec::new(),
        };
        let rows = match stmt.query_map([], |row| {
            Ok(CorrectionRule {
                id: row.get(0)?,
                pattern: row.get(1)?,
                replacement: row.get(2)?,
                enabled: row.get::<_, i64>(3)? != 0,
            })
        }) {
            Ok(r) => r,
            Err(_) => return Vec::new(),
        };
        rows.filter_map(|r| r.ok()).collect()
    }

    pub(crate) fn with_transaction<T>(
        &self,
        operation: impl FnOnce(&rusqlite::Transaction<'_>) -> Result<T>,
    ) -> Result<T> {
        let mut conn = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        let transaction = conn.transaction()?;
        let result = operation(&transaction)?;
        transaction.commit()?;
        Ok(result)
    }

    #[cfg(test)]
    pub(crate) fn execute_batch_for_test(&self, sql: &str) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|error| error.into_inner());
        conn.execute_batch(sql)?;
        Ok(())
    }
}

fn migrate_dictionary_columns(conn: &Connection) -> Result<()> {
    let mut statement = conn.prepare("PRAGMA table_info(dictionary)")?;
    let has_auto = statement
        .query_map([], |row| row.get::<_, String>(1))?
        .filter_map(|name| name.ok())
        .any(|name| name == "auto");
    drop(statement);
    if !has_auto {
        conn.execute(
            "ALTER TABLE dictionary ADD COLUMN auto INTEGER NOT NULL DEFAULT 0",
            [],
        )?;
    }
    Ok(())
}

pub(crate) fn normalized_dictionary_identity(value: &str) -> String {
    value.nfkc().collect::<String>().trim().to_lowercase()
}

pub(crate) fn normalized_correction_identity(pattern: &str, replacement: &str) -> (String, String) {
    (
        normalized_dictionary_identity(pattern),
        normalized_dictionary_identity(replacement),
    )
}

fn validate_dictionary_text(value: &str, max_chars: usize, field: &str) -> Result<String> {
    let value = value.replace('\0', "").trim().to_string();
    if value.is_empty() {
        anyhow::bail!("{field}_empty");
    }
    if value.chars().count() > max_chars {
        anyhow::bail!("{field}_too_long");
    }
    Ok(value)
}

fn dictionary_identity_exists(
    conn: &Connection,
    identity: &str,
    excluding_id: Option<i64>,
) -> Result<bool> {
    let mut statement = conn.prepare("SELECT id, word FROM dictionary")?;
    let rows = statement.query_map([], |row| {
        Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
    })?;
    for row in rows {
        let (id, word) = row?;
        if Some(id) != excluding_id && normalized_dictionary_identity(&word) == identity {
            return Ok(true);
        }
    }
    Ok(false)
}

fn correction_identity_exists(
    conn: &Connection,
    identity: &(String, String),
    excluding_id: Option<i64>,
) -> Result<bool> {
    let mut statement = conn.prepare("SELECT id, pattern, replacement FROM correction_rules")?;
    let rows = statement.query_map([], |row| {
        Ok((
            row.get::<_, i64>(0)?,
            row.get::<_, String>(1)?,
            row.get::<_, String>(2)?,
        ))
    })?;
    for row in rows {
        let (id, pattern, replacement) = row?;
        if Some(id) != excluding_id
            && normalized_correction_identity(&pattern, &replacement) == *identity
        {
            return Ok(true);
        }
    }
    Ok(false)
}

#[cfg(test)]
mod tests {
    #[test]
    fn settings_size_counter_stops_before_the_limit_is_exceeded() {
        use std::io::Write;
        let mut counter = SettingsSizeCounter { bytes: 0, limit: 4 };
        counter.write_all(b"1234").unwrap();
        assert_eq!(counter.bytes, 4);
        assert!(counter.write_all(b"5").is_err());
        assert_eq!(counter.bytes, 4);
        let mut counter = SettingsSizeCounter {
            bytes: 0,
            limit: usize::MAX,
        };
        counter.write_all(b"ok").unwrap();
        assert_eq!(counter.bytes, 2);
    }

    #[test]
    fn settings_size_check_does_not_materialize_serialized_output() {
        let source = include_str!("mod.rs");
        let method = source
            .split("fn persist_config_value<R: tauri::Runtime>(")
            .nth(1)
            .unwrap()
            .split("// ─── HistoryStore")
            .next()
            .unwrap();
        assert!(!method.contains("serde_json::to_vec_pretty"));
        assert!(method.contains("serde_json::to_writer_pretty"));
    }

    #[test]
    fn invalid_onboarding_marker_is_rejected_without_rewriting_settings() {
        let directory = std::env::temp_dir().join(format!("otl-marker-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("settings.json");
        for marker in [
            serde_json::Value::Null,
            serde_json::json!("true"),
            serde_json::json!(1),
            serde_json::json!([]),
            serde_json::json!({}),
        ] {
            let bytes = serde_json::to_vec(&serde_json::json!({
                "app_config": {"ui_language": "ja"},
                "onboarding_completed": marker
            }))
            .unwrap();
            std::fs::write(&path, &bytes).unwrap();
            assert!(validate_settings_file(&path).is_err());
            assert_eq!(std::fs::read(&path).unwrap(), bytes);
        }
        for marker in [false, true] {
            std::fs::write(
                &path,
                serde_json::to_vec(&serde_json::json!({"onboarding_completed": marker})).unwrap(),
            )
            .unwrap();
            assert!(validate_settings_file(&path).is_ok());
        }
        std::fs::remove_file(&path).unwrap();
        std::fs::remove_dir(&directory).unwrap();
    }

    #[test]
    fn settings_file_validation_rejects_corruption_without_changing_bytes() {
        let directory =
            std::env::temp_dir().join(format!("otl-settings-validation-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("settings.json");
        assert!(validate_settings_file(&path).is_ok());
        for bytes in [
            br#"{"app_config":{"history_enabled":false}"#.as_slice(),
            b"[]".as_slice(),
            br#"{"app_config":[]}"#.as_slice(),
            b"{\"app_config\":\xff}".as_slice(),
        ] {
            std::fs::write(&path, bytes).unwrap();
            assert!(validate_settings_file(&path).is_err());
            assert_eq!(std::fs::read(&path).unwrap(), bytes);
        }
        std::fs::write(&path, br#"{"app_config":{"history_enabled":false}}"#).unwrap();
        assert!(validate_settings_file(&path).is_ok());
        assert!(validate_settings_file(&directory).is_err());
        std::fs::remove_file(&path).unwrap();
        std::fs::remove_dir(&directory).unwrap();
    }

    #[test]
    fn settings_file_validation_bounds_reads_and_preserves_oversized_files() {
        let directory =
            std::env::temp_dir().join(format!("otl-settings-size-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let path = directory.join("settings.json");
        let limit = 8 * 1024 * 1024;
        let mut boundary = br#"{"app_config":{}}"#.to_vec();
        boundary.resize(limit, b' ');
        std::fs::write(&path, &boundary).unwrap();
        assert!(validate_settings_file(&path).is_ok());
        boundary.push(b' ');
        std::fs::write(&path, &boundary).unwrap();
        let error = validate_settings_file(&path).unwrap_err();
        assert_eq!(error.to_string(), "Application settings are too large");
        assert_eq!(std::fs::read(&path).unwrap(), boundary);
        std::fs::remove_file(&path).unwrap();
        std::fs::remove_dir(&directory).unwrap();
    }

    #[test]
    fn stored_config_requires_an_object_instead_of_a_defaultable_sequence() {
        for value in [
            serde_json::json!([]),
            serde_json::json!([null]),
            serde_json::Value::Null,
            serde_json::json!("synthetic-invalid-config"),
            serde_json::json!(false),
            serde_json::json!(42),
        ] {
            assert!(AppConfig::from_stored_value(value).is_err());
        }
        assert!(AppConfig::from_stored_value(serde_json::json!({})).is_ok());
    }

    #[test]
    fn explicit_hotkey_normalization_does_not_run_platform_default_migration() {
        let source = include_str!("mod.rs");
        let method = source
            .split("    fn normalize_hotkey_settings(&mut self) {")
            .nth(1)
            .unwrap()
            .split("    fn ")
            .next()
            .unwrap();
        assert!(!method.contains("migrate_platform_typed_hotkeys"));
    }

    #[test]
    fn explicit_right_alt_bindings_survive_normalization_and_reopen() {
        for mode in ["auto", "toggle", "hold"] {
            let mut config = AppConfig::default();
            let dictation = ShortcutBinding::from_hotkey("RightAlt").unwrap();
            let ask = ShortcutBinding::from_hotkey("RightAlt+Space").unwrap();
            let translate = ShortcutBinding::from_hotkey("RightAlt+LeftShift").unwrap();
            config.hotkeys.dictation = dictation.clone();
            config.hotkeys.dictation_bindings = vec![dictation];
            config.hotkeys.ask = Some(ask.clone());
            config.hotkeys.ask_bindings = vec![ask];
            config.hotkeys.translate = Some(translate.clone());
            config.hotkeys.translate_bindings = vec![translate];
            config.hotkeys.dictation_mode = mode.to_string();
            let expected = config.hotkeys.clone();
            config.normalize_values();
            assert_eq!(config.hotkeys, expected);
            assert_eq!(config.hotkey, "RightAlt");
            assert_eq!(config.ask_hotkey, "RightAlt+Space");
            assert_eq!(config.hotkey_mode, mode);
            let reopened =
                AppConfig::from_stored_value(serde_json::to_value(&config).unwrap()).unwrap();
            assert_eq!(reopened.hotkeys, expected);
        }
    }

    #[test]
    fn config_revision_capacity_rejects_unsafe_integers_and_overflow() {
        assert_eq!(checked_config_revision(0).unwrap(), 1);
        assert_eq!(
            checked_config_revision(9_007_199_254_740_990).unwrap(),
            9_007_199_254_740_991
        );
        assert!(checked_config_revision(9_007_199_254_740_991).is_err());
        assert!(checked_config_revision(u64::MAX).is_err());
    }

    #[test]
    fn credential_migration_transaction_uses_current_cache_and_persists_even_without_changes() {
        let source = include_str!("mod.rs");
        let method = source
            .split("    pub(crate) fn migrate_current_credentials_commit(")
            .nth(1)
            .expect("current credential migration transaction is missing")
            .split("    pub(crate) fn try_update_current_config_commit(")
            .next()
            .unwrap();
        assert!(
            method.find("self.cache.lock()").unwrap()
                < method.find("migrate_legacy_config_secrets(").unwrap()
        );
        assert!(
            method.find("self.persist_config(&config)?").unwrap()
                < method.find("*cache = Some(config.clone())").unwrap()
        );
        assert!(method.contains("self.publish_input_commit(&config)"));
        assert!(!method.contains(".await"));
    }

    #[test]
    fn identity_owned_noop_commit_requires_a_new_persisted_revision() {
        use std::sync::atomic::{AtomicU64, Ordering};
        let cache = Mutex::new(Some(AppConfig::default()));
        let revision = AtomicU64::new(4);
        let committed = update_cached_config_commit(
            &cache,
            &revision,
            |_| Ok(()),
            |_| {
                assert!(cache.try_lock().is_err());
                Ok(revision.fetch_add(1, Ordering::SeqCst) + 1)
            },
            true,
        )
        .unwrap();
        assert_eq!(committed.0, 5);
    }

    #[test]
    fn identity_owned_noop_persistence_failure_preserves_revision_and_cache() {
        use std::sync::atomic::{AtomicU64, Ordering};
        let original = AppConfig::default();
        let cache = Mutex::new(Some(original.clone()));
        let revision = AtomicU64::new(4);
        let result = update_cached_config_commit(
            &cache,
            &revision,
            |_| Ok(()),
            |_| Err(anyhow::anyhow!("Synthetic persistence failure")),
            true,
        );
        assert!(result.is_err());
        assert_eq!(revision.load(Ordering::SeqCst), 4);
        assert_eq!(
            serde_json::to_value(cache.lock().unwrap().as_ref().unwrap()).unwrap(),
            serde_json::to_value(original).unwrap()
        );
    }

    #[test]
    fn scene_activation_success_pairs_persisted_bytes_cache_and_owned_revision() {
        use std::sync::atomic::{AtomicU64, Ordering};
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory =
            std::env::temp_dir().join(format!("otl-scene-success-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("settings.json");
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let scene = CustomScene {
            id: "custom_persisted".into(),
            name: "Persisted".into(),
            prompt_template: "Keep concise.".into(),
            ..CustomScene::default()
        };
        let mut original = AppConfig::default();
        original.custom_scenes.push(scene.clone());
        original.microphone_device = "synthetic-device".into();
        persist_config_value(&store, serde_json::to_value(&original).unwrap()).unwrap();
        let cache = Mutex::new(Some(original));
        let revision = AtomicU64::new(4);
        let committed = update_cached_config_commit(
            &cache,
            &revision,
            |candidate| {
                candidate.active_scene = Some(ActiveScene {
                    id: scene.id.clone(),
                    source: "custom".into(),
                    name: scene.name.clone(),
                    prompt_template: scene.prompt_template.clone(),
                });
                Ok(())
            },
            |candidate| {
                assert!(cache.try_lock().is_err());
                persist_config_value(&store, serde_json::to_value(candidate)?)?;
                Ok(revision.fetch_add(1, Ordering::SeqCst) + 1)
            },
            true,
        )
        .unwrap();
        let persisted: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&filename).unwrap()).unwrap();
        let committed_value = serde_json::to_value(&committed.1).unwrap();
        assert_eq!(committed.0, 5);
        assert_eq!(revision.load(Ordering::SeqCst), 5);
        assert_eq!(persisted["app_config"], committed_value);
        assert_eq!(store.get("app_config"), Some(committed_value.clone()));
        assert_eq!(
            serde_json::to_value(cache.lock().unwrap().as_ref().unwrap()).unwrap(),
            committed_value
        );
        assert_eq!(committed.1.microphone_device, "synthetic-device");
        let reloaded = AppConfig::from_stored_value(persisted["app_config"].clone()).unwrap();
        assert_eq!(reloaded.active_scene, committed.1.active_scene);
        assert_eq!(reloaded.custom_scenes, committed.1.custom_scenes);
        std::fs::remove_file(filename).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn scene_save_and_activation_disk_failure_preserves_the_complete_previous_state() {
        use std::sync::atomic::{AtomicU64, Ordering};
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory =
            std::env::temp_dir().join(format!("otl-scene-atomic-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("settings.json");
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let original = AppConfig::default();
        persist_config_value(&store, serde_json::to_value(&original).unwrap()).unwrap();
        let previous_bytes = std::fs::read(&filename).unwrap();
        let preserved = directory.join("previous.json");
        std::fs::rename(&filename, &preserved).unwrap();
        std::fs::create_dir(&filename).unwrap();
        let cache = Mutex::new(Some(original.clone()));
        let revision = AtomicU64::new(4);
        let result = update_cached_config_commit(
            &cache,
            &revision,
            |candidate| {
                candidate.custom_scenes.push(CustomScene {
                    id: "custom_atomic".into(),
                    name: "Atomic".into(),
                    prompt_template: "Keep concise.".into(),
                    ..CustomScene::default()
                });
                candidate.active_scene = Some(ActiveScene {
                    id: "custom_atomic".into(),
                    source: "custom".into(),
                    name: "Atomic".into(),
                    prompt_template: "Keep concise.".into(),
                });
                Ok(())
            },
            |candidate| {
                assert!(cache.try_lock().is_err());
                persist_config_value(&store, serde_json::to_value(candidate)?)?;
                Ok(revision.fetch_add(1, Ordering::SeqCst) + 1)
            },
            true,
        );
        assert!(result.is_err());
        assert_eq!(revision.load(Ordering::SeqCst), 4);
        assert_eq!(
            serde_json::to_value(cache.lock().unwrap().as_ref().unwrap()).unwrap(),
            serde_json::to_value(&original).unwrap()
        );
        assert_eq!(
            store.get("app_config"),
            Some(serde_json::to_value(original).unwrap())
        );
        assert_eq!(std::fs::read(&preserved).unwrap(), previous_bytes);
        std::fs::remove_dir(&filename).unwrap();
        std::fs::remove_file(preserved).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn narrow_owned_commit_remains_paired_after_a_later_update() {
        use std::sync::atomic::{AtomicU64, Ordering};
        let original = AppConfig::default();
        let cache = Mutex::new(Some(original));
        let revision = AtomicU64::new(4);
        let first = try_update_cached_config_commit(
            &cache,
            &revision,
            |config| {
                config.microphone_device = "synthetic-first".into();
                Ok(())
            },
            |_| Ok(revision.fetch_add(1, Ordering::SeqCst) + 1),
        )
        .unwrap();
        let second = try_update_cached_config_commit(
            &cache,
            &revision,
            |config| {
                config.microphone_device = "synthetic-second".into();
                Ok(())
            },
            |_| Ok(revision.fetch_add(1, Ordering::SeqCst) + 1),
        )
        .unwrap();
        assert_eq!(first.0, 5);
        assert_eq!(first.1.microphone_device, "synthetic-first");
        assert_eq!(second.0, 6);
        assert_eq!(second.1.microphone_device, "synthetic-second");
        let unchanged = try_update_cached_config_commit(
            &cache,
            &revision,
            |_| Ok(()),
            |_| panic!("unchanged settings must not persist or increment"),
        )
        .unwrap();
        assert_eq!(unchanged.0, 6);
        assert_eq!(unchanged.1.microphone_device, "synthetic-second");
    }

    #[test]
    fn failed_narrow_owned_commit_does_not_publish_or_replace_cache() {
        use std::sync::atomic::{AtomicU64, Ordering};
        let original = AppConfig::default();
        let cache = Mutex::new(Some(original.clone()));
        let revision = AtomicU64::new(4);
        let result = try_update_cached_config_commit(
            &cache,
            &revision,
            |config| {
                config.microphone_device = "synthetic-rejected".into();
                Ok(())
            },
            |_| Err(anyhow::anyhow!("synthetic persistence failure")),
        );
        assert!(result.is_err());
        assert_eq!(revision.load(Ordering::SeqCst), 4);
        assert_eq!(
            serde_json::to_value(cache.lock().unwrap().as_ref().unwrap()).unwrap(),
            serde_json::to_value(original).unwrap()
        );
    }

    #[test]
    fn config_load_holds_one_cache_guard_through_initialization() {
        let source = include_str!("mod.rs");
        let method = source
            .split("    pub async fn load(&self) -> Result<AppConfig> {")
            .nth(1)
            .unwrap()
            .split("    pub async fn save(")
            .next()
            .unwrap();
        assert_eq!(method.matches("self.cache.lock()").count(), 1);
        assert!(
            method.find("let mut cache =").unwrap()
                < method.find(".store(\"settings.json\")").unwrap()
        );
        assert!(!method.contains(".await"));
        assert!(!method.contains("AppConfig::from_stored_value(val.clone()).ok()"));
        assert!(!method.contains("Err(_) => None"));
    }

    #[test]
    fn privacy_disable_waits_for_current_write_and_blocks_the_next_history_insert() {
        use std::sync::{mpsc, Arc};
        use std::time::Duration;
        let cache = Arc::new(Mutex::new(Some(AppConfig::default())));
        let history = Arc::new(temp_history_store("concurrent-privacy"));
        let (entered_sender, entered_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let writer_cache = cache.clone();
        let writer_history = history.clone();
        let writer = std::thread::spawn(move || {
            with_history_privacy_cache(&writer_cache, true, |privacy| {
                entered_sender.send(()).unwrap();
                release_receiver
                    .recv_timeout(Duration::from_secs(5))
                    .unwrap();
                let (policy, _) = privacy.unwrap();
                writer_history.add_with_audio_sync(
                    test_history_entry(0, "2026-11-10T10:00:00"),
                    &policy,
                    None,
                )
            })
            .unwrap()
        });
        entered_receiver
            .recv_timeout(Duration::from_secs(5))
            .unwrap();
        let (attempt_sender, attempt_receiver) = mpsc::channel();
        let (committed_sender, committed_receiver) = mpsc::channel();
        let settings_cache = cache.clone();
        let settings = std::thread::spawn(move || {
            attempt_sender.send(()).unwrap();
            settings_cache
                .lock()
                .unwrap()
                .as_mut()
                .unwrap()
                .history_enabled = false;
            committed_sender.send(()).unwrap();
        });
        attempt_receiver
            .recv_timeout(Duration::from_secs(5))
            .unwrap();
        assert!(matches!(
            committed_receiver.try_recv(),
            Err(mpsc::TryRecvError::Empty)
        ));
        release_sender.send(()).unwrap();
        assert!(writer.join().unwrap().is_some());
        committed_receiver
            .recv_timeout(Duration::from_secs(5))
            .unwrap();
        settings.join().unwrap();
        let next = with_history_privacy_cache(&cache, true, |privacy| {
            let (policy, _) = privacy.unwrap();
            history.add_with_audio_sync(test_history_entry(0, "2026-11-10T10:01:00"), &policy, None)
        })
        .unwrap();
        assert!(next.is_none());
        assert_eq!(
            history
                .conn
                .lock()
                .unwrap()
                .query_row("SELECT COUNT(*) FROM history", [], |row| row
                    .get::<_, i64>(0))
                .unwrap(),
            1
        );
    }

    #[test]
    fn guarded_history_write_skips_disabled_history_and_drops_disabled_audio() {
        let store = temp_history_store("guarded-history-write");
        let cache = Mutex::new(Some(AppConfig {
            history_enabled: false,
            ..AppConfig::default()
        }));
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let saved = with_history_privacy_cache(&cache, true, |privacy| {
            let (policy, _) = privacy.unwrap();
            store.add_with_audio_sync(
                test_history_entry(0, "2026-11-10T10:00:00"),
                &policy,
                Some(audio.clone()),
            )
        })
        .unwrap();
        assert!(saved.is_none());
        assert_eq!(
            store
                .conn
                .lock()
                .unwrap()
                .query_row("SELECT COUNT(*) FROM history", [], |row| row
                    .get::<_, i64>(0),)
                .unwrap(),
            0
        );
        *cache.lock().unwrap() = Some(AppConfig {
            save_recording_audio: false,
            ..AppConfig::default()
        });
        let saved = with_history_privacy_cache(&cache, true, |privacy| {
            let (policy, save_audio) = privacy.unwrap();
            store.add_with_audio_sync(
                test_history_entry(0, "2026-11-10T10:00:00"),
                &policy,
                save_audio.then_some(audio),
            )
        })
        .unwrap()
        .unwrap();
        let has_audio: bool = store
            .conn
            .lock()
            .unwrap()
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM history_audio WHERE history_id = ?1)",
                [saved],
                |row| row.get(0),
            )
            .unwrap();
        assert!(!has_audio);
    }

    #[test]
    fn concurrent_backup_restore_reservations_have_exactly_one_owner() {
        let store = std::sync::Arc::new(temp_history_store("backup-restore-race"));
        for _round in 0..16 {
            let start = std::sync::Arc::new(std::sync::Barrier::new(3));
            let finish = std::sync::Arc::new(std::sync::Barrier::new(3));
            let (sender, receiver) = std::sync::mpsc::channel();
            let mut workers = Vec::new();
            for _worker in 0..2 {
                let store = store.clone();
                let start = start.clone();
                let finish = finish.clone();
                let sender = sender.clone();
                workers.push(std::thread::spawn(move || {
                    start.wait();
                    let reservation = store.reserve_backup_restore();
                    sender.send(reservation.is_ok()).unwrap();
                    finish.wait();
                    drop(reservation);
                }));
            }
            start.wait();
            let outcomes: Vec<_> = (0..2)
                .map(|_| {
                    receiver
                        .recv_timeout(std::time::Duration::from_secs(3))
                        .unwrap()
                })
                .collect();
            let owner_count = outcomes.iter().filter(|owned| **owned).count();
            let extra_refused = store.reserve_backup_restore().is_err();
            finish.wait();
            for worker in workers {
                worker.join().unwrap();
            }
            assert_eq!(owner_count, 1);
            assert!(extra_refused);
            assert!(store.reserve_backup_restore().is_ok());
        }
    }

    #[test]
    fn backup_restore_reservation_rejects_duplicates_and_releases_after_unwind() {
        let store = temp_history_store("backup-restore-reservation");
        let first = store.reserve_backup_restore().unwrap();
        assert!(store.reserve_backup_restore().is_err());
        drop(first);
        assert!(std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _reservation = store.reserve_backup_restore().unwrap();
            panic!("Synthetic restore worker panic");
        }))
        .is_err());
        assert!(store.reserve_backup_restore().is_ok());
    }

    #[tokio::test]
    async fn local_delete_and_clear_preserve_history_while_sync_is_active() {
        let store = temp_history_store("local-delete-sync-race");
        store
            .add(test_history_entry(0, "2026-11-21T12:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        {
            let _sync = store.reserve_history_sync().unwrap();
            assert!(store.delete(1).await.is_err());
            assert!(store.clear().await.is_err());
            assert!(store.get(1).await.unwrap().is_some());
            assert!(store.build_pending_history_request(&mutation).is_ok());
        }
        assert!(store.delete(1).await.unwrap());
        assert!(!store.delete(1).await.unwrap());
        store.clear().await.unwrap();
    }

    #[test]
    fn history_sync_reservation_excludes_sync_and_backup_and_releases_after_unwind() {
        let store = temp_history_store("sync-reservation");
        let first = store.reserve_history_sync().unwrap();
        assert!(store.reserve_history_sync().is_err());
        assert!(store.reserve_backup_restore().is_err());
        drop(first);
        let backup = store.reserve_backup_restore().unwrap();
        assert!(store.reserve_history_sync().is_err());
        drop(backup);
        let result = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _reservation = store.reserve_history_sync().unwrap();
            panic!("Synthetic sync worker failure");
        }));
        assert!(result.is_err());
        assert!(store.reserve_history_sync().is_ok());
        assert!(store.reserve_backup_restore().is_ok());
    }

    #[test]
    fn unresolved_attempt_cannot_be_acknowledged_during_active_sync() {
        let store = temp_history_store("attempt-ack-reservation");
        let request = uuid::Uuid::new_v4().to_string();
        let endpoint = "https://example.invalid/api/history-sync/status";
        store
            .begin_history_sync_attempt("account-a", endpoint, &request)
            .unwrap();
        let sync = store.reserve_history_sync().unwrap();
        assert!(store
            .acknowledge_history_sync_attempt("account-a", endpoint, &request, |transaction| {
                transaction.commit()?;
                Ok(())
            },)
            .is_err());
        drop(sync);
        assert!(store
            .begin_history_sync_attempt("account-a", endpoint, &uuid::Uuid::new_v4().to_string(),)
            .is_err());
        store
            .acknowledge_history_sync_attempt("account-a", endpoint, &request, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .unwrap();
        assert_eq!(
            store
                .read_history_sync_attempt("account-a", endpoint)
                .unwrap()
                .unwrap()
                .status,
            "unknown"
        );
        store
            .begin_history_sync_attempt("account-a", endpoint, &uuid::Uuid::new_v4().to_string())
            .unwrap();
    }

    #[test]
    fn backup_restore_requires_verified_privacy_and_does_not_import_when_disabled() {
        let store = temp_history_store("backup-privacy-boundary");
        let original = test_history_entry(1, "2026-11-20T00:00:00");
        store
            .add_with_metadata_sync(original, &HistoryRetentionPolicy::default(), None, None)
            .unwrap();
        let cache = Mutex::new(Some(AppConfig {
            history_enabled: false,
            ..AppConfig::default()
        }));
        let replacement = test_history_entry(2, "2026-11-20T00:00:00");
        let restore = |verified| {
            with_history_privacy_cache(&cache, verified, |privacy| {
                let (policy, _) =
                    privacy.ok_or_else(|| anyhow::anyhow!("Unverified history privacy"))?;
                assert!(cache.try_lock().is_err());
                store.restore_backup_data_sync(
                    Some(vec![replacement.clone()]),
                    None,
                    None,
                    &policy,
                    "2026-11-20T00:00:00",
                )
            })
        };
        assert!(restore(false).is_err());
        let retained: String = store
            .conn
            .lock()
            .unwrap()
            .query_row("SELECT raw_text FROM history", [], |row| row.get(0))
            .unwrap();
        assert_eq!(retained, "raw 1");
        restore(true).unwrap();
        let count: i64 = store
            .conn
            .lock()
            .unwrap()
            .query_row("SELECT count(*) FROM history", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 0);
        assert!(cache.try_lock().is_ok());
    }

    #[tokio::test(flavor = "current_thread")]
    async fn backup_restore_worker_waits_for_database_without_blocking_async_release() {
        let store = std::sync::Arc::new(temp_history_store("backup-worker-lock"));
        let cache = std::sync::Arc::new(Mutex::new(Some(AppConfig::default())));
        let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let holding_store = store.clone();
        let holder = std::thread::spawn(move || {
            let _connection = holding_store.conn.lock().unwrap();
            entered_sender.send(()).unwrap();
            release_receiver
                .recv_timeout(std::time::Duration::from_secs(3))
                .is_ok()
        });
        entered_receiver.await.unwrap();
        let restoring_store = store.clone();
        let restoring_cache = cache.clone();
        let (privacy_sender, privacy_receiver) = tokio::sync::oneshot::channel();
        let worker = tauri::async_runtime::spawn_blocking(move || {
            with_history_privacy_cache(&restoring_cache, true, |privacy| {
                let (policy, _) = privacy.unwrap();
                privacy_sender.send(()).unwrap();
                restoring_store.restore_backup_data_sync(
                    Some(vec![test_history_entry(7, "2026-11-20T00:00:00")]),
                    None,
                    None,
                    &policy,
                    "2026-11-20T00:00:00",
                )
            })
        });
        privacy_receiver.await.unwrap();
        let completed = std::cell::Cell::new(false);
        let waiting = std::cell::Cell::new(false);
        let restoring = async {
            let result = worker.await;
            completed.set(true);
            result
        };
        let releasing = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            waiting.set(!completed.get() && cache.try_lock().is_err());
            let _ = release_sender.send(());
        };
        let (result, ()) = tokio::join!(restoring, releasing);
        assert!(holder.join().unwrap());
        assert!(
            waiting.get(),
            "Restore must wait while holding current privacy"
        );
        result.unwrap().unwrap();
        assert!(cache.try_lock().is_ok());
        assert_eq!(store.list(10, 0).await.unwrap()[0].raw_text, "raw 7");
    }

    #[test]
    fn history_write_keeps_privacy_guard_until_storage_action_finishes() {
        let cache = Mutex::new(Some(AppConfig::default()));
        with_history_privacy_cache(&cache, true, |privacy| {
            assert!(privacy.unwrap().0.enabled);
            assert!(cache.try_lock().is_err());
            Ok(())
        })
        .unwrap();
        assert!(cache.try_lock().is_ok());
    }

    #[test]
    fn confirmed_cleanup_holds_configuration_ownership_until_deletion_finishes() {
        let cache = Mutex::new(Some(AppConfig {
            history_retention_days: 7,
            ..AppConfig::default()
        }));
        with_confirmed_retention_cache(&cache, true, true, 7, |policy| {
            assert_eq!(policy.retention_days, 7);
            assert!(cache.try_lock().is_err());
            Ok(())
        })
        .unwrap();
        assert!(cache.try_lock().is_ok());
        let mut invoked = false;
        assert!(with_confirmed_retention_cache(&cache, true, false, 0, |_| {
            invoked = true;
            Ok(())
        })
        .is_err());
        assert!(!invoked);
        assert!(with_confirmed_retention_cache(&cache, false, true, 7, |_| {
            invoked = true;
            Ok(())
        })
        .is_err());
        assert!(!invoked);
        assert!(with_confirmed_retention_cache(&cache, true, true, 7, |_| {
            Err::<(), _>(anyhow::anyhow!("synthetic cleanup failure"))
        })
        .is_err());
        assert!(cache.try_lock().is_ok());
    }

    #[test]
    fn unverified_default_cache_does_not_authorize_history_retention() {
        let config = AppConfig::new_install_default();
        assert!(history_privacy_from_cache(Some(&config), false).is_none());
        assert!(history_privacy_from_cache(None, true).is_none());
        let (policy, save_audio) = history_privacy_from_cache(Some(&config), true).unwrap();
        assert_eq!(policy.enabled, config.history_enabled);
        assert_eq!(save_audio, config.save_recording_audio);
        let disabled = AppConfig {
            history_enabled: false,
            save_recording_audio: false,
            ..config
        };
        let (policy, save_audio) = history_privacy_from_cache(Some(&disabled), true).unwrap();
        assert!(!policy.enabled);
        assert!(!save_audio);
    }

    use super::*;

    #[test]
    fn store_builder_swallows_corrupt_settings_then_save_overwrites_original_bytes() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-store-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        let original = br#"{"onboarding_completed":true,"app_config":{"ui_language":"ja"}"#;
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(&filename, original).unwrap();
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let loaded_config = store.get("app_config");
        let loaded_onboarding = store.get("onboarding_completed");
        let reload = store.reload();
        let before_save = std::fs::read(&filename).unwrap();
        let save = store.save();
        let after_save = std::fs::read(&filename).unwrap();
        std::fs::remove_file(&filename).unwrap();
        std::fs::remove_dir(&directory).unwrap();

        assert_eq!(loaded_config, None);
        assert_eq!(loaded_onboarding, None);
        assert!(reload.is_err());
        assert_eq!(before_save, original);
        save.unwrap();
        assert_eq!(after_save, b"{}");
    }

    #[test]
    fn store_builder_swallows_unreadable_directory_until_explicit_reload() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-store-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        std::fs::create_dir_all(&filename).unwrap();
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let loaded_config = store.get("app_config");
        let reload = store.reload();
        let save = store.save();
        let still_directory = filename.is_dir();
        std::fs::remove_dir(&filename).unwrap();
        std::fs::remove_dir(&directory).unwrap();

        assert_eq!(loaded_config, None);
        assert!(reload.is_err());
        assert!(save.is_err());
        assert!(still_directory);
    }

    #[test]
    fn store_builder_accepts_invalid_config_types_but_config_parser_rejects_them() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-store-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        let original = br#"{"onboarding_completed":true,"app_config":{"auto_start":"invalid"}}"#;
        std::fs::create_dir_all(&directory).unwrap();
        std::fs::write(&filename, original).unwrap();
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let parsed = AppConfig::from_stored_value(store.get("app_config").unwrap());
        let onboarding = store.get("onboarding_completed");
        let observed = std::fs::read(&filename).unwrap();
        std::fs::remove_file(&filename).unwrap();
        std::fs::remove_dir(&directory).unwrap();

        assert!(parsed.is_err());
        assert_eq!(onboarding, Some(serde_json::json!(true)));
        assert_eq!(observed, original);
    }

    fn rejecting_settings_serializer(
        _settings: &std::collections::HashMap<String, serde_json::Value>,
    ) -> std::result::Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
        Err(std::io::Error::other("injected settings serialization failure").into())
    }

    #[test]
    fn oversized_config_write_preserves_previous_store_and_file() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory =
            std::env::temp_dir().join(format!("otl-store-size-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("settings.json");
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let previous = serde_json::json!({ "history_enabled": false });
        persist_config_value(&store, previous.clone()).unwrap();
        let original = std::fs::read(&filename).unwrap();
        let result = persist_config_value(
            &store,
            serde_json::json!({ "synthetic": "x".repeat(8 * 1024 * 1024) }),
        );
        let observed = store.get("app_config");
        let persisted = std::fs::read(&filename).unwrap();
        std::fs::remove_file(&filename).unwrap();
        std::fs::remove_dir(&directory).unwrap();
        assert!(result.is_err());
        assert_eq!(observed, Some(previous));
        assert_eq!(persisted, original);
    }

    #[test]
    fn migrated_settings_survive_a_new_store_runtime_without_losing_other_keys() {
        let directory =
            std::env::temp_dir().join(format!("otl-migration-reopen-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("settings.json");
        let legacy = serde_json::json!({
            "stt_provider": "custom-whisper",
            "stt_custom_base_url": "http://127.0.0.1:8000/v1",
            "llm_provider": "ollama",
            "polish_enabled": false,
            "history_enabled": false,
            "auto_start": false,
            "ui_language": "ja"
        });
        std::fs::write(
            &filename,
            serde_json::to_vec(&serde_json::json!({
                "app_config": legacy,
                "onboarding_completed": true,
                "synthetic_other_setting": "preserve"
            }))
            .unwrap(),
        )
        .unwrap();
        let expected = {
            let app = tauri::test::mock_builder()
                .plugin(tauri_plugin_store::Builder::default().build())
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
                .disable_auto_save()
                .build()
                .unwrap();
            let config = AppConfig::from_stored_value(store.get("app_config").unwrap()).unwrap();
            assert!(!config.mute_background_audio);
            assert!(!config.capsule_auto_hide);
            let migrated = serde_json::to_value(config).unwrap();
            persist_config_value(&store, migrated.clone()).unwrap();
            migrated
        };
        let observed = {
            let app = tauri::test::mock_builder()
                .plugin(tauri_plugin_store::Builder::default().build())
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
                .disable_auto_save()
                .build()
                .unwrap();
            assert_eq!(
                store.get("onboarding_completed"),
                Some(serde_json::json!(true))
            );
            assert_eq!(
                store.get("synthetic_other_setting"),
                Some(serde_json::json!("preserve"))
            );
            let config = AppConfig::from_stored_value(store.get("app_config").unwrap()).unwrap();
            serde_json::to_value(config).unwrap()
        };
        std::fs::remove_file(&filename).unwrap();
        std::fs::remove_dir(&directory).unwrap();
        assert_eq!(observed, expected);
        for (key, value) in legacy.as_object().unwrap() {
            assert_eq!(observed[key], *value, "legacy field changed: {key}");
        }
    }

    #[test]
    fn successful_store_config_write_preserves_other_settings_on_disk() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-store-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        store.set("onboarding_completed", true);
        let config = serde_json::json!({ "ui_language": "zh", "polish_enabled": false });
        let result = persist_config_value(&store, config.clone());
        let bytes = std::fs::read(&filename);
        let observed = store.get("app_config");
        let _ = std::fs::remove_file(&filename);
        let _ = std::fs::remove_dir(&directory);
        result.unwrap();
        let persisted: serde_json::Value = serde_json::from_slice(&bytes.unwrap()).unwrap();
        assert_eq!(persisted["app_config"], config);
        assert_eq!(persisted["onboarding_completed"], true);
        assert_eq!(observed, Some(config));
    }

    fn reject_candidate_settings_serializer(
        settings: &std::collections::HashMap<String, serde_json::Value>,
    ) -> std::result::Result<Vec<u8>, Box<dyn std::error::Error + Send + Sync>> {
        if settings
            .get("app_config")
            .and_then(|config| config.get("ui_language"))
            .and_then(serde_json::Value::as_str)
            == Some("zh")
        {
            return Err(std::io::Error::other("injected candidate failure").into());
        }
        serde_json::to_vec(settings).map_err(Into::into)
    }

    #[tokio::test]
    async fn store_config_write_failure_autosaves_only_the_restored_value() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-store-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .serialize(reject_candidate_settings_serializer)
            .build()
            .unwrap();
        let previous = serde_json::json!({ "ui_language": "en" });
        store.set("app_config", previous.clone());
        store.set("onboarding_completed", true);
        store.save().unwrap();
        std::fs::remove_file(&filename).unwrap();
        let result = persist_config_value(&store, serde_json::json!({ "ui_language": "zh" }));
        let persisted = tokio::time::timeout(std::time::Duration::from_secs(3), async {
            loop {
                if let Ok(bytes) = std::fs::read(&filename) {
                    if let Ok(value) = serde_json::from_slice::<serde_json::Value>(&bytes) {
                        break value;
                    }
                }
                tokio::time::sleep(std::time::Duration::from_millis(20)).await;
            }
        })
        .await;
        store.save().unwrap();
        std::fs::remove_file(&filename).unwrap();
        std::fs::remove_dir(&directory).unwrap();
        assert!(result.is_err());
        let persisted = persisted.unwrap();
        assert_eq!(persisted["app_config"], previous);
        assert_eq!(persisted["onboarding_completed"], true);
        assert_eq!(store.get("app_config"), Some(previous));
    }

    #[test]
    fn store_config_write_can_retry_after_a_rejected_candidate() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-store-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .serialize(reject_candidate_settings_serializer)
            .build()
            .unwrap();
        store.set("onboarding_completed", true);
        let previous = serde_json::json!({ "ui_language": "en" });
        persist_config_value(&store, previous.clone()).unwrap();
        let previous_bytes = std::fs::read(&filename).unwrap();
        let rejected = persist_config_value(&store, serde_json::json!({ "ui_language": "zh" }));
        let rejected_bytes = std::fs::read(&filename);
        let restored = store.get("app_config");
        let corrected = serde_json::json!({ "ui_language": "ja", "polish_enabled": false });
        let retry = persist_config_value(&store, corrected.clone());
        let bytes = std::fs::read(&filename);
        std::fs::remove_file(&filename).unwrap();
        std::fs::remove_dir(&directory).unwrap();
        assert!(rejected.is_err());
        assert_eq!(rejected_bytes.unwrap(), previous_bytes);
        assert_eq!(restored, Some(previous));
        retry.unwrap();
        let persisted: serde_json::Value = serde_json::from_slice(&bytes.unwrap()).unwrap();
        assert_eq!(persisted["app_config"], corrected);
        assert_eq!(persisted["onboarding_completed"], true);
        assert_eq!(store.get("app_config"), Some(corrected));
    }

    #[test]
    fn microphone_scan_write_rolls_back_failed_save_and_preserves_other_settings() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-scan-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        std::fs::create_dir_all(&filename).unwrap();
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let config = serde_json::json!({"ui_language": "ja"});
        store.set("app_config", config.clone());
        store.set("onboarding_completed", true);
        store.set("better_microphone_last_scan", 1000);
        assert!(persist_microphone_scan(&store, 2000).is_err());
        assert_eq!(
            store.get("better_microphone_last_scan"),
            Some(serde_json::json!(1000))
        );
        std::fs::remove_dir(&filename).unwrap();
        persist_microphone_scan(&store, 2000).unwrap();
        let persisted: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&filename).unwrap()).unwrap();
        assert_eq!(persisted["better_microphone_last_scan"], 2000);
        assert_eq!(persisted["onboarding_completed"], true);
        assert_eq!(persisted["app_config"], config);
        std::fs::remove_file(&filename).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn onboarding_marker_write_failure_restores_previous_state_and_keeps_config() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for previous in [None, Some(false), Some(true)] {
            let directory =
                std::env::temp_dir().join(format!("otl-onboarding-{}", uuid::Uuid::new_v4()));
            let filename = directory.join("settings.json");
            std::fs::create_dir_all(&filename).unwrap();
            let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
                .disable_auto_save()
                .build()
                .unwrap();
            let config = serde_json::json!({ "ui_language": "ja" });
            store.set("app_config", config.clone());
            if let Some(previous) = previous {
                store.set("onboarding_completed", previous);
            }
            assert!(persist_onboarding_marker(&store).is_err());
            assert_eq!(
                store.get("onboarding_completed"),
                previous.map(serde_json::Value::Bool)
            );
            assert_eq!(store.get("app_config"), Some(config.clone()));
            std::fs::remove_dir(&filename).unwrap();
            persist_onboarding_marker(&store).unwrap();
            let persisted: serde_json::Value =
                serde_json::from_slice(&std::fs::read(&filename).unwrap()).unwrap();
            assert_eq!(persisted["onboarding_completed"], true);
            assert_eq!(persisted["app_config"], config);
            std::fs::remove_file(&filename).unwrap();
            std::fs::remove_dir(&directory).unwrap();
        }
    }

    #[test]
    fn failed_validated_update_does_not_persist_or_change_cached_settings() {
        let original = AppConfig::new_install_default();
        let cache = Mutex::new(Some(original.clone()));
        let mut persisted = false;
        let result = try_update_cached_config(
            &cache,
            |config| {
                config.microphone_device = "synthetic-rejected-device".into();
                Err(anyhow::anyhow!("synthetic validation failure"))
            },
            |_| {
                persisted = true;
                Ok(())
            },
        );
        assert!(result.is_err());
        assert!(!persisted);
        assert_eq!(
            serde_json::to_value(cache.lock().unwrap().as_ref().unwrap()).unwrap(),
            serde_json::to_value(original).unwrap()
        );
    }

    #[test]
    fn unchanged_narrow_update_does_not_persist_or_authorize_default_privacy() {
        let current = AppConfig::new_install_default();
        let cache = Mutex::new(Some(current.clone()));
        let mut persisted = false;
        update_cached_config(
            &cache,
            |_| {},
            |_| {
                persisted = true;
                Ok(())
            },
        )
        .unwrap();
        assert!(!persisted);
        assert!(history_privacy_from_cache(cache.lock().unwrap().as_ref(), persisted).is_none());
    }

    #[test]
    fn managed_capability_narrow_update_preserves_settings_and_rolls_back_failed_clear() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-managed-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("settings.json");
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let current = AppConfig {
            stt_provider: "cloud".into(),
            llm_model: "latest-model".into(),
            history_enabled: false,
            ..AppConfig::default()
        };
        let expected_hotkeys = serde_json::to_value(&current.hotkeys).unwrap();
        let cache = Mutex::new(Some(current));
        let now = chrono::Utc::now();
        let managed = crate::stt::capabilities::ManagedSttCapabilityState {
            user_id: "synthetic-user".into(),
            capability: crate::stt::capabilities::ManagedSttCapability {
                version: 2,
                max_recording_seconds: 600,
                max_multipart_bytes: 4_200_000,
                formats: vec![
                    crate::stt::capabilities::ManagedSttFormat {
                        mime_type: "audio/wav".into(),
                        max_audio_bytes: 4_000_000,
                        preferred_client_switch_bytes: Some(3_500_000),
                        bitrate_bits_per_second: None,
                    },
                    crate::stt::capabilities::ManagedSttFormat {
                        mime_type: "audio/ogg; codecs=opus".into(),
                        max_audio_bytes: 4_000_000,
                        preferred_client_switch_bytes: None,
                        bitrate_bits_per_second: Some(48_000),
                    },
                ],
            },
            server_generated_at: now.to_rfc3339(),
            received_at_unix_seconds: now.timestamp(),
        };
        let installed = update_cached_config(
            &cache,
            |config| {
                config.managed_stt_capability_state = Some(managed.clone());
                config.recompute_recording_limit_mirror();
            },
            |config| persist_config_value(&store, serde_json::to_value(config)?),
        )
        .unwrap();
        assert_eq!(installed.managed_stt_capability_state, Some(managed));
        assert_eq!(installed.max_recording_seconds, 600);
        assert_eq!(installed.llm_model, "latest-model");
        assert!(!installed.history_enabled);
        assert_eq!(
            serde_json::to_value(&installed.hotkeys).unwrap(),
            expected_hotkeys
        );
        let before_failed_clear = std::fs::read(&filename).unwrap();
        let preserved = directory.join("preserved-settings.json");
        std::fs::rename(&filename, &preserved).unwrap();
        std::fs::create_dir(&filename).unwrap();
        let failed = update_cached_config(
            &cache,
            |config| {
                config.managed_stt_capability_state = None;
                config.recompute_recording_limit_mirror();
            },
            |config| persist_config_value(&store, serde_json::to_value(config)?),
        );
        assert!(failed.is_err());
        assert_eq!(
            serde_json::to_value(cache.lock().unwrap().as_ref().unwrap()).unwrap(),
            serde_json::to_value(&installed).unwrap()
        );
        assert_eq!(std::fs::read(&preserved).unwrap(), before_failed_clear);
        assert_eq!(
            store.get("app_config").unwrap(),
            serde_json::to_value(&installed).unwrap()
        );
        std::fs::remove_dir(&filename).unwrap();
        std::fs::rename(&preserved, &filename).unwrap();
        let cleared = update_cached_config(
            &cache,
            |config| {
                config.managed_stt_capability_state = None;
                config.recompute_recording_limit_mirror();
            },
            |config| persist_config_value(&store, serde_json::to_value(config)?),
        )
        .unwrap();
        let persisted: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&filename).unwrap()).unwrap();
        assert!(cleared.managed_stt_capability_state.is_none());
        assert_eq!(cleared.max_recording_seconds, 30);
        assert!(persisted["app_config"]["managed_stt_capability_state"].is_null());
        assert_eq!(persisted["app_config"]["llm_model"], "latest-model");
        assert_eq!(persisted["app_config"]["history_enabled"], false);
        assert_eq!(persisted["app_config"]["hotkeys"], expected_hotkeys);
        std::fs::remove_file(filename).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn narrow_update_preserves_latest_settings_and_cache_after_failed_persistence() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-narrow-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        std::fs::create_dir_all(&filename).unwrap();
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let current = AppConfig {
            history_enabled: false,
            llm_model: "new-model".to_string(),
            capsule_auto_hide: false,
            ..AppConfig::default()
        };
        let cache = Mutex::new(Some(current.clone()));
        let save = |config: &mut AppConfig| {
            assert!(cache.try_lock().is_err());
            persist_config_value(&store, serde_json::to_value(config)?)
        };
        assert!(
            update_cached_config(&cache, |config| config.capsule_auto_hide = true, save).is_err()
        );
        assert_eq!(
            serde_json::to_value(cache.lock().unwrap().as_ref().unwrap()).unwrap(),
            serde_json::to_value(current).unwrap()
        );
        std::fs::remove_dir(&filename).unwrap();
        let saved =
            update_cached_config(&cache, |config| config.capsule_auto_hide = true, save).unwrap();
        assert!(!saved.history_enabled);
        assert_eq!(saved.llm_model, "new-model");
        assert!(saved.capsule_auto_hide);
        let persisted: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&filename).unwrap()).unwrap();
        assert_eq!(persisted["app_config"]["llm_model"], "new-model");
        assert_eq!(persisted["app_config"]["history_enabled"], false);
        assert_eq!(persisted["app_config"]["capsule_auto_hide"], true);
        std::fs::remove_file(&filename).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn translation_narrow_update_preserves_latest_values_on_disk() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory =
            std::env::temp_dir().join(format!("otl-translation-save-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("settings.json");
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        store.set("onboarding_completed", true);
        let mut latest = AppConfig {
            llm_model: "latest-model".into(),
            history_enabled: false,
            target_lang: "en".into(),
            ..Default::default()
        };
        latest.translation.targets = vec!["en".into(), "ja".into(), "de".into()];
        latest.translation.active_target = "en".into();
        let cache = Mutex::new(Some(latest));
        let updated = update_cached_config(
            &cache,
            |config| {
                assert!(crate::commands::translation::apply_translation_target(
                    config, "ja"
                ));
            },
            |config| persist_config_value(&store, serde_json::to_value(config)?),
        )
        .unwrap();
        let persisted: serde_json::Value =
            serde_json::from_slice(&std::fs::read(&filename).unwrap()).unwrap();
        assert_eq!(persisted["app_config"]["llm_model"], "latest-model");
        assert_eq!(persisted["app_config"]["history_enabled"], false);
        assert_eq!(persisted["app_config"]["target_lang"], "ja");
        assert_eq!(
            persisted["app_config"]["translation"]["targets"],
            serde_json::json!(["en", "ja", "de"])
        );
        assert_eq!(persisted["onboarding_completed"], true);
        assert_eq!(updated.translation.active_target, "ja");
        std::fs::remove_file(filename).unwrap();
        std::fs::remove_dir(directory).unwrap();
    }

    #[test]
    fn store_config_write_io_failure_restores_memory_without_removing_other_settings() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-store-{}", uuid::Uuid::new_v4()));
        let filename = directory.join("settings.json");
        std::fs::create_dir_all(&filename).unwrap();
        let store = tauri_plugin_store::StoreBuilder::new(&app, &filename)
            .disable_auto_save()
            .build()
            .unwrap();
        let previous = serde_json::json!({ "ui_language": "en" });
        store.set("app_config", previous.clone());
        store.set("onboarding_completed", true);
        let result = persist_config_value(&store, serde_json::json!({ "ui_language": "zh" }));
        let observed = store.get("app_config");
        let onboarding = store.get("onboarding_completed");
        let still_directory = filename.is_dir();
        std::fs::remove_dir(&filename).unwrap();
        let corrected = serde_json::json!({ "ui_language": "ja" });
        let retry = persist_config_value(&store, corrected.clone());
        let bytes = std::fs::read(&filename);
        let _ = std::fs::remove_file(&filename);
        std::fs::remove_dir(&directory).unwrap();
        assert!(result.is_err());
        assert_eq!(observed, Some(previous));
        assert_eq!(onboarding, Some(serde_json::json!(true)));
        assert!(still_directory);
        retry.unwrap();
        let persisted: serde_json::Value = serde_json::from_slice(&bytes.unwrap()).unwrap();
        assert_eq!(persisted["app_config"], corrected);
        assert_eq!(persisted["onboarding_completed"], true);
        assert_eq!(store.get("app_config"), Some(corrected));
    }

    #[test]
    fn failed_store_config_write_restores_previous_value_and_other_settings() {
        let app = tauri::test::mock_builder()
            .plugin(tauri_plugin_store::Builder::default().build())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let directory = std::env::temp_dir().join(format!("otl-store-{}", uuid::Uuid::new_v4()));
        let store = tauri_plugin_store::StoreBuilder::new(&app, directory.join("settings.json"))
            .disable_auto_save()
            .serialize(rejecting_settings_serializer)
            .build()
            .unwrap();
        let previous = serde_json::json!({ "ui_language": "en", "llm_api_key": "legacy-local" });
        let outcomes = [Some(previous), None].map(|previous| {
            match &previous {
                Some(value) => store.set("app_config", value.clone()),
                None => {
                    store.delete("app_config");
                }
            }
            store.set("onboarding_completed", true);
            let result = persist_config_value(&store, serde_json::json!({ "ui_language": "zh" }));
            (
                previous,
                result,
                store.get("app_config"),
                store.get("onboarding_completed"),
            )
        });
        let _ = std::fs::remove_dir(&directory);
        for (previous, result, observed, onboarding) in outcomes {
            assert!(result
                .unwrap_err()
                .to_string()
                .contains("injected settings"));
            assert_eq!(observed, previous);
            assert_eq!(onboarding, Some(serde_json::json!(true)));
        }
    }

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
        assert_eq!(
            config.stt_volcengine_resource_id,
            crate::stt::volcengine::VOLCENGINE_SEEDASR_RESOURCE_ID
        );
        assert_eq!(
            config.stt_aliyun_qwen_region,
            crate::stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_REGION_CHINA_MAINLAND
        );
    }

    #[test]
    fn app_config_preserves_supported_qwen_region_and_normalizes_unknown_values() {
        let international = AppConfig::from_stored_value(serde_json::json!({
            "stt_provider": "aliyun-qwen3-asr",
            "stt_aliyun_qwen_region": "international"
        }))
        .unwrap();
        assert_eq!(international.stt_aliyun_qwen_region, "international");

        let unknown = AppConfig::from_stored_value(serde_json::json!({
            "stt_provider": "aliyun-qwen3-asr",
            "stt_aliyun_qwen_region": "unknown"
        }))
        .unwrap();
        assert_eq!(
            unknown.stt_aliyun_qwen_region,
            crate::stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_REGION_CHINA_MAINLAND
        );
    }

    #[test]
    fn app_config_defaults_missing_polish_preferences() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "stt_api_key": "",
            "stt_language": "multi",
            "llm_provider": "openrouter",
            "llm_api_key": "",
            "llm_model": "google/gemini-2.5-flash",
            "llm_base_url": "https://openrouter.ai/api/v1",
            "polish_enabled": true,
            "translate_enabled": false,
            "target_lang": "en",
            "hotkey": "Ctrl+/",
            "hotkey_mode": "hold",
            "output_mode": "keyboard",
            "selected_text_enabled": false,
            "theme": "system",
            "auto_start": false,
            "close_to_tray": true,
            "start_minimized": false,
            "max_recording_seconds": 30,
            "ui_language": "en"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.polish_custom_prompt, "");
        assert_eq!(config.polish_chinese_script, "preserve");
        assert_eq!(config.polish_style, "clean");
    }

    #[test]
    fn recording_limit_migrates_historical_default_to_provider_auto() {
        let config = AppConfig::from_stored_value(serde_json::json!({
            "stt_provider": "groq-whisper",
            "max_recording_seconds": 30
        }))
        .unwrap();

        assert_eq!(
            config.recording_limit_mode,
            crate::stt::capabilities::RecordingLimitMode::Auto
        );
        assert_eq!(config.custom_recording_limit_seconds, 600);
        assert_eq!(config.max_recording_seconds, 600);
    }

    #[test]
    fn recording_limit_migrates_historical_custom_value_without_losing_intent() {
        let config = AppConfig::from_stored_value(serde_json::json!({
            "stt_provider": "groq-whisper",
            "max_recording_seconds": 120
        }))
        .unwrap();

        assert_eq!(
            config.recording_limit_mode,
            crate::stt::capabilities::RecordingLimitMode::Custom
        );
        assert_eq!(config.custom_recording_limit_seconds, 120);
        assert_eq!(config.max_recording_seconds, 120);
    }

    #[test]
    fn recording_limit_migrates_zero_to_a_safe_auto_default() {
        let config = AppConfig::from_stored_value(serde_json::json!({
            "stt_provider": "deepgram",
            "max_recording_seconds": 0
        }))
        .unwrap();

        assert_eq!(
            config.recording_limit_mode,
            crate::stt::capabilities::RecordingLimitMode::Auto
        );
        assert_eq!(config.custom_recording_limit_seconds, 600);
        assert_eq!(config.max_recording_seconds, 600);
    }

    #[test]
    fn recording_limit_clamps_the_compatibility_mirror_but_preserves_new_user_intent_on_load() {
        let config = AppConfig::from_stored_value(serde_json::json!({
            "stt_provider": "glm-asr",
            "recording_limit_mode": "custom",
            "custom_recording_limit_seconds": 9999,
            "max_recording_seconds": 9999
        }))
        .unwrap();

        assert_eq!(
            config.recording_limit_mode,
            crate::stt::capabilities::RecordingLimitMode::Custom
        );
        assert_eq!(config.custom_recording_limit_seconds, 9999);
        assert_eq!(config.max_recording_seconds, 30);
    }

    #[test]
    fn app_config_voice_routing_flags_migrate_on_and_preserve_independent_kill_switches() {
        let missing = AppConfig::from_stored_value(serde_json::json!({})).unwrap();
        assert_eq!(
            missing.voice_routing_flags,
            crate::voice_intent::VoiceRoutingFlags::default()
        );

        let mut partial_value = serde_json::to_value(AppConfig::default()).unwrap();
        partial_value["voice_routing_flags"] = serde_json::json!({
            "draft_insert": false
        });
        let partial = AppConfig::from_stored_value(partial_value).unwrap();
        assert_eq!(
            partial.voice_routing_flags,
            crate::voice_intent::VoiceRoutingFlags {
                draft_insert: false,
                rewrite_selection: true,
                translate_selection: true,
                search: true,
            }
        );
    }

    #[test]
    fn translation_config_migrates_legacy_target_and_enforces_ordered_invariants() {
        let legacy = AppConfig::from_stored_value(serde_json::json!({
            "target_lang": "ja"
        }))
        .unwrap();
        assert_eq!(
            legacy.translation,
            TranslationConfig {
                targets: vec!["ja".to_string()],
                active_target: "ja".to_string(),
            }
        );
        assert_eq!(legacy.target_lang, "ja");

        let normalized = AppConfig::from_stored_value(serde_json::json!({
            "target_lang": "de",
            "translation": {
                "targets": [" FR ", "fr", "xx", "ja", "de", "es", "pt", "it"],
                "active_target": "ko"
            }
        }))
        .unwrap();
        assert_eq!(
            normalized.translation.targets,
            ["fr", "ja", "de", "es", "pt"]
        );
        assert_eq!(normalized.translation.active_target, "de");
        assert_eq!(normalized.target_lang, "de");

        let empty = AppConfig::from_stored_value(serde_json::json!({
            "target_lang": "xx",
            "translation": {
                "targets": [],
                "active_target": "xx"
            }
        }))
        .unwrap();
        assert_eq!(empty.translation.targets, ["en"]);
        assert_eq!(empty.translation.active_target, "en");
        assert_eq!(serde_json::to_value(&empty).unwrap()["target_lang"], "en");
    }

    #[test]
    fn app_config_preserves_custom_polish_style_and_prompt() {
        let config = AppConfig::from_stored_value(serde_json::json!({
            "polish_style": "custom",
            "polish_custom_prompt": "Keep short sentences."
        }))
        .unwrap();
        assert_eq!(config.polish_style, "custom");
        assert_eq!(config.polish_custom_prompt, "Keep short sentences.");
    }

    #[test]
    fn app_config_preserves_valid_polish_style() {
        let mut value = serde_json::to_value(AppConfig::default()).unwrap();
        value["polish_style"] = serde_json::json!("structured");

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.polish_style, "structured");
    }

    #[test]
    fn app_config_normalizes_invalid_polish_style_to_clean() {
        let mut value = serde_json::to_value(AppConfig::default()).unwrap();
        value["polish_style"] = serde_json::json!("marketplace-pack");

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.polish_style, "clean");
    }

    #[test]
    fn app_config_defaults_missing_clipboard_restore_preferences() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "output_mode": "clipboard"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert!(config.restore_clipboard_after_paste);
        assert_eq!(config.paste_shortcut, "ctrlV");
    }

    #[test]
    fn app_config_defaults_and_normalizes_history_privacy_settings() {
        let default_value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter"
        });
        let default_config = AppConfig::from_stored_value(default_value).unwrap();
        assert!(default_config.history_enabled);
        assert_eq!(default_config.history_retention_days, 0);
        assert_eq!(default_config.history_max_entries, 5000);

        let invalid_value = serde_json::json!({
            "history_enabled": true,
            "history_retention_days": 99999,
            "history_max_entries": 99999
        });
        let invalid_config = AppConfig::from_stored_value(invalid_value).unwrap();
        assert_eq!(invalid_config.history_retention_days, 3650);
        assert_eq!(invalid_config.history_max_entries, 5000);
    }

    #[test]
    fn shortcut_binding_accepts_native_single_key_triggers() {
        let fn_binding = ShortcutBinding::from_hotkey("Fn").expect("Fn parses");
        assert_eq!(fn_binding.primary, "Fn");
        assert!(fn_binding.modifiers.is_empty());
        assert_eq!(fn_binding.to_hotkey_string().as_deref(), Some("Fn"));

        let right_alt = ShortcutBinding::from_hotkey("RightAlt").expect("RightAlt parses");
        assert_eq!(right_alt.primary, "RightAlt");
        assert!(right_alt.modifiers.is_empty());
        assert_eq!(right_alt.to_hotkey_string().as_deref(), Some("RightAlt"));
    }

    #[test]
    fn shortcut_binding_accepts_native_mode_shortcuts() {
        let ask = ShortcutBinding::from_hotkey("Fn+Space").expect("Fn+Space parses");
        assert_eq!(ask.primary, "Space");
        assert_eq!(ask.modifiers, vec!["Fn".to_string()]);
        assert_eq!(ask.to_hotkey_string().as_deref(), Some("Fn+Space"));

        let translate =
            ShortcutBinding::from_hotkey("RightAlt+LeftShift").expect("RightAlt+LeftShift parses");
        assert_eq!(translate.primary, "LeftShift");
        assert_eq!(translate.modifiers, vec!["RightAlt".to_string()]);
        assert_eq!(
            translate.to_hotkey_string().as_deref(),
            Some("RightAlt+LeftShift")
        );
    }

    #[test]
    fn app_config_defaults_streaming_insert_to_disabled() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert!(!config.streaming_insert_enabled);
    }

    #[test]
    fn legacy_provider_matrix_preserves_local_configuration_after_roundtrip() {
        let speech_providers = [
            "deepgram",
            "assemblyai",
            "aliyun-qwen3-asr",
            "volcengine-doubao",
            "glm-asr",
            "openai-whisper",
            "groq-whisper",
            "siliconflow",
            "apple-speech",
            "custom-whisper",
            "cloud",
        ];
        let language_providers = [
            "zhipu",
            "deepseek",
            "siliconflow",
            "openai",
            "gemini",
            "moonshot",
            "doubao",
            "qwen",
            "groq",
            "claude",
            "ollama",
            "openrouter",
            "cloud",
        ];
        for speech in speech_providers {
            for language in language_providers {
                let legacy = serde_json::json!({
                    "stt_provider": speech,
                    "llm_provider": language,
                    "llm_model": "synthetic-preserved-model",
                    "polish_enabled": false,
                    "context_adaptation_enabled": false,
                    "selected_text_enabled": false,
                    "restore_clipboard_after_paste": false,
                    "insertion_strategy": "clipboardCopyOnly",
                    "hotkey": "Ctrl+Shift+D",
                    "ask_hotkey": "",
                    "hotkey_mode": "hold",
                    "history_enabled": false,
                    "auto_start": false,
                    "polish_chinese_script": "traditional"
                });
                let config = AppConfig::from_stored_value(legacy.clone()).unwrap();
                let serialized = serde_json::to_value(&config).unwrap();
                for field in [
                    "stt_provider",
                    "llm_provider",
                    "llm_model",
                    "polish_enabled",
                    "context_adaptation_enabled",
                    "selected_text_enabled",
                    "restore_clipboard_after_paste",
                    "insertion_strategy",
                    "hotkey",
                    "ask_hotkey",
                    "hotkey_mode",
                    "history_enabled",
                    "auto_start",
                ] {
                    assert_eq!(
                        serialized[field], legacy[field],
                        "{speech}/{language}: {field}"
                    );
                }
                assert_eq!(
                    config.language_variants.get("zh").map(String::as_str),
                    Some("zh-TW")
                );
                let reopened = AppConfig::from_stored_value(serialized.clone()).unwrap();
                assert_eq!(serde_json::to_value(reopened).unwrap(), serialized);
            }
        }
    }

    #[test]
    fn legacy_config_roundtrip_preserves_provider_shortcuts_and_privacy_preferences() {
        let legacy = serde_json::json!({
            "stt_provider": "custom-whisper",
            "stt_custom_preset": "custom",
            "stt_custom_base_url": "http://127.0.0.1:8000/v1",
            "stt_custom_model": "synthetic-local-model",
            "llm_provider": "ollama",
            "llm_base_url": "http://127.0.0.1:11434/v1",
            "llm_model": "synthetic-polish-model",
            "polish_enabled": false,
            "hotkey": "Ctrl+Shift+D",
            "ask_hotkey": "",
            "hotkey_mode": "toggle",
            "history_enabled": false,
            "history_retention_days": 30,
            "restore_clipboard_after_paste": false,
            "selected_text_enabled": false,
            "auto_start": false,
            "ui_language": "ja"
        });
        let migrated = AppConfig::from_stored_value(legacy.clone()).unwrap();
        let serialized = serde_json::to_value(&migrated).unwrap();
        for key in [
            "stt_provider",
            "stt_custom_preset",
            "stt_custom_base_url",
            "stt_custom_model",
            "llm_provider",
            "llm_base_url",
            "llm_model",
            "polish_enabled",
            "hotkey",
            "ask_hotkey",
            "hotkey_mode",
            "history_enabled",
            "history_retention_days",
            "restore_clipboard_after_paste",
            "selected_text_enabled",
            "auto_start",
            "ui_language",
        ] {
            assert_eq!(serialized[key], legacy[key], "legacy field changed: {key}");
        }
        assert!(!migrated.capsule_auto_hide);
        assert!(!migrated.mute_background_audio);
        assert!(migrated.microphone_device.is_empty());
        assert!(migrated.language_variants.is_empty());
        assert!(migrated.show_in_dock);
        let reopened = AppConfig::from_stored_value(serialized.clone()).unwrap();
        assert_eq!(serde_json::to_value(reopened).unwrap(), serialized);
    }

    #[test]
    fn app_config_defaults_audio_preferences_and_keeps_existing_users_unmuted() {
        let existing = AppConfig::from_stored_value(serde_json::json!({
            "stt_provider": "glm-asr"
        }))
        .unwrap();
        assert_eq!(existing.microphone_device, "");
        assert!(existing.interaction_sounds);
        assert!(!existing.mute_background_audio);

        let fresh = AppConfig::new_install_default();
        assert!(fresh.mute_background_audio);
        assert!(fresh.interaction_sounds);
    }

    #[test]
    fn app_config_migrates_legacy_keyboard_output_to_auto_insertion_strategy() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "output_mode": "keyboard"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.insertion_strategy, "auto");
        assert_eq!(config.output_mode, "keyboard");
    }

    #[test]
    fn app_config_migrates_legacy_clipboard_output_to_clipboard_paste_strategy() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "output_mode": "clipboard"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.insertion_strategy, "clipboardPaste");
        assert_eq!(config.output_mode, "clipboard");
    }

    #[test]
    fn app_config_migrates_legacy_hotkeys_to_typed_config() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "hotkey": "Ctrl+Shift+;",
            "ask_hotkey": "Ctrl+Alt+.",
            "hotkey_mode": "toggle"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(
            config.hotkeys.dictation,
            ShortcutBinding {
                primary: ";".to_string(),
                modifiers: vec!["Ctrl".to_string(), "Shift".to_string()],
            }
        );
        assert_eq!(
            config.hotkeys.ask,
            Some(ShortcutBinding {
                primary: ".".to_string(),
                modifiers: vec!["Ctrl".to_string(), "Alt".to_string()],
            })
        );
        assert_eq!(config.hotkeys.dictation_mode, "toggle");
        assert_eq!(config.hotkey, "Ctrl+Shift+;");
        assert_eq!(config.ask_hotkey, "Ctrl+Alt+.");
        assert_eq!(config.hotkey_mode, "toggle");
    }

    #[test]
    fn app_config_uses_typed_hotkeys_when_present() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "hotkey": "Ctrl+/",
            "ask_hotkey": "Ctrl+.",
            "hotkey_mode": "hold",
            "hotkeys": {
                "dictation": { "primary": "-", "modifiers": ["Ctrl", "Shift"] },
                "ask": { "primary": ".", "modifiers": ["Ctrl"] },
                "translate": null,
                "editSelection": null,
                "switchScene": null,
                "openApp": null,
                "dictationMode": "toggle"
            }
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.hotkey, "Ctrl+Shift+-");
        assert_eq!(config.ask_hotkey, "Ctrl+.");
        assert_eq!(config.hotkey_mode, "toggle");
        assert_eq!(config.hotkeys.dictation.primary, "-");
        assert_eq!(config.hotkeys.dictation.modifiers, vec!["Ctrl", "Shift"]);
    }

    #[test]
    fn app_config_preserves_extended_typed_hotkey_roles_when_present() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "hotkey": "Ctrl+/",
            "ask_hotkey": "Ctrl+.",
            "hotkey_mode": "hold",
            "hotkeys": {
                "dictation": { "primary": "-", "modifiers": ["Ctrl", "Shift"] },
                "ask": null,
                "translate": { "primary": "T", "modifiers": ["Ctrl", "Shift"] },
                "editSelection": { "primary": "E", "modifiers": ["Ctrl", "Shift"] },
                "switchScene": { "primary": "S", "modifiers": ["Ctrl", "Shift"] },
                "openApp": { "primary": "O", "modifiers": ["Ctrl", "Shift"] },
                "dictationMode": "toggle"
            }
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.hotkey, "Ctrl+Shift+-");
        assert_eq!(config.ask_hotkey, "");
        assert_eq!(config.hotkeys.ask, None);
        assert_eq!(
            config.hotkeys.translate,
            Some(ShortcutBinding {
                primary: "T".to_string(),
                modifiers: vec!["Ctrl".to_string(), "Shift".to_string()],
            })
        );
        assert_eq!(
            config.hotkeys.edit_selection,
            Some(ShortcutBinding {
                primary: "E".to_string(),
                modifiers: vec!["Ctrl".to_string(), "Shift".to_string()],
            })
        );
        assert_eq!(
            config.hotkeys.switch_scene,
            Some(ShortcutBinding {
                primary: "S".to_string(),
                modifiers: vec!["Ctrl".to_string(), "Shift".to_string()],
            })
        );
        assert_eq!(
            config.hotkeys.open_app,
            Some(ShortcutBinding {
                primary: "O".to_string(),
                modifiers: vec!["Ctrl".to_string(), "Shift".to_string()],
            })
        );
    }

    #[test]
    fn hotkey_binding_lists_migrate_legacy_scalars_and_mirror_primaries() {
        let config = AppConfig::from_stored_value(serde_json::json!({
            "hotkey": "Ctrl+Shift+;",
            "ask_hotkey": "Ctrl+Alt+.",
            "hotkey_mode": "toggle"
        }))
        .unwrap();

        assert_eq!(
            config.hotkeys.dictation_bindings,
            vec![config.hotkeys.dictation.clone()]
        );
        assert_eq!(
            config.hotkeys.ask_bindings,
            vec![config.hotkeys.ask.clone().unwrap()]
        );
        assert!(config.hotkeys.translate_bindings.is_empty());
        assert_eq!(config.hotkey, "Ctrl+Shift+;");
        assert_eq!(config.ask_hotkey, "Ctrl+Alt+.");
    }

    #[test]
    fn hotkey_binding_lists_normalize_dedupe_clamp_and_preserve_order() {
        let config = AppConfig::from_stored_value(serde_json::json!({
            "hotkey": "Ctrl+/",
            "ask_hotkey": "Ctrl+.",
            "hotkeys": {
                "dictation": { "primary": "/", "modifiers": ["Ctrl"] },
                "ask": { "primary": ".", "modifiers": ["Ctrl"] },
                "translate": null,
                "dictationBindings": [
                    { "primary": ";", "modifiers": ["shift", "control"] },
                    { "primary": ";", "modifiers": ["Ctrl", "Shift"] },
                    { "primary": "D", "modifiers": ["Alt"] },
                    { "primary": "F8", "modifiers": [] },
                    { "primary": "F9", "modifiers": [] }
                ],
                "askBindings": [
                    { "primary": "A", "modifiers": ["Option"] },
                    { "primary": "A", "modifiers": ["Alt"] }
                ],
                "translateBindings": [],
                "editSelection": null,
                "switchScene": null,
                "openApp": null,
                "dictationMode": "hold"
            }
        }))
        .unwrap();

        assert_eq!(config.hotkeys.dictation_bindings.len(), 4);
        assert_eq!(
            config.hotkeys.dictation_bindings[3]
                .to_hotkey_string()
                .as_deref(),
            Some("F9")
        );
        assert_eq!(
            config.hotkeys.dictation_bindings[0]
                .to_hotkey_string()
                .as_deref(),
            Some("Ctrl+Shift+;")
        );
        assert_eq!(
            config.hotkeys.dictation_bindings[1]
                .to_hotkey_string()
                .as_deref(),
            Some("Alt+D")
        );
        assert_eq!(
            config.hotkeys.dictation_bindings[2]
                .to_hotkey_string()
                .as_deref(),
            Some("F8")
        );
        assert_eq!(config.hotkey, "Ctrl+Shift+;");
        assert_eq!(config.hotkeys.ask_bindings.len(), 1);
        assert_eq!(config.ask_hotkey, "Option+A");
        assert_eq!(
            config.hotkeys.dictation,
            config.hotkeys.dictation_bindings[0]
        );
        assert_eq!(
            config.hotkeys.ask.as_ref(),
            config.hotkeys.ask_bindings.first()
        );
    }

    #[test]
    fn hotkey_binding_lists_drop_corruption_and_keep_dictation_nonempty() {
        let config = AppConfig::from_stored_value(serde_json::json!({
            "hotkey": "Ctrl+/",
            "ask_hotkey": "Ctrl+.",
            "hotkeys": {
                "dictation": { "primary": "/", "modifiers": ["Ctrl"] },
                "ask": { "primary": ".", "modifiers": ["Ctrl"] },
                "translate": null,
                "dictationBindings": [
                    { "primary": "", "modifiers": ["Ctrl"] }
                ],
                "askBindings": [
                    { "primary": ".", "modifiers": ["Ctrl", "Ctrl"] }
                ],
                "translateBindings": [
                    { "primary": "T", "modifiers": ["shift", "control"] }
                ],
                "editSelection": null,
                "switchScene": null,
                "openApp": null,
                "dictationMode": "hold"
            }
        }))
        .unwrap();

        assert_eq!(config.hotkeys.dictation_bindings.len(), 1);
        assert_eq!(
            config.hotkeys.dictation_bindings[0]
                .to_hotkey_string()
                .as_deref(),
            Some(default_dictation_hotkey())
        );
        assert!(config.hotkeys.ask_bindings.is_empty());
        assert_eq!(config.ask_hotkey, "");
        assert_eq!(
            config.hotkeys.translate_bindings[0]
                .to_hotkey_string()
                .as_deref(),
            Some("Ctrl+Shift+T")
        );
    }

    #[test]
    fn hotkey_binding_lists_survive_restart_and_export_legacy_primary_fields() {
        let mut original = AppConfig::default();
        original.hotkeys.dictation_bindings = vec![
            ShortcutBinding::from_hotkey("Ctrl+Shift+D").unwrap(),
            ShortcutBinding::from_hotkey("F8").unwrap(),
        ];
        original.hotkeys.ask_bindings = Vec::new();
        original.hotkeys.ask = None;
        original.hotkeys.translate_bindings = vec![
            ShortcutBinding::from_hotkey("Ctrl+Shift+T").unwrap(),
            ShortcutBinding::from_hotkey("F9").unwrap(),
        ];
        original.normalize_values();

        let stored = serde_json::to_value(&original).unwrap();
        assert_eq!(stored["hotkey"], "Ctrl+Shift+D");
        assert_eq!(stored["ask_hotkey"], "");
        assert_eq!(stored["hotkeys"]["dictation"]["primary"], "D");
        assert_eq!(
            stored["hotkeys"]["dictationBindings"]
                .as_array()
                .unwrap()
                .len(),
            2
        );

        let restarted = AppConfig::from_stored_value(stored).unwrap();
        assert_eq!(
            restarted.hotkeys.dictation_bindings,
            original.hotkeys.dictation_bindings
        );
        assert_eq!(
            restarted.hotkeys.ask_bindings,
            original.hotkeys.ask_bindings
        );
        assert_eq!(
            restarted.hotkeys.translate_bindings,
            original.hotkeys.translate_bindings
        );
    }

    #[test]
    fn app_config_preserves_explicit_copy_only_insertion_strategy() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "output_mode": "keyboard",
            "insertion_strategy": "clipboardCopyOnly"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.insertion_strategy, "clipboardCopyOnly");
        assert_eq!(config.output_mode, "clipboard");
    }

    #[test]
    fn app_config_defaults_and_normalizes_windows_sendinput_newline_mode() {
        let default_value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter"
        });
        let default_config = AppConfig::from_stored_value(default_value).unwrap();
        assert_eq!(default_config.windows_sendinput_newline_mode, "enter");

        let explicit_value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "windows_sendinput_newline_mode": "shiftEnter"
        });
        let explicit_config = AppConfig::from_stored_value(explicit_value).unwrap();
        assert_eq!(explicit_config.windows_sendinput_newline_mode, "shiftEnter");

        let invalid_value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter",
            "windows_sendinput_newline_mode": "invalid"
        });
        let invalid_config = AppConfig::from_stored_value(invalid_value).unwrap();
        assert_eq!(invalid_config.windows_sendinput_newline_mode, "enter");
    }

    #[test]
    fn app_config_defaults_missing_custom_scenes() {
        let value = serde_json::json!({
            "stt_provider": "glm-asr",
            "llm_provider": "openrouter"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert!(config.custom_scenes.is_empty());
        assert!(config.active_scene.is_none());
        assert!(config.family_scene_assignments.is_empty());
    }

    #[test]
    fn app_config_sanitizes_family_scene_assignments_against_available_scenes() {
        let mut value = serde_json::to_value(AppConfig::default()).unwrap();
        value["custom_scenes"] = serde_json::json!([
            {
                "id": "custom_focus",
                "name": "Focus",
                "description": "",
                "prompt_template": "Use short status bullets.",
                "created_at": "",
                "updated_at": ""
            }
        ]);
        value["family_scene_assignments"] = serde_json::json!([
            { "family": "email", "scene_id": "  builtin_professional_email  " },
            { "family": "email", "scene_id": "builtin_clean_dictation" },
            { "family": "work_chat", "scene_id": "custom_focus" },
            { "family": "support", "scene_id": "missing_scene" },
            { "family": "general", "scene_id": "\0" }
        ]);

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(
            config.family_scene_assignments,
            vec![
                FamilySceneAssignment {
                    family: ContextFamily::Email,
                    scene_id: "builtin_professional_email".to_string(),
                },
                FamilySceneAssignment {
                    family: ContextFamily::WorkChat,
                    scene_id: "custom_focus".to_string(),
                },
            ]
        );
    }

    #[test]
    fn scene_prompt_resolution_supports_builtins_custom_scenes_and_compat_family_lookup() {
        let mut config = AppConfig::default();
        config.custom_scenes.push(CustomScene {
            id: "custom_focus".to_string(),
            name: "Focus".to_string(),
            description: String::new(),
            prompt_template: "Use short status bullets.".to_string(),
            created_at: String::new(),
            updated_at: String::new(),
        });
        config.family_scene_assignments = vec![FamilySceneAssignment {
            family: ContextFamily::WorkChat,
            scene_id: "custom_focus".to_string(),
        }];
        config.normalize_values();

        assert_eq!(
            scene_prompt_for_id(&config, "builtin_professional_email").as_deref(),
            Some(
                "Rewrite the transcript as a concise professional email body. Use a greeting when the recipient is spoken, clear body paragraphs, and a light closing when appropriate. Do not add facts or generate a subject unless requested."
            )
        );
        assert_eq!(
            scene_prompt_for_id(&config, "custom_focus").as_deref(),
            Some("Use short status bullets.")
        );
        assert_eq!(
            family_scene_prompt(&config, ContextFamily::WorkChat).as_deref(),
            Some("Use short status bullets.")
        );
        assert_eq!(
            family_scene_prompt(&config, ContextFamily::Email).as_deref(),
            Some(
                "Email system mode: produce an email body when there is enough content. Use a greeting when the recipient is spoken, concise body paragraphs, and a light closing when appropriate. Do not generate a subject unless explicitly requested."
            )
        );
        assert_eq!(scene_prompt_for_id(&config, "missing_scene"), None);

        assert_eq!(
            automatic_scene_prompt(
                &config,
                ContextFamily::WorkChat,
                Some("builtin_professional_email")
            )
            .as_deref(),
            scene_prompt_for_id(&config, "builtin_professional_email").as_deref()
        );
        assert_eq!(
            automatic_scene_prompt(&config, ContextFamily::WorkChat, Some("custom_focus"))
                .as_deref(),
            Some("Use short status bullets.")
        );
        assert_eq!(
            automatic_scene_prompt(&config, ContextFamily::WorkChat, None).as_deref(),
            Some("Use short status bullets.")
        );

        config.active_scene = Some(ActiveScene {
            id: "builtin_meeting_notes".to_string(),
            source: "builtin".to_string(),
            name: "Meeting Notes".to_string(),
            prompt_template: "Manual scene wins.".to_string(),
        });
        assert_eq!(
            automatic_scene_prompt(
                &config,
                ContextFamily::WorkChat,
                Some("builtin_professional_email")
            ),
            None
        );
    }

    #[test]
    fn system_scene_overrides_replace_default_system_prompts() {
        let mut config = AppConfig {
            system_scene_overrides: vec![SystemSceneOverride {
                id: "system_email".to_string(),
                prompt_template: "Use a warm email body with concise bullets.".to_string(),
            }],
            family_scene_assignments: vec![FamilySceneAssignment {
                family: ContextFamily::Email,
                scene_id: "system_email".to_string(),
            }],
            ..Default::default()
        };
        config.normalize_values();

        assert_eq!(
            scene_prompt_for_id(&config, "system_email").as_deref(),
            Some("Use a warm email body with concise bullets.")
        );
        assert_eq!(
            automatic_scene_prompt(&config, ContextFamily::Email, None).as_deref(),
            Some("Use a warm email body with concise bullets.")
        );
    }

    #[test]
    fn app_config_treats_null_system_scene_overrides_as_empty() {
        let mut value = serde_json::to_value(AppConfig::default()).unwrap();
        value["system_scene_overrides"] = serde_json::Value::Null;

        let config = AppConfig::from_stored_value(value).unwrap();

        assert!(config.system_scene_overrides.is_empty());
    }

    #[test]
    fn app_config_sanitizes_custom_scenes_and_active_scene() {
        let mut value = serde_json::to_value(AppConfig::default()).unwrap();
        value["custom_scenes"] = serde_json::json!([
            {
                "id": "  custom_keep  ",
                "name": format!("  Scene\0{}  ", "x".repeat(100)),
                "description": format!("  Desc\0{}  ", "y".repeat(300)),
                "prompt_template": format!("  Prompt\0{}  ", "z".repeat(5000)),
                "created_at": "  2026-06-30T00:00:00.000Z  ",
                "updated_at": "  2026-06-30T00:00:00.000Z  "
            }
        ]);
        value["active_scene"] = serde_json::json!({
            "id": "  custom_keep  ",
            "source": "custom",
            "name": "  Active\0 Scene  ",
            "prompt_template": "  Use bullets.\0  "
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.custom_scenes.len(), 1);
        let scene = &config.custom_scenes[0];
        assert_eq!(scene.id, "custom_keep");
        assert_eq!(scene.name.chars().count(), 80);
        assert!(scene.name.starts_with("Scene"));
        assert!(!scene.name.contains('\0'));
        assert_eq!(scene.description.chars().count(), 240);
        assert_eq!(scene.prompt_template.chars().count(), 4000);

        let active_scene = config
            .active_scene
            .expect("active scene should remain valid");
        assert_eq!(active_scene.id, "custom_keep");
        assert_eq!(active_scene.name, "Active Scene");
        assert_eq!(active_scene.prompt_template, "Use bullets.");
    }

    #[test]
    fn app_config_clears_empty_active_scene() {
        let mut value = serde_json::to_value(AppConfig::default()).unwrap();
        value["active_scene"] = serde_json::json!({
            "id": "custom_empty",
            "source": "custom",
            "name": "Empty",
            "prompt_template": "   \0  "
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert!(config.active_scene.is_none());
    }

    #[test]
    fn app_config_sanitizes_custom_polish_prompt_and_clears_chinese_script() {
        let mut value = serde_json::to_value(AppConfig::default()).unwrap();
        value["polish_custom_prompt"] = serde_json::json!("  use formal tone\0  ");
        value["polish_chinese_script"] = serde_json::json!("traditional");

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.polish_custom_prompt, "use formal tone");
        assert_eq!(config.polish_chinese_script, "preserve");
    }

    #[test]
    fn app_config_new_install_defaults_capsule_auto_hide_true() {
        let config = AppConfig::new_install_default();
        assert!(config.capsule_auto_hide);
        assert!(config.auto_start);
    }

    #[test]
    fn auto_hotkey_mode_survives_load_and_existing_modes_are_kept() {
        let auto = AppConfig::from_stored_value(serde_json::json!({
            "hotkeys": { "dictationMode": "auto" }
        }))
        .unwrap();
        assert_eq!(auto.hotkeys.dictation_mode, "auto");
        assert_eq!(auto.hotkey_mode, "auto");

        let existing = AppConfig::from_stored_value(serde_json::json!({
            "hotkey": "Ctrl+Shift+;",
            "hotkey_mode": "toggle"
        }))
        .unwrap();
        assert_eq!(existing.hotkey_mode, "toggle");

        let unknown = AppConfig::from_stored_value(serde_json::json!({
            "hotkeys": { "dictationMode": "sometimes" }
        }))
        .unwrap();
        assert_eq!(unknown.hotkeys.dictation_mode, "hold");
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn app_config_new_install_uses_fn_auto_on_macos() {
        let config = AppConfig::new_install_default();
        assert_eq!(config.hotkey, "Fn");
        assert_eq!(config.hotkeys.dictation.primary, "Fn");
        assert_eq!(config.hotkeys.dictation.modifiers, Vec::<String>::new());
        assert_eq!(config.hotkey_mode, "auto");
        assert_eq!(config.hotkeys.dictation_mode, "auto");
        assert_eq!(config.ask_hotkey, "Fn+Space");
        assert_eq!(
            config
                .hotkeys
                .ask
                .as_ref()
                .and_then(ShortcutBinding::to_hotkey_string),
            Some("Fn+Space".to_string())
        );
        assert_eq!(
            config
                .hotkeys
                .translate
                .as_ref()
                .and_then(ShortcutBinding::to_hotkey_string),
            Some("Fn+LeftShift".to_string())
        );
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn app_config_new_install_uses_approved_right_side_shortcuts_on_windows() {
        let config = AppConfig::new_install_default();
        assert_eq!(config.hotkey, "RightAlt");
        assert_eq!(config.hotkeys.dictation.primary, "RightAlt");
        assert!(config.hotkeys.dictation.modifiers.is_empty());
        assert_eq!(config.hotkey_mode, "auto");
        assert_eq!(config.hotkeys.dictation_mode, "auto");
        assert_eq!(config.ask_hotkey, "RightAlt+Space");
        assert_eq!(
            config
                .hotkeys
                .ask
                .as_ref()
                .and_then(ShortcutBinding::to_hotkey_string),
            Some("RightAlt+Space".to_string())
        );
        assert_eq!(
            config
                .hotkeys
                .translate
                .as_ref()
                .and_then(ShortcutBinding::to_hotkey_string),
            Some("RightAlt+RightShift".to_string())
        );
    }

    #[cfg(all(not(target_os = "macos"), not(target_os = "windows")))]
    #[test]
    fn app_config_new_install_keeps_ctrl_slash_on_linux() {
        let config = AppConfig::new_install_default();
        assert_eq!(config.hotkey, "Ctrl+/");
        assert_eq!(config.hotkey_mode, "auto");
        assert_eq!(config.ask_hotkey, "Ctrl+.");
        assert_eq!(
            config
                .hotkeys
                .translate
                .as_ref()
                .and_then(ShortcutBinding::to_hotkey_string),
            Some("Ctrl+Shift+/".to_string())
        );
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn app_config_migrates_old_mac_default_hotkey_to_fn() {
        let value = serde_json::json!({
            "hotkey": "Option+/",
            "ask_hotkey": "Command+.",
            "hotkey_mode": "hold"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.hotkey, "Fn");
        assert_eq!(config.hotkeys.dictation.primary, "Fn");
        assert_eq!(config.hotkeys.dictation.modifiers, Vec::<String>::new());
        assert_eq!(config.hotkey_mode, "toggle");
        assert_eq!(config.hotkeys.dictation_mode, "toggle");
    }

    #[test]
    fn app_config_preserves_custom_hotkey_during_native_default_migration() {
        let value = serde_json::json!({
            "hotkey": "Ctrl+Shift+;",
            "ask_hotkey": "Ctrl+.",
            "hotkey_mode": "hold"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.hotkey, "Ctrl+Shift+;");
        assert_eq!(config.hotkeys.dictation.primary, ";");
        assert_eq!(
            config.hotkeys.dictation.modifiers,
            vec!["Ctrl".to_string(), "Shift".to_string()]
        );
        assert_eq!(config.hotkey_mode, "hold");
        assert_eq!(config.hotkeys.dictation_mode, "hold");
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

    #[cfg(target_os = "macos")]
    #[test]
    fn app_config_migrates_legacy_mac_alt_slash_label() {
        let value = serde_json::json!({
            "hotkey": "Alt+/"
        });

        let config = AppConfig::from_stored_value(value).unwrap();

        assert_eq!(config.hotkey, "Option+/");
    }

    #[cfg(target_os = "macos")]
    #[test]
    fn app_config_migrates_legacy_mac_ask_hotkey_defaults() {
        for legacy in [
            "Alt+Shift+/",
            "Option+Shift+/",
            "Command+Shift+/",
            "Command+/",
            "Command+。",
        ] {
            let value = serde_json::json!({
                "ask_hotkey": legacy
            });

            let config = AppConfig::from_stored_value(value).unwrap();

            assert_eq!(config.ask_hotkey, "Fn+Space");
        }
    }

    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    #[test]
    fn app_config_migrates_legacy_non_macos_ask_hotkey_defaults() {
        for legacy in ["Ctrl+Shift+/", "Control+Shift+/", "Ctrl+/", "Control+/"] {
            let value = serde_json::json!({
                "ask_hotkey": legacy
            });

            let config = AppConfig::from_stored_value(value).unwrap();

            #[cfg(not(target_os = "macos"))]
            assert_eq!(config.ask_hotkey, "Ctrl+.");
        }
    }

    #[cfg(target_os = "windows")]
    #[test]
    fn windows_legacy_saved_shortcuts_are_not_overwritten_by_new_defaults() {
        for mode in ["auto", "hold", "toggle"] {
            let value = serde_json::json!({
                "hotkey": "Ctrl+/",
                "ask_hotkey": "Ctrl+.",
                "hotkey_mode": mode
            });
            let config = AppConfig::from_stored_value(value).unwrap();
            assert_eq!(config.hotkey, "Ctrl+/");
            assert_eq!(config.ask_hotkey, "Ctrl+.");
            assert_eq!(config.hotkey_mode, mode);
        }
    }

    fn test_history_entry(id: i64, created_at: &str) -> HistoryEntry {
        HistoryEntry {
            has_audio: false,
            id,
            created_at: created_at.to_string(),
            context_profile_id: "general.native".to_string(),
            context_label: "General".to_string(),
            context_icon_key: "general".to_string(),
            context_family: ContextFamily::General,
            browser_access_status: BrowserAccessStatus::NotApplicable,
            provider_kind: HistoryProviderKind::Local,
            raw_text: format!("raw {id}"),
            polished_text: format!("polished {id}"),
            language: None,
            duration_ms: None,
            active_scene_id: None,
            active_scene_source: None,
            active_scene_name: None,
            active_scene_prompt_chars: None,
            active_scene_prompt_truncated: false,
            output_status: None,
            output_error: None,
            mode: "dictate".to_string(),
            answer_text: None,
            ask_intent: None,
            citations: None,
            citation_supports: None,
        }
    }

    #[tokio::test]
    async fn local_text_snapshot_bounds_total_utf8_bytes_without_truncating_history() {
        let store = temp_history_store("bounded-text-snapshot");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let limit = 1024 * 1024;
        let text = "中".repeat((limit - 20) / 3);
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = ?1, polished_text = '', mode = 'x',
                     answer_text = NULL, language = NULL WHERE id = 1",
                    [&text],
                )
                .unwrap();
        }
        assert_eq!(
            store.local_text_snapshot(1).unwrap().unwrap().raw_text,
            text
        );
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("UPDATE history SET answer_text = 'more' WHERE id = 1", [])
                .unwrap();
        }
        assert_eq!(
            store.local_text_snapshot(1).err().unwrap().to_string(),
            "History synchronization text unavailable"
        );
        let retained = store.get(1).await.unwrap().unwrap();
        assert_eq!(retained.raw_text, text);
        assert_eq!(retained.answer_text.as_deref(), Some("more"));
        assert!(store.local_identity_snapshot(1).unwrap().is_some());
    }

    #[tokio::test]
    async fn local_text_snapshot_includes_language_in_its_byte_budget() {
        let store = temp_history_store("snapshot-language-budget");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let language = "x".repeat(1024 * 1024);
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("UPDATE history SET language = ?1 WHERE id = 1", [&language])
                .unwrap();
        }
        assert_eq!(
            store.local_text_snapshot(1).err().unwrap().to_string(),
            "History synchronization text unavailable"
        );
        assert_eq!(
            store.get(1).await.unwrap().unwrap().language,
            Some(language)
        );
    }

    #[tokio::test]
    async fn late_history_confirmation_preserves_newer_pending_revision() {
        let store = temp_history_store("late-history-confirm");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let original = store.local_identity_snapshot(1).unwrap().unwrap();
        let old = store
            .enqueue_local_history_revision("synthetic-account", 1, &original)
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'New revision' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let current = store.local_identity_snapshot(1).unwrap().unwrap();
        let newer = store
            .enqueue_local_history_revision("synthetic-account", 1, &current)
            .unwrap();
        assert!(!store.confirm_pending_history_revision(&old, 1).unwrap());
        assert_eq!(
            store
                .pending_history_revisions("synthetic-account", 10)
                .unwrap()
                .len(),
            2
        );
        assert!(store.confirm_pending_history_revision(&newer, 2).unwrap());
        let remaining = store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        assert!(remaining.is_empty());
    }

    #[tokio::test]
    async fn history_confirmation_delete_failure_rolls_back_receipt_and_allows_retry() {
        let store = temp_history_store("history-confirm-rollback");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "CREATE TRIGGER reject_pending_delete BEFORE DELETE ON history_sync_pending
                     BEGIN SELECT RAISE(ABORT, 'Synthetic pending delete failure'); END;",
                )
                .unwrap();
        }
        assert!(store
            .confirm_pending_history_revision(&mutation, 1)
            .is_err());
        let pending = store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].mutation, mutation);
        {
            let connection = store.conn.lock().unwrap();
            let receipts: i64 = connection
                .query_row("SELECT COUNT(*) FROM history_sync_confirmed", [], |row| {
                    row.get(0)
                })
                .unwrap();
            assert_eq!(receipts, 0);
            assert!(connection.is_autocommit());
            connection
                .execute_batch("DROP TRIGGER reject_pending_delete")
                .unwrap();
        }
        assert!(store
            .confirm_pending_history_revision(&mutation, 1)
            .unwrap());
        assert!(store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
        assert_eq!(store.local_identity_snapshot(1).unwrap(), Some(identity));
        assert_eq!(store.get(1).await.unwrap().unwrap().raw_text, "raw 0");
    }

    #[tokio::test]
    async fn history_queue_byte_capacity_rejection_does_not_claim_new_record() {
        let store = temp_history_store("queue-payload-capacity");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "WITH RECURSIVE entries(number) AS (
                         SELECT 1 UNION ALL SELECT number + 1 FROM entries WHERE number < 16
                     )
                     INSERT INTO history_sync_pending
                     (account_id, record_uuid, mutation_uuid, client_revision, base_server_revision, payload_json)
                     SELECT 'synthetic-account', 'record-' || number, 'mutation-' || number,
                            1, 0, CAST(zeroblob(1048576) AS TEXT) FROM entries;",
                )
                .unwrap();
        }
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap_err()
                .to_string(),
            "History sync queue byte capacity reached"
        );
        let connection = store.conn.lock().unwrap();
        let claimed: bool = connection
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM history_sync_record_owner WHERE record_uuid = ?1)",
                [&identity.record_uuid],
                |row| row.get(0),
            )
            .unwrap();
        assert!(!claimed);
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_pending", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 16);
    }

    #[tokio::test]
    async fn failed_history_clear_restores_queued_payloads_and_allows_retry() {
        let store = temp_history_store("clear-payload-rollback");
        let mut queued = Vec::new();
        for id in 1..=2 {
            store
                .add(test_history_entry(0, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(id).unwrap().unwrap();
            queued.push(
                store
                    .enqueue_local_history_revision("synthetic-account", id, &identity)
                    .unwrap(),
            );
        }
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "CREATE TRIGGER reject_second_history_delete AFTER DELETE ON history
                     WHEN OLD.id = 2
                     BEGIN SELECT RAISE(ABORT, 'Synthetic clear failure'); END;",
                )
                .unwrap();
        }
        assert!(store.clear().await.is_err());
        assert_eq!(store.list(10, 0).await.unwrap().len(), 2);
        let pending = store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        assert_eq!(
            pending
                .iter()
                .map(|entry| &entry.mutation)
                .collect::<Vec<_>>(),
            queued.iter().collect::<Vec<_>>()
        );
        {
            let connection = store.conn.lock().unwrap();
            let payloads: i64 = connection
                .query_row(
                    "SELECT COUNT(payload_json) FROM history_sync_pending",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(payloads, 2);
            assert!(connection.is_autocommit());
            connection
                .execute_batch("DROP TRIGGER reject_second_history_delete")
                .unwrap();
        }
        store.clear().await.unwrap();
        assert!(store.list(10, 0).await.unwrap().is_empty());
        let connection = store.conn.lock().unwrap();
        let payloads: i64 = connection
            .query_row(
                "SELECT COUNT(payload_json) FROM history_sync_pending",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(payloads, 0);
    }

    #[tokio::test]
    async fn clearing_history_removes_all_queued_payload_copies() {
        let store = temp_history_store("clear-queued-payloads");
        let mut mutations = Vec::new();
        for id in 1..=2 {
            store
                .add(test_history_entry(0, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(id).unwrap().unwrap();
            mutations.push(
                store
                    .enqueue_local_history_revision("synthetic-account", id, &identity)
                    .unwrap(),
            );
        }
        store.clear().await.unwrap();
        assert!(store
            .pending_sendable_history_revisions("synthetic-account", 200)
            .unwrap()
            .is_empty());
        assert_eq!(store.pending_history_count("synthetic-account").unwrap(), 2);
        assert_eq!(
            store
                .pending_actionable_history_count("synthetic-account")
                .unwrap(),
            0
        );
        for mutation in &mutations {
            assert!(store.build_pending_history_request(mutation).is_err());
        }
        assert!(store
            .build_pending_history_batch_request(&mutations)
            .is_err());
        let connection = store.conn.lock().unwrap();
        let counts: (i64, i64) = connection
            .query_row(
                "SELECT COUNT(*), COUNT(payload_json) FROM history_sync_pending",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(counts, (2, 0));
    }

    #[tokio::test]
    async fn authorized_bulk_clear_preserves_remote_delete_intents_atomically() {
        let store = temp_history_store("authorized-bulk-clear");
        for history_id in 1..=2 {
            store
                .add(test_history_entry(0, "2026-11-21T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(history_id).unwrap().unwrap();
            let mutation = store
                .enqueue_local_history_revision("synthetic-account", history_id, &identity)
                .unwrap();
            store
                .confirm_pending_history_revision(&mutation, 1)
                .unwrap();
        }
        store
            .clear_history_with_sync_commit("synthetic-account", |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .unwrap();
        assert!(store.list(10, 0).await.unwrap().is_empty());
        let pending = store
            .pending_sendable_history_revisions("synthetic-account", 200)
            .unwrap();
        assert_eq!(pending.len(), 2);
        let request = store.build_pending_history_batch_request(&pending).unwrap();
        assert!(request["mutations"]
            .as_array()
            .unwrap()
            .iter()
            .all(|change| { change["deleted"] == true && change["payload"].is_null() }));
    }

    #[tokio::test]
    async fn authorized_bulk_clear_rolls_back_every_row_when_commit_is_rejected() {
        let store = temp_history_store("authorized-bulk-clear-rollback");
        for history_id in 1..=2 {
            store
                .add(test_history_entry(0, "2026-11-21T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(history_id).unwrap().unwrap();
            let mutation = store
                .enqueue_local_history_revision("synthetic-account", history_id, &identity)
                .unwrap();
            store
                .confirm_pending_history_revision(&mutation, 1)
                .unwrap();
        }
        assert!(store
            .clear_history_with_sync_commit("synthetic-account", |_transaction| {
                anyhow::bail!("Synthetic authorization revoked")
            })
            .is_err());
        assert_eq!(store.list(10, 0).await.unwrap().len(), 2);
        assert!(store
            .pending_sendable_history_revisions("synthetic-account", 200)
            .unwrap()
            .is_empty());
        assert!(store
            .clear_history_with_sync_commit("other-account", |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .is_err());
        assert_eq!(store.list(10, 0).await.unwrap().len(), 2);
    }

    #[test]
    fn cloud_backup_history_snapshot_rejects_overflow_instead_of_truncating() {
        let store = temp_history_store("cloud-backup-snapshot");
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "WITH RECURSIVE entries(id) AS (
                        SELECT 1 UNION ALL SELECT id + 1 FROM entries WHERE id < 5001
                     )
                     INSERT INTO history(created_at, raw_text, polished_text)
                     SELECT '2026-11-22T10:00:00', 'Synthetic history', 'Synthetic history'
                     FROM entries;",
                )
                .unwrap();
        }
        assert_eq!(
            store
                .cloud_backup_history_snapshot()
                .unwrap_err()
                .to_string(),
            "backup_history_too_large"
        );
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("DELETE FROM history WHERE id = 5001", [])
                .unwrap();
        }
        let entries = store.cloud_backup_history_snapshot().unwrap();
        assert_eq!(entries.len(), 5000);
        assert_eq!(entries.first().unwrap().id, 5000);
        assert_eq!(entries.last().unwrap().id, 1);
    }

    #[test]
    fn cloud_backup_data_keeps_dictionary_in_the_history_read_transaction() {
        let store = temp_history_store("aggregate-backup-snapshot");
        let connection = store.conn.lock().unwrap();
        let path: String = connection
            .query_row("PRAGMA database_list", [], |row| row.get(2))
            .unwrap();
        drop(connection);
        let dictionary = DictionaryStore::new(PathBuf::from(&path)).unwrap();
        dictionary
            .conn
            .lock()
            .unwrap()
            .execute(
                "INSERT INTO dictionary(word, auto) VALUES ('Before', 0)",
                [],
            )
            .unwrap();
        let writer = Connection::open(path).unwrap();
        let (_, entries, _) = store
            .cloud_backup_data_snapshot_after_history(|| {
                writer.execute("UPDATE dictionary SET word = 'After'", [])?;
                Ok(())
            })
            .unwrap();
        assert_eq!(entries[0].word, "Before");
        assert_eq!(
            writer
                .query_row("SELECT word FROM dictionary", [], |row| row
                    .get::<_, String>(0))
                .unwrap(),
            "After"
        );
    }

    #[test]
    fn cloud_backup_data_rejects_missing_tables_and_dictionary_overflow() {
        let store = temp_history_store("aggregate-backup-limits");
        assert!(store.cloud_backup_data_snapshot().is_err());
        let connection = store.conn.lock().unwrap();
        let path: String = connection
            .query_row("PRAGMA database_list", [], |row| row.get(2))
            .unwrap();
        drop(connection);
        let dictionary = DictionaryStore::new(PathBuf::from(path)).unwrap();
        dictionary
            .conn
            .lock()
            .unwrap()
            .execute_batch(
                "WITH RECURSIVE entries(id) AS (
                SELECT 1 UNION ALL SELECT id + 1 FROM entries WHERE id < 10001
             ) INSERT INTO dictionary(word) SELECT 'Synthetic word' FROM entries;",
            )
            .unwrap();
        assert_eq!(
            store.cloud_backup_data_snapshot().unwrap_err().to_string(),
            "backup_dictionary_too_large"
        );
    }

    #[test]
    fn cloud_backup_rejects_large_text_before_materializing_entries() {
        let store = temp_history_store("aggregate-backup-text-limit");
        let connection = store.conn.lock().unwrap();
        connection
            .execute(
                "INSERT INTO history(created_at, raw_text, polished_text)
             VALUES ('2026-11-23T10:00:00', CAST(zeroblob(20971521) AS TEXT), '')",
                [],
            )
            .unwrap();
        drop(connection);
        assert_eq!(
            store.cloud_backup_data_snapshot().unwrap_err().to_string(),
            "backup_snapshot_too_large"
        );
    }

    #[tokio::test]
    async fn pending_history_payload_remains_fixed_after_edit_and_is_cleared_on_delete() {
        let store = temp_history_store("pinned-queue-payload");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let read_payload = || {
            store
                .conn
                .lock()
                .unwrap()
                .query_row(
                    "SELECT payload_json FROM history_sync_pending WHERE mutation_uuid = ?1",
                    [&mutation.mutation_uuid],
                    |row| row.get::<_, Option<String>>(0),
                )
                .unwrap()
        };
        let original = read_payload().unwrap();
        let parsed: serde_json::Value = serde_json::from_str(&original).unwrap();
        assert_eq!(parsed["rawText"], "raw 0");
        assert_eq!(parsed["createdAt"], "2026-11-19T10:00:00");
        assert_eq!(parsed.as_object().unwrap().len(), 7);
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("UPDATE history SET raw_text = 'New text' WHERE id = 1", [])
                .unwrap();
        }
        assert_eq!(read_payload(), Some(original));
        store.delete(1).await.unwrap();
        assert_eq!(read_payload(), None);
        let remaining = store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].mutation, mutation);
        assert_eq!(
            remaining[0].source,
            history_sync_queue::PendingHistorySource::Missing
        );
    }

    #[tokio::test]
    async fn queued_history_revision_pins_its_confirmed_server_base() {
        let store = temp_history_store("queued-history-server-base");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let original = store.local_identity_snapshot(1).unwrap().unwrap();
        let first = store
            .enqueue_local_history_revision("synthetic-account", 1, &original)
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            let base: i64 = connection
                .query_row(
                    "SELECT base_server_revision FROM history_sync_pending",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(base, 0);
        }
        assert!(store.confirm_pending_history_revision(&first, 1).unwrap());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Second revision' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let current = store.local_identity_snapshot(1).unwrap().unwrap();
        let second = store
            .enqueue_local_history_revision("synthetic-account", 1, &current)
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            let base: i64 = connection
                .query_row(
                    "SELECT base_server_revision FROM history_sync_pending",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(base, 1);
            connection
                .execute("UPDATE history_sync_confirmed SET server_revision = 2", [])
                .unwrap();
            connection
                .execute(
                    "UPDATE history_sync_remote_baseline SET server_revision = 2",
                    [],
                )
                .unwrap();
        }
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account", 1, &current)
                .unwrap(),
            second
        );
        assert_eq!(
            store
                .confirm_pending_history_revision(&second, 3)
                .unwrap_err()
                .to_string(),
            "History sync confirmation baseline changed"
        );
        assert_eq!(
            store
                .pending_history_revisions("synthetic-account", 10)
                .unwrap()
                .len(),
            1
        );
        let connection = store.conn.lock().unwrap();
        let base: i64 = connection
            .query_row(
                "SELECT base_server_revision FROM history_sync_pending",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(base, 1);
        connection
            .execute(
                "UPDATE history_sync_pending SET base_server_revision = NULL",
                [],
            )
            .unwrap();
        drop(connection);
        assert!(store
            .pending_history_revisions("synthetic-account", 10)
            .is_err());
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account", 1, &current)
                .unwrap_err()
                .to_string(),
            "History sync queue baseline unknown"
        );
        assert!(!store.confirm_pending_history_revision(&second, 3).unwrap());
    }

    #[tokio::test]
    async fn confirmed_history_revision_cannot_be_enqueued_again_but_new_edits_can() {
        let store = temp_history_store("confirmed-history-reenqueue");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let original = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &original)
            .unwrap();
        assert!(store
            .confirm_pending_history_revision(&mutation, 1)
            .unwrap());
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account", 1, &original)
                .unwrap_err()
                .to_string(),
            "History revision already confirmed"
        );
        assert!(store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'New synthetic revision' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let current = store.local_identity_snapshot(1).unwrap().unwrap();
        let next = store
            .enqueue_local_history_revision("synthetic-account", 1, &current)
            .unwrap();
        assert_eq!(next.client_revision, 2);
        assert_ne!(next.mutation_uuid, mutation.mutation_uuid);
    }

    #[tokio::test]
    async fn pending_history_request_uses_pinned_identity_and_rejects_changed_or_deleted_source() {
        let store = temp_history_store("pending-request-source");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let first = store.build_pending_history_request(&mutation).unwrap();
        assert_eq!(first["version"], 1);
        assert_eq!(first.as_object().unwrap().len(), 2);
        let request = &first["mutations"][0];
        assert_eq!(request.as_object().unwrap().len(), 6);
        assert_eq!(request["recordId"], mutation.record_uuid);
        assert_eq!(request["mutationId"], mutation.mutation_uuid);
        assert_eq!(request["clientRevision"], mutation.client_revision);
        assert_eq!(request["baseServerRevision"], mutation.base_server_revision);
        assert_eq!(request["deleted"], false);
        assert_eq!(request["payload"]["rawText"], "raw 0");
        assert_eq!(request["payload"].as_object().unwrap().len(), 7);
        assert_eq!(
            store.build_pending_history_request(&mutation).unwrap(),
            first
        );
        let mut wrong_owner = mutation.clone();
        wrong_owner.account_id = "another-account".into();
        assert!(store.build_pending_history_request(&wrong_owner).is_err());
        let mut wrong_base = mutation.clone();
        wrong_base.base_server_revision = 1;
        assert!(store.build_pending_history_request(&wrong_base).is_err());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Synthetic later edit' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        assert_eq!(
            store
                .build_pending_history_request(&mutation)
                .unwrap_err()
                .to_string(),
            "History sync request source unavailable"
        );
        store.delete(1).await.unwrap();
        assert!(store.build_pending_history_request(&mutation).is_err());
        assert_eq!(
            store
                .pending_history_revisions("synthetic-account", 10)
                .unwrap()
                .len(),
            1
        );
    }

    #[tokio::test]
    async fn pending_history_batch_preserves_order_and_rejects_invalid_members() {
        let store = temp_history_store("pending-batch");
        let mut mutations = Vec::new();
        for index in 0..2 {
            store
                .add(test_history_entry(index, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(index + 1).unwrap().unwrap();
            mutations.push(
                store
                    .enqueue_local_history_revision("synthetic-account", index + 1, &identity)
                    .unwrap(),
            );
        }
        let request = store
            .build_pending_history_batch_request(&mutations)
            .unwrap();
        assert_eq!(request["version"], 1);
        assert_eq!(request["mutations"].as_array().unwrap().len(), 2);
        let session_store = crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-token".into())),
            std::sync::atomic::AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let expected = session_store.snapshot();
        assert_eq!(
            store
                .build_pending_history_batch_for_session(&session_store, &expected, &mutations)
                .unwrap(),
            request
        );
        session_store.rotate_epoch();
        assert_eq!(
            store
                .build_pending_history_batch_for_session(&session_store, &expected, &mutations)
                .unwrap_err()
                .to_string(),
            "History sync session unavailable"
        );
        for (index, mutation) in mutations.iter().enumerate() {
            let single = store.build_pending_history_request(mutation).unwrap();
            assert_eq!(request["mutations"][index], single["mutations"][0]);
        }
        assert!(store.build_pending_history_batch_request(&[]).is_err());
        assert!(store
            .build_pending_history_batch_request(&vec![mutations[0].clone(); 201])
            .is_err());
        assert!(store
            .build_pending_history_batch_request(&[mutations[0].clone(), mutations[0].clone()])
            .is_err());
        let mut different_account = mutations.clone();
        different_account[1].account_id = "another-account".into();
        assert!(store
            .build_pending_history_batch_request(&different_account)
            .is_err());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Later edit' WHERE id = 2",
                    [],
                )
                .unwrap();
        }
        assert!(store
            .build_pending_history_batch_request(&mutations)
            .is_err());
        let connection = store.conn.lock().unwrap();
        let retained: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_pending", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(retained, 2);
        assert!(connection.is_autocommit());
    }

    #[tokio::test]
    async fn sendable_pending_selection_reaches_current_revision_after_200_stale_entries() {
        let store = temp_history_store("sendable-stale-prefix");
        store
            .add(test_history_entry(0, "2026-11-21T12:00:00"))
            .await
            .unwrap();
        let mut latest = None;
        for revision in 1..=201 {
            if revision > 1 {
                store
                    .conn
                    .lock()
                    .unwrap()
                    .execute(
                        "UPDATE history SET raw_text = ?1 WHERE id = 1",
                        [format!("Revision {revision}")],
                    )
                    .unwrap();
            }
            let identity = store.local_identity_snapshot(1).unwrap().unwrap();
            latest = Some(
                store
                    .enqueue_local_history_revision("synthetic-account", 1, &identity)
                    .unwrap(),
            );
        }
        assert_eq!(
            store
                .pending_sendable_history_revisions("synthetic-account", 1)
                .unwrap(),
            vec![latest.unwrap()]
        );
        assert_eq!(
            store.pending_history_count("synthetic-account").unwrap(),
            201
        );
        assert_eq!(store.pending_history_count("another-account").unwrap(), 0);
        assert_eq!(
            store
                .conn
                .lock()
                .unwrap()
                .query_row("SELECT COUNT(*) FROM history_sync_pending", [], |row| {
                    row.get::<_, i64>(0)
                })
                .unwrap(),
            201
        );
    }

    #[tokio::test]
    async fn sendable_pending_batch_selects_latest_live_or_explicit_tombstone_per_record() {
        let store = temp_history_store("sendable-pending-batch");
        for index in 0..2 {
            store
                .add(test_history_entry(
                    index,
                    &format!("2026-11-21T12:0{index}:00"),
                ))
                .await
                .unwrap();
        }
        let first_identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let first = store
            .enqueue_local_history_revision("synthetic-account", 1, &first_identity)
            .unwrap();
        assert!(store.confirm_pending_history_revision(&first, 1).unwrap());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("UPDATE history SET raw_text = 'edited' WHERE id = 1", [])
                .unwrap();
        }
        let edited_identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let edited = store
            .enqueue_local_history_revision("synthetic-account", 1, &edited_identity)
            .unwrap();
        let second_identity = store.local_identity_snapshot(2).unwrap().unwrap();
        let second = store
            .enqueue_local_history_revision("synthetic-account", 2, &second_identity)
            .unwrap();
        store.delete(2).await.unwrap();
        let selected = store
            .pending_sendable_history_revisions("synthetic-account", 200)
            .unwrap();
        assert_eq!(selected, vec![edited.clone()]);
        let tombstone = store
            .delete_history_with_sync_commit("synthetic-account", 1, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .unwrap()
            .unwrap();
        let selected = store
            .pending_sendable_history_revisions("synthetic-account", 1)
            .unwrap();
        assert_eq!(selected, vec![tombstone]);
        assert_ne!(second.record_uuid, edited.record_uuid);
    }

    #[tokio::test]
    async fn pending_history_batch_enforces_serialized_budget_and_accepts_200_records() {
        let store = temp_history_store("pending-batch-budget");
        let mut mutations = Vec::new();
        for index in 0..200 {
            store
                .add(test_history_entry(index, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(index + 1).unwrap().unwrap();
            mutations.push(
                store
                    .enqueue_local_history_revision("synthetic-account", index + 1, &identity)
                    .unwrap(),
            );
        }
        let request = store
            .build_pending_history_batch_request(&mutations)
            .unwrap();
        assert_eq!(request["mutations"].as_array().unwrap().len(), 200);
        let payload = serde_json::json!({
            "rawText": "中".repeat(260_000), "polishedText": "", "mode": "dictate",
            "answerText": null, "createdAt": "2026-11-19T10:00:00",
            "durationMs": null, "language": null
        })
        .to_string();
        {
            let connection = store.conn.lock().unwrap();
            for mutation in mutations.iter().take(6) {
                connection
                    .execute(
                        "UPDATE history_sync_pending SET payload_json = ?1 WHERE mutation_uuid = ?2",
                        rusqlite::params![payload, mutation.mutation_uuid],
                    )
                    .unwrap();
            }
        }
        let bounded = store
            .build_pending_history_batch_request(&mutations[..5])
            .unwrap();
        assert!(serde_json::to_vec(&bounded).unwrap().len() <= 4 * 1024 * 1024);
        assert_eq!(
            store
                .build_pending_history_batch_request(&mutations[..6])
                .unwrap_err()
                .to_string(),
            "History sync batch exceeds request limit"
        );
        let connection = store.conn.lock().unwrap();
        let retained: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_pending", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(retained, 200);
        assert!(connection.is_autocommit());
    }

    #[tokio::test]
    async fn pending_history_request_uses_saved_payload_and_rejects_unavailable_baseline() {
        let store = temp_history_store("pending-request-baseline");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let payload = serde_json::json!({
            "rawText": "Synthetic saved payload", "polishedText": "", "mode": "dictate",
            "answerText": null, "createdAt": "2026-11-19T10:00:00",
            "durationMs": null, "language": null
        });
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_sync_pending SET payload_json = ?1",
                    [payload.to_string()],
                )
                .unwrap();
        }
        let request = store.build_pending_history_request(&mutation).unwrap();
        assert_eq!(request["mutations"][0]["payload"], payload);
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("UPDATE history_sync_pending SET payload_json = NULL", [])
                .unwrap();
        }
        assert!(store.build_pending_history_request(&mutation).is_err());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_sync_pending SET payload_json = ?1",
                    [payload.to_string()],
                )
                .unwrap();
            connection
                .execute(
                    "INSERT INTO history_identity_state(id, rebuild_required) VALUES (1, 1)
                     ON CONFLICT(id) DO UPDATE SET rebuild_required = 1",
                    [],
                )
                .unwrap();
        }
        assert!(store.build_pending_history_request(&mutation).is_err());
        let connection = store.conn.lock().unwrap();
        let retained: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_pending", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(retained, 1);
        assert!(connection.is_autocommit());
    }

    #[tokio::test]
    async fn repeated_history_enqueue_rejects_corrupt_snapshot_without_replacing_it() {
        let store = temp_history_store("retry-corrupt-snapshot");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_sync_pending SET payload_json = '{invalid'",
                    [],
                )
                .unwrap();
        }
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap_err()
                .to_string(),
            "History sync payload unavailable"
        );
        let connection = store.conn.lock().unwrap();
        let retained: (String, String) = connection
            .query_row(
                "SELECT mutation_uuid, payload_json FROM history_sync_pending",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(retained, (mutation.mutation_uuid, "{invalid".into()));
        assert!(connection.is_autocommit());
    }

    #[tokio::test]
    async fn pending_history_rejects_nonstandard_uuid_forms_without_confirming() {
        let store = temp_history_store("pending-uuid-form");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let original = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        for invalid in [
            "22222222-2222-4222-0222-222222222222",
            "22222222-2222-4222-c222-222222222222",
            "22222222222242228222222222222222",
            "urn:uuid:22222222-2222-4222-8222-222222222222",
        ] {
            {
                let connection = store.conn.lock().unwrap();
                connection
                    .execute(
                        "UPDATE history_sync_pending SET mutation_uuid = ?1",
                        [invalid],
                    )
                    .unwrap();
            }
            let mut mutation = original.clone();
            mutation.mutation_uuid = invalid.into();
            assert!(store.build_pending_history_request(&mutation).is_err());
            assert!(store
                .pending_history_revisions("synthetic-account", 10)
                .is_err());
            assert!(store
                .confirm_pending_history_revision(&mutation, 1)
                .is_err());
        }
        let connection = store.conn.lock().unwrap();
        let counts: (i64, i64) = connection
            .query_row(
                "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                        (SELECT COUNT(*) FROM history_sync_confirmed)",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(counts, (1, 0));
        assert!(connection.is_autocommit());
    }

    #[tokio::test]
    async fn new_sync_page_imports_text_and_cursor_without_claiming_local_history() {
        let store = temp_history_store("new-sync-page");
        store
            .add(test_history_entry(0, "2026-11-20T00:00:00"))
            .await
            .unwrap();
        let local = store.local_identity_snapshot(1).unwrap().unwrap();
        let remote = "abcdefab-1111-4111-8111-111111111111";
        let page = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": remote, "serverRevision": 7,
                "deleted": false, "payload": {
                    "rawText": "Synthetic remote", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }]
        });
        assert_eq!(
            store
                .import_new_history_sync_page(
                    page.to_string().as_bytes(),
                    "synthetic-account",
                    0,
                    &HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            1
        );
        assert_eq!(store.local_identity_snapshot(1).unwrap().unwrap(), local);
        let imported = store.local_identity_snapshot(2).unwrap().unwrap();
        assert_eq!(imported.record_uuid, remote);
        assert_eq!(
            store.local_text_snapshot(2).unwrap().unwrap().raw_text,
            "Synthetic remote"
        );
        assert!(store
            .enqueue_local_history_revision("synthetic-account", 2, &imported)
            .is_err());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_local_identity SET record_uuid = ?1 WHERE history_id = 2",
                    [remote.to_ascii_uppercase()],
                )
                .unwrap();
        }
        let alternate = store.local_identity_snapshot(2).unwrap().unwrap();
        assert!(store
            .enqueue_local_history_revision("synthetic-account", 2, &alternate)
            .is_err());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_local_identity SET record_uuid = ?1 WHERE history_id = 2",
                    [remote],
                )
                .unwrap();
        }
        let connection = store.conn.lock().unwrap();
        let counts: (i64, i64) = connection
            .query_row(
                "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                    (SELECT COUNT(*) FROM history_sync_record_owner)",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(counts, (0, 1));
        let cursor: i64 = connection.query_row(
            "SELECT cursor FROM history_sync_pull_cursor WHERE account_id = 'synthetic-account'",
            [], |row| row.get(0),
        ).unwrap();
        assert_eq!(cursor, 1);
        drop(connection);
        let mut blocked = page.clone();
        blocked["fromCursor"] = serde_json::json!(1);
        blocked["nextCursor"] = serde_json::json!(3);
        blocked["changeSequences"] = serde_json::json!([2, 3]);
        let mut new_record = page["records"][0].clone();
        new_record["recordId"] = serde_json::json!("22222222-2222-4222-8222-222222222222");
        new_record["changeSequence"] = serde_json::json!(2);
        let mut collision = new_record.clone();
        collision["recordId"] = serde_json::json!(local.record_uuid);
        collision["changeSequence"] = serde_json::json!(3);
        blocked["records"] = serde_json::json!([new_record, collision]);
        assert!(store
            .import_new_history_sync_page(
                blocked.to_string().as_bytes(),
                "synthetic-account",
                1,
                &HistoryRetentionPolicy::default(),
            )
            .is_err());
        let connection = store.conn.lock().unwrap();
        let counts: (i64, i64) = connection
            .query_row(
                "SELECT (SELECT COUNT(*) FROM history),
                        (SELECT cursor FROM history_sync_pull_cursor WHERE account_id = 'synthetic-account')",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(counts, (2, 1));
        assert!(connection.is_autocommit());
        drop(connection);
        for policy in [
            HistoryRetentionPolicy {
                enabled: false,
                ..HistoryRetentionPolicy::default()
            },
            HistoryRetentionPolicy {
                max_entries: 1,
                ..HistoryRetentionPolicy::default()
            },
            HistoryRetentionPolicy {
                retention_days: 1,
                ..HistoryRetentionPolicy::default()
            },
        ] {
            assert!(store
                .import_new_history_sync_page(
                    blocked.to_string().as_bytes(),
                    "synthetic-account",
                    1,
                    &policy,
                )
                .is_err());
        }
        let connection = store.conn.lock().unwrap();
        let path: String = connection
            .query_row("PRAGMA database_list", [], |row| row.get(2))
            .unwrap();
        drop(connection);
        drop(store);
        let reopened = HistoryStore::new(PathBuf::from(path)).unwrap();
        assert_eq!(
            reopened
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            1
        );
        assert_eq!(
            reopened
                .history_sync_pull_cursor("another-account")
                .unwrap(),
            0
        );
        assert_eq!(reopened.local_identity_snapshot(1).unwrap().unwrap(), local);
        assert_eq!(
            reopened.local_identity_snapshot(2).unwrap().unwrap(),
            imported
        );
        let connection = reopened.conn.lock().unwrap();
        let cursor: i64 = connection.query_row(
            "SELECT cursor FROM history_sync_pull_cursor WHERE account_id = 'synthetic-account'",
            [], |row| row.get(0),
        ).unwrap();
        assert_eq!(cursor, 1);
    }

    #[tokio::test]
    async fn sync_import_refuses_case_equivalent_existing_identity_without_claiming_it() {
        let store = temp_history_store("sync-import-case-collision");
        store
            .add(test_history_entry(0, "2026-11-20T00:00:00"))
            .await
            .unwrap();
        let uppercase = "ABCDEFAB-1111-4111-8111-111111111111";
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_local_identity SET record_uuid = ?1 WHERE history_id = 1",
                    [uppercase],
                )
                .unwrap();
        }
        let page = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": uppercase.to_ascii_lowercase(),
                "serverRevision": 1, "deleted": false, "payload": {
                    "rawText": "Synthetic remote", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }]
        });
        assert!(store
            .import_new_history_sync_page(
                &serde_json::to_vec(&page).unwrap(),
                "synthetic-account",
                0,
                &HistoryRetentionPolicy::default(),
            )
            .is_err());
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        assert_eq!(identity.record_uuid, uppercase);
        assert!(store.local_identity_snapshot(2).unwrap().is_none());
        let connection = store.conn.lock().unwrap();
        let owners: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM history_sync_record_owner",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(owners, 0);
        assert!(connection.is_autocommit());
    }

    #[tokio::test]
    async fn unknown_remote_tombstone_advances_cursor_without_deleting_local_history() {
        let store = temp_history_store("unknown-remote-tombstone");
        store
            .add(test_history_entry(0, "2026-11-20T00:00:00"))
            .await
            .unwrap();
        let local = store.local_identity_snapshot(1).unwrap().unwrap();
        let record = "44444444-4444-4444-8444-444444444444";
        let page = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": record, "serverRevision": 7,
                "deleted": true, "payload": null
            }]
        });
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&page).unwrap(),
                    "synthetic-account",
                    0,
                    &HistoryRetentionPolicy {
                        max_entries: 1,
                        ..HistoryRetentionPolicy::default()
                    },
                )
                .unwrap(),
            1
        );
        assert_eq!(store.local_identity_snapshot(1).unwrap().unwrap(), local);
        assert!(store.local_identity_snapshot(2).unwrap().is_none());
        assert_eq!(
            store.history_sync_pull_cursor("synthetic-account").unwrap(),
            1
        );
        let connection = store.conn.lock().unwrap();
        let tombstone: (String, i64) = connection
            .query_row(
                "SELECT account_id, server_revision FROM history_sync_remote_tombstone WHERE record_uuid = ?1",
                [record],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(tombstone, ("synthetic-account".into(), 7));
        let path: String = connection
            .query_row("PRAGMA database_list", [], |row| row.get(2))
            .unwrap();
        drop(connection);
        drop(store);
        let store = HistoryStore::new(PathBuf::from(path)).unwrap();
        let mut revival = page;
        revival["fromCursor"] = serde_json::json!(1);
        revival["nextCursor"] = serde_json::json!(2);
        revival["changeSequences"] = serde_json::json!([2]);
        revival["records"][0]["changeSequence"] = serde_json::json!(2);
        revival["records"][0]["serverRevision"] = serde_json::json!(8);
        revival["records"][0]["deleted"] = serde_json::json!(false);
        revival["records"][0]["payload"] = serde_json::json!({
            "rawText": "Synthetic stale revival", "polishedText": "", "mode": "dictate",
            "answerText": null, "createdAt": "2026-11-20T00:00:00",
            "durationMs": null, "language": null
        });
        assert!(store
            .import_new_history_sync_page(
                &serde_json::to_vec(&revival).unwrap(),
                "synthetic-account",
                1,
                &HistoryRetentionPolicy::default(),
            )
            .is_err());
        assert_eq!(
            store.history_sync_pull_cursor("synthetic-account").unwrap(),
            1
        );
        let mut newer_delete = revival.clone();
        newer_delete["records"][0]["deleted"] = serde_json::json!(true);
        newer_delete["records"][0]["payload"] = serde_json::Value::Null;
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&newer_delete).unwrap(),
                    "synthetic-account",
                    1,
                    &HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            2
        );
        let mut stale_delete = newer_delete.clone();
        stale_delete["fromCursor"] = serde_json::json!(2);
        stale_delete["nextCursor"] = serde_json::json!(3);
        stale_delete["changeSequences"] = serde_json::json!([3]);
        stale_delete["records"][0]["changeSequence"] = serde_json::json!(3);
        for revision in [7, 8] {
            stale_delete["records"][0]["serverRevision"] = serde_json::json!(revision);
            assert!(store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&stale_delete).unwrap(),
                    "synthetic-account",
                    2,
                    &HistoryRetentionPolicy::default(),
                )
                .is_err());
        }
        let mut foreign = newer_delete;
        foreign["accountId"] = serde_json::json!("another-account");
        foreign["fromCursor"] = serde_json::json!(0);
        foreign["nextCursor"] = serde_json::json!(1);
        foreign["changeSequences"] = serde_json::json!([1]);
        foreign["records"][0]["changeSequence"] = serde_json::json!(1);
        foreign["records"][0]["serverRevision"] = serde_json::json!(9);
        assert!(store
            .import_new_history_sync_page(
                &serde_json::to_vec(&foreign).unwrap(),
                "another-account",
                0,
                &HistoryRetentionPolicy::default(),
            )
            .is_err());
        assert_eq!(
            store.history_sync_pull_cursor("synthetic-account").unwrap(),
            2
        );
        assert_eq!(
            store.history_sync_pull_cursor("another-account").unwrap(),
            0
        );
        assert_eq!(store.local_identity_snapshot(1).unwrap().unwrap(), local);
        let connection = store.conn.lock().unwrap();
        let version: i64 = connection
            .query_row(
                "SELECT server_revision FROM history_sync_remote_tombstone WHERE record_uuid = ?1",
                [record],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(version, 8);
        assert!(connection.is_autocommit());
        connection
            .execute_batch(
                "WITH RECURSIVE identities(number) AS (
                    SELECT 1 UNION ALL SELECT number + 1 FROM identities WHERE number < 4999
                 )
                 INSERT INTO history_sync_remote_tombstone(record_uuid, account_id, server_revision)
                 SELECT printf('55555555-5555-4555-8555-%012d', number), 'synthetic-account', 1
                 FROM identities;",
            )
            .unwrap();
        drop(connection);
        let mut capacity_page = stale_delete.clone();
        capacity_page["records"][0]["serverRevision"] = serde_json::json!(9);
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&capacity_page).unwrap(),
                    "synthetic-account",
                    2,
                    &HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            3
        );
        capacity_page["fromCursor"] = serde_json::json!(3);
        capacity_page["nextCursor"] = serde_json::json!(5);
        capacity_page["changeSequences"] = serde_json::json!([4, 5]);
        capacity_page["records"][0]["changeSequence"] = serde_json::json!(4);
        capacity_page["records"][0]["serverRevision"] = serde_json::json!(10);
        let mut unknown = capacity_page["records"][0].clone();
        unknown["recordId"] = serde_json::json!("66666666-6666-4666-8666-666666666666");
        unknown["changeSequence"] = serde_json::json!(5);
        capacity_page["records"]
            .as_array_mut()
            .unwrap()
            .push(unknown);
        assert!(store
            .import_new_history_sync_page(
                &serde_json::to_vec(&capacity_page).unwrap(),
                "synthetic-account",
                3,
                &HistoryRetentionPolicy::default(),
            )
            .is_err());
        let connection = store.conn.lock().unwrap();
        let retained: (i64, i64, i64) = connection
            .query_row(
                "SELECT (SELECT COUNT(*) FROM history_sync_remote_tombstone),
                        (SELECT server_revision FROM history_sync_remote_tombstone WHERE record_uuid = ?1),
                        (SELECT cursor FROM history_sync_pull_cursor WHERE account_id = 'synthetic-account')",
                [record],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(retained, (5000, 9, 3));
        assert!(connection.is_autocommit());
    }

    #[test]
    fn remote_delete_removes_only_unchanged_imported_text_and_keeps_tombstone() {
        let store = temp_history_store("remote-delete-imported");
        let record = "99999999-9999-4999-8999-999999999999";
        let page = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": record, "serverRevision": 1,
                "deleted": false, "payload": {
                    "rawText": "Synthetic imported text", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }]
        });
        store
            .import_new_history_sync_page(
                &serde_json::to_vec(&page).unwrap(),
                "synthetic-account",
                0,
                &HistoryRetentionPolicy::default(),
            )
            .unwrap();
        let deletion = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 1,
            "nextCursor": 2, "hasMore": false, "changeSequences": [2],
            "records": [{
                "changeSequence": 2, "recordId": record, "serverRevision": 2,
                "deleted": true, "payload": null
            }]
        });
        let original = store.local_identity_snapshot(1).unwrap().unwrap();
        for protection in [
            "audio",
            "citations",
            "pending",
            "local-edit",
            "delete-failure",
            "baseline-cleanup-failure",
            "ignored-tombstone",
        ] {
            {
                let connection = store.conn.lock().unwrap();
                match protection {
                    "audio" => {
                        connection
                            .execute(
                                "INSERT INTO history_audio(history_id, wav) VALUES (1, ?1)",
                                [b"synthetic audio".as_slice()],
                            )
                            .unwrap();
                    }
                    "local-edit" => {
                        connection
                            .execute(
                                "UPDATE history SET raw_text = 'Local edit' WHERE id = 1",
                                [],
                            )
                            .unwrap();
                    }
                    "citations" => {
                        connection
                            .execute(
                                "INSERT INTO history_citations(history_id, sources) VALUES (1, ?1)",
                                [r#"[{"url":"https://example.invalid/","title":"Synthetic source"}]"#],
                            )
                            .unwrap();
                    }
                    "pending" => {
                        connection
                            .execute(
                                "INSERT INTO history_sync_pending
                                 (account_id, record_uuid, mutation_uuid, client_revision,
                                  base_server_revision, payload_json)
                                 VALUES ('synthetic-account', ?1, ?2, 1, 1, ?3)",
                                rusqlite::params![
                                    record,
                                    uuid::Uuid::new_v4().to_string(),
                                    page["records"][0]["payload"].to_string()
                                ],
                            )
                            .unwrap();
                    }
                    "baseline-cleanup-failure" => {
                        connection
                            .execute_batch(
                                "CREATE TRIGGER reject_synthetic_baseline_delete
                                 BEFORE DELETE ON history_sync_remote_baseline
                                 BEGIN SELECT RAISE(ABORT, 'Synthetic baseline cleanup failure'); END;",
                            )
                            .unwrap();
                    }
                    "ignored-tombstone" => {
                        connection
                            .execute_batch(
                                "CREATE TRIGGER ignore_synthetic_tombstone
                                 BEFORE INSERT ON history_sync_remote_tombstone
                                 BEGIN SELECT RAISE(IGNORE); END;",
                            )
                            .unwrap();
                    }
                    _ => {
                        connection
                            .execute_batch(
                                "CREATE TRIGGER reject_synthetic_history_delete
                             BEFORE DELETE ON history
                             BEGIN SELECT RAISE(ABORT, 'Synthetic delete failure'); END;",
                            )
                            .unwrap();
                    }
                }
            }
            assert!(store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&deletion).unwrap(),
                    "synthetic-account",
                    1,
                    &HistoryRetentionPolicy::default(),
                )
                .is_err());
            assert_eq!(
                store.history_sync_pull_cursor("synthetic-account").unwrap(),
                1
            );
            let connection = store.conn.lock().unwrap();
            let state: (i64, i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history),
                        (SELECT COUNT(*) FROM history_sync_remote_baseline),
                        (SELECT COUNT(*) FROM history_sync_remote_tombstone)",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .unwrap();
            assert_eq!(state, (1, 1, 0));
            assert!(connection.is_autocommit());
            let retained_identity: (String, i64) = connection
                .query_row(
                    "SELECT record_uuid, local_revision FROM history_local_identity WHERE history_id = 1",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(retained_identity.0, original.record_uuid);
            let owner: String = connection
                .query_row(
                    "SELECT account_id FROM history_sync_record_owner WHERE record_uuid = ?1",
                    [record],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(owner, "synthetic-account");
            if protection != "local-edit" {
                assert_eq!(retained_identity.1, original.local_revision);
                let text: String = connection
                    .query_row("SELECT raw_text FROM history WHERE id = 1", [], |row| {
                        row.get(0)
                    })
                    .unwrap();
                assert_eq!(text, "Synthetic imported text");
            }
            match protection {
                "audio" => {
                    let audio: Vec<u8> = connection
                        .query_row("SELECT wav FROM history_audio", [], |row| row.get(0))
                        .unwrap();
                    assert_eq!(audio, b"synthetic audio");
                    connection.execute("DELETE FROM history_audio", []).unwrap();
                }
                "local-edit" => {
                    let text: String = connection
                        .query_row("SELECT raw_text FROM history WHERE id = 1", [], |row| {
                            row.get(0)
                        })
                        .unwrap();
                    assert_eq!(text, "Local edit");
                    connection
                        .execute(
                            "UPDATE history SET raw_text = 'Synthetic imported text' WHERE id = 1",
                            [],
                        )
                        .unwrap();
                    connection.execute("UPDATE history_local_identity SET local_revision = ?1 WHERE history_id = 1", [original.local_revision]).unwrap();
                }
                "citations" => {
                    let sources: String = connection
                        .query_row("SELECT sources FROM history_citations", [], |row| {
                            row.get(0)
                        })
                        .unwrap();
                    assert!(sources.contains("Synthetic source"));
                    connection
                        .execute("DELETE FROM history_citations", [])
                        .unwrap();
                }
                "pending" => {
                    let payload: String = connection
                        .query_row("SELECT payload_json FROM history_sync_pending", [], |row| {
                            row.get(0)
                        })
                        .unwrap();
                    assert_eq!(payload, page["records"][0]["payload"].to_string());
                    connection
                        .execute("DELETE FROM history_sync_pending", [])
                        .unwrap();
                }
                "baseline-cleanup-failure" => {
                    connection
                        .execute_batch("DROP TRIGGER reject_synthetic_baseline_delete")
                        .unwrap();
                }
                "ignored-tombstone" => {
                    connection
                        .execute_batch("DROP TRIGGER ignore_synthetic_tombstone")
                        .unwrap();
                }
                _ => {
                    connection
                        .execute_batch("DROP TRIGGER reject_synthetic_history_delete")
                        .unwrap();
                }
            }
        }
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_local_identity SET local_revision = 9007199254740991 WHERE history_id = 1",
                    [],
                )
                .unwrap();
            connection
                .execute(
                    "UPDATE history_sync_remote_baseline SET local_revision = 9007199254740991",
                    [],
                )
                .unwrap();
        }
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&deletion).unwrap(),
                    "synthetic-account",
                    1,
                    &HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            2
        );
        assert!(store.local_identity_snapshot(1).unwrap().is_none());
        let connection = store.conn.lock().unwrap();
        let state: (i64, i64, i64, i64) = connection
            .query_row(
                "SELECT (SELECT COUNT(*) FROM history),
                        (SELECT COUNT(*) FROM history_sync_remote_baseline),
                        (SELECT server_revision FROM history_sync_remote_tombstone WHERE record_uuid = ?1),
                        (SELECT COUNT(*) FROM history_sync_record_owner WHERE record_uuid = ?1)",
                [record],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .unwrap();
        assert_eq!(state, (0, 0, 2, 0));
        assert!(connection.is_autocommit());
    }

    #[tokio::test]
    async fn acknowledgement_batch_confirms_accepted_and_preserves_conflicts() {
        let store = temp_history_store("acknowledgement-batch");
        let mut sent = Vec::new();
        for index in 0..2 {
            store
                .add(test_history_entry(index, "2026-11-20T00:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(index + 1).unwrap().unwrap();
            sent.push(
                store
                    .enqueue_local_history_revision("synthetic-account", index + 1, &identity)
                    .unwrap(),
            );
        }
        let session = crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-token".into())),
            std::sync::atomic::AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let response = serde_json::json!({
            "version": 1, "accountId": "synthetic-account",
            "acknowledgements": sent.iter().enumerate().map(|(index, mutation)| {
                serde_json::json!({
                    "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                    "clientRevision": mutation.client_revision,
                    "result": if index == 0 { "accepted" } else { "conflict" },
                    "serverRevision": 1
                })
            }).collect::<Vec<_>>()
        });
        assert!(store
            .apply_history_acknowledgements_with_gate(
                &serde_json::to_vec(&response).unwrap(),
                "synthetic-account",
                &sent,
                (&session, &session.snapshot()),
                || anyhow::bail!("Synthetic mixed acknowledgement commit rejected"),
            )
            .is_err());
        {
            let connection = store.conn.lock().unwrap();
            let counts: (i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                            (SELECT COUNT(*) FROM history_sync_confirmed)",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(counts, (2, 0));
            let conflict_table_exists: bool = connection
                .query_row(
                    "SELECT EXISTS(SELECT 1 FROM sqlite_master
                     WHERE type = 'table' AND name = 'history_sync_conflict')",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert!(!conflict_table_exists);
            assert!(connection.is_autocommit());
        }
        let mut failing = response.clone();
        failing["acknowledgements"][1]["result"] = serde_json::json!("accepted");
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "CREATE TRIGGER reject_second_synthetic_confirmation
                     BEFORE INSERT ON history_sync_confirmed WHEN NEW.record_uuid = (
                       SELECT record_uuid FROM history_local_identity WHERE history_id = 2
                     )
                     BEGIN SELECT RAISE(ABORT, 'Synthetic second confirmation failure'); END;",
                )
                .unwrap();
        }
        assert!(store
            .apply_history_acknowledgements(
                &serde_json::to_vec(&failing).unwrap(),
                "synthetic-account",
                &sent,
                (&session, &session.snapshot()),
            )
            .is_err());
        {
            let connection = store.conn.lock().unwrap();
            let counts: (i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                            (SELECT COUNT(*) FROM history_sync_confirmed)",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(counts, (2, 0));
            assert!(connection.is_autocommit());
            connection
                .execute_batch("DROP TRIGGER reject_second_synthetic_confirmation")
                .unwrap();
        }
        assert!(store
            .apply_history_acknowledgements_with_gate(
                &serde_json::to_vec(&failing).unwrap(),
                "synthetic-account",
                &sent,
                (&session, &session.snapshot()),
                || anyhow::bail!("Synthetic expired discovery"),
            )
            .is_err());
        {
            let connection = store.conn.lock().unwrap();
            let counts: (i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                            (SELECT COUNT(*) FROM history_sync_confirmed)",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(counts, (2, 0));
            assert!(connection.is_autocommit());
        }
        assert_eq!(
            store
                .apply_history_acknowledgements(
                    &serde_json::to_vec(&response).unwrap(),
                    "synthetic-account",
                    &sent,
                    (&session, &session.snapshot()),
                )
                .unwrap(),
            1
        );
        let pending = store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].mutation, sent[1]);
        assert!(store
            .apply_history_acknowledgements_with_gate(
                &serde_json::to_vec(&response).unwrap(),
                "synthetic-account",
                &sent,
                (&session, &session.snapshot()),
                || anyhow::bail!("Synthetic authorization revoked"),
            )
            .is_err());
        let connection = store.conn.lock().unwrap();
        let conflict: (String, String, i64, String) = connection
            .query_row(
                "SELECT record_uuid, mutation_uuid, server_revision, result
                 FROM history_sync_conflict WHERE account_id = ?1",
                ["synthetic-account"],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
            )
            .unwrap();
        assert_eq!(
            conflict,
            (
                sent[1].record_uuid.clone(),
                sent[1].mutation_uuid.clone(),
                1,
                "conflict".into()
            )
        );
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_conflict", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 1);
        drop(connection);
        assert!(store
            .has_history_sync_conflict("synthetic-account", &sent[1].record_uuid)
            .unwrap());
        assert!(!store
            .has_history_sync_conflict("synthetic-account", &sent[0].record_uuid)
            .unwrap());
        assert!(!store
            .has_history_sync_conflict("another-account", &sent[1].record_uuid)
            .unwrap());
        let conflicts = store
            .list_history_sync_conflicts("synthetic-account")
            .unwrap();
        assert_eq!(conflicts.len(), 1);
        assert_eq!(conflicts[0].record_id, sent[1].record_uuid);
        assert_eq!(conflicts[0].mutation_id, sent[1].mutation_uuid);
        assert_eq!(conflicts[0].server_revision, 1);
        let local = store
            .read_history_sync_conflict_local("synthetic-account", &conflicts[0])
            .unwrap();
        assert!(!local.deleted);
        assert_eq!(local.record_id, sent[1].record_uuid);
        assert_eq!(local.client_revision, sent[1].client_revision);
        assert_eq!(
            local.payload,
            store.build_pending_history_request(&sent[1]).unwrap()["mutations"][0]["payload"]
        );
        assert!(store
            .read_history_sync_conflict_local("another-account", &conflicts[0])
            .is_err());
        assert!(store
            .list_history_sync_conflicts("another-account")
            .unwrap()
            .is_empty());
        assert_eq!(
            store
                .history_sync_conflict_count("synthetic-account")
                .unwrap(),
            1
        );
        assert_eq!(
            store
                .history_sync_conflict_count("another-account")
                .unwrap(),
            0
        );
        let repeated = serde_json::json!({
            "version": 1, "accountId": "synthetic-account",
            "acknowledgements": [{
                "recordId": sent[1].record_uuid,
                "mutationId": sent[1].mutation_uuid,
                "clientRevision": sent[1].client_revision,
                "result": "conflict",
                "serverRevision": 1
            }]
        });
        assert_eq!(
            store
                .apply_history_acknowledgements(
                    &serde_json::to_vec(&repeated).unwrap(),
                    "synthetic-account",
                    &sent[1..],
                    (&session, &session.snapshot()),
                )
                .unwrap(),
            0
        );
        assert!(store.confirm_pending_history_revision(&sent[1], 2).unwrap());
        let connection = store.conn.lock().unwrap();
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_conflict", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 0);
        drop(connection);
        assert_eq!(
            store
                .history_sync_conflict_count("synthetic-account")
                .unwrap(),
            0
        );
    }

    #[test]
    fn conflict_pages_cover_more_than_two_hundred_records_without_duplicates() {
        let store = temp_history_store("conflict-pages");
        let session = synthetic_device_session();
        store
            .apply_history_acknowledgements(
                br#"{"version":1,"accountId":"synthetic-account","acknowledgements":[]}"#,
                "synthetic-account",
                &[],
                (&session, &session.snapshot()),
            )
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            for index in 1..=201 {
                let record = format!("11111111-1111-4111-8111-{index:012}");
                let mutation = format!("22222222-2222-4222-8222-{index:012}");
                connection
                    .execute(
                        "INSERT INTO history_sync_record_owner VALUES (?1, 'synthetic-account')",
                        [&record],
                    )
                    .unwrap();
                connection.execute(
                    "INSERT INTO history_sync_pending
                     (account_id, record_uuid, mutation_uuid, client_revision, base_server_revision, payload_json, deleted)
                     VALUES ('synthetic-account', ?1, ?2, 1, 0, NULL, 1)",
                    rusqlite::params![record, mutation],
                ).unwrap();
                connection
                    .execute(
                        "INSERT INTO history_sync_conflict
                     VALUES ('synthetic-account', ?1, ?2, 1, 2, 'conflict')",
                        rusqlite::params![record, mutation],
                    )
                    .unwrap();
            }
        }
        let first = store
            .list_history_sync_conflict_page("synthetic-account", None)
            .unwrap();
        assert_eq!(first.len(), 200);
        let second = store
            .list_history_sync_conflict_page(
                "synthetic-account",
                Some(&first.last().unwrap().record_id),
            )
            .unwrap();
        assert_eq!(second.len(), 1);
        assert!(second[0].record_id > first.last().unwrap().record_id);
        assert_eq!(
            store
                .list_history_sync_conflicts("synthetic-account")
                .unwrap()
                .len(),
            201
        );
        assert!(store
            .has_history_sync_conflict("synthetic-account", &second[0].record_id)
            .unwrap());
        assert!(store
            .list_history_sync_conflict_page("another-account", None)
            .unwrap()
            .is_empty());
        assert!(store
            .list_history_sync_conflict_page("synthetic-account", Some("invalid"))
            .is_err());
    }

    #[tokio::test]
    async fn keep_local_conflict_rebases_only_the_approved_mutation_and_accepts_its_ack() {
        let store = temp_history_store("keep-local-conflict");
        store
            .add(test_history_entry(0, "2026-11-22T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let session = synthetic_device_session();
        let response = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "acknowledgements": [{
                "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                "clientRevision": mutation.client_revision, "result": "conflict", "serverRevision": 3
            }]
        });
        store
            .apply_history_acknowledgements(
                &serde_json::to_vec(&response).unwrap(),
                "synthetic-account",
                std::slice::from_ref(&mutation),
                (&session, &session.snapshot()),
            )
            .unwrap();
        let summary = store
            .list_history_sync_conflicts("synthetic-account")
            .unwrap()
            .remove(0);
        let before = store.build_pending_history_request(&mutation).unwrap();
        assert!(store
            .keep_local_history_sync_conflict("synthetic-account", &summary, 3, |_transaction| {
                anyhow::bail!("Synthetic resolution commit rejected")
            })
            .is_err());
        assert_eq!(
            store.build_pending_history_request(&mutation).unwrap(),
            before
        );
        assert!(store
            .has_history_sync_conflict("synthetic-account", &summary.record_id)
            .unwrap());
        assert!(store
            .keep_local_history_sync_conflict("other-account", &summary, 3, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .is_err());
        let rebased = store
            .keep_local_history_sync_conflict("synthetic-account", &summary, 3, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .unwrap();
        assert_ne!(rebased.mutation_uuid, mutation.mutation_uuid);
        assert_eq!(rebased.client_revision, mutation.client_revision);
        assert_eq!(rebased.base_server_revision, 3);
        assert_eq!(
            store.build_pending_history_request(&rebased).unwrap()["mutations"][0]["payload"],
            before["mutations"][0]["payload"]
        );
        assert!(store.build_pending_history_request(&mutation).is_err());
        assert_eq!(
            store
                .history_sync_conflict_count("synthetic-account")
                .unwrap(),
            0
        );
        let renewed_conflict = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "acknowledgements": [{
                "recordId": rebased.record_uuid, "mutationId": rebased.mutation_uuid,
                "clientRevision": rebased.client_revision, "result": "conflict",
                "serverRevision": 4
            }]
        });
        assert_eq!(
            store
                .apply_history_acknowledgements(
                    &serde_json::to_vec(&renewed_conflict).unwrap(),
                    "synthetic-account",
                    std::slice::from_ref(&rebased),
                    (&session, &session.snapshot()),
                )
                .unwrap(),
            0
        );
        let renewed = store
            .list_history_sync_conflicts("synthetic-account")
            .unwrap()
            .remove(0);
        assert_eq!(renewed.server_revision, 4);
        assert_eq!(renewed.mutation_id, rebased.mutation_uuid);
        let approved_again = store
            .keep_local_history_sync_conflict("synthetic-account", &renewed, 4, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .unwrap();
        assert_ne!(approved_again.mutation_uuid, rebased.mutation_uuid);
        assert_eq!(approved_again.base_server_revision, 4);
        assert!(store
            .confirm_pending_history_revision(&approved_again, 5)
            .unwrap());
        assert!(store
            .pending_history_revisions("synthetic-account", 200)
            .unwrap()
            .is_empty());
        assert!(store
            .keep_local_history_sync_conflict("synthetic-account", &summary, 3, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .is_err());
    }

    #[tokio::test]
    async fn keep_remote_conflict_replaces_only_the_confirmed_version_transactionally() {
        let store = temp_history_store("keep-remote-conflict");
        store
            .add(test_history_entry(0, "2026-11-23T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let session = synthetic_device_session();
        let response = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "acknowledgements": [{
                "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                "clientRevision": mutation.client_revision, "result": "conflict", "serverRevision": 3
            }]
        });
        store
            .apply_history_acknowledgements(
                &serde_json::to_vec(&response).unwrap(),
                "synthetic-account",
                std::slice::from_ref(&mutation),
                (&session, &session.snapshot()),
            )
            .unwrap();
        let summary = store
            .list_history_sync_conflicts("synthetic-account")
            .unwrap()
            .remove(0);
        let remote = history_sync_record::RemoteRecord {
            record_id: summary.record_id.clone(),
            server_revision: 3,
            deleted: false,
            payload: serde_json::json!({
                "rawText": "Cloud version", "polishedText": "", "mode": "dictate",
                "answerText": null, "createdAt": "2026-11-23T10:00:00",
                "durationMs": null, "language": null
            }),
        };
        let before = store.local_text_snapshot(1).unwrap().unwrap();
        let stale_remote = history_sync_record::RemoteRecord {
            record_id: remote.record_id.clone(),
            server_revision: 2,
            deleted: remote.deleted,
            payload: remote.payload.clone(),
        };
        assert!(store
            .keep_remote_history_sync_conflict(
                "synthetic-account",
                &summary,
                &stale_remote,
                |transaction| {
                    transaction.commit()?;
                    Ok(())
                },
            )
            .is_err());
        assert!(store
            .keep_remote_history_sync_conflict(
                "synthetic-account",
                &summary,
                &remote,
                |_transaction| anyhow::bail!("Synthetic rollback"),
            )
            .is_err());
        assert_eq!(
            store.local_text_snapshot(1).unwrap().unwrap().raw_text,
            before.raw_text
        );
        assert_eq!(
            store
                .history_sync_conflict_count("synthetic-account")
                .unwrap(),
            1
        );
        assert!(store
            .keep_remote_history_sync_conflict("other-account", &summary, &remote, |transaction| {
                transaction.commit()?;
                Ok(())
            },)
            .is_err());
        store
            .keep_remote_history_sync_conflict(
                "synthetic-account",
                &summary,
                &remote,
                |transaction| {
                    transaction.commit()?;
                    Ok(())
                },
            )
            .unwrap();
        assert_eq!(
            store.local_text_snapshot(1).unwrap().unwrap().raw_text,
            "Cloud version"
        );
        assert_eq!(
            store
                .history_sync_conflict_count("synthetic-account")
                .unwrap(),
            0
        );
        assert_eq!(store.pending_history_count("synthetic-account").unwrap(), 0);
        assert!(store
            .keep_remote_history_sync_conflict(
                "synthetic-account",
                &summary,
                &remote,
                |transaction| {
                    transaction.commit()?;
                    Ok(())
                },
            )
            .is_err());
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let connection = store.conn.lock().unwrap();
        connection
            .execute(
                "UPDATE history SET raw_text = 'New local edit' WHERE id = 1",
                [],
            )
            .unwrap();
        drop(connection);
        let edited = store.local_identity_snapshot(1).unwrap().unwrap();
        assert!(edited.local_revision > identity.local_revision);
        let pending = store
            .enqueue_local_history_revision("synthetic-account", 1, &edited)
            .unwrap();
        assert_eq!(pending.base_server_revision, 3);
    }

    #[tokio::test]
    async fn keep_remote_deleted_record_removes_only_the_approved_local_take() {
        let store = temp_history_store("keep-remote-delete");
        store
            .add(test_history_entry(0, "2026-11-23T10:00:00"))
            .await
            .unwrap();
        store
            .add(test_history_entry(0, "2026-11-23T11:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let session = synthetic_device_session();
        let response = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "acknowledgements": [{
                "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                "clientRevision": mutation.client_revision, "result": "conflict", "serverRevision": 3
            }]
        });
        store
            .apply_history_acknowledgements(
                &serde_json::to_vec(&response).unwrap(),
                "synthetic-account",
                std::slice::from_ref(&mutation),
                (&session, &session.snapshot()),
            )
            .unwrap();
        let summary = store
            .list_history_sync_conflicts("synthetic-account")
            .unwrap()
            .remove(0);
        let remote = history_sync_record::RemoteRecord {
            record_id: summary.record_id.clone(),
            server_revision: 3,
            deleted: true,
            payload: serde_json::Value::Null,
        };
        store
            .keep_remote_history_sync_conflict(
                "synthetic-account",
                &summary,
                &remote,
                |transaction| {
                    transaction.commit()?;
                    Ok(())
                },
            )
            .unwrap();
        assert!(store.local_identity_snapshot(1).unwrap().is_none());
        assert!(store.local_identity_snapshot(2).unwrap().is_some());
        assert_eq!(
            store
                .history_sync_conflict_count("synthetic-account")
                .unwrap(),
            0
        );
        assert_eq!(
            store
                .pending_actionable_history_count("synthetic-account")
                .unwrap(),
            0
        );
    }

    #[tokio::test]
    async fn keep_local_conflict_preserves_existing_baselines_and_local_deletions() {
        for deleted in [false, true] {
            let store = temp_history_store("keep-local-existing-baseline");
            store
                .add(test_history_entry(0, "2026-11-22T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(1).unwrap().unwrap();
            let original = store
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap();
            assert!(store
                .confirm_pending_history_revision(&original, 1)
                .unwrap());
            let pending = if deleted {
                store
                    .delete_history_with_sync_commit("synthetic-account", 1, |transaction| {
                        transaction.commit()?;
                        Ok(())
                    })
                    .unwrap()
                    .unwrap()
            } else {
                {
                    let connection = store.conn.lock().unwrap();
                    connection
                        .execute(
                            "UPDATE history SET raw_text = 'Synthetic edited text' WHERE id = 1",
                            [],
                        )
                        .unwrap();
                }
                let edited = store.local_identity_snapshot(1).unwrap().unwrap();
                store
                    .enqueue_local_history_revision("synthetic-account", 1, &edited)
                    .unwrap()
            };
            let session = synthetic_device_session();
            let response = serde_json::json!({
                "version": 1, "accountId": "synthetic-account", "acknowledgements": [{
                    "recordId": pending.record_uuid, "mutationId": pending.mutation_uuid,
                    "clientRevision": pending.client_revision, "result": "conflict",
                    "serverRevision": 3
                }]
            });
            store
                .apply_history_acknowledgements(
                    &serde_json::to_vec(&response).unwrap(),
                    "synthetic-account",
                    std::slice::from_ref(&pending),
                    (&session, &session.snapshot()),
                )
                .unwrap();
            let summary = store
                .list_history_sync_conflicts("synthetic-account")
                .unwrap()
                .remove(0);
            let resolved = store
                .keep_local_history_sync_conflict("synthetic-account", &summary, 3, |transaction| {
                    transaction.commit()?;
                    Ok(())
                })
                .unwrap();
            let request = store.build_pending_history_request(&resolved).unwrap();
            assert_eq!(request["mutations"][0]["deleted"], deleted);
            assert!(store
                .confirm_pending_history_revision(&resolved, 4)
                .unwrap());
            assert_eq!(store.get(1).await.unwrap().is_none(), deleted);
            assert!(store
                .pending_history_revisions("synthetic-account", 200)
                .unwrap()
                .is_empty());
        }
    }

    #[tokio::test]
    async fn deletion_never_discards_an_unconfirmed_conflict_resolution() {
        let store = temp_history_store("delete-pending-resolution");
        store
            .add(test_history_entry(0, "2026-11-22T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let session = synthetic_device_session();
        let response = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "acknowledgements": [{
                "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                "clientRevision": mutation.client_revision, "result": "conflict",
                "serverRevision": 3
            }]
        });
        store
            .apply_history_acknowledgements(
                &serde_json::to_vec(&response).unwrap(),
                "synthetic-account",
                std::slice::from_ref(&mutation),
                (&session, &session.snapshot()),
            )
            .unwrap();
        let summary = store
            .list_history_sync_conflicts("synthetic-account")
            .unwrap()
            .remove(0);
        let resolved = store
            .keep_local_history_sync_conflict("synthetic-account", &summary, 3, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .unwrap();
        let before = store.build_pending_history_request(&resolved).unwrap();
        assert!(store
            .delete_history_with_sync_commit("synthetic-account", 1, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .is_err());
        assert!(store.get(1).await.unwrap().is_some());
        assert_eq!(
            store.build_pending_history_request(&resolved).unwrap(),
            before
        );
        assert!(store
            .clear_history_with_sync_commit("synthetic-account", |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .is_err());
        assert!(store.get(1).await.unwrap().is_some());
        assert!(store
            .confirm_pending_history_revision(&resolved, 4)
            .unwrap());
        let deletion = store
            .delete_history_with_sync_commit("synthetic-account", 1, |transaction| {
                transaction.commit()?;
                Ok(())
            })
            .unwrap()
            .unwrap();
        assert_eq!(deletion.base_server_revision, 4);
        assert!(store
            .confirm_pending_history_revision(&deletion, 5)
            .unwrap());
        assert!(store.get(1).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn history_confirmation_session_rejects_old_owner_and_preserves_queue() {
        let store = temp_history_store("confirmation-session");
        store
            .add(test_history_entry(0, "2026-11-20T00:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let session = crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-token".into())),
            std::sync::atomic::AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let expected = session.snapshot();
        session.rotate_epoch();
        assert!(store
            .confirm_history_revision_for_session(&mutation, 1, (&session, &expected))
            .is_err());
        assert_eq!(
            store
                .pending_history_revisions("synthetic-account", 10)
                .unwrap()
                .len(),
            1
        );
        let current = session.snapshot();
        {
            let mut connection = store.conn.lock().unwrap();
            let result = history_sync_queue::confirm_with_commit(
                &mut connection,
                &mutation,
                1,
                |transaction| {
                    session.rotate_epoch();
                    crate::history_sync_session::commit_with_current_session(
                        &session,
                        &current,
                        || {
                            transaction.commit()?;
                            Ok(())
                        },
                    )
                },
            );
            assert!(result.is_err());
            let state: (i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                            (SELECT COUNT(*) FROM history_sync_confirmed)",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(state, (1, 0));
            assert!(connection.is_autocommit());
        }
        assert!(store
            .confirm_history_revision_for_session(&mutation, 1, (&session, &session.snapshot()))
            .unwrap());
        assert!(store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn confirmed_local_record_can_receive_its_own_pull_echo_without_duplicate_history() {
        let store = temp_history_store("local-confirm-echo");
        store
            .add(test_history_entry(0, "2026-11-20T00:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let request = store.build_pending_history_request(&mutation).unwrap();
        assert!(store
            .confirm_pending_history_revision(&mutation, 1)
            .unwrap());
        let echo = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": mutation.record_uuid, "serverRevision": 1,
                "deleted": false, "payload": request["mutations"][0]["payload"]
            }]
        });
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&echo).unwrap(),
                    "synthetic-account",
                    0,
                    &HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            1
        );
        assert_eq!(store.local_identity_snapshot(1).unwrap().unwrap(), identity);
        assert!(store.local_identity_snapshot(2).unwrap().is_none());
        let connection = store.conn.lock().unwrap();
        let baseline: (i64, i64) = connection
            .query_row(
                "SELECT local_revision, server_revision FROM history_sync_remote_baseline",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(baseline, (identity.local_revision, 1));
    }

    #[tokio::test]
    async fn sqlite_ack_commit_blocks_synthetic_logout_and_rejects_old_session_afterward() {
        use std::sync::{mpsc, Arc};
        use std::time::Duration;

        let store = Arc::new(temp_history_store("sqlite-ack-logout"));
        let session = Arc::new(synthetic_device_session());
        let expected = session.snapshot();
        let mut sent = Vec::new();
        for index in 0..2 {
            store
                .add(test_history_entry(index, "2026-11-20T00:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(index + 1).unwrap().unwrap();
            sent.push(
                store
                    .enqueue_local_history_revision("synthetic-account", index + 1, &identity)
                    .unwrap(),
            );
        }
        let acknowledgement = |mutation: &history_sync_queue::PendingHistoryMutation| {
            serde_json::to_vec(&serde_json::json!({
                "version": 1, "accountId": "synthetic-account",
                "acknowledgements": [{
                    "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                    "clientRevision": mutation.client_revision,
                    "result": "accepted", "serverRevision": 1
                }]
            }))
            .unwrap()
        };
        let bytes = acknowledgement(&sent[0]);
        let (entered_sender, entered_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let committing_store = store.clone();
        let committing_session = session.clone();
        let committing_expected = expected.clone();
        let committing_mutation = sent[0].clone();
        let committing = std::thread::spawn(move || {
            committing_store.apply_history_acknowledgements_with_gate(
                &bytes,
                "synthetic-account",
                &[committing_mutation],
                (&committing_session, &committing_expected),
                || {
                    entered_sender
                        .send(())
                        .map_err(|_| anyhow::anyhow!("Synthetic commit observer unavailable"))?;
                    release_receiver
                        .recv_timeout(Duration::from_secs(5))
                        .map_err(|_| anyhow::anyhow!("Synthetic commit release unavailable"))?;
                    Ok(())
                },
            )
        });
        entered_receiver
            .recv_timeout(Duration::from_secs(5))
            .unwrap();
        let (blocked_sender, blocked_receiver) = mpsc::channel();
        let (finished_sender, finished_receiver) = mpsc::channel();
        let logging_out_session = session.clone();
        let logging_out = std::thread::spawn(move || {
            blocked_sender
                .send(matches!(
                    logging_out_session.0.try_lock(),
                    Err(std::sync::TryLockError::WouldBlock)
                ))
                .unwrap();
            let mut token = logging_out_session.0.lock().unwrap();
            token.clear();
            logging_out_session.rotate_epoch();
            finished_sender.send(()).unwrap();
        });
        let blocked = blocked_receiver.recv_timeout(Duration::from_secs(5));
        let premature_completion = finished_receiver.try_recv();
        release_sender.send(()).unwrap();
        let committed = committing.join().unwrap();
        logging_out.join().unwrap();
        assert!(blocked.unwrap());
        assert!(matches!(
            premature_completion,
            Err(mpsc::TryRecvError::Empty)
        ));
        assert_eq!(committed.unwrap(), 1);
        finished_receiver
            .recv_timeout(Duration::from_secs(5))
            .unwrap();
        let cleared = session.snapshot();
        assert!(cleared.token.is_none());
        assert_ne!(cleared.session_epoch, expected.session_epoch);
        assert!(store
            .apply_history_acknowledgements(
                &acknowledgement(&sent[1]),
                "synthetic-account",
                std::slice::from_ref(&sent[1]),
                (&session, &expected),
            )
            .is_err());
        let pending = store
            .pending_history_revisions("synthetic-account", 200)
            .unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].mutation, sent[1]);
        let connection = store.conn.lock().unwrap();
        assert!(connection.is_autocommit());
        let confirmed: Vec<String> = connection
            .prepare("SELECT mutation_uuid FROM history_sync_confirmed")
            .unwrap()
            .query_map([], |row| row.get(0))
            .unwrap()
            .collect::<rusqlite::Result<_>>()
            .unwrap();
        assert_eq!(confirmed, vec![sent[0].mutation_uuid.clone()]);
    }

    #[tokio::test]
    async fn retained_server_responses_confirm_echo_import_and_delete_in_native_sqlite() {
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../tests/fixtures/history_sync_server.json"
        ))
        .unwrap();
        let account = fixture["accountId"].as_str().unwrap();
        let record_uuid = fixture["mutation"]["recordId"].as_str().unwrap();
        let mutation_uuid = fixture["mutation"]["mutationId"].as_str().unwrap();
        let device_a = temp_history_store("retained-server-device-a");
        let device_b = temp_history_store("retained-server-device-b");
        let session_a = synthetic_device_session();
        let session_b = synthetic_device_session();
        let mut entry = test_history_entry(0, "2026-11-19T10:00:00");
        entry.raw_text = "Synthetic text".into();
        entry.polished_text.clear();
        device_a.add(entry).await.unwrap();
        device_a
            .conn
            .lock()
            .unwrap()
            .execute(
                "UPDATE history_local_identity SET record_uuid = ?1 WHERE history_id = 1",
                [record_uuid],
            )
            .unwrap();
        let identity = device_a.local_identity_snapshot(1).unwrap().unwrap();
        let queued = device_a
            .enqueue_local_history_revision(account, 1, &identity)
            .unwrap();
        device_a
            .conn
            .lock()
            .unwrap()
            .execute(
                "UPDATE history_sync_pending SET mutation_uuid = ?1 WHERE record_uuid = ?2",
                rusqlite::params![mutation_uuid, record_uuid],
            )
            .unwrap();
        let mutation = history_sync_queue::PendingHistoryMutation {
            mutation_uuid: mutation_uuid.into(),
            ..queued
        };
        let request = device_a
            .build_pending_history_batch_for_session(
                &session_a,
                &session_a.snapshot(),
                std::slice::from_ref(&mutation),
            )
            .unwrap();
        assert_eq!(request["mutations"][0], fixture["mutation"]);
        assert_eq!(
            device_a
                .apply_history_acknowledgements(
                    &serde_json::to_vec(&fixture["acknowledgement"]).unwrap(),
                    account,
                    std::slice::from_ref(&mutation),
                    (&session_a, &session_a.snapshot()),
                )
                .unwrap(),
            1
        );
        assert!(device_a
            .pending_history_revisions(account, 200)
            .unwrap()
            .is_empty());
        for (device, session) in [(&device_a, &session_a), (&device_b, &session_b)] {
            assert_eq!(
                device
                    .apply_history_sync_page_for_session(
                        &serde_json::to_vec(&fixture["page"]).unwrap(),
                        account,
                        0,
                        &HistoryRetentionPolicy::default(),
                        (session, &session.snapshot()),
                    )
                    .unwrap(),
                1
            );
            assert_eq!(device.list(10, 0).await.unwrap().len(), 1);
            assert_eq!(
                device
                    .local_identity_snapshot(1)
                    .unwrap()
                    .unwrap()
                    .record_uuid,
                record_uuid
            );
            assert_eq!(
                device.local_text_snapshot(1).unwrap().unwrap().raw_text,
                "Synthetic text"
            );
            assert_eq!(
                device
                    .apply_history_sync_page_for_session(
                        &serde_json::to_vec(&fixture["tombstone"]).unwrap(),
                        account,
                        1,
                        &HistoryRetentionPolicy::default(),
                        (session, &session.snapshot()),
                    )
                    .unwrap(),
                2
            );
            assert!(device.list(10, 0).await.unwrap().is_empty());
            assert_eq!(device.history_sync_pull_cursor(account).unwrap(), 2);
        }
    }

    #[tokio::test]
    async fn consent_revoked_after_ack_writes_rolls_back_receipts_baselines_and_pending() {
        let history = temp_history_store("ack-consent-mid-transaction");
        let session = synthetic_device_session();
        let snapshot = session.snapshot();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let authorization = consent
            .grant("synthetic-account", &snapshot.session_epoch, &endpoint)
            .unwrap();
        let mut sent = Vec::new();
        for index in 0..2 {
            history
                .add(test_history_entry(index, "2026-11-20T00:00:00"))
                .await
                .unwrap();
            let identity = history.local_identity_snapshot(index + 1).unwrap().unwrap();
            sent.push(
                history
                    .enqueue_local_history_revision("synthetic-account", index + 1, &identity)
                    .unwrap(),
            );
        }
        let bytes = serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "synthetic-account",
            "acknowledgements": sent.iter().map(|mutation| serde_json::json!({
                "recordId": mutation.record_uuid,
                "mutationId": mutation.mutation_uuid,
                "clientRevision": mutation.client_revision,
                "result": "accepted", "serverRevision": 1
            })).collect::<Vec<_>>()
        }))
        .unwrap();
        let outcome = history.apply_history_acknowledgements_with_commit(
            &bytes,
            "synthetic-account",
            &sent,
            (&session, &snapshot),
            |transaction| {
                let state: (i64, i64, i64) = transaction.query_row(
                    "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                            (SELECT COUNT(*) FROM history_sync_confirmed),
                            (SELECT COUNT(*) FROM history_sync_remote_baseline)",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )?;
                assert_eq!(state, (0, 2, 2));
                std::thread::scope(|scope| scope.spawn(|| consent.revoke()).join().unwrap())?;
                consent.with_current(
                    &authorization,
                    "synthetic-account",
                    &snapshot.session_epoch,
                    &endpoint,
                    || {
                        transaction.commit()?;
                        Ok(())
                    },
                )
            },
        );
        assert!(outcome.is_err());
        let pending = history
            .pending_history_revisions("synthetic-account", 200)
            .unwrap();
        assert_eq!(pending.len(), 2);
        for mutation in &sent {
            assert!(pending.iter().any(|entry| &entry.mutation == mutation));
            assert!(history.build_pending_history_request(mutation).is_ok());
        }
        let connection = history.conn.lock().unwrap();
        assert!(connection.is_autocommit());
        let state: (i64, i64, i64) = connection
            .query_row(
                "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                        (SELECT COUNT(*) FROM history_sync_confirmed),
                        (SELECT COUNT(*) FROM sqlite_master
                         WHERE type = 'table' AND name = 'history_sync_remote_baseline')",
                [],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(state, (2, 0, 0));
    }

    #[test]
    fn consent_revoked_after_page_writes_rolls_back_history_ownership_baseline_and_cursor() {
        let history = temp_history_store("page-consent-mid-transaction");
        let session = synthetic_device_session();
        let snapshot = session.snapshot();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let authorization = consent
            .grant("synthetic-account", &snapshot.session_epoch, &endpoint)
            .unwrap();
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../tests/fixtures/history_sync_server.json"
        ))
        .unwrap();
        let page = history_sync_page::parse_page(
            &serde_json::to_vec(&fixture["page"]).unwrap(),
            "synthetic-account",
            0,
        )
        .unwrap();
        let outcome = history.apply_validated_sync_page_with_commit(
            &page,
            &HistoryRetentionPolicy::default(),
            (&session, &snapshot),
            |transaction| {
                let state: (i64, i64, i64, i64) = transaction.query_row(
                    "SELECT (SELECT COUNT(*) FROM history),
                            (SELECT COUNT(*) FROM history_sync_record_owner),
                            (SELECT COUNT(*) FROM history_sync_remote_baseline),
                            (SELECT cursor FROM history_sync_pull_cursor WHERE account_id = 'synthetic-account')",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
                )?;
                assert_eq!(state, (1, 1, 1, 1));
                std::thread::scope(|scope| scope.spawn(|| consent.revoke()).join().unwrap())?;
                consent.with_current(
                    &authorization,
                    "synthetic-account",
                    &snapshot.session_epoch,
                    &endpoint,
                    || {
                        transaction.commit()?;
                        Ok(())
                    },
                )
            },
        );
        assert!(outcome.is_err());
        assert!(history.local_identity_snapshot(1).unwrap().is_none());
        let connection = history.conn.lock().unwrap();
        assert!(connection.is_autocommit());
        let history_count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history", [], |row| row.get(0))
            .unwrap();
        assert_eq!(history_count, 0);
        let owner_count: i64 = connection
            .query_row(
                "SELECT COUNT(*) FROM history_sync_record_owner",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(owner_count, 0);
        for table in [
            "history_sync_remote_baseline",
            "history_sync_pull_cursor",
            "history_sync_remote_tombstone",
        ] {
            let count: i64 = connection
                .query_row(
                    "SELECT COUNT(*) FROM sqlite_master WHERE type = 'table' AND name = ?1",
                    [table],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(count, 0, "{table}");
        }
        drop(connection);
        assert_eq!(
            history
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            0
        );
    }

    #[tokio::test]
    async fn actual_ack_commit_holds_consent_until_commit_then_rejects_old_authorization() {
        use std::sync::{mpsc, Arc};
        use std::time::Duration;

        let history = Arc::new(temp_history_store("ack-consent-held-commit"));
        let session = Arc::new(synthetic_device_session());
        let snapshot = session.snapshot();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let consent = Arc::new(crate::history_sync_consent::ConsentStore::default());
        let authorization = consent
            .grant("synthetic-account", &snapshot.session_epoch, &endpoint)
            .unwrap();
        let mut sent = Vec::new();
        for index in 0..2 {
            history
                .add(test_history_entry(index, "2026-11-20T00:00:00"))
                .await
                .unwrap();
            let identity = history.local_identity_snapshot(index + 1).unwrap().unwrap();
            sent.push(
                history
                    .enqueue_local_history_revision("synthetic-account", index + 1, &identity)
                    .unwrap(),
            );
        }
        let acknowledge = |mutation: &history_sync_queue::PendingHistoryMutation| {
            serde_json::to_vec(&serde_json::json!({
                "version": 1, "accountId": "synthetic-account",
                "acknowledgements": [{
                    "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                    "clientRevision": mutation.client_revision,
                    "result": "accepted", "serverRevision": 1
                }]
            }))
            .unwrap()
        };
        let bytes = acknowledge(&sent[0]);
        let (entered_sender, entered_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let worker_history = history.clone();
        let worker_session = session.clone();
        let worker_consent = consent.clone();
        let worker_snapshot = snapshot.clone();
        let worker_authorization = authorization.clone();
        let worker_endpoint = endpoint.clone();
        let mutation = sent[0].clone();
        let committing = std::thread::spawn(move || {
            worker_history.apply_history_acknowledgements_with_commit(
                &bytes,
                "synthetic-account",
                &[mutation],
                (&worker_session, &worker_snapshot),
                |transaction| {
                    worker_consent.with_current(
                        &worker_authorization,
                        "synthetic-account",
                        &worker_snapshot.session_epoch,
                        &worker_endpoint,
                        || {
                            let receipts: i64 = transaction.query_row(
                                "SELECT COUNT(*) FROM history_sync_confirmed",
                                [],
                                |row| row.get(0),
                            )?;
                            assert_eq!(receipts, 1);
                            entered_sender.send(()).unwrap();
                            release_receiver
                                .recv_timeout(Duration::from_secs(3))
                                .map_err(|_| anyhow::anyhow!("Synthetic release unavailable"))?;
                            transaction.commit()?;
                            Ok(())
                        },
                    )
                },
            )
        });
        entered_receiver
            .recv_timeout(Duration::from_secs(3))
            .unwrap();
        let (started_sender, started_receiver) = mpsc::channel();
        let (finished_sender, finished_receiver) = mpsc::channel();
        let revoking_consent = consent.clone();
        let revoking = std::thread::spawn(move || {
            started_sender.send(()).unwrap();
            let result = revoking_consent.revoke();
            finished_sender.send(()).unwrap();
            result
        });
        started_receiver
            .recv_timeout(Duration::from_secs(3))
            .unwrap();
        let premature = finished_receiver.recv_timeout(Duration::from_millis(30));
        release_sender.send(()).unwrap();
        let committed = committing.join().unwrap();
        revoking.join().unwrap().unwrap();
        assert!(matches!(premature, Err(mpsc::RecvTimeoutError::Timeout)));
        assert_eq!(committed.unwrap(), 1);
        assert!(history
            .apply_history_acknowledgements_with_commit(
                &acknowledge(&sent[1]),
                "synthetic-account",
                std::slice::from_ref(&sent[1]),
                (&session, &snapshot),
                |transaction| consent.with_current(
                    &authorization,
                    "synthetic-account",
                    &snapshot.session_epoch,
                    &endpoint,
                    || {
                        transaction.commit()?;
                        Ok(())
                    },
                ),
            )
            .is_err());
        let pending = history
            .pending_history_revisions("synthetic-account", 200)
            .unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].mutation, sent[1]);
        let connection = history.conn.lock().unwrap();
        assert!(connection.is_autocommit());
        let receipts: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_confirmed", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(receipts, 1);
    }

    #[test]
    fn actual_page_commit_holds_consent_and_revocation_prevents_the_next_page() {
        use std::sync::{mpsc, Arc};
        use std::time::Duration;

        let history = Arc::new(temp_history_store("page-held-consent"));
        let session = Arc::new(synthetic_device_session());
        let snapshot = session.snapshot();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let consent = Arc::new(crate::history_sync_consent::ConsentStore::default());
        let authorization = consent
            .grant("synthetic-account", &snapshot.session_epoch, &endpoint)
            .unwrap();
        let fixture: serde_json::Value = serde_json::from_str(include_str!(
            "../../tests/fixtures/history_sync_server.json"
        ))
        .unwrap();
        let page = history_sync_page::parse_page(
            &serde_json::to_vec(&fixture["page"]).unwrap(),
            "synthetic-account",
            0,
        )
        .unwrap();
        let (entered_sender, entered_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let worker_history = history.clone();
        let worker_session = session.clone();
        let worker_consent = consent.clone();
        let worker_snapshot = snapshot.clone();
        let worker_authorization = authorization.clone();
        let worker_endpoint = endpoint.clone();
        let committing = std::thread::spawn(move || {
            worker_history.apply_validated_sync_page_with_commit(
                &page,
                &HistoryRetentionPolicy::default(),
                (&worker_session, &worker_snapshot),
                |transaction| {
                    worker_consent.with_current(
                        &worker_authorization,
                        "synthetic-account",
                        &worker_snapshot.session_epoch,
                        &worker_endpoint,
                        || {
                            let cursor: i64 = transaction.query_row(
                                "SELECT cursor FROM history_sync_pull_cursor",
                                [],
                                |row| row.get(0),
                            )?;
                            assert_eq!(cursor, 1);
                            entered_sender.send(()).unwrap();
                            release_receiver
                                .recv_timeout(Duration::from_secs(3))
                                .map_err(|_| anyhow::anyhow!("Synthetic release unavailable"))?;
                            transaction.commit()?;
                            Ok(())
                        },
                    )
                },
            )
        });
        entered_receiver
            .recv_timeout(Duration::from_secs(3))
            .unwrap();
        let (observed_sender, observed_receiver) = mpsc::channel();
        let revoking_consent = consent.clone();
        let revoking = std::thread::spawn(move || {
            observed_sender
                .send(revoking_consent.lock_is_held_for_test())
                .unwrap();
            revoking_consent.revoke()
        });
        let held = observed_receiver.recv_timeout(Duration::from_secs(3));
        release_sender.send(()).unwrap();
        let committed = committing.join().unwrap();
        revoking.join().unwrap().unwrap();
        assert!(held.unwrap());
        assert_eq!(committed.unwrap(), 1);
        let tombstone = history_sync_page::parse_page(
            &serde_json::to_vec(&fixture["tombstone"]).unwrap(),
            "synthetic-account",
            1,
        )
        .unwrap();
        assert!(history
            .apply_validated_sync_page_with_commit(
                &tombstone,
                &HistoryRetentionPolicy::default(),
                (&session, &snapshot),
                |transaction| consent.with_current(
                    &authorization,
                    "synthetic-account",
                    &snapshot.session_epoch,
                    &endpoint,
                    || {
                        transaction.commit()?;
                        Ok(())
                    },
                ),
            )
            .is_err());
        assert_eq!(
            history
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            1
        );
        assert_eq!(
            history.local_text_snapshot(1).unwrap().unwrap().raw_text,
            "Synthetic text"
        );
        assert!(history.conn.lock().unwrap().is_autocommit());
    }

    fn synthetic_device_session() -> crate::SessionTokenStore {
        crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-offline-token".into())),
            std::sync::atomic::AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        )
    }

    fn synthetic_device_page(
        record_uuid: &str,
        cursor: i64,
        server_revision: i64,
        payload: Option<serde_json::Value>,
    ) -> Vec<u8> {
        serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": cursor,
            "nextCursor": cursor + 1, "hasMore": false, "changeSequences": [cursor + 1],
            "records": [{
                "changeSequence": cursor + 1, "recordId": record_uuid,
                "serverRevision": server_revision, "deleted": payload.is_none(),
                "payload": payload
            }]
        }))
        .unwrap()
    }

    fn synthetic_device_confirm(
        store: &HistoryStore,
        session: &crate::SessionTokenStore,
        mutation: &history_sync_queue::PendingHistoryMutation,
        server_revision: i64,
    ) {
        let acknowledgement = serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "synthetic-account",
            "acknowledgements": [{
                "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                "clientRevision": mutation.client_revision,
                "result": "accepted", "serverRevision": server_revision
            }]
        }))
        .unwrap();
        assert_eq!(
            store
                .apply_history_acknowledgements(
                    &acknowledgement,
                    "synthetic-account",
                    std::slice::from_ref(mutation),
                    (session, &session.snapshot()),
                )
                .unwrap(),
            1
        );
        assert!(store
            .pending_history_revisions("synthetic-account", 200)
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn synthetic_two_device_roundtrip_preserves_identity_across_restart_and_remote_delete() {
        let device_a = temp_history_store("synthetic-device-a");
        let device_b = temp_history_store("synthetic-device-b");
        let session_a = synthetic_device_session();
        let session_b = synthetic_device_session();
        let policy = HistoryRetentionPolicy::default();
        device_a
            .add(test_history_entry(0, "2026-11-20T00:00:00"))
            .await
            .unwrap();
        let original_identity = device_a.local_identity_snapshot(1).unwrap().unwrap();
        let original = device_a
            .enqueue_local_history_revision("synthetic-account", 1, &original_identity)
            .unwrap();
        assert_eq!(original.base_server_revision, 0);
        let request = device_a
            .build_pending_history_batch_for_session(
                &session_a,
                &session_a.snapshot(),
                std::slice::from_ref(&original),
            )
            .unwrap();
        synthetic_device_confirm(&device_a, &session_a, &original, 1);
        let page = synthetic_device_page(
            &original.record_uuid,
            0,
            1,
            Some(request["mutations"][0]["payload"].clone()),
        );
        for (device, session) in [(&device_a, &session_a), (&device_b, &session_b)] {
            assert_eq!(
                device
                    .apply_history_sync_page_for_session(
                        &page,
                        "synthetic-account",
                        0,
                        &policy,
                        (session, &session.snapshot()),
                    )
                    .unwrap(),
                1
            );
            assert_eq!(device.list(10, 0).await.unwrap().len(), 1);
            assert_eq!(
                device
                    .local_identity_snapshot(1)
                    .unwrap()
                    .unwrap()
                    .record_uuid,
                original.record_uuid
            );
        }
        device_b
            .conn
            .lock()
            .unwrap()
            .execute(
                "UPDATE history SET raw_text = 'Synthetic edit on B' WHERE id = 1",
                [],
            )
            .unwrap();
        let edited_identity = device_b.local_identity_snapshot(1).unwrap().unwrap();
        let edited = device_b
            .enqueue_local_history_revision("synthetic-account", 1, &edited_identity)
            .unwrap();
        assert_eq!(edited.base_server_revision, 1);
        let edited_request = device_b
            .build_pending_history_batch_for_session(
                &session_b,
                &session_b.snapshot(),
                std::slice::from_ref(&edited),
            )
            .unwrap();
        assert_eq!(
            edited_request["mutations"][0]["payload"]["rawText"],
            "Synthetic edit on B"
        );
        let path_b = device_b.conn.lock().unwrap().path().unwrap().to_string();
        drop(device_b);
        let device_b = HistoryStore::new(PathBuf::from(&path_b)).unwrap();
        let session_b = synthetic_device_session();
        assert_eq!(
            device_b.local_identity_snapshot(1).unwrap().unwrap(),
            edited_identity
        );
        assert_eq!(
            device_b
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            1
        );
        assert_eq!(
            device_b
                .pending_history_revisions("synthetic-account", 200)
                .unwrap(),
            vec![history_sync_queue::PendingHistoryQueueEntry {
                mutation: edited.clone(),
                source: history_sync_queue::PendingHistorySource::Current { history_id: 1 },
                deleted: false,
            }]
        );
        assert_eq!(
            device_b
                .build_pending_history_batch_for_session(
                    &session_b,
                    &session_b.snapshot(),
                    std::slice::from_ref(&edited),
                )
                .unwrap(),
            edited_request
        );
        synthetic_device_confirm(&device_b, &session_b, &edited, 2);
        let page = synthetic_device_page(
            &edited.record_uuid,
            1,
            2,
            Some(edited_request["mutations"][0]["payload"].clone()),
        );
        for (device, session) in [(&device_a, &session_a), (&device_b, &session_b)] {
            assert_eq!(
                device
                    .apply_history_sync_page_for_session(
                        &page,
                        "synthetic-account",
                        1,
                        &policy,
                        (session, &session.snapshot())
                    )
                    .unwrap(),
                2
            );
            assert_eq!(device.list(10, 0).await.unwrap().len(), 1);
            assert_eq!(
                device.local_text_snapshot(1).unwrap().unwrap().raw_text,
                "Synthetic edit on B"
            );
            assert_eq!(
                device
                    .local_identity_snapshot(1)
                    .unwrap()
                    .unwrap()
                    .record_uuid,
                original.record_uuid
            );
        }
        assert_eq!(
            device_b.local_identity_snapshot(1).unwrap().unwrap(),
            edited_identity
        );
        let tombstone = synthetic_device_page(&original.record_uuid, 2, 3, None);
        for (device, session) in [(&device_a, &session_a), (&device_b, &session_b)] {
            assert_eq!(
                device
                    .apply_history_sync_page_for_session(
                        &tombstone,
                        "synthetic-account",
                        2,
                        &policy,
                        (session, &session.snapshot())
                    )
                    .unwrap(),
                3
            );
            assert!(device.list(10, 0).await.unwrap().is_empty());
            assert!(device.local_identity_snapshot(1).unwrap().is_none());
        }
        drop(device_b);
        let device_b = HistoryStore::new(PathBuf::from(path_b)).unwrap();
        assert_eq!(
            device_b
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            3
        );
        assert!(device_b.list(10, 0).await.unwrap().is_empty());
        let resurrection = synthetic_device_page(
            &original.record_uuid,
            3,
            4,
            Some(request["mutations"][0]["payload"].clone()),
        );
        assert!(device_b
            .apply_history_sync_page_for_session(
                &resurrection,
                "synthetic-account",
                3,
                &policy,
                (&session_b, &session_b.snapshot())
            )
            .is_err());
        assert_eq!(
            device_b
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            3
        );
    }

    #[tokio::test]
    async fn synthetic_two_device_conflicts_preserve_local_text_queue_and_cursor() {
        for enqueue_edit in [false, true] {
            let device_a = temp_history_store("synthetic-conflict-device-a");
            let device_b = temp_history_store("synthetic-conflict-device-b");
            let session_a = synthetic_device_session();
            let session_b = synthetic_device_session();
            let policy = HistoryRetentionPolicy::default();
            device_a
                .add(test_history_entry(0, "2026-11-20T00:00:00"))
                .await
                .unwrap();
            let identity = device_a.local_identity_snapshot(1).unwrap().unwrap();
            let mutation = device_a
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap();
            let request = device_a
                .build_pending_history_batch_for_session(
                    &session_a,
                    &session_a.snapshot(),
                    std::slice::from_ref(&mutation),
                )
                .unwrap();
            synthetic_device_confirm(&device_a, &session_a, &mutation, 1);
            let initial = synthetic_device_page(
                &mutation.record_uuid,
                0,
                1,
                Some(request["mutations"][0]["payload"].clone()),
            );
            for (device, session) in [(&device_a, &session_a), (&device_b, &session_b)] {
                device
                    .apply_history_sync_page_for_session(
                        &initial,
                        "synthetic-account",
                        0,
                        &policy,
                        (session, &session.snapshot()),
                    )
                    .unwrap();
            }
            device_a
                .conn
                .lock()
                .unwrap()
                .execute(
                    "UPDATE history SET raw_text = 'Unsynced synthetic A edit' WHERE id = 1",
                    [],
                )
                .unwrap();
            let local_identity = device_a.local_identity_snapshot(1).unwrap().unwrap();
            if enqueue_edit {
                device_a
                    .enqueue_local_history_revision("synthetic-account", 1, &local_identity)
                    .unwrap();
            }
            let pending = device_a
                .pending_history_revisions("synthetic-account", 200)
                .unwrap();
            device_b
                .conn
                .lock()
                .unwrap()
                .execute(
                    "UPDATE history SET raw_text = 'Accepted synthetic B edit' WHERE id = 1",
                    [],
                )
                .unwrap();
            let remote_identity = device_b.local_identity_snapshot(1).unwrap().unwrap();
            let remote = device_b
                .enqueue_local_history_revision("synthetic-account", 1, &remote_identity)
                .unwrap();
            let remote_request = device_b
                .build_pending_history_batch_for_session(
                    &session_b,
                    &session_b.snapshot(),
                    std::slice::from_ref(&remote),
                )
                .unwrap();
            synthetic_device_confirm(&device_b, &session_b, &remote, 2);
            for payload in [
                Some(remote_request["mutations"][0]["payload"].clone()),
                None,
            ] {
                let conflicting = synthetic_device_page(&remote.record_uuid, 1, 2, payload);
                assert!(device_a
                    .apply_history_sync_page_for_session(
                        &conflicting,
                        "synthetic-account",
                        1,
                        &policy,
                        (&session_a, &session_a.snapshot())
                    )
                    .is_err());
                assert_eq!(
                    device_a
                        .history_sync_pull_cursor("synthetic-account")
                        .unwrap(),
                    1
                );
                assert_eq!(
                    device_a.local_identity_snapshot(1).unwrap().unwrap(),
                    local_identity
                );
                assert_eq!(
                    device_a.local_text_snapshot(1).unwrap().unwrap().raw_text,
                    "Unsynced synthetic A edit"
                );
                assert_eq!(
                    device_a
                        .pending_history_revisions("synthetic-account", 200)
                        .unwrap(),
                    pending
                );
                assert_eq!(device_a.list(10, 0).await.unwrap().len(), 1);
            }
        }
    }

    #[test]
    fn imported_record_edits_pin_remote_baseline_and_confirm_atomically() {
        let store = temp_history_store("imported-edit-confirm");
        let page = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": "cccccccc-cccc-4ccc-8ccc-cccccccccccc",
                "serverRevision": 7, "deleted": false, "payload": {
                    "rawText": "Imported", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }]
        });
        store
            .import_new_history_sync_page(
                &serde_json::to_vec(&page).unwrap(),
                "synthetic-account",
                0,
                &HistoryRetentionPolicy::default(),
            )
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Local edit' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        assert_eq!(mutation.base_server_revision, 7);
        let request = store.build_pending_history_request(&mutation).unwrap();
        assert_eq!(request["mutations"][0]["payload"]["rawText"], "Local edit");
        for trigger in [
            "CREATE TRIGGER ignore_synthetic_confirmation BEFORE INSERT ON history_sync_confirmed
             BEGIN SELECT RAISE(IGNORE); END;",
            "CREATE TRIGGER ignore_synthetic_confirmation BEFORE DELETE ON history_sync_pending
             BEGIN SELECT RAISE(IGNORE); END;",
        ] {
            {
                let connection = store.conn.lock().unwrap();
                connection.execute_batch(trigger).unwrap();
            }
            assert!(store
                .confirm_pending_history_revision(&mutation, 8)
                .is_err());
            let connection = store.conn.lock().unwrap();
            let state: (i64, i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                            (SELECT COUNT(*) FROM history_sync_confirmed),
                            (SELECT server_revision FROM history_sync_remote_baseline)",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .unwrap();
            assert_eq!(state, (1, 0, 7));
            assert!(connection.is_autocommit());
            connection
                .execute_batch("DROP TRIGGER ignore_synthetic_confirmation")
                .unwrap();
        }
        assert!(store
            .confirm_pending_history_revision(&mutation, 8)
            .unwrap());
        assert!(store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .is_err());
        let mut echo = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 1,
            "nextCursor": 2, "hasMore": false, "changeSequences": [2],
            "records": [{
                "changeSequence": 2, "recordId": mutation.record_uuid, "serverRevision": 8,
                "deleted": false, "payload": request["mutations"][0]["payload"]
            }]
        });
        echo["records"][0]["payload"]["rawText"] = serde_json::json!("Mismatched echo");
        assert!(store
            .import_new_history_sync_page(
                &serde_json::to_vec(&echo).unwrap(),
                "synthetic-account",
                1,
                &HistoryRetentionPolicy::default(),
            )
            .is_err());
        echo["records"][0]["payload"] = request["mutations"][0]["payload"].clone();
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&echo).unwrap(),
                    "synthetic-account",
                    1,
                    &HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            2
        );
        assert_eq!(store.local_identity_snapshot(1).unwrap().unwrap(), identity);
        {
            let connection = store.conn.lock().unwrap();
            let baseline: (i64, i64) = connection
                .query_row(
                    "SELECT local_revision, server_revision FROM history_sync_remote_baseline",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(baseline, (identity.local_revision, 8));
            connection
                .execute("UPDATE history SET raw_text = 'Next edit' WHERE id = 1", [])
                .unwrap();
        }
        let next_identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let next = store
            .enqueue_local_history_revision("synthetic-account", 1, &next_identity)
            .unwrap();
        assert_eq!(next.base_server_revision, 8);
        assert!(store.confirm_pending_history_revision(&next, 9).unwrap());
        let confirmed_identity = store.local_identity_snapshot(1).unwrap().unwrap();
        echo["fromCursor"] = serde_json::json!(2);
        echo["nextCursor"] = serde_json::json!(3);
        echo["changeSequences"] = serde_json::json!([3]);
        echo["records"][0]["changeSequence"] = serde_json::json!(3);
        echo["records"][0]["serverRevision"] = serde_json::json!(10);
        echo["records"][0]["payload"]["rawText"] = serde_json::json!("New remote version");
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&echo).unwrap(),
                    "synthetic-account",
                    2,
                    &HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            3
        );
        let updated_identity = store.local_identity_snapshot(1).unwrap().unwrap();
        assert_eq!(updated_identity.record_uuid, confirmed_identity.record_uuid);
        assert_eq!(
            updated_identity.local_revision,
            confirmed_identity.local_revision + 1
        );
        assert_eq!(
            store.local_text_snapshot(1).unwrap().unwrap().raw_text,
            "New remote version"
        );
        let connection = store.conn.lock().unwrap();
        let receipts: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_confirmed", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(receipts, 0);
    }

    #[test]
    fn imported_remote_text_updates_only_while_local_baseline_is_unchanged() {
        let store = temp_history_store("remote-text-update");
        let mut page = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": "88888888-8888-4888-8888-888888888888",
                "serverRevision": 1, "deleted": false, "payload": {
                    "rawText": "Original remote", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }]
        });
        let policy = HistoryRetentionPolicy {
            max_entries: 1,
            ..HistoryRetentionPolicy::default()
        };
        store
            .import_new_history_sync_page(
                &serde_json::to_vec(&page).unwrap(),
                "synthetic-account",
                0,
                &policy,
            )
            .unwrap();
        page["fromCursor"] = serde_json::json!(1);
        page["nextCursor"] = serde_json::json!(2);
        page["changeSequences"] = serde_json::json!([2]);
        page["records"][0]["changeSequence"] = serde_json::json!(2);
        page["records"][0]["serverRevision"] = serde_json::json!(2);
        page["records"][0]["payload"]["rawText"] = serde_json::json!("Updated remote");
        let original_identity = store.local_identity_snapshot(1).unwrap().unwrap();
        for protection in [
            "audio",
            "citations",
            "pending",
            "ignored-update",
            "baseline-failure",
        ] {
            {
                let connection = store.conn.lock().unwrap();
                match protection {
                    "audio" => {
                        connection
                            .execute(
                                "INSERT INTO history_audio(history_id, wav) VALUES (1, ?1)",
                                [b"synthetic audio".as_slice()],
                            )
                            .unwrap();
                    }
                    "citations" => {
                        connection
                            .execute(
                                "INSERT INTO history_citations(history_id, sources) VALUES (1, ?1)",
                                [r#"[{"url":"https://example.invalid/","title":"Synthetic source"}]"#],
                            )
                            .unwrap();
                    }
                    "pending" => {
                        let payload = history_sync_queue::capture_payload(&connection, 1).unwrap();
                        connection
                            .execute(
                                "INSERT INTO history_sync_pending
                                 (account_id, record_uuid, mutation_uuid, client_revision,
                                  base_server_revision, payload_json, deleted)
                                 VALUES (?1, ?2, ?3, 1, 1, ?4, 0)",
                                rusqlite::params![
                                    "synthetic-account",
                                    original_identity.record_uuid,
                                    "99999999-9999-4999-8999-999999999999",
                                    payload
                                ],
                            )
                            .unwrap();
                    }
                    "ignored-update" => {
                        connection
                            .execute_batch(
                                "CREATE TRIGGER ignore_synthetic_history_update
                                 BEFORE UPDATE ON history
                                 BEGIN SELECT RAISE(IGNORE); END;",
                            )
                            .unwrap();
                    }
                    _ => {
                        connection
                            .execute_batch(
                                "CREATE TRIGGER reject_synthetic_baseline_update
                                 BEFORE UPDATE ON history_sync_remote_baseline
                                 BEGIN SELECT RAISE(ABORT, 'Synthetic update failure'); END;",
                            )
                            .unwrap();
                    }
                }
            }
            assert!(store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&page).unwrap(),
                    "synthetic-account",
                    1,
                    &policy,
                )
                .is_err());
            assert_eq!(
                store.local_text_snapshot(1).unwrap().unwrap().raw_text,
                "Original remote"
            );
            assert_eq!(
                store.local_identity_snapshot(1).unwrap().unwrap(),
                original_identity
            );
            assert_eq!(
                store.history_sync_pull_cursor("synthetic-account").unwrap(),
                1
            );
            let connection = store.conn.lock().unwrap();
            let baseline: (i64, i64) = connection
                .query_row(
                    "SELECT local_revision, server_revision FROM history_sync_remote_baseline",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(baseline, (1, 1));
            assert!(connection.is_autocommit());
            match protection {
                "audio" => {
                    let retained: Vec<u8> = connection
                        .query_row("SELECT wav FROM history_audio", [], |row| row.get(0))
                        .unwrap();
                    assert_eq!(retained, b"synthetic audio");
                    connection.execute("DELETE FROM history_audio", []).unwrap();
                }
                "citations" => {
                    let retained: String = connection
                        .query_row("SELECT sources FROM history_citations", [], |row| {
                            row.get(0)
                        })
                        .unwrap();
                    assert!(retained.contains("Synthetic source"));
                    connection
                        .execute("DELETE FROM history_citations", [])
                        .unwrap();
                }
                "pending" => {
                    let retained: (String, String, i64, i64, String, i64) = connection
                        .query_row(
                            "SELECT account_id, mutation_uuid, client_revision,
                                    base_server_revision, payload_json, deleted
                             FROM history_sync_pending",
                            [],
                            |row| {
                                Ok((
                                    row.get(0)?,
                                    row.get(1)?,
                                    row.get(2)?,
                                    row.get(3)?,
                                    row.get(4)?,
                                    row.get(5)?,
                                ))
                            },
                        )
                        .unwrap();
                    assert_eq!(retained.0, "synthetic-account");
                    assert_eq!(retained.1, "99999999-9999-4999-8999-999999999999");
                    assert_eq!((retained.2, retained.3, retained.5), (1, 1, 0));
                    assert_eq!(
                        retained.4,
                        history_sync_queue::capture_payload(&connection, 1).unwrap()
                    );
                    connection
                        .execute("DELETE FROM history_sync_pending", [])
                        .unwrap();
                }
                "ignored-update" => {
                    connection
                        .execute_batch("DROP TRIGGER ignore_synthetic_history_update")
                        .unwrap();
                }
                _ => {
                    connection
                        .execute_batch("DROP TRIGGER reject_synthetic_baseline_update")
                        .unwrap();
                }
            }
        }
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &serde_json::to_vec(&page).unwrap(),
                    "synthetic-account",
                    1,
                    &policy
                )
                .unwrap(),
            2
        );
        assert_eq!(
            store.local_text_snapshot(1).unwrap().unwrap().raw_text,
            "Updated remote"
        );
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Local edit' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        page["fromCursor"] = serde_json::json!(2);
        page["nextCursor"] = serde_json::json!(3);
        page["changeSequences"] = serde_json::json!([3]);
        page["records"][0]["changeSequence"] = serde_json::json!(3);
        page["records"][0]["serverRevision"] = serde_json::json!(3);
        assert!(store
            .import_new_history_sync_page(
                &serde_json::to_vec(&page).unwrap(),
                "synthetic-account",
                2,
                &policy
            )
            .is_err());
        assert_eq!(
            store.local_text_snapshot(1).unwrap().unwrap().raw_text,
            "Local edit"
        );
        assert_eq!(
            store.history_sync_pull_cursor("synthetic-account").unwrap(),
            2
        );
    }

    #[test]
    fn initial_sync_page_keeps_final_version_without_counting_intermediate_history() {
        for deleted in [false, true] {
            let store = temp_history_store("sync-final-page-version");
            let record = "77777777-7777-4777-8777-777777777777";
            let first = serde_json::json!({
                "changeSequence": 1, "recordId": record, "serverRevision": 1,
                "deleted": false, "payload": {
                    "rawText": "Intermediate synthetic text", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            });
            let mut last = first.clone();
            last["changeSequence"] = serde_json::json!(2);
            last["serverRevision"] = serde_json::json!(2);
            last["deleted"] = serde_json::json!(deleted);
            if deleted {
                last["payload"] = serde_json::Value::Null;
            } else {
                last["payload"]["rawText"] = serde_json::json!("Final synthetic text");
            }
            let page = serde_json::json!({
                "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
                "nextCursor": 2, "hasMore": false, "changeSequences": [1, 2],
                "records": [first, last]
            });
            assert_eq!(
                store
                    .import_new_history_sync_page(
                        &serde_json::to_vec(&page).unwrap(),
                        "synthetic-account",
                        0,
                        &HistoryRetentionPolicy {
                            max_entries: 1,
                            ..HistoryRetentionPolicy::default()
                        },
                    )
                    .unwrap(),
                2
            );
            let connection = store.conn.lock().unwrap();
            let count: i64 = connection
                .query_row("SELECT COUNT(*) FROM history", [], |row| row.get(0))
                .unwrap();
            assert_eq!(count, i64::from(!deleted));
            let table = if deleted {
                "history_sync_remote_tombstone"
            } else {
                "history_sync_remote_baseline"
            };
            let revision: i64 = connection
                .query_row(
                    &format!("SELECT server_revision FROM {table} WHERE record_uuid = ?1"),
                    [record],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(revision, 2);
            drop(connection);
            if !deleted {
                assert_eq!(
                    store.local_text_snapshot(1).unwrap().unwrap().raw_text,
                    "Final synthetic text"
                );
            } else {
                let mut invalid_page = page.clone();
                invalid_page["fromCursor"] = serde_json::json!(2);
                invalid_page["nextCursor"] = serde_json::json!(4);
                invalid_page["changeSequences"] = serde_json::json!([3, 4]);
                invalid_page["records"][0]["changeSequence"] = serde_json::json!(3);
                invalid_page["records"][0]["serverRevision"] = serde_json::json!(3);
                invalid_page["records"][1]["changeSequence"] = serde_json::json!(4);
                invalid_page["records"][1]["serverRevision"] = serde_json::json!(4);
                assert!(store
                    .import_new_history_sync_page(
                        &serde_json::to_vec(&invalid_page).unwrap(),
                        "synthetic-account",
                        2,
                        &HistoryRetentionPolicy::default(),
                    )
                    .is_err());
                assert_eq!(
                    store.history_sync_pull_cursor("synthetic-account").unwrap(),
                    2
                );
            }
        }
    }

    #[tokio::test]
    async fn sync_pull_cursor_is_read_only_account_scoped_and_refuses_corruption() {
        let store = temp_history_store("sync-pull-cursor");
        assert_eq!(
            store.history_sync_pull_cursor("synthetic-account").unwrap(),
            0
        );
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "CREATE TABLE history_sync_pull_cursor (
                        account_id TEXT PRIMARY KEY, cursor INTEGER NOT NULL
                    );
                    INSERT INTO history_sync_pull_cursor VALUES ('synthetic-account', 7);",
                )
                .unwrap();
        }
        assert_eq!(
            store.history_sync_pull_cursor("synthetic-account").unwrap(),
            7
        );
        assert_eq!(
            store.history_sync_pull_cursor("another-account").unwrap(),
            0
        );
        for invalid in ["", " spaced ", "bad\naccount"] {
            assert!(store.history_sync_pull_cursor(invalid).is_err());
        }
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("UPDATE history_sync_pull_cursor SET cursor = -1", [])
                .unwrap();
        }
        assert!(store.history_sync_pull_cursor("synthetic-account").is_err());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("UPDATE history_sync_pull_cursor SET cursor = 7", [])
                .unwrap();
            connection
                .execute(
                    "INSERT INTO history_identity_state(id, rebuild_required) VALUES (1, 1)
                     ON CONFLICT(id) DO UPDATE SET rebuild_required = 1",
                    [],
                )
                .unwrap();
        }
        assert!(store.history_sync_pull_cursor("synthetic-account").is_err());
        let connection = store.conn.lock().unwrap();
        let cursor: i64 = connection
            .query_row("SELECT cursor FROM history_sync_pull_cursor", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(cursor, 7);
        assert!(connection.is_autocommit());
    }

    #[test]
    fn sync_page_session_rotation_before_commit_rolls_back_all_changes() {
        let store = temp_history_store("sync-session-commit");
        let session = crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-token".into())),
            std::sync::atomic::AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let expected = session.snapshot();
        let bytes = serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": "aaaaaaaa-aaaa-4aaa-8aaa-aaaaaaaaaaaa",
                "serverRevision": 1, "deleted": false, "payload": {
                    "rawText": "Synthetic", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }]
        }))
        .unwrap();
        let page = history_sync_page::parse_page(&bytes, "synthetic-account", 0).unwrap();
        {
            let mut connection = store.conn.lock().unwrap();
            let outcome = history_sync_page::import_page_with_commit(
                &mut connection,
                &page,
                5000,
                |transaction| {
                    session.rotate_epoch();
                    crate::history_sync_session::commit_with_current_session(
                        &session,
                        &expected,
                        || {
                            transaction.commit()?;
                            Ok(())
                        },
                    )
                },
            );
            assert!(outcome.is_err());
            assert!(connection.is_autocommit());
            let count: i64 = connection
                .query_row("SELECT COUNT(*) FROM history", [], |row| row.get(0))
                .unwrap();
            assert_eq!(count, 0);
        }
        assert_eq!(
            store.history_sync_pull_cursor("synthetic-account").unwrap(),
            0
        );
        assert!(store
            .apply_history_sync_page_for_session(
                &bytes,
                "synthetic-account",
                0,
                &HistoryRetentionPolicy::default(),
                (&session, &expected),
            )
            .is_err());
        assert_eq!(
            store
                .apply_history_sync_page_for_session(
                    &bytes,
                    "synthetic-account",
                    0,
                    &HistoryRetentionPolicy::default(),
                    (&session, &session.snapshot()),
                )
                .unwrap(),
            1
        );
    }

    #[tokio::test]
    async fn new_sync_import_policy_and_sql_failure_preserve_all_local_state() {
        let store = temp_history_store("sync-import-independent-gates");
        store
            .add(test_history_entry(0, "2026-11-20T00:00:00"))
            .await
            .unwrap();
        let local = store.local_identity_snapshot(1).unwrap().unwrap();
        let page = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1,
                "recordId": "33333333-3333-4333-8333-333333333333",
                "serverRevision": 1, "deleted": false, "payload": {
                    "rawText": "Synthetic remote", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }]
        });
        let bytes = serde_json::to_vec(&page).unwrap();
        for policy in [
            HistoryRetentionPolicy {
                enabled: false,
                ..HistoryRetentionPolicy::default()
            },
            HistoryRetentionPolicy {
                max_entries: 1,
                ..HistoryRetentionPolicy::default()
            },
            HistoryRetentionPolicy {
                retention_days: 1,
                ..HistoryRetentionPolicy::default()
            },
        ] {
            assert!(store
                .import_new_history_sync_page(&bytes, "synthetic-account", 0, &policy)
                .is_err());
            let connection = store.conn.lock().unwrap();
            let counts: (i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history),
                            (SELECT COUNT(*) FROM history_sync_record_owner)",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(counts, (1, 0));
            assert!(connection.is_autocommit());
        }
        let empty_page = serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 0, "hasMore": false, "changeSequences": [], "records": []
        });
        store
            .import_new_history_sync_page(
                &serde_json::to_vec(&empty_page).unwrap(),
                "synthetic-account",
                0,
                &HistoryRetentionPolicy::default(),
            )
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "CREATE TRIGGER reject_synthetic_remote_baseline
                     BEFORE INSERT ON history_sync_remote_baseline
                     BEGIN SELECT RAISE(ABORT, 'Synthetic baseline write failure'); END;",
                )
                .unwrap();
        }
        assert!(store
            .import_new_history_sync_page(
                &bytes,
                "synthetic-account",
                0,
                &HistoryRetentionPolicy::default(),
            )
            .is_err());
        assert_eq!(store.local_identity_snapshot(1).unwrap().unwrap(), local);
        assert!(store.local_identity_snapshot(2).unwrap().is_none());
        {
            let connection = store.conn.lock().unwrap();
            let counts: (i64, i64, i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history),
                            (SELECT COUNT(*) FROM history_sync_record_owner),
                            (SELECT COUNT(*) FROM history_sync_remote_baseline),
                            (SELECT cursor FROM history_sync_pull_cursor WHERE account_id = 'synthetic-account')",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
                )
                .unwrap();
            assert_eq!(counts, (1, 0, 0, 0));
            assert!(connection.is_autocommit());
            connection
                .execute_batch("DROP TRIGGER reject_synthetic_remote_baseline")
                .unwrap();
        }
        for trigger in [
            "CREATE TRIGGER ignore_synthetic_cursor BEFORE INSERT ON history
             BEGIN SELECT RAISE(IGNORE); END;",
            "CREATE TRIGGER ignore_synthetic_cursor BEFORE INSERT ON history_local_identity
             BEGIN SELECT RAISE(IGNORE); END;",
            "CREATE TRIGGER ignore_synthetic_cursor BEFORE INSERT ON history_sync_record_owner
             BEGIN SELECT RAISE(IGNORE); END;",
            "CREATE TRIGGER ignore_synthetic_cursor BEFORE INSERT ON history_sync_remote_baseline
             BEGIN SELECT RAISE(IGNORE); END;",
            "CREATE TRIGGER ignore_synthetic_cursor BEFORE INSERT ON history_sync_pull_cursor
             BEGIN SELECT RAISE(IGNORE); END;",
            "CREATE TRIGGER ignore_synthetic_cursor BEFORE UPDATE ON history_sync_pull_cursor
             BEGIN SELECT RAISE(IGNORE); END;",
            "CREATE TRIGGER ignore_synthetic_cursor AFTER UPDATE ON history_sync_pull_cursor
             BEGIN UPDATE history_sync_pull_cursor SET cursor = 0 WHERE account_id = NEW.account_id; END;",
        ] {
            {
                let connection = store.conn.lock().unwrap();
                connection.execute_batch(trigger).unwrap();
            }
            assert!(store
                .import_new_history_sync_page(
                    &bytes,
                    "synthetic-account",
                    0,
                    &HistoryRetentionPolicy::default(),
                )
                .is_err());
            let connection = store.conn.lock().unwrap();
            let counts: (i64, i64, i64, i64) = connection
                .query_row(
                    "SELECT (SELECT COUNT(*) FROM history),
                            (SELECT COUNT(*) FROM history_sync_record_owner),
                            (SELECT COUNT(*) FROM history_sync_remote_baseline),
                            (SELECT cursor FROM history_sync_pull_cursor WHERE account_id = 'synthetic-account')",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
                )
                .unwrap();
            assert_eq!(counts, (1, 0, 0, 0));
            assert!(connection.is_autocommit());
            connection
                .execute_batch("DROP TRIGGER ignore_synthetic_cursor")
                .unwrap();
        }
        assert_eq!(
            store
                .import_new_history_sync_page(
                    &bytes,
                    "synthetic-account",
                    0,
                    &HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            1
        );
    }

    #[tokio::test]
    async fn history_confirmation_rejects_invalid_payload_json_without_clearing_queue() {
        let store = temp_history_store("confirmation-invalid-payload");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let valid = serde_json::json!({
            "rawText": "Synthetic", "polishedText": "", "mode": "dictate",
            "answerText": null, "createdAt": "2026-11-19T10:00:00",
            "durationMs": null, "language": null
        });
        let mut extra = valid.clone();
        extra["apiKey"] = serde_json::json!("synthetic-private-key");
        let mut invalid_date = valid.clone();
        invalid_date["createdAt"] = serde_json::json!("2026-02-30T10:00:00");
        let mut leap_second = valid.clone();
        leap_second["createdAt"] = serde_json::json!("2026-12-31T23:59:60");
        let mut extended_year = valid.clone();
        extended_year["createdAt"] = serde_json::json!("+10000-01-01T00:00:00");
        let mut missing = valid.clone();
        missing.as_object_mut().unwrap().remove("language");
        for payload in [
            "{invalid".to_string(),
            extra.to_string(),
            invalid_date.to_string(),
            leap_second.to_string(),
            extended_year.to_string(),
            missing.to_string(),
            valid.to_string().replace(
                "\"mode\":\"dictate\"",
                "\"mode\":\"dictate\",\"mode\":\"ask\"",
            ),
        ] {
            {
                let connection = store.conn.lock().unwrap();
                connection
                    .execute(
                        "UPDATE history_sync_pending SET payload_json = ?1",
                        [&payload],
                    )
                    .unwrap();
            }
            let error = store
                .confirm_pending_history_revision(&mutation, 1)
                .unwrap_err();
            assert_eq!(error.to_string(), "History sync payload unavailable");
            assert!(!error.to_string().contains("synthetic-private-key"));
            let request_error = store.build_pending_history_request(&mutation).unwrap_err();
            assert_eq!(
                request_error.to_string(),
                "History sync payload unavailable"
            );
            assert!(!request_error.to_string().contains("synthetic-private-key"));
        }
        let connection = store.conn.lock().unwrap();
        let counts: (i64, i64) = connection
            .query_row(
                "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                        (SELECT COUNT(*) FROM history_sync_confirmed)",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(counts, (1, 0));
    }

    #[tokio::test]
    async fn history_confirmation_does_not_accept_a_missing_queued_payload() {
        let store = temp_history_store("confirmation-missing-payload");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("UPDATE history_sync_pending SET payload_json = NULL", [])
                .unwrap();
        }
        assert!(!store
            .confirm_pending_history_revision(&mutation, 1)
            .unwrap());
        let connection = store.conn.lock().unwrap();
        let counts: (i64, i64) = connection
            .query_row(
                "SELECT (SELECT COUNT(*) FROM history_sync_pending),
                        (SELECT COUNT(*) FROM history_sync_confirmed)",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(counts, (1, 0));
    }

    #[tokio::test]
    async fn accepted_latest_revision_removes_only_its_superseded_pending_rows() {
        let store = temp_history_store("ack-superseded-pending");
        for index in 0..2 {
            store
                .add(test_history_entry(index, "2026-11-21T12:00:00"))
                .await
                .unwrap();
        }
        let original = store.local_identity_snapshot(1).unwrap().unwrap();
        let old = store
            .enqueue_local_history_revision("account-a", 1, &original)
            .unwrap();
        let other = store.local_identity_snapshot(2).unwrap().unwrap();
        let retained = store
            .enqueue_local_history_revision("account-b", 2, &other)
            .unwrap();
        store
            .conn
            .lock()
            .unwrap()
            .execute(
                "UPDATE history SET raw_text = 'Updated text' WHERE id = 1",
                [],
            )
            .unwrap();
        let current = store.local_identity_snapshot(1).unwrap().unwrap();
        let latest = store
            .enqueue_local_history_revision("account-a", 1, &current)
            .unwrap();
        assert!(!store.confirm_pending_history_revision(&old, 1).unwrap());
        assert_eq!(store.pending_history_count("account-a").unwrap(), 2);
        assert!(store.confirm_pending_history_revision(&latest, 1).unwrap());
        assert_eq!(store.pending_history_count("account-a").unwrap(), 0);
        assert_eq!(
            store.pending_history_revisions("account-b", 10).unwrap()[0].mutation,
            retained
        );
        assert_eq!(
            store.get(1).await.unwrap().unwrap().raw_text,
            "Updated text"
        );
    }

    #[tokio::test]
    async fn accepted_history_revision_is_confirmed_and_removed_atomically() {
        let store = temp_history_store("history-queue-confirm");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        assert!(store
            .confirm_pending_history_revision(&mutation, 1)
            .unwrap());
        assert!(store
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
        assert!(!store
            .confirm_pending_history_revision(&mutation, 1)
            .unwrap());
        let confirmed: (String, i64, i64) = store
            .conn
            .lock()
            .unwrap()
            .query_row(
                "SELECT mutation_uuid, client_revision, server_revision FROM history_sync_confirmed",
                [],
                |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
            )
            .unwrap();
        assert_eq!(confirmed, (mutation.mutation_uuid, 1, 1));
        assert!(store.get(1).await.unwrap().is_some());
    }

    #[tokio::test]
    async fn pending_history_queue_rejects_current_revision_older_than_submitted_revision() {
        let store = temp_history_store("queue-revision-regression");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Synthetic revision two' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        assert_eq!(identity.local_revision, 2);
        let queued = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_local_identity SET local_revision = 1 WHERE history_id = 1",
                    [],
                )
                .unwrap();
        }
        assert_eq!(
            store
                .pending_history_revisions("synthetic-account", 10)
                .unwrap_err()
                .to_string(),
            "Invalid history sync queue metadata"
        );
        let connection = store.conn.lock().unwrap();
        let mutation: String = connection
            .query_row(
                "SELECT mutation_uuid FROM history_sync_pending",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(mutation, queued.mutation_uuid);
    }

    #[tokio::test]
    async fn pending_history_queue_rejects_corrupted_current_revision_without_modifying_it() {
        let store = temp_history_store("invalid-current-queue-revision");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "DROP TRIGGER validate_history_revision_update;
                     PRAGMA ignore_check_constraints=ON;
                     UPDATE history_local_identity SET local_revision = 0;
                     PRAGMA ignore_check_constraints=OFF;",
                )
                .unwrap();
        }
        assert_eq!(
            store
                .pending_history_revisions("synthetic-account", 10)
                .unwrap_err()
                .to_string(),
            "Invalid history sync queue metadata"
        );
        assert!(store.get(1).await.unwrap().is_some());
        let connection = store.conn.lock().unwrap();
        let revision: i64 = connection
            .query_row(
                "SELECT local_revision FROM history_local_identity",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(revision, 0);
    }

    #[tokio::test]
    async fn pending_history_queue_reads_only_selected_account_and_reports_changed_sources() {
        use history_sync_queue::PendingHistorySource;
        let store = temp_history_store("pending-queue-read");
        assert!(store
            .pending_history_revisions("account-a", 10)
            .unwrap()
            .is_empty());
        for id in 1..=3 {
            store
                .add(test_history_entry(0, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(id).unwrap().unwrap();
            store
                .enqueue_local_history_revision(
                    if id == 3 { "account-b" } else { "account-a" },
                    id,
                    &identity,
                )
                .unwrap();
        }
        assert_eq!(
            store
                .pending_history_revisions("account-a", 1)
                .unwrap()
                .len(),
            1
        );
        let initial = store.pending_history_revisions("account-a", 10).unwrap();
        assert_eq!(initial.len(), 2);
        assert_eq!(
            initial[0].source,
            PendingHistorySource::Current { history_id: 1 }
        );
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Synthetic changed' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        store.delete(2).await.unwrap();
        let changed = store.pending_history_revisions("account-a", 10).unwrap();
        assert_eq!(changed[0].source, PendingHistorySource::Changed);
        assert_eq!(changed[1].source, PendingHistorySource::Missing);
        assert_eq!(changed[0].mutation, initial[0].mutation);
        assert_eq!(changed[1].mutation, initial[1].mutation);
        assert_eq!(
            store
                .pending_history_revisions("account-b", 10)
                .unwrap()
                .len(),
            1
        );
        for limit in [0, 201] {
            assert!(store.pending_history_revisions("account-a", limit).is_err());
        }
    }

    #[tokio::test]
    async fn full_history_queue_rejects_new_items_without_claiming_the_record() {
        let store = temp_history_store("full-history-queue");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "WITH RECURSIVE entries(number) AS (
                         SELECT 1 UNION ALL SELECT number + 1 FROM entries WHERE number < 5000
                     )
                     INSERT INTO history_sync_pending(account_id, record_uuid, mutation_uuid, client_revision)
                     SELECT 'synthetic-account', 'synthetic-record-' || number,
                            'synthetic-mutation-' || number, 1 FROM entries;",
                )
                .unwrap();
        }
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap_err()
                .to_string(),
            "History sync queue capacity reached"
        );
        let connection = store.conn.lock().unwrap();
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_sync_pending", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 5000);
        let claimed: bool = connection
            .query_row(
                "SELECT EXISTS(SELECT 1 FROM history_sync_record_owner WHERE record_uuid = ?1)",
                [&identity.record_uuid],
                |row| row.get(0),
            )
            .unwrap();
        assert!(!claimed);
        connection
            .execute(
                "DELETE FROM history_sync_pending WHERE mutation_uuid = 'synthetic-mutation-1'",
                [],
            )
            .unwrap();
        drop(connection);
        let queued = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap(),
            queued
        );
    }

    #[tokio::test]
    async fn explicit_history_queue_does_not_allow_another_account_to_claim_the_same_record() {
        let store = temp_history_store("history-queue-owner");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let queued = store
            .enqueue_local_history_revision("synthetic-account-a", 1, &identity)
            .unwrap();
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account-b", 1, &identity)
                .unwrap_err()
                .to_string(),
            "History sync record belongs to another account"
        );
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute("DELETE FROM history_sync_pending", [])
                .unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Synthetic edited text' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let current = store.local_identity_snapshot(1).unwrap().unwrap();
        assert!(store
            .enqueue_local_history_revision("synthetic-account-b", 1, &current)
            .is_err());
        let next = store
            .enqueue_local_history_revision("synthetic-account-a", 1, &current)
            .unwrap();
        assert_eq!(next.account_id, queued.account_id);
        assert_eq!(next.client_revision, 2);
        assert!(store.get(1).await.unwrap().is_some());
    }

    #[tokio::test]
    async fn explicit_history_queue_survives_reopen_and_stays_empty_until_selected() {
        let path =
            std::env::temp_dir().join(format!("history-queue-{}.sqlite", uuid::Uuid::new_v4()));
        let (identity, queued) = {
            let store = HistoryStore::new(path.clone()).unwrap();
            store
                .add(test_history_entry(0, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let count: i64 = store
                .conn
                .lock()
                .unwrap()
                .query_row("SELECT COUNT(*) FROM history_sync_pending", [], |row| {
                    row.get(0)
                })
                .unwrap();
            assert_eq!(count, 0);
            let identity = store.local_identity_snapshot(1).unwrap().unwrap();
            let queued = store
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap();
            (identity, queued)
        };
        let store = HistoryStore::new(path).unwrap();
        assert_eq!(
            store
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap(),
            queued
        );
        let connection = store.conn.lock().unwrap();
        connection
            .execute(
                "INSERT INTO history_identity_state(id, rebuild_required) VALUES (1, 1)",
                [],
            )
            .unwrap();
        drop(connection);
        assert!(store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .is_err());
    }

    #[tokio::test]
    async fn history_sync_queue_requires_explicit_current_revision_and_reuses_mutation_identity() {
        let store = temp_history_store("explicit-sync-queue");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let identity = store.local_identity_snapshot(1).unwrap().unwrap();
        let first = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let repeat = store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        assert_eq!(first, repeat);
        assert_eq!(first.client_revision, 1);
        assert_eq!(first.record_uuid, identity.record_uuid);
        assert_eq!(
            uuid::Uuid::parse_str(&first.mutation_uuid)
                .unwrap()
                .get_version_num(),
            4
        );
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'New synthetic text' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        assert!(store
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .is_err());
        let current = store.local_identity_snapshot(1).unwrap().unwrap();
        let next = store
            .enqueue_local_history_revision("synthetic-account", 1, &current)
            .unwrap();
        assert_ne!(next.mutation_uuid, first.mutation_uuid);
        assert_eq!(next.client_revision, 2);
    }

    #[tokio::test]
    async fn local_text_snapshot_pairs_persisted_content_with_its_revision() {
        let store = temp_history_store("text-identity-snapshot");
        assert!(store.local_text_snapshot(999).unwrap().is_none());
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let original = store.local_text_snapshot(1).unwrap().unwrap();
        assert_eq!(original.identity.local_revision, 1);
        assert_eq!(original.raw_text, "raw 0");
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Synthetic question',
                     polished_text = 'Synthetic polished question', mode = 'ask',
                     answer_text = 'Synthetic answer' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let changed = store.local_text_snapshot(1).unwrap().unwrap();
        assert_eq!(changed.identity.record_uuid, original.identity.record_uuid);
        assert_eq!(changed.identity.local_revision, 2);
        assert_eq!(changed.raw_text, "Synthetic question");
        assert_eq!(changed.polished_text, "Synthetic polished question");
        assert_eq!(changed.mode, "ask");
        assert_eq!(changed.answer_text.as_deref(), Some("Synthetic answer"));
        assert_eq!(changed.created_at, "2026-11-19T10:00:00");
        assert_eq!(changed.duration_ms, None);
        assert_eq!(changed.language, None);
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "INSERT INTO history_identity_state(id, rebuild_required) VALUES (1, 1)",
                    [],
                )
                .unwrap();
        }
        assert_eq!(
            store.local_text_snapshot(1).err().unwrap().to_string(),
            "History synchronization text unavailable"
        );
        assert!(store.get(1).await.unwrap().is_some());
    }

    #[tokio::test]
    async fn identity_snapshot_rejects_baseline_invalidated_by_another_connection() {
        let path =
            std::env::temp_dir().join(format!("identity-reader-{}.sqlite", uuid::Uuid::new_v4()));
        let store = HistoryStore::new(path.clone()).unwrap();
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        assert!(store.local_identity_snapshot(1).unwrap().is_some());
        let other = Connection::open(path).unwrap();
        other
            .execute(
                "INSERT INTO history_identity_state(id, rebuild_required) VALUES (1, 1)",
                [],
            )
            .unwrap();
        for id in [1, 999] {
            assert_eq!(
                store.local_identity_snapshot(id).unwrap_err().to_string(),
                "History synchronization identity unavailable"
            );
        }
        assert_eq!(store.get(1).await.unwrap().unwrap().raw_text, "raw 0");
    }

    #[tokio::test]
    async fn local_identity_snapshot_reads_persisted_revision_without_creating_missing_identity() {
        let store = temp_history_store("identity-snapshot");
        assert_eq!(store.local_identity_snapshot(999).unwrap(), None);
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let original = store.local_identity_snapshot(1).unwrap().unwrap();
        assert_eq!(original.local_revision, 1);
        assert_eq!(
            uuid::Uuid::parse_str(&original.record_uuid)
                .unwrap()
                .get_version_num(),
            4
        );
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Synthetic changed text' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let changed = store.local_identity_snapshot(1).unwrap().unwrap();
        assert_eq!(changed.record_uuid, original.record_uuid);
        assert_eq!(changed.local_revision, 2);
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "DELETE FROM history_local_identity WHERE history_id = 1",
                    [],
                )
                .unwrap();
        }
        assert_eq!(
            store.local_identity_snapshot(1).unwrap_err().to_string(),
            "History synchronization identity unavailable"
        );
        assert!(store.get(1).await.unwrap().is_some());
    }

    #[tokio::test]
    async fn history_local_identity_is_stable_unique_and_deleted_with_the_record() {
        let store = temp_history_store("local-history-identity");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        store
            .add(test_history_entry(0, "2026-11-19T10:01:00"))
            .await
            .unwrap();
        let identities = {
            let connection = store.conn.lock().unwrap();
            let mut query = connection
                .prepare("SELECT history_id, record_uuid FROM history_local_identity ORDER BY history_id")
                .unwrap();
            query
                .query_map([], |row| {
                    Ok((row.get::<_, i64>(0)?, row.get::<_, String>(1)?))
                })
                .unwrap()
                .collect::<rusqlite::Result<Vec<_>>>()
                .unwrap()
        };
        assert_eq!(identities.len(), 2);
        assert_ne!(identities[0].1, identities[1].1);
        for (_, identity) in &identities {
            assert_eq!(
                uuid::Uuid::parse_str(identity).unwrap().get_version_num(),
                4
            );
        }
        {
            let connection = store.conn.lock().unwrap();
            initialize_history_local_identity(&connection).unwrap();
            let identity: String = connection
                .query_row(
                    "SELECT record_uuid FROM history_local_identity WHERE history_id = ?1",
                    [identities[0].0],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(identity, identities[0].1);
        }
        store.delete(identities[0].0).await.unwrap();
        let connection = store.conn.lock().unwrap();
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_local_identity", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 1);
    }

    #[tokio::test]
    async fn invalid_sync_metadata_does_not_disable_local_history_after_reopen() {
        let path =
            std::env::temp_dir().join(format!("degraded-identity-{}.sqlite", uuid::Uuid::new_v4()));
        {
            let store = HistoryStore::new(path.clone()).unwrap();
            store
                .add(test_history_entry(0, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "DROP TRIGGER validate_history_revision_update;
                     PRAGMA ignore_check_constraints=ON;
                     UPDATE history_local_identity SET local_revision = 1.5;
                     PRAGMA ignore_check_constraints=OFF;",
                )
                .unwrap();
        }
        let store = HistoryStore::new(path.clone()).unwrap();
        assert_eq!(store.get(1).await.unwrap().unwrap().raw_text, "raw 0");
        store
            .add(test_history_entry(0, "2026-11-19T11:00:00"))
            .await
            .unwrap();
        assert_eq!(store.list(10, 0).await.unwrap().len(), 2);
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET raw_text = 'Synthetic local recovery' WHERE id = 1",
                    [],
                )
                .unwrap();
            let revision: f64 = connection
                .query_row(
                    "SELECT local_revision FROM history_local_identity WHERE history_id = 1",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(revision, 1.5);
            let identity_count: i64 = connection
                .query_row("SELECT COUNT(*) FROM history_local_identity", [], |row| {
                    row.get(0)
                })
                .unwrap();
            assert_eq!(identity_count, 1);
            connection
                .execute("UPDATE history_local_identity SET local_revision = 1", [])
                .unwrap();
        }
        drop(store);
        let reopened = HistoryStore::new(path).unwrap();
        assert!(!reopened.local_identity_available);
        assert_eq!(
            reopened.local_identity_snapshot(1).unwrap_err().to_string(),
            "History synchronization identity unavailable"
        );
        reopened
            .add(test_history_entry(0, "2026-11-19T12:00:00"))
            .await
            .unwrap();
        let connection = reopened.conn.lock().unwrap();
        let identities: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_local_identity", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(identities, 1);
    }

    #[tokio::test]
    async fn degraded_history_preserves_audio_and_restores_backup_sources_without_sync_identity() {
        let path =
            std::env::temp_dir().join(format!("degraded-backup-{}.sqlite", uuid::Uuid::new_v4()));
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let saved_id = {
            let store = HistoryStore::new(path.clone()).unwrap();
            let mut entry = test_history_entry(0, "2026-11-19T10:00:00");
            entry.mode = "ask".into();
            entry.answer_text = Some("Synthetic saved answer".into());
            let id = store
                .add_with_metadata_sync(
                    entry,
                    &HistoryRetentionPolicy::default(),
                    Some(audio.clone()),
                    Some(
                        r#"[{"url":"https://example.invalid","title":"Synthetic source"}]"#.into(),
                    ),
                )
                .unwrap()
                .unwrap();
            store
                .conn
                .lock()
                .unwrap()
                .execute(
                    "INSERT INTO history_identity_state(id, rebuild_required) VALUES (1, 1)",
                    [],
                )
                .unwrap();
            id
        };
        let store = HistoryStore::new(path.clone()).unwrap();
        assert!(!store.local_identity_available);
        assert_eq!(store.read_audio(saved_id).await.unwrap(), Some(audio));
        let backup = store.list(10, 0).await.unwrap();
        assert_eq!(backup.len(), 1);
        assert!(backup[0].citations.is_some());
        store
            .restore_backup_data(
                Some(backup),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-19T11:00:00",
            )
            .await
            .unwrap();
        let restored = store.list(10, 0).await.unwrap();
        assert_eq!(restored.len(), 1);
        assert_ne!(restored[0].id, saved_id);
        assert_eq!(
            restored[0].answer_text.as_deref(),
            Some("Synthetic saved answer")
        );
        assert!(store
            .read_citations_sync(restored[0].id, "raw 0", "Synthetic saved answer")
            .unwrap()
            .is_some());
        assert_eq!(store.read_audio(restored[0].id).await.unwrap(), None);
        {
            let connection = store.conn.lock().unwrap();
            let identity_count: i64 = connection
                .query_row("SELECT COUNT(*) FROM history_local_identity", [], |row| {
                    row.get(0)
                })
                .unwrap();
            assert_eq!(identity_count, 0);
        }
        drop(store);
        let reopened = HistoryStore::new(path).unwrap();
        assert!(!reopened.local_identity_available);
        assert_eq!(reopened.list(10, 0).await.unwrap().len(), 1);
    }

    #[tokio::test]
    async fn history_identity_migration_rejects_existing_invalid_revisions_without_repairing_data()
    {
        for revision in ["0", "-1", "1.5", "9007199254740992", "NULL", "'private'"] {
            let store = temp_history_store("invalid-existing-history-revision");
            store
                .add(test_history_entry(0, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "DROP TRIGGER track_history_text_revision;
                     DROP TABLE history_local_identity;
                     CREATE TABLE history_local_identity (
                         history_id INTEGER PRIMARY KEY REFERENCES history(id) ON DELETE CASCADE,
                         record_uuid TEXT NOT NULL UNIQUE,
                         local_revision INTEGER
                     );",
                )
                .unwrap();
            let identity = uuid::Uuid::new_v4().to_string();
            connection
                .execute(
                    &format!(
                        "INSERT INTO history_local_identity(history_id, record_uuid, local_revision)
                         VALUES (1, ?1, {revision})"
                    ),
                    [&identity],
                )
                .unwrap();
            let original: rusqlite::types::Value = connection
                .query_row(
                    "SELECT local_revision FROM history_local_identity",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            let error = initialize_history_local_identity(&connection).unwrap_err();
            assert_eq!(error.to_string(), "Invalid existing history revision");
            let retained: (String, rusqlite::types::Value) = connection
                .query_row(
                    "SELECT record_uuid, local_revision FROM history_local_identity",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(retained, (identity, original));
            let text: String = connection
                .query_row("SELECT raw_text FROM history", [], |row| row.get(0))
                .unwrap();
            assert_eq!(text, "raw 0");
            assert!(connection.is_autocommit());
        }
    }

    #[tokio::test]
    async fn legacy_history_identity_upgrade_preserves_uuid_and_rejects_invalid_revisions() {
        let path =
            std::env::temp_dir().join(format!("legacy-identity-{}.sqlite", uuid::Uuid::new_v4()));
        let original_uuid = uuid::Uuid::new_v4().to_string();
        {
            let store = HistoryStore::new(path.clone()).unwrap();
            store
                .add(test_history_entry(0, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "DROP TRIGGER track_history_text_revision;
                     DROP TABLE history_local_identity;
                     CREATE TABLE history_local_identity (
                         history_id INTEGER PRIMARY KEY REFERENCES history(id) ON DELETE CASCADE,
                         record_uuid TEXT NOT NULL UNIQUE
                     );",
                )
                .unwrap();
            connection
                .execute(
                    "INSERT INTO history_local_identity(history_id, record_uuid) VALUES (1, ?1)",
                    [&original_uuid],
                )
                .unwrap();
        }
        let store = HistoryStore::new(path).unwrap();
        assert_eq!(store.get(1).await.unwrap().unwrap().raw_text, "raw 0");
        let connection = store.conn.lock().unwrap();
        let upgraded: (String, i64) = connection
            .query_row(
                "SELECT record_uuid, local_revision FROM history_local_identity WHERE history_id = 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(upgraded, (original_uuid, 1));
        for value in ["0", "-1", "1.5", "9007199254740992", "NULL", "'private'"] {
            assert!(
                connection
                    .execute(
                        &format!("UPDATE history_local_identity SET local_revision = {value}"),
                        [],
                    )
                    .is_err(),
                "Invalid revision must be rejected: {value}"
            );
        }
    }

    #[tokio::test]
    async fn reopening_history_upgrades_old_revision_trigger_without_replacing_identity() {
        let path = std::env::temp_dir().join(format!(
            "old-revision-trigger-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let original = {
            let store = HistoryStore::new(path.clone()).unwrap();
            store
                .add(test_history_entry(0, "2026-11-19T10:00:00"))
                .await
                .unwrap();
            let identity = store.local_identity_snapshot(1).unwrap().unwrap();
            let connection = store.conn.lock().unwrap();
            connection
                .execute_batch(
                    "DROP TRIGGER track_history_text_revision;
                     CREATE TRIGGER track_history_text_revision
                     AFTER UPDATE OF raw_text ON history
                     WHEN OLD.raw_text IS NOT NEW.raw_text
                     BEGIN
                         UPDATE history_local_identity SET local_revision = local_revision + 1
                         WHERE history_id = NEW.id;
                     END;",
                )
                .unwrap();
            identity
        };
        let store = HistoryStore::new(path.clone()).unwrap();
        assert_eq!(
            store.local_identity_snapshot(1).unwrap(),
            Some(original.clone())
        );
        assert_eq!(store.get(1).await.unwrap().unwrap().raw_text, "raw 0");
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET created_at = '2026-11-19T11:00:00',
                     duration_ms = 42, language = 'ja' WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        let snapshot = store.local_text_snapshot(1).unwrap().unwrap();
        assert_eq!(snapshot.identity.record_uuid, original.record_uuid);
        assert_eq!(snapshot.identity.local_revision, 2);
        assert_eq!(snapshot.created_at, "2026-11-19T11:00:00");
        assert_eq!(snapshot.duration_ms, Some(42));
        assert_eq!(snapshot.language.as_deref(), Some("ja"));
        drop(store);
        let reopened = HistoryStore::new(path).unwrap();
        assert_eq!(
            reopened.local_identity_snapshot(1).unwrap(),
            Some(snapshot.identity)
        );
    }

    #[tokio::test]
    async fn history_revision_tracks_sync_metadata_changes_and_preserves_noop_updates() {
        let store = temp_history_store("history-metadata-revision");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let original = store.local_identity_snapshot(1).unwrap().unwrap();
        for (offset, assignment) in [
            "created_at = '2026-11-19T10:01:00'",
            "duration_ms = 42",
            "language = 'ja'",
        ]
        .iter()
        .enumerate()
        {
            {
                let connection = store.conn.lock().unwrap();
                connection
                    .execute(&format!("UPDATE history SET {assignment} WHERE id = 1"), [])
                    .unwrap();
            }
            let changed = store.local_identity_snapshot(1).unwrap().unwrap();
            assert_eq!(changed.record_uuid, original.record_uuid);
            assert_eq!(changed.local_revision, offset as i64 + 2);
        }
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history SET created_at = created_at, duration_ms = duration_ms,
                     language = language WHERE id = 1",
                    [],
                )
                .unwrap();
        }
        assert_eq!(
            store
                .local_identity_snapshot(1)
                .unwrap()
                .unwrap()
                .local_revision,
            4
        );
    }

    #[tokio::test]
    async fn history_text_revision_changes_atomically_without_replacing_identity() {
        let store = temp_history_store("history-text-revision");
        store
            .add(test_history_entry(0, "2026-11-19T10:00:00"))
            .await
            .unwrap();
        let connection = store.conn.lock().unwrap();
        let read_identity = || {
            connection
                .query_row(
                    "SELECT record_uuid, local_revision FROM history_local_identity",
                    [],
                    |row| Ok((row.get::<_, String>(0)?, row.get::<_, i64>(1)?)),
                )
                .unwrap()
        };
        let original = read_identity();
        assert_eq!(original.1, 1);
        connection
            .execute("UPDATE history SET raw_text = raw_text", [])
            .unwrap();
        assert_eq!(read_identity(), original);
        connection
            .execute("UPDATE history SET raw_text = 'Synthetic new text'", [])
            .unwrap();
        assert_eq!(read_identity(), (original.0.clone(), 2));
        for assignment in [
            "polished_text = 'Synthetic polished text'",
            "mode = 'ask'",
            "answer_text = 'Synthetic answer'",
        ] {
            connection
                .execute(&format!("UPDATE history SET {assignment}"), [])
                .unwrap();
        }
        assert_eq!(read_identity(), (original.0.clone(), 5));
        connection
            .execute("UPDATE history SET duration_ms = 42", [])
            .unwrap();
        assert_eq!(read_identity().1, 6);
        connection
            .execute(
                "UPDATE history_local_identity SET local_revision = 9007199254740991",
                [],
            )
            .unwrap();
        assert!(connection
            .execute("UPDATE history SET raw_text = 'Must not commit'", [])
            .is_err());
        let retained: String = connection
            .query_row("SELECT raw_text FROM history", [], |row| row.get(0))
            .unwrap();
        assert_eq!(retained, "Synthetic new text");
        assert_eq!(read_identity().1, 9007199254740991);
    }

    #[tokio::test]
    async fn history_identity_migrates_legacy_rows_and_restore_creates_new_identity() {
        let path =
            std::env::temp_dir().join(format!("history-identity-{}.sqlite", uuid::Uuid::new_v4()));
        {
            let store = HistoryStore::new(path.clone()).unwrap();
            let connection = store.conn.lock().unwrap();
            connection
                .execute("DROP TABLE history_local_identity", [])
                .unwrap();
            connection
                .execute(
                    "INSERT INTO history(created_at, raw_text) VALUES ('2026-11-19T10:00:00', 'Legacy synthetic')",
                    [],
                )
                .unwrap();
        }
        let old_identity = {
            let store = HistoryStore::new(path.clone()).unwrap();
            let connection = store.conn.lock().unwrap();
            connection
                .query_row(
                    "SELECT record_uuid FROM history_local_identity",
                    [],
                    |row| row.get::<_, String>(0),
                )
                .unwrap()
        };
        let store = HistoryStore::new(path).unwrap();
        let entries = store.list(10, 0).await.unwrap();
        store
            .restore_backup_data(
                Some(entries),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-19T11:00:00",
            )
            .await
            .unwrap();
        let connection = store.conn.lock().unwrap();
        let restored: String = connection
            .query_row(
                "SELECT record_uuid FROM history_local_identity",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_ne!(restored, old_identity);
        assert_eq!(
            uuid::Uuid::parse_str(&restored).unwrap().get_version_num(),
            4
        );
    }

    #[tokio::test]
    async fn history_identity_failure_rolls_back_insert_and_preserves_restore_data() {
        let store = temp_history_store("identity-write-rollback");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let mut entry = test_history_entry(0, "2026-11-19T10:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic saved answer".into());
        let id = store
            .add_with_metadata_sync(
                entry,
                &HistoryRetentionPolicy::default(),
                Some(audio.clone()),
                Some(r#"[{"url":"https://example.invalid","title":"Synthetic source"}]"#.into()),
            )
            .unwrap()
            .unwrap();
        let original = store.list(10, 0).await.unwrap();
        let original_identity: String = {
            let connection = store.conn.lock().unwrap();
            let identity = connection
                .query_row(
                    "SELECT record_uuid FROM history_local_identity WHERE history_id = ?1",
                    [id],
                    |row| row.get(0),
                )
                .unwrap();
            connection
                .execute_batch(
                    "CREATE TRIGGER fail_history_identity BEFORE INSERT ON history_local_identity
                     BEGIN SELECT RAISE(ABORT, 'Synthetic identity write failure'); END;",
                )
                .unwrap();
            identity
        };
        assert!(store
            .add_with_audio(
                test_history_entry(0, "2026-11-19T10:01:00"),
                &HistoryRetentionPolicy::default(),
                Some(audio.clone()),
            )
            .await
            .is_err());
        assert!(store
            .restore_backup_data(
                Some(vec![test_history_entry(0, "2026-11-19T11:00:00")]),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-19T12:00:00",
            )
            .await
            .is_err());
        let retained = store.list(10, 0).await.unwrap();
        assert_eq!(
            serde_json::to_value(retained).unwrap(),
            serde_json::to_value(original).unwrap()
        );
        assert_eq!(store.read_audio(id).await.unwrap(), Some(audio));
        assert!(store
            .read_citations_sync(id, "raw 0", "Synthetic saved answer")
            .unwrap()
            .is_some());
        let connection = store.conn.lock().unwrap();
        let retained_identity: String = connection
            .query_row(
                "SELECT record_uuid FROM history_local_identity WHERE history_id = ?1",
                [id],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(retained_identity, original_identity);
        assert!(connection.is_autocommit());
    }

    #[test]
    fn history_identity_backfill_failure_rolls_back_all_missing_identities() {
        let store = temp_history_store("identity-backfill-rollback");
        let connection = store.conn.lock().unwrap();
        connection
            .execute_batch(
                "INSERT INTO history(created_at, raw_text)
                 VALUES ('2026-11-19T10:00:00', 'Synthetic legacy one'),
                        ('2026-11-19T10:01:00', 'Synthetic legacy two');
                 CREATE TRIGGER fail_second_identity BEFORE INSERT ON history_local_identity
                 WHEN NEW.history_id = 2
                 BEGIN SELECT RAISE(ABORT, 'Synthetic migration failure'); END;",
            )
            .unwrap();
        assert!(initialize_history_local_identity(&connection).is_err());
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_local_identity", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 0);
        assert!(connection.is_autocommit());
        connection
            .execute_batch("DROP TRIGGER fail_second_identity")
            .unwrap();
        initialize_history_local_identity(&connection).unwrap();
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_local_identity", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 2);
    }

    #[tokio::test]
    async fn history_search_pages_match_older_records_and_literal_unicode_text() {
        let store = temp_history_store("search-pages");
        for entry_id in 1..=205 {
            let mut entry = test_history_entry(entry_id, "2026-11-18T10:00:00");
            entry.raw_text = "ordinary".to_string();
            entry.polished_text = "ordinary".to_string();
            if entry_id <= 3 {
                entry.answer_text = Some("ÄPFEL 100%_ 中文".to_string());
                entry.mode = "ask".to_string();
            }
            store.add(entry).await.unwrap();
        }
        let page = store
            .search_page("äpfel 100%_", "ask", 2, None)
            .await
            .unwrap();
        assert_eq!(
            page.iter().map(|entry| entry.id).collect::<Vec<_>>(),
            vec![3, 2]
        );
        let next = store.search_page("中文", "ask", 2, Some(2)).await.unwrap();
        assert_eq!(
            next.iter().map(|entry| entry.id).collect::<Vec<_>>(),
            vec![1]
        );
        assert!(store
            .search_page("中文", "dictate", 2, None)
            .await
            .unwrap()
            .is_empty());
        assert!(store
            .search_page("absent", "all", 2, None)
            .await
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn retention_cleanup_rejects_invalid_time_before_deleting_records_or_audio() {
        let store = temp_history_store("retention-invalid-time");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        for timestamp in ["2026-11-01T10:00:00", "2026-11-02T10:00:00"] {
            store
                .add_with_audio(
                    test_history_entry(0, timestamp),
                    &HistoryRetentionPolicy::default(),
                    Some(audio.clone()),
                )
                .await
                .unwrap();
        }
        let entries = store.list(100, 0).await.unwrap();
        for now in ["invalid", "2026-02-30T10:00:00", "-262143-01-01T00:00:00"] {
            assert!(store
                .prune_with_policy(
                    &HistoryRetentionPolicy {
                        max_entries: 1,
                        retention_days: 7,
                        ..HistoryRetentionPolicy::default()
                    },
                    now,
                )
                .await
                .is_err());
            assert_eq!(store.list(100, 0).await.unwrap().len(), 2);
            for entry in &entries {
                assert_eq!(
                    store.read_audio(entry.id).await.unwrap(),
                    Some(audio.clone())
                );
            }
        }
    }

    #[tokio::test]
    async fn confirmed_retention_removes_expired_audio_and_preserves_cutoff() {
        let store = temp_history_store("retention-cutoff");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        for timestamp in ["2026-11-02T09:59:59", "2026-11-03T10:00:00"] {
            store
                .add_with_audio(
                    test_history_entry(0, timestamp),
                    &HistoryRetentionPolicy::default(),
                    Some(audio.clone()),
                )
                .await
                .unwrap();
        }
        let entries = store.list(100, 0).await.unwrap();
        store
            .prune_with_policy(
                &HistoryRetentionPolicy {
                    retention_days: 7,
                    ..HistoryRetentionPolicy::default()
                },
                "2026-11-10T10:00:00",
            )
            .await
            .unwrap();
        assert_eq!(store.list(100, 0).await.unwrap().len(), 1);
        assert_eq!(store.read_audio(entries[0].id).await.unwrap(), Some(audio));
        assert!(store.read_audio(entries[1].id).await.unwrap().is_none());
        store
            .prune_with_policy(
                &HistoryRetentionPolicy {
                    enabled: false,
                    ..HistoryRetentionPolicy::default()
                },
                "2026-11-10T10:00:00",
            )
            .await
            .unwrap();
        assert!(store.list(100, 0).await.unwrap().is_empty());
        assert!(store.read_audio(entries[0].id).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn corrupted_database_audio_is_rejected_without_deleting_history() {
        let store = temp_history_store("corrupted-audio-read");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let id = store
            .add_with_audio(
                test_history_entry(0, "2026-11-12T10:00:00"),
                &HistoryRetentionPolicy::default(),
                Some(audio.clone()),
            )
            .await
            .unwrap()
            .unwrap();
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_audio SET wav = zeroblob(25165869) WHERE history_id = ?1",
                    [id],
                )
                .unwrap();
        }
        assert!(store.read_audio(id).await.is_err());
        assert!(store.get(id).await.unwrap().is_some());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_audio SET wav = ?1 WHERE history_id = ?2",
                    rusqlite::params![b"invalid recording".as_slice(), id],
                )
                .unwrap();
        }
        assert!(store.read_audio(id).await.is_err());
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_audio SET wav = ?1 WHERE history_id = ?2",
                    rusqlite::params![audio, id],
                )
                .unwrap();
        }
        assert!(store.read_audio(id).await.unwrap().is_some());
    }

    #[test]
    fn history_audio_size_and_data_are_selected_in_one_statement() {
        let source = include_str!("mod.rs");
        let method = source
            .split("pub async fn read_audio(")
            .nth(1)
            .unwrap()
            .split("pub async fn prune_with_policy(")
            .next()
            .unwrap();
        assert_eq!(method.matches(".query_row(").count(), 1);
        assert!(method.contains("CASE WHEN length(wav) BETWEEN 44 AND ?2 THEN wav ELSE NULL END"));
    }

    #[tokio::test]
    async fn text_typed_audio_is_rejected_without_modifying_the_record() {
        let store = temp_history_store("text-typed-audio");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let id = store
            .add_with_audio(
                test_history_entry(0, "2026-11-12T10:00:00"),
                &HistoryRetentionPolicy::default(),
                Some(audio),
            )
            .await
            .unwrap()
            .unwrap();
        let invalid_text = "语".repeat(48);
        {
            let connection = store.conn.lock().unwrap();
            connection
                .execute(
                    "UPDATE history_audio SET wav = ?1 WHERE history_id = ?2",
                    rusqlite::params![invalid_text, id],
                )
                .unwrap();
        }
        assert!(store.read_audio(id).await.is_err());
        assert!(store.get(id).await.unwrap().is_some());
        let connection = store.conn.lock().unwrap();
        let stored: String = connection
            .query_row(
                "SELECT wav FROM history_audio WHERE history_id = ?1",
                [id],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(stored, invalid_text);
    }

    #[tokio::test]
    async fn malformed_recording_header_is_rejected_before_history_insertion() {
        let store = temp_history_store("malformed-recording");
        let valid =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        for offset in [4, 16, 20, 22, 24, 28, 32, 34, 40] {
            let mut invalid = valid.clone();
            invalid[offset] ^= 1;
            assert!(store
                .add_with_audio(
                    test_history_entry(0, "2026-11-10T10:00:00"),
                    &HistoryRetentionPolicy::default(),
                    Some(invalid),
                )
                .await
                .is_err());
            assert!(store.list(100, 0).await.unwrap().is_empty());
        }
        store
            .add_with_audio(
                test_history_entry(0, "2026-11-10T10:00:00"),
                &HistoryRetentionPolicy::default(),
                Some(valid.clone()),
            )
            .await
            .unwrap();
        let saved = store.list(100, 0).await.unwrap().remove(0);
        assert_eq!(store.read_audio(saved.id).await.unwrap(), Some(valid));
    }

    #[tokio::test]
    async fn invalid_retention_timestamp_rolls_back_a_new_history_recording() {
        let store = temp_history_store("invalid-insert-retention");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        assert!(store
            .add_with_audio(
                test_history_entry(0, "invalid"),
                &HistoryRetentionPolicy {
                    retention_days: 7,
                    ..HistoryRetentionPolicy::default()
                },
                Some(audio),
            )
            .await
            .is_err());
        assert!(store.list(100, 0).await.unwrap().is_empty());
        assert_eq!(
            store
                .conn
                .lock()
                .unwrap()
                .query_row("SELECT COUNT(*) FROM history_audio", [], |row| {
                    row.get::<_, i64>(0)
                })
                .unwrap(),
            0
        );
    }

    #[tokio::test]
    async fn retention_scans_full_capacity_and_removes_only_expired_rows_and_audio() {
        let store = temp_history_store("full-capacity-retention");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        {
            let mut connection = store.conn.lock().unwrap();
            let transaction = connection.transaction().unwrap();
            for index in 0..5000 {
                let timestamp = if index < 2500 {
                    "2026-11-03T09:59:59"
                } else {
                    "2026-11-03T10:00:00"
                };
                transaction.execute(
                    "INSERT INTO history(created_at, raw_text, polished_text) VALUES (?1, ?2, ?2)",
                    rusqlite::params![timestamp, "Synthetic text"],
                ).unwrap();
                transaction
                    .execute(
                        "INSERT INTO history_audio(history_id, wav) VALUES (?1, ?2)",
                        rusqlite::params![transaction.last_insert_rowid(), audio],
                    )
                    .unwrap();
            }
            transaction.commit().unwrap();
        }
        let started = std::time::Instant::now();
        store
            .prune_with_policy(
                &HistoryRetentionPolicy {
                    retention_days: 7,
                    ..HistoryRetentionPolicy::default()
                },
                "2026-11-10T10:00:00",
            )
            .await
            .unwrap();
        eprintln!(
            "5000-row synthetic retention elapsed: {:?}",
            started.elapsed()
        );
        let connection = store.conn.lock().unwrap();
        for table in ["history", "history_audio"] {
            assert_eq!(
                connection
                    .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |row| {
                        row.get::<_, i64>(0)
                    })
                    .unwrap(),
                2500
            );
        }
        assert_eq!(
            connection
                .query_row("SELECT MIN(created_at) FROM history", [], |row| {
                    row.get::<_, String>(0)
                })
                .unwrap(),
            "2026-11-03T10:00:00"
        );
    }

    #[tokio::test]
    async fn retention_age_check_preserves_extended_year_future_recordings() {
        let store = temp_history_store("extended-retention");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let id = store
            .add_with_audio(
                test_history_entry(0, "+10000-01-01T10:00:00"),
                &HistoryRetentionPolicy::default(),
                Some(audio.clone()),
            )
            .await
            .unwrap()
            .unwrap();
        store
            .prune_with_policy(
                &HistoryRetentionPolicy {
                    retention_days: 7,
                    ..HistoryRetentionPolicy::default()
                },
                "2026-11-10T10:00:00",
            )
            .await
            .unwrap();
        assert_eq!(store.list(100, 0).await.unwrap().len(), 1);
        assert_eq!(store.read_audio(id).await.unwrap(), Some(audio));
    }

    #[tokio::test]
    async fn history_insert_and_retention_failure_roll_back_in_one_transaction() {
        let store = temp_history_store("insert-retention-rollback");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        store
            .add_with_audio(
                test_history_entry(0, "2026-11-01T10:00:00"),
                &HistoryRetentionPolicy::default(),
                Some(audio.clone()),
            )
            .await
            .unwrap();
        let original = store.list(100, 0).await.unwrap().remove(0);
        store
            .conn
            .lock()
            .unwrap()
            .execute_batch(
                "CREATE TRIGGER reject_insert_prune BEFORE DELETE ON history
             BEGIN SELECT RAISE(ABORT, 'synthetic retention failure'); END;",
            )
            .unwrap();
        let result = store
            .add_with_audio(
                test_history_entry(0, "2026-11-10T10:00:00"),
                &HistoryRetentionPolicy {
                    max_entries: 1,
                    ..HistoryRetentionPolicy::default()
                },
                Some(audio.clone()),
            )
            .await;
        assert!(result.is_err());
        let entries = store.list(100, 0).await.unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].id, original.id);
        assert_eq!(store.read_audio(original.id).await.unwrap(), Some(audio));
        assert_eq!(
            store
                .conn
                .lock()
                .unwrap()
                .query_row("SELECT COUNT(*) FROM history_audio", [], |row| row
                    .get::<_, i64>(0))
                .unwrap(),
            1
        );
    }

    #[tokio::test]
    async fn retention_cleanup_rolls_back_records_and_audio_when_deletion_fails() {
        let store = temp_history_store("retention-rollback");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        for timestamp in ["2026-11-01T10:00:00", "2026-11-02T10:00:00"] {
            store
                .add_with_audio(
                    test_history_entry(0, timestamp),
                    &HistoryRetentionPolicy::default(),
                    Some(audio.clone()),
                )
                .await
                .unwrap();
        }
        let entries = store.list(100, 0).await.unwrap();
        store
            .conn
            .lock()
            .unwrap()
            .execute_batch(
                "CREATE TRIGGER reject_retention_delete BEFORE DELETE ON history
             WHEN OLD.created_at = '2026-11-02T10:00:00'
             BEGIN SELECT RAISE(ABORT, 'synthetic retention failure'); END;",
            )
            .unwrap();
        assert!(store
            .prune_with_policy(
                &HistoryRetentionPolicy {
                    enabled: true,
                    max_entries: 1,
                    retention_days: 1,
                },
                "2026-11-10T10:00:00",
            )
            .await
            .is_err());
        assert_eq!(store.list(100, 0).await.unwrap().len(), 2);
        for entry in entries {
            assert_eq!(
                store.read_audio(entry.id).await.unwrap(),
                Some(audio.clone())
            );
        }
    }

    #[tokio::test]
    async fn recording_audio_follows_history_deletion_and_retention() {
        let store = temp_history_store("audio-retention");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        store
            .add_with_audio(
                test_history_entry(0, "2026-11-01T10:00:00"),
                &HistoryRetentionPolicy::default(),
                Some(audio.clone()),
            )
            .await
            .unwrap();
        let entry = store.list(1, 0).await.unwrap().remove(0);
        assert!(entry.has_audio);
        assert_eq!(
            store.read_audio(entry.id).await.unwrap(),
            Some(audio.clone())
        );
        assert!(store.delete(entry.id).await.unwrap());
        assert!(store.read_audio(entry.id).await.unwrap().is_none());
        for day in ["2026-11-01T10:00:00", "2026-11-02T10:00:00"] {
            store
                .add_with_audio(
                    test_history_entry(0, day),
                    &HistoryRetentionPolicy::default(),
                    Some(audio.clone()),
                )
                .await
                .unwrap();
        }
        let oldest = store.list(2, 0).await.unwrap()[1].id;
        store
            .prune_with_policy(
                &HistoryRetentionPolicy {
                    enabled: true,
                    max_entries: 1,
                    retention_days: 0,
                },
                "2026-11-02T10:00:00",
            )
            .await
            .unwrap();
        assert!(store.read_audio(oldest).await.unwrap().is_none());
        let latest = store.list(1, 0).await.unwrap()[0].id;
        store.clear().await.unwrap();
        assert!(store.read_audio(latest).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn dismissed_take_is_restored_once_on_the_row_that_stored_it() {
        let store = temp_history_store("dismissed-restore");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let mut dismissed = test_history_entry(0, "2026-11-03T10:00:00");
        dismissed.output_status = Some(HISTORY_STATUS_DISMISSED.to_string());
        dismissed.raw_text.clear();
        dismissed.polished_text.clear();
        let id = store
            .add_with_audio(dismissed, &HistoryRetentionPolicy::default(), Some(audio))
            .await
            .unwrap()
            .unwrap();
        let stored = store.get(id).await.unwrap().unwrap();
        assert_eq!(stored.id, id);
        assert!(stored.has_audio);
        assert_eq!(
            stored.output_status.as_deref(),
            Some(HISTORY_STATUS_DISMISSED)
        );
        assert!(store.get(id + 1).await.unwrap().is_none());

        assert!(store
            .complete_unfinished_take(id, "raw words", "Polished words.", None, None)
            .await
            .unwrap());
        let restored = store.get(id).await.unwrap().unwrap();
        assert_eq!(restored.raw_text, "raw words");
        assert_eq!(restored.polished_text, "Polished words.");
        assert_eq!(restored.output_status, None);
        assert!(restored.has_audio);
        assert!(!store
            .complete_unfinished_take(id, "again", "again", None, None)
            .await
            .unwrap());
        assert_eq!(store.get(id).await.unwrap().unwrap().raw_text, "raw words");
    }

    #[tokio::test]
    async fn only_cancelled_or_failed_rows_accept_a_recovered_take() {
        let store = temp_history_store("dismissed-restore-guard");
        let mut silent = test_history_entry(0, "2026-11-03T10:00:00");
        silent.output_status = Some(HISTORY_STATUS_SILENT.to_string());
        let id = store
            .add_with_audio(silent, &HistoryRetentionPolicy::default(), None)
            .await
            .unwrap()
            .unwrap();
        assert!(!store
            .complete_unfinished_take(id, "raw", "polished", Some("fallback"), Some("why"))
            .await
            .unwrap());
        let mut failed = test_history_entry(0, "2026-11-03T10:01:00");
        failed.output_status = Some(HISTORY_STATUS_RECOGNITION_FAILED.to_string());
        failed.output_error = Some("Recognition did not produce a completed transcript".into());
        let failed_id = store
            .add_with_audio(failed, &HistoryRetentionPolicy::default(), None)
            .await
            .unwrap()
            .unwrap();
        assert!(store
            .complete_unfinished_take(failed_id, "raw", "Polished.", None, None)
            .await
            .unwrap());
        let recovered = store.get(failed_id).await.unwrap().unwrap();
        assert_eq!(recovered.polished_text, "Polished.");
        assert_eq!(recovered.output_status, None);
        assert_eq!(recovered.output_error, None);
        assert_eq!(
            store
                .add_with_audio(
                    test_history_entry(0, "2026-11-03T10:00:00"),
                    &HistoryRetentionPolicy {
                        enabled: false,
                        ..HistoryRetentionPolicy::default()
                    },
                    None,
                )
                .await
                .unwrap(),
            None
        );
    }

    #[tokio::test]
    async fn disabled_history_does_not_store_audio_and_invalid_audio_is_rejected() {
        let store = temp_history_store("audio-disabled");
        store
            .add_with_audio(
                test_history_entry(0, "2026-11-02T10:00:00"),
                &HistoryRetentionPolicy {
                    enabled: false,
                    ..HistoryRetentionPolicy::default()
                },
                Some(vec![0; 100]),
            )
            .await
            .unwrap();
        assert!(store.list(10, 0).await.unwrap().is_empty());
        assert!(store
            .add_with_audio(
                test_history_entry(0, "2026-11-02T10:00:00"),
                &HistoryRetentionPolicy::default(),
                Some(vec![0; 100]),
            )
            .await
            .is_err());
        assert!(store.list(10, 0).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn disabled_history_skips_valid_audio_in_both_database_tables() {
        let store = temp_history_store("valid-audio-disabled");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let policy = HistoryRetentionPolicy {
            enabled: false,
            ..HistoryRetentionPolicy::default()
        };
        for mode in ["dictate", "translate", "ask"] {
            let mut entry = test_history_entry(0, "2026-11-05T10:00:00");
            entry.mode = mode.to_string();
            let result = store
                .add_with_audio(entry, &policy, (mode != "ask").then(|| audio.clone()))
                .await
                .unwrap();
            assert_eq!(result, None);
        }
        let connection = store.conn.lock().unwrap();
        for table in ["history", "history_audio"] {
            let count: i64 = connection
                .query_row(&format!("SELECT COUNT(*) FROM {table}"), [], |row| {
                    row.get(0)
                })
                .unwrap();
            assert_eq!(count, 0, "{table} must remain empty");
        }
    }

    #[tokio::test]
    async fn disabled_history_insert_preserves_existing_text_and_audio() {
        let store = temp_history_store("disabled-preserves-existing-audio");
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let existing_id = store
            .add_with_audio(
                test_history_entry(0, "2026-11-04T10:00:00"),
                &HistoryRetentionPolicy::default(),
                Some(audio.clone()),
            )
            .await
            .unwrap()
            .unwrap();
        let existing = store.get(existing_id).await.unwrap().unwrap();
        let result = store
            .add_with_audio(
                test_history_entry(0, "2026-11-05T10:00:00"),
                &HistoryRetentionPolicy {
                    enabled: false,
                    max_entries: 1,
                    retention_days: 1,
                },
                Some(audio.clone()),
            )
            .await
            .unwrap();
        assert_eq!(result, None);
        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].id, existing_id);
        assert_eq!(entries[0].raw_text, existing.raw_text);
        assert_eq!(entries[0].polished_text, existing.polished_text);
        assert_eq!(store.read_audio(existing_id).await.unwrap(), Some(audio));
        let connection = store.conn.lock().unwrap();
        let audio_count: i64 = connection
            .query_row("SELECT COUNT(*) FROM history_audio", [], |row| row.get(0))
            .unwrap();
        assert_eq!(audio_count, 1);
    }

    #[tokio::test]
    async fn dismissed_and_silent_recordings_do_not_inflate_usage_statistics() {
        let store = temp_history_store("recording-only-stats");
        for status in [HISTORY_STATUS_DISMISSED, HISTORY_STATUS_SILENT] {
            let mut entry = test_history_entry(0, "2026-11-02T10:00:00");
            entry.output_status = Some(status.to_string());
            entry.raw_text.clear();
            entry.polished_text.clear();
            entry.duration_ms = Some(4000);
            store.add(entry).await.unwrap();
        }
        let stats = store
            .stats(chrono::NaiveDate::from_ymd_opt(2026, 11, 2).unwrap())
            .await
            .unwrap();
        assert_eq!(stats.total_sessions, 0);
        assert_eq!(stats.total_audio_seconds, 0);
    }

    #[tokio::test]
    async fn recovered_recordings_become_ordinary_transcripts() {
        let store = temp_history_store("recovered-recording");
        let mut dismissed = test_history_entry(0, "2026-11-02T10:00:00");
        dismissed.output_status = Some(HISTORY_STATUS_DISMISSED.to_string());
        dismissed.raw_text.clear();
        dismissed.polished_text.clear();
        store.add(dismissed).await.unwrap();
        store
            .add(test_history_entry(0, "2026-11-02T10:01:00"))
            .await
            .unwrap();
        let entries = store.list(10, 0).await.unwrap();
        let finished = entries.iter().find(|e| e.output_status.is_none()).unwrap();
        let recovered = entries.iter().find(|e| e.output_status.is_some()).unwrap();

        assert!(store
            .complete_recovered(recovered.id, "hello again")
            .await
            .unwrap());
        assert!(!store
            .complete_recovered(finished.id, "must not overwrite")
            .await
            .unwrap());

        let entries = store.list(10, 0).await.unwrap();
        let recovered = entries.iter().find(|e| e.id == recovered.id).unwrap();
        assert_eq!(recovered.raw_text, "hello again");
        assert!(recovered.output_status.is_none());
        let finished = entries.iter().find(|e| e.id == finished.id).unwrap();
        assert_ne!(finished.raw_text, "must not overwrite");
    }

    #[test]
    fn recording_only_statuses_match_typeless_item_states() {
        assert!(is_recording_only_status(Some("dismissed")));
        assert!(is_recording_only_status(Some("silent")));
        assert!(is_recording_only_status(Some("recognition_failed")));
        assert!(!is_recording_only_status(Some("partial")));
        assert!(!is_recording_only_status(None));
    }

    #[tokio::test]
    async fn failed_recordings_do_not_inflate_usage_statistics() {
        let store = temp_history_store("failed-audio-stats");
        let mut entry = test_history_entry(0, "2026-11-02T10:00:00");
        entry.output_status = Some("recognition_failed".to_string());
        entry.raw_text = "partial recognition".to_string();
        entry.polished_text.clear();
        entry.duration_ms = Some(5000);
        store.add(entry).await.unwrap();
        let stats = store
            .stats(chrono::NaiveDate::from_ymd_opt(2026, 11, 2).unwrap())
            .await
            .unwrap();
        assert_eq!(stats.total_sessions, 0);
        assert_eq!(stats.total_words, 0);
        assert_eq!(stats.total_audio_seconds, 0);
    }

    #[tokio::test]
    async fn history_store_keeps_ask_answers_and_deletes_single_entries() {
        let store = temp_history_store("ask-delete");
        let mut ask = test_history_entry(0, "2026-10-31T10:00:00");
        ask.mode = "ask".to_string();
        ask.raw_text = "What is the weather?".to_string();
        ask.answer_text = Some("Sunny.".to_string());
        store
            .add(test_history_entry(0, "2026-10-31T09:00:00"))
            .await
            .unwrap();
        store.add(ask).await.unwrap();

        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(entries[0].mode, "ask");
        assert_eq!(entries[0].answer_text.as_deref(), Some("Sunny."));
        assert_eq!(entries[1].mode, "dictate");

        assert!(store.delete(entries[1].id).await.unwrap());
        assert!(!store.delete(entries[1].id).await.unwrap());
        assert_eq!(store.list(10, 0).await.unwrap().len(), 1);
    }

    #[tokio::test]
    async fn history_stats_use_raw_words_when_polished_text_is_whitespace() {
        let store = temp_history_store("stats-whitespace");
        let mut entry = test_history_entry(0, "2026-11-04T09:00:00");
        entry.raw_text = "one two three".to_string();
        entry.polished_text = " \n\t ".to_string();
        entry.duration_ms = Some(1_500);
        store.add(entry).await.unwrap();
        let stats = store
            .stats(chrono::NaiveDate::from_ymd_opt(2026, 11, 4).unwrap())
            .await
            .unwrap();
        assert_eq!(stats.total_words, 3);
        assert_eq!(stats.average_wpm, Some(120));
    }

    #[tokio::test]
    async fn history_store_stats_skip_ask_questions() {
        let store = temp_history_store("stats");
        let mut dictation = test_history_entry(0, "2026-10-31T09:00:00");
        dictation.polished_text = "one two three".to_string();
        dictation.duration_ms = Some(1_500);
        let mut ask = test_history_entry(0, "2026-10-31T10:00:00");
        ask.mode = "ask".to_string();
        ask.polished_text = "ignored question words".to_string();
        store.add(dictation).await.unwrap();
        store.add(ask).await.unwrap();

        let today = chrono::NaiveDate::from_ymd_opt(2026, 10, 31).unwrap();
        let stats = store.stats(today).await.unwrap();
        assert_eq!(stats.total_words, 3);
        assert_eq!(stats.total_sessions, 1);
        assert_eq!(stats.average_wpm, Some(120));
        assert_eq!(stats.current_streak, 1);
    }

    fn temp_history_store(name: &str) -> HistoryStore {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-history-test-{}-{}.sqlite",
            name,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        HistoryStore::new(path).unwrap()
    }

    fn temp_dictionary_store(name: &str) -> DictionaryStore {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-dictionary-test-{}-{}.sqlite",
            name,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        DictionaryStore::new(path).unwrap()
    }

    fn temp_backup_stores(name: &str) -> (HistoryStore, DictionaryStore) {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-backup-test-{}-{}.sqlite",
            name,
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        let history = HistoryStore::new(path.clone()).unwrap();
        let dictionary = DictionaryStore::new(path).unwrap();
        (history, dictionary)
    }

    #[tokio::test]
    async fn history_store_respects_disabled_policy() {
        let store = temp_history_store("disabled");
        let policy = HistoryRetentionPolicy {
            enabled: false,
            max_entries: 5000,
            retention_days: 0,
        };

        store
            .add_with_policy(test_history_entry(1, "2026-07-01T00:00:00"), &policy)
            .await
            .unwrap();

        assert!(store.list(10, 0).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn legacy_ask_history_migration_preserves_sources_without_guessing_intent() {
        let path = std::env::temp_dir().join(format!(
            "otl-legacy-ask-intent-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let connection = Connection::open(&path).unwrap();
        connection
            .execute_batch(
                "CREATE TABLE history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    app_name TEXT NOT NULL DEFAULT '',
                    app_type TEXT NOT NULL DEFAULT '',
                    raw_text TEXT NOT NULL DEFAULT '',
                    polished_text TEXT NOT NULL DEFAULT '',
                    language TEXT,
                    duration_ms INTEGER,
                    mode TEXT NOT NULL DEFAULT 'dictate',
                    answer_text TEXT
                );
                INSERT INTO history(created_at, raw_text, polished_text, mode, answer_text)
                VALUES ('2026-11-20T00:00:00', 'Legacy question', 'Legacy question', 'ask', 'Legacy answer');
                CREATE TABLE history_citations (
                    history_id INTEGER PRIMARY KEY REFERENCES history(id) ON DELETE CASCADE,
                    sources TEXT NOT NULL
                );",
            )
            .unwrap();
        connection
            .execute(
                "INSERT INTO history_citations(history_id, sources) VALUES (1, ?1)",
                [r#"[{"url":"https://example.invalid/source","title":"Legacy source"}]"#],
            )
            .unwrap();
        drop(connection);
        for _reopen in 0..2 {
            let store = HistoryStore::new(path.clone()).unwrap();
            let entry = store.get(1).await.unwrap().unwrap();
            assert_eq!(entry.raw_text, "Legacy question");
            assert_eq!(entry.answer_text.as_deref(), Some("Legacy answer"));
            assert!(entry.ask_intent.is_none());
            let saved = store
                .read_saved_answer_sync(1, "Legacy question", "Legacy answer")
                .unwrap()
                .unwrap();
            assert!(saved.ask_intent.is_none());
            assert_eq!(
                saved.citations,
                serde_json::json!([{
                    "url": "https://example.invalid/source",
                    "title": "Legacy source"
                }])
            );
            assert!(serde_json::to_value(saved)
                .unwrap()
                .get("askIntent")
                .is_none());
        }
        std::fs::remove_file(path).unwrap();
    }

    #[tokio::test]
    async fn saved_ask_intent_is_invalidated_by_content_changes_not_metadata() {
        let store = temp_history_store("ask-intent-invalidation");
        let mut entry = test_history_entry(0, "2026-11-20T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        entry.ask_intent = Some("ask_selection".into());
        store.add(entry).await.unwrap();
        store
            .conn
            .lock()
            .unwrap()
            .execute("UPDATE history SET duration_ms = 42 WHERE id = 1", [])
            .unwrap();
        assert_eq!(
            store.get(1).await.unwrap().unwrap().ask_intent.as_deref(),
            Some("ask_selection")
        );
        for change in [
            "raw_text = 'Changed question'",
            "answer_text = 'Changed answer'",
            "mode = 'dictate'",
        ] {
            store
                .conn
                .lock()
                .unwrap()
                .execute(
                    &format!(
                        "UPDATE history SET ask_intent = 'ask_selection', {change} WHERE id = 1"
                    ),
                    [],
                )
                .unwrap();
            assert!(store.get(1).await.unwrap().unwrap().ask_intent.is_none());
        }
    }

    #[tokio::test]
    async fn invalid_saved_ask_intent_does_not_insert_or_replace_history() {
        let store = temp_history_store("ask-intent-invalid");
        store
            .add(test_history_entry(1, "2026-11-20T00:00:00"))
            .await
            .unwrap();
        let mut entry = test_history_entry(2, "2026-11-20T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        entry.ask_intent = Some("search".into());
        assert!(store.add(entry.clone()).await.is_err());
        assert!(store
            .restore_backup_data(
                Some(vec![entry]),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-20T00:00:00",
            )
            .await
            .is_err());
        let retained = store.list(10, 0).await.unwrap();
        assert_eq!(retained.len(), 1);
        assert_eq!(retained[0].raw_text, "raw 1");
        assert!(retained[0].ask_intent.is_none());
    }

    #[tokio::test]
    async fn saved_ask_intent_survives_storage_serialization_and_backup_restore() {
        let source = temp_history_store("ask-intent-source");
        let entry = test_history_entry(0, "2026-11-20T00:00:00");
        let mut value = serde_json::to_value(entry).unwrap();
        value["mode"] = serde_json::json!("ask");
        value["answer_text"] = serde_json::json!("Synthetic answer");
        value["ask_intent"] = serde_json::json!("ask_selection");
        let entry: HistoryEntry = serde_json::from_value(value).unwrap();
        source.add(entry).await.unwrap();
        let entries = source.list(10, 0).await.unwrap();
        let saved = source
            .read_saved_answer_sync(entries[0].id, &entries[0].raw_text, "Synthetic answer")
            .unwrap()
            .unwrap();
        assert_eq!(
            serde_json::to_value(saved).unwrap()["askIntent"],
            "ask_selection"
        );
        assert_eq!(
            serde_json::to_value(&entries[0]).unwrap()["ask_intent"],
            "ask_selection"
        );
        let target = temp_history_store("ask-intent-target");
        target
            .restore_backup_data(
                Some(entries),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-20T00:00:00",
            )
            .await
            .unwrap();
        assert_eq!(
            serde_json::to_value(&target.list(10, 0).await.unwrap()[0]).unwrap()["ask_intent"],
            "ask_selection"
        );
    }

    #[tokio::test]
    async fn saved_answer_snapshot_bounds_json_expansion_without_deleting_the_answer() {
        let reader = include_str!("mod.rs")
            .split("pub(crate) fn read_saved_answer_sync(")
            .nth(1)
            .unwrap()
            .split("pub(crate) fn hold_citation_connection_for_test")
            .next()
            .unwrap();
        assert!(
            reader.contains("serde_json::to_writer("),
            "Response bounds must use a non-buffering writer"
        );
        assert!(!reader.contains("serde_json::to_vec(&snapshot)"));
        let store = temp_history_store("saved-answer-response-limit");
        let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
        entry.mode = "ask".into();
        let answer = "\u{0001}".repeat(SAVED_ANSWER_MAX_BYTES / 6 + 1);
        entry.answer_text = Some(answer.clone());
        let id = store
            .add_with_metadata_sync(entry, &HistoryRetentionPolicy::default(), None, None)
            .unwrap()
            .unwrap();
        assert!(validate_saved_answer_input(id, "raw 0", &answer).is_ok());
        assert!(store.read_saved_answer_sync(id, "raw 0", &answer).is_err());
        assert!(store.get(id).await.unwrap().is_some());
    }

    #[test]
    fn saved_answer_input_counts_utf8_bytes_and_keeps_large_legacy_questions() {
        assert!(validate_saved_answer_input(1, &"x".repeat(501), "Answer").is_ok());
        assert!(validate_saved_answer_input(1, "", &"x".repeat(SAVED_ANSWER_MAX_BYTES)).is_ok());
        assert!(validate_saved_answer_input(1, "x", &"x".repeat(SAVED_ANSWER_MAX_BYTES)).is_err());
        assert!(
            validate_saved_answer_input(1, "", &"😀".repeat(SAVED_ANSWER_MAX_BYTES / 4 + 1))
                .is_err()
        );
        assert!(validate_saved_answer_input(0, "Question", "Answer").is_err());
        assert!(validate_saved_answer_input(1, "Question", " \n ").is_err());
    }

    #[tokio::test]
    async fn saved_answer_sources_are_byte_bounded_before_sqlite_projection() {
        let source = include_str!("mod.rs")
            .split("pub(crate) fn read_saved_answer_sync(")
            .nth(1)
            .unwrap()
            .split("pub(crate) fn hold_citation_connection_for_test")
            .next()
            .unwrap();
        assert!(
            source.contains("length(CAST(sources AS BLOB))"),
            "SQLite projection must enforce source byte bounds"
        );
        let store = temp_history_store("saved-answer-source-bytes");
        let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        let id = store
            .add_with_metadata_sync(entry, &HistoryRetentionPolicy::default(), None, None)
            .unwrap()
            .unwrap();
        let sources = serde_json::json!([{
            "url":"https://example.invalid/source",
            "title":"Source",
            "content":"字".repeat(34000)
        }])
        .to_string();
        assert!(sources.chars().count() < 100000);
        assert!(sources.len() > 100000);
        store
            .conn
            .lock()
            .unwrap()
            .execute(
                "INSERT INTO history_citations(history_id, sources) VALUES (?1, ?2)",
                rusqlite::params![id, sources],
            )
            .unwrap();
        assert!(store
            .read_saved_answer_sync(id, "raw 0", "Synthetic answer")
            .is_err());
        assert!(store.get(id).await.unwrap().is_some());
    }

    #[tokio::test]
    async fn saved_answer_snapshot_rejects_corrupt_sources_without_changing_history() {
        let store = temp_history_store("saved-answer-corrupt");
        let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        let id = store
            .add_with_metadata_sync(entry, &HistoryRetentionPolicy::default(), None, None)
            .unwrap()
            .unwrap();
        for sources in [
            "not-json",
            r#"{"url":"https://example.invalid"}"#,
            r#"[{"url":"javascript:bad","title":"Source"}]"#,
        ] {
            store
                .conn
                .lock()
                .unwrap()
                .execute(
                    "INSERT OR REPLACE INTO history_citations(history_id, sources) VALUES (?1, ?2)",
                    rusqlite::params![id, sources],
                )
                .unwrap();
            assert!(store
                .read_saved_answer_sync(id, "raw 0", "Synthetic answer")
                .is_err());
        }
        assert_eq!(store.list(10, 0).await.unwrap().len(), 1);
        assert_eq!(
            store.get(id).await.unwrap().unwrap().answer_text.as_deref(),
            Some("Synthetic answer")
        );
    }

    #[tokio::test]
    async fn saved_answer_snapshot_matches_content_and_disappears_after_delete() {
        let store = temp_history_store("saved-answer-snapshot");
        let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
        entry.mode = "ask".into();
        entry.raw_text = "Original question".into();
        entry.polished_text = "Display question".into();
        entry.answer_text = Some("Synthetic answer".into());
        let id = store
            .add_with_metadata_sync(entry, &HistoryRetentionPolicy::default(), None, None)
            .unwrap()
            .unwrap();
        let snapshot = store
            .read_saved_answer_sync(id, "Original question", "Synthetic answer")
            .unwrap()
            .expect("Matching saved answer must exist even without citations");
        assert_eq!(snapshot.question, "Original question");
        assert_eq!(snapshot.citations, serde_json::json!([]));
        let serialized = serde_json::to_value(&snapshot).unwrap();
        assert_eq!(serialized.as_object().unwrap().len(), 4);
        assert!(store
            .read_saved_answer_sync(id, "Display question", "Synthetic answer")
            .unwrap()
            .is_none());
        assert!(store
            .read_saved_answer_sync(id, "Original question", "Other answer")
            .unwrap()
            .is_none());
        store
            .conn
            .lock()
            .unwrap()
            .execute(
                "INSERT INTO history_citations(history_id, sources) VALUES (?1, ?2)",
                rusqlite::params![
                    id,
                    r#"[{"url":"https://example.invalid/source","title":"Synthetic source"}]"#
                ],
            )
            .unwrap();
        assert_eq!(
            store
                .read_saved_answer_sync(id, "Original question", "Synthetic answer")
                .unwrap()
                .unwrap()
                .citations,
            serde_json::json!([
                {"url":"https://example.invalid/source","title":"Synthetic source"}
            ])
        );
        store
            .conn
            .lock()
            .unwrap()
            .execute("UPDATE history SET mode = 'dictate' WHERE id = ?1", [id])
            .unwrap();
        assert!(store
            .read_saved_answer_sync(id, "Original question", "Synthetic answer")
            .unwrap()
            .is_none());
        store
            .conn
            .lock()
            .unwrap()
            .execute("UPDATE history SET mode = 'ask' WHERE id = ?1", [id])
            .unwrap();
        store.delete(id).await.unwrap();
        assert!(store
            .read_saved_answer_sync(id, "Original question", "Synthetic answer")
            .unwrap()
            .is_none());
    }

    #[tokio::test]
    async fn normalized_citation_url_cannot_make_saved_sources_unreadable() {
        let store = temp_history_store("citation-normalized-length");
        let mut entry = test_history_entry(0, "2026-11-20T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        let raw_url = format!("https://example.invalid/{}", "字".repeat(300));
        assert!(raw_url.len() < 2048);
        entry.citations = Some(serde_json::json!([{"url":raw_url,"title":"Source"}]));
        assert!(store.add(entry).await.is_err());
        assert!(store.list(10, 0).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn citation_storage_and_restore_keep_only_source_identity() {
        let store = temp_history_store("citation-minimal");
        let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        entry.citations = Some(serde_json::json!([
            {
                "url":"https://example.invalid/source",
                "title":" Source ",
                "content":"synthetic private page text",
                "token":"synthetic credential",
                "nested":{"window_title":"synthetic private title"}
            },
            {"url":"https://example.invalid/source","title":"Duplicate"}
        ]));
        let expected = serde_json::json!([
            {"url":"https://example.invalid/source","title":"Source"}
        ]);
        store.add(entry.clone()).await.unwrap();
        assert_eq!(
            store.list(10, 0).await.unwrap()[0].citations,
            Some(expected.clone())
        );
        store
            .restore_backup_data(
                Some(vec![entry]),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-19T00:00:00",
            )
            .await
            .unwrap();
        assert_eq!(
            store.list(10, 0).await.unwrap()[0].citations,
            Some(expected)
        );
    }

    #[tokio::test]
    async fn ordinary_history_insert_rejects_invalid_citations_without_changing_history() {
        let store = temp_history_store("citation-insert-validation");
        store
            .add(test_history_entry(0, "2026-11-19T00:00:00"))
            .await
            .unwrap();
        for sources in [
            serde_json::json!([{"url":"javascript:alert(1)","title":"Unsafe"}]),
            serde_json::json!([{"url":"https://example.invalid","title":"x".repeat(501)}]),
            serde_json::json!(vec![
                serde_json::json!({
                    "url":"https://example.invalid","title":"Source"
                });
                21
            ]),
        ] {
            for explicit_metadata in [true, false] {
                let mut entry = test_history_entry(1, "2026-11-19T00:00:00");
                entry.mode = "ask".into();
                entry.answer_text = Some("Synthetic answer".into());
                let metadata = if explicit_metadata {
                    Some(sources.to_string())
                } else {
                    entry.citations = Some(sources.clone());
                    None
                };
                assert!(store
                    .add_with_metadata_sync(
                        entry,
                        &HistoryRetentionPolicy::default(),
                        None,
                        metadata,
                    )
                    .is_err());
                let history = store.list(10, 0).await.unwrap();
                assert_eq!(history.len(), 1);
                assert_eq!(history[0].raw_text, "raw 0");
            }
        }
    }

    #[tokio::test]
    async fn invalid_history_citations_roll_back_the_answer_insert() {
        let store = temp_history_store("citation-rollback");
        let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        assert!(store
            .add_with_metadata_sync(
                entry,
                &HistoryRetentionPolicy::default(),
                None,
                Some("not JSON".into()),
            )
            .is_err());
        assert!(store.list(10, 0).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn saved_citation_supports_reopen_and_invalidate_with_the_answer() {
        let store = temp_history_store("support-history");
        let url = "https://example.invalid/source";
        let answer = format!("Read {url}");
        let mut entry = test_history_entry(0, "2026-11-21T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some(answer.clone());
        let supports = serde_json::json!([{"start":5,"end":answer.len(),"sourceIndex":0}]);
        let id = store
            .add_with_supports_sync(
                entry,
                &HistoryRetentionPolicy::default(),
                None,
                Some(serde_json::json!([{"url":url,"title":"Source"}]).to_string()),
                Some(supports.clone()),
            )
            .unwrap()
            .unwrap();
        let saved = store
            .read_saved_answer_sync(id, "raw 0", &answer)
            .unwrap()
            .unwrap();
        assert_eq!(saved.citation_supports, Some(supports));
        {
            let conn = store.conn.lock().unwrap();
            conn.execute(
                "UPDATE history SET answer_text = 'Changed' WHERE id = ?1",
                [id],
            )
            .unwrap();
            let remaining: i64 = conn
                .query_row(
                    "SELECT count(*) FROM history_citation_supports",
                    [],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(remaining, 0);
        }
        assert!(store
            .read_saved_answer_sync(id, "raw 0", "Changed")
            .unwrap()
            .unwrap()
            .citation_supports
            .is_none());
    }

    #[test]
    fn saved_citation_supports_survive_database_reopening_and_reject_corruption() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-support-reopen-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let url = "https://example.invalid/source";
        let answer = format!("[Source]({url})");
        let supports = serde_json::json!([{"start":0,"end":answer.len(),"sourceIndex":0}]);
        let id = {
            let store = HistoryStore::new(path.clone()).unwrap();
            let mut entry = test_history_entry(0, "2026-11-21T00:00:00");
            entry.mode = "ask".into();
            entry.answer_text = Some(answer.clone());
            store
                .add_with_supports_sync(
                    entry,
                    &HistoryRetentionPolicy::default(),
                    None,
                    Some(serde_json::json!([{"url":url,"title":"Source"}]).to_string()),
                    Some(supports.clone()),
                )
                .unwrap()
                .unwrap()
        };
        let store = HistoryStore::new(path).unwrap();
        let snapshot = store
            .read_saved_answer_sync(id, "raw 0", &answer)
            .unwrap()
            .unwrap();
        assert_eq!(snapshot.citation_supports, Some(supports));
        for corrupt in [
            "not JSON",
            "[{\"start\":0,\"end\":4,\"sourceIndex\":0}]",
            "[{\"start\":0,\"end\":4,\"sourceIndex\":99}]",
            "null",
        ] {
            store
                .conn
                .lock()
                .unwrap()
                .execute(
                    "UPDATE history_citation_supports SET supports = ?1 WHERE history_id = ?2",
                    rusqlite::params![corrupt, id],
                )
                .unwrap();
            assert!(store.read_saved_answer_sync(id, "raw 0", &answer).is_err());
        }
    }

    #[tokio::test]
    async fn invalid_citation_supports_never_insert_history() {
        let store = temp_history_store("support-history-invalid");
        let mut entry = test_history_entry(0, "2026-11-21T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        assert!(store
            .add_with_supports_sync(
                entry,
                &HistoryRetentionPolicy::default(),
                None,
                Some(
                    serde_json::json!([{"url":"https://example.invalid","title":"Source"}])
                        .to_string()
                ),
                Some(serde_json::json!([{"start":0,"end":9,"sourceIndex":0}])),
            )
            .is_err());
        assert!(store.list(10, 0).await.unwrap().is_empty());
    }

    #[tokio::test]
    async fn citation_supports_survive_history_export_and_backup_restore() {
        let source = temp_history_store("support-backup-source");
        let target = temp_history_store("support-backup-target");
        let url = "https://example.invalid/source";
        let answer = format!("Read {url}");
        let mut entry = test_history_entry(0, "2026-11-21T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some(answer.clone());
        entry.citations = Some(serde_json::json!([{"url":url,"title":"Source"}]));
        let supports = serde_json::json!([{"start":5,"end":answer.len(),"sourceIndex":0}]);
        entry.citation_supports = Some(supports.clone());
        source.add(entry).await.unwrap();
        let exported = source.list(10, 0).await.unwrap();
        assert_eq!(exported[0].citation_supports, Some(supports.clone()));
        let encoded = serde_json::to_string(&exported).unwrap();
        let decoded = serde_json::from_str(&encoded).unwrap();
        target
            .restore_backup_data(
                Some(decoded),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-21T00:00:00",
            )
            .await
            .unwrap();
        let restored = target.list(10, 0).await.unwrap();
        assert_eq!(restored[0].citation_supports, Some(supports.clone()));
        assert_eq!(
            target
                .read_saved_answer_sync(restored[0].id, "raw 0", &answer)
                .unwrap()
                .unwrap()
                .citation_supports,
            Some(supports)
        );
    }

    #[tokio::test]
    async fn invalid_backup_citations_preserve_existing_history_transactionally() {
        let target = temp_history_store("citation-backup-invalid");
        target
            .add(test_history_entry(0, "2026-11-19T00:00:00"))
            .await
            .unwrap();
        let before = target.list(10, 0).await.unwrap();
        let mut invalid = test_history_entry(1, "2026-11-19T00:00:00");
        invalid.mode = "ask".into();
        invalid.answer_text = Some("Synthetic answer".into());
        invalid.citations = Some(serde_json::json!([
            {"url":"javascript:alert(1)","title":"Unsafe"}
        ]));
        assert!(target
            .restore_backup_data(
                Some(vec![invalid]),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-19T00:00:00",
            )
            .await
            .is_err());
        let after = target.list(10, 0).await.unwrap();
        assert_eq!(after.len(), 1);
        assert_eq!(after[0].id, before[0].id);
        assert_eq!(after[0].raw_text, before[0].raw_text);
    }

    #[tokio::test]
    async fn invalid_backup_supports_roll_back_existing_answers_sources_and_ranges() {
        let target = temp_history_store("support-backup-rollback");
        let url = "https://example.invalid/source";
        let answer = format!("Read {url}");
        let mut original = test_history_entry(0, "2026-11-21T00:00:00");
        original.mode = "ask".into();
        original.answer_text = Some(answer.clone());
        original.citations = Some(serde_json::json!([{"url":url,"title":"Source"}]));
        original.citation_supports =
            Some(serde_json::json!([{"start":5,"end":answer.len(),"sourceIndex":0}]));
        target.add(original).await.unwrap();
        let before = target.list(10, 0).await.unwrap();
        let saved_before = serde_json::to_value(
            target
                .read_saved_answer_sync(before[0].id, "raw 0", &answer)
                .unwrap()
                .unwrap(),
        )
        .unwrap();
        let mut invalid = before[0].clone();
        invalid.raw_text = "Replacement question".into();
        invalid.citation_supports = Some(serde_json::json!([{"start":0,"end":4,"sourceIndex":0}]));
        assert!(target
            .restore_backup_data(
                Some(vec![invalid]),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-21T00:00:00",
            )
            .await
            .is_err());
        assert_eq!(
            serde_json::to_value(target.list(10, 0).await.unwrap()).unwrap(),
            serde_json::to_value(&before).unwrap()
        );
        let saved_after = serde_json::to_value(
            target
                .read_saved_answer_sync(before[0].id, "raw 0", &answer)
                .unwrap()
                .unwrap(),
        )
        .unwrap();
        assert_eq!(saved_after, saved_before);
    }

    #[tokio::test]
    async fn backup_restore_preserves_citations_with_new_history_ids() {
        let source = temp_history_store("citation-backup-source");
        let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        let original_id = source
            .add_with_metadata_sync(
                entry,
                &HistoryRetentionPolicy::default(),
                None,
                Some(r#"[{"url":"https://example.invalid/source","title":"Source"}]"#.into()),
            )
            .unwrap()
            .unwrap();
        let entries = source.list(10, 0).await.unwrap();
        assert!(entries[0].citations.is_some());
        let target = temp_history_store("citation-backup-target");
        target
            .add(test_history_entry(0, "2026-11-19T00:00:00"))
            .await
            .unwrap();
        target
            .restore_backup_data(
                Some(entries),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-19T00:00:00",
            )
            .await
            .unwrap();
        let restored = target.list(10, 0).await.unwrap();
        assert_eq!(restored.len(), 1);
        assert_ne!(restored[0].id, original_id);
        let sources = target
            .read_citations_sync(restored[0].id, "raw 0", "Synthetic answer")
            .unwrap()
            .unwrap();
        assert_eq!(
            serde_json::from_str::<serde_json::Value>(&sources).unwrap(),
            restored[0].citations.clone().unwrap()
        );
    }

    #[tokio::test]
    async fn history_citations_are_invalidated_by_content_changes_not_metadata_updates() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-citation-invalidation-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let store = HistoryStore::new(path.clone()).unwrap();
        let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
        entry.mode = "ask".into();
        entry.answer_text = Some("Synthetic answer".into());
        let id = store
            .add_with_metadata_sync(
                entry,
                &HistoryRetentionPolicy::default(),
                None,
                Some(r#"[{"url":"https://example.invalid","title":"Source"}]"#.into()),
            )
            .unwrap()
            .unwrap();
        let connection = Connection::open(path).unwrap();
        connection
            .execute("UPDATE history SET duration_ms = 42 WHERE id = ?1", [id])
            .unwrap();
        connection
            .execute(
                "UPDATE history SET raw_text = raw_text, answer_text = answer_text, mode = mode
                 WHERE id = ?1",
                [id],
            )
            .unwrap();
        assert!(store
            .read_citations_sync(id, "raw 0", "Synthetic answer")
            .unwrap()
            .is_some());
        for assignment in [
            "raw_text = 'Changed question'",
            "answer_text = 'Changed answer'",
            "answer_text = NULL",
            "mode = 'dictate'",
        ] {
            connection
                .execute(
                    &format!("UPDATE history SET {assignment} WHERE id = ?1"),
                    [id],
                )
                .unwrap();
            let count: i64 = connection
                .query_row(
                    "SELECT count(*) FROM history_citations WHERE history_id = ?1",
                    [id],
                    |row| row.get(0),
                )
                .unwrap();
            assert_eq!(count, 0, "{assignment}");
            connection
                .execute(
                    "UPDATE history SET raw_text = 'raw 0', answer_text = 'Synthetic answer',
                     mode = 'ask' WHERE id = ?1",
                    [id],
                )
                .unwrap();
            assert!(store
                .read_citations_sync(id, "raw 0", "Synthetic answer")
                .unwrap()
                .is_none());
            connection
                .execute(
                    "INSERT INTO history_citations(history_id, sources) VALUES (?1, '[]')",
                    [id],
                )
                .unwrap();
        }
    }

    #[tokio::test]
    async fn history_citations_reopen_and_delete_with_their_owned_answer() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-citations-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let id = {
            let store = HistoryStore::new(path.clone()).unwrap();
            let mut entry = test_history_entry(0, "2026-11-19T00:00:00");
            entry.mode = "ask".into();
            entry.answer_text = Some("Synthetic answer".into());
            store
                .add_with_metadata_sync(
                    entry,
                    &HistoryRetentionPolicy::default(),
                    None,
                    Some(r#"[{"url":"https://example.invalid","title":"Source"}]"#.into()),
                )
                .unwrap()
                .unwrap()
        };
        let store = HistoryStore::new(path.clone()).unwrap();
        assert!(store
            .read_citations_sync(id, "raw 0", "Synthetic answer")
            .unwrap()
            .is_some());
        assert!(store
            .read_citations_sync(id, "Other question", "Synthetic answer")
            .unwrap()
            .is_none());
        store.delete(id).await.unwrap();
        assert!(store
            .read_citations_sync(id, "raw 0", "Synthetic answer")
            .unwrap()
            .is_none());
        let conn = Connection::open(path).unwrap();
        let count: i64 = conn
            .query_row("SELECT count(*) FROM history_citations", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 0);
    }

    #[tokio::test]
    async fn shared_database_reopen_preserves_history_audio_dictionary_and_corrections() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-shared-reopen-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let (history_id, correction_id) = {
            let history = HistoryStore::new(path.clone()).unwrap();
            let dictionary = DictionaryStore::new(path.clone()).unwrap();
            let mut entry = test_history_entry(0, "2026-11-10T10:00:00");
            entry.mode = "ask".to_string();
            entry.answer_text = Some("Synthetic persisted answer".to_string());
            history
                .add_with_audio(
                    entry,
                    &HistoryRetentionPolicy::default(),
                    Some(audio.clone()),
                )
                .await
                .unwrap();
            dictionary.add("Manual word", Some("manual")).await.unwrap();
            dictionary.add_auto("Automatic word").await.unwrap();
            dictionary
                .add_correction("old phrase", "new phrase")
                .await
                .unwrap();
            let correction_id = dictionary.correction_rules().await.unwrap()[0].id;
            dictionary
                .set_correction_enabled(correction_id, false)
                .await
                .unwrap();
            let history_id = history.list(10, 0).await.unwrap()[0].id;
            (history_id, correction_id)
        };
        let dictionary = DictionaryStore::new(path.clone()).unwrap();
        let history = HistoryStore::new(path.clone()).unwrap();
        let entries = history.list(10, 0).await.unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].id, history_id);
        assert_eq!(entries[0].raw_text, "raw 0");
        assert_eq!(entries[0].polished_text, "polished 0");
        assert_eq!(entries[0].mode, "ask");
        assert_eq!(
            entries[0].answer_text.as_deref(),
            Some("Synthetic persisted answer")
        );
        assert!(entries[0].has_audio);
        assert_eq!(history.read_audio(history_id).await.unwrap(), Some(audio));
        let words = dictionary.list().await.unwrap();
        assert_eq!(words.len(), 2);
        assert!(words.iter().any(|word| word.word == "Manual word"
            && word.pronunciation.as_deref() == Some("manual")
            && !word.auto));
        assert!(words
            .iter()
            .any(|word| word.word == "Automatic word" && word.auto));
        let corrections = dictionary.correction_rules().await.unwrap();
        assert_eq!(corrections.len(), 1);
        assert_eq!(corrections[0].id, correction_id);
        assert_eq!(corrections[0].pattern, "old phrase");
        assert_eq!(corrections[0].replacement, "new phrase");
        assert!(!corrections[0].enabled);
    }

    #[tokio::test]
    async fn failed_dictionary_import_preserves_shared_database_after_reopen() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-import-reopen-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let audio =
            crate::stt::whisper_compat::WhisperCompatProvider::build_wav(&[1, 0, 2, 0], 16000);
        let history_id = {
            let history = HistoryStore::new(path.clone()).unwrap();
            let dictionary = DictionaryStore::new(path.clone()).unwrap();
            history
                .add_with_audio(
                    test_history_entry(0, "2026-11-10T10:00:00"),
                    &HistoryRetentionPolicy::default(),
                    Some(audio.clone()),
                )
                .await
                .unwrap();
            dictionary
                .add("Existing word", Some("existing"))
                .await
                .unwrap();
            dictionary
                .add_correction("existing phrase", "corrected phrase")
                .await
                .unwrap();
            dictionary
                .execute_batch_for_test(
                    "CREATE TRIGGER reject_import BEFORE INSERT ON dictionary
                     WHEN NEW.word = 'rejected'
                     BEGIN SELECT RAISE(ABORT, 'synthetic import failure'); END;",
                )
                .unwrap();
            let parsed = crate::dictionary_io::parse_dictionary_import(
                b"first\nrejected",
                crate::dictionary_io::ImportFormat::Txt,
            )
            .unwrap();
            assert_eq!(
                crate::dictionary_io::commit_dictionary_import(&dictionary, parsed).await,
                Err(crate::dictionary_io::DictionaryImportError::Database)
            );
            history.list(10, 0).await.unwrap()[0].id
        };
        let history = HistoryStore::new(path.clone()).unwrap();
        let dictionary = DictionaryStore::new(path).unwrap();
        let entries = history.list(10, 0).await.unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].id, history_id);
        assert_eq!(entries[0].raw_text, "raw 0");
        assert_eq!(entries[0].polished_text, "polished 0");
        assert!(entries[0].has_audio);
        assert_eq!(history.read_audio(history_id).await.unwrap(), Some(audio));
        let words = dictionary.list().await.unwrap();
        assert_eq!(words.len(), 1);
        assert_eq!(words[0].word, "Existing word");
        assert_eq!(words[0].pronunciation.as_deref(), Some("existing"));
        let corrections = dictionary.correction_rules().await.unwrap();
        assert_eq!(corrections.len(), 1);
        assert_eq!(corrections[0].pattern, "existing phrase");
        assert_eq!(corrections[0].replacement, "corrected phrase");
        assert!(corrections[0].enabled);
    }

    #[tokio::test]
    async fn history_store_prunes_by_max_entries_policy() {
        let store = temp_history_store("max");
        let policy = HistoryRetentionPolicy {
            enabled: true,
            max_entries: 2,
            retention_days: 0,
        };

        for id in 1..=3 {
            store
                .add_with_policy(
                    test_history_entry(id, &format!("2026-07-0{id}T00:00:00")),
                    &policy,
                )
                .await
                .unwrap();
        }

        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].polished_text, "polished 3");
        assert_eq!(entries[1].polished_text, "polished 2");
    }

    #[tokio::test]
    async fn history_store_prunes_by_retention_days_policy() {
        let store = temp_history_store("days");
        let policy = HistoryRetentionPolicy {
            enabled: true,
            max_entries: 5000,
            retention_days: 7,
        };

        store
            .add_with_policy(test_history_entry(1, "2026-06-25T00:00:00"), &policy)
            .await
            .unwrap();
        store
            .add_with_policy(test_history_entry(2, "2026-07-01T00:00:00"), &policy)
            .await
            .unwrap();
        store
            .prune_with_policy(&policy, "2026-07-05T00:00:00")
            .await
            .unwrap();

        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].polished_text, "polished 2");
    }

    #[tokio::test]
    async fn backup_restore_orders_extended_years_by_time_not_text() {
        let (history, _dictionary) = temp_backup_stores("extended-year-order");
        history
            .restore_backup_data(
                Some(vec![
                    test_history_entry(1, "9999-01-01T10:00:00"),
                    test_history_entry(2, "+10000-01-01T10:00:00"),
                ]),
                None,
                None,
                &HistoryRetentionPolicy {
                    max_entries: 1,
                    ..HistoryRetentionPolicy::default()
                },
                "2026-11-10T10:00:00",
            )
            .await
            .unwrap();
        let restored = history.list(100, 0).await.unwrap();
        assert_eq!(restored.len(), 1);
        assert_eq!(restored[0].raw_text, "raw 2");
    }

    #[tokio::test]
    async fn backup_restore_retains_newest_entries_from_unsorted_input() {
        let (history, _dictionary) = temp_backup_stores("unsorted");
        history
            .restore_backup_data(
                Some(vec![
                    test_history_entry(1, "2026-11-01T10:00:00"),
                    test_history_entry(3, "2026-11-03T10:00:00"),
                    test_history_entry(2, "2026-11-02T10:00:00"),
                ]),
                None,
                None,
                &HistoryRetentionPolicy {
                    max_entries: 2,
                    ..HistoryRetentionPolicy::default()
                },
                "2026-11-10T10:00:00",
            )
            .await
            .unwrap();
        let restored = history.list(100, 0).await.unwrap();
        assert_eq!(restored.len(), 2);
        assert_eq!(restored[0].raw_text, "raw 3");
        assert_eq!(restored[1].raw_text, "raw 2");
    }

    #[tokio::test]
    async fn backup_restore_preserves_ask_answers_and_translation_modes() {
        let (history, _dictionary) = temp_backup_stores("voice-modes");
        let mut ask = test_history_entry(1, "2026-11-01T10:00:00");
        ask.mode = "ask".to_string();
        ask.answer_text = Some("Synthetic saved answer".to_string());
        let mut translation = test_history_entry(2, "2026-11-02T10:00:00");
        translation.mode = "translate".to_string();
        history
            .restore_backup_data(
                Some(vec![translation, ask]),
                None,
                None,
                &HistoryRetentionPolicy::default(),
                "2026-11-10T10:00:00",
            )
            .await
            .unwrap();
        let restored = history.list(100, 0).await.unwrap();
        assert_eq!(restored[0].mode, "translate");
        assert_eq!(restored[1].mode, "ask");
        assert_eq!(
            restored[1].answer_text.as_deref(),
            Some("Synthetic saved answer")
        );
        assert!(!restored[0].has_audio);
        assert!(!restored[1].has_audio);
    }

    #[tokio::test]
    async fn backup_restore_replaces_all_requested_data_in_one_transaction() {
        let (history, dictionary) = temp_backup_stores("replace");
        history
            .add(test_history_entry(99, "2026-07-01T00:00:00"))
            .await
            .unwrap();
        dictionary.add("Old word", None).await.unwrap();
        dictionary
            .add_correction("old phrase", "Old phrase")
            .await
            .unwrap();
        let policy = HistoryRetentionPolicy {
            enabled: true,
            max_entries: 2,
            retention_days: 0,
        };

        history
            .restore_backup_data(
                Some(vec![
                    test_history_entry(3, "2026-07-03T00:00:00"),
                    test_history_entry(2, "2026-07-02T00:00:00"),
                    test_history_entry(1, "2026-07-01T00:00:00"),
                ]),
                Some(vec![
                    DictionaryEntry {
                        id: 20,
                        word: "OpenTypeless".to_string(),
                        pronunciation: None,
                        auto: true,
                    },
                    DictionaryEntry {
                        id: 21,
                        word: " opentypeless ".to_string(),
                        pronunciation: Some("duplicate".to_string()),
                        auto: false,
                    },
                ]),
                Some(vec![CorrectionRule {
                    id: 30,
                    pattern: "open type less".to_string(),
                    replacement: "OpenTypeless".to_string(),
                    enabled: false,
                }]),
                &policy,
                "2026-07-13T00:00:00",
            )
            .await
            .unwrap();

        let restored_history = history.list(10, 0).await.unwrap();
        assert_eq!(restored_history.len(), 2);
        assert_eq!(restored_history[0].polished_text, "polished 3");
        assert_eq!(restored_history[1].polished_text, "polished 2");
        let restored_dictionary = dictionary.list().await.unwrap();
        assert_eq!(restored_dictionary.len(), 1);
        assert_eq!(restored_dictionary[0].word, "OpenTypeless");
        assert!(restored_dictionary[0].auto);
        let restored_rules = dictionary.correction_rules().await.unwrap();
        assert_eq!(restored_rules.len(), 1);
        assert!(!restored_rules[0].enabled);
    }

    #[tokio::test]
    async fn backup_restore_rejects_invalid_retention_time_without_replacing_data() {
        let (history, dictionary) = temp_backup_stores("invalid-retention-time");
        history
            .add(test_history_entry(1, "2026-07-01T00:00:00"))
            .await
            .unwrap();
        dictionary.add("Existing", None).await.unwrap();
        let result = history
            .restore_backup_data(
                Some(vec![test_history_entry(2, "2026-07-02T00:00:00")]),
                Some(vec![DictionaryEntry {
                    id: 2,
                    word: "Replacement".to_string(),
                    pronunciation: None,
                    auto: false,
                }]),
                None,
                &HistoryRetentionPolicy {
                    retention_days: 7,
                    ..HistoryRetentionPolicy::default()
                },
                "invalid",
            )
            .await;
        assert!(result.is_err());
        assert_eq!(history.list(10, 0).await.unwrap()[0].raw_text, "raw 1");
        assert_eq!(dictionary.list().await.unwrap()[0].word, "Existing");
    }

    #[tokio::test]
    async fn invalid_backup_dictionary_leaves_existing_data_unchanged() {
        let (history, dictionary) = temp_backup_stores("validation");
        history
            .add(test_history_entry(1, "2026-07-01T00:00:00"))
            .await
            .unwrap();
        dictionary.add("Existing", None).await.unwrap();

        let result = history
            .restore_backup_data(
                Some(vec![test_history_entry(2, "2026-07-02T00:00:00")]),
                Some(vec![DictionaryEntry {
                    id: 2,
                    word: "x".repeat(101),
                    pronunciation: None,
                    auto: false,
                }]),
                None,
                &HistoryRetentionPolicy::default(),
                "2026-07-13T00:00:00",
            )
            .await;

        assert_eq!(result.unwrap_err().to_string(), "dictionary_word_too_long");
        assert_eq!(history.list(10, 0).await.unwrap()[0].raw_text, "raw 1");
        assert_eq!(dictionary.list().await.unwrap()[0].word, "Existing");
    }

    #[tokio::test]
    async fn history_store_persists_active_scene_diagnostics() {
        let store = temp_history_store("scene-diagnostics");
        let mut entry = test_history_entry(1, "2026-07-01T00:00:00");
        entry.active_scene_id = Some("builtin_meeting_notes".to_string());
        entry.active_scene_source = Some("builtin".to_string());
        entry.active_scene_name = Some("Meeting Notes".to_string());
        entry.active_scene_prompt_chars = Some(128);
        entry.active_scene_prompt_truncated = false;

        store.add(entry).await.unwrap();

        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(
            entries[0].active_scene_id.as_deref(),
            Some("builtin_meeting_notes")
        );
        assert_eq!(entries[0].active_scene_source.as_deref(), Some("builtin"));
        assert_eq!(
            entries[0].active_scene_name.as_deref(),
            Some("Meeting Notes")
        );
        assert_eq!(entries[0].active_scene_prompt_chars, Some(128));
        assert!(!entries[0].active_scene_prompt_truncated);
    }

    #[tokio::test]
    async fn history_store_persists_output_status_diagnostics() {
        let store = temp_history_store("output-diagnostics");
        let mut entry = test_history_entry(1, "2026-07-01T00:00:00");
        entry.output_status = Some("partial".to_string());
        entry.output_error = Some("LLM failed after partial streaming insert".to_string());

        store.add(entry).await.unwrap();

        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(entries[0].output_status.as_deref(), Some("partial"));
        assert_eq!(
            entries[0].output_error.as_deref(),
            Some("LLM failed after partial streaming insert")
        );
    }

    #[tokio::test]
    async fn history_store_persists_browser_access_status_without_raw_url() {
        let store = temp_history_store("browser-access-status");
        let mut entry = test_history_entry(1, "2026-07-01T00:00:00");
        entry.context_profile_id = "general.browser".to_string();
        entry.context_label = "Browser".to_string();
        entry.browser_access_status = BrowserAccessStatus::NeedsPermission;

        store.add(entry).await.unwrap();

        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(
            entries[0].browser_access_status,
            BrowserAccessStatus::NeedsPermission
        );

        let conn = store.conn.lock().unwrap();
        let raw_value: Option<String> = conn
            .query_row(
                "SELECT browser_access_status FROM history WHERE id = 1",
                [],
                |row| row.get(0),
            )
            .unwrap();
        assert_eq!(raw_value.as_deref(), Some("needs_permission"));
        assert_ne!(raw_value.as_deref(), Some("mail.google.com"));
    }

    #[tokio::test]
    async fn history_store_migrates_legacy_history_table_scene_columns() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-history-test-legacy-scene-{}.sqlite",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        {
            let conn = Connection::open(&path).unwrap();
            conn.execute_batch(
                "CREATE TABLE history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    app_name TEXT NOT NULL DEFAULT '',
                    app_type TEXT NOT NULL DEFAULT '',
                    raw_text TEXT NOT NULL DEFAULT '',
                    polished_text TEXT NOT NULL DEFAULT '',
                    language TEXT,
                    duration_ms INTEGER
                );",
            )
            .unwrap();
        }

        let store = HistoryStore::new(path).unwrap();
        let mut entry = test_history_entry(1, "2026-07-01T00:00:00");
        entry.active_scene_id = Some("custom_focus".to_string());
        entry.active_scene_source = Some("custom".to_string());
        entry.active_scene_name = Some("Focus".to_string());
        entry.active_scene_prompt_chars = Some(64);
        entry.active_scene_prompt_truncated = true;

        store.add(entry).await.unwrap();

        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(entries[0].active_scene_id.as_deref(), Some("custom_focus"));
        assert!(entries[0].active_scene_prompt_truncated);
        assert!(entries[0].output_status.is_none());
        assert!(entries[0].output_error.is_none());
    }

    #[tokio::test]
    async fn history_context_migration_clears_raw_app_identity_and_uses_safe_profiles() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-history-test-legacy-context-{}.sqlite",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        {
            let conn = Connection::open(&path).unwrap();
            conn.execute_batch(
                "CREATE TABLE history (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    created_at TEXT NOT NULL,
                    app_name TEXT NOT NULL DEFAULT '',
                    app_type TEXT NOT NULL DEFAULT '',
                    raw_text TEXT NOT NULL DEFAULT '',
                    polished_text TEXT NOT NULL DEFAULT '',
                    language TEXT,
                    duration_ms INTEGER
                );
                INSERT INTO history (created_at, app_name, app_type, raw_text, polished_text)
                VALUES
                    ('2026-07-01T00:00:00', 'Gmail', 'Email', 'one', 'One'),
                    ('2026-07-02T00:00:00', 'Private Project Alpha', 'Document', 'two', 'Two');",
            )
            .unwrap();
        }

        let store = HistoryStore::new(path.clone()).unwrap();
        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(entries[1].context_profile_id, "email.gmail");
        assert_eq!(entries[1].context_label, "Gmail");
        assert_eq!(entries[1].context_icon_key, "gmail");
        assert_eq!(entries[1].context_family, ContextFamily::Email);
        assert_eq!(entries[1].provider_kind, HistoryProviderKind::Local);
        assert_eq!(entries[0].context_profile_id, "general.native");
        assert_eq!(entries[0].context_label, "General");

        let conn = Connection::open(path).unwrap();
        let raw_values: (String, String) = conn
            .query_row(
                "SELECT app_name, app_type FROM history WHERE id = 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(raw_values, (String::new(), String::new()));
    }

    #[tokio::test]
    async fn history_context_migration_new_writes_store_only_safe_context_metadata() {
        let store = temp_history_store("safe-context-write");
        let mut entry = test_history_entry(1, "2026-07-03T00:00:00");
        entry.context_profile_id = "dev.github".to_string();
        entry.context_label = "GitHub".to_string();
        entry.context_icon_key = "github".to_string();
        entry.context_family = ContextFamily::DeveloperCollaboration;
        entry.provider_kind = HistoryProviderKind::ManagedCloud;
        store.add(entry).await.unwrap();

        let entries = store.list(10, 0).await.unwrap();
        assert_eq!(entries[0].context_profile_id, "dev.github");
        assert_eq!(
            entries[0].context_family,
            ContextFamily::DeveloperCollaboration
        );
        assert_eq!(entries[0].provider_kind, HistoryProviderKind::ManagedCloud);

        let conn = store.conn.lock().unwrap();
        let raw_values: (String, String) = conn
            .query_row(
                "SELECT app_name, app_type FROM history WHERE id = 1",
                [],
                |row| Ok((row.get(0)?, row.get(1)?)),
            )
            .unwrap();
        assert_eq!(raw_values, (String::new(), String::new()));
    }

    #[tokio::test]
    async fn dictionary_store_persists_correction_rules() {
        let store = temp_dictionary_store("correction-rules");

        store.add_correction(" 拓肯 ", " Token ").await.unwrap();
        let rules = store.correction_rules().await.unwrap();

        assert_eq!(rules.len(), 1);
        assert_eq!(rules[0].pattern, "拓肯");
        assert_eq!(rules[0].replacement, "Token");
        assert!(rules[0].enabled);
    }

    #[tokio::test]
    async fn dictionary_store_ignores_disabled_correction_rules_for_prompt() {
        let store = temp_dictionary_store("correction-rules-disabled");

        store.add_correction("克劳德", "Claude").await.unwrap();
        let rules = store.correction_rules().await.unwrap();
        store
            .set_correction_enabled(rules[0].id, false)
            .await
            .unwrap();

        let enabled = store.enabled_correction_rules().await;

        assert!(enabled.is_empty());
    }

    #[tokio::test]
    async fn dictionary_store_updates_entries_and_rejects_normalized_duplicates() {
        let store = temp_dictionary_store("updates");
        store.add("Token", None).await.unwrap();
        store.add("TalkMore", Some("talk more")).await.unwrap();
        let entries = store.list().await.unwrap();

        store
            .update(entries[0].id, "OpenTypeless", Some("open typeless"))
            .await
            .unwrap();
        assert!(store
            .update(entries[0].id, "ＴＡＬＫＭＯＲＥ", None)
            .await
            .is_err());

        let updated = store.list().await.unwrap();
        assert_eq!(updated[0].word, "OpenTypeless");
        assert_eq!(updated[0].pronunciation.as_deref(), Some("open typeless"));
    }

    #[tokio::test]
    async fn dictionary_store_migrates_legacy_rows_as_manual_and_tracks_auto_words() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-dictionary-legacy-{}.sqlite",
            std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos()
        ));
        {
            let conn = Connection::open(&path).unwrap();
            conn.execute_batch(
                "CREATE TABLE dictionary (
                    id INTEGER PRIMARY KEY AUTOINCREMENT,
                    word TEXT NOT NULL,
                    pronunciation TEXT
                );
                INSERT INTO dictionary (word) VALUES ('Legacy');",
            )
            .unwrap();
        }
        let store = DictionaryStore::new(path.clone()).unwrap();
        let auto_id = store.add_auto("Learned").await.unwrap();
        assert!(store.add_auto(" learned ").await.is_err());

        let entries = store.list().await.unwrap();
        assert_eq!(entries.len(), 2);
        assert_eq!(entries[0].word, "Legacy");
        assert!(!entries[0].auto);
        assert_eq!(entries[1].id, auto_id);
        assert!(entries[1].auto);
        drop(store);
        assert!(DictionaryStore::new(path).unwrap().list().await.unwrap()[1].auto);
    }

    #[tokio::test]
    async fn auto_dictionary_guard_runs_under_database_lock_and_can_reject_insert() {
        let store = temp_dictionary_store("auto-guard");
        let result = store
            .add_auto_if_current("Cancelled word", || {
                assert!(store.conn.try_lock().is_err());
                false
            })
            .await
            .unwrap();
        assert!(result.is_none());
        assert!(store.list().await.unwrap().is_empty());
        let inserted = store
            .add_auto_if_current("Current word", || true)
            .await
            .unwrap();
        assert!(inserted.is_some());
        assert!(store.list().await.unwrap()[0].auto);
    }

    #[test]
    fn learning_setting_guard_holds_current_config_and_rejects_disabled_or_missing_state() {
        let cache = Mutex::new(Some(AppConfig {
            auto_learn_dictionary: true,
            ..Default::default()
        }));
        let committed = with_learning_setting_cache(&cache, || {
            assert!(cache.try_lock().is_err());
            Ok(7)
        })
        .unwrap();
        assert_eq!(committed, Some(7));
        cache
            .lock()
            .unwrap()
            .as_mut()
            .unwrap()
            .auto_learn_dictionary = false;
        assert_eq!(with_learning_setting_cache(&cache, || Ok(8)).unwrap(), None);
        *cache.lock().unwrap() = None;
        assert!(with_learning_setting_cache(&cache, || Ok(9)).is_err());
    }

    #[tokio::test]
    async fn dictionary_store_removes_many_entries_atomically() {
        let store = temp_dictionary_store("remove-many");
        store.add("One", None).await.unwrap();
        store.add("Two", None).await.unwrap();
        store.add("Three", None).await.unwrap();
        let ids = store
            .list()
            .await
            .unwrap()
            .iter()
            .map(|entry| entry.id)
            .collect::<Vec<_>>();

        assert_eq!(
            store.remove_many(&[ids[0], ids[2], 9_999]).await.unwrap(),
            2
        );
        let remaining = store.list().await.unwrap();
        assert_eq!(remaining.len(), 1);
        assert_eq!(remaining[0].word, "Two");
    }

    #[tokio::test]
    async fn dictionary_store_updates_correction_pair_and_enabled_state() {
        let store = temp_dictionary_store("correction-updates");
        store.add_correction("token", "Token").await.unwrap();
        store.add_correction("talk more", "TalkMore").await.unwrap();
        let rules = store.correction_rules().await.unwrap();

        store
            .update_correction(rules[0].id, "open type less", "OpenTypeless", false)
            .await
            .unwrap();
        assert!(store
            .update_correction(rules[0].id, "ＴＡＬＫ ＭＯＲＥ", "talkmore", true)
            .await
            .is_err());

        let updated = store.correction_rules().await.unwrap();
        assert_eq!(updated[0].pattern, "open type less");
        assert_eq!(updated[0].replacement, "OpenTypeless");
        assert!(!updated[0].enabled);
    }

    #[test]
    fn automatic_dictionary_insert_does_not_wait_for_connection_mutex() {
        let store = std::sync::Arc::new(temp_dictionary_store("learning-connection-busy"));
        let held_connection = store.conn.lock().unwrap();
        let worker_store = store.clone();
        let (finished_tx, finished_rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            finished_tx
                .send(worker_store.insert("Synthetic deferred learning", None, true))
                .unwrap();
        });
        let completion = finished_rx.recv_timeout(std::time::Duration::from_secs(1));
        drop(held_connection);
        worker.join().unwrap();
        let result = completion.expect("Automatic learning must not wait for the connection mutex");
        assert_eq!(result.unwrap_err().to_string(), "dictionary_busy");
        assert!(tauri::async_runtime::block_on(store.list())
            .unwrap()
            .is_empty());
        tauri::async_runtime::block_on(store.add("Synthetic manual word", None)).unwrap();
        assert_eq!(
            tauri::async_runtime::block_on(store.list()).unwrap().len(),
            1
        );
    }

    #[tokio::test]
    async fn dictionary_and_correction_updates_recheck_duplicates_after_writer_commit() {
        for correction in [false, true] {
            let path = std::env::temp_dir().join(format!(
                "dictionary-update-lock-{}.sqlite",
                uuid::Uuid::new_v4()
            ));
            let store = DictionaryStore::new(path.clone()).unwrap();
            store
                .add("Original word", Some("Original pronunciation"))
                .await
                .unwrap();
            store
                .add_correction("Original pattern", "Original replacement")
                .await
                .unwrap();
            store.set_correction_enabled(1, false).await.unwrap();
            let mut other = Connection::open(path).unwrap();
            let transaction = other
                .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
                .unwrap();
            let (started_tx, started_rx) = std::sync::mpsc::channel();
            let (finished_tx, finished_rx) = std::sync::mpsc::channel();
            let worker = std::thread::spawn(move || {
                started_tx.send(()).unwrap();
                let result = if correction {
                    tauri::async_runtime::block_on(store.update_correction(
                        1,
                        "ＴＡＲＧＥＴ",
                        "replacement",
                        true,
                    ))
                } else {
                    tauri::async_runtime::block_on(store.update(
                        1,
                        "ＴＡＲＧＥＴ",
                        Some("Changed pronunciation"),
                    ))
                };
                finished_tx.send(result).unwrap();
            });
            started_rx.recv().unwrap();
            let waiting = finished_rx.recv_timeout(std::time::Duration::from_millis(150));
            if correction {
                transaction
                    .execute(
                        "INSERT INTO correction_rules(pattern, replacement, enabled)
                         VALUES ('Target', 'Replacement', 0)",
                        [],
                    )
                    .unwrap();
            } else {
                transaction
                    .execute(
                        "INSERT INTO dictionary(word, pronunciation, auto)
                         VALUES ('Target', 'Target pronunciation', 0)",
                        [],
                    )
                    .unwrap();
            }
            transaction.commit().unwrap();
            let outcome = finished_rx
                .recv_timeout(std::time::Duration::from_secs(5))
                .unwrap();
            worker.join().unwrap();
            assert!(matches!(
                waiting,
                Err(std::sync::mpsc::RecvTimeoutError::Timeout)
            ));
            assert_eq!(
                outcome.unwrap_err().to_string(),
                if correction {
                    "correction_duplicate"
                } else {
                    "dictionary_duplicate"
                }
            );
            let word: (String, String) = other
                .query_row(
                    "SELECT word, pronunciation FROM dictionary WHERE id = 1",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(
                word,
                ("Original word".into(), "Original pronunciation".into())
            );
            let rule: (String, String, bool) = other
                .query_row(
                    "SELECT pattern, replacement, enabled FROM correction_rules WHERE id = 1",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
                )
                .unwrap();
            assert_eq!(
                rule,
                (
                    "Original pattern".into(),
                    "Original replacement".into(),
                    false
                )
            );
            assert!(other.is_autocommit());
        }
    }

    #[tokio::test]
    async fn automatic_dictionary_lock_wait_is_bounded_and_restores_connection_timeout() {
        let path =
            std::env::temp_dir().join(format!("learning-busy-{}.sqlite", uuid::Uuid::new_v4()));
        let store = DictionaryStore::new(path.clone()).unwrap();
        let original_timeout: i64 = store
            .conn
            .lock()
            .unwrap()
            .query_row("PRAGMA busy_timeout", [], |row| row.get(0))
            .unwrap();
        let mut other = Connection::open(path).unwrap();
        let transaction = other
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .unwrap();
        let (finished_tx, finished_rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            let outcome = store.insert("Synthetic busy learning", None, true);
            let timeout: i64 = store
                .conn
                .lock()
                .unwrap()
                .query_row("PRAGMA busy_timeout", [], |row| row.get(0))
                .unwrap();
            finished_tx.send((outcome, timeout)).unwrap();
        });
        let completion = finished_rx.recv_timeout(std::time::Duration::from_secs(2));
        transaction.commit().unwrap();
        worker.join().unwrap();
        let (outcome, timeout) = completion.expect("Automatic learning must not wait five seconds");
        assert!(outcome.is_err());
        assert_eq!(timeout, original_timeout);
        let count: i64 = other
            .query_row("SELECT COUNT(*) FROM dictionary", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 0);
    }

    #[tokio::test]
    async fn automatic_dictionary_insert_observes_cancellation_after_waiting_for_writer() {
        use std::sync::atomic::{AtomicBool, Ordering};
        let path =
            std::env::temp_dir().join(format!("dictionary-cancel-{}.sqlite", uuid::Uuid::new_v4()));
        let store = DictionaryStore::new(path.clone()).unwrap();
        let mut other = Connection::open(path).unwrap();
        let transaction = other
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .unwrap();
        let active = std::sync::Arc::new(AtomicBool::new(true));
        let worker_active = active.clone();
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let (finished_tx, finished_rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            started_tx.send(()).unwrap();
            let result = store.insert_if_current("Synthetic cancelled word", None, true, || {
                worker_active.load(Ordering::SeqCst)
            });
            finished_tx.send(result).unwrap();
        });
        started_rx.recv().unwrap();
        assert!(matches!(
            finished_rx.recv_timeout(std::time::Duration::from_millis(150)),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout)
        ));
        active.store(false, Ordering::SeqCst);
        transaction.commit().unwrap();
        let result = finished_rx
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        worker.join().unwrap();
        assert_eq!(result.unwrap(), None);
        let transaction = other
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .unwrap();
        let count: i64 = transaction
            .query_row("SELECT COUNT(*) FROM dictionary", [], |row| row.get(0))
            .unwrap();
        assert_eq!(count, 0);
        transaction.commit().unwrap();
    }

    #[tokio::test]
    async fn dictionary_insert_rechecks_identity_after_another_connection_commits() {
        for automatic in [false, true] {
            let path = std::env::temp_dir()
                .join(format!("dictionary-lock-{}.sqlite", uuid::Uuid::new_v4()));
            let store = DictionaryStore::new(path.clone()).unwrap();
            let mut other = Connection::open(path).unwrap();
            let transaction = other
                .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
                .unwrap();
            let (started_tx, started_rx) = std::sync::mpsc::channel();
            let (finished_tx, finished_rx) = std::sync::mpsc::channel();
            let worker = std::thread::spawn(move || {
                started_tx.send(()).unwrap();
                finished_tx
                    .send(store.insert("ＯＰＥＮＴＹＰＥＬＥＳＳ", None, automatic))
                    .unwrap();
            });
            started_rx.recv().unwrap();
            assert!(matches!(
                finished_rx.recv_timeout(std::time::Duration::from_millis(150)),
                Err(std::sync::mpsc::RecvTimeoutError::Timeout)
            ));
            transaction
                .execute(
                    "INSERT INTO dictionary(word, pronunciation, auto)
                     VALUES ('OpenTypeless', 'Synthetic pronunciation', 0)",
                    [],
                )
                .unwrap();
            transaction.commit().unwrap();
            let result = finished_rx
                .recv_timeout(std::time::Duration::from_secs(5))
                .unwrap();
            worker.join().unwrap();
            assert_eq!(result.unwrap_err().to_string(), "dictionary_duplicate");
            let retained: (i64, String, String, bool) = other
                .query_row(
                    "SELECT id, word, pronunciation, auto FROM dictionary",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
                )
                .unwrap();
            assert_eq!(
                retained,
                (
                    1,
                    "OpenTypeless".into(),
                    "Synthetic pronunciation".into(),
                    false
                )
            );
            let count: i64 = other
                .query_row("SELECT COUNT(*) FROM dictionary", [], |row| row.get(0))
                .unwrap();
            assert_eq!(count, 1);
        }
    }

    #[tokio::test]
    async fn correction_creation_rechecks_duplicates_after_another_connection_commits() {
        let path =
            std::env::temp_dir().join(format!("correction-lock-{}.sqlite", uuid::Uuid::new_v4()));
        let store = DictionaryStore::new(path.clone()).unwrap();
        let mut other = Connection::open(path).unwrap();
        let transaction = other
            .transaction_with_behavior(rusqlite::TransactionBehavior::Immediate)
            .unwrap();
        let (started_tx, started_rx) = std::sync::mpsc::channel();
        let (finished_tx, finished_rx) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            started_tx.send(()).unwrap();
            let result = tauri::async_runtime::block_on(
                store.add_correction("ＯＰＥＮ ＴＹＰＥ ＬＥＳＳ", "opentypeless"),
            );
            finished_tx.send(result).unwrap();
        });
        started_rx.recv().unwrap();
        assert!(matches!(
            finished_rx.recv_timeout(std::time::Duration::from_millis(150)),
            Err(std::sync::mpsc::RecvTimeoutError::Timeout)
        ));
        transaction
            .execute(
                "INSERT INTO correction_rules(pattern, replacement, enabled)
                 VALUES ('open type less', 'OpenTypeless', 0)",
                [],
            )
            .unwrap();
        transaction.commit().unwrap();
        let result = finished_rx
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        worker.join().unwrap();
        assert_eq!(result.unwrap_err().to_string(), "correction_duplicate");
        let count: i64 = other
            .query_row("SELECT COUNT(*) FROM correction_rules", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 1);
    }

    #[tokio::test]
    async fn correction_retries_reject_normalized_duplicates_without_overwriting_rules() {
        let store = std::sync::Arc::new(temp_dictionary_store("correction-retries"));
        let barrier = std::sync::Arc::new(std::sync::Barrier::new(2));
        let mut workers = Vec::new();
        for pattern in ["open type less", "ＯＰＥＮ ＴＹＰＥ ＬＥＳＳ"] {
            let worker_store = store.clone();
            let worker_barrier = barrier.clone();
            workers.push(std::thread::spawn(move || {
                worker_barrier.wait();
                tauri::async_runtime::block_on(worker_store.add_correction(pattern, "OpenTypeless"))
            }));
        }
        let outcomes = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .collect::<Vec<_>>();
        assert_eq!(outcomes.iter().filter(|result| result.is_ok()).count(), 1);
        assert_eq!(
            outcomes
                .iter()
                .filter(|result| result
                    .as_ref()
                    .is_err_and(|error| error.to_string() == "correction_duplicate"))
                .count(),
            1
        );
        let original = store.correction_rules().await.unwrap();
        assert_eq!(original.len(), 1);
        store
            .set_correction_enabled(original[0].id, false)
            .await
            .unwrap();
        assert_eq!(
            store
                .add_correction("OPEN TYPE LESS", "ＯＰＥＮＴＹＰＥＬＥＳＳ")
                .await
                .unwrap_err()
                .to_string(),
            "correction_duplicate"
        );
        let retained = store.correction_rules().await.unwrap();
        assert_eq!(retained[0].id, original[0].id);
        assert_eq!(retained[0].pattern, original[0].pattern);
        assert_eq!(retained[0].replacement, original[0].replacement);
        assert!(!retained[0].enabled);
        store
            .add_correction("open type less", "Another replacement")
            .await
            .unwrap();
        assert_eq!(store.correction_rules().await.unwrap().len(), 2);
    }
}
