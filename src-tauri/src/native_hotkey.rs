use crate::input_session::InputMode;
#[cfg(any(target_os = "macos", target_os = "windows", test))]
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri_plugin_global_shortcut::ShortcutState;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum NativeHotkeyTrigger {
    Fn,
    FnSpace,
    FnLeftShift,
    FnKey(char),
    RightAlt,
    RightAltSpace,
    RightAltLeftShift,
    RightAltRightShift,
}

impl NativeHotkeyTrigger {
    pub fn canonical(self) -> String {
        match self {
            Self::Fn => "Fn",
            Self::FnSpace => "Fn+Space",
            Self::FnLeftShift => "Fn+LeftShift",
            Self::RightAlt => "RightAlt",
            Self::RightAltSpace => "RightAlt+Space",
            Self::RightAltLeftShift => "RightAlt+LeftShift",
            Self::RightAltRightShift => "RightAlt+RightShift",
            Self::FnKey(key) => return format!("Fn+{key}"),
        }
        .to_string()
    }

    #[cfg(any(target_os = "macos", target_os = "windows", test))]
    fn base(self) -> NativeHotkeyTrigger {
        match self {
            Self::Fn | Self::FnSpace | Self::FnLeftShift => Self::Fn,
            Self::FnKey(_) => Self::Fn,
            Self::RightAlt
            | Self::RightAltSpace
            | Self::RightAltLeftShift
            | Self::RightAltRightShift => Self::RightAlt,
        }
    }

    #[cfg(any(target_os = "macos", target_os = "windows", test))]
    fn combo_key(self) -> Option<NativeComboKey> {
        match self {
            Self::FnSpace | Self::RightAltSpace => Some(NativeComboKey::Space),
            Self::FnLeftShift | Self::RightAltLeftShift => Some(NativeComboKey::LeftShift),
            Self::RightAltRightShift => Some(NativeComboKey::RightShift),
            Self::Fn | Self::RightAlt => None,
            Self::FnKey(key) => Some(NativeComboKey::Key(key)),
        }
    }

