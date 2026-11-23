use crate::credentials::{
    CredentialSecretReader, CredentialSecretRemover, CredentialVault, SystemCredentialVault,
};
use crate::storage;
use serde::Serialize;
use tauri::{Emitter, Window};

const CREDENTIALS_CHANGED_EVENT: &str = "credentials:changed";

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "kebab-case")]
pub enum CredentialStorage {
    Unavailable,
    OsVault,
    SessionOnly,
    LegacyWarning,
}

#[derive(Debug, Clone, Serialize, PartialEq, Eq)]
#[serde(rename_all = "camelCase")]
pub struct CredentialStatus {
    pub namespace: String,
    pub provider: String,
    pub has_secret: bool,
    pub updated_at: Option<String>,
    pub storage: CredentialStorage,
}

#[tauri::command]
pub fn get_credential_status<R: tauri::Runtime>(
    window: Window<R>,
    namespace: String,
    provider: String,
) -> Result<CredentialStatus, String> {
    ensure_main_window(&window)?;
    credential_status_from_vault(namespace, provider, &SystemCredentialVault)
}

#[tauri::command]
pub async fn migrate_legacy_credentials(
    window: Window,
    app: tauri::AppHandle,
    config_state: tauri::State<'_, storage::ConfigManager>,
) -> Result<(), String> {
    ensure_main_window(&window)?;
    config_state.load().await.map_err(|e| e.to_string())?;
    let (revision, config) = config_state
        .migrate_current_credentials_commit()
        .map_err(|error| error.to_string())?;
    crate::commands::config::emit_config_commit(
        &app,
        config_state.config_epoch(),
        revision,
        &config,
    );
    Ok(())
}

#[tauri::command]
pub fn read_credential<R: tauri::Runtime>(
    window: Window<R>,
    namespace: String,
    provider: String,
) -> Result<Option<String>, String> {
    ensure_main_window(&window)?;
    let (namespace, provider) = validate_credential_target(namespace, provider)?;
    read_credential_from_vault(&namespace, &provider, &SystemCredentialVault)
}

#[tauri::command]
pub fn set_credential<R: tauri::Runtime>(
    window: Window<R>,
    namespace: String,
    provider: String,
    value: String,
) -> Result<(), String> {
    ensure_main_window(&window)?;
    let (namespace, provider) = validate_credential_target(namespace, provider)?;
    let vault = SystemCredentialVault;
    if value.trim().is_empty() {
        clear_credential_from_vault(namespace, provider, &vault)?;
    } else {
        store_credential_in_vault(&namespace, &provider, &value, &vault)?;
    }
    let _ = window.emit(CREDENTIALS_CHANGED_EVENT, ());
    Ok(())
}

#[tauri::command]
pub fn clear_credential<R: tauri::Runtime>(
    window: Window<R>,
    namespace: String,
    provider: String,
) -> Result<(), String> {
    ensure_main_window(&window)?;
    clear_credential_from_vault(namespace, provider, &SystemCredentialVault)?;
    let _ = window.emit(CREDENTIALS_CHANGED_EVENT, ());
    Ok(())
}

fn read_credential_from_vault<V: CredentialSecretReader>(
    namespace: &str,
    provider: &str,
    vault: &V,
) -> Result<Option<String>, String> {
    vault
        .get_secret(namespace, provider)
        .map_err(|_| "Could not read credential from the system vault".to_string())
}

fn store_credential_in_vault<V: CredentialVault + CredentialSecretReader>(
    namespace: &str,
    provider: &str,
    value: &str,
    vault: &V,
) -> Result<(), String> {
    vault
        .set_secret(namespace, provider, value)
        .map_err(|_| "Could not save credential in the system vault".to_string())?;
    match vault.get_secret(namespace, provider) {
        Ok(Some(stored)) if stored == value => Ok(()),
        _ => Err("Credential save could not be verified".to_string()),
    }
}

