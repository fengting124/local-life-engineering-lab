"""Read evaluation fixtures and tool evidence from the existing MySQL schema."""
from __future__ import annotations

import asyncio
import json
import os
from dataclasses import replace
from typing import Any

from sqlalchemy import text
from sqlalchemy.ext.asyncio import AsyncEngine, create_async_engine

from evals.eval_scoring import ToolEvidence
from evals.fixtures import FixtureCatalog


MISSING_ORDER_NO = "2026999999999999999"

FIXTURE_QUERIES = {
    "merchant_actor": """
        SELECT m.user_id, m.id AS merchant_id
        FROM merchant m
        WHERE m.id = 880000100001
          AND m.status = 'APPROVED' AND m.deleted = 0
        LIMIT 1
    """,
    "cs_actor": """
        SELECT id AS user_id FROM user
        WHERE nickname = 'perf_user_1' AND status = 'ENABLED' AND deleted = 0
        LIMIT 1
    """,
    "admin_actor": """
        SELECT id AS user_id FROM user
        WHERE nickname = 'perf_user_2' AND status = 'ENABLED' AND deleted = 0
        LIMIT 1
    """,
    "paid_order": """
        SELECT order_no FROM order_info
        WHERE id = 880001000001 AND order_status = 'PAID' AND deleted = 0
        LIMIT 1
    """,
    "payment_mismatch_order": """
        SELECT order_no FROM order_info
        WHERE id = 880001000002 AND order_status = 'WAIT_PAY' AND deleted = 0
        LIMIT 1
    """,
    "coupon_issue_order": """
        SELECT order_no FROM order_info
        WHERE id = 880001000003 AND order_status = 'PAID' AND deleted = 0
        LIMIT 1
    """,
    "failed_payment_order": """
        SELECT o.order_no
        FROM order_info o
        JOIN payment_order p ON p.order_id = o.id
        WHERE p.id = 881700000095
          AND o.id = 881600000095
          AND p.pay_status = 'FAILED'
          AND o.deleted = 0
        LIMIT 1
    """,
    "missing_order_count": """
        SELECT COUNT(*) AS count FROM order_info
        WHERE order_no = '2026999999999999999'
    """,
}


class EvalDatabase:
    def __init__(self, db_url: str | None = None):
        self.db_url = db_url or os.environ.get(
            "EVAL_DB_URL",
            "mysql+aiomysql://root:123456@localhost:3306/local_life",
        )
        self._engine: AsyncEngine = create_async_engine(
            self.db_url,
            pool_pre_ping=False,
        )

    async def close(self) -> None:
        await self._engine.dispose()

    async def load_fixtures(self) -> FixtureCatalog:
        rows: dict[str, dict[str, Any] | None] = {}
        async with self._engine.connect() as connection:
            for name, query in FIXTURE_QUERIES.items():
                result = await connection.execute(text(query))
                row = result.mappings().first()
                rows[name] = dict(row) if row is not None else None
        return _fixture_values_from_rows(rows)

    async def load_evidence(self, session_id: int | str | None) -> list[ToolEvidence]:
        if session_id is None:
            return []
        for attempt in range(3):
            async with self._engine.connect() as connection:
                messages_result = await connection.execute(
                    text(
                        """
                        SELECT tool_calls, tool_results
                        FROM agent_message
                        WHERE session_id = :session_id
                          AND (tool_calls IS NOT NULL OR tool_results IS NOT NULL)
                        ORDER BY step_index, id
                        """
                    ),
                    {"session_id": int(session_id)},
                )
                audits_result = await connection.execute(
                    text(
                        """
                        SELECT tool_name, tool_input, tool_output, status, error_msg
                        FROM tool_audit_log
                        WHERE session_id = :session_id
                        ORDER BY id
                        """
                    ),
                    {"session_id": int(session_id)},
                )
                messages = [dict(row) for row in messages_result.mappings().all()]
                audits = [dict(row) for row in audits_result.mappings().all()]
            evidence = _merge_evidence(
                _evidence_from_messages(messages),
                _evidence_from_audits(audits),
            )
            if evidence or attempt == 2:
                return evidence
            await asyncio.sleep(0.1)
        return []


