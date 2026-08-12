from dataclasses import replace

import pytest

from session.hitl_binding import (
    ApprovalPayload,
    ApprovalPayloadError,
    canonical_payload_json,
    sign_payload,
    verify_payload_digest,
)


TEST_SECRET = "test-only-hitl-key"
EXPECTED_CANONICAL_JSON = (
    '{"payload_version":1,"tool_name":"execute_refund",'
    '"order_id":"202606100003","amount_minor":2000,'
    '"target_user_id":"","merchant_id":"42",'
    '"requested_user_id":"1001","requested_role":"admin",'
    '"reason":"订单状态满足退款前置条件，等待人工审批"}'
)
EXPECTED_HMAC = "e951df4e681338c555d54c2acf5f46a058dcf2be1c6beaca8c92dab32028d81a"
EXPECTED_COMPENSATION_JSON = (
    '{"payload_version":2,"tool_name":"issue_compensation_coupon",'
    '"order_id":"202606100003","amount_minor":2000,'
    '"target_user_id":"9001","shop_id":"101","merchant_id":"42",'
    '"coupon_template_id":"7001","coupon_discount_type":"CASH",'
    '"coupon_min_order_amount":5000,"coupon_valid_days":30,'
    '"coupon_terms_digest":"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",'
    '"requested_user_id":"1001","requested_role":"admin",'
    '"reason":"订单异常，补发20元优惠券"}'
)
EXPECTED_COMPENSATION_HMAC = (
    "1da8c4f042c4908ab68162d6182f8911a580dbe9e83cf6c5f6a0e2326a6b9be7"
)


def refund_payload(**overrides) -> ApprovalPayload:
    values = {
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
    values.update(overrides)
    return ApprovalPayload(**values)


def compensation_payload(**overrides) -> ApprovalPayload:
    values = {
        "payload_version": 2,
        "tool_name": "issue_compensation_coupon",
        "order_id": "202606100003",
        "amount_minor": 2000,
        "target_user_id": "9001",
        "shop_id": "101",
        "merchant_id": "42",
        "coupon_template_id": "7001",
        "coupon_discount_type": "CASH",
        "coupon_min_order_amount": 5000,
        "coupon_valid_days": 30,
        "coupon_terms_digest": "0123456789abcdef" * 4,
        "requested_user_id": "1001",
        "requested_role": "admin",
        "reason": "订单异常，补发20元优惠券",
    }
    values.update(overrides)
    return ApprovalPayload(**values)


def test_canonical_payload_and_hmac_match_committed_contract_vector():
    payload = refund_payload()

    assert canonical_payload_json(payload) == EXPECTED_CANONICAL_JSON
    assert sign_payload(payload, TEST_SECRET) == EXPECTED_HMAC
    assert verify_payload_digest(payload, EXPECTED_HMAC, TEST_SECRET) is True


def test_compensation_v2_matches_cross_language_contract_vector():
    payload = compensation_payload()

    assert canonical_payload_json(payload) == EXPECTED_COMPENSATION_JSON
    assert sign_payload(payload, TEST_SECRET) == EXPECTED_COMPENSATION_HMAC
    assert verify_payload_digest(
        payload, EXPECTED_COMPENSATION_HMAC, TEST_SECRET
    ) is True


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("shop_id", ""),
        ("coupon_template_id", ""),
        ("coupon_discount_type", "PERCENT"),
        ("coupon_min_order_amount", -1),
        ("coupon_valid_days", 0),
        ("coupon_terms_digest", "bad"),
    ],
)
def test_compensation_v2_rejects_missing_or_invalid_terms(field, value):
    with pytest.raises(ApprovalPayloadError, match=field):
        compensation_payload(**{field: value})


def test_legacy_v1_compensation_fails_closed():
    with pytest.raises(ApprovalPayloadError, match="payload_version"):
        refund_payload(
            tool_name="issue_compensation_coupon",
            target_user_id="9001",
        )


def test_payload_normalizes_string_edges_before_signing():
    payload = refund_payload(
        tool_name=" execute_refund ",
        order_id=" 202606100003 ",
        merchant_id=42,
        requested_user_id=1001,
        requested_role=" admin ",
        reason=" 订单状态满足退款前置条件，等待人工审批 ",
    )

    assert canonical_payload_json(payload) == EXPECTED_CANONICAL_JSON


@pytest.mark.parametrize(
    ("field", "changed_value"),
    [
        ("order_id", "202606100004"),
        ("amount_minor", 2001),
        ("target_user_id", "9001"),
        ("merchant_id", "43"),
        ("requested_user_id", "1002"),
        ("requested_role", "cs"),
        ("reason", "changed reason"),
    ],
)
def test_digest_rejects_every_signed_field_change(field, changed_value):
    original = refund_payload()
    changed = replace(original, **{field: changed_value})

    assert verify_payload_digest(changed, EXPECTED_HMAC, TEST_SECRET) is False


@pytest.mark.parametrize("amount", [0, -1, True, 20.5, "2000"])
def test_payload_rejects_non_positive_or_non_integer_amount(amount):
    with pytest.raises(ApprovalPayloadError, match="amount_minor"):
        refund_payload(amount_minor=amount)


@pytest.mark.parametrize(
    ("field", "value"),
    [
        ("payload_version", 2),
        ("tool_name", "unknown_tool"),
        ("order_id", " "),
        ("requested_user_id", ""),
        ("requested_role", " "),
        ("reason", ""),
    ],
)
def test_payload_rejects_missing_or_unsupported_required_fields(field, value):
    with pytest.raises(ApprovalPayloadError, match=field):
        refund_payload(**{field: value})


def test_compensation_payload_requires_target_user():
    with pytest.raises(ApprovalPayloadError, match="target_user_id"):
        compensation_payload(target_user_id="")


@pytest.mark.parametrize("secret", ["", " ", None])
def test_signing_rejects_missing_secret(secret):
    with pytest.raises(ApprovalPayloadError, match="secret"):
        sign_payload(refund_payload(), secret)


def test_digest_rejects_malformed_hex_without_raising():
    assert verify_payload_digest(refund_payload(), "not-a-digest", TEST_SECRET) is False
