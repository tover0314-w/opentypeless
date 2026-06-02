# Custom Local STT Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add a Settings-only Local / Custom Whisper STT provider that can call Speaches or any OpenAI-compatible `/audio/transcriptions` server.

**Architecture:** Reuse the existing `WhisperCompatProvider` and add a custom config path that supplies endpoint/model from stored app config. Settings owns the custom provider UI; onboarding continues to show only the existing simple providers until local server setup is designed for onboarding.

**Tech Stack:** Tauri v2, Rust, reqwest multipart, React, Zustand, i18next, Vitest, Cargo tests

---

## File Structure

- Modify `src-tauri/src/stt/config.rs`: provider constants, custom defaults, URL normalization, known/custom config builders, unit tests.
- Modify `src-tauri/src/stt/whisper_compat.rs`: optional API-key support for local servers, request header behavior, tests updated for new config field.
- Modify `src-tauri/src/stt/mod.rs`: return `Result<Box<dyn SttProvider>, AppError>` from provider creation and accept optional custom config.
- Modify `src-tauri/src/pipeline.rs`: skip API-key guard for `custom-whisper`, build custom provider config from `AppConfig`, handle provider creation errors.
- Modify `src-tauri/src/commands/stt.rs`: accept optional custom base URL/model in test and benchmark commands; reuse the same custom config builder and friendly errors.
- Modify `src-tauri/src/storage/mod.rs`: persist custom STT preset/base URL/model with serde defaults.
- Modify `src/stores/appStore.ts`: add custom STT fields and provider union value.
- Modify `src/lib/constants.ts`: add custom defaults, presets, Settings provider list, and onboarding provider list.
- Modify `src/lib/tauri.ts`: pass optional custom base URL/model to STT test commands.
- Modify `src/components/Settings/SttPane.tsx`: render preset/base URL/model/API-key UI for custom STT and pass custom fields to test.
- Modify `src/components/Onboarding/SttSetupStep.tsx`: use onboarding-only STT provider list so incomplete custom setup is not exposed there.
- Modify `src/i18n/locales/*.json`: add provider and settings labels/errors.
- Modify `src/components/Settings/__tests__/SttPane.test.tsx`: cover custom provider UI and test behavior.

## Task 1: Rust Custom Whisper Config

**Files:**
- Modify: `src-tauri/src/stt/config.rs`
- Modify: `src-tauri/src/stt/whisper_compat.rs`

- [ ] **Step 1: Write failing config tests**

Add these tests inside `#[cfg(test)] mod tests` in `src-tauri/src/stt/config.rs`:

```rust
#[test]
fn test_normalize_custom_whisper_base_url() {
    let endpoint = normalize_custom_whisper_endpoint("http://localhost:8000/v1").unwrap();
    assert_eq!(endpoint, "http://localhost:8000/v1/audio/transcriptions");
}

#[test]
fn test_normalize_custom_whisper_full_endpoint() {
    let endpoint =
        normalize_custom_whisper_endpoint("http://localhost:8000/v1/audio/transcriptions")
            .unwrap();
    assert_eq!(endpoint, "http://localhost:8000/v1/audio/transcriptions");
}

#[test]
fn test_custom_whisper_rejects_empty_base_url() {
    let err = normalize_custom_whisper_endpoint("   ").unwrap_err();
    assert!(err.contains("Base URL is required"));
}

#[test]
fn test_custom_whisper_rejects_non_http_url() {
    let err = normalize_custom_whisper_endpoint("file:///tmp/server").unwrap_err();
    assert!(err.contains("http://"));
}

#[test]
fn test_build_custom_whisper_config() {
    let cfg = build_custom_whisper_config(
        "http://localhost:8000/v1",
        "Systran/faster-whisper-large-v3",
    )
    .unwrap();
    assert_eq!(cfg.provider_name, CUSTOM_WHISPER_PROVIDER);
    assert_eq!(cfg.endpoint, "http://localhost:8000/v1/audio/transcriptions");
    assert_eq!(cfg.model, "Systran/faster-whisper-large-v3");
    assert!(!cfg.api_key_required);
}

#[test]
fn test_build_custom_whisper_config_requires_model() {
    let err = build_custom_whisper_config("http://localhost:8000/v1", "  ").unwrap_err();
    assert!(err.contains("Model is required"));
}
```

Update the existing `WhisperCompatConfig` construction in `src-tauri/src/stt/whisper_compat.rs` tests to include `api_key_required: true`; this should fail until the field exists:

