use crate::{CloudSessionSnapshot, SessionTokenStore};

#[derive(Debug, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Status {
    version: u8,
    pub account_id: String,
    pub capability: Option<Capability>,
}

#[derive(Debug, PartialEq, Eq, serde::Deserialize)]
#[serde(rename_all = "camelCase", deny_unknown_fields)]
pub struct Capability {
    version: u8,
    pub max_push_records: u32,
    pub max_pull_records: u32,
    pub max_payload_bytes: u32,
    pub max_request_bytes: u32,
    pub max_response_bytes: u32,
    audio_supported: bool,
    citations_supported: bool,
}

pub struct DiscoveredStatus {
    status: Status,
    session: CloudSessionSnapshot,
    discovered_at: std::time::Instant,
    endpoint: reqwest::Url,
}

#[derive(Debug, PartialEq, Eq)]
pub struct PullOutcome {
    pub next_cursor: i64,
    pub has_more: bool,
    pub change_count: usize,
}

#[derive(Debug, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct ProposalDescription {
    pub account_id: String,
    pub service_url: String,
    pub session_epoch: String,
}

impl DiscoveredStatus {
    pub(crate) fn account_id(&self) -> &str {
        &self.status.account_id
    }

    pub(crate) fn max_push_records(&self) -> anyhow::Result<u32> {
        Ok(self.current_capability()?.max_push_records)
    }

    pub(crate) fn with_proposal_description<T>(
        self,
        store: &SessionTokenStore,
        action: impl FnOnce(Self, ProposalDescription) -> anyhow::Result<T>,
    ) -> anyhow::Result<T> {
        let expected = self.session.clone();
        crate::history_sync_session::commit_with_current_session(store, &expected, || {
            self.current_capability()?;
            let description = ProposalDescription {
                account_id: self.status.account_id.clone(),
                service_url: self.endpoint.as_str().into(),
                session_epoch: self.session.session_epoch.clone(),
            };
            action(self, description)
        })
    }

    pub fn proposal_description(
        &self,
        store: &SessionTokenStore,
    ) -> anyhow::Result<ProposalDescription> {
        crate::history_sync_session::with_current_session(store, &self.session, || {
            self.current_capability()?;
            Ok(ProposalDescription {
                account_id: self.status.account_id.clone(),
                service_url: self.endpoint.as_str().into(),
                session_epoch: self.session.session_epoch.clone(),
            })
        })
    }

    pub fn build_request_with_consent(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        mutations: &[crate::storage::history_sync_queue::PendingHistoryMutation],
        authorization: (
            &crate::history_sync_consent::ConsentStore,
            &crate::history_sync_consent::ConsentSnapshot,
        ),
    ) -> anyhow::Result<serde_json::Value> {
        self.check_consent(store, authorization.0, authorization.1)?;
        let request = self.build_request(store, history, mutations)?;
        self.check_consent(store, authorization.0, authorization.1)?;
        Ok(request)
    }

    pub fn apply_page_with_consent(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        bytes: &[u8],
        cursor: i64,
        policy: &crate::storage::HistoryRetentionPolicy,
        authorization: (
            &crate::history_sync_consent::ConsentStore,
            &crate::history_sync_consent::ConsentSnapshot,
        ),
    ) -> anyhow::Result<PullOutcome> {
        let page = self.validate_page(store, bytes, cursor)?;
        let (consent, expected) = authorization;
        let next_cursor = history.apply_validated_sync_page_with_commit(
            &page,
            policy,
            (store, &self.session),
            |transaction| {
                consent.with_current(
                    expected,
                    &self.status.account_id,
                    &self.session.session_epoch,
                    &self.endpoint,
                    || {
                        self.current_capability()?;
                        transaction.commit()?;
                        Ok(())
                    },
                )
            },
        )?;
        Ok(PullOutcome {
            next_cursor,
            has_more: page.has_more,
            change_count: page.change_sequences.len(),
        })
    }

    pub fn apply_acknowledgements_with_consent(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        bytes: &[u8],
        sent: &[crate::storage::history_sync_queue::PendingHistoryMutation],
        authorization: (
            &crate::history_sync_consent::ConsentStore,
            &crate::history_sync_consent::ConsentSnapshot,
        ),
    ) -> anyhow::Result<usize> {
        let (consent, expected) = authorization;
        let capability = self.current_capability()?;
        if sent.len() > capability.max_push_records as usize
            || bytes.len() > capability.max_response_bytes as usize
        {
            anyhow::bail!("History sync discovery unavailable");
        }
        history.apply_history_acknowledgements_with_commit(
            bytes,
            &self.status.account_id,
            sent,
            (store, &self.session),
            |transaction| {
                consent.with_current(
                    expected,
                    &self.status.account_id,
                    &self.session.session_epoch,
                    &self.endpoint,
                    || {
                        self.current_capability()?;
                        transaction.commit()?;
                        Ok(())
                    },
                )
            },
        )
    }

    #[cfg(test)]
    pub(crate) fn confirm_consent(
        &self,
        store: &SessionTokenStore,
        consent: &crate::history_sync_consent::ConsentStore,
    ) -> anyhow::Result<crate::history_sync_consent::ConsentSnapshot> {
        crate::history_sync_session::commit_with_current_session(store, &self.session, || {
            self.current_capability()?;
            consent.grant(
                &self.status.account_id,
                &self.session.session_epoch,
                &self.endpoint,
            )
        })
    }

    pub(crate) fn confirm_empty_consent(
        &self,
        store: &SessionTokenStore,
        consent: &crate::history_sync_consent::ConsentStore,
    ) -> anyhow::Result<crate::history_sync_consent::ConsentSnapshot> {
        crate::history_sync_session::commit_with_current_session(store, &self.session, || {
            self.current_capability()?;
            consent.grant_if_empty(
                &self.status.account_id,
                &self.session.session_epoch,
                &self.endpoint,
            )
        })
    }

    pub fn check_consent(
        &self,
        store: &SessionTokenStore,
        consent: &crate::history_sync_consent::ConsentStore,
        expected: &crate::history_sync_consent::ConsentSnapshot,
    ) -> anyhow::Result<()> {
        crate::history_sync_session::with_current_session(store, &self.session, || {
            self.current_capability()?;
            consent.check(
                expected,
                &self.status.account_id,
                &self.session.session_epoch,
                &self.endpoint,
            )
        })
    }

    pub async fn push_once(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        mutations: &[crate::storage::history_sync_queue::PendingHistoryMutation],
        policy: &crate::storage::HistoryRetentionPolicy,
        authorization: (
            &crate::history_sync_consent::ConsentStore,
            &crate::history_sync_consent::ConsentSnapshot,
        ),
    ) -> anyhow::Result<usize> {
        let invalid = || anyhow::anyhow!("History sync push unavailable");
        self.check_consent(store, authorization.0, authorization.1)?;
        if !policy.enabled || policy.retention_days != 0 {
            return Err(invalid());
        }
        let request = self.build_request_with_consent(store, history, mutations, authorization)?;
        let bytes = serde_json::to_vec(&request).map_err(|_| invalid())?;
        let capability = self.current_capability()?;
        let mut url = self.endpoint.clone();
        let loopback_test = cfg!(test)
            && url.scheme() == "http"
            && matches!(url.host_str(), Some("127.0.0.1" | "[::1]"));
        if (url.scheme() != "https" && !loopback_test)
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || url.path() != "/api/history-sync/status"
        {
            return Err(invalid());
        }
        url.set_path("/api/history-sync/push");
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .map_err(|_| invalid())?;
        crate::history_sync_session::with_current_session(store, &self.session, || {
            self.current_capability().map(|_| ())
        })?;
        self.check_consent(store, authorization.0, authorization.1)?;
        let bytes = authorization
            .0
            .await_current(
                authorization.1,
                &self.status.account_id,
                &self.session.session_epoch,
                &self.endpoint,
                async {
                    let response = crate::with_desktop_client_version(
                        client
                            .post(url)
                            .bearer_auth(self.session.token.as_deref().ok_or_else(invalid)?)
                            .header(reqwest::header::CONTENT_TYPE, "application/json")
                            .body(bytes),
                    )
                    .send()
                    .await
                    .map_err(|_| invalid())?;
                    if response.status() != reqwest::StatusCode::OK {
                        return Err(invalid());
                    }
                    let bytes = crate::cloud_request::read_response_with_limit(
                        response,
                        (capability.max_response_bytes as usize).min(256 * 1024),
                    )
                    .await
                    .map_err(|_| invalid())?;
                    Ok(bytes)
                },
            )
            .await?;
        self.apply_acknowledgements_with_consent(store, history, &bytes, mutations, authorization)
    }

    pub async fn pull_once(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        policy: &crate::storage::HistoryRetentionPolicy,
        authorization: (
            &crate::history_sync_consent::ConsentStore,
            &crate::history_sync_consent::ConsentSnapshot,
        ),
    ) -> anyhow::Result<PullOutcome> {
        let invalid = || anyhow::anyhow!("History sync pull unavailable");
        self.check_consent(store, authorization.0, authorization.1)?;
        crate::history_sync_session::with_current_session(store, &self.session, || Ok(()))?;
        let capability = self.current_capability()?;
        if !policy.enabled || policy.retention_days != 0 {
            return Err(invalid());
        }
        let cursor = history.history_sync_pull_cursor(&self.status.account_id)?;
        let mut url = self.endpoint.clone();
        let loopback_test = cfg!(test)
            && url.scheme() == "http"
            && matches!(url.host_str(), Some("127.0.0.1" | "[::1]"));
        if (url.scheme() != "https" && !loopback_test)
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || url.path() != "/api/history-sync/status"
        {
            return Err(invalid());
        }
        url.set_path("/api/history-sync/pull");
        url.query_pairs_mut()
            .append_pair("cursor", &cursor.to_string())
            .append_pair("limit", &capability.max_pull_records.to_string());
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .map_err(|_| invalid())?;
        crate::history_sync_session::with_current_session(store, &self.session, || {
            self.current_capability().map(|_| ())
        })?;
        self.check_consent(store, authorization.0, authorization.1)?;
        let bytes = authorization
            .0
            .await_current(
                authorization.1,
                &self.status.account_id,
                &self.session.session_epoch,
                &self.endpoint,
                async {
                    let response = crate::with_desktop_client_version(
                        client
                            .get(url)
                            .bearer_auth(self.session.token.as_deref().ok_or_else(invalid)?),
                    )
                    .send()
                    .await
                    .map_err(|_| invalid())?;
                    if response.status() != reqwest::StatusCode::OK {
                        return Err(invalid());
                    }
                    let bytes = crate::cloud_request::read_response_with_limit(
                        response,
                        capability.max_response_bytes as usize,
                    )
                    .await
                    .map_err(|_| invalid())?;
                    Ok(bytes)
                },
            )
            .await?;
        self.apply_page_with_consent(store, history, &bytes, cursor, policy, authorization)
    }

