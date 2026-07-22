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
from dataclasses import dataclass
from datetime import datetime
from pathlib import Path
from typing import Iterable

from evals.eval_cases import (
    BOUNDARY_CASES,
    DIAGNOSIS_CASES,
    KNOWLEDGE_CASES,
    QUERY_CASES,
    EvalCase,
)
from evals.metrics import calc_keyword_coverage, calc_tool_sequence_match
from evals.real_agent_client import invoke_real_agent


@dataclass(frozen=True)
class BaselineCaseResult:
    case_id: int
    category: str
    role: str
    concurrency: int
    iteration: int
    success: bool
    task_completed: bool
    tool_seq_match: float
    keyword_coverage: float
    tool_count: int
    latency_ms: float
    ttft_ms: int | None
    stop_reason: str
    error_type: str | None


def select_baseline_cases() -> list[EvalCase]:
    """Pick a stable 24-case slice across query, diagnosis, knowledge, boundary."""
    return [
        *QUERY_CASES[:6],
        *DIAGNOSIS_CASES[:6],
        *KNOWLEDGE_CASES[:7],
        *BOUNDARY_CASES[:5],
    ]


async def run_group(
    cases: list[EvalCase],
    concurrency: int,
    repeat: int,
    agent_url: str | None,
) -> list[BaselineCaseResult]:
    semaphore = asyncio.Semaphore(concurrency)
    jobs = [
        _run_one(case, concurrency=concurrency, iteration=iteration, semaphore=semaphore, agent_url=agent_url)
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
) -> BaselineCaseResult:
    async with semaphore:
        started_at = time.perf_counter()
        try:
            response = await invoke_real_agent(
                message=case.input,
                role=case.role,
                merchant_id=case.merchant_id,
                agent_url=agent_url,
            )
            latency_ms = response.get("latency_ms")
            if latency_ms is None:
                latency_ms = (time.perf_counter() - started_at) * 1000
            actual_tools = response.get("tools_called", [])
            final_answer = response.get("final_answer", "")
            tool_match = calc_tool_sequence_match(case.expected_tools, actual_tools)
            keyword_coverage = calc_keyword_coverage(case.expected_keywords, final_answer)
            task_completed = tool_match >= 0.6 and keyword_coverage >= 0.5
            error_msg = response.get("error")
            return BaselineCaseResult(
                case_id=case.id,
                category=case.category,
                role=case.role,
                concurrency=concurrency,
                iteration=iteration,
                success=error_msg is None,
                task_completed=task_completed,
                tool_seq_match=tool_match,
                keyword_coverage=keyword_coverage,
                tool_count=len(actual_tools),
                latency_ms=float(latency_ms),
                ttft_ms=response.get("ttft_ms"),
                stop_reason=response.get("stop_reason", "unknown"),
                error_type=_classify_error(error_msg),
            )
        except Exception as exc:
            return BaselineCaseResult(
                case_id=case.id,
                category=case.category,
                role=case.role,
                concurrency=concurrency,
                iteration=iteration,
                success=False,
                task_completed=False,
                tool_seq_match=0.0,
                keyword_coverage=0.0,
                tool_count=0,
                latency_ms=(time.perf_counter() - started_at) * 1000,
                ttft_ms=None,
                stop_reason="runner_error",
                error_type=exc.__class__.__name__,
            )


def summarize(results: list[BaselineCaseResult]) -> dict:
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
        "groups": by_concurrency,
        "results": [row.__dict__ for row in results],
    }


def _summarize_rows(rows: list[BaselineCaseResult]) -> dict:
    latencies = [row.latency_ms for row in rows if row.success]
    ttfts = [row.ttft_ms for row in rows if row.ttft_ms is not None and row.success]
    total = len(rows) or 1
    return {
        "runs": len(rows),
        "success_rate": sum(row.success for row in rows) / total,
        "task_completion_rate": sum(row.task_completed for row in rows) / total,
        "tool_call_accuracy": statistics.mean(row.tool_seq_match for row in rows) if rows else 0.0,
        "keyword_coverage": statistics.mean(row.keyword_coverage for row in rows) if rows else 0.0,
        "latency_p50_ms": _percentile(latencies, 0.50),
        "latency_p95_ms": _percentile(latencies, 0.95),
        "latency_p99_ms": _percentile(latencies, 0.99),
        "ttft_p50_ms": _percentile(ttfts, 0.50),
        "ttft_p95_ms": _percentile(ttfts, 0.95),
        "error_types": _count(row.error_type for row in rows if row.error_type),
    }


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
    if "connection" in lowered:
        return "connection_error"
    if "http 4" in lowered:
        return "http_4xx"
    if "http 5" in lowered:
        return "http_5xx"
    return "other"


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
        "- Stored data: sanitized metrics only; prompts, answers, tool payloads, and keys are not persisted.",
        "",
        "| Concurrency | Runs | Success | Task Done | Tool Acc | Keyword | Latency P50 | Latency P95 | Latency P99 | TTFT P50 | TTFT P95 | Errors |",
        "| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | --- |",
    ]
    for concurrency, group in report["groups"].items():
        lines.append(
            f"| {concurrency} | {group['runs']} | {group['success_rate']:.3f} | "
            f"{group['task_completion_rate']:.3f} | {group['tool_call_accuracy']:.3f} | "
            f"{group['keyword_coverage']:.3f} | {_fmt(group['latency_p50_ms'])} | "
            f"{_fmt(group['latency_p95_ms'])} | {_fmt(group['latency_p99_ms'])} | "
            f"{_fmt(group['ttft_p50_ms'])} | {_fmt(group['ttft_p95_ms'])} | "
            f"{group['error_types']} |"
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
    parser.add_argument("--concurrency", default="1,3,5", help="comma-separated concurrency groups")
    parser.add_argument("--repeat", type=int, default=2, help="repeat count per selected case")
    args = parser.parse_args()

    if not os.environ.get("LLM_API_KEY"):
        raise SystemExit("LLM_API_KEY is required for the real DeepSeek baseline")

    cases = select_baseline_cases()
    groups = [int(item.strip()) for item in args.concurrency.split(",") if item.strip()]
    all_results: list[BaselineCaseResult] = []
    for concurrency in groups:
        print(f"running concurrency={concurrency}, cases={len(cases)}, repeat={args.repeat}")
        all_results.extend(asyncio.run(run_group(cases, concurrency, args.repeat, args.agent_url)))

    report = summarize(all_results)
    json_path, md_path = write_outputs(report, Path(args.output_dir), args.run_name)
    print(json.dumps({k: v for k, v in report.items() if k != "results"}, ensure_ascii=False, indent=2))
    print(f"reports: {json_path} {md_path}")


if __name__ == "__main__":
    main()