```rust
let mut provider = WhisperCompatProvider::new(WhisperCompatConfig {
    provider_name: "test-whisper".to_string(),
    endpoint: "https://example.test/transcriptions".to_string(),
    model: "test-model".to_string(),
    extra_fields: vec![],
    api_key_required: true,
});
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
cd src-tauri
cargo test stt::config
```

Expected: FAIL with missing functions/constants such as `normalize_custom_whisper_endpoint` and missing field `api_key_required`.

- [ ] **Step 3: Implement custom config helpers**

In `src-tauri/src/stt/whisper_compat.rs`, extend the config struct:

```rust
pub struct WhisperCompatConfig {
    pub provider_name: String,
    pub endpoint: String,
    pub model: String,
    /// Extra form text fields (e.g. GLM-ASR needs "stream"="false").
    pub extra_fields: Vec<(String, String)>,
    /// Local OpenAI-compatible servers often do not require authentication.
    pub api_key_required: bool,
}
```

In `src-tauri/src/stt/config.rs`, add these constants and helpers near the top:

```rust
use super::whisper_compat::WhisperCompatConfig;

pub const CUSTOM_WHISPER_PROVIDER: &str = "custom-whisper";
pub const CUSTOM_WHISPER_PRESET_SPEACHES: &str = "speaches";
pub const CUSTOM_WHISPER_PRESET_CUSTOM: &str = "custom";
pub const DEFAULT_CUSTOM_WHISPER_BASE_URL: &str = "http://localhost:8000/v1";
pub const DEFAULT_CUSTOM_WHISPER_MODEL: &str = "Systran/faster-whisper-large-v3";

pub fn normalize_custom_whisper_endpoint(base_url: &str) -> Result<String, String> {
    let trimmed = base_url.trim().trim_end_matches('/');
    if trimmed.is_empty() {
        return Err("Base URL is required for Local / Custom Whisper".to_string());
    }

    let parsed = url::Url::parse(trimmed)
        .map_err(|_| "Base URL must be a valid URL".to_string())?;
    if parsed.scheme() != "http" && parsed.scheme() != "https" {
        return Err("Base URL must start with http:// or https://".to_string());
    }

    if trimmed.ends_with("/audio/transcriptions") {
        Ok(trimmed.to_string())
    } else {
        Ok(format!("{}/audio/transcriptions", trimmed))
    }
}

pub fn build_custom_whisper_config(
    base_url: &str,
    model: &str,
) -> Result<WhisperCompatConfig, String> {
    let model = model.trim();
    if model.is_empty() {
        return Err("Model is required for Local / Custom Whisper".to_string());
    }

    Ok(WhisperCompatConfig {
        provider_name: CUSTOM_WHISPER_PROVIDER.to_string(),
        endpoint: normalize_custom_whisper_endpoint(base_url)?,
        model: model.to_string(),
        extra_fields: vec![],
        api_key_required: false,
    })
}

pub fn build_known_whisper_config(provider: &str) -> Option<WhisperCompatConfig> {
    let cfg = get_whisper_config(provider)?;
    Some(WhisperCompatConfig {
        provider_name: provider.to_string(),
        endpoint: cfg.endpoint.to_string(),
        model: cfg.model.to_string(),
        extra_fields: cfg
            .extra_fields
            .iter()
            .map(|(k, v)| (k.to_string(), v.to_string()))
            .collect(),
        api_key_required: true,
    })
}

pub fn stt_provider_requires_api_key(provider: &str) -> bool {
    !matches!(provider, "cloud" | CUSTOM_WHISPER_PROVIDER)
}
```

- [ ] **Step 4: Run tests to verify pass**

Run:

```bash
cd src-tauri
cargo test stt::config
cargo test stt::whisper_compat
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/stt/config.rs src-tauri/src/stt/whisper_compat.rs
git commit -m "feat(stt): add custom whisper config helpers"
```

## Task 2: Optional Auth in Whisper-Compatible Provider

**Files:**
- Modify: `src-tauri/src/stt/whisper_compat.rs`

- [ ] **Step 1: Write failing provider auth test**

Add this unit test in `src-tauri/src/stt/whisper_compat.rs`:

```rust
#[tokio::test]
async fn connect_allows_empty_api_key_when_not_required() {
    let mut provider = WhisperCompatProvider::new(WhisperCompatConfig {
        provider_name: "custom-whisper".to_string(),
        endpoint: "http://localhost:8000/v1/audio/transcriptions".to_string(),
        model: "test-model".to_string(),
        extra_fields: vec![],
        api_key_required: false,
    });

    let result = provider.connect(&SttConfig {
        api_key: String::new(),
        language: None,
        smart_format: true,
        sample_rate: 16000,
    })
    .await;

    assert!(result.is_ok());
}
```

