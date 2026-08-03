"""
api/hitl.py endpoint tests.
"""
from datetime import datetime, timedelta
from unittest.mock import AsyncMock, patch

from fastapi import FastAPI
from starlette.testclient import TestClient

from api.hitl import router as hitl_router


app = FastAPI()
app.include_router(hitl_router)
client = TestClient(app, raise_server_exceptions=False)


def make_approval(**overrides):
    data = {
        "id": 1001,
        "session_id": 2001,
        "thread_id": "thread-1",
        "checkpoint_id": "checkpoint-1",
        "action_type": "execute_refund",
        "action_payload": {"order_id": "O-1"},
        "payload_version": 1,
        "payload_digest": "a" * 64,
        "merchant_id": None,
        "requested_user_id": 1001,
        "requested_role": "cs",
        "agent_reason": "订单异常，需要退款",
        "status": "PENDING",
        "approver_id": None,
        "approver_comment": None,
        "approved_at": None,
        "expire_at": datetime.now() + timedelta(hours=1),
        "created_at": datetime.now(),
    }
    data.update(overrides)
    return type("Approval", (), data)()


def test_pending_requires_operator_user_id():
    resp = client.get("/hitl/pending", headers={"X-User-Role": "cs"})
    assert resp.status_code == 422


def test_pending_rejects_non_operator_role():
    resp = client.get("/hitl/pending", headers={"X-User-Id": "1", "X-User-Role": "merchant"})
    assert resp.status_code == 403


def test_pending_returns_sanitized_queue_items_without_thread_id():
    approval = make_approval()
    with patch("api.hitl.hitl_service.get_pending_approvals", AsyncMock(return_value=[approval])):
        resp = client.get("/hitl/pending", headers={"X-User-Id": "9", "X-User-Role": "cs"})

    assert resp.status_code == 200
    body = resp.json()
    assert body["count"] == 1
    assert body["approvals"][0]["id"] == 1001
    assert "thread_id" not in body["approvals"][0]


def test_pending_passes_optional_merchant_scope_to_service():
    with patch("api.hitl.hitl_service.get_pending_approvals", AsyncMock(return_value=[])) as pending_mock:
        resp = client.get(
            "/hitl/pending",
            headers={"X-User-Id": "9", "X-User-Role": "cs", "X-Merchant-Id": "42"},
        )

    assert resp.status_code == 200
    pending_mock.assert_awaited_once_with(limit=100, merchant_id=42)


def test_pending_rejects_invalid_merchant_scope_header():
    with patch("api.hitl.hitl_service.get_pending_approvals", AsyncMock(return_value=[])) as pending_mock:
        resp = client.get(
            "/hitl/pending",
            headers={"X-User-Id": "9", "X-User-Role": "cs", "X-Merchant-Id": "not-a-number"},
        )

    assert resp.status_code == 400
    pending_mock.assert_not_awaited()


def test_detail_rejects_non_operator_role():
    resp = client.get("/hitl/1001", headers={"X-User-Id": "1", "X-User-Role": "merchant"})
    assert resp.status_code == 403


def test_detail_hides_record_outside_merchant_scope():
    approval = make_approval(action_payload={"order_id": "O-1", "merchant_id": 42})

    with patch("api.hitl.hitl_service.get_approval", AsyncMock(return_value=approval)):
        resp = client.get(
            "/hitl/1001",
            headers={"X-User-Id": "9", "X-User-Role": "cs", "X-Merchant-Id": "99"},
        )

    assert resp.status_code == 404


def test_approve_checks_record_before_mutating_and_returns_bound_thread():
    approval = make_approval(thread_id="thread-bound")

    with patch("api.hitl.hitl_service.get_approval", AsyncMock(return_value=approval)) as get_mock, \
         patch("api.hitl.hitl_service.approve", AsyncMock(return_value=True)) as approve_mock:
        resp = client.post(
            "/hitl/1001/approve",
            json={"comment": "已核实"},
            headers={"X-User-Id": "9", "X-User-Role": "admin"},
        )

    assert resp.status_code == 200
    assert resp.json()["thread_id"] == "thread-bound"
    get_mock.assert_awaited_once_with(1001)
    approve_mock.assert_awaited_once_with(1001, 9, "已核实")


def test_approve_rejects_non_pending_record_without_mutating():
    approval = make_approval(status="APPROVED")

    with patch("api.hitl.hitl_service.get_approval", AsyncMock(return_value=approval)), \
         patch("api.hitl.hitl_service.approve", AsyncMock(return_value=True)) as approve_mock:
        resp = client.post(
            "/hitl/1001/approve",
            json={"comment": "重复审批"},
            headers={"X-User-Id": "9", "X-User-Role": "cs"},
        )

    assert resp.status_code == 400
    approve_mock.assert_not_awaited()


def test_approve_rejects_unbound_record_without_mutating():
    approval = make_approval(checkpoint_id=None)

    with patch("api.hitl.hitl_service.get_approval", AsyncMock(return_value=approval)), \
         patch("api.hitl.hitl_service.approve", AsyncMock(return_value=True)) as approve_mock:
        resp = client.post(
            "/hitl/1001/approve",
            json={"comment": "不能批准未绑定审批"},
            headers={"X-User-Id": "9", "X-User-Role": "cs"},
        )

    assert resp.status_code == 409
    assert resp.json()["detail"]["code"] == "unbound_approval"
    approve_mock.assert_not_awaited()


def test_approve_rejects_record_outside_merchant_scope_without_mutating():
    approval = make_approval(action_payload={"order_id": "O-1", "merchant_id": 42})

    with patch("api.hitl.hitl_service.get_approval", AsyncMock(return_value=approval)), \
         patch("api.hitl.hitl_service.approve", AsyncMock(return_value=True)) as approve_mock:
        resp = client.post(
            "/hitl/1001/approve",
            json={"comment": "越权审批"},
            headers={"X-User-Id": "9", "X-User-Role": "cs", "X-Merchant-Id": "99"},
        )

    assert resp.status_code == 404
    approve_mock.assert_not_awaited()


def test_reject_checks_record_before_mutating_and_returns_bound_thread():
    approval = make_approval(thread_id="thread-reject")

    with patch("api.hitl.hitl_service.get_approval", AsyncMock(return_value=approval)) as get_mock, \
         patch("api.hitl.hitl_service.reject", AsyncMock(return_value=True)) as reject_mock:
        resp = client.post(
            "/hitl/1001/reject",
            json={"comment": "证据不足"},
            headers={"X-User-Id": "9", "X-User-Role": "cs"},
        )

    assert resp.status_code == 200
    assert resp.json()["thread_id"] == "thread-reject"
    get_mock.assert_awaited_once_with(1001)
    reject_mock.assert_awaited_once_with(1001, 9, "证据不足")
