import json
import inspect
from collections import Counter
from unittest.mock import AsyncMock
from urllib.parse import quote_plus

import ormsgpack
import pytest
import pytest_asyncio
from langgraph.checkpoint.serde.jsonplus import (
    EXT_CONSTRUCTOR_SINGLE_ARG,
    JsonPlusSerializer,
)
from langgraph.graph import END, StateGraph
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.community.mysql import MySqlContainer

from session import checkpointer as checkpointer_module
from session.checkpointer import AsyncMySQLCheckpointer


def _checkpoint(checkpoint_id: str = "checkpoint-security-1") -> dict:
    return {
        "v": 1,
        "ts": "2026-08-05T00:00:00+00:00",
        "id": checkpoint_id,
        "channel_values": {"marker": "original"},
        "channel_versions": {},
        "versions_seen": {},
        "pending_sends": [],
    }


def _harmless_constructor_marker(label: str) -> dict:
    return {
        "lc": 2,
        "type": "constructor",
        "id": ["collections", "Counter"],
        "args": [[label]],
    }


def _harmless_msgpack_marker(label: str) -> bytes:
    constructor = ormsgpack.packb(("collections", "Counter", [label]))
    return ormsgpack.packb(
        ormsgpack.Ext(EXT_CONSTRUCTOR_SINGLE_ARG, constructor)
    )


class TrackingSerializer:
    def __init__(self) -> None:
        self.delegate = JsonPlusSerializer()
        self.loads_typed_calls = 0

    def dumps(self, value):
        return self.delegate.dumps(value)

    def loads(self, value):
        return self.delegate.loads(value)

    def dumps_typed(self, value):
        return self.delegate.dumps_typed(value)

    def loads_typed(self, value):
        self.loads_typed_calls += 1
        return self.delegate.loads_typed(value)


@pytest.fixture(scope="module")
def isolated_mysql():
    with MySqlContainer(
        "mysql:8.4",
        dialect="pymysql",
        dbname="checkpoint_security",
    ) as mysql:
        yield {
            "host": mysql.get_container_host_ip(),
            "port": mysql.get_exposed_port(3306),
            "username": mysql.username,
            "password": mysql.password,
            "database": mysql.dbname,
        }


@pytest_asyncio.fixture
async def checkpoint_db(isolated_mysql, monkeypatch):
    url = (
        "mysql+aiomysql://"
        f"{quote_plus(isolated_mysql['username'])}:"
        f"{quote_plus(isolated_mysql['password'])}@"
        f"{isolated_mysql['host']}:{isolated_mysql['port']}/"
        f"{isolated_mysql['database']}?charset=utf8mb4"
    )
    engine = create_async_engine(url, pool_pre_ping=False)
    async with engine.begin() as connection:
        await connection.execute(text("DROP TABLE IF EXISTS langgraph_checkpoint_write"))
        await connection.execute(text("DROP TABLE IF EXISTS langgraph_checkpoint"))
        await connection.execute(
            text(
                """
                CREATE TABLE langgraph_checkpoint (
                    thread_id VARCHAR(64) NOT NULL,
                    checkpoint_id VARCHAR(64) NOT NULL,
                    parent_checkpoint_id VARCHAR(64) NULL,
                    state LONGTEXT NOT NULL,
                    metadata JSON NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (thread_id, checkpoint_id)
                ) CHARACTER SET utf8mb4
                """
            )
        )
        await connection.execute(
            text(
                """
                CREATE TABLE langgraph_checkpoint_write (
                    thread_id VARCHAR(64) NOT NULL,
                    checkpoint_id VARCHAR(64) NOT NULL,
                    task_id VARCHAR(128) NOT NULL,
                    task_path VARCHAR(255) NOT NULL DEFAULT '',
                    write_index INT NOT NULL,
                    channel VARCHAR(128) NOT NULL,
                    value LONGTEXT NOT NULL,
                    created_at DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                    PRIMARY KEY (thread_id, checkpoint_id, task_id, write_index)
                ) CHARACTER SET utf8mb4
                """
            )
        )
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    monkeypatch.setattr(
        checkpointer_module,
        "AsyncSessionLocal",
        session_factory,
    )
    yield session_factory
    await engine.dispose()


