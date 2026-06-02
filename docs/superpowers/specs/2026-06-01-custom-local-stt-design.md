# Custom Local STT Design

Date: 2026-06-01

## Overview

Add a first-class local/custom STT option for OpenAI-compatible transcription servers. The immediate driver is GitHub Discussion #41, where a user self-hosts Speaches on a LAN GPU and wants OpenTypeless to call its `/v1/audio/transcriptions` API instead of the currently hardcoded Whisper-compatible endpoints.

The feature should make local STT approachable without turning this MVP into a full model manager. Users who already run Speaches, LocalAI, or another OpenAI-compatible Whisper server can select a preset, adjust the base URL/model if needed, test the connection, and use the existing recording pipeline.

## Goals

- Add an STT provider labeled "Local / Custom Whisper" or equivalent.
- Support OpenAI-compatible `audio/transcriptions` APIs by reusing the existing `WhisperCompatProvider`.
- Let users configure:
  - Preset: Speaches or Custom OpenAI-compatible.
  - Base URL, such as `http://localhost:8000/v1`.
  - Model name.
  - Optional API key.
- Make the API key optional for local servers with no authentication.
- Provide a friendly test flow with actionable errors for common setup problems.
- Preserve all existing STT providers and their current defaults.
- Keep configuration local in the existing Tauri store.

## Non-Goals

- Do not download Whisper models.
- Do not start, stop, install, or update a local STT server.
- Do not manage GPU/CPU runtime selection.
- Do not add custom request schema editing, arbitrary multipart fields, or response mapping.
- Do not add streaming local STT. This uses the existing file-upload final transcription path.
- Do not build the broader plugin system yet.

## User Experience

In Settings -> Speech Recognition, users can choose a new provider: "Local / Custom Whisper".

When selected, the pane shows:

- A preset selector:
  - Speaches
  - Custom OpenAI-compatible
- A Base URL field.
- A Model field.
- An optional API Key field.
- The existing Language selector.
- The existing Test button.

Selecting the Speaches preset fills:

- Base URL: `http://localhost:8000/v1`
- Model: a sensible default such as `Systran/faster-whisper-large-v3`

Selecting Custom OpenAI-compatible keeps the fields editable and does not assume a specific port. The placeholder should show the expected shape: `http://localhost:8000/v1`.

The product stance is "helpful local setup, not managed local runtime." The UI should avoid suggesting that OpenTypeless will download a model or start a server in this MVP. It can mention that the local server must already be running.

## URL Handling

The user-facing field is a base URL, not a full endpoint. Recommended input:

```text
http://localhost:8000/v1
```

The backend derives the final transcription endpoint by appending `/audio/transcriptions`.

For resilience, accept users who paste a full endpoint:

```text
http://localhost:8000/v1/audio/transcriptions
```

Normalize it to the same final endpoint instead of appending the path twice. This makes the form forgiving without exposing endpoint-path complexity in the UI.

## Configuration Model

Extend the app config with custom STT fields:

- `stt_custom_preset`: string, default `speaches`
- `stt_custom_base_url`: string, default `http://localhost:8000/v1`
- `stt_custom_model`: string, default `Systran/faster-whisper-large-v3`

Reuse the existing `stt_api_key` field for the optional API key while the selected provider is Local / Custom Whisper. This avoids a second secret field and matches existing STT settings behavior.

The frontend provider id should be stable, for example:

```text
custom-whisper
```

Rust should treat this provider as a Whisper-compatible provider whose endpoint and model come from the saved app config rather than from `stt::config::get_whisper_config`.

## Backend Design

Current state:

- `src-tauri/src/stt/config.rs` returns hardcoded endpoint/model pairs for Whisper-compatible providers.
- `src-tauri/src/stt/whisper_compat.rs` already implements the OpenAI-compatible multipart upload path.
- `src-tauri/src/stt/mod.rs` creates `WhisperCompatProvider` for provider names handled by `get_whisper_config`.
- `src-tauri/src/commands/stt.rs` duplicates connection test logic for Whisper-compatible providers.

Design:

1. Add a small config builder that can produce `WhisperCompatConfig` from either:
   - a known provider id such as `groq-whisper`, or
   - the custom provider fields stored in `AppConfig`.
2. Normalize custom base URLs in one Rust helper:
   - trim whitespace.
   - trim trailing slashes.
   - if the URL ends with `/audio/transcriptions`, use it as-is.
   - otherwise append `/audio/transcriptions`.
3. Update pipeline provider creation so `custom-whisper` receives the custom endpoint/model.
4. Update STT test and benchmark commands so they can test `custom-whisper` with the same custom fields.

The backend should not silently fall back to `glm-asr` when `custom-whisper` config is invalid. It should return a clear configuration error.

## Frontend Design

Update the STT settings pane:

- Add `custom-whisper` to `STT_PROVIDERS`.
- When the selected provider is `custom-whisper`, render preset, base URL, model, optional API key, and Test.
- When preset changes to Speaches, fill the Speaches defaults.
- When preset changes to Custom, preserve the current values so users do not lose edits.
- Keep the language field visible, as the existing Whisper-compatible request can pass a language hint.

The component should stay consistent with the current Settings style:

- Use existing `FormField`.
- Use existing button/test status patterns.
- Add i18n keys instead of hardcoded visible strings.
- Avoid adding a separate explanatory card unless the current settings layout already calls for it.

## Error Handling

The Test action should translate backend failures into actionable messages. Suggested mapping:

- Network/connect timeout: "Local STT server is not reachable. Check that it is running and the port is correct."
- 401 or 403: "This server requires an API key."
- 404: "The Base URL does not look OpenAI-compatible. Use a base URL like `http://localhost:8000/v1`."
- 400: "The server rejected the request. Check the model name and server logs."
- Empty model: "Model is required for Local / Custom Whisper."
- Empty base URL: "Base URL is required for Local / Custom Whisper."
- Invalid URL scheme: "Base URL must start with `http://` or `https://`."

Connection test should still send the existing short silent WAV request. The result may be empty text, which is acceptable as long as the HTTP response is successful.

## Data Flow

```text
Settings
  -> AppConfig stores provider, optional API key, custom base URL, custom model
  -> Pipeline loads AppConfig
  -> create custom WhisperCompatConfig
  -> WhisperCompatProvider uploads WAV to normalized endpoint
  -> raw transcript enters existing LLM polish/output/history flow
```

## Testing

Frontend tests:

- STT provider list includes Local / Custom Whisper.
- Selecting the custom provider shows preset, base URL, model, API key, and Test.
- Speaches preset fills default base URL and model.
- Custom preset preserves user-entered values.
- Test button is enabled when base URL and model are present, even if API key is empty.

Rust tests:

- Base URL normalization appends `/audio/transcriptions`.
- Full endpoint input is accepted without duplicating the path.
- Empty or invalid base URL returns a config error.
- `custom-whisper` does not fall back to `glm-asr`.
- `WhisperCompatConfig` for `custom-whisper` uses the configured endpoint/model.

Integration/manual checks:

- Existing providers still run their current config tests.
- Custom provider can call a local OpenAI-compatible endpoint with no API key.
- Custom provider can call an endpoint that requires a Bearer token.
- Failed local server produces a user-friendly error.

## Future Work

This MVP intentionally leaves room for a larger "Managed Local STT" feature:

- Download and cache Whisper/faster-whisper models.
- Start and monitor a bundled or sidecar local STT server.
- Offer model size choices such as tiny/base/small/medium/large.
- Detect available CPU/GPU acceleration.
- Surface disk usage and model removal.

That future feature should be designed separately because it changes OpenTypeless from a client of local STT services into a runtime manager.
