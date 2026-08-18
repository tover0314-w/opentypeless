# Task Report: KSP-009

## Result

DONE — 在同一台小米 10 Ultra/API 33 上完成路线 A（Floris Shell + 自建 librime Adapter）与路线 B
（fcitx5-android + official Rime plugin）的字段布局、横屏、TalkBack/Accessibility、主题、剪贴板表面、Rime
集成和上游补丁重放矩阵。每个结论均绑定真实设备观察、定向 instrumentation、固定源码默认值或 clean
patch replay；没有凭 UI 印象代替证据。

2026-08-15 重开 follow-up 又在固定 Floris upstream 上生成单一 Route-A 候选；同一 APK 内实际加载双 ABI
librime/JNI，并在 API35 arm64 emulator 与小米 10 Ultra 上分别通过核心 **6/6**、UserDB seed **1/1** 和
force-stop 后 fresh-process restart **1/1**。因此旧版报告中的“Route A integrated Rime FAIL”已由实测关闭，
ADR-0011 的共同功能垂直切片硬门已通过。2026-08-16 的 KSP-007 addendum 随后移除 `han.sqlite3`/Han pack、
来源未闭的 `assets/ime/dict/data.json` 与当前已知 GPL/Lua/octagram 载荷，并关闭 native/CLDR provenance 缺口；
本次 KSP-009 Release closure 又逐项从官方仓库认证 29 个 release-only dependency artifacts，在不放宽 strict
verification 的前提下产出 candidate/replay 逐字节相同的 unsigned Release APK。旧 Release FAIL 仅保留为历史
探测结果，当前 strict Release 门为 **PASS**。同一 final Debug/AndroidTest APK 又在 disposable API26 x86_64
guest 完成安装、core **6/6**、Latin **3/3**、seed **1/1**、force-stop 与 fresh restart **1/1**，x86 动态门也
反转为 **PASS**。KSP-009 当时的 Release/x86 follow-up 至此关闭全部剩余实物硬门；受该阶段单任务边界约束，
ADR-0011 当时仍保持 `Proposed`、KSP-010 仍为 `IN PROGRESS`，随后才进入单独的 KSP-010 正式重裁决。

KSP-010 随后的 whole-artifact 审计发现上述 evidence APK 仍有 ETM 外 writer/`InputConnection`
capability 与 unsafe backup/exported/import surface 两个 P0；该失败历史未被删除。2026-08-16 的
KSP-009 safety follow-up 因此另建不依赖 `:app` 的独立 `:route-a-safety-eval` application，以同一
buildable artifact 闭合真实 View 的 Latin/Rime/Voice/Undo/QuickAction、唯一 ETM authority、无 fallback
Flag spy 与 fail-closed manifest。最终 strict clean/fresh replay、Debug/Release source + whole-APK compiled/
merged-manifest gates、小米 API33 与 API26 x86_64 的 exact **12/12** 均 PASS。这只关闭 KSP-010
所要求的 editor/privacy 评估证据；不是 KBD-001 实现、系统选中 IME E2E、签名 Release
或真实小鹤验收。据此后续单独 KSP-010 重裁决为 `DONE`，ADR-0011 为 `Accepted`，
KBD-001 仍为 `TODO` 但已获准启动。

## Scope

- Task ID: `KSP-009`
- Goal: 对 KSP-003..006 固定的两条候选路线建立同设备、可追溯的功能矩阵。
- 原 KSP-009 功能矩阵阶段 Non-goals（历史范围；不覆盖后续 safety follow-up 与 KSP-010 重裁决）：
  - 不选择目标底座、不接受 ADR-0011、不实现 KSP-010；
  - 不把第三方候选源码或 APK 引入产品树，不接线 production writer；
  - 不读取、写入、粘贴、记录或保存用户剪贴板内容；
  - 不把 Accessibility tree 指标冒充完整的人类 TalkBack 导航研究；
  - 不把仓库外候选冒充 production 集成、已签名发行物或许可接受；
  - 在该阶段不接受 ADR-0011，不把当时 KSP-010 的 `IN PROGRESS` 状态改写为完成。
- OpenTypeless branch/HEAD: `agent/android-offline-followup` / `80d20496c4eb59e4f27281becfa8a32021212e53`。
- Device: Xiaomi `M2007J1SC/cas`，Android 13/API 33，security patch `2024-03-01`，HyperOS build
  `V816.0.4.0.TJJCNXM`；ADB serial 在仓库证据中脱敏。

