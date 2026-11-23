use crate::credentials::{
    self, CredentialSecretReader, CredentialSecretRemover, CredentialVault, SystemCredentialVault,
};
use crate::storage;
use crate::AskHotkeyCache;
use crate::CloseToTrayCache;
use crate::HotkeyModeCache;
use crate::HotkeyRegistrationError;
use crate::SessionTokenStore;
use serde_json::{json, Map, Value};
use tauri::{Emitter, Manager, Window};

fn config_patch_between(previous: &storage::AppConfig, next: &storage::AppConfig) -> Value {
    let mut patch = Map::new();
    if previous.stt_provider != next.stt_provider {
        patch.insert("stt_provider".to_string(), json!(next.stt_provider));
    }
    if previous.llm_provider != next.llm_provider {
        patch.insert("llm_provider".to_string(), json!(next.llm_provider));
    }
    if previous.llm_model != next.llm_model {
        patch.insert("llm_model".to_string(), json!(next.llm_model));
    }
    if previous.polish_enabled != next.polish_enabled {
        patch.insert("polish_enabled".to_string(), json!(next.polish_enabled));
    }
    if previous.auto_learn_dictionary != next.auto_learn_dictionary {
        patch.insert(
            "auto_learn_dictionary".to_string(),
            json!(next.auto_learn_dictionary),
        );
    }
    if previous.hotkeys != next.hotkeys {
        patch.insert("hotkeys".to_string(), json!(next.hotkeys));
    }
    if previous.hotkey != next.hotkey {
        patch.insert("hotkey".to_string(), json!(next.hotkey));
    }
    if previous.ask_hotkey != next.ask_hotkey {
        patch.insert("ask_hotkey".to_string(), json!(next.ask_hotkey));
    }
    if previous.hotkey_mode != next.hotkey_mode {
        patch.insert("hotkey_mode".to_string(), json!(next.hotkey_mode));
    }
    if previous.capsule_auto_hide != next.capsule_auto_hide {
        patch.insert(
            "capsule_auto_hide".to_string(),
            json!(next.capsule_auto_hide),
        );
    }
    if previous.max_recording_seconds != next.max_recording_seconds {
        patch.insert(
            "max_recording_seconds".to_string(),
            json!(next.max_recording_seconds),
        );
    }
    if previous.recording_limit_mode != next.recording_limit_mode {
        patch.insert(
            "recording_limit_mode".to_string(),
            json!(next.recording_limit_mode),
        );
    }
    if previous.custom_recording_limit_seconds != next.custom_recording_limit_seconds {
        patch.insert(
            "custom_recording_limit_seconds".to_string(),
            json!(next.custom_recording_limit_seconds),
        );
    }
    if previous.history_enabled != next.history_enabled {
        patch.insert("history_enabled".to_string(), json!(next.history_enabled));
    }
    if previous.save_recording_audio != next.save_recording_audio {
        patch.insert(
            "save_recording_audio".to_string(),
            json!(next.save_recording_audio),
        );
    }
    if previous.history_retention_days != next.history_retention_days {
        patch.insert(
            "history_retention_days".to_string(),
            json!(next.history_retention_days),
        );
    }
    if previous.history_max_entries != next.history_max_entries {
        patch.insert(
            "history_max_entries".to_string(),
            json!(next.history_max_entries),
        );
    }
    if previous.ui_language != next.ui_language {
        patch.insert("ui_language".to_string(), json!(next.ui_language));
    }
    if previous.theme != next.theme {
        patch.insert("theme".to_string(), json!(next.theme));
    }
    if previous.microphone_device != next.microphone_device {
        patch.insert(
            "microphone_device".to_string(),
            json!(next.microphone_device),
        );
    }
    Value::Object(patch)
}

fn emit_config_patch<R: tauri::Runtime>(app: &tauri::AppHandle<R>, patch: &Value) {
    if patch.as_object().is_some_and(|object| !object.is_empty()) {
        let _ = app.emit("config:patch", patch.clone());
    }
}

pub(crate) fn emit_config_commit<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    config_epoch: &str,
    revision: u64,
    config: &storage::AppConfig,
) {
    let commit = ConfigurationCommit {
        config_epoch: config_epoch.to_string(),
        revision,
        save_id: None,
        config: config_for_renderer(config.clone()),
    };
    let _ = app.emit("config:commit", commit);
}

fn prepare_config_for_save(mut config: storage::AppConfig) -> Result<storage::AppConfig, String> {
    // Renderer settings are not a credential update channel. Migration is
    // reserved for reading old settings; explicit edits use set_credential.
    config.stt_api_key.clear();
    config.stt_custom_api_key.clear();
    config.llm_api_key.clear();
    sync_hotkey_fields_before_save(&mut config);
    crate::hotkey::validate_hotkey_config(&config.hotkeys).map_err(|e| e.to_string())?;
    config.normalize_values();
    config.clamp_recording_limit_intent_for_save();
    crate::hotkey::validate_hotkey_config(&config.hotkeys).map_err(|e| e.to_string())?;
    Ok(config)
}

fn sync_hotkey_fields_before_save(config: &mut storage::AppConfig) {
    let default = storage::AppConfig::default();
    let typed_was_changed = config.hotkeys != default.hotkeys;
    let core_lists_changed = config.hotkeys.dictation_bindings
        != default.hotkeys.dictation_bindings
        || config.hotkeys.ask_bindings != default.hotkeys.ask_bindings
        || config.hotkeys.translate_bindings != default.hotkeys.translate_bindings;
    let core_scalars_changed = config.hotkeys.dictation != default.hotkeys.dictation
        || config.hotkeys.ask != default.hotkeys.ask
        || config.hotkeys.translate != default.hotkeys.translate;
    let legacy_was_changed = config.hotkey != default.hotkey
        || config.ask_hotkey != default.ask_hotkey
        || config.hotkey_mode != default.hotkey_mode;

    if typed_was_changed || !legacy_was_changed {
        if core_scalars_changed && !core_lists_changed {
            replace_primary_binding(
                &mut config.hotkeys.dictation_bindings,
                Some(config.hotkeys.dictation.clone()),
            );
            replace_primary_binding(&mut config.hotkeys.ask_bindings, config.hotkeys.ask.clone());
            replace_primary_binding(
                &mut config.hotkeys.translate_bindings,
                config.hotkeys.translate.clone(),
            );
        } else {
            if let Some(primary) = config.hotkeys.dictation_bindings.first().cloned() {
                config.hotkeys.dictation = primary;
            }
            config.hotkeys.ask = config.hotkeys.ask_bindings.first().cloned();
            config.hotkeys.translate = config.hotkeys.translate_bindings.first().cloned();
        }
        config.hotkey = config
            .hotkeys
            .dictation
            .to_hotkey_string()
            .unwrap_or_else(|| config.hotkey.clone());
        config.ask_hotkey = config
            .hotkeys
            .ask
            .as_ref()
            .and_then(storage::ShortcutBinding::to_hotkey_string)
            .unwrap_or_default();
        config.hotkey_mode = config.hotkeys.dictation_mode.clone();
    } else {
        let legacy = storage::HotkeyConfig::from_legacy(
            &config.hotkey,
            &config.ask_hotkey,
            &config.hotkey_mode,
        );
        replace_primary_binding(
            &mut config.hotkeys.dictation_bindings,
            Some(legacy.dictation.clone()),
        );
        replace_primary_binding(&mut config.hotkeys.ask_bindings, legacy.ask.clone());
        config.hotkeys.dictation = legacy.dictation;
        config.hotkeys.ask = legacy.ask;
        config.hotkeys.dictation_mode = legacy.dictation_mode;
    }
}

fn replace_primary_binding(
    bindings: &mut Vec<storage::ShortcutBinding>,
    primary: Option<storage::ShortcutBinding>,
) {
    match (bindings.first_mut(), primary) {
        (Some(current), Some(primary)) => *current = primary,
        (None, Some(primary)) => bindings.push(primary),
        (_, None) => bindings.clear(),
    }
}

fn hotkey_runtime_config_changed(previous: &storage::AppConfig, next: &storage::AppConfig) -> bool {
    previous.hotkey != next.hotkey
        || previous.ask_hotkey != next.ask_hotkey
        || previous.hotkey_mode != next.hotkey_mode
        || previous.hotkeys != next.hotkeys
}

fn hotkey_refresh_allowed(supervisor: &crate::hotkey::HotkeySupervisor) -> bool {
    supervisor.settings_registration_allowed()
}

fn provider_connection_config_changed(
    previous: &storage::AppConfig,
    next: &storage::AppConfig,
) -> bool {
    previous.stt_provider != next.stt_provider
        || previous.stt_custom_base_url != next.stt_custom_base_url
        || previous.llm_provider != next.llm_provider
        || previous.llm_base_url != next.llm_base_url
        || previous.polish_enabled != next.polish_enabled
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub(crate) struct HotkeyRuntimeRefreshError {
    pub registration_error: String,
    pub rollback_error: Option<String>,
}

pub(crate) fn refresh_hotkey_runtime_with_rollback(
    previous: &storage::AppConfig,
    next: &storage::AppConfig,
    mut register: impl FnMut(&storage::AppConfig) -> Result<(), String>,
) -> Result<(), HotkeyRuntimeRefreshError> {
    match register(next) {
        Ok(()) => Ok(()),
        Err(registration_error) => {
            if registration_error == crate::commands::misc::HOTKEY_REGISTRATION_SUPERSEDED_ERROR {
                return Err(HotkeyRuntimeRefreshError {
                    registration_error,
                    rollback_error: None,
                });
            }
            let rollback_error = register(previous).err();
            Err(HotkeyRuntimeRefreshError {
                registration_error,
                rollback_error,
            })
        }
    }
}

fn update_runtime_caches(
    hotkey_mode_cache: &HotkeyModeCache,
    ask_cache: &AskHotkeyCache,
    close_tray_cache: &CloseToTrayCache,
    config: &storage::AppConfig,
) {
    *hotkey_mode_cache
        .0
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = config.hotkey_mode.clone();
    *ask_cache.0.lock().unwrap_or_else(|e| e.into_inner()) = config.ask_hotkey.clone();
    *close_tray_cache.0.lock().unwrap_or_else(|e| e.into_inner()) = config.close_to_tray;
}

pub(crate) fn commit_input_registration_feedback<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    supervisor: &crate::hotkey::HotkeySupervisor,
    generation: u64,
    hotkey_error: &HotkeyRegistrationError,
    message: Option<String>,
) -> bool {
    supervisor.record_registration_result(generation, message.clone(), || {
        *hotkey_error
            .0
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = message;
        crate::hotkey::publish_saved_input_configuration(app, generation);
    })
}

pub(crate) async fn save_capsule_auto_hide(
    app: &tauri::AppHandle,
    state: &storage::ConfigManager,
    enabled: bool,
) -> Result<(), String> {
    state.load().await.map_err(|e| e.to_string())?;
    let (revision, config) = state
        .try_update_current_config_commit(|current| {
            current.capsule_auto_hide = enabled;
            Ok(())
        })
        .map_err(|error| error.to_string())?;
    emit_config_commit(app, state.config_epoch(), revision, &config);
    let patch = json!({ "capsule_auto_hide": config.capsule_auto_hide });
    emit_config_patch(app, &patch);
    crate::refresh_tray(app);
    Ok(())
}

pub(crate) async fn save_microphone_device(
    app: &tauri::AppHandle,
    state: &storage::ConfigManager,
    device: String,
) -> Result<(), String> {
    state.load().await.map_err(|e| e.to_string())?;
    let (revision, config) = state
        .try_update_current_config_commit(|current| {
            current.microphone_device = device;
            Ok(())
        })
        .map_err(|error| error.to_string())?;
    emit_config_commit(app, state.config_epoch(), revision, &config);
    let patch = json!({ "microphone_device": config.microphone_device });
    emit_config_patch(app, &patch);
    crate::refresh_tray(app);
    Ok(())
}

