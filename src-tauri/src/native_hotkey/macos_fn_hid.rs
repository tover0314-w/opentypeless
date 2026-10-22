//! Read only Apple's Fn HID element. Quartz does not deliver Fn flagsChanged
//! events on every keyboard/system configuration, even when other modifiers work.

use std::collections::HashSet;
use std::ffi::{c_char, c_void};
use std::ptr;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::{Arc, Mutex};

type Ref = *const c_void;
type HidRef = *mut c_void;
type DeviceCallback = extern "C" fn(*mut c_void, i32, *mut c_void, HidRef);
type ValueCallback = extern "C" fn(*mut c_void, i32, *mut c_void, Ref);

#[link(name = "IOKit", kind = "framework")]
extern "C" {
    fn IOHIDCheckAccess(request: u32) -> u32;
    fn IOHIDManagerCreate(allocator: Ref, options: u32) -> HidRef;
    fn IOHIDManagerSetDeviceMatching(manager: HidRef, matching: Ref);
    fn IOHIDManagerSetInputValueMatching(manager: HidRef, matching: Ref);
    fn IOHIDManagerRegisterInputValueCallback(
        manager: HidRef,
        callback: ValueCallback,
        context: *mut c_void,
    );
    fn IOHIDManagerRegisterDeviceMatchingCallback(
        manager: HidRef,
        callback: DeviceCallback,
        context: *mut c_void,
    );
    fn IOHIDManagerRegisterDeviceRemovalCallback(
        manager: HidRef,
        callback: DeviceCallback,
        context: *mut c_void,
    );
    fn IOHIDManagerScheduleWithRunLoop(manager: HidRef, runloop: Ref, mode: Ref);
    fn IOHIDManagerUnscheduleFromRunLoop(manager: HidRef, runloop: Ref, mode: Ref);
    fn IOHIDManagerOpen(manager: HidRef, options: u32) -> i32;
    fn IOHIDManagerClose(manager: HidRef, options: u32) -> i32;
    fn IOHIDManagerCopyDevices(manager: HidRef) -> Ref;
    fn IOHIDDeviceCopyMatchingElements(device: HidRef, matching: Ref, options: u32) -> Ref;
    fn IOHIDDeviceGetValue(device: HidRef, element: Ref, value: *mut Ref) -> i32;
    fn IOHIDValueGetElement(value: Ref) -> Ref;
    fn IOHIDValueGetIntegerValue(value: Ref) -> isize;
    fn IOHIDElementGetUsagePage(element: Ref) -> u32;
    fn IOHIDElementGetUsage(element: Ref) -> u32;
    fn IOHIDElementGetDevice(element: Ref) -> HidRef;
}

#[link(name = "CoreFoundation", kind = "framework")]
extern "C" {
    fn CFStringCreateWithCString(allocator: Ref, value: *const c_char, encoding: u32) -> Ref;
    fn CFNumberCreate(allocator: Ref, kind: isize, value: Ref) -> Ref;
    fn CFDictionaryCreate(
        allocator: Ref,
        keys: *const Ref,
        values: *const Ref,
        count: isize,
        key_callbacks: Ref,
        value_callbacks: Ref,
    ) -> Ref;
    fn CFArrayGetCount(array: Ref) -> isize;
    fn CFArrayGetValueAtIndex(array: Ref, index: isize) -> Ref;
    fn CFSetGetCount(set: Ref) -> isize;
    fn CFSetGetValues(set: Ref, values: *mut HidRef);
    fn CFRelease(value: Ref);
    static kCFRunLoopCommonModes: Ref;
    static kCFTypeDictionaryKeyCallBacks: u8;
    static kCFTypeDictionaryValueCallBacks: u8;
}

struct OwnedCf(Ref);

impl OwnedCf {
    fn new(value: Ref) -> Option<Self> {
        (!value.is_null()).then_some(Self(value))
    }

    fn release(&mut self) {
        let value = std::mem::replace(&mut self.0, ptr::null());
        if !value.is_null() {
            unsafe { CFRelease(value) };
        }
    }
}

impl Drop for OwnedCf {
    fn drop(&mut self) {
        self.release();
    }
}

