//! Pure rules for learning dictionary words from the user's edits after a dictation.
//!
//! Tracking and the large-modify thresholds mirror Typeless's edit tracker. Typeless sends small
//! edits to its server, which picks the word; OpenTypeless picks it locally with conservative rules.

use std::time::{Duration, Instant};

/// Typeless `maxTrackTime`: stop watching 15 s after the last edit.
pub const TRACK_IDLE_TIMEOUT: Duration = Duration::from_secs(15);
/// Upper bound so a field that keeps changing cannot be watched forever.
pub const TRACK_MAX_DURATION: Duration = Duration::from_secs(120);
/// Typeless only diffs the first 1000 characters of the field.
const DIFF_WINDOW_CHARS: usize = 1000;
const MAX_TERM_CHARS: usize = 40;
const MAX_TERM_WORDS: usize = 4;
/// Han names are 2–4 characters; longer ideograph-only swaps are rephrasing.
const MAX_TERM_IDEOGRAPHS: usize = 4;
const MAX_REPLACEMENT_HUNKS: usize = 3;
const MAX_DIFF_TOKENS: usize = 400;

#[derive(Clone, Debug, PartialEq, Eq)]
pub struct FieldSnapshot {
    /// Stable for the same focused element of the same app.
    pub identity: (i32, usize),
    pub value: String,
    pub editable: bool,
    pub secure: bool,
}

/// Typeless `normalizeText`: line breaks and zero-width marks don't count as edits.
pub fn normalize_text(text: &str) -> String {
    text.chars()
        .filter(|c| {
            !matches!(
                c,
                '\r' | '\n'
                    | '\u{200B}'
                    | '\u{200C}'
                    | '\u{200D}'
                    | '\u{200E}'
                    | '\u{200F}'
                    | '\u{FEFF}'
            )
        })
        .collect()
}

