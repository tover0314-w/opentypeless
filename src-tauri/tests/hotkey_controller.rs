#[path = "../src/hotkey_controller.rs"]
mod hotkey_controller;
#[path = "../src/shortcut_lease.rs"]
mod shortcut_lease;

use hotkey_controller::{HotkeySupervisor, HotkeySupervisorState};
use std::sync::mpsc;
use std::time::Duration;

#[test]
fn long_hold_cancels_owned_dictation_before_waiting_for_the_command_worker() {
    let source = include_str!("../src/hotkey.rs");
    let dispatcher = source
        .split("fn dispatch_input_actions(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn recording_input_finished")
        .next()
        .unwrap();
    let cancellation = dispatcher
        .split("InputAction::Cancel { session_id } =>")
        .nth(1)
        .unwrap()
        .split("InputAction::ShowGuide")
        .next()
        .unwrap();
    assert!(
        cancellation
            .find("cancel_queued_input(handle, session_id)")
            .unwrap()
            < cancellation.find("RecordingCommand::Cancel").unwrap()
    );
    let owned = source
        .split("fn cancel_queued_input(")
        .nth(1)
        .unwrap()
        .split("async fn stop_queued_ask")
        .next()
        .unwrap();
    assert!(owned.contains(".filter(|(owner, _)| *owner == session_id)"));
    assert!(owned.contains(".abort_owned_input(&operation)"));
    let pipeline = include_str!("../src/pipeline.rs");
    assert!(!pipeline.contains("observe_recording_input_audio"));
    assert!(pipeline.contains(".cancel_if_current(operation, || self.abort_cleanup(true))"));
}

#[test]
fn streaming_worker_waits_for_chunks_with_owned_cancellation() {
    let source = include_str!("../src/pipeline.rs");
    let worker = source
        .split("async fn run_streaming_insert_worker(")
        .nth(1)
        .unwrap()
        .split("fn take_matching_stt_error(")
        .next()
        .unwrap();
    assert!(worker.contains("operation.cancel_receiver()"));
    assert!(worker.contains("receiver.recv()"));
    assert!(worker.contains("crate::retry_audio::cancellable_retry("));
}

#[test]
fn streaming_chunks_and_suffixes_use_the_execution_time_guard() {
    let source = include_str!("../src/pipeline.rs");
    assert!(!source.contains("output::output_stream_chunk("));
    assert!(source.contains("output::output_stream_chunk_with_guard("));
    let worker = source
        .split("async fn run_streaming_insert_worker(")
        .nth(1)
        .unwrap()
        .split("fn take_matching_stt_error(")
        .next()
        .unwrap();
    assert!(worker.contains("target_still_matches_now"));
    assert!(worker.contains("operation.is_cancelled()"));
    assert!(!worker.contains("abort_flag: Arc<AtomicBool>"));
}

#[test]
fn output_recovery_copy_does_not_require_the_original_focus() {
    let source = include_str!("../src/output/mod.rs");
    let output = source
        .split("pub async fn output_with_guard(")
        .nth(1)
        .unwrap()
        .split("pub async fn output_stream_chunk(")
        .next()
        .unwrap();
    assert!(output.contains("GuardedOutput::new(clipboard_copy, guards.copy)"));
    assert!(output.contains("guards.insert"));
    let pipeline = include_str!("../src/pipeline.rs");
    let output = pipeline
        .split("    async fn output_text(")
        .nth(1)
        .unwrap()
        .split("    async fn pre_warm_endpoint(")
        .next()
        .unwrap();
    assert!(output.contains("copy: Arc::new(move || !copy_operation.is_cancelled())"));
}

#[test]
fn execution_target_check_uses_identity_only_source_not_full_context_collection() {
    let cache = include_str!("../src/app_detector/cache.rs");
    let observation = cache
        .split("pub fn observe_target_now(")
        .nth(1)
        .unwrap()
        .split("pub fn restore_target_application(")
        .next()
        .unwrap();
    assert!(observation.contains("self.source.target_identity()"));
    assert!(!observation.contains("self.source.collect()"));
    let macos = include_str!("../src/app_detector/platform/macos.rs");
    let identity = macos
        .split("fn target_identity(")
        .nth(1)
        .unwrap()
        .split("fn collect(")
        .next()
        .unwrap();
    assert!(!identity.contains("Command::new"));
    assert!(!identity.contains("read_browser_host"));
}

#[test]
fn pipeline_and_keyboard_share_an_execution_time_output_guard() {
    let pipeline = include_str!("../src/pipeline.rs");
    let output = pipeline
        .split("    async fn output_text(")
        .nth(1)
        .unwrap()
        .split("    async fn pre_warm_endpoint(")
        .next()
        .unwrap();
    assert!(output.contains("output::output_with_guard("));
    assert!(output.contains("!owned_operation.is_cancelled()"));
    let keyboard = include_str!("../src/output/keyboard.rs");
    assert!(keyboard.contains("is_waiting() &&"));
    assert!(keyboard.contains("guard()"));
}

#[test]
fn pipeline_output_wait_is_owned_by_the_voice_cancellation() {
    let source = include_str!("../src/pipeline.rs");
    let output = source
        .split("    async fn output_text(")
        .nth(1)
        .unwrap()
        .split("    async fn pre_warm_endpoint(")
        .next()
        .unwrap();
    assert!(output.contains("crate::retry_audio::cancellable_retry("));
    assert!(output.contains("operation.cancel_receiver()"));
    assert!(output.contains("Voice operation was cancelled during output"));
}

#[test]
fn native_combo_parent_is_explicit_and_not_inferred_from_binding_text() {
    let source = include_str!("../src/hotkey.rs");
    let input = source
        .split("fn handle_dictation_input(")
        .nth(1)
        .unwrap()
        .split("fn handle_advanced_role_shortcut(")
        .next()
        .unwrap();
    assert!(!input.contains("binding_id.starts_with("));
    assert!(input.contains("parent_binding_id"));
    let native = include_str!("../src/native_hotkey.rs");
    assert!(native.contains("pub parent_trigger: Option<NativeHotkeyTrigger>"));
    let registration = include_str!("../src/commands/misc.rs");
    assert!(registration.contains("event.parent_trigger"));
}

#[test]
fn reused_monitor_releases_retired_handler_after_runtime_lock() {
    let source = include_str!("../src/native_hotkey.rs");
    let reuse = source
        .split("if inner.bindings == bindings")
        .nth(1)
        .unwrap()
        .split("let generation = self")
        .next()
        .unwrap();
    let unlock = reuse.find("drop(inner)").unwrap();
    let retire = reuse.find("drop(previous_handler)").unwrap();
    assert!(unlock < retire);
    assert!(reuse.contains("slot.replace(handler)"));
}

#[test]
fn owned_dictation_completion_is_routed_before_ask_compatibility() {
    let source = include_str!("../src/hotkey.rs");
    let entry = source
        .split("pub(crate) fn handle_hotkey_binding_event_at(")
        .nth(1)
        .unwrap()
        .split("pub fn build_shortcut_handler(")
        .next()
        .unwrap();
    let dictation = entry.rsplit("HotkeyRole::Dictation => {").next().unwrap();
    let ownership = dictation.find("if owned_completion {").unwrap();
    let ask = dictation.find("handle_active_ask_input(").unwrap();
    assert!(ownership < ask);
    let branch = &dictation[ownership..ask];
    assert!(branch.contains("handle_dictation_input("));
    assert!(branch.contains("return handle_dictation_input("));
}

