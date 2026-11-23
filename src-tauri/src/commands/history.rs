use crate::storage;
use tauri::Manager;

#[tauri::command]
pub(crate) async fn read_saved_ask_answer<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    history_id: i64,
    question: String,
    answer: String,
) -> Result<Option<storage::SavedAnswerSnapshot>, String> {
    if window.label() != "main" {
        return Err("Saved answers are only available in the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "Saved answers require local application content".to_string())?;
    storage::validate_saved_answer_input(history_id, &question, &answer)
        .map_err(|_| "Saved answer input unavailable".to_string())?;
    let app = window.app_handle().clone();
    let result = tauri::async_runtime::spawn_blocking(move || {
        app.try_state::<storage::HistoryStore>()
            .ok_or_else(|| "Saved answer storage unavailable".to_string())?
            .read_saved_answer_sync(history_id, &question, &answer)
            .map_err(|_| "Saved answer data unavailable".to_string())
    })
    .await
    .map_err(|_| "Saved answer read worker unavailable".to_string())??;
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "Saved answers require local application content".to_string())?;
    Ok(result)
}

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
pub async fn get_cloud_backup_history<R: tauri::Runtime>(
    window: tauri::Window<R>,
) -> Result<Vec<storage::HistoryEntry>, String> {
    ensure_history_change_window(window.label())?;
    let webview = window
        .app_handle()
        .get_webview_window(window.label())
        .ok_or_else(|| "Backup history unavailable".to_string())?;
    crate::update_install_request::ensure_local_window(&webview)
        .map_err(|_| "Backup history requires local content".to_string())?;
    let app = window.app_handle().clone();
    tauri::async_runtime::spawn_blocking(move || {
        app.state::<storage::HistoryStore>()
            .cloud_backup_history_snapshot()
            .map_err(|error| error.to_string())
    })
    .await
    .map_err(|_| "Backup history worker unavailable".to_string())?
}

#[tauri::command]
pub async fn search_history_page<R: tauri::Runtime>(
    window: tauri::Window<R>,
    state: tauri::State<'_, storage::HistoryStore>,
    query: String,
    mode: String,
    limit: u32,
    before_id: Option<i64>,
) -> Result<Vec<storage::HistoryEntry>, String> {
    ensure_history_change_window(window.label())?;
    state
        .search_page(&query, &mode, history_query_limit(limit), before_id)
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn clear_history<R: tauri::Runtime>(
    window: tauri::Window<R>,
    state: tauri::State<'_, storage::HistoryStore>,
) -> Result<(), String> {
    ensure_history_change_window(window.label())?;
    let app = window.app_handle().clone();
    let cleared = tauri::async_runtime::spawn_blocking(move || {
        crate::history_sync_commands::clear_saved_history_if_authorized(&app)
    })
    .await
    .map_err(|_| "History deletion worker unavailable".to_string())?
    .map_err(|_| "History deletion unavailable".to_string())?;
    if cleared {
        return Ok(());
    }
    state.clear().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn delete_history_entry<R: tauri::Runtime>(
    window: tauri::Window<R>,
    state: tauri::State<'_, storage::HistoryStore>,
    id: i64,
) -> Result<bool, String> {
    ensure_history_change_window(window.label())?;
    let app = window.app_handle().clone();
    let endpoint: reqwest::Url = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    )
    .parse()
    .map_err(|_| "History deletion unavailable".to_string())?;
    let authorization =
        match (
            app.try_state::<crate::SessionTokenStore>(),
            app.try_state::<crate::history_sync_consent::ConsentStore>(),
        ) {
            (Some(session), Some(consent)) => consent
                .current_for_session(session.inner(), &endpoint)
                .map_err(|_| "History deletion unavailable".to_string())?,
            _ => None,
        };
    if authorization.is_some() {
        crate::history_sync_commands::delete_saved_history_if_authorized(&app, id)
            .map(|(deleted, _)| deleted)
            .map_err(|_| "History deletion unavailable".to_string())
    } else {
        state
            .delete(id)
            .await
            .map_err(|_| "History deletion unavailable".to_string())
    }
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
    fn production_delete_routes_active_authorization_through_atomic_sync_deletion() {
        let source = include_str!("history.rs");
        let method = source
            .split("pub async fn delete_history_entry")
            .nth(1)
            .unwrap()
            .split("fn ensure_history_change_window")
            .next()
            .unwrap();
        let view = method.find(".current_for_session(").unwrap();
        let branch = method.find("if authorization.is_some()").unwrap();
        let synced = method.find("delete_saved_history_if_authorized(").unwrap();
        let local = method.find(".delete(id)").unwrap();
        assert!(view < branch && branch < synced && synced < local);
        assert!(!method.contains("block_on"));
    }

    #[tokio::test(flavor = "current_thread")]
    async fn saved_answer_read_observes_deletion_or_edit_while_waiting_for_database() {
        for delete in [false, true] {
            let path = std::env::temp_dir().join(format!(
                "otl-saved-read-mutation-{}.sqlite",
                uuid::Uuid::new_v4()
            ));
            let history = storage::HistoryStore::new(path.clone()).unwrap();
            let connection = rusqlite::Connection::open(&path).unwrap();
            connection
                .execute(
                    "INSERT INTO history(created_at, raw_text, polished_text, mode, answer_text)
                     VALUES ('2026-11-20T00:00:00', 'Synthetic question', '', 'ask', 'Synthetic answer')",
                    [],
                )
                .unwrap();
            let id = connection.last_insert_rowid();
            drop(connection);
            let app = tauri::test::mock_builder()
                .manage(history)
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
                .build()
                .unwrap();
            let holder_app = app.handle().clone();
            let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
            let (release_sender, release_receiver) = std::sync::mpsc::channel();
            let holder = std::thread::spawn(move || {
                let mut released = false;
                holder_app
                    .state::<storage::HistoryStore>()
                    .hold_citation_connection_for_test(|| {
                        entered_sender.send(()).unwrap();
                        released = release_receiver
                            .recv_timeout(std::time::Duration::from_secs(3))
                            .is_ok();
                    });
                released
            });
            entered_receiver.await.unwrap();
            let completed = std::cell::Cell::new(false);
            let waiting = std::cell::Cell::new(false);
            let reading = async {
                let result = read_saved_ask_answer(
                    window,
                    id,
                    "Synthetic question".into(),
                    "Synthetic answer".into(),
                )
                .await;
                completed.set(true);
                result
            };
            let changing = async {
                tokio::time::sleep(std::time::Duration::from_millis(30)).await;
                waiting.set(!completed.get());
                let connection = rusqlite::Connection::open(path).unwrap();
                let statement = if delete {
                    "DELETE FROM history WHERE id = ?1"
                } else {
                    "UPDATE history SET answer_text = 'Changed answer' WHERE id = ?1"
                };
                connection.execute(statement, [id]).unwrap();
                let _ = release_sender.send(());
            };
            let (result, ()) = tokio::join!(reading, changing);
            assert!(holder.join().unwrap());
            assert!(
                waiting.get(),
                "Mutation must happen while the read is waiting"
            );
            assert!(result.unwrap().is_none());
            assert!(app.get_webview_window("ask").is_none());
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn saved_answer_read_rechecks_navigation_after_responsive_database_wait() {
        let path = std::env::temp_dir().join(format!(
            "otl-saved-read-navigation-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let history = storage::HistoryStore::new(path.clone()).unwrap();
        let connection = rusqlite::Connection::open(path).unwrap();
        connection
            .execute(
                "INSERT INTO history(created_at, raw_text, polished_text, mode, answer_text)
             VALUES ('2026-11-20T00:00:00', 'Synthetic question', '', 'ask', 'Synthetic answer')",
                [],
            )
            .unwrap();
        let id = connection.last_insert_rowid();
        drop(connection);
        let app = tauri::test::mock_builder()
            .manage(history)
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let holder_app = app.handle().clone();
        let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let holder = std::thread::spawn(move || {
            let mut released = false;
            holder_app
                .state::<storage::HistoryStore>()
                .hold_citation_connection_for_test(|| {
                    entered_sender.send(()).unwrap();
                    released = release_receiver
                        .recv_timeout(std::time::Duration::from_secs(3))
                        .is_ok();
                });
            released
        });
        entered_receiver.await.unwrap();
        let completed = std::cell::Cell::new(false);
        let waiting = std::cell::Cell::new(false);
        let reading = async {
            let result = read_saved_ask_answer(
                window.clone(),
                id,
                "Synthetic question".into(),
                "Synthetic answer".into(),
            )
            .await;
            completed.set(true);
            result
        };
        let navigating = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            waiting.set(!completed.get());
            window
                .navigate("https://example.invalid/".parse().unwrap())
                .unwrap();
            let _ = release_sender.send(());
        };
        let (result, ()) = tokio::join!(reading, navigating);
        assert!(
            holder.join().unwrap(),
            "Async navigation must precede lock timeout"
        );
        assert!(waiting.get(), "Read must still wait for the database");
        assert_eq!(
            result.unwrap_err(),
            "Saved answers require local application content"
        );
        assert!(app.get_webview_window("ask").is_none());
    }

    #[test]
    fn saved_answer_read_ipc_serializes_minimal_data_and_rejects_untrusted_windows() {
        for (label, remote) in [("main", false), ("main", true), ("capsule", false)] {
            let path = std::env::temp_dir().join(format!(
                "otl-saved-answer-dispatch-{}.sqlite",
                uuid::Uuid::new_v4()
            ));
            let history = storage::HistoryStore::new(path.clone()).unwrap();
            let connection = rusqlite::Connection::open(path).unwrap();
            connection.execute(
                "INSERT INTO history(created_at, raw_text, polished_text, mode, answer_text)
                 VALUES ('2026-11-20T00:00:00', 'Synthetic question', '', 'ask', 'Synthetic answer')",
                [],
            ).unwrap();
            let id = connection.last_insert_rowid();
            connection
                .execute(
                    "INSERT INTO history_citations(history_id, sources) VALUES (?1, ?2)",
                    rusqlite::params![
                        id,
                        r#"[{"url":"https://example.invalid/source","title":"Saved source"}]"#
                    ],
                )
                .unwrap();
            drop(connection);
            let app = tauri::test::mock_builder()
                .manage(history)
                .invoke_handler(tauri::generate_handler![read_saved_ask_answer])
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let origin = if remote {
                "https://example.invalid/"
            } else {
                "tauri://localhost"
            };
            let url = if remote {
                tauri::WebviewUrl::External(origin.parse().unwrap())
            } else {
                Default::default()
            };
            let window = tauri::WebviewWindowBuilder::new(&app, label, url)
                .build()
                .unwrap();
            for question in ["Synthetic question", "Other question"] {
                let result = tauri::test::get_ipc_response(
                    &window,
                    tauri::webview::InvokeRequest {
                        cmd: "read_saved_ask_answer".into(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: origin.parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "historyId": id, "question": question, "answer": "Synthetic answer"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.into(),
                    },
                );
                if label == "main" && !remote {
                    let value = result.unwrap().deserialize::<serde_json::Value>().unwrap();
                    assert_eq!(
                        value,
                        if question == "Synthetic question" {
                            serde_json::json!({
                                "historyId": id, "question": question, "answer": "Synthetic answer",
                                "citations": [{"url":"https://example.invalid/source","title":"Saved source"}]
                            })
                        } else {
                            serde_json::Value::Null
                        }
                    );
                } else {
                    assert!(result.is_err());
                }
            }
            assert!(app.get_webview_window("ask").is_none());
        }
    }

    #[tokio::test]
    async fn saved_answer_read_rejects_oversized_input_before_storage_lookup() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let error = read_saved_ask_answer(
            window,
            1,
            "Synthetic question".into(),
            "x".repeat(4 * 1024 * 1024 + 1),
        )
        .await
        .unwrap_err();
        assert_eq!(error, "Saved answer input unavailable");
    }

    #[tokio::test]
    async fn saved_answer_read_returns_only_matching_data_to_local_main() {
        for (label, remote) in [("main", false), ("main", true), ("ask", false)] {
            let path = std::env::temp_dir().join(format!(
                "otl-saved-answer-read-{}.sqlite",
                uuid::Uuid::new_v4()
            ));
            let history = storage::HistoryStore::new(path.clone()).unwrap();
            let connection = rusqlite::Connection::open(path).unwrap();
            connection.execute(
                "INSERT INTO history(created_at, raw_text, polished_text, mode, answer_text)
                 VALUES ('2026-11-20T00:00:00', 'Synthetic question', '', 'ask', 'Synthetic answer')",
                [],
            ).unwrap();
            let id = connection.last_insert_rowid();
            drop(connection);
            let app = tauri::test::mock_builder()
                .manage(history)
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let url = if remote {
                tauri::WebviewUrl::External("https://example.invalid/".parse().unwrap())
            } else {
                Default::default()
            };
            let window = tauri::WebviewWindowBuilder::new(&app, label, url)
                .build()
                .unwrap();
            let result = read_saved_ask_answer(
                window.clone(),
                id,
                "Synthetic question".into(),
                "Synthetic answer".into(),
            )
            .await;
            if label == "main" && !remote {
                let value = serde_json::to_value(result.unwrap().unwrap()).unwrap();
                assert_eq!(
                    value,
                    serde_json::json!({
                        "historyId": id, "question": "Synthetic question",
                        "answer": "Synthetic answer", "citations": []
                    })
                );
                assert!(read_saved_ask_answer(
                    window,
                    id,
                    "Other question".into(),
                    "Synthetic answer".into(),
                )
                .await
                .unwrap()
                .is_none());
            } else {
                assert!(result.is_err());
            }
            if label == "main" {
                assert!(app.get_webview_window("ask").is_none());
            }
        }
    }

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
                get_cloud_backup_history,
                search_history_page,
                export_history_audio
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let backup_result = tauri::test::get_ipc_response(
                &webview,
                tauri::webview::InvokeRequest {
                    cmd: "get_cloud_backup_history".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            assert_eq!(
                backup_result.unwrap_err(),
                serde_json::json!("History changes are only available in the main window")
            );
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
                    cmd: "search_history_page".to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "query": "Synthetic",
                        "mode": "all",
                        "limit": 51,
                        "beforeId": null
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            assert_eq!(
                result.unwrap_err(),
                serde_json::json!("History changes are only available in the main window")
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
        let backup = tauri::test::get_ipc_response(
            &main,
            tauri::webview::InvokeRequest {
                cmd: "get_cloud_backup_history".into(),
                callback: tauri::ipc::CallbackFn(0),
                error: tauri::ipc::CallbackFn(1),
                url: "tauri://localhost".parse().unwrap(),
                body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                headers: Default::default(),
                invoke_key: tauri::test::INVOKE_KEY.into(),
            },
        )
        .unwrap()
        .deserialize::<Vec<storage::HistoryEntry>>()
        .unwrap();
        assert_eq!(backup.len(), 1);
        assert_eq!(backup[0].id, entries[0].id);
        assert_eq!(backup[0].raw_text, entries[0].raw_text);
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
    fn main_window_bulk_clear_preserves_local_behavior_without_sync_authorization() {
        let directory =
            std::env::temp_dir().join(format!("otl-local-clear-{}", uuid::Uuid::new_v4()));
        std::fs::create_dir_all(&directory).unwrap();
        let filename = directory.join("history.db");
        let history = storage::HistoryStore::new(filename.clone()).unwrap();
        let connection = rusqlite::Connection::open(&filename).unwrap();
        connection
            .execute(
                "INSERT INTO history(created_at, raw_text, polished_text) VALUES (?1, ?2, ?2)",
                ["2026-11-21T10:00:00", "Synthetic history"],
            )
            .unwrap();
        let app = tauri::test::mock_builder()
            .manage(history)
            .invoke_handler(tauri::generate_handler![clear_history])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let result = tauri::test::get_ipc_response(
            &webview,
            tauri::webview::InvokeRequest {
                cmd: "clear_history".into(),
                callback: tauri::ipc::CallbackFn(0),
                error: tauri::ipc::CallbackFn(1),
                url: "tauri://localhost".parse().unwrap(),
                body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                headers: Default::default(),
                invoke_key: tauri::test::INVOKE_KEY.into(),
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
