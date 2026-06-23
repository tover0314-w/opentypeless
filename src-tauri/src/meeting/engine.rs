//! UI-agnostic meeting recording core.
//!
//! This module contains the portable `listen → segment` logic for the
//! meeting / long-recording mode (MEL-74 design §5). It deliberately has
//! **zero** dependencies on Tauri, rusqlite, or cpal so that it can be lifted
//! into a standalone `meeting_core` crate (and reused by a future wearable
//! host) without modification.
//!
//! The desktop host provides implementations of the traits below
//! (`AudioSource`, `Transcriber`, `MeetingStore`, `MeetingEvents`) in
//! `meeting::adapters`; this file owns only the state machine and the
//! segment-timing arithmetic.

use async_trait::async_trait;

/// PCM sample format the engine assumes throughout: 16 kHz, mono, 16-bit LE.
/// One sample = 2 bytes. Mirrors `audio::AudioConfig::default()`.
pub const PCM_SAMPLE_RATE: u32 = 16000;
pub const PCM_BYTES_PER_SAMPLE: usize = 2;

/// A chunk of raw PCM bytes pulled from the audio source (non-blocking).
#[derive(Debug, Clone)]
pub struct PcmChunk {
    pub bytes: Vec<u8>,
    pub sample_rate: u32,
}

/// Lifecycle state of a meeting recording (MEL-74 design §1.3).
///
/// `Recording → Recording` segment cuts are intra-state events and do not
/// change this value; only the four transitions below are observable.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum MeetingState {
    Idle,
    Recording,
    Finalizing,
    Archived,
}

/// Per-segment transcription status, surfaced to the UI for the rolling list.
#[derive(Debug, Clone, Copy, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "snake_case")]
pub enum SegStatus {
    Pending,
    Transcribing,
    Done,
    Failed,
}

/// Tunable timing knobs (MEL-74 design §2.1). Defaults are the locked-in
/// product values: 5-min segments, 3-hr cap, 15-s minimum segment.
#[derive(Debug, Clone, Copy)]
pub struct MeetingConfig {
    /// Cut a new segment after the current one reaches this length.
    pub segment_interval_secs: u32,
    /// Auto-finalize the whole meeting once total elapsed reaches this.
    pub max_meeting_secs: u32,
    /// Never emit a standalone segment shorter than this (merge into the next).
    pub min_segment_secs: u32,
}

impl Default for MeetingConfig {
    fn default() -> Self {
        Self {
            segment_interval_secs: 300,   // 5 minutes
            max_meeting_secs: 10800,      // 3 hours
            min_segment_secs: 15,
        }
    }
}

/// Metadata describing one finished segment handed to the downstream
/// (transcribe + store) layer. This struct plus the raw PCM bytes is the
/// MEL-75 → MEL-76 handoff contract.
#[derive(Debug, Clone, PartialEq, Eq)]
pub struct SegmentMeta {
    /// 0-based segment index within the session.
    pub seg_index: u32,
    /// Offset of this segment's start relative to meeting start.
    pub start_ms: u64,
    /// Offset of this segment's end relative to meeting start.
    pub end_ms: u64,
}

impl SegmentMeta {
    pub fn duration_ms(&self) -> u64 {
        self.end_ms.saturating_sub(self.start_ms)
    }
}

/// A closed segment: metadata + the PCM bytes captured during it.
/// This is what the engine produces and the `Transcriber` consumes.
#[derive(Debug, Clone)]
pub struct Segment {
    pub meta: SegmentMeta,
    pub pcm: Vec<u8>,
}

/// Events emitted by the engine for the host to forward to its UI / logs.
#[derive(Debug, Clone)]
pub enum MeetingEvent {
    State(MeetingState),
    Segment {
        idx: u32,
        status: SegStatus,
        text: String,
    },
    Progress {
        done: u32,
        total: u32,
    },
    Error(String),
}

/// Session metadata recorded at meeting start.
#[derive(Debug, Clone)]
pub struct SessionMeta {
    pub created_at: String,
    pub stt_provider: String,
    pub language: Option<String>,
}

