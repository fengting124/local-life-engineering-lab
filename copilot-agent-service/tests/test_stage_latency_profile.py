import importlib.util
import json
from pathlib import Path
from types import SimpleNamespace

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


def test_run_plan_filters_scenarios_and_repeats_in_stable_order():
    profiler = _load()
    scenarios = [
        {"name": "order_lookup"},
        {"name": "payment_diagnosis"},
        {"name": "coupon_diagnosis"},
    ]

    plan = profiler.build_run_plan(
        scenarios,
        ["order_lookup", "coupon_diagnosis"],
        repeat=3,
    )

    assert [(row["name"], row["observation"]) for row in plan] == [
        ("order_lookup", 1),
        ("order_lookup", 2),
        ("order_lookup", 3),
        ("coupon_diagnosis", 1),
        ("coupon_diagnosis", 2),
        ("coupon_diagnosis", 3),
    ]


def test_run_plan_defaults_to_all_once_and_rejects_unknown_names():
    profiler = _load()
    scenarios = [{"name": "order_lookup"}, {"name": "payment_diagnosis"}]

    assert profiler.build_run_plan(scenarios, [], repeat=1) == [
        {"name": "order_lookup", "observation": 1},
        {"name": "payment_diagnosis", "observation": 1},
    ]
    with pytest.raises(ValueError, match="unknown scenario"):
        profiler.build_run_plan(scenarios, ["not_real"], repeat=1)
    with pytest.raises(ValueError, match="repeat must be positive"):
        profiler.build_run_plan(scenarios, [], repeat=0)


def test_diagnostic_profile_messages_match_the_frozen_router_contract():
    from agent.tool_router import classify_request

    profiler = _load()
    args = SimpleNamespace(
        merchant_user="merchant",
        merchant_id="merchant-id",
        admin_user="admin",
        cs_user="cs",
        paid_order="202606100001",
        payment_order="202606100002",
        coupon_order="202606100003",
        missing_order="2026999999999999999",
    )
    scenarios = {scenario["name"]: scenario for scenario in profiler._scenarios(args)}

    assert classify_request("admin", scenarios["payment_diagnosis"]["text"]).task_type == "payment_diagnosis"
    assert classify_request("admin", scenarios["coupon_diagnosis"]["text"]).task_type == "coupon_issue"


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


def test_row_keeps_real_tool_call_count_from_run_summary():
    profiler = _load()
    row = profiler._row(
        {"name": "multi", "role": "admin"},
        [
            {"event": "agent_run_measured", "stop_reason": "completed", "tool_call_count": 3},
            {"event": "genai_span_end", "span_name": "request.total", "duration_ms": 1},
        ],
        "completed",
        ["query_order"],
    )

    assert row["tool_call_count"] == 3


def test_row_rejects_missing_correlated_measurements():
    profiler = _load()

    with pytest.raises(ValueError, match="missing agent_run_measured"):
        profiler._row(
            {"name": "missing", "role": "cs"},
            [],
            "completed",
            [],
        )


def test_row_rejects_duplicate_run_summaries():
    profiler = _load()
    events = [
        {"event": "agent_run_measured", "stop_reason": "completed"},
        {"event": "agent_run_measured", "stop_reason": "completed"},
        {"event": "genai_span_end", "span_name": "request.total", "duration_ms": 1},
    ]

    with pytest.raises(ValueError, match="expected exactly one agent_run_measured"):
        profiler._row(
            {"name": "duplicate", "role": "cs"},
            events,
            "completed",
            [],
        )


def test_row_rejects_missing_llm_measurement_and_span():
    profiler = _load()
    events = [
        {
            "event": "agent_run_measured",
            "stop_reason": "completed",
            "llm_call_count": 1,
            "llm_input_tokens": 2,
            "llm_output_tokens": 1,
            "llm_usage_missing_count": 0,
        },
        {"event": "genai_span_end", "span_name": "request.total", "duration_ms": 10},
    ]

    with pytest.raises(ValueError, match="LLM measurement mismatch"):
        profiler._row(
            {"name": "missing_llm", "role": "cs"},
            events,
            "completed",
            [],
        )


def test_row_accepts_matching_llm_summary_measurement_and_span():
    profiler = _load()
    events = [
        {
            "event": "agent_run_measured",
            "stop_reason": "completed",
            "llm_call_count": 1,
            "llm_input_tokens": 2,
            "llm_output_tokens": 1,
            "llm_usage_missing_count": 0,
        },
        {"event": "genai_span_end", "span_name": "request.total", "duration_ms": 10},
        {"event": "genai_span_end", "span_name": "llm.invoke", "duration_ms": 8},
        {"event": "llm_call_measured", "usage_status": "reported", "input_tokens": 2, "output_tokens": 1},
    ]

    row = profiler._row(
        {"name": "complete_llm", "role": "cs"},
        events,
        "completed",
        [],
    )

    assert row["usage"]["llm_calls"] == 1


def test_row_rejects_token_aggregation_mismatch():
    profiler = _load()
    events = [
        {
            "event": "agent_run_measured",
            "stop_reason": "completed",
            "llm_call_count": 1,
            "llm_input_tokens": 999,
            "llm_output_tokens": 999,
            "llm_usage_missing_count": 0,
        },
        {"event": "genai_span_end", "span_name": "request.total", "duration_ms": 10},
        {"event": "genai_span_end", "span_name": "llm.invoke", "duration_ms": 8},
        {"event": "llm_call_measured", "usage_status": "reported", "input_tokens": 2, "output_tokens": 1},
    ]

    with pytest.raises(ValueError, match="LLM token mismatch"):
        profiler._row({"name": "bad_tokens", "role": "cs"}, events, "completed", [])


def test_row_rejects_terminal_summary_mismatch():
    profiler = _load()
    events = [
        {"event": "agent_run_measured", "stop_reason": "completed"},
        {"event": "genai_span_end", "span_name": "request.total", "duration_ms": 10},
    ]

    with pytest.raises(ValueError, match="terminal mismatch"):
        profiler._row({"name": "bad_terminal", "role": "cs"}, events, "internal_error", [])
