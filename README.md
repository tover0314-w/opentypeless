# OpenTypeless BYOK + Android

[中文说明](README_zh.md)

This fork turns OpenTypeless into a BYOK-only voice input project and adds a native Android voice keyboard. It has no OpenTypeless account, subscription, checkout, quota, or managed-cloud runtime path.

- Fork: [dengxuezhao/opentypeless](https://github.com/dengxuezhao/opentypeless)
- Upstream: [tover0314-w/opentypeless](https://github.com/tover0314-w/opentypeless)

## What works

| Platform | Input surface | STT | Optional polish |
| --- | --- | --- | --- |
| macOS / Windows / Linux | Global shortcut and floating capsule | Built-in provider list or custom Whisper-compatible endpoint | OpenAI-compatible LLM, including local Ollama |
| Android 8.0+ | System input method (IME) in any editable field | OpenAI-compatible `/audio/transcriptions` | OpenAI-compatible `/chat/completions` |

Desktop keeps the upstream dictation, selected-text editing, translation, Ask Anything, local dictionary, history, scenes, and app-aware writing workflows. Commercial account screens and the upstream managed proxy have been removed. Legacy settings that selected `cloud` are migrated to editable BYOK defaults.

## Privacy and security model

- Provider requests go directly from the device to the configured STT/LLM endpoint.
- Desktop secrets use the operating-system credential vault where supported.
- Android API keys are encrypted with a non-exportable AES-GCM key in Android Keystore; Android backup is disabled.
- Android treats transcript text as untrusted data in the polish prompt and commits only the returned text to the active field.
- HTTPS is recommended. Android permits cleartext HTTP solely so a user can opt into a localhost/LAN self-hosted endpoint; HTTP can expose audio and text on an untrusted network.
- Automatic desktop updates are disabled until this fork publishes and signs its own update artifacts. This prevents an upstream release from reinstalling removed commercial code.

No hosted service is bundled. Usage costs, retention, and privacy are determined by the endpoint the user chooses.

## Android quick start

1. Build or install `android/app/build/outputs/apk/debug/app-debug.apk`.
2. Open OpenTypeless and configure an STT base URL, API key if required, and model.
3. Optionally configure an LLM endpoint and enable polish.
4. Grant microphone permission, enable “OpenTypeless Voice Keyboard,” then choose it from the system input-method picker.
5. In any editable field, tap **Speak**, talk, then tap **Stop**. The keyboard transcribes, optionally polishes, and inserts the result.

The Android keyboard records mono PCM16 at 16 kHz, limits a single recording to 60 seconds, supports cancellation, and offers safe undo only when the text before the cursor still matches its last insertion.

### Build Android

Requirements: JDK 17, Android SDK Platform 35, and Build Tools 35.0.0.

```bash
cd android
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew testDebugUnitTest lintRelease assembleDebug assembleRelease
```

## Build desktop

Requirements: Node.js 20+, Rust stable, and the platform prerequisites documented by Tauri.

```bash
npm ci
npm run build
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri build
```

Useful verification commands:

```bash
npm run lint
npm run format:check
npm test
cargo fmt --check --manifest-path src-tauri/Cargo.toml
cargo clippy --all-targets --manifest-path src-tauri/Cargo.toml -- -D warnings
```

## Android source layout

```text
android/app/src/main/java/com/opentypeless/android/
├── MainActivity.java               # BYOK configuration and IME setup
├── audio/                          # AudioRecord and deterministic WAV encoding
├── ime/                            # InputMethodService and cancellable voice pipeline
├── net/                            # URL validation and OpenAI-compatible HTTP client
├── security/                       # Android Keystore secret encryption
└── settings/                       # Non-secret preferences and runtime settings
```

## Scope and limitations

- Android v0.1 is a voice-first companion, not a feature-for-feature port of every desktop settings pane.
- The Android client currently targets OpenAI-compatible STT and chat-completion APIs; provider-specific realtime WebSocket protocols remain desktop-only.
- Network integration tests require credentials and are intentionally not run in CI. Unit tests cover URL validation, WAV structure, and prompt safety; lint and APK assembly verify the Android surface.
- A release APK should be signed with a private release keystore. The repository never contains signing secrets.

## License and attribution

MIT licensed; see [LICENSE](LICENSE). This fork is derived from OpenTypeless and preserves the upstream copyright and license. It is independently maintained and is not the upstream project's hosted service.
