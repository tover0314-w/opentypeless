//! SQLite persistence for the meeting / long-recording mode (MEL-76).
//!
//! Two new tables (`meeting_session` + `meeting_segment`) per the MEL-74
//! storage model. The existing instant-dictation `history` table is left
//! completely untouched — meeting transcripts never mix into it.
//!
//! `MeetingDbStore` is the SQLite-backed implementation of the engine's
//! `meeting::engine::MeetingStore` trait (the write path used while a meeting
//! records). The read path (list sessions / read one full session / export)
//! lives on the same struct as plain async methods consumed by the Tauri
//! query commands and the frontend (MEL-77).

use anyhow::Result;
use rusqlite::Connection;
use serde::{Deserialize, Serialize};
use std::path::PathBuf;
use std::sync::{Arc, Mutex};

use crate::meeting::engine::{MeetingStore, SegStatus, SegmentRow, SessionMeta};

/// A meeting session row (header), without its segments.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeetingSession {
    pub id: i64,
    pub created_at: String,
    pub stt_provider: String,
    pub language: Option<String>,
    /// Merged transcript of all segments; empty until the meeting is finalized.
    pub full_text: String,
    pub duration_ms: i64,
    /// "recording" while in progress, "archived" once finalized.
    pub status: String,
}

/// A single transcribed segment row.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeetingSegment {
    pub seg_index: i64,
    pub start_ms: i64,
    pub end_ms: i64,
    /// "pending" | "transcribing" | "done" | "failed".
    pub status: String,
    pub text: String,
}

/// A full meeting: header + ordered segments. The "read one meeting in full"
/// shape returned to the UI and used for export.
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct MeetingDetail {
    #[serde(flatten)]
    pub session: MeetingSession,
    pub segments: Vec<MeetingSegment>,
}

fn seg_status_str(status: SegStatus) -> &'static str {
    match status {
        SegStatus::Pending => "pending",
        SegStatus::Transcribing => "transcribing",
        SegStatus::Done => "done",
        SegStatus::Failed => "failed",
    }
}

/// SQLite store for meeting sessions and segments.
///
/// Cloneable handle (the engine holds one clone via the trait, the Tauri
/// query commands hold another via managed state) sharing a single connection.
#[derive(Clone)]
pub struct MeetingDbStore {
    conn: Arc<Mutex<Connection>>,
}

impl MeetingDbStore {
    pub fn new(db_path: PathBuf) -> Result<Self> {
        let conn = Connection::open(&db_path)?;
        conn.execute_batch("PRAGMA journal_mode=WAL;")?;
        conn.execute_batch(
            "CREATE TABLE IF NOT EXISTS meeting_session (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL,
                stt_provider TEXT NOT NULL DEFAULT '',
                language TEXT,
                full_text TEXT NOT NULL DEFAULT '',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'recording'
            );
            CREATE TABLE IF NOT EXISTS meeting_segment (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                seg_index INTEGER NOT NULL,
                start_ms INTEGER NOT NULL DEFAULT 0,
                end_ms INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'pending',
                text TEXT NOT NULL DEFAULT '',
                UNIQUE(session_id, seg_index)
            );
            CREATE INDEX IF NOT EXISTS idx_meeting_segment_session
                ON meeting_segment(session_id);",
        )?;
        Ok(Self {
            conn: Arc::new(Mutex::new(conn)),
        })
    }

    // ─── Read path (query commands / export) ───

    /// List meeting sessions, newest first. Segments are not loaded here.
    pub async fn list_sessions(&self, limit: u32, offset: u32) -> Result<Vec<MeetingSession>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        let mut stmt = conn.prepare(
            "SELECT id, created_at, stt_provider, language, full_text, duration_ms, status
             FROM meeting_session ORDER BY id DESC LIMIT ?1 OFFSET ?2",
        )?;
        let rows = stmt.query_map(rusqlite::params![limit, offset], row_to_session)?;
        let mut out = Vec::new();
        for r in rows {
            out.push(r?);
        }
        Ok(out)
    }

    /// Read one meeting in full (header + ordered segments). `None` if the id
    /// does not exist.
    pub async fn get_session(&self, session_id: i64) -> Result<Option<MeetingDetail>> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());

        let session = conn
            .query_row(
                "SELECT id, created_at, stt_provider, language, full_text, duration_ms, status
                 FROM meeting_session WHERE id = ?1",
                rusqlite::params![session_id],
                row_to_session,
            )
            .ok();

        let session = match session {
            Some(s) => s,
            None => return Ok(None),
        };

        let mut stmt = conn.prepare(
            "SELECT seg_index, start_ms, end_ms, status, text
             FROM meeting_segment WHERE session_id = ?1 ORDER BY seg_index ASC",
        )?;
        let rows = stmt.query_map(rusqlite::params![session_id], |row| {
            Ok(MeetingSegment {
                seg_index: row.get(0)?,
                start_ms: row.get(1)?,
                end_ms: row.get(2)?,
                status: row.get(3)?,
                text: row.get(4)?,
            })
        })?;
        let mut segments = Vec::new();
        for r in rows {
            segments.push(r?);
        }

        Ok(Some(MeetingDetail { session, segments }))
    }

    /// Delete a meeting session and its segments.
    pub async fn delete_session(&self, session_id: i64) -> Result<()> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "DELETE FROM meeting_segment WHERE session_id = ?1",
            rusqlite::params![session_id],
        )?;
        conn.execute(
            "DELETE FROM meeting_session WHERE id = ?1",
            rusqlite::params![session_id],
        )?;
        Ok(())
    }
}

