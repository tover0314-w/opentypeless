//! Typeless "Paste last transcript": re-inserts the newest history text at the cursor.

use crate::{output, pipeline, storage};
use tauri::{Emitter, Manager};

/// Rows scanned for the newest insertable transcript; Ask answers in between are skipped.
const LOOKBACK: u32 = 20;

/// The text a dictation or translation row inserted: the polished output, else the raw transcript.
/// Ask rows hold a question and an answer card, not inserted text, so they are skipped.
pub fn transcript_text(entry: &storage::HistoryEntry) -> Option<&str> {
    if entry.mode == "ask" {
        return None;
    }
    [entry.polished_text.as_str(), entry.raw_text.as_str()]
        .into_iter()
        .map(str::trim)
        .find(|text| !text.is_empty())
}

pub async fn paste_last_transcript(handle: tauri::AppHandle) {
    let entries = match handle
        .state::<storage::HistoryStore>()
        .list(LOOKBACK, 0)
        .await
    {
        Ok(entries) => entries,
        Err(error) => {
            tracing::warn!("Paste last transcript: failed to read history: {error}");
            return;
        }
    };
    let Some(text) = entries.iter().find_map(transcript_text).map(str::to_owned) else {
        tracing::info!("Paste last transcript: history is empty");
        return;
    };

    let config = match handle.state::<storage::ConfigManager>().load().await {
        Ok(config) => config,
        Err(error) => {
            tracing::warn!("Paste last transcript: failed to load config: {error}");
            return;
        }
    };
    let requested = output::InsertionStrategy::from_config_value(&config.insertion_strategy);
    let (strategy, _) = pipeline::effective_strategy_for_accessibility(
        requested,
        pipeline::is_accessibility_trusted(),
    );
    let result = output::output_with_strategy(
        &handle,
        &text,
        strategy,
        output::clipboard::ClipboardOutputOptions {
            restore_after_paste: config.restore_clipboard_after_paste,
            paste_shortcut: output::clipboard::PasteShortcut::from_config_value(
                &config.paste_shortcut,
            ),
            auto_paste: true,
        },
        output::windows_sendinput::WindowsSendInputOptions {
            newline_mode: output::windows_sendinput::WindowsSendInputNewlineMode::from_config_value(
                &config.windows_sendinput_newline_mode,
            ),
        },
    )
    .await;

    match result {
        Ok(outcome) => {
            let _ = handle.emit("pipeline:insert_result", &outcome.insert_result);
        }
        Err(error) => tracing::warn!("Paste last transcript failed: {error}"),
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    fn entry(mode: &str, raw: &str, polished: &str) -> storage::HistoryEntry {
        serde_json::from_value(serde_json::json!({
            "id": 1,
            "created_at": "2026-11-01T10:00:00",
            "context_profile_id": "general.native",
            "context_label": "General",
            "context_icon_key": "general",
            "context_family": "general",
            "browser_access_status": "not_applicable",
            "provider_kind": "local",
            "raw_text": raw,
            "polished_text": polished,
            "language": null,
            "duration_ms": null,
            "active_scene_id": null,
            "active_scene_source": null,
            "active_scene_name": null,
            "active_scene_prompt_chars": null,
            "active_scene_prompt_truncated": false,
            "output_status": null,
            "output_error": null,
            "mode": mode,
            "answer_text": null,
        }))
        .expect("history entry fixture")
    }

    #[test]
    fn prefers_the_polished_text_that_was_inserted() {
        assert_eq!(
            transcript_text(&entry("dictate", "um hello", "Hello.")),
            Some("Hello.")
        );
    }

    #[test]
    fn falls_back_to_the_raw_transcript_when_polish_was_off() {
        assert_eq!(
            transcript_text(&entry("translate", "hello there", "  ")),
            Some("hello there")
        );
    }

    #[test]
    fn skips_ask_rows_and_empty_rows() {
        assert_eq!(transcript_text(&entry("ask", "What time is it?", "")), None);
        assert_eq!(transcript_text(&entry("dictate", " ", "")), None);
    }
}
