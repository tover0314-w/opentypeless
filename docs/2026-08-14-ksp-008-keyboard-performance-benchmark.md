# Task Report: KSP-008

## Result

DONE — 在同一台小米 10 Ultra/API 33、同一套候选 APK、同一脚本和交替执行顺序下，完成路线 A
（Floris Shell + 隔离 librime Adapter）与路线 B（fcitx5-android + official Rime plugin）的冷 Activity
初显、命令等待、QWERTY 事务 P95、候选 P95、进程 PSS 和 APK 体积基准。

两条路线的 QWERTY P95 均低于 6 ms，Rime 候选 P95 均低于规范建议的 80 ms。路线 A 的 Activity 初显和
Shell PSS 明显更低；路线 B 的完整 Rime plugin 候选仍有充分热路径余量，但首次安装后的第一次引擎初始化曾达
9.727 s，现有数据下的 fresh-process 初始化约 0.752 s，须作为 KSP-010/TST-008 的启动风险输入。

本任务只记录性能，不选择底座。路线 A 的 Rime 数据来自 KSP-004 两候选合成 Schema，且 Shell 与 Adapter 是
两个隔离进程；其候选延迟、PSS 和 APK 总量只能作为 JNI/Adapter 与分发代理，不能冒充完整词库或最终集成包数据。
ADR-0011 继续保持 `Proposed`。

## Scope

- Task ID: `KSP-008`
- Goal: 对 KSP-003..006 固定的两条路线执行同设备、同脚本、可复现的性能基准。
- Non-goals:
  - 不选择底座、不接受 ADR、不实现 KSP-009 功能矩阵或 KSP-010 决策；
  - 不把第三方候选源码/APK 引入产品、不改变默认 IME、不接线生产 writer；
  - 不把 Activity 初显等同于系统 IME 首次显示；正式 IME Macrobenchmark 仍属于 `TST-008`；
  - 不把两词合成 Schema 与完整 Rime 词库作语言复杂度横向评分。
- OpenTypeless branch/HEAD: `agent/android-offline-followup` / `80d20496c4eb59e4f27281becfa8a32021212e53`。
- Device: Xiaomi `M2007J1SC/cas`，Android 13/API 33，arm64-v8a，security patch `2024-03-01`，
  HyperOS build `V816.0.4.0.TJJCNXM`；ADB serial 在证据中脱敏。
- Stable device conditions: 电量 100%，运行前后 38.4°C；自动熄屏 600,000 ms、充电常亮关闭；默认 IME
  始终为 `com.flypy.input/PangIME.Android.InputService`。

## Fixed artifacts

| Route | Artifact | Bytes | SHA-256 |
|---|---|---:|---|
| A | Floris KSP-003 main | 33,949,144 | `0e98f7458ab2aa0237afbdb3ab56c56e380fa21db397f0a72ba5bee2e436f648` |
| A | KSP-004 librime adapter | 33,349,121 | `81e44ab5565953be838188311813f5c208d41bcd763a6c21b478095175089277` |
| B | fcitx5 main arm64 | 59,762,479 | `1471ad9e4a82502ea1bde4dbbc0f5bcd18148d712394c2f45991b5960ee5cbec` |
| B | official Rime plugin arm64 | 8,942,660 | `fc4a1f426934dae7c2de9ca2ea4413f8b693b7202285a6d252379a220d91c5a2` |

分发代理总量：路线 A 67,298,265 bytes（64.18 MiB），路线 B 68,705,139 bytes（65.52 MiB）。路线 A
尚未把 Shell/Adapter 合入一个 APK，不能据此推断最终包一定比路线 B 小。

## Benchmark protocol

1. 候选 benchmark instrumentation 先以 strict dependency verification 在各自仓库外隔离目录构建；小米安装
   成功后不清 App data，不修改默认 IME、锁屏、屏幕超时或网络设置。
2. QWERTY 每路线 50 次 warm-up、250 次记录；测量同一 OpenTypeless transaction/adapter 调用。
3. 候选测量均为 `ni`：路线 A 25 次 warm-up、200 次合成 Schema 记录；路线 B 25 次 warm-up、120 次
   official Rime runtime 记录。`cold_init_us` 是新 instrumentation 进程在已有数据上的第一次 engine init；脚本
   不用 `pm clear` 制造破坏性“冷数据”。
