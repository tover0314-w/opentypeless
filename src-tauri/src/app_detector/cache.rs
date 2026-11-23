use std::sync::atomic::{AtomicBool, AtomicU64, Ordering};
use std::sync::mpsc::{self, Receiver, SyncSender, TrySendError};
use std::sync::{Arc, Mutex, RwLock};
use std::thread::JoinHandle;
use std::time::{Duration, Instant};

use super::platform::{default_source, ContextSignalSource};
#[cfg(test)]
use super::registry::AppRegistry;
use super::types::{
    BrowserAccessStatus, BrowserTarget, ContextProfile, ContextSnapshot, ContextSource,
    RecordingContext, TargetAppGuard,
};
use super::user_mappings::{
    candidate_from_signals, MappingCandidate, MappingCandidateView, UserAppMappingStore,
};

const DEFAULT_STALE_AFTER: Duration = Duration::from_secs(2);
const DEFAULT_REFRESH_INTERVAL: Duration = Duration::from_secs(1);

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub enum TargetObservation {
    Matched,
    Changed,
    Unavailable,
}

#[derive(Clone, Copy, Debug)]
enum RefreshSignal {
    Normal,
    FocusChanged,
    Shutdown,
}

#[derive(Clone, Debug)]
struct CachedContext {
    snapshot: ContextSnapshot,
    target_guard: TargetAppGuard,
    mapped_scene_id: Option<String>,
    candidate_template: Option<MappingCandidate>,
    browser_access_status: BrowserAccessStatus,
    browser_target: Option<BrowserTarget>,
}

struct DetectorRuntime {
    shutdown: Arc<AtomicBool>,
    refresh_tx: SyncSender<RefreshSignal>,
    worker: Mutex<Option<JoinHandle<()>>>,
    latest_candidate: Arc<Mutex<Option<MappingCandidate>>>,
}

impl DetectorRuntime {
    fn stop(&self) {
        if self.shutdown.swap(true, Ordering::SeqCst) {
            return;
        }
        *self
            .latest_candidate
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = None;
        let _ = self.refresh_tx.try_send(RefreshSignal::Shutdown);
        if let Some(worker) = self
            .worker
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .take()
        {
            let _ = worker.join();
        }
    }
}

impl Drop for DetectorRuntime {
    fn drop(&mut self) {
        self.stop();
    }
}

#[derive(Clone)]
pub struct ContextDetectorHandle {
    cached: Arc<RwLock<CachedContext>>,
    refresh_tx: SyncSender<RefreshSignal>,
    _runtime: Arc<DetectorRuntime>,
    source: Arc<dyn ContextSignalSource>,
    stale_after: Duration,
    latest_candidate: Arc<Mutex<Option<MappingCandidate>>>,
    candidate_generation: Arc<AtomicU64>,
}

impl ContextDetectorHandle {
    pub fn start_default(
        mapping_store: UserAppMappingStore,
        enabled: impl Fn() -> bool + Send + Sync + 'static,
    ) -> Self {
        Self::start_with_mapping_store(
            Arc::new(super::platform::PrivacyGuardedSource::new(
                default_source(),
                enabled,
            )),
            mapping_store,
            DEFAULT_REFRESH_INTERVAL,
            DEFAULT_STALE_AFTER,
        )
    }

    #[cfg(test)]
    pub(crate) fn start_with_source(
        source: Arc<dyn ContextSignalSource>,
        registry: AppRegistry,
        refresh_interval: Duration,
        stale_after: Duration,
    ) -> Self {
        Self::start_with_mapping_store(
            source,
            UserAppMappingStore::memory(registry),
            refresh_interval,
            stale_after,
        )
    }

