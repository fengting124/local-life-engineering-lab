import pytest
from pydantic import ValidationError

from agent.tool_policy import (
    canonicalize_tool_args,
    evaluate_tool_batch,
    tool_call_signature,
)
from config.settings import Settings


def tool_call(name: str, args: dict, call_id: str = "c1") -> dict:
    return {"name": name, "args": args, "id": call_id}


def evaluate(calls: list[dict], **overrides):
    kwargs = {
        "tool_call_count": 0,
        "tool_call_counts": {},
        "tool_signature_counts": {},
        "max_per_turn": 4,
        "max_total": 8,
        "max_per_tool": 3,
        "max_identical": 2,
    }
    kwargs.update(overrides)
    return evaluate_tool_batch(calls, **kwargs)


class TestToolSignatures:
    def test_argument_key_order_is_canonical(self):
        first = {"order_no": "O-1", "filters": {"status": "PAID", "page": 1}}
        reordered = {"filters": {"page": 1, "status": "PAID"}, "order_no": "O-1"}

        assert canonicalize_tool_args(first) == canonicalize_tool_args(reordered)
        assert tool_call_signature("query_order", first) == tool_call_signature(
            "query_order", reordered
        )

    def test_real_argument_change_has_different_signature(self):
        assert tool_call_signature(
            "query_order", {"order_no": "O-1"}
        ) != tool_call_signature("query_order", {"order_no": "O-2"})


class TestToolBudget:
    def test_valid_three_call_batch_updates_all_counters(self):
        calls = [
            tool_call("query_order", {"order_no": f"O-{i}"}, f"c{i}")
            for i in range(3)
        ]

        decision = evaluate(calls)

        assert decision.allowed is True
        assert decision.tool_call_count == 3
        assert decision.tool_call_counts == {"query_order": 3}
        assert len(decision.tool_signature_counts) == 3

    def test_five_calls_reject_entire_turn_without_counting(self):
        calls = [
            tool_call("query_order", {"order_no": f"O-{i}"}, f"c{i}")
            for i in range(5)
        ]

        decision = evaluate(calls)

        assert decision.allowed is False
        assert decision.reason == "per_turn_limit"
        assert decision.tool_call_count == 0
        assert decision.tool_call_counts == {}
        assert decision.tool_signature_counts == {}

    def test_total_limit_rejects_next_call(self):
        decision = evaluate(
            [tool_call("query_order", {"order_no": "O-9"})],
            tool_call_count=8,
            tool_call_counts={"query_order": 2},
        )

        assert decision.allowed is False
        assert decision.reason == "total_limit"
        assert decision.tool_call_count == 8

    def test_different_arguments_still_obey_per_tool_limit(self):
        decision = evaluate(
            [
                tool_call("query_order", {"order_no": "O-3"}, "c3"),
                tool_call("query_order", {"order_no": "O-4"}, "c4"),
            ],
            tool_call_count=2,
            tool_call_counts={"query_order": 2},
        )

        assert decision.allowed is False
        assert decision.reason == "per_tool_limit"
        assert decision.tool == "query_order"

    def test_third_identical_call_is_rejected(self):
        args = {"order_no": "O-1"}
        signature = tool_call_signature("query_order", args)

        decision = evaluate(
            [tool_call("query_order", args)],
            tool_call_count=2,
            tool_call_counts={"query_order": 2},
            tool_signature_counts={signature: 2},
        )

        assert decision.allowed is False
        assert decision.reason == "identical_call_limit"
        assert decision.tool == "query_order"

    def test_same_ai_message_detects_identical_repetition(self):
        calls = [
            tool_call("query_order", {"order_no": "O-1"}, f"c{i}")
            for i in range(3)
        ]

        decision = evaluate(calls)

        assert decision.allowed is False
        assert decision.reason == "identical_call_limit"


@pytest.mark.parametrize(
    "field",
    [
        "agent_max_tool_calls_per_turn",
        "agent_max_tool_calls_total",
        "agent_max_calls_per_tool",
        "agent_max_identical_tool_calls",
    ],
)
def test_tool_budget_settings_must_be_positive(field):
    with pytest.raises(ValidationError):
        Settings(_env_file=None, **{field: 0})