/// One transcribed segment row to persist.
#[derive(Debug, Clone)]
pub struct SegmentRow {
    pub seg_index: u32,
    pub start_ms: u64,
    pub end_ms: u64,
    pub status: SegStatus,
    pub text: String,
}

// ─── Host-supplied trait boundaries (MEL-74 design §5.3) ───
//
// The engine depends only on these traits. The desktop host wires concrete
// cpal / SttProvider / SQLite / Tauri implementations in `adapters.rs`; a
// wearable host would supply different ones. `MeetingStore` and `Transcriber`
// are *defined* here (MEL-75) but the real desktop implementations land in
// MEL-76 — MEL-75 ships only in-memory stubs (see `stub` below).

/// Pulls raw PCM from the capture backend without blocking.
pub trait AudioSource: Send {
    /// Return the next available PCM chunk, or `None` if none is ready yet.
    fn try_recv(&mut self) -> Option<PcmChunk>;
}

/// Turns one segment's PCM into text. The desktop adapter wraps the existing
/// `SttProvider` (connect → send whole segment → disconnect) behind this.
#[async_trait]
pub trait Transcriber: Send + Sync {
    async fn transcribe_segment(&self, pcm: &[u8], sample_rate: u32) -> Result<String, String>;
    /// Streaming providers transcribe each segment as soon as it is cut
    /// (design §3B); file-based providers wait until finalize (design §3A).
    fn is_streaming(&self) -> bool;
}

/// Persists session + segment rows. Defined here; SQLite impl is MEL-76.
pub trait MeetingStore: Send + Sync {
    fn create_session(&self, meta: SessionMeta) -> Result<i64, String>;
    fn upsert_segment(&self, session_id: i64, seg: SegmentRow) -> Result<(), String>;
    fn finalize_session(
        &self,
        session_id: i64,
        full_text: &str,
        duration_ms: u64,
    ) -> Result<(), String>;
}

/// Emits engine events to the host (Tauri `emit`, a log, a BLE notify, …).
pub trait MeetingEvents: Send + Sync {
    fn emit(&self, ev: MeetingEvent);
}

// ─── Pure segmentation timing logic (the deterministic core) ───

/// Bytes captured per second of audio at the engine's PCM format.
pub fn bytes_per_sec(sample_rate: u32) -> u64 {
    sample_rate as u64 * PCM_BYTES_PER_SAMPLE as u64
}

/// Convert a byte count of PCM into elapsed milliseconds.
pub fn pcm_bytes_to_ms(bytes: u64, sample_rate: u32) -> u64 {
    let bps = bytes_per_sec(sample_rate);
    if bps == 0 {
        return 0;
    }
    bytes * 1000 / bps
}

/// Decide whether the in-progress segment should be cut now.
///
/// A cut happens once the current segment has accumulated at least
/// `segment_interval_secs` of audio. `min_segment_secs` guards the *opposite*
/// direction (manual stop / finalize of a tiny tail) and is enforced by
/// [`should_emit_on_finalize`], so a regular interval cut only checks the
/// upper bound here.
pub fn should_cut_segment(current_segment_ms: u64, config: &MeetingConfig) -> bool {
    current_segment_ms >= config.segment_interval_secs as u64 * 1000
}

/// Decide whether the meeting has hit its hard duration cap and must finalize.
pub fn should_auto_finalize(total_elapsed_ms: u64, config: &MeetingConfig) -> bool {
    total_elapsed_ms >= config.max_meeting_secs as u64 * 1000
}

/// On manual stop / auto-finalize, decide whether the trailing buffer is long
/// enough to become its own segment. A tail shorter than `min_segment_secs`
/// is still emitted **if it is the only segment** (otherwise the meeting would
/// have zero segments); when prior segments exist a sub-minimum tail is merged
/// away by the caller (dropped here, its audio already flushed with the last
/// cut is not — see engine: tail always emitted when it is segment 0).
pub fn should_emit_on_finalize(
    tail_ms: u64,
    prior_segment_count: u32,
    config: &MeetingConfig,
) -> bool {
    if prior_segment_count == 0 {
        // First and only segment: always emit, even if short.
        return tail_ms > 0;
    }
    tail_ms >= config.min_segment_secs as u64 * 1000
}

