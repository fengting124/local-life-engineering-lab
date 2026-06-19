"""
agent/tool_router.py 单元测试。

ToolRouter 和 is_tool_concurrency_safe 均为纯逻辑（无 I/O），直接实例化测试。

覆盖重点：
  - RBAC 过滤：角色权限边界（merchant 不能看到 cs/admin 专属工具）
  - 任务类型推断：关键词 → task_type 的各分支
  - 上下文过滤：高风险工具（退款/补券）在对话上下文不充分时应被隐藏
  - 并发安全分级：fail-closed 原则（未知工具默认不安全）
"""
import pytest
from agent.tool_router import ToolRouter, is_tool_concurrency_safe, TOOL_ROLE_MAP


def _tools(*names: str) -> list[dict]:
    """构造测试用工具 Schema（只需 name 字段）。"""
    return [{"name": n, "description": f"desc_{n}"} for n in names]


ALL_TOOLS = list(TOOL_ROLE_MAP.keys())


# =========================================================
# 并发安全分级
# =========================================================

class TestConcurrencySafe:
    def test_query_order_is_safe(self):
        assert is_tool_concurrency_safe("query_order") is True

    def test_query_payment_is_safe(self):
        assert is_tool_concurrency_safe("query_payment") is True

    def test_shop_metrics_query_is_safe(self):
        assert is_tool_concurrency_safe("shop_metrics_query") is True

    def test_knowledge_search_is_safe(self):
        assert is_tool_concurrency_safe("knowledge_search") is True

    def test_execute_refund_is_not_safe(self):
        # 资金类高风险写操作，不可并发
        assert is_tool_concurrency_safe("execute_refund") is False

    def test_issue_compensation_coupon_is_not_safe(self):
        assert is_tool_concurrency_safe("issue_compensation_coupon") is False

    def test_campaign_draft_generate_is_not_safe(self):
        # 有副作用的生成型操作
        assert is_tool_concurrency_safe("campaign_draft_generate") is False

    def test_unknown_tool_is_not_safe(self):
        # fail-closed：未登记的工具一律视为不安全
        assert is_tool_concurrency_safe("brand_new_unknown_tool") is False


# =========================================================
# RBAC 过滤
# =========================================================

class TestRbacFilter:
    def test_merchant_can_see_query_order(self):
        router = ToolRouter(user_role="merchant")
        result = router.route(_tools("query_order"))
        assert any(t["name"] == "query_order" for t in result)

    def test_merchant_cannot_see_query_payment(self):
        router = ToolRouter(user_role="merchant")
        result = router.route(_tools("query_payment", "query_order"))
        names = {t["name"] for t in result}
        assert "query_payment" not in names

    def test_merchant_cannot_see_execute_refund_via_rbac(self):
        router = ToolRouter(user_role="merchant", conversation_context="已付款 paid")
        result = router.route(_tools("execute_refund"))
        # 即使上下文有支付确认，merchant 角色也无权看退款工具
        assert len(result) == 0

    def test_cs_read_tools_only_include_query_order(self):
        router = ToolRouter(user_role="cs", user_message="查询订单")
        result = router.route(_tools(
            "query_order",
            "query_payment",
            "query_coupon_issue_log",
            "query_mq_dead_letter",
            "knowledge_search",
        ))
        assert {t["name"] for t in result} == {"query_order"}

    def test_cs_cannot_see_shop_metrics(self):
        router = ToolRouter(user_role="cs", user_message="昨天 GMV 是多少")
        result = router.route(_tools("shop_metrics_query", "query_order"))
        names = {t["name"] for t in result}
        assert "shop_metrics_query" not in names

    def test_cs_cannot_see_coupon_policy(self):
        router = ToolRouter(user_role="cs", user_message="优惠券规则是什么")
        result = router.route(_tools("coupon_policy_lookup", "query_order"))
        names = {t["name"] for t in result}
        assert "coupon_policy_lookup" not in names

    def test_admin_can_see_all_tools(self):
        router = ToolRouter(user_role="admin", user_message="查询订单")
        result = router.route(_tools(*ALL_TOOLS))
        names = {t["name"] for t in result}
        # admin 应能看到所有工具（受任务过滤影响，但至少 query_order 要在）
        assert "query_order" in names

    def test_unknown_role_sees_nothing(self):
        # 未知角色：所有工具的 allowed_roles 都不包含它
        router = ToolRouter(user_role="attacker")
        result = router.route(_tools(*ALL_TOOLS))
        assert len(result) == 0


