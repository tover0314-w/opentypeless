use std::path::Path;

use crate::storage;

/// A saved recording, serialized for the frontend Recordings page.
///
/// JSON keys are exactly: id, created_at, raw_text, polished_text,
/// recording_file, format, duration_ms (snake_case, matching the frontend's
/// interface). `format` is derived from the saved file extension.
#[derive(Debug, Clone, serde::Serialize)]
pub struct RecordingEntry {
    pub id: i64,
    pub created_at: String,
    pub raw_text: String,
    pub polished_text: String,
    pub recording_file: Option<String>,
    pub format: Option<String>,
    pub duration_ms: Option<i64>,
}

/// Extract the lower-case file extension (without dot) from a path.
fn format_from_path(path: &str) -> Option<String> {
    Path::new(path)
        .extension()
        .map(|ext| ext.to_string_lossy().to_ascii_lowercase())
}

impl RecordingEntry {
    fn from_history(entry: storage::HistoryEntry) -> Self {
        let format = entry.recording_file.as_deref().and_then(format_from_path);
        Self {
            id: entry.id,
            created_at: entry.created_at,
            raw_text: entry.raw_text,
            polished_text: entry.polished_text,
            recording_file: entry.recording_file,
            format,
            duration_ms: entry.duration_ms,
        }
    }
}

/// List history rows that have a saved audio file, newest first.
#[tauri::command]
pub async fn get_recordings(
    state: tauri::State<'_, storage::HistoryStore>,
    limit: u32,
    offset: u32,
) -> Result<Vec<RecordingEntry>, String> {
    let rows = state
        .list_recordings(limit, offset)
        .await
        .map_err(|e| e.to_string())?;
    Ok(rows.into_iter().map(RecordingEntry::from_history).collect())
}

/// Port of the localhost server that streams recordings for in-app playback.
/// The frontend builds `http://127.0.0.1:<port>/<filename>` audio URLs from it.
#[tauri::command]
pub fn get_recordings_server_port(
    port: tauri::State<'_, crate::RecordingsServerPort>,
) -> u16 {
    port.0
}

/// Return the absolute path to a recording's audio file.
#[tauri::command]
pub async fn get_recording_path(
    state: tauri::State<'_, storage::HistoryStore>,
    id: i64,
) -> Result<String, String> {
    let entry = state
        .find_by_id(id)
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("Recording {id} not found"))?;
    entry
        .recording_file
        .ok_or_else(|| format!("Recording {id} has no saved audio file"))
}

/// Read a recording's raw audio bytes for in-app playback.
///
/// WebKitGTK's `<audio>` element rejects Tauri's custom `asset://` scheme as a
/// media source (it only accepts http/file/blob), so the frontend plays
/// recordings from a `blob:` URL built from these bytes instead of from the
/// asset protocol. Returns the bytes as a raw IPC response (ArrayBuffer on the
/// JS side), not a JSON number array.
#[tauri::command]
pub async fn read_recording_bytes(
    state: tauri::State<'_, storage::HistoryStore>,
    id: i64,
) -> Result<tauri::ipc::Response, String> {
    let entry = state
        .find_by_id(id)
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("Recording {id} not found"))?;
    let path = entry
        .recording_file
        .ok_or_else(|| format!("Recording {id} has no saved audio file"))?;
    let bytes = std::fs::read(&path).map_err(|e| format!("Failed to read {path}: {e}"))?;
    Ok(tauri::ipc::Response::new(bytes))
}

/// Re-run the configured STT provider on a saved recording, update the stored
/// raw transcript, and return the new transcript.
#[tauri::command]
pub async fn retranscribe_recording(
    history: tauri::State<'_, storage::HistoryStore>,
    config_manager: tauri::State<'_, storage::ConfigManager>,
    client: tauri::State<'_, reqwest::Client>,
    id: i64,
) -> Result<String, String> {
    let entry = history
        .find_by_id(id)
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("Recording {id} not found"))?;
    let path = entry
        .recording_file
        .ok_or_else(|| format!("Recording {id} has no saved audio file"))?;

    let audio = std::fs::read(&path).map_err(|e| format!("Failed to read {path}: {e}"))?;
    let ext = format_from_path(&path).unwrap_or_else(|| "wav".to_string());

    let config = config_manager.load().await.map_err(|e| e.to_string())?;

    let transcript = crate::stt::retranscribe_file(&config, audio, &ext, client.inner().clone())
        .await
        .map_err(|e| e.to_string())?;

    history
        .update_transcript(id, &transcript)
        .await
        .map_err(|e| e.to_string())?;

    Ok(transcript)
}

/// Delete a recording's audio file and clear its path, keeping the transcript row.
#[tauri::command]
pub async fn delete_recording(
    state: tauri::State<'_, storage::HistoryStore>,
    id: i64,
) -> Result<(), String> {
    let entry = state
        .find_by_id(id)
        .await
        .map_err(|e| e.to_string())?
        .ok_or_else(|| format!("Recording {id} not found"))?;

    if let Some(path) = entry.recording_file {
        // Ignore "not found" so a missing file still clears the DB reference.
        if let Err(e) = std::fs::remove_file(&path) {
            if e.kind() != std::io::ErrorKind::NotFound {
                return Err(format!("Failed to delete {path}: {e}"));
            }
        }
    }

    state
        .clear_recording_file(id)
        .await
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn format_from_path_extracts_extension() {
        assert_eq!(format_from_path("/data/rec/abc.flac").as_deref(), Some("flac"));
        assert_eq!(format_from_path("/data/rec/abc.MP3").as_deref(), Some("mp3"));
        assert_eq!(format_from_path("/data/rec/abc").as_deref(), None);
    }

    #[test]
    fn recording_entry_derives_format_from_file() {
        let entry = storage::HistoryEntry {
            id: 7,
            created_at: "2026-06-22T10:00:00".to_string(),
            app_name: "App".to_string(),
            app_type: "Unknown".to_string(),
            raw_text: "hi".to_string(),
            polished_text: "Hi.".to_string(),
            language: None,
            duration_ms: Some(900),
            recording_file: Some("/data/rec/abc.flac".to_string()),
        };

        let rec = RecordingEntry::from_history(entry);
        assert_eq!(rec.id, 7);
        assert_eq!(rec.format.as_deref(), Some("flac"));
        assert_eq!(rec.recording_file.as_deref(), Some("/data/rec/abc.flac"));
        assert_eq!(rec.duration_ms, Some(900));
    }

    #[test]
    fn recording_entry_serializes_expected_keys() {
        let entry = storage::HistoryEntry {
            id: 1,
            created_at: "t".to_string(),
            app_name: String::new(),
            app_type: String::new(),
            raw_text: "r".to_string(),
            polished_text: "p".to_string(),
            language: None,
            duration_ms: None,
            recording_file: Some("/x/y.wav".to_string()),
        };
        let value = serde_json::to_value(RecordingEntry::from_history(entry)).unwrap();
        let obj = value.as_object().unwrap();

        for key in [
            "id",
            "created_at",
            "raw_text",
            "polished_text",
            "recording_file",
            "format",
            "duration_ms",
        ] {
            assert!(obj.contains_key(key), "missing key: {key}");
        }
        assert_eq!(obj.len(), 7, "unexpected extra keys: {obj:?}");
        assert!(obj["duration_ms"].is_null());
    }
}
