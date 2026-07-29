# Agent Routing Quality Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

- Status: Active
- Type: Plan
- Owners: Project maintainers
- Last verified: 2026-07-29
- Current phase: Implementation complete; awaiting fresh CI and independent review
- Source of truth: `docs/superpowers/specs/2026-07-26-agent-routing-quality-design.md`

**Goal:** Add deterministic task classification, one-tool-at-a-time routing, normalized evidence progression, and reliable stopping to the existing single LangGraph ReAct Agent.

**Architecture:** `ToolRouter` classifies the original request once and exposes only the current authorized tool. The pure `EvidenceGate` converts tool results into bounded facts, chooses the next required tool, or records a terminal outcome. High-risk routes additionally retain a hashed order target and requested minor-unit amount so tool calls, tool results, and approval proposals remain bound to the original request. Existing `ToolPolicy`, HITL resume protocol, Reflection, Auto-Compact, RAG, evaluation contracts, Java services, and production dependency versions remain unchanged.

**Tech Stack:** Python 3.11, FastAPI, LangChain 0.3.7, langchain-openai 0.2.6, LangGraph 0.2.45, pytest 9.0.3, Docker Compose, DeepSeek `deepseek-v4-flash`

## Global Constraints

- Work only on `fix/agent-routing-quality`; never commit directly to `main`.
- Push `fix/agent-routing-quality` after each verified commit; do not open the Draft PR until the final branch review.
- Preserve `TOOL_ROLE_MAP` as the only Python role-permission source.
- Preserve all four PR #25 tool-budget layers and SHA-256 call signatures.
- Do not change `agent/tool_policy.py`, production permissions, HITL approval/resume/checkpoint behavior, prompts, model settings, production dependencies, evaluation contracts, RAG, Java services, migrations, or Compose topology.
- Keep one LangGraph ReAct graph; do not add nodes or introduce a multi-agent architecture.
- Do not raise a budget or expose a substitute tool when a required tool is unauthorized.
- Controlled evidence routes bind exactly one `next_tool`; `small_talk` is the
  direct-response, no-tool exception; `general_fallback` exposes only the
  existing role-filtered read-only general set.
- Store only normalized control facts in state; never store raw arguments, IDs, amounts, user data, or raw tool output in evidence.
- `coupon_usage_status=UNUSED` never proves coupon-delivery failure and never unlocks compensation.
- Use TDD for every behavior change and keep each commit independently reviewable.

---

## File Map

| File | Responsibility |
| --- | --- |
| `copilot-agent-service/agent/tool_router.py` | Immutable route decision, scored request classification, role intersection, and visible-tool selection |
| `copilot-agent-service/agent/evidence_gate.py` | Pure result normalization, bounded evidence records, retry/terminal decisions, and next-tool progression |
| `copilot-agent-service/agent/state.py` | Checkpointed route and evidence field types |
| `copilot-agent-service/api/chat.py` | Compute one route decision for a new run and initialize state |
| `copilot-agent-service/agent/nodes.py` | Bind the selected tool, collect structured outcomes, and synthesize only after evidence completion |
| `copilot-agent-service/agent/graph.py` | Read evidence state in existing conditional edges |
| `copilot-agent-service/guardrails/input_checker.py` | Narrow explicit cross-merchant and bulk-action blocking rules |
| `copilot-agent-service/tests/test_tool_router.py` | Scoring, ambiguity, clarification, plans, and role filtering |
| `copilot-agent-service/tests/test_evidence_gate.py` | Normalization, progression, retry, failure, and high-risk evidence tests |
| `copilot-agent-service/tests/test_agent_nodes.py` | Specific `tool_choice`, no-tool synthesis, outcome integration, and policy regressions |
| `copilot-agent-service/tests/test_agent_graph.py` | Evidence-aware edge priority and unchanged graph topology |
| `copilot-agent-service/tests/test_chat_api.py` | Initial route state and terminal Guardrail audit fields |
| `copilot-agent-service/tests/test_e2e_agent.py` | Complete controlled tool loop and deterministic stopping |
| `copilot-agent-service/tests/test_guardrails.py` | Positive and false-positive Guardrail cases |
| `docs/performance/02-backend-agent-baseline-report.md` | Human-readable real DeepSeek comparison |
| `docs/performance/baseline-summary.json` | Machine-readable current baseline |

### Task 1: Deterministic Route Decisions

**Files:**
- Modify: `copilot-agent-service/agent/tool_router.py`
- Modify: `copilot-agent-service/tests/test_tool_router.py`

**Interfaces:**
- Produces: `RouteDecision`, `classify_request(user_role: str, message: str) -> RouteDecision`
- Produces: `ToolRouter.from_state(state: Mapping[str, object]) -> ToolRouter`
- Produces: `RouteDecision.to_state() -> dict[str, object]`
- Preserves: `TOOL_ROLE_MAP`, `is_tool_allowed_for_role()`, and `is_tool_concurrency_safe()`

- [ ] **Step 1: Replace keyword-order tests with scored classification tests**

Add table-driven cases that verify semantic precedence and missing-anchor handling:

```python
@pytest.mark.parametrize(
    ("message", "role", "task_type", "route_mode", "next_tool"),
    [
        ("今天有多少笔订单？", "merchant", "analytics", "controlled", "shop_metrics_query"),
        ("退款规则是什么？", "merchant", "knowledge", "controlled", "knowledge_search"),
        ("给订单 202606100001 退款", "cs", "refund_action", "controlled", "query_order"),
        ("订单 202606100001 支付失败是什么原因？", "admin", "payment_diagnosis", "controlled", "query_order"),
        ("订单 202606100001 没收到券，查根因", "admin", "coupon_root_cause", "controlled", "query_order"),
        ("按照平台规则创建优惠券活动", "merchant", "campaign_draft", "controlled", "coupon_policy_lookup"),
        ("帮我查一下", "cs", "unknown", "clarification", None),
        ("这个月我总共卖了多少钱？", "merchant", "analytics", "clarification", None),
        ("哈哈哈这个活动好玩！", "merchant", "small_talk", "controlled", None),
    ],
)
def test_classify_request(message, role, task_type, route_mode, next_tool):
    decision = classify_request(role, message)
    assert decision.task_type == task_type
    assert decision.route_mode == route_mode
    assert decision.next_tool == next_tool
```

Add ambiguity and hierarchy assertions:

```python
def test_complete_payment_and_coupon_mix_uses_bounded_fallback():
    decision = classify_request(
        "admin",
        "分析订单 202606100001 的支付和优惠券异常",
    )
    assert decision.route_mode == "general_fallback"
    assert "execute_refund" not in decision.authorized_tools


def test_coupon_root_cause_beats_coupon_issue_parent_route():
    decision = classify_request(
        "admin",
        "订单 202606100001 支付成功但没发券，查一下根因",
    )
    assert decision.task_type == "coupon_root_cause"
```

- [ ] **Step 2: Run the focused tests and confirm the old implementation fails**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest \
  tests/test_tool_router.py -q
```

Expected: FAIL because `classify_request` and `RouteDecision` do not exist and the old first-keyword behavior returns broad task groups.

- [ ] **Step 3: Implement the immutable route contract and scored classifier**

Replace `TOOL_TASK_MAP`, `TASK_KEYWORDS`, and raw context matching with these bounded contracts:

```python
from dataclasses import dataclass
from typing import Mapping, Sequence

ROUTE_MODES = {"controlled", "clarification", "general_fallback"}
GENERAL_READ_ONLY_TOOLS = (
    "query_order",
    "shop_metrics_query",
    "knowledge_search",
    "coupon_policy_lookup",
)
TASK_TOOL_PLANS: dict[str, tuple[str, ...]] = {
    "analytics": ("shop_metrics_query",),
    "order_query": ("query_order",),
    "payment_diagnosis": ("query_order", "query_payment"),
    "coupon_issue": ("query_order", "query_coupon_issue_log"),
    "coupon_root_cause": (
        "query_order",
        "query_coupon_issue_log",
        "query_mq_dead_letter",
    ),
    "mq_diagnosis": ("query_order", "query_mq_dead_letter"),
    "knowledge": ("knowledge_search",),
    "policy_configuration": ("knowledge_search", "coupon_policy_lookup"),
    "refund_action": ("query_order", "execute_refund"),
    "compensation_action": (
        "query_order",
        "query_coupon_issue_log",
        "issue_compensation_coupon",
    ),
}


