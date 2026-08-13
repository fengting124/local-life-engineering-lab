import importlib.util
import json
from pathlib import Path

import pytest


SCRIPT = Path(__file__).parents[2] / "scripts" / "profile-agent-latency.py"


def _load():
    spec = importlib.util.spec_from_file_location("stage_profile", SCRIPT)
    module = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(module)
    return module


def test_parse_trace_keeps_only_matching_structured_events():
    profiler = _load()
    lines = "\n".join([
        json.dumps({"event": "genai_span_end", "trace_id": "wanted", "span_name": "request.total", "duration_ms": 100}),
        json.dumps({"event": "genai_span_end", "trace_id": "other", "span_name": "request.total", "duration_ms": 999}),
        "plain non-json line",
    ])

    events = profiler.parse_trace_events(lines, "wanted")

    assert len(events) == 1
    assert events[0]["duration_ms"] == 100


def test_stage_math_does_not_double_count_nested_mcp():
    profiler = _load()
    events = [
        {"event": "genai_span_end", "span_name": "request.total", "duration_ms": 1000},
        {"event": "genai_span_end", "span_name": "session.prepare", "duration_ms": 50},
        {"event": "genai_span_end", "span_name": "router.classify", "duration_ms": 10},
        {"event": "genai_span_end", "span_name": "graph.total", "duration_ms": 900},
        {"event": "genai_span_end", "span_name": "llm.invoke", "duration_ms": 500},
        {"event": "genai_span_end", "span_name": "tool.query_order", "duration_ms": 200},
        {"event": "genai_span_end", "span_name": "mcp.rpc", "duration_ms": 190},
    ]

    stages = profiler.summarize_stages(events)

    assert stages["tool_ms"] == 200
    assert stages["graph_overhead_ms"] == 200
    assert sum(stages[key] for key in profiler.NON_OVERLAPPING_STAGE_KEYS) == 1000
    assert stages["other_ms"] == 40


def test_fast_path_direct_mcp_is_not_also_counted_as_other():
    profiler = _load()
    stages = profiler.summarize_stages([
        {"event": "genai_span_end", "span_name": "request.total", "duration_ms": 100},
        {"event": "genai_span_end", "span_name": "session.prepare", "duration_ms": 20},
        {"event": "genai_span_end", "span_name": "router.classify", "duration_ms": 1},
        {"event": "genai_span_end", "span_name": "mcp.rpc", "rpc_method": "tools/call", "duration_ms": 60},
    ])

    assert stages["tool_ms"] == 60
    assert stages["other_ms"] == 19
    assert sum(stages[key] for key in profiler.NON_OVERLAPPING_STAGE_KEYS) == 100


def test_missing_usage_remains_null():
    profiler = _load()
    usage = profiler.summarize_usage([
        {"event": "llm_call_measured", "usage_status": "missing", "input_tokens": None, "output_tokens": None}
    ])

    assert usage == {
        "llm_calls": 1,
        "input_tokens": None,
        "output_tokens": None,
        "total_tokens": None,
        "usage_missing_calls": 1,
    }


def test_artifact_validator_rejects_payload_and_credentials():
    profiler = _load()
    valid = {
        "metadata": {"model": "deepseek-chat", "timestamp": "2026-08-13T00:00:00Z"},
        "scenarios": [{"name": "order_lookup", "role": "cs", "success": True}],
    }
    profiler.validate_artifact(valid)

    with pytest.raises(ValueError, match="forbidden artifact key"):
        profiler.validate_artifact({**valid, "prompt": "secret request"})
    with pytest.raises(ValueError, match="credential-like content"):
        profiler.validate_artifact({"metadata": {"model": "sk-12345678901234567890"}, "scenarios": []})


def test_percentage_math_handles_zero_total():
    profiler = _load()
    assert profiler.percentage(5, 0) == 0.0
    assert profiler.percentage(25, 100) == 25.0


def test_internal_error_is_not_a_successful_terminal():
    profiler = _load()
    assert profiler.terminal_succeeded("completed", expected_pending=False)
    assert profiler.terminal_succeeded("hitl_request", expected_pending=True)
    assert not profiler.terminal_succeeded("internal_error", expected_pending=False)


def test_tool_names_fall_back_to_sanitized_span_names():
    profiler = _load()
    assert profiler.summarize_tool_names([
        {"event": "genai_span_end", "span_name": "tool.query_order"},
        {"event": "genai_span_end", "span_name": "tool.query_order"},
        {"event": "genai_span_end", "span_name": "tool.BAD-NAME"},
    ], []) == ["query_order"]
