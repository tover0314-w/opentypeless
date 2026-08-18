# ADR-0009: Launchable App Picker without broad package visibility

## Status

Accepted

## Background

CFG-009 要让普通用户从可识别的应用名称和图标中选择 AppRule 目标，并把手填包名降为显式高级入口。现有
`AppProfileActivity` 只有包名文本框；直接改用 `PackageManager.getInstalledApplications()`、
`getInstalledPackages()`、`queryIntentActivities()` 或声明 `QUERY_ALL_PACKAGES` 虽能扩大列表，却会收集与规则编辑
无关的安装清单，增加隐私、审核和 OEM 差异风险。另一方面，只依赖 manifest `<queries>` 无法形成通用 Picker，
而某些没有当前 profile 启动入口的包仍需为企业、工作资料或高级调试规则提供精确包名入口。

Android `LauncherApps.getActivityList(null, Process.myUserHandle())` 提供当前用户 profile 中可启动 Activity 的
受限目录，符合 Picker 的实际交互目标。该目录仍属于应用清单敏感数据，不能进入配置、日志、诊断、网络、
SavedState 或导出；应用规则最终只保存用户明确选中的包名。

## Decision

1. App Picker 的系统目录 authority 只存在于 package-private final `InstalledAppCatalog`。它只调用一次
   `LauncherApps.getActivityList(null, Process.myUserHandle())`，不声明 `QUERY_ALL_PACKAGES`，不调用 broad
   `PackageManager` 枚举 API，也不新增 exported component 或权限。
2. 目录最多接受 4,096 个 launcher activity，并规范化为最多 2,048 个 distinct package entry。应用 label、
   package 和搜索 query 都检查 UTF-16、控制字符和 code-point 上限；畸形第三方条目被跳过，目录整体失败只返回
   无 payload 的稳定错误。列表按 label/package 稳定排序并按 package 去重。
3. `AppPickerModel` 是 Android-free、immutable、non-serializable 的 bounded value model；label/package 搜索均
   大小写不敏感。`InstalledAppCatalog.Snapshot` 只在本次进程内短暂持有 package 到
   `LauncherActivityInfo` 的映射，图标通过有界 32-entry UI cache 懒加载。model、snapshot、dialog 与异常的
   `toString()` 不输出应用清单。
4. `AppPickerDialog` 是 `AppProfileActivity` 唯一目录消费者。普通路径显示图标、label 和 package，并支持搜索；
   用户选中后只把 exact package 写回既有 profile draft。手填包名默认隐藏，只能通过“高级：输入包名”显式打开；
   因当前 profile 无启动入口而未出现在目录中的合法包仍可通过此入口配置。
5. 本任务不改变 `AppProfileRepository`、`AppRule` 或最终 ConfigStore authority，不启动目标应用，不引入网络，
   不把目录保存到 Bundle/SavedState/SharedPreferences/数据库。最终规则 transaction 与解释器分别留给
   `CFG-011` 和 `CFG-010`。

未选择的方案：

- `QUERY_ALL_PACKAGES` 或 broad PackageManager inventory：采集范围超过 Picker 目的，且增加审核与隐私负担；
- 在 manifest 中预列固定 `<queries>`：无法覆盖用户实际要配置的任意应用；
- 只保留包名输入框：违背普通用户不应先知道技术包名的产品要求；
- 把目录缓存到持久层或诊断：会形成不必要的安装清单副本；
- 点击 Picker 条目后启动目标应用：扩大 capability，与编辑 AppRule 无关。

## Consequences

普通用户可以通过图标、名称和搜索选择当前 profile 的可启动应用，应用无需 broad package visibility。代价是
没有可见 launcher activity、被 profile/OEM 隐藏或暂时不可查询的包不会出现在普通列表；这些情况只能使用显式
高级包名入口。Picker 目录是进程内短生命周期 UI 数据，不能被解释为完整安装应用清单，也不能成为规则匹配或
安全授权来源。

将来若需要跨 profile、企业管理或非启动包发现，必须以新 ADR 说明最小可见范围、Android policy、用户告知和
数据生命周期，不能原地加入 broad permission/API。

## Validation

持续验证命令：

```bash
python3 -m unittest discover -s android/architecture-tests -p 'test_*.py' -v
python3 android/architecture-tests/architecture_contracts.py --android-root android
cd android
./gradlew --dependency-verification=strict \
  :app:testDebugUnitTest \
  :architecture-gate:test \
  :architecture-gate:verifyCompiledArchitecture \
  :app:lintRelease :app:assembleDebug :app:assembleDebugAndroidTest
```

验收必须覆盖 immutable/sorted/deduplicated model、label/package 搜索、package/Unicode/query/count 上限、redacted
diagnostics、无 broad permission/API、current-user LauncherApps exact authority、图标 fallback、默认隐藏高级入口、
选择/搜索/rotation、目录/模型不外传，以及 Debug/Release 缺失 binary、open shape、wrong caller 和额外 inventory
edge 的恶意 fixture。

2026-08-14 实际验收：`AppPickerModelTest` 6/6、app JVM 766/766、source architecture 93/93、compiled gate
90/90、Debug/Release production variants 2/2 均 PASS。标准 clean strict `scripts/verify_android.sh` 47s、187
tasks（184 executed / 3 up-to-date），全新 `GRADLE_USER_HOME` 重跑 2m33s、187 tasks（183 executed / 4
up-to-date），均 `BUILD SUCCESSFUL`。API36 模拟器显式安装最终 debug/androidTest APK 后，
`AppPickerInstrumentedTest` 2/2 PASS（6.207s），覆盖真实 LauncherApps、图标、无 broad permission，以及搜索、
选择、高级入口和重建。小米 10 Ultra Android 13/API33 的 catalog/icon/permission 定向测试 1/1 PASS（0.097s）；
同机 UI case 因 HyperOS 拒绝测试 runner 启动非 exported Activity 而 NOT RUN，未把安装/编译冒充 UI PASS。测试后
模拟器已关闭，小米恢复 Dozing；未切换默认 IME、未关闭 package verification。最终 APK SHA-256：debug
`231a6b97307efee4894922e46507194ef04e0dee8327471989ee31b60482f3d9`，androidTest
`2c6478f78423fb4770f52566c595bdcf533fb10a2880ac9d571b5e14a697f2ad`，unsigned release
`47ac325fa45c17b3dee65295a1f354817bb517c6cfd6b56f92e17b1c2f1703a1`。

## Rollback

删除 `AppPickerDialog`、`InstalledAppCatalog`、`AppPickerModel` 及 Activity 的 Picker 接线，恢复原有包名输入框即可；
本任务没有迁移持久数据、改变 rule format 或新增权限/组件。已保存的 package 仍是既有合法 AppProfile key，回滚
不会丢失或重写规则。

## References

- Task：`CFG-009`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- 安全规范：`docs/opentypeless_specs/06_SECURITY_PRIVACY.md`
- UX 原型：`docs/opentypeless_specs/03_UX_DESIGN_PROTOTYPES.md`
- Android API：<https://developer.android.com/reference/android/content/pm/LauncherApps>
- Android package visibility：<https://developer.android.com/training/package-visibility/declaring>
- 关联 ADR：[ADR-0004](0004-versioned-configuration-partitions.md)
