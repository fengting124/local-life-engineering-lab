# Agent Policy Configuration Controlled Dispatch Implementation Plan

- Status: Active
- Type: Plan
- Owners: Agent maintainers
- Last verified: 2026-08-20
- Source of truth: `agent/nodes.py`, fixed Eval contracts, Docker Lite evidence, and `docs/performance/07-agent-policy-configuration-controlled-dispatch.md`

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate stochastic duplicate `knowledge_search` calls in the fixed `policy_configuration` plan without changing product semantics, permissions, tools, prompts, or evaluation contracts.

**Architecture:** Extend the existing `_build_controlled_knowledge_dispatch` strict whitelist to accept exactly two route shapes: single-tool `knowledge` and two-step `policy_configuration`. It emits one standard `AIMessage(tool_calls=[...])` for the current expected tool, while the existing `tool_node`, ToolPolicy, RBAC, budget, audit, merchant context, and Evidence Gate remain the only execution path.

**Tech Stack:** Python 3.11, LangChain messages, LangGraph state, pytest, Docker Lite, DeepSeek V4 Flash, Java MCP `coupon_policy_lookup`.

## Global Constraints

- Do not modify Prompt, EvalCase, scoring, RBAC, ToolPolicy, tool budgets, RAG retrieval, embedding, reranker, Milvus, model, HITL, Checkpointer, Java, or MCP business semantics.
- Do not add Case 32/37 ID branches or generalize to every route whose next tool is `knowledge_search`.
- Deterministic calls must be standard `AIMessage(tool_calls=[...])` values consumed by the existing `tool_node`.
- Queries come from the latest valid current `HumanMessage`; stale, blank, malformed, or non-`BaseMessage` state fails closed.
- Merchant scope remains authenticated runtime context and is never supplied or rewritten by the model.
- Do not run the full 24x2 baseline until this PR and PR #42 are both merged to `main`.

---

## Failure Facts

The one-shot artifact is `artifacts/performance/agent-post-fastpath-20260814-041547/deepseek-flash-post-fastpath.json`. Historical `agent_message` and `tool_audit_log` rows remain available in the Docker Lite MySQL volume.

| Case / run | Route state before first call | Actual assistant tool calls | Tool-node / Evidence result | Why it repeated | Expected trajectory |
| --- | --- | --- | --- | --- | --- |
| 32 / 1 | `policy_configuration`; required/authorized `knowledge_search, coupon_policy_lookup`; next `knowledge_search` | `knowledge_search(query=current request)` then a rewritten `knowledge_search` | First RAG succeeded and Evidence Gate advanced next to `coupon_policy_lookup`; second call was rejected as `invalid_controlled_tool_batch`, final `internal_error` | Existing helper only accepts task `knowledge` with the one-tool plan, so both policy steps fall back to DeepSeek | `knowledge_search(query=current request) -> coupon_policy_lookup({})` |
| 32 / 2 | Same | `knowledge_search(query=current request)` then `coupon_policy_lookup({})` | Both succeeded; `completed` | Model happened to follow the fixed plan | Same |
| 37 / 1 | Same | `knowledge_search(query=current request)` then `coupon_policy_lookup({})` | Both succeeded; `completed` | Model happened to follow the fixed plan | Same |
| 37 / 2 | Same | `knowledge_search(query=current request)` then a rewritten `knowledge_search` | First RAG succeeded and Evidence Gate advanced next to `coupon_policy_lookup`; second call was rejected, final `internal_error` | Same uncontrolled fallback | Same |

`TASK_TOOL_PLANS["policy_configuration"]` is exactly `("knowledge_search", "coupon_policy_lookup")`. `ToolRouter.route()` exposes only `route_next_tool`, but `_build_controlled_knowledge_dispatch()` currently returns `(None, None)` for `policy_configuration`, leaving DeepSeek to recreate a tool call already fixed by route state.

