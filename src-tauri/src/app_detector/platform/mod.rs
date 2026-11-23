use std::sync::Arc;

use super::types::ContextSignals;
use super::types::TargetAppGuard;
use super::types::{BrowserAccessStatus, BrowserTarget};

#[cfg(target_os = "linux")]
mod linux;
#[cfg(target_os = "macos")]
mod macos;
#[cfg(target_os = "windows")]
mod windows;

pub(crate) trait ContextSignalSource: Send + Sync + 'static {
    fn collect(&self) -> Option<ContextSignals>;
    fn target_identity(&self) -> Option<TargetAppGuard> {
        self.collect().map(|signals| TargetAppGuard::from(&signals))
    }
}

pub(crate) struct PrivacyGuardedSource {
    source: Arc<dyn ContextSignalSource>,
    enabled: Box<dyn Fn() -> bool + Send + Sync>,
}

impl PrivacyGuardedSource {
    pub(crate) fn new(
        source: Arc<dyn ContextSignalSource>,
        enabled: impl Fn() -> bool + Send + Sync + 'static,
    ) -> Self {
        Self {
            source,
            enabled: Box::new(enabled),
        }
    }
}

impl ContextSignalSource for PrivacyGuardedSource {
    fn collect(&self) -> Option<ContextSignals> {
        if (self.enabled)() {
            let signals = self.source.collect();
            if (self.enabled)() {
                return signals;
            }
        }
        self.source.target_identity().map(|target| ContextSignals {
            process_id: target.process_id,
            native_identity: target.native_identity,
            ..Default::default()
        })
    }

    fn target_identity(&self) -> Option<TargetAppGuard> {
        self.source.target_identity()
    }
}

pub(crate) fn default_source() -> Arc<dyn ContextSignalSource> {
    #[cfg(target_os = "macos")]
    {
        Arc::new(macos::MacOsContextSource)
    }
    #[cfg(target_os = "windows")]
    {
        Arc::new(windows::WindowsContextSource)
    }
    #[cfg(target_os = "linux")]
    {
        Arc::new(linux::LinuxContextSource)
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    {
        Arc::new(UnsupportedContextSource)
    }
}

pub(crate) fn restore_target_application(target: &TargetAppGuard) -> bool {
    #[cfg(target_os = "macos")]
    {
        macos::restore_target_application(target)
    }
    #[cfg(target_os = "windows")]
    {
        windows::restore_target_application(target)
    }
    #[cfg(target_os = "linux")]
    {
        linux::restore_target_application(target)
    }
    #[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
    {
        let _ = target;
        false
    }
}

pub(crate) fn request_browser_access(target: BrowserTarget) -> BrowserAccessStatus {
    #[cfg(target_os = "macos")]
    {
        macos::request_browser_access(target)
    }
    #[cfg(not(target_os = "macos"))]
    {
        let _ = target;
        BrowserAccessStatus::NotApplicable
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
struct UnsupportedContextSource;

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
impl ContextSignalSource for UnsupportedContextSource {
    fn collect(&self) -> Option<ContextSignals> {
        None
    }
}

#[cfg(test)]
mod privacy_tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, AtomicUsize, Ordering};

    #[test]
    fn production_detection_uses_committed_policy_and_identity_only_adapters() {
        let cache = include_str!("../cache.rs");
        let startup = include_str!("../../lib.rs");
        assert!(cache.contains("PrivacyGuardedSource::new("));
        assert!(startup.contains("manager.cached_input_config().ok()"));
        assert!(startup.contains("config.context_adaptation_enabled"));
        for source in [
            include_str!("macos.rs"),
            include_str!("windows.rs"),
            include_str!("linux.rs"),
        ] {
            let identity = source
                .split("fn target_identity(&self)")
                .nth(1)
                .unwrap()
                .split("fn collect(&self)")
                .next()
                .unwrap();
            assert!(!identity.contains("self.collect("));
            assert!(!identity.contains("GetWindowTextW"));
            assert!(!identity.contains("getwindowname"));
            assert!(!identity.contains("read_browser_host"));
            assert!(!identity.contains("FRONT_APP_SCRIPT"));
        }
    }

    struct SyntheticSource {
        calls: AtomicUsize,
        enabled: Arc<AtomicBool>,
        disable_during_collection: bool,
    }

    impl ContextSignalSource for SyntheticSource {
        fn collect(&self) -> Option<ContextSignals> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            if self.disable_during_collection {
                self.enabled.store(false, Ordering::SeqCst);
            }
            Some(ContextSignals {
                process_id: Some(42),
                native_identity: Some("synthetic.app".into()),
                window_title: Some("Private synthetic title".into()),
                browser_host: Some("example.invalid".into()),
                ..Default::default()
            })
        }

        fn target_identity(&self) -> Option<TargetAppGuard> {
            Some(TargetAppGuard {
                process_id: Some(42),
                native_identity: Some("synthetic.app".into()),
            })
        }
    }

    #[test]
    fn disabled_collection_skips_private_signals_but_preserves_output_guard() {
        let enabled = Arc::new(AtomicBool::new(false));
        let source = Arc::new(SyntheticSource {
            calls: AtomicUsize::new(0),
            enabled: enabled.clone(),
            disable_during_collection: false,
        });
        let policy = enabled.clone();
        let guarded =
            PrivacyGuardedSource::new(source.clone(), move || policy.load(Ordering::SeqCst));
        let signals = guarded.collect().unwrap();
        assert_eq!(source.calls.load(Ordering::SeqCst), 0);
        assert_eq!(signals.process_id, Some(42));
        assert_eq!(signals.window_title, None);
        assert_eq!(signals.browser_host, None);
        enabled.store(true, Ordering::SeqCst);
        assert!(guarded.collect().unwrap().browser_host.is_some());
        assert_eq!(source.calls.load(Ordering::SeqCst), 1);
    }

    #[test]
    fn disabling_during_collection_discards_private_signals() {
        let enabled = Arc::new(AtomicBool::new(true));
        let source = Arc::new(SyntheticSource {
            calls: AtomicUsize::new(0),
            enabled: enabled.clone(),
            disable_during_collection: true,
        });
        let guarded = PrivacyGuardedSource::new(source, move || enabled.load(Ordering::SeqCst));
        let signals = guarded.collect().unwrap();
        assert_eq!(signals.process_id, Some(42));
        assert_eq!(signals.window_title, None);
        assert_eq!(signals.browser_host, None);
    }
}
