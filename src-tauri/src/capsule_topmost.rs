//! Keeps the capsule window above other windows on Windows.
//!
//! The capsule keeps WS_EX_TOPMOST, yet other apps (seen with a maximized Claude
//! desktop window) can still end up above it, hiding the capsule while dictation
//! keeps running. Re-asserting HWND_TOPMOST on every pipeline state change brings
//! it back in front.

pub fn raise(app: &tauri::AppHandle) {
    #[cfg(target_os = "windows")]
    {
        use tauri::Manager;
        use windows_sys::Win32::UI::WindowsAndMessaging::{
            SetWindowPos, HWND_TOPMOST, SWP_ASYNCWINDOWPOS, SWP_NOACTIVATE, SWP_NOMOVE, SWP_NOSIZE,
        };

        let Some(window) = app.get_webview_window("capsule") else {
            return;
        };
        let Ok(hwnd) = window.hwnd() else {
            return;
        };
        // SAFETY: hwnd is our live capsule window. SWP_ASYNCWINDOWPOS posts the
        // change instead of blocking on the UI thread from an async-runtime thread.
        unsafe {
            SetWindowPos(
                hwnd.0 as _,
                HWND_TOPMOST,
                0,
                0,
                0,
                0,
                SWP_ASYNCWINDOWPOS | SWP_NOMOVE | SWP_NOSIZE | SWP_NOACTIVATE,
            );
        }
    }
    #[cfg(not(target_os = "windows"))]
    let _ = app;
}
