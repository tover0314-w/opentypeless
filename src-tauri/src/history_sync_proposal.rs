use std::sync::Mutex;

#[derive(Default)]
pub struct ProposalStore {
    state: Mutex<ProposalState>,
}

#[derive(Default)]
struct ProposalState {
    pending: Option<(String, crate::history_sync_status::DiscoveredStatus)>,
    discovery_attempt: Option<String>,
    confirming: Option<String>,
}

struct ConfirmationGuard<'store> {
    store: &'store ProposalStore,
    identity: String,
}

impl Drop for ConfirmationGuard<'_> {
    fn drop(&mut self) {
        if let Ok(mut state) = self.store.state.lock() {
            if state.confirming.as_deref() == Some(&self.identity) {
                state.confirming = None;
            }
        }
    }
}

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct PreparedProposal {
    pub request_id: String,
    pub description: crate::history_sync_status::ProposalDescription,
}

pub struct DiscoveryGuard<'store> {
    store: &'store ProposalStore,
    attempt_id: String,
}

impl DiscoveryGuard<'_> {
    pub fn attempt_id(&self) -> &str {
        &self.attempt_id
    }
}

impl Drop for DiscoveryGuard<'_> {
    fn drop(&mut self) {
        let _ = self.store.abandon_discovery(&self.attempt_id);
    }
}

impl ProposalStore {
    #[cfg(test)]
    pub(crate) fn pending_for_test(&self) -> bool {
        self.state.lock().unwrap().pending.is_some()
    }

    #[cfg(test)]
    pub(crate) fn hold_lock_for_test(&self, action: impl FnOnce()) {
        let _guard = self.state.lock().unwrap();
        action();
    }

    #[cfg(test)]
    pub(crate) fn retains_state_for_test(&self) -> bool {
        let state = self.state.lock().unwrap_or_else(|error| error.into_inner());
        state.pending.is_some() || state.discovery_attempt.is_some()
    }

    #[cfg(test)]
    pub(crate) fn poison_for_test(&self) {
        let _ = std::panic::catch_unwind(|| {
            let _guard = self.state.lock().unwrap();
            panic!("Synthetic proposal lock poisoning");
        });
    }

    pub fn clear(&self) -> anyhow::Result<()> {
        let (mut state, poisoned) = match self.state.lock() {
            Ok(state) => (state, false),
            Err(error) => (error.into_inner(), true),
        };
        state.pending = None;
        state.discovery_attempt = None;
        if poisoned {
            anyhow::bail!("History sync proposal unavailable");
        }
        Ok(())
    }

    pub fn confirm_owned(
        &self,
        request_id: &str,
        expected_epoch: &str,
        session: &crate::SessionTokenStore,
        consent: &crate::history_sync_consent::ConsentStore,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<crate::history_sync_consent::ConsentSnapshot> {
        let (discovery, _confirmation) = {
            let mut state = self
                .state
                .lock()
                .map_err(|_| anyhow::anyhow!("History sync proposal unavailable"))?;
            if state.confirming.is_some()
                || state
                    .pending
                    .as_ref()
                    .is_none_or(|(identity, _)| identity != request_id)
            {
                anyhow::bail!("History sync proposal unavailable");
            }
            let identity = uuid::Uuid::new_v4().to_string();
            state.confirming = Some(identity.clone());
            state.discovery_attempt = None;
            (
                state.pending.take().unwrap().1,
                ConfirmationGuard {
                    store: self,
                    identity,
                },
            )
        };
        let description = discovery.proposal_description(session)?;
        if description.session_epoch != expected_epoch
            || description.service_url != endpoint.as_str()
        {
            anyhow::bail!("History sync proposal ownership changed");
        }
        discovery.confirm_empty_consent(session, consent)
    }

    pub fn begin_guarded_discovery(&self) -> anyhow::Result<DiscoveryGuard<'_>> {
        Ok(DiscoveryGuard {
            store: self,
            attempt_id: self.begin_discovery()?,
        })
    }

