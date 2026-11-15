use std::collections::HashMap;

#[derive(Default)]
pub(crate) struct ShortcutEventConsumption {
    held: HashMap<u16, bool>,
}

impl ShortcutEventConsumption {
    #[cfg(test)]
    pub(crate) fn key_event(
        &mut self,
        keycode: u16,
        pressed: bool,
        matched: bool,
        actions_allowed: bool,
    ) -> bool {
        self.key_event_with_acceptance(keycode, pressed, || matched && actions_allowed)
    }

    pub(crate) fn key_event_with_acceptance(
        &mut self,
        keycode: u16,
        pressed: bool,
        accept: impl FnOnce() -> bool,
    ) -> bool {
        if pressed {
            *self.held.entry(keycode).or_insert_with(accept)
        } else {
            self.held.remove(&keycode).unwrap_or(false)
        }
    }
}
