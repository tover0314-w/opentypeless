use anyhow::Result;
use rusqlite::{Connection, OptionalExtension, TransactionBehavior};
use serde::{Deserialize, Serialize};

pub(super) fn valid_sync_uuid(value: &str) -> bool {
    value.len() == 36
        && uuid::Uuid::parse_str(value).ok().is_some_and(|identity| {
            identity.get_version_num() == 4
                && identity.get_variant() == uuid::Variant::RFC4122
                && identity
                    .hyphenated()
                    .to_string()
                    .eq_ignore_ascii_case(value)
        })
}

fn remote_baseline(
    connection: &Connection,
    record_uuid: &str,
) -> Result<Option<(String, i64, i64)>> {
    let exists: bool = connection.query_row(
        "SELECT EXISTS(SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = 'history_sync_remote_baseline')",
        [],
        |row| row.get(0),
    )?;
    if !exists {
        return Ok(None);
    }
    let mut query = connection.prepare(
        "SELECT record_uuid, account_id, local_revision, server_revision
         FROM history_sync_remote_baseline WHERE record_uuid = ?1 COLLATE NOCASE",
    )?;
    let rows = query
        .query_map([record_uuid], |row| {
            Ok((
                row.get::<_, String>(0)?,
                row.get::<_, String>(1)?,
                row.get::<_, i64>(2)?,
                row.get::<_, i64>(3)?,
            ))
        })?
        .collect::<rusqlite::Result<Vec<_>>>()?;
    if rows.is_empty() {
        return Ok(None);
    }
    if rows.len() != 1 || rows[0].0 != record_uuid {
        anyhow::bail!("Invalid history sync remote baseline");
    }
    let (_, account, local, server) = rows.into_iter().next().unwrap();
    if !(1..=9007199254740991).contains(&local) || !(1..=9007199254740991).contains(&server) {
        anyhow::bail!("Invalid history sync remote baseline");
    }
    Ok(Some((account, local, server)))
}

#[derive(Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(super) struct QueuedHistoryPayload {
    raw_text: String,
    polished_text: String,
    mode: String,
    #[serde(deserialize_with = "deserialize_nullable")]
    answer_text: Option<String>,
    created_at: String,
    #[serde(deserialize_with = "deserialize_nullable")]
    duration_ms: Option<i64>,
    #[serde(deserialize_with = "deserialize_nullable")]
    language: Option<String>,
}

fn deserialize_nullable<'de, D, T>(deserializer: D) -> Result<Option<T>, D::Error>
where
    D: serde::Deserializer<'de>,
    T: Deserialize<'de>,
{
    Option::<T>::deserialize(deserializer)
}

pub(super) fn capture_payload(connection: &Connection, history_id: i64) -> Result<String> {
    let payload = connection
        .query_row(
            "SELECT raw_text, polished_text, mode, answer_text, created_at, duration_ms, language,
         length(CAST(raw_text AS BLOB)) + length(CAST(polished_text AS BLOB))
         + length(CAST(mode AS BLOB)) + COALESCE(length(CAST(answer_text AS BLOB)), 0)
         + length(CAST(created_at AS BLOB)) + COALESCE(length(CAST(language AS BLOB)), 0)
         FROM history WHERE id = ?1",
            [history_id],
            |row| {
                if row.get::<_, i64>(7)? > 1024 * 1024 {
                    return Err(rusqlite::Error::InvalidQuery);
                }
                Ok(QueuedHistoryPayload {
                    raw_text: row.get(0)?,
                    polished_text: row.get(1)?,
                    mode: row.get(2)?,
                    answer_text: row.get(3)?,
                    created_at: row.get(4)?,
                    duration_ms: row.get(5)?,
                    language: row.get(6)?,
                })
            },
        )
        .map_err(|_| anyhow::anyhow!("History sync payload unavailable"))?;
    serialize_valid_payload(&payload)
}

fn serialize_valid_payload(payload: &QueuedHistoryPayload) -> Result<String> {
    let date = payload.created_at.as_bytes();
    if date.len() != 19
        || date.iter().enumerate().any(|(index, byte)| match index {
            4 | 7 => *byte != b'-',
            10 => *byte != b'T',
            13 | 16 => *byte != b':',
            _ => !byte.is_ascii_digit(),
        })
        || date[17] > b'5'
    {
        anyhow::bail!("History sync payload unavailable");
    }
    if !matches!(payload.mode.as_str(), "dictate" | "translate" | "ask")
        || (payload.mode != "ask" && payload.answer_text.is_some())
        || payload
            .duration_ms
            .is_some_and(|duration| !(0..=9007199254740991).contains(&duration))
        || payload
            .language
            .as_ref()
            .is_some_and(|language| language.len() > 200)
        || [
            &payload.raw_text,
            &payload.polished_text,
            &payload.mode,
            &payload.created_at,
        ]
        .iter()
        .any(|value| value.contains('\0'))
        || payload
            .answer_text
            .as_ref()
            .is_some_and(|value| value.contains('\0'))
        || payload
            .language
            .as_ref()
            .is_some_and(|value| value.contains('\0'))
    {
        anyhow::bail!("History sync payload unavailable");
    }
    let timestamp = chrono::NaiveDateTime::parse_from_str(&payload.created_at, "%Y-%m-%dT%H:%M:%S")
        .map_err(|_| anyhow::anyhow!("History sync payload unavailable"))?;
    if timestamp.format("%Y-%m-%dT%H:%M:%S").to_string() != payload.created_at {
        anyhow::bail!("History sync payload unavailable");
    }
    let serialized = serde_json::to_string(payload)?;
    if serialized.len() > 1024 * 1024 {
        anyhow::bail!("History sync payload unavailable");
    }
    Ok(serialized)
}