    #[cfg(any(target_os = "macos", target_os = "windows", test))]
    fn from_base_combo(base: NativeHotkeyTrigger, combo: NativeComboKey) -> Option<Self> {
        match (base, combo) {
            (Self::Fn, NativeComboKey::Space) => Some(Self::FnSpace),
            (Self::Fn, NativeComboKey::LeftShift) => Some(Self::FnLeftShift),
            (Self::Fn, NativeComboKey::Key(key)) => Some(Self::FnKey(key)),
            (Self::RightAlt, NativeComboKey::Space) => Some(Self::RightAltSpace),
            (Self::RightAlt, NativeComboKey::LeftShift) => Some(Self::RightAltLeftShift),
            (Self::RightAlt, NativeComboKey::RightShift) => Some(Self::RightAltRightShift),
            _ => None,
        }
    }
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum NativeComboKey {
    Space,
    LeftShift,
    RightShift,
    Key(char),
}

#[cfg(any(target_os = "macos", test))]
fn mac_combo_key(keycode: i64) -> Option<NativeComboKey> {
    if keycode == 49 {
        return Some(NativeComboKey::Space);
    }
    const KEYS: &[(i64, char)] = &[
        (0, 'A'),
        (1, 'S'),
        (2, 'D'),
        (3, 'F'),
        (4, 'H'),
        (5, 'G'),
        (6, 'Z'),
        (7, 'X'),
        (8, 'C'),
        (9, 'V'),
        (11, 'B'),
        (12, 'Q'),
        (13, 'W'),
        (14, 'E'),
        (15, 'R'),
        (16, 'Y'),
        (17, 'T'),
        (18, '1'),
        (19, '2'),
        (20, '3'),
        (21, '4'),
        (22, '6'),
        (23, '5'),
        (25, '9'),
        (26, '7'),
        (28, '8'),
        (29, '0'),
        (31, 'O'),
        (32, 'U'),
        (34, 'I'),
        (35, 'P'),
        (37, 'L'),
        (38, 'J'),
        (40, 'K'),
        (45, 'N'),
        (46, 'M'),
    ];
    KEYS.iter()
        .find(|(code, _)| *code == keycode)
        .map(|(_, key)| NativeComboKey::Key(*key))
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NativeHotkeyBinding {
    pub role: crate::hotkey::HotkeyRole,
    pub index: usize,
    pub trigger: NativeHotkeyTrigger,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NativeHotkeyEvent {
    pub parent_trigger: Option<NativeHotkeyTrigger>,
    pub occurred_at: std::time::Instant,
    pub role: crate::hotkey::HotkeyRole,
    pub index: usize,
    pub state: ShortcutState,
}

fn dispatch_when_enabled(
    paused: &crate::native_dispatch_gate::NativeDispatchGate,
    event: NativeHotkeyEvent,
    handler: &NativeHotkeyHandler,
) -> bool {
    if !paused.is_paused() {
        handler.dispatch(event)
    } else {
        tracing::debug!(
            role = event.role.as_str(),
            "Native shortcut dispatch paused"
        );
        false
    }
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
#[derive(Default)]
struct NativeHeldState {
    held: AtomicBool,
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
impl NativeHeldState {
    fn edge(&self, pressed: bool) -> Option<ShortcutState> {
        match (pressed, self.held.swap(pressed, Ordering::SeqCst)) {
            (true, false) => Some(ShortcutState::Pressed),
            (false, true) => Some(ShortcutState::Released),
            _ => None,
        }
    }
}

#[derive(Clone, Default)]
pub struct NativeHotkeyRuntime {
    inner: Arc<Mutex<NativeHotkeyRuntimeInner>>,
    dispatch_paused: Arc<crate::native_dispatch_gate::NativeDispatchGate>,
    generation: Arc<std::sync::atomic::AtomicU64>,
}

#[derive(Default)]
struct NativeHotkeyRuntimeInner {
    monitor: Option<platform::PlatformNativeMonitor>,
    handler_slot: Option<Arc<NativeHandlerSlot>>,
    bindings: Vec<NativeHotkeyBinding>,
    mode: InputMode,
    stopped: bool,
}

impl NativeHotkeyRuntime {
    pub fn install(
        &self,
        bindings: Vec<NativeHotkeyBinding>,
        mode: InputMode,
        handler: Arc<dyn Fn(NativeHotkeyEvent) + Send + Sync>,
    ) -> Result<(), String> {
        self.install_handler(bindings, mode, Arc::new(LegacyNativeHandler(handler)))
    }

    pub(crate) fn install_with_acceptance(
        &self,
        bindings: Vec<NativeHotkeyBinding>,
        mode: InputMode,
        handler: Arc<dyn Fn(NativeHotkeyEvent) -> bool + Send + Sync>,
    ) -> Result<(), String> {
        self.install_handler(bindings, mode, Arc::new(AcceptingNativeHandler(handler)))
    }

    fn install_handler(
        &self,
        bindings: Vec<NativeHotkeyBinding>,
        mode: InputMode,
        handler: NativeHotkeyHandler,
    ) -> Result<(), String> {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        if inner.stopped {
            return Err("Native shortcut runtime is stopped".into());
        }
        let installation = self.dispatch_paused.begin_install();
        #[cfg(target_os = "macos")]
        if inner.bindings == bindings
            && inner
                .monitor
                .as_ref()
                .is_some_and(|monitor| monitor.base_pressed().is_ok())
        {
            if let Some(monitor) = &inner.monitor {
                monitor.reconfigure_mode(mode, &bindings);
            }
            let previous_handler = inner
                .handler_slot
                .as_ref()
                .map(|slot| slot.replace(handler));
            inner.mode = mode;
            let result = self.finish_install(installation);
            drop(inner);
            drop(previous_handler);
            return result;
        }
        let generation = self
            .generation
            .load(std::sync::atomic::Ordering::SeqCst)
            .checked_add(1)
            .ok_or("Native shortcut generation exhausted")?;
        #[cfg(not(target_os = "macos"))]
        let _ = inner.monitor.take();

        if bindings.is_empty() {
            self.generation
                .store(generation, std::sync::atomic::Ordering::SeqCst);
            let _ = inner.monitor.take();
            inner.bindings.clear();
            inner.handler_slot = None;
            return self.finish_install(installation);
        }

        let paused = Arc::clone(&self.dispatch_paused);
        let current_generation = Arc::clone(&self.generation);
        let handler_slot = Arc::new(NativeHandlerSlot::new(handler));
        let dispatch_slot = Arc::clone(&handler_slot);
        let latest_handler: NativeHotkeyHandler =
            Arc::new(AcceptingNativeHandler(Arc::new(move |event| {
                dispatch_slot.dispatch(event)
            })));
        let guarded_handler: NativeHotkeyHandler =
            Arc::new(AcceptingNativeHandler(Arc::new(move |event| {
                if current_generation.load(std::sync::atomic::Ordering::SeqCst) == generation {
                    dispatch_when_enabled(&paused, event, &latest_handler)
                } else {
                    tracing::debug!("Ignored shortcut event from superseded native monitor");
                    false
                }
            })));
        crate::monitor_transaction::replace_after_start(
            &mut inner.monitor,
            || {
                platform::PlatformNativeMonitor::start(
                    bindings.clone(),
                    mode,
                    guarded_handler,
                    Arc::clone(&self.dispatch_paused),
                )
            },
            || {
                self.generation
                    .store(generation, std::sync::atomic::Ordering::SeqCst);
            },
        )?;
        inner.bindings = bindings;
        inner.handler_slot = Some(handler_slot);
        inner.mode = mode;
        self.finish_install(installation)
    }

    fn finish_install(&self, installation: u64) -> Result<(), String> {
        if self.dispatch_paused.finish_install(installation) {
            Ok(())
        } else {
            Err("hotkey registration superseded".into())
        }
    }

    pub fn pause(&self) {
        self.dispatch_paused.pause();
        #[cfg(target_os = "macos")]
        {
            match self.inner.try_lock() {
                Ok(inner) => {
                    if let Some(monitor) = &inner.monitor {
                        monitor.suspend_actions();
                    }
                }
                Err(std::sync::TryLockError::Poisoned(error)) => {
                    if let Some(monitor) = &error.into_inner().monitor {
                        monitor.suspend_actions();
                    }
                }
                Err(std::sync::TryLockError::WouldBlock) => {}
            }
        }
    }

    pub fn shutdown(&self) {
        self.pause();
        let previous = {
            let mut inner = self.inner.lock().unwrap_or_else(|error| error.into_inner());
            inner.stopped = true;
            self.dispatch_paused.pause();
            inner.bindings.clear();
            inner.handler_slot = None;
            inner.monitor.take()
        };
        drop(previous);
    }

    pub fn observed_native_key_pressed(&self) -> Option<Result<bool, String>> {
        #[cfg(target_os = "macos")]
        {
            let inner = self.inner.lock().unwrap_or_else(|error| error.into_inner());
            inner.monitor.as_ref().map(|monitor| monitor.base_pressed())
        }
        #[cfg(not(target_os = "macos"))]
        {
            None
        }
    }

    pub fn observed_native_chord_pressed(&self) -> Option<Result<bool, String>> {
        #[cfg(target_os = "macos")]
        {
            let inner = self.inner.lock().unwrap_or_else(|error| error.into_inner());
            inner
                .monitor
                .as_ref()
                .map(|monitor| monitor.chord_pressed())
        }
        #[cfg(not(target_os = "macos"))]
        {
            None
        }
    }
}

trait NativeEventHandler: Send + Sync {
    fn dispatch(&self, event: NativeHotkeyEvent) -> bool;
}

impl<Handler: Fn(NativeHotkeyEvent) + Send + Sync> NativeEventHandler for Handler {
    fn dispatch(&self, event: NativeHotkeyEvent) -> bool {
        self(event);
        true
    }
}

struct AcceptingNativeHandler(Arc<dyn Fn(NativeHotkeyEvent) -> bool + Send + Sync>);

struct LegacyNativeHandler(Arc<dyn Fn(NativeHotkeyEvent) + Send + Sync>);

impl NativeEventHandler for LegacyNativeHandler {
    fn dispatch(&self, event: NativeHotkeyEvent) -> bool {
        self.0(event);
        true
    }
}

impl NativeEventHandler for AcceptingNativeHandler {
    fn dispatch(&self, event: NativeHotkeyEvent) -> bool {
        self.0(event)
    }
}

type NativeHotkeyHandler = Arc<dyn NativeEventHandler + 'static>;

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn dispatch_native_state_update(
    state: &Mutex<NativeComboState>,
    handler: &NativeHotkeyHandler,
    update: impl FnOnce(&mut NativeComboState, &NativeHotkeyHandler) -> bool,
) -> bool {
    let dispatch_order = state
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .dispatch_order
        .clone();
    let _dispatch = dispatch_order
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    let events = Arc::new(Mutex::new(Vec::new()));
    let captured = events.clone();
    let collector: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
        captured
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .push(event);
    });
    {
        let mut state = state.lock().unwrap_or_else(|error| error.into_inner());
        update(&mut state, &collector);
    }
    let events = std::mem::take(&mut *events.lock().unwrap_or_else(|error| error.into_inner()));
    let mut accepted = false;
    for event in events {
        accepted |= handler.dispatch(event);
    }
    accepted
}

struct NativeHandlerSlot {
    handler: Mutex<NativeHotkeyHandler>,
}

impl NativeHandlerSlot {
    fn new(handler: NativeHotkeyHandler) -> Self {
        Self {
            handler: Mutex::new(handler),
        }
    }

    #[cfg(any(target_os = "macos", test))]
    fn replace(&self, handler: NativeHotkeyHandler) -> NativeHotkeyHandler {
        {
            let mut current = self
                .handler
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            std::mem::replace(&mut *current, handler)
        }
    }

    fn dispatch(&self, event: NativeHotkeyEvent) -> bool {
        let handler = self
            .handler
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone();
        handler.dispatch(event)
    }
}

#[cfg(any(target_os = "macos", test))]
fn chord_observation(known: bool, base_pressed: bool, combo_pressed: bool) -> Result<bool, String> {
    if base_pressed || combo_pressed {
        return Ok(true);
    }
    if !known {
        return Err("Native shortcut release observation is not initialized".into());
    }
    Ok(false)
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
struct NativeMonitoredBinding {
    binding: NativeHotkeyBinding,
    held: NativeHeldState,
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
impl NativeMonitoredBinding {
    fn new(binding: NativeHotkeyBinding) -> Self {
        Self {
            binding,
            held: NativeHeldState::default(),
        }
    }
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn monitored_bindings_for_base(
    bindings: Vec<NativeHotkeyBinding>,
    base: NativeHotkeyTrigger,
) -> Vec<NativeMonitoredBinding> {
    bindings
        .into_iter()
        .filter(|binding| binding.trigger.base() == base)
        .map(NativeMonitoredBinding::new)
        .collect()
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn has_combo_bindings(bindings: &[NativeMonitoredBinding], base: NativeHotkeyTrigger) -> bool {
    bindings.iter().any(|binding| {
        binding.binding.trigger.base() == base && binding.binding.trigger.combo_key().is_some()
    })
}

#[cfg(any(target_os = "macos", test))]
fn reconcile_native_release(
    state: &mut NativeComboState,
    bindings: &[NativeMonitoredBinding],
    keys: Option<&[u16]>,
) -> bool {
    if !state.reconcile_release(keys) {
        return false;
    }
    for binding in bindings {
        binding.held.held.store(false, Ordering::SeqCst);
    }
    true
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn has_binding_for_trigger(
    bindings: &[NativeMonitoredBinding],
    trigger: NativeHotkeyTrigger,
) -> bool {
    bindings
        .iter()
        .any(|binding| binding.binding.trigger == trigger)
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn dispatch_matching_bindings(
    bindings: &[NativeMonitoredBinding],
    trigger: NativeHotkeyTrigger,
    pressed: bool,
    handler: &NativeHotkeyHandler,
) -> bool {
    dispatch_matching_bindings_at(
        bindings,
        trigger,
        pressed,
        std::time::Instant::now(),
        handler,
    )
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn dispatch_matching_bindings_at(
    bindings: &[NativeMonitoredBinding],
    trigger: NativeHotkeyTrigger,
    pressed: bool,
    occurred_at: std::time::Instant,
    handler: &NativeHotkeyHandler,
) -> bool {
    let mut accepted = false;
    for binding in bindings {
        if binding.binding.trigger != trigger {
            continue;
        }
        if let Some(state) = binding.held.edge(pressed) {
            accepted |= handler.dispatch(NativeHotkeyEvent {
                parent_trigger: trigger.combo_key().map(|_| trigger.base()),
                occurred_at,
                role: binding.binding.role,
                index: binding.binding.index,
                state,
            });
        }
    }
    accepted
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
#[derive(Default)]
struct NativeComboState {
    dispatch_order: Arc<Mutex<()>>,
    mode: InputMode,
    base_pressed: bool,
    held_combos: Vec<NativeComboKey>,
    pending_base_press: bool,
    combo_used: bool,
    edge_generation: u64,
    pressed_at: Option<std::time::Instant>,
    awaiting_release: bool,
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
impl NativeComboState {
    #[cfg(any(target_os = "macos", test))]
    fn reconcile_release(&mut self, keys: Option<&[u16]>) -> bool {
        if !self.awaiting_release || keys.is_none_or(|keys| !keys.is_empty()) {
            return false;
        }
        self.edge_generation = self.edge_generation.wrapping_add(1);
        self.base_pressed = false;
        self.held_combos.clear();
        self.pending_base_press = false;
        self.combo_used = false;
        self.pressed_at = None;
        self.awaiting_release = false;
        true
    }

    #[cfg(any(target_os = "macos", test))]
    fn reconfigure_mode(&mut self, mode: InputMode) {
        if self.mode == mode {
            return;
        }
        self.mode = mode;
        self.suspend_actions();
    }

    #[cfg(any(target_os = "macos", test))]
    fn initialize_pressed(&mut self, base_pressed: bool, combos: Vec<NativeComboKey>) {
        self.base_pressed = base_pressed;
        self.held_combos = combos;
        self.suspend_actions();
    }

    #[cfg(any(target_os = "macos", test))]
    fn suspend_actions(&mut self) {
        self.edge_generation = self.edge_generation.wrapping_add(1);
        self.pending_base_press = false;
        self.pressed_at = None;
        self.awaiting_release = self.base_pressed || !self.held_combos.is_empty();
    }

    #[cfg(any(target_os = "macos", test))]
    fn invalidate_observation(&mut self) {
        self.edge_generation = self.edge_generation.wrapping_add(1);
        self.base_pressed = false;
        self.pending_base_press = false;
        self.combo_used = false;
        self.pressed_at = None;
        self.held_combos.clear();
    }

    #[cfg(any(target_os = "macos", test))]
    fn combo_pressed(&self) -> bool {
        !self.held_combos.is_empty()
    }

    #[cfg(test)]
    fn any_pressed(&self) -> bool {
        self.base_pressed || self.combo_pressed()
    }
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn activate_pending_base(
    state: &mut NativeComboState,
    bindings: &[NativeMonitoredBinding],
    base: NativeHotkeyTrigger,
    edge_generation: u64,
    handler: &NativeHotkeyHandler,
) -> bool {
    if state.awaiting_release
        || !state.base_pressed
        || !state.pending_base_press
        || state.combo_used
        || state.edge_generation != edge_generation
    {
        return false;
    }
    state.pending_base_press = false;
    dispatch_matching_bindings_at(
        bindings,
        base,
        true,
        state.pressed_at.unwrap_or_else(std::time::Instant::now),
        handler,
    )
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn dispatch_native_base_edge(
    state: &mut NativeComboState,
    bindings: &[NativeMonitoredBinding],
    base: NativeHotkeyTrigger,
    pressed: bool,
    handler: &NativeHotkeyHandler,
) -> bool {
    if state.awaiting_release {
        state.base_pressed = pressed;
        for binding in bindings {
            binding.held.held.store(false, Ordering::SeqCst);
        }
        if !pressed && state.held_combos.is_empty() {
            state.awaiting_release = false;
        }
        return false;
    }
    if state.base_pressed == pressed {
        return false;
    }
    let physical_press = state.pressed_at;
    state.edge_generation = state.edge_generation.wrapping_add(1);
    if pressed {
        state.pressed_at = Some(std::time::Instant::now());
        state.base_pressed = true;
        state.combo_used = false;
        state.pending_base_press = has_combo_bindings(bindings, base);
        for combo in state.held_combos.clone() {
            let _ = dispatch_native_combo_edge(state, bindings, base, combo, true, handler);
        }
        if state.combo_used {
            return true;
        }
        if state.pending_base_press {
            return true;
        }
        return dispatch_matching_bindings(bindings, base, true, handler);
    }

    if state.pending_base_press && !state.combo_used {
        let matched = dispatch_matching_bindings_at(
            bindings,
            base,
            true,
            physical_press.unwrap_or_else(std::time::Instant::now),
            handler,
        );
        let _ = dispatch_matching_bindings(bindings, base, false, handler);
        state.base_pressed = false;
        state.pending_base_press = false;
        return matched;
    }

    if state.combo_used {
        let _ = dispatch_matching_bindings(bindings, base, false, handler);
        for binding in bindings {
            if binding.binding.trigger.base() == base
                && binding.binding.trigger.combo_key().is_some()
            {
                if let Some(event_state) = binding.held.edge(false) {
                    handler.dispatch(NativeHotkeyEvent {
                        parent_trigger: Some(base),
                        occurred_at: std::time::Instant::now(),
                        role: binding.binding.role,
                        index: binding.binding.index,
                        state: event_state,
                    });
                }
            }
        }
        state.base_pressed = false;
        state.pending_base_press = false;
        state.combo_used = false;
        return true;
    }

    state.base_pressed = false;
    dispatch_matching_bindings(bindings, base, false, handler)
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn dispatch_native_combo_edge(
    state: &mut NativeComboState,
    bindings: &[NativeMonitoredBinding],
    base: NativeHotkeyTrigger,
    combo: NativeComboKey,
    pressed: bool,
    handler: &NativeHotkeyHandler,
) -> bool {
    let Some(trigger) = NativeHotkeyTrigger::from_base_combo(base, combo) else {
        return false;
    };
    if !has_binding_for_trigger(bindings, trigger) {
        return false;
    }
    if pressed {
        if !state.held_combos.contains(&combo) {
            state.held_combos.push(combo);
        }
    } else {
        state.held_combos.retain(|held| *held != combo);
    }
    if state.awaiting_release {
        if !state.base_pressed && state.held_combos.is_empty() {
            state.awaiting_release = false;
        }
        return false;
    }
    if !state.base_pressed {
        return false;
    }
    if pressed {
        state.combo_used = true;
        state.pending_base_press = false;
    }
    dispatch_matching_bindings(bindings, trigger, pressed, handler)
}

#[cfg(target_os = "macos")]
mod platform {
    use super::{
        activate_pending_base, dispatch_native_base_edge, dispatch_native_combo_edge,
        dispatch_native_state_update, has_binding_for_trigger, monitored_bindings_for_base,
        reconcile_native_release, InputMode, NativeComboKey, NativeComboState, NativeHotkeyHandler,
        NativeHotkeyTrigger, NativeMonitoredBinding,
    };
    use std::ffi::c_void;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{mpsc, Arc, Mutex};
    use std::thread;
    use std::time::Duration;

    const STARTUP_TIMEOUT: Duration = Duration::from_secs(3);
    const HOLD_COMBO_GRACE_PERIOD: Duration = Duration::from_millis(200);

    type CgEventMask = u64;
    type CgEventType = u32;
    type CgEventTapLocation = u32;
    type CgEventTapPlacement = u32;
    type CgEventTapOptions = u32;
    type CgEventField = u32;
    type CgEventFlags = u64;
    type CfStringRef = *const c_void;
    type CfAllocatorRef = *const c_void;

    #[repr(C)]
    struct OpaqueCgEvent(c_void);
    type CgEventRef = *mut OpaqueCgEvent;

    #[repr(C)]
    struct OpaqueCfMachPort(c_void);
    type CfMachPortRef = *mut OpaqueCfMachPort;

    #[repr(C)]
    struct OpaqueCfRunLoop(c_void);
    type CfRunLoopRef = *mut OpaqueCfRunLoop;

    #[repr(C)]
    struct OpaqueCfRunLoopSource(c_void);
    type CfRunLoopSourceRef = *mut OpaqueCfRunLoopSource;

    const SESSION_EVENT_TAP: CgEventTapLocation = 1;
    const HEAD_INSERT: CgEventTapPlacement = 0;
    const TAP_OPTION_DEFAULT: CgEventTapOptions = 0;

    const KEY_DOWN: CgEventType = 10;
    const KEY_UP: CgEventType = 11;
    const FLAGS_CHANGED: CgEventType = 12;
    const TAP_DISABLED_BY_TIMEOUT: CgEventType = 0xFFFF_FFFE;
    const TAP_DISABLED_BY_USER_INPUT: CgEventType = 0xFFFF_FFFF;

    const KEYBOARD_EVENT_KEYCODE: CgEventField = 9;
    const FLAG_MASK_SECONDARY_FN: CgEventFlags = 0x0080_0000;
    const FLAG_MASK_LEFT_SHIFT: CgEventFlags = 0x0000_0002;
    const FN_KEYCODE: i64 = 63;
    const LEFT_SHIFT_KEYCODE: i64 = 56;

    type CgEventTapCallBack = extern "C" fn(
        proxy: *mut c_void,
        event_type: CgEventType,
        event: CgEventRef,
        user_info: *mut c_void,
    ) -> CgEventRef;

    #[link(name = "CoreGraphics", kind = "framework")]
    extern "C" {
        fn CGEventTapCreate(
            tap: CgEventTapLocation,
            place: CgEventTapPlacement,
            options: CgEventTapOptions,
            events_of_interest: CgEventMask,
            callback: CgEventTapCallBack,
            user_info: *mut c_void,
        ) -> CfMachPortRef;
        fn CGEventTapEnable(tap: CfMachPortRef, enable: bool);
        fn CGEventTapIsEnabled(tap: CfMachPortRef) -> bool;
        fn CGEventGetIntegerValueField(event: CgEventRef, field: CgEventField) -> i64;
        fn CGEventGetFlags(event: CgEventRef) -> CgEventFlags;
        fn CGEventSourceFlagsState(state_id: i32) -> CgEventFlags;
        fn CGPreflightListenEventAccess() -> bool;
        fn CGEventSourceKeyState(state_id: i32, key: u16) -> bool;
    }

    #[link(name = "CoreFoundation", kind = "framework")]
    extern "C" {
        fn CFMachPortCreateRunLoopSource(
            allocator: CfAllocatorRef,
            port: CfMachPortRef,
            order: isize,
        ) -> CfRunLoopSourceRef;
        fn CFRunLoopGetCurrent() -> CfRunLoopRef;
        fn CFRunLoopAddSource(rl: CfRunLoopRef, source: CfRunLoopSourceRef, mode: CfStringRef);
        fn CFRunLoopRun();
        fn CFRunLoopStop(rl: CfRunLoopRef);
        fn CFRelease(cf: *const c_void);
        static kCFRunLoopCommonModes: CfStringRef;
    }

    struct MacShutdownHandles {
        state: Arc<Mutex<NativeComboState>>,
        bindings: Arc<Vec<NativeMonitoredBinding>>,
        suspend_requested: AtomicBool,
        tap: Mutex<Option<CfMachPortRef>>,
        runloop: Mutex<Option<CfRunLoopRef>>,
        cancelled: AtomicBool,
        base_pressed: AtomicBool,
        combo_pressed: AtomicBool,
        observing: AtomicBool,
        dispatch_gate: Arc<crate::native_dispatch_gate::NativeDispatchGate>,
    }

    unsafe impl Send for MacShutdownHandles {}
    unsafe impl Sync for MacShutdownHandles {}

    impl MacShutdownHandles {
        fn new(bindings: Arc<Vec<NativeMonitoredBinding>>) -> Self {
            Self {
                state: Arc::new(Mutex::new(NativeComboState::default())),
                bindings,
                suspend_requested: AtomicBool::new(false),
                tap: Mutex::new(None),
                runloop: Mutex::new(None),
                cancelled: AtomicBool::new(false),
                base_pressed: AtomicBool::new(false),
                combo_pressed: AtomicBool::new(false),
                observing: AtomicBool::new(false),
                dispatch_gate: Arc::default(),
            }
        }

        fn shutdown(&self) {
            self.observing.store(false, Ordering::SeqCst);
            self.cancelled.store(true, Ordering::SeqCst);
            if let Some(tap) = self.tap.lock().unwrap_or_else(|e| e.into_inner()).as_ref() {
                unsafe { CGEventTapEnable(*tap, false) };
            }
            if let Some(runloop) = self
                .runloop
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .as_ref()
            {
                unsafe { CFRunLoopStop(*runloop) };
            }
        }
    }

    pub struct PlatformNativeMonitor {
        handles: Arc<MacShutdownHandles>,
    }

    impl PlatformNativeMonitor {
        pub fn reconfigure_mode(&self, mode: InputMode, _bindings: &[super::NativeHotkeyBinding]) {
            self.handles
                .state
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .reconfigure_mode(mode);
        }

        pub fn suspend_actions(&self) {
            self.handles.suspend_requested.store(true, Ordering::SeqCst);
        }

        pub fn chord_pressed(&self) -> Result<bool, String> {
            crate::shortcut_release::query_with_health_check(
                || self.base_pressed().map(|_| ()),
                || {
                    let base = self.base_pressed()?;
                    if unsafe { CGPreflightListenEventAccess() } {
                        let pressed = crate::shortcut_release::any_physical_key_pressed(
                            0..128,
                            |key| unsafe { CGEventSourceKeyState(1, key) },
                        );
                        if !unsafe { CGPreflightListenEventAccess() } {
                            return Err("Shortcut release query permission changed".into());
                        }
                        return Ok(base || pressed);
                    }
                    super::chord_observation(
                        false,
                        base,
                        self.handles.combo_pressed.load(Ordering::SeqCst),
                    )
                },
            )
        }

        pub fn base_pressed(&self) -> Result<bool, String> {
            if self.handles.cancelled.load(Ordering::SeqCst)
                || !self.handles.observing.load(Ordering::SeqCst)
            {
                return Err("Native shortcut observation is unavailable".to_string());
            }
            let tap = self
                .handles
                .tap
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            if !tap
                .as_ref()
                .is_some_and(|tap| unsafe { CGEventTapIsEnabled(*tap) })
            {
                return Err("Native shortcut observation is unavailable".to_string());
            }
            drop(tap);
            let needs_release = self
                .handles
                .state
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .awaiting_release;
            if needs_release {
                let mut state = self
                    .handles
                    .state
                    .lock()
                    .unwrap_or_else(|error| error.into_inner());
                let keys = crate::shortcut_release::physical_key_snapshot().ok();
                if reconcile_native_release(&mut state, &self.handles.bindings, keys.as_deref()) {
                    self.handles.base_pressed.store(false, Ordering::SeqCst);
                    self.handles.combo_pressed.store(false, Ordering::SeqCst);
                    tracing::debug!("Native release barrier reconciled");
                }
            }
            Ok(self.handles.base_pressed.load(Ordering::SeqCst))
        }

        pub fn start(
            bindings: Vec<super::NativeHotkeyBinding>,
            mode: InputMode,
            handler: NativeHotkeyHandler,
            dispatch_gate: Arc<crate::native_dispatch_gate::NativeDispatchGate>,
        ) -> Result<Self, String> {
            let bindings = monitored_bindings_for_base(bindings, NativeHotkeyTrigger::Fn);
            if bindings.is_empty() {
                return Err("macOS native hotkeys currently support Fn only".to_string());
            }

            let mut handles = MacShutdownHandles::new(Arc::new(bindings));
            handles.dispatch_gate = Arc::clone(&dispatch_gate);
            let handles = Arc::new(handles);
            let thread_handles = Arc::clone(&handles);
            let (status_tx, status_rx) = mpsc::channel();
            thread::Builder::new()
                .name("opentypeless-native-hotkey-mac".to_string())
                .spawn(move || {
                    run_event_tap_loop(
                        thread_handles.bindings.clone(),
                        mode,
                        handler,
                        thread_handles,
                        status_tx,
                        dispatch_gate,
                    )
                })
                .map_err(|error| {
                    format!("Failed to spawn macOS native hotkey monitor thread: {error}")
                })?;

            match status_rx.recv_timeout(STARTUP_TIMEOUT) {
                Ok(Ok(())) => Ok(Self { handles }),
                Ok(Err(error)) => {
                    handles.shutdown();
                    Err(error)
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    handles.shutdown();
                    Err(
                        "Timed out starting macOS native hotkey EventTap after 3 seconds"
                            .to_string(),
                    )
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    handles.shutdown();
                    Err(
                        "macOS native hotkey EventTap thread exited before startup completed"
                            .to_string(),
                    )
                }
            }
        }
    }

    impl Drop for PlatformNativeMonitor {
        fn drop(&mut self) {
            self.handles.shutdown();
        }
    }

    struct CallbackContext {
        bindings: Arc<Vec<NativeMonitoredBinding>>,
        handler: NativeHotkeyHandler,
        handles: Arc<MacShutdownHandles>,
        state: Arc<Mutex<NativeComboState>>,
        consumption: Mutex<crate::shortcut_event_consumption::ShortcutEventConsumption>,
    }

    fn run_event_tap_loop(
        bindings: Arc<Vec<NativeMonitoredBinding>>,
        mode: InputMode,
        handler: NativeHotkeyHandler,
        handles: Arc<MacShutdownHandles>,
        status_tx: mpsc::Sender<Result<(), String>>,
        _dispatch_gate: Arc<crate::native_dispatch_gate::NativeDispatchGate>,
    ) {
        let mut initial_state = handles
            .state
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        initial_state.mode = mode;
        drop(initial_state);
        let context = Box::into_raw(Box::new(CallbackContext {
            bindings,
            handler,
            handles: Arc::clone(&handles),
            state: Arc::clone(&handles.state),
            consumption: Mutex::default(),
        }));
        let mask: CgEventMask = (1u64 << FLAGS_CHANGED) | (1u64 << KEY_DOWN) | (1u64 << KEY_UP);

        unsafe {
            let tap = CGEventTapCreate(
                SESSION_EVENT_TAP,
                HEAD_INSERT,
                TAP_OPTION_DEFAULT,
                mask,
                event_tap_callback,
                context as *mut c_void,
            );
            if tap.is_null() {
                drop(Box::from_raw(context));
                let _ = status_tx.send(Err(
                    "Failed to create macOS native hotkey EventTap; Accessibility permission may be denied"
                        .to_string(),
                ));
                return;
            }
            *handles.tap.lock().unwrap_or_else(|e| e.into_inner()) = Some(tap);

            let source = CFMachPortCreateRunLoopSource(std::ptr::null(), tap, 0);
            if source.is_null() {
                CGEventTapEnable(tap, false);
                *handles.tap.lock().unwrap_or_else(|e| e.into_inner()) = None;
                CFRelease(tap as *const c_void);
                drop(Box::from_raw(context));
                let _ = status_tx.send(Err(
                    "Failed to create macOS native hotkey EventTap run loop source".to_string(),
                ));
                return;
            }

            let runloop = CFRunLoopGetCurrent();
            *handles.runloop.lock().unwrap_or_else(|e| e.into_inner()) = Some(runloop);

            if handles.cancelled.load(Ordering::SeqCst) {
                CGEventTapEnable(tap, false);
                CFRelease(source as *const c_void);
                *handles.tap.lock().unwrap_or_else(|e| e.into_inner()) = None;
                *handles.runloop.lock().unwrap_or_else(|e| e.into_inner()) = None;
                CFRelease(tap as *const c_void);
                drop(Box::from_raw(context));
                let _ = status_tx.send(Err(
                    "macOS native hotkey EventTap startup was cancelled".to_string()
                ));
                return;
            }

            CFRunLoopAddSource(runloop, source, kCFRunLoopCommonModes);
            CFRelease(source as *const c_void);
            CGEventTapEnable(tap, true);
            handles.base_pressed.store(
                CGEventSourceFlagsState(1) & FLAG_MASK_SECONDARY_FN != 0,
                Ordering::SeqCst,
            );
            let combos = if CGPreflightListenEventAccess() {
                (0..128)
                    .filter(|key| CGEventSourceKeyState(1, *key))
                    .filter_map(|key| {
                        if key == LEFT_SHIFT_KEYCODE as u16 {
                            Some(NativeComboKey::LeftShift)
                        } else {
                            super::mac_combo_key(i64::from(key))
                        }
                    })
                    .filter(|combo| {
                        NativeHotkeyTrigger::from_base_combo(NativeHotkeyTrigger::Fn, *combo)
                            .is_some_and(|trigger| {
                                super::has_binding_for_trigger(&handles.bindings, trigger)
                            })
                    })
                    .collect()
            } else {
                Vec::new()
            };
            let mut state = handles
                .state
                .lock()
                .unwrap_or_else(|error| error.into_inner());
            state.initialize_pressed(handles.base_pressed.load(Ordering::SeqCst), combos);
            handles
                .combo_pressed
                .store(state.combo_pressed(), Ordering::SeqCst);
            drop(state);
            handles.observing.store(true, Ordering::SeqCst);

            if status_tx.send(Ok(())).is_err() || handles.cancelled.load(Ordering::SeqCst) {
                handles.shutdown();
            }

            if !handles.cancelled.load(Ordering::SeqCst) {
                CFRunLoopRun();
            }

            if let Some(tap) = handles.tap.lock().unwrap_or_else(|e| e.into_inner()).take() {
                handles.observing.store(false, Ordering::SeqCst);
                CGEventTapEnable(tap, false);
                CFRelease(tap as *const c_void);
            }
            *handles.runloop.lock().unwrap_or_else(|e| e.into_inner()) = None;
            drop(Box::from_raw(context));
        }
    }

    extern "C" fn event_tap_callback(
        _proxy: *mut c_void,
        event_type: CgEventType,
        event: CgEventRef,
        user_info: *mut c_void,
    ) -> CgEventRef {
        if user_info.is_null() {
            return event;
        }
        if matches!(event_type, KEY_DOWN | KEY_UP | FLAGS_CHANGED) && event.is_null() {
            return event;
        }
        if matches!(event_type, KEY_DOWN | KEY_UP | FLAGS_CHANGED)
            && !event.is_null()
            && crate::output::event_identity::is_owned_keyboard_output(
                unsafe { CGEventGetIntegerValueField(event, 42) },
                unsafe { CGEventGetIntegerValueField(event, 41) },
            )
        {
            return event;
        }
        let context = unsafe { &*(user_info as *const CallbackContext) };
        if context
            .handles
            .suspend_requested
            .swap(false, Ordering::SeqCst)
        {
            context
                .state
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .suspend_actions();
        }

        match event_type {
            TAP_DISABLED_BY_TIMEOUT | TAP_DISABLED_BY_USER_INPUT => {
                context.handles.observing.store(false, Ordering::SeqCst);
                let mut state = context.state.lock().unwrap_or_else(|e| e.into_inner());
                state.invalidate_observation();
                drop(state);
            }
            FLAGS_CHANGED if handle_flags_changed(context, event) => {
                return std::ptr::null_mut();
            }
            KEY_DOWN | KEY_UP if handle_key_event(context, event, event_type == KEY_DOWN) => {
                return std::ptr::null_mut();
            }
            _ => {}
        }

        event
    }

    fn handle_flags_changed(context: &CallbackContext, event: CgEventRef) -> bool {
        let keycode = unsafe { CGEventGetIntegerValueField(event, KEYBOARD_EVENT_KEYCODE) };
        let flags = unsafe { CGEventGetFlags(event) };
        context
            .handles
            .base_pressed
            .store(flags & FLAG_MASK_SECONDARY_FN != 0, Ordering::SeqCst);
        if keycode == FN_KEYCODE {
            let pressed = (flags & FLAG_MASK_SECONDARY_FN) != 0;
            tracing::debug!(pressed, "Observed native Fn edge");
            let mut pending_generation = None;
            let business_accepted = dispatch_native_state_update(
                &context.state,
                &context.handler,
                |state, collector| {
                    let was_pressed = state.base_pressed;
                    tracing::debug!(
                        pressed,
                        was_pressed,
                        awaiting_release = state.awaiting_release,
                        held_combo_count = state.held_combos.len(),
                        pending_base_press = state.pending_base_press,
                        combo_used = state.combo_used,
                        "Native Fn dispatch state"
                    );
                    let accepted = dispatch_native_base_edge(
                        state,
                        &context.bindings,
                        NativeHotkeyTrigger::Fn,
                        pressed,
                        collector,
                    );
                    if !pressed && state.awaiting_release {
                        let keys = crate::shortcut_release::physical_key_snapshot().ok();
                        if reconcile_native_release(state, &context.bindings, keys.as_deref()) {
                            context.handles.combo_pressed.store(false, Ordering::SeqCst);
                            tracing::debug!("Native release barrier reconciled");
                        }
                    }
                    if pressed && !was_pressed && state.pending_base_press {
                        pending_generation = Some(state.edge_generation);
                    }
                    accepted
                },
            );
            tracing::debug!(pressed, business_accepted, "Native Fn dispatch outcome");
            if let Some(generation) = pending_generation {
                let state = Arc::clone(&context.state);
                let bindings = Arc::clone(&context.bindings);
                let handler = Arc::clone(&context.handler);
                let handles = Arc::clone(&context.handles);
                if let Err(error) = thread::Builder::new()
                    .name("opentypeless-fn-hold-delay".to_string())
                    .spawn(move || {
                        thread::sleep(HOLD_COMBO_GRACE_PERIOD);
                        if handles.cancelled.load(Ordering::SeqCst) {
                            return;
                        }
                        dispatch_native_state_update(&state, &handler, |state, collector| {
                            if handles.suspend_requested.swap(false, Ordering::SeqCst) {
                                state.suspend_actions();
                            }
                            activate_pending_base(
                                state,
                                &bindings,
                                NativeHotkeyTrigger::Fn,
                                generation,
                                collector,
                            )
                        });
                    })
                {
                    tracing::warn!("Failed to schedule Fn hold hotkey: {error}");
                }
            }
            let pending = context
                .state
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .pending_base_press;
            return context
                .consumption
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .key_event_with_acceptance(FN_KEYCODE as u16, pressed, || {
                    business_accepted || (pending && !context.handles.dispatch_gate.is_paused())
                });
        }

        if keycode == LEFT_SHIFT_KEYCODE {
            let pressed = (flags & FLAG_MASK_LEFT_SHIFT) != 0;
            dispatch_native_state_update(&context.state, &context.handler, |state, collector| {
                let accepted = dispatch_native_combo_edge(
                    state,
                    &context.bindings,
                    NativeHotkeyTrigger::Fn,
                    NativeComboKey::LeftShift,
                    pressed,
                    collector,
                );
                context
                    .handles
                    .combo_pressed
                    .store(state.combo_pressed(), Ordering::SeqCst);
                accepted
            });
        }
        false
    }

    fn handle_key_event(context: &CallbackContext, event: CgEventRef, pressed: bool) -> bool {
        let keycode = unsafe { CGEventGetIntegerValueField(event, KEYBOARD_EVENT_KEYCODE) };
        let Some(combo) = super::mac_combo_key(keycode) else {
            return false;
        };
        let fn_pressed = unsafe { CGEventGetFlags(event) } & FLAG_MASK_SECONDARY_FN != 0;
        let accepted =
            dispatch_native_state_update(&context.state, &context.handler, |state, collector| {
                if pressed
                    && fn_pressed
                    && !state.base_pressed
                    && !state.awaiting_release
                    && !context.handles.dispatch_gate.is_paused()
                    && NativeHotkeyTrigger::from_base_combo(NativeHotkeyTrigger::Fn, combo)
                        .is_some_and(|trigger| has_binding_for_trigger(&context.bindings, trigger))
                {
                    state.base_pressed = true;
                    state.pressed_at = Some(std::time::Instant::now());
                    state.edge_generation = state.edge_generation.wrapping_add(1);
                    context.handles.base_pressed.store(true, Ordering::SeqCst);
                }
                let accepted = dispatch_native_combo_edge(
                    state,
                    &context.bindings,
                    NativeHotkeyTrigger::Fn,
                    combo,
                    pressed,
                    collector,
                );
                context
                    .handles
                    .combo_pressed
                    .store(state.combo_pressed(), Ordering::SeqCst);
                accepted
            });
        let consumed = context
            .consumption
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .key_event_with_acceptance(keycode as u16, pressed, || accepted);
        consumed
    }

    #[cfg(test)]
    mod modifier_tests {
        use super::*;

        #[link(name = "CoreGraphics", kind = "framework")]
        extern "C" {
            fn CGEventSourceCreate(state: i32) -> *const c_void;
            fn CGEventCreateKeyboardEvent(
                source: *const c_void,
                keycode: u16,
                pressed: bool,
            ) -> CgEventRef;
            fn CGEventSetFlags(event: CgEventRef, flags: CgEventFlags);
            fn CGEventSetIntegerValueField(event: CgEventRef, field: CgEventField, value: i64);
            fn CGEventKeyboardSetUnicodeString(event: CgEventRef, length: usize, text: *const u16);
        }

        #[test]
        fn accepted_fn_edges_are_consumed_instead_of_forwarded_to_system() {
            let bindings = Arc::new(vec![NativeMonitoredBinding::new(
                super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Dictation,
                    index: 0,
                    trigger: NativeHotkeyTrigger::Fn,
                },
            )]);
            let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
            let dispatched = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let captured = dispatched.clone();
            let mut context = CallbackContext {
                bindings,
                handler: Arc::new(move |_: super::super::NativeHotkeyEvent| {
                    captured.fetch_add(1, Ordering::SeqCst);
                }),
                state: handles.state.clone(),
                handles,
                consumption: Mutex::new(Default::default()),
            };
            for pressed in [true, false] {
                let event = unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), 63, pressed) };
                assert!(!event.is_null());
                unsafe {
                    CGEventSetFlags(event, if pressed { FLAG_MASK_SECONDARY_FN } else { 0 });
                }
                let returned = event_tap_callback(
                    std::ptr::null_mut(),
                    FLAGS_CHANGED,
                    event,
                    (&mut context as *mut CallbackContext).cast(),
                );
                assert!(returned.is_null());
                unsafe { CFRelease(event as *const c_void) };
            }
            assert_eq!(dispatched.load(Ordering::SeqCst), 2);
        }

        #[test]
        fn owned_unicode_output_does_not_enter_configured_fn_combo() {
            let bindings = Arc::new(vec![NativeMonitoredBinding::new(
                super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Ask,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnKey('A'),
                },
            )]);
            let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
            let dispatched = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let captured = dispatched.clone();
            let mut context = CallbackContext {
                bindings,
                handler: Arc::new(move |_: super::super::NativeHotkeyEvent| {
                    captured.fetch_add(1, Ordering::SeqCst);
                }),
                state: handles.state.clone(),
                handles,
                consumption: Mutex::new(Default::default()),
            };
            context.state.lock().unwrap().base_pressed = true;
            let source = unsafe { CGEventSourceCreate(0) };
            assert!(!source.is_null());
            let event = unsafe { CGEventCreateKeyboardEvent(source, 0, true) };
            unsafe { CFRelease(source) };
            assert!(!event.is_null());
            let text = [
                u16::from(b't'),
                u16::from(b'e'),
                u16::from(b's'),
                u16::from(b't'),
            ];
            unsafe {
                CGEventSetFlags(event, FLAG_MASK_SECONDARY_FN);
                CGEventSetIntegerValueField(event, 42, 0x4f54_5950_454f_5554);
                CGEventKeyboardSetUnicodeString(event, text.len(), text.as_ptr());
            }
            assert_eq!(
                unsafe { CGEventGetIntegerValueField(event, 41) },
                i64::from(std::process::id())
            );
            context
                .handles
                .suspend_requested
                .store(true, Ordering::SeqCst);
            let returned = event_tap_callback(
                std::ptr::null_mut(),
                KEY_DOWN,
                event,
                (&mut context as *mut CallbackContext).cast(),
            );
            unsafe {
                CFRelease(event as *const c_void);
            }
            assert_eq!(returned, event);
            assert_eq!(dispatched.load(Ordering::SeqCst), 0);
            assert!(context.handles.suspend_requested.load(Ordering::SeqCst));
            assert!(!context
                .state
                .lock()
                .unwrap()
                .held_combos
                .contains(&NativeComboKey::Key('A')));
        }

        #[test]
        fn owned_output_release_preserves_physical_combo_and_consumption_owner() {
            let bindings = Arc::new(vec![NativeMonitoredBinding::new(
                super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Ask,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnKey('A'),
                },
            )]);
            let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
            let dispatched = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let captured = dispatched.clone();
            let mut context = CallbackContext {
                bindings,
                handler: Arc::new(move |_: super::super::NativeHotkeyEvent| {
                    captured.fetch_add(1, Ordering::SeqCst);
                }),
                state: handles.state.clone(),
                handles,
                consumption: Mutex::new(Default::default()),
            };
            context.state.lock().unwrap().base_pressed = true;
            assert_eq!(
                context.bindings[0].held.edge(true),
                Some(super::super::ShortcutState::Pressed)
            );
            context
                .state
                .lock()
                .unwrap()
                .held_combos
                .push(NativeComboKey::Key('A'));
            assert!(context
                .consumption
                .lock()
                .unwrap()
                .key_event_with_acceptance(0, true, || true));
            for (keycode, event_type) in [(0, KEY_UP), (63, FLAGS_CHANGED)] {
                let source = unsafe { CGEventSourceCreate(0) };
                assert!(!source.is_null());
                let event = unsafe { CGEventCreateKeyboardEvent(source, keycode, false) };
                unsafe { CFRelease(source) };
                assert!(!event.is_null());
                unsafe {
                    CGEventSetFlags(event, 0);
                    CGEventSetIntegerValueField(
                        event,
                        42,
                        crate::output::event_identity::KEYBOARD_OUTPUT_MARKER,
                    );
                }
                let returned = event_tap_callback(
                    std::ptr::null_mut(),
                    event_type,
                    event,
                    (&mut context as *mut CallbackContext).cast(),
                );
                unsafe { CFRelease(event as *const c_void) };
                assert_eq!(returned, event);
            }
            let state = context.state.lock().unwrap();
            assert!(state.base_pressed);
            assert_eq!(state.held_combos, vec![NativeComboKey::Key('A')]);
            assert!(context.bindings[0].held.held.load(Ordering::SeqCst));
            assert_eq!(dispatched.load(Ordering::SeqCst), 0);
            assert!(context
                .consumption
                .lock()
                .unwrap()
                .key_event_with_acceptance(0, false, || panic!("release must retain ownership")));
        }

        #[test]
        fn fn_space_flags_recover_a_missing_fn_edge_and_consume_the_combo() {
            let bindings = Arc::new(vec![NativeMonitoredBinding::new(
                super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Ask,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnSpace,
                },
            )]);
            let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
            let dispatched = Arc::new(std::sync::atomic::AtomicUsize::new(0));
            let captured = dispatched.clone();
            let mut context = CallbackContext {
                bindings,
                handler: Arc::new(move |_: super::super::NativeHotkeyEvent| {
                    captured.fetch_add(1, Ordering::SeqCst);
                }),
                state: handles.state.clone(),
                handles,
                consumption: Mutex::new(Default::default()),
            };
            for (pressed, event_type) in [(true, KEY_DOWN), (false, KEY_UP)] {
                let event = unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), 49, pressed) };
                assert!(!event.is_null());
                unsafe {
                    CGEventSetFlags(event, FLAG_MASK_SECONDARY_FN);
                }
                let returned = event_tap_callback(
                    std::ptr::null_mut(),
                    event_type,
                    event,
                    (&mut context as *mut CallbackContext).cast(),
                );
                assert!(returned.is_null());
                unsafe { CFRelease(event as *const c_void) };
            }
            assert_eq!(dispatched.load(Ordering::SeqCst), 2);
            assert!(context.state.lock().unwrap().base_pressed);
            assert!(context.state.lock().unwrap().held_combos.is_empty());
        }

        #[test]
        fn foreign_and_unmarked_events_still_reach_configured_fn_combo() {
            for (marker, process, keycode, trigger) in [
                (0x4f54_5950_454f_5554, i64::from(std::process::id()) + 1),
                (0, i64::from(std::process::id())),
                (100, i64::from(std::process::id())),
            ]
            .into_iter()
            .flat_map(|(marker, process)| {
                [
                    (marker, process, 0, NativeHotkeyTrigger::FnKey('A')),
                    (marker, process, 49, NativeHotkeyTrigger::FnSpace),
                ]
            }) {
                let bindings = Arc::new(vec![NativeMonitoredBinding::new(
                    super::super::NativeHotkeyBinding {
                        role: crate::hotkey::HotkeyRole::Ask,
                        index: 0,
                        trigger,
                    },
                )]);
                let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
                let dispatched = Arc::new(std::sync::atomic::AtomicUsize::new(0));
                let captured = dispatched.clone();
                let mut context = CallbackContext {
                    bindings,
                    handler: Arc::new(move |_: super::super::NativeHotkeyEvent| {
                        captured.fetch_add(1, Ordering::SeqCst);
                    }),
                    state: handles.state.clone(),
                    handles,
                    consumption: Mutex::new(Default::default()),
                };
                context.state.lock().unwrap().base_pressed = true;
                let event = unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), keycode, true) };
                assert!(!event.is_null());
                unsafe {
                    CGEventSetFlags(event, FLAG_MASK_SECONDARY_FN);
                    CGEventSetIntegerValueField(event, 42, marker);
                    CGEventSetIntegerValueField(event, 41, process);
                }
                let returned = event_tap_callback(
                    std::ptr::null_mut(),
                    KEY_DOWN,
                    event,
                    (&mut context as *mut CallbackContext).cast(),
                );
                unsafe { CFRelease(event as *const c_void) };
                assert!(returned.is_null());
                assert_eq!(dispatched.load(Ordering::SeqCst), 1);
            }
        }

        #[test]
        fn left_shift_release_with_right_shift_held_clears_mode_switch_barrier() {
            let bindings = Arc::new(vec![NativeMonitoredBinding::new(
                super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::TranslateSelection,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnLeftShift,
                },
            )]);
            let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
            let context = CallbackContext {
                bindings,
                handler: Arc::new(|_: super::super::NativeHotkeyEvent| {}),
                state: handles.state.clone(),
                handles,
                consumption: Mutex::new(Default::default()),
            };
            context
                .state
                .lock()
                .unwrap()
                .initialize_pressed(false, vec![NativeComboKey::LeftShift]);
            let event = unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), 56, false) };
            assert!(!event.is_null());
            unsafe {
                CGEventSetFlags(event, 0x0002_0000 | 0x0000_0004);
            }
            handle_flags_changed(&context, event);
            unsafe {
                CFRelease(event as *const c_void);
            }
            let state = context.state.lock().unwrap();
            assert!(
                !state.held_combos.contains(&NativeComboKey::LeftShift),
                "right Shift must not keep left Shift recorded as pressed"
            );
            assert!(!state.awaiting_release);
            assert!(!context.handles.combo_pressed.load(Ordering::SeqCst));
        }

        #[test]
        fn left_shift_press_uses_its_device_flag() {
            let bindings = Arc::new(vec![NativeMonitoredBinding::new(
                super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::TranslateSelection,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnLeftShift,
                },
            )]);
            let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
            let context = CallbackContext {
                bindings,
                handler: Arc::new(|_: super::super::NativeHotkeyEvent| {}),
                state: handles.state.clone(),
                handles,
                consumption: Mutex::new(Default::default()),
            };
            let event = unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), 56, true) };
            assert!(!event.is_null());
            unsafe {
                CGEventSetFlags(event, 0x0002_0000 | 0x0000_0002 | 0x0000_0004);
            }
            handle_flags_changed(&context, event);
            unsafe {
                CFRelease(event as *const c_void);
            }
            assert!(context
                .state
                .lock()
                .unwrap()
                .held_combos
                .contains(&NativeComboKey::LeftShift));
        }

        #[test]
        fn rejected_fn_and_plain_space_remain_available_to_the_system() {
            let bindings = Arc::new(vec![NativeMonitoredBinding::new(
                super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Dictation,
                    index: 0,
                    trigger: NativeHotkeyTrigger::Fn,
                },
            )]);
            let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
            let mut context = CallbackContext {
                bindings,
                handler: Arc::new(super::super::AcceptingNativeHandler(Arc::new(|_| false))),
                state: handles.state.clone(),
                handles,
                consumption: Mutex::default(),
            };
            for (keycode, event_type, pressed, flags) in [
                (63, FLAGS_CHANGED, true, FLAG_MASK_SECONDARY_FN),
                (63, FLAGS_CHANGED, false, 0),
                (49, KEY_DOWN, true, 0),
                (49, KEY_UP, false, 0),
            ] {
                let event =
                    unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), keycode, pressed) };
                assert!(!event.is_null());
                unsafe { CGEventSetFlags(event, flags) };
                let returned = event_tap_callback(
                    std::ptr::null_mut(),
                    event_type,
                    event,
                    (&mut context as *mut CallbackContext).cast(),
                );
                assert_eq!(returned, event);
                unsafe { CFRelease(event as *const c_void) };
            }
        }

        #[test]
        fn paused_fn_combo_does_not_consume_base_or_recover_a_missing_edge() {
            let bindings = Arc::new(vec![
                NativeMonitoredBinding::new(super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Dictation,
                    index: 0,
                    trigger: NativeHotkeyTrigger::Fn,
                }),
                NativeMonitoredBinding::new(super::super::NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Ask,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnSpace,
                }),
            ]);
            let handles = Arc::new(MacShutdownHandles::new(bindings.clone()));
            handles.dispatch_gate.pause();
            let mut context = CallbackContext {
                bindings,
                handler: Arc::new(super::super::AcceptingNativeHandler(Arc::new(|_| false))),
                state: handles.state.clone(),
                handles,
                consumption: Mutex::default(),
            };
            for (keycode, event_type) in [(63, FLAGS_CHANGED), (49, KEY_DOWN)] {
                let event = unsafe { CGEventCreateKeyboardEvent(std::ptr::null(), keycode, true) };
                assert!(!event.is_null());
                unsafe { CGEventSetFlags(event, FLAG_MASK_SECONDARY_FN) };
                let returned = event_tap_callback(
                    std::ptr::null_mut(),
                    event_type,
                    event,
                    (&mut context as *mut CallbackContext).cast(),
                );
                assert_eq!(returned, event);
                unsafe { CFRelease(event as *const c_void) };
            }
        }
    }
}

