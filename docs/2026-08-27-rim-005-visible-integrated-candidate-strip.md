# Task Report: RIM-005 visible integrated candidate strip regression

## Result

DONE

## Scope

- Implemented: 恢复 Rime 当前稳定候选页、分页和次选；候选条改为键盘内部同 surface 的平面区域；补齐旧页、隐私、
  generation、pending 操作与精确选择回归门禁。
- Not implemented: 字母几何、底部垫高、中英文全半角快捷符号、Voice 新按钮、中文状态回车提交英文；未修改或打包真实
  小鹤个人资源。

## Changes

- `OpenTypelessImeService.java`: 在两条 accepted Rime state/page 路径恢复稳定页渲染；任何 pending 或策略不满足时清空。
- `KeyboardCandidateBar.java`: 候选条与键盘同底色，候选默认透明且无卡片间距，保留 48dp 触控和横向分页。
- RIM-005/KBD-007 architecture tests: 禁止再次全局隐藏候选，并锁定稳定页门禁与非悬浮样式。
- Android instrumented tests: 覆盖平面候选条、voice-first 进入 QWERTY、真实 system IME 分页及次选提交。
- Rime resource policy: 审核并登记本次 product/test APK 精确哈希；无真实小鹤资源进入 APK。

## Architecture

- contracts: 候选身份仍绑定 producer、editor generation、coordination generation、page revision、page index、candidate
  index、candidate id 与 expected text。
- state changes: 仅 accepted 且无 pending 操作的 Rime snapshot 可见；每个新按键开始处理时先清空旧页。
- migration: 无。
- feature flag: 沿用现有 QWERTY/Rime 路径，无新增 flag。

## Security & privacy

- data sent/stored: 无网络、无新增持久数据；设备验证只使用 2,191-byte 合成临时词库，结束后清理。
- permissions/components: 无新增权限、导出组件、Provider 或 editor capability。
- threat considerations: 敏感字段、no-learning、错误 editor、错误 generation、非 Rime 引擎和 stale/pending 页全部 fail closed。

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `python3 -m unittest test_verify_rime_resource_policy.py` | PASS | 37/37 |
| `scripts/verify_android.sh all` | PASS | 120 script tests、270 architecture tests、191 Gradle tasks、Release lint、五个 APK 与资源扫描 |
| `KeyboardCandidateBarInstrumentedTest` | PASS | API35 arm64 emulator 7/7；含同 surface、透明候选、零卡片间距 |
| `RimePreeditInstrumentedTest#importedSchemaProcessesAsciiUnicodeCandidatesBackspaceAndClose` | PASS | actual librime 1/1；三页、翻页、`庚` 次选、重复选择拒绝及清理 |
| `TestHostInstrumentedTest#selectedImeRimeCandidatePagingCommitsExactSelectionWhenRequested` | PASS | system-selected OpenTypeless 1/1；`ni` 后第二页第 2 项精确上屏 `庚` |
| Xiaomi 真机 / 真实小鹤 `xkvi` | NOT RUN | 本轮只连接 API35 emulator；不得用合成词库冒充个人小鹤结果 |

第一次执行 actual-librime 清理路径时，旧设备断言错误地期待提交后的重复选择返回
`STALE_COORDINATION_GENERATION`，实际按既有 JVM 契约返回 `INACTIVE`。断言对齐后重新构建、安装并 **1/1 PASS**；
产品实现未为通过测试而改变 terminal state。

## Evidence

- 外部 ADB 真实触控：`ni` 时显示 `1 甲 / 2 乙 / 3 丙 / 4 丁 / 下一页`，候选与键盘同 surface、无白色悬浮卡片。
- Debug APK: 65,523,463 bytes，SHA-256
  `4b4adbe12c9c5f2964cdf49a05266e197d2684ce8145d7409758ec9da19507c2`。
- unsigned Release APK: 63,628,769 bytes，SHA-256
  `138005b7ee111c2cf668fe909d8efed2cf5ff4b6157ac7fcdf84507698dacd98`。
- clean app AndroidTest APK: 1,103,306 bytes，SHA-256
  `6d7d6822d4dadf02c3a9238dbc0a9e6b9006d27b24c50520b3f0580a1033a88f`。
- test-host Debug/AndroidTest APK: SHA-256
  `908d7582c6c668466311aeb2c124167eb5985900abcece78e499bc6970e1a4e3` /
  `08dcbb0902593a5d7a7e0aa8e6282318c6d11d34fd077671c84d8d8335167ac1`。

## Risks

- 当前截图与设备 E2E 使用合成候选，只证明候选 UI/分页/次选链路；真实小鹤候选顺序仍需 Xiaomi 连接后验收。
- 字母布局和底部高度仍是独立问题，本任务没有夹带调整。

## Rollback

- 回滚本交付提交即可；基线 parent 为 `74b95cd0f39bececf7d86cf69f3b721da48d9121`。

## Follow-ups

- KBD-002
- KBD-006
- KBD-009
- KBD-015
- RIM-004

## Git

- branch: `agent/android-offline-followup`
- commit: 本报告所在交付提交（最终哈希见分支 HEAD）
- worktree status: 提交后仅保留用户已有且未纳入版本控制的 `docs/2026-08-19-session-handoff.md`
