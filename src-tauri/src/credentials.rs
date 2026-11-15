use anyhow::{anyhow, Context, Result};
use chrono::SecondsFormat;
use serde::{Deserialize, Serialize};

use crate::storage::AppConfig;

const SERVICE_NAME: &str = "OpenTypeless";
const API_KEY_ACCOUNT_SUFFIX: &str = "api_key";
const STORED_CREDENTIAL_VERSION: u8 = 1;
const CLOUD_SESSION_NAMESPACE: &str = "session";
const CLOUD_SESSION_PROVIDER: &str = "cloud";

pub trait CredentialVault {
    fn set_secret(&self, namespace: &str, provider: &str, secret: &str) -> Result<()>;
}

pub trait CredentialSecretReader {
    fn get_secret(&self, namespace: &str, provider: &str) -> Result<Option<String>>;

    fn get_secret_updated_at(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
        Ok(None)
    }
}

pub trait CredentialSecretRemover {
    fn remove_secret(&self, namespace: &str, provider: &str) -> Result<()>;
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct CredentialRef {
    pub namespace: String,
    pub provider: String,
}

#[derive(Debug, Clone, Default, PartialEq, Eq)]
pub struct CredentialMigrationReport {
    pub migrated: Vec<CredentialRef>,
    pub legacy_fields_cleared: bool,
}

#[derive(Debug, Clone, PartialEq, Eq)]
pub struct StoredCredential {
    pub value: String,
    pub updated_at: Option<String>,
}

#[derive(Debug, Clone, Serialize, Deserialize)]
#[serde(rename_all = "camelCase")]
struct StoredCredentialPayload {
    version: u8,
    secret_kind: String,
    value: String,
    updated_at: String,
}

#[derive(Debug, Clone, Copy, Default)]
pub struct SystemCredentialVault;

impl SystemCredentialVault {
    fn account(namespace: &str, provider: &str) -> String {
        format!(
            "{}.{}.{}",
            namespace.trim(),
            provider.trim(),
            API_KEY_ACCOUNT_SUFFIX
        )
    }

    pub fn has_secret(&self, namespace: &str, provider: &str) -> Result<bool> {
        Ok(self.get_secret(namespace, provider)?.is_some())
    }

    pub fn remove_secret(&self, namespace: &str, provider: &str) -> Result<()> {
        delete_system_secret(&Self::account(namespace, provider))
    }
}

impl CredentialVault for SystemCredentialVault {
    fn set_secret(&self, namespace: &str, provider: &str, secret: &str) -> Result<()> {
        let stored = encode_stored_credential(secret, &current_credential_timestamp())
            .map_err(|e| anyhow!("encode credential payload for {namespace}.{provider}: {e}"))?;
        write_system_secret(&Self::account(namespace, provider), &stored)
    }
}

impl CredentialSecretReader for SystemCredentialVault {
    fn get_secret(&self, namespace: &str, provider: &str) -> Result<Option<String>> {
        read_system_secret(&Self::account(namespace, provider))?
            .map(|stored| decode_stored_credential(&stored).map(|credential| credential.value))
            .transpose()
    }

