use std::ffi::OsString;
use std::os::windows::ffi::OsStringExt;

use super::ContextSignalSource;
use crate::app_detector::types::{BrowserAccessStatus, ContextSignals, TargetAppGuard};

pub struct WindowsContextSource;

pub(crate) fn restore_target_application(target: &TargetAppGuard) -> bool {
    let Some(process_id) = target.process_id else {
        return false;
    };
    unsafe { restore_process_window(process_id) }
}

#[repr(C)]
struct WindowSearch {
    process_id: u32,
    window: windows_sys::Win32::Foundation::HWND,
}

unsafe extern "system" fn find_process_window(
    window: windows_sys::Win32::Foundation::HWND,
    parameter: windows_sys::Win32::Foundation::LPARAM,
) -> windows_sys::Win32::Foundation::BOOL {
    let search = &mut *(parameter as *mut WindowSearch);
    if windows_sys::Win32::UI::WindowsAndMessaging::IsWindowVisible(window) == 0 {
        return 1;
    }
    let mut process_id = 0u32;
    windows_sys::Win32::UI::WindowsAndMessaging::GetWindowThreadProcessId(window, &mut process_id);
    if process_id == search.process_id {
        search.window = window;
        return 0;
    }
    1
}

unsafe fn restore_process_window(process_id: u32) -> bool {
    let mut search = WindowSearch {
        process_id,
        window: std::ptr::null_mut(),
    };
    windows_sys::Win32::UI::WindowsAndMessaging::EnumWindows(
        Some(find_process_window),
        &mut search as *mut WindowSearch as isize,
    );
    if search.window.is_null() {
        return false;
    }
    windows_sys::Win32::UI::WindowsAndMessaging::ShowWindow(
        search.window,
        windows_sys::Win32::UI::WindowsAndMessaging::SW_RESTORE,
    );
    windows_sys::Win32::UI::WindowsAndMessaging::SetForegroundWindow(search.window) != 0
}

impl ContextSignalSource for WindowsContextSource {
    fn collect(&self) -> Option<ContextSignals> {
        unsafe { collect_foreground_context() }
    }
}

unsafe fn collect_foreground_context() -> Option<ContextSignals> {
    let hwnd = windows_sys::Win32::UI::WindowsAndMessaging::GetForegroundWindow();
    if hwnd.is_null() {
        return None;
    }

    let mut title_buf = [0u16; 512];
    let title_len = windows_sys::Win32::UI::WindowsAndMessaging::GetWindowTextW(
        hwnd,
        title_buf.as_mut_ptr(),
        title_buf.len() as i32,
    );
    let window_title = (title_len > 0).then(|| {
        OsString::from_wide(&title_buf[..title_len as usize])
            .to_string_lossy()
            .to_string()
    });

    let mut process_id = 0u32;
    windows_sys::Win32::UI::WindowsAndMessaging::GetWindowThreadProcessId(hwnd, &mut process_id);
    let process_alias = process_name(process_id);
    let supported_browser = process_alias.as_deref().is_some_and(is_supported_browser);

    Some(ContextSignals {
        process_id: (process_id != 0).then_some(process_id),
        native_identity: process_alias.clone(),
        process_alias,
        window_title,
        browser_host: None,
        is_supported_browser: supported_browser,
        browser_access_status: BrowserAccessStatus::for_unavailable_url_adapter(supported_browser),
        browser_target: None,
    })
}

unsafe fn process_name(process_id: u32) -> Option<String> {
    if process_id == 0 {
        return None;
    }
    let handle = windows_sys::Win32::System::Threading::OpenProcess(
        windows_sys::Win32::System::Threading::PROCESS_QUERY_LIMITED_INFORMATION,
        0,
        process_id,
    );
    if handle.is_null() {
        return None;
    }

    let mut buffer = [0u16; 260];
    let mut size = buffer.len() as u32;
    let ok = windows_sys::Win32::System::Threading::QueryFullProcessImageNameW(
        handle,
        0,
        buffer.as_mut_ptr(),
        &mut size,
    );
    let _ = windows_sys::Win32::Foundation::CloseHandle(handle);
    if ok == 0 || size == 0 {
        return None;
    }

    let path = OsString::from_wide(&buffer[..size as usize])
        .to_string_lossy()
        .to_string();
    path.rsplit('\\').next().map(str::to_string)
}

fn is_supported_browser(process_name: &str) -> bool {
    matches!(
        process_name.to_ascii_lowercase().as_str(),
        "chrome.exe" | "msedge.exe" | "brave.exe" | "arc.exe"
    )
}