@dataclass(frozen=True)
class RouteDecision:
    task_type: str
    route_mode: str
    confidence: int
    required_tools: tuple[str, ...] = ()
    authorized_tools: tuple[str, ...] = ()
    next_tool: str | None = None
    missing_fields: tuple[str, ...] = ()

    def to_state(self) -> dict[str, object]:
        return {
            "route_task_type": self.task_type,
            "route_mode": self.route_mode,
            "route_confidence": self.confidence,
            "route_required_tools": list(self.required_tools),
            "route_authorized_tools": list(self.authorized_tools),
            "route_next_tool": self.next_tool,
            "route_missing_fields": list(self.missing_fields),
        }

    @classmethod
    def from_state(cls, state: Mapping[str, object]) -> "RouteDecision":
        return cls(
            task_type=str(state.get("route_task_type", "unknown")),
            route_mode=str(state.get("route_mode", "clarification")),
            confidence=int(state.get("route_confidence", 0)),
            required_tools=tuple(state.get("route_required_tools", ())),
            authorized_tools=tuple(state.get("route_authorized_tools", ())),
            next_tool=state.get("route_next_tool"),
            missing_fields=tuple(state.get("route_missing_fields", ())),
        )
```

Use one normalized request, explicit feature booleans, additive scores, and the specification tie-break order. Keep route construction in one helper:

```python
def _decision(
    user_role: str,
    task_type: str,
    score: int,
    *,
    route_mode: str = "controlled",
    required_tools: Sequence[str] = (),
    missing_fields: Sequence[str] = (),
) -> RouteDecision:
    required = tuple(required_tools)
    authorized = tuple(
        name for name in required
        if is_tool_allowed_for_role(name, user_role)
    )
    first = required[0] if required and required[0] in authorized else None
    return RouteDecision(
        task_type=task_type,
        route_mode=route_mode,
        confidence=min(score, 100),
        required_tools=required,
        authorized_tools=authorized,
        next_tool=first,
        missing_fields=tuple(missing_fields),
    )
```

The classifier must implement these exact precedence rules:

```text
1. Explicit high-risk execution verb + one concrete order target.
2. Explicit MQ, coupon-root-cause, coupon-issue, or payment diagnosis.
3. Analytics with metric and one supported date.
4. Policy configuration or knowledge question.
5. Campaign authoring.
6. Order query.
7. Small talk with no business entity, metric, or action.
```

Before returning a scored route, enforce:

```text
order-dependent route without order ID -> clarification(order_id)
analytics without metric -> clarification(metric)
analytics without date -> clarification(date)
month/week/range analytics -> clarification(supported_date)
high-risk action without one order ID -> clarification(order_id)
two unrelated scores >= 60 and delta < 20 -> general_fallback
```

For campaign authoring, set the plan to only `campaign_draft_generate` when threshold, validity, and purchase-limit constraints are all explicit. Otherwise set `("coupon_policy_lookup", "campaign_draft_generate")`.

- [ ] **Step 4: Implement visible-tool selection without text-context unlocks**

Implement `ToolRouter` as a facade over the retained decision:

```python
class ToolRouter:
    def __init__(self, user_role: str, user_message: str):
        self.user_role = user_role
        self.decision = classify_request(user_role, user_message)
        self.task_type = self.decision.task_type

    @classmethod
    def from_state(cls, state: Mapping[str, object]) -> "ToolRouter":
        router = cls.__new__(cls)
        router.user_role = str(state.get("user_role", ""))
        router.decision = RouteDecision.from_state(state)
        router.task_type = router.decision.task_type
        return router

    def route(self, all_tools: list[dict]) -> list[dict]:
        by_name = {tool["name"]: tool for tool in all_tools}
        if self.decision.route_mode == "clarification":
            return []
        if self.decision.route_mode == "controlled":
            name = self.decision.next_tool
            if (
                name is None
                or name not in self.decision.authorized_tools
                or not is_tool_allowed_for_role(name, self.user_role)
            ):
                return []
            return [by_name[name]] if name in by_name else []
        return [
            by_name[name]
            for name in GENERAL_READ_ONLY_TOOLS
            if name in by_name and is_tool_allowed_for_role(name, self.user_role)
        ]
```

Delete `_filter_by_context()` and all raw `paid`, `success`, `not_issued`, or `failed` high-risk unlock logic.

- [ ] **Step 5: Add role-intersection and serialization tests**

```python
def test_cs_knowledge_route_executes_zero_tools():
    decision = classify_request("cs", "退款规则是什么？")
    assert decision.required_tools == ("knowledge_search",)
    assert decision.authorized_tools == ()
    assert decision.next_tool is None


def test_route_state_round_trip_is_checkpoint_safe():
    original = classify_request(
        "admin",
        "订单 202606100001 支付失败是什么原因？",
    )
    restored = RouteDecision.from_state(original.to_state())
    assert restored == original


def test_controlled_router_exposes_exactly_one_tool():
    decision = classify_request(
        "admin",
        "订单 202606100001 支付失败是什么原因？",
    )
    router = ToolRouter.from_state(
        {"user_role": "admin", **decision.to_state()}
    )
    assert [tool["name"] for tool in router.route(_tools(*ALL_TOOLS))] == [
        "query_order"
    ]
```

- [ ] **Step 6: Run router tests and commit**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest tests/test_tool_router.py -q
```

Expected: all router tests PASS.

Commit:

```bash
git add copilot-agent-service/agent/tool_router.py \
  copilot-agent-service/tests/test_tool_router.py
git commit -m "feat(agent): add deterministic route decisions" \
  -m "Goal:
- Replace first-keyword routing with bounded scored decisions.

Changes:
- Add immutable RouteDecision state and minimal task plans.
- Separate clarification from bounded read-only fallback.
- Remove raw conversation-text high-risk unlocks.

Verification:
- DEBUG=false .venv/bin/python -m pytest tests/test_tool_router.py -q

Risk / Follow-up:
- Runtime nodes do not consume the new decision until the following commits."
```

### Task 2: Normalized Evidence Gate

**Files:**
- Create: `copilot-agent-service/agent/evidence_gate.py`
- Create: `copilot-agent-service/tests/test_evidence_gate.py`

**Interfaces:**
- Consumes: route fields produced by `RouteDecision.to_state()`
- Produces: `ToolOutcome(tool_name: str, status: str, facts: dict[str, object])`
- Produces: `normalize_tool_outcome(tool_name: str, raw_result: object = None, error_reason: str | None = None) -> ToolOutcome`
- Produces: `initial_evidence_state(decision: RouteDecision) -> dict[str, object]`
- Produces: `advance_evidence(state: Mapping[str, object], outcomes: Sequence[ToolOutcome]) -> dict[str, object]`

- [ ] **Step 1: Write normalization tests before the module exists**

Cover real Java and native RAG shapes:

