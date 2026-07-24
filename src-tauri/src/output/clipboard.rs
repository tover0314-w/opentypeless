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
                    .status()
                    .map_err(|e| AppError::Output(format!("osascript error: {}", e)))?;
                if !status.success() {
                    return Err(AppError::Output(format!(
                        "osascript paste failed with exit code: {:?}",
                        status.code()
                    )));
                }
            }

            #[cfg(not(target_os = "macos"))]
            {
                // On Wayland, simulating Ctrl+V goes through the RemoteDesktop
                // portal, which pops a "Remote Desktop / Allow Remote
                // Interaction" permission dialog on every paste (a fresh input
                // session each time). The compositor forbids seamless input
                // injection anyway, so skip the auto-paste and leave the text
                // on the clipboard for a manual Ctrl+V — mirroring how keyboard
                // output is disabled on Wayland (see output/keyboard.rs).
                #[cfg(target_os = "linux")]
                let skip_paste = std::env::var("XDG_SESSION_TYPE")
                    .map(|s| s.eq_ignore_ascii_case("wayland"))
                    .unwrap_or(false);
                #[cfg(not(target_os = "linux"))]
                let skip_paste = false;

                if skip_paste {
                    tracing::info!(
                        "Wayland: skipping simulated paste; transcript left on clipboard"
                    );
                } else {
                    use enigo::{Direction, Enigo, Key, Keyboard, Settings};
                    let mut enigo = Enigo::new(&Settings::default()).map_err(|e| {
                        AppError::Output(format!("Failed to create Enigo: {:?}", e))
                    })?;

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
