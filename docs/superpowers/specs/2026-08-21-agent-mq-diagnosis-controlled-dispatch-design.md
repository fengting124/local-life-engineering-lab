# Agent MQ Diagnosis Controlled Dispatch Design

- Status: Approved
- Type: Design
- Owners: Agent maintainers
- Last verified: 2026-08-21
- Base: `main@bc796bbacb404c0f920f22d1ba9d2c9d79eae949`
- Branch: `fix/agent-mq-diagnosis-controlled-dispatch`
- Source of truth: `agent/tool_router.py`, `agent/nodes.py`, `agent/evidence_gate.py`, and the fixed Case 20 contract

## Problem And Evidence

The one-shot post-controlled baseline completed 47/48 runs. Case 20 iteration 1
was the only failure. Runtime evidence showed this exact sequence:

```text
route_task_type=mq_diagnosis
route_required_tools=query_order -> query_mq_dead_letter
query_order=success
route_next_tool=query_mq_dead_letter
DeepSeek proposed query_coupon_issue_log
controlled_tool_batch_rejected
stop_reason=internal_error
```

`TASK_TOOL_PLANS` and Evidence Gate already own the product sequence. The
stochastic proposal came from `llm_node` because `mq_diagnosis` is absent from
`CONTROLLED_DISPATCH_PLANS`. The rejected tool never reached MCP, so the current
ToolPolicy/RBAC boundary worked as designed.

The audit also found that `mq_diagnosis` does not currently persist
`route_target_order_hash`. Adding the plan without adding that existing binding
would fail closed in `_bound_controlled_order_id()`. The route must therefore
bind the one parsed order target with the same SHA-256 mechanism already used
by order, payment, coupon-issue, refund, and compensation routes.

## Controlled Route Coverage Audit

| Task type | Required tools | Current dispatch | LLM selects tools | Arguments deterministic | HITL / conditional gate | This PR |
| --- | --- | --- | --- | --- | --- | --- |
| `analytics` | `shop_metrics_query` | HTTP analytics fast path for supported forms | Graph fallback can | Date parser can determine supported forms | No HITL | No |
| `order_query` | `query_order` | Existing order dispatch | No | Bound order target | None | Preserve |
| `payment_diagnosis` | `query_order -> query_payment` | Existing order dispatch | No | Bound order target/evidence | None | Preserve |
| `coupon_issue` | `query_order -> query_coupon_issue_log` | Existing order dispatch | No | Bound order target/evidence | None | Preserve |
| `coupon_root_cause` | `query_order -> query_coupon_issue_log -> query_mq_dead_letter` | LLM tool calls | Yes | Order arguments are derivable | Evidence Gate conditionally skips MQ; CS escalates | Defer |
| `mq_diagnosis` | `query_order -> query_mq_dead_letter` | LLM tool calls | Yes | Bound order target/evidence | No HITL or branch | Include |
| `knowledge` | `knowledge_search` | Existing knowledge dispatch | No pre-search LLM | Current HumanMessage | None | Preserve |
| `policy_configuration` | `knowledge_search -> coupon_policy_lookup` | Existing policy dispatch | No tool-selection LLM | Current request plus bounded evidence | None | Preserve |
| `refund_action` | `query_order -> execute_refund` | Structured high-risk proposal after evidence | First read can use LLM | Order and explicit amount are bound | HITL | No |
| `compensation_action` | `query_order -> resolve -> issue` | Structured resolver/proposal after evidence | First read can use LLM | Order, amount, and resolver evidence are bound | HITL | No |

`coupon_root_cause` meets several deterministic criteria, but its conditional
MQ continuation and CS escalation add a separate behavior surface. It is not
the failing route and is deliberately excluded from this minimal repair.

## Frozen Contract

Only this exact route shape is added to the existing order-scoped dispatcher:

```text
route_mode=controlled
route_task_type=mq_diagnosis
route_required_tools=(query_order, query_mq_dead_letter)
route_authorized_tools=(query_order, query_mq_dead_letter)
route_target_order_hash=sha256(the one current request order id)
```

The dispatcher emits exactly one standard `AIMessage(tool_calls=[...])` for
the current `route_next_tool`. For `query_order`, `order_id` is recovered from
the latest current `HumanMessage` only when it matches the stored hash. For
`query_mq_dead_letter`, it is recovered from successful canonical order
evidence and the matching raw tool result. Alphanumeric order IDs remain
byte-for-byte intact.

Execution remains:

```text
AIMessage(tool_calls=[...])
  -> existing tool_node
  -> tool budget
  -> controlled batch check
  -> request binding
  -> HITL check (unchanged and not used here)
  -> ToolPolicy / RBAC / audit / metrics / MCP
  -> existing Evidence Gate
  -> deterministic EvidenceAnswer
```

The dispatcher never calls `McpClient` directly and never decides the next
tool. Evidence Gate remains the only owner of progression and retry/terminal
semantics.

## Fail-Closed Behavior

- Missing, multiple, stale, malformed, or hash-mismatched order targets stop as
  `internal_error`; the LLM does not receive a chance to repair the binding.
- Exact plan, authorization, current next tool, and routed catalog membership
  are required. Supersets, subsets, or wrong next tools are rejected.
- `query_order` `not_found` is terminal and cannot advance to MQ.
- Existing first `parameter_error`/`timeout` retry and second terminal behavior
  remains owned by Evidence Gate.
- Tool unavailability remains `internal_error`.
- ToolPolicy, RBAC, budgets, audit, metrics, Prompt, model, Eval, HITL, RAG,
  Checkpointer, MCP, and Java contracts do not change.

## Verification

RED must prove the current route invokes the LLM and can be rejected when the
model proposes the coupon tool. GREEN must prove both MQ steps emit one bound
standard ToolCall with zero tool-selection LLM calls. Focused tests cover
binding, alphanumeric IDs, malformed route state, not-found/retry/permission,
tool unavailability, policy/budget enforcement, and all existing deterministic
routes.

Docker Lite then runs Case 20 ten times plus payment, coupon, policy, knowledge,
permission, refund-proposal, and compensation-proposal controls. High-risk
controls stop at `PENDING`; no approval is granted and no side effect executes.
The fixed 24x2 baseline is authorized exactly once only after merge and green
`main` CI.
