# BYOK + Android acceptance report

Date: 2026-08-08

## Accepted scope

- The repository is forked to `dengxuezhao/opentypeless` with `upstream` retained for future comparison.
- The desktop runtime is BYOK-only: account, authentication, subscription, checkout, quota, managed STT/LLM proxy, upgrade UI, and upstream automatic-update paths are removed.
- Existing desktop dictation, local history/dictionary, scenes, translation, selected-text editing, and Ask workflows remain available through local or user-configured providers.
- A native Android 8.0+ system input method is provided under `android/`.

## Android acceptance

- App ID: `com.opentypeless.android`
- Version: `0.1.0` (`versionCode` 1)
- `minSdk`: 26; `targetSdk`: 35
- API keys are encrypted with AES-GCM using a non-exportable Android Keystore key.
- Backup/device-transfer extraction is disabled for app data and secrets.
- HTTPS is the default. Plain HTTP input is rejected unless the host is localhost, `.local`, loopback, link-local, or a private LAN address.
- Voice input is disabled in password fields, the settings screen is protected from screenshots, recording is capped at 60 seconds, cancellation disconnects active provider requests, provider responses are size-limited, and undo only removes an exact last insertion.
- If optional LLM polish fails after successful transcription, the raw transcript is inserted instead of being lost.
- The app installed and cold-launched on an Android API 36 ARM64 emulator. Microphone permission was granted, the IME was enabled and selected, its two-row voice keyboard rendered in the system Settings search field, and Android reported no fatal runtime exception.

## Automated verification

| Surface | Result |
| --- | --- |
| Frontend formatting and ESLint | Pass |
| Frontend tests | 32 files, 320 tests passed |
| TypeScript/Vite production build | Pass |
| BYOK boundary script | Pass |
| npm audit, high severity | 0 vulnerabilities |
| Rust formatting and Clippy with warnings denied | Pass |
| Rust tests | 476 passed |
| Rust audit | 0 unignored vulnerabilities; inherited transitive maintenance/unsoundness warnings remain |
| Android JVM tests | 6 passed |
| Android release Lint | Pass with the documented cleartext-LAN warning |
| Android debug and minified release assembly | Pass |
| Android debug APK signature | APK Signature Scheme v2 verified with the standard Android debug certificate |
| macOS Tauri application bundle | Pass |

## Artifacts

- Installable development APK: `android/app/build/outputs/apk/debug/app-debug.apk`
- Unsigned release candidate: `android/app/build/outputs/apk/release/app-release-unsigned.apk`
- macOS bundle: `src-tauri/target/release/bundle/macos/OpenTypeless.app`

The debug APK is intentionally not presented as a production release. A distributable release must be signed with a private release keystore controlled by the maintainer. External STT/LLM integration was not exercised because the acceptance environment contains no provider credentials; endpoint construction, request boundaries, URL policy, and failure behavior are covered locally.
