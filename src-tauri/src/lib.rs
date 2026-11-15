pub mod app_detector;
mod ask_stop_request;
pub mod audio;
pub mod cloud_request;
pub mod commands;
pub mod credentials;
pub mod dictionary_io;
pub mod edit_learning;
pub mod error;
pub mod escape_cancel;
pub mod hotkey;
mod hotkey_controller;
mod hotkey_registration;
#[cfg(test)]
mod http_redirect_tests;
mod input_command_queue;
mod input_configuration;
mod input_session;
pub mod language_variants;
#[cfg(target_os = "linux")]
mod linux_x11;
pub mod llm;
mod monitor_transaction;
mod native_dispatch_gate;
pub mod native_hotkey;
pub mod output;
pub mod paste_last;
pub mod pipeline;
pub mod platform;
pub mod practice;
pub mod recording_deadline;
pub mod retry_audio;
pub mod selection;
mod session_commit;
mod session_recovery;
mod shortcut_capture;
mod shortcut_event_consumption;
mod shortcut_lease;
mod shortcut_release;
pub mod storage;
pub mod stt;
pub mod stt_finalize;
pub mod tray;
pub mod voice_intent;
mod voice_operation;
mod window_state;

pub use hotkey::{default_ask_shortcut, default_shortcut, parse_hotkey};
pub use tray::{refresh_tray, TrayHandle};

use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, Manager};
use tauri_plugin_autostart::MacosLauncher;
use tracing_subscriber::EnvFilter;

use std::sync::{Arc, Mutex};

/// Default cloud API base URL. Override with the `API_BASE_URL` environment variable.
pub const DEFAULT_API_BASE_URL: &str = "https://www.opentypeless.com";
pub const CLIENT_VERSION_HEADER: &str = "X-OpenTypeless-Version";
const HTTP_POOL_IDLE_TIMEOUT_SECS: u64 = 10 * 60;
const HTTP_TCP_KEEPALIVE_SECS: u64 = 60;

fn build_shared_http_client() -> reqwest::Client {
    reqwest::Client::builder()
        .redirect(reqwest::redirect::Policy::custom(|attempt| {
            let Some(original) = attempt.previous().first() else {
                return attempt.stop();
            };
            if attempt.url().origin() != original.origin() {
                return attempt.stop();
            }
            if attempt.previous().len() >= 10 {
                return attempt.error("HTTP redirect limit exceeded");
            }
            attempt.follow()
        }))
        .pool_max_idle_per_host(2)
        .pool_idle_timeout(std::time::Duration::from_secs(HTTP_POOL_IDLE_TIMEOUT_SECS))
        .tcp_keepalive(std::time::Duration::from_secs(HTTP_TCP_KEEPALIVE_SECS))
        .timeout(std::time::Duration::from_secs(30))
        .build()
        .expect("Failed to create HTTP client")
}

/// Read the cloud API base URL from the environment, falling back to the compiled default.
pub fn api_base_url() -> String {
    std::env::var("API_BASE_URL").unwrap_or_else(|_| DEFAULT_API_BASE_URL.to_string())
}

pub fn desktop_client_version() -> &'static str {
    env!("CARGO_PKG_VERSION")
}

pub fn with_desktop_client_version(request: reqwest::RequestBuilder) -> reqwest::RequestBuilder {
    request.header(CLIENT_VERSION_HEADER, desktop_client_version())
}

/// Cached hotkey mode to avoid loading config from disk on every keypress.
/// Updated whenever config is saved.
pub struct HotkeyModeCache(pub Arc<Mutex<String>>);

/// Cached Ask Anything hotkey to route global-shortcut events without disk I/O.
pub struct AskHotkeyCache(pub Arc<Mutex<String>>);

/// Cached registered hotkey roles to route global-shortcut events without disk I/O.
pub struct HotkeyRoleCache(pub Arc<Mutex<hotkey::HotkeyRegistrationPlan>>);

/// Cached close_to_tray setting to avoid blocking I/O in the window close handler.
pub struct CloseToTrayCache(pub Arc<Mutex<bool>>);

/// Last global-hotkey registration error, if startup or settings registration failed.
pub struct HotkeyRegistrationError(pub Arc<Mutex<Option<String>>>);

/// In-memory copy of the system-vault-backed cloud session token.
/// The main renderer may restore it for authenticated API calls; native STT/LLM reads it directly.
pub struct SessionTokenStore(
    pub Arc<Mutex<String>>,
    pub std::sync::atomic::AtomicBool,
    pub Mutex<String>,
);

#[derive(Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct CloudSessionSnapshot {
    pub token: Option<String>,
    pub session_epoch: String,
}

impl SessionTokenStore {
    pub fn snapshot(&self) -> CloudSessionSnapshot {
        let token = self.0.lock().unwrap_or_else(|error| error.into_inner());
        let epoch = self.2.lock().unwrap_or_else(|error| error.into_inner());
        CloudSessionSnapshot {
            token: (!token.trim().is_empty()).then(|| token.clone()),
            session_epoch: epoch.clone(),
        }
    }

    pub fn rotate_epoch(&self) {
        *self.2.lock().unwrap_or_else(|error| error.into_inner()) =
            uuid::Uuid::new_v4().to_string();
    }
}

#[cfg(test)]
fn with_restored_session_token<R, V>(builder: tauri::Builder<R>, vault: &V) -> tauri::Builder<R>
where
    R: tauri::Runtime,
    V: credentials::CredentialSecretReader,
{
    let token = credentials::load_cloud_session_token(vault)
        .unwrap_or_else(|error| {
            tracing::warn!("Failed to restore cloud session from the system vault: {error}");
            None
        })
        .unwrap_or_default();

    builder
        .manage(session_commit::SessionCommitAttempts::default())
        .manage(SessionTokenStore(
            Arc::new(Mutex::new(token)),
            std::sync::atomic::AtomicBool::new(false),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        ))
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct AutoStartSyncOutcome {
    config_auto_start: bool,
    error: Option<String>,
}

/// Shows or hides the Dock icon; the menu-bar icon and windows keep working either way.
pub fn apply_dock_visibility(app: &tauri::AppHandle, visible: bool) {
    #[cfg(target_os = "macos")]
    if let Err(error) = app.set_dock_visibility(visible) {
        tracing::warn!("Failed to set Dock visibility: {error}");
    }
    #[cfg(not(target_os = "macos"))]
    let _ = (app, visible);
}

fn reconcile_auto_start_preference(
    desired_enabled: bool,
    current_enabled: Result<bool, String>,
    apply: impl FnOnce(bool) -> Result<(), String>,
) -> AutoStartSyncOutcome {
    let current_enabled = match current_enabled {
        Ok(enabled) => enabled,
        Err(error) => {
            return AutoStartSyncOutcome {
                config_auto_start: desired_enabled,
                error: Some(format!("Failed to read launch at startup status: {error}")),
            };
        }
    };

    if current_enabled == desired_enabled {
        return AutoStartSyncOutcome {
            config_auto_start: desired_enabled,
            error: None,
        };
    }

    match apply(desired_enabled) {
        Ok(()) => AutoStartSyncOutcome {
            config_auto_start: desired_enabled,
            error: None,
        },
        Err(error) => {
            let action = if desired_enabled { "enable" } else { "disable" };
            AutoStartSyncOutcome {
                config_auto_start: current_enabled,
                error: Some(format!("Failed to {action} launch at startup: {error}")),
            }
        }
    }
}

fn sync_auto_start_preference(
    app: &tauri::AppHandle,
    config_manager: &storage::ConfigManager,
    config: &mut storage::AppConfig,
) {
    use tauri_plugin_autostart::ManagerExt;

    let autolaunch = app.autolaunch();
    let outcome = reconcile_auto_start_preference(
        config.auto_start,
        autolaunch.is_enabled().map_err(|e| e.to_string()),
        |enabled| {
            if enabled {
                autolaunch.enable()
            } else {
                autolaunch.disable()
            }
            .map_err(|e| e.to_string())
        },
    );

    if let Some(error) = &outcome.error {
        tracing::warn!("{error}");
    }

    if config.auto_start != outcome.config_auto_start {
        config.auto_start = outcome.config_auto_start;
        if let Err(error) = tauri::async_runtime::block_on(config_manager.save(config)) {
            tracing::warn!("Failed to persist launch at startup status after sync: {error}");
        }
    }
}

#[cfg(any(target_os = "macos", test))]
fn should_restore_main_window_on_reopen(_has_visible_windows: bool) -> bool {
    true
}

fn show_main_window_at(app: &tauri::AppHandle, hash: &str) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.emit("navigate", hash);
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
        refresh_tray(app);
    }
}

