"""Immutable payload contract for high-risk HITL approvals."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import json
from typing import Any


REFUND_PAYLOAD_VERSION = 1
COMPENSATION_PAYLOAD_VERSION = 2


class ApprovalPayloadError(ValueError):
    """Raised when an approval payload cannot be signed safely."""


def _required_text(field: str, value: Any) -> str:
    normalized = str(value).strip() if value is not None else ""
    if not normalized:
        raise ApprovalPayloadError(f"{field} is required")
    return normalized


def _optional_text(value: Any) -> str:
    return str(value).strip() if value is not None else ""


@dataclass(frozen=True, kw_only=True)
class ApprovalPayload:
    payload_version: int
    tool_name: str
    order_id: str
    amount_minor: int
    target_user_id: str
    shop_id: str = ""
    merchant_id: str
    coupon_template_id: str = ""
    coupon_discount_type: str = ""
    coupon_min_order_amount: int = 0
    coupon_valid_days: int = 0
    coupon_terms_digest: str = ""
    requested_user_id: str
    requested_role: str
    reason: str

    def __post_init__(self) -> None:
        if isinstance(self.amount_minor, bool) or not isinstance(self.amount_minor, int):
            raise ApprovalPayloadError("amount_minor must be an integer")
        if self.amount_minor <= 0:
            raise ApprovalPayloadError("amount_minor must be positive")

        tool_name = _required_text("tool_name", self.tool_name)
        target_user_id = _optional_text(self.target_user_id)
        if self.payload_version == REFUND_PAYLOAD_VERSION:
            if tool_name != "execute_refund":
                raise ApprovalPayloadError("payload_version does not support tool_name")
        elif self.payload_version == COMPENSATION_PAYLOAD_VERSION:
            if tool_name != "issue_compensation_coupon":
                raise ApprovalPayloadError("payload_version does not support tool_name")
            self._validate_compensation_fields(target_user_id)
        else:
            raise ApprovalPayloadError("payload_version is unsupported")

        object.__setattr__(self, "tool_name", tool_name)
        object.__setattr__(self, "order_id", _required_text("order_id", self.order_id))
        object.__setattr__(self, "target_user_id", target_user_id)
        object.__setattr__(self, "merchant_id", _optional_text(self.merchant_id))
        object.__setattr__(
            self,
            "requested_user_id",
            _required_text("requested_user_id", self.requested_user_id),
        )
        object.__setattr__(
            self,
            "requested_role",
            _required_text("requested_role", self.requested_role),
        )
        object.__setattr__(self, "reason", _required_text("reason", self.reason))

    def _validate_compensation_fields(self, target_user_id: str) -> None:
        if not target_user_id:
            raise ApprovalPayloadError("target_user_id is required")
        object.__setattr__(self, "shop_id", _required_text("shop_id", self.shop_id))
        object.__setattr__(
            self,
            "coupon_template_id",
            _required_text("coupon_template_id", self.coupon_template_id),
        )
        discount_type = _required_text(
            "coupon_discount_type", self.coupon_discount_type
        )
        if discount_type != "CASH":
            raise ApprovalPayloadError("coupon_discount_type must be CASH")
        object.__setattr__(self, "coupon_discount_type", discount_type)
        if (
            isinstance(self.coupon_min_order_amount, bool)
            or not isinstance(self.coupon_min_order_amount, int)
            or self.coupon_min_order_amount < 0
        ):
            raise ApprovalPayloadError(
                "coupon_min_order_amount must be a non-negative integer"
            )
        if (
            isinstance(self.coupon_valid_days, bool)
            or not isinstance(self.coupon_valid_days, int)
            or self.coupon_valid_days <= 0
        ):
            raise ApprovalPayloadError("coupon_valid_days must be a positive integer")
        digest = _required_text("coupon_terms_digest", self.coupon_terms_digest)
        if len(digest) != 64:
            raise ApprovalPayloadError("coupon_terms_digest must be SHA-256 hex")
        try:
            bytes.fromhex(digest)
        except ValueError as error:
            raise ApprovalPayloadError(
                "coupon_terms_digest must be SHA-256 hex"
            ) from error
        object.__setattr__(self, "coupon_terms_digest", digest.lower())

    def canonical_dict(self) -> dict[str, str | int]:
        base = {
            "payload_version": self.payload_version,
            "tool_name": self.tool_name,
            "order_id": self.order_id,
            "amount_minor": self.amount_minor,
            "target_user_id": self.target_user_id,
        }
        if self.payload_version == COMPENSATION_PAYLOAD_VERSION:
            base.update({
                "shop_id": self.shop_id,
                "merchant_id": self.merchant_id,
                "coupon_template_id": self.coupon_template_id,
                "coupon_discount_type": self.coupon_discount_type,
                "coupon_min_order_amount": self.coupon_min_order_amount,
                "coupon_valid_days": self.coupon_valid_days,
                "coupon_terms_digest": self.coupon_terms_digest,
            })
        else:
            base["merchant_id"] = self.merchant_id
        base.update({
            "requested_user_id": self.requested_user_id,
            "requested_role": self.requested_role,
            "reason": self.reason,
        })
        return base


def canonical_payload_json(payload: ApprovalPayload) -> str:
    return json.dumps(
        payload.canonical_dict(),
        ensure_ascii=False,
        separators=(",", ":"),
    )


def _secret_bytes(secret: str | None) -> bytes:
    normalized = secret.strip() if isinstance(secret, str) else ""
    if not normalized:
        raise ApprovalPayloadError("secret is required")
    return normalized.encode("utf-8")


def sign_payload(payload: ApprovalPayload, secret: str | None) -> str:
    return hmac.new(
        _secret_bytes(secret),
        canonical_payload_json(payload).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def verify_payload_digest(
    payload: ApprovalPayload,
    digest: str,
    secret: str | None,
) -> bool:
    if not isinstance(digest, str) or len(digest) != hashlib.sha256().digest_size * 2:
        return False
    try:
        bytes.fromhex(digest)
    except ValueError:
        return False
    return hmac.compare_digest(sign_payload(payload, secret), digest.lower())