    fn start_with_mapping_store(
        source: Arc<dyn ContextSignalSource>,
        mapping_store: UserAppMappingStore,
        refresh_interval: Duration,
        stale_after: Duration,
    ) -> Self {
        let cached = Arc::new(RwLock::new(CachedContext {
            snapshot: ContextSnapshot {
                profile: ContextProfile::general_native(),
                captured_at: Instant::now(),
            },
            target_guard: TargetAppGuard::default(),
            mapped_scene_id: None,
            candidate_template: None,
            browser_access_status: BrowserAccessStatus::NotApplicable,
            browser_target: None,
        }));
        let (refresh_tx, refresh_rx) = mpsc::sync_channel(1);
        let shutdown = Arc::new(AtomicBool::new(false));

        let worker_cached = cached.clone();
        let worker_shutdown = shutdown.clone();
        let worker_source = source.clone();
        let worker_mapping_store = mapping_store.clone();
        let latest_candidate = Arc::new(Mutex::new(None));
        let worker = std::thread::Builder::new()
            .name("opentypeless-context-detector".to_string())
            .spawn(move || {
                run_detector(
                    worker_source,
                    worker_mapping_store,
                    worker_cached,
                    refresh_rx,
                    worker_shutdown,
                    refresh_interval,
                );
            })
            .expect("failed to start context detector thread");

        let runtime = Arc::new(DetectorRuntime {
            shutdown,
            refresh_tx: refresh_tx.clone(),
            worker: Mutex::new(Some(worker)),
            latest_candidate: latest_candidate.clone(),
        });

        Self {
            cached,
            refresh_tx,
            _runtime: runtime,
            source,
            stale_after,
            latest_candidate,
            candidate_generation: Arc::new(AtomicU64::new(0)),
        }
    }

    pub fn snapshot_for_recording(&self) -> RecordingContext {
        self.snapshot_for_recording_enabled(true)
    }

    pub fn snapshot_for_recording_enabled(&self, enabled: bool) -> RecordingContext {
        let cached = self
            .cached
            .read()
            .unwrap_or_else(|error| error.into_inner());
        let stale = cached.snapshot.captured_at.elapsed() > self.stale_after;
        let profile = if enabled && !stale {
            cached.snapshot.profile.clone()
        } else {
            fallback_for_profile(&cached.snapshot.profile)
        };
        let target_guard = cached.target_guard.clone();
        let mapped_scene_id = if enabled && !stale {
            cached.mapped_scene_id.clone()
        } else {
            None
        };
        let candidate_template = if !enabled || stale {
            None
        } else {
            cached.candidate_template.clone()
        };
        let browser_access_status = if enabled && !stale {
            cached.browser_access_status
        } else {
            BrowserAccessStatus::NotApplicable
        };
        let browser_target = if enabled && !stale {
            cached.browser_target
        } else {
            None
        };
        drop(cached);

        let generation = self.candidate_generation.fetch_add(1, Ordering::SeqCst) + 1;
        let next_candidate = candidate_template.map(|mut candidate| {
            candidate.generation = generation;
            candidate
        });
        *self
            .latest_candidate
            .lock()
            .unwrap_or_else(|error| error.into_inner()) = next_candidate;

        if stale {
            self.request_refresh();
        }
        RecordingContext {
            profile,
            target_guard,
            mapped_scene_id,
            browser_access_status,
            browser_target,
        }
    }

    pub fn latest_mapping_candidate(&self) -> Option<MappingCandidateView> {
        self.latest_candidate
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .map(MappingCandidate::view)
    }

    pub(crate) fn mapping_candidate_for_generation(
        &self,
        generation: u64,
    ) -> Option<MappingCandidate> {
        self.latest_candidate
            .lock()
            .unwrap_or_else(|error| error.into_inner())
            .as_ref()
            .filter(|candidate| candidate.generation == generation)
            .cloned()
    }

    pub(crate) fn clear_mapping_candidate(&self, generation: u64) {
        let mut candidate = self
            .latest_candidate
            .lock()
            .unwrap_or_else(|error| error.into_inner());
        if candidate
            .as_ref()
            .is_some_and(|candidate| candidate.generation == generation)
        {
            *candidate = None;
        }
    }

