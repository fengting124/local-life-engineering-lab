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


def test_query_payment_note_only_entry_is_not_found():
    outcome = normalize_tool_outcome(
        "query_payment",
        '{"payments":[{"note":"该订单从未发起过支付"}]}',
    )

    assert outcome.status == "not_found"
    assert outcome.facts == {"found": False, "payment_status": "UNKNOWN"}


def test_query_payment_preserves_valid_status_after_note_entry():
    outcome = normalize_tool_outcome(
        "query_payment",
        '{"payments":[{"note":"no payment"},{"pay_status":"SUCCESS"}]}',
    )

    assert outcome.status == "success"
    assert outcome.facts == {"found": True, "payment_status": "SUCCESS"}


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


@pytest.mark.parametrize("raw_status", [None, "NOT_USED"])
def test_query_order_normalizes_explicit_no_coupon_status_to_none(raw_status):
    outcome = normalize_tool_outcome(
        "query_order",
        json.dumps(
            {
                "order_status": "PAID",
                "payment": {},
                "coupon": {"coupon_status": raw_status},
            }
        ),
    )

    assert outcome.facts["coupon_usage_status"] == "NONE"
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
        (
            "coupon_policy_lookup",
            '{"coupon_template_id":"SECRET"}',
            {"policy_available": True},
        ),
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
    ("raw_result", "status", "available"),
    [
        ('{"count":0,"coupons":[]}', "not_found", False),
        (
            '{"count":1,"coupons":[{"coupon_template_id":"SECRET"}]}',
            "success",
            True,
        ),
        ('{"coupon_template_id":"SECRET"}', "success", True),
    ],
)
def test_coupon_policy_lookup_normalizes_real_java_shapes(
    raw_result, status, available
):
    outcome = normalize_tool_outcome("coupon_policy_lookup", raw_result)

    assert outcome.status == status
    assert outcome.facts == {"policy_available": available}
    assert "SECRET" not in repr(outcome)


@pytest.mark.parametrize(
    "raw_result",
    [
        '{"count":1}',
        '{"coupons":[]}',
        '{"count":1,"coupons":[]}',
        '{"count":0,"coupons":[{"coupon_template_id":"SECRET"}]}',
        '{"count":"1","coupons":[{"coupon_template_id":"SECRET"}]}',
        '{"count":1,"coupons":[{"coupon_name":"missing identity"}]}',
        '{"coupon_name":"missing identity"}',
    ],
)
def test_malformed_coupon_policy_wrappers_fail_closed(raw_result):
    outcome = normalize_tool_outcome("coupon_policy_lookup", raw_result)

    assert outcome.status == "internal_error"
    assert outcome.facts == {}


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


def test_inconsistent_coupon_failure_pair_is_rebound_and_cannot_unlock_compensation():
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
                {
                    "found": True,
                    "coupon_issue_status": "SENT",
                    "coupon_failure_confirmed": True,
                },
            )
        ],
    )

    assert update["route_next_tool"] is None
    assert update["evidence_stop_reason"] == "business_rejected"
    assert update["evidence_collected"]["query_coupon_issue_log"]["facts"][
        "coupon_failure_confirmed"
    ] is False


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


def test_false_policy_success_is_rebound_to_not_found_and_stops_campaign():
    update = advance_evidence(
        _state(
            "campaign_draft",
            ["coupon_policy_lookup", "campaign_draft_generate"],
            ["coupon_policy_lookup", "campaign_draft_generate"],
            "coupon_policy_lookup",
        ),
        [
            ToolOutcome(
                "coupon_policy_lookup",
                "success",
                {"policy_available": False},
            )
        ],
    )

    assert update["route_next_tool"] is None
    assert update["evidence_stop_reason"] == "not_found"
    assert update["evidence_collected"]["coupon_policy_lookup"]["status"] == "not_found"


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
    ("required", "authorized", "next_tool", "outcome_tool", "stop_reason"),
    [
        (
            ["query_order", "query_payment"],
            ["query_order", "query_payment"],
            "query_order",
            "query_payment",
            "internal_error",
        ),
        ([], ["query_payment"], "query_payment", "query_payment", "internal_error"),
        (["query_order"], [], "query_order", "query_order", "permission_denied"),
        (["invented_tool"], ["invented_tool"], "invented_tool", "invented_tool", "internal_error"),
    ],
)
def test_invalid_outcome_is_rejected_before_storage_or_retry(
    required, authorized, next_tool, outcome_tool, stop_reason
):
    update = advance_evidence(
        _state("order_query", required, authorized, next_tool),
        [ToolOutcome(outcome_tool, "timeout", {})],
    )

    assert update["route_next_tool"] is None
    assert update["evidence_stop_reason"] == stop_reason
    assert update["evidence_collected"] == {}


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


