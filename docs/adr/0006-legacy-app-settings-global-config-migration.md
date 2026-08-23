# ADR-0006: Legacy AppSettings to versioned GlobalConfig migration

## Status

Accepted

## Background

Android 0.2 把全局语音路线、处理模式、上下文、历史以及 Provider URL/model、语言、个性化和 Secret
混在 `opentypeless_settings` 与 `SecurePreferences` 中。CFG-004 已冻结 format 1 的 `GlobalConfig`，但没有
定义旧值映射、持久 owner、备份标记或失败策略。若迁移在多个 SharedPreferences 文件间双写、把旧空值解释为
三态，或提前复制 Secret，就会产生半配置、意图漂移或明文扩散。

当前新 schema 只能无损承载旧 backend 对应的 route ID、ProcessingMode、sendContext 与 historyEnabled；
Keyboard 没有旧字段，Automation/Action 也不存在。旧 Provider endpoint/model、语言、polish、翻译、个性化、
录音上限及 Secret 尚没有完整的 versioned target，其中 SecretRef Store 明确属于 CFG-008。因此 CFG-006 必须
迁移可表示的全局配置，同时保留旧存储作为可验证备份，不得通过丢弃未映射字段伪造“全量迁移”。

## Decision

1. CFG-006 的迁移版本为 1、来源版本固定为 `0.2`、目标 `GlobalConfig` format 固定为 1。目标 projection 与
   旧普通设置保存在同一 `opentypeless_settings` SharedPreferences 文件中，使用独立 `config_v1_` 前缀；一次
   `Editor.commit()` 同时写入全部 projection、migration version、source revision 与
   `legacy_backup_retained=true`。旧键不删除、不改名，因此提交失败或进程终止时仍可继续使用旧设置。
2. 旧 backend 精确映射为稳定 route ID：`OPENAI_COMPATIBLE → legacy.openai-compatible`、
   `LOCAL_OFFLINE → legacy.local-offline`、`DASHSCOPE_STREAMING → legacy.dashscope-streaming`、
   `SYSTEM_ON_DEVICE → legacy.system-on-device`、`SYSTEM_DEFAULT → legacy.system-default`。0.2 没有
   keyboard 字段，迁移使用唯一兼容默认 `latin.base`；没有 Action 的旧版本迁移为显式 Disabled。
3. 旧 `AUTO / VERBATIM / SMART / TRANSLATE` 分别映射为新 `AUTO / EXACT / SMART / TRANSLATE`。
   `send_context` 与 `history_enabled` 均保存为显式 `OverrideValue.Value(boolean)`，不能把 `false` 解释成
   Inherit 或 Disabled。五个 override 使用 ADR-0003 的 canonical format-1 JSON，不能另造空字符串 sentinel。
4. 迁移读取 actual 0.2 key/type 形状。缺失键采用 0.2 兼容默认；错误 JVM 类型、未知 enum、负 revision、未知
   migration version、部分/矛盾 target 或 codec 失败均以稳定、无 payload 的 MigrationException fail closed，
   且不得提交任何 target key。相同 source revision 重复执行只验证并返回，不再次写盘；旧设置 revision 改变时
   以一个原子提交更新整个 projection。
5. 旧设置正常 `save()` 必须在原有单次普通设置提交中同步写入同一 projection。现有 recovery journal 仍以旧
   `AppSettings` 为唯一回滚源；恢复旧设置时用同一纯映射重建 projection，因此不用把新 payload 或 Secret 复制
   到 journal，也不会出现跨文件事务。
6. 新 projection 不复制 API Key、Bearer、SecurePreferences ciphertext、URL、model、语言、自定义指令、用户
   正文或任意未知 key。旧 Provider/语言/个性化/录音字段留在原有备份并继续由旧 runtime 使用；CFG-007 迁移
   AppProfile，CFG-008 迁移 Secret identity，CFG-011 决定最终 ConfigStore 事务与切换。CFG-006 不启用新
   Resolver、Provider route、UI 或网络 authority。
