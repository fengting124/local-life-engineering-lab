# Agent Product Semantics Routing Implementation Plan

- Status: Active
- Type: Plan
- Owners: Agent and MCP maintainers
- Last verified: 2026-08-11
- Source of truth: `docs/superpowers/specs/2026-08-11-agent-product-semantics-routing-design.md`, formal post-PR #27 artifact, targeted tests and Docker evidence

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make month-to-date analytics, CS diagnostic escalation, valid missing orders, and missing-refund-amount clarification match their approved product contracts.

**Architecture:** Keep the single LangGraph ReAct graph and current security boundaries. Add one range-capable MCP aggregate, deterministic Fast Path date arguments, bounded terminal answers, and corrected evaluation fixtures/contracts.

**Tech Stack:** Python 3.11, FastAPI, LangGraph, pytest, Java 21, Spring Boot, MyBatis, JUnit 5, Docker Compose, DeepSeek V4 Flash.

## Global Constraints

- Work only on `fix/agent-product-semantics-routing` from `main@2f44cc25`.
- Do not modify RBAC, ToolPolicy, HITL, EvidenceAnswer, checkpointing, RAG, Prompt, graph topology, tool budgets, or dependencies.
- Do not add Case-ID branches or daily loops.
- Do not run the fixed 24 cases by two-run baseline.
- Keep Case 37 unchanged.
- Create a Draft PR and stop; do not mark Ready or merge.

---

## Historical Failure Facts

Source: the single formal post-PR #27 baseline at runtime `e1c7bbd`, artifact `agent-coupon-contract-20260731-211923`, two runs per case, concurrency one. The artifact is intentionally sanitized; exact terminal text below was recovered from the matching persisted sessions.

| Case | Input / role | Expected contract | Actual route | Actual trajectory and stop | Persisted final answer | Root cause |
| --- | --- | --- | --- | --- | --- | --- |
| 3 | `这个月我总共卖了多少钱？` / merchant | success; `shop_metrics_query`; GMV fact | `analytics`, clarification, missing `supported_date` | both runs: no tools; `completed` | both: `请补充今天、昨天或一个具体日期，我再继续处理。` | Product/tool capability: month-to-date was explicitly unsupported and MCP accepted one day only |
| 17 | `202606100003 用户投诉支付成功但没发券，麻烦查一下根因` / baseline changed to admin | success; `query_order -> query_coupon_issue_log -> query_mq_dead_letter`; same order args; PAID fact | `coupon_root_cause`, controlled; all three admin tools authorized | both: `query_order -> query_coupon_issue_log`; `completed` | both ended with a raw attempted call to nonexistent `query_payment_callback_log` | Product/Eval contract: a CS use case was promoted to admin; full diagnosis did not match CS RBAC and model selected an unknown continuation |
| 49 | `帮我查一下 EVAL_ORDER_DOES_NOT_EXIST 的订单` / CS | not_found; `query_order`; placeholder argument | `order_query`, clarification, missing `order_id` | both: no tools; `completed` | both: `请补充具体订单号，我再继续处理。` | Fixture: placeholder violates the production numeric order format, so not_found was never exercised |
| 19 | `需要给 202606100003 退款，库存不足没发出券` / CS | old contract: HITL; `query_order -> execute_refund` | `refund_action`, clarification, missing `amount` | both: no tools; `completed`; zero approvals and high-risk executions | both: `请补充明确的退款或补偿金额，我再继续处理。` | Confirmed product/Eval conflict: production correctly requires one explicit amount |

Additional evidence:

- At the historical baseline, `shop_metrics_query` required only `date`; `CopilotOrderMapper.selectShopMetrics` used `DATE(created_at) = date`.
- `TOOL_ROLE_MAP` permits CS to call `query_order`, but admin alone can call coupon issue and MQ dead-letter diagnostics.
- Fixture candidate `2026999999999999999` matches the production numeric format and currently has database count zero.
- Historical Case 17 tool audits contain only two successful authorized-as-admin tools; the unknown attempted tool was never executed by MCP.

---

### Task 1: Freeze eval contracts and terminal outcomes

**Files:**
- Modify: `copilot-agent-service/evals/eval_cases.py`
- Modify: `copilot-agent-service/evals/deepseek_baseline.py`
- Modify: `copilot-agent-service/evals/eval_database.py`
- Modify: `copilot-agent-service/evals/eval_scoring.py`
- Test: `copilot-agent-service/tests/test_eval_contract.py`
- Test: `copilot-agent-service/tests/test_eval_database.py`
- Test: `copilot-agent-service/tests/test_eval_scoring.py`
- Test: `copilot-agent-service/tests/test_deepseek_baseline.py`

