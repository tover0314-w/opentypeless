use crate::{dictionary_io, storage};

fn validate_dictionary_inputs(
    word: String,
    pronunciation: Option<String>,
) -> Result<(String, Option<String>), String> {
    let word = word.trim().to_string();
    if word.is_empty() {
        return Err("Word cannot be empty".to_string());
    }
    if word.chars().count() > 100 {
        return Err("Word is too long (max 100 characters)".to_string());
    }
    let pronunciation = pronunciation
        .map(|value| value.trim().to_string())
        .filter(|value| !value.is_empty());
    if pronunciation
        .as_ref()
        .is_some_and(|value| value.chars().count() > 100)
    {
        return Err("Pronunciation is too long (max 100 characters)".to_string());
    }
    Ok((word, pronunciation))
}

#[tauri::command]
pub async fn get_dictionary(
    state: tauri::State<'_, storage::DictionaryStore>,
) -> Result<Vec<storage::DictionaryEntry>, String> {
    state.list().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn add_dictionary_entry(
    state: tauri::State<'_, storage::DictionaryStore>,
    word: String,
    pronunciation: Option<String>,
) -> Result<(), String> {
    let (word, pronunciation) = validate_dictionary_inputs(word, pronunciation)?;
    state
        .add(&word, pronunciation.as_deref())
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn update_dictionary_entry(
    state: tauri::State<'_, storage::DictionaryStore>,
    id: i64,
    word: String,
    pronunciation: Option<String>,
) -> Result<(), String> {
    let (word, pronunciation) = validate_dictionary_inputs(word, pronunciation)?;
    state
        .update(id, &word, pronunciation.as_deref())
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn remove_dictionary_entry(
    state: tauri::State<'_, storage::DictionaryStore>,
    id: i64,
) -> Result<(), String> {
    state.remove(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remove_dictionary_entries(
    state: tauri::State<'_, storage::DictionaryStore>,
    ids: Vec<i64>,
) -> Result<usize, String> {
    if ids.len() > storage::MAX_BACKUP_DICTIONARY_ENTRIES {
        return Err("dictionary_batch_too_large".to_string());
    }
    state.remove_many(&ids).await.map_err(|e| e.to_string())
}

fn validate_correction_inputs(
    pattern: String,
    replacement: String,
) -> Result<(String, String), String> {
    let pattern = pattern.trim().to_string();
    let replacement = replacement.trim().to_string();
    if pattern.is_empty() {
        return Err("Wrong phrase cannot be empty".to_string());
    }
    if replacement.is_empty() {
        return Err("Correct phrase cannot be empty".to_string());
    }
    if pattern.chars().count() > 120 {
        return Err("Wrong phrase is too long (max 120 characters)".to_string());
    }
    if replacement.chars().count() > 120 {
        return Err("Correct phrase is too long (max 120 characters)".to_string());
    }
    Ok((pattern, replacement))
}

#[tauri::command]
pub async fn get_correction_rules(
    state: tauri::State<'_, storage::DictionaryStore>,
) -> Result<Vec<storage::CorrectionRule>, String> {
    state.correction_rules().await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn add_correction_rule(
    state: tauri::State<'_, storage::DictionaryStore>,
    pattern: String,
    replacement: String,
) -> Result<(), String> {
    let (pattern, replacement) = validate_correction_inputs(pattern, replacement)?;
    state
        .add_correction(&pattern, &replacement)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn remove_correction_rule(
    state: tauri::State<'_, storage::DictionaryStore>,
    id: i64,
) -> Result<(), String> {
    state.remove_correction(id).await.map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn set_correction_rule_enabled(
    state: tauri::State<'_, storage::DictionaryStore>,
    id: i64,
    enabled: bool,
) -> Result<(), String> {
    state
        .set_correction_enabled(id, enabled)
        .await
        .map_err(|e| e.to_string())
}

#[tauri::command]
pub async fn update_correction_rule(
    state: tauri::State<'_, storage::DictionaryStore>,
    id: i64,
    pattern: String,
    replacement: String,
    enabled: bool,
) -> Result<(), String> {
    let (pattern, replacement) = validate_correction_inputs(pattern, replacement)?;
    state
        .update_correction(id, &pattern, &replacement, enabled)
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn preview_dictionary_import(
    state: tauri::State<'_, storage::DictionaryStore>,
    bytes: Vec<u8>,
    format: dictionary_io::ImportFormat,
) -> Result<dictionary_io::DictionaryImportReport, String> {
    let parsed = dictionary_io::parse_dictionary_import(&bytes, format)
        .map_err(|error| error.to_string())?;
    dictionary_io::preview_dictionary_import(&state, &parsed)
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn commit_dictionary_import(
    state: tauri::State<'_, storage::DictionaryStore>,
    bytes: Vec<u8>,
    format: dictionary_io::ImportFormat,
) -> Result<dictionary_io::DictionaryImportReport, String> {
    let parsed = dictionary_io::parse_dictionary_import(&bytes, format)
        .map_err(|error| error.to_string())?;
    dictionary_io::commit_dictionary_import(&state, parsed)
        .await
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn export_dictionary_json(
    state: tauri::State<'_, storage::DictionaryStore>,
) -> Result<String, String> {
    let dictionary = state.list().await.map_err(|error| error.to_string())?;
    let corrections = state
        .correction_rules()
        .await
        .map_err(|error| error.to_string())?;
    dictionary_io::export_dictionary_json(&dictionary, &corrections)
        .map_err(|error| error.to_string())
}

#[tauri::command]
pub async fn export_dictionary_csv(
    state: tauri::State<'_, storage::DictionaryStore>,
) -> Result<String, String> {
    let dictionary = state.list().await.map_err(|error| error.to_string())?;
    let corrections = state
        .correction_rules()
        .await
        .map_err(|error| error.to_string())?;
    dictionary_io::export_dictionary_csv(&dictionary, &corrections)
        .map_err(|error| error.to_string())
}

#[cfg(test)]
mod tests {
    use super::{validate_correction_inputs, validate_dictionary_inputs};

    #[test]
    fn validate_dictionary_inputs_counts_unicode_scalars_and_normalizes_optional_value() {
        let (word, pronunciation) = validate_dictionary_inputs(
            "  OpenTypeless  ".to_string(),
            Some("  open typeless  ".to_string()),
        )
        .unwrap();
        assert_eq!(word, "OpenTypeless");
        assert_eq!(pronunciation.as_deref(), Some("open typeless"));

        assert!(validate_dictionary_inputs("你".repeat(100), None).is_ok());
        assert!(validate_dictionary_inputs("你".repeat(101), None).is_err());
        assert!(
            validate_dictionary_inputs("OpenTypeless".to_string(), Some(" ".to_string())).is_ok()
        );
    }

    #[test]
    fn validate_correction_inputs_trims_valid_values() {
        let (pattern, replacement) =
            validate_correction_inputs("  拓肯  ".to_string(), "  Token  ".to_string())
                .expect("valid correction");

        assert_eq!(pattern, "拓肯");
        assert_eq!(replacement, "Token");
    }

    #[test]
    fn validate_correction_inputs_rejects_empty_values() {
        assert!(validate_correction_inputs("".to_string(), "Token".to_string()).is_err());
        assert!(validate_correction_inputs("拓肯".to_string(), "  ".to_string()).is_err());
    }

    #[test]
    fn validate_correction_inputs_rejects_overlong_values() {
        let too_long = "a".repeat(121);

        assert!(validate_correction_inputs(too_long.clone(), "Token".to_string()).is_err());
        assert!(validate_correction_inputs("拓肯".to_string(), too_long).is_err());
    }
}