async def _save_checkpoint(saver: AsyncMySQLCheckpointer) -> dict:
    return await saver.aput(
        {"configurable": {"thread_id": "thread-security"}},
        _checkpoint(),
        {"source": "security-assessment"},
        {},
    )


@pytest.mark.asyncio
async def test_compiled_graph_get_state_delegates_to_custom_aget_tuple():
    saver = AsyncMySQLCheckpointer()
    saver.aget_tuple = AsyncMock(return_value=None)
    builder = StateGraph(dict)
    builder.add_node("noop", lambda state: state)
    builder.set_entry_point("noop")
    builder.add_edge("noop", END)
    graph = builder.compile(checkpointer=saver)
    config = {"configurable": {"thread_id": "thread-security"}}

    await graph.aget_state(config)

    saver.aget_tuple.assert_awaited_once_with(config)


@pytest.mark.asyncio
async def test_json_checkpoint_tamper_reconstructs_only_harmless_marker(
    checkpoint_db,
):
    saver = AsyncMySQLCheckpointer()
    config = await _save_checkpoint(saver)
    tampered = _checkpoint()
    tampered["channel_values"]["marker"] = _harmless_constructor_marker(
        "json-checkpoint-marker"
    )
    async with checkpoint_db() as db:
        await db.execute(
            text(
                """
                UPDATE langgraph_checkpoint
                SET state = :state
                WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
                """
            ),
            {
                "state": json.dumps(tampered),
                "thread_id": "thread-security",
                "checkpoint_id": _checkpoint()["id"],
            },
        )
        await db.commit()

    restored = await saver.aget_tuple(config)

    assert restored is not None
    assert restored.checkpoint["channel_values"]["marker"] == Counter(
        ["json-checkpoint-marker"]
    )


@pytest.mark.asyncio
async def test_msgpack_state_does_not_reach_typed_deserializer(checkpoint_db):
    saver = AsyncMySQLCheckpointer()
    tracker = TrackingSerializer()
    saver.serde = tracker
    config = await _save_checkpoint(saver)
    benign_msgpack = ormsgpack.packb(42)
    assert benign_msgpack == b"*"
    async with checkpoint_db() as db:
        await db.execute(
            text(
                """
                UPDATE langgraph_checkpoint
                SET state = :state
                WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
                """
            ),
            {
                "state": benign_msgpack,
                "thread_id": "thread-security",
                "checkpoint_id": _checkpoint()["id"],
            },
        )
        await db.commit()

    with pytest.raises(json.JSONDecodeError):
        await saver.aget_tuple(config)

    assert tracker.loads_typed_calls == 0


def test_dependency_loads_typed_reconstructs_only_harmless_marker():
    marker = JsonPlusSerializer().loads_typed(
        ("msgpack", _harmless_msgpack_marker("typed-dependency-marker"))
    )

    assert marker == Counter(["typed-dependency-marker"])


def test_fixed_serializer_strict_mode_blocks_unregistered_msgpack_marker():
    parameters = inspect.signature(JsonPlusSerializer).parameters
    if "allowed_msgpack_modules" not in parameters:
        pytest.skip("strict msgpack allowlist is unavailable in the affected version")

    marker = JsonPlusSerializer(allowed_msgpack_modules=None).loads_typed(
        ("msgpack", _harmless_msgpack_marker("strict-policy-marker"))
    )

    assert not isinstance(marker, Counter)


@pytest.mark.asyncio
async def test_pending_writes_use_plain_json_deserialization(checkpoint_db):
    saver = AsyncMySQLCheckpointer()
    tracker = TrackingSerializer()
    saver.serde = tracker
    config = await _save_checkpoint(saver)
    await saver.aput_writes(
        config,
        [("security_marker", {"value": "original"})],
        task_id="task-security",
        task_path="graph:security",
    )
    async with checkpoint_db() as db:
        await db.execute(
            text(
                """
                UPDATE langgraph_checkpoint_write
                SET value = :value
                WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
                """
            ),
            {
                "value": json.dumps(
                    _harmless_constructor_marker("pending-write-marker")
                ),
                "thread_id": "thread-security",
                "checkpoint_id": _checkpoint()["id"],
            },
        )
        await db.commit()

    restored = await saver.aget_tuple(config)

    assert restored is not None
    assert restored.pending_writes == [
        (
            "task-security",
            "security_marker",
            Counter(["pending-write-marker"]),
        )
    ]
    assert tracker.loads_typed_calls == 0
