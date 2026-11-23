use super::history_sync_queue::PendingHistoryMutation;
use serde::Deserialize;

#[derive(Debug, Deserialize, PartialEq, Eq)]
#[serde(rename_all = "lowercase")]
pub enum AckResult {
    Accepted,
    Conflict,
    Rejected,
}

#[derive(Debug, Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct HistorySyncAck {
    pub record_id: String,
    pub mutation_id: String,
    pub client_revision: i64,
    pub result: AckResult,
    pub server_revision: i64,
}

#[derive(Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
struct AckResponse {
    version: u8,
    account_id: String,
    acknowledgements: Vec<HistorySyncAck>,
}

pub fn parse_acknowledgements(
    bytes: &[u8],
    account_id: &str,
    sent: &[PendingHistoryMutation],
) -> anyhow::Result<Vec<HistorySyncAck>> {
    let invalid = || anyhow::anyhow!("Invalid history sync acknowledgement");
    if bytes.len() > 256 * 1024
        || account_id.is_empty()
        || account_id.trim() != account_id
        || account_id.len() > 200
        || account_id.chars().any(char::is_control)
        || sent.len() > 200
    {
        return Err(invalid());
    }
    let mut mutations = std::collections::HashMap::new();
    let mut records = std::collections::HashSet::new();
    for mutation in sent {
        if mutation.account_id != account_id
            || !super::history_sync_queue::valid_sync_uuid(&mutation.record_uuid)
            || !super::history_sync_queue::valid_sync_uuid(&mutation.mutation_uuid)
            || !(1..=9007199254740991).contains(&mutation.client_revision)
            || !(0..=9007199254740991).contains(&mutation.base_server_revision)
            || !records.insert(mutation.record_uuid.to_ascii_lowercase())
            || mutations
                .insert(mutation.mutation_uuid.to_ascii_lowercase(), mutation)
                .is_some()
        {
            return Err(invalid());
        }
    }
    let mut response: AckResponse = serde_json::from_slice(bytes).map_err(|_| invalid())?;
    if response.version != 1
        || response.account_id != account_id
        || response.acknowledgements.len() > sent.len()
    {
        return Err(invalid());
    }
    let mut acknowledged = std::collections::HashSet::new();
    for ack in &mut response.acknowledgements {
        if !super::history_sync_queue::valid_sync_uuid(&ack.record_id)
            || !super::history_sync_queue::valid_sync_uuid(&ack.mutation_id)
            || !(0..=9007199254740991).contains(&ack.server_revision)
        {
            return Err(invalid());
        }
        ack.record_id.make_ascii_lowercase();
        ack.mutation_id.make_ascii_lowercase();
        let mutation = mutations.get(&ack.mutation_id).ok_or_else(invalid)?;
        if !ack.record_id.eq_ignore_ascii_case(&mutation.record_uuid)
            || ack.client_revision != mutation.client_revision
            || !acknowledged.insert(ack.mutation_id.clone())
            || (ack.result == AckResult::Accepted
                && ack.server_revision <= mutation.base_server_revision)
        {
            return Err(invalid());
        }
    }
    Ok(response.acknowledgements)
}

#[cfg(test)]
mod tests {
    use super::*;

    fn sent() -> PendingHistoryMutation {
        PendingHistoryMutation {
            account_id: "synthetic-account".into(),
            record_uuid: "11111111-1111-4111-8111-111111111111".into(),
            mutation_uuid: "22222222-2222-4222-8222-222222222222".into(),
            client_revision: 2,
            base_server_revision: 7,
        }
    }

    fn response() -> serde_json::Value {
        let mutation = sent();
        serde_json::json!({
            "version": 1, "accountId": mutation.account_id, "acknowledgements": [{
                "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                "clientRevision": 2, "result": "accepted", "serverRevision": 8
            }]
        })
    }

    #[test]
    fn parses_matching_ack_and_keeps_absent_ack_pending() {
        let parsed = parse_acknowledgements(
            &serde_json::to_vec(&response()).unwrap(),
            "synthetic-account",
            &[sent()],
        )
        .unwrap();
        assert_eq!(parsed[0].result, AckResult::Accepted);
        assert_eq!(parsed[0].server_revision, 8);
        let mut missing = response();
        missing["acknowledgements"] = serde_json::json!([]);
        assert!(parse_acknowledgements(
            &serde_json::to_vec(&missing).unwrap(),
            "synthetic-account",
            &[sent()],
        )
        .unwrap()
        .is_empty());
    }

    #[test]
    fn refuses_wrong_account_mutation_revision_and_extra_fields() {
        for field in ["account", "mutation", "revision", "server", "extra"] {
            let mut value = response();
            match field {
                "account" => value["accountId"] = serde_json::json!("another-account"),
                "mutation" => {
                    value["acknowledgements"][0]["mutationId"] =
                        serde_json::json!("33333333-3333-4333-8333-333333333333")
                }
                "revision" => value["acknowledgements"][0]["clientRevision"] = serde_json::json!(3),
                "server" => value["acknowledgements"][0]["serverRevision"] = serde_json::json!(7),
                _ => value["acknowledgements"][0]["apiKey"] = serde_json::json!("private"),
            }
            assert!(parse_acknowledgements(
                &serde_json::to_vec(&value).unwrap(),
                "synthetic-account",
                &[sent()],
            )
            .is_err());
        }
    }

    #[test]
    fn refuses_duplicate_fields_ids_and_oversized_responses_but_keeps_conflicts() {
        let serialized = response().to_string().replace(
            "\"result\":\"accepted\"",
            "\"result\":\"accepted\",\"result\":\"conflict\"",
        );
        assert!(
            parse_acknowledgements(serialized.as_bytes(), "synthetic-account", &[sent()]).is_err()
        );
        let mut duplicate = response();
        let ack = duplicate["acknowledgements"][0].clone();
        duplicate["acknowledgements"]
            .as_array_mut()
            .unwrap()
            .push(ack);
        assert!(parse_acknowledgements(
            &serde_json::to_vec(&duplicate).unwrap(),
            "synthetic-account",
            &[sent()],
        )
        .is_err());
        assert!(parse_acknowledgements(
            &vec![b' '; 256 * 1024 + 1],
            "synthetic-account",
            &[sent()],
        )
        .is_err());
        let mut conflict = response();
        conflict["acknowledgements"][0]["result"] = serde_json::json!("conflict");
        conflict["acknowledgements"][0]["serverRevision"] = serde_json::json!(0);
        assert_eq!(
            parse_acknowledgements(
                &serde_json::to_vec(&conflict).unwrap(),
                "synthetic-account",
                &[sent()],
            )
            .unwrap()[0]
                .result,
            AckResult::Conflict
        );
    }
}