pub(super) fn validate_stored_payload(serialized: &str) -> Result<()> {
    if serialized.len() > 1024 * 1024 {
        anyhow::bail!("History sync payload unavailable");
    }
    let payload: QueuedHistoryPayload = serde_json::from_str(serialized)
        .map_err(|_| anyhow::anyhow!("History sync payload unavailable"))?;
    let fields: serde_json::Value = serde_json::from_str(serialized)
        .map_err(|_| anyhow::anyhow!("History sync payload unavailable"))?;
    if fields.as_object().is_none_or(|fields| {
        fields.len() != 7
            || ["answerText", "durationMs", "language"]
                .iter()
                .any(|field| !fields.contains_key(*field))
    }) {
        anyhow::bail!("History sync payload unavailable");
    }
    serialize_valid_payload(&payload)?;
    Ok(())
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingHistoryMutation {
    pub account_id: String,
    pub record_uuid: String,
    pub mutation_uuid: String,
    pub client_revision: i64,
    pub base_server_revision: i64,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub enum PendingHistorySource {
    Current { history_id: i64 },
    Changed,
    Missing,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct PendingHistoryQueueEntry {
    pub mutation: PendingHistoryMutation,
    pub source: PendingHistorySource,
    pub deleted: bool,
}

pub fn build_batch_request(
    connection: &mut Connection,
    mutations: &[PendingHistoryMutation],
) -> Result<serde_json::Value> {
    if !(1..=200).contains(&mutations.len()) {
        anyhow::bail!("Invalid history sync batch metadata");
    }
    let account_id = &mutations[0].account_id;
    let mut records = std::collections::HashSet::new();
    let mut identities = std::collections::HashSet::new();
    for mutation in mutations {
        if &mutation.account_id != account_id
            || !records.insert(mutation.record_uuid.to_ascii_lowercase())
            || !identities.insert(mutation.mutation_uuid.to_ascii_lowercase())
        {
            anyhow::bail!("Invalid history sync batch metadata");
        }
    }
    let transaction = connection.transaction_with_behavior(TransactionBehavior::Deferred)?;
    let mut bytes = b"{\"version\":1,\"mutations\":[]}".len();
    let mut requests = Vec::with_capacity(mutations.len());
    for mutation in mutations {
        let mut request = build_request(&transaction, mutation)?;
        let entry = request["mutations"][0].take();
        bytes += serde_json::to_vec(&entry)?.len() + usize::from(!requests.is_empty());
        if bytes > 4 * 1024 * 1024 {
            anyhow::bail!("History sync batch exceeds request limit");
        }
        requests.push(entry);
    }
    transaction.commit()?;
    Ok(serde_json::json!({"version": 1, "mutations": requests}))
}

pub fn build_request(
    connection: &Connection,
    mutation: &PendingHistoryMutation,
) -> Result<serde_json::Value> {
    if mutation.account_id.is_empty()
        || mutation.account_id.trim() != mutation.account_id
        || mutation.account_id.len() > 200
        || mutation.account_id.chars().any(char::is_control)
        || !(1..=9007199254740991).contains(&mutation.client_revision)
        || !(0..=9007199254740991).contains(&mutation.base_server_revision)
        || [&mutation.record_uuid, &mutation.mutation_uuid]
            .iter()
            .any(|value| !valid_sync_uuid(value))
    {
        anyhow::bail!("Invalid history sync request metadata");
    }
    let row: Option<(Option<String>, bool, Option<i64>, bool)> = connection
        .query_row(
            "SELECT pending.payload_json, history.id IS NOT NULL, identity.local_revision,
                    pending.deleted
             FROM history_sync_pending AS pending
             JOIN history_sync_record_owner AS owner USING(record_uuid)
             LEFT JOIN history_local_identity AS identity USING(record_uuid)
             LEFT JOIN history ON history.id = identity.history_id
             WHERE pending.account_id = ?1 AND owner.account_id = ?1
               AND pending.record_uuid = ?2 AND pending.mutation_uuid = ?3
               AND pending.client_revision = ?4
               AND pending.base_server_revision = ?5
               AND NOT EXISTS (
                   SELECT 1 FROM history_identity_state WHERE rebuild_required = 1
               )",
            rusqlite::params![
                mutation.account_id,
                mutation.record_uuid,
                mutation.mutation_uuid,
                mutation.client_revision,
                mutation.base_server_revision
            ],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        )
        .optional()
        .map_err(|_| anyhow::anyhow!("History sync request source unavailable"))?;
    let (serialized, history_exists, local_revision, deleted) =
        row.ok_or_else(|| anyhow::anyhow!("History sync request source unavailable"))?;
    if deleted {
        if history_exists || serialized.is_some() || local_revision.is_some() {
            anyhow::bail!("History sync deletion source unavailable");
        }
        return Ok(serde_json::json!({
            "version": 1,
            "mutations": [{
                "recordId": mutation.record_uuid,
                "mutationId": mutation.mutation_uuid,
                "clientRevision": mutation.client_revision,
                "baseServerRevision": mutation.base_server_revision,
                "deleted": true,
                "payload": serde_json::Value::Null
            }]
        }));
    } else if !history_exists {
        anyhow::bail!("History sync request source unavailable");
    }
    if local_revision != Some(mutation.client_revision) {
        anyhow::bail!("History sync request source unavailable");
    }
    let serialized =
        serialized.ok_or_else(|| anyhow::anyhow!("History sync payload unavailable"))?;
    if serialized.len() > 1024 * 1024 {
        anyhow::bail!("History sync payload unavailable");
    }
    validate_stored_payload(&serialized)?;
    let payload: QueuedHistoryPayload = serde_json::from_str(&serialized)
        .map_err(|_| anyhow::anyhow!("History sync payload unavailable"))?;
    Ok(serde_json::json!({
        "version": 1,
        "mutations": [{
            "recordId": mutation.record_uuid,
            "mutationId": mutation.mutation_uuid,
            "clientRevision": mutation.client_revision,
            "baseServerRevision": mutation.base_server_revision,
            "deleted": false,
            "payload": payload
        }]
    }))
}

pub fn delete_with_commit<'connection>(
    connection: &'connection mut Connection,
    account_id: &str,
    history_id: i64,
    commit: impl FnOnce(rusqlite::Transaction<'connection>) -> Result<()>,
) -> Result<Option<PendingHistoryMutation>> {
    let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let mutation = delete_in_transaction(&transaction, account_id, history_id)?;
    commit(transaction)?;
    Ok(mutation)
}

fn delete_in_transaction(
    transaction: &rusqlite::Transaction<'_>,
    account_id: &str,
    history_id: i64,
) -> Result<Option<PendingHistoryMutation>> {
    if account_id.is_empty()
        || account_id.trim() != account_id
        || account_id.len() > 200
        || account_id.chars().any(char::is_control)
        || history_id <= 0
    {
        anyhow::bail!("Invalid history sync deletion request");
    }
    let identity: Option<(String, i64)> = transaction
        .query_row(
            "SELECT record_uuid, local_revision FROM history_local_identity
             WHERE history_id = ?1",
            [history_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let Some((record_uuid, local_revision)) = identity else {
        anyhow::bail!("History sync deletion source unavailable");
    };
    if !valid_sync_uuid(&record_uuid) || !(1..9007199254740991).contains(&local_revision) {
        anyhow::bail!("Invalid history sync deletion source");
    }
    let owner: Option<String> = transaction
        .query_row(
            "SELECT account_id FROM history_sync_record_owner WHERE record_uuid = ?1",
            [&record_uuid],
            |row| row.get(0),
        )
        .optional()?;
    let foreign_pending: bool = transaction.query_row(
        "SELECT EXISTS(SELECT 1 FROM history_sync_pending
         WHERE record_uuid = ?1 AND account_id != ?2)",
        rusqlite::params![record_uuid, account_id],
        |row| row.get(0),
    )?;
    if owner.as_deref().is_some_and(|owner| owner != account_id) || foreign_pending {
        anyhow::bail!("History sync deletion belongs to another account");
    }
    let pending_resolution: bool = transaction.query_row(
        "SELECT EXISTS(
            SELECT 1 FROM history_sync_resolution_base AS resolution
            JOIN history_sync_pending AS pending
              ON pending.account_id = resolution.account_id
             AND pending.record_uuid = resolution.record_uuid
             AND pending.client_revision = resolution.client_revision
             AND pending.mutation_uuid = resolution.mutation_uuid
            WHERE resolution.account_id = ?1 AND resolution.record_uuid = ?2
        )",
        rusqlite::params![account_id, record_uuid],
        |row| row.get(0),
    )?;
    if pending_resolution {
        anyhow::bail!("Confirm the pending conflict resolution before deleting history");
    }
    let remote = remote_baseline(transaction, &record_uuid)?;
    let confirmed: Option<(String, i64, i64)> = transaction
        .query_row(
            "SELECT account_id, client_revision, server_revision
             FROM history_sync_confirmed WHERE record_uuid = ?1",
            [&record_uuid],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    let baseline: Option<(String, i64)> = match (remote.as_ref(), confirmed.as_ref()) {
        (None, None) => None,
        (Some((remote_account, remote_local, remote_server)), Some(confirmed)) => {
            if confirmed != &(remote_account.clone(), *remote_local, *remote_server) {
                anyhow::bail!("History sync deletion baseline changed");
            }
            Some((remote_account.clone(), *remote_server))
        }
        (Some((remote_account, _, remote_server)), None) => {
            Some((remote_account.clone(), *remote_server))
        }
        (None, Some((confirmed_account, _, confirmed_server))) => {
            Some((confirmed_account.clone(), *confirmed_server))
        }
    };
    transaction.execute(
        "DELETE FROM history_sync_pending WHERE record_uuid = ?1",
        [&record_uuid],
    )?;
    let mutation = if let Some((baseline_account, server_revision)) = baseline {
        if baseline_account != account_id || !(1..=9007199254740991).contains(&server_revision) {
            anyhow::bail!("History sync deletion belongs to another account");
        }
        let client_revision = local_revision + 1;
        let mutation_uuid = uuid::Uuid::new_v4().to_string();
        transaction.execute(
            "INSERT INTO history_sync_pending
             (account_id, record_uuid, mutation_uuid, client_revision,
              base_server_revision, payload_json, deleted)
             VALUES (?1, ?2, ?3, ?4, ?5, NULL, 1)",
            rusqlite::params![
                account_id,
                record_uuid,
                mutation_uuid,
                client_revision,
                server_revision
            ],
        )?;
        Some(PendingHistoryMutation {
            account_id: account_id.into(),
            record_uuid: record_uuid.clone(),
            mutation_uuid,
            client_revision,
            base_server_revision: server_revision,
        })
    } else {
        transaction.execute(
            "DELETE FROM history_sync_record_owner WHERE record_uuid = ?1",
            [&record_uuid],
        )?;
        None
    };
    let deleted = transaction.execute("DELETE FROM history WHERE id = ?1", [history_id])?;
    if deleted != 1 {
        anyhow::bail!("History sync deletion source unavailable");
    }
    Ok(mutation)
}

pub(super) fn clear_with_commit<'connection>(
    connection: &'connection mut Connection,
    account_id: &str,
    commit: impl FnOnce(rusqlite::Transaction<'connection>) -> Result<()>,
) -> Result<()> {
    if account_id.is_empty()
        || account_id.trim() != account_id
        || account_id.len() > 200
        || account_id.chars().any(char::is_control)
    {
        anyhow::bail!("Invalid history sync deletion request");
    }
    let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let history_ids = {
        let mut query = transaction.prepare("SELECT id FROM history ORDER BY id")?;
        let rows = query.query_map([], |row| row.get::<_, i64>(0))?;
        rows.collect::<rusqlite::Result<Vec<_>>>()?
    };
    for history_id in history_ids {
        delete_in_transaction(&transaction, account_id, history_id)?;
    }
    commit(transaction)?;
    Ok(())
}

pub fn pending(
    connection: &Connection,
    account_id: &str,
    limit: u32,
) -> Result<Vec<PendingHistoryQueueEntry>> {
    if !(1..=200).contains(&limit) {
        anyhow::bail!("Invalid history sync queue request");
    }
    pending_with_scan_limit(connection, account_id, limit)
}

pub(super) fn pending_for_selection(
    connection: &Connection,
    account_id: &str,
) -> Result<Vec<PendingHistoryQueueEntry>> {
    pending_with_scan_limit(connection, account_id, 5000)
}

fn pending_with_scan_limit(
    connection: &Connection,
    account_id: &str,
    limit: u32,
) -> Result<Vec<PendingHistoryQueueEntry>> {
    if account_id.is_empty()
        || account_id.trim() != account_id
        || account_id.len() > 200
        || account_id.chars().any(char::is_control)
        || !(1..=5000).contains(&limit)
    {
        anyhow::bail!("Invalid history sync queue request");
    }
    let mut query = connection.prepare(
        "SELECT EXISTS(SELECT 1 FROM history_identity_state WHERE rebuild_required = 1),
         pending.record_uuid, pending.mutation_uuid, pending.client_revision,
         owner.account_id, history.id, identity.local_revision, pending.base_server_revision,
         pending.deleted
         FROM (SELECT 1) AS anchor
         LEFT JOIN history_sync_pending AS pending ON pending.account_id = ?1
         LEFT JOIN history_sync_record_owner AS owner ON owner.record_uuid = pending.record_uuid
         LEFT JOIN history_local_identity AS identity ON identity.record_uuid = pending.record_uuid
         LEFT JOIN history ON history.id = identity.history_id
         ORDER BY pending.rowid LIMIT ?2",
    )?;
    let rows = query.query_map(rusqlite::params![account_id, limit], |row| {
        Ok((
            row.get::<_, bool>(0)?,
            row.get::<_, Option<String>>(1)?,
            row.get::<_, Option<String>>(2)?,
            row.get::<_, Option<i64>>(3)?,
            row.get::<_, Option<String>>(4)?,
            row.get::<_, Option<i64>>(5)?,
            row.get::<_, Option<i64>>(6)?,
            row.get::<_, Option<i64>>(7)?,
            row.get::<_, Option<bool>>(8)?,
        ))
    })?;
    let mut entries = Vec::new();
    for row in rows {
        let (
            rebuild,
            record,
            mutation,
            revision,
            owner,
            history_id,
            current_revision,
            base,
            deleted,
        ) = row.map_err(|_| anyhow::anyhow!("Invalid history sync queue metadata"))?;
        if rebuild {
            anyhow::bail!("History sync queue unavailable");
        }
        let Some(record_uuid) = record else {
            continue;
        };
        let (Some(mutation_uuid), Some(client_revision), Some(base_server_revision), Some(deleted)) =
            (mutation, revision, base, deleted)
        else {
            anyhow::bail!("Invalid history sync queue metadata");
        };
        if owner.as_deref() != Some(account_id)
            || !(1..=9007199254740991).contains(&client_revision)
            || !(0..=9007199254740991).contains(&base_server_revision)
            || [&record_uuid, &mutation_uuid]
                .iter()
                .any(|value| !valid_sync_uuid(value))
        {
            anyhow::bail!("Invalid history sync queue metadata");
        }
        if current_revision.is_some_and(|current| {
            !(1..=9007199254740991).contains(&current) || current < client_revision
        }) {
            anyhow::bail!("Invalid history sync queue metadata");
        }
        let source = match (history_id, current_revision) {
            (Some(history_id), Some(current)) if current == client_revision => {
                PendingHistorySource::Current { history_id }
            }
            (Some(_), Some(_)) => PendingHistorySource::Changed,
            _ => PendingHistorySource::Missing,
        };
        entries.push(PendingHistoryQueueEntry {
            mutation: PendingHistoryMutation {
                account_id: account_id.to_owned(),
                record_uuid,
                mutation_uuid,
                client_revision,
                base_server_revision,
            },
            source,
            deleted,
        });
    }
    Ok(entries)
}

pub fn initialize(connection: &Connection) -> Result<()> {
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS history_sync_record_owner (
            record_uuid TEXT PRIMARY KEY,
            account_id TEXT NOT NULL
        );
        CREATE TABLE IF NOT EXISTS history_sync_pending (
            account_id TEXT NOT NULL,
            record_uuid TEXT NOT NULL,
            mutation_uuid TEXT NOT NULL UNIQUE,
            client_revision INTEGER NOT NULL
                CHECK(typeof(client_revision) = 'integer'
                    AND client_revision BETWEEN 1 AND 9007199254740991),
            PRIMARY KEY(account_id, record_uuid, client_revision)
        );
        CREATE TABLE IF NOT EXISTS history_sync_confirmed (
            record_uuid TEXT PRIMARY KEY,
            account_id TEXT NOT NULL,
            mutation_uuid TEXT NOT NULL,
            client_revision INTEGER NOT NULL
                CHECK(typeof(client_revision) = 'integer'
                    AND client_revision BETWEEN 1 AND 9007199254740991),
            server_revision INTEGER NOT NULL
                CHECK(typeof(server_revision) = 'integer'
                    AND server_revision BETWEEN 1 AND 9007199254740991)
        );",
    )?;
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS history_sync_resolution_base (
            account_id TEXT NOT NULL,
            record_uuid TEXT NOT NULL,
            client_revision INTEGER NOT NULL,
            mutation_uuid TEXT NOT NULL,
            previous_base INTEGER NOT NULL CHECK(previous_base BETWEEN 0 AND 9007199254740991),
            approved_base INTEGER NOT NULL CHECK(approved_base BETWEEN 0 AND 9007199254740991),
            PRIMARY KEY(account_id, record_uuid, client_revision),
            FOREIGN KEY(account_id, record_uuid, client_revision)
                REFERENCES history_sync_pending(account_id, record_uuid, client_revision)
                ON DELETE CASCADE
        );",
    )?;
    let has_base = {
        let mut query = connection.prepare("PRAGMA table_info(history_sync_pending)")?;
        let columns = query
            .query_map([], |row| row.get::<_, String>(1))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        columns
            .iter()
            .any(|column| column == "base_server_revision")
    };
    if !has_base {
        connection.execute_batch(
            "ALTER TABLE history_sync_pending ADD COLUMN base_server_revision INTEGER
             CHECK(base_server_revision IS NULL OR
                 (typeof(base_server_revision) = 'integer'
                  AND base_server_revision BETWEEN 0 AND 9007199254740991));",
        )?;
    }
    let has_payload = {
        let mut query = connection.prepare("PRAGMA table_info(history_sync_pending)")?;
        let columns = query
            .query_map([], |row| row.get::<_, String>(1))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        columns.iter().any(|column| column == "payload_json")
    };
    if !has_payload {
        connection.execute_batch(
            "ALTER TABLE history_sync_pending ADD COLUMN payload_json TEXT
             CHECK(payload_json IS NULL OR
                 (typeof(payload_json) = 'text' AND length(CAST(payload_json AS BLOB)) <= 1048576));",
        )?;
    }
    let has_deleted = {
        let mut query = connection.prepare("PRAGMA table_info(history_sync_pending)")?;
        let columns = query
            .query_map([], |row| row.get::<_, String>(1))?
            .collect::<rusqlite::Result<Vec<_>>>()?;
        columns.iter().any(|column| column == "deleted")
    };
    if !has_deleted {
        connection.execute_batch(
            "ALTER TABLE history_sync_pending ADD COLUMN deleted INTEGER NOT NULL DEFAULT 0
             CHECK(typeof(deleted) = 'integer' AND deleted IN (0, 1));",
        )?;
    }
    connection.execute_batch(
        "CREATE TRIGGER IF NOT EXISTS clear_pending_payload_on_history_delete
         BEFORE DELETE ON history
         BEGIN
             UPDATE history_sync_pending SET payload_json = NULL
             WHERE record_uuid = (
                 SELECT record_uuid FROM history_local_identity WHERE history_id = OLD.id
             );
         END;",
    )?;
    let ambiguous: bool = connection.query_row(
        "SELECT EXISTS(
            SELECT record_uuid FROM history_sync_pending
            GROUP BY record_uuid HAVING COUNT(DISTINCT account_id) > 1
        )",
        [],
        |row| row.get(0),
    )?;
    if ambiguous {
        anyhow::bail!("Ambiguous history sync ownership");
    }
    connection.execute(
        "INSERT INTO history_sync_record_owner(record_uuid, account_id)
         SELECT DISTINCT record_uuid, account_id FROM history_sync_pending
         WHERE true ON CONFLICT(record_uuid) DO NOTHING",
        [],
    )?;
    let inconsistent: bool = connection.query_row(
        "SELECT EXISTS(
            SELECT 1 FROM history_sync_pending AS pending
            JOIN history_sync_record_owner AS owner USING(record_uuid)
            WHERE pending.account_id != owner.account_id
        )",
        [],
        |row| row.get(0),
    )?;
    if inconsistent {
        anyhow::bail!("Ambiguous history sync ownership");
    }
    Ok(())
}

