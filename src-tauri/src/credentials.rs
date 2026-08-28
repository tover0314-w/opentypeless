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
    if load_cloud_session_token(vault)?.as_deref() != Some(token) {
        return Err(anyhow!("cloud session vault verification failed"));
    }
    Ok(())
}

pub fn remove_cloud_session_token<V: CredentialSecretRemover>(vault: &V) -> Result<()> {
    vault.remove_secret(CLOUD_SESSION_NAMESPACE, CLOUD_SESSION_PROVIDER)
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
        Ok(payload) if payload.version == STORED_CREDENTIAL_VERSION => Ok(StoredCredential {
            value: payload.value,
            updated_at: Some(payload.updated_at),
        }),
        _ => Ok(StoredCredential {
            value: stored.to_string(),
            updated_at: None,
        }),
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

    for (namespace, provider, secret) in &pending {
        vault.set_secret(namespace, provider, secret)?;
        let verified = vault.get_secret(namespace, provider)?;
        if verified.as_deref() != Some(secret.as_str()) {
            return Err(anyhow!(
                "credential vault verification failed for {namespace}.{provider}"
            ));
        }
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
        migrated: pending
            .into_iter()
            .map(|(namespace, provider, _)| CredentialRef {
                namespace,
                provider,
            })
            .collect(),
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
    Ok(vault.get_secret(namespace, provider)?.unwrap_or_default())
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
        struct MismatchedReadVault;

        impl CredentialVault for MismatchedReadVault {
            fn set_secret(&self, _namespace: &str, _provider: &str, _secret: &str) -> Result<()> {
                Ok(())
            }
        }

        impl CredentialSecretReader for MismatchedReadVault {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Ok(Some("wrong-secret".to_string()))
            }
        }

        let mut config = AppConfig {
            stt_provider: "deepgram".to_string(),
            stt_api_key: "stt-secret".to_string(),
            ..AppConfig::default()
        };

        let result = migrate_legacy_config_secrets(&mut config, &MismatchedReadVault);

        assert!(result.is_err());
        assert_eq!(config.stt_api_key, "stt-secret");
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
}
