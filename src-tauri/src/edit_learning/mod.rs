//! Learns dictionary words from the corrections a user makes right after a dictation lands.
//!
//! Everything stays on this device: the focused field is read through Accessibility, compared
//! locally, and only the picked word is stored in the local dictionary.

pub mod analysis;
#[cfg(target_os = "macos")]
mod macos;

#[cfg(target_os = "macos")]
pub(crate) fn native_selected_text(
) -> Option<Result<Option<String>, crate::selection::SelectionError>> {
    macos::selected_text()
}

use std::sync::atomic::{AtomicU64, Ordering};

use serde::Serialize;

pub const WORD_AUTO_ADDED_EVENT: &str = "dictionary:word_auto_added";
pub const DICTIONARY_CHANGED_EVENT: &str = "dictionary:changed";

#[derive(Default)]
pub struct EditLearningState {
    generation: AtomicU64,
    commit_lock: std::sync::Mutex<()>,
}

impl EditLearningState {
    fn invalidate(&self) {
        let _guard = self
            .commit_lock
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        self.generation.fetch_add(1, Ordering::SeqCst);
    }

    #[cfg(any(target_os = "macos", test))]
    fn commit_if_current<Result>(
        &self,
        expected: u64,
        commit: impl FnOnce() -> Result,
    ) -> Option<Result> {
        let _guard = self.commit_lock.lock().ok()?;
        if self.generation.load(Ordering::SeqCst) != expected {
            return None;
        }
        Some(commit())
    }

    #[cfg(any(target_os = "macos", test))]
    fn claim_start(&self, expected: u64) -> Option<u64> {
        let _guard = self.commit_lock.lock().ok()?;
        let next = expected.checked_add(1)?;
        self.generation
            .compare_exchange(expected, next, Ordering::SeqCst, Ordering::SeqCst)
            .ok()
            .map(|_| next)
    }
}

#[cfg(any(target_os = "macos", test))]
fn learning_session_owner_matches(expected: &str, current: &str) -> bool {
    expected == current
}

#[cfg(any(target_os = "macos", test))]
fn with_learning_session_owner<Result>(
    store: &crate::SessionTokenStore,
    expected: &str,
    action: impl FnOnce() -> Result,
) -> Option<Result> {
    let current = store.2.lock().ok()?;
    if !learning_session_owner_matches(expected, &current) {
        return None;
    }
    Some(action())
}

#[cfg(any(target_os = "macos", test))]
fn learning_session_epoch<R: tauri::Runtime>(app: &tauri::AppHandle<R>) -> Option<String> {
    use tauri::Manager;
    app.try_state::<crate::SessionTokenStore>()
        .and_then(|store| store.2.lock().ok().map(|epoch| epoch.clone()))
}

#[cfg(any(target_os = "macos", test))]
fn learning_session_is_current(
    expected: u64,
    current: u64,
    enabled: bool,
    operation_aborted: bool,
) -> bool {
    enabled && !operation_aborted && expected == current
}

#[cfg(target_os = "macos")]
async fn learning_enabled(app: &tauri::AppHandle) -> bool {
    use tauri::Manager;
    let Some(config) = app.try_state::<crate::storage::ConfigManager>() else {
        return false;
    };
    config
        .load()
        .await
        .ok()
        .is_some_and(|config| config.auto_learn_dictionary)
}

#[cfg(test)]
mod setting_tests {
    use super::*;
    use tauri::Manager;

