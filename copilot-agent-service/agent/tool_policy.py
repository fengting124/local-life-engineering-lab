"""Deterministic authorization and call-budget decisions for Agent tools."""
from collections import Counter
from dataclasses import dataclass
import hashlib
import json

from agent.tool_router import is_tool_allowed_for_role


@dataclass(frozen=True)
class ToolPolicyDecision:
    allowed: bool
    tool_call_count: int
    tool_call_counts: dict[str, int]
    tool_signature_counts: dict[str, int]
    reason: str | None = None
    tool: str | None = None


def first_denied_tool(tool_calls: list[dict], user_role: str) -> str | None:
    """Return the first unauthorized or unknown tool in call order."""
    return next(
        (
            call["name"]
            for call in tool_calls
            if not is_tool_allowed_for_role(call["name"], user_role)
        ),
        None,
    )


def canonicalize_tool_args(args: dict) -> str:
    """Canonical JSON used only as input to a one-way signature."""
    return json.dumps(
        args,
        sort_keys=True,
        separators=(",", ":"),
        ensure_ascii=False,
    )


def tool_call_signature(tool_name: str, args: dict) -> str:
    payload = f"{tool_name}:{canonicalize_tool_args(args)}".encode("utf-8")
    return hashlib.sha256(payload).hexdigest()


def evaluate_tool_batch(
    tool_calls: list[dict],
    *,
    tool_call_count: int,
    tool_call_counts: dict[str, int],
    tool_signature_counts: dict[str, int],
    max_per_turn: int,
    max_total: int,
    max_per_tool: int,
    max_identical: int,
) -> ToolPolicyDecision:
    """Preflight a full LLM tool-call batch without partially consuming it."""
    current_tool_counts = dict(tool_call_counts or {})
    current_signature_counts = dict(tool_signature_counts or {})

    def reject(reason: str, tool: str | None) -> ToolPolicyDecision:
        return ToolPolicyDecision(
            allowed=False,
            reason=reason,
            tool=tool,
            tool_call_count=tool_call_count,
            tool_call_counts=current_tool_counts,
            tool_signature_counts=current_signature_counts,
        )

    if len(tool_calls) > max_per_turn:
        return reject("per_turn_limit", tool_calls[max_per_turn]["name"])

    if tool_call_count + len(tool_calls) > max_total:
        return reject("total_limit", tool_calls[0]["name"] if tool_calls else None)

    batch_tool_counts: Counter[str] = Counter()
    for call in tool_calls:
        tool_name = call["name"]
        batch_tool_counts[tool_name] += 1
        if (
            current_tool_counts.get(tool_name, 0)
            + batch_tool_counts[tool_name]
            > max_per_tool
        ):
            return reject("per_tool_limit", tool_name)

    batch_signature_counts: Counter[str] = Counter()
    for call in tool_calls:
        tool_name = call["name"]
        signature = tool_call_signature(tool_name, call.get("args", {}))
        batch_signature_counts[signature] += 1
        if (
            current_signature_counts.get(signature, 0)
            + batch_signature_counts[signature]
            > max_identical
        ):
            return reject("identical_call_limit", tool_name)

    updated_tool_counts = dict(current_tool_counts)
    for tool_name, count in batch_tool_counts.items():
        updated_tool_counts[tool_name] = updated_tool_counts.get(tool_name, 0) + count

    updated_signature_counts = dict(current_signature_counts)
    for signature, count in batch_signature_counts.items():
        updated_signature_counts[signature] = (
            updated_signature_counts.get(signature, 0) + count
        )

    return ToolPolicyDecision(
        allowed=True,
        tool_call_count=tool_call_count + len(tool_calls),
        tool_call_counts=updated_tool_counts,
        tool_signature_counts=updated_signature_counts,
    )
