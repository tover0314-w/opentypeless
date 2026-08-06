//! Microsoft Entra ID credentials and endpoint detection for Azure AI services.
//!
//! Many Azure tenants set `disableLocalAuth=true` on Cognitive Services accounts, which
//! removes API keys entirely. Those resources can only be reached with an Entra ID bearer
//! token, and such tokens expire roughly hourly — so a pasted secret is not workable.
//!
//! Tokens are sourced from the Azure CLI (`az account get-access-token`), which keeps this
//! dependency-free and reuses the sign-in the user already has. Tokens are cached in process
//! and renewed shortly before expiry. This path is only used when the API key is left blank;
//! key-based BYOK keeps working with no Azure CLI installed.

use std::sync::Arc;
use std::time::{Duration, SystemTime};

use tokio::sync::Mutex;

use crate::error::AppError;

/// Audience for Azure AI / Cognitive Services data-plane calls.
const SCOPE: &str = "https://cognitiveservices.azure.com";

/// Host suffixes served by Azure AI data-plane endpoints.
const AZURE_AI_HOST_SUFFIXES: &[&str] = &[
    ".openai.azure.com",
    ".cognitiveservices.azure.com",
    ".services.ai.azure.com",
    ".api.cognitive.microsoft.com",
];

/// True when a URL points at an Azure AI endpoint.
///
/// Used by the speech-to-text path, where the provider is the generic
/// "custom OpenAI-compatible" one and the endpoint is the only signal that
/// Entra ID should be used instead of an unauthenticated local server.
pub fn is_azure_ai_endpoint(url: &str) -> bool {
    let Ok(parsed) = url::Url::parse(url.trim()) else {
        return false;
    };
    let Some(host) = parsed.host_str() else {
        return false;
    };
    let host = host.to_ascii_lowercase();
    AZURE_AI_HOST_SUFFIXES
        .iter()
        .any(|suffix| host.ends_with(suffix))
}

/// Renew this long before the token actually expires, so a request never starts with a
/// credential that dies mid-flight.
const RENEW_MARGIN: Duration = Duration::from_secs(300);

#[derive(Clone)]
struct CachedToken {
    value: String,
    expires_at: SystemTime,
}

/// Redacted so a stray log line or test failure can never print a bearer token.
impl std::fmt::Debug for CachedToken {
    fn fmt(&self, f: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        f.debug_struct("CachedToken")
            .field("value", &"<redacted>")
            .field("expires_at", &self.expires_at)
            .finish()
    }
}

impl CachedToken {
    fn is_usable(&self) -> bool {
        SystemTime::now() + RENEW_MARGIN < self.expires_at
    }
}

#[derive(Default)]
pub struct EntraCredential {
    cached: Mutex<Option<CachedToken>>,
}

impl EntraCredential {
    /// Returns a bearer token, reusing the cached one until it is close to expiring.
    pub async fn access_token(&self) -> Result<String, AppError> {
        let mut cached = self.cached.lock().await;

        if let Some(token) = cached.as_ref() {
            if token.is_usable() {
                return Ok(token.value.clone());
            }
        }

        let fresh = acquire_token().await?;
        let value = fresh.value.clone();
        *cached = Some(fresh);
        Ok(value)
    }
}

/// Process-wide credential so the token is fetched once and shared by polish, Ask,
/// connection tests, and model listing.
pub fn shared() -> Arc<EntraCredential> {
    use std::sync::OnceLock;
    static SHARED: OnceLock<Arc<EntraCredential>> = OnceLock::new();
    SHARED
        .get_or_init(|| Arc::new(EntraCredential::default()))
        .clone()
}

/// Attaches credentials to an OpenAI-compatible audio request.
///
/// A pasted key always wins. Otherwise an Azure endpoint means Entra ID, while any other
/// endpoint (a local Speaches or faster-whisper server) is left unauthenticated.
pub async fn authorize_audio_request(
    request: reqwest::RequestBuilder,
    api_key: &str,
    endpoint: &str,
) -> Result<reqwest::RequestBuilder, AppError> {
    let api_key = api_key.trim();
    if !api_key.is_empty() {
        return Ok(request.header("Authorization", format!("Bearer {api_key}")));
    }

    if is_azure_ai_endpoint(endpoint) {
        let token = shared().access_token().await?;
        return Ok(request.header("Authorization", format!("Bearer {token}")));
    }

    Ok(request)
}

async fn acquire_token() -> Result<CachedToken, AppError> {
    let program = azure_cli_program()?;
    let output = tokio::process::Command::new(&program)
        .args([
            "account",
            "get-access-token",
            "--resource",
            SCOPE,
            "--output",
            "json",
        ])
        .output()
        .await
        .map_err(|e| {
            AppError::Config(format!(
                "Azure sign-in requires the Azure CLI. Install it and run `az login`, \
                 or enter an Azure OpenAI API key instead ({e})."
            ))
        })?;

    if !output.status.success() {
        let stderr = String::from_utf8_lossy(&output.stderr);
        return Err(AppError::Config(format!(
            "Could not get an Entra ID token. Run `az login`, or enter an Azure OpenAI API key instead. {}",
            stderr.trim()
        )));
    }

    parse_token_response(&String::from_utf8_lossy(&output.stdout))
}

