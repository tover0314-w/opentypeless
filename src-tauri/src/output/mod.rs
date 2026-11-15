pub mod clipboard;
#[cfg(target_os = "macos")]
pub(crate) mod event_identity;
mod guarded;
pub mod keyboard;
mod keyboard_progress;
#[cfg(target_os = "macos")]
mod scheduled;
pub mod windows_modifier_guard;
pub mod windows_sendinput;

use crate::error::{AppError, UserError};
use async_trait::async_trait;
use serde::Serialize;

#[derive(Debug, Clone, Copy, PartialEq)]
pub enum OutputMode {
    Keyboard,
    Clipboard,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum InsertionStrategy {
    Auto,
    Keyboard,
    ClipboardPaste,
    ClipboardCopyOnly,
    WindowsSendInput,
}

impl InsertionStrategy {
    pub fn from_config_value(value: &str) -> Self {
        match value {
            "keyboard" => Self::Keyboard,
            "clipboardPaste" => Self::ClipboardPaste,
            "clipboardCopyOnly" => Self::ClipboardCopyOnly,
            "windowsSendInput" => Self::WindowsSendInput,
            _ => Self::Auto,
        }
    }

    pub fn direct_streaming_strategy(self) -> Option<Self> {
        match self {
            Self::Auto | Self::Keyboard => Some(Self::Keyboard),
            Self::WindowsSendInput => Some(Self::WindowsSendInput),
            Self::ClipboardPaste | Self::ClipboardCopyOnly => None,
        }
    }

    pub fn needs_keyboard_access(self) -> bool {
        matches!(self, Self::Auto | Self::Keyboard | Self::WindowsSendInput)
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum InsertStatus {
    Inserted,
    CopiedFallback,
    Failed,
    PartiallyInserted,
}

#[derive(Debug, Clone, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct InsertResult {
    pub status: InsertStatus,
    pub strategy_used: InsertionStrategy,
    pub chars_inserted: usize,
    pub chars_copied: usize,
    pub warning_code: Option<String>,
    pub message: Option<String>,
}

impl InsertResult {
    pub fn inserted(strategy_used: InsertionStrategy, chars_inserted: usize) -> Self {
        Self {
            status: InsertStatus::Inserted,
            strategy_used,
            chars_inserted,
            chars_copied: 0,
            warning_code: None,
            message: None,
        }
    }

    pub fn copied_fallback(strategy_used: InsertionStrategy, chars_copied: usize) -> Self {
        Self {
            status: InsertStatus::CopiedFallback,
            strategy_used,
            chars_inserted: 0,
            chars_copied,
            warning_code: None,
            message: None,
        }
    }

    #[allow(dead_code)]
    pub fn failed(strategy_used: InsertionStrategy) -> Self {
        Self {
            status: InsertStatus::Failed,
            strategy_used,
            chars_inserted: 0,
            chars_copied: 0,
            warning_code: None,
            message: None,
        }
    }

    #[allow(dead_code)]
    pub fn partially_inserted(strategy_used: InsertionStrategy, chars_inserted: usize) -> Self {
        Self {
            status: InsertStatus::PartiallyInserted,
            strategy_used,
            chars_inserted,
            chars_copied: 0,
            warning_code: None,
            message: None,
        }
    }