#[test]
fn invalid_configuration_blocks_actions_but_allows_a_corrected_settings_commit() {
    let controller = HotkeySupervisor::default();
    let (lease, generation) = controller.begin_capture_resume(None).unwrap();
    controller.fail_capture_recovery(
        generation,
        lease,
        "invalid binding".into(),
        hotkey_controller::RecoveryFailure::InvalidConfiguration,
        || {},
    );
    assert!(!controller.actions_allowed());
    assert!(!controller.role_actions_allowed("dictation"));
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    assert!(controller.settings_registration_allowed());
    controller.wake_for_settings_change();
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Starting);
}

#[test]
fn legacy_shortcut_updates_defer_installation_during_owned_voice_operations() {
    let source = include_str!("../src/commands/misc.rs");
    for signature in [
        "pub async fn update_hotkey(",
        "pub async fn update_ask_hotkey(",
    ] {
        let command = source
            .split(signature)
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let deferred = command
            .find("crate::hotkey::input_configuration_busy(&app)")
            .unwrap();
        let installation = command.find("wake_for_settings_change()").unwrap();
        assert!(deferred < installation);
        let branch = &command[deferred..installation];
        assert!(branch.contains("validate_hotkey_config(&config.hotkeys)"));
        assert!(branch.contains("commit_legacy_hotkey_candidate("));
        assert!(branch.contains("emit_config_commit("));
        assert!(!branch.contains(".save(&config)"));
        assert!(branch.contains("return Ok(())"));
    }
}

#[test]
fn legacy_shortcut_updates_recheck_recovery_after_acquiring_transaction() {
    let source = include_str!("../src/commands/misc.rs");
    for signature in [
        "pub async fn update_hotkey(",
        "pub async fn update_ask_hotkey(",
    ] {
        let command = source
            .split(signature)
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let acquired = command.find("transaction.0.lock().await").unwrap();
        let gate = command
            .find("hotkey_supervisor.settings_registration_allowed()")
            .unwrap();
        assert!(acquired < gate);
        assert!(gate < command.find("config_state.load().await").unwrap());
    }
}

#[test]
fn settings_change_cannot_bypass_a_pending_release_barrier() {
    let controller = HotkeySupervisor::default();
    let (lease, generation) = controller.begin_capture_resume(None).unwrap();
    controller.fail_capture_recovery(
        generation,
        lease,
        "release unconfirmed".into(),
        hotkey_controller::RecoveryFailure::ReleaseUnconfirmed,
        || {},
    );
    let waiting = controller.snapshot();
    controller.wake_for_settings_change();
    assert_eq!(controller.snapshot(), waiting);
    controller.observe_release_for_recovery(generation, Some(false));
    controller.wake_for_settings_change();
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Starting);
}

#[test]
fn ask_shortcut_decision_and_completion_use_one_owned_state_snapshot() {
    let source = include_str!("../src/hotkey.rs");
    let entry = source
        .split("fn handle_active_ask_input(")
        .nth(1)
        .unwrap()
        .split("pub fn handle_hotkey_role_event(")
        .next()
        .unwrap();
    assert!(entry.contains(".shortcut_snapshot()"));
    let completion = source
        .split("RecordingCommand::ExternalAskStop { snapshot } =>")
        .nth(1)
        .unwrap()
        .split("if let Err(error) = result")
        .next()
        .unwrap();
    assert!(!completion.contains(".recording_session_id()"));
    assert!(completion.contains("snapshot.recording_session_id"));
}

#[test]
fn internal_recording_snapshots_preserve_legacy_credentials_without_renderer_publication() {
    let storage = include_str!("../src/storage/mod.rs");
    let snapshot = storage
        .split("pub(crate) fn cached_input_commit(")
        .nth(1)
        .unwrap()
        .split("#[cfg(target_os = \"macos\")]")
        .next()
        .unwrap();
    assert!(!snapshot.contains("api_key.clear()"));
    assert!(!snapshot.contains(".emit("));
    let hotkey = include_str!("../src/hotkey.rs");
    let refresh = hotkey
        .split("pub(crate) fn refresh_committed_input_configuration(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn input_configuration_busy(")
        .next()
        .unwrap();
    assert!(!refresh.contains("api_key.clear()"));
    assert!(!refresh.contains(".emit("));
    let renderer = include_str!("../src/commands/config.rs");
    let boundary = renderer
        .split("fn config_for_renderer(")
        .nth(1)
        .unwrap()
        .split("#[tauri::command]")
        .next()
        .unwrap();
    for field in ["stt_api_key", "stt_custom_api_key", "llm_api_key"] {
        assert!(boundary.contains(&format!("config.{field}.clear()")));
    }
}

#[test]
fn monitor_health_is_observed_before_active_session_blocks_reinstallation() {
    let source = include_str!("../src/lib.rs");
    let worker = source.split("fn spawn_hotkey_supervisor(").nth(1).unwrap();
    let observation = worker.find(".observed_native_key_pressed()").unwrap();
    let busy = worker.find("hotkey::input_configuration_busy(").unwrap();
    assert!(observation < busy);
    assert!(
        busy < worker
            .find("supervisor.begin_retry_registration_attempt()")
            .unwrap()
    );
}

#[test]
fn stale_physical_snapshot_cannot_reach_a_replacement_input_session() {
    let controller = HotkeySupervisor::default();
    let scanned_generation = controller.snapshot().generation;
    controller.acquire_capture("main", "replacement").unwrap();
    let mut observed = false;
    assert!(!controller.observe_input_for_generation(scanned_generation, || observed = true));
    assert!(!observed);
    let current_generation = controller.snapshot().generation;
    assert!(controller.observe_input_for_generation(current_generation, || observed = true));
    assert!(observed);
    controller.shutdown();
    assert!(
        !controller.observe_input_for_generation(current_generation, || {
            panic!("stopped controller cannot observe input");
        })
    );
}

#[test]
fn stale_release_scan_cannot_recover_a_replacement_editor() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "original").unwrap();
    let scanned_generation = controller.snapshot().generation;
    controller.acquire_capture("main", "replacement").unwrap();
    let (lease, generation) = controller
        .begin_capture_resume(Some("replacement"))
        .unwrap();
    controller.fail_capture_recovery(
        generation,
        lease,
        "release unknown".into(),
        hotkey_controller::RecoveryFailure::ReleaseUnconfirmed,
        || {},
    );
    assert!(controller
        .release_recovery_capture_for(scanned_generation, Some(false))
        .is_none());
    assert_eq!(
        controller
            .release_recovery_capture_for(generation, Some(false))
            .unwrap()
            .capture_id,
        "replacement"
    );
    assert!(!controller.observe_release_for_recovery(scanned_generation, Some(false)));
}

#[test]
fn ask_shortcut_uses_the_captured_configuration_in_the_ordered_worker() {
    let source = include_str!("../src/hotkey.rs");
    let start = source
        .split("async fn start_queued_ask(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn install_recording_command_queue(")
        .next()
        .unwrap();
    assert!(!start.contains("cached_input_config()"));
    assert!(!start.contains("tauri::async_runtime::spawn("));
    assert!(start.contains("config: storage::AppConfig"));
    assert!(start.contains("start_reserved_ask_with_config("));
    let ask = include_str!("../src/commands/ask.rs");
    let owned = ask
        .split("pub(crate) async fn start_reserved_ask_with_config(")
        .nth(1)
        .unwrap()
        .split("pub(crate) async fn stop_owned_ask_dictation(")
        .next()
        .unwrap();
    assert!(!owned.contains("config_state.load().await"));
    assert!(owned.contains("state.owns_startup(generation)"));
}

