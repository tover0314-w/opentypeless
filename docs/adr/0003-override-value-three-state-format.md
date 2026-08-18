# ADR-0003: OverrideValue 三态与持久编码

## Status

Accepted

## Background

`CFG-003` 要把配置覆盖从目前容易混淆的 nullable/空字符串/boolean sentinel 收敛为
`Inherit / Disabled / Value<T>` 三态。后续 `AppRule`/`FieldRule` 会同时保存字符串、布尔值和闭集枚举；如果 JSON
或数据库只保存 nullable value，就无法区分“继承”“显式关闭”“显式空字符串”以及 `Value(false)`。

该表示会成为 `CFG-004`/`CFG-006` 的持久格式输入，因此必须在接线前冻结 format version、状态标签、缺失值语义、
输入上限和失败策略。当前生产使用 Android `org.json` 与 SQLite，但本任务不得新建表、修改旧
`SharedPreferences`/数据库、迁移 `AppSettings` 或实现 resolver。

## Decision

1. 在纯 Java `com.opentypeless.android.config` 中新增不可变、不可序列化的 sealed `OverrideValue<T>`。只 permits
   singleton `Inherit<T>`、singleton `Disabled<T>` 与 non-null `Value<T>`；工厂方法是唯一构造入口。
   `Value("")` 与 `Value(false)` 都是合法显式值，且与另外两态不相等。
2. 新增无 I/O 的 `OverrideValueCodec<T>`。调用方提供窄 `ScalarCodec<T>` 把一个领域标量映射到/从一个有界
   well-formed UTF-16 字符串；codec 不接受 Context、数据库、Cursor、SharedPreferences、文件、网络、Secret、
   reflection 或可变 Map。adapter 失败统一变为不携带 payload/cause 的稳定 `FormatException`。
3. JSON format version 1 使用 exact positional array，避免 object duplicate-key 的 last-wins 歧义：

   ```json
   [1,"inherit",false]
   [1,"disabled",false]
   [1,"value",true,"<encoded scalar>"]
   ```

   array 长度、每一项 JVM 类型、状态标签、presence flag 与结尾都必须精确；拒绝未知版本/状态、额外项、null
   scalar、number/boolean coercion、尾随数据和畸形 JSON。输入 JSON 最多 32,768 UTF-16 units，encoded scalar 最多
   4,096 UTF-16 units。
4. DB seam 是无 Android 依赖的 exact row：
   `(formatVersion:int, state:String, valuePresent:boolean, encodedValue:String?)`。version 固定 1；state 仅
   `inherit/disabled/value`。前两态必须 `valuePresent=false && encodedValue=null`；Value 必须
   `valuePresent=true && encodedValue!=null`，所以空字符串不会变成缺失。CFG-004/006 将来只能把这四列映射到
   versioned schema，不得改用 null/空字符串 sentinel。
5. codec 只在 Value 态调用 scalar adapter；Inherit/Disabled round-trip 不执行 adapter。所有 model/row/codec
   `toString()` 脱敏 value 和 adapter，异常不得包含 encoded scalar。无 codec 实例或 row 是存储/解析 authority；
   未知输入 fail closed，不能退回 Inherit 或 Disabled。
6. 本任务不选择具体 AppRule schema、不保存任意复合 JSON、不定义 enum 名称迁移、不执行 SQLite/文件 I/O，也不
   迁移旧配置。`CFG-004` 定义字段与 type-specific scalar codec，`CFG-006/007/011` 负责版本迁移和事务。

未选择的方案：

- `null=inherit`、空串或 `false=disabled`：无法无损表达显式空/false，且迁移时会静默改变用户意图。
- JSON object `{state,value}`：常见 parser 对 duplicate key 采用 last-wins，额外 key 也容易被忽略；exact array
  更容易闭合长度和类型。
- Java serialization/Parcelable/Bundle：会扩大持久和 IPC 表面，不能提供显式 format version 与迁移边界。
- 在本任务直接建 SQLite 表：CFG-004 字段/schema 尚未冻结，会越过 CFG-006/011 的迁移与事务验收。

## Consequences

正面结果是三态在内存、JSON 和未来 DB 列之间可以一一对应，显式空字符串和 false 不再被 sentinel 吞掉；解析
输入有硬上限、未知状态 fail closed，且 model 可在纯 JVM 验证。代价是每个持久字段需要四列或等价复合列，调用方
还必须提供 type-specific scalar codec；未来改变状态标签、上限或 JSON/DB shape 必须新 format version/ADR。

当前运行行为、旧设置和数据库完全不变。该 codec 不是通用对象序列化器，不允许保存 Secret、正文或未审计复合
对象；CFG-004 只能为已冻结的配置标量接线。

## Validation

2026-08-13 接受前已核对架构 §15.1、Backlog CFG-003、历史 ADR-010 和现有生产存储：项目使用 `org.json`、
SQLite/SharedPreferences，但没有 `OverrideValue` 或新配置 schema；因此选择无 I/O seam，避免提前修改持久数据。
实现后实际证据：

```text
python3 scripts/verify_adrs.py
PASS — template + index, 3 standalone decisions

python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
PASS — 4/4

git diff --check -- docs/adr
PASS

OverrideValueTest + OverrideValueCodecTest
PASS — 13/13

python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v
PASS — 87/87; production source scan PASS

./gradlew --dependency-verification=strict :architecture-gate:test :architecture-gate:verifyCompiledArchitecture
PASS — compiled gate 80/80; Debug/Release 2/2

GRADLE_USER_HOME=/tmp/opentypeless-cfg003-gradle.SNqLlD scripts/verify_android.sh
PASS — BUILD SUCCESSFUL in 2m28s; 187 tasks (184 executed, 3 up-to-date)
```

全 app JVM **715/715 PASS**；`lintRelease`、Debug/Release、AndroidTest assemble 与 strict dependency
verification 均包含在上述 fresh-cache run。小米 10 Ultra `be4e2015` 在线，但最终 app-debug 安装被
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user` 拒绝，因此设备执行 **NOT RUN**；没有把 APK
assemble 冒充真机测试。实现没有新增 dependency、权限、Android component、schema、持久 I/O、迁移或网络。

## Rollback

在 CFG-004/006 接线前，可删除模型、codec、测试与门禁并把 CFG-003 恢复为 TODO，不影响旧设置或用户数据。
接线后改变 format 只能通过新 ADR 与显式迁移版本，不能原地重解释 version 1。

## References

- Task：`CFG-003`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- Backlog：`docs/opentypeless_specs/07_IMPLEMENTATION_BACKLOG.md`
- 测试矩阵：`docs/opentypeless_specs/08_TEST_VALIDATION.md`
- 关联 ADR：历史调研 `ADR-010`
