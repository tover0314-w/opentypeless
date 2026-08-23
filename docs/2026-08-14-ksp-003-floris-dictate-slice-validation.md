# Task Report: KSP-003

## Result

DONE — 完成的是 ADR-0011 允许的仓库外 Floris/Dictate 隔离垂直切片；OpenTypeless 生产 APK、默认 IME 与底座选择均未改变。

## Scope

- Implemented:
  - 在固定 FlorisBoard `v0.5.2` commit `2e82060251897226c0739b9f52d1d051b02305fb` 的隔离副本中接入真实
    `EditorSessionManager` / `EditorTransactionManager`；
  - QWERTY、candidate completion 与 toolbar `InsertText` 统一经 adapter 生成受校验事务；
  - Voice 按键提供确定性的 `vo` → `voice` partial、final receipt 与 exact-ID Undo 演示；
  - IME start/selection/finish 生命周期同步到唯一、长寿命 transaction manager；
  - 真正的 `BaseInputConnection` / `Editable` Android instrumentation 覆盖普通、敏感与 session restart 路径。
- Not implemented:
  - 未接 OpenTypeless 生产 IME，未选择最终键盘底座；
  - 未接真实 ASR、librime、测试 Schema、UserDB、性能矩阵、完整字段/横屏/TalkBack 矩阵；
  - 未完成 KSP-007 逐文件/传递依赖/资源许可审计；
  - 未把候选上游源码、APK、Maven artifact 或 patch queue 提交到 OpenTypeless 仓库。

## Changes

隔离候选中的主要 patch surface：

- `opentypeless-editor-host/`: 独立 Java 17 Android library，承载 36 个已验证的 OpenTypeless editor/context/host
  类，避免把 Java record 直接交给候选 app 的 KSP processor；源码集合 SHA-256
  `dd61647e965990b8dbf6cf8363ed0c2eb3d93a9d27f3473507d2230c89869666`。
- `OpenTypelessKeyboardAdapter.kt`: 单一 adapter、动态 `KeyboardHost`、bounded fresh evidence、局部/语音事务和脱敏
  outcome；SHA-256 `f97033ab931fed2373450a5004d307065fdc198abe00b2ccc9602f6c15fd2958`。
- `FlorisApplication.kt` / `FlorisImeService.kt`: 创建唯一 adapter 并转发 editor lifecycle；Host 每次动态读取当前
  `EditorInfo` / `InputConnection`，adapter 不持有 editor capability。
- `AbstractEditorInstance.kt` / `EditorInstance.kt` / `QuickAction.kt`: 分别把 QWERTY、candidate 和 toolbar insertion
  送入 adapter。
- `KeyboardManager.kt`: `VOICE_INPUT` 驱动 deterministic partial/final/Undo 垂直切片。
- `OpenTypelessKeyboardAdapterInstrumentedTest.kt`: 3 个真实 Android editor 用例；SHA-256
  `ec6ae6a07166f1f474b64734ba66b537fa77b9df8bbb9cb2ca1864c054140987`。

## Architecture

- contracts: UI/候选/语音入口只调用 adapter；所有实际 editor write 仍在复制自当前主线的
  `EditorTransactionManager` 中完成。adapter 字节码没有 `commitText`、`setComposingText`、
  `finishComposingText`、delete 或 `sendKeyEvent` 调用。
- state changes: adapter 仅保留 `Idle` / content-free `Composing(snapshot, revision)` /
  `Committed(<redacted exact id>)`；`onStartInput` / `onFinishInput` 清空旧 Voice state。
- migration: 无持久格式、数据库、Preference 或用户数据迁移。
- feature flag: 仅隔离候选内的 compile-time `SPIKE_ENABLED=true`；不是生产 Feature Flag，不能进入发布包。

## Security & privacy

