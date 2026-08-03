"""
HITL（Human-In-The-Loop）审批服务。

负责：
  1. create_approval()   — Agent 挂起时，写 hitl_approval 记录（PENDING）
  2. approve()           — 运营审批通过，更新状态为 APPROVED，触发 Agent 恢复
  3. reject()            — 运营拒绝，更新状态为 REJECTED
  4. get_approval()      — 查询审批记录（恢复 Agent 时验证 approval_id 合法性）
  5. expire_overdue()    — 定时任务：24 小时未审批自动过期

HITL 是 LocalLife Copilot 企业级 Agent 的核心安全机制：
  高风险动作（退款/补券）必须经人工审批，不允许 AI 自主执行。
"""
import hashlib
import structlog
from datetime import datetime, timedelta
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from session.manager import AsyncSessionLocal, _snowflake_id

log = structlog.get_logger(__name__)

# =========================================================
# hitl_approval SQLAlchemy 模型（内联，避免过多文件）
# =========================================================
from sqlalchemy import BigInteger, Integer, String, Text, JSON, DateTime, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from session.models import Base
from config.settings import settings
from session.hitl_binding import ApprovalPayload, sign_payload


class HitlBindingError(RuntimeError):
    """Raised when an approval cannot be bound to one exact checkpoint."""


class HitlApproval(Base):
    """
    人工审批记录（对应 hitl_approval 表）。

    生命周期：
      PENDING   → 审批申请已提交，等待运营处理
      APPROVED  → 运营已通过，Agent 可以继续执行高风险动作
      REJECTED  → 运营已拒绝，Agent 终止任务并通知用户
      EXPIRED   → 24 小时未处理，自动拒绝

    checkpoint_id 是 LangGraph 挂起时的状态快照 ID，
    Agent 恢复时通过 thread_id + checkpoint_id 找到挂起前的状态继续执行。
    """
    __tablename__ = "hitl_approval"

    id:               Mapped[int]       = mapped_column(BigInteger, primary_key=True)
    session_id:       Mapped[int]       = mapped_column(BigInteger, nullable=False)
    thread_id:        Mapped[str]       = mapped_column(String(64), nullable=False)
    checkpoint_id:    Mapped[str | None] = mapped_column(String(64), nullable=True)
    action_type:      Mapped[str]       = mapped_column(String(50), nullable=False)
    action_payload:   Mapped[dict]      = mapped_column(JSON, nullable=False)
    payload_version:  Mapped[int | None] = mapped_column(Integer, nullable=True)
    payload_digest:   Mapped[str | None] = mapped_column(String(64), nullable=True)
    order_target_hash: Mapped[str | None] = mapped_column(String(64), nullable=True)
    merchant_id:      Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    requested_user_id: Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    requested_role:   Mapped[str | None] = mapped_column(String(32), nullable=True)
    agent_reason:     Mapped[str]       = mapped_column(Text, nullable=False)
    status:           Mapped[str]       = mapped_column(String(20), default="PENDING")
    approver_id:      Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    approver_comment: Mapped[str | None] = mapped_column(Text, nullable=True)
    approved_at:      Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    execution_id:     Mapped[str | None] = mapped_column(String(64), nullable=True)
    execution_lease_until: Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    executing_at:     Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    executed_at:      Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    execution_result: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    execution_error:  Mapped[str | None] = mapped_column(Text, nullable=True)
    expire_at:        Mapped[datetime]  = mapped_column(DateTime, nullable=False)
    created_at:       Mapped[datetime]  = mapped_column(DateTime, default=func.now())
    updated_at:       Mapped[datetime]  = mapped_column(DateTime, default=func.now(), onupdate=func.now())

    def __init__(self, **kwargs):
        super().__init__(**kwargs)
        if self.checkpoint_id is None and (self.status or "PENDING") != "PENDING":
            raise HitlBindingError("checkpoint binding is required outside PENDING")


# =========================================================
# HITL 服务
# =========================================================

