use crate::storage::{
    normalized_correction_identity, normalized_dictionary_identity, CorrectionRule,
    DictionaryEntry, DictionaryStore,
};
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::fmt;

pub const MAX_IMPORT_BYTES: usize = 5 * 1024 * 1024;
pub const MAX_IMPORT_ROWS: usize = 10_000;
const CSV_MARKER: &str = "# opentypeless_dictionary";
const CSV_HEADER: [&str; 6] = [
    "type",
    "word",
    "pronunciation",
    "wrong_phrase",
    "corrected_phrase",
    "enabled",
];

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum ImportFormat {
    Txt,
    Csv,
    Json,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(tag = "type", rename_all = "snake_case")]
pub enum ParsedDictionaryRow {
    Dictionary {
        word: String,
        pronunciation: Option<String>,
    },
    Correction {
        pattern: String,
        replacement: String,
        enabled: bool,
    },
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct ParsedDictionaryImport {
    pub rows: Vec<ParsedDictionaryRow>,
    pub skipped_invalid: usize,
    pub errors: Vec<ImportRowError>,
}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
pub struct ImportRowError {
    pub row: usize,
    pub code: String,
}

#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "snake_case")]
pub enum DictionaryImportError {
    FileTooLarge,
    InvalidUtf8,
    UnsupportedFormat,
    InvalidStructure,
    TooManyRows,
    Database,
}

impl fmt::Display for DictionaryImportError {
    fn fmt(&self, formatter: &mut fmt::Formatter<'_>) -> fmt::Result {
        formatter.write_str(match self {
            Self::FileTooLarge => "dictionary_import_file_too_large",
            Self::InvalidUtf8 => "dictionary_import_invalid_utf8",
            Self::UnsupportedFormat => "dictionary_import_unsupported_format",
            Self::InvalidStructure => "dictionary_import_invalid_structure",
            Self::TooManyRows => "dictionary_import_too_many_rows",
            Self::Database => "dictionary_import_database_error",
        })
    }
}

impl std::error::Error for DictionaryImportError {}

#[derive(Debug, Clone, Serialize, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct DictionaryImportReport {
    pub accepted: usize,
    pub skipped_duplicates: usize,
    pub skipped_invalid: usize,
    pub errors: Vec<ImportRowError>,
    pub accepted_words: Vec<String>,
}

pub fn parse_dictionary_import(
    bytes: &[u8],
    format: ImportFormat,
) -> Result<ParsedDictionaryImport, DictionaryImportError> {
    if bytes.len() > MAX_IMPORT_BYTES {
        return Err(DictionaryImportError::FileTooLarge);
    }
    let text = std::str::from_utf8(bytes).map_err(|_| DictionaryImportError::InvalidUtf8)?;
    let text = text.strip_prefix('\u{feff}').unwrap_or(text);
    match format {
        ImportFormat::Txt => parse_txt(text),
        ImportFormat::Csv => parse_csv(text),
        ImportFormat::Json => parse_json(text),
    }
}

fn parse_txt(text: &str) -> Result<ParsedDictionaryImport, DictionaryImportError> {
    let mut parsed = empty_parsed_import();
    let mut data_rows = 0;
    for (line_index, line) in text.lines().enumerate() {
        let trimmed = line.trim();
        if trimmed.is_empty() || trimmed.starts_with('#') {
            continue;
        }
        data_rows += 1;
        enforce_row_limit(data_rows)?;
        push_validated_row(
            &mut parsed,
            line_index + 1,
            validate_dictionary_row(trimmed, None),
        );
    }
    Ok(parsed)
}