```python
def test_query_order_normalizes_only_control_facts():
    outcome = normalize_tool_outcome(
        "query_order",
        json.dumps({
            "order_no": "SECRET-1",
            "user_id": "9001",
            "order_amount": 9900,
            "order_status": "PAID",
            "payment": {"pay_status": "SUCCESS", "trade_no": "SECRET"},
            "coupon": {"coupon_status": "UNUSED"},
        }),
    )
    assert outcome.status == "success"
    assert outcome.facts == {
        "found": True,
        "order_status": "PAID",
        "payment_status": "SUCCESS",
        "coupon_usage_status": "UNUSED",
    }
    assert "SECRET" not in repr(outcome)
    assert "9900" not in repr(outcome)


def test_unused_coupon_does_not_confirm_delivery_failure():
    outcome = normalize_tool_outcome(
        "query_order",
        '{"order_status":"PAID","payment":{},"coupon":{"coupon_status":"UNUSED"}}',
    )
    assert "coupon_failure_confirmed" not in outcome.facts


def test_structured_failed_outbox_confirms_coupon_failure():
    outcome = normalize_tool_outcome(
        "query_coupon_issue_log",
        '{"outbox_messages":[{"status":"FAILED"}],"coupon":{"coupon_status":"UNUSED"}}',
    )
    assert outcome.facts["coupon_issue_status"] == "FAILED"
    assert outcome.facts["coupon_failure_confirmed"] is True


@pytest.mark.parametrize(
    ("reason", "status"),
    [
        ("not_found", "not_found"),
        ("parameter_error", "parameter_error"),
        ("permission_denied", "permission_denied"),
        ("tool_timeout", "timeout"),
        ("business_rejected", "business_rejected"),
        ("anything_else", "internal_error"),
    ],
)
def test_mcp_reason_mapping(reason, status):
    assert normalize_tool_outcome(
        "query_order", error_reason=reason
    ).status == status
```

- [ ] **Step 2: Run the new test file and confirm import failure**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest tests/test_evidence_gate.py -q
```

Expected: FAIL with `ModuleNotFoundError: No module named 'agent.evidence_gate'`.

- [ ] **Step 3: Implement bounded normalization**

Create these immutable types and mappings:

```python
from __future__ import annotations

import json
from dataclasses import dataclass
from typing import Mapping, Sequence

from agent.tool_router import RouteDecision

VALID_STATUSES = {
    "success",
    "not_found",
    "parameter_error",
    "permission_denied",
    "timeout",
    "business_rejected",
    "internal_error",
    "pending_hitl",
}
ERROR_STATUS_MAP = {
    "not_found": "not_found",
    "parameter_error": "parameter_error",
    "permission_denied": "permission_denied",
    "tool_timeout": "timeout",
    "business_rejected": "business_rejected",
}
ORDER_STATUSES = {"WAIT_PAY", "PAID", "COMPLETED", "CANCELLED", "REFUNDED"}
PAYMENT_STATUSES = {"PENDING", "SUCCESS", "FAILED", "CLOSED"}
COUPON_USAGE_STATUSES = {"UNUSED", "USED", "EXPIRED"}
COUPON_ISSUE_STATUSES = {"PENDING", "SENT", "FAILED", "NO_RECORD"}


@dataclass(frozen=True)
class ToolOutcome:
    tool_name: str
    status: str
    facts: dict[str, object]
```

Implement JSON parsing as fail-closed for controlled read tools. High-risk action and campaign outputs may be treated as `success` without facts when their result is non-empty text. Normalize only:

```text
query_order -> found, order_status, payment_status, coupon_usage_status
query_payment -> found, payment_status
query_coupon_issue_log -> found, coupon_usage_status, coupon_issue_status,
                          coupon_failure_confirmed
query_mq_dead_letter -> found, mq_dead_letter_present
knowledge_search -> knowledge_found
coupon_policy_lookup -> policy_available
campaign_draft_generate -> campaign_draft_generated
```

Use `UNKNOWN` for unrecognized enums. Never copy an unknown key from raw data into `facts`.

- [ ] **Step 4: Write evidence progression tests**

```python
def _state(task, required, authorized, next_tool, records=None):
    return {
        "route_mode": "controlled",
        "route_task_type": task,
        "route_required_tools": required,
        "route_authorized_tools": authorized,
        "route_next_tool": next_tool,
        "evidence_collected": records or {},
        "evidence_complete": False,
        "evidence_stop_reason": None,
        "synthesis_only": False,
    }


def test_payment_route_advances_one_tool_at_a_time():
    state = _state(
        "payment_diagnosis",
        ["query_order", "query_payment"],
        ["query_order", "query_payment"],
        "query_order",
    )
    update = advance_evidence(
        state,
        [ToolOutcome("query_order", "success", {
            "found": True,
            "order_status": "WAIT_PAY",
            "payment_status": "SUCCESS",
        })],
    )
    assert update["route_next_tool"] == "query_payment"
    assert update["evidence_complete"] is False


def test_root_not_found_stops_without_downstream_tool():
    state = _state(
        "payment_diagnosis",
        ["query_order", "query_payment"],
        ["query_order", "query_payment"],
        "query_order",
    )
    update = advance_evidence(
        state,
        [ToolOutcome("query_order", "not_found", {"found": False})],
    )
    assert update["route_next_tool"] is None
    assert update["evidence_stop_reason"] == "not_found"


def test_refund_unlock_requires_structured_order_status():
    eligible = advance_evidence(
        _state(
            "refund_action",
            ["query_order", "execute_refund"],
            ["query_order", "execute_refund"],
            "query_order",
        ),
        [ToolOutcome("query_order", "success", {
            "found": True, "order_status": "PAID",
        })],
    )
    assert eligible["route_next_tool"] == "execute_refund"


def test_compensation_never_unlocks_from_unused_alone():
    update = advance_evidence(
        _state(
            "compensation_action",
            ["query_order", "query_coupon_issue_log", "issue_compensation_coupon"],
            ["query_order", "issue_compensation_coupon"],
            "query_order",
        ),
        [ToolOutcome("query_order", "success", {
            "found": True,
            "order_status": "PAID",
            "coupon_usage_status": "UNUSED",
        })],
    )
    assert update["route_next_tool"] is None
    assert update["evidence_stop_reason"] == "permission_denied"


def test_parameter_and_timeout_each_retry_once():
    for status in ("parameter_error", "timeout"):
        first = advance_evidence(
            _state("order_query", ["query_order"], ["query_order"], "query_order"),
            [ToolOutcome("query_order", status, {})],
        )
        assert first["route_next_tool"] == "query_order"
        second = advance_evidence(
            {**_state(
                "order_query", ["query_order"], ["query_order"], "query_order"
            ), "evidence_collected": first["evidence_collected"]},
            [ToolOutcome("query_order", status, {})],
        )
        assert second["route_next_tool"] is None
        assert second["evidence_stop_reason"] == status
```

Also cover permission, budget-adjacent terminal state, business rejection, malformed JSON, knowledge `found=false`, conditional campaign, conditional MQ, successful completion, and attempt increments.

- [ ] **Step 5: Implement progression and initial evidence state**

`initial_evidence_state()` must return:

```python
def initial_evidence_state(decision: RouteDecision) -> dict[str, object]:
    blocked_first = (
        decision.route_mode == "controlled"
        and bool(decision.required_tools)
        and decision.required_tools[0] not in decision.authorized_tools
    )
    return {
        "required_evidence": list(decision.required_tools),
        "evidence_collected": {},
        "evidence_complete": False,
        "evidence_stop_reason": (
            "permission_denied" if blocked_first else None
        ),
        "synthesis_only": False,
    }
```

`advance_evidence()` must:

1. Ignore deterministic progression for `general_fallback`.
2. Increment attempts for the outcome tool and replace only that tool's normalized record.
3. Retry `parameter_error` and `timeout` once, then terminate.
4. Terminate immediately for `not_found`, `permission_denied`, `business_rejected`, and `internal_error`.
5. Require `order_status in {"PAID", "COMPLETED"}` before refund.
6. Require `coupon_failure_confirmed is True` before compensation.
7. Continue coupon root-cause to MQ only when the coupon-log status is `FAILED`.
8. Stop with `permission_denied` when the next required tool is absent from `route_authorized_tools`.
9. Set `evidence_complete=True`, `synthesis_only=True`, and `route_next_tool=None` after the last successful evidence step.

- [ ] **Step 6: Run Evidence Gate tests and commit**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest tests/test_evidence_gate.py -q
```