fn restore_main_window(app: &tauri::AppHandle) {
    if let Some(window) = app.get_webview_window("main") {
        let _ = window.unminimize();
        let _ = window.show();
        let _ = window.set_focus();
    }
}

fn should_preserve_auxiliary_window_on_close(label: &str) -> bool {
    label == "ask"
}

fn attach_ask_window_close_handler<R: tauri::Runtime>(
    handle: &tauri::AppHandle<R>,
    ask_window: &tauri::WebviewWindow<R>,
) {
    let handle = handle.clone();
    ask_window.on_window_event(move |event| {
        if let tauri::WindowEvent::CloseRequested { api, .. } = event {
            if should_preserve_auxiliary_window_on_close("ask") {
                api.prevent_close();
                if let Some(w) = handle.get_webview_window("ask") {
                    let _ = w.hide();
                }
            }
        }
    });
}

fn build_ask_window<R: tauri::Runtime>(
    handle: &tauri::AppHandle<R>,
) -> tauri::Result<tauri::WebviewWindow<R>> {
    if let Some(config) = handle
        .config()
        .app
        .windows
        .iter()
        .find(|config| config.label == "ask")
    {
        return tauri::WebviewWindowBuilder::from_config(handle, config)?.build();
    }

    tauri::WebviewWindowBuilder::new(
        handle,
        "ask",
        tauri::WebviewUrl::App("index.html#ask".into()),
    )
    .title("OpenTypeless Ask")
    .inner_size(ASK_WINDOW_WIDTH, 560.0)
    .min_inner_size(480.0, 200.0)
    .resizable(false)
    .decorations(false)
    .transparent(true)
    .shadow(false)
    .always_on_top(true)
    .skip_taskbar(true)
    .center()
    .visible(false)
    .build()
}

pub fn ensure_ask_window<R: tauri::Runtime>(
    handle: &tauri::AppHandle<R>,
) -> tauri::Result<tauri::WebviewWindow<R>> {
    if let Some(window) = handle.get_webview_window("ask") {
        return Ok(window);
    }

    match build_ask_window(handle) {
        Ok(window) => {
            attach_ask_window_close_handler(handle, &window);
            Ok(window)
        }
        Err(error) => {
            if let Some(window) = handle.get_webview_window("ask") {
                Ok(window)
            } else {
                Err(error)
            }
        }
    }
}

pub fn show_ask_popup_window<R: tauri::Runtime>(
    handle: &tauri::AppHandle<R>,
) -> tauri::Result<tauri::WebviewWindow<R>> {
    let window = ensure_ask_window(handle)?;
    let _ = window.unminimize();
    let _ = window.show();
    let _ = window.set_focus();
    Ok(window)
}

#[tauri::command]
async fn show_ask_window(
    app: tauri::AppHandle,
    state: tauri::State<'_, commands::ask::AskDictationState>,
    config_state: tauri::State<'_, storage::ConfigManager>,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<(), String> {
    commands::ask::start_ask_flow(app, state, config_state, token_store, client).await
}

/// The answer card is 744 px wide inside an 800 px transparent window (Typeless layout).
const ASK_WINDOW_WIDTH: f64 = 800.0;
const ASK_WINDOW_MAX_HEIGHT: f64 = 800.0;
const ASK_WINDOW_MIN_HEIGHT: f64 = 200.0;

/// Clamps the card's reported content height to the window bounds and the visible work area.
fn ask_window_height(content_height: f64, work_area_height: Option<f64>) -> f64 {
    let ceiling = work_area_height
        .map(|available| (available - 20.0).max(ASK_WINDOW_MIN_HEIGHT))
        .unwrap_or(ASK_WINDOW_MAX_HEIGHT)
        .min(ASK_WINDOW_MAX_HEIGHT);
    content_height.clamp(ASK_WINDOW_MIN_HEIGHT, ceiling).ceil()
}

