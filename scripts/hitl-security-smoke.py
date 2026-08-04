#!/usr/bin/env python3
"""Run deterministic HITL security scenarios against Docker Compose Lite."""

from __future__ import annotations

import hashlib
import hmac
import json
import os
import subprocess
import sys
import time
import urllib.error
import urllib.request
from concurrent.futures import ThreadPoolExecutor
from pathlib import Path
from typing import Any


PAYLOAD_FIELDS = (
    "payload_version",
    "tool_name",
    "order_id",
    "amount_minor",
    "target_user_id",
    "merchant_id",
    "requested_user_id",
    "requested_role",
    "reason",
)
SENSITIVE_REPORT_KEY_PARTS = (
    "secret",
    "signature",
    "digest",
    "payload",
    "api_key",
    "token",
)


def canonical_payload_json(payload: dict[str, Any]) -> str:
    ordered = {field: payload[field] for field in PAYLOAD_FIELDS}
    return json.dumps(ordered, ensure_ascii=False, separators=(",", ":"))


def sign_payload(payload: dict[str, Any], secret: str) -> str:
    return hmac.new(
        secret.encode("utf-8"),
        canonical_payload_json(payload).encode("utf-8"),
        hashlib.sha256,
    ).hexdigest()


def sanitize_evidence(value: Any) -> Any:
    if isinstance(value, dict):
        sanitized = {}
        for key, item in value.items():
            lower = str(key).lower()
            if any(part in lower for part in SENSITIVE_REPORT_KEY_PARTS):
                sanitized[key] = "[REDACTED]"
            else:
                sanitized[key] = sanitize_evidence(item)
        return sanitized
    if isinstance(value, list):
        return [sanitize_evidence(item) for item in value]
    return value


