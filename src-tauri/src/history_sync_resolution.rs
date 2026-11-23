use std::collections::HashMap;
use std::sync::Mutex;
use std::time::{Duration, Instant};

#[derive(Clone, Debug, PartialEq, Eq)]
pub(crate) struct ResolutionBinding {
    pub account_id: String,
    pub session_epoch: String,
    pub generation: String,
    pub record_id: String,
    pub mutation_id: String,
    pub client_revision: i64,
    pub remote_revision: i64,
    pub remote_deleted: bool,
}

struct PendingReceipt {
    binding: ResolutionBinding,
    prepared_at: Instant,
}

#[derive(Default)]
pub(crate) struct ResolutionReceipts {
    pending: Mutex<HashMap<String, PendingReceipt>>,
}

impl ResolutionReceipts {
    pub(crate) fn prepare(&self, binding: ResolutionBinding) -> anyhow::Result<String> {
        if binding.account_id.is_empty()
            || binding.account_id.trim() != binding.account_id
            || binding.account_id.len() > 200
            || binding.account_id.chars().any(char::is_control)
            || !(1..=9007199254740991).contains(&binding.client_revision)
            || !(0..=9007199254740991).contains(&binding.remote_revision)
            || [
                &binding.session_epoch,
                &binding.generation,
                &binding.record_id,
                &binding.mutation_id,
            ]
            .iter()
            .any(|value| {
                value.len() != 36
                    || uuid::Uuid::parse_str(value).ok().is_none_or(|identity| {
                        identity.get_version_num() != 4
                            || identity.get_variant() != uuid::Variant::RFC4122
                    })
            })
        {
            anyhow::bail!("Invalid history sync resolution binding");
        }
        let mut pending = self
            .pending
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync resolution unavailable"))?;
        pending.retain(|_, receipt| {
            receipt.prepared_at.elapsed() < Duration::from_secs(60)
                && !(receipt.binding.account_id == binding.account_id
                    && receipt.binding.record_id == binding.record_id)
        });
        if pending.len() >= 32 {
            anyhow::bail!("Too many pending history sync resolutions");
        }
        let receipt_id = uuid::Uuid::new_v4().to_string();
        pending.insert(
            receipt_id.clone(),
            PendingReceipt {
                binding,
                prepared_at: Instant::now(),
            },
        );
        Ok(receipt_id)
    }

    pub(crate) fn consume(
        &self,
        receipt_id: &str,
        expected: &ResolutionBinding,
    ) -> anyhow::Result<ResolutionBinding> {
        let mut pending = self
            .pending
            .lock()
            .map_err(|_| anyhow::anyhow!("History sync resolution unavailable"))?;
        let receipt = pending
            .get(receipt_id)
            .ok_or_else(|| anyhow::anyhow!("History sync resolution expired"))?;
        if receipt.prepared_at.elapsed() >= Duration::from_secs(60) {
            pending.remove(receipt_id);
            anyhow::bail!("History sync resolution expired");
        }
        if &receipt.binding != expected {
            anyhow::bail!("History sync resolution superseded");
        }
        pending
            .remove(receipt_id)
            .map(|receipt| receipt.binding)
            .ok_or_else(|| anyhow::anyhow!("History sync resolution expired"))
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn binding() -> ResolutionBinding {
        ResolutionBinding {
            account_id: "synthetic-account".into(),
            session_epoch: "11111111-1111-4111-8111-111111111111".into(),
            generation: "22222222-2222-4222-8222-222222222222".into(),
            record_id: "33333333-3333-4333-8333-333333333333".into(),
            mutation_id: "44444444-4444-4444-8444-444444444444".into(),
            client_revision: 2,
            remote_revision: 3,
            remote_deleted: false,
        }
    }

    #[test]
    fn resolution_receipt_is_bound_and_consumed_exactly_once() {
        let store = ResolutionReceipts::default();
        let receipt = store.prepare(binding()).unwrap();
        let mut other = binding();
        other.account_id = "other-account".into();
        assert!(store.consume(&receipt, &other).is_err());
        assert_eq!(store.consume(&receipt, &binding()).unwrap(), binding());
        assert!(store.consume(&receipt, &binding()).is_err());
    }

    #[test]
    fn replacement_preview_invalidates_the_previous_receipt_for_that_record() {
        let store = ResolutionReceipts::default();
        let original = store.prepare(binding()).unwrap();
        let mut updated = binding();
        updated.remote_revision = 4;
        let replacement = store.prepare(updated.clone()).unwrap();
        assert!(store.consume(&original, &binding()).is_err());
        assert_eq!(store.consume(&replacement, &updated).unwrap(), updated);
    }

    #[test]
    fn expired_receipts_never_authorize_a_change() {
        let store = ResolutionReceipts::default();
        let receipt = store.prepare(binding()).unwrap();
        {
            let mut pending = store.pending.lock().unwrap();
            pending.get_mut(&receipt).unwrap().prepared_at =
                std::time::Instant::now() - std::time::Duration::from_secs(61);
        }
        assert!(store.consume(&receipt, &binding()).is_err());
    }
}