- data sent/stored: 无网络发送、文件/数据库写入或诊断正文；测试文字只存在于测试进程内的 `Editable`。
- permissions/components: 未新增权限或 exported component；候选 manifest 仍无 `INTERNET`。
- threat considerations:
  - adapter 没有 `InputConnection` 字段，Host 每次动态解析当前连接；
  - 普通字段证据每次重新 bounded capture；敏感 Voice 在零正文 getter、零 writer 下拒绝；
  - 敏感本地 QWERTY 仍允许，但不读取正文 evidence；
  - exact receipt ID 的 `toString` 脱敏，restart/finish 后旧能力失效；
  - 该结论只覆盖被选中的 KSP-003 路由，不宣称上游 FlorisBoard 其余 legacy writer 已迁移。

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| `:app:compileDebugKotlin :app:compileDebugJavaWithJavac`，strict verification，offline | PASS | 94 tasks；Java record 从 app 移入独立 library 后 KSP 编译通过 |
| `:app:compileDebugAndroidTestKotlin`，strict verification | PASS | 104 tasks；首次只联网取得已有 Android test 依赖，verification 未关闭 |
| `:app:assembleDebug :app:assembleDebugAndroidTest`，strict verification，offline | PASS | 189 tasks；arm64-v8a/x86_64 native payload 均保留 |
| `apksigner verify --verbose --print-certs`（两包） | PASS | v2，单一 Android Debug signer，certificate SHA-256 `ec624165…5612e` |
| 小米定向 `OpenTypelessKeyboardAdapterInstrumentedTest` | PASS | 3/3，首次 0.183s；覆盖安装后复跑 3/3，0.206s |
| 主 APK 同签名覆盖安装 | PASS | 无人值守，1.44s |
| androidTest APK 同签名覆盖安装 | PASS | 首次经明确 USB 安装确认；随后无人值守 0.93s |
| OpenTypeless 仓库 GitHub Actions for current HEAD | NOT RUN | 当前 HEAD 无 GitHub run；本任务候选只存在于仓库外隔离目录 |

## Evidence

- Main APK: `33,949,144` bytes，SHA-256
  `0e98f7458ab2aa0237afbdb3ab56c56e380fa21db397f0a72ba5bee2e436f648`。
- AndroidTest APK: `579,910` bytes，SHA-256
  `e3f0a9821cd66ed3a6ad193cf42bf7372ab09bfb5729f26910d415dd93a0c76f`。
- Package: `dev.patrickgold.florisboard.debug`，version code/name `117` / `0.5.2-debug+null`，min/target/compile
  SDK `26/36/36`。
- Device: Xiaomi 10 Ultra `be4e2015`，M2007J1SC，Android 13/API33，HyperOS
  `OS1.0.4.0.TJJCNXM`；runner 精确 target 为 `dev.patrickgold.florisboard.debug`。
- 用例行为：选区 `OLD` 被 QWERTY 替换，candidate 与 toolbar 依次追加；Voice 两次 composition 后 final，再按 exact
  commit ID 删除；restart 不复用旧 ID；密码字段 Voice 零 plaintext read/write，本地 QWERTY 可用。
- 默认 IME 始终为 `com.flypy.input/PangIME.Android.InputService`；验收后设备为 `Dozing`，系统自动熄屏仍
  `600000ms`、充电常亮仍关闭。

## Risks

- deterministic Voice 文本只验证 adapter/editor contract，不验证 ASR 质量、音频权限或真实网络生命周期；
- candidate route 使用 Floris completion，不是 Rime candidate；Rime/JNI/UserDB 属于 KSP-004；
- 上游候选仍含自身 writer surface。本任务只证明被选中的四条入口走唯一 OpenTypeless writer，正式迁移必须在
  KSP-010/KBD-001 后用 Feature Flag 保证新旧路径不双写；
- 该测试 APK 为 debug signer，不是发布候选；许可证与 NOTICE 结论仍待 KSP-007。

## Rollback

删除仓库外隔离目录，并从测试设备卸载 `dev.patrickgold.florisboard.debug` 与
`dev.patrickgold.florisboard.debug.test` 即可。OpenTypeless runtime、默认 IME、持久数据和发布配置无需回滚。

## Follow-ups

- KSP-004
- KSP-005
- KSP-006
- KSP-007
- KSP-008
- KSP-009
- KSP-010

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: 共享工作树已有大量其他任务的 tracked/untracked 变更；KSP-003 只更新证据文档，不 stage、
  commit 或 push；第三方候选 patch 与 APK 均留在仓库外临时目录。
