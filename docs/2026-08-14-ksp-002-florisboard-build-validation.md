# KSP-002 FlorisBoard 最小构建与安装验收

## Result

**DONE** — 固定 FlorisBoard upstream commit 的最小 Debug APK 已在隔离目录中完成严格依赖校验、双 ABI clean build，并分别在 arm64-v8a 真机与 x86_64 模拟设备上完成首次安装和同包覆盖安装。

本任务只验证候选路线 A 的可构建/可安装基线；没有把 FlorisBoard 源码、二进制或运行路径接入 OpenTypeless，也没有选择最终键盘底座。

## Scope

- Task ID：`KSP-002`
- OpenTypeless branch：`agent/android-offline-followup`
- OpenTypeless HEAD：`80d20496c4eb59e4f27281becfa8a32021212e53`
- upstream：[FlorisBoard](https://github.com/florisboard/florisboard) `v0.5.2` / `2e82060251897226c0739b9f52d1d051b02305fb`
- upstream source archive SHA-256：`ba279c66ad4800b8b6242758e734a2f5852d6c1775cb54a538a497b595215594`
- upstream `LICENSE`：Apache-2.0；SHA-256 `b7eb0e4356678f0fa7fb53a35c864ec0b3dca6c6602a26c3cb972c13e2041fcf`
- Git submodules：无
- transitive source build：[JetPref](https://github.com/patrickgold/jetpref) `d6e12dda6517345dacc3682aa476a8448a71c34b`；source archive SHA-256 `f14abe3d2730369b6fc7d5868182722d057eb574bf0c349035f094e7f4a93f6b`；Apache-2.0 `LICENSE` SHA-256 `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4`

## Isolated build contract

所有候选源码、缓存、SDK、AVD 和 APK 均位于一次性 `/private/tmp/opentypeless-ksp002.*` 目录；仓库没有新增第三方源码、APK、Maven artifact 或运行时依赖。

相对固定 upstream 只应用六类临时构建补丁：

1. `local.properties` 指向隔离 Android SDK；
2. `lib/native/build.gradle.kts` 将 native ABI 精确限制为 `arm64-v8a`、`x86_64`；
3. `app/build.gradle.kts` 将 APK ABI 精确限制为同一集合；
4. `settings.gradle.kts` 增加可选、仅本次使用的本地 plugin repository；
5. 同一文件增加可选、仅本次使用的 JetPref 本地 Maven repository；
6. 生成严格 `gradle/verification-metadata.xml`，SHA-256 `04b66b271d840649b117bf4112175d53d106c99524cfb206ac7a6d27c5e55d21`。

没有修改 FlorisBoard 编辑、网络、权限、IME 或 UI 行为。严格 dependency verification 始终开启；最终 clean build 在断网模式执行。

## Toolchain

| Component | Fixed value |
|---|---|
| Java | OpenJDK `17.0.20` |
| Gradle | `9.2.0` |
| Android Gradle Plugin | `8.12.0` |
| Kotlin | `2.2.20` |
| KSP | `2.2.20-2.0.3` |
| compile/target SDK | `36` / `36` |
| min SDK | `26` |
| Build Tools | `35.0.0` |
| NDK | `26.1.10909125` |
| CMake | `4.0.2` |
| Rust | `1.83.0` |
| Rust Android targets | `aarch64-linux-android`, `x86_64-linux-android`（隔离工具链另含 upstream 既有 targets） |

最终构建等价命令：

```bash
env \
  CARGO_HOME="$KSP002_ROOT/home/.cargo" \
  RUSTUP_HOME="$KSP002_ROOT/home/.rustup" \
  GRADLE_USER_HOME="$KSP002_ROOT/gradle-strict2" \
  JAVA_HOME=/opt/homebrew/opt/openjdk@17 \
  ANDROID_HOME="$KSP002_ROOT/android-sdk" \
  ANDROID_SDK_ROOT="$KSP002_ROOT/android-sdk" \
  ./gradlew clean :app:assembleDebug \
  -Pksp002PluginRepo="$KSP002_ROOT/plugin-repo" \
  -Pksp002DependencyRepo="$KSP002_ROOT/jetpref-maven" \
  --dependency-verification strict \
  --offline \
  --no-daemon \
  --stacktrace \
  --console=plain
```

结果：`BUILD SUCCESSFUL in 1m 24s`，`145 actionable tasks: 145 executed`。同一固定源码的两次成功构建产出相同 APK SHA-256。

## APK evidence

| Property | Observed value |
|---|---|
| File size | `33,716,737` bytes |
| SHA-256 | `7a40a44800ed1fa898f626a76560c334d9c03a3450bea2ba37c7d82f0bdcd5d2` |
| Application ID | `dev.patrickgold.florisboard.debug` |
| Version | code `117`, name `0.5.2-debug+null` |
| SDK | min `26`, target `36`, compile `36` |
| Signature | APK Signature Scheme v2；单一 debug signer，certificate SHA-256 `ec62416501e3da3a45d59f4167b14933897c4cdeeeeecb01d6e725fb7de5612e` |
| Declared permissions | `VIBRATE`、`POST_NOTIFICATIONS`、package-local non-exported dynamic-receiver signature permission；无 `INTERNET` |

APK 仅包含以下 native payload：

| ABI | File | Uncompressed bytes |
|---|---|---:|
| arm64-v8a | `libandroidx.graphics.path.so` | 10,096 |
| arm64-v8a | `libfl_native.so` | 1,743,680 |
| x86_64 | `libandroidx.graphics.path.so` | 10,760 |
| x86_64 | `libfl_native.so` | 1,756,880 |

## Install evidence

### Xiaomi 10 Ultra / arm64-v8a

- serial `be4e2015`；M2007J1SC；Android 13/API 33；HyperOS `OS1.0.4.0.TJJCNXM`；
- 首次安装由 HyperOS 显示“USB安装提示”，经用户明确授权后返回 `Success`；
- 第二次同 APK、同签名 `adb install -r` 无人值守返回 `Success`（约 1.2s）；
- fresh `dumpsys package`：`primaryCpuAbi=arm64-v8a`、version code/name 与 APK 一致；
- `FlorisImeService` 已注册、enabled/exported，并要求 `android.permission.BIND_INPUT_METHOD`；
- 默认 IME 仍为 `com.flypy.input/PangIME.Android.InputService`，未切换到候选键盘；
- 系统自动熄屏仍为 10 分钟，充电常亮未启用；`package_verifier_enable=1`，未为本任务关闭 package verifier。

### Android API 26 / x86_64

- 官方 default x86_64 system image revision 1；guest `ro.product.cpu.abi=x86_64`、API `26`、user `RUNNING_UNLOCKED`；
- Apple Silicon host 无法运行 native x86_64 AVD，因此使用 Google 官方 Intel macOS Emulator `37.1.11`（build `15917651`）经 Rosetta + TCG 软件模拟；官方 zip SHA-1 `7df8b0acbe915217dcbb576222bddfcc23e81230`；
- cold boot 完成后 `sys.boot_completed=1`；首次 `adb install -r` 返回 `Success`；
- fresh package/IME 检查：`primaryCpuAbi=x86_64`，version code/name 一致，`FlorisImeService` 注册正确；
- 第二次同 APK 覆盖安装返回 `Success`（`real 210.90s`）；
- 验收后通过 emulator console 正常关闭临时 AVD，未保存 snapshot。

Rosetta + TCG 的耗时只用于证明 x86_64 可安装性，不进入 KSP-008 性能评分。

## Security and privacy

- 没有上传、提交或记录真实用户正文、音频、词典、密钥或 token；
- 没有修改 OpenTypeless runtime、权限、manifest component 或 dependency verification；
- 没有启用候选 IME，也没有更改真机默认输入法；
- 测试 APK 使用上游 debug signer，只用于隔离技术验证，不是发布候选；
- `KSP-007` 仍须完成逐文件、传递依赖、资源与 NOTICE 审计，本报告不替代法律/许可接受。

## Risks and follow-ups

- FlorisBoard `v0.5.2` 仍为 beta；构建出现 upstream deprecation、resource-format 与 KSP nullable DAO 警告，但无构建失败；
- JetPref 使用固定 snapshot 对应的固定 source commit；若进入正式 patch queue，必须改为可重放、可审计的供应链输入；
- 本任务未验证 QWERTY/候选栏/Voice/partial/final/Undo 垂直切片（`KSP-003`），也未验证 librime（`KSP-004`）；
- ADR-0011 保持 `Proposed`，只有 `KSP-010` 可选择最终底座并转 `Accepted`。

## Rollback

删除隔离临时目录并从测试设备卸载 `dev.patrickgold.florisboard.debug` 即可回滚。仓库中没有 KSP-002 runtime/source 变更需要撤销。

## Git

- branch：`agent/android-offline-followup`
- commit：`80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree：共享工作树已有大量其他任务的未提交变更；KSP-002 只新增/更新证据文档，不 stage、不 commit、不 push。
