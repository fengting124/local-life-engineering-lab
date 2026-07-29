# Agent Routing Quality Design

- Status: Approved
- Type: Design
- Owners: Project maintainers
- Last verified: 2026-07-26
- Source of truth: `copilot-agent-service/agent/`, PR #23-#25, and the 2026-07-26 DeepSeek baseline
- Scope: `copilot-agent-service`

## 1. Goal

Improve deterministic task routing and stopping in the existing single LangGraph
ReAct graph without weakening production authorization or increasing tool-call
budgets.

The change addresses three measured problems from the real DeepSeek baseline:

1. Keyword-first classification can hide the required tool.
2. Broad candidate sets let the model choose unrelated tools.
3. The runtime has no independent definition of sufficient evidence, so the
   model can answer without evidence or continue after enough evidence exists.

This design does not claim that deterministic routing replaces the model. It
uses controlled routes only for high-confidence, well-understood business
tasks and preserves a bounded ReAct fallback for complete mixed requests.

## 2. Dependency Baseline

The dependency chain was merged in order before this design branch was
created:

```text
PR #23 performance baseline          -> main @ 6c8e086
PR #24 eval contract and classification -> main @ d182118
PR #25 RBAC and tool budgets         -> main @ 60f5e86
```

The implementation branch is created from `main@60f5e86`.

PR #25 established the safety baseline that this design must preserve:

- `TOOL_ROLE_MAP` is the only Python role-permission source.
- Tool visibility and execution authorization are separate controls.
- Every proposed batch is authorized before MCP, RAG, or HITL side effects.
- Per-turn, per-run, per-tool, and identical-signature budgets remain active.
- Canonical call signatures are stored as SHA-256 only.
- Denied batches keep complete ToolMessage pairing.

The latest 48-run real baseline is the comparison point:

| Metric | Baseline |
| --- | ---: |
| Task completion | 45.8% |
| First-tool accuracy | 72.9% |
| Trajectory accuracy | 60.4% |
| Tool-argument accuracy | 99.3% |
| Final-fact accuracy | 72.9% |
| Permission accuracy | 100% |
| HITL accuracy | 95.8% |
| Refusal accuracy | 91.7% |
| End-to-end P95 | 19.584 s |

## 3. Scope

### 3.1 In scope

1. Deterministic task scoring for the current user request.
2. Separation of `clarification` from `general_fallback`.
3. Minimal role-filtered candidate tools per recognized task.
4. A pure Evidence Gate with normalized, non-sensitive facts.
5. A specific next-tool choice for controlled evidence routes; small talk is
   the explicit direct-response, no-tool exception.
6. Deterministic tool stopping after evidence is complete.
7. Narrow Guardrail fixes for explicit cross-merchant access and bulk
   high-risk execution.
8. Unit, integration, mutation, Docker, and real DeepSeek verification.
9. Bind high-risk order and amount proposals to the original user request.

### 3.2 Out of scope

- Model, provider, temperature, Prompt text, or production dependency upgrades.
- Multi-agent routing or a second LangGraph.
- `TOOL_ROLE_MAP` permission changes.
- Tool budget increases or new retry budgets.
- EvalCase, fixture, contract, runner, or scoring changes.
- RAG Pipeline, Milvus, BM25, Reranker, or embedding changes.
- MCP Server, Java service, database, migration, or Docker topology changes.
- Cryptographic approval-payload binding across HITL resume, approval semantics,
  resume flow, or checkpoint design. Request-to-proposal target binding is in
  scope because it must hold before an approval is created.
- Reflection and Auto-Compact redesign.
- Generic Guardrail framework rewrites.

## 4. Current Data Flow