# =========================================================
# 任务类型推断
# =========================================================

class TestTaskDetection:
    def test_diagnosis_from_order_keyword(self):
        router = ToolRouter(user_role="cs", user_message="用户订单显示异常，需要退款处理")
        assert router.task_type == "diagnosis"

    def test_diagnosis_from_payment_keyword(self):
        router = ToolRouter(user_role="cs", user_message="支付失败了怎么办")
        assert router.task_type == "diagnosis"

    def test_analytics_from_gmv_keyword(self):
        router = ToolRouter(user_role="merchant", user_message="昨天的GMV是多少")
        assert router.task_type == "analytics"

    def test_analytics_from_sales_keyword(self):
        router = ToolRouter(user_role="merchant", user_message="今天卖了多少")
        assert router.task_type == "analytics"

    def test_campaign_from_coupon_keyword(self):
        router = ToolRouter(user_role="merchant", user_message="帮我创建一个优惠券活动")
        assert router.task_type == "campaign"

    def test_knowledge_from_rule_keyword(self):
        # 注意：campaign 在 dict 中排在 knowledge 前面，"优惠券/活动" 会先命中 campaign。
        # "退款" 会先命中 diagnosis。只用纯 knowledge 关键词（限制/为什么/什么是）测此路径。
        router = ToolRouter(user_role="merchant", user_message="平台对账户的使用限制是什么")
        assert router.task_type == "knowledge"

    def test_general_when_no_keyword_matched(self):
        router = ToolRouter(user_role="merchant", user_message="你好，帮我看看")
        assert router.task_type == "general"

    def test_context_also_detected(self):
        # task_type 从 user_message + conversation_context 联合推断
        router = ToolRouter(
            user_role="cs",
            user_message="帮个忙",
            conversation_context="用户说订单有问题",
        )
        assert router.task_type == "diagnosis"


# =========================================================
# 上下文过滤（高风险工具展示门控）
# =========================================================

class TestContextFilter:
    def test_refund_hidden_without_payment_confirmation(self):
        """退款工具：对话中没有「支付成功」关键词时不展示。"""
        router = ToolRouter(
            user_role="cs",
            user_message="帮用户退款",
            conversation_context="用户反映订单有问题",
        )
        result = router.route(_tools("execute_refund", "query_order", "query_payment"))
        names = {t["name"] for t in result}
        assert "execute_refund" not in names

    def test_refund_shown_after_payment_confirmed(self):
        """退款工具：对话中有「已付款」确认后才展示；执行前仍必须 HITL。"""
        router = ToolRouter(
            user_role="cs",
            user_message="帮用户退款",
            conversation_context="订单状态 paid，支付成功",
        )
        result = router.route(_tools("execute_refund", "query_order", "query_payment"))
        names = {t["name"] for t in result}
        assert "execute_refund" in names

    def test_coupon_tool_hidden_without_issue_failure(self):
        """补券工具：没有「发券失败」确认时不展示。"""
        router = ToolRouter(
            user_role="cs",
            user_message="给用户补一张券",
            conversation_context="用户查询订单",
        )
        result = router.route(_tools("issue_compensation_coupon"))
        assert len(result) == 0

    def test_coupon_tool_shown_after_issue_failure(self):
        """补券工具：对话中有「未发券」确认后才展示；执行前仍必须 HITL。"""
        router = ToolRouter(
            user_role="cs",
            user_message="给用户补券",
            conversation_context="系统记录：发券失败 not_issued",
        )
        result = router.route(_tools("issue_compensation_coupon"))
        assert len(result) == 1

    def test_regular_tools_not_affected_by_context_filter(self):
        """普通查询工具不受上下文过滤限制。"""
        router = ToolRouter(
            user_role="cs",
            user_message="查订单",
            conversation_context="",
        )
        result = router.route(_tools("query_order", "shop_metrics_query", "knowledge_search"))
        names = {t["name"] for t in result}
        assert names == {"query_order"}
