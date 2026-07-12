"""
api/session.py endpoint tests.
"""
from fastapi import FastAPI
from starlette.testclient import TestClient

from api.session import router as session_router


app = FastAPI()
app.include_router(session_router)
client = TestClient(app, raise_server_exceptions=False)


def test_create_session_rejects_non_numeric_user_id_before_db_work():
    resp = client.post(
        "/sessions",
        json={"initial_message": "你好"},
        headers={"X-User-Id": "not-a-number", "X-User-Role": "merchant", "X-Merchant-Id": "42"},
    )

    assert resp.status_code == 400
    assert resp.json()["detail"] == "X-User-Id 必须是数字"


def test_create_session_rejects_non_numeric_merchant_id_before_db_work():
    resp = client.post(
        "/sessions",
        json={"initial_message": "你好"},
        headers={
            "X-User-Id": "1",
            "X-User-Role": "merchant",
            "X-Merchant-Id": "not-a-number",
        },
    )

    assert resp.status_code == 400
    assert resp.json()["detail"] == "X-Merchant-Id 必须是数字"
