use std::path::PathBuf;
use std::process::Command;

use super::ContextSignalSource;
use crate::app_detector::types::{BrowserAccessStatus, ContextSignals, TargetAppGuard};

pub struct LinuxContextSource;

pub(crate) fn restore_target_application(target: &TargetAppGuard) -> bool {
    if crate::platform::is_wayland_session() {
        return false;
    }
    let Some(process_id) = target.process_id else {
        return false;
    };
    let output = Command::new("xdotool")
        .args(["search", "--onlyvisible", "--pid", &process_id.to_string()])
        .output();
    let Some(window_id) = output
        .ok()
        .filter(|output| output.status.success())
        .and_then(|output| String::from_utf8(output.stdout).ok())
        .and_then(|value| value.lines().next().map(str::to_string))
    else {
        return false;
    };
    Command::new("xdotool")
        .args(["windowactivate", "--sync", &window_id])
        .status()
        .is_ok_and(|status| status.success())
}

impl ContextSignalSource for LinuxContextSource {
    fn collect(&self) -> Option<ContextSignals> {
        if crate::platform::is_wayland_session() {
            return None;
        }

        let window_id = command_output(&["getactivewindow"])?;
        let process_id = command_output(&["getwindowpid", window_id.trim()])?
            .trim()
            .parse::<u32>()
            .ok()?;
        let window_title = command_output(&["getwindowname", window_id.trim()])
            .map(|value| value.trim().to_string())
            .filter(|value| !value.is_empty());
        let process_alias = std::fs::read_link(PathBuf::from(format!("/proc/{process_id}/exe")))
            .ok()
            .and_then(|path| {
                path.file_name()
                    .map(|value| value.to_string_lossy().to_string())
            });
        let is_supported_browser = process_alias.as_deref().is_some_and(is_supported_browser);

        Some(ContextSignals {
            process_id: Some(process_id),
            native_identity: process_alias.clone(),
            process_alias,
            window_title,
            browser_host: None,
            is_supported_browser,
            browser_access_status: BrowserAccessStatus::for_unavailable_url_adapter(
                is_supported_browser,
            ),
            browser_target: None,
        })
    }
}

fn command_output(args: &[&str]) -> Option<String> {
    let output = Command::new("xdotool").args(args).output().ok()?;
    output
        .status
        .success()
        .then(|| String::from_utf8_lossy(&output.stdout).to_string())
}

fn is_supported_browser(process_name: &str) -> bool {
    matches!(
        process_name.to_ascii_lowercase().as_str(),
        "google-chrome" | "chrome" | "chromium" | "microsoft-edge" | "brave-browser" | "arc"
    )
}
