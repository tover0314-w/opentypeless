#[cfg(any(target_os = "macos", test))]
#[derive(Default)]
struct CaptureGeneration(std::sync::atomic::AtomicU64);

#[cfg(any(target_os = "macos", test))]
impl CaptureGeneration {
    fn advance(&self) {
        self.0.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
    }

    fn snapshot(&self) -> u64 {
        self.0.load(std::sync::atomic::Ordering::SeqCst)
    }

    fn cleanup_if_current(&self, generation: u64, cleanup: impl FnOnce()) {
        if self.snapshot() == generation {
            cleanup();
        }
    }
}

#[cfg(target_os = "macos")]
static CAPTURE_GENERATION: CaptureGeneration =
    CaptureGeneration(std::sync::atomic::AtomicU64::new(0));

#[cfg(target_os = "macos")]
mod platform {
    use block2::RcBlock;
    use objc2::msg_send;
    use objc2::runtime::{AnyClass, AnyObject};
    use std::cell::RefCell;
    use tauri::{Emitter, Manager};

    struct LocalMonitor {
        capture_id: String,
        monitor: *mut AnyObject,
    }

    thread_local! {
        static MONITOR: RefCell<Option<LocalMonitor>> = const { RefCell::new(None) };
    }

    fn remove_monitor(monitor: LocalMonitor) {
        if let Some(event_class) = AnyClass::get("NSEvent") {
            unsafe {
                let _: () = msg_send![event_class, removeMonitor: monitor.monitor];
                let _: () = msg_send![monitor.monitor, release];
            }
        }
    }

    pub fn start<R: tauri::Runtime>(
        app: &tauri::AppHandle<R>,
        capture_id: String,
    ) -> Result<(), String> {
        let main = app
            .get_webview_window("main")
            .ok_or("Local shortcut capture main window unavailable")?;
        super::ensure_capture_focus(main.is_focused().unwrap_or(false))?;
        let event_class = AnyClass::get("NSEvent").ok_or("Local shortcut capture unavailable")?;
        super::CAPTURE_GENERATION.advance();
        MONITOR.with(|slot| {
            if let Some(previous) = slot.borrow_mut().take() {
                remove_monitor(previous);
            }
        });
        let handle = app.clone();
        let owner = capture_id.clone();
        let block = RcBlock::new(move |event: *mut AnyObject| -> *mut AnyObject {
            if event.is_null() {
                return event;
            }
            let active = MONITOR.with(|slot| {
                slot.borrow()
                    .as_ref()
                    .is_some_and(|monitor| monitor.capture_id == owner)
            });
            if !active {
                return event;
            }
            let Some(window) = handle.get_webview_window("main") else {
                return event;
            };
            if !window.is_focused().unwrap_or(false) {
                return event;
            }
            let keycode: u16 = unsafe { msg_send![event, keyCode] };
            if keycode != 63 {
                return event;
            }
            let flags: usize = unsafe { msg_send![event, modifierFlags] };
            let _ = window.emit(
                "shortcut-capture:fn",
                serde_json::json!({
                    "captureId": owner,
                    "pressed": flags & (1 << 23) != 0
                }),
            );
            event
        });
        let monitor: *mut AnyObject = unsafe {
            msg_send![event_class, addLocalMonitorForEventsMatchingMask: (1usize << 12), handler: &*block]
        };
        if monitor.is_null() {
            return Err("Local shortcut capture unavailable".into());
        }
        unsafe {
            let _: *mut AnyObject = msg_send![monitor, retain];
        }
        MONITOR.with(|slot| {
            *slot.borrow_mut() = Some(LocalMonitor {
                capture_id,
                monitor,
            });
        });
        Ok(())
    }

    pub fn stop(capture_id: &str) {
        MONITOR.with(|slot| {
            let mut slot = slot.borrow_mut();
            if slot
                .as_ref()
                .is_some_and(|monitor| monitor.capture_id == capture_id)
            {
                if let Some(monitor) = slot.take() {
                    remove_monitor(monitor);
                }
            }
        });
    }

    pub fn stop_all() {
        let monitor = MONITOR.with(|slot| slot.borrow_mut().take());
        if let Some(monitor) = monitor {
            remove_monitor(monitor);
        }
    }
}

fn capture_ends_for_window_event(label: &str, event: &tauri::WindowEvent) -> bool {
    label == "main"
        && matches!(
            event,
            tauri::WindowEvent::Focused(false)
                | tauri::WindowEvent::CloseRequested { .. }
                | tauri::WindowEvent::Destroyed
        )
}

