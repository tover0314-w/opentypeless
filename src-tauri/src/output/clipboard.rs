use anyhow::Result;
use async_trait::async_trait;

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
    async fn type_text(&self, text: &str) -> Result<()> {
        let text = text.to_string();
        tokio::task::spawn_blocking(move || {
            let mut clipboard = arboard::Clipboard::new()
                .map_err(|e| anyhow::anyhow!("Failed to access clipboard: {}", e))?;

            clipboard
                .set_text(&text)
                .map_err(|e| anyhow::anyhow!("Failed to set clipboard: {}", e))?;

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
                    .status()?;
                if !status.success() {
                    anyhow::bail!("osascript paste failed with exit code: {:?}", status.code());
                }
            }

            #[cfg(not(target_os = "macos"))]
            {
                use enigo::{Direction, Enigo, Key, Keyboard, Settings};
                let mut enigo = Enigo::new(&Settings::default())
                    .map_err(|e| anyhow::anyhow!("Failed to create Enigo: {:?}", e))?;

                enigo
                    .key(Key::Control, Direction::Press)
                    .map_err(|e| anyhow::anyhow!("Key press error: {:?}", e))?;
                enigo
                    .key(Key::Unicode('v'), Direction::Click)
                    .map_err(|e| anyhow::anyhow!("Key click error: {:?}", e))?;
                enigo
                    .key(Key::Control, Direction::Release)
                    .map_err(|e| anyhow::anyhow!("Key release error: {:?}", e))?;
            }

            Ok(())
        })
        .await?
    }

    fn mode(&self) -> OutputMode {
        OutputMode::Clipboard
    }
}
