use serde::{Deserialize, Serialize};
use std::time::Duration;
use tauri::Emitter;

/// Structured error sent to the frontend via Tauri events.
/// The frontend uses `code` to look up an i18n-translated message.
#[derive(Debug, Clone, Serialize)]
pub struct UserError {
    pub code: String,
    pub details: Option<String>,
    pub retry_count: u32,
}

/// Internal error type used throughout the Rust backend.
/// Provides `is_retryable()` for retry logic and `to_user_error()` for frontend display.
#[derive(Debug)]
pub enum AppError {
    Network(String),
    Timeout(Duration),
    Api { status: u16, body: String },
    Auth(String),
    Quota(String),
    LlmQuota(String),
    Output(String),
    OutputUnconfirmed(String),
    Config(String),
    CloudSessionInvalid,
}

const CLOUD_SESSION_INVALID_EVENT: &str = "auth:session-invalid";

#[derive(Debug, Deserialize)]
struct ManagedCloudErrorEnvelope {
    error: ManagedCloudErrorBody,
}

#[derive(Debug, Deserialize)]
struct ManagedCloudErrorBody {
    code: String,
}

pub fn managed_cloud_error(status: u16, body: &str) -> Option<AppError> {
    if status != 401 {
        return None;
    }
    let envelope = serde_json::from_str::<ManagedCloudErrorEnvelope>(body).ok()?;
    (envelope.error.code == "AUTH_SESSION_INVALID").then_some(AppError::CloudSessionInvalid)
}

pub fn notify_cloud_session_invalid<F>(error: &AppError, notify: F) -> bool
where
    F: FnOnce(&'static str),
{
    if !matches!(error, AppError::CloudSessionInvalid) {
        return false;
    }
    notify(CLOUD_SESSION_INVALID_EVENT);
    true
}

pub fn emit_cloud_session_invalid(app_handle: &tauri::AppHandle, error: &AppError) -> bool {
    notify_cloud_session_invalid(error, |event| {
        let _ = app_handle.emit(event, ());
    })
}

pub fn emit_owned_cloud_session_invalid<R: tauri::Runtime>(
    app_handle: &tauri::AppHandle<R>,
    error: &AppError,
    session_epoch: &str,
) -> bool {
    if !matches!(error, AppError::CloudSessionInvalid)
        || uuid::Uuid::parse_str(session_epoch).is_err()
    {
        return false;
    }
    app_handle
        .emit(
            "auth:session-invalid-owned",
            serde_json::json!({ "sessionEpoch": session_epoch }),
        )
        .is_ok()
}

impl AppError {
    pub fn is_retryable(&self) -> bool {
        match self {
            AppError::Network(_) => true,
            AppError::Timeout(_) => true,
            AppError::Api { status, .. } => *status >= 500,
            AppError::Auth(_) => false,
            AppError::Quota(_) => false,
            AppError::LlmQuota(_) => false,
            AppError::Output(_) => false,
            AppError::OutputUnconfirmed(_) => false,
            AppError::Config(_) => false,
            AppError::CloudSessionInvalid => false,
        }
    }

    pub fn to_user_error(&self) -> UserError {
        let (code, details) = match self {
            AppError::Network(msg) => ("stt_timeout".to_string(), Some(msg.clone())),
            AppError::Timeout(_) => ("stt_timeout".to_string(), None),
            AppError::Api { status, body: _ } => {
                if *status == 401 || *status == 403 {
                    ("stt_invalid_key".to_string(), None)
                } else {
                    ("stt_failed".to_string(), Some(format!("HTTP {}", status)))
                }
            }
            AppError::Auth(msg) => ("stt_invalid_key".to_string(), Some(msg.clone())),
            AppError::Quota(msg) => ("stt_quota_exceeded".to_string(), Some(msg.clone())),
            AppError::LlmQuota(msg) => ("llm_quota_exceeded".to_string(), Some(msg.clone())),
            AppError::Output(msg) => ("output_fallback_clipboard".to_string(), Some(msg.clone())),
            AppError::OutputUnconfirmed(msg) => {
                ("output_fallback_clipboard".to_string(), Some(msg.clone()))
            }
            AppError::Config(msg) => {
                let normalized = msg.to_ascii_lowercase();
                let code = if normalized.contains("speech recognition is restricted") {
                    "speech_permission_restricted"
                } else if normalized.contains("speech recognition")
                    && (normalized.contains("permission")
                        || normalized.contains("not authorized")
                        || normalized.contains("waiting for")
                        || normalized.contains("authorization status"))
                {
                    "speech_permission_required"
                } else if normalized
                    .contains("apple speech recognizer could not be created for this language")
                    || normalized
                        .contains("apple speech is not currently available for this language")
                {
                    "speech_language_unavailable"
                } else {
                    "stt_failed"
                };
                (code.to_string(), Some(msg.clone()))
            }
            AppError::CloudSessionInvalid => ("stt_failed".to_string(), None),
        };
        UserError {
            code,
            details,
            retry_count: 0,
        }
    }

    pub fn with_retry_count(self, count: u32) -> UserError {
        let mut ue = self.to_user_error();
        ue.retry_count = count;
        ue
    }
}

impl std::fmt::Display for AppError {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        match self {
            AppError::Network(msg) => write!(f, "Network error: {}", msg),
            AppError::Timeout(d) => write!(f, "Timeout after {:.1}s", d.as_secs_f64()),
            AppError::Api { status, body } => write!(f, "API error {}: {}", status, body),
            AppError::Auth(msg) => write!(f, "Auth error: {}", msg),
            AppError::Quota(msg) => write!(f, "Quota error: {}", msg),
            AppError::LlmQuota(msg) => write!(f, "LLM quota error: {}", msg),
            AppError::Output(msg) => write!(f, "Output error: {}", msg),
            AppError::OutputUnconfirmed(msg) => write!(f, "Output result unconfirmed: {}", msg),
            AppError::Config(msg) => write!(f, "Config error: {}", msg),
            AppError::CloudSessionInvalid => write!(f, "Managed cloud session is invalid"),
        }
    }
}

