use crate::commands;
use crate::native_hotkey::NativeHotkeyTrigger;
use crate::pipeline;
use crate::storage;
use crate::AskHotkeyCache;
use crate::HotkeyModeCache;
use crate::HotkeyRoleCache;
use crate::SessionTokenStore;
use std::sync::{Arc, Mutex};
use std::time::Duration;
use tauri::Emitter;
use tauri::Manager;
use tauri_plugin_global_shortcut::{Code, Modifiers, Shortcut, ShortcutState};

pub const HOTKEY_SUPERVISOR_RETRY_DELAY_SECS: u64 = 3;
pub const HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT: u8 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HotkeySupervisorState {
    Starting,
    Installed,
    Failed,
    Disabled,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HotkeySupervisorSnapshot {
    pub generation: u64,
    pub state: HotkeySupervisorState,
    pub retry_attempts: u8,
    pub last_error: Option<String>,
}

#[derive(Debug)]
struct HotkeySupervisorInner {
    generation: u64,
    state: HotkeySupervisorState,
    retry_attempts: u8,
    last_error: Option<String>,
}

#[derive(Clone, Debug)]
pub struct HotkeySupervisor(Arc<Mutex<HotkeySupervisorInner>>);

impl Default for HotkeySupervisor {
    fn default() -> Self {
        Self(Arc::new(Mutex::new(HotkeySupervisorInner {
            generation: 1,
            state: HotkeySupervisorState::Starting,
            retry_attempts: 0,
            last_error: None,
        })))
    }
}

impl HotkeySupervisor {
    pub fn snapshot(&self) -> HotkeySupervisorSnapshot {
        let guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        HotkeySupervisorSnapshot {
            generation: guard.generation,
            state: guard.state,
            retry_attempts: guard.retry_attempts,
            last_error: guard.last_error.clone(),
        }
    }

    pub fn begin_registration_attempt(&self) -> u64 {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        guard.state = HotkeySupervisorState::Starting;
        guard.generation
    }

    pub fn begin_retry_registration_attempt(&self) -> Option<u64> {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if guard.state != HotkeySupervisorState::Failed
            || guard.retry_attempts > HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT
        {
            return None;
        }
        guard.state = HotkeySupervisorState::Starting;
        Some(guard.generation)
    }

    pub fn wake_for_settings_change(&self) -> u64 {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        guard.generation = guard.generation.saturating_add(1);
        guard.state = HotkeySupervisorState::Starting;
        guard.retry_attempts = 0;
        guard.last_error = None;
        guard.generation
    }

    pub fn is_current_generation(&self, generation: u64) -> bool {
        let guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        guard.generation == generation
    }

    pub fn run_if_current_generation<R>(
        &self,
        generation: u64,
        f: impl FnOnce() -> R,
    ) -> Option<R> {
        let guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if guard.generation != generation {
            return None;
        }
        Some(f())
    }

    pub fn record_registration_success(&self, generation: u64) {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if guard.generation != generation {
            return;
        }
        guard.state = HotkeySupervisorState::Installed;
        guard.retry_attempts = 0;
        guard.last_error = None;
    }

    pub fn record_registration_failure(&self, generation: u64, message: String) {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if guard.generation != generation {
            return;
        }
        guard.state = HotkeySupervisorState::Failed;
        guard.retry_attempts = guard.retry_attempts.saturating_add(1);
        guard.last_error = Some(message);
    }

    pub fn record_disabled(&self, generation: u64) {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if guard.generation != generation {
            return;
        }
        guard.state = HotkeySupervisorState::Disabled;
        guard.last_error = None;
    }

    pub fn disable(&self) -> u64 {
        let mut guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        guard.generation = guard.generation.saturating_add(1);
        guard.state = HotkeySupervisorState::Disabled;
        guard.retry_attempts = 0;
        guard.last_error = None;
        guard.generation
    }

    pub fn next_retry_delay(&self) -> Option<Duration> {
        let guard = self.0.lock().unwrap_or_else(|e| e.into_inner());
        if guard.state == HotkeySupervisorState::Failed
            && guard.retry_attempts <= HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT
        {
            Some(Duration::from_secs(HOTKEY_SUPERVISOR_RETRY_DELAY_SECS))
        } else {
            None
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum HotkeyPairError {
    InvalidDictationHotkey(String),
    InvalidAskHotkey(String),
    InvalidRoleHotkey {
        role: &'static str,
        value: String,
    },
    InvalidIndexedHotkey {
        role: HotkeyRole,
        index: usize,
        value: String,
    },
    UnsupportedNativeHotkey {
        role: HotkeyRole,
        index: usize,
        value: String,
        platform: String,
    },
    ConflictingHotkeys,
    ConflictingRoleHotkeys {
        role: HotkeyRole,
        index: usize,
        conflict_role: HotkeyRole,
        conflict_index: usize,
    },
}

impl std::fmt::Display for HotkeyPairError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            Self::InvalidDictationHotkey(value) => write!(f, "Invalid hotkey: {value}"),
            Self::InvalidAskHotkey(value) => write!(f, "Invalid Ask hotkey: {value}"),
            Self::InvalidRoleHotkey { role, value } => {
                write!(f, "Invalid {role} hotkey: {value}")
            }
            Self::InvalidIndexedHotkey { role, index, value } => {
                write!(
                    f,
                    "Invalid {} hotkey at index {index}: {value}",
                    role.as_str()
                )
            }
            Self::UnsupportedNativeHotkey {
                role,
                index,
                value,
                platform,
            } => write!(
                f,
                "Unsupported {} hotkey at index {index} on {platform}: {value}",
                role.as_str()
            ),
            Self::ConflictingHotkeys => {
                write!(f, "Dictation and Ask hotkeys must use different shortcuts")
            }
            Self::ConflictingRoleHotkeys {
                role,
                index,
                conflict_role,
                conflict_index,
            } => write!(
                f,
                "{} hotkey at index {index} conflicts with {} hotkey at index {conflict_index}",
                role.as_str(),
                conflict_role.as_str()
            ),
        }
    }
}

impl HotkeyPairError {
    pub fn is_conflict(&self) -> bool {
        matches!(
            self,
            Self::ConflictingHotkeys | Self::ConflictingRoleHotkeys { .. }
        )
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum HotkeyRole {
    Dictation,
    Ask,
    TranslateSelection,
    EditSelection,
    SwitchScene,
    OpenApp,
}

impl HotkeyRole {
    pub fn as_str(self) -> &'static str {
        match self {
            Self::Dictation => "dictation",
            Self::Ask => "ask",
            Self::TranslateSelection => "translate",
            Self::EditSelection => "editSelection",
            Self::SwitchScene => "switchScene",
            Self::OpenApp => "openApp",
        }
    }
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RegisteredGlobalHotkey {
    pub role: HotkeyRole,
    pub index: usize,
    pub shortcut: Shortcut,
}

pub type RegisteredHotkey = RegisteredGlobalHotkey;

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct RegisteredNativeHotkey {
    pub role: HotkeyRole,
    pub index: usize,
    pub trigger: NativeHotkeyTrigger,
}

#[derive(Debug, Clone, PartialEq, Eq, Default)]
pub struct HotkeyRegistrationPlan {
    pub global: Vec<RegisteredGlobalHotkey>,
    pub native: Vec<RegisteredNativeHotkey>,
}

fn push_optional_registered_hotkey(
    plan: &mut HotkeyRegistrationPlan,
    role: HotkeyRole,
    binding: Option<&storage::ShortcutBinding>,
    platform: &str,
) -> Result<(), HotkeyPairError> {
    let Some(binding) = binding else {
        return Ok(());
    };
    push_registered_hotkey(plan, role, 0, binding, platform)
}

fn binding_display(binding: &storage::ShortcutBinding) -> String {
    binding
        .to_hotkey_string()
        .unwrap_or_else(|| binding.primary.clone())
}

fn invalid_binding_error(
    role: HotkeyRole,
    index: usize,
    binding: &storage::ShortcutBinding,
) -> HotkeyPairError {
    if index > 0 {
        return HotkeyPairError::InvalidIndexedHotkey {
            role,
            index,
            value: binding_display(binding),
        };
    }
    match role {
        HotkeyRole::Dictation => HotkeyPairError::InvalidDictationHotkey(binding_display(binding)),
        HotkeyRole::Ask => HotkeyPairError::InvalidAskHotkey(binding_display(binding)),
        role => HotkeyPairError::InvalidRoleHotkey {
            role: role.as_str(),
            value: binding_display(binding),
        },
    }
}

fn native_conflict(
    plan: &HotkeyRegistrationPlan,
    trigger: NativeHotkeyTrigger,
) -> Option<&RegisteredNativeHotkey> {
    plan.native
        .iter()
        .find(|registered| registered.trigger == trigger)
}

fn global_conflict<'a>(
    plan: &'a HotkeyRegistrationPlan,
    shortcut: &Shortcut,
) -> Option<&'a RegisteredGlobalHotkey> {
    plan.global
        .iter()
        .find(|registered| shortcuts_match(&registered.shortcut, shortcut))
}

fn conflict_error(
    role: HotkeyRole,
    index: usize,
    conflict_role: HotkeyRole,
    conflict_index: usize,
) -> HotkeyPairError {
    if index == 0
        && conflict_index == 0
        && matches!(
            (role, conflict_role),
            (HotkeyRole::Ask, HotkeyRole::Dictation) | (HotkeyRole::Dictation, HotkeyRole::Ask)
        )
    {
        HotkeyPairError::ConflictingHotkeys
    } else {
        HotkeyPairError::ConflictingRoleHotkeys {
            role,
            index,
            conflict_role,
            conflict_index,
        }
    }
}

fn push_registered_hotkey(
    plan: &mut HotkeyRegistrationPlan,
    role: HotkeyRole,
    index: usize,
    binding: &storage::ShortcutBinding,
    platform: &str,
) -> Result<(), HotkeyPairError> {
    if let Some(trigger) = native_trigger_from_binding(binding) {
        if !native_trigger_supported_on_platform(trigger, platform) {
            return Err(HotkeyPairError::UnsupportedNativeHotkey {
                role,
                index,
                value: trigger.canonical().to_string(),
                platform: platform.to_string(),
            });
        }
        if let Some(conflict) = native_conflict(plan, trigger) {
            return Err(conflict_error(role, index, conflict.role, conflict.index));
        }
        plan.native.push(RegisteredNativeHotkey {
            role,
            index,
            trigger,
        });
        return Ok(());
    }

    let shortcut = shortcut_from_binding(binding)
        .ok_or_else(|| invalid_binding_error(role, index, binding))?;
    if let Some(conflict) = global_conflict(plan, &shortcut) {
        return Err(conflict_error(role, index, conflict.role, conflict.index));
    }
    plan.global.push(RegisteredGlobalHotkey {
        role,
        index,
        shortcut,
    });
    Ok(())
}

pub fn native_trigger_supported_on_platform(trigger: NativeHotkeyTrigger, platform: &str) -> bool {
    match platform {
        "macos" => matches!(
            trigger,
            NativeHotkeyTrigger::Fn
                | NativeHotkeyTrigger::FnSpace
                | NativeHotkeyTrigger::FnLeftShift
        ),
        "windows" => matches!(
            trigger,
            NativeHotkeyTrigger::RightAlt
                | NativeHotkeyTrigger::RightAltSpace
                | NativeHotkeyTrigger::RightAltLeftShift
        ),
        _ => false,
    }
}

pub fn native_trigger_from_binding(
    binding: &storage::ShortcutBinding,
) -> Option<NativeHotkeyTrigger> {
    if binding.modifiers.is_empty() {
        return match binding.primary.trim().to_lowercase().as_str() {
            "fn" | "function" => Some(NativeHotkeyTrigger::Fn),
            "rightalt" | "right_alt" | "right-alt" | "altright" | "alt_right" | "alt-right" => {
                Some(NativeHotkeyTrigger::RightAlt)
            }
            _ => None,
        };
    }

    if binding.modifiers.len() != 1 {
        return None;
    }

    let modifier = binding.modifiers[0].trim().to_lowercase();
    let primary = binding.primary.trim().to_lowercase();
    match (modifier.as_str(), primary.as_str()) {
        ("fn" | "function", "space") => Some(NativeHotkeyTrigger::FnSpace),
        ("fn" | "function", "leftshift" | "left_shift" | "left-shift") => {
            Some(NativeHotkeyTrigger::FnLeftShift)
        }
        (
            "rightalt" | "right_alt" | "right-alt" | "altright" | "alt_right" | "alt-right",
            "space",
        ) => Some(NativeHotkeyTrigger::RightAltSpace),
        (
            "rightalt" | "right_alt" | "right-alt" | "altright" | "alt_right" | "alt-right",
            "leftshift" | "left_shift" | "left-shift",
        ) => Some(NativeHotkeyTrigger::RightAltLeftShift),
        _ => None,
    }
}

pub fn hotkey_registration_plan_from_config(
    config: &storage::HotkeyConfig,
) -> Result<HotkeyRegistrationPlan, HotkeyPairError> {
    hotkey_registration_plan_from_config_for_platform(config, std::env::consts::OS)
}

pub(crate) fn hotkey_registration_plan_from_config_for_platform(
    config: &storage::HotkeyConfig,
    platform: &str,
) -> Result<HotkeyRegistrationPlan, HotkeyPairError> {
    let mut plan = HotkeyRegistrationPlan::default();

    let dictation_bindings = if config.dictation_bindings.is_empty() {
        std::slice::from_ref(&config.dictation)
    } else {
        config.dictation_bindings.as_slice()
    };
    let ask_bindings = if config.ask_bindings.is_empty() {
        config.ask.as_slice()
    } else {
        config.ask_bindings.as_slice()
    };
    let translate_bindings = if config.translate_bindings.is_empty() {
        config.translate.as_slice()
    } else {
        config.translate_bindings.as_slice()
    };

    for (index, binding) in dictation_bindings.iter().enumerate() {
        push_registered_hotkey(&mut plan, HotkeyRole::Dictation, index, binding, platform)?;
    }
    for (index, binding) in ask_bindings.iter().enumerate() {
        push_registered_hotkey(&mut plan, HotkeyRole::Ask, index, binding, platform)?;
    }
    for (index, binding) in translate_bindings.iter().enumerate() {
        push_registered_hotkey(
            &mut plan,
            HotkeyRole::TranslateSelection,
            index,
            binding,
            platform,
        )?;
    }
    push_optional_registered_hotkey(
        &mut plan,
        HotkeyRole::EditSelection,
        config.edit_selection.as_ref(),
        platform,
    )?;
    push_optional_registered_hotkey(
        &mut plan,
        HotkeyRole::SwitchScene,
        config.switch_scene.as_ref(),
        platform,
    )?;
    push_optional_registered_hotkey(
        &mut plan,
        HotkeyRole::OpenApp,
        config.open_app.as_ref(),
        platform,
    )?;

    Ok(plan)
}

pub fn registered_hotkeys_from_config(
    config: &storage::HotkeyConfig,
) -> Result<Vec<RegisteredHotkey>, HotkeyPairError> {
    Ok(hotkey_registration_plan_from_config(config)?.global)
}

pub fn role_for_shortcut(plan: &[RegisteredHotkey], shortcut: &Shortcut) -> Option<HotkeyRole> {
    plan.iter()
        .find(|registered| shortcuts_match(&registered.shortcut, shortcut))
        .map(|registered| registered.role)
}

pub fn role_for_global_shortcut(
    plan: &HotkeyRegistrationPlan,
    shortcut: &Shortcut,
) -> Option<HotkeyRole> {
    plan.global
        .iter()
        .find(|registered| shortcuts_match(&registered.shortcut, shortcut))
        .map(|registered| registered.role)
}

pub fn default_shortcut() -> Shortcut {
    let default_hotkey = storage::AppConfig::default().hotkey;
    let fallback = {
        #[cfg(target_os = "macos")]
        {
            Shortcut::new(Some(Modifiers::ALT), Code::Slash)
        }
        #[cfg(not(target_os = "macos"))]
        {
            Shortcut::new(Some(Modifiers::CONTROL), Code::Slash)
        }
    };
    parse_hotkey(&default_hotkey).unwrap_or(fallback)
}

pub fn default_ask_shortcut() -> Shortcut {
    let default_hotkey = storage::AppConfig::default().ask_hotkey;
    let fallback = {
        #[cfg(target_os = "macos")]
        {
            Shortcut::new(Some(Modifiers::SUPER), Code::Period)
        }
        #[cfg(not(target_os = "macos"))]
        {
            Shortcut::new(Some(Modifiers::CONTROL), Code::Period)
        }
    };
    parse_hotkey(&default_hotkey).unwrap_or(fallback)
}

fn shortcuts_match(a: &Shortcut, b: &Shortcut) -> bool {
    a.mods == b.mods && a.key == b.key
}

pub fn hotkeys_conflict(left: &str, right: &str) -> bool {
    let native_left = storage::ShortcutBinding::from_hotkey(left)
        .as_ref()
        .and_then(native_trigger_from_binding);
    let native_right = storage::ShortcutBinding::from_hotkey(right)
        .as_ref()
        .and_then(native_trigger_from_binding);
    if let (Some(left), Some(right)) = (native_left, native_right) {
        return left == right;
    }

    match (parse_hotkey(left), parse_hotkey(right)) {
        (Some(left), Some(right)) => shortcuts_match(&left, &right),
        _ => false,
    }
}

pub fn shortcut_from_binding(binding: &storage::ShortcutBinding) -> Option<Shortcut> {
    binding.to_hotkey_string().as_deref().and_then(parse_hotkey)
}

pub fn binding_is_valid_for_registration(binding: &storage::ShortcutBinding) -> bool {
    binding_is_valid_for_platform(binding, std::env::consts::OS)
}

pub fn binding_is_valid_for_platform(binding: &storage::ShortcutBinding, platform: &str) -> bool {
    if let Some(trigger) = native_trigger_from_binding(binding) {
        native_trigger_supported_on_platform(trigger, platform)
    } else {
        shortcut_from_binding(binding).is_some()
    }
}

pub fn validate_hotkey_pair(
    dictation_hotkey: &str,
    ask_hotkey: &str,
) -> Result<(), HotkeyPairError> {
    let dictation = storage::ShortcutBinding::from_hotkey(dictation_hotkey)
        .ok_or_else(|| HotkeyPairError::InvalidDictationHotkey(dictation_hotkey.to_string()))?;
    let ask = storage::ShortcutBinding::from_hotkey(ask_hotkey)
        .ok_or_else(|| HotkeyPairError::InvalidAskHotkey(ask_hotkey.to_string()))?;
    let config = storage::HotkeyConfig {
        dictation_bindings: vec![dictation.clone()],
        ask_bindings: vec![ask.clone()],
        translate_bindings: Vec::new(),
        dictation,
        ask: Some(ask),
        translate: None,
        edit_selection: None,
        switch_scene: None,
        open_app: None,
        dictation_mode: "hold".to_string(),
    };

    hotkey_registration_plan_from_config(&config).map(|_| ())
}

pub fn validate_hotkey_config(config: &storage::HotkeyConfig) -> Result<(), HotkeyPairError> {
    hotkey_registration_plan_from_config(config).map(|_| ())
}

pub(crate) fn validate_hotkey_config_for_platform(
    config: &storage::HotkeyConfig,
    platform: &str,
) -> Result<(), HotkeyPairError> {
    hotkey_registration_plan_from_config_for_platform(config, platform).map(|_| ())
}

fn is_ask_shortcut(handle: &tauri::AppHandle, shortcut: &Shortcut) -> bool {
    let ask_hotkey = handle
        .state::<AskHotkeyCache>()
        .0
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone();
    parse_hotkey(&ask_hotkey)
        .map(|configured| shortcuts_match(&configured, shortcut))
        .unwrap_or(false)
}

fn hotkey_role_for_shortcut(handle: &tauri::AppHandle, shortcut: &Shortcut) -> HotkeyRole {
    if let Some(role_cache) = handle.try_state::<HotkeyRoleCache>() {
        let plan = role_cache
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        if let Some(role) = role_for_global_shortcut(&plan, shortcut) {
            return role;
        }
    }

    if is_ask_shortcut(handle, shortcut) {
        HotkeyRole::Ask
    } else {
        HotkeyRole::Dictation
    }
}

fn show_ask_result_window(handle: &tauri::AppHandle, result: &commands::ask::AskDictationResult) {
    handle
        .state::<commands::ask::AskDictationState>()
        .set_pending_result(result.clone());
    match crate::show_ask_popup_window(handle) {
        Ok(window) => {
            let _ = window.emit("ask:result", result);
        }
        Err(error) => {
            tracing::error!("Failed to show Ask result window: {}", error);
        }
    }
}

fn show_ask_error_window(handle: &tauri::AppHandle, message: String) {
    handle
        .state::<commands::ask::AskDictationState>()
        .set_pending_error(message.clone());
    match crate::show_ask_popup_window(handle) {
        Ok(window) => {
            let _ = window.emit("ask:error", message);
        }
        Err(error) => {
            tracing::error!("Failed to show Ask error window: {}", error);
        }
    }
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum AskShortcutAction {
    Start,
    Stop,
    StopAfterStart,
    Ignore,
}

#[derive(Clone, Copy, Debug, Eq, PartialEq)]
enum RecordingShortcutAction {
    Start {
        options: pipeline::PipelineStartOptions,
    },
    Stop,
    Ignore,
}

fn ask_shortcut_action(
    event_state: ShortcutState,
    is_recording: bool,
    is_starting: bool,
    is_busy: bool,
) -> AskShortcutAction {
    if event_state == ShortcutState::Released {
        return AskShortcutAction::Ignore;
    }

    if is_recording {
        return AskShortcutAction::Stop;
    }

    if is_starting {
        return AskShortcutAction::StopAfterStart;
    }

    if is_busy {
        return AskShortcutAction::Ignore;
    }

    AskShortcutAction::Start
}

fn recording_shortcut_action(
    hotkey_mode: &str,
    event_state: ShortcutState,
    pipeline_state: pipeline::PipelineState,
    options: pipeline::PipelineStartOptions,
) -> RecordingShortcutAction {
    let is_toggle_mode = hotkey_mode == "toggle";

    match (is_toggle_mode, event_state, pipeline_state) {
        (true, ShortcutState::Released, _) => RecordingShortcutAction::Ignore,
        (true, ShortcutState::Pressed, pipeline::PipelineState::Idle) => {
            RecordingShortcutAction::Start { options }
        }
        (true, ShortcutState::Pressed, _) => RecordingShortcutAction::Stop,
        (false, ShortcutState::Pressed, pipeline::PipelineState::Idle) => {
            RecordingShortcutAction::Start { options }
        }
        (false, ShortcutState::Pressed, _) => RecordingShortcutAction::Ignore,
        (false, ShortcutState::Released, _) => RecordingShortcutAction::Stop,
    }
}

async fn stop_ask_shortcut(handle: tauri::AppHandle) {
    if !handle
        .state::<commands::ask::AskDictationState>()
        .is_recording()
    {
        return;
    }

    let ask_state = handle.state::<commands::ask::AskDictationState>();
    let config_state = handle.state::<storage::ConfigManager>();
    let token_store = handle.state::<SessionTokenStore>();
    let client = handle.state::<reqwest::Client>();

    match commands::ask::stop_ask_dictation(
        handle.clone(),
        ask_state,
        config_state,
        token_store,
        client,
    )
    .await
    {
        Ok(result) if result.should_show_window() => show_ask_result_window(&handle, &result),
        Ok(_) => {}
        Err(message) if message == "Ask dictation is not recording" => {}
        Err(message) => show_ask_error_window(&handle, message),
    }
}

fn start_ask_shortcut(handle: tauri::AppHandle) {
    let did_reserve_start = {
        let ask_state = handle.state::<commands::ask::AskDictationState>();
        ask_state.try_begin_starting()
    };
    if !did_reserve_start {
        return;
    }

    tauri::async_runtime::spawn(async move {
        let ask_state = handle.state::<commands::ask::AskDictationState>();
        let config_state = handle.state::<storage::ConfigManager>();
        let token_store = handle.state::<SessionTokenStore>();
        let client = handle.state::<reqwest::Client>();

        if let Err(message) = commands::ask::start_reserved_ask_dictation(
            handle.clone(),
            ask_state,
            config_state,
            token_store,
            client,
            true,
        )
        .await
        {
            show_ask_error_window(&handle, message);
            return;
        }

        if handle
            .state::<commands::ask::AskDictationState>()
            .take_stop_after_start()
        {
            stop_ask_shortcut(handle).await;
        }
    });
}

fn handle_ask_shortcut(handle: tauri::AppHandle, action: AskShortcutAction) {
    match action {
        AskShortcutAction::Start => start_ask_shortcut(handle),
        AskShortcutAction::Stop => {
            tauri::async_runtime::spawn(stop_ask_shortcut(handle));
        }
        AskShortcutAction::StopAfterStart => {
            let _ = handle
                .state::<commands::ask::AskDictationState>()
                .request_stop_after_start();
        }
        AskShortcutAction::Ignore => {}
    }
}

fn handle_recording_shortcut(handle: tauri::AppHandle, action: RecordingShortcutAction) {
    match action {
        RecordingShortcutAction::Start { options } => {
            tauri::async_runtime::spawn(async move {
                if handle.state::<commands::ask::AskDictationState>().is_busy() {
                    return;
                }

                let pipeline = handle.state::<pipeline::PipelineHandle>();
                if let Err(e) = pipeline.start_with_options(options).await {
                    tracing::error!("Failed to start recording: {}", e);
                    let _ = handle.emit("pipeline:error", e.to_string());
                }
            });
        }
        RecordingShortcutAction::Stop => {
            tauri::async_runtime::spawn(async move {
                if handle.state::<commands::ask::AskDictationState>().is_busy() {
                    return;
                }

                let pipeline = handle.state::<pipeline::PipelineHandle>();
                if let Err(e) = pipeline.stop().await {
                    tracing::error!("Failed to stop recording: {}", e);
                    let _ = handle.emit("pipeline:error", e.to_string());
                }
            });
        }
        RecordingShortcutAction::Ignore => {}
    }
}

fn handle_advanced_role_shortcut(
    handle: tauri::AppHandle,
    role: HotkeyRole,
    event_state: ShortcutState,
) {
    if event_state != ShortcutState::Pressed {
        return;
    }
    let _ = handle.emit("hotkey:role", role.as_str());
}

pub fn handle_hotkey_role_event(
    handle: tauri::AppHandle,
    role: HotkeyRole,
    event_state: ShortcutState,
) {
    match role {
        HotkeyRole::Ask => {
            let ask_state = handle.state::<commands::ask::AskDictationState>();
            let action = ask_shortcut_action(
                event_state,
                ask_state.is_recording(),
                ask_state.is_starting(),
                ask_state.is_busy(),
            );
            handle_ask_shortcut(handle, action);
        }
        HotkeyRole::TranslateSelection => {
            let hotkey_mode = handle
                .state::<HotkeyModeCache>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            let pipeline_state = handle.state::<pipeline::PipelineHandle>().current_state();
            let action = recording_shortcut_action(
                &hotkey_mode,
                event_state,
                pipeline_state,
                pipeline::PipelineStartOptions {
                    force_translate: true,
                },
            );
            handle_recording_shortcut(handle, action);
        }
        HotkeyRole::Dictation => {
            let ask_action = {
                let ask_state = handle.state::<commands::ask::AskDictationState>();
                let is_recording = ask_state.is_recording();
                let is_starting = ask_state.is_starting();
                if is_recording || is_starting {
                    Some(ask_shortcut_action(
                        event_state,
                        is_recording,
                        is_starting,
                        ask_state.is_busy(),
                    ))
                } else {
                    None
                }
            };
            if let Some(action) = ask_action {
                handle_ask_shortcut(handle, action);
                return;
            }

            let hotkey_mode = handle
                .state::<HotkeyModeCache>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            let pipeline_state = handle.state::<pipeline::PipelineHandle>().current_state();
            let action = recording_shortcut_action(
                &hotkey_mode,
                event_state,
                pipeline_state,
                pipeline::PipelineStartOptions::default(),
            );
            handle_recording_shortcut(handle, action);
        }
        role => handle_advanced_role_shortcut(handle, role, event_state),
    }
}

pub fn build_shortcut_handler(
    app_handle: tauri::AppHandle,
) -> impl Fn(&tauri::AppHandle, &Shortcut, tauri_plugin_global_shortcut::ShortcutEvent)
       + Send
       + Sync
       + 'static {
    move |_app, shortcut, event| {
        let handle = app_handle.clone();
        let role = hotkey_role_for_shortcut(&handle, shortcut);
        handle_hotkey_role_event(handle, role, event.state);
    }
}

pub fn parse_hotkey(s: &str) -> Option<Shortcut> {
    let parts: Vec<&str> = s.split('+').map(|p| p.trim()).collect();
    if parts.is_empty() {
        return None;
    }

    let mut modifiers = Modifiers::empty();
    let key_str = parts.last()?;

    for &part in &parts[..parts.len() - 1] {
        match part.to_lowercase().as_str() {
            "alt" | "option" => modifiers |= Modifiers::ALT,
            "ctrl" | "control" => modifiers |= Modifiers::CONTROL,
            "shift" => modifiers |= Modifiers::SHIFT,
            "meta" | "super" | "win" | "cmd" | "command" => modifiers |= Modifiers::SUPER,
            _ => return None,
        }
    }

    let code = match key_str.to_lowercase().as_str() {
        "space" => Code::Space,
        "tab" => Code::Tab,
        "enter" | "return" => Code::Enter,
        "backspace" => Code::Backspace,
        "escape" | "esc" => Code::Escape,
        "delete" => Code::Delete,
        "insert" => Code::Insert,
        "home" => Code::Home,
        "end" => Code::End,
        "pageup" => Code::PageUp,
        "pagedown" => Code::PageDown,
        "arrowup" | "up" => Code::ArrowUp,
        "arrowdown" | "down" => Code::ArrowDown,
        "arrowleft" | "left" => Code::ArrowLeft,
        "arrowright" | "right" => Code::ArrowRight,
        "f1" => Code::F1,
        "f2" => Code::F2,
        "f3" => Code::F3,
        "f4" => Code::F4,
        "f5" => Code::F5,
        "f6" => Code::F6,
        "f7" => Code::F7,
        "f8" => Code::F8,
        "f9" => Code::F9,
        "f10" => Code::F10,
        "f11" => Code::F11,
        "f12" => Code::F12,
        "a" => Code::KeyA,
        "b" => Code::KeyB,
        "c" => Code::KeyC,
        "d" => Code::KeyD,
        "e" => Code::KeyE,
        "f" => Code::KeyF,
        "g" => Code::KeyG,
        "h" => Code::KeyH,
        "i" => Code::KeyI,
        "j" => Code::KeyJ,
        "k" => Code::KeyK,
        "l" => Code::KeyL,
        "m" => Code::KeyM,
        "n" => Code::KeyN,
        "o" => Code::KeyO,
        "p" => Code::KeyP,
        "q" => Code::KeyQ,
        "r" => Code::KeyR,
        "s" => Code::KeyS,
        "t" => Code::KeyT,
        "u" => Code::KeyU,
        "v" => Code::KeyV,
        "w" => Code::KeyW,
        "x" => Code::KeyX,
        "y" => Code::KeyY,
        "z" => Code::KeyZ,
        "0" => Code::Digit0,
        "1" => Code::Digit1,
        "2" => Code::Digit2,
        "3" => Code::Digit3,
        "4" => Code::Digit4,
        "5" => Code::Digit5,
        "6" => Code::Digit6,
        "7" => Code::Digit7,
        "8" => Code::Digit8,
        "9" => Code::Digit9,
        "/" | "slash" => Code::Slash,
        "\\" | "backslash" => Code::Backslash,
        "." | "period" | "。" => Code::Period,
        "," | "comma" => Code::Comma,
        ";" | "semicolon" => Code::Semicolon,
        "'" | "quote" => Code::Quote,
        "`" | "backquote" => Code::Backquote,
        "-" | "minus" => Code::Minus,
        "=" | "equal" => Code::Equal,
        "[" | "bracketleft" => Code::BracketLeft,
        "]" | "bracketright" => Code::BracketRight,
        _ => return None,
    };

    let mods = if modifiers.is_empty() {
        None
    } else {
        Some(modifiers)
    };
    Some(Shortcut::new(mods, code))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_parse_hotkey_ctrl_slash() {
        let s = parse_hotkey("Ctrl+/");
        assert!(s.is_some());
        let s = s.unwrap();
        assert_eq!(s.mods, Modifiers::CONTROL);
        assert_eq!(s.key, Code::Slash);
    }

    #[test]
    fn test_parse_hotkey_ctrl_shift_a() {
        let s = parse_hotkey("Ctrl+Shift+A");
        assert!(s.is_some());
        let s = s.unwrap();
        assert_eq!(s.mods, Modifiers::CONTROL | Modifiers::SHIFT);
        assert_eq!(s.key, Code::KeyA);
    }

    #[test]
    fn test_parse_hotkey_case_insensitive() {
        let s = parse_hotkey("cTrL+/");
        assert!(s.is_some());
        let s = s.unwrap();
        assert_eq!(s.mods, Modifiers::CONTROL);
        assert_eq!(s.key, Code::Slash);
    }

    #[test]
    fn test_parse_hotkey_option_slash() {
        let s = parse_hotkey("Option+/");
        assert!(s.is_some());
        let s = s.unwrap();
        assert_eq!(s.mods, Modifiers::ALT);
        assert_eq!(s.key, Code::Slash);
    }

    #[test]
    fn test_parse_hotkey_command_period() {
        for hotkey in ["Command+.", "Command+。"] {
            let s = parse_hotkey(hotkey);
            assert!(s.is_some(), "Failed to parse {hotkey}");
            let s = s.unwrap();
            assert_eq!(s.mods, Modifiers::SUPER);
            assert_eq!(s.key, Code::Period);
        }
    }

    #[test]
    fn test_parse_hotkey_ctrl_period() {
        let s = parse_hotkey("Ctrl+.");
        assert!(s.is_some());
        let s = s.unwrap();
        assert_eq!(s.mods, Modifiers::CONTROL);
        assert_eq!(s.key, Code::Period);
    }

    #[test]
    fn validates_distinct_hotkey_pair() {
        assert!(validate_hotkey_pair("Ctrl+/", "Ctrl+.").is_ok());
    }

    #[test]
    fn rejects_conflicting_hotkey_pair() {
        assert_eq!(
            validate_hotkey_pair("Ctrl+/", "Control+Slash").unwrap_err(),
            HotkeyPairError::ConflictingHotkeys
        );
    }

    #[test]
    fn rejects_invalid_dictation_hotkey() {
        assert_eq!(
            validate_hotkey_pair("Ctrl+Nope", "Ctrl+.").unwrap_err(),
            HotkeyPairError::InvalidDictationHotkey("Ctrl+Nope".to_string())
        );
    }

    #[test]
    fn rejects_invalid_ask_hotkey() {
        assert_eq!(
            validate_hotkey_pair("Ctrl+/", "Ctrl+Nope").unwrap_err(),
            HotkeyPairError::InvalidAskHotkey("Ctrl+Nope".to_string())
        );
    }

    #[test]
    fn invalid_advanced_role_hotkey_is_not_reported_as_ask_hotkey() {
        let mut config = storage::HotkeyConfig::from_legacy("Ctrl+/", "Ctrl+.", "hold");
        config.translate = Some(storage::ShortcutBinding {
            primary: "Nope".to_string(),
            modifiers: vec!["Ctrl".to_string()],
        });

        assert_eq!(
            validate_hotkey_config(&config).unwrap_err(),
            HotkeyPairError::InvalidRoleHotkey {
                role: "translate",
                value: "Nope".to_string(),
            }
        );
    }

    #[test]
    fn validates_typed_hotkey_config_and_rejects_role_collisions() {
        let mut config = storage::HotkeyConfig::from_legacy("Ctrl+/", "Ctrl+.", "hold");

        assert!(validate_hotkey_config(&config).is_ok());

        config.ask = Some(storage::ShortcutBinding {
            primary: "/".to_string(),
            modifiers: vec!["Control".to_string()],
        });
        config.ask_bindings = config.ask.clone().into_iter().collect();

        assert_eq!(
            validate_hotkey_config(&config).unwrap_err(),
            HotkeyPairError::ConflictingHotkeys
        );
    }

    #[test]
    fn native_single_key_dictation_uses_native_adapter_plan() {
        let dictation = storage::ShortcutBinding {
            primary: "RightAlt".to_string(),
            modifiers: vec![],
        };
        let ask = storage::ShortcutBinding::from_hotkey("Ctrl+.");
        let config = storage::HotkeyConfig {
            dictation_bindings: vec![dictation.clone()],
            ask_bindings: ask.clone().into_iter().collect(),
            translate_bindings: Vec::new(),
            dictation,
            ask,
            translate: None,
            edit_selection: None,
            switch_scene: None,
            open_app: None,
            dictation_mode: "toggle".to_string(),
        };

        let plan = hotkey_registration_plan_from_config_for_platform(&config, "windows").unwrap();

        assert_eq!(plan.native.len(), 1);
        assert_eq!(plan.native[0].role, HotkeyRole::Dictation);
        assert_eq!(
            plan.native[0].trigger,
            crate::native_hotkey::NativeHotkeyTrigger::RightAlt
        );
        assert_eq!(plan.global.len(), 1);
        assert_eq!(plan.global[0].role, HotkeyRole::Ask);
    }

    #[test]
    fn native_typeless_mode_shortcuts_use_native_adapter_plan() {
        let dictation = storage::ShortcutBinding::from_hotkey("Fn").unwrap();
        let ask = storage::ShortcutBinding::from_hotkey("Fn+Space");
        let translate = storage::ShortcutBinding::from_hotkey("Fn+LeftShift");
        let config = storage::HotkeyConfig {
            dictation_bindings: vec![dictation.clone()],
            ask_bindings: ask.clone().into_iter().collect(),
            translate_bindings: translate.clone().into_iter().collect(),
            dictation,
            ask,
            translate,
            edit_selection: None,
            switch_scene: None,
            open_app: None,
            dictation_mode: "toggle".to_string(),
        };

        let plan = hotkey_registration_plan_from_config_for_platform(&config, "macos").unwrap();

        assert!(plan.global.is_empty());
        assert!(plan.native.iter().any(|entry| {
            entry.role == HotkeyRole::Dictation
                && entry.trigger == crate::native_hotkey::NativeHotkeyTrigger::Fn
        }));
        assert!(plan.native.iter().any(|entry| {
            entry.role == HotkeyRole::Ask
                && entry.trigger == crate::native_hotkey::NativeHotkeyTrigger::FnSpace
        }));
        assert!(plan.native.iter().any(|entry| {
            entry.role == HotkeyRole::TranslateSelection
                && entry.trigger == crate::native_hotkey::NativeHotkeyTrigger::FnLeftShift
        }));
    }

    #[test]
    fn fn_dictation_conflicts_with_fn_ask_binding() {
        let dictation = storage::ShortcutBinding {
            primary: "Fn".to_string(),
            modifiers: vec![],
        };
        let ask = storage::ShortcutBinding {
            primary: "Fn".to_string(),
            modifiers: vec![],
        };
        let config = storage::HotkeyConfig {
            dictation_bindings: vec![dictation.clone()],
            ask_bindings: vec![ask.clone()],
            translate_bindings: Vec::new(),
            dictation,
            ask: Some(ask),
            translate: None,
            edit_selection: None,
            switch_scene: None,
            open_app: None,
            dictation_mode: "toggle".to_string(),
        };

        assert_eq!(
            hotkey_registration_plan_from_config_for_platform(&config, "macos").unwrap_err(),
            HotkeyPairError::ConflictingHotkeys
        );
    }

    #[test]
    fn new_install_default_hotkeys_have_platform_registration_plan() {
        let config = storage::AppConfig::new_install_default();
        let plan = crate::hotkey::hotkey_registration_plan_from_config(&config.hotkeys).unwrap();

        #[cfg(target_os = "macos")]
        {
            assert!(plan.native.iter().any(|entry| {
                entry.role == HotkeyRole::Dictation
                    && entry.trigger == crate::native_hotkey::NativeHotkeyTrigger::Fn
            }));
            assert!(plan.native.iter().any(|entry| {
                entry.role == HotkeyRole::Ask
                    && entry.trigger == crate::native_hotkey::NativeHotkeyTrigger::FnSpace
            }));
            assert!(plan.native.iter().any(|entry| {
                entry.role == HotkeyRole::TranslateSelection
                    && entry.trigger == crate::native_hotkey::NativeHotkeyTrigger::FnLeftShift
            }));
            assert!(!plan
                .global
                .iter()
                .any(|entry| entry.role == HotkeyRole::Dictation));
        }

        #[cfg(target_os = "windows")]
        {
            assert!(plan
                .global
                .iter()
                .any(|entry| entry.role == HotkeyRole::Dictation));
            assert!(plan
                .global
                .iter()
                .any(|entry| entry.role == HotkeyRole::Ask));
            assert!(plan
                .global
                .iter()
                .any(|entry| entry.role == HotkeyRole::TranslateSelection));
            assert!(plan.native.is_empty());
        }

        #[cfg(all(not(target_os = "macos"), not(target_os = "windows")))]
        {
            assert!(plan
                .global
                .iter()
                .any(|entry| entry.role == HotkeyRole::Dictation));
            assert!(!plan
                .native
                .iter()
                .any(|entry| entry.role == HotkeyRole::Dictation));
        }
    }

    #[test]
    fn hotkey_registration_plan_includes_all_configured_roles() {
        let mut config = storage::HotkeyConfig::from_legacy("Ctrl+/", "Ctrl+.", "hold");
        config.translate = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+T");
        config.edit_selection = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+E");
        config.switch_scene = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+S");
        config.open_app = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+O");

        let plan = registered_hotkeys_from_config(&config).unwrap();
        let roles: Vec<HotkeyRole> = plan.iter().map(|entry| entry.role).collect();

        assert_eq!(
            roles,
            vec![
                HotkeyRole::Dictation,
                HotkeyRole::Ask,
                HotkeyRole::TranslateSelection,
                HotkeyRole::EditSelection,
                HotkeyRole::SwitchScene,
                HotkeyRole::OpenApp,
            ]
        );
    }

    #[test]
    fn hotkey_binding_lists_register_every_core_binding_with_stable_indices() {
        let mut config = storage::HotkeyConfig::from_legacy("Ctrl+/", "Ctrl+.", "hold");
        config.dictation_bindings = vec![
            storage::ShortcutBinding::from_hotkey("Ctrl+/").unwrap(),
            storage::ShortcutBinding::from_hotkey("F8").unwrap(),
        ];
        config.ask_bindings = vec![
            storage::ShortcutBinding::from_hotkey("Ctrl+.").unwrap(),
            storage::ShortcutBinding::from_hotkey("F9").unwrap(),
        ];
        config.translate_bindings =
            vec![storage::ShortcutBinding::from_hotkey("Ctrl+Shift+T").unwrap()];

        let plan = hotkey_registration_plan_from_config(&config).unwrap();
        let core: Vec<(HotkeyRole, usize)> = plan
            .global
            .iter()
            .map(|entry| (entry.role, entry.index))
            .collect();

        assert_eq!(
            core,
            vec![
                (HotkeyRole::Dictation, 0),
                (HotkeyRole::Dictation, 1),
                (HotkeyRole::Ask, 0),
                (HotkeyRole::Ask, 1),
                (HotkeyRole::TranslateSelection, 0),
            ]
        );
    }

    #[test]
    fn hotkey_binding_lists_reject_secondary_cross_role_conflicts() {
        let mut config = storage::HotkeyConfig::from_legacy("Ctrl+/", "Ctrl+.", "hold");
        config
            .dictation_bindings
            .push(storage::ShortcutBinding::from_hotkey("Ctrl+Shift+E").unwrap());
        config.edit_selection = storage::ShortcutBinding::from_hotkey("Ctrl+Shift+E");

        assert_eq!(
            hotkey_registration_plan_from_config(&config).unwrap_err(),
            HotkeyPairError::ConflictingRoleHotkeys {
                role: HotkeyRole::EditSelection,
                index: 0,
                conflict_role: HotkeyRole::Dictation,
                conflict_index: 1,
            }
        );
    }

    #[test]
    fn hotkey_binding_lists_reject_duplicate_normalized_triggers() {
        let mut config = storage::HotkeyConfig::from_legacy("Ctrl+/", "", "hold");
        config.dictation_bindings = vec![
            storage::ShortcutBinding {
                primary: "/".to_string(),
                modifiers: vec!["Control".to_string()],
            },
            storage::ShortcutBinding {
                primary: "/".to_string(),
                modifiers: vec!["Ctrl".to_string()],
            },
        ];

        assert!(matches!(
            hotkey_registration_plan_from_config(&config),
            Err(HotkeyPairError::ConflictingRoleHotkeys {
                role: HotkeyRole::Dictation,
                index: 1,
                conflict_role: HotkeyRole::Dictation,
                conflict_index: 0,
            })
        ));
    }

    #[test]
    fn hotkey_binding_lists_validate_native_triggers_for_target_platform() {
        assert!(native_trigger_supported_on_platform(
            NativeHotkeyTrigger::FnSpace,
            "macos"
        ));
        assert!(!native_trigger_supported_on_platform(
            NativeHotkeyTrigger::RightAlt,
            "macos"
        ));
        assert!(native_trigger_supported_on_platform(
            NativeHotkeyTrigger::RightAltLeftShift,
            "windows"
        ));
        assert!(!native_trigger_supported_on_platform(
            NativeHotkeyTrigger::Fn,
            "windows"
        ));
        assert!(!native_trigger_supported_on_platform(
            NativeHotkeyTrigger::Fn,
            "linux"
        ));
    }

    #[test]
    fn typed_binding_parser_rejects_duplicate_modifiers() {
        let binding = storage::ShortcutBinding {
            primary: "/".to_string(),
            modifiers: vec!["Ctrl".to_string(), "Control".to_string()],
        };

        assert!(shortcut_from_binding(&binding).is_none());
    }

    #[test]
    fn ask_shortcut_is_toggle_only_and_ignores_release() {
        assert_eq!(
            ask_shortcut_action(ShortcutState::Pressed, false, false, false),
            AskShortcutAction::Start
        );
        assert_eq!(
            ask_shortcut_action(ShortcutState::Pressed, true, false, true),
            AskShortcutAction::Stop
        );
        assert_eq!(
            ask_shortcut_action(ShortcutState::Pressed, false, true, true),
            AskShortcutAction::StopAfterStart
        );
        assert_eq!(
            ask_shortcut_action(ShortcutState::Released, true, false, true),
            AskShortcutAction::Ignore
        );
        assert_eq!(
            ask_shortcut_action(ShortcutState::Pressed, false, false, true),
            AskShortcutAction::Ignore
        );
    }

    #[test]
    fn translation_shortcut_starts_recording_with_translate_override() {
        assert_eq!(
            recording_shortcut_action(
                "hold",
                ShortcutState::Pressed,
                pipeline::PipelineState::Idle,
                pipeline::PipelineStartOptions {
                    force_translate: true,
                },
            ),
            RecordingShortcutAction::Start {
                options: pipeline::PipelineStartOptions {
                    force_translate: true,
                },
            }
        );

        assert_eq!(
            recording_shortcut_action(
                "hold",
                ShortcutState::Released,
                pipeline::PipelineState::Recording,
                pipeline::PipelineStartOptions {
                    force_translate: true,
                },
            ),
            RecordingShortcutAction::Stop
        );
    }

    #[test]
    fn hold_release_queues_stop_even_when_start_is_still_pending() {
        assert_eq!(
            recording_shortcut_action(
                "hold",
                ShortcutState::Released,
                pipeline::PipelineState::Idle,
                pipeline::PipelineStartOptions::default(),
            ),
            RecordingShortcutAction::Stop
        );
    }

    #[test]
    fn test_parse_hotkey_f_keys() {
        for (key, expected) in [("F1", Code::F1), ("F12", Code::F12)] {
            let s = parse_hotkey(&format!("Ctrl+{}", key));
            assert!(s.is_some(), "Failed to parse Ctrl+{}", key);
            assert_eq!(s.unwrap().key, expected);
        }
    }

    #[test]
    fn test_parse_hotkey_meta_modifier() {
        for name in ["Meta", "Super", "Win", "Cmd", "Command"] {
            let s = parse_hotkey(&format!("{}+A", name));
            assert!(s.is_some(), "Failed to parse {}+A", name);
            assert_eq!(s.unwrap().mods, Modifiers::SUPER);
        }
    }

    #[test]
    fn test_parse_hotkey_no_modifier() {
        let s = parse_hotkey("A");
        assert!(s.is_some());
        assert_eq!(s.unwrap().mods, Modifiers::empty());
    }

    #[test]
    fn test_parse_hotkey_invalid_key() {
        let s = parse_hotkey("Alt+InvalidKey");
        assert!(s.is_none());
    }

    #[test]
    fn test_parse_hotkey_empty_string() {
        let s = parse_hotkey("");
        assert!(s.is_none());
    }

    #[test]
    fn test_parse_hotkey_digits() {
        let s = parse_hotkey("Ctrl+0");
        assert!(s.is_some());
        assert_eq!(s.unwrap().key, Code::Digit0);

        let s = parse_hotkey("Ctrl+9");
        assert!(s.is_some());
        assert_eq!(s.unwrap().key, Code::Digit9);
    }

    #[test]
    fn test_parse_hotkey_navigation_keys() {
        for (key, expected) in [
            ("Enter", Code::Enter),
            ("Tab", Code::Tab),
            ("Escape", Code::Escape),
            ("Backspace", Code::Backspace),
            ("Delete", Code::Delete),
            ("Up", Code::ArrowUp),
            ("Down", Code::ArrowDown),
        ] {
            let s = parse_hotkey(&format!("Alt+{}", key));
            assert!(s.is_some(), "Failed to parse Alt+{}", key);
            assert_eq!(s.unwrap().key, expected);
        }
    }

    #[test]
    fn hotkey_supervisor_starts_in_starting_and_keeps_failed_retry_state() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.snapshot().generation;

        assert_eq!(supervisor.snapshot().state, HotkeySupervisorState::Starting);

        supervisor.record_registration_failure(generation, "shortcut is occupied".to_string());

        let snapshot = supervisor.snapshot();
        assert_eq!(snapshot.state, HotkeySupervisorState::Failed);
        assert_eq!(snapshot.last_error.as_deref(), Some("shortcut is occupied"));
        assert_eq!(snapshot.retry_attempts, 1);
        assert_eq!(
            supervisor.next_retry_delay(),
            Some(std::time::Duration::from_secs(
                HOTKEY_SUPERVISOR_RETRY_DELAY_SECS
            ))
        );
    }

    #[test]
    fn hotkey_supervisor_stops_fast_retry_after_configured_attempts() {
        let supervisor = HotkeySupervisor::default();

        for attempt in 0..=HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT {
            let generation = supervisor.begin_registration_attempt();
            supervisor
                .record_registration_failure(generation, format!("registration failed {attempt}"));
        }

        let snapshot = supervisor.snapshot();
        assert_eq!(
            snapshot.retry_attempts,
            HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT + 1
        );
        assert_eq!(supervisor.next_retry_delay(), None);
    }

    #[test]
    fn hotkey_supervisor_settings_change_resets_retry_window() {
        let supervisor = HotkeySupervisor::default();
        let first_generation = supervisor.snapshot().generation;
        supervisor.record_registration_failure(first_generation, "occupied".to_string());

        let next_generation = supervisor.wake_for_settings_change();
        let snapshot = supervisor.snapshot();

        assert!(next_generation > first_generation);
        assert_eq!(snapshot.state, HotkeySupervisorState::Starting);
        assert_eq!(snapshot.retry_attempts, 0);
        assert_eq!(snapshot.last_error, None);
        assert_eq!(supervisor.next_retry_delay(), None);
    }

    #[test]
    fn hotkey_supervisor_generation_guard_rejects_old_generation() {
        let supervisor = HotkeySupervisor::default();
        let old_generation = supervisor.snapshot().generation;

        assert!(supervisor.is_current_generation(old_generation));

        let next_generation = supervisor.wake_for_settings_change();

        assert!(!supervisor.is_current_generation(old_generation));
        assert!(supervisor.is_current_generation(next_generation));
    }

    #[test]
    fn hotkey_supervisor_run_if_current_generation_runs_matching_closure() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.snapshot().generation;

        let result = supervisor.run_if_current_generation(generation, || "registered");

        assert_eq!(result, Some("registered"));
    }

    #[test]
    fn hotkey_supervisor_run_if_current_generation_skips_superseded_closure() {
        let supervisor = HotkeySupervisor::default();
        let stale_generation = supervisor.snapshot().generation;
        supervisor.wake_for_settings_change();
        let mut called = false;

        let result = supervisor.run_if_current_generation(stale_generation, || {
            called = true;
        });

        assert_eq!(result, None);
        assert!(!called);
    }

    #[test]
    fn hotkey_supervisor_disable_invalidates_in_flight_generation() {
        let supervisor = HotkeySupervisor::default();
        let in_flight_generation = supervisor.snapshot().generation;

        let disabled_generation = supervisor.disable();

        assert!(!supervisor.is_current_generation(in_flight_generation));
        assert!(supervisor.is_current_generation(disabled_generation));
        assert_eq!(supervisor.snapshot().state, HotkeySupervisorState::Disabled);
        assert_eq!(supervisor.next_retry_delay(), None);
    }

    #[test]
    fn hotkey_supervisor_success_clears_last_error() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.snapshot().generation;
        supervisor.record_registration_failure(generation, "occupied".to_string());
        let retry_generation = supervisor.begin_registration_attempt();

        supervisor.record_registration_success(retry_generation);

        let snapshot = supervisor.snapshot();
        assert_eq!(snapshot.state, HotkeySupervisorState::Installed);
        assert_eq!(snapshot.retry_attempts, 0);
        assert_eq!(snapshot.last_error, None);
    }
}
