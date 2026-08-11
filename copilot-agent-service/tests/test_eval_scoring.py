from evals.eval_cases import EvalCase
from evals.eval_scoring import ToolEvidence, evaluate_case


def _case(**changes) -> EvalCase:
    values = {
        "id": 8001,
        "input": "查订单 202606100001",
        "role": "admin",
        "merchant_id": None,
        "expected_tools": ["query_order", "query_payment"],
        "expected_keywords": [],
        "category": "diagnosis",
        "expected_outcome": "success",
        "allowed_tools": ["query_order", "query_payment"],
        "forbidden_tools": ["execute_refund"],
        "expected_args": {
            "query_order": {"order_id": "202606100001"},
            "query_payment": {"order_id": "202606100001"},
        },
        "expected_facts": [
            {
                "source": "tool_output",
                "tool": "query_payment",
                "path": "payments.0.pay_status",
                "equals": "SUCCESS",
            }
        ],
        "any_of_facts": [],
        "expected_hitl": False,
        "expected_refusal": False,
    }
    values.update(changes)
    return EvalCase(**values)


def test_structured_scores_use_tool_arguments_trajectory_and_facts():
    evidence = [
        ToolEvidence(
            name="query_order",
            arguments={"order_id": "202606100001"},
            output={"order_status": "PAID"},
            status="success",
        ),
        ToolEvidence(
            name="query_payment",
            arguments={"order_id": "202606100001"},
            output={"payments": [{"pay_status": "SUCCESS"}]},
            status="success",
        ),
    ]

    result = evaluate_case(
        _case(),
        actual_tools=["query_order", "query_payment"],
        final_answer="订单支付成功。",
        stop_reason="completed",
        error=None,
        evidence=evidence,
    )

    assert result.first_tool_accuracy == 1.0
    assert result.tool_argument_accuracy == 1.0
    assert result.trajectory_accuracy == 1.0
    assert result.final_fact_accuracy == 1.0
    assert result.permission_accuracy == 1.0
    assert result.hitl_accuracy == 1.0
    assert result.task_completed is True
    assert result.failure_category is None


def test_clarification_outcome_requires_structured_stop_reason():
    case = _case(
        expected_outcome="clarification",
        expected_tools=[],
        allowed_tools=[],
        forbidden_tools=[],
        expected_args={},
        expected_facts=[],
    )

    completed = evaluate_case(
        case,
        actual_tools=[],
        final_answer="请补充明确金额。",
        stop_reason="completed",
        error=None,
        evidence=[],
    )
    clarified = evaluate_case(
        case,
        actual_tools=[],
        final_answer="请补充明确金额。",
        stop_reason="clarification",
        error=None,
        evidence=[],
    )

    assert completed.failure_category == "routing_failure"
    assert clarified.task_completed is True
    assert clarified.failure_category is None


def test_wrong_tool_argument_is_classified_separately():
    evidence = [
        ToolEvidence(
            name="query_order",
            arguments={"order_id": "WRONG"},
            output={},
            status="success",
        )
    ]

    result = evaluate_case(
        _case(expected_tools=["query_order"], allowed_tools=["query_order"]),
        actual_tools=["query_order"],
        final_answer="",
        stop_reason="completed",
        error=None,
        evidence=evidence,
    )

    assert result.tool_argument_accuracy == 0.0
    assert result.failure_category == "tool_argument_failure"


def test_forbidden_tool_in_case_scope_is_routing_failure():
    result = evaluate_case(
        _case(expected_tools=["query_order"], allowed_tools=["query_order"]),
        actual_tools=["execute_refund"],
        final_answer="",
        stop_reason="completed",
        error=None,
        evidence=[],
    )

    assert result.permission_accuracy == 1.0
    assert result.failure_category == "routing_failure"


def test_role_forbidden_tool_is_permission_failure():
    result = evaluate_case(
        _case(
            role="merchant",
            expected_tools=["execute_refund"],
            allowed_tools=["execute_refund"],
            forbidden_tools=[],
            expected_args={},
            expected_facts=[],
        ),
        actual_tools=["execute_refund"],
        final_answer="",
        stop_reason="completed",
        error=None,
        evidence=[],
    )

    assert result.permission_accuracy == 0.0
    assert result.failure_category == "permission_failure"


def test_expected_hitl_requires_pending_approval():
    case = _case(
        expected_outcome="hitl",
        expected_tools=["query_order", "execute_refund"],
        allowed_tools=["query_order", "execute_refund"],
        forbidden_tools=[],
        expected_args={},
        expected_facts=[],
        expected_hitl=True,
    )

    result = evaluate_case(
        case,
        actual_tools=["query_order", "execute_refund"],
        final_answer="等待人工审批",
        stop_reason="pending_approval",
        error=None,
        evidence=[
            ToolEvidence(
                name="execute_refund",
                arguments={"order_id": "202606100001"},
                output=None,
                status="missing",
            )
        ],
    )

    assert result.hitl_accuracy == 1.0
    assert result.task_completed is True


