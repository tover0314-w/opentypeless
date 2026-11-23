use crate::app_detector::types::ContextFamily;
use crate::app_detector::user_mappings::{
    CustomAppMappingView, MappingCandidateView, UserAppMappingStore,
};
use crate::app_detector::ContextDetectorHandle;
use crate::storage::{AppConfig, FamilySceneAssignment};
use serde::Deserialize;
use tauri::Emitter;

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SaveCustomAppMappingInput {
    pub candidate_generation: u64,
    pub label: String,
    pub family: ContextFamily,
    pub scene_id: Option<String>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct UpdateCustomAppMappingInput {
    pub id: String,
    pub label: String,
    pub family: ContextFamily,
    pub scene_id: Option<String>,
    pub enabled: bool,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase")]
pub struct SetFamilySceneAssignmentInput {
    pub family: ContextFamily,
    pub scene_id: Option<String>,
}

fn validate_optional_scene_id(
    config: &AppConfig,
    scene_id: Option<String>,
) -> Result<Option<String>, String> {
    let scene_id = scene_id
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());
    let Some(scene_id) = scene_id else {
        return Ok(None);
    };
    crate::storage::scene_prompt_for_id(config, &scene_id)
        .is_some()
        .then_some(Some(scene_id))
        .ok_or_else(|| "custom_app_mapping_scene_not_found".to_string())
}

fn set_family_assignment_value(
    config: &mut AppConfig,
    family: ContextFamily,
    scene_id: Option<String>,
) -> Result<(), String> {
    let scene_id = validate_optional_scene_id(config, scene_id)?;
    config
        .family_scene_assignments
        .retain(|assignment| assignment.family != family);
    if let Some(scene_id) = scene_id {
        config
            .family_scene_assignments
            .push(FamilySceneAssignment { family, scene_id });
    }
    Ok(())
}

#[tauri::command]
pub fn get_latest_mapping_candidate(
    detector: tauri::State<'_, ContextDetectorHandle>,
) -> Option<MappingCandidateView> {
    detector.latest_mapping_candidate()
}

#[tauri::command]
pub fn list_custom_app_mappings(
    mapping_store: tauri::State<'_, UserAppMappingStore>,
) -> Vec<CustomAppMappingView> {
    mapping_store.list_views()
}

#[tauri::command]
pub async fn save_custom_app_mapping(
    detector: tauri::State<'_, ContextDetectorHandle>,
    mapping_store: tauri::State<'_, UserAppMappingStore>,
    config_manager: tauri::State<'_, crate::storage::ConfigManager>,
    input: SaveCustomAppMappingInput,
) -> Result<CustomAppMappingView, String> {
    let config = config_manager
        .load()
        .await
        .map_err(|error| error.to_string())?;
    let scene_id = validate_optional_scene_id(&config, input.scene_id)?;
    let candidate = detector
        .mapping_candidate_for_generation(input.candidate_generation)
        .ok_or_else(|| "custom_app_mapping_candidate_expired".to_string())?;
    let mapping = mapping_store.save_candidate(&candidate, &input.label, input.family, scene_id)?;
    detector.clear_mapping_candidate(input.candidate_generation);
    detector.notify_focus_changed();
    Ok(mapping)
}

#[tauri::command]
pub async fn update_custom_app_mapping(
    detector: tauri::State<'_, ContextDetectorHandle>,
    mapping_store: tauri::State<'_, UserAppMappingStore>,
    config_manager: tauri::State<'_, crate::storage::ConfigManager>,
    input: UpdateCustomAppMappingInput,
) -> Result<CustomAppMappingView, String> {
    let config = config_manager
        .load()
        .await
        .map_err(|error| error.to_string())?;
    let scene_id = validate_optional_scene_id(&config, input.scene_id)?;
    let mapping = mapping_store.update(
        input.id.trim(),
        &input.label,
        input.family,
        scene_id,
        input.enabled,
    )?;
    detector.notify_focus_changed();
    Ok(mapping)
}

#[tauri::command]
pub fn set_custom_app_mapping_enabled(
    detector: tauri::State<'_, ContextDetectorHandle>,
    mapping_store: tauri::State<'_, UserAppMappingStore>,
    id: String,
    enabled: bool,
) -> Result<(), String> {
    mapping_store.set_enabled(id.trim(), enabled)?;
    detector.notify_focus_changed();
    Ok(())
}

#[tauri::command]
pub fn delete_custom_app_mapping(
    detector: tauri::State<'_, ContextDetectorHandle>,
    mapping_store: tauri::State<'_, UserAppMappingStore>,
    id: String,
) -> Result<(), String> {
    mapping_store.delete(id.trim())?;
    detector.notify_focus_changed();
    Ok(())
}

#[tauri::command]
pub fn reset_custom_app_mappings(
    detector: tauri::State<'_, ContextDetectorHandle>,
    mapping_store: tauri::State<'_, UserAppMappingStore>,
) -> Result<(), String> {
    mapping_store.reset()?;
    detector.notify_focus_changed();
    Ok(())
}

#[tauri::command]
pub async fn set_family_scene_assignment(
    app: tauri::AppHandle,
    config_manager: tauri::State<'_, crate::storage::ConfigManager>,
    input: SetFamilySceneAssignmentInput,
) -> Result<Vec<FamilySceneAssignment>, String> {
    config_manager
        .load()
        .await
        .map_err(|error| error.to_string())?;
    let (revision, config) = config_manager
        .try_update_current_config_commit(|current| {
            set_family_assignment_value(current, input.family, input.scene_id)
                .map_err(anyhow::Error::msg)
        })
        .map_err(|error| error.to_string())?;
    super::config::emit_config_commit(&app, config_manager.config_epoch(), revision, &config);
    let assignments = config.family_scene_assignments;
    let _ = app.emit(
        "config:patch",
        serde_json::json!({ "family_scene_assignments": assignments }),
    );
    Ok(assignments)
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::{ActiveScene, CustomScene};

    #[test]
    fn mapping_scene_validation_accepts_known_scenes_only() {
        let mut config = AppConfig::default();
        config.custom_scenes.push(CustomScene {
            id: "custom_focus".to_string(),
            name: "Focus".to_string(),
            description: String::new(),
            prompt_template: "Use short bullets.".to_string(),
            created_at: String::new(),
            updated_at: String::new(),
        });

        assert_eq!(
            validate_optional_scene_id(&config, Some("  builtin_professional_email  ".to_string()))
                .unwrap(),
            Some("builtin_professional_email".to_string())
        );
        assert_eq!(
            validate_optional_scene_id(&config, Some("custom_focus".to_string())).unwrap(),
            Some("custom_focus".to_string())
        );
        assert_eq!(
            validate_optional_scene_id(&config, Some("  ".to_string())).unwrap(),
            None
        );
        assert_eq!(
            validate_optional_scene_id(&config, Some("missing".to_string())).unwrap_err(),
            "custom_app_mapping_scene_not_found"
        );
    }

    #[test]
    fn family_assignment_update_is_unique_and_supports_clear() {
        let mut config = AppConfig {
            family_scene_assignments: vec![FamilySceneAssignment {
                family: ContextFamily::Email,
                scene_id: "builtin_clean_dictation".to_string(),
            }],
            ..Default::default()
        };

        set_family_assignment_value(
            &mut config,
            ContextFamily::Email,
            Some("builtin_professional_email".to_string()),
        )
        .unwrap();
        assert_eq!(
            config.family_scene_assignments,
            vec![FamilySceneAssignment {
                family: ContextFamily::Email,
                scene_id: "builtin_professional_email".to_string(),
            }]
        );

        set_family_assignment_value(&mut config, ContextFamily::Email, None).unwrap();
        assert!(config.family_scene_assignments.is_empty());
    }

    #[test]
    fn manual_scene_does_not_block_saving_an_automatic_assignment() {
        let mut config = AppConfig {
            active_scene: Some(ActiveScene {
                id: "builtin_meeting_notes".to_string(),
                source: "builtin".to_string(),
                name: "Meeting Notes".to_string(),
                prompt_template: "Manual scene wins at runtime.".to_string(),
            }),
            ..Default::default()
        };

        set_family_assignment_value(
            &mut config,
            ContextFamily::WorkChat,
            Some("builtin_clean_dictation".to_string()),
        )
        .unwrap();

        assert_eq!(config.family_scene_assignments.len(), 1);
        assert!(config.active_scene.is_some());
    }
}
