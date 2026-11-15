use crate::storage;
use tauri::Manager;

fn validate_retention_cleanup(
    window_label: &str,
    current: &storage::AppConfig,
    enabled: bool,
    retention_days: u32,
) -> Result<(), String> {
    if window_label != "main" {
        return Err("History retention is only available in the main window".to_string());
    }
    if current.history_enabled != enabled || current.history_retention_days != retention_days {
        return Err("History retention settings changed before cleanup".to_string());
    }
    Ok(())
}

#[tauri::command]
pub async fn prune_history_retention(
    window: tauri::Window,
    state: tauri::State<'_, storage::HistoryStore>,
    config: tauri::State<'_, storage::ConfigManager>,
    enabled: bool,
    retention_days: u32,
) -> Result<Vec<storage::HistoryEntry>, String> {
    if window.label() != "main" {
        return Err("History retention is only available in the main window".to_string());
    }
    let current = config.load().await.map_err(|error| error.to_string())?;
    validate_retention_cleanup(window.label(), &current, enabled, retention_days)?;
    config
        .prune_confirmed_history(
            state.inner(),
            enabled,
            retention_days,
            &chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string(),
        )
        .map_err(|error| error.to_string())?;
    state.list(200, 0).await.map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn export_history_audio<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    state: tauri::State<'_, storage::HistoryStore>,
    id: i64,
) -> Result<String, String> {
    ensure_history_change_window(window.label())?;
    let audio = state
        .read_audio(id)
        .await
        .map_err(|error| error.to_string())?
        .ok_or_else(|| "No audio saved for this history entry".to_string())?;
    let directory = app
        .path()
        .download_dir()
        .map_err(|error| error.to_string())?;
    let path = tauri::async_runtime::spawn_blocking(move || {
        crate::audio::archive::export_wav(&directory, id, &audio)
    })
    .await
    .map_err(|error| error.to_string())?
    .map_err(|error| error.to_string())?;
    Ok(path.to_string_lossy().into_owned())
}

#[tauri::command]
pub async fn get_history<R: tauri::Runtime>(
    window: tauri::Window<R>,
    state: tauri::State<'_, storage::HistoryStore>,
    limit: u32,
    offset: u32,
) -> Result<Vec<storage::HistoryEntry>, String> {
    if window.label() != "main" {
        return Err("History access is only available in the main window".to_string());
    }
    state
        .list(history_query_limit(limit), offset)
        .await
        .map_err(|e| e.to_string())
}

fn history_query_limit(limit: u32) -> u32 {
    limit.min(storage::DEFAULT_HISTORY_MAX_ENTRIES)
}