fn matching(
    page_key: &'static [u8],
    page: i32,
    usage_key: &'static [u8],
    usage: i32,
) -> Option<OwnedCf> {
    unsafe {
        let page_key = OwnedCf::new(CFStringCreateWithCString(
            ptr::null(),
            page_key.as_ptr().cast(),
            0x0800_0100,
        ))?;
        let usage_key = OwnedCf::new(CFStringCreateWithCString(
            ptr::null(),
            usage_key.as_ptr().cast(),
            0x0800_0100,
        ))?;
        // kCFNumberSInt32Type: both HID matching values are 32-bit integers.
        let page = OwnedCf::new(CFNumberCreate(ptr::null(), 3, (&page as *const i32).cast()))?;
        let usage = OwnedCf::new(CFNumberCreate(
            ptr::null(),
            3,
            (&usage as *const i32).cast(),
        ))?;
        OwnedCf::new(CFDictionaryCreate(
            ptr::null(),
            [page_key.0, usage_key.0].as_ptr(),
            [page.0, usage.0].as_ptr(),
            2,
            ptr::addr_of!(kCFTypeDictionaryKeyCallBacks).cast(),
            ptr::addr_of!(kCFTypeDictionaryValueCallBacks).cast(),
        ))
    }
}

#[derive(Default)]
struct FnDevices {
    capable: HashSet<usize>,
    held: HashSet<usize>,
}

impl FnDevices {
    fn add(&mut self, device: usize, initially_held: bool) {
        if self.capable.insert(device) && initially_held {
            self.held.insert(device);
        }
    }

    fn edge(&mut self, device: usize, down: bool) -> Option<bool> {
        if !self.capable.contains(&device) {
            return None;
        }
        let before = !self.held.is_empty();
        if down {
            self.held.insert(device);
        } else {
            self.held.remove(&device);
        }
        let after = !self.held.is_empty();
        (before != after).then_some(after)
    }

    fn remove(&mut self, device: usize) -> Option<bool> {
        self.capable.remove(&device);
        let before = !self.held.is_empty();
        self.held.remove(&device);
        (before && self.held.is_empty()).then_some(false)
    }
}

struct CallbackState {
    matching: OwnedCf,
    devices: Mutex<FnDevices>,
    authoritative: Arc<AtomicBool>,
    on_edge: Box<dyn Fn(bool)>,
}

impl CallbackState {
    fn add_device(&self, device: HidRef) {
        if device.is_null() {
            return;
        }
        let elements =
            unsafe { OwnedCf::new(IOHIDDeviceCopyMatchingElements(device, self.matching.0, 0)) };
        let Some(elements) = elements else {
            return;
        };
        if unsafe { CFArrayGetCount(elements.0) } == 0 {
            return;
        }
        let mut value = ptr::null();
        let held = unsafe {
            IOHIDDeviceGetValue(device, CFArrayGetValueAtIndex(elements.0, 0), &mut value) == 0
                && !value.is_null()
                && IOHIDValueGetIntegerValue(value) != 0
        };
        self.devices
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .add(device as usize, held);
        if !self.authoritative.swap(true, Ordering::AcqRel) {
            // Finish a possible legacy Quartz hold before changing event sources.
            (self.on_edge)(false);
        }
    }
}

extern "C" fn device_added(raw: *mut c_void, result: i32, _sender: *mut c_void, device: HidRef) {
    if raw.is_null() || result != 0 {
        return;
    }
    unsafe { &*(raw as *const CallbackState) }.add_device(device);
}

extern "C" fn device_removed(raw: *mut c_void, _result: i32, _sender: *mut c_void, device: HidRef) {
    if raw.is_null() {
        return;
    }
    let context = unsafe { &*(raw as *const CallbackState) };
    let (edge, active) = {
        let mut devices = context.devices.lock().unwrap_or_else(|e| e.into_inner());
        let edge = devices.remove(device as usize);
        (edge, !devices.capable.is_empty())
    };
    if let Some(edge) = edge {
        (context.on_edge)(edge);
    }
    context.authoritative.store(active, Ordering::Release);
}