## Fixed artifacts

| Route | Artifact | Bytes | SHA-256 |
|---|---|---:|---|
| A | Floris KSP-003 main | 33,949,144 | `0e98f7458ab2aa0237afbdb3ab56c56e380fa21db397f0a72ba5bee2e436f648` |
| A | AndroidTest overlay | — | `e3f0a9821cd66ed3a6ad193cf42bf7372ab09bfb5729f26910d415dd93a0c76f` |
| A follow-up | Integrated Route-A Debug | 39,562,488 | `65ada3dd1222dcbf0e0f4b85826c494dff5eb55528039d3a6c651188988ffd54` |
| A follow-up | Integrated AndroidTest | 605,079 | `690d8cf3fa2b876bd62c5d7f407b095d1fdf4294fb2f2e00adc76fff3eb42b16` |
| A closure | Final integrated Route-A Debug | 39,136,901 | `24f43dc77e387bf251cab2c61edaf980e0e73aa3c9420e95fe21e3bbc2c9edd7` |
| A closure | Final integrated AndroidTest | 592,323 | `66dd631d44a5a0255e04305ca4a8fa25bc1a015d5f92e4f84ce393c527301091` |
| A closure | Final unsigned Release | 17,758,708 | `243020c76caaf6f6577f5f14a20a02050a15883ac4ab3dafc919ec2381b94df9` |
| A safety | Restricted safety-eval Debug | 10,390,848 | `072873267bf817043bb022eced1a885ec28d6452ac93cf4f48347894430ad1d9` |
| A safety | Restricted safety-eval AndroidTest | 625,336 | `fd8f3db9c42b82969961c39c1ff88a6be5c461371ee6e3b4b9da0292796161a1` |
| A safety | Restricted safety-eval unsigned Release | 10,009,905 | `75a618a8a78c7ffdcc4d9d3319c5d7d859f3625999a6256b9105375f2c0d247d` |
| B | fcitx5 main arm64 | 59,762,479 | `1471ad9e4a82502ea1bde4dbbc0f5bcd18148d712394c2f45991b5960ee5cbec` |
| B | AndroidTest overlay | — | `b5ca4d367a3f57038f6889897a6205f730774386007f48e07fbd2d2ed742d603` |
| B | official Rime plugin arm64 | 8,942,660 | `fc4a1f426934dae7c2de9ca2ea4413f8b693b7202285a6d252379a220d91c5a2` |
| Host | TestHost main | — | `4a094ecdb30edbee995d2c4592daf396de5d8b2f18a8449315464960b57ea1b5` |
| Host | TestHost AndroidTest | — | `53d9dc5d06b095fa316aba43e40bf7c48d120661daa38fe33cf87ae0648dba3b` |

## Functional matrix

| Area | Route A | Route B | KSP-009 interpretation |
|---|---|---|---|
| Plain/short/long/name/search | QWERTY | QWERTY | Tie |
| Email | Dedicated `@` key | Generic QWERTY; `@` key hint | A has stronger specialization |
| URI | Dedicated `/` key | Generic QWERTY; `/` key hint | A has stronger specialization |
| Phone / decimal | Numeric layouts | Numeric layouts | Tie |
| Date | Generic QWERTY fallback | Generic QWERTY fallback | Same documented fallback |
| Password | Delegates to MIUI secure IME; adb screenshot is zero bytes | Same | No password screenshot or plaintext evidence retained |
| Landscape | Actual extracted fullscreen editor | Actual inline host field | Both render; behavior differs |
| Accessibility baseline | PASS 1/1 | PASS 1/1 | Automated tree baseline only |
| Strict accessibility descriptor | FAIL: 1 screen-reader-focusable action has no label | Explicit focusable check passes vacuously; 5 clickable subtrees unlabeled | Both require follow-up, for different hierarchy reasons |
| Theme | Actual dark render; source modes day/night/system/time, default system | Actual dark render; gallery with light/dark/dynamic/custom previews | Both satisfy surface requirement |
| Clipboard surface/default | Toolbar icon; history default off, system sync `NO_EVENTS` | Toolbar icon; history default on, limit 10, sensitive mask on | B needs a product privacy-default change |
| Rime | Integrated follow-up PASS: actual preedit/candidates/select, shared generation-bound writer, fresh-process UserDB | Actual official plugin PASS 1/1 with shared transaction writer | Common functional hard gate closed for both routes |
| Clean upstream replay | Final closure: 89 files, 10,227,983-byte binary patch, check/apply/exact-tree PASS | 49 files, 380,004-byte patch, check/apply PASS | Route A is reproducible evidence but not a maintainable KSP-011 patch queue |

