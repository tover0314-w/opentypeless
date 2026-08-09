# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [1.2.0] - 2026-08-09

### Added
- Android Voice Studio 0.2 with an independent IME, `RecognitionService`, and `RecognizerIntent` entry point.
- Android on-device/system recognition plus explicit BYOK OpenAI-compatible STT, optional LLM editing, per-app profiles, deterministic personal terms, corrections, and encrypted opt-in history.
- Target-bound Android commits, selection preservation, undo/raw restore, explicit Teach, fact-integrity checks, adaptive VAD, and cancellation-safe recording.
- Portable desktop/Android vocabulary import and export, Android model support/download checks, and English/Simplified Chinese IME localization.
- Android JVM, lint, minified-build, real SQLite/Keystore/Recognition emulator tests, and CI artifacts.
- Reproducible desktop dependency inventory, complete offline license material, and finished-artifact legal-file verification.

### Changed
- The fork is BYOK-only; account, subscription, checkout, quota, donation, managed-cloud proxy, and upstream update paths remain removed.
- Desktop release numbering now continues above the inherited `v1.1.53` tag line to prevent update-order regressions.
- Release/support/security metadata now points to this maintained fork.
- Desktop updater artifacts are disabled consistently across configuration and release workflows; signed platform releases remain fail-closed on missing maintainer credentials.

### Security
- Android API keys and optional history text are protected with separate Android Keystore AES-GCM keys; backup and device transfer are disabled.
- AI output is rejected when protected facts or the selected-edit target cannot be preserved.
- Provider redirects, oversized responses, unsafe public HTTP endpoints, stale editor commits, and late cancelled callbacks are rejected.

## [1.1.48] - 2026-07-08

### Added
- Typeless-style default shortcuts: macOS `Fn`, Windows `Right Alt`, with separate Ask and translate shortcuts.
- Linux keeps conservative defaults: `Ctrl+/` for dictation and `Ctrl+.` for Ask Anything.
- Lightweight Ask Anything flow with capsule recording/thinking states and a compact answer note.
- Selected-text context for Ask and polish, with safe truncation and explicit intent routing.
- Built-in scenes, local custom scenes, scene activation metadata, and import/export.
- Local correction rules alongside the custom dictionary.
- macOS Apple Speech provider and stronger custom Whisper/self-hosted STT diagnostics.
- OS credential vault storage for BYOK STT/LLM secrets where available.
- Output strategy diagnostics, clipboard restore safeguards, and Windows SendInput support.

### Changed
- Simplified Settings while keeping key controls discoverable, including idle capsule visibility in General Advanced.
- Restyled Upgrade to match the quieter jelly-card product UI instead of a heavy marketing layout.
- Refined AI polish styles: Minimal, Clean, Structured, and Professional.
- Ask now starts directly from Try Ask / legacy Ask entrypoints instead of opening an empty window.
- Release notes and README now document the platform-specific shortcut defaults more explicitly.

### Fixed
- Strict Rust Clippy checks now pass.
- Onboarding Skip now continues into the app even if best-effort config persistence fails.
- Improved hotkey registration rollback, collision checks, and status reporting.
- Reduced accidental git noise from local release, screenshot, and debug artifacts.
- Completed i18n key coverage across bundled locale files and removed stale onboarding copy that implied clicking the capsule starts recording.

## [0.1.0] - 2026-02-26

### Added
- Initial open-source release under MIT license
- Global hotkey voice recording with hold-to-record and toggle modes
- Floating capsule widget — always-on-top, draggable, with recording/transcribing/polishing states
- 6 STT providers: Deepgram Nova-3, AssemblyAI, OpenAI Whisper, Groq Whisper, GLM-ASR, SiliconFlow
- 11 LLM providers: OpenAI, DeepSeek, Zhipu, Claude, Gemini, Moonshot, Qwen, Groq, Ollama, OpenRouter, SiliconFlow
- Real-time streaming keyboard output — text appears character-by-character as the LLM generates it
- Clipboard output mode as alternative to keyboard simulation
- Selected text context — highlight text before recording to give the LLM additional context
- Translation mode — speak in one language, output in another (20+ target languages)
- Custom dictionary for domain-specific terms and proper nouns
- Per-app detection — adapts formatting based on the active application
- Local history with full-text search and date grouping
- Dark / light / system theme with smooth transitions
- Onboarding wizard for first-time setup
- System tray with quick actions (show/hide, start recording, quit)
- Auto-start on login
- Optional Cloud (Pro) subscription for managed STT/LLM quota
- BYOK (Bring Your Own Key) mode — fully functional without any cloud dependency
- Cross-platform support: Windows, macOS, Linux
- CI/CD with automated builds for all three platforms