fn clear_credential_from_vault<V: CredentialSecretRemover + CredentialSecretReader>(
    namespace: String,
    provider: String,
    vault: &V,
) -> Result<(), String> {
    let (namespace, provider) = validate_credential_target(namespace, provider)?;
    vault
        .remove_secret(&namespace, &provider)
        .map_err(|_| "Could not delete credential from the system vault".to_string())?;
    match vault.get_secret(&namespace, &provider) {
        Ok(None) => Ok(()),
        _ => Err("Credential deletion could not be verified".to_string()),
    }
}

fn credential_status_from_vault<V: CredentialSecretReader>(
    namespace: String,
    provider: String,
    vault: &V,
) -> Result<CredentialStatus, String> {
    let (namespace, provider) = validate_credential_target(namespace, provider)?;
    let has_secret = match vault.get_secret(&namespace, &provider) {
        Ok(secret) => secret.is_some_and(|value| !value.trim().is_empty()),
        Err(_) => {
            tracing::warn!("Credential vault unavailable");
            return Ok(CredentialStatus {
                namespace,
                provider,
                has_secret: false,
                updated_at: None,
                storage: CredentialStorage::Unavailable,
            });
        }
    };
    let updated_at = if has_secret {
        vault
            .get_secret_updated_at(&namespace, &provider)
            .map_err(|_| "Could not read credential metadata from the system vault".to_string())?
    } else {
        None
    };

    Ok(CredentialStatus {
        namespace,
        provider,
        has_secret,
        updated_at,
        storage: CredentialStorage::OsVault,
    })
}

fn ensure_main_window<R: tauri::Runtime>(window: &Window<R>) -> Result<(), String> {
    ensure_main_window_label(window.label())
}

fn ensure_main_window_label(label: &str) -> Result<(), String> {
    if label == "main" {
        Ok(())
    } else {
        Err("credential access is only allowed from the main window".to_string())
    }
}

