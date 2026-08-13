#!/usr/bin/env python3
"""Run a small, sanitized Agent stage-latency profile against Docker Lite."""
from __future__ import annotations

import argparse
import json
import re
import subprocess
import time
import urllib.request
import uuid
from datetime import datetime, timezone
from pathlib import Path
from typing import Any


NON_OVERLAPPING_STAGE_KEYS = (
    "session_ms",
    "router_ms",
    "list_tools_ms",
    "llm_ms",
    "tool_ms",
    "rag_ms",
    "hitl_ms",
    "graph_overhead_ms",
    "other_ms",
)
FORBIDDEN_ARTIFACT_KEYS = {
    "prompt", "request", "request_text", "message", "content", "answer",
    "response", "response_text", "tool_args", "tool_result", "payload",
    "api_key", "authorization", "secret",
}
CREDENTIAL_PATTERN = re.compile(
    r"(?i)(?:sk-[a-z0-9_-]{16,}|bearer\s+[a-z0-9._-]{12,}|api[_-]?key\s*[:=])"
)
SAFE_TOOL_NAME = re.compile(r"^[a-z][a-z0-9_]{0,63}$")


def _json_from_line(line: str) -> dict[str, Any] | None:
    start = line.find("{")
    if start < 0:
        return None
    try:
        value = json.loads(line[start:])
    except json.JSONDecodeError:
        return None
    return value if isinstance(value, dict) else None


def parse_trace_events(raw_logs: str, trace_id: str) -> list[dict[str, Any]]:
    return [
        event
        for line in raw_logs.splitlines()
        if (event := _json_from_line(line)) is not None
        and str(event.get("trace_id")) == trace_id
    ]


def _duration(events: list[dict[str, Any]], *names: str) -> int:
    return sum(
        int(event.get("duration_ms", 0))
        for event in events
        if event.get("event") == "genai_span_end"
        and event.get("span_name") in names
        and isinstance(event.get("duration_ms"), (int, float))
    )


def summarize_stages(events: list[dict[str, Any]]) -> dict[str, int]:
    total = _duration(events, "request.total")
    graph = _duration(events, "graph.total")
    session = _duration(events, "session.prepare")
    router = _duration(events, "router.classify")
    list_tools = _duration(events, "mcp.list_tools")
    llm = _duration(events, "llm.invoke")
    hitl = _duration(events, "hitl.prepare")

    tool_spans = [
        event for event in events
        if event.get("event") == "genai_span_end"
        and str(event.get("span_name", "")).startswith("tool.")
    ]
    rag = sum(
        int(event.get("duration_ms", 0))
        for event in tool_spans
        if event.get("span_name") == "tool.knowledge_search"
    )
    tool = sum(
        int(event.get("duration_ms", 0))
        for event in tool_spans
        if event.get("span_name") != "tool.knowledge_search"
    )
    if not tool_spans:
        # Fast Path calls MCP directly, so there is no enclosing tool span.
        tool = sum(
            int(event.get("duration_ms", 0))
            for event in events
            if event.get("event") == "genai_span_end"
            and event.get("span_name") == "mcp.rpc"
            and event.get("rpc_method") == "tools/call"
        )
    graph_children = llm + tool + rag + list_tools + hitl
    graph_overhead = max(0, graph - graph_children)
    other = max(0, total - session - router - graph)
    return {
        "total_ms": total,
        "session_ms": session,
        "router_ms": router,
        "list_tools_ms": list_tools,
        "llm_ms": llm,
        "tool_ms": tool,
        "rag_ms": rag,
        "hitl_ms": hitl,
        "graph_overhead_ms": graph_overhead,
        "other_ms": other,
    }


def summarize_usage(events: list[dict[str, Any]]) -> dict[str, int | None]:
    calls = [event for event in events if event.get("event") == "llm_call_measured"]
    missing = sum(event.get("usage_status") != "reported" for event in calls)
    reliable = [event for event in calls if event.get("usage_status") == "reported"]
    if missing:
        input_tokens = output_tokens = total_tokens = None
    else:
        input_tokens = sum(int(event["input_tokens"]) for event in reliable)
        output_tokens = sum(int(event["output_tokens"]) for event in reliable)
        total_tokens = input_tokens + output_tokens
    return {
        "llm_calls": len(calls),
        "input_tokens": input_tokens,
        "output_tokens": output_tokens,
        "total_tokens": total_tokens,
        "usage_missing_calls": missing,
    }


