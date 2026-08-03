import json
from unittest.mock import AsyncMock

import pytest

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
    assert all(p["checkpoint_id"] == "checkpoint-1" for p in params)
    assert all(p["task_id"] == "task-1" for p in params)
    assert all(p["task_path"] == "graph:hitl_node" for p in params)
    assert {p["channel"] for p in params} == {"messages", "audit"}


@pytest.mark.asyncio
async def test_aget_tuple_returns_deserialized_pending_writes(monkeypatch):
    fake_session = FakeSession()
    monkeypatch.setattr(ckpt_mod, "AsyncSessionLocal", lambda: fake_session)

    saver = AsyncMySQLCheckpointer()
    checkpoint = {
        "id": "checkpoint-1",
        "channel_values": {},
        "channel_versions": {},
        "versions_seen": {},
    }
    serialized_state = saver.serde.dumps(checkpoint)
    row = {
        "thread_id": "thread-1",
        "checkpoint_id": "checkpoint-1",
        "parent_checkpoint_id": None,
        "state": serialized_state.decode("utf-8")
        if isinstance(serialized_state, bytes)
        else serialized_state,
        "metadata": json.dumps({"step": "hitl"}),
    }
    pending_payload = saver.serde.dumps({"content": "恢复前未合并的输出"})
    encoded_pending_payload = (
        pending_payload.decode("utf-8")
        if isinstance(pending_payload, bytes)
        else pending_payload
    )

    saver._fetch_one = AsyncMock(return_value=row)
    saver._fetch_pending_writes = AsyncMock(
        return_value=[
            {
                "task_id": "task-1",
                "channel": "messages",
                "value": encoded_pending_payload,
            }
        ]
    )

    result = await saver.aget_tuple(
        {
            "configurable": {
                "thread_id": "thread-1",
                "checkpoint_id": "checkpoint-1",
            }
        }
    )

    assert result is not None
    assert result.pending_writes == [
        ("task-1", "messages", {"content": "恢复前未合并的输出"})
    ]
    saver._fetch_pending_writes.assert_awaited_once_with(
        fake_session, "thread-1", "checkpoint-1"
    )