fn validate_credential_target(
    namespace: String,
    provider: String,
) -> Result<(String, String), String> {
    let namespace = namespace.trim().to_string();
    let provider = provider.trim().to_string();

    if namespace != "stt" && namespace != "llm" {
        return Err(format!("unknown credential namespace: {namespace}"));
    }
    if provider.is_empty() {
        return Err("credential provider is required".to_string());
    }
    if provider.len() > 80
        || !provider
            .chars()
            .all(|c| c.is_ascii_alphanumeric() || matches!(c, '-' | '_' | '.'))
    {
        return Err(format!("unsupported credential provider: {provider}"));
    }

    Ok((namespace, provider))
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn credential_migration_commit_broadcast_redacts_secrets_and_preserves_owned_revision() {
        use std::sync::{Arc, Mutex};
        use tauri::Listener;

        let app = tauri::test::mock_builder()
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let received = Arc::new(Mutex::new(Vec::<serde_json::Value>::new()));
        let captured = received.clone();
        let listener = app.listen_any("config:commit", move |event| {
            captured
                .lock()
                .unwrap()
                .push(serde_json::from_str(event.payload()).unwrap());
        });
        let config = storage::AppConfig {
            microphone_device: "synthetic-owned-device".into(),
            stt_api_key: "synthetic-secret".into(),
            stt_custom_api_key: "synthetic-custom-secret".into(),
            llm_api_key: "synthetic-llm-secret".into(),
            ..storage::AppConfig::default()
        };
        let epoch = "11111111-1111-4111-8111-111111111111";
        crate::commands::config::emit_config_commit(app.handle(), epoch, 7, &config);
        let events = received.lock().unwrap();
        assert_eq!(events.len(), 1);
        assert_eq!(events[0]["configEpoch"], epoch);
        assert_eq!(events[0]["revision"], 7);
        assert_eq!(events[0]["saveId"], serde_json::Value::Null);
        assert_eq!(
            events[0]["config"]["microphone_device"],
            "synthetic-owned-device"
        );
        for field in ["stt_api_key", "stt_custom_api_key", "llm_api_key"] {
            assert_eq!(events[0]["config"][field], "");
        }
        app.unlisten(listener);
    }

    #[test]
    fn legacy_migration_commits_current_settings_and_emits_the_owned_version() {
        let source = include_str!("credentials.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
        let command = source
            .split("pub async fn migrate_legacy_credentials(")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        assert!(command.contains(".migrate_current_credentials_commit()"));
        assert!(command.contains("emit_config_commit("));
        assert!(command.contains("config_state.config_epoch(),"));
        assert!(command.contains("revision,"));
        assert!(command.contains("&config,"));
        assert!(!command.contains("config_state.save(&config)"));
        assert!(
            command.find("ensure_main_window(&window)?").unwrap()
                < command.find("config_state.load().await").unwrap()
        );
    }

    #[test]
    fn credential_ipc_checks_window_before_target_validation() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![
                get_credential_status,
                read_credential,
                set_credential,
                clear_credential
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["main", "capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for command in [
                "get_credential_status",
                "read_credential",
                "set_credential",
                "clear_credential",
            ] {
                let response = tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: command.to_string(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "namespace": "invalid",
                            "provider": "synthetic-provider",
                            "value": "synthetic-value"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                assert_eq!(
                    response.unwrap_err(),
                    serde_json::json!(if label == "main" {
                        "unknown credential namespace: invalid"
                    } else {
                        "credential access is only allowed from the main window"
                    })
                );
            }
        }
    }

    #[test]
    fn auxiliary_credential_mutations_are_denied_before_vault_access() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![set_credential, clear_credential])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for namespace in ["stt", "llm"] {
                for (command, value) in [
                    ("set_credential", "synthetic-replacement"),
                    ("set_credential", ""),
                    ("clear_credential", ""),
                ] {
                    let response = tauri::test::get_ipc_response(
                        &webview,
                        tauri::webview::InvokeRequest {
                            cmd: command.to_string(),
                            callback: tauri::ipc::CallbackFn(0),
                            error: tauri::ipc::CallbackFn(1),
                            url: "tauri://localhost".parse().unwrap(),
                            body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                                "namespace": namespace,
                                "provider": "synthetic-provider",
                                "value": value
                            })),
                            headers: Default::default(),
                            invoke_key: tauri::test::INVOKE_KEY.to_string(),
                        },
                    );
                    assert_eq!(
                        response.unwrap_err(),
                        serde_json::json!("credential access is only allowed from the main window")
                    );
                }
            }
        }
    }

    #[test]
    fn auxiliary_read_credential_ipc_is_denied_before_vault_access() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![read_credential])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for namespace in ["stt", "llm"] {
                let response = tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: "read_credential".to_string(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "namespace": namespace,
                            "provider": "synthetic-provider"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                assert_eq!(
                    response.unwrap_err(),
                    serde_json::json!("credential access is only allowed from the main window")
                );
            }
        }
    }
    use anyhow::Result;
    use std::collections::HashMap;
    use std::sync::Mutex;

    #[derive(Default)]
    struct MemoryCredentialVault {
        records: Mutex<HashMap<(String, String), String>>,
        updated_at: Mutex<HashMap<(String, String), String>>,
    }

    impl CredentialVault for MemoryCredentialVault {
        fn set_secret(&self, namespace: &str, provider: &str, secret: &str) -> Result<()> {
            self.records.lock().unwrap().insert(
                (namespace.to_string(), provider.to_string()),
                secret.to_string(),
            );
            Ok(())
        }
    }

    impl CredentialSecretReader for MemoryCredentialVault {
        fn get_secret(&self, namespace: &str, provider: &str) -> Result<Option<String>> {
            Ok(self
                .records
                .lock()
                .unwrap()
                .get(&(namespace.to_string(), provider.to_string()))
                .cloned())
        }

        fn get_secret_updated_at(&self, namespace: &str, provider: &str) -> Result<Option<String>> {
            Ok(self
                .updated_at
                .lock()
                .unwrap()
                .get(&(namespace.to_string(), provider.to_string()))
                .cloned())
        }
    }

    impl CredentialSecretRemover for MemoryCredentialVault {
        fn remove_secret(&self, namespace: &str, provider: &str) -> Result<()> {
            self.records
                .lock()
                .unwrap()
                .remove(&(namespace.to_string(), provider.to_string()));
            Ok(())
        }
    }

    struct FailingCredentialVault;

    #[test]
    fn credential_metadata_failure_does_not_expose_vault_details() {
        struct FailingMetadata;
        impl CredentialSecretReader for FailingMetadata {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Ok(Some("synthetic-key".to_string()))
            }

            fn get_secret_updated_at(
                &self,
                _namespace: &str,
                _provider: &str,
            ) -> Result<Option<String>> {
                Err(anyhow::anyhow!("synthetic-private-metadata-detail"))
            }
        }
        let result =
            credential_status_from_vault("llm".to_string(), "openai".to_string(), &FailingMetadata);
        assert_eq!(
            result.unwrap_err(),
            "Could not read credential metadata from the system vault"
        );
    }

    #[test]
    fn credential_status_failure_does_not_log_private_vault_details() {
        struct LogWriter(std::sync::Arc<Mutex<Vec<u8>>>);
        impl std::io::Write for LogWriter {
            fn write(&mut self, bytes: &[u8]) -> std::io::Result<usize> {
                self.0.lock().unwrap().extend_from_slice(bytes);
                Ok(bytes.len())
            }
            fn flush(&mut self) -> std::io::Result<()> {
                Ok(())
            }
        }
        struct PrivateFailure;
        impl CredentialSecretReader for PrivateFailure {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Err(anyhow::anyhow!("synthetic-private-vault-secret"))
            }
        }
        let output = std::sync::Arc::new(Mutex::new(Vec::new()));
        let writer = output.clone();
        let subscriber = tracing_subscriber::fmt()
            .without_time()
            .with_ansi(false)
            .with_writer(move || LogWriter(writer.clone()))
            .finish();
        let status = tracing::subscriber::with_default(subscriber, || {
            credential_status_from_vault("llm".to_string(), "openai".to_string(), &PrivateFailure)
                .unwrap()
        });
        assert_eq!(status.storage, CredentialStorage::Unavailable);
        let logged = String::from_utf8(output.lock().unwrap().clone()).unwrap();
        assert!(logged.contains("Credential vault unavailable"));
        assert!(!logged.contains("synthetic-private-vault-secret"));
    }

    #[test]
    fn credential_read_failure_does_not_expose_vault_details() {
        struct PrivateReadFailure;
        impl CredentialSecretReader for PrivateReadFailure {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Err(anyhow::anyhow!("synthetic-private-key in vault failure"))
            }
        }
        assert_eq!(
            read_credential_from_vault("llm", "openai", &PrivateReadFailure).unwrap_err(),
            "Could not read credential from the system vault"
        );
    }

    #[test]
    fn credential_read_preserves_exact_secrets_and_missing_entries() {
        let vault = MemoryCredentialVault::default();
        assert_eq!(
            read_credential_from_vault("llm", "openai", &vault).unwrap(),
            None
        );
        let secret = " synthetic+$密钥 ";
        vault.set_secret("llm", "openai", secret).unwrap();
        assert_eq!(
            read_credential_from_vault("llm", "openai", &vault)
                .unwrap()
                .as_deref(),
            Some(secret)
        );
    }

    #[test]
    fn credential_access_requires_the_main_window_label() {
        assert!(ensure_main_window_label("main").is_ok());
        for label in ["capsule", "ask", "", "main-preview"] {
            assert!(ensure_main_window_label(label).is_err());
        }
    }

    #[test]
    fn credential_save_verifies_stored_value_before_emitting_success() {
        let production = include_str!("credentials.rs")
            .split("\n#[cfg(test)]\nmod tests")
            .next()
            .unwrap();
        let command = production
            .split("pub fn set_credential<")
            .nth(1)
            .unwrap()
            .split("#[tauri::command]")
            .next()
            .unwrap();
        let verification = command.find("store_credential_in_vault(");
        assert!(verification.is_some(), "save must verify the vault write");
        assert!(verification.unwrap() < command.find("window.emit(").unwrap());
    }

    #[test]
    fn credential_save_preserves_exact_value_after_verification() {
        let vault = MemoryCredentialVault::default();
        let value = " synthetic+$密钥 ";
        store_credential_in_vault("llm", "openai", value, &vault).unwrap();
        assert_eq!(
            vault.get_secret("llm", "openai").unwrap().as_deref(),
            Some(value)
        );
    }

    #[test]
    fn credential_mutation_failures_do_not_echo_vault_details() {
        struct FailingMutations;
        impl CredentialVault for FailingMutations {
            fn set_secret(&self, _namespace: &str, _provider: &str, _value: &str) -> Result<()> {
                Err(anyhow::anyhow!("synthetic-private-key write failure"))
            }
        }
        impl CredentialSecretRemover for FailingMutations {
            fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
                Err(anyhow::anyhow!("synthetic-private-key deletion failure"))
            }
        }
        impl CredentialSecretReader for FailingMutations {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                panic!("failed mutations must not attempt verification")
            }
        }
        assert_eq!(
            store_credential_in_vault("llm", "openai", "synthetic-private-key", &FailingMutations)
                .unwrap_err(),
            "Could not save credential in the system vault"
        );
        assert_eq!(
            clear_credential_from_vault(
                "llm".to_string(),
                "openai".to_string(),
                &FailingMutations,
            )
            .unwrap_err(),
            "Could not delete credential from the system vault"
        );
    }

    #[test]
    fn credential_save_rejects_ignored_or_unreadable_writes() {
        struct IgnoredWrite {
            unreadable: bool,
        }
        impl CredentialVault for IgnoredWrite {
            fn set_secret(&self, _namespace: &str, _provider: &str, _value: &str) -> Result<()> {
                Ok(())
            }
        }
        impl CredentialSecretReader for IgnoredWrite {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                if self.unreadable {
                    Err(anyhow::anyhow!("synthetic-private-vault-detail"))
                } else {
                    Ok(Some("old-private-key".to_string()))
                }
            }
        }
        for unreadable in [false, true] {
            assert_eq!(
                store_credential_in_vault(
                    "llm",
                    "openai",
                    "new-private-key",
                    &IgnoredWrite { unreadable },
                )
                .unwrap_err(),
                "Credential save could not be verified"
            );
        }
    }

    impl CredentialSecretReader for FailingCredentialVault {
        fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
            Err(anyhow::anyhow!("vault unavailable"))
        }
    }

    #[test]
    fn validates_supported_credential_target() {
        assert_eq!(
            validate_credential_target(" stt ".to_string(), "custom-whisper".to_string()).unwrap(),
            ("stt".to_string(), "custom-whisper".to_string())
        );
    }

    #[test]
    fn rejects_unknown_namespace() {
        let err =
            validate_credential_target("other".to_string(), "openai".to_string()).unwrap_err();

        assert!(err.contains("unknown credential namespace"));
    }

    #[test]
    fn rejects_provider_with_path_separators() {
        let err =
            validate_credential_target("llm".to_string(), "openai/key".to_string()).unwrap_err();

        assert!(err.contains("unsupported credential provider"));
    }

    #[test]
    fn clear_credential_from_vault_removes_valid_target() {
        let vault = MemoryCredentialVault::default();
        vault.set_secret("llm", "openai", "secret").unwrap();

        clear_credential_from_vault("llm".to_string(), "openai".to_string(), &vault).unwrap();

        assert_eq!(vault.get_secret("llm", "openai").unwrap(), None);
    }

    #[test]
    fn credential_clear_rejects_success_when_the_secret_remains() {
        struct IgnoredRemoval;
        impl CredentialSecretRemover for IgnoredRemoval {
            fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
                Ok(())
            }
        }
        impl CredentialSecretReader for IgnoredRemoval {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Ok(Some("synthetic-private-key".to_string()))
            }
        }
        let result =
            clear_credential_from_vault("llm".to_string(), "openai".to_string(), &IgnoredRemoval);
        assert_eq!(
            result.unwrap_err(),
            "Credential deletion could not be verified"
        );
    }

    #[test]
    fn credential_clear_rejects_unreadable_verification_without_echoing_details() {
        struct UnreadableRemoval;
        impl CredentialSecretRemover for UnreadableRemoval {
            fn remove_secret(&self, _namespace: &str, _provider: &str) -> Result<()> {
                Ok(())
            }
        }
        impl CredentialSecretReader for UnreadableRemoval {
            fn get_secret(&self, _namespace: &str, _provider: &str) -> Result<Option<String>> {
                Err(anyhow::anyhow!("synthetic-private-vault-detail"))
            }
        }
        let result = clear_credential_from_vault(
            "stt".to_string(),
            "deepgram".to_string(),
            &UnreadableRemoval,
        );
        assert_eq!(
            result.unwrap_err(),
            "Credential deletion could not be verified"
        );
    }

    #[test]
    fn credential_status_reports_spec_contract_shape() {
        let vault = MemoryCredentialVault::default();
        vault.set_secret("llm", "openai", "secret").unwrap();

        let status =
            credential_status_from_vault("llm".to_string(), "openai".to_string(), &vault).unwrap();

        assert_eq!(status.namespace, "llm");
        assert_eq!(status.provider, "openai");
        assert!(status.has_secret);
        assert_eq!(status.updated_at, None);
        assert_eq!(status.storage, CredentialStorage::OsVault);
    }

    #[test]
    fn credential_status_does_not_treat_blank_vault_entries_as_configured() {
        for secret in ["", " ", "\n\t"] {
            let vault = MemoryCredentialVault::default();
            vault.set_secret("llm", "openai", secret).unwrap();
            vault.updated_at.lock().unwrap().insert(
                ("llm".to_string(), "openai".to_string()),
                "synthetic-timestamp".to_string(),
            );
            let status =
                credential_status_from_vault("llm".to_string(), "openai".to_string(), &vault)
                    .unwrap();
            assert!(!status.has_secret);
            assert_eq!(status.updated_at, None);
            assert_eq!(status.storage, CredentialStorage::OsVault);
        }
    }

    #[test]
    fn credential_status_maps_vault_failure_to_unavailable_storage() {
        let status = credential_status_from_vault(
            "llm".to_string(),
            "openai".to_string(),
            &FailingCredentialVault,
        )
        .unwrap();

        assert_eq!(status.namespace, "llm");
        assert_eq!(status.provider, "openai");
        assert!(!status.has_secret);
        assert_eq!(status.updated_at, None);
        assert_eq!(status.storage, CredentialStorage::Unavailable);
    }

    #[test]
    fn credential_status_reports_stored_updated_at() {
        let vault = MemoryCredentialVault::default();
        vault.set_secret("llm", "openai", "secret").unwrap();
        vault.updated_at.lock().unwrap().insert(
            ("llm".to_string(), "openai".to_string()),
            "2026-07-06T00:00:00Z".to_string(),
        );

        let status =
            credential_status_from_vault("llm".to_string(), "openai".to_string(), &vault).unwrap();

        assert_eq!(status.updated_at.as_deref(), Some("2026-07-06T00:00:00Z"));
    }
}
