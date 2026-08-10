"""LangGraph 4.x asynchronous MySQL checkpoint persistence."""

import json
from collections.abc import AsyncIterator, Sequence
from typing import Any, cast

import structlog
from langchain_core.runnables import RunnableConfig
from langgraph.checkpoint.base import (
    WRITES_IDX_MAP,
    BaseCheckpointSaver,
    ChannelVersions,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
    get_checkpoint_metadata,
)
from langgraph.checkpoint.serde.base import SerializerProtocol
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession

from session.hitl import HitlBindingError, hitl_service
from session.manager import AsyncSessionLocal

log = structlog.get_logger(__name__)


def _blob_bytes(value: Any) -> bytes:
    if isinstance(value, bytes):
        return value
    if isinstance(value, memoryview):
        return value.tobytes()
    if isinstance(value, bytearray):
        return bytes(value)
    raise TypeError(f"checkpoint blob must be bytes, got {type(value).__name__}")


def _metadata_dict(value: Any) -> CheckpointMetadata:
    if value is None:
        return cast(CheckpointMetadata, {})
    if isinstance(value, dict):
        return cast(CheckpointMetadata, value)
    if isinstance(value, bytes):
        value = value.decode("utf-8")
    if isinstance(value, str):
        return cast(CheckpointMetadata, json.loads(value))
    raise TypeError(f"checkpoint metadata must be JSON, got {type(value).__name__}")


