# ADR-0011: Keyboard base evaluation and upstream boundary

## Status

Accepted

## Background

OpenTypeless 目前的 Android IME 是语音优先的受限输入面板，不是完整 QWERTY 键盘。后续 KBD、RIM、Action
与完整 test-host 工作需要一个可长期维护的键盘 Shell，但把任一现成键盘直接合入主干会同时引入高成本边界：

- IME 生命周期、选区与组合态必须继续服从 `EditorSessionManager`、`EditorTransactionManager` 和
  `CompositionCoordinator`，底座不能成为第二个 editor writer；
- OpenTypeless 当前按 MIT 分发。候选的 Apache-2.0、BSD-3-Clause、LGPL-2.1 和 GPL-3.0 义务不同，不能用
  星标、功能数量或一次成功构建覆盖许可证阻断；
- 键盘 Shell、Rime runtime、Schema/词库和图标/主题是不同作品，必须分别记录来源、固定版本、许可证、NOTICE
  与可分发权；
- fork 若没有固定 upstream、有限 patch queue 和可复现同步演练，会把安全更新与 Android API 升级变成不可控的
  长期维护成本；
- 历史调研中的分数只是 2026-08-12 快照，不是选择证据。

截至 2026-08-14，候选项目官方仓库公开标识为：FlorisBoard 为 Apache-2.0，librime 为 BSD-3-Clause，
fcitx5-android 为 LGPL-2.1；HeliBoard 为 GPL-3.0。这里只记录上游声明，不构成法律意见，也不授权复制、链接或
分发任何代码。每个固定候选提交仍须在 KSP-007 重新审计其完整依赖、资源和生成产物。

本 ADR 正式化规范包历史 `ADR-003` 的评估问题；历史快照保持不变。产品负责人已在 2026-08-15 确认路线 A
为主产品方向，并拒绝当前路线 B 的 GPL payload。后续同一 Route-A artifact 已关闭 integrated Rime 共同功能门；
KSP-007 addendum 又删除 `han.sqlite3`/Han pack 与来源未闭的 `data.json`，补 CLDR/native/patch provenance，并完成
两台 arm64 动态矩阵。strict Release 随后由 KSP-009 Release closure 在逐项官方认证 29 个 release-only
artifacts、保持 strict verification 后通过，并由 fresh exact patch replay 复现 byte-identical unsigned Release
APK；同一 final Debug/AndroidTest APK 又在 disposable API26 x86_64 guest 通过安装、core、Latin 与 UserDB
fresh-process restart。

2026-08-16 的独立 KSP-010 复核发现当前 whole Route-A evidence artifact 仍有两个 P0 硬门失败：compiled
production graph 保留 `EditorTransactionManager` 外的 upstream direct writers/`InputConnection` capability；merged
manifest 又保留 `allowBackup=true`、IME/词典备份、profileable 与 exported/import/SEND/notification/query surface。
把未来产品对象文字上收窄为 writer-free/privacy-safe Shell source boundary 不能替代可构建 artifact 证据，因此
编辑安全与隐私/权限门当前均为 **FAIL**。路线 A 的许可证/来源 inventory、strict build/双 ABI 动态和 selected-path
共同切片为 **PASS**；路线 B 的许可证/来源与当前隐私默认为 **FAIL**。本 ADR 保持 `Proposed`，KSP-010 为
`PARTIAL`，KBD-001 不得开工。这是 safety follow-up 之前的历史裁决。

KSP-009 safety follow-up 随后产出独立 `:route-a-safety-eval` application：它不依赖 `:app`，不编译/
打包被拒绝的 legacy editor graph，以真实 View 覆盖 Latin/Rime/Voice/Undo/QuickAction，并把 framework editor
capability 限制在 editor-host authority enclave、把精确七条 mutator edge 限制在
`EditorTransactionManager`。source + final Debug/Release whole-APK gate、deny-all backup/transfer 的
merged-manifest gate、strict clean/fresh replay 与小米 API33/API26 x86_64 exact 12/12 全部 PASS；独立终审对
固定 candidate/replay/双 ABI 给出 residual P0/P1=0 与无条件 GO。whole upstream App 仍保持
**FAIL / NOT SELECTED**。本 ADR 因此转为 `Accepted`，KSP-010 为 `DONE`；KBD-001 在本裁决时仍为 `TODO`，随后已由
独立任务完成。

## Decision

### KSP-010 accepted restricted direction

