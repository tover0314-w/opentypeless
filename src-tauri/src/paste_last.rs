//! Typeless "Paste last transcript": re-inserts the newest history text at the cursor.

use crate::{output, pipeline, storage};
use tauri::{Emitter, Manager};

async fn latest_transcript(store: &storage::HistoryStore) -> anyhow::Result<Option<String>> {
    store.latest_insertable_transcript().await
}

/// The text a dictation or translation row inserted: the polished output, else the raw transcript.
/// Ask rows hold a question and an answer card, not inserted text, so they are skipped.
pub fn transcript_text(entry: &storage::HistoryEntry) -> Option<&str> {
    if entry.mode == "ask" || storage::is_recording_only_status(entry.output_status.as_deref()) {
        return None;
    }
    if entry.output_status.as_deref() == Some("fallback") && entry.polished_text.trim().is_empty() {
        return None;
    }
    [entry.polished_text.as_str(), entry.raw_text.as_str()]
        .into_iter()
        .map(str::trim)
        .find(|text| !text.is_empty())
}

pub async fn paste_last_transcript(handle: tauri::AppHandle) {
    let text = match latest_transcript(handle.state::<storage::HistoryStore>().inner()).await {
        Ok(text) => text,
        Err(error) => {
            tracing::warn!("Paste last transcript: failed to read history: {error}");
            return;
        }
    };
    let Some(text) = text else {
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
            let _ = handle.emit(
                "history:paste-result",
                serde_json::json!({ "text": text, "result": outcome.insert_result }),
            );
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

    #[tokio::test]
    async fn failed_generation_without_output_does_not_replace_last_transcript() {
        let store = storage::HistoryStore::new(":memory:".into()).unwrap();
        store
            .add(entry("dictate", "Earlier", "Valid output"))
            .await
            .unwrap();
        for mode in ["translate", "dictate"] {
            let mut failed = entry(mode, "Untranslated source", "\u{3000}");
            failed.output_status = Some("fallback".into());
            assert_eq!(transcript_text(&failed), None);
            store.add(failed).await.unwrap();
            assert_eq!(
                latest_transcript(&store).await.unwrap().as_deref(),
                Some("Valid output")
            );
        }
        let mut recovered = entry("dictate", "Raw", "Raw");
        recovered.output_status = Some("fallback".into());
        assert_eq!(transcript_text(&recovered), Some("Raw"));
        store.add(recovered).await.unwrap();
        assert_eq!(
            latest_transcript(&store).await.unwrap().as_deref(),
            Some("Raw")
        );
    }

    #[tokio::test]
    async fn only_failed_generation_has_no_pasteable_transcript() {
        let store = storage::HistoryStore::new(":memory:".into()).unwrap();
        for mode in ["translate", "dictate"] {
            for empty_output in ["", " \t\n", "\u{3000}\u{a0}"] {
                let mut failed = entry(mode, "Untranslated source", empty_output);
                failed.output_status = Some("fallback".into());
                assert_eq!(transcript_text(&failed), None);
                store.add(failed).await.unwrap();
                assert_eq!(latest_transcript(&store).await.unwrap(), None);
            }
        }
        store
            .add(entry("dictate", "Normal raw dictation", ""))
            .await
            .unwrap();
        assert_eq!(
            latest_transcript(&store).await.unwrap().as_deref(),
            Some("Normal raw dictation")
        );
    }

    #[tokio::test]
    async fn finds_last_dictation_beyond_twenty_newer_ask_rows() {
        let path = std::env::temp_dir().join(format!("paste-last-{}.db", uuid::Uuid::new_v4()));
        let store = storage::HistoryStore::new(path.clone()).unwrap();
        store
            .add(entry("dictate", "Original", "Last dictation"))
            .await
            .unwrap();
        for _index in 0..205 {
            store.add(entry("ask", "Question", "Answer")).await.unwrap();
        }
        let result = latest_transcript(&store).await.unwrap();
        drop(store);
        let _ = std::fs::remove_file(path);
        assert_eq!(result.as_deref(), Some("Last dictation"));
    }

    #[tokio::test]
    async fn single_query_skips_failed_and_unicode_whitespace_rows() {
        let path = std::env::temp_dir().join(format!("paste-query-{}.db", uuid::Uuid::new_v4()));
        let store = storage::HistoryStore::new(path.clone()).unwrap();
        store
            .add(entry("translate", "raw", " Final text "))
            .await
            .unwrap();
        for status in ["dismissed", "silent", "recognition_failed"] {
            let mut failed = entry("dictate", "partial text", "");
            failed.output_status = Some(status.to_string());
            store.add(failed).await.unwrap();
        }
        store
            .add(entry("dictate", "\u{3000}", "\u{a0}"))
            .await
            .unwrap();
        store.add(entry("ask", "Question", "Answer")).await.unwrap();
        let result = store.latest_insertable_transcript().await.unwrap();
        drop(store);
        let _ = std::fs::remove_file(path);
        assert_eq!(result.as_deref(), Some("Final text"));
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

    #[test]
    fn skips_failed_recordings_even_if_partial_text_exists() {
        for status in ["recognition_failed", "dismissed", "silent"] {
            let mut failed = entry("dictate", "incomplete words", "");
            failed.output_status = Some(status.to_string());
            assert_eq!(transcript_text(&failed), None, "{status}");
        }
    }
}