    pub fn latest_profile(&self) -> ContextProfile {
        self.cached
            .read()
            .unwrap_or_else(|error| error.into_inner())
            .snapshot
            .profile
            .clone()
    }

    pub fn target_still_matches(&self, expected: &TargetAppGuard) -> bool {
        if expected.is_empty() {
            return true;
        }
        let cached = self
            .cached
            .read()
            .unwrap_or_else(|error| error.into_inner());
        cached.snapshot.captured_at.elapsed() <= self.stale_after
            && expected.matches(&cached.target_guard)
    }

    pub fn target_still_matches_now(&self, expected: &TargetAppGuard) -> bool {
        self.observe_target_now(expected) == TargetObservation::Matched
    }

    pub fn observe_target_now(&self, expected: &TargetAppGuard) -> TargetObservation {
        if expected.is_empty() {
            return TargetObservation::Matched;
        }
        let Some(current) = self.source.target_identity() else {
            return TargetObservation::Unavailable;
        };
        if expected.has_known_mismatch(&current) {
            return TargetObservation::Changed;
        }
        if expected.process_id.is_some() && current.process_id.is_none()
            || expected.native_identity.is_some() && current.native_identity.is_none()
        {
            return TargetObservation::Unavailable;
        }
        if expected.matches(&current) {
            TargetObservation::Matched
        } else {
            TargetObservation::Changed
        }
    }

    pub fn restore_target_application(&self, expected: &TargetAppGuard) -> bool {
        if expected.is_empty() {
            return false;
        }
        if self.target_still_matches_now(expected) {
            return true;
        }
        if !super::platform::restore_target_application(expected) {
            return false;
        }
        for _ in 0..5 {
            std::thread::sleep(Duration::from_millis(40));
            if self.target_still_matches_now(expected) {
                self.notify_focus_changed();
                return true;
            }
        }
        false
    }

    pub fn notify_focus_changed(&self) {
        let _ = self.refresh_tx.try_send(RefreshSignal::FocusChanged);
    }

    pub fn request_refresh(&self) {
        match self.refresh_tx.try_send(RefreshSignal::Normal) {
            Ok(()) | Err(TrySendError::Full(_)) | Err(TrySendError::Disconnected(_)) => {}
        }
    }

    #[cfg(test)]
    fn replace_cached_for_test(
        &self,
        profile: ContextProfile,
        captured_at: Instant,
        target_guard: TargetAppGuard,
    ) {
        *self
            .cached
            .write()
            .unwrap_or_else(|error| error.into_inner()) = CachedContext {
            snapshot: ContextSnapshot {
                profile,
                captured_at,
            },
            target_guard,
            mapped_scene_id: None,
            candidate_template: None,
            browser_access_status: BrowserAccessStatus::NotApplicable,
            browser_target: None,
        };
    }

    #[cfg(test)]
    fn shutdown_for_test(&self) {
        self._runtime.stop();
    }
}

fn fallback_for_profile(profile: &ContextProfile) -> ContextProfile {
    if profile.source == ContextSource::BrowserDomain || profile.id == "general.browser" {
        ContextProfile::general_browser()
    } else {
        ContextProfile::general_native()
    }
}

fn run_detector(
    source: Arc<dyn ContextSignalSource>,
    mapping_store: UserAppMappingStore,
    cached: Arc<RwLock<CachedContext>>,
    refresh_rx: Receiver<RefreshSignal>,
    shutdown: Arc<AtomicBool>,
    refresh_interval: Duration,
) {
    refresh_context(source.as_ref(), &mapping_store, &cached);
    let mut last_refresh = Instant::now();

    while !shutdown.load(Ordering::SeqCst) {
        let signal = match refresh_rx.recv_timeout(refresh_interval) {
            Ok(signal) => signal,
            Err(mpsc::RecvTimeoutError::Timeout) => RefreshSignal::Normal,
            Err(mpsc::RecvTimeoutError::Disconnected) => break,
        };
        match signal {
            RefreshSignal::Shutdown => break,
            RefreshSignal::FocusChanged => {
                refresh_context(source.as_ref(), &mapping_store, &cached);
                last_refresh = Instant::now();
            }
            RefreshSignal::Normal if last_refresh.elapsed() >= refresh_interval => {
                refresh_context(source.as_ref(), &mapping_store, &cached);
                last_refresh = Instant::now();
            }
            RefreshSignal::Normal => {}
        }
    }
}

