use std::sync::Mutex;
use std::time::{Duration, Instant};
use tauri::{Emitter, Manager};

const REQUEST_LIFETIME: Duration = Duration::from_secs(30);

pub(crate) fn ensure_local_window<R: tauri::Runtime>(
    window: &tauri::WebviewWindow<R>,
) -> Result<(), String> {
    let location = window.url().map_err(|error| error.to_string())?;
    let bundled = matches!(
        (location.scheme(), location.host_str(), location.port()),
        ("tauri", Some("localhost"), None) | ("http" | "https", Some("tauri.localhost"), None)
    ) && location.username().is_empty()
        && location.password().is_none();
    let development = cfg!(debug_assertions)
        && window
            .app_handle()
            .config()
            .build
            .dev_url
            .as_ref()
            .is_some_and(|origin| location.origin() == origin.origin());
    if bundled || development {
        Ok(())
    } else {
        Err("Update install authorization requires local application content".into())
    }
}

#[derive(Default)]
pub(crate) struct UpdateInstallRequests(Mutex<Option<(String, Instant)>>);

impl UpdateInstallRequests {
    fn issue(&self) -> String {
        let request_id = uuid::Uuid::new_v4().to_string();
        *self.0.lock().unwrap_or_else(|error| error.into_inner()) =
            Some((request_id.clone(), Instant::now()));
        request_id
    }

    fn consume(&self, request_id: &str) -> bool {
        let mut pending = self.0.lock().unwrap_or_else(|error| error.into_inner());
        let accepted = pending.as_ref().is_some_and(|(identity, issued_at)| {
            identity == request_id && issued_at.elapsed() < REQUEST_LIFETIME
        });
        if accepted {
            pending.take();
        }
        accepted
    }
}

#[tauri::command]
pub(crate) fn request_update_install<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    requests: tauri::State<'_, UpdateInstallRequests>,
) -> Result<(), String> {
    if window.label() != "capsule" {
        return Err("Update install requests are only available in the capsule".into());
    }
    ensure_local_window(&window)?;
    let main = window
        .app_handle()
        .get_webview_window("main")
        .ok_or("Main update window is unavailable")?;
    ensure_local_window(&main)?;
    let request_id = requests.issue();
    if let Err(error) = main.emit("updater:quit-and-install", &request_id) {
        requests.consume(&request_id);
        return Err(error.to_string());
    }
    Ok(())
}

#[tauri::command]
pub(crate) fn consume_update_install_request<R: tauri::Runtime>(
    window: tauri::WebviewWindow<R>,
    requests: tauri::State<'_, UpdateInstallRequests>,
    request_id: String,
) -> Result<bool, String> {
    if window.label() != "main" {
        return Err("Update install authorization is only available in the main window".into());
    }
    ensure_local_window(&window)?;
    Ok(requests.consume(&request_id))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn issued_request_is_single_use_and_invalid_requests_do_not_revoke_it() {
        let requests = UpdateInstallRequests::default();
        let request_id = requests.issue();
        assert!(!requests.consume("forged-request"));
        assert!(requests.consume(&request_id));
        assert!(!requests.consume(&request_id));
    }

    #[test]
    fn new_request_replaces_previous_and_expired_requests_cannot_install() {
        let requests = UpdateInstallRequests::default();
        let previous = requests.issue();
        let current = requests.issue();
        assert!(!requests.consume(&previous));
        *requests.0.lock().unwrap() = Some((current.clone(), Instant::now() - REQUEST_LIFETIME));
        assert!(!requests.consume(&current));
    }

    #[test]
    fn simultaneous_consumers_can_accept_an_authorization_only_once() {
        let requests = std::sync::Arc::new(UpdateInstallRequests::default());
        let request_id = requests.issue();
        let workers: Vec<_> = (0..8)
            .map(|_| {
                let requests = requests.clone();
                let request_id = request_id.clone();
                std::thread::spawn(move || requests.consume(&request_id))
            })
            .collect();
        let accepted = workers
            .into_iter()
            .map(|worker| usize::from(worker.join().unwrap()))
            .sum::<usize>();
        assert_eq!(accepted, 1);
    }

    #[test]
    fn capsule_request_emits_a_consumable_native_authorization_to_main() {
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .manage(UpdateInstallRequests::default())
            .invoke_handler(tauri::generate_handler![request_update_install])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let main = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let capsule = tauri::WebviewWindowBuilder::new(&app, "capsule", Default::default())
            .build()
            .unwrap();
        let (sender, receiver) = std::sync::mpsc::channel();
        let listener = main.listen("updater:quit-and-install", move |event| {
            sender.send(event.payload().to_string()).unwrap();
        });
        let result = tauri::test::get_ipc_response(
            &capsule,
            tauri::webview::InvokeRequest {
                cmd: "request_update_install".into(),
                callback: tauri::ipc::CallbackFn(0),
                error: tauri::ipc::CallbackFn(1),
                url: "tauri://localhost".parse().unwrap(),
                body: tauri::ipc::InvokeBody::Json(serde_json::json!({})),
                headers: Default::default(),
                invoke_key: tauri::test::INVOKE_KEY.into(),
            },
        );
        assert!(result.is_ok());
        let payload = receiver.recv_timeout(Duration::from_secs(1)).unwrap();
        let request_id: String = serde_json::from_str(&payload).unwrap();
        assert!(app.state::<UpdateInstallRequests>().consume(&request_id));
        assert!(!app.state::<UpdateInstallRequests>().consume(&request_id));
        main.unlisten(listener);
    }

    #[test]
    fn ipc_uses_actual_window_identity_for_issuance_and_consumption() {
        let app = tauri::test::mock_builder()
            .manage(UpdateInstallRequests::default())
            .invoke_handler(tauri::generate_handler![
                request_update_install,
                consume_update_install_request
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["main", "capsule", "ask", "unknown"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for command in ["request_update_install", "consume_update_install_request"] {
                let request_id = app.state::<UpdateInstallRequests>().issue();
                let result = tauri::test::get_ipc_response(
                    &window,
                    tauri::webview::InvokeRequest {
                        cmd: command.into(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "requestId": request_id,
                            "window": "main"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.into(),
                    },
                );
                let allowed = if command == "request_update_install" {
                    label == "capsule"
                } else {
                    label == "main"
                };
                assert_eq!(result.is_ok(), allowed, "{command} from {label}");
                if command == "consume_update_install_request" && allowed {
                    assert!(result.unwrap().deserialize::<bool>().unwrap());
                    assert!(!app.state::<UpdateInstallRequests>().consume(&request_id));
                }
            }
        }
    }

    #[test]
    fn remote_content_in_named_windows_cannot_issue_or_consume_requests() {
        let app = tauri::test::mock_builder()
            .manage(UpdateInstallRequests::default())
            .invoke_handler(tauri::generate_handler![
                request_update_install,
                consume_update_install_request
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for (label, command) in [
            ("main", "consume_update_install_request"),
            ("capsule", "request_update_install"),
        ] {
            let window = tauri::WebviewWindowBuilder::new(
                &app,
                label,
                tauri::WebviewUrl::External("https://untrusted.example.test/".parse().unwrap()),
            )
            .build()
            .unwrap();
            let request_id = app.state::<UpdateInstallRequests>().issue();
            let result = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: command.into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "https://untrusted.example.test/".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "requestId": request_id
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            assert!(result.is_err());
            assert!(app.state::<UpdateInstallRequests>().consume(&request_id));
        }
    }
}
