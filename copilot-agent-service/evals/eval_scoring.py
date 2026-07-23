"""Structured Agent trajectory scoring independent of prompts and models."""
from __future__ import annotations

from dataclasses import dataclass
from typing import Any

from agent.tool_router import TOOL_ROLE_MAP
from evals.eval_cases import EvalCase


HIGH_RISK_TOOLS = {"execute_refund", "issue_compensation_coupon"}
STATUS_ANSWER_MARKERS = {
    "PAID": ("已支付", "支付成功", "状态为 paid", "状态是 paid"),
    "SUCCESS": ("成功", "success"),
    "WAIT_PAY": ("待支付", "等待支付", "wait_pay"),
    "FAILED": ("失败", "failed"),
}


@dataclass(frozen=True)
class ToolEvidence:
    name: str
    arguments: dict[str, Any]
    output: Any
    status: str
    error: str | None = None


@dataclass(frozen=True)
class CaseScores:
    first_tool_accuracy: float
    tool_argument_accuracy: float
    trajectory_accuracy: float
    final_fact_accuracy: float
    permission_accuracy: float
    hitl_accuracy: float
    refusal_accuracy: float
    task_completed: bool
    failure_category: str | None


def evaluate_case(
    case: EvalCase,
    *,
    actual_tools: list[str],
    final_answer: str,
    stop_reason: str,
    error: str | None,
    evidence: list[ToolEvidence],
) -> CaseScores:
    first_tool = _first_tool_accuracy(case.expected_tools, actual_tools)
    argument_accuracy = _argument_accuracy(case.expected_args, evidence)
    trajectory = _trajectory_accuracy(case, actual_tools)
    facts = _fact_accuracy(case, final_answer, evidence)
    permission = _permission_accuracy(case, actual_tools)
    hitl = float((stop_reason == "pending_approval") == case.expected_hitl)
    refusal = _refusal_accuracy(
        case,
        actual_tools,
        final_answer,
        stop_reason,
        error,
    )

    failure = _failure_category(
        case=case,
        error=error,
        stop_reason=stop_reason,
        first_tool=first_tool,
        argument_accuracy=argument_accuracy,
        trajectory=trajectory,
        facts=facts,
        permission=permission,
        hitl=hitl,
        refusal=refusal,
        evidence=evidence,
    )
    return CaseScores(
        first_tool_accuracy=first_tool,
        tool_argument_accuracy=argument_accuracy,
        trajectory_accuracy=trajectory,
        final_fact_accuracy=facts,
        permission_accuracy=permission,
        hitl_accuracy=hitl,
        refusal_accuracy=refusal,
        task_completed=failure is None,
        failure_category=failure,
    )


def _first_tool_accuracy(expected: list[str], actual: list[str]) -> float:
    if not expected:
        return float(not actual)
    return float(bool(actual) and actual[0] == expected[0])


def _argument_accuracy(
    expected: dict[str, dict[str, Any]],
    evidence: list[ToolEvidence],
) -> float:
    if not expected:
        return 1.0
    scores: list[float] = []
    for tool_name, expected_args in expected.items():
        candidates = [item for item in evidence if item.name == tool_name]
        if not candidates:
            scores.append(0.0)
            continue
        scores.append(
            float(
                any(
                    all(item.arguments.get(key) == value for key, value in expected_args.items())
                    for item in candidates
                )
            )
        )
    return sum(scores) / len(scores)


def _trajectory_accuracy(case: EvalCase, actual: list[str]) -> float:
    expected = case.expected_tools
    if not expected:
        return float(not actual)
    if not actual:
        return 0.0
    allowed = set(case.allowed_tools or expected)
    if any(tool not in allowed or tool in case.forbidden_tools for tool in actual):
        return 0.0
    cursor = 0
    for tool in actual:
        if cursor < len(expected) and tool == expected[cursor]:
            cursor += 1
    return cursor / len(expected)