/// The answer card reports its rendered height so the window hugs it, bottom-anchored.
#[tauri::command]
fn resize_ask_window(window: tauri::WebviewWindow, height: f64) -> Result<(), String> {
    if window.label() != "ask" || !height.is_finite() {
        return Ok(());
    }
    let monitor = window.current_monitor().ok().flatten();
    let scale = monitor.as_ref().map_or(1.0, |m| m.scale_factor());
    let work_area_height = monitor
        .as_ref()
        .map(|m| f64::from(m.work_area().size.height) / scale);
    let target = ask_window_height(height, work_area_height);
    let current = window
        .inner_size()
        .map(|size| f64::from(size.height) / scale)
        .unwrap_or(0.0);
    if (current - target).abs() <= 2.0 {
        return Ok(());
    }
    let position = window.outer_position().map_err(|e| e.to_string())?;
    let bottom = f64::from(position.y) / scale + current;
    window
        .set_size(tauri::LogicalSize::new(ASK_WINDOW_WIDTH, target))
        .map_err(|e| e.to_string())?;
    window
        .set_position(tauri::LogicalPosition::new(
            f64::from(position.x) / scale,
            (bottom - target).max(0.0),
        ))
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::Result;

    #[test]
    fn stale_hotkey_registration_does_not_clear_new_error_or_emit_recovery() {
        use tauri::Listener;

        let feedback = Arc::new(Mutex::new(Some("new editor failure".to_string())));
        let app = tauri::test::mock_builder()
            .manage(HotkeyRegistrationError(Arc::clone(&feedback)))
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let recovered = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let observed = Arc::clone(&recovered);
        app.listen_any("hotkey:registration-recovered", move |_| {
            observed.store(true, std::sync::atomic::Ordering::SeqCst);
        });
        let supervisor = hotkey::HotkeySupervisor::default();
        let stale_generation = supervisor.snapshot().generation;
        supervisor.disable();
        for result in [Ok(()), Err("old failure".to_string())] {
            assert_eq!(
                record_hotkey_registration_result(
                    app.handle(),
                    &supervisor,
                    stale_generation,
                    result,
                ),
                Err(commands::misc::HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string()),
            );
            assert_eq!(
                feedback.lock().unwrap().as_deref(),
                Some("new editor failure")
            );
            assert!(!recovered.load(std::sync::atomic::Ordering::SeqCst));
        }
    }

    #[test]
    fn history_retry_cancellation_is_limited_to_the_main_window() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![cancel_transcription_retry])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["ask", "capsule", "unknown", "main"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "cancel_transcription_retry".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            assert_eq!(
                result.unwrap_err(),
                serde_json::json!(if label == "main" {
                    "History retry cancellation is unavailable"
                } else {
                    "History retry cancellation is only available in the main window"
                })
            );
        }
    }

    #[test]
    fn take_recovery_is_limited_to_main_and_capsule_windows() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![recover_unfinished_take])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["ask", "unknown", "main", "capsule"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "recover_unfinished_take".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({ "id": 7 })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            assert_eq!(
                result.unwrap_err(),
                serde_json::json!(if matches!(label, "main" | "capsule") {
                    "Take recovery is unavailable"
                } else {
                    "Take recovery is only available in the main or capsule window"
                })
            );
        }
    }

    #[test]
    fn failed_audio_retry_text_is_limited_to_main_and_capsule_windows() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![retry_transcription])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["ask", "unknown", "main", "capsule"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "retry_transcription".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            assert_eq!(
                result.unwrap_err(),
                serde_json::json!(if matches!(label, "main" | "capsule") {
                    "Transcription retry is unavailable"
                } else {
                    "Transcription retry is only available in the main or capsule window"
                })
            );
        }
    }

    #[test]
    fn auxiliary_windows_cannot_request_history_transcription_text() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![retry_history_transcription])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask", "main"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "retry_history_transcription".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({ "id": 7 })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            assert_eq!(
                result.unwrap_err(),
                serde_json::json!(if label == "main" {
                    "History transcription is unavailable"
                } else {
                    "History transcription is only available in the main window"
                })
            );
        }
    }

    #[test]
    fn generated_update_acl_allows_main_and_denies_auxiliary_windows() {
        let capabilities: std::collections::BTreeMap<
            String,
            tauri::utils::acl::capability::Capability,
        > = serde_json::from_str(include_str!("../gen/schemas/capabilities.json")).unwrap();
        for target in [
            tauri::utils::platform::Target::MacOS,
            tauri::utils::platform::Target::Windows,
            tauri::utils::platform::Target::Linux,
        ] {
            let manifests =
                serde_json::from_str(include_str!("../gen/schemas/acl-manifests.json")).unwrap();
            let resolved = tauri::utils::acl::resolved::Resolved::resolve(
                &manifests,
                capabilities.clone(),
                target,
            )
            .unwrap();
            let authority = tauri::ipc::RuntimeAuthority::new(manifests, resolved);
            for command in [
                "plugin:autostart|enable",
                "plugin:autostart|disable",
                "plugin:autostart|is_enabled",
                "plugin:global-shortcut|register",
                "plugin:global-shortcut|register_all",
                "plugin:global-shortcut|unregister",
                "plugin:global-shortcut|unregister_all",
                "plugin:global-shortcut|is_registered",
            ] {
                for window in ["main", "capsule", "ask"] {
                    assert!(
                        authority
                            .resolve_access(command, window, window, &tauri::ipc::Origin::Local)
                            .is_none(),
                        "{command} must use the guarded configuration command"
                    );
                }
            }
            for command in [
                "plugin:updater|check",
                "plugin:updater|download",
                "plugin:updater|install",
                "plugin:updater|download_and_install",
                "plugin:process|exit",
                "plugin:process|restart",
            ] {
                assert!(
                    authority
                        .resolve_access(command, "main", "main", &tauri::ipc::Origin::Local)
                        .is_some(),
                    "{command} must remain available in main"
                );
                for window in ["capsule", "ask"] {
                    assert!(
                        authority
                            .resolve_access(command, window, window, &tauri::ipc::Origin::Local)
                            .is_none(),
                        "{command} must not be available in {window}"
                    );
                }
                for window in ["main", "capsule", "ask"] {
                    assert!(
                        authority
                            .resolve_access(
                                command,
                                window,
                                window,
                                &tauri::ipc::Origin::Remote {
                                    url: "https://untrusted.example.test/".parse().unwrap(),
                                },
                            )
                            .is_none(),
                        "{command} must not be available to remote content in {window}"
                    );
                }
            }
        }
    }

    #[test]
    fn startup_does_not_restore_credentials_before_local_runtime_is_ready() {
        let source = include_str!("lib.rs");
        let setup = source
            .split("        .setup(|app| {")
            .last()
            .unwrap()
            .split("        .invoke_handler(")
            .next()
            .unwrap();
        assert!(!setup.contains("get_protected_session_snapshot"));
        assert!(!setup.contains("SystemCredentialVault"));
    }

    #[test]
    fn startup_requires_valid_settings_before_database_and_background_initialization() {
        let source = include_str!("lib.rs");
        let setup = source
            .split("        .setup(|app| {")
            .last()
            .unwrap()
            .split("        .invoke_handler(")
            .next()
            .unwrap();
        let load = setup.find("block_on(config_manager.load())?").unwrap();
        assert!(load < setup.find("storage::HistoryStore::new(").unwrap());
        assert!(load < setup.find("start_default(").unwrap());
        assert!(load < setup.find("sync_auto_start_preference(").unwrap());
        assert!(!setup.contains("block_on(config_manager.load()).unwrap_or_default()"));
    }

    fn text_mentions(text: &str, keywords: &[&str]) -> bool {
        let normalized = text.to_ascii_lowercase();
        keywords.iter().any(|keyword| normalized.contains(keyword))
    }

    #[test]
    fn dock_reopen_restores_main_window_when_no_windows_are_visible() {
        assert!(should_restore_main_window_on_reopen(false));
    }

    #[test]
    fn dock_reopen_restores_main_window_even_when_capsule_is_visible() {
        assert!(should_restore_main_window_on_reopen(true));
    }

    #[test]
    fn desktop_client_version_header_matches_frontend_contract() {
        assert_eq!(crate::CLIENT_VERSION_HEADER, "X-OpenTypeless-Version");
        assert_eq!(crate::desktop_client_version(), env!("CARGO_PKG_VERSION"));
    }

    struct StaticSessionVault(&'static str);

    impl credentials::CredentialSecretReader for StaticSessionVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            Ok(Some(self.0.to_string()))
        }
    }

    #[test]
    fn cloud_session_state_is_managed_before_setup_and_window_scripts() {
        let app = with_restored_session_token(
            tauri::test::mock_builder(),
            &StaticSessionVault("restored-token"),
        )
        .build(tauri::test::mock_context(tauri::test::noop_assets()))
        .unwrap();

        let state = app.state::<SessionTokenStore>();
        assert_eq!(
            state.0.lock().unwrap_or_else(|e| e.into_inner()).as_str(),
            "restored-token"
        );
    }

    #[test]
    fn shared_http_pool_survives_normal_gaps_between_dictations() {
        const {
            assert!(HTTP_POOL_IDLE_TIMEOUT_SECS >= 10 * 60);
            assert!(HTTP_TCP_KEEPALIVE_SECS <= HTTP_POOL_IDLE_TIMEOUT_SECS);
        }
        let _client = build_shared_http_client();
    }

    #[test]
    fn cli_action_parser_supports_first_and_later_instance_arguments() {
        let toggle = vec!["/usr/bin/opentypeless".to_string(), "toggle".to_string()];
        let ask = vec!["OpenTypeless.exe".to_string(), "ask".to_string()];

        assert_eq!(parse_cli_action(&toggle), Some(CliAction::Toggle));
        assert_eq!(parse_cli_action(&ask), Some(CliAction::Ask));
    }

    #[test]
    fn cli_action_parser_does_not_hijack_deep_links_or_ambiguous_commands() {
        let deep_link = vec![
            "opentypeless".to_string(),
            "opentypeless://auth/callback?mode=toggle".to_string(),
        ];
        let ambiguous = vec![
            "opentypeless".to_string(),
            "toggle".to_string(),
            "ask".to_string(),
        ];

        assert_eq!(parse_cli_action(&deep_link), None);
        assert_eq!(parse_cli_action(&ambiguous), None);
    }

    #[test]
    fn ask_window_close_keeps_popup_available_for_future_results() {
        assert!(should_preserve_auxiliary_window_on_close("ask"));
        assert!(!should_preserve_auxiliary_window_on_close("main"));
    }

    #[test]
    fn ask_window_fallback_uses_ask_route() {
        let url = tauri::WebviewUrl::App("index.html#ask".into());
        match url {
            tauri::WebviewUrl::App(path) => assert_eq!(path.to_string_lossy(), "index.html#ask"),
            _ => panic!("expected app url"),
        }
    }

    #[test]
    fn ask_window_config_uses_floating_note_chrome() {
        let manifest_dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let tauri_config: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(manifest_dir.join("tauri.conf.json")).unwrap(),
        )
        .unwrap();
        let ask = tauri_config["app"]["windows"]
            .as_array()
            .unwrap()
            .iter()
            .find(|window| window["label"].as_str() == Some("ask"))
            .unwrap();

        assert_eq!(ask["decorations"].as_bool(), Some(false));
        assert_eq!(ask["transparent"].as_bool(), Some(true));
        assert_eq!(ask["shadow"].as_bool(), Some(false));
        assert_eq!(ask["resizable"].as_bool(), Some(false));
        assert_eq!(ask["width"].as_f64(), Some(ASK_WINDOW_WIDTH));
        assert_eq!(ask["height"].as_i64(), Some(560));
    }

    #[test]
    fn ask_window_height_clamps_to_card_bounds_and_work_area() {
        assert_eq!(
            ask_window_height(120.0, Some(1000.0)),
            ASK_WINDOW_MIN_HEIGHT
        );
        assert_eq!(ask_window_height(512.4, Some(1000.0)), 513.0);
        assert_eq!(
            ask_window_height(2000.0, Some(1000.0)),
            ASK_WINDOW_MAX_HEIGHT
        );
        assert_eq!(ask_window_height(2000.0, Some(620.0)), 600.0);
        assert_eq!(ask_window_height(700.0, None), 700.0);
    }

    #[test]
    fn linux_launch_env_detects_nvidia_wayland_only_for_matching_tuple() {
        let base = LinuxLaunchEnv {
            session_type: "wayland".to_string(),
            wayland_display: Some("wayland-0".to_string()),
            display: Some(":0".to_string()),
            gdk_backend: None,
            webkit_disable_dmabuf: None,
            webkit_disable_compositing: None,
            webkit_force_sandbox: None,
            libgl_always_software: None,
            glx_vendor: None,
            xdg_runtime_dir_present: true,
            systemd_seats_present: false,
            systemd_users_present: false,
            nvidia_driver_present: false,
            amd_driver_present: true,
            intel_driver_present: false,
        };

        assert!(!base.is_nvidia_wayland());

        let mut nvidia = base.clone();
        nvidia.glx_vendor = Some("nvidia".to_string());
        assert!(nvidia.is_nvidia_wayland());

        let mut x11_nvidia = nvidia;
        x11_nvidia.session_type = "x11".to_string();
        assert!(!x11_nvidia.is_nvidia_wayland());
    }

    #[test]
    fn linux_launch_env_builds_only_requested_safe_workarounds() {
        let base = LinuxLaunchEnv {
            session_type: "wayland".to_string(),
            wayland_display: Some("wayland-0".to_string()),
            display: Some(":0".to_string()),
            gdk_backend: None,
            webkit_disable_dmabuf: None,
            webkit_disable_compositing: None,
            webkit_force_sandbox: None,
            libgl_always_software: None,
            glx_vendor: None,
            xdg_runtime_dir_present: true,
            systemd_seats_present: false,
            systemd_users_present: false,
            nvidia_driver_present: false,
            amd_driver_present: true,
            intel_driver_present: false,
        };

        assert_eq!(
            linux_workaround_plan(&base, false, false, false, false),
            LinuxWorkaroundPlan::default()
        );
        assert_eq!(
            linux_workaround_plan(&base, true, true, true, true),
            LinuxWorkaroundPlan {
                disable_dmabuf: true,
                disable_compositing: true,
                force_software_gl: true,
                force_gdk_x11: true,
            }
        );

        let mut without_x11 = base;
        without_x11.display = None;
        assert!(!linux_workaround_plan(&without_x11, false, false, true, false).force_gdk_x11);
    }

    #[test]
    fn auto_start_sync_uses_actual_state_when_enable_fails() {
        let outcome = reconcile_auto_start_preference(true, Ok(false), |_| {
            Err("Login item failed".to_string())
        });

        assert!(!outcome.config_auto_start);
        assert_eq!(
            outcome.error.as_deref(),
            Some("Failed to enable launch at startup: Login item failed")
        );
    }

    #[test]
    fn auto_start_sync_keeps_preference_when_apply_succeeds() {
        let outcome = reconcile_auto_start_preference(true, Ok(false), |_| Ok(()));

        assert!(outcome.config_auto_start);
        assert_eq!(outcome.error, None);
    }

    #[test]
    fn macos_bundle_merges_info_plist_with_local_speech_permissions() {
        let manifest_dir = std::path::PathBuf::from(env!("CARGO_MANIFEST_DIR"));
        let tauri_config: serde_json::Value = serde_json::from_str(
            &std::fs::read_to_string(manifest_dir.join("tauri.conf.json")).unwrap(),
        )
        .unwrap();

        assert_eq!(
            tauri_config
                .pointer("/bundle/macOS/infoPlist")
                .and_then(serde_json::Value::as_str),
            Some("./Info.plist")
        );

        let info_plist = plist::Value::from_file(manifest_dir.join("Info.plist")).unwrap();
        let info_plist = info_plist.as_dictionary().unwrap();
        let microphone_usage = info_plist
            .get("NSMicrophoneUsageDescription")
            .and_then(plist::Value::as_string)
            .unwrap_or_default();
        let speech_usage = info_plist
            .get("NSSpeechRecognitionUsageDescription")
            .and_then(plist::Value::as_string)
            .unwrap_or_default();

        assert!(text_mentions(
            microphone_usage,
            &["microphone", "voice", "speech"]
        ));
        assert!(text_mentions(
            speech_usage,
            &["speech", "transcrib", "dictation", "voice"]
        ));
    }
}

