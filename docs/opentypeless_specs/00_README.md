# OpenTypeless 产品、架构与开发规范包

> 文档版本：1.0  
> 生成日期：2026-08-12  
> 适用仓库：`dengxuezhao/opentypeless`  
> 代码基线：`main@67be488dcd2e9f36520618f9f644f97c3ec02b98`  
> 目标读者：产品负责人、Android/IME 工程师、语音与模型工程师、安全工程师、测试工程师，以及 Codex / Claude Code 等编码代理  
> 状态：**拟议目标方案（Proposed Target Architecture）**；涉及键盘底座、许可证和 Rime 集成的不可逆决策，必须先完成 ADR 技术验证


## 1. 这套文档解决什么问题

OpenTypeless 当前 Android 版本已经具备一套相当扎实的语音输入安全骨架：多识别后端、可修订 partial、目标输入框绑定、Raw 恢复、Undo、显式 Teach、个人词典、事实完整性保护、历史加密和标准 Android 语音入口。但当前实现仍是一个**语音输入层**，并非可长期承载完整 QWERTY、Rime/小鹤音形、真流式 ASR、可编排动作和跨端配置的输入平台。

本规范将下一阶段目标定义为：

> **语音优先、键盘完整、可编排、隐私可控的个人输入平台。**

本包不是“一次性重写说明”，而是用于指导一系列可独立验证、可回滚、可逐步合并的垂直切片。

## 2. 文档清单与阅读顺序

| 顺序 | 文件 | 用途 |
|---:|---|---|
| 1 | [`01_PRODUCT_DESIGN.md`](01_PRODUCT_DESIGN.md) | 产品定位、边界、信息架构、核心场景、功能规格与长期路线 |
| 2 | [`02_ARCHITECTURE_DEVELOPMENT.md`](02_ARCHITECTURE_DEVELOPMENT.md) | 当前问题、目标架构、接口、状态机、线程模型、模块拆分与迁移方案 |
| 3 | [`03_UX_DESIGN_PROTOTYPES.md`](03_UX_DESIGN_PROTOTYPES.md) | 设计语言、组件规范、导航结构、键盘和管理端低保真原型 |
| 4 | [`04_ACTION_PROTOCOL_V1.md`](04_ACTION_PROTOCOL_V1.md) | 自定义按钮、Connector、Action、Placement、Docker 协议与安全限制 |
| 5 | [`05_DATA_PERSONALIZATION.md`](05_DATA_PERSONALIZATION.md) | 词典、纠正规则、Rime UserDB、历史、反馈和学习建议的数据模型 |
| 6 | [`06_SECURITY_PRIVACY.md`](06_SECURITY_PRIVACY.md) | 威胁模型、信任边界、敏感字段、网络、密钥、供应链和日志策略 |
| 7 | [`07_IMPLEMENTATION_BACKLOG.md`](07_IMPLEMENTATION_BACKLOG.md) | 按依赖顺序拆细的开发任务、优先级、交付物、测试和完成定义 |
| 8 | [`08_TEST_VALIDATION.md`](08_TEST_VALIDATION.md) | 单测、契约测试、Instrumentation、真机矩阵、性能和发布门禁 |
| 9 | [`09_ADR_RESEARCH.md`](09_ADR_RESEARCH.md) | 同类项目调研、许可证、键盘底座决策矩阵和架构决策记录 |
| 10 | [`10_RELEASE_OPERATIONS.md`](10_RELEASE_OPERATIONS.md) | 分支、CI、版本、迁移、签名、发布、回滚和运维 |
| 11 | [`AGENTS.md`](AGENTS.md) | Codex / Claude Code 必须遵守的实现约束 |
| 12 | [`TASK_TEMPLATE.md`](TASK_TEMPLATE.md) | 每个编码任务的输入、输出和验收模板 |
| — | [`OpenTypeless_FULL_SPEC.md`](OpenTypeless_FULL_SPEC.md) | 上述核心文档合并后的单文件版本，适合一次性交给编码代理建立全局上下文 |
| — | [`PACKAGE_VALIDATION.md`](PACKAGE_VALIDATION.md) | 规范包结构、任务 ID、标题和围栏完整性校验摘要 |
| — | [`FILE_MANIFEST.md`](FILE_MANIFEST.md) | 规范包文件大小与 SHA-256 清单 |
| — | [`../COMPATIBILITY.md`](../COMPATIBILITY.md) | 当前 Android/desktop/config/protocol/schema 兼容矩阵与真实 authority |
| — | [`../../CHANGELOG.md`](../../CHANGELOG.md) | 未发布变更、版本变更 ID 与发布历史入口 |

