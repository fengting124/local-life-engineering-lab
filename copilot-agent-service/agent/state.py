"""
LangGraph Agent 状态定义。

AgentState 是 LangGraph StateGraph 的核心数据结构，
贯穿整个 ReAct 循环的所有节点（llm_node / tool_node / reflection_node 等）。

每个节点接收 AgentState，返回 dict（LangGraph 自动 merge 到状态中）。
"""
from typing import Annotated, TypedDict
from langgraph.graph.message import add_messages
from langchain_core.messages import BaseMessage


class AgentState(TypedDict):
    """
    ReAct Agent 的完整运行状态。

    LangGraph 的 StateGraph 使用此 TypedDict 追踪 Agent 执行过程中的所有信息。

    设计原则：
    1. messages 使用 add_messages reducer（追加语义，不覆盖），保留完整对话历史
    2. 其他字段为 last-write-wins（节点直接赋值覆盖）
    3. 需要持久化到 checkpoint 的字段都在这里声明
    """

    # =========================================================
    # 核心状态（ReAct 循环必需）
    # =========================================================

    # 完整消息历史（user / assistant / tool 三种角色）
    # add_messages：每次节点返回新消息时追加，不覆盖历史
    messages: Annotated[list[BaseMessage], add_messages]

    # 当前 ReAct 循环步数（控制最大步数终止条件）
    step_count: int

    # 当前会话已消耗 token 数（控制 token 预算终止条件）
    token_count: int

    # =========================================================
    # 会话元数据
    # =========================================================

    # 会话 ID（关联 agent_session 表）
    session_id: int

    # LangGraph thread ID（与 checkpoint 关联）
    thread_id: str

    # 用户 ID / 角色 / 商家 ID（RBAC 上下文，传递给 MCP Client）
    user_id: int
    user_role: str
    merchant_id: int | None

    # =========================================================
    # ReAct 控制标志
    # =========================================================

    # 是否需要触发 Self-Reflection（每 N 步或工具失败时置 True）
    needs_reflection: bool

    # 最近一次工具调用是否失败（触发 Reflection 的条件之一）
    last_tool_failed: bool

    # 最近一次工具失败的错误信息（供 Reflection 节点分析）
    last_tool_error: str | None

    # =========================================================
    # Auto-Compact 状态（上下文自动压缩，参考 Claude Code 的 autoCompact 设计）
    # =========================================================

    # 历史对话摘要：早期消息被压缩后的累积要点。
    # None 表示尚未触发过压缩；触发后由 compact_node 写入，
    # llm_node 在构造 system prompt 时会把它拼进去，替代被删除的原始消息。
    conversation_summary: str | None

    # 连续压缩失败次数（含「已无可压缩内容」）。达到阈值后熔断，
    # 不再尝试压缩，直接走 token_budget 硬终止，防止死循环浪费 token。
    compact_failures: int

    # =========================================================
    # HITL 状态
    # =========================================================

    # 是否正在等待人工审批（True 时 Agent 挂起，不再继续 ReAct 循环）
    pending_hitl: bool

    # 当前待审批的 action（审批通过后由恢复流程填入 approval_id）
    pending_action: dict | None

    # =========================================================
    # 终止状态
    # =========================================================

    # 最终回答（生成后 Agent 终止循环）
    final_answer: str | None

    # 终止原因（用于日志和前端展示）
    # 可能值：completed / max_steps / token_budget / pending_approval / error
    stop_reason: str | None