pub fn stop_for_window_event<R: tauri::Runtime>(
    app: &tauri::AppHandle<R>,
    label: &str,
    event: &tauri::WindowEvent,
) -> Option<crate::shortcut_lease::CaptureLease> {
    if !capture_ends_for_window_event(label, event) {
        return None;
    }
    let released =
        if let Some(leases) = tauri::Manager::try_state::<crate::hotkey::HotkeySupervisor>(app) {
            leases.release_capture_window(label)
        } else {
            None
        };
    #[cfg(target_os = "macos")]
    {
        let generation = CAPTURE_GENERATION.snapshot();
        if app
            .run_on_main_thread(move || {
                CAPTURE_GENERATION.cleanup_if_current(generation, platform::stop_all);
            })
            .is_err()
        {
            tracing::warn!("Failed to stop local shortcut capture after window event");
        }
    }
    #[cfg(not(target_os = "macos"))]
    let _ = app;
    released
}

fn ensure_capture_request(label: &str, capture_id: &str) -> Result<(), String> {
    if label != "main" {
        return Err("Shortcut capture is only allowed from the main window".into());
    }
    if uuid::Uuid::parse_str(capture_id).is_err() {
        return Err("Invalid shortcut capture identity".into());
    }
    Ok(())
}

fn ensure_capture_focus(focused: bool) -> Result<(), String> {
    if focused {
        Ok(())
    } else {
        Err("Local shortcut capture requires main window focus".into())
    }
}

#[tauri::command]
pub async fn start_shortcut_capture<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    capture_id: String,
) -> Result<(), String> {
    ensure_capture_request(window.label(), &capture_id)?;
    ensure_capture_focus(window.is_focused().unwrap_or(false))?;
    #[cfg(target_os = "macos")]
    {
        let (send, receive) = tokio::sync::oneshot::channel();
        let handle = app.clone();
        app.run_on_main_thread(move || {
            let _ = send.send(platform::start(&handle, capture_id));
        })
        .map_err(|_| "Local shortcut capture unavailable".to_string())?;
        receive
            .await
            .map_err(|_| "Local shortcut capture unavailable".to_string())?
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = app;
        Ok(())
    }
}

#[tauri::command]
pub async fn stop_shortcut_capture<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    capture_id: String,
) -> Result<(), String> {
    ensure_capture_request(window.label(), &capture_id)?;
    #[cfg(target_os = "macos")]
    {
        let (send, receive) = tokio::sync::oneshot::channel();
        app.run_on_main_thread(move || {
            platform::stop(&capture_id);
            let _ = send.send(());
        })
        .map_err(|_| "Local shortcut capture cleanup unavailable".to_string())?;
        receive
            .await
            .map_err(|_| "Local shortcut capture cleanup unavailable".to_string())?;
    }
    #[cfg(not(target_os = "macos"))]
    let _ = app;
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delayed_cleanup_preserves_a_new_capture() {
        let generation = CaptureGeneration::default();
        generation.advance();
        let cleanup_generation = generation.snapshot();
        generation.advance();
        let removed = std::cell::Cell::new(false);
        generation.cleanup_if_current(cleanup_generation, || removed.set(true));
        assert!(!removed.get());
    }

    #[test]
    fn cleanup_removes_the_capture_it_was_scheduled_for() {
        let generation = CaptureGeneration::default();
        generation.advance();
        let removed = std::cell::Cell::new(false);
        generation.cleanup_if_current(generation.snapshot(), || removed.set(true));
        assert!(removed.get());
    }

    #[test]
    fn local_capture_start_requires_current_main_window_focus() {
        assert!(ensure_capture_focus(false).is_err());
        assert!(ensure_capture_focus(true).is_ok());
    }

    #[test]
    fn only_main_focus_loss_or_destruction_ends_local_capture() {
        assert!(capture_ends_for_window_event(
            "main",
            &tauri::WindowEvent::Focused(false)
        ));
        assert!(capture_ends_for_window_event(
            "main",
            &tauri::WindowEvent::Destroyed
        ));
        assert!(!capture_ends_for_window_event(
            "main",
            &tauri::WindowEvent::Focused(true)
        ));
        for label in ["capsule", "ask", "unknown"] {
            assert!(!capture_ends_for_window_event(
                label,
                &tauri::WindowEvent::Focused(false)
            ));
        }
    }

    #[test]
    fn auxiliary_capture_ipc_is_denied_before_installing_a_local_monitor() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![
                start_shortcut_capture,
                stop_shortcut_capture
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask", "unknown"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for command in ["start_shortcut_capture", "stop_shortcut_capture"] {
                let result = tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: command.into(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "captureId": uuid::Uuid::new_v4().to_string()
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                assert_eq!(
                    result.unwrap_err(),
                    serde_json::json!("Shortcut capture is only allowed from the main window")
                );
            }
        }
    }

    #[test]
    fn local_capture_only_accepts_main_with_a_valid_identity() {
        let identity = uuid::Uuid::new_v4().to_string();
        assert!(ensure_capture_request("main", &identity).is_ok());
        for label in ["capsule", "ask", "unknown"] {
            assert!(ensure_capture_request(label, &identity).is_err());
        }
        for identity in ["", "secret", "1"] {
            assert!(ensure_capture_request("main", identity).is_err());
        }
    }
}