产品负责人选择 **路线 A：FlorisBoard 风格 Shell source boundary + OpenTypeless 独立能力层 + 自建 librime
Adapter contract** 作为主产品实施方向。未来实现必须只包含下述固定 source identity 中为
键盘 Shell 所需、经逐项导入审查的源码边界与 adapter contract，不能继承 whole FlorisBoard App 的 direct
writers、manifest、数据策略、存储、备份、权限或 exported component。
路线 B（fcitx5-android + official Rime plugin）只保留为技术备用；当前已验证 artifact 中的 GPL-2.0-or-later
`pinyin.lua` 与静态进入 `librime.so` 的 GPL-3.0-only octagram 不进入主产品。重新考虑路线 B 必须新建 ADR，
并先证明 clean GPL-free rebuild，或由负责人明确接受 GPL/LGPL 分发范围、对应源码、修改、重链接和长期维护义务。

路线 A 固定输入为：FlorisBoard `v0.5.2` commit `2e82060251897226c0739b9f52d1d051b02305fb`、JetPref
`d6e12dda6517345dacc3682aa476a8448a71c34b`、librime `1.17.0` commit
`33e78140250125871856cdc5b42ddc6a5fcd3cd4` 及 KSP-004 记录的 recursive gitlink、Boost `1.89.0` exact
archive/digest。KSP-011 必须把 upstream remote、有限 patch queue、版权保留和 clean replay 自动化；KSP-012
必须独立裁决小鹤码表/词库及所有内置数据，未知或无分发权资源不得进入 release variant。

路线 A 的当前工作表为 **80/100**：许可证 16/20、Rime 20/20、完整键盘 16/20、Voice/Action 12/15、上游同步
6/10、迁移成本 6/10、性能 4/5。Rime 5/5 严格来自同一 artifact 的 synthetic test Schema、candidate、UserDB
与 restart 共同矩阵全部 PASS；它不授权真实小鹤资源，也不表示 production RIM integration 已完成。早期 72/100
工作表已被该 rubric-correct 结果取代。80/100 只在后续 restricted boundary 的全部硬门通过后用于选型；它仍不是
产品质量、完整度或发布承诺。分数不能覆盖 KSP-012、NOTICE/SBOM、EditorTransaction、隐私或发布硬门。路线 B
当前许可证硬门失败，按本 ADR 规则不计算可用总分。

### 候选与实验边界

KSP-002..009 只对以下两条路线做同条件、可复现的垂直切片：

1. **路线 A：FlorisBoard 风格 Shell + OpenTypeless 独立能力层 + 自有 librime Adapter。** Shell、librime、
   Schema 与 OpenTypeless adapter 分开固定来源和许可；Dictate Keyboard 仅作为产品形态参考，不复制其实现。
2. **路线 B：fcitx5-android Shell/engine framework + 官方 Rime plugin + OpenTypeless Voice/Action Adapter。**
   LGPL 合规、修改源码提供方式、链接/可替换要求和所有传递依赖必须由 KSP-007 给出可执行清单并经负责人确认。

自建完整 Shell 不进入本轮主候选，因为它不能在相同时间内提供成熟键盘基线。HeliBoard、Trime 及其他 GPL
实现只可作为公开行为参考；在没有新的许可证 ADR 和负责人明确接受整体分发后果前，禁止把其源码、资源或衍生
实现复制进当前 MIT 主干。任何候选若需要这一越界才可通过垂直切片，直接判定硬门失败。

### 硬门

加权分数不能覆盖以下任一失败：

1. **许可证与来源：** 固定的 root commit、submodule/dependency commit、源码/二进制/资源/Schema/词库许可、
   copyright、NOTICE、修改与再分发义务完整；未知、冲突、不可分发或需要未批准许可证变更即失败。
2. **构建与供应链：** arm64-v8a 与 x86_64 从干净环境构建和安装；无浮动分支、浮动下载或未校验产物；依赖
   verification、模型/AAR/hash 门禁不降低。
3. **编辑安全：** 候选通过 OpenTypeless adapter 产生领域事件/`EditorOperation`；不得让底座、Rime plugin、
   Provider 或 UI 在 `EditorTransactionManager` 外持有或调用 editor 写能力；新旧 Feature Flag 路径不得双写。
4. **隐私与权限：** manifest、exported component、网络/剪贴板/存储/诊断逐项审计；密码、OTP、支付字段不能
   因底座默认行为扩大联网、学习、历史或诊断正文。
