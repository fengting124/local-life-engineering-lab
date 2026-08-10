#!/usr/bin/env python3
"""Migrate legacy LangGraph checkpoints using DB_URL from the environment."""

import argparse
import asyncio
import json
import os
import sys
from pathlib import Path

from sqlalchemy.ext.asyncio import async_sessionmaker, create_async_engine

sys.path.insert(0, str(Path(__file__).resolve().parents[1]))

from session.checkpoint_migration import (  # noqa: E402
    AsyncCheckpointMigrator,
    MigrationValidationError,
)


def parse_args() -> argparse.Namespace:
    parser = argparse.ArgumentParser(
        description="Migrate legacy LangGraph TEXT checkpoints to typed v2 tables."
    )
    mode = parser.add_mutually_exclusive_group(required=True)
    mode.add_argument("--dry-run", action="store_const", const="dry-run", dest="mode")
    mode.add_argument("--migrate", action="store_const", const="migrate", dest="mode")
    mode.add_argument(
        "--verify-only",
        action="store_const",
        const="verify-only",
        dest="mode",
    )
    parser.add_argument("--thread-id")
    parser.add_argument("--limit", type=int)
    return parser.parse_args()


async def run(args: argparse.Namespace) -> int:
    db_url = os.getenv("DB_URL", "").strip()
    if not db_url:
        print(json.dumps({"status": "error", "error_type": "MissingDatabaseUrl"}))
        return 2
    engine = create_async_engine(db_url, pool_pre_ping=True)
    session_factory = async_sessionmaker(engine, expire_on_commit=False)
    try:
        stats = await AsyncCheckpointMigrator(session_factory).run(
            args.mode,
            thread_id=args.thread_id,
            limit=args.limit,
        )
        print(json.dumps({"status": "ok", **stats.to_dict()}, ensure_ascii=False))
        return 0
    except (MigrationValidationError, ValueError) as exc:
        print(
            json.dumps(
                {
                    "status": "error",
                    "error_type": type(exc).__name__,
                    "message": str(exc),
                },
                ensure_ascii=False,
            )
        )
        return 1
    finally:
        await engine.dispose()


if __name__ == "__main__":
    raise SystemExit(asyncio.run(run(parse_args())))
