use serde::Serialize;
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
    Config(String),
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
            AppError::Config(_) => false,
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
            AppError::Config(msg) => ("stt_failed".to_string(), Some(msg.clone())),
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
            AppError::Config(msg) => write!(f, "Config error: {}", msg),
        }
    }
}

impl std::error::Error for AppError {}

impl From<reqwest::Error> for AppError {
    fn from(e: reqwest::Error) -> Self {
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
    use super::*;
    use std::time::Duration;

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
