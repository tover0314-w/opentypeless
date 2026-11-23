use rusqlite::{Connection, OptionalExtension, TransactionBehavior};
use serde::Deserialize;

pub(super) fn read_cursor(connection: &Connection, account_id: &str) -> anyhow::Result<i64> {
    let invalid = || anyhow::anyhow!("History sync cursor unavailable");
    if account_id.is_empty()
        || account_id.trim() != account_id
        || account_id.len() > 200
        || account_id.chars().any(char::is_control)
    {
        return Err(invalid());
    }
    let transaction = connection.unchecked_transaction().map_err(|_| invalid())?;
    let rebuild: bool = transaction
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM history_identity_state WHERE rebuild_required = 1)",
            [],
            |row| row.get(0),
        )
        .map_err(|_| invalid())?;
    if rebuild {
        return Err(invalid());
    }
    let exists: bool = transaction
        .query_row(
            "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'history_sync_pull_cursor')",
            [],
            |row| row.get(0),
        )
        .map_err(|_| invalid())?;
    let cursor = if exists {
        transaction
            .query_row(
                "SELECT cursor FROM history_sync_pull_cursor WHERE account_id = ?1",
                [account_id],
                |row| row.get::<_, i64>(0),
            )
            .optional()
            .map_err(|_| invalid())?
            .unwrap_or(0)
    } else {
        0
    };
    if !(0..=9007199254740991).contains(&cursor) {
        return Err(invalid());
    }
    transaction.commit().map_err(|_| invalid())?;
    Ok(cursor)
}

pub(super) fn import_new_page(
    connection: &mut Connection,
    page: &HistorySyncPage,
    max_entries: u32,
) -> anyhow::Result<i64> {
    import_page_with_commit(connection, page, max_entries, |transaction| {
        transaction.commit()?;
        Ok(())
    })
}

