//! Reads the focused text field through the macOS Accessibility API.

use std::ffi::c_void;

use super::analysis::FieldSnapshot;

type CfTypeRef = *const c_void;
type CfStringRef = *const c_void;
type AxUiElementRef = *const c_void;
type AxError = i32;

const AX_SUCCESS: AxError = 0;
const CF_STRING_ENCODING_UTF8: u32 = 0x0800_0100;
/// Larger fields are documents, not the message box the user just dictated into.
const MAX_FIELD_UTF16: isize = 20_000;
const AX_MESSAGING_TIMEOUT_SECONDS: f32 = 0.5;

#[repr(C)]
struct CfRange {
    location: isize,
    length: isize,
}

#[link(name = "ApplicationServices", kind = "framework")]
extern "C" {
    fn AXUIElementCreateSystemWide() -> AxUiElementRef;
    fn AXUIElementCopyAttributeValue(
        element: AxUiElementRef,
        attribute: CfStringRef,
        value: *mut CfTypeRef,
    ) -> AxError;
    fn AXUIElementIsAttributeSettable(
        element: AxUiElementRef,
        attribute: CfStringRef,
        settable: *mut u8,
    ) -> AxError;
    fn AXUIElementGetPid(element: AxUiElementRef, pid: *mut i32) -> AxError;
    fn AXUIElementSetMessagingTimeout(element: AxUiElementRef, timeout: f32) -> AxError;
}

#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    fn CFRelease(cf: CfTypeRef);
    fn CFGetTypeID(cf: CfTypeRef) -> usize;
    fn CFStringGetTypeID() -> usize;
    fn CFHash(cf: CfTypeRef) -> usize;
    fn CFStringGetLength(string: CfStringRef) -> isize;
    fn CFStringGetCharacters(string: CfStringRef, range: CfRange, buffer: *mut u16);
    fn CFStringCreateWithBytes(
        allocator: *const c_void,
        bytes: *const u8,
        num_bytes: isize,
        encoding: u32,
        is_external_representation: u8,
    ) -> CfStringRef;
}

struct Owned(std::ptr::NonNull<c_void>);

impl Owned {
    fn new(value: CfTypeRef) -> Option<Self> {
        std::ptr::NonNull::new(value.cast_mut()).map(Self)
    }

    fn as_ptr(&self) -> CfTypeRef {
        self.0.as_ptr()
    }
}

impl Drop for Owned {
    fn drop(&mut self) {
        unsafe { CFRelease(self.as_ptr()) }
    }
}

fn cf_string(text: &str) -> Option<Owned> {
    Owned::new(unsafe {
        CFStringCreateWithBytes(
            std::ptr::null(),
            text.as_ptr(),
            text.len() as isize,
            CF_STRING_ENCODING_UTF8,
            0,
        )
    })
}

fn copy_attribute(element: AxUiElementRef, name: &str) -> Option<Owned> {
    let name = cf_string(name)?;
    let mut value: CfTypeRef = std::ptr::null();
    let status = unsafe { AXUIElementCopyAttributeValue(element, name.as_ptr(), &mut value) };
    if status != AX_SUCCESS {
        return None;
    }
    Owned::new(value)
}

fn string_value(value: &Owned) -> Option<String> {
    unsafe {
        if CFGetTypeID(value.as_ptr()) != CFStringGetTypeID() {
            return None;
        }
        let length = CFStringGetLength(value.as_ptr());
        if !(0..=MAX_FIELD_UTF16).contains(&length) {
            return None;
        }
        let mut buffer = vec![0u16; length as usize];
        CFStringGetCharacters(
            value.as_ptr(),
            CfRange {
                location: 0,
                length,
            },
            buffer.as_mut_ptr(),
        );
        Some(String::from_utf16_lossy(&buffer))
    }
}

fn copy_string(element: AxUiElementRef, name: &str) -> Option<String> {
    string_value(&copy_attribute(element, name)?)
}

fn optional_subrole_status(status: AxError) -> Result<bool, ()> {
    match status {
        AX_SUCCESS => Ok(true),
        -25205 | -25212 => Ok(false),
        _ => Err(()),
    }
}

fn copy_optional_subrole(element: AxUiElementRef) -> Result<Option<String>, ()> {
    let name = cf_string("AXSubrole").ok_or(())?;
    let mut value: CfTypeRef = std::ptr::null();
    let status = unsafe { AXUIElementCopyAttributeValue(element, name.as_ptr(), &mut value) };
    let value = Owned::new(value);
    if !optional_subrole_status(status)? {
        return Ok(None);
    }
    let value = value.ok_or(())?;
    string_value(&value).map(Some).ok_or(())
}

fn is_settable(element: AxUiElementRef, name: &str) -> bool {
    let Some(name) = cf_string(name) else {
        return false;
    };
    let mut settable = 0u8;
    let status = unsafe { AXUIElementIsAttributeSettable(element, name.as_ptr(), &mut settable) };
    status == AX_SUCCESS && settable != 0
}

