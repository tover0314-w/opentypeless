use async_trait::async_trait;

use crate::error::AppError;

use super::{InsertResult, InsertionStrategy, OutputMode, TextOutput};

#[cfg(target_os = "windows")]
const SENDINPUT_CHUNK_CHARS: usize = 16;
#[cfg(target_os = "windows")]
const SENDINPUT_CHUNK_DELAY_MS: u64 = 12;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum WindowsSendInputNewlineMode {
    Enter,
    ShiftEnter,
    CrLf,
}

impl WindowsSendInputNewlineMode {
    pub fn from_config_value(value: &str) -> Self {
        match value {
            "shiftEnter" => Self::ShiftEnter,
            "crlf" => Self::CrLf,
            _ => Self::Enter,
        }
    }
}

#[derive(Debug, PartialEq, Eq)]
pub enum TypeError {
    Partial {
        typed_chars: usize,
        source: Box<TypeError>,
    },
    SendInputFailed(String),
}

impl TypeError {
    fn typed_chars(&self) -> usize {
        match self {
            Self::Partial { typed_chars, .. } => *typed_chars,
            _ => 0,
        }
    }
}

impl std::fmt::Display for TypeError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            TypeError::Partial {
                typed_chars,
                source,
            } => {
                write!(f, "{source} after {typed_chars} chars were sent")
            }
            TypeError::SendInputFailed(message) => write!(f, "Windows SendInput failed: {message}"),
        }
    }
}

impl std::error::Error for TypeError {}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct WindowsSendInputOptions {
    pub newline_mode: WindowsSendInputNewlineMode,
}

impl Default for WindowsSendInputOptions {
    fn default() -> Self {
        Self {
            newline_mode: WindowsSendInputNewlineMode::Enter,
        }
    }
}

pub struct WindowsSendInputOutput {
    options: WindowsSendInputOptions,
}

impl WindowsSendInputOutput {
    pub fn new(options: WindowsSendInputOptions) -> Self {
        Self { options }
    }
}

#[async_trait]
impl TextOutput for WindowsSendInputOutput {
    async fn type_text(&self, text: &str) -> Result<InsertResult, AppError> {
        let text = text.to_string();
        let options = self.options;
        tokio::task::spawn_blocking(move || {
            let result = type_unicode_chunk_with_options(&text, options);
            let insert_result = map_sendinput_result(&text, result);
            if insert_result.status == super::InsertStatus::Failed {
                Err(AppError::Output(format!(
                    "Windows SendInput inserted 0/{} chars",
                    expected_sendinput_typed_chars(&text)
                )))
            } else {
                Ok(insert_result)
            }
        })
        .await
        .map_err(|e| AppError::Output(format!("Spawn blocking error: {}", e)))?
    }

    fn mode(&self) -> OutputMode {
        OutputMode::Keyboard
    }
}

pub fn expected_sendinput_typed_chars(text: &str) -> usize {
    text.chars().filter(|ch| *ch != '\r').count()
}

pub fn map_sendinput_result(text: &str, result: Result<usize, TypeError>) -> InsertResult {
    let expected = expected_sendinput_typed_chars(text);
    match result {
        Ok(typed_chars) if typed_chars == expected => {
            InsertResult::inserted(InsertionStrategy::WindowsSendInput, typed_chars)
        }
        Ok(typed_chars) if typed_chars > 0 => {
            InsertResult::partially_inserted(InsertionStrategy::WindowsSendInput, typed_chars)
        }
        Err(error) if error.typed_chars() > 0 => InsertResult::partially_inserted(
            InsertionStrategy::WindowsSendInput,
            error.typed_chars(),
        ),
        Ok(_) | Err(_) => InsertResult::failed(InsertionStrategy::WindowsSendInput),
    }
}

#[cfg(target_os = "windows")]
fn type_unicode_chunk_with_options(
    text: &str,
    options: WindowsSendInputOptions,
) -> Result<usize, TypeError> {
    if text.is_empty() {
        return Ok(0);
    }

    super::windows_modifier_guard::wait_for_modifier_release()
        .map_err(|error| TypeError::SendInputFailed(error.to_string()))?;

    let mut typed_chars = 0usize;
    let mut sent_in_chunk = 0usize;
    let mut chars = text.chars().peekable();

    while let Some(ch) = chars.next() {
        if ch == '\r' {
            continue;
        }

        let result = if ch == '\n' {
            send_newline(options.newline_mode)
        } else if ch == '\t' {
            press_vk(windows_sys::Win32::UI::Input::KeyboardAndMouse::VK_TAB)
        } else {
            send_unicode_char(ch)
        };

        if let Err(error) = result {
            return Err(partial_or_original(typed_chars, error));
        }

        typed_chars += 1;
        sent_in_chunk += 1;

        if sent_in_chunk >= SENDINPUT_CHUNK_CHARS && chars.peek().is_some() {
            std::thread::sleep(std::time::Duration::from_millis(SENDINPUT_CHUNK_DELAY_MS));
            sent_in_chunk = 0;
        }
    }

    Ok(typed_chars)
}

