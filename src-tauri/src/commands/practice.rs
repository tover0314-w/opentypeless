use crate::practice::{PracticeSnapshot, PracticeState};
use tauri::Manager;

fn ensure_main(label: &str) -> Result<(), String> {
    if label == "main" {
        Ok(())
    } else {
        Err("Practice is only available from the main window".into())
    }
}

#[tauri::command]
pub async fn start_practice(
    window: tauri::Window,
    app: tauri::AppHandle,
    options: Option<crate::practice::PracticeOptions>,
) -> Result<String, String> {
    ensure_main(window.label())?;
    tauri::async_runtime::spawn_blocking(move || match options {
        Some(options) => crate::practice::start_with_options(app, options),
        None => crate::practice::start(app),
    })
    .await
    .map_err(|_| "Practice startup worker unavailable".to_string())?
}

#[tauri::command]
pub fn stop_practice<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    session: String,
) -> Result<(), String> {
    ensure_main(window.label())?;
    app.try_state::<PracticeState>()
        .ok_or("Practice state is unavailable")?
        .stop(&session)
}

#[tauri::command]
pub fn cancel_practice<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    session: String,
) -> Result<(), String> {
    ensure_main(window.label())?;
    app.try_state::<PracticeState>()
        .ok_or("Practice state is unavailable")?
        .cancel(&session)
}

#[tauri::command]
pub fn get_practice<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    session: String,
) -> Result<PracticeSnapshot, String> {
    ensure_main(window.label())?;
    app.try_state::<PracticeState>()
        .ok_or("Practice state is unavailable")?
        .snapshot(&session)
}

#[cfg(test)]
mod tests {
    #[test]
    fn missing_practice_state_returns_an_error_without_panicking() {
        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        for command in [super::stop_practice, super::cancel_practice] {
            let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
                command(
                    window.as_ref().window().clone(),
                    app.handle().clone(),
                    "synthetic-session".into(),
                )
            }));
            assert_eq!(
                outcome.unwrap().err().unwrap(),
                "Practice state is unavailable"
            );
        }
        let outcome = std::panic::catch_unwind(std::panic::AssertUnwindSafe(|| {
            super::get_practice(
                window.as_ref().window().clone(),
                app.handle().clone(),
                "synthetic-session".into(),
            )
        }));
        assert_eq!(
            outcome.unwrap().err().unwrap(),
            "Practice state is unavailable"
        );
    }

    #[test]
    fn practice_session_commands_enforce_window_identity_and_remote_origin_at_ipc() {
        let app = tauri::test::mock_builder()
            .manage(crate::practice::PracticeState::default())
            .invoke_handler(tauri::generate_handler![
                super::stop_practice,
                super::cancel_practice,
                super::get_practice
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["main", "capsule", "ask", "unknown"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for origin in ["tauri://localhost", "https://untrusted.example.test/"] {
                for command in ["stop_practice", "cancel_practice", "get_practice"] {
                    let result = tauri::test::get_ipc_response(
                        &window,
                        tauri::webview::InvokeRequest {
                            cmd: command.into(),
                            callback: tauri::ipc::CallbackFn(0),
                            error: tauri::ipc::CallbackFn(1),
                            url: origin.parse().unwrap(),
                            body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                                "session": "synthetic-nonexistent-session",
                                "window": "main"
                            })),
                            headers: Default::default(),
                            invoke_key: tauri::test::INVOKE_KEY.into(),
                        },
                    );
                    let error = result.unwrap_err();
                    let message = error.as_str().unwrap();
                    if origin.starts_with("https:") {
                        assert!(message.contains("not allowed"));
                    } else if label == "main" {
                        assert_eq!(message, "practice_stopped");
                    } else {
                        assert_eq!(message, "Practice is only available from the main window");
                    }
                }
            }
        }
    }

    #[test]
    fn practice_start_does_not_wait_for_session_locks_on_the_command_thread() {
        let source = include_str!("practice.rs");
        let body = source
            .split("#[tauri::command]")
            .nth(1)
            .unwrap()
            .split("\n}\n")
            .next()
            .unwrap();
        assert!(body.contains("pub async fn start_practice"));
        assert!(body.contains("spawn_blocking"));
        assert!(
            body.find("ensure_main(window.label())?").unwrap()
                < body.find("spawn_blocking").unwrap()
        );
    }

    #[test]
    fn practice_start_resolves_required_state_before_claiming_voice_resources() {
        let source = include_str!("../practice.rs");
        let start = source
            .split("\npub fn start_with_options(")
            .nth(1)
            .unwrap()
            .split("\nasync fn run(")
            .next()
            .unwrap();
        let guard = start.find("pipeline.try_voice_start_guard()").unwrap();
        for required in [
            ".try_state::<PracticeState>()",
            ".try_state::<crate::pipeline::PipelineHandle>()",
            ".try_state::<crate::commands::ask::AskDictationState>()",
            ".try_state::<crate::SessionTokenStore>()",
        ] {
            assert!(
                start
                    .find(required)
                    .is_some_and(|position| position < guard),
                "{required} must be checked before reserving voice resources"
            );
        }
    }

    #[test]
    fn auxiliary_windows_cannot_use_practice() {
        assert!(super::ensure_main("main").is_ok());
        for label in ["capsule", "ask", "unknown"] {
            assert!(super::ensure_main(label).is_err());
        }
    }
}
