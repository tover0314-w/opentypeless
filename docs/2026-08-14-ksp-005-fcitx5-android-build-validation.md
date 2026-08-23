# Task Report: KSP-005

## Result

DONE — 固定 fcitx5-android `0.1.3` 源码与全部 recursive submodule，在仓库外隔离环境完成
`arm64-v8a` / `x86_64` 主 APK和官方 Rime plugin 的 clean build；两套 ABI 均完成实际安装、安装后 APK
回读哈希、ABI/版本、插件发现与主界面启动验证。

本任务只关闭路线 B 的“可构建、可安装”基线。没有把第三方源码、APK、native runtime 或依赖引入
OpenTypeless 产品树，没有接 Voice/Undo/EditorTransaction，也没有选择最终键盘底座。

## Scope

- Implemented:
  - 固定 upstream tag `0.1.3`、tag object `c1f05310df5f7e4ede4869cd8f64540129526b6c` 与 source commit
    `048f581c652367567b8ee5c28c5163b805288895`；GitHub tag verification 为 `verified=true`；
  - 固定 source archive `fcitx5-android-0.1.3.tar.gz`：`837,196` bytes，SHA-256
    `f92fedba749d64f2bd567f3ca75b4909292aa461342413006cb1cc73945ae734`；
  - 递归初始化并核对 22 个 gitlink，包括 fcitx5、libime、fcitx5-rime、Rime schema data 与各语言插件；
  - 对主程序与官方 Rime plugin 执行双 ABI clean build、APK 签名/权限/ABI 检查、安装后原包回读；
  - 在 API35 arm64 emulator 与 API26 x86_64 guest 启动主界面，并验证 Rime plugin manifest 可被查询。
- Not implemented:
  - 未实现 KSP-006 的 QWERTY/Rime/Voice/Undo 垂直切片；
  - 未执行 KSP-007 的逐文件许可证/NOTICE 接受结论、KSP-008 性能矩阵或 KSP-009 功能矩阵；
  - 未切换设备默认 IME，未把候选接入 OpenTypeless runtime，未修改产品权限、数据格式或 Feature Flag；
  - 小米 10 Ultra 上只完成主 APK 安装/启动；Rime plugin 的首次 USB 安装被 HyperOS 用户确认界面阻止，
    因而记为 `NOT RUN`，不冒充真机 plugin PASS。双 ABI 安装验收由两个 emulator 实际完成。

## Upstream and recursive inputs

上游 `LICENSE` 为 LGPL-2.1-or-later，`25,906` bytes，SHA-256
`1ccf09bf2f598308df4bed9cd8e9657dc5cd0973d2800318f2e241486e2edf3f`。这只是 upstream 声明记录，
不替代 KSP-007 的分发许可结论。

固定 recursive gitlink：

| Input | Commit |
|---|---|
| fcitx5-chinese-addons | `9614900b591d180315144b14c529be756fa0e4da` |
| fcitx5-lua | `05db9ee519d448a64ccbe216044e8e0342e8c536` |
| fcitx5 / yoga / prebuilt | `16465b04f675105da9fde14e7087984bdc07a146` / `042f5013152eb81c1552dec945b88f7b95ca350f` / `86ce2c95d42f1132746fbf60c278193aa1f4b758` |
| libime / kenlm | `7b638a433815ed7a29d9bcb8d59aed7366bd3b28` / `4cb443e60b7bf2c0ddf3c745378f76cb59e254e5` |
| anthy-cmake / anthy-unicode / fcitx5-anthy | `627b94e60320b6ef3ca5cc404c22c84649b76f73` / `44a16491df37a0f067e7a431ad1acd3ab4e9cda8` / `84bf0376cdb924a89c41bc7fadeb49d78bad385c` |
| chewing / ClearURLsRules / hangul | `07eddb16961b18765e67cec538708b6964baa57c` / `11086f40512774dcadef54079f1ba023bfacf940` / `9357892335b7f4a38a885f7c09113295f23c5d4a` |
| libime-jyutping / fcitx5-rime | `aeb0d010e0b945d894f60b48a952a59820d85f46` / `4e996319edea790495edc2c91893e9af4c4e6d6a` |
| rime essay / luna-pinyin / prelude / stroke | `e9b1a374a6ea015fca5bdd04318924b4483ac35a` / `56b934b099dfbeab842320f13aa8b461a6ab3e42` / `082425ea0684bca36474415d4a0e8db9b016487e` / `3a4b0f4013e2b4c14b1e80c92b1d4723eb65f39c` |
| sayura / thai / unikey | `43ea084170dfe0496fcd9f2dbfc712cc7d7eef64` / `8bfa27d7ae675fda3257f21e691cd5c285663e6f` / `366b858db1c0f464102857021d7af9b75e14ebd8` |