extern "C" fn input_value(raw: *mut c_void, result: i32, _sender: *mut c_void, value: Ref) {
    if raw.is_null() || value.is_null() || result != 0 {
        return;
    }
    let context = unsafe { &*(raw as *const CallbackState) };
    let element = unsafe { IOHIDValueGetElement(value) };
    if element.is_null()
        || unsafe {
            IOHIDElementGetUsagePage(element) != 0xff || IOHIDElementGetUsage(element) != 3
        }
    {
        return;
    }
    let device = unsafe { IOHIDElementGetDevice(element) };
    if device.is_null() {
        return;
    }
    let edge = {
        let mut devices = context.devices.lock().unwrap_or_else(|e| e.into_inner());
        // A value can race the device-matching notification at hotplug.
        devices.add(device as usize, false);
        devices.edge(device as usize, unsafe {
            IOHIDValueGetIntegerValue(value) != 0
        })
    };
    if !context.authoritative.swap(true, Ordering::AcqRel) {
        (context.on_edge)(false);
    }
    if let Some(edge) = edge {
        (context.on_edge)(edge);
    }
}

/// Runs on the native hotkey thread's existing CFRunLoop. No additional thread,
/// permission request, character subscription, or exclusive device grab is used.
pub(super) struct FnHidMonitor {
    manager: OwnedCf,
    runloop: Ref,
    context: *mut CallbackState,
}

impl FnHidMonitor {
    pub(super) fn start(
        runloop: Ref,
        authoritative: Arc<AtomicBool>,
        on_edge: Box<dyn Fn(bool)>,
    ) -> Option<Self> {
        // kIOHIDRequestTypeListenEvent=1; kIOHIDAccessTypeGranted=0.
        // Never open devices (or request access) when consent is unavailable.
        if unsafe { IOHIDCheckAccess(1) } != 0 {
            return None;
        }
        let manager = unsafe { OwnedCf::new(IOHIDManagerCreate(ptr::null(), 0)) }?;
        let keyboard = matching(b"DeviceUsagePage\0", 1, b"DeviceUsage\0", 6)?;
        let fn_element = matching(b"UsagePage\0", 0xff, b"Usage\0", 3)?;
        let context = Box::into_raw(Box::new(CallbackState {
            matching: fn_element,
            devices: Mutex::new(FnDevices::default()),
            authoritative,
            on_edge,
        }));
        unsafe {
            IOHIDManagerSetDeviceMatching(manager.0.cast_mut(), keyboard.0);
            IOHIDManagerSetInputValueMatching(manager.0.cast_mut(), (*context).matching.0);
            IOHIDManagerRegisterDeviceMatchingCallback(
                manager.0.cast_mut(),
                device_added,
                context.cast(),
            );
            IOHIDManagerRegisterDeviceRemovalCallback(
                manager.0.cast_mut(),
                device_removed,
                context.cast(),
            );
            IOHIDManagerRegisterInputValueCallback(
                manager.0.cast_mut(),
                input_value,
                context.cast(),
            );
            IOHIDManagerScheduleWithRunLoop(manager.0.cast_mut(), runloop, kCFRunLoopCommonModes);
        }
        let monitor = Self {
            manager,
            runloop,
            context,
        };
        if unsafe { IOHIDManagerOpen(monitor.manager.0.cast_mut(), 0) } != 0 {
            return None; // Drop unschedules and closes before releasing callback state.
        }
        if let Some(devices) =
            unsafe { OwnedCf::new(IOHIDManagerCopyDevices(monitor.manager.0.cast_mut())) }
        {
            let count = unsafe { CFSetGetCount(devices.0) };
            if (1..=256).contains(&count) {
                let mut devices_list = vec![ptr::null_mut(); count as usize];
                unsafe {
                    CFSetGetValues(devices.0, devices_list.as_mut_ptr());
                }
                for device in devices_list {
                    unsafe { &*context }.add_device(device);
                }
            }
        }
        Some(monitor)
    }
}

