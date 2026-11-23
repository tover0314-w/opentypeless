use async_trait::async_trait;
use enigo::{Direction, Enigo, Key, Keyboard, Settings};

use crate::error::AppError;

use super::{InsertResult, OutputMode, TextOutput};

/// Maximum characters per enigo.text() call to avoid input buffer overflow.
const TYPE_CHUNK_SIZE: usize = 200;
/// Delay between typing chunks.
const TYPE_CHUNK_DELAY_MS: u64 = 5;
/// Keep every `kwtype` positional argument well below Linux's per-argument limit.
#[cfg(any(target_os = "linux", test))]
const KWTYPE_CHUNK_SIZE: usize = 8_192;
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
    Kwtype,
}

#[cfg(any(target_os = "linux", test))]
fn select_linux_keyboard_backend(
    session_type: &str,
    current_desktop: &str,
    xdotool_available: bool,
    wtype_available: bool,
    kwtype_available: bool,
) -> std::result::Result<LinuxKeyboardBackend, String> {
    if session_type.eq_ignore_ascii_case("wayland") {
        if is_kde_desktop(current_desktop) {
            return kwtype_available
                .then_some(LinuxKeyboardBackend::Kwtype)
                .ok_or_else(|| "wayland_unsupported".to_string());
        }

        return wtype_available
            .then_some(LinuxKeyboardBackend::Wtype)
            .ok_or_else(|| "wayland_unsupported".to_string());
    }

    xdotool_available
        .then_some(LinuxKeyboardBackend::Xdotool)
        .ok_or_else(|| "xdotool_missing".to_string())
}

#[cfg(any(target_os = "linux", test))]
fn is_kde_desktop(current_desktop: &str) -> bool {
    current_desktop
        .split([':', ';'])
        .map(str::trim)
        .any(|part| {
            part.eq_ignore_ascii_case("kde")
                || part.eq_ignore_ascii_case("plasma")
                || part.eq_ignore_ascii_case("plasmawayland")
                || part.eq_ignore_ascii_case("plasmax11")
        })
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

#[cfg(target_os = "linux")]
fn detect_linux_keyboard_backend() -> std::result::Result<LinuxKeyboardBackend, String> {
    let current_desktop = std::env::var("XDG_CURRENT_DESKTOP")
        .ok()
        .filter(|desktop| !desktop.trim().is_empty())
        .or_else(|| std::env::var("DESKTOP_SESSION").ok())
        .unwrap_or_default();
    let current_desktop = if current_desktop.trim().is_empty()
        && (std::env::var_os("KDE_FULL_SESSION").is_some()
            || std::env::var_os("KDE_SESSION_VERSION").is_some())
    {
        "KDE".to_string()
    } else {
        current_desktop
    };

    select_linux_keyboard_backend(
        &crate::platform::current_session_type(),
        &current_desktop,
        executable_on_path("xdotool"),
        executable_on_path("wtype"),
        executable_on_path("kwtype"),
    )
}

/// Check if keyboard simulation is reliable on this platform.
/// Returns Ok(()) if fine, or Err with a reason string for the caller.
pub fn check_keyboard_available() -> std::result::Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        detect_linux_keyboard_backend()?;
    }
    let _ = (); // suppress unused warning on non-Linux
    Ok(())
}

pub struct KeyboardOutput {
    guard: super::guarded::OutputGuard,
    #[cfg(target_os = "macos")]
    app_handle: tauri::AppHandle,
}

impl KeyboardOutput {
    pub fn new(app_handle: &tauri::AppHandle) -> Self {
        Self::with_guard(app_handle, std::sync::Arc::new(|| true))
    }

    pub(crate) fn with_guard(
        app_handle: &tauri::AppHandle,
        guard: super::guarded::OutputGuard,
    ) -> Self {
        #[cfg(target_os = "macos")]
        {
            Self {
                guard,
                app_handle: app_handle.clone(),
            }
        }

        #[cfg(not(target_os = "macos"))]
        {
            let _ = app_handle;
            Self { guard }
        }
    }

