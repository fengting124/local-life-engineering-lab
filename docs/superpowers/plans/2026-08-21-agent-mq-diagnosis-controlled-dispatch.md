# Agent MQ Diagnosis Controlled Dispatch Implementation Plan

- Status: Active
- Type: Plan
- Owners: Agent maintainers
- Last verified: 2026-08-21
- Source of truth: `docs/superpowers/specs/2026-08-21-agent-mq-diagnosis-controlled-dispatch-design.md`, Agent route code, fixed Eval contracts, and Docker Lite evidence

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the model-only MQ tool-selection step after the Router and Evidence Gate have already fixed `query_order -> query_mq_dead_letter`.

**Architecture:** Reuse the existing order-scoped `_build_controlled_dispatch()` and standard `AIMessage(tool_calls=[...]) -> tool_node` path. Add only the exact `mq_diagnosis` plan and the existing SHA-256 order target binding required by that dispatcher; Evidence Gate remains the sole next-step decision owner.

**Tech Stack:** Python 3.11, LangChain messages, LangGraph state, pytest, Docker Lite, DeepSeek V4 Flash, MCP tool audit, GitHub Actions.

## Global Constraints

- Do not modify Prompt, model, EvalCase, fixture, scoring, RBAC, ToolPolicy, tool budgets, Evidence Gate semantics, HITL, RAG, Checkpointer, MCP, or Java.
- Do not add Case 20 branches or a separate MQ dispatcher.
- Do not include `coupon_root_cause`; its conditional MQ continuation and CS escalation remain a separate product route.
- All deterministic calls must remain standard `AIMessage(tool_calls=[...])` values consumed by the existing `tool_node`.
- The exact order ID comes from one current request target and matching successful order evidence; the persisted state stores only its SHA-256 digest.
- Do not run the fixed 24x2 baseline before this branch is merged and `main` CI is green. Run it exactly once afterward.

---

### Task 1: Establish RED Route Binding And Dispatch Contracts

**Files:**
- Modify: `copilot-agent-service/tests/test_tool_router.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`
- Modify: `copilot-agent-service/tests/test_evidence_gate.py`

**Interfaces:**
- Consumes: `classify_request()`, `_build_controlled_dispatch()`, `llm_node()`, `tool_node()`, and `advance_evidence()`.
- Produces: failing executable contracts for the exact MQ route and its fail-closed boundaries.

- [x] **Step 1: Add a router binding RED test**

Add a test using an alphanumeric order ID and assert the complete normalized ID is represented by `RouteDecision.target_order_hash`:

```python
def test_mq_diagnosis_binds_the_complete_order_target():
    order_id = "BULK2026061000000095"
    decision = classify_request(
        "admin", f"排查订单 {order_id} 的 MQ 死信失败原因"
    )
    assert decision.task_type == "mq_diagnosis"
    assert decision.target_order_hash == hashlib.sha256(
        order_id.encode("utf-8")
    ).hexdigest()
```

- [x] **Step 2: Run the router RED test**

Run:

```bash
python -m pytest -q tests/test_tool_router.py::test_mq_diagnosis_binds_the_complete_order_target
```

Expected: FAIL because current `mq_diagnosis` decisions store `target_order_hash=None`.

- [x] **Step 3: Replace the obsolete MQ LLM expectation with first-step RED**

Create the exact route state with a rejecting LLM and assert one standard
`query_order` call, the complete order ID, and `llm_call_count == 0`:

```python
assert result["messages"][0].tool_calls == [{
    "name": "query_order",
    "args": {"order_id": order_id},
    "id": "controlled-query_order-1",
    "type": "tool_call",
}]
rejecting_llm.ainvoke.assert_not_awaited()
```

- [x] **Step 4: Add second-step MQ RED**

Provide canonical successful `query_order` evidence plus its matching raw
`ToolMessage`, set `route_next_tool="query_mq_dead_letter"`, expose only that
catalog tool, and assert exactly one bound call with zero LLM calls.

- [x] **Step 5: Run both dispatch RED tests**

Run the two exact node test IDs. Expected: FAIL because
`CONTROLLED_DISPATCH_PLANS` has no `mq_diagnosis` entry and the path invokes the
rejecting LLM.

- [x] **Step 6: Add the fail-closed regression matrix**

Add product-level tests for:

```text
wrong model proposal -> controlled batch rejected before MCP
not_found order -> no MQ continuation
first parameter_error/timeout -> same-tool retry
second parameter_error/timeout -> terminal
missing or mismatched binding -> internal_error
alphanumeric order preserved
plan subset/superset/malformed -> internal_error
wrong route_next_tool -> internal_error
MQ tool unavailable -> internal_error
unauthorized role/state -> zero MCP execution
tool budget and ToolPolicy denial -> existing rejection path
```

Also retain existing payment, coupon-issue, policy-configuration, knowledge,
refund, compensation, and HITL tests unchanged.

### Task 2: Implement The Exact MQ Plan

