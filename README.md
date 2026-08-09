# OpenTypeless BYOK + Android Voice Studio

[中文说明](README_zh.md)

This independent MIT fork removes the account, subscription, checkout, quota, donation, and
managed-cloud product paths from OpenTypeless. It keeps the desktop BYOK application and adds
OpenTypeless Android 0.2: a local-first voice-input layer designed to be safer and more adaptable
than a mandatory-cloud “ASR + LLM” keyboard.

- Fork: [dengxuezhao/opentypeless](https://github.com/dengxuezhao/opentypeless)
- Upstream project: [tover0314-w/opentypeless](https://github.com/tover0314-w/opentypeless)

## Android 0.2 highlights

- **Three Android entry points:** an independent voice IME, a standard `RecognitionService`, and a
  `RecognizerIntent` activity. The service lets compatible full keyboards keep their letters,
  swipe input, emoji, and clipboard while using OpenTypeless for speech.
- **Local-first routing:** a newly installed app chooses Android on-device recognition when it is
  genuinely available, otherwise the installed system service, otherwise an explicit BYOK or
  self-hosted OpenAI-compatible endpoint. “System service” is labelled separately because its
  network behavior belongs to that provider and is not guaranteed offline.
- **AI is optional:** Exact mode and structured fields do not require an LLM. Smart editing,
  selected-text editing, and translation run only after the user enables an OpenAI-compatible LLM.
- **Personal names that actually reach ASR:** confirmed canonical spellings, pronunciations,
  aliases, corrections, and app scope are injected as an ASR prompt where supported, as Android
  biasing strings where supported, and as a deterministic one-pass post-ASR correction.
- **Portable vocabulary:** Android imports its earlier personalization backups and desktop
  `opentypeless_dictionary` v1 files. Its export is a desktop-readable v1 superset while retaining
  Android aliases, app scopes, and enable flags for Android-to-Android round trips.
- **Explicit learning:** the keyboard never silently learns everything typed. A correction is saved
  only from the dictionary UI or after the user presses **Teach** and confirms the smallest
  `wrong → correct` pair.
- **Fact-safe, reversible editing:** numbers, amounts, dates, URLs, email addresses, code-shaped
  tokens, negations, and confirmed personal terms are checked after AI editing. Unsafe output is
  rejected in favor of the exact transcript. Every insertion has safe Undo; AI dictation also has
  one-tap Raw restore.
- **Target-bound IME commits:** every recording is bound to the exact editor epoch, app, field,
  `InputConnection`, selection, and text around the cursor. Switching apps, fields, selections,
  password fields, or cursor position cancels or discards the old result.
- **Private local state:** API keys and opt-in history text use separate non-exportable Android
  Keystore AES-GCM keys. History is off by default, can be deleted entry-by-entry or all at once,
  is capped locally, and is never included in dictionary export.
- **Per-app behavior:** an explicit app profile can choose Auto, Exact, Smart, or Translate mode,
  a target language, a writing preference, and whether limited preceding context may be sent.
- **Voice UX:** partial results for Android recognizers; silence auto-stop, leading-silence trim,
  cancellation tokens, and an upper recording limit for upload-based recognition.

No model weights are bundled. See [Android third-party notices](android/THIRD_PARTY_NOTICES.md).

## Processing policy

| Field or operation | Auto behavior | Generative AI |
| --- | --- | --- |
| Password / sensitive | Voice disabled | Never |
| URL, email, number, person name, search | Exact transcript + confirmed local rules | Skipped |
| Message, long text, general prose | Conservative Smart edit when enabled; exact fallback | Optional |
| Selected text | Explicit spoken edit with target revalidation | Required, original preserved on failure |
| Translate | Faithful translation to configured target | Required, no raw instruction inserted on failure |

`IME_FLAG_NO_PERSONALIZED_LEARNING` disables context collection, history writes, and usage learning.
Existing confirmed dictionary entries may still help recognition; they are not modified.

## Privacy and network boundaries

- Android on-device recognition is selected only through
  `SpeechRecognizer.createOnDeviceSpeechRecognizer`; availability depends on the device and
  installed language model.
- Android system recognition may be local or cloud-backed. OpenTypeless reports it as a distinct
  route and does not claim it is offline.
- BYOK audio goes directly to the configured `/audio/transcriptions` endpoint. Optional Smart,
  Translate, or selected-text content goes directly to `/chat/completions`.
- HTTP redirects are rejected, provider error bodies are not shown, response sizes are bounded,
  and credentials/control characters are validated before request headers are written.
- HTTPS is required for public hosts. Plain HTTP is accepted only for an explicitly configured
  localhost, link-local, or private-LAN self-hosted endpoint. Bearer credentials require HTTPS
  except on a loopback address; use an empty key for cleartext LAN services.
- Password fields never start recording. Android backup and device transfer are disabled for app
  data. Settings, history, and management screens use `FLAG_SECURE`.

## Install and use Android

1. Install `android/app/build/outputs/apk/debug/app-debug.apk` or a properly signed release APK.
2. Open **OpenTypeless Voice Studio**, grant microphone access, and confirm the selected speech
   route. Android on-device is preferred only when the platform reports it available.
3. Optionally configure BYOK STT and an LLM. AI, history, and preceding-context sharing start off.
4. Enable the OpenTypeless IME. To use either Android standard speech entry, first configure a
   ready BYOK STT endpoint, explicitly enable **Standard Android speech entry**, and add the exact
   caller package name to its allowlist. Then select OpenTypeless as the speech-recognition service
   or launch its `RecognizerIntent` activity from that allowed app. Some proprietary keyboards
   hard-code their own speech provider; use the independent IME or system keyboard switcher in that
   case.
5. Choose Auto, Exact, Smart, or Translate, then speak. For selected-text editing, select text before
   starting voice input; the same selection must still exist when the result returns.

Both exported standard speech entries intentionally use the BYOK STT route only and are disabled
by default. Their package allowlist and request limiter prevent an arbitrary microphone-enabled app
from spending the user's provider quota. Calling the Android system recognizer from inside a
registered recognition service could resolve back to itself. The independent IME supports all
three recognition backends.

## Build and verify Android

Requirements: JDK 17, Android SDK Platform 35, and Build Tools 35.x.

```bash
cd android
export JAVA_HOME=/path/to/jdk-17
export ANDROID_HOME=/path/to/android-sdk
./gradlew clean testDebugUnitTest lintRelease assembleDebug assembleRelease assembleDebugAndroidTest
./gradlew connectedDebugAndroidTest  # with an API 35+ emulator/device online
```

The automated suite covers deterministic personalization, NFKC span mapping, prompt boundaries,
fact integrity, VAD, cancellation state, editor-target identity, HTTP redirects/errors/headers,
RecognitionService contracts, real SQLite import transactions, and Android Keystore history
encryption/migration. CI runs JVM, lint, APK assembly, and API 35 emulator tests without real API
keys.

The exact accepted matrix, artifacts, and known limits are recorded in the
[2026-08-09 acceptance report](docs/2026-08-09-byok-android-acceptance.md).

The release build produced by a local checkout is unsigned unless a signing configuration is
provided. Never distribute it as a trusted release without signing and publishing checksums.

## Desktop

Desktop keeps global dictation, selected-text operations, translation, Ask, dictionary, history,
scenes, and app-aware workflows, routed through local or user-configured providers. Commercial
account/runtime paths are not present in this fork. Build it with:

```bash
npm ci
npm run build
cargo test --manifest-path src-tauri/Cargo.toml
npm run tauri build
```

## Honest scope

Android 0.2 is a voice layer, not a newly invented full QWERTY/swipe keyboard. Its standard Android
entry points are the compatibility strategy. Android on-device recognition is not available on
every device or for every language, and the project does not yet publish a cross-device CER/WER,
latency, battery, or blind Typeless benchmark. The repository therefore claims verifiable product
advantages—offline-capable routing, provider freedom, explicit term learning, target-bound commits,
fact guards, and reversible AI—not universal recognition accuracy superiority.

## License

MIT licensed; see [LICENSE](LICENSE). This fork preserves upstream copyright and attribution, does
not use Typeless branding/assets/code, and is not the upstream hosted service.