fn parse_csv(text: &str) -> Result<ParsedDictionaryImport, DictionaryImportError> {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(text.as_bytes());
    let mut records = reader.records();
    let first = records
        .next()
        .ok_or(DictionaryImportError::InvalidStructure)?
        .map_err(|_| DictionaryImportError::InvalidStructure)?;

    let export_version =
        if first.len() == 2 && first.get(0).is_some_and(|value| value.trim() == CSV_MARKER) {
            match first.get(1).map(str::trim) {
                Some("1") => Some(1),
                Some("2") => Some(2),
                _ => None,
            }
        } else {
            None
        };
    let marked = export_version.is_some();
    if first
        .get(0)
        .is_some_and(|value| value.trim().starts_with(CSV_MARKER))
        && !marked
    {
        return Err(DictionaryImportError::InvalidStructure);
    }
    let header = if marked {
        records
            .next()
            .ok_or(DictionaryImportError::InvalidStructure)?
            .map_err(|_| DictionaryImportError::InvalidStructure)?
    } else {
        first
    };
    if header.len() != CSV_HEADER.len()
        || !header
            .iter()
            .zip(CSV_HEADER)
            .all(|(actual, expected)| actual.trim() == expected)
    {
        if marked {
            return Err(DictionaryImportError::InvalidStructure);
        }
        return parse_word_list_csv(text);
    }

    let mut parsed = empty_parsed_import();
    for (record_index, record) in records.enumerate() {
        let row_number = record_index + if marked { 3 } else { 2 };
        enforce_row_limit(record_index + 1)?;
        let record = match record {
            Ok(record) => record,
            Err(_) => {
                push_row_error(&mut parsed, row_number, "csv_parse_error");
                continue;
            }
        };
        if record.len() != CSV_HEADER.len() {
            push_row_error(&mut parsed, row_number, "invalid_column_count");
            continue;
        }
        let fields = record
            .iter()
            .map(|value| restore_exported_csv_cell(value, export_version))
            .collect::<Vec<_>>();
        let row = match fields[0].trim().to_ascii_lowercase().as_str() {
            "dictionary" => {
                if !fields[3].trim().is_empty() || !fields[4].trim().is_empty() {
                    Err("invalid_dictionary_columns")
                } else {
                    validate_dictionary_row(&fields[1], Some(&fields[2]))
                }
            }
            "correction" => {
                if !fields[1].trim().is_empty() || !fields[2].trim().is_empty() {
                    Err("invalid_correction_columns")
                } else {
                    validate_correction_row(&fields[3], &fields[4], parse_enabled(&fields[5]))
                }
            }
            _ => Err("unknown_row_type"),
        };
        push_validated_row(&mut parsed, row_number, row);
    }
    Ok(parsed)
}

/// One word per row, matching the plain word-list CSV other dictation apps export.
fn parse_word_list_csv(text: &str) -> Result<ParsedDictionaryImport, DictionaryImportError> {
    let mut reader = csv::ReaderBuilder::new()
        .has_headers(false)
        .flexible(true)
        .from_reader(text.as_bytes());
    let mut parsed = empty_parsed_import();
    let mut data_rows = 0;
    for (record_index, record) in reader.records().enumerate() {
        let record = record.map_err(|_| DictionaryImportError::InvalidStructure)?;
        let mut cells = record.iter().map(str::trim).filter(|cell| !cell.is_empty());
        let Some(word) = cells.next() else {
            continue;
        };
        if cells.next().is_some() {
            return Err(DictionaryImportError::InvalidStructure);
        }
        data_rows += 1;
        enforce_row_limit(data_rows)?;
        push_validated_row(
            &mut parsed,
            record_index + 1,
            validate_dictionary_row(word, None),
        );
    }
    Ok(parsed)
}

