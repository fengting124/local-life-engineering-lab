"""DeepSeek real-agent performance baseline runner.

The normal eval report stores answers for quality debugging. This runner is for
performance evidence, so it persists only sanitized metrics: no prompts, no
answers, no API keys, and no raw tool payloads.
"""
from __future__ import annotations

import argparse
import asyncio
import json
import os
import statistics
import time
from dataclasses import dataclass, replace
from datetime import datetime
from pathlib import Path
from typing import Iterable

from agent.tool_router import TOOL_ROLE_MAP
from evals.eval_cases import (
    BOUNDARY_CASES,
    DIAGNOSIS_CASES,
    KNOWLEDGE_CASES,
    QUERY_CASES,
    EvalCase,
)
from evals.eval_contract import ContractValidationResult, validate_eval_contract
from evals.eval_database import EvalDatabase
from evals.eval_scoring import evaluate_case
from evals.fixtures import resolve_cases
from evals.real_agent_client import invoke_real_agent


@dataclass(frozen=True)
class BaselineCaseResult:
    case_id: int
    category: str
    role: str
    expected_outcome: str
    concurrency: int
    iteration: int
    success: bool
    task_completed: bool
    first_tool_accuracy: float
    tool_argument_accuracy: float
    trajectory_accuracy: float
    final_fact_accuracy: float
    permission_accuracy: float
    hitl_accuracy: float
    refusal_accuracy: float
    tool_count: int
    actual_tools: tuple[str, ...]
    latency_ms: float
    time_to_first_sse_ms: int | None
    stop_reason: str
    failure_category: str | None


def _sanitize_tool_sequence(tools: Iterable[object]) -> tuple[str, ...]:
    """Persist only names from the production tool registry."""
    return tuple(
        tool if isinstance(tool, str) and tool in TOOL_ROLE_MAP else "unknown_tool"
        for tool in tools
    )


def select_baseline_cases() -> list[EvalCase]:
    """Pick the 24 cases and attach the real baseline contract."""
    selected = [
        *QUERY_CASES[:6],
        *DIAGNOSIS_CASES[:6],
        *KNOWLEDGE_CASES[:7],
        *BOUNDARY_CASES[:5],
    ]
    return [_with_baseline_contract(case) for case in selected]


