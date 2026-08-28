use crate::error::AppError;

#[cfg(target_os = "windows")]
const MODIFIER_RELEASE_POLL_INTERVAL_MS: u64 = 10;
#[cfg(target_os = "windows")]
const MODIFIER_RELEASE_MAX_WAIT_CYCLES: usize = 50;

#[cfg(any(target_os = "windows", test))]
fn wait_until_modifiers_released(
    mut any_modifier_is_down: impl FnMut() -> bool,
    mut pause: impl FnMut(),
    max_wait_cycles: usize,
) -> bool {
    for cycle in 0..=max_wait_cycles {
        if !any_modifier_is_down() {
            return true;
        }
        if cycle < max_wait_cycles {
            pause();
        }
    }
    false
}

#[cfg(target_os = "windows")]
fn any_modifier_is_down() -> bool {
    use windows_sys::Win32::UI::Input::KeyboardAndMouse::{
        GetAsyncKeyState, VK_CONTROL, VK_LWIN, VK_MENU, VK_RWIN, VK_SHIFT,
    };

    [VK_SHIFT, VK_CONTROL, VK_MENU, VK_LWIN, VK_RWIN]
        .into_iter()
        .any(|virtual_key| unsafe { GetAsyncKeyState(virtual_key as i32) } < 0)
}

#[cfg(target_os = "windows")]
pub fn wait_for_modifier_release() -> Result<(), AppError> {
    let released = wait_until_modifiers_released(
        any_modifier_is_down,
        || {
            std::thread::sleep(std::time::Duration::from_millis(
                MODIFIER_RELEASE_POLL_INTERVAL_MS,
            ));
        },
        MODIFIER_RELEASE_MAX_WAIT_CYCLES,
    );

    if released {
        Ok(())
    } else {
        Err(AppError::Output(
            "Windows modifier keys remained pressed for 500 ms; skipped simulated input"
                .to_string(),
        ))
    }
}

#[cfg(not(target_os = "windows"))]
pub fn wait_for_modifier_release() -> Result<(), AppError> {
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::collections::VecDeque;

    #[test]
    fn waits_until_pressed_modifiers_are_released() {
        let mut states = VecDeque::from([true, true, false]);
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(
            || states.pop_front().expect("probe called too many times"),
            || sleeps += 1,
            50,
        );

        assert!(released);
        assert_eq!(sleeps, 2);
    }

    #[test]
    fn times_out_when_modifier_remains_pressed() {
        let mut checks = 0;
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(
            || {
                checks += 1;
                true
            },
            || sleeps += 1,
            2,
        );

        assert!(!released);
        assert_eq!(checks, 3);
        assert_eq!(sleeps, 2);
    }

    #[test]
    fn returns_immediately_when_no_modifier_is_pressed() {
        let mut sleeps = 0;

        let released = wait_until_modifiers_released(|| false, || sleeps += 1, 50);

        assert!(released);
        assert_eq!(sleeps, 0);
    }
}