fn row_to_session(row: &rusqlite::Row) -> rusqlite::Result<MeetingSession> {
    Ok(MeetingSession {
        id: row.get(0)?,
        created_at: row.get(1)?,
        stt_provider: row.get(2)?,
        language: row.get(3)?,
        full_text: row.get(4)?,
        duration_ms: row.get(5)?,
        status: row.get(6)?,
    })
}

// ─── Write path: engine MeetingStore trait impl (synchronous) ───

impl MeetingStore for MeetingDbStore {
    fn create_session(&self, meta: SessionMeta) -> Result<i64, String> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "INSERT INTO meeting_session (created_at, stt_provider, language, full_text, duration_ms, status)
             VALUES (?1, ?2, ?3, '', 0, 'recording')",
            rusqlite::params![meta.created_at, meta.stt_provider, meta.language],
        )
        .map_err(|e| e.to_string())?;
        Ok(conn.last_insert_rowid())
    }

    fn upsert_segment(&self, session_id: i64, seg: SegmentRow) -> Result<(), String> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "INSERT INTO meeting_segment (session_id, seg_index, start_ms, end_ms, status, text)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6)
             ON CONFLICT(session_id, seg_index) DO UPDATE SET
                start_ms = excluded.start_ms,
                end_ms = excluded.end_ms,
                status = excluded.status,
                text = excluded.text",
            rusqlite::params![
                session_id,
                seg.seg_index,
                seg.start_ms,
                seg.end_ms,
                seg_status_str(seg.status),
                seg.text,
            ],
        )
        .map_err(|e| e.to_string())?;
        Ok(())
    }

    fn finalize_session(
        &self,
        session_id: i64,
        full_text: &str,
        duration_ms: u64,
    ) -> Result<(), String> {
        let conn = self.conn.lock().unwrap_or_else(|e| e.into_inner());
        conn.execute(
            "UPDATE meeting_session
             SET full_text = ?2, duration_ms = ?3, status = 'archived'
             WHERE id = ?1",
            rusqlite::params![session_id, full_text, duration_ms as i64],
        )
        .map_err(|e| e.to_string())?;
        Ok(())
    }
}

/// Render a meeting's transcript as a Markdown document for export.
pub fn render_markdown(detail: &MeetingDetail) -> String {
    let s = &detail.session;
    let mut out = String::new();
    out.push_str(&format!("# Meeting {}\n\n", s.id));
    out.push_str(&format!("- Date: {}\n", s.created_at));
    out.push_str(&format!("- Provider: {}\n", s.stt_provider));
    if let Some(lang) = &s.language {
        out.push_str(&format!("- Language: {}\n", lang));
    }
    out.push_str(&format!(
        "- Duration: {}\n\n",
        format_duration(s.duration_ms),
    ));
    out.push_str("---\n\n");

    if !s.full_text.trim().is_empty() {
        out.push_str(&s.full_text);
        out.push('\n');
    } else {
        // Fall back to per-segment text if the merged field is empty.
        for seg in &detail.segments {
            if !seg.text.trim().is_empty() {
                out.push_str(&seg.text);
                out.push_str("\n\n");
            }
        }
    }
    out
}

/// Render a meeting's transcript as plain text (no headers, just the body).
pub fn render_plain_text(detail: &MeetingDetail) -> String {
    let s = &detail.session;
    if !s.full_text.trim().is_empty() {
        return s.full_text.clone();
    }
    detail
        .segments
        .iter()
        .map(|seg| seg.text.as_str())
        .filter(|t| !t.trim().is_empty())
        .collect::<Vec<_>>()
        .join("\n\n")
}

