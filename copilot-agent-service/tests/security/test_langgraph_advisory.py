import json
from collections import Counter
from importlib.metadata import version
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
        "ts": "2026-08-10T00:00:00+00:00",
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


def _harmless_msgpack_ext(label: str) -> ormsgpack.Ext:
    constructor = ormsgpack.packb(("collections", "Counter", [label]))
    return ormsgpack.Ext(EXT_CONSTRUCTOR_SINGLE_ARG, constructor)


def _harmless_msgpack_marker(label: str) -> bytes:
    return ormsgpack.packb(_harmless_msgpack_ext(label))


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
        await connection.execute(text("DROP TABLE IF EXISTS langgraph_checkpoint_write_v2"))
        await connection.execute(text("DROP TABLE IF EXISTS langgraph_checkpoint_v2"))
        await connection.execute(
            text(
                """
                CREATE TABLE langgraph_checkpoint_v2 (
                    thread_id VARCHAR(64) NOT NULL,
                    checkpoint_ns VARCHAR(255) NOT NULL DEFAULT '',
                    checkpoint_id VARCHAR(64) NOT NULL,
                    parent_checkpoint_id VARCHAR(64) NULL,
                    state_type VARCHAR(32) NOT NULL,
                    state_blob LONGBLOB NOT NULL,
                    metadata JSON NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (thread_id, checkpoint_ns, checkpoint_id)
                ) CHARACTER SET utf8mb4
                """
            )
        )
        await connection.execute(
            text(
                """
                CREATE TABLE langgraph_checkpoint_write_v2 (
                    thread_id VARCHAR(64) NOT NULL,
                    checkpoint_ns VARCHAR(255) NOT NULL DEFAULT '',
                    checkpoint_id VARCHAR(64) NOT NULL,
                    task_id VARCHAR(128) NOT NULL,
                    task_path VARCHAR(255) NOT NULL DEFAULT '',
                    write_index INT NOT NULL,
                    channel VARCHAR(128) NOT NULL,
                    value_type VARCHAR(32) NOT NULL,
                    value_blob LONGBLOB NOT NULL,
                    created_at DATETIME(6) NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
                    PRIMARY KEY (
                        thread_id, checkpoint_ns, checkpoint_id, task_id, write_index
                    )
                ) CHARACTER SET utf8mb4
                """
            )
        )
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    monkeypatch.setattr(checkpointer_module, "AsyncSessionLocal", session_factory)
    yield session_factory
    await engine.dispose()


async def _save_checkpoint(saver: AsyncMySQLCheckpointer) -> dict:
    return await saver.aput(
        {"configurable": {"thread_id": "thread-security", "checkpoint_ns": ""}},
        _checkpoint(),
        {"source": "input", "step": 0},
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


def test_checkpoint_dependency_is_on_fixed_major_version():
    assert int(version("langgraph-checkpoint").split(".", 1)[0]) >= 4


def test_strict_msgpack_blocks_unregistered_constructor():
    restored = AsyncMySQLCheckpointer().serde.loads_typed(
        ("msgpack", _harmless_msgpack_marker("strict-msgpack-marker"))
    )

    assert not isinstance(restored, Counter)


def test_strict_json_blocks_unregistered_constructor():
    restored = AsyncMySQLCheckpointer().serde.loads_typed(
        (
            "json",
            json.dumps(_harmless_constructor_marker("strict-json-marker")).encode(),
        )
    )

    assert not isinstance(restored, Counter)
    assert restored["id"] == ["collections", "Counter"]


@pytest.mark.asyncio
async def test_production_checkpoint_path_does_not_reconstruct_msgpack_marker(
    checkpoint_db,
):
    saver = AsyncMySQLCheckpointer()
    config = await _save_checkpoint(saver)
    tampered = _checkpoint()
    tampered["channel_values"]["marker"] = _harmless_msgpack_ext(
        "typed-checkpoint-marker"
    )
    async with checkpoint_db() as db:
        await db.execute(
            text(
                """
                UPDATE langgraph_checkpoint_v2
                SET state_type = 'msgpack', state_blob = :state_blob
                WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
                """
            ),
            {
                "state_blob": ormsgpack.packb(tampered),
                "thread_id": "thread-security",
                "checkpoint_id": _checkpoint()["id"],
            },
        )
        await db.commit()

    restored = await saver.aget_tuple(config)

    assert restored is not None
    assert not isinstance(restored.checkpoint["channel_values"]["marker"], Counter)


@pytest.mark.asyncio
async def test_production_checkpoint_path_does_not_reconstruct_json_marker(
    checkpoint_db,
):
    saver = AsyncMySQLCheckpointer()
    config = await _save_checkpoint(saver)
    tampered = _checkpoint()
    tampered["channel_values"]["marker"] = _harmless_constructor_marker(
        "typed-json-marker"
    )
    async with checkpoint_db() as db:
        await db.execute(
            text(
                """
                UPDATE langgraph_checkpoint_v2
                SET state_type = 'json', state_blob = :state_blob
                WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
                """
            ),
            {
                "state_blob": json.dumps(tampered).encode(),
                "thread_id": "thread-security",
                "checkpoint_id": _checkpoint()["id"],
            },
        )
        await db.commit()

    restored = await saver.aget_tuple(config)

    assert restored is not None
    marker = restored.checkpoint["channel_values"]["marker"]
    assert not isinstance(marker, Counter)
    assert marker["id"] == ["collections", "Counter"]


@pytest.mark.asyncio
async def test_production_pending_write_path_does_not_reconstruct_marker(
    checkpoint_db,
):
    saver = AsyncMySQLCheckpointer()
    config = await _save_checkpoint(saver)
    await saver.aput_writes(
        config,
        [("security_marker", {"value": "original"})],
        task_id="task-security",
    )
    async with checkpoint_db() as db:
        await db.execute(
            text(
                """
                UPDATE langgraph_checkpoint_write_v2
                SET value_type = 'msgpack', value_blob = :value_blob
                WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
                """
            ),
            {
                "value_blob": _harmless_msgpack_marker("pending-write-marker"),
                "thread_id": "thread-security",
                "checkpoint_id": _checkpoint()["id"],
            },
        )
        await db.commit()

    restored = await saver.aget_tuple(config)

    assert restored is not None
    assert not isinstance(restored.pending_writes[0][2], Counter)
