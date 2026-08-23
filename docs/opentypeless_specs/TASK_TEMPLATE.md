# OpenTypeless 单任务执行模板

> 将本文件复制到 Issue/任务提示中。一次只填写一个 Backlog ID。

## 1. 任务

```text
Task ID:
Title:
Priority:
Size:
Owner:
Target branch:
Baseline commit:
```

## 2. 必读材料

```text
AGENTS.md
00_README.md
07_IMPLEMENTATION_BACKLOG.md 对应任务
关联产品章节：
关联架构章节：
关联安全章节：
关联测试章节：
关联 ADR：
```

## 3. 目标

用一句话描述可验证结果：

> 

## 4. 非目标

- 
- 
- 

## 5. 前置依赖

| Dependency | Status | Evidence |
|---|---|---|
| | | |

如果依赖未满足，任务状态应为 BLOCKED，不要自行实现依赖之外的大量内容。

## 6. 允许修改范围

```text
Expected packages/modules:
Expected files:
New dependencies allowed:
Persistence changes allowed:
Manifest/permission changes allowed:
UI changes allowed:
```

## 7. 不可违反约束

- 所有编辑器写入走 `EditorTransactionManager`；
- 不降低敏感字段策略；
- 不关闭 dependency verification；
- 不硬编码 Secret；
- 不引入未审计许可证；
- 不实现后续任务；
- 不声称未执行测试通过。

补充：

- 

## 8. 设计

### 接口

```kotlin
// expected contract
```

### 状态变化

```text
Before:
After:
```

### 数据流

```mermaid
flowchart LR
  A --> B
```

### 错误与回滚

| Failure | Expected behavior |
|---|---|
| | |

## 9. 测试先行清单

### Unit

- [ ] 

### Contract

- [ ] 

### Instrumentation

- [ ] 

### Real device

- [ ] 或 `NOT REQUIRED`

### Security/privacy

- [ ] 

### Performance

- [ ] 或 `NOT REQUIRED`

## 10. 验收标准

- [ ] Backlog 交付物已完成；
- [ ] Backlog 验证项有证据；
- [ ] 没有夹带后续任务；
- [ ] 实际命令已记录；
- [ ] 失败分类稳定；
- [ ] 文档已更新；
- [ ] 可回滚。

## 11. 实际执行命令

```bash
# 填写实际命令
```

## 12. 交付报告

按 `AGENTS.md` 第 10 节格式输出。
