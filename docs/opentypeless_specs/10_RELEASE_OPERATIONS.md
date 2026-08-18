# OpenTypeless 发布、运维与长期维护规范

> 文档版本：1.0  
> 生成日期：2026-08-12  
> 适用仓库：`dengxuezhao/opentypeless`  
> 代码基线：`main@67be488dcd2e9f36520618f9f644f97c3ec02b98`  
> 目标读者：产品负责人、Android/IME 工程师、语音与模型工程师、安全工程师、测试工程师，以及 Codex / Claude Code 等编码代理  
> 状态：**拟议目标方案（Proposed Target Architecture）**；涉及键盘底座、许可证和 Rime 集成的不可逆决策，必须先完成 ADR 技术验证

## 1. 目标

发布体系必须保证：

- 产物对应明确 commit；
- 构建可复现；
- 签名可信；
- 升级不丢数据；
- Feature Flag 可回滚；
- 模型、Schema 和依赖来源可验证；
- CI 不能在安全校验缺失时“勉强成功”；
- 用户能了解隐私、权限和模型变化；
- 大型上游 fork 能持续同步。

---

## 2. 分支与 PR

### 2.1 分支

```text
main                  始终可构建、可测试
feature/<task-id>-... 单任务分支
fix/<issue>-...       缺陷
release/<version>     仅发布候选冻结
spike/<adr>-...       技术验证，不直接发布
```

不长期维护多个功能分支。Spike 代码只有在 ADR 接受且清理后进入主干。

### 2.2 PR 要求

PR 标题包含任务 ID：

```text
EDT-008: add guarded replace-selection transaction
```

PR 描述必须包含：

- 目标；
- 非目标；
- 架构/ADR；
- 变更文件；
- 测试；
- 风险；
- 隐私/网络影响；
- 数据迁移；
- 回滚；
- 截图或录屏；
- 后续任务。

### 2.3 合并

- Required checks 全绿；
- P0 至少一名人工审查；
- 安全/数据迁移需对应领域审查；
- 默认 squash merge，保留任务 ID；
- 禁止在 main 直接修复；
- 紧急修复也要补测试和事后 ADR/报告。

### 2.4 `main` 保护的可执行基线

- `dengxuezhao/opentypeless` 的 `main` 必须由 GitHub branch protection 实际保护；仓库内 JSON 只保存期望策略，
  不能替代 REST readback；
- strict required contexts 精确覆盖 Android build、四个 Android API device job、frontend、offline ASR、四平台
  Rust、audit、CodeQL、typos 与 PR title。新增/改名 job 时必须原子更新 workflow、策略、负向测试和远端规则；
- 保护对管理员生效，禁止直接 push、force push 和 delete，要求 PR、线性历史及解决对话。单协作者仓库的 approval
  count 为 0，避免永久自锁；增加第二个可审查维护者后应在独立任务提升到至少 1；
- `scripts/verify_github_branch_protection.py` 的离线模式验证仓库策略与 workflow topology；带
  `--repository dengxuezhao/opentypeless` 时必须通过认证 API 回读真实远端。远端读取失败不得按 PASS 处理。

---

## 3. 版本体系

分别版本化：

| 对象 | 版本 |
|---|---|
| Android App | SemVer |
| Desktop App | SemVer |
| Config | `format + version` |
| SQLite | schema integer |
| Action Protocol | `opentypeless.action.v1` |
| ASR Streaming Protocol | 独立版本 |
| Import Bundle | section version |
| Model Manifest | version |
| Rime Schema Bundle | version |
| Diagnostic Bundle | version |

App 版本升级不意味着所有协议同时升级。

### 3.1 变更日志与兼容 authority

- 根 `CHANGELOG.md` 是版本与兼容变更历史入口；未绑定 immutable tag/commit 的内容只能留在
  `Unreleased`，不得从 package version 或旧 Git tag 推断发布完成；
- `docs/COMPATIBILITY.md` 记录当前 Android、desktop、config、protocol、schema 的精确 read/write 边界与
  source authority。Android 与 desktop App 版本独立，只有矩阵明确列出的跨端格式可以互读；
- 每次 authority 常量、App version、持久格式、协议或 schema 改动必须在同一 task 更新实现、兼容矩阵、唯一
  changelog change ID 与迁移/contract tests。漏任一项由 `verify_compatibility.py` 在 Gradle 前 fail closed；
- `legacy-unversioned` 是已知风险而非兼容承诺。首次引入显式版本同样属于格式变更；涉及不可逆数据时必须先有
  Accepted ADR、forward migration、失败/中断/磁盘不足 fixture、downgrade 说明和 rollback 边界；