## Isolated build contract

所有候选源码、SDK、Gradle cache、AVD 与 APK 均位于
`/private/tmp/opentypeless-ksp005.4LlBJ1`。最终生产 APK 由固定 upstream source、固定 recursive gitlink 和
upstream 自带 `-PbuildABI=arm64-v8a,x86_64` 开关直接构建；没有修改生产源码。

固定工具链：

| Component | Fixed value |
|---|---|
| Java / source level | OpenJDK 17 / Java 11 |
| Gradle / AGP | `9.6.1` / `9.3.1` |
| Kotlin | `2.4.10` |
| compile / target / min SDK | `36` / `36` / `23` |
| Build Tools | `36.1.0` |
| NDK / CMake | `28.0.13004108` / `3.31.6` |
| External build tools | ECM `6.28.0`、gettext `1.0` |

最终命令：

```bash
env \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ANDROID_HOME="$KSP005_ROOT/android-sdk" \
  ANDROID_SDK_ROOT="$KSP005_ROOT/android-sdk" \
  GRADLE_USER_HOME="$KSP005_ROOT/gradle-home" \
  PATH="/opt/homebrew/opt/gettext/bin:$PATH" \
  ./gradlew --no-daemon --console=plain \
  -PbuildABI=arm64-v8a,x86_64 \
  clean :app:assembleDebug :plugin:rime:assembleDebug
```

结果：`BUILD SUCCESSFUL in 3m`，343 tasks（309 executed、34 up-to-date）。

## Artifact evidence

| APK | Bytes | SHA-256 | ABI |
|---|---:|---|---|
| main arm64 | 61,012,843 | `b00cae369ea6b59d6cc9c75e894f6e907300711445974a0af1662589834a7dc8` | only `arm64-v8a` |
| Rime arm64 | 8,942,659 | `61cdb3f195027b37fd1af7f89f5d6de048cc43d6d4acf3d9c81e1d3c909ba76e` | only `arm64-v8a` |
| main x86_64 | 61,060,178 | `05377d99d417d975de57d897809bda49cc10e59957d1a2c16f68bfac49f57c48` | only `x86_64` |
| Rime x86_64 | 9,010,341 | `340001db5dbe0ff479db5fdb28b20e306a10aaf78300408bb4ec07460ecd220d` | only `x86_64` |

- main application ID `org.fcitx.fcitx5.android.debug`；Rime plugin ID
  `org.fcitx.fcitx5.android.plugin.rime.debug`；
- arm64 version code `112`，x86_64 version code `114`；version name 均为 `0.1.3-0-g048f581`；
- 四包均为 APK Signature Scheme v2、同一 debug signer，certificate SHA-256
  `ec62416501e3da3a45d59f4167b14933897c4cdeeeeecb01d6e725fb7de5612e`；
- main 声明 `VIBRATE`、`POST_NOTIFICATIONS` 与 package-local signature IPC/plugin permissions，无
  `INTERNET`；Rime plugin 声明 `REQUEST_DELETE_PACKAGES`。该 plugin 权限与 exported `AboutActivity` 必须在
  KSP-007/KSP-009 继续审计，当前不视为已接受产品权限。

## Tests actually run

