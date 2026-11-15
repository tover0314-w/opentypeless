use std::ffi::c_void;
use std::process::Command;

use url::Url;

use super::ContextSignalSource;
use crate::app_detector::types::{
    BrowserAccessStatus, BrowserTarget, ContextSignals, TargetAppGuard,
};

pub struct MacOsContextSource;

#[derive(Clone, Copy)]
struct SupportedBrowser {
    script_name: &'static str,
    bundle_id: &'static str,
    target: BrowserTarget,
}

pub(crate) fn restore_target_application(target: &TargetAppGuard) -> bool {
    let Some(process_id) = target.process_id else {
        return false;
    };
    let script = format!(
        "tell application \"System Events\" to set frontmost of first application process whose unix id is {process_id} to true"
    );
    Command::new("/usr/bin/osascript")
        .args(["-e", &script])
        .status()
        .is_ok_and(|status| status.success())
}

impl ContextSignalSource for MacOsContextSource {
    fn target_identity(&self) -> Option<TargetAppGuard> {
        use objc2::msg_send;
        use objc2::runtime::{AnyClass, AnyObject};
        use std::ffi::CStr;

        objc2::rc::autoreleasepool(|_| unsafe {
            let workspace_class = AnyClass::get("NSWorkspace")?;
            let workspace: *mut AnyObject = msg_send![workspace_class, sharedWorkspace];
            if workspace.is_null() {
                return None;
            }
            let application: *mut AnyObject = msg_send![workspace, frontmostApplication];
            if application.is_null() {
                return None;
            }
            let process_id: i32 = msg_send![application, processIdentifier];
            if process_id <= 0 {
                return None;
            }
            let identifier: *mut AnyObject = msg_send![application, bundleIdentifier];
            let native_identity = if identifier.is_null() {
                None
            } else {
                let bytes: *const std::os::raw::c_char = msg_send![identifier, UTF8String];
                if bytes.is_null() {
                    None
                } else {
                    CStr::from_ptr(bytes).to_str().ok().map(str::to_owned)
                }
            };
            Some(TargetAppGuard {
                process_id: Some(process_id as u32),
                native_identity,
            })
        })
    }

    fn collect(&self) -> Option<ContextSignals> {
        let output = Command::new("/usr/bin/osascript")
            .args(["-e", FRONT_APP_SCRIPT])
            .output()
            .ok()?;
        if !output.status.success() {
            return None;
        }

        let value = String::from_utf8(output.stdout).ok()?;
        let mut fields = value.trim_end().split('\u{1f}');
        let app_name = fields.next()?.trim().to_string();
        let process_id = fields.next().and_then(|value| value.trim().parse().ok());
        let native_identity = fields
            .next()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_string);
        let window_title = fields
            .next()
            .map(str::trim)
            .filter(|value| !value.is_empty())
            .map(str::to_string);

        let browser = supported_browser(native_identity.as_deref(), &app_name);
        let (browser_host, browser_access_status) = match browser {
            Some(browser) => {
                let permission_status = browser_access_status_for_automation_permission(
                    automation_permission_status(browser.bundle_id, false),
                );
                if permission_status != BrowserAccessStatus::Available {
                    (None, permission_status)
                } else {
                    match read_browser_host(browser.script_name) {
                        Some(host) => (Some(host), BrowserAccessStatus::Available),
                        None => (None, BrowserAccessStatus::Unknown),
                    }
                }
            }
            None => (None, BrowserAccessStatus::NotApplicable),
        };

        Some(ContextSignals {
            process_id,
            native_identity,
            process_alias: Some(app_name),
            window_title,
            browser_host,
            is_supported_browser: browser.is_some(),
            browser_access_status,
            browser_target: browser.map(|browser| browser.target),
        })
    }
}

const FRONT_APP_SCRIPT: &str = r#"
tell application "System Events"
    set frontProcess to first application process whose frontmost is true
    set appName to name of frontProcess
    set appPid to unix id of frontProcess
    try
        set bundleId to bundle identifier of frontProcess
    on error
        set bundleId to ""
    end try
    try
        set windowName to name of front window of frontProcess
    on error
        set windowName to ""
    end try
    return appName & ASCII character 31 & (appPid as text) & ASCII character 31 & bundleId & ASCII character 31 & windowName
end tell
"#;

