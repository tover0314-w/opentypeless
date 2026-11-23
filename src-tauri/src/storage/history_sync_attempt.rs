use anyhow::Result;
use rusqlite::{Connection, OptionalExtension};

#[derive(Debug, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct Attempt {
    pub request_id: String,
    pub status: String,
    pub result: Option<serde_json::Value>,
    #[serde(skip_serializing_if = "is_false")]
    pub acknowledged: bool,
}

fn is_false(value: &bool) -> bool {
    !value
}

pub(super) fn initialize(connection: &Connection) -> Result<()> {
    connection.execute_batch(
        "CREATE TABLE IF NOT EXISTS history_sync_attempt (
            account_id TEXT NOT NULL,
            endpoint TEXT NOT NULL,
            request_id TEXT NOT NULL,
            status TEXT NOT NULL CHECK(status IN ('running', 'confirmed', 'unknown')),
            result TEXT CHECK(result IS NULL OR length(CAST(result AS BLOB)) <= 1024),
            PRIMARY KEY(account_id, endpoint)
        );",
    )?;
    let has_acknowledged = connection
        .prepare("PRAGMA table_info(history_sync_attempt)")?
        .query_map([], |row| row.get::<_, String>(1))?
        .collect::<rusqlite::Result<Vec<_>>>()?
        .iter()
        .any(|column| column == "acknowledged");
    if !has_acknowledged {
        connection.execute_batch(
            "ALTER TABLE history_sync_attempt ADD COLUMN acknowledged INTEGER NOT NULL DEFAULT 0
             CHECK(acknowledged IN (0, 1));",
        )?;
    }
    Ok(())
}

fn validate_identity(account: &str, endpoint: &str, request: &str) -> Result<()> {
    let url = reqwest::Url::parse(endpoint)?;
    if account.is_empty()
        || account.trim() != account
        || account.len() > 200
        || account.chars().any(char::is_control)
        || url.scheme() != "https"
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/api/history-sync/status"
        || uuid::Uuid::parse_str(request)?.get_version_num() != 4
    {
        anyhow::bail!("History sync attempt identity unavailable");
    }
    Ok(())
}

pub(super) fn begin(
    connection: &Connection,
    account: &str,
    endpoint: &str,
    request: &str,
) -> Result<()> {
    validate_identity(account, endpoint, request)?;
    let transaction =
        rusqlite::Transaction::new_unchecked(connection, rusqlite::TransactionBehavior::Immediate)?;
    if let Some(attempt) = read(&transaction, account, endpoint)? {
        if attempt.status != "confirmed" && !attempt.acknowledged {
            anyhow::bail!("Previous history sync result is unresolved");
        }
    }
    let changed = transaction.execute(
        "INSERT INTO history_sync_attempt(account_id, endpoint, request_id, status, result)
         VALUES (?1, ?2, ?3, 'running', NULL)
         ON CONFLICT(account_id, endpoint) DO UPDATE
         SET request_id = excluded.request_id, status = 'running', result = NULL, acknowledged = 0
         WHERE history_sync_attempt.status = 'confirmed' OR history_sync_attempt.acknowledged = 1",
        rusqlite::params![account, endpoint, request],
    )?;
    if changed != 1 {
        anyhow::bail!("Previous history sync result is unresolved");
    }
    transaction.commit()?;
    Ok(())
}

#[cfg(test)]
fn acknowledge(
    connection: &Connection,
    account: &str,
    endpoint: &str,
    request: &str,
) -> Result<()> {
    acknowledge_with_commit(connection, account, endpoint, request, |transaction| {
        transaction.commit()?;
        Ok(())
    })
}

