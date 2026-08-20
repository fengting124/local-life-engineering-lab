"""
LangGraph Agent 节点实现。

每个节点是一个异步函数，接收 AgentState，返回 dict（partial state update）。
LangGraph 负责 merge 到完整 state，节点之间通过 state 传递信息。

节点列表：
  llm_node       → 调用 Claude，生成 Thought + Action（或 Final Answer）
  tool_node      → 执行 MCP 工具调用，获取 Observation
  reflection_node → Self-Reflection，分析当前路径是否有效
  hitl_node      → 创建 HITL 审批请求，挂起 Agent
  final_node     → 生成最终回答，结束循环
"""
import json
import re
import time
import structlog
from collections.abc import Mapping
from types import MappingProxyType
from langchain_core.messages import (
    AIMessage,
    BaseMessage,
    HumanMessage,
    RemoveMessage,
    SystemMessage,
    ToolMessage,
)
from agent.trace import SpanTimer, genai_span
from langchain_core.language_models import BaseChatModel

from agent.answer_facts import build_evidence_answer, validate_or_fallback
from agent.evidence_gate import (
    ToolOutcome,
    advance_evidence,
    normalize_tool_outcome,
)
from agent.state import AgentState
from agent.tool_router import extract_order_ids, order_target_hash
from mcp.mcp_client import McpClient, McpToolError
from config.settings import settings

log = structlog.get_logger(__name__)

HITL_TOOLS = {"execute_refund", "issue_compensation_coupon"}
HIGH_RISK_ROUTE_TYPES = {"refund_action", "compensation_action"}
ORDER_SCOPED_TOOLS = {
    "query_order",
    "query_payment",
    "query_coupon_issue_log",
    "query_mq_dead_letter",
    "resolve_compensation_coupon",
    *HITL_TOOLS,
}
CONTROLLED_DISPATCH_PLANS = {
    "order_query": ("query_order",),
    "payment_diagnosis": ("query_order", "query_payment"),
    "coupon_issue": ("query_order", "query_coupon_issue_log"),
}
CONTROLLED_KNOWLEDGE_DISPATCH_PLANS = MappingProxyType({
    "knowledge": ("knowledge_search",),
    "policy_configuration": ("knowledge_search", "coupon_policy_lookup"),
})

# =========================================================
# LLM 工厂（支持多 Provider 切换）
# =========================================================

def _create_llm() -> BaseChatModel:
    """
    根据 settings.llm_provider 创建对应的 LLM 客户端。

    切换方式（.env 文件）：
      LLM_PROVIDER=deepseek
      LLM_API_KEY=sk-xxxxxxxx
      LLM_MODEL=deepseek-v4-flash

    各 Provider 特点：
      anthropic → Claude，工具调用最稳定，推荐（需 ANTHROPIC_API_KEY）
      deepseek  → 性价比最高（¥2/百万token），DeepSeek-V3 工具调用能力不错
      openai    → GPT-4o，国际主流
      qwen      → 通义千问，国内可用，阿里云 DashScope
      local     → Ollama 本地模型（完全离线，不需要 Key）
    """
    # 优先用统一的 llm_api_key，兼容旧的 anthropic_api_key
    api_key = settings.llm_api_key or settings.anthropic_api_key
    provider = settings.llm_provider.lower()

    # ── Anthropic（默认）──
    if provider == "anthropic":
        from langchain_anthropic import ChatAnthropic
        log.info("llm_provider", provider="anthropic", model=settings.llm_model)
        return ChatAnthropic(
            model=settings.llm_model,
            max_tokens=settings.llm_max_tokens,
            api_key=api_key,
        )

    # ── DeepSeek / OpenAI / Qwen / Local（Ollama）── OpenAI 兼容接口
    # DeepSeek、通义千问、Ollama 都兼容 OpenAI Chat Completions API，
    # 只需要换 base_url 和 api_key，模型名对应各平台的名称。
    from langchain_openai import ChatOpenAI

    provider_defaults = {
        "deepseek": {
            "base_url": "https://api.deepseek.com/v1",
            # deepseek-chat / deepseek-reasoner 两个旧别名将于 2026-07-24 下线，
            # 统一指向 deepseek-v4-flash（非思考模式），故直接使用新名称。
            "model":    "deepseek-v4-flash",
        },
        "openai": {
            "base_url": "https://api.openai.com/v1",
            "model":    "gpt-4o",
        },
        "qwen": {
            "base_url": "https://dashscope.aliyuncs.com/compatible-mode/v1",
            "model":    "qwen-max",
        },
        "local": {
            # Ollama 默认监听 11434，不需要真实 key
            "base_url": "http://localhost:11434/v1",
            "model":    "qwen2.5:7b",
            "api_key":  "ollama",   # Ollama 要求非空但不校验
        },
    }

    defaults = provider_defaults.get(provider, {})
    base_url = settings.llm_base_url or defaults.get("base_url", "")
    # llm_model 默认是空字符串（未显式配置），这种情况下要落回 provider 的默认模型名，
    # 否则会把 model="" 传给 API 导致报错；只有用户显式填写了 LLM_MODEL 才尊重该值。
    model    = settings.llm_model or defaults.get("model", settings.llm_model)
    key      = api_key or defaults.get("api_key", "placeholder")

    log.info("llm_provider", provider=provider, model=model, base_url=base_url)
    return ChatOpenAI(
        model=model,
        max_tokens=settings.llm_max_tokens,
        api_key=key,
        base_url=base_url if base_url else None,
    )


_llm: BaseChatModel = _create_llm()


def _build_system_prompt(tools: list[dict], conversation_summary: str | None = None) -> str:
    """
    构建 ReAct System Prompt，嵌入工具列表和业务背景。

    Agent 的行为完全由 System Prompt 驱动，这里的设计要点：
    1. 角色定位：说明 Agent 是什么，服务谁
    2. 工具清单：嵌入 MCP 工具列表（name + description + hint）
    3. ReAct 格式：明确 Thought / Action / Observation 格式
    4. 终止条件：说明什么情况下输出 Final Answer
    5. 高风险规则：说明哪些动作必须 HITL，不能直接执行
    6. 历史摘要（可选）：Auto-Compact 触发后，早期消息已被删除替换为摘要，
       这里把摘要拼进 system prompt，让 Agent 在「失忆」之后仍知道此前发生了什么。

    :param conversation_summary: compact_node 生成的历史对话摘要。
        None 表示尚未触发过压缩，不需要拼接。
    """
    tool_descriptions = "\n".join([
        f"- {t['name']}: {t['description']}"
        + (f"\n  提示: {t.get('xBusinessHint', '')}" if t.get('xBusinessHint') else "")
        for t in tools
    ])

    # ---- 历史摘要拼接（仅在触发过 Auto-Compact 后存在）----
    # 放在 system prompt 末尾而不是单独插一条 message：
    #   1. 不破坏 messages 历史的角色顺序（避免连续 Human/Tool 消息触发 API 校验问题）
    #   2. 自然复用 system prompt 已有的 Prompt Caching（cache_control）
    summary_section = ""
    if conversation_summary:
        summary_section = f"""

## 历史对话摘要（早期消息已压缩）
以下是本次任务前期已经发生的关键信息要点（原始消息已删除以节省上下文空间）。
请基于这些要点继续推理，不要重复执行已经得出结论的工具调用：

{conversation_summary}"""

    return f"""你是 LocalLife Copilot，一个服务本地生活平台商家和客服的企业级 AI 助手。

## 你的职责
- 查询订单、支付、优惠券、门店经营数据
- 排查订单异常（支付成功但券未发放等）
- 解释平台规则和活动政策
- 辅助生成活动草稿和运营建议
- 对高风险动作（退款、补券）发起人工审批请求

## 可用工具
{tool_descriptions}

## 工作方式（ReAct 循环）
每一步：
1. Thought：分析当前任务和已有信息，决定下一步
2. Action：选择工具并生成参数
3. Observation：获取工具返回结果
4. 重复直到有足够证据输出 Final Answer

## 终止规则
- 当你有足够证据回答用户问题时，输出 Final Answer
- 最多执行 {settings.agent_max_steps} 步，超出后说明当前已收集的证据
- 工具连续失败 3 次，停止并解释失败原因
- 高风险动作（execute_refund / issue_compensation_coupon）不能直接调用，必须先走审批流程

## 安全规则
- 不能修改、伪造 merchant_id 或 user_id
- 不能访问不属于当前商家的数据
- 所有资金操作必须有明确证据支撑{summary_section}"""


# =========================================================
# 节点实现
# =========================================================

def _direct_route_answer(state: AgentState) -> str | None:
    if state.get("route_mode") == "clarification":
        labels = {
            "order_id": "具体订单号",
            "amount": "明确的退款或补偿金额",
            "metric": "需要查询的经营指标",
            "date": "一个具体日期",
            "supported_date": "今天、昨天或一个具体日期",
            "target": "一个具体业务目标",
        }
        fields = [
            labels.get(field, field)
            for field in state.get("route_missing_fields", [])
        ]
        requested = "、".join(fields) or "更具体的业务信息"
        return f"请补充{requested}，我再继续处理。"
    if (
        state.get("evidence_stop_reason") == "permission_denied"
        and not state.get("evidence_collected")
    ):
        return "当前角色无法获取完成该任务所需的证据，请升级给有权限的管理员处理。"
    return None