    #[cfg(target_os = "macos")]
    async fn type_text_on_main_thread(&self, text: &str) -> Result<InsertResult, AppError> {
        let text = text.to_string();
        let timeout = macos_type_timeout(&text);
        let app_handle = self.app_handle.clone();
        let guard = self.guard.clone();
        let (tx, rx) = tokio::sync::oneshot::channel();

        app_handle
            .run_on_main_thread(move || {
                super::scheduled::execute_if_waiting(tx, |is_waiting| {
                    type_text_sync_while(&text, &|| is_waiting() && guard())
                });
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
            Err(_) => Err(AppError::OutputUnconfirmed(format!(
                "Main thread keyboard output timed out after {:.0}s",
                timeout.as_secs_f64()
            ))),
        }
    }
}

#[async_trait]
impl TextOutput for KeyboardOutput {
    async fn type_text(&self, text: &str) -> Result<InsertResult, AppError> {
        #[cfg(target_os = "macos")]
        {
            return self.type_text_on_main_thread(text).await;
        }

        #[cfg(not(target_os = "macos"))]
        {
            let text = text.to_string();
            let guard = self.guard.clone();
            tokio::task::spawn_blocking(move || {
                let completed_chars = std::cell::Cell::new(0);
                let result = type_text_sync_progress(&text, guard.as_ref(), &completed_chars);
                let progress = if result.is_ok() {
                    text.chars().count()
                } else {
                    completed_chars.get()
                };
                super::keyboard_progress::finish_keyboard_attempt(result, progress)
            })
            .await
            .map_err(|e| AppError::Output(format!("Spawn blocking error: {}", e)))?
        }
    }