#[cfg(target_os = "windows")]
const AZURE_CLI_BINARY: &str = "az.cmd";

#[cfg(not(target_os = "windows"))]
const AZURE_CLI_BINARY: &str = "az";

/// Well-known Azure CLI install locations.
///
/// A desktop app launched from Finder or a `.desktop` entry inherits a minimal `PATH`
/// that excludes Homebrew and most package-manager prefixes, so resolving the binary by
/// name alone fails in exactly the case that matters — the shipped app, not `tauri dev`.
#[cfg(target_os = "macos")]
const AZURE_CLI_FALLBACK_PATHS: &[&str] = &[
    "/opt/homebrew/bin/az",
    "/usr/local/bin/az",
    "/opt/local/bin/az",
];

#[cfg(target_os = "linux")]
const AZURE_CLI_FALLBACK_PATHS: &[&str] = &[
    "/usr/bin/az",
    "/usr/local/bin/az",
    "/snap/bin/az",
    "/home/linuxbrew/.linuxbrew/bin/az",
];

#[cfg(target_os = "windows")]
const AZURE_CLI_FALLBACK_PATHS: &[&str] = &[
    r"C:\Program Files\Microsoft SDKs\Azure\CLI2\wbin\az.cmd",
    r"C:\Program Files (x86)\Microsoft SDKs\Azure\CLI2\wbin\az.cmd",
];

#[cfg(not(any(target_os = "macos", target_os = "linux", target_os = "windows")))]
const AZURE_CLI_FALLBACK_PATHS: &[&str] = &[];

fn azure_cli_program() -> Result<std::path::PathBuf, AppError> {
    if let Some(found) = find_on_path(AZURE_CLI_BINARY) {
        return Ok(found);
    }

    for candidate in AZURE_CLI_FALLBACK_PATHS {
        let path = std::path::Path::new(candidate);
        if path.is_file() {
            return Ok(path.to_path_buf());
        }
    }

    Err(AppError::Config(
        "Could not find the Azure CLI. Install it and run `az login`, or enter an \
         Azure OpenAI API key instead."
            .to_string(),
    ))
}

fn find_on_path(binary: &str) -> Option<std::path::PathBuf> {
    let path_var = std::env::var_os("PATH")?;
    std::env::split_paths(&path_var)
        .map(|dir| dir.join(binary))
        .find(|candidate| candidate.is_file())
}

fn parse_token_response(stdout: &str) -> Result<CachedToken, AppError> {
    let parsed: serde_json::Value = serde_json::from_str(stdout)
        .map_err(|e| AppError::Config(format!("Unexpected Azure CLI token response: {e}")))?;

    let value = parsed["accessToken"]
        .as_str()
        .filter(|token| !token.trim().is_empty())
        .ok_or_else(|| AppError::Config("Azure CLI returned no access token".to_string()))?
        .to_string();

    Ok(CachedToken {
        value,
        expires_at: parse_expiry(&parsed),
    })
}