fn allows_learning_role(role: Option<&str>, subrole: Option<&str>) -> bool {
    matches!(role, Some("AXTextField" | "AXTextArea" | "AXComboBox"))
        && subrole != Some("AXSecureTextField")
}

fn snapshot_from_metadata(
    identity: (i32, usize),
    role: Option<&str>,
    subrole: Option<&str>,
    editable: bool,
    read_text: impl FnOnce() -> Option<String>,
) -> Option<FieldSnapshot> {
    let secure = role == Some("AXSecureTextField") || subrole == Some("AXSecureTextField");
    if secure {
        return Some(FieldSnapshot {
            identity,
            value: String::new(),
            editable: false,
            secure,
        });
    }
    if !allows_learning_role(role, subrole) {
        return None;
    }
    Some(FieldSnapshot {
        identity,
        value: if editable {
            read_text()?
        } else {
            String::new()
        },
        editable,
        secure,
    })
}

pub fn focused_field() -> Option<FieldSnapshot> {
    if !crate::pipeline::is_accessibility_trusted() {
        return None;
    }
    let system = Owned::new(unsafe { AXUIElementCreateSystemWide() })?;
    unsafe {
        AXUIElementSetMessagingTimeout(system.as_ptr(), AX_MESSAGING_TIMEOUT_SECONDS);
    }
    let focused = copy_attribute(system.as_ptr(), "AXFocusedUIElement")?;
    if unsafe { AXUIElementSetMessagingTimeout(focused.as_ptr(), AX_MESSAGING_TIMEOUT_SECONDS) }
        != AX_SUCCESS
    {
        return None;
    }
    let mut pid = 0;
    if unsafe { AXUIElementGetPid(focused.as_ptr(), &mut pid) } != AX_SUCCESS
        || pid == std::process::id() as i32
    {
        return None;
    }
    // CFHash agrees with CFEqual, so it is stable for the same remote element across copies.
    let identity = (pid, unsafe { CFHash(focused.as_ptr()) });
    let role = copy_string(focused.as_ptr(), "AXRole");
    let subrole = copy_optional_subrole(focused.as_ptr()).ok()?;
    let editable = is_settable(focused.as_ptr(), "AXValue");
    snapshot_from_metadata(
        identity,
        role.as_deref(),
        subrole.as_deref(),
        editable,
        || copy_string(focused.as_ptr(), "AXValue"),
    )
}

fn selection_attribute_allows_copy(status: AxError) -> Result<bool, ()> {
    match status {
        AX_SUCCESS => Ok(false),
        -25205 => Ok(true),
        _ => Err(()),
    }
}

pub(crate) fn selected_text() -> Option<Result<Option<String>, crate::selection::SelectionError>> {
    if !crate::pipeline::is_accessibility_trusted() {
        return Some(Err(crate::selection::SelectionError::ReadFailed));
    }
    let Some(system) = Owned::new(unsafe { AXUIElementCreateSystemWide() }) else {
        return Some(Err(crate::selection::SelectionError::ReadFailed));
    };
    if unsafe { AXUIElementSetMessagingTimeout(system.as_ptr(), AX_MESSAGING_TIMEOUT_SECONDS) }
        != AX_SUCCESS
    {
        return Some(Err(crate::selection::SelectionError::ReadFailed));
    }
    let Some(focused) = copy_attribute(system.as_ptr(), "AXFocusedUIElement") else {
        return Some(Err(crate::selection::SelectionError::ReadFailed));
    };
    let mut pid = 0;
    if unsafe { AXUIElementGetPid(focused.as_ptr(), &mut pid) } != AX_SUCCESS
        || pid == std::process::id() as i32
    {
        return Some(Err(crate::selection::SelectionError::ReadFailed));
    }
    if unsafe { AXUIElementSetMessagingTimeout(focused.as_ptr(), AX_MESSAGING_TIMEOUT_SECONDS) }
        != AX_SUCCESS
    {
        return Some(Err(crate::selection::SelectionError::ReadFailed));
    }
    let role = copy_string(focused.as_ptr(), "AXRole");
    let subrole = match copy_optional_subrole(focused.as_ptr()) {
        Ok(subrole) => subrole,
        Err(()) => return Some(Err(crate::selection::SelectionError::ReadFailed)),
    };
    if role.is_none()
        || role.as_deref() == Some("AXSecureTextField")
        || subrole.as_deref() == Some("AXSecureTextField")
    {
        return Some(Err(crate::selection::SelectionError::ReadFailed));
    }
    let Some(name) = cf_string("AXSelectedText") else {
        return Some(Err(crate::selection::SelectionError::ReadFailed));
    };
    let mut value: CfTypeRef = std::ptr::null();
    let status =
        unsafe { AXUIElementCopyAttributeValue(focused.as_ptr(), name.as_ptr(), &mut value) };
    let value = Owned::new(value);
    match selection_attribute_allows_copy(status) {
        Ok(true) => return None,
        Ok(false) => {}
        Err(()) => return Some(Err(crate::selection::SelectionError::ReadFailed)),
    }
    let text = value.as_ref().and_then(string_value);
    Some(
        text.map(|text| (!text.trim().is_empty()).then_some(text))
            .ok_or(crate::selection::SelectionError::ReadFailed),
    )
}

