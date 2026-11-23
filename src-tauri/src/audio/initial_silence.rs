use std::time::Duration;

#[derive(Default)]
pub(crate) struct InitialSilence {
    heard_audio: bool,
    stopped: bool,
    last_sample: Option<Duration>,
}

impl InitialSilence {
    pub(crate) fn observe_audio_activity(&mut self, heard: bool) {
        self.heard_audio |= heard;
    }
    pub(crate) fn observe_sample(
        &mut self,
        elapsed: Duration,
        level: Option<f32>,
        released: impl FnOnce() -> Result<bool, String>,
    ) -> bool {
        let previous = self.last_sample.unwrap_or(Duration::ZERO);
        if elapsed < previous || elapsed.saturating_sub(previous) > Duration::from_secs(1) {
            self.heard_audio = true;
            return false;
        }
        self.last_sample = Some(elapsed);
        self.should_stop_with_release_query(elapsed, level, released)
    }

    #[cfg(test)]
    pub(crate) fn should_stop_after_release(
        &mut self,
        elapsed: Duration,
        level: Option<f32>,
        released: Result<bool, String>,
    ) -> bool {
        self.should_stop_with_release_query(elapsed, level, || released)
    }

    pub(crate) fn should_stop_with_release_query(
        &mut self,
        elapsed: Duration,
        level: Option<f32>,
        released: impl FnOnce() -> Result<bool, String>,
    ) -> bool {
        if self.stopped || self.heard_audio {
            return false;
        }
        if level.is_none_or(|level| !level.is_finite() || !(0.0..=1.0).contains(&level))
            || level.is_some_and(|level| level > 0.001)
        {
            self.heard_audio = true;
            return false;
        }
        if elapsed < Duration::from_secs(6) || released() != Ok(true) {
            return false;
        }
        self.should_stop(elapsed, level)
    }

    pub(crate) fn observe_transcript(&mut self, text: &str) {
        if !text.trim().is_empty() {
            self.heard_audio = true;
        }
    }

    pub(crate) fn should_stop(&mut self, elapsed: Duration, level: Option<f32>) -> bool {
        if self.stopped || self.heard_audio {
            return false;
        }
        let Some(level) = level.filter(|level| level.is_finite() && (0.0..=1.0).contains(level))
        else {
            self.heard_audio = true;
            return false;
        };
        if level > 0.001 {
            self.heard_audio = true;
            return false;
        }
        if elapsed >= Duration::from_secs(6) {
            self.stopped = true;
            return true;
        }
        false
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn delayed_first_sample_or_sampling_gap_cannot_prove_continuous_silence() {
        let mut delayed = InitialSilence::default();
        assert!(
            !delayed.observe_sample(Duration::from_secs(6), Some(0.0), || {
                panic!("delayed sample must not query keys")
            })
        );
        let mut interrupted = InitialSilence::default();
        assert!(!interrupted.observe_sample(Duration::from_millis(50), Some(0.0), || Ok(true)));
        assert!(
            !interrupted.observe_sample(Duration::from_secs(6), Some(0.0), || {
                panic!("sampling gap must not query keys")
            })
        );
    }

    #[test]
    fn continuous_samples_allow_one_initial_silent_stop() {
        let mut detection = InitialSilence::default();
        for sample in 1..120 {
            assert!(!detection.observe_sample(
                Duration::from_millis(sample * 50),
                Some(0.0),
                || panic!("early samples must not query keys"),
            ));
        }
        assert!(detection.observe_sample(Duration::from_secs(6), Some(0.0), || Ok(true)));
    }

    #[test]
    fn release_query_runs_only_for_eligible_silent_stop() {
        let mut detection = InitialSilence::default();
        assert!(!detection.should_stop_with_release_query(
            Duration::from_secs(5),
            Some(0.0),
            || panic!("early recording must not query keys"),
        ));
        detection.observe_transcript("Synthetic speech");
        assert!(!detection.should_stop_with_release_query(
            Duration::from_secs(10),
            Some(0.0),
            || panic!("recognized speech must not query keys"),
        ));
        let mut silent = InitialSilence::default();
        let queries = std::cell::Cell::new(0);
        assert!(
            silent.should_stop_with_release_query(Duration::from_secs(6), Some(0.0), || {
                queries.set(queries.get() + 1);
                Ok(true)
            },)
        );
        assert_eq!(queries.get(), 1);
        assert!(!silent.should_stop_with_release_query(
            Duration::from_secs(7),
            Some(0.0),
            || panic!("completed detection must not query keys"),
        ));
    }

    #[test]
    fn held_or_unknown_keys_defer_silent_stop_until_release() {
        let mut detection = InitialSilence::default();
        assert!(!detection.should_stop_after_release(Duration::from_secs(6), Some(0.0), Ok(false),));
        assert!(!detection.should_stop_after_release(
            Duration::from_secs(7),
            Some(0.0),
            Err("unknown".into()),
        ));
        assert!(detection.should_stop_after_release(Duration::from_secs(8), Some(0.0), Ok(true),));
    }

    #[test]
    fn silence_only_stops_once_after_initial_budget() {
        let mut detection = InitialSilence::default();
        assert!(!detection.should_stop(Duration::from_secs(5), Some(0.0)));
        assert!(detection.should_stop(Duration::from_secs(6), Some(0.0)));
        assert!(!detection.should_stop(Duration::from_secs(7), Some(0.0)));
    }

    #[test]
    fn audio_disarms_detection_for_later_pauses() {
        let mut detection = InitialSilence::default();
        assert!(!detection.should_stop(Duration::from_secs(1), Some(0.01)));
        assert!(!detection.should_stop(Duration::from_secs(30), Some(0.0)));
    }

    #[test]
    fn unavailable_or_invalid_levels_are_not_silence() {
        for level in [None, Some(f32::NAN), Some(-1.0), Some(2.0)] {
            let mut detection = InitialSilence::default();
            assert!(!detection.should_stop(Duration::from_secs(1), level));
            assert!(!detection.should_stop(Duration::from_secs(10), Some(0.0)));
        }
    }

    #[test]
    fn recognized_text_disarms_silence_even_when_meter_missed_audio() {
        let mut detection = InitialSilence::default();
        detection.observe_transcript("Synthetic spoken sentence");
        assert!(!detection.should_stop(Duration::from_secs(6), Some(0.0)));
        let mut empty = InitialSilence::default();
        empty.observe_transcript(" \n ");
        assert!(empty.should_stop(Duration::from_secs(6), Some(0.0)));
    }
}
