use crate::{CloudSessionSnapshot, SessionTokenStore};

pub(crate) fn commit_with_current_session<T>(
    store: &SessionTokenStore,
    expected: &CloudSessionSnapshot,
    commit: impl FnOnce() -> anyhow::Result<T>,
) -> anyhow::Result<T> {
    let unavailable = || anyhow::anyhow!("History sync session unavailable");
    let expected_token = expected
        .token
        .as_deref()
        .filter(|token| !token.trim().is_empty())
        .ok_or_else(unavailable)?;
    if uuid::Uuid::parse_str(&expected.session_epoch)
        .ok()
        .is_none_or(|epoch| epoch.get_version_num() != 4)
    {
        return Err(unavailable());
    }
    let token = store.0.lock().map_err(|_| unavailable())?;
    let epoch = store.2.lock().map_err(|_| unavailable())?;
    if token.as_str() != expected_token || epoch.as_str() != expected.session_epoch {
        return Err(unavailable());
    }
    let result = commit().map_err(|_| anyhow::anyhow!("History sync commit unavailable"));
    drop(epoch);
    drop(token);
    result
}

pub(crate) fn with_current_session<T>(
    store: &SessionTokenStore,
    expected: &CloudSessionSnapshot,
    build: impl FnOnce() -> anyhow::Result<T>,
) -> anyhow::Result<T> {
    ensure_current(store, expected)?;
    let result = build();
    ensure_current(store, expected)?;
    result.map_err(|_| anyhow::anyhow!("History sync request unavailable"))
}

