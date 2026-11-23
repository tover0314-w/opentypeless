use tauri::{Emitter, Manager};

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct HistorySyncOnceResult {
    pub(crate) pushed: usize,
    pub(crate) pulled: usize,
    pub(crate) has_more: bool,
    pub(crate) pending: usize,
}

pub(crate) fn enqueue_new_history_for_current_authorization(
    session: &crate::SessionTokenStore,
    consent: &crate::history_sync_consent::ConsentStore,
    history: &crate::storage::HistoryStore,
    endpoint: &reqwest::Url,
    history_id: i64,
    policy: &crate::storage::HistoryRetentionPolicy,
) -> anyhow::Result<bool> {
    if history_id <= 0 || !policy.enabled || policy.retention_days != 0 {
        return Ok(false);
    }
    let expected_session = session.snapshot();
    let Some(authorization) = consent.current_for_session(session, endpoint)? else {
        return Ok(false);
    };
    let identity = history
        .local_identity_snapshot(history_id)?
        .ok_or_else(|| anyhow::anyhow!("History sync queue source unavailable"))?;
    history.enqueue_history_revision_with_commit(
        authorization.account_id(),
        history_id,
        &identity,
        |transaction| {
            crate::history_sync_session::commit_with_current_session(
                session,
                &expected_session,
                || {
                    consent.with_current(
                        &authorization,
                        authorization.account_id(),
                        &expected_session.session_epoch,
                        endpoint,
                        || {
                            transaction.commit()?;
                            Ok(())
                        },
                    )
                },
            )
        },
    )?;
    Ok(true)
}

pub(crate) fn enqueue_saved_history_if_authorized<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    history_id: i64,
) -> anyhow::Result<bool> {
    let endpoint: reqwest::Url = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    )
    .parse()
    .map_err(|_| anyhow::anyhow!("History sync service configuration unavailable"))?;
    let session = app
        .try_state::<crate::SessionTokenStore>()
        .ok_or_else(|| anyhow::anyhow!("History sync session state unavailable"))?;
    let consent = app
        .try_state::<crate::history_sync_consent::ConsentStore>()
        .ok_or_else(|| anyhow::anyhow!("History sync consent state unavailable"))?;
    let history = app
        .try_state::<crate::storage::HistoryStore>()
        .ok_or_else(|| anyhow::anyhow!("History sync storage unavailable"))?;
    let config = app
        .try_state::<crate::storage::ConfigManager>()
        .ok_or_else(|| anyhow::anyhow!("History sync settings unavailable"))?;
    config.with_current_history_privacy(|privacy| {
        let Some((policy, _)) = privacy else {
            return Ok(false);
        };
        enqueue_new_history_for_current_authorization(
            session.inner(),
            consent.inner(),
            history.inner(),
            &endpoint,
            history_id,
            &policy,
        )
    })
}

pub(crate) fn delete_history_for_current_authorization(
    session: &crate::SessionTokenStore,
    consent: &crate::history_sync_consent::ConsentStore,
    history: &crate::storage::HistoryStore,
    endpoint: &reqwest::Url,
    history_id: i64,
    policy: &crate::storage::HistoryRetentionPolicy,
) -> anyhow::Result<(
    bool,
    Option<crate::storage::history_sync_queue::PendingHistoryMutation>,
)> {
    if history_id <= 0 {
        anyhow::bail!("History sync deletion unavailable");
    }
    let _reservation = history.reserve_history_sync()?;
    if !policy.enabled || policy.retention_days != 0 {
        return history
            .delete_history_with_local_commit(history_id)
            .map(|deleted| (deleted, None));
    }
    let expected_session = session.snapshot();
    let authorization = consent
        .current_for_session(session, endpoint)?
        .ok_or_else(|| anyhow::anyhow!("History sync authorization unavailable"))?;
    if history
        .read_history_sync_attempt(authorization.account_id(), endpoint.as_str())?
        .is_some_and(|attempt| attempt.status != "confirmed")
    {
        anyhow::bail!("Previous history sync result must be reconciled before deletion");
    }
    history
        .delete_history_with_sync_commit(authorization.account_id(), history_id, |transaction| {
            crate::history_sync_session::commit_with_current_session(
                session,
                &expected_session,
                || {
                    consent.with_current(
                        &authorization,
                        authorization.account_id(),
                        &expected_session.session_epoch,
                        endpoint,
                        || {
                            transaction.commit()?;
                            Ok(())
                        },
                    )
                },
            )
        })
        .map(|mutation| (true, mutation))
}

pub(crate) fn delete_saved_history_if_authorized<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    history_id: i64,
) -> anyhow::Result<(
    bool,
    Option<crate::storage::history_sync_queue::PendingHistoryMutation>,
)> {
    let endpoint: reqwest::Url = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    )
    .parse()
    .map_err(|_| anyhow::anyhow!("History sync service configuration unavailable"))?;
    let session = app
        .try_state::<crate::SessionTokenStore>()
        .ok_or_else(|| anyhow::anyhow!("History sync session state unavailable"))?;
    let consent = app
        .try_state::<crate::history_sync_consent::ConsentStore>()
        .ok_or_else(|| anyhow::anyhow!("History sync consent state unavailable"))?;
    let history = app
        .try_state::<crate::storage::HistoryStore>()
        .ok_or_else(|| anyhow::anyhow!("History sync storage unavailable"))?;
    let config = app
        .try_state::<crate::storage::ConfigManager>()
        .ok_or_else(|| anyhow::anyhow!("History sync settings unavailable"))?;
    config.with_current_history_privacy(|privacy| {
        let Some((policy, _)) = privacy else {
            anyhow::bail!("History sync privacy unavailable");
        };
        delete_history_for_current_authorization(
            session.inner(),
            consent.inner(),
            history.inner(),
            &endpoint,
            history_id,
            &policy,
        )
    })
}

pub(crate) fn clear_saved_history_if_authorized<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
) -> anyhow::Result<bool> {
    use tauri::Manager;
    let endpoint: reqwest::Url = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    )
    .parse()?;
    let (Some(session), Some(consent)) = (
        app.try_state::<crate::SessionTokenStore>(),
        app.try_state::<crate::history_sync_consent::ConsentStore>(),
    ) else {
        return Ok(false);
    };
    let Some(authorization) = consent.current_for_session(session.inner(), &endpoint)? else {
        return Ok(false);
    };
    let history = app.state::<crate::storage::HistoryStore>();
    let config = app.state::<crate::storage::ConfigManager>();
    let _reservation = history.reserve_history_sync()?;
    let expected_session = session.snapshot();
    if history
        .read_history_sync_attempt(authorization.account_id(), endpoint.as_str())?
        .is_some_and(|attempt| attempt.status != "confirmed")
    {
        anyhow::bail!("Previous history sync result must be reconciled before deletion");
    }
    config.with_current_history_privacy(|privacy| {
        let Some((policy, _)) = privacy else {
            anyhow::bail!("History sync privacy unavailable");
        };
        if !policy.enabled || policy.retention_days != 0 {
            return Ok(false);
        }
        history.clear_history_with_sync_commit(authorization.account_id(), |transaction| {
            crate::history_sync_session::commit_with_current_session(
                session.inner(),
                &expected_session,
                || {
                    consent.with_current(
                        &authorization,
                        authorization.account_id(),
                        &expected_session.session_epoch,
                        &endpoint,
                        || {
                            transaction.commit()?;
                            Ok(())
                        },
                    )
                },
            )
        })?;
        Ok(true)
    })
}

#[tauri::command]
pub(crate) async fn sync_history_once<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<HistorySyncOnceResult, String> {
    if window.label() != "main" {
        return Err("History sync requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        tauri::async_runtime::block_on(sync_history_once_worker(
            window,
            expected_session_epoch,
            expected_generation,
        ))
    })
    .await
    .map_err(|_| "History sync worker result unavailable".to_string())?
}

async fn sync_history_once_worker<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<HistorySyncOnceResult, String> {
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    let app = window.app_handle().clone();
    let session = app
        .try_state::<crate::SessionTokenStore>()
        .ok_or("History sync session unavailable")?;
    let expected_session = session.snapshot();
    let consent = app
        .try_state::<crate::history_sync_consent::ConsentStore>()
        .ok_or("History sync authorization unavailable")?;
    let authorization = consent
        .current_for_session(
            session.inner(),
            &format!(
                "{}/api/history-sync/status",
                crate::api_base_url().trim_end_matches('/')
            )
            .parse()
            .map_err(|_| "History sync service unavailable")?,
        )
        .map_err(|_| "History sync authorization unavailable".to_string())?
        .ok_or("History sync authorization unavailable")?;
    if !authorization.matches_view(&expected_session_epoch, &expected_generation) {
        return Err("History sync authorization superseded".into());
    }
    let history = app
        .try_state::<crate::storage::HistoryStore>()
        .ok_or("History sync storage unavailable")?;
    let _reservation = history
        .reserve_history_sync()
        .map_err(|_| "History sync or backup restoration is already in progress".to_string())?;
    let config = app
        .try_state::<crate::storage::ConfigManager>()
        .ok_or("History sync settings unavailable")?;
    let policy = config
        .with_current_history_privacy(|privacy| {
            let Some((policy, _)) = privacy else {
                anyhow::bail!("History sync privacy unavailable");
            };
            if !policy.enabled || policy.retention_days != 0 {
                anyhow::bail!("History sync privacy unavailable");
            }
            Ok(policy)
        })
        .map_err(|_| "History sync settings unavailable".to_string())?;
    let endpoint = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    );
    let previous = history
        .read_history_sync_attempt(authorization.account_id(), &endpoint)
        .map_err(|_| "History sync result unavailable".to_string())?;
    if previous.is_some_and(|attempt| attempt.status != "confirmed" && !attempt.acknowledged) {
        return Err("Previous history sync result is unresolved".into());
    }
    if history
        .history_sync_conflict_count(authorization.account_id())
        .map_err(|_| "History sync conflict state unavailable".to_string())?
        > 0
    {
        return Err("History sync conflicts require review before synchronization".into());
    }
    let discovery = crate::history_sync_status::discover(session.inner(), &expected_session)
        .await
        .map_err(|_| "History sync service unavailable".to_string())?;
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    discovery
        .check_consent(session.inner(), consent.inner(), &authorization)
        .map_err(|_| "History sync authorization unavailable".to_string())?;
    let request_id = uuid::Uuid::new_v4().to_string();
    history
        .begin_history_sync_attempt(authorization.account_id(), &endpoint, &request_id)
        .map_err(|_| "Previous history sync result is unresolved".to_string())?;
    let result = sync_discovered_history_once(
        &discovery,
        session.inner(),
        history.inner(),
        &policy,
        (consent.inner(), &authorization),
        || {
            let _ = app.emit("history:changed", ());
        },
    )
    .await;
    let saved_result = result
        .as_ref()
        .ok()
        .map(serde_json::to_value)
        .transpose()
        .map_err(|_| "History sync result unavailable".to_string())?;
    history
        .finish_history_sync_attempt(
            authorization.account_id(),
            &endpoint,
            &request_id,
            saved_result.as_ref(),
        )
        .map_err(|_| "History sync result persistence unavailable".to_string())?;
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync result requires local application content".to_string())?;
    result
}

