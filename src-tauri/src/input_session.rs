#[derive(Clone, Copy, Debug, Default, PartialEq, Eq)]
pub enum InputMode {
    #[default]
    Auto,
    Toggle,
    Hold,
}

impl InputMode {
    pub fn from_config(value: &str) -> Option<Self> {
        match value {
            "auto" => Some(Self::Auto),
            "toggle" => Some(Self::Toggle),
            "hold" => Some(Self::Hold),
            _ => None,
        }
    }
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum VoiceRole {
    Dictation,
    Translate,
    Ask,
    EditSelection,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct InputBinding {
    pub role: VoiceRole,
    pub binding_id: String,
    pub mode: InputMode,
    pub revision: u64,
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum SessionPhase {
    Preparing,
    Recording,
    Stopping,
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum InputAction {
    Start {
        session_id: u64,
        binding: InputBinding,
    },
    Stop {
        session_id: u64,
    },
    Cancel {
        session_id: u64,
    },
    ShowGuide {
        press_id: u64,
    },
    HideGuide {
        press_id: u64,
    },
}

struct ActiveSession {
    id: u64,
    binding: InputBinding,
    phase: SessionPhase,
    stop_requested: bool,
}

struct ActivePress {
    id: u64,
    binding: InputBinding,
    started_at_ms: u64,
    guide_visible: bool,
    session_id: Option<u64>,
    suppressed: bool,
    monitor_release_required: bool,
    started_session: bool,
}

struct BindingRevision {
    revision: u64,
    bindings: Vec<InputBinding>,
    installed: bool,
    installed_binding_ids: Vec<String>,
}

#[derive(Default)]
pub struct InputSession {
    next_identity: u64,
    session: Option<ActiveSession>,
    press: Option<ActivePress>,
    active_bindings: Option<BindingRevision>,
    pending_bindings: Option<BindingRevision>,
    suspended: bool,
    stopped: bool,
    physical_keys: Option<Option<Vec<u16>>>,
    resume_release_required: bool,
}

impl InputSession {
    pub fn press_combo_committed(
        &mut self,
        parent_binding_id: &str,
        binding_id: &str,
        role: VoiceRole,
        now_ms: u64,
    ) -> Vec<InputAction> {
        if self.press.is_none() {
            return self.press_committed(binding_id, role, now_ms);
        }
        if self.stopped
            || self.suspended
            || self.session.is_some()
            || !self.press.as_ref().is_some_and(|press| {
                press.binding.binding_id == parent_binding_id
                    && press.binding.mode == InputMode::Auto
                    && !press.suppressed
            })
        {
            return Vec::new();
        }
        let installed = self.active_bindings.as_ref().is_some_and(|config| {
            config.installed
                && config
                    .installed_binding_ids
                    .iter()
                    .any(|installed| installed == binding_id)
                && config
                    .bindings
                    .iter()
                    .any(|binding| binding.binding_id == binding_id && binding.role == role)
        });
        if !installed {
            return Vec::new();
        }
        let previous = self.press.take().unwrap();
        let mut actions = if previous.guide_visible {
            vec![InputAction::HideGuide {
                press_id: previous.id,
            }]
        } else {
            Vec::new()
        };
        actions.extend(self.press_committed(binding_id, role, now_ms));
        actions
    }

    pub fn commit_bindings(&mut self, revision: u64, bindings: Vec<InputBinding>) -> bool {
        let latest = self
            .pending_revision()
            .or(self.active_revision())
            .unwrap_or(0);
        if self.stopped
            || revision <= latest
            || bindings
                .iter()
                .any(|binding| binding.revision != revision || binding.binding_id.trim().is_empty())
            || bindings.iter().enumerate().any(|(index, binding)| {
                bindings[..index]
                    .iter()
                    .any(|previous| previous.binding_id == binding.binding_id)
            })
        {
            return false;
        }
        self.pending_bindings = Some(BindingRevision {
            revision,
            bindings,
            installed: false,
            installed_binding_ids: Vec::new(),
        });
        self.activate_pending();
        true
    }

    #[cfg(test)]
    pub fn confirm_installation(&mut self, revision: u64) -> bool {
        let binding_ids = self
            .pending_bindings
            .as_ref()
            .filter(|config| config.revision == revision)
            .or_else(|| {
                self.active_bindings
                    .as_ref()
                    .filter(|config| config.revision == revision)
            })
            .map(|config| {
                config
                    .bindings
                    .iter()
                    .map(|binding| binding.binding_id.clone())
                    .collect()
            });
        binding_ids
            .is_some_and(|binding_ids| self.confirm_binding_installation(revision, binding_ids))
    }

    pub fn confirm_binding_installation(
        &mut self,
        revision: u64,
        binding_ids: Vec<String>,
    ) -> bool {
        if self.stopped {
            return false;
        }
        let config = if self.pending_revision() == Some(revision) {
            self.pending_bindings.as_mut()
        } else {
            self.active_bindings
                .as_mut()
                .filter(|config| config.revision == revision)
        };
        let Some(config) = config else {
            return false;
        };
        if binding_ids.iter().any(|binding_id| {
            !config
                .bindings
                .iter()
                .any(|binding| &binding.binding_id == binding_id)
        }) {
            return false;
        }
        config.installed_binding_ids = binding_ids;
        config.installed = true;
        self.activate_pending();
        true
    }

    pub fn installation_failed(&mut self, revision: u64) -> bool {
        let Some(config) = self
            .pending_bindings
            .as_mut()
            .filter(|config| config.revision == revision)
        else {
            return false;
        };
        config.installed = false;
        true
    }

    pub fn monitor_lost(&mut self, revision: u64) -> bool {
        let Some(config) = self
            .active_bindings
            .as_mut()
            .filter(|config| config.revision == revision)
        else {
            return false;
        };
        config.installed = false;
        if self.press.is_some() {
            self.resume_release_required = true;
            self.physical_keys = Some(None);
            if let Some(press) = self.press.as_mut() {
                press.monitor_release_required = true;
                if self.session.is_none() {
                    press.suppressed = true;
                }
            }
        }
        true
    }

    pub fn active_revision(&self) -> Option<u64> {
        self.active_bindings.as_ref().map(|config| config.revision)
    }

    pub fn pending_revision(&self) -> Option<u64> {
        self.pending_bindings.as_ref().map(|config| config.revision)
    }

    pub fn press_committed(
        &mut self,
        binding_id: &str,
        role: VoiceRole,
        now_ms: u64,
    ) -> Vec<InputAction> {
        if let Some(binding) = self
            .session
            .as_ref()
            .filter(|session| {
                session.binding.binding_id == binding_id && session.binding.role == role
            })
            .map(|session| session.binding.clone())
        {
            return self.press(binding, now_ms);
        }
        let binding = self.active_bindings.as_ref().and_then(|config| {
            if !config.installed {
                return None;
            }
            config
                .bindings
                .iter()
                .find(|binding| {
                    binding.binding_id == binding_id
                        && binding.role == role
                        && config
                            .installed_binding_ids
                            .iter()
                            .any(|installed| installed == binding_id)
                })
                .cloned()
        });
        match binding {
            Some(binding) => self.press(binding, now_ms),
            None => Vec::new(),
        }
    }

    pub fn press(&mut self, binding: InputBinding, now_ms: u64) -> Vec<InputAction> {
        if self.stopped || self.suspended || self.press.is_some() {
            return Vec::new();
        }
        if self.session.is_none()
            && (self.resume_release_required
                || (self.pending_bindings.is_some() && !self.physical_release_confirmed()))
        {
            return Vec::new();
        }
        let effective_binding = match self.session.as_ref() {
            Some(session) => {
                if session.binding.binding_id != binding.binding_id
                    || session.binding.role != binding.role
                    || session.phase == SessionPhase::Stopping
                {
                    return Vec::new();
                }
                session.binding.clone()
            }
            None => binding,
        };
        let monitor_release_required = self.session.is_some()
            && self
                .active_bindings
                .as_ref()
                .is_some_and(|config| !config.installed);
        if monitor_release_required {
            self.resume_release_required = true;
            self.physical_keys = Some(None);
        }
        let press_id = self.next_identity();
        self.press = Some(ActivePress {
            id: press_id,
            binding: effective_binding.clone(),
            started_at_ms: now_ms,
            guide_visible: false,
            session_id: self.session_id(),
            suppressed: false,
            monitor_release_required,
            started_session: false,
        });
        if effective_binding.role == VoiceRole::Ask {
            return if self.session.is_some() {
                self.request_stop()
            } else {
                self.start(effective_binding)
            };
        }
        match effective_binding.mode {
            InputMode::Auto => Vec::new(),
            InputMode::Hold if self.session.is_some() => Vec::new(),
            _ if self.session.is_some() => self.request_stop(),
            _ => self.start(effective_binding),
        }
    }

    pub fn release(&mut self, binding_id: &str, now_ms: u64) -> Vec<InputAction> {
        if self
            .press
            .as_ref()
            .is_none_or(|press| press.binding.binding_id != binding_id)
        {
            return Vec::new();
        }
        let press = self.press.take().unwrap();
        if press.suppressed {
            self.activate_pending();
            return if press.guide_visible {
                vec![InputAction::HideGuide { press_id: press.id }]
            } else {
                Vec::new()
            };
        }
        if press.session_id.is_some() && press.session_id != self.session_id() {
            self.activate_pending();
            return Vec::new();
        }
        if press.guide_visible {
            self.activate_pending();
            return vec![InputAction::HideGuide { press_id: press.id }];
        }
        if press.started_session
            && press.binding.mode == InputMode::Toggle
            && press.binding.role == VoiceRole::Dictation
            && now_ms.saturating_sub(press.started_at_ms) >= 600
            && press.session_id == self.session_id()
            && self.session.as_ref().is_some_and(|session| {
                session.phase != SessionPhase::Stopping && !session.stop_requested
            })
        {
            if let Some(session_id) = press.session_id {
                self.finish(session_id);
                return vec![InputAction::Cancel { session_id }];
            }
        }
        if press.binding.role == VoiceRole::Ask {
            if press.binding.mode == InputMode::Auto
                && now_ms.saturating_sub(press.started_at_ms) >= 600
                && press.session_id == self.session_id()
                && self.session.as_ref().is_some_and(|session| {
                    session.phase != SessionPhase::Stopping && !session.stop_requested
                })
            {
                if let Some(session_id) = press.session_id {
                    self.finish(session_id);
                    return vec![InputAction::Cancel { session_id }];
                }
            }
            self.activate_pending();
            return Vec::new();
        }
        let actions = match press.binding.mode {
            InputMode::Hold => self.request_stop(),
            InputMode::Toggle => Vec::new(),
            InputMode::Auto if now_ms.saturating_sub(press.started_at_ms) >= 600 => Vec::new(),
            InputMode::Auto if self.session.is_some() => self.request_stop(),
            InputMode::Auto => self.start(press.binding),
        };
        self.activate_pending();
        actions
    }

    pub fn long_press_due(&mut self, press_id: u64, now_ms: u64) -> Vec<InputAction> {
        if self.stopped || self.suspended {
            return Vec::new();
        }
        let Some(press) = self.press.as_ref() else {
            return Vec::new();
        };
        let owns_started_session = press.started_session
            && (press.binding.mode == InputMode::Auto && press.binding.role == VoiceRole::Ask
                || press.binding.mode == InputMode::Toggle
                    && press.binding.role == VoiceRole::Dictation)
            && press.session_id.is_some()
            && press.session_id == self.session_id()
            && self.session.as_ref().is_some_and(|session| {
                session.phase != SessionPhase::Stopping && !session.stop_requested
            });
        if press.id != press_id
            || press.suppressed
            || (press.binding.mode != InputMode::Auto && !owns_started_session)
            || press.guide_visible
            || now_ms.saturating_sub(press.started_at_ms) < 600
            || (!owns_started_session && (press.session_id.is_some() || self.session.is_some()))
        {
            return Vec::new();
        }
        let cancelled_session = owns_started_session.then_some(press.session_id).flatten();
        let press = self.press.as_mut().unwrap();
        press.guide_visible = true;
        if cancelled_session.is_some() {
            press.session_id = None;
        }
        let mut actions = Vec::new();
        if let Some(session_id) = cancelled_session {
            self.finish(session_id);
            actions.push(InputAction::Cancel { session_id });
        }
        actions.push(InputAction::ShowGuide { press_id });
        actions
    }

    pub fn guide_delay(&self, press_id: u64, now_ms: u64) -> Option<u64> {
        let press = self.press.as_ref()?;
        let owns_started_session = press.started_session
            && (press.binding.mode == InputMode::Auto && press.binding.role == VoiceRole::Ask
                || press.binding.mode == InputMode::Toggle
                    && press.binding.role == VoiceRole::Dictation)
            && press.session_id.is_some()
            && press.session_id == self.session_id()
            && self.session.as_ref().is_some_and(|session| {
                session.phase != SessionPhase::Stopping && !session.stop_requested
            });
        if self.stopped
            || self.suspended
            || press.id != press_id
            || press.suppressed
            || press.guide_visible
            || (press.binding.mode != InputMode::Auto && !owns_started_session)
            || (!owns_started_session && (press.session_id.is_some() || self.session.is_some()))
        {
            return None;
        }
        Some(600_u64.saturating_sub(now_ms.saturating_sub(press.started_at_ms)))
    }

    pub fn ready(&mut self, session_id: u64) -> Vec<InputAction> {
        let Some(session) = self.session.as_mut() else {
            return Vec::new();
        };
        if session.id != session_id || session.phase != SessionPhase::Preparing {
            return Vec::new();
        }
        if session.stop_requested {
            session.phase = SessionPhase::Stopping;
            vec![InputAction::Stop { session_id }]
        } else {
            session.phase = SessionPhase::Recording;
            Vec::new()
        }
    }

    #[cfg(test)]
    pub fn cancel(&mut self, session_id: u64) -> bool {
        self.finish(session_id)
    }

    pub fn suspend(&mut self) {
        self.suspended = true;
        self.resume_release_required = true;
        if let Some(press) = self.press.as_mut() {
            if press.session_id.is_none() {
                press.suppressed = true;
            }
        }
    }

    pub fn observe_physical_keys(&mut self, keys: Option<Vec<u16>>) -> Vec<InputAction> {
        if self.stopped {
            return Vec::new();
        }
        let mut actions = Vec::new();
        self.physical_keys = Some(keys);
        if self.physical_release_confirmed() {
            self.resume_release_required = false;
            if self.session.is_none()
                && self
                    .press
                    .as_ref()
                    .is_some_and(|press| press.suppressed || press.monitor_release_required)
            {
                if let Some(press) = self.press.take() {
                    if press.guide_visible {
                        actions.push(InputAction::HideGuide { press_id: press.id });
                    }
                }
            }
        } else if self.suspended {
            self.resume_release_required = true;
        }
        self.activate_pending();
        actions
    }

    pub fn resume(&mut self) -> bool {
        if self.stopped {
            return false;
        }
        self.suspended = false;
        self.activate_pending();
        true
    }

    pub fn requires_release_observation(&self) -> bool {
        !self.stopped
            && (self.suspended
                || self.resume_release_required
                || !self.physical_release_confirmed())
    }

    pub fn shutdown(&mut self) {
        self.stopped = true;
        self.suspended = true;
        self.session = None;
        self.press = None;
        self.pending_bindings = None;
        self.active_bindings = None;
    }

    pub fn finish(&mut self, session_id: u64) -> bool {
        if self
            .session
            .as_ref()
            .is_none_or(|session| session.id != session_id)
        {
            return false;
        }
        self.session = None;
        if let Some(press) = self.press.as_mut().filter(|press| {
            press.started_session
                && press.binding.mode == InputMode::Toggle
                && press.session_id == Some(session_id)
        }) {
            press.session_id = None;
            press.suppressed = true;
        }
        if self
            .press
            .as_ref()
            .is_some_and(|press| press.monitor_release_required)
            && self.physical_release_confirmed()
        {
            self.press = None;
        }
        self.activate_pending();
        true
    }

    #[cfg(test)]
    pub fn phase(&self) -> Option<SessionPhase> {
        self.session.as_ref().map(|session| session.phase)
    }

    pub fn session_id(&self) -> Option<u64> {
        self.session.as_ref().map(|session| session.id)
    }

    pub fn session_binding(&self) -> Option<&InputBinding> {
        self.session.as_ref().map(|session| &session.binding)
    }

    pub fn session_revision(&self, session_id: u64) -> Option<u64> {
        self.session
            .as_ref()
            .filter(|session| session.id == session_id)
            .map(|session| session.binding.revision)
    }

    pub fn owns_session_binding(&self, binding_id: &str, role: VoiceRole) -> bool {
        !self.stopped
            && !self.suspended
            && self.session.as_ref().is_some_and(|session| {
                session.binding.binding_id == binding_id && session.binding.role == role
            })
    }

    pub fn owns_press_binding(&self, binding_id: &str, role: VoiceRole) -> bool {
        !self.stopped
            && self.press.as_ref().is_some_and(|press| {
                press.binding.binding_id == binding_id && press.binding.role == role
            })
    }

    pub fn owns_suppressed_release(&self, binding_id: &str, role: VoiceRole) -> bool {
        self.owns_press_binding(binding_id, role)
            && self.press.as_ref().is_some_and(|press| press.suppressed)
    }

    pub fn press_id(&self) -> Option<u64> {
        self.press.as_ref().map(|press| press.id)
    }

    pub fn permits_reconfiguration(&self) -> bool {
        !self.stopped
            && !self.suspended
            && !self.resume_release_required
            && self.session.is_none()
            && self.press.is_none()
            && self.physical_release_confirmed()
    }

    fn next_identity(&mut self) -> u64 {
        self.next_identity = self
            .next_identity
            .checked_add(1)
            .expect("Input identity exhausted");
        self.next_identity
    }

    fn activate_pending(&mut self) {
        if self.permits_reconfiguration()
            && self
                .pending_bindings
                .as_ref()
                .is_some_and(|config| config.installed)
        {
            if let Some(config) = self.pending_bindings.take() {
                self.active_bindings = Some(config);
            }
        }
    }

    fn physical_release_confirmed(&self) -> bool {
        match self.physical_keys.as_ref() {
            None => true,
            Some(Some(keys)) => keys.is_empty(),
            Some(None) => false,
        }
    }

    fn start(&mut self, binding: InputBinding) -> Vec<InputAction> {
        let session_id = self.next_identity();
        if let Some(press) = self.press.as_mut() {
            press.session_id = Some(session_id);
            press.started_session = true;
        }
        self.session = Some(ActiveSession {
            id: session_id,
            binding: binding.clone(),
            phase: SessionPhase::Preparing,
            stop_requested: false,
        });
        vec![InputAction::Start {
            session_id,
            binding,
        }]
    }

    fn request_stop(&mut self) -> Vec<InputAction> {
        let Some(session) = self.session.as_mut() else {
            return Vec::new();
        };
        match session.phase {
            SessionPhase::Preparing => {
                session.stop_requested = true;
                Vec::new()
            }
            SessionPhase::Recording => {
                session.phase = SessionPhase::Stopping;
                vec![InputAction::Stop {
                    session_id: session.id,
                }]
            }
            SessionPhase::Stopping => Vec::new(),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::{InputBinding, InputMode, InputSession, SessionPhase, VoiceRole};

    #[test]
    fn edit_selection_session_preserves_mode_and_rejects_foreign_cancellation() {
        for mode in [InputMode::Auto, InputMode::Toggle, InputMode::Hold] {
            let mut session = InputSession::default();
            session.press(
                InputBinding {
                    role: VoiceRole::EditSelection,
                    binding_id: "Ctrl+Shift+E".into(),
                    mode,
                    revision: 1,
                },
                10,
            );
            if mode == InputMode::Auto {
                session.release("Ctrl+Shift+E", 100);
            }
            let identity = session.session_id().unwrap();
            assert!(session.owns_session_binding("Ctrl+Shift+E", VoiceRole::EditSelection));
            assert!(!session.owns_session_binding("Ctrl+Shift+E", VoiceRole::Dictation));
            assert!(!session.cancel(identity + 1));
            assert!(session.cancel(identity));
            assert!(session.ready(identity).is_empty());
        }
    }

    #[test]
    fn cancellation_retires_only_the_owned_preparing_session() {
        let mut session = InputSession::default();
        session.press(
            InputBinding {
                role: VoiceRole::Ask,
                binding_id: "Fn+Space".into(),
                mode: InputMode::Toggle,
                revision: 7,
            },
            10,
        );
        let identity = session.session_id().unwrap();
        assert_eq!(session.phase(), Some(SessionPhase::Preparing));
        assert!(!session.cancel(identity + 1));
        assert_eq!(session.phase(), Some(SessionPhase::Preparing));
        assert!(session.cancel(identity));
        assert_eq!(session.phase(), None);
        assert!(session.ready(identity).is_empty());
    }
}
