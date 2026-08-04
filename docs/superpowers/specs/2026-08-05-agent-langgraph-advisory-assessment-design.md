# LangGraph 安全公告评估设计

- Status: Approved
- Type: Explanation
- Owners: Project maintainers
- Last verified: 2026-08-05
- Source of truth: `copilot-agent-service/session/checkpointer.py`, installed LangGraph source, `tests/security/`

本文定义 Issue #29 第一阶段的评估边界。目标是把依赖是否受影响、项目运行路径是否可达、升级是否兼容三个问题拆开，用无害证据回答，而不是直接修改生产依赖。

## 约束

- 基线固定为 `main@2ee8e88cb375ef82abb5f32db54bb6cf67872892`。
- 本阶段不修改生产依赖、Prompt、Router、RAG、工具权限、EvalCase 或评分规则。
- 本阶段不迁移到 `interrupt()` / `Command(resume=...)`。
- 复现载荷只允许构造进程内 `collections.Counter` 标记，禁止 shell、环境变量读取和网络访问。
- 所有数据库复现使用 Testcontainers 创建的隔离 MySQL 8.4。

## 判定模型

评估使用三个相互独立的结论：

| 结论 | 含义 |
| --- | --- |
| `dependency_affected` | 已安装依赖中的公告 API 能否重建未注册对象。 |
| `advisory_msgpack_path_reachable` | 生产 Checkpointer 是否把数据库字节交给 `loads_typed(("msgpack", ...))`。 |
| `current_production_path_reachable` | 具备 Checkpoint 表写权限的主体，能否让生产恢复路径执行不安全对象重建。 |

不能用“当前存储列是 JSON”推导整个生产路径安全。旧版 `JsonPlusSerializer.loads()` 自身也会根据持久化 JSON 中的模块名和类名动态导入并构造对象。

## 调用链

```text
Graph 节点结束
  -> BaseCheckpointSaver.aput / aput_writes
  -> AsyncMySQLCheckpointer.aput / aput_writes
  -> JsonPlusSerializer.dumps
  -> langgraph_checkpoint.state / langgraph_checkpoint_write.value

GET /chat/resume
  -> agent_graph.aget_state(config)
  -> Pregel.aget_state
  -> AsyncMySQLCheckpointer.aget_tuple
  -> _fetch_one / _fetch_latest + _fetch_pending_writes
  -> _row_to_tuple
  -> JsonPlusSerializer.loads(state)
  -> JsonPlusSerializer.loads(pending write)
  -> StateSnapshot
  -> HITL 绑定校验
```

完整 Checkpoint 和 pending writes 使用 `dumps/loads`。Metadata 使用标准库 `json.dumps/json.loads`。当前生产 MySQL 路径没有调用 `dumps_typed/loads_typed`。`BaseCheckpointSaver.__init__` 会为缺少 typed 方法的旧 serializer 补适配，但不会把自定义 saver 的显式 `loads()` 调用改成 typed 调用。

## 无害复现设计

测试覆盖以下路径：

1. 编译图的 `aget_state` 确实委托给自定义 saver 的 `aget_tuple`。
2. 修改隔离 MySQL 中的普通 JSON Checkpoint 后，生产 `_row_to_tuple` 能重建无害 `Counter` 标记。
3. 向 `state` 写入 msgpack 字节时，生产路径以 JSON 解码错误停止，`loads_typed` 调用计数保持零。
4. 直接调用受影响依赖的 `loads_typed`，证明依赖层公告路径存在。
5. 单独修改 pending writes，证明其与完整 Checkpoint 一样走普通 JSON `loads`。
6. 在修复候选版本中启用 strict allowlist，证明未注册 msgpack 类型被阻止。

## 历史数据设计

由当前 0.2.45 环境生成静态脱敏 fixture，覆盖：

- 普通对话；
- 工具调用和工具结果；
- pending writes；
- PENDING 审批；
- EXECUTED 结果重放；
- 父子 Checkpoint 链。

fixture 只使用 `FIXTURE-*` 标识，不保存真实订单、用户、密钥、连接串或 HMAC 摘要。候选版本必须能读取全部字段、恢复消息类型并保持父子关系；否则升级策略不能写成直接兼容。

## 兼容矩阵设计

矩阵固定包含：

- A：当前 `langgraph==0.2.45`；
- B：最低修复 `langgraph==1.0.10`；
- C：执行时 PyPI 最新稳定版。

每组分别验证 `pip --dry-run --report`、`pip check`、导入、图编译、自定义 Checkpointer 协议、历史 fixture 和现有测试。临时虚拟环境位于系统临时目录，不修改仓库依赖文件。

## 信任边界

重点检查：

- Agent 使用的 MySQL 账号及其表权限；
- 数据库宿主机端口是否暴露；
- 哪些应用、脚本和运维工具能写 Checkpoint；
- HTTP API 是否接受原始 Checkpoint 字节；
- 测试脚本是否可能因环境变量误连非测试数据库。

本阶段只记录风险和后续控制，不修改数据库账号、Compose 网络或脚本行为。

## 输出

- [安全公告评估报告](../../security/LangGraph安全公告评估报告.md)
- [依赖兼容矩阵](../../security/LangGraph依赖兼容矩阵.md)
- [实施计划](../plans/2026-08-05-agent-langgraph-advisory-assessment.md)
- `copilot-agent-service/tests/security/test_langgraph_advisory.py`
- `copilot-agent-service/tests/fixtures/langgraph-0.2.45/checkpoints.json`

## 非目标

版本升级、自定义 Checkpointer 迁移、历史数据迁移、最小权限数据库账号和 `interrupt()` API 迁移必须在后续独立 PR 完成。
