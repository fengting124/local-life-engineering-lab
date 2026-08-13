"""
Chat API：对前端提供 SSE 流式输出。

接口：
  POST /chat          → 发起新的 Agent 对话，返回 SSE 流
  POST /chat/resume   → 审批通过后恢复挂起的 Agent（HITL 恢复）

SSE 事件类型（前端按 event 字段区分处理）：
  agent_step    → Agent 进入新步骤（step_count + thought）
  tool_call     → 即将调用工具（工具名 + 参数 key 摘要，不输出参数值）
  tool_result   → 工具调用完成状态（不输出原始结果）
  reflection    → Self-Reflection 触发
  hitl_request  → 高风险动作申请人工审批，Agent 挂起
  final_answer  → 最终回答（流结束）
  error         → 安全错误摘要（详细异常只写日志）
"""
import json
import asyncio
import structlog
from datetime import date, datetime, timezone
from typing import Any
from zoneinfo import ZoneInfo
from fastapi import APIRouter, Header, HTTPException, Query
from fastapi.responses import StreamingResponse
from pydantic import BaseModel
from langchain_core.messages import HumanMessage
from structlog.contextvars import get_contextvars

from agent.graph import agent_graph
from agent.state import AgentState
from api.header_utils import parse_optional_merchant_id_header, parse_user_id_header
from config.settings import settings
from session.hitl import HitlResumeError, hitl_service
from session.manager import AsyncSessionLocal, session_manager
from session.models import AgentSession
from session.runtime import runtime_store
from agent.trace import SpanTimer

log = structlog.get_logger(__name__)

router = APIRouter(prefix="/chat", tags=["chat"])

SENSITIVE_ARG_KEY_FRAGMENTS = (
    "key",
    "token",
    "secret",
    "password",
    "authorization",
    "cookie",
)


def _safe_arg_keys(raw_args: Any) -> list[str]:
    """Return non-sensitive argument keys for UI explainability without values."""
    if not isinstance(raw_args, dict):
        return []
    return sorted(
        key for key in raw_args.keys()
        if not any(fragment in str(key).lower() for fragment in SENSITIVE_ARG_KEY_FRAGMENTS)
    )


def _safe_tool_call_event(tool_name: str, raw_args: Any) -> dict:
    """Build a browser-safe tool call event."""
    return {
        "tool": tool_name,
        "arg_keys": _safe_arg_keys(raw_args),
    }


def _safe_tool_result_event(tool_name: str, raw_output: Any) -> dict:
    """Build a browser-safe tool result event without leaking raw tool output."""
    return {
        "tool": tool_name,
        "status": "completed",
    }


def _safe_error_event(error: Exception) -> dict:
    """Build a browser-safe error event while keeping details in server logs."""
    return {
        "code": "AGENT_STREAM_ERROR",
        "message": "Agent 执行过程中出现错误，请稍后重试或联系管理员",
    }


def _safe_hitl_request_event(thread_id: str, pending_action: Any) -> dict:
    """Build a browser-safe HITL request event without action payload values."""
    action = pending_action if isinstance(pending_action, dict) else {}
    approval_id = action.get("approval_id")
    return {
        "thread_id": thread_id,
        "approval_id": str(approval_id) if approval_id is not None else None,
        "action": {"action_type": action.get("action_type", "high_risk_action")},
        "message": "等待人工审批",
    }


def _finish_request_measurement(
    timer: SpanTimer,
    *,
    status: str,
    route_mode: str,
    route_task_type: str,
    stop_reason: str,
    session_id: int,
    thread_id: str,
    run_id: str | None,
    output: dict[str, Any] | None = None,
) -> None:
    """Close one request measurement and emit a payload-free run summary."""
    state = output or {}
    duration_ms = getattr(timer, "elapsed_ms", 0)
    attrs = {
        "route_mode": route_mode,
        "route_task_type": route_task_type,
        "stop_reason": stop_reason,
        "session_id": session_id,
        "thread_id": thread_id,
        "run_id": run_id,
        "llm_call_count": state.get("llm_call_count", 0),
        "llm_input_tokens": state.get("llm_input_tokens", 0),
        "llm_output_tokens": state.get("llm_output_tokens", 0),
        "llm_usage_missing_count": state.get("llm_usage_missing_count", 0),
        "tool_call_count": state.get("tool_call_count", 0),
    }
    if not timer.finish(status=status, **attrs):
        return
    try:
        log.info("agent_run_measured", duration_ms=duration_ms, status=status, **attrs)
    except Exception:
        pass


# =========================================================
# Fast Path：简单确定性查询直接调工具，跳过 LLM 推理
# =========================================================

import re

# 指标词（触发 analytics 场景）
_METRIC_WORDS = re.compile(
    r"卖了多少|销售额|gmv|营业额|订单量|订单数|成交额|收入|营收|销售|销量", re.IGNORECASE
)

