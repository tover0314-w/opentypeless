use crate::commands;
use crate::native_hotkey::NativeHotkeyTrigger;
use crate::pipeline;
use crate::storage;
use crate::AskHotkeyCache;
use crate::HotkeyRoleCache;
use crate::SessionTokenStore;
#[cfg(test)]
use std::sync::Arc;
use std::sync::Mutex;
use std::time::Duration;
use tauri::Emitter;
use tauri::Manager;
use tauri_plugin_global_shortcut::{Code, Modifiers, Shortcut, ShortcutState};

#[derive(Default)]
pub struct HotkeyConfigTransaction(pub tokio::sync::Mutex<()>);

pub use crate::hotkey_controller::{
    HotkeySupervisor, HotkeySupervisorSnapshot, HotkeySupervisorState,
    HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT, HOTKEY_SUPERVISOR_RETRY_DELAY_SECS,
};

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
    PasteLast,
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
            Self::PasteLast => "pasteLast",
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
                | NativeHotkeyTrigger::FnKey(_)
        ),
        "windows" => matches!(
            trigger,
            NativeHotkeyTrigger::RightAlt
                | NativeHotkeyTrigger::RightAltSpace
                | NativeHotkeyTrigger::RightAltLeftShift
                | NativeHotkeyTrigger::RightAltRightShift
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
        ("fn" | "function", key) if key.len() == 1 && key.as_bytes()[0].is_ascii_alphanumeric() => {
            Some(NativeHotkeyTrigger::FnKey(
                key.chars().next()?.to_ascii_uppercase(),
            ))
        }
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
        (
            "rightalt" | "right_alt" | "right-alt" | "altright" | "alt_right" | "alt-right",
            "rightshift" | "right_shift" | "right-shift" | "shiftright",
        ) => Some(NativeHotkeyTrigger::RightAltRightShift),
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
    push_optional_registered_hotkey(
        &mut plan,
        HotkeyRole::PasteLast,
        config.paste_last.as_ref(),
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
        paste_last: None,
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

fn show_ask_error_window<R: tauri::Runtime>(handle: &tauri::AppHandle<R>, message: String) {
    if message == commands::ask::ASK_CANCELLED {
        return;
    }
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

/// Holding a voice key longer than this in auto mode is treated as a mistaken push-to-talk
/// attempt rather than a tap (Typeless `LONG_PRESS_MS`).
pub const AUTO_MODE_LONG_PRESS: Duration = Duration::from_millis(600);

#[cfg(test)]
#[derive(Default)]
struct AutoPress {
    generation: u64,
    /// Last key-down while the key is believed held; cleared on release.
    last_key_down: Option<std::time::Instant>,
    pressed_at: Option<std::time::Instant>,
    long_held: bool,
}

/// OS auto-repeat fires every ~30–100 ms; a longer gap means a release was lost and the
/// key-down is a genuine new press.
#[cfg(test)]
const KEY_REPEAT_WINDOW: Duration = Duration::from_millis(1000);

#[cfg(test)]
#[derive(Clone, Copy, Debug, Eq, PartialEq)]
pub struct PressRelease {
    pub held: Duration,
    /// The long-press timer fired and the shortcut guide is showing.
    pub long_held: bool,
}

/// Tracks the press that started an auto-mode session so the long-press timer and the release
/// are classified against the same press.
#[cfg(test)]
#[derive(Default)]
pub struct AutoPressTracker(Mutex<AutoPress>);

#[cfg(test)]
impl AutoPressTracker {
    fn lock(&self) -> std::sync::MutexGuard<'_, AutoPress> {
        self.0.lock().unwrap_or_else(|e| e.into_inner())
    }

    /// Records a key-down; returns true when it is an OS auto-repeat of a key still held.
    pub fn begin_press(&self, now: std::time::Instant) -> bool {
        let mut press = self.lock();
        let repeat = press
            .last_key_down
            .is_some_and(|last| now.saturating_duration_since(last) < KEY_REPEAT_WINDOW);
        press.last_key_down = Some(now);
        repeat
    }

    /// Records a session-starting press and returns its generation for the long-press timer.
    pub fn mark_pressed(&self, at: std::time::Instant) -> u64 {
        let mut press = self.lock();
        press.generation += 1;
        press.pressed_at = Some(at);
        press.long_held = false;
        press.generation
    }

    /// Returns true exactly once when press `generation` is still held at the threshold.
    pub fn claim_long_hold(&self, generation: u64) -> bool {
        let mut press = self.lock();
        if press.generation != generation || press.pressed_at.is_none() || press.long_held {
            return false;
        }
        press.long_held = true;
        true
    }

    fn publish_long_hold(&self, generation: u64, publish: impl FnOnce()) -> bool {
        let press = self.lock();
        if press.generation != generation || press.pressed_at.is_none() || !press.long_held {
            return false;
        }
        publish();
        true
    }

    pub fn take_release(&self, now: std::time::Instant) -> Option<PressRelease> {
        let mut press = self.lock();
        press.last_key_down = None;
        let pressed_at = press.pressed_at.take()?;
        Some(PressRelease {
            held: now.saturating_duration_since(pressed_at),
            long_held: std::mem::take(&mut press.long_held),
        })
    }

    pub fn clear(&self) {
        let mut press = self.lock();
        press.pressed_at = None;
        press.long_held = false;
    }

    pub fn reset_for_lifecycle(&self) {
        let mut press = self.lock();
        press.generation = press.generation.wrapping_add(1);
        press.last_key_down = None;
        press.pressed_at = None;
        press.long_held = false;
    }
}

fn emit_capsule_alert(handle: &tauri::AppHandle, kind: &str) {
    let _ = handle.emit("capsule:alert", serde_json::json!({ "kind": kind }));
}

async fn stop_ask_shortcut(handle: tauri::AppHandle, session_id: u64) {
    let ask_state = handle.state::<commands::ask::AskDictationState>();
    let token_store = handle.state::<SessionTokenStore>();
    let client = handle.state::<reqwest::Client>();

    match commands::ask::stop_owned_ask_dictation(
        handle.clone(),
        ask_state,
        token_store,
        client,
        session_id,
    )
    .await
    {
        Ok(result) if result.should_show_window() => show_ask_result_window(&handle, &result),
        Ok(_) => {}
        Err(message)
            if message == "Ask dictation is not recording"
                || message == commands::ask::ASK_CANCELLED => {}
        Err(message) => show_ask_error_window(&handle, message),
    }
}

/// Typeless shows a narrow waveform-only bar for push-to-talk and adds the cancel / finish
/// buttons only for hands-free takes, where the user needs a way to end the recording.
fn emit_recording_style(handle: &tauri::AppHandle, mode: crate::input_session::InputMode) {
    let style = if mode == crate::input_session::InputMode::Hold {
        "pushToTalk"
    } else {
        "handsFree"
    };
    let _ = handle.emit("capsule:recording_style", style);
}

enum RecordingCommand {
    Start {
        session_id: u64,
        binding: crate::input_session::InputBinding,
        config: Box<storage::AppConfig>,
    },
    Stop {
        session_id: u64,
    },
    Cancel {
        session_id: u64,
    },
    ExternalAskStop {
        snapshot: commands::ask::AskShortcutSnapshot,
    },
}

pub(crate) struct RecordingCommandQueue {
    queue: crate::input_command_queue::InputCommandQueue<RecordingCommand>,
    session: std::sync::Arc<
        Mutex<
            crate::input_configuration::InputConfiguration<
                storage::HotkeyConfig,
                storage::AppConfig,
            >,
        >,
    >,
    executing_session: std::sync::Arc<std::sync::atomic::AtomicU64>,
    ask_owner: std::sync::Arc<Mutex<Option<(u64, u64)>>>,
    queued_operation:
        std::sync::Arc<Mutex<Option<(u64, crate::voice_operation::VoiceOperationToken)>>>,
    clock: std::time::Instant,
}

impl RecordingCommandQueue {
    fn invalidate(&self) {
        self.queue.invalidate();
        let mut session = self
            .session
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if let Some(session_id) = session.session_id() {
            session.cancel(session_id);
        }
    }

    pub(crate) fn shutdown(&self) {
        self.queue.shutdown();
        self.session
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .shutdown();
    }
}

pub(crate) fn invalidate_pending_recording_commands(app: &tauri::AppHandle) {
    if let Some(queue) = app.try_state::<RecordingCommandQueue>() {
        queue.invalidate();
        let session_id = queue
            .ask_owner
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .map(|(session_id, _)| session_id);
        if let Some(session_id) = session_id {
            cancel_queued_ask(app, session_id, &queue.ask_owner);
        }
    }
}

pub(crate) fn stage_input_installation(
    app: &tauri::AppHandle,
    generation: u64,
    hotkeys: storage::HotkeyConfig,
    plan: &HotkeyRegistrationPlan,
    results: &[crate::hotkey_controller::BindingRegistration],
) {
    use crate::input_session::{InputBinding, InputMode, VoiceRole};
    let Some(runtime) = app.try_state::<RecordingCommandQueue>() else {
        return;
    };
    let Some(mode) = InputMode::from_config(&hotkeys.dictation_mode) else {
        return;
    };
    let mut bindings = Vec::new();
    let mut installed = Vec::new();
    for (role, index, binding_id) in plan
        .global
        .iter()
        .map(|binding| {
            (
                binding.role,
                binding.index,
                format!("{:?}", binding.shortcut),
            )
        })
        .chain(
            plan.native
                .iter()
                .map(|binding| (binding.role, binding.index, binding.trigger.canonical())),
        )
    {
        let voice_role = match role {
            HotkeyRole::Dictation => VoiceRole::Dictation,
            HotkeyRole::TranslateSelection => VoiceRole::Translate,
            HotkeyRole::Ask => VoiceRole::Ask,
            HotkeyRole::EditSelection => VoiceRole::EditSelection,
            _ => continue,
        };
        if results.iter().any(|result| {
            result.role == role.as_str() && result.index == index && result.error.is_none()
        }) {
            installed.push(binding_id.clone());
        }
        bindings.push(InputBinding {
            role: voice_role,
            binding_id,
            mode,
            revision: 0,
        });
    }
    runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .stage_installation(generation, hotkeys, bindings, installed);
}

pub(crate) fn publish_saved_input_configuration<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    generation: u64,
) {
    let Some(runtime) = app.try_state::<RecordingCommandQueue>() else {
        return;
    };
    let Some(manager) = app.try_state::<storage::ConfigManager>() else {
        return;
    };
    let Ok((saved_revision, config)) = manager.cached_input_commit() else {
        return;
    };
    let hotkeys = crate::commands::misc::effective_hotkey_config(&config);
    let mut session = runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    let recovery_actions;
    if session.requires_release_observation() {
        #[cfg(target_os = "macos")]
        {
            recovery_actions = session
                .observe_physical_keys(crate::shortcut_release::physical_key_snapshot().ok());
        }
        #[cfg(not(target_os = "macos"))]
        {
            recovery_actions = Vec::new();
        }
        session.resume();
    } else {
        recovery_actions = Vec::new();
    }
    let published = session.publish_saved_revision(generation, saved_revision, &hotkeys, config);
    drop(session);
    emit_input_guides(app, recovery_actions);
    if published {
        if let (Some(cache), Ok(plan)) = (
            app.try_state::<HotkeyRoleCache>(),
            hotkey_registration_plan_from_config(&hotkeys),
        ) {
            *cache.0.lock().unwrap_or_else(|error| error.into_inner()) = plan;
        }
    }
}

pub(crate) fn suspend_input_session<R: tauri::Runtime>(app: &tauri::AppHandle<R>) {
    #[cfg(target_os = "macos")]
    if let Some(runtime) = app.try_state::<RecordingCommandQueue>() {
        runtime
            .session
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .suspend();
    }
    #[cfg(not(target_os = "macos"))]
    let _ = app;
}

#[cfg(target_os = "macos")]
pub(crate) fn observe_input_physical_keys(app: &tauri::AppHandle, keys: Option<Vec<u16>>) {
    if let Some(runtime) = app.try_state::<RecordingCommandQueue>() {
        let mut session = runtime
            .session
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        let actions = if session.requires_release_observation() {
            session.observe_physical_keys(keys)
        } else {
            Vec::new()
        };
        drop(session);
        emit_input_guides(app, actions);
    }
}

fn emit_input_guides<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    actions: Vec<crate::input_session::InputAction>,
) {
    for action in actions {
        if let crate::input_session::InputAction::HideGuide { press_id } = action {
            let _ = app.emit(
                "capsule:alert_dismiss",
                serde_json::json!({"kind":"shortcut_guide", "pressId":press_id}),
            );
        }
    }
}