#[tauri::command]
pub(crate) async fn get_history_sync_attempt<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<Option<crate::storage::history_sync_attempt::Attempt>, String> {
    if window.label() != "main" {
        return Err("History sync requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        let app = window.app_handle().clone();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .map_err(|_| "History sync service unavailable")?;
        let session = app
            .try_state::<crate::SessionTokenStore>()
            .ok_or("History sync session unavailable")?;
        let consent = app
            .try_state::<crate::history_sync_consent::ConsentStore>()
            .ok_or("History sync authorization unavailable")?;
        let authorization = consent
            .current_for_session(session.inner(), &endpoint)
            .map_err(|_| "History sync authorization unavailable")?
            .ok_or("History sync authorization unavailable")?;
        if !authorization.matches_view(&expected_session_epoch, &expected_generation) {
            return Err("History sync authorization superseded".to_string());
        }
        let history = app
            .try_state::<crate::storage::HistoryStore>()
            .ok_or("History sync storage unavailable")?;
        let expected = session.snapshot();
        let result = history
            .read_history_sync_attempt(authorization.account_id(), endpoint.as_str())
            .map_err(|_| "History sync result unavailable".to_string())?;
        crate::history_sync_session::with_current_session(session.inner(), &expected, || {
            consent.check(
                &authorization,
                authorization.account_id(),
                &expected.session_epoch,
                &endpoint,
            )
        })
        .map_err(|_| "History sync authorization superseded")?;
        crate::update_install_request::ensure_local_window(&window)
            .map_err(|_| "History sync result requires local application content")?;
        Ok(result)
    })
    .await
    .map_err(|_| "History sync result worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn get_history_sync_conflict_count<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<usize, String> {
    if window.label() != "main" {
        return Err("History sync requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        let app = window.app_handle();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .map_err(|_| "History sync service unavailable".to_string())?;
        let session = app.state::<crate::SessionTokenStore>();
        let consent = app.state::<crate::history_sync_consent::ConsentStore>();
        let authorization = consent
            .current_for_session(session.inner(), &endpoint)
            .map_err(|_| "History sync authorization unavailable".to_string())?
            .ok_or_else(|| "History sync authorization unavailable".to_string())?;
        if !authorization.matches_view(&expected_session_epoch, &expected_generation) {
            return Err("History sync authorization superseded".into());
        }
        let expected = session.snapshot();
        let count = app
            .state::<crate::storage::HistoryStore>()
            .history_sync_conflict_count(authorization.account_id())
            .map_err(|_| "History sync conflict state unavailable".to_string())?;
        crate::history_sync_session::with_current_session(session.inner(), &expected, || {
            consent.check(
                &authorization,
                authorization.account_id(),
                &expected.session_epoch,
                &endpoint,
            )
        })
        .map_err(|_| "History sync authorization superseded".to_string())?;
        crate::update_install_request::ensure_local_window(&window)
            .map_err(|_| "History sync requires local application content".to_string())?;
        Ok(count)
    })
    .await
    .map_err(|_| "History sync conflict worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn preview_history_sync_remote_record<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    record_id: String,
    mutation_id: String,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<Option<crate::storage::history_sync_record::RemoteRecord>, String> {
    if window.label() != "main" {
        return Err("History sync requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        tauri::async_runtime::block_on(async {
            let app = window.app_handle();
            let endpoint: reqwest::Url = format!(
                "{}/api/history-sync/status",
                crate::api_base_url().trim_end_matches('/')
            )
            .parse()
            .map_err(|_| "History sync service unavailable".to_string())?;
            let session = app.state::<crate::SessionTokenStore>();
            let consent = app.state::<crate::history_sync_consent::ConsentStore>();
            let authorization = consent
                .current_for_session(session.inner(), &endpoint)
                .map_err(|_| "History sync authorization unavailable".to_string())?
                .ok_or_else(|| "History sync authorization unavailable".to_string())?;
            if !authorization.matches_view(&expected_session_epoch, &expected_generation) {
                return Err("History sync authorization superseded".into());
            }
            let history = app.state::<crate::storage::HistoryStore>();
            let _reservation = history
                .reserve_history_sync()
                .map_err(|_| "History synchronization is already active".to_string())?;
            let summaries = history
                .list_history_sync_conflicts(authorization.account_id())
                .map_err(|_| "History sync conflict unavailable".to_string())?;
            let summary = summaries
                .iter()
                .find(|summary| {
                    summary.record_id == record_id && summary.mutation_id == mutation_id
                })
                .ok_or_else(|| "History sync conflict changed".to_string())?;
            history
                .read_history_sync_conflict_local(authorization.account_id(), summary)
                .map_err(|_| "History sync local conflict changed".to_string())?;
            let expected = session.snapshot();
            let discovery = crate::history_sync_status::discover(session.inner(), &expected)
                .await
                .map_err(|_| "History sync service unavailable".to_string())?;
            let record = discovery
                .read_record(
                    session.inner(),
                    &record_id,
                    (consent.inner(), &authorization),
                )
                .await
                .map_err(|_| "History sync remote record unavailable".to_string())?;
            history
                .read_history_sync_conflict_local(authorization.account_id(), summary)
                .map_err(|_| "History sync local conflict changed".to_string())?;
            if record
                .as_ref()
                .is_some_and(|record| record.server_revision < summary.server_revision)
            {
                return Err("History sync remote baseline changed".into());
            }
            crate::update_install_request::ensure_local_window(&window)
                .map_err(|_| "History sync requires local application content".to_string())?;
            Ok(record)
        })
    })
    .await
    .map_err(|_| "History sync remote record worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn keep_local_history_sync_conflict<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    record_id: String,
    mutation_id: String,
    expected_remote_revision: i64,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<(), String> {
    resolve_history_sync_conflict(
        window,
        record_id,
        mutation_id,
        expected_remote_revision,
        expected_session_epoch,
        expected_generation,
        false,
    )
    .await
}

#[tauri::command]
pub(crate) async fn keep_remote_history_sync_conflict<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    record_id: String,
    mutation_id: String,
    expected_remote_revision: i64,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<(), String> {
    resolve_history_sync_conflict(
        window,
        record_id,
        mutation_id,
        expected_remote_revision,
        expected_session_epoch,
        expected_generation,
        true,
    )
    .await
}

#[allow(clippy::too_many_arguments)]
async fn resolve_history_sync_conflict<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    record_id: String,
    mutation_id: String,
    expected_remote_revision: i64,
    expected_session_epoch: String,
    expected_generation: String,
    keep_remote: bool,
) -> Result<(), String> {
    if window.label() != "main" {
        return Err("History sync requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        tauri::async_runtime::block_on(async {
            let app = window.app_handle();
            let endpoint: reqwest::Url = format!(
                "{}/api/history-sync/status",
                crate::api_base_url().trim_end_matches('/')
            )
            .parse()
            .map_err(|_| "History sync service unavailable".to_string())?;
            let session = app.state::<crate::SessionTokenStore>();
            let consent = app.state::<crate::history_sync_consent::ConsentStore>();
            let authorization = consent
                .current_for_session(session.inner(), &endpoint)
                .map_err(|_| "History sync authorization unavailable".to_string())?
                .ok_or_else(|| "History sync authorization unavailable".to_string())?;
            if !authorization.matches_view(&expected_session_epoch, &expected_generation) {
                return Err("History sync authorization superseded".into());
            }
            let history = app.state::<crate::storage::HistoryStore>();
            let _reservation = history
                .reserve_history_sync()
                .map_err(|_| "History synchronization is already active".to_string())?;
            if history
                .read_history_sync_attempt(authorization.account_id(), endpoint.as_str())
                .map_err(|_| "History sync attempt unavailable".to_string())?
                .is_some_and(|attempt| attempt.status != "confirmed" && !attempt.acknowledged)
            {
                return Err("Previous history sync result is unresolved".into());
            }
            let summaries = history
                .list_history_sync_conflicts(authorization.account_id())
                .map_err(|_| "History sync conflict unavailable".to_string())?;
            let summary = summaries
                .iter()
                .find(|summary| {
                    summary.record_id == record_id && summary.mutation_id == mutation_id
                })
                .ok_or_else(|| "History sync conflict changed".to_string())?;
            let local = history
                .read_history_sync_conflict_local(authorization.account_id(), summary)
                .map_err(|_| "History sync local conflict changed".to_string())?;
            let expected = session.snapshot();
            let discovery = crate::history_sync_status::discover(session.inner(), &expected)
                .await
                .map_err(|_| "History sync service unavailable".to_string())?;
            let remote = discovery
                .read_record(
                    session.inner(),
                    &record_id,
                    (consent.inner(), &authorization),
                )
                .await
                .map_err(|_| "History sync remote record unavailable".to_string())?;
            let remote_revision = remote.as_ref().map_or(0, |record| record.server_revision);
            if remote_revision != expected_remote_revision
                || (!keep_remote
                    && remote
                        .as_ref()
                        .is_some_and(|record| record.deleted && !local.deleted))
                || (keep_remote && remote.is_none())
            {
                return Err("History sync remote version changed; preview it again".into());
            }
            let config = app.state::<crate::storage::ConfigManager>();
            config
                .with_current_history_privacy(|privacy| {
                    let Some((policy, _)) = privacy else {
                        anyhow::bail!("History sync privacy unavailable");
                    };
                    if !policy.enabled || policy.retention_days != 0 {
                        anyhow::bail!("History sync privacy unavailable");
                    }
                    if keep_remote {
                        history.keep_remote_history_sync_conflict(
                            authorization.account_id(),
                            summary,
                            remote
                                .as_ref()
                                .ok_or_else(|| anyhow::anyhow!("Remote version unavailable"))?,
                            |transaction| {
                                crate::history_sync_session::commit_with_current_session(
                                    session.inner(),
                                    &expected,
                                    || {
                                        consent.with_current(
                                            &authorization,
                                            authorization.account_id(),
                                            &expected.session_epoch,
                                            &endpoint,
                                            || {
                                                crate::update_install_request::ensure_local_window(
                                                    &window,
                                                )
                                                .map_err(anyhow::Error::msg)?;
                                                transaction.commit()?;
                                                Ok(())
                                            },
                                        )
                                    },
                                )
                            },
                        )?;
                        let _ = app.emit("history:changed", ());
                        return Ok(());
                    }
                    history.keep_local_history_sync_conflict(
                        authorization.account_id(),
                        summary,
                        remote_revision,
                        |transaction| {
                            crate::history_sync_session::commit_with_current_session(
                                session.inner(),
                                &expected,
                                || {
                                    consent.with_current(
                                        &authorization,
                                        authorization.account_id(),
                                        &expected.session_epoch,
                                        &endpoint,
                                        || {
                                            crate::update_install_request::ensure_local_window(
                                                &window,
                                            )
                                            .map_err(anyhow::Error::msg)?;
                                            transaction.commit()?;
                                            Ok(())
                                        },
                                    )
                                },
                            )
                        },
                    )?;
                    Ok(())
                })
                .map_err(|_| "History sync resolution could not be queued".to_string())?;
            Ok(())
        })
    })
    .await
    .map_err(|_| "History sync resolution result unknown".to_string())?
}

