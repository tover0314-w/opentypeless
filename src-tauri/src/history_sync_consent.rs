use std::sync::Mutex;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct ConsentSnapshot {
    account_id: String,
    session_epoch: String,
    endpoint: String,
    generation: uuid::Uuid,
}

impl ConsentSnapshot {
    pub(crate) fn matches_view(&self, epoch: &str, generation: &str) -> bool {
        self.session_epoch == epoch && self.generation.to_string() == generation
    }

    pub(crate) fn account_id(&self) -> &str {
        &self.account_id
    }
}

#[derive(Default)]
pub struct ConsentStore {
    current: Mutex<Option<ConsentSnapshot>>,
    changes: tokio::sync::watch::Sender<()>,
}

#[derive(Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ConsentView {
    pub session_epoch: String,
    pub active: bool,
    pub generation: Option<String>,
}

impl ConsentStore {
    pub(crate) fn current_for_session(
        &self,
        session: &crate::SessionTokenStore,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<Option<ConsentSnapshot>> {
        let unavailable = || anyhow::anyhow!("History sync consent unavailable");
        let token = session.0.lock().map_err(|_| unavailable())?;
        let epoch = session.2.lock().map_err(|_| unavailable())?;
        let current = self.current.lock().map_err(|_| unavailable())?;
        Ok(current
            .as_ref()
            .filter(|snapshot| {
                !token.trim().is_empty()
                    && snapshot.session_epoch == epoch.as_str()
                    && snapshot.endpoint == endpoint.as_str()
            })
            .cloned())
    }

    #[cfg(test)]
    pub(crate) fn lock_is_held_for_test(&self) -> bool {
        matches!(
            self.current.try_lock(),
            Err(std::sync::TryLockError::WouldBlock)
        )
    }

    pub(crate) fn revoke_generation(
        &self,
        session: &crate::SessionTokenStore,
        expected_epoch: &str,
        expected_generation: &str,
    ) -> anyhow::Result<()> {
        let unavailable = || anyhow::anyhow!("History sync consent revocation superseded");
        let generation = uuid::Uuid::parse_str(expected_generation).map_err(|_| unavailable())?;
        let expected = uuid::Uuid::parse_str(expected_epoch).map_err(|_| unavailable())?;
        if generation.get_version_num() != 4 || expected.get_version_num() != 4 {
            return Err(unavailable());
        }
        let _token = session.0.lock().map_err(|_| unavailable())?;
        let epoch = session.2.lock().map_err(|_| unavailable())?;
        let mut current = self.current.lock().map_err(|_| unavailable())?;
        if epoch.as_str() != expected_epoch
            || !current.as_ref().is_some_and(|snapshot| {
                snapshot.session_epoch == expected_epoch && snapshot.generation == generation
            })
        {
            return Err(unavailable());
        }
        *current = None;
        self.changes.send_replace(());
        Ok(())
    }

    pub(crate) fn view(
        &self,
        session: &crate::SessionTokenStore,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<ConsentView> {
        let unavailable = || anyhow::anyhow!("History sync consent unavailable");
        let token = session.0.lock().map_err(|_| unavailable())?;
        let epoch = session.2.lock().map_err(|_| unavailable())?;
        let current = self.current.lock().map_err(|_| unavailable())?;
        let active = current.as_ref().filter(|snapshot| {
            !token.trim().is_empty()
                && snapshot.session_epoch == epoch.as_str()
                && snapshot.endpoint == endpoint.as_str()
        });
        Ok(ConsentView {
            session_epoch: epoch.clone(),
            active: active.is_some(),
            generation: active.map(|snapshot| snapshot.generation.to_string()),
        })
    }

    #[cfg(test)]
    pub(crate) fn revoke_for_session(
        &self,
        session: &crate::SessionTokenStore,
        expected_epoch: &str,
    ) -> anyhow::Result<()> {
        let unavailable = || anyhow::anyhow!("History sync consent revocation superseded");
        if uuid::Uuid::parse_str(expected_epoch)
            .ok()
            .is_none_or(|epoch| epoch.get_version_num() != 4)
        {
            return Err(unavailable());
        }
        let _token = session.0.lock().map_err(|_| unavailable())?;
        let epoch = session.2.lock().map_err(|_| unavailable())?;
        if epoch.as_str() != expected_epoch {
            return Err(unavailable());
        }
        self.revoke()
    }

    pub(crate) fn active_for_session(
        &self,
        session: &crate::SessionTokenStore,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<bool> {
        self.view(session, endpoint).map(|view| view.active)
    }

    #[cfg(test)]
    pub(crate) fn poison_for_test(&self) {
        let _ = std::panic::catch_unwind(|| {
            let _guard = self.current.lock().unwrap();
            panic!("Synthetic consent lock poisoning");
        });
    }

    pub(crate) async fn await_current<T>(
        &self,
        expected: &ConsentSnapshot,
        account_id: &str,
        session_epoch: &str,
        endpoint: &reqwest::Url,
        operation: impl std::future::Future<Output = anyhow::Result<T>>,
    ) -> anyhow::Result<T> {
        let mut changes = self.changes.subscribe();
        self.check(expected, account_id, session_epoch, endpoint)?;
        tokio::pin!(operation);
        loop {
            tokio::select! {
                biased;
                changed = changes.changed() => {
                    changed.map_err(|_| anyhow::anyhow!("History sync consent unavailable"))?;
                    self.check(expected, account_id, session_epoch, endpoint)?;
                },
                result = &mut operation => {
                    self.check(expected, account_id, session_epoch, endpoint)?;
                    return result;
                }
            }
        }
    }

    #[cfg(test)]
    pub(crate) fn grant_synthetic_loopback(
        &self,
        account_id: &str,
        session_epoch: &str,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<ConsentSnapshot> {
        assert_eq!(endpoint.scheme(), "http");
        assert_eq!(endpoint.host_str(), Some("127.0.0.1"));
        let mut snapshot = self.grant(
            account_id,
            session_epoch,
            &"https://example.invalid/api/history-sync/status".parse()?,
        )?;
        snapshot.endpoint = endpoint.as_str().into();
        *self.current.lock().unwrap() = Some(snapshot.clone());
        Ok(snapshot)
    }

    pub(crate) fn with_empty<T>(
        &self,
        action: impl FnOnce() -> anyhow::Result<T>,
    ) -> anyhow::Result<T> {
        let current = self
            .current
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync consent unavailable"))?;
        if current.is_some() {
            anyhow::bail!("History sync authorization already exists");
        }
        let result = action();
        drop(current);
        result
    }

    pub(crate) fn with_current<T>(
        &self,
        expected: &ConsentSnapshot,
        account_id: &str,
        session_epoch: &str,
        endpoint: &reqwest::Url,
        action: impl FnOnce() -> anyhow::Result<T>,
    ) -> anyhow::Result<T> {
        let current = self
            .current
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync consent unavailable"))?;
        if current.as_ref() != Some(expected)
            || expected.account_id != account_id
            || expected.session_epoch != session_epoch
            || expected.endpoint != endpoint.as_str()
        {
            anyhow::bail!("History sync consent unavailable");
        }
        let result = action();
        drop(current);
        result
    }

    #[cfg(test)]
    pub(crate) fn grant(
        &self,
        account_id: &str,
        session_epoch: &str,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<ConsentSnapshot> {
        self.grant_with_policy(account_id, session_epoch, endpoint, false)
    }

    pub(crate) fn grant_if_empty(
        &self,
        account_id: &str,
        session_epoch: &str,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<ConsentSnapshot> {
        self.grant_with_policy(account_id, session_epoch, endpoint, true)
    }

    fn grant_with_policy(
        &self,
        account_id: &str,
        session_epoch: &str,
        endpoint: &reqwest::Url,
        require_empty: bool,
    ) -> anyhow::Result<ConsentSnapshot> {
        if account_id.is_empty()
            || account_id.trim() != account_id
            || account_id.len() > 200
            || account_id.chars().any(char::is_control)
            || uuid::Uuid::parse_str(session_epoch)
                .ok()
                .is_none_or(|epoch| epoch.get_version_num() != 4)
            || endpoint.scheme() != "https"
            || !endpoint.username().is_empty()
            || endpoint.password().is_some()
            || endpoint.query().is_some()
            || endpoint.fragment().is_some()
            || endpoint.path() != "/api/history-sync/status"
        {
            anyhow::bail!("History sync consent unavailable");
        }
        let snapshot = ConsentSnapshot {
            account_id: account_id.into(),
            session_epoch: session_epoch.into(),
            endpoint: endpoint.as_str().into(),
            generation: uuid::Uuid::new_v4(),
        };
        let mut current = self
            .current
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync consent unavailable"))?;
        if require_empty && current.is_some() {
            anyhow::bail!("History sync authorization already exists");
        }
        *current = Some(snapshot.clone());
        self.changes.send_replace(());
        Ok(snapshot)
    }

    pub(crate) fn check(
        &self,
        expected: &ConsentSnapshot,
        account_id: &str,
        session_epoch: &str,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<()> {
        self.with_current(expected, account_id, session_epoch, endpoint, || Ok(()))
    }

    pub fn revoke(&self) -> anyhow::Result<()> {
        *self
            .current
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync consent unavailable"))? = None;
        self.changes.send_replace(());
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{atomic::AtomicBool, Arc};

    fn synthetic_session() -> crate::SessionTokenStore {
        crate::SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        )
    }

    #[test]
    fn empty_publication_gate_holds_consent_lock_and_refuses_existing_authorization() {
        let store = ConsentStore::default();
        assert_eq!(
            store
                .with_empty(|| {
                    assert!(store.lock_is_held_for_test());
                    Ok(7)
                })
                .unwrap(),
            7
        );
        store
            .grant(
                "synthetic-account",
                &uuid::Uuid::new_v4().to_string(),
                &"https://example.invalid/api/history-sync/status"
                    .parse()
                    .unwrap(),
            )
            .unwrap();
        let mut ran = false;
        assert!(store
            .with_empty(|| {
                ran = true;
                Ok(())
            })
            .is_err());
        assert!(!ran);
    }

    #[test]
    fn concurrent_empty_grants_have_one_winner_and_preserve_its_generation() {
        for _round in 0..16 {
            let store = std::sync::Arc::new(ConsentStore::default());
            let start = std::sync::Arc::new(std::sync::Barrier::new(3));
            let epoch = uuid::Uuid::new_v4().to_string();
            let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap();
            let mut workers = Vec::new();
            for account in ["synthetic-account-a", "synthetic-account-b"] {
                let store = store.clone();
                let start = start.clone();
                let epoch = epoch.clone();
                let endpoint = endpoint.clone();
                workers.push(std::thread::spawn(move || {
                    start.wait();
                    store.grant_if_empty(account, &epoch, &endpoint)
                }));
            }
            start.wait();
            let outcomes: Vec<_> = workers
                .into_iter()
                .map(|worker| worker.join().unwrap())
                .collect();
            assert_eq!(outcomes.iter().filter(|outcome| outcome.is_ok()).count(), 1);
            let winner = outcomes.into_iter().find_map(Result::ok).unwrap();
            assert!(store
                .check(&winner, &winner.account_id, &epoch, &endpoint)
                .is_ok());
            let mut changes = store.changes.subscribe();
            changes.borrow_and_update();
            assert!(store
                .grant_if_empty("synthetic-account-c", &epoch, &endpoint)
                .is_err());
            assert!(!changes.has_changed().unwrap());
            assert!(store
                .check(&winner, &winner.account_id, &epoch, &endpoint)
                .is_ok());
        }
    }

    #[test]
    fn old_generation_revocation_preserves_reconfirmed_consent_in_the_same_session() {
        let session = crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-token".into())),
            std::sync::atomic::AtomicBool::new(false),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let epoch = session.snapshot().session_epoch;
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let store = ConsentStore::default();
        let old = store.grant("synthetic-account", &epoch, &endpoint).unwrap();
        let current = store.grant("synthetic-account", &epoch, &endpoint).unwrap();
        assert!(store
            .revoke_generation(&session, &epoch, &old.generation.to_string())
            .is_err());
        assert!(store
            .check(&current, "synthetic-account", &epoch, &endpoint)
            .is_ok());
        assert!(store
            .revoke_generation(&session, &epoch, "invalid")
            .is_err());
        store
            .revoke_generation(&session, &epoch, &current.generation.to_string())
            .unwrap();
        assert!(store
            .check(&current, "synthetic-account", &epoch, &endpoint)
            .is_err());
        assert!(store
            .revoke_generation(&session, &epoch, &current.generation.to_string())
            .is_err());
    }

    #[test]
    fn consent_view_atomically_binds_epoch_and_generation_without_credentials() {
        let session = crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-private-token".into())),
            std::sync::atomic::AtomicBool::new(false),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let store = ConsentStore::default();
        let epoch = session.snapshot().session_epoch;
        assert_eq!(
            store.view(&session, &endpoint).unwrap(),
            ConsentView {
                session_epoch: epoch.clone(),
                active: false,
                generation: None,
            }
        );
        let first = store
            .grant("synthetic-private-account", &epoch, &endpoint)
            .unwrap();
        let view = store.view(&session, &endpoint).unwrap();
        assert_eq!(view.generation, Some(first.generation.to_string()));
        assert!(view.active);
        let serialized = serde_json::to_value(&view).unwrap();
        assert_eq!(serialized.as_object().unwrap().len(), 3);
        assert!(serialized.get("token").is_none());
        assert!(serialized.get("accountId").is_none());
        let second = store
            .grant("synthetic-private-account", &epoch, &endpoint)
            .unwrap();
        assert_ne!(first.generation, second.generation);
        assert_eq!(
            store.view(&session, &endpoint).unwrap().generation,
            Some(second.generation.to_string())
        );
        session.rotate_epoch();
        let changed = store.view(&session, &endpoint).unwrap();
        assert_ne!(changed.session_epoch, epoch);
        assert!(!changed.active);
        assert!(changed.generation.is_none());
    }

    #[test]
    fn stale_revocation_cannot_clear_new_session_consent() {
        let session = crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-token".into())),
            std::sync::atomic::AtomicBool::new(false),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let stale = session.snapshot().session_epoch;
        session.rotate_epoch();
        let current = session.snapshot().session_epoch;
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let store = ConsentStore::default();
        let authorization = store
            .grant("synthetic-account", &current, &endpoint)
            .unwrap();
        assert!(store.revoke_for_session(&session, &stale).is_err());
        assert!(store
            .check(&authorization, "synthetic-account", &current, &endpoint)
            .is_ok());
        assert!(store.revoke_for_session(&session, "invalid").is_err());
        store.revoke_for_session(&session, &current).unwrap();
        assert!(store
            .check(&authorization, "synthetic-account", &current, &endpoint)
            .is_err());
    }

    #[test]
    fn public_status_exposes_only_current_session_consent_without_restoring_credentials() {
        let session = crate::SessionTokenStore(
            std::sync::Arc::new(Mutex::new("synthetic-token".into())),
            std::sync::atomic::AtomicBool::new(false),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let store = ConsentStore::default();
        assert!(!store.active_for_session(&session, &endpoint).unwrap());
        store
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        assert!(store.active_for_session(&session, &endpoint).unwrap());
        assert!(!store
            .active_for_session(
                &session,
                &"https://other.invalid/api/history-sync/status"
                    .parse()
                    .unwrap()
            )
            .unwrap());
        session.rotate_epoch();
        assert!(!store.active_for_session(&session, &endpoint).unwrap());
        store
            .grant(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint,
            )
            .unwrap();
        session.0.lock().unwrap().clear();
        assert!(!store.active_for_session(&session, &endpoint).unwrap());
    }

    #[test]
    fn current_snapshot_is_available_only_for_the_bound_session_and_endpoint() {
        let store = ConsentStore::default();
        let session = synthetic_session();
        let epoch = session.snapshot().session_epoch;
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        assert!(store
            .current_for_session(&session, &endpoint)
            .unwrap()
            .is_none());
        let granted = store.grant("synthetic-account", &epoch, &endpoint).unwrap();
        assert_eq!(
            store.current_for_session(&session, &endpoint).unwrap(),
            Some(granted.clone())
        );
        assert!(store
            .current_for_session(
                &session,
                &"https://other.invalid/api/history-sync/status"
                    .parse()
                    .unwrap()
            )
            .unwrap()
            .is_none());
        session.rotate_epoch();
        assert!(store
            .current_for_session(&session, &endpoint)
            .unwrap()
            .is_none());
    }

    #[tokio::test]
    async fn delayed_notification_does_not_cancel_current_generation_or_restart_operation() {
        let store = ConsentStore::default();
        let epoch = uuid::Uuid::new_v4().to_string();
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let expected = store.grant("synthetic-account", &epoch, &endpoint).unwrap();
        let polls_started = std::cell::Cell::new(0);
        let operation = async {
            polls_started.set(polls_started.get() + 1);
            store.changes.send_replace(());
            tokio::task::yield_now().await;
            Ok(7)
        };
        let result = store
            .await_current(&expected, "synthetic-account", &epoch, &endpoint, operation)
            .await;
        assert_eq!(result.unwrap(), 7);
        assert_eq!(polls_started.get(), 1);
    }

    #[tokio::test]
    async fn revocation_stops_waiting_without_polling_operation_to_completion() {
        let store = ConsentStore::default();
        let epoch = uuid::Uuid::new_v4().to_string();
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let expected = store.grant("synthetic-account", &epoch, &endpoint).unwrap();
        let operation = async {
            store.revoke()?;
            std::future::pending::<anyhow::Result<()>>().await
        };
        let result = tokio::time::timeout(
            std::time::Duration::from_millis(500),
            store.await_current(&expected, "synthetic-account", &epoch, &endpoint, operation),
        )
        .await;
        assert!(
            result.is_ok(),
            "Revocation must stop waiting for the operation"
        );
        assert!(result.unwrap().is_err());
    }

    #[test]
    fn consent_lock_covers_actual_sqlite_commit_and_revocation_blocks_old_transactions() {
        use std::sync::{mpsc, Arc};
        use std::time::Duration;

        let store = Arc::new(ConsentStore::default());
        let epoch = uuid::Uuid::new_v4().to_string();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let expected = store.grant("synthetic-account", &epoch, &endpoint).unwrap();
        let (entered_sender, entered_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let worker_store = store.clone();
        let worker_expected = expected.clone();
        let worker_epoch = epoch.clone();
        let worker_endpoint = endpoint.clone();
        let committing = std::thread::spawn(move || {
            let mut connection = rusqlite::Connection::open_in_memory().unwrap();
            connection
                .execute_batch("CREATE TABLE synthetic_commit (value INTEGER)")
                .unwrap();
            let transaction = connection.transaction().unwrap();
            transaction
                .execute("INSERT INTO synthetic_commit VALUES (1)", [])
                .unwrap();
            let result = worker_store.with_current(
                &worker_expected,
                "synthetic-account",
                &worker_epoch,
                &worker_endpoint,
                || {
                    entered_sender
                        .send(())
                        .map_err(|_| anyhow::anyhow!("Synthetic observer unavailable"))?;
                    release_receiver
                        .recv_timeout(Duration::from_secs(3))
                        .map_err(|_| anyhow::anyhow!("Synthetic release unavailable"))?;
                    transaction.commit()?;
                    Ok(())
                },
            );
            let count: i64 = connection
                .query_row("SELECT COUNT(*) FROM synthetic_commit", [], |row| {
                    row.get(0)
                })
                .unwrap();
            (result, count)
        });
        let entered = entered_receiver.recv_timeout(Duration::from_secs(3));
        if entered.is_err() {
            drop(release_sender);
            let result = committing.join().unwrap();
            panic!("Consent gate did not enter SQLite commit: {:?}", result.0);
        }
        let (blocked_sender, blocked_receiver) = mpsc::channel();
        let (finished_sender, finished_receiver) = mpsc::channel();
        let revoking_store = store.clone();
        let revoking = std::thread::spawn(move || {
            blocked_sender
                .send(matches!(
                    revoking_store.current.try_lock(),
                    Err(std::sync::TryLockError::WouldBlock)
                ))
                .unwrap();
            let result = revoking_store.revoke();
            finished_sender.send(()).unwrap();
            result
        });
        let blocked = blocked_receiver.recv_timeout(Duration::from_secs(3));
        let premature = finished_receiver.try_recv();
        release_sender.send(()).unwrap();
        let (result, count) = committing.join().unwrap();
        revoking.join().unwrap().unwrap();
        assert!(blocked.unwrap());
        assert!(matches!(premature, Err(mpsc::TryRecvError::Empty)));
        result.unwrap();
        assert_eq!(count, 1);
        let mut connection = rusqlite::Connection::open_in_memory().unwrap();
        connection
            .execute_batch("CREATE TABLE synthetic_commit (value INTEGER)")
            .unwrap();
        let transaction = connection.transaction().unwrap();
        transaction
            .execute("INSERT INTO synthetic_commit VALUES (2)", [])
            .unwrap();
        assert!(store
            .with_current(&expected, "synthetic-account", &epoch, &endpoint, || {
                transaction.commit()?;
                Ok(())
            })
            .is_err());
        assert!(connection.is_autocommit());
        let count: i64 = connection
            .query_row("SELECT COUNT(*) FROM synthetic_commit", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(count, 0);
    }

    #[test]
    fn invalid_binding_and_poisoned_store_never_create_consent() {
        let epoch = uuid::Uuid::new_v4().to_string();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let store = ConsentStore::default();
        for account in ["", " account", "account\n", &"x".repeat(201)] {
            assert!(store.grant(account, &epoch, &endpoint).is_err());
        }
        assert!(store.grant("account", "invalid", &endpoint).is_err());
        for address in [
            "http://127.0.0.1/api/history-sync/status",
            "https://user@example.invalid/api/history-sync/status",
            "https://example.invalid/api/history-sync/status?extra=1",
            "https://example.invalid/api/history-sync/status#extra",
            "https://example.invalid/other",
        ] {
            assert!(store
                .grant("account", &epoch, &address.parse().unwrap())
                .is_err());
        }
        assert!(store.current.lock().unwrap().is_none());
        let poisoned = std::sync::Arc::new(ConsentStore::default());
        let worker = poisoned.clone();
        assert!(std::thread::spawn(move || {
            let _guard = worker.current.lock().unwrap();
            panic!("Synthetic consent lock poisoning");
        })
        .join()
        .is_err());
        assert!(poisoned.grant("account", &epoch, &endpoint).is_err());
        assert!(poisoned.revoke().is_err());
    }

    #[test]
    fn explicit_grant_is_bound_and_revocation_never_revives_an_old_generation() {
        let store = ConsentStore::default();
        let epoch = uuid::Uuid::new_v4().to_string();
        let endpoint = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let first = store.grant("synthetic-account", &epoch, &endpoint).unwrap();
        assert!(store
            .check(&first, "synthetic-account", &epoch, &endpoint)
            .is_ok());
        assert!(store
            .check(&first, "other-account", &epoch, &endpoint)
            .is_err());
        assert!(store
            .check(
                &first,
                "synthetic-account",
                &uuid::Uuid::new_v4().to_string(),
                &endpoint,
            )
            .is_err());
        assert!(store
            .check(
                &first,
                "synthetic-account",
                &epoch,
                &"https://other.invalid/api/history-sync/status"
                    .parse()
                    .unwrap(),
            )
            .is_err());
        store.revoke().unwrap();
        assert!(store
            .check(&first, "synthetic-account", &epoch, &endpoint)
            .is_err());
        let second = store.grant("synthetic-account", &epoch, &endpoint).unwrap();
        assert_ne!(first.generation, second.generation);
        assert!(store
            .check(&first, "synthetic-account", &epoch, &endpoint)
            .is_err());
        assert!(store
            .check(&second, "synthetic-account", &epoch, &endpoint)
            .is_ok());
        let fresh_store = ConsentStore::default();
        assert!(fresh_store
            .check(&second, "synthetic-account", &epoch, &endpoint)
            .is_err());
    }
}