#[cfg(test)]
mod tests {
    #[test]
    fn selection_no_value_is_not_permission_to_simulate_copy() {
        assert_eq!(super::selection_attribute_allows_copy(-25205), Ok(true));
        assert_eq!(super::selection_attribute_allows_copy(0), Ok(false));
        for status in [-25212, -25204, -25201, -25211] {
            assert!(super::selection_attribute_allows_copy(status).is_err());
        }
    }

    #[test]
    fn null_owned_resource_is_rejected_without_running_its_destructor() {
        const CHILD_MARKER: &str = "OPENTYPELESS_NULL_CF_RESOURCE_TEST";
        if std::env::var(CHILD_MARKER).as_deref() == Ok("1") {
            assert!(super::Owned::new(std::ptr::null()).is_none());
            return;
        }
        let child = std::process::Command::new(std::env::current_exe().unwrap())
            .args([
                "--exact",
                "edit_learning::macos::tests::null_owned_resource_is_rejected_without_running_its_destructor",
                "--nocapture",
            ])
            .env(CHILD_MARKER, "1")
            .output()
            .unwrap();
        assert!(
            child.status.success(),
            "null resource handling terminated the subprocess: {:?}\n{}",
            child.status,
            String::from_utf8_lossy(&child.stderr)
        );
    }

    #[test]
    fn valid_owned_resource_keeps_its_value_until_drop() {
        let value = super::cf_string("synthetic resource").unwrap();
        assert_eq!(
            super::string_value(&value).as_deref(),
            Some("synthetic resource")
        );
    }

    #[test]
    fn missing_accessibility_attribute_does_not_construct_an_owned_resource() {
        let element = super::Owned::new(unsafe { super::AXUIElementCreateSystemWide() }).unwrap();
        assert!(
            super::copy_attribute(element.as_ptr(), "OpenTypelessSyntheticMissingAttribute")
                .is_none()
        );
    }

    #[test]
    fn repeated_missing_resources_preserve_valid_resource_ownership() {
        for _attempt in 0..1000 {
            assert!(super::Owned::new(std::ptr::null()).is_none());
            let value = super::cf_string("synthetic resource").unwrap();
            assert_eq!(
                super::string_value(&value).as_deref(),
                Some("synthetic resource")
            );
        }
    }

    #[test]
    fn optional_subrole_absence_does_not_hide_a_communication_failure() {
        assert_eq!(super::optional_subrole_status(-25205), Ok(false));
        assert_eq!(super::optional_subrole_status(-25212), Ok(false));
        assert_eq!(super::optional_subrole_status(0), Ok(true));
        for status in [-25204, -25201, -25211] {
            assert!(super::optional_subrole_status(status).is_err());
        }
    }

    #[test]
    fn protected_or_unconfirmed_fields_never_read_their_text() {
        for (role, subrole, editable) in [
            (Some("AXTextField"), Some("AXSecureTextField"), true),
            (Some("AXSecureTextField"), None, true),
            (None, None, true),
            (Some("AXUnknown"), None, true),
            (Some("AXTextArea"), None, false),
        ] {
            let read = std::cell::Cell::new(false);
            let snapshot = super::snapshot_from_metadata((42, 7), role, subrole, editable, || {
                read.set(true);
                Some("Text that must not be read".into())
            });
            assert!(!read.get());
            if let Some(snapshot) = snapshot {
                assert!(snapshot.value.is_empty());
                assert!(!snapshot.editable);
            }
        }
    }

    #[test]
    fn confirmed_editable_field_reads_its_text_once() {
        let reads = std::cell::Cell::new(0);
        let snapshot =
            super::snapshot_from_metadata((42, 7), Some("AXTextArea"), None, true, || {
                reads.set(reads.get() + 1);
                Some("Ordinary dictation".into())
            })
            .unwrap();
        assert_eq!(reads.get(), 1);
        assert_eq!(snapshot.value, "Ordinary dictation");
        assert!(snapshot.editable);
        assert!(!snapshot.secure);
    }

    #[test]
    fn learning_requires_a_known_text_role_and_rejects_secure_fields() {
        assert!(!super::allows_learning_role(None, None));
        assert!(!super::allows_learning_role(Some("AXUnknown"), None));
        assert!(!super::allows_learning_role(
            Some("AXTextField"),
            Some("AXSecureTextField")
        ));
        assert!(!super::allows_learning_role(
            Some("AXSecureTextField"),
            None
        ));
        for role in ["AXTextField", "AXTextArea", "AXComboBox"] {
            assert!(super::allows_learning_role(Some(role), None));
        }
    }
}
