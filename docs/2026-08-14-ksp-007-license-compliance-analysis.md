# Task Report: KSP-007

## Result

DONE — 对 KSP-002..006 固定的两条键盘底座候选执行了源码、最终 APK、native 二进制、内置数据、递归
submodule/prebuilt 与修改边界的许可证合规分析，记录了可接受方案、发布前材料和禁止复制范围。

本报告是工程合规分析，不是法律意见，也不选择最终底座。路线 A 在满足本文 NOTICE/SBOM/数据来源门禁后可作为
MIT 主仓库的候选分发架构；路线 B 的 KSP-006 调试产物不能按“仅 LGPL”或“MIT + LGPL”发布，因为最终主 APK
实际包含 GPL-2.0-or-later 的 Lua 扩展，最终 Rime plugin 的 `librime.so` 实际静态包含 GPL-3.0-only 的
octagram。路线 B 只有在明确接受相应 GPL 分发范围，或从可重放源码移除这些 GPL 输入并重新构建，再完整满足
LGPL 的源码、修改、重链接/替换材料后，才可进入发布候选。

ADR-0011 继续保持 `Proposed`；只有在 KSP-009 safety follow-up 以同一 buildable artifact 关闭 editor/privacy P0 后，
未来 KSP-010 重裁决才可转为 `Accepted`。KSP-012 仍须独立裁决小鹤码表和词库，SEC-010/REL-003/REL-007 仍须
生成正式 SBOM、provenance、模型/Schema 清单。

2026-08-16 的 Route-A addendum 已把本报告发现的两个未知 bundled-resource 输入从最新 Debug 候选中移除：
`han.sqlite3`/Han pack 与 `assets/ime/dict/data.json` 均不再打包；Latin 拼写、建议与 glide 在无已许可词库时明确
fail closed，旧 Han provider ID 回退且不崩溃。候选同时补入 CLDR v45 的 Unicode License v3 和 native/patch
provenance seam。该补证关闭的是当前 Debug 候选的已知资源来源缺口，不是正式 Release NOTICE/SBOM，也不改变
KSP-012 对真实小鹤资源“未经许可不得随包、仅可用户显式导入”的边界。

后续 KSP-009 已取代本报告 addendum 当时的 Release/x86 缺口：strict candidate/fresh replay Release 与 x86_64
动态矩阵均为 PASS。KSP-010 的最终隐私审计又确认该 APK 只能作为 evidence artifact，不能作为 production
candidate：whole upstream/candidate App 的 `allowBackup=true`、backup/transfer rules、profileable、SpellChecker、
custom URI/content/SEND import、launcher alias、copy-to-clipboard、notification/query 与额外 exported surfaces 不被
选择。KSP-010 还发现 whole compiled graph 保留 ETM 外 direct writers/`InputConnection` capability；未来 restricted
Shell source boundary 尚无 buildable evidence。ADR-0011 因 editor/privacy P0 保持 `Proposed`；formal notices/SBOM/
source bundle/drift 仍是 SEC/REL 发布门，真实小鹤仍是 KSP-012 门。

## Scope

- Task ID: `KSP-007`
- Goal: 审计 KSP-002..006 固定候选的 Apache/BSD/LGPL/GPL 边界、NOTICE、源码和可替换/重链接要求。
- Non-goals:
  - 不提供法律意见或代表许可证权利人作解释；
  - 不选择底座、不接受 ADR、不把第三方源码、二进制、资源或依赖引入产品树；
  - 不审计尚未选定的小鹤资源，不实现 SBOM CI、上游同步脚本或发布包；
  - 不把测试 APK 的可构建/可安装结论当成可分发许可。
- OpenTypeless branch/HEAD: `agent/android-offline-followup` / `80d20496c4eb59e4f27281becfa8a32021212e53`。
- Fixed inputs:
  - FlorisBoard `v0.5.2` / `2e82060251897226c0739b9f52d1d051b02305fb`；
  - JetPref `d6e12dda6517345dacc3682aa476a8448a71c34b`；
  - librime `1.17.0` / `33e78140250125871856cdc5b42ddc6a5fcd3cd4` 与 Boost `1.89.0`；
  - fcitx5-android `0.1.3` / `048f581c652367567b8ee5c28c5163b805288895` 及 22 个 recursive gitlink；
  - fcitx prebuilt gitlink `86ce2c95d42f1132746fbf60c278193aa1f4b758`，其工具链记录的 prebuilder
    `eb156443de3b387089e51f9bd19df4e3ddce1732`。

