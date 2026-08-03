from collections import deque
from datetime import datetime, timedelta
from pathlib import Path

import pytest

from session import hitl as hitl_mod
from session.hitl import HitlApproval, HitlBindingError, HitlService
from session.hitl_binding import ApprovalPayload, sign_payload


TEST_SECRET = "test-only-hitl-key"


def _approval_payload() -> ApprovalPayload:
    return ApprovalPayload(
        payload_version=1,
        tool_name="execute_refund",
        order_id="202606100003",
        amount_minor=2000,
        target_user_id="",
        merchant_id="42",
        requested_user_id="1001",
        requested_role="admin",
        reason="订单状态满足退款前置条件，等待人工审批",
    )


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


class _CreateSession:
    def __init__(self):
        self.added = []
        self.commits = 0

    async def __aenter__(self):
        return self

    async def __aexit__(self, exc_type, exc, tb):
        return False

    def add(self, value):
        self.added.append(value)

    async def commit(self):
        self.commits += 1


class _BindSession:
    def __init__(self, rowcount, loaded_approval):
        self.rowcount = rowcount
        self.loaded_approval = loaded_approval
        self.executed = []

    async def execute(self, statement):
        self.executed.append(statement)
        return _FakeExecuteResult(self.rowcount)

    async def get(self, _model, _key):
        return self.loaded_approval


def test_v104_model_and_migration_expose_binding_contract():
    columns = HitlApproval.__table__.columns

    assert columns["checkpoint_id"].nullable is True
    assert {
        "payload_version",
        "payload_digest",
        "order_target_hash",
        "merchant_id",
        "requested_user_id",
        "requested_role",
        "execution_id",
        "execution_lease_until",
        "executing_at",
        "executed_at",
        "execution_result",
        "execution_error",
    } <= set(columns.keys())

    migration = (
        Path(__file__).parents[2]
        / "local-life-copilot/src/main/resources/db/migration/V104__harden_hitl_approval.sql"
    ).read_text(encoding="utf-8")
    assert "MODIFY COLUMN `checkpoint_id` VARCHAR(64) NULL" in migration
    assert "idx_hitl_status_lease" in migration
    assert "idx_hitl_payload_digest" in migration


def test_unbound_checkpoint_is_only_valid_for_pending_approval():
    pending = HitlApproval(
        id=1,
        session_id=2,
        thread_id="thread-1",
        checkpoint_id=None,
        action_type="execute_refund",
        action_payload={},
        agent_reason="reason",
        status="PENDING",
        expire_at=datetime.now() + timedelta(hours=1),
    )

    assert pending.checkpoint_id is None
    with pytest.raises(HitlBindingError, match="checkpoint"):
        HitlApproval(
            id=2,
            session_id=2,
            thread_id="thread-1",
            checkpoint_id=None,
            action_type="execute_refund",
            action_payload={},
            agent_reason="reason",
            status="APPROVED",
            expire_at=datetime.now() + timedelta(hours=1),
        )


@pytest.mark.asyncio
async def test_create_approval_persists_immutable_binding_fields(monkeypatch):
    fake_session = _CreateSession()
    monkeypatch.setattr(hitl_mod, "AsyncSessionLocal", lambda: fake_session)
    monkeypatch.setattr(hitl_mod, "_snowflake_id", lambda: 7001)
    payload = _approval_payload()

    approval_id = await HitlService().create_approval(
        session_id=2001,
        thread_id="thread-1",
        approval_payload=payload,
        agent_reason="订单状态满足退款前置条件",
    )

    assert approval_id == 7001
    assert fake_session.commits == 1
    approval = fake_session.added[0]
    assert approval.checkpoint_id is None
    assert approval.status == "PENDING"
    assert approval.action_type == payload.tool_name
    assert approval.action_payload == payload.canonical_dict()
    assert approval.action_payload["order_id"] == "202606100003"
    assert approval.payload_version == 1
    assert approval.payload_digest == sign_payload(payload, TEST_SECRET)
    assert approval.order_target_hash == (
        "9d3ab724614885ad92153938b85d04f194a3c825ee709c0e0aabe7b344ca613e"
    )
    assert approval.merchant_id == 42
    assert approval.requested_user_id == 1001
    assert approval.requested_role == "admin"


@pytest.mark.asyncio
async def test_bind_checkpoint_accepts_new_and_identical_binding():
    payload = _approval_payload()
    digest = sign_payload(payload, TEST_SECRET)
    service = HitlService()
    newly_bound = _BindSession(rowcount=1, loaded_approval=None)

    await service.bind_checkpoint(
        newly_bound,
        approval_id=7001,
        thread_id="thread-1",
        checkpoint_id="checkpoint-1",
        payload_digest=digest,
    )

    assert len(newly_bound.executed) == 1

    existing = type(
        "Approval",
        (),
        {
            "id": 7001,
            "thread_id": "thread-1",
            "checkpoint_id": "checkpoint-1",
            "payload_digest": digest,
            "status": "APPROVED",
        },
    )()
    idempotent = _BindSession(rowcount=0, loaded_approval=existing)

    await service.bind_checkpoint(
        idempotent,
        approval_id=7001,
        thread_id="thread-1",
        checkpoint_id="checkpoint-1",
        payload_digest=digest,
    )


@pytest.mark.asyncio
@pytest.mark.parametrize(
    "changed",
    [
        None,
        {"id": 7002},
        {"thread_id": "thread-2"},
        {"checkpoint_id": "checkpoint-2"},
        {"payload_digest": "b" * 64},
    ],
)
async def test_bind_checkpoint_rejects_missing_or_changed_tuple(changed):
    payload = _approval_payload()
    digest = sign_payload(payload, TEST_SECRET)
    loaded = None
    if changed is not None:
        values = {
            "id": 7001,
            "thread_id": "thread-1",
            "checkpoint_id": "checkpoint-1",
            "payload_digest": digest,
            "status": "PENDING",
        }
        values.update(changed)
        loaded = type("Approval", (), values)()

    with pytest.raises(HitlBindingError, match="binding"):
        await HitlService().bind_checkpoint(
            _BindSession(rowcount=0, loaded_approval=loaded),
            approval_id=7001,
            thread_id="thread-1",
            checkpoint_id="checkpoint-1",
            payload_digest=digest,
        )


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
