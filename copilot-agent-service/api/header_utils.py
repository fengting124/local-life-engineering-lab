"""Shared HTTP header parsing helpers for Agent APIs."""
from fastapi import HTTPException


def parse_user_id_header(value: str) -> int:
    """Parse X-User-Id into an integer or raise a clean 400."""
    try:
        return int(value)
    except ValueError:
        raise HTTPException(status_code=400, detail="X-User-Id 必须是数字")


def parse_optional_merchant_id_header(value: str | None) -> int | None:
    """Parse optional X-Merchant-Id into a positive integer or raise a clean 400."""
    if not value:
        return None
    try:
        merchant_id = int(value)
    except ValueError:
        raise HTTPException(status_code=400, detail="X-Merchant-Id 必须是数字")
    if merchant_id <= 0:
        raise HTTPException(status_code=400, detail="X-Merchant-Id 必须是正数")
    return merchant_id
