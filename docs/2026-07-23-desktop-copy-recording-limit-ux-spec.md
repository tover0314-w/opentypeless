# Desktop Copy and Recording-Limit UX Design

- Date: 2026-07-23
- Status: Confirmed scope implemented; explicitly pending sections remain unchanged
- Scope: OpenTypeless desktop app
- Primary concern: explain provider-aware recording limits in plain language without changing Cloud latency, billing, quota, or recording behavior

## Confirmed Scope

The owner has confirmed only the following desktop changes:

1. Keep the existing OpenTypeless Cloud promotional/access card unchanged.
2. Rename `最长录音` to `单次录音时长`.
3. For normal OpenTypeless Cloud operation, show `自动（推荐，最长 10 分钟）`.
4. Replace the current mode + preset + always-visible numeric input with one duration dropdown.
5. Put Auto, applicable presets, and `自定义时长…` in that dropdown.
6. Show the numeric input and visible `秒` unit only after `自定义时长…` is selected.
7. When the saved duration is shorter than the provider maximum, show both values clearly, for example: `当前设置为 30 秒，云端最长支持 10 分钟。`
8. Preserve an existing custom duration instead of silently changing it to Auto.
9. Keep the stored config model, Cloud backend, recording limits, billing, quota, and Neon behavior unchanged.
10. Apply the confirmed copy and interaction consistently across all shipped locales.
11. Show only one normal helper paragraph and derive its wording from the Rust capability reason; never describe an app buffer, app safety policy, or unknown upstream limit as a provider-supported maximum.

The following items remain unconfirmed and must not be implemented yet:

- managed-Cloud 30-second fallback copy or behavior;
- a manual `重新检测` action;
- Home-page display mappings;
- macOS Accessibility copy;
- update-notice copy or layout.

## 1. Decision Summary

The desktop does not need a new settings page or a broad visual redesign.

This change should:

1. make the recording-duration setting easier to understand;
2. keep all normal navigation, recording, transcription, quota, and billing paths unchanged.

It must not add a usage-summary feature, bundle Local Whisper, add background polling, or create additional routine Neon traffic.

The existing OpenTypeless Cloud promotional/access card is explicitly unchanged.

## 2. Final User Experience

### 2.1 Normal OpenTypeless Cloud state

For a signed-in user with Cloud access and a valid managed capability, Speech Recognition settings should read:

```text
语音识别

服务商
[ OpenTypeless 云端                         ▾ ]

✓ 云端语音识别已可用，无需配置 API Key。

语言
[ 自动检测                                  ▾ ]

单次录音时长
[ 自动（推荐，最长 10 分钟）                 ▾ ]
已根据 OpenTypeless 云端服务自动设置。
```

Important behavior:

- `10 分钟` is the resolved recommendation, not hard-coded frontend marketing copy.
- The value changes with the selected provider and current transport capability.
- Opening this page uses the locally synchronized capability and does not make a new Cloud or Neon request.

### 2.2 Cloud capability fallback state — pending decision

This state has not been approved for modification. Keep the current fallback behavior and UI unchanged until it is reviewed separately.

The earlier design proposal is retained below only as discussion context, not as implementation scope.

When Cloud authentication remains valid but the app cannot obtain a current recording capability:

```text
单次录音时长
[ 自动（当前 30 秒）                         ▾ ]

ⓘ 云端语音识别仍可使用；当前未获取到录音时长，
  单次暂按 30 秒。

[ 重新检测 ]
```

This replaces:

> 云端能力信息不可用或已过期，因此使用安全的 30 秒限制。

The new copy must make three facts explicit:

1. Cloud speech recognition still works;
2. 30 seconds is a temporary fallback, not the normal Cloud product limit;
3. the user can retry without restarting the desktop app.

`重新检测` behavior:

- appears only for the managed-Cloud fallback state;
- is disabled while checking;
- allows only one in-flight request;
- refreshes account/capability once, then re-resolves the local recording limit;
- does not run while a recording is active;
- does not introduce polling, focus-based refreshes, or repeated automatic requests;
- on success, changes the selection to `自动（推荐，最长 10 分钟）`;
- on failure, leaves the usable 30-second fallback in place.

Success feedback:

```text
已恢复自动设置，单次最长 10 分钟。
```

Failure feedback:

```text
暂时仍无法获取录音时长，当前可继续使用 30 秒录音。
```

### 2.3 Other STT providers

