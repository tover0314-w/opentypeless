#[derive(Debug, Clone, Copy, PartialEq, Eq, Default)]
pub enum LeasePhase {
    #[default]
    Ready,
    Capturing,
    AwaitingRelease,
    Registering,
    Failed,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CaptureLease {
    pub window_label: String,
    pub capture_id: String,
    pub generation: u64,
}

#[derive(Debug, Default)]
pub struct CaptureLeaseState {
    generation: u64,
    lease: Option<CaptureLease>,
    phase: LeasePhase,
    last_error: Option<String>,
    released_lease: Option<CaptureLease>,
    recovery_running: bool,
}

impl CaptureLeaseState {
    pub fn registration_allowed(&self) -> bool {
        self.actions_allowed() || self.phase == LeasePhase::Registering
    }

    pub fn owned_by(&self, window_label: &str, capture_id: &str) -> bool {
        self.lease.as_ref().is_some_and(|lease| {
            lease.window_label == window_label && lease.capture_id == capture_id
        })
    }

    pub fn actions_allowed(&self) -> bool {
        self.phase == LeasePhase::Ready && self.lease.is_none()
    }

    pub fn failed_released_capture(&self) -> Option<CaptureLease> {
        if self.lease.is_none() && self.phase == LeasePhase::Failed && !self.recovery_running {
            self.released_lease.clone()
        } else {
            None
        }
    }

    #[cfg(test)]
    pub fn phase(&self) -> LeasePhase {
        self.phase
    }

    pub fn acquire(
        &mut self,
        window_label: &str,
        capture_id: &str,
    ) -> Result<CaptureLease, String> {
        if window_label != "main" || capture_id.trim().is_empty() {
            return Err("Invalid shortcut capture owner".to_string());
        }
        if let Some(lease) = &self.lease {
            if lease.window_label == window_label && lease.capture_id == capture_id {
                return Ok(lease.clone());
            }
        }
        self.generation = self
            .generation
            .checked_add(1)
            .ok_or("Shortcut capture generation exhausted")?;
        let lease = CaptureLease {
            window_label: window_label.to_string(),
            capture_id: capture_id.to_string(),
            generation: self.generation,
        };
        self.lease = Some(lease.clone());
        self.released_lease = None;
        self.recovery_running = false;
        self.phase = LeasePhase::Capturing;
        self.last_error = None;
        Ok(lease)
    }

    pub fn is_current(&self, lease: &CaptureLease) -> bool {
        self.lease.as_ref() == Some(lease)
    }

    pub fn release(&mut self, lease: &CaptureLease) -> bool {
        if !self.is_current(lease) {
            return false;
        }
        self.lease = None;
        self.released_lease = Some(lease.clone());
        self.phase = LeasePhase::AwaitingRelease;
        true
    }

    pub fn release_window(&mut self, window_label: &str, generation: u64) -> bool {
        let Some(lease) = self.lease.clone() else {
            return false;
        };
        if lease.window_label != window_label || lease.generation != generation {
            return false;
        }
        self.release(&lease)
    }

    pub fn release_current_window(&mut self, window_label: &str) -> Option<CaptureLease> {
        let lease = self.lease.clone()?;
        self.release_window(window_label, lease.generation)
            .then_some(lease)
    }

    pub fn confirm_released(&mut self, generation: u64, pressed: Option<bool>) -> bool {
        if self.phase != LeasePhase::AwaitingRelease
            || self.generation != generation
            || pressed != Some(false)
        {
            return false;
        }
        self.phase = LeasePhase::Registering;
        true
    }

    pub fn finish_registration(&mut self, generation: u64, error: Option<String>) -> bool {
        if self.phase != LeasePhase::Registering || self.generation != generation {
            return false;
        }
        self.phase = if error.is_some() {
            LeasePhase::Failed
        } else {
            LeasePhase::Ready
        };
        self.last_error = error;
        self.recovery_running = false;
        true
    }

    #[cfg(test)]
    pub fn last_error(&self) -> Option<&str> {
        self.last_error.as_deref()
    }

    pub fn begin_resume(&mut self, capture_id: Option<&str>) -> Result<Option<u64>, String> {
        if self.recovery_running {
            return Err("Shortcut recovery is already running".to_string());
        }
        if let Some(lease) = self.lease.clone() {
            if capture_id != Some(lease.capture_id.as_str()) {
                return Err("hotkey registration superseded".to_string());
            }
            self.release(&lease);
            self.recovery_running = true;
            return Ok(Some(lease.generation));
        }
        if let Some(lease) = &self.released_lease {
            if capture_id.is_none() && self.phase == LeasePhase::Ready {
                return Ok(None);
            }
            if capture_id != Some(lease.capture_id.as_str()) {
                return Err("hotkey registration superseded".to_string());
            }
            match self.phase {
                LeasePhase::AwaitingRelease => {
                    self.recovery_running = true;
                    return Ok(Some(lease.generation));
                }
                LeasePhase::Failed => {
                    self.phase = LeasePhase::AwaitingRelease;
                    self.recovery_running = true;
                    return Ok(Some(lease.generation));
                }
                LeasePhase::Registering => {
                    return Err("Shortcut recovery is already running".to_string())
                }
                LeasePhase::Ready => return Ok(None),
                LeasePhase::Capturing => unreachable!(),
            }
        }
        if capture_id.is_some() {
            return Err("hotkey registration superseded".to_string());
        }
        Ok(None)
    }

    pub fn recovery_failed(&mut self, generation: u64, error: String) -> bool {
        if self.generation != generation
            || !matches!(
                self.phase,
                LeasePhase::AwaitingRelease | LeasePhase::Registering
            )
        {
            return false;
        }
        self.phase = LeasePhase::Failed;
        self.last_error = Some(error);
        self.recovery_running = false;
        true
    }

    pub fn recovery_is_current(&self, generation: u64) -> bool {
        self.generation == generation
            && self.recovery_running
            && self.lease.is_none()
            && matches!(
                self.phase,
                LeasePhase::AwaitingRelease | LeasePhase::Registering
            )
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn window_cleanup_preserves_failure_until_owner_retries() {
        let mut state = CaptureLeaseState::default();
        let lease = state.acquire("main", "first").unwrap();
        assert!(state.release_window("main", lease.generation));
        assert_eq!(state.phase(), LeasePhase::AwaitingRelease);
        assert!(state.recovery_failed(lease.generation, "unavailable".to_string()));
        assert_eq!(state.last_error(), Some("unavailable"));
        assert!(state.begin_resume(None).is_err());
        assert_eq!(
            state.begin_resume(Some("first")).unwrap(),
            Some(lease.generation),
        );
    }
}