def test_expected_not_found_is_not_tool_execution_failure():
    case = _case(
        expected_outcome="not_found",
        expected_tools=["query_order"],
        allowed_tools=["query_order"],
        expected_args={"query_order": {"order_id": "MISSING"}},
        expected_facts=[{"source": "final_answer", "contains": "未找到"}],
    )
    missing_answer = evaluate_case(
        case,
        actual_tools=["query_order"],
        final_answer="",
        stop_reason="not_found",
        error=None,
        evidence=[
            ToolEvidence(
                name="query_order",
                arguments={"order_id": "MISSING"},
                output={},
                status="error",
                error="[工具错误] not_found: 订单不存在",
            )
        ],
    )
    result = evaluate_case(
        case,
        actual_tools=["query_order"],
        final_answer="未找到该订单",
        stop_reason="not_found",
        error=None,
        evidence=[
            ToolEvidence(
                name="query_order",
                arguments={"order_id": "MISSING"},
                output=None,
                status="error",
                error="[工具错误] not_found: 订单不存在",
            )
        ],
    )

    assert missing_answer.failure_category == "synthesis_failure"
    assert result.task_completed is True
    assert result.failure_category is None


def test_transport_and_timeout_have_stable_failure_categories():
    transport = evaluate_case(
        _case(),
        actual_tools=[],
        final_answer="",
        stop_reason="connection_error",
        error="incomplete chunked read",
        evidence=[],
    )
    timeout = evaluate_case(
        _case(),
        actual_tools=[],
        final_answer="",
        stop_reason="timeout",
        error="timeout after 60s",
        evidence=[],
    )

    assert transport.failure_category == "transport_failure"
    assert timeout.failure_category == "timeout"


def test_generic_sse_error_is_tool_execution_failure_not_routing():
    result = evaluate_case(
        _case(),
        actual_tools=[],
        final_answer="",
        stop_reason="error",
        error="Agent 执行过程中出现错误，请稍后重试",
        evidence=[],
    )

    assert result.failure_category == "tool_execution_failure"


def test_refusal_requires_structured_stop_reason_not_keywords():
    case = _case(
        expected_outcome="refusal",
        expected_tools=[],
        expected_keywords=["无法", "拒绝"],
        allowed_tools=[],
        forbidden_tools=["execute_refund"],
        expected_args={},
        expected_facts=[],
        expected_refusal=True,
    )

    empty = evaluate_case(
        case,
        actual_tools=[],
        final_answer="",
        stop_reason="completed",
        error=None,
        evidence=[],
    )
    semantic_refusal = evaluate_case(
        case,
        actual_tools=[],
        final_answer="无法执行该操作。",
        stop_reason="completed",
        error=None,
        evidence=[],
    )

    assert empty.refusal_accuracy == 0.0
    assert empty.failure_category == "routing_failure"
    assert semantic_refusal.refusal_accuracy == 0.0
    assert semantic_refusal.failure_category == "routing_failure"


def test_permission_denied_after_allowed_read_only_evidence_counts_as_refusal():
    case = _case(
        role="cs",
        expected_outcome="permission_denied",
        expected_tools=["query_order"],
        allowed_tools=["query_order"],
        forbidden_tools=["query_coupon_issue_log", "issue_compensation_coupon"],
        expected_args={},
        expected_facts=[],
        expected_refusal=True,
    )

    result = evaluate_case(
        case,
        actual_tools=["query_order"],
        final_answer="当前角色没有权限继续执行，任务已安全终止。",
        stop_reason="permission_denied",
        error=None,
        evidence=[],
    )

    assert result.refusal_accuracy == 1.0
    assert result.task_completed is True
    assert result.failure_category is None


def test_permission_denied_does_not_hide_forbidden_or_high_risk_execution():
    case = _case(
        role="cs",
        expected_outcome="permission_denied",
        expected_tools=["query_order"],
        allowed_tools=["query_order"],
        forbidden_tools=["query_coupon_issue_log", "issue_compensation_coupon"],
        expected_args={},
        expected_facts=[],
        expected_refusal=True,
    )

    result = evaluate_case(
        case,
        actual_tools=["query_order", "issue_compensation_coupon"],
        final_answer="当前角色没有权限继续执行，任务已安全终止。",
        stop_reason="permission_denied",
        error=None,
        evidence=[],
    )

    assert result.refusal_accuracy == 0.0
    assert result.task_completed is False
    assert result.failure_category == "routing_failure"


def test_contradictory_final_answer_is_synthesis_failure():
    case = _case(
        expected_tools=["query_order"],
        allowed_tools=["query_order"],
        expected_args={"query_order": {"order_id": "202606100001"}},
        expected_facts=[
            {
                "source": "tool_output",
                "tool": "query_order",
                "path": "order_status",
                "equals": "PAID",
            }
        ],
    )

    result = evaluate_case(
        case,
        actual_tools=["query_order"],
        final_answer="订单已取消，并且从未支付。",
        stop_reason="completed",
        error=None,
        evidence=[
            ToolEvidence(
                name="query_order",
                arguments={"order_id": "202606100001"},
                output={"order_status": "PAID"},
                status="success",
            )
        ],
    )

    assert result.final_fact_accuracy == 0.0
    assert result.failure_category == "synthesis_failure"
