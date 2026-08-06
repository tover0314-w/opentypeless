# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/), and this project adheres to [Semantic Versioning](https://semver.org/).

## [Unreleased]

### Added
- Azure OpenAI as an AI polish provider, using the `api-key` header Azure expects.
- Azure OpenAI speech-to-text through a Custom Whisper preset, selectable during onboarding alongside the endpoint and deployment it needs.
- Microsoft Entra ID sign-in for Azure OpenAI: leave the API key blank and tokens are taken from the Azure CLI sign-in, cached in memory and renewed before expiry. This is the only option for tenants that set `disableLocalAuth=true`.

### Fixed
- Custom Whisper endpoints that carry a query string are no longer corrupted. `/audio/transcriptions?api-version=...` previously had a second `/audio/transcriptions` appended, which broke every Azure OpenAI transcription URL.

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
