"""
LangGraph ReAct Agent 状态图。

图结构（节点 + 边）定义了 Agent 的决策流程。

节点（Nodes）：
  llm_node       → 调用 LLM 决定下一步动作（Thought + Action）
  tool_node      → 执行工具调用（通过 MCP Client）
  reflection_node → Self-Reflection（每 5 步 或 工具失败时触发）
  hitl_node      → HITL 请求（高风险动作写 hitl_approval，LangGraph interrupt）
  final_node     → 生成最终回答

条件路由（Edges）：
  llm_node 之后，根据 LLM 输出的 action 类型路由：
    → tool_call  → tool_node
    → hitl       → hitl_node
    → final      → final_node
    → reflection → reflection_node

终止条件（在路由判断中检查）：
  max_steps / token_budget / pending_hitl / final_answer
"""
import structlog
from langgraph.graph import StateGraph, END
from langgraph.checkpoint.memory import MemorySaver  # 仅作为 fallback

from agent.state import AgentState
from agent import nodes
from config.settings import settings

log = structlog.get_logger(__name__)


def route_after_llm(state: AgentState) -> str:
    """
    LLM 节点之后的路由决策。

    根据 Agent 当前状态和 LLM 最新输出决定下一个节点：
    1. 检查终止条件（步数/token 超限）
    2. 检查是否需要 HITL
    3. 检查是否需要 Self-Reflection
    4. 检查是否有工具调用待执行
    5. 否则输出 Final Answer
    """
    # ---- 终止条件检查 ----
    if state["step_count"] >= settings.agent_max_steps:
        log.warning("agent_max_steps_reached", step_count=state["step_count"])
        return "final_node"  # final_node 会生成「步数超限」说明

    if state["token_count"] >= settings.session_token_budget:
        log.warning("agent_token_budget_exceeded", token_count=state["token_count"])
        return "final_node"

    # ---- HITL 检查 ----
    if state.get("pending_hitl"):
        return "hitl_node"

    # ---- Final Answer 检查 ----
    if state.get("final_answer"):
        return "final_node"

    # ---- Reflection 触发检查 ----
    # 条件：每 N 步触发一次 OR 上次工具失败
    should_reflect = (
        state.get("needs_reflection")
        or state.get("last_tool_failed")
        or (state["step_count"] > 0 and state["step_count"] % settings.reflection_interval == 0)
    )
    if should_reflect:
        return "reflection_node"

    # ---- 工具调用检查 ----
    # 检查最新的 assistant 消息是否包含 tool_calls
    messages = state.get("messages", [])
    if messages:
        last_msg = messages[-1]
        if hasattr(last_msg, "tool_calls") and last_msg.tool_calls:
            return "tool_node"

    # ---- 默认输出 Final Answer ----
    return "final_node"


def build_graph() -> StateGraph:
    """
    构建 LangGraph ReAct Agent 状态图。

    返回编译后的 CompiledGraph，可直接用于 ainvoke() 或 astream()。
    """
    builder = StateGraph(AgentState)

    # 注册节点
    builder.add_node("llm_node",        nodes.llm_node)
    builder.add_node("tool_node",       nodes.tool_node)
    builder.add_node("reflection_node", nodes.reflection_node)
    builder.add_node("hitl_node",       nodes.hitl_node)
    builder.add_node("final_node",      nodes.final_node)

    # 入口节点
    builder.set_entry_point("llm_node")

    # LLM 节点 → 条件路由
    builder.add_conditional_edges(
        "llm_node",
        route_after_llm,
        {
            "tool_node":       "tool_node",
            "reflection_node": "reflection_node",
            "hitl_node":       "hitl_node",
            "final_node":      "final_node",
        },
    )

    # 工具调用完成后回到 LLM 继续推理
    builder.add_edge("tool_node",       "llm_node")

    # Reflection 完成后回到 LLM（携带反思结果）
    builder.add_edge("reflection_node", "llm_node")

    # HITL：interrupt 挂起，此边到 END（等待外部恢复）
    builder.add_edge("hitl_node",       END)

    # Final Answer：输出结果，循环结束
    builder.add_edge("final_node",      END)

    # ---- Checkpoint Saver ----
    # 生产环境：AsyncMySQLCheckpointer（持久化到 langgraph_checkpoint 表）
    #   优点：服务重启后 HITL 状态不丢失，审批通过后仍可恢复
    # 开发 fallback：MemorySaver（进程内存，重启丢失，仅用于快速调试）
    try:
        from session.checkpointer import AsyncMySQLCheckpointer
        checkpointer = AsyncMySQLCheckpointer()
        log.info("checkpointer_type", type="AsyncMySQLCheckpointer", persistence="MySQL")
    except Exception as e:
        # DB 不可用时降级为内存（开发场景，不影响启动）
        log.warning("mysql_checkpointer_failed", error=str(e), fallback="MemorySaver")
        checkpointer = MemorySaver()

    return builder.compile(checkpointer=checkpointer)


# 全局图实例（单例，启动时编译一次）
agent_graph = build_graph()