// ─── In-memory stub implementations (MEL-75 self-test scaffolding) ───
//
// Per MEL-74 design §6, MEL-75 defines the trait boundaries and ships
// **stub** implementations so the engine can be exercised end-to-end before
// the real SQLite store (MEL-76) and SttProvider adapter (MEL-76) exist.
// These also back the unit tests below.
pub mod stub {
    use super::*;
    use std::sync::Mutex;

    /// Feeds a fixed queue of PCM chunks, then returns `None`.
    pub struct VecAudioSource {
        chunks: std::collections::VecDeque<PcmChunk>,
    }

    impl VecAudioSource {
        pub fn new(chunks: Vec<PcmChunk>) -> Self {
            Self {
                chunks: chunks.into(),
            }
        }
    }

    impl AudioSource for VecAudioSource {
        fn try_recv(&mut self) -> Option<PcmChunk> {
            self.chunks.pop_front()
        }
    }

    /// Echoes a deterministic transcript per segment; records every call.
    pub struct StubTranscriber {
        pub streaming: bool,
        pub calls: Mutex<Vec<(usize, u32)>>,
    }

    impl StubTranscriber {
        pub fn new(streaming: bool) -> Self {
            Self {
                streaming,
                calls: Mutex::new(Vec::new()),
            }
        }
    }

    #[async_trait]
    impl Transcriber for StubTranscriber {
        async fn transcribe_segment(
            &self,
            pcm: &[u8],
            sample_rate: u32,
        ) -> Result<String, String> {
            self.calls
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push((pcm.len(), sample_rate));
            Ok(format!("[seg {} bytes]", pcm.len()))
        }

        fn is_streaming(&self) -> bool {
            self.streaming
        }
    }

    /// Records sessions / segments in memory for assertions.
    #[derive(Default)]
    pub struct StubStore {
        pub sessions: Mutex<Vec<SessionMeta>>,
        pub segments: Mutex<Vec<(i64, SegmentRow)>>,
        pub finalized: Mutex<Vec<(i64, String, u64)>>,
    }

    impl MeetingStore for StubStore {
        fn create_session(&self, meta: SessionMeta) -> Result<i64, String> {
            let mut s = self.sessions.lock().unwrap_or_else(|e| e.into_inner());
            s.push(meta);
            Ok(s.len() as i64)
        }

        fn upsert_segment(&self, session_id: i64, seg: SegmentRow) -> Result<(), String> {
            self.segments
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push((session_id, seg));
            Ok(())
        }

        fn finalize_session(
            &self,
            session_id: i64,
            full_text: &str,
            duration_ms: u64,
        ) -> Result<(), String> {
            self.finalized
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push((session_id, full_text.to_string(), duration_ms));
            Ok(())
        }
    }

    /// Collects emitted events for assertions.
    #[derive(Default)]
    pub struct StubEvents {
        pub events: Mutex<Vec<MeetingEvent>>,
    }

    impl MeetingEvents for StubEvents {
        fn emit(&self, ev: MeetingEvent) {
            self.events
                .lock()
                .unwrap_or_else(|e| e.into_inner())
                .push(ev);
        }
    }
}

// ─── The engine: drives the state machine over the trait boundaries ───

/// Owns the meeting state machine and segment scheduling. Generic over the
/// four host boundaries so the same logic runs on desktop and (future)
/// wearable hosts. The desktop host owns one of these inside `MeetingHandle`.
pub struct MeetingEngine<A: AudioSource, T: Transcriber, S: MeetingStore, E: MeetingEvents> {
    audio: A,
    transcriber: T,
    store: S,
    events: E,
    config: MeetingConfig,

    state: MeetingState,
    session_id: i64,
    sample_rate: u32,

