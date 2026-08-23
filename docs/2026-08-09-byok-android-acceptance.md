# BYOK + Android acceptance report

Date: 2026-08-09

## Accepted scope

- The maintained fork lives at `dengxuezhao/opentypeless`; the `upstream` remote is retained for
  comparison.
- The desktop runtime is BYOK/local-provider only. Account, authentication, subscription,
  checkout, quota, donation, managed STT/LLM proxy, and inherited automatic-updater paths are not
  product surfaces in this fork.
- Desktop dictation, local history and dictionary, scenes, translation, selected-text editing, and
  Ask workflows remain available through local or user-configured providers.
- Android 8.0+ has three native entry points: an independent IME, an opt-in standard
  `RecognitionService`, and a `RecognizerIntent` Activity.

## Android 0.2 acceptance

- App ID: `com.opentypeless.android`; version `0.2.0` (`versionCode` 2); `minSdk` 26;
  `targetSdk` 35.
- A new installation prefers Android on-device recognition only when the platform reports the
  recognizer available. Android system recognition is a separately labelled route and is not
  represented as necessarily offline. OpenAI-compatible STT and optional LLM editing require the
  user's own endpoint and credentials.
- Confirmed terms, pronunciations, aliases, common misrecognitions, correction rules, and optional
  per-app scope reach ASR prompt/biasing where supported and a deterministic, non-cascading
  post-correction pass. Learning is explicit: history never silently becomes a correction rule.
- Android imports legacy Android personalization backups and desktop
  `opentypeless_dictionary` v1. Its export is a desktop-readable compatible superset that preserves
  Android-only metadata for Android-to-Android round trips.
- Processing modes include exact dictation, deterministic cleanup, optional AI polish, and
  translation. Structured fields bypass generative rewriting; selected text is preserved whenever
  an AI edit fails or violates integrity checks.
- Commits are bound to the original editor package, input shape, selection, and surrounding-text
  fingerprint. Undo and raw restore use guarded `InputConnection` mutations with rollback-aware
  failure handling.
- API keys and opt-in history text use separate, non-exportable Android Keystore AES-GCM keys.
  Existing plaintext history is migrated in place, the SQLite WAL is truncated after migration,
  app backup/device transfer is disabled, and sensitive activities and the IME use screenshot
  protection.
- `IME_FLAG_NO_PERSONALIZED_LEARNING` blocks field context, history/usage updates, and Teach for
  that field. Previously confirmed vocabulary may still be used, matching Android's no-learning
  contract.
- Password fields cannot record. Provider redirects, oversized responses, stale callbacks,
  unsafe public cleartext endpoints, and bearer credentials over cleartext LAN transports are
  rejected. Plain HTTP without a credential remains available for explicitly configured local or
  private self-hosting; bearer credentials require HTTPS except on loopback.
- Recording defaults to 180 seconds and is configurable from 5 to 540 seconds. Audio capture has a
  bounded buffer, bounded zero-read retries, terminal handling for negative reads, adaptive VAD,
  generation-safe cancellation, and watchdogs for platform recognizers.
- Standard Android speech entry is disabled by default. Enabling it requires ready BYOK STT, an
  explicit caller allowlist, `RECORD_AUDIO` attribution, and per-caller rate limiting, preventing an
  arbitrary microphone-capable app from spending the user's provider quota.
- Android 13+ support checks and model-download requests are generation-safe. Android 13 retains
  the recognizer through the request-dispatch window; Android 14+ retains an in-progress download
  across temporary Activity stops and falls back when download event listening is unavailable.
- English and Simplified Chinese resources cover the app, IME states, errors, actions, and
  accessibility labels. Manual API 36 checks covered normal and 2.0 font scale layouts, system
  English and Simplified Chinese configurations, IME selection, a missing-system-model error path,
  and process/window leak logs.

## Automated verification

All results below were produced from the final local worktree on 2026-08-09.

| Surface | Result |
| --- | --- |
| Frontend formatting, ESLint, and TypeScript | Pass |
| Frontend tests | 33 files, 322 tests passed |
| TypeScript/Vite production build | Pass; only documented chunk-size/dynamic-import warnings |
| BYOK commercial-runtime boundary | Pass |
| npm audit at high severity | 0 vulnerabilities |
| Rust formatting and Clippy with warnings denied | Pass |
| Rust tests | 478 passed |
| Rust audit | 0 unignored vulnerabilities; 18 transitive maintenance/unsoundness warnings remain |
| Reproducible desktop dependency material | 19 npm runtime, 423 Cargo runtime-linked, 122 Cargo build-only, 311 unique license/notice texts |
| Android JVM tests | 37 suites, 144 tests passed |
| Android API 36 device tests | 16 passed; SQLite, Keystore, migration, Activity state, Recognition contracts, and Binder entry covered |
| Android Debug and Release Lint | Pass; `No issues found.` for both variants |
| Android Debug, minified Release, and AndroidTest assembly | Pass |
| Android debug APK signature | APK Signature Scheme v2, standard Android debug certificate |
| macOS Tauri application bundle | Pass; 15 MiB unsigned `.app` generated |
| Bundled legal material | `LICENSE` plus all three third-party files present and byte-identical to the repository |

## Artifacts

- Installable development APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Unsigned Android release candidate:
  `android/app/build/outputs/apk/release/app-release-unsigned.apk`
- Android instrumentation APK:
  `android/app/build/outputs/apk/androidTest/debug/app-debug-androidTest.apk`
- Unsigned macOS bundle:
  `src-tauri/target/release/bundle/macos/OpenTypeless.app`

The debug APK is intentionally not a production release. The Android release candidate and local
macOS bundle are intentionally unsigned. Public binary publication requires maintainer-controlled
Android, Apple, Windows, and Linux signing material as applicable, successful CI on the exact tag,
and artifact signature/checksum verification. Release workflows fail closed when required secrets
are absent.

## Honest limits

- No Android speech model is bundled; device/language availability belongs to the installed system
  recognition provider.
- This is a voice-input layer, not a full QWERTY/swipe keyboard. It cancels when the IME is hidden
  instead of continuing background dictation across apps.
- No live external STT/LLM provider call was made without maintainer credentials. Local tests cover
  request construction, redirects, response bounds, cancellation, and failure behavior.
- API 33/34 model download was exercised through contract tests, not a physical OEM matrix.
- No cross-device CER/WER, latency, battery, long-form, or blind Typeless benchmark has been
  published. The accepted advantages are the verifiable ones: MIT/BYOK freedom, local-first
  routing, explicit noun memory, portable corrections, target-bound editing, privacy controls,
  deterministic fallback, and reversible AI. They are not proof that every language or acoustic
  environment has a lower recognition error rate than Typeless.
