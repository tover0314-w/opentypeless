use std::sync::Mutex;

#[derive(Default)]
pub(crate) struct DeviceNotice {
    previous: Option<String>,
}

impl DeviceNotice {
    fn changed(&mut self, name: &str) -> bool {
        if name.trim().is_empty() || self.previous.as_deref() == Some(name) {
            return false;
        }
        self.previous = Some(name.to_string());
        true
    }
}

pub(crate) fn emit_changed(app: &tauri::AppHandle, name: Option<String>) {
    use tauri::Emitter;
    static NOTICE: Mutex<DeviceNotice> = Mutex::new(DeviceNotice { previous: None });
    if let Some(name) = name {
        if NOTICE
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .changed(&name)
        {
            let _ = app.emit("audio:input-device", name);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn announces_first_device_and_each_change_but_not_repeated_takes() {
        let mut notice = DeviceNotice::default();
        assert!(notice.changed("Built-in"));
        assert!(!notice.changed("Built-in"));
        assert!(notice.changed("USB"));
        assert!(!notice.changed("USB"));
        assert!(notice.changed("Built-in"));
    }

    #[test]
    fn ignores_empty_device_names() {
        let mut notice = DeviceNotice::default();
        assert!(!notice.changed(" "));
        assert!(notice.changed("USB"));
        assert!(!notice.changed(""));
        assert!(!notice.changed("USB"));
    }
}