## Decision summary

| Route | Engineering compliance result | Release boundary |
|---|---|---|
| A — Floris Shell + OpenTypeless adapter + self-built librime | **Conditionally acceptable** | 保留 Apache-2.0/BSD/MIT/BSL/Unicode 许可与归属；静态 native 依赖进入完整 notices/SBOM；内置语言包、emoji/CLDR 与最终 Schema/词库逐项固定来源。无 LGPL/GPL runtime 被 KSP-002/004 最终产物证明进入该路线。 |
| B — fcitx5-android + official Rime plugin | **Current artifacts not acceptable as LGPL-only; conditionally acceptable only as an explicit GPL/LGPL distribution or after a clean GPL-free rebuild** | 主 APK 含 GPL-2.0-or-later `pinyin.lua`；Rime `librime.so` 含 GPL-3.0-only octagram；另有 LGPL-2.1/3.0 代码和数据。必须明确整个发行单元的许可证、提供完整对应源码/修改/构建和必要重链接材料；不能只展示 AboutLibraries JSON。 |
| HeliBoard / Trime / unselected fcitx plugins | **Reference only** | 未经新的许可证 ADR 与负责人明确接受 GPL 分发后果，不得复制源码、资源或衍生实现到当前 MIT 主干。 |

“Conditionally acceptable”只表示存在可执行的合规路径；不代表第三方律师复核、KSP-010 选择或正式发布门禁已完成。

## Route A inventory and obligations

### Shell and managed dependencies

| Component | Fixed identity | Observed license | Distribution requirement |
|---|---|---|---|
| FlorisBoard | `2e820602…05fb` | Apache-2.0；`LICENSE` SHA-256 `b7eb0e4356678f0fa7fb53a35c864ec0b3dca6c6602a26c3cb972c13e2041fcf` | 保留许可证、版权/归属与修改说明；若上游后续出现 NOTICE，发布物须逐字保留其相关内容。当前固定源码无 top-level NOTICE。 |
| JetPref | `d6e12dda…c34b` | Apache-2.0；`LICENSE` SHA-256 `c71d239df91726fc519c6eb72d318ec65820627232b2f796219e87dcf35d0ab4` | 与 Floris 分开记录 source commit；不能只记录本地 Maven snapshot。 |
| Final Floris APK managed inventory | KSP-002 APK | 137 entries：134 Apache-2.0、2 MIT、1 ICU/Unicode custom | 正式发行须从实际 release variant 生成 inventory；AboutLibraries 是 UI 入口，不替代可下载的 notices/SBOM。 |
| Floris Rust native lock | exact KSP-002 `Cargo.lock` | Android graph为 MIT、Apache-2.0、Unlicense 与 Unicode-DFS-2016 兼容组合；workspace crates未声明独立 SPDX | 正式 fork 给 OpenTypeless 新/修改 workspace crate 加明确 SPDX；只列 Android target 实际链接依赖，target-only Windows crates不得伪称随 APK分发。 |

Floris APK 中非 Apache managed 项为 `colormath 3.6.1`、`material-kolor-android 4.0.5`（MIT）和 ICU/Unicode
许可集合。ICU 配置中还携带其第三方字典/数据 notices；发布生成器必须保存完整文本，不能把它压缩成一个
“icu4c”标签。

### Built-in Floris resources

固定源码内 8 个 built-in extension manifest 均声明 Apache-2.0，包括 layouts、composers、currency sets、
localization、themes、default language pack 和汉字形码 basic pack。后者把 7,430,144-byte
`han.sqlite3` 打入 APK，SHA-256 为
`197c212f452aef9d6d09bcf257c87e7099c70eaff1ce266f2626bb3609ea0540`，并列出郑码、嘸蝦米和仓颉维护者。

该 manifest 声明是可追踪输入，但不是对每条字典数据权利的独立证明。路线 A 正式 fork 必须二选一：

1. 取得并记录该数据库的生成源码、上游 commit、作者/数据来源及可再分发证明；或
2. 从 OpenTypeless release variant 删除该数据库，只随包放入来源已审计的资源。

