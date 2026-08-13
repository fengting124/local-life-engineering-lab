# Agent 分阶段延迟基线

- Status: Active
- Type: Reference
- Owners: Agent maintainers
- Last verified: 2026-08-13
- Source of truth: `scripts/profile-agent-latency.py` and the ignored one-shot Docker artifact

## 结论

本轮只做观测，不修改 Prompt、路由、工具权限、HITL、RAG 或评测合同。基于
Docker Lite、DeepSeek V4 Flash、并发 1 的 11 个单次观测，LLM 占全部请求时间
`97.6%`，在受控 ReAct 请求中占 `97.9%`。20 秒级长尾首先应减少不必要的模型
调用，而不是优化 MySQL、MCP 或 Checkpoint。

Fast Path 的今日/本月指标分别为 `87ms` 和 `97ms`，均为 `0` 次 LLM。相对地，
两次 LLM 的结构化查询为 `9.8s-18.3s`。本轮未重复任何正式场景，也未运行
`24x2`；结果用于选择下一项优化，不代表容量、稳定分位数或质量提升。

## 运行信息

| 项目 | 值 |
| --- | --- |
| 基线 main | `98cba51c4e658c9e0262aa1251a062d6257b89ac` |
| 运行时代码 | `d8ae0e31e4a4b5483cf2fc04c8f161bec3587f91` |
| Agent 镜像 | `sha256:145991d96a7cfd0eefb8a7f9730c422756c150a3e6eb4d882bb24e85d73762ce` |
| Provider / Model | `deepseek / deepseek-v4-flash` |
| 环境 | Docker Lite，MySQL/Redis/Server/Copilot/Agent/Embedding/Reranker healthy |
| 时间 | 2026-08-13 23:16 CST |
| 策略 | concurrency=1；每个场景一次；无正式场景重跑 |

“今日”和“本月”是同一经营指标类别的两个 Fast Path 控制样本，因此需求中的
约 10 类场景实际产生 11 行观测。

## 场景结果

| 场景 | 结果 / 终态 | Total | LLM | Tool | Graph overhead | LLM calls | Tool calls | Tokens |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 今日经营指标 | PASS / fast_path | 87ms | 0ms | 24ms | 0ms | 0 | 0 | 0 |
| 本月经营指标 | PASS / fast_path | 97ms | 0ms | 36ms | 0ms | 0 | 0 | 0 |
| 单订单查询 | PASS / completed | 17,128ms | 16,868ms | 94ms | 107ms | 2 | 1 | 1,234 |
| 合法但不存在订单 | PASS / not_found | 2,247ms | 2,102ms | 32ms | 94ms | 1 | 1 | 737 |
| 支付诊断 | PASS / completed | 9,940ms | 9,649ms | 24ms | 243ms | 2 | 1 | 1,265 |
| 优惠券诊断 | PASS / completed | 18,334ms | 18,131ms | 25ms | 159ms | 2 | 1 | 1,298 |
| 优惠券根因 | PASS / completed | 9,849ms | 9,656ms | 77ms | 97ms | 2 | 2 | 1,694 |
| RAG 规则查询 | FAIL / internal_error | 6,225ms | 6,136ms | 0ms | 69ms | 1 | 2 attempted | 786 |
| 活动草案 | PASS / clarification | 70ms | 0ms | 0ms | 51ms | 0 | 0 | 0 |
| 明确金额退款 | PASS / pending_approval | 2,840ms | 2,692ms | 29ms | 87ms | 1 | 2 | 733 |
| 真实补偿路由 | PASS / pending_approval | 6,485ms | 6,294ms | 54ms | 104ms | 1 | 3 | 736 |

Token 合计为 `8,483`（输入 `7,876`、输出 `607`），12 次模型调用。Token
最多的是优惠券根因 `1,694`，其次是优惠券诊断 `1,298`、支付诊断 `1,265`。
所有模型调用均返回可靠 usage，本轮没有把缺失 usage 伪造为零。

## 阶段占比

11 次请求总计 `73,302ms`：LLM `71,528ms`（`97.6%`）、工具 `395ms`
（`0.5%`）、session `199ms`（`0.3%`）、list-tools `38ms`（`0.1%`）、
HITL prepare `33ms`（`<0.1%`）、graph overhead `1,011ms`（`1.4%`）。

1. **P95/P99 是否主要由 LLM 造成：** 是。虽然本轮单样本不能重新估计分位数，
   但全部慢场景的 LLM 占比为 `93.5%-98.9%`，足以定位既有长尾的首要来源。
2. **调用次数最多：** 单订单查询、支付诊断、优惠券诊断和优惠券根因均为 2 次。
3. **Token 最多：** 优惠券根因、优惠券诊断、支付诊断。
4. **Fast Path 差异：** `87-97ms` 且 0 LLM；普通两次 LLM 路径 `9.8-18.3s`。
5. **list-tools 固定开销：** 首个 ReAct 缓存未命中为 `38ms`；后续缓存命中低于
   毫秒日志精度，不是显著瓶颈。
6. **RAG 是否显著：** 未证明。该场景在模型提出两个同名 `knowledge_search` 后被
   受控批处理门禁 fail closed，实际 RAG span 为 0；不得把这次失败解释成 RAG 性能。
7. **Graph/runtime 是否继续拆：** 暂不需要。整体为 `1.4%`，慢请求单项最高
   `4.2%`；活动草案的 `72.9%` 只是 70ms 请求中的 51ms 绝对开销。
8. **下一轮 Fast Path 候选：** 单订单状态查询、支付状态诊断、优惠券状态诊断。
   它们输入和工具合同结构化，且当前主要成本来自 1-2 次 LLM，而非工具执行。

## 安全与限制

- 退款和补偿各创建一条 `PENDING` 审批，`execution_id`、`executed_at` 均为空。
- `tool_audit_log` 中没有 `execute_refund` 或 `issue_compensation_coupon` 执行记录；
  本轮没有批准、退款或发放补偿券。
- RAG 场景的 `controlled_tool_batch_rejected` 是真实失败，留待独立产品/路由工作，
  本 PR 不修复，也未重跑该场景。
- 原始 artifact 位于被 `.gitignore` 排除的 `artifacts/`，不提交 Prompt、回答、
  工具参数/结果、业务 ID、trace ID 或密钥。
- 单次观测不能用于容量、稳定分位数或模型质量结论。