fn parse_json(text: &str) -> Result<ParsedDictionaryImport, DictionaryImportError> {
    let value: serde_json::Value =
        serde_json::from_str(text).map_err(|_| DictionaryImportError::InvalidStructure)?;
    let object = value
        .as_object()
        .ok_or(DictionaryImportError::InvalidStructure)?;
    if let Some(format) = object.get("format") {
        if format.as_str() != Some("opentypeless_dictionary")
            || object.get("version").and_then(serde_json::Value::as_u64) != Some(1)
        {
            return Err(DictionaryImportError::InvalidStructure);
        }
    }

    let dictionary = optional_json_array(object.get("dictionary"))?;
    let corrections = optional_json_array(
        object
            .get("correctionRules")
            .or_else(|| object.get("correction_rules")),
    )?;
    if dictionary.is_none() && corrections.is_none() {
        return Err(DictionaryImportError::InvalidStructure);
    }
    let dictionary_len = dictionary.map_or(0, |rows| rows.len());
    let corrections_len = corrections.map_or(0, |rows| rows.len());
    enforce_row_limit(dictionary_len + corrections_len)?;

    let mut parsed = empty_parsed_import();
    let mut row_number = 0;
    if let Some(rows) = dictionary {
        for row in rows {
            row_number += 1;
            let validated = match row {
                serde_json::Value::String(word) => validate_dictionary_row(word, None),
                serde_json::Value::Object(entry) => {
                    let Some(word) = entry.get("word").and_then(serde_json::Value::as_str) else {
                        push_row_error(&mut parsed, row_number, "dictionary_word_missing");
                        continue;
                    };
                    let pronunciation = match entry.get("pronunciation") {
                        None | Some(serde_json::Value::Null) => None,
                        Some(value) => match value.as_str() {
                            Some(value) => Some(value),
                            None => {
                                push_row_error(
                                    &mut parsed,
                                    row_number,
                                    "dictionary_pronunciation_invalid",
                                );
                                continue;
                            }
                        },
                    };
                    validate_dictionary_row(word, pronunciation)
                }
                _ => Err("dictionary_row_invalid"),
            };
            push_validated_row(&mut parsed, row_number, validated);
        }
    }
    if let Some(rows) = corrections {
        for row in rows {
            row_number += 1;
            let Some(entry) = row.as_object() else {
                push_row_error(&mut parsed, row_number, "correction_row_invalid");
                continue;
            };
            let pattern = string_alias(entry, &["pattern", "wrong_phrase", "wrongPhrase"]);
            let replacement = string_alias(
                entry,
                &[
                    "replacement",
                    "corrected_phrase",
                    "correctedPhrase",
                    "correct_phrase",
                ],
            );
            let (Some(pattern), Some(replacement)) = (pattern, replacement) else {
                push_row_error(&mut parsed, row_number, "correction_fields_missing");
                continue;
            };
            let enabled = entry
                .get("enabled")
                .and_then(serde_json::Value::as_bool)
                .unwrap_or(true);
            push_validated_row(
                &mut parsed,
                row_number,
                validate_correction_row(pattern, replacement, Ok(enabled)),
            );
        }
    }
    Ok(parsed)
}

fn empty_parsed_import() -> ParsedDictionaryImport {
    ParsedDictionaryImport {
        rows: Vec::new(),
        skipped_invalid: 0,
        errors: Vec::new(),
    }
}

fn enforce_row_limit(rows: usize) -> Result<(), DictionaryImportError> {
    if rows > MAX_IMPORT_ROWS {
        Err(DictionaryImportError::TooManyRows)
    } else {
        Ok(())
    }
}

fn optional_json_array(
    value: Option<&serde_json::Value>,
) -> Result<Option<&Vec<serde_json::Value>>, DictionaryImportError> {
    match value {
        None => Ok(None),
        Some(value) => value
            .as_array()
            .map(Some)
            .ok_or(DictionaryImportError::InvalidStructure),
    }
}

fn string_alias<'a>(
    object: &'a serde_json::Map<String, serde_json::Value>,
    aliases: &[&str],
) -> Option<&'a str> {
    aliases
        .iter()
        .find_map(|key| object.get(*key).and_then(serde_json::Value::as_str))
}

fn validate_dictionary_row(
    word: &str,
    pronunciation: Option<&str>,
) -> Result<ParsedDictionaryRow, &'static str> {
    let word = clean_required_text(word, 100, "dictionary_word")?;
    let pronunciation = pronunciation
        .map(str::trim)
        .filter(|value| !value.is_empty())
        .map(|value| clean_required_text(value, 100, "dictionary_pronunciation"))
        .transpose()?;
    Ok(ParsedDictionaryRow::Dictionary {
        word,
        pronunciation,
    })
}

fn validate_correction_row(
    pattern: &str,
    replacement: &str,
    enabled: Result<bool, &'static str>,
) -> Result<ParsedDictionaryRow, &'static str> {
    Ok(ParsedDictionaryRow::Correction {
        pattern: clean_required_text(pattern, 120, "correction_pattern")?,
        replacement: clean_required_text(replacement, 120, "correction_replacement")?,
        enabled: enabled?,
    })
}

