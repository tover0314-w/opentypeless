pub mod app_detector;
pub mod audio;
pub mod commands;
pub mod credentials;
pub mod dictionary_io;
pub mod error;
pub mod hotkey;
#[cfg(target_os = "linux")]
mod linux_x11;
pub mod llm;
pub mod native_hotkey;
pub mod output;
pub mod pipeline;
pub mod platform;
pub mod recording_deadline;
pub mod selection;
pub mod storage;
pub mod stt;
pub mod tray;
pub mod voice_intent;

pub use hotkey::{default_ask_shortcut, default_shortcut, parse_hotkey};
pub use tray::{refresh_tray, TrayHandle};

use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{Emitter, Manager};
use tauri_plugin_autostart::MacosLauncher;
use tauri_plugin_store::StoreExt;
use tracing_subscriber::EnvFilter;

use std::sync::{Arc, Mutex};

/// Default cloud API base URL. Override with the `API_BASE_URL` environment variable.
pub const DEFAULT_API_BASE_URL: &str = "https://www.opentypeless.com";
pub const CLIENT_VERSION_HEADER: &str = "X-OpenTypeless-Version";
const HTTP_POOL_IDLE_TIMEOUT_SECS: u64 = 10 * 60;
const HTTP_TCP_KEEPALIVE_SECS: u64 = 60;