Floris emoji/CLDR 数据同样必须保留 Unicode/CLDR 许可与版本。任何小鹤码表、词库或第三方主题都不继承
FlorisBoard root Apache-2.0 结论，继续由 KSP-012/REL-007 单独审批。

### Self-built librime runtime

KSP-004 的 `librime.so` 把所有第三方库静态并入，最终动态依赖只有 Android system libraries。实际构建关闭
`ENABLE_LOGGING`，因此 glog 没有进入最终 runtime；googletest/benchmark 也不是发行输入。

| Linked input | Fixed source | Selected license branch | Required material |
|---|---|---|---|
| librime | `33e781402…cd4` | BSD-3-Clause | 完整 BSD notice；binary distribution 中重现 copyright、conditions 与 disclaimer。 |
| Boost | `1.89.0` archive SHA-256 `67acec02…1f74` | BSL-1.0 | BSL text、archive source/digest。 |
| yaml-cpp | recursive gitlink | MIT | MIT copyright/license。 |
| LevelDB | recursive gitlink | BSD-3-Clause | BSD notice。 |
| marisa-trie | recursive gitlink | **BSD-2-Clause selected**, not LGPL | 明确记录许可证选择并随 binary 重现 BSD notice，避免把 dual license 当成两者都适用。 |
| OpenCC | recursive gitlink | Apache-2.0 | Apache license、attribution、OpenCC data provenance。 |

KSP-004 的合成 `ni → 甲/乙` Schema 仅是测试 fixture，不是可发布语言资源；最终小鹤资源仍未授权进入发行包。

### 2026-08-16 Route-A resource/provenance addendum

最终 addendum 基于同一 fixed upstream 生成 89-file、10,214,294-byte binary patch，SHA-256
`a04c5fecdadadc49e5f67e09495de6f71219cb02b5e974a80bd9d8fe1d2985a5`。全新解包树使用普通
`git apply --check`/apply 后 Git tree 为 `d99747a43f3c8dcc2a9c70de1f789cce6948af30`，与候选 exact；候选与
replay 的 225/225 assets 路径和内容逐项一致。

addendum 的资源边界如下：

- 删除 `han.sqlite3`、Han pack 的运行时注册和新用户选择/preset；保留上游 provider 源码，但旧持久 provider ID
  的 spelling/suggestion 都显式回退，不读取已删除数据库；
- 删除来源未闭的 `assets/ime/dict/data.json`；Latin 内建 word/frequency/suggestion/correction 数据为空，字面
  `typo`/`gerror` 演示候选也已删除；legacy `glide.enabled=true` 在 detector 前因无 word data 被禁用，普通按键
  仍走原路径；
- 为 bundled CLDR v45 emoji 数据打包 `assets/license/cldr_45_unicode_3_0.txt`，并记录 Route-A patch/native
  source、选定许可分支与静态链接 closure；source-first 脚本固定 HEAD、clean worktree、OpenCC 精确允许修改及
  patch hash，重建/strip 两 ABI librime/JNI 并拒绝 host path；这些 seam 不替代未来 release 的完整 notices、
  SBOM 和 source acquisition/bundle；
- 未引入真实小鹤码表/词库。KSP-012 仍为阻塞任务，当前只允许用户显式导入来源与许可可核验的资源。

source-first 脚本 SHA-256 为 `e9b7fd8603adfc349d0998de0cac9e53fafca99259f8421bd0e97b104823cddf`。
其 stripped native 输出及随后 `jniLibs`/APK entry 的逐字节相同哈希为：arm64-v8a librime
`1e94fd8ae942bc6b146ec5febe4c26913c9a5feefd761cd496defd55873e6394`（4,381,752 bytes）、JNI
`b9f8b76169e06694f9f19dc788b3a75c186acffbd519d9fc64a30743441fe789`（37,944 bytes）；x86_64 librime
`e7252fb56d346a30aee76800b166987bb83e4c36b77d8a1afa44019774a9a7c8`（4,384,720 bytes）、JNI
`e7c4b62862e57399239248aabb66f173105ac66882de4b9ed19279d1ba076011`（37,032 bytes）。最终 APK 共 8 个
native entries，forbidden/path/GPL/Lua/octagram 扫描为零。

