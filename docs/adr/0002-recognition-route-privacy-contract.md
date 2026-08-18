# ADR-0002: RecognitionRoute 隐私与失败边界

## Status

Accepted

## Background

`CFG-002` 要把未来 `RecognitionRouter` 的路线配置冻结为纯领域值对象。现有
`com.opentypeless.android.diagnostics.RecognitionRoute` 只描述一次运行实际选择的旧 backend，不包含多 step、
失败分类、能力要求或隐私下限，不能充当配置 authority，也不能在构造时拒绝不安全 fallback。

架构草案给出了 route ID、steps、privacy floor、retry/fallback、capability 与 confirmation，但 step 若不声明
自己的隐私等级，模型无法判断“本地转公网却未获确认”或“step 低于 route floor”等矛盾。路由模型还属于未来
导入配置的外部输入，因此集合、字符串和重试次数必须有硬上限，取消、权限失败和 editor target 变化不得被配置
成自动重试或 fallback。

该模型会被 `REC-009` 用作识别选择输入，隐私等级和默认降级规则属于高成本安全边界；在实现前由本 ADR 冻结。

## Decision

1. 在纯 Java `com.opentypeless.android.config` 包中新增不可变、不可序列化的 `RecognitionRoute` record。它只保存
   `id`、有序 `steps`、`privacyFloor` 与 `allowPrivacyDowngrade`；不引用旧 diagnostics route、Android、Provider
   实例、网络客户端、Secret、持久化或可执行回调。
2. route ID 与 provider ID 使用 `CFG-001` 相同的 1..128 小写 ASCII 形状。route 必须有 1..8 个 step，provider
   ID 不得重复；输入 List/Set 必须防御性复制。非末 step 必须声明至少一个 fallback failure，末 step 的
   fallback set 必须为空，从结构上保证有限且不存在悬空 fallback。
3. `RouteStep` 精确保存 provider ID、显式 `PrivacyClass`、`RetryPolicy`、`fallbackOn`、
   `requiredCapabilities` 与 `ConfirmationPolicy`。显式 privacy 字段是架构草案的安全补全，未来 Provider descriptor
   必须用实际 capability/privacy 与它交叉验证，不能根据 provider 名称猜测。
4. `PrivacyClass` 按隐私强度从高到低闭合为 `ON_DEVICE`、`LOCAL_NETWORK`、`PUBLIC_NETWORK`。所有 step 必须
   不低于 route floor；`allowPrivacyDowngrade=false` 时后续 step 不得比前一步更公开。允许降级时，每个实际
   降级 step 必须使用 `REQUIRE_ON_PRIVACY_DOWNGRADE` 或 `REQUIRE_BEFORE_USE`，不能用 `NOT_REQUIRED`。
   该 flag 只是配置意图，不能绕过敏感字段禁止联网、`EffectiveProfileResolver` 硬规则或运行时用户拒绝。
5. `ProviderCapability` 只表达闭合需求：streaming、partial revision、endpointing、on-device、prompt、biasing、
   dynamic keyterms、language detection、timestamps 与 audio upload。声明 `ON_DEVICE` privacy 的 step 必须要求
   `ON_DEVICE` capability，且不得同时要求 `AUDIO_UPLOAD`；其他 privacy class 不得伪称 on-device capability。
6. `FailureClass` 精确采用架构文档的 19 个稳定类别。`CANCELLED`、`PERMISSION_DENIED` 与 `TARGET_CHANGED`
   永远不能出现在 retry/fallback set。`RetryPolicy` 只允许 1 或 2 次总尝试，并显式保存 `retryOn`；一次尝试必须
   配空 retry set，两次尝试必须配非空 set，禁止无界或无错误分类的重试。`AUTHENTICATION` 可以作为前一
   step 的分类结果，但后续 step 必须 `REQUIRE_BEFORE_USE`，不能用同隐私等级掩盖凭据配置错误。
7. Route/Step 的 `toString()` 只输出 step 数、隐私/策略与集合计数，不输出 route ID、provider ID、Secret、
   Endpoint 或任意用户正文。该值对象不是执行授权；`REC-003`/`REC-008`/`REC-009` 才负责 registry、错误映射与
   运行时决策，`CFG-004`/`CFG-006` 才负责配置组合与迁移。

未选择的方案：

