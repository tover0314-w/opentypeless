use async_trait::async_trait;
use std::sync::atomic::{AtomicU64, Ordering};
use std::sync::Mutex;

use crate::error::AppError;

#[cfg(test)]
use super::InsertStatus;
use super::{InsertResult, InsertionStrategy, OutputMode, TextOutput};

/// Delay after writing to clipboard before simulating paste.
const CLIPBOARD_SETTLE_MS: u64 = 20;
const CLIPBOARD_RESTORE_DELAY_MS: u64 = 750;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum PasteShortcut {
    CtrlV,
    CtrlShiftV,
    ShiftInsert,
}

impl PasteShortcut {
    pub fn from_config_value(value: &str) -> Self {
        match value {
            "ctrlShiftV" => Self::CtrlShiftV,
            "shiftInsert" => Self::ShiftInsert,
            _ => Self::CtrlV,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct ClipboardOutputOptions {
    pub restore_after_paste: bool,
    pub paste_shortcut: PasteShortcut,
    pub auto_paste: bool,
}

impl Default for ClipboardOutputOptions {
    fn default() -> Self {
        Self {
            restore_after_paste: true,
            paste_shortcut: PasteShortcut::CtrlV,
            auto_paste: true,
        }
    }
}

pub struct ClipboardOutput {
    options: ClipboardOutputOptions,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum ClipboardRestoreScope {
    TextOnly,
}

pub fn clipboard_restore_scope() -> ClipboardRestoreScope {
    ClipboardRestoreScope::TextOnly
}

impl Default for ClipboardOutput {
    fn default() -> Self {
        Self::new()
    }
}

impl ClipboardOutput {
    pub fn new() -> Self {
        Self::with_options(ClipboardOutputOptions::default())
    }

    pub fn with_options(options: ClipboardOutputOptions) -> Self {
        Self { options }
    }
}

#[cfg(any(target_os = "linux", test))]
fn should_auto_paste_after_clipboard(session_type: &str) -> bool {
    !session_type.eq_ignore_ascii_case("wayland")
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum ClipboardRestoreDecision {
    Restore(String),
    KeepOutput,
}

fn clipboard_restore_decision(
    restore_enabled: bool,
    paste_succeeded: bool,
    previous_text: Option<&str>,
    current_text: Option<&str>,
    inserted_text: &str,
) -> ClipboardRestoreDecision {
    if restore_enabled && paste_succeeded && current_text == Some(inserted_text) {
        if let Some(previous_text) = previous_text {
            return ClipboardRestoreDecision::Restore(previous_text.to_string());
        }
    }

    ClipboardRestoreDecision::KeepOutput
}

fn clipboard_insert_result(paste_succeeded: bool, inserted_text: &str) -> InsertResult {
    if paste_succeeded {
        InsertResult::inserted(
            InsertionStrategy::ClipboardPaste,
            inserted_text.chars().count(),
        )
    } else {
        InsertResult::copied_fallback(
            InsertionStrategy::ClipboardPaste,
            inserted_text.chars().count(),
        )
    }
}

#[derive(Debug)]
struct PendingClipboardRestore {
    latest_restore_id: u64,
    original_text: Option<String>,
}

static NEXT_CLIPBOARD_RESTORE_ID: AtomicU64 = AtomicU64::new(1);
static PENDING_CLIPBOARD_RESTORE: Mutex<Option<PendingClipboardRestore>> = Mutex::new(None);

fn schedule_clipboard_restore(inserted_text: String, previous_text: Option<String>) {
    let (restore_id, original_text) = remember_pending_clipboard_restore(previous_text);
    std::thread::spawn(move || {
        std::thread::sleep(std::time::Duration::from_millis(CLIPBOARD_RESTORE_DELAY_MS));
        restore_clipboard_if_safe(inserted_text, original_text, restore_id);
    });
}

fn remember_pending_clipboard_restore(previous_text: Option<String>) -> (u64, Option<String>) {
    let restore_id = NEXT_CLIPBOARD_RESTORE_ID.fetch_add(1, Ordering::SeqCst);
    let original_text = {
        let mut pending = PENDING_CLIPBOARD_RESTORE
            .lock()
            .unwrap_or_else(|e| e.into_inner());
        let original = pending
            .as_ref()
            .map(|batch| batch.original_text.clone())
            .unwrap_or(previous_text);
        *pending = Some(PendingClipboardRestore {
            latest_restore_id: restore_id,
            original_text: original.clone(),
        });
        original
    };
    (restore_id, original_text)
}

fn is_latest_clipboard_restore(restore_id: u64) -> bool {
    matches!(
        PENDING_CLIPBOARD_RESTORE
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .as_ref(),
        Some(batch) if batch.latest_restore_id == restore_id
    )
}

fn clear_pending_clipboard_restore(restore_id: u64) {
    let mut pending = PENDING_CLIPBOARD_RESTORE
        .lock()
        .unwrap_or_else(|e| e.into_inner());
    if matches!(pending.as_ref(), Some(batch) if batch.latest_restore_id == restore_id) {
        pending.take();
    }
}

fn restore_clipboard_if_safe(
    inserted_text: String,
    original_text: Option<String>,
    restore_id: u64,
) {
    if !is_latest_clipboard_restore(restore_id) {
        return;
    }

    let mut clipboard = match arboard::Clipboard::new() {
        Ok(clipboard) => clipboard,
        Err(error) => {
            tracing::warn!("Failed to reopen clipboard for restore: {error}");
            clear_pending_clipboard_restore(restore_id);
            return;
        }
    };
    let current_text = clipboard.get_text().ok();

    if let ClipboardRestoreDecision::Restore(value) = clipboard_restore_decision(
        true,
        true,
        original_text.as_deref(),
        current_text.as_deref(),
        &inserted_text,
    ) {
        if let Err(error) = clipboard.set_text(value) {
            tracing::warn!("Failed to restore previous clipboard text: {error}");
        }
    }

    clear_pending_clipboard_restore(restore_id);
}

#[cfg(target_os = "macos")]
fn simulate_paste(_shortcut: PasteShortcut) -> Result<(), AppError> {
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
    Ok(())
}

#[cfg(not(target_os = "macos"))]
fn paste_keys(shortcut: PasteShortcut) -> (Vec<enigo::Key>, enigo::Key) {
    use enigo::Key;
    match shortcut {
        PasteShortcut::CtrlV => (vec![Key::Control], Key::Unicode('v')),
        PasteShortcut::CtrlShiftV => (vec![Key::Control, Key::Shift], Key::Unicode('v')),
        PasteShortcut::ShiftInsert => (vec![Key::Shift], Key::Insert),
    }
}

#[cfg(not(target_os = "macos"))]
fn simulate_paste(shortcut: PasteShortcut) -> Result<(), AppError> {
    super::windows_modifier_guard::wait_for_modifier_release()?;

    use enigo::{Direction, Enigo, Keyboard, Settings};
    let (modifiers, primary) = paste_keys(shortcut);
    let mut enigo = Enigo::new(&Settings::default())
        .map_err(|e| AppError::Output(format!("Failed to create Enigo: {:?}", e)))?;

    let mut pressed = 0usize;
    let mut first_error: Option<String> = None;

    for modifier in &modifiers {
        if let Err(error) = enigo.key(*modifier, Direction::Press) {
            first_error = Some(format!("Key press error: {:?}", error));
            break;
        }
        pressed += 1;
    }

    if first_error.is_none() {
        if let Err(error) = enigo.key(primary, Direction::Click) {
            first_error = Some(format!("Key click error: {:?}", error));
        }
    }

    for modifier in modifiers[..pressed].iter().rev() {
        if let Err(error) = enigo.key(*modifier, Direction::Release) {
            if first_error.is_none() {
                first_error = Some(format!("Key release error: {:?}", error));
            }
        }
    }

    match first_error {
        Some(error) => Err(AppError::Output(error)),
        None => Ok(()),
    }
}

#[async_trait]
impl TextOutput for ClipboardOutput {
    async fn type_text(&self, text: &str) -> Result<InsertResult, AppError> {
        let text = text.to_string();
        let options = self.options;
        tokio::task::spawn_blocking(move || -> Result<InsertResult, AppError> {
            let mut clipboard = arboard::Clipboard::new()
                .map_err(|e| AppError::Output(format!("Failed to access clipboard: {}", e)))?;
            let previous_text = clipboard.get_text().ok();

            clipboard
                .set_text(&text)
                .map_err(|e| AppError::Output(format!("Failed to set clipboard: {}", e)))?;

            if !options.auto_paste {
                return Ok(InsertResult::copied_fallback(
                    InsertionStrategy::ClipboardCopyOnly,
                    text.chars().count(),
                ));
            }

            std::thread::sleep(std::time::Duration::from_millis(CLIPBOARD_SETTLE_MS));

            #[cfg(target_os = "linux")]
            if !should_auto_paste_after_clipboard(&crate::platform::current_session_type()) {
                return Ok(InsertResult::copied_fallback(
                    InsertionStrategy::ClipboardCopyOnly,
                    text.chars().count(),
                ));
            }

            if let Err(error) = simulate_paste(options.paste_shortcut) {
                tracing::warn!("Clipboard paste failed; leaving output text on clipboard: {error}");
                return Ok(clipboard_insert_result(false, &text));
            }

            if options.restore_after_paste {
                schedule_clipboard_restore(text.clone(), previous_text);
            }

            Ok(clipboard_insert_result(true, &text))
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

    #[test]
    fn clipboard_restore_decision_restores_only_after_successful_unchanged_paste() {
        assert_eq!(
            clipboard_restore_decision(true, true, Some("before"), Some("inserted"), "inserted"),
            ClipboardRestoreDecision::Restore("before".to_string())
        );
    }

    #[test]
    fn clipboard_restore_decision_keeps_output_when_paste_failed() {
        assert_eq!(
            clipboard_restore_decision(true, false, Some("before"), Some("inserted"), "inserted"),
            ClipboardRestoreDecision::KeepOutput
        );
    }

    #[test]
    fn clipboard_restore_decision_keeps_user_changed_clipboard() {
        assert_eq!(
            clipboard_restore_decision(
                true,
                true,
                Some("before"),
                Some("user-copied-something-else"),
                "inserted",
            ),
            ClipboardRestoreDecision::KeepOutput
        );
    }

    #[test]
    fn clipboard_restore_decision_respects_disabled_restore() {
        assert_eq!(
            clipboard_restore_decision(false, true, Some("before"), Some("inserted"), "inserted"),
            ClipboardRestoreDecision::KeepOutput
        );
    }

    #[test]
    fn paste_shortcut_defaults_unknown_values_to_ctrl_v() {
        assert_eq!(PasteShortcut::from_config_value(""), PasteShortcut::CtrlV);
        assert_eq!(
            PasteShortcut::from_config_value("something-else"),
            PasteShortcut::CtrlV
        );
    }

    #[test]
    fn paste_shortcut_parses_terminal_friendly_shortcuts() {
        assert_eq!(
            PasteShortcut::from_config_value("ctrlShiftV"),
            PasteShortcut::CtrlShiftV
        );
        assert_eq!(
            PasteShortcut::from_config_value("shiftInsert"),
            PasteShortcut::ShiftInsert
        );
    }

    #[test]
    fn clipboard_result_marks_paste_failure_as_copied_fallback() {
        let result = clipboard_insert_result(false, "typed text");

        assert_eq!(result.status, InsertStatus::CopiedFallback);
        assert_eq!(result.strategy_used, InsertionStrategy::ClipboardPaste);
        assert_eq!(result.chars_inserted, 0);
        assert_eq!(result.chars_copied, 10);
    }

    #[test]
    fn clipboard_restore_scope_is_text_only() {
        assert_eq!(clipboard_restore_scope(), ClipboardRestoreScope::TextOnly);
    }
}
