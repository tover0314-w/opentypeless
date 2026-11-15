use crate::audio::InputDeviceInfo;
use crate::{pipeline, storage};
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::Mutex;
use tauri::menu::{CheckMenuItem, IsMenuItem, Menu, MenuItem, PredefinedMenuItem, Submenu};
use tauri::Manager;
use tauri_plugin_store::StoreExt;

/// Managed tray icon handle for dynamic menu/tooltip updates.
pub struct TrayHandle {
    pub tray: Mutex<tauri::tray::TrayIcon>,
}

struct TrayLabels {
    open_home: &'static str,
    start_recording: &'static str,
    stop_recording: &'static str,
    hide_capsule_when_idle: &'static str,
    keep_capsule_visible: &'static str,
    settings: &'static str,
    history: &'static str,
    add_word: &'static str,
    select_microphone: &'static str,
    auto_detect: &'static str,
    account: &'static str,
    about: &'static str,
    quit: &'static str,
    version: &'static str,
    update_check: &'static str,
    update_checking: &'static str,
    update_downloading: &'static str,
    update_ready: &'static str,
}

/// Updater phase mirrored from the main window so the tray can show Typeless's status item.
#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum TrayUpdateStatus {
    Idle,
    Checking,
    Downloading,
    Downloaded,
}

impl TrayUpdateStatus {
    pub fn parse(value: &str) -> Option<Self> {
        match value {
            "idle" => Some(Self::Idle),
            "checking" => Some(Self::Checking),
            "downloading" => Some(Self::Downloading),
            "downloaded" => Some(Self::Downloaded),
            _ => None,
        }
    }
}

static UPDATE_STATUS: Mutex<TrayUpdateStatus> = Mutex::new(TrayUpdateStatus::Idle);

/// Returns whether the status changed, so callers only rebuild the menu when needed.
pub fn set_update_status(status: TrayUpdateStatus) -> bool {
    let mut current = UPDATE_STATUS.lock().unwrap_or_else(|e| e.into_inner());
    let changed = *current != status;
    *current = status;
    changed
}

fn update_status() -> TrayUpdateStatus {
    *UPDATE_STATUS.lock().unwrap_or_else(|e| e.into_inner())
}

// Typeless keeps the item clickable only when it can act: start a check or restart.
fn update_item(labels: &TrayLabels, status: TrayUpdateStatus) -> (&'static str, bool) {
    match status {
        TrayUpdateStatus::Idle => (labels.update_check, true),
        TrayUpdateStatus::Checking => (labels.update_checking, false),
        TrayUpdateStatus::Downloading => (labels.update_downloading, false),
        TrayUpdateStatus::Downloaded => (labels.update_ready, true),
    }
}

