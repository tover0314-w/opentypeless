# OpenTypeless 调研、技术选型与架构决策记录

> 文档版本：1.0  
> 生成日期：2026-08-12  
> 适用仓库：`dengxuezhao/opentypeless`  
> 代码基线：`main@67be488dcd2e9f36520618f9f644f97c3ec02b98`  
> 目标读者：产品负责人、Android/IME 工程师、语音与模型工程师、安全工程师、测试工程师，以及 Codex / Claude Code 等编码代理  
> 状态：**拟议目标方案（Proposed Target Architecture）**；涉及键盘底座、许可证和 Rime 集成的不可逆决策，必须先完成 ADR 技术验证

## 1. 调研说明

本调研快照日期为 **2026-08-12**。GitHub 星标、活跃度和功能会变化，只用于辅助判断，不作为唯一选型标准。所有最终选择必须通过本仓库的垂直切片和设备基准。

---

## 2. 当前仓库基线

| 项目 | 当前事实 |
|---|---|
| 仓库 | `dengxuezhao/opentypeless` |
| 基线 | `main@67be488dcd2e9f36520618f9f644f97c3ec02b98` |
| Android 结构 | 单 `:app` 模块 |
| 语言 | 主要 Java 17 |
| SDK | min 26 / target 35 |
| IME | 语音优先的小型键盘，不是完整 QWERTY |
| 本地 ASR | SenseVoice Small INT8 Final + 前缀重识别预览 |
| 当前风险 | 最新基线 Android CI 曾因 aapt2 dependency verification 元数据缺失失败 |
| 现有优势 | 目标绑定、Raw/Undo/Teach、个性化、事实保护、BYOK、本地优先、安全网络边界 |

---

## 3. 同类项目调研

### 3.1 项目快照