# 时间词匹配
_TODAY_RE = re.compile(r"今天|今日|today", re.IGNORECASE)
_YESTERDAY_RE = re.compile(r"昨天|昨日|yesterday", re.IGNORECASE)
_MONTH_TO_DATE_RE = re.compile(r"本月|这个月|这月|this month", re.IGNORECASE)
_BUSINESS_TIMEZONE = ZoneInfo("Asia/Shanghai")


def _business_today(now: datetime | None = None) -> date:
    instant = now or datetime.now(timezone.utc)
    if instant.tzinfo is None:
        instant = instant.replace(tzinfo=timezone.utc)
    return instant.astimezone(_BUSINESS_TIMEZONE).date()


def _classify_fast_path(
    message: str,
    user_role: str,
    merchant_id: int | None,
    today: date | None = None,
) -> tuple[dict[str, str], str] | None:
    """Return deterministic analytics arguments without performing I/O."""
    timer = SpanTimer("router.classify", "router", route_candidate="fast_path")
    status = "ok"
    error_type = None
    try:
        if user_role != "merchant" or merchant_id is None:
            return None
        msg = message.strip()
        if not _METRIC_WORDS.search(msg):
            return None
        if _MONTH_TO_DATE_RE.search(msg):
            current_date = today or _business_today()
            return {
                "start_date": current_date.replace(day=1).isoformat(),
                "end_date": current_date.isoformat(),
            }, "本月"
        if _TODAY_RE.search(msg):
            return {"date": "today"}, "今天"
        if _YESTERDAY_RE.search(msg):
            return {"date": "yesterday"}, "昨天"
        return None
    except Exception as exc:
        status = "error"
        error_type = type(exc).__name__
        raise
    finally:
        timer.finish(status=status, **({"error_type": error_type} if error_type else {}))


async def _try_fast_path(
    message: str,
    user_role: str,
    merchant_id: int | None,
    session_id: int,
    user_id: int,
    today: date | None = None,
) -> str | None:
    """
    Fast Path：识别简单确定性查询，直接调 MCP 工具返回，跳过 LLM 推理。

    覆盖场景（约 60% 的商家日常查询）：
    - 「今天/昨天 + 销售额/订单量/GMV/营业额」→ shop_metrics_query(date=today/yesterday)

    不覆盖场景（仍走 ReAct）：
    - 订单异常排查（需要多步推理）
    - 活动配置（需要理解业务上下文）
    - 模糊问题（「为什么单量下降」）

    Tradeoff：
    - 规则覆盖有限，复杂语义会 fallback 到 ReAct（无损，不会答错）
    - 误判简单问题为复杂时只是多耗 token，不影响正确性
    - 简单查询从 ~3s 降至 <500ms，节省约 2000 input tokens/次

    :return: 格式化的回答字符串，None 表示不走 Fast Path
    """
    decision = _classify_fast_path(
        message, user_role, merchant_id, today=today
    )
    if decision is None:
        return None
    arguments, date_label = decision

    # 直接调 MCP 工具
    try:
        from mcp.mcp_client import McpClient
        mcp = McpClient(user_id=user_id, user_role=user_role, merchant_id=merchant_id)
        raw = await mcp.call_tool(
            tool_name="shop_metrics_query",
            arguments=arguments,
            session_id=session_id,
        )
        import json as _json
        try:
            data = _json.loads(raw)
        except Exception:
            return None

        gmv = data.get("gmv", 0)
        order_count = data.get("order_count", 0)
        coupon_used = data.get("coupon_used_count", 0)
        cancel_count = data.get("cancel_count", 0)

        # 金额单位：分 → 元
        gmv_yuan = gmv / 100 if gmv else 0

        if order_count == 0:
            return f"{date_label}暂无订单数据，可能还没有完成的订单。"

        answer = (
            f"{date_label}经营数据：\n"
            f"- 订单数：**{order_count}** 笔\n"
            f"- GMV（成交额）：**{gmv_yuan:.2f} 元**\n"
            f"- 优惠券核销：**{coupon_used}** 张\n"
            f"- 取消订单：**{cancel_count}** 笔\n\n"
            f"如需了解具体某笔订单或排查异常，请告诉我订单号。"
        )
        log.info("fast_path_hit", date_range=arguments, merchant_id=merchant_id,
                 order_count=order_count, gmv=gmv)
        return answer

    except Exception as e:
        # Fast Path 失败时 fallback 到 ReAct（不报错给用户）
        log.warning("fast_path_failed", error=str(e))
        return None


class ChatRequest(BaseModel):
    """发起新对话的请求体。"""
    message: str                    # 用户输入
    session_id: int = 0             # 会话 ID（0 = 自动创建新会话）
    thread_id: str | None = None    # LangGraph thread ID（不传则自动生成）


class ResumeRequest(BaseModel):
    """HITL 审批通过后恢复 Agent 的请求体。"""
    thread_id: str | None = None   # 挂起时的 thread ID（可选，服务端以 approval 记录为准）
    approval_id: str     # 审批记录 ID
    approved: bool       # True=通过，False=拒绝