- 复用 diagnostics `RecognitionRoute`：它绑定旧 `RecognitionBackend`，只有 selected/actual/fallback 观察值，
  无法表达或验证目标配置。
- 省略 step privacy、等 Router 再查 Provider：会让持久化配置本身可构造矛盾，且测试无法证明 privacy floor。
- 用开放字符串/Map 表达 failure/capability：无法穷举、限制或安全迁移未知值。
- 允许任意重试次数：会产生无限重试、重复录音或不可预测的 Provider 切换风险。

## Consequences

正面结果是非法空路线、不可达 fallback、终态错误重试、能力/隐私矛盾和未确认的隐私降级在进入 Router 前即被
拒绝；模型可由纯 JVM 与 source/compiled gate 独立验证。代价是 RouteStep 比原草案多一个显式 privacy 字段，
未来 `ProviderDescriptor` 必须证明声明与实际能力一致；旧 diagnostics route 在迁移期间继续共存但不得成为配置
authority。

本决策不启动网络、不选择 Provider、不实现 fallback、不迁移旧设置，也不改变现有语音行为。若未来需要新的
隐私等级、超过两次的重试或跨 step 重复 provider，必须由新 ADR 与有界测试替代本决策，不能静默放宽。

## Validation

2026-08-13 接受本决策前已逐项核对架构 §10.2/§11.1–11.3、测试矩阵 §9、安全规范的敏感字段无隐私降级
规则，以及旧 diagnostics route 的实际三字段表面。确认 step 若缺少显式 privacy，无法从值对象本身验证
privacy floor，因此接受本 ADR 的六字段 RouteStep 补全。

ADR 结构和生命周期检查实际结果：

```text
python3 scripts/verify_adrs.py
PASS — template + index, 2 standalone decisions

python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
PASS — 4/4

git diff --check -- docs/adr
PASS
```

实现与自动化验证已完成：

```bash
python3 scripts/verify_adrs.py
python3 -m unittest discover -s scripts -p 'test_verify_adrs.py' -v
cd android
./gradlew :app:testDebugUnitTest --tests 'com.opentypeless.android.config.RecognitionRouteTest'
./gradlew :architecture-gate:check
```

`RecognitionRouteTest` 12/12、app JVM 702/702、source architecture 86/86、compiled gate 79/79 与
Debug/Release production variants 2/2 均 PASS。测试覆盖 exact record/enum shape、全部 failure/capability 值、
1/8/9 step、重复 provider、终态错误、重试上限、防御性有界复制、privacy floor、降级确认、认证失败转移、
on-device capability 矛盾、ID 边界和 redacted `toString()`；双门禁拒绝 open/extra/Android/serialization/
execution/Secret/plaintext/旧 diagnostics authority 形状。

全新 `GRADLE_USER_HOME` 下以 strict dependency verification 运行 `scripts/verify_android.sh`，实际
`BUILD SUCCESSFUL`（2m39s，187 tasks：183 executed / 4 up-to-date），覆盖 clean、全部 JVM、compiled
architecture、lintRelease、Debug/Release 与 AndroidTest assemble。小米 10 Ultra `be4e2015` 在线但处于
Dozing/锁屏；对 SHA-256 为 `254e8ee1d6468e2d02018458e99165cf5f0b5f9b1938f16893742e44feb01d7d`
的最终 debug APK 执行一次显式 serial 安装，设备返回
`INSTALL_FAILED_USER_RESTRICTED: Install canceled by user`，故设备执行为 NOT RUN；未唤醒、解锁或绕过策略。
该限制不改变纯 JVM 模型验收，也没有把 APK 编译冒充设备测试。

## Rollback

在 `CFG-004`/`REC-009` 接线和持久化前，可删除新领域类型、测试与门禁并把 `CFG-002` 恢复为 TODO，不影响现有
diagnostics route 或用户数据。接线后只能由新 ADR 和配置迁移替代。

## References

- Task：`CFG-002`
- 设计文档：`docs/opentypeless_specs/02_ARCHITECTURE_DEVELOPMENT.md`
- 安全规范：`docs/opentypeless_specs/06_SECURITY_PRIVACY.md`
- 测试矩阵：`docs/opentypeless_specs/08_TEST_VALIDATION.md`
- 关联 ADR：`ADR-0001`；历史调研 `ADR-005`
