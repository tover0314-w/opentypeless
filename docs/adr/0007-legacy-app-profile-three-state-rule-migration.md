# ADR-0007: Legacy AppProfile to three-state AppRule migration

## Status

Accepted

## Background

Android 0.2 把每个应用的语音处理偏好保存为 `opentypeless_app_profiles/profiles_v1` JSON 数组。每条旧
`AppProfile` 固定包含 package、`ProcessingMode`、target language、custom instructions 和普通 boolean
`sendContext`。CFG-004 已冻结 format-1 `AppRule` 的五个三态叶子，CFG-005 已冻结解析优先级，但尚未定义旧
profile 如何进入该模型、如何保留无法表示的字段，以及旧 `sendContext=false` 应当解释为 Inherit、Disabled
还是显式 Value。

旧 repository 在 profile 存在时总会用其 mode 与 sendContext 覆盖全局值；`sendContext` 缺失时 0.2 的读取
默认也是 false。因此把 false 迁成 Inherit 会在全局配置为 true 时扩大上下文披露，既改变旧行为，也破坏
隐私边界。另一方面，新 AppRule 没有 target language 或 custom instructions 叶子；把这些值强塞进 route、
action ID 或其他字符串会制造错误语义，并扩大正文样式数据的复制面。

旧 store 没有 source revision。若只在首次迁移写一个自增 marker，旧 0.2 二进制在回滚后改写 `profiles_v1`
时不会更新 marker，新 shadow 会永久陈旧。CFG-007 因而需要从实际 source 重算有界、规范化的 projection，
而不是伪造旧版本不存在的 revision。

## Decision

1. CFG-007 的 migration version 与 AppRule target format 均为 1，source version 固定为 `0.2`。projection 与
   `profiles_v1` 保存在同一 `opentypeless_app_profiles` SharedPreferences 文件中，使用独立
   `app_rules_v1_` 前缀。一次同步 `Editor.commit()` 写入完整 rules JSON、migration/source/format version 与
   `legacy_backup_retained=true`。旧 key 不删除、不改名。
2. 每条合法旧 profile 精确映射为一个 exact-package `AppRule`：`voiceRouteId=Inherit`、
   `historyEnabled=Inherit`、`actionSetId=Inherit`；旧 `AUTO / VERBATIM / SMART / TRANSLATE` 分别映射为
   `Value(AUTO / EXACT / SMART / TRANSLATE)`；旧 sendContext 无论 true 还是 false 都映射为
   `OverrideValue.Value(boolean)`。尤其 false 绝不是 Inherit 或 Disabled。
3. 旧 target language 与 custom instructions 无 format-1 AppRule 目标，只保留在 `profiles_v1` 备份中；target
   projection 不复制它们、Secret、URL、model、正文或未知字段。CFG-007 不把 profile 的空字符串解释成新的
   三态 sentinel。
4. source 读取最多接受 100 条 actual 0.2 profile 和 1,000,000 UTF-16 units 的 JSON。package、mode、
   target language、custom instructions 与 sendContext 必须满足 0.2 的确切类型和界限；缺失字段按 0.2 默认
   `AUTO / empty / false` 解释。错误类型、未知 mode、重复 package、超限、畸形 UTF-16/JSON 或非对象 row
   均以稳定、无 payload 的 MigrationException fail closed，且不得写 target。
5. target rules 按 package 排序并使用 ADR-0003 canonical override JSON 编码。每次迁移都从 source 重算
   projection：完整现有 target 与 projection 相等时零写；合法 source 改变时一次 commit 刷新全部 target；
   未知 version、partial/corrupt target 或错误 target 类型不自动修补。提交后必须 readback 并与预期 immutable
   `List<AppRule>` 精确相等。
6. `AppProfileRepository` 的 load/get/list 首先验证并刷新 shadow；save/delete 在一个进程级临界区中先验证
   现有 source/target，再用同一个同步 commit 同时写新 `profiles_v1` 和完整 projection。commit/readback 失败
   以无正文错误结束。旧 profile 仍是 production runtime 与 rollback authority；`loadMigratedAppRules()` 只
   返回 immutable shadow，不启用 Resolver、route、Provider、UI 或网络执行。
7. migration raw Map、JSON、package、custom instructions 与 Android persistence capability 只存在于
   package-private migration/repository family。异常、diagnostic 与内部 `toString()` 不得输出 package、旧值、
   target payload、hash、Secret 或 preference contents。