    /// PCM accumulated for the segment currently being recorded.
    segment_buffer: Vec<u8>,
    /// Total PCM bytes captured across the whole meeting (for elapsed time).
    total_bytes: u64,
    /// Start offset (ms) of the in-progress segment.
    current_segment_start_ms: u64,
    /// Index of the next segment to be cut.
    next_seg_index: u32,
    /// Finished segments awaiting transcription (file-based / deferred mode).
    pending: Vec<Segment>,
    /// Transcribed text per segment index, in order, for the merged full text.
    transcribed: Vec<(u32, String)>,
}

impl<A: AudioSource, T: Transcriber, S: MeetingStore, E: MeetingEvents> MeetingEngine<A, T, S, E> {
    pub fn new(
        audio: A,
        transcriber: T,
        store: S,
        events: E,
        config: MeetingConfig,
        sample_rate: u32,
    ) -> Self {
        Self {
            audio,
            transcriber,
            store,
            events,
            config,
            state: MeetingState::Idle,
            session_id: 0,
            sample_rate,
            segment_buffer: Vec::new(),
            total_bytes: 0,
            current_segment_start_ms: 0,
            next_seg_index: 0,
            pending: Vec::new(),
            transcribed: Vec::new(),
        }
    }

    pub fn state(&self) -> MeetingState {
        self.state
    }

    pub fn is_active(&self) -> bool {
        matches!(self.state, MeetingState::Recording | MeetingState::Finalizing)
    }

    fn set_state(&mut self, s: MeetingState) {
        self.state = s;
        self.events.emit(MeetingEvent::State(s));
    }

    /// Total audio captured so far, in milliseconds.
    pub fn elapsed_ms(&self) -> u64 {
        pcm_bytes_to_ms(self.total_bytes, self.sample_rate)
    }

    /// Begin a meeting: create the session row and enter `Recording`.
    pub fn start(&mut self, meta: SessionMeta) -> Result<(), String> {
        if self.state != MeetingState::Idle {
            return Err("meeting already in progress".to_string());
        }
        self.session_id = self.store.create_session(meta)?;
        self.set_state(MeetingState::Recording);
        Ok(())
    }

    /// Pull all currently-available audio, append to the segment buffer, and
    /// cut / finalize as the timing thresholds dictate. Call this on a timer.
    /// Returns `true` if the meeting auto-finalized during this tick.
    pub async fn tick(&mut self) -> Result<bool, String> {
        if self.state != MeetingState::Recording {
            return Ok(false);
        }

        while let Some(chunk) = self.audio.try_recv() {
            self.total_bytes += chunk.bytes.len() as u64;
            self.segment_buffer.extend_from_slice(&chunk.bytes);
        }

        // Hard cap → finalize the whole meeting.
        if should_auto_finalize(self.elapsed_ms(), &self.config) {
            self.finalize().await?;
            return Ok(true);
        }

        // Interval reached → cut one segment per interval's worth of audio,
        // looping in case a slow tick accumulated several intervals at once.
        loop {
            let seg_bytes = self.segment_buffer.len() as u64;
            let seg_ms = pcm_bytes_to_ms(seg_bytes, self.sample_rate);
            if !should_cut_segment(seg_ms, &self.config) {
                break;
            }
            self.cut_segment().await?;
        }

        Ok(false)
    }

    /// Close the in-progress segment, hand it downstream, and start a fresh
    /// buffer. Audio capture is never paused, so no samples are dropped at the
    /// boundary (design §2.2).
    ///
    /// Peels off exactly one `segment_interval_secs` worth of PCM and retains
    /// the remainder for the next segment, so segmentation stays deterministic
    /// regardless of how much audio a single tick happened to drain.
    async fn cut_segment(&mut self) -> Result<(), String> {
        let interval_bytes =
            (bytes_per_sec(self.sample_rate) * self.config.segment_interval_secs as u64) as usize;
        let take = interval_bytes.min(self.segment_buffer.len());
        let pcm: Vec<u8> = self.segment_buffer.drain(..take).collect();
        let seg_ms = pcm_bytes_to_ms(pcm.len() as u64, self.sample_rate);
        let start_ms = self.current_segment_start_ms;
        let end_ms = start_ms + seg_ms;
        let meta = SegmentMeta {
            seg_index: self.next_seg_index,
            start_ms,
            end_ms,
        };
        self.next_seg_index += 1;
        self.current_segment_start_ms = end_ms;
        self.handoff_segment(Segment { meta, pcm }).await
    }