pub(super) fn acknowledge_with_commit(
    connection: &Connection,
    account: &str,
    endpoint: &str,
    request: &str,
    commit: impl FnOnce(rusqlite::Transaction<'_>) -> Result<()>,
) -> Result<()> {
    validate_identity(account, endpoint, request)?;
    let transaction =
        rusqlite::Transaction::new_unchecked(connection, rusqlite::TransactionBehavior::Immediate)?;
    let attempt = read(&transaction, account, endpoint)?
        .ok_or_else(|| anyhow::anyhow!("History sync attempt unavailable"))?;
    if attempt.request_id != request || !matches!(attempt.status.as_str(), "running" | "unknown") {
        anyhow::bail!("History sync attempt acknowledgement superseded");
    }
    let changed = transaction.execute(
        "UPDATE history_sync_attempt SET status = 'unknown', acknowledged = 1
         WHERE account_id = ?1 AND endpoint = ?2 AND request_id = ?3 AND acknowledged = 0",
        rusqlite::params![account, endpoint, request],
    )?;
    if changed != 1 {
        anyhow::bail!("History sync attempt acknowledgement superseded");
    }
    commit(transaction)
}

fn validate_result(result: &serde_json::Value) -> Result<()> {
    let fields = result
        .as_object()
        .ok_or_else(|| anyhow::anyhow!("Invalid history sync result"))?;
    if fields.len() != 4
        || !result["hasMore"].is_boolean()
        || result["pushed"].as_u64().is_none_or(|count| count > 200)
        || result["pulled"].as_u64().is_none_or(|count| count > 500)
        || result["pending"].as_u64().is_none_or(|count| count > 5000)
    {
        anyhow::bail!("Invalid history sync result");
    }
    Ok(())
}

pub(super) fn finish(
    connection: &Connection,
    account: &str,
    endpoint: &str,
    request: &str,
    result: Option<&serde_json::Value>,
) -> Result<()> {
    validate_identity(account, endpoint, request)?;
    if let Some(value) = result {
        validate_result(value)?;
    }
    let serialized = result.map(serde_json::to_string).transpose()?;
    let changed = connection.execute(
        "UPDATE history_sync_attempt SET status = ?4, result = ?5
         WHERE account_id = ?1 AND endpoint = ?2 AND request_id = ?3 AND status = 'running'",
        rusqlite::params![
            account,
            endpoint,
            request,
            if result.is_some() {
                "confirmed"
            } else {
                "unknown"
            },
            serialized
        ],
    )?;
    if changed != 1 {
        anyhow::bail!("History sync attempt completion superseded");
    }
    Ok(())
}

pub(super) fn read(
    connection: &Connection,
    account: &str,
    endpoint: &str,
) -> Result<Option<Attempt>> {
    let saved: Option<(String, String, Option<String>, i64, bool)> = connection
        .query_row(
            "SELECT request_id, status,
                CASE WHEN length(CAST(result AS BLOB)) <= 1024 THEN result ELSE NULL END,
                acknowledged,
                result IS NOT NULL AND length(CAST(result AS BLOB)) > 1024
             FROM history_sync_attempt
         WHERE account_id = ?1 AND endpoint = ?2",
            rusqlite::params![account, endpoint],
            |row| {
                Ok((
                    row.get(0)?,
                    row.get(1)?,
                    row.get(2)?,
                    row.get(3)?,
                    row.get(4)?,
                ))
            },
        )
        .optional()?;
    let Some((request_id, status, serialized, acknowledged, oversized)) = saved else {
        return Ok(None);
    };
    if oversized {
        anyhow::bail!("History sync persisted result exceeds limit");
    }
    validate_identity(account, endpoint, &request_id)?;
    if !matches!(acknowledged, 0 | 1) {
        anyhow::bail!("Invalid history sync acknowledgement flag");
    }
    let acknowledged = acknowledged == 1;
    if acknowledged && status != "unknown" {
        anyhow::bail!("Invalid acknowledged history sync attempt");
    }
    let result = serialized
        .map(|text| serde_json::from_str(&text))
        .transpose()?;
    if status == "confirmed" {
        validate_result(
            result
                .as_ref()
                .ok_or_else(|| anyhow::anyhow!("Missing history sync result"))?,
        )?;
    } else if !matches!(status.as_str(), "running" | "unknown") || result.is_some() {
        anyhow::bail!("Invalid history sync attempt state");
    }
    Ok(Some(Attempt {
        request_id,
        status,
        result,
        acknowledged,
    }))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn oversized_persisted_result_is_rejected_without_replacing_the_attempt() {
        let connection = Connection::open_in_memory().unwrap();
        initialize(&connection).unwrap();
        let request = uuid::Uuid::new_v4().to_string();
        let endpoint = "https://example.invalid/api/history-sync/status";
        begin(&connection, "account-a", endpoint, &request).unwrap();
        let result = format!(
            "{}{}",
            " ".repeat(1024),
            serde_json::json!({"pushed":0,"pulled":0,"hasMore":false,"pending":0})
        );
        connection
            .execute_batch("PRAGMA ignore_check_constraints=ON;")
            .unwrap();
        connection
            .execute(
                "UPDATE history_sync_attempt SET status = 'confirmed', result = ?1",
                [&result],
            )
            .unwrap();
        connection
            .execute_batch("PRAGMA ignore_check_constraints=OFF;")
            .unwrap();
        assert!(read(&connection, "account-a", endpoint).is_err());
        assert!(begin(
            &connection,
            "account-a",
            endpoint,
            &uuid::Uuid::new_v4().to_string(),
        )
        .is_err());
        let retained: String = connection
            .query_row("SELECT result FROM history_sync_attempt", [], |row| {
                row.get(0)
            })
            .unwrap();
        assert_eq!(retained, result);
    }

    #[test]
    fn corrupted_acknowledgement_flag_never_unlocks_or_replaces_an_attempt() {
        for corrupt in ["2", "-1", "1.5", "'yes'"] {
            let connection = Connection::open_in_memory().unwrap();
            initialize(&connection).unwrap();
            let request = uuid::Uuid::new_v4().to_string();
            let endpoint = "https://example.invalid/api/history-sync/status";
            begin(&connection, "account-a", endpoint, &request).unwrap();
            finish(&connection, "account-a", endpoint, &request, None).unwrap();
            connection
                .execute_batch(&format!(
                    "PRAGMA ignore_check_constraints=ON;
                     UPDATE history_sync_attempt SET acknowledged = {corrupt};
                     PRAGMA ignore_check_constraints=OFF;"
                ))
                .unwrap();
            assert!(read(&connection, "account-a", endpoint).is_err());
            assert!(begin(
                &connection,
                "account-a",
                endpoint,
                &uuid::Uuid::new_v4().to_string(),
            )
            .is_err());
            let retained: String = connection
                .query_row("SELECT request_id FROM history_sync_attempt", [], |row| {
                    row.get(0)
                })
                .unwrap();
            assert_eq!(retained, request);
        }
    }

    #[test]
    fn explicit_acknowledgement_releases_only_the_matching_unresolved_attempt() {
        let connection = Connection::open_in_memory().unwrap();
        initialize(&connection).unwrap();
        let endpoint = "https://example.invalid/api/history-sync/status";
        let request = uuid::Uuid::new_v4().to_string();
        begin(&connection, "account-a", endpoint, &request).unwrap();
        finish(&connection, "account-a", endpoint, &request, None).unwrap();
        assert!(acknowledge(
            &connection,
            "account-a",
            endpoint,
            &uuid::Uuid::new_v4().to_string(),
        )
        .is_err());
        assert!(acknowledge(&connection, "account-b", endpoint, &request).is_err());
        acknowledge(&connection, "account-a", endpoint, &request).unwrap();
        let saved = read(&connection, "account-a", endpoint).unwrap().unwrap();
        assert_eq!(saved.status, "unknown");
        assert!(saved.acknowledged);
        assert_eq!(serde_json::to_value(&saved).unwrap()["acknowledged"], true);
        assert!(saved.result.is_none());
        let next = uuid::Uuid::new_v4().to_string();
        begin(&connection, "account-a", endpoint, &next).unwrap();
        assert!(acknowledge(&connection, "account-a", endpoint, &request).is_err());
        assert!(begin(
            &connection,
            "account-a",
            endpoint,
            &uuid::Uuid::new_v4().to_string(),
        )
        .is_err());
    }

    #[test]
    fn corrupted_confirmed_result_cannot_be_overwritten_by_a_new_attempt() {
        for corrupt in ["not-json", "null", "{\"pushed\":999}", "{}"] {
            let connection = Connection::open_in_memory().unwrap();
            initialize(&connection).unwrap();
            let request = uuid::Uuid::new_v4().to_string();
            let endpoint = "https://example.invalid/api/history-sync/status";
            begin(&connection, "account-a", endpoint, &request).unwrap();
            connection
                .execute(
                    "UPDATE history_sync_attempt SET status = 'confirmed', result = ?1",
                    [corrupt],
                )
                .unwrap();
            assert!(begin(
                &connection,
                "account-a",
                endpoint,
                &uuid::Uuid::new_v4().to_string(),
            )
            .is_err());
            let retained: (String, String) = connection
                .query_row(
                    "SELECT request_id, result FROM history_sync_attempt",
                    [],
                    |row| Ok((row.get(0)?, row.get(1)?)),
                )
                .unwrap();
            assert_eq!(retained, (request, corrupt.to_string()));
        }
    }

    #[test]
    fn interrupted_attempt_survives_reopen_and_blocks_a_new_session() {
        let path =
            std::env::temp_dir().join(format!("sync-attempt-{}.sqlite", uuid::Uuid::new_v4()));
        let request = uuid::Uuid::new_v4().to_string();
        {
            let connection = rusqlite::Connection::open(&path).unwrap();
            initialize(&connection).unwrap();
            begin(
                &connection,
                "account-a",
                "https://example.invalid/api/history-sync/status",
                &request,
            )
            .unwrap();
        }
        let connection = rusqlite::Connection::open(&path).unwrap();
        initialize(&connection).unwrap();
        assert!(begin(
            &connection,
            "account-a",
            "https://example.invalid/api/history-sync/status",
            &uuid::Uuid::new_v4().to_string()
        )
        .is_err());
        assert!(read(
            &connection,
            "account-b",
            "https://example.invalid/api/history-sync/status"
        )
        .unwrap()
        .is_none());
        assert_eq!(
            read(
                &connection,
                "account-a",
                "https://example.invalid/api/history-sync/status"
            )
            .unwrap()
            .unwrap()
            .request_id,
            request
        );
        drop(connection);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn only_matching_completion_can_replace_running_attempt() {
        let connection = rusqlite::Connection::open_in_memory().unwrap();
        initialize(&connection).unwrap();
        let request = uuid::Uuid::new_v4().to_string();
        let endpoint = "https://example.invalid/api/history-sync/status";
        begin(&connection, "account-a", endpoint, &request).unwrap();
        let result = serde_json::json!({"pushed":1,"pulled":2,"hasMore":false,"pending":0});
        assert!(finish(
            &connection,
            "account-a",
            endpoint,
            &uuid::Uuid::new_v4().to_string(),
            Some(&result)
        )
        .is_err());
        finish(&connection, "account-a", endpoint, &request, Some(&result)).unwrap();
        assert_eq!(
            read(&connection, "account-a", endpoint)
                .unwrap()
                .unwrap()
                .result,
            Some(result)
        );
        begin(
            &connection,
            "account-a",
            endpoint,
            &uuid::Uuid::new_v4().to_string(),
        )
        .unwrap();
    }

    #[test]
    fn unknown_completion_blocks_retry_and_never_retains_private_error_text() {
        let connection = rusqlite::Connection::open_in_memory().unwrap();
        initialize(&connection).unwrap();
        let request = uuid::Uuid::new_v4().to_string();
        let endpoint = "https://example.invalid/api/history-sync/status";
        begin(&connection, "account-a", endpoint, &request).unwrap();
        finish(&connection, "account-a", endpoint, &request, None).unwrap();
        let saved = read(&connection, "account-a", endpoint).unwrap().unwrap();
        assert_eq!(saved.status, "unknown");
        assert!(saved.result.is_none());
        assert!(begin(
            &connection,
            "account-a",
            endpoint,
            &uuid::Uuid::new_v4().to_string()
        )
        .is_err());
    }
}