fn clean_required_text(
    value: &str,
    max_chars: usize,
    field: &'static str,
) -> Result<String, &'static str> {
    let value = value.trim();
    if value.is_empty() {
        return Err(match field {
            "dictionary_word" => "dictionary_word_empty",
            "dictionary_pronunciation" => "dictionary_pronunciation_empty",
            "correction_pattern" => "correction_pattern_empty",
            _ => "correction_replacement_empty",
        });
    }
    if value.contains('\0') {
        return Err("invalid_null_character");
    }
    if value.chars().count() > max_chars {
        return Err(match field {
            "dictionary_word" => "dictionary_word_too_long",
            "dictionary_pronunciation" => "dictionary_pronunciation_too_long",
            "correction_pattern" => "correction_pattern_too_long",
            _ => "correction_replacement_too_long",
        });
    }
    Ok(value.to_string())
}

fn parse_enabled(value: &str) -> Result<bool, &'static str> {
    match value.trim().to_ascii_lowercase().as_str() {
        "true" | "1" => Ok(true),
        "false" | "0" => Ok(false),
        _ => Err("correction_enabled_invalid"),
    }
}

fn push_validated_row(
    parsed: &mut ParsedDictionaryImport,
    row: usize,
    value: Result<ParsedDictionaryRow, &'static str>,
) {
    match value {
        Ok(value) => parsed.rows.push(value),
        Err(code) => push_row_error(parsed, row, code),
    }
}

fn push_row_error(parsed: &mut ParsedDictionaryImport, row: usize, code: &str) {
    parsed.skipped_invalid += 1;
    parsed.errors.push(ImportRowError {
        row,
        code: code.to_string(),
    });
}

fn restore_exported_csv_cell(value: &str, export_version: Option<u8>) -> String {
    if let Some(rest) = value.strip_prefix('\'') {
        if export_version.is_some()
            && (starts_with_formula_character(rest)
                || (export_version == Some(2) && rest.starts_with('\'')))
        {
            return rest.to_string();
        }
    }
    value.to_string()
}

fn starts_with_formula_character(value: &str) -> bool {
    matches!(
        value.chars().find(|character| !character.is_whitespace()),
        Some('=' | '+' | '-' | '@')
    )
}

pub async fn preview_dictionary_import(
    store: &DictionaryStore,
    parsed: &ParsedDictionaryImport,
) -> Result<DictionaryImportReport, DictionaryImportError> {
    let dictionary = store
        .list()
        .await
        .map_err(|_| DictionaryImportError::Database)?;
    let corrections = store
        .correction_rules()
        .await
        .map_err(|_| DictionaryImportError::Database)?;
    Ok(report_rows(
        &parsed.rows,
        dictionary
            .iter()
            .map(|entry| normalized_dictionary_identity(&entry.word))
            .collect(),
        corrections
            .iter()
            .map(|rule| normalized_correction_identity(&rule.pattern, &rule.replacement))
            .collect(),
        parsed.skipped_invalid,
        parsed.errors.clone(),
    ))
}

fn report_rows(
    rows: &[ParsedDictionaryRow],
    mut dictionary_identities: HashSet<String>,
    mut correction_identities: HashSet<(String, String)>,
    skipped_invalid: usize,
    errors: Vec<ImportRowError>,
) -> DictionaryImportReport {
    let mut accepted = 0;
    let mut skipped_duplicates = 0;
    let mut accepted_words = Vec::new();
    for row in rows {
        let inserted = match row {
            ParsedDictionaryRow::Dictionary { word, .. } => {
                let inserted = dictionary_identities.insert(normalized_dictionary_identity(word));
                if inserted {
                    accepted_words.push(word.clone());
                }
                inserted
            }
            ParsedDictionaryRow::Correction {
                pattern,
                replacement,
                ..
            } => correction_identities.insert(normalized_correction_identity(pattern, replacement)),
        };
        if inserted {
            accepted += 1;
        } else {
            skipped_duplicates += 1;
        }
    }
    DictionaryImportReport {
        accepted,
        skipped_duplicates,
        skipped_invalid,
        errors,
        accepted_words,
    }
}