pub(crate) fn refresh_committed_input_configuration(
    app: &tauri::AppHandle,
    saved_revision: u64,
    config: &storage::AppConfig,
) {
    let Some(runtime) = app.try_state::<RecordingCommandQueue>() else {
        return;
    };
    let snapshot = config.clone();
    let hotkeys = crate::commands::misc::effective_hotkey_config(&snapshot);
    runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .refresh_saved_snapshot(saved_revision, &hotkeys, snapshot);
}

pub(crate) fn input_configuration_busy(app: &tauri::AppHandle) -> bool {
    if app
        .try_state::<commands::ask::AskDictationState>()
        .is_some_and(|ask| ask.is_busy())
    {
        return true;
    }
    app.try_state::<RecordingCommandQueue>()
        .is_some_and(|runtime| {
            let session = runtime
                .session
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            session.session_id().is_some() || session.press_id().is_some()
        })
}

pub(crate) fn input_monitor_lost(app: &tauri::AppHandle) {
    let Some(runtime) = app.try_state::<RecordingCommandQueue>() else {
        return;
    };
    let mut session = runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if let Some(revision) = session.active_revision() {
        session.monitor_lost(revision);
    }
}

pub(crate) fn input_configuration_needs_installation(
    app: &tauri::AppHandle,
    config: &storage::AppConfig,
) -> bool {
    app.try_state::<RecordingCommandQueue>()
        .is_some_and(|runtime| {
            runtime
                .session
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .needs_installation(&crate::commands::misc::effective_hotkey_config(config))
        })
}