| 项目 | 星标快照 | 许可证 | 主要价值 | 主要风险 |
|---|---:|---|---|---|
| [sherpa-onnx](https://github.com/k2-fsa/sherpa-onnx) | 14,128 | Apache-2.0 | Android、本地 ASR、流式/非流式、VAD、WebSocket | 模型选择和内存仍需自己基准 |
| [FlorisBoard](https://github.com/florisboard/florisboard) | 8,556 | Apache-2.0 | 现代完整键盘、布局、主题、Compose/原生模块 | beta；没有现成 Rime |
| [HeliBoard](https://github.com/HeliBorg/HeliBoard) | 5,830 | GPL-3.0 | AOSP 系成熟键盘、隐私和词典 | GPL 与当前 MIT 路线冲突 |
| [fcitx5-android](https://github.com/fcitx5-android/fcitx5-android) | 5,515 | LGPL-2.1 | 引擎框架、中文、独立 Rime 插件 | 集成复杂、LGPL 合规和上游同步 |
| [Trime](https://github.com/osfans/trime) | 4,546 | GPL-3.0 | Android Rime、主题、候选、Schema | GPL；不宜直接复制进 MIT 产品 |
| [librime](https://github.com/rime/librime) | 4,539 | BSD-3-Clause | Rime 核心、Schema、候选、UserDB | 仅引擎，Android Shell/JNI 要自己做 |
| [Qwen3-ASR](https://github.com/QwenLM/Qwen3-ASR) | 3,353 | Apache-2.0 | 0.6B/1.7B、52 语言方言、流式/离线、vLLM/Docker | 更适合服务器，不假定手机端可用 |
| [Dictate Keyboard](https://github.com/DevEmperor/DictateKeyboard) | 261 | Apache-2.0 | 在 FlorisBoard 上验证完整键盘+语音+流式+动作行 | 项目年轻、差异较大，不能盲目照搬 |

### 3.2 关键观察

#### Dictate Keyboard 的路线意义

它从旧 Java 语音 App 转向 FlorisBoard 完整键盘底座，并把 Provider Registry、实时识别、AI rewording、工具栏和自托管能力作为独立层。这与 OpenTypeless 当前“从语音壳扩展完整键盘”的阶段高度相似。

启示：

- 不要继续在小型语音面板上手搓所有键盘能力；
- 语音 Provider 和键盘 Shell 应分离；
- 完整键盘功能来自成熟底座；
- 动作行必须是一等能力，而不是设置页临时按钮。

#### fcitx5-android 的路线意义

其 Gradle 结构包含 `lib:fcitx5`、`lib:libime`、`lib:plugin-base` 和独立 `plugin:rime`。这证明“引擎框架 + 插件 + Android Shell”对中文输入和 Rime 是成熟范式。

启示：

- Rime 不应嵌在 IME Service；
- 引擎生命周期和 UI 分离；
- 但采用整个框架需要承担构建、原生依赖、LGPL 和上游维护成本。

#### librime 的意义

BSD-3-Clause 使它更适合成为 OpenTypeless 自有 Rime Adapter 的核心依赖。问题是需要自己完成：

- JNI；
- Android 生命周期；
- Schema 部署；
- 候选模型；
- UserDB；
- native crash 和 ABI；
- 与键盘底座组合态协调。

#### sherpa-onnx 的意义

当前项目已经使用其 ASR-only AAR。上游同时覆盖流式、非流式、Android、VAD 和 WebSocket，因此可继续作为本地流式候选框架。但不能只因为框架支持流式，就直接选择任一模型；必须用中文、混说、内存和电量基准筛选。

#### Qwen3-ASR 的意义

官方 0.6B/1.7B 提供统一流式/离线推理、52 种语言和方言、vLLM 及 Docker。对 OpenTypeless 最合理的近期位置是：

- 家庭服务器；
- 云端 GPU；
- Docker Connector/Streaming Provider；
- Final 或流式自托管路线。

不应在没有量化、ONNX/移动端 runtime 和内存证据时，把它写进手机端默认方案。

---

## 4. 键盘底座决策矩阵

评分 1–5；总分按权重折算为 100。分数是调研初评，最终必须由 KSP 任务替换为实测结果。

| 维度 | 权重 | Floris/Dictate + librime | fcitx5-android + Rime | 自建 Shell + librime | HeliBoard + librime | Trime fork |
|---|---:|---:|---:|---:|---:|---:|
| 许可证/分发自由度 | 20 | 5 | 3 | 5 | 1 | 1 |
| Rime 就绪度 | 20 | 2 | 5 | 3 | 2 | 5 |
| 完整键盘成熟度 | 20 | 5 | 4 | 1 | 5 | 3 |
| 语音/Action 扩展性 | 15 | 5 | 4 | 5 | 3 | 3 |
| 上游活跃与同步 | 10 | 4 | 4 | 5 | 4 | 4 |
| 当前迁移成本 | 10 | 3 | 2 | 1 | 2 | 2 |
| 性能可控性 | 5 | 4 | 4 | 3 | 4 | 4 |
| **调研初评总分** | **100** | **81** | **76** | **66** | **57** | **61** |

### 4.1 暂定建议

> **首选验证：FlorisBoard/Dictate 风格的完整键盘 Shell + OpenTypeless 独立能力层 + 自有 librime Adapter。**  
> **备用验证：fcitx5-android + Rime 插件 + OpenTypeless Voice/Action Adapter。**

原因：

- 更深入的许可证与产品体验调研后，Apache-2.0 + BSD-3-Clause 对当前开源和未来分发最清晰；
- Dictate 已证明 Floris 作为“完整键盘 + 语音”的可行性；
- OpenTypeless 可以把自己的编辑事务、安全、个性化和动作能力保持为独立模块；
- 但 Rime Adapter 的实际成本未知，因此不能在 Spike 前最终接受；
- 若 librime 自建 Adapter 成本或稳定性不达标，而 fcitx 垂直切片明显更好，则可以选择 fcitx 路线，但必须接受并落实 LGPL 合规和上游同步策略。

### 4.2 明确排除

- 未经产品许可证变更，不直接以 HeliBoard/Trime GPL 代码作为当前 MIT 主干；
- 不从 GPL 项目复制实现后只改包名；
- 不在没有相同垂直切片的情况下按星标选择；
- 不因某条路线 Rime 易接，就忽略完整键盘和产品设计成本。

KSP-001 已将本节的历史调研问题正式化为
[ADR-0011](../adr/0011-keyboard-base-evaluation.md)。新 ADR 冻结评分 rubric、不可被分数覆盖的硬门与 upstream
策略；最终 restricted safety evidence 已使其成为 `Accepted`。本节分数仍只是历史初评，不能单独作为 KSP-010
的接受证据。

---

# ADR-001：统一 EditorTransaction

- 状态：**Accepted**
- 日期：2026-08-12

## 背景

QWERTY、Rime、语音 partial/final、Action 和 Undo 都会修改同一编辑器。各组件直接操作 `InputConnection` 会产生竞态和错误输入框写入。

## 决策

所有编辑器写入必须转换为受限 `EditorOperation`，由 `EditorTransactionManager` 在主线程重新校验 `EditorSession` 后执行。

## 后果

正面：

- 一个权威安全边界；
- 可测试；
- Action 服务器无法扩大权限；
- Undo 和审计统一；
- 底座可替换。

代价：

- 初期需要包裹现有直接写入；
- 需要 FakeInputConnection 和 Instrumentation；
- 某些底座内部提交逻辑需要 Adapter。

## 验证

- 静态门禁；
- 20 个竞态场景；
- 所有旧语音回归；
- 误写为 0。

---

# ADR-002：CompositionCoordinator

- 状态：**Accepted**

## 决策

Typing/Rime/Voice/ActionPreview 使用单一 CompositionCoordinator 和唯一 owner，禁止多个松散 Boolean 管理并发组合。

## 关键策略

- Rime 组合时启动语音：默认先提交 Rime，可配置为取消 Rime；
- Voice partial 时键盘输入：默认提交可见 partial，可配置为取消语音；无 partial 时固定取消；
- Final 等待时输入变化：按键继续，已有 partial 按上述策略释放，迟到 Final 只进结果面板；
- ActionRunning/ActionPreview 被语音打断：默认释放 owner 并保留 displaced result 到结果面板，可配置丢弃；
- Latin/Rime 组合时开始 Action：固定先提交 composition，再重新捕获 Session；
- CMP-003 的 policy 只选择 `ReleaseDirective` 与结果面板元数据；只有 CMP-004 的唯一 ETM bridge 能根据
  typed result 完成 two-phase preemption，策略本身不能证明 release 或授权 editor 写入。
- CMP-004 的当前 Voice direct-owner 路径由 Service-owned 唯一 Coordinator 签发 observation；partial 取得
  VOICE revision 后才进入 ETM，Final/取消/错误只在 typed 物理结果成功后释放。cleanup 不确定时保持 owner，
  仅 editor lifecycle revoke 可安全终止旧 generation；键盘/Rime/Action 抢占仍由后续接线执行 two-phase。

---

# ADR-003：键盘底座

- 状态：**Accepted（restricted Route-A；whole upstream App 不选）**

## 候选

A. Floris/Dictate + librime  
B. fcitx5-android + Rime plugin

## 必须完成的证据

- 同一垂直切片；
- APK/PSS/冷启动/按键/候选；
- 小鹤 Schema；
- Voice partial/final/Undo；
- App 切换安全；
- TalkBack/横屏；
- 许可证意见；
- 上游同步演练。

## 选择规则

实测矩阵替换调研初评分数后，总分最高且无许可证/安全硬阻断者胜出。若分数接近，优先选择：

1. 许可证和可持续维护更清晰；
2. 能保持 OpenTypeless 核心独立；
3. 上游安全更新容易同步；
4. Rime 完整度达到验收；
5. IME 性能更稳定。

---

# ADR-004：Kotlin 与 Compose

- 状态：**Accepted**

## 决策

- 新纯领域模块使用 Kotlin；
- 现有 Java 逐步通过 Adapter 迁移，不机械全量转换；
- 管理端采用 Compose Material 3；
- IME 热路径是否 Compose 化由基准决定，初期允许成熟 View/底座实现。

## 原因

管理端表单和状态适合 Compose；IME 对首帧、按键延迟、常驻内存和 OEM 生命周期更敏感。

---

# ADR-005：RecognitionProvider + Router

- 状态：**Accepted**

## 决策

识别路径从单一 `RecognitionBackend` 枚举升级为：

- Provider；
- Capability；
- Route；
- FailureClass；
- Retry/Fallback；
- PrivacyClass；
- Circuit Breaker。

## 后果

- 系统、本地、云端、Qwen3-ASR、流式服务可统一；
- 降级可解释；
- 配置更复杂，需要优秀 UI 和默认路线。

---

# ADR-006：双阶段语音

- 状态：**Proposed**

## 决策候选

- 低延迟流式模型/服务负责 partial；
- 高准确率 SenseVoice 或服务器负责 final；
- 两阶段共享同一 Session；
- Final 替换 Voice Composition 后再提交。

## 前置条件

- 本地流式候选基准；
- 峰值内存符合 DeviceTier；
- Final 延迟收益明确；
- 电量可接受；
- 用户能选择单模型模式。

---

# ADR-007：声明式 Action

- 状态：**Accepted**

## 决策

Action 由 Connector、Action、Placement 和 Workflow 构成。远端只返回白名单 EditorOperation，不能执行脚本、Intent、KeyEvent、Accessibility 或 Shell。

## 原因

输入法具有高权限上下文，任意插件代码风险不可接受。复杂逻辑放入用户 Docker，以协议隔离。

---

# ADR-008：显式学习默认

- 状态：**Accepted**

## 决策

- VoiceLexicon 和 CorrectionRule 默认只由用户确认；
- 重复行为只生成 LearningSuggestion；
- History 不直接变成永久规则；
- Rime UserDB 独立；
- Raw/Undo/Teach 语义分离。

## 原因

静默学习易污染词库、泄漏敏感内容、无法解释。

---

# ADR-009：标准 RecognitionService 范围

- 状态：**Proposed**

## 问题

当前外部标准语音入口与 IME 主路线能力不完全一致，容易让用户误解。

## 方案

A. 让外部入口走统一 RecognitionRouter；  
B. 为外部入口配置独立 Route，并在 UI 明确限制。

## 暂定倾向

选择 B 起步：

- 外部调用没有 IME 选区和上下文；
- 默认 Exact；
- 单独白名单和限流；
- 可引用相同 Provider，但不共享 UI Session；
- 待安全和生命周期成熟后再评估完整统一。

---

# ADR-010：配置继承

- 状态：**Accepted**

## 决策

所有覆盖项使用 `Inherit / Disabled / Value`；解析顺序为：

```text
硬安全
> 会话
> 字段
> App
> 全局
> Provider 默认
```

解析结果携带来源和解释，UI 不自己重复计算。

---

# ADR-011：本地模型管理

- 状态：**Accepted**

## 决策

模型通过 versioned manifest、固定来源、大小和 SHA-256 安装到 no-backup 私有目录；staging 验证后原子切换；支持损坏隔离和旧版本回滚。

---

# ADR-012：长期跨端

- 状态：**Accepted**

## 决策

Android 和桌面共享：

- 领域 Schema；
- Bundle；
- Action Protocol；
- Provider 非密钥配置；
- 词典/规则。

不强行共享：

- UI；
- IME/Accessibility；
- Android package scope；
- 原始 Rime UserDB；
- Session；
- 设备模型状态。

---

## 5. 官方平台约束

### Android SpeechRecognizer

设计必须遵守：

- 在主线程创建和调用；
- 不再使用时 `destroy()`；
- `stopListening()` 后等待 results/error，再开始下一次；
- 系统识别可能把音频发送到远端；
- 不适合作为无限持续识别机制；
- API 33+ 使用 `checkRecognitionSupport`；
- 模型下载回调需要生命周期和 generation 防护。

参考：[Android SpeechRecognizer](https://developer.android.com/reference/android/speech/SpeechRecognizer)

### Android IME

- 输入视图应快速显示；
- 资源预加载/缓存；
- 隐藏后释放大对象；
- 支持切换下一输入法；
- 密码/用户名仍需完整字符能力；
- 不假定每个 App 的 `InputConnection` 行为完全标准。

参考：[Create an input method](https://developer.android.com/develop/ui/views/touch-and-input/creating-input-method)

### Material 3 / Accessibility

- 管理端采用 Material 3；
- 触控目标至少 48dp；
- 状态不只用颜色；
- 语义、内容描述、动态字体和自适应导航。

参考：

- [Material 3 in Compose](https://developer.android.com/develop/ui/compose/designsystems/material3)
- [Accessibility in Compose](https://developer.android.com/develop/ui/compose/accessibility)
- [Touch target size](https://support.google.com/accessibility/android/answer/7101858)

---

## 6. 许可证策略

### 6.1 当前原则

- OpenTypeless 主仓库当前 MIT；
- Apache-2.0/BSD 代码可在满足 NOTICE/归属后集成；
- LGPL 需要明确链接、修改、可替换和源码义务；
- GPL 代码会影响整体分发许可，未经决策不集成；
- 模型权重、码表和词库分别审计，不能只看 runtime 许可证。

### 6.2 必须记录

每个外部组件：

```text
name
version/commit
source URL
license SPDX
modified?
linked/bundled?
NOTICE path
source availability
model/data license
redistribution allowed?
```

### 6.3 上游策略

如果采用大型 fork：

- 保留 upstream remote；
- OpenTypeless 变更分层；
- 尽量独立模块；
- 定期同步；
- 不改写上游版权；
- 安全补丁优先；
- 做一次真实同步演练后才视为可维护。

### 6.4 KSP-007 固定 artifact 合规结论

KSP-007 不再以候选仓库根许可证代替实包审计，而是同时核对 KSP-002..006 固定 source、最终 APK entries、
native symbol、内置 data 和 prebuilt build recipe：

- **路线 A 条件可接受。** FlorisBoard/JetPref 为 Apache-2.0；自建 librime 和实际静态依赖可选择
  BSD-3-Clause、BSD-2-Clause、MIT、BSL-1.0 与 Apache-2.0 兼容分支。发布仍须生成完整 NOTICE/SBOM，保留
  ICU/Unicode/CLDR notices；初始 artifact 中 `han.sqlite3` 与 `data.json` 的来源缺口已由 2026-08-16 addendum
  采取删除/fail-closed 方案关闭。最终 Schema/词库仍须固定逐数据来源；真实小鹤资源继续由 KSP-012 审批。
- **路线 B 当前调试产物不能描述为 LGPL-only。** 主 APK 实际包含 GPL-2.0-or-later `pinyin.lua`；官方 Rime
  plugin 的 `librime.so` 实际静态包含 GPL-3.0-only octagram，另有 LGPL-2.1/3.0 code/data。当前 prebuilt 只含
  static library、headers/data 和 toolchain commit，不能替代完整对应源码、修改、构建与重链接材料。
- 路线 B 只有两种可接受工程路径：明确接受并履行 GPL/LGPL 分发范围；或从固定源码删除全部 GPL payload，
  clean rebuild 后用 APK/ELF drift gate 证明不存在，并继续履行 LGPL 的源码、修改与重链接/替换义务。Android
  签名/安装限制必须用 modified-build 重建安装演练并经法律负责人接受。
- HeliBoard、Trime、未选 fcitx GPL plugins 仍只可作公开行为参考。删除 header、改包名、把 GPL code 放进
  assets/plugin 或仅展示 AboutLibraries 都不能成为许可替代方案。

正式发行必须从 release variant 生成 `UPSTREAM_SOURCES`、patch provenance、SBOM、native link manifest、完整
license/notices 和 model/schema manifests，并校验 source manifest、APK entries、ELF 与 notices 双向一致。完整
hash、component 和禁止复制清单见
[KSP-007 合规分析](../2026-08-14-ksp-007-license-compliance-analysis.md)。这仍是工程合规输入，不是法律意见；
ADR-0011 已在 KSP-010 restricted editor/privacy 等全部硬门通过后转为 `Accepted`。

2026-08-16 addendum 的最新 Route-A Debug 候选移除 `han.sqlite3`/Han pack 与来源未闭的 `data.json`，让 Latin
correction/suggestion/glide 无词数据时 fail closed，并补 CLDR v45 Unicode License v3、patch/native provenance。
source-first native 构建和 fresh patch replay 后，candidate/replay 225/225 assets exact、strict-offline 各 209 tasks
与 JVM 7/7 PASS；main APK 逐字节同 SHA-256
`24f43dc77e387bf251cab2c61edaf980e0e73aa3c9420e95fe21e3bbc2c9edd7`。这些是当前 Debug 候选的来源补证，
不替代正式 release notices/SBOM/source acquisition，也不授权真实小鹤资源随包。

### 6.5 KSP-008 同设备性能输入

KSP-008 在小米 10 Ultra/API33 上对 KSP-003..006 固定 arm64 artifacts 使用同一脱敏脚本。A/B QWERTY P95
为 5.649/5.708 ms，均低于 50 ms；candidate P95 为 0.392/6.150 ms，均低于 80 ms。路线 A 候选数据只来自
KSP-004 两候选合成 Schema，不能按语言复杂度与路线 B actual Rime 直接评分。A/B Activity initial-display P95
为 437/1,128 ms，post-launch PSS 为 78,573/139,111 KB，debug distribution proxy 为
67,298,265/68,705,139 bytes。路线 B actual Rime 首次安装后第一轮 engine init 观测 9.727 s，已有数据的新进程
为 0.752 s，属于必须保留的首次可用性风险。

以上只为 ADR-0011 的性能可控性输入：路线 A 的 Shell 初显/内存当前更优，路线 B full Rime 热候选仍有充足阈值
余量。路线 A 尚无 full-schema/integrated-process 数字，Activity timing 也不是正式 IME-show Macrobenchmark；因此
不得单凭此节接受路线或把 proxy 变成 release 承诺。完整协议与样本见
[KSP-008 基准报告](../2026-08-14-ksp-008-keyboard-performance-benchmark.md)。

### 6.6 KSP-009 同设备功能输入

KSP-009 在同一台小米 10 Ultra/API33 上完成字段布局、横屏、TalkBack/Accessibility tree、主题、剪贴板表面、
Rime 与 clean upstream replay。路线 A 的 email/URI 专用键和剪贴板 history-off/system-sync-off 默认值更符合
OpenTypeless 当前隐私方向，且 Shell 初显/内存沿用 KSP-008 的优势；原始矩阵中 KSP-004 librime Adapter 仍与
Floris Shell 分离，故当时不能计为 integrated Rime，严格无障碍探测也发现 1 个无描述的 screen-reader action。

2026-08-15 重开 follow-up 已从同一 fixed upstream 生成单一 Route-A Debug artifact，并在 API35 arm64 emulator
与小米 10 Ultra 各通过核心 6/6、seed 1/1、force-stop 后 fresh-process restart 1/1；actual Rime
preedit/candidate/select、QWERTY/Voice/Undo 与 app-switch late-event 共用 generation-bound writer。该证据关闭
共同功能垂直切片，但不覆盖 `assets/ime/dict/data.json` 与 native source/NOTICE 的许可证来源硬门，也不把
当时 Release assemble 的 strict-offline 缓存失败写成通过。

2026-08-16 KSP-007 addendum 已用删除未知资源、补 CLDR/native/patch provenance 和 exact replay 取代上述
`data.json`/native Debug 缺口；最新冻结 main/test APK 又在小米与 API35 arm64 emulator 安装成功，并各通过
core 6/6、Latin resource 3/3、seed 1/1 和 fresh-process restart 1/1。早期 Apple Silicon x86 模拟尝试未启动
package service；该历史失败已被后续 KSP-009 disposable x86 dynamic PASS 取代。strict Release 的首次探测也曾在
`generateReleaseLintModel` 因 material-color-utilities/backhandler 两个 POM 缺可信校验项而失败；该失败保留为
历史证据。

最终 source-first 脚本校验 fixed HEAD、clean worktree、OpenCC 精确修改/patch hash，重建/strip 两 ABI
librime/JNI 并拒绝 host path；四产物回填 `jniLibs` 后与 APK entries 同哈希。89-file integrated patch 为
10,214,294 bytes、SHA-256 `a04c5fecdadadc49e5f67e09495de6f71219cb02b5e974a80bd9d8fe1d2985a5`，fresh
apply/check 后 tree `d99747a43f3c8dcc2a9c70de1f789cce6948af30` exact。candidate/replay strict-offline
各 209 tasks、JVM 7/7 PASS；main/test APK 均逐字节一致，main SHA-256 为
`24f43dc77e387bf251cab2c61edaf980e0e73aa3c9420e95fe21e3bbc2c9edd7`，test 为
`66dd631d44a5a0255e04305ca4a8fa25bc1a015d5f92e4f84ce393c527301091`。225/225 assets exact，8 native
entries 的 forbidden/path/GPL/Lua/octagram 扫描为零。

KSP-009 Release closure 随后逐项以官方仓库 bytes/checksum sidecar 认证全部 29 个新增 release-only artifacts；
final verification metadata SHA-256 为
`6f72a35928022190b243866d17faff1097a895ae14d5938a3ca4048e953ead38`，strict verification 未放宽。final
89-file patch 为 10,227,983 bytes、SHA-256
`81bf81420a4f2ce40461163514f59f583dbe33c0d54d72da8efba7aa4017f9f3`，fresh apply/check 后 tree
`001b727d3c6e2cb8b4a2b50fdb12bb9ca6c6a443` exact。candidate/fresh replay strict Release 分别
2m55s/262 tasks 与 2m44s/262 tasks PASS；unsigned Release APK 逐字节相同，17,758,708 bytes、SHA-256
`243020c76caaf6f6577f5f14a20a02050a15883ac4ab3dafc919ec2381b94df9`。Release 的 225/225 expected assets +
2 baseline profiles、8 native entries、四个 source-built Rime SO 映射与 forbidden marker 零均通过；manifest
`minSdk 26`/`targetSdk 36` 且无 `INTERNET`。因此 strict Release 当前为 PASS，不再是 ADR 的未闭门。

同一 final main/test APK 又在 disposable official API26 `default/x86_64` rev1 guest 安装 `Success`，并通过
core 6/6、Latin 3/3、seed 1/1、显式 force-stop main+test 和 fresh restart 1/1。final readback 为
x86_64/API26/boot complete/package found/both paths present；emulator kill 后 process/port 消失，AVD 副本可恢复地
移入 Trash。Rosetta + software TCG 的长启动/安装耗时不作产品性能结论。至此 KSP-009 follow-up 的 strict
Release 与 x86 动态实物门都已关闭。

路线 B 的 official Rime plugin 在真实设备完成 preedit/candidate/commit，并与 QWERTY 共用唯一 transaction
writer；实际主题 gallery 与剪贴板入口均可达。其 email/URI 专用程度较弱，剪贴板历史默认开启（limit 10，
sensitive mask 开启），Accessibility tree 另有 5 个未描述 clickable subtree，均需选型后整改。两路线从固定
upstream tree 生成的 OpenTypeless patch 都是 49 个文件，分别 366,089/380,004 bytes，`git apply --check` 与
实际 apply 均 PASS；这只证明当前 clean replay，不替代 KSP-011 的长期同步机制。

完整矩阵见 [KSP-009 功能报告](../2026-08-15-ksp-009-keyboard-function-matrix.md) 与
[脱敏证据](../benchmarks/ksp-009-xiaomi-10-ultra.json)。KSP-009 没有读取剪贴板正文、没有保留密码截图，设备默认
IME、无障碍服务与屏幕/旋转设置均已恢复。KSP-009 单任务只关闭当时声明的证据；KSP-010 后续全图审计又发现
whole candidate editor/privacy P0，因此当时“可直接接受”的预期已被取代。

### 6.7 KSP-010 初审裁决边界（历史）

Route-A license/source inventory、selected-path common function、strict Release 与 arm64/x86 动态证据为 PASS；
路线 B 当前 GPL payload 继续排除。早期 72/100 工作表被 rubric-correct **80/100** 取代：synthetic test Schema、
candidate、UserDB 与 restart 使 Rime readiness 为 5/5，但不授权真实小鹤或 production RIM。

KSP-010 全图审计确认 whole candidate 仍有两个 P0：production source 的六类 mutator regex 至少命中 32 个
已审计调用点（排除 2 个 `commitText` 方法声明），另有 selection writer surface 与 5 个 `InputConnection` 文件；
SPIKE 只接 Voice，普通 key/QuickAction 仍 legacy，adapter QWERTY case 不覆盖真实 Shell；
merged manifest 是 `allowBackup=true` 且备份 IME/词典，并保留 profileable、SpellChecker、URI/content/SEND
import、alias、copy-to-clipboard、`POST_NOTIFICATIONS`、queries 与额外 exported surfaces。未来 restricted source
boundary 尚无 buildable artifact，不能把 exclusion 规则记为当前 PASS。ADR-0011 保持 `Proposed`，KSP-010 为
`PARTIAL`，KBD-001 不得开工。

下一 KSP-009 safety follow-up 必须在同一 buildable evaluation flavor/module 证明真实 key/Rime/Voice/Undo/
QuickAction 全经 one ETM、legacy capability 与 ETM 外 writer/IC 为零、Flag 互斥无 fallback；同时证明
`allowBackup=false`、UserDB/学习/历史/Secret backup/transfer 全域排除、上述 App surfaces 为零，并通过 Debug/
Release source+compiled/merged-manifest gates、strict clean builds 与 arm64/x86 动态矩阵。KSP-012 继续阻止真实
小鹤码表/词库随包，当前只允许用户显式导入可核验资源。

### 6.8 KSP-009 safety closure 与 KSP-010 最终接受

最终证据对象为不依赖 `:app` 的独立 `:route-a-safety-eval` module；whole upstream/candidate App 仍是
**FAIL / NOT SELECTED**。真实 View Latin/Rime/Voice/Undo/QuickAction 仅经一条互斥、无 fallback 的 Route-A。
非 editor-host production writer/`InputConnection` capability 为零，唯一 editor-host authority enclave 内精确
保留 7 条 ETM writer edge；source 与 Debug/Release whole-APK compiled gates 还拒绝 reflection、dynamic loader、
Unsafe、native/JNI delegation、non-host→host façade/type/edge expansion、package/property spoof 与
source/dependency/package drift。

restricted merged manifest 为 `allowBackup=false`；base 5 个敏感域和 cloud/device-transfer 各 9 个域全部排除，
仅一个受 `BIND_INPUT_METHOD` 保护的 exported evaluation service，无 permission/query/profileable/其他
component。architecture Python **30/30**、manifest Python **23/23**、JVM Debug/Release 各 **23/23**、clean
strict **216 tasks PASS**。final3 patch 123 files、10,501,449 bytes、SHA-256
`13a0073967cc8fe9e61fe31ffc46b3110ef9e60cb629faa5ba172d33d79a38b0`；fresh replay tree exact、strict
**216 tasks PASS**，三 APK 与 merged manifests byte-identical。

Debug/Test/unsigned Release SHA-256 为
`072873267bf817043bb022eced1a885ec28d6452ac93cf4f48347894430ad1d9` /
`fd8f3db9c42b82969961c39c1ff88a6be5c461371ee6e3b4b9da0292796161a1` /
`75a618a8a78c7ffdcc4d9d3319c5d7d859f3625999a6256b9105375f2c0d247d`。Xiaomi/API33 与 API26 x86_64
exact class 均 **OK (12 tests)**、0 failure、instrumentation code -1、runner RC 0。x86 streamed install 的
`Broken pipe` RC 1 保留为历史失败；稳定 package service 后 no-streaming main/test 均 `Success` RC 0，guest、
PID、ports 与临时 AVD 已清理且小米 PangIME/emulator-5554 未变。最终独立红队裁决 residual P0/P1=0、GO。

因此 KSP-009 safety follow-up 与 KSP-010 为 `DONE`、ADR-0011 为 `Accepted`；KBD-001 在该裁决时仍为 `TODO`，现已
由独立任务完成。
该结论不等于完整 APP、系统选中 IME E2E、正式签名 Release 或真实小鹤。KSP-011 后由本文件 7.1 节独立关闭，
KSP-012 后由 7.2 节关闭资源决策；SEC/TST/REL 仍不得跳过。

---

## 7. 调研结论

1. 当前语音安全核心值得保留；
2. 继续手搓完整键盘风险高；
3. Floris/Dictate 路线最接近目标产品形态；
4. fcitx 路线最接近目标 Rime 完整度；
5. 最终底座必须由相同垂直切片决定；
6. librime 是许可证清晰的 Rime 核心；
7. sherpa-onnx 适合作为本地 ASR runtime 候选框架；
8. Qwen3-ASR 优先作为自托管 Docker/服务器路线；
9. 所有编辑必须统一事务化；
10. 动作必须声明式、能力受限；
11. 学习必须默认显式；
12. 发布前许可证、模型和码表审计与功能测试同等重要。

### 7.1 KSP-011 fixed-upstream 执行结论

ADR-0011 的 upstream/fork 策略已由 KSP-011 落地：Floris official remote/commit/Git tree 与 direct codeload
archive bytes/SHA 分开锁定，JetPref remote 修正为 `patrickgold/jetpref`，librime recursion/Boost identity 一并记录。
维护队列不是早期 whole artifact binary patch，而是 3 个 finite source patches；historical final3 因 unknown-resource
preimage、generated SO 与 whole-App scope 被显式拒绝。

固定 archive normalized base tree `5a911de0dc2242f146b3cfcc47c2b8b1dc90f7e5` 经过 3-step exact tree chain 得到
`179eca9923d2e93af0acdadde454d901d58bf8c0` / 972 files。两个 path-independent offline replay 的 report/export
manifest exact，adversarial suite 44/44。故 KSP-011 为 DONE。实际跟随 upstream 更新一次、处理真实 conflict、生成
review proposal 仍属于 REL-009；不能用本次固定版本重放宣称 future upstream 无冲突。

### 7.2 KSP-012 小鹤来源与分发结论

[ADR-0012](../adr/0012-xiaohe-resource-distribution-policy.md) 已 Accepted。官方来源表明“小鹤双拼”是双拼
布局，“小鹤音形”是双拼加双形的完整方案；Rime 官方
`double_pinyin_flypy.schema.yaml` 则是“朙月拼音 + 小鹤双拼”的 GPL-3.0 Schema，不是完整音形资源。
Rime 对象固定在 `rime-double-pinyin@01a13287cbd27819be1c34fa1ddc1b3643d5001b` / tree
`a1c64a175f1d4f79938fa6da560a633933be7c2d` / schema blob
`4c78a06b5df625c82904ec2a6b07e161c79cf44a`，并固定审计 `luna_pinyin`、`stroke`、`default` direct refs 及未来
closure 输入；不同 package 的许可不能由顶层标签替代。

2026-08-16 审阅 Flypy 官方首页/about/download/sitemap 和公开帮助入口，在该公开范围未发现允许 OpenTypeless
复制、转换、修改、随包或下游再分发完整小鹤音形资源的明确授权；没有下载、OCR 或重建载荷。该 negative finding
不是法律意见，也不排除私下协议。主产品因此对真实资源与 GPL Schema 采用 zero-bundle，仅允许未来用户显式本地
导入 closed manifest v1；自报许可不自证，未受信包保持 `USER_PROVIDED_UNVERIFIED` / `LOCAL_ONLY`。

未来随包必须用 superseding Accepted ADR 记录明确书面授权，或明确接受 GPL 完整义务及对应源码/修改/构建/
NOTICE/SBOM/商店条款/法律审阅。KSP-012 不实现 RIM-003/008/011；synthetic fixture 例外不能被描述为真实小鹤。
