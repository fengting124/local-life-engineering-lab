"""
LangGraph 异步 MySQL Checkpointer。

为什么需要持久化 Checkpointer？
  - 默认的 MemorySaver 是进程内内存，服务重启后所有状态丢失
  - HITL 场景：Agent 挂起等待审批（可能需要数小时），期间服务重启了
    → MemorySaver 丢失状态 → 审批通过但 Agent 无法恢复
  - 生产必须使用持久化 Checkpointer

LangGraph Checkpointer 协议（v0.2.x）：
  aget_tuple(config)           → CheckpointTuple | None   （读最新快照）
  aput(config, ckpt, meta, ...) → RunnableConfig          （写快照）
  alist(config, ...)            → AsyncIterator[...]       （列出历史）

序列化：
  LangGraph 的 JsonPlusSerializer 负责序列化 state（含 LangChain Message 对象）。
  序列化后存 TEXT，反序列化时还原为原始 Python 对象。

数据库表：langgraph_checkpoint（由 Copilot DB V1 Migration 创建）
  PRIMARY KEY (thread_id, checkpoint_id)
  state TEXT（JsonPlusSerializer 序列化的状态）
  metadata JSON（CheckpointMetadata）
  parent_checkpoint_id（形成快照链）
"""
import json
from typing import AsyncIterator, Any, Sequence
import structlog

from sqlalchemy import text, select
from sqlalchemy.ext.asyncio import AsyncSession

from langgraph.checkpoint.base import (
    BaseCheckpointSaver,
    Checkpoint,
    CheckpointMetadata,
    CheckpointTuple,
    SerializerProtocol,
)
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langchain_core.runnables import RunnableConfig

from session.manager import AsyncSessionLocal
from session.hitl import HitlBindingError, hitl_service

log = structlog.get_logger(__name__)