- [ ] **Step 2: Run test to verify failure**

Run:

```bash
cd src-tauri
cargo test stt::whisper_compat::tests::connect_allows_empty_api_key_when_not_required
```

Expected: FAIL because `connect` still rejects every empty API key.

- [ ] **Step 3: Implement optional auth**

Change the `connect` guard in `src-tauri/src/stt/whisper_compat.rs`:

```rust
if self.provider_config.api_key_required && config.api_key.is_empty() {
    return Err(AppError::Auth(format!(
        "{} API key is empty",
        self.provider_config.provider_name
    )));
}
```

Change the request builder in `disconnect` so the Authorization header is only sent when present:

```rust
let mut request = self
    .client
    .post(&self.provider_config.endpoint)
    .multipart(form)
    .timeout(std::time::Duration::from_secs(60));

if !config.api_key.trim().is_empty() {
    request = request.header("Authorization", format!("Bearer {}", config.api_key));
}

let resp_result = request.send().await;
```

- [ ] **Step 4: Run provider tests**

Run:

```bash
cd src-tauri
cargo test stt::whisper_compat
```

Expected: PASS.

- [ ] **Step 5: Commit**

```bash
git add src-tauri/src/stt/whisper_compat.rs
git commit -m "feat(stt): allow optional auth for custom whisper"
```

## Task 3: Wire Custom Provider into Rust Pipeline

**Files:**
- Modify: `src-tauri/src/stt/mod.rs`
- Modify: `src-tauri/src/pipeline.rs`
- Modify: `src-tauri/src/storage/mod.rs`

- [ ] **Step 1: Write failing provider creation tests**

Add this test module to `src-tauri/src/stt/mod.rs`:

```rust
#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn custom_whisper_requires_explicit_config() {
        let result = create_provider(config::CUSTOM_WHISPER_PROVIDER, None, None);
        assert!(result.is_err());
    }

    #[test]
    fn custom_whisper_uses_explicit_config() {
        let cfg = config::build_custom_whisper_config(
            "http://localhost:8000/v1",
            "Systran/faster-whisper-large-v3",
        )
        .unwrap();

        let provider = create_provider(config::CUSTOM_WHISPER_PROVIDER, Some(cfg), None).unwrap();
        assert_eq!(provider.name(), config::CUSTOM_WHISPER_PROVIDER);
    }

    #[test]
    fn unknown_stt_provider_returns_error() {
        let result = create_provider("not-a-provider", None, None);
        assert!(result.is_err());
    }
}
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
cd src-tauri
cargo test stt::mod::tests
```

Expected: FAIL because `create_provider` does not accept custom config and does not return `Result`.

- [ ] **Step 3: Update storage config defaults**

Add fields to `src-tauri/src/storage/mod.rs` `AppConfig`:

```rust
pub stt_custom_preset: String,
pub stt_custom_base_url: String,
pub stt_custom_model: String,
```

Add default values:

```rust
stt_custom_preset: crate::stt::config::CUSTOM_WHISPER_PRESET_SPEACHES.to_string(),
stt_custom_base_url: crate::stt::config::DEFAULT_CUSTOM_WHISPER_BASE_URL.to_string(),
stt_custom_model: crate::stt::config::DEFAULT_CUSTOM_WHISPER_MODEL.to_string(),
```

- [ ] **Step 4: Update provider creation**

Replace `create_provider` in `src-tauri/src/stt/mod.rs` with this signature and behavior:

