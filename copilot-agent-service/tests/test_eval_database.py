from evals.eval_database import (
    FIXTURE_QUERIES,
    _evidence_from_audits,
    _evidence_from_messages,
    _fixture_values_from_rows,
    _merge_evidence,
)
from evals.eval_scoring import ToolEvidence


def test_fixture_rows_build_complete_catalog_and_negative_fixture():
    catalog = _fixture_values_from_rows(
        {
            "merchant_actor": {
                "user_id": 880000000001,
                "merchant_id": 880000100001,
            },
            "cs_actor": {"user_id": 9000000001},
            "admin_actor": {"user_id": 9000000002},
            "paid_order": {"order_no": "202606100001"},
            "payment_mismatch_order": {"order_no": "202606100002"},
            "coupon_issue_order": {"order_no": "202606100003"},
            "failed_payment_order": {"order_no": "BULK2026061000009999"},
            "missing_order_count": {"count": 0},
        }
    )

    assert catalog.get("actor.merchant.merchant_id") == 880000100001
    assert catalog.get("order.failed_payment.order_no") == "BULK2026061000009999"
    assert catalog.get("order.missing.order_no") == "EVAL_ORDER_DOES_NOT_EXIST"


def test_agent_messages_reconstruct_tool_arguments_and_results():
    evidence = _evidence_from_messages(
        [
            {
                "tool_calls": [
                    {
                        "id": "call-1",
                        "name": "query_order",
                        "args": {"order_id": "202606100001"},
                    }
                ],
                "tool_results": None,
            },
            {
                "tool_calls": None,
                "tool_results": [
                    {
                        "call_id": "call-1",
                        "name": "query_order",
                        "content": '{"order_status":"PAID"}',
                    }
                ],
            },
        ]
    )

    assert len(evidence) == 1
    assert evidence[0].arguments == {"order_id": "202606100001"}
    assert evidence[0].output == {"order_status": "PAID"}
    assert evidence[0].status == "success"


def test_mcp_audit_reconstructs_fast_path_evidence():
    evidence = _evidence_from_audits(
        [
            {
                "tool_name": "shop_metrics_query",
                "tool_input": '{"date":"yesterday"}',
                "tool_output": '{"gmv":12800,"order_count":1}',
                "status": "success",
                "error_msg": None,
            }
        ]
    )

    assert evidence[0].name == "shop_metrics_query"
    assert evidence[0].arguments == {"date": "yesterday"}
    assert evidence[0].output["gmv"] == 12800


def test_failed_payment_fixture_is_pinned_to_synthetic_seed():
    query = FIXTURE_QUERIES["failed_payment_order"]

    assert "p.id = 881700000095" in query
    assert "ORDER BY o.id DESC" not in query


def test_duplicate_audits_replace_duplicate_calls_in_order():
    messages = [
        ToolEvidence("query_order", {"order_id": "1"}, {"attempt": 1}, "success"),
        ToolEvidence("query_order", {"order_id": "1"}, {"attempt": 2}, "success"),
    ]
    audits = [
        ToolEvidence("query_order", {"order_id": "1"}, None, "error", "first failed"),
        ToolEvidence("query_order", {"order_id": "1"}, {"attempt": 2}, "success"),
    ]

    merged = _merge_evidence(messages, audits)

    assert [item.status for item in merged] == ["error", "success"]
    assert merged[0].error == "first failed"
    assert merged[1].output == {"attempt": 2}


def test_audit_merge_preserves_stable_message_error_reason():
    messages = [
        ToolEvidence(
            "query_order",
            {"order_id": "missing"},
            None,
            "error",
            '[工具错误] {"reason":"not_found"}',
        )
    ]
    audits = [
        ToolEvidence(
            "query_order",
            {"order_id": "missing"},
            None,
            "error",
            "garbled database error",
        )
    ]

    merged = _merge_evidence(messages, audits)

    assert "not_found" in merged[0].error
    assert "garbled database error" in merged[0].error