pub fn includes_refined_text(field: &str, refined: &str) -> bool {
    normalize_text(field).contains(&normalize_text(refined))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
pub struct Modification {
    pub added: usize,
    pub removed: usize,
    pub is_large: bool,
}

/// Typeless `analysisModification`: a rewrite, a mostly-deleted field or a wholesale replacement
/// is not a correction worth learning from.
pub fn analyse_modification(original: &str, current: &str) -> Modification {
    let a: Vec<char> = normalize_text(&take_chars(original, DIFF_WINDOW_CHARS))
        .chars()
        .collect();
    let b: Vec<char> = normalize_text(&take_chars(current, DIFF_WINDOW_CHARS))
        .chars()
        .collect();
    let common = lcs_len(&a, &b);
    let removed = a.len() - common;
    let added = b.len() - common;
    let base = a.len() as f64;
    let is_large = (current.chars().count() as f64) < original.chars().count() as f64 * 0.2
        || removed as f64 > base * 0.5
        || (added + removed) as f64 > base * 2.0;
    Modification {
        added,
        removed,
        is_large,
    }
}

#[derive(Clone, Debug, PartialEq, Eq)]
pub enum PollOutcome {
    Continue,
    /// Tracking ended; evaluate this field content (Typeless reports the last stable state).
    Finish(Option<String>),
    /// Tracking ended without anything worth evaluating.
    Abort,
}

#[derive(Debug)]
pub struct EditSession {
    identity: (i32, usize),
    original: String,
    refined: String,
    last_value: String,
    last_stable: Option<String>,
    started: Instant,
    deadline: Instant,
}

impl EditSession {
    /// Typeless `startTrack`: only editable fields that visibly contain the inserted text.
    pub fn begin(snapshot: &FieldSnapshot, refined: &str, now: Instant) -> Option<Self> {
        if !snapshot.editable
            || snapshot.secure
            || refined.trim().is_empty()
            || !includes_refined_text(&snapshot.value, refined)
        {
            return None;
        }
        Some(Self {
            identity: snapshot.identity,
            original: snapshot.value.clone(),
            refined: refined.to_string(),
            last_value: snapshot.value.clone(),
            last_stable: None,
            started: now,
            deadline: now + TRACK_IDLE_TIMEOUT,
        })
    }

    pub fn original(&self) -> &str {
        &self.original
    }

    pub fn refined(&self) -> &str {
        &self.refined
    }

    pub fn poll(&mut self, snapshot: Option<&FieldSnapshot>, now: Instant) -> PollOutcome {
        let Some(snapshot) = snapshot else {
            return PollOutcome::Finish(self.last_stable.take());
        };
        if snapshot.identity != self.identity || snapshot.secure || !snapshot.editable {
            return PollOutcome::Finish(self.last_stable.take());
        }
        // Chat apps clear the box on send; judge what was there just before.
        if snapshot.value.trim().is_empty() {
            return PollOutcome::Finish(self.last_stable.take());
        }
        if analyse_modification(&self.original, &snapshot.value).is_large {
            return PollOutcome::Abort;
        }
        if snapshot.value != self.last_value {
            if !includes_refined_text(&snapshot.value, &self.refined) {
                self.deadline = now + TRACK_IDLE_TIMEOUT;
            }
            self.last_value = snapshot.value.clone();
        }
        self.last_stable = Some(snapshot.value.clone());
        if now >= self.deadline || now.duration_since(self.started) >= TRACK_MAX_DURATION {
            return PollOutcome::Finish(self.last_stable.take());
        }
        PollOutcome::Continue
    }
}

/// Typeless `checkEditedText` skip rules, then the local word pick.
pub fn evaluate_edit(original: &str, edited: &str, refined: &str) -> Option<String> {
    if edited.trim().is_empty() || includes_refined_text(edited, refined) {
        return None;
    }
    if analyse_modification(original, edited).is_large {
        return None;
    }
    extract_learned_term(original, edited, refined)
}

/// Picks the word the user typed over part of the dictated text, if it looks like a name or term.
pub fn extract_learned_term(original: &str, edited: &str, refined: &str) -> Option<String> {
    let original: Vec<char> = normalize_text(original).chars().collect();
    let edited: Vec<char> = normalize_text(edited).chars().collect();
    let refined: Vec<char> = normalize_text(refined).chars().collect();
    let start = rfind_chars(&original, &refined)?;
    let end = start + refined.len();

    let mut prefix = 0;
    while prefix < original.len() && prefix < edited.len() && original[prefix] == edited[prefix] {
        prefix += 1;
    }
    let mut suffix = 0;
    while suffix < original.len() - prefix
        && suffix < edited.len() - prefix
        && original[original.len() - 1 - suffix] == edited[edited.len() - 1 - suffix]
    {
        suffix += 1;
    }
    // Edits must stay inside the dictated text; touching the user's own text is not a correction.
    if prefix < start || original.len() - suffix > end {
        return None;
    }
    let edited_end = edited.len() - (original.len() - end);
    if edited_end < start {
        return None;
    }
    let edited_refined = &edited[start..edited_end];

    let before = tokenize(&refined);
    let after = tokenize(edited_refined);
    if before.len() > MAX_DIFF_TOKENS || after.len() > MAX_DIFF_TOKENS {
        return None;
    }
    let hunks = replacement_hunks(&before, &after);
    if hunks.is_empty() || hunks.len() > MAX_REPLACEMENT_HUNKS {
        return None;
    }
    hunks.into_iter().find_map(|(removed, added)| {
        let removed_text: String = removed
            .iter()
            .map(|token| token.text.as_str())
            .collect::<Vec<_>>()
            .join(" ");
        let first = added.first()?;
        let last = added.last()?;
        let term: String = edited_refined[first.start..last.end].iter().collect();
        accept_term(
            term.trim(),
            &removed,
            &added,
            &removed_text,
            starts_sentence(edited_refined, first.start),
        )
        .then(|| term.trim().to_string())
    })
}

fn accept_term(
    term: &str,
    removed: &[Token],
    added: &[Token],
    removed_text: &str,
    at_sentence_start: bool,
) -> bool {
    let chars: Vec<char> = term.chars().collect();
    if chars.len() < 2 || chars.len() > MAX_TERM_CHARS || !chars.iter().any(|c| c.is_alphabetic()) {
        return false;
    }
    let words = added.iter().filter(|t| t.kind == TokenKind::Word).count();
    let ideographs = added
        .iter()
        .filter(|t| t.kind == TokenKind::Ideograph)
        .count();
    if words > MAX_TERM_WORDS || ideographs > MAX_TERM_IDEOGRAPHS {
        return false;
    }
    if words == 0 && ideographs < 2 {
        return false;
    }

    let lower = term.to_lowercase();
    let removed_lower = removed_text.replace(' ', "").to_lowercase();
    let inner_upper = chars.iter().skip(1).any(|c| c.is_uppercase());
    if lower.replace(' ', "") == removed_lower {
        // Pure re-casing only counts for brand-style casing such as "iPhone" or "OpenAI".
        return inner_upper;
    }
    let leading_upper = chars[0].is_uppercase() && !at_sentence_start;
    // Han and kana have no case, so a multi-character replacement is the only signal there.
    let ideographic_term = words == 0;
    let mixed_alnum =
        chars.iter().any(|c| c.is_ascii_digit()) && chars.iter().any(|c| c.is_alphabetic());
    let joined = chars
        .windows(2)
        .any(|pair| matches!(pair[0], '.' | '-' | '_' | '+' | '#') && pair[1].is_alphanumeric())
        || term.ends_with("++")
        || term.ends_with('#');
    let merged = words == 1 && removed.len() >= 2;
    inner_upper || leading_upper || ideographic_term || mixed_alnum || joined || merged
}

fn starts_sentence(text: &[char], index: usize) -> bool {
    text[..index]
        .iter()
        .rev()
        .find(|c| !c.is_whitespace())
        .is_none_or(|c| matches!(c, '.' | '!' | '?' | '。' | '！' | '？' | '…' | ':' | '：'))
}

#[derive(Clone, Copy, Debug, PartialEq, Eq)]
enum TokenKind {
    Word,
    Ideograph,
    Punct,
}

#[derive(Clone, Debug, PartialEq, Eq)]
struct Token {
    text: String,
    start: usize,
    end: usize,
    kind: TokenKind,
}

fn is_ideograph(c: char) -> bool {
    matches!(c,
        '\u{3040}'..='\u{30FF}'
        | '\u{3400}'..='\u{4DBF}'
        | '\u{4E00}'..='\u{9FFF}'
        | '\u{F900}'..='\u{FAFF}'
        | '\u{FF66}'..='\u{FF9F}'
        | '\u{20000}'..='\u{2A6DF}')
}

fn is_word_char(c: char) -> bool {
    c.is_alphanumeric() && !is_ideograph(c)
}

fn tokenize(text: &[char]) -> Vec<Token> {
    let mut tokens = Vec::new();
    let mut i = 0;
    while i < text.len() {
        let c = text[i];
        if c.is_whitespace() {
            i += 1;
            continue;
        }
        let start = i;
        let kind = if is_ideograph(c) {
            i += 1;
            TokenKind::Ideograph
        } else if is_word_char(c) {
            i += 1;
            while i < text.len() {
                let next = text.get(i + 1).copied();
                if is_word_char(text[i]) {
                    i += 1;
                } else if matches!(text[i], '-' | '_' | '.' | '\'' | '’')
                    && next.is_some_and(is_word_char)
                {
                    i += 2;
                } else if matches!(text[i], '+' | '#') {
                    i += 1;
                } else {
                    break;
                }
            }
            TokenKind::Word
        } else {
            i += 1;
            TokenKind::Punct
        };
        tokens.push(Token {
            text: text[start..i].iter().collect(),
            start,
            end: i,
            kind,
        });
    }
    tokens
}

/// Word-level diff hunks that replace something (pure insertions and deletions are ignored).
fn replacement_hunks(before: &[Token], after: &[Token]) -> Vec<(Vec<Token>, Vec<Token>)> {
    let n = before.len();
    let m = after.len();
    let mut table = vec![vec![0u16; m + 1]; n + 1];
    for i in (0..n).rev() {
        for j in (0..m).rev() {
            table[i][j] = if before[i].text == after[j].text {
                table[i + 1][j + 1] + 1
            } else {
                table[i + 1][j].max(table[i][j + 1])
            };
        }
    }

    let mut hunks = Vec::new();
    let (mut removed, mut added) = (Vec::new(), Vec::new());
    let flush = |removed: &mut Vec<Token>, added: &mut Vec<Token>, hunks: &mut Vec<_>| {
        removed.retain(|t: &Token| t.kind != TokenKind::Punct);
        added.retain(|t: &Token| t.kind != TokenKind::Punct);
        if !removed.is_empty() && !added.is_empty() {
            hunks.push((std::mem::take(removed), std::mem::take(added)));
        }
        removed.clear();
        added.clear();
    };
    let (mut i, mut j) = (0, 0);
    while i < n || j < m {
        if i < n && j < m && before[i].text == after[j].text {
            flush(&mut removed, &mut added, &mut hunks);
            i += 1;
            j += 1;
        } else if j < m && (i == n || table[i][j + 1] >= table[i + 1][j]) {
            added.push(after[j].clone());
            j += 1;
        } else {
            removed.push(before[i].clone());
            i += 1;
        }
    }
    flush(&mut removed, &mut added, &mut hunks);
    hunks
}

fn take_chars(text: &str, limit: usize) -> String {
    text.chars().take(limit).collect()
}

fn rfind_chars(haystack: &[char], needle: &[char]) -> Option<usize> {
    if needle.is_empty() || needle.len() > haystack.len() {
        return None;
    }
    (0..=haystack.len() - needle.len())
        .rev()
        .find(|&start| haystack[start..start + needle.len()] == *needle)
}

fn lcs_len(a: &[char], b: &[char]) -> usize {
    let prefix = a.iter().zip(b).take_while(|(x, y)| x == y).count();
    let (a, b) = (&a[prefix..], &b[prefix..]);
    let suffix = a
        .iter()
        .rev()
        .zip(b.iter().rev())
        .take_while(|(x, y)| x == y)
        .count();
    let (a, b) = (&a[..a.len() - suffix], &b[..b.len() - suffix]);
    let mut row = vec![0usize; b.len() + 1];
    for x in a {
        let mut diagonal = 0;
        for (j, y) in b.iter().enumerate() {
            let above = row[j + 1];
            row[j + 1] = if x == y {
                diagonal + 1
            } else {
                above.max(row[j])
            };
            diagonal = above;
        }
    }
    prefix + suffix + row[b.len()]
}

#[cfg(test)]
mod tests {
    use super::*;

    fn snapshot(value: &str) -> FieldSnapshot {
        FieldSnapshot {
            identity: (42, 7),
            value: value.to_string(),
            editable: true,
            secure: false,
        }
    }

    fn learned(original: &str, edited: &str, refined: &str) -> Option<String> {
        evaluate_edit(original, edited, refined)
    }

    #[test]
    fn normalize_drops_line_breaks_and_zero_width_marks() {
        assert_eq!(normalize_text("a\r\nb\u{200B}c\u{FEFF}"), "abc");
        assert!(includes_refined_text("Hi\nthere", "Hithere"));
    }

    #[test]
    fn large_modify_follows_typeless_thresholds() {
        let original = "We shipped the new onboarding flow today";
        assert!(!analyse_modification(original, "We shipped the new Typeless flow today").is_large);
        // Field shrank below 20% of its original length.
        assert!(analyse_modification(original, "We").is_large);
        // More than half of the original removed.
        assert!(analyse_modification("abcdefghij", "abcXYZ").is_large);
        // Changes add up to more than twice the original.
        assert!(analyse_modification("abcd", "abcd and much more").is_large);
        let modification = analyse_modification("abc", "abXc");
        assert_eq!((modification.added, modification.removed), (1, 0));
    }

    #[test]
    fn learns_a_corrected_name() {
        let refined = "Please send it to john smith tomorrow.";
        let original = format!("Hi team, {refined}");
        let edited = "Hi team, Please send it to Jon Smyth tomorrow.";
        assert_eq!(
            learned(&original, edited, refined).as_deref(),
            Some("Jon Smyth")
        );
    }

    #[test]
    fn learns_brand_casing_and_merged_terms() {
        let refined = "I use open ai and cube control every day";
        assert_eq!(
            learned(refined, "I use OpenAI and cube control every day", refined).as_deref(),
            Some("OpenAI")
        );
        assert_eq!(
            learned(refined, "I use open ai and kubectl every day", refined).as_deref(),
            Some("kubectl")
        );
        let refined = "Order a new iphone for the office";
        assert_eq!(
            learned(refined, "Order a new iPhone for the office", refined).as_deref(),
            Some("iPhone")
        );
    }

    #[test]
    fn learns_cjk_replacements_with_latin_or_multi_ideograph_terms() {
        let refined = "我们今天用泰普勒斯写周报";
        assert_eq!(
            learned(refined, "我们今天用Typeless写周报", refined).as_deref(),
            Some("Typeless")
        );
        let refined = "请联系张伟经理";
        assert_eq!(
            learned(refined, "请联系章蔚经理", refined).as_deref(),
            Some("章蔚")
        );
        // A single-character fix is too ambiguous to be a word.
        assert_eq!(learned(refined, "请联系张蔚经理", refined), None);
    }

    #[test]
    fn ignores_ordinary_word_fixes_and_sentence_case() {
        let refined = "their going to the park later";
        assert_eq!(
            learned(refined, "there going to the park later", refined),
            None
        );
        assert_eq!(
            learned(refined, "They're going to the park later", refined),
            None
        );
        let refined = "hello world from the team";
        assert_eq!(learned(refined, "Hello world from the team", refined), None);
        assert_eq!(
            learned(refined, "hello world from the team 2", refined),
            None
        );
    }

    #[test]
    fn ignores_unchanged_cleared_rewritten_or_outside_edits() {
        let refined = "Meet Alex at noon";
        let original = format!("Notes: {refined}");
        assert_eq!(learned(&original, &original, refined), None);
        assert_eq!(learned(&original, "   ", refined), None);
        assert_eq!(
            learned(&original, "Something completely different now", refined),
            None
        );
        // The user edited their own text before the dictation, not the dictation.
        assert_eq!(learned(&original, "Memo: Meet Alex at noon", refined), None);
    }

    #[test]
    fn keeps_latin_terms_with_digits_or_joiners() {
        let refined = "deploy it with node js and gpt four";
        assert_eq!(
            learned(refined, "deploy it with Node.js and gpt four", refined).as_deref(),
            Some("Node.js")
        );
        assert_eq!(
            learned(refined, "deploy it with node js and GPT-4", refined).as_deref(),
            Some("GPT-4")
        );
    }

    #[test]
    fn session_starts_only_on_editable_fields_with_the_inserted_text() {
        let now = Instant::now();
        assert!(EditSession::begin(&snapshot("Hello there"), "there", now).is_some());
        assert!(EditSession::begin(&snapshot("Hello"), "there", now).is_none());
        let mut secure = snapshot("there");
        secure.secure = true;
        assert!(EditSession::begin(&secure, "there", now).is_none());
        let mut readonly = snapshot("there");
        readonly.editable = false;
        assert!(EditSession::begin(&readonly, "there", now).is_none());
    }

    #[test]
    fn session_does_not_consume_text_after_the_field_becomes_readonly() {
        let now = Instant::now();
        let mut session =
            EditSession::begin(&snapshot("ask john now"), "ask john now", now).unwrap();
        session.poll(Some(&snapshot("ask Jon now")), now);
        let mut readonly = snapshot("ask Jonathan now");
        readonly.editable = false;
        assert_eq!(
            session.poll(Some(&readonly), now),
            PollOutcome::Finish(Some("ask Jon now".to_string()))
        );
    }

    #[test]
    fn session_reports_last_stable_content_when_focus_moves_or_field_clears() {
        let now = Instant::now();
        let mut session =
            EditSession::begin(&snapshot("ask john now"), "ask john now", now).unwrap();
        assert_eq!(
            session.poll(Some(&snapshot("ask Jon now")), now),
            PollOutcome::Continue
        );
        assert_eq!(
            session.poll(Some(&snapshot("")), now),
            PollOutcome::Finish(Some("ask Jon now".to_string()))
        );

        let mut session =
            EditSession::begin(&snapshot("ask john now"), "ask john now", now).unwrap();
        session.poll(Some(&snapshot("ask Jon now")), now);
        let mut other = snapshot("x");
        other.identity = (42, 8);
        assert_eq!(
            session.poll(Some(&other), now),
            PollOutcome::Finish(Some("ask Jon now".to_string()))
        );
        assert_eq!(session.poll(None, now), PollOutcome::Finish(None));
    }

    #[test]
    fn session_times_out_after_idle_and_resets_on_edits() {
        let now = Instant::now();
        let mut session =
            EditSession::begin(&snapshot("ask john now"), "ask john now", now).unwrap();
        let later = now + Duration::from_secs(10);
        assert_eq!(
            session.poll(Some(&snapshot("ask Jon now")), later),
            PollOutcome::Continue
        );
        // The edit at 10 s pushed the deadline to 25 s.
        assert_eq!(
            session.poll(
                Some(&snapshot("ask Jon now")),
                now + Duration::from_secs(20)
            ),
            PollOutcome::Continue
        );
        assert_eq!(
            session.poll(
                Some(&snapshot("ask Jon now")),
                now + Duration::from_secs(26)
            ),
            PollOutcome::Finish(Some("ask Jon now".to_string()))
        );
    }

    #[test]
    fn session_aborts_on_a_large_rewrite() {
        let now = Instant::now();
        let mut session = EditSession::begin(
            &snapshot("Please call john tomorrow"),
            "Please call john tomorrow",
            now,
        )
        .unwrap();
        assert_eq!(session.poll(Some(&snapshot("ok")), now), PollOutcome::Abort);
    }
}
