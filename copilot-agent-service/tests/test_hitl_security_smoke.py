import importlib.util
import json
from pathlib import Path


REPOSITORY_ROOT = Path(__file__).resolve().parents[2]
SCRIPT_PATH = REPOSITORY_ROOT / "scripts/hitl-security-smoke.py"


def load_smoke_module():
    spec = importlib.util.spec_from_file_location("hitl_security_smoke", SCRIPT_PATH)
    module = importlib.util.module_from_spec(spec)
    assert spec.loader is not None
    spec.loader.exec_module(module)
    return module


def test_smoke_signing_matches_the_cross_language_contract_vector():
    smoke = load_smoke_module()
    payload = {
        "payload_version": 1,
        "tool_name": "execute_refund",
        "order_id": "202606100003",
        "amount_minor": 2000,
        "target_user_id": "",
        "merchant_id": "42",
        "requested_user_id": "1001",
        "requested_role": "admin",
        "reason": "订单状态满足退款前置条件，等待人工审批",
    }

    canonical = smoke.canonical_payload_json(payload)

    assert canonical == (
        '{"payload_version":1,"tool_name":"execute_refund",'
        '"order_id":"202606100003","amount_minor":2000,'
        '"target_user_id":"","merchant_id":"42",'
        '"requested_user_id":"1001","requested_role":"admin",'
        '"reason":"订单状态满足退款前置条件，等待人工审批"}'
    )
    assert smoke.sign_payload(payload, "test-only-hitl-key") == (
        "e951df4e681338c555d54c2acf5f46a058dcf2be1c6beaca8c92dab32028d81a"
    )


def test_smoke_report_redacts_credentials_and_bound_payloads(tmp_path):
    smoke = load_smoke_module()
    report_path = tmp_path / "report.json"
    secret = "must-never-appear"
    evidence = {
        "run_id": "hitl-smoke-1",
        "approval_id": 123,
        "payload_digest": "a" * 64,
        "request_payload": {"order_id": "ORDER_PRIVATE"},
        "nested": {
            "signature": secret,
            "status": "EXECUTED",
        },
        "credential_leak_detected": False,
    }

    smoke.write_sanitized_report(report_path, evidence)
    stored = report_path.read_text(encoding="utf-8")
    parsed = json.loads(stored)

    assert secret not in stored
    assert "ORDER_PRIVATE" not in stored
    assert parsed["approval_id"] == 123
    assert parsed["nested"]["status"] == "EXECUTED"
    assert parsed["payload_digest"] == "[REDACTED]"
    assert parsed["request_payload"] == "[REDACTED]"
    assert parsed["nested"]["signature"] == "[REDACTED]"
    assert parsed["credential_leak_detected"] is False


def test_tamper_smoke_mutates_a_real_langgraph_checkpoint():
    source = SCRIPT_PATH.read_text(encoding="utf-8")

    assert "checkpointer.aget_tuple" in source
    assert "deepcopy(saved.checkpoint)" in source
    assert 'tampered["channel_values"]' in source
    assert "checkpointer.serde.dumps_typed(tampered)" in source
    assert "UPDATE langgraph_checkpoint_v2" in source
    assert "state_type = :state_type" in source
    assert "state_blob = :state_blob" in source
    assert "empty_checkpoint" not in source


def test_ambiguous_outcome_smoke_recovers_an_expired_execution_lease():
    source = SCRIPT_PATH.read_text(encoding="utf-8")

    assert "status = 'EXECUTING'" in source
    assert "execution_lease_until = UTC_TIMESTAMP() - INTERVAL 1 SECOND" in source
    assert '"lease_recovered": True' in source
