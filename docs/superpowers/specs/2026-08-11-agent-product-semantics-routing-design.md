# Agent Product Semantics Routing Design

- Status: Approved
- Type: Design
- Owners: Agent and MCP maintainers
- Last verified: 2026-08-11
- Source of truth: formal post-PR #27 artifact, current Router, Eval, MCP schema and Mapper
- Baseline: `main@2f44cc25b4f2e5dba497c14dbb18c88bab9e7077`
- Scope: Cases 3, 17, 49, and the confirmed Case 19 product/evaluation conflict

## Goal

Align deterministic routing and evaluation contracts with four approved product rules without changing RBAC, HITL, EvidenceAnswer, RAG, checkpointing, prompts, graph topology, or tool budgets.

## Decisions

### Month-to-date analytics

For a merchant request containing `本月`, `这个月`, or `这月` and a supported metric such as GMV, sales, revenue, or order count, the Agent resolves one inclusive range:

```text
start_date = first day of the current business month
end_date = today
```

The existing Fast Path performs this resolution with an injectable `today` value and calls `shop_metrics_query` once. The Copilot tool accepts either the existing single `date` argument or a complete `start_date`/`end_date` pair. Its mapper performs one range aggregate query; it never loops over individual days. The ReAct classifier also treats month-to-date as supported so a Fast Path transport failure does not incorrectly request a concrete date.

### CS coupon root-cause escalation

`TOOL_ROLE_MAP` remains the sole permission source. For CS, the deterministic plan remains the full business requirement, while the authorized plan contains only `query_order`. After the order evidence succeeds, Evidence Gate terminates with `permission_denied` before either admin-only tool can execute. The final response includes the bounded order status already established and explains that deeper coupon/MQ evidence requires administrator escalation.

The Case 17 contract therefore expects only `query_order`, forbids `query_coupon_issue_log` and `query_mq_dead_letter`, expects `permission_denied`, and requires both retained order evidence and an escalation explanation.

### Valid but nonexistent order

The missing-order fixture becomes a numeric, production-format order number and the fixture loader proves its count is zero before evaluation. A valid missing ID follows `query_order -> not_found -> terminal`; malformed IDs remain `clarification -> zero tools`.

### Refund without an amount

Case 19 follows the already-approved production behavior: missing amount means `clarification`, zero tools, zero approvals, and zero high-risk execution. The Router and HITL implementation remain unchanged. The evaluation case and outcome scoring are corrected so `clarification` is a first-class terminal outcome rather than an apparent HITL failure.

## Component Changes

| Component | Responsibility | Minimal change |
| --- | --- | --- |
| `api/chat.py` | Deterministic analytics Fast Path | Parse month-to-date with injected `today`; issue one range call |
| `agent/tool_router.py` | Candidate tool selection | Treat month-to-date as supported analytics |
| `agent/nodes.py` | Terminal product response | Preserve bounded order status in CS permission escalation; classify clarification terminal state |
| `evals/*` | Product contracts and fixtures | Correct Cases 17/19/49 and validate terminal outcomes |
| `ShopMetricsQueryTool` | MCP schema and date validation | Accept one date or one inclusive range |
| `CopilotOrderMapper` | Metrics aggregate | Replace single-day predicate with one inclusive range query |
| `CampaignDraftGenerateTool` | Existing mapper caller | Pass the same date as range start and end |

## Error Semantics

| Condition | Stop reason | Tool calls |
| --- | --- | ---: |
| Month metric with valid month phrase | `fast_path` | one `shop_metrics_query` |
| Analytics without metric | `clarification` | zero |
| CS root-cause after order evidence | `permission_denied` | one `query_order` |
| Valid missing order | `not_found` | one `query_order` |
| Malformed order number | `clarification` | zero |
| Refund without amount | `clarification` | zero |
| Refund with explicit valid amount | existing `pending_approval` path | unchanged |

## Non-Goals

- No Case 37 change.
- No RBAC, ToolPolicy, HITL, EvidenceAnswer, checkpoint, RAG, Prompt, graph, budget, or dependency changes.
- No trend, group-by, daily loop, BI endpoint, Router directory split, or `nodes.py` split.
- No full 24-case by two-run DeepSeek baseline in this PR.

## Verification

Tests proceed RED then GREEN for product behavior. Final verification includes the Agent full suite with coverage and mutation, Copilot full tests if the metrics contract changes, Docker Lite smoke, docs checks, and one targeted DeepSeek run set: Cases 3, 17, and 49 three times each at concurrency one. Case 19 uses deterministic tests, with at most one positive and one negative real-model control if needed.