Expected: all Evidence Gate tests PASS.

Commit:

```bash
git add copilot-agent-service/agent/evidence_gate.py \
  copilot-agent-service/tests/test_evidence_gate.py
git commit -m "feat(agent): add normalized evidence gate" \
  -m "Goal:
- Make tool progression and stopping independent of free-form model text.

Changes:
- Normalize tool results into bounded non-sensitive facts.
- Distinguish transport success from business outcomes.
- Add one-retry and structured high-risk progression rules.

Verification:
- DEBUG=false .venv/bin/python -m pytest tests/test_evidence_gate.py -q

Risk / Follow-up:
- Runtime nodes are connected to the Evidence Gate in later commits."
```

### Task 3: Checkpointed Route State Initialization

**Files:**
- Modify: `copilot-agent-service/agent/state.py`
- Modify: `copilot-agent-service/api/chat.py`
- Modify: `copilot-agent-service/tests/test_chat_api.py`
- Modify: `copilot-agent-service/tests/test_e2e_agent.py`

**Interfaces:**
- Consumes: `RouteDecision.to_state()` and `initial_evidence_state()`
- Produces: all route/evidence fields in every new graph invocation
- Preserves: Fast Path behavior and Guardrail HTTP response contract

- [ ] **Step 1: Add a chat endpoint test that captures initial graph state**

Patch `_try_fast_path` to return `None`, patch session/runtime persistence, and capture the first argument passed to `agent_graph.astream_events`. Assert:

```python
assert captured["route_task_type"] == "payment_diagnosis"
assert captured["route_mode"] == "controlled"
assert captured["route_required_tools"] == ["query_order", "query_payment"]
assert captured["route_next_tool"] == "query_order"
assert captured["evidence_collected"] == {}
assert captured["evidence_complete"] is False
assert captured["synthesis_only"] is False
```

Add a blocked-request log assertion that the existing `security_audit` event includes:

```python
route_mode="terminal"
stop_reason="guardrail_blocked"
```

- [ ] **Step 2: Run chat tests and confirm missing state**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest \
  tests/test_chat_api.py -q
```

Expected: the captured initial state lacks route and evidence fields.

- [ ] **Step 3: Extend `AgentState` with JSON-safe fields**

Add:

```python
route_task_type: str
route_mode: str
route_confidence: int
route_required_tools: list[str]
route_authorized_tools: list[str]
route_next_tool: str | None
route_missing_fields: list[str]

required_evidence: list[str]
evidence_collected: dict[str, dict[str, object]]
evidence_complete: bool
evidence_stop_reason: str | None
synthesis_only: bool
```

Do not add raw request text, identifiers, arguments, or tool payloads.

- [ ] **Step 4: Initialize the decision once after Fast Path fallback**

Immediately before `initial_state`:

```python
from agent.evidence_gate import initial_evidence_state
from agent.tool_router import classify_request

route_decision = classify_request(user_role, request.message)
route_state = route_decision.to_state()
evidence_state = initial_evidence_state(route_decision)
```

Merge `**route_state` and `**evidence_state` into `initial_state`. Add
`route_mode="terminal"` and `stop_reason="guardrail_blocked"` fields only to
the existing blocked audit event; keep its HTTP 400 body unchanged.

- [ ] **Step 5: Update graph-test fixtures with the same initializer**

In `tests/test_e2e_agent.py`, use:

```python
decision = classify_request("merchant", "今天卖了多少？")
state = {
    # existing fields
    **decision.to_state(),
    **initial_evidence_state(decision),
}
```

Update `ScriptedLLM.bind_tools` to accept `tool_choice=None` and retain it for
later assertions:

```python
def bind_tools(self, tools, tool_choice=None):
    self.bound_tools = tools
    self.tool_choice = tool_choice
    return self
```

- [ ] **Step 6: Run state and chat tests and commit**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest \
  tests/test_chat_api.py \
  tests/test_e2e_agent.py -q
```

Expected: all selected tests PASS.

Commit:

```bash
git add copilot-agent-service/agent/state.py \
  copilot-agent-service/api/chat.py \
  copilot-agent-service/tests/test_chat_api.py \
  copilot-agent-service/tests/test_e2e_agent.py
git commit -m "feat(agent): initialize route evidence state" \
  -m "Goal:
- Retain one routing decision throughout each checkpointed run.

Changes:
- Add JSON-safe route and evidence fields to AgentState.
- Initialize those fields after Fast Path fallback.
- Record terminal Guardrail route metadata without changing the API response.

Verification:
- DEBUG=false .venv/bin/python -m pytest tests/test_chat_api.py tests/test_e2e_agent.py -q

Risk / Follow-up:
- The LLM and tool nodes consume this state in the next commits."
```

### Task 4: Force the Unique Controlled Tool

**Files:**
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`

**Interfaces:**
- Consumes: `ToolRouter.from_state()` and `route_next_tool`
- Produces: `bind_tools([one_tool], tool_choice="<same-name>")` for controlled routes
- Produces: deterministic clarification and initial permission responses without MCP, RAG, or LLM calls
- Preserves: `_build_system_prompt()` content

- [ ] **Step 1: Extend fake LLM and write binding tests**

```python
class FakeLLM:
    def __init__(self, response):
        self._response = response
        self.bound_tools = []
        self.tool_choice = None
        self.seen_messages = []

    def bind_tools(self, tools, tool_choice=None):
        self.bound_tools = tools
        self.tool_choice = tool_choice
        return self
```

Add:

```python
@pytest.mark.asyncio
async def test_controlled_route_binds_one_named_tool(monkeypatch):
    mock_mcp = MagicMock()
    mock_mcp.list_tools = AsyncMock(return_value=[
        {"name": "query_order", "description": "查订单"},
        {"name": "query_payment", "description": "查支付"},
    ])
    fake_llm = FakeLLM(ai_with_tool_call(
        "query_order", {"order_id": "202606100001"}
    ))
    monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
    monkeypatch.setattr(nodes, "_llm", fake_llm)

    result = await nodes.llm_node(make_state(
        [HumanMessage(content="查订单 202606100001")],
        route_task_type="order_query",
        route_mode="controlled",
        route_required_tools=["query_order"],
        route_authorized_tools=["query_order"],
        route_next_tool="query_order",
        route_missing_fields=[],
        synthesis_only=False,
    ))

    names = {
        tool["name"] if isinstance(tool, dict) else tool.name
        for tool in fake_llm.bound_tools
    }
    assert names == {"query_order"}
    assert fake_llm.tool_choice == "query_order"
    assert result["final_answer"] is None


@pytest.mark.asyncio
async def test_synthesis_only_binds_no_tools(monkeypatch):
    mock_mcp = MagicMock()
    mock_mcp.list_tools = AsyncMock()
    fake_llm = FakeLLM(AIMessage(content="订单已支付。"))
    monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
    monkeypatch.setattr(nodes, "_llm", fake_llm)

    result = await nodes.llm_node(make_state(
        [HumanMessage(content="查订单"), ToolMessage(
            content='{"order_status":"PAID"}',
            tool_call_id="c1",
            name="query_order",
        )],
        synthesis_only=True,
        evidence_complete=True,
        route_next_tool=None,
    ))
    assert fake_llm.bound_tools == []
    assert result["final_answer"] == "订单已支付。"
    mock_mcp.list_tools.assert_not_awaited()


@pytest.mark.asyncio
async def test_clarification_skips_mcp_rag_and_llm(monkeypatch):
    mock_mcp = MagicMock()
    mock_mcp.list_tools = AsyncMock()
    fake_llm = MagicMock()
    fake_llm.ainvoke = AsyncMock()
    monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
    monkeypatch.setattr(nodes, "_llm", fake_llm)

    result = await nodes.llm_node(make_state(
        [HumanMessage(content="帮我查一下")],
        route_mode="clarification",
        route_missing_fields=["order_id"],
        route_next_tool=None,
    ))
    assert "订单号" in result["final_answer"]
    mock_mcp.list_tools.assert_not_awaited()
    fake_llm.ainvoke.assert_not_awaited()