The same setting displays the resolved recommendation for each provider:

```text
单次录音时长
[ 自动（推荐，最长 30 秒）                   ▾ ]
这是该语音服务支持的单次最长录音时长。
```

or:

```text
单次录音时长
[ 自动（推荐，最长 10 分钟）                 ▾ ]
已根据当前语音服务自动设置。
```

The UI must not imply that every provider supports 10 minutes. The Rust capability resolver remains the authority.

### 2.4 Custom duration interaction

Use one primary duration control instead of showing a mode selector, a preset selector, and a numeric field at the same time.

The dropdown contains:

```text
自动（推荐，最长 10 分钟）
30 秒
1 分钟
2 分钟
5 分钟
10 分钟
自定义…
```

Rules:

- only durations at or below the resolved hard maximum are shown;
- selecting a preset stores `recording_limit_mode = custom` and its duration;
- selecting Auto stores `recording_limit_mode = auto`;
- `自定义…` reveals the numeric input;
- when a provider has no meaningful custom range beyond the minimum, omit
  `自定义时长…`; keep Auto and the applicable fixed preset so an existing saved
  selection remains visible.

Custom input:

```text
自定义时长
[ 300                               ] 秒
可选 30 秒–10 分钟；上限由当前云端能力决定。
```

The unit must be visible next to the input. The range must use human-readable durations instead of `30–600 秒`.
The custom input and its source-aware range explanation replace the normal current/max helper; they must not be followed by a second, repetitive helper paragraph.

For non-managed providers, keep the same one-paragraph structure while naming the actual source of the limit:

- provider-owned limit: identify the provider limit;
- Apple Speech: identify the on-device recognition-session limit;
- client buffer: identify the app’s local audio-buffer limit;
- product safety: identify the app’s stability limit;
- custom or unknown upstream: say that the server limit is unknown;
- managed fallback: retain the existing safe 30-second fallback explanation.

If a persisted value is above the current provider limit:

```text
当前语音服务单次最多录制 10 分钟，已为你调整。
```

The corrected effective value must be shown immediately.

### 2.4.1 Existing shorter Cloud selection

If Cloud supports 10 minutes but the user currently has a shorter saved duration, the primary control must show the duration that will actually be used:

```text
单次录音时长
[ 30 秒                                    ▾ ]
当前设置为 30 秒，云端最长支持 10 分钟。
```

The Auto option remains available as:

```text
自动（推荐，10 分钟）
```

Requirements:

- never display `自动（推荐，最长 10 分钟）` as the selected value while the effective saved selection is 30 seconds;
- distinguish the current selected duration from the provider maximum;
- preserve the user's saved custom duration during upgrade;
- do not silently migrate a custom 30-second choice to Auto;
- make returning to the recommended 10-minute Cloud setting a single dropdown selection.
- when the hard maximum equals the 30-second minimum, omit `自定义时长…` because it cannot produce a different valid value.
- for a provider-owned fixed limit, avoid repeating identical selected and maximum values; use a compact explanation such as `此服务商单次最长 30 秒。`

### 2.5 Approaching and reaching the limit

Ten seconds before the effective limit:

```text
还有 10 秒达到本次录音上限，已录内容会正常转写。
```

When the limit is reached:

```text
本次已录满 10 分钟，正在转写。已录内容不会丢失。
```

Requirements:

- the duration is dynamic;
- the toast does not append internal reason keys or transport details;
- recording still stops through the Rust-owned deadline;
- captured audio is submitted exactly as it is today;
- the same behavior applies to dictation and Ask.

### 2.6 Cloud access card

No change.

Keep the current title, signed-out copy, upgrade copy, active-state copy, Pro positioning, and upgrade action. In particular, this work does not change the existing `99 种语言` or `words/月` wording in that card.

### 2.7 Recording-limit explanation copy

| Capability reason  | Final Chinese copy                                                              |
| ------------------ | ------------------------------------------------------------------------------- |
| Provider duration  | `受当前服务商的单次音频时长限制。`                                              |
| Apple Speech       | `受 Apple Speech 设备端识别会话限制。`                                         |
| Client buffer      | `受应用本地音频缓冲区限制。`                                                    |
| Unknown upstream   | `自定义服务的限制未知，因此使用保守的默认值。`                                  |
| Unknown provider   | `暂未识别此服务商，因此使用安全的回退值。`                                      |
| Product safety     | `这是应用为流式语音服务设置的保守安全上限。`                                    |
| Managed capability | `已根据 OpenTypeless 云端服务自动设置。`                                        |
| Encoder fallback   | `托管音频编码器不可用，本次录音已使用较短的本地 WAV 安全上限。`                 |
| Managed fallback   | `云端能力信息不可用或已过期，因此使用安全的 30 秒限制。`                        |