pub async fn commit_dictionary_import(
    store: &DictionaryStore,
    parsed: ParsedDictionaryImport,
) -> Result<DictionaryImportReport, DictionaryImportError> {
    store
        .with_transaction(|transaction| {
            let mut dictionary_identities = HashSet::new();
            let mut statement = transaction.prepare("SELECT word FROM dictionary")?;
            let words = statement.query_map([], |row| row.get::<_, String>(0))?;
            for word in words {
                dictionary_identities.insert(normalized_dictionary_identity(&word?));
            }
            drop(statement);

            let mut correction_identities = HashSet::new();
            let mut statement =
                transaction.prepare("SELECT pattern, replacement FROM correction_rules")?;
            let rules = statement.query_map([], |row| {
                Ok((row.get::<_, String>(0)?, row.get::<_, String>(1)?))
            })?;
            for rule in rules {
                let (pattern, replacement) = rule?;
                correction_identities
                    .insert(normalized_correction_identity(&pattern, &replacement));
            }
            drop(statement);

            let mut accepted = 0;
            let mut skipped_duplicates = 0;
            let mut accepted_words = Vec::new();
            for row in &parsed.rows {
                match row {
                    ParsedDictionaryRow::Dictionary {
                        word,
                        pronunciation,
                    } => {
                        if !dictionary_identities.insert(normalized_dictionary_identity(word)) {
                            skipped_duplicates += 1;
                            continue;
                        }
                        transaction.execute(
                            "INSERT INTO dictionary (word, pronunciation) VALUES (?1, ?2)",
                            rusqlite::params![word, pronunciation],
                        )?;
                        accepted_words.push(word.clone());
                    }
                    ParsedDictionaryRow::Correction {
                        pattern,
                        replacement,
                        enabled,
                    } => {
                        if !correction_identities
                            .insert(normalized_correction_identity(pattern, replacement))
                        {
                            skipped_duplicates += 1;
                            continue;
                        }
                        transaction.execute(
                            "INSERT INTO correction_rules (pattern, replacement, enabled)
                             VALUES (?1, ?2, ?3)",
                            rusqlite::params![pattern, replacement, if *enabled { 1 } else { 0 }],
                        )?;
                    }
                }
                accepted += 1;
            }
            Ok(DictionaryImportReport {
                accepted,
                skipped_duplicates,
                skipped_invalid: parsed.skipped_invalid,
                errors: parsed.errors,
                accepted_words,
            })
        })
        .map_err(|_| DictionaryImportError::Database)
}

pub fn export_dictionary_json(
    dictionary: &[DictionaryEntry],
    corrections: &[CorrectionRule],
) -> Result<String, DictionaryImportError> {
    let dictionary = dictionary
        .iter()
        .map(|entry| {
            serde_json::json!({
                "word": entry.word,
                "pronunciation": entry.pronunciation,
            })
        })
        .collect::<Vec<_>>();
    let corrections = corrections
        .iter()
        .map(|rule| {
            serde_json::json!({
                "pattern": rule.pattern,
                "replacement": rule.replacement,
                "enabled": rule.enabled,
            })
        })
        .collect::<Vec<_>>();
    serde_json::to_string_pretty(&serde_json::json!({
        "format": "opentypeless_dictionary",
        "version": 1,
        "dictionary": dictionary,
        "correctionRules": corrections,
    }))
    .map_err(|_| DictionaryImportError::InvalidStructure)
}

pub fn export_dictionary_csv(
    dictionary: &[DictionaryEntry],
    corrections: &[CorrectionRule],
) -> Result<String, DictionaryImportError> {
    let mut writer = csv::WriterBuilder::new()
        .flexible(true)
        .from_writer(Vec::new());
    writer
        .write_record([CSV_MARKER, "2"])
        .map_err(|_| DictionaryImportError::InvalidStructure)?;
    writer
        .write_record(CSV_HEADER)
        .map_err(|_| DictionaryImportError::InvalidStructure)?;
    for entry in dictionary {
        writer
            .write_record([
                "dictionary".to_string(),
                safe_csv_cell(&entry.word),
                entry
                    .pronunciation
                    .as_deref()
                    .map(safe_csv_cell)
                    .unwrap_or_default(),
                String::new(),
                String::new(),
                "true".to_string(),
            ])
            .map_err(|_| DictionaryImportError::InvalidStructure)?;
    }
    for rule in corrections {
        writer
            .write_record([
                "correction".to_string(),
                String::new(),
                String::new(),
                safe_csv_cell(&rule.pattern),
                safe_csv_cell(&rule.replacement),
                rule.enabled.to_string(),
            ])
            .map_err(|_| DictionaryImportError::InvalidStructure)?;
    }
    let bytes = writer
        .into_inner()
        .map_err(|_| DictionaryImportError::InvalidStructure)?;
    String::from_utf8(bytes).map_err(|_| DictionaryImportError::InvalidUtf8)
}

