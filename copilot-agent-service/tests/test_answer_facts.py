from agent.answer_facts import build_evidence_answer, validate_or_fallback


def _record(**facts):
    return {"status": "success", "attempts": 1, "facts": facts}


def _state(task_type, evidence):
    return {
        "route_task_type": task_type,
        "synthesis_only": True,
        "evidence_collected": evidence,
    }


def test_payment_mismatch_renders_all_required_statuses():
    answer = build_evidence_answer(
        _state(
            "payment_diagnosis",
            {
                "query_order": _record(order_status="WAIT_PAY"),
                "query_payment": _record(payment_status="SUCCESS"),
            },
        )
    )

    assert answer is not None
    assert answer.render() == "订单状态：待支付；支付状态：支付成功。"
    assert {fact.value for fact in answer.facts} == {"WAIT_PAY", "SUCCESS"}


def test_failed_payment_preserves_failed_enum_meaning():
    answer = build_evidence_answer(
        _state(
            "payment_diagnosis",
            {
                "query_order": _record(order_status="WAIT_PAY"),
                "query_payment": _record(payment_status="FAILED"),
            },
        )
    )

    assert answer is not None
    assert "支付状态：支付失败" in answer.render()
    assert "支付成功" not in answer.render()


def test_coupon_issue_renders_evidence_and_omits_unknown_optional_fact():
    answer = build_evidence_answer(
        _state(
            "coupon_issue",
            {
                "query_order": _record(order_status="PAID"),
                "query_coupon_issue_log": _record(
                    coupon_issue_status="FAILED",
                    coupon_usage_status="UNKNOWN",
                    coupon_failure_confirmed=True,
                ),
            },
        )
    )

    assert answer is not None
    assert answer.render() == "订单状态：已支付；发券状态：发券失败。"
    assert "优惠券状态" not in answer.render()
    assert "UNKNOWN" not in answer.render()


def test_optional_coupon_usage_fact_is_rendered_when_supported():
    answer = build_evidence_answer(
        _state(
            "coupon_root_cause",
            {
                "query_order": _record(order_status="PAID"),
                "query_coupon_issue_log": _record(
                    coupon_issue_status="SENT",
                    coupon_usage_status="UNUSED",
                    coupon_failure_confirmed=False,
                ),
                "query_mq_dead_letter": _record(
                    mq_dead_letter_present=False,
                ),
            },
        )
    )

    assert answer is not None
    assert answer.render() == (
        "订单状态：已支付；发券状态：已发券；"
        "优惠券状态：未使用；MQ 死信：不存在。"
    )


def test_missing_required_tool_evidence_does_not_invent_answer():
    answer = build_evidence_answer(
        _state(
            "payment_diagnosis",
            {"query_order": _record(order_status="WAIT_PAY")},
        )
    )

    assert answer is None


def test_failed_tool_record_does_not_become_business_fact():
    answer = build_evidence_answer(
        _state(
            "payment_diagnosis",
            {
                "query_order": _record(order_status="WAIT_PAY"),
                "query_payment": {
                    "status": "permission_denied",
                    "attempts": 1,
                    "facts": {"payment_status": "SUCCESS"},
                },
            },
        )
    )

    assert answer is None


def test_validator_keeps_complete_supported_wording():
    answer = build_evidence_answer(
        _state(
            "payment_diagnosis",
            {
                "query_order": _record(order_status="WAIT_PAY"),
                "query_payment": _record(payment_status="SUCCESS"),
            },
        )
    )

    assert validate_or_fallback(
        "核实结果：订单仍是 WAIT_PAY，但支付已经成功。",
        answer,
    ) == "核实结果：订单仍是 WAIT_PAY，但支付已经成功。"


def test_validator_falls_back_when_required_fact_is_missing():
    answer = build_evidence_answer(
        _state(
            "payment_diagnosis",
            {
                "query_order": _record(order_status="WAIT_PAY"),
                "query_payment": _record(payment_status="SUCCESS"),
            },
        )
    )

    assert validate_or_fallback("支付记录已核实。", answer) == answer.render()


def test_validator_falls_back_when_candidate_contradicts_evidence():
    answer = build_evidence_answer(
        _state(
            "payment_diagnosis",
            {
                "query_order": _record(order_status="WAIT_PAY"),
                "query_payment": _record(payment_status="FAILED"),
            },
        )
    )

    assert validate_or_fallback(
        "订单待支付，但支付状态是支付成功。",
        answer,
    ) == answer.render()


def test_unsupported_route_keeps_existing_synthesis_path():
    assert build_evidence_answer(
        _state("knowledge", {"knowledge_search": _record(knowledge_found=True)})
    ) is None