```text
POST /chat
  -> identity headers
  -> input Guardrail
  -> optional analytics Fast Path
  -> initial AgentState
  -> llm_node
       -> MCP tools/list + native knowledge_search
       -> ToolRouter
            -> first keyword decides task type
            -> role filter
            -> broad task filter
            -> raw-text high-risk context filter
       -> bind tools
       -> DeepSeek Flash
  -> route_after_llm
       -> tool_node
            -> execution RBAC
            -> four budget checks
            -> HITL interception
            -> MCP or RAG execution
       -> llm_node
  -> final_node
  -> END
```

The model currently decides both whether evidence is required and whether
enough evidence has been collected.

## 5. Measured Failure Classes

| Failure class | Baseline evidence | Root cause |
| --- | --- | --- |
| Analytics misclassification | A request for today's order count was classified as diagnosis | `订单` wins before aggregate and time features |
| Knowledge misclassification | Campaign, coupon, and refund rule questions lost `knowledge_search` | First keyword wins before question semantics |
| Candidate overexposure | Admin diagnosis called payment, coupon, and MQ tools outside the requested path | Diagnosis exposes four to five tools together |
| Missing evidence obligation | Dynamic and knowledge requests returned without tools | No runtime rule requires external evidence |
| Missing sufficiency rule | Monthly metrics and diagnosis continued after enough evidence | No deterministic evidence-complete state |
| Incomplete high-risk handoff | Refund intent stopped after `query_order` | High-risk visibility depends on raw ToolMessage text |
| Guardrail phrase gap | Cross-merchant and bulk-refund requests ended as normal completion | Existing patterns do not cover both word orders |
| Transport failure | One unrelated request lost the external connection | Not a routing defect |

Production logic must address feature classes, not baseline case IDs or exact
evaluation prompts.

## 6. Component Boundaries

```text
Input Guardrail
  Decides whether a request may enter business routing.

ToolRouter
  Produces a RouteDecision and decides which tool the model can see.

ToolPolicy
  Decides whether a proposed call is authorized and within budgets.

Evidence Gate
  Normalizes execution outcomes, determines sufficiency, and selects next_tool.

Graph Router
  Reads state and chooses LLM, HITL, finalization, or END.
```

The Evidence Gate is a pure module, not a LangGraph node. `tool_node` returns
the evidence state update; conditional routing functions only read state and
must not mutate it.

The primary LangGraph loop remains:

```text
llm_node -> tool_node -> llm_node -> final_node
```

The existing Reflection, Auto-Compact, HITL, retry, and terminal branches stay
in place. This design changes only the conditions that select a tool, advance
evidence, or stop the primary loop.

## 7. Route Decision

The router returns a value object with bounded fields:

```text
RouteDecision
  task_type
  route_mode: controlled | clarification | general_fallback
  confidence
  required_tools
  authorized_tools
  next_tool
  missing_fields
```

The decision is computed from the current user request, then retained in
AgentState for that run. Tool outputs do not reclassify the original intent.
They only update evidence and advance the controlled plan.

`terminal` is an AgentState route mode set by Guardrail, not a RouteDecision
that ToolRouter can produce.

### 7.1 Guardrail before classification

Explicitly unsafe requests terminate before ToolRouter:

- Explicit access to all or other merchants' data.
- Explicit bulk execution of refunds or compensation actions.
- Existing prompt injection, secret disclosure, and HITL bypass patterns.

Knowledge questions remain allowed:

```text
"What is the bulk refund policy?" -> knowledge
"Refund these 100 orders now"     -> guardrail_blocked
```

`refusal` is not a business `task_type`. A blocked request records
`route_mode=terminal` and `stop_reason=guardrail_blocked`; it does not enter
ToolRouter.

### 7.2 Clarification and fallback

`clarification` is selected when required anchors are missing:

- Order task without an order identifier.
- Analytics request without a metric or usable time range.
- Analytics request whose time range is unsupported by the current single-day
  `shop_metrics_query` contract.
- High-risk action without one concrete target.
- A fragment such as "help me check this" with no recoverable subject.

It binds no tools and returns a deterministic request for the missing fields.

`general_fallback` is selected only when the request is complete but does not
fit one controlled route. Typical conditions are:

- Two supported task families score at least 60 and differ by less than 20.
- The request contains all necessary identifiers but combines multiple
  unsupported diagnostic goals.

It uses the existing role-filtered, read-only ReAct candidates and all PR #25
budgets. It never exposes high-risk tools by default.

## 8. Task Scoring

The router normalizes case and whitespace, extracts bounded features, and sums
weights. It does not use model inference, eval metadata, or tool outputs.

| Feature | Score and qualification |
| --- | --- |
| Refund or compensation action | +100 only for an execution verb and one concrete target; policy questions do not qualify |
| MQ diagnosis | +90 for an order target plus MQ, dead-letter, or consumer-failure evidence |
| Coupon issue diagnosis | +80 for an order target plus missing-coupon, issue-failure, or stock-failure evidence |
| Coupon root-cause diagnosis | Coupon issue score plus +20 for an explicit why, reason, or root-cause request |
| Payment diagnosis | +80 for an order target plus payment failure, callback, mismatch, or payment-status evidence |
| Analytics | +60 aggregate request, +30 metric term, +20 usable time range |
| Knowledge | +60 rule/policy/difference/ratio/SLA/time-limit term, +20 question form, +20 no concrete order |
| Policy configuration | Knowledge score plus +30 for threshold, limit, or configuration semantics |
| Campaign authoring | +70 create/new/generate/draft verb, +30 campaign/coupon object; pure rule questions are excluded |
| Order query | +50 concrete order identifier, +30 query/status intent |
| Small talk | +100 only when no business entity, metric, or action exists |

The highest qualified score wins. Ties are resolved by specificity:

```text
high-risk action
  > explicit operational diagnosis
  > analytics
  > policy configuration / knowledge
  > campaign authoring
  > order query
```

A maximum score below 60 does not automatically expose general tools. It
becomes `clarification` unless the request satisfies the complete mixed-task
conditions for `general_fallback`.

## 9. Minimal Tool Plans

The table lists the logical plan before role filtering. At runtime the model
sees only `next_tool`, not the whole plan.

| Task type | Required plan |
| --- | --- |
| `analytics` | `shop_metrics_query` for one supported date |
| `order_query` | `query_order` |
| `payment_diagnosis` | `query_order -> query_payment` |
| `coupon_issue` | `query_order -> query_coupon_issue_log` |
| `coupon_root_cause` | `query_order -> query_coupon_issue_log -> [query_mq_dead_letter]` |
| `mq_diagnosis` | `query_order -> query_mq_dead_letter` |
| `knowledge` | `knowledge_search` |
| `policy_configuration` | `knowledge_search -> coupon_policy_lookup` |
| `campaign_draft` | the conditional plan in section 9.1 |
| `refund_action` | `query_order -> execute_refund` after structured eligibility |
| `compensation_action` | `query_order -> [query_coupon_issue_log] -> issue_compensation_coupon` after conclusive structured evidence |
| `clarification` / small talk | no tools |
| `general_fallback` | existing role-filtered read-only general candidates |

### 9.1 Conditional campaign plan

Campaign drafting is not a fixed two-tool chain:

```text
Complete threshold, validity period, and purchase limit
  -> campaign_draft_generate

Explicit "follow platform policy" request or missing policy constraints
  -> coupon_policy_lookup
  -> campaign_draft_generate
```

The plan includes only necessary evidence, not every potentially useful tool.

The bracketed MQ step in `coupon_root_cause` is also conditional. It is selected
only when the structured coupon-log result points to an MQ or dead-letter path.
An ordinary coupon-log result completes the route without an extra MQ query.

### 9.2 Analytics capability boundary

The current Java `shop_metrics_query` accepts one date only. It does not accept
a date range and cannot calculate an exact monthly aggregate in one call.

```text
today / yesterday / one concrete date
  -> shop_metrics_query

this month / recent week / arbitrary range
  -> clarification explaining the current single-day capability
```

