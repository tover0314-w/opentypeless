use crate::credentials::{resolve_config_secret, SystemCredentialVault};
use crate::storage;
use crate::stt;
use crate::stt::SttProvider;
use crate::SessionTokenStore;
use crate::{api_base_url, with_desktop_client_version};
use tauri::Emitter;

fn ensure_stt_configuration_window(label: &str) -> Result<(), String> {
    if label == "main" {
        Ok(())
    } else {
        Err("STT configuration requests are only allowed from the main window".to_string())
    }
}

#[tauri::command]
pub async fn get_stt_recording_capability(
    state: tauri::State<'_, storage::ConfigManager>,
    provider: String,
    mode: stt::capabilities::RecordingLimitMode,
    custom_seconds: u32,
) -> Result<stt::capabilities::ResolvedRecordingLimit, String> {
    let mut config = state.load().await.map_err(|error| error.to_string())?;
    config.stt_provider = provider;
    config.recording_limit_mode = mode;
    config.custom_recording_limit_seconds = custom_seconds;
    Ok(stt::capabilities::resolve_recording_limit(
        &config,
        None,
        chrono::Utc::now().timestamp(),
    ))
}

#[tauri::command]
pub async fn cache_managed_stt_capability<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
    account_snapshot: stt::capabilities::AuthenticatedAccountSnapshot,
    expected_user_id: String,
) -> Result<stt::capabilities::ResolvedRecordingLimit, String> {
    ensure_stt_configuration_window(window.label())?;
    let state = tauri::Manager::try_state::<storage::ConfigManager>(&app)
        .ok_or_else(|| "STT configuration state is unavailable".to_string())?;
    let now = chrono::Utc::now().timestamp();
    let managed_state = stt::capabilities::managed_state_from_authenticated_snapshot(
        account_snapshot,
        &expected_user_id,
        now,
    )?;
    state.load().await.map_err(|error| error.to_string())?;
    let mut previous_max_seconds = 0;
    let (revision, config) = state
        .try_update_current_config_commit(|current| {
            previous_max_seconds = current.max_recording_seconds;
            current.managed_stt_capability_state = managed_state;
            current.recompute_recording_limit_mirror();
            Ok(())
        })
        .map_err(|error| error.to_string())?;
    super::config::emit_config_commit(&app, state.config_epoch(), revision, &config);
    if config.max_recording_seconds != previous_max_seconds {
        let _ = app.emit(
            "config:patch",
            serde_json::json!({ "max_recording_seconds": config.max_recording_seconds }),
        );
    }
    Ok(stt::capabilities::resolve_recording_limit(
        &config, None, now,
    ))
}

#[tauri::command]
pub async fn clear_managed_stt_capability<R: tauri::Runtime>(
    window: tauri::Window<R>,
    app: tauri::AppHandle<R>,
) -> Result<(), String> {
    ensure_stt_configuration_window(window.label())?;
    let state = tauri::Manager::try_state::<storage::ConfigManager>(&app)
        .ok_or_else(|| "STT configuration state is unavailable".to_string())?;
    state.load().await.map_err(|error| error.to_string())?;
    let mut previous_max_seconds = 0;
    let (revision, config) = state
        .try_update_current_config_commit(|current| {
            previous_max_seconds = current.max_recording_seconds;
            current.managed_stt_capability_state = None;
            current.recompute_recording_limit_mirror();
            Ok(())
        })
        .map_err(|error| error.to_string())?;
    super::config::emit_config_commit(&app, state.config_epoch(), revision, &config);
    if config.max_recording_seconds != previous_max_seconds {
        let _ = app.emit(
            "config:patch",
            serde_json::json!({ "max_recording_seconds": config.max_recording_seconds }),
        );
    }
    Ok(())
}

async fn check_volcengine_doubao_connection(
    api_key: &str,
    resource_id: Option<String>,
) -> Result<(), String> {
    let mut provider = stt::volcengine::VolcengineDoubaoProvider::new();
    let config = stt::SttConfig {
        api_key: api_key.to_string(),
        language: Some("zh".to_string()),
        smart_format: true,
        sample_rate: 16000,
        resource_id,
        operation_id: None,
        managed_audio: None,
        provider_region: None,
    };
    provider.connect(&config).await.map_err(|e| e.to_string())?;
    let _ = provider.disconnect().await;
    Ok(())
}