#[tauri::command]
pub async fn get_config(
    state: tauri::State<'_, storage::ConfigManager>,
) -> Result<storage::AppConfig, String> {
    state
        .load()
        .await
        .map(config_for_renderer)
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn get_config_commit(
    state: tauri::State<'_, storage::ConfigManager>,
) -> Result<ConfigurationCommit, String> {
    state.load().await.map_err(|error| error.to_string())?;
    let (revision, config) = state
        .cached_input_commit()
        .map_err(|error| error.to_string())?;
    Ok(ConfigurationCommit {
        config_epoch: state.config_epoch().to_string(),
        revision,
        save_id: None,
        config: config_for_renderer(config),
    })
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfigurationCommit {
    pub config_epoch: String,
    pub revision: u64,
    pub save_id: Option<String>,
    pub config: storage::AppConfig,
}

#[derive(Debug, Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConfigurationSaveReceipt {
    pub config: storage::AppConfig,
    pub saved_revision: u64,
    pub input_activation: &'static str,
    pub commit: ConfigurationCommit,
}

fn validated_config_save_id(save_id: Option<String>) -> Result<Option<String>, String> {
    save_id
        .map(|identity| {
            uuid::Uuid::parse_str(&identity)
                .map(|identity| identity.to_string())
                .map_err(|_| "Invalid configuration save identity".to_string())
        })
        .transpose()
}

#[derive(Debug, Clone, serde::Serialize, serde::Deserialize)]
#[serde(deny_unknown_fields)]
pub struct SceneConfiguration {
    custom_scenes: Vec<storage::CustomScene>,
    system_scene_overrides: Vec<storage::SystemSceneOverride>,
    active_scene: Option<storage::ActiveScene>,
    family_scene_assignments: Vec<storage::FamilySceneAssignment>,
}

impl SceneConfiguration {
    fn from_config(config: &storage::AppConfig) -> Self {
        Self {
            custom_scenes: config.custom_scenes.clone(),
            system_scene_overrides: config.system_scene_overrides.clone(),
            active_scene: config.active_scene.clone(),
            family_scene_assignments: config.family_scene_assignments.clone(),
        }
    }
}

fn apply_scene_configuration(
    current: &mut storage::AppConfig,
    expected: &SceneConfiguration,
    desired: &SceneConfiguration,
) -> anyhow::Result<()> {
    if serde_json::to_value(SceneConfiguration::from_config(current))?
        != serde_json::to_value(expected)?
    {
        return Err(anyhow::anyhow!("Scene configuration changed during saving"));
    }
    let mut candidate = current.clone();
    candidate.custom_scenes = desired.custom_scenes.clone();
    candidate.system_scene_overrides = desired.system_scene_overrides.clone();
    candidate.active_scene = desired.active_scene.clone();
    candidate.family_scene_assignments = desired.family_scene_assignments.clone();
    candidate.normalize_values();
    if candidate.custom_scenes.len() != desired.custom_scenes.len()
        || candidate.system_scene_overrides.len() != desired.system_scene_overrides.len()
        || candidate.family_scene_assignments.len() != desired.family_scene_assignments.len()
        || candidate.active_scene.is_some() != desired.active_scene.is_some()
    {
        return Err(anyhow::anyhow!(
            "Scene configuration contains invalid entries"
        ));
    }
    let mut scene_ids = std::collections::HashSet::new();
    if candidate
        .custom_scenes
        .iter()
        .any(|scene| !scene_ids.insert(&scene.id))
    {
        return Err(anyhow::anyhow!(
            "Scene configuration contains duplicate identifiers"
        ));
    }
    let reserved_catalog = storage::AppConfig::default();
    if candidate.custom_scenes.iter().any(|scene| {
        !current
            .custom_scenes
            .iter()
            .any(|existing| existing.id == scene.id)
            && storage::scene_prompt_for_id(&reserved_catalog, &scene.id).is_some()
    }) {
        return Err(anyhow::anyhow!("Custom scene identifier is reserved"));
    }
    if candidate.active_scene == current.active_scene {
        if let Some(active) = candidate
            .active_scene
            .as_mut()
            .filter(|active| active.source == "custom")
        {
            let previous_scene = current
                .custom_scenes
                .iter()
                .find(|scene| scene.id == active.id);
            let updated_scene = candidate
                .custom_scenes
                .iter()
                .find(|scene| scene.id == active.id);
            if let (Some(previous), Some(updated)) = (previous_scene, updated_scene) {
                if previous.name != updated.name
                    || previous.prompt_template != updated.prompt_template
                {
                    active.name = updated.name.clone();
                    active.prompt_template = updated.prompt_template.clone();
                }
            }
        }
    }
    if candidate.active_scene != current.active_scene {
        if let Some(active) = candidate
            .active_scene
            .as_ref()
            .filter(|active| active.source == "builtin")
        {
            if storage::builtin_scene_prompt(&active.id).is_none() {
                return Err(anyhow::anyhow!(
                    "Active builtin scene reference is unavailable"
                ));
            }
            candidate.active_scene =
                Some(resolve_active_scene(&candidate, &active.id).map_err(anyhow::Error::msg)?);
        }
        if let Some(active) = candidate
            .active_scene
            .as_mut()
            .filter(|active| active.source == "custom")
        {
            let scene = candidate
                .custom_scenes
                .iter()
                .find(|scene| scene.id == active.id)
                .ok_or_else(|| anyhow::anyhow!("Active custom scene reference is unavailable"))?;
            active.name = scene.name.clone();
            active.prompt_template = scene.prompt_template.clone();
        }
    }
    if let Some(active) = &candidate.active_scene {
        let removed_custom = active.source == "custom"
            && current
                .custom_scenes
                .iter()
                .any(|scene| scene.id == active.id)
            && !candidate
                .custom_scenes
                .iter()
                .any(|scene| scene.id == active.id);
        if (removed_custom || candidate.active_scene != current.active_scene)
            && storage::scene_prompt_for_id(&candidate, &active.id).is_none()
        {
            return Err(anyhow::anyhow!("Active scene reference is unavailable"));
        }
    }
    current.custom_scenes = candidate.custom_scenes;
    current.system_scene_overrides = candidate.system_scene_overrides;
    current.active_scene = candidate.active_scene;
    current.family_scene_assignments = candidate.family_scene_assignments;
    Ok(())
}

#[tauri::command]
pub async fn update_scene_configuration<R: tauri::Runtime>(
    window: Window<R>,
    app: tauri::AppHandle<R>,
    expected: SceneConfiguration,
    desired: SceneConfiguration,
    save_id: Option<String>,
    activate_scene_id: Option<String>,
) -> Result<ConfigurationCommit, String> {
    ensure_main_session_window(&window)?;
    let state = app
        .try_state::<storage::ConfigManager>()
        .ok_or("Scene configuration state is unavailable")?;
    let save_id = validated_config_save_id(save_id)?;
    let transaction = app.try_state::<crate::hotkey::HotkeyConfigTransaction>();
    let _transaction = match transaction.as_ref() {
        Some(transaction) => Some(transaction.0.lock().await),
        None => None,
    };
    state.load().await.map_err(|error| error.to_string())?;
    let (revision, config) = state
        .commit_current_config(|current| {
            apply_scene_save_and_activation(
                current,
                &expected,
                &desired,
                activate_scene_id.as_deref(),
            )
        })
        .map_err(|error| error.to_string())?;
    let commit = ConfigurationCommit {
        config_epoch: state.config_epoch().to_string(),
        revision,
        save_id,
        config: config_for_renderer(config),
    };
    let _ = app.emit("config:commit", commit.clone());
    Ok(commit)
}

fn apply_scene_save_and_activation(
    current: &mut storage::AppConfig,
    expected: &SceneConfiguration,
    desired: &SceneConfiguration,
    activate_scene_id: Option<&str>,
) -> anyhow::Result<()> {
    let mut candidate = current.clone();
    apply_scene_configuration(&mut candidate, expected, desired)?;
    if let Some(identifier) = activate_scene_id {
        candidate.active_scene =
            Some(resolve_active_scene(&candidate, identifier).map_err(anyhow::Error::msg)?);
    }
    *current = candidate;
    Ok(())
}

fn resolve_active_scene(
    config: &storage::AppConfig,
    scene_id: &str,
) -> Result<storage::ActiveScene, String> {
    let scene_id = scene_id.trim();
    let custom_matches = config
        .custom_scenes
        .iter()
        .filter(|scene| scene.id == scene_id)
        .count();
    if custom_matches > 1
        || (custom_matches != 0 && storage::builtin_scene_prompt(scene_id).is_some())
    {
        return Err("Scene identifier is ambiguous".into());
    }
    if let Some(prompt) = storage::builtin_scene_prompt(scene_id) {
        let key = match scene_id {
            "builtin_clean_dictation" => "cleanDictation",
            "builtin_meeting_notes" => "meetingNotes",
            "builtin_professional_email" => "professionalEmail",
            "builtin_support_reply" => "supportReply",
            "builtin_technical_explanation" => "technicalExplanation",
            "builtin_code_comment" => "codeComment",
            "builtin_product_spec_notes" => "productSpecNotes",
            _ => return Err("Builtin scene metadata is unavailable".into()),
        };
        let locale = match config.ui_language.as_str() {
            "zh" => include_str!("../../../src/i18n/locales/zh.json"),
            "ja" => include_str!("../../../src/i18n/locales/ja.json"),
            "ko" => include_str!("../../../src/i18n/locales/ko.json"),
            "de" => include_str!("../../../src/i18n/locales/de.json"),
            "fr" => include_str!("../../../src/i18n/locales/fr.json"),
            "es" => include_str!("../../../src/i18n/locales/es.json"),
            "pt" => include_str!("../../../src/i18n/locales/pt.json"),
            "ru" => include_str!("../../../src/i18n/locales/ru.json"),
            "it" => include_str!("../../../src/i18n/locales/it.json"),
            _ => include_str!("../../../src/i18n/locales/en.json"),
        };
        let metadata: Value = serde_json::from_str(locale)
            .map_err(|_| "Builtin scene metadata is invalid".to_string())?;
        let name = metadata["scenes"]["builtin"][key]["name"]
            .as_str()
            .filter(|name| !name.trim().is_empty())
            .ok_or_else(|| "Builtin scene name is unavailable".to_string())?;
        return Ok(storage::ActiveScene {
            id: scene_id.into(),
            source: "builtin".into(),
            name: name.into(),
            prompt_template: prompt.into(),
        });
    }
    let scene = config
        .custom_scenes
        .iter()
        .find(|scene| scene.id == scene_id)
        .ok_or_else(|| "Scene identifier is unavailable".to_string())?;
    Ok(storage::ActiveScene {
        id: scene.id.clone(),
        source: "custom".into(),
        name: scene.name.clone(),
        prompt_template: scene.prompt_template.clone(),
    })
}

fn next_shortcut_scene(
    config: &storage::AppConfig,
) -> Result<Option<storage::ActiveScene>, String> {
    let mut identifiers = vec![
        "builtin_clean_dictation",
        "builtin_meeting_notes",
        "builtin_professional_email",
        "builtin_support_reply",
        "builtin_technical_explanation",
        "builtin_code_comment",
        "builtin_product_spec_notes",
    ];
    identifiers.extend(config.custom_scenes.iter().map(|scene| scene.id.as_str()));
    let next = match config.active_scene.as_ref() {
        None => identifiers.first().copied(),
        Some(active) => identifiers
            .iter()
            .position(|identifier| *identifier == active.id)
            .and_then(|index| identifiers.get(index + 1).copied()),
    };
    next.map(|identifier| resolve_active_scene(config, identifier))
        .transpose()
}

fn apply_shortcut_scene(config: &mut storage::AppConfig) -> Result<(), String> {
    config.active_scene = next_shortcut_scene(config)?;
    Ok(())
}

pub(crate) async fn cycle_scene_from_shortcut(app: &tauri::AppHandle) -> Result<(), String> {
    let pipeline = app.state::<crate::pipeline::PipelineHandle>();
    let _voice_guard = pipeline.try_voice_start_guard()?;
    if pipeline.current_state() != crate::pipeline::PipelineState::Idle
        || app
            .state::<crate::commands::ask::AskDictationState>()
            .is_busy()
        || app.state::<crate::practice::PracticeState>().is_busy()
    {
        return Err("Finish the current voice operation before switching scenes".into());
    }
    let transaction = app.state::<crate::hotkey::HotkeyConfigTransaction>();
    let _transaction = transaction.0.lock().await;
    let state = app.state::<storage::ConfigManager>();
    state.load().await.map_err(|error| error.to_string())?;
    let (revision, config) = state
        .commit_current_config(|current| apply_shortcut_scene(current).map_err(anyhow::Error::msg))
        .map_err(|error| error.to_string())?;
    let notice = json!({ "configEpoch": state.config_epoch(), "revision": revision });
    let commit = ConfigurationCommit {
        config_epoch: state.config_epoch().to_string(),
        revision,
        save_id: None,
        config: config_for_renderer(config),
    };
    app.emit("config:commit", commit)
        .map_err(|error| error.to_string())?;
    app.emit_to("capsule", "scene:shortcut-changed", notice)
        .map_err(|error| error.to_string())
}

#[cfg(test)]
mod scene_shortcut_tests {
    use super::*;

    #[test]
    fn shortcut_scene_update_changes_only_active_scene() {
        let mut config = storage::AppConfig {
            stt_provider: "custom-whisper".into(),
            llm_model: "synthetic-local-model".into(),
            microphone_device: "synthetic-device".into(),
            history_enabled: false,
            selected_text_enabled: false,
            ..Default::default()
        };
        let before = serde_json::to_value(&config).unwrap();
        apply_shortcut_scene(&mut config).unwrap();
        let mut after = serde_json::to_value(&config).unwrap();
        assert_eq!(
            config.active_scene.as_ref().unwrap().id,
            "builtin_clean_dictation"
        );
        after["active_scene"] = before["active_scene"].clone();
        assert_eq!(after, before);
    }

    #[test]
    fn failed_shortcut_scene_resolution_keeps_complete_config_unchanged() {
        let mut config = storage::AppConfig {
            custom_scenes: vec![
                storage::CustomScene {
                    id: "duplicate".into(),
                    ..Default::default()
                },
                storage::CustomScene {
                    id: "duplicate".into(),
                    ..Default::default()
                },
            ],
            ..Default::default()
        };
        config.active_scene =
            Some(resolve_active_scene(&config, "builtin_product_spec_notes").unwrap());
        let before = serde_json::to_value(&config).unwrap();
        assert!(apply_shortcut_scene(&mut config).is_err());
        assert_eq!(serde_json::to_value(config).unwrap(), before);
    }

    #[test]
    fn scene_shortcut_cycles_builtins_and_returns_to_no_manual_scene() {
        let mut config = storage::AppConfig::default();
        let original_provider = config.stt_provider.clone();
        for expected in [
            Some("builtin_clean_dictation"),
            Some("builtin_meeting_notes"),
            Some("builtin_professional_email"),
            Some("builtin_support_reply"),
            Some("builtin_technical_explanation"),
            Some("builtin_code_comment"),
            Some("builtin_product_spec_notes"),
            None,
        ] {
            config.active_scene = next_shortcut_scene(&config).unwrap();
            assert_eq!(
                config.active_scene.as_ref().map(|scene| scene.id.as_str()),
                expected
            );
        }
        assert_eq!(config.stt_provider, original_provider);
    }

    #[test]
    fn scene_shortcut_includes_custom_scenes_and_rejects_ambiguous_ids() {
        let mut config = storage::AppConfig {
            custom_scenes: vec![storage::CustomScene {
                id: "custom_shortcut".into(),
                name: "Synthetic scene".into(),
                prompt_template: "Preserve synthetic meaning".into(),
                ..Default::default()
            }],
            ..Default::default()
        };
        config.active_scene =
            Some(resolve_active_scene(&config, "builtin_product_spec_notes").unwrap());
        let next = next_shortcut_scene(&config).unwrap().unwrap();
        assert_eq!(next.id, "custom_shortcut");
        assert_eq!(next.source, "custom");
        config.active_scene = Some(next);
        assert!(next_shortcut_scene(&config).unwrap().is_none());
        config.custom_scenes.push(config.custom_scenes[0].clone());
        config.active_scene =
            Some(resolve_active_scene(&config, "builtin_product_spec_notes").unwrap());
        assert!(next_shortcut_scene(&config).is_err());
    }
}

#[tauri::command]
pub async fn set_active_scene<R: tauri::Runtime>(
    window: Window<R>,
    app: tauri::AppHandle<R>,
    scene_id: Option<String>,
    save_id: Option<String>,
) -> Result<ConfigurationCommit, String> {
    ensure_main_session_window(&window)?;
    let state = app
        .try_state::<storage::ConfigManager>()
        .ok_or("Scene configuration state is unavailable")?;
    let save_id = validated_config_save_id(save_id)?;
    let transaction = app.try_state::<crate::hotkey::HotkeyConfigTransaction>();
    let _transaction = match transaction.as_ref() {
        Some(transaction) => Some(transaction.0.lock().await),
        None => None,
    };
    state.load().await.map_err(|error| error.to_string())?;
    let (revision, config) = state
        .commit_current_config(|current| {
            let active = scene_id
                .as_deref()
                .map(|identifier| resolve_active_scene(current, identifier))
                .transpose()
                .map_err(anyhow::Error::msg)?;
            current.active_scene = active;
            Ok(())
        })
        .map_err(|error| error.to_string())?;
    let commit = ConfigurationCommit {
        config_epoch: state.config_epoch().to_string(),
        revision,
        save_id,
        config: config_for_renderer(config),
    };
    let _ = app.emit("config:commit", commit.clone());
    Ok(commit)
}

#[cfg(test)]
mod scene_transaction_tests {
    use super::*;

    #[test]
    fn activation_rejects_ambiguous_historical_scene_identifiers() {
        let mut config = storage::AppConfig::default();
        config.custom_scenes.push(storage::CustomScene {
            id: "builtin_clean_dictation".into(),
            ..scene()
        });
        assert!(resolve_active_scene(&config, "builtin_clean_dictation").is_err());
        config.custom_scenes = vec![scene(), scene()];
        assert!(resolve_active_scene(&config, "custom_test").is_err());
        config.custom_scenes = vec![scene()];
        assert_eq!(
            resolve_active_scene(&config, "custom_test").unwrap().source,
            "custom"
        );
    }

    #[test]
    fn new_custom_scenes_cannot_shadow_reserved_scene_identifiers() {
        for identifier in ["builtin_clean_dictation", "system_email"] {
            let mut current = storage::AppConfig::default();
            let expected = SceneConfiguration::from_config(&current);
            let mut desired = expected.clone();
            desired.custom_scenes.push(storage::CustomScene {
                id: identifier.into(),
                ..scene()
            });
            assert!(apply_scene_configuration(&mut current, &expected, &desired).is_err());
            assert!(current.custom_scenes.is_empty());
        }
    }

    #[test]
    fn ordinary_config_save_checks_scene_transition_before_registration() {
        let source = include_str!("config.rs");
        let command = source
            .split("\npub async fn update_config(\n")
            .nth(1)
            .unwrap();
        let validation = command
            .find("validate_custom_scene_transition")
            .expect("Ordinary configuration saves must validate scene identity");
        assert!(validation < command.find("let installation_reservation").unwrap());
    }

    #[test]
    fn renderer_persistence_rechecks_scene_identity_before_writing() {
        let source = include_str!("../storage/mod.rs");
        let method = source
            .split("pub async fn save_renderer_config(")
            .nth(1)
            .unwrap()
            .split("pub(crate) fn migrate_current_credentials_commit")
            .next()
            .unwrap();
        let validation = method.find("validate_custom_scene_transition").unwrap();
        assert!(validation < method.find("self.persist_config(&config)").unwrap());
        assert!(validation < method.find("*cache = Some(config.clone())").unwrap());
    }

    #[test]
    fn shared_scene_transition_rejects_new_reserved_ids_and_preserves_existing_ones() {
        for identifier in ["builtin_clean_dictation", "system_email"] {
            let current = storage::AppConfig::default();
            let mut candidate = current.clone();
            candidate.custom_scenes.push(storage::CustomScene {
                id: identifier.into(),
                ..scene()
            });
            assert!(storage::validate_custom_scene_transition(Some(&current), &candidate).is_err());
            assert!(
                storage::validate_custom_scene_transition(Some(&candidate), &candidate).is_ok()
            );
            assert!(storage::validate_custom_scene_transition(Some(&candidate), &current).is_ok());
        }
    }

    #[test]
    fn existing_custom_scene_with_reserved_id_can_be_preserved_or_deleted() {
        let mut current = storage::AppConfig::default();
        current.custom_scenes.push(storage::CustomScene {
            id: "system_email".into(),
            ..scene()
        });
        let expected = SceneConfiguration::from_config(&current);
        apply_scene_configuration(&mut current, &expected, &expected).unwrap();
        let mut desired = expected.clone();
        desired.custom_scenes.clear();
        apply_scene_configuration(&mut current, &expected, &desired).unwrap();
        assert!(current.custom_scenes.is_empty());
    }

    #[test]
    fn remote_main_scene_ipc_is_denied_before_configuration_state_lookup() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![
                set_active_scene,
                update_scene_configuration
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let scenes = serde_json::to_value(SceneConfiguration::from_config(
            &storage::AppConfig::default(),
        ))
        .unwrap();
        for (command, body) in [
            (
                "set_active_scene",
                json!({"sceneId": null, "saveId": "invalid"}),
            ),
            (
                "update_scene_configuration",
                json!({"expected": scenes, "desired": scenes, "saveId": "invalid"}),
            ),
        ] {
            let response = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: command.into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "https://untrusted.example.test/".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(body),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            let error = response.unwrap_err();
            let message = error.as_str().unwrap();
            assert!(message.contains("not allowed"));
            assert_ne!(message, "Scene configuration state is unavailable");
        }
    }

    #[test]
    fn auxiliary_scene_ipc_is_denied_before_missing_state_or_invalid_save_identity() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![
                set_active_scene,
                update_scene_configuration
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let scenes = serde_json::to_value(SceneConfiguration::from_config(
            &storage::AppConfig::default(),
        ))
        .unwrap();
        for label in ["capsule", "ask", "unknown", "main"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for (command, body) in [
                (
                    "set_active_scene",
                    json!({"sceneId": null, "saveId": "invalid"}),
                ),
                (
                    "update_scene_configuration",
                    json!({
                        "expected": scenes,
                        "desired": scenes,
                        "saveId": "invalid"
                    }),
                ),
            ] {
                let response = tauri::test::get_ipc_response(
                    &window,
                    tauri::webview::InvokeRequest {
                        cmd: command.into(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(body),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                let expected = if label == "main" {
                    "Scene configuration state is unavailable"
                } else {
                    "cloud session access is only allowed from the main window"
                };
                assert_eq!(response.unwrap_err(), json!(expected));
            }
        }
    }

    #[test]
    fn scene_ipc_checks_window_before_resolving_configuration_state() {
        let source = include_str!("config.rs");
        for marker in [
            "pub async fn set_active_scene",
            "pub async fn update_scene_configuration",
        ] {
            let command = source
                .split(marker)
                .nth(1)
                .unwrap()
                .split("\n}\n")
                .next()
                .unwrap();
            assert!(!command.contains("state: tauri::State"));
            assert!(
                command
                    .find("ensure_main_session_window(&window)?")
                    .unwrap()
                    < command
                        .find("try_state::<storage::ConfigManager>()")
                        .unwrap()
            );
        }
    }

    #[test]
    fn save_and_activate_resolves_the_newly_saved_scene_in_one_candidate() {
        let mut current = storage::AppConfig::default();
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.custom_scenes.push(scene());
        apply_scene_save_and_activation(&mut current, &expected, &desired, Some("custom_test"))
            .unwrap();
        assert_eq!(current.custom_scenes.len(), 1);
        assert_eq!(current.active_scene.as_ref().unwrap().id, "custom_test");
        assert_eq!(
            current.active_scene.unwrap().prompt_template,
            "Use concise wording."
        );
    }

    #[test]
    fn invalid_activation_does_not_apply_the_scene_save_candidate() {
        let mut current = storage::AppConfig::default();
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.custom_scenes.push(scene());
        assert!(apply_scene_save_and_activation(
            &mut current,
            &expected,
            &desired,
            Some("missing"),
        )
        .is_err());
        assert!(current.custom_scenes.is_empty());
        assert!(current.active_scene.is_none());
    }

    #[test]
    fn activation_command_uses_current_configuration_and_returns_owned_commit() {
        let source = include_str!("config.rs");
        let command = source
            .split("pub async fn set_active_scene")
            .nth(1)
            .expect("ID-only scene activation command is missing")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(command.contains("scene_id: Option<String>"));
        assert!(command.contains(".commit_current_config("));
        assert!(command.contains("resolve_active_scene(current, identifier)"));
        assert!(command.contains("app.emit(\"config:commit\", commit.clone())"));
        assert!(!command.contains("register_configured_shortcuts"));
        assert!(include_str!("../lib.rs").contains("commands::config::set_active_scene,"));
    }

    #[test]
    fn activation_id_resolves_custom_metadata_and_seven_localized_builtin_names() {
        let mut config = storage::AppConfig::default();
        config.custom_scenes.push(scene());
        let custom = resolve_active_scene(&config, "custom_test").unwrap();
        assert_eq!(custom.name, "Test");
        assert_eq!(custom.source, "custom");
        for language in ["en", "zh", "ja", "ko", "de", "fr", "es", "pt", "ru", "it"] {
            config.ui_language = language.into();
            for identifier in [
                "builtin_clean_dictation",
                "builtin_meeting_notes",
                "builtin_professional_email",
                "builtin_support_reply",
                "builtin_technical_explanation",
                "builtin_code_comment",
                "builtin_product_spec_notes",
            ] {
                let active = resolve_active_scene(&config, identifier).unwrap();
                assert_eq!(active.source, "builtin");
                assert!(!active.name.is_empty());
                assert_eq!(
                    Some(active.prompt_template.as_str()),
                    storage::builtin_scene_prompt(identifier)
                );
            }
        }
        config.ui_language = "zh".into();
        assert_ne!(
            resolve_active_scene(&config, "builtin_clean_dictation")
                .unwrap()
                .name,
            "Clean Dictation"
        );
        assert!(resolve_active_scene(&config, "missing").is_err());
        assert!(resolve_active_scene(&config, "system_email").is_err());
    }

    #[test]
    fn builtin_activation_uses_native_prompt_and_rejects_custom_identifier() {
        let mut current = storage::AppConfig {
            ui_language: "ja".into(),
            ..storage::AppConfig::default()
        };
        current.custom_scenes.push(scene());
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.active_scene = Some(storage::ActiveScene {
            id: "builtin_clean_dictation".into(),
            source: "builtin".into(),
            name: "Clean dictation".into(),
            prompt_template: "Renderer instructions.".into(),
        });
        apply_scene_configuration(&mut current, &expected, &desired).unwrap();
        assert_eq!(
            current.active_scene.as_ref().unwrap().name,
            resolve_active_scene(&current, "builtin_clean_dictation")
                .unwrap()
                .name
        );
        assert_eq!(
            current.active_scene.as_ref().unwrap().prompt_template,
            storage::scene_prompt_for_id(&current, "builtin_clean_dictation").unwrap()
        );
        let expected = SceneConfiguration::from_config(&current);
        desired = expected.clone();
        desired.active_scene.as_mut().unwrap().id = "custom_test".into();
        assert!(apply_scene_configuration(&mut current, &expected, &desired).is_err());
        assert_eq!(current.active_scene.unwrap().id, "builtin_clean_dictation");
    }

    #[test]
    fn custom_activation_uses_the_saved_scene_instead_of_renderer_prompt_metadata() {
        let mut current = storage::AppConfig::default();
        current.custom_scenes.push(scene());
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.active_scene = Some(storage::ActiveScene {
            id: "custom_test".into(),
            source: "custom".into(),
            name: "Renderer name".into(),
            prompt_template: "Renderer instructions.".into(),
        });
        apply_scene_configuration(&mut current, &expected, &desired).unwrap();
        let active = current.active_scene.unwrap();
        assert_eq!(active.name, "Test");
        assert_eq!(active.prompt_template, "Use concise wording.");
    }

    #[test]
    fn custom_activation_rejects_a_builtin_identifier_without_mutating_configuration() {
        let mut current = storage::AppConfig::default();
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.active_scene = Some(storage::ActiveScene {
            id: "builtin_clean_dictation".into(),
            source: "custom".into(),
            name: "Wrong source".into(),
            prompt_template: "Wrong instructions.".into(),
        });
        assert!(apply_scene_configuration(&mut current, &expected, &desired).is_err());
        assert!(current.active_scene.is_none());
    }

    #[test]
    fn editing_the_active_custom_scene_refreshes_its_owned_name_and_prompt() {
        let mut current = storage::AppConfig::default();
        current.custom_scenes.push(scene());
        current.active_scene = Some(storage::ActiveScene {
            id: "custom_test".into(),
            source: "custom".into(),
            name: "Test".into(),
            prompt_template: "Use concise wording.".into(),
        });
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.custom_scenes[0].name = "Updated".into();
        desired.custom_scenes[0].prompt_template = "Use updated instructions.".into();
        apply_scene_configuration(&mut current, &expected, &desired).unwrap();
        let active = current.active_scene.unwrap();
        assert_eq!(active.id, "custom_test");
        assert_eq!(active.source, "custom");
        assert_eq!(active.name, "Updated");
        assert_eq!(active.prompt_template, "Use updated instructions.");
    }

    #[test]
    fn editing_an_inactive_custom_scene_does_not_activate_it() {
        let mut current = storage::AppConfig::default();
        current.custom_scenes.push(scene());
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.custom_scenes[0].prompt_template = "Updated inactive instructions.".into();
        apply_scene_configuration(&mut current, &expected, &desired).unwrap();
        assert!(current.active_scene.is_none());
    }

    #[test]
    fn editing_another_custom_scene_preserves_the_active_scene_snapshot() {
        let mut current = storage::AppConfig::default();
        current.custom_scenes.push(scene());
        current.custom_scenes.push(storage::CustomScene {
            id: "custom_other".into(),
            ..scene()
        });
        current.active_scene = Some(storage::ActiveScene {
            id: "custom_test".into(),
            source: "custom".into(),
            name: "Historical name".into(),
            prompt_template: "Historical instructions.".into(),
        });
        let active = current.active_scene.clone();
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.custom_scenes[1].prompt_template = "Updated other scene.".into();
        apply_scene_configuration(&mut current, &expected, &desired).unwrap();
        assert_eq!(current.active_scene, active);
    }

    #[test]
    fn scene_command_checks_caller_and_returns_its_owned_commit_without_reinstalling_hotkeys() {
        let source = include_str!("config.rs");
        let command = source
            .split("pub async fn update_scene_configuration")
            .nth(1)
            .unwrap()
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(
            command
                .find("ensure_main_session_window(&window)?")
                .unwrap()
                < command.find("state.load().await").unwrap()
        );
        assert!(command.contains(".commit_current_config("));
        assert!(command.contains("app.emit(\"config:commit\", commit.clone())"));
        assert!(command.contains("Ok(commit)"));
        assert!(!command.contains(".save_renderer_config("));
        assert!(!command.contains("register_configured_shortcuts"));
        assert!(include_str!("../lib.rs").contains("commands::config::update_scene_configuration,"));
    }

    #[test]
    fn deleting_a_scene_and_its_owned_references_is_one_valid_transaction() {
        let mut current = storage::AppConfig::default();
        current.custom_scenes.push(scene());
        current.active_scene = Some(storage::ActiveScene {
            id: "custom_test".into(),
            source: "custom".into(),
            name: "Test".into(),
            prompt_template: "Use concise wording.".into(),
        });
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.custom_scenes.clear();
        desired.active_scene = None;
        apply_scene_configuration(&mut current, &expected, &desired).unwrap();
        assert!(current.custom_scenes.is_empty());
        assert!(current.active_scene.is_none());
    }

    #[test]
    fn deleting_an_active_custom_scene_cannot_leave_its_old_reference_behind() {
        let mut current = storage::AppConfig::default();
        current.custom_scenes.push(scene());
        current.active_scene = Some(storage::ActiveScene {
            id: "custom_test".into(),
            source: "custom".into(),
            name: "Test".into(),
            prompt_template: "Use concise wording.".into(),
        });
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired.custom_scenes.clear();
        assert!(apply_scene_configuration(&mut current, &expected, &desired).is_err());
        assert_eq!(current.custom_scenes.len(), 1);
    }

    fn scene() -> storage::CustomScene {
        storage::CustomScene {
            id: "custom_test".into(),
            name: "Test".into(),
            description: String::new(),
            prompt_template: "Use concise wording.".into(),
            created_at: String::new(),
            updated_at: String::new(),
        }
    }

    #[test]
    fn scene_transaction_preserves_current_non_scene_settings() {
        let original = storage::AppConfig::default();
        let expected = SceneConfiguration::from_config(&original);
        let mut desired = expected.clone();
        desired.custom_scenes.push(scene());
        let mut current = original;
        current.microphone_device = "concurrent-device".into();
        current.history_enabled = false;
        apply_scene_configuration(&mut current, &expected, &desired).unwrap();
        assert_eq!(current.microphone_device, "concurrent-device");
        assert!(!current.history_enabled);
        assert_eq!(current.custom_scenes.len(), 1);
    }

    #[test]
    fn scene_transaction_rejects_a_concurrent_scene_change_without_mutation() {
        let mut current = storage::AppConfig::default();
        let expected = SceneConfiguration::from_config(&current);
        current.custom_scenes.push(scene());
        let before = serde_json::to_value(&current).unwrap();
        assert!(apply_scene_configuration(&mut current, &expected, &expected).is_err());
        assert_eq!(serde_json::to_value(current).unwrap(), before);
    }

    #[test]
    fn scene_transaction_rejects_unknown_assignment_and_active_scene_references() {
        let mut current = storage::AppConfig::default();
        let expected = SceneConfiguration::from_config(&current);
        let mut desired = expected.clone();
        desired
            .family_scene_assignments
            .push(storage::FamilySceneAssignment {
                family: crate::app_detector::types::ContextFamily::Email,
                scene_id: "missing".into(),
            });
        assert!(apply_scene_configuration(&mut current, &expected, &desired).is_err());
        assert!(current.family_scene_assignments.is_empty());
        desired.family_scene_assignments.clear();
        desired.active_scene = Some(storage::ActiveScene {
            id: "missing".into(),
            source: "custom".into(),
            name: "Missing".into(),
            prompt_template: "Missing".into(),
        });
        assert!(apply_scene_configuration(&mut current, &expected, &desired).is_err());
        assert!(current.active_scene.is_none());
    }

    #[test]
    fn scene_transaction_payload_cannot_include_unrelated_settings() {
        let mut value = serde_json::to_value(SceneConfiguration::from_config(
            &storage::AppConfig::default(),
        ))
        .unwrap();
        value["microphone_device"] = serde_json::json!("forbidden");
        assert!(serde_json::from_value::<SceneConfiguration>(value).is_err());
    }
}

fn config_for_renderer(mut config: storage::AppConfig) -> storage::AppConfig {
    config.stt_api_key.clear();
    config.stt_custom_api_key.clear();
    config.llm_api_key.clear();
    config
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn update_config(
    window: Window,
    app: tauri::AppHandle,
    state: tauri::State<'_, storage::ConfigManager>,
    cache: tauri::State<'_, HotkeyModeCache>,
    ask_cache: tauri::State<'_, AskHotkeyCache>,
    hotkey_error: tauri::State<'_, HotkeyRegistrationError>,
    hotkey_supervisor: tauri::State<'_, crate::hotkey::HotkeySupervisor>,
    close_tray_cache: tauri::State<'_, CloseToTrayCache>,
    config: storage::AppConfig,
    save_id: Option<String>,
) -> Result<ConfigurationSaveReceipt, String> {
    ensure_main_session_window(&window)?;
    let save_id = validated_config_save_id(save_id)?;
    let transaction = app.try_state::<crate::hotkey::HotkeyConfigTransaction>();
    let _transaction = match transaction.as_ref() {
        Some(transaction) => Some(transaction.0.lock().await),
        None => None,
    };
    let previous = state.load().await.map_err(|e| e.to_string())?;
    let incoming = config;
    let config = prepare_config_for_save(storage::config_with_native_managed_state(
        incoming.clone(),
        Some(&previous),
    ))?;
    storage::validate_custom_scene_transition(Some(&previous), &config)
        .map_err(|error| error.to_string())?;
    let installation_reservation = (hotkey_runtime_config_changed(&previous, &config)
        && !crate::hotkey::input_configuration_busy(&app)
        && hotkey_refresh_allowed(&hotkey_supervisor)
        && app
            .try_state::<crate::hotkey::HotkeySupervisor>()
            .is_none_or(|supervisor| supervisor.capture_allows_registration()))
    .then(|| crate::hotkey::reserve_input_installation(&app))
    .flatten();
    let refresh_hotkeys = installation_reservation.is_some();
    let refresh_provider_connections = provider_connection_config_changed(&previous, &config);
    let registration_generation =
        refresh_hotkeys.then(|| hotkey_supervisor.wake_for_settings_change());

    if let Some(generation) = registration_generation {
        if let Err(error) = refresh_hotkey_runtime_with_rollback(&previous, &config, |candidate| {
            crate::commands::misc::register_configured_shortcuts_for_generation(
                &app,
                candidate,
                &hotkey_supervisor,
                generation,
            )
        }) {
            if error.registration_error
                == crate::commands::misc::HOTKEY_REGISTRATION_SUPERSEDED_ERROR
            {
                return Err(error.registration_error);
            }
            commit_input_registration_feedback(
                &app,
                &hotkey_supervisor,
                generation,
                &hotkey_error,
                error.rollback_error,
            );
            return Err(error.registration_error);
        }
    }

    let (saved_revision, config) = match state
        .save_renderer_config(incoming, prepare_config_for_save)
        .await
    {
        Ok(config) => config,
        Err(error) => {
            let error = error.to_string();
            if let Some(generation) = registration_generation {
                let Some(rollback_generation) = hotkey_supervisor.begin_rollback(generation) else {
                    return Err(error);
                };
                let rollback_error =
                    crate::commands::misc::register_configured_shortcuts_for_generation(
                        &app,
                        &previous,
                        &hotkey_supervisor,
                        rollback_generation,
                    )
                    .err();
                if rollback_error.as_deref()
                    == Some(crate::commands::misc::HOTKEY_REGISTRATION_SUPERSEDED_ERROR)
                {
                    return Err(error);
                }
                commit_input_registration_feedback(
                    &app,
                    &hotkey_supervisor,
                    rollback_generation,
                    &hotkey_error,
                    rollback_error,
                );
            }
            return Err(error);
        }
    };

    let patch = config_patch_between(&previous, &config);
    crate::edit_learning::apply_enabled_setting(&app, config.auto_learn_dictionary);
    update_runtime_caches(&cache, &ask_cache, &close_tray_cache, &config);
    if let Some(generation) = registration_generation {
        commit_input_registration_feedback(
            &app,
            &hotkey_supervisor,
            generation,
            &hotkey_error,
            None,
        );
    } else if hotkey_supervisor.role_actions_allowed("dictation")
        || hotkey_supervisor
            .role_actions_allowed(crate::hotkey::HotkeyRole::TranslateSelection.as_str())
    {
        crate::hotkey::publish_saved_input_configuration(
            &app,
            hotkey_supervisor.snapshot().generation,
        );
    }
    let commit = ConfigurationCommit {
        config_epoch: state.config_epoch().to_string(),
        revision: saved_revision,
        save_id,
        config: config_for_renderer(config.clone()),
    };
    let _ = app.emit("config:commit", commit.clone());
    emit_config_patch(&app, &patch);
    if ["ui_language", "capsule_auto_hide", "microphone_device"]
        .iter()
        .any(|key| patch.get(key).is_some())
    {
        crate::refresh_tray(&app);
    }
    if patch.get("show_in_dock").is_some() {
        crate::apply_dock_visibility(&app, config.show_in_dock);
    }
    if refresh_provider_connections {
        if let Some(pipeline) = app.try_state::<crate::pipeline::PipelineHandle>() {
            let pipeline = pipeline.inner().clone();
            tauri::async_runtime::spawn(async move {
                pipeline.pre_warm().await;
            });
        }
    }
    let input_activation = if hotkey_supervisor.snapshot().state
        == crate::hotkey::HotkeySupervisorState::Installed
        && crate::hotkey::saved_input_revision_is_active(&app, saved_revision, &config)
    {
        "active"
    } else {
        "pending"
    };
    Ok(ConfigurationSaveReceipt {
        config: config_for_renderer(config),
        saved_revision,
        input_activation,
        commit,
    })
}

#[tauri::command]
pub async fn set_auto_start(
    window: Window,
    app: tauri::AppHandle,
    config_state: tauri::State<'_, storage::ConfigManager>,
    enabled: bool,
) -> Result<(), String> {
    ensure_main_session_window(&window)?;
    config_state
        .load()
        .await
        .map_err(|error| error.to_string())?;
    use tauri_plugin_autostart::ManagerExt;
    let autolaunch = app.autolaunch();
    let previous = autolaunch.is_enabled().map_err(|error| error.to_string())?;
    apply_auto_start_with_persistence(
        previous,
        enabled,
        |value| {
            if value {
                autolaunch.enable()
            } else {
                autolaunch.disable()
            }
            .map_err(|error| error.to_string())
        },
        || {
            let (revision, config) = config_state
                .try_update_current_config_commit(|current| {
                    current.auto_start = enabled;
                    Ok(())
                })
                .map_err(|error| error.to_string())?;
            emit_config_commit(&app, config_state.config_epoch(), revision, &config);
            Ok(())
        },
    )
}

fn apply_auto_start_with_persistence(
    previous: bool,
    enabled: bool,
    mut apply: impl FnMut(bool) -> Result<(), String>,
    persist: impl FnOnce() -> Result<(), String>,
) -> Result<(), String> {
    if previous != enabled {
        apply(enabled)?;
    }
    if let Err(error) = persist() {
        if previous != enabled && apply(previous).is_err() {
            return Err(format!(
                "{error}; Could not restore the previous system auto-start setting"
            ));
        }
        return Err(error);
    }
    Ok(())
}

#[tauri::command]
pub async fn set_capsule_auto_hide(
    app: tauri::AppHandle,
    state: tauri::State<'_, storage::ConfigManager>,
    enabled: bool,
) -> Result<(), String> {
    save_capsule_auto_hide(&app, &state, enabled).await
}

#[tauri::command]
pub async fn load_onboarding_completed(
    window: Window,
    state: tauri::State<'_, storage::ConfigManager>,
) -> Result<bool, String> {
    ensure_main_session_window(&window)?;
    state
        .load_onboarding_completed()
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn save_onboarding_completed(
    window: Window,
    state: tauri::State<'_, storage::ConfigManager>,
) -> Result<(), String> {
    ensure_main_session_window(&window)?;
    state
        .save_onboarding_completed()
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn get_session_token<R: tauri::Runtime>(
    app: tauri::AppHandle<R>,
    window: Window<R>,
) -> Result<Option<String>, String> {
    ensure_main_session_window(&window)?;
    protected_session_snapshot(app)
        .await
        .map(|snapshot| snapshot.token)
}

#[tauri::command]
pub async fn get_cloud_session_snapshot<R: tauri::Runtime>(
    app: tauri::AppHandle<R>,
    window: Window<R>,
) -> Result<crate::CloudSessionSnapshot, String> {
    ensure_main_session_window(&window)?;
    protected_session_snapshot(app).await
}

#[tauri::command]
pub async fn set_session_token<R: tauri::Runtime>(
    app: tauri::AppHandle<R>,
    window: Window<R>,
    token: String,
) -> Result<(), String> {
    ensure_main_session_window(&window)?;
    tauri::async_runtime::spawn_blocking(move || {
        let recovery = recovery_guard(&app)?;
        let state = app.state::<SessionTokenStore>();
        let consent = app.state::<crate::history_sync_consent::ConsentStore>();
        let proposals = app.state::<crate::history_sync_proposal::ProposalStore>();
        write_session_snapshot(
            state.inner(),
            token,
            None,
            None,
            &SystemCredentialVault,
            (
                Some(&recovery),
                Some(consent.inner()),
                Some(proposals.inner()),
            ),
        )
        .map(|_| ())
    })
    .await
    .map_err(|_| "Cloud session commit worker unavailable".to_string())?
}

fn recovery_guard<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
) -> Result<crate::session_recovery::SessionRecoveryGuard, String> {
    app.path()
        .app_data_dir()
        .map(crate::session_recovery::SessionRecoveryGuard::new)
        .map_err(|_| "Cloud session recovery protection unavailable".to_string())
}

async fn protected_session_snapshot<R: tauri::Runtime>(
    app: tauri::AppHandle<R>,
) -> Result<crate::CloudSessionSnapshot, String> {
    let recovery = recovery_guard(&app)?;
    protected_session_snapshot_with_reader(app, SystemCredentialVault, recovery).await
}

async fn protected_session_snapshot_with_reader<
    R: tauri::Runtime,
    V: CredentialSecretReader + Send + 'static,
>(
    app: tauri::AppHandle<R>,
    vault: V,
    recovery: crate::session_recovery::SessionRecoveryGuard,
) -> Result<crate::CloudSessionSnapshot, String> {
    tauri::async_runtime::spawn_blocking(move || {
        let state = app.state::<SessionTokenStore>();
        let consent = app.state::<crate::history_sync_consent::ConsentStore>();
        let proposals = app.state::<crate::history_sync_proposal::ProposalStore>();
        read_session_snapshot(
            state.inner(),
            &vault,
            (
                Some(&recovery),
                Some(consent.inner()),
                Some(proposals.inner()),
            ),
        )
    })
    .await
    .map_err(|_| "Cloud session recovery worker unavailable".to_string())?
}

fn ensure_main_session_window<R: tauri::Runtime>(window: &Window<R>) -> Result<(), String> {
    if window.label() != "main" {
        return Err("cloud session access is only allowed from the main window".to_string());
    }
    let webview = window
        .app_handle()
        .get_webview_window(window.label())
        .ok_or("Cloud session local content unavailable")?;
    crate::update_install_request::ensure_local_window(&webview)
        .map_err(|_| "Cloud session access requires local application content".into())
}

#[tauri::command]
pub fn begin_cloud_session_commit<R: tauri::Runtime>(
    window: Window<R>,
    attempts: tauri::State<'_, crate::session_commit::SessionCommitAttempts>,
) -> Result<String, String> {
    ensure_main_session_window(&window)?;
    attempts.begin()
}

#[tauri::command]
pub fn cancel_cloud_session_commit<R: tauri::Runtime>(
    window: Window<R>,
    attempts: tauri::State<'_, crate::session_commit::SessionCommitAttempts>,
    attempt_id: String,
) -> Result<bool, String> {
    ensure_main_session_window(&window)?;
    Ok(attempts.cancel(&attempt_id))
}

#[tauri::command]
pub async fn set_cloud_session_snapshot<R: tauri::Runtime>(
    app: tauri::AppHandle<R>,
    window: Window<R>,
    token: String,
    expected_session_epoch: Option<String>,
    attempt_id: Option<String>,
) -> Result<crate::CloudSessionSnapshot, String> {
    ensure_main_session_window(&window)?;
    tauri::async_runtime::spawn_blocking(move || {
        let state = app.state::<SessionTokenStore>();
        let attempts = app.try_state::<crate::session_commit::SessionCommitAttempts>();
        let attempt = if let Some(attempt_id) = attempt_id.as_deref() {
            Some((
                attempts
                    .as_ref()
                    .ok_or("Cloud session commit state unavailable")?
                    .inner(),
                attempt_id,
            ))
        } else {
            None
        };
        let recovery = recovery_guard(&app)?;
        let consent = app.state::<crate::history_sync_consent::ConsentStore>();
        let proposals = app.state::<crate::history_sync_proposal::ProposalStore>();
        write_session_snapshot(
            state.inner(),
            token,
            expected_session_epoch.as_deref(),
            attempt,
            &SystemCredentialVault,
            (
                Some(&recovery),
                Some(consent.inner()),
                Some(proposals.inner()),
            ),
        )
    })
    .await
    .map_err(|_| "Cloud session commit worker unavailable".to_string())?
}

#[cfg(test)]
fn get_session_token_from_vault<V: CredentialSecretReader>(
    state: &SessionTokenStore,
    vault: &V,
) -> Result<Option<String>, String> {
    get_cloud_session_snapshot_from_vault(state, vault).map(|snapshot| snapshot.token)
}

#[cfg(test)]
fn get_cloud_session_snapshot_from_vault<V: CredentialSecretReader>(
    state: &SessionTokenStore,
    vault: &V,
) -> Result<crate::CloudSessionSnapshot, String> {
    read_session_snapshot(state, vault, (None, None, None))
}

#[cfg(test)]
pub(crate) fn get_protected_session_snapshot<V: CredentialSecretReader>(
    state: &SessionTokenStore,
    vault: &V,
    recovery: &crate::session_recovery::SessionRecoveryGuard,
) -> Result<crate::CloudSessionSnapshot, String> {
    read_session_snapshot(state, vault, (Some(recovery), None, None))
}

fn read_session_snapshot<V: CredentialSecretReader>(
    state: &SessionTokenStore,
    vault: &V,
    protection: (
        Option<&crate::session_recovery::SessionRecoveryGuard>,
        Option<&crate::history_sync_consent::ConsentStore>,
        Option<&crate::history_sync_proposal::ProposalStore>,
    ),
) -> Result<crate::CloudSessionSnapshot, String> {
    let (recovery, consent, proposals) = protection;
    let mut cached = state.0.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(recovery) = recovery {
        if recovery.is_blocked().unwrap_or(true) {
            if !cached.is_empty() {
                cached.clear();
                state.rotate_epoch();
            }
            state.1.store(true, std::sync::atomic::Ordering::SeqCst);
            clear_session_sync_state(consent, proposals)?;
        }
    }
    if cached.trim().is_empty() && !state.1.load(std::sync::atomic::Ordering::SeqCst) {
        let restored = credentials::load_cloud_session_token(vault).map_err(|e| e.to_string())?;
        if let Some(token) = restored {
            clear_session_sync_state(consent, proposals).inspect_err(|_| {
                state.1.store(true, std::sync::atomic::Ordering::SeqCst);
            })?;
            *cached = token;
            state.rotate_epoch();
        }
    }
    let epoch = state.2.lock().unwrap_or_else(|error| error.into_inner());
    Ok(crate::CloudSessionSnapshot {
        token: (!cached.trim().is_empty()).then(|| cached.clone()),
        session_epoch: epoch.clone(),
    })
}

fn clear_session_sync_state(
    consent: Option<&crate::history_sync_consent::ConsentStore>,
    proposals: Option<&crate::history_sync_proposal::ProposalStore>,
) -> Result<(), String> {
    let proposal_failed = proposals.is_some_and(|store| store.clear().is_err());
    let consent_failed = consent.is_some_and(|store| store.revoke().is_err());
    if proposal_failed {
        return Err("Cloud synchronization proposal cleanup unavailable".into());
    }
    if consent_failed {
        return Err("Cloud synchronization consent unavailable".into());
    }
    Ok(())
}

#[cfg(test)]
fn set_session_token_with_vault<
    V: CredentialVault + CredentialSecretReader + CredentialSecretRemover,
>(
    state: &SessionTokenStore,
    token: String,
    vault: &V,
) -> Result<(), String> {
    set_cloud_session_snapshot_with_vault(state, token, vault).map(|_| ())
}

#[cfg(test)]
fn set_cloud_session_snapshot_with_vault<
    V: CredentialVault + CredentialSecretReader + CredentialSecretRemover,
>(
    state: &SessionTokenStore,
    token: String,
    vault: &V,
) -> Result<crate::CloudSessionSnapshot, String> {
    commit_cloud_session_snapshot_with_vault(state, token, None, None, vault)
}

#[cfg(test)]
fn commit_cloud_session_snapshot_with_vault<
    V: CredentialVault + CredentialSecretReader + CredentialSecretRemover,
>(
    state: &SessionTokenStore,
    token: String,
    expected_session_epoch: Option<&str>,
    attempt: Option<(&crate::session_commit::SessionCommitAttempts, &str)>,
    vault: &V,
) -> Result<crate::CloudSessionSnapshot, String> {
    write_session_snapshot(
        state,
        token,
        expected_session_epoch,
        attempt,
        vault,
        (None, None, None),
    )
}

#[cfg(test)]
fn protected_session_commit<
    V: CredentialVault + CredentialSecretReader + CredentialSecretRemover,
>(
    state: &SessionTokenStore,
    token: String,
    expected_session_epoch: Option<&str>,
    attempt: Option<(&crate::session_commit::SessionCommitAttempts, &str)>,
    vault: &V,
    recovery: &crate::session_recovery::SessionRecoveryGuard,
) -> Result<crate::CloudSessionSnapshot, String> {
    write_session_snapshot(
        state,
        token,
        expected_session_epoch,
        attempt,
        vault,
        (Some(recovery), None, None),
    )
}

fn write_session_snapshot<V: CredentialVault + CredentialSecretReader + CredentialSecretRemover>(
    state: &SessionTokenStore,
    token: String,
    expected_session_epoch: Option<&str>,
    attempt: Option<(&crate::session_commit::SessionCommitAttempts, &str)>,
    vault: &V,
    protection: (
        Option<&crate::session_recovery::SessionRecoveryGuard>,
        Option<&crate::history_sync_consent::ConsentStore>,
        Option<&crate::history_sync_proposal::ProposalStore>,
    ),
) -> Result<crate::CloudSessionSnapshot, String> {
    let (recovery, consent, proposals) = protection;
    let mut cached = state.0.lock().unwrap_or_else(|e| e.into_inner());
    if let Some(expected) = expected_session_epoch {
        let epoch = state.2.lock().unwrap_or_else(|error| error.into_inner());
        if expected != epoch.as_str() {
            if let Some((attempts, attempt)) = attempt {
                attempts.cancel(attempt);
            }
            return Err("Cloud session write superseded".to_string());
        }
    }
    let _commit = attempt
        .map(|(attempts, attempt)| attempts.enter_commit(attempt))
        .transpose()?;
    let proposal_cleanup_failed = proposals.is_some_and(|proposals| proposals.clear().is_err());
    if let Some(recovery) = recovery {
        if let Err(error) = recovery.block() {
            if let Some(consent) = consent {
                let _ = consent.revoke();
            }
            cached.clear();
            state.1.store(true, std::sync::atomic::Ordering::SeqCst);
            state.rotate_epoch();
            return Err(error);
        }
    }
    if let Some(consent) = consent {
        if consent.revoke().is_err() {
            cached.clear();
            state.1.store(true, std::sync::atomic::Ordering::SeqCst);
            state.rotate_epoch();
            return Err("Cloud synchronization consent unavailable".into());
        }
    }
    if proposal_cleanup_failed {
        cached.clear();
        state.1.store(true, std::sync::atomic::Ordering::SeqCst);
        state.rotate_epoch();
        return Err("Cloud synchronization proposal cleanup unavailable".into());
    }
    state.rotate_epoch();
    if token.trim().is_empty() {
        cached.clear();
        state.1.store(true, std::sync::atomic::Ordering::SeqCst);
        credentials::remove_cloud_session_token(vault).map_err(|e| e.to_string())?;
    } else {
        if let Err(error) = credentials::store_cloud_session_token(vault, &token) {
            if recovery.is_some()
                || error
                    .downcast_ref::<credentials::UnverifiedCloudSessionWrite>()
                    .is_some()
                || credentials::load_cloud_session_token(vault)
                    .map(|stored| stored.as_deref() != Some(cached.as_str()))
                    .unwrap_or(true)
            {
                cached.clear();
                state.1.store(true, std::sync::atomic::Ordering::SeqCst);
            }
            return Err(error.to_string());
        }
        *cached = token;
        state.1.store(false, std::sync::atomic::Ordering::SeqCst);
        if let Some(recovery) = recovery {
            if let Err(error) = recovery.allow() {
                cached.clear();
                state.1.store(true, std::sync::atomic::Ordering::SeqCst);
                return Err(error);
            }
        }
    }
    let epoch = state.2.lock().unwrap_or_else(|error| error.into_inner());
    Ok(crate::CloudSessionSnapshot {
        token: (!cached.trim().is_empty()).then(|| cached.clone()),
        session_epoch: epoch.clone(),
    })
}

#[cfg(test)]
mod tests {
    #[test]
    fn blocked_recovery_clears_sync_state_even_when_cached_token_is_already_empty() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        recovery.block().unwrap();
        let state = session_store("");
        let original = state.snapshot();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let authorization = consent
            .grant("synthetic-account", &original.session_epoch, &endpoint)
            .unwrap();
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let attempt = proposals.begin_discovery().unwrap();
        let vault = MemorySessionVault {
            token: Mutex::new(Some("synthetic-residual-token".into())),
            fail_write: false,
        };
        let result = read_session_snapshot(
            &state,
            &vault,
            (Some(&recovery), Some(&consent), Some(&proposals)),
        )
        .unwrap();
        assert!(result.token.is_none());
        assert_eq!(result.session_epoch, original.session_epoch);
        assert!(!proposals.abandon_discovery(&attempt).unwrap());
        assert!(consent
            .check(
                &authorization,
                "synthetic-account",
                &original.session_epoch,
                &endpoint
            )
            .is_err());
        assert_eq!(
            vault.token.lock().unwrap().as_deref(),
            Some("synthetic-residual-token")
        );
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn poisoned_proposal_cleanup_does_not_skip_consent_revocation_during_session_read() {
        for restoring in [false, true] {
            let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
            let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
            let state = session_store(if restoring { "" } else { "synthetic-token" });
            let original = state.snapshot();
            let endpoint = "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap();
            let consent = crate::history_sync_consent::ConsentStore::default();
            let authorization = consent
                .grant("synthetic-account", &original.session_epoch, &endpoint)
                .unwrap();
            let proposals = crate::history_sync_proposal::ProposalStore::default();
            proposals.poison_for_test();
            let vault = MemorySessionVault {
                token: Mutex::new(Some("synthetic-token".into())),
                fail_write: false,
            };
            if !restoring {
                recovery.block().unwrap();
            }
            let result = read_session_snapshot(
                &state,
                &vault,
                (Some(&recovery), Some(&consent), Some(&proposals)),
            );
            assert!(result.is_err());
            assert!(state.snapshot().token.is_none());
            assert!(state.1.load(std::sync::atomic::Ordering::SeqCst));
            assert!(consent
                .check(
                    &authorization,
                    "synthetic-account",
                    &original.session_epoch,
                    &endpoint
                )
                .is_err());
            if directory.exists() {
                std::fs::remove_dir_all(directory).unwrap();
            }
        }
    }

    #[test]
    fn session_read_clears_proposals_only_for_recovery_session_changes() {
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        let state = session_store("synthetic-token");
        let vault = MemorySessionVault {
            token: Mutex::new(Some("synthetic-token".into())),
            fail_write: false,
        };
        let unchanged_attempt = proposals.begin_discovery().unwrap();
        read_session_snapshot(&state, &vault, (Some(&recovery), None, Some(&proposals))).unwrap();
        assert!(proposals.abandon_discovery(&unchanged_attempt).unwrap());
        let blocked_attempt = proposals.begin_discovery().unwrap();
        recovery.block().unwrap();
        let cleared =
            read_session_snapshot(&state, &vault, (Some(&recovery), None, Some(&proposals)))
                .unwrap();
        assert!(cleared.token.is_none());
        assert!(!proposals.abandon_discovery(&blocked_attempt).unwrap());
        let restoring = session_store("");
        let restoring_attempt = proposals.begin_discovery().unwrap();
        let restored =
            read_session_snapshot(&restoring, &vault, (None, None, Some(&proposals))).unwrap();
        assert_eq!(restored.token.as_deref(), Some("synthetic-token"));
        assert!(!proposals.abandon_discovery(&restoring_attempt).unwrap());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn session_write_clears_discovery_attempt_only_after_owner_validation() {
        let state = session_store("synthetic-token");
        let original = state.snapshot();
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let attempt = proposals.begin_discovery().unwrap();
        let vault = MemorySessionVault::default();
        let stale = uuid::Uuid::new_v4().to_string();
        assert!(write_session_snapshot(
            &state,
            "replacement".into(),
            Some(&stale),
            None,
            &vault,
            (None, None, Some(&proposals)),
        )
        .is_err());
        assert!(proposals.abandon_discovery(&attempt).unwrap());
        let current_attempt = proposals.begin_discovery().unwrap();
        assert!(write_session_snapshot(
            &state,
            String::new(),
            Some(&original.session_epoch),
            None,
            &RemovalFailingSessionVault,
            (None, None, Some(&proposals)),
        )
        .is_err());
        assert!(!proposals.abandon_discovery(&current_attempt).unwrap());
        assert!(state.snapshot().token.is_none());
    }

    #[test]
    fn remote_main_content_cannot_read_cached_cloud_credentials() {
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(session_store("synthetic-private-token"))
            .invoke_handler(tauri::generate_handler![get_session_token])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(
            &app,
            "main",
            tauri::WebviewUrl::External("https://example.invalid/".parse().unwrap()),
        )
        .build()
        .unwrap();
        let result = tauri::test::get_ipc_response(
            &window,
            tauri::webview::InvokeRequest {
                cmd: "get_session_token".into(),
                callback: tauri::ipc::CallbackFn(0),
                error: tauri::ipc::CallbackFn(1),
                url: "https://example.invalid/".parse().unwrap(),
                body: tauri::ipc::InvokeBody::default(),
                headers: Default::default(),
                invoke_key: tauri::test::INVOKE_KEY.into(),
            },
        );
        assert!(
            result.is_err(),
            "Remote main must not receive cached credentials"
        );
        assert!(
            ensure_main_session_window(&window.as_ref().window()).is_err(),
            "Application guard must independently reject remote main content"
        );
        assert_eq!(
            app.state::<SessionTokenStore>().snapshot().token.as_deref(),
            Some("synthetic-private-token")
        );
    }

    #[test]
    fn poisoned_consent_logout_still_blocks_residual_vault_recovery_after_restart() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        let state = session_store("old-token");
        let consent = crate::history_sync_consent::ConsentStore::default();
        consent.poison_for_test();
        let vault = MemorySessionVault {
            token: Mutex::new(Some("old-token".into())),
            fail_write: false,
        };
        assert!(write_session_snapshot(
            &state,
            String::new(),
            None,
            None,
            &vault,
            (Some(&recovery), Some(&consent), None),
        )
        .is_err());
        assert!(state.snapshot().token.is_none());
        assert!(recovery.is_blocked().unwrap());
        assert_eq!(vault.token.lock().unwrap().as_deref(), Some("old-token"));
        let restarted = session_store("");
        let reopened = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        let snapshot = get_protected_session_snapshot(&restarted, &vault, &reopened).unwrap();
        assert!(snapshot.token.is_none());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn vault_restoration_revokes_old_consent_but_stale_write_preserves_current_consent() {
        let state = session_store("");
        let original = state.snapshot();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let old = consent
            .grant("synthetic-account", &original.session_epoch, &endpoint)
            .unwrap();
        let vault = MemorySessionVault {
            token: Mutex::new(Some("synthetic-restored-token".into())),
            fail_write: false,
        };
        let restored = read_session_snapshot(&state, &vault, (None, Some(&consent), None)).unwrap();
        assert_eq!(restored.token.as_deref(), Some("synthetic-restored-token"));
        assert_ne!(restored.session_epoch, original.session_epoch);
        assert!(consent
            .check(
                &old,
                "synthetic-account",
                &original.session_epoch,
                &endpoint
            )
            .is_err());
        let current = consent
            .grant("synthetic-account", &restored.session_epoch, &endpoint)
            .unwrap();
        let result = write_session_snapshot(
            &state,
            "stale-token".into(),
            Some(&original.session_epoch),
            None,
            &vault,
            (None, Some(&consent), None),
        );
        assert!(matches!(result, Err(error) if error == "Cloud session write superseded"));
        assert!(consent
            .check(
                &current,
                "synthetic-account",
                &restored.session_epoch,
                &endpoint
            )
            .is_ok());
        assert_eq!(state.snapshot().token, restored.token);
        assert_eq!(state.snapshot().session_epoch, restored.session_epoch);
        assert_eq!(
            vault.token.lock().unwrap().as_deref(),
            Some("synthetic-restored-token")
        );
    }

    #[test]
    fn recovery_read_revokes_consent_only_when_runtime_session_changes() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        let state = session_store("synthetic-token");
        let original = state.snapshot();
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let expected = consent
            .grant("synthetic-account", &original.session_epoch, &endpoint)
            .unwrap();
        let vault = MemorySessionVault {
            token: Mutex::new(Some("synthetic-token".into())),
            fail_write: false,
        };
        let unchanged =
            read_session_snapshot(&state, &vault, (Some(&recovery), Some(&consent), None)).unwrap();
        assert_eq!(unchanged.session_epoch, original.session_epoch);
        assert!(consent
            .check(
                &expected,
                "synthetic-account",
                &original.session_epoch,
                &endpoint
            )
            .is_ok());
        recovery.block().unwrap();
        let cleared =
            read_session_snapshot(&state, &vault, (Some(&recovery), Some(&consent), None)).unwrap();
        assert!(cleared.token.is_none());
        assert_ne!(cleared.session_epoch, original.session_epoch);
        assert!(consent
            .check(
                &expected,
                "synthetic-account",
                &original.session_epoch,
                &endpoint
            )
            .is_err());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn session_change_revokes_sync_consent_even_if_vault_removal_fails() {
        let state = session_store("old-token");
        let snapshot = state.snapshot();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let expected = consent
            .grant("synthetic-account", &snapshot.session_epoch, &endpoint)
            .unwrap();
        assert!(write_session_snapshot(
            &state,
            String::new(),
            None,
            None,
            &RemovalFailingSessionVault,
            (None, Some(&consent), None),
        )
        .is_err());
        assert!(consent
            .check(
                &expected,
                "synthetic-account",
                &snapshot.session_epoch,
                &endpoint
            )
            .is_err());
        assert!(state.snapshot().token.is_none());
    }

    #[test]
    fn save_identity_validation_preserves_legacy_calls_and_rejects_invalid_values() {
        assert_eq!(validated_config_save_id(None).unwrap(), None);
        let identity = "22222222-2222-4222-8222-222222222222";
        assert_eq!(
            validated_config_save_id(Some(identity.into())).unwrap(),
            Some(identity.into())
        );
        for invalid in [
            "",
            "synthetic-invalid",
            " 22222222-2222-4222-8222-222222222222",
        ] {
            assert!(validated_config_save_id(Some(invalid.into())).is_err());
        }
    }

    #[test]
    fn receipt_and_event_serialize_the_same_owned_commit() {
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let received = Arc::new(Mutex::new(Vec::<Value>::new()));
        let captured = received.clone();
        let listener = app.listen_any("config:commit", move |event| {
            captured
                .lock()
                .unwrap()
                .push(serde_json::from_str(event.payload()).unwrap());
        });
        let commit = ConfigurationCommit {
            config_epoch: "11111111-1111-4111-8111-111111111111".into(),
            revision: 7,
            save_id: Some("22222222-2222-4222-8222-222222222222".into()),
            config: config_for_renderer(storage::AppConfig {
                stt_api_key: "synthetic-secret".into(),
                ..storage::AppConfig::default()
            }),
        };
        app.emit("config:commit", commit.clone()).unwrap();
        let receipt = ConfigurationSaveReceipt {
            config: commit.config.clone(),
            saved_revision: commit.revision,
            input_activation: "pending",
            commit,
        };
        let value = serde_json::to_value(receipt).unwrap();
        assert_eq!(received.lock().unwrap()[0], value["commit"]);
        assert_eq!(value["savedRevision"], value["commit"]["revision"]);
        assert_eq!(value["config"], value["commit"]["config"]);
        assert_eq!(value["commit"]["config"]["stt_api_key"], "");
        app.unlisten(listener);
    }

    #[test]
    fn full_save_returns_and_broadcasts_the_same_commit_identity() {
        let source = include_str!("config.rs");
        let command = source
            .split("\npub async fn update_config(\n")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        assert!(command.contains("save_id: Option<String>"));
        assert!(
            command.find("validated_config_save_id(save_id)?").unwrap()
                < command.find("state.load().await").unwrap()
        );
        assert!(command.contains("app.emit(\"config:commit\", commit.clone())"));
        assert!(command.contains("commit,"));
    }

    #[test]
    fn config_commit_event_keeps_owned_version_and_redacts_full_snapshot() {
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let received = Arc::new(Mutex::new(Vec::<Value>::new()));
        let captured = received.clone();
        let listener = app.listen_any("config:commit", move |event| {
            captured
                .lock()
                .unwrap()
                .push(serde_json::from_str(event.payload()).unwrap());
        });
        let mut config = storage::AppConfig {
            stt_api_key: "synthetic-stt-secret".into(),
            stt_custom_api_key: "synthetic-custom-secret".into(),
            llm_api_key: "synthetic-llm-secret".into(),
            theme: "dark".into(),
            ..storage::AppConfig::default()
        };
        let epoch = "11111111-1111-4111-8111-111111111111";
        emit_config_commit(app.handle(), epoch, 5, &config);
        config.theme = "light".into();
        emit_config_commit(app.handle(), epoch, 6, &config);
        let events = received.lock().unwrap();
        assert_eq!(events.len(), 2);
        assert_eq!(events[0]["configEpoch"], epoch);
        assert_eq!(events[0]["revision"], 5);
        assert!(events[0]["saveId"].is_null());
        assert_eq!(events[0]["config"]["theme"], "dark");
        assert_eq!(events[1]["revision"], 6);
        assert_eq!(events[1]["config"]["theme"], "light");
        for event in events.iter() {
            assert_eq!(
                event["config"].as_object().unwrap().len(),
                serde_json::to_value(&config)
                    .unwrap()
                    .as_object()
                    .unwrap()
                    .len()
            );
            for field in ["stt_api_key", "stt_custom_api_key", "llm_api_key"] {
                assert_eq!(event["config"][field], "");
            }
        }
        drop(events);
        app.unlisten(listener);
    }

    #[test]
    fn versioned_config_snapshot_serializes_identity_and_redacts_provider_secrets() {
        let config = storage::AppConfig {
            stt_api_key: "synthetic-stt-secret".into(),
            stt_custom_api_key: "synthetic-custom-secret".into(),
            llm_api_key: "synthetic-llm-secret".into(),
            ..storage::AppConfig::default()
        };
        let snapshot = ConfigurationCommit {
            config_epoch: "11111111-1111-4111-8111-111111111111".into(),
            revision: 0,
            save_id: None,
            config: config_for_renderer(config),
        };
        let value = serde_json::to_value(snapshot).unwrap();
        assert_eq!(value.as_object().unwrap().len(), 4);
        assert_eq!(value["configEpoch"], "11111111-1111-4111-8111-111111111111");
        assert_eq!(value["revision"], 0);
        assert!(value["saveId"].is_null());
        for field in ["stt_api_key", "stt_custom_api_key", "llm_api_key"] {
            assert_eq!(value["config"][field], "");
        }
    }

    #[test]
    fn versioned_config_read_is_registered_and_uses_an_owned_redacted_snapshot() {
        let source = include_str!("config.rs");
        let command = source
            .split("pub async fn get_config_commit(")
            .nth(1)
            .expect("versioned configuration read must exist")
            .split("#[derive")
            .next()
            .unwrap();
        assert!(command.contains(".cached_input_commit()"));
        assert!(command.contains("config_for_renderer(config)"));
        assert!(command.contains("config_epoch"));
        assert!(include_str!("../lib.rs").contains("commands::config::get_config_commit,"));
    }

    use super::*;
    use anyhow::{anyhow, Result};
    use std::sync::{Arc, Mutex};

    #[tokio::test(flavor = "current_thread")]
    async fn blocked_session_recovery_leaves_async_tasks_responsive() {
        struct BlockingSessionVault {
            entered: Mutex<Option<tokio::sync::oneshot::Sender<()>>>,
            release: Mutex<std::sync::mpsc::Receiver<()>>,
        }

        impl CredentialSecretReader for BlockingSessionVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                self.entered
                    .lock()
                    .unwrap()
                    .take()
                    .unwrap()
                    .send(())
                    .unwrap();
                self.release
                    .lock()
                    .unwrap()
                    .recv_timeout(std::time::Duration::from_secs(2))?;
                Ok(Some("synthetic-restored-session".to_string()))
            }
        }

        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(session_store(""))
            .manage(crate::history_sync_proposal::ProposalStore::default())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let data_dir = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir_all(&data_dir).unwrap();
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(data_dir.clone());
        let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let vault = BlockingSessionVault {
            entered: Mutex::new(Some(entered_sender)),
            release: Mutex::new(release_receiver),
        };
        let recovery_future =
            protected_session_snapshot_with_reader(app.handle().clone(), vault, recovery);
        let local_task = async move {
            entered_receiver.await.unwrap();
            tokio::task::yield_now().await;
            release_sender.send(()).unwrap();
        };
        let (snapshot, ()) = tokio::join!(recovery_future, local_task);
        assert_eq!(
            snapshot.unwrap().token.as_deref(),
            Some("synthetic-restored-session")
        );
        std::fs::remove_dir_all(data_dir).unwrap();
    }

    #[test]
    fn session_write_commands_move_vault_operations_to_blocking_workers() {
        let source = include_str!("config.rs");
        for command in ["set_session_token", "set_cloud_session_snapshot"] {
            let body = source
                .split(&format!("pub async fn {command}<"))
                .nth(1)
                .unwrap()
                .split("\n}\n")
                .next()
                .unwrap();
            assert!(body.contains("spawn_blocking"), "{command}");
        }
    }

    #[test]
    fn failed_auto_start_persistence_restores_actual_previous_system_state() {
        let mut applied = Vec::new();
        let result = apply_auto_start_with_persistence(
            false,
            true,
            |enabled| {
                applied.push(enabled);
                Ok(())
            },
            || Err("synthetic settings save failure".to_string()),
        );
        assert_eq!(applied, vec![true, false]);
        assert_eq!(result.unwrap_err(), "synthetic settings save failure");
    }

    #[test]
    fn auto_start_reports_rollback_failure_and_skips_persistence_after_apply_failure() {
        let mut calls = 0;
        let error = apply_auto_start_with_persistence(
            true,
            false,
            |_| {
                calls += 1;
                if calls == 2 {
                    Err("synthetic rollback failure".to_string())
                } else {
                    Ok(())
                }
            },
            || Err("synthetic save failure".to_string()),
        )
        .unwrap_err();
        assert_eq!(calls, 2);
        assert!(error.contains("Could not restore"));
        let mut persisted = false;
        assert!(apply_auto_start_with_persistence(
            false,
            true,
            |_| Err("synthetic apply failure".to_string()),
            || {
                persisted = true;
                Ok(())
            },
        )
        .is_err());
        assert!(!persisted);
    }

    #[test]
    fn auto_start_save_checks_window_and_updates_only_current_field() {
        let production = include_str!("config.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
        let command = production
            .split("pub async fn set_auto_start(")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let guard = command
            .find("ensure_main_session_window(&window)?;")
            .unwrap();
        assert!(guard < command.find("app.autolaunch()").unwrap());
        assert!(command.contains(".try_update_current_config_commit("));
        assert!(command.contains("emit_config_commit("));
        assert!(!command.contains(".save(&config)"));
    }

    #[test]
    fn narrow_tray_settings_update_current_config_instead_of_replaying_snapshots() {
        let production = include_str!("config.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        for marker in [
            "pub(crate) async fn save_capsule_auto_hide(",
            "pub(crate) async fn save_microphone_device(",
        ] {
            let method = production
                .split(marker)
                .nth(1)
                .unwrap()
                .split("\n}\n")
                .next()
                .unwrap();
            assert!(method.contains(".try_update_current_config_commit("));
            assert!(
                method.contains("emit_config_commit(app, state.config_epoch(), revision, &config)")
            );
            assert!(!method.contains(".cached_input_commit()"));
            assert!(!method.contains("state.save(&config)"));
        }
    }

    #[test]
    fn session_token_ipc_allows_main_and_denies_auxiliary_windows() {
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_proposal::ProposalStore::default())
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(session_store("synthetic-cached-token"))
            .invoke_handler(tauri::generate_handler![get_session_token])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["main", "capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &webview,
                tauri::webview::InvokeRequest {
                    cmd: "get_session_token".to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::default(),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            if label == "main" {
                assert_eq!(
                    result.unwrap().deserialize::<Option<String>>().unwrap(),
                    Some("synthetic-cached-token".to_string())
                );
            } else {
                assert_eq!(
                    result.unwrap_err(),
                    json!("cloud session access is only allowed from the main window")
                );
            }
        }
    }

    #[test]
    fn session_snapshot_ipc_exposes_identity_only_to_main() {
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_proposal::ProposalStore::default())
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(session_store("synthetic-cached-token"))
            .invoke_handler(tauri::generate_handler![get_cloud_session_snapshot])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["main", "capsule", "ask", "unknown"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &webview,
                tauri::webview::InvokeRequest {
                    cmd: "get_cloud_session_snapshot".to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::default(),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            if label == "main" {
                let snapshot = result.unwrap().deserialize::<Value>().unwrap();
                assert_eq!(snapshot["token"], "synthetic-cached-token");
                assert_eq!(
                    snapshot["sessionEpoch"],
                    app.state::<SessionTokenStore>().snapshot().session_epoch
                );
            } else {
                assert_eq!(
                    result.unwrap_err(),
                    json!("cloud session access is only allowed from the main window")
                );
            }
        }
    }

    #[test]
    fn auxiliary_session_write_ipc_is_denied_without_changing_runtime_token() {
        let app = tauri::test::mock_builder()
            .manage(session_store("synthetic-cached-token"))
            .invoke_handler(tauri::generate_handler![
                set_session_token,
                set_cloud_session_snapshot
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask", "unknown"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for (command, token) in [
                ("set_session_token", ""),
                ("set_session_token", "synthetic-replacement"),
                ("set_cloud_session_snapshot", ""),
                ("set_cloud_session_snapshot", "synthetic-replacement"),
            ] {
                let result = tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: command.to_string(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(json!({ "token": token })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                assert_eq!(
                    result.unwrap_err(),
                    json!("cloud session access is only allowed from the main window")
                );
                assert_eq!(
                    *app.state::<SessionTokenStore>().0.lock().unwrap(),
                    "synthetic-cached-token"
                );
            }
        }
    }

    #[derive(Default)]
    struct MemorySessionVault {
        token: Mutex<Option<String>>,
        fail_write: bool,
    }

    impl CredentialVault for MemorySessionVault {
        fn set_secret(&self, _namespace: &str, _provider: &str, secret: &str) -> Result<()> {
            if self.fail_write {
                return Err(anyhow!("vault write failed"));
            }
            *self.token.lock().unwrap() = Some(secret.to_string());
            Ok(())
        }
    }

    impl CredentialSecretReader for MemorySessionVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            Ok(self.token.lock().unwrap().clone())
        }
    }

    impl CredentialSecretRemover for MemorySessionVault {
        fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
            *self.token.lock().unwrap() = None;
            Ok(())
        }
    }

    fn session_store(token: &str) -> SessionTokenStore {
        SessionTokenStore(
            Arc::new(Mutex::new(token.to_string())),
            std::sync::atomic::AtomicBool::new(false),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        )
    }

    struct UnverifiedSessionWriteVault {
        token: Mutex<Option<String>>,
    }

    struct PartiallyFailingSessionWriteVault {
        token: Mutex<Option<String>>,
    }

    impl CredentialVault for PartiallyFailingSessionWriteVault {
        fn set_secret(&self, _namespace: &str, _provider: &str, secret: &str) -> Result<()> {
            *self.token.lock().unwrap() = Some(secret.into());
            Err(anyhow!("synthetic partial write failure"))
        }
    }

    impl CredentialSecretReader for PartiallyFailingSessionWriteVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            Ok(self.token.lock().unwrap().clone())
        }
    }

    impl CredentialSecretRemover for PartiallyFailingSessionWriteVault {
        fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
            Ok(())
        }
    }

    #[test]
    fn partial_vault_write_failure_disables_the_previous_runtime_identity() {
        let state = session_store("previous-token");
        let vault = PartiallyFailingSessionWriteVault {
            token: Mutex::new(Some("previous-token".into())),
        };
        assert!(set_session_token_with_vault(&state, "candidate-token".into(), &vault).is_err());
        assert!(state.snapshot().token.is_none());
        assert_eq!(get_session_token_from_vault(&state, &vault).unwrap(), None);
        assert_eq!(
            vault.token.lock().unwrap().as_deref(),
            Some("candidate-token")
        );
    }

    impl CredentialVault for UnverifiedSessionWriteVault {
        fn set_secret(&self, _namespace: &str, _provider: &str, secret: &str) -> Result<()> {
            *self.token.lock().unwrap() = Some(secret.to_string());
            Ok(())
        }
    }

    impl CredentialSecretReader for UnverifiedSessionWriteVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            Err(anyhow!("synthetic session verification failure"))
        }
    }

    impl CredentialSecretRemover for UnverifiedSessionWriteVault {
        fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
            *self.token.lock().unwrap() = None;
            Ok(())
        }
    }

    #[test]
    fn unverified_session_write_disables_cached_identity_without_rolling_back_vault() {
        let state = session_store("previous-token");
        let vault = UnverifiedSessionWriteVault {
            token: Mutex::new(Some("previous-token".to_string())),
        };
        assert!(set_session_token_with_vault(&state, "candidate-token".into(), &vault).is_err());
        assert_eq!(
            vault.token.lock().unwrap().as_deref(),
            Some("candidate-token")
        );
        assert!(state.0.lock().unwrap().is_empty());
        assert_eq!(get_session_token_from_vault(&state, &vault).unwrap(), None);
    }

    struct RemovalFailingSessionVault;

    struct SilentRemovalSessionVault {
        token: Mutex<Option<String>>,
        fail_read: bool,
    }

    impl CredentialVault for SilentRemovalSessionVault {
        fn set_secret(&self, _namespace: &str, _provider: &str, secret: &str) -> Result<()> {
            *self.token.lock().unwrap() = Some(secret.to_string());
            Ok(())
        }
    }

    impl CredentialSecretReader for SilentRemovalSessionVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            if self.fail_read {
                return Err(anyhow!("synthetic vault read failure"));
            }
            Ok(self.token.lock().unwrap().clone())
        }
    }

    impl CredentialSecretRemover for SilentRemovalSessionVault {
        fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
            Ok(())
        }
    }

    #[test]
    fn logout_verifies_reported_deletion_and_clears_a_silently_retained_token() {
        let vault = SilentRemovalSessionVault {
            token: Mutex::new(Some("old-token".to_string())),
            fail_read: false,
        };
        let state = session_store("old-token");
        set_session_token_with_vault(&state, String::new(), &vault).unwrap();
        assert_eq!(
            get_session_token_from_vault(&session_store(""), &vault).unwrap(),
            None
        );
    }

    #[test]
    fn logout_cannot_report_success_when_deletion_cannot_be_verified() {
        let vault = SilentRemovalSessionVault {
            token: Mutex::new(Some("old-token".to_string())),
            fail_read: true,
        };
        let state = session_store("old-token");
        assert!(set_session_token_with_vault(&state, String::new(), &vault).is_err());
        assert!(state.0.lock().unwrap().is_empty());
        assert_eq!(get_session_token_from_vault(&state, &vault).unwrap(), None);
    }

    #[derive(Default)]
    struct OverwriteOnlySessionVault {
        token: Mutex<Option<String>>,
    }

    impl CredentialVault for OverwriteOnlySessionVault {
        fn set_secret(&self, _namespace: &str, _provider: &str, secret: &str) -> Result<()> {
            *self.token.lock().unwrap() = Some(secret.to_string());
            Ok(())
        }
    }

    impl CredentialSecretReader for OverwriteOnlySessionVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            Ok(self.token.lock().unwrap().clone())
        }
    }

    impl CredentialSecretRemover for OverwriteOnlySessionVault {
        fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
            Err(anyhow!("synthetic delete failure"))
        }
    }

    #[test]
    fn logout_overwrites_old_token_when_removal_fails_and_prevents_restart_restoration() {
        let vault = OverwriteOnlySessionVault {
            token: Mutex::new(Some("old-token".to_string())),
        };
        let previous_runtime = session_store("old-token");
        assert!(set_session_token_with_vault(&previous_runtime, String::new(), &vault).is_ok());
        assert!(vault.token.lock().unwrap().as_deref().unwrap().is_empty());
        let restarted_runtime = session_store("");
        assert_eq!(
            get_session_token_from_vault(&restarted_runtime, &vault).unwrap(),
            None
        );
        set_session_token_with_vault(&restarted_runtime, "new-token".to_string(), &vault).unwrap();
        assert_eq!(
            get_session_token_from_vault(&session_store(""), &vault).unwrap(),
            Some("new-token".to_string())
        );
    }

    struct DelayedSessionVault {
        token: Mutex<Option<String>>,
        write_started: std::sync::mpsc::Sender<()>,
        release_write: Mutex<std::sync::mpsc::Receiver<()>>,
    }

    impl CredentialVault for DelayedSessionVault {
        fn set_secret(&self, _namespace: &str, _provider: &str, secret: &str) -> Result<()> {
            *self.token.lock().unwrap() = Some(secret.to_string());
            self.write_started.send(())?;
            self.release_write
                .lock()
                .unwrap()
                .recv_timeout(std::time::Duration::from_secs(5))?;
            Ok(())
        }
    }

    impl CredentialSecretReader for DelayedSessionVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            Ok(self.token.lock().unwrap().clone())
        }
    }

    impl CredentialSecretRemover for DelayedSessionVault {
        fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
            *self.token.lock().unwrap() = None;
            Ok(())
        }
    }

    #[test]
    fn logout_waits_for_an_in_flight_write_and_clears_both_token_copies() {
        let state = Arc::new(session_store("previous-token"));
        let (write_started, write_observed) = std::sync::mpsc::channel();
        let (release_write, write_release) = std::sync::mpsc::channel();
        let vault = Arc::new(DelayedSessionVault {
            token: Mutex::new(Some("previous-token".to_string())),
            write_started,
            release_write: Mutex::new(write_release),
        });
        let write_state = state.clone();
        let write_vault = vault.clone();
        let writer = std::thread::spawn(move || {
            set_session_token_with_vault(
                &write_state,
                "new-token".to_string(),
                write_vault.as_ref(),
            )
        });
        write_observed
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        let clear_state = state.clone();
        let clear_vault = vault.clone();
        let (clear_finished, clear_observed) = std::sync::mpsc::channel();
        let clearer = std::thread::spawn(move || {
            let result =
                set_session_token_with_vault(&clear_state, String::new(), clear_vault.as_ref());
            clear_finished.send(()).unwrap();
            result
        });
        let premature_clear = clear_observed.recv_timeout(std::time::Duration::from_millis(50));
        release_write.send(()).unwrap();
        writer.join().unwrap().unwrap();
        clearer.join().unwrap().unwrap();
        assert!(matches!(
            premature_clear,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout)
        ));
        assert!(state.0.lock().unwrap().is_empty());
        assert!(vault.token.lock().unwrap().is_none());
        assert_eq!(
            get_session_token_from_vault(&state, vault.as_ref()).unwrap(),
            None
        );
    }

    impl CredentialVault for RemovalFailingSessionVault {
        fn set_secret(&self, _namespace: &str, _provider: &str, _secret: &str) -> Result<()> {
            Ok(())
        }
    }

    impl CredentialSecretReader for RemovalFailingSessionVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            Ok(Some("old-token".to_string()))
        }
    }

    impl CredentialSecretRemover for RemovalFailingSessionVault {
        fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
            Err(anyhow!("vault removal failed"))
        }
    }

    #[test]
    fn failed_vault_removal_disables_runtime_token_and_prevents_restoration() {
        let state = session_store("old-token");
        let vault = RemovalFailingSessionVault;
        assert!(set_session_token_with_vault(&state, String::new(), &vault).is_err());
        assert!(state.0.lock().unwrap().is_empty());
        assert_eq!(get_session_token_from_vault(&state, &vault).unwrap(), None);
    }

    #[test]
    fn failed_vault_removal_allows_old_token_restoration_in_a_new_runtime() {
        let previous_runtime = session_store("old-token");
        let vault = RemovalFailingSessionVault;
        assert!(set_session_token_with_vault(&previous_runtime, String::new(), &vault).is_err());
        assert_eq!(
            get_session_token_from_vault(&previous_runtime, &vault).unwrap(),
            None
        );

        let restarted_runtime = session_store("");
        assert_eq!(
            get_session_token_from_vault(&restarted_runtime, &vault)
                .unwrap()
                .as_deref(),
            Some("old-token")
        );
    }

    #[test]
    fn protected_failed_logout_blocks_residual_credentials_after_runtime_restart() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        let previous = session_store("old-token");
        let original = previous.snapshot();
        let vault = RemovalFailingSessionVault;
        assert!(
            protected_session_commit(&previous, String::new(), None, None, &vault, &recovery,)
                .is_err()
        );
        let cleared = previous.snapshot();
        assert!(cleared.token.is_none());
        assert_ne!(cleared.session_epoch, original.session_epoch);
        let invoked = std::cell::Cell::new(false);
        assert!(
            crate::history_sync_session::with_current_session(&previous, &original, || {
                invoked.set(true);
                Ok(())
            })
            .is_err()
        );
        assert!(!invoked.get());
        assert_eq!(
            credentials::load_cloud_session_token(&vault)
                .unwrap()
                .as_deref(),
            Some("old-token")
        );
        let restarted = session_store("");
        let reopened = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        assert_eq!(
            get_protected_session_snapshot(&restarted, &vault, &reopened)
                .unwrap()
                .token,
            None
        );
        assert!(reopened.is_blocked().unwrap());
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn protected_verified_login_reenables_recovery_for_its_new_credentials() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        recovery.block().unwrap();
        let state = session_store("");
        let vault = MemorySessionVault::default();
        let committed =
            protected_session_commit(&state, "new-token".into(), None, None, &vault, &recovery)
                .unwrap();
        assert_eq!(committed.token.as_deref(), Some("new-token"));
        assert!(!recovery.is_blocked().unwrap());
        let restarted = session_store("");
        assert_eq!(
            get_protected_session_snapshot(&restarted, &vault, &recovery)
                .unwrap()
                .token
                .as_deref(),
            Some("new-token")
        );
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn protected_failed_login_disables_cache_while_retaining_old_vault_credentials() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        let state = session_store("existing-token");
        let vault = MemorySessionVault {
            token: Mutex::new(Some("existing-token".into())),
            fail_write: true,
        };
        assert!(protected_session_commit(
            &state,
            "candidate-token".into(),
            None,
            None,
            &vault,
            &recovery,
        )
        .is_err());
        assert!(recovery.is_blocked().unwrap());
        assert!(state.snapshot().token.is_none());
        assert_eq!(
            vault.token.lock().unwrap().as_deref(),
            Some("existing-token")
        );
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn unavailable_recovery_protection_prevents_vault_mutation() {
        let directory = std::env::temp_dir().join(uuid::Uuid::new_v4().to_string());
        std::fs::create_dir_all(directory.join("session-recovery-blocked")).unwrap();
        let recovery = crate::session_recovery::SessionRecoveryGuard::new(directory.clone());
        let state = session_store("existing-token");
        let vault = MemorySessionVault {
            token: Mutex::new(Some("existing-token".into())),
            fail_write: false,
        };
        let attempts = crate::session_commit::SessionCommitAttempts::default();
        let attempt = attempts.begin().unwrap();
        let epoch = state.snapshot().session_epoch;
        let result = protected_session_commit(
            &state,
            "candidate-token".into(),
            Some(&epoch),
            Some((&attempts, &attempt)),
            &vault,
            &recovery,
        );
        assert!(result.is_err());
        assert!(state.snapshot().token.is_none());
        assert!(!attempts.claim(&attempt));
        assert_eq!(
            vault.token.lock().unwrap().as_deref(),
            Some("existing-token")
        );
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn failed_login_after_failed_logout_keeps_the_session_disabled() {
        let state = session_store("old-token");
        assert!(
            set_session_token_with_vault(&state, String::new(), &RemovalFailingSessionVault,)
                .is_err()
        );
        let vault = MemorySessionVault {
            token: Mutex::new(Some("old-token".to_string())),
            fail_write: true,
        };
        assert!(set_session_token_with_vault(&state, "new-token".to_string(), &vault).is_err());
        assert_eq!(get_session_token_from_vault(&state, &vault).unwrap(), None);
    }

    #[test]
    fn successful_login_after_failed_logout_reenables_the_new_session() {
        let state = session_store("old-token");
        assert!(
            set_session_token_with_vault(&state, String::new(), &RemovalFailingSessionVault,)
                .is_err()
        );
        let vault = MemorySessionVault::default();
        set_session_token_with_vault(&state, "new-token".to_string(), &vault).unwrap();
        assert_eq!(
            get_session_token_from_vault(&state, &vault)
                .unwrap()
                .as_deref(),
            Some("new-token")
        );
        assert_eq!(vault.token.lock().unwrap().as_deref(), Some("new-token"));
    }

    struct LockCheckingSessionVault {
        state: Arc<Mutex<String>>,
        token: Mutex<Option<String>>,
    }

    impl CredentialVault for LockCheckingSessionVault {
        fn set_secret(&self, _namespace: &str, _provider: &str, secret: &str) -> Result<()> {
            assert!(self.state.try_lock().is_err());
            *self.token.lock().unwrap() = Some(secret.to_string());
            Ok(())
        }
    }

    impl CredentialSecretReader for LockCheckingSessionVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            assert!(self.state.try_lock().is_err());
            Ok(self.token.lock().unwrap().clone())
        }
    }

    impl CredentialSecretRemover for LockCheckingSessionVault {
        fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
            assert!(self.state.try_lock().is_err());
            *self.token.lock().unwrap() = None;
            Ok(())
        }
    }

    #[test]
    fn session_vault_operations_share_the_runtime_token_lock() {
        let state = session_store("");
        let vault = LockCheckingSessionVault {
            state: state.0.clone(),
            token: Mutex::new(Some("restored".to_string())),
        };
        assert_eq!(
            get_session_token_from_vault(&state, &vault)
                .unwrap()
                .as_deref(),
            Some("restored")
        );
        set_session_token_with_vault(&state, "updated".to_string(), &vault).unwrap();
        set_session_token_with_vault(&state, String::new(), &vault).unwrap();
        assert!(state.0.lock().unwrap().is_empty());
        assert!(vault.token.lock().unwrap().is_none());
    }

    #[test]
    fn session_token_round_trips_through_vault_and_runtime_cache() {
        let state = session_store("");
        let vault = MemorySessionVault::default();

        set_session_token_with_vault(&state, "vault-token".to_string(), &vault).unwrap();

        assert_eq!(vault.token.lock().unwrap().as_deref(), Some("vault-token"));
        assert_eq!(
            get_session_token_from_vault(&state, &vault)
                .unwrap()
                .as_deref(),
            Some("vault-token")
        );
    }

    #[test]
    fn session_reads_keep_identity_but_same_token_login_and_logout_rotate_it() {
        let state = session_store("session-token");
        let vault = MemorySessionVault::default();
        let initial = state.snapshot();
        assert_eq!(initial.token.as_deref(), Some("session-token"));
        get_session_token_from_vault(&state, &vault).unwrap();
        assert_eq!(state.snapshot().session_epoch, initial.session_epoch);
        set_session_token_with_vault(&state, "session-token".into(), &vault).unwrap();
        let logged_in = state.snapshot();
        assert_ne!(logged_in.session_epoch, initial.session_epoch);
        assert_eq!(logged_in.token, initial.token);
        set_session_token_with_vault(&state, String::new(), &vault).unwrap();
        let logged_out = state.snapshot();
        assert_ne!(logged_out.session_epoch, logged_in.session_epoch);
        assert_eq!(logged_out.token, None);
        assert!(uuid::Uuid::parse_str(&logged_out.session_epoch).is_ok());
    }

    #[test]
    fn session_write_returns_the_snapshot_owned_by_its_commit() {
        let state = session_store("previous-token");
        let vault = MemorySessionVault::default();
        let committed =
            set_cloud_session_snapshot_with_vault(&state, "candidate-token".into(), &vault)
                .unwrap();
        assert_eq!(committed.token.as_deref(), Some("candidate-token"));
        assert_eq!(committed.session_epoch, state.snapshot().session_epoch);
        set_session_token_with_vault(&state, "later-token".into(), &vault).unwrap();
        assert_ne!(committed.session_epoch, state.snapshot().session_epoch);
        assert_eq!(committed.token.as_deref(), Some("candidate-token"));
    }

    #[test]
    fn stale_session_commit_is_rejected_before_vault_or_cache_changes() {
        let state = session_store("previous-token");
        let vault = MemorySessionVault::default();
        let stale = state.snapshot().session_epoch;
        set_session_token_with_vault(&state, "new-token".into(), &vault).unwrap();
        let current = state.snapshot();
        let result = commit_cloud_session_snapshot_with_vault(
            &state,
            "stale-token".into(),
            Some(&stale),
            None,
            &vault,
        );
        assert!(result.is_err());
        assert_eq!(vault.token.lock().unwrap().as_deref(), Some("new-token"));
        assert_eq!(state.snapshot().session_epoch, current.session_epoch);
        assert_eq!(state.snapshot().token, current.token);
        let committed = commit_cloud_session_snapshot_with_vault(
            &state,
            "current-token".into(),
            Some(&current.session_epoch),
            None,
            &vault,
        )
        .unwrap();
        assert_eq!(committed.token.as_deref(), Some("current-token"));
    }

    #[test]
    fn cancelled_commit_preserves_vault_token_and_epoch() {
        let state = session_store("existing-token");
        let vault = MemorySessionVault {
            token: Mutex::new(Some("existing-token".into())),
            fail_write: false,
        };
        let attempts = crate::session_commit::SessionCommitAttempts::default();
        let attempt = attempts.begin().unwrap();
        let original = state.snapshot();
        assert!(attempts.cancel(&attempt));
        let result = commit_cloud_session_snapshot_with_vault(
            &state,
            "candidate-token".into(),
            Some(&original.session_epoch),
            Some((&attempts, &attempt)),
            &vault,
        );
        assert_eq!(
            result.err().as_deref(),
            Some("Cloud session commit cancelled")
        );
        assert_eq!(state.snapshot().session_epoch, original.session_epoch);
        assert_eq!(state.snapshot().token, original.token);
        assert_eq!(
            vault.token.lock().unwrap().as_deref(),
            Some("existing-token")
        );
    }

    #[test]
    fn stale_epoch_rejection_releases_its_pending_commit_attempt() {
        let state = session_store("existing-token");
        let vault = MemorySessionVault::default();
        let attempts = crate::session_commit::SessionCommitAttempts::default();
        let attempt = attempts.begin().unwrap();
        let stale_epoch = uuid::Uuid::new_v4().to_string();
        let result = commit_cloud_session_snapshot_with_vault(
            &state,
            "candidate-token".into(),
            Some(&stale_epoch),
            Some((&attempts, &attempt)),
            &vault,
        );
        assert!(result.is_err());
        assert!(!attempts.claim(&attempt));
        assert!(vault.token.lock().unwrap().is_none());
    }

    #[test]
    fn cancellation_while_commit_waits_for_session_lock_prevents_vault_write() {
        let state = Arc::new(session_store("existing-token"));
        let vault = Arc::new(MemorySessionVault {
            token: Mutex::new(Some("existing-token".into())),
            fail_write: false,
        });
        let attempts = Arc::new(crate::session_commit::SessionCommitAttempts::default());
        let attempt = attempts.begin().unwrap();
        let epoch = state.snapshot().session_epoch;
        let session_lock = state.0.lock().unwrap();
        let worker_state = state.clone();
        let worker_vault = vault.clone();
        let worker_attempts = attempts.clone();
        let worker_attempt = attempt.clone();
        let (started, ready) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            started.send(()).unwrap();
            commit_cloud_session_snapshot_with_vault(
                &worker_state,
                "candidate-token".into(),
                Some(&epoch),
                Some((&worker_attempts, &worker_attempt)),
                worker_vault.as_ref(),
            )
        });
        ready
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        assert!(attempts.cancel(&attempt));
        drop(session_lock);
        assert_eq!(
            worker.join().unwrap().err().as_deref(),
            Some("Cloud session commit cancelled")
        );
        assert_eq!(
            vault.token.lock().unwrap().as_deref(),
            Some("existing-token")
        );
        assert_eq!(state.snapshot().token.as_deref(), Some("existing-token"));
    }

    #[test]
    fn cancellation_during_vault_write_does_not_report_a_revoked_commit() {
        let state = Arc::new(session_store("existing-token"));
        let attempts = Arc::new(crate::session_commit::SessionCommitAttempts::default());
        let attempt = attempts.begin().unwrap();
        let epoch = state.snapshot().session_epoch;
        let (write_started, write_observed) = std::sync::mpsc::channel();
        let (release_write, write_release) = std::sync::mpsc::channel();
        let vault = Arc::new(DelayedSessionVault {
            token: Mutex::new(Some("existing-token".into())),
            write_started,
            release_write: Mutex::new(write_release),
        });
        let worker_state = state.clone();
        let worker_vault = vault.clone();
        let worker_attempts = attempts.clone();
        let worker_attempt = attempt.clone();
        let worker = std::thread::spawn(move || {
            commit_cloud_session_snapshot_with_vault(
                &worker_state,
                "candidate-token".into(),
                Some(&epoch),
                Some((&worker_attempts, &worker_attempt)),
                worker_vault.as_ref(),
            )
        });
        write_observed
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        let cancelled = attempts.cancel(&attempt);
        release_write.send(()).unwrap();
        let committed = worker.join().unwrap().unwrap();
        assert!(!cancelled);
        assert_eq!(committed.token.as_deref(), Some("candidate-token"));
        assert_eq!(state.snapshot().session_epoch, committed.session_epoch);
        assert!(!attempts.claim(&attempt));
    }

    #[test]
    fn commit_attempt_ipc_only_allows_main_and_rejects_cancelled_write() {
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_proposal::ProposalStore::default())
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(session_store("existing-token"))
            .manage(crate::session_commit::SessionCommitAttempts::default())
            .invoke_handler(tauri::generate_handler![
                begin_cloud_session_commit,
                cancel_cloud_session_commit,
                set_cloud_session_snapshot
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let attempts = app.state::<crate::session_commit::SessionCommitAttempts>();
        let attempt = attempts.begin().unwrap();
        for label in ["capsule", "ask", "unknown", "main"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let invoke_command = |command: &str, body: Value| {
                tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: command.into(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(body),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                )
            };
            let begin = invoke_command("begin_cloud_session_commit", json!({}));
            let cancel = invoke_command(
                "cancel_cloud_session_commit",
                json!({ "attemptId": attempt }),
            );
            if label != "main" {
                for result in [begin, cancel] {
                    assert_eq!(
                        result.unwrap_err(),
                        json!("cloud session access is only allowed from the main window")
                    );
                }
            } else {
                let created = begin.unwrap().deserialize::<String>().unwrap();
                assert!(uuid::Uuid::parse_str(&created).is_ok());
                assert!(cancel.unwrap().deserialize::<bool>().unwrap());
                let snapshot = app.state::<SessionTokenStore>().snapshot();
                let write = invoke_command(
                    "set_cloud_session_snapshot",
                    json!({
                        "token": "candidate-token",
                        "expectedSessionEpoch": snapshot.session_epoch,
                        "attemptId": attempt
                    }),
                );
                assert_eq!(write.unwrap_err(), json!("Cloud session commit cancelled"));
                assert_eq!(
                    app.state::<SessionTokenStore>().snapshot().token,
                    snapshot.token
                );
                assert_eq!(
                    app.state::<SessionTokenStore>().snapshot().session_epoch,
                    snapshot.session_epoch
                );
                assert!(attempts.cancel(&created));
            }
        }
    }

    #[test]
    fn session_token_restores_runtime_cache_after_restart() {
        let state = session_store("");
        let vault = MemorySessionVault {
            token: Mutex::new(Some("restored-token".to_string())),
            fail_write: false,
        };

        let restored = get_session_token_from_vault(&state, &vault).unwrap();

        assert_eq!(restored.as_deref(), Some("restored-token"));
        assert_eq!(
            state.0.lock().unwrap_or_else(|e| e.into_inner()).as_str(),
            "restored-token"
        );
    }

    #[test]
    fn failed_session_vault_write_keeps_previous_runtime_token() {
        let state = session_store("previous-token");
        let vault = MemorySessionVault {
            token: Mutex::new(Some("previous-token".to_string())),
            fail_write: true,
        };

        let error =
            set_session_token_with_vault(&state, "new-token".to_string(), &vault).unwrap_err();

        assert!(error.contains("vault write failed"));
        assert_eq!(
            state.0.lock().unwrap_or_else(|e| e.into_inner()).as_str(),
            "previous-token"
        );
    }

    #[test]
    fn clearing_session_removes_vault_and_runtime_token() {
        let state = session_store("session-token");
        let vault = MemorySessionVault {
            token: Mutex::new(Some("session-token".to_string())),
            fail_write: false,
        };

        set_session_token_with_vault(&state, String::new(), &vault).unwrap();

        assert_eq!(*vault.token.lock().unwrap(), None);
        assert!(state.0.lock().unwrap_or_else(|e| e.into_inner()).is_empty());
    }

    #[test]
    fn config_patch_includes_capsule_auto_hide_change() {
        let mut next = storage::AppConfig::default();
        let previous = next.clone();
        next.capsule_auto_hide = !previous.capsule_auto_hide;

        let patch = config_patch_between(&previous, &next);

        assert_eq!(patch["capsule_auto_hide"], next.capsule_auto_hide);
    }

    #[test]
    fn config_patch_includes_ui_language_change() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.ui_language = "zh".to_string();

        let patch = config_patch_between(&previous, &next);

        assert_eq!(patch["ui_language"], "zh");
    }

    #[test]
    fn config_patch_includes_only_changed_theme() {
        let previous = storage::AppConfig::default();
        for theme in ["light", "dark"] {
            let mut next = previous.clone();
            next.theme = theme.to_string();
            assert_eq!(
                config_patch_between(&previous, &next),
                json!({ "theme": theme })
            );
        }
        assert_eq!(config_patch_between(&previous, &previous), json!({}));
    }

    #[test]
    fn config_patch_includes_learning_setting_without_other_fields() {
        let previous = storage::AppConfig {
            auto_learn_dictionary: true,
            ..Default::default()
        };
        let mut next = previous.clone();
        next.auto_learn_dictionary = false;
        assert_eq!(
            config_patch_between(&previous, &next),
            json!({ "auto_learn_dictionary": false })
        );
        assert_eq!(
            config_patch_between(&next, &previous),
            json!({ "auto_learn_dictionary": true })
        );
    }

    #[test]
    fn config_patch_theme_broadcast_reaches_native_listener_without_empty_events() {
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let received = Arc::new(Mutex::new(Vec::<Value>::new()));
        let captured = received.clone();
        let listener = app.listen_any("config:patch", move |event| {
            captured
                .lock()
                .unwrap()
                .push(serde_json::from_str(event.payload()).unwrap());
        });
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.theme = "dark".to_string();
        emit_config_patch(app.handle(), &config_patch_between(&previous, &next));
        emit_config_patch(app.handle(), &json!({}));
        assert_eq!(*received.lock().unwrap(), vec![json!({ "theme": "dark" })]);
        app.unlisten(listener);
        emit_config_patch(app.handle(), &json!({ "theme": "light" }));
        assert_eq!(received.lock().unwrap().len(), 1);
    }

    #[test]
    fn config_patch_includes_microphone_change_for_the_tray() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.microphone_device = "USB Mic".to_string();

        let patch = config_patch_between(&previous, &next);

        assert_eq!(patch["microphone_device"], "USB Mic");
    }

    #[test]
    fn config_patch_includes_max_recording_seconds_change() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.max_recording_seconds = 45;

        let patch = config_patch_between(&previous, &next);

        assert_eq!(patch["max_recording_seconds"], 45);
    }

    #[test]
    fn config_patch_includes_recording_limit_intent_changes() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.recording_limit_mode = crate::stt::capabilities::RecordingLimitMode::Custom;
        next.custom_recording_limit_seconds = 120;

        let patch = config_patch_between(&previous, &next);

        assert_eq!(patch["recording_limit_mode"], "custom");
        assert_eq!(patch["custom_recording_limit_seconds"], 120);
    }

    #[test]
    fn prepare_config_recomputes_auto_mirror_when_provider_changes() {
        let config = storage::AppConfig {
            stt_provider: "deepgram".to_string(),
            ..storage::AppConfig::default()
        };

        let prepared = prepare_config_for_save(config).unwrap();

        assert_eq!(prepared.max_recording_seconds, 600);
        assert_eq!(prepared.custom_recording_limit_seconds, 600);
    }

    #[test]
    fn provider_connection_changes_trigger_pre_warm() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.llm_base_url = "https://api.openai.com/v1".to_string();
        assert!(provider_connection_config_changed(&previous, &next));

        let mut next = previous.clone();
        next.stt_custom_base_url = "http://localhost:9000/v1".to_string();
        assert!(provider_connection_config_changed(&previous, &next));
    }

    #[test]
    fn unrelated_settings_do_not_trigger_provider_pre_warm() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.theme = "dark".to_string();

        assert!(!provider_connection_config_changed(&previous, &next));
    }

    #[test]
    fn prepare_config_clamps_custom_intent_only_when_settings_are_saved() {
        let config = storage::AppConfig {
            recording_limit_mode: crate::stt::capabilities::RecordingLimitMode::Custom,
            custom_recording_limit_seconds: 9_999,
            ..storage::AppConfig::default()
        };

        let prepared = prepare_config_for_save(config).unwrap();

        assert_eq!(prepared.custom_recording_limit_seconds, 30);
        assert_eq!(prepared.max_recording_seconds, 30);
    }

    #[test]
    fn config_patch_broadcasts_model_summary_without_credentials() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.stt_provider = "apple-speech".to_string();
        next.llm_provider = "ollama".to_string();
        next.llm_model = "synthetic-local-model".to_string();
        next.polish_enabled = !previous.polish_enabled;
        next.stt_api_key = "synthetic-stt-secret".to_string();
        next.llm_api_key = "synthetic-llm-secret".to_string();
        let patch = config_patch_between(&previous, &next);
        assert_eq!(
            patch,
            json!({
                "stt_provider": next.stt_provider,
                "llm_provider": next.llm_provider,
                "llm_model": next.llm_model,
                "polish_enabled": next.polish_enabled,
            })
        );
        assert!(!patch.to_string().contains("secret"));
        assert_eq!(config_patch_between(&previous, &previous), json!({}));
    }

    #[test]
    fn config_patch_broadcasts_typed_and_legacy_shortcuts_without_credentials() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.hotkey = "Ctrl+Shift+K".to_string();
        next.ask_hotkey = "Ctrl+Shift+J".to_string();
        next.hotkey_mode = "hold".to_string();
        next.hotkeys.dictation = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+K").unwrap();
        next.hotkeys.dictation_bindings = vec![next.hotkeys.dictation.clone()];
        next.stt_api_key = "synthetic-secret".to_string();
        let patch = config_patch_between(&previous, &next);
        assert_eq!(patch["hotkeys"], json!(next.hotkeys));
        assert_eq!(patch["hotkey"], next.hotkey);
        assert_eq!(patch["ask_hotkey"], next.ask_hotkey);
        assert_eq!(patch["hotkey_mode"], next.hotkey_mode);
        assert_eq!(patch.as_object().unwrap().len(), 4);
        assert!(!patch.to_string().contains("synthetic-secret"));
    }

    #[test]
    fn config_patch_includes_audio_retention_without_unrelated_changes() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.save_recording_audio = false;
        let patch = config_patch_between(&previous, &next);
        assert_eq!(patch, json!({ "save_recording_audio": false }));
        assert_eq!(
            config_patch_between(&next, &previous),
            json!({ "save_recording_audio": true })
        );
        assert_eq!(config_patch_between(&previous, &previous), json!({}));
    }

    #[test]
    fn config_patch_includes_history_privacy_changes() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.history_enabled = false;
        next.history_retention_days = 30;
        next.history_max_entries = 250;

        let patch = config_patch_between(&previous, &next);

        assert_eq!(patch["history_enabled"], false);
        assert_eq!(patch["history_retention_days"], 30);
        assert_eq!(patch["history_max_entries"], 250);
    }

    #[test]
    fn full_config_save_requires_main_window_before_loading_or_registering() {
        let source = include_str!("config.rs");
        let command = source
            .split("\npub async fn update_config(\n")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let guard = command
            .find("ensure_main_session_window(&window)?;")
            .unwrap();
        assert!(guard < command.find("state.load().await").unwrap());
        assert!(guard < command.find("hotkey_refresh_allowed").unwrap());
    }

    #[test]
    fn learning_setting_applies_only_after_a_successful_config_save() {
        let source = include_str!("config.rs");
        let command = source
            .split("\npub async fn update_config(\n")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let applied = command
            .find("crate::edit_learning::apply_enabled_setting(&app, config.auto_learn_dictionary)")
            .unwrap();
        assert!(command.find("return Err(error);").unwrap() < applied);
        assert!(command.find(".save_renderer_config(").unwrap() < applied);
        assert!(applied < command.find("emit_config_patch(&app, &patch);").unwrap());
    }

    #[test]
    fn config_save_checks_capture_pause_before_refreshing_hotkeys() {
        let source = include_str!("config.rs");
        let command = source
            .split("\npub async fn update_config(\n")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        assert!(command.contains("hotkey_refresh_allowed(&hotkey_supervisor)"));
    }

    #[test]
    fn hotkey_refresh_remains_disabled_until_capture_resumes() {
        let supervisor = crate::hotkey::HotkeySupervisor::default();
        assert!(hotkey_refresh_allowed(&supervisor));
        supervisor.disable();
        assert!(!hotkey_refresh_allowed(&supervisor));
        assert_eq!(
            supervisor.snapshot().state,
            crate::hotkey::HotkeySupervisorState::Disabled
        );
        supervisor.begin_registration_attempt();
        assert!(hotkey_refresh_allowed(&supervisor));
    }

    #[test]
    fn renderer_config_never_returns_legacy_provider_secrets() {
        let config = storage::AppConfig {
            stt_api_key: "legacy-stt".to_string(),
            stt_custom_api_key: "legacy-custom".to_string(),
            llm_api_key: "legacy-llm".to_string(),
            ..storage::AppConfig::default()
        };
        let returned = config_for_renderer(config.clone());
        assert!(returned.stt_api_key.is_empty());
        assert!(returned.stt_custom_api_key.is_empty());
        assert!(returned.llm_api_key.is_empty());
        assert_eq!(config.stt_api_key, "legacy-stt");
        assert_eq!(config.stt_custom_api_key, "legacy-custom");
        assert_eq!(config.llm_api_key, "legacy-llm");
        let mut expected = config;
        expected.stt_api_key.clear();
        expected.stt_custom_api_key.clear();
        expected.llm_api_key.clear();
        assert_eq!(
            serde_json::to_value(returned).unwrap(),
            serde_json::to_value(expected).unwrap()
        );
    }

    #[test]
    fn ordinary_settings_save_cannot_reintroduce_legacy_secrets() {
        let config = storage::AppConfig {
            stt_api_key: "stale-stt".to_string(),
            stt_custom_api_key: "stale-custom".to_string(),
            llm_api_key: "stale-llm".to_string(),
            ..storage::AppConfig::default()
        };
        let prepared = prepare_config_for_save(config).unwrap();
        assert!(prepared.stt_api_key.is_empty());
        assert!(prepared.stt_custom_api_key.is_empty());
        assert!(prepared.llm_api_key.is_empty());
    }

    fn native_cloud_config(user_id: &str, age_hours: i64) -> storage::AppConfig {
        use crate::stt::capabilities::{
            ManagedSttCapability, ManagedSttCapabilityState, ManagedSttFormat,
        };
        let received = chrono::Utc::now() - chrono::Duration::hours(age_hours);
        storage::AppConfig {
            stt_provider: "cloud".to_string(),
            managed_stt_capability_state: Some(ManagedSttCapabilityState {
                user_id: user_id.to_string(),
                server_generated_at: received.to_rfc3339(),
                received_at_unix_seconds: received.timestamp(),
                capability: ManagedSttCapability {
                    version: 2,
                    max_recording_seconds: 600,
                    max_multipart_bytes: 4_200_000,
                    formats: vec![
                        ManagedSttFormat {
                            mime_type: "audio/wav".to_string(),
                            max_audio_bytes: 4_000_000,
                            preferred_client_switch_bytes: Some(700_000),
                            bitrate_bits_per_second: None,
                        },
                        ManagedSttFormat {
                            mime_type: "audio/ogg; codecs=opus".to_string(),
                            max_audio_bytes: 4_000_000,
                            preferred_client_switch_bytes: None,
                            bitrate_bits_per_second: Some(48_000),
                        },
                    ],
                },
            }),
            ..storage::AppConfig::default()
        }
    }

    #[test]
    fn onboarding_draft_cannot_clear_a_fresh_native_cloud_limit() {
        let current = native_cloud_config("current-user", 0);
        let draft = storage::AppConfig {
            stt_provider: "cloud".to_string(),
            theme: "dark".to_string(),
            ..storage::AppConfig::default()
        };
        let saved = prepare_config_for_save(storage::config_with_native_managed_state(
            draft,
            Some(&current),
        ))
        .unwrap();
        assert_eq!(saved.max_recording_seconds, 600);
        assert_eq!(saved.theme, "dark");
        assert_eq!(
            saved.managed_stt_capability_state,
            current.managed_stt_capability_state
        );
    }

    #[test]
    fn settings_snapshot_cannot_replace_a_newer_native_capability() {
        let current = native_cloud_config("current-user", 0);
        let stale_draft = native_cloud_config("old-user", 25);
        let saved = prepare_config_for_save(storage::config_with_native_managed_state(
            stale_draft,
            Some(&current),
        ))
        .unwrap();
        assert_eq!(saved.max_recording_seconds, 600);
        assert_eq!(
            saved.managed_stt_capability_state.unwrap().user_id,
            "current-user"
        );
    }

    #[test]
    fn settings_save_cannot_resurrect_a_capability_cleared_on_logout() {
        let cleared = storage::AppConfig {
            stt_provider: "cloud".to_string(),
            ..storage::AppConfig::default()
        };
        let draft = native_cloud_config("logged-out-user", 0);
        let saved = prepare_config_for_save(storage::config_with_native_managed_state(
            draft,
            Some(&cleared),
        ))
        .unwrap();
        assert!(saved.managed_stt_capability_state.is_none());
        assert_eq!(saved.max_recording_seconds, 30);
    }

    #[test]
    fn prepare_config_for_save_rejects_conflicting_hotkeys() {
        let config = storage::AppConfig {
            hotkey: "Ctrl+/".to_string(),
            ask_hotkey: "Ctrl+/".to_string(),
            ..storage::AppConfig::default()
        };

        let error = prepare_config_for_save(config).unwrap_err();

        assert!(error.contains("Dictation and Ask hotkeys"));
    }

    #[test]
    fn trigger_modes_survive_native_preparation_and_settings_roundtrip() {
        for mode in ["hold", "toggle", "auto"] {
            let mut config = storage::AppConfig {
                hotkey_mode: mode.to_string(),
                ..Default::default()
            };
            config.hotkeys.dictation_mode = mode.to_string();
            let extra = storage::ShortcutBinding::from_hotkey("Ctrl+Alt+9").unwrap();
            config.hotkeys.dictation_bindings.push(extra.clone());
            let bindings = config.hotkeys.dictation_bindings.clone();
            let prepared = prepare_config_for_save(config).unwrap();
            let restored =
                storage::AppConfig::from_stored_value(serde_json::to_value(&prepared).unwrap())
                    .unwrap();
            assert_eq!(restored.hotkey_mode, mode);
            assert_eq!(restored.hotkeys.dictation_mode, mode);
            assert_eq!(restored.hotkeys.dictation_bindings, bindings);
            assert_eq!(restored.hotkeys.dictation_bindings.last(), Some(&extra));
        }
    }

    #[test]
    fn prepare_config_for_save_normalizes_typed_hotkeys_before_save() {
        let mut config = storage::AppConfig::default();
        config.hotkeys.dictation = storage::ShortcutBinding {
            primary: ";".to_string(),
            modifiers: vec!["shift".to_string(), "control".to_string()],
        };
        config.hotkeys.ask = Some(storage::ShortcutBinding {
            primary: ".".to_string(),
            modifiers: vec!["control".to_string()],
        });
        config.hotkeys.dictation_mode = "toggle".to_string();

        let prepared = prepare_config_for_save(config).unwrap();

        assert_eq!(prepared.hotkey, "Ctrl+Shift+;");
        assert_eq!(prepared.ask_hotkey, "Ctrl+.");
        assert_eq!(prepared.hotkey_mode, "toggle");
    }

    #[test]
    fn prepare_config_for_save_preserves_disabled_ask_hotkey() {
        let mut config = storage::AppConfig::default();
        config.hotkeys.ask = None;
        config.ask_hotkey = String::new();

        let prepared = prepare_config_for_save(config).unwrap();

        assert_eq!(prepared.hotkeys.ask, None);
        assert_eq!(prepared.ask_hotkey, "");
    }

    #[test]
    fn prepare_config_for_save_rejects_secondary_role_conflicts() {
        let mut config = storage::AppConfig::default();
        config
            .hotkeys
            .dictation_bindings
            .push(storage::ShortcutBinding::from_hotkey("Ctrl+Shift+E").unwrap());
        config.hotkeys.edit_selection = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+E");

        let error = prepare_config_for_save(config).unwrap_err();

        assert!(error.contains("index 1"));
        assert!(error.contains("editSelection"));
    }

    #[test]
    fn hotkey_runtime_config_changed_detects_shortcut_and_mode_changes() {
        let previous = storage::AppConfig::default();

        let mut changed_dictation = previous.clone();
        changed_dictation.hotkey = "Ctrl+Shift+;".to_string();
        changed_dictation = prepare_config_for_save(changed_dictation).unwrap();
        assert!(hotkey_runtime_config_changed(&previous, &changed_dictation));

        let mut changed_ask = previous.clone();
        changed_ask.ask_hotkey = "Ctrl+,".to_string();
        changed_ask = prepare_config_for_save(changed_ask).unwrap();
        assert!(hotkey_runtime_config_changed(&previous, &changed_ask));

        let mut changed_mode = previous.clone();
        changed_mode.hotkey_mode = if previous.hotkey_mode == "toggle" {
            "hold".to_string()
        } else {
            "toggle".to_string()
        };
        changed_mode = prepare_config_for_save(changed_mode).unwrap();
        assert!(hotkey_runtime_config_changed(&previous, &changed_mode));

        let mut changed_secondary = previous.clone();
        changed_secondary
            .hotkeys
            .dictation_bindings
            .push(storage::ShortcutBinding::from_hotkey("F8").unwrap());
        changed_secondary = prepare_config_for_save(changed_secondary).unwrap();
        assert!(hotkey_runtime_config_changed(&previous, &changed_secondary));
    }

    #[test]
    fn hotkey_runtime_config_changed_ignores_unrelated_settings() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.max_recording_seconds = 120;
        next = prepare_config_for_save(next).unwrap();

        assert!(!hotkey_runtime_config_changed(&previous, &next));
    }

    #[test]
    fn hotkey_runtime_refresh_rolls_back_previous_config_when_new_registration_fails() {
        let previous = storage::AppConfig::default();
        let mut next = previous.clone();
        next.hotkey = "Ctrl+Shift+;".to_string();
        next = prepare_config_for_save(next).unwrap();
        let mut attempts = Vec::new();

        let error = refresh_hotkey_runtime_with_rollback(&previous, &next, |config| {
            attempts.push(config.hotkey.clone());
            if config.hotkey == next.hotkey {
                Err("shortcut occupied".to_string())
            } else {
                Ok(())
            }
        })
        .unwrap_err();

        assert_eq!(attempts, vec![next.hotkey, previous.hotkey]);
        assert_eq!(error.registration_error, "shortcut occupied");
        assert_eq!(error.rollback_error, None);
    }

    #[test]
    fn superseded_hotkey_refresh_does_not_rollback_over_a_new_capture() {
        let previous = storage::AppConfig::default();
        let next = previous.clone();
        let mut attempts = 0;
        let error = refresh_hotkey_runtime_with_rollback(&previous, &next, |_| {
            attempts += 1;
            Err(crate::commands::misc::HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string())
        })
        .unwrap_err();
        assert_eq!(attempts, 1);
        assert_eq!(
            error.registration_error,
            crate::commands::misc::HOTKEY_REGISTRATION_SUPERSEDED_ERROR
        );
    }

    #[test]
    fn config_feedback_cannot_clear_a_new_capture_error() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let supervisor = crate::hotkey::HotkeySupervisor::default();
        let generation = supervisor.begin_registration_attempt();
        let feedback = HotkeyRegistrationError(std::sync::Arc::new(std::sync::Mutex::new(Some(
            "current capture error".into(),
        ))));
        supervisor.acquire_capture("main", "new").unwrap();
        assert!(!commit_input_registration_feedback(
            app.handle(),
            &supervisor,
            generation,
            &feedback,
            None,
        ));
        assert_eq!(
            feedback.0.lock().unwrap().as_deref(),
            Some("current capture error")
        );
    }

    #[test]
    fn configured_shortcuts_are_not_enabled_before_persistence_and_cache_publication() {
        let source = include_str!("config.rs");
        let command = source
            .split("\npub async fn update_config(\n")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let save = command.find(".save_renderer_config(").unwrap();
        let publication = command.find("update_runtime_caches(").unwrap();
        let activation = command
            .rfind("commit_input_registration_feedback(")
            .unwrap();
        assert!(save < publication);
        assert!(publication < activation);
    }

    #[test]
    fn successful_persistence_is_not_reported_as_failed_when_activation_is_superseded() {
        let source = include_str!("config.rs");
        let command = source
            .split("\npub async fn update_config(\n")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let committed = command
            .split("let patch = config_patch_between(&previous, &config);")
            .nth(1)
            .unwrap();
        assert!(!committed.contains("return Err("));
        assert!(committed.contains("emit_config_patch(&app, &patch);"));
    }
}