The router must not make repeated daily calls, send an unsupported range, or
present one day's result as a monthly total. Extending the Java tool with a
range contract is a separate backend change.

### 9.3 Role intersection

Each required tool is checked through `TOOL_ROLE_MAP` before exposure. Missing
permission does not cause tool substitution.

For example, `knowledge_search` remains unavailable to CS. A CS knowledge
request results in an escalation or permission response with zero native RAG
execution.

The bracketed coupon-log step is role- and evidence-dependent:

- `query_order` returns coupon usage state, not conclusive delivery-failure
  evidence. `UNUSED`, `USED`, `EXPIRED`, or null must not unlock compensation.
- An admin may continue to `query_coupon_issue_log`. A structured failed
  Outbox result can then unlock `issue_compensation_coupon`.
- A CS user cannot call `query_coupon_issue_log`. If `query_order` is the only
  available evidence, the route escalates without exposing the action tool.
- No route parses the human-readable `diagnosis` field to unlock a high-risk
  action.

This preserves `TOOL_ROLE_MAP` while failing closed around an ambiguity in the
current Java coupon-status contract.

## 10. Evidence State

Evidence state stores control facts only:

```text
route_task_type
route_mode
required_evidence
evidence_collected
evidence_complete
evidence_stop_reason
synthesis_only
```

Each record has this bounded shape:

```text
EvidenceRecord
  status:
    success | not_found | parameter_error | permission_denied |
    timeout | business_rejected | internal_error | pending_hitl
  attempts
  facts:
    found
    order_status
    payment_status
    coupon_usage_status
    coupon_issue_status
    coupon_failure_confirmed
    mq_dead_letter_present
    knowledge_found
    policy_available
    campaign_draft_generated
```

Only fields needed by route decisions are populated. Values are normalized to
known booleans or enums; unknown values become `UNKNOWN`.

Evidence state must not contain:

- Order number, user ID, merchant ID, or payment trade number.
- Amount, phone number, address, or other business payload.
- Raw tool arguments or complete tool output.
- Prompt or final answer text.

Raw output remains in the existing ToolMessage for model synthesis and audit.

Normalized coupon fields have separate meanings:

```text
coupon_usage_status:
  UNUSED | USED | EXPIRED | NONE | UNKNOWN

coupon_issue_status:
  PENDING | SENT | FAILED | NO_RECORD | UNKNOWN

coupon_failure_confirmed:
  true only for an explicit structured failure result
  false only for an explicit healthy result
  UNKNOWN when the available tools cannot prove either state
```

The Evidence Gate must not treat `coupon_usage_status=UNUSED` as an issue
failure. An explicit structured Outbox failure is required; prose in a tool
description or `diagnosis` value is not control evidence.

### 10.1 Tool result normalization

Transport success and business success are separate:

```text
HTTP/RPC success + valid business object -> success
HTTP/RPC success + found=false           -> not_found
McpToolError(not_found)                  -> not_found
McpToolError(parameter_error)            -> parameter_error
McpToolError(permission_denied)           -> permission_denied
McpToolError(tool_timeout)                -> timeout
Business rule rejection                  -> business_rejected
Unknown exception or malformed result    -> internal_error
```

For knowledge retrieval, `found=false` is valid negative evidence and ends the
knowledge route without fabrication.

### 10.2 Evidence completion

- All required successful facts collected: set `evidence_complete=true`.
- Root `query_order=not_found`: short-circuit downstream tools.
- Knowledge `found=false`: complete with an evidence-based refusal.
- Controlled evidence incomplete: expose only the next required tool.
- Terminal error: set `evidence_stop_reason`; do not expose another tool.
- Evidence complete: set `synthesis_only=true`, call the LLM once with no
  bound tools, then finalize.
- HITL pending: preserve `pending_hitl`; the existing HITL edge takes
  precedence.

### 10.3 Structured high-risk unlock

High-risk tools are never unlocked by raw string matching.