def _fixture_values_from_rows(
    rows: dict[str, dict[str, Any] | None],
) -> FixtureCatalog:
    required = {
        name for name in FIXTURE_QUERIES
        if name != "missing_order_count"
    }
    missing = sorted(name for name in required if not rows.get(name))
    missing_count = rows.get("missing_order_count")
    if not missing_count or int(missing_count.get("count", 1)) != 0:
        missing.append("missing_order_count")
    if missing:
        raise ValueError("missing database eval fixtures: " + ", ".join(missing))

    return FixtureCatalog(
        {
            "actor.merchant.user_id": rows["merchant_actor"]["user_id"],
            "actor.merchant.merchant_id": rows["merchant_actor"]["merchant_id"],
            "actor.cs.user_id": rows["cs_actor"]["user_id"],
            "actor.admin.user_id": rows["admin_actor"]["user_id"],
            "order.paid.order_no": rows["paid_order"]["order_no"],
            "order.payment_mismatch.order_no": rows["payment_mismatch_order"]["order_no"],
            "order.coupon_issue.order_no": rows["coupon_issue_order"]["order_no"],
            "order.failed_payment.order_no": rows["failed_payment_order"]["order_no"],
            "order.missing.order_no": MISSING_ORDER_NO,
        }
    )


def _evidence_from_messages(
    messages: list[dict[str, Any]],
) -> list[ToolEvidence]:
    calls: dict[str, dict[str, Any]] = {}
    order: list[str] = []
    results: dict[str, dict[str, Any]] = {}

    for message in messages:
        for call in _as_list(message.get("tool_calls")):
            call_id = str(call.get("id", ""))
            if not call_id:
                continue
            calls[call_id] = call
            order.append(call_id)
        for result in _as_list(message.get("tool_results")):
            call_id = str(result.get("call_id", ""))
            if call_id:
                results[call_id] = result

    evidence = []
    for call_id in order:
        call = calls[call_id]
        result = results.get(call_id, {})
        content = result.get("content")
        error = (
            str(content)
            if isinstance(content, str)
            and content.startswith(("[工具错误]", "[工具异常]", "[系统异常]"))
            else None
        )
        evidence.append(
            ToolEvidence(
                name=str(call.get("name") or result.get("name") or "unknown"),
                arguments=call.get("args") if isinstance(call.get("args"), dict) else {},
                output=_decode_json(content),
                status="error" if error else ("success" if result else "missing"),
                error=error,
            )
        )
    return evidence


def _evidence_from_audits(audits: list[dict[str, Any]]) -> list[ToolEvidence]:
    return [
        ToolEvidence(
            name=str(audit.get("tool_name", "unknown")),
            arguments=_as_dict(audit.get("tool_input")),
            output=_decode_json(audit.get("tool_output")),
            status=str(audit.get("status", "missing")),
            error=(
                str(audit["error_msg"])
                if audit.get("error_msg") is not None else None
            ),
        )
        for audit in audits
    ]


def _merge_evidence(
    messages: list[ToolEvidence],
    audits: list[ToolEvidence],
) -> list[ToolEvidence]:
    merged = list(messages)
    consumed: set[int] = set()
    for audit in audits:
        match = next(
            (
                index for index, item in enumerate(merged)
                if index not in consumed
                and item.name == audit.name
                and item.arguments == audit.arguments
            ),
            None,
        )
        if match is None:
            merged.append(audit)
        else:
            message = merged[match]
            if message.error and audit.status != "success":
                audit = replace(
                    audit,
                    error="; ".join(
                        part for part in (message.error, audit.error) if part
                    ),
                )
            merged[match] = audit
            consumed.add(match)
    return merged


def _as_list(value: Any) -> list[dict[str, Any]]:
    decoded = _decode_json(value)
    if not isinstance(decoded, list):
        return []
    return [item for item in decoded if isinstance(item, dict)]


def _as_dict(value: Any) -> dict[str, Any]:
    decoded = _decode_json(value)
    return decoded if isinstance(decoded, dict) else {}


def _decode_json(value: Any) -> Any:
    if not isinstance(value, str):
        return value
    try:
        return json.loads(value)
    except json.JSONDecodeError:
        return value
