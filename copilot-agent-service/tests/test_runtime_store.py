from datetime import datetime

import pytest

from session import runtime as runtime_mod
from session.models import AgentEvent, AgentRun
from session.runtime import AgentRuntimeStore


class FakeSession:
    def __init__(self):
        self.added = []
        self.commits = 0
        self.run = None
        self.execute_result = None

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    def add(self, obj):
        self.added.append(obj)
        if isinstance(obj, AgentRun):
            self.run = obj

    async def get(self, model, key):
        if model is AgentRun and self.run and self.run.id == key:
            return self.run
        return None

    async def commit(self):
        self.commits += 1

    async def execute(self, _stmt):
        return self.execute_result


class _FakeScalarResult:
    def __init__(self, rows):
        self._rows = rows

    def all(self):
        return self._rows


class _FakeExecuteResult:
    def __init__(self, rows):
        self._rows = rows

    def scalars(self):
        return _FakeScalarResult(self._rows)


@pytest.mark.asyncio
async def test_create_run_persists_submitted_runtime_record(monkeypatch):
    fake_session = FakeSession()
    monkeypatch.setattr(runtime_mod, "AsyncSessionLocal", lambda: fake_session)
    monkeypatch.setattr(runtime_mod, "_new_run_id", lambda: "run-001")

    store = AgentRuntimeStore()
    run_id = await store.create_run(
        session_id=1001,
        thread_id="thread-001",
        user_id=9001,
        user_role="merchant",
        merchant_id=8001,
        input_message="帮我查今天经营数据" * 40,
        trace_id="trace-001",
    )

    assert run_id == "run-001"
    assert fake_session.commits == 1
    run = fake_session.added[0]
    assert run.id == "run-001"
    assert run.session_id == 1001
    assert run.thread_id == "thread-001"
    assert run.status == "SUBMITTED"
    assert run.trace_id == "trace-001"
    assert run.input_summary.endswith("...")
    assert len(run.input_summary) == 203


@pytest.mark.asyncio
async def test_append_event_persists_runtime_event(monkeypatch):
    fake_session = FakeSession()
    monkeypatch.setattr(runtime_mod, "AsyncSessionLocal", lambda: fake_session)
    monkeypatch.setattr(runtime_mod, "_snowflake_id", lambda: 123456)

    store = AgentRuntimeStore()
    event_id = await store.append_event(
        run_id="run-001",
        session_id=1001,
        thread_id="thread-001",
        sequence_index=2,
        event_type="tool_call",
        event_name="query_order",
        payload={"tool": "query_order", "arg_keys": ["order_no"]},
        trace_id="trace-001",
    )

    assert event_id == 123456
    assert fake_session.commits == 1
    event = fake_session.added[0]
    assert event.id == 123456
    assert event.run_id == "run-001"
    assert event.sequence_index == 2
    assert event.event_type == "tool_call"
    assert event.event_name == "query_order"
    assert event.payload == {"tool": "query_order", "arg_keys": ["order_no"]}
    assert event.trace_id == "trace-001"


@pytest.mark.asyncio
async def test_mark_run_status_updates_terminal_metadata(monkeypatch):
    fake_session = FakeSession()
    fake_session.run = AgentRun(
        id="run-001",
        session_id=1001,
        thread_id="thread-001",
        user_id=9001,
        user_role="merchant",
        merchant_id=8001,
        status="RUNNING",
        input_summary="hello",
    )
    monkeypatch.setattr(runtime_mod, "AsyncSessionLocal", lambda: fake_session)

    store = AgentRuntimeStore()
    await store.mark_run_status("run-001", "FAILED", error_message="boom")

    assert fake_session.commits == 1
    assert fake_session.run.status == "FAILED"
    assert fake_session.run.error_message == "boom"
    assert isinstance(fake_session.run.finished_at, datetime)


@pytest.mark.asyncio
async def test_list_events_returns_ordered_runtime_events(monkeypatch):
    fake_session = FakeSession()
    fake_session.execute_result = _FakeExecuteResult([
        AgentEvent(
            id=1,
            run_id="run-001",
            session_id=1001,
            thread_id="thread-001",
            sequence_index=1,
            event_type="final_answer",
            event_name=None,
            payload={"content": "ok"},
            trace_id="trace-001",
        )
    ])
    monkeypatch.setattr(runtime_mod, "AsyncSessionLocal", lambda: fake_session)

    store = AgentRuntimeStore()
    events = await store.list_events("run-001", after_sequence=0, limit=10)

    assert len(events) == 1
    assert events[0].run_id == "run-001"
    assert events[0].sequence_index == 1
    assert events[0].event_type == "final_answer"