7. projection 的公开读取只返回 immutable `GlobalConfig`；migration marker、raw preference Map、旧
   `AppSettings`、Secret 与 Android Context 不进入 config domain。所有诊断、异常和 `toString()` 均不得输出
   route ID、旧值、Secret、URL、package 或 preference contents。

未选择的方案：

- 写入第二个 SharedPreferences 文件：旧保存与新 projection 无法用一次 commit 原子更新，会在 CFG-011 前产生
  可观察半配置。
- 删除或重命名旧键：失败后无可靠回滚来源，且未映射字段会永久丢失。
- 把 API Key 直接复制进 ProviderConfig：违反 SecretRef 边界并扩大明文/备份表面。
- 把 endpoint、model 或 DashScope `wss` 强塞进现有 ProviderConfig：当前 Endpoint 契约只接受 HTTP(S)，会
  静默改变协议或丢字段；这些值必须继续由旧备份保留，等待对应 schema 与 SecretStore。
- 把迁移延后到新 UI 保存时：无法证明真实 0.2 安装升级，也会让未打开设置页的 IME 永远没有 projection。

## Consequences

升级后会有一个可重复验证、不会覆盖旧数据的 GlobalConfig format-1 shadow，且旧设置每次保存都保持 projection
同步。代价是迁移期仍保留一份旧 AppSettings authority；新 Resolver/route registry 不能仅因 projection 存在就
开始生产执行。未映射的 Provider、语言、个性化和 Secret 继续占用旧存储，直到后续任务完成并明确删除条件。

改变 route ID 映射、keyboard 默认、override 语义、target key、备份保留策略或 source version 都必须提高
migration/config format 并用新 ADR 替代；不得原地重解释已经写入的 version 1。

## Validation

接受前已逐项核对 Android 0.2 commit `c8d8161` 的 `SettingsRepository`/`AppSettings` 实际 key 与默认值、当前
recovery journal、ADR-0001/0003/0004/0005、Backlog CFG-006 及架构 §16.2。实现必须由以下证据收口：

```bash
python3 scripts/verify_adrs.py
python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v
cd android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest --tests 'com.opentypeless.android.settings.LegacyAppSettingsMigrationTest' \
  :architecture-gate:check
```

测试必须覆盖 actual 0.2 SharedPreferences fixture、clean defaults、全部 backend/mode 映射、`false` 三态、重复
执行零写、revision 更新、commit failure、部分/未知/错误类型 target、旧 key 与 Secret sentinel 不复制、journal
rollback，以及真实 Android SharedPreferences 读写。完整 strict build、Debug/Release compiled gate 与小米
10 Ultra 设备结果必须分别记录；assemble 不得冒充 instrumentation。

2026-08-14 最终证据：迁移 JVM 8/8、app JVM 743/743、source 90/90、compiled 84/84、Debug/Release 2/2、
ADR 6 decisions 与生命周期单测 4/4 均 PASS；全新 `GRADLE_USER_HOME` 的 strict clean 验证 187 tasks PASS。
API36 模拟器与小米 10 Ultra（Android 13/API33）上的 actual SharedPreferences instrumentation 均 1/1 PASS。
小米通过系统可见的 Shell 来源授权页安装与相同 SHA-256 的 AndroidTest APK，未关闭 package verification、
未绕过系统策略；定向 runner 返回 `OK (1 test)`。

## Rollback

在 CFG-007/008/011 或任何 runtime consumer 接线前，可以停止读取 `config_v1_` projection 并删除 CFG-006
代码；旧 0.2 keys 从未删除，现有 runtime 可直接继续使用。已经写入的 projection 可以留作 inert shadow，或由
后续显式 migration version 清理；旧二进制会忽略未知前缀。不得在回滚时清除旧 Secret 或未映射字段。

## References

- Task：`CFG-006`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- 测试矩阵：`docs/opentypeless_specs/08_TEST_VALIDATION.md`
- 关联 ADR：[ADR-0001](0001-provider-config-secret-boundary.md)、
  [ADR-0003](0003-override-value-three-state-format.md)、
  [ADR-0004](0004-versioned-configuration-partitions.md)、
  [ADR-0005](0005-effective-profile-resolution.md)
