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
) -> Result<String, String> {
    ensure_main(window.label())?;
    tauri::async_runtime::spawn_blocking(move || crate::practice::start(app))
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
    app.state::<PracticeState>().stop(&session)
}

#[tauri::command]
pub fn cancel_practice<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    session: String,
) -> Result<(), String> {
    ensure_main(window.label())?;
    app.state::<PracticeState>().cancel(&session)
}

#[tauri::command]
pub fn get_practice<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    session: String,
) -> Result<PracticeSnapshot, String> {
    ensure_main(window.label())?;
    app.state::<PracticeState>().snapshot(&session)
}

#[cfg(test)]
mod tests {
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
    fn auxiliary_windows_cannot_use_practice() {
        assert!(super::ensure_main("main").is_ok());
        for label in ["capsule", "ask", "unknown"] {
            assert!(super::ensure_main(label).is_err());
        }
    }
}
