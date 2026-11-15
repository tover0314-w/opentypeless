use tauri::Emitter;

use crate::{pipeline::PipelineHandle, storage};

#[tauri::command]
pub async fn set_active_translation_target(
    app: tauri::AppHandle,
    code: String,
    pipeline: tauri::State<'_, PipelineHandle>,
    config_manager: tauri::State<'_, storage::ConfigManager>,
) -> Result<storage::TranslationConfig, String> {
    let code = code.trim().to_ascii_lowercase();
    let mut operation = pipeline
        .active_translation_id()
        .ok_or_else(|| "translation_not_recording".to_string())?;
    let config = config_manager
        .load()
        .await
        .map_err(|error| error.to_string())?;
    let mut committed = None;
    let commit_slot = &mut committed;
    let translation = save_translation_target(
        config,
        code.clone(),
        |target| {
            let (previous, revision) =
                pipeline.switch_active_translation_target(&operation, target)?;
            operation = revision;
            Ok(previous)
        },
        |_config| async move {
            let (revision, updated) = config_manager
                .try_update_current_config_commit(|current| {
                    if !apply_translation_target(current, &code) {
                        anyhow::bail!("translation_target_not_configured");
                    }
                    Ok(())
                })
                .map_err(|error| error.to_string())?;
            let translation = updated.translation.clone();
            *commit_slot = Some((config_manager.config_epoch().to_string(), revision, updated));
            Ok(translation)
        },
    )
    .await?;

    if let Some((epoch, revision, config)) = committed {
        super::config::emit_config_commit(&app, &epoch, revision, &config);
    }
    let _ = app.emit("translation:target-changed", &translation.active_target);
    let _ = app.emit(
        "config:patch",
        serde_json::json!({
            "target_lang": translation.active_target,
            "translation": translation.clone(),
        }),
    );
    Ok(translation)
}

async fn save_translation_target<Save, SaveFuture>(
    mut config: storage::AppConfig,
    code: String,
    mut switch_target: impl FnMut(String) -> Result<String, String>,
    save: Save,
) -> Result<storage::TranslationConfig, String>
where
    Save: FnOnce(storage::AppConfig) -> SaveFuture,
    SaveFuture: std::future::Future<Output = Result<storage::TranslationConfig, String>>,
{
    if !config.translation.targets.contains(&code) {
        return Err("translation_target_not_configured".to_string());
    }

    let previous_operation_target = switch_target(code.clone())?;
    config.translation.active_target = code.clone();
    config.target_lang = code.clone();
    match save(config).await {
        Ok(translation) => Ok(translation),
        Err(error) => {
            let _ = switch_target(previous_operation_target);
            Err(error)
        }
    }
}