candidate strict-offline clean build **209 tasks PASS**（207 executed、2 up-to-date），定向 JVM **7/7 PASS**；
fresh replay 同为 **209 tasks PASS**（204 executed、5 up-to-date）与 JVM **7/7 PASS**。candidate/replay 主 APK
逐字节相同，39,136,901 bytes，SHA-256
`24f43dc77e387bf251cab2c61edaf980e0e73aa3c9420e95fe21e3bbc2c9edd7`；AndroidTest APK 也逐字节相同，
592,323 bytes，SHA-256 `66dd631d44a5a0255e04305ca4a8fa25bc1a015d5f92e4f84ce393c527301091`。

最新冻结 main/test APK 在 Xiaomi 10 Ultra/API33 与 API35 arm64 emulator 上均安装成功，并各通过 core **6/6**、Latin resource
**3/3**、Rime seed **1/1**；分别 force-stop target/test 后，独立 fresh-process restart **1/1 PASS**。
两端命令均 exit 0；小米默认 IME 复核仍为 `com.flypy.input/PangIME.Android.InputService`。x86_64 APK 已打包，
但 Apple Silicon 上 HVF x86 启动失败；`-accel off` 的 QEMU 运行 17:05、约 99.8% CPU 后虽出现 ADB device 和
x86_64 ABI，`sys.boot_completed` 仍为空且 package service 不存在，安装返回
`cmd: Can't find service: package`。该项严格记为 **NOT RUN — host capability blocked**，不能写成安装通过。

final candidate strict-offline `:app:assembleRelease` 在 2 秒、109 tasks（92 executed、17 up-to-date）后于
`:opentypeless-editor-host:generateReleaseLintModel` **FAIL**；dependency verification 仅缺两个 release-only POM
的可信校验项：`com.materialkolor:material-color-utilities-android:4.0.5` 与
`org.jetbrains.compose.ui:ui-backhandler-android:1.9.0-beta03`。未关闭或绕过 verification，也没有 Release
artifact。因此该 addendum 不接受 ADR-0011，也不把 KSP-010 标为完成。

## Route B inventory and blocking evidence

### Main APK is not LGPL-only

KSP-006 final arm64 main APK SHA-256 为
`1471ad9e4a82502ea1bde4dbbc0f5bcd18148d712394c2f45991b5960ee5cbec`。其中 AboutLibraries 有 118
entries：108 Apache-2.0、3 LGPL-2.1-or-later、2 BSL-1.0、2 MIT、1 WTFPL，以及 dual-license
zstd/chinese-addons entries。

更重要的是最终 APK 实际包含
`assets/usr/share/fcitx5/lua/imeapi/extensions/pinyin.lua`。它与固定 source 文件逐字节相同，7,808 bytes、
SHA-256 `c56a9da457279312952a43178bb669b1473a6e13b4e93aac2701a8d29f143df8`，文件头明确为
`GPL-2.0-or-later`。因此不能用 root LGPL 声明或 AboutLibraries 的 dual-license 汇总把该 APK描述成 LGPL-only。

主 APK 同时打包 fcitx5、fcitx5-lua、libime（LGPL-2.1-or-later）以及 pinyin/table/chttrans/punctuation 等
native modules。`fcitx5-chinese-addons` 的 C/C++ runtime 文件在该固定提交主要标注 LGPL-2.1-or-later，但
GPL Lua extension 确实随包存在。可接受方案为：

- **GPL route:** 明确该发行单元的 GPL 兼容许可和所有对应源码义务，经负责人及法律复核接受；或
- **rebuild route:** 从可重放源码删除 GPL Lua extension 和任何 GPL-only plugin/data，重新 clean-build，使用
  artifact inspection 证明 release APK 无 GPL payload，再按 LGPL 方案发布。

仅删除 UI license entry、把文件改名、改包名或把 GPL 文件放入 assets/plugin 不会改变其分发事实。

### Rime plugin contains GPL-3.0 code

KSP-006 final arm64 Rime APK SHA-256 为
`fc4a1f426934dae7c2de9ca2ea4413f8b693b7202285a6d252379a220d91c5a2`；其中 stripped
`librime.so` 为 5,217,712 bytes、SHA-256
`3353fccc1fbe78aad766723d84532c7331f98ef5ae0e0d0cf876949e69dee406`。

