use crate::input_session::{InputAction, InputBinding, InputSession, VoiceRole};
use std::collections::{BTreeMap, BTreeSet};
use std::ops::{Deref, DerefMut};

#[derive(Clone)]
struct Installation<Fingerprint> {
    generation: u64,
    fingerprint: Fingerprint,
    bindings: Vec<InputBinding>,
    installed: Vec<String>,
    published: bool,
    valid: bool,
}

pub struct InputConfiguration<Fingerprint, Config> {
    session: InputSession,
    installation: Option<Installation<Fingerprint>>,
    committed_installation: Option<Installation<Fingerprint>>,
    saved_revision: u64,
    saved_fingerprint: Option<Fingerprint>,
    next_revision: u64,
    snapshots: BTreeMap<u64, Config>,
    stopped: bool,
    next_installation_reservation: u64,
    installation_reservation: Option<u64>,
    installation_blocked_bindings: BTreeSet<String>,
}

impl<Fingerprint, Config> Default for InputConfiguration<Fingerprint, Config> {
    fn default() -> Self {
        Self {
            session: InputSession::default(),
            installation: None,
            committed_installation: None,
            saved_revision: 0,
            saved_fingerprint: None,
            next_revision: 0,
            snapshots: BTreeMap::new(),
            stopped: false,
            next_installation_reservation: 0,
            installation_reservation: None,
            installation_blocked_bindings: BTreeSet::new(),
        }
    }
}

impl<Fingerprint: PartialEq + Clone, Config> InputConfiguration<Fingerprint, Config> {
    pub fn reserve_installation(&mut self) -> Option<u64> {
        if self.stopped
            || self.installation_reservation.is_some()
            || self.session.session_id().is_some()
            || self.session.press_id().is_some()
        {
            return None;
        }
        self.next_installation_reservation = self.next_installation_reservation.checked_add(1)?;
        self.installation_reservation = Some(self.next_installation_reservation);
        self.installation_reservation
    }

    pub fn release_installation(&mut self, reservation: u64) -> bool {
        if self.installation_reservation != Some(reservation) {
            return false;
        }
        self.installation_reservation = None;
        true
    }

    pub fn press_committed(
        &mut self,
        binding_id: &str,
        role: VoiceRole,
        now_ms: u64,
    ) -> Vec<InputAction> {
        if self.block_installation_press(binding_id) {
            return Vec::new();
        }
        self.session.press_committed(binding_id, role, now_ms)
    }

    pub fn press_combo_committed(
        &mut self,
        parent_binding_id: &str,
        binding_id: &str,
        role: VoiceRole,
        now_ms: u64,
    ) -> Vec<InputAction> {
        if self.block_installation_press(binding_id) {
            return Vec::new();
        }
        self.session
            .press_combo_committed(parent_binding_id, binding_id, role, now_ms)
    }

    pub fn block_installation_press(&mut self, binding_id: &str) -> bool {
        if self.installation_reservation.is_some() {
            self.installation_blocked_bindings
                .insert(binding_id.to_string());
        }
        self.installation_reservation.is_some() || !self.installation_blocked_bindings.is_empty()
    }

    pub fn publish_saved_revision(
        &mut self,
        generation: u64,
        saved_revision: u64,
        fingerprint: &Fingerprint,
        config: Config,
    ) -> bool {
        if saved_revision < self.saved_revision
            || (saved_revision == self.saved_revision
                && self
                    .saved_fingerprint
                    .as_ref()
                    .is_some_and(|saved| saved != fingerprint))
        {
            return false;
        }
        if !self.publish_saved(generation, fingerprint, config) {
            return false;
        }
        self.saved_revision = saved_revision;
        self.saved_fingerprint = Some(fingerprint.clone());
        true
    }

    pub fn refresh_saved_snapshot(
        &mut self,
        saved_revision: u64,
        fingerprint: &Fingerprint,
        config: Config,
    ) -> bool {
        if self.stopped || saved_revision <= self.saved_revision {
            return false;
        }
        self.saved_revision = saved_revision;
        self.saved_fingerprint = Some(fingerprint.clone());
        let Some(installation) = self
            .committed_installation
            .as_ref()
            .filter(|installation| installation.valid && &installation.fingerprint == fingerprint)
            .cloned()
        else {
            return false;
        };
        if !self.publish_snapshot(&installation, config) {
            return false;
        }
        true
    }

    pub fn pending_publication(&self, fingerprint: &Fingerprint) -> Option<u64> {
        if self.stopped {
            return None;
        }
        self.installation
            .as_ref()
            .filter(|installation| {
                installation.valid
                    && !installation.published
                    && &installation.fingerprint == fingerprint
            })
            .map(|installation| installation.generation)
    }

    pub fn needs_installation(&self, fingerprint: &Fingerprint) -> bool {
        !self.stopped
            && self.installation_reservation.is_none()
            && self.session.permits_reconfiguration()
            && self.installation.as_ref().is_none_or(|installation| {
                !installation.valid || &installation.fingerprint != fingerprint
            })
    }

    pub fn saved_revision_is_active(&self, saved_revision: u64, fingerprint: &Fingerprint) -> bool {
        !self.stopped
            && self.saved_revision == saved_revision
            && self.saved_fingerprint.as_ref() == Some(fingerprint)
            && self.session.active_revision() == Some(self.next_revision)
            && self
                .committed_installation
                .as_ref()
                .is_some_and(|installation| {
                    installation.valid && &installation.fingerprint == fingerprint
                })
    }

