from pathlib import Path
import re


def _index_html() -> str:
    return (Path(__file__).resolve().parents[1] / "static" / "index.html").read_text()


def test_chat_ui_tracks_runtime_run_and_replay_cursor():
    html = _index_html()

    assert "let currentRunId = null" in html
    assert re.search(r"let\s+currentRunId\s*=\s*null,\s*lastEventSequence\s*=\s*-1", html)
    assert "payload.run_id" in html


def test_chat_ui_can_replay_runtime_events_after_stream_disconnect():
    html = _index_html()

    assert "async function replayPendingEvents(" in html
    assert "/chat/runs/${currentRunId}/events" in html
    assert "after_sequence=${lastEventSequence}" in html