class HitlService:
    """
    HITL 审批流程管理。
    """

    async def create_approval(
        self,
        session_id: int,
        thread_id: str,
        approval_payload: ApprovalPayload,
        agent_reason: str,
        expire_hours: int = 24,
    ) -> int:
        """
        创建人工审批申请（Agent hitl_node 调用）。

        :param session_id:     会话 ID（关联 agent_session）
        :param thread_id:      LangGraph thread ID（恢复时找到 checkpoint）
        :param approval_payload: 规范化且待签名的高风险业务参数
        :param agent_reason:   Agent 的申请理由（根因分析结论，方便审批者判断）
        :param expire_hours:   过期时间（默认 24 小时）
        :return: 审批记录 ID（approval_id）
        """
        approval_id = _snowflake_id()
        expire_at = datetime.now() + timedelta(hours=expire_hours)
        action_payload = dict(approval_payload.canonical_dict())
        payload_digest = sign_payload(
            approval_payload,
            settings.hitl_payload_signing_secret,
        )

        async with AsyncSessionLocal() as db:
            approval = HitlApproval(
                id=approval_id,
                session_id=session_id,
                thread_id=thread_id,
                checkpoint_id=None,
                action_type=approval_payload.tool_name,
                action_payload=action_payload,
                payload_version=approval_payload.payload_version,
                payload_digest=payload_digest,
                order_target_hash=hashlib.sha256(
                    approval_payload.order_id.encode("utf-8")
                ).hexdigest(),
                merchant_id=(
                    int(approval_payload.merchant_id)
                    if approval_payload.merchant_id
                    else None
                ),
                requested_user_id=int(approval_payload.requested_user_id),
                requested_role=approval_payload.requested_role,
                agent_reason=agent_reason,
                status="PENDING",
                expire_at=expire_at,
            )
            db.add(approval)
            await db.commit()

        log.info(
            "hitl_approval_created",
            approval_id=approval_id,
            action_type=approval_payload.tool_name,
            thread_id=thread_id,
            expire_at=expire_at.isoformat(),
        )
        return approval_id

    async def bind_checkpoint(
        self,
        db: AsyncSession,
        *,
        approval_id: int,
        thread_id: str,
        checkpoint_id: str,
        payload_digest: str,
    ) -> None:
        """Bind a pending approval to the exact checkpoint in the caller transaction."""
        transition = await db.execute(
            update(HitlApproval)
            .where(
                HitlApproval.id == approval_id,
                HitlApproval.thread_id == thread_id,
                HitlApproval.payload_digest == payload_digest,
                HitlApproval.checkpoint_id.is_(None),
                HitlApproval.status == "PENDING",
            )
            .values(checkpoint_id=checkpoint_id, updated_at=datetime.now())
        )
        if transition.rowcount == 1:
            return

        approval = await db.get(HitlApproval, approval_id)
        if (
            approval is not None
            and approval.id == approval_id
            and approval.thread_id == thread_id
            and approval.checkpoint_id == checkpoint_id
            and approval.payload_digest == payload_digest
        ):
            return
        raise HitlBindingError("approval checkpoint binding mismatch")

    async def approve(
        self,
        approval_id: int,
        approver_id: int,
        comment: str | None = None,
    ) -> bool:
        """
        审批通过（运营人员调用）。

        :param approval_id: 审批记录 ID
        :param approver_id: 审批者用户 ID
        :param comment:     审批备注（可选）
        :return: True=更新成功，False=记录不存在或已不是 PENDING 状态
        """
        now = datetime.now()
        async with AsyncSessionLocal() as db:
            transition = await db.execute(
                update(HitlApproval)
                .where(
                    HitlApproval.id == approval_id,
                    HitlApproval.status == "PENDING",
                    HitlApproval.expire_at >= now,
                )
                .values(
                    status="APPROVED",
                    approver_id=approver_id,
                    approver_comment=comment,
                    approved_at=now,
                    updated_at=now,
                )
            )
            if transition.rowcount == 1:
                await db.commit()
                log.info("hitl_approved", approval_id=approval_id, approver_id=approver_id)
                return True

            await db.rollback()
            result = await db.get(HitlApproval, approval_id)
            if not result:
                log.warning("hitl_approve_not_found", approval_id=approval_id)
                return False
            if result.status == "PENDING" and result.expire_at < now:
                log.warning("hitl_approve_expired", approval_id=approval_id)
                result.status = "EXPIRED"
                result.updated_at = now
                await db.commit()
                return False
            log.warning("hitl_approve_invalid_status",
                        approval_id=approval_id, status=result.status)
            return False

    async def reject(
        self,
        approval_id: int,
        approver_id: int,
        comment: str | None = None,
    ) -> bool:
        """
        审批拒绝（运营人员调用）。

        拒绝后 Agent 不会继续执行高风险动作，
        会向用户说明拒绝原因并建议其他解决方案。
        """
        now = datetime.now()
        async with AsyncSessionLocal() as db:
            transition = await db.execute(
                update(HitlApproval)
                .where(
                    HitlApproval.id == approval_id,
                    HitlApproval.status == "PENDING",
                )
                .values(
                    status="REJECTED",
                    approver_id=approver_id,
                    approver_comment=comment,
                    approved_at=now,
                    updated_at=now,
                )
            )
            if transition.rowcount != 1:
                await db.rollback()
                log.warning("hitl_reject_invalid_status", approval_id=approval_id)
                return False
            await db.commit()

        log.info("hitl_rejected", approval_id=approval_id, approver_id=approver_id)
        return True

    async def get_approval(self, approval_id: int) -> HitlApproval | None:
        """查询审批记录（Agent 恢复时验证 approval_id 合法性）。"""
        async with AsyncSessionLocal() as db:
            return await db.get(HitlApproval, approval_id)

    async def get_pending_approvals(
        self,
        limit: int = 50,
        merchant_id: int | None = None,
    ) -> list[HitlApproval]:
        """查询待审批记录；merchant_id 存在时只返回该商家的审批。"""
        async with AsyncSessionLocal() as db:
            stmt = (
                select(HitlApproval)
                .where(HitlApproval.status == "PENDING")
            )
            if merchant_id is not None:
                stmt = stmt.where(HitlApproval.action_payload["merchant_id"].as_integer() == merchant_id)
            stmt = stmt.order_by(HitlApproval.created_at.asc()).limit(limit)
            result = await db.execute(stmt)
            return list(result.scalars().all())


# 全局单例
hitl_service = HitlService()