impl std::error::Error for AppError {}

pub fn request_error_message(error: reqwest::Error) -> String {
    error.without_url().to_string()
}

impl From<reqwest::Error> for AppError {
    fn from(e: reqwest::Error) -> Self {
        let e = e.without_url();
        if e.is_timeout() {
            AppError::Timeout(Duration::from_secs(30))
        } else if let Some(status) = e.status() {
            AppError::Api {
                status: status.as_u16(),
                body: e.to_string(),
            }
        } else {
            AppError::Network(e.to_string())
        }
    }
}

/// Retry an async operation with exponential backoff.
///
/// - `max_retries`: number of retries (0 = no retry)
/// - `f`: closure returning a Future that produces Result<T, AppError>
///
/// Emits a `pipeline:retry` event on each retry attempt.
pub async fn with_retry<F, Fut, T>(
    app_handle: &tauri::AppHandle,
    max_retries: u32,
    f: F,
) -> Result<T, AppError>
where
    F: Fn() -> Fut,
    Fut: std::future::Future<Output = Result<T, AppError>>,
{
    let mut last_error: Option<AppError> = None;
    for attempt in 0..=max_retries {
        match f().await {
            Ok(result) => return Ok(result),
            Err(e) if e.is_retryable() && attempt < max_retries => {
                let delay_ms = 1000 * 2u64.pow(attempt);
                tracing::warn!(
                    "Retryable error (attempt {}/{}): {}, retrying in {}ms",
                    attempt + 1,
                    max_retries,
                    e,
                    delay_ms
                );
                let _ = app_handle.emit(
                    "pipeline:retry",
                    serde_json::json!({
                        "attempt": attempt + 1,
                        "max": max_retries,
                        "error": e.to_string(),
                    }),
                );
                tokio::time::sleep(Duration::from_millis(delay_ms)).await;
                last_error = Some(e);
            }
            Err(e) => return Err(e),
        }
    }
    Err(last_error.unwrap())
}