5. **功能正确性：** 两路线均完成同一垂直切片：QWERTY `abc`、测试 Rime Schema、preedit/candidate/选择、
   Voice partial/final/Undo、切 App 后迟到事件零误写；不能用路线特有捷径删减场景。

KSP-010 的 Route-A 正式结果为：

| 硬门 | 结果 | 决策边界 |
|---|---|---|
| 许可证与来源 | **PASS** | fixed source/dependency identity、selected license branch、copyright、redistribution obligation 与 provenance 已完整记录；未知资源已从 exact artifact 排除。正式 notices/SBOM/source bundle/drift 是发布执行门。 |
| 构建与供应链 | **PASS** | 两 ABI 源码重建与 artifact 映射、strict Debug/AndroidTest/Release、fresh exact replay、arm64/x86_64 安装与动态矩阵均通过。 |
| 编辑安全 | **PASS — selected restricted boundary** | `:route-a-safety-eval` 不依赖 `:app`，legacy editor classes 不进 APK；producer 无 IC/manager/registry/host capability，真实 View/Rime/Voice/Undo/QuickAction 只越过一条 exclusive route 与 capability-free `EditorPort`；D/R whole-APK gate 锁定 ETM 精确七条 writer edge。whole upstream artifact 的 32+ writer/5-IC-file 失败保留为 NOT SELECTED 历史证据。 |
| 隐私与权限 | **PASS — selected restricted boundary** | `allowBackup=false`；base/cloud/device-transfer 全域排除；只有一个 `BIND_INPUT_METHOD` service，无 permission/query/profileable/其他 component；source 和 D/R merged-manifest gates PASS。whole App 原有 surface 仍 NOT SELECTED。 |
| 共同垂直切片 | **PASS** | final restricted Debug 的真实 View 覆盖 Latin/Rime/Voice/exact Undo/QuickAction/sensitive/lifecycle/generation；小米 API33 与 API26 x86_64 均 exact 12/12。 |

路线 B 的构建、编辑安全和共同垂直切片有明确 **PASS** 证据；其当前默认剪贴板历史与 OpenTypeless 隐私
默认冲突，因而隐私门对当前默认为 **FAIL**；其 GPL payload 与对应源码/重链缺口使许可证/来源门为 **FAIL**。
whole Route-A candidate APK 同样不是可接受对象：后续 KSP-009 APK 只作为构建、功能和供应链证据 artifact，
不得被称为 production candidate。失败证据保留，分数不得覆盖。

### KSP-009 safety follow-up 关闭证据

KSP-009 safety follow-up 已以同一 buildable evaluation module 完成下列关闭条件：

- 真实 QWERTY `abc`、Rime、Voice/Undo、普通键与 QuickAction 全部只发 domain event/`EditorOperation`，经唯一
  OpenTypeless `EditorTransactionManager`，无失败 fallback；legacy writer classes 在该 variant 不编译或 capability
  为零；source + compiled Debug/Release gate 证明 ETM 外 writer 调用和 `InputConnection` capability 均为零；
- old/new Flag spy 分别证明 old-only/new-only，不能双写或在拒绝后回退；
- 保持 OpenTypeless `allowBackup=false`，并在 backup/data-transfer rules 中全域排除 Rime UserDB、学习数据、历史和
  Secret；不得继承 candidate 对 `root`、`jetpref_datastore`、`file/ime` 或 `database/floris_user_dictionary` 的备份；
- 不得导入 upstream `SpellCheckerService`、自定义 `ui://`、`content`/`SEND` extension import、launcher alias、
  `CopyToClipboardActivity` image `SEND`、profileable shell、`POST_NOTIFICATIONS`、额外 `queries` 或新的无保护
  exported surface；不得启用 clipboard history/system sync 或正文诊断；
- 评估 APK 只包含一个受 `android.permission.BIND_INPUT_METHOD` 保护的 IME service，不包含第二 editor
  authority 或 ProfileInstaller receiver；
- 任何新增危险权限、扩大 backup/exported/network/clipboard/storage/diagnostic 边界的决定都必须先有独立
  `Accepted` ADR。Debug 或 Release 任一 merged-manifest gate 失败时不得继续；同一新 artifact 还须通过 strict
  clean Debug/Release 与 arm64/x86 动态矩阵。

