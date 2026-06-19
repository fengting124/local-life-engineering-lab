import pytest

from agent import trace


class FakeLog:
    def __init__(self):
        self.events = []

    def info(self, event, **kw):
        self.events.append((event, kw))


@pytest.mark.asyncio
async def test_genai_span_logs_start_and_success(monkeypatch):
    fake = FakeLog()
    monkeypatch.setattr(trace, "log", fake)

    async with trace.genai_span("llm.invoke", "llm", model="deepseek-v4-flash"):
        pass

    assert [event for event, _ in fake.events] == ["genai_span_start", "genai_span_end"]
    assert fake.events[-1][1]["status"] == "ok"
    assert fake.events[-1][1]["span_name"] == "llm.invoke"
    assert "duration_ms" in fake.events[-1][1]


@pytest.mark.asyncio
async def test_genai_span_logs_error_and_reraises(monkeypatch):
    fake = FakeLog()
    monkeypatch.setattr(trace, "log", fake)

    with pytest.raises(RuntimeError):
        async with trace.genai_span("mcp.rpc", "client"):
            raise RuntimeError("boom")

    assert fake.events[-1][0] == "genai_span_end"
    assert fake.events[-1][1]["status"] == "error"
    assert "boom" in fake.events[-1][1]["error"]
