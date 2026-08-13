# Agent Controlled Fast Path Design

- Status: Approved
- Type: Design
- Owners: Agent maintainers
- Last verified: 2026-08-14
- Source of truth: `copilot-agent-service/agent/nodes.py`, `agent/tool_router.py`, `agent/evidence_gate.py`, and this contract
- Branch: `perf/agent-controlled-fast-path`
- Base: `main@566c7fe886ef552582827ecc82443e7c0cdc68b9`
- Scope: remove LLM calls that only reproduce an already deterministic tool call

## Problem

PR #39 measured two LLM calls for three structured controlled routes even though
the Router had already selected one ordered tool plan and the Evidence Gate
already selected the next tool. The calls add seconds and tokens but make no
remaining product decision.

## Invariants

The graph remains a single LangGraph ReAct graph. Deterministic dispatch emits a
normal `AIMessage(tool_calls=[...])`; `route_after_llm` therefore sends it to the
existing `tool_node`. ToolPolicy, RBAC, budgets, request binding, audit, metrics,
MCP execution and Evidence Gate remain the execution path and security boundary.

The following are frozen: `classify_request` semantics, Prompt, ToolPolicy,
RBAC, Evidence Gate transitions, HITL, RAG, Checkpointer, MCP/Java contracts,
Eval contracts and model configuration.

## Allowlist

| Task type | Deterministic tools, in order |
| --- | --- |
| `order_query` | `query_order` |
| `payment_diagnosis` | `query_order`, `query_payment` |
| `coupon_issue` | `query_order`, `query_coupon_issue_log` |

No other task or tool is eligible. In particular, `coupon_root_cause`, refund,
compensation, knowledge/RAG and ambiguous `general_fallback` routes retain their
existing behavior.

## Argument Binding

The LLM must never recover or choose an order number for this path.

1. `query_order` extracts the only order candidate from the current human
   request and requires its SHA-256 to equal `route_target_order_hash`.
2. Later tools require successful `query_order` evidence and the latest
   `query_order` tool payload.
3. The payload order must match the same route target hash.
4. All three tools receive exactly `{"order_id": <bound order>}`.

An eligible route with missing, ambiguous or mismatched binding fails closed as
`internal_error`; it does not fall back to an LLM that could choose another
target. An ineligible route falls back unchanged to ReAct.

## Tool Discovery

Dispatch occurs only after the existing MCP tool discovery and `ToolRouter`
role filtering. A required tool missing from discovery retains the current
`internal_error` behavior. The dispatch helper never calls `McpClient` itself.

## Lifecycle

```text
classify_request (unchanged)
  -> llm_node discovers and role-filters tools
  -> allowlisted dispatch builds standard AIMessage(tool_calls)
  -> route_after_llm
  -> tool_node (policy, budget, binding, audit, MCP)
  -> Evidence Gate chooses next tool or marks synthesis_only
  -> allowlisted dispatch for the next tool, or EvidenceAnswer
  -> final_node
```

`llm_call_count`, input/output tokens and behavioral `token_count` remain zero
for successful eligible routes. `step_count` still advances for each standard
assistant tool-call message, preserving graph limits and message protocol.

## Failure Matrix

| Condition | Result |
| --- | --- |
| malformed/missing order | existing clarification, zero tools |
| valid order not found | `query_order` once, normal terminal `not_found` |
| role lacks later tool | Evidence Gate permission escalation; no forbidden execution |
| eligible target binding mismatch | fail closed `internal_error`, zero MCP calls |
| discovered tool missing | existing `internal_error`, zero LLM calls |
| tool/MCP error | existing normalized error path; never reported successful |
| unauthorized standard ToolCall | existing ToolPolicy denial |
| ambiguous/complex task | unchanged ReAct fallback |
| refund/compensation | unchanged HITL route, never eligible |

## Verification

RED tests first prove the current three routes invoke the LLM. GREEN tests prove
zero LLM/tokens, exact ordered tool trajectories and complete EvidenceAnswer.
Security regressions cover clarification, not-found, permissions, ToolPolicy,
binding, discovery, tool errors and exclusions.

After deterministic gates, rebuild Docker Lite from current source and run only
the three PR #39 scenarios three times each at concurrency 1. Compare latency,
LLM calls, tokens, tools and final facts against the fixed PR #39 observations.
No 11-scenario profile and no 24x2 baseline are permitted.