fn refresh_context(
    source: &dyn ContextSignalSource,
    mapping_store: &UserAppMappingStore,
    cached: &RwLock<CachedContext>,
) {
    let (
        profile,
        target_guard,
        mapped_scene_id,
        candidate_template,
        browser_access_status,
        browser_target,
    ) = match source.collect() {
        Some(signals) => {
            let resolved = mapping_store.resolve(&signals);
            let candidate_template = candidate_from_signals(&signals, &resolved.profile, 0)
                .filter(|_| !mapping_store.has_match(&signals));
            let target_guard = TargetAppGuard::from(&signals);
            let browser_access_status = signals.browser_access_status;
            let browser_target = signals.browser_target;
            (
                resolved.profile,
                target_guard,
                resolved.mapped_scene_id,
                candidate_template,
                browser_access_status,
                browser_target,
            )
        }
        None => (
            ContextProfile::general_native(),
            TargetAppGuard::default(),
            None,
            None,
            BrowserAccessStatus::NotApplicable,
            None,
        ),
    };

    *cached.write().unwrap_or_else(|error| error.into_inner()) = CachedContext {
        snapshot: ContextSnapshot {
            profile,
            captured_at: Instant::now(),
        },
        target_guard,
        mapped_scene_id,
        candidate_template,
        browser_access_status,
        browser_target,
    };
}

#[cfg(test)]
mod tests {
    use std::sync::atomic::{AtomicUsize, Ordering};

    use super::*;
    use crate::app_detector::types::{ContextSignals, ContextSource};

    struct FakeSource {
        signals: Mutex<Option<ContextSignals>>,
        calls: AtomicUsize,
    }

    impl FakeSource {
        fn new(signals: Option<ContextSignals>) -> Self {
            Self {
                signals: Mutex::new(signals),
                calls: AtomicUsize::new(0),
            }
        }

        fn set(&self, signals: Option<ContextSignals>) {
            *self
                .signals
                .lock()
                .unwrap_or_else(|error| error.into_inner()) = signals;
        }
    }

    impl ContextSignalSource for FakeSource {
        fn collect(&self) -> Option<ContextSignals> {
            self.calls.fetch_add(1, Ordering::SeqCst);
            self.signals
                .lock()
                .unwrap_or_else(|error| error.into_inner())
                .clone()
        }
    }

    fn gmail_signals() -> ContextSignals {
        ContextSignals {
            process_id: Some(42),
            native_identity: Some("com.google.Chrome".to_string()),
            process_alias: Some("Google Chrome".to_string()),
            browser_host: Some("mail.google.com".to_string()),
            is_supported_browser: true,
            browser_access_status: BrowserAccessStatus::Available,
            ..ContextSignals::default()
        }
    }

    fn browser_without_url_signals() -> ContextSignals {
        ContextSignals {
            process_id: Some(42),
            native_identity: Some("com.google.Chrome".to_string()),
            process_alias: Some("Google Chrome".to_string()),
            is_supported_browser: true,
            browser_access_status: BrowserAccessStatus::NeedsPermission,
            browser_target: Some(BrowserTarget::Chrome),
            ..ContextSignals::default()
        }
    }

    fn wait_for_profile(handle: &ContextDetectorHandle, id: &str) {
        let deadline = Instant::now() + Duration::from_secs(1);
        while Instant::now() < deadline {
            if handle.latest_profile().id == id {
                return;
            }
            std::thread::sleep(Duration::from_millis(2));
        }
        panic!("profile {id} was not published");
    }