#[cfg(not(target_os = "windows"))]
fn type_unicode_chunk_with_options(
    _text: &str,
    _options: WindowsSendInputOptions,
) -> Result<usize, TypeError> {
    Err(TypeError::SendInputFailed(
        "Windows SendInput is only available on Windows".to_string(),
    ))
}

#[cfg(target_os = "windows")]
fn partial_or_original(typed_chars: usize, source: TypeError) -> TypeError {
    if typed_chars == 0 {
        source
    } else {
        TypeError::Partial {
            typed_chars,
            source: Box::new(source),
        }
    }
}

#[cfg(target_os = "windows")]
fn send_unicode_char(ch: char) -> Result<(), TypeError> {
    let mut buf = [0u16; 2];
    for unit in ch.encode_utf16(&mut buf) {
        send_utf16_unit(*unit, false)?;
        send_utf16_unit(*unit, true)?;
    }
    Ok(())
}

#[cfg(target_os = "windows")]
fn send_newline(mode: WindowsSendInputNewlineMode) -> Result<(), TypeError> {
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{VK_RETURN, VK_SHIFT};

    match mode {
        WindowsSendInputNewlineMode::Enter => press_vk(VK_RETURN),
        WindowsSendInputNewlineMode::ShiftEnter => {
            send_vk(VK_SHIFT, false)?;
            press_vk(VK_RETURN)?;
            send_vk(VK_SHIFT, true)
        }
        WindowsSendInputNewlineMode::CrLf => {
            send_utf16_unit(0x000D, false)?;
            send_utf16_unit(0x000D, true)?;
            send_utf16_unit(0x000A, false)?;
            send_utf16_unit(0x000A, true)
        }
    }
}

#[cfg(target_os = "windows")]
fn press_vk(vk: u16) -> Result<(), TypeError> {
    send_vk(vk, false)?;
    send_vk(vk, true)
}

#[cfg(target_os = "windows")]
fn send_vk(vk: u16, key_up: bool) -> Result<(), TypeError> {
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP,
    };

    let input = INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: vk,
                wScan: 0,
                dwFlags: if key_up { KEYEVENTF_KEYUP } else { 0 },
                time: 0,
                dwExtraInfo: 0,
            },
        },
    };

    let sent = unsafe { SendInput(1, &input, std::mem::size_of::<INPUT>() as i32) };
    if sent == 1 {
        Ok(())
    } else {
        Err(TypeError::SendInputFailed(
            std::io::Error::last_os_error().to_string(),
        ))
    }
}

#[cfg(target_os = "windows")]
fn send_utf16_unit(unit: u16, key_up: bool) -> Result<(), TypeError> {
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        SendInput, INPUT, INPUT_0, INPUT_KEYBOARD, KEYBDINPUT, KEYEVENTF_KEYUP, KEYEVENTF_UNICODE,
    };

    let input = INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT {
                wVk: 0,
                wScan: unit,
                dwFlags: if key_up {
                    KEYEVENTF_UNICODE | KEYEVENTF_KEYUP
                } else {
                    KEYEVENTF_UNICODE
                },
                time: 0,
                dwExtraInfo: 0,
            },
        },
    };

    let sent = unsafe { SendInput(1, &input, std::mem::size_of::<INPUT>() as i32) };
    if sent == 1 {
        Ok(())
    } else {
        Err(TypeError::SendInputFailed(
            std::io::Error::last_os_error().to_string(),
        ))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn expected_typed_chars_skips_carriage_returns_and_counts_unicode_scalars() {
        assert_eq!(expected_sendinput_typed_chars("A\r\n\t😀"), 4);
    }

    #[test]
    fn maps_full_partial_and_zero_sendinput_results() {
        assert_eq!(
            map_sendinput_result("abcd", Ok(4)),
            InsertResult::inserted(InsertionStrategy::WindowsSendInput, 4)
        );
        assert_eq!(
            map_sendinput_result("abcd", Ok(2)),
            InsertResult::partially_inserted(InsertionStrategy::WindowsSendInput, 2)
        );
        assert_eq!(
            map_sendinput_result("abcd", Ok(0)),
            InsertResult::failed(InsertionStrategy::WindowsSendInput)
        );
        assert_eq!(
            map_sendinput_result("abcd", Err(TypeError::SendInputFailed("denied".into()))),
            InsertResult::failed(InsertionStrategy::WindowsSendInput)
        );
        assert_eq!(
            map_sendinput_result(
                "abcd",
                Err(TypeError::Partial {
                    typed_chars: 3,
                    source: Box::new(TypeError::SendInputFailed("denied".into())),
                })
            ),
            InsertResult::partially_inserted(InsertionStrategy::WindowsSendInput, 3)
        );
    }
}