#[cfg(test)]
mod tests {
    #[test]
    fn websocket_http_display_omits_response_headers_and_body() {
        for status in [400, 401, 403, 429, 503] {
            let response = tokio_tungstenite::tungstenite::http::Response::builder()
                .status(status)
                .header("x-private-marker", "synthetic-header-secret")
                .body(Some(b"synthetic-api-key private spoken words".to_vec()))
                .unwrap();
            let websocket_error = tokio_tungstenite::tungstenite::Error::Http(response);
            let formatted = websocket_error.to_string();
            assert!(formatted.contains(&status.to_string()));
            assert!(!formatted.contains("synthetic-header-secret"));
            assert!(!formatted.contains("synthetic-api-key"));
            assert!(!formatted.contains("private spoken words"));
            let mapped = super::AppError::Network(formatted);
            assert!(!mapped.to_string().contains("synthetic-api-key"));
        }
    }

    use super::*;
    use std::time::Duration;

    #[tokio::test]
    async fn request_url_removal_preserves_http_status_and_retry_classification() {
        use std::io::{Read, Write};
        for (status, retryable, code) in
            [(401, false, "stt_invalid_key"), (503, true, "stt_failed")]
        {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            let address = listener.local_addr().unwrap();
            std::thread::spawn(move || {
                let (mut socket, _) = listener.accept().unwrap();
                socket
                    .set_read_timeout(Some(Duration::from_secs(5)))
                    .unwrap();
                let mut request = [0_u8; 4096];
                assert!(socket.read(&mut request).unwrap() > 0);
                let response = format!(
                    "HTTP/1.1 {status} Error\r\nContent-Length: 0\r\nConnection: close\r\n\r\n"
                );
                let _ = socket.write_all(response.as_bytes());
            });
            let response = reqwest::Client::builder()
                .no_proxy()
                .timeout(Duration::from_secs(5))
                .build()
                .unwrap()
                .get(format!("http://{address}/?api_key=private-status-secret"))
                .send()
                .await
                .unwrap();
            let failure = response.error_for_status().unwrap_err();
            assert!(failure.url().is_some());
            let error = AppError::from(failure);
            assert!(matches!(&error, AppError::Api { status: actual, .. } if *actual == status));
            assert_eq!(error.is_retryable(), retryable);
            assert_eq!(error.to_user_error().code, code);
            assert!(!error.to_string().contains("private-status-secret"));
            assert!(!error.to_string().contains(&address.to_string()));
        }
    }

    #[tokio::test]
    async fn diagnostic_request_errors_preserve_the_reason_without_the_url() {
        let failure = reqwest::Client::builder()
            .no_proxy()
            .build()
            .unwrap()
            .get("ftp://example.invalid/private-model?api_key=diagnostic-secret")
            .send()
            .await
            .unwrap_err();
        let expected = failure.to_string();
        let message = request_error_message(failure);
        assert!(expected.contains("diagnostic-secret"));
        assert!(!message.contains("diagnostic-secret"));
        assert!(!message.contains("example.invalid"));
        assert!(!message.is_empty());
    }

    #[tokio::test]
    async fn request_errors_do_not_expose_endpoint_query_secrets() {
        let failure = reqwest::Client::builder()
            .no_proxy()
            .build()
            .unwrap()
            .get("ftp://example.invalid/private-model?api_key=private-query-secret")
            .send()
            .await
            .unwrap_err();
        assert!(failure.url().is_some());
        let error = AppError::from(failure);
        assert!(matches!(error, AppError::Network(_)));
        assert!(error.is_retryable());
        assert!(!error.to_string().contains("private-query-secret"));
        assert!(!error.to_string().contains("example.invalid"));
        assert!(!format!("{:?}", error.to_user_error()).contains("private-query-secret"));
    }