最终 architecture/manifest verifier 分别 **30/30** 与 **23/23 PASS**；strict clean 和 fresh replay 均为
216 tasks PASS，Debug/Release JVM 各 23/23，三个 APK 与 merged manifests 逐字节一致。固定 123-file patch
为 10,501,449 bytes/SHA-256 `13a0073967cc8fe9e61fe31ffc46b3110ef9e60cb629faa5ba172d33d79a38b0`，
重放 tree `338b3ec42379876cf9091552e492e285eb4382d4`。final Debug/AndroidTest/unsigned Release 的 SHA-256
为 `072873267bf817043bb022eced1a885ec28d6452ac93cf4f48347894430ad1d9` /
`fd8f3db9c42b82969961c39c1ff88a6be5c461371ee6e3b4b9da0292796161a1` /
`75a618a8a78c7ffdcc4d9d3319c5d7d859f3625999a6256b9105375f2c0d247d`。小米 API33 和 API26 x86_64 对
同一 final main/test exact class 均 12/12 PASS、零失败、instrumentation code -1、runner RC0；x86 streamed
`Broken pipe` 失败保留，稳定服务后 no-streaming 安装、回读与清理 PASS。独立终审 residual P0/P1=0、无条件 GO。

### 评分

硬门全部通过后，每项按 1–5 分计分，并将原始数据、脚本、设备、commit 与判分理由链接到 KSP-008/009 证据。
总分为 `sum(weight * score / 5)`，满分 100；禁止用 GitHub star、主观“成熟”或未执行项代替实测。

| 维度 | 权重 | 1 分 | 3 分 | 5 分 |
|---|---:|---|---|---|
| 许可证/分发自由度 | 20 | 义务不清或需未批准整体改许可 | 可分发但有持续合规与替换义务 | 许可清晰、NOTICE/再分发边界可自动验证 |
| Rime 就绪度 | 20 | 无可运行 Adapter | 基础 preedit/candidate 可用但生命周期或 UserDB 不完整 | Schema/candidate/UserDB/重启均通过共同矩阵 |
| 完整键盘成熟度 | 20 | 仅演示层 | 基础布局/字段/横屏可用 | 字段、候选、TalkBack、横屏和工具栏矩阵完整 |
| Voice/Action 扩展性 | 15 | 必须绕过 OpenTypeless authority | 可接入但 patch 面或耦合较大 | 只经稳定 adapter，Voice/Action 与底座具体类隔离 |
| 上游活跃与同步 | 10 | 无可重放来源或 fork 已漂移 | 能固定并手工同步 | clean upstream 可自动重放有限 patch queue |
| 当前迁移成本 | 10 | 需重写核心或多套 writer | 有界模块迁移 | 最小 adapter/flag 切片且旧路径可回滚 |
| 性能可控性 | 5 | 超出硬阈值或不可测 | 达到基本阈值 | 同设备冷启动、按键 P95、PSS、APK 均有稳定余量 |

KSP-010 只能在两路线硬门均有明确结果后选择；某路线硬门失败时可以不计总分，但必须保留失败证据。若两条
可接受路线总分差小于 5 分，依次以许可证/分发自由度、上游同步、编辑安全、Rime 完整度、性能稳定性决胜。
仍无法区分时保持本 ADR 为 `Proposed`，不得凭偏好开工 KBD-001。

### 上游与 fork 策略

- KSP-002/005 在独立 spike 工作区记录官方 HTTPS remote、完整 40 字符 commit、recursive submodule commit、
  获取日期和源码归档 SHA-256；产品构建不得跟随 branch、tag 移动或未经哈希的 CI artifact。
- OpenTypeless 变更保持为可排序的有限 patch queue；底座无关的 editor、voice、config、security 领域代码不得
  搬入 fork。上游代码中的修改保留原版权/NOTICE，并单独标记 OpenTypeless patch。
- 每次候选版本更新先在 clean upstream 重放 patch，再运行共同垂直切片、许可 diff、manifest/permission diff
  与性能基准；冲突不得通过覆盖上游或删除测试解决。
- KSP-011 在目标底座选定后实现同步脚本和操作说明；在此之前手工 spike 也必须保留命令与 digest，不能把
  本机缓存或未跟踪 checkout 当成可复现证据。
- 默认回滚是关闭尚未发布的 keyboard shell Feature Flag 并恢复已验证的语音路径；配置/Schema migration 与
  shell Flag 分离，实验不得改变现有持久格式。

### 非本 ADR 范围