fn safe_csv_cell(value: &str) -> String {
    if starts_with_formula_character(value) || value.starts_with('\'') {
        format!("'{value}")
    } else {
        value.to_string()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use crate::storage::{CorrectionRule, DictionaryEntry, DictionaryStore};
    use std::time::{SystemTime, UNIX_EPOCH};

    fn temp_store(name: &str) -> DictionaryStore {
        let nonce = SystemTime::now()
            .duration_since(UNIX_EPOCH)
            .unwrap()
            .as_nanos();
        let path = std::env::temp_dir().join(format!(
            "opentypeless-dictionary-io-{name}-{}-{nonce}.sqlite",
            std::process::id()
        ));
        DictionaryStore::new(path).unwrap()
    }

    #[test]
    fn csv_parser_handles_bom_crlf_quoted_commas_and_newlines() {
        let input = concat!(
            "\u{feff}type,word,pronunciation,wrong_phrase,corrected_phrase,enabled\r\n",
            "dictionary,\"Open,Typeless\",\"open\r\ntypeless\",,,true\r\n",
            "correction,,,\"open, type less\",OpenTypeless,true\r\n"
        );

        let parsed = parse_dictionary_import(input.as_bytes(), ImportFormat::Csv).unwrap();

        assert_eq!(parsed.rows.len(), 2);
        assert_eq!(parsed.skipped_invalid, 0);
        assert_eq!(
            parsed.rows[0],
            ParsedDictionaryRow::Dictionary {
                word: "Open,Typeless".to_string(),
                pronunciation: Some("open\r\ntypeless".to_string()),
            }
        );
        assert_eq!(
            parsed.rows[1],
            ParsedDictionaryRow::Correction {
                pattern: "open, type less".to_string(),
                replacement: "OpenTypeless".to_string(),
                enabled: true,
            }
        );
    }

    #[test]
    fn parser_rejects_invalid_utf8_size_and_row_limit() {
        assert_eq!(
            parse_dictionary_import(&[0xff, 0xfe], ImportFormat::Txt),
            Err(DictionaryImportError::InvalidUtf8)
        );
        assert_eq!(
            parse_dictionary_import(&vec![b'a'; MAX_IMPORT_BYTES + 1], ImportFormat::Txt),
            Err(DictionaryImportError::FileTooLarge)
        );

        let too_many = (0..=MAX_IMPORT_ROWS)
            .map(|index| format!("word-{index}"))
            .collect::<Vec<_>>()
            .join("\n");
        assert_eq!(
            parse_dictionary_import(too_many.as_bytes(), ImportFormat::Txt),
            Err(DictionaryImportError::TooManyRows)
        );
    }

    #[test]
    fn csv_parser_accepts_one_column_word_lists() {
        let input = "\u{feff}OpenTypeless\r\n\r\n\"Talk, More\"\r\n  ,  \r\n  Kubernetes  ,\r\n";

        let parsed = parse_dictionary_import(input.as_bytes(), ImportFormat::Csv).unwrap();

        let words = parsed
            .rows
            .iter()
            .map(|row| match row {
                ParsedDictionaryRow::Dictionary { word, .. } => word.as_str(),
                ParsedDictionaryRow::Correction { .. } => panic!("unexpected correction"),
            })
            .collect::<Vec<_>>();
        assert_eq!(words, ["OpenTypeless", "Talk, More", "Kubernetes"]);
        assert_eq!(parsed.skipped_invalid, 0);
    }

    #[test]
    fn csv_parser_rejects_multi_column_word_lists_and_bad_marked_headers() {
        assert_eq!(
            parse_dictionary_import(b"OpenTypeless\nword,second", ImportFormat::Csv),
            Err(DictionaryImportError::InvalidStructure)
        );
        assert_eq!(
            parse_dictionary_import(b"# opentypeless_dictionary,1\nword\n", ImportFormat::Csv),
            Err(DictionaryImportError::InvalidStructure)
        );
    }

    #[test]
    fn parser_reports_mixed_valid_and_invalid_rows_without_mutation() {
        let input = format!("OpenTypeless\n{}\n# ignored", "x".repeat(101));

        let parsed = parse_dictionary_import(input.as_bytes(), ImportFormat::Txt).unwrap();

        assert_eq!(parsed.rows.len(), 1);
        assert_eq!(parsed.skipped_invalid, 1);
        assert_eq!(parsed.errors[0].row, 2);
        assert_eq!(parsed.errors[0].code, "dictionary_word_too_long");
    }

    #[test]
    fn json_parser_accepts_standalone_and_backup_subset_shapes() {
        let standalone = br#"{
            "format":"opentypeless_dictionary",
            "version":1,
            "dictionary":[{"word":"OpenTypeless","pronunciation":"open typeless"}],
            "correctionRules":[{"pattern":"open type less","replacement":"OpenTypeless","enabled":false}]
        }"#;
        let backup = br#"{"dictionary":[{"id":7,"word":"TalkMore","pronunciation":null}]}"#;

        let parsed = parse_dictionary_import(standalone, ImportFormat::Json).unwrap();
        assert_eq!(parsed.rows.len(), 2);
        assert!(matches!(
            &parsed.rows[1],
            ParsedDictionaryRow::Correction { enabled: false, .. }
        ));

        let parsed = parse_dictionary_import(backup, ImportFormat::Json).unwrap();
        assert_eq!(parsed.rows.len(), 1);
        assert!(matches!(
            &parsed.rows[0],
            ParsedDictionaryRow::Dictionary { word, .. } if word == "TalkMore"
        ));
    }

    #[test]
    fn csv_export_preserves_literal_apostrophes_before_formula_characters() {
        let words = ["'=literal", "''=literal", "'ordinary", "+formula"];
        let dictionary = words
            .iter()
            .enumerate()
            .map(|(index, word)| DictionaryEntry {
                id: index as i64,
                word: word.to_string(),
                pronunciation: Some(word.to_string()),
                auto: false,
            })
            .collect::<Vec<_>>();
        let exported = export_dictionary_csv(&dictionary, &[]).unwrap();
        let parsed = parse_dictionary_import(exported.as_bytes(), ImportFormat::Csv).unwrap();
        for (row, expected) in parsed.rows.iter().zip(words) {
            assert_eq!(
                row,
                &ParsedDictionaryRow::Dictionary {
                    word: expected.to_string(),
                    pronunciation: Some(expected.to_string()),
                }
            );
        }
        assert_eq!(parsed.rows.len(), words.len());
    }

    #[test]
    fn csv_export_neutralizes_formulas_and_round_trips_only_its_marker() {
        let dictionary = vec![DictionaryEntry {
            id: 1,
            word: "  =HYPERLINK(\"https://example.com\")".to_string(),
            pronunciation: Some("+cmd".to_string()),
            auto: false,
        }];
        let corrections = vec![CorrectionRule {
            id: 2,
            pattern: "-danger".to_string(),
            replacement: "@danger".to_string(),
            enabled: true,
        }];

        let exported = export_dictionary_csv(&dictionary, &corrections).unwrap();
        assert!(exported.starts_with("# opentypeless_dictionary,2"));
        assert!(exported.contains("'  =HYPERLINK"));
        assert!(exported.contains("'+cmd"));
        assert!(exported.contains("'-danger"));
        assert!(exported.contains("'@danger"));

        let parsed = parse_dictionary_import(exported.as_bytes(), ImportFormat::Csv).unwrap();
        assert!(matches!(
            &parsed.rows[0],
            ParsedDictionaryRow::Dictionary { word, pronunciation }
                if word.starts_with("=HYPERLINK") && pronunciation.as_deref() == Some("+cmd")
        ));

        let unmarked = b"type,word,pronunciation,wrong_phrase,corrected_phrase,enabled\n\
dictionary,'=literal,,,,true\n";
        let parsed = parse_dictionary_import(unmarked, ImportFormat::Csv).unwrap();
        assert!(matches!(
            &parsed.rows[0],
            ParsedDictionaryRow::Dictionary { word, .. } if word == "'=literal"
        ));

        let legacy = b"# opentypeless_dictionary,1\n\
type,word,pronunciation,wrong_phrase,corrected_phrase,enabled\n\
dictionary,'=legacy,,,,true\n";
        let parsed = parse_dictionary_import(legacy, ImportFormat::Csv).unwrap();
        assert!(matches!(
            &parsed.rows[0],
            ParsedDictionaryRow::Dictionary { word, .. } if word == "=legacy"
        ));
    }

    #[test]
    fn json_export_contains_only_dictionary_subset() {
        let exported = export_dictionary_json(
            &[DictionaryEntry {
                id: 9,
                word: "OpenTypeless".to_string(),
                pronunciation: None,
                auto: true,
            }],
            &[CorrectionRule {
                id: 8,
                pattern: "open type less".to_string(),
                replacement: "OpenTypeless".to_string(),
                enabled: true,
            }],
        )
        .unwrap();
        let value: serde_json::Value = serde_json::from_str(&exported).unwrap();

        assert_eq!(value["format"], "opentypeless_dictionary");
        assert_eq!(value["version"], 1);
        assert!(value.get("dictionary").is_some());
        assert!(value.get("correctionRules").is_some());
        assert!(value.get("settings").is_none());
        assert!(value.get("history").is_none());
        assert!(value.get("apiKey").is_none());
    }

    #[tokio::test]
    async fn preview_and_commit_dedupe_nfkc_case_and_pairs() {
        let store = temp_store("dedupe");
        store.add("OpenTypeless", None).await.unwrap();
        store
            .add_correction("open type less", "OpenTypeless")
            .await
            .unwrap();
        let parsed = ParsedDictionaryImport {
            rows: vec![
                ParsedDictionaryRow::Dictionary {
                    word: "ＯＰＥＮＴＹＰＥＬＥＳＳ".to_string(),
                    pronunciation: None,
                },
                ParsedDictionaryRow::Dictionary {
                    word: "TalkMore".to_string(),
                    pronunciation: None,
                },
                ParsedDictionaryRow::Correction {
                    pattern: " OPEN TYPE LESS ".to_string(),
                    replacement: "opentypeless".to_string(),
                    enabled: true,
                },
            ],
            skipped_invalid: 0,
            errors: Vec::new(),
        };

        let preview = preview_dictionary_import(&store, &parsed).await.unwrap();
        assert_eq!(preview.accepted, 1);
        assert_eq!(preview.skipped_duplicates, 2);
        assert_eq!(preview.accepted_words, ["TalkMore"]);

        let report = commit_dictionary_import(&store, parsed).await.unwrap();
        assert_eq!(report.accepted, 1);
        assert_eq!(report.accepted_words, ["TalkMore"]);
        assert_eq!(report.skipped_duplicates, 2);
        assert_eq!(store.list().await.unwrap().len(), 2);
        assert_eq!(store.correction_rules().await.unwrap().len(), 1);
    }

    #[tokio::test]
    async fn database_failure_rolls_back_every_accepted_row() {
        let store = temp_store("rollback");
        store
            .execute_batch_for_test(
                "CREATE TRIGGER fail_dictionary_insert BEFORE INSERT ON dictionary
                 WHEN NEW.word = 'boom' BEGIN SELECT RAISE(ABORT, 'boom'); END;",
            )
            .unwrap();
        let parsed = parse_dictionary_import(b"first\nboom", ImportFormat::Txt).unwrap();

        assert_eq!(
            commit_dictionary_import(&store, parsed).await,
            Err(DictionaryImportError::Database)
        );
        assert!(store.list().await.unwrap().is_empty());
    }
}
