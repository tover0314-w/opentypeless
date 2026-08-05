use crate::api_base_url;
use crate::stt;
use crate::SessionTokenStore;

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

#[tauri::command]
pub async fn test_stt_connection(
    api_key: String,
    provider: String,
    custom_base_url: Option<String>,
    custom_model: Option<String>,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<bool, String> {
    if provider.is_empty() {
        return Ok(false);
    }

    // Cloud provider: verify session token + Pro status via API
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
        let resp = client
            .get(format!("{}/api/subscription/status", api_base))
            .header("Authorization", format!("Bearer {}", token))
            .timeout(std::time::Duration::from_secs(10))
            .send()
            .await
            .map_err(|e| e.to_string())?;
        if !resp.status().is_success() {
            return Ok(false);
        }
        let body: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
        return Ok(body["plan"].as_str() == Some("pro"));
    }

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
                .map_err(|e| e.to_string())?;
            Ok(resp.status().is_success())
        }
        "assemblyai" => {
            let resp = client
                .get("https://api.assemblyai.com/v2/transcript?limit=1")
                .header("Authorization", api_key)
                .timeout(std::time::Duration::from_secs(10))
                .send()
                .await
                .map_err(|e| e.to_string())?;
            Ok(resp.status().is_success())
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

            let mut request = client
                .post(&cfg.endpoint)
                .multipart(form)
                .timeout(std::time::Duration::from_secs(15));

            if !api_key.trim().is_empty() {
                request = request.header("Authorization", format!("Bearer {}", api_key));
            }

            let resp = request.send().await.map_err(|e| e.to_string())?;
            Ok(resp.status().is_success())
        }
    }
}

/// Strip a trailing `/v1` (and slashes) from a Whisper base URL to get the
/// server root, e.g. `http://localhost:8000/v1` -> `http://localhost:8000`.
fn local_server_root(base_url: &str) -> String {
    base_url
        .trim()
        .trim_end_matches('/')
        .trim_end_matches("/v1")
        .trim_end_matches('/')
        .to_string()
}

/// Query the local STT server's model/GPU status (GET /health).
/// Only meaningful for the local `custom-whisper` server; returns its JSON.
#[tauri::command]
pub async fn local_stt_status(
    base_url: String,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<serde_json::Value, String> {
    let root = local_server_root(&base_url);
    let resp = client
        .get(format!("{}/health", root))
        .timeout(std::time::Duration::from_secs(5))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    resp.json::<serde_json::Value>()
        .await
        .map_err(|e| e.to_string())
}

/// Load the model into VRAM on the local STT server (POST /v1/local/load).
#[tauri::command]
pub async fn local_stt_load(
    base_url: String,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<serde_json::Value, String> {
    let root = local_server_root(&base_url);
    let resp = client
        .post(format!("{}/v1/local/load", root))
        .timeout(std::time::Duration::from_secs(180))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    resp.json::<serde_json::Value>()
        .await
        .map_err(|e| e.to_string())
}

/// Free the model from VRAM on the local STT server (POST /v1/local/unload).
#[tauri::command]
pub async fn local_stt_unload(
    base_url: String,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<serde_json::Value, String> {
    let root = local_server_root(&base_url);
    let resp = client
        .post(format!("{}/v1/local/unload", root))
        .timeout(std::time::Duration::from_secs(30))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    resp.json::<serde_json::Value>()
        .await
        .map_err(|e| e.to_string())
}

/// Pin the local STT server to a device (POST /v1/local/device).
///
/// `mode` is `auto` (GPU with CPU fallback on VRAM exhaustion), `gpu` (GPU
/// only), or `cpu` (CPU only — keeps dictation working while a game owns the
/// GPU). Pinning to `cpu` also frees the model from VRAM server-side.
#[tauri::command]
pub async fn local_stt_set_device(
    base_url: String,
    mode: String,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<serde_json::Value, String> {
    let root = local_server_root(&base_url);
    let form = reqwest::multipart::Form::new().text("mode", mode);
    let resp = client
        .post(format!("{}/v1/local/device", root))
        .multipart(form)
        .timeout(std::time::Duration::from_secs(30))
        .send()
        .await
        .map_err(|e| e.to_string())?;
    resp.json::<serde_json::Value>()
        .await
        .map_err(|e| e.to_string())
}

#[cfg(test)]
mod tests {
    use super::*;

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
}

#[tauri::command]
pub async fn bench_stt_connection(
    api_key: String,
    provider: String,
    custom_base_url: Option<String>,
    custom_model: Option<String>,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<u32, String> {
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
        let resp = client
            .get(format!("{}/api/subscription/status", api_base))
            .header("Authorization", format!("Bearer {}", token))
            .timeout(std::time::Duration::from_secs(10))
            .send()
            .await
            .map_err(|e| e.to_string())?;
        let elapsed = t0.elapsed().as_millis() as u32;
        if !resp.status().is_success() {
            return Err("Request failed".to_string());
        }
        let body: serde_json::Value = resp.json().await.map_err(|e| e.to_string())?;
        if body["plan"].as_str() != Some("pro") {
            return Err("Pro plan required".to_string());
        }
        return Ok(elapsed);
    }

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
                .map_err(|e| e.to_string())?;
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
                .map_err(|e| e.to_string())?;
            let elapsed = t0.elapsed().as_millis() as u32;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            Ok(elapsed)
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

            let resp = request.send().await.map_err(|e| e.to_string())?;
            let elapsed = t0.elapsed().as_millis() as u32;
            if !resp.status().is_success() {
                return Err(format!("HTTP {}", resp.status()));
            }
            Ok(elapsed)
        }
    }
}
