#[cfg(not(target_os = "macos"))]
use enigo::{Direction, Enigo, Key, Keyboard, Settings as EnigoSettings};

const CLIPBOARD_COPY_SETTLE_MS: u64 = 100;

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SelectionError {
    BackupUnavailable,
    MarkerFailed,
    CopyFailed,
    ReadFailed,
    RestoreFailed,
    ClipboardChanged,
    SelectionUnconfirmed,
    Cancelled,
}

trait SelectionClipboard {
    fn can_backup_as_text(&mut self) -> bool {
        true
    }
    fn requires_revision(&self) -> bool {
        false
    }
    fn revision(&mut self) -> Option<u64> {
        None
    }
    fn is_active(&self) -> bool {
        true
    }
    fn read_text(&mut self) -> Result<String, SelectionError>;
    fn write_text(&mut self, text: &str) -> Result<(), SelectionError>;
    fn copy_selection(&mut self) -> Result<(), SelectionError>;
    fn settle(&mut self);
}

pub struct SelectionReceipt {
    pub text: Option<String>,
    pub restoration_error: Option<SelectionError>,
}

impl SelectionReceipt {
    pub fn into_checked(self) -> Result<Option<String>, SelectionError> {
        match self.restoration_error {
            Some(error) => Err(error),
            None => Ok(self.text),
        }
    }
}

#[cfg(test)]
fn capture_with_clipboard(
    backend: &mut impl SelectionClipboard,
    sentinel: &str,
) -> Result<Option<String>, SelectionError> {
    capture_with_clipboard_receipt(backend, sentinel)?.into_checked()
}

fn capture_with_clipboard_receipt(
    backend: &mut impl SelectionClipboard,
    sentinel: &str,
) -> Result<SelectionReceipt, SelectionError> {
    if !backend.is_active() {
        return Err(SelectionError::Cancelled);
    }
    if !backend.can_backup_as_text() {
        return Err(SelectionError::BackupUnavailable);
    }
    let backup_revision = backend.revision();
    if backend.requires_revision() && backup_revision.is_none() {
        return Err(SelectionError::BackupUnavailable);
    }
    let backup = backend
        .read_text()
        .map_err(|_| SelectionError::BackupUnavailable)?;
    if !backend.can_backup_as_text() || backend.revision() != backup_revision {
        return Err(SelectionError::ClipboardChanged);
    }
    if !backend.is_active() {
        return Err(SelectionError::Cancelled);
    }
    backend
        .write_text(sentinel)
        .map_err(|_| SelectionError::MarkerFailed)?;
    let marker_revision = backend.revision();
    if (backend.requires_revision() && marker_revision.is_none())
        || backend.read_text()? != sentinel
        || !backend.can_backup_as_text()
        || backend.revision() != marker_revision
    {
        return Err(SelectionError::ClipboardChanged);
    }
    let copied = if backend.is_active() {
        backend.copy_selection()
    } else {
        Err(SelectionError::Cancelled)
    };
    backend.settle();
    let captured_revision = backend.revision();
    if backend.requires_revision() && captured_revision.is_none() {
        return Err(SelectionError::ClipboardChanged);
    }
    let captured = backend.read_text()?;
    if backend.read_text()? != captured || backend.revision() != captured_revision {
        return Err(SelectionError::ClipboardChanged);
    }
    if copied.is_err() && captured != sentinel {
        return Err(SelectionError::ClipboardChanged);
    }
    let restoration_error = backend
        .write_text(&backup)
        .err()
        .map(|_| SelectionError::RestoreFailed);
    copied?;
    if !backend.is_active() {
        return Err(SelectionError::Cancelled);
    }
    let text = selected_text_from_clipboard_result(Some(captured), sentinel)
        .ok_or(SelectionError::SelectionUnconfirmed)?;
    Ok(SelectionReceipt {
        text: Some(text),
        restoration_error,
    })
}

struct SystemClipboard(arboard::Clipboard);

struct ActiveClipboard<'active, Backend> {
    backend: Backend,
    is_active: &'active dyn Fn() -> bool,
}

impl<Backend: SelectionClipboard> SelectionClipboard for ActiveClipboard<'_, Backend> {
    fn can_backup_as_text(&mut self) -> bool {
        self.backend.can_backup_as_text()
    }
    fn requires_revision(&self) -> bool {
        self.backend.requires_revision()
    }
    fn revision(&mut self) -> Option<u64> {
        self.backend.revision()
    }
    fn is_active(&self) -> bool {
        (self.is_active)()
    }

    fn read_text(&mut self) -> Result<String, SelectionError> {
        self.backend.read_text()
    }

    fn write_text(&mut self, text: &str) -> Result<(), SelectionError> {
        self.backend.write_text(text)
    }

    fn copy_selection(&mut self) -> Result<(), SelectionError> {
        self.backend.copy_selection()
    }

    fn settle(&mut self) {
        self.backend.settle()
    }
}

