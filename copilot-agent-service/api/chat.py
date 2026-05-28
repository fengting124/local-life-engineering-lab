"""
Chat API：对前端提供 SSE 流式输出。

接口：
  POST /chat          → 发起新的 Agent 对话，返回 SSE 流
  POST /chat/resume   → 审批通过后恢复挂起的 Agent（HITL 恢复）

SSE 事件类型（前端按 event 字段区分处理）：
  agent_step    → Agent 进入新步骤（step_count + thought）
  tool_call     → 即将调用工具（工具名 + 参数预览）
  tool_result   → 工具返回结果
  reflection    → Self-Reflection 触发
  hitl_request  → 高风险动作申请人工审批，Agent 挂起
  final_answer  → 最终回答（流结束）
  error         → 异常（前端弹错误提示）
"""
import json
import asyncio
import structlog
from fastapi import APIRouter, Header, HTTPException
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from langchain_core.messages import HumanMessage

from agent.graph import agent_graph
from agent.state import AgentState
from config.settings import settings

log = structlog.get_logger(__name__)

router = APIRouter(prefix="/chat", tags=["chat"])


class ChatRequest(BaseModel):
    """发起新对话的请求体。"""
    message: str                    # 用户输入
    session_id: int = 0             # 会话 ID（0 = 自动创建新会话）
    thread_id: str | None = None    # LangGraph thread ID（不传则自动生成）


class ResumeRequest(BaseModel):
    """HITL 审批通过后恢复 Agent 的请求体。"""
    thread_id: str       # 挂起时的 thread ID
    approval_id: str     # 审批记录 ID
    approved: bool       # True=通过，False=拒绝


# =========================================================
# POST /chat —— 发起对话（SSE 流式返回）
# =========================================================