pub fn confirm(
    connection: &mut Connection,
    mutation: &PendingHistoryMutation,
    server_revision: i64,
) -> Result<bool> {
    confirm_with_commit(connection, mutation, server_revision, |transaction| {
        transaction.commit()?;
        Ok(())
    })
}

pub(super) fn confirm_with_commit<'connection>(
    connection: &'connection mut Connection,
    mutation: &PendingHistoryMutation,
    server_revision: i64,
    commit: impl FnOnce(rusqlite::Transaction<'connection>) -> Result<()>,
) -> Result<bool> {
    let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let confirmed = confirm_in_transaction(&transaction, mutation, server_revision)?;
    if confirmed {
        commit(transaction)?;
    }
    Ok(confirmed)
}

pub(super) fn confirm_in_transaction(
    transaction: &rusqlite::Transaction<'_>,
    mutation: &PendingHistoryMutation,
    server_revision: i64,
) -> Result<bool> {
    if !(1..=9007199254740991).contains(&server_revision)
        || !(0..=9007199254740991).contains(&mutation.base_server_revision)
        || server_revision <= mutation.base_server_revision
        || !(1..=9007199254740991).contains(&mutation.client_revision)
        || mutation.account_id.is_empty()
        || mutation.account_id.trim() != mutation.account_id
        || mutation.account_id.len() > 200
        || mutation.account_id.chars().any(char::is_control)
        || [&mutation.record_uuid, &mutation.mutation_uuid]
            .iter()
            .any(|value| !valid_sync_uuid(value))
    {
        anyhow::bail!("Invalid history sync confirmation");
    }
    let invalidated: bool = transaction.query_row(
        "SELECT EXISTS(SELECT 1 FROM history_identity_state WHERE rebuild_required = 1)",
        [],
        |row| row.get(0),
    )?;
    if invalidated {
        anyhow::bail!("History sync queue unavailable");
    }
    let pending: Option<(Option<String>, Option<i64>, bool, bool)> = transaction
        .query_row(
            "SELECT pending.payload_json, identity.local_revision, history.id IS NOT NULL,
                    pending.deleted
             FROM history_sync_pending AS pending
             JOIN history_sync_record_owner AS owner USING(record_uuid)
             LEFT JOIN history_local_identity AS identity USING(record_uuid)
             LEFT JOIN history ON history.id = identity.history_id
             WHERE pending.account_id = ?1 AND owner.account_id = ?1
               AND pending.record_uuid = ?2 AND pending.mutation_uuid = ?3
               AND pending.client_revision = ?4
               AND pending.base_server_revision = ?5",
            rusqlite::params![
                mutation.account_id,
                mutation.record_uuid,
                mutation.mutation_uuid,
                mutation.client_revision,
                mutation.base_server_revision
            ],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?, row.get(3)?)),
        )
        .optional()?;
    let Some((payload, local_revision, history_exists, deleted)) = pending else {
        return Ok(false);
    };
    if deleted {
        if history_exists || payload.is_some() || local_revision.is_some() {
            return Ok(false);
        }
    } else if history_exists {
        if local_revision != Some(mutation.client_revision) {
            return Ok(false);
        }
        let Some(payload) = payload else {
            return Ok(false);
        };
        validate_stored_payload(&payload)?;
    } else {
        return Ok(false);
    }
    let previous: Option<(String, i64, i64)> = transaction
        .query_row(
            "SELECT account_id, client_revision, server_revision
             FROM history_sync_confirmed WHERE record_uuid = ?1",
            [&mutation.record_uuid],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    let remote = remote_baseline(transaction, &mutation.record_uuid)?;
    let resolution: Option<(i64, i64)> = transaction
        .query_row(
            "SELECT previous_base, approved_base FROM history_sync_resolution_base
             WHERE account_id = ?1 AND record_uuid = ?2 AND client_revision = ?3
               AND mutation_uuid = ?4",
            rusqlite::params![
                mutation.account_id,
                mutation.record_uuid,
                mutation.client_revision,
                mutation.mutation_uuid
            ],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let expected_known_base = resolution
        .map(|(previous, _)| previous)
        .unwrap_or(mutation.base_server_revision);
    if resolution.is_some_and(|(_, approved)| approved != mutation.base_server_revision) {
        anyhow::bail!("History sync resolution baseline changed");
    }
    if remote.as_ref().is_some_and(|(account, local, server)| {
        account != &mutation.account_id
            || *local >= mutation.client_revision
            || *server != expected_known_base
            || previous
                .as_ref()
                .is_some_and(|confirmed| confirmed != &(account.clone(), *local, *server))
    }) {
        anyhow::bail!("History sync confirmation baseline changed");
    }
    let known_base = remote
        .as_ref()
        .or(previous.as_ref())
        .map(|(_, _, server)| *server)
        .unwrap_or(0);
    if known_base != expected_known_base {
        anyhow::bail!("History sync confirmation baseline changed");
    }
    if previous.is_some_and(|(account, client, server)| {
        account != mutation.account_id
            || client >= mutation.client_revision
            || server >= server_revision
    }) {
        anyhow::bail!("Invalid history sync confirmation");
    }
    let confirmed_written = transaction.execute(
        "INSERT INTO history_sync_confirmed
         (record_uuid, account_id, mutation_uuid, client_revision, server_revision)
         VALUES (?1, ?2, ?3, ?4, ?5)
         ON CONFLICT(record_uuid) DO UPDATE SET
             account_id = excluded.account_id, mutation_uuid = excluded.mutation_uuid,
             client_revision = excluded.client_revision, server_revision = excluded.server_revision",
        rusqlite::params![
            mutation.record_uuid, mutation.account_id, mutation.mutation_uuid,
            mutation.client_revision, server_revision
        ],
    )?;
    if confirmed_written != 1 {
        anyhow::bail!("History sync confirmation unavailable");
    }
    if let Some((account, local, server)) = remote {
        let changed = transaction.execute(
            "UPDATE history_sync_remote_baseline SET local_revision = ?1, server_revision = ?2
             WHERE record_uuid = ?3 AND account_id = ?4 AND local_revision = ?5 AND server_revision = ?6",
            rusqlite::params![
                mutation.client_revision, server_revision, mutation.record_uuid, account, local, server
            ],
        )?;
        if changed != 1 {
            anyhow::bail!("History sync confirmation baseline changed");
        }
    } else {
        transaction.execute_batch(
            "CREATE TABLE IF NOT EXISTS history_sync_remote_baseline (
                record_uuid TEXT PRIMARY KEY,
                account_id TEXT NOT NULL,
                local_revision INTEGER NOT NULL CHECK(local_revision BETWEEN 1 AND 9007199254740991),
                server_revision INTEGER NOT NULL CHECK(server_revision BETWEEN 1 AND 9007199254740991)
            );",
        )?;
        let inserted = transaction.execute(
            "INSERT INTO history_sync_remote_baseline
             (record_uuid, account_id, local_revision, server_revision) VALUES (?1, ?2, ?3, ?4)",
            rusqlite::params![
                mutation.record_uuid,
                mutation.account_id,
                mutation.client_revision,
                server_revision
            ],
        )?;
        if inserted != 1 {
            anyhow::bail!("History sync confirmation baseline changed");
        }
    }
    let pending_deleted = transaction.execute(
        "DELETE FROM history_sync_pending
         WHERE account_id = ?1 AND record_uuid = ?2 AND mutation_uuid = ?3 AND client_revision = ?4",
        rusqlite::params![
            mutation.account_id, mutation.record_uuid, mutation.mutation_uuid,
            mutation.client_revision
        ],
    )?;
    if pending_deleted != 1 {
        anyhow::bail!("History sync confirmation unavailable");
    }
    transaction.execute(
        "DELETE FROM history_sync_pending
         WHERE account_id = ?1 AND record_uuid = ?2 AND client_revision < ?3",
        rusqlite::params![
            mutation.account_id,
            mutation.record_uuid,
            mutation.client_revision
        ],
    )?;
    Ok(true)
}

