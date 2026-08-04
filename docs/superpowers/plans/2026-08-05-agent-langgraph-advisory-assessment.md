# LangGraph 安全公告评估实施计划

- Status: Complete
- Type: Plan
- Owners: Project maintainers
- Last verified: 2026-08-05
- Source of truth: `docs/superpowers/specs/2026-08-05-agent-langgraph-advisory-assessment-design.md`

## Task 1：隔离工作区和环境证据

- [x] 从指定 `main` SHA 创建专用 worktree 和安全分支。
- [x] 记录 Python、`pip freeze`、`pip check` 和六个关键包版本。
- [x] 检查证据不包含路径、Token、密钥和数据库连接串。

## Task 2：序列化调用链

- [x] 审计项目 `aput`、`aget_tuple`、`aput_writes` 和 `_row_to_tuple`。
- [x] 审计安装包 `BaseCheckpointSaver`、`JsonPlusSerializer` 和 `Pregel.aget_state`。
- [x] 区分 Checkpoint、metadata 和 pending writes 的 serializer 方法。
- [x] 记录 HITL 恢复从 HTTP 到 Checkpointer 的完整调用链。

## Task 3：安全复现

- [x] 为普通 JSON、msgpack、typed API 和 pending writes 先建立测试。
- [x] 使用隔离 MySQL 8.4 执行无害 marker 复现。
- [x] 分别记录依赖受影响和生产路径可达性。
- [x] 验证修复候选版本的 strict msgpack 策略。

## Task 4：数据库信任边界

- [x] 审计 Agent 数据库账号和 Compose 端口。
- [x] 搜索 Checkpoint 写入主体、测试脚本和清理脚本。
- [x] 确认外部 API 不接受原始 Checkpoint 字节。
- [x] 记录后续最小权限、端口和测试防误连建议。

## Task 5：依赖兼容矩阵

- [x] 验证当前 0.2.45 基线。
- [x] 查询并验证最低修复版本 1.0.10。
- [x] 查询执行时最新稳定版并验证。
- [x] 分析 LangChain、模型 SDK、Checkpoint 和 Milvus 适配层的联动升级。
- [x] 运行导入、图编译、协议测试和现有测试。

## Task 6：历史 Checkpoint

- [x] 从 0.2.45 生成脱敏 fixture。
- [x] 覆盖对话、工具、pending writes、审批、重放和父子链。
- [x] 用两个候选版本验证字段和消息类型。
- [x] 选择历史数据策略并设计 rollout/rollback。

## Task 7：完整门禁

- [x] 运行当前 Agent 全量测试与覆盖率门禁：最终收口 `665 passed, 1 skipped`，`76.42%`。
- [x] 运行 Checkpointer/HITL 定向测试和 Testcontainers 安全测试：最终收口子集 `67 passed, 1 skipped`。
- [x] 运行 Docker Lite 安全烟雾测试：`7/7 PASS`，隔离数据已清理。
- [x] 运行文档检查和 `git diff --check`。

## Task 8：版本管理

- [x] 独立复审 diff 和敏感信息，未发现生产代码、生产 pin 或凭据变更。
- [x] 按评估测试和评估文档拆分详细提交。
- [x] 推送 `security/agent-langgraph-advisory-assessment`。
- [x] 创建 Draft PR #31 到 `main`，保持 Draft，不转 Ready、不合并。

## Task 9：安全归因收口

- [x] 通过 PyPI JSON、`pip index` 和可下载 wheel 复核执行时最新版本。
- [x] 确认 `langgraph==1.2.10` 是 2026-07-28 发布的真实 PyPI 版本，保留候选 C。
- [x] 将 msgpack 路径归属 GHSA-g48c，将旧 JSON 路径归属独立的 GHSA-wwqv。
- [x] 将后续升级门槛拆分为 `langgraph>=1.0.10` 与 `langgraph-checkpoint>=3.0.0`。
- [x] 运行新 head 的完整验证，推送并等待 Docs/Agent CI：两项均成功。
- [x] 完成独立复审：`BLOCKING FINDINGS=0`，PR 保持 Draft 等待合并决策。

## Handoff

- Draft PR：#31 `security(agent): assess LangGraph checkpoint advisory reachability`。
- 基线：`main@2ee8e88cb375ef82abb5f32db54bb6cf67872892`。
- 收口复审 head：`cde7e044f9d3f4b73205f6c1723a7fb8b906e351`；相对 `origin/main` 为 `ahead 5 / behind 0`。
- GitHub Docs 和 Agent `test-and-mutation` 门禁均成功。
- PR 为 Draft、mergeable/clean，评论、Review 和未解决 thread 均为 0。
- 最终复审：`BLOCKING FINDINGS=0`。
- 后续升级必须新建实施分支，不在本评估 PR 修改生产依赖。

## 停止条件

本计划完成 Draft PR 后停止。不得在本分支升级 LangGraph 或实施数据库权限重构，不运行 24×2 DeepSeek 基线。