impl SelectionClipboard for SystemClipboard {
    fn can_backup_as_text(&mut self) -> bool {
        #[cfg(target_os = "macos")]
        {
            macos_clipboard_is_plain_text()
        }
        #[cfg(target_os = "windows")]
        {
            windows_clipboard_is_plain_text()
        }
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        {
            true
        }
    }
    fn requires_revision(&self) -> bool {
        cfg!(any(target_os = "macos", target_os = "windows"))
    }
    fn revision(&mut self) -> Option<u64> {
        #[cfg(target_os = "macos")]
        {
            macos_clipboard_revision()
        }
        #[cfg(target_os = "windows")]
        {
            windows_clipboard_revision()
        }
        #[cfg(not(any(target_os = "macos", target_os = "windows")))]
        {
            None
        }
    }
    fn read_text(&mut self) -> Result<String, SelectionError> {
        self.0.get_text().map_err(|_| SelectionError::ReadFailed)
    }

    fn write_text(&mut self, text: &str) -> Result<(), SelectionError> {
        self.0
            .set_text(text)
            .map_err(|_| SelectionError::RestoreFailed)
    }

    fn copy_selection(&mut self) -> Result<(), SelectionError> {
        if copy_selected_text_to_clipboard() {
            Ok(())
        } else {
            Err(SelectionError::CopyFailed)
        }
    }

    fn settle(&mut self) {
        std::thread::sleep(std::time::Duration::from_millis(CLIPBOARD_COPY_SETTLE_MS));
    }
}

pub fn selected_text_from_clipboard_result(
    selected: Option<String>,
    sentinel: &str,
) -> Option<String> {
    match selected {
        Some(text) if !text.trim().is_empty() && text != sentinel => Some(text),
        _ => None,
    }
}

fn clipboard_copy_sentinel() -> String {
    let nanos = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .map(|duration| duration.as_nanos())
        .unwrap_or_default();
    format!(
        "__opentypeless_copy_sentinel_{}_{}__",
        std::process::id(),
        nanos
    )
}

#[cfg(target_os = "macos")]
fn copy_selected_text_to_clipboard() -> bool {
    let mut child = match std::process::Command::new("/usr/bin/osascript")
        .args([
            "-e",
            r#"tell application "System Events" to keystroke "c" using command down"#,
        ])
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
    {
        Ok(child) => child,
        Err(_) => return false,
    };
    let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
    loop {
        match child.try_wait() {
            Ok(Some(status)) => return status.success(),
            Ok(None) if std::time::Instant::now() < deadline => {
                std::thread::sleep(std::time::Duration::from_millis(10));
            }
            _ => {
                let _ = child.kill();
                let _ = child.wait();
                return false;
            }
        }
    }
}

#[cfg(not(target_os = "macos"))]
fn copy_selected_text_to_clipboard() -> bool {
    let Ok(mut enigo) = Enigo::new(&EnigoSettings::default()) else {
        return false;
    };

    let pressed = enigo.key(Key::Control, Direction::Press).is_ok();
    if pressed {
        let copied = enigo.key(Key::Unicode('c'), Direction::Click).is_ok();
        let released = enigo.key(Key::Control, Direction::Release).is_ok();
        return copied && released;
    }
    pressed
}

pub fn capture_selected_text() -> Option<String> {
    capture_selected_text_checked().ok().flatten()
}

pub fn capture_selected_text_checked() -> Result<Option<String>, SelectionError> {
    capture_selected_text_if_active(&|| true)
}

pub fn capture_selected_text_if_active(
    is_active: &dyn Fn() -> bool,
) -> Result<Option<String>, SelectionError> {
    capture_selected_text_receipt(is_active)?.into_checked()
}

