use crate::{pipeline, storage};
use std::sync::Mutex;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::Manager;
use tauri_plugin_store::StoreExt;

/// Managed tray icon handle for dynamic menu/tooltip updates.
pub struct TrayHandle {
    pub tray: Mutex<tauri::tray::TrayIcon>,
}

struct TrayLabels {
    show_window: &'static str,
    hide_window: &'static str,
    start_recording: &'static str,
    stop_recording: &'static str,
    hide_capsule_when_idle: &'static str,
    keep_capsule_visible: &'static str,
    settings: &'static str,
    history: &'static str,
    about: &'static str,
    quit: &'static str,
}

fn get_tray_labels(lang: &str) -> TrayLabels {
    match lang {
        "zh" => TrayLabels {
            show_window: "显示窗口",
            hide_window: "隐藏窗口",
            start_recording: "开始录音",
            stop_recording: "停止录音",
            hide_capsule_when_idle: "空闲时隐藏胶囊",
            keep_capsule_visible: "保持胶囊可见",
            settings: "设置",
            history: "历史记录",
            about: "关于 OpenTypeless",
            quit: "退出",
        },
        "ja" => TrayLabels {
            show_window: "ウィンドウを表示",
            hide_window: "ウィンドウを隠す",
            start_recording: "録音を開始",
            stop_recording: "録音を停止",
            hide_capsule_when_idle: "待機中はカプセルを非表示",
            keep_capsule_visible: "カプセルを表示したままにする",
            settings: "設定",
            history: "履歴",
            about: "OpenTypeless について",
            quit: "終了",
        },
        "ko" => TrayLabels {
            show_window: "창 보이기",
            hide_window: "창 숨기기",
            start_recording: "녹음 시작",
            stop_recording: "녹음 중지",
            hide_capsule_when_idle: "유휴 시 캡슐 숨기기",
            keep_capsule_visible: "캡슐 항상 표시",
            settings: "설정",
            history: "기록",
            about: "OpenTypeless 정보",
            quit: "종료",
        },
        "fr" => TrayLabels {
            show_window: "Afficher la fenêtre",
            hide_window: "Masquer la fenêtre",
            start_recording: "Démarrer l'enregistrement",
            stop_recording: "Arrêter l'enregistrement",
            hide_capsule_when_idle: "Masquer la capsule au repos",
            keep_capsule_visible: "Garder la capsule visible",
            settings: "Paramètres",
            history: "Historique",
            about: "À propos d'OpenTypeless",
            quit: "Quitter",
        },
        "de" => TrayLabels {
            show_window: "Fenster anzeigen",
            hide_window: "Fenster ausblenden",
            start_recording: "Aufnahme starten",
            stop_recording: "Aufnahme stoppen",
            hide_capsule_when_idle: "Kapsel im Leerlauf ausblenden",
            keep_capsule_visible: "Kapsel sichtbar lassen",
            settings: "Einstellungen",
            history: "Verlauf",
            about: "Über OpenTypeless",
            quit: "Beenden",
        },
        "es" => TrayLabels {
            show_window: "Mostrar ventana",
            hide_window: "Ocultar ventana",
            start_recording: "Iniciar grabación",
            stop_recording: "Detener grabación",
            hide_capsule_when_idle: "Ocultar cápsula en reposo",
            keep_capsule_visible: "Mantener cápsula visible",
            settings: "Configuración",
            history: "Historial",
            about: "Acerca de OpenTypeless",
            quit: "Salir",
        },
        "pt" => TrayLabels {
            show_window: "Mostrar janela",
            hide_window: "Ocultar janela",
            start_recording: "Iniciar gravação",
            stop_recording: "Parar gravação",
            hide_capsule_when_idle: "Ocultar cápsula em repouso",
            keep_capsule_visible: "Manter cápsula visível",
            settings: "Configurações",
            history: "Histórico",
            about: "Sobre o OpenTypeless",
            quit: "Sair",
        },
        "ru" => TrayLabels {
            show_window: "Показать окно",
            hide_window: "Скрыть окно",
            start_recording: "Начать запись",
            stop_recording: "Остановить запись",
            hide_capsule_when_idle: "Скрывать капсулу в простое",
            keep_capsule_visible: "Оставлять капсулу видимой",
            settings: "Настройки",
            history: "История",
            about: "О программе OpenTypeless",
            quit: "Выход",
        },
        "it" => TrayLabels {
            show_window: "Mostra finestra",
            hide_window: "Nascondi finestra",
            start_recording: "Inizia registrazione",
            stop_recording: "Ferma registrazione",
            hide_capsule_when_idle: "Nascondi capsula quando inattiva",
            keep_capsule_visible: "Mantieni capsula visibile",
            settings: "Impostazioni",
            history: "Cronologia",
            about: "Informazioni su OpenTypeless",
            quit: "Esci",
        },
        _ => TrayLabels {
            show_window: "Show Window",
            hide_window: "Hide Window",
            start_recording: "Start Recording",
            stop_recording: "Stop Recording",
            hide_capsule_when_idle: "Hide Capsule When Idle",
            keep_capsule_visible: "Keep Capsule Visible",
            settings: "Settings",
            history: "History",
            about: "About OpenTypeless",
            quit: "Quit",
        },
    }
}

