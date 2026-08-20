# Agent 策略配置受控派发报告

- Status: Active
- Type: Reference
- Owners: Agent maintainers
- Last verified: 2026-08-20
- Source of truth: `agent/nodes.py`, Docker Lite structured events, MySQL tool audit, and the fixed Eval contracts
- Runtime source: `cc4ecf05a3c77756b5596108a6e4f8d5c05e5300`
- Environment: Docker Lite, DeepSeek V4 Flash, concurrency 1

## 结论

`policy_configuration` 现在按既有产品合同确定性执行：

```text
knowledge_search(current HumanMessage)
-> coupon_policy_lookup({})
```

Agent 只为两个精确白名单 plan 构造标准 `AIMessage(tool_calls=[...])`，调用仍经过
`tool_node`、ToolPolicy、RBAC、工具预算、审计和 Evidence Gate。它没有直接调用 RAG
或 MCP，没有改变 Prompt、EvalCase、评分、权限、工具 schema 或商家范围。

Case 32 和 Case 37 各运行 5 次，共 10 次，全部完成固定两工具轨迹。历史偶发的第二次
`knowledge_search` 和 `invalid_controlled_tool_batch` 均为 0。

## Docker 证据

| 项目 | 值 |
| --- | --- |
| Agent image | `sha256:12868f7ccbf4367d0267e1ef8a4b9650dfeef383e789463a475ec0e1ac93d764` |
| Local/container `nodes.py` SHA-256 | `b7389666710cd7a8ca52705095ea4af5f2df555c44cec6c79399f9b232415cd6` / same |
| Health | `healthy`, restart count `0` |
| Model | `deepseek-v4-flash` |
| Milvus Lite | `/app/data/local_life_kb.db`, existing persistent volume loaded successfully |
| Validation window | 2026-08-20 15:19:30-15:22:20 UTC |

镜像由本分支标准 `copilot-agent-service/Dockerfile` 构建，`COPY` 当前 worktree 源码层
重新执行。容器重建只影响 Agent；MySQL、Redis、Java 服务和数据卷未清理。

## 场景结果

| 场景 | 结果 | 工具轨迹 | 延迟 | LLM / Token |
| --- | --- | --- | --- | --- |
| Case 32 x5 | 5/5 completed，全指标 100% | 每次 `knowledge_search -> coupon_policy_lookup` | 8,989-12,571 ms；P50 11,294 ms | 每次 1 次最终合成 LLM |
| Case 37 x5 | 5/5 completed，全指标 100% | 每次 `knowledge_search -> coupon_policy_lookup` | 12,251-13,190 ms；P50 13,021 ms | 每次 1 次最终合成 LLM |
| Case 31 公开知识 x2 | 2/2 completed，全指标 100% | 每次 `knowledge_search` | 10,028 / 10,030 ms | 2 次 LLM，3,176 tokens |
| CS 权限负例 x2 | 2/2 permission_denied，全指标 100% | 0 工具、0 RAG | 90 / 98 ms | 0 LLM，0 tokens |

10 个策略配置会话共 10 次最终答案合成 LLM、16,535 session tokens，平均 1,653.5。
输入/输出 Token 当前没有可靠拆分，因此不推算。这个 PR 删除的是工具选择阶段的随机性，
不修改既有答案合成；约 9-13 秒的延迟仍主要属于 RAG 和最终 LLM，留给独立性能工作。

## 审计结果

| 门禁 | 结果 |
| --- | --- |
| 每个策略会话原生 `knowledge_search` 次数 | 10/10 恰好 1 次 |
| 每个策略会话 `coupon_policy_lookup` 次数 | 10/10 恰好 1 次且 success |
| `coupon_policy_lookup` 参数 | 10/10 为 `{}`；商家范围来自认证上下文 |
| duplicate `knowledge_search` | 0 |
| `controlled_tool_batch_rejected` | 0 |
| 工具失败 | 0 |
| unknown tool / protocol error | 0 |
| HITL approval | 0 |
| 审批前高风险工具执行 | 0 |

原生 RAG 工具不写 `tool_audit_log`，其一次性调用由 `agent_event` 的 `tool_call` 和
`tool_result` 交叉核验；MCP 工具由 `tool_audit_log` 核验。评测器合并两类证据后得到
完整两工具轨迹。

## 质量门禁

| 门禁 | 结果 |
| --- | --- |
| Agent 完整行为测试 | 848/848 passed |
| Agent 覆盖率 | 81.60%（门槛 45%） |
| Mutation | 857 killed / 345 survived / 0 other，共 1,202；kill rate 71.3%（门槛 50%） |
| Checkpointer / HITL 定向测试 | 53/53 passed |
| Testcontainers 迁移与安全测试 | 11/11 passed |

完整 Agent 测试在隔离的 Python 3.11 CI 镜像中运行。其中主测试集为 847 passed，
Embedding 镜像中的模型依赖测试为 1 passed；两者合计 848/848。宿主机旧虚拟环境仍是
Python 3.10 和 LangGraph 0.2.45，不代表当前生产依赖，因此未用它声明门禁结果。

独立合并前复审发现并关闭了三个 fail-closed 边界：第二步现在同样要求合法当前消息，
知识证据必须是 Evidence Gate 的 canonical 形状，畸形 MCP 工具目录统一按不可用处理。
新增边界测试先得到 9 failed / 5 passed 的 RED 证据，修复后为 14/14 passed；相关节点、
路由和 Evidence Gate 定向测试为 306/306 passed。

## 限制

- 这是 14 次定向功能验收，不是容量、长稳或完整 `24x2` 基线。
- 原始请求、回答、业务 ID、session/trace ID 和工具 payload 未写入仓库。
- 真实运行没有为了替换不利结果而重跑。
- 完整固定 `24x2` 只能在本修复合并 `main` 后执行一次。