```rust
pub fn create_provider(
    provider_name: &str,
    custom_whisper_config: Option<WhisperCompatConfig>,
    client: Option<reqwest::Client>,
) -> Result<Box<dyn SttProvider>, AppError> {
    match provider_name {
        "cloud" => {
            let api_base_url = crate::api_base_url();
            Ok(match client {
                Some(ref c) => Box::new(cloud::CloudSttProvider::with_client(
                    api_base_url,
                    c.clone(),
                )),
                None => Box::new(cloud::CloudSttProvider::new(api_base_url)),
            })
        }
        "assemblyai" => Ok(Box::new(assemblyai::AssemblyAiProvider::new())),
        "deepgram" => Ok(match client {
            Some(ref c) => Box::new(deepgram::DeepgramProvider::with_client(c.clone())),
            None => Box::new(deepgram::DeepgramProvider::new()),
        }),
        config::CUSTOM_WHISPER_PROVIDER => {
            let wc = custom_whisper_config.ok_or_else(|| {
                AppError::Config("Local / Custom Whisper is missing base URL or model".to_string())
            })?;
            Ok(match client {
                Some(ref c) => Box::new(WhisperCompatProvider::with_client(wc, c.clone())),
                None => Box::new(WhisperCompatProvider::new(wc)),
            })
        }
        name => {
            let wc = config::build_known_whisper_config(name)
                .ok_or_else(|| AppError::Config(format!("Unknown STT provider: {}", name)))?;
            Ok(match client {
                Some(ref c) => Box::new(WhisperCompatProvider::with_client(wc, c.clone())),
                None => Box::new(WhisperCompatProvider::new(wc)),
            })
        }
    }
}
```

- [ ] **Step 5: Update pipeline guard and provider config**

In `src-tauri/src/pipeline.rs`, replace the API-key guard:

```rust
if stt::config::stt_provider_requires_api_key(&config_data.stt_provider)
    && config_data.stt_api_key.is_empty()
{
    let _ = self.app_handle.emit(
        "pipeline:error",
        "STT API key is not configured. Please set it in Settings -> Speech Recognition.",
    );
    *self
        .preloaded_config
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = None;
    *self
        .preloaded_app_ctx
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = None;
    *self
        .preloaded_dictionary
        .lock()
        .unwrap_or_else(|e| e.into_inner()) = None;
    self.set_state(PipelineState::Idle);
    return Ok(());
}
```

Before provider creation, build optional custom config:

```rust
let custom_whisper_config =
    if config_data.stt_provider == stt::config::CUSTOM_WHISPER_PROVIDER {
        match stt::config::build_custom_whisper_config(
            &config_data.stt_custom_base_url,
            &config_data.stt_custom_model,
        ) {
            Ok(cfg) => Some(cfg),
            Err(e) => {
                let _ = self.app_handle.emit("pipeline:error", e);
                self.set_state(PipelineState::Idle);
                return Ok(());
            }
        }
    } else {
        None
    };
```

Replace the provider creation call:

```rust
let mut provider = match stt::create_provider(
    &config_data.stt_provider,
    custom_whisper_config,
    Some(self.shared_client.clone()),
) {
    Ok(provider) => provider,
    Err(e) => {
        tracing::error!("STT provider creation failed: {}", e);
        let _ = self
            .app_handle
            .emit("pipeline:error", format!("STT configuration failed: {e}"));
        self.set_state(PipelineState::Idle);
        return Ok(());
    }
};
```

- [ ] **Step 6: Run Rust tests**

Run:

```bash
cd src-tauri
cargo test stt::
```

Expected: PASS.

- [ ] **Step 7: Commit**

```bash
git add src-tauri/src/stt/mod.rs src-tauri/src/pipeline.rs src-tauri/src/storage/mod.rs
git commit -m "feat(stt): wire custom whisper provider"
```

## Task 4: Custom STT Test and Benchmark Commands

**Files:**
- Modify: `src-tauri/src/commands/stt.rs`
- Modify: `src/lib/tauri.ts`

- [ ] **Step 1: Add Rust helper tests**

Add this helper near the top of `src-tauri/src/commands/stt.rs`:

```rust
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
```

Add tests at the bottom:

```rust
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
        assert_eq!(cfg.endpoint, "http://localhost:8000/v1/audio/transcriptions");
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
```

- [ ] **Step 2: Run tests to verify helper compiles**

Run:

```bash
cd src-tauri
cargo test commands::stt
```

Expected: PASS after helper insertion, before command signature changes.

- [ ] **Step 3: Update Tauri command signatures**

Change both command signatures in `src-tauri/src/commands/stt.rs`:

```rust
pub async fn test_stt_connection(
    api_key: String,
    provider: String,
    custom_base_url: Option<String>,
    custom_model: Option<String>,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<bool, String> {
```

```rust
pub async fn bench_stt_connection(
    api_key: String,
    provider: String,
    custom_base_url: Option<String>,
    custom_model: Option<String>,
    token_store: tauri::State<'_, SessionTokenStore>,
    client: tauri::State<'_, reqwest::Client>,
) -> Result<u32, String> {
```

Replace the non-cloud empty-key guard in both commands:

```rust
if stt::config::stt_provider_requires_api_key(&provider) && api_key.is_empty() {
    return Ok(false);
}
```

