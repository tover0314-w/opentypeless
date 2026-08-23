# ADR-0005: Effective profile resolution and hard safety precedence

## Status

Accepted

## Background

`CFG-001..004` 已冻结 Provider/Route、三态 Override 与 versioned Global/App/Field schema，但尚未有唯一权威
实现把这些值解析为运行时可消费的有效配置。历史 ADR-010、产品 §13.2 与架构 §15.3 已接受固定顺序
`硬安全 > 会话 > 字段 > App > 全局 > Provider 默认`，并要求每个结果携带来源和解释；若不同 UI/IME/Provider
各自重复计算，`Disabled`、显式 `false`、敏感字段和重复规则会产生不一致。

本任务只实现纯领域 Resolver。它不能读取旧 `AppSettings`/`AppProfile`、数据库或 Android metadata，不能选择
Provider、执行网络或把 CFG-006/007 迁移夹带进来。输入规则可能来自未来持久化或导入，因此集合数量、重复项、
null、错误 package/field match 和诊断泄漏都必须在边界 fail closed。

## Decision

1. `EffectiveProfileResolver` 是配置优先级的唯一领域实现。输入精确包含 GlobalConfig、最低层 ProviderDefaults、
   bounded AppRule/FieldRule、当前会话 RuleOverrides，以及 exact packageName/FieldKind；不接受 Context、EditorInfo、
   Provider 实例、Secret、Repository、callback、Map 或任意策略函数。
2. 普通字段对五个叶子分别独立解析：Session、exact package+FieldKind 的 Field、exact package 的 App、Global、
   Provider default；遇到首个非 Inherit 的 `Value` 或 `Disabled` 即停止。一个层对某叶 Inherit 不会遮挡更低层，
   显式 `false` 仍是 Value，Disabled 保持独立终态。
3. ProviderDefaults 的五个叶子必须全部为 Value 或 Disabled，禁止 Inherit，保证每个叶必有终态。Keyboard layout
   是 CFG-004 已要求的 global non-disabled 值，直接以 Global 来源进入 EffectiveProfile，不伪造 Provider fallback。
4. `FieldKind.SENSITIVE` 使用不可由请求覆盖的硬规则：voiceRoute、sendContext、history 和 actionSet 为 Disabled，
   processingMode 为 `EXACT`。它优先于 session/field/app/global/provider，避免 Smart/Translate、联网 route、上下文、
   历史或动作配置在敏感字段重新启用。该 profile 仍不是录音/联网 authority，调用方必须继续执行现有硬策略。
5. `ResolvedValue<T>` 保存一个禁止 Inherit 的 OverrideValue、闭合 RuleSource 与闭合 ResolutionExplanation；不用
   任意 explanation String，避免 package/ID/payload 进入日志。`toString()` 只显示 source/explanation/state，
   EffectiveProfile/Request/Defaults 同样脱敏且不可序列化。
6. 每次请求最多 256 条 AppRule 与 512 条 FieldRule，逐项有界复制后再解析；重复 package AppRule 或重复
   `(packageName, FieldKind)` FieldRule 一律拒绝。精确、大小写敏感匹配；不使用 first/last wins、wildcard、regex、
   trim 或 HashMap 覆盖来隐藏歧义。
7. CFG-005 不进行 Provider registry/capability/privacy cross-check，不验证 route/action ID 是否存在，不持久化或
   迁移配置，不接线 production settings/UI。CFG-006/007 负责旧值迁移，REC 任务负责 registry 验证，UI-003/
   CFG-010 使用同一 ResolvedValue 展示来源而不得重新实现优先级。

未选择的方案：

- 用 nullable/Optional 终态：会再次混淆 Disabled 与缺失；
- 把列表顺序当优先级：重复配置会因数据库/导入顺序改变，无法解释；
- 允许调用方传入“硬规则”对象：普通配置可能铸造或删除安全覆盖；
- 在 Resolver 内读取 legacy settings/Provider registry：会夹带迁移、I/O 和运行 authority，破坏纯 JVM 边界。

## Consequences

所有叶子都得到可解释、稳定且不含 Inherit 的终态；敏感字段不能被低层配置放宽，重复规则也不会静默获胜。
代价是 future store 必须先规范化或拒绝重复规则，并显式提供完整 ProviderDefaults。Resolver 输出本身不是
Provider、网络、Action 或 editor authority；production 在 CFG-006/007 接线前仍使用旧设置路径。

改变层级顺序、敏感硬规则、重复规则策略、上限或 explanation vocabulary 必须以新 ADR 替代本决策，不能靠
UI 或调用方局部改变。

## Validation

接受前已核对历史 ADR-010、产品 §13、架构 §15、CFG-004 ADR、FieldKind 与现有敏感字段策略；所选顺序和
硬规则均未放宽既有安全边界。ADR 结构必须通过：

```text
python3 scripts/verify_adrs.py
python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
git diff --check -- docs/adr
```

收口必须实际通过六层逐叶表驱动、Disabled/false/Inherit、敏感硬覆盖、exact matcher、重复/超限/lying iterable、
input immutability、全部来源/解释、redaction/non-serialization JVM 测试；source/compiled gates 必须锁定唯一
Resolver、closed result vocabulary、无 Android/I/O/legacy/provider execution authority；最后执行 fresh strict
全量构建。小米 APK assemble 不能冒充真机执行，最终安装/运行结果必须单独记录。

## Rollback

在 CFG-006/007 production 接线前，可删除 Resolver/result/tests/gates 与本 ADR 索引，不影响旧设置或用户数据。
接线后只能回滚到保留旧配置备份的已验证 Feature Flag 路径，不能以局部 UI 计算替代唯一 Resolver。

## References

- Task：`CFG-005`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- 关联 ADR：历史 `ADR-010`、[ADR-0004](0004-versioned-configuration-partitions.md)