fn supported_browser(identity: Option<&str>, app_name: &str) -> Option<SupportedBrowser> {
    let identity = identity.unwrap_or_default();
    match identity {
        "com.apple.Safari" => Some(SupportedBrowser {
            script_name: "Safari",
            bundle_id: "com.apple.Safari",
            target: BrowserTarget::Safari,
        }),
        "com.google.Chrome" => Some(SupportedBrowser {
            script_name: "Google Chrome",
            bundle_id: "com.google.Chrome",
            target: BrowserTarget::Chrome,
        }),
        "com.microsoft.edgemac" => Some(SupportedBrowser {
            script_name: "Microsoft Edge",
            bundle_id: "com.microsoft.edgemac",
            target: BrowserTarget::Edge,
        }),
        "com.brave.Browser" => Some(SupportedBrowser {
            script_name: "Brave Browser",
            bundle_id: "com.brave.Browser",
            target: BrowserTarget::Brave,
        }),
        "company.thebrowser.Browser" => Some(SupportedBrowser {
            script_name: "Arc",
            bundle_id: "company.thebrowser.Browser",
            target: BrowserTarget::Arc,
        }),
        _ => match app_name {
            "Safari" => supported_browser(Some("com.apple.Safari"), ""),
            "Google Chrome" => supported_browser(Some("com.google.Chrome"), ""),
            "Microsoft Edge" => supported_browser(Some("com.microsoft.edgemac"), ""),
            "Brave Browser" => supported_browser(Some("com.brave.Browser"), ""),
            "Arc" => supported_browser(Some("company.thebrowser.Browser"), ""),
            _ => None,
        },
    }
}

#[repr(C)]
struct AeDesc {
    descriptor_type: u32,
    data_handle: *mut c_void,
}

#[link(name = "ApplicationServices", kind = "framework")]
extern "C" {
    fn AECreateDesc(
        descriptor_type: u32,
        data: *const c_void,
        data_size: isize,
        result: *mut AeDesc,
    ) -> i16;
    fn AEDisposeDesc(descriptor: *mut AeDesc) -> i16;
    fn AEDeterminePermissionToAutomateTarget(
        target: *const AeDesc,
        event_class: u32,
        event_id: u32,
        ask_user_if_needed: u8,
    ) -> i32;
}

fn automation_permission_status(bundle_id: &str, ask_user_if_needed: bool) -> i32 {
    const TYPE_APPLICATION_BUNDLE_ID: u32 = u32::from_be_bytes(*b"bund");
    const CORE_EVENT_CLASS: u32 = u32::from_be_bytes(*b"core");
    const GET_DATA_EVENT_ID: u32 = u32::from_be_bytes(*b"getd");

    let mut target = AeDesc {
        descriptor_type: 0,
        data_handle: std::ptr::null_mut(),
    };
    let create_status = unsafe {
        AECreateDesc(
            TYPE_APPLICATION_BUNDLE_ID,
            bundle_id.as_ptr().cast(),
            bundle_id.len() as isize,
            &mut target,
        )
    };
    if create_status != 0 {
        return i32::from(create_status);
    }

    let permission_status = unsafe {
        AEDeterminePermissionToAutomateTarget(
            &target,
            CORE_EVENT_CLASS,
            GET_DATA_EVENT_ID,
            u8::from(ask_user_if_needed),
        )
    };
    unsafe {
        let _ = AEDisposeDesc(&mut target);
    }
    permission_status
}

pub(crate) fn request_browser_access(target: BrowserTarget) -> BrowserAccessStatus {
    let bundle_id = match target {
        BrowserTarget::Safari => "com.apple.Safari",
        BrowserTarget::Chrome => "com.google.Chrome",
        BrowserTarget::Edge => "com.microsoft.edgemac",
        BrowserTarget::Brave => "com.brave.Browser",
        BrowserTarget::Arc => "company.thebrowser.Browser",
    };
    browser_access_status_for_automation_permission(automation_permission_status(bundle_id, true))
}

fn browser_access_status_for_automation_permission(status: i32) -> BrowserAccessStatus {
    match status {
        0 => BrowserAccessStatus::Available,
        -1743 | -1744 => BrowserAccessStatus::NeedsPermission,
        _ => BrowserAccessStatus::Unknown,
    }
}

fn read_browser_host(browser_name: &str) -> Option<String> {
    let script = if browser_name == "Safari" {
        r#"tell application "Safari" to get URL of current tab of front window"#.to_string()
    } else {
        format!("tell application \"{browser_name}\" to get URL of active tab of front window")
    };
    let output = Command::new("/usr/bin/osascript")
        .args(["-e", &script])
        .output()
        .ok()?;
    if !output.status.success() {
        return None;
    }

    let transient_url = String::from_utf8(output.stdout).ok()?;
    Url::parse(transient_url.trim())
        .ok()?
        .host_str()
        .map(|host| host.trim_end_matches('.').to_ascii_lowercase())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn automation_permission_status_only_requests_action_for_permission_errors() {
        assert_eq!(
            browser_access_status_for_automation_permission(0),
            BrowserAccessStatus::Available
        );
        assert_eq!(
            browser_access_status_for_automation_permission(-1743),
            BrowserAccessStatus::NeedsPermission
        );
        assert_eq!(
            browser_access_status_for_automation_permission(-1744),
            BrowserAccessStatus::NeedsPermission
        );
        assert_eq!(
            browser_access_status_for_automation_permission(-600),
            BrowserAccessStatus::Unknown
        );
        assert_eq!(
            browser_access_status_for_automation_permission(-1),
            BrowserAccessStatus::Unknown
        );
    }
}