    #[test]
    fn poisoned_learning_commit_lock_rejects_startup_and_dictionary_write() {
        let state = EditLearningState::default();
        let expected = state.generation.load(Ordering::SeqCst);
        let poisoned = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _guard = state.commit_lock.lock().unwrap();
            panic!("Synthetic poisoned learning commit");
        }));
        assert!(poisoned.is_err());
        assert!(state.claim_start(expected).is_none());
        let dictionary =
            crate::storage::DictionaryStore::new(std::path::PathBuf::from(":memory:")).unwrap();
        assert!(state
            .commit_if_current(expected, || {
                tauri::async_runtime::block_on(
                    dictionary.add_auto_if_current("Unverified learning", || true),
                )
            })
            .is_none());
        tauri::async_runtime::block_on(dictionary.add("Manual word", None)).unwrap();
        let entries = tauri::async_runtime::block_on(dictionary.list()).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].word, "Manual word");
        assert!(!entries[0].auto);
    }

    #[test]
    fn poisoned_session_owner_rejects_learning_reads_and_commits() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        app.manage(crate::SessionTokenStore(
            std::sync::Arc::new(std::sync::Mutex::new(String::new())),
            std::sync::atomic::AtomicBool::new(true),
            std::sync::Mutex::new("synthetic-owner".into()),
        ));
        let store = app.state::<crate::SessionTokenStore>();
        let poisoned = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            let _guard = store.2.lock().unwrap();
            panic!("Synthetic poisoned session state");
        }));
        assert!(poisoned.is_err());
        assert!(learning_session_epoch(app.handle()).is_none());
        let invoked = std::cell::Cell::new(false);
        assert!(
            with_learning_session_owner(&store, "synthetic-owner", || invoked.set(true)).is_none()
        );
        assert!(!invoked.get());
    }

    #[test]
    fn session_guard_holds_epoch_through_dictionary_commit_and_rejects_old_owner() {
        let store = std::sync::Arc::new(crate::SessionTokenStore(
            std::sync::Arc::new(std::sync::Mutex::new(String::new())),
            std::sync::atomic::AtomicBool::new(true),
            std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
        ));
        let dictionary = std::sync::Arc::new(
            crate::storage::DictionaryStore::new(std::path::PathBuf::from(":memory:")).unwrap(),
        );
        let original = store.2.lock().unwrap().clone();
        let (entered_send, entered_receive) = std::sync::mpsc::channel();
        let (release_send, release_receive) = std::sync::mpsc::channel();
        let writing_store = store.clone();
        let writing_dictionary = dictionary.clone();
        let writing_owner = original.clone();
        let writer = std::thread::spawn(move || {
            with_learning_session_owner(&writing_store, &writing_owner, || {
                entered_send.send(()).unwrap();
                release_receive
                    .recv_timeout(std::time::Duration::from_secs(5))
                    .unwrap();
                tauri::async_runtime::block_on(
                    writing_dictionary.add_auto_if_current("Owned result", || true),
                )
            })
        });
        entered_receive
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        assert!(store.2.try_lock().is_err());
        let (rotation_started_send, rotation_started_receive) = std::sync::mpsc::channel();
        let (rotation_done_send, rotation_done_receive) = std::sync::mpsc::channel();
        let rotating_store = store.clone();
        let rotation = std::thread::spawn(move || {
            rotation_started_send.send(()).unwrap();
            rotating_store.rotate_epoch();
            rotation_done_send.send(()).unwrap();
        });
        rotation_started_receive
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        let before_commit =
            rotation_done_receive.recv_timeout(std::time::Duration::from_millis(100));
        release_send.send(()).unwrap();
        let inserted = writer.join().unwrap().unwrap().unwrap();
        rotation.join().unwrap();
        assert!(matches!(
            before_commit,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout)
        ));
        rotation_done_receive
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        assert!(inserted.is_some());
        assert!(with_learning_session_owner(&store, &original, || {
            tauri::async_runtime::block_on(dictionary.add_auto_if_current("Old result", || true))
        })
        .is_none());
        assert_eq!(
            tauri::async_runtime::block_on(dictionary.list())
                .unwrap()
                .len(),
            1
        );
    }

    #[test]
    fn disabling_then_enabling_rejects_old_owner_but_allows_a_new_dictionary_commit() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        app.manage(EditLearningState::default());
        let dictionary =
            crate::storage::DictionaryStore::new(std::path::PathBuf::from(":memory:")).unwrap();
        let state = app.state::<EditLearningState>();
        let old_owner = state.generation.load(Ordering::SeqCst);
        apply_enabled_setting(app.handle(), false);
        apply_enabled_setting(app.handle(), true);
        assert!(state.claim_start(old_owner).is_none());
        assert!(state
            .commit_if_current(old_owner, || {
                tauri::async_runtime::block_on(
                    dictionary.add_auto_if_current("Old processing result", || true),
                )
            })
            .is_none());
        let new_owner = state
            .claim_start(state.generation.load(Ordering::SeqCst))
            .unwrap();
        let inserted = state
            .commit_if_current(new_owner, || {
                tauri::async_runtime::block_on(
                    dictionary.add_auto_if_current("New processing result", || true),
                )
            })
            .unwrap()
            .unwrap();
        assert!(inserted.is_some());
        let entries = tauri::async_runtime::block_on(dictionary.list()).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].word, "New processing result");
        assert!(entries[0].auto);
    }

    #[test]
    fn learning_uses_the_processing_owner_instead_of_capturing_a_new_account() {
        let source = include_str!("mod.rs");
        let startup = source
            .rsplit("pub async fn track_after_insert(")
            .next()
            .unwrap()
            .split("fn run_tracker(")
            .next()
            .unwrap();
        assert!(startup.contains("session_epoch: String"));
        assert!(startup.contains("expected_generation: Option<u64>"));
        assert!(!startup.contains("let expected = state.generation.load"));
        assert!(!startup.contains("let Some(session_epoch) = learning_session_epoch(app)"));
        assert!(startup.contains("learning_session_owner_matches(&session_epoch, &current)"));
    }

    #[test]
    fn tracker_checks_owner_after_waiting_for_current_settings() {
        let source = include_str!("mod.rs");
        let check = source
            .rsplit("fn run_tracker(")
            .next()
            .unwrap()
            .split("    let is_current = || {")
            .nth(1)
            .unwrap()
            .split("    std::thread::sleep(SETTLE_DELAY);")
            .next()
            .unwrap();
        let settings = check.find("block_on(learning_enabled(&app))").unwrap();
        assert!(settings < check.find("learning_session_epoch(&app)").unwrap());
        assert!(settings < check.find("state.generation.load").unwrap());
    }

    #[test]
    fn revoked_tracker_does_not_publish_an_auto_added_notification() {
        use tauri::Listener;
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let state = EditLearningState::default();
        let expected = state.generation.load(Ordering::SeqCst);
        let received = std::sync::Arc::new(std::sync::atomic::AtomicUsize::new(0));
        let captured = received.clone();
        app.listen_any(WORD_AUTO_ADDED_EVENT, move |_| {
            captured.fetch_add(1, Ordering::SeqCst);
        });
        state.invalidate();
        publish_auto_added_if_current(&state, expected, app.handle(), || true, "Old".into(), 1);
        assert_eq!(received.load(Ordering::SeqCst), 0);
        let current = state.generation.load(Ordering::SeqCst);
        publish_auto_added_if_current(&state, current, app.handle(), || false, "Old".into(), 1);
        assert_eq!(received.load(Ordering::SeqCst), 0);
        publish_auto_added_if_current(&state, current, app.handle(), || true, "Current".into(), 2);
        assert_eq!(received.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn session_owner_reads_only_epoch_and_rejects_rotated_native_store() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        assert!(learning_session_epoch(app.handle()).is_none());
        app.manage(crate::SessionTokenStore(
            std::sync::Arc::new(std::sync::Mutex::new(String::new())),
            std::sync::atomic::AtomicBool::new(true),
            std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
        ));
        let store = app.state::<crate::SessionTokenStore>();
        let token_guard = store.0.lock().unwrap();
        let original = learning_session_epoch(app.handle()).unwrap();
        assert!(learning_session_owner_matches(
            &original,
            &learning_session_epoch(app.handle()).unwrap()
        ));
        store.rotate_epoch();
        assert!(!learning_session_owner_matches(
            &original,
            &learning_session_epoch(app.handle()).unwrap()
        ));
        assert!(token_guard.is_empty());
    }

    #[test]
    fn ask_and_practice_revoke_previous_learning_before_new_capture() {
        let ask = include_str!("../commands/ask.rs")
            .split("pub(crate) async fn start_reserved_ask_dictation(")
            .nth(1)
            .unwrap()
            .split("let result = async")
            .next()
            .unwrap();
        let practice = include_str!("../practice.rs")
            .split("pub fn start(app: tauri::AppHandle)")
            .nth(1)
            .unwrap()
            .split("tauri::async_runtime::spawn")
            .next()
            .unwrap();
        for source in [ask, practice] {
            let cancelled = source.find("crate::edit_learning::cancel(&app);").unwrap();
            assert!(
                cancelled
                    < source
                        .find("crate::audio::preview::stop_for_voice_operation(&app);")
                        .unwrap()
            );
            assert!(source.find("try_voice_start_guard").unwrap() < cancelled);
        }
    }

    #[test]
    fn concurrent_invalidation_waits_for_dictionary_commit_and_blocks_old_retry() {
        let state = std::sync::Arc::new(EditLearningState::default());
        let dictionary = std::sync::Arc::new(
            crate::storage::DictionaryStore::new(std::path::PathBuf::from(":memory:")).unwrap(),
        );
        let expected = state.generation.load(Ordering::SeqCst);
        let (entered_send, entered_receive) = std::sync::mpsc::channel();
        let (release_send, release_receive) = std::sync::mpsc::channel();
        let committing = state.clone();
        let writing_dictionary = dictionary.clone();
        let writer = std::thread::spawn(move || {
            committing.commit_if_current(expected, || {
                entered_send.send(()).unwrap();
                release_receive.recv().unwrap();
                tauri::async_runtime::block_on(
                    writing_dictionary.add_auto_if_current("Committed before cancellation", || {
                        committing.generation.load(Ordering::SeqCst) == expected
                    }),
                )
            })
        });
        entered_receive
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        let (started_send, started_receive) = std::sync::mpsc::channel();
        let (done_send, done_receive) = std::sync::mpsc::channel();
        let invalidating = state.clone();
        let canceller = std::thread::spawn(move || {
            started_send.send(()).unwrap();
            invalidating.invalidate();
            done_send.send(()).unwrap();
        });
        started_receive
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        let before_release = done_receive.recv_timeout(std::time::Duration::from_millis(100));
        release_send.send(()).unwrap();
        let inserted = writer.join().unwrap().unwrap().unwrap();
        canceller.join().unwrap();
        assert!(matches!(
            before_release,
            Err(std::sync::mpsc::RecvTimeoutError::Timeout)
        ));
        done_receive
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        assert!(inserted.is_some());
        assert!(state
            .commit_if_current(expected, || {
                tauri::async_runtime::block_on(
                    dictionary.add_auto_if_current("Rejected old retry", || true),
                )
            })
            .is_none());
        let entries = tauri::async_runtime::block_on(dictionary.list()).unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].word, "Committed before cancellation");
        assert!(entries[0].auto);
    }

    #[test]
    fn invalidation_serializes_with_commit_and_rejects_later_old_writes() {
        let state = std::sync::Arc::new(EditLearningState::default());
        let expected = state.generation.load(Ordering::SeqCst);
        let (entered_send, entered_receive) = std::sync::mpsc::channel();
        let (release_send, release_receive) = std::sync::mpsc::channel();
        let committing = state.clone();
        let writer = std::thread::spawn(move || {
            committing.commit_if_current(expected, || {
                entered_send.send(()).unwrap();
                release_receive.recv().unwrap();
            })
        });
        entered_receive.recv().unwrap();
        assert!(state.commit_lock.try_lock().is_err());
        release_send.send(()).unwrap();
        assert!(writer.join().unwrap().is_some());
        state.invalidate();
        let invoked = std::cell::Cell::new(false);
        assert!(state
            .commit_if_current(expected, || invoked.set(true))
            .is_none());
        assert!(!invoked.get());
    }

    #[test]
    fn learning_cannot_continue_after_the_cloud_session_changes() {
        assert!(!learning_session_owner_matches(
            "old-session",
            "new-session"
        ));
        assert!(learning_session_owner_matches(
            "same-session",
            "same-session"
        ));
    }

    #[test]
    fn stale_startup_cannot_replace_a_cancelled_or_newer_tracker() {
        let state = EditLearningState::default();
        let expected = state.generation.load(Ordering::SeqCst);
        state.generation.fetch_add(1, Ordering::SeqCst);
        assert!(state.claim_start(expected).is_none());
        let current = state.generation.load(Ordering::SeqCst);
        assert_eq!(state.claim_start(current), Some(current + 1));
        assert!(state.claim_start(current).is_none());
    }

    #[test]
    fn current_generation_cannot_track_when_learning_is_disabled() {
        assert!(!learning_session_is_current(4, 4, false, false));
        assert!(!learning_session_is_current(4, 5, true, false));
        assert!(!learning_session_is_current(4, 4, true, true));
        assert!(learning_session_is_current(4, 4, true, false));
    }

    #[test]
    fn disabling_learning_invalidates_an_existing_tracker() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        app.manage(EditLearningState::default());
        let state = app.state::<EditLearningState>();
        let before = state.generation.load(Ordering::SeqCst);
        apply_enabled_setting(app.handle(), false);
        assert_ne!(state.generation.load(Ordering::SeqCst), before);
        let disabled = state.generation.load(Ordering::SeqCst);
        apply_enabled_setting(app.handle(), true);
        assert_eq!(state.generation.load(Ordering::SeqCst), disabled);
    }
}