def percentage(value: int, total: int) -> float:
    return round(value * 100 / total, 1) if total > 0 else 0.0


def validate_artifact(value: Any, path: str = "root") -> None:
    if isinstance(value, dict):
        for key, item in value.items():
            if str(key).lower() in FORBIDDEN_ARTIFACT_KEYS:
                raise ValueError(f"forbidden artifact key: {path}.{key}")
            validate_artifact(item, f"{path}.{key}")
    elif isinstance(value, list):
        for index, item in enumerate(value):
            validate_artifact(item, f"{path}[{index}]")
    elif isinstance(value, str) and CREDENTIAL_PATTERN.search(value):
        raise ValueError(f"credential-like content: {path}")


def _post_sse(url: str, scenario: dict[str, Any], trace_id: str) -> tuple[str, list[str]]:
    body = json.dumps({"message": scenario["text"]}).encode("utf-8")
    headers = {
        "Content-Type": "application/json",
        "Accept": "text/event-stream",
        "X-Trace-Id": trace_id,
        "X-User-Id": str(scenario["user_id"]),
        "X-User-Role": scenario["role"],
    }
    if scenario.get("merchant_id") is not None:
        headers["X-Merchant-Id"] = str(scenario["merchant_id"])
    request = urllib.request.Request(url, data=body, headers=headers, method="POST")
    terminal = "stream_closed"
    safe_tools: list[str] = []
    with urllib.request.urlopen(request, timeout=120) as response:
        event_name = None
        for raw in response:
            line = raw.decode("utf-8", "replace").strip()
            if line.startswith("event:"):
                event_name = line.split(":", 1)[1].strip()
            elif line.startswith("data:") and event_name:
                data = json.loads(line.split(":", 1)[1].strip())
                if event_name == "tool_call" and SAFE_TOOL_NAME.fullmatch(str(data.get("tool", ""))):
                    safe_tools.append(data["tool"])
                if event_name in {"final_answer", "hitl_request", "error"}:
                    terminal = str(data.get("stop_reason") or event_name)
    return terminal, safe_tools


def _docker_logs(container: str, since: str) -> str:
    result = subprocess.run(
        ["docker", "logs", "--since", since, container],
        check=True,
        capture_output=True,
        text=True,
    )
    return result.stdout + result.stderr


def _scenarios(args: argparse.Namespace) -> list[dict[str, Any]]:
    merchant = {"role": "merchant", "user_id": args.merchant_user, "merchant_id": args.merchant_id}
    admin = {"role": "admin", "user_id": args.admin_user, "merchant_id": None}
    cs = {"role": "cs", "user_id": args.cs_user, "merchant_id": None}
    return [
        {"name": "metrics_today", "text": "今天的GMV和订单数是多少？", **merchant},
        {"name": "metrics_month", "text": "本月GMV和订单数是多少？", **merchant},
        {"name": "order_lookup", "text": f"查询订单 {args.paid_order} 的状态", **cs},
        {"name": "order_not_found", "text": f"查询订单 {args.missing_order} 的状态", **cs},
        {"name": "payment_diagnosis", "text": f"订单 {args.payment_order} 显示已支付但状态异常，查明原因", **admin},
        {"name": "coupon_diagnosis", "text": f"订单 {args.coupon_order} 支付成功但没有发券，查一下", **admin},
        {"name": "coupon_root_cause", "text": f"订单 {args.coupon_order} 支付成功但没发券，请查根因", **admin},
        {"name": "rag_knowledge", "text": "平台优惠券叠加和过期规则是什么？请引用规则依据", **merchant},
        {"name": "campaign_draft", "text": "为门店设计一个周末优惠券活动草案，不要实际发布", **merchant},
        {"name": "refund_pending", "text": f"给订单 {args.coupon_order} 退款20元", **admin, "expected_pending": True},
        {"name": "compensation_pending", "text": f"给订单 {args.coupon_order} 补发20元优惠券", **admin, "expected_pending": True},
    ]


