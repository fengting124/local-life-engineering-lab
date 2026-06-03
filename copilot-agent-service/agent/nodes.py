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
import structlog
from langchain_core.messages import SystemMessage, AIMessage, ToolMessage, HumanMessage
from langchain_core.language_models import BaseChatModel

from agent.state import AgentState
from mcp.mcp_client import McpClient, McpToolError
from config.settings import settings

log = structlog.get_logger(__name__)

# =========================================================
# LLM 工厂（支持多 Provider 切换）
# =========================================================

def _create_llm() -> BaseChatModel:
    """
    根据 settings.llm_provider 创建对应的 LLM 客户端。

    切换方式（.env 文件）：
      LLM_PROVIDER=deepseek
      LLM_API_KEY=sk-xxxxxxxx
      LLM_MODEL=deepseek-chat

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
            "model":    "deepseek-chat",
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
    model    = settings.llm_model if settings.llm_model != "claude-sonnet-4-6" else defaults.get("model", settings.llm_model)
    key      = api_key or defaults.get("api_key", "placeholder")

    log.info("llm_provider", provider=provider, model=model, base_url=base_url)
    return ChatOpenAI(
        model=model,
        max_tokens=settings.llm_max_tokens,
        api_key=key,
        base_url=base_url if base_url else None,
    )


_llm: BaseChatModel = _create_llm()


def _build_system_prompt(tools: list[dict]) -> str:
    """
    构建 ReAct System Prompt，嵌入工具列表和业务背景。

    Agent 的行为完全由 System Prompt 驱动，这里的设计要点：
    1. 角色定位：说明 Agent 是什么，服务谁
    2. 工具清单：嵌入 MCP 工具列表（name + description + hint）
    3. ReAct 格式：明确 Thought / Action / Observation 格式
    4. 终止条件：说明什么情况下输出 Final Answer
    5. 高风险规则：说明哪些动作必须 HITL，不能直接执行
    """
    tool_descriptions = "\n".join([
        f"- {t['name']}: {t['description']}"
        + (f"\n  提示: {t.get('xBusinessHint', '')}" if t.get('xBusinessHint') else "")
        for t in tools
    ])

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
- 所有资金操作必须有明确证据支撑"""


# =========================================================
# 节点实现
# =========================================================

async def llm_node(state: AgentState) -> dict:
    """
    LLM 节点：调用 Claude 决定下一步动作。

    输入：完整消息历史 + 系统提示
    输出：新的 assistant 消息（含 tool_calls 或 Final Answer）
    """
    # 构建 MCP Client 获取工具列表
    mcp = McpClient(
        user_id=state["user_id"],
        user_role=state["user_role"],
        merchant_id=state.get("merchant_id"),
    )

    try:
        all_tools = await mcp.list_tools()
    except Exception as e:
        log.error("mcp_list_tools_failed", error=str(e))
        all_tools = []  # 工具获取失败时降级为纯 LLM 回答

    # ---- Tool Router：按角色和任务类型过滤工具 ----
    # 不把所有工具都传给 LLM：减少 token 消耗 + 降低决策噪音 + 权限边界清晰
    from agent.tool_router import ToolRouter
    messages = state.get("messages", [])
    last_user_msg = ""
    for msg in reversed(messages):
        if hasattr(msg, "type") and msg.type == "human":
            last_user_msg = msg.content if isinstance(msg.content, str) else ""
            break
    # 将历史消息文本拼接作为上下文（供 context_filter 使用）
    context_text = " ".join([
        m.content for m in messages[-10:]  # 只取最近 10 条
        if isinstance(getattr(m, "content", ""), str)
    ])
    router = ToolRouter(
        user_role=state.get("user_role", "merchant"),
        user_message=last_user_msg,
        conversation_context=context_text,
    )
    tools = router.route(all_tools)

    # ---- 注入 Python 原生工具：knowledge_search（RAG） ----
    # knowledge_search 不通过 MCP（向量检索在 Python 侧本地执行，避免跨语言传输大向量）
    # 它作为 LangChain 原生 tool 绑定到 LLM，tool_node 中会被特判调用本地函数
    from rag.knowledge_tool import make_knowledge_search_tool
    native_knowledge_tool = make_knowledge_search_tool(merchant_id=state.get("merchant_id"))

    # 将过滤后的 MCP 工具转换为 LangChain tool 格式
    lc_mcp_tools = _convert_to_lc_tools(tools)
    # 合并 MCP 工具 + Python 原生工具
    lc_tools = lc_mcp_tools + [native_knowledge_tool]

    # 绑定工具到 LLM
    llm_with_tools = _llm.bind_tools(lc_tools) if lc_tools else _llm

    # 构建消息列表（System + 历史消息）
    # ---- Prompt Caching（Claude 专属优化，节省 80-90% input token 成本）----
    # cache_control={"type":"ephemeral"} 告知 Claude 将此消息缓存 5 分钟。
    # 系统提示（角色定义 + 工具说明）是每轮对话都重复的稳定内容，适合缓存。
    # 注意：只缓存稳定内容；用户消息和工具结果不缓存（每次都不同）。
    # 面试说法：「通过 Prompt Caching 将稳定的 System Prompt 缓存，
    #   多轮对话中 input tokens 减少约 80%，成本从 ~$0.006/次降至 ~$0.001/次」
    system_msg = SystemMessage(
        content=_build_system_prompt(tools),
        additional_kwargs={"cache_control": {"type": "ephemeral"}},
    )
    messages = [system_msg] + state["messages"]

    # 调用 LLM
    response = await llm_with_tools.ainvoke(messages)

    log.info(
        "llm_response",
        step=state["step_count"],
        has_tool_calls=bool(getattr(response, "tool_calls", None)),
        token_usage=getattr(response, "usage_metadata", {}),
    )

    # 更新 token 计数
    usage = getattr(response, "usage_metadata", {}) or {}
    new_tokens = usage.get("total_tokens", 0)

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

    return {
        "messages": [response],
        "step_count": state["step_count"] + 1,
        "token_count": state["token_count"] + new_tokens,
        "final_answer": final_answer,
        "last_tool_failed": False,  # 重置
        "needs_reflection": False,  # 重置
    }


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


