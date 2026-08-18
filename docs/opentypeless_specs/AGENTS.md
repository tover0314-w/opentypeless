# AGENTS.md — OpenTypeless 编码代理工作规范

> 适用于 Codex、Claude Code、其他自动编码代理及人工贡献者。  
> 代码基线：`dengxuezhao/opentypeless`。  
> 本文件的安全约束高于单个任务提示。

## 1. 开始工作前

必须依次：

1. 读取本文件；
2. 读取 `00_README.md`；
3. 读取任务指定的设计文档；
4. 读取 `07_IMPLEMENTATION_BACKLOG.md` 中对应任务；
5. 从 `docs/adr/README.md` 读取关联 ADR；
6. 检查当前 git status、分支和 HEAD；
7. 检查最新 CI；
8. 只实现一个任务 ID。

如果任务与文档冲突，停止扩大范围，在交付报告中指出冲突；不要自行重写整体方案。

许可证、危险权限、持久格式、Secret 边界、默认网络行为、不可逆迁移、编辑器 authority、键盘底座或 Feature
Flag 删除条件发生新决策时，必须先从 `docs/adr/0000-template.md` 建立 ADR。`Proposed` 不能授权实施；只有
证据与后果完整的 `Accepted` ADR 才能作为不可逆实现依据。

---

## 2. 绝对禁止

### 编辑器与 IME

- 不得在 `EditorTransactionManager` 之外新增 `commitText`、`setComposingText`、`finishComposingText`、`deleteSurroundingText`、`sendKeyEvent` 等写操作；
- 不得让 Provider、Action、LLM、Rime native Adapter 直接持有 `InputConnection`；
- 不得在目标校验失败后改为写入当前光标；
- 不得忽略 editor epoch、selection 或 fingerprint；
- 不得让新旧 Feature Flag 路径同时提交；
- 不得在 IME 隐藏或锁屏后继续无提示录音。

### 安全与网络

- 不得关闭 Gradle dependency verification；
- 不得添加“信任所有证书”；
- 不得允许公网 HTTP；
- 不得默认跟随重定向；
- 不得硬编码 API Key、Token、密码；
- 不得把 Secret 放入 URL、日志、Bundle、测试快照或导出；
- 不得移除请求/响应/文本/音频大小上限；
- 不得把服务端错误正文直接显示给用户；
- 不得实现远端 Shell、JavaScript、任意 Intent、KeyEvent 或 Accessibility 点击；
- 不得让 Action 自动点击发送按钮；
- 不得在密码/验证码/支付字段联网、学习或留历史。

### 数据与学习

- 不得把历史自动转成永久规则；
- 不得把全部键盘输入保存为“学习数据”；
- 不得把 Rime UserDB 自动上传；
- 不得在无迁移测试时修改持久格式；
- 不得在导出中包含 Secret；
- 不得用一次 LLM 大改写生成整段纠正规则；
- 不得把真实用户音频、正文、词典或 Key 提交到仓库。

### 许可证与依赖

- 不得未经 ADR/许可审查复制 GPL/LGPL 代码；
- 不得删除版权和 NOTICE；
- 不得使用浮动模型/依赖 URL；
- 不得在哈希不匹配时继续安装；
- 不得为减小构建问题绕过 AAR/模型校验。

### 工程过程

- 不得一次性重写整个 Android 项目；
- 不得把多个 Epic 夹带进一个任务；
- 不得把“编译通过”写成“测试完成”；
- 不得声明未实际执行的测试；
- 不得吞掉异常且不给错误分类；
- 不得虚构性能、准确率、真机结果；
- 不得删除失败测试来让 CI 变绿；
- 不得在用户未要求时推送、发布或修改外部服务。

---

## 3. 架构硬规则

1. 所有异步任务携带 Session ID/generation；
2. 所有编辑器写入走 EditorOperation；
3. 所有 Composition 走 CompositionCoordinator；
4. 所有 Provider 走 RecognitionProvider Contract；
5. 所有识别选择走 RecognitionRouter；
6. 所有有效配置走 EffectiveProfileResolver；
7. 所有联网动作先生成 DisclosurePlan；
8. 所有 Action Response 过 JSON Schema 和 Operation 白名单；
9. 所有 Secret 通过 SecretRef；
10. 所有持久格式有 `format/version`；
11. 所有外部输入有长度、数量和深度限制；
12. 领域层尽量不依赖 Android UI；
13. 主线程不做网络、数据库、模型校验或重推理；
14. 诊断默认不存正文。

---

## 4. 任务执行流程

### Step 1：确认范围

输出内部工作说明：

```text
Task ID:
Goal:
Non-goals:
Files expected:
Tests required:
Dependencies satisfied:
Risks:
```

不要因看到相邻问题而顺便实现后续任务。可以在报告中记录 follow-up。

### Step 2：先读现有实现

定位：

- 当前调用链；
- 现有测试；
- 安全守卫；
- 生命周期；
- 数据格式；
- Feature Flag；
- 许可证。

不得只根据文件名猜行为。

### Step 3：先建立契约

优先顺序：

1. 领域模型；
2. 失败分类；
3. 单元/契约测试；
4. Adapter；
5. 真实实现；
6. UI；
7. 删除旧路径。

