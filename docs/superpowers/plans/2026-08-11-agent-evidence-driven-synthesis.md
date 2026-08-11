# Agent Evidence-Driven Synthesis Implementation Plan

- Status: Complete
- Type: Plan
- Owners: Project maintainers
- Last verified: 2026-08-11
- Source of truth: `docs/superpowers/specs/2026-08-11-agent-evidence-driven-synthesis-design.md`, targeted tests and Docker evidence

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make controlled diagnostic answers deterministically include every normalized business fact required by Cases 16, 18, and 21 without changing routing or evaluation contracts.

**Architecture:** A small `answer_facts` module derives immutable facts and a deterministic answer from existing bounded evidence. `llm_node` uses it only after evidence completion for supported diagnostic routes, skipping one synthesis model call while existing terminal and unsupported paths remain unchanged.

**Tech Stack:** Python 3.10/3.11, LangGraph 1.2.10, LangChain Core 1.5.3, pytest, Docker Compose Lite, DeepSeek V4 Flash.

## Global Constraints

- Do not modify Router, Guardrail, ToolPolicy, RBAC, HITL, MCP, Java, EvalCase, scoring, RAG retrieval, Prompt routing strategy, or graph topology.
- Do not branch on Case IDs, increase tool budgets, or run the full 24x2 baseline.
- Real model validation is concurrency 1, Cases 16/18/21 only, at most three runs each and nine requests total.
- Preserve permission and refusal behavior and execute no additional tools.

---

### Task 1: Establish the factual answer contract

**Files:**
- Create: `copilot-agent-service/tests/test_answer_facts.py`
- Create: `copilot-agent-service/agent/answer_facts.py`

**Interfaces:**
- Consumes: `route_task_type`, `synthesis_only`, and normalized `evidence_collected` records.
- Produces: `AnswerFact`, `EvidenceAnswer`, `build_evidence_answer(state)`, and `validate_or_fallback(candidate, answer)`.

- [x] **Step 1: Write failing unit tests**

Cover payment mismatch (`WAIT_PAY` + `SUCCESS`), failed payment (`FAILED`), coupon issue (`PAID` plus available coupon facts), optional `UNKNOWN` omission, absent-fact non-invention, exact enum wording, contradictory candidate rejection, and missing-fact fallback.

- [x] **Step 2: Verify RED**

Run:

```bash
cd copilot-agent-service
DEBUG=false pytest -q tests/test_answer_facts.py
```

Expected: collection/import failure because `agent.answer_facts` does not exist.

- [x] **Step 3: Implement the minimal immutable fact model**

Use frozen dataclasses and fixed enum render maps. Read only successful bounded evidence, omit unknown values, and return `None` for unsupported or incomplete routes. Rendering must be deterministic and must not inspect user text or case IDs.

- [x] **Step 4: Verify GREEN**

Run the same test command. Expected: all `test_answer_facts.py` tests pass.

- [x] **Step 5: Commit**

Commit with `test(agent): define evidence answer contract` after reviewing the staged diff.

### Task 2: Integrate deterministic synthesis

**Files:**
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`
- Modify: `copilot-agent-service/tests/test_e2e_agent.py`

**Interfaces:**
- Consumes: `build_evidence_answer` and `validate_or_fallback` from Task 1.
- Produces: supported completed diagnostic routes return a fact-complete `AIMessage` without invoking the synthesis LLM.

- [x] **Step 1: Write failing node and graph tests**

Construct route/evidence state for payment and coupon diagnostics. Assert all required facts appear, `_llm.ainvoke` is not called after evidence completion, MCP call count is unchanged, and malformed/unsupported evidence does not fabricate an answer. Retain tests proving permission, business rejection, tool errors, and synthesis tool-call rejection keep their current stop reasons.

- [x] **Step 2: Verify RED**

Run:

```bash
cd copilot-agent-service
DEBUG=false pytest -q tests/test_agent_nodes.py tests/test_e2e_agent.py
```

Expected: new deterministic-synthesis assertions fail because `llm_node` still calls the LLM.

- [x] **Step 3: Implement the minimal `llm_node` integration**

At the existing direct-answer boundary, build an evidence answer only when `synthesis_only=true`. Return its validated deterministic rendering before tool discovery or model invocation. Do not change graph edges, state schema, prompts, tool routing, or `final_node` terminal precedence.

- [x] **Step 4: Verify GREEN and focused regression**

```bash
cd copilot-agent-service
DEBUG=false pytest -q \
  tests/test_answer_facts.py \
  tests/test_agent_nodes.py \
  tests/test_evidence_gate.py \
  tests/test_e2e_agent.py \
  tests/test_agent_graph.py
