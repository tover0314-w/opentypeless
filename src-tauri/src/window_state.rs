use std::sync::Mutex;

use tauri::{Manager, Runtime};
use tauri_plugin_store::StoreExt;

/// Geometry is always the restored window's geometry, never a maximized frame.
#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize, serde::Deserialize)]
struct WindowState {
    x: i32,
    y: i32,
    width: u32,
    height: u32,
    #[serde(default)]
    maximized: bool,
}

struct MainWindowState(Mutex<Option<WindowState>>);

// Mirrors the main window minimum in tauri.conf.json.
const MIN_WIDTH: u32 = 988;
const MIN_HEIGHT: u32 = 685;

impl WindowState {
    fn valid(&self) -> bool {
        self.x > -1000 && self.y > -1000 && self.width >= MIN_WIDTH && self.height >= MIN_HEIGHT
    }

    fn observe(&mut self, observed: Self, minimized: bool, fullscreen: bool) {
        // Minimized coordinates and fullscreen frames must not become restore bounds.
        if minimized || fullscreen {
            return;
        }
        self.maximized = observed.maximized;
        if !observed.maximized && observed.valid() {
            *self = observed;
        }
    }
}

fn observe_window<R: Runtime>(window: &tauri::WebviewWindow<R>, state: &MainWindowState) {
    let (Ok(position), Ok(size), Ok(maximized), Ok(minimized), Ok(fullscreen)) = (
        window.outer_position(),
        // set_size restores the client area, so do not persist the frame size.
        window.inner_size(),
        window.is_maximized(),
        window.is_minimized(),
        window.is_fullscreen(),
    ) else {
        return;
    };
    let observed = WindowState {
        x: position.x,
        y: position.y,
        width: size.width,
        height: size.height,
        maximized,
    };
    let mut current = state.0.lock().unwrap_or_else(|error| error.into_inner());
    if let Some(current) = current.as_mut() {
        current.observe(observed, minimized, fullscreen);
    } else if !minimized && !fullscreen && !maximized && observed.valid() {
        *current = Some(observed);
    }
}

pub fn restore_and_track<R: Runtime>(window: &tauri::WebviewWindow<R>) {
    let handle = window.app_handle();
    let saved = handle.store("settings.json").ok().and_then(|store| {
        store
            .get("window_state")
            .and_then(|value| serde_json::from_value::<WindowState>(value).ok())
            .filter(WindowState::valid)
    });
    if let Some(saved) = &saved {
        let _ = window.set_position(tauri::PhysicalPosition::new(saved.x, saved.y));
        let _ = window.set_size(tauri::PhysicalSize::new(saved.width, saved.height));
    }
    handle.manage(MainWindowState(Mutex::new(saved.clone())));
    // Capture a new install's normal geometry before maximizing or hiding it.
    if saved.is_none() {
        observe_window(window, &handle.state::<MainWindowState>());
    }
    if saved.as_ref().is_some_and(|state| state.maximized) {
        let _ = window.maximize();
    }

    let tracked_window = window.clone();
    window.on_window_event(move |event| {
        if matches!(
            event,
            tauri::WindowEvent::Moved(_) | tauri::WindowEvent::Resized(_)
        ) {
            observe_window(
                &tracked_window,
                &tracked_window.app_handle().state::<MainWindowState>(),
            );
        }
    });
}

pub fn save_main_window_state<R: Runtime>(handle: &tauri::AppHandle<R>) {
    let Some(state) = handle.try_state::<MainWindowState>() else {
        return;
    };
    if let Some(window) = handle.get_webview_window("main") {
        observe_window(&window, &state);
    }
    let current = state
        .0
        .lock()
        .unwrap_or_else(|error| error.into_inner())
        .clone();
    let Some(current) = current else {
        return;
    };
    let result = (|| -> anyhow::Result<()> {
        let store = handle.store("settings.json")?;
        store.set("window_state", serde_json::to_value(current)?);
        store.save()?;
        Ok(())
    })();
    if let Err(error) = result {
        tracing::warn!("Failed to save main window state: {error}");
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn normal_state() -> WindowState {
        WindowState {
            x: 100,
            y: 80,
            width: 1080,
            height: 750,
            maximized: false,
        }
    }

    #[test]
    fn legacy_window_state_defaults_to_restored() {
        let state: WindowState =
            serde_json::from_str(r#"{"x":100,"y":80,"width":1080,"height":750}"#).unwrap();
        assert_eq!(state, normal_state());
    }

    #[test]
    fn maximizing_preserves_normal_geometry_across_serialization() {
        let mut state = normal_state();
        state.observe(
            WindowState {
                x: -8,
                y: -8,
                width: 1936,
                height: 1048,
                maximized: true,
            },
            false,
            false,
        );
        let saved: WindowState =
            serde_json::from_value(serde_json::to_value(&state).unwrap()).unwrap();
        assert_eq!(
            saved,
            WindowState {
                maximized: true,
                ..normal_state()
            }
        );
    }

    #[test]
    fn minimized_or_fullscreen_frames_do_not_change_restore_state() {
        let mut state = WindowState {
            maximized: true,
            ..normal_state()
        };
        let original = state.clone();
        state.observe(
            WindowState {
                x: -32000,
                y: -32000,
                maximized: false,
                ..normal_state()
            },
            true,
            false,
        );
        state.observe(
            WindowState {
                x: 0,
                y: 0,
                width: 1920,
                height: 1080,
                maximized: false,
            },
            false,
            true,
        );
        assert_eq!(state, original);
    }

    #[test]
    fn restoring_updates_normal_geometry_and_clears_maximized() {
        let mut state = WindowState {
            maximized: true,
            ..normal_state()
        };
        let restored = WindowState {
            x: 200,
            width: 1000,
            ..normal_state()
        };
        state.observe(restored.clone(), false, false);
        assert_eq!(state, restored);
    }

    #[test]
    fn geometry_below_the_main_window_minimum_is_not_restored() {
        let legacy_small = WindowState {
            width: 900,
            height: 700,
            ..normal_state()
        };
        assert!(!legacy_small.valid());
        assert!(WindowState {
            width: MIN_WIDTH,
            height: MIN_HEIGHT,
            ..normal_state()
        }
        .valid());
    }
}