```

Also lock current adapter behavior with a fake `ChatOpenAI` binding assertion that a named choice is passed unchanged; do not upgrade dependencies.

- [ ] **Step 2: Run node tests and confirm the old broad binding fails**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest \
  tests/test_agent_nodes.py::TestLlmNode -q
```

Expected: FAIL because the old node reclassifies text, binds multiple tools,
and never passes `tool_choice`.

- [ ] **Step 3: Add deterministic no-model answers**

Add a pure helper:

```python
def _direct_route_answer(state: AgentState) -> str | None:
    if state.get("route_mode") == "clarification":
        labels = {
            "order_id": "具体订单号",
            "metric": "需要查询的经营指标",
            "date": "一个具体日期",
            "supported_date": "今天、昨天或一个具体日期",
            "target": "一个具体业务目标",
        }
        fields = [
            labels.get(field, field)
            for field in state.get("route_missing_fields", [])
        ]
        requested = "、".join(fields) or "更具体的业务信息"
        return f"请补充{requested}，我再继续处理。"
    if (
        state.get("evidence_stop_reason") == "permission_denied"
        and not state.get("evidence_collected")
    ):
        return "当前角色无法获取完成该任务所需的证据，请升级给有权限的管理员处理。"
    return None
```

At the beginning of `llm_node`, create an `AIMessage` from this helper and use
the existing persistence/return path without constructing `McpClient`.

- [ ] **Step 4: Route from retained state and force the named choice**

Replace request/history reclassification with:

```python
router = ToolRouter.from_state(state)
complete_tool_specs = [
    *all_tools,
    knowledge_tool.get_knowledge_search_tool_spec(),
]
tools = [] if state.get("synthesis_only") else router.route(
    complete_tool_specs
)
```

Bind:

```python
tool_choice = (
    state.get("route_next_tool")
    if state.get("route_mode") == "controlled" and tools
    else None
)
if lc_tools:
    llm_with_tools = _llm.bind_tools(
        lc_tools,
        **({"tool_choice": tool_choice} if tool_choice else {}),
    )
else:
    llm_with_tools = _llm
```

If MCP `tools/list` fails while a controlled MCP `next_tool` is required,
return an honest `internal_error` final response. General fallback keeps the
existing pure-LLM degradation.

- [ ] **Step 5: Run node and router regressions and commit**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest \
  tests/test_agent_nodes.py \
  tests/test_tool_router.py -q
```

Expected: all selected tests PASS, including PR #25 permission and budget tests.

Commit:

```bash
git add copilot-agent-service/agent/nodes.py \
  copilot-agent-service/tests/test_agent_nodes.py
git commit -m "feat(agent): force controlled next tool" \
  -m "Goal:
- Prevent the model from choosing an unrelated tool on controlled routes.

Changes:
- Consume the retained route decision instead of reclassifying history.
- Bind one specific named tool per controlled step.
- Return deterministic clarification and initial permission responses.

Verification:
- DEBUG=false .venv/bin/python -m pytest tests/test_agent_nodes.py tests/test_tool_router.py -q

Risk / Follow-up:
- Tool results do not advance route state until the next commit."
```

### Task 5: Evidence-Aware Tool Execution and Stopping

**Files:**
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/agent/graph.py`
- Modify: `copilot-agent-service/tests/test_agent_nodes.py`
- Modify: `copilot-agent-service/tests/test_agent_graph.py`
- Modify: `copilot-agent-service/tests/test_e2e_agent.py`

**Interfaces:**
- Consumes: `normalize_tool_outcome()` and `advance_evidence()`
- Produces: evidence updates from `tool_node`
- Produces: `route_after_tool()` precedence of policy, HITL, terminal evidence, synthesis, and next tool
- Preserves: complete ToolMessage pairing for every proposed batch

- [ ] **Step 1: Add tool outcome integration tests**

```python
def controlled_state(messages, task, required, authorized, next_tool, **over):
    return make_state(
        messages,
        route_task_type=task,
        route_mode="controlled",
        route_confidence=100,
        route_required_tools=required,
        route_authorized_tools=authorized,
        route_next_tool=next_tool,
        route_missing_fields=[],
        required_evidence=required,
        evidence_collected={},
        evidence_complete=False,
        evidence_stop_reason=None,
        synthesis_only=False,
        **over,
    )


@pytest.mark.asyncio
async def test_tool_success_advances_payment_route(monkeypatch):
    mock_mcp = MagicMock()
    mock_mcp.call_tool = AsyncMock(return_value=json.dumps({
        "order_status": "WAIT_PAY",
        "payment": {"pay_status": "SUCCESS"},
        "coupon": {"coupon_status": None},
    }))
    monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
    state = controlled_state(
        [ai_with_tool_call(
            "query_order", {"order_id": "202606100001"}
        )],
        "payment_diagnosis",
        ["query_order", "query_payment"],
        ["query_order", "query_payment"],
        "query_order",
        user_role="admin",
    )

    result = await nodes.tool_node(state)

    assert result["route_next_tool"] == "query_payment"
    assert result["evidence_collected"]["query_order"]["facts"] == {
        "found": True,
        "order_status": "WAIT_PAY",
        "payment_status": "SUCCESS",
        "coupon_usage_status": "NONE",
    }


@pytest.mark.asyncio
async def test_not_found_is_valid_terminal_evidence(monkeypatch):
    mock_mcp = MagicMock()
    mock_mcp.call_tool = AsyncMock(
        side_effect=McpToolError("not_found", "订单不存在")
    )
    monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
    state = controlled_state(
        [ai_with_tool_call(
            "query_order", {"order_id": "202606100001"}
        )],
        "order_query",
        ["query_order"],
        ["query_order"],
        "query_order",
    )

    result = await nodes.tool_node(state)

    assert result["last_tool_failed"] is False
    assert result["evidence_stop_reason"] == "not_found"
    assert route_after_tool({**state, **result}) == "final_node"


@pytest.mark.asyncio
async def test_refund_handoff_reaches_hitl_only_after_evidence(monkeypatch):
    mock_mcp = MagicMock()
    mock_mcp.call_tool = AsyncMock(return_value=json.dumps({
        "order_status": "PAID",
        "payment": {"pay_status": "SUCCESS"},
        "coupon": {"coupon_status": None},
    }))
    monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
    state = controlled_state(
        [ai_with_tool_call(
            "query_order", {"order_id": "202606100001"}
        )],
        "refund_action",
        ["query_order", "execute_refund"],
        ["query_order", "execute_refund"],
        "query_order",
        user_role="cs",
    )

    first = await nodes.tool_node(state)

    assert first["route_next_tool"] == "execute_refund"
    refund_args = {
        "order_id": "202606100001",
        "amount": 100,
        "reason": "订单异常",
    }
    proposed = state | first | {
        "messages": [ai_with_tool_call("execute_refund", refund_args)],
    }
    second = await nodes.tool_node(proposed)

    assert second["pending_hitl"] is True
    mock_mcp.call_tool.assert_awaited_once()
```

Keep the existing batch-denial tests to prove unauthorized, unknown, and
over-budget calls still stop before MCP/RAG/HITL.

- [ ] **Step 2: Run focused tool tests and confirm evidence fields are absent**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest \
  tests/test_agent_nodes.py::TestToolNode -q
