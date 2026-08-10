"""Safe legacy-to-typed LangGraph checkpoint migration primitives."""

import json
from dataclasses import asdict, dataclass
from typing import Any, Protocol, cast

from langgraph.checkpoint.base import Checkpoint, WRITES_IDX_MAP
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncSession


class MigrationValidationError(RuntimeError):
    """Raised when legacy state cannot be migrated without ambiguity."""


def _legacy_bytes(value: str | bytes) -> bytes:
    return value.encode("utf-8") if isinstance(value, str) else value


def _contains_unresolved_constructor(value: Any) -> bool:
    if isinstance(value, dict):
        if value.get("lc") in {1, 2} and value.get("type") == "constructor":
            return True
        return any(_contains_unresolved_constructor(item) for item in value.values())
    if isinstance(value, (list, tuple)):
        return any(_contains_unresolved_constructor(item) for item in value)
    return False


class LegacyCheckpointCodec:
    """Decode legacy JSON and re-encode it with the strict typed serializer."""

    def __init__(self) -> None:
        self.serde = JsonPlusSerializer(
            pickle_fallback=False,
            allowed_json_modules=None,
            allowed_msgpack_modules=None,
        )

    def decode_legacy_json(self, payload: str | bytes) -> Any:
        try:
            value = self.serde.loads_typed(("json", _legacy_bytes(payload)))
        except Exception as exc:
            raise MigrationValidationError(
                f"legacy JSON is not safely decodable: {type(exc).__name__}"
            ) from exc
        if _contains_unresolved_constructor(value):
            raise MigrationValidationError(
                "legacy JSON contains an unresolved constructor"
            )
        return value

    def decode_checkpoint(
        self,
        payload: str | bytes,
        *,
        expected_checkpoint_id: str,
    ) -> Checkpoint:
        checkpoint = self.decode_legacy_json(payload)
        if not isinstance(checkpoint, dict):
            raise MigrationValidationError("legacy checkpoint must decode to an object")
        if checkpoint.get("id") != expected_checkpoint_id:
            raise MigrationValidationError(
                "checkpoint id mismatch: "
                f"source={expected_checkpoint_id} state={checkpoint.get('id')}"
            )
        required = {"channel_values", "channel_versions", "versions_seen"}
        missing = sorted(required.difference(checkpoint))
        if missing:
            raise MigrationValidationError(
                f"legacy checkpoint missing fields: {', '.join(missing)}"
            )
        return cast(Checkpoint, checkpoint)

    def encode(self, value: Any) -> tuple[str, bytes]:
        return self.serde.dumps_typed(value)

    def decode_typed(self, type_tag: str, blob: bytes) -> Any:
        return self.serde.loads_typed((type_tag, blob))


class SessionFactory(Protocol):
    def __call__(self) -> AsyncSession: ...


@dataclass(frozen=True)
class MigrationStats:
    mode: str
    checkpoints_scanned: int = 0
    writes_scanned: int = 0
    checkpoints_migrated: int = 0
    writes_migrated: int = 0
    checkpoints_verified: int = 0
    writes_verified: int = 0
    failures: int = 0

    def to_dict(self) -> dict[str, Any]:
        return asdict(self)


@dataclass(frozen=True)
class PreparedWrite:
    task_id: str
    task_path: str
    write_index: int
    channel: str
    value: Any
    value_type: str
    value_blob: bytes


@dataclass(frozen=True)
class PreparedCheckpoint:
    thread_id: str
    checkpoint_id: str
    parent_checkpoint_id: str | None
    checkpoint: Checkpoint
    state_type: str
    state_blob: bytes
    metadata: dict[str, Any]
    writes: tuple[PreparedWrite, ...]


def _json_dict(value: Any) -> dict[str, Any]:
    if value is None:
        return {}
    if isinstance(value, dict):
        return value
    if isinstance(value, bytes):
        value = value.decode("utf-8")
    if isinstance(value, str):
        result = json.loads(value)
        if isinstance(result, dict):
            return result
    raise MigrationValidationError("legacy metadata must be a JSON object")