def _permission_escalation_answer(state: AgentState) -> str | None:
    if state.get("route_task_type") != "coupon_root_cause":
        return None
    order_evidence = state.get("evidence_collected", {}).get("query_order", {})
    if order_evidence.get("status") != "success":
        return None
    facts = order_evidence.get("facts", {})
    if facts.get("found") is not True:
        return None
    return (
        f"已确认订单状态为 {facts.get('order_status', 'UNKNOWN')}，"
        f"支付状态为 {facts.get('payment_status', 'UNKNOWN')}，"
        f"优惠券状态为 {facts.get('coupon_usage_status', 'UNKNOWN')}。"
        "继续查询发券日志和消息队列需要管理员权限，请升级给管理员完成根因排查。"
    )


def _latest_tool_payload(
    messages: list,
    tool_name: str,
) -> dict[str, object] | None:
    for message in reversed(messages):
        if not isinstance(message, ToolMessage) or message.name != tool_name:
            continue
        try:
            payload = json.loads(message.content)
        except (TypeError, json.JSONDecodeError):
            return None
        return payload if isinstance(payload, dict) else None
    return None


def _successful_evidence(
    state: AgentState,
    tool_name: str,
) -> Mapping[str, object] | None:
    record = state.get("evidence_collected", {}).get(tool_name)
    if not isinstance(record, Mapping) or record.get("status") != "success":
        return None
    facts = record.get("facts")
    return facts if isinstance(facts, Mapping) else None


def _request_binding_error(
    state: Mapping[str, object],
    tool_call: Mapping[str, object],
) -> str | None:
    tool_name = tool_call.get("name")
    if tool_name not in ORDER_SCOPED_TOOLS:
        return None
    if (
        state.get("route_task_type") not in HIGH_RISK_ROUTE_TYPES
        and tool_name not in HITL_TOOLS
    ):
        return None

    expected_order_hash = state.get("route_target_order_hash")
    if not isinstance(expected_order_hash, str) or not expected_order_hash:
        return "missing_request_target_binding"

    args = tool_call.get("args")
    if not isinstance(args, Mapping):
        return "request_target_mismatch"
    if order_target_hash(args.get("order_id")) != expected_order_hash:
        return "request_target_mismatch"

    if tool_name in HITL_TOOLS or tool_name == "resolve_compensation_coupon":
        expected_amount = state.get("route_requested_amount_minor")
        amount_key = (
            "amount"
            if tool_name == "execute_refund"
            else (
                "compensation_amount"
                if tool_name == "issue_compensation_coupon"
                else "amount_minor"
            )
        )
        actual_amount = args.get(amount_key)
        if (
            isinstance(expected_amount, bool)
            or not isinstance(expected_amount, int)
            or expected_amount <= 0
            or isinstance(actual_amount, bool)
            or actual_amount != expected_amount
        ):
            return "request_amount_mismatch"
    return None


def _query_order_response_matches_request(
    state: Mapping[str, object],
    raw_result: object,
) -> bool:
    expected_order_hash = state.get("route_target_order_hash")
    if not isinstance(expected_order_hash, str) or not expected_order_hash:
        return state.get("route_task_type") not in HIGH_RISK_ROUTE_TYPES
    if isinstance(raw_result, Mapping):
        payload = raw_result
    elif isinstance(raw_result, str):
        try:
            parsed = json.loads(raw_result)
        except (TypeError, ValueError):
            return False
        payload = parsed if isinstance(parsed, Mapping) else {}
    else:
        return False
    return order_target_hash(payload.get("order_no")) == expected_order_hash


def _build_structured_high_risk_proposal(
    state: AgentState,
) -> tuple[AIMessage | None, str | None]:
    next_tool = state.get("route_next_tool")
    if next_tool not in HITL_TOOLS:
        return None, None

    order_facts = _successful_evidence(state, "query_order")
    order = _latest_tool_payload(state["messages"], "query_order")
    order_status = (
        order_facts.get("order_status")
        if order_facts is not None
        else None
    )
    if (
        order_facts is None
        or order_facts.get("found") is not True
        or order_status not in {"PAID", "COMPLETED"}
        or order is None
        or order.get("order_status") != order_status
    ):
        return AIMessage(content="订单证据不完整，无法发起人工审批。"), "internal_error"

    order_id = order.get("order_no")
    requested_amount = state.get("route_requested_amount_minor")
    expected_order_hash = state.get("route_target_order_hash")
    if (
        not isinstance(order_id, str)
        or not order_id.strip()
        or order_target_hash(order_id) != expected_order_hash
        or isinstance(requested_amount, bool)
        or not isinstance(requested_amount, int)
        or requested_amount <= 0
    ):
        return AIMessage(content="订单证据不完整，无法发起人工审批。"), "internal_error"

    if next_tool == "execute_refund":
        payment = order.get("payment")
        paid_amount = payment.get("paid_amount") if isinstance(payment, Mapping) else None
        if isinstance(paid_amount, bool) or not isinstance(paid_amount, int) or paid_amount <= 0:
            return AIMessage(content="订单证据不完整，无法发起人工审批。"), "internal_error"
        if requested_amount > paid_amount:
            return (
                AIMessage(content="退款金额超过订单实付金额，无法发起人工审批。"),
                "business_rejected",
            )
        args = {
            "order_id": order_id.strip(),
            "amount": requested_amount,
            "reason": "订单状态满足退款前置条件，等待人工审批",
        }
    else:
        resolver_facts = _successful_evidence(state, "resolve_compensation_coupon")
        resolution = _latest_tool_payload(state["messages"], "resolve_compensation_coupon")
        if (
            resolver_facts is None
            or resolver_facts.get("compensation_configuration_available") is not True
            or resolution is None
            or resolution.get("order_no") != order_id.strip()
            or resolution.get("amount_minor") != requested_amount
        ):
            return AIMessage(content="补偿证据不完整，无法发起人工审批。"), "internal_error"
        required = (
            "target_user_id", "shop_id", "merchant_id", "coupon_template_id",
            "coupon_discount_type", "coupon_min_order_amount", "coupon_valid_days",
            "coupon_terms_digest",
        )
        if any(key not in resolution for key in required):
            return AIMessage(content="补偿证据不完整，无法发起人工审批。"), "internal_error"
        args = {
            "user_id": str(resolution["target_user_id"]).strip(),
            "order_id": order_id.strip(),
            "compensation_amount": requested_amount,
            "shop_id": str(resolution["shop_id"]).strip(),
            "merchant_id": str(resolution["merchant_id"]).strip(),
            "coupon_template_id": str(resolution["coupon_template_id"]).strip(),
            "coupon_discount_type": resolution["coupon_discount_type"],
            "coupon_min_order_amount": resolution["coupon_min_order_amount"],
            "coupon_valid_days": resolution["coupon_valid_days"],
            "coupon_terms_digest": resolution["coupon_terms_digest"],
            "reason": "补偿券配置已确定，等待人工审批",
        }

    return AIMessage(
        content="",
        tool_calls=[{
            "name": next_tool,
            "args": args,
            "id": f"controlled-{next_tool}-{state['step_count']}",
            "type": "tool_call",
        }],
    ), None


def _build_compensation_resolver_call(state: AgentState) -> AIMessage | None:
    if (
        state.get("route_task_type") != "compensation_action"
        or state.get("route_next_tool") != "resolve_compensation_coupon"
    ):
        return None
    order = _latest_tool_payload(state["messages"], "query_order")
    amount = state.get("route_requested_amount_minor")
    if (
        order is None
        or order_target_hash(order.get("order_no")) != state.get("route_target_order_hash")
        or isinstance(amount, bool)
        or not isinstance(amount, int)
        or amount <= 0
    ):
        return None
    return AIMessage(content="", tool_calls=[{
        "name": "resolve_compensation_coupon",
        "args": {"order_id": order["order_no"], "amount_minor": amount},
        "id": f"controlled-resolve_compensation_coupon-{state['step_count']}",
        "type": "tool_call",
    }])


def _bound_controlled_order_id(
    state: AgentState,
    next_tool: str,
) -> str | None:
    expected_hash = state.get("route_target_order_hash")
    if not isinstance(expected_hash, str) or not expected_hash:
        return None

    if next_tool == "query_order":
        current_message = next(
            (
                message
                for message in reversed(state.get("messages", []))
                if isinstance(message, HumanMessage)
            ),
            None,
        )
        if current_message is None:
            return None
        candidates = {
            candidate
            for candidate in extract_order_ids(str(current_message.content))
            if order_target_hash(candidate) == expected_hash
        }
        return next(iter(candidates)) if len(candidates) == 1 else None

    evidence = state.get("evidence_collected", {}).get("query_order")
    if not isinstance(evidence, Mapping) or evidence.get("status") != "success":
        return None
    facts = evidence.get("facts")
    if not isinstance(facts, Mapping) or facts.get("found") is not True:
        return None
    order = _latest_tool_payload(state.get("messages", []), "query_order")
    if order is None:
        return None
    order_id = order.get("order_no")
    if not isinstance(order_id, str) or order_target_hash(order_id) != expected_hash:
        return None
    return order_id.strip()