### Field and orientation evidence

The TestHost exposes plain, short, long, name, search, email, URI, phone, decimal, date, password and dynamic fields. Both
candidates were manually exercised against the same host and the actual input-method window. Password entry delegated to
`com.miui.securityinputmethod`; `FLAG_SECURE` caused `adb exec-out screencap -p` to produce a zero-byte artifact, whose SHA-256 is
the standard empty-file digest. The report stores neither a password image nor user input.

Landscape was verified by rotating the real device rather than resizing an Activity preview. Route A switched to an extracted
fullscreen editor; route B retained an inline host field above the keyboard. Both remained usable, but KSP-010 may score the two
interaction models differently.

### Accessibility evidence

TalkBack was temporarily bound with touch exploration enabled, and a `TYPE_INPUT_METHOD` window was observed. HyperOS presented
three extra permission prompts; all were cancelled. The original accessibility-service list was restored after measurement.

The candidate-specific TestHost instrumentation requires a visible IME package, a minimum labeled-node baseline and labels on leaf
actions. Route A exposed 128 visible nodes, 56 labeled nodes and one screen-reader-focusable action; the strict descriptor probe
correctly failed because that action had no own or descendant label. Route B exposed 154 visible nodes, 57 labeled nodes, 36
actions and 31 described actions. It reported no node with the explicit screen-reader-focusable flag, so the strict check alone is
not affirmative TalkBack quality evidence; five clickable subtrees remained unlabeled. KSP-009 therefore records both defects and
does not manufacture a winner from incomparable tree structure.

### Theme and clipboard evidence

Both candidates rendered an actual dark keyboard. Route A's fixed source exposes `ALWAYS_DAY`, `ALWAYS_NIGHT`, `FOLLOW_SYSTEM`
and `FOLLOW_TIME`, defaulting to `FOLLOW_SYSTEM`. Route B's on-device theme gallery opened successfully and showed multiple light,
dark, dynamic and custom previews.

Both clipboard toolbar icons were observed. Route A's fixed-source defaults disable history and system-event sync while leaving
suggestions enabled. Route B's actual settings page showed history enabled, limit 10, suggestions enabled and sensitive masking
enabled. The page was inspected without opening any entry: no clipboard value was read, written, pasted, logged or stored by this
task. Route B's history default is a KSP-010 privacy-policy input, not an accepted OpenTypeless default.

### Rime evidence

The original KSP-009 run correctly recorded Route A as FAIL because KSP-003 Shell and KSP-004 Adapter were separate packages.
The reopened follow-up does not rewrite that history: it adds later evidence from one fixed-upstream candidate package. Its actual
Rime route processes synthetic `ni`, exposes bounded preedit/candidates, selects `乙`, then runs deterministic Voice
partial/partial/final/exact Undo through the same generation-bound OpenTypeless transaction authority. Switching the editor before a
late selection produces zero write to either old or new editor. Separate cases fail closed before Rime storage or plaintext read in a
sensitive field, and lifecycle cancellation/coalescing cannot persist or write a stale candidate.

The exact core suite passed **6/6** on API35 arm64 emulator and **6/6** on Xiaomi `be4e2015`. On both devices, a separate synthetic
UserDB seed passed **1/1**, the app was force-stopped, and fresh-process restart passed **1/1** with learned candidate order retained.
This closes only the ADR common vertical-slice functional gate; it is not real Xiaohè data, production RIM-001..009 or release proof.

Route B detected the `fcitx5-rime` domain, merged Rime data and added the plugin native-library directory. On the Xiaomi device,
`OpenTypelessFcitxAdapterInstrumentedTest#qwertyAndActualRimeCandidateUseTheSameTransactionWriter` passed 1/1 and exercised real
preedit, candidate selection and commit through the same OpenTypeless transaction writer as QWERTY.

### Upstream synchronization dry run

