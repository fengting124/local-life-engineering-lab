from unittest.mock import AsyncMock

import pytest
from langgraph.checkpoint.base import WRITES_IDX_MAP

from session import checkpointer as ckpt_mod
from session.checkpointer import AsyncMySQLCheckpointer
from session.hitl import HitlBindingError


class FakeSession:
    def __init__(self):
        self.executed = []
        self.commits = 0
        self.rollbacks = 0

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def execute(self, statement, params=None):
        self.executed.append((str(statement), params))

    async def commit(self):
        self.commits += 1

    async def rollback(self):
        self.rollbacks += 1


def _hitl_checkpoint(approval_id=1001, digest="a" * 64):
    return {
        "v": 1,
        "ts": "2026-08-10T00:00:00+00:00",
        "id": "checkpoint-hitl-1",
        "channel_values": {
            "pending_hitl": True,
            "pending_action": {
                "approval_id": approval_id,
                "payload_digest": digest,
            },
        },
        "channel_versions": {},
        "versions_seen": {},
    }


def _checkpoint(checkpoint_id="checkpoint-1"):
    return {
        "v": 1,
        "ts": "2026-08-10T00:00:00+00:00",
        "id": checkpoint_id,
        "channel_values": {},
        "channel_versions": {},
        "versions_seen": {},
        "pending_sends": [],
    }


def test_checkpointer_uses_strict_typed_serializer():
    saver = AsyncMySQLCheckpointer()
    second_saver = AsyncMySQLCheckpointer()

    assert second_saver.serde is not saver.serde
    assert saver.serde.pickle_fallback is False
    assert saver.serde._allowed_json_modules is None
    assert saver.serde._allowed_msgpack_modules is None


@pytest.mark.asyncio
async def test_aput_binds_pending_approval_in_checkpoint_transaction(monkeypatch):
    fake_session = FakeSession()
    binding = AsyncMock()
    monkeypatch.setattr(ckpt_mod, "AsyncSessionLocal", lambda: fake_session)
    monkeypatch.setattr(ckpt_mod.hitl_service, "bind_checkpoint", binding)
    saver = AsyncMySQLCheckpointer()

    result = await saver.aput(
        {"configurable": {"thread_id": "thread-1"}},
        _hitl_checkpoint(),
        {"step": "hitl"},
        {},
    )

    assert fake_session.commits == 1
    assert fake_session.rollbacks == 0
    assert len(fake_session.executed) == 1
    binding.assert_awaited_once_with(
        fake_session,
        approval_id=1001,
        thread_id="thread-1",
        checkpoint_id="checkpoint-hitl-1",
        payload_digest="a" * 64,
    )
    assert result["configurable"]["checkpoint_id"] == "checkpoint-hitl-1"
    assert result["configurable"]["checkpoint_ns"] == ""
    params = fake_session.executed[0][1]
    assert params["checkpoint_ns"] == ""
    assert isinstance(params["state_blob"], bytes)
    assert params["state_type"] in {"msgpack", "json", "bytes", "null"}


@pytest.mark.asyncio
async def test_aput_rolls_back_checkpoint_when_approval_binding_fails(monkeypatch):
    fake_session = FakeSession()
    monkeypatch.setattr(ckpt_mod, "AsyncSessionLocal", lambda: fake_session)
    monkeypatch.setattr(
        ckpt_mod.hitl_service,
        "bind_checkpoint",
        AsyncMock(side_effect=HitlBindingError("checkpoint binding mismatch")),
    )

    with pytest.raises(HitlBindingError, match="mismatch"):
        await AsyncMySQLCheckpointer().aput(
            {"configurable": {"thread_id": "thread-1"}},
            _hitl_checkpoint(),
            {"step": "hitl"},
            {},
        )

    assert fake_session.commits == 0
    assert fake_session.rollbacks == 1


@pytest.mark.asyncio
async def test_aput_writes_persists_each_pending_write(monkeypatch):
    fake_session = FakeSession()
    monkeypatch.setattr(ckpt_mod, "AsyncSessionLocal", lambda: fake_session)

    saver = AsyncMySQLCheckpointer()
    config = {
        "configurable": {
            "thread_id": "thread-1",
            "checkpoint_ns": "merchant:7",
            "checkpoint_id": "checkpoint-1",
        }
    }

    await saver.aput_writes(
        config,
        writes=[
            ("messages", {"content": "需要审批"}),
            ("audit", ["tool_call", "blocked"]),
        ],
        task_id="task-1",
        task_path="graph:hitl_node",
    )

    assert fake_session.commits == 1
    assert len(fake_session.executed) == 2
    params = [call[1] for call in fake_session.executed]
    assert {p["write_index"] for p in params} == {0, 1}
    assert all(p["thread_id"] == "thread-1" for p in params)
    assert all(p["checkpoint_ns"] == "merchant:7" for p in params)
    assert all(p["checkpoint_id"] == "checkpoint-1" for p in params)
    assert all(p["task_id"] == "task-1" for p in params)
    assert all(p["task_path"] == "graph:hitl_node" for p in params)
    assert {p["channel"] for p in params} == {"messages", "audit"}
    assert all(isinstance(p["value_blob"], bytes) for p in params)
    assert all(p["value_type"] in {"msgpack", "json", "bytes", "null"} for p in params)


