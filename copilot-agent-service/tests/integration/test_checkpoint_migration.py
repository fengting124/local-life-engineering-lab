import json
import operator
from pathlib import Path
from typing import Annotated, TypedDict
from urllib.parse import quote_plus

import pytest
import pytest_asyncio
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.community.mysql import MySqlContainer
from langgraph.graph import END, StateGraph

from session import checkpointer as checkpointer_module
from session.checkpoint_migration import (
    AsyncCheckpointMigrator,
    MigrationValidationError,
)
from session.checkpointer import AsyncMySQLCheckpointer


FIXTURE = (
    Path(__file__).parents[1]
    / "fixtures"
    / "langgraph-0.2.45"
    / "checkpoints.json"
)

SCHEMA = [
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
    """,
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
    """,
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
    """,
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
        PRIMARY KEY (thread_id, checkpoint_ns, checkpoint_id, task_id, write_index)
    ) CHARACTER SET utf8mb4
    """,
]


@pytest.fixture(scope="module")
def migration_mysql():
    with MySqlContainer(
        "mysql:8.4",
        dialect="pymysql",
        dbname="checkpoint_migration",
    ) as mysql:
        yield mysql


@pytest_asyncio.fixture
async def migration_db(migration_mysql, monkeypatch):
    url = (
        "mysql+aiomysql://"
        f"{quote_plus(migration_mysql.username)}:"
        f"{quote_plus(migration_mysql.password)}@"
        f"{migration_mysql.get_container_host_ip()}:"
        f"{migration_mysql.get_exposed_port(3306)}/"
        f"{migration_mysql.dbname}?charset=utf8mb4"
    )
    engine = create_async_engine(url, pool_pre_ping=False)
    async with engine.begin() as connection:
        for table in (
            "langgraph_checkpoint_write_v2",
            "langgraph_checkpoint_v2",
            "langgraph_checkpoint_write",
            "langgraph_checkpoint",
        ):
            await connection.execute(text(f"DROP TABLE IF EXISTS {table}"))
        for statement in SCHEMA:
            await connection.execute(text(statement))
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    monkeypatch.setattr(checkpointer_module, "AsyncSessionLocal", session_factory)
    yield session_factory
    await engine.dispose()


async def _seed_fixture(session_factory, thread_id="fixture-thread"):
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    async with session_factory() as db:
        for record in fixture["records"]:
            await db.execute(
                text(
                    """
                    INSERT INTO langgraph_checkpoint
                        (thread_id, checkpoint_id, parent_checkpoint_id, state, metadata)
                    VALUES
                        (:thread_id, :checkpoint_id, :parent_id, :state, :metadata)
                    """
                ),
                {
                    "thread_id": thread_id,
                    "checkpoint_id": record["checkpoint_id"],
                    "parent_id": record["parent_checkpoint_id"],
                    "state": record["state_json"],
                    "metadata": record["metadata_json"],
                },
            )
        for pending in fixture["pending_writes"]:
            await db.execute(
                text(
                    """
                    INSERT INTO langgraph_checkpoint_write
                        (thread_id, checkpoint_id, task_id, task_path,
                         write_index, channel, value)
                    VALUES
                        (:thread_id, :checkpoint_id, :task_id, :task_path,
                         :write_index, :channel, :value)
                    """
                ),
                {"thread_id": thread_id, **pending, "value": pending["value_json"]},
            )
        await db.commit()


class ProbeState(TypedDict):
    events: Annotated[list[str], operator.add]


def _probe_graph(saver):
    builder = StateGraph(ProbeState)
    builder.add_node("first", lambda state: {"events": ["first"]})
    builder.add_node("second", lambda state: {"events": ["second"]})
    builder.set_entry_point("first")
    builder.add_edge("first", "second")
    builder.add_edge("second", END)
    return builder.compile(checkpointer=saver)


async def _seed_resume_probes(session_factory):
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    async with session_factory() as db:
        for record in fixture["resume_probes"]:
            await db.execute(
                text(
                    """
                    INSERT INTO langgraph_checkpoint
                        (thread_id, checkpoint_id, parent_checkpoint_id,
                         state, metadata)
                    VALUES
                        ('fixture-resume-thread', :checkpoint_id, :parent_id,
                         :state, :metadata)
                    """
                ),
                {
                    "checkpoint_id": record["checkpoint_id"],
                    "parent_id": record["parent_checkpoint_id"],
                    "state": record["state_json"],
                    "metadata": record["metadata_json"],
                },
            )
        await db.commit()


