import json
import operator
import re
from pathlib import Path
from typing import Annotated, TypedDict

from langchain_core.messages import AIMessage, HumanMessage, ToolMessage
from langgraph.checkpoint.base import BaseCheckpointSaver, CheckpointTuple
from langgraph.checkpoint.serde.jsonplus import JsonPlusSerializer
from langgraph.graph import END, StateGraph


FIXTURE = (
    Path(__file__).parents[1]
    / "fixtures"
    / "langgraph-0.2.45"
    / "checkpoints.json"
)


def _load_legacy_json(serializer, payload: str):
    encoded = payload.encode("utf-8")
    legacy_loads = getattr(serializer, "loads", None)
    if legacy_loads is not None:
        return legacy_loads(encoded)
    return serializer.loads_typed(("json", encoded))


class ProbeState(TypedDict):
    events: Annotated[list[str], operator.add]


class FixtureSaver(BaseCheckpointSaver):
    def __init__(self, record: dict) -> None:
        super().__init__()
        self.record = record

    def get_tuple(self, config):
        checkpoint = _load_legacy_json(self.serde, self.record["state_json"])
        parent_id = self.record["parent_checkpoint_id"]
        return CheckpointTuple(
            config=config,
            checkpoint=checkpoint,
            metadata=json.loads(self.record["metadata_json"]),
            parent_config=(
                {
                    "configurable": {
                        "thread_id": "fixture-resume-thread",
                        "checkpoint_id": parent_id,
                    }
                }
                if parent_id
                else None
            ),
        )


def _probe_graph(saver):
    builder = StateGraph(ProbeState)
    builder.add_node("first", lambda state: {"events": ["first"]})
    builder.add_node("second", lambda state: {"events": ["second"]})
    builder.set_entry_point("first")
    builder.add_edge("first", "second")
    builder.add_edge("second", END)
    return builder.compile(checkpointer=saver)


def test_sanitized_fixture_identifies_exact_legacy_producer():
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    assert fixture["producer"] == {
        "langgraph": "0.2.45",
        "langgraph-checkpoint": "2.1.2",
        "langchain-core": "0.3.63",
    }
    serialized = json.dumps(fixture)
    for forbidden in (
        r"\bsk-[A-Za-z0-9]{16,}",
        r"(?:mysql|mysql\+aiomysql)://[^\s\"]+",
        r'"payload_digest"\s*:\s*"[0-9a-f]{64}"',
    ):
        assert re.search(forbidden, serialized) is None


def test_legacy_checkpoint_chain_round_trips_without_field_loss():
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    serializer = JsonPlusSerializer()
    restored = [
        _load_legacy_json(serializer, record["state_json"])
        for record in fixture["records"]
    ]

    assert [checkpoint["id"] for checkpoint in restored] == [
        record["checkpoint_id"] for record in fixture["records"]
    ]
    assert [record["parent_checkpoint_id"] for record in fixture["records"]] == [
        None,
        "fixture-checkpoint-0001",
        "fixture-checkpoint-0002",
        "fixture-checkpoint-0003",
    ]
    assert isinstance(restored[0]["channel_values"]["messages"][0], HumanMessage)
    assert isinstance(restored[1]["channel_values"]["messages"][1], AIMessage)
    assert isinstance(restored[1]["channel_values"]["messages"][2], ToolMessage)
    assert restored[2]["channel_values"]["pending_action"]["approval_id"] == 9001
    assert restored[3]["channel_values"]["approval_status"] == "EXECUTED"
    assert [record["expected_next_node"] for record in fixture["records"]] == [
        "agent",
        "evidence_gate",
        "__interrupt__",
        "__end__",
    ]


def test_legacy_pending_write_round_trips_as_tool_message():
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))
    pending = fixture["pending_writes"][0]

    value = _load_legacy_json(JsonPlusSerializer(), pending["value_json"])

    assert isinstance(value, ToolMessage)
    assert value.tool_call_id == "fixture-call-001"
    assert value.content == "fixture pending write"


def test_legacy_checkpoints_resume_at_the_expected_graph_nodes():
    fixture = json.loads(FIXTURE.read_text(encoding="utf-8"))

    for record in fixture["resume_probes"]:
        graph = _probe_graph(FixtureSaver(record))
        snapshot = graph.get_state(
            {
                "configurable": {
                    "thread_id": "fixture-resume-thread",
                    "checkpoint_id": record["checkpoint_id"],
                }
            }
        )

        assert list(snapshot.next) == record["expected_next_nodes"]
        assert snapshot.values.get("events", []) == record["expected_events"]