def _build_controlled_dispatch(
    state: AgentState,
    routed_tools: list[dict],
) -> tuple[AIMessage | None, str | None]:
    """Build one predetermined ToolCall while retaining tool_node enforcement."""
    if state.get("route_mode") != "controlled":
        return None, None
    task_type = state.get("route_task_type")
    plan = CONTROLLED_DISPATCH_PLANS.get(task_type)
    if plan is None:
        return None, None
    if tuple(state.get("route_required_tools", ())) != plan:
        return None, "invalid_controlled_plan"

    next_tool = state.get("route_next_tool")
    if next_tool not in plan:
        return None, "invalid_controlled_next_tool"
    if next_tool not in state.get("route_authorized_tools", ()):
        return None, "unauthorized_controlled_tool"
    if next_tool not in {tool.get("name") for tool in routed_tools}:
        return None, "controlled_tool_not_routed"

    order_id = _bound_controlled_order_id(state, next_tool)
    if order_id is None:
        return None, "controlled_order_binding_failed"
    return AIMessage(content="", tool_calls=[{
        "name": next_tool,
        "args": {"order_id": order_id},
        "id": f"controlled-{next_tool}-{state['step_count']}",
        "type": "tool_call",
    }]), None


def _build_controlled_knowledge_dispatch(
    state: AgentState,
    routed_tools: list[dict],
) -> tuple[AIMessage | None, str | None]:
    """Build a whitelisted knowledge-plan call without bypassing tool_node."""
    if state.get("route_mode") != "controlled":
        return None, None
    task_type = state.get("route_task_type")
    plan = CONTROLLED_KNOWLEDGE_DISPATCH_PLANS.get(task_type)
    if plan is None:
        return None, None
    required_tools = state.get("route_required_tools")
    if (
        not isinstance(required_tools, (list, tuple))
        or tuple(required_tools) != plan
    ):
        return None, "invalid_controlled_knowledge_plan"
    next_tool = state.get("route_next_tool")
    if next_tool not in plan:
        return None, "invalid_controlled_knowledge_next_tool"
    authorized_tools = state.get("route_authorized_tools")
    if (
        not isinstance(authorized_tools, (list, tuple))
        or tuple(authorized_tools) != plan
    ):
        return None, "unauthorized_controlled_knowledge_tool"
    if (
        not isinstance(routed_tools, list)
        or len(routed_tools) != 1
        or not isinstance(routed_tools[0], Mapping)
        or routed_tools[0].get("name") != next_tool
    ):
        return None, "controlled_knowledge_tool_not_routed"

    messages = state.get("messages")
    if (
        not isinstance(messages, (list, tuple))
        or not all(isinstance(message, BaseMessage) for message in messages)
    ):
        return None, "controlled_knowledge_query_missing"
    current_message = next(
        (
            message
            for message in reversed(messages)
            if isinstance(message, HumanMessage)
        ),
        None,
    )
    query = current_message.content if current_message is not None else None
    if not isinstance(query, str) or not query.strip():
        return None, "controlled_knowledge_query_missing"

    if next_tool == "coupon_policy_lookup":
        records = state.get("evidence_collected")
        knowledge_record = (
            records.get("knowledge_search") if isinstance(records, Mapping) else None
        )
        facts = (
            knowledge_record.get("facts")
            if isinstance(knowledge_record, Mapping)
            else None
        )
        if (
            not isinstance(knowledge_record, Mapping)
            or set(knowledge_record) != {"status", "attempts", "facts"}
            or knowledge_record.get("status") != "success"
            or not isinstance(knowledge_record.get("attempts"), int)
            or isinstance(knowledge_record.get("attempts"), bool)
            or knowledge_record.get("attempts", 0) < 1
            or not isinstance(facts, Mapping)
            or set(facts) != {"knowledge_found"}
            or facts.get("knowledge_found") is not True
        ):
            return None, "controlled_policy_knowledge_evidence_missing"
        return AIMessage(content="", tool_calls=[{
            "name": next_tool,
            "args": {},
            "id": f"controlled-{next_tool}-{state['step_count']}",
            "type": "tool_call",
        }]), None

    return AIMessage(content="", tool_calls=[{
        "name": next_tool,
        "args": {"query": query},
        "id": f"controlled-{next_tool}-{state['step_count']}",
        "type": "tool_call",
    }]), None