仓库根目录的 `README.md`、`README_zh.md` 与 `AGENTS.md` 均把本文件作为规范包唯一入口；编码代理从
根目录开始即可定位设计文档、任务 Backlog、关联 ADR 调研与测试矩阵，无需猜测文件位置。
从 DOC-002 起新增或替代的正式架构决策统一进入 [`docs/adr/`](../adr/README.md)；本包
`09_ADR_RESEARCH.md` 中 ADR-001..012 继续保留为生成时的历史调研快照。
版本或兼容边界变更还必须同步更新根 [`CHANGELOG.md`](../../CHANGELOG.md) 与
[`docs/COMPATIBILITY.md`](../COMPATIBILITY.md)，不能只调整 App SemVer 或实现常量。

## 3. 规范优先级

发生冲突时，按以下顺序执行：

1. 隐私、安全和输入目标正确性；
2. `AGENTS.md` 的不可违反规则；
3. 已接受的 ADR；
4. 架构文档中的接口与状态机；
5. 产品功能文档；
6. UX 和视觉细节；
7. 具体任务描述。

编码代理不得用“简化实现”为理由绕过更高层级的约束。

## 4. 当前代码基线摘要

截至基线提交：

- Android 只有一个 `:app` Gradle 模块；
- 使用 Java 17，`minSdk 26`、`targetSdk 35`；
- 管理端页面主要由 Java 代码程序化构建 View；
- `OpenTypelessImeService` 同时承担 UI、编辑器生命周期、语音入口、Undo/Raw/Teach 和多项导航职责；
- `VoicePipeline` 同时承担录音、识别、降级、个性化、LLM、事实保护和结果组装；
- 本地 SenseVoice partial 是约每 750 ms 重识别有界前缀，不是真正增量流式；
- 当前 README 明确把 Android 0.2 定义为语音输入层，而不是完整 QWERTY；
- 最新基线 CI 的 Android 构建曾因 `aapt2` dependency verification 元数据缺失而失败，因此所有新功能前必须先恢复 `main` 绿灯。

## 5. 给 Codex / Claude Code 的使用方式

### 5.1 首次接手仓库

将以下内容一起放入仓库根目录或上下文：

```text
AGENTS.md
00_README.md
01_PRODUCT_DESIGN.md
02_ARCHITECTURE_DEVELOPMENT.md
07_IMPLEMENTATION_BACKLOG.md
08_TEST_VALIDATION.md
09_ADR_RESEARCH.md
```

然后只指定一个任务 ID，例如：

```text
实现 CORE-004：EditorSessionSnapshot。
严格遵守 AGENTS.md。
开始前读取关联 ADR 和架构章节。
不要实现后续任务。
完成后按 TASK_TEMPLATE.md 输出变更、测试、风险和未完成项。
```

### 5.2 每个任务的执行原则

- 一个任务对应一个可审查的分支或提交组；
- 先补测试或契约，再迁移实现；
- 新实现通过 Feature Flag 接入，旧实现可回退；
- 不允许一次 PR 同时做键盘底座替换、UI 重构和语音管线重写；
- 每个任务必须给出实际执行过的命令和结果；
- 不得把“编译通过”当作 IME 正确性的充分证据。

## 6. 立即执行顺序

在任何新功能前，依次完成：

1. 修复当前 Android CI dependency verification；
2. 固化最新基线验收报告；
3. 新建 ADR 和架构测试骨架；
4. 引入统一 `EditorSession` 与 `EditorTransactionManager`；
5. 将现有语音提交改为统一编辑事务；
6. 引入组合态协调器；
7. 重构配置域与诊断页；
8. 完成键盘底座技术验证；
9. 再进入完整 QWERTY、Rime、小鹤音形和动作平台。

## 7. 额外补充内容

除用户明确要求的产品、架构、开发顺序、原型和测试外，本规范还补充了：

- 键盘底座许可证与上游维护策略；
- 威胁模型和数据流披露；
- 配置及数据库迁移版本化；
- Feature Flag 和回滚策略；
- Android IME 生命周期与内存预算；
- 无障碍、国际化和大字体要求；
- 供应链校验、模型来源和发布签名；
- 编码代理的禁止事项与任务交付模板；
- 桌面端与 Android 长期共享协议，而不是强行共享 UI 代码。

## 8. 文档维护规则

- 所有不可逆决策必须新增或更新 ADR；
- 接口或协议变更必须更新版本号、迁移说明和契约测试；
- 任务完成后在 Backlog 中标记状态，但不要删除历史；
- 产品宣称必须有测试或基准依据；
- 星标数、项目活跃度和外部 API 能力属于时间敏感信息，只作为 2026-08-12 调研快照。
