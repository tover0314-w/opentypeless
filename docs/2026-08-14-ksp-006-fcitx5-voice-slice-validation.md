# Task Report: KSP-006

## Result

DONE — 在固定 fcitx5-android `0.1.3` source commit
`048f581c652367567b8ee5c28c5163b805288895` 的仓库外隔离副本中，完成路线 B 的 QWERTY、实际官方 Rime
plugin preedit/candidate/commit、deterministic Voice partial/final 与 exact-ID Undo 垂直切片。选中的三条入口均只经
OpenTypeless `EditorSessionManager` / `EditorTransactionManager` 写入；失败不会回落到候选旧 writer。

该 `DONE` 只关闭 KSP-006 的隔离技术验证。候选源码、APK、native runtime 和依赖没有进入 OpenTypeless 产品树，
默认 IME 与生产 Feature Flag 均未改变，ADR-0011 继续为 `Proposed`。

## Scope

- Implemented:
  - virtual QWERTY Unicode `abc` 经 adapter 生成 `InsertText`；
  - 实际 fcitx Rime plugin 处理 `nihao`，把真实 preedit、首候选和 `CommitStringEvent` 送入同一个 adapter；
  - Voice 工具栏入口依次演示 `vo` → `voice` partial、同栈 final receipt 和 exact commit ID Undo；
  - `onStartInput`、selection update、`onFinishInput` 与 App/editor 切换同步到一个长寿命 transaction manager；
  - 敏感字段 Voice 零正文读取/零写入，本地 QWERTY 仍可用；
  - Rime 空 preedit 也由新路线 fail-closed 消费，不能回落到旧 `InputConnection` writer。
- Not implemented:
  - 未接真实麦克风/ASR、Action、Raw Restore、完整按键层、剪贴板、横屏、TalkBack 或性能矩阵；
  - 未迁移 fcitx5-android 未被本切片选择的 backspace、return、physical-key 等旧 writer；
  - 未完成 KSP-007 许可证/NOTICE、KSP-008 性能、KSP-009 功能矩阵或 KSP-010 底座选择；
  - 未创建生产 Feature Flag、持久格式或配置迁移。

## Changes

所有实验代码只位于 `/private/tmp/opentypeless-ksp005.4LlBJ1/fcitx5-android`：

- `opentypeless-editor-host/`：36 个当前 OpenTypeless editor/context/host Java 源文件；除
  `EditorSessionManager` 新增两个 Rime façade 外，与共享产品树逐文件相同。包含路径的源码清单 SHA-256 为
  `94e0a87322b5fda5f1e40313f0311c178bdff9c144270b1912dca6428d7a42bc`。
- `OpenTypelessFcitxAdapter.kt`：长寿命 manager、动态 `KeyboardHost`、bounded evidence、Rime/Voice generation 和
  exact-ID Undo；SHA-256 `4dd0e72ba3de4f08d955199ca655a218e60f55a01e026d05ef71cb054035d38b`。
- `OpenTypelessFcitxEventBridge.kt`：只把选中的 fcitx/Rime data event 交给 adapter，不引用
  `InputConnection`；SHA-256 `d8c3f04ca70b3822476b490840f7cdc1b089345be75704cd2f65040993362163`。
- `FcitxInputMethodService.kt` / `KawaiiBarComponent.kt`：分别接 editor lifecycle、virtual QWERTY、Rime
  event 和 Voice 按钮；新旧分支互斥且新分支不做失败 fallback。
- `OpenTypelessFcitxAdapterInstrumentedTest.kt`：4 个真实 Android editor/Rime runtime 用例；SHA-256
  `88eea039e0a98cf458648ac05996e1973683440f29ef0eea5426de71e7067291`。
- `app/build.gradle.kts` / `settings.gradle.kts`：加入隔离 host library，统一 OpenTypeless `minSdk 26`，为 host
  module 启用既有 desugar JDK library；未增加网络或运行时 Maven 依赖。
- `ThemeSerializationTest.kt`：沿用 KSP-005 已确认的单行上游陈旧 fixture 修正；生产逻辑未改。

tracked patch SHA-256 为
`4afe4efb55c9744f630a34f0c33402e2a141691aa037f402805e9f90c1a9f4e4`；新文件分别以上述源码哈希固定。

## Architecture

- contracts: adapter 只持有 `EditorSessionManager` 和动态 `KeyboardHost`，不持有/返回 `InputConnection`；bridge
  只持有 adapter。实际 editor mutation 仍只出现在 `EditorTransactionManager`。