该 ELF 可直接读到 `rime_require_module_octagram`、`OctagramComponent`、`Octagram::Query` 及
`plugins/octagram/src/*.cc` 字符串。prebuilder `eb156443…1732` 的 `LibRime` 规则又明确把
`librime-octagram` 挂入 `librime/plugins/octagram` 后以 `BUILD_SHARED_LIBS=OFF` 构建。对应固定 gitlink
`dfcc15115788c828d9dd7b4bff68067d3ce2ffb8` 和 APK metadata 都声明 `GPL-3.0-only`。这是“进入最终
binary”的证据，不只是候选仓库中存在未使用 GPL source。

同一 plugin 还随包分发：

| Component/data | Fixed/observed version | License |
|---|---|---|
| fcitx5-rime | source `4e996319…6d6a` / metadata 5.1.14 | LGPL-2.1-or-later |
| librime prebuilt | prebuilder gitlink `de4700e9…0567` / metadata 1.16.1 | BSD-3-Clause |
| librime-octagram | `dfcc1511…2ffb8` | GPL-3.0-only |
| librime-lua / librime-predict | `68f9c364…b627` / `920bd41e…9791` | BSD-3-Clause |
| rime-essay | source gitlink `e9b1a374…35a` / packaged metadata `816b9ee` | LGPL-3.0-only |
| rime-luna-pinyin | source gitlink `56b934b0…e42` / metadata `0c6d8e3` | LGPL-3.0-or-later |
| rime-prelude | source gitlink `082425ea…87e` / metadata `541e03e` | LGPL-3.0-only |
| rime-stroke | `3a4b0f40…f39c` | LGPL-3.0-or-later |
| glog / LevelDB / marisa / OpenCC / Boost / Lua / yaml-cpp | prebuilder-fixed | BSD, BSD-2 selected, Apache-2.0, BSL-1.0, MIT |

source gitlink 与 packaged prebuilt metadata 对 essay/luna/prelude 并不完全相同，证明“递归初始化 App 仓库”不能
替代 native prebuilt 的 exact source manifest。正式分发必须以最终 ELF/assets 的真实来源为准。

### Prebuilt/relink gap

fcitx prebuilt gitlink `86ce2c95…b758` 只包含 static libraries、headers、dictionaries/data 和
`toolchain-versions.json`，没有随 binary 提供完整对应源码或 license bundle。该 JSON 固定的 prebuilder
`eb156443…1732` 是 verified commit，并固定 librime/octagram/依赖 gitlinks，但 prebuilder 仓库本身没有
GitHub 可识别的 root license。上游 F-Droid recipe 能从 prebuilder 重建，说明存在技术路径；它仍不是当前 APK
已经携带完整合规材料的证明。

路线 B 若被选择，发布前必须：

1. 从每个精确 gitlink clean-build，不能把 opaque prebuilt 目录作为唯一 source authority；
2. 保存所有 OpenTypeless/upstream patch，给修改文件加 prominent modification notice/date；
3. 发布应用、native libraries、GPL/LGPL plugins/data 的完整对应源码、license、copyright、build scripts、
   toolchain manifest 和必要对象/重链接材料；
4. 提供用户可执行的 rebuild、relink、re-sign、install instructions；不得用签名/安装机制阻止运行修改版；
5. 对 Android 同签名更新限制做一次真实、无私钥泄露的 modified-build 安装演练，并由法律负责人确认其满足
   选定 LGPL/GPL 版本的义务；
6. 若走 LGPL-only route，二进制和资产扫描必须证明 GPL octagram、GPL pinyin.lua 及未选 GPL plugins 均不存在；
7. 若走 GPL route，明确哪些 APK/模块构成 GPL distribution，并核对 Apache-2.0 等依赖与选定 GPL 版本兼容。

官方依据入口：