pub(super) fn import_page_with_commit<'connection>(
    connection: &'connection mut Connection,
    page: &HistorySyncPage,
    max_entries: u32,
    commit: impl FnOnce(rusqlite::Transaction<'connection>) -> anyhow::Result<()>,
) -> anyhow::Result<i64> {
    let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let invalid = || anyhow::anyhow!("History sync import unavailable");
    let rebuild: bool = transaction.query_row(
        "SELECT EXISTS(SELECT 1 FROM history_identity_state WHERE rebuild_required = 1)",
        [],
        |row| row.get(0),
    )?;
    if rebuild {
        return Err(invalid());
    }
    transaction.execute_batch(
        "CREATE TABLE IF NOT EXISTS history_sync_pull_cursor (
            account_id TEXT PRIMARY KEY,
            cursor INTEGER NOT NULL CHECK(typeof(cursor) = 'integer' AND cursor BETWEEN 0 AND 9007199254740991)
        );
        CREATE TABLE IF NOT EXISTS history_sync_remote_baseline (
            record_uuid TEXT PRIMARY KEY,
            account_id TEXT NOT NULL,
            local_revision INTEGER NOT NULL CHECK(local_revision BETWEEN 1 AND 9007199254740991),
            server_revision INTEGER NOT NULL CHECK(server_revision BETWEEN 1 AND 9007199254740991)
        );
        CREATE TABLE IF NOT EXISTS history_sync_remote_tombstone (
            record_uuid TEXT PRIMARY KEY,
            account_id TEXT NOT NULL,
            server_revision INTEGER NOT NULL CHECK(typeof(server_revision) = 'integer' AND server_revision BETWEEN 1 AND 9007199254740991)
        );",
    )?;
    let current: Option<i64> = transaction
        .query_row(
            "SELECT cursor FROM history_sync_pull_cursor WHERE account_id = ?1",
            [&page.account_id],
            |row| row.get(0),
        )
        .optional()?;
    if current.unwrap_or(0) != page.from_cursor {
        return Err(invalid());
    }
    let mut accepted_echoes = std::collections::HashSet::new();
    for record in &page.records {
        let violates_baseline: bool = transaction.query_row(
            "SELECT EXISTS(
                SELECT 1 FROM history_sync_remote_tombstone
                WHERE record_uuid = ?1 COLLATE NOCASE
                  AND (account_id != ?2 OR server_revision >= ?3 OR ?4 = 0)
            )",
            rusqlite::params![
                record.record_id,
                page.account_id,
                record.server_revision,
                record.deleted
            ],
            |row| row.get(0),
        )?;
        if violates_baseline {
            return Err(invalid());
        }
        let echoes = {
            let mut query = transaction.prepare(
                "SELECT identity.history_id, baseline.local_revision
                 FROM history_sync_remote_baseline AS baseline
                 JOIN history_local_identity AS identity USING(record_uuid)
                 JOIN history_sync_record_owner AS owner USING(record_uuid)
                 JOIN history_sync_confirmed AS confirmed USING(record_uuid)
                 WHERE baseline.record_uuid = ?1 COLLATE NOCASE
                   AND baseline.account_id = ?2 AND owner.account_id = ?2
                   AND confirmed.account_id = ?2
                   AND baseline.server_revision = ?3 AND confirmed.server_revision = ?3
                   AND baseline.local_revision = identity.local_revision
                   AND confirmed.client_revision = identity.local_revision
                   AND NOT EXISTS (
                     SELECT 1 FROM history_sync_pending WHERE record_uuid = ?1 COLLATE NOCASE
                   )",
            )?;
            let rows = query
                .query_map(
                    rusqlite::params![record.record_id, page.account_id, record.server_revision],
                    |row| Ok((row.get::<_, i64>(0)?, row.get::<_, i64>(1)?)),
                )?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            rows
        };
        if !record.deleted && echoes.len() == 1 {
            let (history_id, revision) = echoes[0];
            if !(1..=9007199254740991).contains(&revision) {
                return Err(invalid());
            }
            let stored = super::history_sync_queue::capture_payload(&transaction, history_id)?;
            let stored: serde_json::Value = serde_json::from_str(&stored)?;
            if stored != record.payload {
                return Err(invalid());
            }
            accepted_echoes.insert(record.change_sequence);
            continue;
        }
        let stale_remote: bool = transaction.query_row(
            "SELECT EXISTS(
                SELECT 1 FROM history_sync_remote_baseline
                WHERE record_uuid = ?1 COLLATE NOCASE
                  AND (account_id != ?2 OR server_revision >= ?3)
            )",
            rusqlite::params![record.record_id, page.account_id, record.server_revision],
            |row| row.get(0),
        )?;
        if stale_remote {
            return Err(invalid());
        }
    }
    let mut identities = std::collections::HashSet::new();
    let mut final_records = page
        .records
        .iter()
        .rev()
        .filter(|record| identities.insert(record.record_id.as_str()))
        .collect::<Vec<_>>();
    final_records.reverse();
    let mut count: i64 =
        transaction.query_row("SELECT COUNT(*) FROM history", [], |row| row.get(0))?;
    for record in final_records {
        if accepted_echoes.contains(&record.change_sequence) {
            continue;
        }
        let remote = {
            let mut query = transaction.prepare(
                "SELECT baseline.record_uuid, baseline.account_id, baseline.local_revision,
                        baseline.server_revision, identity.history_id, identity.local_revision,
                        owner.account_id
                 FROM history_sync_remote_baseline AS baseline
                 LEFT JOIN history_local_identity AS identity
                   ON identity.record_uuid = baseline.record_uuid COLLATE NOCASE
                 LEFT JOIN history_sync_record_owner AS owner
                   ON owner.record_uuid = baseline.record_uuid COLLATE NOCASE
                 WHERE baseline.record_uuid = ?1 COLLATE NOCASE",
            )?;
            let rows = query
                .query_map([&record.record_id], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, i64>(2)?,
                        row.get::<_, i64>(3)?,
                        row.get::<_, Option<i64>>(4)?,
                        row.get::<_, Option<i64>>(5)?,
                        row.get::<_, Option<String>>(6)?,
                    ))
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            rows
        };
        if !remote.is_empty() {
            if remote.len() != 1 {
                return Err(invalid());
            }
            let (
                stored_uuid,
                account,
                baseline_revision,
                server_revision,
                history_id,
                revision,
                owner,
            ) = &remote[0];
            let Some(history_id) = history_id else {
                return Err(invalid());
            };
            if account != &page.account_id
                || owner.as_deref() != Some(page.account_id.as_str())
                || revision != &Some(*baseline_revision)
                || !(1..=9007199254740991).contains(baseline_revision)
                || (!record.deleted && *baseline_revision == 9007199254740991)
                || !(1..=9007199254740991).contains(server_revision)
                || record.server_revision <= *server_revision
            {
                return Err(invalid());
            }
            let unsafe_source: bool = transaction.query_row(
                "SELECT NOT EXISTS(SELECT 1 FROM history WHERE id = ?1)
                     OR EXISTS(SELECT 1 FROM history_audio WHERE history_id = ?1)
                     OR EXISTS(SELECT 1 FROM history_citations WHERE history_id = ?1)
                     OR EXISTS(SELECT 1 FROM history_sync_pending WHERE record_uuid = ?2 COLLATE NOCASE)
                     OR EXISTS(SELECT 1 FROM history_sync_remote_tombstone WHERE record_uuid = ?2 COLLATE NOCASE)",
                rusqlite::params![history_id, record.record_id],
                |row| row.get(0),
            )?;
            if unsafe_source {
                return Err(invalid());
            }
            let confirmations = {
                let mut query = transaction.prepare(
                    "SELECT record_uuid, account_id, client_revision, server_revision
                     FROM history_sync_confirmed WHERE record_uuid = ?1 COLLATE NOCASE",
                )?;
                let rows = query
                    .query_map([&record.record_id], |row| {
                        Ok((
                            row.get::<_, String>(0)?,
                            row.get::<_, String>(1)?,
                            row.get::<_, i64>(2)?,
                            row.get::<_, i64>(3)?,
                        ))
                    })?
                    .collect::<rusqlite::Result<Vec<_>>>()?;
                rows
            };
            if !confirmations.is_empty() {
                if confirmations.len() != 1
                    || confirmations[0]
                        != (
                            stored_uuid.clone(),
                            account.clone(),
                            *baseline_revision,
                            *server_revision,
                        )
                {
                    return Err(invalid());
                }
                let cleared = transaction.execute(
                    "DELETE FROM history_sync_confirmed
                     WHERE record_uuid = ?1 AND account_id = ?2
                       AND client_revision = ?3 AND server_revision = ?4",
                    rusqlite::params![stored_uuid, account, baseline_revision, server_revision],
                )?;
                if cleared != 1 {
                    return Err(invalid());
                }
            }
            if record.deleted {
                let tombstone_count: i64 = transaction.query_row(
                    "SELECT COUNT(*) FROM history_sync_remote_tombstone",
                    [],
                    |row| row.get(0),
                )?;
                if tombstone_count >= 5000 {
                    anyhow::bail!("History sync deletion metadata capacity reached");
                }
                let inserted = transaction.execute(
                    "INSERT INTO history_sync_remote_tombstone(record_uuid, account_id, server_revision)
                     VALUES (?1, ?2, ?3)",
                    rusqlite::params![record.record_id, page.account_id, record.server_revision],
                )?;
                if inserted != 1 {
                    return Err(invalid());
                }
                if transaction.execute("DELETE FROM history WHERE id = ?1", [history_id])? != 1 {
                    return Err(invalid());
                }
                if transaction.execute(
                    "DELETE FROM history_sync_remote_baseline
                     WHERE record_uuid = ?1 AND account_id = ?2 AND server_revision = ?3",
                    rusqlite::params![stored_uuid, account, server_revision],
                )? != 1
                {
                    return Err(invalid());
                }
                if transaction.execute(
                    "DELETE FROM history_sync_record_owner
                     WHERE record_uuid = ?1 COLLATE NOCASE AND account_id = ?2",
                    rusqlite::params![stored_uuid, account],
                )? != 1
                {
                    return Err(invalid());
                }
                count -= 1;
                continue;
            }
            let payload = &record.payload;
            let updated = transaction.execute(
                "UPDATE history SET created_at = ?1, raw_text = ?2, polished_text = ?3,
                        mode = ?4, answer_text = ?5, duration_ms = ?6, language = ?7 WHERE id = ?8",
                rusqlite::params![
                    payload["createdAt"].as_str(),
                    payload["rawText"].as_str(),
                    payload["polishedText"].as_str(),
                    payload["mode"].as_str(),
                    payload["answerText"].as_str(),
                    payload["durationMs"].as_i64(),
                    payload["language"].as_str(),
                    history_id,
                ],
            )?;
            if updated != 1 {
                return Err(invalid());
            }
            let current_revision: i64 = transaction.query_row(
                "SELECT local_revision FROM history_local_identity WHERE history_id = ?1",
                [history_id],
                |row| row.get(0),
            )?;
            let changed = transaction.execute(
                "UPDATE history_sync_remote_baseline SET local_revision = ?1, server_revision = ?2
                 WHERE record_uuid = ?3 AND account_id = ?4 AND server_revision = ?5",
                rusqlite::params![
                    current_revision,
                    record.server_revision,
                    stored_uuid,
                    account,
                    server_revision
                ],
            )?;
            if changed != 1 {
                return Err(invalid());
            }
            continue;
        }
        let exists: bool = transaction.query_row(
            "SELECT EXISTS(SELECT 1 FROM history_local_identity WHERE record_uuid = ?1 COLLATE NOCASE)
                 OR EXISTS(SELECT 1 FROM history_sync_record_owner WHERE record_uuid = ?1 COLLATE NOCASE)
                 OR EXISTS(SELECT 1 FROM history_sync_pending WHERE record_uuid = ?1 COLLATE NOCASE)
                 OR EXISTS(SELECT 1 FROM history_sync_confirmed WHERE record_uuid = ?1 COLLATE NOCASE)
                 OR EXISTS(SELECT 1 FROM history_sync_remote_baseline WHERE record_uuid = ?1 COLLATE NOCASE)",
            [&record.record_id],
            |row| row.get(0),
        )?;
        if exists {
            return Err(invalid());
        }
        let tombstones = {
            let mut query = transaction.prepare(
                "SELECT record_uuid, account_id, server_revision FROM history_sync_remote_tombstone
                 WHERE record_uuid = ?1 COLLATE NOCASE",
            )?;
            let rows = query
                .query_map([&record.record_id], |row| {
                    Ok((
                        row.get::<_, String>(0)?,
                        row.get::<_, String>(1)?,
                        row.get::<_, i64>(2)?,
                    ))
                })?
                .collect::<rusqlite::Result<Vec<_>>>()?;
            rows
        };
        if !tombstones.is_empty() {
            if tombstones.len() != 1 {
                return Err(invalid());
            }
            let (stored_uuid, account, version) = &tombstones[0];
            if !record.deleted
                || account != &page.account_id
                || !(1..=9007199254740991).contains(version)
                || record.server_revision <= *version
            {
                return Err(invalid());
            }
            let changed = transaction.execute(
                "UPDATE history_sync_remote_tombstone SET server_revision = ?1
                 WHERE record_uuid = ?2 AND account_id = ?3 AND server_revision = ?4",
                rusqlite::params![record.server_revision, stored_uuid, account, version],
            )?;
            if changed != 1 {
                return Err(invalid());
            }
            continue;
        }
        if record.deleted {
            let count: i64 = transaction.query_row(
                "SELECT COUNT(*) FROM history_sync_remote_tombstone",
                [],
                |row| row.get(0),
            )?;
            if count >= 5000 {
                anyhow::bail!("History sync deletion metadata capacity reached");
            }
            let inserted = transaction.execute(
                "INSERT INTO history_sync_remote_tombstone(record_uuid, account_id, server_revision)
                 VALUES (?1, ?2, ?3)",
                rusqlite::params![record.record_id, page.account_id, record.server_revision],
            )?;
            if inserted != 1 {
                return Err(invalid());
            }
            continue;
        }
        let payload = &record.payload;
        if count >= i64::from(max_entries.clamp(1, super::DEFAULT_HISTORY_MAX_ENTRIES)) {
            return Err(invalid());
        }
        count += 1;
        let inserted = transaction.execute(
            "INSERT INTO history(created_at, raw_text, polished_text, mode, answer_text, duration_ms, language)
             VALUES (?1, ?2, ?3, ?4, ?5, ?6, ?7)",
            rusqlite::params![
                payload["createdAt"].as_str(),
                payload["rawText"].as_str(),
                payload["polishedText"].as_str(),
                payload["mode"].as_str(),
                payload["answerText"].as_str(),
                payload["durationMs"].as_i64(),
                payload["language"].as_str(),
            ],
        )?;
        if inserted != 1 {
            return Err(invalid());
        }
        let history_id = transaction.last_insert_rowid();
        let identity_inserted = transaction.execute(
            "INSERT INTO history_local_identity(history_id, record_uuid, local_revision) VALUES (?1, ?2, 1)",
            rusqlite::params![history_id, record.record_id],
        )?;
        if identity_inserted != 1 {
            return Err(invalid());
        }
        let owner_inserted = transaction.execute(
            "INSERT INTO history_sync_record_owner(record_uuid, account_id) VALUES (?1, ?2)",
            rusqlite::params![record.record_id, page.account_id],
        )?;
        if owner_inserted != 1 {
            return Err(invalid());
        }
        let baseline_inserted = transaction.execute(
            "INSERT INTO history_sync_remote_baseline(record_uuid, account_id, local_revision, server_revision)
             VALUES (?1, ?2, 1, ?3)",
            rusqlite::params![record.record_id, page.account_id, record.server_revision],
        )?;
        if baseline_inserted != 1 {
            return Err(invalid());
        }
    }
    let written = transaction.execute(
        "INSERT INTO history_sync_pull_cursor(account_id, cursor) VALUES (?1, ?2)
         ON CONFLICT(account_id) DO UPDATE SET cursor = excluded.cursor",
        rusqlite::params![page.account_id, page.next_cursor],
    )?;
    if written != 1 {
        return Err(invalid());
    }
    let saved: i64 = transaction.query_row(
        "SELECT cursor FROM history_sync_pull_cursor WHERE account_id = ?1",
        [&page.account_id],
        |row| row.get(0),
    )?;
    if saved != page.next_cursor {
        return Err(invalid());
    }
    commit(transaction)?;
    Ok(page.next_cursor)
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HistorySyncPage {
    version: u8,
    pub account_id: String,
    pub from_cursor: i64,
    pub next_cursor: i64,
    pub has_more: bool,
    pub change_sequences: Vec<i64>,
    pub records: Vec<HistorySyncPageRecord>,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HistorySyncPageRecord {
    pub change_sequence: i64,
    pub record_id: String,
    pub server_revision: i64,
    pub deleted: bool,
    #[serde(deserialize_with = "deserialize_payload")]
    pub payload: serde_json::Value,
}

fn deserialize_payload<'de, D>(deserializer: D) -> Result<serde_json::Value, D::Error>
where
    D: serde::Deserializer<'de>,
{
    let payload =
        Option::<super::history_sync_queue::QueuedHistoryPayload>::deserialize(deserializer)?;
    serde_json::to_value(payload).map_err(serde::de::Error::custom)
}

pub fn parse_page(
    bytes: &[u8],
    account_id: &str,
    requested_cursor: i64,
) -> anyhow::Result<HistorySyncPage> {
    let invalid = || anyhow::anyhow!("Invalid history sync page");
    if bytes.len() > 4 * 1024 * 1024
        || account_id.is_empty()
        || account_id.trim() != account_id
        || account_id.len() > 200
        || account_id.chars().any(char::is_control)
        || !(0..=9007199254740991).contains(&requested_cursor)
    {
        return Err(invalid());
    }
    let mut page: HistorySyncPage = serde_json::from_slice(bytes).map_err(|_| invalid())?;
    if page.version != 1
        || page.account_id != account_id
        || page.from_cursor != requested_cursor
        || !(0..=9007199254740991).contains(&page.next_cursor)
        || page.records.len() > 500
        || page.change_sequences.len() != page.records.len()
        || (page.has_more && page.records.is_empty())
    {
        return Err(invalid());
    }
    let mut previous_sequence = requested_cursor;
    let mut revisions = std::collections::HashMap::<String, (i64, bool)>::new();
    for (record, sequence) in page.records.iter_mut().zip(&page.change_sequences) {
        if *sequence != previous_sequence + 1
            || !(1..=9007199254740991).contains(sequence)
            || record.change_sequence != *sequence
            || !(1..=9007199254740991).contains(&record.server_revision)
            || !super::history_sync_queue::valid_sync_uuid(&record.record_id)
        {
            return Err(invalid());
        }
        previous_sequence = *sequence;
        record.record_id.make_ascii_lowercase();
        if revisions
            .get(&record.record_id)
            .is_some_and(|(revision, deleted)| {
                record.server_revision <= *revision || (*deleted && !record.deleted)
            })
        {
            return Err(invalid());
        }
        revisions.insert(
            record.record_id.clone(),
            (record.server_revision, record.deleted),
        );
        if record.deleted {
            if !record.payload.is_null() {
                return Err(invalid());
            }
        } else {
            let payload = serde_json::to_string(&record.payload).map_err(|_| invalid())?;
            super::history_sync_queue::validate_stored_payload(&payload).map_err(|_| invalid())?;
        }
    }
    if page.next_cursor != previous_sequence {
        return Err(invalid());
    }
    Ok(page)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn page() -> serde_json::Value {
        serde_json::json!({
            "version": 1, "accountId": "synthetic-account", "fromCursor": 0,
            "nextCursor": 1, "hasMore": false, "changeSequences": [1],
            "records": [{
                "changeSequence": 1, "recordId": "11111111-1111-4111-8111-111111111111",
                "serverRevision": 1, "deleted": false,
                "payload": {
                    "rawText": "Synthetic", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }]
        })
    }

    #[test]
    fn parses_bounded_account_page_and_tombstone_without_writing_history() {
        let value = page();
        let parsed =
            parse_page(&serde_json::to_vec(&value).unwrap(), "synthetic-account", 0).unwrap();
        assert_eq!(parsed.next_cursor, 1);
        assert_eq!(parsed.records[0].payload["rawText"], "Synthetic");
        let mut tombstone = value;
        tombstone["records"][0]["deleted"] = serde_json::json!(true);
        tombstone["records"][0]["payload"] = serde_json::Value::Null;
        assert!(parse_page(
            &serde_json::to_vec(&tombstone).unwrap(),
            "synthetic-account",
            0
        )
        .is_ok());
    }

    #[test]
    fn refuses_mismatched_account_cursor_sequences_and_unknown_payload_fields() {
        for mutation in ["account", "cursor", "sequence", "field", "tombstone"] {
            let mut value = page();
            match mutation {
                "account" => value["accountId"] = serde_json::json!("another-account"),
                "cursor" => value["fromCursor"] = serde_json::json!(1),
                "sequence" => value["changeSequences"] = serde_json::json!([2]),
                "field" => value["records"][0]["payload"]["apiKey"] = serde_json::json!("private"),
                _ => value["records"][0]["deleted"] = serde_json::json!(true),
            }
            assert!(
                parse_page(&serde_json::to_vec(&value).unwrap(), "synthetic-account", 0).is_err()
            );
        }
    }

    #[test]
    fn refuses_duplicate_payload_fields_and_tombstone_resurrection() {
        let serialized = page().to_string().replace(
            "\"rawText\":\"Synthetic\"",
            "\"rawText\":\"Synthetic\",\"rawText\":\"replacement\"",
        );
        assert!(parse_page(serialized.as_bytes(), "synthetic-account", 0).is_err());
        let mut value = page();
        let mut second = value["records"][0].clone();
        second["changeSequence"] = serde_json::json!(2);
        second["serverRevision"] = serde_json::json!(2);
        value["records"][0]["deleted"] = serde_json::json!(true);
        value["records"][0]["payload"] = serde_json::Value::Null;
        value["records"].as_array_mut().unwrap().push(second);
        value["changeSequences"] = serde_json::json!([1, 2]);
        value["nextCursor"] = serde_json::json!(2);
        assert!(parse_page(value.to_string().as_bytes(), "synthetic-account", 0).is_err());
    }

    #[test]
    fn bounds_raw_bytes_records_and_empty_page_progress() {
        assert!(parse_page(&vec![b' '; 4 * 1024 * 1024 + 1], "synthetic-account", 0).is_err());
        let mut value = page();
        value["records"] = serde_json::json!([]);
        value["changeSequences"] = serde_json::json!([]);
        value["nextCursor"] = serde_json::json!(0);
        assert!(parse_page(value.to_string().as_bytes(), "synthetic-account", 0).is_ok());
        value["hasMore"] = serde_json::json!(true);
        assert!(parse_page(value.to_string().as_bytes(), "synthetic-account", 0).is_err());
        let mut oversized = page();
        oversized["records"] = serde_json::json!(vec![page()["records"][0].clone(); 501]);
        oversized["changeSequences"] = serde_json::json!(vec![1; 501]);
        assert!(parse_page(oversized.to_string().as_bytes(), "synthetic-account", 0).is_err());
        let mut missing = page();
        missing["records"][0]["payload"]
            .as_object_mut()
            .unwrap()
            .remove("language");
        assert!(parse_page(missing.to_string().as_bytes(), "synthetic-account", 0).is_err());
    }
}
