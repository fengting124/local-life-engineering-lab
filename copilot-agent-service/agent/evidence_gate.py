"""Normalize bounded tool evidence and advance controlled routes deterministically."""
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
COUPON_USAGE_STATUSES = {"UNUSED", "USED", "EXPIRED", "NONE"}
COUPON_ISSUE_STATUSES = {"PENDING", "SENT", "FAILED", "NO_RECORD"}

CONTROLLED_READ_TOOLS = {
    "query_order",
    "query_payment",
    "query_coupon_issue_log",
    "query_mq_dead_letter",
    "shop_metrics_query",
    "knowledge_search",
    "coupon_policy_lookup",
}
TEXT_SUCCESS_TOOLS = {
    "execute_refund",
    "issue_compensation_coupon",
    "campaign_draft_generate",
}
FACT_KEYS_BY_TOOL = {
    "query_order": {
        "found",
        "order_status",
        "payment_status",
        "coupon_usage_status",
    },
    "query_payment": {"found", "payment_status"},
    "query_coupon_issue_log": {
        "found",
        "coupon_usage_status",
        "coupon_issue_status",
        "coupon_failure_confirmed",
    },
    "query_mq_dead_letter": {"found", "mq_dead_letter_present"},
    "knowledge_search": {"knowledge_found"},
    "coupon_policy_lookup": {"policy_available"},
    "campaign_draft_generate": {"campaign_draft_generated"},
    "shop_metrics_query": set(),
    "execute_refund": set(),
    "issue_compensation_coupon": set(),
}
KNOWN_EVIDENCE_TOOLS = set(FACT_KEYS_BY_TOOL)
ENUM_FACTS = {
    "order_status": ORDER_STATUSES,
    "payment_status": PAYMENT_STATUSES,
    "coupon_usage_status": COUPON_USAGE_STATUSES,
    "coupon_issue_status": COUPON_ISSUE_STATUSES,
}
BOOLEAN_FACTS = {
    "found",
    "mq_dead_letter_present",
    "knowledge_found",
    "policy_available",
    "campaign_draft_generated",
}


@dataclass(frozen=True)
class ToolOutcome:
    tool_name: str
    status: str
    facts: dict[str, object]


def _enum(value: object, allowed: set[str]) -> str:
    return value if isinstance(value, str) and value in allowed else "UNKNOWN"


def _mapping(value: object) -> Mapping[str, object] | None:
    return value if isinstance(value, Mapping) else None


def _parse_result(raw_result: object) -> Mapping[str, object] | None:
    if isinstance(raw_result, Mapping):
        return raw_result
    if not isinstance(raw_result, str):
        return None
    try:
        parsed = json.loads(raw_result)
    except (TypeError, ValueError):
        return None
    return _mapping(parsed)


def _non_empty_text(raw_result: object) -> bool:
    if isinstance(raw_result, str):
        return bool(raw_result.strip())
    return isinstance(raw_result, Mapping) and bool(raw_result)


def _coupon_usage_status(coupon: Mapping[str, object] | None) -> str:
    if coupon is None or "coupon_status" not in coupon:
        return "UNKNOWN"
    status = coupon["coupon_status"]
    if status is None or status == "NOT_USED":
        return "NONE"
    return _enum(status, COUPON_USAGE_STATUSES)


def _normalize_order(data: Mapping[str, object]) -> dict[str, object] | None:
    payment = _mapping(data.get("payment"))
    coupon = _mapping(data.get("coupon"))
    if "order_status" not in data and payment is None and coupon is None:
        return None
    return {
        "found": True,
        "order_status": _enum(data.get("order_status"), ORDER_STATUSES),
        "payment_status": _enum(
            payment.get("pay_status") if payment is not None else None,
            PAYMENT_STATUSES,
        ),
        "coupon_usage_status": _coupon_usage_status(coupon),
    }