@pytest.mark.asyncio
async def test_dry_run_migrate_verify_and_repeat_are_safe(migration_db):
    await _seed_fixture(migration_db)
    migrator = AsyncCheckpointMigrator(migration_db)

    dry_run = await migrator.run("dry-run", thread_id="fixture-thread")
    assert dry_run.checkpoints_scanned == 4
    assert dry_run.writes_scanned == 1
    assert dry_run.checkpoints_migrated == 0

    async with migration_db() as db:
        count = await db.scalar(text("SELECT COUNT(*) FROM langgraph_checkpoint_v2"))
    assert count == 0

    migrated = await migrator.run("migrate", thread_id="fixture-thread")
    assert migrated.checkpoints_migrated == 4
    assert migrated.writes_migrated == 1

    verified = await migrator.run("verify-only", thread_id="fixture-thread")
    assert verified.checkpoints_verified == 4
    assert verified.writes_verified == 1

    repeated = await migrator.run("migrate", thread_id="fixture-thread")
    assert repeated.checkpoints_migrated == 4
    async with migration_db() as db:
        checkpoint_count = await db.scalar(
            text("SELECT COUNT(*) FROM langgraph_checkpoint_v2")
        )
        write_count = await db.scalar(
            text("SELECT COUNT(*) FROM langgraph_checkpoint_write_v2")
        )
    assert (checkpoint_count, write_count) == (4, 1)

    restored = await AsyncMySQLCheckpointer().aget_tuple(
        {
            "configurable": {
                "thread_id": "fixture-thread",
                "checkpoint_ns": "",
                "checkpoint_id": "fixture-checkpoint-0002",
            }
        }
    )
    assert restored is not None
    assert restored.parent_config["configurable"]["checkpoint_id"] == (
        "fixture-checkpoint-0001"
    )
    assert restored.pending_writes[0][0:2] == ("fixture-task-001", "messages")


@pytest.mark.asyncio
async def test_migration_fails_closed_before_writing_any_target(migration_db):
    async with migration_db() as db:
        await db.execute(
            text(
                """
                INSERT INTO langgraph_checkpoint
                    (thread_id, checkpoint_id, state, metadata)
                VALUES (:thread_id, :checkpoint_id, :state, '{}')
                """
            ),
            {
                "thread_id": "invalid-thread",
                "checkpoint_id": "source-id",
                "state": json.dumps(
                    {
                        "id": "different-id",
                        "channel_values": {},
                        "channel_versions": {},
                        "versions_seen": {},
                    }
                ),
            },
        )
        await db.commit()

    with pytest.raises(MigrationValidationError, match="checkpoint id mismatch"):
        await AsyncCheckpointMigrator(migration_db).run(
            "migrate", thread_id="invalid-thread"
        )

    async with migration_db() as db:
        target_count = await db.scalar(
            text(
                "SELECT COUNT(*) FROM langgraph_checkpoint_v2 "
                "WHERE thread_id = 'invalid-thread'"
            )
        )
    assert target_count == 0


@pytest.mark.asyncio
async def test_migrated_legacy_checkpoints_resume_at_exact_nodes(migration_db):
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    await _seed_resume_probes(migration_db)
    migrator = AsyncCheckpointMigrator(migration_db)

    dry_run = await migrator.run(
        "dry-run", thread_id="fixture-resume-thread"
    )
    assert dry_run.checkpoints_scanned == 4

    migrated = await migrator.run(
        "migrate", thread_id="fixture-resume-thread"
    )
    assert migrated.checkpoints_migrated == 4

    verified = await migrator.run(
        "verify-only", thread_id="fixture-resume-thread"
    )
    assert verified.checkpoints_verified == 4

    graph = _probe_graph(AsyncMySQLCheckpointer())
    for record in fixture["resume_probes"]:
        snapshot = await graph.aget_state(
            {
                "configurable": {
                    "thread_id": "fixture-resume-thread",
                    "checkpoint_ns": "",
                    "checkpoint_id": record["checkpoint_id"],
                }
            }
        )
        assert list(snapshot.next) == record["expected_next_nodes"]
        assert snapshot.values.get("events", []) == record["expected_events"]