- spec-only 与外部 unversioned 协议不得被赋予伪造 runtime 版本。生产实现出现时必须新增 producer/consumer
  contract、兼容窗口与发布记录，再修改矩阵状态。

---

## 4. Feature Flag

### 4.1 Flag 分类

- build-time；
- developer；
- user-visible experimental；
- migration；
- emergency rollback。

### 4.2 Flag 记录

每个 Flag：

```text
id
owner
introduced version
default debug
default release
data migration dependency
rollback behavior
removal condition
target removal version
```

### 4.3 规则

- 不用 Flag 永久维持两套完整产品；
- 安全硬规则没有关闭 Flag；
- 新旧路径不能同时提交文本；
- Flag 切换后清理未完成 Session；
- Flag 状态进入脱敏诊断；
- 删除 Flag 时删除死代码和测试。

### 4.4 当前 Voice rollback Flag（VOC-011）

| 字段 | 值 |
|---|---|
| id | `voice_engine_v2` |
| owner | Voice editor delivery / `VoiceEditorTransactionConfig` |
| introduced version | 当前 `Unreleased` |
| default debug | `true`（transaction route） |
| default release | `true`（EDT-017 已验证的 production route） |
| data migration dependency | 同一 store 的旧 `enabled` boolean 原值同步迁移；canonical 优先 |
| rollback behavior | `setEnabled(false)` 同步 commit；只影响下一次 voice capture，当前 session 不切换、不双写 |
| removal condition | VOC-012 删除 legacy writer 且 REL-004 发布/回滚清单给出稳定证据后另行决策 |
| target removal version | 由 REL-004 决定，当前不得猜测 |

迁移或 rollback 持久失败不得报告成功；安全硬规则不受 Flag 影响。Debug/生产使用同一键与同一同步语义，
但生产发布记录必须包含 Flag 值、回滚步骤和回滚后下一 session 的验证结果，不得在 active session 中强制换路。

---

## 5. CI 结构

### 每个 PR

```text
dependency verification
format/static analysis
JVM unit
architecture rules
provider/action contracts
migration tests
Android lint
debug/release assemble
AndroidTest assemble
API 35 instrumentation
frontend/rust existing checks
license/SBOM drift
```

### 定期

```text
API matrix
fuzz
macrobenchmark
ASR benchmark
Rime golden
native sanitizer where possible
upstream drift check
dependency security audit
```

### Release

```text
all PR checks
old-version upgrade matrix
real-device evidence
Xiaomi 15 full matrix
signed release
apksigner verify
SHA-256
SBOM
license bundle
model/schema manifests
acceptance report
```

### 5.1 Android SDK package pinning

- Android compile/target authority 固定为 Platform 35，Build Tools 固定为 35.0.0；App 与 Test Host
  必须一致；
- `check-android` 不使用 runner 预装 Platform/Build Tools，必须先执行
  `sdkmanager --install "platforms;android-35" "build-tools;35.0.0"` 并检查安装目录；
- emulator matrix 固定 API 26/33/35/36、`google_apis`、`x86_64`，每个 job 必须先安装并回读精确
  `system-images;android-<api>;google_apis;x86_64` package path，再启动 runner；
- 本地 `scripts/verify_android.sh` 必须先运行 BLD-002 fail-closed verifier 与 fault-injection suite，CI
  workflow、Gradle SDK 声明或本地门禁任一漂移都在 Gradle 前失败；
- SDK-style package path 固定用于消除 runner preinstall drift；它不等同于对 Google repository 内 package
  revision 做内容寻址。不得把 package path 声称为 artifact hash，也不得为“复现”而绕过 HTTPS、license
  或 dependency verification；
- Action commit 更新、日志/报告 job 拆分分别属于 BLD-003/BLD-004，不在 BLD-002 夹带。

### 5.2 GitHub Actions pinning 与最小权限

- 所有远程 `uses:` 必须固定到经官方 repository tag 解析的 40 位 commit；`@vN`、branch、`latest` 与未知
  action 一律 fail closed。行尾版本只作可读 provenance，也必须与 allowlist 一致；
- checkout 必须 `persist-credentials: false`。`pull_request_target` workflow 不得 checkout 或执行 PR
  提交内容；语义标题、welcome、release drafter 只读取事件 metadata；
- 每个 workflow 必须有显式 root `permissions`。默认只读；issue/PR automation 只开放对应 write，CodeQL
  只开放 `security-events: write` 与 `contents: read`，发布写入继续使用范围明确的 release Secret；
