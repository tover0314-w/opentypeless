#[path = "../src/input_command_queue.rs"]
mod input_command_queue;
#[path = "input_session.rs"]
mod input_session_regression;
#[path = "../src/voice_operation.rs"]
mod voice_operation;
use input_session_regression::input_session;
use opentypeless_lib::retry_audio;

use input_command_queue::InputCommandQueue;
use input_session::{InputAction, InputBinding, InputMode, InputSession, SessionPhase, VoiceRole};
use std::sync::{Arc, Mutex};
use tokio::sync::Notify;
use voice_operation::VoiceOperations;

#[test]
fn input_mode_comes_from_the_same_snapshot_as_the_queued_configuration() {
    let source = include_str!("../src/hotkey.rs");
    let input = source
        .split("fn handle_dictation_input(")
        .nth(1)
        .unwrap()
        .split("fn handle_advanced_role_shortcut(")
        .next()
        .unwrap();
    assert!(!input.contains("current_hotkey_mode(handle)"));
    assert!(input.contains("session.press_committed("));
    let dispatch = source
        .split("fn dispatch_input_actions(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn recording_input_finished(")
        .next()
        .unwrap();
    assert!(dispatch.contains(".owned_snapshot(session_id, binding.revision)"));
    assert!(dispatch.contains(".cloned()"));
    assert!(!input.contains("cached_input_config()"));
    assert!(!input.contains("session.press("));
}

#[test]
fn ask_uses_the_same_owned_input_and_ordered_worker_as_dictation() {
    let source = include_str!("../src/hotkey.rs");
    let installation = source
        .split("pub(crate) fn stage_input_installation(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn publish_saved_input_configuration")
        .next()
        .unwrap();
    assert!(installation.contains("HotkeyRole::Ask => VoiceRole::Ask"));
    let worker = source
        .split("pub(crate) fn install_recording_command_queue(")
        .nth(1)
        .unwrap()
        .split("fn dispatch_input_actions(")
        .next()
        .unwrap();
    assert!(worker.contains("start_queued_ask("));
    assert!(worker.contains("stop_queued_ask("));
    assert!(worker.contains("RecordingCommand::Cancel"));
    let input = source
        .split("fn handle_dictation_input(")
        .nth(1)
        .unwrap()
        .split("fn handle_advanced_role_shortcut(")
        .next()
        .unwrap();
    assert!(input.contains("HotkeyRole::Ask => VoiceRole::Ask"));
    assert!(!input.contains("track_auto_press("));
}

#[test]
fn ask_shortcut_can_finish_an_ask_started_outside_the_input_queue() {
    let source = include_str!("../src/hotkey.rs");
    let entry = source
        .split("pub(crate) fn handle_input_binding_event_at(")
        .nth(1)
        .unwrap()
        .split("pub fn handle_shortcut_event(")
        .next()
        .unwrap();
    let ask_entry = entry
        .split("HotkeyRole::Ask => {")
        .nth(1)
        .expect("Ask entry must distinguish an external active Ask");
    assert!(ask_entry.contains("handle_active_ask_input("));
    let compatibility = source
        .split("fn handle_active_ask_input(")
        .nth(1)
        .unwrap()
        .split("pub fn handle_hotkey_role_event(")
        .next()
        .unwrap();
    assert!(compatibility.contains(".shortcut_snapshot()"));
    assert!(compatibility.contains("RecordingCommand::ExternalAskStop"));
    assert!(!compatibility.contains("tauri::async_runtime::spawn("));
}