#[cfg(target_os = "windows")]
mod platform {
    use super::{
        activate_pending_base, dispatch_native_base_edge, dispatch_native_combo_edge,
        dispatch_native_state_update, monitored_bindings_for_base, NativeComboKey,
        NativeComboState, NativeHotkeyHandler, NativeHotkeyTrigger, NativeMonitoredBinding,
    };
    use std::ptr;
    use std::sync::atomic::{AtomicBool, AtomicPtr, AtomicU32, Ordering as AtomicOrdering};
    use std::sync::{mpsc, Arc};
    use std::thread;
    use std::time::Duration;
    use windows_sys::Win32::Foundation::{GetLastError, LPARAM, LRESULT, WPARAM};
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::System::Threading::GetCurrentThreadId;
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CallNextHookEx, DispatchMessageW, GetMessageW, KillTimer, PeekMessageW, PostThreadMessageW,
        SetTimer, SetWindowsHookExW, TranslateMessage, UnhookWindowsHookEx, HC_ACTION, HHOOK,
        KBDLLHOOKSTRUCT, LLKHF_INJECTED, MSG, PM_NOREMOVE, WH_KEYBOARD_LL, WM_QUIT, WM_TIMER,
    };

    const STARTUP_TIMEOUT: Duration = Duration::from_secs(3);
    const HOLD_COMBO_GRACE_PERIOD: Duration = Duration::from_millis(200);
    const ACCEPT_SYNTHETIC_EVENTS_ENV: &str = "OPENTYPELESS_ACCEPT_SYNTHETIC_HOTKEY_EVENTS";

    const WM_KEYDOWN: usize = 0x0100;
    const WM_KEYUP: usize = 0x0101;
    const WM_SYSKEYDOWN: usize = 0x0104;
    const WM_SYSKEYUP: usize = 0x0105;
    const VK_SPACE: u32 = 0x20;
    const VK_LSHIFT: u32 = 0xA0;
    const VK_RSHIFT: u32 = 0xA1;
    const VK_RMENU: u32 = 0xA5;

    static HOOK_CONTEXT: AtomicPtr<CallbackContext> = AtomicPtr::new(ptr::null_mut());

    pub struct PlatformNativeMonitor {
        thread_id: u32,
        thread: Option<thread::JoinHandle<()>>,
    }

    struct WindowsStartupState {
        thread_id: AtomicU32,
        cancelled: AtomicBool,
    }

    impl WindowsStartupState {
        fn new() -> Self {
            Self {
                thread_id: AtomicU32::new(0),
                cancelled: AtomicBool::new(false),
            }
        }

        fn cancel(&self) {
            self.cancelled.store(true, AtomicOrdering::SeqCst);
            let thread_id = self.thread_id.load(AtomicOrdering::SeqCst);
            if thread_id != 0 {
                let ok = unsafe { PostThreadMessageW(thread_id, WM_QUIT, 0, 0) };
                if ok == 0 {
                    tracing::warn!(
                        "Failed to post WM_QUIT to cancelled Windows native hotkey hook thread: {}",
                        unsafe { GetLastError() }
                    );
                }
            }
        }

        fn is_cancelled(&self) -> bool {
            self.cancelled.load(AtomicOrdering::SeqCst)
        }
    }

    impl PlatformNativeMonitor {
        pub fn start(
            bindings: Vec<super::NativeHotkeyBinding>,
            mode: super::InputMode,
            handler: NativeHotkeyHandler,
            _dispatch_gate: Arc<crate::native_dispatch_gate::NativeDispatchGate>,
        ) -> Result<Self, String> {
            let bindings = monitored_bindings_for_base(bindings, NativeHotkeyTrigger::RightAlt);
            if bindings.is_empty() {
                return Err("Windows native hotkeys currently support RightAlt only".to_string());
            }

            let (status_tx, status_rx) = mpsc::channel();
            let startup = Arc::new(WindowsStartupState::new());
            let thread_startup = Arc::clone(&startup);
            let thread = thread::Builder::new()
                .name("opentypeless-native-hotkey-win".to_string())
                .spawn(move || {
                    run_keyboard_hook_loop(bindings, mode, handler, thread_startup, status_tx)
                })
                .map_err(|error| {
                    format!("Failed to spawn Windows native hotkey monitor thread: {error}")
                })?;

            match status_rx.recv_timeout(STARTUP_TIMEOUT) {
                Ok(Ok(thread_id)) => Ok(Self {
                    thread_id,
                    thread: Some(thread),
                }),
                Ok(Err(error)) => {
                    startup.cancel();
                    let _ = thread.join();
                    Err(error)
                }
                Err(mpsc::RecvTimeoutError::Timeout) => {
                    startup.cancel();
                    let _ = thread.join();
                    Err("Timed out starting Windows native hotkey hook after 3 seconds".to_string())
                }
                Err(mpsc::RecvTimeoutError::Disconnected) => {
                    startup.cancel();
                    let _ = thread.join();
                    Err(
                        "Windows native hotkey hook thread exited before startup completed"
                            .to_string(),
                    )
                }
            }
        }
    }

    impl Drop for PlatformNativeMonitor {
        fn drop(&mut self) {
            let ok = unsafe { PostThreadMessageW(self.thread_id, WM_QUIT, 0, 0) };
            if ok == 0 {
                tracing::warn!(
                    "Failed to post WM_QUIT to Windows native hotkey hook thread: {}",
                    unsafe { GetLastError() }
                );
            }
            if let Some(thread) = self.thread.take() {
                if thread.thread().id() != thread::current().id() && thread.join().is_err() {
                    tracing::warn!("Windows native hotkey hook thread panicked during shutdown");
                }
            }
        }
    }

    struct CallbackContext {
        bindings: Vec<NativeMonitoredBinding>,
        handler: NativeHotkeyHandler,
        hook: std::sync::Mutex<Option<HHOOK>>,
        state: std::sync::Mutex<NativeComboState>,
        consumption: std::sync::Mutex<crate::shortcut_event_consumption::ShortcutEventConsumption>,
    }

    unsafe impl Send for CallbackContext {}
    unsafe impl Sync for CallbackContext {}

    fn run_keyboard_hook_loop(
        bindings: Vec<NativeMonitoredBinding>,
        mode: super::InputMode,
        handler: NativeHotkeyHandler,
        startup: Arc<WindowsStartupState>,
        status_tx: mpsc::Sender<Result<u32, String>>,
    ) {
        let thread_id = unsafe { GetCurrentThreadId() };

        unsafe {
            let mut message: MSG = std::mem::zeroed();
            let _ = PeekMessageW(&mut message, ptr::null_mut(), 0, 0, PM_NOREMOVE);
            startup.thread_id.store(thread_id, AtomicOrdering::SeqCst);

            if startup.is_cancelled() {
                let _ = status_tx.send(Err(
                    "Windows native hotkey hook startup was cancelled before install".to_string(),
                ));
                return;
            }

            let context = Box::into_raw(Box::new(CallbackContext {
                bindings,
                handler,
                hook: std::sync::Mutex::new(None),
                state: std::sync::Mutex::new(NativeComboState {
                    mode,
                    ..Default::default()
                }),
                consumption: std::sync::Mutex::default(),
            }));
            HOOK_CONTEXT.store(context, AtomicOrdering::SeqCst);

            // Global low-level hooks should be installed with the current module handle.
            let module = GetModuleHandleW(ptr::null());
            if module.is_null() {
                let error = GetLastError();
                let _ = HOOK_CONTEXT.compare_exchange(
                    context,
                    ptr::null_mut(),
                    AtomicOrdering::SeqCst,
                    AtomicOrdering::SeqCst,
                );
                drop(Box::from_raw(context));
                let _ = status_tx.send(Err(format!(
                    "Failed to resolve Windows module handle for native hotkey hook: Win32 error {error}"
                )));
                return;
            }

            let hook = SetWindowsHookExW(WH_KEYBOARD_LL, Some(low_level_keyboard_proc), module, 0);
            if hook.is_null() {
                let _ = HOOK_CONTEXT.compare_exchange(
                    context,
                    ptr::null_mut(),
                    AtomicOrdering::SeqCst,
                    AtomicOrdering::SeqCst,
                );
                drop(Box::from_raw(context));
                let _ = status_tx.send(Err(format!(
                    "Failed to install Windows native hotkey keyboard hook: Win32 error {}",
                    GetLastError()
                )));
                return;
            }
            *(*context).hook.lock().unwrap_or_else(|e| e.into_inner()) = Some(hook);

            if startup.is_cancelled() {
                cleanup_context(context);
                let _ = status_tx.send(Err(
                    "Windows native hotkey hook startup was cancelled after install".to_string(),
                ));
                return;
            }

            let timer = SetTimer(ptr::null_mut(), 0, 20, None);
            if timer == 0 {
                let error = GetLastError();
                cleanup_context(context);
                let _ = status_tx.send(Err(format!(
                    "Failed to start Windows shortcut timer: Win32 error {error}"
                )));
                return;
            }
            if status_tx.send(Ok(thread_id)).is_err() || startup.is_cancelled() {
                KillTimer(ptr::null_mut(), timer);
                cleanup_context(context);
                return;
            }

            loop {
                let result = GetMessageW(&mut message, ptr::null_mut(), 0, 0);
                if result <= 0 {
                    break;
                }
                if message.message == WM_TIMER && message.wParam == timer {
                    dispatch_native_state_update(
                        &(*context).state,
                        &(*context).handler,
                        |state, collector| {
                            if !state.pressed_at.is_some_and(|pressed_at| {
                                pressed_at.elapsed() >= HOLD_COMBO_GRACE_PERIOD
                            }) {
                                return false;
                            }
                            let generation = state.edge_generation;
                            activate_pending_base(
                                state,
                                &(*context).bindings,
                                NativeHotkeyTrigger::RightAlt,
                                generation,
                                collector,
                            )
                        },
                    );
                    continue;
                }
                let _ = TranslateMessage(&message);
                let _ = DispatchMessageW(&message);
            }

            KillTimer(ptr::null_mut(), timer);
            cleanup_context(context);
        }
    }

    unsafe fn cleanup_context(context: *mut CallbackContext) {
        if let Some(hook) = (*context)
            .hook
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .take()
        {
            if UnhookWindowsHookEx(hook) == 0 {
                tracing::warn!(
                    "Failed to unhook Windows native hotkey keyboard hook: {}",
                    GetLastError()
                );
            }
        }
        let _ = HOOK_CONTEXT.compare_exchange(
            context,
            ptr::null_mut(),
            AtomicOrdering::SeqCst,
            AtomicOrdering::SeqCst,
        );
        drop(Box::from_raw(context));
    }

    unsafe extern "system" fn low_level_keyboard_proc(
        code: i32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        if code == HC_ACTION as i32 && lparam != 0 {
            let keyboard = *(lparam as *const KBDLLHOOKSTRUCT);
            let injected = (keyboard.flags & LLKHF_INJECTED) != 0;
            if (!injected || accept_synthetic_events())
                && dispatch_keyboard_event(keyboard.vkCode, wparam)
            {
                return 1;
            }
        }

        CallNextHookEx(ptr::null_mut(), code, wparam, lparam)
    }

    fn dispatch_keyboard_event(vk_code: u32, message: WPARAM) -> bool {
        let pressed = match message {
            WM_KEYDOWN | WM_SYSKEYDOWN => true,
            WM_KEYUP | WM_SYSKEYUP => false,
            _ => return false,
        };

        let context = unsafe { callback_context() };
        let Some(context) = context else {
            return false;
        };

        let accepted =
            dispatch_native_state_update(&context.state, &context.handler, |state, collector| {
                match vk_code {
                    VK_RMENU => dispatch_native_base_edge(
                        state,
                        &context.bindings,
                        NativeHotkeyTrigger::RightAlt,
                        pressed,
                        collector,
                    ),
                    VK_SPACE => dispatch_native_combo_edge(
                        state,
                        &context.bindings,
                        NativeHotkeyTrigger::RightAlt,
                        NativeComboKey::Space,
                        pressed,
                        collector,
                    ),
                    VK_LSHIFT => dispatch_native_combo_edge(
                        state,
                        &context.bindings,
                        NativeHotkeyTrigger::RightAlt,
                        NativeComboKey::LeftShift,
                        pressed,
                        collector,
                    ),
                    VK_RSHIFT => dispatch_native_combo_edge(
                        state,
                        &context.bindings,
                        NativeHotkeyTrigger::RightAlt,
                        NativeComboKey::RightShift,
                        pressed,
                        collector,
                    ),
                    _ => false,
                }
            });
        if !matches!(vk_code, VK_RMENU | VK_SPACE | VK_LSHIFT | VK_RSHIFT) {
            return false;
        }
        context
            .consumption
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .key_event_with_acceptance(vk_code as u16, pressed, || accepted)
    }

    unsafe fn callback_context<'a>() -> Option<&'a CallbackContext> {
        let ptr = HOOK_CONTEXT.load(AtomicOrdering::SeqCst);
        if ptr.is_null() {
            None
        } else {
            Some(&*ptr)
        }
    }

    fn accept_synthetic_events() -> bool {
        std::env::var(ACCEPT_SYNTHETIC_EVENTS_ENV).ok().as_deref() == Some("1")
    }

    #[cfg(test)]
    mod timer_tests {
        use super::*;
        use windows_sys::Win32::UI::WindowsAndMessaging::PM_REMOVE;

        #[test]
        fn native_timer_delivers_to_its_message_thread_without_keyboard_injection() {
            thread::spawn(|| unsafe {
                let mut message: MSG = std::mem::zeroed();
                PeekMessageW(&mut message, ptr::null_mut(), 0, 0, PM_NOREMOVE);
                let timer = SetTimer(ptr::null_mut(), 0, 20, None);
                assert_ne!(timer, 0);
                let deadline = std::time::Instant::now() + Duration::from_secs(3);
                let mut delivered = false;
                while std::time::Instant::now() < deadline {
                    if PeekMessageW(&mut message, ptr::null_mut(), WM_TIMER, WM_TIMER, PM_REMOVE)
                        != 0
                        && message.wParam == timer
                    {
                        delivered = true;
                        break;
                    }
                    thread::sleep(Duration::from_millis(5));
                }
                let retired = KillTimer(ptr::null_mut(), timer);
                assert!(delivered, "Windows message timer did not deliver");
                assert_ne!(retired, 0);
            })
            .join()
            .unwrap();
        }
    }
}

