use crate::pipeline;
use crate::storage;
use crate::HotkeyModeCache;
use std::sync::Mutex;
use tauri::Emitter;
use tauri::Manager;
use tauri_plugin_global_shortcut::GlobalShortcutExt;
use tauri_plugin_global_shortcut::{Code, Modifiers, Shortcut, ShortcutState};

#[derive(Default)]
pub struct HotkeyRegistry {
    ctrl_meta_hook: Mutex<Option<CtrlMetaHookGuard>>,
}

#[cfg(target_os = "windows")]
enum HookEvent {
    Pressed,
    Released,
}

struct CtrlMetaHookGuard {
    #[cfg(target_os = "windows")]
    hook: windows_sys::Win32::UI::WindowsAndMessaging::HHOOK,
}

#[cfg(target_os = "windows")]
unsafe impl Send for CtrlMetaHookGuard {}
#[cfg(target_os = "windows")]
unsafe impl Sync for CtrlMetaHookGuard {}

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

pub fn build_shortcut_handler(
    app_handle: tauri::AppHandle,
) -> impl Fn(&tauri::AppHandle, &Shortcut, tauri_plugin_global_shortcut::ShortcutEvent)
       + Send
       + Sync
       + 'static {
    move |_app, _shortcut, event| {
        handle_shortcut_state(app_handle.clone(), event.state);
    }
}

fn handle_shortcut_state(handle: tauri::AppHandle, state: ShortcutState) {
    match state {
        ShortcutState::Pressed => {
            let hotkey_mode = handle
                .state::<HotkeyModeCache>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            tauri::async_runtime::spawn(async move {
                let pipeline = handle.state::<pipeline::PipelineHandle>();

                if hotkey_mode == "toggle" {
                    if pipeline.current_state() == pipeline::PipelineState::Idle {
                        if let Err(e) = pipeline.start().await {
                            tracing::error!("Failed to start recording: {}", e);
                            let _ = handle.emit("pipeline:error", e.to_string());
                        }
                    } else if let Err(e) = pipeline.stop().await {
                        tracing::error!("Failed to stop recording: {}", e);
                        let _ = handle.emit("pipeline:error", e.to_string());
                    }
                } else if let Err(e) = pipeline.start().await {
                    tracing::error!("Failed to start recording: {}", e);
                    let _ = handle.emit("pipeline:error", e.to_string());
                }
            });
        }
        ShortcutState::Released => {
            let hotkey_mode = handle
                .state::<HotkeyModeCache>()
                .0
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .clone();
            if hotkey_mode != "toggle" {
                tauri::async_runtime::spawn(async move {
                    let pipeline = handle.state::<pipeline::PipelineHandle>();
                    if let Err(e) = pipeline.stop().await {
                        tracing::error!("Failed to stop recording: {}", e);
                        let _ = handle.emit("pipeline:error", e.to_string());
                    }
                });
            }
        }
    }
}

pub fn register_hotkey(app: &tauri::AppHandle, hotkey: &str) -> Result<(), String> {
    let _ = app.global_shortcut().unregister_all();
    clear_ctrl_meta_hook(app);
    set_ctrl_meta_hook_paused(false);

    if is_ctrl_meta_hotkey(hotkey) {
        return register_ctrl_meta_hotkey(app, hotkey);
    }

    let shortcut = parse_hotkey(hotkey).ok_or_else(|| format!("Invalid hotkey: {}", hotkey))?;
    app.global_shortcut()
        .register(shortcut)
        .map_err(|e| e.to_string())
}

pub fn pause_registered_hotkey(app: &tauri::AppHandle) -> Result<(), String> {
    set_ctrl_meta_hook_paused(true);
    app.global_shortcut()
        .unregister_all()
        .map_err(|e| e.to_string())
}

fn clear_ctrl_meta_hook(app: &tauri::AppHandle) {
    let registry = app.state::<HotkeyRegistry>();
    *registry
        .ctrl_meta_hook
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = None;
}

