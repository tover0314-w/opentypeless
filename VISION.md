# Vision

## What is OpenTypeless?

OpenTypeless is an open-source, cross-platform voice-input system for Android, Windows, macOS, and Linux. It combines speech-to-text, deterministic personal vocabulary, and optional LLM refinement; exact dictation does not require generative AI.

## Origin

OpenTypeless was built in a single day with the help of Claude Code — demonstrating that AI-assisted development can produce real, usable software rapidly. The project is now open source so the community can shape its future.

## Core Principles

- **Privacy first**: BYOK (Bring Your Own Key) model. Your API keys stay on your machine. No mandatory cloud accounts, no telemetry.
- **Cross-platform**: Android native voice entry points plus Windows, macOS, and Linux via Tauri.
- **Open source**: MIT licensed. Transparent development, community-driven roadmap.
- **Provider agnostic**: Support multiple STT and LLM providers. No vendor lock-in.

## Current Priorities

1. Stability and reliability across all platforms
2. User experience polish
3. Broader STT provider support (Deepgram, AssemblyAI, Whisper variants, etc.)
4. Broader LLM provider support (OpenAI, DeepSeek, Anthropic, etc.)
5. Internationalization
6. Reproducible recognition-quality, latency, battery, and noun-correction evaluations

## Future Directions

- Plugin / extension system for custom workflows
- Voice commands and shortcuts
- Bundled opt-in offline model backends where size, licensing, and device capability permit
- Team / collaboration features

## What We Won't Merge

- Features that break the privacy-first principle (mandatory cloud accounts, telemetry)
- Forced vendor lock-in to a single provider
- Complexity that doesn't serve a clear user need