#[test]
fn configuration_installation_gate_includes_active_ask_operations() {
    let source = include_str!("../src/hotkey.rs");
    let gate = source
        .split("pub(crate) fn input_configuration_busy(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn input_monitor_lost(")
        .next()
        .unwrap();
    assert!(gate.contains("commands::ask::AskDictationState"));
    assert!(gate.contains("ask.is_busy()"));
}

#[test]
fn ask_finalization_uses_the_owned_start_configuration() {
    let source = include_str!("../src/commands/ask.rs");
    let stop = source
        .split("pub async fn stop_ask_dictation(")
        .nth(1)
        .unwrap()
        .split("pub async fn finish_ask_flow(")
        .next()
        .unwrap();
    assert!(!stop.contains("config_state.load().await"));
    assert!(stop.contains("session.config.clone()"));
}

#[test]
fn capture_pause_and_permission_observation_reach_the_input_session() {
    let commands = include_str!("../src/commands/misc.rs");
    let pause = commands
        .split("pub fn pause_hotkey<")
        .nth(1)
        .unwrap()
        .split("pub async fn resume_hotkey(")
        .next()
        .unwrap();
    assert!(pause.contains("crate::hotkey::suspend_input_session(&app)"));
    let source = include_str!("../src/lib.rs");
    let worker = source.split("fn spawn_hotkey_supervisor(").nth(1).unwrap();
    assert!(
        worker.find("hotkey::observe_input_physical_keys(").unwrap()
            < worker.find("transaction.0.try_lock()").unwrap()
    );
}

#[test]
fn native_adapter_does_not_own_recording_guide_decisions() {
    let native = include_str!("../src/native_hotkey.rs");
    assert!(!native.contains("fn claim_pending_shortcut_guide("));
    assert!(!native.contains("guide_visible"));
    assert!(!native.contains("guide_press_id"));
    let registration = include_str!("../src/commands/misc.rs");
    assert!(!registration.contains("event.shortcut_guide"));
}

#[test]
fn managed_capability_notifications_use_their_owned_config_commit() {
    let source = include_str!("../src/commands/stt.rs");
    for signature in [
        "pub async fn cache_managed_stt_capability<",
        "pub async fn clear_managed_stt_capability<",
    ] {
        let command = source
            .split(signature)
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        assert!(command.contains(".try_update_current_config_commit("));
        assert!(
            command.contains("emit_config_commit(&app, state.config_epoch(), revision, &config)")
        );
        assert!(!command.contains(".cached_input_commit()"));
    }
}

#[test]
fn full_config_save_broadcast_uses_the_receipt_owned_revision_and_snapshot() {
    let source = include_str!("../src/commands/config.rs");
    let command = source
        .split("\npub async fn update_config(\n")
        .nth(1)
        .unwrap()
        .split("#[tauri::command]")
        .next()
        .unwrap();
    let broadcast = command
        .find("app.emit(\"config:commit\", commit.clone())")
        .expect("full save must publish its owned committed snapshot");
    assert!(command.find(".save_renderer_config(").unwrap() < broadcast);
    assert!(broadcast < command.find("Ok(ConfigurationSaveReceipt").unwrap());
    assert!(command.contains("revision: saved_revision"));
    assert!(command.contains("config: config_for_renderer(config.clone())"));
    assert!(!command.contains(".cached_input_commit()"));
}

#[test]
fn narrow_config_updates_return_their_owned_commit_without_later_readback() {
    let source = include_str!("../src/storage/mod.rs");
    let method = source
        .split("pub(crate) fn try_update_current_config_commit(")
        .nth(1)
        .expect("narrow update must expose its own commit")
        .split("fn next_input_commit_revision(")
        .next()
        .unwrap();
    assert!(method.contains("update_cached_config_commit("));
    assert!(method.contains("self.update_current_config_commit(update, false)"));
    assert!(method.contains("self.update_current_config_commit(update, true)"));
    assert!(!method.contains("cached_input_commit()"));
}

#[test]
fn config_revision_capacity_is_checked_before_each_persistence_path() {
    let source = include_str!("../src/storage/mod.rs");
    for (start, end, persistence) in [
        (
            "pub async fn save(&self, config:",
            "pub async fn save_renderer_config(",
            "self.persist_config(&config)?",
        ),
        (
            "pub async fn save_renderer_config(",
            "pub(crate) fn try_update_current_config_commit(",
            "self.persist_config(&config)?",
        ),
        (
            "pub(crate) fn try_update_current_config_commit(",
            "fn publish_input_commit(",
            "self.persist_config(config)?",
        ),
    ] {
        let method = source
            .split(start)
            .nth(1)
            .unwrap()
            .split(end)
            .next()
            .unwrap();
        assert!(
            method
                .find("self.next_input_commit_revision()?")
                .expect("revision capacity must be checked")
                < method.find(persistence).unwrap()
        );
    }
}

#[test]
fn all_config_commit_paths_publish_input_only_after_successful_persistence() {
    let source = include_str!("../src/storage/mod.rs");
    let save = source
        .split("pub async fn save(&self, config:")
        .nth(1)
        .unwrap()
        .split("pub async fn save_renderer_config(")
        .next()
        .unwrap();
    assert!(
        save.find("self.persist_config(&config)?").unwrap()
            < save.find("self.publish_input_commit(&config)").unwrap()
    );
    assert!(
        save.find("*cache = Some(config.clone())").unwrap()
            < save.find("self.publish_input_commit(&config)").unwrap()
    );
    let renderer = source
        .split("pub async fn save_renderer_config(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn try_update_current_config_commit(")
        .next()
        .unwrap();
    assert!(
        renderer.find("self.persist_config(&config)?").unwrap()
            < renderer.find("self.publish_input_commit(&config)").unwrap()
    );
    let update = source
        .split("pub(crate) fn try_update_current_config_commit(")
        .nth(1)
        .unwrap()
        .split("fn publish_input_commit(")
        .next()
        .unwrap();
    assert!(
        update.find("self.persist_config(config)?").unwrap()
            < update.find("self.publish_input_commit(config)").unwrap()
    );
}

#[test]
fn monitor_failure_invalidates_delayed_configuration_publication() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.record_binding_results(
        generation,
        vec![hotkey_controller::BindingRegistration {
            role: "dictation".into(),
            index: 0,
            error: None,
        }],
    );
    controller.record_registration_success(generation);
    let mut published = false;
    assert!(controller.publish_registered_configuration(generation, || published = true));
    assert!(published);
    controller.record_monitor_failure(generation, "monitor unavailable".into());
    published = false;
    assert!(!controller.publish_registered_configuration(generation, || published = true));
    assert!(!published);
}

#[test]
fn capture_owner_blocks_delayed_configuration_publication() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.record_binding_results(generation, Vec::new());
    controller.record_registration_success(generation);
    controller.acquire_capture("main", "editor").unwrap();
    assert!(
        !controller.publish_registered_configuration(generation, || {
            panic!("stale publication must not execute");
        })
    );
}

#[test]
fn recording_dispatch_has_no_independent_start_configuration_slot() {
    let source = include_str!("../src/hotkey.rs");
    assert!(!source.contains("start_config"));
    let dispatch = source
        .split("fn dispatch_input_actions(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn recording_input_finished(")
        .next()
        .unwrap();
    assert!(dispatch.contains("session.session_id() != Some(session_id)"));
    assert!(dispatch.contains(".owned_snapshot(session_id, binding.revision)"));
    assert!(dispatch.contains(".cloned()"));
}

#[test]
fn recovery_can_finish_owned_sessions_without_allowing_new_starts() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.record_registration_failure(generation, "monitor lost".into());
    assert!(!controller.role_actions_allowed("dictation"));
    assert!(controller.session_completion_allowed());
    controller.acquire_capture("main", "editor").unwrap();
    assert!(!controller.session_completion_allowed());
}

