# Agent Stage Latency Implementation Plan

- Status: Active
- Type: Plan
- Owners: Agent maintainers
- Last verified: 2026-08-13
- Source of truth: `docs/superpowers/specs/2026-08-13-agent-stage-latency-design.md` and the commands in this plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Produce a sanitized ten-scenario Docker Lite profile that identifies Agent stage latency and real provider token usage without changing Agent behavior.

**Architecture:** Extend the existing `genai_span` structured-log path with a fail-open timer, connect the existing Prometheus LLM metrics to real `usage_metadata`, and add only the missing request/router/graph/list-tools/HITL spans. A standalone script correlates requests to Docker JSON logs with unique trace IDs and writes a sanitized local artifact plus a short committed report.

**Tech Stack:** Python 3.11, FastAPI/SSE, LangGraph, structlog, prometheus-client, httpx, Docker Compose Lite, pytest.

## Global Constraints

- Base is `main@98cba51c4e658c9e0262aa1251a062d6257b89ac`; work only on `perf/agent-stage-latency`.
- Preserve Prompt, Router behavior, Evidence Gate, RBAC, HITL semantics, MCP business logic, RAG algorithm, Checkpoint protocol, Eval contract, and tool budgets.
- Reuse `agent.trace.genai_span`, `agent.metrics`, structlog, runtime events, `usage_metadata`, and Prometheus.
- Do not add an observability service, database migration, high-cardinality Prometheus label, or raw Prompt/answer/tool payload artifact.
- Missing provider usage remains unknown/null and is never reported as measured zero.
- Run each representative scenario once at concurrency one; allow at most one disclosed diagnostic rerun for measurement failure.
- Do not run the 24x2 baseline, approve refund/compensation, implement a new Fast Path, mark the PR Ready, or merge it.

---

### Task 1: Fail-open span and metric primitives

**Files:**
- Modify: `copilot-agent-service/agent/trace.py`
- Modify: `copilot-agent-service/agent/metrics.py`
- Modify: `copilot-agent-service/tests/test_trace.py`
- Create: `copilot-agent-service/tests/test_metrics.py`

**Interfaces:**
- Produces: `SpanTimer(name: str, kind: str, **attrs)`, `finish(status="ok", **attrs)`, and existing `genai_span` backed by that timer.
- Produces: `record_llm_call(role, input_tokens, output_tokens, duration_seconds) -> bool`, returning false for missing/invalid usage and never raising into business code.

- [x] **Step 1: Add RED tests** proving span logging failures do not replace business results, duplicate finish is harmless, negative/missing tokens do not mutate counters, unknown roles normalize to `unknown`, and no session/thread/trace labels exist on LLM metrics.
- [x] **Step 2: Run** `pytest -q tests/test_trace.py tests/test_metrics.py` and confirm failures identify the missing fail-open timer and validation.
- [x] **Step 3: Implement** the minimum timer and safe metric adapter. Keep the current span event names and duration semantics.
- [x] **Step 4: Re-run** the focused tests and `git diff --check`.
- [x] **Step 5: Commit** as `feat(perf): add fail-open stage measurement primitives` with Goal/Changes/Verification/Risk body.

### Task 2: Connect real LLM duration and token usage

**Files:**
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/agent/state.py`
- Modify: `copilot-agent-service/api/chat.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`
- Modify: `copilot-agent-service/tests/test_chat_api.py`

**Interfaces:**
- Consumes: Task 1 `record_llm_call` and existing `genai_span`.
- Produces: sanitized `llm_call_measured` log fields `step`, `provider`, `model`, `duration_ms`, nullable token counts, and `usage_status`.
- Produces: state totals `llm_call_count`, `llm_input_tokens`, `llm_output_tokens`, and `llm_usage_missing_count` for request summaries.

- [x] **Step 1: Add RED tests** for valid usage, absent usage, malformed/negative usage, structured correlation fields, and absence of Prompt/response/tool payload in measurement logs.
- [x] **Step 2: Run** the exact new node/chat tests and confirm `record_llm_call` is not yet called and missing usage is currently coerced to zero.
- [x] **Step 3: Measure** only the real `ainvoke` call with `perf_counter`; normalize token usage without character estimation; update state totals only from reliable integers; emit no raw metadata object.
- [x] **Step 4: Run** `pytest -q tests/test_agent_nodes.py tests/test_chat_api.py tests/test_metrics.py tests/test_trace.py` (`130 passed`).
- [x] **Step 5: Commit** as `feat(perf): record real llm latency and usage` with before/after evidence.

### Task 3: Add missing request and stage boundaries

**Files:**
- Modify: `copilot-agent-service/api/chat.py`
- Modify: `copilot-agent-service/mcp/mcp_client.py`
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/tests/test_chat_api.py`
- Modify: `copilot-agent-service/tests/test_mcp_client.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`