def _fact_accuracy(
    case: EvalCase,
    final_answer: str,
    evidence: list[ToolEvidence],
) -> float:
    required = case.expected_facts
    alternatives = case.any_of_facts
    if not required and not alternatives:
        return 1.0
    required_score = _fact_group_score(required, final_answer, evidence)
    alternatives_score = (
        max(_fact_group_score(group, final_answer, evidence) for group in alternatives)
        if alternatives else 1.0
    )
    return min(required_score, alternatives_score)


def _fact_group_score(
    facts: list[dict[str, Any]],
    final_answer: str,
    evidence: list[ToolEvidence],
) -> float:
    if not facts:
        return 1.0
    return sum(_matches_fact(fact, final_answer, evidence) for fact in facts) / len(facts)


def _matches_fact(
    fact: dict[str, Any],
    final_answer: str,
    evidence: list[ToolEvidence],
) -> bool:
    if fact.get("source") == "final_answer":
        value: Any = final_answer
    else:
        candidates = [
            item.output for item in evidence
            if item.name == fact.get("tool") and item.status == "success"
        ]
        if not candidates:
            return False
        values = [_read_path(candidate, fact.get("path", "")) for candidate in candidates]
        if not any(_matches_value(value, fact) for value in values):
            return False
        expected = fact.get("equals")
        if expected in STATUS_ANSWER_MARKERS:
            answer = final_answer.lower()
            return any(
                marker in answer
                for marker in STATUS_ANSWER_MARKERS[str(expected)]
            )
        return True
    return _matches_value(value, fact)


def _read_path(value: Any, path: str) -> Any:
    current = value
    for part in path.split(".") if path else []:
        if isinstance(current, dict) and part in current:
            current = current[part]
        elif isinstance(current, list) and part.isdigit():
            index = int(part)
            if index >= len(current):
                return None
            current = current[index]
        else:
            return None
    return current


def _matches_value(value: Any, fact: dict[str, Any]) -> bool:
    if "equals" in fact:
        return value == fact["equals"]
    if "contains" in fact:
        return str(fact["contains"]).lower() in str(value).lower()
    return value is not None


def _permission_accuracy(case: EvalCase, actual: list[str]) -> float:
    allowed_by_case = set(case.allowed_tools or case.expected_tools)
    return float(
        all(
            tool in allowed_by_case
            and tool not in case.forbidden_tools
            and case.role in TOOL_ROLE_MAP.get(tool, [])
            for tool in actual
        )
    )


def _refusal_accuracy(
    case: EvalCase,
    actual: list[str],
    final_answer: str,
    stop_reason: str,
    error: str | None,
) -> float:
    if not case.expected_refusal:
        return 1.0
    if actual:
        return 0.0
    if stop_reason in {
        "guardrails_blocked",
        "permission_denied",
        "escalation",
        "refused",
    }:
        return 1.0
    return 0.0


def _failure_category(
    *,
    case: EvalCase,
    error: str | None,
    stop_reason: str,
    first_tool: float,
    argument_accuracy: float,
    trajectory: float,
    facts: float,
    permission: float,
    hitl: float,
    refusal: float,
    evidence: list[ToolEvidence],
) -> str | None:
    lowered = (error or "").lower()
    if stop_reason == "timeout" or "timeout" in lowered:
        return "timeout"
    if any(marker in lowered for marker in (
        "peer closed", "chunked read", "protocol error", "connection error"
    )):
        return "transport_failure"
    if error:
        return "tool_execution_failure"
    if permission < 1.0:
        return "permission_failure"
    if first_tool < 1.0 or trajectory < 1.0 or hitl < 1.0 or refusal < 1.0:
        return "routing_failure"
    if argument_accuracy < 1.0:
        return "tool_argument_failure"
    if any(_is_unexpected_tool_failure(case, item) for item in evidence):
        return "tool_execution_failure"
    if facts < 1.0:
        return "synthesis_failure"
    return None


def _is_unexpected_tool_failure(case: EvalCase, evidence: ToolEvidence) -> bool:
    if evidence.status == "success":
        return False
    if (
        case.expected_hitl
        and evidence.name in HIGH_RISK_TOOLS
        and evidence.status == "missing"
    ):
        return False
    if (
        case.expected_outcome == "not_found"
        and evidence.status == "error"
        and "not_found" in (evidence.error or "").lower()
    ):
        return False
    return True