    fn get_secret_updated_at(&self, namespace: &str, provider: &str) -> Result<Option<String>> {
        read_system_secret(&Self::account(namespace, provider))?
            .map(|stored| decode_stored_credential(&stored).map(|credential| credential.updated_at))
            .transpose()
            .map(Option::flatten)
    }
}

impl CredentialSecretRemover for SystemCredentialVault {
    fn remove_secret(&self, namespace: &str, provider: &str) -> Result<()> {
        Self::remove_secret(self, namespace, provider)
    }
}

pub fn load_cloud_session_token<V: CredentialSecretReader>(vault: &V) -> Result<Option<String>> {
    Ok(vault
        .get_secret(CLOUD_SESSION_NAMESPACE, CLOUD_SESSION_PROVIDER)?
        .filter(|token| !token.trim().is_empty()))
}

pub fn store_cloud_session_token<V: CredentialVault + CredentialSecretReader>(
    vault: &V,
    token: &str,
) -> Result<()> {
    if token.trim().is_empty() {
        return Err(anyhow!("cloud session token is empty"));
    }
    vault.set_secret(CLOUD_SESSION_NAMESPACE, CLOUD_SESSION_PROVIDER, token)?;
    if load_cloud_session_token(vault)
        .map_err(|_| anyhow!(UnverifiedCloudSessionWrite))?
        .as_deref()
        != Some(token)
    {
        return Err(anyhow!(UnverifiedCloudSessionWrite));
    }
    Ok(())
}

#[derive(Debug)]
pub struct UnverifiedCloudSessionWrite;

impl std::fmt::Display for UnverifiedCloudSessionWrite {
    fn fmt(&self, formatter: &mut std::fmt::Formatter<'_>) -> std::fmt::Result {
        formatter.write_str("cloud session vault verification failed")
    }
}

impl std::error::Error for UnverifiedCloudSessionWrite {}

pub fn remove_cloud_session_token<
    V: CredentialVault + CredentialSecretReader + CredentialSecretRemover,
>(
    vault: &V,
) -> Result<()> {
    if vault
        .remove_secret(CLOUD_SESSION_NAMESPACE, CLOUD_SESSION_PROVIDER)
        .is_ok()
    {
        match load_cloud_session_token(vault) {
            Ok(None) => return Ok(()),
            Ok(Some(_)) => {}
            Err(_) => return Err(anyhow!("cloud session vault clearing verification failed")),
        }
    }
    vault
        .set_secret(CLOUD_SESSION_NAMESPACE, CLOUD_SESSION_PROVIDER, "")
        .map_err(|_| anyhow!("cloud session could not be cleared from the system vault"))?;
    if load_cloud_session_token(vault)
        .map_err(|_| anyhow!("cloud session vault clearing verification failed"))?
        .is_some()
    {
        return Err(anyhow!("cloud session vault clearing verification failed"));
    }
    Ok(())
}

#[cfg(any(target_os = "macos", target_os = "windows", target_os = "linux"))]
fn system_entry(account: &str) -> Result<keyring::Entry> {
    keyring::Entry::new(SERVICE_NAME, account).context("open system credential vault")
}

#[cfg(any(target_os = "macos", target_os = "windows", target_os = "linux"))]
fn write_system_secret(account: &str, secret: &str) -> Result<()> {
    system_entry(account)?
        .set_password(secret)
        .with_context(|| format!("write system credential vault {account}"))
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
fn write_system_secret(_account: &str, _secret: &str) -> Result<()> {
    Err(anyhow!(
        "system credential vault is not supported on this platform"
    ))
}

#[cfg(any(target_os = "macos", target_os = "windows", target_os = "linux"))]
fn read_system_secret(account: &str) -> Result<Option<String>> {
    match system_entry(account)?.get_password() {
        Ok(secret) => Ok(Some(secret)),
        Err(keyring::Error::NoEntry) => Ok(None),
        Err(error) => Err(anyhow!("read system credential vault {account}: {error}")),
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
fn read_system_secret(_account: &str) -> Result<Option<String>> {
    Err(anyhow!(
        "system credential vault is not supported on this platform"
    ))
}

#[cfg(any(target_os = "macos", target_os = "windows", target_os = "linux"))]
fn delete_system_secret(account: &str) -> Result<()> {
    match system_entry(account)?.delete_credential() {
        Ok(()) | Err(keyring::Error::NoEntry) => Ok(()),
        Err(error) => Err(anyhow!("delete system credential vault {account}: {error}")),
    }
}

#[cfg(not(any(target_os = "macos", target_os = "windows", target_os = "linux")))]
fn delete_system_secret(_account: &str) -> Result<()> {
    Err(anyhow!(
        "system credential vault is not supported on this platform"
    ))
}

fn current_credential_timestamp() -> String {
    chrono::Utc::now().to_rfc3339_opts(SecondsFormat::Secs, true)
}

fn encode_stored_credential(secret: &str, updated_at: &str) -> Result<String> {
    serde_json::to_string(&StoredCredentialPayload {
        version: STORED_CREDENTIAL_VERSION,
        secret_kind: "apiKey".to_string(),
        value: secret.to_string(),
        updated_at: updated_at.to_string(),
    })
    .context("serialize credential payload")
}

fn decode_stored_credential(stored: &str) -> Result<StoredCredential> {
    match serde_json::from_str::<StoredCredentialPayload>(stored) {
        Ok(payload)
            if payload.version == STORED_CREDENTIAL_VERSION && payload.secret_kind == "apiKey" =>
        {
            Ok(StoredCredential {
                value: payload.value,
                updated_at: Some(payload.updated_at),
            })
        }
        Ok(_) => Err(anyhow!("Unsupported stored credential format")),
        Err(_) => {
            let identified = serde_json::from_str::<serde_json::Value>(stored)
                .ok()
                .and_then(|value| {
                    value.as_object().map(|object| {
                        object.contains_key("version") && object.contains_key("secretKind")
                    })
                })
                .unwrap_or(false);
            if identified {
                return Err(anyhow!("Invalid stored credential format"));
            }
            Ok(StoredCredential {
                value: stored.to_string(),
                updated_at: None,
            })
        }
    }
}

pub fn migrate_legacy_config_secrets<V: CredentialVault + CredentialSecretReader>(
    config: &mut AppConfig,
    vault: &V,
) -> Result<CredentialMigrationReport> {
    let mut pending = Vec::new();

    if !config.stt_api_key.trim().is_empty() {
        pending.push((
            "stt".to_string(),
            config.stt_provider.clone(),
            config.stt_api_key.clone(),
        ));
    }
    if !config.stt_custom_api_key.trim().is_empty() {
        pending.push((
            "stt".to_string(),
            "custom-whisper".to_string(),
            config.stt_custom_api_key.clone(),
        ));
    }
    if !config.llm_api_key.trim().is_empty() {
        pending.push((
            "llm".to_string(),
            config.llm_provider.clone(),
            config.llm_api_key.clone(),
        ));
    }

    let mut migrated = Vec::new();
    for (namespace, provider, secret) in &pending {
        // Migration imports missing entries only. Explicit key changes use
        // set_credential, so an old settings snapshot cannot replace a newer key.
        if vault
            .get_secret(namespace, provider)
            .map_err(|_| anyhow!("Could not read provider credential during migration"))?
            .is_some_and(|stored| !stored.trim().is_empty())
        {
            continue;
        }
        vault
            .set_secret(namespace, provider, secret)
            .map_err(|_| anyhow!("Could not store provider credential during migration"))?;
        let verified = vault
            .get_secret(namespace, provider)
            .map_err(|_| anyhow!("Could not verify provider credential during migration"))?;
        if verified.as_deref() != Some(secret.as_str()) {
            return Err(anyhow!(
                "Provider credential verification failed during migration"
            ));
        }
        migrated.push(CredentialRef {
            namespace: namespace.clone(),
            provider: provider.clone(),
        });
    }

    if pending
        .iter()
        .any(|(namespace, provider, _)| namespace == "stt" && provider != "custom-whisper")
    {
        config.stt_api_key.clear();
    }
    if pending
        .iter()
        .any(|(namespace, provider, _)| namespace == "stt" && provider == "custom-whisper")
    {
        config.stt_custom_api_key.clear();
    }
    if pending.iter().any(|(namespace, _, _)| namespace == "llm") {
        config.llm_api_key.clear();
    }

    Ok(CredentialMigrationReport {
        migrated,
        legacy_fields_cleared: !pending.is_empty(),
    })
}

pub fn resolve_config_secret<V: CredentialSecretReader>(
    legacy_secret: &str,
    namespace: &str,
    provider: &str,
    vault: &V,
) -> Result<String> {
    if !legacy_secret.trim().is_empty() {
        return Ok(legacy_secret.to_string());
    }
    Ok(vault
        .get_secret(namespace, provider)
        .map_err(|_| anyhow!("Could not read provider credential from the system vault"))?
        .filter(|secret| !secret.trim().is_empty())
        .unwrap_or_default())
}

pub fn stt_credential_provider(config: &AppConfig) -> &str {
    if config.stt_provider == crate::stt::config::CUSTOM_WHISPER_PROVIDER {
        crate::stt::config::CUSTOM_WHISPER_PROVIDER
    } else {
        &config.stt_provider
    }
}

pub fn resolve_stt_config_secret<V: CredentialSecretReader>(
    config: &AppConfig,
    vault: &V,
) -> Result<String> {
    if config.stt_provider == crate::stt::config::APPLE_SPEECH_PROVIDER {
        return Ok(String::new());
    }
    let provider = stt_credential_provider(config);
    let legacy_secret = if provider == crate::stt::config::CUSTOM_WHISPER_PROVIDER {
        &config.stt_custom_api_key
    } else {
        &config.stt_api_key
    };

    resolve_config_secret(legacy_secret, "stt", provider, vault)
}

pub fn resolve_llm_config_secret<V: CredentialSecretReader>(
    config: &AppConfig,
    vault: &V,
) -> Result<String> {
    resolve_config_secret(&config.llm_api_key, "llm", &config.llm_provider, vault)
}

#[cfg(test)]
mod tests {
    use super::*;
    use anyhow::anyhow;
    use std::sync::Mutex;

    #[derive(Default)]
    struct MemoryVault {
        records: Mutex<Vec<(String, String, String)>>,
        fail_provider: Option<String>,
    }

    impl MemoryVault {
        fn with_failure(provider: &str) -> Self {
            Self {
                records: Mutex::new(Vec::new()),
                fail_provider: Some(provider.to_string()),
            }
        }

        fn records(&self) -> Vec<(String, String, String)> {
            self.records.lock().unwrap().clone()
        }
    }

    impl CredentialVault for MemoryVault {
        fn set_secret(&self, namespace: &str, provider: &str, secret: &str) -> Result<()> {
            if self.fail_provider.as_deref() == Some(provider) {
                return Err(anyhow!("vault write failed"));
            }
            self.records.lock().unwrap().push((
                namespace.to_string(),
                provider.to_string(),
                secret.to_string(),
            ));
            Ok(())
        }
    }

    impl CredentialSecretReader for MemoryVault {
        fn get_secret(&self, namespace: &str, provider: &str) -> Result<Option<String>> {
            Ok(self
                .records
                .lock()
                .unwrap()
                .iter()
                .rev()
                .find(|(record_namespace, record_provider, _)| {
                    record_namespace == namespace && record_provider == provider
                })
                .map(|(_, _, secret)| secret.clone()))
        }
    }

    #[test]
    fn migrates_plaintext_api_keys_and_clears_config_after_success() {
        let mut config = AppConfig {
            stt_provider: "deepgram".to_string(),
            stt_api_key: "stt-secret".to_string(),
            stt_custom_api_key: "custom-stt-secret".to_string(),
            llm_provider: "openai".to_string(),
            llm_api_key: "llm-secret".to_string(),
            ..AppConfig::default()
        };
        let vault = MemoryVault::default();

        let report = migrate_legacy_config_secrets(&mut config, &vault).unwrap();

        assert_eq!(
            vault.records(),
            vec![
                (
                    "stt".to_string(),
                    "deepgram".to_string(),
                    "stt-secret".to_string()
                ),
                (
                    "stt".to_string(),
                    "custom-whisper".to_string(),
                    "custom-stt-secret".to_string()
                ),
                (
                    "llm".to_string(),
                    "openai".to_string(),
                    "llm-secret".to_string()
                ),
            ]
        );
        assert_eq!(
            report.migrated,
            vec![
                CredentialRef {
                    namespace: "stt".to_string(),
                    provider: "deepgram".to_string(),
                },
                CredentialRef {
                    namespace: "stt".to_string(),
                    provider: "custom-whisper".to_string(),
                },
                CredentialRef {
                    namespace: "llm".to_string(),
                    provider: "openai".to_string(),
                },
            ]
        );
        assert!(config.stt_api_key.is_empty());
        assert!(config.stt_custom_api_key.is_empty());
        assert!(config.llm_api_key.is_empty());
    }

    #[test]
    fn stale_onboarding_keys_cannot_overwrite_rotated_vault_keys() {
        let stale = AppConfig {
            stt_provider: "groq-whisper".to_string(),
            stt_api_key: "old-stt".to_string(),
            stt_custom_api_key: "old-custom".to_string(),
            llm_provider: "gemini".to_string(),
            llm_api_key: "old-llm".to_string(),
            ..AppConfig::default()
        };
        let vault = MemoryVault::default();
        migrate_legacy_config_secrets(&mut stale.clone(), &vault).unwrap();
        vault.set_secret("stt", "groq-whisper", "new-stt").unwrap();
        vault
            .set_secret("stt", "custom-whisper", "new-custom")
            .unwrap();
        vault.set_secret("llm", "gemini", "new-llm").unwrap();
        let writes_before_save = vault.records().len();
        for _ in 0..2 {
            let mut settings = stale.clone();
            let report = migrate_legacy_config_secrets(&mut settings, &vault).unwrap();
            assert!(report.migrated.is_empty());
            assert!(report.legacy_fields_cleared);
            assert!(settings.stt_api_key.is_empty());
            assert!(settings.stt_custom_api_key.is_empty());
            assert!(settings.llm_api_key.is_empty());
            assert_eq!(
                resolve_stt_config_secret(&settings, &vault).unwrap(),
                "new-stt"
            );
            assert_eq!(
                resolve_llm_config_secret(&settings, &vault).unwrap(),
                "new-llm"
            );
            assert_eq!(
                vault
                    .get_secret("stt", "custom-whisper")
                    .unwrap()
                    .as_deref(),
                Some("new-custom")
            );
        }
        assert_eq!(vault.records().len(), writes_before_save);
    }

    #[test]
    fn migration_errors_do_not_expose_vault_details_or_discard_legacy_keys() {
        struct PrivateFailureVault {
            stage: &'static str,
            reads: std::sync::atomic::AtomicUsize,
        }

        impl CredentialVault for PrivateFailureVault {
            fn set_secret(&self, _namespace: &str, _provider: &str, _secret: &str) -> Result<()> {
                if self.stage == "write" {
                    Err(anyhow!("synthetic-private-account-detail"))
                } else {
                    Ok(())
                }
            }
        }

        impl CredentialSecretReader for PrivateFailureVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                let read = self.reads.fetch_add(1, std::sync::atomic::Ordering::SeqCst);
                if self.stage == "read" || (self.stage == "verify" && read > 0) {
                    Err(anyhow!("synthetic-private-account-detail"))
                } else {
                    Ok(None)
                }
            }
        }

        for (stage, expected) in [
            (
                "read",
                "Could not read provider credential during migration",
            ),
            (
                "write",
                "Could not store provider credential during migration",
            ),
            (
                "verify",
                "Could not verify provider credential during migration",
            ),
        ] {
            let mut config = AppConfig {
                stt_api_key: "synthetic-legacy-secret".into(),
                ..AppConfig::default()
            };
            let vault = PrivateFailureVault {
                stage,
                reads: std::sync::atomic::AtomicUsize::new(0),
            };
            let error = migrate_legacy_config_secrets(&mut config, &vault).unwrap_err();
            assert_eq!(error.to_string(), expected);
            assert_eq!(config.stt_api_key, "synthetic-legacy-secret");
        }
    }

    #[test]
    fn keeps_plaintext_api_keys_when_any_vault_write_fails() {
        let mut config = AppConfig {
            stt_provider: "deepgram".to_string(),
            stt_api_key: "stt-secret".to_string(),
            stt_custom_api_key: "custom-stt-secret".to_string(),
            llm_provider: "openai".to_string(),
            llm_api_key: "llm-secret".to_string(),
            ..AppConfig::default()
        };
        let vault = MemoryVault::with_failure("openai");

        let result = migrate_legacy_config_secrets(&mut config, &vault);

        assert!(result.is_err());
        assert_eq!(config.stt_api_key, "stt-secret");
        assert_eq!(config.stt_custom_api_key, "custom-stt-secret");
        assert_eq!(config.llm_api_key, "llm-secret");
    }

    #[test]
    fn keeps_plaintext_api_keys_when_vault_verification_fails() {
        #[derive(Default)]
        struct MismatchedReadVault(std::sync::atomic::AtomicBool);

        impl CredentialVault for MismatchedReadVault {
            fn set_secret(&self, _namespace: &str, _provider: &str, _secret: &str) -> Result<()> {
                Ok(())
            }
        }

        impl CredentialSecretReader for MismatchedReadVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                if self.0.swap(true, std::sync::atomic::Ordering::Relaxed) {
                    Ok(Some("wrong-secret".to_string()))
                } else {
                    Ok(None)
                }
            }
        }

        let mut config = AppConfig {
            stt_provider: "synthetic-private-provider".to_string(),
            stt_api_key: "stt-secret".to_string(),
            ..AppConfig::default()
        };

        let result = migrate_legacy_config_secrets(&mut config, &MismatchedReadVault::default());

        assert_eq!(
            result.unwrap_err().to_string(),
            "Provider credential verification failed during migration"
        );
        assert_eq!(config.stt_api_key, "stt-secret");
    }