#[tauri::command]
pub async fn clear_history<R: tauri::Runtime>(
    window: tauri::Window<R>,
    state: tauri::State<'_, storage::HistoryStore>,
) -> Result<(), String> {
    ensure_history_change_window(window.label())?;
    state.clear().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_history_entry<R: tauri::Runtime>(
    window: tauri::Window<R>,
    state: tauri::State<'_, storage::HistoryStore>,
    id: i64,
) -> Result<bool, String> {
    ensure_history_change_window(window.label())?;
    state.delete(id).await.map_err(|e| e.to_string())
}

fn ensure_history_change_window(label: &str) -> Result<(), String> {
    if label == "main" {
        Ok(())
    } else {
        Err("History changes are only available in the main window".to_string())
    }
}

#[tauri::command]
pub async fn get_usage_stats(
    state: tauri::State<'_, storage::HistoryStore>,
) -> Result<storage::stats::UsageStats, String> {
    state
        .stats(chrono::Local::now().date_naive())
        .await
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn history_query_limit_preserves_pagination_and_bounds_large_requests() {
        for limit in [0, 1, 200, 5000] {
            assert_eq!(history_query_limit(limit), limit);
        }
        assert_eq!(history_query_limit(5001), 5000);
        assert_eq!(history_query_limit(u32::MAX), 5000);
    }

    #[test]
    fn audio_export_checks_window_before_reading_audio_or_resolving_downloads() {
        let source = include_str!("history.rs");
        let command = source
            .split("pub async fn export_history_audio")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let guard = command
            .find("ensure_history_change_window(window.label())?;")
            .unwrap();
        assert!(guard < command.find(".read_audio(id)").unwrap());
        assert!(guard < command.find(".download_dir()").unwrap());
    }

    #[test]
    fn auxiliary_history_delete_ipc_preserves_records() {
        let directory =
            std::env::temp_dir().join(format!("otl-history-ipc-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("history.db");
        let history = storage::HistoryStore::new(filename.clone()).unwrap();
        let connection = rusqlite::Connection::open(&filename).unwrap();
        connection
            .execute(
                "INSERT INTO history(created_at, raw_text, polished_text) VALUES (?1, ?2, ?2)",
                ["2026-11-10T10:00:00", "Synthetic history"],
            )
            .unwrap();
        let app = tauri::test::mock_builder()
            .manage(history)
            .invoke_handler(tauri::generate_handler![
                clear_history,
                delete_history_entry,
                get_history,
                export_history_audio
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &webview,
                tauri::webview::InvokeRequest {
                    cmd: "get_history".to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(
                        serde_json::json!({"limit": 200, "offset": 0}),
                    ),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            assert_eq!(
                result.unwrap_err(),
                serde_json::json!("History access is only available in the main window")
            );
            let result = tauri::test::get_ipc_response(
                &webview,
                tauri::webview::InvokeRequest {
                    cmd: "export_history_audio".to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({"id": 1})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            assert_eq!(
                result.unwrap_err(),
                serde_json::json!("History changes are only available in the main window")
            );
            for command in ["clear_history", "delete_history_entry"] {
                let result = tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: command.to_string(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({"id": 1})),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                assert_eq!(
                    result.unwrap_err(),
                    serde_json::json!("History changes are only available in the main window")
                );
                assert_eq!(
                    connection
                        .query_row("SELECT COUNT(*) FROM history", [], |row| row
                            .get::<_, i64>(0))
                        .unwrap(),
                    1
                );
            }
        }
        let main = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let result = tauri::test::get_ipc_response(
            &main,
            tauri::webview::InvokeRequest {
                cmd: "get_history".to_string(),
                callback: tauri::ipc::CallbackFn(0),
                error: tauri::ipc::CallbackFn(1),
                url: "tauri://localhost".parse().unwrap(),
                body: tauri::ipc::InvokeBody::Json(serde_json::json!({"limit": 200, "offset": 0})),
                headers: Default::default(),
                invoke_key: tauri::test::INVOKE_KEY.to_string(),
            },
        );
        let entries = result
            .unwrap()
            .deserialize::<Vec<storage::HistoryEntry>>()
            .unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].raw_text, "Synthetic history");
        for command in ["delete_history_entry", "clear_history"] {
            let result = tauri::test::get_ipc_response(
                &main,
                tauri::webview::InvokeRequest {
                    cmd: command.to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({"id": 1})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            assert!(result.is_ok());
            assert_eq!(
                connection
                    .query_row("SELECT COUNT(*) FROM history", [], |row| row
                        .get::<_, i64>(0))
                    .unwrap(),
                0
            );
            if command == "delete_history_entry" {
                connection.execute(
                    "INSERT INTO history(created_at, raw_text, polished_text) VALUES (?1, ?2, ?2)",
                    ["2026-11-10T10:00:00", "Synthetic replacement"],
                ).unwrap();
            }
        }
        drop(main);
        drop(app);
        drop(connection);
        std::fs::remove_dir_all(directory).unwrap();
    }

    #[test]
    fn retention_cleanup_requires_main_window_and_matching_saved_settings() {
        let current = storage::AppConfig {
            history_retention_days: 7,
            ..storage::AppConfig::default()
        };
        assert!(validate_retention_cleanup("main", &current, true, 7).is_ok());
        assert!(validate_retention_cleanup("capsule", &current, true, 7).is_err());
        assert!(validate_retention_cleanup("main", &current, false, 7).is_err());
        assert!(validate_retention_cleanup("main", &current, true, 1).is_err());
    }
}
