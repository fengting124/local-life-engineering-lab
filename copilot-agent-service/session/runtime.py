"""
Agent runtime persistence.

`agent_session` records a conversation. `agent_run` records one concrete Agent
execution inside that conversation, and `agent_event` records the durable event
stream for replay and incident investigation.
"""
import uuid
from datetime import datetime
from typing import Any

import structlog
from sqlalchemy import desc, func, select

from session.manager import AsyncSessionLocal, _snowflake_id
from session.models import AgentEvent, AgentRun

log = structlog.get_logger(__name__)

TERMINAL_STATUSES = {"COMPLETED", "FAILED", "CANCELED", "EXPIRED"}


def _new_run_id() -> str:
    return str(uuid.uuid4())


def _summary(message: str | None, limit: int = 200) -> str | None:
    if message is None:
        return None
    normalized = " ".join(message.split())
    if len(normalized) <= limit:
        return normalized
    return normalized[:limit] + "..."


class AgentRuntimeStore:
    """Persistence gateway for agent_run and agent_event."""

    async def get_run(self, run_id: str) -> AgentRun | None:
        async with AsyncSessionLocal() as db:
            return await db.get(AgentRun, run_id)

    async def get_latest_waiting_run_by_thread(self, thread_id: str) -> AgentRun | None:
        async with AsyncSessionLocal() as db:
            result = await db.execute(
                select(AgentRun)
                .where(
                    AgentRun.thread_id == thread_id,
                    AgentRun.status == "WAITING_APPROVAL",
                )
                .order_by(desc(AgentRun.created_at))
                .limit(1)
            )
            return result.scalars().first()

    async def get_latest_run_by_thread(self, thread_id: str) -> AgentRun | None:
        async with AsyncSessionLocal() as db:
            result = await db.execute(
                select(AgentRun)
                .where(AgentRun.thread_id == thread_id)
                .order_by(desc(AgentRun.created_at))
                .limit(1)
            )
            return result.scalars().first()

    async def create_run(
        self,
        *,
        session_id: int,
        thread_id: str,
        user_id: int,
        user_role: str,
        merchant_id: int | None,
        input_message: str,
        trace_id: str | None = None,
    ) -> str:
        run_id = _new_run_id()
        async with AsyncSessionLocal() as db:
            run = AgentRun(
                id=run_id,
                session_id=session_id,
                thread_id=thread_id,
                trace_id=trace_id,
                user_id=user_id,
                user_role=user_role,
                merchant_id=merchant_id,
                status="SUBMITTED",
                input_summary=_summary(input_message),
            )
            db.add(run)
            await db.commit()

        log.info("agent_run_created", run_id=run_id, session_id=session_id, thread_id=thread_id)
        return run_id

    async def mark_run_status(
        self,
        run_id: str,
        status: str,
        *,
        error_message: str | None = None,
    ) -> None:
        async with AsyncSessionLocal() as db:
            run = await db.get(AgentRun, run_id)
            if run is None:
                log.warning("agent_run_missing", run_id=run_id, status=status)
                return

            run.status = status
            run.error_message = error_message
            run.updated_at = datetime.now()
            if status == "RUNNING" and run.started_at is None:
                run.started_at = datetime.now()
            if status in TERMINAL_STATUSES:
                run.finished_at = datetime.now()
            await db.commit()

        log.info("agent_run_status_updated", run_id=run_id, status=status)

    async def append_event(
        self,
        *,
        run_id: str,
        session_id: int,
        thread_id: str,
        sequence_index: int,
        event_type: str,
        event_name: str | None,
        payload: dict[str, Any] | None,
        trace_id: str | None = None,
    ) -> int:
        event_id = _snowflake_id()
        async with AsyncSessionLocal() as db:
            event = AgentEvent(
                id=event_id,
                run_id=run_id,
                session_id=session_id,
                thread_id=thread_id,
                sequence_index=sequence_index,
                event_type=event_type,
                event_name=event_name,
                payload=payload,
                trace_id=trace_id,
            )
            db.add(event)
            await db.commit()

        log.debug(
            "agent_event_appended",
            run_id=run_id,
            event_type=event_type,
            event_name=event_name,
            sequence_index=sequence_index,
        )
        return event_id

    async def list_events(
        self,
        run_id: str,
        *,
        after_sequence: int = -1,
        limit: int = 100,
    ) -> list[AgentEvent]:
        async with AsyncSessionLocal() as db:
            result = await db.execute(
                select(AgentEvent)
                .where(
                    AgentEvent.run_id == run_id,
                    AgentEvent.sequence_index > after_sequence,
                )
                .order_by(AgentEvent.sequence_index.asc())
                .limit(limit)
            )
            return list(result.scalars().all())

    async def next_sequence(self, run_id: str) -> int:
        async with AsyncSessionLocal() as db:
            result = await db.execute(
                select(func.max(AgentEvent.sequence_index)).where(AgentEvent.run_id == run_id)
            )
            current_max = result.scalar_one_or_none()
            return int(current_max) + 1 if current_max is not None else 0


runtime_store = AgentRuntimeStore()
