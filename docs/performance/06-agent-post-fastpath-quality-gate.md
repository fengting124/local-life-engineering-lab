# Agent Fast Path 性能日收口与质量门禁

- Status: Active
- Type: Reference
- Owners: Agent maintainers
- Last verified: 2026-08-14
- Source of truth: ignored one-shot artifact, Docker Lite structured logs, tool audit, and the targeted regression suite
- Runtime baseline: `main@f2e54bd991d3c41db3604ec717d57923ef64d247`
- Environment: Docker Lite, DeepSeek V4 Flash, concurrency 1
- Result: **PARTIAL**

## 结论

PR #39、#40 和 #41 已依次以 merge commit 合入 `main`。三个结构化业务查询的
中位延迟下降 `97.9%-99.0%`，工具调用、参数和事实保持正确，模型调用与 Token 均降为
0。受控单工具 RAG 的重复派发也已消除，并保持 ToolPolicy、RBAC、商家隔离和真实检索。

两项优化进入 `main` 后，按计划执行了一次且仅一次固定 24 Case x 2 基线。该次运行
没有重试或挑选结果：P50 从 `9.083s` 降至 `0.352s`，P95 从 `21.508s` 降至
`13.712s`，但 Task completion 和 Final facts 均为 `44/48`，没有通过 `48/48`
质量硬门。因此本次结果不替换上一版正式 48/48 质量基线，也不发布为 PASS。

## 合并记录

| PR | Merge SHA | 内容 |
| --- | --- | --- |
| #39 | `566c7fe` | 分阶段延迟、模型调用和 Token 可观测性 |
| #40 | `e1f9914` | 订单、支付和发券状态受控零 LLM 派发 |
| #41 | `f2e54bd` | 单工具知识检索受控派发和权限隔离 |

## 结构化查询 Before / After

| 场景 | PR #39 固定观测 | PR #40 Docker Lite | 降幅 | LLM / Token |
| --- | ---: | ---: | ---: | --- |
| 单订单查询 | 14,306ms | 136ms | 99.0% | 2 / 1,439 -> 0 / 0 |
| 支付状态诊断 | 9,125ms | 195ms | 97.9% | 2 / 1,229 -> 0 / 0 |
| 优惠券状态诊断 | 15,383ms | 176ms | 98.9% | 2 / 1,281 -> 0 / 0 |

每个 After 数字均为 3 次串行真实请求的中位数。标准 `AIMessage(tool_calls=[...])`
仍进入原 `tool_node`，没有直接调用 MCP；工具审计分别证明 `query_order`、
`query_order -> query_payment` 和 `query_order -> query_coupon_issue_log` 的实际轨迹。

## RAG 受控派发

| 场景 | 结果 | 工具 / RAG / LLM |
| --- | --- | --- |
| 公开政策 x3 | 3/3 completed | 每次 1 / 1 / 1 |
| 无命中 x2 | 2/2 not_found | 每次 1 / 1 / 0 |
| 目标商家私有 x2 | 2/2 completed | 每次 1 / 1 / 1 |
| 其他商家反向探针 x1 | 1/1 not_found | 1 / 1 / 0 |
| CS 权限负例 x2 | 2/2 permission_denied | 0 / 0 / 0 |

受控单工具 `knowledge_search` 的重复调用和 `internal_error` 均为 0。公开政策中位总
延迟仍为 `12.862s`，其中 RAG 中位 `2.395s`、证据合成 LLM 中位 `10.548s`；本轮
没有修改检索、Reranker、Prompt 或模型。

## 唯一 24 x 2 结果

脱敏原始产物保留在被 Git 忽略的
`artifacts/performance/agent-post-fastpath-20260814-041547/`，没有覆盖历史产物。