pub(crate) fn saved_input_revision_is_active(
    app: &tauri::AppHandle,
    revision: u64,
    config: &storage::AppConfig,
) -> bool {
    app.try_state::<RecordingCommandQueue>()
        .is_some_and(|runtime| {
            runtime
                .session
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .saved_revision_is_active(
                    revision,
                    &crate::commands::misc::effective_hotkey_config(config),
                )
        })
}

pub(crate) fn pending_input_configuration_publication(
    app: &tauri::AppHandle,
    config: &storage::AppConfig,
) -> Option<u64> {
    let runtime = app.try_state::<RecordingCommandQueue>()?;
    let session = runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    session.pending_publication(&crate::commands::misc::effective_hotkey_config(config))
}

type SharedInputConfiguration = std::sync::Arc<
    Mutex<
        crate::input_configuration::InputConfiguration<storage::HotkeyConfig, storage::AppConfig>,
    >,
>;
type SharedAskOwner = std::sync::Arc<Mutex<Option<(u64, u64)>>>;

pub(crate) struct InputInstallationReservation {
    session: SharedInputConfiguration,
    identity: u64,
    voice_start_guard: Option<tokio::sync::OwnedMutexGuard<()>>,
}

impl Drop for InputInstallationReservation {
    fn drop(&mut self) {
        let mut session = self
            .session
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        drop(self.voice_start_guard.take());
        session.release_installation(self.identity);
    }
}

pub(crate) fn reserve_input_installation(
    app: &tauri::AppHandle,
) -> Option<InputInstallationReservation> {
    let runtime = app.try_state::<RecordingCommandQueue>()?;
    let pipeline = app.state::<pipeline::PipelineHandle>();
    let voice_start_guard = pipeline.try_voice_start_guard().ok()?;
    let mut session = runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if app.state::<commands::ask::AskDictationState>().is_busy()
        || pipeline.current_state() != pipeline::PipelineState::Idle
    {
        return None;
    }
    let identity = session.reserve_installation()?;
    Some(InputInstallationReservation {
        session: runtime.session.clone(),
        identity,
        voice_start_guard: Some(voice_start_guard),
    })
}

fn cancel_queued_ask(handle: &tauri::AppHandle, session_id: u64, owner: &SharedAskOwner) {
    let generation = {
        let mut owner = owner.lock().unwrap_or_else(|error| error.into_inner());
        if owner.is_none_or(|(current, _)| current != session_id) {
            return;
        }
        owner.take().map(|(_, generation)| generation)
    };
    if let Some(generation) = generation {
        if handle
            .state::<commands::ask::AskDictationState>()
            .abort_owned_startup(generation)
        {
            commands::ask::emit_capsule_state(handle, pipeline::PipelineState::Idle);
        }
    }
}

fn cancel_queued_input(handle: &tauri::AppHandle, session_id: u64) {
    let runtime = handle.state::<RecordingCommandQueue>();
    cancel_queued_ask(handle, session_id, &runtime.ask_owner);
    let operation = runtime
        .queued_operation
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .as_ref()
        .filter(|(owner, _)| *owner == session_id)
        .map(|(_, operation)| operation.clone());
    if let Some(operation) = operation {
        handle
            .state::<pipeline::PipelineHandle>()
            .abort_owned_input(&operation);
    }
}

async fn stop_queued_ask(
    handle: &tauri::AppHandle,
    session_id: u64,
    session: &SharedInputConfiguration,
    owner: &SharedAskOwner,
) {
    let generation = owner
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .filter(|(current, _)| *current == session_id)
        .map(|(_, generation)| generation);
    let Some(generation) = generation else {
        return;
    };
    if let Some(recording_id) = handle
        .state::<commands::ask::AskDictationState>()
        .recording_session_id_for_startup(generation)
    {
        stop_ask_shortcut(handle.clone(), recording_id).await;
    }
    session
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .finish(session_id);
    let mut owner = owner.lock().unwrap_or_else(|error| error.into_inner());
    if owner.is_some_and(|(current, _)| current == session_id) {
        *owner = None;
    }
}

async fn start_queued_ask(
    handle: &tauri::AppHandle,
    session_id: u64,
    binding: crate::input_session::InputBinding,
    config: storage::AppConfig,
    session: &SharedInputConfiguration,
    owner: &SharedAskOwner,
) {
    let generation = {
        let mut session = session.lock().unwrap_or_else(|error| error.into_inner());
        if session.session_id() != Some(session_id) {
            return;
        }
        let ask_state = handle.state::<commands::ask::AskDictationState>();
        let Some(generation) = ask_state.reserve_startup() else {
            session.finish(session_id);
            return;
        };
        *owner.lock().unwrap_or_else(|error| error.into_inner()) = Some((session_id, generation));
        generation
    };
    emit_recording_style(handle, crate::input_session::InputMode::Toggle);
    let result = commands::ask::start_reserved_ask_with_config(
        handle.clone(),
        handle.state(),
        config,
        handle.state(),
        handle.state(),
        true,
        generation,
    )
    .await;
    if let Err(message) = result {
        session
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .finish(session_id);
        let mut owner = owner.lock().unwrap_or_else(|error| error.into_inner());
        if owner.is_some_and(|(current, _)| current == session_id) {
            *owner = None;
        }
        drop(owner);
        if message != commands::ask::ASK_CANCELLED {
            show_ask_error_window(handle, message);
        }
        return;
    }
    let actions = {
        let mut session = session.lock().unwrap_or_else(|error| error.into_inner());
        if !session.owns_session_binding(&binding.binding_id, crate::input_session::VoiceRole::Ask)
            || session.session_id() != Some(session_id)
        {
            drop(session);
            cancel_queued_ask(handle, session_id, owner);
            return;
        }
        session.ready(session_id)
    };
    let input_stop_requested = actions.iter().any(|action| {
        matches!(action, crate::input_session::InputAction::Stop { session_id: current } if *current == session_id)
    });
    let external_stop_requested = handle
        .state::<commands::ask::AskDictationState>()
        .take_stop_after_start_for(generation);
    if input_stop_requested || external_stop_requested {
        stop_queued_ask(handle, session_id, session, owner).await;
    }
}