@router.post("")
async def chat(
    request: ChatRequest,
    x_user_id:     str = Header(..., alias="X-User-Id"),
    x_user_role:   str = Header(..., alias="X-User-Role"),
    x_merchant_id: str | None = Header(None, alias="X-Merchant-Id"),
):
    """
    发起 Agent 对话，以 SSE 格式流式返回 Agent 执行过程。

    前端使用 EventSource 或 fetch + ReadableStream 接收 SSE 事件。
    每个事件格式：
      event: <事件类型>
      data: <JSON 字符串>
    """
    user_id     = int(x_user_id)
    user_role   = x_user_role
    merchant_id = int(x_merchant_id) if x_merchant_id else None

    # ---- Guardrails 输入检查（Prompt Injection / 越权尝试）----
    from guardrails.input_checker import check_input, GuardLevel
    guard_result = check_input(request.message, user_role)
    if guard_result.level == GuardLevel.BLOCK:
        log.warning("chat_blocked_by_guardrails", user_id=user_id, reason=guard_result.reason)
        raise HTTPException(
            status_code=400,
            detail={
                "code":    "BLOCKED_BY_GUARDRAILS",
                "message": "请求被安全策略拦截",
                "reason":  guard_result.reason,
            },
        )

    # ---- Session 自动创建（session_id=0 表示新对话）----
    # 前端可以直接调 /chat 而不必先调 /sessions，更友好
    from session.manager import session_manager
    actual_session_id = request.session_id
    if actual_session_id == 0:
        actual_session_id = await session_manager.create_session(
            user_id=user_id,
            user_role=user_role,
            merchant_id=merchant_id,
            first_message=request.message,
        )
        log.info("session_auto_created", session_id=actual_session_id)

    # ---- Session 持久化：保存用户输入消息（异步，不阻塞 SSE 启动）----
    # 写 agent_message 表（role=user, step_index=0），方便后续会话回放和评测
    try:
        await session_manager.save_message(
            session_id=actual_session_id,
            role="user",
            content=request.message,
            step_index=0,
        )
    except Exception as e:
        # DB 不可用时不阻塞主流程，仅日志告警
        log.warning("session_save_failed", error=str(e))

    # 初始化 Agent 状态
    import uuid
    thread_id = request.thread_id or str(uuid.uuid4())

    initial_state: AgentState = {
        "messages":       [HumanMessage(content=request.message)],
        "step_count":     0,
        "token_count":    0,
        "session_id":     actual_session_id,
        "thread_id":      thread_id,
        "user_id":        user_id,
        "user_role":      user_role,
        "merchant_id":    merchant_id,
        "needs_reflection": False,
        "last_tool_failed": False,
        "last_tool_error":  None,
        "pending_hitl":   False,
        "pending_action": None,
        "final_answer":   None,
        "stop_reason":    None,
    }

    config = {"configurable": {"thread_id": thread_id}}

    async def event_stream():
        """LangGraph astream_events 转换为 SSE 格式。"""
        # 立即推送 session_id 和 thread_id（前端记录，方便后续 /chat/resume）
        yield _sse("session_started", {
            "session_id": str(actual_session_id),
            "thread_id":  thread_id,
        })
        try:
            # astream_events 以事件流形式输出每个节点的执行过程
            async for event in agent_graph.astream_events(
                initial_state, config=config, version="v2"
            ):
                event_type = event["event"]
                event_name = event.get("name", "")
                event_data = event.get("data", {})

                # ---- 节点开始事件 ----
                if event_type == "on_chain_start":
                    if event_name == "llm_node":
                        yield _sse("agent_step", {
                            "step": event_data.get("input", {}).get("step_count", 0),
                            "node": "llm_node",
                        })

                # ---- LLM 流式输出 ----
                elif event_type == "on_chat_model_stream":
                    chunk = event_data.get("chunk", {})
                    content = getattr(chunk, "content", "")
                    if content:
                        yield _sse("stream", {"content": content})

                # ---- 工具调用开始 ----
                elif event_type == "on_tool_start":
                    yield _sse("tool_call", {
                        "tool": event_name,
                        "args": event_data.get("input", {}),
                    })

                # ---- 工具调用结束 ----
                elif event_type == "on_tool_end":
                    output = event_data.get("output", "")
                    yield _sse("tool_result", {
                        "tool": event_name,
                        "result": str(output)[:500],  # 截断避免过大
                    })

                # ---- 节点结束（检查 Final Answer / HITL）----
                elif event_type == "on_chain_end":
                    output = event_data.get("output", {})
                    if isinstance(output, dict):
                        if output.get("final_answer"):
                            yield _sse("final_answer", {
                                "content": output["final_answer"],
                                "stop_reason": output.get("stop_reason", "completed"),
                                "thread_id": thread_id,
                            })
                        if output.get("pending_hitl"):
                            yield _sse("hitl_request", {
                                "thread_id": thread_id,
                                "action": output.get("pending_action", {}),
                                "message": "等待人工审批",
                            })

        except Exception as e:
            log.error("agent_stream_error", error=str(e), exc_info=True)
            yield _sse("error", {"message": str(e)})

    return StreamingResponse(
        event_stream(),
        media_type="text/event-stream",
        headers={
            "Cache-Control": "no-cache",
            "X-Accel-Buffering": "no",  # Nginx 关闭缓冲，保证 SSE 实时推送
        },
    )


# =========================================================
# POST /chat/resume —— HITL 审批后恢复 Agent
# =========================================================