impl Drop for FnHidMonitor {
    fn drop(&mut self) {
        unsafe {
            // Both sources run on this same CFRunLoop: after unscheduling and
            // closing, no callback can access the context being released below.
            IOHIDManagerUnscheduleFromRunLoop(
                self.manager.0.cast_mut(),
                self.runloop,
                kCFRunLoopCommonModes,
            );
            IOHIDManagerClose(self.manager.0.cast_mut(), 0);
            // Keep callback context alive through the manager's final release.
            self.manager.release();
            let context = Box::from_raw(self.context);
            context.authoritative.store(false, Ordering::Release);
            let held = !context
                .devices
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .held
                .is_empty();
            if held {
                (context.on_edge)(false);
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::FnDevices;

    #[test]
    fn fn_edges_do_not_depend_on_any_quartz_event() {
        let mut devices = FnDevices::default();
        devices.add(1, false);
        assert_eq!(devices.edge(1, true), Some(true));
        assert_eq!(devices.edge(1, true), None);
        assert_eq!(devices.edge(1, false), Some(false));
        assert_eq!(devices.edge(1, false), None);
    }

    #[test]
    fn two_keyboards_produce_one_hold_until_both_release() {
        let mut devices = FnDevices::default();
        devices.add(1, false);
        devices.add(2, false);
        assert_eq!(devices.edge(1, true), Some(true));
        assert_eq!(devices.edge(2, true), None);
        assert_eq!(devices.edge(1, false), None);
        assert_eq!(devices.edge(2, false), Some(false));
    }

    #[test]
    fn disconnect_releases_a_held_fn_and_removes_authority() {
        let mut devices = FnDevices::default();
        devices.add(1, false);
        assert_eq!(devices.edge(1, true), Some(true));
        assert_eq!(devices.remove(1), Some(false));
        assert!(devices.capable.is_empty());
        assert_eq!(devices.edge(1, true), None);
    }

    #[test]
    fn fn_already_held_at_registration_does_not_create_a_press() {
        let mut devices = FnDevices::default();
        devices.add(1, true);
        assert_eq!(devices.edge(1, true), None);
        devices.add(1, false);
        assert_eq!(devices.edge(1, true), None);
        assert_eq!(devices.edge(1, false), Some(false));
        assert_eq!(devices.edge(1, true), Some(true));
    }

    #[test]
    fn hardware_fn_hold_drives_dictation_without_quartz_fn_events() {
        use super::super::{
            activate_pending_hold_base, dispatch_native_base_edge, monitored_bindings_for_base,
            NativeComboState, NativeHotkeyBinding, NativeHotkeyHandler, NativeHotkeyTrigger,
        };
        use crate::hotkey::HotkeyRole;
        use std::sync::{Arc, Mutex};
        use tauri_plugin_global_shortcut::ShortcutState;

        let bindings = monitored_bindings_for_base(
            vec![
                NativeHotkeyBinding {
                    role: HotkeyRole::Dictation,
                    index: 0,
                    trigger: NativeHotkeyTrigger::Fn,
                },
                NativeHotkeyBinding {
                    role: HotkeyRole::Ask,
                    index: 0,
                    trigger: NativeHotkeyTrigger::FnSpace,
                },
            ],
            NativeHotkeyTrigger::Fn,
        );
        let events = Arc::new(Mutex::new(Vec::new()));
        let recorded = Arc::clone(&events);
        let handler: NativeHotkeyHandler = Arc::new(move |event| {
            recorded.lock().unwrap().push((event.role, event.state));
        });
        let mut devices = FnDevices::default();
        devices.add(1, false);
        let mut state = NativeComboState {
            hold_base: true,
            ..Default::default()
        };
        // This machine emits HID Fn down/up but no Quartz keycode 63 at all.
        let down = devices.edge(1, true).unwrap();
        dispatch_native_base_edge(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            down,
            &handler,
        );
        let generation = state.edge_generation;
        assert!(activate_pending_hold_base(
            &mut state,
            &bindings,
            NativeHotkeyTrigger::Fn,
            generation,
            &handler
        ));
        let up = devices.edge(1, false).unwrap();
        dispatch_native_base_edge(&mut state, &bindings, NativeHotkeyTrigger::Fn, up, &handler);
        assert_eq!(
            *events.lock().unwrap(),
            vec![
                (HotkeyRole::Dictation, ShortcutState::Pressed),
                (HotkeyRole::Dictation, ShortcutState::Released),
            ]
        );
    }
}