pub fn enqueue(
    connection: &mut Connection,
    account_id: &str,
    history_id: i64,
    expected_uuid: &str,
    expected_revision: i64,
) -> Result<PendingHistoryMutation> {
    enqueue_with_commit(
        connection,
        account_id,
        history_id,
        expected_uuid,
        expected_revision,
        |transaction| {
            transaction.commit()?;
            Ok(())
        },
    )
}

pub(super) fn enqueue_with_commit<'connection>(
    connection: &'connection mut Connection,
    account_id: &str,
    history_id: i64,
    expected_uuid: &str,
    expected_revision: i64,
    commit: impl FnOnce(rusqlite::Transaction<'connection>) -> Result<()>,
) -> Result<PendingHistoryMutation> {
    if account_id.is_empty()
        || account_id.trim() != account_id
        || account_id.len() > 200
        || account_id.chars().any(char::is_control)
        || !(1..=9007199254740991).contains(&expected_revision)
    {
        anyhow::bail!("Invalid history sync queue request");
    }
    let transaction = connection.transaction_with_behavior(TransactionBehavior::Immediate)?;
    let rebuild_required: bool = transaction.query_row(
        "SELECT EXISTS(SELECT 1 FROM history_identity_state WHERE rebuild_required = 1)",
        [],
        |row| row.get(0),
    )?;
    if rebuild_required {
        anyhow::bail!("History sync queue unavailable");
    }
    let identity: Option<(String, i64)> = transaction
        .query_row(
            "SELECT record_uuid, local_revision FROM history_local_identity
             JOIN history ON history.id = history_local_identity.history_id
             WHERE history.id = ?1",
            [history_id],
            |row| Ok((row.get(0)?, row.get(1)?)),
        )
        .optional()?;
    let Some((record_uuid, revision)) = identity else {
        anyhow::bail!("History sync queue source changed");
    };
    if record_uuid != expected_uuid
        || revision != expected_revision
        || !valid_sync_uuid(&record_uuid)
    {
        anyhow::bail!("History sync queue source changed");
    }
    let remote = remote_baseline(&transaction, &record_uuid)?;
    if remote
        .as_ref()
        .is_some_and(|(account, local, _)| account == account_id && *local == revision)
    {
        anyhow::bail!("History revision already confirmed");
    }
    if remote
        .as_ref()
        .is_some_and(|(account, local, _)| account != account_id || *local >= revision)
    {
        anyhow::bail!("History sync remote baseline is not an unconfirmed edit");
    }
    transaction.execute(
        "INSERT INTO history_sync_record_owner(record_uuid, account_id) VALUES (?1, ?2)
         ON CONFLICT(record_uuid) DO NOTHING",
        rusqlite::params![record_uuid, account_id],
    )?;
    let owner: String = transaction.query_row(
        "SELECT account_id FROM history_sync_record_owner WHERE record_uuid = ?1",
        [&record_uuid],
        |row| row.get(0),
    )?;
    if owner != account_id {
        anyhow::bail!("History sync record belongs to another account");
    }
    let confirmed: Option<(String, i64, i64)> = transaction
        .query_row(
            "SELECT account_id, client_revision, server_revision FROM history_sync_confirmed WHERE record_uuid = ?1",
            [&record_uuid],
            |row| Ok((row.get(0)?, row.get(1)?, row.get(2)?)),
        )
        .optional()?;
    let mut base_server_revision = 0;
    if let Some((account, local, server)) = &remote {
        if confirmed
            .as_ref()
            .is_some_and(|confirmed| confirmed != &(account.clone(), *local, *server))
        {
            anyhow::bail!("History sync confirmation baseline changed");
        }
        base_server_revision = *server;
    }
    if let Some((confirmed_account, confirmed_revision, server_revision)) = confirmed {
        if confirmed_account != account_id
            || !(1..=9007199254740991).contains(&confirmed_revision)
            || confirmed_revision > revision
            || !(1..=9007199254740991).contains(&server_revision)
        {
            anyhow::bail!("Invalid history sync confirmation metadata");
        }
        if confirmed_revision == revision {
            anyhow::bail!("History revision already confirmed");
        }
        base_server_revision = server_revision;
    }
    let existing: bool = transaction.query_row(
        "SELECT EXISTS(
            SELECT 1 FROM history_sync_pending
            WHERE account_id = ?1 AND record_uuid = ?2 AND client_revision = ?3
        )",
        rusqlite::params![account_id, record_uuid, revision],
        |row| row.get(0),
    )?;
    if !existing {
        let count: i64 =
            transaction.query_row("SELECT COUNT(*) FROM history_sync_pending", [], |row| {
                row.get(0)
            })?;
        if count >= 5000 {
            anyhow::bail!("History sync queue capacity reached");
        }
    }
    let payload_json = if existing {
        let payload: Option<String> = transaction.query_row(
            "SELECT payload_json FROM history_sync_pending
             WHERE account_id = ?1 AND record_uuid = ?2 AND client_revision = ?3",
            rusqlite::params![account_id, record_uuid, revision],
            |row| row.get(0),
        )?;
        let payload =
            payload.ok_or_else(|| anyhow::anyhow!("History sync queue payload unknown"))?;
        validate_stored_payload(&payload)?;
        payload
    } else {
        let payload = capture_payload(&transaction, history_id)?;
        let retained_bytes: i64 = transaction.query_row(
            "SELECT COALESCE(SUM(length(CAST(payload_json AS BLOB))), 0) FROM history_sync_pending",
            [],
            |row| row.get(0),
        )?;
        if retained_bytes < 0 || retained_bytes > 16 * 1024 * 1024 - payload.len() as i64 {
            anyhow::bail!("History sync queue byte capacity reached");
        }
        payload
    };
    transaction.execute(
        "INSERT INTO history_sync_pending(account_id, record_uuid, mutation_uuid, client_revision, base_server_revision, payload_json, deleted)
         VALUES (?1, ?2, ?3, ?4, ?5, ?6, 0)
         ON CONFLICT(account_id, record_uuid, client_revision) DO NOTHING",
        rusqlite::params![
            account_id,
            record_uuid,
            uuid::Uuid::new_v4().to_string(),
            revision,
            base_server_revision,
            payload_json
        ],
    )?;
    let (mutation_uuid, pinned_base): (String, Option<i64>) = transaction.query_row(
        "SELECT mutation_uuid, base_server_revision FROM history_sync_pending
         WHERE account_id = ?1 AND record_uuid = ?2 AND client_revision = ?3",
        rusqlite::params![account_id, record_uuid, revision],
        |row| Ok((row.get(0)?, row.get(1)?)),
    )?;
    let Some(pinned_base) = pinned_base else {
        anyhow::bail!("History sync queue baseline unknown");
    };
    if !(0..=9007199254740991).contains(&pinned_base) {
        anyhow::bail!("Invalid history sync queue metadata");
    }
    commit(transaction)?;
    Ok(PendingHistoryMutation {
        account_id: account_id.to_owned(),
        record_uuid,
        mutation_uuid,
        client_revision: revision,
        base_server_revision: pinned_base,
    })
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn legacy_pending_rows_migrate_to_non_deleted_and_deleted_flag_is_constrained() {
        let connection = Connection::open_in_memory().unwrap();
        connection
            .execute_batch(
                "CREATE TABLE history (
                   id INTEGER PRIMARY KEY,
                   raw_text TEXT NOT NULL DEFAULT '',
                   polished_text TEXT NOT NULL DEFAULT ''
                 );
                 CREATE TABLE history_local_identity (
                   history_id INTEGER PRIMARY KEY REFERENCES history(id) ON DELETE CASCADE,
                   record_uuid TEXT NOT NULL UNIQUE,
                   local_revision INTEGER NOT NULL DEFAULT 1
                 );
                 CREATE TABLE history_sync_pending (
                   account_id TEXT NOT NULL,
                   record_uuid TEXT NOT NULL,
                   mutation_uuid TEXT NOT NULL UNIQUE,
                   client_revision INTEGER NOT NULL,
                   base_server_revision INTEGER,
                   payload_json TEXT,
                   PRIMARY KEY(account_id, record_uuid, client_revision)
                 );
                 INSERT INTO history_sync_pending VALUES (
                   'synthetic-account',
                   '11111111-1111-4111-8111-111111111111',
                   '22222222-2222-4222-8222-222222222222',
                   1, 0, '{}'
                 );",
            )
            .unwrap();
        initialize(&connection).unwrap();
        let deleted: i64 = connection
            .query_row("SELECT deleted FROM history_sync_pending", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(deleted, 0);
        assert!(connection
            .execute("UPDATE history_sync_pending SET deleted = 2", [])
            .is_err());
    }
}