    pub fn stage_installation(
        &mut self,
        generation: u64,
        fingerprint: Fingerprint,
        bindings: Vec<InputBinding>,
        installed: Vec<String>,
    ) -> bool {
        if self.stopped
            || installed.iter().any(|binding_id| {
                !bindings
                    .iter()
                    .any(|binding| &binding.binding_id == binding_id)
            })
            || bindings
                .iter()
                .any(|binding| binding.binding_id.trim().is_empty())
            || bindings.iter().enumerate().any(|(index, binding)| {
                bindings[..index]
                    .iter()
                    .any(|previous| previous.binding_id == binding.binding_id)
            })
            || self
                .installation
                .as_ref()
                .is_some_and(|previous| previous.generation > generation)
        {
            return false;
        }
        self.installation = Some(Installation {
            generation,
            fingerprint,
            bindings,
            installed,
            published: false,
            valid: true,
        });
        true
    }

    pub fn publish_saved(
        &mut self,
        generation: u64,
        fingerprint: &Fingerprint,
        config: Config,
    ) -> bool {
        if self.stopped {
            return false;
        }
        let Some(installation) = self
            .installation
            .as_ref()
            .filter(|installation| {
                installation.valid
                    && installation.generation == generation
                    && &installation.fingerprint == fingerprint
            })
            .cloned()
        else {
            return false;
        };
        if !self.publish_snapshot(&installation, config) {
            return false;
        }
        self.committed_installation = Some(installation);
        if let Some(installation) = self.installation.as_mut() {
            installation.published = true;
        }
        true
    }

    fn publish_snapshot(
        &mut self,
        installation: &Installation<Fingerprint>,
        config: Config,
    ) -> bool {
        let revision = self
            .next_revision
            .checked_add(1)
            .expect("Input revision exhausted");
        let bindings = installation
            .bindings
            .iter()
            .cloned()
            .map(|mut binding| {
                binding.revision = revision;
                binding
            })
            .collect();
        if !self.session.commit_bindings(revision, bindings) {
            return false;
        }
        self.next_revision = revision;
        self.snapshots.insert(revision, config);
        self.session
            .confirm_binding_installation(revision, installation.installed.clone());
        self.prune_snapshots();
        true
    }

    pub fn finish(&mut self, session_id: u64) -> bool {
        let finished = self.session.finish(session_id);
        self.prune_snapshots();
        finished
    }

    pub fn cancel(&mut self, session_id: u64) -> bool {
        self.finish(session_id)
    }

    pub fn release(&mut self, binding_id: &str, now_ms: u64) -> Vec<InputAction> {
        if self.installation_blocked_bindings.remove(binding_id) {
            return Vec::new();
        }
        let actions = self.session.release(binding_id, now_ms);
        self.prune_snapshots();
        actions
    }

    pub fn owns_installation_blocked_release(&self, binding_id: &str) -> bool {
        self.installation_blocked_bindings.contains(binding_id)
    }

    pub fn requires_release_observation(&self) -> bool {
        !self.installation_blocked_bindings.is_empty()
            || self.session.requires_release_observation()
    }

    pub fn observe_physical_keys(&mut self, keys: Option<Vec<u16>>) -> Vec<InputAction> {
        if keys.as_ref().is_some_and(Vec::is_empty) {
            self.installation_blocked_bindings.clear();
        }
        let actions = self.session.observe_physical_keys(keys);
        self.prune_snapshots();
        actions
    }

    pub fn resume(&mut self) -> bool {
        let resumed = self.session.resume();
        self.prune_snapshots();
        resumed
    }

    fn prune_snapshots(&mut self) {
        let active = self.session.active_revision();
        let pending = self.session.pending_revision();
        self.snapshots
            .retain(|revision, _| Some(*revision) == active || Some(*revision) == pending);
    }

    pub fn snapshot(&self, revision: u64) -> Option<&Config> {
        self.snapshots.get(&revision)
    }

    pub fn monitor_lost(&mut self, revision: u64) -> bool {
        if !self.session.monitor_lost(revision) {
            return false;
        }
        if let Some(pending) = self.session.pending_revision() {
            self.session.installation_failed(pending);
        }
        if let Some(installation) = self.installation.as_mut() {
            installation.valid = false;
        }
        if let Some(installation) = self.committed_installation.as_mut() {
            installation.valid = false;
        }
        true
    }

    pub fn owned_snapshot(&self, session_id: u64, revision: u64) -> Option<&Config> {
        if self.stopped || self.session.session_revision(session_id) != Some(revision) {
            return None;
        }
        self.snapshot(revision)
    }

    pub fn shutdown(&mut self) {
        self.stopped = true;
        self.installation_reservation = None;
        self.installation_blocked_bindings.clear();
        self.session.shutdown();
        self.installation = None;
        self.committed_installation = None;
        self.snapshots.clear();
    }
}

impl<Fingerprint, Config> Deref for InputConfiguration<Fingerprint, Config> {
    type Target = InputSession;

    fn deref(&self) -> &Self::Target {
        &self.session
    }
}

impl<Fingerprint, Config> DerefMut for InputConfiguration<Fingerprint, Config> {
    fn deref_mut(&mut self) -> &mut Self::Target {
        &mut self.session
    }
}
