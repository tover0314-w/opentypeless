# ADR-0008: SecretRef Store and legacy credential shadow

## Status

Accepted

## Background

CFG-001 已把 ASR、LLM 与 Connector 配置中的认证材料降为带 Kind 的 opaque `SecretRef`，但 Android 0.2
`AppSettings` 运行路径仍通过固定 `stt_api_key`、`streaming_api_key` 与 `llm_api_key` 名称取得明文 String。
这些值实际已由 `SecurePreferences` 使用 Android Keystore AES-GCM 加密，且应用禁止 backup/device transfer；
缺口不是“磁盘上完全未加密”，而是没有可轮换的 identity、Provider 配置仍无法只持 ref，也没有严格的
Keystore 失效、Bundle/导出与旧数据迁移边界。

CFG-011 尚未完成最终 ConfigStore/SecretStore authority 切换。若 CFG-008 直接删除固定旧槽或让普通
`SecretStore.rotate()` 改写 legacy runtime，会使现有 IME、设置事务 journal 与 rollback 失配；若只新增未接线
抽象，又无法证明 0.2 实际 ciphertext 能安全进入 ref 模型。因此需要一个可独立验证、可回滚的 encrypted
shadow，同时明确 legacy-bound ref 仍不能由新 API 自行轮换或删除。

## Decision

1. 新增 final `SecretStore`，沿用现有 `opentypeless_secrets` SharedPreferences 与不可导出的 Android Keystore
   AES-GCM key。format/migration version 固定为 1；最多 64 个 entry，单个明文最多 4,096 code points，存储值
   最多 32,768 UTF-16 units。应用继续 `allowBackup=false`，cloud backup/device transfer 均排除全部 shared prefs。
2. 每个 entry 由 `SecretRef(kind, opaqueId)` 定位；store 同时保存 exact Kind 与 ciphertext，不能从 ID 或 entry
   名推断 Secret。新 ID 使用 `sec_` + 32 位随机 lower-hex，碰撞有界重试；未知/partial/corrupt version、Kind、
   entry/binding 对、重复 binding、超限、ID/Keystore/commit/readback failure 都返回稳定无 payload 分类。
3. `create(kind, char[])`、`rotate(exactRef, char[])` 与 `delete(exactRef)` 只操作 unbound entry。rotate 一次同步
   commit 写入 fresh ref 并退休旧 ref；delete 同步退休 exact ref。plaintext 只在一次同步 `use(ref, callback)`
   中以临时 char[] 出现，回调结束必清零；store 不提供 `getString()`、latest、Bundle、Intent、Serializable、
   Parcelable、export ciphertext 或枚举 plaintext API。
4. Android 0.2 三个固定槽通过闭集 `LegacySlot` 进入迁移 bridge：初次迁移不解密，只在同一 encrypted store 的
   一次同步 commit 中复制现有 ciphertext、生成 opaque binding 并保留旧槽。重复执行完整校验且零写；旧槽更新
   时沿用 exact binding 刷新 shadow ciphertext，旧槽清除时同步退休 binding/entry。
5. legacy-bound ref 禁止普通 rotate/delete，固定返回 `LEGACY_AUTHORITY`。只有 `SettingsRepository` 的现有
   save/recovery transaction 可调用 exact ciphertext bridge，使 legacy source 与 ref projection 在同一 Secret
   store commit 中一致；CFG-011 前新 Store 不越权改变 runtime credential。
6. `SettingsRepository.load()` 在取得旧 runtime String 前先验证/刷新 migration；
   `loadMigratedSecretRefs()` 只返回 immutable `LegacyRefs` shadow。当前 `AppSettings`、Provider client 与 UI
   仍以 legacy source 为运行 authority；CFG-008 不接 Recognition/Connector、不把 SecretRef 解析结果放入
   Bundle/SavedState/日志/诊断/导出，也不删除旧 source。
7. decrypt/authentication/Keystore 失败不能自动删除 ciphertext，也不能输出 cause、alias、opaque ID 或 Secret。
   `SecretStoreException` 只包含闭集 Failure。调用方回调异常统一为 `USE_FAILED` 且不保留 cause；所有内部
   `toString()` 与测试诊断均脱敏。

未选择的方案：

