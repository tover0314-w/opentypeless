use async_trait::async_trait;
use enigo::{Direction, Enigo, Key, Keyboard, Settings};

use crate::error::AppError;

use super::{InsertResult, InsertionStrategy, OutputMode, TextOutput};

/// Maximum characters per enigo.text() call to avoid input buffer overflow.
const TYPE_CHUNK_SIZE: usize = 200;
/// Delay between typing chunks.
const TYPE_CHUNK_DELAY_MS: u64 = 5;
/// Base timeout for macOS main-thread keyboard output.
#[cfg(target_os = "macos")]
const MACOS_TYPE_BASE_TIMEOUT_SECS: u64 = 30;
/// Maximum timeout for macOS main-thread keyboard output.
#[cfg(target_os = "macos")]
const MACOS_TYPE_MAX_TIMEOUT_SECS: u64 = 300;

#[cfg(any(target_os = "linux", test))]
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
enum LinuxKeyboardBackend {
    Xdotool,
    Wtype,
}

#[cfg(any(target_os = "linux", test))]
fn select_linux_keyboard_backend(
    session_type: &str,
    xdotool_available: bool,
    wtype_available: bool,
) -> std::result::Result<LinuxKeyboardBackend, String> {
    if session_type.eq_ignore_ascii_case("wayland") {
        return wtype_available
            .then_some(LinuxKeyboardBackend::Wtype)
            .ok_or_else(|| "wayland_unsupported".to_string());
    }

    xdotool_available
        .then_some(LinuxKeyboardBackend::Xdotool)
        .ok_or_else(|| "xdotool_missing".to_string())
}

#[cfg(target_os = "linux")]
fn executable_on_path(name: &str) -> bool {
    use std::os::unix::fs::PermissionsExt;

    std::env::var_os("PATH")
        .map(|paths| {
            std::env::split_paths(&paths).any(|path| {
                path.join(name)
                    .metadata()
                    .map(|metadata| {
                        metadata.is_file() && metadata.permissions().mode() & 0o111 != 0
                    })
                    .unwrap_or(false)
            })
        })
        .unwrap_or(false)
}

/// Check if keyboard simulation is reliable on this platform.
/// Returns Ok(()) if fine, or Err with a reason string for the caller.
pub fn check_keyboard_available() -> std::result::Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        let session = crate::platform::current_session_type();
        select_linux_keyboard_backend(
            &session,
            executable_on_path("xdotool"),
            executable_on_path("wtype"),
        )?;
    }
    let _ = (); // suppress unused warning on non-Linux
    Ok(())
}

pub struct KeyboardOutput {
    #[cfg(target_os = "macos")]
    app_handle: tauri::AppHandle,
}

impl KeyboardOutput {
    pub fn new(app_handle: &tauri::AppHandle) -> Self {
        #[cfg(target_os = "macos")]
        {
            Self {
                app_handle: app_handle.clone(),
            }
        }

        #[cfg(not(target_os = "macos"))]
        {
            let _ = app_handle;
            Self {}
        }
    }

    #[cfg(target_os = "macos")]
    async fn type_text_on_main_thread(&self, text: &str) -> Result<(), AppError> {
        let text = text.to_string();
        let timeout = macos_type_timeout(&text);
        let app_handle = self.app_handle.clone();
        let (tx, rx) = tokio::sync::oneshot::channel();

        app_handle
            .run_on_main_thread(move || {
                let result = type_text_sync(&text);
                let _ = tx.send(result);
            })
            .map_err(|e| {
                AppError::Output(format!(
                    "Failed to schedule keyboard output on main thread: {}",
                    e
                ))
            })?;

        match tokio::time::timeout(timeout, rx).await {
            Ok(Ok(result)) => result,
            Ok(Err(_)) => Err(AppError::Output(
                "Main thread keyboard output task was dropped".to_string(),
            )),
            Err(_) => Err(AppError::Output(format!(
                "Main thread keyboard output timed out after {:.0}s",
                timeout.as_secs_f64()
            ))),
        }
    }
}

#[async_trait]
impl TextOutput for KeyboardOutput {
    async fn type_text(&self, text: &str) -> Result<InsertResult, AppError> {
        let chars_inserted = text.chars().count();

        #[cfg(target_os = "macos")]
        {
            self.type_text_on_main_thread(text).await?;
            return Ok(InsertResult::inserted(
                InsertionStrategy::Keyboard,
                chars_inserted,
            ));
        }

        #[cfg(not(target_os = "macos"))]
        {
            let text = text.to_string();
            tokio::task::spawn_blocking(move || type_text_sync(&text))
                .await
                .map_err(|e| AppError::Output(format!("Spawn blocking error: {}", e)))??;
            Ok(InsertResult::inserted(
                InsertionStrategy::Keyboard,
                chars_inserted,
            ))
        }
    }