#[test]
fn explicit_disable_and_shutdown_never_allow_session_completion_events() {
    let controller = HotkeySupervisor::default();
    controller.disable();
    assert!(!controller.session_completion_allowed());
    controller.shutdown();
    assert!(!controller.session_completion_allowed());
}

#[test]
fn production_completion_bypass_requires_both_binding_ownership_and_recovery_permission() {
    let source = include_str!("../src/hotkey.rs");
    let entry = source
        .split("pub(crate) fn handle_hotkey_binding_event_at(")
        .nth(1)
        .unwrap()
        .split("pub fn build_shortcut_handler(")
        .next()
        .unwrap();
    assert!(entry.contains(".owns_session_binding(binding_id, voice_role)"));
    assert!(entry.contains("owned_completion && supervisor.session_completion_allowed()"));
}

#[test]
fn production_recording_uses_the_reducer_and_blocks_new_starts_while_processing() {
    let source = include_str!("../src/hotkey.rs");
    assert!(!source.contains("enum RecordingShortcutAction"));
    assert!(!source.contains("fn auto_recording_shortcut_action("));
    assert!(!source.contains("fn recording_shortcut_action("));
    let input = source
        .split("fn handle_dictation_input(")
        .nth(1)
        .unwrap()
        .split("fn handle_advanced_role_shortcut(")
        .next()
        .unwrap();
    let gate = input.find("pipeline::PipelineState::Idle").unwrap();
    assert!(gate < input.find("session.press_committed(").unwrap());
    assert!(input[..gate].contains("session.session_id().is_none()"));
    assert!(input.contains("emit_capsule_alert(handle, \"previous_pending\")"));
}

#[test]
fn released_editor_permission_recovery_is_checked_before_the_editing_gate() {
    let source = include_str!("../src/lib.rs");
    let worker = source
        .split("fn spawn_hotkey_supervisor(")
        .nth(1)
        .unwrap()
        .split("\n#[derive(")
        .next()
        .unwrap();
    let recovery = worker.find(".permission_recovery_capture()").unwrap();
    assert!(recovery < worker.find(".capture_allows_registration()").unwrap());
    let recovery_path = &worker[recovery..worker.find(".capture_allows_registration()").unwrap()];
    assert!(recovery_path.contains("drop(_transaction)"));
    assert!(recovery_path.contains("Some(lease.capture_id)"));
    assert!(recovery_path.contains("commands::misc::resume_hotkey("));
}

#[test]
fn granted_permission_recovers_only_the_failed_released_editor_identity() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    controller.acquire_capture("main", "editor").unwrap();
    let (lease, generation) = controller.begin_capture_resume(Some("editor")).unwrap();
    assert!(controller.fail_capture_recovery(
        generation,
        lease,
        "access unavailable".into(),
        hotkey_controller::RecoveryFailure::PermissionRequired,
        || {}
    ));
    assert!(controller.permission_recovery_capture().is_none());
    controller.observe_accessibility(true);
    let recovery = controller.permission_recovery_capture().unwrap();
    assert_eq!(recovery.capture_id, "editor");
    assert_eq!(recovery.generation, lease.unwrap());
    assert!(!controller.actions_allowed());
    let (recovered_lease, recovered_generation) = controller
        .begin_capture_resume(Some(&recovery.capture_id))
        .unwrap();
    assert_eq!(recovered_lease, lease);
    assert!(controller.permission_recovery_capture().is_none());
    assert!(!controller.confirm_capture_released(recovered_lease.unwrap(), None));
    assert!(controller.confirm_capture_released(recovered_lease.unwrap(), Some(false)));
    assert!(controller.finish_capture_registration(
        recovered_generation,
        recovered_lease,
        None,
        || {}
    ));
    assert!(controller.actions_allowed());
}

#[test]
fn late_permission_cannot_automatically_release_a_replacement_editor() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    controller.acquire_capture("main", "first").unwrap();
    let (lease, generation) = controller.begin_capture_resume(Some("first")).unwrap();
    assert!(controller.fail_capture_recovery(
        generation,
        lease,
        "access unavailable".into(),
        hotkey_controller::RecoveryFailure::PermissionRequired,
        || {}
    ));
    controller.acquire_capture("main", "replacement").unwrap();
    controller.observe_accessibility(true);
    assert!(controller.permission_recovery_capture().is_none());
    assert!(!controller.actions_allowed());
    assert!(controller.begin_capture_resume(Some("first")).is_err());
}

#[test]
fn granted_permissions_do_not_override_invalid_configuration_or_explicit_pause() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    controller.observe_input_monitoring(false);
    let (lease_generation, generation) = controller.begin_capture_resume(None).unwrap();
    assert!(controller.fail_capture_recovery(
        generation,
        lease_generation,
        "invalid binding".into(),
        hotkey_controller::RecoveryFailure::InvalidConfiguration,
        || {}
    ));
    controller.observe_input_monitoring(true);
    controller.observe_accessibility(true);
    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::InvalidConfiguration
    );
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    controller.disable();
    controller.observe_accessibility(false);
    controller.observe_accessibility(true);
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Disabled);
    assert!(!controller.actions_allowed());
}

#[test]
fn temporary_recovery_failure_remains_retryable_without_granting_actions() {
    let controller = HotkeySupervisor::default();
    let (lease_generation, generation) = controller.begin_capture_resume(None).unwrap();
    assert!(controller.fail_capture_recovery(
        generation,
        lease_generation,
        "platform temporarily unavailable".into(),
        hotkey_controller::RecoveryFailure::Temporary,
        || {}
    ));
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Failed);
    assert!(controller.next_retry_delay().is_some());
    assert!(!controller.actions_allowed());
    assert_eq!(
        controller.begin_retry_registration_attempt(),
        Some(generation)
    );
}

#[test]
fn permission_failure_resumes_after_grant_without_enabling_actions_early() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    let (lease_generation, generation) = controller.begin_capture_resume(None).unwrap();
    assert!(controller.fail_capture_recovery(
        generation,
        lease_generation,
        "permission unavailable".into(),
        hotkey_controller::RecoveryFailure::PermissionRequired,
        || {}
    ));
    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::WaitingPermission
    );
    assert_eq!(controller.next_retry_delay(), None);
    assert!(!controller.actions_allowed());
    controller.observe_accessibility(true);
    assert_eq!(
        controller.begin_retry_registration_attempt(),
        Some(generation)
    );
    assert!(!controller.actions_allowed());
}

#[test]
fn input_monitoring_grant_wakes_release_recovery_without_accessibility_change() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(true);
    controller.observe_input_monitoring(false);
    let (lease_generation, generation) = controller.begin_capture_resume(None).unwrap();
    assert!(controller.fail_capture_recovery(
        generation,
        lease_generation,
        "input monitoring unavailable".into(),
        hotkey_controller::RecoveryFailure::InputMonitoringRequired,
        || {}
    ));
    controller.observe_accessibility(true);
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    controller.observe_input_monitoring(true);
    assert_eq!(
        controller.begin_retry_registration_attempt(),
        Some(generation)
    );
    assert!(!controller.actions_allowed());
}

#[test]
fn accessibility_grant_does_not_wake_a_missing_input_monitoring_permission() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    controller.observe_input_monitoring(false);
    let (lease_generation, generation) = controller.begin_capture_resume(None).unwrap();
    assert!(controller.fail_capture_recovery(
        generation,
        lease_generation,
        "input monitoring unavailable".into(),
        hotkey_controller::RecoveryFailure::InputMonitoringRequired,
        || {}
    ));
    controller.observe_accessibility(true);
    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::WaitingPermission
    );
    assert_eq!(controller.begin_retry_registration_attempt(), None);
}