#[derive(Clone, Debug, Serialize)]
pub struct WordAutoAdded {
    pub term: String,
    pub id: i64,
}

#[cfg(any(target_os = "macos", test))]
fn publish_auto_added_if_current<R: tauri::Runtime>(
    state: &EditLearningState,
    generation: u64,
    app: &tauri::AppHandle<R>,
    is_current: impl FnOnce() -> bool,
    term: String,
    id: i64,
) {
    use tauri::Emitter;
    state.commit_if_current(generation, || {
        if is_current() {
            let _ = app.emit(WORD_AUTO_ADDED_EVENT, WordAutoAdded { term, id });
        }
    });
}

/// Stops any running tracker; a new dictation supersedes the previous one (Typeless `stopTrack`).
pub fn cancel(app: &tauri::AppHandle) {
    use tauri::Manager;
    if let Some(state) = app.try_state::<EditLearningState>() {
        state.invalidate();
    }
}

pub fn apply_enabled_setting<R: tauri::Runtime>(app: &tauri::AppHandle<R>, enabled: bool) {
    use tauri::Manager;
    if !enabled {
        if let Some(state) = app.try_state::<EditLearningState>() {
            state.invalidate();
        }
    }
}

pub(crate) fn current_generation(app: &tauri::AppHandle) -> Option<u64> {
    use tauri::Manager;
    app.try_state::<EditLearningState>()
        .map(|state| state.generation.load(Ordering::SeqCst))
}