No third-party source was copied into this repository. For each route, the OpenTypeless patch was generated in a disposable clone,
checked against the corresponding clean fixed upstream tree, then actually applied. Route A replayed 49 files as a 366,089-byte
patch (`bdf8c83f659cd545903fe3159d313f359baa06dd2e26baf613cc8c84f5dccd36`); route B replayed 49 files as a
380,004-byte patch (`94101871475aba3e2631426fc168702f2a23820beaaaeb54517a985c3ca78f7d`). Both `git apply --check` and
`git apply` passed.

The integrated follow-up then restarted from the fixed upstream tar SHA-256
`ba279c66ad4800b8b6242758e734a2f5852d6c1775cb54a538a497b595215594`. The final 68-file binary patch is
48,057,658 bytes, SHA-256 `722797d55cac50abd61415522588b8acc2a5e8331a5ff4e2d9a499ba867de388`;
`git apply --check`, actual apply and source-tree `rsync --checksum` exact comparison all passed. Its size is dominated by pinned
native binaries and demonstrates reproducibility only; it is intentionally not described as the maintained KSP-011 patch queue.

The later KSP-007 resource/provenance addendum produced its own exact 89-file patch. This KSP-009 Release closure adds only the
authenticated release dependency-verification metadata needed by the same candidate: the final 89-file patch is **10,227,983
bytes**, SHA-256 `81bf81420a4f2ce40461163514f59f583dbe33c0d54d72da8efba7aa4017f9f3`, and fresh
apply/check yields exact Git tree `001b727d3c6e2cb8b4a2b50fdb12bb9ca6c6a443`. The final
`verification-metadata.xml` SHA-256 is `6f72a35928022190b243866d17faff1097a895ae14d5938a3ca4048e953ead38`.
All 29 newly required artifacts were individually authenticated against official Google Maven or Maven Central bytes/checksum
sidecars; no cache directory, group or wildcard was trusted wholesale, and strict dependency verification was not disabled.

### Integrated build and artifact scan

- Historical probe: strict offline `clean :app:assembleDebug :app:assembleDebugAndroidTest` passed **189 tasks**; the then-current
  Release attempt failed because the isolated cache lacked `com.android.tools.lint:lint-gradle:31.12.0`. This failure remains
  recorded as discovery evidence and is not the current result.
- Current candidate strict offline Release passed in **2m55s**, **262 tasks**（146 executed、116 up-to-date）. A fresh patch replay
  passed the same strict Release target in **2m44s**, **262/262 tasks**. Candidate/replay unsigned Release APKs are byte-identical:
  17,758,708 bytes, SHA-256 `243020c76caaf6f6577f5f14a20a02050a15883ac4ab3dafc919ec2381b94df9`.
- Fresh replay also passed strict clean Debug/JVM/AndroidTest **209/209 tasks** and JVM **7/7**. The main/test APK hashes remain
  `24f43dc77e387bf251cab2c61edaf980e0e73aa3c9420e95fe21e3bbc2c9edd7` /
  `66dd631d44a5a0255e04305ca4a8fa25bc1a015d5f92e4f84ce393c527301091`.
- Release contains all **225/225** expected assets plus exactly two baseline-profile entries and exactly eight expected native
  entries. Both ABI `librime.so`/`libopentypeless_rime.so` pairs equal their source-built outputs. The release scan found zero
  forbidden/path/GPL/Lua/octagram/unknown-Han/database payload markers; manifest inspection records `minSdk 26`, `targetSdk 36`
  and no `INTERNET` permission.

### x86_64 dynamic closure

A disposable official API26 `default/x86_64` rev1 AVD ran with Intel macOS Emulator 37.1.11/build 15917651 through Rosetta +
software TCG (`-accel off -wipe-data -no-snapshot`) on isolated port 5572. Package service became available after about 7:37 even
though `sys.boot_completed` was still blank at that instant. Installing the final main APK returned `Success` in 11:22.96 and a
fresh `pm path` existed; installing the final AndroidTest APK returned `Success` in 28.20s.

The exact `OpenTypelessKeyboardAdapterInstrumentedTest` returned **OK (6)** in 1:08.75; Latin resource returned **OK (3)** in
15.818s; Rime seed returned **OK (1)** in 21.356s. After explicit `am force-stop` of main and test packages, fresh-process Rime
restart returned **OK (1)** in 1:03.67. Every runner exited 0 with `INSTRUMENTATION_CODE: -1`. Final readback reported
`x86_64`/API26, `sys.boot_completed=1`, package service found and both package paths present. `adb emu kill` returned OK; the
process and port disappeared, while the existing arm64 emulator and Xiaomi default PangIME remained unchanged. The disposable AVD
copy was moved to Trash, so cleanup is recoverable.