    #[test]
    fn speech_permission_errors_keep_their_specific_reason() {
        assert_eq!(
            AppError::Config("Speech Recognition permission is denied for OpenTypeless".into())
                .to_user_error()
                .code,
            "speech_permission_required"
        );
        assert_eq!(
            AppError::Config("Speech Recognition is restricted on this Mac".into())
                .to_user_error()
                .code,
            "speech_permission_restricted"
        );
        assert_eq!(
            AppError::Config(
                "Apple Speech recognizer could not be created for this language".into()
            )
            .to_user_error()
            .code,
            "speech_language_unavailable"
        );
        assert_eq!(
            AppError::Config("Unknown recognition failure".into())
                .to_user_error()
                .code,
            "stt_failed"
        );
    }

    #[test]
    fn test_network_error_is_retryable() {
        let err = AppError::Network("connection reset".to_string());
        assert!(err.is_retryable());
    }

    #[test]
    fn test_timeout_is_retryable() {
        let err = AppError::Timeout(Duration::from_secs(30));
        assert!(err.is_retryable());
    }

    #[test]
    fn test_500_is_retryable() {
        let err = AppError::Api {
            status: 500,
            body: "internal error".to_string(),
        };
        assert!(err.is_retryable());
    }

    #[test]
    fn test_503_is_retryable() {
        let err = AppError::Api {
            status: 503,
            body: "service unavailable".to_string(),
        };
        assert!(err.is_retryable());
    }

    #[test]
    fn test_401_is_not_retryable() {
        let err = AppError::Api {
            status: 401,
            body: "unauthorized".to_string(),
        };
        assert!(!err.is_retryable());
    }

    #[test]
    fn test_403_is_not_retryable() {
        let err = AppError::Api {
            status: 403,
            body: "forbidden".to_string(),
        };
        assert!(!err.is_retryable());
    }

    #[test]
    fn test_auth_not_retryable() {
        let err = AppError::Auth("bad key".to_string());
        assert!(!err.is_retryable());
    }

    #[test]
    fn test_output_not_retryable() {
        let err = AppError::Output("enigo failed".to_string());
        assert!(!err.is_retryable());
    }

    #[test]
    fn test_config_not_retryable() {
        let err = AppError::Config("bad config".to_string());
        assert!(!err.is_retryable());
    }

    #[test]
    fn test_quota_is_not_retryable() {
        let err = AppError::Quota("quota exceeded".to_string());
        assert!(!err.is_retryable());
    }

    #[test]
    fn test_quota_maps_to_quota_code() {
        let err = AppError::Quota("quota exceeded".to_string());
        let ue = err.to_user_error();
        assert_eq!(ue.code, "stt_quota_exceeded");
        assert_eq!(ue.details.as_deref(), Some("quota exceeded"));
    }

    #[test]
    fn test_llm_quota_maps_to_llm_quota_code() {
        let err = AppError::LlmQuota("quota exceeded".to_string());
        let ue = err.to_user_error();
        assert_eq!(ue.code, "llm_quota_exceeded");
        assert_eq!(ue.details.as_deref(), Some("quota exceeded"));
    }

    #[test]
    fn test_401_maps_to_invalid_key_code() {
        let err = AppError::Api {
            status: 401,
            body: "".to_string(),
        };
        let ue = err.to_user_error();
        assert_eq!(ue.code, "stt_invalid_key");
    }

    #[test]
    fn cloud_session_invalid_envelope_requires_exact_401_code() {
        let body = r#"{"error":{"code":"AUTH_SESSION_INVALID","message":"Session expired"}}"#;

        assert!(matches!(
            managed_cloud_error(401, body),
            Some(AppError::CloudSessionInvalid)
        ));
        assert!(managed_cloud_error(403, body).is_none());
        assert!(managed_cloud_error(
            401,
            r#"{"error":{"code":"AUTH_REQUIRED","message":"Authentication required"}}"#
        )
        .is_none());
    }

    #[test]
    fn cloud_session_invalid_event_is_emitted_once_per_boundary_call() {
        let mut events = Vec::new();
        let emitted = notify_cloud_session_invalid(&AppError::CloudSessionInvalid, |event| {
            events.push(event.to_string());
        });

        assert!(emitted);
        assert_eq!(events, vec!["auth:session-invalid"]);

        let mut byok_events = Vec::new();
        let byok = AppError::Api {
            status: 401,
            body: "invalid provider key".to_string(),
        };
        assert!(!notify_cloud_session_invalid(&byok, |event| {
            byok_events.push(event.to_string());
        }));
        assert!(byok_events.is_empty());
    }