async def llm_node(state: AgentState) -> dict:
    """
    LLM 节点：调用 Claude 决定下一步动作。

    输入：完整消息历史 + 系统提示
    输出：新的 assistant 消息（含 tool_calls 或 Final Answer）
    """
    llm_duration_seconds: float | None = None
    direct_answer = _direct_route_answer(state)
    evidence_answer = (
        build_evidence_answer(state) if direct_answer is None else None
    )
    if direct_answer is not None:
        response = AIMessage(content=direct_answer)
    elif evidence_answer is not None:
        response = AIMessage(
            content=validate_or_fallback(None, evidence_answer)
        )
    else:
        response = None
    high_risk_proposal_stop_reason = None
    controlled_tool_unavailable = False
    controlled_dispatch_error = None
    if response is None:
        tools = []
        llm_with_tools = _llm
        if (
            state.get("synthesis_only")
            and settings.llm_provider.lower() == "deepseek"
        ):
            llm_with_tools = _llm.bind(
                extra_body={"thinking": {"type": "disabled"}}
            )

        if not state.get("synthesis_only"):
            # 原生工具和 MCP 工具共用一条路由，确保 Prompt 与 bind_tools 权限一致。
            from agent.tool_router import ToolRouter
            from rag import knowledge_tool

            mcp = McpClient(
                user_id=state["user_id"],
                user_role=state["user_role"],
                merchant_id=state.get("merchant_id"),
            )
            try:
                all_tools = await mcp.list_tools()
                if (
                    not isinstance(all_tools, list)
                    or any(
                        not isinstance(tool, Mapping)
                        or not isinstance(tool.get("name"), str)
                        or not tool["name"].strip()
                        for tool in all_tools
                    )
                ):
                    raise ValueError("malformed MCP tool catalog")
                mcp_available = True
            except Exception as e:
                log.error("mcp_list_tools_failed", error=str(e))
                all_tools = []
                mcp_available = False

            next_tool = state.get("route_next_tool")
            discovered_tool_names = {
                tool["name"] for tool in all_tools
            }
            if (
                state.get("route_mode") == "controlled"
                and next_tool
                and next_tool != "knowledge_search"
                and (
                    not mcp_available
                    or next_tool not in discovered_tool_names
                )
            ):
                response = AIMessage(
                    content="抱歉，发生内部错误，完成该请求所需的工具暂时不可用，请稍后重试。"
                )
                controlled_tool_unavailable = True
            else:
                router = ToolRouter.from_state(state)
                complete_tool_specs = [
                    *all_tools,
                    knowledge_tool.get_knowledge_search_tool_spec(),
                ]
                tools = router.route(complete_tool_specs)

                response, controlled_dispatch_error = _build_controlled_dispatch(
                    state,
                    tools,
                )
                if response is None and controlled_dispatch_error is None:
                    response, controlled_dispatch_error = (
                        _build_controlled_knowledge_dispatch(state, tools)
                    )
                if controlled_dispatch_error is not None:
                    response = AIMessage(
                        content="抱歉，受控查询参数校验失败，无法安全执行该请求。"
                    )

                if (
                    response is None
                    and settings.llm_provider.lower() == "deepseek"
                    and next_tool == "resolve_compensation_coupon"
                    and tools
                ):
                    response = _build_compensation_resolver_call(state)

                if (
                    response is None
                    and settings.llm_provider.lower() == "deepseek"
                    and next_tool in HITL_TOOLS
                    and tools
                ):
                    response, high_risk_proposal_stop_reason = (
                        _build_structured_high_risk_proposal(state)
                    )

                if response is None:
                    native_selected = any(
                        tool["name"] == "knowledge_search" for tool in tools
                    )
                    mcp_tools = [
                        tool for tool in tools if tool["name"] != "knowledge_search"
                    ]
                    lc_tools = _convert_to_lc_tools(mcp_tools)
                    if native_selected:
                        lc_tools.append(
                            knowledge_tool.make_knowledge_search_tool(
                                merchant_id=state.get("merchant_id")
                            )
                        )

                    tool_choice = (
                        next_tool
                        if state.get("route_mode") == "controlled" and tools
                        else None
                    )
                    if lc_tools:
                        binding_kwargs = {}
                        if tool_choice:
                            binding_kwargs["tool_choice"] = tool_choice
                            if settings.llm_provider.lower() == "deepseek":
                                binding_kwargs["extra_body"] = {
                                    "thinking": {"type": "disabled"}
                                }
                        llm_with_tools = _llm.bind_tools(
                            lc_tools,
                            **binding_kwargs,
                        )

        if response is None:
            # 构建消息列表（System + 历史消息）
            # ---- Prompt Caching（Claude 专属优化，节省 80-90% input token 成本）----
            # cache_control={"type":"ephemeral"} 告知 Claude 将此消息缓存 5 分钟。
            # 系统提示（角色定义 + 工具说明）是每轮对话都重复的稳定内容，适合缓存。
            # 注意：只缓存稳定内容；用户消息和工具结果不缓存（每次都不同）。
            # 面试说法：「通过 Prompt Caching 将稳定的 System Prompt 缓存，
            #   多轮对话中 input tokens 减少约 80%，成本从 ~$0.006/次降至 ~$0.001/次」
            system_msg = SystemMessage(
                content=_build_system_prompt(
                    tools,
                    conversation_summary=state.get("conversation_summary"),
                ),
                additional_kwargs={"cache_control": {"type": "ephemeral"}},
            )
            messages = [system_msg] + state["messages"]

            llm_started_at = time.perf_counter()
            async with genai_span(
                "llm.invoke",
                "llm",
                provider=settings.llm_provider,
                model=settings.llm_model or "provider-default",
                step=state["step_count"],
                session_id=state.get("session_id"),
                thread_id=state.get("thread_id"),
            ):
                response = await llm_with_tools.ainvoke(messages)
            llm_duration_seconds = time.perf_counter() - llm_started_at

    usage = getattr(response, "usage_metadata", {}) or {}
    synthesis_tool_call_rejected = bool(
        state.get("synthesis_only")
        and getattr(response, "tool_calls", None)
    )
    if synthesis_tool_call_rejected:
        log.warning(
            "synthesis_tool_call_rejected",
            proposed_tools=[
                tool_call.get("name", "unknown")
                for tool_call in response.tool_calls
            ],
        )
        response = AIMessage(
            content="依赖工具返回异常，本次任务未生成未经证实的结论。"
        )

    input_tokens = usage.get("input_tokens") if isinstance(usage, Mapping) else None
    output_tokens = usage.get("output_tokens") if isinstance(usage, Mapping) else None
    usage_reported = (
        llm_duration_seconds is not None
        and isinstance(input_tokens, int)
        and not isinstance(input_tokens, bool)
        and input_tokens >= 0
        and isinstance(output_tokens, int)
        and not isinstance(output_tokens, bool)
        and output_tokens >= 0
    )
    total_tokens = input_tokens + output_tokens if usage_reported else None
    if llm_duration_seconds is not None:
        if usage_reported:
            from agent.metrics import record_llm_call
            record_llm_call(
                state.get("user_role", "unknown"),
                input_tokens,
                output_tokens,
                llm_duration_seconds,
            )
        try:
            log.info(
                "llm_call_measured",
                session_id=state.get("session_id"),
                thread_id=state.get("thread_id"),
                step=state["step_count"],
                provider=settings.llm_provider,
                model=settings.llm_model or "provider-default",
                duration_ms=int(llm_duration_seconds * 1000),
                input_tokens=input_tokens if usage_reported else None,
                output_tokens=output_tokens if usage_reported else None,
                total_tokens=total_tokens,
                usage_status="reported" if usage_reported else "missing",
            )
        except Exception:
            pass

    usage_status = (
        "reported" if usage_reported
        else "missing" if llm_duration_seconds is not None
        else "not_called"
    )
    log.info(
        "llm_response",
        step=state["step_count"],
        has_tool_calls=bool(getattr(response, "tool_calls", None)),
        usage_status=usage_status,
    )

    # Preserve the pre-instrumentation context-budget behavior. Provider total
    # may include cached/system tokens that are not equal to input + output.
    new_tokens = usage.get("total_tokens", 0) if isinstance(usage, Mapping) else 0

    # 检查是否是 Final Answer（无 tool_calls）
    final_answer = None
    if not getattr(response, "tool_calls", None):
        final_answer = response.content

    # ---- 持久化 assistant 消息 ----
    # 不论是工具调用决策还是 Final Answer，都写入 agent_message 表
    # 用途：1) 会话回放 2) Evals 评测重放 3) 审计追溯
    try:
        from session.manager import session_manager
        session_id = state.get("session_id")
        if session_id:
            tool_calls_payload = None
            if getattr(response, "tool_calls", None):
                tool_calls_payload = [
                    {"id": tc.get("id"), "name": tc.get("name"), "args": tc.get("args", {})}
                    for tc in response.tool_calls
                ]
            await session_manager.save_message(
                session_id=session_id,
                role="assistant",
                content=str(response.content) if response.content else None,
                step_index=state["step_count"] + 1,
                tool_calls=tool_calls_payload,
                tokens=new_tokens,
            )
    except Exception as e:
        log.warning("save_assistant_message_failed", error=str(e))

    update = {
        "messages": [response],
        "step_count": state["step_count"] + 1,
        "token_count": state["token_count"] + new_tokens,
        "llm_call_count": state.get("llm_call_count", 0) + (
            1 if llm_duration_seconds is not None else 0
        ),
        "llm_input_tokens": state.get("llm_input_tokens", 0) + (
            input_tokens if usage_reported else 0
        ),
        "llm_output_tokens": state.get("llm_output_tokens", 0) + (
            output_tokens if usage_reported else 0
        ),
        "llm_usage_missing_count": state.get("llm_usage_missing_count", 0) + (
            1 if llm_duration_seconds is not None and not usage_reported else 0
        ),
        "final_answer": final_answer,
        "last_tool_failed": False,  # 重置
        "needs_reflection": False,  # 重置
    }
    if synthesis_tool_call_rejected:
        update.update({
            "route_next_tool": None,
            "evidence_stop_reason": "internal_error",
        })
    if high_risk_proposal_stop_reason:
        update.update({
            "route_next_tool": None,
            "evidence_stop_reason": high_risk_proposal_stop_reason,
        })
    if controlled_tool_unavailable:
        update.update({
            "route_next_tool": None,
            "evidence_stop_reason": "internal_error",
            "stop_reason": "internal_error",
        })
    if controlled_dispatch_error is not None:
        log.warning(
            "controlled_dispatch_rejected",
            reason=controlled_dispatch_error,
            task_type=state.get("route_task_type"),
            tool=state.get("route_next_tool"),
        )
        update.update({
            "route_next_tool": None,
            "evidence_stop_reason": "internal_error",
            "stop_reason": "internal_error",
        })
    return update


def _detect_loop(messages: list, tool_name: str, args: dict) -> bool:
    """
    循环检测：同一工具 + 同参数在最近消息中出现 ≥ 3 次则判定为循环。

    设计依据（来自面试题 Top50 Q14）：
    Agent 可能因工具一直返回相同错误而陷入「调同一工具→失败→再调同一工具」的死循环。
    此检测是 Agent Harness 的核心安全能力之一。

    阈值说明：
    - 阈值=3：允许 1 次正常调用 + 1 次参数修正重试 + 第3次触发停止
    - 只检查最近 10 条消息（防止历史消息误触发）
    - 区分参数：修正了参数的重试不算循环（如纠正了 order_id 格式）

    局限性：
    - 串行调用才能检测；并行调用在同一轮发出，不计入历史
    - 参数完全相同才触发；略有修改的参数无法检测
    """
    identical_calls = [
        tc
        for m in messages[-10:]
        if hasattr(m, "tool_calls") and m.tool_calls
        for tc in (m.tool_calls or [])
        if tc.get("name") == tool_name and tc.get("args") == args
    ]
    return len(identical_calls) >= 2  # 已有 2 次，当前是第 3 次


def _partition_tool_calls(tool_calls: list[dict]) -> list[tuple[str, list[int]]]:
    """
    按并发安全性给一轮工具调用分批，返回 [(模式, 下标列表), ...]。

    思路对照 Claude Code 的 partitionToolCalls()：
    - 连续的「并发安全」工具合并为一个 concurrent 批，仍用 asyncio.gather 并发执行；
    - 任何「不安全/高风险」工具单独切成一个 sequential 批，与前后批次隔离串行执行——
      既不和别的工具抢跑，也不会被并发中的异常掩盖自己的结果。

    例：[query_order, query_order, execute_refund, query_payment] 会被切成
        [concurrent: 0,1] [sequential: 2] [concurrent: 3]
    而不是不分青红皂白地把全部 4 个一起 gather。

    是否安全由 tool_router.is_tool_concurrency_safe 判定（fail-closed：
    未登记的工具一律按不安全处理）。

    :return: 模式为 "concurrent" / "sequential"，下标对应原始 tool_calls 的位置
    """
    from agent.tool_router import is_tool_concurrency_safe

    batches: list[tuple[str, list[int]]] = []
    pending_safe: list[int] = []

    def _flush_safe():
        if pending_safe:
            batches.append(("concurrent", pending_safe.copy()))
            pending_safe.clear()

    for i, tc in enumerate(tool_calls):
        if is_tool_concurrency_safe(tc["name"]):
            pending_safe.append(i)
        else:
            _flush_safe()
            batches.append(("sequential", [i]))

    _flush_safe()
    return batches