async fn check_aliyun_qwen3_connection(
    api_key: &str,
    region: Option<String>,
) -> Result<(), String> {
    let mut provider = stt::aliyun_qwen3_asr::AliyunQwen3AsrProvider::new();
    let config = stt::SttConfig {
        api_key: api_key.to_string(),
        language: Some("zh".to_string()),
        smart_format: true,
        sample_rate: 16_000,
        resource_id: None,
        operation_id: None,
        managed_audio: None,
        provider_region: region,
    };
    provider
        .connect(&config)
        .await
        .map_err(|error| error.to_string())?;
    provider
        .disconnect()
        .await
        .map_err(|error| error.to_string())?;
    Ok(())
}

fn has_managed_cloud_access(body: &serde_json::Value) -> bool {
    if matches!(
        body["licenseStatus"].as_str(),
        Some("refunded") | Some("deactivated")
    ) {
        return false;
    }

    let source = body["source"].as_str().unwrap_or_default();
    let plan = body["plan"].as_str().unwrap_or_default();
    let cloud_words_limit = body["cloudWordsLimit"].as_i64().unwrap_or_default();
    let display_words_limit = body["displayWordsLimit"].as_i64().unwrap_or_default();
    if source == "appsumo" {
        return cloud_words_limit > 0 && body["licenseStatus"].as_str() == Some("active");
    }
    if source == "lifetime" {
        return cloud_words_limit > 0 || display_words_limit > 0 || plan == "lifetime_starter";
    }
    if source == "creem" && (cloud_words_limit > 0 || display_words_limit > 0) {
        return true;
    }

    matches!(plan, "pro" | "lifetime_starter")
}