- writer gate: 最终宿主源码精确保留 7 条 framework writer edge：`commitText`、
  `deleteSurroundingTextInCodePoints`、`setComposingText`、`finishComposingText`、`performEditorAction`、
  `beginBatchEdit`、`endBatchEdit` 各 1；adapter/bridge 源码与最终字节码均为 0 条 writer invocation。
- route exclusivity:
  - virtual QWERTY 在 spike enabled 时只调用 `insertQwerty`，否则才走 upstream `commitText`；
  - 当前 addon 为 Rime 时，Commit/ClientPreedit/InputPanel 事件均调用 bridge 后立即返回，空 preedit 也不回落；
  - Voice 按钮在 adapter 接管时立即返回，不再启动另一 voice IME。
- state changes: Rime 与 Voice 使用互相独立的 strictly increasing revision；Voice 额外绑定 editor generation，final
  后只保留脱敏 exact commit ID，Undo 单次 consume。
- migration: 无数据库、Preference、Schema 或用户数据迁移。
- feature flag: 仅候选源码中的 compile-time `SPIKE_ENABLED=true`；不是生产 Flag，也不授权发布。

## Security & privacy

- data sent/stored: 无新增网络、文件、数据库或诊断正文；测试文本只在测试进程的 `Editable` 与官方 Rime runtime
  内短期存在。
- permissions/components: main 仍只有 `VIBRATE`、`POST_NOTIFICATIONS` 与 package-local signature
  IPC/plugin permission，无 `INTERNET`；Rime plugin 仍声明 KSP-005 已记录的 `REQUEST_DELETE_PACKAGES`。
- threat considerations:
  - 每次操作从 host 动态解析当前 editor；App/editor 切换使旧 generation 失效，迟到 partial/final 零写；
  - 普通字段使用有界、前后 authority bracket 的 fresh evidence；敏感 Voice 在正文 getter 前拒绝；
  - 公开 receipt/record 不作为 Undo 权限，只有 transaction ledger 中的 exact ID 可消费；
  - adapter/bridge `toString` 脱敏，测试反射确认两类均无 `InputConnection` 字段或返回值；
  - Rime 空 preedit 的最终修复为 fail-closed no-op/rejection，不以旧 writer “兼容”失败。

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| Java 17，fresh `clean :app:assembleDebug :app:assembleDebugAndroidTest :plugin:rime:assembleDebug :app:testDebugUnitTest`，`-PbuildABI=arm64-v8a,x86_64` | PASS | 409 tasks（377 executed、32 up-to-date），双 ABI 主包/plugin 与测试包均生成 |
| `:app:testDebugUnitTest`（包含在 final clean build） | PASS | 5/5，0 skipped/failure/error；Theme migration 3、StringEscape 2 |
| API35 arm64 emulator 覆盖安装 main/Rime/androidTest | PASS | 三包均为最终 clean-build 产物，三个 install 均 `Success` |
| `OpenTypelessFcitxAdapterInstrumentedTest` | PASS | 4/4，0.735s；真实官方 Rime runtime、QWERTY、Voice/Undo、sensitive、App switch 与空 preedit |
| source + `javap` writer/capability assertions | PASS | ETM 7 edges；adapter/bridge 0 writer；bridge 0 `InputConnection` reference；三条 route exclusivity 命中 |
| `:opentypeless-editor-host:lintDebug`（JDK 21） | PASS | host transaction module 无 Lint finding |
| `:app:lintDebug`（JDK 21） | FAIL | upstream 全 App 269 errors/83 warnings；首项为既有 `fragment_setup.xml` `android:tint`；未建立 baseline/降级规则 |
| `apksigner verify --verbose --print-certs`（5 APK） | PASS | 全部 v2；同一 debug certificate SHA-256 `ec624165…5612e` |
| `git diff --check`（隔离候选） | PASS | 无尾随空白或 patch 格式错误 |
| Xiaomi 10 Ultra exact KSP-006 instrumentation | NOT RUN | HyperOS 首包确认失败后手机只保留 USB device、ADB interface 未重新枚举；未冒充真机 PASS |
| OpenTypeless current HEAD GitHub Actions | NOT RUN | current HEAD 无对应 CI run；最新可见 runs 是无关 schedule/workflow failure |