fn get_tray_labels(lang: &str) -> TrayLabels {
    match lang {
        "zh" => TrayLabels {
            open_home: "打开OpenTypeless主页",
            start_recording: "开始录音",
            stop_recording: "停止录音",
            hide_capsule_when_idle: "空闲时隐藏胶囊",
            keep_capsule_visible: "保持胶囊可见",
            settings: "设置...",
            history: "显示历史记录",
            add_word: "将词汇添加到词典",
            select_microphone: "选择麦克风",
            auto_detect: "自动检测",
            account: "账户",
            about: "关于 OpenTypeless",
            quit: "退出 OpenTypeless",
            version: "版本",
            update_check: "检查更新...",
            update_checking: "检查更新中...",
            update_downloading: "下载更新中...",
            update_ready: "更新已准备好，立即重启吗？",
        },
        "ja" => TrayLabels {
            open_home: "OpenTypelessのホームを開く",
            start_recording: "録音を開始",
            stop_recording: "録音を停止",
            hide_capsule_when_idle: "待機中はカプセルを非表示",
            keep_capsule_visible: "カプセルを表示したままにする",
            settings: "設定...",
            history: "履歴を表示",
            add_word: "辞書に単語を追加",
            select_microphone: "マイクを選択",
            auto_detect: "自動検出",
            account: "アカウント",
            about: "OpenTypeless について",
            quit: "OpenTypelessを終了",
            version: "バージョン",
            update_check: "アップデートを確認...",
            update_checking: "アップデートを確認中...",
            update_downloading: "アップデートをダウンロード中...",
            update_ready: "アップデートの準備ができました。今すぐ再起動しますか？",
        },
        "ko" => TrayLabels {
            open_home: "OpenTypeless 홈 열기",
            start_recording: "녹음 시작",
            stop_recording: "녹음 중지",
            hide_capsule_when_idle: "유휴 시 캡슐 숨기기",
            keep_capsule_visible: "캡슐 항상 표시",
            settings: "설정...",
            history: "기록 보기",
            add_word: "사전에 단어 추가",
            select_microphone: "마이크 선택",
            auto_detect: "자동 감지",
            account: "계정",
            about: "OpenTypeless 정보",
            quit: "OpenTypeless 종료",
            version: "버전",
            update_check: "업데이트 확인...",
            update_checking: "업데이트 확인 중...",
            update_downloading: "업데이트 다운로드 중...",
            update_ready: "업데이트가 준비되었습니다. 지금 다시 시작할까요?",
        },
        "fr" => TrayLabels {
            open_home: "Ouvrir l'accueil d'OpenTypeless",
            start_recording: "Démarrer l'enregistrement",
            stop_recording: "Arrêter l'enregistrement",
            hide_capsule_when_idle: "Masquer la capsule au repos",
            keep_capsule_visible: "Garder la capsule visible",
            settings: "Paramètres...",
            history: "Afficher l'historique",
            add_word: "Ajouter un mot au dictionnaire",
            select_microphone: "Sélectionner le microphone",
            auto_detect: "Détection automatique",
            account: "Compte",
            about: "À propos d'OpenTypeless",
            quit: "Quitter OpenTypeless",
            version: "Version",
            update_check: "Rechercher des mises à jour...",
            update_checking: "Recherche de mises à jour...",
            update_downloading: "Téléchargement de la mise à jour...",
            update_ready: "Mise à jour prête, redémarrer maintenant ?",
        },
        "de" => TrayLabels {
            open_home: "OpenTypeless-Startseite öffnen",
            start_recording: "Aufnahme starten",
            stop_recording: "Aufnahme stoppen",
            hide_capsule_when_idle: "Kapsel im Leerlauf ausblenden",
            keep_capsule_visible: "Kapsel sichtbar lassen",
            settings: "Einstellungen...",
            history: "Verlauf anzeigen",
            add_word: "Wort zum Wörterbuch hinzufügen",
            select_microphone: "Mikrofon auswählen",
            auto_detect: "Automatische Erkennung",
            account: "Konto",
            about: "Über OpenTypeless",
            quit: "OpenTypeless beenden",
            version: "Version",
            update_check: "Nach Updates suchen...",
            update_checking: "Suche nach Updates...",
            update_downloading: "Update wird heruntergeladen...",
            update_ready: "Update bereit – jetzt neu starten?",
        },
        "es" => TrayLabels {
            open_home: "Abrir inicio de OpenTypeless",
            start_recording: "Iniciar grabación",
            stop_recording: "Detener grabación",
            hide_capsule_when_idle: "Ocultar cápsula en reposo",
            keep_capsule_visible: "Mantener cápsula visible",
            settings: "Configuración...",
            history: "Mostrar historial",
            add_word: "Añadir palabra al diccionario",
            select_microphone: "Seleccionar micrófono",
            auto_detect: "Detección automática",
            account: "Cuenta",
            about: "Acerca de OpenTypeless",
            quit: "Salir de OpenTypeless",
            version: "Versión",
            update_check: "Buscar actualizaciones...",
            update_checking: "Buscando actualizaciones...",
            update_downloading: "Descargando actualización...",
            update_ready: "Actualización lista, ¿reiniciar ahora?",
        },
        "pt" => TrayLabels {
            open_home: "Abrir página inicial do OpenTypeless",
            start_recording: "Iniciar gravação",
            stop_recording: "Parar gravação",
            hide_capsule_when_idle: "Ocultar cápsula em repouso",
            keep_capsule_visible: "Manter cápsula visível",
            settings: "Configurações...",
            history: "Mostrar histórico",
            add_word: "Adicionar palavra ao dicionário",
            select_microphone: "Selecionar microfone",
            auto_detect: "Detecção automática",
            account: "Conta",
            about: "Sobre o OpenTypeless",
            quit: "Sair do OpenTypeless",
            version: "Versão",
            update_check: "Verificar atualizações...",
            update_checking: "Verificando atualizações...",
            update_downloading: "Baixando atualização...",
            update_ready: "Atualização pronta. Reiniciar agora?",
        },
        "ru" => TrayLabels {
            open_home: "Открыть главную OpenTypeless",
            start_recording: "Начать запись",
            stop_recording: "Остановить запись",
            hide_capsule_when_idle: "Скрывать капсулу в простое",
            keep_capsule_visible: "Оставлять капсулу видимой",
            settings: "Настройки...",
            history: "Показать историю",
            add_word: "Добавить слово в словарь",
            select_microphone: "Выбрать микрофон",
            auto_detect: "Автоопределение",
            account: "Аккаунт",
            about: "О программе OpenTypeless",
            quit: "Выйти из OpenTypeless",
            version: "Версия",
            update_check: "Проверить обновления...",
            update_checking: "Проверка обновлений...",
            update_downloading: "Загрузка обновления...",
            update_ready: "Обновление готово. Перезапустить сейчас?",
        },
        "it" => TrayLabels {
            open_home: "Apri la home di OpenTypeless",
            start_recording: "Inizia registrazione",
            stop_recording: "Ferma registrazione",
            hide_capsule_when_idle: "Nascondi capsula quando inattiva",
            keep_capsule_visible: "Mantieni capsula visibile",
            settings: "Impostazioni...",
            history: "Mostra cronologia",
            add_word: "Aggiungi parola al dizionario",
            select_microphone: "Seleziona il microfono",
            auto_detect: "Rilevamento automatico",
            account: "Account",
            about: "Informazioni su OpenTypeless",
            quit: "Esci da OpenTypeless",
            version: "Versione",
            update_check: "Controlla aggiornamenti...",
            update_checking: "Controllo aggiornamenti...",
            update_downloading: "Download aggiornamento...",
            update_ready: "Aggiornamento pronto, riavviare ora?",
        },
        _ => TrayLabels {
            open_home: "Open OpenTypeless home",
            start_recording: "Start Recording",
            stop_recording: "Stop Recording",
            hide_capsule_when_idle: "Hide Capsule When Idle",
            keep_capsule_visible: "Keep Capsule Visible",
            settings: "Settings...",
            history: "Show history",
            add_word: "Add word to dictionary",
            select_microphone: "Select microphone",
            auto_detect: "Auto-detect",
            account: "Account",
            about: "About OpenTypeless",
            quit: "Quit OpenTypeless",
            version: "Version",
            update_check: "Check for updates...",
            update_checking: "Checking for updates...",
            update_downloading: "Downloading update...",
            update_ready: "Update ready, restart now?",
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

static MICROPHONES: Mutex<Vec<InputDeviceInfo>> = Mutex::new(Vec::new());
static MICROPHONE_REFRESH_IN_FLIGHT: AtomicBool = AtomicBool::new(false);

pub const MICROPHONE_AUTO_ID: &str = "microphone_auto";
const MICROPHONE_DEVICE_ID_PREFIX: &str = "microphone:";

/// Returns whether the cached device list changed.
pub fn set_microphones(devices: Vec<InputDeviceInfo>) -> bool {
    let mut current = MICROPHONES.lock().unwrap_or_else(|e| e.into_inner());
    let changed = *current != devices;
    *current = devices;
    changed
}

fn microphones() -> Vec<InputDeviceInfo> {
    MICROPHONES
        .lock()
        .unwrap_or_else(|e| e.into_inner())
        .clone()
}

/// Maps a tray menu id back to the `microphone_device` value it selects.
pub fn microphone_selection_from_menu_id(id: &str) -> Option<String> {
    if id == MICROPHONE_AUTO_ID {
        return Some(String::new());
    }
    id.strip_prefix(MICROPHONE_DEVICE_ID_PREFIX)
        .map(ToString::to_string)
}

/// Re-lists input devices off the main thread and rebuilds the tray only when they changed.
pub fn refresh_microphones(app: &tauri::AppHandle) {
    if MICROPHONE_REFRESH_IN_FLIGHT.swap(true, Ordering::AcqRel) {
        return;
    }
    let app = app.clone();
    tauri::async_runtime::spawn_blocking(move || {
        let changed = set_microphones(crate::audio::list_input_devices());
        MICROPHONE_REFRESH_IN_FLIGHT.store(false, Ordering::Release);
        if changed {
            refresh_tray(&app);
        }
    });
}

#[derive(Debug, Clone, PartialEq, Eq)]
enum TrayEntry {
    Item {
        id: String,
        label: String,
        enabled: bool,
        accelerator: Option<&'static str>,
    },
    Radio {
        id: String,
        label: String,
        checked: bool,
    },
    Submenu {
        id: &'static str,
        label: String,
        children: Vec<TrayEntry>,
    },
    Separator,
}

fn item(id: &str, label: impl Into<String>) -> TrayEntry {
    TrayEntry::Item {
        id: id.to_string(),
        label: label.into(),
        enabled: true,
        accelerator: None,
    }
}

// Typeless checks the system-default entry when the saved device is unset or disconnected.
fn microphone_entries(
    labels: &TrayLabels,
    devices: &[InputDeviceInfo],
    selected: &str,
) -> Vec<TrayEntry> {
    let selected = selected.trim();
    let selected_connected = devices.iter().any(|device| device.name == selected);
    let auto_label = match devices.iter().find(|device| device.is_default) {
        Some(default) => format!("{} ({})", labels.auto_detect, default.name),
        None => labels.auto_detect.to_string(),
    };
    let mut entries = vec![TrayEntry::Radio {
        id: MICROPHONE_AUTO_ID.to_string(),
        label: auto_label,
        checked: selected.is_empty() || !selected_connected,
    }];
    entries.extend(devices.iter().map(|device| TrayEntry::Radio {
        id: format!("{MICROPHONE_DEVICE_ID_PREFIX}{}", device.name),
        label: device.name.clone(),
        checked: selected_connected && device.name == selected,
    }));
    entries
}

struct TrayState<'a> {
    is_recording: bool,
    capsule_auto_hide: bool,
    microphone_device: &'a str,
    microphones: &'a [InputDeviceInfo],
    update_status: TrayUpdateStatus,
    version: &'a str,
}

// Typeless order (home, history, dictionary | settings, microphone | version, update, quit),
// with OpenTypeless's recording, capsule, account and about items kept in their own groups.
fn tray_layout(labels: &TrayLabels, state: &TrayState<'_>) -> Vec<TrayEntry> {
    let (update_label, update_enabled) = update_item(labels, state.update_status);
    vec![
        item("open_home", labels.open_home),
        item("history", labels.history),
        item("add_word", labels.add_word),
        TrayEntry::Separator,
        item(
            "record",
            if state.is_recording {
                labels.stop_recording
            } else {
                labels.start_recording
            },
        ),
        item(
            "toggle_capsule_auto_hide",
            if state.capsule_auto_hide {
                labels.keep_capsule_visible
            } else {
                labels.hide_capsule_when_idle
            },
        ),
        TrayEntry::Separator,
        TrayEntry::Item {
            id: "settings".to_string(),
            label: labels.settings.to_string(),
            enabled: true,
            accelerator: Some("CmdOrCtrl+,"),
        },
        TrayEntry::Submenu {
            id: "select_microphone",
            label: labels.select_microphone.to_string(),
            children: microphone_entries(labels, state.microphones, state.microphone_device),
        },
        item("account", labels.account),
        TrayEntry::Separator,
        item("about", labels.about),
        TrayEntry::Item {
            id: "version".to_string(),
            label: format!("{} {}", labels.version, state.version),
            enabled: false,
            accelerator: None,
        },
        TrayEntry::Item {
            id: "check_updates".to_string(),
            label: update_label.to_string(),
            enabled: update_enabled,
            accelerator: None,
        },
        TrayEntry::Item {
            id: "quit".to_string(),
            label: labels.quit.to_string(),
            enabled: true,
            accelerator: Some("CmdOrCtrl+Q"),
        },
    ]
}

fn build_entry(
    app: &tauri::AppHandle,
    entry: &TrayEntry,
) -> tauri::Result<Box<dyn IsMenuItem<tauri::Wry>>> {
    Ok(match entry {
        TrayEntry::Item {
            id,
            label,
            enabled,
            accelerator,
        } => Box::new(MenuItem::with_id(
            app,
            id.as_str(),
            label,
            *enabled,
            *accelerator,
        )?),
        TrayEntry::Radio { id, label, checked } => Box::new(CheckMenuItem::with_id(
            app,
            id.as_str(),
            label,
            true,
            *checked,
            None::<&str>,
        )?),
        TrayEntry::Submenu {
            id,
            label,
            children,
        } => {
            let built = children
                .iter()
                .map(|child| build_entry(app, child))
                .collect::<tauri::Result<Vec<_>>>()?;
            let refs: Vec<&dyn IsMenuItem<tauri::Wry>> =
                built.iter().map(|child| child.as_ref()).collect();
            Box::new(Submenu::with_id_and_items(app, *id, label, true, &refs)?)
        }
        TrayEntry::Separator => Box::new(PredefinedMenuItem::separator(app)?),
    })
}

fn tray_microphone_device(config: Option<&serde_json::Value>) -> String {
    config
        .and_then(|config| config.get("microphone_device"))
        .and_then(|value| value.as_str())
        .unwrap_or_default()
        .to_string()
}

/// Build (or rebuild) the system tray menu based on current state.
pub fn build_tray_menu(
    app: &tauri::AppHandle,
    is_recording: bool,
    capsule_auto_hide: bool,
) -> Result<Menu<tauri::Wry>, Box<dyn std::error::Error>> {
    let labels = get_tray_labels(&tray_language(app));
    let config = app_config_value(app);
    let microphone_device = tray_microphone_device(config.as_ref());
    let microphones = microphones();
    let version = app.package_info().version.to_string();
    let layout = tray_layout(
        &labels,
        &TrayState {
            is_recording,
            capsule_auto_hide,
            microphone_device: &microphone_device,
            microphones: &microphones,
            update_status: update_status(),
            version: &version,
        },
    );
    let built = layout
        .iter()
        .map(|entry| build_entry(app, entry))
        .collect::<tauri::Result<Vec<_>>>()?;
    let refs: Vec<&dyn IsMenuItem<tauri::Wry>> = built.iter().map(|entry| entry.as_ref()).collect();
    Ok(Menu::with_items(app, &refs)?)
}

/// Rebuild the tray menu and update tooltip based on pipeline state.
pub fn refresh_tray(app: &tauri::AppHandle) {
    let is_recording = app
        .try_state::<pipeline::PipelineHandle>()
        .map(|p| p.current_state() == pipeline::PipelineState::Recording)
        .unwrap_or(false);
    let capsule_auto_hide = tray_capsule_auto_hide(app);

    if let Some(tray_handle) = app.try_state::<TrayHandle>() {
        if let Ok(tray) = tray_handle.tray.lock() {
            if let Ok(menu) = build_tray_menu(app, is_recording, capsule_auto_hide) {
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
    fn every_tray_language_has_update_status_labels() {
        for lang in ["en", "zh", "ja", "ko", "fr", "de", "es", "pt", "ru", "it"] {
            let labels = get_tray_labels(lang);
            for label in [
                labels.version,
                labels.update_check,
                labels.update_checking,
                labels.update_downloading,
                labels.update_ready,
            ] {
                assert!(!label.is_empty(), "{lang} is missing an update label");
            }
        }
        assert_eq!(
            get_tray_labels("zh").update_ready,
            "更新已准备好，立即重启吗？"
        );
    }

    #[test]
    fn update_item_is_only_clickable_when_it_can_act() {
        let labels = get_tray_labels("en");
        assert_eq!(
            update_item(&labels, TrayUpdateStatus::Idle),
            ("Check for updates...", true)
        );
        assert!(!update_item(&labels, TrayUpdateStatus::Checking).1);
        assert!(!update_item(&labels, TrayUpdateStatus::Downloading).1);
        assert_eq!(
            update_item(&labels, TrayUpdateStatus::Downloaded),
            ("Update ready, restart now?", true)
        );
    }

    #[test]
    fn update_status_parses_frontend_phases_only() {
        assert_eq!(
            TrayUpdateStatus::parse("downloading"),
            Some(TrayUpdateStatus::Downloading)
        );
        assert_eq!(TrayUpdateStatus::parse("up-to-date"), None);
    }

    fn device(name: &str, is_default: bool) -> InputDeviceInfo {
        InputDeviceInfo {
            name: name.to_string(),
            is_default,
            connection: "builtIn",
        }
    }

    fn state<'a>(microphone_device: &'a str, microphones: &'a [InputDeviceInfo]) -> TrayState<'a> {
        TrayState {
            is_recording: false,
            capsule_auto_hide: true,
            microphone_device,
            microphones,
            update_status: TrayUpdateStatus::Idle,
            version: "1.1.64",
        }
    }

    fn ids(entries: &[TrayEntry]) -> Vec<&str> {
        entries
            .iter()
            .map(|entry| match entry {
                TrayEntry::Item { id, .. } | TrayEntry::Radio { id, .. } => id.as_str(),
                TrayEntry::Submenu { id, .. } => id,
                TrayEntry::Separator => "-",
            })
            .collect()
    }

    fn radios(entries: &[TrayEntry]) -> Vec<(&str, bool)> {
        entries
            .iter()
            .filter_map(|entry| match entry {
                TrayEntry::Radio { label, checked, .. } => Some((label.as_str(), *checked)),
                _ => None,
            })
            .collect()
    }

    fn microphone_children(layout: &[TrayEntry]) -> &[TrayEntry] {
        layout
            .iter()
            .find_map(|entry| match entry {
                TrayEntry::Submenu { children, .. } => Some(children.as_slice()),
                _ => None,
            })
            .expect("microphone submenu")
    }

    #[test]
    fn tray_follows_typeless_order_and_keeps_opentypeless_items() {
        let layout = tray_layout(&get_tray_labels("en"), &state("", &[]));
        assert_eq!(
            ids(&layout),
            [
                "open_home",
                "history",
                "add_word",
                "-",
                "record",
                "toggle_capsule_auto_hide",
                "-",
                "settings",
                "select_microphone",
                "account",
                "-",
                "about",
                "version",
                "check_updates",
                "quit",
            ]
        );
    }

    #[test]
    fn tray_uses_typeless_accelerators_and_disabled_version() {
        let layout = tray_layout(&get_tray_labels("zh"), &state("", &[]));
        let find = |wanted: &str| {
            layout
                .iter()
                .find_map(|entry| match entry {
                    TrayEntry::Item {
                        id,
                        label,
                        enabled,
                        accelerator,
                    } if id == wanted => Some((label.clone(), *enabled, *accelerator)),
                    _ => None,
                })
                .unwrap()
        };
        assert_eq!(
            find("settings"),
            ("设置...".to_string(), true, Some("CmdOrCtrl+,"))
        );
        assert_eq!(
            find("quit"),
            ("退出 OpenTypeless".to_string(), true, Some("CmdOrCtrl+Q"))
        );
        assert_eq!(find("version"), ("版本 1.1.64".to_string(), false, None));
        assert_eq!(find("open_home").0, "打开OpenTypeless主页");
        assert_eq!(find("add_word").0, "将词汇添加到词典");
    }

    #[test]
    fn microphone_submenu_checks_the_saved_device() {
        let devices = [
            device("MacBook Pro Microphone", true),
            device("USB Mic", false),
        ];
        let layout = tray_layout(&get_tray_labels("en"), &state("USB Mic", &devices));
        assert_eq!(
            radios(microphone_children(&layout)),
            [
                ("Auto-detect (MacBook Pro Microphone)", false),
                ("MacBook Pro Microphone", false),
                ("USB Mic", true),
            ]
        );
    }

    #[test]
    fn microphone_submenu_falls_back_to_auto_detect() {
        let devices = [device("MacBook Pro Microphone", true)];
        for saved in ["", "Unplugged Headset"] {
            let layout = tray_layout(&get_tray_labels("en"), &state(saved, &devices));
            assert_eq!(
                radios(microphone_children(&layout)),
                [
                    ("Auto-detect (MacBook Pro Microphone)", true),
                    ("MacBook Pro Microphone", false),
                ]
            );
        }
        let layout = tray_layout(&get_tray_labels("ja"), &state("", &[]));
        assert_eq!(radios(microphone_children(&layout)), [("自動検出", true)]);
    }

    #[test]
    fn microphone_menu_ids_round_trip_to_config_values() {
        let devices = [device("USB Mic: Studio", false)];
        let layout = tray_layout(&get_tray_labels("en"), &state("", &devices));
        let children = microphone_children(&layout);
        let selections: Vec<Option<String>> = ids(children)
            .into_iter()
            .map(microphone_selection_from_menu_id)
            .collect();
        assert_eq!(
            selections,
            [Some(String::new()), Some("USB Mic: Studio".to_string())]
        );
        assert_eq!(microphone_selection_from_menu_id("settings"), None);
    }

    #[test]
    fn every_tray_language_has_typeless_menu_labels() {
        for lang in ["en", "zh", "ja", "ko", "fr", "de", "es", "pt", "ru", "it"] {
            let labels = get_tray_labels(lang);
            for label in [
                labels.open_home,
                labels.add_word,
                labels.select_microphone,
                labels.auto_detect,
            ] {
                assert!(!label.is_empty(), "{lang} is missing a tray label");
            }
            assert!(labels.open_home.contains("OpenTypeless"), "{lang}");
            assert!(labels.quit.contains("OpenTypeless"), "{lang}");
        }
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