    #[test]
    fn whitespace_only_vault_credentials_are_treated_as_missing() {
        struct BlankVault;

        impl CredentialSecretReader for BlankVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Ok(Some(" \t\n ".into()))
            }
        }

        for (namespace, provider) in [("stt", "deepgram"), ("llm", "openai")] {
            assert_eq!(
                resolve_config_secret("", namespace, provider, &BlankVault).unwrap(),
                ""
            );
        }
    }

    #[test]
    fn resolves_missing_config_secret_from_vault() {
        struct ReadVault;

        impl CredentialSecretReader for ReadVault {
            fn get_secret(&self, namespace: &str, provider: &str) -> Result<Option<String>> {
                assert_eq!(namespace, "llm");
                assert_eq!(provider, "openai");
                Ok(Some("vault-secret".to_string()))
            }
        }

        let secret = resolve_config_secret("", "llm", "openai", &ReadVault).unwrap();

        assert_eq!(secret, "vault-secret");
    }

    #[test]
    fn config_secret_resolution_does_not_echo_vault_failure_details() {
        struct PrivateFailure;
        impl CredentialSecretReader for PrivateFailure {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Err(anyhow!("synthetic-private-key and account detail"))
            }
        }
        for namespace in ["stt", "llm"] {
            assert_eq!(
                resolve_config_secret("", namespace, "synthetic-provider", &PrivateFailure)
                    .unwrap_err()
                    .to_string(),
                "Could not read provider credential from the system vault"
            );
        }
    }

    #[test]
    fn resolves_in_memory_secret_before_vault() {
        struct PanicVault;

        impl CredentialSecretReader for PanicVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                panic!("vault should not be read when legacy secret is present");
            }
        }

        let secret = resolve_config_secret("typed-secret", "llm", "openai", &PanicVault).unwrap();

        assert_eq!(secret, "typed-secret");
    }

    #[test]
    fn stored_credential_payload_round_trips_secret_and_metadata() {
        let stored = encode_stored_credential("vault-secret", "2026-07-06T00:00:00Z").unwrap();

        assert_ne!(stored, "vault-secret");

        let decoded = decode_stored_credential(&stored).unwrap();

        assert_eq!(decoded.value, "vault-secret");
        assert_eq!(decoded.updated_at.as_deref(), Some("2026-07-06T00:00:00Z"));
    }

    #[test]
    fn stored_credential_decoder_preserves_legacy_plaintext_secret() {
        let decoded = decode_stored_credential("legacy-secret").unwrap();

        assert_eq!(decoded.value, "legacy-secret");
        assert_eq!(decoded.updated_at, None);
    }

    #[test]
    fn stored_credential_decoder_rejects_unsupported_envelope_identity() {
        for (version, kind) in [(2, "apiKey"), (1, "otherSecret")] {
            let stored = serde_json::json!({
                "version": version,
                "secretKind": kind,
                "value": "synthetic-private-secret",
                "updatedAt": "2026-07-06T00:00:00Z"
            })
            .to_string();
            let error = decode_stored_credential(&stored).unwrap_err();
            assert_eq!(error.to_string(), "Unsupported stored credential format");
        }
    }

    #[test]
    fn stored_credential_decoder_rejects_malformed_identified_envelopes() {
        for payload in [
            serde_json::json!({"version": 1, "secretKind": "apiKey"}),
            serde_json::json!({
                "version": 1,
                "secretKind": "apiKey",
                "value": 42,
                "updatedAt": "2026-07-06T00:00:00Z"
            }),
            serde_json::json!({
                "version": "1",
                "secretKind": "apiKey",
                "value": "synthetic-private-secret",
                "updatedAt": null
            }),
        ] {
            assert_eq!(
                decode_stored_credential(&payload.to_string())
                    .unwrap_err()
                    .to_string(),
                "Invalid stored credential format"
            );
        }
    }

    #[test]
    fn stored_credential_decoder_preserves_unrelated_legacy_json() {
        let stored = r#"{"value":"legacy-json-secret","custom":"provider-specific"}"#;
        assert_eq!(decode_stored_credential(stored).unwrap().value, stored);
    }

    #[test]
    fn apple_speech_does_not_require_access_to_the_credential_vault() {
        struct UnavailableVault;

        impl CredentialSecretReader for UnavailableVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Err(anyhow!("synthetic unavailable vault"))
            }
        }

        let config = AppConfig {
            stt_provider: crate::stt::config::APPLE_SPEECH_PROVIDER.into(),
            stt_api_key: String::new(),
            ..AppConfig::default()
        };
        assert_eq!(
            resolve_stt_config_secret(&config, &UnavailableVault).unwrap(),
            ""
        );
    }

    #[test]
    fn apple_speech_does_not_forward_an_unrelated_legacy_provider_key() {
        struct UnexpectedVault;

        impl CredentialSecretReader for UnexpectedVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                panic!("Apple Speech must not read provider credentials");
            }
        }

        let config = AppConfig {
            stt_provider: crate::stt::config::APPLE_SPEECH_PROVIDER.into(),
            stt_api_key: "synthetic-previous-provider-key".into(),
            ..AppConfig::default()
        };
        assert_eq!(
            resolve_stt_config_secret(&config, &UnexpectedVault).unwrap(),
            ""
        );
        assert_eq!(config.stt_api_key, "synthetic-previous-provider-key");
    }

    #[test]
    fn custom_whisper_still_reports_vault_failures_without_private_details() {
        struct UnavailableVault;

        impl CredentialSecretReader for UnavailableVault {
            fn get_secret(&self, namespace: &str, provider: &str) -> Result<Option<String>> {
                assert_eq!(namespace, "stt");
                assert_eq!(provider, crate::stt::config::CUSTOM_WHISPER_PROVIDER);
                Err(anyhow!("synthetic-private-vault-detail"))
            }
        }

        let config = AppConfig {
            stt_provider: crate::stt::config::CUSTOM_WHISPER_PROVIDER.into(),
            stt_custom_api_key: String::new(),
            ..AppConfig::default()
        };
        assert_eq!(
            resolve_stt_config_secret(&config, &UnavailableVault)
                .unwrap_err()
                .to_string(),
            "Could not read provider credential from the system vault"
        );
    }

    #[test]
    fn resolves_regular_stt_secret_from_active_provider() {
        let config = AppConfig {
            stt_provider: "deepgram".to_string(),
            ..AppConfig::default()
        };
        let vault = MemoryVault::default();
        vault
            .set_secret("stt", "deepgram", "deepgram-secret")
            .unwrap();

        let secret = resolve_stt_config_secret(&config, &vault).unwrap();

        assert_eq!(secret, "deepgram-secret");
    }

    #[test]
    fn resolves_custom_stt_secret_from_custom_whisper_provider() {
        let config = AppConfig {
            stt_provider: crate::stt::config::CUSTOM_WHISPER_PROVIDER.to_string(),
            ..AppConfig::default()
        };
        let vault = MemoryVault::default();
        vault
            .set_secret(
                "stt",
                crate::stt::config::CUSTOM_WHISPER_PROVIDER,
                "custom-secret",
            )
            .unwrap();

        let secret = resolve_stt_config_secret(&config, &vault).unwrap();

        assert_eq!(secret, "custom-secret");
    }

    #[test]
    fn resolves_llm_secret_from_active_provider() {
        let config = AppConfig {
            llm_provider: "openai".to_string(),
            ..AppConfig::default()
        };
        let vault = MemoryVault::default();
        vault.set_secret("llm", "openai", "llm-secret").unwrap();

        let secret = resolve_llm_config_secret(&config, &vault).unwrap();

        assert_eq!(secret, "llm-secret");
    }

    #[test]
    fn recording_snapshots_preserve_legacy_byok_credentials_when_vault_is_unavailable() {
        use crate::input_configuration::InputConfiguration;
        use crate::input_session::{InputAction, InputBinding, InputMode, VoiceRole};

        struct UnavailableVault;

        impl CredentialSecretReader for UnavailableVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Err(anyhow!("synthetic unavailable vault"))
            }
        }

        for provider in ["deepgram", crate::stt::config::CUSTOM_WHISPER_PROVIDER] {
            let mut runtime = InputConfiguration::<String, AppConfig>::default();
            let original = AppConfig {
                stt_provider: provider.into(),
                stt_api_key: "synthetic-original-stt".into(),
                stt_custom_api_key: "synthetic-original-custom".into(),
                llm_api_key: "synthetic-original-llm".into(),
                ..Default::default()
            };
            runtime.stage_installation(
                1,
                "toggle".into(),
                vec![InputBinding {
                    role: VoiceRole::Dictation,
                    binding_id: "synthetic-binding".into(),
                    mode: InputMode::Toggle,
                    revision: 0,
                }],
                vec!["synthetic-binding".into()],
            );
            assert!(runtime.publish_saved_revision(1, 1, &"toggle".into(), original.clone()));
            let actions = runtime.press_committed("synthetic-binding", VoiceRole::Dictation, 10);
            let [InputAction::Start {
                session_id,
                binding,
            }] = actions.as_slice()
            else {
                panic!("expected synthetic recording start");
            };
            let mut next = original.clone();
            next.stt_api_key = "synthetic-next-stt".into();
            next.stt_custom_api_key = "synthetic-next-custom".into();
            next.llm_api_key = "synthetic-next-llm".into();
            assert!(runtime.refresh_saved_snapshot(2, &"toggle".into(), next));
            let owned = runtime
                .owned_snapshot(*session_id, binding.revision)
                .unwrap();
            let expected_stt = if provider == crate::stt::config::CUSTOM_WHISPER_PROVIDER {
                "synthetic-original-custom"
            } else {
                "synthetic-original-stt"
            };
            assert_eq!(
                resolve_stt_config_secret(owned, &UnavailableVault).unwrap(),
                expected_stt
            );
            assert_eq!(
                resolve_llm_config_secret(owned, &UnavailableVault).unwrap(),
                "synthetic-original-llm"
            );
            runtime.release("synthetic-binding", 20);
            runtime.cancel(*session_id);
            let actions = runtime.press_committed("synthetic-binding", VoiceRole::Dictation, 30);
            let [InputAction::Start {
                session_id,
                binding,
            }] = actions.as_slice()
            else {
                panic!("expected next synthetic recording start");
            };
            let owned = runtime
                .owned_snapshot(*session_id, binding.revision)
                .unwrap();
            let expected_stt = if provider == crate::stt::config::CUSTOM_WHISPER_PROVIDER {
                "synthetic-next-custom"
            } else {
                "synthetic-next-stt"
            };
            assert_eq!(
                resolve_stt_config_secret(owned, &UnavailableVault).unwrap(),
                expected_stt
            );
            assert_eq!(
                resolve_llm_config_secret(owned, &UnavailableVault).unwrap(),
                "synthetic-next-llm"
            );
        }
    }
}