4. Activity 冷启动每路线 10 次，轮次按 `A→B, B→A` 交替；每次 `am force-stop` 后执行
   `am start -W -S`。`TotalTime` 记录冷进程到 initial display，`WaitTime` 记录命令等待；二者不是
   `InputMethodService` 首次显示 Macrobenchmark。
5. PSS 分为 instrumentation 内当前 benchmark 进程 PSS，以及 Activity 启动后 `dumpsys meminfo` 的 TOTAL
   PSS。路线 A 的两个 instrumentation 包不相加成“最终集成 PSS”。
6. 每条 instrumentation 必须有 `INSTRUMENTATION_CODE: -1`、精确 route/metric JSON，且不得包含失败标记；
   启动样本必须全部报告 `Status: ok` 与 `LaunchState: COLD`。

可重复执行入口为 [`scripts/benchmark_keyboard_routes.py`](../scripts/benchmark_keyboard_routes.py)，解析器/边界测试为
[`scripts/test_benchmark_keyboard_routes.py`](../scripts/test_benchmark_keyboard_routes.py)，完整脱敏原始样本为
[`docs/benchmarks/ksp-008-xiaomi-10-ultra.json`](benchmarks/ksp-008-xiaomi-10-ultra.json)。

## Results

| Metric | Route A | Route B | Threshold / interpretation |
|---|---:|---:|---|
| QWERTY transaction P50 | 4.726 ms | 4.659 ms | Diagnostic |
| QWERTY transaction P95 | **5.649 ms** | **5.708 ms** | Both PASS `< 50 ms`; effectively tied |
| QWERTY max | 15.327 ms | 13.784 ms | Both below 50 ms in this run |
| Rime candidate P50 | 0.279 ms | 5.254 ms | A synthetic / B actual; not language-complexity comparable |
| Rime candidate P95 | **0.392 ms** | **6.150 ms** | Both PASS `< 80 ms`; A only proves Adapter/JNI plumbing |
| Rime process init, existing data | 23.378 ms | 752.371 ms | New process, no storage clear |
| First-install observed Rime init | 16.045 ms | **9,726.915 ms** | One successful post-install pass; B startup risk |
| Activity initial display P50 | 431 ms | 1,039 ms | `am start -W TotalTime` |
| Activity initial display P95 | **437 ms** | **1,128 ms** | A is about 61% lower; not IME-show time |
| Cold command wait P50/P95 | 434 / 444 ms | 1,050 / 1,144 ms | `am start -W WaitTime` |
| Post-launch TOTAL PSS | 78,573 KB | 139,111 KB | A Shell-only proxy; B about 77% higher |
| QWERTY benchmark process PSS | 58,209 KB | 100,537 KB | Isolated test process |
| Rime benchmark process PSS | 32,242 KB | 101,129 KB | A separate synthetic Adapter / B combined actual process |
| APK distribution proxy | 67,298,265 B | 68,705,139 B | B about 2.1% larger; A not yet integrated |

All 20 startup samples and all four performance instrumentation cases completed successfully. The device temperature did not
rise across the final recorded run, so the route-order difference is not explained by measured thermal drift.

## Interpretation for KSP-010

- **Hot key path:** no meaningful separation; both have large margin below the 50 ms target.
- **Candidate path:** route B demonstrates a full plugin/runtime and still has a large margin below 80 ms. Route A only proves the
  self-built JNI/adapter can be fast; KSP-010 must not score 0.392 ms as a full Rime advantage.
- **Cold UI and memory:** route A has the stronger current Shell baseline. Route B initial display is around 2.6× and post-launch
  PSS around 1.8× the route A Shell proxy on this device.
- **Initialization risk:** route B's first-install 9.727 s engine init is user-visible risk even though repeat process init falls
  below one second. A product integration must move/defer work away from keyboard availability and add `TST-008` measurement.
- **APK:** current debug distribution totals are close; neither proxy is a release-size prediction.