Refund eligibility is derived from normalized evidence:

```text
found = true
order_status in {PAID, COMPLETED}
```

Only then can `execute_refund` become `next_tool`. ToolPolicy still applies
RBAC and budget checks before the existing HITL interception. Approval binding,
resume, and checkpoint behavior are unchanged.

Compensation follows the same principle: a recognized order plus
`coupon_failure_confirmed=true` are required. The current `query_order`
contract cannot establish that fact by itself. An authorized
`query_coupon_issue_log` result must provide an explicit structured failure;
otherwise the request escalates instead of exposing the action tool.

## 11. Specific Tool Choice

For a controlled evidence route:

1. Bind exactly one tool.
2. Pass that tool name as the specific `tool_choice`.
3. Validate the returned call through ToolPolicy as before.

`small_talk` is classified deterministically but requires no external
evidence. It binds no tool and proceeds directly to response generation.

The fixed dependencies are retained:

```text
langchain 0.3.7
langchain-openai 0.2.6
langgraph 0.2.45
```

Local compatibility inspection confirms that:

```text
bind_tools(..., tool_choice="query_order")
```

is serialized as:

```json
{"type": "function", "function": {"name": "query_order"}}
```

No dependency upgrade is needed. A unit test will lock this adapter contract,
and one real DeepSeek smoke will verify provider behavior.

## 12. Error Handling

| Condition | Behavior |
| --- | --- |
| LLM transport error | Keep existing graph retry policy; exhausted retries remain `transport_failure` |
| MCP timeout | Allow one retry within existing budgets; second timeout terminates |
| Business `not_found` | Treat as valid evidence; no retry and no downstream tool |
| Parameter error | Allow one model correction; a second error asks for clarification |
| Permission denial | Stop immediately; no retry, substitution, MCP, RAG, or HITL |
| Budget denial | Stop immediately; do not raise any threshold |
| Business rejection | Stop and explain the business precondition |
| High-risk action | Enter existing HITL; do not execute before approval |
| Unknown tool | Fail closed through ToolPolicy |
| Unknown exception or malformed result | Record `internal_error`, stop, and return an honest failure |

All retries count against PR #25 budgets.

## 13. Planned Files

### 13.1 Production changes

- `copilot-agent-service/agent/tool_router.py`
  - Scored classification, RouteDecision, minimal candidates.
- `copilot-agent-service/agent/evidence_gate.py`
  - Pure evidence normalization and next-step decisions.
- `copilot-agent-service/agent/state.py`
  - Bounded route and evidence fields.
- `copilot-agent-service/agent/nodes.py`
  - Specific next-tool binding and structured execution outcomes.
- `copilot-agent-service/agent/graph.py`
  - Read evidence state in existing conditional routes; topology unchanged.
- `copilot-agent-service/api/chat.py`
  - Initialize route and evidence state.
- `copilot-agent-service/guardrails/input_checker.py`
  - Narrow cross-merchant and bulk-action pattern fixes.

### 13.2 Tests

- `copilot-agent-service/tests/test_tool_router.py`
- `copilot-agent-service/tests/test_evidence_gate.py`
- `copilot-agent-service/tests/test_agent_nodes.py`
- `copilot-agent-service/tests/test_agent_graph.py`
- `copilot-agent-service/tests/test_guardrails.py`

### 13.3 Evidence documents after the real run

- `docs/performance/02-backend-agent-baseline-report.md`
- `docs/performance/baseline-summary.json`

No eval implementation file will change.

### 13.4 Explicit non-edits

- `copilot-agent-service/agent/tool_policy.py` and its permission or budget
  constants.
- `copilot-agent-service/evals/`, including EvalCase, fixtures, contract
  validation, selection, runner, and scoring.
- `copilot-agent-service/rag/` and all Milvus, BM25, reranker, and embedding
  code.
- `_build_system_prompt`, Reflection, Auto-Compact, HITL approval, checkpoint,
  and resume behavior.