pub fn capture_selected_text_receipt(
    is_active: &dyn Fn() -> bool,
) -> Result<SelectionReceipt, SelectionError> {
    if !is_active() {
        return Err(SelectionError::Cancelled);
    }
    #[cfg(target_os = "macos")]
    if let Some(result) = crate::edit_learning::native_selected_text() {
        return if is_active() {
            result.map(|text| SelectionReceipt {
                text,
                restoration_error: None,
            })
        } else {
            Err(SelectionError::Cancelled)
        };
    }
    #[cfg(target_os = "macos")]
    if !macos_clipboard_is_plain_text() {
        return Err(SelectionError::BackupUnavailable);
    }
    #[cfg(target_os = "windows")]
    if !windows_clipboard_is_plain_text() {
        return Err(SelectionError::BackupUnavailable);
    }
    let clipboard = arboard::Clipboard::new().map_err(|_| SelectionError::BackupUnavailable)?;
    capture_with_clipboard_receipt(
        &mut ActiveClipboard {
            backend: SystemClipboard(clipboard),
            is_active,
        },
        &clipboard_copy_sentinel(),
    )
}

#[cfg(target_os = "macos")]
fn macos_clipboard_revision() -> Option<u64> {
    use objc2::msg_send;
    use objc2::runtime::{AnyClass, AnyObject};

    objc2::rc::autoreleasepool(|_| unsafe {
        let class = AnyClass::get("NSPasteboard")?;
        let clipboard: *mut AnyObject = msg_send![class, generalPasteboard];
        if clipboard.is_null() {
            return None;
        }
        let revision: isize = msg_send![clipboard, changeCount];
        u64::try_from(revision).ok()
    })
}

#[cfg(any(target_os = "windows", test))]
fn windows_formats_are_plain_text(formats: &[u32]) -> bool {
    formats.iter().any(|format| matches!(format, 1 | 7 | 13))
        && formats
            .iter()
            .all(|format| matches!(format, 1 | 7 | 13 | 16))
}

#[cfg(target_os = "windows")]
mod windows_clipboard {
    #[link(name = "user32")]
    extern "system" {
        pub fn OpenClipboard(window: *mut std::ffi::c_void) -> i32;
        pub fn CloseClipboard() -> i32;
        pub fn GetClipboardSequenceNumber() -> u32;
        pub fn EnumClipboardFormats(format: u32) -> u32;
    }
}

#[cfg(target_os = "windows")]
fn windows_clipboard_revision() -> Option<u64> {
    let revision = unsafe { windows_clipboard::GetClipboardSequenceNumber() };
    (revision != 0).then_some(u64::from(revision))
}

#[cfg(target_os = "windows")]
fn windows_clipboard_is_plain_text() -> bool {
    use windows_sys::Win32::Foundation::{GetLastError, SetLastError};
    if unsafe { windows_clipboard::OpenClipboard(std::ptr::null_mut()) } == 0 {
        return false;
    }
    struct ClipboardLease;
    impl Drop for ClipboardLease {
        fn drop(&mut self) {
            unsafe { windows_clipboard::CloseClipboard() };
        }
    }
    let _lease = ClipboardLease;
    let mut formats = Vec::new();
    let mut previous = 0;
    loop {
        unsafe { SetLastError(0) };
        let format = unsafe { windows_clipboard::EnumClipboardFormats(previous) };
        if format == 0 {
            return unsafe { GetLastError() } == 0 && windows_formats_are_plain_text(&formats);
        }
        if formats.len() >= 16 {
            return false;
        }
        formats.push(format);
        previous = format;
    }
}