/// Starts watching the focused field after `inserted` was typed into it.
pub async fn track_after_insert(
    app: &tauri::AppHandle,
    inserted: String,
    operation_aborted: std::sync::Arc<std::sync::atomic::AtomicBool>,
    session_epoch: String,
    expected_generation: Option<u64>,
) {
    #[cfg(target_os = "macos")]
    {
        use tauri::Manager;
        let Some(state) = app.try_state::<EditLearningState>() else {
            return;
        };
        let Some(expected) = expected_generation else {
            return;
        };
        if !learning_enabled(app).await || operation_aborted.load(Ordering::SeqCst) {
            return;
        }
        if !learning_session_epoch(app)
            .is_some_and(|current| learning_session_owner_matches(&session_epoch, &current))
        {
            return;
        }
        let Some(generation) = state.claim_start(expected) else {
            return;
        };
        let app = app.clone();
        let spawned = std::thread::Builder::new()
            .name("edit-learning".to_string())
            .spawn(move || {
                run_tracker(app, generation, inserted, operation_aborted, session_epoch)
            });
        if let Err(error) = spawned {
            tracing::warn!("Edit learning tracker could not start: {error}");
        }
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = (
            app,
            inserted,
            operation_aborted,
            session_epoch,
            expected_generation,
        );
    }
}