    #[test]
    fn owned_session_invalidation_preserves_request_identity_without_exposing_token() {
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let received = std::sync::Arc::new(std::sync::Mutex::new(Vec::<serde_json::Value>::new()));
        let captured = received.clone();
        let listener = app.listen_any("auth:session-invalid-owned", move |event| {
            captured
                .lock()
                .unwrap()
                .push(serde_json::from_str(event.payload()).unwrap());
        });
        let epoch = uuid::Uuid::new_v4().to_string();
        assert!(emit_owned_cloud_session_invalid(
            app.handle(),
            &AppError::CloudSessionInvalid,
            &epoch,
        ));
        assert_eq!(
            *received.lock().unwrap(),
            vec![serde_json::json!({ "sessionEpoch": epoch })]
        );
        let byok = AppError::Api {
            status: 401,
            body: "synthetic-provider-secret".into(),
        };
        assert!(!emit_owned_cloud_session_invalid(
            app.handle(),
            &byok,
            &epoch
        ));
        assert_eq!(received.lock().unwrap().len(), 1);
        app.unlisten(listener);
    }

    #[test]
    fn polish_failure_uses_its_captured_session_epoch() {
        let source = include_str!("pipeline.rs");
        let polish = source
            .split("async fn polish_text(&self, input: PolishTextInput<'_>)")
            .nth(1)
            .unwrap()
            .split("pub async fn")
            .next()
            .unwrap();
        assert!(polish.contains("emit_owned_cloud_session_invalid"));
        assert!(polish.contains("session_epoch.as_deref()"));
        assert!(!polish.contains("emit_cloud_session_invalid("));
    }

    #[test]
    fn pipeline_stt_failures_do_not_emit_unowned_session_invalidation() {
        let source = include_str!("pipeline.rs");
        assert!(!source.contains("emit_cloud_session_invalid("));
        assert!(source.contains("stt_session_epoch.as_deref()"));
    }

    #[test]
    fn test_403_maps_to_invalid_key_code() {
        let err = AppError::Api {
            status: 403,
            body: "".to_string(),
        };
        let ue = err.to_user_error();
        assert_eq!(ue.code, "stt_invalid_key");
    }

    #[test]
    fn test_500_maps_to_stt_failed_code() {
        let err = AppError::Api {
            status: 500,
            body: "".to_string(),
        };
        let ue = err.to_user_error();
        assert_eq!(ue.code, "stt_failed");
    }

    #[test]
    fn test_network_maps_to_timeout_code() {
        let err = AppError::Network("timeout".to_string());
        let ue = err.to_user_error();
        assert_eq!(ue.code, "stt_timeout");
    }

    #[test]
    fn test_timeout_maps_to_timeout_code() {
        let err = AppError::Timeout(Duration::from_secs(10));
        let ue = err.to_user_error();
        assert_eq!(ue.code, "stt_timeout");
    }

    #[test]
    fn test_output_maps_to_fallback_code() {
        let err = AppError::Output("keyboard failed".to_string());
        let ue = err.to_user_error();
        assert_eq!(ue.code, "output_fallback_clipboard");
    }

    #[test]
    fn test_with_retry_count() {
        let err = AppError::Timeout(Duration::from_secs(10));
        let ue = err.with_retry_count(2);
        assert_eq!(ue.retry_count, 2);
    }

    #[test]
    fn test_display_format() {
        let err = AppError::Network("timeout".to_string());
        assert!(err.to_string().contains("Network error"));

        let err = AppError::Timeout(Duration::from_secs(5));
        assert!(err.to_string().contains("Timeout"));

        let err = AppError::Api {
            status: 429,
            body: "rate limited".to_string(),
        };
        assert!(err.to_string().contains("429"));
    }
}
