# Agent MQ Diagnosis Controlled Dispatch Report

- Status: Verified
- Type: Reference
- Owners: Agent maintainers
- Last verified: 2026-08-21
- Source of truth: fixed Eval contracts, Docker Lite structured traces, approval and side-effect ledger audit
- Runtime `nodes.py` SHA-256: `92f4c91d2a9fe7f3065559e25baedbc0ef696802493e6286758169f5b3b36e00`
- Runtime `tool_router.py` SHA-256: `e8adc3e00d92c4496c445e9cea8f9c801ebcde5abd4685c909b82ca18bb070d1`
- Agent image: `sha256:d82103f7b1a625db56b3ee0f765ff2fadee1fa6ab8ed01ea78640f0010f31006`
- Provider/model: `deepseek` / `deepseek-v4-flash`

## Result

The fixed Case 20 contract was run ten times at concurrency 1 against the
Docker Lite stack. All runs completed with the exact trajectory
`query_order -> query_mq_dead_letter`; both tools executed exactly once per
run. No LLM call was made to select a tool or synthesize the final answer.

| Gate | Result |
| --- | ---: |
| Task completion | 10/10 |
| First tool / arguments / trajectory / final facts | 10/10 each |
| `query_order` executions | 10 |
| `query_mq_dead_letter` executions | 10 |
| `query_coupon_issue_log` executions | 0 |
| Controlled batch rejections | 0 |
| LLM calls | 0 |
| Input / output / total tokens | 0 / 0 / 0 |
| Agent request duration | 142-338 ms |
| Eval client latency | 151-361 ms; P50 166 ms |

The preceding fixed baseline had two Case 20 observations at 9,690 ms and
9,924 ms. Its failed first observation made two model calls, consumed 1,613
tokens, and proposed `query_coupon_issue_log` after a successful order query.
The existing controlled enforcement rejected that proposal before MCP. The
new path removes that model-only dispatch decision while retaining the same
tool and evidence boundaries.

## Controls

The unchanged fixed Eval contracts produced the following results:

| Control | Runs | Result |
| --- | ---: | --- |
| Payment diagnosis | 2 | 2/2 PASS; `query_order -> query_payment` |
| Coupon diagnosis | 2 | 2/2 PASS; `query_order -> query_coupon_issue_log` |
| Policy configuration Case 32 | 2 | 2/2 PASS |
| Policy configuration Case 37 | 2 | 2/2 PASS |
| Public knowledge Case 31 | 2 | 2/2 PASS |
| CS permission-negative Case 17 | 2 | 2/2 PASS; `permission_denied` |
| Refund proposal | 1 | `PENDING`; no execution |
| Compensation proposal | 1 | `PENDING`; no execution |

Both high-risk proposals created one `PENDING` approval. Their `execution_id`
and `executed_at` remained null, no new `side_effect_ledger` row was written,
and pre-approval `execute_refund` / `issue_compensation_coupon` execution was
zero.

## Deterministic Gates

- Focused route, node, Evidence Gate, graph, answer, E2E, and policy tests:
  `408 passed`.
- Full Agent suite: `868 passed`.
- Coverage: `81.66%` with the required `45%` gate.
- Mutation: `859/1204` killed, `71.3%`, `other=0` with the required `50%` gate.
- Documentation and Compose recovery checks: PASS.
- Container and worktree `nodes.py` / `tool_router.py` SHA-256 values matched.

## Scope

Only `mq_diagnosis` was added to the existing controlled dispatch whitelist,
and its order target now uses the existing SHA-256 request binding. The route
still emits a standard `AIMessage(tool_calls=[...])` and enters the existing
`tool_node`; ToolPolicy, RBAC, budget, audit, metrics, and Evidence Gate remain
in force. `coupon_root_cause`, Prompt, Eval, HITL, RAG, MCP, and Java contracts
were not changed.

The complete 24x2 baseline was not run on this branch. After PR #44 merged as
`18542a6` and `main` CI passed, the authorized baseline was run exactly once.
All quality dimensions passed 48/48, the failure matrix was empty, and P50 / P95 /
P99 were 185 / 12,562 / 13,066 ms. The tracked aggregate is published in
`baseline-summary.json`; the raw artifact remains Git-ignored.
