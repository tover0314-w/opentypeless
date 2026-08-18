# ADR-0004: Versioned configuration partitions

## Status

Accepted

## Background

`CFG-001..003` 已分别冻结 Provider/SecretRef、RecognitionRoute 与 `OverrideValue<T>`，但尚无一个把全局、App
和字段配置放在不同领域边界中的 versioned schema。旧 `AppSettings`/`AppProfile` 仍把空字符串、普通 boolean、
raw Key 和运行设置混在一起；在没有显式分域前实现 Resolver 或迁移，会把“继承”“关闭”和“显式 false/空值”
重新混淆。

架构 §15.1/15.2 与历史 ADR-010 要求 `GlobalConfig / AppRule / FieldRule`、三态覆盖以及
`硬安全 > 会话 > 字段 > App > 全局 > Provider 默认`。本任务只冻结下一步 Resolver 可消费的纯领域 schema，
不得读取旧设置、创建数据库表、选择迁移映射或把 route/provider registry 校验提前并入配置值对象。

## Decision

1. 新配置 schema 的 format version 为 1。`GlobalConfig` 精确包含 `formatVersion` 与 Keyboard、Voice、
   Processing、Privacy、Automation 五个不可变子域；未知 version 在构造时 fail closed。
2. 五个可解析叶子统一为：`voiceRouteId`、`processingMode`、`sendContext`、`historyEnabled`、`actionSetId`。
   AppRule 直接携带这五个 `OverrideValue`；FieldRule 通过同 shape 的 `RuleOverrides` 携带。全局 Voice、
   Processing、Privacy、Automation 子域也保留对应三态，供 CFG-005 继续解析到 Provider/内建默认。
3. Keyboard 当前只有一个必需、non-empty `layoutId`。它不是可禁用的策略叶子，不能用 Disabled 让基本键盘
   消失；未来增加布局覆盖必须另行定义安全 fallback。
4. `ProcessingMode` 是新 config domain 的闭集 `AUTO / EXACT / SMART / TRANSLATE`，不依赖 legacy
   `settings.ProcessingMode.VERBATIM`。旧值映射属于 CFG-006/007，不能在构造器里静默 fallback。
5. route/action/layout ID 使用 1..128 的 lower-ASCII config ID：首字符为字母，其余只允许字母、数字、点、
   下划线、连字符。`OverrideValue.Value("")` 在通用模型层仍合法，但进入这些 ID 字段必须拒绝；Inherit 与
   Disabled 保持独立状态。packageName 为 1..255 ASCII、至少两个非空点分 segment，不 trim、不用空串通配。
6. `FieldMatcher` 精确绑定 packageName 与现有纯领域 `FieldKind`。不使用不稳定的 fieldId，不接受 Android
   `EditorInfo`/inputType capability，也不执行 match；匹配和硬安全规则属于 CFG-005。
7. 所有模型是 immutable record/closed enum、不可序列化，诊断不得输出 package/layout/route/action ID、
   Override payload、Secret 或用户正文。它们不持有 Context、SharedPreferences、SQLite、JSON codec、Provider、
   callback、线程或网络 authority。
8. 本任务不把 ProviderConfig/RecognitionRoute 列表聚合进 GlobalConfig，也不交叉验证 route/provider ID；
   CFG-002 已将 registry capability/privacy cross-check 留给 REC-003/REC-009。CFG-004 不创建存储格式；
   CFG-006/011 才能把这些值映射到 versioned transaction schema。

未选择的方案：

- 复用旧 `settings.AppProfile`：其空字符串和普通 boolean 已丢失继承语义，且会把迁移夹带进本任务。
- 用 nullable/Optional 代替三态：不能区分 Disabled 与 Inherit。
- 让 FieldMatcher 保存任意 regex、class name、inputType 或 Android object：扩大外部输入和 capability 表面，
  并把 CFG-005 的匹配策略提前固化。
- 在 GlobalConfig 内保存任意 Map/JSON：破坏 exact shape、上限和静态门禁，也无法证明同一概念只有一套类型。

## Consequences

正面结果是 CFG-005 可以在一套相同类型的叶子上实现优先级，并且空字符串/false 不再承担继承语义；领域模型
可在纯 JVM、source gate 和 Debug/Release bytecode gate 中闭合验证。代价是本任务不产生运行行为：旧设置、
AppProfile UI、Provider registry 和存储仍然不变，直到后续迁移任务显式接线。

将来修改字段、ID 语法、ProcessingMode vocabulary 或 FieldMatcher shape 必须提升 format version 并新增 ADR；
不能原地重解释 version 1。

## Validation

2026-08-13 接受前已核对架构 §15、Backlog CFG-004、产品 §13、隐私边界、历史 ADR-010、现有
`AppSettings/AppProfile/AppProfileRepository` 与已完成 CFG-001..003：旧 repository 的确使用 blank/string/
boolean fallback，因此本任务必须保持无 I/O，避免在无迁移 fixture 时修改用户数据。ADR 结构必须通过：

```text
python3 scripts/verify_adrs.py
python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
git diff --check -- docs/adr
```

收口还必须实际通过 CFG-004 exact shape、version、ID/package/FieldKind、全部三态、空/false、raw-generic hostile
injection、immutability/non-serialization/redaction JVM 测试；source/compiled gate 必须闭合新增 binaries、generic
signatures 和无 legacy/settings/Android/persistence/codec authority；最后运行 fresh-cache strict 全量构建。设备
assemble 不能冒充小米真机执行，结果必须写回本节。

最终实测：`ConfigurationPartitionsTest` 9/9、app JVM 724/724、source architecture 88/88、compiled gate
81/81、Debug/Release production variants 2/2 全部 PASS；ADR validation 为 4 decisions、生命周期单测 4/4。
以全新 `GRADLE_USER_HOME=/tmp/opentypeless-cfg004-gradle.QH7vkq` 和 strict dependency verification 执行
`scripts/verify_android.sh`，一次 `BUILD SUCCESSFUL`（2m39s，187 tasks，184 executed / 3 up-to-date），覆盖
clean、全 JVM、lintRelease、Debug/Release 与 AndroidTest assemble。小米 10 Ultra `be4e2015` 在线且熄屏，
最终 debug APK 的显式 serial 安装被系统以 `INSTALL_FAILED_USER_RESTRICTED` 拒绝，因此设备安装/执行是
NOT RUN；未唤醒、解锁或绕过限制。

## Rollback

在 CFG-005/006 接线前，可删除 CFG-004 模型、测试、门禁与本 ADR 索引，不影响旧设置或用户数据。接线后
version 1 只能通过新 version 与显式迁移替代，不能原地改变字段含义。

## References

- Task：`CFG-004`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- 关联 ADR：历史 `ADR-010`、[ADR-0003](0003-override-value-three-state-format.md)