async def tool_node(state: AgentState) -> dict:
    """
    工具节点：按并发安全性分批执行 MCP 工具调用，获取 Observation。

    核心改进：
    1. **并发安全分批执行**（参考 Claude Code 的 partitionToolCalls）：
       同一轮 LLM 决策的工具调用先经 _partition_tool_calls 分批——
       连续的只读/查询类工具合并为并发批（asyncio.gather，延迟从 t1+t2+...
       降到 max(t1,t2,...)），而 execute_refund / issue_compensation_coupon
       等高风险写操作会被单独隔离为串行批，避免它们与其他调用产生竞态
       （例如同一轮里 query_order 和 execute_refund 同时跑）。
       fail-closed：未登记为「并发安全」的工具一律按不安全处理。

    2. **循环检测**：同工具同参数 ≥ 3 次触发停止，避免死循环浪费 token。

    从最新的 assistant 消息中读取 tool_calls，按分批结果执行（安全批并发、
    不安全批串行隔离），将结果按原始顺序追加到消息历史。
    """
    import asyncio
    import time as _time
    from agent.metrics import record_tool_call

    messages = state["messages"]
    last_msg = messages[-1]
    tool_calls = getattr(last_msg, "tool_calls", []) or []

    if not tool_calls:
        return {"messages": []}

    # Tool visibility is not an authorization boundary. Re-check every model
    # generated call immediately before any HITL, MCP, or native execution.
    from agent.metrics import (
        record_tool_budget_exhausted,
        record_tool_policy_denied,
    )
    from agent.tool_policy import evaluate_tool_batch, first_denied_tool

    user_role = state.get("user_role", "")
    denied_tool = first_denied_tool(tool_calls, user_role)
    if denied_tool:
        log.warning(
            "tool_permission_denied",
            tool=denied_tool,
            role=state.get("user_role", "unknown"),
        )
        record_tool_policy_denied(denied_tool, user_role or "unknown")
        denied_messages = [
            ToolMessage(
                content=json.dumps(
                    {
                        "error": "permission_denied",
                        "tool": tool_call["name"],
                    },
                    ensure_ascii=False,
                ),
                tool_call_id=tool_call.get("id", ""),
                name=tool_call["name"],
            )
            for tool_call in tool_calls
        ]
        return {
            "messages": denied_messages,
            "last_tool_failed": True,
            "policy_denied_tool": denied_tool,
            "stop_reason": "permission_denied",
            "route_next_tool": None,
            "evidence_stop_reason": "permission_denied",
        }

    budget = evaluate_tool_batch(
        tool_calls,
        tool_call_count=state.get("tool_call_count", 0),
        tool_call_counts=state.get("tool_call_counts", {}),
        tool_signature_counts=state.get("tool_signature_counts", {}),
        max_per_turn=settings.agent_max_tool_calls_per_turn,
        max_total=settings.agent_max_tool_calls_total,
        max_per_tool=settings.agent_max_calls_per_tool,
        max_identical=settings.agent_max_identical_tool_calls,
    )
    budget_state = {
        "tool_call_count": budget.tool_call_count,
        "tool_call_counts": budget.tool_call_counts,
        "tool_signature_counts": budget.tool_signature_counts,
    }
    if not budget.allowed:
        rejected_tool = budget.tool or "unknown"
        log.warning(
            "tool_budget_exhausted",
            reason=budget.reason,
            tool=rejected_tool,
        )
        record_tool_budget_exhausted(budget.reason or "unknown", rejected_tool)
        budget_messages = [
            ToolMessage(
                content=json.dumps(
                    {
                        "error": "tool_budget_exhausted",
                        "reason": budget.reason,
                        "tool": tool_call["name"],
                    },
                    ensure_ascii=False,
                ),
                tool_call_id=tool_call.get("id", ""),
                name=tool_call["name"],
            )
            for tool_call in tool_calls
        ]
        return {
            **budget_state,
            "messages": budget_messages,
            "last_tool_failed": True,
            "tool_budget_exhausted": True,
            "tool_budget_reason": budget.reason,
            "stop_reason": "tool_budget_exhausted",
            "route_next_tool": None,
            "evidence_stop_reason": "tool_budget_exhausted",
        }

    if state.get("route_mode") == "controlled" and (
        len(tool_calls) != 1
        or tool_calls[0]["name"] != state.get("route_next_tool")
    ):
        log.warning(
            "controlled_tool_batch_rejected",
            expected_tool=state.get("route_next_tool"),
            proposed_tools=[tool_call["name"] for tool_call in tool_calls],
        )
        rejected_messages = [
            ToolMessage(
                content=json.dumps(
                    {
                        "error": "internal_error",
                        "reason": "invalid_controlled_tool_batch",
                        "tool": tool_call["name"],
                    },
                    ensure_ascii=False,
                ),
                tool_call_id=tool_call.get("id", ""),
                name=tool_call["name"],
            )
            for tool_call in tool_calls
        ]
        return {
            **budget_state,
            "messages": rejected_messages,
            "last_tool_failed": True,
            "last_tool_error": "invalid_controlled_tool_batch",
            "stop_reason": "internal_error",
            "route_next_tool": None,
            "evidence_stop_reason": "internal_error",
        }

    binding_failures = [
        (index, error)
        for index, tool_call in enumerate(tool_calls)
        if (error := _request_binding_error(state, tool_call)) is not None
    ]
    if binding_failures:
        failed_index, binding_error = binding_failures[0]
        failed_call = tool_calls[failed_index]
        tool_name = failed_call["name"]
        binding_status = (
            "internal_error"
            if binding_error == "missing_request_target_binding"
            else "parameter_error"
        )
        log.warning(
            "request_binding_rejected",
            reason=binding_error,
            tool=tool_name,
        )
        binding_messages = [
            ToolMessage(
                content=json.dumps(
                    {
                        "error": binding_status,
                        "reason": (
                            binding_error
                            if index == failed_index
                            else "batch_rejected_due_request_binding"
                        ),
                        "tool": tool_call["name"],
                    },
                    ensure_ascii=False,
                ),
                tool_call_id=tool_call.get("id", ""),
                name=tool_call["name"],
            )
            for index, tool_call in enumerate(tool_calls)
        ]
        binding_update = advance_evidence(
            {**state, **budget_state},
            [ToolOutcome(tool_name, binding_status, {})],
        )
        binding_update.pop("messages", None)
        if tool_name in HITL_TOOLS or binding_status == "internal_error":
            binding_update.update({
                "route_next_tool": None,
                "evidence_stop_reason": binding_status,
                "stop_reason": binding_status,
            })
        return {
            **budget_state,
            **binding_update,
            "messages": binding_messages,
            "last_tool_failed": True,
            "last_tool_error": binding_error,
        }

    pending_action = state.get("pending_action") or {}
    for tool_call in tool_calls:
        tool_name = tool_call["name"]
        if tool_name not in HITL_TOOLS:
            continue
        approval_id = pending_action.get("approval_id")
        approval_digest = pending_action.get("approval_digest")
        if (
            not approval_id
            or not isinstance(approval_digest, str)
            or len(approval_digest) != 64
            or any(char not in "0123456789abcdefABCDEF" for char in approval_digest)
            or pending_action.get("action_type") != tool_name
        ):
            log.warning(
                "hitl_required_before_tool",
                tool=tool_name,
                session_id=state.get("session_id"),
                thread_id=state.get("thread_id"),
            )
            return {
                **budget_state,
                "messages": [],
                # The next hitl_node creates and binds the approval. Keeping this
                # intermediate checkpoint unpaused avoids persisting an unbound
                # pending HITL state.
                "pending_hitl": False,
                "pending_action": {
                    "action_type": tool_name,
                    "payload": tool_call.get("args", {}),
                    "reason": "高风险工具调用必须先经过人工审批",
                },
                "stop_reason": "pending_approval",
            }
        # ponytail: copy only when needed; avoid mutating LangChain's original tool_call args.
        tool_call["args"] = {
            **tool_call.get("args", {}),
            "approval_id": str(approval_id),
            "approval_digest": approval_digest,
        }

    mcp = McpClient(
        user_id=state["user_id"],
        user_role=state["user_role"],
        merchant_id=state.get("merchant_id"),
    )

    async def _execute_single_tool(
        tool_call: dict,
    ) -> tuple[ToolMessage, ToolOutcome]:
        """执行单个工具调用，返回配对的消息与规范化结果。"""
        tool_name = tool_call["name"]
        tool_args = tool_call.get("args", {})
        call_id   = tool_call.get("id", "")

        log.info(
            "tool_calling",
            tool=tool_name,
            argument_keys=sorted(tool_args),
            step=state["step_count"],
        )
        start = _time.time()

        try:
            async with genai_span(
                f"tool.{tool_name}",
                "tool",
                tool_name=tool_name,
                step=state["step_count"],
                session_id=state.get("session_id"),
                thread_id=state.get("thread_id"),
            ):
                if tool_name == "knowledge_search":
                    from rag.knowledge_tool import make_knowledge_search_tool
                    native_tool = make_knowledge_search_tool(merchant_id=state.get("merchant_id"))
                    result = await native_tool.ainvoke(tool_args)
                else:
                    result = await mcp.call_tool(
                        tool_name=tool_name,
                        arguments=tool_args,
                        session_id=state.get("session_id"),
                        thread_id=state.get("thread_id"),
                    )
            if (
                tool_name == "query_order"
                and not _query_order_response_matches_request(state, result)
            ):
                record_tool_call(
                    tool_name,
                    "internal_error",
                    _time.time() - start,
                )
                log.warning(
                    "query_order_response_binding_rejected",
                    tool=tool_name,
                )
                outcome = normalize_tool_outcome(
                    tool_name,
                    error_reason="internal_error",
                )
                return (
                    ToolMessage(
                        content=json.dumps(
                            {
                                "error": "internal_error",
                                "reason": "request_target_response_mismatch",
                                "tool": tool_name,
                            },
                            ensure_ascii=False,
                        ),
                        tool_call_id=call_id,
                        name=tool_name,
                    ),
                    outcome,
                )
            record_tool_call(tool_name, "success", _time.time() - start)
            log.info("tool_success", tool=tool_name, elapsed_ms=int((_time.time()-start)*1000))
            outcome = normalize_tool_outcome(tool_name, raw_result=result)
            return (
                ToolMessage(
                    content=result,
                    tool_call_id=call_id,
                    name=tool_name,
                ),
                outcome,
            )

        except McpToolError as e:
            record_tool_call(tool_name, e.reason, _time.time() - start)
            log.warning("tool_failed", tool=tool_name, reason=e.reason, detail=e.detail)
            outcome = normalize_tool_outcome(tool_name, error_reason=e.reason)
            return (
                ToolMessage(
                    content=f"[工具错误] {json.dumps(e.to_dict(), ensure_ascii=False)}",
                    tool_call_id=call_id,
                    name=tool_name,
                ),
                outcome,
            )
        except Exception as e:
            record_tool_call(tool_name, "internal_error", _time.time() - start)
            log.error("tool_exception", tool=tool_name, error=str(e))
            outcome = normalize_tool_outcome(
                tool_name,
                error_reason="internal_error",
            )
            return (
                ToolMessage(
                    content=f"[工具异常] {tool_name} 执行时发生内部错误: {str(e)[:200]}",
                    tool_call_id=call_id,
                    name=tool_name,
                ),
                outcome,
            )

    # ---- 按并发安全性分批执行 ----
    # 安全批：asyncio.gather 并发跑（return_exceptions=True 防止一个失败拖垮全部）；
    # 不安全批：单独 await，与其他批次彻底隔离，不与任何调用并发竞争。
    # 用 results 数组按原始下标回填，保证 ToolMessage 顺序与 tool_calls 一一对应。
    results: list = [None] * len(tool_calls)
    batches = _partition_tool_calls(tool_calls)
    log.debug("tool_batches_partitioned", step=state["step_count"],
              batches=[(mode, [tool_calls[i]["name"] for i in idxs]) for mode, idxs in batches])

    for mode, indices in batches:
        if mode == "concurrent":
            batch_results = await asyncio.gather(
                *[_execute_single_tool(tool_calls[idx]) for idx in indices],
                return_exceptions=True,
            )
        else:
            batch_results = []
            for idx in indices:
                try:
                    batch_results.append(await _execute_single_tool(tool_calls[idx]))
                except Exception as e:
                    batch_results.append(e)
        for idx, r in zip(indices, batch_results):
            results[idx] = r

    tool_messages = []
    tool_outcomes = []
    last_error = None

    for i, result in enumerate(results):
        if isinstance(result, Exception):
            # gather 捕获的未预期异常（不应该发生，_execute_single_tool 已处理所有异常）
            call_id = tool_calls[i].get("id", "")
            tool_name = tool_calls[i]["name"]
            tool_message = ToolMessage(
                content=f"[系统异常] {str(result)[:200]}",
                tool_call_id=call_id,
                name=tool_name,
            )
            outcome = normalize_tool_outcome(
                tool_name,
                error_reason="internal_error",
            )
        else:
            tool_message, outcome = result
        tool_messages.append(tool_message)
        tool_outcomes.append(outcome)
        if outcome.status != "success":
            last_error = str(tool_message.content or "")[:200]

    # ---- 持久化所有工具消息 ----
    try:
        from session.manager import session_manager
        session_id = state.get("session_id")
        if session_id and tool_messages:
            tool_results_payload = [
                {
                    "call_id": getattr(tm, "tool_call_id", ""),
                    "name":    getattr(tm, "name", ""),
                    "content": str(tm.content)[:5000],  # 截断超长内容
                }
                for tm in tool_messages
            ]
            await session_manager.save_message(
                session_id=session_id,
                role="tool",
                content=None,
                step_index=state["step_count"] + 1,
                tool_results=tool_results_payload,
            )
    except Exception as e:
        log.warning("save_tool_message_failed", error=str(e))

    evidence_update = advance_evidence(
        {**state, **budget_state},
        tool_outcomes,
    )
    evidence_update.pop("messages", None)
    technical_failure_statuses = {
        "parameter_error",
        "permission_denied",
        "timeout",
        "business_rejected",
        "internal_error",
    }
    return {
        **budget_state,
        **evidence_update,
        "messages": tool_messages,
        "last_tool_failed": any(
            outcome.status in technical_failure_statuses
            for outcome in tool_outcomes
        ),
        "last_tool_error": last_error,
    }