### Route-A safety evaluation closure

The final safety object is the independent `:route-a-safety-eval` application. It has no `:app` project dependency and does not
compile/package the rejected `AbstractEditorInstance`/`EditorInstance`/legacy adapter graph. Its production Shell/Rime/Voice/
QuickAction producers hold no `InputConnection`, editor manager, registry or host capability. Real View down/up events become a
closed `SafetyShellIntent`, cross one `ExclusiveWriterRoute`, and reach a capability-free `EditorPort`; framework editor capability
is confined to the `opentypeless-editor-host` authority enclave and the exact seven mutator edges remain in
`EditorTransactionManager`. The old-route spy is capability-free; old-only/new-only, missing/dual binding and rejection/exception
cases prove no dual write, retry or fallback.

The same View exercises Latin `abc`, delete/space/enter, Rime synthetic `ni` preedit/candidates/select, two Voice partials + Final +
exact Undo, ordinary and QuickAction insertion, lifecycle/new-generation late Rime rejection and sensitive-field zero storage/
zero plaintext/zero write. Rime work is generation-bound, off the main thread, bounded and cleaned only inside its authorized
no-backup session root. The synthetic `甲/乙` fixture is not real Xiaohè data and grants no production RIM or resource license.

The source manifest declares no permission, query, profileable, Activity, provider or receiver. Its only exported component is one
`BIND_INPUT_METHOD`-protected evaluation IME service. `allowBackup=false`; base backup excludes all five domains, and API31+
cloud/device transfer each exclude all nine credential/device-protected domains. Debug and Release merged-manifest gates reject the
previous SpellChecker, custom URI/content/SEND import, alias, clipboard SEND, notification/query and extra exported surfaces.

The architecture gate's **30/30** fault-injection tests cover source/compiled hidden writers, exact seven-edge drift, legacy class/
`:app` dependency, producer capability, reflection, MethodHandle/dynamic loader/Unsafe/native/JNI delegation, non-host→host façade
expansion, package/property spoofing and production source/dependency drift. The manifest gate's **23/23** tests cover source and
merged variants plus backup/transfer/component negatives. One manifest-suite invocation from the wrong module tools directory
failed with `ModuleNotFoundError` and ran **0 tests**; the corrected `candidate/tools` invocation passed 23/23 and is the evidence.

Final strict clean
`clean :route-a-safety-eval:check :route-a-safety-eval:assembleDebugAndroidTest` passed in **1m21s** with **216** actionable tasks
(201 executed, 15 up-to-date), Debug JVM **23/23**, Release JVM **23/23**, lint, AndroidTest compile and actual Debug/Release
source + whole-APK compiled + merged-manifest hard gates. Final whole-APK dexdumps were non-empty; the gate scanned every root
`classes*.dex`. The 123-file safety patch is 10,501,449 bytes, SHA-256
`13a0073967cc8fe9e61fe31ffc46b3110ef9e60cb629faa5ba172d33d79a38b0`; fresh apply/check yields exact tree
`338b3ec42379876cf9091552e492e285eb4382d4`, and fresh replay passed the same strict target in **1m29s**, 216 tasks
(210 executed, 6 up-to-date). Candidate/replay Debug, AndroidTest, unsigned Release and merged manifests are byte-identical;
dexdump differs only in its absolute extraction-path header.

Xiaomi 10 Ultra/API33 and the final API26 x86_64 guest each ran the exact `SafetyRouteAInstrumentedTest` and returned
**OK (12 tests)** with zero failure, `INSTRUMENTATION_CODE: -1` and runner exit 0. On x86, streamed installs twice exposed a guest
package-service `Broken pipe` and were retained as failure history; after stable service probes, exact `--no-streaming -r -t`
installs returned `Success`/RC0 in 524.45s (main) and 234.84s (test). Instrumentation took 87.241s (198.99s runner E2E).
Final readback was API26/x86_64/boot=1/package service found with both package paths. Emulator kill removed its PID and ports
5574/5575; the 823 MB temporary AVD moved recoverably to Trash. Xiaomi PangIME and the existing arm64 emulator-5554 were unchanged.