#[test]
fn cancellation_has_no_separate_configuration_slot_to_invalidate() {
    let source = include_str!("../src/hotkey.rs");
    let invalidation = source
        .split("impl RecordingCommandQueue {")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn shutdown(")
        .next()
        .unwrap();
    assert!(invalidation.contains("session.cancel(session_id)"));
    assert!(!source.contains("start_config"));
}

#[test]
fn rejected_session_claim_stops_initialization_before_reading_session_credentials() {
    let source = include_str!("../src/pipeline.rs");
    let startup = source
        .split("pub(crate) async fn start_with_owned_config(")
        .nth(1)
        .unwrap()
        .split("pub async fn stop(")
        .next()
        .unwrap();
    let rejection = startup
        .find("if !claimed(operation.clone())")
        .expect("startup must reject a cancelled input session");
    assert!(rejection < startup.find("let learning_epoch").unwrap());
    let rejection_body = startup[rejection..]
        .split("let learning_epoch")
        .next()
        .unwrap()
        .split_whitespace()
        .collect::<String>();
    assert!(rejection_body.contains("self.operations.cancel(&operation,"));
    assert!(rejection_body.contains("self.publish_state(PipelineState::Idle)"));
    assert!(rejection_body.contains("returnOk(());"));
}

#[test]
fn queued_start_receives_its_owned_token_without_reading_the_current_recording() {
    let source = include_str!("../src/hotkey.rs");
    let worker = source
        .split("pub(crate) fn install_recording_command_queue(")
        .nth(1)
        .unwrap()
        .split("fn dispatch_input_actions(")
        .next()
        .unwrap();
    assert!(worker.contains(".start_with_owned_config("));
    assert!(!worker.contains(".recording_operation()"));
}

#[test]
fn queued_start_uses_the_captured_configuration_instead_of_loading_latest_settings() {
    let source = include_str!("../src/hotkey.rs");
    let worker = source
        .split("pub(crate) fn install_recording_command_queue(")
        .nth(1)
        .unwrap()
        .split("fn dispatch_input_actions(")
        .next()
        .unwrap();
    assert!(worker.contains(".start_with_owned_config("));
    assert!(!worker.contains(".start_with_options("));
    let pipeline = include_str!("../src/pipeline.rs");
    let startup = pipeline
        .split("pub(crate) async fn start_with_config(")
        .nth(1)
        .expect("missing shared configuration-owned startup")
        .split("pub async fn stop(")
        .next()
        .unwrap();
    assert!(!startup.contains(".load()"));
    assert!(startup.contains("apply_pipeline_start_options(config, options)"));
}

#[tokio::test]
async fn shutdown_releases_the_idle_worker_and_its_captured_resources() {
    let resource = Arc::new(());
    let observer = Arc::downgrade(&resource);
    let queue = InputCommandQueue::new(8, move |_: ()| {
        let resource = resource.clone();
        async move {
            drop(resource);
        }
    });
    queue.flush().await.unwrap();
    queue.shutdown();
    tokio::time::timeout(std::time::Duration::from_secs(1), async {
        while observer.upgrade().is_some() {
            tokio::task::yield_now().await;
        }
    })
    .await
    .expect("stopped queue retained its worker resources");
}

#[tokio::test]
async fn hold_released_during_queued_start_stops_only_its_reserved_session() {
    let session = Arc::new(Mutex::new(InputSession::default()));
    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let stopped = Arc::new(Mutex::new(Vec::new()));
    let worker_session = session.clone();
    let worker_entered = entered.clone();
    let worker_release = release.clone();
    let worker_stopped = stopped.clone();
    let queue = InputCommandQueue::new(8, move |action| {
        let session = worker_session.clone();
        let entered = worker_entered.clone();
        let release = worker_release.clone();
        let stopped = worker_stopped.clone();
        async move {
            if let InputAction::Start { session_id, .. } = action {
                entered.notify_one();
                release.notified().await;
                let actions = session.lock().unwrap().ready(session_id);
                for action in actions {
                    if let InputAction::Stop { session_id } = action {
                        stopped.lock().unwrap().push(session_id);
                        session.lock().unwrap().finish(session_id);
                    }
                }
            }
        }
    });
    let actions = session.lock().unwrap().press(
        InputBinding {
            role: VoiceRole::Dictation,
            binding_id: "Fn".into(),
            mode: InputMode::Hold,
            revision: 1,
        },
        10,
    );
    let session_id = session.lock().unwrap().session_id().unwrap();
    queue.submit(actions.into_iter().next().unwrap()).unwrap();
    entered.notified().await;
    assert!(session.lock().unwrap().release("Fn", 20).is_empty());
    assert_eq!(
        session.lock().unwrap().phase(),
        Some(SessionPhase::Preparing)
    );
    assert!(session.lock().unwrap().commit_bindings(
        2,
        vec![InputBinding {
            role: VoiceRole::Dictation,
            binding_id: "Ctrl+D".into(),
            mode: InputMode::Toggle,
            revision: 2,
        }],
    ));
    session.lock().unwrap().confirm_installation(2);
    release.notify_one();
    queue.flush().await.unwrap();
    assert_eq!(*stopped.lock().unwrap(), [session_id]);
    assert_eq!(session.lock().unwrap().session_id(), None);
    assert_eq!(session.lock().unwrap().active_revision(), Some(2));
}

#[tokio::test]
async fn cancelled_queued_start_cannot_publish_ready_or_stop_a_replacement_session() {
    let session = Arc::new(Mutex::new(InputSession::default()));
    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let effects = Arc::new(Mutex::new(Vec::new()));
    let worker_session = session.clone();
    let worker_entered = entered.clone();
    let worker_release = release.clone();
    let worker_effects = effects.clone();
    let queue = InputCommandQueue::new(8, move |session_id| {
        let session = worker_session.clone();
        let entered = worker_entered.clone();
        let release = worker_release.clone();
        let effects = worker_effects.clone();
        async move {
            entered.notify_one();
            release.notified().await;
            effects
                .lock()
                .unwrap()
                .extend(session.lock().unwrap().ready(session_id));
        }
    });
    let binding = InputBinding {
        role: VoiceRole::Dictation,
        binding_id: "Fn".into(),
        mode: InputMode::Hold,
        revision: 1,
    };
    session.lock().unwrap().press(binding.clone(), 10);
    let original = session.lock().unwrap().session_id().unwrap();
    queue.submit(original).unwrap();
    entered.notified().await;
    session.lock().unwrap().cancel(original);
    session.lock().unwrap().release("Fn", 20);
    session.lock().unwrap().press(binding, 30);
    let replacement = session.lock().unwrap().session_id().unwrap();
    release.notify_one();
    queue.flush().await.unwrap();
    assert!(effects.lock().unwrap().is_empty());
    assert_eq!(session.lock().unwrap().session_id(), Some(replacement));
    assert_eq!(
        session.lock().unwrap().phase(),
        Some(SessionPhase::Preparing)
    );
}

#[tokio::test]
async fn delayed_stop_cannot_claim_a_replacement_voice_operation() {
    let operations = VoiceOperations::default();
    let original = operations.begin_if(|| true).unwrap();
    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let stopped = Arc::new(std::sync::atomic::AtomicBool::new(false));
    let worker_operations = operations.clone();
    let worker_entered = entered.clone();
    let worker_release = release.clone();
    let worker_stopped = stopped.clone();
    let queue = InputCommandQueue::new(8, move |operation| {
        let operations = worker_operations.clone();
        let entered = worker_entered.clone();
        let release = worker_release.clone();
        let stopped = worker_stopped.clone();
        async move {
            entered.notify_one();
            release.notified().await;
            operations.adopt_token_if(&operation, || {
                stopped.store(true, std::sync::atomic::Ordering::SeqCst);
                true
            });
        }
    });
    queue.submit(original).unwrap();
    entered.notified().await;
    operations.abort_current(|| {});
    let replacement = operations.begin_if(|| true).unwrap();
    release.notify_one();
    queue.flush().await.unwrap();
    assert!(!stopped.load(std::sync::atomic::Ordering::SeqCst));
    assert!(!replacement.is_cancelled());
}

#[test]
fn pipeline_cancellation_invalidates_pending_input_before_publishing_idle() {
    let source = include_str!("../src/pipeline.rs");
    let cleanup = source
        .split("fn abort_cleanup(")
        .nth(1)
        .unwrap()
        .split("fn clear_stt_session(")
        .next()
        .unwrap();
    let invalidation = cleanup
        .find("invalidate_pending_recording_commands")
        .unwrap();
    assert!(invalidation < cleanup.find("publish_state(PipelineState::Idle)").unwrap());
}

#[tokio::test]
async fn release_waits_for_the_preceding_start_instead_of_racing_it() {
    let started = Arc::new(Notify::new());
    let released = Arc::new(Notify::new());
    let events = Arc::new(Mutex::new(Vec::new()));
    let worker_started = started.clone();
    let worker_released = released.clone();
    let worker_events = events.clone();
    let queue = InputCommandQueue::new(8, move |command: &'static str| {
        let started = worker_started.clone();
        let released = worker_released.clone();
        let events = worker_events.clone();
        async move {
            if command == "start" {
                events.lock().unwrap().push("starting");
                started.notify_one();
                released.notified().await;
            }
            events.lock().unwrap().push(command);
        }
    });
    queue.submit("start").unwrap();
    started.notified().await;
    queue.submit("stop").unwrap();
    assert_eq!(*events.lock().unwrap(), ["starting"]);
    released.notify_one();
    queue.flush().await.unwrap();
    assert_eq!(*events.lock().unwrap(), ["starting", "start", "stop"]);
}