#[tauri::command]
async fn start_recording(state: tauri::State<'_, pipeline::PipelineHandle>) -> Result<(), String> {
    state.start().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn stop_recording(state: tauri::State<'_, pipeline::PipelineHandle>) -> Result<(), String> {
    state.stop().await.map_err(|e| e.to_string())
}

#[tauri::command]
async fn retry_transcription<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
) -> Result<String, String> {
    if !matches!(window.label(), "main" | "capsule") {
        return Err("Transcription retry is only available in the main or capsule window".into());
    }
    let state = app
        .try_state::<pipeline::PipelineHandle>()
        .ok_or_else(|| "Transcription retry is unavailable".to_string())?;
    state
        .retry_transcription()
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
fn abort_recording(state: tauri::State<'_, pipeline::PipelineHandle>) -> Result<(), String> {
    state.dismiss();
    Ok(())
}

#[tauri::command]
async fn retry_history_transcription<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    id: i64,
) -> Result<String, String> {
    if window.label() != "main" {
        return Err("History transcription is only available in the main window".into());
    }
    let state = app
        .try_state::<pipeline::PipelineHandle>()
        .ok_or_else(|| "History transcription is unavailable".to_string())?;
    state
        .retry_history_transcription(id)
        .await
        .map_err(|error| {
            tracing::warn!(entry_id = id, error = %error, "History retry failed");
            error.to_string()
        })
}

#[tauri::command]
async fn recover_unfinished_take<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    id: i64,
) -> Result<(), String> {
    if !matches!(window.label(), "main" | "capsule") {
        return Err("Take recovery is only available in the main or capsule window".into());
    }
    let state = app
        .try_state::<pipeline::PipelineHandle>()
        .ok_or_else(|| "Take recovery is unavailable".to_string())?;
    state.recover_unfinished_take(id).await.map_err(|error| {
        tracing::warn!(entry_id = id, error = %error, "Recovering unfinished take failed");
        error.to_string()
    })
}

#[tauri::command]
fn cancel_transcription_retry<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
) -> Result<(), String> {
    if window.label() != "main" {
        return Err("History retry cancellation is only available in the main window".into());
    }
    let state = app
        .try_state::<pipeline::PipelineHandle>()
        .ok_or_else(|| "History retry cancellation is unavailable".to_string())?;
    state.cancel_history_retry();
    Ok(())
}

#[cfg(any(target_os = "linux", test))]
#[derive(Debug, Clone, PartialEq, Eq)]
struct LinuxLaunchEnv {
    session_type: String,
    wayland_display: Option<String>,
    display: Option<String>,
    gdk_backend: Option<String>,
    webkit_disable_dmabuf: Option<String>,
    webkit_disable_compositing: Option<String>,
    webkit_force_sandbox: Option<String>,
    libgl_always_software: Option<String>,
    glx_vendor: Option<String>,
    xdg_runtime_dir_present: bool,
    systemd_seats_present: bool,
    systemd_users_present: bool,
    nvidia_driver_present: bool,
    amd_driver_present: bool,
    intel_driver_present: bool,
}

#[cfg(any(target_os = "linux", test))]
#[derive(Debug, Clone, Copy, Default, PartialEq, Eq)]
struct LinuxWorkaroundPlan {
    disable_dmabuf: bool,
    disable_compositing: bool,
    force_software_gl: bool,
    force_gdk_x11: bool,
}