**Files:**
- Modify: `copilot-agent-service/agent/tool_router.py`
- Modify: `copilot-agent-service/agent/nodes.py`
- Test: `copilot-agent-service/tests/test_tool_router.py`
- Test: `copilot-agent-service/tests/test_agent_nodes.py`

**Interfaces:**
- Consumes: one parsed order ID, `order_target_hash()`, canonical order evidence, current route state, and the single routed next-tool spec.
- Produces: one existing-format ToolCall for the current MQ plan step.

- [x] **Step 1: Bind the MQ route target in classification**

Extend only the existing diagnostic `_decision()` call so `mq_diagnosis` stores:

```python
target_order_hash=(
    order_target_hash(order_ids[0])
    if task_type in {
        "payment_diagnosis",
        "coupon_issue",
        "mq_diagnosis",
    }
    else None
)
```

Do not add `coupon_root_cause` in this PR.

- [x] **Step 2: Add the exact plan to the existing dispatcher whitelist**

Modify only the existing mapping:

```python
CONTROLLED_DISPATCH_PLANS = {
    "order_query": ("query_order",),
    "payment_diagnosis": ("query_order", "query_payment"),
    "coupon_issue": ("query_order", "query_coupon_issue_log"),
    "mq_diagnosis": ("query_order", "query_mq_dead_letter"),
}
```

Do not create a helper specific to Case 20 or MQ.

- [x] **Step 3: Run GREEN route and node tests**

Run:

```bash
python -m pytest -q tests/test_tool_router.py tests/test_agent_nodes.py
```

Expected: all pass, including zero LLM calls for both MQ steps and unchanged
existing deterministic routes.

- [x] **Step 4: Run focused Evidence/graph/policy regressions**

Run:

```bash
python -m pytest -q \
  tests/test_evidence_gate.py \
  tests/test_answer_facts.py \
  tests/test_agent_graph.py \
  tests/test_e2e_agent.py \
  tests/test_tool_policy.py
```

Expected: all pass with no Evidence Gate, answer, graph, permission, budget, or
HITL behavior change.

- [x] **Step 5: Commit the production fix**

Commit only the Router, node, and test changes with Goal, Changes,
Verification, and Risk sections.

### Task 3: Run Deterministic Quality Gates

**Files:**
- Modify only plan/report status after results are known.

**Interfaces:**
- Consumes: final branch source.
- Produces: fresh test, coverage, mutation, documentation, and diff evidence.

- [x] **Step 1: Run the full Agent suite with coverage**

Run the repository CI command with `LANGGRAPH_STRICT_MSGPACK=true` and
`--cov-fail-under=45`. Require zero failures and a generated coverage report.

- [x] **Step 2: Run the current mutation gate**

Run:

```bash
mutmut run --max-children 4
python scripts/check_mutmut_score.py --min-kill-rate 50 --max-other 0
```

Require kill rate at or above the current gate and `other=0`; do not add tests
solely to increase the score.

- [x] **Step 3: Run repository hygiene checks**

Run:

```bash
python3 scripts/check_docs.py
git diff --check
git status --short
```

Do not include secrets, raw prompts, generated databases, logs, or ignored
performance artifacts in commits.

### Task 4: Docker Lite Targeted Validation

**Files:**
- Create: `docs/performance/08-agent-mq-diagnosis-controlled-dispatch.md`
- Modify: `docs/文档清单.md`
- Modify: `docs/superpowers/plans/2026-08-21-agent-mq-diagnosis-controlled-dispatch.md`

**Interfaces:**
- Consumes: standard Agent Dockerfile, unchanged Eval fixtures/contracts, DeepSeek V4 Flash, and current Lite services.
- Produces: sanitized aggregate evidence and runtime/source hashes.

- [x] **Step 1: Rebuild the standard Agent image from this worktree**

Use the repository Compose files and current branch source. Recreate only the
Agent, wait on its healthcheck, and prove image/source hashes match the branch.

- [x] **Step 2: Run Case 20 ten times at concurrency 1**

Require every run to complete this exact trajectory:

```text
query_order exactly once
query_mq_dead_letter exactly once
query_coupon_issue_log zero
controlled_tool_batch_rejected zero
unknown/protocol/high-risk execution zero
```

Record per-run latency, LLM calls, input/output/total tokens, final fact scores,
tool audit status, model, provider, image, source hash, and timestamp. Do not
discard or replace an unfavorable run.

- [x] **Step 3: Run the fixed control group**

Run payment diagnosis x2, coupon diagnosis x2, Case 32 x2, Case 37 x2, public
knowledge x2, CS permission-negative x2, refund proposal x1, and compensation
proposal x1. The two high-risk controls must stop at `PENDING`; do not approve
them. Require pre-approval high-risk execution count zero.

- [x] **Step 4: Record a compact report and commit it**

Document before/after Case 20 latency, LLM usage, trajectory, all controls, any
limitations, and raw evidence locations without storing sensitive payloads.

### Task 5: Independent Review And PR Integration

**Files:**
- Modify only documentation needed to record verified review and CI state.

