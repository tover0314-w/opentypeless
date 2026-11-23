use crate::app_detector::types::{BrowserAccessStatus, BrowserTarget};
use crate::app_detector::ContextDetectorHandle;
use crate::hotkey::{HotkeySupervisor, HotkeySupervisorSnapshot, HotkeySupervisorState};
use crate::native_hotkey::{NativeHotkeyBinding, NativeHotkeyRuntime};
use crate::pipeline;
use crate::platform;
use crate::storage;
use crate::tray;
use crate::AskHotkeyCache;
use crate::HotkeyRegistrationError;
use crate::HotkeyRoleCache;
use serde::Serialize;
use std::sync::Arc;
use tauri::Manager;
use tauri_plugin_global_shortcut::GlobalShortcutExt;

fn request_capsule_exit(window_label: &str, busy: bool, exit: impl FnOnce()) -> Result<(), String> {
    if window_label != "capsule" {
        return Err("This exit action is only available from the capsule".to_string());
    }
    if busy {
        return Err("Finish the current voice operation before exiting".to_string());
    }
    exit();
    Ok(())
}

#[tauri::command]
pub fn quit_from_capsule(window: tauri::Window, app: tauri::AppHandle) -> Result<(), String> {
    if window.label() != "capsule" {
        return Err("This exit action is only available from the capsule".to_string());
    }
    let pipeline = app.state::<pipeline::PipelineHandle>();
    let _guard = pipeline.try_voice_start_guard()?;
    let busy = pipeline.current_state() != pipeline::PipelineState::Idle
        || app
            .state::<crate::commands::ask::AskDictationState>()
            .is_busy()
        || app.state::<crate::practice::PracticeState>().is_busy();
    request_capsule_exit(window.label(), busy, || app.exit(0))
}

#[cfg(test)]
mod capsule_exit_tests {
    use super::request_capsule_exit;

    #[test]
    fn only_idle_capsule_can_request_normal_exit() {
        for label in ["main", "ask", "unknown"] {
            assert!(request_capsule_exit(label, false, || panic!("must not exit")).is_err());
        }
        assert!(request_capsule_exit("capsule", true, || panic!("must not exit")).is_err());
        let called = std::cell::Cell::new(false);
        assert!(request_capsule_exit("capsule", false, || called.set(true)).is_ok());
        assert!(called.get());
    }
}