    pub(crate) async fn read_record(
        &self,
        store: &SessionTokenStore,
        record_id: &str,
        authorization: (
            &crate::history_sync_consent::ConsentStore,
            &crate::history_sync_consent::ConsentSnapshot,
        ),
    ) -> anyhow::Result<Option<crate::storage::history_sync_record::RemoteRecord>> {
        let invalid = || anyhow::anyhow!("History sync record unavailable");
        self.check_consent(store, authorization.0, authorization.1)?;
        self.current_capability()?;
        if record_id.len() != 36
            || uuid::Uuid::parse_str(record_id)
                .ok()
                .is_none_or(|identity| {
                    identity.get_version_num() != 4
                        || identity.get_variant() != uuid::Variant::RFC4122
                })
        {
            return Err(invalid());
        }
        let mut url = self.endpoint.clone();
        let loopback_test = cfg!(test)
            && url.scheme() == "http"
            && matches!(url.host_str(), Some("127.0.0.1" | "[::1]"));
        if (url.scheme() != "https" && !loopback_test)
            || !url.username().is_empty()
            || url.password().is_some()
            || url.query().is_some()
            || url.fragment().is_some()
            || url.path() != "/api/history-sync/status"
        {
            return Err(invalid());
        }
        url.set_path("/api/history-sync/record");
        url.query_pairs_mut().append_pair("recordId", record_id);
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(20))
            .build()?;
        let bytes = authorization
            .0
            .await_current(
                authorization.1,
                &self.status.account_id,
                &self.session.session_epoch,
                &self.endpoint,
                async {
                    let response = crate::with_desktop_client_version(
                        client
                            .get(url)
                            .bearer_auth(self.session.token.as_deref().ok_or_else(invalid)?),
                    )
                    .send()
                    .await?;
                    if response.status() != reqwest::StatusCode::OK {
                        return Err(invalid());
                    }
                    crate::cloud_request::read_response_with_limit(response, 2 * 1024 * 1024)
                        .await
                        .map_err(|_| invalid())
                },
            )
            .await?;
        self.check_consent(store, authorization.0, authorization.1)?;
        crate::storage::history_sync_record::parse_record(
            &bytes,
            &self.status.account_id,
            record_id,
        )
    }

    pub fn apply_acknowledgements(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        bytes: &[u8],
        sent: &[crate::storage::history_sync_queue::PendingHistoryMutation],
    ) -> anyhow::Result<usize> {
        crate::history_sync_session::with_current_session(store, &self.session, || Ok(()))?;
        let capability = self.current_capability()?;
        if sent.len() > capability.max_push_records as usize
            || bytes.len() > capability.max_response_bytes as usize
        {
            anyhow::bail!("History sync discovery unavailable");
        }
        history.apply_history_acknowledgements_with_gate(
            bytes,
            &self.status.account_id,
            sent,
            (store, &self.session),
            || self.current_capability().map(|_| ()),
        )
    }

    pub fn apply_page(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        bytes: &[u8],
        cursor: i64,
        policy: &crate::storage::HistoryRetentionPolicy,
    ) -> anyhow::Result<i64> {
        self.apply_page_outcome(store, history, bytes, cursor, policy)
            .map(|outcome| outcome.next_cursor)
    }

    fn apply_page_outcome(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        bytes: &[u8],
        cursor: i64,
        policy: &crate::storage::HistoryRetentionPolicy,
    ) -> anyhow::Result<PullOutcome> {
        let page = self.validate_page(store, bytes, cursor)?;
        let next_cursor =
            history.apply_validated_sync_page(&page, policy, (store, &self.session), || {
                self.current_capability().map(|_| ())
            })?;
        Ok(PullOutcome {
            next_cursor,
            has_more: page.has_more,
            change_count: page.change_sequences.len(),
        })
    }

    fn validate_page(
        &self,
        store: &SessionTokenStore,
        bytes: &[u8],
        cursor: i64,
    ) -> anyhow::Result<crate::storage::history_sync_page::HistorySyncPage> {
        crate::history_sync_session::with_current_session(store, &self.session, || Ok(()))?;
        let capability = self.current_capability()?;
        if bytes.len() > capability.max_response_bytes as usize {
            anyhow::bail!("History sync discovery unavailable");
        }
        let page =
            crate::storage::history_sync_page::parse_page(bytes, &self.status.account_id, cursor)?;
        if page.records.len() > capability.max_pull_records as usize {
            anyhow::bail!("History sync discovery unavailable");
        }
        for record in &page.records {
            if !record.deleted
                && serde_json::to_vec(&record.payload)?.len()
                    > capability.max_payload_bytes as usize
            {
                anyhow::bail!("History sync discovery unavailable");
            }
        }
        Ok(page)
    }

    pub fn build_request(
        &self,
        store: &SessionTokenStore,
        history: &crate::storage::HistoryStore,
        mutations: &[crate::storage::history_sync_queue::PendingHistoryMutation],
    ) -> anyhow::Result<serde_json::Value> {
        crate::history_sync_session::with_current_session(store, &self.session, || {
            let capability = self.current_capability()?;
            if mutations.len() > capability.max_push_records as usize
                || mutations
                    .iter()
                    .any(|mutation| mutation.account_id != self.status.account_id)
            {
                anyhow::bail!("History sync discovery unavailable");
            }
            let request = history.build_pending_history_batch_request(mutations)?;
            for mutation in request["mutations"]
                .as_array()
                .ok_or_else(|| anyhow::anyhow!("History sync discovery unavailable"))?
            {
                if serde_json::to_vec(&mutation["payload"])?.len()
                    > capability.max_payload_bytes as usize
                {
                    anyhow::bail!("History sync discovery unavailable");
                }
            }
            if serde_json::to_vec(&request)?.len() > capability.max_request_bytes as usize {
                anyhow::bail!("History sync discovery unavailable");
            }
            self.current_capability()?;
            Ok(request)
        })
    }

    fn current_capability(&self) -> anyhow::Result<&Capability> {
        if self
            .discovered_at
            .checked_add(std::time::Duration::from_secs(60))
            .is_none_or(|deadline| std::time::Instant::now() >= deadline)
        {
            anyhow::bail!("History sync discovery unavailable");
        }
        self.status
            .capability
            .as_ref()
            .ok_or_else(|| anyhow::anyhow!("History sync discovery unavailable"))
    }
}

fn parse(bytes: &[u8]) -> anyhow::Result<Status> {
    let invalid = || anyhow::anyhow!("History sync status unavailable");
    if bytes.len() > 16 * 1024 {
        return Err(invalid());
    }
    let status: Status = serde_json::from_slice(bytes).map_err(|_| invalid())?;
    let fields: serde_json::Value = serde_json::from_slice(bytes).map_err(|_| invalid())?;
    if !fields
        .as_object()
        .is_some_and(|fields| fields.contains_key("capability"))
        || status.version != 1
        || status.account_id.is_empty()
        || status.account_id.trim() != status.account_id
        || status.account_id.len() > 200
        || status.account_id.chars().any(char::is_control)
    {
        return Err(invalid());
    }
    if let Some(capability) = &status.capability {
        if capability.version != 1
            || capability.audio_supported
            || capability.citations_supported
            || !(1..=200).contains(&capability.max_push_records)
            || !(1..=500).contains(&capability.max_pull_records)
            || !(1..=1048576).contains(&capability.max_payload_bytes)
            || !(1..=4194304).contains(&capability.max_request_bytes)
            || !(1..=4194304).contains(&capability.max_response_bytes)
        {
            return Err(invalid());
        }
    }
    Ok(status)
}

pub async fn discover(
    store: &SessionTokenStore,
    expected: &CloudSessionSnapshot,
) -> anyhow::Result<DiscoveredStatus> {
    let endpoint = format!(
        "{}/api/history-sync/status",
        crate::api_base_url().trim_end_matches('/')
    );
    let status = discover_endpoint(&endpoint, store, expected).await?;
    crate::history_sync_session::with_current_session(store, expected, || {
        Ok(DiscoveredStatus {
            status,
            session: expected.clone(),
            discovered_at: std::time::Instant::now(),
            endpoint: reqwest::Url::parse(&endpoint)?,
        })
    })
}

async fn discover_endpoint(
    endpoint: &str,
    store: &SessionTokenStore,
    expected: &CloudSessionSnapshot,
) -> anyhow::Result<Status> {
    crate::history_sync_session::with_current_session(store, expected, || Ok(()))?;
    let invalid = || anyhow::anyhow!("History sync status unavailable");
    let url = reqwest::Url::parse(endpoint).map_err(|_| invalid())?;
    let loopback_test = cfg!(test)
        && url.scheme() == "http"
        && matches!(url.host_str(), Some("127.0.0.1" | "[::1]"));
    if (url.scheme() != "https" && !loopback_test)
        || !url.username().is_empty()
        || url.password().is_some()
        || url.query().is_some()
        || url.fragment().is_some()
        || url.path() != "/api/history-sync/status"
    {
        return Err(invalid());
    }
    let result = async {
        let client = reqwest::Client::builder()
            .redirect(reqwest::redirect::Policy::none())
            .timeout(std::time::Duration::from_secs(20))
            .build()
            .map_err(|_| invalid())?;
        let response = crate::with_desktop_client_version(
            client
                .get(url)
                .bearer_auth(expected.token.as_deref().ok_or_else(invalid)?),
        )
        .send()
        .await
        .map_err(|_| invalid())?;
        if response.status() != reqwest::StatusCode::OK {
            return Err(invalid());
        }
        let body = crate::cloud_request::read_response_with_limit(response, 16 * 1024)
            .await
            .map_err(|_| invalid())?;
        parse(&body)
    }
    .await;
    crate::history_sync_session::with_current_session(store, expected, || result)
}

#[cfg(test)]
mod tests {
    use super::*;
    use std::io::{Read, Write};
    use std::sync::{atomic::AtomicBool, Arc, Mutex};