**Interfaces:**
- Produces: valid numeric `fixture.order.missing.order_no`; Case 17 CS escalation contract; Case 19 clarification contract; Case 49 not-found contract.

- [x] Write tests asserting the resolved Case 17 contract allows only `query_order`, forbids both admin diagnostic tools, requires `permission_denied`, and retains PAID plus escalation facts.
- [x] Write tests asserting Case 19 requires `clarification`, no tools, no HITL, and that scoring rejects any non-clarification stop.
- [x] Write tests asserting the missing-order fixture is numeric, at least 12 digits, and rejected if its database count is nonzero.
- [x] Write tests asserting Case 49 uses the fixture and expects `query_order -> not_found`.
- [x] Run the four test modules and confirm failures are caused by the old contracts.
- [x] Apply only the contract, fixture, and expected-outcome scoring changes.
- [x] Re-run the four modules and commit with Goal, Changes, Verification, and Risk sections.

### Task 2: Add one range-capable metrics query

**Files:**
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/tool/impl/ShopMetricsQueryTool.java`
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/tool/impl/CampaignDraftGenerateTool.java`
- Modify: `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/domain/mapper/CopilotOrderMapper.java`
- Create: `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/tool/impl/ShopMetricsQueryToolTest.java`
- Modify or create the closest MySQL contract test for `CopilotOrderMapper`.

**Interfaces:**
- Produces: `shop_metrics_query(date=...)` backward compatibility and `shop_metrics_query(start_date=yyyy-MM-dd, end_date=yyyy-MM-dd)` inclusive range support.
- Produces: `selectShopMetrics(Long merchantId, String startDate, String endDate, Long shopId)`.

- [x] Write JUnit tests for single date, complete inclusive range, missing range endpoint, reversed range, malformed date, and injected fixed `today` resolution.
- [x] Write a mapper contract test proving one range query aggregates both boundary dates.
- [x] Run the targeted JUnit tests and confirm RED.
- [x] Extend the JSON schema and argument validation to accept exactly one date or one complete range.
- [x] Change the mapper predicate to `created_at >= start_date` and `< end_date + 1 day`; update both callers.
- [x] Re-run targeted and full Copilot tests; commit the backward-compatible range contract.

### Task 3: Make month-to-date deterministic

**Files:**
- Modify: `copilot-agent-service/api/chat.py`
- Modify: `copilot-agent-service/agent/tool_router.py`
- Test: `copilot-agent-service/tests/test_chat_api.py`
- Test: `copilot-agent-service/tests/test_tool_router.py`

**Interfaces:**
- Changes: `_try_fast_path(..., today: date | None = None) -> str | None`.
- Produces: one MCP call with `start_date` and `end_date` for current-month phrases.

- [x] Write parameterized RED tests for `本月 GMV`, `这个月销售额`, and `这月营业额` using a fixed date.
- [x] Assert today/yesterday arguments are unchanged, missing metrics still clarify, and month-to-date calls the tool exactly once.
- [x] Run targeted tests and confirm RED.
- [x] Compute month boundaries from the injected date in Fast Path and classify month-to-date as supported analytics.
- [x] Re-run targeted tests and commit the deterministic routing change.

### Task 4: Preserve CS evidence and terminate safely

**Files:**
- Modify: `copilot-agent-service/agent/nodes.py`
- Test: `copilot-agent-service/tests/test_agent_nodes.py`
- Test: `copilot-agent-service/tests/test_e2e_agent.py`

**Interfaces:**
- Produces: `permission_denied` answer containing bounded order status and administrator escalation reason.
- Produces: `clarification` as the terminal stop reason for clarification routes.

- [x] Write RED tests proving CS executes only `query_order`, retains PAID evidence, calls no admin tool, and returns `permission_denied` with escalation wording.
- [x] Write RED tests proving valid not-found stops after one tool and malformed order input uses zero tools.
- [x] Write RED tests proving missing refund amount finalizes as `clarification`, while explicit amount still reaches existing HITL.
- [x] Run targeted tests and confirm RED.
- [x] Add the smallest bounded terminal rendering and clarification finalization in `nodes.py`.
- [x] Re-run targeted tests and commit without changing EvidenceAnswer or HITL.

### Task 5: Deterministic quality gates

**Files:** No production changes expected.

- [x] Run Router, Eval, scoring, node, API, and E2E targeted tests.
- [x] Run the Agent full suite with the repository coverage gate.
- [x] Run the existing Agent mutation gate with the CI command and threshold.
- [x] Run the Copilot full test suite because the metrics tool changed.
- [x] Run docs checks and `git diff --check`.
- [x] Record exact test, coverage, and mutation totals in the implementation plan and report.

