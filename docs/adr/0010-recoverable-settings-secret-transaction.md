# ADR-0010: Recoverable settings and Secret transaction

## Status

Accepted

## Background

Android 0.2 的一次设置保存会同时改变普通 `opentypeless_settings`、Keystore-backed
`opentypeless_secrets`，以及 CFG-006/CFG-008 的 projection/binding。`SharedPreferences.Editor.commit()`
只能保证单个 preference 文件的一次同步提交；普通配置和 Secret store 之间不存在平台提供的跨 store 原子提交。
旧实现虽有 recovery journal，却没有在清除 journal 前精确验证 committed/restored 状态，也没有记录 retired
legacy-bound `SecretRef` 的原 opaque identity。进程在两次 store 写入之间终止，或 rollback 为旧 ciphertext 分配新
identity，都会留下半配置或使仍持有旧 ref 的配置失效。

CFG-011 只解决当前 production `SettingsRepository.save()` 的配置、legacy credential ciphertext 与 shadow ref
binding。它不把所有 Provider consumer 改为 `SecretRef`，不删除 legacy source，也不把独立
`AppProfileRepository` 与设置保存伪装成一个平台级事务。

## Decision

1. package-private final `SettingsSaveTransaction` 是唯一跨 store 协调器，固定执行 journal → Secret → settings →
   exact committed readback → journal clear。journal 在任一 value store 改变前以同步 `commit()` 落盘并立即精确
   readback；`apply()` 不得进入该协议。
2. journal 保存 bounded non-secret settings、旧 revision、三个 legacy ciphertext 和三个旧 opaque ref ID。它不保存
   Secret 明文。journal key 集、类型与 pending marker 必须精确；未知、缺失、额外、畸形或越界状态 fail closed。
3. Secret 恢复必须同时恢复旧 ciphertext 与 exact legacy-bound ref identity，并删除本次失败 save 产生的临时
   binding/entry。若旧 ID 已被不一致状态占用，恢复失败且保留 journal；不得分配“等价的新 ID”冒充恢复。
4. settings 写入继续在原 `opentypeless_settings` 的一次同步 commit 中更新 legacy source、revision 与 CFG-006
   projection。`LegacyAppSettingsMigration.readValidated()` 只做无写 readback，避免验证阶段偷偷修复 target。
5. committed verification 必须同时精确比较 settings、revision、完整 projection、legacy ciphertext 与 refs；只有全部
   一致才可清 journal。rollback/restart recovery 先恢复两 store，再做同一精确验证，最后同步清 journal并验证为空。
   恢复或清理失败时保留 journal，下一 `SettingsRepository` 实例可幂等重放。
6. failure 使用无 payload 闭集 `TransactionFailure`；异常、suppressed failure、`RecoveryState.toString()`、门禁和测试
   诊断不得输出 Secret、ciphertext、opaque ID、URL、custom instruction 或 preference contents。
7. CFG-011 把当前 production settings save 定义为“可恢复、经 readback 验证的跨 store 事务”，不宣称 Android
   提供多文件原子提交。legacy `AppSettings` String 与旧 profile source 继续保留，直到对应 consumer/UI 迁移任务
   具备同等 rollback 和设备证据。

未选择的方案：

- 使用 `apply()` 并事后检查内存值：它不报告磁盘失败，也不能建立 durable journal 顺序；
- 先写新值再创建 journal：进程终止会留下没有恢复依据的半配置；
- rollback 只恢复 ciphertext、重新生成 ref：会破坏 opaque identity 与持有旧 ref 的配置；
- 把 Secret 明文复制到 journal：扩大明文磁盘表面且没有必要；
- 删除 legacy source 或一次切换全部 Provider：超出 CFG-011，且缺少 consumer rollback/feature-flag 证据；
- 把两个 SharedPreferences 文件称为原子事务：平台没有提供这种保证。

## Consequences

设置保存失败、readback 不一致和进程重启都能回到同一个旧 settings/revision/ciphertext/ref identity；journal 只有在
已验证 committed 或 restored 状态后消失。代价是正常保存需要多次同步 commit/readback，journal 会短暂保存普通
配置和 encrypted ciphertext，且 repository load 必须先执行 recovery。该协议只适合低频设置保存，不进入 IME
热路径。

`AppProfileRepository` 仍在自身 SharedPreferences 文件内独立提交；Provider/Recognition 仍可能消费 legacy
runtime String。后续 authority 切换必须复用本 ADR 的 fail-closed、旧 source 保留和设备恢复证据，不能把 CFG-011
的完成解释为所有配置 consumer 已迁移。

## Validation

- `./scripts/verify_android.sh`：**BUILD SUCCESSFUL**，47s，187 tasks（184 executed / 3 up-to-date）；source
  architecture **95/95 PASS**、compiled gate **94/94 PASS**、Debug/Release production variants **2/2 PASS**、app
  JVM **777/777 PASS**，并完成 `lintRelease`、Debug/Release 与 AndroidTest assemble；
- 全新临时 `GRADLE_USER_HOME`、strict dependency verification 再跑同一脚本：**BUILD SUCCESSFUL**，2m49s，
  187 tasks（183 executed / 4 up-to-date）；
- JVM 覆盖 journal/write/verify/clear 顺序、save/readback failure、rollback verification failure、surviving journal、
  幂等 recovery、只读 migration validation，以及 exact retired-ref identity restore；source/compiled 恶意夹具拒绝
  open transaction、错误 caller、未脱敏 recovery state、Secret bridge 漂移与缺失 production binary；
- API36 `medium_phone` emulator 显式安装最终 app/androidTest APK，定向
  `SecretStoreInstrumentedTest#pendingJournalRestoresExactSettingsCiphertextAndRetiredRefIdentity`：**1/1 PASS**
  （0.335s）；
- 小米 10 Ultra `be4e2015`（M2007J1SC、Android 13/API33、HyperOS OS1.0.4.0.TJJCNXM）同一最终两包无人值守
  `--no-streaming` 覆盖安装成功，同一定向 case **1/1 PASS**（0.086s）。设备保持 10 分钟自动熄屏、最大自动锁
  延迟、充电不常亮；未切换默认 IME、未关闭 package verification。

## Rollback

回滚代码前必须保留并先运行当前版本 recovery，确保没有 pending journal；随后可以恢复旧协调器，但不得删除 legacy
settings、旧 fixed ciphertext 槽或 CFG-006/CFG-008 projection。若设备上仍有 pending journal，旧版本不认识新增的
exact ref-ID keys，必须停留在新版本完成恢复，不能清文件或强行降级。

## References

- Task：`CFG-011`
- 前置：[ADR-0006](0006-legacy-app-settings-global-config-migration.md)、
  [ADR-0007](0007-legacy-app-profile-three-state-rule-migration.md)、
  [ADR-0008](0008-secret-ref-store-and-legacy-credential-shadow.md)
- Android `SharedPreferences.Editor`：<https://developer.android.com/reference/android/content/SharedPreferences.Editor>