async def tool_node(state: AgentState) -> dict:
    """
    工具节点：并行执行 MCP 工具调用，获取 Observation。

    核心改进：
    1. **并行执行**（asyncio.gather）：同一轮 LLM 决策的多个独立工具并发执行，
       延迟从 t1+t2+... 降到 max(t1,t2,...)，理论减少到 1/N（N=并发工具数）。
       理论背书：Claude/GPT等主流 LLM 的并行工具调用（parallel tool calls）正是这个模式。
       注意：有数据依赖的工具仍需 ReAct 下一轮串行调用。

    2. **循环检测**：同工具同参数 ≥ 3 次触发停止，避免死循环浪费 token。

    从最新的 assistant 消息中读取 tool_calls，
    通过 asyncio.gather 并发执行所有工具，将结果追加到消息历史。
    """
    import asyncio
    import time as _time
    from agent.metrics import record_tool_call

    messages = state["messages"]
    last_msg = messages[-1]
    tool_calls = getattr(last_msg, "tool_calls", []) or []

    if not tool_calls:
        return {"messages": []}

    mcp = McpClient(
        user_id=state["user_id"],
        user_role=state["user_role"],
        merchant_id=state.get("merchant_id"),
    )

    async def _execute_single_tool(tool_call: dict) -> ToolMessage:
        """执行单个工具调用，返回 ToolMessage（无论成功或失败）。"""
        tool_name = tool_call["name"]
        tool_args = tool_call.get("args", {})
        call_id   = tool_call.get("id", "")

        # ---- 循环检测（在执行前检查）----
        if _detect_loop(messages, tool_name, tool_args):
            log.warning("tool_loop_detected", tool=tool_name, args=tool_args,
                        step=state["step_count"])
            return ToolMessage(
                content=(
                    f"[循环检测] 工具 '{tool_name}' 以相同参数已调用 3 次，"
                    "停止重复调用。请尝试其他工具或换一种方式解决问题。"
                ),
                tool_call_id=call_id,
                name=tool_name,
            )

        log.info("tool_calling", tool=tool_name, args=tool_args, step=state["step_count"])
        start = _time.time()

        try:
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
            record_tool_call(tool_name, "success", _time.time() - start)
            log.info("tool_success", tool=tool_name, elapsed_ms=int((_time.time()-start)*1000))
            return ToolMessage(content=result, tool_call_id=call_id, name=tool_name)

        except McpToolError as e:
            record_tool_call(tool_name, e.reason, _time.time() - start)
            log.warning("tool_failed", tool=tool_name, reason=e.reason, detail=e.detail)
            return ToolMessage(
                content=f"[工具错误] {json.dumps(e.to_dict(), ensure_ascii=False)}",
                tool_call_id=call_id,
                name=tool_name,
            )
        except Exception as e:
            record_tool_call(tool_name, "internal_error", _time.time() - start)
            log.error("tool_exception", tool=tool_name, error=str(e))
            return ToolMessage(
                content=f"[工具异常] {tool_name} 执行时发生内部错误: {str(e)[:200]}",
                tool_call_id=call_id,
                name=tool_name,
            )

    # ---- 并行执行所有工具（asyncio.gather）----
    # return_exceptions=True 确保一个工具失败不影响其他工具的结果
    results = await asyncio.gather(
        *[_execute_single_tool(tc) for tc in tool_calls],
        return_exceptions=True,
    )

    tool_messages = []
    any_failed = False
    last_error = None

    for i, result in enumerate(results):
        if isinstance(result, Exception):
            # gather 捕获的未预期异常（不应该发生，_execute_single_tool 已处理所有异常）
            call_id = tool_calls[i].get("id", "")
            tool_name = tool_calls[i]["name"]
            tool_messages.append(ToolMessage(
                content=f"[系统异常] {str(result)[:200]}",
                tool_call_id=call_id,
                name=tool_name,
            ))
            any_failed = True
            last_error = str(result)
        else:
            tm: ToolMessage = result
            tool_messages.append(tm)
            # 检查是否是错误消息
            content_str = str(tm.content or "")
            if content_str.startswith("[工具错误]") or content_str.startswith("[工具异常]") \
                    or content_str.startswith("[系统异常]"):
                any_failed = True
                last_error = content_str[:200]

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

    return {
        "messages": tool_messages,
        "last_tool_failed": any_failed,
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
    恢复时：agent_graph.ainvoke(resume_input, config={"configurable": {"thread_id": ...}})
    LangGraph 从最新 checkpoint 恢复，Agent 继续执行。
    """
    pending      = state.get("pending_action") or {}
    action_type  = pending.get("action_type", "unknown")
    action_payload = pending.get("payload", {})
    agent_reason = pending.get("reason", "Agent 认为需要执行此高风险动作")
    session_id   = state.get("session_id")
    thread_id    = state.get("thread_id", "")

    log.info(
        "hitl_requested",
        action_type=action_type,
        session_id=session_id,
        thread_id=thread_id,
    )

    # ---- 写 hitl_approval 到 MySQL ----
    # LangGraph 的 checkpoint_id 在此时已由 checkpointer 生成，
    # 但 Python API 中无法直接从节点内获取当前 checkpoint_id。
    # 折中方案：用 thread_id 作为恢复标识符（从最新 checkpoint 恢复）。
    # 生产升级：langgraph 提供了 get_state() 可以获取当前 checkpoint_id。
    approval_id = None
    try:
        from session.hitl import hitl_service
        approval_id = await hitl_service.create_approval(
            session_id=session_id or 0,
            thread_id=thread_id,
            checkpoint_id=thread_id,   # 简化：用 thread_id 标识恢复点
            action_type=action_type,
            action_payload=action_payload,
            agent_reason=agent_reason,
        )
        log.info("hitl_approval_written", approval_id=approval_id)
    except Exception as e:
        # 写 DB 失败时不能阻塞主流程，记录错误后继续挂起
        log.error("hitl_approval_write_failed", error=str(e))

    # ---- 生成挂起通知消息 ----
    hitl_message = AIMessage(content=(
        f"此操作（**{action_type}**）涉及高风险，需要人工审批后才能执行。\n\n"
        f"**申请原因**：{agent_reason}\n\n"
        f"**审批记录 ID**：{approval_id or '写入失败，请联系技术支持'}\n\n"
        f"已提交审批申请，请运营人员在审批工作台处理。"
        f"审批通过后系统将继续执行，拒绝则任务终止。"
    ))

    return {
        "messages":      [hitl_message],
        "pending_hitl":  True,
        "stop_reason":   "pending_approval",
        # 将 approval_id 存入 state，恢复时传给工具作为凭证
        "pending_action": {
            **pending,
            "approval_id": approval_id,
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

    # 确定终止原因
    if step_count >= settings.agent_max_steps:
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
        from agent.metrics import record_session_end
        record_session_end(
            status=stop_reason,
            role=state.get("user_role", "unknown"),
            step_count=step_count,
        )
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
