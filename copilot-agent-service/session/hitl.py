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
import structlog
from datetime import datetime, timedelta
from sqlalchemy import select, update
from sqlalchemy.ext.asyncio import AsyncSession

from session.manager import AsyncSessionLocal, _snowflake_id

log = structlog.get_logger(__name__)

# =========================================================
# hitl_approval SQLAlchemy 模型（内联，避免过多文件）
# =========================================================
from sqlalchemy import BigInteger, String, Text, JSON, DateTime, func
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column
from session.models import Base


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
    checkpoint_id:    Mapped[str]       = mapped_column(String(64), nullable=False)
    action_type:      Mapped[str]       = mapped_column(String(50), nullable=False)
    action_payload:   Mapped[dict]      = mapped_column(JSON, nullable=False)
    agent_reason:     Mapped[str]       = mapped_column(Text, nullable=False)
    status:           Mapped[str]       = mapped_column(String(20), default="PENDING")
    approver_id:      Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    approver_comment: Mapped[str | None] = mapped_column(Text, nullable=True)
    approved_at:      Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    expire_at:        Mapped[datetime]  = mapped_column(DateTime, nullable=False)
    created_at:       Mapped[datetime]  = mapped_column(DateTime, default=func.now())
    updated_at:       Mapped[datetime]  = mapped_column(DateTime, default=func.now(), onupdate=func.now())


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
        checkpoint_id: str,
        action_type: str,
        action_payload: dict,
        agent_reason: str,
        expire_hours: int = 24,
    ) -> int:
        """
        创建人工审批申请（Agent hitl_node 调用）。

        :param session_id:     会话 ID（关联 agent_session）
        :param thread_id:      LangGraph thread ID（恢复时找到 checkpoint）
        :param checkpoint_id:  挂起时的 checkpoint ID（恢复 Agent 的起点）
        :param action_type:    高风险动作类型（execute_refund / issue_compensation_coupon）
        :param action_payload: 动作参数（退款金额、用户 ID 等）
        :param agent_reason:   Agent 的申请理由（根因分析结论，方便审批者判断）
        :param expire_hours:   过期时间（默认 24 小时）
        :return: 审批记录 ID（approval_id）
        """
        approval_id = _snowflake_id()
        expire_at = datetime.now() + timedelta(hours=expire_hours)

        async with AsyncSessionLocal() as db:
            approval = HitlApproval(
                id=approval_id,
                session_id=session_id,
                thread_id=thread_id,
                checkpoint_id=checkpoint_id,
                action_type=action_type,
                action_payload=action_payload,
                agent_reason=agent_reason,
                expire_at=expire_at,
            )
            db.add(approval)
            await db.commit()

        log.info(
            "hitl_approval_created",
            approval_id=approval_id,
            action_type=action_type,
            thread_id=thread_id,
            expire_at=expire_at.isoformat(),
        )
        return approval_id

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
        async with AsyncSessionLocal() as db:
            result = await db.get(HitlApproval, approval_id)
            if not result:
                log.warning("hitl_approve_not_found", approval_id=approval_id)
                return False
            if result.status != "PENDING":
                log.warning("hitl_approve_invalid_status",
                            approval_id=approval_id, status=result.status)
                return False
            if result.expire_at < datetime.now():
                log.warning("hitl_approve_expired", approval_id=approval_id)
                result.status = "EXPIRED"
                await db.commit()
                return False

            result.status = "APPROVED"
            result.approver_id = approver_id
            result.approver_comment = comment
            result.approved_at = datetime.now()
            await db.commit()

        log.info("hitl_approved", approval_id=approval_id, approver_id=approver_id)
        return True

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
        async with AsyncSessionLocal() as db:
            result = await db.get(HitlApproval, approval_id)
            if not result or result.status != "PENDING":
                return False
            result.status = "REJECTED"
            result.approver_id = approver_id
            result.approver_comment = comment
            result.approved_at = datetime.now()
            await db.commit()

        log.info("hitl_rejected", approval_id=approval_id, approver_id=approver_id)
        return True

    async def get_approval(self, approval_id: int) -> HitlApproval | None:
        """查询审批记录（Agent 恢复时验证 approval_id 合法性）。"""
        async with AsyncSessionLocal() as db:
            return await db.get(HitlApproval, approval_id)

    async def get_pending_approvals(self, limit: int = 50) -> list[HitlApproval]:
        """查询所有待审批记录（审批工作台展示用）。"""
        async with AsyncSessionLocal() as db:
            stmt = (
                select(HitlApproval)
                .where(HitlApproval.status == "PENDING")
                .order_by(HitlApproval.created_at.asc())
                .limit(limit)
            )
            result = await db.execute(stmt)
            return list(result.scalars().all())


# 全局单例
hitl_service = HitlService()