def _with_baseline_contract(case: EvalCase) -> EvalCase:
    actor = f"{{{{fixture.actor.{case.role}.user_id}}}}"
    merchant = (
        "{{fixture.actor.merchant.merchant_id}}"
        if case.role == "merchant"
        else None
    )
    common = {
        "user_id": actor,
        "merchant_id": merchant,
        "allowed_tools": list(case.expected_tools),
    }
    overrides: dict[int, dict] = {
        1: {
            "expected_facts": [_tool_fact("shop_metrics_query", "gmv")],
        },
        2: {
            "expected_facts": [_tool_fact("shop_metrics_query", "order_count")],
        },
        3: {
            "expected_facts": [_tool_fact("shop_metrics_query", "gmv")],
        },
        4: {
            "input": "订单 {{fixture.order.paid.order_no}} 的状态是什么？",
            "expected_args": {
                "query_order": {
                    "order_id": "{{fixture.order.paid.order_no}}",
                }
            },
            "expected_facts": [
                _tool_fact("query_order", "order_status", equals="PAID")
            ],
        },
        5: {
            "input": "帮我查一下 {{fixture.order.paid.order_no}} 的支付情况",
            "role": "admin",
            "user_id": "{{fixture.actor.admin.user_id}}",
            "expected_args": _order_args(
                "{{fixture.order.paid.order_no}}",
                "query_order",
                "query_payment",
            ),
            "expected_facts": [
                _tool_fact(
                    "query_payment",
                    "payments.0.pay_status",
                    equals="SUCCESS",
                )
            ],
        },
        6: {
            "expected_facts": [
                _tool_fact("shop_metrics_query", "coupon_used_count")
            ],
        },
        16: _admin_order_contract(
            "用户说 {{fixture.order.coupon_issue.order_no}} 支付了但没收到券",
            "{{fixture.order.coupon_issue.order_no}}",
            ["query_order", "query_coupon_issue_log"],
            [_tool_fact("query_order", "order_status", equals="PAID")],
        ),
        17: {
            "input": (
                "{{fixture.order.coupon_issue.order_no}} "
                "用户投诉支付成功但没发券，麻烦查一下根因"
            ),
            "expected_outcome": "permission_denied",
            "expected_tools": ["query_order"],
            "allowed_tools": ["query_order"],
            "forbidden_tools": [
                "query_coupon_issue_log",
                "query_mq_dead_letter",
            ],
            "expected_args": _order_args(
                "{{fixture.order.coupon_issue.order_no}}",
                "query_order",
            ),
            "expected_facts": [
                _tool_fact("query_order", "order_status", equals="PAID"),
                {"source": "final_answer", "contains": "管理员"},
            ],
            "expected_refusal": True,
        },
        18: _admin_order_contract(
            "{{fixture.order.payment_mismatch.order_no}} 显示已支付但状态还是待支付",
            "{{fixture.order.payment_mismatch.order_no}}",
            ["query_order", "query_payment"],
            [
                _tool_fact("query_order", "order_status", equals="WAIT_PAY"),
                _tool_fact(
                    "query_payment",
                    "payments.0.pay_status",
                    equals="SUCCESS",
                ),
            ],
        ),
        19: {
            "input": "需要给 {{fixture.order.coupon_issue.order_no}} 退款，库存不足没发出券",
            "expected_outcome": "clarification",
            "expected_tools": [],
            "allowed_tools": [],
            "expected_args": {},
            "expected_hitl": False,
        },
        20: _admin_order_contract(
            "查一下 {{fixture.order.payment_mismatch.order_no}} 的 MQ 死信情况",
            "{{fixture.order.payment_mismatch.order_no}}",
            ["query_order", "query_mq_dead_letter"],
            [_tool_fact("query_mq_dead_letter", "count")],
        ),
        21: _admin_order_contract(
            "{{fixture.order.failed_payment.order_no}} 支付失败是什么原因？",
            "{{fixture.order.failed_payment.order_no}}",
            ["query_order", "query_payment"],
            [
                _tool_fact(
                    "query_payment",
                    "payments.0.pay_status",
                    equals="FAILED",
                )
            ],
        ),
        31: _knowledge_contract(["knowledge_search"]),
        32: _knowledge_contract(["knowledge_search", "coupon_policy_lookup"]),
        33: _knowledge_contract(["knowledge_search"]),
        34: _knowledge_contract(["knowledge_search"]),
        35: _knowledge_contract(["knowledge_search"]),
        36: _knowledge_contract(["knowledge_search"]),
        37: _knowledge_contract(["knowledge_search", "coupon_policy_lookup"]),
        46: _refusal_contract(),
        47: _refusal_contract(forbidden=["query_order", "shop_metrics_query"]),
        48: _no_tool_contract(),
        49: {
            "input": "帮我查一下 {{fixture.order.missing.order_no}} 的订单",
            "expected_outcome": "not_found",
            "expected_args": {
                "query_order": {
                    "order_id": "{{fixture.order.missing.order_no}}",
                }
            },
        },
        50: _refusal_contract(
            forbidden=["execute_refund"],
        ),
    }
    return replace(case, **(common | overrides[case.id]))


def _tool_fact(tool: str, path: str, *, equals=None) -> dict:
    fact = {"source": "tool_output", "tool": tool, "path": path}
    if equals is not None:
        fact["equals"] = equals
    return fact


def _order_args(order_no: str, *tools: str) -> dict:
    return {tool: {"order_id": order_no} for tool in tools}


def _admin_order_contract(
    input_text: str,
    order_no: str,
    tools: list[str],
    facts: list[dict],
) -> dict:
    return {
        "input": input_text,
        "role": "admin",
        "user_id": "{{fixture.actor.admin.user_id}}",
        "allowed_tools": tools,
        "expected_args": _order_args(order_no, *tools),
        "expected_facts": facts,
    }


def _knowledge_contract(tools: list[str]) -> dict:
    return {
        "allowed_tools": tools,
        "expected_facts": [
            _tool_fact("knowledge_search", "found", equals=True)
        ],
    }


def _refusal_contract(*, forbidden: list[str] | None = None) -> dict:
    return {
        "expected_outcome": "refusal",
        "allowed_tools": [],
        "forbidden_tools": forbidden or [],
        "expected_refusal": True,
    }


def _no_tool_contract() -> dict:
    return {
        "expected_outcome": "success",
        "allowed_tools": [],
        "expected_refusal": False,
    }


