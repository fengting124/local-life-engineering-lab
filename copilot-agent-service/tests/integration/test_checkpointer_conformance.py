from urllib.parse import quote_plus
from uuid import uuid4

import pytest
from langgraph.checkpoint.conformance import checkpointer_test, validate
from sqlalchemy import text
from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine
from testcontainers.community.mysql import MySqlContainer

from session import checkpointer as checkpointer_module
from session.checkpointer import AsyncMySQLCheckpointer


CHECKPOINT_SCHEMA = """
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

WRITE_SCHEMA = """
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
"""


@pytest.fixture(scope="module")
def conformance_mysql():
    with MySqlContainer(
        "mysql:8.4",
        dialect="pymysql",
        dbname="checkpoint_conformance",
    ) as mysql:
        yield mysql


@pytest.mark.asyncio
async def test_mysql_checkpointer_passes_official_base_conformance(
    conformance_mysql,
    monkeypatch,
):
    url = (
        "mysql+aiomysql://"
        f"{quote_plus(conformance_mysql.username)}:"
        f"{quote_plus(conformance_mysql.password)}@"
        f"{conformance_mysql.get_container_host_ip()}:"
        f"{conformance_mysql.get_exposed_port(3306)}/"
        f"{conformance_mysql.dbname}?charset=utf8mb4"
    )
    engine = create_async_engine(url, pool_pre_ping=False)
    async with engine.begin() as connection:
        await connection.execute(text(CHECKPOINT_SCHEMA))
        await connection.execute(text(WRITE_SCHEMA))
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    monkeypatch.setattr(checkpointer_module, "AsyncSessionLocal", session_factory)

    @checkpointer_test(name=f"AsyncMySQLCheckpointer-{uuid4()}")
    async def checkpointer_factory():
        async with engine.begin() as connection:
            await connection.execute(text("DELETE FROM langgraph_checkpoint_write_v2"))
            await connection.execute(text("DELETE FROM langgraph_checkpoint_v2"))
        yield AsyncMySQLCheckpointer()

    try:
        report = await validate(checkpointer_factory)
    finally:
        await engine.dispose()

    failures = {
        capability: result.failures
        for capability, result in report.results.items()
        if result.tests_failed
    }
    assert report.passed_all_base(), failures
    assert report.results["delete_for_runs"].detected is False
    assert report.results["copy_thread"].detected is False
    assert report.results["prune"].detected is False
