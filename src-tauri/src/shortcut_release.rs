use std::time::Duration;

#[cfg(any(target_os = "macos", test))]
pub(crate) fn query_with_health_check(
    health: impl Fn() -> Result<(), String>,
    query: impl FnOnce() -> Result<bool, String>,
) -> Result<bool, String> {
    health()?;
    let result = query()?;
    health()?;
    Ok(result)
}

#[cfg(any(target_os = "macos", test))]
pub(crate) fn any_physical_key_pressed(
    keys: impl IntoIterator<Item = u16>,
    mut query: impl FnMut(u16) -> bool,
) -> bool {
    keys.into_iter().any(&mut query)
}

pub(crate) async fn wait_for_release(
    mut is_pressed: impl FnMut() -> Result<bool, String>,
    is_current: impl Fn() -> bool,
    timeout: Duration,
) -> Result<(), String> {
    let deadline = tokio::time::Instant::now() + timeout;
    loop {
        if !is_current() {
            return Err("hotkey registration superseded".to_string());
        }
        let pressed = is_pressed()?;
        if !is_current() {
            return Err("hotkey registration superseded".to_string());
        }
        if !pressed {
            return Ok(());
        }
        if tokio::time::Instant::now() >= deadline {
            return Err("Shortcut release could not be confirmed".to_string());
        }
        tokio::time::sleep(Duration::from_millis(20)).await;
    }
}

pub(crate) fn input_monitoring_trusted() -> bool {
    #[cfg(target_os = "macos")]
    {
        #[link(name = "CoreGraphics", kind = "framework")]
        extern "C" {
            fn CGPreflightListenEventAccess() -> bool;
        }
        unsafe { CGPreflightListenEventAccess() }
    }
    #[cfg(not(target_os = "macos"))]
    {
        true
    }
}

#[cfg(target_os = "macos")]
pub(crate) fn physical_key_snapshot() -> Result<Vec<u16>, String> {
    #[link(name = "CoreGraphics", kind = "framework")]
    extern "C" {
        fn CGEventSourceKeyState(state_id: i32, key: u16) -> bool;
        fn CGEventSourceFlagsState(state_id: i32) -> u64;
    }
    let check_permission = || -> Result<(), String> {
        if input_monitoring_trusted() {
            Ok(())
        } else {
            Err("Shortcut release query requires Input Monitoring permission".into())
        }
    };
    check_permission()?;
    let mut keys: Vec<u16> = (0..128)
        .filter(|key| unsafe { CGEventSourceKeyState(1, *key) })
        .collect();
    if unsafe { CGEventSourceFlagsState(1) } & 0x0080_0000 != 0 && !keys.contains(&63) {
        keys.push(63);
    }
    check_permission()?;
    Ok(keys)
}

pub(crate) fn native_key_pressed() -> Result<bool, String> {
    #[cfg(target_os = "macos")]
    {
        #[link(name = "CoreGraphics", kind = "framework")]
        extern "C" {
            fn CGEventSourceKeyState(state_id: i32, key: u16) -> bool;
            fn CGEventSourceFlagsState(state_id: i32) -> u64;
        }
        if !input_monitoring_trusted() {
            return Err("Shortcut release query requires Input Monitoring permission".to_string());
        }
        Ok(unsafe {
            any_physical_key_pressed(0..128, |key| CGEventSourceKeyState(1, key))
                || CGEventSourceFlagsState(1) & 0x0080_0000 != 0
        })
    }
    #[cfg(target_os = "windows")]
    {
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::GetAsyncKeyState;
        Ok(unsafe { GetAsyncKeyState(0xA5) < 0 })
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        Err("Native shortcut release query is unavailable".to_string())
    }
}

pub(crate) fn all_keys_released() -> Result<bool, String> {
    #[cfg(target_os = "macos")]
    {
        physical_key_snapshot().map(|keys| keys.is_empty())
    }
    #[cfg(target_os = "windows")]
    {
        use windows_sys::Win32::UI::Input::KeyboardAndMouse::GetAsyncKeyState;
        Ok(!(1..=254).any(|key| unsafe { GetAsyncKeyState(key) < 0 }))
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows")))]
    {
        Err("Physical key release query is unavailable".to_string())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn release_query_rejects_health_lost_during_physical_scan() {
        let healthy = std::cell::Cell::new(true);
        let result = query_with_health_check(
            || {
                if healthy.get() {
                    Ok(())
                } else {
                    Err("monitor unavailable".to_string())
                }
            },
            || {
                healthy.set(false);
                Ok(false)
            },
        );
        assert_eq!(result, Err("monitor unavailable".to_string()));
    }

    #[test]
    fn release_query_does_not_scan_an_unavailable_monitor() {
        let result = query_with_health_check(
            || Err("monitor unavailable".to_string()),
            || panic!("unavailable monitor performed a physical scan"),
        );
        assert_eq!(result, Err("monitor unavailable".to_string()));
    }

    #[test]
    fn physical_release_query_checks_combo_keys_after_the_base_is_up() {
        let mut queried = Vec::new();
        let pressed = any_physical_key_pressed(0..128, |key| {
            queried.push(key);
            key == 56
        });
        assert!(pressed);
        assert!(queried.contains(&49));
        assert!(queried.contains(&56));
        assert!(!any_physical_key_pressed(0..128, |_| false));
    }

    #[tokio::test(start_paused = true)]
    async fn a_generation_changed_during_query_cannot_report_release() {
        let current = std::cell::Cell::new(true);
        let result = wait_for_release(
            || {
                current.set(false);
                Ok(false)
            },
            || current.get(),
            Duration::from_secs(2),
        )
        .await;
        assert_eq!(result.unwrap_err(), "hotkey registration superseded");
    }

    #[tokio::test(start_paused = true)]
    async fn waits_for_observed_release_instead_of_a_fixed_delay() {
        let mut readings = [Ok(true), Ok(true), Ok(false)].into_iter();
        let mut queries = 0;
        wait_for_release(
            || {
                queries += 1;
                readings.next().unwrap()
            },
            || true,
            std::time::Duration::from_secs(2),
        )
        .await
        .unwrap();
        assert_eq!(queries, 3);
    }

    #[tokio::test(start_paused = true)]
    async fn unknown_pressed_and_superseded_states_never_report_release() {
        assert!(
            wait_for_release(|| Err("unknown".into()), || true, Duration::from_secs(2))
                .await
                .is_err()
        );
        assert!(
            wait_for_release(|| Ok(true), || true, Duration::from_secs(2))
                .await
                .is_err()
        );
        let mut queried = false;
        assert_eq!(
            wait_for_release(
                || {
                    queried = true;
                    Ok(false)
                },
                || false,
                Duration::from_secs(2),
            )
            .await
            .unwrap_err(),
            "hotkey registration superseded"
        );
        assert!(!queried);
    }
}