async def run_group(
    cases: list[EvalCase],
    concurrency: int,
    repeat: int,
    agent_url: str | None,
    evidence_store: EvalDatabase | None = None,
) -> list[BaselineCaseResult]:
    semaphore = asyncio.Semaphore(concurrency)
    jobs = [
        _run_one(
            case,
            concurrency=concurrency,
            iteration=iteration,
            semaphore=semaphore,
            agent_url=agent_url,
            evidence_store=evidence_store,
        )
        for iteration in range(1, repeat + 1)
        for case in cases
    ]
    return list(await asyncio.gather(*jobs))


async def _run_one(
    case: EvalCase,
    concurrency: int,
    iteration: int,
    semaphore: asyncio.Semaphore,
    agent_url: str | None,
    evidence_store: EvalDatabase | None = None,
) -> BaselineCaseResult:
    async with semaphore:
        started_at = time.perf_counter()
        try:
            response = await invoke_real_agent(
                message=case.input,
                role=case.role,
                merchant_id=case.merchant_id,
                agent_url=agent_url,
                user_id=_eval_user_id(case, concurrency, iteration),
            )
            latency_ms = response.get("latency_ms")
            if latency_ms is None:
                latency_ms = (time.perf_counter() - started_at) * 1000
            evidence = (
                await evidence_store.load_evidence(response.get("session_id"))
                if evidence_store else []
            )
            actual_tools = response.get("tools_called", [])
            if evidence:
                actual_tools = [item.name for item in evidence]
            final_answer = response.get("final_answer", "")
            error_msg = response.get("error")
            expected_guardrail_block = (
                case.expected_refusal
                and response.get("error_code") == "BLOCKED_BY_GUARDRAILS"
            )
            if expected_guardrail_block:
                error_msg = None
            scores = evaluate_case(
                case,
                actual_tools=actual_tools,
                final_answer=final_answer,
                stop_reason=response.get("stop_reason", "unknown"),
                error=error_msg,
                evidence=evidence,
            )
            return BaselineCaseResult(
                case_id=case.id,
                category=case.category,
                role=case.role,
                expected_outcome=case.expected_outcome,
                concurrency=concurrency,
                iteration=iteration,
                success=scores.failure_category not in {
                    "timeout", "transport_failure"
                },
                task_completed=scores.task_completed,
                first_tool_accuracy=scores.first_tool_accuracy,
                tool_argument_accuracy=scores.tool_argument_accuracy,
                trajectory_accuracy=scores.trajectory_accuracy,
                final_fact_accuracy=scores.final_fact_accuracy,
                permission_accuracy=scores.permission_accuracy,
                hitl_accuracy=scores.hitl_accuracy,
                refusal_accuracy=scores.refusal_accuracy,
                tool_count=len(actual_tools),
                actual_tools=_sanitize_tool_sequence(actual_tools),
                latency_ms=float(latency_ms),
                time_to_first_sse_ms=response.get("time_to_first_sse_ms"),
                stop_reason=response.get("stop_reason", "unknown"),
                failure_category=scores.failure_category,
            )
        except Exception as exc:
            return BaselineCaseResult(
                case_id=case.id,
                category=case.category,
                role=case.role,
                expected_outcome=case.expected_outcome,
                concurrency=concurrency,
                iteration=iteration,
                success=False,
                task_completed=False,
                first_tool_accuracy=0.0,
                tool_argument_accuracy=0.0,
                trajectory_accuracy=0.0,
                final_fact_accuracy=0.0,
                permission_accuracy=0.0,
                hitl_accuracy=0.0,
                refusal_accuracy=0.0,
                tool_count=0,
                actual_tools=(),
                latency_ms=(time.perf_counter() - started_at) * 1000,
                time_to_first_sse_ms=None,
                stop_reason="runner_error",
                failure_category=_classify_exception(exc),
            )


def summarize(
    results: list[BaselineCaseResult],
    contract: ContractValidationResult | None = None,
) -> dict:
    by_concurrency = {}
    for concurrency in sorted({row.concurrency for row in results}):
        rows = [row for row in results if row.concurrency == concurrency]
        by_concurrency[str(concurrency)] = _summarize_rows(rows)
    return {
        "generated_at": datetime.now().isoformat(timespec="seconds"),
        "case_count": len({row.case_id for row in results}),
        "total_runs": len(results),
        "model": os.environ.get("LLM_MODEL", "unknown"),
        "provider": os.environ.get("LLM_PROVIDER", "unknown"),
        "contract": {
            "invalid_eval_contract": len(contract.violations) if contract else 0,
            "fixture_reference_count": contract.fixture_reference_count if contract else 0,
            "fixture_resolved_count": contract.fixture_resolved_count if contract else 0,
            "fixture_resolution_rate": contract.fixture_resolution_rate if contract else 1.0,
        },
        "groups": by_concurrency,
        "failure_matrix": [row.__dict__ for row in results if not row.task_completed],
        "results": [row.__dict__ for row in results],
    }


