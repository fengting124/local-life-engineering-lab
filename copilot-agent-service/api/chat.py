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
    session_id: int                 # 会话 ID（由前端或 session API 创建）
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

    # 初始化 Agent 状态
    import uuid
    thread_id = request.thread_id or str(uuid.uuid4())

    initial_state: AgentState = {
        "messages":       [HumanMessage(content=request.message)],
        "step_count":     0,
        "token_count":    0,
        "session_id":     request.session_id,
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
    审批通过后恢复挂起的 Agent。

    流程：
    1. 验证 approval_id 有效且 approved=True
    2. 从 MySQL 读取对应的 checkpoint_id
    3. 调用 LangGraph resume（从 checkpoint 恢复 thread）
    4. Agent 继续执行挂起前的下一步动作
    5. 以 SSE 流式返回后续执行过程
    """
    if not request.approved:
        # 审批拒绝：通知 Agent 终止
        # 实际实现：更新 hitl_approval.status = REJECTED，发送拒绝通知
        return {"status": "rejected", "message": "审批已拒绝，Agent 任务终止"}

    # 实际实现：
    # 1. approval = await db.get_hitl_approval(request.approval_id)
    # 2. assert approval.status == "PENDING"
    # 3. await db.update_hitl_approval(approval.id, status="APPROVED", approver_id=user_id)
    # 4. config = {"configurable": {"thread_id": request.thread_id}}
    # 5. resume_input = {"approved": True, "approval_id": request.approval_id}
    # 6. return StreamingResponse(agent_graph.astream(resume_input, config), ...)

    return {
        "status": "resumed",
        "thread_id": request.thread_id,
        "message": "审批通过，Agent 继续执行（SSE 恢复功能开发中）",
    }


def _sse(event: str, data: dict) -> str:
    """格式化为 SSE 事件字符串。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