#[test]
fn final_accessibility_grant_wakes_recovery_when_input_monitoring_arrived_first() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    controller.observe_input_monitoring(false);
    let (lease_generation, generation) = controller.begin_capture_resume(None).unwrap();
    assert!(controller.fail_capture_recovery(
        generation,
        lease_generation,
        "input monitoring unavailable".into(),
        hotkey_controller::RecoveryFailure::InputMonitoringRequired,
        || {}
    ));
    controller.observe_input_monitoring(true);
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    controller.observe_accessibility(true);
    assert_eq!(
        controller.begin_retry_registration_attempt(),
        Some(generation)
    );
    assert!(!controller.actions_allowed());
}

#[test]
fn permission_failure_arriving_after_grant_does_not_wait_for_another_grant() {
    for failure in [
        hotkey_controller::RecoveryFailure::PermissionRequired,
        hotkey_controller::RecoveryFailure::InputMonitoringRequired,
    ] {
        let controller = HotkeySupervisor::default();
        controller.observe_accessibility(false);
        controller.observe_input_monitoring(false);
        let (lease_generation, generation) = controller.begin_capture_resume(None).unwrap();
        controller.observe_accessibility(true);
        controller.observe_input_monitoring(true);
        assert!(controller.fail_capture_recovery(
            generation,
            lease_generation,
            "late permission result".into(),
            failure,
            || {}
        ));
        assert_eq!(controller.snapshot().state, HotkeySupervisorState::Failed);
        assert_eq!(
            controller.begin_retry_registration_attempt(),
            Some(generation)
        );
        assert!(!controller.actions_allowed());
    }
}

#[test]
fn capture_resume_uses_the_same_permission_failure_state_as_startup() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    controller.acquire_capture("main", "editing").unwrap();
    let (lease_generation, generation) = controller.begin_capture_resume(Some("editing")).unwrap();
    assert!(controller.confirm_capture_released(lease_generation.unwrap(), Some(false)));
    assert!(controller.record_native_permission_blocked(generation, true));
    assert!(controller.finish_capture_registration(
        generation,
        lease_generation,
        Some("native access denied".into()),
        || {}
    ));
    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::WaitingPermission
    );
    assert_eq!(controller.snapshot().retry_attempts, 0);
    assert!(!controller.actions_allowed());
}

#[test]
fn denied_permission_waits_without_consuming_registration_retries() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    let generation = controller.begin_registration_attempt();
    assert!(controller.record_native_permission_blocked(generation, true));
    controller.record_registration_failure(generation, "native access denied".into());
    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::WaitingPermission
    );
    assert_eq!(controller.snapshot().retry_attempts, 0);
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    controller.observe_accessibility(false);
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    controller.observe_accessibility(true);
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Failed);
    assert_eq!(
        controller.begin_retry_registration_attempt(),
        Some(generation)
    );
}

#[test]
fn unrelated_registration_failure_is_not_hidden_by_missing_accessibility() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    let generation = controller.begin_registration_attempt();
    controller.record_registration_failure(generation, "invalid ordinary binding".into());
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Failed);
    assert_eq!(controller.snapshot().retry_attempts, 1);
}

#[test]
fn permission_grant_alone_does_not_confirm_release_for_anonymous_resume() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    let (lease_generation, generation) = controller.begin_capture_resume(None).unwrap();
    assert_eq!(lease_generation, None);
    assert!(controller.fail_capture_recovery(
        generation,
        lease_generation,
        "Shortcut release query requires Input Monitoring permission".into(),
        hotkey_controller::RecoveryFailure::ReleaseUnconfirmed,
        || {}
    ));
    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::WaitingRelease
    );
    assert!(controller.capture_allows_registration());

    controller.observe_accessibility(true);

    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::WaitingRelease
    );
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    assert!(!controller.retry_failed_on_focus());
    assert!(!controller.actions_allowed());
}

#[test]
fn released_keys_recover_only_the_original_failed_editor() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "first").unwrap();
    let (lease, generation) = controller.begin_capture_resume(Some("first")).unwrap();
    controller.fail_capture_recovery(
        generation,
        lease,
        "release unknown".into(),
        hotkey_controller::RecoveryFailure::ReleaseUnconfirmed,
        || {},
    );
    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::WaitingRelease
    );
    assert!(controller
        .release_recovery_capture_for(generation, None)
        .is_none());
    assert!(controller
        .release_recovery_capture_for(generation, Some(true))
        .is_none());
    assert_eq!(
        controller
            .release_recovery_capture_for(generation, Some(false))
            .unwrap()
            .capture_id,
        "first"
    );
    controller.acquire_capture("main", "replacement").unwrap();
    assert!(controller
        .release_recovery_capture_for(generation, Some(false))
        .is_none());
}

#[test]
fn anonymous_release_wait_requires_a_fresh_release_before_retry() {
    let controller = HotkeySupervisor::default();
    let (lease, generation) = controller.begin_capture_resume(None).unwrap();
    controller.fail_capture_recovery(
        generation,
        lease,
        "held keys".into(),
        hotkey_controller::RecoveryFailure::ReleaseUnconfirmed,
        || {},
    );
    assert!(!controller.observe_release_for_recovery(generation, None));
    assert!(!controller.observe_release_for_recovery(generation, Some(true)));
    assert!(controller.observe_release_for_recovery(generation, Some(false)));
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Failed);
    assert_eq!(
        controller.begin_retry_registration_attempt(),
        Some(generation)
    );
}

#[test]
fn late_permission_grant_reopens_a_failure_that_exhausted_its_budget() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    for _ in 0..=hotkey_controller::HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT {
        controller.record_registration_failure(generation, "startup failure".into());
    }
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    controller.observe_accessibility(false);
    controller.record_native_permission_blocked(generation, false);
    controller.observe_accessibility(true);
    assert_eq!(
        controller.begin_retry_registration_attempt(),
        Some(generation)
    );
}

#[test]
fn repeated_granted_permission_does_not_reset_a_new_failure_budget() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    let generation = controller.begin_registration_attempt();
    controller.record_registration_failure(generation, "first failure".into());
    controller.observe_accessibility(true);
    controller.begin_retry_registration_attempt().unwrap();
    controller.record_registration_failure(generation, "platform failure".into());
    controller.observe_accessibility(true);
    assert_eq!(controller.snapshot().retry_attempts, 1);
}

#[test]
fn permission_grant_during_capture_is_deferred_until_owner_resumes() {
    let controller = HotkeySupervisor::default();
    controller.observe_accessibility(false);
    controller.acquire_capture("main", "editing").unwrap();
    let snapshot = controller.snapshot();
    controller.observe_accessibility(true);
    assert_eq!(controller.snapshot(), snapshot);
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    controller.shutdown();
    controller.observe_accessibility(false);
    controller.observe_accessibility(true);
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Stopped);
}

#[test]
fn blocked_platform_registration_does_not_block_pause_or_accept_a_stale_result() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    let (entered_sender, entered_receiver) = mpsc::channel();
    let (release_sender, release_receiver) = mpsc::channel();
    let registration_controller = controller.clone();
    let registration = std::thread::spawn(move || {
        registration_controller.run_if_current_generation(generation, || {
            entered_sender.send(()).unwrap();
            release_receiver
                .recv_timeout(Duration::from_secs(2))
                .unwrap();
            "installed"
        })
    });
    entered_receiver
        .recv_timeout(Duration::from_secs(2))
        .unwrap();
    let pause_controller = controller.clone();
    let (paused_sender, paused_receiver) = mpsc::channel();
    let pause = std::thread::spawn(move || {
        let generation = pause_controller.disable();
        paused_sender.send(generation).unwrap();
    });
    let paused_before_release = paused_receiver.recv_timeout(Duration::from_millis(300));
    release_sender.send(()).unwrap();
    let registration_result = registration.join().unwrap();
    pause.join().unwrap();
    assert!(
        paused_before_release.is_ok(),
        "platform registration held the lifecycle state lock"
    );
    assert_eq!(registration_result, None);
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Disabled);
    assert!(
        !controller.record_registration_result(generation, None, || {
            panic!("stale registration published feedback");
        })
    );
}

