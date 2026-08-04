"""Immutable payload contract for high-risk HITL approvals."""

from __future__ import annotations

from dataclasses import dataclass
import hashlib
import hmac
import json
from typing import Any


SUPPORTED_PAYLOAD_VERSION = 1
SUPPORTED_TOOLS = frozenset({"execute_refund", "issue_compensation_coupon"})


class ApprovalPayloadError(ValueError):
    """Raised when an approval payload cannot be signed safely."""


def _required_text(field: str, value: Any) -> str:
    normalized = str(value).strip() if value is not None else ""
    if not normalized:
        raise ApprovalPayloadError(f"{field} is required")
    return normalized


def _optional_text(value: Any) -> str:
    return str(value).strip() if value is not None else ""


@dataclass(frozen=True)
class ApprovalPayload:
    payload_version: int
    tool_name: str
    order_id: str
    amount_minor: int
    target_user_id: str
    merchant_id: str
    requested_user_id: str
    requested_role: str
    reason: str

    def __post_init__(self) -> None:
        if self.payload_version != SUPPORTED_PAYLOAD_VERSION:
            raise ApprovalPayloadError("payload_version is unsupported")
        if isinstance(self.amount_minor, bool) or not isinstance(self.amount_minor, int):
            raise ApprovalPayloadError("amount_minor must be an integer")
        if self.amount_minor <= 0:
            raise ApprovalPayloadError("amount_minor must be positive")

        tool_name = _required_text("tool_name", self.tool_name)
        if tool_name not in SUPPORTED_TOOLS:
            raise ApprovalPayloadError("tool_name is unsupported")

        target_user_id = _optional_text(self.target_user_id)
        if tool_name == "issue_compensation_coupon" and not target_user_id:
            raise ApprovalPayloadError("target_user_id is required")

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

    def canonical_dict(self) -> dict[str, str | int]:
        return {
            "payload_version": self.payload_version,
            "tool_name": self.tool_name,
            "order_id": self.order_id,
            "amount_minor": self.amount_minor,
            "target_user_id": self.target_user_id,
            "merchant_id": self.merchant_id,
            "requested_user_id": self.requested_user_id,
            "requested_role": self.requested_role,
            "reason": self.reason,
        }


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