fn register_ctrl_meta_hotkey(app: &tauri::AppHandle, _hotkey: &str) -> Result<(), String> {
    #[cfg(not(target_os = "windows"))]
    let _ = app;

    #[cfg(target_os = "windows")]
    {
        let (guard, mut rx) = install_ctrl_meta_hook()?;
        let handle = app.clone();
        tauri::async_runtime::spawn(async move {
            while let Some(event) = rx.recv().await {
                match event {
                    HookEvent::Pressed => {
                        handle_shortcut_state(handle.clone(), ShortcutState::Pressed)
                    }
                    HookEvent::Released => {
                        handle_shortcut_state(handle.clone(), ShortcutState::Released)
                    }
                }
            }
        });

        let registry = app.state::<HotkeyRegistry>();
        *registry
            .ctrl_meta_hook
            .lock()
            .unwrap_or_else(|e| e.into_inner()) = Some(guard);
        Ok(())
    }
    #[cfg(not(target_os = "windows"))]
    {
        Err(format!(
            "{} is only supported as a pure modifier hotkey on Windows",
            _hotkey
        ))
    }
}

fn is_ctrl_meta_hotkey(s: &str) -> bool {
    let parts: Vec<&str> = s
        .split('+')
        .map(|p| p.trim())
        .filter(|p| !p.is_empty())
        .collect();
    if parts.len() != 2 {
        return false;
    }

    let mut has_ctrl = false;
    let mut has_meta = false;
    for part in parts {
        match part.to_lowercase().as_str() {
            "ctrl" | "control" => has_ctrl = true,
            "meta" | "super" | "win" | "cmd" => has_meta = true,
            _ => return false,
        }
    }

    has_ctrl && has_meta
}

#[cfg(target_os = "windows")]
fn install_ctrl_meta_hook() -> Result<
    (
        CtrlMetaHookGuard,
        tokio::sync::mpsc::UnboundedReceiver<HookEvent>,
    ),
    String,
> {
    windows_hook::install()
}

