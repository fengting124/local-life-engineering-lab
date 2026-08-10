# LangGraph 安全升级实施计划

- Status: Active
- Type: Plan
- Owners: Project maintainers
- Last verified: 2026-08-10
- Baseline: `main@179f009b997824f80edbacf9ac750544fa527041`
- Source of truth: `docs/superpowers/specs/2026-08-10-agent-langgraph-safe-upgrade-design.md`, PR #32 implementation and verification commands

## Task 0：依赖审计和红灯基线

- [x] 创建 `security/agent-langgraph-safe-upgrade` 独立 worktree。
- [x] 审计 LangChain、LangGraph 和 Milvus 直接 import。
- [x] 用 dry-run resolver 验证候选版本与 PyMilvus 2.4.9 共存。
- [x] 在干净候选环境复现 4 个 legacy serializer 失败。
- [x] 确认 `pip check` 通过并记录环境噪声。

## Task 1：测试先行

- [x] 为 typed Checkpoint、pending writes 和 namespace 编写红灯测试。
- [x] 为精确读取、父链、特殊 write 索引和重启恢复编写红灯测试。
- [x] 为 strict serializer 与 HITL rollback 编写安全红灯测试。
- [x] 注册 Testcontainers MySQL 的官方基础 conformance。

## Task 2：最小依赖升级

- [x] 固定 LangGraph、Checkpoint、Core 和 provider 的直接版本。
- [x] 删除未使用的 `langchain` 和 `langchain-milvus`。
- [x] 保持 PyMilvus/Milvus Lite 2.4.x。
- [x] 增加 test-only conformance 包和 strict msgpack 配置。
- [x] 运行 import smoke 与 `pip check`。

## Task 3：typed schema 与 Saver

- [x] 新增 V105 v2 Checkpoint 和 write 表，legacy 表不变。
- [x] 将 Saver 改为严格 typed serializer，并调用 BaseSaver 构造逻辑。
- [x] 对所有查询增加 `checkpoint_ns` 隔离。
- [x] 实现 4.1.1 `WRITES_IDX_MAP` 语义。
- [x] 保持 Checkpoint 保存与 HITL bind 的事务原子性。
- [x] 通过定向测试与官方基础 conformance。

## Task 4：历史数据迁移

- [x] 实现 dry-run/migrate/verify-only/thread/limit CLI。
- [x] 使用严格 JSON legacy load，再编码为 typed v2。
- [x] 验证幂等、父链、pending writes、恢复节点和 fail-closed。
- [x] 通过 0.2.45 脱敏 fixture。
- [x] 通过 Testcontainers MySQL 真实迁移。

## Task 5：行为兼容

- [x] 运行完整 HITL 定向测试。
- [x] 运行 Provider 确定性合同测试。
- [x] 验证现有 Milvus Lite 2.4.x 数据、过滤、CRUD 和重启持久化。
- [x] 重建 Docker Lite 并运行 HITL security smoke 7/7。
- [x] 运行一次 4 请求 DeepSeek Flash smoke；未形成质量基线或重复挑选结果。

## Task 6：最终门禁

- [x] `pip check`、Agent full suite、coverage 和 mutation 通过。
- [x] Checkpointer、migration、V105 空库、HITL、RAG、Docker restart 通过。
- [x] docs check 与 `git diff --check` 通过。
- [x] 证明 strict msgpack 开启、pickle fallback 关闭、未知类型无法重建。
- [x] 证明高风险审批前执行仍为 0，permission/refusal 无回退。

## Task 7：交付

- [x] 更新 typed schema、迁移 runbook、rollout/rollback 和依赖审计文档。
- [x] 按依赖/schema、Saver、migration、测试、文档拆分提交。
- [x] 推送 `security/agent-langgraph-safe-upgrade` 并创建 Draft PR #32；保持 Draft，不转 Ready、不合并。
- [x] 独立审查发现 1 个 legacy special-write 索引阻塞项；以红灯测试、`WRITES_IDX_MAP` 规范化和冲突 fail-closed 修复后，定向 9/9 与 Agent 686 条回归通过，当前 `BLOCKING FINDINGS=0`。不转 Ready、不合并。