#[cfg(all(not(target_os = "macos"), not(target_os = "windows")))]
mod platform {
    use super::{NativeHotkeyBinding, NativeHotkeyHandler};

    pub struct PlatformNativeMonitor;

    impl PlatformNativeMonitor {
        pub fn start(
            _bindings: Vec<NativeHotkeyBinding>,
            _mode: super::InputMode,
            _handler: NativeHotkeyHandler,
            _dispatch_gate: std::sync::Arc<crate::native_dispatch_gate::NativeDispatchGate>,
        ) -> Result<Self, String> {
            Err("Native hotkey runtime is unsupported on this platform".to_string())
        }
    }
}

#[cfg(test)]
mod tests {
    #[test]
    fn right_shift_translation_binding_does_not_match_left_shift() {
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = events.clone();
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event);
        });
        let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::TranslateSelection,
            index: 0,
            trigger: NativeHotkeyTrigger::RightAltRightShift,
        })];
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::RightAlt,
            true,
            &handler,
        );
        assert!(!dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::RightAlt,
            NativeComboKey::LeftShift,
            true,
            &handler,
        ));
        assert!(dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::RightAlt,
            NativeComboKey::RightShift,
            true,
            &handler,
        ));
        assert_eq!(events.lock().unwrap().len(), 1);
    }

    #[test]
    fn windows_hook_loop_activates_pending_base_and_retires_its_timer() {
        let windows = include_str!("native_hotkey.rs")
            .split("#[cfg(target_os = \"windows\")]\nmod platform")
            .nth(1)
            .unwrap()
            .split("#[cfg(all(not(target_os")
            .next()
            .unwrap();
        assert!(windows.contains("SetTimer("));
        assert!(windows.contains("WM_TIMER"));
        assert!(windows.contains("activate_pending_base("));
        assert!(windows.contains("KillTimer("));
        assert!(windows.contains("pressed_at.elapsed() >= HOLD_COMBO_GRACE_PERIOD"));
    }

    #[test]
    fn windows_monitor_passes_selected_mode_into_native_state() {
        let windows = include_str!("native_hotkey.rs")
            .split("#[cfg(target_os = \"windows\")]\nmod platform")
            .nth(1)
            .unwrap()
            .split("#[cfg(all(not(target_os")
            .next()
            .unwrap();
        assert!(!windows.contains("_mode: super::InputMode"));
        assert!(windows.contains("run_keyboard_hook_loop(bindings, mode,"));
        assert!(windows.contains("NativeComboState {\n                    mode,"));
    }

    #[test]
    fn right_alt_with_combo_activates_base_before_release() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::RightAlt,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::RightAltSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = events.clone();
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event.state);
        });
        let mut state = NativeComboState {
            mode: InputMode::Hold,
            ..Default::default()
        };
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::RightAlt,
            true,
            &handler,
        );
        assert!(state.pending_base_press);
        assert!(events.lock().unwrap().is_empty());
        let generation = state.edge_generation;
        assert!(activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::RightAlt,
            generation,
            &handler,
        ));
        assert_eq!(*events.lock().unwrap(), vec![ShortcutState::Pressed]);
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::RightAlt,
            false,
            &handler,
        );
        assert_eq!(
            *events.lock().unwrap(),
            vec![ShortcutState::Pressed, ShortcutState::Released]
        );
        let windows = include_str!("native_hotkey.rs")
            .split("#[cfg(target_os = \"windows\")]\nmod platform")
            .nth(1)
            .unwrap()
            .split("#[cfg(not(any(")
            .next()
            .unwrap();
        assert!(windows.contains("activate_pending_base("));
    }

    #[test]
    fn startup_combo_snapshot_filters_by_actual_native_bindings() {
        let source = include_str!("native_hotkey.rs");
        let startup = source
            .split("            let combos = if CGPreflightListenEventAccess() {")
            .nth(1)
            .unwrap()
            .split("            let mut state = handles")
            .next()
            .unwrap();
        assert!(startup.contains("NativeHotkeyTrigger::from_base_combo("));
        assert!(startup.contains("super::has_binding_for_trigger(&handles.bindings, trigger)"));
    }

    #[test]
    fn configured_combo_release_is_not_blocked_by_an_unconfigured_held_key() {
        let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::Ask,
            index: 0,
            trigger: NativeHotkeyTrigger::FnSpace,
        })];
        let handler: NativeHotkeyHandler = Arc::new(|_: NativeHotkeyEvent| {});
        let mut state = NativeComboState::default();
        for combo in [NativeComboKey::Space, NativeComboKey::Key('A')] {
            dispatch_native_combo_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                combo,
                true,
                &handler,
            );
        }
        state.reconfigure_mode(InputMode::Hold);
        assert!(state.awaiting_release);
        assert_eq!(state.held_combos, vec![NativeComboKey::Space]);
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            false,
            &handler,
        );
        assert!(!state.awaiting_release);
        assert!(state.held_combos.is_empty());
    }

    #[test]
    fn unconfigured_combo_key_cannot_block_fn_after_mode_change() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let handler: NativeHotkeyHandler = Arc::new(|_: NativeHotkeyEvent| {});
        let mut state = NativeComboState::default();
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Key('A'),
            true,
            &handler,
        );
        state.reconfigure_mode(InputMode::Hold);
        assert!(state.held_combos.is_empty());
        assert!(!state.awaiting_release);
    }

    #[test]
    fn physical_release_reconciliation_resets_binding_edges_without_fabricating_a_release() {
        let mut state = NativeComboState::default();
        state.initialize_pressed(false, vec![NativeComboKey::Space]);
        let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::Dictation,
            index: 0,
            trigger: NativeHotkeyTrigger::Fn,
        })];
        assert_eq!(bindings[0].held.edge(true), Some(ShortcutState::Pressed));
        assert!(!reconcile_native_release(&mut state, &bindings, None));
        assert!(bindings[0].held.held.load(Ordering::SeqCst));
        assert!(reconcile_native_release(&mut state, &bindings, Some(&[])));
        assert_eq!(bindings[0].held.edge(false), None);
        assert_eq!(bindings[0].held.edge(true), Some(ShortcutState::Pressed));
    }

    #[test]
    fn confirmed_physical_release_retires_stale_combo_without_dispatching_recording() {
        let mut state = NativeComboState::default();
        state.initialize_pressed(false, vec![NativeComboKey::Space]);
        state.reconfigure_mode(InputMode::Hold);
        let generation = state.edge_generation;
        assert!(!state.reconcile_release(None));
        assert!(!state.reconcile_release(Some(&[63])));
        assert!(state.awaiting_release);
        assert_eq!(state.held_combos.len(), 1);
        assert!(state.reconcile_release(Some(&[])));
        assert!(!state.awaiting_release);
        assert!(state.held_combos.is_empty());
        assert!(!state.base_pressed);
        assert!(!state.pending_base_press);
        assert!(state.edge_generation > generation);
        assert!(!state.reconcile_release(Some(&[])));
    }

    #[test]
    fn reconciled_mode_change_accepts_only_the_next_real_press_and_release() {
        for mode in [InputMode::Auto, InputMode::Toggle, InputMode::Hold] {
            let mut state = NativeComboState::default();
            state.initialize_pressed(false, vec![NativeComboKey::Space]);
            state.reconfigure_mode(mode);
            let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            })];
            let events = Arc::new(Mutex::new(Vec::new()));
            let recorded = events.clone();
            let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
                recorded.lock().unwrap().push(event.state);
            });
            let old_generation = state.edge_generation;
            assert!(state.reconcile_release(Some(&[])));
            assert!(events.lock().unwrap().is_empty());
            assert!(!activate_pending_base(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                old_generation,
                &handler,
            ));
            dispatch_native_base_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                true,
                &handler,
            );
            dispatch_native_base_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                false,
                &handler,
            );
            assert_eq!(
                *events.lock().unwrap(),
                vec![ShortcutState::Pressed, ShortcutState::Released]
            );
        }
    }

    #[test]
    fn staged_dispatch_keeps_release_after_the_preceding_press_handler() {
        let state = Arc::new(Mutex::new(NativeComboState::default()));
        let events = Arc::new(Mutex::new(Vec::new()));
        let recorded = events.clone();
        let (entered, started) = std::sync::mpsc::channel();
        let (resume, continuation) = std::sync::mpsc::channel();
        let continuation = Mutex::new(continuation);
        let handler: NativeHotkeyHandler =
            Arc::new(AcceptingNativeHandler(Arc::new(move |event| {
                if event.state == ShortcutState::Pressed {
                    entered.send(()).unwrap();
                    continuation
                        .lock()
                        .unwrap()
                        .recv_timeout(std::time::Duration::from_secs(5))
                        .unwrap();
                }
                recorded.lock().unwrap().push(event.state);
                true
            })));
        let binding = Arc::new(vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::Dictation,
            index: 0,
            trigger: NativeHotkeyTrigger::Fn,
        })]);
        let press_state = state.clone();
        let press_handler = handler.clone();
        let press_binding = binding.clone();
        let press = std::thread::spawn(move || {
            dispatch_native_state_update(&press_state, &press_handler, |state, collector| {
                dispatch_native_base_edge(
                    state,
                    &press_binding,
                    NativeHotkeyTrigger::Fn,
                    true,
                    collector,
                )
            })
        });
        started
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        assert!(state.try_lock().is_ok());
        let release = std::thread::spawn(move || {
            dispatch_native_state_update(&state, &handler, |state, collector| {
                dispatch_native_base_edge(
                    state,
                    &binding,
                    NativeHotkeyTrigger::Fn,
                    false,
                    collector,
                )
            })
        });
        resume.send(()).unwrap();
        assert!(press.join().unwrap());
        assert!(release.join().unwrap());
        assert_eq!(
            *events.lock().unwrap(),
            vec![ShortcutState::Pressed, ShortcutState::Released]
        );
    }

    #[test]
    fn physical_edge_dispatch_releases_native_state_before_business_acceptance() {
        let state = Arc::new(Mutex::new(NativeComboState::default()));
        let observed_state = state.clone();
        let handler: NativeHotkeyHandler = Arc::new(AcceptingNativeHandler(Arc::new(move |_| {
            assert!(observed_state.try_lock().is_ok());
            true
        })));
        let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::Dictation,
            index: 0,
            trigger: NativeHotkeyTrigger::Fn,
        })];
        assert!(dispatch_native_state_update(
            &state,
            &handler,
            |state, collector| {
                dispatch_native_base_edge(
                    state,
                    &bindings,
                    NativeHotkeyTrigger::Fn,
                    true,
                    collector,
                )
            }
        ));
    }

    #[test]
    fn pause_rejects_new_dispatch_but_does_not_interrupt_an_in_flight_handler() {
        let runtime = NativeHotkeyRuntime::default();
        let (entered_sender, entered_receiver) = std::sync::mpsc::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let (finished_sender, finished_receiver) = std::sync::mpsc::channel();
        let release_receiver = Mutex::new(release_receiver);
        let handler: NativeHotkeyHandler = Arc::new(AcceptingNativeHandler(Arc::new(move |_| {
            entered_sender.send(()).unwrap();
            release_receiver
                .lock()
                .unwrap()
                .recv_timeout(std::time::Duration::from_secs(5))
                .unwrap();
            true
        })));
        let gate = runtime.dispatch_paused.clone();
        let worker_handler = handler.clone();
        let event = NativeHotkeyEvent {
            parent_trigger: None,
            occurred_at: std::time::Instant::now(),
            role: crate::hotkey::HotkeyRole::Dictation,
            index: 0,
            state: ShortcutState::Pressed,
        };
        let worker = std::thread::spawn(move || {
            let accepted = dispatch_when_enabled(&gate, event, &worker_handler);
            finished_sender.send(accepted).unwrap();
        });
        let entered = entered_receiver.recv_timeout(std::time::Duration::from_secs(2));
        runtime.pause();
        let still_running = !worker.is_finished();
        let rejects_new = !dispatch_when_enabled(
            &runtime.dispatch_paused,
            NativeHotkeyEvent {
                parent_trigger: None,
                occurred_at: std::time::Instant::now(),
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                state: ShortcutState::Pressed,
            },
            &handler,
        );
        let _ = release_sender.send(());
        let completed = finished_receiver.recv_timeout(std::time::Duration::from_secs(2));
        worker.join().unwrap();
        assert!(entered.is_ok());
        assert!(still_running);
        assert!(rejects_new);
        assert!(completed.unwrap());
    }

    #[test]
    fn paused_native_dispatch_returns_rejection_without_calling_business_handler() {
        let paused = crate::native_dispatch_gate::NativeDispatchGate::default();
        paused.pause();
        let handler: NativeHotkeyHandler = Arc::new(AcceptingNativeHandler(Arc::new(|_| {
            panic!("paused monitor cannot claim a voice operation")
        })));
        assert!(!dispatch_when_enabled(
            &paused,
            NativeHotkeyEvent {
                parent_trigger: Some(NativeHotkeyTrigger::Fn),
                occurred_at: std::time::Instant::now(),
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                state: ShortcutState::Pressed,
            },
            &handler,
        ));
    }

    #[test]
    fn combo_grace_expiry_changes_ask_acceptance_and_space_consumption() {
        use crate::input_session::{InputAction, InputBinding, InputSession, VoiceRole};

        for mode in [InputMode::Auto, InputMode::Hold, InputMode::Toggle] {
            for expire_grace in [false, true] {
                let bindings = vec![
                    NativeMonitoredBinding::new(NativeHotkeyBinding {
                        role: crate::hotkey::HotkeyRole::Dictation,
                        index: 0,
                        trigger: NativeHotkeyTrigger::Fn,
                    }),
                    NativeMonitoredBinding::new(NativeHotkeyBinding {
                        role: crate::hotkey::HotkeyRole::Ask,
                        index: 0,
                        trigger: NativeHotkeyTrigger::FnSpace,
                    }),
                ];
                let mut input = InputSession::default();
                input.commit_bindings(
                    7,
                    vec![
                        InputBinding {
                            role: VoiceRole::Dictation,
                            binding_id: "Fn".into(),
                            mode,
                            revision: 7,
                        },
                        InputBinding {
                            role: VoiceRole::Ask,
                            binding_id: "Fn+Space".into(),
                            mode,
                            revision: 7,
                        },
                    ],
                );
                input.confirm_installation(7);
                let input = Arc::new(Mutex::new(input));
                let starts = Arc::new(Mutex::new(Vec::new()));
                let captured_input = Arc::clone(&input);
                let captured_starts = Arc::clone(&starts);
                let handler: NativeHotkeyHandler =
                    Arc::new(AcceptingNativeHandler(Arc::new(move |event| {
                        let mut input = captured_input.lock().unwrap();
                        let previous = input.press_id();
                        let (binding_id, role) = match event.role {
                            crate::hotkey::HotkeyRole::Ask => ("Fn+Space", VoiceRole::Ask),
                            _ => ("Fn", VoiceRole::Dictation),
                        };
                        let actions = match event.state {
                            ShortcutState::Pressed if event.parent_trigger.is_some() => {
                                input.press_combo_committed("Fn", binding_id, role, 250)
                            }
                            ShortcutState::Pressed => input.press_committed(binding_id, role, 10),
                            ShortcutState::Released => input.release(binding_id, 300),
                        };
                        for action in &actions {
                            if let InputAction::Start { binding, .. } = action {
                                captured_starts.lock().unwrap().push(binding.role);
                            }
                        }
                        !actions.is_empty() || previous != input.press_id()
                    })));
                let mut state = NativeComboState {
                    mode,
                    ..NativeComboState::default()
                };
                dispatch_native_base_edge(
                    &mut state,
                    &bindings,
                    NativeHotkeyTrigger::Fn,
                    true,
                    &handler,
                );
                if expire_grace {
                    let generation = state.edge_generation;
                    assert!(activate_pending_base(
                        &mut state,
                        &bindings,
                        NativeHotkeyTrigger::Fn,
                        generation,
                        &handler,
                    ));
                }
                let accepted = dispatch_native_combo_edge(
                    &mut state,
                    &bindings,
                    NativeHotkeyTrigger::Fn,
                    NativeComboKey::Space,
                    true,
                    &handler,
                );
                let should_accept = !expire_grace || mode == InputMode::Auto;
                let mut consumption =
                    crate::shortcut_event_consumption::ShortcutEventConsumption::default();
                assert_eq!(accepted, should_accept);
                assert_eq!(
                    consumption.key_event_with_acceptance(49, true, || accepted),
                    should_accept
                );
                assert_eq!(
                    consumption.key_event_with_acceptance(49, false, || false),
                    should_accept
                );
                let expected_role = if should_accept {
                    VoiceRole::Ask
                } else {
                    VoiceRole::Dictation
                };
                assert_eq!(*starts.lock().unwrap(), vec![expected_role]);
                assert_eq!(
                    input.lock().unwrap().session_binding().unwrap().role,
                    expected_role
                );
            }
        }
    }

    #[test]
    fn native_combo_acceptance_is_the_same_claim_that_reserves_the_ask_session() {
        use crate::input_session::{InputBinding, InputSession, VoiceRole};
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let mut input = InputSession::default();
        input.commit_bindings(
            7,
            vec![
                InputBinding {
                    role: VoiceRole::Dictation,
                    binding_id: "Fn".into(),
                    mode: InputMode::Auto,
                    revision: 7,
                },
                InputBinding {
                    role: VoiceRole::Ask,
                    binding_id: "Fn+Space".into(),
                    mode: InputMode::Auto,
                    revision: 7,
                },
            ],
        );
        input.confirm_installation(7);
        let input = Arc::new(Mutex::new(input));
        let captured = input.clone();
        let handler: NativeHotkeyHandler =
            Arc::new(AcceptingNativeHandler(Arc::new(move |event| {
                let mut input = captured.lock().unwrap();
                let (binding, role) = match event.role {
                    crate::hotkey::HotkeyRole::Ask => ("Fn+Space", VoiceRole::Ask),
                    _ => ("Fn", VoiceRole::Dictation),
                };
                let previous = input.press_id();
                let actions = match event.state {
                    ShortcutState::Pressed => input.press_combo_committed("Fn", binding, role, 10),
                    ShortcutState::Released => input.release(binding, 100),
                };
                !actions.is_empty() || previous != input.press_id()
            })));
        let mut state = NativeComboState::default();
        let mut consumption =
            crate::shortcut_event_consumption::ShortcutEventConsumption::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let accepted = dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        );
        assert!(consumption.key_event_with_acceptance(49, true, || accepted));
        let session_id = input.lock().unwrap().session_id().unwrap();
        assert!(input
            .lock()
            .unwrap()
            .owns_session_binding("Fn+Space", VoiceRole::Ask));
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert!(consumption.key_event_with_acceptance(49, false, || false));
        assert_eq!(input.lock().unwrap().session_id(), Some(session_id));
    }

    #[test]
    fn native_rejected_combo_does_not_consume_or_retry_its_held_press() {
        let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::Ask,
            index: 0,
            trigger: NativeHotkeyTrigger::FnSpace,
        })];
        let calls = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let recorded = calls.clone();
        let handler: NativeHotkeyHandler = Arc::new(AcceptingNativeHandler(Arc::new(move |_| {
            recorded.fetch_add(1, Ordering::SeqCst);
            false
        })));
        let mut state = NativeComboState::default();
        let mut consumption =
            crate::shortcut_event_consumption::ShortcutEventConsumption::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        for _ in 0..3 {
            let accepted = dispatch_native_combo_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                NativeComboKey::Space,
                true,
                &handler,
            );
            assert!(!consumption.key_event_with_acceptance(49, true, || accepted));
        }
        assert_eq!(calls.load(Ordering::SeqCst), 1);
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert!(!consumption.key_event_with_acceptance(49, false, || false));
    }

    #[test]
    fn native_accepted_combo_consumes_repeats_and_release_after_base_release() {
        let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::Ask,
            index: 0,
            trigger: NativeHotkeyTrigger::FnSpace,
        })];
        let events = Arc::new(Mutex::new(Vec::new()));
        let recorded = events.clone();
        let handler: NativeHotkeyHandler =
            Arc::new(AcceptingNativeHandler(Arc::new(move |event| {
                recorded.lock().unwrap().push(event.state);
                true
            })));
        let mut state = NativeComboState::default();
        let mut consumption =
            crate::shortcut_event_consumption::ShortcutEventConsumption::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let accepted = dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        );
        assert!(consumption.key_event_with_acceptance(49, true, || accepted));
        assert!(consumption.key_event_with_acceptance(49, true, || false));
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert!(consumption.key_event_with_acceptance(49, false, || false));
        assert_eq!(
            *events.lock().unwrap(),
            vec![ShortcutState::Pressed, ShortcutState::Released]
        );
    }

    #[test]
    fn legacy_consumption_wrapper_preserves_first_press_ownership() {
        let mut consumption =
            crate::shortcut_event_consumption::ShortcutEventConsumption::default();
        assert!(!consumption.key_event(49, true, true, false));
        assert!(!consumption.key_event(49, true, true, true));
        assert!(!consumption.key_event(49, false, true, true));
    }

    #[test]
    fn handler_replacement_preserves_in_flight_capture_until_the_call_returns() {
        let capture = Arc::new(());
        let retired_capture = Arc::downgrade(&capture);
        let (entered, started) = std::sync::mpsc::channel();
        let (release, completion) = std::sync::mpsc::channel();
        let completion = Mutex::new(completion);
        let slot = Arc::new(NativeHandlerSlot::new(Arc::new(move |_| {
            let _ = &capture;
            entered.send(()).unwrap();
            completion
                .lock()
                .unwrap()
                .recv_timeout(std::time::Duration::from_secs(5))
                .unwrap();
        })));
        let event = NativeHotkeyEvent {
            parent_trigger: None,
            occurred_at: std::time::Instant::now(),
            role: crate::hotkey::HotkeyRole::Dictation,
            index: 0,
            state: ShortcutState::Pressed,
        };
        let worker_slot = slot.clone();
        let worker = std::thread::spawn(move || worker_slot.dispatch(event));
        started
            .recv_timeout(std::time::Duration::from_secs(5))
            .unwrap();
        let new_calls = Arc::new(std::sync::atomic::AtomicU64::new(0));
        let recorded_calls = new_calls.clone();
        let previous = slot.replace(Arc::new(move |_| {
            recorded_calls.fetch_add(1, Ordering::SeqCst);
        }));
        drop(previous);
        assert!(retired_capture.upgrade().is_some());
        slot.dispatch(event);
        assert_eq!(new_calls.load(Ordering::SeqCst), 1);
        release.send(()).unwrap();
        worker.join().unwrap();
        assert!(retired_capture.upgrade().is_none());
    }

    #[test]
    fn replacing_handler_releases_the_previous_capture_outside_the_slot_lock() {
        struct CapturedResource {
            slot: std::sync::Weak<NativeHandlerSlot>,
            released: Arc<std::sync::atomic::AtomicBool>,
        }

        impl Drop for CapturedResource {
            fn drop(&mut self) {
                if let Some(slot) = self.slot.upgrade() {
                    assert!(slot.handler.try_lock().is_ok());
                }
                self.released.store(true, Ordering::SeqCst);
            }
        }

        let slot = Arc::new(NativeHandlerSlot::new(Arc::new(|_| {})));
        let released = Arc::new(std::sync::atomic::AtomicBool::new(false));
        let resource = CapturedResource {
            slot: Arc::downgrade(&slot),
            released: released.clone(),
        };
        slot.replace(Arc::new(move |_| {
            let _ = &resource;
        }));
        slot.replace(Arc::new(|_| {}));
        assert!(released.load(Ordering::SeqCst));
    }

    #[test]
    fn reused_monitor_dispatches_to_the_latest_handler_without_holding_its_lock() {
        let received = Arc::new(Mutex::new(Vec::new()));
        let old_received = received.clone();
        let handlers = Arc::new(NativeHandlerSlot::new(Arc::new(move |_| {
            old_received.lock().unwrap().push("old");
        })));
        let current_received = received.clone();
        let current_handlers = Arc::downgrade(&handlers);
        handlers.replace(Arc::new(move |_| {
            assert!(current_handlers
                .upgrade()
                .unwrap()
                .handler
                .try_lock()
                .is_ok());
            current_received.lock().unwrap().push("new");
        }));
        handlers.dispatch(NativeHotkeyEvent {
            parent_trigger: None,
            occurred_at: std::time::Instant::now(),
            role: crate::hotkey::HotkeyRole::Dictation,
            index: 0,
            state: ShortcutState::Pressed,
        });
        assert_eq!(*received.lock().unwrap(), vec!["new"]);
    }

    #[test]
    fn unchanged_mode_reconfiguration_preserves_the_pending_physical_press() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = events.clone();
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event.state);
        });
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let generation = state.edge_generation;
        state.reconfigure_mode(InputMode::Auto);
        assert_eq!(state.edge_generation, generation);
        assert!(activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler,
        ));
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert_eq!(
            *events.lock().unwrap(),
            vec![ShortcutState::Pressed, ShortcutState::Released]
        );
    }

    #[test]
    fn late_combo_does_not_forge_or_lose_the_base_release_in_any_mode() {
        for mode in [InputMode::Auto, InputMode::Toggle, InputMode::Hold] {
            let bindings = vec![
                NativeMonitoredBinding::new(NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Dictation,
                    index: 0,
                    trigger: NativeHotkeyTrigger::Fn,
                }),
                NativeMonitoredBinding::new(NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Ask,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnSpace,
                }),
            ];
            let events = Arc::new(Mutex::new(Vec::new()));
            let captured = events.clone();
            let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
                captured.lock().unwrap().push((event.role, event.state));
            });
            let mut state = NativeComboState {
                mode,
                ..Default::default()
            };
            dispatch_native_base_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                true,
                &handler,
            );
            let generation = state.edge_generation;
            activate_pending_base(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                generation,
                &handler,
            );
            dispatch_native_combo_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                NativeComboKey::Space,
                true,
                &handler,
            );
            assert_eq!(
                *events.lock().unwrap(),
                vec![
                    (crate::hotkey::HotkeyRole::Dictation, ShortcutState::Pressed),
                    (crate::hotkey::HotkeyRole::Ask, ShortcutState::Pressed),
                ],
                "{mode:?}"
            );
            dispatch_native_base_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                false,
                &handler,
            );
            let recorded = events.lock().unwrap();
            assert_eq!(
                recorded
                    .iter()
                    .filter(|(role, edge)| {
                        *role == crate::hotkey::HotkeyRole::Dictation
                            && *edge == ShortcutState::Released
                    })
                    .count(),
                1,
                "{mode:?}"
            );
        }
    }

    use super::*;
    use std::time::Duration;

    #[test]
    fn hold_release_before_combo_window_preserves_both_physical_edges() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = events.clone();
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event.state);
        });
        let mut state = NativeComboState {
            mode: InputMode::Hold,
            ..Default::default()
        };
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert_eq!(
            *events.lock().unwrap(),
            [ShortcutState::Pressed, ShortcutState::Released]
        );
    }

    #[test]
    fn physical_base_activation_delegates_auto_hold_semantics_to_the_session() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let clock = std::time::Instant::now() - Duration::from_secs(1);
        let session = Arc::new(Mutex::new(crate::input_session::InputSession::default()));
        let effects = Arc::new(Mutex::new(Vec::new()));
        let captured_session = session.clone();
        let captured_effects = effects.clone();
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            let now_ms = event
                .occurred_at
                .saturating_duration_since(clock)
                .as_millis() as u64;
            let mut session = captured_session.lock().unwrap();
            let actions = match event.state {
                ShortcutState::Pressed => session.press(
                    crate::input_session::InputBinding {
                        role: crate::input_session::VoiceRole::Dictation,
                        binding_id: "Fn".into(),
                        mode: InputMode::Auto,
                        revision: 1,
                    },
                    now_ms,
                ),
                ShortcutState::Released => session.release("Fn", now_ms),
            };
            captured_effects.lock().unwrap().extend(actions);
        });
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        state.pressed_at = Some(clock + Duration::from_millis(100));
        let generation = state.edge_generation;
        assert!(activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler
        ));
        assert!(effects.lock().unwrap().is_empty());
        let mut session = session.lock().unwrap();
        let press_id = session.press_id().unwrap();
        assert_eq!(
            session.long_press_due(press_id, 700),
            vec![crate::input_session::InputAction::ShowGuide { press_id }]
        );
        drop(session);
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert_eq!(
            effects.lock().unwrap().as_slice(),
            &[crate::input_session::InputAction::HideGuide { press_id }]
        );
    }

    #[test]
    fn delayed_base_dispatch_preserves_physical_press_time() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = events.clone();
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event);
        });
        let mut state = NativeComboState {
            mode: InputMode::Toggle,
            ..Default::default()
        };
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let physical_press = std::time::Instant::now() - std::time::Duration::from_millis(100);
        state.pressed_at = Some(physical_press);
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        let events = events.lock().unwrap();
        assert_eq!(events.len(), 2);
        assert_eq!(events[0].occurred_at, physical_press);
        assert!(events[1].occurred_at >= physical_press + std::time::Duration::from_millis(100));
    }

    #[test]
    fn mode_change_invalidates_old_timer_and_waits_for_held_keys() {
        let mut state = NativeComboState::default();
        state.initialize_pressed(true, vec![NativeComboKey::Space]);
        let previous_generation = state.edge_generation;
        state.reconfigure_mode(InputMode::Toggle);
        assert_eq!(state.mode, InputMode::Toggle);
        assert!(state.awaiting_release);
        assert_ne!(state.edge_generation, previous_generation);
        assert!(!state.pending_base_press);
        assert!(state.pressed_at.is_none());
        state.base_pressed = false;
        state.held_combos.clear();
        state.reconfigure_mode(InputMode::Hold);
        assert_eq!(state.mode, InputMode::Hold);
        assert!(!state.awaiting_release);
    }

    #[test]
    fn toggle_hold_never_claims_an_auto_guide_or_discards_its_release() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event);
        });
        let mut state = NativeComboState {
            mode: crate::input_session::InputMode::Toggle,
            ..Default::default()
        };
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        state.pressed_at = Some(std::time::Instant::now() - crate::hotkey::AUTO_MODE_LONG_PRESS);
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        let events = events.lock().unwrap();
        assert_eq!(events.len(), 2);
        assert_eq!(events[0].state, ShortcutState::Pressed);
        assert_eq!(events[1].state, ShortcutState::Released);
    }

    #[test]
    fn pause_does_not_wait_for_platform_installation_lock() {
        let runtime = NativeHotkeyRuntime::default();
        let guard = runtime.inner.lock().unwrap();
        let paused = runtime.clone();
        let (sender, receiver) = std::sync::mpsc::channel();
        let worker = std::thread::spawn(move || {
            paused.pause();
            sender.send(()).unwrap();
        });
        let result = receiver.recv_timeout(std::time::Duration::from_millis(300));
        drop(guard);
        worker.join().unwrap();
        assert!(result.is_ok(), "pause waited for platform installation");
        assert!(runtime.dispatch_paused.is_paused());
    }

    #[test]
    fn initially_held_keys_must_release_before_the_first_shortcut() {
        let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::Ask,
            index: 0,
            trigger: NativeHotkeyTrigger::FnSpace,
        })];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event);
        });
        let mut state = NativeComboState::default();
        state.initialize_pressed(true, vec![NativeComboKey::Space]);
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        assert!(events.lock().unwrap().is_empty());
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            false,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        );
        assert_eq!(events.lock().unwrap().len(), 1);
    }

    #[test]
    fn paused_press_cannot_dispatch_after_resume_until_all_physical_keys_release() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event);
        });
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let generation = state.edge_generation;
        state.suspend_actions();
        state.pressed_at = Some(std::time::Instant::now() - crate::hotkey::AUTO_MODE_LONG_PRESS);
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler,
        ));
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        assert!(events.lock().unwrap().is_empty());
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            false,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert!(events.lock().unwrap().is_empty());
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert_eq!(events.lock().unwrap().len(), 2);
        assert_eq!(events.lock().unwrap()[0].state, ShortcutState::Pressed);
        assert_eq!(events.lock().unwrap()[1].state, ShortcutState::Released);
    }

    #[test]
    fn interrupted_observation_clears_pending_press_without_emitting_dictation() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event);
        });
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let generation = state.edge_generation;
        state.invalidate_observation();
        assert!(events.lock().unwrap().is_empty());
        assert!(!state.base_pressed);
        assert!(!state.pending_base_press);
        assert_ne!(state.edge_generation, generation);
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler,
        ));
    }

    #[test]
    fn an_uninitialized_chord_snapshot_is_not_evidence_of_release() {
        assert!(chord_observation(false, false, false).is_err());
        assert_eq!(chord_observation(true, false, false), Ok(false));
        assert_eq!(chord_observation(true, false, true), Ok(true));
        assert_eq!(chord_observation(false, true, false), Ok(true));
    }

    #[test]
    fn release_snapshot_remains_pressed_until_the_combo_key_is_released() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::TranslateSelection,
                index: 0,
                trigger: NativeHotkeyTrigger::FnLeftShift,
            }),
        ];
        let handler: NativeHotkeyHandler = Arc::new(|_| {});
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        );
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert!(state.any_pressed());
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::LeftShift,
            true,
            &handler,
        );
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            false,
            &handler,
        );
        assert!(state.any_pressed());
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::LeftShift,
            false,
            &handler,
        );
        assert!(!state.any_pressed());
    }

    #[test]
    fn delayed_guide_timer_cannot_turn_a_long_hold_release_into_dictation() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event);
        });
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        state.pressed_at = Some(std::time::Instant::now() - crate::hotkey::AUTO_MODE_LONG_PRESS);
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        let events = events.lock().unwrap();
        assert_eq!(events.len(), 2);
        let clock = events[0].occurred_at;
        let mut session = crate::input_session::InputSession::default();
        assert!(session
            .press(
                crate::input_session::InputBinding {
                    role: crate::input_session::VoiceRole::Dictation,
                    binding_id: "Fn".into(),
                    mode: InputMode::Auto,
                    revision: 1,
                },
                0,
            )
            .is_empty());
        assert!(session
            .release(
                "Fn",
                events[1].occurred_at.duration_since(clock).as_millis() as u64,
            )
            .is_empty());
        assert_eq!(session.session_id(), None);
    }

    #[test]
    fn stale_combo_timer_cannot_activate_a_released_or_replaced_press() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let handler: NativeHotkeyHandler = Arc::new(|_| {});
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let generation = state.edge_generation;
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler
        ));
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler
        ));
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        );
        let current = state.edge_generation;
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            current,
            &handler
        ));
    }

    #[test]
    fn pending_fn_long_hold_is_interpreted_only_by_the_session_reducer() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event);
        });
        let mut state = NativeComboState::default();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let generation = state.edge_generation;
        state.pressed_at = Some(std::time::Instant::now() - crate::hotkey::AUTO_MODE_LONG_PRESS);
        assert!(activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler
        ));
        assert!(state.base_pressed);
        assert_eq!(events.lock().unwrap().len(), 1);
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler
        ));
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        let observed = events.lock().unwrap();
        assert_eq!(observed.len(), 2);
        assert_eq!(observed[0].state, ShortcutState::Pressed);
        assert_eq!(observed[1].state, ShortcutState::Released);
        use crate::input_session::{InputAction, InputBinding, InputSession, VoiceRole};
        let mut session = InputSession::default();
        session.press(
            InputBinding {
                role: VoiceRole::Dictation,
                binding_id: "Fn".into(),
                mode: InputMode::Auto,
                revision: 1,
            },
            0,
        );
        let press_id = session.press_id().unwrap();
        assert_eq!(
            session.long_press_due(press_id, 600),
            vec![InputAction::ShowGuide { press_id }]
        );
        assert_eq!(
            session.release("Fn", 700),
            vec![InputAction::HideGuide { press_id }]
        );
        assert_eq!(session.session_id(), None);
        drop(observed);
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler
        ));
    }

    #[test]
    fn shutdown_is_idempotent_and_rejects_installation_without_starting_a_monitor() {
        let runtime = NativeHotkeyRuntime::default();
        runtime.shutdown();
        runtime.shutdown();
        assert!(runtime.dispatch_paused.is_paused());
        assert_eq!(
            runtime.install(Vec::new(), InputMode::Toggle, Arc::new(|_| {})),
            Err("Native shortcut runtime is stopped".into())
        );
        assert!(runtime.observed_native_key_pressed().is_none());
    }

    #[test]
    fn capture_pause_blocks_actions_but_not_the_observation_lifecycle() {
        let paused = crate::native_dispatch_gate::NativeDispatchGate::default();
        paused.pause();
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event.state);
        });
        let event = NativeHotkeyEvent {
            parent_trigger: None,
            occurred_at: std::time::Instant::now(),
            role: crate::hotkey::HotkeyRole::Dictation,
            index: 0,
            state: ShortcutState::Pressed,
        };
        dispatch_when_enabled(&paused, event, &handler);
        assert!(events.lock().unwrap().is_empty());
        let installation = paused.begin_install();
        assert!(paused.finish_install(installation));
        dispatch_when_enabled(&paused, event, &handler);
        assert_eq!(*events.lock().unwrap(), [ShortcutState::Pressed]);
    }

    #[test]
    fn held_state_dedupes_repeat_edges() {
        let state = NativeHeldState::default();

        assert_eq!(state.edge(true), Some(ShortcutState::Pressed));
        assert_eq!(state.edge(true), None);
        assert_eq!(state.edge(false), Some(ShortcutState::Released));
        assert_eq!(state.edge(false), None);
        assert_eq!(state.edge(true), Some(ShortcutState::Pressed));
    }

    #[test]
    fn fn_alphanumeric_combo_matches_physical_keys_in_both_orders() {
        for (keycode, key) in [(40, 'K'), (0, 'A'), (29, '0')] {
            assert_eq!(mac_combo_key(keycode), Some(NativeComboKey::Key(key)));
            for combo_first in [true, false] {
                let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
                    role: crate::hotkey::HotkeyRole::Ask,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnKey(key),
                })];
                let events = Arc::new(Mutex::new(Vec::new()));
                let captured = Arc::clone(&events);
                let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
                    captured.lock().unwrap().push(event.state);
                });
                let mut state = NativeComboState::default();
                if combo_first {
                    dispatch_native_combo_edge(
                        &mut state,
                        &bindings,
                        NativeHotkeyTrigger::Fn,
                        NativeComboKey::Key(key),
                        true,
                        &handler,
                    );
                }
                dispatch_native_base_edge(
                    &mut state,
                    &bindings,
                    NativeHotkeyTrigger::Fn,
                    true,
                    &handler,
                );
                if !combo_first {
                    dispatch_native_combo_edge(
                        &mut state,
                        &bindings,
                        NativeHotkeyTrigger::Fn,
                        NativeComboKey::Key(key),
                        true,
                        &handler,
                    );
                }
                dispatch_native_base_edge(
                    &mut state,
                    &bindings,
                    NativeHotkeyTrigger::Fn,
                    false,
                    &handler,
                );
                assert_eq!(
                    *events.lock().unwrap(),
                    [ShortcutState::Pressed, ShortcutState::Released]
                );
            }
        }
        assert_eq!(mac_combo_key(63), None);
    }

    #[test]
    fn runtime_install_accepts_shared_handler_arc() {
        let runtime = NativeHotkeyRuntime::default();
        let handler: Arc<dyn Fn(NativeHotkeyEvent) + Send + Sync> = Arc::new(|_| {});

        assert!(runtime
            .install(Vec::new(), InputMode::Toggle, handler)
            .is_ok());
    }

    #[test]
    fn combo_state_clears_released_keys_and_supports_repeated_base_presses() {
        let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
            role: crate::hotkey::HotkeyRole::Ask,
            index: 0,
            trigger: NativeHotkeyTrigger::FnSpace,
        })];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push(event.state);
        });
        let mut state = NativeComboState::default();
        for pressed in [true, false] {
            dispatch_native_combo_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                NativeComboKey::Space,
                pressed,
                &handler,
            );
        }
        assert!(state.held_combos.is_empty());
        for pressed in [true, false] {
            dispatch_native_base_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                pressed,
                &handler,
            );
        }
        assert!(events.lock().unwrap().is_empty());
        dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        );
        for _attempt in 0..2 {
            for pressed in [true, false] {
                dispatch_native_base_edge(
                    &mut state,
                    &bindings,
                    NativeHotkeyTrigger::Fn,
                    pressed,
                    &handler,
                );
            }
        }
        assert_eq!(
            *events.lock().unwrap(),
            [
                ShortcutState::Pressed,
                ShortcutState::Released,
                ShortcutState::Pressed,
                ShortcutState::Released,
            ]
        );
    }

    #[test]
    fn a_combo_pressed_before_fn_still_triggers_and_releases_once() {
        for (combo, trigger) in [
            (NativeComboKey::Space, NativeHotkeyTrigger::FnSpace),
            (NativeComboKey::LeftShift, NativeHotkeyTrigger::FnLeftShift),
        ] {
            let bindings = vec![NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger,
            })];
            let events = Arc::new(Mutex::new(Vec::new()));
            let captured = Arc::clone(&events);
            let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
                captured.lock().unwrap().push(event.state);
            });
            let mut state = NativeComboState::default();
            dispatch_native_combo_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                combo,
                true,
                &handler,
            );
            dispatch_native_base_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                true,
                &handler,
            );
            dispatch_native_base_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                false,
                &handler,
            );
            dispatch_native_combo_edge(
                &mut state,
                &bindings,
                NativeHotkeyTrigger::Fn,
                combo,
                false,
                &handler,
            );
            assert_eq!(
                *events.lock().unwrap(),
                [ShortcutState::Pressed, ShortcutState::Released]
            );
        }
    }

    #[test]
    fn combo_state_suppresses_bare_base_when_combo_is_used() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push((event.role, event.state));
        });
        let mut state = NativeComboState::default();

        assert!(dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        ));
        assert!(events.lock().unwrap().is_empty());

        assert!(dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        ));
        assert!(dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            false,
            &handler,
        ));
        assert!(dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        ));

        assert_eq!(
            *events.lock().unwrap(),
            vec![
                (crate::hotkey::HotkeyRole::Ask, ShortcutState::Pressed),
                (crate::hotkey::HotkeyRole::Ask, ShortcutState::Released),
            ]
        );
    }

    #[test]
    fn combo_state_dispatches_bare_base_when_no_combo_is_used() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push((event.role, event.state));
        });
        let mut state = NativeComboState::default();

        assert!(dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        ));
        assert!(events.lock().unwrap().is_empty());

        assert!(dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        ));

        assert_eq!(
            *events.lock().unwrap(),
            vec![
                (crate::hotkey::HotkeyRole::Dictation, ShortcutState::Pressed),
                (
                    crate::hotkey::HotkeyRole::Dictation,
                    ShortcutState::Released
                ),
            ]
        );
    }

    #[test]
    fn hold_fn_starts_while_held_and_stops_on_release() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push((event.role, event.state));
        });
        let mut state = NativeComboState {
            mode: InputMode::Hold,
            ..NativeComboState::default()
        };

        assert!(dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        ));
        assert!(events.lock().unwrap().is_empty());

        let generation = state.edge_generation;
        assert!(activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler,
        ));
        assert_eq!(
            *events.lock().unwrap(),
            vec![(crate::hotkey::HotkeyRole::Dictation, ShortcutState::Pressed)]
        );

        assert!(dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        ));
        assert_eq!(
            *events.lock().unwrap(),
            vec![
                (crate::hotkey::HotkeyRole::Dictation, ShortcutState::Pressed),
                (
                    crate::hotkey::HotkeyRole::Dictation,
                    ShortcutState::Released
                ),
            ]
        );
    }

    #[test]
    fn fn_combo_cancels_pending_base_without_replaying_a_previous_short_tap() {
        let bindings = vec![
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Dictation,
                index: 0,
                trigger: NativeHotkeyTrigger::Fn,
            }),
            NativeMonitoredBinding::new(NativeHotkeyBinding {
                role: crate::hotkey::HotkeyRole::Ask,
                index: 0,
                trigger: NativeHotkeyTrigger::FnSpace,
            }),
        ];
        let events = Arc::new(Mutex::new(Vec::new()));
        let captured = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event: NativeHotkeyEvent| {
            captured.lock().unwrap().push((event.role, event.state));
        });
        let mut state = NativeComboState {
            mode: InputMode::Hold,
            ..NativeComboState::default()
        };

        let _ = dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let stale_generation = state.edge_generation;
        let _ = dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            stale_generation,
            &handler,
        ));
        assert_eq!(
            *events.lock().unwrap(),
            vec![
                (crate::hotkey::HotkeyRole::Dictation, ShortcutState::Pressed),
                (
                    crate::hotkey::HotkeyRole::Dictation,
                    ShortcutState::Released
                ),
            ]
        );
        events.lock().unwrap().clear();

        let _ = dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            true,
            &handler,
        );
        let generation = state.edge_generation;
        let _ = dispatch_native_combo_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            true,
            &handler,
        );
        assert!(!activate_pending_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler,
        ));
        let _ = dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            false,
            &handler,
        );
        assert_eq!(
            *events.lock().unwrap(),
            vec![
                (crate::hotkey::HotkeyRole::Ask, ShortcutState::Pressed),
                (crate::hotkey::HotkeyRole::Ask, ShortcutState::Released),
            ]
        );
    }
}