- 禁止 `write-all`、`read-all`、`id-token: write`、`actions: write`；需要新写权限时必须单任务审查并补
  fault-injection；
- 本地 verify 在 Gradle 前执行全 workflow action allowlist/permissions/credential gate。Action 更新与 SHA
  变更必须同时更新版本来源证据和测试，不能只改注释或把 immutable commit 降为 major tag；
- CI job/report 拆分与下载报告属于 BLD-004，不在 action 升级时重排执行拓扑。

### 5.3 Android CI 阶段与报告保留

- `scripts/verify_android.sh` 的无参数入口是本地与 CI 的 canonical full verify；`preflight`、`unit`、`lint`、
  `assemble`、`instrumentation` 只负责把同一套严格命令映射到可定位的 CI step，不得复制或弱化
  `--dependency-verification=strict`；
- Unit/Architecture、Lint、Assemble 与每个 emulator API 的 Instrumentation 必须是独立命名 step。
  Preflight 或前序阶段失败不得通过 `continue-on-error` 降级；
- Unit 的 JUnit XML/HTML、Lint 的 HTML/XML/SARIF、Instrumentation 的 UTP results/HTML 必须作为可下载
  artifact。报告上传使用 `always()` 与 `if-no-files-found: warn`，从而保留失败证据而不覆盖原始失败；
- APK artifact 覆盖 App/Test Host 的 debug、androidTest 及 unsigned release，缺失时必须报错。所有 artifact
  保留期固定 14 天，设备报告名必须包含 matrix API，防止覆盖；
- 本地 fail-closed verifier 同时锁定 stage 顺序、同一脚本入口、报告路径、matrix 唯一命名与缺失策略。
  远端 workflow 未实际运行时必须记录 `NOT RUN`，不得以 YAML parse、本地构建或 artifact glob 命中代替。

### 5.4 当前候选验收报告

- 每份当前基线报告必须绑定不可变 commit；如果工作树尚未形成 commit，则额外绑定覆盖所有非 ignored 候选文件的
  deterministic content SHA-256，并显式说明其不是可发布 commit。报告自身必须排除在 digest 之外，避免自引用；
- 只记录实际执行的命令、最终退出状态、测试完成数与产物 SHA-256。`assembleDebugAndroidTest`、runner started、
  UTP 目录生成或 artifact glob 命中都不等于 device PASS；
- 本地、GitHub-hosted、emulator、Test Host 和指定真机结果分别列出 **PASS / FAIL / NOT RUN**。未推送时必须
  查询当前 HEAD 的 Actions run 并记录 NOT RUN；设备失败不得被另一设备或本地 JVM 结果覆盖；
- 报告不得保存 API key、正文、完整 device serial 或其他不必要标识；可以使用 SHA-256 correlation value；
- dirty worktree、远端 required checks 未绿、真机矩阵失败/未运行、unsigned release 或必需 Backlog 未完成时，
  release decision 只能是 **CONDITIONAL** 或 **FAIL**。报告生成任务可完成，但不能把报告 `DONE` 写成 App
  release-ready。

### 5.5 Release Tag 来源门禁

- Release 与 Windows SignPath 的输入必须是已存在、形状受限的 `v*` tag；所有构建 job 必须 checkout 该 tag，
  workflow dispatch 不得以输入版本字符串给当前任意 branch 改名发布；
- 发布前 fetch `origin/main` 完整 history，并用 Git ancestry 证明 tag commit 是受保护 main 的祖先。tag 不存在、
  字符串越界、main ref 不可用、Git 无法证明或 tag 指向 side branch 时都在读取 Secret/构建/签名前 fail closed；
- ancestry 证明与远端保护读回是两个独立门：前者由 `verify_release_source.py` 在 release runner 执行，后者由
  管理员凭证运行 branch-protection verifier。不得把“commit 恰好在 main 历史”冒充“main 当前仍受保护”；
- Release workflow 本身必须来自受保护 main。新门禁尚未推送或远端 run 未完成时只可记录 NOT RUN。

### 5.6 工程指标趋势

- CI 在 Android Assemble 后调用 canonical `scripts/verify_android.sh metrics`，生成 schema-versioned JSON 并
  独立上传；缺输出或 JSON 生成失败属于 pipeline failure，普通数值变化不属于机械失败；
- 基线只记录选定关键类的 source size/method proxy、测试 XML/source declarations 与精确 APK bytes/SHA。指标
  定义必须随 artifact 一起版本化，不能在不更新基线说明时静默改变算法；
- complexity 是 review signal，不是质量分数。热点增长要求评审解释或后续 task ID，但不得以任意阈值驱动跨
  task 重写、删除测试或拆散安全事务；