The normal helper is rendered as one source-aware paragraph. When a custom
preset is selected, the UI combines the current value, hard maximum, and
capability reason in that paragraph. A corrected-value warning may appear as a
second amber line only when the persisted value had to be clamped.

### 2.8 Home page

Pending decision. Do not implement this section yet.

The “Current configuration” card must never display raw identifiers such as `cloud`, `keyboard`, or `groq-whisper`.

Final Chinese labels:

| Field          | Final label/value behavior                   |
| -------------- | -------------------------------------------- |
| `语音识别服务` | use the localized label from `STT_PROVIDERS` |
| `AI 润色服务`  | use the localized label from `LLM_PROVIDERS` |
| `AI 润色`      | `已开启` / `已关闭`                          |
| `文字写入方式` | `直接打字` / `复制后粘贴`                    |

Example:

```text
当前配置

语音识别服务        OpenTypeless 云端
AI 润色服务         OpenTypeless 云端
AI 润色             已开启
文字写入方式         直接打字
```

This is a display mapping only. Stored config values do not change.

### 2.9 macOS Accessibility copy

Pending decision. Do not implement this section yet.

macOS only:

```text
若要使用 Fn 快捷键或直接输入文字，请在“系统设置”中允许
OpenTypeless 使用“辅助功能”。

[ 去系统设置 ]
```

Requirements:

- do not concatenate two partial translations with `-`;
- use one complete sentence;
- show it only when the selected shortcut/output behavior actually needs macOS Accessibility;
- Windows and Linux must never display macOS permission terminology.

Linux Wayland keeps its platform-specific clipboard explanation. Windows keeps its platform-specific output behavior.

### 2.10 Update notice

Pending decision. Do not implement this section yet.

Keep the existing dismissible update notice and installation behavior. Only clarify what the action does:

```text
发现新版本
版本 {{version}} 已准备好。安装完成后应用会自动重启。

[ 安装并重启 ]   [ × ]
```

While installing:

```text
正在安装…
```

No forced update and no new modal are introduced.

## 3. Information Hierarchy and Visual Treatment

- normal explanatory text uses the existing secondary text color;
- managed fallback uses an amber information treatment, not a red error treatment;
- Cloud active uses the existing positive/green treatment;
- important fallback text must be at least the normal 12 px helper size;
- numeric inputs have a visible label and visible unit, not ARIA-only context;
- the retry action sits beside or immediately below the fallback message;
- no new page, navigation item, modal, or usage dashboard is added.

## 4. Behavior and Data Boundaries

### 4.1 Unchanged

- provider capability matrix;
- Rust-owned recording deadline;
- WAV and Ogg/Opus transport selection;
- TalkMore 10-minute managed maximum;
- Vercel function and request-size handling;
- Cloud authorization;
- quota and billing calculations;
- Neon schema and queries;
- stop-to-transcript flow;
- captured-audio submission;
- BYOK provider behavior.

### 4.2 Small frontend behavior changes

- map stored provider/output identifiers to localized labels;
- flatten the recording-duration controls into one primary selector;
- show numeric input only for `自定义…`;
- replace user-facing reason and toast copy.

Only the recording-duration selector and its confirmed normal-state copy are currently approved. The other bullets in this document remain pending where marked.

## 5. Cost and Latency Guardrails

### 5.1 Neon/Vercel cost

The UI change must not undo the existing compute optimization.

Acceptance guardrails:

- opening Settings causes zero new subscription-status requests;
- switching between settings panes causes zero new subscription-status requests;
- focusing the desktop causes zero new subscription-status requests;
- an idle signed-in desktop performs no periodic subscription polling;
- one press of `重新检测` causes at most one deduplicated subscription refresh;
- repeated presses while refreshing do not create parallel requests;
- failure does not start a retry loop.

### 5.2 User-perceived latency

- recording start does not wait for a capability refresh;
- stopping a recording does not wait for a capability refresh;
- transcription and output do not wait for the settings UI;
- the retry action cannot run during active recording;
- normal short Cloud recordings keep the current WAV path;
- no additional network hop is added to STT.

