use crate::pipeline;
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
    settings: &'static str,
    history: &'static str,
    account: &'static str,
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
            settings: "设置",
            history: "历史记录",
            account: "账户",
            about: "关于 OpenTypeless",
            quit: "退出",
        },
        "ja" => TrayLabels {
            show_window: "ウィンドウを表示",
            hide_window: "ウィンドウを隠す",
            start_recording: "録音を開始",
            stop_recording: "録音を停止",
            settings: "設定",
            history: "履歴",
            account: "アカウント",
            about: "OpenTypeless について",
            quit: "終了",
        },
        "ko" => TrayLabels {
            show_window: "창 보이기",
            hide_window: "창 숨기기",
            start_recording: "녹음 시작",
            stop_recording: "녹음 중지",
            settings: "설정",
            history: "기록",
            account: "계정",
            about: "OpenTypeless 정보",
            quit: "종료",
        },
        "fr" => TrayLabels {
            show_window: "Afficher la fenêtre",
            hide_window: "Masquer la fenêtre",
            start_recording: "Démarrer l'enregistrement",
            stop_recording: "Arrêter l'enregistrement",
            settings: "Paramètres",
            history: "Historique",
            account: "Compte",
            about: "À propos d'OpenTypeless",
            quit: "Quitter",
        },
        "de" => TrayLabels {
            show_window: "Fenster anzeigen",
            hide_window: "Fenster ausblenden",
            start_recording: "Aufnahme starten",
            stop_recording: "Aufnahme stoppen",
            settings: "Einstellungen",
            history: "Verlauf",
            account: "Konto",
            about: "Über OpenTypeless",
            quit: "Beenden",
        },
        "es" => TrayLabels {
            show_window: "Mostrar ventana",
            hide_window: "Ocultar ventana",
            start_recording: "Iniciar grabación",
            stop_recording: "Detener grabación",
            settings: "Configuración",
            history: "Historial",
            account: "Cuenta",
            about: "Acerca de OpenTypeless",
            quit: "Salir",
        },
        "pt" => TrayLabels {
            show_window: "Mostrar janela",
            hide_window: "Ocultar janela",
            start_recording: "Iniciar gravação",
            stop_recording: "Parar gravação",
            settings: "Configurações",
            history: "Histórico",
            account: "Conta",
            about: "Sobre o OpenTypeless",
            quit: "Sair",
        },
        "ru" => TrayLabels {
            show_window: "Показать окно",
            hide_window: "Скрыть окно",
            start_recording: "Начать запись",
            stop_recording: "Остановить запись",
            settings: "Настройки",
            history: "История",
            account: "Аккаунт",
            about: "О программе OpenTypeless",
            quit: "Выход",
        },
        "it" => TrayLabels {
            show_window: "Mostra finestra",
            hide_window: "Nascondi finestra",
            start_recording: "Inizia registrazione",
            stop_recording: "Ferma registrazione",
            settings: "Impostazioni",
            history: "Cronologia",
            account: "Account",
            about: "Informazioni su OpenTypeless",
            quit: "Esci",
        },
        _ => TrayLabels {
            show_window: "Show Window",
            hide_window: "Hide Window",
            start_recording: "Start Recording",
            stop_recording: "Stop Recording",
            settings: "Settings",
            history: "History",
            account: "Account",
            about: "About OpenTypeless",
            quit: "Quit",
        },
    }
}

/// Build (or rebuild) the system tray menu based on current state.
pub fn build_tray_menu(
    app: &tauri::AppHandle,
    is_recording: bool,
    window_visible: bool,
) -> Result<Menu<tauri::Wry>, Box<dyn std::error::Error>> {
    let lang = app
        .store("settings.json")
        .ok()
        .and_then(|s| s.get("ui_language"))
        .and_then(|v| v.as_str().map(String::from))
        .unwrap_or_else(|| "en".to_string());

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
    let sep2 = PredefinedMenuItem::separator(app)?;
    let settings = MenuItem::with_id(app, "settings", labels.settings, true, None::<&str>)?;
    let history = MenuItem::with_id(app, "history", labels.history, true, None::<&str>)?;
    let account = MenuItem::with_id(app, "account", labels.account, true, None::<&str>)?;
    let sep3 = PredefinedMenuItem::separator(app)?;
    let about = MenuItem::with_id(app, "about", labels.about, true, None::<&str>)?;
    let quit = MenuItem::with_id(app, "quit", labels.quit, true, None::<&str>)?;

    let menu = Menu::with_items(
        app,
        &[
            &show_hide, &sep1, &record, &sep2, &settings, &history, &account, &sep3, &about, &quit,
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

    if let Some(tray_handle) = app.try_state::<TrayHandle>() {
        if let Ok(tray) = tray_handle.tray.lock() {
            if let Ok(menu) = build_tray_menu(app, is_recording, window_visible) {
                let _ = tray.set_menu(Some(menu));
            }
        }
    }
}
