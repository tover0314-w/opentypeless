use crate::credentials::{
    self, CredentialSecretReader, CredentialSecretRemover, CredentialVault, SystemCredentialVault,
};
use crate::storage;
use crate::AskHotkeyCache;
use crate::CloseToTrayCache;
use crate::HotkeyModeCache;
use crate::HotkeyRegistrationError;
use crate::HotkeyRoleCache;
use crate::SessionTokenStore;
use serde_json::{json, Map, Value};
use tauri::{Emitter, Manager, Window};

fn config_patch_between(previous: &storage::AppConfig, next: &storage::AppConfig) -> Value {
    let mut patch = Map::new();
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
    Value::Object(patch)
}

fn emit_config_patch(app: &tauri::AppHandle, patch: &Value) {
    if patch.as_object().is_some_and(|object| !object.is_empty()) {
        let _ = app.emit("config:patch", patch.clone());
    }
}

fn prepare_config_for_save(mut config: storage::AppConfig) -> Result<storage::AppConfig, String> {
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
    role_cache: &HotkeyRoleCache,
    close_tray_cache: &CloseToTrayCache,
    config: &storage::AppConfig,
) {
    *hotkey_mode_cache
        .0
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = config.hotkey_mode.clone();
    *ask_cache.0.lock().unwrap_or_else(|e| e.into_inner()) = config.ask_hotkey.clone();
    *role_cache.0.lock().unwrap_or_else(|e| e.into_inner()) =
        crate::hotkey::hotkey_registration_plan_from_config(&config.hotkeys).unwrap_or_default();
    *close_tray_cache.0.lock().unwrap_or_else(|e| e.into_inner()) = config.close_to_tray;
}

fn set_hotkey_registration_error(hotkey_error: &HotkeyRegistrationError, message: Option<String>) {
    *hotkey_error.0.lock().unwrap_or_else(|e| e.into_inner()) = message;
}

pub(crate) async fn save_capsule_auto_hide(
    app: &tauri::AppHandle,
    state: &storage::ConfigManager,
    enabled: bool,
) -> Result<(), String> {
    let mut config = state.load().await.map_err(|e| e.to_string())?;
    if config.capsule_auto_hide == enabled {
        return Ok(());
    }
    config.capsule_auto_hide = enabled;
    state.save(&config).await.map_err(|e| e.to_string())?;
    let patch = json!({ "capsule_auto_hide": enabled });
    emit_config_patch(app, &patch);
    crate::refresh_tray(app);
    Ok(())
}

#[tauri::command]
pub async fn get_config(
    state: tauri::State<'_, storage::ConfigManager>,
) -> Result<storage::AppConfig, String> {
    state.load().await.map_err(|e| e.to_string())
}

#[tauri::command]
#[allow(clippy::too_many_arguments)]
pub async fn update_config(
    app: tauri::AppHandle,
    state: tauri::State<'_, storage::ConfigManager>,
    cache: tauri::State<'_, HotkeyModeCache>,
    ask_cache: tauri::State<'_, AskHotkeyCache>,
    hotkey_error: tauri::State<'_, HotkeyRegistrationError>,
    hotkey_supervisor: tauri::State<'_, crate::hotkey::HotkeySupervisor>,
    role_cache: tauri::State<'_, HotkeyRoleCache>,
    close_tray_cache: tauri::State<'_, CloseToTrayCache>,
    config: storage::AppConfig,
) -> Result<(), String> {
    let previous = state.load().await.map_err(|e| e.to_string())?;
    let config = prepare_config_for_save(config)?;
    let patch = config_patch_between(&previous, &config);
    let refresh_hotkeys = hotkey_runtime_config_changed(&previous, &config);
    let refresh_provider_connections = provider_connection_config_changed(&previous, &config);

    if refresh_hotkeys {
        let generation = hotkey_supervisor.wake_for_settings_change();
        if let Err(error) = refresh_hotkey_runtime_with_rollback(&previous, &config, |candidate| {
            crate::commands::misc::register_configured_shortcuts(&app, candidate)
        }) {
            if let Some(rollback_error) = error.rollback_error {
                hotkey_supervisor.record_registration_failure(generation, rollback_error.clone());
                set_hotkey_registration_error(&hotkey_error, Some(rollback_error));
            } else {
                hotkey_supervisor.record_registration_success(generation);
                set_hotkey_registration_error(&hotkey_error, None);
            }
            return Err(error.registration_error);
        }
        hotkey_supervisor.record_registration_success(generation);
        set_hotkey_registration_error(&hotkey_error, None);
    }

    if let Err(error) = state.save(&config).await.map_err(|e| e.to_string()) {
        if refresh_hotkeys {
            let rollback_generation = hotkey_supervisor.wake_for_settings_change();
            let rollback_error =
                crate::commands::misc::register_configured_shortcuts(&app, &previous).err();
            if let Some(rollback_error) = rollback_error {
                hotkey_supervisor
                    .record_registration_failure(rollback_generation, rollback_error.clone());
                set_hotkey_registration_error(&hotkey_error, Some(rollback_error));
            } else {
                hotkey_supervisor.record_registration_success(rollback_generation);
                set_hotkey_registration_error(&hotkey_error, None);
            }
        }
        return Err(error);
    }

    update_runtime_caches(&cache, &ask_cache, &role_cache, &close_tray_cache, &config);
    emit_config_patch(&app, &patch);
    if patch.get("ui_language").is_some() || patch.get("capsule_auto_hide").is_some() {
        crate::refresh_tray(&app);
    }
    if refresh_provider_connections {
        if let Some(pipeline) = app.try_state::<crate::pipeline::PipelineHandle>() {
            let pipeline = pipeline.inner().clone();
            tauri::async_runtime::spawn(async move {
                pipeline.pre_warm().await;
            });
        }
    }
    Ok(())
}

