# Task Report: VOC-011

## Result

DONE

## Scope

- Implemented: canonical `voice_engine_v2`、旧 `enabled` 原值迁移、同步 A/B/rollback、进程内串行化、
  source/compiled gate 与小米真机验证。
- Not implemented: legacy writer 删除（VOC-012）、Flag 删除条件（REL-004）、远程配置、语音算法或 UI 重构。

## Changes

- `VoiceEditorTransactionConfig.java`: canonical/legacy key compatibility、同步迁移与显式切换。
- `VoiceEditorTransactionConfigInstrumentedTest.java`: 默认、A/B、migration、canonical precedence 与恢复。
- architecture source/compiled gates: exact key/method/edge/caller/mutual-exclusion contract 与 fault fixture。
- architecture/release/security/backlog/test specs: Flag register、回滚边界和真实证据。

## Architecture

- contracts: 每次 voice target capture 恰读一次；选择冻结到当前 session；失败不得跨 writer fallback。
- state changes: SharedPreferences canonical key 为 `voice_engine_v2`，旧 `enabled` 在首次读取时原值迁移。
- migration: canonical 优先；legacy-only 保留 boolean；写盘失败本次仍使用已读值并在下次重试。
- feature flag: Debug/Release 默认 true；`setEnabled(false)` 同步回滚并只影响下一 session。

## Security & privacy

- data sent/stored: 只存一个 boolean；无正文、音频、Secret、网络或诊断 payload。
- permissions/components: 无新增权限或 Android component。
- threat considerations: synchronized 防进程内 migration/toggle 竞态；禁止 async apply；Flag 不关闭敏感、
  Session、selection、fingerprint、composition 或 evidence 硬规则。

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `:app:testDebugUnitTest --rerun-tasks` | PASS | 783/783；122 suites；0 skipped/failure/error |
| source architecture suite + production scan | PASS | 98/98；production scan PASS |
| `:architecture-gate:test` | PASS | 96/96 |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| `:app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` | PASS | 61 tasks executed |
| Xiaomi exact Instrumentation | PASS | config default/A-B/migration/precedence, 1/1 |
| fresh-cache `scripts/verify_android.sh` | PASS | 187 tasks；184 executed / 3 up-to-date；2m24s |

## Evidence

- app-debug SHA-256: `bf343282ca7843d3726b337133b186c572d7c7f6fa33cd1fd9044dee469367c7`
- app androidTest SHA-256: `c6e99b4a44650b79bbb635e802914f6bde4502d1b6a4d68a53fc191fe0e37b82`
- release unsigned SHA-256: `24b03d9e5bffc894cb99af8ad6483dc81f667896cb7f03a8f22bfdd591841034`
- device: Xiaomi 10 Ultra `be4e2015`, Android 13/API33, HyperOS OS1.0；两个 APK unattended install PASS；
  exact test `OK (1 test)`，0.019s；测试结束仍 Dozing、无 keyguard、background app-op `ignore`。

## Risks

- legacy writer 在 VOC-012 前仍存在；回滚安全依赖既有 exact shrinking inventory 与 session-level mutual exclusion。
- SharedPreferences 是同进程开关，不是远程配置系统；发布操作仍需 REL-004 清单化。

## Rollback

- 调用 `setEnabled(false)` 并在下一次 voice capture 验证 legacy route；不得在 active session 中双写或换路。
- 代码回滚需保留旧 `enabled` compatibility，避免已存 canonical false 被忽略；如需移除 Flag，先完成 REL-004。

## Follow-ups

- `VOC-012`
- `REL-004`

## Git

- branch: `agent/android-offline-followup`
- commit: 未创建
- worktree status: shared dirty worktree；本任务变更未 stage、未 push