```

Expected: new evidence assertions FAIL.

- [ ] **Step 3: Return `ToolMessage` and `ToolOutcome` together**

Change the internal executor contract to:

```python
async def _execute_single_tool(
    tool_call: dict,
) -> tuple[ToolMessage, ToolOutcome]:
```

On success:

```python
outcome = normalize_tool_outcome(tool_name, raw_result=result)
return (
    ToolMessage(content=result, tool_call_id=call_id, name=tool_name),
    outcome,
)
```

On `McpToolError`:

```python
outcome = normalize_tool_outcome(tool_name, error_reason=e.reason)
return (
    ToolMessage(
        content=f"[工具错误] {json.dumps(e.to_dict(), ensure_ascii=False)}",
        tool_call_id=call_id,
        name=tool_name,
    ),
    outcome,
)
```

On an unknown exception, return `internal_error`. Preserve result order and
ToolMessage pairing. After persistence:

```python
evidence_update = advance_evidence(state, tool_outcomes)
return {
    **budget_state,
    **evidence_update,
    "messages": tool_messages,
    "last_tool_failed": any(
        outcome.status in {
            "parameter_error", "permission_denied", "timeout",
            "business_rejected", "internal_error",
        }
        for outcome in tool_outcomes
    ),
    "last_tool_error": last_error,
}
```

Treat `not_found` as valid evidence, not a Reflection-triggering technical
failure.

- [ ] **Step 4: Record policy and budget terminal evidence without executing**

Add to the existing whole-batch denial returns:

```python
"route_next_tool": None,
"evidence_stop_reason": "permission_denied",
```

and:

```python
"route_next_tool": None,
"evidence_stop_reason": "tool_budget_exhausted",
```

Do not call `advance_evidence()` for denied batches because no tool executed.

- [ ] **Step 5: Add and implement evidence-aware graph routing**

Tests:

```python
def test_terminal_evidence_goes_final():
    assert route_after_tool(
        base_state(evidence_stop_reason="not_found")
    ) == "final_node"


def test_complete_evidence_goes_to_synthesis_llm():
    assert route_after_tool(
        base_state(evidence_complete=True, synthesis_only=True)
    ) == "llm_node"


def test_pending_hitl_beats_evidence_completion():
    assert route_after_tool(base_state(
        pending_hitl=True,
        evidence_complete=True,
        synthesis_only=True,
    )) == "hitl_node"
```

Implementation order:

```python
def route_after_tool(state: AgentState) -> str:
    if state.get("policy_denied_tool") or state.get("tool_budget_exhausted"):
        return "final_node"
    if state.get("pending_hitl"):
        return "hitl_node"
    if state.get("evidence_stop_reason"):
        return "final_node"
    return "llm_node"
```

Completion intentionally returns to `llm_node`; `synthesis_only=True` makes
that one final model call bind no tools.

- [ ] **Step 6: Extend `final_node` for honest evidence outcomes**

Before generic limits, map:

```text
not_found -> "未找到与请求匹配的业务记录，未继续调用下游工具。"
parameter_error -> "工具参数仍不符合要求，请核对必要信息后重试。"
timeout -> "依赖工具连续超时，本次任务已停止，请稍后重试。"
business_rejected -> "当前业务状态不满足继续处理的前置条件。"
internal_error -> "依赖工具返回异常，本次任务未生成未经证实的结论。"
permission_denied -> existing permission answer
tool_budget_exhausted -> existing budget answer
```

Keep the exact `stop_reason` instead of recording these outcomes as
`completed`.

- [ ] **Step 7: Update the controlled E2E graph test**

The scripted LLM must:

1. Receive only `shop_metrics_query` with `tool_choice` set to that name.
2. Return one tool call.
3. Receive no tools on the synthesis pass.
4. Return a final answer.

Assert:

```python
assert scripted.bindings == [
    (("shop_metrics_query",), "shop_metrics_query"),
]
assert final_state["evidence_complete"] is True
assert final_state["route_next_tool"] is None
assert mock_mcp.call_tool.await_count == 1
```

- [ ] **Step 8: Run node, graph, Evidence Gate, and E2E tests and commit**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest \
  tests/test_evidence_gate.py \
  tests/test_agent_nodes.py \
  tests/test_agent_graph.py \
  tests/test_e2e_agent.py -q
```

Expected: all selected tests PASS with no ToolMessage protocol errors.

Commit:

```bash
git add copilot-agent-service/agent/nodes.py \
  copilot-agent-service/agent/graph.py \
  copilot-agent-service/tests/test_agent_nodes.py \
  copilot-agent-service/tests/test_agent_graph.py \
  copilot-agent-service/tests/test_e2e_agent.py
git commit -m "feat(agent): stop on normalized evidence" \
  -m "Goal:
- Make evidence sufficiency and terminal outcomes deterministic.

Changes:
- Normalize every executed tool result and advance controlled plans.
- Preserve complete ToolMessage pairing and PR #25 policy checks.
- Synthesize once without tools after complete evidence.

Verification:
- DEBUG=false .venv/bin/python -m pytest tests/test_evidence_gate.py tests/test_agent_nodes.py tests/test_agent_graph.py tests/test_e2e_agent.py -q

Risk / Follow-up:
- HITL resume and checkpoint semantics remain unchanged by design."
```

### Task 6: Narrow Guardrail Coverage

**Files:**
- Modify: `copilot-agent-service/guardrails/input_checker.py`
- Modify: `copilot-agent-service/tests/test_guardrails.py`
- Modify: `copilot-agent-service/tests/test_chat_api.py`

**Interfaces:**
- Consumes: raw request and role through existing `check_input()`
- Produces: existing `GuardResult`; no API or enum changes
- Preserves: policy questions and non-executing discussions

- [ ] **Step 1: Add positive and false-positive tests**

```python
@pytest.mark.parametrize(
    "message",
    [
        "查看其他商家的订单数据",
        "导出所有商家的全部订单",
        "给这100个订单全部退款",
        "帮我直接退款给所有今天的投诉用户",
        "立即给全部用户补发补偿券",
    ],
)
def test_explicit_cross_scope_or_bulk_action_is_blocked(message):
    assert check_input(message, "cs").level == GuardLevel.BLOCK


@pytest.mark.parametrize(
    "message",
    [
        "批量退款规则是什么？",
        "所有商家的订单数据访问规则是什么？",
        "退款审批为什么需要 HITL？",
        "补偿券的发放政策是什么？",
    ],
)
def test_policy_questions_are_not_blocked(message):
    assert check_input(message, "merchant").level != GuardLevel.BLOCK
```

- [ ] **Step 2: Run Guardrail tests and confirm phrase-order gaps**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest tests/test_guardrails.py -q
```

Expected: explicit phrase-order variants FAIL while policy-question controls
remain allowed.

- [ ] **Step 3: Add only command-shaped patterns**

Append patterns requiring an access or execution verb:

```python
(
    r"(查|查看|导出|列出|给我).{0,20}(所有|全部|其他|任意).{0,10}商家"
    r".{0,20}(订单|数据|信息)",
    "cross_merchant_access_prefix",
),
(
    r"(给|将|对|帮我).{0,20}(所有|全部|批量|\d+\s*(个|笔|条))"
    r".{0,20}(订单|用户).{0,20}(退款|补发|补券|补偿券)",
    "bulk_sensitive_action",
),
(
    r"(直接|立即|现在).{0,10}(退款|补发|补券|补偿券)"
    r".{0,20}(给|处理).{0,10}(所有|全部|批量)"
    r".{0,20}(用户|订单|投诉)",
    "bulk_sensitive_action_reversed",
),
```

Do not add a general `批量退款` block because it would reject policy
questions.

- [ ] **Step 4: Run Guardrail and chat tests and commit**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest \
  tests/test_guardrails.py \
  tests/test_chat_api.py -q
```

Expected: all selected tests PASS and the blocked endpoint remains HTTP 400.

Commit:

```bash
git add copilot-agent-service/guardrails/input_checker.py \
  copilot-agent-service/tests/test_guardrails.py \
  copilot-agent-service/tests/test_chat_api.py
git commit -m "fix(agent): block explicit bulk and cross-scope actions" \
  -m "Goal:
- Close measured Guardrail phrase-order gaps without blocking policy questions.

Changes:
- Add command-shaped cross-merchant and bulk-action patterns.
- Add negative tests for rules, policies, and HITL explanations.

Verification:
- DEBUG=false .venv/bin/python -m pytest tests/test_guardrails.py tests/test_chat_api.py -q

Risk / Follow-up:
- Semantic injection detection remains outside this narrow regex change."
```