@pytest.mark.asyncio
async def test_aput_writes_uses_reserved_indices_for_special_channels(monkeypatch):
    fake_session = FakeSession()
    monkeypatch.setattr(ckpt_mod, "AsyncSessionLocal", lambda: fake_session)

    await AsyncMySQLCheckpointer().aput_writes(
        {
            "configurable": {
                "thread_id": "thread-1",
                "checkpoint_ns": "",
                "checkpoint_id": "checkpoint-1",
            }
        },
        writes=[("messages", "normal"), ("__error__", "failed")],
        task_id="task-1",
    )

    params = [call[1] for call in fake_session.executed]
    assert params[0]["write_index"] == 0
    assert params[1]["write_index"] == WRITES_IDX_MAP["__error__"]


@pytest.mark.asyncio
async def test_recreated_checkpointer_reads_exact_checkpoint_with_pending_writes(
    monkeypatch,
):
    fake_session = FakeSession()
    monkeypatch.setattr(ckpt_mod, "AsyncSessionLocal", lambda: fake_session)

    original_saver = AsyncMySQLCheckpointer()
    checkpoint = _checkpoint()
    state_type, serialized_state = original_saver.serde.dumps_typed(checkpoint)
    row = {
        "thread_id": "thread-1",
        "checkpoint_ns": "merchant:7",
        "checkpoint_id": "checkpoint-1",
        "parent_checkpoint_id": None,
        "state_type": state_type,
        "state_blob": serialized_state,
        "metadata": {"step": "hitl"},
    }
    pending_type, pending_payload = original_saver.serde.dumps_typed(
        {"content": "恢复前未合并的输出"}
    )

    # Simulate an Agent process restart: recovery uses a fresh saver instance,
    # not any in-memory state from the instance that serialized the checkpoint.
    restarted_saver = AsyncMySQLCheckpointer()
    restarted_saver._fetch_one = AsyncMock(return_value=row)
    restarted_saver._fetch_latest = AsyncMock(
        side_effect=AssertionError("bound HITL recovery must not read latest")
    )
    restarted_saver._fetch_pending_writes = AsyncMock(
        return_value=[
            {
                "task_id": "task-1",
                "channel": "messages",
                "value_type": pending_type,
                "value_blob": pending_payload,
            }
        ]
    )

    result = await restarted_saver.aget_tuple(
        {
            "configurable": {
                "thread_id": "thread-1",
                "checkpoint_ns": "merchant:7",
                "checkpoint_id": "checkpoint-1",
            }
        }
    )

    assert result is not None
    assert result.pending_writes == [
        ("task-1", "messages", {"content": "恢复前未合并的输出"})
    ]
    restarted_saver._fetch_one.assert_awaited_once_with(
        fake_session, "thread-1", "merchant:7", "checkpoint-1"
    )
    restarted_saver._fetch_latest.assert_not_awaited()
    restarted_saver._fetch_pending_writes.assert_awaited_once_with(
        fake_session, "thread-1", "merchant:7", "checkpoint-1"
    )
    assert result.config["configurable"]["checkpoint_ns"] == "merchant:7"


@pytest.mark.asyncio
async def test_latest_checkpoint_read_is_isolated_by_namespace(monkeypatch):
    fake_session = FakeSession()
    monkeypatch.setattr(ckpt_mod, "AsyncSessionLocal", lambda: fake_session)
    saver = AsyncMySQLCheckpointer()
    saver._fetch_latest = AsyncMock(return_value=None)

    result = await saver.aget_tuple(
        {"configurable": {"thread_id": "thread-1", "checkpoint_ns": "shop:9"}}
    )

    assert result is None
    saver._fetch_latest.assert_awaited_once_with(fake_session, "thread-1", "shop:9")


def test_parent_config_preserves_namespace():
    saver = AsyncMySQLCheckpointer()
    checkpoint = _checkpoint("checkpoint-child")
    state_type, state_blob = saver.serde.dumps_typed(checkpoint)

    result = saver._row_to_tuple(
        {"configurable": {"thread_id": "thread-1", "checkpoint_ns": "shop:9"}},
        {
            "thread_id": "thread-1",
            "checkpoint_ns": "shop:9",
            "checkpoint_id": "checkpoint-child",
            "parent_checkpoint_id": "checkpoint-parent",
            "state_type": state_type,
            "state_blob": state_blob,
            "metadata": {},
        },
        [],
    )

    assert result.parent_config == {
        "configurable": {
            "thread_id": "thread-1",
            "checkpoint_ns": "shop:9",
            "checkpoint_id": "checkpoint-parent",
        }
    }


@pytest.mark.asyncio
async def test_delete_thread_removes_v2_writes_before_checkpoints(monkeypatch):
    fake_session = FakeSession()
    monkeypatch.setattr(ckpt_mod, "AsyncSessionLocal", lambda: fake_session)

    await AsyncMySQLCheckpointer().adelete_thread("thread-1")

    assert fake_session.commits == 1
    assert len(fake_session.executed) == 2
    statements = [call[0] for call in fake_session.executed]
    assert "langgraph_checkpoint_write_v2" in statements[0]
    assert "langgraph_checkpoint_v2" in statements[1]
    assert all(call[1] == {"thread_id": "thread-1"} for call in fake_session.executed)
