use crate::hotkey::HotkeyRole;
use crate::practice::PracticeMode;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Arc;
use std::sync::Mutex;
use tauri::Manager;
use tauri_plugin_global_shortcut::ShortcutState;

#[derive(Clone, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct PracticeShortcutEdge {
    lease_id: String,
    pressed: bool,
}

#[derive(Clone)]
struct Lease {
    id: String,
    mode: PracticeMode,
    channel: tauri::ipc::Channel<PracticeShortcutEdge>,
    held: Arc<AtomicBool>,
}

#[derive(Default)]
pub(crate) struct PracticeShortcutLease(Mutex<Option<Lease>>);

impl PracticeShortcutLease {
    fn register(
        &self,
        id: String,
        mode: PracticeMode,
        channel: tauri::ipc::Channel<PracticeShortcutEdge>,
    ) -> Result<(), String> {
        uuid::Uuid::parse_str(&id).map_err(|_| "Invalid practice shortcut lease")?;
        *self.0.lock().unwrap_or_else(|error| error.into_inner()) = Some(Lease {
            id,
            mode,
            channel,
            held: Arc::new(AtomicBool::new(false)),
        });
        Ok(())
    }

    pub(crate) fn revoke(&self, id: Option<&str>) {
        let mut lease = self.0.lock().unwrap_or_else(|error| error.into_inner());
        if id.is_none()
            || lease
                .as_ref()
                .is_some_and(|lease| Some(lease.id.as_str()) == id)
        {
            lease.take();
        }
    }

    fn dispatch(&self, role: HotkeyRole, state: ShortcutState, focused: bool) -> Option<bool> {
        let lease = self
            .0
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .clone()?;
        let expected = match lease.mode {
            PracticeMode::Dictate => HotkeyRole::Dictation,
            PracticeMode::Translate => HotkeyRole::TranslateSelection,
            PracticeMode::Ask => HotkeyRole::Ask,
        };
        if role != expected || !focused {
            return Some(false);
        }
        let pressed = state == ShortcutState::Pressed;
        if lease.held.swap(pressed, Ordering::SeqCst) == pressed {
            return Some(pressed);
        }
        let accepted = lease
            .channel
            .send(PracticeShortcutEdge {
                lease_id: lease.id.clone(),
                pressed,
            })
            .is_ok();
        if !accepted {
            lease.held.store(false, Ordering::SeqCst);
        }
        Some(accepted)
    }
}

#[tauri::command]
pub(crate) fn register_practice_shortcut<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    lease_id: String,
    mode: PracticeMode,
    channel: tauri::ipc::Channel<PracticeShortcutEdge>,
) -> Result<(), String> {
    if window.label() != "main" {
        return Err("Practice shortcuts are only available from the main window".into());
    }
    window
        .app_handle()
        .try_state::<PracticeShortcutLease>()
        .ok_or("Practice shortcut state is unavailable")?
        .register(lease_id, mode, channel)
}

#[tauri::command]
pub(crate) fn unregister_practice_shortcut<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    lease_id: String,
) -> Result<(), String> {
    if window.label() != "main" {
        return Err("Practice shortcuts are only available from the main window".into());
    }
    window
        .app_handle()
        .try_state::<PracticeShortcutLease>()
        .ok_or("Practice shortcut state is unavailable")?
        .revoke(Some(&lease_id));
    Ok(())
}

pub(crate) fn route(
    app: &tauri::AppHandle,
    role: HotkeyRole,
    state: ShortcutState,
) -> Option<bool> {
    let lease = app.try_state::<PracticeShortcutLease>()?;
    let focused = app
        .get_webview_window("main")
        .is_some_and(|window| window.is_focused().unwrap_or(false));
    lease.dispatch(role, state, focused)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::Arc;

    #[test]
    fn lease_commands_reject_auxiliary_windows_and_remote_origins() {
        let app = tauri::test::mock_builder()
            .manage(PracticeShortcutLease::default())
            .invoke_handler(tauri::generate_handler![
                register_practice_shortcut,
                unregister_practice_shortcut
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["main", "capsule", "ask", "unknown"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for origin in ["tauri://localhost", "https://untrusted.example.test/"] {
                let response = tauri::test::get_ipc_response(
                    &window,
                    tauri::webview::InvokeRequest {
                        cmd: "unregister_practice_shortcut".into(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: origin.parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "leaseId": "synthetic-unknown-lease",
                            "window": "main"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.into(),
                    },
                );
                if origin.starts_with("https:") {
                    assert!(response
                        .unwrap_err()
                        .as_str()
                        .unwrap()
                        .contains("not allowed"));
                } else if label == "main" {
                    assert!(response.is_ok());
                } else {
                    assert_eq!(
                        response.unwrap_err(),
                        serde_json::json!(
                            "Practice shortcuts are only available from the main window"
                        )
                    );
                }
            }
        }
    }

    #[test]
    fn active_lease_routes_only_its_role_and_rejects_unfocused_input() {
        let lease = PracticeShortcutLease::default();
        let edges = Arc::new(Mutex::new(Vec::new()));
        let captured = edges.clone();
        let channel = tauri::ipc::Channel::new(move |body| {
            captured
                .lock()
                .unwrap()
                .push(body.deserialize::<serde_json::Value>()?);
            Ok(())
        });
        let id = uuid::Uuid::new_v4().to_string();
        lease
            .register(id.clone(), PracticeMode::Translate, channel)
            .unwrap();
        let role = HotkeyRole::TranslateSelection;
        assert_eq!(
            lease.dispatch(HotkeyRole::Dictation, ShortcutState::Pressed, true),
            Some(false)
        );
        assert_eq!(
            lease.dispatch(role, ShortcutState::Pressed, false),
            Some(false)
        );
        assert_eq!(
            lease.dispatch(role, ShortcutState::Pressed, true),
            Some(true)
        );
        assert_eq!(
            lease.dispatch(role, ShortcutState::Pressed, true),
            Some(true)
        );
        assert_eq!(
            lease.dispatch(role, ShortcutState::Released, true),
            Some(true)
        );
        assert_eq!(edges.lock().unwrap().len(), 2);
        assert_eq!(
            lease.dispatch(role, ShortcutState::Released, true),
            Some(false)
        );
        assert_eq!(edges.lock().unwrap().len(), 2);
        lease.revoke(Some("retired-lease"));
        assert_eq!(
            lease.dispatch(HotkeyRole::Ask, ShortcutState::Pressed, true),
            Some(false)
        );
        lease.revoke(Some(&id));
        assert_eq!(
            lease.dispatch(HotkeyRole::Dictation, ShortcutState::Pressed, true),
            None
        );
    }
}