#[tauri::command]
pub(crate) async fn list_history_sync_conflicts<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    expected_session_epoch: String,
    expected_generation: String,
    after_record_id: Option<String>,
) -> Result<Vec<crate::storage::history_sync_record::ConflictSummary>, String> {
    if window.label() != "main" {
        return Err("History sync requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        let app = window.app_handle();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .map_err(|_| "History sync service unavailable".to_string())?;
        let session = app.state::<crate::SessionTokenStore>();
        let consent = app.state::<crate::history_sync_consent::ConsentStore>();
        let authorization = consent
            .current_for_session(session.inner(), &endpoint)
            .map_err(|_| "History sync authorization unavailable".to_string())?
            .ok_or_else(|| "History sync authorization unavailable".to_string())?;
        if !authorization.matches_view(&expected_session_epoch, &expected_generation) {
            return Err("History sync authorization superseded".into());
        }
        let expected = session.snapshot();
        let summaries = app
            .state::<crate::storage::HistoryStore>()
            .list_history_sync_conflict_page(authorization.account_id(), after_record_id.as_deref())
            .map_err(|_| "History sync conflict list unavailable".to_string())?;
        crate::history_sync_session::with_current_session(session.inner(), &expected, || {
            consent.check(
                &authorization,
                authorization.account_id(),
                &expected.session_epoch,
                &endpoint,
            )
        })
        .map_err(|_| "History sync authorization superseded".to_string())?;
        crate::update_install_request::ensure_local_window(&window)
            .map_err(|_| "History sync requires local application content".to_string())?;
        Ok(summaries)
    })
    .await
    .map_err(|_| "History sync conflict worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn preview_history_sync_local_record<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    record_id: String,
    mutation_id: String,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<crate::storage::history_sync_record::LocalConflictRecord, String> {
    if window.label() != "main" {
        return Err("History sync requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        let app = window.app_handle();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .map_err(|_| "History sync service unavailable".to_string())?;
        let session = app.state::<crate::SessionTokenStore>();
        let consent = app.state::<crate::history_sync_consent::ConsentStore>();
        let authorization = consent
            .current_for_session(session.inner(), &endpoint)
            .map_err(|_| "History sync authorization unavailable".to_string())?
            .ok_or_else(|| "History sync authorization unavailable".to_string())?;
        if !authorization.matches_view(&expected_session_epoch, &expected_generation) {
            return Err("History sync authorization superseded".into());
        }
        let expected = session.snapshot();
        let history = app.state::<crate::storage::HistoryStore>();
        let summaries = history
            .list_history_sync_conflicts(authorization.account_id())
            .map_err(|_| "History sync conflict list unavailable".to_string())?;
        let summary = summaries
            .iter()
            .find(|summary| summary.record_id == record_id && summary.mutation_id == mutation_id)
            .ok_or_else(|| "History sync conflict changed".to_string())?;
        let record = history
            .read_history_sync_conflict_local(authorization.account_id(), summary)
            .map_err(|_| "History sync local record unavailable".to_string())?;
        crate::history_sync_session::with_current_session(session.inner(), &expected, || {
            consent.check(
                &authorization,
                authorization.account_id(),
                &expected.session_epoch,
                &endpoint,
            )
        })
        .map_err(|_| "History sync authorization superseded".to_string())?;
        crate::update_install_request::ensure_local_window(&window)
            .map_err(|_| "History sync requires local application content".to_string())?;
        Ok(record)
    })
    .await
    .map_err(|_| "History sync local preview worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn acknowledge_history_sync_attempt<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request_id: String,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<(), String> {
    if window.label() != "main" {
        return Err("History sync requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync requires local application content".to_string())?;
    tauri::async_runtime::spawn_blocking(move || {
        let app = window.app_handle().clone();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .map_err(|_| "History sync service unavailable")?;
        let session = app
            .try_state::<crate::SessionTokenStore>()
            .ok_or("History sync session unavailable")?;
        let consent = app
            .try_state::<crate::history_sync_consent::ConsentStore>()
            .ok_or("History sync authorization unavailable")?;
        let authorization = consent
            .current_for_session(session.inner(), &endpoint)
            .map_err(|_| "History sync authorization unavailable")?
            .ok_or("History sync authorization unavailable")?;
        if !authorization.matches_view(&expected_session_epoch, &expected_generation) {
            return Err("History sync authorization superseded".to_string());
        }
        let expected = session.snapshot();
        let history = app
            .try_state::<crate::storage::HistoryStore>()
            .ok_or("History sync storage unavailable")?;
        history
            .acknowledge_history_sync_attempt(
                authorization.account_id(),
                endpoint.as_str(),
                &request_id,
                |transaction| {
                    crate::history_sync_session::commit_with_current_session(
                        session.inner(),
                        &expected,
                        || {
                            consent.with_current(
                                &authorization,
                                authorization.account_id(),
                                &expected.session_epoch,
                                &endpoint,
                                || {
                                    crate::update_install_request::ensure_local_window(&window)
                                        .map_err(anyhow::Error::msg)?;
                                    transaction.commit()?;
                                    Ok(())
                                },
                            )
                        },
                    )
                },
            )
            .map_err(|_| "History sync acknowledgement unavailable".to_string())?;
        crate::update_install_request::ensure_local_window(&window)
            .map_err(|_| "History sync result requires local application content".to_string())?;
        Ok(())
    })
    .await
    .map_err(|_| "History sync acknowledgement worker unavailable".to_string())?
}

fn require_complete_push(selected: usize, accepted: usize) -> Result<(), String> {
    if selected != accepted {
        return Err("History sync push incomplete".to_string());
    }
    Ok(())
}

pub(crate) async fn sync_discovered_history_once(
    discovery: &crate::history_sync_status::DiscoveredStatus,
    session: &crate::SessionTokenStore,
    history: &crate::storage::HistoryStore,
    policy: &crate::storage::HistoryRetentionPolicy,
    authorization: (
        &crate::history_sync_consent::ConsentStore,
        &crate::history_sync_consent::ConsentSnapshot,
    ),
    history_changed: impl FnOnce(),
) -> Result<HistorySyncOnceResult, String> {
    discovery
        .check_consent(session, authorization.0, authorization.1)
        .map_err(|_| "History sync authorization unavailable".to_string())?;
    let mutations = history
        .pending_sendable_history_revisions(
            discovery.account_id(),
            discovery
                .max_push_records()
                .map_err(|_| "History sync service unavailable".to_string())?,
        )
        .map_err(|_| "History sync queue unavailable".to_string())?;
    let pushed = if mutations.is_empty() {
        0
    } else {
        discovery
            .push_once(session, history, &mutations, policy, authorization)
            .await
            .map_err(|_| "History sync push unavailable".to_string())?
    };
    require_complete_push(mutations.len(), pushed)?;
    let pulled = discovery
        .pull_once(session, history, policy, authorization)
        .await
        .map_err(|_| "History sync pull unavailable".to_string())?;
    if pulled.change_count > 0 {
        history_changed();
    }
    let pending = history
        .pending_actionable_history_count(discovery.account_id())
        .map_err(|_| "History sync queue unavailable".to_string())?;
    discovery
        .check_consent(session, authorization.0, authorization.1)
        .map_err(|_| "History sync authorization unavailable".to_string())?;
    Ok(HistorySyncOnceResult {
        pushed,
        pulled: pulled.change_count,
        has_more: pulled.has_more,
        pending,
    })
}

#[tauri::command]
pub(crate) async fn confirm_history_sync_proposal<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request_id: String,
    expected_session_epoch: String,
) -> Result<(), String> {
    if window.label() != "main" {
        return Err("History sync consent requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync consent requires local application content".to_string())?;
    let app = window.app_handle().clone();
    let endpoint = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    )
    .parse()
    .map_err(|_| "History sync service configuration unavailable")?;
    tauri::async_runtime::spawn_blocking(move || {
        let session = app
            .try_state::<crate::SessionTokenStore>()
            .ok_or("History sync session state unavailable")?;
        proposal_session(session.inner(), &expected_session_epoch)?;
        let consent = app
            .try_state::<crate::history_sync_consent::ConsentStore>()
            .ok_or("History sync consent state unavailable")?;
        let proposals = app
            .try_state::<crate::history_sync_proposal::ProposalStore>()
            .ok_or("History sync proposal state unavailable")?;
        proposals
            .confirm_owned(
                &request_id,
                &expected_session_epoch,
                session.inner(),
                consent.inner(),
                &endpoint,
            )
            .map(|_| ())
            .map_err(|_| "History sync proposal confirmation unavailable".to_string())
    })
    .await
    .map_err(|_| "History sync proposal confirmation worker unavailable".to_string())?
}

fn proposal_session(
    session: &crate::SessionTokenStore,
    expected_epoch: &str,
) -> Result<crate::CloudSessionSnapshot, String> {
    let invalid = || "History sync proposal session unavailable".to_string();
    if uuid::Uuid::parse_str(expected_epoch)
        .ok()
        .is_none_or(|epoch| epoch.get_version_num() != 4)
    {
        return Err(invalid());
    }
    let token = session.0.lock().map_err(|_| invalid())?;
    let epoch = session.2.lock().map_err(|_| invalid())?;
    if token.trim().is_empty() || epoch.as_str() != expected_epoch {
        return Err(invalid());
    }
    Ok(crate::CloudSessionSnapshot {
        token: Some(token.clone()),
        session_epoch: epoch.clone(),
    })
}

#[tauri::command]
pub(crate) async fn prepare_history_sync_proposal<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    expected_session_epoch: String,
) -> Result<crate::history_sync_proposal::PreparedProposal, String> {
    if window.label() != "main" {
        return Err("History sync consent requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync consent requires local application content".to_string())?;
    let app = window.app_handle().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let session = app
            .try_state::<crate::SessionTokenStore>()
            .ok_or("History sync session state unavailable")?;
        let proposals = app
            .try_state::<crate::history_sync_proposal::ProposalStore>()
            .ok_or("History sync proposal state unavailable")?;
        let expected = proposal_session(session.inner(), &expected_session_epoch)?;
        let consent = app
            .try_state::<crate::history_sync_consent::ConsentStore>()
            .ok_or("History sync consent state unavailable")?;
        let endpoint = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .map_err(|_| "History sync service configuration unavailable")?;
        if consent
            .active_for_session(session.inner(), &endpoint)
            .map_err(|_| "History sync consent state unavailable")?
        {
            return Err("History sync authorization already active".to_string());
        }
        let attempt = proposals
            .begin_guarded_discovery()
            .map_err(|_| "History sync proposal state unavailable".to_string())?;
        let discovery = tauri::async_runtime::block_on(crate::history_sync_status::discover(
            session.inner(),
            &expected,
        ))
        .map_err(|_| "History sync discovery unavailable".to_string())?;
        crate::update_install_request::ensure_local_window(&window)
            .map_err(|_| "History sync consent requires local application content".to_string())?;
        proposals
            .finish_discovery_with_consent(
                attempt.attempt_id(),
                discovery,
                session.inner(),
                consent.inner(),
            )
            .map_err(|_| "History sync proposal unavailable".to_string())
    })
    .await
    .map_err(|_| "History sync proposal preparation worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn cancel_history_sync_proposal<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    request_id: String,
) -> Result<bool, String> {
    if window.label() != "main" {
        return Err("History sync consent requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync consent requires local application content".to_string())?;
    let app = window.app_handle().clone();
    tauri::async_runtime::spawn_blocking(move || {
        app.try_state::<crate::history_sync_proposal::ProposalStore>()
            .ok_or("History sync proposal state unavailable")?
            .cancel(&request_id)
            .map_err(|_| "History sync proposal cancellation unavailable".to_string())
    })
    .await
    .map_err(|_| "History sync proposal cancellation worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn revoke_history_sync_consent_generation<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    expected_session_epoch: String,
    expected_generation: String,
) -> Result<(), String> {
    if window.label() != "main" {
        return Err("History sync consent requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync consent requires local application content".to_string())?;
    let app = window.app_handle().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let session = app
            .try_state::<crate::SessionTokenStore>()
            .ok_or("History sync session state unavailable")?;
        app.try_state::<crate::history_sync_consent::ConsentStore>()
            .ok_or("History sync consent state unavailable")?
            .revoke_generation(
                session.inner(),
                &expected_session_epoch,
                &expected_generation,
            )
            .map_err(|_| "History sync consent revocation superseded".to_string())
    })
    .await
    .map_err(|_| "History sync consent revocation worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn get_history_sync_consent_view<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
) -> Result<crate::history_sync_consent::ConsentView, String> {
    if window.label() != "main" {
        return Err("History sync consent requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync consent requires local application content".to_string())?;
    let app = window.app_handle().clone();
    let endpoint = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    )
    .parse()
    .map_err(|_| "History sync service configuration unavailable")?;
    tauri::async_runtime::spawn_blocking(move || {
        let session = app
            .try_state::<crate::SessionTokenStore>()
            .ok_or("History sync session state unavailable")?;
        let consent = app
            .try_state::<crate::history_sync_consent::ConsentStore>()
            .ok_or("History sync consent state unavailable")?;
        consent
            .view(session.inner(), &endpoint)
            .map_err(|_| "History sync consent state unavailable".into())
    })
    .await
    .map_err(|_| "History sync consent worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn get_history_sync_session_epoch<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
) -> Result<String, String> {
    if window.label() != "main" {
        return Err("History sync consent requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync consent requires local application content".to_string())?;
    let app = window.app_handle().clone();
    tauri::async_runtime::spawn_blocking(move || {
        let session = app
            .try_state::<crate::SessionTokenStore>()
            .ok_or("History sync session state unavailable")?;
        let _token = session
            .0
            .lock()
            .map_err(|_| "History sync session state unavailable")?;
        let epoch = session
            .2
            .lock()
            .map_err(|_| "History sync session state unavailable")?;
        Ok(epoch.clone())
    })
    .await
    .map_err(|_| "History sync session epoch worker unavailable".to_string())?
}

#[tauri::command]
pub(crate) async fn get_history_sync_consent_status<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
) -> Result<bool, String> {
    if window.label() != "main" {
        return Err("History sync consent requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync consent requires local application content".to_string())?;
    let app = window.app_handle().clone();
    let endpoint = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    )
    .parse()
    .map_err(|_| "History sync service configuration unavailable")?;
    tauri::async_runtime::spawn_blocking(move || {
        let consent = app
            .try_state::<crate::history_sync_consent::ConsentStore>()
            .ok_or("History sync consent state unavailable")?;
        let session = app
            .try_state::<crate::SessionTokenStore>()
            .ok_or("History sync session state unavailable")?;
        consent
            .active_for_session(session.inner(), &endpoint)
            .map_err(|_| "History sync consent state unavailable".to_string())
    })
    .await
    .map_err(|_| "History sync consent status worker unavailable".to_string())?
}

#[cfg(test)]
#[tauri::command]
pub(crate) fn revoke_history_sync_consent<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    expected_session_epoch: String,
) -> Result<(), String> {
    if window.label() != "main" {
        return Err("History sync consent requires the main window".into());
    }
    crate::update_install_request::ensure_local_window(&window)
        .map_err(|_| "History sync consent requires local application content".to_string())?;
    let app = window.app_handle();
    let session = app
        .try_state::<crate::SessionTokenStore>()
        .ok_or("History sync session state unavailable")?;
    app.try_state::<crate::history_sync_consent::ConsentStore>()
        .ok_or("History sync consent state unavailable")?
        .revoke_for_session(session.inner(), &expected_session_epoch)
        .map_err(|_| "History sync consent revocation unavailable".to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{atomic::AtomicBool, Arc, Mutex};
    use tauri::Manager;

    #[test]
    fn local_resolution_checks_unresolved_attempt_before_remote_discovery() {
        let source = include_str!("history_sync_commands.rs");
        let command = source
            .split("async fn resolve_history_sync_conflict")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        assert!(
            command.find(".read_history_sync_attempt(").unwrap()
                < command.find("history_sync_status::discover(").unwrap()
        );
        assert!(command.contains("!attempt.acknowledged"));
    }

    #[test]
    fn partial_push_acknowledgement_never_counts_as_a_completed_push() {
        assert!(require_complete_push(3, 3).is_ok());
        assert!(require_complete_push(3, 0).is_err());
        assert!(require_complete_push(3, 2).is_err());
        assert!(require_complete_push(2, 3).is_err());
    }

    #[tokio::test]
    async fn conflict_count_requires_main_window_and_current_authorization() {
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let view = consent.view(&session, &endpoint).unwrap();
        let app = tauri::test::mock_builder()
            .manage(session)
            .manage(consent)
            .manage(sync_history())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let main = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let auxiliary = tauri::WebviewWindowBuilder::new(&app, "ask", Default::default())
            .build()
            .unwrap();
        let record_id = "33333333-3333-4333-8333-333333333333".to_string();
        let mutation_id = "44444444-4444-4444-8444-444444444444".to_string();
        assert_eq!(
            keep_local_history_sync_conflict(
                auxiliary.clone(),
                record_id.clone(),
                mutation_id.clone(),
                2,
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
            )
            .await
            .unwrap_err(),
            "History sync requires the main window"
        );
        assert_eq!(
            keep_local_history_sync_conflict(
                main.clone(),
                record_id.clone(),
                mutation_id.clone(),
                2,
                view.session_epoch.clone(),
                uuid::Uuid::new_v4().to_string(),
            )
            .await
            .unwrap_err(),
            "History sync authorization superseded"
        );
        assert_eq!(
            keep_local_history_sync_conflict(
                main.clone(),
                record_id.clone(),
                mutation_id.clone(),
                2,
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
            )
            .await
            .unwrap_err(),
            "History sync conflict changed"
        );
        let unresolved_request = uuid::Uuid::new_v4().to_string();
        let history = app.state::<crate::storage::HistoryStore>();
        history
            .begin_history_sync_attempt("synthetic-account", endpoint.as_str(), &unresolved_request)
            .unwrap();
        assert_eq!(
            keep_local_history_sync_conflict(
                main.clone(),
                record_id.clone(),
                mutation_id.clone(),
                2,
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
            )
            .await
            .unwrap_err(),
            "Previous history sync result is unresolved"
        );
        history
            .finish_history_sync_attempt(
                "synthetic-account",
                endpoint.as_str(),
                &unresolved_request,
                Some(&serde_json::json!({
                    "pushed": 0, "pulled": 0, "pending": 0, "hasMore": false
                })),
            )
            .unwrap();
        assert_eq!(
            preview_history_sync_remote_record(
                auxiliary.clone(),
                record_id.clone(),
                mutation_id.clone(),
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
            )
            .await
            .unwrap_err(),
            "History sync requires the main window"
        );
        assert_eq!(
            preview_history_sync_local_record(
                auxiliary.clone(),
                record_id.clone(),
                mutation_id.clone(),
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
            )
            .await
            .unwrap_err(),
            "History sync requires the main window"
        );
        assert_eq!(
            preview_history_sync_remote_record(
                main.clone(),
                record_id.clone(),
                mutation_id.clone(),
                view.session_epoch.clone(),
                uuid::Uuid::new_v4().to_string(),
            )
            .await
            .unwrap_err(),
            "History sync authorization superseded"
        );
        assert_eq!(
            preview_history_sync_remote_record(
                main.clone(),
                record_id.clone(),
                mutation_id.clone(),
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
            )
            .await
            .unwrap_err(),
            "History sync conflict changed"
        );
        assert_eq!(
            preview_history_sync_local_record(
                main.clone(),
                record_id,
                mutation_id,
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
            )
            .await
            .unwrap_err(),
            "History sync conflict changed"
        );
        assert_eq!(
            list_history_sync_conflicts(
                auxiliary.clone(),
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
                None,
            )
            .await
            .unwrap_err(),
            "History sync requires the main window"
        );
        assert_eq!(
            list_history_sync_conflicts(
                main.clone(),
                view.session_epoch.clone(),
                uuid::Uuid::new_v4().to_string(),
                None,
            )
            .await
            .unwrap_err(),
            "History sync authorization superseded"
        );
        assert!(list_history_sync_conflicts(
            main.clone(),
            view.session_epoch.clone(),
            view.generation.clone().unwrap(),
            None,
        )
        .await
        .unwrap()
        .is_empty());
        assert_eq!(
            get_history_sync_conflict_count(
                auxiliary,
                view.session_epoch.clone(),
                view.generation.clone().unwrap(),
            )
            .await
            .unwrap_err(),
            "History sync requires the main window"
        );
        assert_eq!(
            get_history_sync_conflict_count(
                main.clone(),
                view.session_epoch.clone(),
                uuid::Uuid::new_v4().to_string(),
            )
            .await
            .unwrap_err(),
            "History sync authorization superseded"
        );
        assert_eq!(
            get_history_sync_conflict_count(main, view.session_epoch, view.generation.unwrap(),)
                .await
                .unwrap(),
            0
        );
    }

    #[test]
    fn acknowledgement_ipc_checks_request_authorization_and_active_operation() {
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let view = consent.view(&session, &endpoint).unwrap();
        let history = sync_history();
        let request = uuid::Uuid::new_v4().to_string();
        history
            .begin_history_sync_attempt("synthetic-account", endpoint.as_str(), &request)
            .unwrap();
        history
            .finish_history_sync_attempt("synthetic-account", endpoint.as_str(), &request, None)
            .unwrap();
        let app = tauri::test::mock_builder()
            .manage(session)
            .manage(consent)
            .manage(history)
            .invoke_handler(tauri::generate_handler![acknowledge_history_sync_attempt])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let main = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let auxiliary = tauri::WebviewWindowBuilder::new(&app, "ask", Default::default())
            .build()
            .unwrap();
        let invoke = |window: &tauri::WebviewWindow<tauri::test::MockRuntime>,
                      request_id: &str,
                      generation: &str| {
            tauri::test::get_ipc_response(
                window,
                tauri::webview::InvokeRequest {
                    cmd: "acknowledge_history_sync_attempt".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "requestId": request_id,
                        "expectedSessionEpoch": view.session_epoch,
                        "expectedGeneration": generation
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            )
        };
        let generation = view.generation.as_deref().unwrap();
        assert!(invoke(&auxiliary, &request, generation).is_err());
        assert!(invoke(&main, &uuid::Uuid::new_v4().to_string(), generation).is_err());
        assert!(invoke(&main, &request, &uuid::Uuid::new_v4().to_string()).is_err());
        let store = app.state::<crate::storage::HistoryStore>();
        {
            let _running = store.reserve_history_sync().unwrap();
            assert!(invoke(&main, &request, generation).is_err());
        }
        assert!(store
            .begin_history_sync_attempt(
                "synthetic-account",
                endpoint.as_str(),
                &uuid::Uuid::new_v4().to_string()
            )
            .is_err());
        let response = invoke(&main, &request, generation).unwrap();
        assert!(response
            .deserialize::<serde_json::Value>()
            .unwrap()
            .is_null());
        let saved = store
            .read_history_sync_attempt("synthetic-account", endpoint.as_str())
            .unwrap()
            .unwrap();
        assert_eq!(saved.status, "unknown");
        assert!(saved.result.is_none());
        store
            .begin_history_sync_attempt(
                "synthetic-account",
                endpoint.as_str(),
                &uuid::Uuid::new_v4().to_string(),
            )
            .unwrap();
        assert!(invoke(&main, &request, generation).is_err());
    }

    #[tokio::test]
    async fn persisted_result_read_rejects_the_previous_authorization_before_storage_access() {
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let displayed = consent.view(&session, &endpoint).unwrap();
        consent.revoke().unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let app = tauri::test::mock_builder()
            .manage(session)
            .manage(consent)
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        assert_eq!(
            get_history_sync_attempt(
                window,
                displayed.session_epoch,
                displayed.generation.unwrap(),
            )
            .await
            .unwrap_err(),
            "History sync authorization superseded"
        );
    }

    #[test]
    fn sync_once_checks_authorization_before_service_discovery() {
        let source = include_str!("history_sync_commands.rs");
        let command = source
            .split("pub(crate) async fn sync_history_once")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        assert!(
            command.find(".current_for_session(").unwrap()
                < command.find("history_sync_status::discover(").unwrap()
        );
        assert!(
            command.find(".reserve_history_sync()").unwrap()
                < command.find("history_sync_status::discover(").unwrap()
        );
        assert!(command.contains("let _reservation ="));
        assert!(!command.contains("drop(_reservation)"));
        let worker = command
            .split("async fn sync_history_once_worker")
            .nth(1)
            .unwrap();
        assert!(
            worker.rfind("ensure_local_window(&window)").unwrap()
                > worker.find("sync_discovered_history_once(").unwrap()
        );
        assert!(
            command.find(".with_current_history_privacy(").unwrap()
                < command.find("history_sync_status::discover(").unwrap()
        );
        assert!(
            command.find(".read_history_sync_attempt(").unwrap()
                < command.find("history_sync_status::discover(").unwrap()
        );
        assert!(
            command.find(".history_sync_conflict_count(").unwrap()
                < command.find("history_sync_status::discover(").unwrap()
        );
    }

    #[tokio::test]
    async fn sync_once_rejects_an_unauthorized_local_window_before_discovery() {
        let app = tauri::test::mock_builder()
            .manage(sync_session())
            .manage(crate::history_sync_consent::ConsentStore::default())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let result = sync_history_once(
            window,
            uuid::Uuid::new_v4().to_string(),
            uuid::Uuid::new_v4().to_string(),
        )
        .await;
        assert_eq!(
            result.unwrap_err(),
            "History sync authorization unavailable"
        );
    }

    #[tokio::test(flavor = "current_thread")]
    async fn sync_once_blocked_session_read_keeps_async_release_responsive() {
        let token = Arc::new(Mutex::new("synthetic-token".to_string()));
        let app = tauri::test::mock_builder()
            .manage(crate::SessionTokenStore(
                token.clone(),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .manage(crate::history_sync_consent::ConsentStore::default())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let holder = std::thread::spawn(move || {
            let _guard = token.lock().unwrap();
            entered_sender.send(()).unwrap();
            release_receiver
                .recv_timeout(std::time::Duration::from_secs(2))
                .is_ok()
        });
        entered_receiver.await.unwrap();
        let syncing = sync_history_once(
            window,
            uuid::Uuid::new_v4().to_string(),
            uuid::Uuid::new_v4().to_string(),
        );
        let releasing = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            let _ = release_sender.send(());
        };
        let (result, ()) = tokio::join!(syncing, releasing);
        assert!(result.is_err());
        assert!(
            holder.join().unwrap(),
            "Async release was blocked by synchronous session read"
        );
    }

    #[tokio::test]
    async fn sync_once_rejects_unavailable_privacy_before_service_discovery() {
        let session = sync_session();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .unwrap();
        let consent = crate::history_sync_consent::ConsentStore::default();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let view = consent.view(&session, &endpoint).unwrap();
        let app = tauri::test::mock_builder()
            .manage(session)
            .manage(consent)
            .manage(sync_history())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        assert_eq!(
            sync_history_once(window, view.session_epoch, view.generation.unwrap(),)
                .await
                .unwrap_err(),
            "History sync settings unavailable"
        );
        assert!(app
            .state::<crate::storage::HistoryStore>()
            .reserve_history_sync()
            .is_ok());
    }

    #[tokio::test]
    async fn sync_once_rejects_an_old_displayed_generation_before_discovery() {
        let session = sync_session();
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .unwrap();
        let consent = crate::history_sync_consent::ConsentStore::default();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let displayed = consent.view(&session, &endpoint).unwrap();
        consent.revoke().unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let app = tauri::test::mock_builder()
            .manage(session)
            .manage(consent)
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        assert_eq!(
            sync_history_once(
                window,
                displayed.session_epoch,
                displayed.generation.unwrap()
            )
            .await
            .unwrap_err(),
            "History sync authorization superseded"
        );
    }

    fn sync_session() -> crate::SessionTokenStore {
        crate::SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        )
    }

    fn sync_history() -> crate::storage::HistoryStore {
        crate::storage::HistoryStore::new(std::env::temp_dir().join(format!(
            "opentypeless-authorized-enqueue-{}.sqlite",
            uuid::Uuid::new_v4()
        )))
        .unwrap()
    }

    fn sync_entry(created_at: &str) -> crate::storage::HistoryEntry {
        crate::storage::HistoryEntry {
            id: 0,
            created_at: created_at.into(),
            context_profile_id: "general.native".into(),
            context_label: "Synthetic".into(),
            context_icon_key: "general".into(),
            context_family: crate::app_detector::types::ContextFamily::General,
            browser_access_status: crate::app_detector::types::BrowserAccessStatus::NotApplicable,
            provider_kind: crate::storage::HistoryProviderKind::Local,
            raw_text: "Synthetic transcript".into(),
            polished_text: "Synthetic transcript".into(),
            language: None,
            duration_ms: Some(1000),
            active_scene_id: None,
            active_scene_source: None,
            active_scene_name: None,
            active_scene_prompt_chars: None,
            active_scene_prompt_truncated: false,
            output_status: None,
            output_error: None,
            mode: "dictate".into(),
            answer_text: None,
            ask_intent: None,
            citations: None,
            citation_supports: None,
            has_audio: false,
        }
    }

    #[tokio::test]
    async fn only_new_history_saved_after_authorization_is_enqueued() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        assert!(!enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap());
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:01:00"))
            .await
            .unwrap();
        assert!(enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            2,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap());
        let pending = history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(
            pending[0].source,
            crate::storage::history_sync_queue::PendingHistorySource::Current { history_id: 2 }
        );
        assert_eq!(pending[0].mutation.base_server_revision, 0);
    }

    #[tokio::test]
    async fn revoked_changed_or_age_limited_authorization_does_not_enqueue() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let age_limited = crate::storage::HistoryRetentionPolicy {
            retention_days: 30,
            ..Default::default()
        };
        assert!(!enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &age_limited,
        )
        .unwrap());
        consent.revoke().unwrap();
        assert!(!enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap());
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        session.rotate_epoch();
        assert!(!enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap());
        assert!(history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn authorization_revoked_at_enqueue_commit_rolls_back_the_pending_record() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        let authorization = consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let expected_session = session.snapshot();
        let identity = history.local_identity_snapshot(1).unwrap().unwrap();
        let result = history.enqueue_history_revision_with_commit(
            authorization.account_id(),
            1,
            &identity,
            |transaction| {
                consent.revoke()?;
                crate::history_sync_session::commit_with_current_session(
                    &session,
                    &expected_session,
                    || {
                        consent.with_current(
                            &authorization,
                            authorization.account_id(),
                            &expected_session.session_epoch,
                            &endpoint,
                            || {
                                transaction.commit()?;
                                Ok(())
                            },
                        )
                    },
                )
            },
        );
        assert!(result.is_err());
        assert!(history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
        assert!(history.get(1).await.unwrap().is_some());
    }

    #[tokio::test]
    async fn confirmed_history_edit_enqueues_the_new_revision_with_its_server_base() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let mut entry = sync_entry("2026-11-21T12:00:00");
        entry.output_status = Some(crate::storage::HISTORY_STATUS_DISMISSED.into());
        history.add(entry).await.unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        assert!(enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap());
        let first = history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .remove(0)
            .mutation;
        assert!(history.confirm_pending_history_revision(&first, 1).unwrap());
        assert!(history
            .complete_recovered(1, "Recovered words")
            .await
            .unwrap());
        assert!(enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap());
        let pending = history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].mutation.client_revision, 2);
        assert_eq!(pending[0].mutation.base_server_revision, 1);
        let request = history
            .build_pending_history_request(&pending[0].mutation)
            .unwrap();
        assert_eq!(
            request["mutations"][0]["payload"]["rawText"],
            "Recovered words"
        );
    }

    #[tokio::test]
    async fn another_account_cannot_cancel_unconfirmed_history_owned_by_the_first_account() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        consent
            .grant("account-a", &session.snapshot().session_epoch, &endpoint)
            .unwrap();
        enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap();
        let before = history.pending_history_revisions("account-a", 10).unwrap();
        consent.revoke().unwrap();
        session.rotate_epoch();
        consent
            .grant("account-b", &session.snapshot().session_epoch, &endpoint)
            .unwrap();
        assert!(delete_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .is_err());
        assert!(history.get(1).await.unwrap().is_some());
        assert_eq!(
            history.pending_history_revisions("account-a", 10).unwrap(),
            before
        );
        assert!(history
            .pending_history_revisions("account-b", 10)
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn deleting_unconfirmed_new_history_cancels_pending_without_tombstone() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        assert!(enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap());
        assert!(delete_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap()
        .1
        .is_none());
        assert!(history.get(1).await.unwrap().is_none());
        assert!(history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn unknown_sync_result_prevents_cancelling_a_possibly_uploaded_record() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap();
        let pending = history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        let request = uuid::Uuid::new_v4().to_string();
        history
            .begin_history_sync_attempt("synthetic-account", endpoint.as_str(), &request)
            .unwrap();
        history
            .finish_history_sync_attempt("synthetic-account", endpoint.as_str(), &request, None)
            .unwrap();
        assert!(delete_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .is_err());
        assert!(history.get(1).await.unwrap().is_some());
        assert_eq!(
            history
                .pending_history_revisions("synthetic-account", 10)
                .unwrap(),
            pending
        );
        history
            .acknowledge_history_sync_attempt(
                "synthetic-account",
                endpoint.as_str(),
                &request,
                |transaction| {
                    transaction.commit()?;
                    Ok(())
                },
            )
            .unwrap();
        assert!(delete_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .is_err());
    }

    #[tokio::test]
    async fn active_sync_prevents_deletion_from_cancelling_an_inflight_mutation() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap();
        let before = history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        {
            let _sync = history.reserve_history_sync().unwrap();
            assert!(delete_history_for_current_authorization(
                &session,
                &consent,
                &history,
                &endpoint,
                1,
                &crate::storage::HistoryRetentionPolicy::default(),
            )
            .is_err());
        }
        assert!(history.get(1).await.unwrap().is_some());
        assert_eq!(
            history
                .pending_history_revisions("synthetic-account", 10)
                .unwrap(),
            before
        );
        assert!(
            delete_history_for_current_authorization(
                &session,
                &consent,
                &history,
                &endpoint,
                1,
                &crate::storage::HistoryRetentionPolicy::default(),
            )
            .unwrap()
            .0
        );
    }

    #[tokio::test]
    async fn age_limited_authorization_keeps_plain_local_delete_semantics() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        let age_limited = crate::storage::HistoryRetentionPolicy {
            retention_days: 30,
            ..Default::default()
        };
        assert_eq!(
            delete_history_for_current_authorization(
                &session,
                &consent,
                &history,
                &endpoint,
                1,
                &age_limited,
            )
            .unwrap(),
            (true, None)
        );
        assert_eq!(
            delete_history_for_current_authorization(
                &session,
                &consent,
                &history,
                &endpoint,
                1,
                &age_limited,
            )
            .unwrap(),
            (false, None)
        );
        assert!(history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn confirmed_history_delete_creates_and_confirms_a_tombstone() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap();
        let first = history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .remove(0)
            .mutation;
        assert!(history.confirm_pending_history_revision(&first, 1).unwrap());
        let tombstone = delete_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap()
        .1
        .unwrap();
        assert_eq!(tombstone.client_revision, 2);
        assert_eq!(tombstone.base_server_revision, 1);
        assert!(history.get(1).await.unwrap().is_none());
        let request = history.build_pending_history_request(&tombstone).unwrap();
        assert_eq!(request["mutations"][0]["deleted"], true);
        assert!(request["mutations"][0]["payload"].is_null());
        assert!(history
            .confirm_pending_history_revision(&tombstone, 2)
            .unwrap());
        assert!(history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap()
            .is_empty());
    }

    #[tokio::test]
    async fn authorization_revoked_at_delete_commit_keeps_local_history_and_queue() {
        let history = sync_history();
        let session = sync_session();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        history
            .add(sync_entry("2026-11-21T12:00:00"))
            .await
            .unwrap();
        consent
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        enqueue_new_history_for_current_authorization(
            &session,
            &consent,
            &history,
            &endpoint,
            1,
            &crate::storage::HistoryRetentionPolicy::default(),
        )
        .unwrap();
        let before = history
            .pending_history_revisions("synthetic-account", 10)
            .unwrap();
        let authorization = consent
            .current_for_session(&session, &endpoint)
            .unwrap()
            .unwrap();
        let expected_session = session.snapshot();
        let result =
            history.delete_history_with_sync_commit(authorization.account_id(), 1, |transaction| {
                consent.revoke()?;
                crate::history_sync_session::commit_with_current_session(
                    &session,
                    &expected_session,
                    || {
                        consent.with_current(
                            &authorization,
                            authorization.account_id(),
                            &expected_session.session_epoch,
                            &endpoint,
                            || {
                                transaction.commit()?;
                                Ok(())
                            },
                        )
                    },
                )
            });
        assert!(result.is_err());
        assert!(history.get(1).await.unwrap().is_some());
        assert_eq!(
            history
                .pending_history_revisions("synthetic-account", 10)
                .unwrap(),
            before
        );
    }

    #[test]
    fn preparation_publishes_through_the_consent_bound_gate() {
        let command = include_str!("history_sync_commands.rs")
            .split("fn prepare_history_sync_proposal")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        assert!(command.contains(".finish_discovery_with_consent("));
    }

    #[test]
    fn preparation_checks_existing_consent_before_starting_discovery() {
        let command = include_str!("history_sync_commands.rs")
            .split("fn prepare_history_sync_proposal")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let check = command
            .find(".active_for_session(")
            .expect("Preparation must check existing authorization");
        assert!(command.find("spawn_blocking").unwrap() < check);
        assert!(check < command.find(".begin_guarded_discovery(").unwrap());
        assert!(check < command.find("::discover(").unwrap());
    }

    #[tokio::test]
    async fn preparation_rejects_active_or_missing_consent_without_discovery() {
        for active in [false, true] {
            let epoch = uuid::Uuid::new_v4().to_string();
            let builder = tauri::test::mock_builder()
                .manage(crate::history_sync_proposal::ProposalStore::default())
                .manage(crate::SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(epoch.clone()),
                ));
            let builder = if active {
                builder.manage(crate::history_sync_consent::ConsentStore::default())
            } else {
                builder
            };
            let app = builder
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            if active {
                let endpoint = format!(
                    "{}/api/history-sync/status",
                    crate::api_base_url().trim_end_matches('/')
                )
                .parse()
                .unwrap();
                app.state::<crate::history_sync_consent::ConsentStore>()
                    .grant("synthetic-account", &epoch, &endpoint)
                    .unwrap();
            }
            let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
                .build()
                .unwrap();
            let error = prepare_history_sync_proposal(window, epoch)
                .await
                .unwrap_err();
            assert_eq!(
                error,
                if active {
                    "History sync authorization already active"
                } else {
                    "History sync consent state unavailable"
                }
            );
            assert!(!app
                .state::<crate::history_sync_proposal::ProposalStore>()
                .retains_state_for_test());
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn blocked_preparation_attempt_leaves_async_release_responsive_without_network() {
        let expected = uuid::Uuid::new_v4().to_string();
        let token = std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into()));
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(crate::history_sync_proposal::ProposalStore::default())
            .manage(crate::SessionTokenStore(
                token.clone(),
                std::sync::atomic::AtomicBool::new(false),
                std::sync::Mutex::new(expected.clone()),
            ))
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
                .state::<crate::history_sync_proposal::ProposalStore>()
                .hold_lock_for_test(|| {
                    entered_sender.send(()).unwrap();
                    released = release_receiver
                        .recv_timeout(std::time::Duration::from_secs(3))
                        .is_ok();
                    token.lock().unwrap().clear();
                });
            released
        });
        entered_receiver.await.unwrap();
        let completed = std::cell::Cell::new(false);
        let waiting = std::cell::Cell::new(false);
        let preparing = async {
            let result = prepare_history_sync_proposal(window, expected).await;
            completed.set(true);
            result
        };
        let releasing = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            waiting.set(!completed.get());
            let _ = release_sender.send(());
        };
        let (result, ()) = tokio::join!(preparing, releasing);
        assert!(
            holder.join().unwrap(),
            "Async release must run before the proposal lock holder times out"
        );
        assert!(waiting.get(), "Preparation must wait for the proposal lock");
        assert!(result.is_err(), "Cleared session must prevent discovery");
    }

    #[tokio::test(flavor = "current_thread")]
    async fn blocked_status_read_leaves_async_release_responsive() {
        let token = std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into()));
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(crate::SessionTokenStore(
                token.clone(),
                std::sync::atomic::AtomicBool::new(false),
                std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let holder = std::thread::spawn(move || {
            let _guard = token.lock().unwrap();
            entered_sender.send(()).unwrap();
            release_receiver
                .recv_timeout(std::time::Duration::from_secs(3))
                .is_ok()
        });
        entered_receiver.await.unwrap();
        let completed = std::cell::Cell::new(false);
        let waiting = std::cell::Cell::new(false);
        let reading = async {
            let result = get_history_sync_consent_status(window).await;
            completed.set(true);
            result
        };
        let releasing = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            waiting.set(!completed.get());
            let _ = release_sender.send(());
        };
        let (result, ()) = tokio::join!(reading, releasing);
        assert!(
            holder.join().unwrap(),
            "Async release must run before the lock holder times out"
        );
        assert!(waiting.get(), "Status read must wait for the token lock");
        assert!(!result.unwrap());
    }

    #[tokio::test(flavor = "current_thread")]
    async fn blocked_epoch_read_leaves_async_release_responsive() {
        let expected = uuid::Uuid::new_v4().to_string();
        let token = std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into()));
        let app = tauri::test::mock_builder()
            .manage(crate::SessionTokenStore(
                token.clone(),
                std::sync::atomic::AtomicBool::new(false),
                std::sync::Mutex::new(expected.clone()),
            ))
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let holder = std::thread::spawn(move || {
            let _guard = token.lock().unwrap();
            entered_sender.send(()).unwrap();
            release_receiver
                .recv_timeout(std::time::Duration::from_secs(3))
                .is_ok()
        });
        entered_receiver.await.unwrap();
        let completed = std::cell::Cell::new(false);
        let waiting = std::cell::Cell::new(false);
        let reading = async {
            let result = get_history_sync_session_epoch(window).await;
            completed.set(true);
            result
        };
        let releasing = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            waiting.set(!completed.get());
            let _ = release_sender.send(());
        };
        let (result, ()) = tokio::join!(reading, releasing);
        assert!(
            holder.join().unwrap(),
            "Async release must run before the lock holder times out"
        );
        assert!(waiting.get(), "Epoch read must wait for the token lock");
        assert_eq!(result.unwrap(), expected);
    }

    #[tokio::test(flavor = "current_thread")]
    async fn blocked_proposal_cancel_leaves_async_release_responsive() {
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_proposal::ProposalStore::default())
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
                .state::<crate::history_sync_proposal::ProposalStore>()
                .hold_lock_for_test(|| {
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
        let cancelling = async {
            let result =
                cancel_history_sync_proposal(window, uuid::Uuid::new_v4().to_string()).await;
            completed.set(true);
            result
        };
        let releasing = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            waiting.set(!completed.get());
            let _ = release_sender.send(());
        };
        let (result, ()) = tokio::join!(cancelling, releasing);
        assert!(
            holder.join().unwrap(),
            "Async task must release before holder timeout"
        );
        assert!(
            waiting.get(),
            "Cancellation must wait for the proposal lock"
        );
        assert!(!result.unwrap());
    }

    #[test]
    fn proposal_cancellation_waits_for_its_lock_only_in_a_blocking_worker() {
        let command = include_str!("history_sync_commands.rs")
            .split("fn cancel_history_sync_proposal")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let worker = command
            .find("spawn_blocking")
            .expect("Cancellation needs a worker");
        assert!(command.find("ensure_local_window").unwrap() < worker);
        assert!(worker < command.find(".cancel(").unwrap());
    }

    #[test]
    fn proposal_preparation_captures_session_in_a_worker_before_discovery() {
        let command = include_str!("history_sync_commands.rs")
            .split("fn prepare_history_sync_proposal")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let worker = command
            .find("spawn_blocking")
            .expect("Preparation needs a worker");
        assert!(command.find("ensure_local_window").unwrap() < worker);
        assert!(worker < command.find("proposal_session(").unwrap());
        assert!(command.find("proposal_session(").unwrap() < command.find("::discover(").unwrap());
    }

    #[test]
    fn proposal_confirmation_moves_session_and_proposal_locks_to_a_blocking_worker() {
        let source = include_str!("history_sync_commands.rs");
        let command = source
            .split("fn confirm_history_sync_proposal")
            .nth(1)
            .unwrap()
            .split("fn proposal_session")
            .next()
            .unwrap();
        let worker = command
            .find("spawn_blocking")
            .expect("Confirmation needs a worker");
        assert!(command.find("ensure_local_window").unwrap() < worker);
        assert!(worker < command.find("proposal_session(").unwrap());
        assert!(worker < command.find(".confirm_owned(").unwrap());
    }

    #[test]
    fn generation_revocation_waits_for_locks_only_in_a_blocking_worker() {
        let source = include_str!("history_sync_commands.rs");
        let command = source
            .split("fn revoke_history_sync_consent_generation")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let worker = command
            .find("spawn_blocking")
            .expect("Revocation needs a blocking worker");
        assert!(command.find("ensure_local_window").unwrap() < worker);
        assert!(worker < command.find(".revoke_generation(").unwrap());
    }

    #[tokio::test(flavor = "current_thread")]
    async fn blocked_consent_view_and_revocation_leave_single_thread_async_tasks_responsive() {
        for revoking in [false, true] {
            let app = tauri::test::mock_builder()
                .manage(crate::history_sync_consent::ConsentStore::default())
                .manage(crate::SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
                ))
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
                .build()
                .unwrap();
            let session = app.state::<crate::SessionTokenStore>();
            let epoch = session.snapshot().session_epoch;
            let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap();
            let consent = app.state::<crate::history_sync_consent::ConsentStore>();
            consent
                .grant("synthetic-account", &epoch, &endpoint)
                .unwrap();
            let generation = consent
                .view(session.inner(), &endpoint)
                .unwrap()
                .generation
                .unwrap();
            let token = app.state::<crate::SessionTokenStore>().0.clone();
            let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
            let (release_sender, release_receiver) = std::sync::mpsc::channel();
            let holder = std::thread::spawn(move || {
                let _guard = token.lock().unwrap();
                entered_sender.send(()).unwrap();
                release_receiver
                    .recv_timeout(std::time::Duration::from_secs(3))
                    .is_ok()
            });
            entered_receiver.await.unwrap();
            let progressed = std::cell::Cell::new(false);
            let completed = std::cell::Cell::new(false);
            let observation = std::cell::Cell::new(false);
            let reading = async {
                let result = if revoking {
                    revoke_history_sync_consent_generation(window, epoch, generation)
                        .await
                        .map(|_| ())
                } else {
                    get_history_sync_consent_view(window).await.map(|_| ())
                };
                completed.set(true);
                result
            };
            let releasing = async {
                tokio::time::sleep(std::time::Duration::from_millis(30)).await;
                progressed.set(true);
                observation.set(!completed.get());
                let _ = release_sender.send(());
            };
            let (view, ()) = tokio::join!(reading, releasing);
            let released = holder.join().unwrap();
            assert!(
                released,
                "Async task must release the lock before the holder times out"
            );
            assert!(progressed.get());
            assert!(
                observation.get(),
                "View must still wait while the token lock is held"
            );
            view.unwrap();
            assert_eq!(
                consent.view(session.inner(), &endpoint).unwrap().active,
                !revoking
            );
        }
    }

    #[test]
    fn consent_view_reads_locks_in_a_blocking_worker_not_the_ipc_thread() {
        let source = include_str!("history_sync_commands.rs");
        let command = source
            .split("fn get_history_sync_consent_view")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        assert!(command.contains("spawn_blocking"));
        assert!(
            command.find("ensure_local_window").unwrap() < command.find("spawn_blocking").unwrap()
        );
        assert!(command.find("spawn_blocking").unwrap() < command.find(".view(").unwrap());
    }

    #[tokio::test]
    async fn confirmation_rejects_untrusted_windows_and_missing_proposals() {
        for (label, remote) in [("ask", false), ("main", true), ("main", false)] {
            let app = tauri::test::mock_builder()
                .manage(crate::history_sync_proposal::ProposalStore::default())
                .manage(crate::history_sync_consent::ConsentStore::default())
                .manage(crate::SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
                ))
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let epoch = app
                .state::<crate::SessionTokenStore>()
                .snapshot()
                .session_epoch;
            let url = if remote {
                tauri::WebviewUrl::External("https://example.invalid/".parse().unwrap())
            } else {
                Default::default()
            };
            let window = tauri::WebviewWindowBuilder::new(&app, label, url)
                .build()
                .unwrap();
            let result =
                confirm_history_sync_proposal(window, uuid::Uuid::new_v4().to_string(), epoch)
                    .await;
            let error = result.unwrap_err();
            assert!(error.contains(if label != "main" {
                "main window"
            } else if remote {
                "local application content"
            } else {
                "confirmation unavailable"
            }));
        }
    }

    #[tokio::test]
    async fn prepare_command_rejects_auxiliary_remote_and_stale_calls_before_network() {
        for (label, remote) in [("ask", false), ("main", true), ("main", false)] {
            let app = tauri::test::mock_builder()
                .manage(crate::history_sync_proposal::ProposalStore::default())
                .manage(crate::SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
                ))
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
            let result =
                prepare_history_sync_proposal(window, uuid::Uuid::new_v4().to_string()).await;
            let error = result.unwrap_err();
            assert!(error.contains(if label != "main" {
                "main window"
            } else if remote {
                "local application content"
            } else {
                "proposal session"
            }));
        }
    }

    #[test]
    fn proposal_session_requires_the_expected_authenticated_epoch() {
        let session = crate::SessionTokenStore(
            std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into())),
            std::sync::atomic::AtomicBool::new(false),
            std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let epoch = session.snapshot().session_epoch;
        let expected = proposal_session(&session, &epoch).unwrap();
        assert_eq!(expected.session_epoch, epoch);
        assert_eq!(expected.token.as_deref(), Some("synthetic-token"));
        assert!(proposal_session(&session, "invalid").is_err());
        session.rotate_epoch();
        assert!(proposal_session(&session, &epoch).is_err());
        let current = session.snapshot().session_epoch;
        session.0.lock().unwrap().clear();
        assert!(proposal_session(&session, &current).is_err());
    }

    #[test]
    fn proposal_cancel_ipc_requires_local_main_and_does_not_require_credentials() {
        for (label, remote) in [
            ("main", false),
            ("main", true),
            ("ask", false),
            ("capsule", false),
        ] {
            let app = tauri::test::mock_builder()
                .manage(crate::history_sync_proposal::ProposalStore::default())
                .invoke_handler(tauri::generate_handler![cancel_history_sync_proposal])
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
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "cancel_history_sync_proposal".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: origin.parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "requestId": uuid::Uuid::new_v4().to_string()
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            if label == "main" && !remote {
                assert!(!result.unwrap().deserialize::<bool>().unwrap());
            } else {
                assert!(result.is_err());
            }
        }
    }

    #[test]
    fn generation_revocation_ipc_rejects_old_grants_and_untrusted_windows() {
        for (label, remote, stale) in [
            ("main", false, false),
            ("main", false, true),
            ("main", true, false),
            ("ask", false, false),
            ("capsule", false, false),
        ] {
            let app = tauri::test::mock_builder()
                .manage(crate::history_sync_consent::ConsentStore::default())
                .manage(crate::SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
                ))
                .invoke_handler(tauri::generate_handler![
                    revoke_history_sync_consent_generation
                ])
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let session = app.state::<crate::SessionTokenStore>();
            let consent = app.state::<crate::history_sync_consent::ConsentStore>();
            let epoch = session.snapshot().session_epoch;
            let endpoint = "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap();
            consent
                .grant("synthetic-account", &epoch, &endpoint)
                .unwrap();
            let previous = consent.view(session.inner(), &endpoint).unwrap();
            if stale {
                consent
                    .grant("synthetic-account", &epoch, &endpoint)
                    .unwrap();
            }
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
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "revoke_history_sync_consent_generation".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: origin.parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "expectedSessionEpoch": epoch,
                        "expectedGeneration": previous.generation
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            let allowed = label == "main" && !remote && !stale;
            assert_eq!(result.is_ok(), allowed);
            assert_eq!(
                consent.view(session.inner(), &endpoint).unwrap().active,
                !allowed
            );
        }
    }

    #[test]
    fn epoch_ipc_returns_only_identity_to_local_main_without_vault_state() {
        for (label, remote) in [
            ("main", false),
            ("main", true),
            ("ask", false),
            ("capsule", false),
        ] {
            let epoch = uuid::Uuid::new_v4().to_string();
            let app = tauri::test::mock_builder()
                .manage(crate::SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new("synthetic-private-token".into())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(epoch.clone()),
                ))
                .invoke_handler(tauri::generate_handler![get_history_sync_session_epoch])
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
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "get_history_sync_session_epoch".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: origin.parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            if label == "main" && !remote {
                assert_eq!(result.unwrap().deserialize::<String>().unwrap(), epoch);
            } else {
                assert!(result.is_err());
            }
        }
    }

    #[test]
    fn status_ipc_only_returns_a_boolean_to_local_main() {
        for (label, remote) in [
            ("ask", false),
            ("capsule", false),
            ("main", true),
            ("main", false),
        ] {
            let app = tauri::test::mock_builder()
                .manage(crate::history_sync_consent::ConsentStore::default())
                .manage(crate::SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
                ))
                .invoke_handler(tauri::generate_handler![
                    get_history_sync_consent_status,
                    get_history_sync_consent_view
                ])
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
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "get_history_sync_consent_status".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: if remote {
                        "https://example.invalid/"
                    } else {
                        "tauri://localhost"
                    }
                    .parse()
                    .unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            if label == "main" && !remote {
                assert!(!result.unwrap().deserialize::<bool>().unwrap());
            } else {
                assert!(result.is_err());
            }
            let view_result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "get_history_sync_consent_view".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: if remote {
                        "https://example.invalid/"
                    } else {
                        "tauri://localhost"
                    }
                    .parse()
                    .unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            if label == "main" && !remote {
                let view = view_result
                    .unwrap()
                    .deserialize::<serde_json::Value>()
                    .unwrap();
                assert_eq!(view.as_object().unwrap().len(), 3);
                assert_eq!(view["active"], false);
                assert_eq!(view["generation"], serde_json::Value::Null);
                assert_eq!(
                    view["sessionEpoch"],
                    app.state::<crate::SessionTokenStore>()
                        .snapshot()
                        .session_epoch
                );
            } else {
                assert!(view_result.is_err());
            }
        }
    }

    #[test]
    fn revocation_ipc_only_allows_local_main_and_invalidates_the_granted_snapshot() {
        for (label, remote) in [
            ("ask", false),
            ("capsule", false),
            ("main", true),
            ("main", false),
        ] {
            let app = tauri::test::mock_builder()
                .manage(crate::history_sync_consent::ConsentStore::default())
                .manage(crate::SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".into())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
                ))
                .invoke_handler(tauri::generate_handler![revoke_history_sync_consent])
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let consent = app.state::<crate::history_sync_consent::ConsentStore>();
            let epoch = app
                .state::<crate::SessionTokenStore>()
                .snapshot()
                .session_epoch;
            let endpoint = "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap();
            let snapshot = consent
                .grant("synthetic-account", &epoch, &endpoint)
                .unwrap();
            let url = if remote {
                tauri::WebviewUrl::External("https://example.invalid/".parse().unwrap())
            } else {
                Default::default()
            };
            let window = tauri::WebviewWindowBuilder::new(&app, label, url)
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "revoke_history_sync_consent".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: if remote {
                        "https://example.invalid/"
                    } else {
                        "tauri://localhost"
                    }
                    .parse()
                    .unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "expectedSessionEpoch": epoch
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            let allowed = label == "main" && !remote;
            assert_eq!(result.is_ok(), allowed, "{label}, remote={remote}");
            assert_eq!(
                consent
                    .check(&snapshot, "synthetic-account", &epoch, &endpoint)
                    .is_ok(),
                !allowed
            );
        }
    }
}