pub(crate) fn ask_input_finished(handle: &tauri::AppHandle, generation: u64) {
    let Some(runtime) = handle.try_state::<RecordingCommandQueue>() else {
        return;
    };
    let mut session = runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    let mut owner = runtime
        .ask_owner
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if let Some((session_id, current)) = *owner {
        if current == generation {
            *owner = None;
            session.finish(session_id);
        }
    }
}

pub(crate) fn install_recording_command_queue(app: &tauri::AppHandle) {
    let handle = app.clone();
    let session = std::sync::Arc::new(Mutex::new(
        crate::input_configuration::InputConfiguration::default(),
    ));
    let worker_session = session.clone();
    let executing_session = std::sync::Arc::new(std::sync::atomic::AtomicU64::new(0));
    let worker_executing = executing_session.clone();
    let ask_owner = std::sync::Arc::new(Mutex::new(None));
    let worker_ask_owner = ask_owner.clone();
    let queued_operation = std::sync::Arc::new(Mutex::new(
        None::<(u64, crate::voice_operation::VoiceOperationToken)>,
    ));
    let worker_queued_operation = queued_operation.clone();
    let queue = tauri::async_runtime::block_on(async {
        crate::input_command_queue::InputCommandQueue::new(32, move |command| {
            let handle = handle.clone();
            let queued_operation = worker_queued_operation.clone();
            let session = worker_session.clone();
            let executing_session = worker_executing.clone();
            let ask_owner = worker_ask_owner.clone();
            async move {
                let pipeline = handle.state::<pipeline::PipelineHandle>();
                let result = match command {
                    RecordingCommand::Start {
                        session_id,
                        binding,
                        config,
                    } => {
                        if binding.role == crate::input_session::VoiceRole::Ask {
                            start_queued_ask(
                                &handle, session_id, binding, *config, &session, &ask_owner,
                            )
                            .await;
                            return;
                        }
                        if session
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .session_id()
                            != Some(session_id)
                        {
                            return;
                        }
                        if handle.state::<commands::ask::AskDictationState>().is_busy()
                            || pipeline.current_state() != pipeline::PipelineState::Idle
                        {
                            session
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .finish(session_id);
                            return;
                        }
                        executing_session.store(session_id, std::sync::atomic::Ordering::SeqCst);
                        *queued_operation
                            .lock()
                            .unwrap_or_else(|error| error.into_inner()) = None;
                        emit_recording_style(&handle, binding.mode);
                        let claimed_operation = queued_operation.clone();
                        let claimed_session = session.clone();
                        let result = pipeline
                            .start_with_owned_config(
                                *config,
                                pipeline::PipelineStartOptions {
                                    force_translate: binding.role
                                        == crate::input_session::VoiceRole::Translate,
                                    edit_selection: binding.role
                                        == crate::input_session::VoiceRole::EditSelection,
                                },
                                move |operation| {
                                    let session = claimed_session
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner());
                                    if session.session_id() != Some(session_id) {
                                        return false;
                                    }
                                    *claimed_operation
                                        .lock()
                                        .unwrap_or_else(|error| error.into_inner()) =
                                        Some((session_id, operation));
                                    true
                                },
                            )
                            .await;
                        let operation = if result.is_ok() {
                            queued_operation
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .as_ref()
                                .filter(|(owner, operation)| {
                                    *owner == session_id && !operation.is_cancelled()
                                })
                                .map(|(_, operation)| operation.clone())
                        } else {
                            None
                        };
                        if let Some(operation) = operation {
                            let ready_actions = session
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .ready(session_id);
                            if ready_actions.iter().any(|action| matches!(
                                action, crate::input_session::InputAction::Stop { session_id: stop_id } if *stop_id == session_id
                            )) {
                                let stopped = pipeline.stop_operation(operation).await;
                                session.lock().unwrap_or_else(|error| error.into_inner()).finish(session_id);
                                stopped
                            } else {
                                result
                            }
                        } else {
                            session
                                .lock()
                                .unwrap_or_else(|error| error.into_inner())
                                .finish(session_id);
                            result
                        }
                    }
                    RecordingCommand::Stop { session_id } => {
                        if ask_owner
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .is_some_and(|(owner, _)| owner == session_id)
                        {
                            stop_queued_ask(&handle, session_id, &session, &ask_owner).await;
                            return;
                        }
                        let operation = queued_operation
                            .lock()
                            .unwrap_or_else(|error| error.into_inner())
                            .as_ref()
                            .filter(|(owner, _)| *owner == session_id)
                            .map(|(_, operation)| operation.clone());
                        match operation {
                            Some(operation) => {
                                let result = pipeline.stop_operation(operation).await;
                                session
                                    .lock()
                                    .unwrap_or_else(|error| error.into_inner())
                                    .finish(session_id);
                                result
                            }
                            None => Ok(()),
                        }
                    }
                    RecordingCommand::Cancel { session_id } => {
                        cancel_queued_input(&handle, session_id);
                        Ok(())
                    }
                    RecordingCommand::ExternalAskStop { snapshot } => {
                        let recording_id = if let Some(generation) = snapshot.startup_generation {
                            match handle
                                .state::<commands::ask::AskDictationState>()
                                .request_owned_stop(generation)
                            {
                                crate::ask_stop_request::AskStopDisposition::Recording(
                                    recording_id,
                                ) => Some(recording_id),
                                _ => None,
                            }
                        } else {
                            snapshot.recording_session_id
                        };
                        if let Some(recording_id) = recording_id {
                            stop_ask_shortcut(handle.clone(), recording_id).await;
                        }
                        Ok(())
                    }
                };
                if let Err(error) = result {
                    tracing::error!("Recording command failed");
                    let _ = handle.emit("pipeline:error", error.to_string());
                }
            }
        })
    });
    app.manage(RecordingCommandQueue {
        queue,
        session,
        executing_session,
        ask_owner,
        queued_operation,
        clock: std::time::Instant::now(),
    });
}

