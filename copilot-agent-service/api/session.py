"""
会话管理 API（Session API）。

提供给前端的会话生命周期管理接口：
  POST /sessions          创建新会话（首次对话前调用，拿到 session_id）
  GET  /sessions/{id}     查询会话详情（含统计信息）
  GET  /sessions          列出当前用户的所有会话
  POST /sessions/{id}/end 主动结束会话

设计理由：
  之前的 /chat 接口要求前端传入 session_id，但前端如何拿到 session_id？
  此 API 解决「会话从哪来」的问题，前端流程：
    1. 用户点击「新对话」 → POST /sessions → 返回 session_id
    2. 用户输入消息 → POST /chat with session_id → SSE 流
    3. 对话结束 → POST /sessions/{id}/end → 标记完成
"""
import structlog
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from session.manager import session_manager, AsyncSessionLocal
from session.models import AgentSession
from sqlalchemy import select

log = structlog.get_logger(__name__)

router = APIRouter(prefix="/sessions", tags=["session"])


class CreateSessionRequest(BaseModel):
    """创建会话请求体。"""
    initial_message: str | None = None     # 可选：第一条消息（用于自动生成会话标题）
    title:           str | None = None     # 可选：手动指定标题


class SessionResponse(BaseModel):
    """会话响应体（不含完整消息历史，只含元数据）。"""
    session_id:       str         # 字符串形式（避免 JS 数字精度问题）
    user_id:          str
    user_role:        str
    merchant_id:      str | None
    title:            str | None
    status:           str
    total_tokens:     int
    total_cost_cents: int
    created_at:       str


# =========================================================
# POST /sessions —— 创建新会话
# =========================================================

@router.post("", response_model=SessionResponse)
async def create_session(
    body: CreateSessionRequest,
    x_user_id:     str = Header(..., alias="X-User-Id"),
    x_user_role:   str = Header(..., alias="X-User-Role"),
    x_merchant_id: str | None = Header(None, alias="X-Merchant-Id"),
):
    """
    创建新会话，返回 session_id 给前端。

    前端拿到 session_id 后，调用 POST /chat 时携带此 ID。
    后端会自动用此 ID 持久化所有消息和 token 统计。
    """
    user_id     = int(x_user_id)
    merchant_id = int(x_merchant_id) if x_merchant_id else None

    # 校验 merchant 角色必须带 merchant_id
    if x_user_role == "merchant" and not merchant_id:
        raise HTTPException(status_code=400, detail="merchant 角色必须提供 X-Merchant-Id Header")

    title = body.title or (body.initial_message or "新对话")[:50]

    session_id = await session_manager.create_session(
        user_id=user_id,
        user_role=x_user_role,
        merchant_id=merchant_id,
        first_message=title,
    )

    log.info("session_api_created", session_id=session_id, user_id=user_id)

    # 查询返回完整对象
    async with AsyncSessionLocal() as db:
        session = await db.get(AgentSession, session_id)

    return SessionResponse(
        session_id=       str(session.id),
        user_id=          str(session.user_id),
        user_role=        session.user_role,
        merchant_id=      str(session.merchant_id) if session.merchant_id else None,
        title=            session.title,
        status=           session.status,
        total_tokens=     session.total_tokens,
        total_cost_cents= session.total_cost_cents,
        created_at=       session.created_at.isoformat(),
    )


# =========================================================
# GET /sessions/{id} —— 查询会话详情
# =========================================================

@router.get("/{session_id}", response_model=SessionResponse)
async def get_session(
    session_id: int,
    x_user_id: str = Header(..., alias="X-User-Id"),
):
    """
    查询会话详情。
    权限校验：只能查询自己的会话。
    """
    async with AsyncSessionLocal() as db:
        session = await db.get(AgentSession, session_id)

    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")

    # RBAC：只能查询自己的会话
    if session.user_id != int(x_user_id):
        raise HTTPException(status_code=403, detail="无权访问此会话")

    return SessionResponse(
        session_id=       str(session.id),
        user_id=          str(session.user_id),
        user_role=        session.user_role,
        merchant_id=      str(session.merchant_id) if session.merchant_id else None,
        title=            session.title,
        status=           session.status,
        total_tokens=     session.total_tokens,
        total_cost_cents= session.total_cost_cents,
        created_at=       session.created_at.isoformat(),
    )


# =========================================================
# GET /sessions —— 列出当前用户的会话
# =========================================================

@router.get("")
async def list_sessions(
    x_user_id: str = Header(..., alias="X-User-Id"),
    limit:     int = 20,
):
    """列出当前用户的最近 N 个会话（按创建时间倒序）。"""
    user_id = int(x_user_id)
    async with AsyncSessionLocal() as db:
        result = await db.execute(
            select(AgentSession)
            .where(AgentSession.user_id == user_id)
            .order_by(AgentSession.created_at.desc())
            .limit(limit)
        )
        sessions = result.scalars().all()

    return {
        "count": len(sessions),
        "sessions": [
            {
                "session_id":   str(s.id),
                "title":        s.title,
                "status":       s.status,
                "total_tokens": s.total_tokens,
                "created_at":   s.created_at.isoformat(),
            }
            for s in sessions
        ],
    }


# =========================================================
# POST /sessions/{id}/end —— 主动结束会话
# =========================================================

@router.post("/{session_id}/end")
async def end_session(
    session_id: int,
    x_user_id: str = Header(..., alias="X-User-Id"),
):
    """主动结束会话（标记为 COMPLETED）。"""
    async with AsyncSessionLocal() as db:
        session = await db.get(AgentSession, session_id)

    if session is None:
        raise HTTPException(status_code=404, detail="会话不存在")
    if session.user_id != int(x_user_id):
        raise HTTPException(status_code=403, detail="无权操作此会话")

    await session_manager.update_session_status(
        session_id=session_id,
        status="COMPLETED",
        total_tokens=session.total_tokens,
    )
    return {"status": "ended", "session_id": str(session_id)}
