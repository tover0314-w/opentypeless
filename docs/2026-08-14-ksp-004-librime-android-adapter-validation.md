# Task Report: KSP-004

## Result

DONE — 完成的是 ADR-0011 允许的仓库外 librime Android Adapter 隔离验证；没有把第三方源码、native runtime、
测试 Schema 或 APK 引入 OpenTypeless 产品树，也没有选择最终键盘底座。

## Scope

- Implemented:
  - 固定 librime `1.17.0` commit `33e78140250125871856cdc5b42ddc6a5fcd3cd4` 及 recursive gitlink；
  - 用 Android NDK `26.1.10909125` / API 26 为 `arm64-v8a`、`x86_64` clean-build `librime.so`；
  - 实现 Java 17 + C++17 JNI adapter，串行化 Rime 生命周期，返回 bounded preedit/candidates/commit；
  - 加入只含合成字符“甲/乙”的测试 Schema，验证 schema deploy、候选选择与 UserDB；
  - 在两个独立 instrumentation 进程之间验证用户候选排序持久化。
- Not implemented:
  - 未接 OpenTypeless 生产 IME、`CompositionCoordinator`、`EditorOperation` 或 FlorisBoard spike；
  - 未引入真实小鹤码表/词库、用户导入、同步、诊断、性能或正式 Feature Flag；
  - 未完成 KSP-007 的逐文件许可证/NOTICE/动态链接接受结论，也未替代 KSP-010 的负责人决策；
  - `x86_64` 完成 clean build、ELF 与 APK payload 校验，但 Apple Silicon 上没有运行 x86 guest；运行时证据来自
    两个 `arm64-v8a` 环境。

## Changes

隔离 spike 位于 `/private/tmp/opentypeless-ksp004.Q5pkQ5`，主要 patch surface：

- `build-android.sh`: 为两个 ABI 构建 Boost、yaml-cpp、LevelDB、marisa-trie、OpenCC 与 librime；依赖静态并入
  `librime.so`，最终动态依赖仅为 Android `libm` / `libdl` / `libc`；
- `android-spike/native/rime_jni.cc`: 进程内 mutex、固定 schema、bounded ASCII 输入、最多 16 个候选、最多 256
  code points/候选、显式 session cleanup；SHA-256
  `33d81e170f9f6cf1216fb9fd111057700eebe60579cdb0f72afdcd70efd0309e`；
- `RimeAdapter.java`: 无 editor capability 的 Java façade，复制 schema 到 app 私有目录，Snapshot `toString()` 只输出
  长度/数量；SHA-256 `81e89e23bff787f8eb71fb1a341e25f789e1285e05e7b07de6cf3cd4e5ea63d8`；
- `ksp004.schema.yaml` / `ksp004.dict.yaml`: 仅包含 `ni → 甲/乙` 的合成测试数据，未使用真实词典；
- 4 个 instrumentation test 文件：基础 adapter、安全边界、UserDB seed 与 fresh-process restart。

构建期间发现并修复一个仅 ARM64 真机可见的问题：librime `RimeSessionId` 来自 tagged native pointer，在 Java
`long` 中可能为负；JNI 现在只把 `0` 判为无效，不再错误拒绝合法负值句柄。

## Architecture

- contracts: Rime adapter 只接受 bounded 测试输入并返回不可变 preedit/candidate 快照；不接收、不存储、不返回
  `InputConnection`，不执行任何 editor write；候选提交结果仍需未来经 OpenTypeless transaction 层交付。
- state changes: native runtime 为进程内单例且所有入口受同一 mutex 串行；每个测试显式创建/销毁 session，
  `close()` 最终清理所有 session 并 finalize。
- migration: 仅隔离测试包的 app-private `shared/` 与 `user/` 目录；无 OpenTypeless 数据格式或迁移。
- feature flag: 无生产 Feature Flag；该 spike 不在产品调用图中。

## Security & privacy

- data sent/stored: 无网络；仅 app 私有目录保存 synthetic schema 与 synthetic UserDB。Rime INFO 日志关闭，
  Snapshot/异常不含 preedit、候选、路径或 UserDB 内容。
- permissions/components: 主 APK 无声明权限、无 exported component、无 IME service；默认输入法未切换。
- threat considerations:
  - Java 反射测试确认 façade 的字段、参数和返回类型均不含 `InputConnection`；
  - 输入长度限制为 128 个小写 ASCII 字符，候选数量/长度均有硬上限；
  - APK 同时包含两套 ABI，JNI adapter 仅依赖同包 `librime.so` 与 Android 系统库；
  - UserDB 未上传、未导出、未读取词条正文作为诊断；真实 UserDB 生命周期/备份仍属于 RIM 后续任务。

## Tests actually run