For `bench_stt_connection`, return an error:

```rust
if stt::config::stt_provider_requires_api_key(&provider) && api_key.is_empty() {
    return Err("API key is empty".to_string());
}
```

Replace known-provider config lookup in both commands:

```rust
let cfg = resolve_whisper_test_config(&provider, custom_base_url, custom_model)?;
```

When building the request, use the owned config and conditional auth:

```rust
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
```

- [ ] **Step 4: Update TypeScript wrapper signatures**

Change `src/lib/tauri.ts`:

```ts
export async function testSttConnection(
  apiKey: string,
  provider: string,
  customBaseUrl?: string,
  customModel?: string,
): Promise<boolean> {
  return invoke('test_stt_connection', {
    apiKey,
    provider,
    customBaseUrl,
    customModel,
  })
}

export async function benchSttConnection(
  apiKey: string,
  provider: string,
  customBaseUrl?: string,
  customModel?: string,
): Promise<number> {
  return invoke('bench_stt_connection', {
    apiKey,
    provider,
    customBaseUrl,
    customModel,
  })
}
```

- [ ] **Step 5: Run Rust and TypeScript checks**

Run:

```bash
cd src-tauri
cargo test commands::stt
cd ..
npm run build
```

Expected: both PASS. If `npm run build` reaches a Tauri/Vite build stage and fails for unrelated environment reasons, record the exact failure before continuing.

- [ ] **Step 6: Commit**

```bash
git add src-tauri/src/commands/stt.rs src/lib/tauri.ts
git commit -m "feat(stt): test custom whisper connections"
```

## Task 5: Frontend State, Constants, and i18n

**Files:**
- Modify: `src/stores/appStore.ts`
- Modify: `src/lib/constants.ts`
- Modify: `src/components/Onboarding/SttSetupStep.tsx`
- Modify: `src/i18n/locales/en.json`
- Modify: `src/i18n/locales/zh.json`
- Modify: `src/i18n/locales/de.json`
- Modify: `src/i18n/locales/es.json`
- Modify: `src/i18n/locales/fr.json`
- Modify: `src/i18n/locales/it.json`
- Modify: `src/i18n/locales/ja.json`
- Modify: `src/i18n/locales/ko.json`
- Modify: `src/i18n/locales/pt.json`
- Modify: `src/i18n/locales/ru.json`

- [ ] **Step 1: Update app store types and defaults**

In `src/stores/appStore.ts`, add the provider union member:

```ts
export type SttProvider =
  | 'deepgram'
  | 'assemblyai'
  | 'glm-asr'
  | 'openai-whisper'
  | 'groq-whisper'
  | 'siliconflow'
  | 'custom-whisper'
  | 'cloud'
```

Add fields to `AppConfig`:

```ts
stt_custom_preset: 'speaches' | 'custom'
stt_custom_base_url: string
stt_custom_model: string
```

Add defaults:

```ts
stt_custom_preset: 'speaches',
stt_custom_base_url: 'http://localhost:8000/v1',
stt_custom_model: 'Systran/faster-whisper-large-v3',
```

- [ ] **Step 2: Update constants and onboarding provider list**

In `src/lib/constants.ts`, add:

```ts
export const CUSTOM_WHISPER_PROVIDER = 'custom-whisper' as const

export const CUSTOM_STT_DEFAULTS = {
  preset: 'speaches',
  baseUrl: 'http://localhost:8000/v1',
  model: 'Systran/faster-whisper-large-v3',
} as const

export const CUSTOM_STT_PRESETS = [
  {
    value: 'speaches',
    labelKey: 'settings.customSttPresetSpeaches',
    baseUrl: CUSTOM_STT_DEFAULTS.baseUrl,
    model: CUSTOM_STT_DEFAULTS.model,
  },
  {
    value: 'custom',
    labelKey: 'settings.customSttPresetCustom',
  },
] as const
```

Add custom provider to `STT_PROVIDERS` before cloud:

```ts
{ value: CUSTOM_WHISPER_PROVIDER, labelKey: 'providers.stt.customWhisper' },
```

Add an onboarding-only list:

```ts
export const ONBOARDING_STT_PROVIDERS = STT_PROVIDERS.filter(
  (provider) => provider.value !== CUSTOM_WHISPER_PROVIDER,
)
```

In `src/components/Onboarding/SttSetupStep.tsx`, change the import and map:

```ts
import { ONBOARDING_STT_PROVIDERS } from '../../lib/constants'
```