fn app_config_value(app: &tauri::AppHandle) -> Option<serde_json::Value> {
    app.store("settings.json")
        .ok()
        .and_then(|store| store.get("app_config"))
}

fn tray_language(app: &tauri::AppHandle) -> String {
    app_config_value(app)
        .and_then(|config| {
            config
                .get("ui_language")
                .and_then(|value| value.as_str())
                .map(String::from)
        })
        .unwrap_or_else(|| "en".to_string())
}

fn capsule_auto_hide_from_app_config(config: Option<&serde_json::Value>) -> bool {
    match config {
        Some(config) => config
            .get("capsule_auto_hide")
            .and_then(|value| value.as_bool())
            .unwrap_or(false),
        None => storage::AppConfig::new_install_default().capsule_auto_hide,
    }
}

fn tray_capsule_auto_hide(app: &tauri::AppHandle) -> bool {
    let config = app_config_value(app);
    capsule_auto_hide_from_app_config(config.as_ref())
}

/// Build (or rebuild) the system tray menu based on current state.
pub fn build_tray_menu(
    app: &tauri::AppHandle,
    is_recording: bool,
    window_visible: bool,
    capsule_auto_hide: bool,
) -> Result<Menu<tauri::Wry>, Box<dyn std::error::Error>> {
    let lang = tray_language(app);
    let labels = get_tray_labels(&lang);

    let show_hide = MenuItem::with_id(
        app,
        "show_hide",
        if window_visible {
            labels.hide_window
        } else {
            labels.show_window
        },
        true,
        None::<&str>,
    )?;
    let sep1 = PredefinedMenuItem::separator(app)?;
    let record = MenuItem::with_id(
        app,
        "record",
        if is_recording {
            labels.stop_recording
        } else {
            labels.start_recording
        },
        true,
        None::<&str>,
    )?;
    let capsule_visibility = MenuItem::with_id(
        app,
        "toggle_capsule_auto_hide",
        if capsule_auto_hide {
            labels.keep_capsule_visible
        } else {
            labels.hide_capsule_when_idle
        },
        true,
        None::<&str>,
    )?;
    let sep2 = PredefinedMenuItem::separator(app)?;
    let settings = MenuItem::with_id(app, "settings", labels.settings, true, None::<&str>)?;
    let history = MenuItem::with_id(app, "history", labels.history, true, None::<&str>)?;
    let sep3 = PredefinedMenuItem::separator(app)?;
    let about = MenuItem::with_id(app, "about", labels.about, true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", labels.quit, true, None::<&str>)?;

    let menu = Menu::with_items(
        app,
        &[
            &show_hide,
            &sep1,
            &record,
            &capsule_visibility,
            &sep2,
            &settings,
            &history,
            &sep3,
            &about,
            &quit,
        ],
    )?;
    Ok(menu)
}

/// Rebuild the tray menu and update tooltip based on pipeline state.
pub fn refresh_tray(app: &tauri::AppHandle) {
    let is_recording = app
        .try_state::<pipeline::PipelineHandle>()
        .map(|p| p.current_state() == pipeline::PipelineState::Recording)
        .unwrap_or(false);
    let window_visible = app
        .get_webview_window("main")
        .and_then(|w| w.is_visible().ok())
        .unwrap_or(false);
    let capsule_auto_hide = tray_capsule_auto_hide(app);

    if let Some(tray_handle) = app.try_state::<TrayHandle>() {
        if let Ok(tray) = tray_handle.tray.lock() {
            if let Ok(menu) = build_tray_menu(app, is_recording, window_visible, capsule_auto_hide)
            {
                let _ = tray.set_menu(Some(menu));
            }
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn tray_labels_include_capsule_visibility_controls() {
        let labels = get_tray_labels("en");

        assert_eq!(labels.hide_capsule_when_idle, "Hide Capsule When Idle");
        assert_eq!(labels.keep_capsule_visible, "Keep Capsule Visible");
    }

    #[test]
    fn missing_app_config_uses_new_install_capsule_auto_hide_default() {
        assert!(capsule_auto_hide_from_app_config(None));
    }

    #[test]
    fn existing_app_config_missing_capsule_auto_hide_preserves_legacy_visible_default() {
        let config = serde_json::json!({});

        assert!(!capsule_auto_hide_from_app_config(Some(&config)));
    }
}