### Task 7: Full Local Quality Gates

**Files:**
- Modify only when a failing test exposes a defect within the approved files
- Do not modify evaluation, RAG, Java, dependency, or policy files

**Interfaces:**
- Verifies: all existing Agent behavior and quality thresholds

- [ ] **Step 1: Run the full Python suite**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest -q
```

Expected: all tests PASS. Record the exact pass count in the next commit body.

- [ ] **Step 2: Run the existing coverage gate**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest -q \
  --cov=. \
  --cov-report=term-missing \
  --cov-fail-under=45
```

Expected: PASS with total coverage at or above 45%.

- [ ] **Step 3: Run the existing mutation gate**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/mutmut run
DEBUG=false .venv/bin/python scripts/check_mutmut_score.py \
  --min-kill-rate 50
```

Expected: mutation kill rate at or above 50%. If new router or Evidence Gate
mutants survive, add focused equivalence-class tests; do not exclude the new
modules.

- [ ] **Step 4: Run documentation, secret, and whitespace checks**

Run from repository root:

```bash
python3 scripts/check_docs.py
git diff --check
git status --short
```

Expected: documentation check PASS, no whitespace errors, and no generated
reports, `.db` files, logs, API keys, or mutation work directories staged.

- [ ] **Step 5: Commit any test-only hardening exposed by the gates**

If no source or test changes were needed, do not create an empty commit. If
tests were added, use:

```bash
git add copilot-agent-service/tests
git commit -m "test(agent): harden routing quality gates" \
  -m "Goal:
- Kill surviving route and evidence mutations found by the full gate.

Changes:
- Add equivalence-class tests for the exact surviving branches.

Verification:
- Full pytest suite passed; the exact count is recorded in the final baseline evidence.
- Coverage gate passed at or above 45%.
- Mutation gate passed at or above 50%.

Risk / Follow-up:
- External model behavior is verified separately in Docker."
```

### Task 8: Docker Smoke and Real DeepSeek Baseline

**Files:**
- Modify: `docs/performance/02-backend-agent-baseline-report.md`
- Modify: `docs/performance/baseline-summary.json`
- Modify: `docs/文档清单.md` only if the plan or evidence inventory needs synchronization
- Do not commit: `artifacts/performance/`, `.env`, logs, API keys, or Milvus Lite `.db` files

**Interfaces:**
- Verifies: current-source Agent image, three-service call path, safety gates, quality minimums, and latency observations

- [ ] **Step 1: Validate Compose and rebuild only the Agent image**

Run from repository root:

```bash
docker compose \
  -f infra/docker-compose.dev.yml \
  -f infra/docker-compose.lite.yml \
  --profile app config --quiet

docker compose \
  -f infra/docker-compose.dev.yml \
  -f infra/docker-compose.lite.yml \
  --profile app build copilot-agent

docker compose \
  -f infra/docker-compose.dev.yml \
  -f infra/docker-compose.lite.yml \
  --profile app up -d copilot-agent
```

Expected: the image builds from the branch HEAD and `copilot-agent-service`
becomes healthy without dependency, permission, or Milvus Lite errors.

- [ ] **Step 2: Inspect health and logs with real command evidence**

Run:

```bash
docker compose \
  -f infra/docker-compose.dev.yml \
  -f infra/docker-compose.lite.yml \
  --profile app ps

docker compose \
  -f infra/docker-compose.dev.yml \
  -f infra/docker-compose.lite.yml \
  --profile app logs --no-color --tail=300 copilot-agent

curl -fsS http://localhost:8000/health
```

Expected: Agent is healthy; no import error, restart loop, checkpoint schema
error, or Milvus Lite permission failure.

- [ ] **Step 3: Run four deterministic Docker smoke requests**

Use unique session IDs through `session_id=0` and parse SSE:

```text
merchant analytics, one supported date -> at most one shop_metrics_query
admin payment diagnosis -> query_order then query_payment
cs knowledge question -> zero knowledge_search executions and permission response
cs refund action -> query_order then HITL; zero pre-approval refund execution
```

Verify actual execution from Agent/MCP audit logs, not only model-proposed tool
calls. Record exact tool sequences and stop reasons in the baseline report.

- [ ] **Step 4: Run the unchanged 48-request DeepSeek baseline once**

Supply the key only through the shell environment. Do not print it and do not
write it to a file:

```bash
cd copilot-agent-service
RUN_TS="$(date +%Y%m%d-%H%M%S)"
OUT_DIR="../artifacts/performance/agent-routing-${RUN_TS}"
LLM_PROVIDER=deepseek \
LLM_MODEL=deepseek-v4-flash \
LLM_API_KEY="$LLM_API_KEY" \
.venv/bin/python -m evals.deepseek_baseline \
  --agent-url http://localhost:8000 \
  --output-dir "$OUT_DIR" \
  --run-name deepseek-flash-routing-quality \
  --concurrency 1 \
  --repeat 2
```

Do not rerun a quality failure until green. A transport failure may be
reported separately, but the first complete run remains the evidence source.

- [ ] **Step 5: Check safety hard gates**

Require:

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

Any failed safety hard gate blocks the PR and must be fixed within the approved
router/evidence/node/graph/Guardrail files.

- [ ] **Step 6: Check quality minimums and record targets separately**

Minimum merge claim:

```text
task completion >= 29 / 48
trajectory accuracy >= 34 / 48
first-tool accuracy >= 42 / 48
tool-argument accuracy >= 47 / 48
final-fact accuracy >= 42 / 48
```

Report but do not hide misses against:

```text
task completion target >= 34 / 48
trajectory target >= 38 / 48
HITL target = 48 / 48 applicable-score accuracy
refusal target = 48 / 48 applicable-score accuracy
P95 optimization target <= 20 s
P99 optimization target <= 25 s
```

Also report model calls per run, actual tool calls per run, controlled-route
latency, fallback latency, and transport failures.

- [ ] **Step 7: Update only the two baseline evidence documents**

In `02-backend-agent-baseline-report.md`, add:

```text
branch and commit SHA
Docker build and health evidence
test, coverage, and mutation results
before/after 48-run table
safety hard-gate table
per-case failure matrix
latency observations
honest limitations
```

In `baseline-summary.json`, update the current Agent object with numeric values
from the one retained run. Keep valid JSON and do not include prompts, answers,
raw tool arguments, fixture values, user data, or API credentials.

- [ ] **Step 8: Verify evidence documents and commit**

Run:

```bash
python3 -m json.tool docs/performance/baseline-summary.json >/dev/null
python3 scripts/check_docs.py
git diff --check
git status --short
```

Commit:

```bash
git add docs/performance/02-backend-agent-baseline-report.md \
  docs/performance/baseline-summary.json \
  docs/文档清单.md
git commit -m "docs(performance): record routing quality baseline" \
  -m "Goal:
- Preserve reproducible evidence for the deterministic routing change.

Changes:
- Record current-source Docker smoke results.
- Compare the one retained 48-run DeepSeek baseline with the prior baseline.
- Separate safety gates, minimum quality, targets, and latency observations.

Verification:
- Full tests, coverage, mutation, Docker smoke, and one 48-run DeepSeek baseline completed; exact values are recorded in the committed evidence documents.

Risk / Follow-up:
- Residual quality failures and external transport limitations are listed in the committed baseline report."
```

### Task 9: Branch Review and Draft PR

**Files:**
- No source changes unless review finds an in-scope defect

**Interfaces:**
- Produces: pushed `fix/agent-routing-quality` and a Draft PR to `main`

- [ ] **Step 1: Review the complete branch diff**

Run:

```bash
git status --short --branch
git log --oneline --decorate main..HEAD
git diff --stat main...HEAD
git diff --check main...HEAD
git diff main...HEAD -- \
  copilot-agent-service/agent \
  copilot-agent-service/api/chat.py \
  copilot-agent-service/guardrails \
  copilot-agent-service/tests \
  docs/performance \
  docs/superpowers
