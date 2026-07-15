from collections import deque
from datetime import datetime, timedelta

import pytest

from session import hitl as hitl_mod
from session.hitl import HitlService


class _FakeExecuteResult:
    def __init__(self, rowcount: int):
        self.rowcount = rowcount


class _FakeSession:
    def __init__(self, rowcounts: list[int]):
        self._rowcounts = deque(rowcounts)
        self.executed = []
        self.commits = 0
        self.rollbacks = 0
        self.loaded_approval = type(
            "Approval",
            (),
            {
                "status": "PENDING",
                "expire_at": datetime.now() + timedelta(hours=1),
                "approver_id": None,
                "approver_comment": None,
                "approved_at": None,
            },
        )()

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    async def execute(self, stmt):
        self.executed.append(stmt)
        return _FakeExecuteResult(self._rowcounts.popleft())

    async def get(self, _model, _key):
        return self.loaded_approval

    async def commit(self):
        self.commits += 1

    async def rollback(self):
        self.rollbacks += 1


@pytest.mark.asyncio
async def test_approve_commits_when_pending_transition_wins(monkeypatch):
    fake_session = _FakeSession(rowcounts=[1])
    monkeypatch.setattr(hitl_mod, "AsyncSessionLocal", lambda: fake_session)

    ok = await HitlService().approve(approval_id=1001, approver_id=9001, comment="ok")

    assert ok is True
    assert len(fake_session.executed) == 1
    assert fake_session.commits == 1
    assert fake_session.rollbacks == 0


@pytest.mark.asyncio
async def test_approve_returns_false_when_pending_transition_lost(monkeypatch):
    fake_session = _FakeSession(rowcounts=[0])
    monkeypatch.setattr(hitl_mod, "AsyncSessionLocal", lambda: fake_session)

    ok = await HitlService().approve(approval_id=1001, approver_id=9001, comment="late")

    assert ok is False
    assert len(fake_session.executed) == 1
    assert fake_session.commits == 0
    assert fake_session.rollbacks == 1


@pytest.mark.asyncio
async def test_reject_returns_false_when_pending_transition_lost(monkeypatch):
    fake_session = _FakeSession(rowcounts=[0])
    monkeypatch.setattr(hitl_mod, "AsyncSessionLocal", lambda: fake_session)

    ok = await HitlService().reject(approval_id=1001, approver_id=9001, comment="late")

    assert ok is False
    assert len(fake_session.executed) == 1
    assert fake_session.commits == 0
    assert fake_session.rollbacks == 1