#[test]
fn extracted_controller_preserves_retry_health_and_feedback_contracts() {
    use hotkey_controller::{
        HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT, HOTKEY_SUPERVISOR_RETRY_DELAY_SECS,
    };
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    assert!(controller.is_current_generation(generation));
    controller.record_registration_success(generation);
    assert!(controller.record_monitor_failure(generation, "monitor gone".into()));
    assert_eq!(
        controller.next_retry_delay(),
        Some(Duration::from_secs(HOTKEY_SUPERVISOR_RETRY_DELAY_SECS))
    );
    for _ in 0..=HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT {
        let attempt = controller.begin_registration_attempt();
        controller.record_registration_failure(attempt, "occupied".into());
    }
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    assert!(controller.retry_failed_on_focus());
    assert_eq!(
        controller.begin_retry_registration_attempt(),
        Some(generation)
    );
    let next = controller.wake_for_settings_change();
    assert!(next > generation);
    let mut feedback = None;
    assert!(controller.record_disabled_with_feedback(next, || feedback = Some("release")));
    assert_eq!(feedback, Some("release"));
    controller.record_disabled(next);
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Disabled);
    assert!(!controller.retry_failed_on_focus());
}

#[test]
fn platform_operation_can_read_lifecycle_without_locking_itself() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    assert_eq!(
        controller.run_if_current_generation(generation, || controller.snapshot().state),
        Some(HotkeySupervisorState::Starting)
    );
}

#[test]
fn queued_platform_operation_rechecks_generation_before_running() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    let (entered_sender, entered_receiver) = mpsc::channel();
    let (release_sender, release_receiver) = mpsc::channel();
    let first_controller = controller.clone();
    let first = std::thread::spawn(move || {
        first_controller.run_if_current_generation(generation, || {
            entered_sender.send(()).unwrap();
            release_receiver
                .recv_timeout(Duration::from_secs(2))
                .unwrap();
        })
    });
    entered_receiver
        .recv_timeout(Duration::from_secs(2))
        .unwrap();
    let queued_controller = controller.clone();
    let queued = std::thread::spawn(move || {
        queued_controller.run_if_current_generation(generation, || {
            panic!("queued stale operation reached the platform");
        })
    });
    controller.disable();
    release_sender.send(()).unwrap();
    assert_eq!(first.join().unwrap(), None);
    assert_eq!(queued.join().unwrap(), None);
}

#[test]
fn lifecycle_invalidated_from_platform_callback_rejects_its_result() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    assert_eq!(
        controller.run_if_current_generation(generation, || {
            controller.disable();
            "superseded"
        }),
        None
    );
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Disabled);
}

#[test]
fn disabled_generation_cannot_start_platform_registration() {
    let controller = HotkeySupervisor::default();
    let generation = controller.disable();
    assert_eq!(
        controller.run_if_current_generation(generation, || "should not install"),
        None
    );
}

#[test]
fn capture_and_recovery_commit_one_lifecycle_state() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.record_registration_success(generation);
    assert!(controller.actions_allowed());
    controller.acquire_capture("main", "first").unwrap();
    assert!(!controller.actions_allowed());
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Disabled);
    let (lease_generation, registration_generation) =
        controller.begin_capture_resume(Some("first")).unwrap();
    let lease_generation = lease_generation.unwrap();
    assert!(!controller.actions_allowed());
    assert!(controller.capture_recovery_is_current(lease_generation));
    assert!(controller.confirm_capture_released(lease_generation, Some(false)));
    assert!(controller.finish_capture_registration(
        registration_generation,
        Some(lease_generation),
        None,
        || {}
    ));
    assert!(controller.actions_allowed());
    assert_eq!(
        controller.snapshot().state,
        HotkeySupervisorState::Installed
    );
}

#[test]
fn old_capture_completion_cannot_publish_installed_during_new_capture() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "first").unwrap();
    let (lease_generation, registration_generation) =
        controller.begin_capture_resume(Some("first")).unwrap();
    assert!(controller.confirm_capture_released(lease_generation.unwrap(), Some(false)));
    controller.acquire_capture("main", "second").unwrap();
    assert!(!controller.finish_capture_registration(
        registration_generation,
        lease_generation,
        None,
        || panic!("old completion published feedback")
    ));
    assert!(!controller.actions_allowed());
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Disabled);
}

#[test]
fn failed_release_and_window_cleanup_keep_the_same_owner_for_retry() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "first").unwrap();
    assert!(!controller.capture_allows_registration());
    assert!(controller.release_capture_window("ask").is_none());
    assert!(controller.release_capture_window("main").is_some());
    let (lease_generation, generation) = controller.begin_capture_resume(Some("first")).unwrap();
    assert!(!controller.confirm_capture_released(lease_generation.unwrap(), None));
    assert!(controller.fail_capture_recovery(
        generation,
        lease_generation,
        "release unknown".into(),
        hotkey_controller::RecoveryFailure::ReleaseUnconfirmed,
        || {}
    ));
    assert!(!controller.actions_allowed());
    assert_eq!(controller.next_retry_delay(), None);
    assert!(controller.begin_capture_resume(Some("other")).is_err());
    let (retry_lease, retry_generation) = controller.begin_capture_resume(Some("first")).unwrap();
    assert_eq!(retry_lease, lease_generation);
    assert!(controller.confirm_capture_released(retry_lease.unwrap(), Some(false)));
    assert!(controller.finish_capture_registration(retry_generation, retry_lease, None, || {}));
    assert!(controller.actions_allowed());
}

#[test]
fn duplicate_capture_request_does_not_invalidate_its_generation() {
    let controller = HotkeySupervisor::default();
    let first = controller.acquire_capture("main", "first").unwrap();
    let generation = controller.snapshot().generation;
    let duplicate = controller.acquire_capture("main", "first").unwrap();
    assert_eq!(duplicate, first);
    assert_eq!(controller.snapshot().generation, generation);
}

#[test]
fn ordinary_registration_feedback_cannot_enable_an_active_capture() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "first").unwrap();
    let generation = controller.begin_registration_attempt();
    assert!(
        !controller.record_registration_result(generation, None, || {
            panic!("ordinary feedback bypassed the capture");
        })
    );
    assert!(!controller.actions_allowed());
}

#[test]
fn platform_registration_cannot_bypass_capture_or_release_confirmation() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "first").unwrap();
    let generation = controller.begin_registration_attempt();
    assert_eq!(
        controller.run_if_current_generation(generation, || "unexpected install"),
        None
    );
    let (lease_generation, generation) = controller.begin_capture_resume(Some("first")).unwrap();
    assert_eq!(
        controller.run_if_current_generation(generation, || "release not confirmed"),
        None
    );
    assert!(controller.confirm_capture_released(lease_generation.unwrap(), Some(false)));
    assert_eq!(
        controller.run_if_current_generation(generation, || "confirmed"),
        Some("confirmed")
    );
}