def write_sanitized_report(path: Path, evidence: dict[str, Any]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(
        json.dumps(sanitize_evidence(evidence), ensure_ascii=False, indent=2),
        encoding="utf-8",
    )


def sql_literal(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, bool):
        return "1" if value else "0"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


class SmokeFailure(RuntimeError):
    pass


class HitlSecuritySmoke:
    def __init__(self) -> None:
        self.hitl_secret = self._required_env("HITL_PAYLOAD_SIGNING_SECRET")
        self.mcp_secret = os.getenv(
            "MCP_CONTEXT_SIGNING_SECRET", "local-life-mcp-context-secret"
        )
        self.internal_key = os.getenv(
            "INTERNAL_API_KEY", "local-life-internal-secret"
        )
        self.mysql_password = os.getenv("MYSQL_ROOT_PASSWORD", "123456")
        self.mysql_database = os.getenv("MYSQL_DATABASE", "local_life")
        self.mysql_container = os.getenv("MYSQL_CONTAINER", "local-life-mysql")
        self.agent_container = os.getenv(
            "AGENT_CONTAINER", "copilot-agent-service"
        )
        self.agent_url = os.getenv("AGENT_URL", "http://localhost:8000")
        self.copilot_url = os.getenv("COPILOT_URL", "http://localhost:8081")
        self.server_url = os.getenv("SERVER_URL", "http://localhost:8080")

        epoch = int(time.time() * 1000)
        suffix = epoch % 100_000_000_000
        self.run_token = f"hitl-smoke-{epoch}"
        self.id_base = 7_700_000_000_000_000_000 + suffix * 100
        self.user_id = 7_001_001
        self.merchant_id = 7_002_001
        self.approval_ids: list[int] = []
        self.session_ids: list[int] = []
        self.thread_ids: list[str] = []
        self.orders: list[tuple[str, int, int]] = []
        self.evidence: dict[str, Any] = {
            "run_id": self.run_token,
            "started_at_epoch_ms": epoch,
            "status": "RUNNING",
            "scenarios": {},
        }

    @staticmethod
    def _required_env(name: str) -> str:
        value = os.getenv(name, "").strip()
        if not value:
            raise SmokeFailure(f"required environment variable {name} is missing")
        return value

    def sql(self, statement: str) -> str:
        command = [
            "docker",
            "exec",
            "-i",
            "-e",
            f"MYSQL_PWD={self.mysql_password}",
            self.mysql_container,
            "mysql",
            "-uroot",
            "--batch",
            "--skip-column-names",
            self.mysql_database,
        ]
        result = subprocess.run(
            command,
            input=statement,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise SmokeFailure(f"mysql command failed: {result.stderr.strip()}")
        return result.stdout.strip()

    def scalar(self, statement: str) -> str:
        output = self.sql(statement)
        return output.splitlines()[0] if output else ""

    def http_json(
        self,
        method: str,
        url: str,
        *,
        body: dict[str, Any] | None = None,
        headers: dict[str, str] | None = None,
        expected_status: int = 200,
        timeout: float = 15,
    ) -> tuple[int, Any, str]:
        encoded = None
        request_headers = {"Accept": "application/json"}
        if body is not None:
            encoded = json.dumps(body, ensure_ascii=False).encode("utf-8")
            request_headers["Content-Type"] = "application/json"
        request_headers.update(headers or {})
        request = urllib.request.Request(
            url, data=encoded, headers=request_headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=timeout) as response:
                status = response.status
                raw = response.read().decode("utf-8")
        except urllib.error.HTTPError as error:
            status = error.code
            raw = error.read().decode("utf-8")
        if status != expected_status:
            raise SmokeFailure(
                f"{method} {url} returned HTTP {status}, expected {expected_status}: "
                f"{raw[:300]}"
            )
        try:
            parsed = json.loads(raw) if raw else None
        except json.JSONDecodeError:
            parsed = None
        return status, parsed, raw

    def wait_health(self, url: str, timeout_seconds: int = 120) -> None:
        deadline = time.monotonic() + timeout_seconds
        last_error = "not attempted"
        while time.monotonic() < deadline:
            try:
                status, _, raw = self.http_json(
                    "GET", url, expected_status=200, timeout=5
                )
                if status == 200 and raw:
                    return
            except Exception as error:  # readiness polling records final cause
                last_error = str(error)
            time.sleep(2)
        raise SmokeFailure(f"health check timed out for {url}: {last_error}")

    def identity_headers(
        self, *, session_id: int, thread_id: str, role: str = "admin"
    ) -> dict[str, str]:
        timestamp = str(int(time.time()))
        merchant = str(self.merchant_id)
        canonical = f"{self.user_id}\n{role}\n{merchant}\n{timestamp}"
        signature = hmac.new(
            self.mcp_secret.encode("utf-8"),
            canonical.encode("utf-8"),
            hashlib.sha256,
        ).hexdigest()
        return {
            "X-User-Id": str(self.user_id),
            "X-User-Role": role,
            "X-Merchant-Id": merchant,
            "X-Agent-Timestamp": timestamp,
            "X-Agent-Signature": signature,
            "X-Session-Id": str(session_id),
            "X-Thread-Id": thread_id,
            "X-Trace-Id": self.trace_id(thread_id),
        }

    @staticmethod
    def trace_id(thread_id: str) -> str:
        return hashlib.sha256(thread_id.encode("utf-8")).hexdigest()[:32]

    def call_mcp(
        self,
        *,
        session_id: int,
        thread_id: str,
        tool_name: str,
        arguments: dict[str, Any],
    ) -> dict[str, Any]:
        _, response, _ = self.http_json(
            "POST",
            f"{self.copilot_url}/mcp",
            body={
                "jsonrpc": "2.0",
                "id": f"{self.run_token}-{tool_name}",
                "method": "tools/call",
                "params": {"name": tool_name, "arguments": arguments},
            },
            headers=self.identity_headers(
                session_id=session_id, thread_id=thread_id
            ),
        )
        if not isinstance(response, dict):
            raise SmokeFailure(f"MCP {tool_name} returned non-JSON response")
        return response

    def create_session(self, offset: int, thread_id: str) -> int:
        session_id = self.id_base + offset
        self.session_ids.append(session_id)
        self.thread_ids.append(thread_id)
        run_id = f"run-{thread_id}"
        trace_id = self.trace_id(thread_id)
        self.sql(
            f"""
            INSERT INTO agent_session
                (id, user_id, user_role, merchant_id, title, status,
                 total_tokens, total_cost_cents, created_at, updated_at)
            VALUES
                ({session_id}, {self.user_id}, 'admin', {self.merchant_id},
                 {sql_literal(self.run_token)}, 'PENDING_APPROVAL', 0, 0,
                 NOW(), NOW());
            INSERT INTO agent_run
                (id, session_id, thread_id, trace_id, user_id, user_role,
                 merchant_id, status, input_summary)
            VALUES
                ({sql_literal(run_id)}, {session_id}, {sql_literal(thread_id)},
                 {sql_literal(trace_id)}, {self.user_id}, 'admin',
                 {self.merchant_id}, 'WAITING_APPROVAL', 'HITL security smoke');
            """
        )
        return session_id

    def create_order(self, offset: int, amount: int = 5000) -> tuple[str, int]:
        order_id = self.id_base + 1_000 + offset
        user_id = self.user_id + offset
        shard = user_id % 4
        order_no = f"HS{str(order_id)[-20:]}"
        self.orders.append((order_no, order_id, shard))
        self.sql(
            f"""
            INSERT INTO order_info_{shard}
                (id, order_no, user_id, shop_id, original_amount,
                 coupon_discount, order_amount, order_status, remark,
                 expire_at, pay_at, deleted)
            VALUES
                ({order_id}, {sql_literal(order_no)}, {user_id}, {self.merchant_id},
                 {amount}, 0, {amount}, 'PAID', 'HITL security smoke',
                 NOW() + INTERVAL 1 DAY, NOW(), 0);
            """
        )
        return order_no, user_id

    def payload(
        self,
        *,
        tool_name: str,
        order_id: str,
        amount: int,
        target_user_id: str = "",
        reason: str,
    ) -> dict[str, Any]:
        return {
            "payload_version": 1,
            "tool_name": tool_name,
            "order_id": order_id,
            "amount_minor": amount,
            "target_user_id": target_user_id,
            "merchant_id": str(self.merchant_id),
            "requested_user_id": str(self.user_id),
            "requested_role": "admin",
            "reason": reason,
        }

    def insert_approval(
        self,
        *,
        offset: int,
        session_id: int,
        thread_id: str,
        payload: dict[str, Any],
        status: str = "APPROVED",
    ) -> tuple[int, str]:
        approval_id = self.id_base + 10_000 + offset
        digest = sign_payload(payload, self.hitl_secret)
        self.approval_ids.append(approval_id)
        action_payload = canonical_payload_json(payload)
        order_hash = hashlib.sha256(
            str(payload["order_id"]).encode("utf-8")
        ).hexdigest()
        approved_columns = ""
        approved_values = ""
        if status == "APPROVED":
            approved_columns = ", approver_id, approved_at"
            approved_values = f", {self.user_id}, NOW()"
        self.sql(
            f"""
            INSERT INTO hitl_approval
                (id, session_id, thread_id, checkpoint_id, action_type,
                 action_payload, payload_version, payload_digest,
                 order_target_hash, merchant_id, requested_user_id,
                 requested_role, agent_reason, status, expire_at
                 , created_at, updated_at
                 {approved_columns})
            VALUES
                ({approval_id}, {session_id}, {sql_literal(thread_id)},
                 {sql_literal('checkpoint-' + thread_id)},
                 {sql_literal(payload['tool_name'])}, {sql_literal(action_payload)},
                 1, {sql_literal(digest)}, {sql_literal(order_hash)},
                 {self.merchant_id}, {self.user_id}, 'admin',
                 'HITL security smoke', {sql_literal(status)},
                 NOW() + INTERVAL 1 HOUR, NOW(), NOW() {approved_values});
            """
        )
        return approval_id, digest

    @staticmethod
    def tool_arguments(
        payload: dict[str, Any], approval_id: int, digest: str
    ) -> dict[str, Any]:
        if payload["tool_name"] == "execute_refund":
            arguments = {
                "order_id": payload["order_id"],
                "amount": payload["amount_minor"],
                "reason": payload["reason"],
            }
        else:
            arguments = {
                "user_id": payload["target_user_id"],
                "order_id": payload["order_id"],
                "compensation_amount": payload["amount_minor"],
                "reason": payload["reason"],
            }
        arguments.update(
            {"approval_id": str(approval_id), "approval_digest": digest}
        )
        return arguments

    def wait_for_audits(self, session_id: int, minimum: int = 1) -> int:
        deadline = time.monotonic() + 15
        count = 0
        while time.monotonic() < deadline:
            count = int(
                self.scalar(
                    f"SELECT COUNT(*) FROM tool_audit_log "
                    f"WHERE session_id = {session_id};"
                )
                or 0
            )
            if count >= minimum:
                return count
            time.sleep(0.25)
        return count

    def assert_approval_executed(self, approval_id: int) -> str:
        row = self.scalar(
            f"SELECT CONCAT(status, ':', COALESCE(execution_id, '')) "
            f"FROM hitl_approval WHERE id = {approval_id};"
        )
        if not row.startswith("EXECUTED:") or not row.split(":", 1)[1]:
            raise SmokeFailure(f"approval {approval_id} was not executed: {row}")
        return row.split(":", 1)[1]

    def ledger_count(self, approval_id: int) -> int:
        return int(
            self.scalar(
                "SELECT COUNT(*) FROM side_effect_ledger "
                f"WHERE approval_id = {sql_literal(str(approval_id))};"
            )
            or 0
        )

    def scenario_rejected(self) -> None:
        thread = f"{self.run_token}-rejected"
        session = self.create_session(1, thread)
        payload = self.payload(
            tool_name="execute_refund",
            order_id="REJECTED-NO-EXECUTION",
            amount=100,
            reason="operator rejected smoke request",
        )
        approval_id, _ = self.insert_approval(
            offset=1,
            session_id=session,
            thread_id=thread,
            payload=payload,
            status="PENDING",
        )
        self.http_json(
            "POST",
            f"{self.agent_url}/hitl/{approval_id}/reject",
            body={"comment": "security smoke rejection"},
            headers={"X-User-Id": str(self.user_id), "X-User-Role": "admin"},
        )
        status = self.scalar(
            f"SELECT status FROM hitl_approval WHERE id = {approval_id};"
        )
        audits = self.wait_for_audits(session, minimum=0)
        if status != "REJECTED" or audits != 0 or self.ledger_count(approval_id) != 0:
            raise SmokeFailure("rejected approval produced execution evidence")
        self.evidence["scenarios"]["rejected"] = {
            "approval_id": approval_id,
            "session_id": session,
            "thread_id": thread,
            "approval_status": status,
            "high_risk_audit_count": audits,
            "ledger_count": 0,
        }

    def execute_approved(
        self,
        *,
        name: str,
        offset: int,
        payload: dict[str, Any],
    ) -> tuple[int, int, str, dict[str, Any]]:
        thread = f"{self.run_token}-{name}"
        session = self.create_session(offset, thread)
        approval_id, digest = self.insert_approval(
            offset=offset,
            session_id=session,
            thread_id=thread,
            payload=payload,
        )
        response = self.call_mcp(
            session_id=session,
            thread_id=thread,
            tool_name=payload["tool_name"],
            arguments=self.tool_arguments(payload, approval_id, digest),
        )
        if response.get("error"):
            raise SmokeFailure(f"approved {name} returned MCP error: {response['error']}")
        execution_id = self.assert_approval_executed(approval_id)
        audits = self.wait_for_audits(session)
        ledger = self.ledger_count(approval_id)
        if audits < 1 or ledger != 1:
            raise SmokeFailure(
                f"approved {name} evidence mismatch: audits={audits}, ledger={ledger}"
            )
        audit_row = self.scalar(
            f"SELECT CONCAT(id, ':', COALESCE(trace_id, '')) FROM tool_audit_log "
            f"WHERE session_id = {session} ORDER BY created_at DESC LIMIT 1;"
        )
        audit_id, audit_trace_id = audit_row.split(":", 1)
        runtime_trace_id = self.scalar(
            f"SELECT COALESCE(trace_id, '') FROM agent_run "
            f"WHERE session_id = {session} ORDER BY created_at DESC LIMIT 1;"
        )
        ledger_id = self.scalar(
            "SELECT id FROM side_effect_ledger "
            f"WHERE approval_id = {sql_literal(str(approval_id))} LIMIT 1;"
        )
        audit_input = self.scalar(
            "SELECT CAST(tool_input AS CHAR) FROM tool_audit_log "
            f"WHERE session_id = {session} ORDER BY created_at DESC LIMIT 1;"
        )
        parsed_audit_input = json.loads(audit_input)
        if (
            not audit_id
            or not ledger_id
            or audit_trace_id != runtime_trace_id
            or parsed_audit_input.get("approval_digest") != "[REDACTED]"
            or digest in audit_input
        ):
            raise SmokeFailure(
                f"approved {name} correlation mismatch"
            )
        self.evidence["scenarios"][name] = {
            "approval_id": approval_id,
            "session_id": session,
            "thread_id": thread,
            "execution_id": execution_id,
            "tool_audit_id": audit_id,
            "ledger_id": ledger_id,
            "approval_status": "EXECUTED",
            "tool_audit_count": audits,
            "ledger_count": ledger,
            "trace_id": runtime_trace_id,
            "audit_credentials_redacted": True,
        }
        return approval_id, session, thread, response

    def scenario_refund(self) -> tuple[int, int, str]:
        order_no, _ = self.create_order(10)
        payload = self.payload(
            tool_name="execute_refund",
            order_id=order_no,
            amount=2000,
            reason="approved refund smoke",
        )
        approval_id, session, thread, _ = self.execute_approved(
            name="refund", offset=10, payload=payload
        )
        order_status = ""
        for candidate_order, _, shard in self.orders:
            if candidate_order == order_no:
                order_status = self.scalar(
                    f"SELECT order_status FROM order_info_{shard} "
                    f"WHERE order_no = {sql_literal(order_no)};"
                )
                break
        if order_status != "CANCELLED":
            raise SmokeFailure(f"refund order status is {order_status}")
        self.evidence["scenarios"]["refund"]["order_status"] = order_status
        return approval_id, session, thread

    def scenario_compensation(self) -> None:
        payload = self.payload(
            tool_name="issue_compensation_coupon",
            order_id=f"COMP-{self.run_token[-10:]}",
            amount=1200,
            target_user_id=str(self.user_id + 99),
            reason="approved compensation smoke",
        )
        self.execute_approved(name="compensation", offset=20, payload=payload)

    def scenario_restart_replay(
        self, approval_id: int, session_id: int, thread_id: str
    ) -> None:
        result = subprocess.run(
            ["docker", "restart", self.agent_container],
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise SmokeFailure(f"Agent restart failed: {result.stderr.strip()}")
        self.wait_health(f"{self.agent_url}/health")
        _, _, raw = self.http_json(
            "POST",
            f"{self.agent_url}/chat/resume",
            body={"approval_id": str(approval_id), "approved": True},
            headers={"X-User-Id": str(self.user_id), "X-User-Role": "admin"},
        )
        if "hitl_result_replayed" not in raw:
            raise SmokeFailure("Agent restart did not replay the executed HITL result")
        if self.ledger_count(approval_id) != 1:
            raise SmokeFailure("Agent replay changed the Server side-effect count")
        self.evidence["scenarios"]["agent_restart_replay"] = {
            "approval_id": approval_id,
            "session_id": session_id,
            "thread_id": thread_id,
            "result_replayed": True,
            "ledger_count": 1,
        }

    def scenario_concurrent_calls(self) -> None:
        order_no, _ = self.create_order(30)
        payload = self.payload(
            tool_name="execute_refund",
            order_id=order_no,
            amount=1500,
            reason="concurrent execution smoke",
        )
        thread = f"{self.run_token}-concurrent"
        session = self.create_session(30, thread)
        approval_id, digest = self.insert_approval(
            offset=30,
            session_id=session,
            thread_id=thread,
            payload=payload,
        )
        arguments = self.tool_arguments(payload, approval_id, digest)
        with ThreadPoolExecutor(max_workers=2) as executor:
            responses = list(
                executor.map(
                    lambda _: self.call_mcp(
                        session_id=session,
                        thread_id=thread,
                        tool_name=payload["tool_name"],
                        arguments=arguments,
                    ),
                    range(2),
                )
            )
        execution_id = self.assert_approval_executed(approval_id)
        ledger = self.ledger_count(approval_id)
        if ledger != 1:
            raise SmokeFailure(f"concurrent calls produced {ledger} ledgers")
        error_count = sum(bool(response.get("error")) for response in responses)
        self.evidence["scenarios"]["concurrent_calls"] = {
            "approval_id": approval_id,
            "session_id": session,
            "thread_id": thread,
            "execution_id": execution_id,
            "request_count": 2,
            "mcp_error_count": error_count,
            "ledger_count": ledger,
        }

    def scenario_tampered_checkpoint(self) -> None:
        order_no, _ = self.create_order(40)
        order_shard = next(
            shard for candidate, _, shard in self.orders if candidate == order_no
        )
        thread = f"{self.run_token}-tampered"
        session = self.create_session(40, thread)
        container_script = r'''
import asyncio
import os
from copy import deepcopy

from langchain_core.messages import AIMessage, HumanMessage
from sqlalchemy import text

from agent.graph import agent_graph
from agent.tool_router import order_target_hash
from session.checkpointer import AsyncMySQLCheckpointer
from session.manager import AsyncSessionLocal


async def main():
    thread_id = os.environ["SMOKE_THREAD_ID"]
    order_id = os.environ["SMOKE_ORDER_ID"]
    amount_minor = 100
    tool_args = {
        "order_id": order_id,
        "amount": amount_minor,
        "reason": "tampered checkpoint smoke",
    }
    state = {
        "messages": [
            HumanMessage(content=f"给订单 {order_id} 退款 1 元"),
            AIMessage(
                content="",
                tool_calls=[{
                    "name": "execute_refund",
                    "args": tool_args,
                    "id": "hitl-smoke-call",
                    "type": "tool_call",
                }],
            ),
        ],
        "step_count": 1,
        "token_count": 0,
        "tool_call_count": 0,
        "tool_call_counts": {},
        "tool_signature_counts": {},
        "session_id": int(os.environ["SMOKE_SESSION_ID"]),
        "thread_id": thread_id,
        "user_id": int(os.environ["SMOKE_USER_ID"]),
        "user_role": "admin",
        "merchant_id": int(os.environ["SMOKE_MERCHANT_ID"]),
        "needs_reflection": False,
        "last_tool_failed": False,
        "last_tool_error": None,
        "conversation_summary": None,
        "compact_failures": 0,
        "pending_hitl": False,
        "pending_action": None,
        "final_answer": None,
        "stop_reason": None,
        "tool_budget_exhausted": False,
        "tool_budget_reason": None,
        "policy_denied_tool": None,
        "route_task_type": "refund_action",
        "route_mode": "controlled",
        "route_confidence": 100,
        "route_required_tools": ["query_order", "execute_refund"],
        "route_authorized_tools": ["query_order", "execute_refund"],
        "route_next_tool": "execute_refund",
        "route_missing_fields": [],
        "route_target_order_hash": order_target_hash(order_id),
        "route_requested_amount_minor": amount_minor,
        "required_evidence": ["query_order", "execute_refund"],
        "evidence_collected": {
            "query_order": {
                "status": "success",
                "attempts": 1,
                "facts": {"found": True, "order_status": "PAID"},
            }
        },
        "evidence_complete": False,
        "evidence_stop_reason": None,
        "synthesis_only": False,
    }
    config = {"configurable": {"thread_id": thread_id}}
    config = await agent_graph.aupdate_state(config, state, as_node="llm_node")
    await agent_graph.ainvoke(None, config=config)

    checkpointer = AsyncMySQLCheckpointer()
    saved = await checkpointer.aget_tuple(
        {"configurable": {"thread_id": thread_id}}
    )
    if saved is None:
        raise RuntimeError("real HITL checkpoint was not persisted")
    pending_action = saved.checkpoint["channel_values"]["pending_action"]
    approval_id = pending_action["approval_id"]
    tampered = deepcopy(saved.checkpoint)
    protected = tampered["channel_values"]["pending_action"]["approval_payload"]
    protected["amount_minor"] += 1
    serialized = checkpointer.serde.dumps(tampered)
    if isinstance(serialized, bytes):
        serialized = serialized.decode("utf-8")
    checkpoint_id = saved.config["configurable"]["checkpoint_id"]
    async with AsyncSessionLocal() as db:
        result = await db.execute(
            text("""
                UPDATE langgraph_checkpoint
                SET state = :state
                WHERE thread_id = :thread_id AND checkpoint_id = :checkpoint_id
            """),
            {
                "state": serialized,
                "thread_id": thread_id,
                "checkpoint_id": checkpoint_id,
            },
        )
        if result.rowcount != 1:
            raise RuntimeError("real HITL checkpoint update did not affect one row")
        await db.commit()
    print(f"SMOKE_APPROVAL_ID={approval_id}")
    print(f"SMOKE_CHECKPOINT_ID={checkpoint_id}")


asyncio.run(main())
'''
        command = [
            "docker",
            "exec",
            "-i",
            "-e",
            f"SMOKE_SESSION_ID={session}",
            "-e",
            f"SMOKE_THREAD_ID={thread}",
            "-e",
            f"SMOKE_ORDER_ID={order_no}",
            "-e",
            f"SMOKE_USER_ID={self.user_id}",
            "-e",
            f"SMOKE_MERCHANT_ID={self.merchant_id}",
            self.agent_container,
            "python",
            "-",
        ]
        result = subprocess.run(
            command,
            input=container_script,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode != 0:
            raise SmokeFailure(
                f"tampered checkpoint fixture failed: {result.stderr.strip()}"
            )
        markers = {
            key: value
            for line in result.stdout.splitlines()
            if line.startswith("SMOKE_") and "=" in line
            for key, value in [line.split("=", 1)]
        }
        if "SMOKE_APPROVAL_ID" not in markers or "SMOKE_CHECKPOINT_ID" not in markers:
            raise SmokeFailure("tampered checkpoint fixture returned incomplete IDs")
        approval_id = int(markers["SMOKE_APPROVAL_ID"])
        checkpoint = markers["SMOKE_CHECKPOINT_ID"]
        self.approval_ids.append(approval_id)
        _, parsed, _ = self.http_json(
            "POST",
            f"{self.agent_url}/chat/resume",
            body={"approval_id": str(approval_id), "approved": True},
            headers={"X-User-Id": str(self.user_id), "X-User-Role": "admin"},
            expected_status=409,
        )
        error_code = (
            parsed.get("detail", {}).get("code") if isinstance(parsed, dict) else None
        )
        approval_state = self.scalar(
            "SELECT CONCAT_WS(':', status, "
            "IF(execution_id IS NULL, 'no_execution', 'has_execution'), "
            "IF(execution_lease_until IS NULL, 'no_lease', 'has_lease'), "
            "IF(executed_at IS NULL, 'not_executed', 'executed')) "
            f"FROM hitl_approval WHERE id = {approval_id};"
        )
        audit_count = self.wait_for_audits(session, minimum=0)
        ledger_count = self.ledger_count(approval_id)
        order_status = self.scalar(
            f"SELECT order_status FROM order_info_{order_shard} "
            f"WHERE order_no = {sql_literal(order_no)};"
        )
        run_status = self.scalar(
            f"SELECT status FROM agent_run WHERE thread_id = {sql_literal(thread)} "
            "ORDER BY created_at DESC LIMIT 1;"
        )
        logs = ""
        for container in (self.agent_container, "local-life-copilot", "local-life-server"):
            collected = subprocess.run(
                ["docker", "logs", "--tail", "1000", container],
                text=True,
                capture_output=True,
                check=False,
            )
            logs += collected.stdout + collected.stderr
        if (
            error_code != "payload_mismatch"
            or approval_state != "PENDING:no_execution:no_lease:not_executed"
            or audit_count != 0
            or ledger_count != 0
            or order_status != "PAID"
            or run_status == "COMPLETED"
            or order_no in logs
        ):
            raise SmokeFailure(
                "tampered checkpoint was not rejected before execution"
            )
        self.evidence["scenarios"]["tampered_checkpoint"] = {
            "approval_id": approval_id,
            "session_id": session,
            "thread_id": thread,
            "checkpoint_id": checkpoint,
            "http_status": 409,
            "error_code": error_code,
            "approval_state": approval_state,
            "high_risk_audit_count": audit_count,
            "ledger_count": ledger_count,
            "order_status": order_status,
            "run_status": run_status,
            "protected_value_present_in_logs": False,
        }

    def scenario_ambiguous_timeout_retry(self) -> None:
        order_no, _ = self.create_order(50)
        payload = self.payload(
            tool_name="execute_refund",
            order_id=order_no,
            amount=1800,
            reason="ambiguous transport outcome smoke",
        )
        thread = f"{self.run_token}-timeout"
        session = self.create_session(50, thread)
        approval_id, digest = self.insert_approval(
            offset=50,
            session_id=session,
            thread_id=thread,
            payload=payload,
        )
        stale_execution_id = f"stale-{self.run_token[-16:]}"
        self.sql(
            "UPDATE hitl_approval SET status = 'EXECUTING', "
            f"execution_id = {sql_literal(stale_execution_id)}, "
            "executing_at = UTC_TIMESTAMP() - INTERVAL 3 MINUTE, "
            "execution_lease_until = UTC_TIMESTAMP() - INTERVAL 1 SECOND "
            f"WHERE id = {approval_id} AND status = 'APPROVED';"
        )
        stale_state = self.scalar(
            "SELECT CONCAT(status, ':', execution_id, ':', "
            "IF(execution_lease_until < UTC_TIMESTAMP(), 'expired', 'active')) "
            f"FROM hitl_approval WHERE id = {approval_id};"
        )
        if stale_state != f"EXECUTING:{stale_execution_id}:expired":
            raise SmokeFailure(
                f"failed to prepare expired execution lease: {stale_state}"
            )
        _, first_result, _ = self.http_json(
            "POST",
            f"{self.server_url}/internal/orders/{order_no}/refund",
            body={
                "amount": payload["amount_minor"],
                "approvalId": str(approval_id),
                "reason": payload["reason"],
            },
            headers={"X-Internal-Key": self.internal_key},
        )
        response = self.call_mcp(
            session_id=session,
            thread_id=thread,
            tool_name=payload["tool_name"],
            arguments=self.tool_arguments(payload, approval_id, digest),
        )
        if response.get("error"):
            raise SmokeFailure(f"timeout retry returned MCP error: {response['error']}")
        recovered_execution_id = self.assert_approval_executed(approval_id)
        if recovered_execution_id == stale_execution_id:
            raise SmokeFailure("expired execution lease was not replaced")
        ledger = self.ledger_count(approval_id)
        if ledger != 1:
            raise SmokeFailure(f"timeout retry produced {ledger} ledger rows")
        self.evidence["scenarios"]["ambiguous_timeout_retry"] = {
            "approval_id": approval_id,
            "session_id": session,
            "thread_id": thread,
            "server_committed_before_retry": bool(first_result),
            "lease_recovered": True,
            "stale_execution_replaced": True,
            "copilot_retry_completed": True,
            "ledger_count": ledger,
        }

    def verify_migration_and_logs(self) -> None:
        migration = self.scalar(
            "SELECT COUNT(*) FROM schema_migrations "
            "WHERE version = 'copilot:V104';"
        )
        column_count = self.scalar(
            "SELECT COUNT(*) FROM information_schema.columns "
            "WHERE table_schema = DATABASE() AND table_name = 'hitl_approval' "
            "AND column_name IN ("
            "'payload_version','payload_digest','order_target_hash','merchant_id',"
            "'requested_user_id','requested_role','execution_id',"
            "'execution_lease_until','executing_at','executed_at',"
            "'execution_result','execution_error');"
        )
        checkpoint_nullable = self.scalar(
            "SELECT is_nullable FROM information_schema.columns "
            "WHERE table_schema = DATABASE() AND table_name = 'hitl_approval' "
            "AND column_name = 'checkpoint_id';"
        )
        index_count = self.scalar(
            "SELECT COUNT(DISTINCT index_name) FROM information_schema.statistics "
            "WHERE table_schema = DATABASE() AND table_name = 'hitl_approval' "
            "AND index_name IN ('idx_hitl_status_lease','idx_hitl_payload_digest');"
        )
        if (
            migration != "1"
            or column_count != "12"
            or checkpoint_nullable != "YES"
            or index_count != "2"
        ):
            raise SmokeFailure(
                "Copilot V104 migration contract is incomplete: "
                f"record={migration}, columns={column_count}, "
                f"checkpoint_nullable={checkpoint_nullable}, indexes={index_count}"
            )
        logs = subprocess.run(
            ["docker", "logs", self.agent_container, "--tail", "500"],
            text=True,
            capture_output=True,
            check=False,
        )
        copilot_logs = subprocess.run(
            ["docker", "logs", "local-life-copilot", "--tail", "500"],
            text=True,
            capture_output=True,
            check=False,
        )
        combined = logs.stdout + logs.stderr + copilot_logs.stdout + copilot_logs.stderr
        if self.hitl_secret in combined:
            raise SmokeFailure("HITL signing secret appeared in container logs")
        self.evidence["migration"] = {
            "version": "copilot:V104",
            "recorded": True,
            "contract_columns": int(column_count),
            "contract_indexes": int(index_count),
            "checkpoint_nullable": checkpoint_nullable == "YES",
        }
        self.evidence["credential_leak_detected"] = False

    def cleanup(self) -> None:
        statements: list[str] = []
        if self.session_ids:
            sessions = ",".join(str(value) for value in self.session_ids)
            statements.extend(
                [
                    f"DELETE FROM tool_audit_log WHERE session_id IN ({sessions})",
                    f"DELETE FROM agent_event WHERE session_id IN ({sessions})",
                    f"DELETE FROM agent_run WHERE session_id IN ({sessions})",
                ]
            )
        if self.approval_ids:
            approval_strings = ",".join(
                sql_literal(str(value)) for value in self.approval_ids
            )
            approval_numbers = ",".join(str(value) for value in self.approval_ids)
            statements.extend(
                [
                    "DELETE FROM side_effect_ledger "
                    f"WHERE approval_id IN ({approval_strings})",
                    f"DELETE FROM hitl_approval WHERE id IN ({approval_numbers})",
                ]
            )
        if self.thread_ids:
            threads = ",".join(sql_literal(value) for value in self.thread_ids)
            statements.extend(
                [
                    f"DELETE FROM langgraph_checkpoint_write WHERE thread_id IN ({threads})",
                    f"DELETE FROM langgraph_checkpoint WHERE thread_id IN ({threads})",
                ]
            )
        for order_no, _, shard in self.orders:
            statements.append(
                f"DELETE FROM order_info_{shard} WHERE order_no = {sql_literal(order_no)}"
            )
        if self.session_ids:
            sessions = ",".join(str(value) for value in self.session_ids)
            statements.append(f"DELETE FROM agent_session WHERE id IN ({sessions})")
        if statements:
            self.sql(";\n".join(statements) + ";")

    def run(self) -> dict[str, Any]:
        self.wait_health(f"{self.server_url}/actuator/health")
        self.wait_health(f"{self.copilot_url}/actuator/health")
        self.wait_health(f"{self.agent_url}/health")
        self.verify_migration_and_logs()
        self.scenario_rejected()
        refund_approval, refund_session, refund_thread = self.scenario_refund()
        self.scenario_compensation()
        self.scenario_restart_replay(
            refund_approval, refund_session, refund_thread
        )
        self.scenario_concurrent_calls()
        self.scenario_tampered_checkpoint()
        self.scenario_ambiguous_timeout_retry()
        self.evidence["status"] = "PASS"
        self.evidence["completed_at_epoch_ms"] = int(time.time() * 1000)
        return self.evidence


def main() -> int:
    timestamp = time.strftime("%Y%m%d-%H%M%S")
    report_path = Path("artifacts/security") / f"hitl-{timestamp}" / "report.json"
    runner: HitlSecuritySmoke | None = None
    evidence: dict[str, Any]
    exit_code = 0
    try:
        runner = HitlSecuritySmoke()
        evidence = runner.run()
    except Exception as error:
        exit_code = 1
        evidence = runner.evidence if runner is not None else {}
        evidence["status"] = "FAIL"
        evidence["error_type"] = type(error).__name__
        evidence["error"] = str(error)
    finally:
        if runner is not None:
            try:
                runner.cleanup()
                evidence["isolated_data_cleaned"] = True
            except Exception as cleanup_error:
                exit_code = 1
                evidence["isolated_data_cleaned"] = False
                evidence["cleanup_error"] = str(cleanup_error)
        write_sanitized_report(report_path, evidence)

    print(f"HITL security smoke: {evidence.get('status', 'FAIL')}")
    print(f"Report: {report_path}")
    if exit_code:
        print(f"Error: {evidence.get('error', evidence.get('cleanup_error', 'unknown'))}")
    return exit_code


if __name__ == "__main__":
    sys.exit(main())
