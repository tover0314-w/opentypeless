pub(crate) const KEYBOARD_OUTPUT_MARKER: i64 = 0x4f54_5950_454f_5554;

pub(crate) fn is_owned_keyboard_output(marker: i64, source_process: i64) -> bool {
    marker == KEYBOARD_OUTPUT_MARKER && source_process == i64::from(std::process::id())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn production_keyboard_output_and_native_callback_share_event_identity() {
        let keyboard = include_str!("keyboard.rs");
        assert!(keyboard.contains(
            "event_source_user_data: Some(super::event_identity::KEYBOARD_OUTPUT_MARKER)"
        ));
        assert!(keyboard.contains("Enigo::new(&settings)"));
        let native = include_str!("../native_hotkey.rs");
        let callback = native
            .split("    extern \"C\" fn event_tap_callback(")
            .nth(1)
            .unwrap()
            .split("    fn handle_flags_changed(")
            .next()
            .unwrap();
        assert!(
            callback.find("is_owned_keyboard_output(").unwrap()
                < callback.find("let context =").unwrap()
        );
        assert!(callback.contains("CGEventGetIntegerValueField(event, 42)"));
        assert!(callback.contains("CGEventGetIntegerValueField(event, 41)"));
    }

    #[test]
    fn both_marker_and_current_process_are_required() {
        let process = i64::from(std::process::id());
        assert!(is_owned_keyboard_output(KEYBOARD_OUTPUT_MARKER, process));
        assert!(!is_owned_keyboard_output(100, process));
        assert!(!is_owned_keyboard_output(
            KEYBOARD_OUTPUT_MARKER,
            process + 1
        ));
        assert!(!is_owned_keyboard_output(KEYBOARD_OUTPUT_MARKER, 0));
    }
}