class RuntimeEventResponse(BaseModel):
    sequence_index: int
    event_type: str
    event_name: str | None = None
    payload: dict[str, Any] | None = None
    trace_id: str | None = None
    created_at: str | None = None


class RuntimeEventsResponse(BaseModel):
    run_id: str
    session_id: int
    thread_id: str
    trace_id: str | None = None
    status: str
    next_after_sequence: int
    events: list[RuntimeEventResponse]


async def _assert_session_owned_by_user(session_id: int, user_id: int) -> AgentSession:
    """
    校验会话归属，防止客户端拿别人的 session_id 污染上下文。
    """
    async with AsyncSessionLocal() as db:
        session = await db.get(AgentSession, session_id)

    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if session.user_id != user_id:
        raise HTTPException(status_code=403, detail="无权访问此会话")
    return session


async def _safe_create_runtime_run(
    *,
    session_id: int,
    thread_id: str,
    user_id: int,
    user_role: str,
    merchant_id: int | None,
    input_message: str,
    trace_id: str | None,
) -> str | None:
    try:
        return await runtime_store.create_run(
            session_id=session_id,
            thread_id=thread_id,
            user_id=user_id,
            user_role=user_role,
            merchant_id=merchant_id,
            input_message=input_message,
            trace_id=trace_id,
        )
    except Exception as e:
        log.warning("agent_run_create_failed", error=str(e))
        return None


async def _safe_mark_runtime_status(
    run_id: str | None,
    status: str,
    *,
    error_message: str | None = None,
) -> None:
    if not run_id:
        return
    try:
        await runtime_store.mark_run_status(
            run_id,
            status,
            error_message=error_message,
        )
    except Exception as e:
        log.warning("agent_run_status_update_failed", run_id=run_id, status=status, error=str(e))


async def _safe_append_runtime_event(
    *,
    run_id: str | None,
    session_id: int,
    thread_id: str,
    sequence_index: int,
    event_type: str,
    event_name: str | None,
    payload: dict,
    trace_id: str | None,
) -> None:
    if not run_id:
        return
    try:
        await runtime_store.append_event(
            run_id=run_id,
            session_id=session_id,
            thread_id=thread_id,
            sequence_index=sequence_index,
            event_type=event_type,
            event_name=event_name,
            payload=payload,
            trace_id=trace_id,
        )
    except Exception as e:
        log.warning("agent_event_append_failed", run_id=run_id, event_type=event_type, error=str(e))


async def _safe_get_waiting_runtime_run(thread_id: str) -> Any | None:
    try:
        return await runtime_store.get_latest_waiting_run_by_thread(thread_id)
    except Exception as e:
        log.warning("agent_waiting_run_lookup_failed", thread_id=thread_id, error=str(e))
        return None


async def _safe_get_latest_runtime_run(thread_id: str) -> Any | None:
    try:
        return await runtime_store.get_latest_run_by_thread(thread_id)
    except Exception as e:
        log.warning("agent_latest_run_lookup_failed", thread_id=thread_id, error=str(e))
        return None


async def _safe_next_runtime_sequence(run_id: str | None) -> int:
    if not run_id:
        return 0
    try:
        return await runtime_store.next_sequence(run_id)
    except Exception as e:
        log.warning("agent_event_sequence_lookup_failed", run_id=run_id, error=str(e))
        return 0


def _serialize_runtime_event(event: Any) -> RuntimeEventResponse:
    created_at = getattr(event, "created_at", None)
    return RuntimeEventResponse(
        sequence_index=event.sequence_index,
        event_type=event.event_type,
        event_name=event.event_name,
        payload=event.payload,
        trace_id=event.trace_id,
        created_at=created_at.isoformat() if created_at is not None else None,
    )


