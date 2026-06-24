//! Tauri command surface for reading and exporting meeting transcripts
//! (MEL-76). These signatures are the unlock point for the frontend (MEL-77):
//! list past meetings, read one in full, export one to a file.

use tauri::Manager;

use crate::storage::meeting::{self, MeetingDbStore, MeetingDetail, MeetingSession};

/// List past meetings, newest first (headers only, no segment text).
#[tauri::command]
pub async fn list_meetings(
    store: tauri::State<'_, MeetingDbStore>,
    limit: u32,
    offset: u32,
) -> Result<Vec<MeetingSession>, String> {
    store
        .list_sessions(limit, offset)
        .await
        .map_err(|e| e.to_string())
}

/// Read one meeting in full: header + ordered segments + merged transcript.
/// Returns `null` if the id does not exist.
#[tauri::command]
pub async fn get_meeting(
    store: tauri::State<'_, MeetingDbStore>,
    session_id: i64,
) -> Result<Option<MeetingDetail>, String> {
    store.get_session(session_id).await.map_err(|e| e.to_string())
}

/// Delete one meeting and its segments.
#[tauri::command]
pub async fn delete_meeting(
    store: tauri::State<'_, MeetingDbStore>,
    session_id: i64,
) -> Result<(), String> {
    store
        .delete_session(session_id)
        .await
        .map_err(|e| e.to_string())
}

/// Export a whole meeting transcript to a file in the user's Downloads folder
/// (MEL-74 default). `format` is "md" (default) or "txt". Returns the written
/// file path so the frontend can reveal it.
#[tauri::command]
pub async fn export_meeting(
    app: tauri::AppHandle,
    store: tauri::State<'_, MeetingDbStore>,
    session_id: i64,
    format: Option<String>,
) -> Result<String, String> {
    let detail = store
        .get_session(session_id)
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("Meeting {session_id} not found"))?;

    let fmt = format.as_deref().unwrap_or("md");
    let (body, ext) = match fmt {
        "txt" => (meeting::render_plain_text(&detail), "txt"),
        "md" | "markdown" => (meeting::render_markdown(&detail), "md"),
        other => return Err(format!("Unsupported export format: {other}")),
    };

    let dir = app
        .path()
        .download_dir()
        .map_err(|e| format!("Cannot resolve Downloads folder: {e}"))?;
    std::fs::create_dir_all(&dir).map_err(|e| e.to_string())?;

    // Stable, recognizable filename: meeting-<id>-<created-date>.<ext>.
    let date_slug = detail
        .session
        .created_at
        .chars()
        .map(|c| if c == ':' { '-' } else { c })
        .collect::<String>();
    let filename = format!("meeting-{}-{}.{}", detail.session.id, date_slug, ext);
    let path = dir.join(filename);

    std::fs::write(&path, body).map_err(|e| e.to_string())?;
    Ok(path.to_string_lossy().to_string())
}
