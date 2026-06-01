pub mod clipboard;
pub mod keyboard;

use crate::error::{AppError, UserError};
use async_trait::async_trait;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum OutputMode {
    Keyboard,
    Clipboard,
}

#[async_trait]
pub trait TextOutput: Send + Sync {
    async fn type_text(&self, text: &str) -> Result<(), AppError>;
    fn mode(&self) -> OutputMode;
}

pub fn create_output(mode: OutputMode) -> Box<dyn TextOutput> {
    create_output_with_app_handle(mode, None)
}

pub fn create_output_with_app_handle(
    mode: OutputMode,
    app_handle: Option<&tauri::AppHandle>,
) -> Box<dyn TextOutput> {
    match mode {
        OutputMode::Keyboard => match app_handle {
            Some(handle) => Box::new(keyboard::KeyboardOutput::with_app_handle(handle.clone())),
            None => Box::new(keyboard::KeyboardOutput::new()),
        },
        OutputMode::Clipboard => Box::new(clipboard::ClipboardOutput::new()),
    }
}

/// Try keyboard output first. On failure, fall back to clipboard.
/// Returns Ok(Some(UserError)) if fell back to clipboard (warning for frontend).
/// Returns Ok(None) if primary output succeeded.
/// Returns Err if both keyboard and clipboard failed.
pub async fn output_with_fallback(
    app_handle: &tauri::AppHandle,
    text: &str,
    mode: OutputMode,
) -> Result<Option<UserError>, String> {
    if mode == OutputMode::Clipboard {
        let output = create_output_with_app_handle(OutputMode::Clipboard, Some(app_handle));
        return output
            .type_text(text)
            .await
            .map_err(|e| e.to_string())
            .map(|_| None);
    }

    // Try keyboard first
    let keyboard = create_output_with_app_handle(OutputMode::Keyboard, Some(app_handle));
    match keyboard.type_text(text).await {
        Ok(()) => Ok(None),
        Err(kb_err) => {
            tracing::warn!(
                "Keyboard output failed: {}, falling back to clipboard",
                kb_err
            );
            let clipboard = create_output_with_app_handle(OutputMode::Clipboard, Some(app_handle));
            match clipboard.type_text(text).await {
                Ok(()) => Ok(Some(UserError {
                    code: "output_fallback_clipboard".to_string(),
                    details: Some(kb_err.to_string()),
                    retry_count: 0,
                })),
                Err(cb_err) => Err(format!(
                    "Both keyboard ({}) and clipboard ({}) output failed",
                    kb_err, cb_err
                )),
            }
        }
    }
}