#[cfg(target_os = "macos")]
fn macos_clipboard_is_plain_text() -> bool {
    use objc2::msg_send;
    use objc2::runtime::{AnyClass, AnyObject};
    use std::ffi::CStr;

    objc2::rc::autoreleasepool(|_| unsafe {
        let Some(class) = AnyClass::get("NSPasteboard") else {
            return false;
        };
        let clipboard: *mut AnyObject = msg_send![class, generalPasteboard];
        if clipboard.is_null() {
            return false;
        }
        let types: *mut AnyObject = msg_send![clipboard, types];
        if types.is_null() {
            return false;
        }
        let count: usize = msg_send![types, count];
        if count == 0 || count > 16 {
            return false;
        }
        for index in 0..count {
            let item: *mut AnyObject = msg_send![types, objectAtIndex: index];
            if item.is_null() {
                return false;
            }
            let name: *const std::ffi::c_char = msg_send![item, UTF8String];
            if name.is_null() {
                return false;
            }
            if !matches!(
                CStr::from_ptr(name).to_bytes(),
                b"public.utf8-plain-text" | b"public.utf16-plain-text" | b"NSStringPboardType"
            ) {
                return false;
            }
        }
        true
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    struct FakeClipboard {
        text: String,
        fail_marker: bool,
        fail_copy: bool,
        copied: String,
        newer: Option<String>,
        copy_calls: usize,
        fail_restore: bool,
        cancelled: bool,
        cancel_after_marker: bool,
        revision: u64,
        newer_during_backup: Option<String>,
        revision_unavailable: bool,
        unsafe_format: bool,
        unsafe_during_backup: bool,
        newer_after_marker: Option<String>,
        lose_revision_after_marker: bool,
        lose_revision_after_copy: bool,
    }

    impl SelectionClipboard for FakeClipboard {
        fn can_backup_as_text(&mut self) -> bool {
            !self.unsafe_format
        }
        fn requires_revision(&self) -> bool {
            true
        }
        fn revision(&mut self) -> Option<u64> {
            (!self.revision_unavailable).then_some(self.revision)
        }
        fn is_active(&self) -> bool {
            !self.cancelled
        }
        fn read_text(&mut self) -> Result<String, SelectionError> {
            if self.unsafe_during_backup {
                self.unsafe_format = true;
                self.unsafe_during_backup = false;
            }
            if let Some(newer) = self.newer_during_backup.take() {
                let backup = self.text.clone();
                self.text = newer;
                self.revision += 1;
                return Ok(backup);
            }
            if self.copy_calls > 0 {
                if let Some(newer) = self.newer.take() {
                    let captured = self.text.clone();
                    self.text = newer;
                    self.revision += 1;
                    return Ok(captured);
                }
            }
            Ok(self.text.clone())
        }

        fn write_text(&mut self, text: &str) -> Result<(), SelectionError> {
            if self.copy_calls > 0 && self.fail_restore {
                return Err(SelectionError::RestoreFailed);
            }
            if self.fail_marker {
                return Err(SelectionError::MarkerFailed);
            }
            self.text = text.to_string();
            self.revision += 1;
            if self.lose_revision_after_marker {
                self.revision_unavailable = true;
            }
            if self.cancel_after_marker {
                self.cancelled = true;
            }
            if let Some(newer) = self.newer_after_marker.take() {
                self.text = newer;
                self.revision += 1;
            }
            Ok(())
        }

        fn copy_selection(&mut self) -> Result<(), SelectionError> {
            self.copy_calls += 1;
            if self.fail_copy {
                Err(SelectionError::CopyFailed)
            } else {
                self.text = self.copied.clone();
                self.revision += 1;
                if self.lose_revision_after_copy {
                    self.revision_unavailable = true;
                }
                Ok(())
            }
        }

        fn settle(&mut self) {}
    }

    fn fake_clipboard() -> FakeClipboard {
        FakeClipboard {
            text: "old clipboard".into(),
            fail_marker: false,
            fail_copy: false,
            copied: "selected".into(),
            newer: None,
            copy_calls: 0,
            fail_restore: false,
            cancelled: false,
            cancel_after_marker: false,
            revision: 0,
            newer_during_backup: None,
            revision_unavailable: false,
            unsafe_format: false,
            unsafe_during_backup: false,
            newer_after_marker: None,
            lose_revision_after_marker: false,
            lose_revision_after_copy: false,
        }
    }

    #[test]
    fn selection_marker_failure_never_sends_copy_or_returns_old_text() {
        let mut backend = fake_clipboard();
        backend.fail_marker = true;
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::MarkerFailed)
        );
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.text, "old clipboard");
    }

    #[test]
    fn missing_marker_revision_blocks_copy() {
        let mut backend = fake_clipboard();
        backend.lose_revision_after_marker = true;
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::ClipboardChanged)
        );
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.text, "marker");
    }

    #[test]
    fn missing_capture_revision_blocks_restore() {
        let mut backend = fake_clipboard();
        backend.lose_revision_after_copy = true;
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::ClipboardChanged)
        );
        assert_eq!(backend.text, "selected");
    }

    #[test]
    fn replaced_marker_does_not_send_copy_or_restore_old_backup() {
        let mut backend = fake_clipboard();
        backend.newer_after_marker = Some("new user copy".into());
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::ClipboardChanged)
        );
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.text, "new user copy");
    }

    #[test]
    fn unavailable_required_revision_does_not_modify_clipboard() {
        let mut backend = fake_clipboard();
        backend.revision_unavailable = true;
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::BackupUnavailable)
        );
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.text, "old clipboard");
    }

    #[test]
    fn unsafe_backup_format_is_rejected_before_marker_write() {
        let mut backend = fake_clipboard();
        backend.unsafe_format = true;
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::BackupUnavailable)
        );
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.text, "old clipboard");
    }

    #[test]
    fn backup_format_change_without_text_change_does_not_write_marker() {
        let mut backend = fake_clipboard();
        backend.unsafe_during_backup = true;
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::ClipboardChanged)
        );
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.revision, 0);
        assert_eq!(backend.text, "old clipboard");
    }

    #[test]
    fn windows_plain_text_formats_reject_images_and_registered_rich_formats() {
        assert!(windows_formats_are_plain_text(&[1, 7, 13, 16]));
        assert!(windows_formats_are_plain_text(&[13]));
        for formats in [vec![], vec![2], vec![13, 8], vec![13, 49152], vec![16]] {
            assert!(!windows_formats_are_plain_text(&formats));
        }
    }

    #[test]
    fn selection_backup_change_does_not_write_marker_or_send_copy() {
        let mut backend = fake_clipboard();
        backend.newer_during_backup = Some("new copy during backup".into());
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::ClipboardChanged)
        );
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.text, "new copy during backup");
    }

    #[test]
    fn selection_cancelled_before_capture_does_not_modify_clipboard() {
        let mut backend = fake_clipboard();
        backend.cancelled = true;
        assert!(capture_with_clipboard(&mut backend, "marker").is_err());
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.text, "old clipboard");
    }

    #[test]
    fn selection_cancelled_after_marker_restores_without_sending_copy() {
        let mut backend = fake_clipboard();
        backend.cancel_after_marker = true;
        assert!(capture_with_clipboard(&mut backend, "marker").is_err());
        assert_eq!(backend.copy_calls, 0);
        assert_eq!(backend.text, "old clipboard");
    }

    #[test]
    fn selection_copy_failure_restores_marker_and_rejects_capture() {
        let mut backend = fake_clipboard();
        backend.fail_copy = true;
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::CopyFailed)
        );
        assert_eq!(backend.text, "old clipboard");
    }

    #[test]
    fn selection_concurrent_change_is_not_overwritten() {
        let mut backend = fake_clipboard();
        backend.newer = Some("new user copy".into());
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::ClipboardChanged)
        );
        assert_eq!(backend.text, "new user copy");
    }

    #[test]
    fn selection_same_text_new_copy_is_not_restored_over() {
        let mut backend = fake_clipboard();
        backend.newer = Some(backend.copied.clone());
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::ClipboardChanged)
        );
        assert_eq!(backend.text, "selected");
    }

    #[test]
    fn selection_same_text_as_backup_remains_valid() {
        let mut backend = fake_clipboard();
        backend.copied = backend.text.clone();
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Ok(Some("old clipboard".into()))
        );
    }

    #[test]
    fn selection_restore_failure_is_not_reported_as_success() {
        let mut backend = fake_clipboard();
        backend.fail_restore = true;
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::RestoreFailed)
        );
    }

    #[test]
    fn selection_restore_failure_retains_capture_in_separate_receipt() {
        let mut backend = fake_clipboard();
        backend.fail_restore = true;
        let receipt = capture_with_clipboard_receipt(&mut backend, "marker").unwrap();
        assert_eq!(receipt.text.as_deref(), Some("selected"));
        assert_eq!(
            receipt.restoration_error,
            Some(SelectionError::RestoreFailed)
        );
        assert_eq!(receipt.into_checked(), Err(SelectionError::RestoreFailed));
    }

    #[test]
    fn selection_unchanged_marker_is_unknown_not_proof_of_no_selection() {
        let mut backend = fake_clipboard();
        backend.copied = "marker".into();
        assert_eq!(
            capture_with_clipboard(&mut backend, "marker"),
            Err(SelectionError::SelectionUnconfirmed)
        );
        assert_eq!(backend.text, "old clipboard");
    }

    #[test]
    fn selected_text_rejects_copy_sentinel_when_clipboard_was_unchanged() {
        assert_eq!(
            selected_text_from_clipboard_result(Some("__sentinel__".to_string()), "__sentinel__"),
            None
        );
    }

    #[test]
    fn selected_text_accepts_text_that_matches_previous_clipboard_backup() {
        assert_eq!(
            selected_text_from_clipboard_result(Some("selected text".to_string()), "__sentinel__"),
            Some("selected text".to_string())
        );
    }

    #[test]
    fn selected_text_rejects_whitespace_only_clipboard() {
        assert_eq!(
            selected_text_from_clipboard_result(Some(" \n\t ".to_string()), "__sentinel__"),
            None
        );
    }
}