本 ADR 只接受 Route-A restricted Shell source boundary + adapter contract，并只解除 KBD-001 的前置阻塞；
KBD-001 当时仍为 TODO，现已由独立任务完成。它不授权 whole upstream/candidate App、manifest、data/storage/backup/permission/exported surface，本身也
不下载或引入依赖、不决定小鹤资源分发、不实现 JNI、QWERTY、Rime、UI 或 Feature Flag。它不替代 KSP-011/012、
SEC-010、TST-008、REL-003/007 的同步、资源、SBOM、性能与发布验证，也不等于系统选中 IME E2E、正式签名
Release 或真实小鹤。仓库外 spike 不是可整体复制的产品代码。

## Consequences

正面结果：两条路线必须在同一安全、功能、性能和许可条件下比较；后续不能以一次成功构建、已有 Rime 或主观
UI 偏好绕过 editor authority 和许可证硬门。底座相关代码被限制在 adapter/fork 边界，OpenTypeless 核心保持
可替换。

代价与风险：KSP-002..009 必须维护两套短期 spike、双 ABI 构建、相同设备脚本和更细的第三方物料清单；
fcitx5 路线可能需要额外法律意见，Floris+librime 路线可能暴露 JNI/Rime 生命周期成本。评分只提高决策可追溯性，
不能消除许可解释或 OEM 性能差异。

长期义务：目标底座接受后仍需保留 upstream remote、固定 commit、patch provenance、NOTICE/SBOM、许可与权限 diff、
同步演练和回滚 Flag。任何新增 plugin、Schema、词库、主题或二进制必须独立复核，不能继承 root project 的许可结论。

## Validation

KSP-001 初始阶段只验证 ADR 结构和决策门槛，不验证候选可构建或可分发：