```tsx
{ONBOARDING_STT_PROVIDERS.map((p) => (
  <option key={p.value} value={p.value}>
    {t(p.labelKey)}
  </option>
))}
```

- [ ] **Step 3: Add English and Chinese i18n keys**

In `src/i18n/locales/en.json`, under `settings`, add:

```json
"customSttPreset": "Preset",
"customSttPresetSpeaches": "Speaches",
"customSttPresetCustom": "Custom OpenAI-compatible",
"customSttBaseUrl": "Base URL",
"customSttBaseUrlPlaceholder": "http://localhost:8000/v1",
"customSttModel": "Model",
"customSttModelPlaceholder": "Systran/faster-whisper-large-v3",
"customSttApiKeyOptional": "API Key (optional)",
"customSttSetupHint": "Start your local OpenAI-compatible STT server first, then test the connection here.",
"customSttConnectionFailed": "Local STT server is not reachable. Check that it is running and the port is correct."
```

Under `providers.stt`, add:

```json
"customWhisper": "Local / Custom Whisper"
```

In `src/i18n/locales/zh.json`, under `settings`, add:

```json
"customSttPreset": "预设",
"customSttPresetSpeaches": "Speaches",
"customSttPresetCustom": "自定义 OpenAI 兼容服务",
"customSttBaseUrl": "Base URL",
"customSttBaseUrlPlaceholder": "http://localhost:8000/v1",
"customSttModel": "模型",
"customSttModelPlaceholder": "Systran/faster-whisper-large-v3",
"customSttApiKeyOptional": "API Key（可选）",
"customSttSetupHint": "请先启动本地 OpenAI 兼容 STT 服务，然后在这里测试连接。",
"customSttConnectionFailed": "无法连接本地 STT 服务。请确认服务已启动且端口正确。"
```

Under `providers.stt`, add:

```json
"customWhisper": "本地 / 自定义 Whisper"
```

- [ ] **Step 4: Add fallback keys to remaining locales**

For `de.json`, `es.json`, `fr.json`, `it.json`, `ja.json`, `ko.json`, `pt.json`, and `ru.json`, add the same English values from Step 3. This ensures the UI never displays raw translation keys when fallback resolution is not triggered.

Use this exact settings fragment:

```json
"customSttPreset": "Preset",
"customSttPresetSpeaches": "Speaches",
"customSttPresetCustom": "Custom OpenAI-compatible",
"customSttBaseUrl": "Base URL",
"customSttBaseUrlPlaceholder": "http://localhost:8000/v1",
"customSttModel": "Model",
"customSttModelPlaceholder": "Systran/faster-whisper-large-v3",
"customSttApiKeyOptional": "API Key (optional)",
"customSttSetupHint": "Start your local OpenAI-compatible STT server first, then test the connection here.",
"customSttConnectionFailed": "Local STT server is not reachable. Check that it is running and the port is correct."
```

Use this exact provider fragment:

```json
"customWhisper": "Local / Custom Whisper"
```

- [ ] **Step 5: Run frontend type check**

Run:

```bash
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/stores/appStore.ts src/lib/constants.ts src/components/Onboarding/SttSetupStep.tsx src/i18n/locales
git commit -m "feat(stt): add custom whisper frontend config"
```

## Task 6: Settings UI for Local / Custom Whisper

**Files:**
- Modify: `src/components/Settings/SttPane.tsx`
- Modify: `src/components/Settings/__tests__/SttPane.test.tsx`

- [ ] **Step 1: Write failing Settings tests**

In the i18n mock in `src/components/Settings/__tests__/SttPane.test.tsx`, add translations:

```ts
'settings.customSttPreset': 'Preset',
'settings.customSttPresetSpeaches': 'Speaches',
'settings.customSttPresetCustom': 'Custom OpenAI-compatible',
'settings.customSttBaseUrl': 'Base URL',
'settings.customSttBaseUrlPlaceholder': 'http://localhost:8000/v1',
'settings.customSttModel': 'Model',
'settings.customSttModelPlaceholder': 'Systran/faster-whisper-large-v3',
'settings.customSttApiKeyOptional': 'API Key (optional)',
'settings.customSttSetupHint': 'Start your local OpenAI-compatible STT server first, then test the connection here.',
```

Extend `mockAppStore.config` in both declarations and `beforeEach`:

```ts
stt_custom_preset: 'speaches',
stt_custom_base_url: 'http://localhost:8000/v1',
stt_custom_model: 'Systran/faster-whisper-large-v3',
```

Add tests:

```ts
describe('Custom Whisper provider UI', () => {
  beforeEach(() => {
    mockAppStore.config.stt_provider = 'custom-whisper'
    mockAppStore.config.stt_api_key = ''
  })

  it('shows preset, base URL, model, and optional API key fields', () => {
    render(<SttPane />)
    expect(screen.getByText('Preset')).toBeInTheDocument()
    expect(screen.getByText('Base URL')).toBeInTheDocument()
    expect(screen.getByText('Model')).toBeInTheDocument()
    expect(screen.getByText('API Key (optional)')).toBeInTheDocument()
    expect(screen.getByDisplayValue('http://localhost:8000/v1')).toBeInTheDocument()
    expect(screen.getByDisplayValue('Systran/faster-whisper-large-v3')).toBeInTheDocument()
  })

  it('enables test without an API key when base URL and model are present', () => {
    render(<SttPane />)
    const button = screen.getAllByRole('button', { name: /test/i })[0]
    expect(button).not.toBeDisabled()
  })

  it('fills Speaches defaults when Speaches preset is selected', () => {
    mockAppStore.config.stt_custom_preset = 'custom'
    mockAppStore.config.stt_custom_base_url = 'http://localhost:9000/v1'
    mockAppStore.config.stt_custom_model = 'custom-model'

    render(<SttPane />)
    const selects = screen.getAllByRole('combobox')
    const presetSelect = selects[1]

    fireEvent.change(presetSelect, { target: { value: 'speaches' } })

    expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
      stt_custom_preset: 'speaches',
      stt_custom_base_url: 'http://localhost:8000/v1',
      stt_custom_model: 'Systran/faster-whisper-large-v3',
    })
  })

  it('preserves values when Custom preset is selected', () => {
    render(<SttPane />)
    const selects = screen.getAllByRole('combobox')
    const presetSelect = selects[1]

    fireEvent.change(presetSelect, { target: { value: 'custom' } })

    expect(mockAppStore.updateConfig).toHaveBeenCalledWith({
      stt_custom_preset: 'custom',
    })
  })

  it('passes custom base URL and model to the benchmark command', async () => {
    const mockBenchStt = vi.mocked(tauri.benchSttConnection)
    mockBenchStt.mockResolvedValue(123)

    render(<SttPane />)
    fireEvent.click(screen.getAllByRole('button', { name: /test/i })[0])

    await waitFor(() => {
      expect(mockBenchStt).toHaveBeenCalledWith(
        '',
        'custom-whisper',
        'http://localhost:8000/v1',
        'Systran/faster-whisper-large-v3',
      )
    })
  })
})
```

- [ ] **Step 2: Run tests to verify failure**

Run:

```bash
npm test -- src/components/Settings/__tests__/SttPane.test.tsx
```

Expected: FAIL because custom fields are not rendered and benchmark args are not passed.

- [ ] **Step 3: Implement Settings UI**

Update imports in `src/components/Settings/SttPane.tsx`:

```ts
import {
  STT_PROVIDERS,
  LANGUAGES,
  CUSTOM_WHISPER_PROVIDER,
  CUSTOM_STT_DEFAULTS,
  CUSTOM_STT_PRESETS,
} from '../../lib/constants'
```

Add flags near `isCloud`:

```ts
const isCustomWhisper = config.stt_provider === CUSTOM_WHISPER_PROVIDER
const canTest = isCustomWhisper
  ? Boolean(config.stt_custom_base_url.trim() && config.stt_custom_model.trim())
  : Boolean(config.stt_api_key)
```

Change `handleTest`:

```ts
const ms = await benchSttConnection(
  config.stt_api_key,
  config.stt_provider,
  isCustomWhisper ? config.stt_custom_base_url : undefined,
  isCustomWhisper ? config.stt_custom_model : undefined,
)
```

In the provider `onChange`, when provider is custom, seed defaults if fields are empty:

```ts
const provider = e.target.value as typeof config.stt_provider
updateConfig({
  stt_provider: provider,
  ...(provider === CUSTOM_WHISPER_PROVIDER
    ? {
        stt_custom_preset: config.stt_custom_preset || CUSTOM_STT_DEFAULTS.preset,
        stt_custom_base_url: config.stt_custom_base_url || CUSTOM_STT_DEFAULTS.baseUrl,
        stt_custom_model: config.stt_custom_model || CUSTOM_STT_DEFAULTS.model,
      }
    : {}),
})
```

Render custom fields in the non-cloud branch before API key:

```tsx
{isCustomWhisper && (
  <>
    <FormField label={t('settings.customSttPreset')}>
      <select
        value={config.stt_custom_preset}
        onChange={(e) => {
          const preset = e.target.value as typeof config.stt_custom_preset
          const selected = CUSTOM_STT_PRESETS.find((p) => p.value === preset)
          updateConfig({
            stt_custom_preset: preset,
            ...(selected?.baseUrl && selected?.model
              ? {
                  stt_custom_base_url: selected.baseUrl,
                  stt_custom_model: selected.model,
                }
              : {}),
          })
          setSttTestStatus('idle')
          setSttLatencyMs(null)
        }}
        className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
      >
        {CUSTOM_STT_PRESETS.map((preset) => (
          <option key={preset.value} value={preset.value}>
            {t(preset.labelKey)}
          </option>
        ))}
      </select>
    </FormField>

    <FormField label={t('settings.customSttBaseUrl')}>
      <input
        value={config.stt_custom_base_url}
        onChange={(e) => {
          updateConfig({ stt_custom_base_url: e.target.value })
          setSttTestStatus('idle')
          setSttLatencyMs(null)
        }}
        placeholder={t('settings.customSttBaseUrlPlaceholder')}
        className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
      />
    </FormField>

    <FormField label={t('settings.customSttModel')}>
      <input
        value={config.stt_custom_model}
        onChange={(e) => {
          updateConfig({ stt_custom_model: e.target.value })
          setSttTestStatus('idle')
          setSttLatencyMs(null)
        }}
        placeholder={t('settings.customSttModelPlaceholder')}
        className="w-full px-3 py-2.5 bg-bg-secondary border border-border rounded-[10px] text-[13px] text-text-primary outline-none focus:border-border-focus transition-colors"
      />
      <p className="text-[11px] text-text-tertiary mt-1.5">
        {t('settings.customSttSetupHint')}
      </p>
    </FormField>
  </>
)}
```

Use optional API key label and test enablement:

```tsx
<FormField label={isCustomWhisper ? t('settings.customSttApiKeyOptional') : t('settings.apiKey')}>
```

```tsx
disabled={!canTest || sttTestStatus === 'testing'}
```

- [ ] **Step 4: Run Settings tests**

Run:

```bash
npm test -- src/components/Settings/__tests__/SttPane.test.tsx
```

Expected: PASS.

- [ ] **Step 5: Run build**

Run:

```bash
npm run build
```

Expected: PASS.

- [ ] **Step 6: Commit**

```bash
git add src/components/Settings/SttPane.tsx src/components/Settings/__tests__/SttPane.test.tsx
git commit -m "feat(stt): add custom whisper settings ui"
```

## Task 7: Full Verification

**Files:**
- No planned file edits unless verification exposes a defect.

- [ ] **Step 1: Run frontend tests**

Run:

```bash
npm test
```

Expected: PASS.

- [ ] **Step 2: Run frontend build**

Run:

```bash
npm run build
```

Expected: PASS.

- [ ] **Step 3: Run Rust tests**

Run:

```bash
cd src-tauri
cargo test
```

Expected: PASS.

- [ ] **Step 4: Review git diff**

Run:

```bash
git status --short
git log --oneline -5
```

Expected:

- Working tree contains only intentional changes or is clean aside from pre-existing `release-artifacts/`.
- Recent commits include the design commit and task commits.

- [ ] **Step 5: Manual local STT check**

With a local OpenAI-compatible STT server running on `http://localhost:8000/v1`, open Settings -> Speech Recognition and verify:

- Provider can be set to Local / Custom Whisper.
- Speaches preset fills `http://localhost:8000/v1` and `Systran/faster-whisper-large-v3`.
- Test succeeds without API key when the server does not require auth.
- If the server is stopped, Test fails and the UI shows the connection failed state.

- [ ] **Step 6: Commit any verification fixes**

If verification exposed a defect and a fix was made:

```bash
git add <changed-files>
git commit -m "fix(stt): stabilize custom whisper verification"
```

If no fix was needed, do not create an empty commit.

## Self-Review Notes

- Spec coverage: provider option, presets, base URL/model/API key, optional auth, URL normalization, Settings UI, friendly test flow, existing provider preservation, local config, and future managed-local non-goals are all covered.
- Scope: the plan does not add model download, local process management, arbitrary request schemas, or streaming local STT.
- Type consistency: frontend provider id is `custom-whisper`; preset values are `speaches` and `custom`; Rust provider constant is `CUSTOM_WHISPER_PROVIDER`.