#[test]
fn every_registration_entry_uses_the_controller_boundary() {
    let source = include_str!("../src/commands/misc.rs");
    let entry = source
        .split("pub(crate) fn register_configured_shortcuts(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn register_configured_shortcuts_for_generation(")
        .next()
        .unwrap();
    assert!(entry.contains("register_configured_shortcuts_for_generation("));
    assert!(!entry.contains("register_configured_shortcuts_guarded("));
}

#[test]
fn capture_install_failure_commits_failure_and_never_allows_actions() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "first").unwrap();
    let (lease_generation, generation) = controller.begin_capture_resume(Some("first")).unwrap();
    assert!(controller.confirm_capture_released(lease_generation.unwrap(), Some(false)));
    let mut feedback = None;
    assert!(controller.finish_capture_registration(
        generation,
        lease_generation,
        Some("install failed".into()),
        || feedback = Some("install failed")
    ));
    assert_eq!(feedback, Some("install failed"));
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Failed);
    assert!(!controller.actions_allowed());
    assert!(!controller.capture_allows_registration());
    assert!(!controller.finish_capture_registration(
        generation,
        lease_generation,
        None,
        || panic!("late success overwrote failure")
    ));
}

#[test]
fn shutdown_rejects_new_capture_registration_and_late_feedback() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.shutdown();
    let stopped = controller.snapshot();
    assert_eq!(stopped.state, HotkeySupervisorState::Stopped);
    assert!(!controller.actions_allowed());
    assert!(!controller.capture_allows_registration());
    assert!(controller.acquire_capture("main", "late").is_err());
    assert!(controller.begin_capture_resume(None).is_err());
    controller.begin_registration_attempt();
    controller.wake_for_settings_change();
    controller.disable();
    assert!(!controller.retry_failed_on_focus());
    assert_eq!(controller.begin_retry_registration_attempt(), None);
    assert_eq!(controller.next_retry_delay(), None);
    assert!(
        !controller.record_registration_result(stopped.generation, None, || {
            panic!("stopped controller accepted feedback");
        })
    );
    assert!(
        !controller.record_disabled_with_feedback(stopped.generation, || {
            panic!("stopped controller accepted disabled feedback");
        })
    );
    assert!(!controller.is_current_generation(generation));
    assert_eq!(controller.snapshot(), stopped);
    controller.shutdown();
    assert_eq!(controller.snapshot(), stopped);
}

#[test]
fn shutdown_invalidates_pending_capture_release_and_completion() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "first").unwrap();
    let (lease_generation, generation) = controller.begin_capture_resume(Some("first")).unwrap();
    controller.shutdown();
    assert!(!controller.capture_recovery_is_current(lease_generation.unwrap()));
    assert!(!controller.confirm_capture_released(lease_generation.unwrap(), Some(false)));
    assert!(
        !controller.finish_capture_registration(generation, lease_generation, None, || {
            panic!("stopped controller completed capture");
        })
    );
    assert!(controller.release_capture_window("main").is_none());
}

#[test]
fn anonymous_completion_cannot_bypass_an_owned_capture() {
    let controller = HotkeySupervisor::default();
    controller.acquire_capture("main", "first").unwrap();
    let generation = controller.begin_registration_attempt();
    assert!(
        !controller.finish_capture_registration(generation, None, None, || {
            panic!("anonymous completion bypassed the capture owner");
        })
    );
}

#[test]
fn rollback_cannot_advance_generation_after_a_new_capture_or_shutdown() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.acquire_capture("main", "first").unwrap();
    let capturing = controller.snapshot();
    assert_eq!(controller.begin_rollback(generation), None);
    assert_eq!(controller.snapshot(), capturing);
    controller.shutdown();
    let stopped = controller.snapshot();
    assert_eq!(controller.begin_rollback(stopped.generation), None);
    assert_eq!(controller.snapshot(), stopped);
}

#[test]
fn rollback_claim_invalidates_the_previous_registration_result() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.record_registration_success(generation);
    let rollback = controller.begin_rollback(generation).unwrap();
    assert!(rollback > generation);
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Starting);
    assert!(
        !controller.record_registration_result(generation, None, || {
            panic!("old result overwrote rollback");
        })
    );
}

#[test]
fn installed_candidate_does_not_allow_actions_until_configuration_is_committed() {
    let controller = HotkeySupervisor::default();
    let generation = controller.wake_for_settings_change();
    assert_eq!(
        controller.run_if_current_generation(generation, || "candidate installed"),
        Some("candidate installed")
    );
    assert!(!controller.actions_allowed());
    assert_eq!(controller.snapshot().state, HotkeySupervisorState::Starting);
    let mut published = false;
    assert!(controller.record_registration_result(generation, None, || {
        published = true;
    }));
    assert!(published);
    assert!(controller.actions_allowed());
}

#[test]
fn partial_registration_allows_only_successful_roles_after_result_commit() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    assert!(controller.record_binding_results(
        generation,
        vec![
            hotkey_controller::BindingRegistration {
                role: "dictation".into(),
                index: 0,
                error: None,
            },
            hotkey_controller::BindingRegistration {
                role: "ask".into(),
                index: 0,
                error: Some("occupied".into()),
            },
        ],
    ));
    assert!(!controller.role_actions_allowed("dictation"));
    controller.record_registration_failure(generation, "occupied".into());
    assert!(controller.role_actions_allowed("dictation"));
    assert!(!controller.role_actions_allowed("ask"));
    assert!(!controller.role_actions_allowed("translate"));
    controller.acquire_capture("main", "edit").unwrap();
    assert!(!controller.role_actions_allowed("dictation"));
}

#[test]
fn stale_binding_results_cannot_overwrite_new_capture() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.acquire_capture("main", "edit").unwrap();
    assert!(!controller.record_binding_results(generation, Vec::new()));
}

#[test]
fn registration_without_new_results_cannot_reuse_previous_partial_success() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.record_binding_results(
        generation,
        vec![hotkey_controller::BindingRegistration {
            role: "dictation".into(),
            index: 0,
            error: None,
        }],
    );
    controller.record_registration_failure(generation, "other role failed".into());
    assert!(controller.role_actions_allowed("dictation"));
    controller.begin_retry_registration_attempt().unwrap();
    controller.record_registration_failure(generation, "configuration unavailable".into());
    assert!(!controller.role_actions_allowed("dictation"));
}

#[test]
fn monitor_failure_invalidates_partial_success_before_recovery() {
    let controller = HotkeySupervisor::default();
    let generation = controller.begin_registration_attempt();
    controller.record_binding_results(
        generation,
        vec![hotkey_controller::BindingRegistration {
            role: "dictation".into(),
            index: 0,
            error: None,
        }],
    );
    controller.record_registration_failure(generation, "ordinary conflict".into());
    assert!(controller.role_actions_allowed("dictation"));
    assert!(controller.record_monitor_failure(generation, "monitor stopped".into()));
    assert!(!controller.role_actions_allowed("dictation"));
}

#[test]
fn permission_observation_precedes_configuration_and_editing_gates() {
    let source = include_str!("../src/lib.rs");
    let supervisor_loop = source
        .split("fn spawn_hotkey_supervisor(")
        .nth(1)
        .unwrap()
        .split("\n#[derive(")
        .next()
        .unwrap();
    let observation = supervisor_loop
        .find("supervisor.observe_accessibility(")
        .unwrap();
    let transaction = supervisor_loop.find("transaction.0.").unwrap();
    let editing_gate = supervisor_loop
        .find(".capture_allows_registration()")
        .unwrap();
    assert!(observation < transaction);
    assert!(observation < editing_gate);
    assert!(supervisor_loop.contains("transaction.0.try_lock()"));
}