- build/test artifacts 不存在时记录 unavailable；不得复用旧 APK、把 source declaration 冒充 executed test，
  或把 unsigned release 大小报告成可分发产物。

---

## 6. 签名

### Android

- release keystore 由维护者控制；
- CI 使用 Secret 管理；
- 日志不输出路径密码；
- 无签名 Secret 时发布 job fail closed；
- debug APK 明确标记；
- release APK/AAB 执行 `apksigner verify --verbose --print-certs`；
- 保存证书摘要；
- 签名轮换有独立计划；
- Play/App Store 与独立 APK 的签名策略记录。

### 桌面

延续各平台签名/notarization；协议和 Bundle 兼容测试纳入同一 Tag。

---

## 7. 产物

每次正式发布：

```text
OpenTypeless-Android-<version>.apk
OpenTypeless-Android-<version>.apk.sha256
OpenTypeless-Android-<version>.sbom.json
THIRD_PARTY_NOTICES
MODEL_MANIFESTS
SCHEMA_MANIFESTS
ACCEPTANCE_REPORT.md
SOURCE_COMMIT.txt
```

未签名产物不能命名为正式 Release。

### 7.1 键盘底座许可证产物门

KSP-007 已证明键盘底座不能只按 root project license 分类。正式 release job 必须从最终 APK/AAB/ELF/assets
生成并双向核对：

```text
UPSTREAM_SOURCES.json
PATCH_PROVENANCE.json
NATIVE_LINK_MANIFEST.json
THIRD_PARTY_NOTICES
THIRD_PARTY_LICENSES
SBOM
MODEL_MANIFESTS
SCHEMA_MANIFESTS
```

- 路线 A 必须保留 Floris/JetPref Apache 许可、librime/静态依赖 BSD/MIT/BSL/Apache notice、ICU/Unicode/CLDR
  全文和每个 bundled language resource 来源；`han.sqlite3` 未补来源前必须从 release variant 删除；
- 路线 B 当前 artifact 含 GPL-2.0-or-later Lua 和 GPL-3.0-only octagram，禁止标为 LGPL-only。只有负责人接受
  GPL/LGPL 分发并提供完整对应源码/修改/重链接材料，或删除 GPL payload 后 clean rebuild 和二进制扫描通过，
  才可进入 release candidate；
- AboutLibraries 是离线 UI 入口，不替代完整 source、license bundle、SBOM 或重链接材料；
- dual-license 组件必须记录实际选择分支；source manifest、APK entries、ELF symbols/NEEDED 和 notices 任一漂移
  都 fail closed。

工程清单见 [KSP-007 合规分析](../2026-08-14-ksp-007-license-compliance-analysis.md)；最终许可证解释仍需法律/负责人
复核。

---

## 8. 数据迁移

### 8.1 原则

- forward-only；
- 幂等；
- 事务；
- 迁移前验证；
- 迁移后验证；
- 失败保留旧数据；
- 大迁移 staging/影子表；
- 测试磁盘不足和中断；
- 不在主线程迁移大量数据；
- 显示进度；
- 敏感明文迁移后 checkpoint/truncate WAL。

### 8.2 回滚

App 二进制可以回滚，但数据库未必可降级。发布前定义：

- Feature Flag 回滚；
- 数据格式向后兼容窗口；
- 旧 App 是否能打开新 DB；
- 不可降级时的用户提示；
- 加密导出恢复；
- 紧急修复版本。

---

## 9. 模型与 Schema 发布

### 9.1 模型

每个模型：

- ID；
- revision；
- runtime；
- ABI；
- 文件大小；
- SHA-256；
- 来源；
- 许可证；
- 基准；
- 峰值内存；
- 适用 DeviceTier；
- 回滚版本。

### 9.2 Rime Schema

- 来源和许可证；
- 文件 manifest；
- 总大小；
- 允许路径；
- 兼容 librime 版本；
- 部署测试；
- Golden 输入；
- 用户数据迁移；
- 回滚。

小鹤资源未经许可不得随 APK 发布，可提供用户导入流程。

### 9.3 KSP-012 小鹤资源发布门

ADR-0012 将上一句收紧为可机器验收的 zero-bundle 合同：完整小鹤音形资源和官方/第三方 GPL 小鹤双拼 Schema/
依赖正文，在 repo/history、Debug/Release/androidTest、APK/AAB、patch/preimage、snapshot/Golden、export/backup/
transfer/migration fixture 和 CI artifact/cache 中均必须为 `0`。Nightly、内部 Debug、测试 APK、加密/压缩/改名或
可逆删除不构成例外。发布物可以包含来源 URL、固定 commit/tree/blob、许可元数据与不可反推载荷的 hash。