- 2026-08-14 复核候选官方仓库及其许可证声明：
  [FlorisBoard](https://github.com/florisboard/florisboard)、
  [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android)、
  [librime](https://github.com/rime/librime) 与
  [HeliBoard](https://github.com/HeliBorg/HeliBoard)。结果仅作为候选边界输入，不是法律接受证据。
- `python3 scripts/test_verify_adrs.py -v`：**4/4 PASS**。
- `python3 scripts/verify_adrs.py --repo-root .`：**PASS**，模板、索引与 11 个 standalone ADR 全部有效。
- `python3 scripts/verify_docs.py --repo-root .`：**PASS**，3 个入口与 16 个规范文件链接完整。

截至 2026-08-14，KSP-002 已完成 FlorisBoard 固定提交的双 ABI clean build/install，KSP-003 已完成路线 A 的
QWERTY/candidate/toolbar/Voice partial/final/exact-Undo 隔离切片并在小米 10 Ultra 上 3/3 PASS；KSP-004 已固定
librime `1.17.0` commit/recursive gitlink/Boost archive，双 ABI clean-build 自有 JNI adapter，并在 API35 arm64
emulator 与小米 API33 通过 Schema/candidate/UserDB fresh-process 重启矩阵。KSP-005 又固定 fcitx5-android
`0.1.3` source commit 与 22 个 recursive gitlink，完成主程序/Rime plugin 的 arm64-v8a、x86_64 clean build，
并在对应 ABI emulator 实际安装、回读同哈希和启动。KSP-006 在同一 source commit 的仓库外副本完成路线 B
QWERTY/actual Rime/Voice/exact-Undo 垂直切片：final clean build 409 tasks、API35 arm64 instrumentation 4/4、
JVM 5/5、host Lint 与 7-edge writer assertions 均 PASS；小米动态用例因 ADB interface 未重新枚举而明确
`NOT RUN`。五项均未把候选源码、runtime 或 APK 引入产品树。

KSP-007 随后从上述固定 artifact 关闭许可证分析门：路线 A 在完整 NOTICE/SBOM、静态依赖归属和内置数据来源
门禁下条件可接受；路线 B 的实包并非“仅 LGPL”——主 APK 含 GPL-2.0-or-later `pinyin.lua`，Rime plugin 的
`librime.so` 含 GPL-3.0-only octagram，且当前 prebuilt 不能替代完整对应源码/重链接材料。路线 B 必须选择明确的
GPL/LGPL 分发方案，或从固定源码移除 GPL payload 后 clean rebuild，并经 artifact/license drift 与法律负责人
复核。详细证据见 [KSP-007 合规分析](../2026-08-14-ksp-007-license-compliance-analysis.md)。

KSP-008 又在同一台小米 10 Ultra/API33 上以固定 artifact 和交替顺序完成性能实测：A/B QWERTY P95 为
5.649/5.708 ms，candidate P95 为 0.392/6.150 ms，均通过 50/80 ms 建议目标；Activity initial-display P95
为 437/1,128 ms，post-launch PSS 为 78,573/139,111 KB，debug distribution proxy 为
67,298,265/68,705,139 bytes。路线 A candidate 是两候选合成 Schema 且 Adapter 与 Shell 分进程，不能把其
低延迟/PSS 作为 full Rime 集成结论；路线 B 首次安装后的第一轮 engine init 观测 9.727 s，必须保留为启动风险。
详细协议与脱敏样本见
[KSP-008 性能报告](../2026-08-14-ksp-008-keyboard-performance-benchmark.md)。

KSP-009 随后在同一设备完成字段布局、横屏、Accessibility、主题、剪贴板、Rime 与 clean replay 矩阵。路线 A
具有更强的 email/URI 专用键和隐私默认值，但 integrated Rime 缺失且有 1 个严格无障碍描述缺陷；路线 B actual
Rime 与共享 transaction writer 通过，代价是 clipboard history 默认开启、email/URI 专用程度较弱和 5 个未描述
clickable subtree。两路线 patch 均为 49 个文件并能重放，不能据此忽略 KSP-011。完整证据见
[KSP-009 功能报告](../2026-08-15-ksp-009-keyboard-function-matrix.md)。

KSP-010 汇总后的当前可追溯裁决是：路线 A 的 license/source inventory、strict Release、arm64/x86 动态与
selected-path 分项垂直切片证据成立；whole candidate 的 Editor authority 与 privacy/permissions 则明确失败，
不能用未来 restricted boundary 的排除条件改判。路线 B 当前 artifact 也因 GPL payload 与不完整的对应源码/
重链接材料未通过许可证硬门。早期 72/100 已由 rubric-correct 80/100 取代，但任何分数都不能覆盖 P0 硬门；
完整矩阵见 [KSP-010 决策报告](../2026-08-15-ksp-010-keyboard-base-decision.md)。

2026-08-15，产品负责人明确确认：“选择路线 A，并不接受当前路线 B 的 GPL 载荷作为主产品。”该确认解决产品
方向，但不撤销 ADR 的证据硬门。后续同一个隔离 Route-A Debug artifact 已证明 QWERTY、actual Rime
preedit/candidate/select、Voice/Undo 与 app-switch late-event safety，并移除 `han.sqlite3`、Han pack、`data.json`
和当前已知 GPL/Lua/octagram payload；CLDR/native/patch provenance 与 exact replay 也已补齐。strict-offline replay
209 tasks、JVM 7/7 PASS，candidate/replay main/test APK 均逐字节相同；main SHA-256 为
`24f43dc77e387bf251cab2c61edaf980e0e73aa3c9420e95fe21e3bbc2c9edd7`，test 为
`66dd631d44a5a0255e04305ca4a8fa25bc1a015d5f92e4f84ce393c527301091`。source-first 脚本校验 fixed clean
source，重建/strip 两 ABI librime/JNI；四 native 输出与 `jniLibs`/APK entries 同哈希，host-path/GPL markers 为零。
小米与 API35 arm64 emulator 各安装成功，并通过 core 6/6、Latin resource 3/3、seed 1/1、fresh-process restart 1/1。

最终 89-file patch 为 10,214,294 bytes、SHA-256
`a04c5fecdadadc49e5f67e09495de6f71219cb02b5e974a80bd9d8fe1d2985a5`；fresh apply/check 后 tree
`d99747a43f3c8dcc2a9c70de1f789cce6948af30` exact。

此前 Apple Silicon 软件模拟尝试虽枚举 ADB/x86_64 ABI，但 17:05 后仍未启动 package service；该尝试是历史
失败证据，已由本次 disposable official API26 `default/x86_64` rev1 AVD 的实际动态结果取代。Intel macOS
Emulator 37.1.11/build 15917651 经 Rosetta + software TCG，在 `-accel off -wipe-data -no-snapshot` 下约 7:37
出现 package service。final main/test APK 分别安装 `Success`（11:22.96 / 28.20s）并有 fresh package path；exact
core **6/6**、Latin **3/3**、seed **1/1** 均 PASS。明确 force-stop main+test 后，fresh restart **1/1 PASS**。
最终回读为 x86_64/API26、boot complete、package service/both paths present；emulator 正常 kill，process/port
消失，disposable AVD 副本可恢复地移入 Trash。

strict Release 的历史首次探测曾在 `generateReleaseLintModel` 因 material-color-utilities/backhandler 两个 POM
缺可信校验项而失败且无 artifact；KSP-009 closure 没有抹除该失败，而是对随后暴露的全部 29 个 release-only
artifacts 逐项用 Google Maven/Maven Central 官方 bytes 或 checksum sidecar 认证。最终 verification metadata
SHA-256 为 `6f72a35928022190b243866d17faff1097a895ae14d5938a3ca4048e953ead38`，verification 未关闭、未放宽。
candidate strict Release **2m55s/262 tasks PASS**，fresh replay **2m44s/262 tasks PASS**；两份 unsigned Release APK
逐字节相同，17,758,708 bytes、SHA-256
`243020c76caaf6f6577f5f14a20a02050a15883ac4ab3dafc919ec2381b94df9`。Release 中 225/225 expected assets 加
2 个 baseline-profile entries、8 个 native entries 均符合清单，四个 Rime SO 与 source-built outputs 同哈希，
forbidden markers 为零，manifest 为 `minSdk 26`/`targetSdk 36` 且无 `INTERNET` 权限。

KSP-009 closure 的 final 89-file patch 为 10,227,983 bytes、SHA-256
`81bf81420a4f2ce40461163514f59f583dbe33c0d54d72da8efba7aa4017f9f3`；fresh apply/check 后 tree
`001b727d3c6e2cb8b4a2b50fdb12bb9ca6c6a443` exact。fresh replay 另通过 strict clean Debug/JVM/AndroidTest
209/209 tasks 与 JVM 7/7；main/test APK hash 保持
`24f43dc77e387bf251cab2c61edaf980e0e73aa3c9420e95fe21e3bbc2c9edd7` /
`66dd631d44a5a0255e04305ca4a8fa25bc1a015d5f92e4f84ce393c527301091`。

strict Release 与 x86_64 动态门现均为 PASS，但它们不能覆盖当前 artifact 的 editor/privacy 失败。KSP-010
独立复核拟将选择对象限定为 Route-A restricted Shell source boundary + adapter contract；该未来边界尚无可构建
artifact，不能把未来排除条件当成当前 PASS。
whole candidate APK 的 `allowBackup=true` 与 backup rules 会包含 root/JetPref、IME 文件和 Floris user dictionary；
它还暴露 profileable shell、IME/SpellChecker、`ui://` 与 `content`/`SEND` import、launcher alias、image `SEND`
clipboard Activity 等 surface，因此 whole APK 隐私结果为 **FAIL / NOT SELECTED**。固定 base 的 `VIBRATE`、
`POST_NOTIFICATIONS`、queries 与 DUMP-protected library receiver 也不自动进入产品，均受 KBD-001 negative gate。
此外 whole compiled graph 保留 ETM 外 direct writers/`InputConnection` capability，故 Route A 的编辑安全门也为
**FAIL**。路线 B 当前许可证/来源与隐私默认为 **FAIL**，其余硬门有明确 PASS 证据。该历史时点 ADR 为
`Proposed`、KSP-010 为 `PARTIAL`；负责人确认和旧 Debug/Release/x86 补证不构成实施或发布授权。未审计数据、
真实小鹤资源、第三方主题/词库或任何 GPL payload 不得进入产品发布物；KSP-012 前真实小鹤资源仅可由用户显式导入。

KSP-009 safety follow-up 后续关闭了上述 restricted boundary 的两个 P0，而没有重写 whole-App 失败：

- 独立 `:route-a-safety-eval` 不依赖 `:app`；producer 零 editor capability，真实 View 的 Latin/Rime/Voice/Undo/
  QuickAction 只经 exclusive route、capability-free `EditorPort` 与唯一 ETM。architecture verifier **30/30 PASS**，
  final Debug/Release whole-APK 扫描覆盖反射、MethodHandle/dynamic loader、Unsafe、native/JNI delegation、
  non-host→host façade、package/property spoof 与 source/dependency/package drift；
- source/Debug/Release manifest gates 证明 `allowBackup=false`、base/cloud/device-transfer 全域排除、单一
  `BIND_INPUT_METHOD` service 及零 permission/query/profileable/其他 component。manifest verifier 的一次错误
  tools 路径调用 `ModuleNotFoundError`、实际 0 tests；正确 `candidate/tools` 重跑 **23/23 PASS**；
- strict clean `clean :route-a-safety-eval:check :route-a-safety-eval:assembleDebugAndroidTest` **1m21s/216 tasks
  PASS**（201 executed、15 up-to-date），Debug/Release JVM 各 23/23；fresh exact replay **1m29s/216 tasks PASS**
  （210 executed、6 up-to-date）。123-file patch 10,501,449 bytes/SHA-256
  `13a0073967cc8fe9e61fe31ffc46b3110ef9e60cb629faa5ba172d33d79a38b0` 重放为 exact tree
  `338b3ec42379876cf9091552e492e285eb4382d4`，三 APK 与 merged manifests 逐字节一致；
- final Debug 10,390,848 bytes/SHA-256
  `072873267bf817043bb022eced1a885ec28d6452ac93cf4f48347894430ad1d9`；AndroidTest 625,336 bytes/
  `fd8f3db9c42b82969961c39c1ff88a6be5c461371ee6e3b4b9da0292796161a1`；unsigned Release 10,009,905 bytes/
  `75a618a8a78c7ffdcc4d9d3319c5d7d859f3625999a6256b9105375f2c0d247d`；
- 小米 API33 与 API26 x86_64 对同一 final main/test exact class 均 **12/12 PASS**、零失败、
  `INSTRUMENTATION_CODE: -1`、runner RC0。x86 streamed `Broken pipe` 失败未删除；稳定 package service 后
  no-streaming main/test 安装分别 `Success`/RC0 524.45s/234.84s，instrumentation 87.241s，最终 ABI/API/boot/
  service/path 回读及 emulator/temp cleanup PASS，Xiaomi PangIME 与 arm64 emulator-5554 未变；
- 独立最终审查对固定实现、candidate/replay 与双 ABI 矩阵给出 residual P0/P1=0、无条件 GO，范围只覆盖
  KSP-009 safety evidence 与 KSP-010 editor/privacy hard gates。

据此五类硬门在 selected restricted boundary 上全部通过，本 ADR 为 `Accepted`、KSP-010 为 `DONE`；KBD-001
在本裁决时只解除前置阻塞，随后已完成产品 Shell 接入。

KSP-011 随后把本 ADR 的 fixed-upstream 条件落为可执行、可信的维护契约。官方 Floris Git commit/tree 与 direct
codeload archive bytes/SHA 分别锁定，JetPref canonical remote 修正为 `patrickgold/jetpref`，librime 递归依赖与
Boost identity 写入 lock。历史 `final3` binary patch 因 generated SO、whole-App 路径和 deleted unknown-resource
preimage 明确禁止进入维护队列；替代队列为 3 个 source-text patches、1,028,979 bytes、77 declared paths。
trusted repo 外部门禁拒绝 binary/DB/archive/model/gitlink、boundary escape、额外/重排/tampered patch、Git config
注入、dirty/ignored source、unsafe tar、tree/legal drift 与 non-deterministic export。

固定 archive 的 896-file normalized tree 为 `5a911de0dc2242f146b3cfcc47c2b8b1dc90f7e5`；3-step final tree
为 `179eca9923d2e93af0acdadde454d901d58bf8c0` / 972 files。两个 fresh temp roots 的 `.git`-free export/report
逐字节一致，report SHA-256 `8504ce9934898dd16e910bc162e5e95450e724b5dc8ebe3049f76b429c7a711c`；adversarial
suite **44/44 PASS**。KSP-011 为 `DONE`，但不等于 product Shell import/native build/真实小鹤/正式 release；REL-009
仍须执行一次真实 upstream update 与 conflict handling。

## Rollback

本次 KSP-010 未引入 OpenTypeless 产品运行时代码、依赖、权限、持久数据或 Feature Flag，因此没有产品数据
回滚；仓库外 safety artifact 可独立删除。若未来改走路线 B 或其他底座，必须创建新 ADR 将本记录标为
`Superseded`，并说明 fork、许可、配置、Schema 与用户数据的迁移/回滚影响。KBD-001 实现时仍必须使用 mutually
exclusive keyboard-shell Feature Flag，不能同时启用两套 writer，也不能放宽已接受的 editor/manifest gate。

## References

- Task：`KSP-001`、`KSP-010`
- 设计文档：
  [`02_ARCHITECTURE_DEVELOPMENT.md`](../opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md)、
  [`09_ADR_RESEARCH.md`](../opentypeless_specs/09_ADR_RESEARCH.md)
- 历史调研快照：`09_ADR_RESEARCH.md` 中 `ADR-003：键盘底座`
- 已完成执行任务：`KSP-011`
- 后续任务：`KSP-012`、`KBD-001`、`RIM-001`、`REL-009`
- 被替代 ADR：无
