import json
from pathlib import Path
from types import SimpleNamespace

import pytest
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langgraph.checkpoint.base import WRITES_IDX_MAP

from session.checkpoint_migration import (
    AsyncCheckpointMigrator,
    LegacyCheckpointCodec,
    MigrationValidationError,
)


FIXTURE = (
    Path(__file__).parent
    / "fixtures"
    / "langgraph-0.2.45"
    / "checkpoints.json"
)


def test_legacy_fixture_reencodes_as_typed_without_field_loss():
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    codec = LegacyCheckpointCodec()

    restored = []
    for record in fixture["records"]:
        checkpoint = codec.decode_checkpoint(
            record["state_json"],
            expected_checkpoint_id=record["checkpoint_id"],
        )
        type_tag, blob = codec.encode(checkpoint)
        restored.append(codec.decode_typed(type_tag, blob))

    assert [item["id"] for item in restored] == [
        record["checkpoint_id"] for record in fixture["records"]
    ]
    assert isinstance(restored[0]["channel_values"]["messages"][0], HumanMessage)
    assert isinstance(restored[1]["channel_values"]["messages"][1], AIMessage)
    assert isinstance(restored[1]["channel_values"]["messages"][2], ToolMessage)
    assert restored[2]["channel_values"]["pending_action"]["approval_id"] == 9001
    assert restored[3]["channel_values"]["approval_status"] == "EXECUTED"


def test_legacy_pending_write_reencodes_as_typed_tool_message():
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    pending = fixture["pending_writes"][0]
    codec = LegacyCheckpointCodec()

    value = codec.decode_legacy_json(pending["value_json"])
    type_tag, blob = codec.encode(value)
    restored = codec.decode_typed(type_tag, blob)

    assert isinstance(restored, ToolMessage)
    assert restored.tool_call_id == "fixture-call-001"


def test_legacy_checkpoint_id_mismatch_fails_closed():
    codec = LegacyCheckpointCodec()
    payload = json.dumps(
        {
            "v": 1,
            "ts": "2026-08-10T00:00:00+00:00",
            "id": "checkpoint-other",
            "channel_values": {},
            "channel_versions": {},
            "versions_seen": {},
        }
    )

    with pytest.raises(MigrationValidationError, match="checkpoint id mismatch"):
        codec.decode_checkpoint(payload, expected_checkpoint_id="checkpoint-source")


def test_unknown_legacy_json_constructor_is_not_reconstructed():
    codec = LegacyCheckpointCodec()
    payload = json.dumps(
        {
            "lc": 2,
            "type": "constructor",
            "id": ["collections", "Counter"],
            "args": [["blocked-marker"]],
        }
    )

    with pytest.raises(MigrationValidationError, match="unresolved constructor"):
        codec.decode_legacy_json(payload)


class _WriteRows:
    def __init__(self, rows):
        self._rows = [SimpleNamespace(_mapping=row) for row in rows]

    def fetchall(self):
        return self._rows


class _WriteDb:
    def __init__(self, rows):
        self._rows = rows

    async def execute(self, _statement, _params):
        return _WriteRows(self._rows)


@pytest.mark.asyncio
async def test_legacy_special_write_indexes_are_normalized_for_checkpoint_4():
    migrator = AsyncCheckpointMigrator(lambda: None)
    writes = await migrator._prepare_writes(
        _WriteDb(
            [
                {
                    "task_id": "task-1",
                    "task_path": "",
                    "write_index": 0,
                    "channel": "__interrupt__",
                    "value": json.dumps({"value": "pause"}),
                },
                {
                    "task_id": "task-1",
                    "task_path": "",
                    "write_index": 1,
                    "channel": "messages",
                    "value": json.dumps("answer"),
                },
            ]
        ),
        thread_id="thread-1",
        checkpoint_id="checkpoint-1",
    )

    assert [write.write_index for write in writes] == [
        WRITES_IDX_MAP["__interrupt__"],
        1,
    ]


@pytest.mark.asyncio
async def test_legacy_special_write_index_collision_fails_closed():
    migrator = AsyncCheckpointMigrator(lambda: None)

    with pytest.raises(MigrationValidationError, match="write identity collision"):
        await migrator._prepare_writes(
            _WriteDb(
                [
                    {
                        "task_id": "task-1",
                        "task_path": "",
                        "write_index": 0,
                        "channel": "__interrupt__",
                        "value": json.dumps("first"),
                    },
                    {
                        "task_id": "task-1",
                        "task_path": "",
                        "write_index": 1,
                        "channel": "__interrupt__",
                        "value": json.dumps("second"),
                    },
                ]
            ),
            thread_id="thread-1",
            checkpoint_id="checkpoint-1",
        )
