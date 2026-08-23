# Task Report: VOC-008

## Result

DONE

## Scope

- Implemented: Teach 只从成功 transaction 同栈返回的 exact `CommitRecord`，或已持久化并重新读取的
  `HistoryEntry` 读取 Raw、Final 与 scope；菜单、Activity factory、resolver、JVM/Instrumentation 与
  source/compiled gate 同步迁移。
- Not implemented: DAT-004 FeedbackEvent、学习建议、History schema 变更、Undo/Raw 行为或新的 UI。

## Changes

- `OpenTypelessImeService.java`: `LastVoiceCommit` 只额外保留 final `teachRecord`，Teach 不再读取 legacy
  copied plaintext；没有 exact record 的 rollback route 隐藏入口。
- `HistoryActivity.java`: 新增唯一 `createTeachIntent(Context, CommitRecord, long)` factory。
- `TeachCorrectionResolver.java`: 冻结 VOICE、learning、Raw 与 committed text eligibility，并让 record 正文
  优先于 optional History metadata。
- JVM/AndroidTest 与 source/compiled architecture gates: 覆盖 provenance、隐私、caller、shape 和 edge drift。
- 02/05/06/07/08 规范、FULL 镜像、PACKAGE/manifest: 同步任务状态与真实验证证据。

## Architecture

- contracts: receipt/record 是 Teach 数据 provenance，不是 editor 写权限；Service 不能从复制字段或 Intent
  extra 重建 Teach authority。
- state changes: 当前 transaction 的 `LastVoiceCommit` 可持有一个 final record 引用；legacy fallback 为 null。
- migration: 无持久格式或数据迁移。
- feature flag: 无新增 flag；沿用当前 transaction/legacy route，后者 fail closed 隐藏 Teach。

## Security & privacy

- data sent/stored: 无新增网络或持久数据；Teach draft 仍只在用户显式确认流程中创建。
- permissions/components: 无新增权限、Android component 或 exported surface。
- threat considerations: 敏感提交无 record；no-learning record 不得 Teach/History/Feedback/持久化；公开伪造
  record、Provider/UI caller、copied plaintext fallback 与 recency lookup 均不能授权入口。

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `:app:testDebugUnitTest --rerun-tasks` | PASS | 783/783；122 suites；0 skipped/failure/error |
| source architecture suite + production scan | PASS | 97/97；production scan PASS |
| `:architecture-gate:test` | PASS | 96/96 |
| `:architecture-gate:verifyCompiledArchitecture` | PASS | Debug/Release 2/2 |
| `:app:assembleDebug :app:assembleDebugAndroidTest --rerun-tasks` | PASS | 61 tasks executed |
| Xiaomi exact Instrumentation | PASS | `ManagementStateInstrumentedTest#teachCorrectionDraftSurvivesActivityRecreation`, 1/1 |
| fresh-cache `scripts/verify_android.sh` | PASS | 187 tasks；184 executed / 3 up-to-date；2m26s |

## Evidence

- app-debug SHA-256: `88600be46935306ddfaabf620b60d00cc867dff8ea9cddf5784abd94999cb2a9`
- app androidTest SHA-256: `373013235d12a16b4fef2dc2a6a6a2fd40a51203c94dc27e275b1b190e1405f1`
- release unsigned SHA-256: `d58a8e4fe495aade9ed130d1d2ea636376643b6136a583c20567f4696f2ea52b`
- device: Xiaomi 10 Ultra `be4e2015`, Android 13/API33, HyperOS OS1.0；两个 APK unattended overlay
  安装成功，exact test 输出 `OK (1 test)`，46.236s。
- HyperOS 首次后台 Activity 确认由用户选择“始终允许”；临时 app-op 已恢复，屏幕为 Dozing，keyguard 未锁。

## Risks

- legacy/rollback route 没有同栈 record 时 Teach 不显示；这是 fail-closed 行为，不回退到 copied plaintext。
- `CommitRecord` public factory 仍不是授权边界；后续消费者必须保持 exact provenance/gate。

## Rollback

- 回滚 Service 的 `teachRecord` 字段、Activity factory、resolver 与对应 gate/tests；无 schema 或数据回滚。

## Follow-ups

- `DAT-004`
- `SEC-005`

## Git

- branch: `agent/android-offline-followup`
- commit: 未创建
- worktree status: shared dirty worktree；本任务变更未 stage、未 push