fn set_ctrl_meta_hook_paused(paused: bool) {
    #[cfg(target_os = "windows")]
    windows_hook::set_paused(paused);
    #[cfg(not(target_os = "windows"))]
    let _ = paused;
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
            "alt" => modifiers |= Modifiers::ALT,
            "ctrl" | "control" => modifiers |= Modifiers::CONTROL,
            "shift" => modifiers |= Modifiers::SHIFT,
            "meta" | "super" | "win" | "cmd" => modifiers |= Modifiers::META,
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
        "." | "period" => Code::Period,
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

#[cfg(target_os = "windows")]
mod windows_hook {
    use super::{CtrlMetaHookGuard, HookEvent};
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::Mutex;
    use tokio::sync::mpsc;
    use windows_sys::Win32::Foundation::{HINSTANCE, LPARAM, LRESULT, WPARAM};
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        VK_CONTROL, VK_LCONTROL, VK_LWIN, VK_RCONTROL, VK_RWIN,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CallNextHookEx, SetWindowsHookExW, UnhookWindowsHookEx, HC_ACTION, KBDLLHOOKSTRUCT,
        WH_KEYBOARD_LL, WM_KEYDOWN, WM_KEYUP, WM_SYSKEYDOWN, WM_SYSKEYUP,
    };

    static EVENT_TX: Mutex<Option<mpsc::UnboundedSender<HookEvent>>> = Mutex::new(None);
    static PAUSED: AtomicBool = AtomicBool::new(false);
    static CTRL_DOWN: AtomicBool = AtomicBool::new(false);
    static META_DOWN: AtomicBool = AtomicBool::new(false);
    static COMBO_ACTIVE: AtomicBool = AtomicBool::new(false);

    pub(super) fn install(
    ) -> Result<(CtrlMetaHookGuard, mpsc::UnboundedReceiver<HookEvent>), String> {
        let (tx, rx) = mpsc::unbounded_channel();
        *EVENT_TX.lock().unwrap_or_else(|e| e.into_inner()) = Some(tx);

        let hook = unsafe {
            SetWindowsHookExW(
                WH_KEYBOARD_LL,
                Some(keyboard_proc),
                GetModuleHandleW(std::ptr::null()) as HINSTANCE,
                0,
            )
        };

        if hook.is_null() {
            *EVENT_TX.lock().unwrap_or_else(|e| e.into_inner()) = None;
            return Err("Failed to install Ctrl+Win keyboard hook".to_string());
        }

        CTRL_DOWN.store(false, Ordering::SeqCst);
        META_DOWN.store(false, Ordering::SeqCst);
        COMBO_ACTIVE.store(false, Ordering::SeqCst);
        PAUSED.store(false, Ordering::SeqCst);

        Ok((CtrlMetaHookGuard { hook }, rx))
    }

    pub(super) fn set_paused(paused: bool) {
        PAUSED.store(paused, Ordering::SeqCst);
        if paused {
            CTRL_DOWN.store(false, Ordering::SeqCst);
            META_DOWN.store(false, Ordering::SeqCst);
            COMBO_ACTIVE.store(false, Ordering::SeqCst);
        }
    }

    unsafe extern "system" fn keyboard_proc(code: i32, wparam: WPARAM, lparam: LPARAM) -> LRESULT {
        if code as u32 == HC_ACTION && !PAUSED.load(Ordering::SeqCst) {
            let kb = unsafe { &*(lparam as *const KBDLLHOOKSTRUCT) };
            let event = wparam as u32;
            let is_down = event == WM_KEYDOWN || event == WM_SYSKEYDOWN;
            let is_up = event == WM_KEYUP || event == WM_SYSKEYUP;

            if is_down || is_up {
                update_key_state(kb.vkCode, is_down);
                update_combo_state();
            }
        }

        unsafe { CallNextHookEx(std::ptr::null_mut(), code, wparam, lparam) }
    }

    fn update_key_state(vk: u32, is_down: bool) {
        let ctrl = VK_CONTROL as u32;
        let lctrl = VK_LCONTROL as u32;
        let rctrl = VK_RCONTROL as u32;
        if vk == ctrl || vk == lctrl || vk == rctrl {
            CTRL_DOWN.store(is_down, Ordering::SeqCst);
        }

        let lmeta = VK_LWIN as u32;
        let rmeta = VK_RWIN as u32;
        if vk == lmeta || vk == rmeta {
            META_DOWN.store(is_down, Ordering::SeqCst);
        }
    }

    fn update_combo_state() {
        let both = CTRL_DOWN.load(Ordering::SeqCst) && META_DOWN.load(Ordering::SeqCst);
        let was_active = COMBO_ACTIVE.swap(both, Ordering::SeqCst);

        if both && !was_active {
            send_event(HookEvent::Pressed);
        } else if !both && was_active {
            send_event(HookEvent::Released);
        }
    }

    fn send_event(event: HookEvent) {
        if let Ok(tx) = EVENT_TX.lock() {
            if let Some(tx) = tx.as_ref() {
                let _ = tx.send(event);
            }
        }
    }

    impl Drop for CtrlMetaHookGuard {
        fn drop(&mut self) {
            if !self.hook.is_null() {
                unsafe {
                    UnhookWindowsHookEx(self.hook);
                }
            }
            *EVENT_TX.lock().unwrap_or_else(|e| e.into_inner()) = None;
            set_paused(false);
        }
    }
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
    fn test_parse_hotkey_f_keys() {
        for (key, expected) in [("F1", Code::F1), ("F12", Code::F12)] {
            let s = parse_hotkey(&format!("Ctrl+{}", key));
            assert!(s.is_some(), "Failed to parse Ctrl+{}", key);
            assert_eq!(s.unwrap().key, expected);
        }
    }

    #[test]
    fn test_parse_hotkey_meta_modifier() {
        for name in ["Meta", "Super", "Win", "Cmd"] {
            let s = parse_hotkey(&format!("{}+A", name));
            assert!(s.is_some(), "Failed to parse {}+A", name);
            assert_eq!(s.unwrap().mods, Modifiers::SUPER);
        }
    }

    #[test]
    fn test_ctrl_meta_hotkey_detection() {
        for hotkey in ["Ctrl+Win", "Win+Ctrl", "Control+Meta", "Super+Control"] {
            assert!(is_ctrl_meta_hotkey(hotkey), "failed to detect {hotkey}");
        }

        for hotkey in ["Ctrl+/", "Win+A", "Ctrl+Alt+Win", "Ctrl", ""] {
            assert!(
                !is_ctrl_meta_hotkey(hotkey),
                "unexpected Ctrl+Win detection for {hotkey}"
            );
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
}
