use crate::credentials::{resolve_config_secret, SystemCredentialVault};
use crate::SessionTokenStore;
use crate::{api_base_url, with_desktop_client_version};

fn ensure_model_configuration_window(label: &str) -> Result<(), String> {
    if label == "main" {
        Ok(())
    } else {
        Err("Model configuration requests are only allowed from the main window".to_string())
    }
}

#[tauri::command]
pub fn get_llm_model_capability(
    provider: String,
    base_url: String,
    model: String,
) -> crate::llm::model_capabilities::ModelCapability {
    crate::llm::model_capabilities::model_capability(
        &provider,
        &base_url,
        &model,
        crate::llm::prompt::CONTEXT_PROMPT_VERSION,
    )
}

fn synthetic_operation_id() -> String {
    let now = std::time::SystemTime::now()
        .duration_since(std::time::UNIX_EPOCH)
        .unwrap_or_default()
        .as_nanos();

    format!(
        "{:08x}-{:04x}-{:04x}-{:04x}-{:012x}",
        (now >> 96) as u32,
        (now >> 80) as u16,
        (now >> 64) as u16,
        (now >> 48) as u16,
        now & 0x0000_ffff_ffff_ffff_ffffu128
    )
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

#[tauri::command]
pub async fn test_llm_connection<R: tauri::Runtime>(
    window: tauri::Window<R>,
    api_key: String,
    provider: String,
    base_url: String,
    model: String,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<bool, String> {
    ensure_model_configuration_window(window.label())?;
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

    let api_key = resolve_config_secret(&api_key, "llm", &provider, &SystemCredentialVault)
        .map_err(|e| e.to_string())?;

    if base_url.is_empty() || !crate::llm::has_usable_provider_credentials(&provider, &api_key) {
        return Ok(false);
    }

    // Validate base_url is a proper HTTP(S) URL
    let parsed = url::Url::parse(&base_url).map_err(|e| format!("Invalid base URL: {e}"))?;
    if parsed.scheme() != "https" && parsed.scheme() != "http" {
        return Err("Base URL must use http or https scheme".to_string());
    }

    let url = crate::llm::protocol::chat_endpoint(&provider, &base_url)?;
    let body = crate::llm::protocol::build_chat_body(
        &provider,
        &base_url,
        &model,
        vec![serde_json::json!({"role": "user", "content": "hi"})],
        1,
        0.3,
        false,
    );

    let request = client.post(&url).header("Content-Type", "application/json");
    let resp = crate::llm::protocol::apply_auth_headers(request, &provider, &base_url, &api_key)
        .json(&body)
        .timeout(crate::llm::protocol::request_timeout(
            &provider, &base_url, &model,
        ))
        .send()
        .await
        .map_err(crate::error::request_error_message)?;

    if !resp.status().is_success() {
        return Ok(false);
    }
    validate_model_probe_response(resp).await?;
    Ok(true)
}

async fn validate_model_probe_response(response: reqwest::Response) -> Result<(), String> {
    let bytes = crate::cloud_request::read_response_with_limit(response, 65536)
        .await
        .map_err(|error| error.to_string())?;
    let body: serde_json::Value =
        serde_json::from_slice(&bytes).map_err(|_| "Invalid model probe response".to_string())?;
    if body.get("error").is_some_and(|error| !error.is_null()) {
        return Err("Model probe returned an error response".to_string());
    }
    let compatible = body["choices"]
        .as_array()
        .and_then(|choices| choices.first())
        .is_some_and(|choice| {
            let message = &choice["message"];
            message["content"].is_string()
                || message["reasoning_content"].is_string()
                || message["tool_calls"]
                    .as_array()
                    .is_some_and(|calls| !calls.is_empty())
        });
    let anthropic = body["content"].as_array().is_some_and(|content| {
        content
            .iter()
            .any(|block| block["type"] == "text" && block["text"].is_string())
    });
    if !compatible && !anthropic {
        return Err("Invalid model probe response".to_string());
    }
    Ok(())
}

fn build_fetch_models_request(
    client: &reqwest::Client,
    provider: &str,
    base_url: &str,
    api_key: &str,
    url: &str,
) -> reqwest::RequestBuilder {
    crate::llm::protocol::apply_auth_headers(client.get(url), provider, base_url, api_key)
}

#[tauri::command]
pub async fn fetch_llm_models<R: tauri::Runtime>(
    window: tauri::Window<R>,
    api_key: String,
    provider: String,
    base_url: String,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<Vec<String>, String> {
    ensure_model_configuration_window(window.label())?;
    if base_url.is_empty() {
        return Ok(vec![]);
    }
    if !crate::llm::has_usable_provider_credentials(&provider, &api_key) {
        return Ok(vec![]);
    }

    // Validate base_url is a proper HTTP(S) URL
    let parsed = url::Url::parse(&base_url).map_err(|e| format!("Invalid base URL: {e}"))?;
    if parsed.scheme() != "https" && parsed.scheme() != "http" {
        return Err("Base URL must use http or https scheme".to_string());
    }

    let url = crate::llm::protocol::models_endpoint(&provider, &base_url)?;

    let resp = build_fetch_models_request(&client, &provider, &base_url, &api_key, &url)
        .timeout(std::time::Duration::from_secs(10))
        .send()
        .await
        .map_err(crate::error::request_error_message)?;

    if !resp.status().is_success() {
        return Err(format!("HTTP {}", resp.status()));
    }

    models_from_response(resp).await
}

async fn models_from_response(response: reqwest::Response) -> Result<Vec<String>, String> {
    let bytes = crate::cloud_request::read_response_with_limit(response, 8 * 1024 * 1024)
        .await
        .map_err(|error| error.to_string())?;
    let body: serde_json::Value =
        serde_json::from_slice(&bytes).map_err(|error| error.to_string())?;
    if body.get("error").is_some_and(|error| !error.is_null()) {
        return Err("Invalid model list response".to_string());
    }

    // OpenAI-compatible: { data: [{ id: "model-name" }] }
    // Ollama-compatible: { models: [{ name: "model-name" }] }
    let mut models: Vec<String> = Vec::new();

    let received_entries;
    if let Some(data) = body.get("data").and_then(|d| d.as_array()) {
        received_entries = data.len();
        for item in data {
            if let Some(id) = item
                .get("id")
                .and_then(|value| value.as_str())
                .filter(|value| !value.trim().is_empty())
            {
                models.push(id.to_string());
            }
        }
    } else if let Some(data) = body.get("models").and_then(|d| d.as_array()) {
        received_entries = data.len();
        for item in data {
            if let Some(name) = item
                .get("name")
                .and_then(|value| value.as_str())
                .filter(|value| !value.trim().is_empty())
            {
                models.push(name.to_string());
            }
        }
    } else {
        return Err("Invalid model list response".to_string());
    }

    if received_entries > 0 && models.is_empty() {
        return Err("Invalid model list response".to_string());
    }
    models.sort();
    Ok(models)
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn cloud_benchmark_validates_the_response_before_reporting_success() {
        let source = include_str!("llm.rs");
        let command = source
            .split("\npub async fn bench_llm_connection<")
            .nth(1)
            .unwrap();
        let cloud = command
            .split("if provider == \"cloud\" {")
            .nth(1)
            .unwrap()
            .split("let api_key = resolve_config_secret(")
            .next()
            .unwrap();
        let validation = cloud.find("validate_model_probe_response(resp).await?;");
        assert!(
            validation.is_some(),
            "HTTP success alone cannot validate a cloud probe"
        );
        assert!(validation.unwrap() < cloud.find("return Ok(elapsed)").unwrap());
    }

    #[tokio::test]
    async fn model_probe_rejects_error_envelopes_without_echoing_details() {
        for body in [
            r#"{"error":{"message":"synthetic-private-key"}}"#,
            r#"{"error":"synthetic-private-key","choices":[]}"#,
        ] {
            let response = benchmark_error_response("", body).await;
            assert_eq!(
                validate_model_probe_response(response).await.unwrap_err(),
                "Model probe returned an error response"
            );
        }
        let response = benchmark_error_response("", "invalid-json").await;
        assert_eq!(
            validate_model_probe_response(response).await.unwrap_err(),
            "Invalid model probe response"
        );
    }

    #[tokio::test]
    async fn model_probe_rejects_unrecognized_success_shapes() {
        for body in [
            "null",
            "[]",
            r#"{"status":"ok"}"#,
            r#"{"choices":[]}"#,
            r#"{"choices":[{"message":null}]}"#,
            r#"{"choices":[{"message":{}}]}"#,
            r#"{"choices":[{"message":{"content":42}}]}"#,
            r#"{"choices":[{"message":{"content":{"text":"invalid"}}}}]}"#,
            r#"{"content":[]}"#,
            r#"{"choices":"invalid","content":"invalid"}"#,
        ] {
            let response = benchmark_error_response("", body).await;
            assert_eq!(
                validate_model_probe_response(response).await.unwrap_err(),
                "Invalid model probe response"
            );
        }
    }

    #[tokio::test]
    async fn model_probe_bounds_declared_and_unannounced_response_bodies() {
        let response = benchmark_error_response("Content-Length: 65537", "").await;
        assert_eq!(
            validate_model_probe_response(response).await.unwrap_err(),
            "Config error: Service response too large"
        );
        let response = benchmark_error_response("", &"x".repeat(65537)).await;
        assert_eq!(
            validate_model_probe_response(response).await.unwrap_err(),
            "Config error: Service response too large"
        );
        let mut body = r#"{"choices":[{"message":{"content":"Hi"}}]}"#.to_string();
        body.push_str(&" ".repeat(65536 - body.len()));
        for headers in ["", "Content-Length: 65536"] {
            let response = benchmark_error_response(headers, &body).await;
            validate_model_probe_response(response).await.unwrap();
        }
    }

    #[tokio::test]
    async fn model_probe_preserves_token_limited_success_and_null_errors() {
        for body in [
            r#"{"choices":[{"message":{"content":"Hi"},"finish_reason":"length"}]}"#,
            r#"{"content":[{"type":"text","text":"Hi"}],"stop_reason":"max_tokens"}"#,
            r#"{"error":null,"choices":[{"message":{"content":""}}]}"#,
            r#"{"choices":[{"message":{"reasoning_content":"Synthetic reasoning"}}]}"#,
            r#"{"choices":[{"message":{"content":null,"tool_calls":[{"id":"synthetic-call","type":"function","function":{"name":"synthetic","arguments":"{}"}}]}}]}"#,
        ] {
            let response = benchmark_error_response("", body).await;
            validate_model_probe_response(response).await.unwrap();
        }
    }

    #[test]
    fn main_model_list_ipc_returns_local_service_models() {
        use std::io::{Read, Write};
        for (command, provider, api_key, status, payload) in [
            (
                "fetch_llm_models",
                "openai",
                "synthetic-key",
                "200 OK",
                r#"{"data":[{"id":"zeta"},{"id":"alpha"}]}"#,
            ),
            (
                "fetch_llm_models",
                "ollama",
                "",
                "200 OK",
                r#"{"models":[{"name":"zeta"},{"name":"alpha"}]}"#,
            ),
            (
                "fetch_llm_models",
                "openai",
                "synthetic-key",
                "401 Unauthorized",
                "synthetic-key private error",
            ),
            (
                "fetch_llm_models",
                "ollama",
                "",
                "500 Internal Server Error",
                "private error",
            ),
            (
                "test_llm_connection",
                "openai",
                "synthetic-key",
                "200 OK",
                r#"{"choices":[{"message":{"content":"Hi"},"finish_reason":"length"}]}"#,
            ),
            (
                "bench_llm_connection",
                "ollama",
                "",
                "200 OK",
                r#"{"choices":[{"message":{"content":"Hi"},"finish_reason":"length"}]}"#,
            ),
            (
                "test_llm_connection",
                "openai",
                "synthetic-key",
                "200 OK",
                r#"{"error":{"message":"synthetic-key private error"}}"#,
            ),
            (
                "bench_llm_connection",
                "ollama",
                "",
                "200 OK",
                r#"{"error":{"message":"private error"}}"#,
            ),
        ] {
            let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
            listener.set_nonblocking(true).unwrap();
            let address = listener.local_addr().unwrap();
            let server = std::thread::spawn(move || {
                let deadline = std::time::Instant::now() + std::time::Duration::from_secs(5);
                let mut socket = loop {
                    match listener.accept() {
                        Ok((socket, _)) => break socket,
                        Err(error) if error.kind() == std::io::ErrorKind::WouldBlock => {
                            assert!(std::time::Instant::now() < deadline);
                            std::thread::sleep(std::time::Duration::from_millis(10));
                        }
                        Err(error) => panic!("Synthetic server accept failed: {error}"),
                    }
                };
                socket
                    .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                socket
                    .set_write_timeout(Some(std::time::Duration::from_secs(5)))
                    .unwrap();
                let mut request = Vec::new();
                let mut buffer = [0_u8; 1024];
                loop {
                    if let Some(boundary) = request.windows(4).position(|part| part == b"\r\n\r\n")
                    {
                        let headers = String::from_utf8_lossy(&request[..boundary]);
                        let length = headers
                            .lines()
                            .find_map(|line| {
                                let (name, value) = line.split_once(':')?;
                                name.eq_ignore_ascii_case("content-length")
                                    .then(|| value.trim().parse::<usize>().unwrap())
                            })
                            .unwrap_or(0);
                        if request.len() >= boundary + 4 + length {
                            break;
                        }
                    }
                    let count = socket.read(&mut buffer).unwrap();
                    assert!(count > 0);
                    request.extend_from_slice(&buffer[..count]);
                    assert!(request.len() <= 16384);
                }
                write!(
                    socket,
                    "HTTP/1.1 {status}\r\nContent-Type: application/json\r\nContent-Length: {}\r\nConnection: close\r\n\r\n{payload}",
                    payload.len()
                )
                .unwrap();
                String::from_utf8(request).unwrap()
            });
            let app = tauri::test::mock_builder()
                .manage(reqwest::Client::builder().no_proxy().build().unwrap())
                .manage(SessionTokenStore(
                    std::sync::Arc::new(std::sync::Mutex::new(String::new())),
                    std::sync::atomic::AtomicBool::new(false),
                    std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
                ))
                .invoke_handler(tauri::generate_handler![
                    fetch_llm_models,
                    test_llm_connection,
                    bench_llm_connection
                ])
                .build(tauri::test::mock_context(tauri::test::noop_assets()))
                .unwrap();
            let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
                .build()
                .unwrap();
            let response = tauri::test::get_ipc_response(
                &webview,
                tauri::webview::InvokeRequest {
                    cmd: command.to_string(),
                    callback: tauri::ipc::CallbackFn(0),
                    error: tauri::ipc::CallbackFn(1),
                    url: "tauri://localhost".parse().unwrap(),
                    body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                        "apiKey": api_key,
                        "provider": provider,
                        "baseUrl": format!("http://{address}/v1"),
                        "model": "synthetic-model"
                    })),
                    headers: Default::default(),
                    invoke_key: tauri::test::INVOKE_KEY.to_string(),
                },
            );
            let request = server.join().unwrap();
            if payload.contains("\"error\"") {
                assert_eq!(
                    response.unwrap_err(),
                    serde_json::json!("Model probe returned an error response")
                );
            } else if command == "test_llm_connection" {
                assert!(response.unwrap().deserialize::<bool>().unwrap());
            } else if command == "bench_llm_connection" {
                response.unwrap().deserialize::<u32>().unwrap();
            } else if status == "200 OK" {
                assert_eq!(
                    response.unwrap().deserialize::<Vec<String>>().unwrap(),
                    vec!["alpha".to_string(), "zeta".to_string()]
                );
            } else {
                assert_eq!(
                    response.unwrap_err(),
                    serde_json::json!(format!("HTTP {status}"))
                );
            }
            if command == "fetch_llm_models" {
                assert!(request.starts_with("GET /v1/models "));
            } else {
                assert!(request.starts_with("POST /v1/chat/completions "));
                let body: serde_json::Value =
                    serde_json::from_str(request.split("\r\n\r\n").nth(1).unwrap()).unwrap();
                assert_eq!(body["model"], "synthetic-model");
                assert_eq!(body["max_tokens"], 1);
                assert_eq!(body["stream"], false);
            }
            let authorization = request
                .lines()
                .find(|line| line.to_lowercase().starts_with("authorization:"));
            if api_key.is_empty() {
                assert!(authorization.is_none());
            } else {
                assert_eq!(authorization, Some("authorization: Bearer synthetic-key"));
            }
        }
    }

    #[test]
    fn main_model_configuration_ipc_rejects_non_http_urls_without_provider_requests() {
        let app = tauri::test::mock_builder()
            .manage(SessionTokenStore(
                std::sync::Arc::new(std::sync::Mutex::new(String::new())),
                std::sync::atomic::AtomicBool::new(false),
                std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .manage(reqwest::Client::new())
            .invoke_handler(tauri::generate_handler![
                test_llm_connection,
                fetch_llm_models,
                bench_llm_connection
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        let webview = tauri::WebviewWindowBuilder::new(&app, "main", Default::default())
            .build()
            .unwrap();
        for command in [
            "test_llm_connection",
            "fetch_llm_models",
            "bench_llm_connection",
        ] {
            for base_url in ["file:///synthetic-model", "ftp://example.test/models"] {
                let response = tauri::test::get_ipc_response(
                    &webview,
                    tauri::webview::InvokeRequest {
                        cmd: command.to_string(),
                        callback: tauri::ipc::CallbackFn(0),
                        error: tauri::ipc::CallbackFn(1),
                        url: "tauri://localhost".parse().unwrap(),
                        body: tauri::ipc::InvokeBody::Json(serde_json::json!({
                            "apiKey": "synthetic-key",
                            "provider": "openai",
                            "baseUrl": base_url,
                            "model": "synthetic-model"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                assert_eq!(
                    response.unwrap_err(),
                    serde_json::json!("Base URL must use http or https scheme")
                );
            }
        }
    }

    #[test]
    fn auxiliary_model_configuration_ipc_is_denied_before_network_or_vault_access() {
        let app = tauri::test::mock_builder()
            .manage(SessionTokenStore(
                std::sync::Arc::new(std::sync::Mutex::new("synthetic-session-token".to_string())),
                std::sync::atomic::AtomicBool::new(false),
                std::sync::Mutex::new(uuid::Uuid::new_v4().to_string()),
            ))
            .manage(reqwest::Client::new())
            .invoke_handler(tauri::generate_handler![
                test_llm_connection,
                fetch_llm_models,
                bench_llm_connection
            ])
            .build(tauri::test::mock_context(tauri::test::noop_assets()))
            .unwrap();
        for label in ["capsule", "ask"] {
            let webview = tauri::WebviewWindowBuilder::new(&app, label, Default::default())
                .build()
                .unwrap();
            for command in [
                "test_llm_connection",
                "fetch_llm_models",
                "bench_llm_connection",
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
                            "provider": "cloud",
                            "baseUrl": "https://invalid.example.test/v1",
                            "model": "synthetic-model"
                        })),
                        headers: Default::default(),
                        invoke_key: tauri::test::INVOKE_KEY.to_string(),
                    },
                );
                assert_eq!(
                    response.unwrap_err(),
                    serde_json::json!(
                        "Model configuration requests are only allowed from the main window"
                    )
                );
            }
        }
    }

    #[test]
    fn provider_requests_check_main_window_before_credentials_or_network() {
        let source = include_str!("llm.rs");
        for signature in [
            "pub async fn test_llm_connection<",
            "pub async fn fetch_llm_models<",
            "pub async fn bench_llm_connection<",
        ] {
            let command = source
                .split(&format!("\n{signature}"))
                .nth(1)
                .unwrap()
                .split("#[tauri::command]")
                .next()
                .unwrap();
            let guard = command.find("ensure_model_configuration_window(window.label())?;");
            assert!(guard.is_some(), "{signature} must check the caller");
            assert!(guard.unwrap() < command.find("if ").unwrap());
        }
    }

    #[tokio::test]
    async fn model_list_response_preserves_supported_formats_and_sorting() {
        for body in [
            r#"{"data":[{"id":"zeta"},{"id":"alpha"},{"missing":"ignored"}]}"#,
            r#"{"models":[{"name":"zeta"},{"name":"alpha"},{"missing":"ignored"}]}"#,
        ] {
            let response = benchmark_error_response("", body).await;
            assert_eq!(
                models_from_response(response).await.unwrap(),
                vec!["alpha".to_string(), "zeta".to_string()]
            );
        }
    }

    #[tokio::test]
    async fn model_list_response_rejects_nonempty_lists_without_usable_names() {
        for body in [
            r#"{"data":[{"missing":"id"},{"id":""},{"id":" \t"}]}"#,
            r#"{"models":[null,{"name":42},{"name":"\n"}]}"#,
        ] {
            let response = benchmark_error_response("", body).await;
            assert_eq!(
                models_from_response(response).await.unwrap_err(),
                "Invalid model list response"
            );
        }
    }

    #[tokio::test]
    async fn model_list_response_preserves_valid_names_among_blank_entries() {
        for body in [
            r#"{"data":[{"id":""},{"id":" "},{"id":" valid-model "}]}"#,
            r#"{"models":[{"name":""},{"name":" "},{"name":" valid-model "}]}"#,
        ] {
            let response = benchmark_error_response("", body).await;
            assert_eq!(
                models_from_response(response).await.unwrap(),
                vec![" valid-model ".to_string()]
            );
        }
    }

    #[tokio::test]
    async fn model_list_response_rejects_error_envelopes_and_unknown_shapes() {
        for body in [
            r#"{"error":{"message":"synthetic-private-key"},"data":[{"id":"ignored"}]}"#,
            r#"{"error":"synthetic-private-key","models":[]}"#,
            r#"{"message":"unexpected"}"#,
            r#"{"data":{},"models":null}"#,
            "null",
            "[]",
        ] {
            let response = benchmark_error_response("", body).await;
            assert_eq!(
                models_from_response(response).await.unwrap_err(),
                "Invalid model list response"
            );
        }
        for body in [r#"{"error":null,"data":[]}"#, r#"{"models":[]}"#] {
            let response = benchmark_error_response("", body).await;
            assert!(models_from_response(response).await.unwrap().is_empty());
        }
    }

    #[tokio::test]
    async fn model_list_response_rejects_an_oversized_body_before_json_parsing() {
        let response = benchmark_error_response("Content-Length: 8388609", "").await;
        let error = models_from_response(response).await.unwrap_err();
        assert!(error.contains("Service response too large"), "{error}");
    }

    #[tokio::test]
    async fn model_list_response_bounds_a_body_without_a_declared_length() {
        let response = benchmark_error_response("", &"x".repeat(8 * 1024 * 1024 + 1)).await;
        let error = models_from_response(response).await.unwrap_err();
        assert!(error.contains("Service response too large"), "{error}");
    }

    #[tokio::test]
    async fn model_list_response_rejects_malformed_json_instead_of_returning_no_models() {
        let response = benchmark_error_response("", r#"{"data":["#).await;
        assert!(models_from_response(response).await.is_err());
        let response = benchmark_error_response("", r#"{"data":[]}"#).await;
        assert!(models_from_response(response).await.unwrap().is_empty());
    }

    async fn benchmark_error_response(headers: &str, body: &str) -> reqwest::Response {
        use std::io::{Read, Write};
        let listener = std::net::TcpListener::bind("127.0.0.1:0").unwrap();
        let address = listener.local_addr().unwrap();
        let headers = if headers.is_empty() {
            String::new()
        } else {
            format!("{headers}\r\n")
        };
        let response =
            format!("HTTP/1.1 400 Bad Request\r\n{headers}Connection: close\r\n\r\n{body}");
        std::thread::spawn(move || {
            let (mut socket, _) = listener.accept().unwrap();
            socket
                .set_read_timeout(Some(std::time::Duration::from_secs(5)))
                .unwrap();
            let mut request = [0_u8; 4096];
            assert!(socket.read(&mut request).unwrap() > 0);
            let _ = socket.write_all(response.as_bytes());
        });
        reqwest::Client::builder()
            .no_proxy()
            .timeout(std::time::Duration::from_secs(5))
            .build()
            .unwrap()
            .get(format!("http://{address}"))
            .send()
            .await
            .unwrap()
    }

    #[tokio::test]
    async fn benchmark_error_preserves_short_details_and_unicode_display_limit() {
        let response = benchmark_error_response("", "invalid model").await;
        assert_eq!(
            benchmark_error_message(response, "").await,
            "HTTP 400 Bad Request: invalid model"
        );
        let body = "界".repeat(201);
        let response = benchmark_error_response("", &body).await;
        assert_eq!(
            benchmark_error_message(response, "").await,
            format!("HTTP 400 Bad Request: {}", "界".repeat(200))
        );
    }

    #[tokio::test]
    async fn benchmark_error_preserves_details_at_the_read_limit() {
        let body = "x".repeat(65536);
        for headers in ["", "Content-Length: 65536"] {
            let response = benchmark_error_response(headers, &body).await;
            assert_eq!(
                benchmark_error_message(response, "").await,
                format!("HTTP 400 Bad Request: {}", "x".repeat(200))
            );
        }
    }

    #[tokio::test]
    async fn benchmark_error_does_not_read_an_oversized_declared_body() {
        let response = benchmark_error_response("Content-Length: 65537", "").await;
        assert_eq!(
            benchmark_error_message(response, "").await,
            "HTTP 400 Bad Request"
        );
    }

    #[tokio::test]
    async fn benchmark_error_redacts_known_keys_before_display_truncation() {
        let secret = "sk-private-diagnostic-key";
        let body = format!("{}Bearer {secret}; repeated {secret}", "x".repeat(190));
        let response = benchmark_error_response("", &body).await;
        let message = benchmark_error_message(response, secret).await;
        assert!(!message.contains("sk-"));
        let response =
            benchmark_error_response("", &format!("invalid {secret}; repeated {secret}")).await;
        assert_eq!(
            benchmark_error_message(response, secret).await,
            "HTTP 400 Bad Request: invalid [redacted]; repeated [redacted]"
        );
    }

    #[tokio::test]
    async fn benchmark_error_bounds_a_body_without_a_content_length() {
        let response = benchmark_error_response("", &"x".repeat(65537)).await;
        assert_eq!(
            benchmark_error_message(response, "").await,
            "HTTP 400 Bad Request"
        );
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

    #[test]
    fn model_request_omits_authorization_for_keyless_ollama() {
        let request = build_fetch_models_request(
            &reqwest::Client::new(),
            "ollama",
            "http://localhost:11434/v1",
            "",
            "http://localhost:11434/v1/models",
        )
        .build()
        .unwrap();

        assert!(request.headers().get("Authorization").is_none());
    }

    #[test]
    fn model_request_keeps_authorization_for_keyed_providers() {
        let request = build_fetch_models_request(
            &reqwest::Client::new(),
            "openai",
            "https://api.openai.com/v1",
            "sk-test",
            "https://api.openai.com/v1/models",
        )
        .build()
        .unwrap();

        assert_eq!(
            request.headers().get("Authorization").unwrap(),
            "Bearer sk-test"
        );
    }
}

#[tauri::command]
pub async fn bench_llm_connection<R: tauri::Runtime>(
    window: tauri::Window<R>,
    api_key: String,
    provider: String,
    base_url: String,
    model: String,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<u32, String> {
    ensure_model_configuration_window(window.label())?;
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
        let operation_id = synthetic_operation_id();
        let body = serde_json::json!({
            "messages": [{"role": "user", "content": "hi"}],
            "stream": false,
            "context": {
                "operationId": operation_id.clone(),
                "stageKey": format!("{operation_id}:llm"),
                "requestType": "connection_benchmark",
                "clientVersion": crate::desktop_client_version(),
                "rawTextChars": 2,
                "selectedTextChars": 0,
                "hasSelectedText": false,
                "translateEnabled": false
            }
        });
        let t0 = std::time::Instant::now();
        let resp = with_desktop_client_version(client.post(format!("{}/api/proxy/llm", api_base)))
            .header("Authorization", format!("Bearer {}", token))
            .header("Content-Type", "application/json")
            .json(&body)
            .timeout(std::time::Duration::from_secs(30))
            .send()
            .await
            .map_err(crate::error::request_error_message)?;
        let elapsed = t0.elapsed().as_millis() as u32;
        if !resp.status().is_success() {
            return Err(format!("HTTP {}", resp.status()));
        }
        validate_model_probe_response(resp).await?;
        return Ok(elapsed);
    }

    let api_key = resolve_config_secret(&api_key, "llm", &provider, &SystemCredentialVault)
        .map_err(|e| e.to_string())?;

    if base_url.is_empty() || !crate::llm::has_usable_provider_credentials(&provider, &api_key) {
        return Err("API key or base URL is empty".to_string());
    }

    let parsed = url::Url::parse(&base_url).map_err(|e| format!("Invalid base URL: {e}"))?;
    if parsed.scheme() != "https" && parsed.scheme() != "http" {
        return Err("Base URL must use http or https scheme".to_string());
    }

    let url = crate::llm::protocol::chat_endpoint(&provider, &base_url)?;
    let body = crate::llm::protocol::build_chat_body(
        &provider,
        &base_url,
        &model,
        vec![serde_json::json!({"role": "user", "content": "hi"})],
        1,
        0.3,
        false,
    );

    let t0 = std::time::Instant::now();
    let request = client.post(&url).header("Content-Type", "application/json");
    let resp = crate::llm::protocol::apply_auth_headers(request, &provider, &base_url, &api_key)
        .json(&body)
        .timeout(crate::llm::protocol::request_timeout(
            &provider, &base_url, &model,
        ))
        .send()
        .await
        .map_err(crate::error::request_error_message)?;
    let elapsed = t0.elapsed().as_millis() as u32;

    if !resp.status().is_success() {
        return Err(benchmark_error_message(resp, &api_key).await);
    }

    validate_model_probe_response(resp).await?;
    Ok(elapsed)
}

async fn benchmark_error_message(response: reqwest::Response, api_key: &str) -> String {
    let status = response.status();
    let body = crate::cloud_request::read_response_with_limit(response, 65536)
        .await
        .unwrap_or_default();
    let text = String::from_utf8_lossy(&body);
    let redacted = if api_key.is_empty() {
        text.into_owned()
    } else {
        text.replace(api_key, "[redacted]")
    };
    let details: String = redacted.chars().take(200).collect();
    if details.is_empty() {
        format!("HTTP {status}")
    } else {
        format!("HTTP {status}: {details}")
    }
}