#[tauri::command]
pub async fn set_auto_start(
    app: tauri::AppHandle,
    config_state: tauri::State<'_, storage::ConfigManager>,
    enabled: bool,
) -> Result<(), String> {
    use tauri_plugin_autostart::ManagerExt;
    let autolaunch = app.autolaunch();
    if enabled {
        autolaunch.enable().map_err(|e| e.to_string())?;
    } else {
        autolaunch.disable().map_err(|e| e.to_string())?;
    }
    let mut config = config_state.load().await.map_err(|e| e.to_string())?;
    config.auto_start = enabled;
    config_state
        .save(&config)
        .await
        .map_err(|e| e.to_string())?;
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
pub fn get_session_token(
    window: Window,
    state: tauri::State<'_, SessionTokenStore>,
) -> Result<Option<String>, String> {
    ensure_main_session_window(&window)?;
    get_session_token_from_vault(state.inner(), &SystemCredentialVault)
}

#[tauri::command]
pub async fn set_session_token(
    window: Window,
    state: tauri::State<'_, SessionTokenStore>,
    token: String,
) -> Result<(), String> {
    ensure_main_session_window(&window)?;
    set_session_token_with_vault(state.inner(), token, &SystemCredentialVault)
}

fn ensure_main_session_window(window: &Window) -> Result<(), String> {
    if window.label() == "main" {
        Ok(())
    } else {
        Err("cloud session access is only allowed from the main window".to_string())
    }
}

fn get_session_token_from_vault<V: CredentialSecretReader>(
    state: &SessionTokenStore,
    vault: &V,
) -> Result<Option<String>, String> {
    let cached = state.0.lock().unwrap_or_else(|e| e.into_inner()).clone();
    if !cached.trim().is_empty() {
        return Ok(Some(cached));
    }

    let restored = credentials::load_cloud_session_token(vault).map_err(|e| e.to_string())?;
    if let Some(token) = restored.as_ref() {
        *state.0.lock().unwrap_or_else(|e| e.into_inner()) = token.clone();
    }
    Ok(restored)
}

fn set_session_token_with_vault<
    V: CredentialVault + CredentialSecretReader + CredentialSecretRemover,
>(
    state: &SessionTokenStore,
    token: String,
    vault: &V,
) -> Result<(), String> {
    if token.trim().is_empty() {
        credentials::remove_cloud_session_token(vault).map_err(|e| e.to_string())?;
        state.0.lock().unwrap_or_else(|e| e.into_inner()).clear();
    } else {
        credentials::store_cloud_session_token(vault, &token).map_err(|e| e.to_string())?;
        *state.0.lock().unwrap_or_else(|e| e.into_inner()) = token;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::{anyhow, Result};
    use std::sync::{Arc, Mutex};

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
        SessionTokenStore(Arc::new(Mutex::new(token.to_string())))
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
            token: Mutex::new(None),
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
}