- `local-life-copilot/`, `local-life-server/`, database migrations, and Compose
  topology.
- Production Python dependencies and the pinned LangChain, LangGraph, and model
  versions. The development-only mutmut pin is covered by the approved
  exception in section 20.4.

## 14. Test Matrix

| Layer | Required tests |
| --- | --- |
| Scoring | Analytics with order terms; refund policy vs refund action; campaign rule vs campaign creation; payment, coupon, MQ, order, small talk, and ambiguity |
| Clarification | Missing order ID, metric, time range, target, and empty business fragment expose no tools |
| Fallback | Complete mixed requests use read-only role-filtered candidates and existing budgets |
| Role filter | Merchant, CS, admin, unknown role; CS cannot see or execute `knowledge_search` |
| Candidate plan | Every controlled evidence task exposes exactly one `next_tool`; small talk exposes none |
| Evidence extraction | Success, HTTP-200 negative result, not-found exception, malformed output, and enum normalization |
| Evidence progression | Ordered advancement, root not-found short-circuit, knowledge refusal, campaign conditional chain |
| High risk | Structured eligibility unlocks HITL tool; `UNUSED` alone, ineligible state, missing evidence, or CS-only evidence does not |
| Errors | One parameter correction, one timeout retry, terminal permission/budget/business/internal errors |
| Stop | Complete evidence binds no tools; monthly metric executes at most once |
| Protocol | Every assistant tool call receives its ToolMessage; no insertion before tool results |
| PR #25 regression | Four budget layers, SHA-256 signatures, whole-batch rejection, metrics cardinality, argument-free logs |

Tests must use equivalence classes and paraphrases. Exact baseline prompts may be
included as regression inputs, but production code may not branch on them.

## 15. Real DeepSeek Verification

Run the unchanged 24-case selection twice at concurrency one against a rebuilt
Agent image using `deepseek-v4-flash`. Seed data, fixtures, eval contract, and
scoring remain unchanged.

### 15.1 Safety hard gates

All must pass:

```text
invalid_eval_contract = 0
fixture resolution = 47 / 47
permission accuracy = 48 / 48
CS knowledge_search actual execution = 0
unknown tool actual execution = 0
out-of-budget actual execution = 0
high-risk pre-approval actual execution = 0
ToolMessage protocol errors = 0
Case 3 actual shop_metrics_query per run <= 1
```

Actual execution is verified from MCP/audit or persisted tool evidence, not
from model-proposed calls that were rejected before execution.

### 15.2 Quality minimum gate

```text
task completion >= 29 / 48
trajectory accuracy >= 34 / 48
first-tool accuracy >= 42 / 48
tool-argument accuracy >= 47 / 48
final-fact accuracy >= 42 / 48
```

Failure to meet this minimum blocks the PR quality claim. It does not justify
changing EvalCase, scoring, permissions, or budgets.

### 15.3 Expected quality target

```text
task completion >= 34 / 48
trajectory accuracy >= 38 / 48
HITL accuracy = 100%
refusal accuracy = 100%
```

Missing an expected target is reported as a residual quality gap, not hidden
through repeated API runs.

### 15.4 Performance observations

Record, but do not use a single external run as the sole merge gate:

```text
P50 / P95 / P99 end-to-end latency
model calls per run
actual tool calls per run
controlled-route latency
general-fallback latency
transport failures
```

P95 <= 20 seconds and P99 <= 25 seconds remain optimization targets.

## 16. Verification Gates

Before commit and PR:

1. Focused router, evidence, node, graph, and Guardrail tests.
2. Full Python suite.
3. Coverage gate at the existing 45% threshold.
4. Mutation gate at the existing 50% threshold.
5. Documentation and whitespace checks.
6. Secret scan and generated-artifact check.
7. Rebuild only the Agent image from current source.
8. Real Docker smoke for classification, next-tool forcing, HITL, and refusal.
9. One 48-run DeepSeek comparison.

