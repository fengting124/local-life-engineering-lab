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

- `shop_metrics_query` currently requires only `date`; `CopilotOrderMapper.selectShopMetrics` uses `DATE(created_at) = date`.
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

- [ ] Write tests asserting the resolved Case 17 contract allows only `query_order`, forbids both admin diagnostic tools, requires `permission_denied`, and retains PAID plus escalation facts.
- [ ] Write tests asserting Case 19 requires `clarification`, no tools, no HITL, and that scoring rejects any non-clarification stop.
- [ ] Write tests asserting the missing-order fixture is numeric, at least 12 digits, and rejected if its database count is nonzero.
- [ ] Write tests asserting Case 49 uses the fixture and expects `query_order -> not_found`.
- [ ] Run the four test modules and confirm failures are caused by the old contracts.
- [ ] Apply only the contract, fixture, and expected-outcome scoring changes.
- [ ] Re-run the four modules and commit with Goal, Changes, Verification, and Risk sections.

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

- [ ] Write JUnit tests for single date, complete inclusive range, missing range endpoint, reversed range, malformed date, and injected fixed `today` resolution.
- [ ] Write a mapper contract test proving one range query aggregates both boundary dates.
- [ ] Run the targeted JUnit tests and confirm RED.
- [ ] Extend the JSON schema and argument validation to accept exactly one date or one complete range.
- [ ] Change the mapper predicate to `created_at >= start_date` and `< end_date + 1 day`; update both callers.
- [ ] Re-run targeted and full Copilot tests; commit the backward-compatible range contract.

### Task 3: Make month-to-date deterministic

**Files:**
- Modify: `copilot-agent-service/api/chat.py`
- Modify: `copilot-agent-service/agent/tool_router.py`
- Test: `copilot-agent-service/tests/test_chat_api.py`
- Test: `copilot-agent-service/tests/test_tool_router.py`

**Interfaces:**
- Changes: `_try_fast_path(..., today: date | None = None) -> str | None`.
- Produces: one MCP call with `start_date` and `end_date` for current-month phrases.

- [ ] Write parameterized RED tests for `本月 GMV`, `这个月销售额`, and `这月营业额` using a fixed date.
- [ ] Assert today/yesterday arguments are unchanged, missing metrics still clarify, and month-to-date calls the tool exactly once.
- [ ] Run targeted tests and confirm RED.
- [ ] Compute month boundaries from the injected date in Fast Path and classify month-to-date as supported analytics.
- [ ] Re-run targeted tests and commit the deterministic routing change.

### Task 4: Preserve CS evidence and terminate safely

**Files:**
- Modify: `copilot-agent-service/agent/nodes.py`
- Test: `copilot-agent-service/tests/test_agent_nodes.py`
- Test: `copilot-agent-service/tests/test_e2e_agent.py`

**Interfaces:**
- Produces: `permission_denied` answer containing bounded order status and administrator escalation reason.
- Produces: `clarification` as the terminal stop reason for clarification routes.

- [ ] Write RED tests proving CS executes only `query_order`, retains PAID evidence, calls no admin tool, and returns `permission_denied` with escalation wording.
- [ ] Write RED tests proving valid not-found stops after one tool and malformed order input uses zero tools.
- [ ] Write RED tests proving missing refund amount finalizes as `clarification`, while explicit amount still reaches existing HITL.
- [ ] Run targeted tests and confirm RED.
- [ ] Add the smallest bounded terminal rendering and clarification finalization in `nodes.py`.
- [ ] Re-run targeted tests and commit without changing EvidenceAnswer or HITL.

### Task 5: Deterministic quality gates

**Files:** No production changes expected.

- [ ] Run Router, Eval, scoring, node, API, and E2E targeted tests.
- [ ] Run the Agent full suite with the repository coverage gate.
- [ ] Run the existing Agent mutation gate with the CI command and threshold.
- [ ] Run the Copilot full test suite because the metrics tool changed.
- [ ] Run docs checks and `git diff --check`.
- [ ] Record exact test, coverage, and mutation totals in the implementation plan and report.

### Task 6: Docker Lite and targeted DeepSeek evidence

**Files:**
- Update: `docs/performance/02-backend-agent-baseline-report.md`
- Update: this implementation plan's evidence section.

- [ ] Rebuild Copilot and Agent from the current branch source with the repository Lite Compose files.
- [ ] Confirm all Lite services are healthy and the new range schema appears in MCP tool discovery.
- [ ] Prove the numeric missing-order fixture has count zero.
- [ ] Run Case 3 three times, Case 17 three times, and Case 49 three times using DeepSeek V4 Flash at concurrency one.
- [ ] Verify Case 3 is 3/3 with one correct range query, Case 17 is 3/3 with zero admin-only executions, and Case 49 is 3/3 with one query followed by not-found.
- [ ] Verify unknown tools, protocol errors, and preapproval high-risk executions are all zero.
- [ ] Do not run the full 24 by two baseline and do not repeat the targeted set to select a better result.

### Task 7: Review and Draft PR

**Files:**
- Update: `docs/00-overview/文档清单.md` only if required by the docs checker.

- [ ] Inspect `git diff origin/main...HEAD`, generated files, secrets, database files, and ignored artifacts.
- [ ] Perform an independent scope and safety review; record `BLOCKING FINDINGS` with file and line evidence.
- [ ] Commit final evidence with Goal, Changes, Verification, and Risk sections.
- [ ] Push `fix/agent-product-semantics-routing` and create a Draft PR to `main`.
- [ ] Stop without marking Ready or merging.

## Final Evidence

To be filled only from executed command output. Historical failures above must remain intact.