#[test]
fn native_consumption_uses_business_acceptance_instead_of_registration_guessing() {
    let source = include_str!("../src/native_hotkey.rs");
    let keyboard = source
        .split("fn handle_key_event(")
        .nth(1)
        .unwrap()
        .split("#[cfg(target_os = \"windows\")]")
        .next()
        .unwrap();
    assert!(keyboard.contains("let accepted = dispatch_native_combo_edge("));
    assert!(keyboard.contains(".key_event_with_acceptance(keycode as u16, pressed, || accepted)"));
    assert!(!keyboard.contains("let matched ="));
    let registration = include_str!("../src/commands/misc.rs");
    assert!(registration.contains("native_runtime.install_with_acceptance("));
    let hotkey = include_str!("../src/hotkey.rs");
    let input = hotkey
        .split("fn handle_dictation_input(")
        .nth(1)
        .unwrap()
        .split("fn handle_advanced_role_shortcut(")
        .next()
        .unwrap();
    assert!(input.contains("accepted && dispatched"));
    assert!(input.contains("let previous_press = session.press_id()"));
}

#[test]
fn physical_native_entries_stage_edges_before_calling_business_handlers() {
    let source = include_str!("../src/native_hotkey.rs");
    for signature in [
        "fn handle_flags_changed(",
        "fn handle_key_event(",
        "fn dispatch_keyboard_event(",
    ] {
        let entry = source
            .split(signature)
            .nth(1)
            .unwrap()
            .split("\n    fn ")
            .next()
            .unwrap();
        assert!(
            entry.contains("dispatch_native_state_update("),
            "{signature}"
        );
        assert!(
            !entry.contains("&context.handler,\n            );"),
            "{signature}"
        );
    }
    let staging = source
        .split("fn dispatch_native_state_update(")
        .nth(1)
        .unwrap()
        .split("struct NativeHandlerSlot")
        .next()
        .unwrap();
    assert!(
        staging.find("update(&mut state, &collector)").unwrap()
            < staging.find("handler.dispatch(event)").unwrap()
    );
}

#[test]
fn scene_validation_failure_is_returned_inside_the_configuration_transaction() {
    let source = include_str!("../src/commands/app_mappings.rs");
    let command = source
        .split("pub async fn set_family_scene_assignment(")
        .nth(1)
        .unwrap()
        .split("#[cfg(test)]")
        .next()
        .unwrap();
    assert!(command.contains(".try_update_current_config_commit("));
    assert!(!command.contains("assignment_result"));
    let storage = include_str!("../src/storage/mod.rs");
    let transaction = storage
        .split("fn try_update_cached_config_commit(")
        .nth(1)
        .unwrap()
        .split("#[cfg(")
        .next()
        .unwrap();
    assert!(
        transaction.find("update(&mut config)?").unwrap()
            < transaction.find("persist(&mut config)?").unwrap()
    );
}

#[test]
fn family_scene_assignment_updates_current_settings_without_replaying_loaded_snapshot() {
    let source = include_str!("../src/commands/app_mappings.rs");
    let command = source
        .split("pub async fn set_family_scene_assignment(")
        .nth(1)
        .unwrap()
        .split("#[cfg(test)]")
        .next()
        .unwrap();
    assert!(command.contains(".try_update_current_config_commit("));
    assert!(command.contains("emit_config_commit("));
    assert!(!command.contains("cached_input_commit()"));
    assert!(!command.contains(".save(&config)"));
    assert!(command.contains("set_family_assignment_value(current,"));
}

#[test]
fn managed_connection_probes_share_bounded_json_response_reading() {
    for (source, signatures) in [
        (
            include_str!("../src/commands/llm.rs"),
            vec!["pub async fn test_llm_connection<R: tauri::Runtime>("],
        ),
        (
            include_str!("../src/commands/stt.rs"),
            vec![
                "pub async fn test_stt_connection<R: tauri::Runtime>(",
                "pub async fn bench_stt_connection<R: tauri::Runtime>(",
            ],
        ),
    ] {
        for signature in signatures {
            let command = source
                .rsplit(signature)
                .next()
                .unwrap()
                .split("#[tauri::command]")
                .next()
                .unwrap();
            assert!(
                command.contains("read_json_response_with_limit(resp, 65536)"),
                "{signature}"
            );
            assert!(!command.contains(".json()"));
        }
    }
}

#[test]
fn configuration_receipt_uses_the_persisted_revision_and_sanitized_config() {
    let config = include_str!("../src/commands/config.rs");
    let update = config
        .split("\npub async fn update_config(\n")
        .nth(1)
        .unwrap()
        .split("#[tauri::command]")
        .next()
        .unwrap();
    assert!(update.contains("let (saved_revision, config) = match state"));
    assert!(update.contains("config: config_for_renderer(config)"));
    assert!(update.contains("saved_input_revision_is_active(&app, saved_revision, &config)"));
    let storage = include_str!("../src/storage/mod.rs");
    let save = storage
        .split("pub async fn save_renderer_config(")
        .nth(1)
        .unwrap()
        .split("pub(crate) fn try_update_current_config_commit(")
        .next()
        .unwrap();
    assert!(
        save.find("self.persist_config(&config)?").unwrap()
            < save
                .find("let revision = self.publish_input_commit(&config)")
                .unwrap()
    );
    assert!(save.contains("Ok((revision, config))"));
}

#[test]
fn settings_installation_reserves_input_before_switching_platform_bindings() {
    let source = include_str!("../src/commands/config.rs");
    let update = source
        .split("\npub async fn update_config(\n")
        .nth(1)
        .unwrap()
        .split("fn ensure_main_session_window(")
        .next()
        .unwrap();
    let reservation = update.find("reserve_input_installation(").unwrap();
    let registration = update
        .find("refresh_hotkey_runtime_with_rollback(")
        .unwrap();
    let persistence = update.find(".save_renderer_config(").unwrap();
    assert!(reservation < registration);
    assert!(registration < persistence);
    assert!(update.contains("installation_reservation.is_some()"));
    let hotkey = include_str!("../src/hotkey.rs");
    let guard = hotkey
        .split("impl Drop for InputInstallationReservation")
        .nth(1)
        .unwrap()
        .split("fn cancel_queued_ask(")
        .next()
        .unwrap();
    assert!(guard.contains(".release_installation(self.identity)"));
    assert!(guard.contains("session.reserve_installation()"));
    let acquisition = guard.find("pipeline.try_voice_start_guard()").unwrap();
    assert!(acquisition < guard.find("session.reserve_installation()").unwrap());
    let dispatch = hotkey
        .split("pub(crate) fn handle_input_binding_event_at(")
        .nth(1)
        .unwrap()
        .split("pub fn handle_shortcut_event(")
        .next()
        .unwrap();
    assert!(
        dispatch
            .find(".block_installation_press(binding_id)")
            .unwrap()
            < dispatch.find("supervisor.role_actions_allowed").unwrap()
    );
    assert!(dispatch.contains("session.owns_installation_blocked_release(binding_id)"));
}

#[test]
fn reconfiguration_does_not_reset_an_independent_press_tracker() {
    let source = include_str!("../src/commands/misc.rs");
    let registration = source
        .split("fn register_configured_shortcuts_guarded(")
        .nth(1)
        .unwrap()
        .split("\nfn effective_hotkey_config(")
        .next()
        .unwrap();
    assert!(!registration.contains("tracker.reset_for_lifecycle()"));
    assert!(registration.contains("register_adapters("));
    assert!(registration.contains("stage_input_installation("));
    let startup = include_str!("../src/lib.rs");
    assert!(!startup.contains("app.manage(hotkey::AutoPressTracker"));
}