    /// Route a closed segment per provider type (design §3C):
    /// streaming → transcribe now; file-based → queue for finalize.
    async fn handoff_segment(&mut self, seg: Segment) -> Result<(), String> {
        let idx = seg.meta.seg_index;
        self.store.upsert_segment(
            self.session_id,
            SegmentRow {
                seg_index: idx,
                start_ms: seg.meta.start_ms,
                end_ms: seg.meta.end_ms,
                status: SegStatus::Pending,
                text: String::new(),
            },
        )?;

        if self.transcriber.is_streaming() {
            self.transcribe_now(seg).await?;
        } else {
            self.events.emit(MeetingEvent::Segment {
                idx,
                status: SegStatus::Pending,
                text: String::new(),
            });
            self.pending.push(seg);
        }
        Ok(())
    }

    /// Transcribe a single segment immediately and persist the result.
    async fn transcribe_now(&mut self, seg: Segment) -> Result<(), String> {
        let idx = seg.meta.seg_index;
        self.events.emit(MeetingEvent::Segment {
            idx,
            status: SegStatus::Transcribing,
            text: String::new(),
        });
        match self
            .transcriber
            .transcribe_segment(&seg.pcm, self.sample_rate)
            .await
        {
            Ok(text) => {
                self.store.upsert_segment(
                    self.session_id,
                    SegmentRow {
                        seg_index: idx,
                        start_ms: seg.meta.start_ms,
                        end_ms: seg.meta.end_ms,
                        status: SegStatus::Done,
                        text: text.clone(),
                    },
                )?;
                self.transcribed.push((idx, text.clone()));
                self.events.emit(MeetingEvent::Segment {
                    idx,
                    status: SegStatus::Done,
                    text,
                });
                Ok(())
            }
            Err(e) => {
                self.store.upsert_segment(
                    self.session_id,
                    SegmentRow {
                        seg_index: idx,
                        start_ms: seg.meta.start_ms,
                        end_ms: seg.meta.end_ms,
                        status: SegStatus::Failed,
                        text: String::new(),
                    },
                )?;
                self.events.emit(MeetingEvent::Segment {
                    idx,
                    status: SegStatus::Failed,
                    text: String::new(),
                });
                self.events.emit(MeetingEvent::Error(e.clone()));
                Err(e)
            }
        }
    }

