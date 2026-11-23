use serde::{Deserialize, Serialize};

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct ConflictSummary {
    pub record_id: String,
    pub mutation_id: String,
    pub client_revision: i64,
    pub server_revision: i64,
    pub result: String,
}

#[derive(Debug, Serialize)]
#[serde(rename_all = "camelCase")]
pub(crate) struct LocalConflictRecord {
    pub record_id: String,
    pub client_revision: i64,
    pub deleted: bool,
    pub payload: serde_json::Value,
}

#[derive(Debug, Deserialize, Serialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub(crate) struct RemoteRecord {
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

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct Response {
    version: u8,
    account_id: String,
    #[serde(deserialize_with = "deserialize_record")]
    record: Option<RemoteRecord>,
}

fn deserialize_record<'de, D>(deserializer: D) -> Result<Option<RemoteRecord>, D::Error>
where
    D: serde::Deserializer<'de>,
{
    Option::<RemoteRecord>::deserialize(deserializer)
}

pub(crate) fn parse_record(
    bytes: &[u8],
    account_id: &str,
    record_id: &str,
) -> anyhow::Result<Option<RemoteRecord>> {
    if bytes.len() > 2 * 1024 * 1024
        || account_id.is_empty()
        || account_id.trim() != account_id
        || account_id.len() > 200
        || account_id.chars().any(char::is_control)
        || !super::history_sync_queue::valid_sync_uuid(record_id)
    {
        anyhow::bail!("Invalid history sync record");
    }
    let response: Response = serde_json::from_slice(bytes)?;
    if response.version != 1 || response.account_id != account_id {
        anyhow::bail!("Invalid history sync record");
    }
    let Some(mut record) = response.record else {
        return Ok(None);
    };
    if !super::history_sync_queue::valid_sync_uuid(&record.record_id)
        || !record.record_id.eq_ignore_ascii_case(record_id)
        || !(1..=9007199254740991).contains(&record.server_revision)
    {
        anyhow::bail!("Invalid history sync record");
    }
    if record.deleted {
        if !record.payload.is_null() {
            anyhow::bail!("Invalid history sync record");
        }
    } else {
        super::history_sync_queue::validate_stored_payload(&serde_json::to_string(
            &record.payload,
        )?)?;
    }
    record.record_id.make_ascii_lowercase();
    Ok(Some(record))
}

#[cfg(test)]
mod tests {
    const RECORD: &str = "11111111-1111-4111-8111-111111111111";

    fn response() -> serde_json::Value {
        serde_json::json!({
            "version": 1,
            "accountId": "synthetic-account",
            "record": {
                "recordId": RECORD, "serverRevision": 2, "deleted": false,
                "payload": {
                    "rawText": "Synthetic", "polishedText": "", "mode": "dictate",
                    "answerText": null, "createdAt": "2026-11-20T00:00:00",
                    "durationMs": null, "language": null
                }
            }
        })
    }

    #[test]
    fn rejects_duplicate_remote_payload_fields_before_normalization() {
        let serialized = response().to_string();
        let duplicated = serialized.replace(
            "\"rawText\":\"Synthetic\"",
            "\"rawText\":\"Unexpected\",\"rawText\":\"Synthetic\"",
        );
        assert_ne!(serialized, duplicated);
        assert!(super::parse_record(duplicated.as_bytes(), "synthetic-account", RECORD).is_err());
    }

    #[test]
    fn remote_record_matches_requested_account_identity_and_payload() {
        let record = super::parse_record(
            &serde_json::to_vec(&response()).unwrap(),
            "synthetic-account",
            RECORD,
        )
        .unwrap()
        .unwrap();
        assert_eq!(record.record_id, RECORD);
        assert_eq!(record.server_revision, 2);
        assert_eq!(record.payload["rawText"], "Synthetic");
    }

    #[test]
    fn missing_and_deleted_records_are_distinct() {
        let mut value = response();
        value["record"] = serde_json::Value::Null;
        assert!(super::parse_record(
            &serde_json::to_vec(&value).unwrap(),
            "synthetic-account",
            RECORD,
        )
        .unwrap()
        .is_none());
        value = response();
        value["record"]["deleted"] = serde_json::json!(true);
        value["record"]["payload"] = serde_json::Value::Null;
        let deleted = super::parse_record(
            &serde_json::to_vec(&value).unwrap(),
            "synthetic-account",
            RECORD,
        )
        .unwrap()
        .unwrap();
        assert!(deleted.deleted);
        assert!(deleted.payload.is_null());
    }

    #[test]
    fn refuses_foreign_stale_malformed_and_oversized_remote_records() {
        for field in [
            "account", "record", "revision", "payload", "deleted", "extra",
        ] {
            let mut value = response();
            match field {
                "account" => value["accountId"] = serde_json::json!("other-account"),
                "record" => {
                    value["record"]["recordId"] =
                        serde_json::json!("22222222-2222-4222-8222-222222222222")
                }
                "revision" => value["record"]["serverRevision"] = serde_json::json!(0),
                "payload" => value["record"]["payload"]["unexpected"] = serde_json::json!(true),
                "deleted" => value["record"]["deleted"] = serde_json::json!(true),
                _ => value["extra"] = serde_json::json!(true),
            }
            assert!(
                super::parse_record(
                    &serde_json::to_vec(&value).unwrap(),
                    "synthetic-account",
                    RECORD,
                )
                .is_err(),
                "{field}"
            );
        }
        assert!(super::parse_record(
            &vec![b' '; 2 * 1024 * 1024 + 1],
            "synthetic-account",
            RECORD
        )
        .is_err());
    }
}
