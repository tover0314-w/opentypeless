use std::sync::atomic::{AtomicU64, Ordering};

#[derive(Default)]
pub(crate) struct NativeDispatchGate(AtomicU64);

impl NativeDispatchGate {
    pub fn begin_install(&self) -> u64 {
        let mut current = self.0.load(Ordering::SeqCst);
        loop {
            let next = current.wrapping_add(2) | 1;
            match self
                .0
                .compare_exchange(current, next, Ordering::SeqCst, Ordering::SeqCst)
            {
                Ok(_) => return next,
                Err(observed) => current = observed,
            }
        }
    }

    pub fn finish_install(&self, installation: u64) -> bool {
        if installation & 1 == 0 {
            return false;
        }
        self.0
            .compare_exchange(
                installation,
                installation & !1,
                Ordering::SeqCst,
                Ordering::SeqCst,
            )
            .is_ok()
    }

    pub fn pause(&self) {
        self.begin_install();
    }

    pub fn is_paused(&self) -> bool {
        self.0.load(Ordering::SeqCst) & 1 != 0
    }
}
