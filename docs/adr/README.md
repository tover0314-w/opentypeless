# OpenTypeless Architecture Decision Records

本目录保存从 DOC-002 起新增或正式替代的架构决策记录（ADR）。规范包中的
[`09_ADR_RESEARCH.md`](../opentypeless_specs/09_ADR_RESEARCH.md) 保留 ADR-001..012 的历史调研快照；它们不会在
本任务中被静默复制或改写。后续决策若替代其中内容，必须创建新的独立 ADR 并显式引用被替代记录。

## 创建流程

1. 从 [`0000-template.md`](0000-template.md) 复制新文件；
2. 使用未占用且单调递增的四位 ID，文件名为 `NNNN-lowercase-kebab-title.md`；
3. 先以 `Proposed` 提交背景、候选和验证计划；
4. 只有选择、后果和验证证据明确后才能改为 `Accepted`；
5. 把新 ADR 加入本页索引，并在对应 Task/PR/设计文档中引用；
6. 已接受决策发生实质变化时创建新 ADR，以 `Superseded` 关闭旧记录，不覆写历史结论。

涉及许可证、危险权限、持久格式、Secret 边界、默认网络行为、不可逆迁移、编辑器 authority、键盘底座或
Feature Flag 删除条件等不可逆决定时，实施前必须存在可引用的 `Accepted` ADR。ADR 不得包含真实 Secret、
用户正文、音频、私有词典或未经脱敏的设备数据。

## 状态

| 状态 | 含义 |
|---|---|
| `Proposed` | 正在审查，不能作为实施授权 |
| `Accepted` | 已接受，可按记录的边界实施 |
| `Rejected` | 已否决，仅保留决策历史 |
| `Deprecated` | 不再推荐，但尚未由单一新 ADR 完全替代 |
| `Superseded` | 已由后续 ADR 明确替代 |

## 必需内容

每个 ADR 必须包含 `Status`、`Background`、`Decision`、`Consequences` 和 `Validation` 五个二级章节。
`Accepted` ADR 的 Validation 必须写入可复现的命令、测试、基准、设备证据或审查结果，不能只写“以后验证”。
建议同时记录回滚路径、替代方案、关联任务和被替代 ADR。

## ADR 索引

| ID | 标题 | 状态 | 日期 | 关联任务 |
|---|---|---|---|---|
| [ADR-0001](0001-provider-config-secret-boundary.md) | Provider 配置与 SecretRef 边界 | Accepted | 2026-08-13 | `CFG-001` |
| [ADR-0002](0002-recognition-route-privacy-contract.md) | RecognitionRoute 隐私与失败边界 | Accepted | 2026-08-13 | `CFG-002` |
| [ADR-0003](0003-override-value-three-state-format.md) | OverrideValue 三态与持久编码 | Accepted | 2026-08-13 | `CFG-003` |
| [ADR-0004](0004-versioned-configuration-partitions.md) | Versioned configuration partitions | Accepted | 2026-08-13 | `CFG-004` |
| [ADR-0005](0005-effective-profile-resolution.md) | Effective profile resolution and hard safety precedence | Accepted | 2026-08-13 | `CFG-005` |
| [ADR-0006](0006-legacy-app-settings-global-config-migration.md) | Legacy AppSettings to versioned GlobalConfig migration | Accepted | 2026-08-13 | `CFG-006` |
| [ADR-0007](0007-legacy-app-profile-three-state-rule-migration.md) | Legacy AppProfile to three-state AppRule migration | Accepted | 2026-08-14 | `CFG-007` |
| [ADR-0008](0008-secret-ref-store-and-legacy-credential-shadow.md) | SecretRef Store and legacy credential shadow | Accepted | 2026-08-14 | `CFG-008` |
| [ADR-0009](0009-launchable-app-picker-without-broad-package-visibility.md) | Launchable App Picker without broad package visibility | Accepted | 2026-08-14 | `CFG-009` |
| [ADR-0010](0010-recoverable-settings-secret-transaction.md) | Recoverable settings and Secret transaction | Accepted | 2026-08-14 | `CFG-011` |
| [ADR-0011](0011-keyboard-base-evaluation.md) | Keyboard base evaluation and upstream boundary | Accepted | 2026-08-15 | `KSP-001`, `KSP-010` |
| [ADR-0012](0012-xiaohe-resource-distribution-policy.md) | Xiaohè resource distribution and local-import boundary | Accepted | 2026-08-16 | `KSP-012` |