```

Expected: all focused tests pass and tool-call counts remain unchanged.

- [x] **Step 5: Commit**

Commit with `fix(agent): synthesize diagnostic answers from evidence`.

### Task 3: Run deterministic quality gates

**Files:**
- Modify only if a test exposes an in-scope synthesis defect.

- [x] **Step 1: Run the Agent suite and coverage gate**

```bash
cd copilot-agent-service
DEBUG=false pytest -q --cov --cov-report=term-missing --cov-fail-under=45
```

Expected: all tests pass and coverage remains above 45%.

- [x] **Step 2: Run the existing mutation gate without changing its targets**

```bash
cd copilot-agent-service
DEBUG=false mutmut run --max-children 4
python scripts/check_mutmut_score.py --min-kill-rate 50 --max-other 0
```

Expected: kill rate at least 50%, `other=0`.

- [x] **Step 3: Run repository document and diff checks**

```bash
python3 scripts/check_docs.py
git diff --check
```

Expected: both commands pass.

### Task 4: Run targeted Docker Lite validation

**Files:**
- Do not commit generated artifacts, database files, logs, or secrets.

- [x] **Step 1: Rebuild and restart only the Agent from current source**

Use the existing Lite Compose pair and required test-only HITL secret. Confirm the Agent is healthy and its source hash matches the worktree.

- [x] **Step 2: Resolve the unchanged fixtures and contract for Cases 16, 18, and 21**

Use existing `select_baseline_cases`, `resolve_cases`, `EvalDatabase`, `run_group`, and `evaluate_case` functions without changing EvalCase or scoring.

- [x] **Step 3: Run at most nine DeepSeek V4 Flash requests**

Run each selected case three times at concurrency 1. Persist a new ignored targeted artifact with per-run tools, facts score, failure category, latency, and model-call evidence; do not persist prompts, answers, raw tool payloads, or keys.

- [x] **Step 4: Evaluate the target**

Expected: each case is 3/3 completed when its route reaches the expected tools; required facts are complete; no extra tool, permission, refusal, or tool-execution regression occurs. Any routing failure is recorded and left for PR #34.

### Task 5: Document, review, and deliver Draft PR

**Files:**
- Modify: `docs/performance/02-backend-agent-baseline-report.md`
- Modify: `docs/superpowers/plans/2026-08-11-agent-evidence-driven-synthesis.md`

- [x] **Step 1: Add the minimal targeted result section**

Record root cause, deterministic design, Cases 16/18/21 results, whether one LLM call was removed, and the unchanged remaining routing failures. Do not overwrite the historical 24x2 baseline or claim global quality improvement.

- [x] **Step 2: Run final verification and independent diff review**

Confirm no changes to prohibited modules/contracts, no secrets or artifacts are tracked, and report `BLOCKING FINDINGS` with file/line evidence.

- [x] **Step 3: Commit documentation**

Commit with `docs(agent): record targeted synthesis validation`.

- [x] **Step 4: Push and create Draft PR**

Push `fix/agent-evidence-driven-synthesis`, create a Draft PR to `main`, and stop. Do not convert to Ready or merge.

## Execution Record

- Focused regression: 185 passed.
- Full Agent suite: 700 passed; coverage 79.31%.
- Mutation: 826 / 1180 killed, 70.0%, other=0.
- Docker Lite: current-source Agent healthy; host/container source hashes match.
- Targeted DeepSeek: Cases 16/18/21 each 3/3 completed at concurrency 1;
  final facts, arguments, trajectories, and permissions all 100%.
- Model calls: 18 `llm.invoke` spans for 9 runs; the final synthesis model call
  was removed while tool calls remained unchanged.
- Independent review: `BLOCKING FINDINGS=0`; prohibited modules, dependencies,
  evaluation contracts, tracked artifacts, and secrets are unchanged.
- Delivery: pushed `fix/agent-evidence-driven-synthesis` and opened Draft PR #33;
  the PR remains unmerged and not Ready.
