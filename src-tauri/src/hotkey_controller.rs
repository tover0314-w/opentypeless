use std::sync::{Arc, Mutex};
use std::time::Duration;

pub const HOTKEY_SUPERVISOR_RETRY_DELAY_SECS: u64 = 3;
pub const HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT: u8 = 3;

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecoveryFailure {
    PermissionRequired,
    InputMonitoringRequired,
    InvalidConfiguration,
    ReleaseUnconfirmed,
    Temporary,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum HotkeySupervisorState {
    Starting,
    Installed,
    Failed,
    WaitingPermission,
    WaitingRelease,
    InvalidConfiguration,
    Disabled,
    Stopped,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct BindingRegistration {
    pub role: String,
    pub index: usize,
    pub error: Option<String>,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct HotkeySupervisorSnapshot {
    pub generation: u64,
    pub state: HotkeySupervisorState,
    pub retry_attempts: u8,
    pub last_error: Option<String>,
    pub bindings: Option<Vec<BindingRegistration>>,
}

#[derive(Debug)]
struct HotkeySupervisorInner {
    generation: u64,
    state: HotkeySupervisorState,
    retry_attempts: u8,
    last_error: Option<String>,
    capture: crate::shortcut_lease::CaptureLeaseState,
    bindings: Option<Vec<BindingRegistration>>,
    accessibility: Option<bool>,
    input_monitoring: Option<bool>,
    waiting_permission: Option<RecoveryFailure>,
    native_permission_blocked: bool,
}

impl HotkeySupervisorInner {
    fn waiting_permission_is_granted(&self) -> bool {
        match self.waiting_permission {
            Some(RecoveryFailure::PermissionRequired) => self.accessibility == Some(true),
            Some(RecoveryFailure::InputMonitoringRequired) => {
                self.accessibility == Some(true) && self.input_monitoring == Some(true)
            }
            _ => false,
        }
    }

    fn wake_granted_permission(&mut self) {
        if self.state == HotkeySupervisorState::WaitingPermission
            && self.waiting_permission_is_granted()
            && self.capture.actions_allowed()
        {
            self.state = HotkeySupervisorState::Failed;
            self.retry_attempts = 0;
            self.waiting_permission = None;
        }
    }

    fn apply_registration_result(&mut self, error: Option<String>) {
        match error {
            Some(message) => {
                if self.accessibility == Some(false) && self.native_permission_blocked {
                    self.state = HotkeySupervisorState::WaitingPermission;
                    self.waiting_permission = Some(RecoveryFailure::PermissionRequired);
                    self.retry_attempts = 0;
                } else {
                    self.state = HotkeySupervisorState::Failed;
                    self.retry_attempts = self.retry_attempts.saturating_add(1);
                }
                self.last_error = Some(message);
            }
            None => {
                self.state = HotkeySupervisorState::Installed;
                self.waiting_permission = None;
                self.retry_attempts = 0;
                self.last_error = None;
            }
        }
    }
}

#[derive(Clone, Debug)]
pub struct HotkeySupervisor {
    state: Arc<Mutex<HotkeySupervisorInner>>,
    platform_operation: Arc<Mutex<()>>,
}

impl Default for HotkeySupervisor {
    fn default() -> Self {
        Self {
            state: Arc::new(Mutex::new(HotkeySupervisorInner {
                generation: 1,
                state: HotkeySupervisorState::Starting,
                retry_attempts: 0,
                last_error: None,
                capture: crate::shortcut_lease::CaptureLeaseState::default(),
                bindings: None,
                accessibility: None,
                input_monitoring: None,
                waiting_permission: None,
                native_permission_blocked: false,
            })),
            platform_operation: Arc::new(Mutex::new(())),
        }
    }
}

impl HotkeySupervisor {
    pub fn record_native_permission_blocked(&self, generation: u64, blocked: bool) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation || guard.state != HotkeySupervisorState::Starting {
            return false;
        }
        guard.native_permission_blocked = blocked;
        true
    }

    pub fn observe_accessibility(&self, trusted: bool) {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return;
        }
        let previous = guard.accessibility.replace(trusted);
        if trusted
            && previous != Some(true)
            && guard.state == HotkeySupervisorState::Failed
            && guard.capture.actions_allowed()
        {
            guard.state = HotkeySupervisorState::Failed;
            guard.retry_attempts = 0;
        }
        guard.wake_granted_permission();
    }

    pub fn observe_input_monitoring(&self, trusted: bool) {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return;
        }
        guard.input_monitoring = Some(trusted);
        guard.wake_granted_permission();
    }

    pub fn record_binding_results(
        &self,
        generation: u64,
        bindings: Vec<BindingRegistration>,
    ) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation || guard.state != HotkeySupervisorState::Starting {
            return false;
        }
        guard.bindings = Some(bindings);
        true
    }

    pub fn role_actions_allowed(&self, role: &str) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if !guard.capture.actions_allowed() {
            return false;
        }
        match guard.state {
            HotkeySupervisorState::Installed
            | HotkeySupervisorState::Failed
            | HotkeySupervisorState::WaitingPermission => guard.bindings.as_ref().map_or(
                guard.state == HotkeySupervisorState::Installed,
                |bindings| {
                    bindings
                        .iter()
                        .any(|binding| binding.role == role && binding.error.is_none())
                },
            ),
            _ => false,
        }
    }

    pub fn shutdown(&self) {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return;
        }
        guard.generation = guard.generation.saturating_add(1);
        guard.state = HotkeySupervisorState::Stopped;
        guard.retry_attempts = 0;
        guard.last_error = None;
    }

    pub fn capture_allows_registration(&self) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        guard.state != HotkeySupervisorState::Stopped && guard.capture.actions_allowed()
    }

    pub fn settings_registration_allowed(&self) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        !matches!(
            guard.state,
            HotkeySupervisorState::Disabled
                | HotkeySupervisorState::WaitingRelease
                | HotkeySupervisorState::Stopped
        ) && guard.capture.actions_allowed()
    }

    pub fn publish_registered_configuration(
        &self,
        generation: u64,
        publish: impl FnOnce(),
    ) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation
            || !guard.capture.actions_allowed()
            || guard.bindings.is_none()
            || !matches!(
                guard.state,
                HotkeySupervisorState::Installed
                    | HotkeySupervisorState::Failed
                    | HotkeySupervisorState::WaitingPermission
            )
        {
            return false;
        }
        publish();
        true
    }

    pub fn observe_input_for_generation(&self, generation: u64, observe: impl FnOnce()) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation || guard.state == HotkeySupervisorState::Stopped {
            return false;
        }
        observe();
        true
    }

    pub fn permission_recovery_capture(&self) -> Option<crate::shortcut_lease::CaptureLease> {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::WaitingPermission
            && guard.waiting_permission_is_granted()
        {
            guard.capture.failed_released_capture()
        } else {
            None
        }
    }

    pub fn release_recovery_capture_for(
        &self,
        generation: u64,
        pressed: Option<bool>,
    ) -> Option<crate::shortcut_lease::CaptureLease> {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation == generation
            && guard.state == HotkeySupervisorState::WaitingRelease
            && pressed == Some(false)
        {
            guard.capture.failed_released_capture()
        } else {
            None
        }
    }

    pub fn observe_release_for_recovery(&self, generation: u64, pressed: Option<bool>) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation
            || guard.state != HotkeySupervisorState::WaitingRelease
            || pressed != Some(false)
            || !guard.capture.actions_allowed()
        {
            return false;
        }
        guard.state = HotkeySupervisorState::Failed;
        guard.retry_attempts = 0;
        true
    }

    pub fn release_capture_window(
        &self,
        label: &str,
    ) -> Option<crate::shortcut_lease::CaptureLease> {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return None;
        }
        guard.capture.release_current_window(label)
    }

    pub fn fail_capture_recovery(
        &self,
        generation: u64,
        lease_generation: Option<u64>,
        error: String,
        failure: RecoveryFailure,
        update: impl FnOnce(),
    ) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation || guard.state != HotkeySupervisorState::Starting {
            return false;
        }
        if let Some(lease_generation) = lease_generation {
            if !guard
                .capture
                .recovery_failed(lease_generation, error.clone())
            {
                return false;
            }
        }
        guard.state = match failure {
            RecoveryFailure::PermissionRequired | RecoveryFailure::InputMonitoringRequired => {
                HotkeySupervisorState::WaitingPermission
            }
            RecoveryFailure::InvalidConfiguration => HotkeySupervisorState::InvalidConfiguration,
            RecoveryFailure::ReleaseUnconfirmed => HotkeySupervisorState::WaitingRelease,
            RecoveryFailure::Temporary => HotkeySupervisorState::Failed,
        };
        guard.last_error = Some(error);
        guard.waiting_permission = match failure {
            RecoveryFailure::PermissionRequired | RecoveryFailure::InputMonitoringRequired => {
                Some(failure)
            }
            _ => None,
        };
        guard.retry_attempts = if guard.waiting_permission.is_some() {
            0
        } else {
            guard.retry_attempts.saturating_add(1)
        };
        guard.wake_granted_permission();
        update();
        true
    }

    pub fn actions_allowed(&self) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        guard.state == HotkeySupervisorState::Installed && guard.capture.actions_allowed()
    }

    pub fn session_completion_allowed(&self) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        !matches!(
            guard.state,
            HotkeySupervisorState::Disabled | HotkeySupervisorState::Stopped
        ) && guard.capture.actions_allowed()
    }

    pub fn acquire_capture(
        &self,
        window_label: &str,
        capture_id: &str,
    ) -> Result<crate::shortcut_lease::CaptureLease, String> {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return Err("Shortcut controller is stopped".into());
        }
        let duplicate = guard.capture.owned_by(window_label, capture_id);
        let lease = guard.capture.acquire(window_label, capture_id)?;
        if duplicate {
            return Ok(lease);
        }
        guard.generation = guard.generation.saturating_add(1);
        guard.state = HotkeySupervisorState::Disabled;
        guard.retry_attempts = 0;
        guard.last_error = None;
        Ok(lease)
    }

    pub fn begin_capture_resume(
        &self,
        capture_id: Option<&str>,
    ) -> Result<(Option<u64>, u64), String> {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return Err("Shortcut controller is stopped".into());
        }
        let lease_generation = guard.capture.begin_resume(capture_id)?;
        guard.bindings = None;
        guard.state = HotkeySupervisorState::Starting;
        Ok((lease_generation, guard.generation))
    }

    pub fn capture_recovery_is_current(&self, generation: u64) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        guard.state != HotkeySupervisorState::Stopped
            && guard.capture.recovery_is_current(generation)
    }

    pub fn confirm_capture_released(&self, generation: u64, pressed: Option<bool>) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return false;
        }
        guard.capture.confirm_released(generation, pressed)
    }

    pub fn finish_capture_registration(
        &self,
        generation: u64,
        lease_generation: Option<u64>,
        error: Option<String>,
        update: impl FnOnce(),
    ) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation || guard.state != HotkeySupervisorState::Starting {
            return false;
        }
        if let Some(lease_generation) = lease_generation {
            if !guard
                .capture
                .finish_registration(lease_generation, error.clone())
            {
                return false;
            }
        } else if !guard.capture.actions_allowed() {
            return false;
        }
        guard.apply_registration_result(error);
        update();
        true
    }

    pub fn snapshot(&self) -> HotkeySupervisorSnapshot {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        HotkeySupervisorSnapshot {
            generation: guard.generation,
            state: guard.state,
            retry_attempts: guard.retry_attempts,
            last_error: guard.last_error.clone(),
            bindings: guard.bindings.clone(),
        }
    }

    pub fn begin_registration_attempt(&self) -> u64 {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return guard.generation;
        }
        guard.state = HotkeySupervisorState::Starting;
        guard.bindings = None;
        guard.native_permission_blocked = false;
        guard.generation
    }

    pub fn begin_retry_registration_attempt(&self) -> Option<u64> {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state != HotkeySupervisorState::Failed
            || guard.retry_attempts > HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT
        {
            return None;
        }
        guard.state = HotkeySupervisorState::Starting;
        guard.bindings = None;
        guard.native_permission_blocked = false;
        Some(guard.generation)
    }

    pub fn retry_failed_on_focus(&self) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state != HotkeySupervisorState::Failed {
            return false;
        }
        guard.retry_attempts = 0;
        true
    }

    pub fn record_monitor_failure(&self, generation: u64, message: String) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation
            || !matches!(
                guard.state,
                HotkeySupervisorState::Installed | HotkeySupervisorState::Failed
            )
            || (guard.state == HotkeySupervisorState::Failed
                && !guard
                    .bindings
                    .as_ref()
                    .is_some_and(|bindings| bindings.iter().any(|binding| binding.error.is_none())))
        {
            return false;
        }
        guard.state = HotkeySupervisorState::Failed;
        guard.bindings = None;
        guard.retry_attempts = 0;
        guard.last_error = Some(message);
        true
    }

    pub fn wake_for_settings_change(&self) -> u64 {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if matches!(
            guard.state,
            HotkeySupervisorState::Stopped | HotkeySupervisorState::WaitingRelease
        ) {
            return guard.generation;
        }
        guard.generation = guard.generation.saturating_add(1);
        guard.state = HotkeySupervisorState::Starting;
        guard.bindings = None;
        guard.native_permission_blocked = false;
        guard.retry_attempts = 0;
        guard.last_error = None;
        guard.generation
    }

    pub fn begin_rollback(&self, generation: u64) -> Option<u64> {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation
            || guard.state == HotkeySupervisorState::Stopped
            || !guard.capture.actions_allowed()
        {
            return None;
        }
        guard.generation = guard.generation.saturating_add(1);
        guard.state = HotkeySupervisorState::Starting;
        guard.bindings = None;
        guard.retry_attempts = 0;
        guard.last_error = None;
        Some(guard.generation)
    }

    pub fn is_current_generation(&self, generation: u64) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        guard.generation == generation && guard.state != HotkeySupervisorState::Stopped
    }

    fn registration_is_current(&self, generation: u64) -> bool {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        guard.generation == generation
            && guard.state == HotkeySupervisorState::Starting
            && guard.capture.registration_allowed()
    }

    pub fn run_if_current_generation<Result>(
        &self,
        generation: u64,
        operation: impl FnOnce() -> Result,
    ) -> Option<Result> {
        let _operation = self
            .platform_operation
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if !self.registration_is_current(generation) {
            return None;
        }
        let result = operation();
        self.registration_is_current(generation).then_some(result)
    }

    pub fn record_registration_success(&self, generation: u64) {
        self.record_registration_result(generation, None, || {});
    }

    pub fn record_registration_failure(&self, generation: u64, message: String) {
        self.record_registration_result(generation, Some(message), || {});
    }

    pub fn record_registration_result(
        &self,
        generation: u64,
        error: Option<String>,
        update: impl FnOnce(),
    ) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation
            || guard.state == HotkeySupervisorState::Stopped
            || !guard.capture.actions_allowed()
        {
            return false;
        }
        guard.apply_registration_result(error);
        update();
        true
    }

    pub fn record_disabled(&self, generation: u64) {
        self.record_disabled_with_feedback(generation, || {});
    }

    pub fn record_disabled_with_feedback(&self, generation: u64, update: impl FnOnce()) -> bool {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.generation != generation || guard.state == HotkeySupervisorState::Stopped {
            return false;
        }
        guard.state = HotkeySupervisorState::Disabled;
        guard.last_error = None;
        update();
        true
    }

    pub fn disable(&self) -> u64 {
        let mut guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Stopped {
            return guard.generation;
        }
        guard.generation = guard.generation.saturating_add(1);
        guard.state = HotkeySupervisorState::Disabled;
        guard.retry_attempts = 0;
        guard.last_error = None;
        guard.generation
    }

    pub fn next_retry_delay(&self) -> Option<Duration> {
        let guard = self.state.lock().unwrap_or_else(|error| error.into_inner());
        if guard.state == HotkeySupervisorState::Failed
            && guard.retry_attempts <= HOTKEY_SUPERVISOR_FAST_RETRY_LIMIT
        {
            Some(Duration::from_secs(HOTKEY_SUPERVISOR_RETRY_DELAY_SECS))
        } else {
            None
        }
    }
}