JDK 17 上 AGP 9.3.1 Lint analyzer 会在本机调用 Java 21 API `List.removeLast()` 而崩溃；为继续实际验证，单独安装
OpenJDK 21 并仅用于 Lint。正常 compile/test/assemble 仍固定 Java 17。JDK 21 的完整 App Lint 能运行到结论，未
通过 baseline 或关闭 dependency verification 掩盖 269 项上游债务。报告中与本切片新增的 adapter/host 文件无
error；触及的 service/bar 仅有 3 个由 `minSdk 26` 暴露的既有 `ObsoleteSdkInt` warning。

## Artifact evidence

| APK | Bytes | SHA-256 | ABI |
|---|---:|---|---|
| main arm64 | 59,762,479 | `1471ad9e4a82502ea1bde4dbbc0f5bcd18148d712394c2f45991b5960ee5cbec` | only `arm64-v8a` |
| main x86_64 | 59,809,855 | `6b29d2de539414de2c3b2535d92cfa63312b9003ce81ec4e924ec33f767ca7c0` | only `x86_64` |
| Rime arm64 | 8,942,660 | `fc4a1f426934dae7c2de9ca2ea4413f8b693b7202285a6d252379a220d91c5a2` | only `arm64-v8a` |
| Rime x86_64 | 9,010,336 | `044b489d4859a9e5ada35169d545afc69021e27eb9207c34692cb08fb735f316` | only `x86_64` |
| AndroidTest | 412,923 | `58645225e5e21d0eb7803f85cfdff8149d37e51fe6f57947971d34ca12f85ccf` | no native payload |

- main package `org.fcitx.fcitx5.android.debug`，arm64/x86 version code `112/114`，version name
  `0.1.3-0-g048f581`，compile/target/min SDK `36/36/26`；
- emulator `emulator-5554`：Android 15/API35、arm64-v8a，默认 IME 保持 AOSP LatinIME；
- actual Rime case 使用已安装官方 plugin 的 runtime，输入 `nihao` 后读取真实 preedit/candidates 并选择首候选；
- Xiaomi 10 Ultra `be4e2015` 最后可读状态为 Android 13/API33、HyperOS
  `OS1.0.4.0.TJJCNXM`、默认 IME `com.flypy.input/PangIME.Android.InputService`。首包安装收到
  `INSTALL_FAILED_USER_RESTRICTED`，前台出现 USB 安装倒计时；随后 ADB interface 消失，macOS 仍识别
  `0x2717:0xff40` USB device。没有设置/恢复锁屏密码，没有关闭 package verifier 或自动熄屏，也没有用未授权
  方式绕过确认。

## Risks

- full App Lint 仍有 269 errors/83 warnings，属于候选上游技术债务；KSP-010 若选择路线 B，KSP-011 必须在正式
  patch queue 中修复并设置零新增问题门禁，而不是建立一份掩盖现状的 baseline；
- fcitx5-android 仍存在未被本切片选择的旧 writer。本任务证明的是三条明确 route；正式接线必须用生产 Feature
  Flag 保证全 session 只实例化一套 writer，并逐步缩减 legacy inventory；
- deterministic Voice 只验证 editor contract，不验证录音、ASR、网络生命周期或识别质量；
- 空 preedit 已安全消费，但“无 commit 的 composition cancel”完整 UX 仍应进入 KSP-009 功能矩阵；
- Rime plugin 权限、LGPL 链接/可替换要求、bundled Schema/data 与 NOTICE 仍由 KSP-007 裁决；
- 小米 KSP-006 动态用例尚未执行；KSP-009 同设备功能矩阵不得引用 emulator 结果冒充真机。

## Rollback

删除仓库外 `/private/tmp/opentypeless-ksp005.4LlBJ1`，并从测试 emulator/设备卸载
`org.fcitx.fcitx5.android.debug`、`org.fcitx.fcitx5.android.debug.test` 与
`org.fcitx.fcitx5.android.plugin.rime.debug` 即可。OpenTypeless 产品 APK、默认 IME、配置与用户数据无需回滚。
若不再需要仅用于 Lint 的 JDK 21，可单独卸载；Java 17 产品工具链未改变。

## Follow-ups

- KSP-007
- KSP-008
- KSP-009
- KSP-010

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: 共享工作树已有大量其他任务的 tracked/untracked 变更；KSP-006 只新增/更新证据文档，不 stage、
  commit 或 push。第三方候选 patch、SDK/cache、native build、APK 与运行时均留在仓库外隔离目录。