def _normalize_payment(data: Mapping[str, object]) -> dict[str, object] | None:
    payments = data.get("payments")
    if not isinstance(payments, list):
        return None
    payment_records: list[Mapping[str, object]] = []
    for raw_payment in payments:
        payment = _mapping(raw_payment)
        if payment is None:
            return None
        if "pay_status" in payment:
            payment_records.append(payment)
    if not payment_records:
        return {"found": False, "payment_status": "UNKNOWN"}
    return {
        "found": True,
        "payment_status": _enum(
            payment_records[0].get("pay_status"),
            PAYMENT_STATUSES,
        ),
    }


def _normalize_coupon_log(data: Mapping[str, object]) -> dict[str, object] | None:
    coupon = _mapping(data.get("coupon"))
    outbox_messages = data.get("outbox_messages")
    if coupon is None and not isinstance(outbox_messages, list):
        return None
    if not isinstance(outbox_messages, list):
        return None

    statuses: list[str] = []
    for raw_message in outbox_messages:
        message = _mapping(raw_message)
        if message is None:
            return None
        statuses.append(_enum(message.get("status"), COUPON_ISSUE_STATUSES))
    issue_status = "NO_RECORD" if not statuses else (
        "FAILED" if "FAILED" in statuses else statuses[0]
    )
    if issue_status == "FAILED":
        confirmed: bool | str = True
    elif issue_status == "SENT":
        confirmed = False
    else:
        confirmed = "UNKNOWN"
    return {
        "found": bool(statuses),
        "coupon_usage_status": _coupon_usage_status(coupon),
        "coupon_issue_status": issue_status,
        "coupon_failure_confirmed": confirmed,
    }


def _normalize_mq_dead_letter(data: Mapping[str, object]) -> dict[str, object] | None:
    count = data.get("count")
    dead_letters = data.get("dead_letters")
    if isinstance(count, bool) or not isinstance(count, int) or count < 0:
        return None
    if not isinstance(dead_letters, list):
        return None
    return {
        "found": True,
        "mq_dead_letter_present": count > 0 or bool(dead_letters),
    }


def _has_coupon_template_identity(data: Mapping[str, object]) -> bool:
    identity = data.get("coupon_template_id")
    if isinstance(identity, bool) or isinstance(identity, (Mapping, list)):
        return False
    return identity is not None and bool(str(identity).strip())


def _normalize_policy(data: Mapping[str, object]) -> dict[str, object] | None:
    is_wrapper = "count" in data or "coupons" in data
    if not is_wrapper:
        return (
            {"policy_available": True}
            if _has_coupon_template_identity(data)
            else None
        )

    count = data.get("count")
    coupons = data.get("coupons")
    if isinstance(count, bool) or not isinstance(count, int) or count < 0:
        return None
    if not isinstance(coupons, list) or len(coupons) != count:
        return None
    for raw_coupon in coupons:
        coupon = _mapping(raw_coupon)
        if coupon is None or not _has_coupon_template_identity(coupon):
            return None
    return {"policy_available": count > 0}


def _normalize_facts(tool_name: str, data: Mapping[str, object]) -> dict[str, object] | None:
    if tool_name == "query_order":
        return _normalize_order(data)
    if tool_name == "query_payment":
        return _normalize_payment(data)
    if tool_name == "query_coupon_issue_log":
        return _normalize_coupon_log(data)
    if tool_name == "query_mq_dead_letter":
        return _normalize_mq_dead_letter(data)
    if tool_name == "knowledge_search":
        found = data.get("found")
        return {"knowledge_found": found} if isinstance(found, bool) else None
    if tool_name == "coupon_policy_lookup":
        return _normalize_policy(data)
    return {}


