"""
会话管理器：负责 agent_session 和 agent_message 的读写。

职责：
  1. create_session()      — 创建新会话（用户发起第一条消息时）
  2. update_session()      — 更新会话状态（完成/中断/等待审批）
  3. save_message()        — 持久化消息（user/assistant/tool 三种角色）
  4. get_session_messages() — 重建消息历史（会话恢复 / HITL 恢复后用）

设计原则：
  - 所有操作异步（async with AsyncSession），不阻塞 Agent 主循环
  - 消息持久化在工具调用前后各一次（保证中断时可以从最近状态恢复）
  - ID 使用雪花算法生成（与 Java 侧一致）
"""
import time
import random
import structlog
from datetime import datetime
from sqlalchemy.ext.asyncio import AsyncSession, create_async_engine, async_sessionmaker
from sqlalchemy import select

from config.settings import settings
from session.models import AgentSession, AgentMessage

log = structlog.get_logger(__name__)

# =========================================================
# 数据库引擎（全局单例）
# =========================================================

engine = create_async_engine(
    settings.db_url,
    pool_size=5,
    max_overflow=10,
    pool_pre_ping=True,   # 每次取连接前 ping，防止连接池中出现断开的连接
    echo=False,           # True 时打印所有 SQL（调试用）
)

AsyncSessionLocal = async_sessionmaker(engine, expire_on_commit=False)


# =========================================================
# 简易雪花 ID 生成（与 Java 侧 MyBatis-Plus ASSIGN_ID 兼容）
# =========================================================

def _snowflake_id() -> int:
    """
    简化版雪花 ID（Python 实现，不依赖额外库）。
    格式：41位时间戳毫秒 + 12位随机序列
    保证同进程内单毫秒内不重复，跨进程依赖极低概率不碰撞。
    生产环境建议用 snowflake 库或 Redis INCR 实现全局唯一。
    """
    ts = int(time.time() * 1000)
    seq = random.randint(0, 4095)
    return (ts << 12) | seq


# =========================================================
# 会话管理
# =========================================================

class SessionManager:
    """
    Agent 会话管理器。
    在 FastAPI 启动时由 lifespan 创建单例，注入到各个接口。
    """

    async def create_session(
        self,
        user_id: int,
        user_role: str,
        merchant_id: int | None,
        first_message: str,
    ) -> int:
        """
        创建新会话，返回 session_id。

        title 由第一条消息的前 50 个字符自动截取，
        方便用户在历史列表中识别会话内容。

        :param user_id:      调用者用户 ID
        :param user_role:    调用者角色（merchant/cs/admin）
        :param merchant_id:  商家 ID（merchant 角色时必填）
        :param first_message: 用户第一条消息（用于生成会话标题）
        :return: 新会话 ID
        """
        session_id = _snowflake_id()
        title = first_message[:50] if first_message else "未命名会话"

        async with AsyncSessionLocal() as db:
            session = AgentSession(
                id=session_id,
                user_id=user_id,
                user_role=user_role,
                merchant_id=merchant_id,
                title=title,
                status="ACTIVE",
            )
            db.add(session)
            await db.commit()

        log.info("session_created", session_id=session_id, user_id=user_id, title=title)
        return session_id

    async def update_session_status(
        self,
        session_id: int,
        status: str,
        total_tokens: int = 0,
    ) -> None:
        """
        更新会话状态和 token 累计（会话结束时调用）。

        :param session_id:   会话 ID
        :param status:       新状态（COMPLETED / ABORTED / PENDING_APPROVAL）
        :param total_tokens: 本次会话消耗的 token 总量
        """
        async with AsyncSessionLocal() as db:
            result = await db.get(AgentSession, session_id)
            if result:
                result.status = status
                result.total_tokens = total_tokens
                result.updated_at = datetime.now()
                await db.commit()
                log.info("session_updated", session_id=session_id, status=status, tokens=total_tokens)

    async def save_message(
        self,
        session_id: int,
        role: str,
        content: str | None,
        step_index: int,
        tool_calls: list | None = None,
        tool_results: list | None = None,
        tokens: int = 0,
    ) -> int:
        """
        持久化一条会话消息，返回消息 ID。

        持久化时机：
        - 用户输入（role=user）：会话开始时立即保存
        - Agent 推理（role=assistant）：llm_node 执行后保存
        - 工具结果（role=tool）：tool_node 执行后保存
        - 最终回答（role=assistant）：final_node 保存

        :param session_id:   所属会话 ID
        :param role:         消息角色（user / assistant / tool）
        :param content:      消息文本内容（Final Answer 或 null）
        :param step_index:   ReAct 循环步数（从 0 开始）
        :param tool_calls:   工具调用列表（assistant 决定调用工具时填写）
        :param tool_results: 工具结果列表（tool 角色时填写）
        :param tokens:       本条消息的 token 数
        :return: 消息 ID
        """
        msg_id = _snowflake_id()
        async with AsyncSessionLocal() as db:
            message = AgentMessage(
                id=msg_id,
                session_id=session_id,
                role=role,
                content=content,
                tool_calls=tool_calls,
                tool_results=tool_results,
                tokens=tokens,
                step_index=step_index,
            )
            db.add(message)
            await db.commit()

        log.debug("message_saved", session_id=session_id, role=role, step=step_index, msg_id=msg_id)
        return msg_id

    async def get_session_messages(self, session_id: int) -> list[AgentMessage]:
        """
        查询会话所有消息（按时间升序），用于重建消息历史。

        HITL 恢复场景：从 checkpoint 恢复 Agent 时，
        也需要从 DB 重建消息历史，保证上下文完整性。

        :param session_id: 会话 ID
        :return: 消息列表（按 created_at 升序）
        """
        async with AsyncSessionLocal() as db:
            stmt = (
                select(AgentMessage)
                .where(AgentMessage.session_id == session_id)
                .order_by(AgentMessage.created_at.asc())
            )
            result = await db.execute(stmt)
            return list(result.scalars().all())


# 全局单例
session_manager = SessionManager()