Therefore, the expected Cloud recording and transcription latency is unchanged.

## 6. Cross-Platform Requirements

### Windows

- provider and duration copy is identical in meaning to other platforms;
- no code-signing-related warning is added to the in-app recording flow;
- duration selector and unit remain readable under Windows text scaling;
- no macOS Accessibility wording appears.

### macOS

- use the complete Accessibility sentence and `去系统设置`;
- Apple Speech explanation uses user-facing wording;
- Fn and direct typing behavior remain unchanged.

### Linux

- provider and duration copy is identical in meaning to other platforms;
- Wayland clipboard limitations remain explicit and separate from recording limits;
- no macOS Accessibility wording appears;
- AppImage/runtime workaround copy, if needed elsewhere, must remain Linux-specific.

## 7. Localization

Chinese and English are the source copy for implementation. Japanese, Korean, French, German, Spanish, Portuguese, Russian, and Italian must have the same keys and state coverage before release.

Localization requirements:

- no raw English fallback in a non-English UI;
- duration pluralization continues to use locale-aware keys;
- provider names that are brands remain untranslated, while surrounding descriptions are localized;
- CI verifies key parity across all shipped locale files.

English source equivalents:

| State              | English source copy                                                                                                                     |
| ------------------ | --------------------------------------------------------------------------------------------------------------------------------------- |
| Cloud active       | `Cloud speech recognition is ready. No API key required.`                                                                               |
| Managed capability | `Set automatically for OpenTypeless Cloud.`                                                                                             |
| Managed fallback   | `Cloud speech recognition still works. The recording limit could not be retrieved, so recordings are temporarily capped at 30 seconds.` |
| Retry              | `Check again`                                                                                                                           |
| Retry success      | `Automatic settings restored. You can record for up to {{duration}}.`                                                                   |
| Warning            | `{{seconds}} seconds until this recording reaches its limit. What you have recorded will still be transcribed.`                         |
| Reached            | `This recording reached {{duration}} and is being transcribed. Your recorded audio will not be lost.`                                   |

## 8. Expected Implementation Surface

Primary files:

- `src/components/Settings/SttPane.tsx`
- `src/components/HomePage/index.tsx`
- `src/components/MainLayout/AccessibilityBanner.tsx`
- `src/components/UpdatePrompt.tsx`
- `src/hooks/useTauriEvents.ts`
- `src/i18n/locales/*.json`

Expected tests:

- `src/components/Settings/__tests__/SttPane.test.tsx`
- `src/components/MainLayout/__tests__/AccessibilityBanner.test.tsx`
- `src/components/__tests__/UpdatePrompt.test.tsx`
- `src/hooks/__tests__/useTauriEvents.test.tsx`
- Home-page display mapping tests
- locale-key parity tests
- subscription-refresh policy tests

No TalkMore server, database migration, Vercel configuration, or Rust capability-registry change is expected.

## 9. Acceptance Criteria

1. A normal Cloud user sees `自动（推荐，最长 10 分钟）` after capability sync.
2. The normal Cloud settings path makes no extra status request and adds no recording latency.
3. A fallback user sees that Cloud still works and that 30 seconds is temporary.
4. A fallback user can manually retry without restarting the app.
5. One retry produces at most one deduplicated refresh and never starts polling.
6. Reaching a limit says that transcription is continuing and recorded content will not be lost.
7. Custom duration shows a visible unit and a human-readable range.
8. Values above the provider maximum are corrected with a plain-language explanation.
9. Home displays localized provider and output names, never raw stored identifiers.
10. macOS permission copy is a complete sentence; Windows and Linux do not see it.
11. All shipped locales contain the new keys and no non-English locale falls back to English.
12. Existing recording, managed upload, BYOK, quota, and billing tests continue to pass.
13. No usage-summary UI, bundled Local Whisper, or new background Cloud request is introduced.

## 10. Out of Scope

- redesigning Account or Upgrade;
- adding a usage summary;
- changing quota presentation or billing units;
- bundling Local Whisper or a local model downloader;
- changing provider limits;
- changing the TalkMore Cloud price or entitlement model;
- changing the existing Cloud promotional/access card;
- changing Cloud audio encoding;
- changing Issue #83 recording startup behavior;
- changing updater signing or release infrastructure;
- adding a database test environment.
