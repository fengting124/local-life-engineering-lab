import asyncio

import pytest

from evals import deepseek_baseline
from evals.deepseek_baseline import (
    _classify_error,
    _run_one,
    run_group,
    summarize,
    write_outputs,
)
from evals.eval_cases import BOUNDARY_CASES, QUERY_CASES
from evals.real_agent_client import _parse_http_error


def _success_response() -> dict:
    return {
        "tools_called": [],
        "final_answer": "",
        "latency_ms": 12,
        "time_to_first_sse_ms": 3,
        "stop_reason": "completed",
        "error": None,
        "error_code": None,
        "session_id": "100",
        "thread_id": "thread-100",
    }


def test_parse_http_error_extracts_guardrail_code_without_returning_body():
    code = _parse_http_error(
        b'{"detail":{"code":"BLOCKED_BY_GUARDRAILS","message":"blocked"}}'
    )

    assert code == "BLOCKED_BY_GUARDRAILS"


@pytest.mark.parametrize(
    "message",
    [
        "peer closed connection without sending complete message body",
        "incomplete chunked read",
        "remote protocol error",
    ],
)
def test_classify_stream_disconnect_as_transport_error(message):
    assert _classify_error(message) == "transport_failure"


@pytest.mark.asyncio
async def test_expected_guardrail_block_counts_as_success(monkeypatch, tmp_path):
    async def fake_invoke(**_kwargs):
        return {
            **_success_response(),
            "stop_reason": "guardrails_blocked",
            "error": "HTTP 400",
            "error_code": "BLOCKED_BY_GUARDRAILS",
        }

    monkeypatch.setattr(deepseek_baseline, "invoke_real_agent", fake_invoke)

    result = await _run_one(
        deepseek_baseline._with_baseline_contract(BOUNDARY_CASES[0]),
        concurrency=1,
        iteration=1,
        semaphore=asyncio.Semaphore(1),
        agent_url="http://agent.test",
    )

    assert result.success is True
    assert result.task_completed is True
    assert result.permission_accuracy == 1.0
    assert result.failure_category is None

    report = summarize([result])
    assert report["groups"]["1"]["refusal_accuracy"] == 1.0

    _, markdown_path = write_outputs(report, tmp_path, "contract")
    markdown = markdown_path.read_text(encoding="utf-8")
    assert "## Per-case result matrix" in markdown
    assert "| 46 | 1 | refusal | guardrails_blocked | PASS |" in markdown


@pytest.mark.asyncio
async def test_run_group_uses_distinct_eval_users_to_avoid_shared_rate_limit(monkeypatch):
    seen_user_ids: list[int | None] = []

    async def fake_invoke(**kwargs):
        seen_user_ids.append(kwargs.get("user_id"))
        return _success_response()

    monkeypatch.setattr(deepseek_baseline, "invoke_real_agent", fake_invoke)

    await run_group(QUERY_CASES[:2], concurrency=2, repeat=2, agent_url="http://agent.test")

    assert len(seen_user_ids) == 4
    assert None not in seen_user_ids
    assert len(set(seen_user_ids)) == 4
