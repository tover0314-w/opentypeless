# ADR-0012: Xiaohè resource distribution and local-import boundary

## Status

Accepted

## Background

OpenTypeless 的产品目标包含小鹤输入体验，但“小鹤”至少指向三个权利、来源和技术形态不同的对象，不能把其中
一个的可获得性推导为另外两个的可分发权：

1. **小鹤双拼**是声母/韵母到按键的双拼布局；Flypy 官方首页把它作为可在现有拼音输入法中选择的双拼方案。
2. **Rime 官方小鹤双拼 Schema**是 Rime 团队维护的
   `double_pinyin_flypy.schema.yaml`，其说明是“朙月拼音 + 小鹤双拼”。它实现双拼布局，不是完整小鹤音形。
3. **完整小鹤音形资源**包含双拼加双形、四码定长所需的形码表、词库、规则、文档、图片或可由这些材料重建的
   数据。Flypy 官方首页明确把“小鹤音形”描述为与“小鹤双拼”不同的音形方案。

Flypy 官方 [首页](https://www.flypy.cc/)、[关于页](https://www.flypy.cc/about/)、
[下载页](https://www.flypy.cc/download/) 与 [公开站点地图](https://www.flypy.cc/sitemap.xml) 表明方案及相关软件由
何海峰维护，并提供官方应用/网盘下载和联系邮箱。2026-08-16 对这些公开官方页面及其公开帮助入口的审阅，
**未发现**授予 OpenTypeless 复制、转换为 Rime、修改、随 APK/AAB/补丁/快照分发或允许下游再分发完整小鹤
音形资源的明确授权。这个结论只描述本次公开页面审阅范围，不证明不存在私下协议或其他权利基础，也不是法律
意见。禁止为了补齐证据而抓取官方查询页、下载网盘载荷、OCR 图片或从查询结果重建码表。

Rime 官方仓库提供了可固定审计的不同对象：

| 组件 | 固定官方身份 | 许可/角色 |
|---|---|---|
| `rime-double-pinyin` | commit [`01a13287cbd27819be1c34fa1ddc1b3643d5001b`](https://github.com/rime/rime-double-pinyin/commit/01a13287cbd27819be1c34fa1ddc1b3643d5001b), tree `a1c64a175f1d4f79938fa6da560a633933be7c2d` | 仓库 LICENSE 与 GitHub SPDX detection 为 GPL-3.0；不是完整小鹤音形。 |
| `double_pinyin_flypy.schema.yaml` | [固定 blob 页面](https://github.com/rime/rime-double-pinyin/blob/01a13287cbd27819be1c34fa1ddc1b3643d5001b/double_pinyin_flypy.schema.yaml), blob `4c78a06b5df625c82904ec2a6b07e161c79cf44a`, 3,125 bytes | 文件标注布局为鹤、Rime 方案为佛振；直接引用 `luna_pinyin`、`stroke` 和 `default` preset。 |
| `rime-luna-pinyin` | [commit `56b934b099dfbeab842320f13aa8b461a6ab3e42`](https://github.com/rime/rime-luna-pinyin/commit/56b934b099dfbeab842320f13aa8b461a6ab3e42), tree `c0a87ac7e0e6408c9441ba7b6533f3e376c1c627` | GitHub 检测 LGPL-3.0；`luna_pinyin.dict.yaml` blob `214f15cd831c7cf58a3c51cf56983e0093102a12`, 889,896 bytes。 |
| `rime-stroke` | [commit `3a4b0f4013e2b4c14b1e80c92b1d4723eb65f39c`](https://github.com/rime/rime-stroke/commit/3a4b0f4013e2b4c14b1e80c92b1d4723eb65f39c), tree `d60c793d8d68154847923f21aa73ba90441dab32` | GitHub 检测 LGPL-3.0；`stroke.dict.yaml` blob `dc7f67e4fef2718094aa143f6627eab588400ba5`, 3,396,347 bytes。 |
| `rime-prelude` | [commit `082425ea0684bca36474415d4a0e8db9b016487e`](https://github.com/rime/rime-prelude/commit/082425ea0684bca36474415d4a0e8db9b016487e), tree `d7e128f09ce6b1f920729ef2f848ca1294c9cb31` | GitHub 检测 LGPL-3.0；`default.yaml` blob `d48d44d9c3b80908b8b483c1fe4ed4863b05f012`, 1,593 bytes。 |
| `rime-essay` | [commit `e9b1a374a6ea015fca5bdd04318924b4483ac35a`](https://github.com/rime/rime-essay/commit/e9b1a374a6ea015fca5bdd04318924b4483ac35a), tree `7637d01138323d4e3527e52bbef5c3d614073961` | GitHub 检测 LGPL-3.0；只作为未来依赖闭包输入，当前没有证据把它写成上述 Schema 的直接必需依赖。 |

`rime-double-pinyin` 的 LICENSE blob 为 `94a9ed024d3859793618152ea559a168bbcbb5e2`（35,147 bytes）。
本 ADR 记录 GitHub 对固定仓库的 `GPL-3.0` 检测和 exact LICENSE；不在缺少进一步法律审阅时自行把其解释扩张为
其他 SPDX 表达式。Rime 官方 [Plum](https://github.com/rime/plum) 文档说明一个 Schema 包可以包含 schema、
dictionary 与其他数据，各 package 可以有不同许可证，组合分发必须审查完整依赖闭包；因此只看顶层 YAML 或
用户自填的 license 字符串不构成可分发证明。

ADR-0011 已接受 Route A，并明确拒绝把当前路线 B 的 GPL 载荷作为主产品。若 KSP-012 不先冻结资源边界，真实
资源可能通过 Debug、androidTest fixture、补丁 preimage、快照、导出或 CI cache 绕过 Release APK 审查进入产品
供应链。该风险属于许可证、默认网络行为、持久格式和隐私共同边界，必须在 RIM-003/008/011 实现前作出
Accepted 决策。

## Decision

### Zero-bundle 主产品规则

OpenTypeless Route A 对以下真实载荷采用 **zero bundle**：完整小鹤音形资源，以及官方/第三方 Rime 小鹤双拼
GPL Schema 和它的词典/preset/依赖闭包。它们在仓库源码与 Git 历史候选、Debug、Release、androidTest、APK、
AAB、补丁及其可逆 preimage、快照、Golden、导出、备份、迁移 fixture、CI artifact/cache 中的允许数量均为
**0**。文件改名、压缩、编码、加密、切片、生成代码、哈希旁带原文或从真实资源机械转换，不能改变该结论。

仓库可保留本 ADR 中的 URL、commit/tree/blob、文件大小、许可元数据和不可反推载荷的哈希。不得复制、下载或
提交资源正文。Flypy 官方公开页面本身也不作为产品资源镜像。

### 仅限用户显式本地导入

未来 `RIM-003` 可以实现本地用户导入，但必须满足以下合同；KSP-012 本身不实现导入器：

- 只能由用户通过系统文件选择器显式选择本地包；应用不得自动下载、推荐下载、跟随重定向、轮询更新、静默
  修复、从剪贴板/浏览器/网盘抓取，或根据查询接口重建资源；
- 导入前展示来源、作者/权利人、license 声明、文件数/总大小、所选 Schema、依赖和本地使用范围，并取得一次
  明确确认；取消、未知版本、未知字段、缺文件、额外文件、哈希不符或依赖不闭合都 fail closed；
- 用户或包作者自填 `licenseExpression`、`author` 或“我有权使用”不能自证真实性，也不能授权 OpenTypeless
  再分发。未由未来独立 trust policy 验证的导入一律标为 `USER_PROVIDED_UNVERIFIED` 和 `LOCAL_ONLY`；
- 真实导入内容只进入 app-private local storage，不能进入诊断、日志、崩溃附件、分析、导出、备份、设备迁移、
  同步、测试快照或 CI。产品文案只能说“用户导入的小鹤资源”，不得暗示 Flypy/Rime 官方认可 OpenTypeless；
- 不提供自动导出/再打包/分享，也不把用户导入的 Schema 与 Rime UserDB 混为一个备份对象；卸载/清除的行为
  必须在 `RIM-003` 实现前明确。

### Versioned manifest 合同

未来导入包必须具有 closed-world manifest：`format = "opentypeless.rime-resource-manifest"`、`version = 1`。
这是 KSP-012 冻结的兼容合同，不是当前 runtime authority。顶层至少包含：

- `packageId`、`packageVersion`、`displayName`；
- `sourceUrl`、`sourceRevision`、`author`、`rightsholder`；
- `licenseExpression`、`licenseTextPath`、`noticePaths`、`usageBasis`；
- `trustState`、`distributionScope`，首版只接受
  `USER_PROVIDED_UNVERIFIED` / `LOCAL_ONLY`；
- `compatibleLibrime`、`selectedSchemas`；
- `files[]` 的 `path`、`size`、`sha256`、`role`；
- `dependencies[]` 的包身份、固定 revision、license 和文件闭包。

`RIM-003` 必须为字段长度、数组数量、文件数量、单文件/总大小、YAML 深度/alias、解压比和部署时间建立硬上限；
拒绝 absolute/traversal/backslash/control/bidi/NFC/case collision、symlink/hardlink/special file、重复路径/键、未清单
文件、Lua/native binary/script/可执行文件和任何网络引用。校验、staging、librime dry deploy 与原子切换失败时保持
旧方案可用并清理临时目录。manifest 中的声明只用于审计和用户提示，不把 `USER_PROVIDED_UNVERIFIED` 提升为
受信或可分发。

### Synthetic evidence 例外

OpenTypeless 自行创作的最小合成测试 Schema 可以进入测试/证据边界，前提是它不含真实小鹤名称、键位、形码、
词条、候选、文档或可反推数据，仅提供例如自造 key 到“甲/乙”两个候选的 deterministic contract fixture。它必须
标记 `SYNTHETIC_TEST_ONLY`，不能在产品 UI 中冒充小鹤兼容性，也不能把合成测试 PASS 写成真实小鹤验收。

`RIM-008` 的真实验收只能在合法可分发资源获得新的随包授权后进行，或在用户已本地导入的设备上运行；共享
证据只保存测试 ID、状态、计数、版本和不可反推载荷的哈希，不保存真实输入、候选、码表或词库正文。

### 后续随包的唯一变更路径

任何渠道未来要随包真实资源或 GPL Schema，必须先建立 **superseding Accepted ADR**，并满足以下二选一：

1. 取得权利人的明确书面授权，覆盖复制、格式转换、修改、全球/App Store 分发、下游再分发，以及资源各数据
   来源；或
2. 由产品负责人明确接受 GPL 完整分发义务，完成依赖闭包、对应源码/修改源码、构建材料、许可证/NOTICE、
   SBOM、安装包/商店条款兼容性和专业法律审阅。

单一仓库 SPDX 标签、用户自报 license、网上可下载、已有输入法可选该布局、只发 Debug/测试包、只分发 YAML
不分发词典，均不能替代上述门槛。

### 非本 ADR 范围

KSP-012 只冻结来源/分发决策、versioned manifest 和安全导入合同。它不实现 `RIM-003` staging/deploy、
`RIM-008` 真实小鹤测试包或 `RIM-011` 导入导出，不引入 librime/Schema/词典，不修改运行时格式、数据库、权限、
网络、备份、Feature Flag 或 UI。`RIM-*`、`KBD-*`、`SEC-*`、`TST-*`、`REL-*` 状态不因本 ADR 自动完成。

## Consequences

正面结果：Route A 保持不接受当前路线 B GPL 载荷的产品决策；同一零随包规则覆盖容易漏审的测试、补丁、快照、
导出和 CI 面；用户仍有未来显式本地使用自己资源的可行路径；合成 fixture 可以在不复制真实资源的情况下验证
librime/候选/重启合同。

代价：OpenTypeless 不能开箱提供完整小鹤音形，也不能用官方 Rime GPL Schema 作为真实随包测试数据；真实体验
验收依赖用户本地导入或后续权利/许可证决策。manifest 与安全 staging 会增加 `RIM-003` 实现和测试成本。

残余不确定性：双拼布局思想、具体映射表达、音形码表、词库选择/编排及软件实现可能具有不同保护边界；中国
[《计算机软件保护条例》](https://www.cac.gov.cn/2013-02/08/c_12648744.htm) 对思想/处理过程与软件表达作区分，
但这不能由工程团队推导出具体资源可复制。若未来考虑 clean-room 自建兼容布局，必须另做专业权利分析和新的
ADR，不能从本 ADR 的 zero-bundle 决策反推许可。

长期义务：每次资源、Schema、依赖或 trust policy 变化都必须重跑 source/license/closure 与 artifact inventory；
诊断、备份、迁移、导出、CI 和商店产物必须持续证明真实资源数量为零，直到 superseding ADR 被接受。

## Validation

接受前完成的只读证据：

- 2026-08-16 审阅 Flypy 官方首页/about/download/sitemap 及公开帮助入口，结果为“在该公开范围未发现明确再分发
  授权”；没有下载、复制、OCR 或重建资源载荷；
- 核对 Rime 官方 `rime-double-pinyin` 固定 commit/tree/blob/许可证与 Schema 直接引用，并固定检查
  `rime-luna-pinyin`、`rime-stroke`、`rime-prelude` 及可选 closure 输入 `rime-essay`；
- fail-closed verifier 对工作树及 trusted patch queue 扫描 **1,061 enumerated / 1,403 inspected**，递归检查
  3 个容器/166 个成员；只允许 3 个 exact `SYNTHETIC_TEST_ONLY` fixture 与 4 个 exact native engine，真实小鹤、
  forbidden resource、violation 均为 `0`；KSP-011 固定 replay 为 972/1,005、3 synthetic、0 real/forbidden/violation；
- exact APK policy 扫描六个 product APK（279 members / 14 exact native）、两个 AndroidTest APK（38 members）和
  三个 Route-A safety evidence APK（73 members / 6 synthetic occurrences / 8 exact librime/JNI）；三个 profile 的
  真实小鹤、forbidden resource、violation 均为 `0`，任何未登记 APK hash/native/asset fail closed；
- hostile contract tests **36/36 PASS**，全 scripts suite **119/119 PASS**；覆盖分片 byte array、真实 7z/zstd、
  unknown opaque binary 与 app/test-host post-build gate；最终 pinned-JDK/SDK preflight 同时通过
  6 个 Android-script、115 个 architecture 和 10 个 mobile-voice tests，且 Sherpa AAR 验证通过。

Gradle、Android build、设备、完整 Git history、AAB、export/backup 与 CI-cache 扫描均 **NOT RUN — 不在
KSP-012 已执行证据范围内**。pre/post-build gate 已静态测试，现有 APK 只读扫描通过；不得把这些结果外推为
未扫描 surface 或真实小鹤 runtime PASS。本 ADR 的
Accepted 状态授权后续按 fail-closed 合同实现，不宣称 `RIM-003/008/011`、真实小鹤运行或发布验证完成。

持续门禁的精确验收为：

1. zero-bundle scanner 对 repo/history、所有 variant/APK/AAB/androidTest、patch preimage、snapshot、export、
   backup 和 CI artifact 返回真实资源/GPL Schema count `0`；
2. synthetic fixture provenance 为 OpenTypeless、自造、`SYNTHETIC_TEST_ONLY`，real-resource signature count `0`；
3. 未来 importer 对 unknown version/key、额外/缺失/篡改文件、hash/size/dependency 失败、路径/YAML/archive bomb、
   link/special/native/Lua/script/network ref 全部拒绝，失败后旧 Schema 仍可用；
4. 未受信包始终显示 `USER_PROVIDED_UNVERIFIED` / `LOCAL_ONLY`，不会被自报 license 提升；
5. 真实导入包不进入 log/diagnostic/export/backup/transfer/snapshot/CI，网络监控证明无 auto download/update；
6. 若出现随包真实资源，构建必须 fail，除非存在 superseding Accepted ADR 和其要求的授权/GPL 完整合规证据。

## Rollback

本任务没有运行时或数据迁移可回滚。若撤销文档，只能把 KSP-012 恢复为 TODO，并继续以更严格的“不得随包、
不得实现真实导入”状态阻断 RIM 资源工作。放宽 zero-bundle 或提升 trust/distribution scope 不能直接改写本 ADR；
必须用新的 Accepted ADR 将它标为 Superseded。删除本 ADR 不会赋予任何资源权利。

## References

- Task：`KSP-012`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`、
  `05_DATA_PERSONALIZATION.md`、`06_SECURITY_PRIVACY.md`、`07_IMPLEMENTATION_BACKLOG.md`、
  `08_TEST_VALIDATION.md`、`09_ADR_RESEARCH.md`、`10_RELEASE_OPERATIONS.md`
- 关联 ADR：[ADR-0011](0011-keyboard-base-evaluation.md)
- Rime sources：[rime-double-pinyin](https://github.com/rime/rime-double-pinyin)、
  [rime-luna-pinyin](https://github.com/rime/rime-luna-pinyin)、
  [rime-stroke](https://github.com/rime/rime-stroke)、[rime-prelude](https://github.com/rime/rime-prelude)、
  [rime-essay](https://github.com/rime/rime-essay)、[Plum](https://github.com/rime/plum)