@router.get("/runs/{run_id}/events", response_model=RuntimeEventsResponse)
async def replay_run_events(
    run_id: str,
    after_sequence: int = Query(-1, ge=-1),
    limit: int = Query(100, ge=1, le=200),
    x_user_id: str = Header(..., alias="X-User-Id"),
    x_user_role: str = Header(..., alias="X-User-Role"),
):
    """
    回放 Agent run 的持久化事件。

    用于浏览器 SSE 断开后，从最后已消费的 sequence_index 继续拉取事件。
    当前用户侧接口只允许 run 创建者读取；运营审计视角后续单独做。
    """
    user_id = parse_user_id_header(x_user_id)
    _ = x_user_role

    run = await runtime_store.get_run(run_id)
    if run is None:
        raise HTTPException(status_code=404, detail="Agent run 不存在")
    if run.user_id != user_id:
        raise HTTPException(status_code=403, detail="无权访问此 Agent run")

    events = await runtime_store.list_events(
        run_id,
        after_sequence=after_sequence,
        limit=limit,
    )
    serialized = [_serialize_runtime_event(event) for event in events]
    next_after_sequence = serialized[-1].sequence_index if serialized else after_sequence

    return RuntimeEventsResponse(
        run_id=run.id,
        session_id=run.session_id,
        thread_id=run.thread_id,
        trace_id=run.trace_id,
        status=run.status,
        next_after_sequence=next_after_sequence,
        events=serialized,
    )


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
    user_id     = parse_user_id_header(x_user_id)
    user_role   = x_user_role
    merchant_id = parse_optional_merchant_id_header(x_merchant_id)
    request_trace_id = get_contextvars().get("trace_id")

    # ---- Guardrails 输入检查（Prompt Injection / 越权尝试）----
    from guardrails.input_checker import check_input, GuardLevel
    guard_result = check_input(request.message, user_role)
    if guard_result.level == GuardLevel.BLOCK:
        log.warning(
            "security_audit",
            event_type="guardrails_blocked",
            user_id=user_id,
            user_role=user_role,
            merchant_id=merchant_id,
            reason=guard_result.reason,
            pattern=guard_result.pattern,
            snippet=request.message[:120],
            route_mode="terminal",
            stop_reason="guardrail_blocked",
        )
        raise HTTPException(
            status_code=400,
            detail={
                "code":    "BLOCKED_BY_GUARDRAILS",
                "message": "请求被安全策略拦截",
                "reason":  guard_result.reason,
            },
        )

    request_timer = SpanTimer(
        "request.total",
        "server",
        user_role=user_role,
        trace_id=request_trace_id,
    )
    session_timer = SpanTimer("session.prepare", "session")

    # ---- Session 自动创建（session_id=0 表示新对话）----
    # 前端可以直接调 /chat 而不必先调 /sessions，更友好
    actual_session_id = request.session_id
    try:
        if actual_session_id == 0:
            actual_session_id = await session_manager.create_session(
                user_id=user_id,
                user_role=user_role,
                merchant_id=merchant_id,
                first_message=request.message,
            )
            log.info("session_auto_created", session_id=actual_session_id)
        else:
            await _assert_session_owned_by_user(actual_session_id, user_id)

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
            log.warning("session_save_failed", error=str(e))

    # 初始化 thread/run：thread_id 给 LangGraph；run_id 给企业运行时排障入口
        import uuid
        thread_id = request.thread_id or str(uuid.uuid4())
        run_id = await _safe_create_runtime_run(
            session_id=actual_session_id,
            thread_id=thread_id,
            user_id=user_id,
            user_role=user_role,
            merchant_id=merchant_id,
            input_message=request.message,
            trace_id=request_trace_id,
        )
    except Exception as exc:
        session_timer.finish(status="error", error_type=type(exc).__name__)
        request_timer.finish(status="error", stop_reason="setup_error")
        raise
    session_timer.finish(status="ok")

    # ---- Fast Path 路由：简单确定性查询跳过 ReAct 完整循环 ----
    # 动机（面试说法）：「简单指标查询（今天/昨天/本月 + 销售额/订单数）
    #   不需要 LLM 推理，直接调工具返回。绕过 ReAct 可将延迟从 3s 降至 <500ms，
    #   同时节省约 2000 input tokens/次。这是 Agent/Workflow 混合架构的体现。」
    #
    # 覆盖场景：~60% 的商家常用查询（简单的经营数据看板类问题）
    # 不覆盖场景：需要多步推理的订单排查、活动配置辅助（仍走完整 ReAct）
    fast_path_result = await _try_fast_path(
        message=request.message,
        user_role=user_role,
        merchant_id=merchant_id,
        session_id=actual_session_id,
        user_id=user_id,
    )
    if fast_path_result is not None:
        async def fast_stream():
            seq = 0
            await _safe_mark_runtime_status(run_id, "RUNNING")
            payload = {
                "session_id": str(actual_session_id),
                "thread_id":  thread_id,
                "run_id":     run_id,
                "trace_id":   request_trace_id,
            }
            await _safe_append_runtime_event(
                run_id=run_id,
                session_id=actual_session_id,
                thread_id=thread_id,
                sequence_index=seq,
                event_type="session_started",
                event_name=None,
                payload=payload,
                trace_id=request_trace_id,
            )
            seq += 1
            yield _sse("session_started", payload)

            payload = {
                "content":    fast_path_result,
                "stop_reason": "fast_path",
                "thread_id":   thread_id,
            }
            await _safe_append_runtime_event(
                run_id=run_id,
                session_id=actual_session_id,
                thread_id=thread_id,
                sequence_index=seq,
                event_type="final_answer",
                event_name=None,
                payload=payload,
                trace_id=request_trace_id,
            )
            await _safe_mark_runtime_status(run_id, "COMPLETED")
            _finish_request_measurement(
                request_timer,
                status="ok",
                route_mode="fast_path",
                route_task_type="analytics_query",
                stop_reason="fast_path",
                session_id=actual_session_id,
                thread_id=thread_id,
                run_id=run_id,
                output={"tool_call_count": 1},
            )
            yield _sse("final_answer", payload)

        return StreamingResponse(fast_stream(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    from agent.evidence_gate import initial_evidence_state
    from agent.tool_router import classify_request

    route_timer = SpanTimer("router.classify", "router", route_candidate="react")
    try:
        route_decision = classify_request(user_role, request.message)
    except Exception as exc:
        route_timer.finish(status="error", error_type=type(exc).__name__)
        request_timer.finish(status="error", stop_reason="router_error")
        raise
    else:
        route_timer.finish(status="ok")
    route_state = route_decision.to_state()
    evidence_state = initial_evidence_state(route_decision)

    # 初始化 Agent 状态
    initial_state: AgentState = {
        "messages":       [HumanMessage(content=request.message)],
        "step_count":     0,
        "token_count":    0,
        "llm_call_count": 0,
        "llm_input_tokens": 0,
        "llm_output_tokens": 0,
        "llm_usage_missing_count": 0,
        "tool_call_count": 0,
        "tool_call_counts": {},
        "tool_signature_counts": {},
        "session_id":     actual_session_id,
        "thread_id":      thread_id,
        "user_id":        user_id,
        "user_role":      user_role,
        "merchant_id":    merchant_id,
        "needs_reflection": False,
        "last_tool_failed": False,
        "last_tool_error":  None,
        "conversation_summary": None,
        "compact_failures": 0,
        "pending_hitl":   False,
        "pending_action": None,
        "final_answer":   None,
        "stop_reason":    None,
        "tool_budget_exhausted": False,
        "tool_budget_reason": None,
        "policy_denied_tool": None,
        **route_state,
        **evidence_state,
    }

    config = {"configurable": {"thread_id": thread_id}}

    async def event_stream():
        """LangGraph astream_events 转换为 SSE 格式。"""
        # 立即推送 session_id 和 thread_id（前端记录，方便后续 /chat/resume）
        seq = 0
        terminal_recorded = False
        run_totals = {
            "llm_call_count": 0,
            "llm_input_tokens": 0,
            "llm_output_tokens": 0,
            "llm_usage_missing_count": 0,
            "tool_call_count": 0,
        }
        graph_timer = SpanTimer(
            "graph.total", "graph", session_id=actual_session_id, thread_id=thread_id
        )
        await _safe_mark_runtime_status(run_id, "RUNNING")
        payload = {
            "session_id": str(actual_session_id),
            "thread_id":  thread_id,
            "run_id":     run_id,
            "trace_id":   request_trace_id,
        }
        await _safe_append_runtime_event(
            run_id=run_id,
            session_id=actual_session_id,
            thread_id=thread_id,
            sequence_index=seq,
            event_type="session_started",
            event_name=None,
            payload=payload,
            trace_id=request_trace_id,
        )
        seq += 1
        yield _sse("session_started", payload)
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
                        payload = {
                            "step": event_data.get("input", {}).get("step_count", 0),
                            "node": "llm_node",
                        }
                        await _safe_append_runtime_event(
                            run_id=run_id,
                            session_id=actual_session_id,
                            thread_id=thread_id,
                            sequence_index=seq,
                            event_type="agent_step",
                            event_name=event_name,
                            payload=payload,
                            trace_id=request_trace_id,
                        )
                        seq += 1
                        yield _sse("agent_step", payload)

                # ---- LLM 流式输出 ----
                elif event_type == "on_chat_model_stream":
                    chunk = event_data.get("chunk", {})
                    content = getattr(chunk, "content", "")
                    if content:
                        payload = {"content": content}
                        await _safe_append_runtime_event(
                            run_id=run_id,
                            session_id=actual_session_id,
                            thread_id=thread_id,
                            sequence_index=seq,
                            event_type="stream",
                            event_name=event_name,
                            payload=payload,
                            trace_id=request_trace_id,
                        )
                        seq += 1
                        yield _sse("stream", payload)

                # ---- 工具调用开始 ----
                elif event_type == "on_tool_start":
                    payload = _safe_tool_call_event(event_name, event_data.get("input", {}))
                    await _safe_append_runtime_event(
                        run_id=run_id,
                        session_id=actual_session_id,
                        thread_id=thread_id,
                        sequence_index=seq,
                        event_type="tool_call",
                        event_name=event_name,
                        payload=payload,
                        trace_id=request_trace_id,
                    )
                    seq += 1
                    yield _sse("tool_call", payload)

                # ---- 工具调用结束 ----
                elif event_type == "on_tool_end":
                    payload = _safe_tool_result_event(event_name, event_data.get("output", ""))
                    await _safe_append_runtime_event(
                        run_id=run_id,
                        session_id=actual_session_id,
                        thread_id=thread_id,
                        sequence_index=seq,
                        event_type="tool_result",
                        event_name=event_name,
                        payload=payload,
                        trace_id=request_trace_id,
                    )
                    seq += 1
                    yield _sse("tool_result", payload)

                # ---- 节点结束（检查 Final Answer / HITL）----
                elif event_type == "on_chain_end":
                    output = event_data.get("output", {})
                    if isinstance(output, dict):
                        for counter in run_totals:
                            value = output.get(counter)
                            if isinstance(value, int) and not isinstance(value, bool):
                                run_totals[counter] = max(run_totals[counter], value)
                        if output.get("final_answer"):
                            payload = {
                                "content": output["final_answer"],
                                "stop_reason": output.get("stop_reason", "completed"),
                                "thread_id": thread_id,
                            }
                            await _safe_append_runtime_event(
                                run_id=run_id,
                                session_id=actual_session_id,
                                thread_id=thread_id,
                                sequence_index=seq,
                                event_type="final_answer",
                                event_name=event_name,
                                payload=payload,
                                trace_id=request_trace_id,
                            )
                            seq += 1
                            await _safe_mark_runtime_status(run_id, "COMPLETED")
                            if not terminal_recorded:
                                terminal_recorded = True
                                stop_reason = output.get("stop_reason", "completed")
                                graph_timer.finish(status="ok", stop_reason=stop_reason)
                                _finish_request_measurement(
                                    request_timer,
                                    status="ok",
                                    route_mode=output.get("route_mode", route_state.get("route_mode", "react")),
                                    route_task_type=route_state.get("route_task_type", "unknown"),
                                    stop_reason=stop_reason,
                                    session_id=actual_session_id,
                                    thread_id=thread_id,
                                    run_id=run_id,
                                    output={**output, **run_totals},
                                )
                            yield _sse("final_answer", payload)
                        if output.get("pending_hitl"):
                            payload = _safe_hitl_request_event(thread_id, output.get("pending_action", {}))
                            await _safe_append_runtime_event(
                                run_id=run_id,
                                session_id=actual_session_id,
                                thread_id=thread_id,
                                sequence_index=seq,
                                event_type="hitl_request",
                                event_name=event_name,
                                payload=payload,
                                trace_id=request_trace_id,
                            )
                            seq += 1
                            await _safe_mark_runtime_status(run_id, "WAITING_APPROVAL")
                            if not terminal_recorded:
                                terminal_recorded = True
                                graph_timer.finish(status="ok", stop_reason="pending_approval")
                                _finish_request_measurement(
                                    request_timer,
                                    status="ok",
                                    route_mode=output.get("route_mode", route_state.get("route_mode", "react")),
                                    route_task_type=route_state.get("route_task_type", "unknown"),
                                    stop_reason="pending_approval",
                                    session_id=actual_session_id,
                                    thread_id=thread_id,
                                    run_id=run_id,
                                    output={**output, **run_totals},
                                )
                            yield _sse("hitl_request", payload)

        except Exception as e:
            terminal_recorded = True
            graph_timer.finish(status="error", stop_reason="stream_error")
            _finish_request_measurement(
                request_timer,
                status="error",
                route_mode=route_state.get("route_mode", "react"),
                route_task_type=route_state.get("route_task_type", "unknown"),
                stop_reason="stream_error",
                session_id=actual_session_id,
                thread_id=thread_id,
                run_id=run_id,
            )
            log.error("agent_stream_error", error=str(e), exc_info=True)
            payload = _safe_error_event(e)
            await _safe_append_runtime_event(
                run_id=run_id,
                session_id=actual_session_id,
                thread_id=thread_id,
                sequence_index=seq,
                event_type="error",
                event_name=None,
                payload=payload,
                trace_id=request_trace_id,
            )
            await _safe_mark_runtime_status(run_id, "FAILED", error_message=str(e))
            yield _sse("error", payload)
        finally:
            if not terminal_recorded:
                graph_timer.finish(status="error", stop_reason="incomplete_stream")
                _finish_request_measurement(
                    request_timer,
                    status="error",
                    route_mode=route_state.get("route_mode", "react"),
                    route_task_type=route_state.get("route_task_type", "unknown"),
                    stop_reason="incomplete_stream",
                    session_id=actual_session_id,
                    thread_id=thread_id,
                    run_id=run_id,
                )

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
    x_merchant_id: str | None = Header(None, alias="X-Merchant-Id"),
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
      服务端使用审批记录中的 thread_id + checkpoint_id 加载精确快照，
      通过载荷签名、原始身份和当前工具权限复核后再恢复。
    """
    approver_id = parse_user_id_header(x_user_id)
    approver_merchant_id = parse_optional_merchant_id_header(x_merchant_id)
    if x_user_role not in ("cs", "admin"):
        raise HTTPException(status_code=403, detail="无权恢复审批任务")

    approval = await hitl_service.get_approval(int(request.approval_id))
    if not approval:
        raise HTTPException(status_code=404, detail="审批记录不存在")
    if (
        approver_merchant_id is not None
        and getattr(approval, "merchant_id", None) != approver_merchant_id
    ):
        raise HTTPException(status_code=404, detail="审批记录不存在")

    resolved_thread_id = approval.thread_id
    if request.thread_id and request.thread_id != resolved_thread_id:
        log.warning(
            "resume_thread_mismatch",
            approval_id=request.approval_id,
            request_thread_id=request.thread_id,
            approval_thread_id=resolved_thread_id,
            approver_id=approver_id,
        )
        raise HTTPException(status_code=400, detail="thread_id 与审批记录不匹配")

    if request.approved and approval.status == "EXECUTED":
        async def replay_stream():
            payload = {
                "content": json.dumps(
                    approval.execution_result or {},
                    ensure_ascii=False,
                ),
                "stop_reason": "hitl_result_replayed",
                "thread_id": resolved_thread_id,
            }
            yield _sse("final_answer", payload)

        return StreamingResponse(
            replay_stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    expected_terminal_status = "APPROVED" if request.approved else "REJECTED"
    if approval.status not in ("PENDING", expected_terminal_status):
        raise HTTPException(status_code=400, detail=f"审批记录状态为 {approval.status}，无法恢复")

    validated_payload = None
    config = None
    if request.approved:
        if not getattr(approval, "checkpoint_id", None):
            raise HTTPException(
                status_code=409,
                detail={"code": "unbound_approval", "message": "审批尚未绑定恢复点"},
            )
        config = {
            "configurable": {
                "thread_id": resolved_thread_id,
                "checkpoint_id": approval.checkpoint_id,
            }
        }
        try:
            snapshot = await agent_graph.aget_state(config)
            checkpoint_values = getattr(snapshot, "values", None)
            validated_payload = hitl_service.validate_resume(
                approval,
                checkpoint_values,
            )
        except HitlResumeError as error:
            raise HTTPException(
                status_code=error.status_code,
                detail={"code": error.code, "message": "审批恢复校验失败"},
            )
        except Exception as error:
            log.warning(
                "hitl_checkpoint_load_failed",
                approval_id=approval.id,
                error_type=type(error).__name__,
            )
            raise HTTPException(
                status_code=409,
                detail={"code": "checkpoint_missing", "message": "审批恢复点不可用"},
            )

    latest_runtime_run = await _safe_get_latest_runtime_run(resolved_thread_id)
    latest_status = getattr(latest_runtime_run, "status", None)
    if latest_status in ("COMPLETED", "CANCELED"):
        async def already_processed_stream():
            payload = {
                "content": "该审批任务已处理，无需重复恢复。",
                "stop_reason": "hitl_already_processed",
                "thread_id": resolved_thread_id,
                "status": latest_status,
            }
            yield _sse("final_answer", payload)

        return StreamingResponse(
            already_processed_stream(),
            media_type="text/event-stream",
            headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
        )

    # ---- Step 1：审批拒绝 ----
    runtime_run = await _safe_get_waiting_runtime_run(resolved_thread_id)
    run_id = runtime_run.id if runtime_run is not None else None
    runtime_session_id = runtime_run.session_id if runtime_run is not None else getattr(approval, "session_id", 0)
    runtime_trace_id = runtime_run.trace_id if runtime_run is not None else None
    seq = await _safe_next_runtime_sequence(run_id)

    if not request.approved:
        if approval.status == "PENDING":
            success = await hitl_service.reject(
                approval_id=int(request.approval_id),
                approver_id=approver_id,
            )
            if not success:
                raise HTTPException(status_code=404, detail="审批记录不存在或状态非 PENDING")

        # 向前端推送拒绝通知（单次 SSE，不恢复 Agent）
        async def reject_stream():
            payload = {
                "content":    "运营已拒绝此操作，任务终止。如需其他帮助请重新发起会话。",
                "stop_reason": "hitl_rejected",
                "thread_id":   resolved_thread_id,
            }
            await _safe_append_runtime_event(
                run_id=run_id,
                session_id=runtime_session_id,
                thread_id=resolved_thread_id,
                sequence_index=seq,
                event_type="final_answer",
                event_name="hitl_rejected",
                payload=payload,
                trace_id=runtime_trace_id,
            )
            await _safe_mark_runtime_status(run_id, "CANCELED")
            yield _sse("final_answer", payload)

        return StreamingResponse(reject_stream(), media_type="text/event-stream",
                                 headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"})

    # ---- Step 2：查询并通过审批 ----
    if approval.status == "PENDING":
        ok = await hitl_service.approve(int(request.approval_id), approver_id)
        if not ok:
            raise HTTPException(status_code=400, detail="审批操作失败，请重试")

    # ---- Step 3：恢复 LangGraph Agent ----
    # 仅使用通过签名和身份校验的规范化载荷，不读取客户端或未验证 JSON。
    if validated_payload is None or config is None:
        raise HTTPException(status_code=409, detail="审批恢复状态不完整")
    if validated_payload.tool_name == "execute_refund":
        execution_payload = {
            "order_id": validated_payload.order_id,
            "amount": validated_payload.amount_minor,
            "reason": validated_payload.reason,
        }
    else:
        execution_payload = {
            "user_id": validated_payload.target_user_id,
            "order_id": validated_payload.order_id,
            "compensation_amount": validated_payload.amount_minor,
            "shop_id": validated_payload.shop_id,
            "merchant_id": validated_payload.merchant_id,
            "coupon_template_id": validated_payload.coupon_template_id,
            "coupon_discount_type": validated_payload.coupon_discount_type,
            "coupon_min_order_amount": validated_payload.coupon_min_order_amount,
            "coupon_valid_days": validated_payload.coupon_valid_days,
            "coupon_terms_digest": validated_payload.coupon_terms_digest,
            "reason": validated_payload.reason,
        }

    # LangGraph resume：传入 None 输入（继续执行被 interrupt 挂起的节点）
    # pending_action 中注入 approval_id（工具调用时作为凭证传入）
    resume_input = {
        "pending_hitl":  False,      # 解除挂起
        "user_id": int(validated_payload.requested_user_id),
        "user_role": validated_payload.requested_role,
        "merchant_id": (
            int(validated_payload.merchant_id)
            if validated_payload.merchant_id
            else None
        ),
        "pending_action": {
            "action_type": validated_payload.tool_name,
            "payload": execution_payload,
            "reason": validated_payload.reason,
            "approval_id": str(request.approval_id),
            "approval_digest": approval.payload_digest,
            "payload_digest": approval.payload_digest,
            "approval_payload": validated_payload.canonical_dict(),
        },
    }

    async def resume_stream():
        """从 checkpoint 恢复 Agent，继续执行，以 SSE 推送后续过程。"""
        nonlocal seq
        await _safe_mark_runtime_status(run_id, "RUNNING")
        payload = {
            "type":    "hitl_resumed",
            "message": f"审批通过（approver={approver_id}），Agent 继续执行",
        }
        await _safe_append_runtime_event(
            run_id=run_id,
            session_id=runtime_session_id,
            thread_id=resolved_thread_id,
            sequence_index=seq,
            event_type="agent_step",
            event_name="hitl_resumed",
            payload=payload,
            trace_id=runtime_trace_id,
        )
        seq += 1
        yield _sse("agent_step", payload)
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
                        payload = {"content": content}
                        await _safe_append_runtime_event(
                            run_id=run_id,
                            session_id=runtime_session_id,
                            thread_id=resolved_thread_id,
                            sequence_index=seq,
                            event_type="stream",
                            event_name=event_name,
                            payload=payload,
                            trace_id=runtime_trace_id,
                        )
                        seq += 1
                        yield _sse("stream", payload)

                elif event_type == "on_tool_start":
                    payload = _safe_tool_call_event(event_name, event_data.get("input", {}))
                    await _safe_append_runtime_event(
                        run_id=run_id,
                        session_id=runtime_session_id,
                        thread_id=resolved_thread_id,
                        sequence_index=seq,
                        event_type="tool_call",
                        event_name=event_name,
                        payload=payload,
                        trace_id=runtime_trace_id,
                    )
                    seq += 1
                    yield _sse("tool_call", payload)

                elif event_type == "on_tool_end":
                    payload = _safe_tool_result_event(event_name, event_data.get("output", ""))
                    await _safe_append_runtime_event(
                        run_id=run_id,
                        session_id=runtime_session_id,
                        thread_id=resolved_thread_id,
                        sequence_index=seq,
                        event_type="tool_result",
                        event_name=event_name,
                        payload=payload,
                        trace_id=runtime_trace_id,
                    )
                    seq += 1
                    yield _sse("tool_result", payload)

                elif event_type == "on_chain_end":
                    output = event_data.get("output", {})
                    if isinstance(output, dict) and output.get("final_answer"):
                        payload = {
                            "content":    output["final_answer"],
                            "stop_reason": output.get("stop_reason", "completed"),
                            "thread_id":   resolved_thread_id,
                        }
                        await _safe_append_runtime_event(
                            run_id=run_id,
                            session_id=runtime_session_id,
                            thread_id=resolved_thread_id,
                            sequence_index=seq,
                            event_type="final_answer",
                            event_name=event_name,
                            payload=payload,
                            trace_id=runtime_trace_id,
                        )
                        seq += 1
                        await _safe_mark_runtime_status(run_id, "COMPLETED")
                        yield _sse("final_answer", payload)

        except Exception as e:
            log.error("resume_stream_error", error=str(e), exc_info=True)
            payload = _safe_error_event(e)
            await _safe_append_runtime_event(
                run_id=run_id,
                session_id=runtime_session_id,
                thread_id=resolved_thread_id,
                sequence_index=seq,
                event_type="error",
                event_name=None,
                payload=payload,
                trace_id=runtime_trace_id,
            )
            await _safe_mark_runtime_status(run_id, "FAILED", error_message=str(e))
            yield _sse("error", payload)

    return StreamingResponse(
        resume_stream(),
        media_type="text/event-stream",
        headers={"Cache-Control": "no-cache", "X-Accel-Buffering": "no"},
    )


def _sse(event: str, data: dict) -> str:
    """格式化为 SSE 事件字符串。"""
    return f"event: {event}\ndata: {json.dumps(data, ensure_ascii=False)}\n\n"