## Security and privacy

- Candidate packages remained isolated debug spikes and never became the product default writer.
- No clipboard value, password, account identifier, device serial, Secret, audio or user text is stored in repository evidence.
- Screenshot evidence is represented by SHA-256 only; the password screenshot artifact was empty due to the secure window.
- No MIUI accessibility permission was accepted. The user's original accessibility services were restored exactly.
- The default and only enabled IME was restored to `com.flypy.input/PangIME.Android.InputService`; Floris and fcitx were disabled.
- Rotation, ten-minute screen-off timeout and charge-stay-awake values were restored. The device finished screen-off, without a
  visible keyguard, and the user's lock-screen-disabled setting was preserved.
- The disposable x86 guest was shut down with `adb emu kill`; its process and port disappeared, the recoverable AVD copy was moved
  to Trash, API35 arm64 emulator remained booted, and the Xiaomi default IME remained PangIME.

## Tests actually run

| Command/check | Result | Notes |
|---|---|---|
| `:test-host:assembleDebugAndroidTest --dependency-verification strict` | PASS | Latest candidate-aware accessibility test compiled |
| TestHost full Xiaomi instrumentation | PASS | 4/4; candidate-specific case skipped by explicit assumption only after candidates were disabled during restoration |
| Candidate-specific accessibility baseline, route A | PASS | 1/1; 128 visible nodes, 56 labeled |
| Candidate-specific accessibility baseline, route B | PASS | 1/1; 154 visible nodes, 57 labeled |
| Route A strict descriptor probe | FAIL — candidate defect | One screen-reader-focusable action had no label; retained as matrix evidence |
| Route B strict descriptor probe | PASS with limitation | Vacuous for explicit focusable flag; five unlabeled clickable subtrees recorded |
| Route B actual Rime transaction instrumentation | PASS | 1/1; real preedit/candidate/commit and shared writer |
| Same-device field/layout/orientation/theme/clipboard matrix | PASS with recorded caveats | Password secure capture produced no image; clipboard contents were never read |
| Route A disposable upstream patch replay | PASS | 49 files; check and apply both succeeded |
| Route A integrated fixed-upstream replay | PASS | 68 files; 48,057,658 bytes; apply-check, apply and exact-tree comparison passed |
| KSP-009 final fixed-upstream replay | PASS | 89 files; 10,227,983 bytes; patch SHA/tree exact; authenticated verification metadata included |
| Strict offline clean Debug + AndroidTest build | PASS | Fresh replay 209/209 tasks; JVM 7/7; dependency verification remained strict |
| Historical strict offline Release assemble | FAIL — superseded probe | Missing lint input was retained as discovery evidence; no artifact from that attempt |
| Candidate strict offline Release assemble | PASS | 2m55s; 262 tasks; 146 executed/116 up-to-date |
| Fresh-replay strict offline Release assemble | PASS | 2m44s; 262/262 tasks; unsigned Release is byte-identical to candidate |
| Final unsigned Release APK audit | PASS | 17,758,708 bytes; 225 assets + 2 baseline profiles; 8 native entries; forbidden markers zero; minSdk 26/targetSdk 36; no INTERNET |
| Integrated Route-A core, API35 arm64 emulator | PASS | 6/6; actual Rime + QWERTY/Voice/Undo/late-event/sensitive/lifecycle cases |
| Integrated Route-A core, Xiaomi `be4e2015` | PASS | 6/6; same final APK and suite |
| Integrated Route-A fresh-process UserDB, both devices | PASS | Each device seed 1/1, force-stop, restart 1/1 |
| Final Debug/Release strip and marker scan | PASS | Unknown Han/data.json/DB/Lua/octagram/GPL markers absent; four Rime SO entries match source-built outputs |
| Integrated Route-A x86_64 main/test install | PASS | API26/default/x86_64 rev1; main `Success` in 11:22.96 and fresh pm path; AndroidTest `Success` in 28.20s |
| Integrated Route-A x86_64 core/Latin | PASS | Exact core 6/6 in 1:08.75; Latin 3/3 in 15.818s; all exit 0/`INSTRUMENTATION_CODE: -1` |
| Integrated Route-A x86_64 UserDB restart | PASS | Seed 1/1, explicit force-stop main+test, fresh restart 1/1 in 1:03.67; final ABI/API/boot/package/path readback PASS |
| Safety architecture verifier unit suite | PASS | Correct final gate: 30/30; reflection/dynamic/native/façade/package/source/dependency bypass corpus included |
| Safety manifest verifier first invocation | FAIL — corrected command-path error | Wrong module tools directory; `ModuleNotFoundError`; 0 tests actually ran |
| Safety manifest verifier corrected invocation | PASS | Correct `candidate/tools` path; 23/23 |
| Safety strict clean + fresh replay | PASS | 216 tasks each; D/R JVM 23/23 each; lint, AndroidTest compile, actual D/R compiled/merged-manifest gates; exact tree/artifacts |
| Safety-eval Xiaomi API33 exact class | PASS | Final exact main/test; `OK (12 tests)`, 0 failure, code -1, runner RC0 |
| Safety-eval API26 x86_64 exact installs/class | PASS with retained streamed failure history | Two streamed `Broken pipe` attempts retained; no-streaming main/test `Success`; `OK (12 tests)`, 0 failure, code -1, runner RC0 |
| Safety-eval x86 final readback/cleanup | PASS | boot=1/x86_64/API26/service/path2; emulator/PID/ports gone; temp moved to Trash; Xiaomi PangIME/arm64-5554 unchanged |
| Final independent red-team review | PASS | Source/gate/final D/R static residual P0/P1 = 0; GO limited to KSP-009 safety artifact and KSP-010 editor/privacy gates |
| Route B disposable upstream patch replay | PASS | 49 files; check and apply both succeeded |
| Current HEAD GitHub Actions | NOT RUN — no matching run | No KSP-009 workflow run exists for current HEAD |

