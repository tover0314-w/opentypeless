# ADR-0001: Provider 配置与 SecretRef 边界

## Status

Accepted

## Background

`CFG-001` 要为 ASR、LLM 和 Connector 定义可复用的非密钥 Provider 配置，并把认证材料降为 opaque
`SecretRef`。现有 `AppSettings` 仍同时承载 URL、模型和明文 API Key；它属于后续 `CFG-006` 迁移与
`CFG-008` Secret Store 的范围，本任务不能通过改写现有持久化或运行时调用链来掩盖边界缺失。

如果领域模型接受任意 URL、把 Secret 字符串直接放入 Provider 配置，或把 ID、显示名与网络端点都表示成无界
字符串，后续 Recognition、Action、配置继承、导入导出和诊断会复制同一风险。Secret 边界与默认网络行为属于
实施前必须冻结的安全决策，因此由本 ADR 记录。

## Decision

1. 在纯 Java `com.opentypeless.android.config` 包中定义 sealed `ProviderConfig`，且唯一实现为
   `Asr`、`Llm` 与 `Connector` 三个不可变 record。共同字段只有稳定 ID、显示名、可选 Endpoint、可选
   `SecretRef` 与启用状态；ASR/LLM 另有可选 model ID。模型不依赖 Android UI、网络客户端、持久化或
   `AppSettings`。
2. Provider ID 必须是 1..128 个小写 ASCII 字符，首字符为字母，后续只允许字母、数字、点、下划线和连字符。
   显示名为 1..80 Unicode code points，model ID 为 1..256 Unicode code points；所有文本必须是 well-formed
   UTF-16、无控制字符、无首尾空白，构造器不做静默 trim 或规范化。
3. `SecretRef` 是只含 `Kind` 与 opaque ID 的不可变 record。Kind 闭集为 `ASR`、`LLM`、`CONNECTOR`，并与
   Provider variant 精确匹配。opaque ID 必须以 `sec_` 开头，后接 16..124 个小写 ASCII 字母、数字、下划线
   或连字符。它不是 Secret 值、不能承载认证头，也不得通过 `toString()` 暴露 ID。
4. Endpoint 为长度不超过 2,048 code points 的绝对 `http`/`https` URI。它必须有合法 host，不得含 user-info、
   query、fragment、越界 port 或 `.`/`..` 路径段。公网端点必须使用 HTTPS；HTTP 只允许 loopback、`.local`
   或显式私有/链路本地地址。绑定 `SecretRef` 时，HTTP 进一步只允许 loopback，避免凭据经局域网明文传输。
5. Provider 带 `SecretRef` 时必须同时带 Endpoint。任何配置、Endpoint 与 SecretRef 的 `toString()` 都只输出
   类型、开关和脱敏网络类别，不输出显示名、model、完整 URL、host、opaque ID 或哈希。
6. `CFG-001` 只建立值对象和构造边界。它不迁移 `AppSettings`，不实现 Secret Store、RecognitionRoute、
   ConnectorDefinition、配置继承、序列化、网络执行、UI 或 Feature Flag；这些分别保留给 `CFG-002`、
   `CFG-004`、`CFG-006`、`CFG-008`、`REC-001` 与 `ACT-001`。

未选择的方案：

- 继续在 `AppSettings` 中增加字段：会延续明文 Secret 与跨域耦合，且抢占 `CFG-006` 的迁移设计。
- 用一个开放 `Map<String, String>` 表示所有 Provider：无法在构造时证明类型、长度、网络和 Secret 不变量。
- 禁止全部 HTTP：会破坏本机或明确私有 LAN 的无凭据开发端点；本决策保留无凭据本地 HTTP，但禁止携带
  Secret 的非 loopback 明文传输。

## Consequences

正面结果是 Provider 非密钥配置、Secret identity 和网络安全下限在构造时即成立，后续任务不必从任意字符串
重新推断安全属性；值对象也可由纯 JVM 测试验证。代价是迁移期间旧 `AppSettings` 与新模型会暂时并存，而且
调用方必须显式处理 Optional、Provider kind 和非法配置。

本 ADR 不声明现有明文 Key 已迁移，也不使新模型成为运行时 authority。`CFG-006` 必须提供幂等迁移和旧数据
处理，`CFG-008` 必须实现 SecretRef 的安全存储、轮换与导出边界。若未来需要公网 HTTP、URL query 模板、更多
Provider kind 或不同 Secret ID 格式，必须通过新 ADR 替代本决策，不能放宽构造器后静默兼容。

## Validation

2026-08-13 接受本决策前已核对：

- `docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md` 的 ConfigStore/SecretStore 分域与 Provider ID 引用；
- `docs/opentypeless_specs/04_ACTION_PROTOCOL_V1.md` 的 Connector `SecretRef` 边界；
- `docs/opentypeless_specs/06_SECURITY_PRIVACY.md` 的 URL、认证头和日志脱敏约束；
- `docs/opentypeless_specs/07_IMPLEMENTATION_BACKLOG.md` 中 `DOC-002 → CFG-001` 依赖与任务范围。

ADR 结构和生命周期检查的实际结果：

```text
python3 scripts/verify_adrs.py
PASS — template + index, 1 standalone decision

python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
PASS — 4/4
```

实现必须继续由以下可复现检查验证：

```bash
python3 scripts/verify_adrs.py
python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
cd android
./gradlew :app:testDebugUnitTest --tests 'com.opentypeless.android.config.*'
./gradlew :architecture-gate:check
```

具体模型、门禁和完整 Android 构建结果由 `CFG-001` 任务报告与测试矩阵记录；接受本 ADR 本身不等于这些实现已
完成，也不授权绕过失败的实现验证。

2026-08-13 的实现验收结果：

```text
ProviderConfigTest + SecretRefTest: 12/12 PASS
app JVM: 690/690 PASS
source architecture: 85/85 PASS; production scan PASS
compiled architecture: 78/78 PASS; Debug/Release 2/2 PASS
fresh-cache strict scripts/verify_android.sh: BUILD SUCCESSFUL
187 tasks: 183 executed, 4 up-to-date
Xiaomi 10 Ultra: NOT RUN — INSTALL_FAILED_USER_RESTRICTED
```

设备安装失败没有被报告成设备测试通过；本 ADR 对应的领域模型由 JVM、source/compiled architecture gate 与
完整 strict 构建闭环，仍未接线到旧 `AppSettings` 或任何网络执行路径。

## Rollback

在尚未接线和持久化前，可删除 `com.opentypeless.android.config` 模型并把 Backlog 恢复为 TODO，不影响现有
`AppSettings` 数据。模型一旦被 `CFG-006` 持久化或导出，本 ADR 只能由新 ADR 显式替代，并由迁移测试证明兼容性。

## References

- Task：`CFG-001`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- 安全规范：`docs/opentypeless_specs/06_SECURITY_PRIVACY.md`
- Backlog：`docs/opentypeless_specs/07_IMPLEMENTATION_BACKLOG.md`
- 关联历史研究：`docs/opentypeless_specs/09_ADR_RESEARCH.md` 中 ADR-010
