# OpenTypeless Reliability, Punctuation, and Wayland Release Specification

- Date: 2026-08-08
- Status: Draft for user review; implementation has not started
- Repository: `tover0314-w/opentypeless`
- Baseline: `origin/main` at `b0062ac90ba8f0f1b1645d55971c01f2009896d6`
- Related issue: [#90 Native command-only / disabled punctuation](https://github.com/tover0314-w/opentypeless/issues/90)
- Related issue: [#87 Wayland direct text input](https://github.com/tover0314-w/opentypeless/issues/87)
- Related discussion: [#93 Custom Whisper query-string endpoints](https://github.com/tover0314-w/opentypeless/discussions/93)
- Related pull request: [#44 Deepgram truncation](https://github.com/tover0314-w/opentypeless/pull/44)

## 0. 中文范围摘要

本轮只做六件事：同步最新主线、恢复自动测试、修复 Custom Whisper 地址、修复 Deepgram 丢尾字、增加三种标点模式、让 Wayland 在支持时直接输入。

需要改 UI 的只有两处：

- `Settings → Speech Recognition` 增加“自动标点 / 只响应口令 / 完全关闭”；
- `Settings → General → Text output` 在 Wayland 下显示 `wtype` 是否可用以及剪贴板降级状态。

Custom Whisper、Deepgram、主线同步和 CI 都不增加新页面。Azure、Requesty、MiMo、OCR、录音管理、麦克风选择、本地模型和移动端不在本轮范围内。

实现拆成四个独立 PR：基础与 CI、转写可靠性、标点模式、Wayland。先完成的部分可以先合并，不做一个难以审查和回滚的大 PR。

## 1. Executive Summary

This release focuses on six concrete outcomes:

1. move development onto the latest upstream `main` without losing user-owned workspace files;
2. restore trustworthy GitHub Actions execution before accepting feature changes;
3. fix Custom Whisper endpoints whose query strings are currently corrupted by path concatenation;
4. fix the Deepgram streaming shutdown path that can discard the final words of a recording;
5. add native `Automatic`, `Command only`, and `Disabled` punctuation modes whose output does not depend on prompt obedience; and
6. allow Linux Wayland users to type directly through an installed `wtype`, with a safe clipboard fallback when direct input is unavailable.

The six outcomes are delivered as independent, reviewable slices. They share one release specification but do not share one large pull request. A completed slice may ship without waiting for an unrelated slice, provided the baseline and release gates in this document pass.

Azure OpenAI, Requesty, Xiaomi MiMo ASR, screen OCR, recording storage, microphone selection, local-model management, and mobile clients are explicitly outside this release.

## 2. Problem Statement

### 2.1 Development Baseline Is Not Trustworthy

The local `main` checkout is four commits ahead of and 32 commits behind `origin/main`. The four local commits have patch-equivalent commits upstream, while the workspace also contains user-owned untracked files. Starting feature work from the local branch would either omit current upstream fixes or create unnecessary duplicate history.

GitHub Actions jobs currently fail before execution because the repository account is billing-locked. Red checks therefore do not represent test failures, and green local checks alone are not sufficient for merge confidence across macOS, Windows, and Linux.

### 2.2 Custom Whisper Query Parameters Are Corrupted

`normalize_custom_whisper_endpoint` checks and appends `/audio/transcriptions` on the raw URL string. When a URL contains an `api-version` or other query parameter, the suffix is appended after the query value.

For example:

```text
Input:
https://host/openai/deployments/model/audio/transcriptions?api-version=2025-03-01-preview

Current invalid result:
https://host/openai/deployments/model/audio/transcriptions?api-version=2025-03-01-preview/audio/transcriptions
```

This blocks Azure's classic transcription route and any other Whisper-compatible endpoint that requires query parameters.

### 2.3 Deepgram Can Lose the Tail of a Recording

The current Deepgram provider sends `CloseStream` and immediately closes the local WebSocket. It does not first request finalization and drain the final server messages. A user who stops immediately after speaking can therefore lose the final buffered words.

The product should retain Deepgram's realtime partial transcript behavior. Replacing the provider with batch upload, as proposed in PR #44, is not required to fix the shutdown lifecycle and would be a larger latency and behavior change.

### 2.4 Users Cannot Control Automatic Punctuation Reliably

STT providers insert punctuation during recognition, and AI Polish can insert it again later. A prompt-only workaround varies by model and requires users to maintain custom scenes. Users who pause while thinking receive unwanted commas and sentence endings that interrupt dictation.

The setting must be native and deterministic. Switching STT or LLM providers must not silently change the selected punctuation policy.

### 2.5 Wayland Users Cannot Reliably Type Directly

On Wayland, the existing keyboard path cannot always inject text into the foreground application. OpenTypeless falls back to the clipboard, requiring a manual paste. `wtype` can provide direct input on compatible compositors, but it is an external system package and is not universally installed or supported.

OpenTypeless must use it only when available and must never lose output when it is absent or fails.

## 3. Users and Jobs To Be Done

### 3.1 Primary Users

- Desktop dictation users who pause while composing and want literal control over punctuation.
- Linux Wayland users who expect the same direct-output workflow available on other supported desktop environments.
- Users of custom Whisper-compatible services whose endpoint includes required query parameters.
- Deepgram users who stop recording immediately after finishing a phrase.

### 3.2 Jobs To Be Done

- When I choose a punctuation policy, apply it consistently without making me engineer an AI prompt.
- When I speak a punctuation command, insert exactly the requested mark or line break and remove the spoken command words.
- When I disable punctuation, do not reintroduce it during AI cleanup.
- When I use Wayland, type into the active application when the system supports it and preserve my text in the clipboard when it does not.
- When I stop a Deepgram recording, wait briefly for the service's final result instead of dropping the last words.
- When I configure a custom endpoint, preserve its path and query parameters exactly as a valid URL.

## 4. Goals

1. Establish a current, reproducible development baseline from `origin/main`.
2. Make GitHub Actions a meaningful merge gate again.
3. Normalize Custom Whisper endpoints structurally rather than through raw string concatenation.
4. Finalize and drain Deepgram streaming results without replacing realtime transcription with batch mode.
5. Provide three native punctuation modes with deterministic final enforcement.
6. Support direct Wayland output through a user-installed `wtype` and automatic clipboard fallback.
7. Preserve the current UI information architecture: no new top-level navigation item.
8. Add no extra LLM request to normal dictation.
9. Avoid logging transcripts, typed text, spoken punctuation commands, or URL query values.

## 5. Non-Goals

This release does not:

- add Azure OpenAI as an official LLM or STT provider;
- add Microsoft Entra ID sign-in;
- add Requesty or Xiaomi MiMo providers;
- add screen OCR or screen-context transmission;
- save, play, export, or retranscribe audio recordings;
- add a microphone selector;
- download or supervise local model servers;
- create iOS or Android clients;
- bundle or automatically install `wtype`;
- replace Deepgram streaming with batch upload;
- redesign Home, History, Settings navigation, onboarding, or the recording capsule;
- perform broad dependency or GitHub Action major-version upgrades unrelated to restoring the release gates.

## 6. Release Structure

### 6.1 Slice A: Baseline and CI

- Start the implementation branch from the fetched `origin/main` baseline.
- Preserve all user-owned untracked workspace files.
- Do not replay the four patch-equivalent local documentation commits.
- Restore the repository account state required for GitHub Actions jobs to start.
- Run the repository's frontend, Rust, format, lint, build, and audit gates on pull requests.
- Apply only the minimum dependency updates required for the agreed audit threshold to pass.

### 6.2 Slice B: Transcription Reliability

- Fix Custom Whisper URL normalization.
- Fix Deepgram finalization and tail draining.
- Add focused regression tests for both failures.

### 6.3 Slice C: Native Punctuation Modes

- Add the persisted punctuation-mode contract.
- Add deterministic command parsing and final enforcement.
- Add the Speech Recognition settings UI and localized copy.
- Add unit, pipeline, and UI tests.

### 6.4 Slice D: Wayland Direct Output

- Add runtime `wtype` capability detection.
- Add a safe direct-output adapter that writes through stdin without a shell.
- Add automatic clipboard fallback and visible status in General settings.
- Add Linux tests and manual compositor verification.

Each slice uses a separate pull request. Slice A is a prerequisite for merging B, C, or D. Slices B, C, and D do not depend on one another.

## 7. Baseline and CI Design

### 7.1 Branch and Workspace Safety

Implementation starts from `origin/main`, not from the stale local `main` tip. Before any branch operation:

1. record `git status --short --branch`;
2. confirm the exact `origin/main` commit;
3. create a named backup reference for the current local `main` tip;
4. create a `codex/` implementation branch from `origin/main` in an isolated worktree;
5. verify that the original workspace's untracked files remain untouched.

No command may delete or overwrite `.codex-zhihu-import.md`, `.superpowers/`, `docs/growth/`, or the untracked website screenshots.

### 7.2 CI Restoration

The account billing lock is an operational prerequisite. Code changes must not attempt to disguise account-level startup failures as passing tests or remove required workflows to avoid red checks.

After the account is unlocked, a no-op documentation pull request or workflow dispatch proves that jobs can start. Only then are feature pull requests considered mergeable.

Required pull-request gates:

```bash
npm ci
npm test -- --run
npm run lint
npm run format:check
npm run build
npm audit --audit-level=high

cargo fmt --manifest-path src-tauri/Cargo.toml --all -- --check
cargo test --manifest-path src-tauri/Cargo.toml
cargo clippy --manifest-path src-tauri/Cargo.toml --all-targets -- -D warnings
cargo audit --file src-tauri/Cargo.lock
```

CI images must install the native build prerequisites already required by the lockfile, including CMake for bundled Opus. Node and Rust versions are pinned to the versions used by release builds rather than floating to the newest runtime.

When an audit fails, the response is a focused lockfile/dependency correction. Existing broad and conflicting Dependabot pull requests are not merged wholesale.

## 8. Custom Whisper URL Design

### 8.1 Required Behavior

`normalize_custom_whisper_endpoint` must operate on `url::Url` components:

1. trim surrounding whitespace;
2. parse the URL;
3. accept only `http` and `https`;
4. reject embedded username/password credentials;
5. reject fragments because fragments are not HTTP request targets;
6. normalize only the path's trailing slash;
7. preserve the existing path when it already ends in `/audio/transcriptions`;
8. otherwise append `audio/transcriptions` as path segments; and
9. preserve all query parameters.

Examples:

| Input                                                           | Result                                             |
| --------------------------------------------------------------- | -------------------------------------------------- |
| `http://localhost:8000/v1`                                      | `http://localhost:8000/v1/audio/transcriptions`    |
| `http://localhost:8000/v1/`                                     | `http://localhost:8000/v1/audio/transcriptions`    |
| `https://host/v1?token=a%2Fb`                                   | `https://host/v1/audio/transcriptions?token=a%2Fb` |
| `https://host/deployments/d/audio/transcriptions?api-version=1` | unchanged                                          |
| `file:///tmp/server`                                            | configuration error                                |
| `https://user:pass@host/v1`                                     | configuration error                                |
| `https://host/v1#fragment`                                      | configuration error                                |

The connection-test and real-transcription paths must consume the same normalized endpoint. There must not be a separate frontend normalization implementation.

### 8.2 Error Handling

- Keep the user-entered Base URL visible when validation fails.
- Show a concise validation error adjacent to the existing Base URL field.
- Do not log the full query string because it may contain credentials.
- A malformed endpoint fails before microphone recording begins when it can be validated at save/test time.

### 8.3 Acceptance Criteria

- Query parameters survive endpoint normalization.
- `/audio/transcriptions` is appended exactly once.
- Test Connection and dictation use identical endpoint construction.
- Existing local Speaches and ordinary Custom Whisper URLs continue to work.
- Unit tests cover every row in the example table and repeated query keys.

## 9. Deepgram Finalization Design

### 9.1 Selected Approach

Retain the realtime WebSocket provider. On recording stop:

1. stop accepting new audio chunks for the session;
2. send Deepgram `{ "type": "Finalize" }`;
3. continue reading server messages for up to 2.5 seconds;
4. collect previously unseen final transcript segments;
5. finish draining when a finalization response is observed, the server closes, or the deadline expires;
6. send `{ "type": "CloseStream" }` when the socket is still open;
7. wait up to 500 milliseconds for the server close handshake; and
8. close the local socket and return any newly drained text through the existing `disconnect() -> Option<String>` contract.

Deepgram documents that `Finalize` flushes unprocessed audio. Its `from_finalize` response is useful but is not guaranteed when there is no significant buffered audio, so timeout and close handling remain required.

### 9.2 Duplicate Prevention

The provider tracks final segments emitted during normal streaming using the response's channel, start, and duration metadata. The disconnect drain returns only final text that the pipeline has not already received.

If a response lacks usable segment metadata, the provider uses a bounded recent-segment fingerprint based on response type and transcript text. This fingerprint exists only for the active recording and is cleared on disconnect.

### 9.3 Failure Behavior

- Finalization timeout is not a fatal error when earlier final text exists; return the accumulated result and record a content-free diagnostic.
- An authentication or protocol error received during drain remains a user-facing STT error.
- Socket close failure after valid final text does not discard the text.
- Cancellation or session replacement must abort the drain so a stale provider cannot append text to a newer recording.
- No transcript content is written to normal production logs.

### 9.4 Acceptance Criteria

- Stopping immediately after the last spoken syllable retains the final phrase in a controlled WebSocket test.
- Final segments already emitted before stop are not duplicated.
- A server that never emits `from_finalize` cannot hang the pipeline.
- A server close during drain returns text already collected.
- Partial transcript behavior during recording is unchanged.
- Stop-to-output latency increases only by the time needed for the final result and never beyond the bounded drain deadline.
- PR #44 is not merged; its truncation report may be closed after the replacement fix is released and manually verified.

## 10. Native Punctuation Design

### 10.1 User-Facing Modes

Add a `Punctuation` control to Settings → Speech Recognition, below Language and above Single recording duration.

The persisted values are:

```text
automatic
command_only
disabled
```

Visible behavior:

| Mode         | Behavior                                                                                                            |
| ------------ | ------------------------------------------------------------------------------------------------------------------- |
| Automatic    | Preserve today's provider and AI punctuation behavior.                                                              |
| Command only | Remove automatically generated punctuation; insert punctuation and line breaks only for recognized spoken commands. |
| Disabled     | Remove punctuation and formatting commands from the final output, producing normalized plain words and spaces.      |

The config default is `automatic`. Missing or unknown persisted values migrate to `automatic`, preserving all existing users' behavior.

### 10.2 First-Release Command Vocabulary

The first release provides deterministic command tables for English and Simplified Chinese.

English commands:

| Spoken command                          | Output          |
| --------------------------------------- | --------------- |
| `period`, `full stop`                   | `.`             |
| `comma`                                 | `,`             |
| `question mark`                         | `?`             |
| `exclamation mark`, `exclamation point` | `!`             |
| `colon`                                 | `:`             |
| `semicolon`                             | `;`             |
| `new line`                              | one line break  |
| `new paragraph`                         | two line breaks |
| `open quote`, `open quotation mark`     | `"`             |
| `close quote`, `close quotation mark`   | `"`             |

Simplified Chinese commands:

| Spoken command       | Output          |
| -------------------- | --------------- |
| `句号`               | `。`            |
| `逗号`               | `，`            |
| `问号`               | `？`            |
| `感叹号`             | `！`            |
| `冒号`               | `：`            |
| `分号`               | `；`            |
| `换行`               | one line break  |
| `新段落`、`另起一段` | two line breaks |
| `左引号`             | `“`             |
| `右引号`             | `”`             |

Matching is case-insensitive for English, uses word boundaries, and applies longest-match-first. Chinese uses exact phrase matching. Additional languages extend data tables in later releases without changing the pipeline contract.

When Speech Recognition Language is Auto Detect, the parser considers both English and Simplified Chinese tables. For an explicitly selected unsupported language, the UI keeps `Automatic` and `Disabled` available and labels `Command only` as supporting English and Chinese spoken commands in this release.

Provider adapters that expose a stable automatic-punctuation option should disable that option in `command_only` and `disabled`. The local final filter remains authoritative because not every provider exposes such a switch.

There is one unavoidable input limitation: if an STT provider converts the spoken word `comma` directly into `,` before OpenTypeless receives the transcript, the application cannot prove whether that mark came from a command or automatic punctuation. In that case command-only mode removes the mark. It never guesses that an unexplained provider mark was user-requested. The Settings helper copy must explain that spoken commands work when the recognition provider returns the command words.

### 10.3 Deterministic Pipeline

Punctuation enforcement must survive both STT punctuation and AI Polish.

```text
raw STT text
  -> spoken-command parser
  -> text with immutable punctuation markers
  -> optional AI Polish using the existing single request
  -> marker integrity validation
  -> final punctuation filter
  -> marker restoration and spacing normalization
  -> output insertion and history
```

Requirements:

1. The parser replaces recognized spoken commands with ordered, session-local markers and records their expected count and order.
2. The AI prompt treats markers as immutable formatting metadata, not instructions from the transcript.
3. After AI Polish, the validator requires the exact marker sequence.
4. If the model removes, duplicates, or reorders markers, discard the polished result and use the deterministic pre-polish text for this dictation.
5. In `command_only`, remove Unicode punctuation from the chosen text, restore the requested markers, then normalize spaces around marks and line breaks.
6. In `disabled`, remove Unicode punctuation and recognized spoken command phrases, collapse horizontal whitespace, and remove line-break macros.
7. In `automatic`, bypass this pipeline and preserve current behavior.
8. The transformer runs before output placement, so direct typing, clipboard output, History, Ask draft insertion, and selected-text replacement receive the same final text.
9. No second AI request is permitted.

URLs, email addresses, code, apostrophes, and hyphenated words may lose punctuation in `command_only` or `disabled`; this is the explicit effect of selecting a literal punctuation-restriction mode. The helper copy warns that these modes are intended for users who want manual formatting control.

### 10.4 UI Requirements

- Use the existing segmented-control visual language.
- Labels: `Automatic`, `Command only`, `Disabled` and localized equivalents.
- Show one concise description for the selected mode.
- `Command only` includes a `View spoken commands` disclosure rather than showing the entire command table by default.
- The disclosure is keyboard accessible and fits the 720×480 minimum window without horizontal scrolling.
- Mode state is conveyed through text and selection semantics, not color alone.
- The control has an accessible group label and each option exposes selected state.

### 10.5 Acceptance Criteria

- Existing users remain in `Automatic` after upgrade.
- The same fixture produces the same final punctuation with AI Polish on or off.
- The same raw-transcript fixture produces the same final punctuation regardless of the selected provider adapter.
- `Today comma we ship full stop` produces `Today, we ship.` in English command-only mode.
- `今天逗号发布句号` produces `今天，发布。` in Chinese command-only mode.
- The two examples produce punctuation-free word output in Disabled mode.
- A model that corrupts markers triggers deterministic fallback instead of leaking markers or returning unreliable formatting.
- No marker is visible in output, History, clipboard, or logs.
- Focused tests cover commands embedded in ordinary phrases, repeated commands, adjacent commands, quotes, empty input, multilingual input, and marker-corruption fallback.
- A provider fixture that collapses a spoken command into unexplained punctuation removes that punctuation rather than treating it as an explicit command.
- Issue #90 closes only after Glenn's reported workflow is manually verified.

## 11. Wayland Direct-Output Design

### 11.1 Product Behavior

The existing Text output choices remain `Type directly` and `Paste from clipboard`.

When `Type directly` is selected:

| Environment                                 | Output strategy                                                        |
| ------------------------------------------- | ---------------------------------------------------------------------- |
| macOS                                       | existing native keyboard path                                          |
| Windows                                     | existing native keyboard path                                          |
| Linux X11                                   | existing Linux keyboard path                                           |
| Linux Wayland with usable `wtype`           | pipe text to `wtype -`                                                 |
| Linux Wayland without usable `wtype`        | copy text to clipboard and notify once                                 |
| Linux Wayland where `wtype` execution fails | copy text to clipboard, retain the text, and expose the failure status |

When `Paste from clipboard` is selected, OpenTypeless never invokes `wtype`.

### 11.2 Capability Detection

The Rust platform layer owns detection and exposes a runtime-only status:

```text
not_wayland
available
not_installed
incompatible
last_execution_failed
```

Detection runs:

- once during application startup on Linux;
- when General settings opens;
- after the user selects Refresh; and
- after a direct-output execution failure.

The status is not persisted as truth because packages, PATH, sessions, and compositors can change between launches.

OpenTypeless does not invoke a shell, interpolate user text into command arguments, or write text to a temporary file. It spawns `wtype` directly, passes `-`, writes UTF-8 text to stdin, closes stdin, and waits with a bounded timeout.

### 11.3 Clipboard Fallback

Before attempting Wayland direct output, keep the final text available for fallback. If `wtype` is missing, incompatible, exits unsuccessfully, or times out:

1. copy the exact final text to the clipboard;
2. do not attempt a second typing mechanism that could duplicate partial output;
3. show a single non-blocking message explaining that the text was copied;
4. update the General settings status; and
5. never include the output text in logs or error messages.

If `wtype` may have emitted only part of the text before failing, the notification explicitly warns that partial text may already be present and that the full text is in the clipboard. OpenTypeless cannot safely erase unknown partial output from another application.

### 11.4 General Settings UI

Under the existing Text output control, show a Linux Wayland-only status row when `Type directly` is selected:

- `Direct typing available through wtype`;
- `wtype is not installed — output will be copied to the clipboard`;
- `wtype cannot type in this Wayland session — output will be copied to the clipboard`; or
- `Direct typing failed last time — output was preserved in the clipboard`.

The unavailable states include:

- a Refresh action;
- concise manual installation commands for Debian/Ubuntu and Arch/Manjaro behind a disclosure; and
- a link to the project's Wayland documentation.

OpenTypeless does not request administrator privileges and does not run package-manager commands.

Status is communicated with text, not only an icon or color. Refresh is keyboard accessible and exposes a loading state.

### 11.5 Acceptance Criteria

- Compatible KDE Plasma/KWin Wayland can type non-ASCII UTF-8 text directly through `wtype`.
- GNOME or another incompatible compositor falls back without losing the final text.
- A missing binary never causes dictation failure.
- Explicit clipboard mode never launches `wtype`.
- Text is passed through stdin and never through shell command construction.
- A timeout or nonzero exit preserves the full result in the clipboard.
- Direct typing cannot run after focus/session ownership has moved to a newer dictation.
- UI status updates after install/removal or a failed execution.
- Issue #87 closes after successful manual verification on the reporter's KDE/Manjaro-style environment and one unsupported/fallback environment.

## 12. Data and Interface Changes

### 12.1 Persisted Configuration

Add one backward-compatible setting:

```rust
pub enum PunctuationMode {
    Automatic,
    CommandOnly,
    Disabled,
}

pub struct AppConfig {
    // existing fields
    pub punctuation_mode: PunctuationMode,
}
```

Serialization uses stable snake-case values. Rust owns the default and validation; TypeScript mirrors the validated contract for rendering.

No Wayland capability result is persisted. Existing text-output settings remain authoritative.

### 12.2 Runtime Interfaces

Add focused interfaces rather than extending unrelated components:

- `PunctuationTransformer`: parse commands, validate markers, and enforce final output.
- `WaylandOutputCapability`: describe runtime `wtype` availability.
- `WaylandTextOutput`: perform bounded stdin-based output and return a typed result.
- Deepgram internal final-segment metadata: support drain and duplicate prevention without changing the UI event shape.
- Shared Custom Whisper URL normalizer: used by save/test/transcribe paths.

Each unit is independently testable and contains no UI concerns.

## 13. Error and Recovery Rules

| Failure                                   | User result                                                                           | Recovery                                         |
| ----------------------------------------- | ------------------------------------------------------------------------------------- | ------------------------------------------------ |
| Invalid Custom Whisper URL                | Existing text remains editable; recording does not start through the invalid provider | Correct URL and retest                           |
| Deepgram finalization timeout             | Return already finalized text; no indefinite spinner                                  | Next recording opens a new socket                |
| Deepgram finalization protocol/auth error | Stable STT error; do not invent transcript                                            | Correct credentials or retry                     |
| Punctuation marker corruption by AI       | Use deterministic pre-polish output                                                   | No second request; next dictation remains usable |
| Unknown punctuation config value          | Use Automatic                                                                         | Persist valid value on next save                 |
| `wtype` missing/incompatible              | Full text copied to clipboard                                                         | Install/refresh or paste manually                |
| `wtype` partial execution failure         | Warn that partial output may exist; full text copied                                  | User pastes full text if needed                  |
| CI account still locked                   | No feature merge                                                                      | Restore account before review continues          |

## 14. Privacy and Security

- Do not log raw transcripts, polished output, spoken commands, clipboard content, or text passed to `wtype`.
- Do not log full Custom Whisper query strings or embedded credentials.
- Reject userinfo in Custom Whisper URLs instead of storing credentials in the URL.
- Never invoke `wtype` through a shell.
- Do not automatically download binaries or run a package manager.
- Punctuation markers are internal structured state. They are not user instructions and must be removed before any output or history write.
- This release adds no telemetry and sends no additional data to OpenTypeless services.

## 15. Testing and Verification

### 15.1 Automated Tests

Custom Whisper:

- path-only base URL;
- existing transcription path;
- query string and repeated query keys;
- encoded query values;
- trailing slash before query;
- invalid scheme, credentials, fragment, and empty input.

Deepgram:

- normal partial/final sequence;
- final message returned after `Finalize`;
- no `from_finalize` response;
- duplicate final segment;
- close during drain;
- timeout;
- cancellation and stale session.

Punctuation:

- English and Chinese command tables;
- Automatic pass-through;
- Command-only cleanup and restoration;
- Disabled cleanup;
- spacing, new line, new paragraph, and quotes;
- AI marker preservation and corruption fallback;
- identical output with AI Polish enabled and disabled;
- config migration and Settings control behavior.

Wayland:

- status mapping for missing, available, incompatible, and failed command;
- successful UTF-8 stdin write;
- timeout and nonzero exit;
- clipboard fallback;
- explicit clipboard mode bypass;
- session cancellation and no transcript logging.

### 15.2 Manual Matrix

| Area               | Required environments                                                         |
| ------------------ | ----------------------------------------------------------------------------- |
| General regression | current macOS and Windows release targets                                     |
| Deepgram           | short utterance, long utterance, and immediate stop after speech              |
| Custom Whisper     | local Speaches plus a query-string endpoint                                   |
| Punctuation        | English and Chinese, AI Polish on/off, at least two STT providers             |
| Wayland direct     | KDE Plasma/KWin with `wtype` installed                                        |
| Wayland fallback   | missing `wtype` and one compositor/session where direct typing is unavailable |
| Linux regression   | X11 direct output remains unchanged                                           |

### 15.3 UI and Accessibility Verification

- Settings fits 720×480 and 900×700 without horizontal overflow.
- Segmented punctuation options are keyboard operable and expose selected state.
- Spoken-command disclosure can be opened, read, and closed by keyboard.
- Wayland status is understandable without color.
- Refresh returns focus and communicates loading/completion.
- All new user-facing strings exist in every currently shipped UI locale; English and Simplified Chinese command vocabularies remain explicitly identified as the first-release parser languages.

## 16. Rollout and Rollback

### 16.1 Rollout Order

1. Restore CI execution and prove the gates run.
2. Merge the Custom Whisper and Deepgram reliability slice.
3. Release native punctuation modes with default `Automatic`.
4. Release Wayland `wtype` support after compatible and fallback environment checks.

Punctuation and Wayland changes may share a tagged desktop release but remain separate pull requests.

### 16.2 Rollback

- Custom Whisper: revert the normalizer while preserving stored user input; no config migration is destructive.
- Deepgram: revert the drain lifecycle to the prior streaming implementation without schema changes.
- Punctuation: default all users to Automatic if a release-blocking transformer defect is found; the new config field remains forward-compatible.
- Wayland: disable `wtype` strategy selection and retain clipboard fallback; no persisted capability migration is required.

No rollback may remove user history or overwrite configuration unrelated to the affected feature.

## 17. Success Criteria

The release succeeds when:

1. GitHub Actions jobs start and all required gates pass on the release commit.
2. The implementation branch contains all current upstream fixes and preserves user-owned workspace files.
3. Every Custom Whisper URL fixture normalizes correctly, including query-string endpoints.
4. Deepgram immediate-stop fixtures and manual tests retain the final phrase without duplication.
5. Existing users see no punctuation behavior change until they select another mode.
6. Command-only and Disabled modes produce deterministic final output with AI Polish on and off.
7. KDE/KWin Wayland types directly through `wtype` in the verified environment.
8. Missing or unusable `wtype` preserves the complete text in the clipboard.
9. macOS, Windows, Linux X11, History, Ask, and selected-text output paths show no regression attributable to the new routing.
10. Issues #90 and #87 are closed only after their reported workflows are manually verified in a released build.

## 18. Deferred Follow-Up Specifications

The following work requires separate product and technical decisions:

1. Azure OpenAI static-key and embedded Entra authentication.
2. Requesty LLM provider.
3. Xiaomi MiMo ASR provider.
4. Microphone selection and unavailable-device recovery.
5. Local OCR context with explicit privacy and prompt-injection boundaries.
6. Recording playback, retention, retranscription, export, and deletion.
7. Packaged local-model download and service supervision.
8. Google Search Grounding and source presentation.
9. iOS and Android products.

## 19. Decisions and Open Questions

All decisions needed for the implementation plan are resolved:

- the release uses independent slices rather than one large pull request;
- punctuation is enforced locally rather than relying only on prompts;
- Automatic remains the migration default;
- English and Simplified Chinese are the first deterministic spoken-command vocabularies;
- Deepgram remains realtime and gains a bounded finalization drain;
- `wtype` is user-installed, detected at runtime, and never invoked through a shell;
- Wayland failure always preserves full text in the clipboard; and
- the deferred providers and larger UI initiatives are not part of this release.

The next document after approval of this specification is a detailed implementation plan with file-level tasks and test-first sequencing.
