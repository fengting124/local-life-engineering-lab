"""Unit tests for deterministic, role-filtered tool route decisions."""
import pytest

from agent import tool_router
from agent.tool_router import (
    TOOL_ROLE_MAP,
    RouteDecision,
    ToolRouter,
    classify_request,
    is_tool_concurrency_safe,
)


def _tools(*names: str) -> list[dict]:
    return [{"name": name, "description": f"desc_{name}"} for name in names]


ALL_TOOLS = list(TOOL_ROLE_MAP)


class TestConcurrencySafety:
    @pytest.mark.parametrize(
        "tool_name",
        [
            "query_order",
            "query_payment",
            "shop_metrics_query",
            "knowledge_search",
        ],
    )
    def test_read_only_tool_is_safe(self, tool_name):
        assert is_tool_concurrency_safe(tool_name) is True

    @pytest.mark.parametrize(
        "tool_name",
        [
            "execute_refund",
            "issue_compensation_coupon",
            "campaign_draft_generate",
            "brand_new_unknown_tool",
        ],
    )
    def test_write_or_unknown_tool_is_not_safe(self, tool_name):
        assert is_tool_concurrency_safe(tool_name) is False


class TestRolePermissions:
    @pytest.mark.parametrize(
        ("tool_name", "role", "expected"),
        [
            ("knowledge_search", "merchant", True),
            ("knowledge_search", "admin", True),
            ("knowledge_search", "cs", False),
            ("brand_new_unknown_tool", "admin", False),
        ],
    )
    def test_shared_role_check_is_fail_closed(self, tool_name, role, expected):
        assert tool_router.is_tool_allowed_for_role(tool_name, role) is expected

    def test_router_and_execution_check_share_the_same_role_map(self, monkeypatch):
        monkeypatch.setitem(TOOL_ROLE_MAP, "knowledge_search", ["cs"])

        result = ToolRouter("cs", "退款规则是什么？").route(
            _tools("knowledge_search")
        )

        assert [item["name"] for item in result] == ["knowledge_search"]
        assert tool_router.is_tool_allowed_for_role("knowledge_search", "cs") is True

    def test_unknown_role_sees_no_controlled_tool(self):
        result = ToolRouter("attacker", "查询订单 202606100001").route(
            _tools(*ALL_TOOLS)
        )

        assert result == []


@pytest.mark.parametrize(
    ("message", "role", "task_type", "route_mode", "next_tool"),
    [
        ("今天有多少笔订单？", "merchant", "analytics", "controlled", "shop_metrics_query"),
        ("退款规则是什么？", "merchant", "knowledge", "controlled", "knowledge_search"),
        ("给订单 202606100001 退款", "cs", "refund_action", "controlled", "query_order"),
        ("订单 202606100001 支付失败是什么原因？", "admin", "payment_diagnosis", "controlled", "query_order"),
        ("订单 202606100001 没收到券，查根因", "admin", "coupon_root_cause", "controlled", "query_order"),
        ("按照平台规则创建优惠券活动", "merchant", "campaign_draft", "controlled", "coupon_policy_lookup"),
        ("帮我查一下", "cs", "unknown", "clarification", None),
        ("这个月我总共卖了多少钱？", "merchant", "analytics", "clarification", None),
        ("哈哈哈这个活动好玩！", "merchant", "small_talk", "controlled", None),
    ],
)
def test_classify_request(message, role, task_type, route_mode, next_tool):
    decision = classify_request(role, message)

    assert decision.task_type == task_type
    assert decision.route_mode == route_mode
    assert decision.next_tool == next_tool


def test_complete_payment_and_coupon_mix_uses_bounded_fallback():
    decision = classify_request(
        "admin",
        "分析订单 202606100001 的支付和优惠券异常",
    )

    assert decision.route_mode == "general_fallback"
    assert "execute_refund" not in decision.authorized_tools


def test_coupon_root_cause_beats_coupon_issue_parent_route():
    decision = classify_request(
        "admin",
        "订单 202606100001 支付成功但没发券，查一下根因",
    )

    assert decision.task_type == "coupon_root_cause"


@pytest.mark.parametrize(
    ("message", "task_type", "missing_field"),
    [
        ("查订单状态", "order_query", "order_id"),
        ("今天的数据怎么样？", "analytics", "metric"),
        ("订单量是多少？", "analytics", "date"),
        ("这个月订单量是多少？", "analytics", "supported_date"),
        ("帮客户退款", "refund_action", "order_id"),
    ],
)
def test_missing_route_anchor_requires_clarification(message, task_type, missing_field):
    decision = classify_request("cs", message)

    assert decision.task_type == task_type
    assert decision.route_mode == "clarification"
    assert decision.missing_fields == (missing_field,)


def test_complete_campaign_constraints_skip_policy_lookup():
    decision = classify_request(
        "merchant",
        "创建优惠券活动，满100减20，有效期7天，每人限购1张",
    )

    assert decision.task_type == "campaign_draft"
    assert decision.required_tools == ("campaign_draft_generate",)
    assert decision.next_tool == "campaign_draft_generate"


def test_conversation_context_is_accepted_but_does_not_classify_request():
    router = ToolRouter(
        "cs",
        "帮我查一下",
        conversation_context="订单 202606100001 支付成功，可以退款",
    )

    assert router.decision.task_type == "unknown"
    assert router.route(_tools(*ALL_TOOLS)) == []


def test_cs_knowledge_route_executes_zero_tools():
    decision = classify_request("cs", "退款规则是什么？")

    assert decision.required_tools == ("knowledge_search",)
    assert decision.authorized_tools == ()
    assert decision.next_tool is None


def test_route_state_round_trip_is_checkpoint_safe():
    original = classify_request(
        "admin",
        "订单 202606100001 支付失败是什么原因？",
    )

    restored = RouteDecision.from_state(original.to_state())

    assert restored == original


def test_controlled_router_exposes_exactly_one_tool():
    decision = classify_request(
        "admin",
        "订单 202606100001 支付失败是什么原因？",
    )
    router = ToolRouter.from_state({"user_role": "admin", **decision.to_state()})

    assert [tool["name"] for tool in router.route(_tools(*ALL_TOOLS))] == [
        "query_order"
    ]


def test_general_fallback_exposes_only_role_allowed_read_only_tools():
    decision = classify_request(
        "admin",
        "分析订单 202606100001 的支付和优惠券异常",
    )
    router = ToolRouter.from_state({"user_role": "admin", **decision.to_state()})

    assert [tool["name"] for tool in router.route(_tools(*ALL_TOOLS))] == [
        "query_order",
        "shop_metrics_query",
        "knowledge_search",
        "coupon_policy_lookup",
    ]