    /// Finalize: flush the trailing buffer, transcribe everything still
    /// pending, merge the full text, and archive. Works for both a manual stop
    /// and the auto cap. Idempotent-ish: a no-op if not recording.
    pub async fn finalize(&mut self) -> Result<(), String> {
        if self.state != MeetingState::Recording {
            return Ok(());
        }
        self.set_state(MeetingState::Finalizing);

        // Drain any audio that arrived since the last tick.
        while let Some(chunk) = self.audio.try_recv() {
            self.total_bytes += chunk.bytes.len() as u64;
            self.segment_buffer.extend_from_slice(&chunk.bytes);
        }

        // Emit the trailing buffer as a final segment when it qualifies.
        let tail = std::mem::take(&mut self.segment_buffer);
        let tail_ms = pcm_bytes_to_ms(tail.len() as u64, self.sample_rate);
        if should_emit_on_finalize(tail_ms, self.next_seg_index, &self.config) {
            let start_ms = self.current_segment_start_ms;
            let meta = SegmentMeta {
                seg_index: self.next_seg_index,
                start_ms,
                end_ms: start_ms + tail_ms,
            };
            self.next_seg_index += 1;
            self.current_segment_start_ms = meta.end_ms;
            self.handoff_segment(Segment { meta, pcm: tail }).await?;
        }

        // File-based mode: everything is still queued — transcribe it now.
        let pending = std::mem::take(&mut self.pending);
        let total = pending.len() as u32;
        let mut done = 0u32;
        for seg in pending {
            self.transcribe_now(seg).await?;
            done += 1;
            self.events.emit(MeetingEvent::Progress { done, total });
        }

        // Merge transcripts in segment order into the session full text.
        self.transcribed.sort_by_key(|(idx, _)| *idx);
        let full_text = self
            .transcribed
            .iter()
            .map(|(_, t)| t.as_str())
            .collect::<Vec<_>>()
            .join("\n\n");

        let duration_ms = self.elapsed_ms();
        self.store
            .finalize_session(self.session_id, &full_text, duration_ms)?;
        self.set_state(MeetingState::Archived);
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::stub::*;
    use super::*;

    fn cfg() -> MeetingConfig {
        // 5 s segments, 12 s cap, 2 s minimum — scaled down for fast tests
        // while exercising the exact same arithmetic as the production knobs.
        MeetingConfig {
            segment_interval_secs: 5,
            max_meeting_secs: 12,
            min_segment_secs: 2,
        }
    }

    fn secs_of_pcm(secs: u32) -> Vec<u8> {
        vec![0u8; (bytes_per_sec(PCM_SAMPLE_RATE) as usize) * secs as usize]
    }

    fn chunk(secs: u32) -> PcmChunk {
        PcmChunk {
            bytes: secs_of_pcm(secs),
            sample_rate: PCM_SAMPLE_RATE,
        }
    }

    fn meta() -> SessionMeta {
        SessionMeta {
            created_at: "2026-06-24T00:00:00".to_string(),
            stt_provider: "glm-asr".to_string(),
            language: None,
        }
    }

    // ── Pure timing-function tests ──

    #[test]
    fn bytes_to_ms_round_trips_one_second() {
        let one_sec = bytes_per_sec(PCM_SAMPLE_RATE);
        assert_eq!(pcm_bytes_to_ms(one_sec, PCM_SAMPLE_RATE), 1000);
    }

    #[test]
    fn cut_triggers_exactly_at_interval_boundary() {
        let c = cfg(); // 5 s
        assert!(!should_cut_segment(4_999, &c));
        assert!(should_cut_segment(5_000, &c));
        assert!(should_cut_segment(5_001, &c));
    }

    #[test]
    fn auto_finalize_triggers_at_cap() {
        let c = cfg(); // 12 s
        assert!(!should_auto_finalize(11_999, &c));
        assert!(should_auto_finalize(12_000, &c));
    }

    #[test]
    fn production_defaults_match_design() {
        let c = MeetingConfig::default();
        assert_eq!(c.segment_interval_secs, 300); // 5 min
        assert_eq!(c.max_meeting_secs, 10800); // 3 hr
        assert_eq!(c.min_segment_secs, 15);
        assert!(should_cut_segment(300_000, &c));
        assert!(should_auto_finalize(10_800_000, &c));
    }

    #[test]
    fn finalize_emits_short_first_segment_but_drops_short_tail() {
        let c = cfg(); // min 2 s
        // Only segment, short → still emit.
        assert!(should_emit_on_finalize(1_000, 0, &c));
        // Short tail after prior segments → drop.
        assert!(!should_emit_on_finalize(1_000, 3, &c));
        // Long tail after prior segments → emit.
        assert!(should_emit_on_finalize(2_000, 3, &c));
    }

    // ── Engine integration tests over the stubs ──

    #[tokio::test]
    async fn manual_stop_finalizes_in_flight_segment() {
        // 3 s of audio, well under the 5 s cut → one tail segment on finalize.
        let audio = VecAudioSource::new(vec![chunk(3)]);
        let store = StubStore::default();
        let events = StubEvents::default();
        let mut engine = MeetingEngine::new(
            audio,
            StubTranscriber::new(false),
            store,
            events,
            cfg(),
            PCM_SAMPLE_RATE,
        );

        engine.start(meta()).unwrap();
        assert_eq!(engine.state(), MeetingState::Recording);
        engine.tick().await.unwrap(); // pulls audio, no cut yet
        engine.finalize().await.unwrap(); // manual stop

        assert_eq!(engine.state(), MeetingState::Archived);
        // Exactly one segment got transcribed and stored as done.
        let segs = engine.store.segments.lock().unwrap();
        assert!(segs.iter().any(|(_, s)| s.status == SegStatus::Done));
        let finalized = engine.store.finalized.lock().unwrap();
        assert_eq!(finalized.len(), 1);
        assert!(!finalized[0].1.is_empty());
    }

    #[tokio::test]
    async fn file_based_provider_cuts_at_interval_and_defers_transcription() {
        // 11 s total → two 5 s cuts (the cut loop peels one interval at a time)
        // + 1 s tail dropped on finalize (tail < 2 s min, prior segments exist).
        let audio = VecAudioSource::new(vec![chunk(5), chunk(5), chunk(1)]);
        let transcriber = StubTranscriber::new(false); // file-based
        let mut engine = MeetingEngine::new(
            audio,
            transcriber,
            StubStore::default(),
            StubEvents::default(),
            cfg(),
            PCM_SAMPLE_RATE,
        );

        engine.start(meta()).unwrap();
        // A single tick drains all queued audio (11 s) and the cut loop peels
        // two 5 s segments, leaving a 1 s remainder in the buffer.
        engine.tick().await.unwrap();

        // File-based: nothing transcribed until finalize, even though segments
        // were already cut and queued.
        assert_eq!(engine.transcriber.calls.lock().unwrap().len(), 0);

        engine.finalize().await.unwrap();
        // Two full segments transcribed at finalize; 1 s tail dropped.
        assert_eq!(engine.transcriber.calls.lock().unwrap().len(), 2);
        assert_eq!(engine.state(), MeetingState::Archived);
    }

    #[tokio::test]
    async fn streaming_provider_transcribes_each_segment_immediately() {
        let audio = VecAudioSource::new(vec![chunk(5)]);
        let transcriber = StubTranscriber::new(true); // streaming
        let mut engine = MeetingEngine::new(
            audio,
            transcriber,
            StubStore::default(),
            StubEvents::default(),
            cfg(),
            PCM_SAMPLE_RATE,
        );

        engine.start(meta()).unwrap();
        engine.tick().await.unwrap(); // 5 s → immediate cut + transcribe

        // Streaming: the segment was transcribed during recording.
        assert_eq!(engine.transcriber.calls.lock().unwrap().len(), 1);

        engine.finalize().await.unwrap();
        assert_eq!(engine.state(), MeetingState::Archived);
    }

    #[tokio::test]
    async fn auto_finalize_at_cap_without_manual_stop() {
        // 12 s in one chunk → first tick cuts one 5 s segment? No: one tick
        // pulls all 12 s, elapsed 12 s ≥ cap → auto-finalize fires first.
        let audio = VecAudioSource::new(vec![chunk(12)]);
        let mut engine = MeetingEngine::new(
            audio,
            StubTranscriber::new(false),
            StubStore::default(),
            StubEvents::default(),
            cfg(),
            PCM_SAMPLE_RATE,
        );

        engine.start(meta()).unwrap();
        let auto = engine.tick().await.unwrap();
        assert!(auto, "tick should report auto-finalize at the cap");
        assert_eq!(engine.state(), MeetingState::Archived);
        assert_eq!(engine.store.finalized.lock().unwrap().len(), 1);
    }

    #[tokio::test]
    async fn full_text_merges_segments_in_order() {
        let audio = VecAudioSource::new(vec![chunk(5), chunk(5)]);
        let mut engine = MeetingEngine::new(
            audio,
            StubTranscriber::new(true), // streaming so both get text
            StubStore::default(),
            StubEvents::default(),
            cfg(),
            PCM_SAMPLE_RATE,
        );

        engine.start(meta()).unwrap();
        engine.tick().await.unwrap(); // drains 10 s → cut loop peels two 5 s segments
        engine.finalize().await.unwrap();

        let finalized = engine.store.finalized.lock().unwrap();
        let full = &finalized[0].1;
        // Two segments joined by a blank line.
        assert!(full.contains("\n\n"));
        assert_eq!(full.matches("[seg").count(), 2);
    }
}
