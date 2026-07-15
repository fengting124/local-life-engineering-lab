# ADR 索引

- Status: Active
- Type: Overview
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: `docs/adr/*.md`

ADR 记录影响多个模块、长期存在并具有明确取舍的架构决策。普通实现细节不需要 ADR。

## 生命周期

- `Proposed`：已经提出，尚未成为基线。
- `Accepted`：当前有效决策。
- `Superseded`：已被后续 ADR 替代。

## 使用条件

满足以下任一条件时创建 ADR：

- 决策影响多个服务或多个团队协作边界。
- 决策会长期影响代码组织、部署、数据或安全策略。
- 存在多个可行方案，并且需要记录为什么选择当前方案。

## 模板

```markdown
# ADR-NNNN：决策标题

- Status: Proposed | Accepted | Superseded
- Date: YYYY-MM-DD
- Owners: Project maintainers
- Superseded by: ADR-NNNN

## 背景

## 决策

## 影响

## 备选方案

## 验证
```

## 索引

- [ADR-0001：建立文档治理规范](./0001-建立文档治理规范.md)