    pub fn with_warning(mut self, warning: &UserError) -> Self {
        self.warning_code = Some(warning.code.clone());
        self.message = warning.details.clone();
        self
    }
}

#[derive(Debug, Clone)]
pub struct OutputOutcome {
    pub insert_result: InsertResult,
    pub warning: Option<UserError>,
}

#[async_trait]
pub trait TextOutput: Send + Sync {
    async fn type_text(&self, text: &str) -> Result<InsertResult, AppError>;
    fn mode(&self) -> OutputMode;
}

pub fn create_output(mode: OutputMode, app_handle: &tauri::AppHandle) -> Box<dyn TextOutput> {
    create_output_with_clipboard_options(
        mode,
        app_handle,
        clipboard::ClipboardOutputOptions::default(),
    )
}

pub fn create_output_with_clipboard_options(
    mode: OutputMode,
    app_handle: &tauri::AppHandle,
    clipboard_options: clipboard::ClipboardOutputOptions,
) -> Box<dyn TextOutput> {
    match mode {
        OutputMode::Keyboard => Box::new(keyboard::KeyboardOutput::new(app_handle)),
        OutputMode::Clipboard => {
            Box::new(clipboard::ClipboardOutput::with_options(clipboard_options))
        }
    }
}

fn clipboard_warning_for_platform() -> Option<UserError> {
    if crate::platform::is_wayland_session() {
        Some(UserError {
            code: "output_wayland_clipboard_copy_only".to_string(),
            details: None,
            retry_count: 0,
        })
    } else {
        None
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
    clipboard_options: clipboard::ClipboardOutputOptions,
) -> Result<OutputOutcome, String> {
    let strategy = match mode {
        OutputMode::Keyboard => InsertionStrategy::Auto,
        OutputMode::Clipboard => InsertionStrategy::ClipboardPaste,
    };
    output_with_strategy(
        app_handle,
        text,
        strategy,
        clipboard_options,
        windows_sendinput::WindowsSendInputOptions::default(),
    )
    .await
}

pub async fn output_with_strategy(
    app_handle: &tauri::AppHandle,
    text: &str,
    strategy: InsertionStrategy,
    clipboard_options: clipboard::ClipboardOutputOptions,
    windows_sendinput_options: windows_sendinput::WindowsSendInputOptions,
) -> Result<OutputOutcome, String> {
    output_with_guard(
        app_handle,
        text,
        strategy,
        clipboard_options,
        windows_sendinput_options,
        OutputGuards {
            insert: std::sync::Arc::new(|| true),
            copy: std::sync::Arc::new(|| true),
        },
    )
    .await
}

pub struct OutputGuards {
    pub insert: std::sync::Arc<dyn Fn() -> bool + Send + Sync>,
    pub copy: std::sync::Arc<dyn Fn() -> bool + Send + Sync>,
}

pub async fn output_with_guard(
    app_handle: &tauri::AppHandle,
    text: &str,
    strategy: InsertionStrategy,
    clipboard_options: clipboard::ClipboardOutputOptions,
    windows_sendinput_options: windows_sendinput::WindowsSendInputOptions,
    guards: OutputGuards,
) -> Result<OutputOutcome, String> {
    let keyboard = keyboard::KeyboardOutput::with_guard(app_handle, guards.insert.clone());
    let windows_sendinput =
        windows_sendinput::WindowsSendInputOutput::new(windows_sendinput_options);
    let clipboard_paste =
        create_output_with_clipboard_options(OutputMode::Clipboard, app_handle, clipboard_options);
    let clipboard_copy = create_output_with_clipboard_options(
        OutputMode::Clipboard,
        app_handle,
        clipboard::ClipboardOutputOptions {
            auto_paste: false,
            ..clipboard_options
        },
    );

    output_with_strategy_using(
        text,
        strategy,
        &guarded::GuardedOutput::new(Box::new(keyboard), guards.insert.clone()),
        &guarded::GuardedOutput::new(Box::new(windows_sendinput), guards.insert.clone()),
        &guarded::GuardedOutput::new(clipboard_paste, guards.insert),
        &guarded::GuardedOutput::new(clipboard_copy, guards.copy),
        clipboard_warning_for_platform(),
    )
    .await
}

pub async fn output_stream_chunk(
    app_handle: &tauri::AppHandle,
    text: &str,
    strategy: InsertionStrategy,
    windows_sendinput_options: windows_sendinput::WindowsSendInputOptions,
) -> Result<InsertResult, String> {
    output_stream_chunk_with_guard(
        app_handle,
        text,
        strategy,
        windows_sendinput_options,
        std::sync::Arc::new(|| true),
    )
    .await
}

pub async fn output_stream_chunk_with_guard(
    app_handle: &tauri::AppHandle,
    text: &str,
    strategy: InsertionStrategy,
    windows_sendinput_options: windows_sendinput::WindowsSendInputOptions,
    guard: std::sync::Arc<dyn Fn() -> bool + Send + Sync>,
) -> Result<InsertResult, String> {
    match strategy.direct_streaming_strategy() {
        Some(InsertionStrategy::Keyboard) => guarded::GuardedOutput::new(
            Box::new(keyboard::KeyboardOutput::with_guard(
                app_handle,
                guard.clone(),
            )),
            guard,
        )
        .type_text(text)
        .await
        .map_err(|e| e.to_string()),
        Some(InsertionStrategy::WindowsSendInput) => guarded::GuardedOutput::new(
            Box::new(windows_sendinput::WindowsSendInputOutput::new(
                windows_sendinput_options,
            )),
            guard,
        )
        .type_text(text)
        .await
        .map_err(|e| e.to_string()),
        _ => Err(format!(
            "Insertion strategy {:?} does not support streaming chunks",
            strategy
        )),
    }
}

#[cfg(test)]
async fn output_with_fallback_using(
    text: &str,
    mode: OutputMode,
    keyboard: &dyn TextOutput,
    clipboard: &dyn TextOutput,
    clipboard_warning: Option<UserError>,
) -> Result<OutputOutcome, String> {
    let strategy = match mode {
        OutputMode::Keyboard => InsertionStrategy::Auto,
        OutputMode::Clipboard => InsertionStrategy::ClipboardPaste,
    };
    output_with_strategy_using(
        text,
        strategy,
        keyboard,
        keyboard,
        clipboard,
        clipboard,
        clipboard_warning,
    )
    .await
}

async fn output_with_strategy_using(
    text: &str,
    strategy: InsertionStrategy,
    keyboard: &dyn TextOutput,
    windows_sendinput: &dyn TextOutput,
    clipboard_paste: &dyn TextOutput,
    clipboard_copy: &dyn TextOutput,
    clipboard_warning: Option<UserError>,
) -> Result<OutputOutcome, String> {
    if strategy == InsertionStrategy::ClipboardCopyOnly {
        return clipboard_copy
            .type_text(text)
            .await
            .map_err(|e| e.to_string())
            .map(|insert_result| OutputOutcome {
                insert_result,
                warning: None,
            });
    }

    if strategy == InsertionStrategy::WindowsSendInput {
        return match windows_sendinput.type_text(text).await {
            Ok(insert_result) if insert_result.status == InsertStatus::Inserted => {
                Ok(OutputOutcome {
                    insert_result,
                    warning: None,
                })
            }
            Ok(insert_result) if insert_result.status == InsertStatus::PartiallyInserted => {
                let expected_chars = windows_sendinput::expected_sendinput_typed_chars(text);
                tracing::warn!(
                    "Windows SendInput inserted {}/{} chars, copying full text to clipboard",
                    insert_result.chars_inserted,
                    expected_chars
                );
                match clipboard_copy.type_text(text).await {
                    Ok(copy_result) => {
                        let warning = UserError {
                            code: "output_fallback_clipboard".to_string(),
                            details: Some(format!(
                                "Windows SendInput inserted {}/{} chars; copied full text to clipboard",
                                insert_result.chars_inserted,
                                expected_chars
                            )),
                            retry_count: 0,
                        };
                        Ok(OutputOutcome {
                            insert_result: insert_result_with_optional_warning(
                                copy_result,
                                Some(&warning),
                            ),
                            warning: Some(warning),
                        })
                    }
                    Err(cb_err) => Err(format!(
                        "Windows SendInput partially inserted {} chars and clipboard copy failed ({})",
                        insert_result.chars_inserted, cb_err
                    )),
                }
            }
            Ok(insert_result) => Ok(OutputOutcome {
                insert_result,
                warning: None,
            }),
            Err(sendinput_err) => {
                tracing::warn!(
                    "Windows SendInput output failed: {}, falling back to clipboard",
                    sendinput_err
                );
                match clipboard_paste.type_text(text).await {
                    Ok(insert_result) => {
                        let warning = clipboard_warning.or(Some(UserError {
                            code: "output_fallback_clipboard".to_string(),
                            details: Some(sendinput_err.to_string()),
                            retry_count: 0,
                        }));
                        Ok(OutputOutcome {
                            insert_result: insert_result_with_optional_warning(
                                insert_result,
                                warning.as_ref(),
                            ),
                            warning,
                        })
                    }
                    Err(cb_err) => Err(format!(
                        "Both Windows SendInput ({}) and clipboard ({}) output failed",
                        sendinput_err, cb_err
                    )),
                }
            }
        };
    }

    if strategy == InsertionStrategy::ClipboardPaste {
        return clipboard_paste
            .type_text(text)
            .await
            .map_err(|e| e.to_string())
            .map(|insert_result| OutputOutcome {
                insert_result: insert_result_with_optional_warning(
                    insert_result,
                    clipboard_warning.as_ref(),
                ),
                warning: clipboard_warning,
            });
    }

    // Try keyboard first
    match keyboard.type_text(text).await {
        Ok(mut partial) if partial.status == InsertStatus::PartiallyInserted => {
            let recovery_message = match clipboard_copy.type_text(text).await {
                Ok(copied) => {
                    partial.chars_copied = copied.chars_copied;
                    "copied full text without pasting again"
                }
                Err(_) => "full text could not be copied",
            };
            let warning = UserError {
                code: "output_fallback_clipboard".into(),
                details: Some(format!(
                    "Keyboard output completed {} characters before stopping; {}.",
                    partial.chars_inserted, recovery_message
                )),
                retry_count: 0,
            };
            Ok(OutputOutcome {
                insert_result: partial.with_warning(&warning),
                warning: Some(warning),
            })
        }
        Ok(insert_result) => Ok(OutputOutcome {
            insert_result,
            warning: None,
        }),
        Err(AppError::OutputUnconfirmed(message)) => {
            let copied = clipboard_copy
                .type_text(text)
                .await
                .map_err(|error| error.to_string())?;
            let warning = UserError {
                code: "output_fallback_clipboard".into(),
                details: Some(format!(
                    "Keyboard output is unconfirmed; copied full text without pasting again. {message}"
                )),
                retry_count: 0,
            };
            Ok(OutputOutcome {
                insert_result: copied.with_warning(&warning),
                warning: Some(warning),
            })
        }
        Err(kb_err) => {
            tracing::warn!(
                "Keyboard output failed: {}, falling back to clipboard",
                kb_err
            );
            match clipboard_paste.type_text(text).await {
                Ok(insert_result) => {
                    let warning = clipboard_warning.or(Some(UserError {
                        code: "output_fallback_clipboard".to_string(),
                        details: Some(kb_err.to_string()),
                        retry_count: 0,
                    }));
                    Ok(OutputOutcome {
                        insert_result: insert_result_with_optional_warning(
                            insert_result,
                            warning.as_ref(),
                        ),
                        warning,
                    })
                }
                Err(cb_err) => Err(format!(
                    "Both keyboard ({}) and clipboard ({}) output failed",
                    kb_err, cb_err
                )),
            }
        }
    }
}

fn insert_result_with_optional_warning(
    insert_result: InsertResult,
    warning: Option<&UserError>,
) -> InsertResult {
    match warning {
        Some(warning) => insert_result.with_warning(warning),
        None => insert_result,
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use async_trait::async_trait;

    #[tokio::test]
    async fn lost_insert_permission_uses_only_the_still_owned_recovery_copy() {
        let keyboard = guarded::GuardedOutput::new(
            Box::new(FakeOutput::err(OutputMode::Keyboard, "must not type")),
            std::sync::Arc::new(|| false),
        );
        let no_paste = FakeOutput::err(OutputMode::Clipboard, "must not paste");
        let copy = guarded::GuardedOutput::new(
            Box::new(FakeOutput::ok(
                OutputMode::Clipboard,
                InsertResult::copied_fallback(InsertionStrategy::ClipboardCopyOnly, 4),
            )),
            std::sync::Arc::new(|| true),
        );
        let outcome = output_with_strategy_using(
            "text",
            InsertionStrategy::Keyboard,
            &keyboard,
            &no_paste,
            &no_paste,
            &copy,
            None,
        )
        .await
        .unwrap();
        assert_eq!(outcome.insert_result.status, InsertStatus::CopiedFallback);
        assert_eq!(outcome.insert_result.chars_copied, 4);
    }

    #[tokio::test]
    async fn unconfirmed_keyboard_output_never_replays_full_text_as_paste() {
        struct UnconfirmedKeyboard;

        #[async_trait]
        impl TextOutput for UnconfirmedKeyboard {
            async fn type_text(&self, _text: &str) -> Result<InsertResult, AppError> {
                Err(AppError::OutputUnconfirmed(
                    "Synthetic output timeout".into(),
                ))
            }

            fn mode(&self) -> OutputMode {
                OutputMode::Keyboard
            }
        }

        let no_paste = FakeOutput::err(OutputMode::Clipboard, "Paste must not execute");
        let copy = FakeOutput::ok(
            OutputMode::Clipboard,
            InsertResult::copied_fallback(InsertionStrategy::ClipboardCopyOnly, 4),
        );
        let outcome = output_with_strategy_using(
            "text",
            InsertionStrategy::Keyboard,
            &UnconfirmedKeyboard,
            &no_paste,
            &no_paste,
            &copy,
            None,
        )
        .await
        .unwrap();
        assert_eq!(outcome.insert_result.chars_copied, 4);
        assert_eq!(outcome.insert_result.chars_inserted, 0);
        assert!(outcome
            .warning
            .unwrap()
            .details
            .unwrap()
            .contains("unconfirmed"));
    }

    #[tokio::test]
    async fn revoked_output_guard_blocks_clipboard_fallback_after_keyboard_failure() {
        use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};
        use std::sync::Arc;

        struct RevokingKeyboard(Arc<AtomicBool>);

        #[async_trait]
        impl TextOutput for RevokingKeyboard {
            async fn type_text(&self, _text: &str) -> Result<InsertResult, AppError> {
                self.0.store(false, Ordering::SeqCst);
                Err(AppError::Output("Synthetic keyboard failure".into()))
            }

            fn mode(&self) -> OutputMode {
                OutputMode::Keyboard
            }
        }

        struct CountingClipboard(Arc<AtomicUsize>);

        #[async_trait]
        impl TextOutput for CountingClipboard {
            async fn type_text(&self, _text: &str) -> Result<InsertResult, AppError> {
                self.0.fetch_add(1, Ordering::SeqCst);
                panic!("Revoked operation must not reach clipboard output");
            }

            fn mode(&self) -> OutputMode {
                OutputMode::Clipboard
            }
        }

        let permitted = Arc::new(AtomicBool::new(true));
        let checked_permission = permitted.clone();
        let guard: guarded::OutputGuard =
            Arc::new(move || checked_permission.load(Ordering::SeqCst));
        let calls = Arc::new(AtomicUsize::new(0));
        let keyboard =
            guarded::GuardedOutput::new(Box::new(RevokingKeyboard(permitted)), guard.clone());
        let clipboard =
            guarded::GuardedOutput::new(Box::new(CountingClipboard(calls.clone())), guard);
        let result = output_with_strategy_using(
            "Synthetic text",
            InsertionStrategy::Keyboard,
            &keyboard,
            &keyboard,
            &clipboard,
            &clipboard,
            None,
        )
        .await;
        assert!(result.is_err());
        assert_eq!(calls.load(Ordering::SeqCst), 0);
    }

    #[tokio::test]
    async fn partial_keyboard_output_copies_without_pasting_and_preserves_progress() {
        let keyboard = FakeOutput::ok(
            OutputMode::Keyboard,
            InsertResult::partially_inserted(InsertionStrategy::Keyboard, 2),
        );
        let unused = FakeOutput::err(OutputMode::Clipboard, "must not paste");
        let copy = FakeOutput::ok(
            OutputMode::Clipboard,
            InsertResult::copied_fallback(InsertionStrategy::ClipboardCopyOnly, 4),
        );
        let outcome = output_with_strategy_using(
            "text",
            InsertionStrategy::Keyboard,
            &keyboard,
            &unused,
            &unused,
            &copy,
            None,
        )
        .await
        .unwrap();
        assert_eq!(
            outcome.insert_result.status,
            InsertStatus::PartiallyInserted
        );
        assert_eq!(outcome.insert_result.chars_inserted, 2);
        assert_eq!(outcome.insert_result.chars_copied, 4);
    }

    struct FakeOutput {
        mode: OutputMode,
        result: Result<InsertResult, &'static str>,
    }

    #[tokio::test]
    async fn partial_keyboard_output_preserves_progress_when_recovery_copy_fails() {
        let keyboard = FakeOutput::ok(
            OutputMode::Keyboard,
            InsertResult::partially_inserted(InsertionStrategy::Keyboard, 2),
        );
        let unavailable = FakeOutput::err(OutputMode::Clipboard, "synthetic copy failure");
        let outcome = output_with_strategy_using(
            "text",
            InsertionStrategy::Keyboard,
            &keyboard,
            &unavailable,
            &unavailable,
            &unavailable,
            None,
        )
        .await
        .unwrap();
        assert_eq!(
            outcome.insert_result.status,
            InsertStatus::PartiallyInserted
        );
        assert_eq!(outcome.insert_result.chars_inserted, 2);
        assert_eq!(outcome.insert_result.chars_copied, 0);
        assert!(outcome
            .warning
            .unwrap()
            .details
            .unwrap()
            .contains("could not be copied"));
    }

    impl FakeOutput {
        fn ok(mode: OutputMode, result: InsertResult) -> Self {
            Self {
                mode,
                result: Ok(result),
            }
        }

        fn err(mode: OutputMode, message: &'static str) -> Self {
            Self {
                mode,
                result: Err(message),
            }
        }
    }

    #[async_trait]
    impl TextOutput for FakeOutput {
        async fn type_text(&self, _text: &str) -> Result<InsertResult, AppError> {
            self.result
                .clone()
                .map_err(|message| AppError::Output(message.to_string()))
        }

        fn mode(&self) -> OutputMode {
            self.mode
        }
    }

    #[test]
    fn direct_streaming_strategy_allows_only_direct_insert_paths() {
        assert_eq!(
            InsertionStrategy::Auto.direct_streaming_strategy(),
            Some(InsertionStrategy::Keyboard)
        );
        assert_eq!(
            InsertionStrategy::Keyboard.direct_streaming_strategy(),
            Some(InsertionStrategy::Keyboard)
        );
        assert_eq!(
            InsertionStrategy::WindowsSendInput.direct_streaming_strategy(),
            Some(InsertionStrategy::WindowsSendInput)
        );
        assert_eq!(
            InsertionStrategy::ClipboardPaste.direct_streaming_strategy(),
            None
        );
        assert_eq!(
            InsertionStrategy::ClipboardCopyOnly.direct_streaming_strategy(),
            None
        );
    }

    #[tokio::test]
    async fn keyboard_success_returns_structured_insert_result() {
        let keyboard = FakeOutput::ok(
            OutputMode::Keyboard,
            InsertResult::inserted(InsertionStrategy::Keyboard, 5),
        );
        let clipboard = FakeOutput::err(OutputMode::Clipboard, "clipboard should not be used");

        let outcome =
            output_with_fallback_using("hello", OutputMode::Keyboard, &keyboard, &clipboard, None)
                .await
                .unwrap();

        assert_eq!(outcome.insert_result.status, InsertStatus::Inserted);
        assert_eq!(
            outcome.insert_result.strategy_used,
            InsertionStrategy::Keyboard
        );
        assert_eq!(outcome.insert_result.chars_inserted, 5);
        assert!(outcome.warning.is_none());
    }

    #[tokio::test]
    async fn keyboard_failure_returns_clipboard_result_with_fallback_warning() {
        let keyboard = FakeOutput::err(OutputMode::Keyboard, "keyboard failed");
        let clipboard = FakeOutput::ok(
            OutputMode::Clipboard,
            InsertResult::inserted(InsertionStrategy::ClipboardPaste, 4),
        );

        let outcome =
            output_with_fallback_using("text", OutputMode::Keyboard, &keyboard, &clipboard, None)
                .await
                .unwrap();

        assert_eq!(
            outcome.insert_result.strategy_used,
            InsertionStrategy::ClipboardPaste
        );
        assert_eq!(outcome.insert_result.chars_inserted, 4);
        assert_eq!(
            outcome
                .warning
                .as_ref()
                .map(|warning| warning.code.as_str()),
            Some("output_fallback_clipboard")
        );
        assert_eq!(
            outcome
                .warning
                .as_ref()
                .and_then(|warning| warning.details.as_deref()),
            Some("Output error: keyboard failed")
        );
        assert_eq!(
            outcome.insert_result.warning_code.as_deref(),
            Some("output_fallback_clipboard")
        );
        assert_eq!(
            outcome.insert_result.message.as_deref(),
            Some("Output error: keyboard failed")
        );
    }

    #[tokio::test]
    async fn clipboard_copy_only_strategy_uses_copy_output_without_warning() {
        let keyboard = FakeOutput::err(OutputMode::Keyboard, "keyboard should not be used");
        let windows_sendinput =
            FakeOutput::err(OutputMode::Keyboard, "sendinput should not be used");
        let clipboard_paste = FakeOutput::err(OutputMode::Clipboard, "paste should not be used");
        let clipboard_copy = FakeOutput::ok(
            OutputMode::Clipboard,
            InsertResult::copied_fallback(InsertionStrategy::ClipboardCopyOnly, 4),
        );

        let outcome = output_with_strategy_using(
            "text",
            InsertionStrategy::ClipboardCopyOnly,
            &keyboard,
            &windows_sendinput,
            &clipboard_paste,
            &clipboard_copy,
            None,
        )
        .await
        .unwrap();

        assert_eq!(outcome.insert_result.status, InsertStatus::CopiedFallback);
        assert_eq!(
            outcome.insert_result.strategy_used,
            InsertionStrategy::ClipboardCopyOnly
        );
        assert_eq!(outcome.insert_result.chars_inserted, 0);
        assert_eq!(outcome.insert_result.chars_copied, 4);
        assert!(outcome.warning.is_none());
        assert!(outcome.insert_result.warning_code.is_none());
    }

    #[tokio::test]
    async fn windows_sendinput_success_uses_sendinput_without_clipboard() {
        let keyboard = FakeOutput::err(OutputMode::Keyboard, "keyboard should not be used");
        let windows_sendinput = FakeOutput::ok(
            OutputMode::Keyboard,
            InsertResult::inserted(InsertionStrategy::WindowsSendInput, 4),
        );
        let clipboard_paste = FakeOutput::err(OutputMode::Clipboard, "paste should not be used");
        let clipboard_copy = FakeOutput::err(OutputMode::Clipboard, "copy should not be used");

        let outcome = output_with_strategy_using(
            "text",
            InsertionStrategy::WindowsSendInput,
            &keyboard,
            &windows_sendinput,
            &clipboard_paste,
            &clipboard_copy,
            None,
        )
        .await
        .unwrap();

        assert_eq!(outcome.insert_result.status, InsertStatus::Inserted);
        assert_eq!(
            outcome.insert_result.strategy_used,
            InsertionStrategy::WindowsSendInput
        );
        assert_eq!(outcome.insert_result.chars_inserted, 4);
        assert!(outcome.warning.is_none());
    }

    #[tokio::test]
    async fn windows_sendinput_zero_insert_falls_back_to_clipboard_with_warning() {
        let keyboard = FakeOutput::err(OutputMode::Keyboard, "keyboard should not be used");
        let windows_sendinput = FakeOutput::err(OutputMode::Keyboard, "sendinput failed");
        let clipboard_paste = FakeOutput::ok(
            OutputMode::Clipboard,
            InsertResult::inserted(InsertionStrategy::ClipboardPaste, 4),
        );
        let clipboard_copy = FakeOutput::err(OutputMode::Clipboard, "copy should not be used");

        let outcome = output_with_strategy_using(
            "text",
            InsertionStrategy::WindowsSendInput,
            &keyboard,
            &windows_sendinput,
            &clipboard_paste,
            &clipboard_copy,
            None,
        )
        .await
        .unwrap();

        assert_eq!(
            outcome.insert_result.strategy_used,
            InsertionStrategy::ClipboardPaste
        );
        assert_eq!(
            outcome.insert_result.warning_code.as_deref(),
            Some("output_fallback_clipboard")
        );
        assert_eq!(
            outcome.insert_result.message.as_deref(),
            Some("Output error: sendinput failed")
        );
    }

    #[tokio::test]
    async fn windows_sendinput_partial_insert_copies_full_text_without_pasting_again() {
        let keyboard = FakeOutput::err(OutputMode::Keyboard, "keyboard should not be used");
        let windows_sendinput = FakeOutput::ok(
            OutputMode::Keyboard,
            InsertResult::partially_inserted(InsertionStrategy::WindowsSendInput, 2),
        );
        let clipboard_paste = FakeOutput::err(OutputMode::Clipboard, "paste should not be used");
        let clipboard_copy = FakeOutput::ok(
            OutputMode::Clipboard,
            InsertResult::copied_fallback(InsertionStrategy::ClipboardCopyOnly, 4),
        );

        let outcome = output_with_strategy_using(
            "text",
            InsertionStrategy::WindowsSendInput,
            &keyboard,
            &windows_sendinput,
            &clipboard_paste,
            &clipboard_copy,
            None,
        )
        .await
        .unwrap();

        assert_eq!(outcome.insert_result.status, InsertStatus::CopiedFallback);
        assert_eq!(
            outcome.insert_result.strategy_used,
            InsertionStrategy::ClipboardCopyOnly
        );
        assert_eq!(outcome.insert_result.chars_copied, 4);
        assert_eq!(
            outcome.insert_result.warning_code.as_deref(),
            Some("output_fallback_clipboard")
        );
        assert!(outcome
            .insert_result
            .message
            .as_deref()
            .unwrap_or_default()
            .contains("inserted 2/4 chars"));
    }

    #[tokio::test]
    async fn clipboard_mode_surfaces_platform_warning_and_insert_result() {
        let keyboard = FakeOutput::err(OutputMode::Keyboard, "keyboard should not be used");
        let clipboard = FakeOutput::ok(
            OutputMode::Clipboard,
            InsertResult::copied_fallback(InsertionStrategy::ClipboardCopyOnly, 4),
        );
        let platform_warning = UserError {
            code: "output_wayland_clipboard_copy_only".to_string(),
            details: None,
            retry_count: 0,
        };

        let outcome = output_with_fallback_using(
            "text",
            OutputMode::Clipboard,
            &keyboard,
            &clipboard,
            Some(platform_warning),
        )
        .await
        .unwrap();

        assert_eq!(outcome.insert_result.status, InsertStatus::CopiedFallback);
        assert_eq!(
            outcome.insert_result.strategy_used,
            InsertionStrategy::ClipboardCopyOnly
        );
        assert_eq!(
            outcome
                .warning
                .as_ref()
                .map(|warning| warning.code.as_str()),
            Some("output_wayland_clipboard_copy_only")
        );
    }

    #[tokio::test]
    async fn keyboard_and_clipboard_failure_reports_both_errors() {
        let keyboard = FakeOutput::err(OutputMode::Keyboard, "keyboard failed");
        let clipboard = FakeOutput::err(OutputMode::Clipboard, "clipboard failed");

        let error =
            output_with_fallback_using("text", OutputMode::Keyboard, &keyboard, &clipboard, None)
                .await
                .unwrap_err();

        assert!(error.contains("keyboard failed"));
        assert!(error.contains("clipboard failed"));
    }
}
