use super::{AudioCaptureHandle, AudioConfig, CaptureState};
use std::sync::Mutex;
use tauri::Manager;

#[derive(Default)]
pub struct MicrophonePreview {
    session: Mutex<PreviewSession>,
}

#[derive(Default)]
struct PreviewSession {
    generation: u64,
    capture: Option<AudioCaptureHandle>,
}

impl MicrophonePreview {
    pub fn stop(&self, expected: Option<u64>) {
        let mut session = self
            .session
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if expected.is_some_and(|generation| generation != session.generation) {
            return;
        }
        session.generation += 1;
        if let Some(mut capture) = session.capture.take() {
            capture.stop();
        }
    }

    pub fn level(&self, generation: u64) -> Result<f32, String> {
        let session = self
            .session
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if generation != session.generation {
            return Err("microphone_preview_stopped".to_string());
        }
        let capture = session
            .capture
            .as_ref()
            .ok_or_else(|| "microphone_preview_stopped".to_string())?;
        if capture.state() != CaptureState::Recording {
            return Err("microphone_preview_stopped".to_string());
        }
        Ok(capture.get_volume().clamp(0.0, 1.0))
    }
}

pub fn stop_for_voice_operation(app: &tauri::AppHandle) {
    if let Some(preview) = app.try_state::<MicrophonePreview>() {
        preview.stop(None);
    }
}

#[tauri::command]
pub async fn start_microphone_preview<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    device: String,
) -> Result<u64, String> {
    ensure_preview_window(window.label())?;
    let _guard = app
        .state::<crate::pipeline::PipelineHandle>()
        .try_voice_start_guard()
        .map_err(|_| "microphone_preview_busy".to_string())?;
    let preview = app.state::<MicrophonePreview>();
    preview.stop(None);
    let busy = app
        .state::<crate::pipeline::PipelineHandle>()
        .current_state()
        != crate::pipeline::PipelineState::Idle
        || app
            .state::<crate::commands::ask::AskDictationState>()
            .is_busy();
    if busy {
        return Err("microphone_preview_busy".to_string());
    }
    let generation = preview
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .generation;
    if !device.is_empty() {
        let devices = tauri::async_runtime::spawn_blocking(super::list_input_devices)
            .await
            .map_err(|error| error.to_string())?;
        if !devices.iter().any(|item| item.name == device) {
            return Err("microphone_preview_device_missing".to_string());
        }
    }
    let (mut capture, mut chunks) = AudioCaptureHandle::start(AudioConfig::for_device(&device))
        .map_err(|error| error.to_string())?;
    tauri::async_runtime::spawn(async move { while chunks.recv().await.is_some() {} });
    let readiness = tokio::select! {
        result = tokio::time::timeout(super::STARTUP_TIMEOUT, capture.wait_until_ready()) => Some(result),
        _ = async {
            loop {
                tokio::time::sleep(std::time::Duration::from_millis(50)).await;
                let cancelled = preview.session.lock()
                    .unwrap_or_else(|error| error.into_inner()).generation != generation;
                if cancelled {
                    break;
                }
            }
        } => None,
    };
    match readiness {
        Some(Ok(Ok(_))) => {}
        Some(Ok(Err(error))) => {
            capture.stop();
            return Err(error.to_string());
        }
        Some(Err(_)) => {
            capture.stop();
            return Err("microphone_preview_timeout".to_string());
        }
        None => {
            capture.stop();
            return Err("microphone_preview_stopped".to_string());
        }
    }
    let mut session = preview
        .session
        .lock()
        .unwrap_or_else(|error| error.into_inner());
    let busy = app
        .state::<crate::pipeline::PipelineHandle>()
        .current_state()
        != crate::pipeline::PipelineState::Idle
        || app
            .state::<crate::commands::ask::AskDictationState>()
            .is_busy();
    if session.generation != generation || busy {
        capture.stop();
        return Err("microphone_preview_stopped".to_string());
    }
    session.capture = Some(capture);
    drop(session);
    let timeout_app = app.clone();
    tauri::async_runtime::spawn(async move {
        tokio::time::sleep(std::time::Duration::from_secs(120)).await;
        timeout_app
            .state::<MicrophonePreview>()
            .stop(Some(generation));
    });
    Ok(generation)
}

#[tauri::command]
pub fn get_microphone_preview_level<R: tauri::Runtime>(
    window: tauri::Window<R>,
    state: tauri::State<'_, MicrophonePreview>,
    session: u64,
) -> Result<f32, String> {
    ensure_preview_window(window.label())?;
    state.level(session)
}

#[tauri::command]
pub fn stop_microphone_preview<R: tauri::Runtime>(
    window: tauri::Window<R>,
    state: tauri::State<'_, MicrophonePreview>,
    session: u64,
) -> Result<(), String> {
    ensure_preview_window(window.label())?;
    state.stop(Some(session));
    Ok(())
}

fn ensure_preview_window(label: &str) -> Result<(), String> {
    if label == "main" {
        Ok(())
    } else {
        Err("Microphone preview is only available from the main window".into())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn auxiliary_preview_start_is_rejected_before_accessing_audio_state() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![start_microphone_preview])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let response = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "start_microphone_preview".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "device": "",
                        "window": "main"
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            assert_eq!(
                response.unwrap_err(),
                serde_json::json!("Microphone preview is only available from the main window")
            );
        }
    }

    #[test]
    fn auxiliary_windows_cannot_stop_the_main_microphone_preview() {
        let app = tauri::test::mock_builder()
            .manage(MicrophonePreview::default())
            .invoke_handler(tauri::generate_handler![stop_microphone_preview])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let window = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            let response = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "stop_microphone_preview".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({ "session": 0 })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            );
            assert!(response.is_err());
            assert_eq!(
                app.state::<MicrophonePreview>()
                    .session
                    .lock()
                    .unwrap()
                    .generation,
                0
            );
        }
    }

    #[test]
    fn stale_stop_cannot_cancel_a_new_preview() {
        let preview = MicrophonePreview::default();
        preview.stop(None);
        preview.stop(Some(0));
        assert_eq!(preview.session.lock().unwrap().generation, 1);
        preview.stop(Some(1));
        assert_eq!(preview.session.lock().unwrap().generation, 2);
    }

    #[test]
    fn preview_stop_preserves_main_access_and_rejects_remote_origins() {
        let app = tauri::test::mock_builder()
            .manage(MicrophonePreview::default())
            .invoke_handler(tauri::generate_handler![stop_microphone_preview])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        for origin in ["https://untrusted.example.test/", "tauri://localhost"] {
            let response = tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "stop_microphone_preview".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: origin.parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "session": 0,
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
                assert_eq!(
                    app.state::<MicrophonePreview>()
                        .session
                        .lock()
                        .unwrap()
                        .generation,
                    0
                );
            } else {
                assert!(response.is_ok());
                assert_eq!(
                    app.state::<MicrophonePreview>()
                        .session
                        .lock()
                        .unwrap()
                        .generation,
                    1
                );
            }
        }
    }

    #[test]
    fn level_is_unavailable_without_a_matching_live_capture() {
        let preview = MicrophonePreview::default();
        assert!(preview.level(0).is_err());
        preview.stop(None);
        assert!(preview.level(0).is_err());
        assert!(preview.level(1).is_err());
    }
}
