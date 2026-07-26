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


@pytest.mark.parametrize(
    ("role", "message", "task_type", "next_tool"),
    [
        ("merchant", "订单 202606100001 退款规则是什么？", "knowledge", "knowledge_search"),
        ("cs", "订单 202606100001 的退款情况怎么样？", "order_query", "query_order"),
        ("cs", "给订单 202606100001 执行退款", "refund_action", "query_order"),
        ("merchant", "订单 202606100001 的补券规则是什么？", "knowledge", "knowledge_search"),
        ("cs", "订单 202606100001 的补券情况怎么样？", "order_query", "query_order"),
        ("cs", "给订单 202606100001 执行补券", "compensation_action", "query_order"),
    ],
)
def test_high_risk_actions_require_explicit_execution_intent(
    role, message, task_type, next_tool
):
    decision = classify_request(role, message)

    assert decision.task_type == task_type
    assert decision.next_tool == next_tool


@pytest.mark.parametrize(
    ("role", "message", "task_type", "next_tool"),
    [
        (
            "merchant",
            "给我查订单 202606100001 的退款规则",
            "knowledge",
            "knowledge_search",
        ),
        (
            "merchant",
            "退款规则是什么？最长多少天可以申请退款？",
            "knowledge",
            "knowledge_search",
        ),
        (
            "merchant",
            "给我查订单 202606100001 的补券规则",
            "knowledge",
            "knowledge_search",
        ),
        ("cs", "给订单 202606100001 退款", "refund_action", "query_order"),
        ("cs", "给订单 202606100001 补券", "compensation_action", "query_order"),
    ],
)
def test_policy_semantics_override_generic_high_risk_wording(
    role, message, task_type, next_tool
):
    decision = classify_request(role, message)

    assert decision.task_type == task_type
    assert decision.next_tool == next_tool


@pytest.mark.parametrize(
    ("message", "task_type"),
    [
        ("按照退款规则给订单 202606100001 执行退款", "refund_action"),
        ("按照补券规则给订单 202606100001 执行补券", "compensation_action"),
    ],
)
def test_strong_high_risk_execution_overrides_policy_semantics(message, task_type):
    decision = classify_request("admin", message)

    assert decision.task_type == task_type
    assert decision.next_tool == "query_order"


@pytest.mark.parametrize("action", ["退款", "补券"])
def test_strong_execution_phrase_can_be_the_subject_of_knowledge_query(action):
    decision = classify_request(
        "admin",
        f"查询订单 202606100001 执行{action}的规则",
    )

    assert decision.task_type == "knowledge"
    assert decision.next_tool == "knowledge_search"


@pytest.mark.parametrize("action", ["退款", "补券"])
@pytest.mark.parametrize(
    "message_template",
    [
        "订单 202606100001 如何执行{action}？",
        "订单 202606100001 怎么执行{action}？",
        "订单 202606100001 是否执行{action}？",
        "订单 202606100001 能否执行{action}？",
        "订单 202606100001 可以执行{action}吗？",
    ],
)
def test_execution_interrogatives_do_not_unlock_high_risk_tools(
    action, message_template
):
    decision = classify_request(
        "admin",
        message_template.format(action=action),
    )

    assert decision.task_type not in {"refund_action", "compensation_action"}
    assert "execute_refund" not in decision.authorized_tools
    assert "issue_compensation_coupon" not in decision.authorized_tools


@pytest.mark.parametrize(
    ("action", "task_type"),
    [
        ("退款", "refund_action"),
        ("补券", "compensation_action"),
    ],
)
def test_sequential_query_then_high_risk_execution_remains_action(action, task_type):
    decision = classify_request(
        "admin",
        f"查询完订单 202606100001 后执行{action}",
    )

    assert decision.task_type == task_type
    assert decision.next_tool == "query_order"


@pytest.mark.parametrize(
    "message",
    [
        "帮我查订单 202606100001 的退款进度",
        "帮我查订单 202606100001 的补券进度",
    ],
)
def test_high_risk_progress_queries_remain_read_only(message):
    decision = classify_request("admin", message)

    assert decision.task_type == "order_query"
    assert decision.required_tools == ("query_order",)
    assert "execute_refund" not in decision.authorized_tools
    assert "issue_compensation_coupon" not in decision.authorized_tools


def test_campaign_labels_without_values_keep_policy_lookup():
    decision = classify_request(
        "merchant",
        "创建优惠券活动，满减，有效期，每人限购",
    )

    assert decision.task_type == "campaign_draft"
    assert decision.required_tools == (
        "coupon_policy_lookup",
        "campaign_draft_generate",
    )


def test_direct_coupon_configuration_is_policy_configuration():
    decision = classify_request("merchant", "配置优惠券门槛和限购")

    assert decision.task_type == "policy_configuration"
    assert decision.next_tool == "knowledge_search"


@pytest.mark.parametrize(
    ("message", "task_type"),
    [
        ("订单 202606100001 支付了但没收到券", "coupon_issue"),
        ("订单 202606100001 支付了但没收到券，查根因", "coupon_root_cause"),
    ],
)
def test_coupon_delivery_diagnosis_ignores_payment_success_context(message, task_type):
    decision = classify_request("admin", message)

    assert decision.task_type == task_type
    assert decision.next_tool == "query_order"


@pytest.mark.parametrize(
    ("role", "message", "task_type", "next_tool"),
    [
        ("admin", "帮我查一下 202606100001 的支付情况", "payment_diagnosis", "query_order"),
        (
            "admin",
            "202606100001 显示已支付但状态还是待支付",
            "payment_diagnosis",
            "query_order",
        ),
        (
            "admin",
            "排查订单 202606100001 的 MQ 死信失败原因",
            "mq_diagnosis",
            "query_order",
        ),
        ("merchant", "昨天的优惠券核销了多少张？", "analytics", "shop_metrics_query"),
        ("merchant", "发布活动需要提前几天申请？", "knowledge", "knowledge_search"),
    ],
)
def test_supported_query_equivalence_classes(role, message, task_type, next_tool):
    decision = classify_request(role, message)

    assert decision.task_type == task_type
    assert decision.next_tool == next_tool


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


def test_invalid_checkpoint_route_mode_exposes_zero_tools():
    router = ToolRouter.from_state(
        {
            "user_role": "admin",
            "route_task_type": "unknown",
            "route_mode": "malicious_mode",
            "route_confidence": 100,
        }
    )

    assert router.decision.route_mode == "clarification"
    assert router.route(_tools(*ALL_TOOLS)) == []


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
