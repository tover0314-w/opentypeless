use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tokio::sync::mpsc;

// ─── Public API ───

#[derive(Debug, Clone, Copy)]
pub enum HookEvent {
    ComboPressed,
    ComboReleased,
}

/// RAII guard — on drop, unhooks the keyboard hook (Windows) or no-ops (others).
pub struct HookGuard {
    #[cfg(target_os = "windows")]
    hook: windows_sys::Win32::UI::WindowsAndMessaging::HHOOK,
}

// HHOOK is a pointer; the guard is only used in managed Tauri state on the main thread.
#[cfg(target_os = "windows")]
unsafe impl Send for HookGuard {}
#[cfg(target_os = "windows")]
unsafe impl Sync for HookGuard {}

/// Install a low-level keyboard hook that monitors `Ctrl+Win`.
///
/// On Windows, installs a `WH_KEYBOARD_LL` hook. On other platforms returns
/// a no-op handle that never produces events.
pub fn install() -> (HookGuard, mpsc::UnboundedReceiver<HookEvent>) {
    #[cfg(target_os = "windows")]
    {
        inner::do_install()
    }
    #[cfg(not(target_os = "windows"))]
    {
        let (_tx, rx) = mpsc::unbounded_channel();
        (HookGuard, rx)
    }
}

/// Pause / resume hook event delivery (used by the frontend hotkey recorder).
pub fn set_paused(paused: bool) {
    #[cfg(target_os = "windows")]
    inner::PAUSED.store(paused, Ordering::SeqCst);
    #[cfg(not(target_os = "windows"))]
    let _ = paused;
}

// ─── Windows implementation ───

#[cfg(target_os = "windows")]
mod inner {
    use super::*;
    use windows_sys::Win32::Foundation::{HINSTANCE, LPARAM, LRESULT, WPARAM};
    use windows_sys::Win32::System::LibraryLoader::GetModuleHandleW;
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        VK_CONTROL, VK_LCONTROL, VK_LWIN, VK_RCONTROL, VK_RWIN,
    };
    use windows_sys::Win32::UI::WindowsAndMessaging::{
        CallNextHookEx, SetWindowsHookExW, HC_ACTION, KBDLLHOOKSTRUCT, WH_KEYBOARD_LL, WM_KEYDOWN,
        WM_SYSKEYDOWN,
    };

    pub(super) static INSTALLED: AtomicBool = AtomicBool::new(false);
    static EVENT_TX: Mutex<Option<mpsc::UnboundedSender<HookEvent>>> = Mutex::new(None);
    pub(super) static PAUSED: AtomicBool = AtomicBool::new(false);

    static CTRL_DOWN: AtomicBool = AtomicBool::new(false);
    static WIN_DOWN: AtomicBool = AtomicBool::new(false);
    static COMBO_ACTIVE: AtomicBool = AtomicBool::new(false);

    pub(super) fn do_install() -> (HookGuard, mpsc::UnboundedReceiver<HookEvent>) {
        let (tx, rx) = mpsc::unbounded_channel();
        *EVENT_TX.lock().unwrap_or_else(|e| e.into_inner()) = Some(tx);

        unsafe {
            let hook = SetWindowsHookExW(
                WH_KEYBOARD_LL,
                Some(keyboard_proc),
                GetModuleHandleW(std::ptr::null()) as HINSTANCE,
                0,
            );

            INSTALLED.store(!hook.is_null(), Ordering::SeqCst);
            (HookGuard { hook }, rx)
        }
    }

    unsafe extern "system" fn keyboard_proc(
        code: i32,
        wparam: WPARAM,
        lparam: LPARAM,
    ) -> LRESULT {
        if code as u32 == HC_ACTION && !PAUSED.load(Ordering::SeqCst) {
            let kb = &*(lparam as *const KBDLLHOOKSTRUCT);
            let is_down =
                wparam as u32 == WM_KEYDOWN || wparam as u32 == WM_SYSKEYDOWN;

            let vk = kb.vkCode;
            let ctrl = VK_CONTROL as u32;
            let lctrl = VK_LCONTROL as u32;
            let rctrl = VK_RCONTROL as u32;
            if vk == ctrl || vk == lctrl || vk == rctrl {
                CTRL_DOWN.store(is_down, Ordering::SeqCst);
            }
            let lwin = VK_LWIN as u32;
            let rwin = VK_RWIN as u32;
            if vk == lwin || vk == rwin {
                WIN_DOWN.store(is_down, Ordering::SeqCst);
            }

            let both = CTRL_DOWN.load(Ordering::SeqCst) && WIN_DOWN.load(Ordering::SeqCst);
            let was_active = COMBO_ACTIVE.load(Ordering::SeqCst);

            if both && !was_active {
                COMBO_ACTIVE.store(true, Ordering::SeqCst);
                if let Ok(tx) = EVENT_TX.lock() {
                    let _ = tx.as_ref().map(|t| t.send(HookEvent::ComboPressed));
                }
            } else if !both && was_active {
                COMBO_ACTIVE.store(false, Ordering::SeqCst);
                if let Ok(tx) = EVENT_TX.lock() {
                    let _ = tx.as_ref().map(|t| t.send(HookEvent::ComboReleased));
                }
            }
        }

        CallNextHookEx(std::ptr::null_mut(), code, wparam, lparam)
    }
}

#[cfg(target_os = "windows")]
impl Drop for HookGuard {
    fn drop(&mut self) {
        if !self.hook.is_null() {
            unsafe {
                windows_sys::Win32::UI::WindowsAndMessaging::UnhookWindowsHookEx(self.hook);
            }
        }
        inner::INSTALLED.store(false, Ordering::SeqCst);
    }
}

#[cfg(not(target_os = "windows"))]
impl Drop for HookGuard {
    fn drop(&mut self) {}
}