fn dispatch_input_actions(
    handle: &tauri::AppHandle,
    actions: Vec<crate::input_session::InputAction>,
) -> bool {
    let mut accepted = true;
    use crate::input_session::InputAction;
    for action in actions {
        let command = match action {
            InputAction::Start {
                session_id,
                binding,
            } => {
                let runtime = handle.state::<RecordingCommandQueue>();
                let config = {
                    let session = runtime
                        .session
                        .lock()
                        .unwrap_or_else(|error| error.into_inner());
                    if session.session_id() != Some(session_id) {
                        accepted = false;
                        continue;
                    }
                    session
                        .owned_snapshot(session_id, binding.revision)
                        .cloned()
                };
                let Some(config) = config else {
                    accepted = false;
                    invalidate_pending_recording_commands(handle);
                    let _ = handle.emit("pipeline:error", "Recording configuration is unavailable");
                    continue;
                };
                RecordingCommand::Start {
                    session_id,
                    binding,
                    config: Box::new(config),
                }
            }
            InputAction::Stop { session_id } => RecordingCommand::Stop { session_id },
            InputAction::Cancel { session_id } => {
                cancel_queued_input(handle, session_id);
                RecordingCommand::Cancel { session_id }
            }
            InputAction::ShowGuide { press_id } | InputAction::HideGuide { press_id } => {
                let showing = matches!(action, InputAction::ShowGuide { .. });
                let _ = handle.emit(
                    if showing {
                        "capsule:alert"
                    } else {
                        "capsule:alert_dismiss"
                    },
                    serde_json::json!({"kind":"shortcut_guide", "pressId":press_id}),
                );
                continue;
            }
        };
        if let Err(error) = handle
            .state::<RecordingCommandQueue>()
            .queue
            .submit(command)
        {
            accepted = false;
            invalidate_pending_recording_commands(handle);
            let _ = handle.emit("pipeline:error", error);
        }
    }
    accepted
}

pub(crate) fn recording_input_finished(handle: &tauri::AppHandle) {
    if let Some(runtime) = handle.try_state::<RecordingCommandQueue>() {
        let session_id = runtime
            .executing_session
            .swap(0, std::sync::atomic::Ordering::SeqCst);
        if session_id != 0 {
            runtime
                .session
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .finish(session_id);
        }
    }
}

fn handle_dictation_input(
    handle: &tauri::AppHandle,
    role: HotkeyRole,
    binding_id: &str,
    event_state: ShortcutState,
    occurred_at: std::time::Instant,
    parent_binding_id: Option<&str>,
) -> bool {
    use crate::input_session::VoiceRole;
    let runtime = handle.state::<RecordingCommandQueue>();
    let now_ms = occurred_at
        .saturating_duration_since(runtime.clock)
        .as_millis() as u64;
    let mut session = runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    if event_state == ShortcutState::Pressed
        && session.session_id().is_none()
        && (handle.state::<pipeline::PipelineHandle>().current_state()
            != pipeline::PipelineState::Idle
            || handle.state::<commands::ask::AskDictationState>().is_busy())
    {
        drop(session);
        emit_capsule_alert(handle, "previous_pending");
        return false;
    }
    let previous_press = session.press_id();
    let actions = match event_state {
        ShortcutState::Pressed => {
            let voice_role = match role {
                HotkeyRole::TranslateSelection => VoiceRole::Translate,
                HotkeyRole::Ask => VoiceRole::Ask,
                HotkeyRole::EditSelection => VoiceRole::EditSelection,
                _ => VoiceRole::Dictation,
            };
            match parent_binding_id {
                Some(parent) => {
                    session.press_combo_committed(parent, binding_id, voice_role, now_ms)
                }
                None => session.press_committed(binding_id, voice_role, now_ms),
            }
        }
        ShortcutState::Released => session.release(binding_id, now_ms),
    };
    let accepted = !actions.is_empty()
        || (event_state == ShortcutState::Pressed
            && session.press_id().is_some()
            && session.press_id() != previous_press)
        || (event_state == ShortcutState::Released
            && previous_press.is_some()
            && session.press_id() != previous_press);
    let guide_timer = session.press_id().and_then(|press_id| {
        session
            .guide_delay(press_id, runtime.clock.elapsed().as_millis() as u64)
            .map(|delay_ms| (press_id, delay_ms))
    });
    drop(session);
    let dispatched = dispatch_input_actions(handle, actions);
    if event_state == ShortcutState::Pressed {
        if let Some((press_id, delay_ms)) = guide_timer {
            let handle = handle.clone();
            tauri::async_runtime::spawn(async move {
                tokio::time::sleep(Duration::from_millis(delay_ms)).await;
                let runtime = handle.state::<RecordingCommandQueue>();
                let actions = runtime
                    .session
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .long_press_due(press_id, runtime.clock.elapsed().as_millis() as u64);
                dispatch_input_actions(&handle, actions);
            });
        }
    }
    accepted && dispatched
}

fn handle_advanced_role_shortcut(
    handle: tauri::AppHandle,
    role: HotkeyRole,
    event_state: ShortcutState,
) {
    dispatch_advanced_role_shortcut(
        role,
        event_state,
        || crate::restore_main_window(&handle),
        |role| {
            if role == HotkeyRole::SwitchScene {
                let app = handle.clone();
                tauri::async_runtime::spawn(async move {
                    if let Err(error) = commands::config::cycle_scene_from_shortcut(&app).await {
                        tracing::warn!("Shortcut scene switch failed: {error}");
                    }
                });
                return;
            }
            let _ = handle.emit("hotkey:role", role.as_str());
        },
    );
}

fn dispatch_advanced_role_shortcut(
    role: HotkeyRole,
    event_state: ShortcutState,
    open_main: impl FnOnce(),
    emit_role: impl FnOnce(HotkeyRole),
) {
    if event_state != ShortcutState::Pressed {
        return;
    }
    if role == HotkeyRole::OpenApp {
        open_main();
        return;
    }
    emit_role(role);
}

fn handle_active_ask_input(
    handle: &tauri::AppHandle,
    event_state: ShortcutState,
    occurred_at: std::time::Instant,
) -> Option<bool> {
    let snapshot = handle
        .state::<commands::ask::AskDictationState>()
        .shortcut_snapshot();
    if snapshot.recording_session_id.is_none() && snapshot.startup_generation.is_none() {
        return None;
    }
    let runtime = handle.state::<RecordingCommandQueue>();
    let owned_binding = runtime
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .session_binding()
        .filter(|binding| binding.role == crate::input_session::VoiceRole::Ask)
        .map(|binding| binding.binding_id.clone());
    if let Some(owned_binding) = owned_binding {
        return Some(handle_dictation_input(
            handle,
            HotkeyRole::Ask,
            &owned_binding,
            event_state,
            occurred_at,
            None,
        ));
    } else if event_state == ShortcutState::Pressed && snapshot.busy {
        if let Err(error) = runtime
            .queue
            .submit(RecordingCommand::ExternalAskStop { snapshot })
        {
            let _ = handle.emit("pipeline:error", error);
            return Some(false);
        }
        return Some(true);
    }
    Some(false)
}

pub fn handle_hotkey_role_event(
    handle: tauri::AppHandle,
    role: HotkeyRole,
    event_state: ShortcutState,
) {
    handle_hotkey_binding_event(handle, role, role.as_str(), event_state);
}