### Step 4：实现最小垂直切片

保持：

- 可编译；
- 可测试；
- 可回滚；
- 旧行为仍可用；
- 不创建永远不接线的抽象层。

### Step 5：验证

至少执行任务相关命令。Android 基线参考：

```bash
cd android
./gradlew testDebugUnitTest
./gradlew lintRelease
./gradlew assembleDebug
./gradlew assembleRelease
./gradlew assembleDebugAndroidTest
```

有设备/模拟器：

```bash
./gradlew connectedDebugAndroidTest
```

完整项目仍需保留原有前端/Rust测试。任务报告必须区分：

```text
PASS
FAIL
NOT RUN — reason
```

### Step 6：自审查

搜索：

```text
InputConnection write calls
hard-coded secrets
http://
followRedirects
Log.* text
TODO/FIXME
new dependencies
new persistence fields
new exported Android components
new permissions
```

### Step 7：更新文档

根据变更更新：

- Backlog 状态；
- ADR；
- protocol/schema；
- migration；
- privacy disclosure；
- test matrix；
- release notes。

---

## 5. 代码要求

### Kotlin

- 优先不可变 `data class`；
- 使用 sealed interface 表达有限状态；
- Coroutine 使用结构化并发；
- Flow 事件有终态；
- 不使用 `GlobalScope`；
- 不使用无界 Channel/Executor；
- Android 对象不进入纯领域模型；
- Java 调用需要稳定 façade。

### Java

- 现有 Java 可保留；
- 不做无行为价值的全文件转换；
- 并发状态显式；
- 资源在 finally/AutoCloseable 释放；
- 回调代际安全；
- 不用静态全局 Context。

### 错误

使用稳定错误类型：

```text
FailureClass / ErrorCode / user-localized message
```

服务端/系统原始 message 仅进入截断、脱敏诊断。

### 测试

- 测试名称描述行为；
- Race 用例必须可重复；
- 不用 sleep 作为唯一同步；
- 使用 fake clock/scheduler；
- Provider 使用 contract suite；
- Action 使用 schema fixture；
- Migration 使用真实旧版本 fixture；
- 大模型测试与常规 CI 分层。

---

## 6. UI 要求

- 管理端遵循 Material 3；
- IME 热路径先服从性能和底座；
- 触控目标至少 48dp；
- 状态不只靠颜色；
- 所有图标有无障碍描述；
- 中文/英文资源同步；
- 2.0 字体不截断；
- 错误给出下一步；
- Provider 显示实际数据去向；
- Prefix replay 只能称“实时预览”，不能称“真流式”；
- 密码字段显示隐私模式；
- 不在 UI 回显完整 Secret。

---

## 7. 新依赖检查

引入前记录：

```text
Name:
Version/commit:
Source:
License:
Why needed:
Alternatives:
APK impact:
Native ABI:
Security history:
Update strategy:
NOTICE:
```

原生库/模型额外记录 hash、runtime、内存、来源和可分发权。

---

## 8. 数据格式变更

必须提供：

- schema/version；
- 旧→新迁移；
- 幂等；
- 中断；
- 磁盘不足；
- 回滚或不可降级说明；
- fixture；
- 导入导出兼容；
- Secret/历史处理；
- WAL/明文迁移检查。

---

## 9. Feature Flag

重大迁移先用 Flag：

- 新旧不能同时写；
- Flag 进入诊断；
- 默认值按渠道；
- 有删除条件；
- 回滚不破坏数据；
- 不用 Flag 关闭硬安全规则。

---

## 10. 交付报告格式

```markdown
# Task Report: <ID>

## Result
DONE / PARTIAL / BLOCKED

## Scope
- Implemented:
- Not implemented:

## Changes
- file: reason

## Architecture
- contracts:
- state changes:
- migration:
- feature flag:

## Security & privacy
- data sent/stored:
- permissions/components:
- threat considerations:

## Tests actually run
| Command | Result | Notes |

## Evidence
- screenshots/logs/benchmark artifacts

## Risks
- ...

## Rollback
- ...

## Follow-ups
- task IDs only

## Git
- branch:
- commit:
- worktree status:
```

不得写：

> “应该可以”“大概通过”“理论上没问题”

必须写实际证据或明确 `NOT RUN`。

---

## 11. Definition of Done

任务完成需：

- 范围符合任务；
- 代码可审查；
- 测试通过；
- CI 不被绕过；
- 安全不变量保持；
- 文档更新；
- 无真实敏感数据；
- 无许可证遗漏；
- 有回滚；
- git worktree 清楚；
- 未完成项诚实记录。

---

## 12. 何时必须停止实现并报告

遇到以下情况，不得自行猜测：

- 许可证与目标分发冲突；
- 需要新的危险权限；
- 必须放宽敏感字段；
- 必须关闭 dependency verification；
- 数据迁移可能不可逆丢失；
- Provider 协议与文档冲突；
- 底座无法经过 EditorTransaction；
- 真实设备行为与契约严重冲突；
- 任务要求同时完成多个未决 ADR。

此时输出 BLOCKED 报告和最小证据，不要用不安全替代方案“完成任务”。