def _summarize_rows(rows: list[BaselineCaseResult]) -> dict:
    latencies = [row.latency_ms for row in rows if row.success]
    first_sse = [
        row.time_to_first_sse_ms
        for row in rows
        if row.time_to_first_sse_ms is not None and row.success
    ]
    total = len(rows) or 1
    return {
        "runs": len(rows),
        "success_rate": sum(row.success for row in rows) / total,
        "task_completion_rate": sum(row.task_completed for row in rows) / total,
        "first_tool_accuracy": _mean(rows, "first_tool_accuracy"),
        "tool_argument_accuracy": _mean(rows, "tool_argument_accuracy"),
        "trajectory_accuracy": _mean(rows, "trajectory_accuracy"),
        "final_fact_accuracy": _mean(rows, "final_fact_accuracy"),
        "permission_accuracy": _mean(rows, "permission_accuracy"),
        "hitl_accuracy": _mean(rows, "hitl_accuracy"),
        "refusal_accuracy": _mean(rows, "refusal_accuracy"),
        "latency_p50_ms": _percentile(latencies, 0.50),
        "latency_p95_ms": _percentile(latencies, 0.95),
        "latency_p99_ms": _percentile(latencies, 0.99),
        "time_to_first_sse_p50_ms": _percentile(first_sse, 0.50),
        "time_to_first_sse_p95_ms": _percentile(first_sse, 0.95),
        "expected_guardrail_blocks": sum(row.stop_reason == "guardrails_blocked" for row in rows),
        "failure_categories": _count(
            row.failure_category for row in rows if row.failure_category
        ),
    }


def _mean(rows: list[BaselineCaseResult], field_name: str) -> float:
    return (
        statistics.mean(getattr(row, field_name) for row in rows)
        if rows else 0.0
    )


def _eval_user_id(case: EvalCase, concurrency: int, iteration: int) -> int:
    """Use a database-backed actor; legacy cases retain a deterministic fallback."""
    if isinstance(case.user_id, int):
        return case.user_id
    return 9_000_000_000 + concurrency * 100_000 + iteration * 1_000 + case.id


def _percentile(values: list[float | int], percentile: float) -> float | None:
    if not values:
        return None
    ordered = sorted(float(value) for value in values)
    index = min(len(ordered) - 1, int((len(ordered) - 1) * percentile))
    return round(ordered[index], 2)


def _count(values: Iterable[str]) -> dict[str, int]:
    counts: dict[str, int] = {}
    for value in values:
        counts[value] = counts.get(value, 0) + 1
    return counts


def _classify_error(error: str | None) -> str | None:
    if not error:
        return None
    lowered = error.lower()
    if "timeout" in lowered:
        return "timeout"
    if any(marker in lowered for marker in ("peer closed", "chunked read", "protocol error")):
        return "transport_failure"
    if "connection" in lowered:
        return "transport_failure"
    return "tool_execution_failure"


def _classify_exception(exc: Exception) -> str:
    classified = _classify_error(str(exc))
    return classified or "tool_execution_failure"