The route-A strict descriptor failure and the superseded Release probe failure are not hidden by the `DONE` task state: KSP-009's
delivery is the functional evidence matrix plus the later same-candidate Release closure. `DONE` closes the integrated common
functional slice, strict Release artifact gate and x86 dynamic matrix. It does not accept ADR-0011 inside KSP-009, nor close final
accessibility, signed distribution, NOTICE/SBOM, KSP-012 language-resource approval or production integration.

## Evidence

- Structured redacted evidence: [`benchmarks/ksp-009-xiaomi-10-ultra.json`](benchmarks/ksp-009-xiaomi-10-ultra.json)
- Device host: [`android/test-host`](../android/test-host)
- Fixed route/build provenance: KSP-003, KSP-004, KSP-005 and KSP-006 task reports
- License and performance inputs: [KSP-007](2026-08-14-ksp-007-license-compliance-analysis.md) and
  [KSP-008](2026-08-14-ksp-008-keyboard-performance-benchmark.md)

## Risks

- Route A integrated Rime is proven only in a repository-external Debug spike; production lifecycle, UI candidate presentation,
  selected-shell performance and RIM-001..009 remain future work.
- x86_64 dynamic correctness passed under Rosetta + software TCG; its long boot/install times are host-emulation overhead and are
  not product performance evidence.
- The unsigned Release proves strict build/package closure only; release signing, formal NOTICE/SBOM/source bundle and distribution
  drift enforcement remain REL/SEC work.
- Route B ships license/relink obligations from KSP-007 and has a first-install Rime latency risk from KSP-008.
- Automated accessibility trees differ structurally. A human TalkBack traversal on the selected integrated IME remains required.
- Route B's clipboard-history default conflicts with OpenTypeless privacy posture until explicitly changed and tested.
- Both upstream replay patches are already large. Passing one clean replay does not substitute for KSP-011's maintained patch queue.
- The safety-eval module is an evidence artifact, not the complete selected Shell. KBD-001 remains TODO and must preserve these
  hard gates while importing only the reviewed Route-A source boundary; system-selected IME E2E remains unrun for the product.

## Rollback

No product dependency, permission, writer, persistent format or default IME changed. The candidate and its patch remain outside the
repository and can be deleted independently. Runtime rollback is therefore unnecessary; documentation rollback is removal of this
follow-up evidence, which would reopen the Route-A common functional, strict Release and x86 dynamic gates.

## Follow-ups

- KSP-011
- KSP-012
- TST-008

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: shared worktree already contains many tracked/untracked changes from completed and in-progress tasks. KSP-009
  does not stage, commit, push, reset or overwrite unrelated changes.