@router.post("/resume")
async def resume(
    request: ResumeRequest,
    x_user_id:   str = Header(..., alias="X-User-Id"),
    x_user_role: str = Header(..., alias="X-User-Role"),
):
    """
    HITL 审批通过后恢复挂起的 Agent。

    完整流程：
      1. 查询 hitl_approval 记录，验证合法性（PENDING 且未过期）
      2. 按审批结果更新状态（APPROVED / REJECTED）
      3. 若通过：向 LangGraph thread 注入 approval_id，从 checkpoint 恢复 Agent
      4. 以 SSE 流式返回 Agent 恢复执行后的过程
      5. 若拒绝：向前端返回拒绝通知，不恢复 Agent

    LangGraph 恢复原理：
      LangGraph 的 checkpointer 在每个节点结束后保存状态快照。
      hitl_node 结束时快照已保存（pending_hitl=True，包含 pending_action）。
      调用 graph.ainvoke(None, config={"configurable": {"thread_id": ...}})
      LangGraph 从最新快照恢复，继续执行 hitl_node → END 之后的逻辑。
      这里我们需要额外注入 approval_id 到状态，工具调用时需要它做凭证。
    """
    from session.hitl import hitl_service

    approver_id = int(x_user_id)

    # ---- Step 1：审批拒绝 ----
    if not request.approved:
        success = await hitl_service.reject(
            approval_id=int(request.approval_id),
            approver_id=approver_id,
        )
        if not success:
            raise HTTPException(status_code=404, detail="审批记录不存在或状态非 PENDING")

        # 向前端推送拒绝通知（单次 SSE，不恢复 Agent）
        async def reject_stream():
            yield _sse("final_answer", {
                "content":    "运营已拒绝此操作，任务终止。如需其他帮助请重新发起会话。",
                "stop_reason": "hitl_rejected",
                "thread_id":   request.thread_id,
            })

        return StreamingResponse(reject_stream(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    # ---- Step 2：查询并通过审批 ----
    approval = await hitl_service.get_approval(int(request.approval_id))
    if not approval:
        raise HTTPException(status_code=404, detail="审批记录不存在")
    if approval.status != "PENDING":
        raise HTTPException(status_code=400, detail=f"审批记录状态为 {approval.status}，无法通过")

    ok = await hitl_service.approve(int(request.approval_id), approver_id)
    if not ok:
        raise HTTPException(status_code=400, detail="审批操作失败，请重试")

    # ---- Step 3：恢复 LangGraph Agent ----
    # 向 Agent state 注入审批结果，Agent 继续执行时可以从 state 获取 approval_id
    config = {"configurable": {"thread_id": request.thread_id}}

    # LangGraph resume：传入 None 输入（继续执行被 interrupt 挂起的节点）
    # pending_action 中注入 approval_id（工具调用时作为凭证传入）
    resume_input = {
        "pending_hitl":  False,      # 解除挂起
        "pending_action": {
            **(approval.action_payload or {}),
            "approval_id": str(request.approval_id),
        },
    }

    async def resume_stream():
        """从 checkpoint 恢复 Agent，继续执行，以 SSE 推送后续过程。"""
        yield _sse("agent_step", {
            "type":    "hitl_resumed",
            "message": f"审批通过（approver={approver_id}），Agent 继续执行",
        })
        try:
            async for event in agent_graph.astream_events(
                resume_input, config=config, version="v2"
            ):
                event_type = event["event"]
                event_name = event.get("name", "")
                event_data = event.get("data", {})

                if event_type == "on_chat_model_stream":
                    chunk = event_data.get("chunk", {})
                    content = getattr(chunk, "content", "")
                    if content:
                        yield _sse("stream", {"content": content})

                elif event_type == "on_tool_start":
                    yield _sse("tool_call", {"tool": event_name, "args": event_data.get("input", {})})

                elif event_type == "on_tool_end":
                    yield _sse("tool_result", {
                        "tool": event_name, "result": str(event_data.get("output", ""))[:500]
                    })

                elif event_type == "on_chain_end":
                    output = event_data.get("output", {})
                    if isinstance(output, dict) and output.get("final_answer"):
                        yield _sse("final_answer", {
                            "content":    output["final_answer"],
                            "stop_reason": output.get("stop_reason", "completed"),
                            "thread_id":   request.thread_id,
                        })

        except Exception as e:
            log.error("resume_stream_error", error=str(e), exc_info=True)
            yield _sse("error", {"message": str(e)})

    return StreamingResponse(
        resume_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


def _sse(event: str, data: dict) -> str:
    """格式化为 SSE 事件字符串。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