pub(crate) fn handle_hotkey_binding_event(
    handle: tauri::AppHandle,
    role: HotkeyRole,
    binding_id: &str,
    event_state: ShortcutState,
) {
    handle_hotkey_binding_event_at(
        handle,
        role,
        binding_id,
        event_state,
        std::time::Instant::now(),
    );
}

pub(crate) fn handle_hotkey_binding_event_at(
    handle: tauri::AppHandle,
    role: HotkeyRole,
    binding_id: &str,
    event_state: ShortcutState,
    occurred_at: std::time::Instant,
) {
    handle_input_binding_event_at(handle, role, binding_id, event_state, occurred_at, None);
}

pub(crate) fn handle_input_binding_event_at(
    handle: tauri::AppHandle,
    role: HotkeyRole,
    binding_id: &str,
    event_state: ShortcutState,
    occurred_at: std::time::Instant,
    parent_binding_id: Option<&str>,
) -> bool {
    if event_state == ShortcutState::Pressed {
        if let Some(runtime) = handle.try_state::<RecordingCommandQueue>() {
            if runtime
                .session
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .block_installation_press(binding_id)
            {
                return false;
            }
        }
    }
    if event_state == ShortcutState::Released {
        if let Some(runtime) = handle.try_state::<RecordingCommandQueue>() {
            let mut session = runtime
                .session
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if session.owns_installation_blocked_release(binding_id) {
                session.release(
                    binding_id,
                    occurred_at
                        .saturating_duration_since(runtime.clock)
                        .as_millis() as u64,
                );
                return false;
            }
        }
        let owned_release = handle
            .try_state::<RecordingCommandQueue>()
            .is_some_and(|runtime| {
                let voice_role = match role {
                    HotkeyRole::Dictation => crate::input_session::VoiceRole::Dictation,
                    HotkeyRole::TranslateSelection => crate::input_session::VoiceRole::Translate,
                    HotkeyRole::Ask => crate::input_session::VoiceRole::Ask,
                    HotkeyRole::EditSelection => crate::input_session::VoiceRole::EditSelection,
                    _ => return false,
                };
                runtime
                    .session
                    .lock()
                    .unwrap_or_else(|error| error.into_inner())
                    .owns_suppressed_release(binding_id, voice_role)
            });
        if owned_release {
            handle_dictation_input(
                &handle,
                role,
                binding_id,
                event_state,
                occurred_at,
                parent_binding_id,
            );
            return false;
        }
    }
    let owned_completion = handle
        .try_state::<RecordingCommandQueue>()
        .is_some_and(|runtime| {
            let voice_role = match role {
                HotkeyRole::Dictation => crate::input_session::VoiceRole::Dictation,
                HotkeyRole::TranslateSelection => crate::input_session::VoiceRole::Translate,
                HotkeyRole::Ask => crate::input_session::VoiceRole::Ask,
                HotkeyRole::EditSelection => crate::input_session::VoiceRole::EditSelection,
                _ => return false,
            };
            runtime
                .session
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .owns_session_binding(binding_id, voice_role)
        });
    if handle
        .try_state::<HotkeySupervisor>()
        .is_some_and(|supervisor| {
            !(supervisor.role_actions_allowed(role.as_str())
                || owned_completion && supervisor.session_completion_allowed())
        })
    {
        tracing::debug!(
            role = role.as_str(),
            "Shortcut blocked by registration state"
        );
        return false;
    }
    tracing::debug!(role = role.as_str(), "Dispatching configured shortcut");
    if let Some(accepted) = crate::practice_shortcut::route(&handle, role, event_state) {
        return accepted;
    }
    match role {
        HotkeyRole::Ask => {
            if !owned_completion {
                if let Some(accepted) = handle_active_ask_input(&handle, event_state, occurred_at) {
                    return accepted;
                }
            }
            return handle_dictation_input(
                &handle,
                role,
                binding_id,
                event_state,
                occurred_at,
                parent_binding_id,
            );
        }
        HotkeyRole::TranslateSelection | HotkeyRole::EditSelection => {
            return handle_dictation_input(
                &handle,
                role,
                binding_id,
                event_state,
                occurred_at,
                parent_binding_id,
            );
        }
        HotkeyRole::Dictation => {
            if owned_completion {
                return handle_dictation_input(
                    &handle,
                    role,
                    binding_id,
                    event_state,
                    occurred_at,
                    parent_binding_id,
                );
            }
            if let Some(accepted) = handle_active_ask_input(&handle, event_state, occurred_at) {
                return accepted;
            }

            return handle_dictation_input(
                &handle,
                role,
                binding_id,
                event_state,
                occurred_at,
                parent_binding_id,
            );
        }
        HotkeyRole::PasteLast => {
            if event_state == ShortcutState::Pressed {
                tauri::async_runtime::spawn(crate::paste_last::paste_last_transcript(handle));
            }
        }
        role => handle_advanced_role_shortcut(handle, role, event_state),
    }
    event_state == ShortcutState::Pressed
}

