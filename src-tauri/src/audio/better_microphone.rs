use super::capture::CaptureMeter;
use super::InputDeviceInfo;
use super::{AudioCaptureHandle, AudioConfig};
use std::sync::Mutex;
use std::time::Duration;
use tauri::{Emitter, Manager};

fn claim_scan(
    completed: bool,
    previous: Option<i64>,
    now: i64,
    persist: impl FnOnce(i64) -> bool,
) -> bool {
    if !completed || now <= 0 {
        return false;
    }
    if previous.is_some_and(|last| now.saturating_sub(last) < 86400) {
        return false;
    }
    persist(now)
}

fn claim_persisted_scan(app: &tauri::AppHandle) -> bool {
    static CLAIM_LOCK: Mutex<()> = Mutex::new(());
    let _guard = CLAIM_LOCK.lock().unwrap_or_else(|error| error.into_inner());
    app.state::<crate::storage::ConfigManager>()
        .with_microphone_scan_settings(|store| Ok(claim_scan_from_store(store)))
        .unwrap_or(false)
}

fn claim_scan_from_store<R: tauri::Runtime>(store: &tauri_plugin_store::Store<R>) -> bool {
    let completed = store
        .get("onboarding_completed")
        .and_then(|value| value.as_bool())
        == Some(true);
    let previous_value = store.get("better_microphone_last_scan");
    let previous = previous_value.as_ref().and_then(|value| value.as_i64());
    if previous_value.is_some() && previous.is_none() {
        return false;
    }
    claim_scan(completed, previous, chrono::Utc::now().timestamp(), |now| {
        if let Err(error) = crate::storage::persist_microphone_scan(store, now) {
            tracing::warn!("Failed to save microphone detection cooldown: {error}");
            return false;
        }
        true
    })
}

fn candidate_allowed(device: &InputDeviceInfo, current: &str) -> bool {
    device.connection != "bluetooth" && device.name != current && !device.name.trim().is_empty()
}

fn is_better(current: f32, candidate: f32) -> bool {
    current.is_finite()
        && candidate.is_finite()
        && candidate >= 0.01
        && (current < 0.01 || candidate / current >= 1.5)
}

async fn wait_active(meter: &CaptureMeter, duration: Duration) -> bool {
    let until = tokio::time::Instant::now() + duration;
    loop {
        if meter.level().is_none() {
            return false;
        }
        if tokio::time::Instant::now() >= until {
            return true;
        }
        tokio::time::sleep(Duration::from_millis(50)).await;
    }
}

pub(crate) fn schedule(app: tauri::AppHandle, current_name: String, meter: CaptureMeter) {
    tauri::async_runtime::spawn(async move {
        if !wait_active(&meter, Duration::from_secs(1)).await {
            return;
        }
        let claim_app = app.clone();
        let allowed =
            tauri::async_runtime::spawn_blocking(move || claim_persisted_scan(&claim_app))
                .await
                .unwrap_or(false);
        if !allowed || meter.level().is_none() {
            return;
        }
        let devices = match tauri::async_runtime::spawn_blocking(super::list_input_devices).await {
            Ok(devices) => devices,
            Err(_) => return,
        };
        let mut captures = Vec::new();
        for device in devices
            .iter()
            .filter(|device| candidate_allowed(device, &current_name))
            .take(8)
        {
            if meter.level().is_none() {
                return;
            }
            let Ok((mut capture, mut chunks)) =
                AudioCaptureHandle::start(AudioConfig::for_device(&device.name))
            else {
                continue;
            };
            tauri::async_runtime::spawn(async move { while chunks.recv().await.is_some() {} });
            let ready = tokio::select! {
                ready = tokio::time::timeout(Duration::from_secs(2), capture.wait_until_ready()) =>
                    matches!(ready, Ok(Ok(_))),
                _ = async {
                    while meter.level().is_some() {
                        tokio::time::sleep(Duration::from_millis(50)).await;
                    }
                } => false,
            };
            if ready && capture.device_name().as_deref() == Some(device.name.as_str()) {
                captures.push((device.name.clone(), capture, 0.0f32));
            }
            if !wait_active(&meter, Duration::from_millis(500)).await {
                return;
            }
        }
        if captures.is_empty() {
            return;
        }
        let mut current_sum = 0.0;
        for _sample in 0..10 {
            let Some(level) = meter.level() else { return };
            current_sum += level;
            for (_name, capture, sum) in &mut captures {
                *sum += capture.get_volume();
            }
            if !wait_active(&meter, Duration::from_millis(50)).await {
                return;
            }
        }
        let best = captures
            .iter()
            .filter(|(_name, capture, sum)| {
                capture.state() == super::CaptureState::Recording
                    && is_better(current_sum / 10.0, *sum / 10.0)
            })
            .max_by(|left, right| left.2.total_cmp(&right.2))
            .map(|(name, _capture, _sum)| name.clone());
        drop(captures);
        if meter.level().is_some() {
            if let Some(name) = best {
                let _ = app.emit("audio:better-microphone", name);
            }
        }
    });
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn scan_settings_are_validated_before_store_open_and_restored_after_save_failure() {
        let implementation = include_str!("better_microphone.rs")
            .split("#[cfg(test)]")
            .next()
            .unwrap();
        assert!(implementation.contains(".with_microphone_scan_settings("));
        assert!(!implementation.contains("app.store(\"settings.json\")"));
    }

    #[test]
    fn scan_cooldown_survives_a_new_claim_and_expires_at_24_hours() {
        let mut saved = None;
        assert!(claim_scan(true, saved, 1000, |now| {
            saved = Some(now);
            true
        }));
        assert!(!claim_scan(true, saved, 1000 + 86399, |_| panic!(
            "early save"
        )));
        assert!(claim_scan(true, saved, 1000 + 86400, |_| true));
    }

    #[test]
    fn onboarding_clock_rollback_and_failed_save_do_not_start_a_scan() {
        assert!(!claim_scan(false, None, 1000, |_| panic!(
            "onboarding save"
        )));
        assert!(!claim_scan(true, Some(2000), 1000, |_| panic!(
            "clock save"
        )));
        assert!(!claim_scan(true, None, 1000, |_| false));
    }

    #[test]
    fn compares_silence_and_the_exact_ratio_boundary() {
        assert!(is_better(0.0, 0.01));
        assert!(!is_better(0.0, 0.009));
        assert!(is_better(0.1, 0.15));
        assert!(!is_better(0.1, 0.149));
        assert!(!is_better(f32::NAN, 0.2));
        assert!(!is_better(0.1, f32::INFINITY));
    }

    #[test]
    fn excludes_current_and_bluetooth_but_keeps_a_different_default_device() {
        let mut device = InputDeviceInfo {
            name: "USB".into(),
            is_default: true,
            connection: "usb",
        };
        assert!(candidate_allowed(&device, "Built-in"));
        assert!(!candidate_allowed(&device, "USB"));
        device.connection = "bluetooth";
        assert!(!candidate_allowed(&device, "Built-in"));
    }
}