def normalize_tool_outcome(
    tool_name: str,
    raw_result: object = None,
    error_reason: str | None = None,
) -> ToolOutcome:
    """Convert one tool result into a bounded, non-sensitive evidence record."""
    if error_reason is not None:
        return ToolOutcome(
            tool_name,
            ERROR_STATUS_MAP.get(error_reason, "internal_error"),
            {},
        )

    if tool_name == "campaign_draft_generate":
        if not _non_empty_text(raw_result):
            return ToolOutcome(tool_name, "internal_error", {})
        return ToolOutcome(tool_name, "success", {"campaign_draft_generated": True})
    if tool_name in TEXT_SUCCESS_TOOLS:
        return ToolOutcome(
            tool_name,
            "success" if _non_empty_text(raw_result) else "internal_error",
            {},
        )
    if tool_name not in CONTROLLED_READ_TOOLS:
        return ToolOutcome(tool_name, "internal_error", {})

    data = _parse_result(raw_result)
    if data is None:
        return ToolOutcome(tool_name, "internal_error", {})
    facts = _normalize_facts(tool_name, data)
    if facts is None:
        return ToolOutcome(tool_name, "internal_error", {})
    if tool_name == "knowledge_search" and facts["knowledge_found"] is False:
        return ToolOutcome(tool_name, "not_found", facts)
    if tool_name == "query_payment" and facts["found"] is False:
        return ToolOutcome(tool_name, "not_found", facts)
    if tool_name == "query_coupon_issue_log" and facts["found"] is False:
        return ToolOutcome(tool_name, "not_found", facts)
    if tool_name == "coupon_policy_lookup" and facts["policy_available"] is False:
        return ToolOutcome(tool_name, "not_found", facts)
    return ToolOutcome(tool_name, "success", facts)


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
        "evidence_stop_reason": "permission_denied" if blocked_first else None,
        "synthesis_only": False,
    }


def _bounded_facts(tool_name: str, facts: Mapping[str, object]) -> dict[str, object]:
    bounded: dict[str, object] = {}
    for key in FACT_KEYS_BY_TOOL.get(tool_name, set()):
        value = facts.get(key)
        if key in ENUM_FACTS and key in facts:
            bounded[key] = _enum(value, ENUM_FACTS[key])
        elif key in BOOLEAN_FACTS and isinstance(value, bool):
            bounded[key] = value
    if tool_name == "query_coupon_issue_log" and (
        "coupon_issue_status" in bounded or "coupon_failure_confirmed" in facts
    ):
        issue_status = bounded.get("coupon_issue_status")
        incoming_confirmation = facts.get("coupon_failure_confirmed")
        if issue_status == "FAILED" and incoming_confirmation is True:
            bounded["coupon_failure_confirmed"] = True
        elif issue_status == "SENT":
            bounded["coupon_failure_confirmed"] = False
        else:
            bounded["coupon_failure_confirmed"] = "UNKNOWN"
    return bounded


def _attempts(record: object) -> int:
    if not isinstance(record, Mapping):
        return 0
    attempts = record.get("attempts")
    return (
        attempts
        if isinstance(attempts, int)
        and not isinstance(attempts, bool)
        and attempts >= 0
        else 0
    )


def _rebound_status(
    tool_name: str,
    status: str,
    facts: Mapping[str, object],
) -> str:
    if tool_name == "coupon_policy_lookup" and status == "success":
        if facts.get("policy_available") is False:
            return "not_found"
        if facts.get("policy_available") is not True:
            return "internal_error"
    return status


def _sanitize_records(records: object) -> dict[str, dict[str, object]]:
    if not isinstance(records, Mapping):
        return {}
    sanitized: dict[str, dict[str, object]] = {}
    for tool_name, raw_record in records.items():
        if tool_name not in KNOWN_EVIDENCE_TOOLS:
            continue
        if not isinstance(raw_record, Mapping):
            continue
        status = raw_record.get("status")
        attempts = raw_record.get("attempts")
        facts = raw_record.get("facts")
        if status not in VALID_STATUSES:
            continue
        if (
            not isinstance(attempts, int)
            or isinstance(attempts, bool)
            or attempts < 0
            or not isinstance(facts, Mapping)
        ):
            continue
        bounded_facts = _bounded_facts(tool_name, facts)
        sanitized[tool_name] = {
            "status": _rebound_status(tool_name, status, bounded_facts),
            "attempts": attempts,
            "facts": bounded_facts,
        }
    return sanitized