The real API run is performed once after deterministic tests pass. A transport
failure is reported separately and is not erased by repeatedly rerunning until
green.

## 17. Implementation Order

1. Add failing classification, clarification, and candidate-set tests.
2. Implement RouteDecision and scored classification.
3. Add failing evidence normalization and completion tests.
4. Implement the pure Evidence Gate.
5. Add failing node and graph integration tests.
6. Bind the unique next tool and integrate evidence state.
7. Add narrow Guardrail fixes with positive and negative tests.
8. Run full local gates and independent review.
9. Rebuild Agent Docker image and run smoke scenarios.
10. Run the unchanged real DeepSeek baseline once.
11. Update evidence documents and open a Draft PR to `main`.

## 18. Known Residual Risks

The following pre-existing risks are recorded but not fixed here:

1. HITL approval is not yet cryptographically bound to an immutable approved
   payload.
2. Resume behavior can regenerate tool arguments after approval.
3. Checkpoint selection and ordering need a separate recovery design.
4. Agent entry identity still trusts client-provided headers in the current
   local architecture.
5. Scored rules can still misclassify unseen language; `clarification` and
   `general_fallback` limit the blast radius.

## 19. References

- DeepSeek Chat Completion tool choice:
  <https://api-docs.deepseek.com/api/create-chat-completion/>
- DeepSeek Tool Calls guide:
  <https://api-docs.deepseek.com/guides/tool_calls/>
- LangGraph state and conditional edges:
  <https://docs.langchain.com/oss/python/langgraph/graph-api>
- Project metric contract:
  `docs/performance/01-metric-contract.md`
- Project Git process:
  `docs/03-process/Git版本管理与提交规范.md`

## 20. Draft PR Review Addendum

This addendum was approved after review of Draft PR #26 on 2026-07-29. It
narrows the remaining merge blockers without changing the graph, model, Prompt,
permissions, budgets, RAG, Java services, database, or evaluation contract.

### 20.1 Original request binding

`RouteDecision` stores:

```text
route_target_order_hash: SHA-256(normalized order number) or null
route_requested_amount_minor: positive integer minor units or null
```

The raw target order number is not checkpointed. High-risk actions without one
unambiguous order and one unambiguous currency amount use `clarification`.
Every order-scoped tool call is checked against the retained hash before MCP or
HITL handling. A `query_order` response is checked again before its raw payload
is retained or used. The final refund or compensation proposal must match the
same order hash and exact requested amount. Paid amount remains evidence for
eligibility and upper-bound checks; it is never substituted for the requested
amount.

### 20.2 Guardrail policy exemption

The policy-question exemption remains lexical and narrow. Before applying it,
the input is split on Chinese and English sentence punctuation, comma, colon,
and parentheses. A separate clause that already matches a cross-scope access
or bulk-sensitive command prevents exemption, even if another clause asks
about policy, permissions, or approval.

### 20.3 Dependency failure outcome

Controlled MCP discovery failure or absence of the required MCP tool returns:

```text
evidence_stop_reason = internal_error
stop_reason = internal_error
```

The full graph must preserve that outcome through `final_node`; it must never
record the request as `completed`.

### 20.4 Approved mutation-tool exception

The production dependency freeze remains unchanged. The development-only
mutation runner was upgraded from mutmut 3.3.1 to 3.6.0 after two GitHub
Runner executions produced false `SIGXCPU` outcomes under the older fixed
timeout heuristic. CI still runs the complete suite with four workers,
`min-kill-rate=50`, and `max-other=0`. The pre-remediation cold-cache local
run and GitHub run both reported 702 killed, 330 survived, and zero other
outcomes out of 1032. After the review remediation added target/amount and
clause-boundary logic, a new local cold-cache run reported 802 killed, 353
survived, and zero other outcomes out of 1155. This exception changes test
infrastructure only and does not claim a like-for-like quality increase over
historical 3.3.1 totals.
