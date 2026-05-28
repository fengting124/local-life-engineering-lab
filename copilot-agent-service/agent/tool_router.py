"""
Tool Router：动态工具过滤器。

面试高频问题：为什么不直接把所有工具传给 LLM？

原因：
  1. 工具太多占用大量 Token（每个工具 Schema 约 200-500 token）
  2. 无关工具增加 LLM 的决策噪音，降低工具调用准确率
  3. 权限边界：merchant 不应该看到 execute_refund 的工具描述
  4. 任务相关性：门店查询任务无需展示退款工具

Tool Router 根据当前上下文动态决定传给 LLM 的工具子集：
  - 按用户角色过滤（merchant 无法看到 cs/admin 专属工具）
  - 按任务类型路由（查询类任务只展示查询工具，执行类任务才展示执行工具）
  - 按对话历史推断（已发现「支付成功」才展示退款工具）

设计模式：职责单一的过滤链（Filter Chain），每个过滤器独立可测。
"""
import re
import structlog

log = structlog.get_logger(__name__)

# =========================================================
# 工具分组定义
# =========================================================

# 所有工具的角色权限配置（与 Java MCP Server 的 x-allowed-roles 保持一致）
TOOL_ROLE_MAP: dict[str, list[str]] = {
    # === Java MCP 工具 ===
    "query_order":               ["merchant", "cs", "admin"],
    "query_payment":             ["cs", "admin"],
    "query_coupon_issue_log":    ["cs", "admin"],
    "query_mq_dead_letter":      ["cs", "admin"],
    "shop_metrics_query":        ["merchant", "cs", "admin"],
    "coupon_policy_lookup":      ["merchant", "cs", "admin"],
    "campaign_draft_generate":   ["merchant", "admin"],
    "execute_refund":            ["cs", "admin"],
    "issue_compensation_coupon": ["cs", "admin"],
    # === Python 原生工具（RAG） ===
    "knowledge_search":          ["merchant", "cs", "admin"],
}

# 工具任务分组（按使用场景分类）
TOOL_TASK_MAP: dict[str, list[str]] = {
    "diagnosis": [    # 订单异常排查场景
        "query_order", "query_payment", "query_coupon_issue_log",
        "query_mq_dead_letter", "execute_refund", "issue_compensation_coupon",
    ],
    "analytics": [    # 经营数据分析场景
        "shop_metrics_query", "coupon_policy_lookup", "knowledge_search",
    ],
    "campaign": [     # 活动配置场景
        "campaign_draft_generate", "coupon_policy_lookup", "shop_metrics_query",
    ],
    "knowledge": [    # 知识问答场景
        "knowledge_search", "coupon_policy_lookup",
    ],
    "general": [      # 通用（首次请求，任务类型未知）
        "query_order", "shop_metrics_query", "knowledge_search",
        "coupon_policy_lookup",
    ],
}

# 触发任务类型检测的关键词（从用户消息中推断任务类型）
TASK_KEYWORDS: dict[str, list[str]] = {
    "diagnosis": [
        "订单", "支付", "没收到", "已付款", "未发券", "异常", "投诉", "退款",
        "order", "payment", "refund", "issue",
    ],
    "analytics": [
        "卖了多少", "营业额", "gmv", "订单量", "数据", "统计",
        "昨天", "今天", "这周", "这个月",
    ],
    "campaign": [
        "活动", "优惠券", "配置", "创建", "发券", "节日", "大促",
        "campaign", "coupon",
    ],
    "knowledge": [
        "规则", "政策", "限制", "怎么", "为什么", "什么是",
        "rule", "policy",
    ],
}


# =========================================================
# Tool Router 核心逻辑
# =========================================================

class ToolRouter:
    """
    动态工具路由器。

    使用方式：
      router = ToolRouter(user_role="merchant", user_message="我昨天卖了多少钱")
      filtered_tools = router.route(all_tools)
      # filtered_tools 只包含 merchant 角色可见的经营数据工具
    """

    def __init__(self, user_role: str, user_message: str = "", conversation_context: str = ""):
        self.user_role = user_role
        self.user_message = user_message
        self.conversation_context = conversation_context

        # 从用户消息推断任务类型
        self.task_type = self._detect_task_type(user_message + " " + conversation_context)
        log.debug("tool_router_init", role=user_role, task_type=self.task_type)

    def route(self, all_tools: list[dict]) -> list[dict]:
        """
        对完整工具列表进行过滤，返回当前上下文适合的工具子集。

        过滤顺序：
          1. 角色过滤（RBAC）：去掉当前角色无权使用的工具
          2. 任务过滤：只保留与当前任务相关的工具
          3. 上下文过滤：根据对话历史进一步精简（如：尚未确认支付成功，不展示退款工具）

        :param all_tools: tools/list 返回的完整工具定义列表
        :return: 过滤后的工具子集
        """
        # Step 1：RBAC 过滤
        role_filtered = [
            t for t in all_tools
            if self._check_role(t["name"])
        ]

        # Step 2：任务类型过滤
        task_filtered = self._filter_by_task(role_filtered)

        # Step 3：上下文过滤（防止 Agent 在没有充分证据时就展示高风险工具）
        context_filtered = self._filter_by_context(task_filtered)

        original_count = len(all_tools)
        final_count    = len(context_filtered)
        log.info(
            "tools_routed",
            role=self.user_role,
            task=self.task_type,
            original=original_count,
            final=final_count,
            tools=[t["name"] for t in context_filtered],
        )
        return context_filtered

    def _check_role(self, tool_name: str) -> bool:
        """检查当前角色是否有权使用此工具。"""
        allowed = TOOL_ROLE_MAP.get(tool_name, ["admin"])
        return self.user_role in allowed

    def _filter_by_task(self, tools: list[dict]) -> list[dict]:
        """只保留与当前任务类型相关的工具。"""
        task_tools = TOOL_TASK_MAP.get(self.task_type, TOOL_TASK_MAP["general"])
        return [t for t in tools if t["name"] in task_tools]

    def _filter_by_context(self, tools: list[dict]) -> list[dict]:
        """
        根据对话历史精简工具。

        核心逻辑：高风险工具（退款/补券）只有在对话中已经出现
        「支付成功」「订单状态 PAID」等关键词时才展示。
        避免 Agent 在不了解完整情况时就提议退款。
        """
        context_lower = self.conversation_context.lower()

        filtered = []
        for t in tools:
            name = t["name"]
            # 退款工具：只有在已确认支付成功后才展示
            if name == "execute_refund":
                if not self._context_contains_any(context_lower, ["paid", "success", "已付", "支付成功"]):
                    continue  # 不展示，减少误触发风险
            # 补券工具：只有在已确认券发放失败后才展示
            if name == "issue_compensation_coupon":
                if not self._context_contains_any(context_lower, ["not_issued", "failed", "未发券", "发券失败"]):
                    continue
            filtered.append(t)

        return filtered

    def _detect_task_type(self, text: str) -> str:
        """从文本中推断任务类型（简单关键词匹配）。"""
        text_lower = text.lower()
        for task, keywords in TASK_KEYWORDS.items():
            if any(kw in text_lower for kw in keywords):
                return task
        return "general"

    @staticmethod
    def _context_contains_any(context: str, keywords: list[str]) -> bool:
        return any(kw in context for kw in keywords)