class AsyncMySQLCheckpointer(BaseCheckpointSaver):
    """
    异步 MySQL Checkpointer，基于 langgraph_checkpoint 表。

    使用方法：
      checkpointer = AsyncMySQLCheckpointer()
      graph = builder.compile(checkpointer=checkpointer)

    线程安全：使用 SQLAlchemy async session，每次操作开新 session，无共享状态。

    性能考虑：
      - `aget_tuple` 走主键查询（thread_id + checkpoint_id），O(1)
      - `alist` 只在 HITL 恢复场景用，频率低
      - checkpoint state 可能较大（含消息历史），但每条 < 100KB，MySQL TEXT 足够
    """

    # LangGraph 官方序列化器，处理 LangChain Message / ToolCall 等对象
    serde: SerializerProtocol = JsonPlusSerializer()

    # =========================================================
    # 读：按配置获取精确或最新 checkpoint
    # =========================================================

    async def aget_tuple(self, config: RunnableConfig) -> CheckpointTuple | None:
        """
        获取配置指定的精确 checkpoint；未提供 checkpoint_id 时获取最新快照。

        LangGraph 在每次节点执行前调用此方法恢复状态。
        查询逻辑：
          - 若 config 中有 checkpoint_id → 查精确快照
          - 否则查 thread_id 下最新的快照（ORDER BY created_at DESC LIMIT 1）
        """
        thread_id     = config["configurable"]["thread_id"]
        checkpoint_id = config["configurable"].get("checkpoint_id")

        pending_rows = []
        async with AsyncSessionLocal() as db:
            if checkpoint_id:
                row = await self._fetch_one(db, thread_id, checkpoint_id)
            else:
                row = await self._fetch_latest(db, thread_id)
            if row is not None:
                pending_rows = await self._fetch_pending_writes(
                    db,
                    row["thread_id"],
                    row["checkpoint_id"],
                )

        if row is None:
            return None

        return self._row_to_tuple(config, row, pending_rows)

    async def aget(self, config: RunnableConfig) -> Checkpoint | None:
        """获取 checkpoint（无需 parent 信息时用此方法）。"""
        result = await self.aget_tuple(config)
        return result.checkpoint if result else None

    # =========================================================
    # 写：保存 checkpoint
    # =========================================================

    async def aput(
        self,
        config: RunnableConfig,
        checkpoint: Checkpoint,
        metadata: CheckpointMetadata,
        new_versions: dict,
    ) -> RunnableConfig:
        """
        保存 checkpoint 快照（LangGraph 在每个节点结束后调用）。

        写入时机：
          - llm_node 执行完毕 → 保存 LLM 推理结果
          - tool_node 执行完毕 → 保存工具调用结果（Observation）
          - hitl_node 执行完毕 → 保存挂起状态（最关键的一次写入）

        序列化：JsonPlusSerializer 将 checkpoint dict（含 LangChain Message）转为 JSON string。
        """
        thread_id     = config["configurable"]["thread_id"]
        checkpoint_id = checkpoint["id"]
        parent_id     = config["configurable"].get("checkpoint_id")

        # 序列化 state（含 LangChain Message 等复杂对象）
        serialized_state = self.serde.dumps(checkpoint)

        async with AsyncSessionLocal() as db:
            try:
                await db.execute(
                    text("""
                    INSERT INTO langgraph_checkpoint
                        (thread_id, checkpoint_id, parent_checkpoint_id, state, metadata, created_at)
                    VALUES
                        (:thread_id, :checkpoint_id, :parent_id, :state, :metadata, NOW())
                    ON DUPLICATE KEY UPDATE
                        state    = VALUES(state),
                        metadata = VALUES(metadata)
                    """),
                    {
                        "thread_id":     thread_id,
                        "checkpoint_id": checkpoint_id,
                        "parent_id":     parent_id,
                        "state":         serialized_state.decode("utf-8") if isinstance(serialized_state, bytes) else serialized_state,
                        "metadata":      json.dumps(metadata, default=str),
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
            checkpoint_id=checkpoint_id,
            parent_id=parent_id,
        )

        return {**config, "configurable": {**config["configurable"], "checkpoint_id": checkpoint_id}}

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
        """
        保存 pending writes（LangGraph 内部用，记录尚未合并的节点输出）。

        LangGraph 在 interrupt、并发节点或节点输出尚未合并到完整 checkpoint 时
        会调用此方法。生产环境必须持久化这些 write，否则服务重启后恢复任务时
        可能丢失“已经产生但还没合并”的节点输出。
        """
        thread_id = config["configurable"]["thread_id"]
        checkpoint_id = config["configurable"]["checkpoint_id"]

        async with AsyncSessionLocal() as db:
            for write_index, (channel, value) in enumerate(writes):
                serialized_value = self.serde.dumps(value)
                await db.execute(
                    text("""
                    INSERT INTO langgraph_checkpoint_write
                        (thread_id, checkpoint_id, task_id, task_path, write_index, channel, value, created_at)
                    VALUES
                        (:thread_id, :checkpoint_id, :task_id, :task_path, :write_index, :channel, :value, NOW())
                    ON DUPLICATE KEY UPDATE
                        task_path = VALUES(task_path),
                        channel   = VALUES(channel),
                        value     = VALUES(value)
                    """),
                    {
                        "thread_id": thread_id,
                        "checkpoint_id": checkpoint_id,
                        "task_id": task_id,
                        "task_path": task_path,
                        "write_index": write_index,
                        "channel": channel,
                        "value": serialized_value.decode("utf-8")
                        if isinstance(serialized_value, bytes)
                        else serialized_value,
                    },
                )
            await db.commit()

        log.debug(
            "checkpoint_pending_writes_saved",
            thread_id=thread_id,
            checkpoint_id=checkpoint_id,
            task_id=task_id,
            write_count=len(writes),
        )

    # =========================================================
    # 列表：HITL 恢复时浏览历史快照
    # =========================================================

    async def alist(
        self,
        config: RunnableConfig,
        *,
        filter: dict | None = None,
        before: RunnableConfig | None = None,
        limit: int | None = None,
    ) -> AsyncIterator[CheckpointTuple]:
        """列出 thread 下所有 checkpoint（按时间降序）。"""
        thread_id = config["configurable"]["thread_id"]
        lim = limit or 10

        async with AsyncSessionLocal() as db:
            result = await db.execute(
                text("""
                SELECT thread_id, checkpoint_id, parent_checkpoint_id, state, metadata, created_at
                FROM langgraph_checkpoint
                WHERE thread_id = :thread_id
                ORDER BY created_at DESC
                LIMIT :lim
                """),
                {"thread_id": thread_id, "lim": lim},
            )
            rows = [dict(row._mapping) for row in result.fetchall()]
            records = [
                (
                    row,
                    await self._fetch_pending_writes(
                        db,
                        row["thread_id"],
                        row["checkpoint_id"],
                    ),
                )
                for row in rows
            ]

        for row, pending_rows in records:
            yield self._row_to_tuple(config, row, pending_rows)

    # =========================================================
    # 私有工具方法
    # =========================================================

    async def _fetch_one(self, db: AsyncSession, thread_id: str, checkpoint_id: str):
        result = await db.execute(
            text("""
            SELECT thread_id, checkpoint_id, parent_checkpoint_id, state, metadata, created_at
            FROM langgraph_checkpoint
            WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
            """),
            {"thread_id": thread_id, "checkpoint_id": checkpoint_id},
        )
        row = result.fetchone()
        return dict(row._mapping) if row else None

    async def _fetch_latest(self, db: AsyncSession, thread_id: str):
        result = await db.execute(
            text("""
            SELECT thread_id, checkpoint_id, parent_checkpoint_id, state, metadata, created_at
            FROM langgraph_checkpoint
            WHERE thread_id = :thread_id
            ORDER BY created_at DESC
            LIMIT 1
            """),
            {"thread_id": thread_id},
        )
        row = result.fetchone()
        return dict(row._mapping) if row else None

    async def _fetch_pending_writes(
        self,
        db: AsyncSession,
        thread_id: str,
        checkpoint_id: str,
    ) -> list[dict]:
        result = await db.execute(
            text("""
            SELECT task_id, channel, value
            FROM langgraph_checkpoint_write
            WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
            ORDER BY task_id ASC, write_index ASC
            """),
            {"thread_id": thread_id, "checkpoint_id": checkpoint_id},
        )
        return [dict(row._mapping) for row in result.fetchall()]

    def _row_to_tuple(
        self,
        config: RunnableConfig,
        row: dict,
        pending_rows: list[dict] | None = None,
    ) -> CheckpointTuple:
        """将数据库行转换为 LangGraph CheckpointTuple。"""
        thread_id     = row["thread_id"]
        checkpoint_id = row["checkpoint_id"]
        parent_id     = row.get("parent_checkpoint_id")

        # 反序列化 state（还原 LangChain Message 等对象）
        state_str = row["state"]
        if isinstance(state_str, str):
            state_bytes = state_str.encode("utf-8")
        else:
            state_bytes = state_str
        checkpoint: Checkpoint = self.serde.loads(state_bytes)

        # 反序列化 metadata
        meta_str  = row.get("metadata") or "{}"
        metadata: CheckpointMetadata = json.loads(meta_str) if isinstance(meta_str, str) else meta_str

        pending_writes = [
            (
                pending["task_id"],
                pending["channel"],
                self.serde.loads(
                    pending["value"].encode("utf-8")
                    if isinstance(pending["value"], str)
                    else pending["value"]
                ),
            )
            for pending in (pending_rows or [])
        ]

        # 构建 parent config（用于 checkpoint 链追踪）
        parent_config = (
            {"configurable": {"thread_id": thread_id, "checkpoint_id": parent_id}}
            if parent_id else None
        )

        return CheckpointTuple(
            config={
                "configurable": {
                    "thread_id":     thread_id,
                    "checkpoint_id": checkpoint_id,
                }
            },
            checkpoint=checkpoint,
            metadata=metadata,
            parent_config=parent_config,
            pending_writes=pending_writes or None,
        )