| Command | Result | Notes |
|---|---|---|
| NDK CMake `--clean-first`，`arm64-v8a` / `x86_64` | PASS | 两套 JNI + librime 均从源码重编；adapter `-Wall -Wextra -Werror` |
| fresh `GRADLE_USER_HOME`, `clean :app:assembleDebug :app:assembleDebugAndroidTest --dependency-verification strict` | PASS | 59/59 tasks executed；`BUILD SUCCESSFUL in 1m 34s` |
| `llvm-readelf` 两套 adapter/runtime | PASS | adapter 仅 NEEDED `librime.so`, `libm.so`, `libdl.so`, `libc.so`；无共享 libc++ |
| `apksigner verify --verbose --print-certs`（两包） | PASS | v2、单一 Android Debug signer，certificate SHA-256 `ec624165…5612e` |
| API35 arm64 emulator 基础 adapter | PASS | 2/2：schema/preedit/candidates/commit、bounds/redaction/no editor capability |
| API35 arm64 emulator seed → force-stop → restart | PASS | 1/1 + 1/1；fresh process 中“乙”排序超过静态首选“甲” |
| 小米 10 Ultra/API33 基础 adapter | PASS | 2/2；最终 clean-build APK |
| 小米 10 Ultra/API33 seed → force-stop → restart | PASS | 1/1 + 1/1；UserDB 文件存在且 fresh process 排序生效 |
| 小米主/测试 APK 同签名覆盖安装 | PASS | 首次新 package 各确认一次；随后多轮均无人值守 `Success` |
| OpenTypeless current HEAD GitHub Actions | NOT RUN | current HEAD 没有本任务对应 CI；spike 位于仓库外 |

## Evidence

- Upstream: tag `1.17.0`, commit `33e78140250125871856cdc5b42ddc6a5fcd3cd4`；recursive gitlinks：
  glog `7b134a5c…`, googletest `f8d7d77c…`, LevelDB `99b3c03b…`, benchmark `bf585a27…`,
  LevelDB googletest `c27acebb…`, marisa-trie `3e87d53b…`, OpenCC `556ed224…`, yaml-cpp `2f86d137…`。
- Boost `1.89.0` official CMake archive SHA-256
  `67acec02d0d118b5de9eb441f5fb707b3a1cdd884be00ca24b9a73c995511f74`。
- Main APK: `33,349,121` bytes，SHA-256
  `81e44ab5565953be838188311813f5c208d41bcd763a6c21b478095175089277`。
- AndroidTest APK: `1,657,391` bytes，SHA-256
  `e9304777bd00deabe7a6bdd84c74bf51583d7fc2a0d307137cfe700ba35e2b62`。
- Native SHA-256：arm64 librime `883bfcd1…face9a`、adapter `a6aac24a…a6b3d`；x86_64 librime
  `cb36aae6…00e25`、adapter `9c75722c…50631`。
- APK payload：arm64 librime/adapter `58,127,976` / `189,632` bytes；x86_64 `54,995,736` /
  `180,680` bytes；manifest 无权限。
- Emulator: `Android SDK built for arm64`, API35；package `primaryCpuAbi=arm64-v8a`。
- Xiaomi: `be4e2015`, M2007J1SC, Android 13/API33, HyperOS `OS1.0.4.0.TJJCNXM`；package
  `primaryCpuAbi=arm64-v8a`，默认 IME 始终为 `com.flypy.input/PangIME.Android.InputService`。

## Risks

- synthetic table schema 只证明 adapter/runtime/UserDB 闭环，不证明真实小鹤资源、造词、简繁、标点或导入兼容；
- native runtime 尚未进入 KSP-008 同设备性能/内存/APK 评分，也未做低内存杀进程、损坏 UserDB 或迁移测试；
- librime 与传递依赖的最终分发/NOTICE 结论留给 KSP-007，当前 BSD/BSL 等上游声明不是法律接受；
- 生产接线前必须经 KSP-010 选择路线，并由 RIM/KBD 任务把候选结果送入统一 Composition/EditorTransaction，
  不能让 JNI adapter 直接持有 editor capability。

## Rollback

删除仓库外隔离目录，并卸载 `com.opentypeless.ksp004` / `com.opentypeless.ksp004.test` 即可；OpenTypeless
生产 APK、默认 IME、权限、配置与用户数据无需回滚。仓库内仅需移除本报告及对应规范引用。

## Follow-ups

- KSP-005
- KSP-006
- KSP-007
- KSP-008
- KSP-009
- KSP-010
- KSP-012
- RIM-001

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: 共享工作树已有大量其他任务的 tracked/untracked 变更；KSP-004 只落盘验证文档，不 stage、
  commit 或 push；第三方源码、native runtime、SDK、AVD、APK 与 UserDB 均留在仓库外隔离目录。
