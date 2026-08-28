use serde::Serialize;
use std::time::{Duration, Instant};

pub const DEADLINE_SAFETY_BUFFER: Duration = Duration::from_millis(250);

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub struct CaptureReadyAt {
    pub monotonic: Instant,
    pub unix_millis: u64,
}

impl CaptureReadyAt {
    pub fn now() -> Self {
        let unix_millis = std::time::SystemTime::now()
            .duration_since(std::time::UNIX_EPOCH)
            .unwrap_or_default()
            .as_millis()
            .min(u128::from(u64::MAX)) as u64;
        Self {
            monotonic: Instant::now(),
            unix_millis,
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub enum RecordingKind {
    Dictation,
    Ask,
}

#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct RecordingDeadlineEvent {
    pub session_id: u64,
    pub recording_kind: RecordingKind,
    pub started_at_unix_ms: u64,
    pub deadline_at_unix_ms: u64,
    pub effective_max_seconds: u32,
}

#[derive(Debug, Clone, Copy)]
pub struct RecordingDeadline {
    pub event: RecordingDeadlineEvent,
    stop_at: tokio::time::Instant,
}

impl RecordingDeadline {
    pub fn new(
        session_id: u64,
        recording_kind: RecordingKind,
        capture_ready: CaptureReadyAt,
        effective_max_seconds: u32,
    ) -> Self {
        let stop_after = Duration::from_secs(u64::from(effective_max_seconds))
            .saturating_sub(DEADLINE_SAFETY_BUFFER);
        let deadline_at_unix_ms = capture_ready
            .unix_millis
            .saturating_add(stop_after.as_millis().min(u128::from(u64::MAX)) as u64);
        Self {
            event: RecordingDeadlineEvent {
                session_id,
                recording_kind,
                started_at_unix_ms: capture_ready.unix_millis,
                deadline_at_unix_ms,
                effective_max_seconds,
            },
            stop_at: tokio::time::Instant::from_std(capture_ready.monotonic + stop_after),
        }
    }
}

#[derive(Debug, Clone, Copy, PartialEq, Eq)]
pub enum RecordingDeadlineSignal {
    Warning { seconds_remaining: u32 },
    Reached,
}

pub async fn drive_recording_deadline<IsActive, Emit>(
    deadline: RecordingDeadline,
    is_active: IsActive,
    mut emit: Emit,
) -> bool
where
    IsActive: Fn() -> bool,
    Emit: FnMut(RecordingDeadlineSignal),
{
    let warnings: &[u32] = if deadline.event.effective_max_seconds >= 300 {
        &[60, 10]
    } else {
        &[10]
    };
    for &seconds_remaining in warnings {
        if deadline.event.effective_max_seconds <= seconds_remaining {
            continue;
        }
        let warning_at = deadline.stop_at - Duration::from_secs(u64::from(seconds_remaining));
        tokio::time::sleep_until(warning_at).await;
        if !is_active() {
            return false;
        }
        emit(RecordingDeadlineSignal::Warning { seconds_remaining });
    }

    tokio::time::sleep_until(deadline.stop_at).await;
    if !is_active() {
        return false;
    }
    emit(RecordingDeadlineSignal::Reached);
    true
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::sync::atomic::{AtomicBool, Ordering};
    use std::sync::{Arc, Mutex};

    fn capture_ready(seconds_before_now: u64) -> CaptureReadyAt {
        CaptureReadyAt {
            monotonic: (tokio::time::Instant::now() - Duration::from_secs(seconds_before_now))
                .into_std(),
            unix_millis: 1_000_000 - seconds_before_now * 1_000,
        }
    }

    #[tokio::test(start_paused = true)]
    async fn deadline_is_anchored_to_capture_ready_not_provider_ready() {
        let deadline = RecordingDeadline::new(7, RecordingKind::Dictation, capture_ready(20), 30);
        let signals = Arc::new(Mutex::new(Vec::new()));
        let task_signals = signals.clone();

        let task = tokio::spawn(drive_recording_deadline(
            deadline,
            || true,
            move |signal| task_signals.lock().unwrap().push(signal),
        ));
        tokio::time::advance(Duration::from_millis(9_749)).await;
        assert!(!task.is_finished());
        tokio::time::advance(Duration::from_millis(1)).await;

        assert!(task.await.unwrap());
        assert_eq!(
            *signals.lock().unwrap(),
            vec![
                RecordingDeadlineSignal::Warning {
                    seconds_remaining: 10
                },
                RecordingDeadlineSignal::Reached,
            ]
        );
    }

    #[tokio::test(start_paused = true)]
    async fn emits_sixty_and_ten_second_warnings_before_one_stop() {
        let deadline = RecordingDeadline::new(8, RecordingKind::Ask, capture_ready(0), 300);
        let signals = Arc::new(Mutex::new(Vec::new()));
        let task_signals = signals.clone();
        let task = tokio::spawn(drive_recording_deadline(
            deadline,
            || true,
            move |signal| task_signals.lock().unwrap().push(signal),
        ));

        tokio::time::advance(Duration::from_millis(239_750)).await;
        tokio::task::yield_now().await;
        assert_eq!(
            *signals.lock().unwrap(),
            vec![RecordingDeadlineSignal::Warning {
                seconds_remaining: 60
            }]
        );
        tokio::time::advance(Duration::from_secs(50)).await;
        tokio::task::yield_now().await;
        assert_eq!(signals.lock().unwrap().len(), 2);
        tokio::time::advance(Duration::from_secs(10)).await;

        assert!(task.await.unwrap());
        assert_eq!(
            signals.lock().unwrap().last(),
            Some(&RecordingDeadlineSignal::Reached)
        );
    }

    #[tokio::test(start_paused = true)]
    async fn a_limit_below_five_minutes_only_warns_at_ten_seconds() {
        let deadline = RecordingDeadline::new(11, RecordingKind::Dictation, capture_ready(0), 120);
        let signals = Arc::new(Mutex::new(Vec::new()));
        let task_signals = signals.clone();
        let task = tokio::spawn(drive_recording_deadline(
            deadline,
            || true,
            move |signal| task_signals.lock().unwrap().push(signal),
        ));

        tokio::time::advance(Duration::from_millis(109_750)).await;
        tokio::task::yield_now().await;
        assert_eq!(
            *signals.lock().unwrap(),
            vec![RecordingDeadlineSignal::Warning {
                seconds_remaining: 10
            }]
        );
        tokio::time::advance(Duration::from_secs(10)).await;
        assert!(task.await.unwrap());
    }

    #[tokio::test(start_paused = true)]
    async fn cancelled_or_stale_session_never_requests_stop() {
        let deadline = RecordingDeadline::new(9, RecordingKind::Dictation, capture_ready(0), 30);
        let active = Arc::new(AtomicBool::new(true));
        let task_active = active.clone();
        let signals = Arc::new(Mutex::new(Vec::new()));
        let task_signals = signals.clone();
        let task = tokio::spawn(drive_recording_deadline(
            deadline,
            move || task_active.load(Ordering::SeqCst),
            move |signal| task_signals.lock().unwrap().push(signal),
        ));

        active.store(false, Ordering::SeqCst);
        tokio::time::advance(Duration::from_secs(30)).await;

        assert!(!task.await.unwrap());
        assert!(signals.lock().unwrap().is_empty());
    }

    #[test]
    fn deadline_metadata_snapshots_the_resolved_limit() {
        let deadline = RecordingDeadline::new(
            10,
            RecordingKind::Dictation,
            CaptureReadyAt {
                monotonic: Instant::now(),
                unix_millis: 2_000_000,
            },
            600,
        );

        assert_eq!(deadline.event.session_id, 10);
        assert_eq!(deadline.event.effective_max_seconds, 600);
        assert_eq!(deadline.event.started_at_unix_ms, 2_000_000);
        assert_eq!(deadline.event.deadline_at_unix_ms, 2_599_750);
    }
}