pub fn build_shortcut_handler(
    app_handle: tauri::AppHandle,
) -> impl Fn(&tauri::AppHandle, &Shortcut, tauri_plugin_global_shortcut::ShortcutEvent)
       + Send
       + Sync
       + 'static {
    move |_app, shortcut, event| {
        let handle = app_handle.clone();
        if crate::escape_cancel::handle_shortcut(&handle, shortcut, event.state) {
            return;
        }
        let role = hotkey_role_for_shortcut(&handle, shortcut);
        handle_hotkey_binding_event(handle, role, &format!("{shortcut:?}"), event.state);
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
    fn advanced_open_app_runs_only_on_press_and_keeps_other_role_events() {
        let opened = std::cell::Cell::new(0);
        for edge in [ShortcutState::Pressed, ShortcutState::Released] {
            dispatch_advanced_role_shortcut(
                HotkeyRole::OpenApp,
                edge,
                || opened.set(opened.get() + 1),
                |_| panic!("OpenApp must execute instead of emitting an unhandled role"),
            );
        }
        assert_eq!(opened.get(), 1);
        for role in [HotkeyRole::SwitchScene, HotkeyRole::EditSelection] {
            let emitted = std::cell::Cell::new(None);
            dispatch_advanced_role_shortcut(
                role,
                ShortcutState::Pressed,
                || panic!("other roles must not open the application"),
                |received| emitted.set(Some(received)),
            );
            assert_eq!(emitted.get(), Some(role));
        }
    }

    #[test]
    fn lost_monitor_enters_recovery_only_for_current_installed_generation() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.snapshot().generation;
        assert!(!supervisor.record_monitor_failure(generation, "not installed".to_string()));
        supervisor.record_registration_success(generation);
        assert!(supervisor.record_monitor_failure(generation, "monitor exited".to_string()));
        assert_eq!(supervisor.snapshot().state, HotkeySupervisorState::Failed);
        assert_eq!(
            supervisor.snapshot().last_error.as_deref(),
            Some("monitor exited")
        );
        assert!(supervisor.next_retry_delay().is_some());
        assert!(!supervisor.record_monitor_failure(generation, "duplicate".to_string()));
        supervisor.record_registration_success(generation);
        let new_generation = supervisor.disable();
        assert!(!supervisor.record_monitor_failure(generation, "old monitor".to_string()));
        assert!(!supervisor.record_monitor_failure(new_generation, "editor paused".to_string()));
        assert_eq!(supervisor.snapshot().state, HotkeySupervisorState::Disabled);
    }

    #[tokio::test]
    async fn configuration_transaction_blocks_recovery_until_save_finishes() {
        let transaction = Arc::new(HotkeyConfigTransaction::default());
        let saving = transaction.0.lock().await;
        assert!(transaction.0.try_lock().is_err());
        let current = Arc::new(Mutex::new("old"));
        let recovery_transaction = Arc::clone(&transaction);
        let recovery_current = Arc::clone(&current);
        let recovery = tokio::spawn(async move {
            let _guard = recovery_transaction.0.lock().await;
            *recovery_current.lock().unwrap()
        });
        tokio::task::yield_now().await;
        assert!(!recovery.is_finished());
        *current.lock().unwrap() = "saved";
        drop(saving);
        assert_eq!(recovery.await.unwrap(), "saved");
    }

    #[test]
    fn cancelled_ask_shortcut_does_not_create_popup_but_real_failure_does() {
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .manage(commands::ask::AskDictationState::default())
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let received = Arc::new(Mutex::new(Vec::<String>::new()));
        let captured = received.clone();
        let listener = app.listen_any("ask:error", move |event| {
            captured
                .lock()
                .unwrap()
                .push(serde_json::from_str(event.payload()).unwrap());
        });
        show_ask_error_window(app.handle(), commands::ask::ASK_CANCELLED.into());
        assert!(app.get_webview_window("ask").is_none());
        assert!(received.lock().unwrap().is_empty());

        show_ask_error_window(app.handle(), "Synthetic genuine failure".into());
        assert!(app.get_webview_window("ask").is_some());
        assert_eq!(
            *received.lock().unwrap(),
            vec!["Synthetic genuine failure".to_string()]
        );
        show_ask_error_window(app.handle(), commands::ask::ASK_CANCELLED.into());
        assert_eq!(received.lock().unwrap().len(), 1);
        app.unlisten(listener);
    }

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
            paste_last: None,
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
    fn fn_alphanumeric_bindings_use_native_registration_only_on_macos() {
        for key in ["K", "A", "Z", "0", "9"] {
            let binding = storage::ShortcutBinding::from_hotkey(&format!("Fn+{key}")).unwrap();
            assert!(native_trigger_from_binding(&binding).is_some());
            assert!(binding_is_valid_for_platform(&binding, "macos"));
            assert!(!binding_is_valid_for_platform(&binding, "windows"));
        }
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
            paste_last: None,
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
            paste_last: None,
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
    fn translation_shortcut_starts_recording_with_translate_override() {
        use crate::input_session::{InputAction, InputBinding, InputMode, InputSession, VoiceRole};
        let mut session = InputSession::default();
        session.commit_bindings(
            1,
            vec![InputBinding {
                role: VoiceRole::Translate,
                binding_id: "Fn+LeftShift".into(),
                mode: InputMode::Hold,
                revision: 1,
            }],
        );
        session.confirm_installation(1);
        let actions = session.press_committed("Fn+LeftShift", VoiceRole::Translate, 10);
        let [InputAction::Start {
            session_id,
            binding,
        }] = actions.as_slice()
        else {
            panic!("expected translation start");
        };
        assert_eq!(binding.role, VoiceRole::Translate);
        session.ready(*session_id);
        assert_eq!(
            session.release("Fn+LeftShift", 20),
            vec![InputAction::Stop {
                session_id: *session_id
            }]
        );
    }

    #[test]
    fn auto_mode_tap_starts_hands_free_and_next_tap_finishes() {
        use crate::input_session::{InputAction, InputBinding, InputMode, InputSession, VoiceRole};
        let mut session = InputSession::default();
        session.commit_bindings(
            1,
            vec![InputBinding {
                role: VoiceRole::Dictation,
                binding_id: "Fn".into(),
                mode: InputMode::Auto,
                revision: 1,
            }],
        );
        session.confirm_installation(1);
        assert!(session
            .press_committed("Fn", VoiceRole::Dictation, 10)
            .is_empty());
        let actions = session.release("Fn", 190);
        let [InputAction::Start { session_id, .. }] = actions.as_slice() else {
            panic!("expected tap start");
        };
        session.ready(*session_id);
        assert!(session
            .press_committed("Fn", VoiceRole::Dictation, 200)
            .is_empty());
        assert_eq!(
            session.release("Fn", 300),
            vec![InputAction::Stop {
                session_id: *session_id
            }]
        );
    }

    #[test]
    fn auto_mode_long_press_release_hides_guide_or_discards_the_take() {
        use crate::input_session::{InputAction, InputBinding, InputMode, InputSession, VoiceRole};
        let mut session = InputSession::default();
        session.commit_bindings(
            1,
            vec![InputBinding {
                role: VoiceRole::Dictation,
                binding_id: "Fn".into(),
                mode: InputMode::Auto,
                revision: 1,
            }],
        );
        session.confirm_installation(1);
        session.press_committed("Fn", VoiceRole::Dictation, 10);
        let press_id = session.press_id().unwrap();
        assert_eq!(
            session.long_press_due(press_id, 610),
            vec![InputAction::ShowGuide { press_id }]
        );
        assert_eq!(
            session.release("Fn", 900),
            vec![InputAction::HideGuide { press_id }]
        );
        session.press_committed("Fn", VoiceRole::Dictation, 1000);
        assert!(session.release("Fn", 1600).is_empty());
        assert_eq!(session.session_id(), None);
    }

    #[test]
    fn auto_press_tracker_reports_hold_once() {
        let tracker = AutoPressTracker::default();
        let pressed = std::time::Instant::now();
        tracker.mark_pressed(pressed);
        assert_eq!(
            tracker.take_release(pressed + Duration::from_millis(700)),
            Some(PressRelease {
                held: Duration::from_millis(700),
                long_held: false,
            })
        );
        assert_eq!(tracker.take_release(pressed + Duration::from_secs(2)), None);
    }

    #[test]
    fn lifecycle_reset_accepts_the_first_press_without_a_previous_release() {
        let tracker = AutoPressTracker::default();
        let pressed = std::time::Instant::now();
        assert!(!tracker.begin_press(pressed));
        let old_generation = tracker.mark_pressed(pressed);
        tracker.reset_for_lifecycle();
        assert!(!tracker.begin_press(pressed + Duration::from_millis(100)));
        assert!(!tracker.claim_long_hold(old_generation));
    }

    #[test]
    fn stopping_press_clear_preserves_repeat_suppression_until_release() {
        let tracker = AutoPressTracker::default();
        let pressed = std::time::Instant::now();
        assert!(!tracker.begin_press(pressed));
        tracker.clear();
        assert!(tracker.begin_press(pressed + Duration::from_millis(100)));
        tracker.take_release(pressed + Duration::from_millis(150));
        assert!(!tracker.begin_press(pressed + Duration::from_millis(200)));
    }

    #[test]
    fn auto_press_tracker_claims_long_hold_only_for_the_current_press() {
        let tracker = AutoPressTracker::default();
        let pressed = std::time::Instant::now();
        let stale = tracker.mark_pressed(pressed);
        let _ = tracker.take_release(pressed + Duration::from_millis(100));
        let current = tracker.mark_pressed(pressed + Duration::from_millis(200));

        assert!(!tracker.claim_long_hold(stale));
        assert!(tracker.claim_long_hold(current));
        assert!(!tracker.claim_long_hold(current));
        assert_eq!(
            tracker
                .take_release(pressed + Duration::from_millis(1000))
                .map(|r| r.long_held),
            Some(true)
        );
    }

    #[test]
    fn long_hold_publication_finishes_before_a_concurrent_release() {
        let tracker = Arc::new(AutoPressTracker::default());
        let pressed = std::time::Instant::now();
        let generation = tracker.mark_pressed(pressed);
        assert!(tracker.claim_long_hold(generation));
        let events = Arc::new(Mutex::new(Vec::new()));
        let (entered_sender, entered_receiver) = std::sync::mpsc::channel();
        let (continue_sender, continue_receiver) = std::sync::mpsc::channel();
        let publisher_tracker = Arc::clone(&tracker);
        let publisher_events = Arc::clone(&events);
        let publisher = std::thread::spawn(move || {
            publisher_tracker.publish_long_hold(generation, || {
                entered_sender.send(()).unwrap();
                continue_receiver
                    .recv_timeout(Duration::from_secs(2))
                    .unwrap();
                publisher_events.lock().unwrap().push("show");
            })
        });
        entered_receiver
            .recv_timeout(Duration::from_secs(2))
            .unwrap();
        let release_tracker = Arc::clone(&tracker);
        let release_events = Arc::clone(&events);
        let release = std::thread::spawn(move || {
            let result = release_tracker
                .take_release(pressed + Duration::from_millis(601))
                .unwrap();
            if result.long_held {
                release_events.lock().unwrap().push("dismiss");
            }
        });
        continue_sender.send(()).unwrap();
        assert!(publisher.join().unwrap());
        release.join().unwrap();
        assert_eq!(*events.lock().unwrap(), ["show", "dismiss"]);
        assert!(!tracker.publish_long_hold(generation, || {
            events.lock().unwrap().push("late show");
        }));
    }

    #[test]
    fn released_or_replaced_long_hold_cannot_publish_its_guide() {
        let tracker = AutoPressTracker::default();
        let pressed = std::time::Instant::now();
        let generation = tracker.mark_pressed(pressed);
        assert!(tracker.claim_long_hold(generation));
        let _ = tracker.take_release(pressed + Duration::from_millis(601));
        let mut published = false;
        assert!(!tracker.publish_long_hold(generation, || published = true));
        assert!(!published);
        let current = tracker.mark_pressed(pressed + Duration::from_secs(1));
        assert!(tracker.claim_long_hold(current));
        assert!(!tracker.publish_long_hold(generation, || published = true));
        assert!(tracker.publish_long_hold(current, || published = true));
        assert!(published);
    }

    #[test]
    fn auto_press_tracker_flags_os_key_repeat_until_release() {
        let tracker = AutoPressTracker::default();
        let t0 = std::time::Instant::now();
        assert!(!tracker.begin_press(t0));
        assert!(tracker.begin_press(t0 + Duration::from_millis(50)));
        let _ = tracker.take_release(t0 + Duration::from_millis(80));
        assert!(!tracker.begin_press(t0 + Duration::from_millis(120)));
    }

    #[test]
    fn auto_press_tracker_recovers_from_a_lost_release() {
        let tracker = AutoPressTracker::default();
        let t0 = std::time::Instant::now();
        assert!(!tracker.begin_press(t0));
        assert!(!tracker.begin_press(t0 + Duration::from_secs(3)));
    }

    #[test]
    fn auto_press_tracker_released_press_cannot_claim_long_hold() {
        let tracker = AutoPressTracker::default();
        let pressed = std::time::Instant::now();
        let generation = tracker.mark_pressed(pressed);
        let _ = tracker.take_release(pressed + Duration::from_millis(150));
        assert!(!tracker.claim_long_hold(generation));

        let generation = tracker.mark_pressed(pressed);
        tracker.clear();
        assert!(!tracker.claim_long_hold(generation));
    }

    #[test]
    fn hold_release_queues_stop_even_when_start_is_still_pending() {
        use crate::input_session::{InputAction, InputBinding, InputMode, InputSession, VoiceRole};
        let mut session = InputSession::default();
        session.commit_bindings(
            1,
            vec![InputBinding {
                role: VoiceRole::Dictation,
                binding_id: "Fn".into(),
                mode: InputMode::Hold,
                revision: 1,
            }],
        );
        session.confirm_installation(1);
        let actions = session.press_committed("Fn", VoiceRole::Dictation, 10);
        let [InputAction::Start { session_id, .. }] = actions.as_slice() else {
            panic!("expected hold start");
        };
        assert!(session.release("Fn", 20).is_empty());
        assert_eq!(
            session.ready(*session_id),
            vec![InputAction::Stop {
                session_id: *session_id
            }]
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
    fn focus_reopens_failed_retry_without_resuming_an_editor_pause() {
        let supervisor = HotkeySupervisor::default();
        let generation = supervisor.snapshot().generation;
        for _ in 0..=HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT {
            supervisor.record_registration_failure(generation, "registration failed".to_string());
        }
        assert_eq!(supervisor.next_retry_delay(), None);
        assert!(supervisor.retry_failed_on_focus());
        assert!(supervisor.next_retry_delay().is_some());
        assert_eq!(supervisor.snapshot().generation, generation);
        supervisor.disable();
        assert!(!supervisor.retry_failed_on_focus());
        assert_eq!(supervisor.next_retry_delay(), None);
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
