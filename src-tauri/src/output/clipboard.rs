use async_trait::async_trait;

use crate::error::AppError;

use super::{OutputMode, TextOutput};

/// Delay after writing to clipboard before simulating paste.
const CLIPBOARD_SETTLE_MS: u64 = 20;

pub struct ClipboardOutput;

impl Default for ClipboardOutput {
    fn default() -> Self {
        Self::new()
    }
}

impl ClipboardOutput {
    pub fn new() -> Self {
        Self
    }
}

#[cfg(any(target_os = "linux", test))]
fn should_auto_paste_after_clipboard(session_type: &str) -> bool {
    !session_type.eq_ignore_ascii_case("wayland")
}

#[async_trait]
impl TextOutput for ClipboardOutput {
    async fn type_text(&self, text: &str) -> Result<(), AppError> {
        let text = text.to_string();
        tokio::task::spawn_blocking(move || -> Result<(), AppError> {
            let mut clipboard = arboard::Clipboard::new()
                .map_err(|e| AppError::Output(format!("Failed to access clipboard: {}", e)))?;

            clipboard
                .set_text(&text)
                .map_err(|e| AppError::Output(format!("Failed to set clipboard: {}", e)))?;

            std::thread::sleep(std::time::Duration::from_millis(CLIPBOARD_SETTLE_MS));

            #[cfg(target_os = "linux")]
            if !should_auto_paste_after_clipboard(&crate::platform::current_session_type()) {
                return Ok(());
            }

            // On macOS: trigger Cmd+V via osascript (AppleScript).
            // This avoids the Accessibility permission requirement that enigo's
            // CGEventPost needs. The apple-events entitlement is already declared.
            // On Windows/Linux: use enigo's SendInput which needs no special permissions.
            #[cfg(target_os = "macos")]
            {
                let status = std::process::Command::new("osascript")
                    .args([
                        "-e",
                        r#"tell application "System Events" to keystroke "v" using command down"#,
                    ])
                    .status();

                match status {
                    Ok(status) if status.success() => {}
                    Ok(status) => {
                        tracing::warn!(
                            "Auto-paste failed with exit code {:?}; text remains copied",
                            status.code()
                        );
                    }
                    Err(e) => {
                        tracing::warn!("Auto-paste failed: {}; text remains copied", e);
                    }
                }
            }

            #[cfg(not(target_os = "macos"))]
            {
                use enigo::{Direction, Enigo, Key, Keyboard, Settings};
                let mut enigo = Enigo::new(&Settings::default())
                    .map_err(|e| AppError::Output(format!("Failed to create Enigo: {:?}", e)))?;

                enigo
                    .key(Key::Control, Direction::Press)
                    .map_err(|e| AppError::Output(format!("Key press error: {:?}", e)))?;
                enigo
                    .key(Key::Unicode('v'), Direction::Click)
                    .map_err(|e| AppError::Output(format!("Key click error: {:?}", e)))?;
                enigo
                    .key(Key::Control, Direction::Release)
                    .map_err(|e| AppError::Output(format!("Key release error: {:?}", e)))?;
            }

            Ok(())
        })
        .await
        .map_err(|e| AppError::Output(format!("Spawn blocking error: {}", e)))?
    }

    fn mode(&self) -> OutputMode {
        OutputMode::Clipboard
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn wayland_clipboard_output_is_copy_only() {
        assert!(!should_auto_paste_after_clipboard("wayland"));
        assert!(!should_auto_paste_after_clipboard("WAYLAND"));
    }

    #[test]
    fn x11_clipboard_output_keeps_auto_paste() {
        assert!(should_auto_paste_after_clipboard("x11"));
        assert!(should_auto_paste_after_clipboard("unknown"));
    }
}