pub(crate) fn apply_translation_target(config: &mut storage::AppConfig, code: &str) -> bool {
    if !config
        .translation
        .targets
        .iter()
        .any(|target| target == code)
    {
        return false;
    }
    config.translation.active_target = code.into();
    config.target_lang = code.into();
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::cell::{Cell, RefCell};

    #[test]
    fn translation_target_notifications_use_a_validated_owned_commit() {
        let command = include_str!("translation.rs")
            .split("pub async fn set_active_translation_target(")
            .nth(1)
            .unwrap()
            .split("async fn save_translation_target")
            .next()
            .unwrap();
        assert!(command.contains(".try_update_current_config_commit("));
        assert!(command.contains("emit_config_commit("));
        assert!(!command.contains("let mut applied"));
        assert!(!command.contains(".cached_input_commit()"));
    }

    #[test]
    fn translation_commit_preserves_latest_unrelated_settings_and_target_list() {
        let mut latest = storage::AppConfig {
            llm_model: "new-model".into(),
            history_enabled: false,
            ..Default::default()
        };
        latest.translation.targets = vec!["en".into(), "fr".into(), "de".into()];
        assert!(apply_translation_target(&mut latest, "fr"));
        assert_eq!(latest.llm_model, "new-model");
        assert!(!latest.history_enabled);
        assert_eq!(latest.translation.targets, ["en", "fr", "de"]);
        assert_eq!(latest.translation.active_target, "fr");
        assert_eq!(latest.target_lang, "fr");
        let before = latest.clone();
        assert!(!apply_translation_target(&mut latest, "ja"));
        assert_eq!(
            serde_json::to_value(latest).unwrap(),
            serde_json::to_value(before).unwrap()
        );
    }

    #[tokio::test]
    async fn failed_translation_save_restores_target_and_allows_retry() {
        let mut config = storage::AppConfig::default();
        config.translation.targets = vec!["ja".into(), "fr".into()];
        config.translation.active_target = "ja".into();
        config.target_lang = "ja".into();
        let target = RefCell::new("ja".to_string());
        let saves = Cell::new(0);
        let switch = |next| Ok(std::mem::replace(&mut *target.borrow_mut(), next));
        let failed = save_translation_target(config.clone(), "fr".into(), switch, |candidate| {
            saves.set(saves.get() + 1);
            assert_eq!(candidate.translation.active_target, "fr");
            assert_eq!(candidate.target_lang, "fr");
            std::future::ready(Err("injected save failure".into()))
        })
        .await;
        assert_eq!(failed.unwrap_err(), "injected save failure");
        assert_eq!(*target.borrow(), "ja");
        assert_eq!(saves.get(), 1);
        let retry = save_translation_target(config, "fr".into(), switch, |candidate| {
            saves.set(saves.get() + 1);
            std::future::ready(Ok(candidate.translation))
        })
        .await
        .unwrap();
        assert_eq!(retry.active_target, "fr");
        assert_eq!(*target.borrow(), "fr");
        assert_eq!(saves.get(), 2);
    }

    #[tokio::test]
    async fn finished_translation_operation_does_not_attempt_persistence() {
        let mut config = storage::AppConfig::default();
        config.translation.targets = vec!["ja".into()];
        let saves = Cell::new(0);
        let switches = Cell::new(0);
        let result = save_translation_target(
            config,
            "ja".into(),
            |_| {
                switches.set(switches.get() + 1);
                Err("translation_operation_finished".into())
            },
            |_| {
                saves.set(saves.get() + 1);
                std::future::ready(Ok(storage::TranslationConfig::default()))
            },
        )
        .await;
        assert_eq!(result.unwrap_err(), "translation_operation_finished");
        assert_eq!(switches.get(), 1);
        assert_eq!(saves.get(), 0);
    }

    #[tokio::test]
    async fn failed_translation_restore_preserves_the_original_save_error() {
        let mut config = storage::AppConfig::default();
        config.translation.targets = vec!["ja".into(), "fr".into()];
        let switches = RefCell::new(Vec::new());
        let saves = Cell::new(0);
        let result = save_translation_target(
            config,
            "fr".into(),
            |target| {
                switches.borrow_mut().push(target);
                if switches.borrow().len() == 1 {
                    Ok("ja".into())
                } else {
                    Err("translation_operation_finished".into())
                }
            },
            |_| {
                saves.set(saves.get() + 1);
                std::future::ready(Err("original persistence error".into()))
            },
        )
        .await;
        assert_eq!(result.unwrap_err(), "original persistence error");
        assert_eq!(*switches.borrow(), vec!["fr", "ja"]);
        assert_eq!(saves.get(), 1);
    }

    #[tokio::test]
    async fn unconfigured_translation_target_does_not_switch_or_save() {
        let mut config = storage::AppConfig::default();
        config.translation.targets = vec!["ja".into()];
        let saves = Cell::new(0);
        let result = save_translation_target(
            config,
            "fr".into(),
            |_| panic!("must not switch"),
            |_| {
                saves.set(saves.get() + 1);
                std::future::ready(Ok::<storage::TranslationConfig, String>(
                    storage::TranslationConfig::default(),
                ))
            },
        )
        .await;
        assert_eq!(result.unwrap_err(), "translation_target_not_configured");
        assert_eq!(saves.get(), 0);
    }
}