#[cfg(target_os = "macos")]
fn run_tracker(
    app: tauri::AppHandle,
    generation: u64,
    inserted: String,
    operation_aborted: std::sync::Arc<std::sync::atomic::AtomicBool>,
    session_epoch: String,
) {
    use std::time::{Duration, Instant};
    use tauri::Manager;

    use analysis::{evaluate_edit, EditSession, PollOutcome};

    // Typeless waits 200 ms for the insertion to settle before reading the field.
    const SETTLE_DELAY: Duration = Duration::from_millis(200);
    const POLL_INTERVAL: Duration = Duration::from_millis(500);

    let is_current = || {
        let enabled = tauri::async_runtime::block_on(learning_enabled(&app));
        app.try_state::<EditLearningState>().is_some_and(|state| {
            learning_session_epoch(&app)
                .is_some_and(|current| learning_session_owner_matches(&session_epoch, &current))
                && learning_session_is_current(
                    generation,
                    state.generation.load(Ordering::SeqCst),
                    enabled,
                    operation_aborted.load(Ordering::SeqCst),
                )
        })
    };

    std::thread::sleep(SETTLE_DELAY);
    if !is_current() {
        return;
    }
    let Some(start) = macos::focused_field() else {
        return;
    };
    let Some(mut session) = EditSession::begin(&start, &inserted, Instant::now()) else {
        tracing::debug!("Edit learning skipped: focused field is not an editable match");
        return;
    };
    tracing::debug!("Edit learning started");
    let edited = loop {
        std::thread::sleep(POLL_INTERVAL);
        if !is_current() {
            return;
        }
        match session.poll(macos::focused_field().as_ref(), Instant::now()) {
            PollOutcome::Continue => {}
            PollOutcome::Finish(edited) => break edited,
            PollOutcome::Abort => {
                tracing::debug!("Edit learning stopped: large modification");
                return;
            }
        }
    };
    let Some(edited) = edited else {
        return;
    };
    let Some(term) = evaluate_edit(session.original(), &edited, session.refined()) else {
        return;
    };
    if !is_current() {
        return;
    }
    let Some(store) = app.try_state::<crate::storage::DictionaryStore>() else {
        return;
    };
    let Some(state) = app.try_state::<EditLearningState>() else {
        return;
    };
    let Some(session_store) = app.try_state::<crate::SessionTokenStore>() else {
        return;
    };
    let Some(config_store) = app.try_state::<crate::storage::ConfigManager>() else {
        return;
    };
    let Some(result) = state.commit_if_current(generation, || {
        config_store.with_current_learning_enabled(|| {
            Ok(with_learning_session_owner(
                &session_store,
                &session_epoch,
                || {
                    tauri::async_runtime::block_on(store.add_auto_if_current(&term, || {
                        learning_session_is_current(
                            generation,
                            state.generation.load(Ordering::SeqCst),
                            true,
                            operation_aborted.load(Ordering::SeqCst),
                        )
                    }))
                },
            ))
        })
    }) else {
        return;
    };
    let result = match result {
        Ok(Some(Some(result))) => result,
        Ok(_) => return,
        Err(_) => return,
    };
    match result {
        Ok(Some(id)) => {
            tracing::info!("Edit learning added a dictionary word");
            use tauri::Emitter;
            let _ = app.emit(DICTIONARY_CHANGED_EVENT, ());
            publish_auto_added_if_current(&state, generation, &app, is_current, term, id);
        }
        Ok(None) => {}
        Err(error) => tracing::debug!("Edit learning did not add the word: {error}"),
    }
}