- [Apache License 2.0](https://www.apache.org/licenses/LICENSE-2.0)；
- [GNU LGPL 2.1](https://www.gnu.org/licenses/old-licenses/lgpl-2.1.en.html) 与
  [GNU GPL FAQ linking guidance](https://www.gnu.org/licenses/gpl-faq.en.html)；
- [GNU GPL 3.0](https://www.gnu.org/licenses/gpl-3.0.en.html)；
- [Android app signing](https://developer.android.com/studio/publish/app-signing)。

## Mandatory release package

无论选择哪条路线，release job 必须从最终 release APK/AAB 而不是 source declarations 生成并校验：

```text
SOURCE_COMMIT.txt
UPSTREAM_SOURCES.json
PATCH_PROVENANCE.json
OpenTypeless-Android-<version>.sbom.json
THIRD_PARTY_NOTICES
THIRD_PARTY_LICENSES
NATIVE_LINK_MANIFEST.json
MODEL_MANIFESTS/
SCHEMA_MANIFESTS/
ACCEPTANCE_REPORT.md
```

每个 bundled/linked component 至少记录 `name/version-or-commit/source URL/SPDX/selected dual-license branch/modified/
linked-or-bundled/NOTICE path/source availability/artifact paths/digests/data license/redistribution decision`。CI 必须
对 release variant 执行 source manifest ↔ APK entries ↔ ELF symbols/NEEDED ↔ notices/SBOM 的双向 drift check：
未列出的 payload 和列出但未打包的 runtime 都失败。

AboutLibraries/in-app licenses 是必要的用户入口，但不能替代 release 下载旁的完整 notice、source offer/materials
或机器可读 SBOM。所有二进制/数据的许可证文本都必须离线可达。

## Prohibited copy and distribution scope

在 KSP-010/法律负责人另行批准前，明确禁止：

- 从 HeliBoard、Trime、fcitx GPL plugins、`pinyin.lua` 或 librime-octagram 复制实现/资源后仅改包名、类名或格式；
- 把 route B debug APK、prebuilt static library 或 Rime plugin 标为 MIT、Apache-only 或 LGPL-only 发布；
- 在 LGPL/GPL binary 旁只放 GitHub URL/AboutLibraries，而不提供选定许可证要求的对应源码、修改和重链接材料；
- 静态链接 dual-license 组件但不记录实际选择的许可分支；
- 删除或重写 upstream copyright、SPDX、NOTICE、AUTHORS、翻译/图标/数据归属；
- 假定 root project license 自动覆盖 Schema、码表、词库、language model、emoji/CLDR、主题或图标；
- 把 prebuilder `master`、浮动 submodule、未校验 CI artifact 或本机缓存当作 release source authority；
- 在 KSP-012 前把真实小鹤资源放入产品、测试快照、release candidate 或 export bundle；
- 因许可证复杂而关闭 dependency verification、SBOM/license drift、hash 或 source provenance gate。

## Security and privacy

- 本任务未发送、存储或提交用户正文、音频、词典、Secret 或设备数据；所有检查对象为公开固定源码和 synthetic
  测试 artifact。
- 没有更改 Android 权限、component、dependency verification、package verifier、默认 IME、Feature Flag 或用户
  数据。
- 许可证清单本身不得记录用户安装路径、签名私钥、token 或真实输入内容；artifact 只记录公开 digest。

## Tests actually run

| Command/check | Result | Notes |
|---|---|---|
| APK `aboutlibraries.json` extraction and license grouping | PASS | Floris 137、fcitx main 118、Rime plugin 21 entries；按最终 KSP-002/KSP-006 artifacts 读取 |
| Floris Cargo metadata with exact Rust 1.83/Cargo.lock | PASS | 所有 registry crate 有许可字段；workspace crate 的 missing SPDX 被列为 fork 修复项 |
| Route A native build script/config audit | PASS | yaml/LevelDB/marisa/OpenCC/Boost static；librime logging/tests/data disabled，glog/GoogleTest 未进 runtime |
| fcitx main APK GPL payload source↔APK byte compare | PASS | `pinyin.lua` bytes/SHA 相同，source SPDX 为 GPL-2.0-or-later |
| Rime ELF symbol/string inspection + prebuilder source rule | PASS | octagram symbols/source paths存在；prebuilder 明确挂载 GPL plugin 后 static build |
| recursive source/prebuilt/data/license manifest comparison | PASS | 发现 packaged prebuilt version 与 App source gitlink不一致，已变成 release hard gate |
| official license/signing terms review | PASS | 只建立工程清单；最终解释/批准仍要求法律/负责人复核 |
| Route-A addendum exact patch replay | PASS | 89 files；10,214,294 bytes；tree `d99747a43f3c8dcc2a9c70de1f789cce6948af30` |
| source-first native build and APK mapping | PASS | 两 ABI librime/JNI 从固定 clean source 构建/strip；四哈希与 `jniLibs`/APK entries 相同；host-path/GPL marker 为零 |
| strict-offline candidate/replay Debug/AndroidTest + JVM | PASS | 两端 209 tasks；JVM 7/7；dependency verification 保持 strict |
| candidate ↔ replay artifact comparison | PASS | main 与 AndroidTest APK 均逐字节一致 |
| Xiaomi + arm64 emulator core/resource/restart | PASS | 两端安装成功；各 core 6/6、Latin resource 3/3、seed 1/1、fresh restart 1/1 |
| x86_64 latest integrated APK runtime/install | NOT RUN — historical KSP-007 probe, superseded by KSP-009 PASS | 本任务当时 ADB/ABI 可见但 package service 不存在；后续同一 final APK 已完成 x86_64 动态矩阵 |
| strict Release build | FAIL — historical KSP-007 probe, superseded by KSP-009 PASS | 本任务当时 109 tasks 后缺两个 POM 可信校验项；后续逐项官方认证并完成 candidate/fresh replay strict Release |
| whole candidate editor/privacy boundary | FAIL / NOT SELECTED — KSP-010 adjudication | 至少 32 个已审计六类 mutator 调用点（排除 2 个 `commitText` 方法声明），另有 selection writer surface 与 5 个 `InputConnection` 文件；再加 backup/profileable/exported/import/SEND/notification/query surface；restricted boundary 尚未构建，不能记 PASS |
| Android build/JVM/instrumentation | NOT RUN — not applicable | KSP-007 只分析 KSP-002..006 已生成的固定 artifacts，不改 runtime |
| Xiaomi 10 Ultra KSP-006 retry | NOT RUN — device absent from ADB | 当前仅 emulator 枚举；未把设备缺席写成测试通过 |
| current HEAD GitHub Actions | NOT RUN — no matching run | 当前 HEAD 无 KSP-007 CI run；本任务不伪造远端证据 |

## Risks

- 许可证兼容和“combined work”范围最终属于法律判断；本报告用最保守的 release gate，不能替代律师意见。
- 当前 Debug 候选已移除 `han.sqlite3` 和 `data.json`；KSP-009 后续 strict Release/x86 动态实物门已通过。
  正式发行仍必须通过 SEC/REL 的 notices/SBOM/source bundle 与 artifact/license drift gate。
- 本报告当时的 x86 package-service 缺口是历史探测结果，已由 KSP-009 同一 final APK 动态 PASS 取代。
- whole candidate App 的 editor/privacy surface 已被 KSP-010 明确拒绝；下一 KSP-009 safety follow-up 必须先产出
  writer-free/privacy-safe buildable artifact 并重跑 Debug/Release、arm64/x86 门，KBD-001 仍未授权。
- 真实小鹤码表/词库仍无分发授权；KSP-012 关闭前只能提供显式用户导入路径。
- route B prebuilder 没有 root license，不能直接复制其 Haskell实现作为 OpenTypeless 发布脚本；可要求上游澄清，
  或在 KSP-011 从已许可源码写独立、可审计的构建说明。
- Android app signing、store 分发和 LGPL replacement/relink 的组合必须实际演练并经法律接受，不能只写说明。
- KSP-006 route B patch 仍是仓库外 spike；其 Apache-2.0 OpenTypeless bridge 与 LGPL upstream 修改在正式 fork 中
  需要分别保留文件级版权/许可证和修改记录。

## Rollback

本任务只新增/更新证据文档。回滚可移除本报告和规范引用，并把 KSP-007 恢复为 TODO；产品 APK、依赖、权限、
默认 IME、配置和用户数据均无需回滚。两条第三方候选仍在仓库外隔离目录，可独立删除。

## Follow-ups

- KSP-008
- KSP-009
- KSP-010
- KSP-011
- KSP-012
- SEC-010
- REL-003
- REL-007

## Git

- branch: `agent/android-offline-followup`
- commit: `80d20496c4eb59e4f27281becfa8a32021212e53`
- worktree status: 共享工作树已有大量其他任务 tracked/untracked 变更；KSP-007 不 stage、commit 或 push，也不把
  第三方源码、APK、native binary、Cargo cache 或设备内容放入产品树。