async def reflection_node(state: AgentState) -> dict:
    """
    Self-Reflection 节点：分析当前执行路径是否有效。

    触发条件：
    - 每 reflection_interval 步触发一次
    - 上次工具调用失败

    反思内容：
    - 已收集的证据是否足够？
    - 是否陷入无效循环（同一工具相同参数重复调用）？
    - 下一步的最佳行动是什么？
    """
    reflection_prompt = f"""请对当前任务执行过程做一个简短的 Self-Reflection（不超过 100 字）：

当前步数：{state["step_count"]}/{settings.agent_max_steps}
上次工具是否失败：{state.get('last_tool_failed', False)}
失败原因：{state.get('last_tool_error', '无')}

请评估：
1. 已收集的证据是否足够回答用户问题？
2. 当前路径是否有效，还是在重复无效操作？
3. 下一步的最佳行动建议。

请直接输出反思结论，不需要格式化。"""

    reflection_msg = HumanMessage(content=f"[Self-Reflection] {reflection_prompt}")

    log.info("reflection_triggered", step=state["step_count"], last_failed=state.get("last_tool_failed"))

    return {
        "messages": [reflection_msg],
        "needs_reflection": False,  # 重置，避免连续触发
        "last_tool_failed": False,
    }


# =========================================================
# Auto-Compact：上下文自动压缩
# =========================================================
#
# 设计动机（对照 Claude Code 的 autoCompact 实现）：
# 旧实现里，token_count 一旦达到 session_token_budget 就直接终止会话，
# 把"可能不完整"的结果甩给用户——一个排查到一半的订单异常，可能因为
# 刚好撞到预算上限就被腰斩。企业级 Agent 的做法是：在真正撞墙之前，
# 把早期消息摘要打薄，让会话"轻装"后继续完成任务，而不是被动放弃。
#
# 核心约束：
#   1. 缓冲触发：提前 compact_buffer_tokens 触发，给摘要请求本身预留空间，
#      避免在上下文已经顶满时再发起一次会爆的摘要请求。
#   2. 安全切分：绝不能把 AIMessage(tool_calls=...) 和它对应的 ToolMessage 拆开，
#      否则会产生孤立的 tool_result，触发 Anthropic / OpenAI API 的严格校验报错。
#   3. 摘要独立维护：摘要不混入 messages 历史（避免破坏 add_messages 的顺序语义和
#      角色交替规则），而是存入 conversation_summary 字段，由 llm_node 在构造
#      system prompt 时拼接进去——相当于一条"压缩边界声明"。
#   4. 熔断器：连续压缩失败（含"已无安全可压缩内容"）达到阈值后不再尝试，
#      防止"压缩了也没用 → 还是超阈值 → 再压缩"的死循环。

def _find_safe_compact_split(messages: list, keep_n: int) -> int:
    """
    从「保留最近 keep_n 条」的候选切分点开始，向前回退到一个安全边界。

    安全边界的定义：切分点左侧的最后一条消息，不能是
    （a）带 tool_calls 的 AIMessage（它的工具结果可能落在保留区里），
    （b）孤立的 ToolMessage（它对应的 AIMessage 可能落在摘要区里）。
    否则压缩后保留区会出现「半截工具链」，破坏 LLM API 的消息校验规则。

    回退过程中 candidate 严格递减，最坏情况退到 0（无法安全切分，调用方应跳过本次压缩）。

    :return: 安全的切分下标（[0, split) 摘要，[split, len) 保留）；<= 0 表示无法安全切分
    """
    n = len(messages)
    candidate = max(0, n - keep_n)

    while candidate > 0:
        prev = messages[candidate - 1]
        cur = messages[candidate]

        # 切分点前一条若是「待处理的工具调用」，其 ToolMessage 很可能就是 cur，
        # 一旦切开就会让保留区以孤立的 tool_result 开头 —— 把这条 AIMessage 也并入保留区
        if hasattr(prev, "tool_calls") and prev.tool_calls:
            candidate -= 1
            continue

        # 切分点本身若是孤立的 ToolMessage（它的 AIMessage 落在了摘要区），同样不安全，
        # 继续向前回退，直到找到一条独立的 Human / 不含 tool_calls 的 AI 消息作为边界
        if isinstance(cur, ToolMessage):
            candidate -= 1
            continue

        break

    return candidate


def _render_messages_for_summary(messages: list) -> str:
    """
    将待摘要的消息渲染成适合给 LLM 阅读的精简文本。

    只保留「角色 + 关键内容」，工具调用只展示工具名（不展开完整参数 JSON）——
    参数 JSON 对长期摘要没有价值，反而会占用大量摘要 token 预算。
    """
    lines = []
    for m in messages:
        role = getattr(m, "type", "unknown")
        content = m.content if isinstance(m.content, str) else str(m.content)
        if hasattr(m, "tool_calls") and m.tool_calls:
            tool_names = ", ".join(tc.get("name", "?") for tc in m.tool_calls)
            lines.append(f"[{role}] (调用工具: {tool_names}) {content[:200]}")
        else:
            lines.append(f"[{role}] {content[:500]}")
    return "\n".join(lines)


