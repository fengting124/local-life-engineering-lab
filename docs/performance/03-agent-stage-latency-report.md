# Agent 分阶段延迟基线

- Status: Active
- Type: Reference
- Owners: Agent maintainers
- Last verified: 2026-08-13
- Source of truth: `scripts/profile-agent-latency.py` and the ignored one-shot Docker artifact

## 结论

本轮只做观测，不修改 Prompt、路由、工具权限、HITL、RAG 或评测合同。基于
Docker Lite、DeepSeek V4 Flash、并发 1 的 11 个单次观测，LLM 占全部请求时间
`97.7%`，在受控 ReAct 请求中占 `98.1%`。20 秒级长尾首先应减少不必要的模型
调用，而不是优化 MySQL、MCP 或 Checkpoint。

Fast Path 的今日/本月指标分别为 `152ms` 和 `64ms`，均为 `0` 次 LLM。相对地，
两次 LLM 的结构化查询为 `9.1s-15.4s`。本轮未运行
`24x2`；结果用于选择下一项优化，不代表容量、稳定分位数或质量提升。

## 运行信息

| 项目 | 值 |
| --- | --- |
| 基线 main | `98cba51c4e658c9e0262aa1251a062d6257b89ac` |
| 运行时代码 | `8a96bb3` |
| Agent 镜像 | `sha256:b6f75ed679dbb783669edffc850bb6bc7944149fd183515e4077814c7f0d73f1` |
| Provider / Model | `deepseek / deepseek-v4-flash` |
| 环境 | Docker Lite，MySQL/Redis/Server/Copilot/Agent/Embedding/Reranker healthy |
| 时间 | 2026-08-13 23:47 CST |
| 策略 | concurrency=1；每个场景一次；替代一次存在埋点缺陷的整组 profile |

“今日”和“本月”是同一经营指标类别的两个 Fast Path 控制样本，因此需求中的
约 10 类场景实际产生 11 行观测。首次 profile 使用 `d8ae0e3`，独立审查确认其
`graph.total` 边界、Fast Path 工具计数和缺日志处理不满足最终合同，整组数据作废；
本表是修复后 `8a96bb3` 的唯一替代 profile，不从两组结果中择优。

## 场景结果

| 场景 | 结果 / 终态 | Total | LLM | Tool | Graph overhead | LLM calls | Tool calls | Tokens |
| --- | --- | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 今日经营指标 | PASS / fast_path | 152ms | 0ms | 89ms | 0ms | 0 | 1 | 0 |
| 本月经营指标 | PASS / fast_path | 64ms | 0ms | 20ms | 0ms | 0 | 1 | 0 |
| 单订单查询 | PASS / completed | 14,306ms | 13,858ms | 26ms | 373ms | 2 | 1 | 1,439 |
| 合法但不存在订单 | PASS / not_found | 8,683ms | 8,575ms | 21ms | 62ms | 1 | 1 | 737 |
| 支付诊断 | PASS / completed | 9,125ms | 8,972ms | 19ms | 109ms | 2 | 1 | 1,229 |
| 优惠券诊断 | PASS / completed | 15,383ms | 15,172ms | 20ms | 164ms | 2 | 1 | 1,281 |
| 优惠券根因 | PASS / completed | 12,762ms | 12,592ms | 40ms | 100ms | 2 | 2 | 1,694 |
| RAG 规则查询 | FAIL / internal_error | 7,726ms | 7,634ms | 0ms | 62ms | 1 | 2 attempted | 787 |
| 活动草案 | PASS / completed | 80ms | 0ms | 0ms | 52ms | 0 | 0 | 0 |
| 明确金额退款 | PASS / pending_approval | 7,036ms | 6,906ms | 22ms | 68ms | 1 | 2 | 733 |
| 真实补偿路由 | PASS / pending_approval | 2,707ms | 2,540ms | 39ms | 90ms | 1 | 3 | 736 |

Token 合计为 `8,636`（输入 `7,876`、输出 `760`），12 次模型调用。Token
最多的是优惠券根因 `1,694`，其次是单订单查询 `1,439`、优惠券诊断 `1,281`。
所有模型调用均返回可靠 usage，本轮没有把缺失 usage 伪造为零。

## 阶段占比

11 次请求总计 `78,024ms`：LLM `76,249ms`（`97.7%`）、工具 `296ms`
（`0.4%`）、session `169ms`（`0.2%`）、list-tools `25ms`（`<0.1%`）、
HITL prepare `19ms`（`<0.1%`）、graph overhead `1,080ms`（`1.4%`）。

1. **P95/P99 是否主要由 LLM 造成：** 是。虽然本轮单样本不能重新估计分位数，
   但受控请求的 LLM 占比为 `93.8%-98.8%`，足以定位既有长尾的首要来源。
2. **调用次数最多：** 单订单查询、支付诊断、优惠券诊断和优惠券根因均为 2 次。
3. **Token 最多：** 优惠券根因、单订单查询、优惠券诊断。
4. **Fast Path 差异：** `64-152ms` 且 0 LLM；普通两次 LLM 路径 `9.1-15.4s`。
5. **list-tools 固定开销：** 首个 ReAct 缓存未命中为 `25ms`；后续缓存命中低于
   毫秒日志精度，不是显著瓶颈。
6. **RAG 是否显著：** 未证明。该场景在模型提出两个同名 `knowledge_search` 后被
   受控批处理门禁 fail closed，实际 RAG span 为 0；不得把这次失败解释成 RAG 性能。
7. **Graph/runtime 是否继续拆：** 暂不需要。整体为 `1.4%`，慢请求单项最高
   `3.1%`；活动草案的 `65.0%` 只是 80ms 请求中的 52ms 绝对开销。
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