    fn detector(source: Arc<FakeSource>) -> ContextDetectorHandle {
        ContextDetectorHandle::start_with_source(
            source,
            AppRegistry::builtin().unwrap(),
            Duration::from_millis(50),
            Duration::from_millis(100),
        )
    }

    #[test]
    fn immediate_target_observation_distinguishes_unavailable_from_changed() {
        let source = Arc::new(FakeSource::new(Some(gmail_signals())));
        let handle = detector(source.clone());
        let expected = TargetAppGuard {
            process_id: Some(42),
            native_identity: Some("com.google.Chrome".into()),
        };
        assert_eq!(
            handle.observe_target_now(&expected),
            TargetObservation::Matched
        );
        source.set(None);
        assert_eq!(
            handle.observe_target_now(&expected),
            TargetObservation::Unavailable
        );
        source.set(Some(ContextSignals {
            process_id: Some(77),
            native_identity: Some("synthetic-other-app".into()),
            ..Default::default()
        }));
        assert_eq!(
            handle.observe_target_now(&expected),
            TargetObservation::Changed
        );
    }

    #[test]
    fn incomplete_target_identity_is_unavailable_not_changed() {
        let source = Arc::new(FakeSource::new(Some(ContextSignals {
            process_id: Some(42),
            ..Default::default()
        })));
        let handle = detector(source);
        let expected = TargetAppGuard {
            process_id: Some(42),
            native_identity: Some("synthetic-app".into()),
        };
        assert_eq!(
            handle.observe_target_now(&expected),
            TargetObservation::Unavailable
        );
    }

    #[test]
    fn known_process_change_is_not_hidden_by_missing_application_identity() {
        let source = Arc::new(FakeSource::new(Some(ContextSignals {
            process_id: Some(77),
            ..Default::default()
        })));
        let handle = detector(source);
        let expected = TargetAppGuard {
            process_id: Some(42),
            native_identity: Some("synthetic-app".into()),
        };
        assert_eq!(
            handle.observe_target_now(&expected),
            TargetObservation::Changed
        );
    }

    #[test]
    fn context_snapshot_cache_publishes_focus_refresh_without_blocking_readers() {
        let source = Arc::new(FakeSource::new(Some(gmail_signals())));
        let handle = detector(source.clone());
        wait_for_profile(&handle, "email.gmail");

        source.set(Some(ContextSignals {
            native_identity: Some("com.tinyspeck.slackmacgap".to_string()),
            process_alias: Some("Slack".to_string()),
            process_id: Some(77),
            ..ContextSignals::default()
        }));
        handle.notify_focus_changed();
        wait_for_profile(&handle, "chat.slack");
        let captured = handle.snapshot_for_recording();
        assert_eq!(captured.profile.id, "chat.slack");
        assert_eq!(captured.target_guard.process_id, Some(77));
    }

    #[test]
    fn context_snapshot_cache_stale_snapshot_falls_back_and_schedules_refresh() {
        let source = Arc::new(FakeSource::new(Some(gmail_signals())));
        let handle = detector(source);
        wait_for_profile(&handle, "email.gmail");
        let mut gmail = handle.latest_profile();
        gmail.source = ContextSource::BrowserDomain;
        handle.replace_cached_for_test(
            gmail,
            Instant::now() - Duration::from_secs(3),
            TargetAppGuard {
                process_id: Some(42),
                native_identity: Some("com.google.Chrome".to_string()),
            },
        );

        let captured = handle.snapshot_for_recording();
        assert_eq!(captured.profile.id, "general.browser");
        assert_eq!(captured.target_guard.process_id, Some(42));
    }