async def _summarize_messages(
    messages: list,
    previous_summary: str | None,
    state: AgentState,
) -> tuple[str, dict]:
    """
    调用 LLM 为待压缩的消息生成累积式摘要。

    Prompt 设计要点：
    - 明确告知"这是在压缩历史，不是在回答用户问题"，避免模型把摘要当成对用户的回复
    - 指明保留什么（已确认的事实结论、已尝试的工具与结论、未解决的问题）
    - 指明舍弃什么（工具调用的原始参数 JSON、重复的中间推理过程）
    - 若已存在上一轮摘要，要求在其基础上累积补充，而不是另起炉灶
      （否则多轮压缩后，第一轮摘要的内容会被逐步冲掉，造成信息丢失）
    """
    transcript = _render_messages_for_summary(messages)

    previous_block = (
        f"## 已有摘要（请在此基础上补充、更新，不要整段重写或丢弃其中仍然有效的信息）：\n{previous_summary}\n\n"
        if previous_summary else ""
    )

    prompt = f"""你正在为一个执行中的 Agent 任务压缩对话历史（这不是在回答用户问题，请勿生成面向用户的回复）。

请用不超过 300 字总结以下对话片段，重点保留：
1. 已确认的关键事实（订单号、状态、金额、商家信息等具体数值和结论）
2. 已经尝试过的工具调用及其结论（避免后续重复调用浪费步数）
3. 仍未解决、需要继续排查的问题

请舍弃：工具调用的原始参数 JSON、重复的中间推理过程、寒暄性内容。

{previous_block}## 待压缩的对话片段：
{transcript}

直接输出摘要正文（纯文本），不要加标题、不要加解释。"""

    started_at = time.perf_counter()
    try:
        async with genai_span(
            "llm.invoke",
            "llm",
            provider=settings.llm_provider,
            model=settings.llm_model or "provider-default",
            operation="compact",
            session_id=state.get("session_id"),
            thread_id=state.get("thread_id"),
        ):
            response = await _llm.ainvoke([HumanMessage(content=prompt)])
    except Exception:
        duration_seconds = time.perf_counter() - started_at
        from agent.metrics import record_llm_latency
        record_llm_latency(state.get("user_role", "unknown"), duration_seconds)
        try:
            log.info(
                "llm_call_measured",
                session_id=state.get("session_id"),
                thread_id=state.get("thread_id"),
                operation="compact",
                provider=settings.llm_provider,
                model=settings.llm_model or "provider-default",
                duration_ms=int(duration_seconds * 1000),
                input_tokens=None,
                output_tokens=None,
                total_tokens=None,
                usage_status="missing",
            )
        except Exception:
            pass
        raise
    duration_seconds = time.perf_counter() - started_at
    usage = getattr(response, "usage_metadata", {}) or {}
    input_tokens = usage.get("input_tokens") if isinstance(usage, Mapping) else None
    output_tokens = usage.get("output_tokens") if isinstance(usage, Mapping) else None
    usage_reported = (
        isinstance(input_tokens, int)
        and not isinstance(input_tokens, bool)
        and input_tokens >= 0
        and isinstance(output_tokens, int)
        and not isinstance(output_tokens, bool)
        and output_tokens >= 0
    )
    if usage_reported:
        from agent.metrics import record_llm_call
        record_llm_call(
            state.get("user_role", "unknown"),
            input_tokens,
            output_tokens,
            duration_seconds,
        )
    try:
        log.info(
            "llm_call_measured",
            session_id=state.get("session_id"),
            thread_id=state.get("thread_id"),
            operation="compact",
            provider=settings.llm_provider,
            model=settings.llm_model or "provider-default",
            duration_ms=int(duration_seconds * 1000),
            input_tokens=input_tokens if usage_reported else None,
            output_tokens=output_tokens if usage_reported else None,
            total_tokens=(input_tokens + output_tokens) if usage_reported else None,
            usage_status="reported" if usage_reported else "missing",
        )
    except Exception:
        pass
    summary = response.content if isinstance(response.content, str) else str(response.content)
    return summary.strip(), {
        "input_tokens": input_tokens if usage_reported else 0,
        "output_tokens": output_tokens if usage_reported else 0,
        "usage_missing": 0 if usage_reported else 1,
    }


async def compact_node(state: AgentState) -> dict:
    """
    Auto-Compact 节点：当 token 消耗接近预算上限时，把较早的消息压缩成一段摘要，
    释放上下文空间，让 Agent 得以"轻装继续"而不是被硬终止。

    执行步骤：
    1. 找到安全切分点（不破坏 tool_call/tool_result 配对）
    2. 调 LLM 把切分点之前的消息总结成一段摘要文本（与历史摘要累积合并）
    3. 用 RemoveMessage 把这些旧消息从 state["messages"] 中删除
       （LangGraph 的 add_messages reducer 协议：返回 RemoveMessage(id=...) 即可删除对应消息）
    4. 把摘要写入 conversation_summary，由 llm_node 拼进 system prompt 继续工作
    5. 按估算释放的 token 量回调 token_count，避免「压缩后立刻又被判定超阈值」的死循环
       （token_count 在本系统中本质是"下一次请求大致会占用多少上下文"的代理指标，
       而非严格的计费总量——计费层面的真实 token 消耗由 Prometheus llm_tokens_total 单独追踪）
    """
    from agent.metrics import record_compact_event

    messages = state["messages"]
    keep_n = settings.compact_keep_recent_messages

    split_index = _find_safe_compact_split(messages, keep_n)

    if split_index <= 0:
        # 找不到安全切分点（比如整段历史都是连续的工具调用链），本次跳过，计入熔断计数
        failures = state.get("compact_failures", 0) + 1
        log.info("compact_skipped_no_safe_split", total=len(messages),
                 keep_n=keep_n, consecutive_failures=failures)
        record_compact_event("skipped_no_split")
        return {"compact_failures": failures}

    to_summarize = messages[:split_index]
    to_keep = messages[split_index:]

    log.info("compact_triggered", total=len(messages),
             summarize_count=len(to_summarize), keep_count=len(to_keep),
             token_count=state["token_count"])

    try:
        summary_text, compact_usage = await _summarize_messages(
            to_summarize,
            state.get("conversation_summary"),
            state,
        )
    except Exception as e:
        failures = state.get("compact_failures", 0) + 1
        log.warning("compact_summarize_failed", error=str(e), consecutive_failures=failures)
        record_compact_event("failed")
        return {
            "compact_failures": failures,
            "llm_call_count": state.get("llm_call_count", 0) + 1,
            "llm_input_tokens": state.get("llm_input_tokens", 0),
            "llm_output_tokens": state.get("llm_output_tokens", 0),
            "llm_usage_missing_count": state.get("llm_usage_missing_count", 0) + 1,
        }

    # 用 RemoveMessage 从 state 中删除被摘要的旧消息（add_messages reducer 按 id 匹配删除）
    removals = [RemoveMessage(id=m.id) for m in to_summarize if getattr(m, "id", None)]

    # 粗略估算释放的 token 量（按 4 字符 ≈ 1 token 的经验比例），让 token_count
    # 回落到阈值以下——否则下一轮 route_after_llm 会立刻再次判定超阈值，陷入死循环
    freed_estimate = sum(
        len(m.content if isinstance(m.content, str) else str(m.content)) // 4
        for m in to_summarize
    )
    new_token_count = max(0, state["token_count"] - freed_estimate)

    log.info("compact_completed", removed=len(removals),
             summary_chars=len(summary_text), freed_estimate=freed_estimate,
             token_count_before=state["token_count"], token_count_after=new_token_count)
    record_compact_event("success")

    return {
        "messages": removals,
        "conversation_summary": summary_text,
        "compact_failures": 0,        # 成功后重置熔断计数
        "token_count": new_token_count,
        "llm_call_count": state.get("llm_call_count", 0) + 1,
        "llm_input_tokens": state.get("llm_input_tokens", 0) + compact_usage["input_tokens"],
        "llm_output_tokens": state.get("llm_output_tokens", 0) + compact_usage["output_tokens"],
        "llm_usage_missing_count": state.get("llm_usage_missing_count", 0) + compact_usage["usage_missing"],
    }