    fn mode(&self) -> OutputMode {
        OutputMode::Keyboard
    }
}

#[cfg(target_os = "macos")]
fn type_text_sync_while(
    text: &str,
    is_active: &dyn Fn() -> bool,
) -> Result<InsertResult, AppError> {
    let completed_chars = std::cell::Cell::new(0);
    let result = type_text_sync_progress(text, is_active, &completed_chars);
    super::keyboard_progress::finish_keyboard_attempt(result, completed_chars.get())
}

fn type_text_sync_progress(
    text: &str,
    is_active: &dyn Fn() -> bool,
    completed_chars: &std::cell::Cell<usize>,
) -> Result<(), AppError> {
    if !is_active() {
        return Err(AppError::Output("Keyboard output was cancelled".into()));
    }
    super::windows_modifier_guard::wait_for_modifier_release()?;

    #[cfg(target_os = "linux")]
    match detect_linux_keyboard_backend().map_err(|reason| {
        AppError::Output(format!("Linux keyboard backend unavailable: {reason}"))
    })? {
        LinuxKeyboardBackend::Wtype => return type_text_with_wtype(text),
        LinuxKeyboardBackend::Kwtype => return type_text_with_kwtype(text),
        LinuxKeyboardBackend::Xdotool => {}
    }

    #[cfg(target_os = "macos")]
    let settings = Settings {
        event_source_user_data: Some(super::event_identity::KEYBOARD_OUTPUT_MARKER),
        ..Settings::default()
    };
    #[cfg(not(target_os = "macos"))]
    let settings = Settings::default();
    let enigo = Enigo::new(&settings)
        .map_err(|e| AppError::Output(format!("Failed to create Enigo: {:?}", e)))?;
    let enigo = std::cell::RefCell::new(enigo);

    let lines: Vec<&str> = text.split('\n').collect();
    for (i, line) in lines.iter().enumerate() {
        if !line.is_empty() {
            for chunk in line.chars().collect::<Vec<_>>().chunks(TYPE_CHUNK_SIZE) {
                if !is_active() {
                    return Err(AppError::Output("Keyboard output was cancelled".into()));
                }
                let s: String = chunk.iter().collect();
                enigo
                    .borrow_mut()
                    .text(&s)
                    .map_err(|e| AppError::Output(format!("Failed to type text: {:?}", e)))?;
                completed_chars.set(completed_chars.get() + chunk.len());
                std::thread::sleep(std::time::Duration::from_millis(TYPE_CHUNK_DELAY_MS));
            }
        }
        if i < lines.len() - 1 {
            if !is_active() {
                return Err(AppError::Output("Keyboard output was cancelled".into()));
            }
            super::keyboard_progress::type_shifted_newline(
                || {
                    enigo
                        .borrow_mut()
                        .key(Key::Shift, Direction::Press)
                        .map_err(|error| AppError::Output(format!("Key error: {error:?}")))
                },
                || {
                    enigo
                        .borrow_mut()
                        .key(Key::Return, Direction::Click)
                        .map_err(|error| AppError::Output(format!("Key error: {error:?}")))?;
                    completed_chars.set(completed_chars.get() + 1);
                    Ok(())
                },
                || {
                    enigo
                        .borrow_mut()
                        .key(Key::Shift, Direction::Release)
                        .map_err(|error| AppError::Output(format!("Key error: {error:?}")))
                },
            )?;
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

#[cfg(any(target_os = "linux", test))]
fn kwtype_chunks(text: &str) -> Vec<String> {
    text.chars()
        .collect::<Vec<_>>()
        .chunks(KWTYPE_CHUNK_SIZE)
        .map(|chunk| chunk.iter().collect())
        .collect()
}

#[cfg(any(target_os = "linux", test))]
fn type_text_with_kwtype(text: &str) -> Result<(), AppError> {
    use std::process::{Command, Stdio};

    for chunk in kwtype_chunks(text) {
        let output = Command::new("kwtype")
            .arg("--")
            .arg(chunk)
            .stdout(Stdio::null())
            .stderr(Stdio::piped())
            .output()
            .map_err(|error| AppError::Output(format!("Failed to start kwtype: {error}")))?;

        if output.status.success() {
            continue;
        }

        let details: String = String::from_utf8_lossy(&output.stderr)
            .trim()
            .chars()
            .take(200)
            .collect();
        return Err(AppError::Output(if details.is_empty() {
            format!("kwtype failed with exit code {:?}", output.status.code())
        } else {
            format!("kwtype failed: {details}")
        }));
    }

    Ok(())
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
            select_linux_keyboard_backend("wayland", "sway", false, true, false),
            Ok(LinuxKeyboardBackend::Wtype)
        );
    }

    #[test]
    fn wayland_keeps_existing_clipboard_fallback_when_wtype_is_missing() {
        assert_eq!(
            select_linux_keyboard_backend("WAYLAND", "sway", true, false, false),
            Err("wayland_unsupported".to_string())
        );
    }

    #[test]
    fn kde_wayland_uses_kwtype_instead_of_wtype() {
        assert_eq!(
            select_linux_keyboard_backend("wayland", "KDE", false, true, true),
            Ok(LinuxKeyboardBackend::Kwtype)
        );
        assert_eq!(
            select_linux_keyboard_backend("wayland", "KDE:Plasma", false, true, false),
            Err("wayland_unsupported".to_string())
        );
    }

    #[test]
    fn kde_desktop_detection_is_delimited_and_case_insensitive() {
        assert!(is_kde_desktop("KDE"));
        assert!(is_kde_desktop("ubuntu:PLASMA"));
        assert!(is_kde_desktop("GNOME;KDE"));
        assert!(is_kde_desktop("plasmawayland"));
        assert!(is_kde_desktop("PlasmaX11"));
        assert!(!is_kde_desktop("GNOME"));
        assert!(!is_kde_desktop("notkde"));
    }

    #[test]
    fn x11_still_requires_xdotool() {
        assert_eq!(
            select_linux_keyboard_backend("x11", "KDE", true, false, false),
            Ok(LinuxKeyboardBackend::Xdotool)
        );
        assert_eq!(
            select_linux_keyboard_backend("x11", "KDE", false, true, true),
            Err("xdotool_missing".to_string())
        );
    }

    #[test]
    fn kwtype_chunks_preserve_text_and_bound_each_argument() {
        let text = format!("{}{}", "a".repeat(KWTYPE_CHUNK_SIZE), "界".repeat(3));
        let chunks = kwtype_chunks(&text);

        assert_eq!(chunks.len(), 2);
        assert!(chunks
            .iter()
            .all(|chunk| chunk.chars().count() <= KWTYPE_CHUNK_SIZE));
        assert_eq!(chunks.concat(), text);
    }

    #[test]
    fn kwtype_empty_text_is_a_noop() {
        assert!(type_text_with_kwtype("").is_ok());
    }
}
