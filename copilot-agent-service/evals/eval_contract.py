"""Fail-closed validation for Agent evaluation cases."""
from __future__ import annotations

from dataclasses import dataclass
from collections import Counter
from typing import Iterable

from agent.nodes import HITL_TOOLS
from agent.tool_router import TOOL_ROLE_MAP
from evals.eval_cases import EvalCase
from evals.fixtures import FixtureCatalog, case_fixture_references


VALID_OUTCOMES = {
    "success",
    "permission_denied",
    "escalation",
    "hitl",
    "refusal",
    "not_found",
}
REFUSAL_OUTCOMES = {"refusal", "permission_denied", "escalation"}


@dataclass(frozen=True)
class ContractViolation:
    case_id: int
    code: str
    detail: str


@dataclass(frozen=True)
class ContractValidationResult:
    violations: list[ContractViolation]
    fixture_reference_count: int
    fixture_resolved_count: int

    @property
    def valid(self) -> bool:
        return not self.violations

    @property
    def fixture_resolution_rate(self) -> float:
        if self.fixture_reference_count == 0:
            return 1.0
        return self.fixture_resolved_count / self.fixture_reference_count


def validate_eval_contract(
    cases: Iterable[EvalCase],
    fixtures: FixtureCatalog,
    *,
    expected_case_count: int | None = None,
) -> ContractValidationResult:
    case_list = list(cases)
    violations: list[ContractViolation] = []
    fixture_refs = 0
    resolved_refs = 0

    if expected_case_count is not None and len(case_list) != expected_case_count:
        violations.append(
            ContractViolation(
                case_id=0,
                code="unexpected_case_count",
                detail=f"expected {expected_case_count}, got {len(case_list)}",
            )
        )
    for case_id, count in Counter(case.id for case in case_list).items():
        if count > 1:
            violations.append(
                ContractViolation(
                    case_id=case_id,
                    code="duplicate_case_id",
                    detail=f"case id occurs {count} times",
                )
            )

    for case in case_list:
        refs = case_fixture_references(case)
        fixture_refs += len(refs)
        resolved_refs += sum(fixtures.has(ref) for ref in refs)
        for ref in sorted(refs):
            if not fixtures.has(ref):
                violations.append(_violation(case, "missing_fixture", ref))

        if case.expected_outcome not in VALID_OUTCOMES:
            violations.append(
                _violation(case, "invalid_outcome", case.expected_outcome)
            )
        if (case.expected_outcome == "hitl") != case.expected_hitl:
            violations.append(
                _violation(
                    case,
                    "outcome_flag_mismatch",
                    "hitl outcome and expected_hitl must agree",
                )
            )
        if (case.expected_outcome in REFUSAL_OUTCOMES) != case.expected_refusal:
            violations.append(
                _violation(
                    case,
                    "outcome_flag_mismatch",
                    "refusal outcome and expected_refusal must agree",
                )
            )

        allowed_tools = set(case.allowed_tools or [])
        if case.allowed_tools is not None:
            unexpected = set(case.expected_tools) - allowed_tools
            for tool in sorted(unexpected):
                violations.append(
                    _violation(case, "expected_tool_not_allowed", tool)
                )
        for tool in sorted(allowed_tools & set(case.forbidden_tools)):
            violations.append(
                _violation(case, "conflicting_tool_policy", tool)
            )

        fact_tools = _fact_tools(case)
        declared_tools = set(case.expected_tools)
        declared_tools.update(allowed_tools)
        declared_tools.update(case.forbidden_tools)
        declared_tools.update(case.expected_args)
        declared_tools.update(fact_tools)
        for tool in sorted(declared_tools):
            if tool not in TOOL_ROLE_MAP:
                violations.append(_violation(case, "unknown_tool", tool))

        role_checked_tools = (
            set(case.expected_tools)
            | allowed_tools
            | set(case.expected_args)
            | fact_tools
        )
        for tool in sorted(role_checked_tools):
            if tool in TOOL_ROLE_MAP and case.role not in TOOL_ROLE_MAP[tool]:
                violations.append(
                    _violation(
                        case,
                        "role_forbidden_tool",
                        f"{case.role} cannot call {tool}",
                    )
                )

        high_risk = (
            set(case.expected_tools)
            | allowed_tools
            | set(case.expected_args)
            | fact_tools
        ) & HITL_TOOLS
        if high_risk and (
            not case.expected_hitl or case.expected_outcome != "hitl"
        ):
            violations.append(
                _violation(
                    case,
                    "high_risk_without_hitl",
                    ",".join(sorted(high_risk)),
                )
            )

    return ContractValidationResult(
        violations=violations,
        fixture_reference_count=fixture_refs,
        fixture_resolved_count=resolved_refs,
    )


def _violation(case: EvalCase, code: str, detail: str) -> ContractViolation:
    return ContractViolation(case_id=case.id, code=code, detail=detail)


def _fact_tools(case: EvalCase) -> set[str]:
    facts = list(case.expected_facts)
    for alternatives in case.any_of_facts:
        facts.extend(alternatives)
    return {
        str(fact["tool"])
        for fact in facts
        if fact.get("source") == "tool_output" and fact.get("tool")
    }