fn resolve_whisper_test_config(
    provider: &str,
    custom_base_url: Option<String>,
    custom_model: Option<String>,
) -> Result<stt::whisper_compat::WhisperCompatConfig, String> {
    if provider == stt::config::CUSTOM_WHISPER_PROVIDER {
        return stt::config::build_custom_whisper_config(
            custom_base_url.as_deref().unwrap_or_default(),
            custom_model.as_deref().unwrap_or_default(),
        );
    }

    stt::config::build_known_whisper_config(provider)
        .ok_or_else(|| format!("Unknown STT provider: {}", provider))
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SttProviderDiagnosticIssue {
    pub code: String,
    pub message: String,
}

#[derive(Debug, Clone, PartialEq, Eq, serde::Serialize)]
#[serde(rename_all = "camelCase")]
pub struct SttProviderDiagnostics {
    pub provider: String,
    pub kind: String,
    pub endpoint: Option<String>,
    pub model: Option<String>,
    pub requires_api_key: bool,
    pub api_key_configured: bool,
    pub ready: bool,
    pub issues: Vec<SttProviderDiagnosticIssue>,
}

fn diagnostic_issue(code: &str, message: impl Into<String>) -> SttProviderDiagnosticIssue {
    SttProviderDiagnosticIssue {
        code: code.to_string(),
        message: message.into(),
    }
}

fn build_remote_stt_diagnostics(
    provider: &str,
    api_key: &str,
    provider_region: Option<&str>,
) -> SttProviderDiagnostics {
    let whisper_config = stt::config::build_known_whisper_config(provider);
    let (endpoint, model) = if let Some(cfg) = whisper_config {
        (Some(cfg.endpoint), Some(cfg.model))
    } else {
        match provider {
            "deepgram" => (Some("https://api.deepgram.com/v1/listen".to_string()), None),
            "assemblyai" => (
                Some("https://api.assemblyai.com/v2/transcript".to_string()),
                None,
            ),
            stt::volcengine::VOLCENGINE_DOUBAO_PROVIDER => (
                Some("wss://openspeech.bytedance.com/api/v3/sauc/bigmodel".to_string()),
                None,
            ),
            stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_PROVIDER => (
                Some(stt::aliyun_qwen3_asr::qwen_realtime_url(provider_region).to_string()),
                Some("qwen3-asr-flash-realtime".to_string()),
            ),
            _ => (None, None),
        }
    };

    let api_key_configured = !api_key.trim().is_empty();
    let mut issues = Vec::new();
    if !api_key_configured {
        issues.push(diagnostic_issue(
            "missing_api_key",
            "API key is required for this STT provider",
        ));
    }

    SttProviderDiagnostics {
        provider: provider.to_string(),
        kind: "byokRemote".to_string(),
        endpoint,
        model,
        requires_api_key: true,
        api_key_configured,
        ready: issues.is_empty(),
        issues,
    }
}

fn build_apple_speech_diagnostics(
    provider: &str,
    availability: stt::apple_speech::AppleSpeechAvailability,
) -> SttProviderDiagnostics {
    SttProviderDiagnostics {
        provider: provider.to_string(),
        kind: "builtinLocal".to_string(),
        endpoint: None,
        model: Some(
            availability
                .locale
                .as_ref()
                .map(|locale| format!("Apple Speech ({locale})"))
                .unwrap_or_else(|| "Apple Speech".to_string()),
        ),
        requires_api_key: false,
        api_key_configured: false,
        ready: availability.ready,
        issues: match (availability.issue_code, availability.issue_message) {
            (Some(code), Some(message)) => vec![diagnostic_issue(&code, message)],
            (Some(code), None) => vec![diagnostic_issue(&code, code.clone())],
            _ => Vec::new(),
        },
    }
}

fn build_stt_provider_diagnostics(
    provider: &str,
    api_key: &str,
    custom_base_url: Option<&str>,
    custom_model: Option<&str>,
    provider_region: Option<&str>,
) -> SttProviderDiagnostics {
    match provider {
        "" => SttProviderDiagnostics {
            provider: provider.to_string(),
            kind: "unknown".to_string(),
            endpoint: None,
            model: None,
            requires_api_key: false,
            api_key_configured: false,
            ready: false,
            issues: vec![diagnostic_issue(
                "missing_provider",
                "No STT provider selected",
            )],
        },
        "cloud" => SttProviderDiagnostics {
            provider: provider.to_string(),
            kind: "cloudManaged".to_string(),
            endpoint: None,
            model: None,
            requires_api_key: false,
            api_key_configured: false,
            ready: true,
            issues: Vec::new(),
        },
        stt::config::APPLE_SPEECH_PROVIDER => build_apple_speech_diagnostics(
            provider,
            stt::apple_speech::apple_speech_availability(None),
        ),
        stt::config::CUSTOM_WHISPER_PROVIDER => {
            let api_key_configured = !api_key.trim().is_empty();
            match stt::config::build_custom_whisper_config(
                custom_base_url.unwrap_or_default(),
                custom_model.unwrap_or_default(),
            ) {
                Ok(cfg) => SttProviderDiagnostics {
                    provider: provider.to_string(),
                    kind: "localCompatible".to_string(),
                    endpoint: diagnostic_endpoint(&cfg.endpoint),
                    model: Some(cfg.model),
                    requires_api_key: cfg.api_key_required,
                    api_key_configured,
                    ready: true,
                    issues: Vec::new(),
                },
                Err(err) => SttProviderDiagnostics {
                    provider: provider.to_string(),
                    kind: "localCompatible".to_string(),
                    endpoint: None,
                    model: custom_model
                        .map(str::trim)
                        .filter(|value| !value.is_empty())
                        .map(ToString::to_string),
                    requires_api_key: false,
                    api_key_configured,
                    ready: false,
                    issues: vec![diagnostic_issue("invalid_custom_whisper_config", err)],
                },
            }
        }
        "deepgram"
        | "assemblyai"
        | stt::volcengine::VOLCENGINE_DOUBAO_PROVIDER
        | stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_PROVIDER => {
            build_remote_stt_diagnostics(provider, api_key, provider_region)
        }
        _ if stt::config::build_known_whisper_config(provider).is_some() => {
            build_remote_stt_diagnostics(provider, api_key, provider_region)
        }
        _ => SttProviderDiagnostics {
            provider: provider.to_string(),
            kind: "unknown".to_string(),
            endpoint: None,
            model: None,
            requires_api_key: false,
            api_key_configured: !api_key.trim().is_empty(),
            ready: false,
            issues: vec![diagnostic_issue(
                "unknown_provider",
                format!("Unknown STT provider: {}", provider),
            )],
        },
    }
}

fn diagnostic_endpoint(endpoint: &str) -> Option<String> {
    let mut endpoint = url::Url::parse(endpoint).ok()?;
    endpoint.set_query(None);
    endpoint.set_fragment(None);
    endpoint.set_username("").ok()?;
    endpoint.set_password(None).ok()?;
    Some(endpoint.to_string())
}

async fn check_openai_whisper_model(client: &reqwest::Client, api_key: &str) -> Result<(), String> {
    let resp = client
        .get("https://api.openai.com/v1/models/whisper-1")
        .header("Authorization", format!("Bearer {}", api_key))
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await
        .map_err(crate::error::request_error_message)?;

    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }

    Ok(())
}