**Interfaces:**
- Consumes: complete branch diff and all local/Docker evidence.
- Produces: `BLOCKING FINDINGS`, Draft PR, merge commit, and clean latest main.

- [x] **Step 1: Perform an independent final diff review**

Check exact allowlist scope, standard tool-node path, hash binding,
alphanumeric preservation, Evidence Gate ownership, RBAC/ToolPolicy/budget,
tool-unavailable behavior, Case-ID absence, unchanged Eval, and conditional
route exclusion. Require `BLOCKING FINDINGS=0`.

Final read-only review at production head `e34f384` verified the standard
tool-node path, request/evidence binding, unchanged security and Eval
boundaries, and final Docker evidence. Its only code observation, malformed
`route_authorized_tools=None`, was reproduced with a RED test and normalized
to the existing fail-closed error path before this final review. Result:
`BLOCKING FINDINGS=0`.

- [x] **Step 2: Push and create a Draft PR**

The PR body must contain Goal, Root cause, Changes, Scope guard, RED/GREEN,
full tests, coverage, mutation, Case 20 x10, controls, high-risk safety, known
limitations, and `BLOCKING FINDINGS`. State explicitly that this PR did not run
the complete 24x2 baseline.

Draft PR: `#44` (`fix(agent): dispatch mq diagnosis deterministically`). The
published body records the exact local gates and explicitly defers 24x2 until
after merge and green `main` CI.

- [x] **Step 3: Merge only after all branch gates pass**

Require Docs and Agent CI success, zero behind, mergeable, no unresolved review
thread, unchanged expected head, and `BLOCKING FINDINGS=0`. Convert to Ready,
use a merge commit, wait for green `main` CI, synchronize local main, and remove
the local/remote branch and worktree.

PR #44 merged with merge commit `18542a6bea99f5b4e9adc7dcf079dc32638f809a`.
Branch and `main` Docs/Agent CI passed, the expected head remained unchanged,
and the local/remote branch and worktree were removed.

### Task 6: Run The One Authorized Fixed Baseline

**Files:**
- Modify baseline summary/report only if all hard gates pass.

**Interfaces:**
- Consumes: clean merged `main`, unchanged fixed 24 cases, fixture, Prompt, scoring, DeepSeek V4 Flash, and concurrency 1.
- Produces: one immutable 48-run artifact and a PASS/PARTIAL decision.

- [x] **Step 1: Record the exact runtime identity**

Record main SHA, Agent image/source hash, provider, model, start timestamp, and
pre-run audit identifiers. Confirm open implementation PR count is zero and
all `main` CI gates are green.

Recorded `main@18542a6`, image `sha256:d82103f...f31006`, matching runtime source
hashes, DeepSeek V4 Flash, zero open PRs, and green `main` CI before execution.

- [x] **Step 2: Run exactly 24 cases x 2 once**

Never rerun to select a better result. Preserve every run and output the full
failure matrix if any metric misses 48/48.

Executed once from 2026-08-21 02:10:18 to 02:14:11 Asia/Shanghai. The ignored
artifact is `agent-post-mq-20260820-181018/deepseek-flash-post-mq.json`; its
SHA-256 is `299b4481...c1f5de1`. No rerun was performed.

- [x] **Step 3: Apply the hard decision gate**

Require Transport, Task completion, First tool, Tool arguments, Trajectory,
Final facts, Permission, HITL, and Refusal all equal 48/48. Record P50/P95/P99
and provider-reported LLM input/output/total tokens without estimating missing
historical usage.

Every hard quality dimension passed 48/48 and the failure matrix was empty.
P50/P95/P99 were 185/12,562/13,066 ms. All 26 LLM calls reported usage:
23,376 input, 5,070 output, and 28,446 total tokens.

- [x] **Step 4: Publish only a real PASS**

If every hard gate passes, update the tracked summary and performance report,
preserving old 48/48 PASS, first post-fastpath 44/48 PARTIAL, PR42/43 47/48
PARTIAL, and this new result. If any gate fails, retain the artifact, classify
the failure, stop, and run a read-only deterministic coverage audit instead of
automatically fixing another case.

### Task 7: Investigate The Next Product Work Only After PASS

**Files:**
- Create a design document only if the baseline is a real 48/48 PASS.

**Interfaces:**
- Consumes: existing admin APIs, auth/RBAC, shop ownership, coupon template lifecycle, `compensation_coupon_binding`, audit, errors, and migration constraints.
- Produces: a read-only design decision for GET, PUT/upsert, DISABLE, and optional list APIs.

- [ ] **Step 1: Audit existing compensation binding management boundaries**

Confirm `(shop_id, face_value_minor)` uniqueness, same-shop template ownership,
merchant consistency, CASH type, matching face value, no automatic template
creation, no LLM template selection, and configuration audit.

- [ ] **Step 2: Stop at product decisions**

Do not implement the API in this task. Report any unresolved authorization,
lifecycle, or API semantics decision for explicit product approval.