def test_clarification_does_not_apply_deterministic_progression():
    state = _state(
        "order_query",
        ["query_order"],
        ["query_order"],
        "query_order",
        route_mode="clarification",
    )

    assert advance_evidence(
        state,
        [ToolOutcome("query_order", "success", {"found": True})],
    ) == state


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


def test_preexisting_records_are_resanitized_before_transition():
    records = {
        "query_order": {
            "status": "success",
            "attempts": 1,
            "facts": {
                "found": True,
                "order_status": "PAID",
                "order_id": "SECRET-ID",
                "amount": 9900,
                "diagnosis": "SECRET-DIAGNOSIS",
            },
            "payload": "SECRET-PAYLOAD",
            "raw_output": "SECRET-RAW",
        },
        "query_coupon_issue_log": {
            "status": "success",
            "attempts": 2,
            "facts": {
                "coupon_issue_status": "SENT",
                "coupon_failure_confirmed": True,
                "sources": ["SECRET-SOURCE"],
            },
            "unknown_record_key": "SECRET-RECORD",
        },
        "query_mq_dead_letter": {
            "status": "success",
            "attempts": -1,
            "facts": {"mq_dead_letter_present": True},
        },
        "query_payment": {
            "status": "forged",
            "attempts": 4,
            "facts": {"payment_status": "SUCCESS"},
        },
        "invented_tool": {
            "status": "success",
            "attempts": 1,
            "facts": {"found": True},
        },
    }

    update = advance_evidence(
        _state(
            "payment_diagnosis",
            ["query_order", "query_payment"],
            ["query_order", "query_payment"],
            "query_payment",
            records,
        ),
        [
            ToolOutcome(
                "query_payment",
                "success",
                {
                    "found": True,
                    "payment_status": "SUCCESS",
                    "trade_no": "SECRET-TRADE",
                },
            )
        ],
    )

    assert update["evidence_collected"] == {
        "query_order": {
            "status": "success",
            "attempts": 1,
            "facts": {"found": True, "order_status": "PAID"},
        },
        "query_coupon_issue_log": {
            "status": "success",
            "attempts": 2,
            "facts": {
                "coupon_issue_status": "SENT",
                "coupon_failure_confirmed": False,
            },
        },
        "query_payment": {
            "status": "success",
            "attempts": 1,
            "facts": {"found": True, "payment_status": "SUCCESS"},
        },
    }
    assert "SECRET" not in repr(update["evidence_collected"])
    assert "9900" not in repr(update["evidence_collected"])


@pytest.mark.parametrize("coupon_usage_status", ["NONE", "UNUSED"])
def test_retained_coupon_usage_status_is_rebounded_without_delivery_failure(
    coupon_usage_status,
):
    records = {
        "query_order": {
            "status": "success",
            "attempts": 1,
            "facts": {
                "found": True,
                "order_status": "PAID",
                "coupon_usage_status": coupon_usage_status,
                "coupon_failure_confirmed": True,
            },
        }
    }

    update = advance_evidence(
        _state(
            "payment_diagnosis",
            ["query_order", "query_payment"],
            ["query_order", "query_payment"],
            "query_payment",
            records,
        ),
        [ToolOutcome("query_payment", "success", {"found": True})],
    )

    retained_facts = update["evidence_collected"]["query_order"]["facts"]
    assert retained_facts["coupon_usage_status"] == coupon_usage_status
    assert "coupon_failure_confirmed" not in retained_facts