#[tauri::command]
pub fn request_browser_access(
    detector: tauri::State<'_, ContextDetectorHandle>,
    target: BrowserTarget,
) -> BrowserAccessStatus {
    let status = crate::app_detector::platform::request_browser_access(target);
    detector.notify_focus_changed();
    status
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub enum DiagnosticStatus {
    Ok,
    Warning,
    Error,
    NotApplicable,
    Checking,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DiagnosticRow {
    pub id: String,
    pub status: DiagnosticStatus,
    pub message: String,
    pub action: Option<String>,
    pub last_checked_at: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct SystemDiagnosticsReport {
    pub checked_at: String,
    pub rows: Vec<DiagnosticRow>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
struct ProbeResult {
    ok: bool,
    message: String,
}

impl ProbeResult {
    fn ok(message: impl Into<String>) -> Self {
        Self {
            ok: true,
            message: message.into(),
        }
    }

    fn err(message: impl Into<String>) -> Self {
        Self {
            ok: false,
            message: message.into(),
        }
    }
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct HotkeyBindingStatus {
    pub value: String,
    pub valid: bool,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HotkeyStatusError {
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HotkeyConflictRef {
    pub role: String,
    pub index: usize,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HotkeyRoleStatus {
    pub role: String,
    pub index: usize,
    pub display: String,
    pub backend: String,
    pub valid: bool,
    pub conflict_with: Option<HotkeyConflictRef>,
    pub adapter: String,
    pub state: String,
    pub message: Option<String>,
    pub last_error: Option<HotkeyStatusError>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct HotkeyCapability {
    pub platform: String,
    pub session_type: String,
    pub supports_global_hotkey: bool,
    pub supports_hold_mode: bool,
    pub supports_released_edge: bool,
    pub supports_side_specific_modifiers: bool,
    pub requires_accessibility_permission: bool,
    pub status_hint: Option<String>,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
pub struct HotkeyStatus {
    pub dictation: HotkeyBindingStatus,
    pub ask: HotkeyBindingStatus,
    pub conflict: bool,
    pub registration_error: Option<String>,
    pub roles: Vec<HotkeyRoleStatus>,
    pub capability: HotkeyCapability,
}

pub(crate) const HOTKEY_REGISTRATION_SUPERSEDED_ERROR: &str = "hotkey registration superseded";

pub(crate) fn run_if_hotkey_generation_current<F>(
    supervisor: &HotkeySupervisor,
    generation: u64,
    register: F,
) -> Result<(), String>
where
    F: FnOnce() -> Result<(), String>,
{
    supervisor
        .run_if_current_generation(generation, register)
        .unwrap_or_else(|| Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string()))
}

pub(crate) fn register_configured_shortcuts(
    app: &tauri::AppHandle,
    config: &storage::AppConfig,
) -> Result<(), String> {
    let supervisor = app.state::<HotkeySupervisor>();
    let _installation_reservation = crate::hotkey::reserve_input_installation(app)
        .ok_or_else(|| "Input installation is waiting for the active session".to_string())?;
    register_configured_shortcuts_for_generation(
        app,
        config,
        &supervisor,
        supervisor.snapshot().generation,
    )
}

pub(crate) fn register_configured_shortcuts_for_generation(
    app: &tauri::AppHandle,
    config: &storage::AppConfig,
    supervisor: &HotkeySupervisor,
    generation: u64,
) -> Result<(), String> {
    run_if_hotkey_generation_current(supervisor, generation, || {
        register_configured_shortcuts_guarded(app, config, generation)
    })
}

fn register_configured_shortcuts_guarded(
    app: &tauri::AppHandle,
    config: &storage::AppConfig,
    generation: u64,
) -> Result<(), String> {
    let hotkeys = effective_hotkey_config(config);
    let plan =
        crate::hotkey::hotkey_registration_plan_from_config(&hotkeys).map_err(|e| e.to_string())?;

    let report = crate::hotkey_registration::register_adapters(
        &plan.global,
        || {
            app.global_shortcut()
                .unregister_all()
                .map_err(|error| error.to_string())
        },
        |registered| {
            app.global_shortcut()
                .register(registered.shortcut)
                .map_err(|error| {
                    format!(
                        "{} hotkey at index {} failed to register: {error}",
                        registered.role.as_str(),
                        registered.index
                    )
                })
        },
        || {
            let Some(native_runtime) = app.try_state::<NativeHotkeyRuntime>() else {
                return if plan.native.is_empty() {
                    Ok(())
                } else {
                    Err("Native shortcut runtime is unavailable".into())
                };
            };
            let native_bindings: Vec<NativeHotkeyBinding> = plan
                .native
                .iter()
                .map(|registered| NativeHotkeyBinding {
                    role: registered.role,
                    index: registered.index,
                    trigger: registered.trigger,
                })
                .collect();
            let handle = app.clone();
            let event_bindings = native_bindings.clone();
            native_runtime.install_with_acceptance(
                native_bindings,
                crate::input_session::InputMode::from_config(&hotkeys.dictation_mode)
                    .ok_or_else(|| "Invalid dictation mode".to_string())?,
                Arc::new(move |event| {
                    let parent_binding = event.parent_trigger.map(|trigger| trigger.canonical());
                    crate::hotkey::handle_input_binding_event_at(
                        handle.clone(),
                        event.role,
                        &match event_bindings.iter().find(|binding| {
                            binding.role == event.role && binding.index == event.index
                        }) {
                            Some(binding) => binding.trigger.canonical(),
                            None => return false,
                        },
                        event.state,
                        event.occurred_at,
                        parent_binding.as_deref(),
                    )
                }),
            )
        },
    );

    let mut bindings: Vec<crate::hotkey_controller::BindingRegistration> = plan
        .global
        .iter()
        .zip(&report.global)
        .map(
            |(binding, result)| crate::hotkey_controller::BindingRegistration {
                role: binding.role.as_str().into(),
                index: binding.index,
                error: result.as_ref().err().cloned(),
            },
        )
        .collect();
    bindings.extend(plan.native.iter().map(|binding| {
        crate::hotkey_controller::BindingRegistration {
            role: binding.role.as_str().into(),
            index: binding.index,
            error: report.native.as_ref().err().cloned(),
        }
    }));
    let supervisor = app.state::<HotkeySupervisor>();
    #[cfg(target_os = "macos")]
    {
        let trusted = pipeline::is_accessibility_trusted();
        supervisor.observe_accessibility(trusted);
        if !supervisor.record_native_permission_blocked(
            generation,
            !plan.native.is_empty() && report.native.is_err() && !trusted,
        ) {
            return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.into());
        }
    }
    if !supervisor.record_binding_results(generation, bindings.clone()) {
        return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.into());
    }

    crate::hotkey::stage_input_installation(app, generation, hotkeys, &plan, &bindings);
    crate::escape_cancel::resync(app);

    match report.error() {
        Some(error) => Err(error),
        None => Ok(()),
    }
}

pub(crate) fn effective_hotkey_config(config: &storage::AppConfig) -> storage::HotkeyConfig {
    let typed_dictation = config.hotkeys.dictation.to_hotkey_string();
    let typed_ask = config
        .hotkeys
        .ask
        .as_ref()
        .and_then(storage::ShortcutBinding::to_hotkey_string);
    let ask_matches = typed_ask
        .as_deref()
        .map(|value| value == config.ask_hotkey)
        .unwrap_or_else(|| config.ask_hotkey.is_empty());

    if typed_dictation.as_deref() != Some(config.hotkey.as_str())
        || !ask_matches
        || config.hotkeys.dictation_mode != config.hotkey_mode
    {
        return storage::HotkeyConfig::from_legacy(
            &config.hotkey,
            &config.ask_hotkey,
            &config.hotkey_mode,
        );
    }

    let mut hotkeys = config.hotkeys.clone();
    replace_effective_primary(
        &mut hotkeys.dictation_bindings,
        Some(hotkeys.dictation.clone()),
    );
    replace_effective_primary(&mut hotkeys.ask_bindings, hotkeys.ask.clone());
    replace_effective_primary(&mut hotkeys.translate_bindings, hotkeys.translate.clone());
    hotkeys
}

fn replace_effective_primary(
    bindings: &mut Vec<storage::ShortcutBinding>,
    primary: Option<storage::ShortcutBinding>,
) {
    let current = bindings.first();
    if current == primary.as_ref() {
        return;
    }
    match (bindings.first_mut(), primary) {
        (Some(current), Some(primary)) => *current = primary,
        (None, Some(primary)) => bindings.push(primary),
        (_, None) => bindings.clear(),
    }
}

#[cfg(test)]
fn hotkey_status_for(
    config: &storage::AppConfig,
    registration_error: Option<String>,
) -> HotkeyStatus {
    hotkey_status_for_with_capability(config, registration_error, platform::capabilities())
}

fn hotkey_capability_for(caps: &platform::PlatformCapabilities) -> HotkeyCapability {
    let is_linux_wayland = caps.os == "linux" && caps.session_type == "wayland";
    let supports_native_single_key = matches!(caps.os.as_str(), "macos" | "windows");
    HotkeyCapability {
        platform: caps.os.clone(),
        session_type: caps.session_type.clone(),
        supports_global_hotkey: !is_linux_wayland,
        supports_hold_mode: !is_linux_wayland,
        supports_released_edge: !is_linux_wayland || supports_native_single_key,
        supports_side_specific_modifiers: supports_native_single_key,
        requires_accessibility_permission: caps.os == "macos",
        status_hint: is_linux_wayland.then(|| "linuxWaylandLimited".to_string()),
    }
}

struct HotkeyRoleStatusContext<'a> {
    validation_error: Option<&'a crate::hotkey::HotkeyPairError>,
    registration_error: Option<&'a str>,
    supervisor: Option<&'a HotkeySupervisorSnapshot>,
    capability: &'a HotkeyCapability,
}

fn hotkey_role_status(
    role: &str,
    index: usize,
    binding: Option<&storage::ShortcutBinding>,
    conflict_with: Option<HotkeyConflictRef>,
    context: &HotkeyRoleStatusContext<'_>,
) -> HotkeyRoleStatus {
    let Some(binding) = binding else {
        return HotkeyRoleStatus {
            role: role.to_string(),
            index,
            display: String::new(),
            backend: "unavailable".to_string(),
            valid: true,
            conflict_with: None,
            adapter: "unavailable".to_string(),
            state: "disabled".to_string(),
            message: None,
            last_error: None,
        };
    };
    let adapter = if crate::hotkey::native_trigger_from_binding(binding).is_some() {
        "nativeHook"
    } else {
        "tauriGlobalShortcut"
    };
    let display = binding
        .to_hotkey_string()
        .unwrap_or_else(|| binding.primary.clone());
    let valid = crate::hotkey::binding_is_valid_for_platform(binding, &context.capability.platform)
        && conflict_with.is_none();
    let status = |adapter: &str,
                  state: &str,
                  message: Option<String>,
                  last_error: Option<HotkeyStatusError>| HotkeyRoleStatus {
        role: role.to_string(),
        index,
        display: display.clone(),
        backend: adapter.to_string(),
        valid,
        conflict_with: conflict_with.clone(),
        adapter: adapter.to_string(),
        state: state.to_string(),
        message,
        last_error,
    };

    if let Some(error) = context.validation_error {
        return status(
            adapter,
            "failed",
            Some(error.to_string()),
            Some(HotkeyStatusError {
                code: if error.is_conflict() {
                    "conflict".to_string()
                } else {
                    "invalidBinding".to_string()
                },
                message: error.to_string(),
            }),
        );
    }

    if !valid {
        return status(
            adapter,
            "failed",
            Some("Invalid shortcut binding".to_string()),
            Some(HotkeyStatusError {
                code: "invalidBinding".to_string(),
                message: "Invalid shortcut binding".to_string(),
            }),
        );
    }

    if let Some(snapshot) = context.supervisor {
        if snapshot.state == HotkeySupervisorState::Starting {
            return status(
                adapter,
                "starting",
                snapshot.last_error.clone(),
                snapshot.last_error.as_ref().map(|error| HotkeyStatusError {
                    code: "registrationFailed".to_string(),
                    message: error.clone(),
                }),
            );
        }

        if snapshot.state == HotkeySupervisorState::Disabled && context.registration_error.is_none()
        {
            return status("unavailable", "disabled", None, None);
        }
        if matches!(
            snapshot.state,
            HotkeySupervisorState::Installed
                | HotkeySupervisorState::Failed
                | HotkeySupervisorState::WaitingPermission
        ) {
            if let Some(bindings) = &snapshot.bindings {
                if let Some(binding) = bindings
                    .iter()
                    .find(|binding| binding.role == role && binding.index == index)
                {
                    return match &binding.error {
                        Some(error) => status(
                            adapter,
                            "failed",
                            Some(error.clone()),
                            Some(HotkeyStatusError {
                                code: "registrationFailed".into(),
                                message: error.clone(),
                            }),
                        ),
                        None => status(adapter, "installed", None, None),
                    };
                }
            }
        }
    }

    if let Some(error) = context.registration_error {
        return status(
            adapter,
            "failed",
            Some(error.to_string()),
            Some(HotkeyStatusError {
                code: "registrationFailed".to_string(),
                message: error.to_string(),
            }),
        );
    }

    status(
        adapter,
        "installed",
        context.capability.status_hint.clone(),
        None,
    )
}

#[cfg(test)]
fn hotkey_status_for_with_capability(
    config: &storage::AppConfig,
    registration_error: Option<String>,
    caps: platform::PlatformCapabilities,
) -> HotkeyStatus {
    hotkey_status_for_with_capability_and_supervisor(config, registration_error, caps, None)
}

fn hotkey_status_for_with_capability_and_supervisor(
    config: &storage::AppConfig,
    registration_error: Option<String>,
    caps: platform::PlatformCapabilities,
    supervisor: Option<HotkeySupervisorSnapshot>,
) -> HotkeyStatus {
    let hotkeys = effective_hotkey_config(config);
    let dictation_value = hotkeys
        .dictation
        .to_hotkey_string()
        .unwrap_or_else(|| config.hotkey.clone());
    let ask_value = hotkeys
        .ask
        .as_ref()
        .and_then(storage::ShortcutBinding::to_hotkey_string)
        .unwrap_or_else(|| config.ask_hotkey.clone());
    let validation_result = crate::hotkey::validate_hotkey_config_for_platform(&hotkeys, &caps.os);
    let validation_error = validation_result.as_ref().err();
    let capability = hotkey_capability_for(&caps);
    let supervisor_error = supervisor
        .as_ref()
        .and_then(|state| state.last_error.clone());
    let effective_registration_error = registration_error.or(supervisor_error);
    let registration_error_ref = effective_registration_error.as_deref();
    let supervisor_ref = supervisor.as_ref();
    let role_context = HotkeyRoleStatusContext {
        validation_error,
        registration_error: registration_error_ref,
        supervisor: supervisor_ref,
        capability: &capability,
    };
    let mut role_bindings: Vec<(&str, usize, Option<&storage::ShortcutBinding>)> = Vec::new();
    role_bindings.extend(
        hotkeys
            .dictation_bindings
            .iter()
            .enumerate()
            .map(|(index, binding)| {
                (
                    crate::hotkey::HotkeyRole::Dictation.as_str(),
                    index,
                    Some(binding),
                )
            }),
    );
    if hotkeys.ask_bindings.is_empty() {
        role_bindings.push((crate::hotkey::HotkeyRole::Ask.as_str(), 0, None));
    } else {
        role_bindings.extend(
            hotkeys
                .ask_bindings
                .iter()
                .enumerate()
                .map(|(index, binding)| {
                    (
                        crate::hotkey::HotkeyRole::Ask.as_str(),
                        index,
                        Some(binding),
                    )
                }),
        );
    }
    if hotkeys.translate_bindings.is_empty() {
        role_bindings.push((
            crate::hotkey::HotkeyRole::TranslateSelection.as_str(),
            0,
            None,
        ));
    } else {
        role_bindings.extend(hotkeys.translate_bindings.iter().enumerate().map(
            |(index, binding)| {
                (
                    crate::hotkey::HotkeyRole::TranslateSelection.as_str(),
                    index,
                    Some(binding),
                )
            },
        ));
    }
    role_bindings.extend([
        (
            crate::hotkey::HotkeyRole::EditSelection.as_str(),
            0,
            hotkeys.edit_selection.as_ref(),
        ),
        (
            crate::hotkey::HotkeyRole::SwitchScene.as_str(),
            0,
            hotkeys.switch_scene.as_ref(),
        ),
        (
            crate::hotkey::HotkeyRole::OpenApp.as_str(),
            0,
            hotkeys.open_app.as_ref(),
        ),
        (
            crate::hotkey::HotkeyRole::PasteLast.as_str(),
            0,
            hotkeys.paste_last.as_ref(),
        ),
    ]);
    let roles = role_bindings
        .iter()
        .enumerate()
        .map(|(position, (role, index, binding))| {
            let conflict_with = binding.and_then(|binding| {
                let value = binding.to_hotkey_string()?;
                role_bindings.iter().enumerate().find_map(
                    |(other_position, (other_role, other_index, other_binding))| {
                        if position == other_position {
                            return None;
                        }
                        let other_value = other_binding.as_ref().copied()?.to_hotkey_string()?;
                        crate::hotkey::hotkeys_conflict(&value, &other_value).then(|| {
                            HotkeyConflictRef {
                                role: (*other_role).to_string(),
                                index: *other_index,
                            }
                        })
                    },
                )
            });
            hotkey_role_status(role, *index, *binding, conflict_with, &role_context)
        })
        .collect();

    HotkeyStatus {
        dictation: HotkeyBindingStatus {
            value: dictation_value,
            valid: crate::hotkey::binding_is_valid_for_platform(&hotkeys.dictation, &caps.os),
        },
        ask: HotkeyBindingStatus {
            value: ask_value,
            valid: hotkeys.ask.is_none()
                || hotkeys.ask.as_ref().is_some_and(|binding| {
                    crate::hotkey::binding_is_valid_for_platform(binding, &caps.os)
                }),
        },
        conflict: validation_result
            .as_ref()
            .err()
            .is_some_and(|error| error.is_conflict()),
        registration_error: effective_registration_error,
        roles,
        capability,
    }
}

fn diagnostic_row(
    id: &str,
    status: DiagnosticStatus,
    message: impl Into<String>,
    action: Option<&str>,
    checked_at: &str,
) -> DiagnosticRow {
    DiagnosticRow {
        id: id.to_string(),
        status,
        message: message.into(),
        action: action.map(str::to_string),
        last_checked_at: checked_at.to_string(),
    }
}

fn microphone_diagnostic_row(probe: ProbeResult, checked_at: &str) -> DiagnosticRow {
    let (status, action) = if probe.ok {
        (DiagnosticStatus::Ok, None)
    } else {
        (DiagnosticStatus::Error, Some("openSystemSoundSettings"))
    };
    diagnostic_row("microphone", status, probe.message, action, checked_at)
}

fn accessibility_diagnostic_row(
    config: &storage::AppConfig,
    caps: &platform::PlatformCapabilities,
    trusted: bool,
    checked_at: &str,
) -> DiagnosticRow {
    if !config_requires_accessibility_permission(config, caps) {
        return diagnostic_row(
            "accessibility",
            DiagnosticStatus::NotApplicable,
            "Not required for the current output mode",
            None,
            checked_at,
        );
    }

    if trusted {
        diagnostic_row(
            "accessibility",
            DiagnosticStatus::Ok,
            "Accessibility permission is granted",
            None,
            checked_at,
        )
    } else {
        diagnostic_row(
            "accessibility",
            DiagnosticStatus::Error,
            "Accessibility permission is required for the Fn shortcut or keyboard output",
            Some("openAccessibilitySettings"),
            checked_at,
        )
    }
}

fn config_requires_accessibility_permission(
    config: &storage::AppConfig,
    caps: &platform::PlatformCapabilities,
) -> bool {
    if caps.os != "macos" {
        return false;
    }

    config.insertion_strategy != "clipboardCopyOnly" || config_uses_macos_native_hotkey(config)
}

fn config_uses_macos_native_hotkey(config: &storage::AppConfig) -> bool {
    let hotkeys = effective_hotkey_config(config);
    crate::hotkey::hotkey_registration_plan_from_config_for_platform(&hotkeys, "macos")
        .map(|plan| {
            plan.native.iter().any(|registered| {
                matches!(
                    registered.trigger,
                    crate::native_hotkey::NativeHotkeyTrigger::Fn
                        | crate::native_hotkey::NativeHotkeyTrigger::FnSpace
                        | crate::native_hotkey::NativeHotkeyTrigger::FnLeftShift
                        | crate::native_hotkey::NativeHotkeyTrigger::FnKey(_)
                )
            })
        })
        .unwrap_or(false)
}

fn hotkey_diagnostic_row(
    status: HotkeyStatus,
    caps: &platform::PlatformCapabilities,
    checked_at: &str,
) -> DiagnosticRow {
    if status.roles.iter().any(|role| role.state == "starting") {
        return diagnostic_row(
            "hotkey",
            DiagnosticStatus::Checking,
            "Global hotkeys are being registered",
            None,
            checked_at,
        );
    }

    if let Some(error) = status.registration_error {
        return diagnostic_row(
            "hotkey",
            DiagnosticStatus::Error,
            error,
            Some("reviewHotkeys"),
            checked_at,
        );
    }

    if status.conflict {
        return diagnostic_row(
            "hotkey",
            DiagnosticStatus::Error,
            "Dictation and Ask hotkeys conflict",
            Some("reviewHotkeys"),
            checked_at,
        );
    }

    if !status.dictation.valid || !status.ask.valid {
        return diagnostic_row(
            "hotkey",
            DiagnosticStatus::Error,
            "One or more hotkeys are invalid",
            Some("reviewHotkeys"),
            checked_at,
        );
    }

    if !caps.global_hotkey_reliable {
        return diagnostic_row(
            "hotkey",
            DiagnosticStatus::Warning,
            "Global hotkeys may be limited in this desktop session",
            None,
            checked_at,
        );
    }

    diagnostic_row(
        "hotkey",
        DiagnosticStatus::Ok,
        "Global hotkeys are configured",
        None,
        checked_at,
    )
}

fn clipboard_diagnostic_row(probe: ProbeResult, checked_at: &str) -> DiagnosticRow {
    let (status, action) = if probe.ok {
        (DiagnosticStatus::Ok, None)
    } else {
        (DiagnosticStatus::Error, Some("retryDiagnostics"))
    };
    let message = if probe.ok {
        format!("{} (text-only restore)", probe.message)
    } else {
        probe.message
    };
    diagnostic_row("clipboard", status, message, action, checked_at)
}

fn insertion_diagnostic_row(
    config: &storage::AppConfig,
    caps: &platform::PlatformCapabilities,
    checked_at: &str,
) -> DiagnosticRow {
    let uses_clipboard_paste = config.insertion_strategy == "clipboardPaste"
        || (config.insertion_strategy == "auto" && config.output_mode == "clipboard");
    let uses_keyboard = config.insertion_strategy == "keyboard"
        || config.insertion_strategy == "windowsSendInput"
        || (config.insertion_strategy == "auto" && config.output_mode == "keyboard");

    if uses_clipboard_paste && !caps.clipboard_auto_paste_reliable {
        return diagnostic_row(
            "insertion",
            DiagnosticStatus::Warning,
            "Clipboard paste automation may fall back to copy-only",
            None,
            checked_at,
        );
    }

    if uses_keyboard && !caps.keyboard_output_reliable {
        return diagnostic_row(
            "insertion",
            DiagnosticStatus::Warning,
            "Keyboard output may be limited in this desktop session",
            None,
            checked_at,
        );
    }

    diagnostic_row(
        "insertion",
        DiagnosticStatus::Ok,
        "Text insertion mode is compatible",
        None,
        checked_at,
    )
}

fn platform_diagnostic_row(
    caps: &platform::PlatformCapabilities,
    checked_at: &str,
) -> DiagnosticRow {
    if caps.os == "linux" && caps.session_type == "wayland" {
        return diagnostic_row(
            "platform",
            DiagnosticStatus::Warning,
            "Linux Wayland can restrict global shortcuts and input automation",
            None,
            checked_at,
        );
    }

    diagnostic_row(
        "platform",
        DiagnosticStatus::Ok,
        format!("{} / {}", caps.os, caps.session_type),
        None,
        checked_at,
    )
}

fn build_system_diagnostics_report(
    config: &storage::AppConfig,
    caps: platform::PlatformCapabilities,
    hotkey_status: HotkeyStatus,
    accessibility_trusted: bool,
    microphone_probe: ProbeResult,
    clipboard_probe: ProbeResult,
    checked_at: &str,
) -> SystemDiagnosticsReport {
    let rows = vec![
        microphone_diagnostic_row(microphone_probe, checked_at),
        accessibility_diagnostic_row(config, &caps, accessibility_trusted, checked_at),
        hotkey_diagnostic_row(hotkey_status, &caps, checked_at),
        clipboard_diagnostic_row(clipboard_probe, checked_at),
        insertion_diagnostic_row(config, &caps, checked_at),
        platform_diagnostic_row(&caps, checked_at),
    ];

    SystemDiagnosticsReport {
        checked_at: checked_at.to_string(),
        rows,
    }
}

fn probe_microphone_input() -> ProbeResult {
    use cpal::traits::{DeviceTrait, HostTrait};

    let host = cpal::default_host();
    let Some(device) = host.default_input_device() else {
        return ProbeResult::err("No default microphone input was found");
    };

    match device.default_input_config() {
        Ok(config) => ProbeResult::ok(format!(
            "{} / {} Hz",
            device
                .description()
                .map(|description| description.name().to_string())
                .unwrap_or_else(|_| "Default microphone".to_string()),
            config.sample_rate()
        )),
        Err(error) => ProbeResult::err(format!("Microphone input is unavailable: {error}")),
    }
}

fn probe_clipboard_write_restore() -> ProbeResult {
    let mut clipboard = match arboard::Clipboard::new() {
        Ok(clipboard) => clipboard,
        Err(error) => return ProbeResult::err(format!("Clipboard is unavailable: {error}")),
    };

    let Ok(original_text) = clipboard.get_text() else {
        return ProbeResult::ok("Clipboard is available");
    };

    let sentinel = format!(
        "opentypeless-diagnostic-{}",
        chrono::Utc::now().timestamp_nanos_opt().unwrap_or_default()
    );
    if let Err(error) = clipboard.set_text(sentinel.clone()) {
        return ProbeResult::err(format!("Clipboard write failed: {error}"));
    }

    let read_back = clipboard.get_text().unwrap_or_default();
    let restore_result = clipboard.set_text(original_text);

    if let Err(error) = restore_result {
        return ProbeResult::err(format!("Clipboard restore failed: {error}"));
    }

    if read_back == sentinel {
        ProbeResult::ok("Clipboard write and text restore succeeded")
    } else {
        ProbeResult::err("Clipboard write verification failed")
    }
}

fn current_diagnostics_timestamp() -> String {
    chrono::Local::now().format("%Y-%m-%dT%H:%M:%S").to_string()
}

#[tauri::command]
pub fn refresh_tray_labels(app: tauri::AppHandle) -> Result<(), String> {
    tray::refresh_tray(&app);
    Ok(())
}

#[tauri::command]
pub fn set_tray_update_status(app: tauri::AppHandle, status: String) -> Result<(), String> {
    let status = tray::TrayUpdateStatus::parse(&status)
        .ok_or_else(|| format!("Unknown update status: {status}"))?;
    if tray::set_update_status(status) {
        tray::refresh_tray(&app);
    }
    Ok(())
}

/// Debug builds run from `target/`, where the updater cannot replace the app bundle.
#[tauri::command]
pub fn is_development_build() -> bool {
    cfg!(debug_assertions)
}

#[tauri::command]
pub fn check_accessibility_permission() -> bool {
    pipeline::is_accessibility_trusted()
}

#[tauri::command]
pub fn request_accessibility_permission() -> bool {
    pipeline::request_accessibility_permission()
}

#[tauri::command]
pub fn get_platform_capabilities() -> platform::PlatformCapabilities {
    platform::capabilities()
}

#[tauri::command]
pub fn get_hotkey_registration_error(
    state: tauri::State<'_, HotkeyRegistrationError>,
) -> Option<String> {
    state.0.lock().unwrap_or_else(|e| e.into_inner()).clone()
}

#[tauri::command]
pub async fn get_hotkey_status(
    config_state: tauri::State<'_, storage::ConfigManager>,
    hotkey_error: tauri::State<'_, HotkeyRegistrationError>,
    hotkey_supervisor: tauri::State<'_, HotkeySupervisor>,
) -> Result<HotkeyStatus, String> {
    let config = config_state.load().await.map_err(|e| e.to_string())?;
    let registration_error = hotkey_error
        .0
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    Ok(hotkey_status_for_with_capability_and_supervisor(
        &config,
        registration_error,
        platform::capabilities(),
        Some(hotkey_supervisor.snapshot()),
    ))
}

#[tauri::command]
pub async fn get_system_diagnostics(
    config_state: tauri::State<'_, storage::ConfigManager>,
    hotkey_error: tauri::State<'_, HotkeyRegistrationError>,
    hotkey_supervisor: tauri::State<'_, HotkeySupervisor>,
) -> Result<SystemDiagnosticsReport, String> {
    let config = config_state.load().await.map_err(|e| e.to_string())?;
    let registration_error = hotkey_error
        .0
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    let caps = platform::capabilities();
    let hotkey_status = hotkey_status_for_with_capability_and_supervisor(
        &config,
        registration_error,
        caps.clone(),
        Some(hotkey_supervisor.snapshot()),
    );
    let accessibility_trusted = pipeline::is_accessibility_trusted();
    let checked_at = current_diagnostics_timestamp();

    Ok(build_system_diagnostics_report(
        &config,
        caps,
        hotkey_status,
        accessibility_trusted,
        probe_microphone_input(),
        probe_clipboard_write_restore(),
        &checked_at,
    ))
}

fn apply_legacy_hotkey_candidate(
    current: &mut storage::AppConfig,
    previous: &storage::AppConfig,
    candidate: &storage::AppConfig,
) -> anyhow::Result<()> {
    if current.hotkeys != previous.hotkeys {
        return Err(anyhow::anyhow!(
            "Shortcut configuration changed during registration"
        ));
    }
    crate::hotkey::validate_hotkey_config(&candidate.hotkeys)
        .map_err(|error| anyhow::anyhow!(error.to_string()))?;
    current.hotkeys = candidate.hotkeys.clone();
    current.hotkey = candidate.hotkey.clone();
    current.ask_hotkey = candidate.ask_hotkey.clone();
    current.hotkey_mode = candidate.hotkey_mode.clone();
    Ok(())
}

fn commit_legacy_hotkey_candidate(
    state: &storage::ConfigManager,
    previous: &storage::AppConfig,
    candidate: &storage::AppConfig,
) -> Result<(u64, storage::AppConfig), String> {
    state
        .try_update_current_config_commit(|current| {
            apply_legacy_hotkey_candidate(current, previous, candidate)
        })
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn update_hotkey(
    window: tauri::Window,
    app: tauri::AppHandle,
    config_state: tauri::State<'_, storage::ConfigManager>,
    hotkey_error: tauri::State<'_, HotkeyRegistrationError>,
    hotkey_supervisor: tauri::State<'_, HotkeySupervisor>,
    hotkey: String,
) -> Result<(), String> {
    ensure_shortcut_capture_window(window.label())?;
    let transaction = app.try_state::<crate::hotkey::HotkeyConfigTransaction>();
    let _transaction = match transaction.as_ref() {
        Some(transaction) => Some(transaction.0.lock().await),
        None => None,
    };
    if !hotkey_supervisor.settings_registration_allowed() {
        return Err("Shortcut recovery or editing is in progress".into());
    }
    if app
        .try_state::<HotkeySupervisor>()
        .is_some_and(|supervisor| !supervisor.capture_allows_registration())
    {
        return Err("Shortcut editing is in progress".to_string());
    }
    let previous = config_state.load().await.map_err(|e| e.to_string())?;
    let mut config = previous.clone();
    let binding = storage::ShortcutBinding::from_hotkey(&hotkey)
        .ok_or_else(|| format!("Invalid hotkey: {hotkey}"))?;
    if let Some(primary) = config.hotkeys.dictation_bindings.first_mut() {
        *primary = binding.clone();
    } else {
        config.hotkeys.dictation_bindings.push(binding.clone());
    }
    config.hotkeys.dictation = binding;
    config.hotkey = hotkey;
    config.normalize_values();

    let installation_reservation = if crate::hotkey::input_configuration_busy(&app) {
        None
    } else {
        crate::hotkey::reserve_input_installation(&app)
    };
    if installation_reservation.is_none() {
        crate::hotkey::validate_hotkey_config(&config.hotkeys)
            .map_err(|error| error.to_string())?;
        let (revision, committed) =
            commit_legacy_hotkey_candidate(&config_state, &previous, &config)?;
        crate::commands::config::emit_config_commit(
            &app,
            config_state.config_epoch(),
            revision,
            &committed,
        );
        return Ok(());
    }

    let generation = hotkey_supervisor.wake_for_settings_change();
    if let Err(error) = crate::commands::config::refresh_hotkey_runtime_with_rollback(
        &previous,
        &config,
        |candidate| {
            register_configured_shortcuts_for_generation(
                &app,
                candidate,
                &hotkey_supervisor,
                generation,
            )
        },
    ) {
        if error.registration_error == HOTKEY_REGISTRATION_SUPERSEDED_ERROR {
            return Err(error.registration_error);
        }
        crate::commands::config::commit_input_registration_feedback(
            &app,
            &hotkey_supervisor,
            generation,
            &hotkey_error,
            error.rollback_error,
        );
        return Err(error.registration_error);
    }
    let committed = commit_legacy_hotkey_candidate(&config_state, &previous, &config);
    if let Err(error) = committed.as_ref() {
        let error = error.clone();
        let Some(rollback_generation) = hotkey_supervisor.begin_rollback(generation) else {
            return Err(error);
        };
        if let Err(rollback_error) = register_configured_shortcuts_for_generation(
            &app,
            &previous,
            &hotkey_supervisor,
            rollback_generation,
        ) {
            if rollback_error == HOTKEY_REGISTRATION_SUPERSEDED_ERROR {
                return Err(error);
            }
            crate::commands::config::commit_input_registration_feedback(
                &app,
                &hotkey_supervisor,
                rollback_generation,
                &hotkey_error,
                Some(rollback_error),
            );
        } else {
            crate::commands::config::commit_input_registration_feedback(
                &app,
                &hotkey_supervisor,
                rollback_generation,
                &hotkey_error,
                None,
            );
        }
        return Err(error);
    }

    let (revision, committed) = committed?;
    crate::commands::config::emit_config_commit(
        &app,
        config_state.config_epoch(),
        revision,
        &committed,
    );
    crate::commands::config::commit_input_registration_feedback(
        &app,
        &hotkey_supervisor,
        generation,
        &hotkey_error,
        None,
    );

    Ok(())
}

#[tauri::command]
pub async fn update_ask_hotkey(
    window: tauri::Window,
    app: tauri::AppHandle,
    config_state: tauri::State<'_, storage::ConfigManager>,
    hotkey_error: tauri::State<'_, HotkeyRegistrationError>,
    hotkey_supervisor: tauri::State<'_, HotkeySupervisor>,
    ask_cache: tauri::State<'_, AskHotkeyCache>,
    hotkey: String,
) -> Result<(), String> {
    ensure_shortcut_capture_window(window.label())?;
    let transaction = app.try_state::<crate::hotkey::HotkeyConfigTransaction>();
    let _transaction = match transaction.as_ref() {
        Some(transaction) => Some(transaction.0.lock().await),
        None => None,
    };
    if !hotkey_supervisor.settings_registration_allowed() {
        return Err("Shortcut recovery or editing is in progress".into());
    }
    if app
        .try_state::<HotkeySupervisor>()
        .is_some_and(|supervisor| !supervisor.capture_allows_registration())
    {
        return Err("Shortcut editing is in progress".to_string());
    }
    let previous = config_state.load().await.map_err(|e| e.to_string())?;
    let mut config = previous.clone();
    if hotkey.trim().is_empty() {
        config.hotkeys.ask_bindings.clear();
        config.hotkeys.ask = None;
    } else {
        let binding = storage::ShortcutBinding::from_hotkey(&hotkey)
            .ok_or_else(|| format!("Invalid Ask hotkey: {hotkey}"))?;
        if let Some(primary) = config.hotkeys.ask_bindings.first_mut() {
            *primary = binding.clone();
        } else {
            config.hotkeys.ask_bindings.push(binding.clone());
        }
        config.hotkeys.ask = Some(binding);
    }
    config.ask_hotkey = hotkey;
    config.normalize_values();

    let installation_reservation = if crate::hotkey::input_configuration_busy(&app) {
        None
    } else {
        crate::hotkey::reserve_input_installation(&app)
    };
    if installation_reservation.is_none() {
        crate::hotkey::validate_hotkey_config(&config.hotkeys)
            .map_err(|error| error.to_string())?;
        let (revision, committed) =
            commit_legacy_hotkey_candidate(&config_state, &previous, &config)?;
        crate::commands::config::emit_config_commit(
            &app,
            config_state.config_epoch(),
            revision,
            &committed,
        );
        return Ok(());
    }

    let generation = hotkey_supervisor.wake_for_settings_change();
    if let Err(error) = crate::commands::config::refresh_hotkey_runtime_with_rollback(
        &previous,
        &config,
        |candidate| {
            register_configured_shortcuts_for_generation(
                &app,
                candidate,
                &hotkey_supervisor,
                generation,
            )
        },
    ) {
        if error.registration_error == HOTKEY_REGISTRATION_SUPERSEDED_ERROR {
            return Err(error.registration_error);
        }
        crate::commands::config::commit_input_registration_feedback(
            &app,
            &hotkey_supervisor,
            generation,
            &hotkey_error,
            error.rollback_error,
        );
        return Err(error.registration_error);
    }
    let committed = commit_legacy_hotkey_candidate(&config_state, &previous, &config);
    if let Err(error) = committed.as_ref() {
        let error = error.clone();
        let Some(rollback_generation) = hotkey_supervisor.begin_rollback(generation) else {
            return Err(error);
        };
        if let Err(rollback_error) = register_configured_shortcuts_for_generation(
            &app,
            &previous,
            &hotkey_supervisor,
            rollback_generation,
        ) {
            if rollback_error == HOTKEY_REGISTRATION_SUPERSEDED_ERROR {
                return Err(error);
            }
            crate::commands::config::commit_input_registration_feedback(
                &app,
                &hotkey_supervisor,
                rollback_generation,
                &hotkey_error,
                Some(rollback_error),
            );
        } else {
            crate::commands::config::commit_input_registration_feedback(
                &app,
                &hotkey_supervisor,
                rollback_generation,
                &hotkey_error,
                None,
            );
        }
        *ask_cache.0.lock().unwrap_or_else(|e| e.into_inner()) = previous.ask_hotkey;
        return Err(error);
    }

    let (revision, committed) = committed?;
    crate::commands::config::emit_config_commit(
        &app,
        config_state.config_epoch(),
        revision,
        &committed,
    );
    *ask_cache.0.lock().unwrap_or_else(|e| e.into_inner()) = config.ask_hotkey.clone();
    crate::commands::config::commit_input_registration_feedback(
        &app,
        &hotkey_supervisor,
        generation,
        &hotkey_error,
        None,
    );

    Ok(())
}

/// Temporarily unregister all global shortcuts so the webview can capture key events.
#[tauri::command]
pub fn pause_hotkey<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    capture_id: Option<String>,
) -> Result<(), String> {
    ensure_shortcut_capture_window(window.label())?;
    if let Some(capture_id) = capture_id.as_deref() {
        if uuid::Uuid::parse_str(capture_id).is_err() {
            return Err("Invalid shortcut capture identity".to_string());
        }
        app.state::<HotkeySupervisor>()
            .acquire_capture(window.label(), capture_id)?;
    } else if let Some(hotkey_supervisor) = app.try_state::<HotkeySupervisor>() {
        hotkey_supervisor.disable();
    }
    crate::hotkey::suspend_input_session(&app);
    crate::escape_cancel::set_paused(&app, true);
    {
        use tauri::Emitter;
        let _ = app.emit(
            "capsule:alert_dismiss",
            serde_json::json!({"kind": "shortcut_guide"}),
        );
    }

    let result = app
        .global_shortcut()
        .unregister_all()
        .map_err(|e| e.to_string());

    if let Some(native_runtime) = app.try_state::<NativeHotkeyRuntime>() {
        native_runtime.pause();
    }

    result
}

/// Re-register the current hotkey from config after recording is done.
#[tauri::command]
pub async fn resume_hotkey(
    window: tauri::Window,
    app: tauri::AppHandle,
    config_state: tauri::State<'_, storage::ConfigManager>,
    hotkey_error: tauri::State<'_, HotkeyRegistrationError>,
    hotkey_supervisor: tauri::State<'_, HotkeySupervisor>,
    capture_id: Option<String>,
) -> Result<(), String> {
    ensure_shortcut_capture_window(window.label())?;
    let transaction = app.state::<crate::hotkey::HotkeyConfigTransaction>();
    let _transaction = transaction.0.lock().await;
    let (lease_generation, generation) =
        hotkey_supervisor.begin_capture_resume(capture_id.as_deref())?;
    if lease_generation.is_some_and(|lease_generation| {
        !hotkey_supervisor.capture_recovery_is_current(lease_generation)
    }) {
        return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
    }
    let previously_native = app.try_state::<HotkeyRoleCache>().is_some_and(|cache| {
        !cache
            .0
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .native
            .is_empty()
    });
    let config = match config_state.load().await {
        Ok(config) => config,
        Err(error) => {
            let error = error.to_string();
            if !hotkey_supervisor.fail_capture_recovery(
                generation,
                lease_generation,
                error.clone(),
                crate::hotkey_controller::RecoveryFailure::Temporary,
                || {
                    crate::escape_cancel::set_paused(&app, false);
                    *hotkey_error.0.lock().unwrap_or_else(|e| e.into_inner()) = Some(error.clone());
                },
            ) {
                return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
            }
            return Err(error);
        }
    };
    let plan = match crate::hotkey::hotkey_registration_plan_from_config_for_platform(
        &effective_hotkey_config(&config),
        std::env::consts::OS,
    ) {
        Ok(plan) => plan,
        Err(error) => {
            let error = error.to_string();
            if !hotkey_supervisor.fail_capture_recovery(
                generation,
                lease_generation,
                error.clone(),
                crate::hotkey_controller::RecoveryFailure::InvalidConfiguration,
                || {
                    *hotkey_error.0.lock().unwrap_or_else(|e| e.into_inner()) = Some(error.clone());
                },
            ) {
                return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
            }
            return Err(error);
        }
    };
    if previously_native || !plan.native.is_empty() {
        if let Err(error) = crate::shortcut_release::wait_for_release(
            || {
                app.try_state::<NativeHotkeyRuntime>()
                    .and_then(|runtime| runtime.observed_native_chord_pressed())
                    .unwrap_or_else(crate::shortcut_release::native_key_pressed)
            },
            || {
                hotkey_supervisor.is_current_generation(generation)
                    && lease_generation.is_none_or(|lease_generation| {
                        hotkey_supervisor.capture_recovery_is_current(lease_generation)
                    })
            },
            std::time::Duration::from_secs(10),
        )
        .await
        {
            if error == HOTKEY_REGISTRATION_SUPERSEDED_ERROR {
                return Err(error);
            }
            if !hotkey_supervisor.fail_capture_recovery(
                generation,
                lease_generation,
                error.clone(),
                if !pipeline::is_accessibility_trusted() {
                    crate::hotkey_controller::RecoveryFailure::PermissionRequired
                } else if !crate::shortcut_release::input_monitoring_trusted() {
                    crate::hotkey_controller::RecoveryFailure::InputMonitoringRequired
                } else {
                    crate::hotkey_controller::RecoveryFailure::ReleaseUnconfirmed
                },
                || {
                    *hotkey_error.0.lock().unwrap_or_else(|e| e.into_inner()) = Some(error.clone());
                },
            ) {
                return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
            }
            return Err(error);
        }
    }
    if let Some(lease_generation) = lease_generation {
        if !hotkey_supervisor.confirm_capture_released(lease_generation, Some(false)) {
            return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
        }
    }
    let Some(_installation_reservation) = crate::hotkey::reserve_input_installation(&app) else {
        let error = "Input installation is waiting for the active session".to_string();
        if !hotkey_supervisor.fail_capture_recovery(
            generation,
            lease_generation,
            error.clone(),
            crate::hotkey_controller::RecoveryFailure::Temporary,
            || {
                *hotkey_error
                    .0
                    .lock()
                    .unwrap_or_else(|error| error.into_inner()) = Some(error.clone());
            },
        ) {
            return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
        }
        return Err(error);
    };
    match run_if_hotkey_generation_current(&hotkey_supervisor, generation, || {
        crate::escape_cancel::set_paused(&app, false);
        register_configured_shortcuts_guarded(&app, &config, generation)
    }) {
        Ok(()) => {
            if !hotkey_supervisor.finish_capture_registration(
                generation,
                lease_generation,
                None,
                || {
                    *hotkey_error.0.lock().unwrap_or_else(|e| e.into_inner()) = None;
                    crate::hotkey::publish_saved_input_configuration(&app, generation);
                },
            ) {
                return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
            }
            Ok(())
        }
        Err(error) => {
            if error == HOTKEY_REGISTRATION_SUPERSEDED_ERROR {
                return Err(error);
            }
            if !hotkey_supervisor.finish_capture_registration(
                generation,
                lease_generation,
                Some(error.clone()),
                || {
                    *hotkey_error.0.lock().unwrap_or_else(|e| e.into_inner()) = Some(error.clone());
                    crate::hotkey::publish_saved_input_configuration(&app, generation);
                },
            ) {
                return Err(HOTKEY_REGISTRATION_SUPERSEDED_ERROR.to_string());
            }
            Err(error)
        }
    }
}

fn ensure_shortcut_capture_window(label: &str) -> Result<(), String> {
    if label == "main" {
        Ok(())
    } else {
        Err("Shortcut capture is only allowed from the main window".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_hotkey_candidate_preserves_current_non_shortcut_settings_and_rejects_conflicts() {
        let previous = storage::AppConfig::default();
        let mut candidate = previous.clone();
        candidate.hotkeys = storage::HotkeyConfig::from_legacy("Ctrl+K", "Ctrl+J", "toggle");
        candidate.normalize_values();
        let mut current = previous.clone();
        current.microphone_device = "concurrent-device".into();
        current.history_enabled = false;
        apply_legacy_hotkey_candidate(&mut current, &previous, &candidate).unwrap();
        assert_eq!(current.microphone_device, "concurrent-device");
        assert!(!current.history_enabled);
        assert_eq!(current.hotkeys, candidate.hotkeys);
        assert_eq!(current.hotkey, candidate.hotkey);
        let committed = serde_json::to_value(&current).unwrap();
        assert!(apply_legacy_hotkey_candidate(&mut current, &previous, &previous).is_err());
        assert_eq!(serde_json::to_value(current).unwrap(), committed);
    }

    #[test]
    fn invalid_legacy_hotkey_candidate_leaves_current_configuration_unchanged() {
        let previous = storage::AppConfig::default();
        let mut current = previous.clone();
        let mut candidate = previous.clone();
        candidate.hotkeys.ask = Some(candidate.hotkeys.dictation.clone());
        candidate.hotkeys.ask_bindings = vec![candidate.hotkeys.dictation.clone()];
        let snapshot = serde_json::to_value(&current).unwrap();
        assert!(apply_legacy_hotkey_candidate(&mut current, &previous, &candidate).is_err());
        assert_eq!(serde_json::to_value(current).unwrap(), snapshot);
    }

    #[test]
    fn disabled_registration_preserves_the_actual_failure_reason() {
        let config = storage::AppConfig {
            hotkey: "Ctrl+/".to_string(),
            ask_hotkey: "Ctrl+.".to_string(),
            ..storage::AppConfig::default()
        };
        let supervisor = HotkeySupervisor::default();
        supervisor.disable();
        let status = hotkey_status_for_with_capability_and_supervisor(
            &config,
            Some("Shortcut release query requires Input Monitoring permission".to_string()),
            platform::capabilities(),
            Some(supervisor.snapshot()),
        );
        assert_eq!(status.roles[0].state, "failed");
        assert_eq!(
            status.roles[0]
                .last_error
                .as_ref()
                .map(|error| error.message.as_str()),
            Some("Shortcut release query requires Input Monitoring permission"),
        );
    }

    #[test]
    fn invalid_resume_plan_records_disabled_feedback_before_returning() {
        let production = include_str!("misc.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
        let command = production
            .split("pub async fn resume_hotkey(")
            .nth(1)
            .unwrap()
            .split("\n}\n")
            .next()
            .unwrap();
        let plan = command
            .split("let plan =")
            .nth(1)
            .unwrap()
            .split("if previously_native")
            .next()
            .unwrap();
        assert!(plan.contains("fail_capture_recovery"));
        assert!(plan.contains("HOTKEY_REGISTRATION_SUPERSEDED_ERROR"));
    }

    #[test]
    fn release_failure_feedback_keeps_current_registration_disabled_without_retry() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.begin_registration_attempt();
        let mut feedback = None;
        assert!(supervisor.record_disabled_with_feedback(generation, || {
            feedback = Some("release unknown");
        }));
        assert_eq!(feedback, Some("release unknown"));
        assert_eq!(supervisor.snapshot().state, HotkeySupervisorState::Disabled);
        assert_eq!(supervisor.next_retry_delay(), None);
        supervisor.disable();
        assert!(!supervisor.record_disabled_with_feedback(generation, || {
            feedback = Some("stale failure");
        }));
        assert_eq!(feedback, Some("release unknown"));
    }

    #[test]
    fn a_new_pause_remains_disabled_after_registration_feedback_finishes() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.begin_registration_attempt();
        let feedback = Arc::new(std::sync::Mutex::new(None));
        let (entered_sender, entered_receiver) = std::sync::mpsc::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let registration_supervisor = supervisor.clone();
        let registration_feedback = Arc::clone(&feedback);
        let registration = std::thread::spawn(move || {
            registration_supervisor.record_registration_result(generation, None, || {
                entered_sender.send(()).unwrap();
                release_receiver
                    .recv_timeout(std::time::Duration::from_secs(2))
                    .unwrap();
                *registration_feedback.lock().unwrap() = Some("registered");
            })
        });
        entered_receiver
            .recv_timeout(std::time::Duration::from_secs(2))
            .unwrap();
        let pause_supervisor = supervisor.clone();
        let pause = std::thread::spawn(move || pause_supervisor.disable());
        release_sender.send(()).unwrap();
        assert!(registration.join().unwrap());
        let next_generation = pause.join().unwrap();
        assert!(next_generation > generation);
        assert_eq!(supervisor.snapshot().state, HotkeySupervisorState::Disabled);
        assert_eq!(*feedback.lock().unwrap(), Some("registered"));
        assert!(
            !supervisor.record_registration_result(generation, None, || {
                *feedback.lock().unwrap() = Some("stale");
            })
        );
        assert_eq!(*feedback.lock().unwrap(), Some("registered"));
    }

    #[test]
    fn superseded_registration_results_do_not_update_shared_error_feedback() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.begin_registration_attempt();
        supervisor.disable();
        let mut feedback = Some("new editor error".to_string());
        assert!(
            !supervisor.record_registration_result(generation, None, || {
                feedback = None;
            })
        );
        assert!(!supervisor.record_registration_result(
            generation,
            Some("old registration failure".to_string()),
            || {
                feedback = Some("old registration failure".to_string());
            },
        ));
        assert_eq!(feedback.as_deref(), Some("new editor error"));
        assert_eq!(supervisor.snapshot().state, HotkeySupervisorState::Disabled);
    }

    #[test]
    fn shortcut_resume_records_config_load_failure_before_returning() {
        let production = include_str!("misc.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
        let command = production
            .split("pub async fn resume_hotkey(")
            .nth(1)
            .unwrap()
            .split("\n}\n")
            .next()
            .unwrap();
        assert!(command.contains("match config_state.load().await"));
        let failure = command.split("Err(error) =>").nth(1).unwrap();
        assert!(failure.contains("fail_capture_recovery"));
        assert!(failure.contains("HOTKEY_REGISTRATION_SUPERSEDED_ERROR"));
    }

    #[test]
    fn shortcut_resume_keeps_its_generation_across_async_config_loading() {
        let production = include_str!("misc.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
        let command = production
            .split("pub async fn resume_hotkey(")
            .nth(1)
            .unwrap()
            .split("\n}\n")
            .next()
            .unwrap();
        assert!(
            command.find("begin_capture_resume(").unwrap()
                < command.find("config_state.load().await").unwrap()
        );
        assert!(command.contains("run_if_hotkey_generation_current"));
    }

    #[test]
    fn legacy_shortcut_success_paths_emit_owned_commits_without_full_snapshot_save() {
        let source = include_str!("misc.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
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
            assert_eq!(
                command.matches("commit_legacy_hotkey_candidate(").count(),
                2
            );
            assert_eq!(command.matches("emit_config_commit(").count(), 2);
            assert!(!command.contains(".save(&config)"));
        }
    }

    #[test]
    fn legacy_shortcut_updates_activate_only_after_config_is_saved() {
        let source = include_str!("misc.rs");
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
            let save = command.rfind("commit_legacy_hotkey_candidate(").unwrap();
            let activation = command
                .rfind("crate::commands::config::commit_input_registration_feedback(")
                .unwrap();
            assert!(save < activation);
        }
    }

    #[test]
    fn auxiliary_pause_hotkey_ipc_is_denied_before_accessing_system_shortcuts() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.begin_registration_attempt();
        supervisor.record_registration_success(generation);
        let expected = supervisor.snapshot();
        let app = tauri::test::mock_builder()
            .manage(supervisor.clone())
            .invoke_handler(tauri::generate_handler![pause_hotkey])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let response = tauri::test::get_ipc_response(
                &webview,
                tauri::webview::InvokeRequest {
                    cmd: "pause_hotkey".to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            assert_eq!(
                response.unwrap_err(),
                serde_json::json!("Shortcut capture is only allowed from the main window")
            );
            assert_eq!(supervisor.snapshot(), expected);
        }
    }

    #[test]
    fn shortcut_capture_window_policy_rejects_auxiliary_and_unknown_windows() {
        assert!(ensure_shortcut_capture_window("main").is_ok());
        for label in ["capsule", "ask", "", "Main", "main-other"] {
            assert!(ensure_shortcut_capture_window(label).is_err());
        }
    }

    #[test]
    fn shortcut_capture_commands_check_window_before_changing_runtime_state() {
        let production = include_str!("misc.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
        for (signature, mutation) in [
            ("pub fn pause_hotkey<", "hotkey_supervisor.disable()"),
            (
                "pub async fn resume_hotkey(",
                "crate::escape_cancel::set_paused(",
            ),
        ] {
            let command = production
                .split(signature)
                .nth(1)
                .unwrap()
                .split("#[tauri::command]")
                .next()
                .unwrap();
            let guard = command.find("ensure_shortcut_capture_window(window.label())?;");
            assert!(guard.is_some(), "{signature} must check its caller");
            assert!(guard.unwrap() < command.find(mutation).unwrap());
        }
    }

    #[test]
    fn legacy_shortcut_updates_check_window_before_loading_or_registering() {
        let production = include_str!("misc.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
        for signature in [
            "pub async fn update_hotkey(",
            "pub async fn update_ask_hotkey(",
        ] {
            let command = production
                .split(signature)
                .nth(1)
                .unwrap()
                .split("#[tauri::command]")
                .next()
                .unwrap();
            let guard = command.find("ensure_shortcut_capture_window(window.label())?;");
            assert!(guard.is_some(), "{signature} must check its caller");
            assert!(guard.unwrap() < command.find("config_state.load()").unwrap());
            assert!(
                guard.unwrap()
                    < command
                        .find("register_configured_shortcuts_for_generation(")
                        .unwrap()
            );
        }
    }

    #[test]
    fn hotkey_status_marks_valid_distinct_bindings() {
        let config = storage::AppConfig {
            hotkey: "Ctrl+/".to_string(),
            ask_hotkey: "Ctrl+.".to_string(),
            hotkeys: storage::HotkeyConfig::from_legacy("Ctrl+/", "Ctrl+.", "hold"),
            ..storage::AppConfig::default()
        };

        let status = hotkey_status_for(&config, None);

        assert!(status.dictation.valid);
        assert!(status.ask.valid);
        assert!(!status.conflict);
        assert_eq!(status.registration_error, None);
    }

    #[test]
    fn hotkey_status_marks_conflicting_bindings() {
        let config = storage::AppConfig {
            hotkey: "Ctrl+/".to_string(),
            ask_hotkey: "Control+Slash".to_string(),
            ..storage::AppConfig::default()
        };

        let status = hotkey_status_for(&config, Some("already registered".to_string()));

        assert!(status.dictation.valid);
        assert!(status.ask.valid);
        assert!(status.conflict);
        assert_eq!(
            status.registration_error,
            Some("already registered".to_string())
        );
    }

    #[test]
    fn hotkey_status_reports_installed_roles_and_capability() {
        let config = storage::AppConfig {
            hotkey: "Ctrl+/".to_string(),
            ask_hotkey: "Ctrl+.".to_string(),
            hotkeys: storage::HotkeyConfig::from_legacy("Ctrl+/", "Ctrl+.", "hold"),
            ..storage::AppConfig::default()
        };
        let caps = platform::PlatformCapabilities {
            os: "linux".to_string(),
            session_type: "x11".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };

        let status = hotkey_status_for_with_capability(&config, None, caps);

        assert!(status.capability.supports_global_hotkey);
        assert!(status.capability.supports_hold_mode);
        assert!(status.capability.supports_released_edge);
        assert_eq!(status.roles.len(), 7);
        assert_eq!(status.roles[0].role, "dictation");
        assert_eq!(status.roles[0].adapter, "tauriGlobalShortcut");
        assert_eq!(status.roles[0].state, "installed");
        assert_eq!(status.roles[0].last_error, None);
        assert_eq!(status.roles[1].role, "ask");
        assert_eq!(status.roles[1].state, "installed");
        assert_eq!(status.roles[2].role, "translate");
        assert_eq!(status.roles[2].state, "disabled");
        assert_eq!(status.roles[5].role, "openApp");
        assert_eq!(status.roles[5].state, "disabled");
    }

    #[test]
    fn hotkey_status_capability_reports_native_support_on_macos_and_windows() {
        let macos = platform::PlatformCapabilities {
            os: "macos".to_string(),
            session_type: "unknown".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };
        let windows = platform::PlatformCapabilities {
            os: "windows".to_string(),
            session_type: "unknown".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };

        let macos_capability = hotkey_capability_for(&macos);
        let windows_capability = hotkey_capability_for(&windows);

        assert!(macos_capability.supports_side_specific_modifiers);
        assert!(macos_capability.requires_accessibility_permission);
        assert!(windows_capability.supports_side_specific_modifiers);
        assert!(!windows_capability.requires_accessibility_permission);
    }

    #[test]
    fn hotkey_status_includes_configured_advanced_roles() {
        let mut config = storage::AppConfig::default();
        config.hotkeys.dictation = storage::ShortcutBinding::from_hotkey("Ctrl+/").unwrap();
        config.hotkeys.dictation_bindings = vec![config.hotkeys.dictation.clone()];
        config.hotkeys.ask = storage::ShortcutBinding::from_hotkey("Ctrl+.");
        config.hotkeys.ask_bindings = config.hotkeys.ask.clone().into_iter().collect();
        config.hotkeys.translate = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+T");
        config.hotkeys.edit_selection = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+E");
        config.hotkeys.switch_scene = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+S");
        config.hotkeys.open_app = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+O");
        config.hotkey = config.hotkeys.dictation.to_hotkey_string().unwrap();
        config.ask_hotkey = "Ctrl+.".to_string();
        let caps = platform::PlatformCapabilities {
            os: "linux".to_string(),
            session_type: "x11".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };

        let status = hotkey_status_for_with_capability(&config, None, caps);
        let roles: Vec<&str> = status.roles.iter().map(|role| role.role.as_str()).collect();

        assert_eq!(
            roles,
            vec![
                "dictation",
                "ask",
                "translate",
                "editSelection",
                "switchScene",
                "openApp",
                "pasteLast",
            ]
        );
        assert!(status.roles.iter().all(|role| role.state == "installed"));
    }

    #[test]
    fn hotkey_status_reports_indexed_core_binding_entries() {
        let mut config = storage::AppConfig {
            hotkeys: storage::HotkeyConfig::from_legacy("Ctrl+/", "Ctrl+.", "hold"),
            ..Default::default()
        };
        config
            .hotkeys
            .dictation_bindings
            .push(storage::ShortcutBinding::from_hotkey("F8").unwrap());
        config.hotkey = "Ctrl+/".to_string();
        config.ask_hotkey = "Ctrl+.".to_string();
        config.hotkey_mode = "hold".to_string();
        let caps = platform::PlatformCapabilities {
            os: "linux".to_string(),
            session_type: "x11".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };

        let status = hotkey_status_for_with_capability(&config, None, caps);
        let dictation: Vec<&HotkeyRoleStatus> = status
            .roles
            .iter()
            .filter(|entry| entry.role == "dictation")
            .collect();

        assert_eq!(dictation.len(), 2);
        assert_eq!(dictation[0].index, 0);
        assert_eq!(dictation[0].display, "Ctrl+/");
        assert_eq!(dictation[0].backend, "tauriGlobalShortcut");
        assert!(dictation[0].valid);
        assert_eq!(dictation[0].conflict_with, None);
        assert_eq!(dictation[1].index, 1);
        assert_eq!(dictation[1].display, "F8");
    }

    #[test]
    fn hotkey_status_reports_native_hook_for_native_dictation() {
        let config = storage::AppConfig {
            hotkey: "RightAlt".to_string(),
            ask_hotkey: "Ctrl+.".to_string(),
            hotkey_mode: "toggle".to_string(),
            hotkeys: storage::HotkeyConfig::from_legacy("RightAlt", "Ctrl+.", "toggle"),
            ..storage::AppConfig::default()
        };
        let caps = platform::PlatformCapabilities {
            os: "windows".to_string(),
            session_type: "unknown".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };

        let status = hotkey_status_for_with_capability(&config, None, caps);
        assert!(status.dictation.valid);
        assert_eq!(status.roles[0].role, "dictation");
        assert_eq!(status.roles[0].adapter, "nativeHook");
        assert_eq!(status.roles[0].state, "installed");
    }

    #[test]
    fn hotkey_supervisor_generation_guard_skips_register_closure_when_superseded() {
        let supervisor = HotkeySupervisor::default();
        let stale_generation = supervisor.snapshot().generation;
        supervisor.wake_for_settings_change();
        let mut called = false;

        let result = run_if_hotkey_generation_current(&supervisor, stale_generation, || {
            called = true;
            Ok(())
        });

        assert_eq!(result.unwrap_err(), HOTKEY_REGISTRATION_SUPERSEDED_ERROR);
        assert!(!called);
    }

    #[test]
    fn hotkey_status_reports_failed_roles_for_registration_error() {
        let config = storage::AppConfig {
            hotkey: "Ctrl+/".to_string(),
            ask_hotkey: "Ctrl+.".to_string(),
            ..storage::AppConfig::default()
        };
        let caps = platform::PlatformCapabilities {
            os: "linux".to_string(),
            session_type: "x11".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };

        let status = hotkey_status_for_with_capability(
            &config,
            Some("already registered".to_string()),
            caps,
        );

        assert_eq!(status.roles[0].state, "failed");
        assert_eq!(
            status.roles[0]
                .last_error
                .as_ref()
                .map(|error| error.code.as_str()),
            Some("registrationFailed")
        );
        assert_eq!(status.roles[1].state, "failed");
    }

    #[test]
    fn indexed_registration_results_do_not_mark_a_working_fn_role_failed() {
        let mut config = storage::AppConfig::default();
        config.hotkey = "Fn".into();
        config.ask_hotkey = "Ctrl+.".into();
        config.hotkeys = storage::HotkeyConfig::from_legacy(
            &config.hotkey,
            &config.ask_hotkey,
            &config.hotkey_mode,
        );
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.begin_registration_attempt();
        assert!(supervisor.record_binding_results(
            generation,
            vec![
                crate::hotkey_controller::BindingRegistration {
                    role: "dictation".into(),
                    index: 0,
                    error: None,
                },
                crate::hotkey_controller::BindingRegistration {
                    role: "ask".into(),
                    index: 0,
                    error: Some("occupied".into()),
                },
            ],
        ));
        supervisor.record_registration_failure(generation, "occupied".into());
        let status = hotkey_status_for_with_capability_and_supervisor(
            &config,
            Some("occupied".into()),
            platform::capabilities(),
            Some(supervisor.snapshot()),
        );
        let dictation = status
            .roles
            .iter()
            .find(|role| role.role == "dictation")
            .unwrap();
        let ask = status.roles.iter().find(|role| role.role == "ask").unwrap();
        assert_eq!(dictation.state, "installed");
        assert!(dictation.last_error.is_none());
        assert_eq!(ask.state, "failed");
        assert_eq!(ask.last_error.as_ref().unwrap().message, "occupied");
    }

    #[test]
    fn hotkey_status_reports_starting_roles_while_supervisor_retries() {
        let config = storage::AppConfig {
            hotkey: "Ctrl+/".to_string(),
            ask_hotkey: "Ctrl+.".to_string(),
            ..storage::AppConfig::default()
        };
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.snapshot().generation;
        supervisor.record_registration_failure(generation, "already registered".to_string());
        supervisor.begin_registration_attempt();
        let caps = platform::PlatformCapabilities {
            os: "macos".to_string(),
            session_type: "unknown".to_string(),
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
            global_hotkey_reliable: true,
        };

        let status = hotkey_status_for_with_capability_and_supervisor(
            &config,
            Some("already registered".to_string()),
            caps,
            Some(supervisor.snapshot()),
        );

        assert_eq!(status.roles[0].state, "starting");
        assert_eq!(
            status.roles[0]
                .last_error
                .as_ref()
                .map(|error| error.code.as_str()),
            Some("registrationFailed")
        );
        assert_eq!(status.roles[1].state, "starting");
    }

    #[test]
    fn hotkey_diagnostics_report_checking_while_supervisor_is_starting() {
        let config = storage::AppConfig {
            hotkey: "Ctrl+/".to_string(),
            ask_hotkey: "Ctrl+.".to_string(),
            ..storage::AppConfig::default()
        };
        let supervisor = HotkeySupervisor::default();
        let caps = platform::PlatformCapabilities {
            os: "macos".to_string(),
            session_type: "unknown".to_string(),
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
            global_hotkey_reliable: true,
        };
        let status = hotkey_status_for_with_capability_and_supervisor(
            &config,
            None,
            caps.clone(),
            Some(supervisor.snapshot()),
        );

        let row = hotkey_diagnostic_row(status, &caps, "2026-07-06T00:00:00");

        assert_eq!(row.status, DiagnosticStatus::Checking);
        assert_eq!(row.action, None);
    }

    #[test]
    fn diagnostics_rows_cover_core_runtime_health_for_configured_native_hook() {
        let config = storage::AppConfig {
            hotkey: "RightAlt".to_string(),
            ask_hotkey: "Ctrl+.".to_string(),
            hotkey_mode: "toggle".to_string(),
            hotkeys: storage::HotkeyConfig::from_legacy("RightAlt", "Ctrl+.", "toggle"),
            ..storage::AppConfig::default()
        };
        let caps = platform::PlatformCapabilities {
            os: "windows".to_string(),
            session_type: "unknown".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };
        let status = hotkey_status_for_with_capability(&config, None, caps.clone());

        let row = hotkey_diagnostic_row(status, &caps, "2026-07-06T00:00:00");

        assert_eq!(row.status, DiagnosticStatus::Ok);
        assert_eq!(row.action, None);
    }

    #[test]
    fn diagnostics_rows_cover_core_runtime_health_does_not_treat_native_hook_disabled_as_pending() {
        let caps = platform::PlatformCapabilities {
            os: "windows".to_string(),
            session_type: "unknown".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };
        let status = HotkeyStatus {
            dictation: HotkeyBindingStatus {
                value: "RightAlt".to_string(),
                valid: true,
            },
            ask: HotkeyBindingStatus {
                value: "Ctrl+.".to_string(),
                valid: true,
            },
            conflict: false,
            registration_error: None,
            roles: vec![HotkeyRoleStatus {
                role: "dictation".to_string(),
                index: 0,
                display: "RightAlt".to_string(),
                backend: "nativeHook".to_string(),
                valid: true,
                conflict_with: None,
                adapter: "nativeHook".to_string(),
                state: "disabled".to_string(),
                message: None,
                last_error: None,
            }],
            capability: hotkey_capability_for(&caps),
        };

        let row = hotkey_diagnostic_row(status, &caps, "2026-07-06T00:00:00");

        assert_eq!(row.status, DiagnosticStatus::Ok);
        assert_ne!(
            row.message,
            "Native hotkey runtime is not installed yet".to_string()
        );
    }

    #[test]
    fn hotkey_status_reports_disabled_optional_ask_role() {
        let mut config = storage::AppConfig::default();
        config.hotkeys.dictation = storage::ShortcutBinding::from_hotkey("Ctrl+/").unwrap();
        config.hotkeys.dictation_bindings = vec![config.hotkeys.dictation.clone()];
        config.hotkey = "Ctrl+/".to_string();
        config.hotkeys.ask = None;
        config.hotkeys.ask_bindings.clear();
        config.ask_hotkey = String::new();
        config.hotkeys.translate = None;
        config.hotkeys.translate_bindings.clear();
        let caps = platform::PlatformCapabilities {
            os: "linux".to_string(),
            session_type: "wayland".to_string(),
            global_hotkey_reliable: false,
            keyboard_output_reliable: false,
            clipboard_auto_paste_reliable: false,
        };

        let status = hotkey_status_for_with_capability(&config, None, caps);

        assert!(!status.capability.supports_global_hotkey);
        assert!(!status.capability.supports_released_edge);
        assert_eq!(
            status.capability.status_hint.as_deref(),
            Some("linuxWaylandLimited")
        );
        assert!(status.ask.valid);
        assert_eq!(status.roles[0].role, "dictation");
        assert_eq!(status.roles[0].state, "installed");
        assert_eq!(
            status.roles[0].message.as_deref(),
            Some("linuxWaylandLimited")
        );
        assert_eq!(status.roles[1].role, "ask");
        assert_eq!(status.roles[1].adapter, "unavailable");
        assert_eq!(status.roles[1].state, "disabled");
    }

    #[test]
    fn diagnostics_rows_cover_core_runtime_health() {
        let config = storage::AppConfig {
            insertion_strategy: "clipboardPaste".to_string(),
            output_mode: "clipboard".to_string(),
            ..storage::AppConfig::default()
        };
        let caps = platform::PlatformCapabilities {
            os: "linux".to_string(),
            session_type: "wayland".to_string(),
            global_hotkey_reliable: false,
            keyboard_output_reliable: false,
            clipboard_auto_paste_reliable: false,
        };
        let hotkey_status = hotkey_status_for(&config, Some("already registered".to_string()));

        let report = build_system_diagnostics_report(
            &config,
            caps,
            hotkey_status,
            false,
            ProbeResult::ok("Built-in microphone"),
            ProbeResult::ok("Clipboard write restored"),
            "2026-07-06T00:00:00",
        );

        let ids: Vec<&str> = report.rows.iter().map(|row| row.id.as_str()).collect();
        assert_eq!(
            ids,
            vec![
                "microphone",
                "accessibility",
                "hotkey",
                "clipboard",
                "insertion",
                "platform"
            ]
        );
        assert_eq!(report.rows[0].status, DiagnosticStatus::Ok);
        assert_eq!(report.rows[1].status, DiagnosticStatus::NotApplicable);
        assert_eq!(report.rows[2].status, DiagnosticStatus::Error);
        assert_eq!(report.rows[4].status, DiagnosticStatus::Warning);
    }

    #[test]
    fn diagnostics_marks_macos_accessibility_as_error_when_missing() {
        let config = storage::AppConfig::default();
        let caps = platform::PlatformCapabilities {
            os: "macos".to_string(),
            session_type: "unknown".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };
        let hotkey_status = hotkey_status_for(&config, None);

        let report = build_system_diagnostics_report(
            &config,
            caps,
            hotkey_status,
            false,
            ProbeResult::ok("Built-in microphone"),
            ProbeResult::ok("Clipboard write restored"),
            "2026-07-06T00:00:00",
        );

        let accessibility = report
            .rows
            .iter()
            .find(|row| row.id == "accessibility")
            .unwrap();
        assert_eq!(accessibility.status, DiagnosticStatus::Error);
        assert_eq!(
            accessibility.action.as_deref(),
            Some("openAccessibilitySettings")
        );
    }

    #[test]
    fn diagnostics_marks_macos_fn_hotkey_accessibility_as_error_even_for_clipboard_output() {
        let mut config = storage::AppConfig {
            output_mode: "clipboard".to_string(),
            insertion_strategy: "clipboardPaste".to_string(),
            hotkey: "Fn".to_string(),
            hotkey_mode: "toggle".to_string(),
            ask_hotkey: "Command+.".to_string(),
            ..storage::AppConfig::default()
        };
        config.hotkeys = storage::HotkeyConfig::from_legacy("Fn", "Command+.", "toggle");
        let caps = platform::PlatformCapabilities {
            os: "macos".to_string(),
            session_type: "unknown".to_string(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };
        let hotkey_status = hotkey_status_for_with_capability(&config, None, caps.clone());

        let report = build_system_diagnostics_report(
            &config,
            caps,
            hotkey_status,
            false,
            ProbeResult::ok("Built-in microphone"),
            ProbeResult::ok("Clipboard write restored"),
            "2026-07-06T00:00:00",
        );

        let accessibility = report
            .rows
            .iter()
            .find(|row| row.id == "accessibility")
            .unwrap();
        assert_eq!(accessibility.status, DiagnosticStatus::Error);
        assert_eq!(
            accessibility.action.as_deref(),
            Some("openAccessibilitySettings")
        );
    }

    #[test]
    fn diagnostics_requires_accessibility_for_fn_combinations_with_copy_only_output() {
        let caps = platform::PlatformCapabilities {
            os: "macos".into(),
            session_type: "unknown".into(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };
        for (dictation, ask) in [("Fn+LeftShift", "Command+."), ("Ctrl+/", "Fn+Space")] {
            let config = storage::AppConfig {
                output_mode: "clipboard".into(),
                insertion_strategy: "clipboardCopyOnly".into(),
                hotkey: dictation.into(),
                ask_hotkey: ask.into(),
                hotkey_mode: "hold".into(),
                hotkeys: storage::HotkeyConfig::from_legacy(dictation, ask, "hold"),
                ..Default::default()
            };
            let row = accessibility_diagnostic_row(&config, &caps, false, "synthetic-time");
            assert_eq!(row.status, DiagnosticStatus::Error);
            assert_eq!(row.action.as_deref(), Some("openAccessibilitySettings"));
        }
    }

    #[test]
    fn accessibility_diagnostics_distinguishes_clipboard_paste_from_copy_only() {
        let caps = platform::PlatformCapabilities {
            os: "macos".into(),
            session_type: "unknown".into(),
            global_hotkey_reliable: true,
            keyboard_output_reliable: true,
            clipboard_auto_paste_reliable: true,
        };
        for (strategy, expected) in [
            ("clipboardPaste", DiagnosticStatus::Error),
            ("clipboardCopyOnly", DiagnosticStatus::NotApplicable),
        ] {
            let config = storage::AppConfig {
                output_mode: "clipboard".into(),
                insertion_strategy: strategy.into(),
                hotkey: "Ctrl+/".into(),
                ask_hotkey: "".into(),
                hotkey_mode: "hold".into(),
                hotkeys: storage::HotkeyConfig::from_legacy("Ctrl+/", "", "hold"),
                ..Default::default()
            };
            assert_eq!(
                accessibility_diagnostic_row(&config, &caps, false, "synthetic-time").status,
                expected
            );
        }
    }

    #[test]
    fn clipboard_diagnostics_disclose_text_only_restore_scope() {
        let row = clipboard_diagnostic_row(
            ProbeResult::ok("Clipboard write and restore succeeded"),
            "2026-07-06T00:00:00",
        );

        assert_eq!(row.status, DiagnosticStatus::Ok);
        assert!(row.message.contains("text-only"));
    }
}
