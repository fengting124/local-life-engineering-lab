from collections import deque
from copy import deepcopy
from datetime import datetime, timedelta, timezone
from pathlib import Path
from types import SimpleNamespace
from unittest.mock import MagicMock

import pytest

from session import hitl as hitl_mod
from session.hitl import (
    HitlApproval,
    HitlBindingError,
    HitlResumeError,
    HitlService,
)
from session.hitl_binding import ApprovalPayload, sign_payload


TEST_SECRET = "test-only-hitl-key"


def _test_utc_now() -> datetime:
    return datetime.now(timezone.utc).replace(tzinfo=None)


def test_utc_now_uses_utc_and_returns_database_compatible_naive_value(monkeypatch):
    class FakeDateTime:
        @classmethod
        def now(cls, tz=None):
            if tz is None:
                return datetime(2026, 8, 4, 20, 0, 0)
            assert tz is timezone.utc
            return datetime(2026, 8, 4, 12, 0, 0, tzinfo=timezone.utc)

    monkeypatch.setattr(hitl_mod, "datetime", FakeDateTime)

    assert hitl_mod._utc_now() == datetime(2026, 8, 4, 12, 0, 0)


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


def _bound_approval(payload=None, **overrides):
    payload = payload or _approval_payload()
    values = {
        "id": 7001,
        "session_id": 2001,
        "thread_id": "thread-1",
        "checkpoint_id": "checkpoint-1",
        "action_type": payload.tool_name,
        "action_payload": payload.canonical_dict(),
        "agent_reason": payload.reason,
        "status": "APPROVED",
        "payload_version": payload.payload_version,
        "payload_digest": sign_payload(payload, TEST_SECRET),
        "merchant_id": int(payload.merchant_id) if payload.merchant_id else None,
        "requested_user_id": int(payload.requested_user_id),
        "requested_role": payload.requested_role,
        "expire_at": _test_utc_now() + timedelta(hours=1),
        "execution_result": None,
    }
    values.update(overrides)
    return SimpleNamespace(**values)


def _checkpoint_values(payload=None, **overrides):
    payload = payload or _approval_payload()
    values = {
        "user_id": int(payload.requested_user_id),
        "user_role": payload.requested_role,
        "merchant_id": int(payload.merchant_id) if payload.merchant_id else None,
        "pending_hitl": True,
        "pending_action": {
            "approval_id": 7001,
            "action_type": payload.tool_name,
            "payload_digest": sign_payload(payload, TEST_SECRET),
            "approval_payload": payload.canonical_dict(),
        },
    }
    values.update(overrides)
    return values


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
                "expire_at": _test_utc_now() + timedelta(hours=1),
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
        expire_at=_test_utc_now() + timedelta(hours=1),
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
            expire_at=_test_utc_now() + timedelta(hours=1),
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


def test_validate_resume_returns_only_verified_canonical_payload():
    payload = _approval_payload()

    validated = HitlService().validate_resume(
        _bound_approval(payload),
        _checkpoint_values(payload),
    )

    assert validated == payload


@pytest.mark.parametrize(
    ("mutation", "expected_code"),
    [
        (lambda values: values["pending_action"].update(approval_id=7002), "approval_mismatch"),
        (lambda values: values["pending_action"].update(action_type="issue_compensation_coupon"), "payload_mismatch"),
        (lambda values: values["pending_action"]["approval_payload"].update(order_id="202606100004"), "payload_mismatch"),
        (lambda values: values["pending_action"]["approval_payload"].update(amount_minor=2001), "payload_mismatch"),
        (lambda values: values["pending_action"]["approval_payload"].update(target_user_id="9001"), "payload_mismatch"),
        (lambda values: values["pending_action"]["approval_payload"].update(reason="changed"), "payload_mismatch"),
        (lambda values: values["pending_action"].update(payload_digest="b" * 64), "digest_mismatch"),
        (lambda values: values.update(user_id=1002), "identity_mismatch"),
        (lambda values: values.update(user_role="cs"), "identity_mismatch"),
        (lambda values: values.update(merchant_id=43), "identity_mismatch"),
    ],
)
def test_validate_resume_rejects_checkpoint_tampering(
    monkeypatch, mutation, expected_code
):
    values = deepcopy(_checkpoint_values())
    mutation(values)
    audit_log = MagicMock()
    monkeypatch.setattr(hitl_mod.log, "warning", audit_log)

    with pytest.raises(HitlResumeError) as error:
        HitlService().validate_resume(_bound_approval(), values)

    assert error.value.code == expected_code
    audit_log.assert_called_once_with(
        "hitl_resume_validation_failed",
        approval_id=7001,
        reason=expected_code,
    )


@pytest.mark.parametrize(
    ("approval_overrides", "expected_code"),
    [
        ({"checkpoint_id": None}, "unbound_approval"),
        ({"payload_digest": None}, "unsigned_approval"),
        ({"expire_at": _test_utc_now() - timedelta(seconds=1)}, "expired_approval"),
        ({"status": "REJECTED"}, "invalid_status"),
        ({"status": "EXECUTING"}, "invalid_status"),
    ],
)
def test_validate_resume_rejects_invalid_lifecycle(approval_overrides, expected_code):
    with pytest.raises(HitlResumeError) as error:
        HitlService().validate_resume(
            _bound_approval(**approval_overrides),
            _checkpoint_values(),
        )

    assert error.value.code == expected_code


def test_validate_resume_rechecks_current_tool_role_policy():
    payload = ApprovalPayload(
        **{
            **_approval_payload().canonical_dict(),
            "requested_role": "merchant",
        }
    )

    with pytest.raises(HitlResumeError) as error:
        HitlService().validate_resume(
            _bound_approval(payload),
            _checkpoint_values(payload),
        )

    assert error.value.code == "permission_denied"


@pytest.mark.parametrize(
    "approval_overrides",
    [
        {"action_type": "issue_compensation_coupon"},
        {"action_payload": {**_approval_payload().canonical_dict(), "amount_minor": 2001}},
        {"payload_version": 2},
        {"payload_digest": "b" * 64},
        {"requested_user_id": 1002},
        {"requested_role": "cs"},
        {"merchant_id": 43},
    ],
)
def test_validate_resume_rejects_database_binding_tampering(approval_overrides):
    with pytest.raises(HitlResumeError):
        HitlService().validate_resume(
            _bound_approval(**approval_overrides),
            _checkpoint_values(),
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
    fake_session = _FakeSession(rowcounts=[0, 0])
    monkeypatch.setattr(hitl_mod, "AsyncSessionLocal", lambda: fake_session)

    ok = await HitlService().approve(approval_id=1001, approver_id=9001, comment="late")

    assert ok is False
    assert len(fake_session.executed) == 2
    assert fake_session.commits == 0
    assert fake_session.rollbacks == 2


@pytest.mark.asyncio
async def test_approve_expires_overdue_record_with_a_second_cas(monkeypatch):
    fake_session = _FakeSession(rowcounts=[0, 1])
    fake_session.loaded_approval.expire_at = datetime(2026, 8, 4, 11, 59, 59)
    monkeypatch.setattr(hitl_mod, "AsyncSessionLocal", lambda: fake_session)
    monkeypatch.setattr(hitl_mod, "_utc_now", lambda: datetime(2026, 8, 4, 12, 0, 0))

    ok = await HitlService().approve(
        approval_id=1001,
        approver_id=9001,
        comment="expired",
    )

    assert ok is False
    assert len(fake_session.executed) == 2
    assert fake_session.commits == 1
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
