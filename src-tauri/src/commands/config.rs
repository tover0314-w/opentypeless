use crate::storage;
use crate::CloseToTrayCache;
use crate::HotkeyModeCache;
use crate::SessionTokenStore;
use serde_json::{json, Map, Value};
use tauri::Emitter;

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
    if previous.ui_language != next.ui_language {
        patch.insert("ui_language".to_string(), json!(next.ui_language));
    }
    if previous.save_recordings != next.save_recordings {
        patch.insert("save_recordings".to_string(), json!(next.save_recordings));
    }
    if previous.recording_format != next.recording_format {
        patch.insert(
            "recording_format".to_string(),
            json!(next.recording_format),
        );
    }
    Value::Object(patch)
}

fn emit_config_patch(app: &tauri::AppHandle, patch: &Value) {
    if patch.as_object().is_some_and(|object| !object.is_empty()) {
        let _ = app.emit("config:patch", patch.clone());
    }
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
pub async fn update_config(
    app: tauri::AppHandle,
    state: tauri::State<'_, storage::ConfigManager>,
    cache: tauri::State<'_, HotkeyModeCache>,
    close_tray_cache: tauri::State<'_, CloseToTrayCache>,
    config: storage::AppConfig,
) -> Result<(), String> {
    let previous = state.load().await.map_err(|e| e.to_string())?;
    let patch = config_patch_between(&previous, &config);
    *cache.0.lock().unwrap_or_else(|e| e.into_inner()) = config.hotkey_mode.clone();
    *close_tray_cache.0.lock().unwrap_or_else(|e| e.into_inner()) = config.close_to_tray;
    state.save(&config).await.map_err(|e| e.to_string())?;
    emit_config_patch(&app, &patch);
    if patch.get("ui_language").is_some() || patch.get("capsule_auto_hide").is_some() {
        crate::refresh_tray(&app);
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
pub async fn set_session_token(
    state: tauri::State<'_, SessionTokenStore>,
    token: String,
) -> Result<(), String> {
    *state.0.lock().unwrap_or_else(|e| e.into_inner()) = token;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

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
}