    #[tokio::test]
    async fn remote_record_http_read_is_authorized_bounded_and_identity_checked() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let endpoint: reqwest::Url = format!(
            "http://{}/api/history-sync/status",
            listener.local_addr().unwrap()
        )
        .parse()
        .unwrap();
        let record_id = "11111111-1111-4111-8111-111111111111";
        let server = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
            let mut socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        assert!(std::time::Instant::now() < deadline);
                        std::thread::sleep(std::time::Duration::from_millis(5));
                    }
                    Err(error) => panic!("{error}"),
                }
            };
            socket.set_nonblocking(false).unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut bytes = Vec::new();
            while !bytes.windows(4).any(|value| value == b"\r\n\r\n") {
                let mut buffer = [0_u8; 4096];
                let count = socket.read(&mut buffer).unwrap();
                assert!(count > 0);
                bytes.extend_from_slice(&buffer[..count]);
                assert!(bytes.len() < 16 * 1024);
            }
            let headers = String::from_utf8(bytes).unwrap().to_ascii_lowercase();
            assert!(headers.starts_with(&format!(
                "get /api/history-sync/record?recordid={record_id} "
            )));
            assert!(headers.contains("authorization: bearer synthetic-token"));
            assert!(headers.contains("x-opentypeless-version:"));
            assert!(!headers.contains("cookie:"));
            let body = serde_json::json!({
                "version": 1, "accountId": "synthetic-account",
                "record": {
                    "recordId": record_id, "serverRevision": 2,
                    "deleted": true, "payload": null
                }
            })
            .to_string();
            write!(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                body.len(),
                body
            )
            .unwrap();
        });
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = DiscoveredStatus {
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
            endpoint,
        };
        let consent = crate::history_sync_consent::ConsentStore::default();
        let authorization = consent
            .grant_synthetic_loopback(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &discovery.endpoint,
            )
            .unwrap();
        let record = discovery
            .read_record(&session, record_id, (&consent, &authorization))
            .await
            .unwrap()
            .unwrap();
        assert!(record.deleted);
        assert_eq!(record.server_revision, 2);
        server.join().unwrap();
        consent.revoke().unwrap();
        assert!(discovery
            .read_record(&session, record_id, (&consent, &authorization))
            .await
            .is_err());
    }

    #[tokio::test]
    async fn production_single_sync_sequence_pushes_ack_then_pulls_and_commits() {
        verify_single_sync_sequence(false, false, false, false, false).await;
    }

    #[tokio::test]
    async fn production_single_sync_pull_failure_preserves_ack_without_advancing_cursor() {
        verify_single_sync_sequence(true, false, false, false, false).await;
    }

    #[tokio::test]
    async fn production_single_sync_without_pending_push_pulls_only_one_page() {
        verify_single_sync_sequence(false, true, false, false, false).await;
    }

    #[tokio::test]
    async fn explicit_second_sync_continues_from_the_committed_pull_cursor() {
        verify_single_sync_sequence(false, true, true, false, false).await;
    }

    #[tokio::test]
    async fn completed_pull_does_not_report_success_after_authorization_revocation() {
        verify_single_sync_sequence(false, false, false, true, false).await;
    }

    #[tokio::test]
    async fn production_single_sync_conflict_stops_before_pull_and_keeps_pending() {
        verify_single_sync_sequence(false, false, false, false, true).await;
    }

    async fn verify_single_sync_sequence(
        fail_pull: bool,
        pull_only: bool,
        continue_pull: bool,
        revoke_after_commit: bool,
        conflict_push: bool,
    ) {
        let history = crate::storage::HistoryStore::new(":memory:".into()).unwrap();
        let entry = serde_json::from_value(serde_json::json!({
            "id":0, "created_at":"2026-11-21T12:00:00",
            "raw_text":"Local text", "polished_text":"", "mode":"dictate",
            "context_profile_id":"general.native", "context_label":"General",
            "context_icon_key":"general", "context_family":"general",
            "browser_access_status":"not_applicable", "provider_kind":"local",
            "active_scene_prompt_truncated":false
        }))
        .unwrap();
        history.add(entry).await.unwrap();
        let identity = history.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = history
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let request = history.build_pending_history_request(&mutation).unwrap();
        if pull_only {
            assert!(history
                .confirm_pending_history_revision(&mutation, 1)
                .unwrap());
        }
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        listener.set_nonblocking(true).unwrap();
        let endpoint: reqwest::Url = format!(
            "http://{}/api/history-sync/status",
            listener.local_addr().unwrap()
        )
        .parse()
        .unwrap();
        let server_mutation = mutation.clone();
        let expected_request = request.clone();
        let server = std::thread::spawn(move || {
            let mut observed = Vec::new();
            let steps = if continue_pull {
                [false, false]
            } else {
                [true, false]
            };
            let mut pull_cursor = 0;
            for pushing in steps {
                if pushing && pull_only {
                    continue;
                }
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
                let (mut socket, _) = loop {
                    match listener.accept() {
                        Ok(connection) => break connection,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(std::time::Instant::now() < deadline, "Missing sync request");
                            std::thread::sleep(std::time::Duration::from_millis(5));
                        }
                        Err(error) => panic!("{error}"),
                    }
                };
                socket.set_nonblocking(false).unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut bytes = Vec::new();
                let header_end = loop {
                    let mut buffer = [0_u8; 4096];
                    let read = socket.read(&mut buffer).unwrap();
                    assert!(read > 0);
                    bytes.extend_from_slice(&buffer[..read]);
                    assert!(bytes.len() < 32 * 1024);
                    if let Some(end) = bytes.windows(4).position(|value| value == b"\r\n\r\n") {
                        break end + 4;
                    }
                };
                let headers = String::from_utf8_lossy(&bytes[..header_end]).to_ascii_lowercase();
                assert!(headers.contains("authorization: bearer synthetic-token"));
                observed.push(headers.lines().next().unwrap().to_string());
                let body = if pushing {
                    assert!(headers.starts_with("post /api/history-sync/push "));
                    let length = headers
                        .lines()
                        .find_map(|line| {
                            line.strip_prefix("content-length:")
                                .map(|value| value.trim().parse::<usize>().unwrap())
                        })
                        .unwrap();
                    while bytes.len() < header_end + length {
                        let mut buffer = [0_u8; 4096];
                        let read = socket.read(&mut buffer).unwrap();
                        assert!(read > 0);
                        bytes.extend_from_slice(&buffer[..read]);
                    }
                    assert_eq!(
                        serde_json::from_slice::<serde_json::Value>(
                            &bytes[header_end..header_end + length]
                        )
                        .unwrap(),
                        expected_request
                    );
                    serde_json::json!({
                        "version":1,"accountId":"synthetic-account","acknowledgements":[{
                            "recordId":server_mutation.record_uuid,"mutationId":server_mutation.mutation_uuid,
                            "clientRevision":server_mutation.client_revision,
                            "result":if conflict_push { "conflict" } else { "accepted" },"serverRevision":1
                        }]
                    })
                } else {
                    assert!(headers.starts_with(&format!(
                        "get /api/history-sync/pull?cursor={pull_cursor}&limit=500 "
                    )));
                    let from_cursor = pull_cursor;
                    pull_cursor += 1;
                    serde_json::json!({
                        "version":1,"accountId":"synthetic-account","fromCursor":from_cursor,
                        "nextCursor":pull_cursor,"hasMore":pull_only && pull_cursor == 1,
                        "changeSequences":[pull_cursor],"records":[{
                            "changeSequence":pull_cursor,"recordId":server_mutation.record_uuid,
                            "serverRevision":pull_cursor,"deleted":false,"payload":expected_request["mutations"][0]["payload"]
                        }]
                    })
                };
                let body = if fail_pull && !pushing {
                    "Synthetic unavailable response".to_string()
                } else {
                    serde_json::to_string(&body).unwrap()
                };
                let status = if fail_pull && !pushing {
                    "503 Service Unavailable"
                } else {
                    "200 OK"
                };
                write!(
                    socket,
                    "HTTP/1.1 {}\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{}",
                    status,
                    body.len(),
                    body
                )
                .unwrap();
                if conflict_push {
                    break;
                }
            }
            observed
        });
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = DiscoveredStatus {
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session:session.snapshot(), discovered_at:std::time::Instant::now(), endpoint,
        };
        let consent = crate::history_sync_consent::ConsentStore::default();
        let authorization = consent
            .grant_synthetic_loopback(
                "synthetic-account",
                &session.snapshot().session_epoch,
                &discovery.endpoint,
            )
            .unwrap();
        let refreshes = std::sync::atomic::AtomicUsize::new(0);
        let result = crate::history_sync_commands::sync_discovered_history_once(
            &discovery,
            &session,
            &history,
            &Default::default(),
            (&consent, &authorization),
            || {
                refreshes.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                if revoke_after_commit {
                    consent.revoke().unwrap();
                }
            },
        )
        .await;
        if conflict_push {
            assert_eq!(result.unwrap_err(), "History sync push incomplete");
        } else if fail_pull {
            assert_eq!(result.unwrap_err(), "History sync pull unavailable");
        } else if revoke_after_commit {
            assert_eq!(
                result.unwrap_err(),
                "History sync authorization unavailable"
            );
        } else {
            let result = result.unwrap();
            assert_eq!(result.pushed, usize::from(!pull_only));
            assert_eq!(result.pulled, 1);
            assert_eq!(result.pending, 0);
            assert_eq!(result.has_more, pull_only);
        }
        if continue_pull {
            assert_eq!(
                history
                    .history_sync_pull_cursor("synthetic-account")
                    .unwrap(),
                1
            );
            let next = crate::history_sync_commands::sync_discovered_history_once(
                &discovery,
                &session,
                &history,
                &Default::default(),
                (&consent, &authorization),
                || {
                    refreshes.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                },
            )
            .await
            .unwrap();
            assert_eq!(next.pushed, 0);
            assert_eq!(next.pulled, 1);
            assert_eq!(next.pending, 0);
            assert!(!next.has_more);
        }
        assert_eq!(
            server.join().unwrap().len(),
            if conflict_push || (pull_only && !continue_pull) {
                1
            } else {
                2
            }
        );
        assert_eq!(
            refreshes.load(std::sync::atomic::Ordering::SeqCst),
            usize::from(!fail_pull && !conflict_push) + usize::from(continue_pull)
        );
        assert_eq!(
            history
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            i64::from(!fail_pull && !conflict_push) + i64::from(continue_pull)
        );
        assert_eq!(history.list(10, 0).await.unwrap().len(), 1);
        assert_eq!(
            history.local_identity_snapshot(1).unwrap().unwrap(),
            identity
        );
        assert_eq!(
            history.pending_history_count("synthetic-account").unwrap(),
            usize::from(conflict_push)
        );
        assert_eq!(
            history
                .confirm_pending_history_revision(&mutation, 1)
                .unwrap(),
            conflict_push
        );
    }

    #[test]
    fn proposal_publication_rejects_consent_created_after_discovery_begins() {
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let discovery = || {
            DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        }
        };
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let attempt = proposals.begin_guarded_discovery().unwrap();
        let epoch = session.snapshot().session_epoch;
        let existing = consent
            .grant("synthetic-account", &epoch, &endpoint)
            .unwrap();
        assert!(proposals
            .finish_discovery_with_consent(attempt.attempt_id(), discovery(), &session, &consent)
            .is_err());
        assert!(!proposals.pending_for_test());
        assert!(consent
            .check(&existing, "synthetic-account", &epoch, &endpoint)
            .is_ok());
        drop(attempt);
        assert!(!proposals.retains_state_for_test());
        consent.revoke().unwrap();
        let fresh = proposals.begin_guarded_discovery().unwrap();
        assert!(proposals
            .finish_discovery_with_consent(fresh.attempt_id(), discovery(), &session, &consent)
            .is_ok());
    }

    #[test]
    fn owned_confirmation_does_not_replace_an_existing_consent_generation() {
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let discovery = DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let request = proposals.prepare(discovery, &session).unwrap();
        let epoch = session.snapshot().session_epoch;
        let existing = consent
            .grant("synthetic-account", &epoch, &endpoint)
            .unwrap();
        let refused = proposals
            .confirm_owned(&request.request_id, &epoch, &session, &consent, &endpoint)
            .is_err();
        assert!(
            refused,
            "Old proposal must not replace an existing authorization"
        );
        assert!(consent
            .check(&existing, "synthetic-account", &epoch, &endpoint)
            .is_ok());
        consent.revoke().unwrap();
        assert!(proposals
            .confirm_owned(&request.request_id, &epoch, &session, &consent, &endpoint)
            .is_err());
    }

    #[test]
    fn confirmation_waiting_for_session_blocks_new_discovery() {
        let session = Arc::new(SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        ));
        let snapshot = session.snapshot();
        let epoch = snapshot.session_epoch.clone();
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let discovery = DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: snapshot,
            discovered_at: std::time::Instant::now(),
        };
        let proposals = Arc::new(crate::history_sync_proposal::ProposalStore::default());
        let consent = Arc::new(crate::history_sync_consent::ConsentStore::default());
        let request_id = proposals.prepare(discovery, &session).unwrap().request_id;
        let pending_id = request_id.clone();
        let expected_epoch = epoch.clone();
        let expected_endpoint = endpoint.clone();
        let token_guard = session.0.lock().unwrap();
        let confirming = {
            let proposals = proposals.clone();
            let consent = consent.clone();
            let session = session.clone();
            std::thread::spawn(move || {
                proposals.confirm_owned(&request_id, &epoch, &session, &consent, &endpoint)
            })
        };
        let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
        while proposals.pending_for_test() && std::time::Instant::now() < deadline {
            std::thread::sleep(std::time::Duration::from_millis(1));
        }
        let consumed = !proposals.pending_for_test();
        let duplicate_refused = proposals
            .confirm_owned(
                &pending_id,
                &expected_epoch,
                &session,
                &consent,
                &expected_endpoint,
            )
            .is_err();
        let cancelled = proposals.cancel(&pending_id).unwrap();
        proposals.clear().unwrap();
        let refused = proposals.begin_discovery().is_err();
        drop(token_guard);
        assert!(confirming.join().unwrap().is_ok());
        assert!(consumed, "Confirmation must have consumed its proposal");
        assert!(duplicate_refused);
        assert!(!cancelled);
        assert!(refused, "Pending confirmation must block new discovery");
        assert!(proposals.begin_discovery().is_ok());
    }

    #[test]
    fn session_change_while_confirmation_waits_prevents_grant_and_releases_reservation() {
        for rotate_epoch in [false, true] {
            let session = Arc::new(SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ));
            let snapshot = session.snapshot();
            let expected_epoch = snapshot.session_epoch.clone();
            let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap();
            let discovery = DiscoveredStatus {
                endpoint: endpoint.clone(),
                status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
                session: snapshot,
                discovered_at: std::time::Instant::now(),
            };
            let proposals = Arc::new(crate::history_sync_proposal::ProposalStore::default());
            let consent = Arc::new(crate::history_sync_consent::ConsentStore::default());
            let request_id = proposals.prepare(discovery, &session).unwrap().request_id;
            let mut token_guard = session.0.lock().unwrap();
            let confirming = {
                let proposals = proposals.clone();
                let consent = consent.clone();
                let session = session.clone();
                let endpoint = endpoint.clone();
                std::thread::spawn(move || {
                    proposals.confirm_owned(
                        &request_id,
                        &expected_epoch,
                        &session,
                        &consent,
                        &endpoint,
                    )
                })
            };
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(2);
            while proposals.pending_for_test() && std::time::Instant::now() < deadline {
                std::thread::sleep(std::time::Duration::from_millis(1));
            }
            let consumed = !proposals.pending_for_test();
            if rotate_epoch {
                *session.2.lock().unwrap() = uuid::Uuid::new_v4().to_string();
            } else {
                *token_guard = "synthetic-replacement-token".into();
            }
            drop(token_guard);
            assert!(confirming.join().unwrap().is_err());
            assert!(consumed, "Confirmation must have consumed the old proposal");
            assert!(!consent.active_for_session(&session, &endpoint).unwrap());
            assert!(proposals.begin_discovery().is_ok());
        }
    }

    #[test]
    fn rejected_confirmation_invalidates_old_discovery_and_releases_reservation() {
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let discovery = || {
            DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        }
        };
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let request = proposals.prepare(discovery(), &session).unwrap();
        let old_attempt = proposals.begin_discovery().unwrap();
        assert!(proposals
            .confirm_owned(
                &request.request_id,
                &uuid::Uuid::new_v4().to_string(),
                &session,
                &consent,
                &endpoint,
            )
            .is_err());
        assert!(!consent.active_for_session(&session, &endpoint).unwrap());
        assert!(proposals
            .finish_discovery(&old_attempt, discovery(), &session)
            .is_err());
        let new_attempt = proposals.begin_discovery().unwrap();
        let prepared = proposals
            .finish_discovery(&new_attempt, discovery(), &session)
            .unwrap();
        assert!(proposals
            .confirm_owned(
                &prepared.request_id,
                &session.snapshot().session_epoch,
                &session,
                &consent,
                &endpoint,
            )
            .is_ok());
    }

    #[test]
    fn poisoned_proposal_clear_discards_retained_discovery_without_reenabling_store() {
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":null}"#)
                .unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        proposals.issue(discovery).unwrap();
        proposals.begin_discovery().unwrap();
        proposals.poison_for_test();
        assert!(proposals.retains_state_for_test());
        assert!(proposals.clear().is_err());
        assert!(!proposals.retains_state_for_test());
        assert!(proposals.begin_discovery().is_err());
        assert!(proposals.cancel("unknown").is_err());
    }

    #[test]
    fn clearing_proposals_releases_pending_discovery_and_invalidates_inflight_attempt() {
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = || {
            DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        }
        };
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let pending = proposals.prepare(discovery(), &session).unwrap();
        let attempt = proposals.begin_discovery().unwrap();
        proposals.clear().unwrap();
        assert!(!proposals.cancel(&pending.request_id).unwrap());
        assert!(proposals
            .finish_discovery(&attempt, discovery(), &session)
            .is_err());
        assert!(proposals
            .confirm_owned(
                &pending.request_id,
                &session.snapshot().session_epoch,
                &session,
                &crate::history_sync_consent::ConsentStore::default(),
                &"https://example.invalid/api/history-sync/status"
                    .parse()
                    .unwrap(),
            )
            .is_err());
    }

    #[test]
    fn concurrent_cancel_and_confirm_have_one_consistent_proposal_outcome() {
        for _round in 0..16 {
            let session = Arc::new(SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ));
            let epoch = session.snapshot().session_epoch;
            let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap();
            let discovery = DiscoveredStatus {
                endpoint: endpoint.clone(),
                status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
                session: session.snapshot(),
                discovered_at: std::time::Instant::now(),
            };
            let proposals = Arc::new(crate::history_sync_proposal::ProposalStore::default());
            let consent = Arc::new(crate::history_sync_consent::ConsentStore::default());
            let request_id = proposals.prepare(discovery, &session).unwrap().request_id;
            let start = Arc::new(std::sync::Barrier::new(3));
            let confirming = {
                let proposals = proposals.clone();
                let consent = consent.clone();
                let session = session.clone();
                let endpoint = endpoint.clone();
                let epoch = epoch.clone();
                let request_id = request_id.clone();
                let start = start.clone();
                std::thread::spawn(move || {
                    start.wait();
                    proposals.confirm_owned(&request_id, &epoch, &session, &consent, &endpoint)
                })
            };
            let cancelling = {
                let proposals = proposals.clone();
                let request_id = request_id.clone();
                let start = start.clone();
                std::thread::spawn(move || {
                    start.wait();
                    proposals.cancel(&request_id)
                })
            };
            start.wait();
            let confirmed = confirming.join().unwrap();
            let cancelled = cancelling.join().unwrap().unwrap();
            assert_eq!(confirmed.is_ok(), !cancelled);
            assert_eq!(
                consent.active_for_session(&session, &endpoint).unwrap(),
                !cancelled
            );
            assert!(!proposals.cancel(&request_id).unwrap());
            assert!(proposals
                .confirm_owned(&request_id, &epoch, &session, &consent, &endpoint)
                .is_err());
        }
    }

    #[tokio::test(flavor = "current_thread")]
    async fn blocked_confirmation_leaves_async_tasks_responsive_and_grants_once() {
        use tauri::Manager;
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_proposal::ProposalStore::default())
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let session = app.state::<SessionTokenStore>();
        let epoch = session.snapshot().session_epoch;
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .unwrap();
        let discovery = DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let proposals = app.state::<crate::history_sync_proposal::ProposalStore>();
        let request = proposals.prepare(discovery, session.inner()).unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let token = session.0.clone();
        let (entered_sender, entered_receiver) = tokio::sync::oneshot::channel();
        let (release_sender, release_receiver) = std::sync::mpsc::channel();
        let holder = std::thread::spawn(move || {
            let _guard = token.lock().unwrap();
            entered_sender.send(()).unwrap();
            release_receiver
                .recv_timeout(std::time::Duration::from_secs(3))
                .is_ok()
        });
        entered_receiver.await.unwrap();
        let completed = std::cell::Cell::new(false);
        let waiting = std::cell::Cell::new(false);
        let confirming = async {
            let result = crate::history_sync_commands::confirm_history_sync_proposal(
                window,
                request.request_id.clone(),
                epoch,
            )
            .await;
            completed.set(true);
            result
        };
        let releasing = async {
            tokio::time::sleep(std::time::Duration::from_millis(30)).await;
            waiting.set(!completed.get());
            let _ = release_sender.send(());
        };
        let (result, ()) = tokio::join!(confirming, releasing);
        let released = holder.join().unwrap();
        assert!(
            released,
            "Async release must execute before the holder timeout"
        );
        assert!(
            waiting.get(),
            "Confirmation must wait while the session lock is held"
        );
        result.unwrap();
        let consent = app.state::<crate::history_sync_consent::ConsentStore>();
        assert!(consent
            .active_for_session(session.inner(), &endpoint)
            .unwrap());
        assert!(proposals
            .confirm_owned(
                &request.request_id,
                &session.snapshot().session_epoch,
                session.inner(),
                consent.inner(),
                &endpoint,
            )
            .is_err());
    }

    #[test]
    fn proposal_storage_action_keeps_session_locked_until_publication() {
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        discovery
            .with_proposal_description(&session, |_, description| {
                assert!(matches!(
                    session.0.try_lock(),
                    Err(std::sync::TryLockError::WouldBlock)
                ));
                assert!(matches!(
                    session.2.try_lock(),
                    Err(std::sync::TryLockError::WouldBlock)
                ));
                assert_eq!(description.account_id, "synthetic-account");
                Ok(())
            })
            .unwrap();
    }

    #[test]
    fn confirmation_matches_caller_epoch_to_the_consumed_proposal() {
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let old_epoch = session.snapshot().session_epoch;
        session.rotate_epoch();
        let current_epoch = session.snapshot().session_epoch;
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let discovery = || {
            DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        }
        };
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let request = proposals.prepare(discovery(), &session).unwrap();
        assert!(proposals
            .confirm_owned(
                &request.request_id,
                &old_epoch,
                &session,
                &consent,
                &endpoint
            )
            .is_err());
        assert!(!consent.active_for_session(&session, &endpoint).unwrap());
        assert!(proposals
            .confirm_owned(
                &request.request_id,
                &current_epoch,
                &session,
                &consent,
                &endpoint
            )
            .is_err());
        let fresh = proposals.prepare(discovery(), &session).unwrap();
        assert!(proposals
            .confirm_owned(
                &fresh.request_id,
                &current_epoch,
                &session,
                &consent,
                &endpoint
            )
            .is_ok());
    }

    #[test]
    fn older_discovery_completion_cannot_replace_a_newer_prepared_proposal() {
        let session = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = || {
            DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        }
        };
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let old_attempt = proposals.begin_discovery().unwrap();
        let new_attempt = proposals.begin_discovery().unwrap();
        assert!(!proposals.abandon_discovery(&old_attempt).unwrap());
        let current = proposals
            .finish_discovery(&new_attempt, discovery(), &session)
            .unwrap();
        assert!(proposals
            .finish_discovery(&old_attempt, discovery(), &session)
            .is_err());
        assert!(proposals
            .finish_discovery(&new_attempt, discovery(), &session)
            .is_err());
        let consent = crate::history_sync_consent::ConsentStore::default();
        let abandoned = proposals.begin_discovery().unwrap();
        assert!(proposals.abandon_discovery(&abandoned).unwrap());
        assert!(!proposals.abandon_discovery(&abandoned).unwrap());
        assert!(proposals
            .finish_discovery(&abandoned, discovery(), &session)
            .is_err());
        assert!(proposals
            .confirm_at_service(
                &current.request_id,
                &session,
                &consent,
                &"https://example.invalid/api/history-sync/status"
                    .parse()
                    .unwrap(),
            )
            .is_ok());
    }

    #[test]
    fn concurrent_proposal_confirmation_grants_exactly_once() {
        let session = Arc::new(SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        ));
        let endpoint: reqwest::Url = "https://example.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        let discovery = DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let proposals = Arc::new(crate::history_sync_proposal::ProposalStore::default());
        let consent = Arc::new(crate::history_sync_consent::ConsentStore::default());
        let request_id = proposals.prepare(discovery, &session).unwrap().request_id;
        let start = Arc::new(std::sync::Barrier::new(3));
        let workers: Vec<_> = (0..2)
            .map(|_| {
                let proposals = proposals.clone();
                let consent = consent.clone();
                let session = session.clone();
                let endpoint = endpoint.clone();
                let request_id = request_id.clone();
                let start = start.clone();
                std::thread::spawn(move || {
                    start.wait();
                    proposals.confirm_at_service(&request_id, &session, &consent, &endpoint)
                })
            })
            .collect();
        start.wait();
        let results: Vec<_> = workers
            .into_iter()
            .map(|worker| worker.join().unwrap())
            .collect();
        assert_eq!(results.iter().filter(|result| result.is_ok()).count(), 1);
        assert_eq!(results.iter().filter(|result| result.is_err()).count(), 1);
        let winner = results.into_iter().find_map(Result::ok).unwrap();
        assert!(consent
            .check(
                &winner,
                "synthetic-account",
                &session.snapshot().session_epoch,
                &endpoint
            )
            .is_ok());
        assert!(!proposals.cancel(&request_id).unwrap());
    }

    #[test]
    fn confirmation_ipc_consumes_valid_proposal_without_network_or_history_access() {
        use tauri::Manager;
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_proposal::ProposalStore::default())
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .invoke_handler(tauri::generate_handler![
                crate::history_sync_commands::confirm_history_sync_proposal
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let session = app.state::<SessionTokenStore>();
        let epoch = session.snapshot().session_epoch;
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .unwrap();
        let discovery = DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let proposals = app.state::<crate::history_sync_proposal::ProposalStore>();
        let prepared = proposals.prepare(discovery, session.inner()).unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let invoke = || {
            tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: "confirm_history_sync_proposal".into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "requestId": prepared.request_id, "expectedSessionEpoch": epoch
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            )
        };
        assert!(invoke().is_ok());
        assert!(invoke().is_err());
        assert!(app
            .state::<crate::history_sync_consent::ConsentStore>()
            .active_for_session(session.inner(), &endpoint)
            .unwrap());
    }

    #[test]
    fn cancellation_ipc_consumes_pending_proposal_without_revoking_existing_consent() {
        use tauri::Manager;
        let app = tauri::test::mock_builder()
            .manage(crate::history_sync_proposal::ProposalStore::default())
            .manage(crate::history_sync_consent::ConsentStore::default())
            .manage(SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .invoke_handler(tauri::generate_handler![
                crate::history_sync_commands::cancel_history_sync_proposal,
                crate::history_sync_commands::confirm_history_sync_proposal
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let session = app.state::<SessionTokenStore>();
        let epoch = session.snapshot().session_epoch;
        let endpoint: reqwest::Url = format!(
            "{}/api/history-sync/status",
            crate::api_base_url().trim_end_matches('/')
        )
        .parse()
        .unwrap();
        let discovery = DiscoveredStatus {
            endpoint: endpoint.clone(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: session.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let consent = app.state::<crate::history_sync_consent::ConsentStore>();
        let existing = discovery
            .confirm_consent(session.inner(), consent.inner())
            .unwrap();
        let prepared = app
            .state::<crate::history_sync_proposal::ProposalStore>()
            .prepare(discovery, session.inner())
            .unwrap();
        let window = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let invoke = |command: &str| {
            tauri::test::get_ipc_response(
                &window,
                tauri::webview::InvokeRequest {
                    cmd: command.into(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "requestId": prepared.request_id, "expectedSessionEpoch": epoch
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.into(),
                },
            )
        };
        assert!(invoke("cancel_history_sync_proposal")
            .unwrap()
            .deserialize::<bool>()
            .unwrap());
        assert!(!invoke("cancel_history_sync_proposal")
            .unwrap()
            .deserialize::<bool>()
            .unwrap());
        assert!(invoke("confirm_history_sync_proposal").is_err());
        assert!(consent
            .check(&existing, "synthetic-account", &epoch, &endpoint)
            .is_ok());
    }

    #[test]
    fn proposal_replacement_and_confirmation_are_single_use_and_session_bound() {
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = || {
            DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        }
        };
        let proposals = crate::history_sync_proposal::ProposalStore::default();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let described = discovery();
        let description = described.proposal_description(&store).unwrap();
        assert_eq!(description.account_id, "synthetic-account");
        assert_eq!(
            description.service_url,
            "https://example.invalid/api/history-sync/status"
        );
        assert_eq!(description.session_epoch, store.snapshot().session_epoch);
        let fields = serde_json::to_value(description).unwrap();
        assert_eq!(fields.as_object().unwrap().len(), 3);
        assert!(fields.get("token").is_none());
        let mut closed = discovery();
        closed.status.capability = None;
        assert!(closed.proposal_description(&store).is_err());
        let prepared = proposals.prepare(discovery(), &store).unwrap();
        assert_eq!(prepared.description.account_id, "synthetic-account");
        let mut invalid = discovery();
        invalid.status.capability = None;
        assert!(proposals.prepare(invalid, &store).is_err());
        assert!(proposals
            .confirm(&prepared.request_id, &store, &consent)
            .is_ok());
        let previous = proposals.issue(discovery()).unwrap();
        let current = proposals.issue(discovery()).unwrap();
        assert!(proposals.confirm(&previous, &store, &consent).is_err());
        let expected = proposals.confirm(&current, &store, &consent).unwrap();
        assert!(discovery()
            .check_consent(&store, &consent, &expected)
            .is_ok());
        assert!(proposals.confirm(&current, &store, &consent).is_err());
        let changed_service = proposals.issue(discovery()).unwrap();
        let other_endpoint = "https://other.invalid/api/history-sync/status"
            .parse()
            .unwrap();
        assert!(proposals
            .confirm_at_service(&changed_service, &store, &consent, &other_endpoint)
            .is_err());
        assert!(discovery()
            .check_consent(&store, &consent, &expected)
            .is_ok());
        assert!(proposals
            .confirm(&changed_service, &store, &consent)
            .is_err());
        let matching_service = proposals.issue(discovery()).unwrap();
        let expected = proposals
            .confirm_at_service(
                &matching_service,
                &store,
                &consent,
                &"https://example.invalid/api/history-sync/status"
                    .parse()
                    .unwrap(),
            )
            .unwrap();
        let cancelled = proposals.issue(discovery()).unwrap();
        let replacement = proposals.issue(discovery()).unwrap();
        assert!(!proposals.cancel(&cancelled).unwrap());
        assert!(proposals.cancel(&replacement).unwrap());
        assert!(!proposals.cancel(&replacement).unwrap());
        assert!(proposals.confirm(&replacement, &store, &consent).is_err());
        assert!(discovery()
            .check_consent(&store, &consent, &expected)
            .is_ok());
        let mut expired = discovery();
        expired.discovered_at = std::time::Instant::now() - std::time::Duration::from_secs(61);
        assert!(expired.proposal_description(&store).is_err());
        let expired_id = proposals.issue(expired).unwrap();
        assert!(proposals.confirm(&expired_id, &store, &consent).is_err());
        assert!(proposals.confirm(&expired_id, &store, &consent).is_err());
        let stale = proposals.issue(discovery()).unwrap();
        store.rotate_epoch();
        assert!(described.proposal_description(&store).is_err());
        assert!(proposals.confirm(&stale, &store, &consent).is_err());
        assert!(proposals.confirm(&stale, &store, &consent).is_err());
    }

    #[test]
    fn authorized_page_rejects_revoked_consent_then_imports_with_current_generation() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-consent-page-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let fixture: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/history_sync_server.json"))
                .unwrap();
        let bytes = serde_json::to_vec(&fixture["page"]).unwrap();
        let policy = crate::storage::HistoryRetentionPolicy::default();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let old = discovery.confirm_consent(&store, &consent).unwrap();
        consent.revoke().unwrap();
        assert!(discovery
            .apply_page_with_consent(&store, &history, &bytes, 0, &policy, (&consent, &old))
            .is_err());
        let current = discovery.confirm_consent(&store, &consent).unwrap();
        assert!(discovery
            .apply_page_with_consent(&store, &history, &bytes, 0, &policy, (&consent, &old))
            .is_err());
        assert_eq!(
            history
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            0
        );
        assert!(history.local_identity_snapshot(1).unwrap().is_none());
        assert_eq!(
            discovery
                .apply_page_with_consent(&store, &history, &bytes, 0, &policy, (&consent, &current))
                .unwrap(),
            PullOutcome {
                next_cursor: 1,
                has_more: false,
                change_count: 1
            }
        );
        assert_eq!(
            history.local_text_snapshot(1).unwrap().unwrap().raw_text,
            "Synthetic text"
        );
        drop(history);
        std::fs::remove_file(path).unwrap();
    }

    #[tokio::test]
    async fn authorized_ack_confirms_only_with_live_consent_and_preserves_revoked_queue() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-consent-ack-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let entry = serde_json::from_value(serde_json::json!({
            "id": 0, "created_at": "2026-11-19T10:00:00",
            "raw_text": "Synthetic consent ACK", "polished_text": "", "mode": "dictate",
            "context_profile_id": "general.native", "context_label": "General",
            "context_icon_key": "general", "context_family": "general",
            "browser_access_status": "not_applicable", "provider_kind": "local",
            "active_scene_prompt_truncated": false
        }))
        .unwrap();
        history.add(entry).await.unwrap();
        let identity = history.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = history
            .enqueue_local_history_revision("synthetic-account", 1, &identity)
            .unwrap();
        let bytes = serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "synthetic-account",
            "acknowledgements": [{
                "recordId": mutation.record_uuid, "mutationId": mutation.mutation_uuid,
                "clientRevision": mutation.client_revision, "result": "accepted", "serverRevision": 1
            }]
        })).unwrap();
        let consent = crate::history_sync_consent::ConsentStore::default();
        let old = discovery.confirm_consent(&store, &consent).unwrap();
        consent.revoke().unwrap();
        assert!(discovery
            .apply_acknowledgements_with_consent(
                &store,
                &history,
                &bytes,
                std::slice::from_ref(&mutation),
                (&consent, &old)
            )
            .is_err());
        let current = discovery.confirm_consent(&store, &consent).unwrap();
        assert!(discovery
            .apply_acknowledgements_with_consent(
                &store,
                &history,
                &bytes,
                std::slice::from_ref(&mutation),
                (&consent, &old)
            )
            .is_err());
        let pending = history
            .pending_history_revisions("synthetic-account", 200)
            .unwrap();
        assert_eq!(pending.len(), 1);
        assert_eq!(pending[0].mutation, mutation);
        assert_eq!(
            discovery
                .apply_acknowledgements_with_consent(
                    &store,
                    &history,
                    &bytes,
                    std::slice::from_ref(&mutation),
                    (&consent, &current)
                )
                .unwrap(),
            1
        );
        assert!(history
            .pending_history_revisions("synthetic-account", 200)
            .unwrap()
            .is_empty());
        assert_eq!(
            history.local_identity_snapshot(1).unwrap().unwrap(),
            identity
        );
        drop(history);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn consent_confirmation_requires_current_discovery_and_session() {
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let mut discovery = DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let consent = crate::history_sync_consent::ConsentStore::default();
        let expected = discovery.confirm_consent(&store, &consent).unwrap();
        assert!(discovery.check_consent(&store, &consent, &expected).is_ok());
        consent.revoke().unwrap();
        assert!(discovery
            .check_consent(&store, &consent, &expected)
            .is_err());
        discovery.discovered_at = std::time::Instant::now() - std::time::Duration::from_secs(61);
        assert!(discovery.confirm_consent(&store, &consent).is_err());
        discovery.discovered_at = std::time::Instant::now();
        store.rotate_epoch();
        assert!(discovery.confirm_consent(&store, &consent).is_err());
        assert!(discovery
            .check_consent(&store, &consent, &expected)
            .is_err());
    }

    #[tokio::test]
    async fn bound_push_sends_selected_fixed_revision_and_confirms_only_accepted_ack() {
        for scenario in [
            "accepted",
            "conflict",
            "invalid-ack",
            "redirect",
            "oversized",
            "rotated",
            "missing-ack",
            "rejected",
            "consent-revoked",
        ] {
            let accepted = scenario == "accepted";
            let store = Arc::new(SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ));
            let server_store = store.clone();
            let consent = Arc::new(crate::history_sync_consent::ConsentStore::default());
            let server_consent = consent.clone();
            let path = std::env::temp_dir().join(format!(
                "opentypeless-network-push-{}.sqlite",
                uuid::Uuid::new_v4()
            ));
            let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
            let entry = serde_json::from_value(serde_json::json!({
                "id": 0, "created_at": "2026-11-19T10:00:00",
                "raw_text": "Synthetic selected push", "polished_text": "", "mode": "dictate",
                "context_profile_id": "general.native", "context_label": "General",
                "context_icon_key": "general", "context_family": "general",
                "browser_access_status": "not_applicable", "provider_kind": "local",
                "active_scene_prompt_truncated": false
            }))
            .unwrap();
            history.add(entry).await.unwrap();
            let identity = history.local_identity_snapshot(1).unwrap().unwrap();
            let mutation = history
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap();
            let expected_request = history.build_pending_history_request(&mutation).unwrap();
            let retry_request = expected_request.clone();
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let endpoint = format!(
                "http://{}/api/history-sync/status",
                listener.local_addr().unwrap()
            );
            let server_mutation = mutation.clone();
            let server = std::thread::spawn(move || {
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
                let mut socket = loop {
                    match listener.accept() {
                        Ok((socket, _)) => break socket,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            if std::time::Instant::now() >= deadline {
                                return false;
                            }
                            std::thread::sleep(std::time::Duration::from_millis(10));
                        }
                        Err(error) => panic!("{error}"),
                    }
                };
                socket.set_nonblocking(false).unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = Vec::new();
                let mut buffer = [0_u8; 4096];
                let (header_end, content_length) = loop {
                    let length = socket.read(&mut buffer).unwrap();
                    assert!(length > 0);
                    request.extend_from_slice(&buffer[..length]);
                    assert!(request.len() < 16 * 1024);
                    if let Some(end) = request.windows(4).position(|bytes| bytes == b"\r\n\r\n") {
                        let headers = String::from_utf8_lossy(&request[..end]).to_ascii_lowercase();
                        assert!(headers.starts_with("post /api/history-sync/push "));
                        assert!(headers.contains("authorization: bearer synthetic-token"));
                        assert!(headers.contains("content-type: application/json"));
                        assert!(!headers.contains("cookie:"));
                        let content_length: usize = headers
                            .lines()
                            .find_map(|line| line.strip_prefix("content-length: "))
                            .unwrap()
                            .parse()
                            .unwrap();
                        break (end + 4, content_length);
                    }
                };
                while request.len() < header_end + content_length {
                    let length = socket.read(&mut buffer).unwrap();
                    assert!(length > 0);
                    request.extend_from_slice(&buffer[..length]);
                    assert!(request.len() < 16 * 1024);
                }
                assert_eq!(
                    serde_json::from_slice::<serde_json::Value>(
                        &request[header_end..header_end + content_length]
                    )
                    .unwrap(),
                    expected_request
                );
                if scenario == "rotated" {
                    server_store.rotate_epoch();
                }
                if scenario == "consent-revoked" {
                    server_consent.revoke().unwrap();
                }
                let mut acknowledgement = serde_json::json!({
                    "version": 1, "accountId": "synthetic-account",
                    "acknowledgements": [{
                        "recordId": server_mutation.record_uuid,
                        "mutationId": server_mutation.mutation_uuid,
                        "clientRevision": server_mutation.client_revision,
                        "result": if accepted || matches!(scenario, "rotated" | "consent-revoked") { "accepted" } else { "conflict" },
                        "serverRevision": 1
                    }]
                });
                if scenario == "missing-ack" {
                    acknowledgement["acknowledgements"] = serde_json::json!([]);
                } else if scenario == "rejected" {
                    acknowledgement["acknowledgements"][0]["result"] =
                        serde_json::json!("rejected");
                }
                let body = serde_json::to_string(&acknowledgement).unwrap();
                let response = match scenario {
                    "redirect" => "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_string(),
                    "oversized" => "HTTP/1.1 200 OK\r\nContent-Length: 262145\r\nConnection: close\r\n\r\n".to_string(),
                    "invalid-ack" => "HTTP/1.1 200 OK\r\nContent-Length: 2\r\nConnection: close\r\n\r\n{}".to_string(),
                    _ => format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len()),
                };
                socket.write_all(response.as_bytes()).unwrap();
                true
            });
            let discovery = DiscoveredStatus {
                endpoint: endpoint.parse().unwrap(),
                status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
                session: store.snapshot(),
                discovered_at: std::time::Instant::now(),
            };
            let result = discovery
                .push_once(
                    &store,
                    &history,
                    std::slice::from_ref(&mutation),
                    &crate::storage::HistoryRetentionPolicy::default(),
                    (
                        &consent,
                        &consent
                            .grant_synthetic_loopback(
                                "synthetic-account",
                                &discovery.session.session_epoch,
                                &discovery.endpoint,
                            )
                            .unwrap(),
                    ),
                )
                .await;
            let requested = server.join().unwrap();
            if matches!(
                scenario,
                "accepted" | "conflict" | "missing-ack" | "rejected"
            ) {
                assert_eq!(result.unwrap(), usize::from(accepted));
            } else {
                assert!(result.is_err(), "{scenario}");
            }
            assert!(requested);
            assert_eq!(
                history
                    .pending_history_revisions("synthetic-account", 200)
                    .unwrap()
                    .len(),
                usize::from(!accepted)
            );
            if !accepted {
                let pending = history
                    .pending_history_revisions("synthetic-account", 200)
                    .unwrap();
                assert_eq!(pending[0].mutation, mutation);
                assert_eq!(
                    history
                        .build_pending_history_request(&pending[0].mutation)
                        .unwrap(),
                    retry_request
                );
            }
            assert_eq!(
                history.local_identity_snapshot(1).unwrap().unwrap(),
                identity
            );
            drop(history);
            std::fs::remove_file(path).unwrap();
        }
    }

    #[tokio::test]
    async fn revoking_consent_cancels_push_and_pull_while_http_body_remains_open() {
        for pushing in [false, true] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let endpoint = format!(
                "http://{}/api/history-sync/status",
                listener.local_addr().unwrap()
            );
            let (release_sender, release_receiver) = std::sync::mpsc::channel();
            let (headers_sender, headers_receiver) = tokio::sync::oneshot::channel();
            let server = std::thread::spawn(move || {
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
                let mut socket = loop {
                    match listener.accept() {
                        Ok((socket, _)) => break socket,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            if std::time::Instant::now() >= deadline {
                                return false;
                            }
                            std::thread::sleep(std::time::Duration::from_millis(10));
                        }
                        Err(error) => panic!("{error}"),
                    }
                };
                socket.set_nonblocking(false).unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(3)))
                    .unwrap();
                let mut request = Vec::new();
                let mut buffer = [0_u8; 4096];
                let (header_end, content_length) = loop {
                    let length = socket.read(&mut buffer).unwrap();
                    assert!(length > 0);
                    request.extend_from_slice(&buffer[..length]);
                    assert!(request.len() < 16 * 1024);
                    if let Some(end) = request.windows(4).position(|bytes| bytes == b"\r\n\r\n") {
                        let headers = String::from_utf8_lossy(&request[..end]).to_ascii_lowercase();
                        assert!(headers.starts_with(if pushing {
                            "post /api/history-sync/push "
                        } else {
                            "get /api/history-sync/pull?"
                        }));
                        let length = headers
                            .lines()
                            .find_map(|line| line.strip_prefix("content-length: "))
                            .map(|value| value.parse::<usize>().unwrap())
                            .unwrap_or(0);
                        break (end + 4, length);
                    }
                };
                while request.len() < header_end + content_length {
                    let length = socket.read(&mut buffer).unwrap();
                    assert!(length > 0);
                    request.extend_from_slice(&buffer[..length]);
                    assert!(request.len() < 16 * 1024);
                }
                if pushing {
                    let body: serde_json::Value =
                        serde_json::from_slice(&request[header_end..header_end + content_length])
                            .unwrap();
                    assert_eq!(
                        body["mutations"][0]["payload"]["rawText"],
                        "Synthetic hanging push"
                    );
                    assert_eq!(body["mutations"].as_array().unwrap().len(), 1);
                }
                socket
                    .write_all(
                        b"HTTP/1.1 200 OK\r\nContent-Length: 100\r\nConnection: close\r\n\r\n{",
                    )
                    .unwrap();
                headers_sender.send(()).unwrap();
                release_receiver
                    .recv_timeout(std::time::Duration::from_secs(3))
                    .is_ok()
            });
            let path = std::env::temp_dir().join(format!(
                "opentypeless-pull-open-body-{}.sqlite",
                uuid::Uuid::new_v4()
            ));
            let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
            let mutations = if pushing {
                let entry = serde_json::from_value(serde_json::json!({
                    "id": 0, "created_at": "2026-11-19T10:00:00",
                    "raw_text": "Synthetic hanging push", "polished_text": "", "mode": "dictate",
                    "context_profile_id": "general.native", "context_label": "General",
                    "context_icon_key": "general", "context_family": "general",
                    "browser_access_status": "not_applicable", "provider_kind": "local",
                    "active_scene_prompt_truncated": false
                }))
                .unwrap();
                history.add(entry).await.unwrap();
                let identity = history.local_identity_snapshot(1).unwrap().unwrap();
                vec![history
                    .enqueue_local_history_revision("synthetic-account", 1, &identity)
                    .unwrap()]
            } else {
                Vec::new()
            };
            let original_request = if pushing {
                Some(
                    history
                        .build_pending_history_batch_request(&mutations)
                        .unwrap(),
                )
            } else {
                None
            };
            let store = SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            );
            let discovery = DiscoveredStatus {
            endpoint: endpoint.parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
            let consent = crate::history_sync_consent::ConsentStore::default();
            let authorization = consent
                .grant_synthetic_loopback(
                    "synthetic-account",
                    &discovery.session.session_epoch,
                    &discovery.endpoint,
                )
                .unwrap();
            let policy = crate::storage::HistoryRetentionPolicy::default();
            {
                let operation = async {
                    if pushing {
                        discovery
                            .push_once(
                                &store,
                                &history,
                                &mutations,
                                &policy,
                                (&consent, &authorization),
                            )
                            .await
                            .map(|_| ())
                    } else {
                        discovery
                            .pull_once(&store, &history, &policy, (&consent, &authorization))
                            .await
                            .map(|_| ())
                    }
                };
                tokio::pin!(operation);
                let ready = tokio::select! {
                    result = &mut operation => panic!("Sync finished before revocation: {result:?}"),
                    ready = tokio::time::timeout(std::time::Duration::from_secs(2), headers_receiver) => ready,
                };
                assert!(ready.unwrap().is_ok());
                assert!(
                    tokio::time::timeout(std::time::Duration::from_millis(30), &mut operation)
                        .await
                        .is_err()
                );
                consent.revoke().unwrap();
                let outcome =
                    tokio::time::timeout(std::time::Duration::from_millis(500), &mut operation)
                        .await;
                release_sender.send(()).unwrap();
                let released = server.join().unwrap();
                assert!(
                    outcome.is_ok(),
                    "Sync must stop before the server releases its open body"
                );
                let error = outcome.unwrap().unwrap_err();
                assert!(error.to_string().contains("consent"));
                assert!(released);
            }
            assert_eq!(
                history
                    .history_sync_pull_cursor("synthetic-account")
                    .unwrap(),
                0
            );
            if pushing {
                let pending = history
                    .pending_history_revisions("synthetic-account", 200)
                    .unwrap();
                assert_eq!(pending.len(), 1);
                assert_eq!(pending[0].mutation, mutations[0]);
                assert_eq!(
                    history
                        .build_pending_history_batch_request(&mutations)
                        .unwrap(),
                    original_request.unwrap()
                );
                assert!(history.local_identity_snapshot(1).unwrap().is_some());
            } else {
                assert!(history.local_identity_snapshot(1).unwrap().is_none());
            }
            drop(history);
            std::fs::remove_file(path).unwrap();
        }
    }

    #[tokio::test]
    async fn bound_pull_imports_retained_server_page_without_sending_local_history() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let endpoint = format!(
            "http://{}/api/history-sync/status",
            listener.local_addr().unwrap()
        );
        listener.set_nonblocking(true).unwrap();
        let fixture: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/history_sync_server.json"))
                .unwrap();
        let body = serde_json::to_vec(&fixture["page"]).unwrap();
        let server = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
            let mut socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        if std::time::Instant::now() >= deadline {
                            return false;
                        }
                        std::thread::sleep(std::time::Duration::from_millis(10));
                    }
                    Err(error) => panic!("{error}"),
                }
            };
            socket.set_nonblocking(false).unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut bytes = [0_u8; 4096];
            let length = socket.read(&mut bytes).unwrap();
            let request = String::from_utf8_lossy(&bytes[..length]).to_ascii_lowercase();
            assert!(request.starts_with("get /api/history-sync/pull?cursor=0&limit=500 "));
            assert!(request.contains("authorization: bearer synthetic-token"));
            assert!(!request.contains("cookie:"));
            assert!(!request.contains("rawtext"));
            write!(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n",
                body.len()
            )
            .unwrap();
            socket.write_all(&body).unwrap();
            true
        });
        let path = std::env::temp_dir().join(format!(
            "opentypeless-network-pull-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = DiscoveredStatus {
            endpoint: endpoint.parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let consent = crate::history_sync_consent::ConsentStore::default();
        let authorization = consent
            .grant_synthetic_loopback(
                "synthetic-account",
                &discovery.session.session_epoch,
                &discovery.endpoint,
            )
            .unwrap();
        let result = discovery
            .pull_once(
                &store,
                &history,
                &crate::storage::HistoryRetentionPolicy::default(),
                (&consent, &authorization),
            )
            .await;
        let requested = server.join().unwrap();
        assert_eq!(
            result.unwrap(),
            PullOutcome {
                next_cursor: 1,
                has_more: false,
                change_count: 1,
            }
        );
        assert!(requested);
        assert_eq!(
            history.local_text_snapshot(1).unwrap().unwrap().raw_text,
            "Synthetic text"
        );
        drop(history);
        std::fs::remove_file(path).unwrap();
    }

    #[tokio::test]
    async fn bound_pull_rejects_redirect_oversize_and_rotated_session_without_cursor_changes() {
        for scenario in ["redirect", "oversized", "rotated", "consent-revoked"] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let endpoint = format!(
                "http://{}/api/history-sync/status",
                listener.local_addr().unwrap()
            );
            let store = Arc::new(SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ));
            let server_store = store.clone();
            let consent = Arc::new(crate::history_sync_consent::ConsentStore::default());
            let server_consent = consent.clone();
            let server = std::thread::spawn(move || {
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
                let mut socket = loop {
                    match listener.accept() {
                        Ok((socket, _)) => break socket,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            if std::time::Instant::now() >= deadline {
                                return false;
                            }
                            std::thread::sleep(std::time::Duration::from_millis(10));
                        }
                        Err(error) => panic!("{error}"),
                    }
                };
                socket.set_nonblocking(false).unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0_u8; 4096];
                assert!(socket.read(&mut request).unwrap() > 0);
                let response = match scenario {
                    "redirect" => "HTTP/1.1 302 Found\r\nLocation: http://127.0.0.1:1/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_string(),
                    "oversized" => "HTTP/1.1 200 OK\r\nContent-Length: 4194305\r\nConnection: close\r\n\r\n".to_string(),
                    _ => {
                        if scenario == "rotated" {
                            server_store.rotate_epoch();
                        } else {
                            server_consent.revoke().unwrap();
                        }
                        let fixture: serde_json::Value = serde_json::from_str(include_str!("../tests/fixtures/history_sync_server.json")).unwrap();
                        let body = serde_json::to_string(&fixture["page"]).unwrap();
                        format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len())
                    }
                };
                socket.write_all(response.as_bytes()).unwrap();
                true
            });
            let path = std::env::temp_dir().join(format!(
                "opentypeless-pull-failure-{}.sqlite",
                uuid::Uuid::new_v4()
            ));
            let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
            let discovery = DiscoveredStatus {
                endpoint: endpoint.parse().unwrap(),
                status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
                session: store.snapshot(),
                discovered_at: std::time::Instant::now(),
            };
            let result = discovery
                .pull_once(
                    &store,
                    &history,
                    &crate::storage::HistoryRetentionPolicy::default(),
                    (
                        &consent,
                        &consent
                            .grant_synthetic_loopback(
                                "synthetic-account",
                                &discovery.session.session_epoch,
                                &discovery.endpoint,
                            )
                            .unwrap(),
                    ),
                )
                .await;
            let requested = server.join().unwrap();
            assert!(result.is_err(), "{scenario}");
            assert!(requested, "{scenario}");
            assert_eq!(
                history
                    .history_sync_pull_cursor("synthetic-account")
                    .unwrap(),
                0
            );
            assert!(history.local_identity_snapshot(1).unwrap().is_none());
            drop(history);
            std::fs::remove_file(path).unwrap();
        }
    }

    #[test]
    fn page_outcome_preserves_more_pages_and_terminal_tombstone_counts() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-page-outcome-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let discovery = DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let mut fixture: serde_json::Value =
            serde_json::from_str(include_str!("../tests/fixtures/history_sync_server.json"))
                .unwrap();
        fixture["page"]["hasMore"] = serde_json::json!(true);
        let policy = crate::storage::HistoryRetentionPolicy::default();
        assert_eq!(
            discovery
                .apply_page_outcome(
                    &store,
                    &history,
                    &serde_json::to_vec(&fixture["page"]).unwrap(),
                    0,
                    &policy
                )
                .unwrap(),
            PullOutcome {
                next_cursor: 1,
                has_more: true,
                change_count: 1
            }
        );
        assert_eq!(
            discovery
                .apply_page_outcome(
                    &store,
                    &history,
                    &serde_json::to_vec(&fixture["tombstone"]).unwrap(),
                    1,
                    &policy
                )
                .unwrap(),
            PullOutcome {
                next_cursor: 2,
                has_more: false,
                change_count: 1
            }
        );
        assert_eq!(
            history
                .history_sync_pull_cursor("synthetic-account")
                .unwrap(),
            2
        );
        assert!(history.local_identity_snapshot(1).unwrap().is_none());
        drop(history);
        std::fs::remove_file(path).unwrap();
    }

    #[tokio::test]
    async fn pull_preflight_rejects_invalid_ownership_policy_and_endpoint_before_connecting() {
        for scenario in [
            "expired",
            "rotated",
            "logged-out",
            "closed",
            "disabled",
            "age-retention",
            "userinfo",
            "query",
            "fragment",
            "path",
        ] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let path = std::env::temp_dir().join(format!(
                "opentypeless-pull-preflight-{}.sqlite",
                uuid::Uuid::new_v4()
            ));
            let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
            let entry = serde_json::from_value(serde_json::json!({
                "id": 0, "created_at": "2026-11-19T10:00:00",
                "raw_text": "Synthetic preflight text", "polished_text": "", "mode": "dictate",
                "context_profile_id": "general.native", "context_label": "General",
                "context_icon_key": "general", "context_family": "general",
                "browser_access_status": "not_applicable", "provider_kind": "local",
                "active_scene_prompt_truncated": false
            }))
            .unwrap();
            history.add(entry).await.unwrap();
            let identity = history.local_identity_snapshot(1).unwrap().unwrap();
            let mutation = history
                .enqueue_local_history_revision("synthetic-account", 1, &identity)
                .unwrap();
            let store = SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            );
            let mut discovery = DiscoveredStatus {
                endpoint: format!("http://{}/api/history-sync/status", listener.local_addr().unwrap())
                    .parse()
                    .unwrap(),
                status: parse(br#"{"version":1,"accountId":"synthetic-account","capability":{"version":1,"maxPushRecords":200,"maxPullRecords":500,"maxPayloadBytes":1048576,"maxRequestBytes":4194304,"maxResponseBytes":4194304,"audioSupported":false,"citationsSupported":false}}"#).unwrap(),
                session: store.snapshot(),
                discovered_at: std::time::Instant::now(),
            };
            let consent = crate::history_sync_consent::ConsentStore::default();
            let authorization = consent
                .grant_synthetic_loopback(
                    "synthetic-account",
                    &discovery.session.session_epoch,
                    &discovery.endpoint,
                )
                .unwrap();
            let mut policy = crate::storage::HistoryRetentionPolicy::default();
            match scenario {
                "expired" => {
                    discovery.discovered_at =
                        std::time::Instant::now() - std::time::Duration::from_secs(61);
                }
                "rotated" => store.rotate_epoch(),
                "logged-out" => store.0.lock().unwrap().clear(),
                "closed" => discovery.status.capability = None,
                "disabled" => policy.enabled = false,
                "age-retention" => policy.retention_days = 30,
                "userinfo" => discovery.endpoint.set_username("synthetic-user").unwrap(),
                "query" => discovery.endpoint.set_query(Some("unexpected=1")),
                "fragment" => discovery.endpoint.set_fragment(Some("unexpected")),
                "path" => discovery.endpoint.set_path("/unexpected"),
                _ => unreachable!(),
            }
            let result = tokio::time::timeout(
                std::time::Duration::from_secs(1),
                discovery.pull_once(&store, &history, &policy, (&consent, &authorization)),
            )
            .await;
            let connected = match listener.accept() {
                Ok(_) => true,
                Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => false,
                Err(error) => panic!("{error}"),
            };
            assert!(
                result.is_ok(),
                "{scenario}: preflight must not await network"
            );
            assert!(result.unwrap().is_err(), "{scenario}");
            assert!(!connected, "{scenario}: preflight must not connect");
            let push = tokio::time::timeout(
                std::time::Duration::from_secs(1),
                discovery.push_once(
                    &store,
                    &history,
                    std::slice::from_ref(&mutation),
                    &policy,
                    (&consent, &authorization),
                ),
            )
            .await;
            assert!(push.is_ok(), "{scenario}: push must not await network");
            assert!(push.unwrap().is_err(), "{scenario}: push must reject");
            assert!(
                matches!(listener.accept(), Err(error) if error.kind() == std::io::ErrorKind::WouldBlock),
                "{scenario}: push must not connect"
            );
            let pending = history
                .pending_history_revisions("synthetic-account", 200)
                .unwrap();
            assert_eq!(pending.len(), 1);
            assert_eq!(pending[0].mutation, mutation);
            assert_eq!(
                history
                    .history_sync_pull_cursor("synthetic-account")
                    .unwrap(),
                0
            );
            assert_eq!(
                history.local_identity_snapshot(1).unwrap().unwrap(),
                identity
            );
            drop(history);
            std::fs::remove_file(path).unwrap();
        }
    }

    #[tokio::test]
    async fn discovered_status_builds_only_for_bound_session_and_server_limits() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-discovery-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
        let entry: crate::storage::HistoryEntry = serde_json::from_value(serde_json::json!({
            "id": 0, "created_at": "2026-11-19T10:00:00",
            "raw_text": "Synthetic", "polished_text": "", "mode": "dictate",
            "context_profile_id": "general.native", "context_label": "General",
            "context_icon_key": "general", "context_family": "general",
            "browser_access_status": "not_applicable", "provider_kind": "local",
            "active_scene_prompt_truncated": false
        }))
        .unwrap();
        history.add(entry.clone()).await.unwrap();
        history.add(entry).await.unwrap();
        let identity = history.local_identity_snapshot(1).unwrap().unwrap();
        let mutation = history
            .enqueue_local_history_revision("server-account", 1, &identity)
            .unwrap();
        let second_identity = history.local_identity_snapshot(2).unwrap().unwrap();
        let second_mutation = history
            .enqueue_local_history_revision("server-account", 2, &second_identity)
            .unwrap();
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let mut discovery = DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status".parse().unwrap(),
            status: parse(&serde_json::to_vec(&serde_json::json!({
                "version": 1, "accountId": "server-account",
                "capability": {
                    "version": 1, "maxPushRecords": 200, "maxPullRecords": 500,
                    "maxPayloadBytes": 1048576, "maxRequestBytes": 4194304,
                    "maxResponseBytes": 4194304, "audioSupported": false, "citationsSupported": false
                }
            })).unwrap()).unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let mutations = [mutation];
        let consent = crate::history_sync_consent::ConsentStore::default();
        let previous = discovery.confirm_consent(&store, &consent).unwrap();
        let original_request = discovery
            .build_request_with_consent(&store, &history, &mutations, (&consent, &previous))
            .unwrap();
        consent.revoke().unwrap();
        assert!(discovery
            .build_request_with_consent(&store, &history, &mutations, (&consent, &previous))
            .is_err());
        let current = discovery.confirm_consent(&store, &consent).unwrap();
        assert!(discovery
            .build_request_with_consent(&store, &history, &mutations, (&consent, &previous))
            .is_err());
        assert_eq!(
            discovery
                .build_request_with_consent(&store, &history, &mutations, (&consent, &current))
                .unwrap(),
            original_request
        );
        let missing_ack = serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "server-account", "acknowledgements": []
        }))
        .unwrap();
        assert_eq!(
            discovery
                .apply_acknowledgements(&store, &history, &missing_ack, &mutations)
                .unwrap(),
            0
        );
        let empty_page = serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "server-account", "fromCursor": 0,
            "nextCursor": 0, "hasMore": false, "changeSequences": [], "records": []
        }))
        .unwrap();
        assert_eq!(
            discovery
                .apply_page(
                    &store,
                    &history,
                    &empty_page,
                    0,
                    &crate::storage::HistoryRetentionPolicy::default(),
                )
                .unwrap(),
            0
        );
        discovery
            .status
            .capability
            .as_mut()
            .unwrap()
            .max_response_bytes = 1;
        assert!(discovery
            .apply_page(
                &store,
                &history,
                &empty_page,
                0,
                &crate::storage::HistoryRetentionPolicy::default(),
            )
            .is_err());
        discovery
            .status
            .capability
            .as_mut()
            .unwrap()
            .max_response_bytes = 4194304;
        discovery.status.account_id = "another-account".into();
        assert!(discovery
            .apply_page(
                &store,
                &history,
                &empty_page,
                0,
                &crate::storage::HistoryRetentionPolicy::default(),
            )
            .is_err());
        discovery.status.account_id = "server-account".into();
        assert!(discovery
            .build_request(&store, &history, &mutations)
            .is_ok());
        let limits = discovery.status.capability.as_mut().unwrap();
        limits.max_request_bytes = 1;
        assert!(discovery
            .build_request(&store, &history, &mutations)
            .is_err());
        let limits = discovery.status.capability.as_mut().unwrap();
        limits.max_request_bytes = 4194304;
        limits.max_payload_bytes = 1;
        assert!(discovery
            .build_request(&store, &history, &mutations)
            .is_err());
        let limits = discovery.status.capability.as_mut().unwrap();
        limits.max_payload_bytes = 1048576;
        limits.max_push_records = 1;
        assert!(discovery
            .build_request(&store, &history, &[mutations[0].clone(), second_mutation])
            .is_err());
        discovery.status.account_id = "another-account".into();
        assert!(discovery
            .build_request(&store, &history, &mutations)
            .is_err());
        discovery.status.account_id = "server-account".into();
        discovery.discovered_at = std::time::Instant::now() - std::time::Duration::from_secs(61);
        assert!(discovery
            .apply_acknowledgements(&store, &history, &missing_ack, &mutations)
            .is_err());
        assert!(discovery
            .apply_page(
                &store,
                &history,
                &empty_page,
                0,
                &crate::storage::HistoryRetentionPolicy::default(),
            )
            .is_err());
        assert!(discovery
            .build_request(&store, &history, &mutations)
            .is_err());
        discovery.discovered_at = std::time::Instant::now();
        store.rotate_epoch();
        assert!(discovery
            .apply_acknowledgements(&store, &history, &missing_ack, &mutations)
            .is_err());
        assert!(discovery
            .apply_page(
                &store,
                &history,
                &empty_page,
                0,
                &crate::storage::HistoryRetentionPolicy::default(),
            )
            .is_err());
        assert!(discovery
            .build_request(&store, &history, &mutations)
            .is_err());
        discovery.session = store.snapshot();
        discovery.status.capability = None;
        assert!(discovery
            .apply_acknowledgements(&store, &history, &missing_ack, &mutations)
            .is_err());
        assert!(discovery
            .apply_page(
                &store,
                &history,
                &empty_page,
                0,
                &crate::storage::HistoryRetentionPolicy::default(),
            )
            .is_err());
        assert!(discovery
            .build_request(&store, &history, &mutations)
            .is_err());
        discovery.status.capability = Some(
            parse(
                &serde_json::to_vec(&serde_json::json!({
                    "version": 1, "accountId": "server-account", "capability": {
                        "version": 1, "maxPushRecords": 200, "maxPullRecords": 500,
                        "maxPayloadBytes": 1048576, "maxRequestBytes": 4194304,
                        "maxResponseBytes": 4194304, "audioSupported": false,
                        "citationsSupported": false
                    }
                }))
                .unwrap(),
            )
            .unwrap()
            .capability
            .unwrap(),
        );
        let accepted = serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "server-account", "acknowledgements": [{
                "recordId": mutations[0].record_uuid,
                "mutationId": mutations[0].mutation_uuid,
                "clientRevision": mutations[0].client_revision,
                "result": "accepted", "serverRevision": 1
            }]
        }))
        .unwrap();
        assert_eq!(
            discovery
                .apply_acknowledgements(&store, &history, &accepted, &mutations)
                .unwrap(),
            1
        );
        assert_eq!(
            history
                .pending_history_revisions("server-account", 10)
                .unwrap()
                .len(),
            1
        );
        drop(history);
        std::fs::remove_file(path).unwrap();
    }

    #[tokio::test]
    async fn status_discovers_authenticated_account_without_sending_history() {
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let endpoint = format!(
            "http://{}/api/history-sync/status",
            listener.local_addr().unwrap()
        );
        listener.set_nonblocking(true).unwrap();
        let server = std::thread::spawn(move || {
            let deadline = std::time::Instant::now() + std::time::Duration::from_secs(3);
            let mut socket = loop {
                match listener.accept() {
                    Ok((socket, _)) => break socket,
                    Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                        if std::time::Instant::now() >= deadline {
                            return;
                        }
                        std::thread::sleep(std::time::Duration::from_millis(10));
                    }
                    Err(error) => panic!("{error}"),
                }
            };
            socket.set_nonblocking(false).unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut bytes = [0_u8; 4096];
            let length = socket.read(&mut bytes).unwrap();
            let request = String::from_utf8_lossy(&bytes[..length]).to_ascii_lowercase();
            assert!(request.starts_with("get /api/history-sync/status "));
            assert!(request.contains("authorization: bearer synthetic-token"));
            assert!(!request.contains("cookie:"));
            assert!(!request.contains("rawtext"));
            let body = r#"{"version":1,"accountId":"server-account","capability":null}"#;
            write!(
                socket,
                "HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}",
                body.len()
            )
            .unwrap();
        });
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let result = discover_endpoint(&endpoint, &store, &store.snapshot()).await;
        server.join().unwrap();
        assert_eq!(result.unwrap().account_id, "server-account");
    }

    #[test]
    fn discovery_nonempty_page_limits_reject_without_writes_then_allow_import() {
        let path = std::env::temp_dir().join(format!(
            "opentypeless-discovery-page-{}.sqlite",
            uuid::Uuid::new_v4()
        ));
        let history = crate::storage::HistoryStore::new(path.clone()).unwrap();
        let store = SessionTokenStore(
            Arc::new(Mutex::new("synthetic-token".into())),
            AtomicBool::new(true),
            Mutex::new(uuid::Uuid::new_v4().to_string()),
        );
        let mut discovery = DiscoveredStatus {
            endpoint: "https://example.invalid/api/history-sync/status"
                .parse()
                .unwrap(),
            status: parse(
                &serde_json::to_vec(&serde_json::json!({
                    "version": 1, "accountId": "server-account", "capability": {
                        "version": 1, "maxPushRecords": 200, "maxPullRecords": 1,
                        "maxPayloadBytes": 1048576, "maxRequestBytes": 4194304,
                        "maxResponseBytes": 4194304, "audioSupported": false,
                        "citationsSupported": false
                    }
                }))
                .unwrap(),
            )
            .unwrap(),
            session: store.snapshot(),
            discovered_at: std::time::Instant::now(),
        };
        let records = (1..=2)
            .map(|sequence| {
                serde_json::json!({
                    "changeSequence": sequence,
                    "recordId": format!("bbbbbbbb-bbbb-4bbb-8bbb-{sequence:012}"),
                    "serverRevision": 1, "deleted": false, "payload": {
                        "rawText": format!("Synthetic remote {sequence}"),
                        "polishedText": "", "mode": "dictate", "answerText": null,
                        "createdAt": "2026-11-20T00:00:00", "durationMs": null, "language": null
                    }
                })
            })
            .collect::<Vec<_>>();
        let bytes = serde_json::to_vec(&serde_json::json!({
            "version": 1, "accountId": "server-account", "fromCursor": 0,
            "nextCursor": 2, "hasMore": false, "changeSequences": [1, 2],
            "records": records
        }))
        .unwrap();
        let policy = crate::storage::HistoryRetentionPolicy::default();
        assert!(discovery
            .apply_page(&store, &history, &bytes, 0, &policy)
            .is_err());
        assert!(history.local_identity_snapshot(1).unwrap().is_none());
        assert_eq!(
            history.history_sync_pull_cursor("server-account").unwrap(),
            0
        );
        let limits = discovery.status.capability.as_mut().unwrap();
        limits.max_pull_records = 2;
        limits.max_payload_bytes = 1;
        assert!(discovery
            .apply_page(&store, &history, &bytes, 0, &policy)
            .is_err());
        assert!(history.local_identity_snapshot(1).unwrap().is_none());
        assert_eq!(
            history.history_sync_pull_cursor("server-account").unwrap(),
            0
        );
        discovery
            .status
            .capability
            .as_mut()
            .unwrap()
            .max_payload_bytes = 1048576;
        assert_eq!(
            discovery
                .apply_page(&store, &history, &bytes, 0, &policy)
                .unwrap(),
            2
        );
        assert_eq!(
            history.local_text_snapshot(2).unwrap().unwrap().raw_text,
            "Synthetic remote 2"
        );
        assert_eq!(
            history.history_sync_pull_cursor("server-account").unwrap(),
            2
        );
        drop(history);
        std::fs::remove_file(path).unwrap();
    }

    #[test]
    fn status_rejects_invalid_fields_and_capability_limits() {
        let capability = serde_json::json!({
            "version": 1, "maxPushRecords": 200, "maxPullRecords": 500,
            "maxPayloadBytes": 1048576, "maxRequestBytes": 4194304,
            "maxResponseBytes": 4194304, "audioSupported": false, "citationsSupported": false
        });
        let valid = serde_json::json!({"version": 1, "accountId": "server-account", "capability": capability});
        assert!(parse(&serde_json::to_vec(&valid).unwrap()).is_ok());
        for (field, value) in [
            ("maxPushRecords", serde_json::json!(201)),
            ("maxResponseBytes", serde_json::json!(0)),
            ("audioSupported", serde_json::json!(true)),
            ("maxPayloadBytes", serde_json::json!(1.5)),
        ] {
            let mut invalid = valid.clone();
            invalid["capability"][field] = value;
            assert!(parse(&serde_json::to_vec(&invalid).unwrap()).is_err());
        }
        for bytes in [
            br#"{"version":1,"accountId":"server-account"}"#.to_vec(),
            br#"{"version":1,"accountId":"server-account","accountId":"other","capability":null}"#
                .to_vec(),
            br#"{"version":1,"accountId":" bad ","capability":null}"#.to_vec(),
            vec![b' '; 16 * 1024 + 1],
        ] {
            assert!(parse(&bytes).is_err());
        }
    }

    #[tokio::test]
    async fn status_refuses_redirects_oversized_bodies_and_late_sessions() {
        for scenario in ["redirect", "oversized", "rotated"] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let endpoint = format!(
                "http://{}/api/history-sync/status",
                listener.local_addr().unwrap()
            );
            let store = Arc::new(SessionTokenStore(
                Arc::new(Mutex::new("synthetic-token".into())),
                AtomicBool::new(true),
                Mutex::new(uuid::Uuid::new_v4().to_string()),
            ));
            let expected = store.snapshot();
            let server_store = store.clone();
            let server = std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut bytes = [0_u8; 4096];
                assert!(socket.read(&mut bytes).unwrap() > 0);
                if scenario == "rotated" {
                    server_store.rotate_epoch();
                }
                let response = match scenario {
                    "redirect" => "HTTP/1.1 302 Found\r\nLocation: https://untrusted.invalid/\r\nContent-Length: 0\r\nConnection: close\r\n\r\n".to_string(),
                    "oversized" => "HTTP/1.1 200 OK\r\nContent-Length: 16385\r\nConnection: close\r\n\r\n".to_string(),
                    _ => {
                        let body = r#"{"version":1,"accountId":"server-account","capability":null}"#;
                        format!("HTTP/1.1 200 OK\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{body}", body.len())
                    }
                };
                socket.write_all(response.as_bytes()).unwrap();
            });
            let error = discover_endpoint(&endpoint, &store, &expected)
                .await
                .unwrap_err();
            server.join().unwrap();
            assert!(!error.to_string().contains("synthetic-token"));
            if scenario == "rotated" {
                assert_eq!(error.to_string(), "History sync session unavailable");
            }
        }
    }
}