class AsyncCheckpointMigrator:
    """Migrate legacy TEXT rows to namespace-aware typed v2 rows."""

    MODES = {"dry-run", "migrate", "verify-only"}

    def __init__(
        self,
        session_factory: SessionFactory,
        *,
        codec: LegacyCheckpointCodec | None = None,
    ) -> None:
        self.session_factory = session_factory
        self.codec = codec or LegacyCheckpointCodec()

    async def run(
        self,
        mode: str,
        *,
        thread_id: str | None = None,
        limit: int | None = None,
    ) -> MigrationStats:
        if mode not in self.MODES:
            raise ValueError(f"unsupported migration mode: {mode}")
        if limit is not None and limit <= 0:
            raise ValueError("limit must be positive")

        async with self.session_factory() as db:
            try:
                prepared = await self._prepare(db, thread_id=thread_id, limit=limit)
                writes_scanned = sum(len(item.writes) for item in prepared)
                if mode == "dry-run":
                    return MigrationStats(
                        mode=mode,
                        checkpoints_scanned=len(prepared),
                        writes_scanned=writes_scanned,
                    )
                if mode == "migrate":
                    for item in prepared:
                        await self._write_target(db, item)
                    await db.commit()
                    return MigrationStats(
                        mode=mode,
                        checkpoints_scanned=len(prepared),
                        writes_scanned=writes_scanned,
                        checkpoints_migrated=len(prepared),
                        writes_migrated=writes_scanned,
                    )

                checkpoints_verified = 0
                writes_verified = 0
                for item in prepared:
                    await self._verify_target(db, item)
                    checkpoints_verified += 1
                    writes_verified += len(item.writes)
                return MigrationStats(
                    mode=mode,
                    checkpoints_scanned=len(prepared),
                    writes_scanned=writes_scanned,
                    checkpoints_verified=checkpoints_verified,
                    writes_verified=writes_verified,
                )
            except Exception:
                await db.rollback()
                raise

    async def _prepare(
        self,
        db: AsyncSession,
        *,
        thread_id: str | None,
        limit: int | None,
    ) -> list[PreparedCheckpoint]:
        where_clause = " WHERE thread_id = :thread_id" if thread_id else ""
        limit_clause = " LIMIT :limit" if limit is not None else ""
        params: dict[str, Any] = {}
        if thread_id:
            params["thread_id"] = thread_id
        if limit is not None:
            params["limit"] = limit
        result = await db.execute(
            text(
                """
                SELECT thread_id, checkpoint_id, parent_checkpoint_id,
                       state, metadata
                FROM langgraph_checkpoint
                """
                + where_clause
                + " ORDER BY thread_id, checkpoint_id"
                + limit_clause
            ),
            params,
        )

        prepared: list[PreparedCheckpoint] = []
        for raw_row in result.fetchall():
            row = dict(raw_row._mapping)
            checkpoint_id = str(row["checkpoint_id"])
            try:
                checkpoint = self.codec.decode_checkpoint(
                    row["state"],
                    expected_checkpoint_id=checkpoint_id,
                )
                state_type, state_blob = self.codec.encode(checkpoint)
                writes = await self._prepare_writes(
                    db,
                    thread_id=str(row["thread_id"]),
                    checkpoint_id=checkpoint_id,
                )
                prepared.append(
                    PreparedCheckpoint(
                        thread_id=str(row["thread_id"]),
                        checkpoint_id=checkpoint_id,
                        parent_checkpoint_id=(
                            str(row["parent_checkpoint_id"])
                            if row.get("parent_checkpoint_id")
                            else None
                        ),
                        checkpoint=checkpoint,
                        state_type=state_type,
                        state_blob=state_blob,
                        metadata=_json_dict(row.get("metadata")),
                        writes=tuple(writes),
                    )
                )
            except Exception as exc:
                if isinstance(exc, MigrationValidationError):
                    raise MigrationValidationError(
                        f"checkpoint {checkpoint_id}: {exc}"
                    ) from exc
                raise
        return prepared

    async def _prepare_writes(
        self,
        db: AsyncSession,
        *,
        thread_id: str,
        checkpoint_id: str,
    ) -> list[PreparedWrite]:
        result = await db.execute(
            text(
                """
                SELECT task_id, task_path, write_index, channel, value
                FROM langgraph_checkpoint_write
                WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
                ORDER BY task_id, write_index
                """
            ),
            {"thread_id": thread_id, "checkpoint_id": checkpoint_id},
        )
        writes: list[PreparedWrite] = []
        target_identities: set[tuple[str, int]] = set()
        for raw_row in result.fetchall():
            row = dict(raw_row._mapping)
            task_id = str(row["task_id"])
            channel = str(row["channel"])
            write_index = WRITES_IDX_MAP.get(channel, int(row["write_index"]))
            identity = (task_id, write_index)
            if identity in target_identities:
                raise MigrationValidationError(
                    "legacy pending writes produce a v2 write identity collision: "
                    f"task_id={task_id} write_index={write_index}"
                )
            target_identities.add(identity)
            value = self.codec.decode_legacy_json(row["value"])
            value_type, value_blob = self.codec.encode(value)
            writes.append(
                PreparedWrite(
                    task_id=task_id,
                    task_path=str(row.get("task_path") or ""),
                    write_index=write_index,
                    channel=channel,
                    value=value,
                    value_type=value_type,
                    value_blob=value_blob,
                )
            )
        return writes

    async def _write_target(
        self,
        db: AsyncSession,
        item: PreparedCheckpoint,
    ) -> None:
        await db.execute(
            text(
                """
                INSERT INTO langgraph_checkpoint_v2
                    (thread_id, checkpoint_ns, checkpoint_id,
                     parent_checkpoint_id, state_type, state_blob,
                     metadata, created_at)
                VALUES
                    (:thread_id, '', :checkpoint_id,
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
                "thread_id": item.thread_id,
                "checkpoint_id": item.checkpoint_id,
                "parent_id": item.parent_checkpoint_id,
                "state_type": item.state_type,
                "state_blob": item.state_blob,
                "metadata": json.dumps(
                    item.metadata,
                    ensure_ascii=False,
                    default=str,
                ),
            },
        )
        for write in item.writes:
            await db.execute(
                text(
                    """
                    INSERT INTO langgraph_checkpoint_write_v2
                        (thread_id, checkpoint_ns, checkpoint_id, task_id,
                         task_path, write_index, channel, value_type,
                         value_blob, created_at)
                    VALUES
                        (:thread_id, '', :checkpoint_id, :task_id,
                         :task_path, :write_index, :channel, :value_type,
                         :value_blob, NOW(6))
                    ON DUPLICATE KEY UPDATE
                        task_path = VALUES(task_path),
                        channel = VALUES(channel),
                        value_type = VALUES(value_type),
                        value_blob = VALUES(value_blob)
                    """
                ),
                {
                    "thread_id": item.thread_id,
                    "checkpoint_id": item.checkpoint_id,
                    "task_id": write.task_id,
                    "task_path": write.task_path,
                    "write_index": write.write_index,
                    "channel": write.channel,
                    "value_type": write.value_type,
                    "value_blob": write.value_blob,
                },
            )

    async def _verify_target(
        self,
        db: AsyncSession,
        item: PreparedCheckpoint,
    ) -> None:
        result = await db.execute(
            text(
                """
                SELECT parent_checkpoint_id, state_type, state_blob, metadata
                FROM langgraph_checkpoint_v2
                WHERE thread_id = :thread_id
                  AND checkpoint_ns = ''
                  AND checkpoint_id = :checkpoint_id
                """
            ),
            {"thread_id": item.thread_id, "checkpoint_id": item.checkpoint_id},
        )
        row = result.fetchone()
        if row is None:
            raise MigrationValidationError(
                f"target checkpoint missing: {item.checkpoint_id}"
            )
        target = dict(row._mapping)
        target_checkpoint = self.codec.decode_typed(
            str(target["state_type"]),
            bytes(target["state_blob"]),
        )
        if target_checkpoint != item.checkpoint:
            raise MigrationValidationError(
                f"target checkpoint differs: {item.checkpoint_id}"
            )
        target_parent = (
            str(target["parent_checkpoint_id"])
            if target.get("parent_checkpoint_id")
            else None
        )
        if target_parent != item.parent_checkpoint_id:
            raise MigrationValidationError(
                f"target parent differs: {item.checkpoint_id}"
            )
        if _json_dict(target.get("metadata")) != item.metadata:
            raise MigrationValidationError(
                f"target metadata differs: {item.checkpoint_id}"
            )

        writes_result = await db.execute(
            text(
                """
                SELECT task_id, task_path, write_index, channel,
                       value_type, value_blob
                FROM langgraph_checkpoint_write_v2
                WHERE thread_id = :thread_id
                  AND checkpoint_ns = ''
                  AND checkpoint_id = :checkpoint_id
                ORDER BY task_id, write_index
                """
            ),
            {"thread_id": item.thread_id, "checkpoint_id": item.checkpoint_id},
        )
        target_writes = [dict(row._mapping) for row in writes_result.fetchall()]
        if len(target_writes) != len(item.writes):
            raise MigrationValidationError(
                f"target pending write count differs: {item.checkpoint_id}"
            )
        for target_write, source_write in zip(target_writes, item.writes):
            identity = (
                str(target_write["task_id"]),
                str(target_write.get("task_path") or ""),
                int(target_write["write_index"]),
                str(target_write["channel"]),
            )
            source_identity = (
                source_write.task_id,
                source_write.task_path,
                source_write.write_index,
                source_write.channel,
            )
            target_value = self.codec.decode_typed(
                str(target_write["value_type"]),
                bytes(target_write["value_blob"]),
            )
            if identity != source_identity or target_value != source_write.value:
                raise MigrationValidationError(
                    f"target pending write differs: {item.checkpoint_id}"
                )