#[cfg(any(target_os = "linux", test))]
fn linux_workaround_plan(
    env: &LinuxLaunchEnv,
    disable_dmabuf: bool,
    disable_compositing: bool,
    force_gdk_x11: bool,
    force_software_gl: bool,
) -> LinuxWorkaroundPlan {
    LinuxWorkaroundPlan {
        disable_dmabuf: env.is_nvidia_wayland() || disable_dmabuf,
        disable_compositing,
        force_software_gl,
        force_gdk_x11: force_gdk_x11 && env.display.is_some(),
    }
}

#[cfg(any(target_os = "linux", test))]
impl LinuxLaunchEnv {
    #[cfg(target_os = "linux")]
    fn current() -> Self {
        Self {
            session_type: crate::platform::current_session_type(),
            wayland_display: std::env::var("WAYLAND_DISPLAY").ok(),
            display: std::env::var("DISPLAY").ok(),
            gdk_backend: std::env::var("GDK_BACKEND").ok(),
            webkit_disable_dmabuf: std::env::var("WEBKIT_DISABLE_DMABUF_RENDERER").ok(),
            webkit_disable_compositing: std::env::var("WEBKIT_DISABLE_COMPOSITING_MODE").ok(),
            webkit_force_sandbox: std::env::var("WEBKIT_FORCE_SANDBOX").ok(),
            libgl_always_software: std::env::var("LIBGL_ALWAYS_SOFTWARE").ok(),
            glx_vendor: std::env::var("__GLX_VENDOR_LIBRARY_NAME").ok(),
            xdg_runtime_dir_present: std::env::var("XDG_RUNTIME_DIR")
                .is_ok_and(|value| !value.trim().is_empty()),
            systemd_seats_present: std::path::Path::new("/run/systemd/seats").exists(),
            systemd_users_present: std::path::Path::new("/run/systemd/users").exists(),
            nvidia_driver_present: std::path::Path::new("/proc/driver/nvidia").exists()
                || linux_drm_vendor_present("0x10de"),
            amd_driver_present: linux_drm_vendor_present("0x1002"),
            intel_driver_present: linux_drm_vendor_present("0x8086"),
        }
    }

    fn is_nvidia_wayland(&self) -> bool {
        self.session_type == "wayland"
            && (self.nvidia_driver_present
                || self
                    .glx_vendor
                    .as_deref()
                    .is_some_and(|value| value.eq_ignore_ascii_case("nvidia")))
    }
}

#[cfg(target_os = "linux")]
fn linux_drm_vendor_present(expected_vendor: &str) -> bool {
    std::fs::read_dir("/sys/class/drm")
        .into_iter()
        .flatten()
        .flatten()
        .any(|entry| {
            std::fs::read_to_string(entry.path().join("device/vendor"))
                .is_ok_and(|vendor| vendor.trim().eq_ignore_ascii_case(expected_vendor))
        })
}

#[cfg(target_os = "linux")]
fn env_flag_enabled(name: &str) -> bool {
    std::env::var(name).is_ok_and(|value| matches!(value.as_str(), "1" | "true" | "TRUE" | "yes"))
}

/// Apply Linux WebKit/GTK environment workarounds before any WebView is created.
fn apply_linux_workarounds() {
    #[cfg(target_os = "linux")]
    {
        let env = LinuxLaunchEnv::current();
        let plan = linux_workaround_plan(
            &env,
            env_flag_enabled("OPENTYPELESS_DISABLE_WEBKIT_DMABUF"),
            env_flag_enabled("OPENTYPELESS_DISABLE_WEBKIT_COMPOSITING"),
            env_flag_enabled("OPENTYPELESS_FORCE_GDK_X11"),
            env_flag_enabled("OPENTYPELESS_FORCE_SOFTWARE_GL"),
        );

        if plan.disable_dmabuf {
            std::env::set_var("WEBKIT_DISABLE_DMABUF_RENDERER", "1");
        }
        if plan.disable_compositing {
            std::env::set_var("WEBKIT_DISABLE_COMPOSITING_MODE", "1");
        }
        if plan.force_software_gl {
            std::env::set_var("LIBGL_ALWAYS_SOFTWARE", "1");
        }
        if plan.force_gdk_x11 {
            std::env::set_var("GDK_BACKEND", "x11");
        }
    }
}

#[cfg(target_os = "linux")]
fn log_linux_launch_diagnostics(xinitthreads_status: linux_x11::XInitThreadsStatus) {
    let env = LinuxLaunchEnv::current();
    tracing::debug!(
        ?xinitthreads_status,
        session_type = %env.session_type,
        wayland_display_present = env.wayland_display.is_some(),
        display_present = env.display.is_some(),
        gdk_backend = ?env.gdk_backend,
        webkit_disable_dmabuf = ?env.webkit_disable_dmabuf,
        webkit_disable_compositing = ?env.webkit_disable_compositing,
        webkit_force_sandbox = ?env.webkit_force_sandbox,
        libgl_always_software = ?env.libgl_always_software,
        glx_vendor = ?env.glx_vendor,
        xdg_runtime_dir_present = env.xdg_runtime_dir_present,
        systemd_seats_present = env.systemd_seats_present,
        systemd_users_present = env.systemd_users_present,
        nvidia_driver_present = env.nvidia_driver_present,
        amd_driver_present = env.amd_driver_present,
        intel_driver_present = env.intel_driver_present,
        "Linux launch diagnostics"
    );
}

fn set_hotkey_registration_error_state<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    message: Option<String>,
) {
    if let Some(state) = app.try_state::<HotkeyRegistrationError>() {
        *state.0.lock().unwrap_or_else(|e| e.into_inner()) = message;
    }
}