def _invalid_outcome_reason(
    state: Mapping[str, object],
    outcome: ToolOutcome,
) -> str | None:
    if outcome.tool_name not in KNOWN_EVIDENCE_TOOLS:
        return "internal_error"
    required = state.get("route_required_tools", ())
    if (
        outcome.tool_name != state.get("route_next_tool")
        or outcome.tool_name not in required
    ):
        return "internal_error"
    if outcome.tool_name not in state.get("route_authorized_tools", ()):
        return "permission_denied"
    return None


def _terminal(update: dict[str, object], reason: str) -> dict[str, object]:
    update["route_next_tool"] = None
    update["evidence_stop_reason"] = reason
    return update


def _complete(update: dict[str, object]) -> dict[str, object]:
    update["route_next_tool"] = None
    update["evidence_complete"] = True
    update["evidence_stop_reason"] = None
    update["synthesis_only"] = True
    return update


def _stored_facts(records: Mapping[str, object], tool_name: str) -> Mapping[str, object]:
    record = records.get(tool_name)
    return record.get("facts", {}) if isinstance(record, Mapping) else {}


def _allows_next_action(
    task_type: object,
    candidate: str,
    records: Mapping[str, object],
) -> bool:
    order_facts = _stored_facts(records, "query_order")
    if candidate == "execute_refund" and task_type == "refund_action":
        return order_facts.get("order_status") in {"PAID", "COMPLETED"}
    if candidate == "issue_compensation_coupon" and task_type == "compensation_action":
        coupon_facts = _stored_facts(records, "query_coupon_issue_log")
        return (
            coupon_facts.get("coupon_issue_status") == "FAILED"
            and coupon_facts.get("coupon_failure_confirmed") is True
        )
    return True


def advance_evidence(
    state: Mapping[str, object], outcomes: Sequence[ToolOutcome]
) -> dict[str, object]:
    """Store one normalized outcome and choose the next controlled evidence step."""
    update = dict(state)
    if (
        state.get("route_mode") != "controlled"
        or state.get("evidence_stop_reason") is not None
        or state.get("evidence_complete") is True
        or not outcomes
    ):
        return update

    outcome = outcomes[0]
    records = _sanitize_records(state.get("evidence_collected", {}))
    update["evidence_collected"] = records
    invalid_reason = _invalid_outcome_reason(state, outcome)
    if invalid_reason is not None:
        return _terminal(update, invalid_reason)

    status = outcome.status if outcome.status in VALID_STATUSES else "internal_error"
    facts = _bounded_facts(outcome.tool_name, outcome.facts)
    status = _rebound_status(outcome.tool_name, status, facts)
    records[outcome.tool_name] = {
        "status": status,
        "attempts": _attempts(records.get(outcome.tool_name)) + 1,
        "facts": facts,
    }
    update["evidence_collected"] = records

    if status in {"parameter_error", "timeout"}:
        if records[outcome.tool_name]["attempts"] == 1:
            update["route_next_tool"] = outcome.tool_name
            return update
        return _terminal(update, status)
    if status in {
        "not_found",
        "permission_denied",
        "business_rejected",
        "internal_error",
        "pending_hitl",
    }:
        return _terminal(update, status)

    required = list(state.get("route_required_tools", ()))
    current_index = required.index(outcome.tool_name)
    remaining = required[current_index + 1 :]
    if not remaining:
        return _complete(update)

    candidate = remaining[0]
    if (
        state.get("route_task_type") == "coupon_root_cause"
        and candidate == "query_mq_dead_letter"
        and facts.get("coupon_issue_status") != "FAILED"
    ):
        return _complete(update)
    if not _allows_next_action(state.get("route_task_type"), candidate, records):
        return _terminal(update, "business_rejected")
    authorized = set(state.get("route_authorized_tools", ()))
    if candidate not in authorized:
        return _terminal(update, "permission_denied")
    update["route_next_tool"] = candidate
    return update