    pub fn abandon_discovery(&self, attempt_id: &str) -> anyhow::Result<bool> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync proposal unavailable"))?;
        if state.discovery_attempt.as_deref() == Some(attempt_id) {
            state.discovery_attempt = None;
            Ok(true)
        } else {
            Ok(false)
        }
    }

    pub fn begin_discovery(&self) -> anyhow::Result<String> {
        let identity = uuid::Uuid::new_v4().to_string();
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync proposal unavailable"))?;
        if state.confirming.is_some() {
            anyhow::bail!("History sync confirmation pending");
        }
        state.discovery_attempt = Some(identity.clone());
        Ok(identity)
    }

    pub(crate) fn finish_discovery_with_consent(
        &self,
        attempt_id: &str,
        discovery: crate::history_sync_status::DiscoveredStatus,
        session: &crate::SessionTokenStore,
        consent: &crate::history_sync_consent::ConsentStore,
    ) -> anyhow::Result<PreparedProposal> {
        discovery.with_proposal_description(session, |discovery, description| {
            consent.with_empty(|| self.publish_discovery(attempt_id, discovery, description))
        })
    }

    #[cfg(test)]
    pub(crate) fn finish_discovery(
        &self,
        attempt_id: &str,
        discovery: crate::history_sync_status::DiscoveredStatus,
        session: &crate::SessionTokenStore,
    ) -> anyhow::Result<PreparedProposal> {
        discovery.with_proposal_description(session, |discovery, description| {
            self.publish_discovery(attempt_id, discovery, description)
        })
    }

    fn publish_discovery(
        &self,
        attempt_id: &str,
        discovery: crate::history_sync_status::DiscoveredStatus,
        description: crate::history_sync_status::ProposalDescription,
    ) -> anyhow::Result<PreparedProposal> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync proposal unavailable"))?;
        if state.confirming.is_some() || state.discovery_attempt.as_deref() != Some(attempt_id) {
            anyhow::bail!("History sync discovery superseded");
        }
        state.discovery_attempt = None;
        let request_id = uuid::Uuid::new_v4().to_string();
        state.pending = Some((request_id.clone(), discovery));
        Ok(PreparedProposal {
            request_id,
            description,
        })
    }

    #[cfg(test)]
    pub(crate) fn confirm_at_service(
        &self,
        request_id: &str,
        session: &crate::SessionTokenStore,
        consent: &crate::history_sync_consent::ConsentStore,
        endpoint: &reqwest::Url,
    ) -> anyhow::Result<crate::history_sync_consent::ConsentSnapshot> {
        let discovery = self.take(request_id)?;
        let description = discovery.proposal_description(session)?;
        if description.service_url != endpoint.as_str() {
            anyhow::bail!("History sync proposal service changed");
        }
        discovery.confirm_consent(session, consent)
    }

    #[cfg(test)]
    pub(crate) fn prepare(
        &self,
        discovery: crate::history_sync_status::DiscoveredStatus,
        session: &crate::SessionTokenStore,
    ) -> anyhow::Result<PreparedProposal> {
        discovery.with_proposal_description(session, |discovery, description| {
            let request_id = self.issue(discovery)?;
            Ok(PreparedProposal {
                request_id,
                description,
            })
        })
    }

    pub fn cancel(&self, request_id: &str) -> anyhow::Result<bool> {
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync proposal unavailable"))?;
        if state
            .pending
            .as_ref()
            .is_some_and(|(identity, _)| identity == request_id)
        {
            state.pending.take();
            Ok(true)
        } else {
            Ok(false)
        }
    }

    #[cfg(test)]
    pub(crate) fn issue(
        &self,
        discovery: crate::history_sync_status::DiscoveredStatus,
    ) -> anyhow::Result<String> {
        let request_id = uuid::Uuid::new_v4().to_string();
        let mut state = self
            .state
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync proposal unavailable"))?;
        state.discovery_attempt = None;
        state.pending = Some((request_id.clone(), discovery));
        Ok(request_id)
    }

    #[cfg(test)]
    pub(crate) fn confirm(
        &self,
        request_id: &str,
        session: &crate::SessionTokenStore,
        consent: &crate::history_sync_consent::ConsentStore,
    ) -> anyhow::Result<crate::history_sync_consent::ConsentSnapshot> {
        let discovery = self.take(request_id)?;
        discovery.confirm_consent(session, consent)
    }

    #[cfg(test)]
    fn take(
        &self,
        request_id: &str,
    ) -> anyhow::Result<crate::history_sync_status::DiscoveredStatus> {
        Ok({
            let mut state = self
                .state
                .lock()
                .map_err(|_| anyhow::anyhow!("History sync proposal unavailable"))?;
            if state
                .pending
                .as_ref()
                .is_none_or(|(identity, _)| identity != request_id)
            {
                anyhow::bail!("History sync proposal unavailable");
            }
            state.pending.take().unwrap().1
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn dropped_guard_clears_only_its_own_discovery_attempt() {
        let store = ProposalStore::default();
        let previous = store.begin_guarded_discovery().unwrap();
        let previous_id = previous.attempt_id().to_string();
        let current = store.begin_guarded_discovery().unwrap();
        let current_id = current.attempt_id().to_string();
        drop(previous);
        assert!(!store.abandon_discovery(&previous_id).unwrap());
        assert_eq!(
            store.state.lock().unwrap().discovery_attempt.as_deref(),
            Some(current_id.as_str())
        );
        drop(current);
        assert!(store.state.lock().unwrap().discovery_attempt.is_none());
    }
}
