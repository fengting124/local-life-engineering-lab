"""Tests for bounded tool-output normalization and deterministic evidence flow."""
import json

import pytest

from agent.evidence_gate import (
    ToolOutcome,
    advance_evidence,
    initial_evidence_state,
    normalize_tool_outcome,
)
from agent.tool_router import RouteDecision


def _state(task, required, authorized, next_tool, records=None, **overrides):
    state = {
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
    state.update(overrides)
    return state


def test_query_order_normalizes_only_control_facts():
    outcome = normalize_tool_outcome(
        "query_order",
        json.dumps(
            {
                "order_no": "SECRET-1",
                "user_id": "9001",
                "order_amount": 9900,
                "order_status": "PAID",
                "payment": {"pay_status": "SUCCESS", "trade_no": "SECRET"},
                "coupon": {"coupon_status": "UNUSED"},
            }
        ),
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


def test_query_payment_reads_status_from_payments_entries_only():
    outcome = normalize_tool_outcome(
        "query_payment",
        '{"pay_status":"FAILED","payments":[{"pay_status":"SUCCESS",'
        '"trade_no":"SECRET"}]}',
    )

    assert outcome.status == "success"
    assert outcome.facts == {"found": True, "payment_status": "SUCCESS"}
    assert "SECRET" not in repr(outcome)


def test_query_coupon_log_uses_nested_coupon_and_structured_outbox_status():
    outcome = normalize_tool_outcome(
        "query_coupon_issue_log",
        '{"coupon":{"coupon_status":"USED"},'
        '"outbox_messages":[{"status":"PENDING","payload":"SECRET"}]}',
    )

    assert outcome.status == "success"
    assert outcome.facts == {
        "found": True,
        "coupon_usage_status": "USED",
        "coupon_issue_status": "PENDING",
        "coupon_failure_confirmed": "UNKNOWN",
    }
    assert "SECRET" not in repr(outcome)


def test_structured_failed_outbox_confirms_coupon_failure():
    outcome = normalize_tool_outcome(
        "query_coupon_issue_log",
        '{"outbox_messages":[{"status":"FAILED"}],'
        '"coupon":{"coupon_status":"UNUSED"}}',
    )

    assert outcome.facts["coupon_issue_status"] == "FAILED"
    assert outcome.facts["coupon_failure_confirmed"] is True


def test_unused_coupon_does_not_confirm_delivery_failure():
    outcome = normalize_tool_outcome(
        "query_order",
        '{"order_status":"PAID","payment":{},'
        '"coupon":{"coupon_status":"UNUSED"}}',
    )

    assert "coupon_failure_confirmed" not in outcome.facts


@pytest.mark.parametrize(
    ("raw_status", "expected"),
    [("BAD", "UNKNOWN"), (None, "UNKNOWN")],
)
def test_unknown_enum_values_are_bounded(raw_status, expected):
    outcome = normalize_tool_outcome(
        "query_order",
        json.dumps({"order_status": raw_status, "payment": {}, "coupon": {}}),
    )

    assert outcome.facts["order_status"] == expected
    assert outcome.facts["payment_status"] == "UNKNOWN"
    assert outcome.facts["coupon_usage_status"] == "UNKNOWN"


def test_query_mq_dead_letter_normalizes_presence_without_records():
    outcome = normalize_tool_outcome(
        "query_mq_dead_letter",
        '{"count":1,"dead_letters":[{"message_id":"SECRET","payload":"SECRET"}]}',
    )

    assert outcome.status == "success"
    assert outcome.facts == {"found": True, "mq_dead_letter_present": True}
    assert "SECRET" not in repr(outcome)


@pytest.mark.parametrize(
    ("tool_name", "raw_result", "expected_facts"),
    [
        ("knowledge_search", '{"found":false,"sources":["SECRET"]}', {"knowledge_found": False}),
        ("coupon_policy_lookup", '{"policy":"SECRET"}', {"policy_available": True}),
        ("campaign_draft_generate", "draft created", {"campaign_draft_generated": True}),
    ],
)
def test_non_sensitive_control_facts_are_normalized(tool_name, raw_result, expected_facts):
    outcome = normalize_tool_outcome(tool_name, raw_result)

    expected_status = "not_found" if tool_name == "knowledge_search" else "success"
    assert outcome.status == expected_status
    assert outcome.facts == expected_facts
    assert "SECRET" not in repr(outcome)


@pytest.mark.parametrize(
    "tool_name",
    [
        "query_order",
        "query_payment",
        "query_coupon_issue_log",
        "query_mq_dead_letter",
        "knowledge_search",
        "coupon_policy_lookup",
    ],
)
def test_malformed_controlled_read_result_fails_closed(tool_name):
    outcome = normalize_tool_outcome(tool_name, "not-json")

    assert outcome.status == "internal_error"
    assert outcome.facts == {}


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
    assert normalize_tool_outcome("query_order", error_reason=reason).status == status


def test_initial_evidence_state_copies_required_tools_and_blocks_unauthorized_first_step():
    decision = RouteDecision(
        task_type="payment_diagnosis",
        route_mode="controlled",
        confidence=100,
        required_tools=("query_order", "query_payment"),
        authorized_tools=("query_payment",),
    )

    assert initial_evidence_state(decision) == {
        "required_evidence": ["query_order", "query_payment"],
        "evidence_collected": {},
        "evidence_complete": False,
        "evidence_stop_reason": "permission_denied",
        "synthesis_only": False,
    }


def test_payment_route_advances_one_tool_at_a_time():
    update = advance_evidence(
        _state(
            "payment_diagnosis",
            ["query_order", "query_payment"],
            ["query_order", "query_payment"],
            "query_order",
        ),
        [
            ToolOutcome(
                "query_order",
                "success",
                {
                    "found": True,
                    "order_status": "WAIT_PAY",
                    "payment_status": "SUCCESS",
                },
            )
        ],
    )

    assert update["route_next_tool"] == "query_payment"
    assert update["evidence_complete"] is False
    assert update["evidence_collected"] == {
        "query_order": {
            "status": "success",
            "attempts": 1,
            "facts": {
                "found": True,
                "order_status": "WAIT_PAY",
                "payment_status": "SUCCESS",
            },
        }
    }


def test_root_not_found_stops_without_downstream_tool():
    update = advance_evidence(
        _state(
            "payment_diagnosis",
            ["query_order", "query_payment"],
            ["query_order", "query_payment"],
            "query_order",
        ),
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
        [ToolOutcome("query_order", "success", {"found": True, "order_status": "PAID"})],
    )
    ineligible = advance_evidence(
        _state(
            "refund_action",
            ["query_order", "execute_refund"],
            ["query_order", "execute_refund"],
            "query_order",
        ),
        [ToolOutcome("query_order", "success", {"found": True, "order_status": "WAIT_PAY"})],
    )

    assert eligible["route_next_tool"] == "execute_refund"
    assert ineligible["route_next_tool"] is None
    assert ineligible["evidence_stop_reason"] == "business_rejected"


def test_compensation_never_unlocks_from_unused_alone():
    update = advance_evidence(
        _state(
            "compensation_action",
            ["query_order", "query_coupon_issue_log", "issue_compensation_coupon"],
            ["query_order", "issue_compensation_coupon"],
            "query_order",
        ),
        [
            ToolOutcome(
                "query_order",
                "success",
                {
                    "found": True,
                    "order_status": "PAID",
                    "coupon_usage_status": "UNUSED",
                },
            )
        ],
    )

    assert update["route_next_tool"] is None
    assert update["evidence_stop_reason"] == "permission_denied"


def test_compensation_unlocks_only_after_confirmed_coupon_failure():
    records = {
        "query_order": {
            "status": "success",
            "attempts": 1,
            "facts": {"found": True, "order_status": "PAID"},
        }
    }
    update = advance_evidence(
        _state(
            "compensation_action",
            ["query_order", "query_coupon_issue_log", "issue_compensation_coupon"],
            ["query_order", "query_coupon_issue_log", "issue_compensation_coupon"],
            "query_coupon_issue_log",
            records,
        ),
        [
            ToolOutcome(
                "query_coupon_issue_log",
                "success",
                {"found": True, "coupon_issue_status": "FAILED", "coupon_failure_confirmed": True},
            )
        ],
    )

    assert update["route_next_tool"] == "issue_compensation_coupon"


def test_coupon_root_cause_queries_mq_only_for_failed_coupon_log():
    records = {
        "query_order": {
            "status": "success",
            "attempts": 1,
            "facts": {"found": True, "order_status": "PAID"},
        }
    }
    failed = advance_evidence(
        _state(
            "coupon_root_cause",
            ["query_order", "query_coupon_issue_log", "query_mq_dead_letter"],
            ["query_order", "query_coupon_issue_log", "query_mq_dead_letter"],
            "query_coupon_issue_log",
            records,
        ),
        [ToolOutcome("query_coupon_issue_log", "success", {"coupon_issue_status": "FAILED"})],
    )
    healthy = advance_evidence(
        _state(
            "coupon_root_cause",
            ["query_order", "query_coupon_issue_log", "query_mq_dead_letter"],
            ["query_order", "query_coupon_issue_log", "query_mq_dead_letter"],
            "query_coupon_issue_log",
            records,
        ),
        [ToolOutcome("query_coupon_issue_log", "success", {"coupon_issue_status": "SENT"})],
    )

    assert failed["route_next_tool"] == "query_mq_dead_letter"
    assert healthy["route_next_tool"] is None
    assert healthy["evidence_complete"] is True
    assert healthy["synthesis_only"] is True


def test_campaign_chain_is_conditional_on_required_policy_lookup():
    state = _state(
        "campaign_draft",
        ["coupon_policy_lookup", "campaign_draft_generate"],
        ["coupon_policy_lookup", "campaign_draft_generate"],
        "coupon_policy_lookup",
    )

    update = advance_evidence(
        state,
        [ToolOutcome("coupon_policy_lookup", "success", {"policy_available": True})],
    )

    assert update["route_next_tool"] == "campaign_draft_generate"


@pytest.mark.parametrize("status", ["parameter_error", "timeout"])
def test_parameter_and_timeout_each_retry_once(status):
    first = advance_evidence(
        _state("order_query", ["query_order"], ["query_order"], "query_order"),
        [ToolOutcome("query_order", status, {})],
    )
    second = advance_evidence(
        _state(
            "order_query",
            ["query_order"],
            ["query_order"],
            "query_order",
            first["evidence_collected"],
        ),
        [ToolOutcome("query_order", status, {})],
    )

    assert first["route_next_tool"] == "query_order"
    assert first["evidence_collected"]["query_order"]["attempts"] == 1
    assert second["route_next_tool"] is None
    assert second["evidence_stop_reason"] == status
    assert second["evidence_collected"]["query_order"]["attempts"] == 2


@pytest.mark.parametrize(
    "status",
    ["permission_denied", "business_rejected", "internal_error", "pending_hitl"],
)
def test_terminal_outcomes_do_not_expose_downstream_tools(status):
    update = advance_evidence(
        _state("order_query", ["query_order"], ["query_order"], "query_order"),
        [ToolOutcome("query_order", status, {})],
    )

    assert update["route_next_tool"] is None
    assert update["evidence_stop_reason"] == status


def test_general_fallback_does_not_apply_deterministic_progression():
    state = _state(
        "unknown",
        ["query_order"],
        ["query_order"],
        "query_order",
        route_mode="general_fallback",
    )

    assert advance_evidence(state, [ToolOutcome("query_order", "success", {"found": True})]) == state


def test_existing_budget_terminal_state_is_preserved():
    state = _state(
        "order_query",
        ["query_order"],
        ["query_order"],
        None,
        evidence_stop_reason="budget_exhausted",
    )

    assert advance_evidence(state, [ToolOutcome("query_order", "success", {"found": True})]) == state


def test_last_successful_evidence_step_completes_for_synthesis_only():
    update = advance_evidence(
        _state("order_query", ["query_order"], ["query_order"], "query_order"),
        [ToolOutcome("query_order", "success", {"found": True, "order_status": "PAID"})],
    )

    assert update["route_next_tool"] is None
    assert update["evidence_complete"] is True
    assert update["evidence_stop_reason"] is None
    assert update["synthesis_only"] is True