| 指标 | 上一版正式基线 | 本次唯一观测 | 门禁 |
| --- | ---: | ---: | --- |
| Transport | 48/48 | 48/48 | 通过 |
| Task completion | 48/48 | 44/48 | **失败** |
| First tool | 48/48 | 48/48 | 通过 |
| Tool argument | 48/48 | 46/48 | 失败 |
| Trajectory | 48/48 | 46/48 | 失败 |
| Final facts | 48/48 | 46/48 | **失败** |
| Permission | 48/48 | 48/48 | 通过 |
| HITL | 48/48 | 48/48 | 通过 |
| Refusal | 48/48 | 48/48 | 通过 |
| P50 | 9,083ms | 352ms | 仅观测 |
| P95 | 21,508ms | 13,712ms | 仅观测 |
| P99 | 25,594ms | 20,953ms | 仅观测 |

本次 48 个请求共记录 36 次真实 LLM 调用、输入 `32,859` Token、输出 `5,204`
Token，合计 `38,063`。上一版 48/48 基线早于本轮 usage 埋点，无法用同口径给出
全套调用和 Token 总数；不得用估算值伪造 before/after。三个结构化场景已有同口径
比较，均从每请求 2 次 LLM 降为 0。

## 失败矩阵与处理

| Case | 次数 | 真实结果 | 分类与处理 |
| --- | ---: | --- | --- |
| 21 | 2/2 | `query_order -> not_found` | 新确定性代码只提取了字母数字订单号的数字尾部；已做最小修复和定向验证 |
| 32 | 1/2 | `knowledge_search` 重复两次后 `internal_error` | 多工具 `knowledge_search -> coupon_policy_lookup` 路径的模型随机派发；不属于 PR #41 单工具白名单 |
| 37 | 1/2 | `knowledge_search` 重复两次后 `internal_error` | 与 Case 32 同类；本轮没有扩产品路由或修改 Eval 迎合结果 |

Case 21 的修复统一复用一个订单号提取器，保留 12-32 位数字或字母数字订单号的完整
大小写，再用既有 SHA-256 请求绑定验证；没有 Case-ID 特判。修复后同一正式合同输入
定向运行 2 次，均为：

- `query_order -> query_payment`，每个工具恰好一次；
- 两个工具的 `order_id` 都是完整字母数字目标；
- `completed`，最终事实为订单已取消、支付失败；
- `196ms / 210ms`，LLM 0、Token 0；
- 审批 0、退款/补券执行 0。

这两次定向验证只证明确定性 Case 21 根因已修复，不能把整套质量结果改写成 48/48。
Case 32/37 仍需后续在不扩大权限、不提高预算且不修改 Eval 的前提下单独确定产品路由；
本性能日不再运行第二套 24 x 2。

## 回归门禁

| 验证 | 结果 |
| --- | --- |
| Router + nodes 定向回归 | 215 passed |
| Agent full suite | 824 passed |
| Coverage | 81.26% |
| Mutation | 857 / 1202 killed，71.3%，other=0 |
| 标准 Agent Docker build | passed |
| 运行时源码 SHA-256 | 与 worktree 一致 |
| Docker Lite | MySQL、Redis、Server、Copilot、Agent、Embedding、Reranker healthy |
| Tool audit | Case 21 每轮 2 条 success，参数完整 |
| High-risk execution | 0 |

## 后续优先级

1. 先处理 Case 32/37 的多工具知识政策路由，保持 PR #41 的单工具边界和现有权限。
2. 质量重新建立可信门禁后，再评估补偿券绑定配置管理 API；本轮不提前实施。
3. SSE 重复 `final_answer` 是既有独立问题，不影响本次数据库工具次数，但应另开小 PR。

本轮没有触碰 Milvus、RAG 算法、模型、Prompt、HITL、Checkpoint、RBAC、工具预算、
Eval 合同或 Java/MCP 接口。最终结论为 **PARTIAL，BLOCKING FINDINGS=2**：固定质量门
未通过，以及多工具知识路由仍存在两次随机重复派发。
