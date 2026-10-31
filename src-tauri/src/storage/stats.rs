//! Usage statistics derived from local history (Typeless home "Insights").

use chrono::{Duration, NaiveDate};
use serde::Serialize;

/// Typing speed used to estimate time saved by dictation, in words per minute.
pub const TYPING_BASELINE_WPM: f64 = 40.0;

#[derive(Debug, Clone, PartialEq)]
pub struct StatsSample {
    /// Local date of the dictation (`YYYY-MM-DD`).
    pub date: NaiveDate,
    pub text: String,
    pub duration_ms: Option<i64>,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct DailyActivity {
    pub date: String,
    pub words: u64,
    pub sessions: u64,
}

#[derive(Debug, Clone, PartialEq, Serialize)]
#[serde(rename_all = "camelCase")]
pub struct UsageStats {
    pub total_words: u64,
    pub total_sessions: u64,
    pub total_audio_seconds: u64,
    pub average_wpm: Option<u32>,
    pub minutes_saved: u64,
    pub active_days: u64,
    pub current_streak: u64,
    pub longest_streak: u64,
    pub daily: Vec<DailyActivity>,
}

fn is_cjk(c: char) -> bool {
    matches!(c as u32,
        0x3040..=0x30FF   // Hiragana, Katakana
        | 0x3400..=0x4DBF // CJK Extension A
        | 0x4E00..=0x9FFF // CJK Unified Ideographs
        | 0xAC00..=0xD7AF // Hangul syllables
        | 0xF900..=0xFAFF // CJK Compatibility Ideographs
    )
}

/// Counts words the way users read them: each CJK character is a word, and runs of
/// other non-whitespace characters count as one word.
pub fn count_words(text: &str) -> u64 {
    let mut words = 0;
    let mut in_word = false;
    for c in text.chars() {
        if is_cjk(c) {
            words += 1;
            in_word = false;
        } else if c.is_whitespace() || (c.is_ascii_punctuation() && !in_word) {
            in_word = false;
        } else if !c.is_ascii_punctuation() && !in_word {
            words += 1;
            in_word = true;
        }
    }
    words
}

pub fn compute_usage_stats(samples: &[StatsSample], today: NaiveDate) -> UsageStats {
    let mut by_day: std::collections::BTreeMap<NaiveDate, (u64, u64)> = Default::default();
    let mut total_words = 0;
    let mut total_audio_ms: i64 = 0;
    let mut timed_words = 0;

    for sample in samples {
        let words = count_words(&sample.text);
        total_words += words;
        let day = by_day.entry(sample.date).or_default();
        day.0 += words;
        day.1 += 1;
        if let Some(duration) = sample.duration_ms.filter(|duration| *duration > 0) {
            total_audio_ms += duration;
            timed_words += words;
        }
    }

    let average_wpm = (total_audio_ms > 0)
        .then(|| (timed_words as f64 / (total_audio_ms as f64 / 60_000.0)).round() as u32);
    let audio_minutes = total_audio_ms as f64 / 60_000.0;
    let typing_minutes = total_words as f64 / TYPING_BASELINE_WPM;
    let minutes_saved = (typing_minutes - audio_minutes).max(0.0).round() as u64;

    let mut longest_streak = 0;
    let mut run = 0;
    let mut previous: Option<NaiveDate> = None;
    for date in by_day.keys() {
        run = match previous {
            Some(prev) if *date - prev == Duration::days(1) => run + 1,
            _ => 1,
        };
        longest_streak = longest_streak.max(run);
        previous = Some(*date);
    }

    // A streak stays current until a full day passes without dictation.
    let mut current_streak = 0;
    let mut cursor = if by_day.contains_key(&today) {
        today
    } else {
        today - Duration::days(1)
    };
    while by_day.contains_key(&cursor) {
        current_streak += 1;
        cursor -= Duration::days(1);
    }

    UsageStats {
        total_words,
        total_sessions: samples.len() as u64,
        total_audio_seconds: (total_audio_ms / 1000).max(0) as u64,
        average_wpm,
        minutes_saved,
        active_days: by_day.len() as u64,
        current_streak,
        longest_streak,
        daily: by_day
            .into_iter()
            .map(|(date, (words, sessions))| DailyActivity {
                date: date.format("%Y-%m-%d").to_string(),
                words,
                sessions,
            })
            .collect(),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn day(value: &str) -> NaiveDate {
        NaiveDate::parse_from_str(value, "%Y-%m-%d").unwrap()
    }

    fn sample(date: &str, text: &str, duration_ms: Option<i64>) -> StatsSample {
        StatsSample {
            date: day(date),
            text: text.to_string(),
            duration_ms,
        }
    }

    #[test]
    fn counts_latin_words_and_each_cjk_character() {
        assert_eq!(count_words("Hello, world!"), 2);
        assert_eq!(count_words("  spaced   out  "), 2);
        assert_eq!(count_words("说话不要打字"), 6);
        assert_eq!(count_words("用 Typeless 说话"), 4);
        assert_eq!(count_words("こんにちは"), 5);
        assert_eq!(count_words(""), 0);
    }

    #[test]
    fn computes_totals_wpm_and_time_saved() {
        let stats = compute_usage_stats(
            &[
                sample("2026-10-30", "one two three four five six", Some(3_000)),
                sample("2026-10-31", "说话不要打字", Some(3_000)),
            ],
            day("2026-10-31"),
        );
        assert_eq!(stats.total_words, 12);
        assert_eq!(stats.total_sessions, 2);
        assert_eq!(stats.total_audio_seconds, 6);
        assert_eq!(stats.average_wpm, Some(120));
        // 12 words take 0.3 min to type at 40 WPM; 0.1 min was spoken.
        assert_eq!(stats.minutes_saved, 0);
    }

    #[test]
    fn untimed_entries_count_words_but_not_speed() {
        let stats = compute_usage_stats(&[sample("2026-10-31", "a b c", None)], day("2026-10-31"));
        assert_eq!(stats.total_words, 3);
        assert_eq!(stats.average_wpm, None);
    }

    #[test]
    fn tracks_current_and_longest_streaks() {
        let samples = [
            sample("2026-10-20", "a", None),
            sample("2026-10-21", "a", None),
            sample("2026-10-22", "a", None),
            sample("2026-10-29", "a", None),
            sample("2026-10-30", "a", None),
            sample("2026-10-30", "b", None),
        ];
        let stats = compute_usage_stats(&samples, day("2026-10-31"));
        assert_eq!(stats.active_days, 5);
        assert_eq!(stats.longest_streak, 3);
        // Yesterday counts until today ends without a dictation.
        assert_eq!(stats.current_streak, 2);
        assert_eq!(stats.daily.len(), 5);
        assert_eq!(stats.daily.last().unwrap().sessions, 2);

        let broken = compute_usage_stats(&samples, day("2026-11-02"));
        assert_eq!(broken.current_streak, 0);
    }

    #[test]
    fn empty_history_has_zero_stats() {
        let stats = compute_usage_stats(&[], day("2026-10-31"));
        assert_eq!(stats.total_words, 0);
        assert_eq!(stats.current_streak, 0);
        assert_eq!(stats.longest_streak, 0);
        assert!(stats.daily.is_empty());
    }
}
