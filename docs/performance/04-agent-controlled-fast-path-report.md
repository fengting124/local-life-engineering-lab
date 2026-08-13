# Agent 受控查询 Fast Path 报告

- Status: Active
- Type: Reference
- Owners: Agent maintainers
- Last verified: 2026-08-14
- Source of truth: `agent/nodes.py`, `scripts/profile-agent-latency.py`, Docker Lite tool audit, and the ignored one-shot artifact
- Runtime source: `9b57543`
- Environment: Docker Lite, DeepSeek V4 Flash configuration, concurrency 1

## 结论

单订单查询、支付状态诊断和优惠券状态诊断现在都由受控路由构造标准
`AIMessage(tool_calls=[...])`，随后进入原有 `tool_node`。本轮没有直接调用 MCP，
ToolPolicy、RBAC、参数绑定、预算、审计、Evidence Gate 和最终事实合成均保持在原链路。

每个场景连续运行 3 次，共 9 次。9 次均为 `completed`，模型调用和 Token 均为
0；工具、顺序、参数和最终事实均符合合同。三组中位延迟相对 PR #39 固定观测
下降 `97.9%-99.0%`，超过 80% 目标。

## Docker 证据

| 项目 | 值 |
| --- | --- |
| Agent image | `sha256:c97b55bdc708972d65c3d47e143c3272cb4a8a67c7717c902e05ea621463e3da` |
| Services | MySQL、Redis、Server、Copilot、Agent、Embedding、Reranker healthy |
| Artifact | ignored `artifacts/performance/agent-controlled-fast-path-20260813T180925Z` |
| Observations | 3 scenarios x 3, concurrency 1 |
| High-risk execution | 0 |

第一次 `20260813T180356Z` 预检发现 PR #39 采集文本中的“显示已支付”“没有发券”
没有命中冻结的支付/优惠券分类词，9 行实际均为 `order_query`。该 artifact 被保留为
无效预检，不参与性能或质量结论。采集器随后增加了路由合同测试，并只替换为已有产品
语义支持的等价表达；`classify_request` 本身未修改。

## 结果

| 场景 | 3 次延迟 | 中位数 | PR #39 | 降幅 | LLM / Token | 工具轨迹 |
| --- | --- | ---: | ---: | ---: | --- | --- |
| 单订单查询 | 163 / 132 / 136ms | 136ms | 14,306ms | 99.0% | 0 / 0 | `query_order` |
| 支付状态诊断 | 222 / 195 / 187ms | 195ms | 9,125ms | 97.9% | 0 / 0 | `query_order -> query_payment` |
| 优惠券状态诊断 | 176 / 175 / 176ms | 176ms | 15,383ms | 98.9% | 0 / 0 | `query_order -> query_coupon_issue_log` |

PR #39 的支付和优惠券行实际只调用过一次 `query_order`，所以历史数字不能作为同语义
两工具质量对照；它们仍能说明两次无决策价值 LLM 调用的延迟成本。本轮工具审计独立证明
了完整两工具产品路径和参数绑定。

## 业务与安全核验

| 核验项 | 结果 |
| --- | --- |
| Route types | `order_query`、`payment_diagnosis`、`coupon_issue` 各 3/3 |
| Tool status | `query_order` 9/9、`query_payment` 3/3、`query_coupon_issue_log` 3/3 success |
| Tool arguments | 每次 `order_id` 均等于对应受控请求目标 |
| 单订单事实 | 3/3 输出“订单状态：已支付” |
| 支付事实 | 3/3 输出“订单状态：待支付；支付状态：支付成功” |
| 优惠券事实 | 3/3 输出订单已支付、无发券记录、优惠券未使用 |
| Unknown/protocol errors | 0 |
| Refund/compensation execution | 0 |

确定性回归测试还覆盖：非法订单澄清、合法订单不存在后终止、CS/admin 权限不变、
ToolPolicy 拒绝未授权调用、目标参数哈希不一致 fail closed、MCP 工具缺失、工具异常、
复杂问题回退原 ReAct，以及退款、补偿和 RAG 不进入该白名单。

## 限制

- 这是每场景 3 次的定向验证，不是容量、P95/P99 或长稳测试。
- 本轮没有运行 11 场景 profile，也没有运行 24x2 DeepSeek 基线。
- 白名单只有三个固定产品路径；复杂、模糊和需要语言推理的请求继续使用原 ReAct。
- 原始 artifact 不提交，避免持久化 Prompt、回答、工具参数、业务 ID 和 trace ID。