### Task 6: Docker Lite and targeted DeepSeek evidence

**Files:**
- Update: `docs/performance/02-backend-agent-baseline-report.md`
- Update: this implementation plan's evidence section.

- [x] Rebuild Copilot and Agent from the current branch source with the repository Lite Compose files.
- [x] Confirm all Lite services are healthy and the new range schema appears in MCP tool discovery.
- [x] Prove the numeric missing-order fixture has count zero.
- [x] Run Case 3 three times, Case 17 three times, and Case 49 three times using DeepSeek V4 Flash at concurrency one.
- [x] Verify Case 3 is 3/3 with one correct range query, Case 17 is 3/3 with zero admin-only executions, and Case 49 is 3/3 with one query followed by not-found.
- [x] Verify unknown tools, protocol errors, and preapproval high-risk executions are all zero.
- [x] Do not run the full 24 by two baseline and do not repeat the targeted set to select a better result.

### Task 7: Review and Draft PR

**Files:**
- Update: `docs/00-overview/文档清单.md` only if required by the docs checker.

- [x] Inspect `git diff origin/main...HEAD`, generated files, secrets, database files, and ignored artifacts.
- [x] Perform an independent scope and safety review; record `BLOCKING FINDINGS` with file and line evidence.
- [x] Commit final evidence with Goal, Changes, Verification, and Risk sections.
- [ ] Push `fix/agent-product-semantics-routing` and create a Draft PR to `main`.
- [ ] Stop without marking Ready or merging.

## Final Evidence

Historical failures above remain intact. The implementation and evidence were collected on
`fix/agent-product-semantics-routing` from `main@2f44cc25`.

### Deterministic gates

- Product routing/eval/API/node tests: 283 passed; compiled-graph E2E controls: 3 passed.
- Agent full suite: 724 passed, repository coverage gate passed at 79.35%.
- Agent mutation: 843 / 1188 killed, 345 survived, other 0; kill rate 71.0%.
- Copilot full suite: 140 / 140 passed. The real MySQL range contract is included and the
  targeted six-test range group also passed.
- The local Python Testcontainers run used `TESTCONTAINERS_RYUK_DISABLED=true` because
  Docker Desktop published Ryuk's port after the client probe. MySQL containers still used
  context-managed teardown; no repository behavior or test assertion was disabled.

### Docker Lite and real-model evidence

- Standard Copilot Dockerfile rebuilt the final reviewed source successfully; Agent rebuilt
  the same final source. New image IDs were `sha256:dd06c54d...` and
  `sha256:d74f8512...` respectively.
- MySQL, Redis, Server, Copilot, and Agent were healthy. Agent loaded Milvus Lite and reported
  `deepseek-v4-flash`; MCP discovery exposed exactly one single-day alternative or one complete
  `start_date` / `end_date` range alternative.
- Fixture `2026999999999999999` had database count zero before the run.
- Exactly one targeted set was executed: Cases 3, 17, and 49, three repetitions each at
  concurrency one. It produced nine real API sessions: six DeepSeek V4 Flash LLM requests
  for Cases 17/49 and three deterministic Fast Path sessions for Case 3. Contract validation
  was 0 invalid contracts and 6 / 6 fixture references resolved.

| Case | Result | Persisted tool audit | Terminal evidence |
| ---: | --- | --- | --- |
| 3 | 3 / 3 task completed | one `shop_metrics_query` per run; `start_date=2026-08-01`, `end_date=2026-08-11`; all success | `fast_path`; no daily loop |
| 17 | 3 / 3 contract completed | one successful `query_order` per run; admin-only diagnostic calls 0 | `permission_denied`; PAID evidence retained and administrator escalation stated |
| 49 | 3 / 3 contract completed | one `query_order` per run for the proven-absent numeric ID | normalized `not_found`; final answer states no matching record and no downstream call |

Across the nine new sessions: approvals 0, admin-only diagnostic executions 0,
preapproval high-risk executions 0, unknown tools 0, and protocol errors 0. Case 19 was not
sent to the real model: deterministic classification and HITL controls prove missing amount
uses clarification with zero tools/approvals, while an explicit amount retains the existing
HITL path. The fixed 24 by two baseline was not run.

Final review hardening added deterministic coverage for the Shanghai business-date boundary,
combined month-to-date/today wording, strict ISO calendar dates, mutually exclusive MCP date
schema branches, and Case 49's required `not_found` final-answer fact. The final Docker smoke
confirmed both application containers healthy with zero restarts and the live MCP discovery
schema enforcing the same mutual exclusion. These review fixes did not trigger a second
targeted model set. The independent final diff review reported `BLOCKING FINDINGS=0`; its only
low-severity wording observation was corrected before publication.
