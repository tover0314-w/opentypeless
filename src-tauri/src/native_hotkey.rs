#[cfg(any(target_os = "macos", target_os = "windows", test))]
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};
use tauri_plugin_global_shortcut::ShortcutState;

#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
pub enum NativeHotkeyTrigger {
    Fn,
    FnSpace,
    FnLeftShift,
    LeftAlt,
    RightAlt,
    RightAltSpace,
    RightAltLeftShift,
}

impl NativeHotkeyTrigger {
    pub fn canonical(self) -> &'static str {
        match self {
            Self::Fn => "Fn",
            Self::FnSpace => "Fn+Space",
            Self::FnLeftShift => "Fn+LeftShift",
            Self::LeftAlt => "LeftAlt",
            Self::RightAlt => "RightAlt",
            Self::RightAltSpace => "RightAlt+Space",
            Self::RightAltLeftShift => "RightAlt+LeftShift",
        }
    }

    #[cfg(any(target_os = "macos", target_os = "windows", test))]
    fn base(self) -> NativeHotkeyTrigger {
        match self {
            Self::Fn | Self::FnSpace | Self::FnLeftShift => Self::Fn,
            Self::LeftAlt => Self::LeftAlt,
            Self::RightAlt | Self::RightAltSpace | Self::RightAltLeftShift => Self::RightAlt,
        }
    }

    #[cfg(any(target_os = "macos", target_os = "windows", test))]
    fn combo_key(self) -> Option<NativeComboKey> {
        match self {
            Self::FnSpace | Self::RightAltSpace => Some(NativeComboKey::Space),
            Self::FnLeftShift | Self::RightAltLeftShift => Some(NativeComboKey::LeftShift),
            Self::Fn | Self::LeftAlt | Self::RightAlt => None,
        }
    }

    #[cfg(any(target_os = "macos", target_os = "windows", test))]
    fn from_base_combo(base: NativeHotkeyTrigger, combo: NativeComboKey) -> Option<Self> {
        match (base, combo) {
            (Self::Fn, NativeComboKey::Space) => Some(Self::FnSpace),
            (Self::Fn, NativeComboKey::LeftShift) => Some(Self::FnLeftShift),
            (Self::RightAlt, NativeComboKey::Space) => Some(Self::RightAltSpace),
            (Self::RightAlt, NativeComboKey::LeftShift) => Some(Self::RightAltLeftShift),
            _ => None,
        }
    }
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
#[derive(Debug, Clone, Copy, PartialEq, Eq, Hash)]
enum NativeComboKey {
    Space,
    LeftShift,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NativeHotkeyBinding {
    pub role: crate::hotkey::HotkeyRole,
    pub index: usize,
    pub trigger: NativeHotkeyTrigger,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct NativeHotkeyEvent {
    pub role: crate::hotkey::HotkeyRole,
    pub index: usize,
    pub state: ShortcutState,
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
}

#[derive(Default)]
struct NativeHotkeyRuntimeInner {
    monitor: Option<platform::PlatformNativeMonitor>,
}

impl NativeHotkeyRuntime {
    pub fn install(
        &self,
        bindings: Vec<NativeHotkeyBinding>,
        handler: Arc<dyn Fn(NativeHotkeyEvent) + Send + Sync>,
    ) -> Result<(), String> {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let _ = inner.monitor.take();

        if bindings.is_empty() {
            return Ok(());
        }

        inner.monitor = Some(platform::PlatformNativeMonitor::start(bindings, handler)?);
        Ok(())
    }

    pub fn pause(&self) {
        let mut inner = self.inner.lock().unwrap_or_else(|e| e.into_inner());
        let _ = inner.monitor.take();
    }
}

type NativeHotkeyHandler = Arc<dyn Fn(NativeHotkeyEvent) + Send + Sync + 'static>;

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

#[cfg(target_os = "macos")]
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
    let mut matched = false;
    for binding in bindings {
        if binding.binding.trigger != trigger {
            continue;
        }
        matched = true;
        if let Some(state) = binding.held.edge(pressed) {
            (handler.as_ref())(NativeHotkeyEvent {
                role: binding.binding.role,
                index: binding.binding.index,
                state,
            });
        }
    }
    matched
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
#[derive(Default)]
struct NativeComboState {
    base_pressed: bool,
    pending_base_press: bool,
    combo_used: bool,
}

#[cfg(any(target_os = "macos", target_os = "windows", test))]
fn dispatch_native_base_edge(
    state: &mut NativeComboState,
    bindings: &[NativeMonitoredBinding],
    base: NativeHotkeyTrigger,
    pressed: bool,
    handler: &NativeHotkeyHandler,
) -> bool {
    if pressed {
        state.base_pressed = true;
        state.combo_used = false;
        state.pending_base_press = has_combo_bindings(bindings, base);
        if state.pending_base_press {
            return true;
        }
        return dispatch_matching_bindings(bindings, base, true, handler);
    }

    if state.pending_base_press && !state.combo_used {
        let matched = dispatch_matching_bindings(bindings, base, true, handler);
        let _ = dispatch_matching_bindings(bindings, base, false, handler);
        state.base_pressed = false;
        state.pending_base_press = false;
        return matched;
    }

    if state.combo_used {
        for combo in [NativeComboKey::Space, NativeComboKey::LeftShift] {
            if let Some(trigger) = NativeHotkeyTrigger::from_base_combo(base, combo) {
                let _ = dispatch_matching_bindings(bindings, trigger, false, handler);
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
    if !state.base_pressed {
        return false;
    }
    let Some(trigger) = NativeHotkeyTrigger::from_base_combo(base, combo) else {
        return false;
    };
    if !has_binding_for_trigger(bindings, trigger) {
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
        dispatch_native_base_edge, dispatch_native_combo_edge, NativeComboKey, NativeComboState,
        NativeHotkeyHandler, NativeHotkeyTrigger, NativeMonitoredBinding,
    };
    use std::ffi::c_void;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{mpsc, Arc, Mutex};
    use std::thread;
    use std::time::Duration;

    const STARTUP_TIMEOUT: Duration = Duration::from_secs(3);

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
    const FLAG_MASK_SHIFT: CgEventFlags = 0x0002_0000;
    const FN_KEYCODE: i64 = 63;
    const SPACE_KEYCODE: i64 = 49;
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
        fn CGEventGetIntegerValueField(event: CgEventRef, field: CgEventField) -> i64;
        fn CGEventGetFlags(event: CgEventRef) -> CgEventFlags;
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
        tap: Mutex<Option<CfMachPortRef>>,
        runloop: Mutex<Option<CfRunLoopRef>>,
        cancelled: AtomicBool,
    }

    unsafe impl Send for MacShutdownHandles {}
    unsafe impl Sync for MacShutdownHandles {}

    impl MacShutdownHandles {
        fn new() -> Self {
            Self {
                tap: Mutex::new(None),
                runloop: Mutex::new(None),
                cancelled: AtomicBool::new(false),
            }
        }

        fn shutdown(&self) {
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
        pub fn start(
            bindings: Vec<super::NativeHotkeyBinding>,
            handler: NativeHotkeyHandler,
        ) -> Result<Self, String> {
            let bindings = monitored_bindings_for_base(bindings, NativeHotkeyTrigger::Fn);
            if bindings.is_empty() {
                return Err("macOS native hotkeys currently support Fn only".to_string());
            }

            let handles = Arc::new(MacShutdownHandles::new());
            let thread_handles = Arc::clone(&handles);
            let (status_tx, status_rx) = mpsc::channel();
            thread::Builder::new()
                .name("opentypeless-native-hotkey-mac".to_string())
                .spawn(move || run_event_tap_loop(bindings, handler, thread_handles, status_tx))
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
        bindings: Vec<NativeMonitoredBinding>,
        handler: NativeHotkeyHandler,
        handles: Arc<MacShutdownHandles>,
        state: Mutex<NativeComboState>,
    }

    fn run_event_tap_loop(
        bindings: Vec<NativeMonitoredBinding>,
        handler: NativeHotkeyHandler,
        handles: Arc<MacShutdownHandles>,
        status_tx: mpsc::Sender<Result<(), String>>,
    ) {
        let context = Box::into_raw(Box::new(CallbackContext {
            bindings,
            handler,
            handles: Arc::clone(&handles),
            state: Mutex::new(NativeComboState::default()),
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

            if status_tx.send(Ok(())).is_err() || handles.cancelled.load(Ordering::SeqCst) {
                handles.shutdown();
            }

            if !handles.cancelled.load(Ordering::SeqCst) {
                CFRunLoopRun();
            }

            if let Some(tap) = handles.tap.lock().unwrap_or_else(|e| e.into_inner()).take() {
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
        let context = unsafe { &*(user_info as *const CallbackContext) };

        match event_type {
            TAP_DISABLED_BY_TIMEOUT | TAP_DISABLED_BY_USER_INPUT => {
                if let Some(tap) = context
                    .handles
                    .tap
                    .lock()
                    .unwrap_or_else(|e| e.into_inner())
                    .as_ref()
                {
                    unsafe { CGEventTapEnable(*tap, true) };
                }
            }
            FLAGS_CHANGED => handle_flags_changed(context, event),
            KEY_DOWN => handle_key_event(context, event, true),
            KEY_UP => handle_key_event(context, event, false),
            _ => {}
        }

        event
    }

    fn handle_flags_changed(context: &CallbackContext, event: CgEventRef) {
        let keycode = unsafe { CGEventGetIntegerValueField(event, KEYBOARD_EVENT_KEYCODE) };
        let flags = unsafe { CGEventGetFlags(event) };
        if keycode == FN_KEYCODE {
            let pressed = (flags & FLAG_MASK_SECONDARY_FN) != 0;
            let mut state = context.state.lock().unwrap_or_else(|e| e.into_inner());
            let _ = dispatch_native_base_edge(
                &mut state,
                &context.bindings,
                NativeHotkeyTrigger::Fn,
                pressed,
                &context.handler,
            );
            return;
        }

        if keycode == LEFT_SHIFT_KEYCODE {
            let pressed = (flags & FLAG_MASK_SHIFT) != 0;
            let mut state = context.state.lock().unwrap_or_else(|e| e.into_inner());
            let _ = dispatch_native_combo_edge(
                &mut state,
                &context.bindings,
                NativeHotkeyTrigger::Fn,
                NativeComboKey::LeftShift,
                pressed,
                &context.handler,
            );
        }
    }

    fn handle_key_event(context: &CallbackContext, event: CgEventRef, pressed: bool) {
        let keycode = unsafe { CGEventGetIntegerValueField(event, KEYBOARD_EVENT_KEYCODE) };
        if keycode != SPACE_KEYCODE {
            return;
        }
        let mut state = context.state.lock().unwrap_or_else(|e| e.into_inner());
        let _ = dispatch_native_combo_edge(
            &mut state,
            &context.bindings,
            NativeHotkeyTrigger::Fn,
            NativeComboKey::Space,
            pressed,
            &context.handler,
        );
    }
}

#[cfg(target_os = "windows")]
mod platform {
    use super::{
        dispatch_native_base_edge, dispatch_native_combo_edge, NativeComboKey, NativeComboState,
        NativeHotkeyHandler, NativeHotkeyTrigger, NativeMonitoredBinding,
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
        CallNextHookEx, DispatchMessageW, GetMessageW, PeekMessageW, PostThreadMessageW,
        SetWindowsHookExW, TranslateMessage, UnhookWindowsHookEx, HC_ACTION, HHOOK,
        KBDLLHOOKSTRUCT, LLKHF_INJECTED, MSG, PM_NOREMOVE, WH_KEYBOARD_LL, WM_QUIT,
    };

    const STARTUP_TIMEOUT: Duration = Duration::from_secs(3);
    const ACCEPT_SYNTHETIC_EVENTS_ENV: &str = "OPENTYPELESS_ACCEPT_SYNTHETIC_HOTKEY_EVENTS";

    const WM_KEYDOWN: usize = 0x0100;
    const WM_KEYUP: usize = 0x0101;
    const WM_SYSKEYDOWN: usize = 0x0104;
    const WM_SYSKEYUP: usize = 0x0105;
    const VK_SPACE: u32 = 0x20;
    const VK_LSHIFT: u32 = 0xA0;
    const VK_LMENU: u32 = 0xA4;
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
            handler: NativeHotkeyHandler,
        ) -> Result<Self, String> {
            let bindings: Vec<_> = bindings
                .into_iter()
                .filter(|binding| {
                    matches!(
                        binding.trigger,
                        NativeHotkeyTrigger::LeftAlt
                            | NativeHotkeyTrigger::RightAlt
                            | NativeHotkeyTrigger::RightAltSpace
                            | NativeHotkeyTrigger::RightAltLeftShift
                    )
                })
                .map(NativeMonitoredBinding::new)
                .collect();
            if bindings.is_empty() {
                return Err("Windows native hotkeys require a supported Alt binding".to_string());
            }

            let (status_tx, status_rx) = mpsc::channel();
            let startup = Arc::new(WindowsStartupState::new());
            let thread_startup = Arc::clone(&startup);
            let thread = thread::Builder::new()
                .name("opentypeless-native-hotkey-win".to_string())
                .spawn(move || run_keyboard_hook_loop(bindings, handler, thread_startup, status_tx))
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
        right_alt_state: std::sync::Mutex<NativeComboState>,
        left_alt_state: std::sync::Mutex<NativeComboState>,
    }

    unsafe impl Send for CallbackContext {}
    unsafe impl Sync for CallbackContext {}

    fn run_keyboard_hook_loop(
        bindings: Vec<NativeMonitoredBinding>,
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
                right_alt_state: std::sync::Mutex::new(NativeComboState::default()),
                left_alt_state: std::sync::Mutex::new(NativeComboState::default()),
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

            if status_tx.send(Ok(thread_id)).is_err() || startup.is_cancelled() {
                cleanup_context(context);
                return;
            }

            loop {
                let result = GetMessageW(&mut message, ptr::null_mut(), 0, 0);
                if result <= 0 {
                    break;
                }
                let _ = TranslateMessage(&message);
                let _ = DispatchMessageW(&message);
            }

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

        match vk_code {
            VK_LMENU => dispatch_native_base_edge(
                &mut context
                    .left_alt_state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()),
                &context.bindings,
                NativeHotkeyTrigger::LeftAlt,
                pressed,
                &context.handler,
            ),
            VK_RMENU => dispatch_native_base_edge(
                &mut context
                    .right_alt_state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()),
                &context.bindings,
                NativeHotkeyTrigger::RightAlt,
                pressed,
                &context.handler,
            ),
            VK_SPACE => dispatch_native_combo_edge(
                &mut context
                    .right_alt_state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()),
                &context.bindings,
                NativeHotkeyTrigger::RightAlt,
                NativeComboKey::Space,
                pressed,
                &context.handler,
            ),
            VK_LSHIFT => dispatch_native_combo_edge(
                &mut context
                    .right_alt_state
                    .lock()
                    .unwrap_or_else(|e| e.into_inner()),
                &context.bindings,
                NativeHotkeyTrigger::RightAlt,
                NativeComboKey::LeftShift,
                pressed,
                &context.handler,
            ),
            _ => false,
        }
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
}

#[cfg(all(not(target_os = "macos"), not(target_os = "windows")))]
mod platform {
    use super::{NativeHotkeyBinding, NativeHotkeyHandler};

    pub struct PlatformNativeMonitor;

    impl PlatformNativeMonitor {
        pub fn start(
            _bindings: Vec<NativeHotkeyBinding>,
            _handler: NativeHotkeyHandler,
        ) -> Result<Self, String> {
            Err("Native hotkey runtime is unsupported on this platform".to_string())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

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
    fn runtime_install_accepts_shared_handler_arc() {
        let runtime = NativeHotkeyRuntime::default();
        let handler: Arc<dyn Fn(NativeHotkeyEvent) + Send + Sync> = Arc::new(|_| {});

        assert!(runtime.install(Vec::new(), handler).is_ok());
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
        let handler: NativeHotkeyHandler = Arc::new(move |event| {
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
        let handler: NativeHotkeyHandler = Arc::new(move |event| {
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
}