/// The Azure CLI reports expiry as `expires_on` (unix seconds) on recent versions and
/// `expiresOn` (local naive time) on older ones. Prefer the unambiguous field; if neither
/// parses, fall back to a conservative window so the token is simply refreshed sooner.
fn parse_expiry(parsed: &serde_json::Value) -> SystemTime {
    if let Some(epoch) = parsed["expires_on"]
        .as_i64()
        .or_else(|| parsed["expires_on"].as_str().and_then(|s| s.parse().ok()))
    {
        if epoch > 0 {
            return SystemTime::UNIX_EPOCH + Duration::from_secs(epoch as u64);
        }
    }

    if let Some(raw) = parsed["expiresOn"].as_str() {
        if let Ok(naive) = chrono::NaiveDateTime::parse_from_str(raw.trim(), "%Y-%m-%d %H:%M:%S%.f")
        {
            use chrono::TimeZone;
            if let chrono::LocalResult::Single(local) = chrono::Local.from_local_datetime(&naive) {
                if let Ok(epoch) = u64::try_from(local.timestamp()) {
                    return SystemTime::UNIX_EPOCH + Duration::from_secs(epoch);
                }
            }
        }
    }

    SystemTime::now() + RENEW_MARGIN + Duration::from_secs(60)
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    #[test]
    fn parses_access_token_and_epoch_expiry() {
        let in_an_hour = chrono::Utc::now().timestamp() + 3600;
        let token = parse_token_response(&format!(
            r#"{{"accessToken":"abc123","expires_on":{in_an_hour},"tokenType":"Bearer"}}"#
        ))
        .unwrap();

        assert_eq!(token.value, "abc123");
        assert!(token.is_usable());
    }

    #[test]
    fn treats_soon_to_expire_token_as_unusable() {
        let in_a_minute = chrono::Utc::now().timestamp() + 60;
        let token = parse_token_response(&format!(
            r#"{{"accessToken":"abc123","expires_on":{in_a_minute}}}"#
        ))
        .unwrap();

        assert!(
            !token.is_usable(),
            "token inside the renew margin must refresh"
        );
    }

    #[test]
    fn falls_back_to_legacy_local_expires_on() {
        let local = chrono::Local::now() + chrono::Duration::hours(1);
        let token = parse_token_response(&format!(
            r#"{{"accessToken":"abc123","expiresOn":"{}"}}"#,
            local.format("%Y-%m-%d %H:%M:%S.%6f")
        ))
        .unwrap();

        assert!(token.is_usable());
    }

    #[test]
    fn unparseable_expiry_still_yields_a_usable_short_lived_token() {
        let token =
            parse_token_response(r#"{"accessToken":"abc123","expiresOn":"not-a-date"}"#).unwrap();

        assert_eq!(token.value, "abc123");
        assert!(token.expires_at > SystemTime::now());
    }

    #[test]
    fn rejects_response_without_a_token() {
        let err = parse_token_response(r#"{"expires_on":123}"#).unwrap_err();
        assert!(err.to_string().contains("no access token"));
    }

    #[test]
    fn rejects_non_json_output() {
        let err = parse_token_response("ERROR: please run az login").unwrap_err();
        assert!(err
            .to_string()
            .contains("Unexpected Azure CLI token response"));
    }

    #[test]
    fn recognises_azure_ai_data_plane_hosts() {
        for url in [
            "https://res.openai.azure.com/openai/v1",
            "https://RES.OpenAI.Azure.com/openai/v1",
            "https://res.cognitiveservices.azure.com/openai/deployments/x/audio/transcriptions?api-version=2025-03-01-preview",
            "https://res.services.ai.azure.com/openai/v1",
            "https://eastus.api.cognitive.microsoft.com/openai/v1",
        ] {
            assert!(is_azure_ai_endpoint(url), "{url} should be Azure");
        }
    }

    #[test]
    fn does_not_mistake_other_hosts_for_azure() {
        for url in [
            "http://localhost:8000/v1",
            "https://api.openai.com/v1",
            "https://api.groq.com/openai/v1",
            // Look-alike host that merely contains the suffix as a substring.
            "https://evil.com/?x=.openai.azure.com",
            "not a url",
            "",
        ] {
            assert!(!is_azure_ai_endpoint(url), "{url} should not be Azure");
        }
    }

    #[tokio::test]
    async fn audio_request_prefers_a_pasted_key_over_entra() {
        let request = authorize_audio_request(
            reqwest::Client::new()
                .post("https://res.openai.azure.com/openai/v1/audio/transcriptions"),
            "azure-secret",
            "https://res.openai.azure.com/openai/v1/audio/transcriptions",
        )
        .await
        .unwrap()
        .build()
        .unwrap();

        assert_eq!(
            request.headers().get("Authorization").unwrap(),
            "Bearer azure-secret"
        );
    }

    #[tokio::test]
    async fn audio_request_leaves_local_servers_unauthenticated() {
        // A local Speaches / faster-whisper server takes no credentials, and must not
        // trigger an Azure sign-in just because the key is blank.
        let request = authorize_audio_request(
            reqwest::Client::new().post("http://localhost:8000/v1/audio/transcriptions"),
            "",
            "http://localhost:8000/v1/audio/transcriptions",
        )
        .await
        .unwrap()
        .build()
        .unwrap();

        assert!(request.headers().get("Authorization").is_none());
    }

    #[test]
    fn resolves_azure_cli_outside_path_like_a_gui_launched_app() {
        // Simulates the minimal PATH a Finder-launched app inherits: the binary must
        // still be found via the well-known install locations.
        let found_on_path = find_on_path(AZURE_CLI_BINARY).is_some();
        let found_in_fallbacks = AZURE_CLI_FALLBACK_PATHS
            .iter()
            .any(|p| std::path::Path::new(p).is_file());

        if !found_on_path && !found_in_fallbacks {
            // Azure CLI genuinely absent (e.g. CI): resolution must fail with guidance.
            let err = azure_cli_program().unwrap_err().to_string();
            assert!(err.contains("Azure CLI"), "unhelpful error: {err}");
            return;
        }

        let resolved = azure_cli_program().expect("azure cli should resolve");
        assert!(resolved.is_file(), "resolved to a non-file: {resolved:?}");
    }

    #[test]
    fn find_on_path_returns_none_for_unknown_binaries() {
        assert!(find_on_path("definitely-not-a-real-binary-xyz").is_none());
    }

    #[test]
    fn epoch_expiry_is_interpreted_as_utc() {
        let fixed = chrono::Utc.timestamp_opt(1_800_000_000, 0).unwrap();
        let token =
            parse_token_response(r#"{"accessToken":"abc","expires_on":1800000000}"#).unwrap();

        assert_eq!(
            token.expires_at,
            SystemTime::UNIX_EPOCH + Duration::from_secs(fixed.timestamp() as u64)
        );
    }
}