    #[test]
    fn context_snapshot_records_browser_access_failure_for_supported_browser_without_host() {
        let source = Arc::new(FakeSource::new(Some(browser_without_url_signals())));
        let handle = detector(source);
        wait_for_profile(&handle, "general.browser");

        let captured = handle.snapshot_for_recording();

        assert_eq!(captured.profile.id, "general.browser");
        assert_eq!(
            captured.browser_access_status,
            BrowserAccessStatus::NeedsPermission
        );
        assert_eq!(captured.browser_target, Some(BrowserTarget::Chrome));
        assert_eq!(
            captured.summary().browser_target,
            Some(BrowserTarget::Chrome)
        );
    }

    #[test]
    fn context_snapshot_cache_refresh_failure_is_safe_general() {
        let source = Arc::new(FakeSource::new(None));
        let handle = detector(source);
        wait_for_profile(&handle, "general.native");
        assert_eq!(handle.snapshot_for_recording().profile.id, "general.native");
    }

    #[test]
    fn context_snapshot_cache_handles_concurrent_readers() {
        let source = Arc::new(FakeSource::new(Some(gmail_signals())));
        let handle = detector(source);
        wait_for_profile(&handle, "email.gmail");
        let readers = (0..8)
            .map(|_| {
                let handle = handle.clone();
                std::thread::spawn(move || {
                    for _ in 0..1_000 {
                        assert_eq!(handle.snapshot_for_recording().profile.id, "email.gmail");
                    }
                })
            })
            .collect::<Vec<_>>();
        for reader in readers {
            reader.join().unwrap();
        }
    }

    #[test]
    fn context_snapshot_cache_shutdown_is_idempotent() {
        let source = Arc::new(FakeSource::new(Some(gmail_signals())));
        let handle = detector(source);
        handle.shutdown_for_test();
        handle.shutdown_for_test();
    }

    #[test]
    fn user_app_mapping_candidate_expires_on_next_recording_and_shutdown() {
        let source = Arc::new(FakeSource::new(Some(gmail_signals())));
        let handle = detector(source);
        wait_for_profile(&handle, "email.gmail");

        let first = handle.snapshot_for_recording();
        assert_eq!(first.profile.id, "email.gmail");
        let first_candidate = handle.latest_mapping_candidate().unwrap();
        assert_eq!(first_candidate.display_value, "mail.google.com");
        assert!(handle
            .mapping_candidate_for_generation(first_candidate.generation)
            .is_some());

        let _ = handle.snapshot_for_recording();
        let second_candidate = handle.latest_mapping_candidate().unwrap();
        assert!(second_candidate.generation > first_candidate.generation);
        assert!(handle
            .mapping_candidate_for_generation(first_candidate.generation)
            .is_none());

        handle.shutdown_for_test();
        assert!(handle.latest_mapping_candidate().is_none());
    }

    #[test]
    fn disabled_context_does_not_publish_mapping_candidate() {
        let source = Arc::new(FakeSource::new(Some(gmail_signals())));
        let handle = detector(source);
        wait_for_profile(&handle, "email.gmail");
        handle.snapshot_for_recording();
        assert!(handle.latest_mapping_candidate().is_some());
        let disabled = handle.snapshot_for_recording_enabled(false);
        assert!(handle.latest_mapping_candidate().is_none());
        assert!(disabled.mapped_scene_id.is_none());
        assert!(disabled.browser_target.is_none());
        assert_eq!(
            disabled.browser_access_status,
            BrowserAccessStatus::NotApplicable
        );
    }

    #[test]
    fn recording_start_context_budget_is_below_five_milliseconds_p95() {
        let source = Arc::new(FakeSource::new(Some(gmail_signals())));
        let handle = detector(source);
        wait_for_profile(&handle, "email.gmail");
        let mut samples = Vec::with_capacity(10_000);
        for _ in 0..10_000 {
            let started = Instant::now();
            let captured = handle.snapshot_for_recording();
            samples.push(started.elapsed());
            assert_eq!(captured.profile.id, "email.gmail");
        }
        samples.sort_unstable();
        let p95 = samples[9_499];
        println!("recording_start_context_budget p95={p95:?}");
        assert!(p95 < Duration::from_millis(5));
    }
}