#[tokio::test]
async fn invalidated_pending_commands_do_not_run_after_a_new_session() {
    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let events = Arc::new(Mutex::new(Vec::new()));
    let worker_entered = entered.clone();
    let worker_release = release.clone();
    let worker_events = events.clone();
    let queue = InputCommandQueue::new(8, move |command: &'static str| {
        let entered = worker_entered.clone();
        let release = worker_release.clone();
        let events = worker_events.clone();
        async move {
            if command == "start" {
                entered.notify_one();
                release.notified().await;
            }
            events.lock().unwrap().push(command);
        }
    });
    queue.submit("start").unwrap();
    entered.notified().await;
    queue.submit("old-stop").unwrap();
    queue.invalidate();
    queue.submit("new-start").unwrap();
    release.notify_one();
    queue.flush().await.unwrap();
    assert_eq!(*events.lock().unwrap(), ["start", "new-start"]);
}

#[tokio::test]
async fn shutdown_rejects_commands_and_drops_pending_work() {
    let entered = Arc::new(Notify::new());
    let release = Arc::new(Notify::new());
    let events = Arc::new(Mutex::new(Vec::new()));
    let worker_entered = entered.clone();
    let worker_release = release.clone();
    let worker_events = events.clone();
    let queue = InputCommandQueue::new(8, move |command: &'static str| {
        let entered = worker_entered.clone();
        let release = worker_release.clone();
        let events = worker_events.clone();
        async move {
            entered.notify_one();
            release.notified().await;
            events.lock().unwrap().push(command);
        }
    });
    queue.submit("start").unwrap();
    entered.notified().await;
    queue.submit("stop").unwrap();
    queue.shutdown();
    assert!(queue.submit("after-exit").is_err());
    release.notify_one();
    queue.flush().await.unwrap();
    assert_eq!(*events.lock().unwrap(), ["start"]);
}