    fn mode(&self) -> OutputMode {
        OutputMode::Keyboard
    }
}

fn type_text_sync(text: &str) -> Result<(), AppError> {
    super::windows_modifier_guard::wait_for_modifier_release()?;

    #[cfg(target_os = "linux")]
    if crate::platform::current_session_type().eq_ignore_ascii_case("wayland") {
        return type_text_with_wtype(text);
    }

    let mut enigo = Enigo::new(&Settings::default())
        .map_err(|e| AppError::Output(format!("Failed to create Enigo: {:?}", e)))?;

    let lines: Vec<&str> = text.split('\n').collect();
    for (i, line) in lines.iter().enumerate() {
        if !line.is_empty() {
            for chunk in line.chars().collect::<Vec<_>>().chunks(TYPE_CHUNK_SIZE) {
                let s: String = chunk.iter().collect();
                enigo
                    .text(&s)
                    .map_err(|e| AppError::Output(format!("Failed to type text: {:?}", e)))?;
                std::thread::sleep(std::time::Duration::from_millis(TYPE_CHUNK_DELAY_MS));
            }
        }
        if i < lines.len() - 1 {
            enigo
                .key(Key::Shift, Direction::Press)
                .map_err(|e| AppError::Output(format!("Key error: {:?}", e)))?;
            enigo
                .key(Key::Return, Direction::Click)
                .map_err(|e| AppError::Output(format!("Key error: {:?}", e)))?;
            enigo
                .key(Key::Shift, Direction::Release)
                .map_err(|e| AppError::Output(format!("Key error: {:?}", e)))?;
        }
    }

    Ok(())
}

#[cfg(target_os = "linux")]
fn type_text_with_wtype(text: &str) -> Result<(), AppError> {
    use std::io::Write;
    use std::process::{Command, Stdio};

    let mut child = Command::new("wtype")
        .args(["-d", "1", "-"])
        .stdin(Stdio::piped())
        .stdout(Stdio::null())
        .stderr(Stdio::piped())
        .spawn()
        .map_err(|error| AppError::Output(format!("Failed to start wtype: {error}")))?;

    let write_result = child
        .stdin
        .take()
        .ok_or_else(|| AppError::Output("Failed to open wtype stdin".to_string()))
        .and_then(|mut stdin| {
            stdin
                .write_all(text.as_bytes())
                .map_err(|error| AppError::Output(format!("Failed to send text to wtype: {error}")))
        });
    if let Err(error) = write_result {
        let _ = child.kill();
        let _ = child.wait();
        return Err(error);
    }

    let output = child
        .wait_with_output()
        .map_err(|error| AppError::Output(format!("Failed to wait for wtype: {error}")))?;
    if output.status.success() {
        return Ok(());
    }

    let details: String = String::from_utf8_lossy(&output.stderr)
        .trim()
        .chars()
        .take(200)
        .collect();
    Err(AppError::Output(if details.is_empty() {
        format!("wtype failed with exit code {:?}", output.status.code())
    } else {
        format!("wtype failed: {details}")
    }))
}

#[cfg(target_os = "macos")]
fn macos_type_timeout(text: &str) -> std::time::Duration {
    let char_count = text.chars().count();
    let chunk_count = if char_count == 0 {
        0
    } else {
        ((char_count - 1) / TYPE_CHUNK_SIZE) + 1
    };
    let seconds =
        (MACOS_TYPE_BASE_TIMEOUT_SECS + chunk_count as u64).min(MACOS_TYPE_MAX_TIMEOUT_SECS);

    std::time::Duration::from_secs(seconds)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wayland_uses_wtype_when_installed() {
        assert_eq!(
            select_linux_keyboard_backend("wayland", false, true),
            Ok(LinuxKeyboardBackend::Wtype)
        );
    }

    #[test]
    fn wayland_keeps_existing_clipboard_fallback_when_wtype_is_missing() {
        assert_eq!(
            select_linux_keyboard_backend("WAYLAND", true, false),
            Err("wayland_unsupported".to_string())
        );
    }

    #[test]
    fn x11_still_requires_xdotool() {
        assert_eq!(
            select_linux_keyboard_backend("x11", true, false),
            Ok(LinuxKeyboardBackend::Xdotool)
        );
        assert_eq!(
            select_linux_keyboard_backend("x11", false, true),
            Err("xdotool_missing".to_string())
        );
    }
}