fn format_duration(ms: i64) -> String {
    let total_secs = (ms / 1000).max(0);
    let h = total_secs / 3600;
    let m = (total_secs % 3600) / 60;
    let sec = total_secs % 60;
    if h > 0 {
        format!("{h}h {m}m {sec}s")
    } else {
        format!("{m}m {sec}s")
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::meeting::engine::{SegStatus, SegmentRow, SessionMeta};

    fn store() -> MeetingDbStore {
        // In-memory DB shared across the single connection — fine for tests.
        let conn = Connection::open_in_memory().unwrap();
        conn.execute_batch(
            "CREATE TABLE meeting_session (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                created_at TEXT NOT NULL,
                stt_provider TEXT NOT NULL DEFAULT '',
                language TEXT,
                full_text TEXT NOT NULL DEFAULT '',
                duration_ms INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'recording'
            );
            CREATE TABLE meeting_segment (
                id INTEGER PRIMARY KEY AUTOINCREMENT,
                session_id INTEGER NOT NULL,
                seg_index INTEGER NOT NULL,
                start_ms INTEGER NOT NULL DEFAULT 0,
                end_ms INTEGER NOT NULL DEFAULT 0,
                status TEXT NOT NULL DEFAULT 'pending',
                text TEXT NOT NULL DEFAULT '',
                UNIQUE(session_id, seg_index)
            );",
        )
        .unwrap();
        MeetingDbStore {
            conn: Arc::new(Mutex::new(conn)),
        }
    }

    fn meta() -> SessionMeta {
        SessionMeta {
            created_at: "2026-06-24T10:00:00".to_string(),
            stt_provider: "glm-asr".to_string(),
            language: Some("zh".to_string()),
        }
    }

    #[tokio::test]
    async fn create_upsert_finalize_round_trips_full_meeting() {
        let store = store();
        let id = store.create_session(meta()).unwrap();
        assert_eq!(id, 1);

        // Two segments persisted as pending, then upserted to done.
        store
            .upsert_segment(
                id,
                SegmentRow {
                    seg_index: 0,
                    start_ms: 0,
                    end_ms: 5000,
                    status: SegStatus::Pending,
                    text: String::new(),
                },
            )
            .unwrap();
        store
            .upsert_segment(
                id,
                SegmentRow {
                    seg_index: 0,
                    start_ms: 0,
                    end_ms: 5000,
                    status: SegStatus::Done,
                    text: "hello".to_string(),
                },
            )
            .unwrap();
        store
            .upsert_segment(
                id,
                SegmentRow {
                    seg_index: 1,
                    start_ms: 5000,
                    end_ms: 9000,
                    status: SegStatus::Done,
                    text: "world".to_string(),
                },
            )
            .unwrap();

        store.finalize_session(id, "hello\n\nworld", 9000).unwrap();

        // Read it back in full.
        let detail = store.get_session(id).await.unwrap().unwrap();
        assert_eq!(detail.session.status, "archived");
        assert_eq!(detail.session.full_text, "hello\n\nworld");
        assert_eq!(detail.session.duration_ms, 9000);
        // Upsert collapsed seg 0 to a single row (not duplicated).
        assert_eq!(detail.segments.len(), 2);
        assert_eq!(detail.segments[0].text, "hello");
        assert_eq!(detail.segments[1].text, "world");
    }

    #[tokio::test]
    async fn list_sessions_newest_first() {
        let store = store();
        let a = store.create_session(meta()).unwrap();
        let b = store.create_session(meta()).unwrap();
        let list = store.list_sessions(10, 0).await.unwrap();
        assert_eq!(list.len(), 2);
        assert_eq!(list[0].id, b);
        assert_eq!(list[1].id, a);
    }

    #[tokio::test]
    async fn get_missing_session_is_none() {
        let store = store();
        assert!(store.get_session(999).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn delete_removes_session_and_segments() {
        let store = store();
        let id = store.create_session(meta()).unwrap();
        store
            .upsert_segment(
                id,
                SegmentRow {
                    seg_index: 0,
                    start_ms: 0,
                    end_ms: 1000,
                    status: SegStatus::Done,
                    text: "x".to_string(),
                },
            )
            .unwrap();
        store.delete_session(id).await.unwrap();
        assert!(store.get_session(id).await.unwrap().is_none());
    }

    #[tokio::test]
    async fn export_renders_markdown_and_plain_text() {
        let store = store();
        let id = store.create_session(meta()).unwrap();
        store.finalize_session(id, "line one\n\nline two", 65000).unwrap();
        let detail = store.get_session(id).await.unwrap().unwrap();

        let md = render_markdown(&detail);
        assert!(md.contains("# Meeting 1"));
        assert!(md.contains("glm-asr"));
        assert!(md.contains("1m 5s"));
        assert!(md.contains("line one"));

        let txt = render_plain_text(&detail);
        assert_eq!(txt, "line one\n\nline two");
    }

    #[test]
    fn duration_formats_hours_and_minutes() {
        assert_eq!(format_duration(0), "0m 0s");
        assert_eq!(format_duration(65_000), "1m 5s");
        assert_eq!(format_duration(3_661_000), "1h 1m 1s");
    }
}

/// End-to-end acceptance: drive the real `MeetingEngine` over the real
/// `MeetingDbStore` (the exact wiring `MeetingHandle` uses) and prove each
/// MEL-76 acceptance box. Uses a fake transcriber so no cloud STT credential
/// is needed — the transcription *logic* (both timings) is exercised in full.
#[cfg(test)]
mod e2e {
    use super::*;
    use crate::meeting::engine::{
        bytes_per_sec, MeetingConfig, MeetingEngine, MeetingEvents, PcmChunk, SessionMeta,
        Transcriber, PCM_SAMPLE_RATE,
    };
    use async_trait::async_trait;

    /// Deterministic transcriber: returns a per-segment label, no network.
    struct FakeTranscriber {
        streaming: bool,
    }

    #[async_trait]
    impl Transcriber for FakeTranscriber {
        async fn transcribe_segment(&self, pcm: &[u8], _sr: u32) -> Result<String, String> {
            Ok(format!("seg-{}b", pcm.len()))
        }
        fn is_streaming(&self) -> bool {
            self.streaming
        }
    }

    struct NullEvents;
    impl MeetingEvents for NullEvents {
        fn emit(&self, _ev: crate::meeting::engine::MeetingEvent) {}
    }

    /// Audio source that yields a fixed queue of chunks, then None.
    struct VecAudio(std::collections::VecDeque<PcmChunk>);
    impl crate::meeting::engine::AudioSource for VecAudio {
        fn try_recv(&mut self) -> Option<PcmChunk> {
            self.0.pop_front()
        }
    }

    /// A db path in the OS temp dir, unique per test, removed on drop.
    struct TempDb {
        path: PathBuf,
    }
    impl TempDb {
        fn new(tag: &str) -> Self {
            let nanos = std::time::SystemTime::now()
                .duration_since(std::time::UNIX_EPOCH)
                .unwrap()
                .as_nanos();
            let path = std::env::temp_dir().join(format!("mel76-{tag}-{nanos}.db"));
            Self { path }
        }
    }
    impl Drop for TempDb {
        fn drop(&mut self) {
            // Best-effort cleanup of the db file and its WAL/SHM siblings.
            for suffix in ["", "-wal", "-shm"] {
                let _ = std::fs::remove_file(format!("{}{}", self.path.display(), suffix));
            }
        }
    }

    fn file_store(tag: &str) -> (MeetingDbStore, TempDb) {
        let db = TempDb::new(tag);
        // Init the instant-history table on the SAME db file, so we can prove
        // meeting writes never touch it.
        let history = crate::storage::HistoryStore::new(db.path.clone()).unwrap();
        drop(history);
        let store = MeetingDbStore::new(db.path.clone()).unwrap();
        (store, db)
    }

    fn chunk(secs: u32) -> PcmChunk {
        PcmChunk {
            bytes: vec![0u8; bytes_per_sec(PCM_SAMPLE_RATE) as usize * secs as usize],
            sample_rate: PCM_SAMPLE_RATE,
        }
    }

    fn cfg() -> MeetingConfig {
        // 5 s segments / 60 s cap / 2 s min — same arithmetic, fast.
        MeetingConfig {
            segment_interval_secs: 5,
            max_meeting_secs: 60,
            min_segment_secs: 2,
        }
    }

    fn meta() -> SessionMeta {
        SessionMeta {
            created_at: "2026-06-24T12:00:00".to_string(),
            stt_provider: "glm-asr".to_string(),
            language: None,
        }
    }

    // Acceptance: file-based provider → one-shot transcription at meeting end;
    // transcript persists; one meeting reads back complete.
    #[tokio::test]
    async fn file_based_one_shot_persists_and_reads_back_complete() {
        let (store, _dir) = file_store("oneshot");
        let audio = VecAudio(vec![chunk(5), chunk(5), chunk(3)].into());
        let mut engine = MeetingEngine::new(
            audio,
            FakeTranscriber { streaming: false },
            store.clone(),
            NullEvents,
            cfg(),
            PCM_SAMPLE_RATE,
        );

        engine.start(meta()).unwrap();
        engine.tick().await.unwrap(); // drains 13 s → cuts two 5 s segments
        engine.finalize().await.unwrap(); // one-shot transcribe + 3 s tail

        let detail = store.get_session(1).await.unwrap().unwrap();
        assert_eq!(detail.session.status, "archived");
        // Three segments: two 5 s cuts + the 3 s tail (≥ 2 s min).
        assert_eq!(detail.segments.len(), 3);
        assert!(detail.segments.iter().all(|s| s.status == "done"));
        // Full text is the merged transcript and reads back non-empty.
        assert!(!detail.session.full_text.is_empty());
        assert_eq!(detail.session.full_text.matches("seg-").count(), 3);
        assert!(detail.session.duration_ms > 0);
    }

    // Acceptance: streaming provider → each segment transcribed as it is cut
    // (during recording), still persists and reads back complete.
    #[tokio::test]
    async fn streaming_interval_transcribes_each_segment_during_recording() {
        let (store, _dir) = file_store("streaming");
        let audio = VecAudio(vec![chunk(5), chunk(5)].into());
        let mut engine = MeetingEngine::new(
            audio,
            FakeTranscriber { streaming: true },
            store.clone(),
            NullEvents,
            cfg(),
            PCM_SAMPLE_RATE,
        );

        engine.start(meta()).unwrap();
        engine.tick().await.unwrap(); // cuts + transcribes two segments now
        // Already persisted as done before finalize (streaming timing).
        let mid = store.get_session(1).await.unwrap().unwrap();
        assert_eq!(mid.segments.len(), 2);
        assert!(mid.segments.iter().all(|s| s.status == "done"));

        engine.finalize().await.unwrap();
        let detail = store.get_session(1).await.unwrap().unwrap();
        assert_eq!(detail.session.status, "archived");
        assert_eq!(detail.session.full_text.matches("seg-").count(), 2);
    }

    // Acceptance: exporting a whole meeting transcript yields the full body.
    #[tokio::test]
    async fn export_whole_meeting_to_markdown_and_text() {
        let (store, _dir) = file_store("export");
        let audio = VecAudio(vec![chunk(5), chunk(5)].into());
        let mut engine = MeetingEngine::new(
            audio,
            FakeTranscriber { streaming: false },
            store.clone(),
            NullEvents,
            cfg(),
            PCM_SAMPLE_RATE,
        );
        engine.start(meta()).unwrap();
        engine.tick().await.unwrap();
        engine.finalize().await.unwrap();

        let detail = store.get_session(1).await.unwrap().unwrap();
        let md = render_markdown(&detail);
        let txt = render_plain_text(&detail);
        assert!(md.contains("# Meeting 1") && md.contains("seg-"));
        assert!(txt.contains("seg-"));
        // Plain text body equals the merged full text.
        assert_eq!(txt, detail.session.full_text);
    }

    // Acceptance: the existing instant-dictation history table is unaffected by
    // a full meeting lifecycle on the same db file.
    #[tokio::test]
    async fn instant_history_unaffected_by_meeting_writes() {
        let dir = TempDb::new("history-unaffected");
        let db = dir.path.clone();
        let history = crate::storage::HistoryStore::new(db.clone()).unwrap();
        history
            .add(crate::storage::HistoryEntry {
                id: 0,
                created_at: "2026-06-24T09:00:00".to_string(),
                app_name: "Notes".to_string(),
                app_type: "editor".to_string(),
                raw_text: "instant text".to_string(),
                polished_text: "instant text".to_string(),
                language: None,
                duration_ms: Some(1200),
            })
            .await
            .unwrap();

        // Full meeting lifecycle on the same file.
        let store = MeetingDbStore::new(db.clone()).unwrap();
        let audio = VecAudio(vec![chunk(5)].into());
        let mut engine = MeetingEngine::new(
            audio,
            FakeTranscriber { streaming: false },
            store.clone(),
            NullEvents,
            cfg(),
            PCM_SAMPLE_RATE,
        );
        engine.start(meta()).unwrap();
        engine.tick().await.unwrap();
        engine.finalize().await.unwrap();
        let _ = engine.state(); // settle

        // The instant history row is still there, untouched.
        let entries = history.list(10, 0).await.unwrap();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].raw_text, "instant text");
        // And the meeting landed in its own table.
        assert!(store.get_session(1).await.unwrap().is_some());
    }
}