def write_outputs(report: dict, output_dir: Path, run_name: str) -> tuple[Path, Path]:
    output_dir.mkdir(parents=True, exist_ok=True)
    json_path = output_dir / f"{run_name}.json"
    md_path = output_dir / f"{run_name}.md"
    json_path.write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")

    lines = [
        f"# DeepSeek Agent Performance Baseline: {run_name}",
        "",
        f"- Generated: `{report['generated_at']}`",
        f"- Provider: `{report['provider']}`",
        f"- Model: `{report['model']}`",
        f"- Cases: `{report['case_count']}`",
        f"- Total runs: `{report['total_runs']}`",
        f"- Invalid eval contracts: `{report['contract']['invalid_eval_contract']}`",
        f"- Fixture resolution: `{report['contract']['fixture_resolution_rate']:.3f}`",
        "- Stored data: sanitized metrics only; prompts, answers, tool payloads, and keys are not persisted.",
        "",
        "| Concurrency | Runs | Success | Task Done | First Tool | Args | Trajectory | Facts | Permission | HITL | Latency P95 | First SSE P95 | Failures |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for concurrency, group in report["groups"].items():
        lines.append(
            f"| {concurrency} | {group['runs']} | {group['success_rate']:.3f} | "
            f"{group['task_completion_rate']:.3f} | {group['first_tool_accuracy']:.3f} | "
            f"{group['tool_argument_accuracy']:.3f} | {group['trajectory_accuracy']:.3f} | "
            f"{group['final_fact_accuracy']:.3f} | {group['permission_accuracy']:.3f} | "
            f"{group['hitl_accuracy']:.3f} | {_fmt(group['latency_p95_ms'])} | "
            f"{_fmt(group['time_to_first_sse_p95_ms'])} | "
            f"{group['failure_categories']} |"
        )
    lines.extend([
        "",
        "## Per-case result matrix",
        "",
        "| Case | Iteration | Outcome | Stop | Tools | Failure |",
        "| ---: | ---: | --- | --- | --- | --- |",
    ])
    for row in report["results"]:
        lines.append(
            f"| {row['case_id']} | {row['iteration']} | "
            f"{row['expected_outcome']} | {row['stop_reason']} | "
            f"{' -> '.join(row['actual_tools']) or '-'} | "
            f"{row['failure_category'] or 'PASS'} |"
        )
    md_path.write_text("\n".join(lines) + "\n", encoding="utf-8")
    return json_path, md_path


def _fmt(value: float | None) -> str:
    return "n/a" if value is None else f"{value:.0f} ms"


def main() -> None:
    parser = argparse.ArgumentParser(description="Run sanitized DeepSeek real-agent performance baseline")
    parser.add_argument("--agent-url", default=None, help="Agent service URL, default http://localhost:8000")
    parser.add_argument("--output-dir", default="evals/reports")
    parser.add_argument("--run-name", default="deepseek-agent-baseline")
    parser.add_argument("--concurrency", default="1", help="this contract baseline only accepts 1")
    parser.add_argument("--repeat", type=int, default=2, help="repeat count per selected case")
    parser.add_argument("--db-url", default=None, help="fixture/evidence DB URL; defaults to EVAL_DB_URL")
    args = parser.parse_args()

    if not os.environ.get("LLM_API_KEY"):
        raise SystemExit("LLM_API_KEY is required for the real DeepSeek baseline")

    groups = [int(item.strip()) for item in args.concurrency.split(",") if item.strip()]
    if groups != [1] or args.repeat != 2:
        raise SystemExit("agent eval contract baseline requires --concurrency 1 --repeat 2")
    report = asyncio.run(_run_baseline(args, groups))
    json_path, md_path = write_outputs(report, Path(args.output_dir), args.run_name)
    print(json.dumps({k: v for k, v in report.items() if k != "results"}, ensure_ascii=False, indent=2))
    print(f"reports: {json_path} {md_path}")


async def _run_baseline(args, groups: list[int]) -> dict:
    database = EvalDatabase(args.db_url)
    try:
        fixtures = await database.load_fixtures()
        cases = select_baseline_cases()
        contract = validate_eval_contract(
            cases,
            fixtures,
            expected_case_count=24,
        )
        if not contract.valid:
            detail = "; ".join(
                f"case-{item.case_id}:{item.code}:{item.detail}"
                for item in contract.violations
            )
            raise SystemExit(f"invalid_eval_contract: {detail}")
        resolved_cases = resolve_cases(cases, fixtures)
        resolved_contract = validate_eval_contract(
            resolved_cases,
            fixtures,
            expected_case_count=24,
        )
        if not resolved_contract.valid:
            raise SystemExit("invalid_eval_contract after fixture resolution")

        all_results: list[BaselineCaseResult] = []
        for concurrency in groups:
            print(
                f"running concurrency={concurrency}, "
                f"cases={len(resolved_cases)}, repeat={args.repeat}"
            )
            all_results.extend(
                await run_group(
                    resolved_cases,
                    concurrency,
                    args.repeat,
                    args.agent_url,
                    database,
                )
            )
        return summarize(all_results, contract)
    finally:
        await database.close()


if __name__ == "__main__":
    main()
