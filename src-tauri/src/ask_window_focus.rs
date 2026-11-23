use std::sync::Mutex;

use crate::app_detector::types::TargetAppGuard;

#[derive(Default)]
pub(crate) struct AskWindowFocus(Mutex<Option<TargetAppGuard>>);

impl AskWindowFocus {
    pub(crate) fn capture(&self, visible: bool, current: Option<TargetAppGuard>, own_pid: u32) {
        if visible {
            return;
        }
        let mut target = self.0.lock().unwrap_or_else(|error| error.into_inner());
        *target = current.filter(|identity| {
            identity
                .process_id
                .is_some_and(|process_id| process_id != own_pid)
        });
    }

    pub(crate) fn take_if_owned(
        &self,
        current: Option<&TargetAppGuard>,
        own_pid: u32,
    ) -> Option<TargetAppGuard> {
        let target = self
            .0
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take();
        current
            .is_some_and(|identity| identity.process_id == Some(own_pid))
            .then_some(target)
            .flatten()
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn identity(process_id: u32) -> TargetAppGuard {
        TargetAppGuard {
            process_id: Some(process_id),
            native_identity: Some(format!("synthetic.{process_id}")),
        }
    }

    #[test]
    fn repeated_show_preserves_original_target_and_dismiss_consumes_it() {
        let focus = AskWindowFocus::default();
        focus.capture(false, Some(identity(42)), 7);
        focus.capture(true, Some(identity(7)), 7);
        assert_eq!(
            focus.take_if_owned(Some(&identity(7)), 7),
            Some(identity(42))
        );
        assert_eq!(focus.take_if_owned(Some(&identity(7)), 7), None);
    }

    #[test]
    fn dismissal_never_steals_focus_from_another_application() {
        let focus = AskWindowFocus::default();
        focus.capture(false, Some(identity(42)), 7);
        assert_eq!(focus.take_if_owned(Some(&identity(99)), 7), None);
        assert_eq!(focus.take_if_owned(Some(&identity(7)), 7), None);
    }

    #[test]
    fn own_or_unknown_target_is_not_restored() {
        let focus = AskWindowFocus::default();
        focus.capture(false, Some(identity(7)), 7);
        assert_eq!(focus.take_if_owned(Some(&identity(7)), 7), None);
        focus.capture(false, Some(identity(42)), 7);
        assert_eq!(focus.take_if_owned(None, 7), None);
    }
}