未来用户导入只走本地显式 picker 和 closed `opentypeless.rime-resource-manifest` v1；不得 auto download/update、
re-export、backup、sync 或记录资源正文。manifest 自报 license 不构成供应商权利证明，未受信包固定为
`USER_PROVIDED_UNVERIFIED` / `LOCAL_ONLY`。`RIM-003` 实现前该 v1 只是 contract-only，不是 runtime authority。

Release inventory 必须把真实资源与 `SYNTHETIC_TEST_ONLY` fixture 分开：后者须为 OpenTypeless 自造且不含任何
真实小鹤名称/布局/码表/词库/候选。未来随包真实载荷必须先有 superseding Accepted ADR，加权分数、可下载性、
自报 SPDX 或用户同意不能替代权利人书面授权或完整 GPL 分发义务。

---

## 10. 发布渠道

建议：

- Debug/CI artifact；
- Nightly；
- Beta；
- Stable。

每个渠道使用不同：

- update feed；
- Feature Flag 默认；
- 崩溃/诊断策略；
- 模型推荐；
- 用户说明。

不把 Nightly 自动覆盖 Stable。

---

## 11. 更新机制

- 不继承上游商业自动更新路径；
- Android 通过可信商店或签名 APK；
- 独立更新必须验证签名和 hash；
- 更新说明包含权限、网络、模型、数据格式变化；
- 模型更新与 App 更新分离；
- 不在后台静默下载数百 MB 模型；
- Wi-Fi/充电策略可配置；
- 更新失败保留旧模型。

---

## 12. 观测与隐私

默认无强制遥测。可选诊断：

- 明确 opt-in；
- 只上传聚合/脱敏技术数据；
- 不上传正文、音频、词典、剪贴板；
- 显示数据结构；
- 可关闭和清除；
- 自托管 endpoint 作为未来选项；
- 不用遥测作为核心功能前置。

本地指标足以支持：

- 首字延迟；
- Final；
- error class；
- route；
- PSS；
- Action 状态；
- Crash/ANR 手工导出。

---

## 13. 上游维护

若采用 Floris/fcitx 大型底座：

1. 固定 upstream commit；
2. 保留 upstream remote；
3. OpenTypeless 业务尽量在独立模块；
4. 避免修改上游核心文件；
5. 记录必要 patch；
6. 定期查看安全和重要修复；
7. 同步在专用分支；
8. 跑完整回归；
9. 更新 NOTICE；
10. 在正式采用前演练一次真实同步。

---

## 14. 支持与缺陷响应

缺陷报告模板：

```text
App version/commit
Device/OS/ROM
Input app and field type
Keyboard engine
Voice route/provider
Feature flags
Steps
Expected
Actual
Can reproduce?
Diagnostic bundle
Privacy-sensitive attachments?
```

对 P0：

- 关闭相关 Feature Flag；
- 停止发布；
- 提供不丢数据的缓解；
- 修复后补竞态/安全回归；
- 发布事后报告，不包含用户敏感数据。

---

## 15. Release Checklist

### 代码

- [ ] main 绿灯
- [ ] 无未提交变更
- [ ] Tag 指向审查 commit
- [ ] Feature Flag 清单
- [ ] 无 P0/P1
- [ ] 架构边界检查

### 测试

- [ ] Unit/Contract
- [ ] Instrumentation
- [ ] Migration
- [ ] Rime golden
- [ ] ASR benchmark
- [ ] Action fuzz
- [ ] Performance
- [ ] Accessibility
- [ ] Xiaomi 15
- [ ] 其他 OEM smoke

### 安全

- [ ] Secret 扫描
- [ ] dependency verification
- [ ] SBOM
- [ ] license
- [ ] model hash
- [ ] schema hash
- [ ] diagnostics redaction
- [ ] sensitive field
- [ ] Action capability

### 产物

- [ ] 正式签名
- [ ] apksigner 验证
- [ ] SHA-256
- [ ] Acceptance Report
- [ ] Release Notes
- [ ] Known limitations
- [ ] Upgrade instructions
- [ ] Rollback plan

---

## 16. 长期维护原则

- 不以功能数量替代可靠性；
- 不让上游 fork 差异无限增长；
- 不保留永久兼容层；
- 不在输入法热路径堆积管理逻辑；
- 不让协议无版本演化；
- 不用真实用户数据做公开回归；
- 不把“AI 生成代码”当作免审查理由；
- 每个重大能力都有 Owner、测试和退出策略。