async def hitl_node(state: AgentState) -> dict:
    """
    HITL 节点：处理高风险动作的人工审批请求。

    当 Agent 决定执行高风险动作（退款/补券）时，完整流程：
    1. 从 pending_action 中读取动作类型和参数
    2. 写 hitl_approval 记录到 MySQL（status=PENDING）
    3. 生成挂起通知消息（含 approval_id，前端展示审批状态）
    4. 返回 pending_hitl=True → 路由到 END → LangGraph thread 挂起
    5. 外部审批系统审批通过后，POST /chat/resume 恢复 thread

    Checkpoint 说明：
    LangGraph 在每个节点执行后自动写 checkpoint（由 checkpointer 配置）。
    hitl_node 执行完毕 → checkpoint 写入 → thread 挂起。
    恢复时：/chat/resume 从审批记录读取 thread_id + checkpoint_id，
    LangGraph 只从该审批绑定的精确 checkpoint 恢复。
    """
    pending      = state.get("pending_action") or {}
    action_type  = pending.get("action_type", "unknown")
    action_payload = dict(pending.get("payload") or {})
    merchant_id = state.get("merchant_id")
    agent_reason = pending.get("reason", "Agent 认为需要执行此高风险动作")
    session_id   = state.get("session_id")
    thread_id    = state.get("thread_id", "")
    stage_timer = SpanTimer(
        "hitl.prepare",
        "hitl",
        action_type=action_type,
        session_id=session_id,
        thread_id=thread_id,
    )

    log.info(
        "hitl_requested",
        action_type=action_type,
        session_id=session_id,
        thread_id=thread_id,
    )

    # ---- 写 hitl_approval 到 MySQL ----
    try:
        from session.hitl import hitl_service
        from session.hitl_binding import ApprovalPayload, sign_payload

        amount_key = (
            "amount"
            if action_type == "execute_refund"
            else "compensation_amount"
        )
        common_payload = {
            "tool_name": action_type,
            "order_id": action_payload.get("order_id"),
            "amount_minor": action_payload.get(amount_key),
            "requested_user_id": state.get("user_id"),
            "requested_role": state.get("user_role"),
            "reason": action_payload.get("reason") or agent_reason,
        }
        if action_type == "issue_compensation_coupon":
            approval_payload = ApprovalPayload(
                payload_version=2,
                target_user_id=action_payload.get("user_id", ""),
                shop_id=action_payload.get("shop_id", ""),
                merchant_id=action_payload.get("merchant_id", ""),
                coupon_template_id=action_payload.get("coupon_template_id", ""),
                coupon_discount_type=action_payload.get(
                    "coupon_discount_type", ""
                ),
                coupon_min_order_amount=action_payload.get(
                    "coupon_min_order_amount"
                ),
                coupon_valid_days=action_payload.get("coupon_valid_days"),
                coupon_terms_digest=action_payload.get("coupon_terms_digest", ""),
                **common_payload,
            )
        else:
            approval_payload = ApprovalPayload(
                payload_version=1,
                target_user_id="",
                merchant_id=merchant_id if merchant_id is not None else "",
                **common_payload,
            )
        approval_id = await hitl_service.create_approval(
            session_id=session_id or 0,
            thread_id=thread_id,
            approval_payload=approval_payload,
            agent_reason=agent_reason,
        )
        if not approval_id:
            raise RuntimeError("approval persistence returned no ID")
        payload_digest = sign_payload(
            approval_payload,
            settings.hitl_payload_signing_secret,
        )
        log.info("hitl_approval_written", approval_id=approval_id)
    except Exception as e:
        stage_timer.finish(status="error", error_type=type(e).__name__)
        log.error("hitl_approval_write_failed", error_type=type(e).__name__)
        return {
            "pending_hitl": False,
            "pending_action": None,
            "evidence_stop_reason": "internal_error",
            "stop_reason": "internal_error",
            "final_answer": "审批服务暂时不可用，本次高风险操作未提交。",
        }

    # ---- 生成挂起通知消息 ----
    approval_details = ""
    if approval_payload.payload_version == 2:
        approval_details = (
            f"**订单**：{approval_payload.order_id}\n\n"
            f"**目标用户**：{approval_payload.target_user_id}\n\n"
            f"**门店**：{approval_payload.shop_id}\n\n"
            f"**补偿金额**：{approval_payload.amount_minor / 100:.2f} 元\n\n"
            f"**券模板**：{approval_payload.coupon_template_id}\n\n"
            f"**使用门槛**：{approval_payload.coupon_min_order_amount / 100:.2f} 元\n\n"
            f"**有效期**：{approval_payload.coupon_valid_days} 天\n\n"
        )
    hitl_message = AIMessage(content=(
        f"此操作（**{action_type}**）涉及高风险，需要人工审批后才能执行。\n\n"
        f"{approval_details}"
        f"**申请原因**：{approval_payload.reason}\n\n"
        f"**审批记录 ID**：{approval_id or '写入失败，请联系技术支持'}\n\n"
        f"已提交审批申请，请运营人员在审批工作台处理。"
        f"审批通过后系统将继续执行，拒绝则任务终止。"
    ))
    stage_timer.finish(status="ok")

    return {
        "messages":      [hitl_message],
        "pending_hitl":  True,
        "stop_reason":   "pending_approval",
        **(
            {"merchant_id": int(approval_payload.merchant_id)}
            if approval_payload.payload_version == 2
            else {}
        ),
        # 将 approval_id 存入 state，恢复时传给工具作为凭证
        "pending_action": {
            **pending,
            "approval_id": approval_id,
            "payload_digest": payload_digest,
            "approval_payload": approval_payload.canonical_dict(),
        },
    }


async def final_node(state: AgentState) -> dict:
    """
    Final Answer 节点：生成最终回答并终止循环。

    处理三种情况：
    1. 正常完成（final_answer 已由 llm_node 填写）
    2. 步数超限（agent_max_steps 触发）
    3. Token 预算耗尽
    """
    step_count   = state["step_count"]
    token_count  = state["token_count"]
    final_answer = state.get("final_answer")

    # 策略终止优先于通用上限，避免被误记为正常完成。
    requested_stop = (
        state.get("evidence_stop_reason")
        or state.get("stop_reason")
    )
    if requested_stop == "permission_denied" or state.get("policy_denied_tool"):
        stop_reason = "permission_denied"
        final_answer = (
            final_answer
            or _permission_escalation_answer(state)
            or "当前角色没有权限执行该工具，任务已安全终止。"
        )
    elif requested_stop == "tool_budget_exhausted" or state.get("tool_budget_exhausted"):
        stop_reason = "tool_budget_exhausted"
        final_answer = final_answer or "本次任务已达到工具调用预算，已停止继续执行。"
    elif requested_stop == "tool_loop_detected":
        stop_reason = "tool_loop_detected"
        final_answer = final_answer or "检测到重复工具调用，已停止继续执行。"
    elif requested_stop in {
        "not_found",
        "parameter_error",
        "timeout",
        "business_rejected",
        "internal_error",
    }:
        stop_reason = requested_stop
        final_answer = final_answer or {
            "not_found": "未找到与请求匹配的业务记录，未继续调用下游工具。",
            "parameter_error": "工具参数仍不符合要求，请核对必要信息后重试。",
            "timeout": "依赖工具连续超时，本次任务已停止，请稍后重试。",
            "business_rejected": "当前业务状态不满足继续处理的前置条件。",
            "internal_error": "依赖工具返回异常，本次任务未生成未经证实的结论。",
        }[requested_stop]
    elif state.get("route_mode") == "clarification":
        stop_reason = "clarification"
        final_answer = final_answer or _direct_route_answer(state)
    elif step_count >= settings.agent_max_steps:
        stop_reason = "max_steps"
        if not final_answer:
            final_answer = (
                f"已执行 {step_count} 步，达到最大步数限制。"
                "以下是目前已收集的信息，但可能不完整。"
            )
    elif token_count >= settings.session_token_budget:
        stop_reason = "token_budget"
        if not final_answer:
            final_answer = "Token 预算已耗尽，已返回当前收集到的信息。"
    else:
        stop_reason = "completed"

    log.info(
        "agent_completed",
        stop_reason=stop_reason,
        steps=step_count,
        tokens=token_count,
    )

    # ---- Prometheus 业务指标 ----
    try:
        from agent.metrics import record_session_end, record_tool_calls_per_run
        record_session_end(
            status=stop_reason,
            role=state.get("user_role", "unknown"),
            step_count=step_count,
        )
        record_tool_calls_per_run(state.get("tool_call_count", 0))
    except Exception as e:
        log.warning("metrics_record_failed", error=str(e))

    # ---- 持久化 final answer + 更新会话状态 ----
    try:
        from session.manager import session_manager
        session_id = state.get("session_id")
        if session_id:
            # 写入最终回答消息
            await session_manager.save_message(
                session_id=session_id,
                role="assistant",
                content=final_answer or "任务处理完成。",
                step_index=step_count + 1,
                tokens=0,
            )
            # 更新会话状态
            final_status = "COMPLETED" if stop_reason == "completed" else "ABORTED"
            await session_manager.update_session_status(
                session_id=session_id,
                status=final_status,
                total_tokens=token_count,
            )
    except Exception as e:
        log.warning("save_final_message_failed", error=str(e))

    final_msg = AIMessage(content=final_answer or "任务处理完成。")

    return {
        "messages": [final_msg],
        "final_answer": final_answer,
        "stop_reason": stop_reason,
    }


# =========================================================
# 工具格式转换
# =========================================================

def _convert_to_lc_tools(mcp_tools: list[dict]) -> list[dict]:
    """
    将 MCP 工具定义转换为 LangChain bind_tools 格式。

    LangChain 的 bind_tools 接受 dict 格式的工具定义：
    { "name": ..., "description": ..., "parameters": <JSON Schema> }
    """
    lc_tools = []
    for t in mcp_tools:
        # 过滤掉当前用户无权使用的工具（RBAC 在 MCP Server 侧也会校验，这里做 Agent 层过滤）
        lc_tools.append({
            "name": t["name"],
            "description": t["description"],
            "parameters": t.get("inputSchema", {"type": "object", "properties": {}}),
        })
    return lc_tools