fn build_shared_http_client() -> reqwest::Client {
    reqwest::Client::builder()
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
pub struct SessionTokenStore(pub Arc<Mutex<String>>);

#[derive(Debug, Clone, PartialEq, Eq)]
struct AutoStartSyncOutcome {
    config_auto_start: bool,
    error: Option<String>,
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

fn attach_ask_window_close_handler(handle: &tauri::AppHandle, ask_window: &tauri::WebviewWindow) {
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

fn build_ask_window(handle: &tauri::AppHandle) -> tauri::Result<tauri::WebviewWindow> {
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
    .inner_size(400.0, 220.0)
    .min_inner_size(360.0, 180.0)
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

pub fn ensure_ask_window(handle: &tauri::AppHandle) -> tauri::Result<tauri::WebviewWindow> {
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

pub fn show_ask_popup_window(handle: &tauri::AppHandle) -> tauri::Result<tauri::WebviewWindow> {
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

#[cfg(test)]
mod tests {
    use super::*;

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
        assert_eq!(ask["width"].as_i64(), Some(400));
        assert_eq!(ask["height"].as_i64(), Some(220));
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

/// Persisted window position and size.
#[derive(serde::Serialize, serde::Deserialize, Clone)]
struct WindowState {
    x: i32,
    y: i32,
    width: u32,
    height: u32,
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
fn abort_recording(state: tauri::State<'_, pipeline::PipelineHandle>) -> Result<(), String> {
    state.abort();
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

fn set_hotkey_registration_error_state(app: &tauri::AppHandle, message: Option<String>) {
    if let Some(state) = app.try_state::<HotkeyRegistrationError>() {
        *state.0.lock().unwrap_or_else(|e| e.into_inner()) = message;
    }
}

fn record_hotkey_registration_result(
    app: &tauri::AppHandle,
    supervisor: &hotkey::HotkeySupervisor,
    generation: u64,
    result: Result<(), String>,
) -> Result<(), String> {
    if !supervisor.is_current_generation(generation) {
        return Err(commands::misc::HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
    }

    match result {
        Ok(()) => {
            supervisor.record_registration_success(generation);
            set_hotkey_registration_error_state(app, None);
            let _ = app.emit("hotkey:registration-recovered", ());
            Ok(())
        }
        Err(message) => {
            supervisor.record_registration_failure(generation, message.clone());
            set_hotkey_registration_error_state(app, Some(message.clone()));
            let _ = app.emit("hotkey:registration-failed", message.clone());
            Err(message)
        }
    }
}

fn spawn_hotkey_supervisor(app_handle: tauri::AppHandle) {
    tauri::async_runtime::spawn(async move {
        loop {
            let delay = app_handle
                .state::<hotkey::HotkeySupervisor>()
                .next_retry_delay()
                .unwrap_or_else(|| {
                    std::time::Duration::from_secs(hotkey::HOTKEY_SUPERVISOR_RETRY_DELAY_SECS)
                });
            tokio::time::sleep(delay).await;

            let supervisor = app_handle.state::<hotkey::HotkeySupervisor>();
            let Some(generation) = supervisor.begin_retry_registration_attempt() else {
                continue;
            };

            let config = match app_handle.state::<storage::ConfigManager>().load().await {
                Ok(config) => config,
                Err(error) => {
                    if !supervisor.is_current_generation(generation) {
                        continue;
                    }
                    let message = format!("Failed to load hotkey config for retry: {error}");
                    supervisor.record_registration_failure(generation, message.clone());
                    set_hotkey_registration_error_state(&app_handle, Some(message.clone()));
                    let _ = app_handle.emit("hotkey:registration-failed", message);
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
                    tracing::error!("CLI ask failed: {error}");
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

    tauri::Builder::default()
        .plugin(tauri_plugin_store::Builder::default().build())
        .plugin(tauri_plugin_clipboard_manager::init())
        .plugin(tauri_plugin_autostart::init(
            MacosLauncher::LaunchAgent,
            None,
        ))
        .plugin(tauri_plugin_process::init())
        .plugin(tauri_plugin_updater::Builder::new().build())
        .plugin(tauri_plugin_opener::init())
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
            let mut initial_config =
                tauri::async_runtime::block_on(config_manager.load()).unwrap_or_default();
            sync_auto_start_preference(&app_handle, &config_manager, &mut initial_config);
            app.manage(config_manager);
            app.manage(history_store);
            app.manage(dictionary_store);
            app.manage(app_mapping_store);
            app.manage(shared_client);
            app.manage(context_detector);
            app.manage(pipeline_handle);
            app.manage(commands::ask::AskDictationState::default());
            app.manage(HotkeyModeCache(Arc::new(Mutex::new(
                initial_config.hotkey_mode.clone(),
            ))));
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
            app.manage(hotkey_supervisor.clone());
            app.manage(CloseToTrayCache(Arc::new(Mutex::new(
                initial_config.close_to_tray,
            ))));
            let initial_session_token =
                credentials::load_cloud_session_token(&credentials::SystemCredentialVault)
                    .unwrap_or_else(|error| {
                        tracing::warn!(
                            "Failed to restore cloud session from the system vault: {error}"
                        );
                        None
                    })
                    .unwrap_or_default();
            app.manage(SessionTokenStore(Arc::new(Mutex::new(
                initial_session_token,
            ))));

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
                tray::build_tray_menu(&app_handle, false, true, initial_config.capsule_auto_hide)
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
                    "show_hide" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let visible = window.is_visible().unwrap_or(false);
                            if visible {
                                let _ = window.hide();
                            } else {
                                let _ = window.show();
                                let _ = window.set_focus();
                            }
                            refresh_tray(app);
                        }
                    }
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
                    "settings" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.emit("tray:settings", ());
                            let _ = window.show();
                            let _ = window.set_focus();
                            refresh_tray(app);
                        }
                    }
                    "history" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.emit("tray:history", ());
                            let _ = window.show();
                            let _ = window.set_focus();
                            refresh_tray(app);
                        }
                    }
                    "account" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.emit("navigate", "#/account");
                            let _ = window.show();
                            let _ = window.set_focus();
                            refresh_tray(app);
                        }
                    }
                    "about" => {
                        if let Some(window) = app.get_webview_window("main") {
                            let _ = window.emit("tray:about", ());
                            let _ = window.show();
                            let _ = window.set_focus();
                            refresh_tray(app);
                        }
                    }
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
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

            // Close-to-tray: intercept window close
            if let Some(main_window) = app.get_webview_window("main") {
                let handle = app.handle().clone();
                main_window.on_window_event(move |event| {
                    if let tauri::WindowEvent::CloseRequested { api, .. } = event {
                        let close_to_tray = *handle
                            .state::<CloseToTrayCache>()
                            .0
                            .lock()
                            .unwrap_or_else(|e| e.into_inner());
                        if close_to_tray {
                            api.prevent_close();
                            // Save window state before hiding (skip if minimized)
                            if let Some(w) = handle.get_webview_window("main") {
                                if let (Ok(pos), Ok(size)) = (w.outer_position(), w.outer_size()) {
                                    if pos.x > -1000
                                        && pos.y > -1000
                                        && size.width >= 720
                                        && size.height >= 480
                                    {
                                        let ws = WindowState {
                                            x: pos.x,
                                            y: pos.y,
                                            width: size.width,
                                            height: size.height,
                                        };
                                        if let Ok(store) = handle.store("settings.json") {
                                            if let Ok(val) = serde_json::to_value(&ws) {
                                                store.set("window_state", val);
                                                let _ = store.save();
                                            }
                                        }
                                    }
                                }
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

            // Restore window state from previous session
            if let Ok(store) = app.handle().store("settings.json") {
                if let Some(val) = store.get("window_state") {
                    if let Ok(ws) = serde_json::from_value::<WindowState>(val.clone()) {
                        // Validate: skip if coordinates are off-screen (e.g. -32000 from minimized state)
                        if ws.x > -1000 && ws.y > -1000 && ws.width >= 720 && ws.height >= 480 {
                            if let Some(window) = app.get_webview_window("main") {
                                let _ = window.set_position(tauri::Position::Physical(
                                    tauri::PhysicalPosition::new(ws.x, ws.y),
                                ));
                                let _ = window.set_size(tauri::Size::Physical(
                                    tauri::PhysicalSize::new(ws.width, ws.height),
                                ));
                            }
                        }
                    }
                }
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
            start_recording,
            stop_recording,
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
            commands::misc::check_accessibility_permission,
            commands::misc::request_accessibility_permission,
            commands::misc::request_browser_access,
            commands::config::get_config,
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
            commands::history::clear_history,
            commands::backup::restore_backup_data,
            commands::dictionary::get_dictionary,
            commands::dictionary::add_dictionary_entry,
            commands::dictionary::update_dictionary_entry,
            commands::dictionary::remove_dictionary_entry,
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
            commands::misc::resume_hotkey,
            commands::misc::refresh_tray_labels,
            commands::misc::get_platform_capabilities,
            commands::misc::get_hotkey_registration_error,
            commands::misc::get_hotkey_status,
            commands::misc::get_system_diagnostics,
            commands::config::set_auto_start,
            commands::config::set_capsule_auto_hide,
            commands::config::get_session_token,
            commands::config::set_session_token,
        ])
        .build(tauri::generate_context!())
        .expect("error while building tauri application")
        .run(|_app, _event| {
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