- 把明文 Key 写进 ProviderConfig 或 ConfigStore：违反 CFG-001 分域并扩大 backup、Bundle 与导出表面；
- 用 package、Provider ID 或 Secret hash 生成 ref：会泄露关联性并造成可预测/ABA identity；
- 初次迁移先解密再加密：无必要地扩大 plaintext 生命周期，且会在 Keystore 临时不可用时破坏可回滚迁移；
- 让普通 rotate/delete 改写 legacy-bound entry：绕过 SettingsRepository cache/revision/journal，属于 CFG-011；
- Keystore 失败时异步删除 entry：会把暂时不可用变成不可恢复的数据丢失，也破坏失败分类与 rollback。

## Consequences

新 Provider/Connector 配置可以只持 opaque identity，创建/轮换/删除与 use 的明文生命周期有独立测试；现有
0.2 ciphertext 可以在不解密、不删除 source 的情况下形成幂等 ref shadow。代价是 CFG-011 前同一 Secret 在
legacy fixed slot 与 ref entry 中各有一份 AES-GCM ciphertext，且旧 `AppSettings` 仍会在进程内短暂持有 String。
这是明确的过渡边界，不得被描述为 runtime 已完全迁移。

改变 entry/ref format、上限、legacy slot 映射、绑定轮换权限、backup 策略或运行 authority 必须提高版本并以
新 ADR 替代，不能原地放宽。

## Validation

持续验证命令：

```bash
python3 scripts/verify_adrs.py
python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v
cd android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest --tests 'com.opentypeless.android.security.SecretStoreTest' \
  :architecture-gate:check assembleDebugAndroidTest
```

验收必须覆盖 create/use/rotate/delete、callback 后清零、legacy actual ciphertext 迁移与零写幂等、source 刷新/
清除、legacy-bound rotate/delete 拒绝、Kind/ID/collision/64-entry/4,096-code-point 边界、unknown/partial/corrupt
target、commit/readback/Keystore/callback failure、异常/toString/Bundle/serialization/export 脱敏、
SettingsRepository save/recovery exact caller，以及 Android Keystore 真实加解密和生产 preferences finally 恢复。
完整 strict build、Debug/Release gate、模拟器和小米 10 Ultra 结果分别记录。

2026-08-14 实际验收：`SecretStoreTest` 8/8、app JVM 760/760、source architecture 92/92、compiled gate
88/88、Debug/Release variants 2/2 均 PASS；标准 clean strict `scripts/verify_android.sh` 45s、187 tasks
（184 executed / 3 up-to-date），全新 `GRADLE_USER_HOME` 重跑 2m22s、187 tasks（183 executed / 4
up-to-date），均 `BUILD SUCCESSFUL`。API36 `medium_phone` 与小米 10 Ultra Android 13/API33 均显式安装
最终 APK 并定向执行真实 Keystore/SharedPreferences `SecretStoreInstrumentedTest` 2/2 PASS（分别 1.036s、
0.298s）。测试 finally 恢复 production preferences，隔离 alias 被删除；未关闭 package verification、未切换
默认 IME。最终 APK SHA-256：debug
`f7f7451e5bbbf8bd7e05d727a053f8b0a72cf93d715fe882ca2d396ed7f9a055`，androidTest
`c2215014e36f60e6748f4ac06697544b4765467ab25009ceeda1edfa6c90a36a`，unsigned release
`41203976ac821fe74b2df17cd881bca982663f66ed2024acf114e61d0ff2ac43`。

## Rollback

CFG-011 接线前可停止调用 `loadMigratedSecretRefs()`，删除 `cfg008_` prefixed entry/kind/binding/marker 和
`SecretStore` 代码；旧 `stt_api_key`、`streaming_api_key`、`llm_api_key` ciphertext 与当前 runtime 从未在迁移
时删除。回滚不得清除旧槽或把 shadow ciphertext 导出/反写为明文。用户显式 save/clear 造成的旧槽变化仍按
现有 Settings transaction 语义处理，不属于迁移数据丢失。

## References

- Task：`CFG-008`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- 安全规范：`docs/opentypeless_specs/06_SECURITY_PRIVACY.md`
- 测试矩阵：`docs/opentypeless_specs/08_TEST_VALIDATION.md`
- 关联 ADR：[ADR-0001](0001-provider-config-secret-boundary.md)、
  [ADR-0006](0006-legacy-app-settings-global-config-migration.md)
