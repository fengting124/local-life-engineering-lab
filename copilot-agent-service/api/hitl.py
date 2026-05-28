"""
HITL 审批工作台 API。

运营人员通过此 API 查看待审批列表、通过或拒绝审批。
前端审批工作台（Approval UI）调用这些接口。

接口列表：
  GET  /hitl/pending          — 查看所有待审批列表
  POST /hitl/{id}/approve     — 通过审批
  POST /hitl/{id}/reject      — 拒绝审批
  GET  /hitl/{id}             — 查看单个审批详情
"""
import structlog
from fastapi import APIRouter, Header, HTTPException
from pydantic import BaseModel

from session.hitl import hitl_service

log = structlog.get_logger(__name__)

router = APIRouter(prefix="/hitl", tags=["hitl"])


class ApproveRequest(BaseModel):
    """审批通过请求体。"""
    comment: str | None = None   # 审批备注（可选）


class RejectRequest(BaseModel):
    """审批拒绝请求体。"""
    comment: str | None = None   # 拒绝原因（建议填写，方便 Agent 在回复用户时说明）


@router.get("/pending")
async def list_pending_approvals(
    x_user_role: str = Header(..., alias="X-User-Role"),
):
    """
    查询所有待审批列表（审批工作台首页）。

    仅 cs 和 admin 角色可以查看，merchant 无权访问审批队列。
    返回按提交时间升序排列（最早提交的最先处理，FIFO）。
    """
    if x_user_role not in ("cs", "admin"):
        raise HTTPException(status_code=403, detail="无权访问审批列表")

    approvals = await hitl_service.get_pending_approvals(limit=100)
    return {
        "count": len(approvals),
        "approvals": [
            {
                "id":            a.id,
                "session_id":    a.session_id,
                "action_type":   a.action_type,
                "action_payload": a.action_payload,
                "agent_reason":  a.agent_reason,
                "expire_at":     a.expire_at.isoformat(),
                "created_at":    a.created_at.isoformat(),
            }
            for a in approvals
        ],
    }


@router.get("/{approval_id}")
async def get_approval_detail(
    approval_id: int,
    x_user_role: str = Header(..., alias="X-User-Role"),
):
    """
    查询单个审批记录详情（运营点击某条记录查看完整信息）。
    """
    if x_user_role not in ("cs", "admin"):
        raise HTTPException(status_code=403, detail="无权访问审批详情")

    approval = await hitl_service.get_approval(approval_id)
    if not approval:
        raise HTTPException(status_code=404, detail="审批记录不存在")

    return {
        "id":               approval.id,
        "session_id":       approval.session_id,
        "thread_id":        approval.thread_id,
        "action_type":      approval.action_type,
        "action_payload":   approval.action_payload,
        "agent_reason":     approval.agent_reason,
        "status":           approval.status,
        "approver_id":      approval.approver_id,
        "approver_comment": approval.approver_comment,
        "approved_at":      approval.approved_at.isoformat() if approval.approved_at else None,
        "expire_at":        approval.expire_at.isoformat(),
        "created_at":       approval.created_at.isoformat(),
    }


@router.post("/{approval_id}/approve")
async def approve(
    approval_id: int,
    body: ApproveRequest,
    x_user_id:   str = Header(..., alias="X-User-Id"),
    x_user_role: str = Header(..., alias="X-User-Role"),
):
    """
    通过审批（运营人员操作）。

    通过后：
    1. hitl_approval.status → APPROVED
    2. 前端收到通知后调用 POST /chat/resume 恢复 Agent

    注意：此接口只更新 DB 状态，不直接恢复 Agent。
    Agent 恢复需要前端主动调用 POST /chat/resume（携带 thread_id 和 approval_id）。
    这样设计的原因：审批和恢复解耦，运营可以审批但不关心 Agent 的技术细节。
    """
    if x_user_role not in ("cs", "admin"):
        raise HTTPException(status_code=403, detail="无权审批")

    approver_id = int(x_user_id)
    ok = await hitl_service.approve(approval_id, approver_id, body.comment)
    if not ok:
        raise HTTPException(status_code=400, detail="审批失败（记录不存在、状态非 PENDING 或已过期）")

    # 查询 approval 获取 thread_id，返回给前端，前端用它调用 /chat/resume
    approval = await hitl_service.get_approval(approval_id)
    return {
        "status":    "approved",
        "thread_id": approval.thread_id if approval else None,
        "message":   "审批已通过，请调用 POST /chat/resume 恢复 Agent 执行",
    }


@router.post("/{approval_id}/reject")
async def reject(
    approval_id: int,
    body: RejectRequest,
    x_user_id:   str = Header(..., alias="X-User-Id"),
    x_user_role: str = Header(..., alias="X-User-Role"),
):
    """
    拒绝审批（运营人员操作）。

    拒绝后：
    1. hitl_approval.status → REJECTED
    2. 前端通过 POST /chat/resume（approved=false）通知 Agent 终止
    """
    if x_user_role not in ("cs", "admin"):
        raise HTTPException(status_code=403, detail="无权审批")

    approver_id = int(x_user_id)
    ok = await hitl_service.reject(approval_id, approver_id, body.comment)
    if not ok:
        raise HTTPException(status_code=400, detail="拒绝失败（记录不存在或状态非 PENDING）")

    approval = await hitl_service.get_approval(approval_id)
    return {
        "status":    "rejected",
        "thread_id": approval.thread_id if approval else None,
        "message":   "审批已拒绝，请调用 POST /chat/resume（approved=false）通知 Agent",
    }