def _row(scenario: dict[str, Any], events: list[dict[str, Any]], terminal: str, tools: list[str]) -> dict[str, Any]:
    run = next((event for event in reversed(events) if event.get("event") == "agent_run_measured"), {})
    stages = summarize_stages(events)
    expected_pending = bool(scenario.get("expected_pending"))
    success = terminal == "hitl_request" if expected_pending else terminal not in {"error", "stream_closed"}
    return {
        "name": scenario["name"],
        "role": scenario["role"],
        "route_type": run.get("route_task_type", "unknown"),
        "route_mode": run.get("route_mode", "unknown"),
        "stop_reason": run.get("stop_reason", terminal),
        "success": success,
        "tools": tools,
        "stages": stages,
        "stage_shares": {key: percentage(stages[key], stages["total_ms"]) for key in NON_OVERLAPPING_STAGE_KEYS},
        "usage": summarize_usage(events),
    }


def _markdown(artifact: dict[str, Any]) -> str:
    lines = [
        "# Agent Stage Latency Profile",
        "",
        f"- Timestamp: `{artifact['metadata']['timestamp']}`",
        f"- Model: `{artifact['metadata']['model']}`",
        "- Concurrency: `1`; one observation per scenario; no quality claim.",
        "",
        "| Scenario | Route | Stop | Total ms | LLM ms | Tool ms | RAG ms | Calls | Tokens |",
        "| --- | --- | --- | ---: | ---: | ---: | ---: | ---: | ---: |",
    ]
    for row in artifact["scenarios"]:
        tokens = row["usage"]["total_tokens"]
        lines.append(
            f"| {row['name']} | {row['route_mode']} | {row['stop_reason']} | "
            f"{row['stages']['total_ms']} | {row['stages']['llm_ms']} | "
            f"{row['stages']['tool_ms']} | {row['stages']['rag_ms']} | "
            f"{row['usage']['llm_calls']} | {tokens if tokens is not None else 'unknown'} |"
        )
    return "\n".join(lines) + "\n"


def main() -> int:
    parser = argparse.ArgumentParser()
    parser.add_argument("--url", default="http://localhost:8000/chat")
    parser.add_argument("--container", default="local-life-copilot-agent")
    parser.add_argument("--output", required=True)
    parser.add_argument("--model", default="deepseek-chat")
    parser.add_argument("--merchant-user", required=True)
    parser.add_argument("--merchant-id", required=True)
    parser.add_argument("--cs-user", required=True)
    parser.add_argument("--admin-user", required=True)
    parser.add_argument("--paid-order", required=True)
    parser.add_argument("--payment-order", required=True)
    parser.add_argument("--coupon-order", required=True)
    parser.add_argument("--missing-order", required=True)
    args = parser.parse_args()

    rows = []
    for scenario in _scenarios(args):
        trace_id = f"latency-{uuid.uuid4().hex}"
        since = datetime.now(timezone.utc).isoformat()
        terminal, tools = _post_sse(args.url, scenario, trace_id)
        time.sleep(0.2)
        events = parse_trace_events(_docker_logs(args.container, since), trace_id)
        rows.append(_row(scenario, events, terminal, tools))

    artifact = {
        "metadata": {
            "timestamp": datetime.now(timezone.utc).isoformat(),
            "model": args.model,
            "concurrency": 1,
            "observations_per_scenario": 1,
            "scenario_count": len(rows),
        },
        "scenarios": rows,
    }
    validate_artifact(artifact)
    output = Path(args.output)
    output.mkdir(parents=True, exist_ok=False)
    (output / "profile.json").write_text(json.dumps(artifact, indent=2) + "\n", encoding="utf-8")
    (output / "profile.md").write_text(_markdown(artifact), encoding="utf-8")
    print(json.dumps({"status": "PASS", "scenario_count": len(rows), "output": str(output)}))
    return 0


if __name__ == "__main__":
    raise SystemExit(main())