| Command / device | Result | Notes |
|---|---|---|
| clean 双 ABI main + Rime build | PASS | 343 tasks；四个 APK 均只含目标 ABI |
| unmodified upstream `:app:testDebugUnitTest :plugin:rime:testDebugUnitTest` | FAIL | 5 tests 中 1 个过期断言：fixture version 2.0 相对 current 2.1 应迁移，但测试仍期望 `false`；plugin `NO-SOURCE` |
| 临时测试-only 一行修正后同一 unit command | PASS | 5/5；只把 `Migration shouldn't happen=false` 改为 `Migration should happen=true`，生产源码/APK 未变 |
| API35 arm64 emulator 安装 main + Rime | PASS | 两包安装后 pull 回的 SHA-256 与构建产物逐字节一致；`primaryCpuAbi=arm64-v8a` |
| API35 arm64 main cold launch | PASS | `MainActivity` resumed；定向 logcat 无 package fatal |
| API26 x86_64 guest 安装 main + Rime | PASS | 两包安装后 pull 回的 SHA-256 与构建产物一致；`primaryCpuAbi=x86_64` |
| API26 x86_64 main launch | PASS | `MainActivity` resumed、进程存活；recent package log 无 fatal |
| 两设备 plugin manifest query | PASS | 均发现 `org.fcitx.fcitx5.android.lib.plugin_base.AboutActivity` |
| 小米 10 Ultra main APK | PASS | `primaryCpuAbi=arm64-v8a`、version/哈希一致，Setup/Main 界面可启动且无 fatal |
| 小米 10 Ultra Rime plugin | NOT RUN | HyperOS 首次 USB 安装需要前台用户确认；本任务未绕过安全确认，也未把失败写成 PASS |
| OpenTypeless current HEAD GitHub Actions | NOT RUN | current HEAD 没有对应 CI run；候选构建位于仓库外 |

x86_64 guest 使用官方 Android API26 default x86_64 image revision 1 和 Google Intel macOS Emulator
`37.1.11` / build `15917651`，经 Rosetta + TCG 完成 cold boot。API35 x86_64 image 在 TCG 下曾触发
`system_server` watchdog，故改用 minSdk 覆盖范围内的 API26 官方 image；这不是候选 APK 崩溃，也不进入
KSP-008 性能评分。

## Architecture

- contracts: 本任务只运行 upstream main/plugin，不复制其中 editor writer；OpenTypeless 的所有编辑仍保持在现有
  `EditorTransactionManager` 边界，候选没有进入产品调用图；
- state changes: 只有隔离 emulator 与小米测试 package 安装状态；没有 OpenTypeless 数据、配置或 schema 迁移；
- migration: 无；
- feature flag: 无生产 Feature Flag。KSP-006 仍须在隔离切片中证明 Voice/Undo 不绕过 transaction；KSP-010
  接受 ADR 后才能进入正式接线。

## Security & privacy

- 没有采集、上传、提交或写入真实用户正文、音频、词典、密钥或 token；
- 小米默认 IME 全程保持 `com.flypy.input/PangIME.Android.InputService`，package verifier 保持启用；
- 小米保持“可自动熄屏、无凭据锁屏”：自动熄屏 10 分钟、充电常亮关闭；本任务不关闭系统安全校验；
- 测试包使用 upstream debug signer，不是发布候选；
- upstream Gradle wrapper 没有 `distributionSha256Sum`，仓库也没有 Gradle dependency verification metadata。
  本任务没有关闭或修改 OpenTypeless 的 strict verification；若路线 B 被选中，KSP-007/KSP-011 必须建立可审计的
  wrapper/dependency 校验与 patch provenance 后才可进入产品树。

## Risks

- upstream Theme serialization 测试断言已落后于 current version；临时测试修正未提交到产品树，也不等同于上游已修；
- Rime plugin 的 `REQUEST_DELETE_PACKAGES`、exported AboutActivity、LGPL/plugin linking 与 bundled schema/data 必须由
  KSP-007 逐项裁决；
- 本任务只证明构建/安装/发现/启动，不证明 QWERTY、候选、Voice、Undo、TalkBack、横屏、主题、剪贴板或性能；
- x86_64 使用软件模拟，只作为安装兼容证据；所有延迟数据不得进入 KSP-008 评分；
- 小米 Rime plugin 没有完成首次安装，因此没有真机 plugin runtime 证据；双 ABI DoD 已由两个相应 ABI emulator
  实际安装关闭，KSP-009 仍需同设备功能证据。

## Rollback

卸载 `org.fcitx.fcitx5.android.debug` 与 `org.fcitx.fcitx5.android.plugin.rime.debug`，关闭临时 emulator，并删除
仓库外 `/private/tmp/opentypeless-ksp005.*` 即可。OpenTypeless 产品代码、默认 IME 与用户数据无需回滚。

## Follow-ups

- KSP-006
- KSP-007
- KSP-008
- KSP-009
- KSP-010

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: 共享工作树已有大量其他任务的 tracked/untracked 变更；KSP-005 只新增/更新证据文档，不
  stage、commit 或 push；第三方源码、SDK、Gradle cache、AVD 与 APK 均留在仓库外隔离目录。
