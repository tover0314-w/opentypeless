use std::collections::HashMap;
use std::sync::Mutex;

#[derive(Default)]
pub struct SessionCommitAttempts {
    attempts: Mutex<HashMap<String, bool>>,
}

pub struct SessionCommitGuard<'a> {
    attempts: &'a SessionCommitAttempts,
    attempt: &'a str,
}

impl Drop for SessionCommitGuard<'_> {
    fn drop(&mut self) {
        self.attempts.finish(self.attempt);
    }
}

impl SessionCommitAttempts {
    pub fn begin(&self) -> Result<String, String> {
        let mut attempts = self
            .attempts
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if attempts.len() >= 32 {
            return Err("Too many pending session commits".into());
        }
        let attempt = uuid::Uuid::new_v4().to_string();
        attempts.insert(attempt.clone(), false);
        Ok(attempt)
    }

    pub fn cancel(&self, attempt: &str) -> bool {
        let mut attempts = self
            .attempts
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if attempts.get(attempt) != Some(&false) {
            return false;
        }
        attempts.remove(attempt);
        true
    }

    pub fn claim(&self, attempt: &str) -> bool {
        let mut attempts = self
            .attempts
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if attempts.get(attempt) != Some(&false) {
            return false;
        }
        attempts.insert(attempt.to_string(), true);
        true
    }

    pub fn finish(&self, attempt: &str) {
        self.attempts
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .remove(attempt);
    }

    pub fn enter_commit<'a>(&'a self, attempt: &'a str) -> Result<SessionCommitGuard<'a>, String> {
        if !self.claim(attempt) {
            return Err("Cloud session commit cancelled".into());
        }
        Ok(SessionCommitGuard {
            attempts: self,
            attempt,
        })
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cancellation_before_commit_rejects_the_attempt() {
        let attempts = SessionCommitAttempts::default();
        let attempt = attempts.begin().unwrap();
        assert!(attempts.cancel(&attempt));
        assert!(!attempts.claim(&attempt));
    }

    #[test]
    fn cancellation_after_commit_does_not_claim_to_revoke_the_write() {
        let attempts = SessionCommitAttempts::default();
        let attempt = attempts.begin().unwrap();
        assert!(attempts.claim(&attempt));
        assert!(!attempts.cancel(&attempt));
        attempts.finish(&attempt);
        assert!(!attempts.claim(&attempt));
    }

    #[test]
    fn cancelling_an_old_attempt_does_not_cancel_a_new_attempt() {
        let attempts = SessionCommitAttempts::default();
        let old = attempts.begin().unwrap();
        let current = attempts.begin().unwrap();
        assert!(attempts.cancel(&old));
        assert!(attempts.claim(&current));
        assert!(!attempts.claim(&current));
        attempts.finish(&current);
    }

    #[test]
    fn pending_attempts_are_bounded_and_cancellation_releases_capacity() {
        let attempts = SessionCommitAttempts::default();
        let mut pending = Vec::new();
        for _ in 0..32 {
            pending.push(attempts.begin().unwrap());
        }
        assert!(attempts.begin().is_err());
        assert!(attempts.cancel(&pending[0]));
        assert!(attempts.begin().is_ok());
    }
}