The real MCP schema for `coupon_policy_lookup` has optional `coupon_template_id` and optional `status`, with no required fields. Historical successful Case 32/37 runs both called it with `{}`. Empty arguments mean “all coupon templates visible to the authenticated merchant”; Java obtains `merchant_id` from `RbacContext`, so deterministic `{}` neither guesses a filter nor expands merchant scope.

## Frozen Contract

| Task type | Exact required/authorized plan | Next tool | Deterministic arguments |
| --- | --- | --- | --- |
| `knowledge` | `("knowledge_search",)` | `knowledge_search` | `{"query": latest_current_human_message}` |
| `policy_configuration` | `("knowledge_search", "coupon_policy_lookup")` | `knowledge_search` | `{"query": latest_current_human_message}` |
| `policy_configuration` | `("knowledge_search", "coupon_policy_lookup")` | `coupon_policy_lookup` | `{}`, only after successful `knowledge_search` evidence |

Every other task, plan permutation, authorization sequence, routed-tool set, message shape, or evidence shape fails closed or stays on its existing path.

---

### Task 1: Establish RED Dispatch Contracts

**Files:**
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`
- Modify: `copilot-agent-service/tests/test_evidence_gate.py`

**Interfaces:**
- Consumes: `_build_controlled_knowledge_dispatch(state, routed_tools)` and `llm_node(state)`.
- Produces: executable contracts for both deterministic policy steps and Evidence Gate progression.

- [x] **Step 1: Replace the old policy test with a failing first-step deterministic assertion**

Create a `policy_configuration` state with the exact two-tool plan and a rejecting LLM. Assert one `knowledge_search` call with the unmodified latest `HumanMessage`, `llm_call_count == 0`, and no LLM invocation.

- [x] **Step 2: Run the first RED test**

Run:

```bash
pytest -q tests/test_agent_nodes.py::TestLlmNode::test_policy_configuration_dispatches_first_knowledge_call_without_llm
```

Expected: FAIL because the current helper excludes `policy_configuration` and `llm_node` invokes the rejecting LLM.

- [x] **Step 3: Add a failing second-step deterministic assertion**

Create exact successful `knowledge_search` evidence, set `route_next_tool="coupon_policy_lookup"`, expose only that routed MCP tool, and assert a standard call with `{}` and zero LLM calls.

- [x] **Step 4: Run the second RED test**

Run:

```bash
pytest -q tests/test_agent_nodes.py::TestLlmNode::test_policy_configuration_dispatches_policy_lookup_without_llm
```

Expected: FAIL because policy configuration still falls through to the model.

- [x] **Step 5: Add deterministic boundary tests**

Cover exact/superset/subset/malformed plans, malformed authorization, malformed routed tools, stale or empty messages, missing/failed knowledge evidence, single-tool knowledge compatibility, CS zero-tool behavior, tool unavailable, and controlled-batch rejection.

- [x] **Step 6: Add Evidence Gate progression test**

Assert successful `knowledge_search` changes `route_next_tool` to `coupon_policy_lookup`, then successful policy evidence sets `evidence_complete=True` and `synthesis_only=True`.

### Task 2: Implement the Strict Two-Step Whitelist

**Files:**
- Modify: `copilot-agent-service/agent/nodes.py`
- Test: `copilot-agent-service/tests/test_agent_nodes.py`
- Test: `copilot-agent-service/tests/test_evidence_gate.py`

**Interfaces:**
- Consumes: route state, latest `HumanMessage`, successful normalized `knowledge_search` evidence, and the single routed next-tool specification.
- Produces: `(AIMessage | None, error_reason | None)` using the existing helper contract.

- [x] **Step 1: Define exact accepted knowledge plans next to the helper**

Use an immutable mapping for only `knowledge` and `policy_configuration`; do not infer acceptance from `route_next_tool` alone.

- [x] **Step 2: Validate exact route, authorization, and routed-tool sequences**

Require the complete plan and complete authorized sequence to match the whitelist, and require `routed_tools` to contain exactly the current next tool.

- [x] **Step 3: Build the first call from the current user request**

For `knowledge_search`, validate all message elements are `BaseMessage`, select the latest `HumanMessage`, reject blank content, preserve its exact text, and emit one standard tool call.

- [x] **Step 4: Build the second call only from successful evidence**

For `coupon_policy_lookup`, require the exact policy plan plus `evidence_collected["knowledge_search"].status == "success"` and normalized `facts.found is True`; emit `{}` and rely on authenticated Java `RbacContext` for merchant scope.

- [x] **Step 5: Run focused GREEN tests**

Run:

```bash
pytest -q tests/test_agent_nodes.py tests/test_evidence_gate.py tests/test_tool_router.py
```

Expected: all pass; no existing single-tool, RBAC, ToolPolicy, budget, not-found, or unavailable-tool regression.

- [ ] **Step 6: Commit the implementation**

Commit with Goal, Changes, Verification, and Risk sections; do not include generated artifacts or secrets.

### Task 3: Docker Lite Targeted Validation

**Files:**
- Modify: `docs/performance/07-agent-policy-configuration-controlled-dispatch.md`
- Modify: `docs/文档清单.md`

**Interfaces:**
- Consumes: current branch Agent image and frozen Case 32/37 contracts.
- Produces: sanitized aggregate evidence, not raw prompts, IDs, keys, or database payloads.

- [x] **Step 1: Rebuild the standard Agent image from the worktree**

Run the repository-standard Docker build, recreate only `copilot-agent`, and verify the container source hashes match the worktree.

- [x] **Step 2: Run Case 32 five times and Case 37 five times**

Use DeepSeek V4 Flash, concurrency 1, isolated sessions, and the unchanged requests/contracts.

- [x] **Step 3: Run controls**

Run public single-tool knowledge twice and CS permission-negative twice.

- [x] **Step 4: Verify runtime evidence**

Require one `knowledge_search` and one `coupon_policy_lookup` per policy run, no duplicate/rejected/unknown/protocol/high-risk events, complete facts, correct permission behavior, and real RAG/tool audit spans.

- [x] **Step 5: Record the sanitized report**

Document all attempts including failures; do not rerun to replace an unfavorable result.

### Task 4: Full Gates, Review, and Integration

**Files:**
- Modify only documentation required to record verified results.

- [ ] **Step 1: Run deterministic gates**

Run focused tests, Agent full suite with coverage, the current mutation gate, `scripts/check_docs.py`, and `git diff --check`.

- [ ] **Step 2: Perform an independent final diff review**

Verify no Prompt, Eval, permissions, budget, RAG, model, HITL, Checkpointer, Java, or MCP semantic changes and no sensitive data.

- [ ] **Step 3: Push and create a Draft PR**

The PR body must include exact head, test numbers, Case 32/37 5x results, control results, runtime hashes, remaining risks, and `BLOCKING FINDINGS`.

- [ ] **Step 4: Merge only after all branch gates pass**

Require CI green, 0 behind, no unresolved review thread, mergeable state, and `BLOCKING FINDINGS=0`; use a head-locked merge commit and wait for `main` CI before cleanup.

### Task 5: One New Fixed 24x2 Baseline

**Files:**
- Modify only the baseline report/summary if all 48 runs satisfy every hard gate.

- [ ] **Step 1: Confirm both fixes are on clean latest `main`**

Require PR #42 and this PR merge commits, all `main` CI green, no open implementation PR, and unchanged Eval/fixture/scoring/model configuration.

- [ ] **Step 2: Run exactly once**

Run fixed 24 cases x 2, DeepSeek V4 Flash, concurrency 1. Never rerun to select a better sample.

- [ ] **Step 3: Classify the real result**

Record Transport, Task completion, First tool, Tool arguments, Trajectory, Final facts, Permission, HITL, Refusal, P50/P95/P99, LLM calls, and input/output/total Token.

- [ ] **Step 4: Publish only on 48/48**

If every quality metric is 48/48, publish a new PASS baseline comparing old PASS, first post-fastpath PARTIAL, and the new result. Otherwise retain the real failure, classify it, and stop without republishing.
