#!/usr/bin/env python3
"""Verify real compensation-coupon journeys against Docker Compose Lite."""

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
from typing import Any


PAYLOAD_FIELDS_V2 = (
    "payload_version", "tool_name", "order_id", "amount_minor",
    "target_user_id", "shop_id", "merchant_id", "coupon_template_id",
    "coupon_discount_type", "coupon_min_order_amount", "coupon_valid_days",
    "coupon_terms_digest", "requested_user_id", "requested_role", "reason",
)


class SmokeFailure(RuntimeError):
    pass


def sql_literal(value: Any) -> str:
    if value is None:
        return "NULL"
    if isinstance(value, int):
        return str(value)
    return "'" + str(value).replace("\\", "\\\\").replace("'", "''") + "'"


class CompensationSmoke:
    def __init__(self) -> None:
        self.hitl_secret = self.required_env("HITL_PAYLOAD_SIGNING_SECRET")
        self.mysql_password = os.getenv("MYSQL_ROOT_PASSWORD", "123456")
        self.mysql_database = os.getenv("MYSQL_DATABASE", "local_life")
        self.mysql_container = os.getenv("MYSQL_CONTAINER", "local-life-mysql")
        self.mcp_secret = os.getenv(
            "MCP_CONTEXT_SIGNING_SECRET", "local-life-mcp-context-secret"
        )
        self.internal_key = os.getenv(
            "INTERNAL_API_KEY", "local-life-internal-secret"
        )
        self.copilot_url = os.getenv("COPILOT_URL", "http://localhost:8081")
        self.server_url = os.getenv("SERVER_URL", "http://localhost:8080")
        suffix = int(time.time() * 1000) % 10_000_000
        self.run_id = f"comp-smoke-{suffix}"
        self.base = 7_810_000_000_000_000_000 + suffix * 100
        self.operator_id = self.base + 1
        self.user_id = self.base + 2
        self.merchant_id = self.base + 3
        self.shop_id = self.base + 4
        self.template_id = self.base + 5
        self.binding_id = self.base + 6
        self.order_id = self.base + 7
        self.order_no = str(self.base + 8)
        self.order_shard = self.user_id % 4
        self.amount = 2000
        self.sessions: list[int] = []
        self.approvals: list[int] = []
        self.results: dict[str, dict[str, Any]] = {}

    @staticmethod
    def required_env(name: str) -> str:
        value = os.getenv(name, "").strip()
        if not value:
            raise SmokeFailure(f"required environment variable {name} is missing")
        return value

    def sql(self, statement: str) -> str:
        result = subprocess.run(
            [
                "docker", "exec", "-i", "-e", f"MYSQL_PWD={self.mysql_password}",
                self.mysql_container, "mysql", "-uroot", "--batch",
                "--skip-column-names", self.mysql_database,
            ],
            input=statement,
            text=True,
            capture_output=True,
            check=False,
        )
        if result.returncode:
            raise SmokeFailure(f"mysql failed: {result.stderr.strip()}")
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
    ) -> dict[str, Any]:
        encoded = None
        request_headers = {"Accept": "application/json", **(headers or {})}
        if body is not None:
            encoded = json.dumps(body, ensure_ascii=False).encode()
            request_headers["Content-Type"] = "application/json"
        request = urllib.request.Request(
            url, data=encoded, headers=request_headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=20) as response:
                status = response.status
                raw = response.read().decode()
        except urllib.error.HTTPError as error:
            status = error.code
            raw = error.read().decode()
        if status != expected_status:
            raise SmokeFailure(
                f"{method} {url} returned {status}, expected {expected_status}: {raw[:300]}"
            )
        return json.loads(raw) if raw else {}

    def wait_health(self, url: str) -> None:
        deadline = time.monotonic() + 180
        last_error = "not attempted"
        while time.monotonic() < deadline:
            try:
                self.http_json("GET", url)
                return
            except Exception as error:
                last_error = str(error)
                time.sleep(2)
        raise SmokeFailure(f"health check failed for {url}: {last_error}")

    def identity_headers(self, session_id: int, thread_id: str) -> dict[str, str]:
        timestamp = str(int(time.time()))
        canonical = f"{self.operator_id}\nadmin\n{self.merchant_id}\n{timestamp}"
        signature = hmac.new(
            self.mcp_secret.encode(), canonical.encode(), hashlib.sha256
        ).hexdigest()
        return {
            "X-User-Id": str(self.operator_id),
            "X-User-Role": "admin",
            "X-Merchant-Id": str(self.merchant_id),
            "X-Agent-Timestamp": timestamp,
            "X-Agent-Signature": signature,
            "X-Session-Id": str(session_id),
            "X-Thread-Id": thread_id,
            "X-Trace-Id": hashlib.sha256(thread_id.encode()).hexdigest()[:32],
        }

    def call_mcp(
        self, session_id: int, thread_id: str, tool: str, args: dict[str, Any]
    ) -> dict[str, Any]:
        return self.http_json(
            "POST",
            f"{self.copilot_url}/mcp",
            body={
                "jsonrpc": "2.0",
                "id": f"{thread_id}-{tool}",
                "method": "tools/call",
                "params": {"name": tool, "arguments": args},
            },
            headers=self.identity_headers(session_id, thread_id),
        )

    @staticmethod
    def mcp_result(response: dict[str, Any]) -> dict[str, Any]:
        if response.get("error"):
            raise SmokeFailure(f"unexpected MCP error: {response['error']}")
        try:
            return json.loads(response["result"]["content"][0]["text"])
        except (KeyError, IndexError, TypeError, json.JSONDecodeError) as error:
            raise SmokeFailure(f"invalid MCP result: {response}") from error

    def seed(self) -> None:
        required = int(self.scalar(
            "SELECT COUNT(*) FROM information_schema.columns "
            "WHERE table_schema=DATABASE() AND table_name='user_coupon' "
            "AND column_name IN ('source_type','source_approval_id','issuance_key');"
        ) or 0)
        if required != 3:
            raise SmokeFailure("V14 is not applied; keep coupon writers stopped and migrate first")

        mobile_a = f"17{str(self.operator_id)[-9:]}"
        mobile_b = f"16{str(self.user_id)[-9:]}"
        self.sql(f"""
            INSERT INTO `user` (id,mobile,nickname,avatar,bio,status,deleted) VALUES
              ({self.operator_id},{sql_literal(mobile_a)},'comp_smoke_admin','','','ENABLED',0),
              ({self.user_id},{sql_literal(mobile_b)},'comp_smoke_user','','','ENABLED',0);
            INSERT INTO merchant
              (id,user_id,merchant_name,logo,description,contact_mobile,status,deleted)
            VALUES ({self.merchant_id},{self.operator_id},'comp smoke merchant','','',
                    {sql_literal(mobile_a)},'APPROVED',0);
            INSERT INTO shop
              (id,merchant_id,shop_name,category_id,cover_image,description,address,
               longitude,latitude,phone,business_hours,score,status,deleted)
            VALUES ({self.shop_id},{self.merchant_id},'comp smoke shop',1,'','',
                    'isolated smoke address',120.0,30.0,'','',5.0,'ONLINE',0);
            INSERT INTO coupon_template
              (id,shop_id,coupon_name,discount_type,discount_value,min_order_amount,
               total_stock,remain_stock,per_user_limit,valid_days,status,deleted)
            VALUES ({self.template_id},{self.shop_id},'20 yuan compensation','CASH',
                    {self.amount},5000,20,20,1,30,'ACTIVE',0);
            INSERT INTO compensation_coupon_binding
              (id,shop_id,merchant_id,face_value_minor,coupon_template_id,enabled)
            VALUES ({self.binding_id},{self.shop_id},{self.merchant_id},{self.amount},
                    {self.template_id},1);
            INSERT INTO order_info
              (id,order_no,user_id,shop_id,original_amount,coupon_discount,order_amount,
               order_status,remark,expire_at,pay_at,deleted)
            VALUES ({self.order_id},{sql_literal(self.order_no)},{self.user_id},{self.shop_id},
                    9900,0,9900,'PAID','{self.run_id}',NOW()+INTERVAL 1 DAY,NOW(),0);
            INSERT INTO order_info_{self.order_shard}
              (id,order_no,user_id,shop_id,original_amount,coupon_discount,order_amount,
               order_status,remark,expire_at,pay_at,deleted)
            VALUES ({self.order_id},{sql_literal(self.order_no)},{self.user_id},{self.shop_id},
                    9900,0,9900,'PAID','{self.run_id}',NOW()+INTERVAL 1 DAY,NOW(),0);
        """)

    def create_session(self, offset: int) -> tuple[int, str]:
        session_id = self.base + 1000 + offset
        thread_id = f"{self.run_id}-{offset}"
        self.sessions.append(session_id)
        self.sql(f"""
            INSERT INTO agent_session
              (id,user_id,user_role,merchant_id,title,status,total_tokens,total_cost_cents,
               created_at,updated_at)
            VALUES ({session_id},{self.operator_id},'admin',{self.merchant_id},
                    {sql_literal(self.run_id)},'PENDING_APPROVAL',0,0,NOW(),NOW());
        """)
        return session_id, thread_id

    def resolve(self, session_id: int, thread_id: str) -> dict[str, Any]:
        result = self.mcp_result(self.call_mcp(
            session_id, thread_id, "resolve_compensation_coupon",
            {"order_id": self.order_no, "amount_minor": self.amount},
        ))
        expected = {
            "order_no": self.order_no,
            "target_user_id": str(self.user_id),
            "shop_id": str(self.shop_id),
            "merchant_id": str(self.merchant_id),
            "coupon_template_id": str(self.template_id),
            "coupon_discount_type": "CASH",
            "coupon_min_order_amount": 5000,
            "coupon_valid_days": 30,
        }
        if any(result.get(key) != value for key, value in expected.items()):
            raise SmokeFailure(f"resolver returned an unbound target: {result}")
        return result

    def payload(self, resolution: dict[str, Any], reason: str) -> dict[str, Any]:
        return {
            "payload_version": 2,
            "tool_name": "issue_compensation_coupon",
            "order_id": self.order_no,
            "amount_minor": self.amount,
            "target_user_id": str(self.user_id),
            "shop_id": str(self.shop_id),
            "merchant_id": str(self.merchant_id),
            "coupon_template_id": str(self.template_id),
            "coupon_discount_type": "CASH",
            "coupon_min_order_amount": 5000,
            "coupon_valid_days": 30,
            "coupon_terms_digest": resolution["coupon_terms_digest"],
            "requested_user_id": str(self.operator_id),
            "requested_role": "admin",
            "reason": reason,
        }

    def insert_approval(
        self, offset: int, session_id: int, thread_id: str, payload: dict[str, Any]
    ) -> tuple[int, str]:
        approval_id = self.base + 2000 + offset
        self.approvals.append(approval_id)
        canonical = json.dumps(
            {key: payload[key] for key in PAYLOAD_FIELDS_V2},
            ensure_ascii=False,
            separators=(",", ":"),
        )
        digest = hmac.new(
            self.hitl_secret.encode(), canonical.encode(), hashlib.sha256
        ).hexdigest()
        order_hash = hashlib.sha256(self.order_no.encode()).hexdigest()
        self.sql(f"""
            INSERT INTO hitl_approval
              (id,session_id,thread_id,checkpoint_id,action_type,action_payload,
               payload_version,payload_digest,order_target_hash,merchant_id,
               requested_user_id,requested_role,agent_reason,status,approver_id,
               approved_at,expire_at,created_at,updated_at)
            VALUES ({approval_id},{session_id},{sql_literal(thread_id)},
                    {sql_literal('checkpoint-' + thread_id)},
                    'issue_compensation_coupon',{sql_literal(canonical)},2,
                    {sql_literal(digest)},{sql_literal(order_hash)},{self.merchant_id},
                    {self.operator_id},'admin','{self.run_id}','APPROVED',
                    {self.operator_id},NOW(),NOW()+INTERVAL 1 HOUR,NOW(),NOW());
        """)
        return approval_id, digest

    @staticmethod
    def issue_args(
        payload: dict[str, Any], approval_id: int, approval_digest: str
    ) -> dict[str, Any]:
        return {
            "user_id": payload["target_user_id"],
            "order_id": payload["order_id"],
            "compensation_amount": payload["amount_minor"],
            "shop_id": payload["shop_id"],
            "merchant_id": payload["merchant_id"],
            "coupon_template_id": payload["coupon_template_id"],
            "coupon_discount_type": payload["coupon_discount_type"],
            "coupon_min_order_amount": payload["coupon_min_order_amount"],
            "coupon_valid_days": payload["coupon_valid_days"],
            "coupon_terms_digest": payload["coupon_terms_digest"],
            "reason": payload["reason"],
            "approval_id": str(approval_id),
            "approval_digest": approval_digest,
        }

    def counts(self, approval_id: int) -> tuple[int, int, int]:
        coupon_count = int(self.scalar(
            "SELECT COUNT(*) FROM user_coupon WHERE source_approval_id="
            f"{sql_literal(str(approval_id))};"
        ) or 0)
        ledger_count = int(self.scalar(
            "SELECT COUNT(*) FROM side_effect_ledger WHERE approval_id="
            f"{sql_literal(str(approval_id))};"
        ) or 0)
        stock = int(self.scalar(
            f"SELECT remain_stock FROM coupon_template WHERE id={self.template_id};"
        ))
        return coupon_count, ledger_count, stock

    def wait_audits(self, session_id: int, minimum: int) -> int:
        deadline = time.monotonic() + 10
        while time.monotonic() < deadline:
            count = int(self.scalar(
                "SELECT COUNT(*) FROM tool_audit_log WHERE session_id="
                f"{session_id} AND tool_name='issue_compensation_coupon';"
            ) or 0)
            if count >= minimum:
                return count
            time.sleep(0.2)
        return count

    def scenario_success_replay_and_concurrency(self, resolution: dict[str, Any]) -> None:
        session, thread = self.create_session(1)
        payload = self.payload(resolution, "approved real compensation smoke")
        approval, digest = self.insert_approval(1, session, thread, payload)
        args = self.issue_args(payload, approval, digest)
        before = self.counts(approval)
        with ThreadPoolExecutor(max_workers=2) as pool:
            responses = list(pool.map(
                lambda _: self.call_mcp(session, thread, "issue_compensation_coupon", args),
                range(2),
            ))
        successes = [response for response in responses if not response.get("error")]
        if len(successes) != 1:
            raise SmokeFailure(f"concurrent execution expected one winner: {responses}")
        winner = self.mcp_result(successes[0])
        after = self.counts(approval)
        replay = self.mcp_result(self.call_mcp(
            session, thread, "issue_compensation_coupon", args
        ))
        final = self.counts(approval)
        status = self.scalar(f"SELECT status FROM hitl_approval WHERE id={approval};")
        if not (
            before == (0, 0, 20)
            and after == (1, 1, 19)
            and final == after
            and replay.get("couponId") == winner.get("couponId")
            and status == "EXECUTED"
            and self.wait_audits(session, 3) >= 3
        ):
            raise SmokeFailure("success/replay/concurrent-resume invariants failed")
        self.results["success"] = {"before": before, "after": after, "coupon_id": winner["couponId"]}
        self.results["concurrent_resume"] = {"winner_count": 1, "business_effect_count": 1}
        self.results["repeat_replay"] = {"same_coupon_id": True, "after": final}

    def scenario_stock_rejection(self, resolution: dict[str, Any]) -> None:
        self.sql(f"UPDATE coupon_template SET remain_stock=0 WHERE id={self.template_id};")
        session, thread = self.create_session(2)
        payload = self.payload(resolution, "stock rejection smoke")
        approval, digest = self.insert_approval(2, session, thread, payload)
        response = self.call_mcp(
            session, thread, "issue_compensation_coupon",
            self.issue_args(payload, approval, digest),
        )
        status = self.scalar(f"SELECT status FROM hitl_approval WHERE id={approval};")
        if not response.get("error") or status != "EXECUTION_FAILED" or self.counts(approval)[:2] != (0, 0):
            raise SmokeFailure("stock rejection produced a business effect")
        self.results["stock_rejection"] = {"approval_status": status, "business_effect_count": 0}
        self.sql(f"UPDATE coupon_template SET remain_stock=19 WHERE id={self.template_id};")

    def scenario_scope_and_terms_tamper(self, resolution: dict[str, Any]) -> None:
        for offset, field, changed in (
            (3, "shop_id", str(self.shop_id + 1)),
            (4, "coupon_valid_days", 31),
        ):
            session, thread = self.create_session(offset)
            payload = self.payload(resolution, f"tamper smoke {field}")
            approval, digest = self.insert_approval(offset, session, thread, payload)
            args = self.issue_args(payload, approval, digest)
            args[field] = changed
            response = self.call_mcp(
                session, thread, "issue_compensation_coupon", args
            )
            status = self.scalar(f"SELECT status FROM hitl_approval WHERE id={approval};")
            if not response.get("error") or status != "APPROVED" or self.counts(approval)[:2] != (0, 0):
                raise SmokeFailure(f"tampered {field} was not denied before execution")
            name = "scope_mismatch" if field == "shop_id" else "payload_terms_tamper"
            self.results[name] = {"approval_status": status, "business_effect_count": 0}

    def scenario_ambiguous_response_replay(self, resolution: dict[str, Any]) -> None:
        session, thread = self.create_session(5)
        payload = self.payload(resolution, "ambiguous response replay smoke")
        approval, digest = self.insert_approval(5, session, thread, payload)
        args = self.issue_args(payload, approval, digest)
        body = {
            "userId": args["user_id"],
            "compensationAmount": args["compensation_amount"],
            "shopId": args["shop_id"],
            "merchantId": args["merchant_id"],
            "couponTemplateId": args["coupon_template_id"],
            "couponDiscountType": args["coupon_discount_type"],
            "couponMinOrderAmount": args["coupon_min_order_amount"],
            "couponValidDays": args["coupon_valid_days"],
            "couponTermsDigest": args["coupon_terms_digest"],
            "approvalId": str(approval),
            "reason": args["reason"],
        }
        server_result = self.http_json(
            "POST", f"{self.server_url}/internal/orders/{self.order_no}/compensate-coupon",
            body=body, headers={"X-Internal-Key": self.internal_key},
        )["data"]
        if self.counts(approval)[:2] != (1, 1):
            raise SmokeFailure("ambiguous setup did not commit exactly once")
        replay = self.mcp_result(self.call_mcp(
            session, thread, "issue_compensation_coupon", args
        ))
        status = self.scalar(f"SELECT status FROM hitl_approval WHERE id={approval};")
        if replay.get("couponId") != server_result.get("couponId") or status != "EXECUTED" or self.counts(approval)[:2] != (1, 1):
            raise SmokeFailure("ambiguous response retry did not replay the committed result")
        self.results["ambiguous_response_replay"] = {
            "same_coupon_id": True, "business_effect_count": 1,
        }

    def assert_audit_and_cleanup(self) -> None:
        approvals = ",".join(map(str, self.approvals))
        sessions = ",".join(map(str, self.sessions))
        leaks = int(self.scalar(
            "SELECT COUNT(*) FROM tool_audit_log WHERE session_id IN "
            f"({sessions}) AND CAST(tool_input AS CHAR) LIKE '%approval_digest%" 
            "' AND CAST(tool_input AS CHAR) NOT LIKE '%[REDACTED]%';"
        ) or 0)
        if leaks:
            raise SmokeFailure("tool audit contains an unredacted approval digest")
        self.results["audit"] = {
            "issue_rows": int(self.scalar(
                "SELECT COUNT(*) FROM tool_audit_log WHERE session_id IN "
                f"({sessions}) AND tool_name='issue_compensation_coupon';"
            ) or 0),
            "credential_leaks": 0,
        }

        self.sql(f"""
            DELETE FROM tool_audit_log WHERE session_id IN ({sessions});
            DELETE FROM side_effect_ledger WHERE approval_id IN ({','.join(sql_literal(str(value)) for value in self.approvals)});
            DELETE FROM hitl_approval WHERE id IN ({approvals});
            DELETE FROM agent_session WHERE id IN ({sessions});
            DELETE FROM user_coupon WHERE coupon_template_id={self.template_id};
            DELETE FROM compensation_coupon_binding WHERE id={self.binding_id};
            DELETE FROM coupon_template WHERE id={self.template_id};
            DELETE FROM order_info_{self.order_shard} WHERE id={self.order_id};
            DELETE FROM order_info WHERE id={self.order_id};
            DELETE FROM shop WHERE id={self.shop_id};
            DELETE FROM merchant WHERE id={self.merchant_id};
            DELETE FROM `user` WHERE id IN ({self.operator_id},{self.user_id});
        """)

    def emergency_cleanup(self) -> None:
        try:
            conditions = f"remark={sql_literal(self.run_id)}"
            self.sql(f"""
                DELETE FROM tool_audit_log WHERE session_id BETWEEN {self.base+1000} AND {self.base+1999};
                DELETE FROM side_effect_ledger WHERE resource_id={sql_literal(self.order_no)};
                DELETE FROM hitl_approval WHERE agent_reason={sql_literal(self.run_id)};
                DELETE FROM agent_session WHERE title={sql_literal(self.run_id)};
                DELETE FROM user_coupon WHERE coupon_template_id={self.template_id};
                DELETE FROM compensation_coupon_binding WHERE id={self.binding_id};
                DELETE FROM coupon_template WHERE id={self.template_id};
                DELETE FROM order_info_{self.order_shard} WHERE {conditions};
                DELETE FROM order_info WHERE {conditions};
                DELETE FROM shop WHERE id={self.shop_id};
                DELETE FROM merchant WHERE id={self.merchant_id};
                DELETE FROM `user` WHERE id IN ({self.operator_id},{self.user_id});
            """)
        except Exception:
            pass

    def run(self) -> None:
        self.wait_health(f"{self.server_url}/actuator/health")
        self.wait_health(f"{self.copilot_url}/actuator/health")
        self.seed()
        try:
            resolver_session, resolver_thread = self.create_session(0)
            resolution = self.resolve(resolver_session, resolver_thread)
            self.results["resolver"] = {"unique_binding": True, "terms_bound": True}
            self.scenario_success_replay_and_concurrency(resolution)
            self.scenario_stock_rejection(resolution)
            self.scenario_scope_and_terms_tamper(resolution)
            self.scenario_ambiguous_response_replay(resolution)
            self.assert_audit_and_cleanup()
        except Exception:
            self.emergency_cleanup()
            raise
        print(json.dumps({"status": "PASS", "scenarios": self.results}, indent=2))


if __name__ == "__main__":
    try:
        CompensationSmoke().run()
    except SmokeFailure as error:
        print(f"FAIL: {error}", file=sys.stderr)
        raise SystemExit(1)