These findings are performance inputs only. KSP-009 must still supply the same-device functional matrix; KSP-007 license blockers
remain independent hard gates. KSP-010 may score only after combining those results.

## Security and privacy

- The benchmark uses only synthetic `qwerty`/`ni` inputs. No user text, audio, clipboard, dictionary, Secret, account identifier,
  device serial or screenshot is stored in repository evidence.
- The script requires an explicit serial for command routing but writes only `explicit_serial_redacted` to JSON.
- It does not install packages, clear candidate data, change default IME, disable auto screen-off, enable charge-stay-awake, change
  keyguard, or modify app configuration. It wakes the display once and returns launcher Activities to Home.
- Default IME before/after remained the user's existing input method. Candidate packages remain isolated debug spikes and never
  become a production editor writer.

## Tests actually run

| Command/check | Result | Notes |
|---|---|---|
| `python3 scripts/test_benchmark_keyboard_routes.py -v` | PASS | 8/8 parser, percentile, failure marker, cold-state, timing, artifact hash/symlink, PSS and serial-redaction tests |
| Route A Floris `:app:assembleDebugAndroidTest` with strict/offline verification | PASS | Generated the exact benchmark overlay used on Xiaomi |
| Route A KSP-004 `:app:assembleDebugAndroidTest` with strict verification | PASS | Synthetic Schema/JNI benchmark test APK |
| Route B `:app:assembleDebugAndroidTest --dependency-verification strict` in isolated SDK/home | PASS | Actual fcitx/Rime benchmark test APK |
| Xiaomi route A instrumentation | PASS | QWERTY 1/1 + Rime 1/1 |
| Xiaomi route B instrumentation | PASS | QWERTY 1/1 + actual Rime 1/1 |
| `benchmark_keyboard_routes.py ... --startup-iterations 10` on Xiaomi | PASS | Four instrumentation cases + 10 A and 10 B cold launches; output exactly matches stored JSON |
| API 35 arm64 emulator diagnostic instrumentation | PASS | Route A 2/2, route B 2/2; excluded from scored device table |
| Current HEAD GitHub Actions | NOT RUN — no matching run | Latest visible runs are unrelated commits/workflows; no KSP-008 run exists for current HEAD |

## Evidence

- Raw redacted JSON: [`benchmarks/ksp-008-xiaomi-10-ultra.json`](benchmarks/ksp-008-xiaomi-10-ultra.json)
- Reproduction script: [`../scripts/benchmark_keyboard_routes.py`](../scripts/benchmark_keyboard_routes.py)
- Script tests: [`../scripts/test_benchmark_keyboard_routes.py`](../scripts/test_benchmark_keyboard_routes.py)
- Fixed candidate source/build identities and vertical-slice evidence: KSP-003, KSP-004, KSP-005 and KSP-006 task reports.

## Risks

- Activity launch timing is not IME first-show/second-show. `TST-008` must run Macrobenchmark on the selected integrated shell.
- Route A PSS/APK/candidate numbers are split-package/synthetic proxies; final integration may add duplicated or shared pages and a
  full Schema. They are not a release performance promise.
- Route B first-install engine initialization varied sharply from 9.727 s to roughly 0.75 s with existing data. KSP-010 must not
  discard the first-run observation simply because a warm-storage rerun is faster.
- Debug APKs and instrumentation processes include debug/test overhead. Release/R8/Baseline Profile performance is intentionally
  deferred until a route is selected.
- Only one physical model is the scored device. Emulator results corroborate code paths but do not replace the future Xiaomi 15 and
  reference low-end baselines required by the test plan.

## Rollback

The product app, dependencies, permissions, default IME, configuration, and user data were not changed. Rollback is limited to
removing the KSP-008 script/tests/report/raw evidence and marking KSP-008 TODO again. Third-party benchmark patches/APKs remain in
repository-external isolated directories and may be deleted independently.

## Follow-ups

- KSP-009
- KSP-010
- TST-008

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared worktree already contains many tracked/untracked changes from other completed/in-progress tasks. KSP-008
  does not stage, commit, push, reset, or overwrite unrelated work.