未选择的方案：

- `sendContext=false → Inherit`：会在全局 true 时静默开始披露旧用户明确关闭的上下文。
- `sendContext=false → Disabled`：Disabled 表示能力不可用，而旧值只是显式 boolean false，会改变来源解释。
- 为旧 store 追加并信任 source revision：旧二进制不会维护它，回滚后再升级会产生陈旧规则。
- 把 target language/custom instructions 复制到 action/route ID：领域语义错误且扩大敏感样式数据复制面。
- 写入第二个 SharedPreferences 文件或继续使用 `apply()`：不能与 legacy source 用一次 durable commit 同步。
- 删除旧 profile：会破坏当前 UI/IME 与回滚路径，且 CFG-011 尚未完成最终 authority 切换。

## Consequences

升级后每个旧 profile 都有一个可重复验证的三态 AppRule shadow，旧 false 意图不会被全局默认覆盖；旧二进制
或旧 UI 改写 source 后，新版本会按实际内容刷新 projection。代价是迁移期继续保存 legacy target language 与
custom instructions，且每次 profile 读取需解析最多 100 条的有界 source/target。该 shadow 不证明 route/action
registry 存在，也不代表生产已切到 EffectiveProfileResolver。

改变 false 解释、mode 映射、target key/JSON shape、source limit、备份保留或运行时 authority 必须提高格式并
用新 ADR 替代；不得原地重解释已写入的 format 1。

## Validation

接受前已逐项核对 Android 0.2 commit `c8d8161` 的 `AppProfile`/`AppProfileRepository` actual JSON shape、当前
AppRule/OverrideValue/EffectiveProfileResolver、ADR-0003..0006、Backlog CFG-007 与隐私 hard rules。持续验证
命令固定为：

```bash
python3 scripts/verify_adrs.py
python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v
cd android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest --tests 'com.opentypeless.android.settings.LegacyAppProfileMigrationTest' \
  :architecture-gate:check
```

验收必须覆盖 actual 0.2 JSON fixture、100 条上限、所有 mode、显式 false、缺失默认、重复/错误/超限 source、
unknown/partial/corrupt target、相同 projection 零写、旧 source 改写刷新、commit/readback failure、legacy backup、
不可表示字段不复制，以及迁移前旧 `AppProfileRepository.apply` 与迁移后 Resolver 的可表示叶子快照一致。完整
strict build、Debug/Release compiled gate、真实 Android SharedPreferences、模拟器和小米 10 Ultra 结果分别记录。

2026-08-14 实际验收：迁移 JVM 9/9、app JVM 752/752、source architecture 91/91、compiled gate 86/86、
Debug/Release 2/2 全部 PASS；全新 `GRADLE_USER_HOME` 下 strict clean `scripts/verify_android.sh` 以 187 tasks
`BUILD SUCCESSFUL`（2m26s）。`LegacyAppProfileMigrationInstrumentedTest` 在 API36 emulator 与小米 10 Ultra
Android 13/API33 上均 2/2 PASS。最终 app-debug/androidTest/release-unsigned SHA-256 分别为
`3a8f1da6e2c60dc1dfa0178c801ec56363974a35eac6f617d3f76f08fa10d022`、
`c2f6ed3d48130f259acc8c819067141f2101555a9e7be9c07a4ac7971f7d1b78`、
`c32999dd7fef0c5c35a9c3bb9e04b5efbbe35a6c105a2536c5552ba05f15bab2`。

## Rollback

在 CFG-008/011 或任何 runtime consumer 接线前，可以停止读取 `app_rules_v1_` projection 并删除 CFG-007
代码；`profiles_v1` 从未删除，当前 UI/IME 可继续直接使用。旧二进制会忽略未知 target keys。已经写入的 inert
projection 可以保留，或由后续显式 migration version 清理；不得在回滚时清空旧 target language/custom
instructions 或把 projection 当作 source 反写旧数据。

## References

- Task：`CFG-007`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- 测试矩阵：`docs/opentypeless_specs/08_TEST_VALIDATION.md`
- 关联 ADR：[ADR-0003](0003-override-value-three-state-format.md)、
  [ADR-0004](0004-versioned-configuration-partitions.md)、
  [ADR-0005](0005-effective-profile-resolution.md)、
  [ADR-0006](0006-legacy-app-settings-global-config-migration.md)