fn record_hotkey_registration_result<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    supervisor: &hotkey::HotkeySupervisor,
    generation: u64,
    result: Result<(), String>,
) -> Result<(), String> {
    let error = result.as_ref().err().cloned();
    if !supervisor.record_registration_result(generation, error.clone(), || {
        set_hotkey_registration_error_state(app, error.clone());
        hotkey::publish_saved_input_configuration(app, generation);
    }) {
        return Err(commands::misc::HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
    }

    match result {
        Ok(()) => {
            let _ = app.emit("hotkey:registration-recovered", ());
            Ok(())
        }
        Err(message) => {
            let _ = app.emit("hotkey:registration-failed", message.clone());
            Err(message)
        }
    }
}

fn spawn_hotkey_supervisor(app_handle: tauri::AppHandle) {
    tauri::async_runtime::spawn(async move {
        loop {
            if app_handle
                .state::<hotkey::HotkeySupervisor>()
                .snapshot()
                .state
                == hotkey::HotkeySupervisorState::Stopped
            {
                break;
            }
            let delay = app_handle
                .state::<hotkey::HotkeySupervisor>()
                .next_retry_delay()
                .unwrap_or_else(|| {
                    std::time::Duration::from_secs(hotkey::HOTKEY_SUPERVISOR_RETRY_DELAY_SECS)
                });
            tokio::time::sleep(delay).await;

            let supervisor = app_handle.state::<hotkey::HotkeySupervisor>();
            let scanned_generation = supervisor.snapshot().generation;
            #[cfg(target_os = "macos")]
            let release_snapshot = shortcut_release::physical_key_snapshot().ok();
            #[cfg(not(target_os = "macos"))]
            let release_snapshot: Option<Vec<u16>> = None;
            let physically_pressed = release_snapshot.as_ref().map(|keys| !keys.is_empty());
            #[cfg(target_os = "macos")]
            {
                supervisor.observe_input_monitoring(shortcut_release::input_monitoring_trusted());
                supervisor.observe_accessibility(pipeline::is_accessibility_trusted());
                supervisor.observe_input_for_generation(scanned_generation, || {
                    hotkey::observe_input_physical_keys(&app_handle, release_snapshot);
                });
            }
            supervisor.observe_release_for_recovery(scanned_generation, physically_pressed);
            let transaction = app_handle.state::<hotkey::HotkeyConfigTransaction>();
            let Ok(_transaction) = transaction.0.try_lock() else {
                continue;
            };
            if let Some(lease) = supervisor.permission_recovery_capture().or_else(|| {
                supervisor.release_recovery_capture_for(scanned_generation, physically_pressed)
            }) {
                drop(_transaction);
                if let Some(window) = app_handle.get_webview_window("main") {
                    let result = commands::misc::resume_hotkey(
                        window.as_ref().window(),
                        app_handle.clone(),
                        app_handle.state::<storage::ConfigManager>(),
                        app_handle.state::<HotkeyRegistrationError>(),
                        app_handle.state::<hotkey::HotkeySupervisor>(),
                        Some(lease.capture_id),
                    )
                    .await;
                    if let Err(error) = result {
                        tracing::warn!("Permission recovery did not complete: {error}");
                    }
                }
                continue;
            }
            if !app_handle
                .state::<hotkey::HotkeySupervisor>()
                .capture_allows_registration()
            {
                continue;
            }
            if supervisor.snapshot().state == hotkey::HotkeySupervisorState::WaitingRelease {
                continue;
            }
            #[cfg(target_os = "macos")]
            {
                let snapshot = supervisor.snapshot();
                let expects_native_monitor = !app_handle
                    .state::<HotkeyRoleCache>()
                    .0
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .native
                    .is_empty();
                if matches!(
                    snapshot.state,
                    hotkey::HotkeySupervisorState::Installed
                        | hotkey::HotkeySupervisorState::Failed
                ) && expects_native_monitor
                {
                    let observation = app_handle
                        .state::<native_hotkey::NativeHotkeyRuntime>()
                        .observed_native_key_pressed();
                    let failure = match observation {
                        Some(Ok(_)) => None,
                        Some(Err(error)) => Some(error),
                        None => Some("Native shortcut monitor is missing".to_string()),
                    };
                    if let Some(error) = failure {
                        if supervisor.record_monitor_failure(snapshot.generation, error) {
                            hotkey::input_monitor_lost(&app_handle);
                            tracing::warn!(
                                "Native shortcut monitor became unavailable; scheduling recovery"
                            );
                        }
                    }
                }
            }
            if hotkey::input_configuration_busy(&app_handle) {
                continue;
            }
            if let Ok(config) = app_handle
                .state::<storage::ConfigManager>()
                .cached_input_config()
            {
                if let Some(generation) =
                    hotkey::pending_input_configuration_publication(&app_handle, &config)
                {
                    if supervisor.publish_registered_configuration(generation, || {
                        hotkey::publish_saved_input_configuration(&app_handle, generation);
                    }) {
                        continue;
                    }
                }
                if hotkey::input_configuration_needs_installation(&app_handle, &config) {
                    let Some(_installation_reservation) =
                        hotkey::reserve_input_installation(&app_handle)
                    else {
                        continue;
                    };
                    let generation = supervisor.wake_for_settings_change();
                    let result = commands::misc::register_configured_shortcuts_for_generation(
                        &app_handle,
                        &config,
                        &supervisor,
                        generation,
                    );
                    let _ = record_hotkey_registration_result(
                        &app_handle,
                        &supervisor,
                        generation,
                        result,
                    );
                    continue;
                }
            }
            let Some(_installation_reservation) = hotkey::reserve_input_installation(&app_handle)
            else {
                continue;
            };
            let Some(generation) = supervisor.begin_retry_registration_attempt() else {
                continue;
            };

            let config = match app_handle.state::<storage::ConfigManager>().load().await {
                Ok(config) => config,
                Err(error) => {
                    let message = format!("Failed to load hotkey config for retry: {error}");
                    let _ = record_hotkey_registration_result(
                        &app_handle,
                        &supervisor,
                        generation,
                        Err(message),
                    );
                    continue;
                }
            };

            if !supervisor.is_current_generation(generation) {
                continue;
            }

            let result = commands::misc::register_configured_shortcuts_for_generation(
                &app_handle,
                &config,
                &supervisor,
                generation,
            );
            if let Err(message) =
                record_hotkey_registration_result(&app_handle, &supervisor, generation, result)
            {
                tracing::warn!("Hotkey supervisor retry failed: {message}");
            } else {
                tracing::info!("Hotkey supervisor recovered global shortcut registration");
            }
        }
    });
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum CliAction {
    Toggle,
    Ask,
}

fn parse_cli_action(args: &[String]) -> Option<CliAction> {
    let mut actions = args.iter().filter_map(|argument| match argument.as_str() {
        "toggle" => Some(CliAction::Toggle),
        "ask" => Some(CliAction::Ask),
        _ => None,
    });
    let action = actions.next()?;
    actions.next().is_none().then_some(action)
}

fn dispatch_cli_action(app: &tauri::AppHandle, action: CliAction) {
    match action {
        CliAction::Toggle => {
            let app_handle = app.clone();
            tauri::async_runtime::spawn(async move {
                let Some(pipeline) = app_handle.try_state::<pipeline::PipelineHandle>() else {
                    tracing::warn!("Ignoring CLI toggle before the recording pipeline is ready");
                    return;
                };
                let result = if pipeline.current_state() == pipeline::PipelineState::Idle {
                    pipeline.start().await
                } else {
                    pipeline.stop().await
                };
                if let Err(error) = result {
                    tracing::error!("CLI toggle failed: {error}");
                }
            });
        }
        CliAction::Ask => {
            let app_handle = app.clone();
            tauri::async_runtime::spawn(async move {
                if app_handle
                    .try_state::<commands::ask::AskDictationState>()
                    .is_none()
                {
                    tracing::warn!("Ignoring CLI ask before Ask Anything is ready");
                    return;
                }
                let ask_state = app_handle.state::<commands::ask::AskDictationState>();
                let config_state = app_handle.state::<storage::ConfigManager>();
                let token_store = app_handle.state::<SessionTokenStore>();
                let client = app_handle.state::<reqwest::Client>();
                if let Err(error) = commands::ask::start_ask_flow(
                    app_handle.clone(),
                    ask_state,
                    config_state,
                    token_store,
                    client,
                )
                .await
                {
                    if error != commands::ask::ASK_CANCELLED {
                        tracing::error!("CLI ask failed");
                    }
                }
            });
        }
    }
}

pub fn run() {
    #[cfg(target_os = "linux")]
    let xinitthreads_status = linux_x11::init_xlib_threads();

    apply_linux_workarounds();

    tracing_subscriber::fmt()
        .with_env_filter(
            EnvFilter::from_default_env().add_directive(
                "opentypeless=debug"
                    .parse()
                    .expect("static directive is valid"),
            ),
        )
        .init();

    #[cfg(target_os = "linux")]
    log_linux_launch_diagnostics(xinitthreads_status);

    #[cfg(target_os = "linux")]
    match xinitthreads_status {
        linux_x11::XInitThreadsStatus::Enabled => {
            tracing::debug!("Initialized Xlib thread support with XInitThreads");
        }
        linux_x11::XInitThreadsStatus::Unavailable => {
            tracing::debug!("libX11 unavailable; skipping XInitThreads");
        }
        linux_x11::XInitThreadsStatus::Failed => {
            tracing::warn!("XInitThreads returned failure; Xlib thread support may be unavailable");
        }
    }

    // Builder-managed state exists before Tauri creates configured webviews.
    // Registering this in `.setup(...)` races the main renderer's first invoke on WebView2.
    let builder = tauri::Builder::default()
        .manage(session_commit::SessionCommitAttempts::default())
        .manage(SessionTokenStore(
            Arc::new(Mutex::new(String::new())),
            std::sync::atomic::AtomicBool::new(false),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        ));

    builder
        .plugin(tauri_plugin_store::Builder::default().build())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_opener::init())
        .plugin(tauri_plugin_dialog::init())
        .plugin(tauri_plugin_single_instance::init(|app, args, _cwd| {
            if let Some(action) = parse_cli_action(&args) {
                dispatch_cli_action(app, action);
                return;
            }
            // Deep-link URL forwarding is handled automatically by the
            // "deep-link" feature of single-instance plugin.
            // Just focus the main window so the user sees the result.
            restore_main_window(app);
        }))
        .plugin(tauri_plugin_deep_link::init())
        .setup(|app| {
            // Open devtools only when the "devtools" feature is explicitly enabled
            #[cfg(feature = "devtools")]
            {
                if let Some(window) = app.get_webview_window("main") {
                    window.open_devtools();
                }
                if let Some(window) = app.get_webview_window("capsule") {
                    window.open_devtools();
                }
                if let Some(window) = app.get_webview_window("ask") {
                    window.open_devtools();
                }
            }

            let app_handle = app.handle().clone();

            // Initialize data directory and database
            let data_dir = app.path().app_data_dir()?;
            std::fs::create_dir_all(&data_dir)?;
            let db_path = data_dir.join("opentypeless.db");

            // Initialize stores
            let config_manager = storage::ConfigManager::new(app_handle.clone());
            let mut initial_config = tauri::async_runtime::block_on(config_manager.load())?;
            let history_store = storage::HistoryStore::new(db_path.clone())
                .map_err(|e| anyhow::anyhow!("Failed to init history store: {}", e))?;
            let dictionary_store = storage::DictionaryStore::new(db_path)
                .map_err(|e| anyhow::anyhow!("Failed to init dictionary store: {}", e))?;

            let shared_client = build_shared_http_client();

            let app_registry = app_detector::registry::AppRegistry::builtin()
                .map_err(|error| anyhow::anyhow!("Failed to init app registry: {error}"))?;
            let app_mapping_store = app_detector::user_mappings::UserAppMappingStore::new(
                app_handle.clone(),
                app_registry,
            );
            let context_detector =
                app_detector::ContextDetectorHandle::start_default(app_mapping_store.clone());
            let pipeline_handle = pipeline::PipelineHandle::new(
                app_handle.clone(),
                shared_client.clone(),
                context_detector.clone(),
            );

            // Load initial config to get hotkey
            sync_auto_start_preference(&app_handle, &config_manager, &mut initial_config);
            app.manage(config_manager);
            app.manage(history_store);
            app.manage(audio::preview::MicrophonePreview::default());
            app.manage(practice::PracticeState::default());
            app.manage(dictionary_store);
            app.manage(edit_learning::EditLearningState::default());
            app.manage(app_mapping_store);
            app.manage(shared_client);
            app.manage(context_detector);
            app.manage(pipeline_handle);
            app.manage(commands::ask::AskDictationState::default());
            app.manage(HotkeyModeCache(Arc::new(Mutex::new(
                initial_config.hotkey_mode.clone(),
            ))));
            hotkey::install_recording_command_queue(&app_handle);
            app.manage(audio::output_mute::BackgroundMute::default());
            audio::output_mute::restore_after_unclean_exit(&app_handle);
            app.manage(AskHotkeyCache(Arc::new(Mutex::new(
                initial_config.ask_hotkey.clone(),
            ))));
            app.manage(HotkeyRoleCache(Arc::new(Mutex::new(
                hotkey::hotkey_registration_plan_from_config(&initial_config.hotkeys)
                    .unwrap_or_default(),
            ))));
            app.manage(native_hotkey::NativeHotkeyRuntime::default());
            let hotkey_registration_error = Arc::new(Mutex::new(None));
            app.manage(HotkeyRegistrationError(hotkey_registration_error.clone()));
            let hotkey_supervisor = hotkey::HotkeySupervisor::default();
            #[cfg(target_os = "macos")]
            hotkey_supervisor.observe_accessibility(pipeline::is_accessibility_trusted());
            app.manage(hotkey_supervisor.clone());
            app.manage(hotkey::HotkeyConfigTransaction::default());
            app.manage(CloseToTrayCache(Arc::new(Mutex::new(
                initial_config.close_to_tray,
            ))));
            apply_dock_visibility(app.handle(), initial_config.show_in_dock);
            escape_cancel::install(&app_handle);
            // Register global shortcut from config
            let handler = hotkey::build_shortcut_handler(app_handle.clone());
            app.handle().plugin(
                tauri_plugin_global_shortcut::Builder::new()
                    .with_handler(handler)
                    .build(),
            )?;
            let generation = hotkey_supervisor.begin_registration_attempt();
            if let Err(message) = record_hotkey_registration_result(
                &app_handle,
                &hotkey_supervisor,
                generation,
                commands::misc::register_configured_shortcuts(&app_handle, &initial_config),
            ) {
                tracing::warn!("Initial global shortcut registration failed: {message}");
            }
            spawn_hotkey_supervisor(app_handle.clone());

            // System tray
            let tray_menu =
                tray::build_tray_menu(&app_handle, false, initial_config.capsule_auto_hide)
                    .map_err(|e| anyhow::anyhow!("Failed to build tray menu: {}", e))?;

            let tray = TrayIconBuilder::new()
                .icon(
                    app.default_window_icon()
                        .expect("default window icon missing")
                        .clone(),
                )
                .menu(&tray_menu)
                .tooltip("OpenTypeless")
                .on_menu_event(move |app, event| match event.id.as_ref() {
                    "quit" => {
                        app.exit(0);
                    }
                    "open_home" => show_main_window_at(app, "#/"),
                    "add_word" => show_main_window_at(app, "#/dictionary"),
                    "record" => {
                        let handle = app.clone();
                        tauri::async_runtime::spawn(async move {
                            let pipeline = handle.state::<pipeline::PipelineHandle>();
                            if pipeline.current_state() == pipeline::PipelineState::Idle {
                                if let Err(e) = pipeline.start().await {
                                    tracing::error!("Tray start recording failed: {}", e);
                                }
                            } else if pipeline.current_state() == pipeline::PipelineState::Recording
                            {
                                if let Err(e) = pipeline.stop().await {
                                    tracing::error!("Tray stop recording failed: {}", e);
                                }
                            }
                        });
                    }
                    "toggle_capsule_auto_hide" => {
                        let handle = app.clone();
                        tauri::async_runtime::spawn(async move {
                            let config_state = handle.state::<storage::ConfigManager>();
                            let enabled = config_state
                                .load()
                                .await
                                .map(|config| !config.capsule_auto_hide)
                                .unwrap_or(true);
                            if let Err(e) = commands::config::save_capsule_auto_hide(
                                &handle,
                                &config_state,
                                enabled,
                            )
                            .await
                            {
                                tracing::error!("Tray capsule visibility toggle failed: {}", e);
                            }
                        });
                    }
                    "settings" => show_main_window_at(app, "#/settings?pane=general"),
                    "history" => show_main_window_at(app, "#/history"),
                    "account" => show_main_window_at(app, "#/account"),
                    "check_updates" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.emit("tray:check-updates", ());
                            let _ = window.unminimize();
                            let _ = window.show();
                            let _ = window.set_focus();
                            refresh_tray(app);
                        }
                    }
                    "about" => show_main_window_at(app, "#/settings?pane=about"),
                    id => {
                        if let Some(device) = tray::microphone_selection_from_menu_id(id) {
                            let handle = app.clone();
                            tauri::async_runtime::spawn(async move {
                                let config_state = handle.state::<storage::ConfigManager>();
                                if let Err(e) = commands::config::save_microphone_device(
                                    &handle,
                                    &config_state,
                                    device,
                                )
                                .await
                                {
                                    tracing::error!("Tray microphone selection failed: {}", e);
                                }
                            });
                        }
                    }
                })
                .on_tray_icon_event(|tray, event| {
                    if matches!(event, TrayIconEvent::Enter { .. }) {
                        tray::refresh_microphones(tray.app_handle());
                    }
                    let should_show = matches!(
                        event,
                        TrayIconEvent::Click {
                            button: MouseButton::Left,
                            button_state: MouseButtonState::Up,
                            ..
                        } | TrayIconEvent::DoubleClick {
                            button: MouseButton::Left,
                            ..
                        }
                    );
                    if should_show {
                        let app = tray.app_handle();
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.show();
                            let _ = window.set_focus();
                            refresh_tray(app);
                        }
                    }
                })
                .build(app)?;

            app.manage(TrayHandle {
                tray: Mutex::new(tray),
            });
            tray::refresh_microphones(&app_handle);

            // Close-to-tray: intercept window close
            if let Some(main_window) = app.get_webview_window("main") {
                window_state::restore_and_track(&main_window);
                let handle = app.handle().clone();
                main_window.on_window_event(move |event| {
                    practice::cancel_for_window_event(&handle, "main", event);
                    if let Some(lease) =
                        shortcut_capture::stop_for_window_event(&handle, "main", event)
                    {
                        let recovery_handle = handle.clone();
                        tauri::async_runtime::spawn(async move {
                            let Some(window) = recovery_handle.get_webview_window("main") else {
                                return;
                            };
                            let result = commands::misc::resume_hotkey(
                                window.as_ref().window(),
                                recovery_handle.clone(),
                                recovery_handle.state::<storage::ConfigManager>(),
                                recovery_handle.state::<HotkeyRegistrationError>(),
                                recovery_handle.state::<hotkey::HotkeySupervisor>(),
                                Some(lease.capture_id),
                            )
                            .await;
                            if let Err(error) = result {
                                tracing::warn!(
                                    "Window shortcut recovery did not complete: {error}"
                                );
                            }
                        });
                    }
                    if matches!(event, tauri::WindowEvent::Focused(true)) {
                        handle
                            .state::<hotkey::HotkeySupervisor>()
                            .retry_failed_on_focus();
                    }
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        window_state::save_main_window_state(&handle);
                        let close_to_tray = *handle
                            .state::<CloseToTrayCache>()
                            .0
                            .lock()
                            .unwrap_or_else(|e| e.into_inner());
                        if close_to_tray {
                            api.prevent_close();
                            if let Some(w) = handle.get_webview_window("main") {
                                let _ = w.hide();
                            }
                            refresh_tray(&handle);
                        }
                    }
                });
            }

            if let Some(ask_window) = app.get_webview_window("ask") {
                attach_ask_window_close_handler(app.handle(), &ask_window);
            }

            // Start minimized: only show window if not configured to start minimized
            if !initial_config.start_minimized {
                if let Some(window) = app.get_webview_window("main") {
                    let _ = window.show();
                    let _ = window.set_focus();
                }
            }

            tracing::info!("OpenTypeless started");

            // P1-2: Pre-warm HTTP connection pool in background
            let warm_handle = app_handle.clone();
            tauri::async_runtime::spawn(async move {
                let pipeline = warm_handle.state::<pipeline::PipelineHandle>();
                pipeline.pre_warm().await;
            });

            let startup_args = std::env::args().collect::<Vec<_>>();
            if let Some(action) = parse_cli_action(&startup_args) {
                dispatch_cli_action(&app_handle, action);
            }

            Ok(())
        })
        .invoke_handler(tauri::generate_handler![
            commands::config::load_onboarding_completed,
            commands::config::save_onboarding_completed,
            start_recording,
            stop_recording,
            retry_transcription,
            retry_history_transcription,
            recover_unfinished_take,
            cancel_transcription_retry,
            abort_recording,
            commands::ask::ask_anything,
            commands::ask::start_ask_dictation,
            commands::ask::stop_ask_dictation,
            commands::ask::start_ask_flow,
            commands::ask::stop_ask_flow,
            commands::ask::abort_ask_dictation,
            commands::ask::take_pending_ask_message,
            commands::translation::set_active_translation_target,
            commands::app_mappings::get_latest_mapping_candidate,
            commands::app_mappings::list_custom_app_mappings,
            commands::app_mappings::save_custom_app_mapping,
            commands::app_mappings::update_custom_app_mapping,
            commands::app_mappings::set_custom_app_mapping_enabled,
            commands::app_mappings::delete_custom_app_mapping,
            commands::app_mappings::reset_custom_app_mappings,
            commands::app_mappings::set_family_scene_assignment,
            show_ask_window,
            resize_ask_window,
            commands::misc::check_accessibility_permission,
            commands::misc::request_accessibility_permission,
            commands::misc::request_browser_access,
            commands::config::get_config,
            commands::config::get_config_commit,
            commands::config::update_config,
            commands::credentials::get_credential_status,
            commands::credentials::read_credential,
            commands::credentials::set_credential,
            commands::credentials::clear_credential,
            commands::credentials::migrate_legacy_credentials,
            commands::stt::get_stt_provider_diagnostics,
            commands::stt::get_stt_recording_capability,
            commands::stt::cache_managed_stt_capability,
            commands::stt::clear_managed_stt_capability,
            commands::stt::test_stt_connection,
            commands::llm::test_llm_connection,
            commands::llm::bench_llm_connection,
            commands::llm::get_llm_model_capability,
            commands::stt::bench_stt_connection,
            commands::llm::fetch_llm_models,
            commands::history::get_history,
            commands::history::export_history_audio,
            commands::share::copy_png_to_clipboard,
            commands::share::save_png_with_dialog,
            commands::history::clear_history,
            commands::history::prune_history_retention,
            commands::history::delete_history_entry,
            commands::history::get_usage_stats,
            commands::ask::show_saved_ask_answer,
            commands::audio::list_microphones,
            audio::preview::start_microphone_preview,
            audio::preview::get_microphone_preview_level,
            audio::preview::stop_microphone_preview,
            commands::practice::start_practice,
            commands::practice::stop_practice,
            commands::practice::cancel_practice,
            commands::practice::get_practice,
            commands::backup::restore_backup_data,
            commands::backup::validate_backup_data,
            commands::dictionary::get_dictionary,
            commands::dictionary::add_dictionary_entry,
            commands::dictionary::update_dictionary_entry,
            commands::dictionary::remove_dictionary_entry,
            commands::dictionary::remove_dictionary_entries,
            commands::dictionary::get_correction_rules,
            commands::dictionary::add_correction_rule,
            commands::dictionary::update_correction_rule,
            commands::dictionary::remove_correction_rule,
            commands::dictionary::set_correction_rule_enabled,
            commands::dictionary::preview_dictionary_import,
            commands::dictionary::commit_dictionary_import,
            commands::dictionary::export_dictionary_json,
            commands::dictionary::export_dictionary_csv,
            commands::misc::update_hotkey,
            commands::misc::update_ask_hotkey,
            commands::misc::pause_hotkey,
            shortcut_capture::start_shortcut_capture,
            shortcut_capture::stop_shortcut_capture,
            commands::misc::resume_hotkey,
            commands::misc::refresh_tray_labels,
            commands::misc::set_tray_update_status,
            commands::misc::is_development_build,
            commands::misc::get_platform_capabilities,
            commands::misc::get_hotkey_registration_error,
            commands::misc::get_hotkey_status,
            commands::misc::get_system_diagnostics,
            commands::config::set_auto_start,
            commands::config::set_capsule_auto_hide,
            commands::config::get_session_token,
            commands::config::get_cloud_session_snapshot,
            commands::config::set_session_token,
            commands::config::set_cloud_session_snapshot,
            commands::config::begin_cloud_session_commit,
            commands::config::cancel_cloud_session_commit,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|_app, _event| {
            if matches!(_event, tauri::RunEvent::Exit) {
                if let Some(supervisor) = _app.try_state::<hotkey::HotkeySupervisor>() {
                    supervisor.shutdown();
                }
                if let Some(queue) = _app.try_state::<hotkey::RecordingCommandQueue>() {
                    queue.shutdown();
                }
                if let Some(runtime) = _app.try_state::<native_hotkey::NativeHotkeyRuntime>() {
                    runtime.shutdown();
                }
            }
            if matches!(_event, tauri::RunEvent::ExitRequested { .. }) {
                practice::cancel_on_exit(_app);
                window_state::save_main_window_state(_app);
                audio::output_mute::restore_on_exit(_app);
            }
            #[cfg(target_os = "macos")]
            if let tauri::RunEvent::Reopen {
                has_visible_windows,
                ..
            } = _event
            {
                if should_restore_main_window_on_reopen(has_visible_windows) {
                    restore_main_window(_app);
                    refresh_tray(_app);
                }
            }
        });
}