**Interfaces:**
- Produces spans: `request.total`, `session.prepare`, `router.classify`, `graph.total`, `mcp.list_tools`, and `hitl.prepare`.
- Produces sanitized terminal `agent_run_measured` with correlation, route, totals, tool count, stop reason, and total duration.

- [x] **Step 1: Add RED tests** for Fast Path and ReAct request timing, normal/error/HITL closure, cache hit/miss list-tools timing, HITL preparation timing, and instrumentation-log failure isolation.
- [x] **Step 2: Run** the focused tests and confirm the six spans/summary are absent (`6` focused assertions failed before implementation).
- [x] **Step 3: Implement** stage boundaries around existing calls without moving business decisions. Extract only a pure Fast Path classification helper if required to avoid including MCP execution in router time.
- [x] **Step 4: Re-run** focused tests and verify no request content, answer, tool values, or business identifiers enter measurement fields (`158 passed`).
- [x] **Step 5: Commit** as `feat(perf): trace agent request stages`.

### Task 4: Build the sanitized Docker collector

**Files:**
- Create: `scripts/profile-agent-latency.py`
- Create: `copilot-agent-service/tests/test_stage_latency_profile.py`
- Modify: `.gitignore` only if the chosen raw artifact directory is not already ignored.

**Interfaces:**
- Consumes: `/chat` SSE, unique `X-Trace-Id`, and Agent container JSON logs.
- Produces: sanitized JSON schema with runtime metadata, scenario rows, non-overlapping stage totals/shares, nullable token totals, safe tool names, and no-rerun disclosure.
- Produces: Markdown table and the eight required analysis answers from the same in-memory result.

- [x] **Step 1: Add RED tests** for span parsing, trace filtering, nested-span non-double-counting, missing token preservation, percentage math, allowed artifact keys, and forbidden content rejection.
- [x] **Step 2: Run** `pytest -q tests/test_stage_latency_profile.py` and confirm the collector is absent (`5 failed`).
- [x] **Step 3: Implement** the standard-library collector with fixture-backed scenario descriptors; never approve HITL and never persist request/answer text.
- [x] **Step 4: Run** collector unit tests (`5 passed`), `python3 -m py_compile scripts/profile-agent-latency.py`, and `git diff --check`.
- [x] **Step 5: Commit** as `test(perf): add sanitized agent latency profiler`.

### Task 5: Docker measurement, report, and full gates

**Files:**
- Create: `docs/performance/03-agent-stage-latency-report.md`
- Modify: `docs/performance/01-metric-contract.md`
- Modify: `docs/文档清单.md`
- Modify: this plan for measured evidence and completion state.
- Keep raw JSON under ignored `artifacts/performance/agent-stage-latency-<timestamp>/`.

**Interfaces:**
- Consumes: current-source Agent image, current Lite fixtures, DeepSeek V4 Flash, and Task 4 collector.
- Produces: one committed aggregate report with scenario table, stage/token shares, bottlenecks, next Fast Path candidates, graph-overhead decision, and limitations.

- [ ] **Step 1: Rebuild** the Agent image from this worktree and verify its source/image SHA plus MySQL, Redis, Server, Copilot, Agent, Embedding, and Reranker health required by the selected scenarios.
- [ ] **Step 2: Run** the ten scenarios once at concurrency one. If one trace is incomplete, diagnose and at most rerun only that scenario once, recording both attempts.
- [ ] **Step 3: Validate** no refund/compensation approval was executed, no raw Prompt/answer/tool payload or credential appears in artifacts, and high-risk scenarios stop at PENDING.
- [ ] **Step 4: Write** the short report answering all eight required questions; do not claim causality or capacity from one observation.
- [ ] **Step 5: Run full gates:** Agent full suite, coverage gate, existing mutation workflow command, docs check, secret scan, and `git diff --check`.
- [ ] **Step 6: Commit** report/contract evidence as `docs(perf): report agent stage latency baseline`.

### Task 6: Independent review and Draft PR

**Files:**
- No feature expansion; fix only in-scope blocking findings.

- [ ] **Step 1: Verify** branch is clean, `0 behind` `origin/main`, all commits are in scope, and no generated/raw artifact or secret is tracked.
- [ ] **Step 2: Review** timing boundaries, non-overlap math, usage null semantics, fail-open behavior, metric cardinality, HITL non-execution, and behavior-diff absence.
- [ ] **Step 3: Push** `perf/agent-stage-latency` and create a Draft PR to `main` with final head, tests, ten rows, token/stage findings, limits, and `BLOCKING FINDINGS`.
- [ ] **Step 4: Stop** at Draft. Do not start Fast Path optimization, mark Ready, or merge.