fn ensure_current(
    store: &SessionTokenStore,
    expected: &CloudSessionSnapshot,
) -> anyhow::Result<()> {
    let unavailable = || anyhow::anyhow!("History sync session unavailable");
    let expected_token = expected
        .token
        .as_deref()
        .filter(|token| !token.trim().is_empty())
        .ok_or_else(unavailable)?;
    if uuid::Uuid::parse_str(&expected.session_epoch)
        .ok()
        .is_none_or(|epoch| epoch.get_version_num() != 4)
    {
        return Err(unavailable());
    }
    let token = store.0.lock().map_err(|_| unavailable())?;
    let epoch = store.2.lock().map_err(|_| unavailable())?;
    if token.as_str() != expected_token || epoch.as_str() != expected.session_epoch {
        return Err(unavailable());
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::{atomic::AtomicBool, Arc, Mutex};

    fn session_store() -> SessionTokenStore {
        SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        )
    }

    #[test]
    fn current_session_builds_without_exposing_credentials() {
        let store = session_store();
        let expected = store.snapshot();
        assert_eq!(
            with_current_session(&store, &expected, || Ok(7)).unwrap(),
            7
        );
    }

    #[test]
    fn session_commit_holds_both_locks_and_refuses_stale_owner() {
        let store = session_store();
        let expected = store.snapshot();
        let result = commit_with_current_session(&store, &expected, || {
            assert!(matches!(
                store.0.try_lock(),
                Err(std::sync::TryLockError::WouldBlock)
            ));
            assert!(matches!(
                store.2.try_lock(),
                Err(std::sync::TryLockError::WouldBlock)
            ));
            Ok(7)
        })
        .unwrap();
        assert_eq!(result, 7);
        store.rotate_epoch();
        let called = std::cell::Cell::new(false);
        assert!(commit_with_current_session(&store, &expected, || {
            called.set(true);
            Ok(())
        })
        .is_err());
        assert!(!called.get());
    }

    #[test]
    fn concurrent_logout_waits_for_commit_then_invalidates_the_old_session() {
        use std::sync::mpsc;
        use std::time::Duration;

        let store = Arc::new(session_store());
        let expected = store.snapshot();
        let (entered_sender, entered_receiver) = mpsc::channel();
        let (release_sender, release_receiver) = mpsc::channel();
        let committing_store = store.clone();
        let committing_session = expected.clone();
        let committing = std::thread::spawn(move || {
            commit_with_current_session(&committing_store, &committing_session, || {
                entered_sender.send(()).unwrap();
                release_receiver
                    .recv_timeout(Duration::from_secs(5))
                    .map_err(|_| anyhow::anyhow!("Synthetic commit release unavailable"))?;
                Ok(7)
            })
        });
        entered_receiver
            .recv_timeout(Duration::from_secs(5))
            .unwrap();
        let (blocked_sender, blocked_receiver) = mpsc::channel();
        let (finished_sender, finished_receiver) = mpsc::channel();
        let logging_out_store = store.clone();
        let logging_out = std::thread::spawn(move || {
            let blocked = matches!(
                logging_out_store.0.try_lock(),
                Err(std::sync::TryLockError::WouldBlock)
            );
            blocked_sender.send(blocked).unwrap();
            let mut token = logging_out_store.0.lock().unwrap();
            token.clear();
            logging_out_store.rotate_epoch();
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
        assert_eq!(committed.unwrap(), 7);
        finished_receiver
            .recv_timeout(Duration::from_secs(5))
            .unwrap();
        assert!(store.snapshot().token.is_none());
        assert_ne!(store.snapshot().session_epoch, expected.session_epoch);
        let invoked = std::cell::Cell::new(false);
        assert!(commit_with_current_session(&store, &expected, || {
            invoked.set(true);
            Ok(())
        })
        .is_err());
        assert!(!invoked.get());
    }

    #[test]
    fn changed_session_does_not_invoke_builder() {
        let store = session_store();
        let expected = store.snapshot();
        store.rotate_epoch();
        let called = std::cell::Cell::new(false);
        let error = with_current_session(&store, &expected, || {
            called.set(true);
            Ok(())
        })
        .unwrap_err();
        assert!(!called.get());
        assert_eq!(error.to_string(), "History sync session unavailable");
        assert!(!error.to_string().contains("synthetic-token"));
    }

    #[test]
    fn session_change_during_build_discards_result() {
        let store = session_store();
        let expected = store.snapshot();
        assert!(with_current_session(&store, &expected, || {
            store.rotate_epoch();
            Ok("synthetic-result")
        })
        .is_err());
    }

    #[test]
    fn signed_out_or_token_changed_sessions_are_refused() {
        for token in ["", "another-token"] {
            let store = session_store();
            let expected = store.snapshot();
            *store.0.lock().unwrap() = token.into();
            let called = std::cell::Cell::new(false);
            assert!(with_current_session(&store, &expected, || {
                called.set(true);
                Ok(())
            })
            .is_err());
            assert!(!called.get());
        }
    }

    #[test]
    fn invalid_epoch_and_missing_token_do_not_invoke_builder() {
        let store = session_store();
        for expected in [
            CloudSessionSnapshot {
                token: None,
                session_epoch: store.snapshot().session_epoch,
            },
            CloudSessionSnapshot {
                token: Some("synthetic-token".into()),
                session_epoch: "invalid-epoch".into(),
            },
        ] {
            let called = std::cell::Cell::new(false);
            assert!(with_current_session(&store, &expected, || {
                called.set(true);
                Ok(())
            })
            .is_err());
            assert!(!called.get());
        }
    }

    #[test]
    fn poisoned_session_is_refused_without_recovery() {
        let store = session_store();
        let expected = store.snapshot();
        let token = store.0.clone();
        let _ = std::thread::spawn(move || {
            let _guard = token.lock().unwrap();
            panic!("synthetic poison");
        })
        .join();
        let called = std::cell::Cell::new(false);
        assert!(with_current_session(&store, &expected, || {
            called.set(true);
            Ok(())
        })
        .is_err());
        assert!(!called.get());
    }

    #[test]
    fn builder_error_is_private_and_session_change_takes_precedence() {
        let store = session_store();
        let expected = store.snapshot();
        let error = with_current_session::<()>(&store, &expected, || {
            anyhow::bail!("synthetic-private-body")
        })
        .unwrap_err();
        assert_eq!(error.to_string(), "History sync request unavailable");
        let error = with_current_session::<()>(&store, &expected, || {
            store.rotate_epoch();
            anyhow::bail!("synthetic-private-body")
        })
        .unwrap_err();
        assert_eq!(error.to_string(), "History sync session unavailable");
    }
}