#[tauri::command]
pub fn get_stt_provider_diagnostics<R: tauri::Runtime>(
    window: tauri::Window<R>,
    api_key: String,
    provider: String,
    custom_base_url: Option<String>,
    custom_model: Option<String>,
    provider_region: Option<String>,
) -> Result<SttProviderDiagnostics, String> {
    ensure_stt_configuration_window(window.label())?;
    let resolved_api_key = if provider == "cloud" {
        String::new()
    } else {
        resolve_config_secret(&api_key, "stt", &provider, &SystemCredentialVault)
            .map_err(|e| e.to_string())?
    };

    Ok(build_stt_provider_diagnostics(
        &provider,
        &resolved_api_key,
        custom_base_url.as_deref(),
        custom_model.as_deref(),
        provider_region.as_deref(),
    ))
}

#[tauri::command]
// Keep these as explicit IPC fields so older desktop callers remain wire-compatible.
#[allow(clippy::too_many_arguments)]
pub async fn test_stt_connection<R: tauri::Runtime>(
    window: tauri::Window<R>,
    api_key: String,
    provider: String,
    custom_base_url: Option<String>,
    custom_model: Option<String>,
    volcengine_resource_id: Option<String>,
    provider_region: Option<String>,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<bool, String> {
    ensure_stt_configuration_window(window.label())?;
    if provider.is_empty() {
        return Ok(false);
    }

    // Cloud provider: verify session token + managed cloud entitlement via API.
    if provider == "cloud" {
        let token = token_store
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        if token.is_empty() {
            return Ok(false);
        }
        let api_base = api_base_url();
        let resp = with_desktop_client_version(
            client.get(format!("{}/api/subscription/status", api_base)),
        )
        .header("Authorization", format!("Bearer {}", token))
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await
        .map_err(crate::error::request_error_message)?;
        if !resp.status().is_success() {
            return Ok(false);
        }
        let body = crate::cloud_request::read_json_response_with_limit(resp, 65536)
            .await
            .map_err(|error| error.to_string())?;
        return Ok(has_managed_cloud_access(&body));
    }

    let api_key = resolve_config_secret(&api_key, "stt", &provider, &SystemCredentialVault)
        .map_err(|e| e.to_string())?;

    if stt::config::stt_provider_requires_api_key(&provider) && api_key.is_empty() {
        return Ok(false);
    }

    match provider.as_str() {
        "deepgram" => {
            let resp = client
                .get("https://api.deepgram.com/v1/projects")
                .header("Authorization", format!("Token {}", api_key))
                .timeout(std::time::Duration::from_secs(10))
                .send()
                .await
                .map_err(crate::error::request_error_message)?;
            Ok(resp.status().is_success())
        }
        "assemblyai" => {
            let resp = client
                .get("https://api.assemblyai.com/v2/transcript?limit=1")
                .header("Authorization", api_key)
                .timeout(std::time::Duration::from_secs(10))
                .send()
                .await
                .map_err(crate::error::request_error_message)?;
            Ok(resp.status().is_success())
        }
        stt::volcengine::VOLCENGINE_DOUBAO_PROVIDER => Ok(check_volcengine_doubao_connection(
            &api_key,
            volcengine_resource_id,
        )
        .await
        .is_ok()),
        stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_PROVIDER => {
            Ok(check_aliyun_qwen3_connection(&api_key, provider_region)
                .await
                .is_ok())
        }
        stt::config::APPLE_SPEECH_PROVIDER => {
            let authorization = stt::apple_speech::request_apple_speech_authorization()
                .map_err(|e| e.to_string())?;
            if authorization != stt::apple_speech::AppleSpeechAuthorizationStatus::Authorized {
                return Ok(false);
            }
            Ok(stt::apple_speech::apple_speech_availability(None).ready)
        }
        "openai-whisper" => Ok(check_openai_whisper_model(&client, &api_key).await.is_ok()),
        _ => {
            let cfg = resolve_whisper_test_config(&provider, custom_base_url, custom_model)?;

            let silent_pcm = vec![0u8; 3200]; // 0.1s at 16kHz 16-bit mono
            let wav = stt::whisper_compat::WhisperCompatProvider::build_wav(&silent_pcm, 16000);

            let file_part = reqwest::multipart::Part::bytes(wav)
                .file_name("test.wav")
                .mime_str("audio/wav")
                .map_err(|e| e.to_string())?;
            let mut form = reqwest::multipart::Form::new()
                .text("model", cfg.model.clone())
                .part("file", file_part);
            for (key, value) in &cfg.extra_fields {
                form = form.text(key.clone(), value.clone());
            }

            let mut request = client
                .post(&cfg.endpoint)
                .multipart(form)
                .timeout(std::time::Duration::from_secs(15));

            if !api_key.trim().is_empty() {
                request = request.header("Authorization", format!("Bearer {}", api_key));
            }

            let resp = request
                .send()
                .await
                .map_err(crate::error::request_error_message)?;
            Ok(resp.status().is_success())
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn managed_capability_ipc_rejects_auxiliary_windows_before_configuration_access() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![
                cache_managed_stt_capability,
                clear_managed_stt_capability
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["main", "capsule", "ask", "unknown"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for command in [
                "cache_managed_stt_capability",
                "clear_managed_stt_capability",
            ] {
                let response = tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: command.to_string(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "accountSnapshot": {
                                "schemaVersion": 1,
                                "userId": "synthetic-user",
                                "generatedAt": "2026-11-12T00:00:00Z",
                                "managedSttCapabilities": null
                            },
                            "expectedUserId": "synthetic-user"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                let expected = if label == "main" {
                    "STT configuration state is unavailable"
                } else {
                    "STT configuration requests are only allowed from the main window"
                };
                assert_eq!(response.unwrap_err(), serde_json::json!(expected));
            }
        }
    }

    #[test]
    fn main_stt_diagnostics_ipc_preserves_custom_configuration_without_exposing_key() {
        let app = tauri::test::mock_builder()
            .invoke_handler(tauri::generate_handler![get_stt_provider_diagnostics])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        let response = tauri::test::get_ipc_response(
            &webview,
            tauri::webview::InvokeRequest {
                cmd: "get_stt_provider_diagnostics".to_string(),
                callback: tauri::ipc::CallbackFn(0),
                error: tauri::ipc::CallbackFn(1),
                url: "tauri://localhost".parse().unwrap(),
                body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                    "apiKey": "synthetic-private-key",
                    "provider": "custom-whisper",
                    "customBaseUrl": "http://127.0.0.1:8000/v1",
                    "customModel": "synthetic-local-model"
                })),
                headers: Default::default(),
                invoke_key: tauri::test::INVOKE_KEY.to_string(),
            },
        );
        let diagnostics = response
            .unwrap()
            .deserialize::<serde_json::Value>()
            .unwrap();
        assert_eq!(diagnostics["provider"], "custom-whisper");
        assert_eq!(diagnostics["kind"], "localCompatible");
        assert_eq!(diagnostics["model"], "synthetic-local-model");
        assert_eq!(
            diagnostics["endpoint"],
            "http://127.0.0.1:8000/v1/audio/transcriptions"
        );
        assert_eq!(diagnostics["ready"], true);
        assert_eq!(diagnostics["apiKeyConfigured"], true);
        assert!(!diagnostics.to_string().contains("synthetic-private-key"));
    }

    #[test]
    fn auxiliary_stt_configuration_ipc_is_denied_before_provider_access() {
        let app = tauri::test::mock_builder()
            .manage(SessionTokenStore(
                std::sync::Arc::new(std::sync::Mutex::new("synthetic-token".to_string())),
                std::sync::atomic::AtomicBool::new(false),
                std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .manage(reqwest::Client::new())
            .invoke_handler(tauri::generate_handler![
                get_stt_provider_diagnostics,
                test_stt_connection,
                bench_stt_connection
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for command in [
                "get_stt_provider_diagnostics",
                "test_stt_connection",
                "bench_stt_connection",
            ] {
                let response = tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: command.to_string(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "apiKey": "synthetic-key",
                            "provider": "cloud"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                assert_eq!(
                    response.unwrap_err(),
                    serde_json::json!(
                        "STT configuration requests are only allowed from the main window"
                    )
                );
            }
        }
    }

    #[test]
    fn stt_configuration_checks_window_before_credentials_or_network() {
        let source = include_str!("stt.rs");
        for signature in [
            "pub fn get_stt_provider_diagnostics<",
            "pub async fn test_stt_connection<",
            "pub async fn bench_stt_connection<",
        ] {
            let command = source
                .split(&format!("\n{signature}"))
                .nth(1)
                .unwrap()
                .split("#[tauri::command]")
                .next()
                .unwrap();
            let guard = command.find("ensure_stt_configuration_window(window.label())?;");
            assert!(guard.is_some(), "{signature} must check its caller");
            assert!(guard.unwrap() < command.find("if ").unwrap());
        }
    }

    #[test]
    fn resolves_custom_whisper_test_config() {
        let cfg = resolve_whisper_test_config(
            stt::config::CUSTOM_WHISPER_PROVIDER,
            Some("http://localhost:8000/v1".to_string()),
            Some("Systran/faster-whisper-large-v3".to_string()),
        )
        .unwrap();
        assert_eq!(
            cfg.endpoint,
            "http://localhost:8000/v1/audio/transcriptions"
        );
        assert!(!cfg.api_key_required);
    }

    #[test]
    fn custom_whisper_test_config_requires_model() {
        let err = resolve_whisper_test_config(
            stt::config::CUSTOM_WHISPER_PROVIDER,
            Some("http://localhost:8000/v1".to_string()),
            Some(" ".to_string()),
        )
        .unwrap_err();
        assert!(err.contains("Model is required"));
    }

    #[test]
    fn custom_whisper_diagnostics_exposes_local_endpoint() {
        let diagnostics = build_stt_provider_diagnostics(
            stt::config::CUSTOM_WHISPER_PROVIDER,
            "",
            Some("http://localhost:8000/v1"),
            Some("Systran/faster-whisper-large-v3"),
            None,
        );

        assert_eq!(diagnostics.provider, stt::config::CUSTOM_WHISPER_PROVIDER);
        assert_eq!(diagnostics.kind, "localCompatible");
        assert_eq!(
            diagnostics.endpoint.as_deref(),
            Some("http://localhost:8000/v1/audio/transcriptions")
        );
        assert_eq!(
            diagnostics.model.as_deref(),
            Some("Systran/faster-whisper-large-v3")
        );
        assert!(!diagnostics.requires_api_key);
        assert!(diagnostics.ready);
        assert!(diagnostics.issues.is_empty());
    }

    #[test]
    fn custom_whisper_diagnostics_omit_queries_without_changing_request_config() {
        let base_url = "https://example.com/v1?api_key=private-query&api-version=2026-01-01";
        let diagnostics = build_stt_provider_diagnostics(
            stt::config::CUSTOM_WHISPER_PROVIDER,
            "",
            Some(base_url),
            Some("whisper"),
            None,
        );
        assert_eq!(
            diagnostics.endpoint.as_deref(),
            Some("https://example.com/v1/audio/transcriptions")
        );
        assert!(!serde_json::to_string(&diagnostics)
            .unwrap()
            .contains("private-query"));
        let request_config = stt::config::build_custom_whisper_config(base_url, "whisper").unwrap();
        assert!(request_config.endpoint.contains("api_key=private-query"));
        assert!(request_config.endpoint.contains("api-version=2026-01-01"));
        assert!(diagnostics.ready);
    }

    #[test]
    fn custom_whisper_diagnostics_reports_invalid_config() {
        let diagnostics = build_stt_provider_diagnostics(
            stt::config::CUSTOM_WHISPER_PROVIDER,
            "",
            Some("file:///tmp/server"),
            Some(" "),
            None,
        );

        assert_eq!(diagnostics.kind, "localCompatible");
        assert!(!diagnostics.ready);
        assert_eq!(diagnostics.issues.len(), 1);
        assert_eq!(diagnostics.issues[0].code, "invalid_custom_whisper_config");
    }

    #[test]
    fn remote_stt_diagnostics_requires_api_key() {
        let diagnostics = build_stt_provider_diagnostics("deepgram", "", None, None, None);

        assert_eq!(diagnostics.kind, "byokRemote");
        assert!(diagnostics.requires_api_key);
        assert!(!diagnostics.ready);
        assert_eq!(diagnostics.issues.len(), 1);
        assert_eq!(diagnostics.issues[0].code, "missing_api_key");
    }

    #[test]
    fn aliyun_qwen3_diagnostics_exposes_realtime_endpoint() {
        let diagnostics = build_stt_provider_diagnostics(
            "aliyun-qwen3-asr",
            "",
            None,
            None,
            Some(stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_REGION_INTERNATIONAL),
        );

        assert_eq!(diagnostics.kind, "byokRemote");
        assert_eq!(
            diagnostics.endpoint.as_deref(),
            Some(stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_INTERNATIONAL_URL)
        );
        assert_eq!(
            diagnostics.model.as_deref(),
            Some("qwen3-asr-flash-realtime")
        );
        assert_eq!(diagnostics.issues[0].code, "missing_api_key");
    }

    #[test]
    fn apple_speech_diagnostics_are_platform_gated_builtin_local() {
        let diagnostics = build_stt_provider_diagnostics("apple-speech", "", None, None, None);

        assert_eq!(diagnostics.provider, "apple-speech");
        assert_eq!(diagnostics.kind, "builtinLocal");
        assert!(!diagnostics.requires_api_key);
        assert!(!diagnostics.api_key_configured);
        assert_eq!(diagnostics.endpoint, None);
        assert_eq!(diagnostics.model.as_deref(), Some("Apple Speech"));

        #[cfg(target_os = "macos")]
        {
            if diagnostics.ready {
                assert!(diagnostics.issues.is_empty());
            } else {
                assert!(!diagnostics.issues.is_empty());
            }
        }

        #[cfg(not(target_os = "macos"))]
        {
            assert!(!diagnostics.ready);
            assert_eq!(diagnostics.issues[0].code, "unsupported_platform");
        }
    }

    #[test]
    fn apple_speech_diagnostics_reports_authorization_issue() {
        let diagnostics = build_apple_speech_diagnostics(
            "apple-speech",
            stt::apple_speech::AppleSpeechAvailability::from_parts(
                true,
                stt::apple_speech::AppleSpeechAuthorizationStatus::Denied,
                Some("en-US".to_string()),
                None,
            ),
        );

        assert!(!diagnostics.ready);
        assert_eq!(diagnostics.kind, "builtinLocal");
        assert_eq!(diagnostics.issues.len(), 1);
        assert_eq!(diagnostics.issues[0].code, "speech_permission_denied");
    }

    #[test]
    fn managed_cloud_access_requires_active_appsumo_license() {
        let active = serde_json::json!({
            "plan": "appsumo_tier1",
            "source": "appsumo",
            "cloudWordsLimit": 200000,
            "licenseStatus": "active"
        });
        let pending = serde_json::json!({
            "plan": "appsumo_tier1",
            "source": "appsumo",
            "cloudWordsLimit": 200000,
            "licenseStatus": "pending"
        });
        let missing = serde_json::json!({
            "plan": "appsumo_tier1",
            "source": "appsumo",
            "cloudWordsLimit": 200000
        });

        assert!(has_managed_cloud_access(&active));
        assert!(!has_managed_cloud_access(&pending));
        assert!(!has_managed_cloud_access(&missing));
    }

    #[test]
    fn managed_cloud_access_allows_direct_lifetime_license() {
        let lifetime_legacy_quota = serde_json::json!({
            "plan": "lifetime_starter",
            "source": "lifetime",
            "cloudWordsLimit": 0,
            "licenseStatus": "active"
        });
        let lifetime_cloud_words = serde_json::json!({
            "plan": "lifetime_starter",
            "source": "lifetime",
            "cloudWordsLimit": 100000
        });

        assert!(has_managed_cloud_access(&lifetime_legacy_quota));
        assert!(has_managed_cloud_access(&lifetime_cloud_words));
    }
}

#[tauri::command]
// Keep these as explicit IPC fields so older desktop callers remain wire-compatible.
#[allow(clippy::too_many_arguments)]
pub async fn bench_stt_connection<R: tauri::Runtime>(
    window: tauri::Window<R>,
    api_key: String,
    provider: String,
    custom_base_url: Option<String>,
    custom_model: Option<String>,
    volcengine_resource_id: Option<String>,
    provider_region: Option<String>,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<u32, String> {
    ensure_stt_configuration_window(window.label())?;
    if provider.is_empty() {
        return Err("No provider specified".to_string());
    }

    if provider == "cloud" {
        let token = token_store
            .0
            .lock()
            .unwrap_or_else(|e| e.into_inner())
            .clone();
        if token.is_empty() {
            return Err("Not signed in".to_string());
        }
        let api_base = api_base_url();
        let t0 = std::time::Instant::now();
        let resp = with_desktop_client_version(
            client.get(format!("{}/api/subscription/status", api_base)),
        )
        .header("Authorization", format!("Bearer {}", token))
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await
        .map_err(crate::error::request_error_message)?;
        let elapsed = t0.elapsed().as_millis() as u32;
        if !resp.status().is_success() {
            return Err("Request failed".to_string());
        }
        let body = crate::cloud_request::read_json_response_with_limit(resp, 65536)
            .await
            .map_err(|error| error.to_string())?;
        if !has_managed_cloud_access(&body) {
            return Err("Cloud plan required".to_string());
        }
        return Ok(elapsed);
    }

    let api_key = resolve_config_secret(&api_key, "stt", &provider, &SystemCredentialVault)
        .map_err(|e| e.to_string())?;

    if stt::config::stt_provider_requires_api_key(&provider) && api_key.is_empty() {
        return Err("API key is empty".to_string());
    }

    match provider.as_str() {
        "deepgram" => {
            let t0 = std::time::Instant::now();
            let resp = client
                .get("https://api.deepgram.com/v1/projects")
                .header("Authorization", format!("Token {}", api_key))
                .timeout(std::time::Duration::from_secs(10))
                .send()
                .await
                .map_err(crate::error::request_error_message)?;
            let elapsed = t0.elapsed().as_millis() as u32;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            Ok(elapsed)
        }
        "assemblyai" => {
            let t0 = std::time::Instant::now();
            let resp = client
                .get("https://api.assemblyai.com/v2/transcript?limit=1")
                .header("Authorization", api_key)
                .timeout(std::time::Duration::from_secs(10))
                .send()
                .await
                .map_err(crate::error::request_error_message)?;
            let elapsed = t0.elapsed().as_millis() as u32;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            Ok(elapsed)
        }
        stt::volcengine::VOLCENGINE_DOUBAO_PROVIDER => {
            let t0 = std::time::Instant::now();
            check_volcengine_doubao_connection(&api_key, volcengine_resource_id).await?;
            Ok(t0.elapsed().as_millis() as u32)
        }
        stt::aliyun_qwen3_asr::ALIYUN_QWEN3_ASR_PROVIDER => {
            let t0 = std::time::Instant::now();
            check_aliyun_qwen3_connection(&api_key, provider_region).await?;
            Ok(t0.elapsed().as_millis() as u32)
        }
        stt::config::APPLE_SPEECH_PROVIDER => {
            if stt::apple_speech::is_available_on_current_platform() {
                Ok(0)
            } else {
                Err("Apple Speech is only available on macOS".to_string())
            }
        }
        "openai-whisper" => {
            let t0 = std::time::Instant::now();
            check_openai_whisper_model(&client, &api_key).await?;
            Ok(t0.elapsed().as_millis() as u32)
        }
        _ => {
            let cfg = resolve_whisper_test_config(&provider, custom_base_url, custom_model)?;

            let silent_pcm = vec![0u8; 3200]; // 0.1s at 16kHz 16-bit mono
            let wav = stt::whisper_compat::WhisperCompatProvider::build_wav(&silent_pcm, 16000);

            let file_part = reqwest::multipart::Part::bytes(wav)
                .file_name("test.wav")
                .mime_str("audio/wav")
                .map_err(|e| e.to_string())?;
            let mut form = reqwest::multipart::Form::new()
                .text("model", cfg.model.clone())
                .part("file", file_part);
            for (key, value) in &cfg.extra_fields {
                form = form.text(key.clone(), value.clone());
            }

            let t0 = std::time::Instant::now();
            let mut request = client
                .post(&cfg.endpoint)
                .multipart(form)
                .timeout(std::time::Duration::from_secs(15));

            if !api_key.trim().is_empty() {
                request = request.header("Authorization", format!("Bearer {}", api_key));
            }

            let resp = request
                .send()
                .await
                .map_err(crate::error::request_error_message)?;
            let elapsed = t0.elapsed().as_millis() as u32;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            Ok(elapsed)
        }
    }
}
