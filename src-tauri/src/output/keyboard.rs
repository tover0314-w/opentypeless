use async_trait::async_trait;
use enigo::{Direction, Enigo, Key, Keyboard, Settings};

use crate::error::AppError;

use super::{OutputMode, TextOutput};

/// Maximum characters per enigo.text() call to avoid input buffer overflow.
const TYPE_CHUNK_SIZE: usize = 200;
/// Delay between typing chunks.
const TYPE_CHUNK_DELAY_MS: u64 = 5;

/// Check if keyboard simulation is reliable on this platform.
/// Returns Ok(()) if fine, or Err with a reason string for the caller.
pub fn check_keyboard_available() -> std::result::Result<(), String> {
    #[cfg(target_os = "linux")]
    {
        let session = std::env::var("XDG_SESSION_TYPE").unwrap_or_default();
        let xdotool_available = std::process::Command::new("which")
            .arg("xdotool")
            .output()
            .map(|o| o.status.success())
            .unwrap_or(false);

        if (session == "x11" || session.is_empty()) && !xdotool_available {
            return Err("xdotool_missing".to_string());
        }
        if let Some(reason) = linux_keyboard_unavailable_reason(&session, xdotool_available) {
            return Err(reason.to_string());
        }
    }
    let _ = (); // suppress unused warning on non-Linux
    Ok(())
}

#[cfg_attr(not(target_os = "linux"), allow(dead_code))]
fn linux_keyboard_unavailable_reason(
    session: &str,
    xdotool_available: bool,
) -> Option<&'static str> {
    if session.eq_ignore_ascii_case("wayland") {
        return Some("wayland_unsupported");
    }
    if (session.eq_ignore_ascii_case("x11") || session.is_empty()) && !xdotool_available {
        return Some("xdotool_missing");
    }
    None
}

pub struct KeyboardOutput {
    #[cfg_attr(not(target_os = "macos"), allow(dead_code))]
    app_handle: Option<tauri::AppHandle>,
}

impl Default for KeyboardOutput {
    fn default() -> Self {
        Self::new()
    }
}

impl KeyboardOutput {
    pub fn new() -> Self {
        Self { app_handle: None }
    }

    pub fn with_app_handle(app_handle: tauri::AppHandle) -> Self {
        Self {
            app_handle: Some(app_handle),
        }
    }
}

#[async_trait]
impl TextOutput for KeyboardOutput {
    async fn type_text(&self, text: &str) -> Result<(), AppError> {
        let text = text.to_string();

        #[cfg(target_os = "macos")]
        {
            let app_handle = self.app_handle.clone().ok_or_else(|| {
                AppError::Output("Keyboard output needs AppHandle on macOS".to_string())
            })?;
            let (tx, rx) = tokio::sync::oneshot::channel();
            app_handle
                .run_on_main_thread(move || {
                    let _ = tx.send(type_text_sync(&text));
                })
                .map_err(|e| AppError::Output(format!("Main thread dispatch failed: {}", e)))?;
            rx.await
                .map_err(|e| AppError::Output(format!("Main thread task dropped: {}", e)))?
        }

        #[cfg(not(target_os = "macos"))]
        {
            tokio::task::spawn_blocking(move || type_text_sync(&text))
                .await
                .map_err(|e| AppError::Output(format!("Spawn blocking error: {}", e)))?
        }
    }

    fn mode(&self) -> OutputMode {
        OutputMode::Keyboard
    }
}

fn type_text_sync(text: &str) -> Result<(), AppError> {
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

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn linux_keyboard_check_rejects_wayland() {
        assert_eq!(
            linux_keyboard_unavailable_reason("wayland", true),
            Some("wayland_unsupported")
        );
        assert_eq!(
            linux_keyboard_unavailable_reason("Wayland", true),
            Some("wayland_unsupported")
        );
    }

    #[test]
    fn linux_keyboard_check_rejects_x11_without_xdotool() {
        assert_eq!(
            linux_keyboard_unavailable_reason("x11", false),
            Some("xdotool_missing")
        );
        assert_eq!(
            linux_keyboard_unavailable_reason("", false),
            Some("xdotool_missing")
        );
        assert_eq!(linux_keyboard_unavailable_reason("x11", true), None);
    }
}