class AsyncMySQLCheckpointer(BaseCheckpointSaver):
    """Store typed LangGraph checkpoints in the v2 Copilot tables."""

    def __init__(self, *, serde: SerializerProtocol | None = None) -> None:
        strict_serde = serde or JsonPlusSerializer(
            pickle_fallback=False,
            allowed_json_modules=None,
            allowed_msgpack_modules=None,
        )
        super().__init__(serde=strict_serde)

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        configurable = config["configurable"]
        thread_id = str(configurable["thread_id"])
        checkpoint_ns = str(configurable.get("checkpoint_ns", ""))
        checkpoint_id = configurable.get("checkpoint_id")

        async with AsyncSessionLocal() as db:
            if checkpoint_id:
                row = await self._fetch_one(
                    db,
                    thread_id,
                    checkpoint_ns,
                    str(checkpoint_id),
                )
            else:
                row = await self._fetch_latest(db, thread_id, checkpoint_ns)
            pending_rows = (
                await self._fetch_pending_writes(
                    db,
                    row["thread_id"],
                    row["checkpoint_ns"],
                    row["checkpoint_id"],
                )
                if row is not None
                else []
            )

        if row is None:
            return None
        return self._row_to_tuple(config, row, pending_rows)

    async def aget(self, config: RunnableConfig) -> Checkpoint | None:
        result = await self.aget_tuple(config)
        return result.checkpoint if result else None

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: ChannelVersions,
    ) -> RunnableConfig:
        configurable = config["configurable"]
        thread_id = str(configurable["thread_id"])
        checkpoint_ns = str(configurable.get("checkpoint_ns", ""))
        checkpoint_id = str(checkpoint["id"])
        parent_id = configurable.get("checkpoint_id")
        state_type, state_blob = self.serde.dumps_typed(checkpoint)
        stored_metadata = get_checkpoint_metadata(config, metadata)

        async with AsyncSessionLocal() as db:
            try:
                await db.execute(
                    text(
                        """
                        INSERT INTO langgraph_checkpoint_v2
                            (thread_id, checkpoint_ns, checkpoint_id,
                             parent_checkpoint_id, state_type, state_blob,
                             metadata, created_at)
                        VALUES
                            (:thread_id, :checkpoint_ns, :checkpoint_id,
                             :parent_id, :state_type, :state_blob,
                             :metadata, NOW(6))
                        ON DUPLICATE KEY UPDATE
                            parent_checkpoint_id = VALUES(parent_checkpoint_id),
                            state_type = VALUES(state_type),
                            state_blob = VALUES(state_blob),
                            metadata = VALUES(metadata)
                        """
                    ),
                    {
                        "thread_id": thread_id,
                        "checkpoint_ns": checkpoint_ns,
                        "checkpoint_id": checkpoint_id,
                        "parent_id": str(parent_id) if parent_id else None,
                        "state_type": state_type,
                        "state_blob": state_blob,
                        "metadata": json.dumps(
                            stored_metadata,
                            ensure_ascii=False,
                            default=str,
                        ),
                    },
                )
                binding = self._pending_hitl_binding(checkpoint)
                if binding is not None:
                    await hitl_service.bind_checkpoint(
                        db,
                        approval_id=binding["approval_id"],
                        thread_id=thread_id,
                        checkpoint_id=checkpoint_id,
                        payload_digest=binding["payload_digest"],
                    )
                await db.commit()
            except Exception:
                await db.rollback()
                raise

        log.debug(
            "checkpoint_saved",
            thread_id=thread_id,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            parent_id=parent_id,
        )
        return {
            "configurable": {
                "thread_id": thread_id,
                "checkpoint_ns": checkpoint_ns,
                "checkpoint_id": checkpoint_id,
            }
        }

    @staticmethod
    def _pending_hitl_binding(checkpoint: Checkpoint) -> dict[str, Any] | None:
        channel_values = checkpoint.get("channel_values") or {}
        if not channel_values.get("pending_hitl"):
            return None
        pending_action = channel_values.get("pending_action") or {}
        approval_id = pending_action.get("approval_id")
        payload_digest = pending_action.get("payload_digest")
        if (
            isinstance(approval_id, bool)
            or not isinstance(approval_id, int)
            or approval_id <= 0
            or not isinstance(payload_digest, str)
            or len(payload_digest) != 64
        ):
            raise HitlBindingError("pending HITL checkpoint is missing approval binding")
        return {
            "approval_id": approval_id,
            "payload_digest": payload_digest,
        }

    async def aput_writes(
        self,
        config: RunnableConfig,
        writes: Sequence[tuple[str, Any]],
        task_id: str,
        task_path: str = "",
    ) -> None:
        configurable = config["configurable"]
        thread_id = str(configurable["thread_id"])
        checkpoint_ns = str(configurable.get("checkpoint_ns", ""))
        checkpoint_id = str(configurable["checkpoint_id"])
        replace_special_writes = all(
            channel in WRITES_IDX_MAP for channel, _ in writes
        )
        upsert_clause = (
            """
            ON DUPLICATE KEY UPDATE
                task_path = VALUES(task_path),
                channel = VALUES(channel),
                value_type = VALUES(value_type),
                value_blob = VALUES(value_blob)
            """
            if replace_special_writes
            else ""
        )
        insert_prefix = "INSERT INTO" if replace_special_writes else "INSERT IGNORE INTO"

        async with AsyncSessionLocal() as db:
            try:
                for index, (channel, value) in enumerate(writes):
                    value_type, value_blob = self.serde.dumps_typed(value)
                    await db.execute(
                        text(
                            f"""
                            {insert_prefix} langgraph_checkpoint_write_v2
                                (thread_id, checkpoint_ns, checkpoint_id, task_id,
                                 task_path, write_index, channel, value_type,
                                 value_blob, created_at)
                            VALUES
                                (:thread_id, :checkpoint_ns, :checkpoint_id, :task_id,
                                 :task_path, :write_index, :channel, :value_type,
                                 :value_blob, NOW(6))
                            {upsert_clause}
                            """
                        ),
                        {
                            "thread_id": thread_id,
                            "checkpoint_ns": checkpoint_ns,
                            "checkpoint_id": checkpoint_id,
                            "task_id": task_id,
                            "task_path": task_path,
                            "write_index": WRITES_IDX_MAP.get(channel, index),
                            "channel": channel,
                            "value_type": value_type,
                            "value_blob": value_blob,
                        },
                    )
                await db.commit()
            except Exception:
                await db.rollback()
                raise

        log.debug(
            "checkpoint_pending_writes_saved",
            thread_id=thread_id,
            checkpoint_ns=checkpoint_ns,
            checkpoint_id=checkpoint_id,
            task_id=task_id,
            write_count=len(writes),
        )

    async def alist(
        self,
        config: RunnableConfig | None,
        *,
        filter: dict[str, Any] | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        conditions: list[str] = []
        params: dict[str, Any] = {}
        if config is not None:
            configurable = config["configurable"]
            conditions.extend(
                ["thread_id = :thread_id", "checkpoint_ns = :checkpoint_ns"]
            )
            params["thread_id"] = str(configurable["thread_id"])
            params["checkpoint_ns"] = str(configurable.get("checkpoint_ns", ""))
        if before is not None:
            before_id = before["configurable"].get("checkpoint_id")
            if before_id:
                conditions.append("checkpoint_id < :before_id")
                params["before_id"] = str(before_id)

        where_clause = " WHERE " + " AND ".join(conditions) if conditions else ""
        query = text(
            """
            SELECT thread_id, checkpoint_ns, checkpoint_id,
                   parent_checkpoint_id, state_type, state_blob,
                   metadata, created_at
            FROM langgraph_checkpoint_v2
            """
            + where_clause
            + " ORDER BY checkpoint_id DESC"
        )

        records: list[tuple[dict[str, Any], list[dict[str, Any]]]] = []
        async with AsyncSessionLocal() as db:
            result = await db.execute(query, params)
            for raw_row in result.fetchall():
                row = dict(raw_row._mapping)
                metadata = _metadata_dict(row.get("metadata"))
                if filter and any(metadata.get(key) != value for key, value in filter.items()):
                    continue
                pending_rows = await self._fetch_pending_writes(
                    db,
                    row["thread_id"],
                    row["checkpoint_ns"],
                    row["checkpoint_id"],
                )
                records.append((row, pending_rows))
                if limit is not None and len(records) >= limit:
                    break

        for row, pending_rows in records:
            yield self._row_to_tuple(config or {"configurable": {}}, row, pending_rows)

    async def adelete_thread(self, thread_id: str) -> None:
        """Delete only v2 rows for one LangGraph thread."""
        params = {"thread_id": str(thread_id)}
        async with AsyncSessionLocal() as db:
            try:
                await db.execute(
                    text(
                        "DELETE FROM langgraph_checkpoint_write_v2 "
                        "WHERE thread_id = :thread_id"
                    ),
                    params,
                )
                await db.execute(
                    text(
                        "DELETE FROM langgraph_checkpoint_v2 "
                        "WHERE thread_id = :thread_id"
                    ),
                    params,
                )
                await db.commit()
            except Exception:
                await db.rollback()
                raise

    async def _fetch_one(
        self,
        db: AsyncSession,
        thread_id: str,
        checkpoint_ns: str,
        checkpoint_id: str,
    ) -> dict[str, Any] | None:
        result = await db.execute(
            text(
                """
                SELECT thread_id, checkpoint_ns, checkpoint_id,
                       parent_checkpoint_id, state_type, state_blob,
                       metadata, created_at
                FROM langgraph_checkpoint_v2
                WHERE thread_id = :thread_id
                  AND checkpoint_ns = :checkpoint_ns
                  AND checkpoint_id = :checkpoint_id
                """
            ),
            {
                "thread_id": thread_id,
                "checkpoint_ns": checkpoint_ns,
                "checkpoint_id": checkpoint_id,
            },
        )
        row = result.fetchone()
        return dict(row._mapping) if row else None

    async def _fetch_latest(
        self,
        db: AsyncSession,
        thread_id: str,
        checkpoint_ns: str,
    ) -> dict[str, Any] | None:
        result = await db.execute(
            text(
                """
                SELECT thread_id, checkpoint_ns, checkpoint_id,
                       parent_checkpoint_id, state_type, state_blob,
                       metadata, created_at
                FROM langgraph_checkpoint_v2
                WHERE thread_id = :thread_id
                  AND checkpoint_ns = :checkpoint_ns
                ORDER BY checkpoint_id DESC
                LIMIT 1
                """
            ),
            {"thread_id": thread_id, "checkpoint_ns": checkpoint_ns},
        )
        row = result.fetchone()
        return dict(row._mapping) if row else None

    async def _fetch_pending_writes(
        self,
        db: AsyncSession,
        thread_id: str,
        checkpoint_ns: str,
        checkpoint_id: str,
    ) -> list[dict[str, Any]]:
        result = await db.execute(
            text(
                """
                SELECT task_id, channel, value_type, value_blob
                FROM langgraph_checkpoint_write_v2
                WHERE thread_id = :thread_id
                  AND checkpoint_ns = :checkpoint_ns
                  AND checkpoint_id = :checkpoint_id
                ORDER BY task_id ASC, write_index ASC
                """
            ),
            {
                "thread_id": thread_id,
                "checkpoint_ns": checkpoint_ns,
                "checkpoint_id": checkpoint_id,
            },
        )
        return [dict(row._mapping) for row in result.fetchall()]

    def _row_to_tuple(
        self,
        config: RunnableConfig,
        row: dict[str, Any],
        pending_rows: list[dict[str, Any]] | None = None,
    ) -> CheckpointTuple:
        thread_id = str(row["thread_id"])
        checkpoint_ns = str(row.get("checkpoint_ns") or "")
        checkpoint_id = str(row["checkpoint_id"])
        parent_id = row.get("parent_checkpoint_id")
        checkpoint = cast(
            Checkpoint,
            self.serde.loads_typed(
                (str(row["state_type"]), _blob_bytes(row["state_blob"]))
            ),
        )
        metadata = _metadata_dict(row.get("metadata"))
        pending_writes = [
            (
                str(pending["task_id"]),
                str(pending["channel"]),
                self.serde.loads_typed(
                    (
                        str(pending["value_type"]),
                        _blob_bytes(pending["value_blob"]),
                    )
                ),
            )
            for pending in (pending_rows or [])
        ]
        parent_config = (
            {
                "configurable": {
                    "thread_id": thread_id,
                    "checkpoint_ns": checkpoint_ns,
                    "checkpoint_id": str(parent_id),
                }
            }
            if parent_id
            else None
        )
        return CheckpointTuple(
            config={
                "configurable": {
                    "thread_id": thread_id,
                    "checkpoint_ns": checkpoint_ns,
                    "checkpoint_id": checkpoint_id,
                }
            },
            checkpoint=checkpoint,
            metadata=metadata,
            parent_config=parent_config,
            pending_writes=pending_writes or None,
        )