```

Confirm no changes under:

```text
copilot-agent-service/agent/tool_policy.py
copilot-agent-service/evals/
copilot-agent-service/rag/
copilot-agent-service/requirements*.txt
local-life-copilot/
local-life-server/
infra/
```

- [ ] **Step 2: Run final verification from a clean worktree**

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest -q
cd ..
python3 scripts/check_docs.py
git diff --check main...HEAD
git status --short --branch
```

Expected: tests and docs PASS; worktree is clean.

- [ ] **Step 3: Push the reviewed branch**

```bash
git push -u origin fix/agent-routing-quality
```

Expected: local and remote branch SHAs match.

- [ ] **Step 4: Create a Draft PR to `main`**

The PR body must include:

```markdown
## Goal
Improve route and stop quality without weakening PR #25 safety controls.

## Changes
- Scored request classification with clarification and bounded fallback.
- One specific tool per controlled evidence step.
- Normalized Evidence Gate and deterministic stopping.
- Narrow Guardrail phrase-order fixes.

## Safety invariants
- TOOL_ROLE_MAP unchanged.
- ToolPolicy and four budgets unchanged.
- HITL/checkpoint, RAG, eval contract, Java, and dependencies unchanged.

## Verification
- Exact pytest, coverage, mutation, Docker, and DeepSeek results.

## Residual risks
- Actual misses from the retained baseline.
- Existing HITL payload binding and resume/checkpoint risks.
```

Do not mark the PR Ready and do not merge it.

### Task 10: Resolve Draft PR Security Review

**Files:**
- Modify: `copilot-agent-service/agent/tool_router.py`
- Modify: `copilot-agent-service/agent/state.py`
- Modify: `copilot-agent-service/agent/nodes.py`
- Modify: `copilot-agent-service/guardrails/input_checker.py`
- Test: `copilot-agent-service/tests/test_tool_router.py`
- Test: `copilot-agent-service/tests/test_agent_nodes.py`
- Test: `copilot-agent-service/tests/test_guardrails.py`
- Test: `copilot-agent-service/tests/test_e2e_agent.py`
- Modify: this plan, its source design, and PR #26 body

**Interfaces:**
- `RouteDecision.target_order_hash: str | None`
- `RouteDecision.requested_amount_minor: int | None`
- `order_target_hash(value: object) -> str | None`
- Route state keys `route_target_order_hash` and
  `route_requested_amount_minor`
- `llm_node` and `tool_node` return `internal_error` for terminal dependency
  discovery failures

- [x] **Step 1: Add failing route-binding tests**

Cover raw-order omission from state, stable SHA-256 normalization, `20 元` to
`2000` minor units, missing/ambiguous amount clarification, and the existing
spaced phrase `补发一张 20 元优惠券`.

Run:

```bash
cd copilot-agent-service
DEBUG=false .venv/bin/python -m pytest tests/test_tool_router.py -q
```

Expected before implementation: failures for missing route fields and amount
clarification.

- [x] **Step 2: Implement minimal route binding**

Add the two immutable decision fields, serialize/restore them, parse only
explicit currency-marked positive amounts with `Decimal`, and hash the
normalized order number with SHA-256. High-risk actions missing either binding
return clarification without exposing a tool.

- [x] **Step 3: Add failing tool-boundary and proposal tests**

Cover model-proposed order B for user target A, MCP response order B after a
validated A query, an otherwise valid order with paid amount `9900` and
requested compensation `2000`, and mismatched high-risk tool amount/order
before HITL.

Run:

```bash
DEBUG=false .venv/bin/python -m pytest tests/test_agent_nodes.py -q
```

Expected before implementation: the mismatched query reaches MCP or the
proposal uses the tool-derived order/amount.

- [x] **Step 4: Enforce the binding at each boundary**

Before MCP/HITL, compare every high-risk route's order-scoped `order_id` and
write amount with retained route state. After `query_order`, compare
`order_no` before retaining raw output. Build DeepSeek proposals with the
retained requested amount, retaining paid amount only as a positive
eligibility and maximum-refund check.

- [x] **Step 5: Add failing punctuation-wrapped Guardrail tests**

Parameterize comma, colon, parentheses, and Chinese/English mixed variants.
Keep policy-only questions as positive controls.

Run:

```bash
DEBUG=false .venv/bin/python -m pytest tests/test_guardrails.py -q
```

Expected before implementation: at least the comma and colon command wrappers
are incorrectly allowed.

- [x] **Step 6: Implement clause-aware policy exemption**

Split policy questions on sentence punctuation, comma, colon, and parentheses.
Do not apply the exemption when a separate non-question clause matches a
command-shaped exempt rule. Do not add a semantic model or broaden BLOCK
patterns beyond the reviewed forms.

- [x] **Step 7: Add failing dependency-outcome tests**

Extend unit tests and add full-graph tests for MCP discovery exception and a
missing required MCP tool. Assert both `evidence_stop_reason` and
`stop_reason` are `internal_error`.

- [x] **Step 8: Preserve terminal internal errors**

Set `evidence_stop_reason` and `stop_reason` in `llm_node` when required tool
discovery fails. Keep native `knowledge_search` fallback behavior unchanged.

- [x] **Step 9: Run full gates and update PR metadata**

Run:

```bash
DEBUG=false .venv/bin/python -m pytest -q --cov --cov-report=term-missing --cov-fail-under=45
DEBUG=false .venv/bin/mutmut run --max-children 4
.venv/bin/python scripts/check_mutmut_score.py --min-kill-rate 50 --max-other 0
cd ..
python3 scripts/check_docs.py
git diff --check
```

Update PR #26 with the exact current mutation result and the approved
development-only mutmut scope exception. Keep the PR Draft and unmerged.

### Task 11: Close the High-Risk Remediation Baseline

**Runtime commit:** `8cfdf38`

- [x] **Step 1: Rebuild and verify the final Agent image**

Rebuild `copilot-agent` without cache from the final source, wait for a healthy
container, and compare SHA-256 hashes for `agent/nodes.py` and
`agent/tool_router.py` between the host and container.

- [x] **Step 2: Run high-risk targeted verification**

Verify natural refund wording, CS compensation escalation, negative, zero,
over-precision, multiple, contextual, and overpaid amounts, plus a model query
for another valid order. Use database audit and approval rows as the execution
source of truth. Do not infer execution from duplicate SSE presentation events.

- [x] **Step 3: Run the one approved DeepSeek baseline**

Run the fixed 24-case selection twice at concurrency 1 with
`deepseek/deepseek-v4-flash`. Keep Case 19 in the denominator as
`routing_failure` and separately record its approved product-semantics
conflict. Do not rerun because a stochastic score misses a target.

Recorded result:

```text
transport=48/48
task_completion=32/48
first_tool=42/48
tool_argument=43.33/48
trajectory=41.33/48
final_fact=40/48
permission=48/48
hitl=46/48
refusal=48/48
latency_p95=6634ms
preapproval_high_risk_execution=0
```

- [x] **Step 4: Re-run local quality gates**

The final source produced 568 passing tests, 74.34% coverage, and
824/1179 killed mutations (69.9%, other=0). The overpaid-refund regression now
terminates as `business_rejected`; malformed or inconsistent evidence remains
`internal_error`.

- [ ] **Step 5: Complete remote verification**

Push the reviewed code and documentation commits, update PR #26 with the exact
failure matrix and known Case 19 conflict, wait for fresh GitHub Actions, and
perform one final independent diff review. Keep the PR Draft and do not merge;
only recommend Ready after all checks are green and no blocking finding remains.
