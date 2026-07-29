"""
agent/nodes.py 的异步节点行为测试（mock 掉 LLM 和 MCP）。

这一层验证 ReAct 三个关键节点的「副作用与产出」：
  - tool_node：调 MCP 取 Observation；成功/工具错误/死循环三条路径
  - llm_node：调 LLM 决策；产出 tool_calls 或 Final Answer
  - final_node：三种终止原因（completed / max_steps / token_budget）

会话持久化（session_manager）通过 session_id=0 短路跳过，不依赖 DB。
"""
import pytest
import json
from types import SimpleNamespace
from unittest.mock import AsyncMock, MagicMock
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from agent import nodes
from agent.graph import route_after_tool
from agent.tool_router import order_target_hash
from mcp.mcp_client import McpToolError


def make_state(messages, **over) -> dict:
    s = dict(
        messages=messages,
        step_count=1,
        token_count=100,
        session_id=0,        # falsy → 跳过 DB 持久化
        thread_id="t-1",
        user_id=1,
        user_role="merchant",
        merchant_id=42,
        final_answer=None,
        tool_call_count=0,
        tool_call_counts={},
        tool_signature_counts={},
        tool_budget_exhausted=False,
        tool_budget_reason=None,
        policy_denied_tool=None,
        route_task_type="unknown",
        route_mode="general_fallback",
        route_confidence=0,
        route_required_tools=[],
        route_authorized_tools=[],
        route_next_tool=None,
        route_missing_fields=[],
        route_target_order_hash=None,
        route_requested_amount_minor=None,
        required_evidence=[],
        evidence_collected={},
        evidence_complete=False,
        evidence_stop_reason=None,
        synthesis_only=False,
    )
    s.update(over)
    return s


def ai_with_tool_call(name, args, call_id="c1"):
    return AIMessage(content="", tool_calls=[{"name": name, "args": args, "id": call_id, "type": "tool_call"}])


def ai_with_tool_calls(calls):
    return AIMessage(
        content="",
        tool_calls=[{**call, "type": "tool_call"} for call in calls],
    )


def controlled_state(messages, task, required, authorized, next_tool, **over):
    return make_state(
        messages,
        route_task_type=task,
        route_mode="controlled",
        route_confidence=100,
        route_required_tools=required,
        route_authorized_tools=authorized,
        route_next_tool=next_tool,
        route_missing_fields=[],
        required_evidence=required,
        evidence_collected={},
        evidence_complete=False,
        evidence_stop_reason=None,
        synthesis_only=False,
        **over,
    )


# =========================================================
# hitl_node
# =========================================================

class TestHitlNode:
    @pytest.mark.asyncio
    async def test_create_approval_payload_includes_current_merchant_id(self, monkeypatch):
        import session.hitl as hitl_module

        mock_service = MagicMock()
        mock_service.create_approval = AsyncMock(return_value=1001)
        monkeypatch.setattr(hitl_module, "hitl_service", mock_service)

        state = make_state(
            [HumanMessage(content="帮用户退款")],
            session_id=2001,
            user_role="cs",
            merchant_id=42,
            pending_action={
                "action_type": "execute_refund",
                "payload": {"order_id": "O-1", "amount": 100},
                "reason": "订单异常，需要退款",
            },
        )

        await nodes.hitl_node(state)

        _, kwargs = mock_service.create_approval.await_args
        assert kwargs["action_payload"]["merchant_id"] == 42
        assert kwargs["action_payload"]["order_id"] == "O-1"


# =========================================================
# tool_node
# =========================================================

class TestToolNode:
    @pytest.mark.asyncio
    async def test_controlled_multi_call_batch_is_rejected_without_execution(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该被调用")
        advance = MagicMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "advance_evidence", advance)
        calls = [
            {
                "name": "query_order",
                "args": {"order_id": "202606100001"},
                "id": "c1",
            },
            {
                "name": "query_payment",
                "args": {"order_id": "202606100001"},
                "id": "c2",
            },
        ]
        state = controlled_state(
            [ai_with_tool_calls(calls)],
            "payment_diagnosis",
            ["query_order", "query_payment"],
            ["query_order", "query_payment"],
            "query_order",
            user_role="admin",
        )

        result = await nodes.tool_node(state)

        assert [message.tool_call_id for message in result["messages"]] == [
            "c1",
            "c2",
        ]
        assert all(
            json.loads(message.content)["error"] == "internal_error"
            for message in result["messages"]
        )
        assert result["route_next_tool"] is None
        assert result["evidence_stop_reason"] == "internal_error"
        mock_mcp.call_tool.assert_not_awaited()
        advance.assert_not_called()

    @pytest.mark.asyncio
    async def test_tool_success_advances_payment_route(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value=json.dumps({
            "order_status": "WAIT_PAY",
            "payment": {"pay_status": "SUCCESS"},
            "coupon": {"coupon_status": None},
        }))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        state = controlled_state(
            [ai_with_tool_call(
                "query_order", {"order_id": "202606100001"}
            )],
            "payment_diagnosis",
            ["query_order", "query_payment"],
            ["query_order", "query_payment"],
            "query_order",
            user_role="admin",
        )

        result = await nodes.tool_node(state)

        assert result["route_next_tool"] == "query_payment"
        assert result["evidence_collected"]["query_order"]["facts"] == {
            "found": True,
            "order_status": "WAIT_PAY",
            "payment_status": "SUCCESS",
            "coupon_usage_status": "NONE",
        }

    @pytest.mark.asyncio
    async def test_not_found_is_valid_terminal_evidence(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(
            side_effect=McpToolError("not_found", "订单不存在")
        )
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        state = controlled_state(
            [ai_with_tool_call(
                "query_order", {"order_id": "202606100001"}
            )],
            "order_query",
            ["query_order"],
            ["query_order"],
            "query_order",
        )

        result = await nodes.tool_node(state)

        assert result["last_tool_failed"] is False
        assert result["evidence_stop_reason"] == "not_found"
        assert route_after_tool({**state, **result}) == "final_node"

    @pytest.mark.asyncio
    async def test_refund_handoff_reaches_hitl_only_after_evidence(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value=json.dumps({
            "order_no": "202606100001",
            "order_status": "PAID",
            "payment": {"pay_status": "SUCCESS"},
            "coupon": {"coupon_status": None},
        }))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        state = controlled_state(
            [ai_with_tool_call(
                "query_order", {"order_id": "202606100001"}
            )],
            "refund_action",
            ["query_order", "execute_refund"],
            ["query_order", "execute_refund"],
            "query_order",
            user_role="cs",
            route_target_order_hash=order_target_hash("202606100001"),
            route_requested_amount_minor=100,
        )

        first = await nodes.tool_node(state)

        assert first["route_next_tool"] == "execute_refund"
        refund_args = {
            "order_id": "202606100001",
            "amount": 100,
            "reason": "订单异常",
        }
        proposed = state | first | {
            "messages": [ai_with_tool_call("execute_refund", refund_args)],
        }
        second = await nodes.tool_node(proposed)

        assert second["pending_hitl"] is True
        mock_mcp.call_tool.assert_awaited_once()

    @pytest.mark.asyncio
    async def test_cs_native_tool_call_is_denied_before_rag_or_mcp(self, monkeypatch):
        from rag import knowledge_tool

        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该被调用")
        mock_native = SimpleNamespace(ainvoke=AsyncMock(return_value="不该被调用"))
        native_factory = MagicMock(return_value=mock_native)
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(knowledge_tool, "make_knowledge_search_tool", native_factory)

        state = make_state(
            [ai_with_tool_call("knowledge_search", {"query": "内部规则"})],
            user_role="cs",
        )
        result = await nodes.tool_node(state)

        assert result["policy_denied_tool"] == "knowledge_search"
        assert result["stop_reason"] == "permission_denied"
        assert result["route_next_tool"] is None
        assert result["evidence_stop_reason"] == "permission_denied"
        assert route_after_tool({**state, **result}) == "final_node"
        assert json.loads(result["messages"][0].content) == {
            "error": "permission_denied",
            "tool": "knowledge_search",
        }
        mock_mcp.call_tool.assert_not_awaited()
        native_factory.assert_not_called()

    @pytest.mark.asyncio
    @pytest.mark.parametrize("role", ["merchant", "admin"])
    async def test_authorized_roles_can_execute_native_tool(
        self, monkeypatch, role
    ):
        from rag import knowledge_tool

        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该走 MCP")
        mock_native = SimpleNamespace(ainvoke=AsyncMock(return_value='{"found": true}'))
        native_factory = MagicMock(return_value=mock_native)
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(knowledge_tool, "make_knowledge_search_tool", native_factory)

        result = await nodes.tool_node(
            make_state(
                [ai_with_tool_call("knowledge_search", {"query": "平台规则"})],
                user_role=role,
            )
        )

        assert result["last_tool_failed"] is False
        assert result["messages"][0].content == '{"found": true}'
        native_factory.assert_called_once()
        mock_native.ainvoke.assert_awaited_once_with({"query": "平台规则"})
        mock_mcp.call_tool.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_unknown_tool_is_denied_before_execution(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该被调用")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        result = await nodes.tool_node(
            make_state([ai_with_tool_call("unknown_tool", {"secret": "x"})])
        )

        assert result["policy_denied_tool"] == "unknown_tool"
        assert result["stop_reason"] == "permission_denied"
        assert json.loads(result["messages"][0].content) == {
            "error": "permission_denied",
            "tool": "unknown_tool",
        }
        mock_mcp.call_tool.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_native_tool_log_does_not_expose_query_value(
        self, monkeypatch, capsys
    ):
        from rag import knowledge_tool
        from rag import pipeline

        monkeypatch.setattr(
            pipeline,
            "retrieve",
            AsyncMock(return_value=SimpleNamespace(refused=True)),
        )
        native_tool = knowledge_tool.make_knowledge_search_tool(merchant_id=42)

        await native_tool.ainvoke({"query": "SECRET_ORDER_123456 的内部政策"})

        assert "SECRET_ORDER_123456" not in capsys.readouterr().out

    @pytest.mark.asyncio
    async def test_high_risk_tool_without_approval_requests_hitl_without_calling_mcp(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该被调用")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        args = {"order_id": "123", "amount": 100, "reason": "用户要求退款"}
        state = make_state(
            [ai_with_tool_call("execute_refund", args)],
            user_role="cs",
            route_task_type="refund_action",
            route_mode="controlled",
            route_required_tools=["query_order", "execute_refund"],
            route_authorized_tools=["query_order", "execute_refund"],
            route_next_tool="execute_refund",
            route_target_order_hash=order_target_hash("123"),
            route_requested_amount_minor=100,
        )

        result = await nodes.tool_node(state)

        assert result["pending_hitl"] is True
        assert result["pending_action"]["action_type"] == "execute_refund"
        assert result["pending_action"]["payload"] == args
        mock_mcp.call_tool.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_high_risk_tool_with_approval_executes_mcp(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="退款成功")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        args = {"order_id": "123", "amount": 100, "reason": "用户要求退款"}
        state = make_state(
            [ai_with_tool_call("execute_refund", args)],
            user_role="cs",
            pending_action={"action_type": "execute_refund", "approval_id": "APPROVAL_1"},
            route_task_type="refund_action",
            route_mode="controlled",
            route_required_tools=["query_order", "execute_refund"],
            route_authorized_tools=["query_order", "execute_refund"],
            route_next_tool="execute_refund",
            route_target_order_hash=order_target_hash("123"),
            route_requested_amount_minor=100,
        )

        result = await nodes.tool_node(state)

        assert result["last_tool_failed"] is False
        mock_mcp.call_tool.assert_awaited_once()
        _, kwargs = mock_mcp.call_tool.await_args
        assert kwargs["arguments"]["approval_id"] == "APPROVAL_1"

    @pytest.mark.asyncio
    async def test_high_risk_route_rejects_model_order_drift_before_mcp(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不应调用")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        state = controlled_state(
            [ai_with_tool_call(
                "query_order",
                {"order_id": "202606100099"},
            )],
            "refund_action",
            ["query_order", "execute_refund"],
            ["query_order", "execute_refund"],
            "query_order",
            user_role="cs",
            route_target_order_hash=order_target_hash("202606100001"),
            route_requested_amount_minor=2000,
        )

        result = await nodes.tool_node(state)

        mock_mcp.call_tool.assert_not_awaited()
        assert result["last_tool_failed"] is True
        assert result["evidence_collected"]["query_order"]["status"] == (
            "parameter_error"
        )
        assert "202606100099" not in result["messages"][0].content

    @pytest.mark.asyncio
    async def test_high_risk_route_rejects_cross_order_mcp_response(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value=json.dumps({
            "order_no": "202606100099",
            "order_status": "PAID",
            "payment": {"paid_amount": 9900},
        }))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        state = controlled_state(
            [ai_with_tool_call(
                "query_order",
                {"order_id": "202606100001"},
            )],
            "refund_action",
            ["query_order", "execute_refund"],
            ["query_order", "execute_refund"],
            "query_order",
            user_role="cs",
            route_target_order_hash=order_target_hash("202606100001"),
            route_requested_amount_minor=2000,
        )

        result = await nodes.tool_node(state)

        mock_mcp.call_tool.assert_awaited_once()
        assert result["evidence_stop_reason"] == "internal_error"
        assert result["route_next_tool"] is None
        assert "202606100099" not in result["messages"][0].content

    @pytest.mark.asyncio
    async def test_high_risk_tool_rejects_order_and_amount_drift_before_hitl(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不应调用")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        state = controlled_state(
            [ai_with_tool_call("execute_refund", {
                "order_id": "202606100099",
                "amount": 9900,
                "reason": "错误目标",
            })],
            "refund_action",
            ["query_order", "execute_refund"],
            ["query_order", "execute_refund"],
            "execute_refund",
            user_role="cs",
            route_target_order_hash=order_target_hash("202606100001"),
            route_requested_amount_minor=2000,
        )
        state["evidence_collected"] = {
            "query_order": {
                "status": "success",
                "attempts": 1,
                "facts": {"found": True, "order_status": "PAID"},
            },
        }

        result = await nodes.tool_node(state)

        assert result.get("pending_hitl") is not True
        assert result["evidence_stop_reason"] == "parameter_error"
        assert result["stop_reason"] == "parameter_error"
        mock_mcp.call_tool.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_unbound_high_risk_tool_later_in_batch_is_rejected(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不应调用")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        state = make_state(
            [ai_with_tool_calls([
                {
                    "name": "query_order",
                    "args": {"order_id": "202606100001"},
                    "id": "query",
                },
                {
                    "name": "execute_refund",
                    "args": {
                        "order_id": "202606100001",
                        "amount": 2000,
                        "reason": "无请求绑定",
                    },
                    "id": "refund",
                },
            ])],
            user_role="cs",
        )

        result = await nodes.tool_node(state)

        assert result.get("pending_hitl") is not True
        assert result["evidence_stop_reason"] == "internal_error"
        assert result["stop_reason"] == "internal_error"
        assert len(result["messages"]) == 2
        mock_mcp.call_tool.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_success_returns_tool_message(self, monkeypatch, capsys):
        mock_mcp = MagicMock()
        raw_result = json.dumps({
            "order_status": "PAID",
            "payment": {"pay_status": "SUCCESS"},
            "coupon": {"coupon_status": None},
        })
        mock_mcp.call_tool = AsyncMock(return_value=raw_result)
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        state = make_state(
            [
                ai_with_tool_call(
                    "query_order",
                    {"order_no": "ORDER_SECRET_123"},
                )
            ]
        )
        result = await nodes.tool_node(state)

        assert result["last_tool_failed"] is False
        assert len(result["messages"]) == 1
        tm = result["messages"][0]
        assert isinstance(tm, ToolMessage)
        assert tm.content == raw_result
        mock_mcp.call_tool.assert_awaited_once()
        assert "ORDER_SECRET_123" not in capsys.readouterr().out

    @pytest.mark.asyncio
    async def test_mcp_not_found_is_not_a_technical_failure(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(side_effect=McpToolError("not_found", "订单不存在", "确认订单号"))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        state = make_state([ai_with_tool_call("query_order", {"order_no": "X1"})])
        result = await nodes.tool_node(state)

        assert result["last_tool_failed"] is False
        assert result["last_tool_error"] is not None
        # 工具错误被包成给 LLM 看的 ToolMessage，带 [工具错误] 前缀 + 结构化 reason
        content = str(result["messages"][0].content)
        assert content.startswith("[工具错误]")
        assert "not_found" in content
        assert result["tool_call_count"] == 1
        assert result["tool_call_counts"] == {"query_order": 1}

    @pytest.mark.asyncio
    async def test_loop_detected_short_circuits_without_calling_mcp(self, monkeypatch):
        from agent.tool_policy import tool_call_signature

        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该被调用")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        args = {"order_no": "DEAD"}
        result = await nodes.tool_node(
            make_state(
                [ai_with_tool_call("query_order", args, "c")],
                tool_call_count=2,
                tool_call_counts={"query_order": 2},
                tool_signature_counts={
                    tool_call_signature("query_order", args): 2
                },
            )
        )

        assert json.loads(result["messages"][0].content) == {
            "error": "tool_budget_exhausted",
            "reason": "identical_call_limit",
            "tool": "query_order",
        }
        assert result["tool_budget_exhausted"] is True
        assert result["tool_budget_reason"] == "identical_call_limit"
        assert result["route_next_tool"] is None
        assert result["evidence_stop_reason"] == "tool_budget_exhausted"
        mock_mcp.call_tool.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_per_turn_limit_rejects_all_calls_and_preserves_protocol(
        self, monkeypatch, capsys
    ):
        from rag import knowledge_tool

        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该被调用")
        native_factory = MagicMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(
            knowledge_tool, "make_knowledge_search_tool", native_factory
        )
        calls = [
            {
                "name": "knowledge_search" if i == 4 else "query_order",
                "args": {"order_no": f"SECRET-{i}"},
                "id": f"c{i}",
            }
            for i in range(5)
        ]

        result = await nodes.tool_node(make_state([ai_with_tool_calls(calls)]))

        assert len(result["messages"]) == 5
        assert [m.tool_call_id for m in result["messages"]] == [
            f"c{i}" for i in range(5)
        ]
        assert all(
            json.loads(m.content)["reason"] == "per_turn_limit"
            for m in result["messages"]
        )
        assert result["stop_reason"] == "tool_budget_exhausted"
        assert route_after_tool(
            {**make_state([]), **result}
        ) == "final_node"
        mock_mcp.call_tool.assert_not_awaited()
        native_factory.assert_not_called()
        assert "SECRET-" not in capsys.readouterr().out

    @pytest.mark.asyncio
    async def test_total_limit_rejects_without_execution(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该被调用")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        result = await nodes.tool_node(
            make_state(
                [ai_with_tool_call("query_order", {"order_no": "O-9"})],
                tool_call_count=8,
                tool_call_counts={"query_order": 2},
            )
        )

        assert result["tool_budget_reason"] == "total_limit"
        assert result["tool_call_count"] == 8
        mock_mcp.call_tool.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_legal_three_tool_diagnostic_batch_executes(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="ok")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        calls = [
            {
                "name": "query_order",
                "args": {"order_no": f"O-{i}"},
                "id": f"c{i}",
            }
            for i in range(3)
        ]

        result = await nodes.tool_node(make_state([ai_with_tool_calls(calls)]))

        assert len(result["messages"]) == 3
        assert result["tool_call_count"] == 3
        assert result["tool_call_counts"] == {"query_order": 3}
        assert mock_mcp.call_tool.await_count == 3

    @pytest.mark.asyncio
    async def test_no_tool_calls_returns_empty(self, monkeypatch):
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: MagicMock())
        result = await nodes.tool_node(make_state([HumanMessage(content="你好")]))
        assert result["messages"] == []


# =========================================================
# final_node
# =========================================================

class TestFinalNode:
    @pytest.mark.asyncio
    async def test_completed_keeps_answer(self):
        state = make_state([], step_count=3, token_count=100, final_answer="共 3 单，营业额 500 元")
        result = await nodes.final_node(state)
        assert result["stop_reason"] == "completed"
        assert result["final_answer"] == "共 3 单，营业额 500 元"
        assert isinstance(result["messages"][0], AIMessage)

    @pytest.mark.asyncio
    async def test_max_steps_synthesizes_answer(self):
        from config.settings import settings
        state = make_state([], step_count=settings.agent_max_steps, final_answer=None)
        result = await nodes.final_node(state)
        assert result["stop_reason"] == "max_steps"
        assert "最大步数" in result["final_answer"]

    @pytest.mark.asyncio
    async def test_token_budget_synthesizes_answer(self):
        from config.settings import settings
        state = make_state([], step_count=3, token_count=settings.session_token_budget, final_answer=None)
        result = await nodes.final_node(state)
        assert result["stop_reason"] == "token_budget"
        assert "Token" in result["final_answer"]

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        ("state_overrides", "reason", "answer_fragment"),
        [
            (
                {
                    "stop_reason": "permission_denied",
                    "policy_denied_tool": "knowledge_search",
                },
                "permission_denied",
                "权限",
            ),
            (
                {
                    "stop_reason": "tool_budget_exhausted",
                    "tool_budget_exhausted": True,
                    "tool_budget_reason": "total_limit",
                },
                "tool_budget_exhausted",
                "调用预算",
            ),
            (
                {"stop_reason": "tool_loop_detected"},
                "tool_loop_detected",
                "重复",
            ),
        ],
    )
    async def test_policy_stops_are_not_completed(
        self, state_overrides, reason, answer_fragment
    ):
        result = await nodes.final_node(
            make_state([], final_answer=None, **state_overrides)
        )

        assert result["stop_reason"] == reason
        assert answer_fragment in result["final_answer"]

    @pytest.mark.asyncio
    @pytest.mark.parametrize(
        ("reason", "answer"),
        [
            ("not_found", "未找到与请求匹配的业务记录，未继续调用下游工具。"),
            ("parameter_error", "工具参数仍不符合要求，请核对必要信息后重试。"),
            ("timeout", "依赖工具连续超时，本次任务已停止，请稍后重试。"),
            ("business_rejected", "当前业务状态不满足继续处理的前置条件。"),
            ("internal_error", "依赖工具返回异常，本次任务未生成未经证实的结论。"),
        ],
    )
    async def test_evidence_stop_reason_survives_finalization(self, reason, answer):
        result = await nodes.final_node(
            make_state([], evidence_stop_reason=reason, final_answer=None)
        )

        assert result["stop_reason"] == reason
        assert result["final_answer"] == answer


# =========================================================
# llm_node（fake LLM + mocked MCP）
# =========================================================

class FakeLLM:
    """最小可用的假 LLM：bind_tools 返回自身，ainvoke 返回预设响应。"""
    def __init__(self, response):
        self._response = response
        self.bound_tools = []
        self.tool_choice = None
        self.seen_messages = []

    def bind_tools(self, tools, tool_choice=None):
        self.bound_tools = tools
        self.tool_choice = tool_choice
        return self

    async def ainvoke(self, messages):
        self.seen_messages = messages
        return self._response


class TestLlmNode:
    def test_chat_openai_binding_keeps_named_choice(self, monkeypatch):
        import langchain_openai

        class FakeChatOpenAI:
            def __init__(self, **kwargs):
                self.init_kwargs = kwargs
                self.bound_tools = []
                self.tool_choice = None

            def bind_tools(self, tools, tool_choice=None):
                self.bound_tools = tools
                self.tool_choice = tool_choice
                return self

        monkeypatch.setattr(langchain_openai, "ChatOpenAI", FakeChatOpenAI)
        monkeypatch.setattr(nodes.settings, "llm_provider", "openai")
        llm = nodes._create_llm()

        assert llm.bind_tools([], tool_choice="query_order") is llm
        assert llm.tool_choice == "query_order"

    @pytest.mark.asyncio
    async def test_controlled_route_binds_one_named_tool(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[
            {"name": "query_order", "description": "查订单"},
            {"name": "query_payment", "description": "查支付"},
        ])
        fake_llm = FakeLLM(ai_with_tool_call(
            "query_order", {"order_id": "202606100001"}
        ))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "openai")

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="查订单 202606100001")],
            route_task_type="order_query",
            route_mode="controlled",
            route_required_tools=["query_order"],
            route_authorized_tools=["query_order"],
            route_next_tool="query_order",
            route_missing_fields=[],
        ))

        names = {
            tool["name"] if isinstance(tool, dict) else tool.name
            for tool in fake_llm.bound_tools
        }
        assert names == {"query_order"}
        assert fake_llm.tool_choice == "query_order"
        assert result["final_answer"] is None

    @pytest.mark.asyncio
    async def test_deepseek_controlled_route_forces_tool_with_thinking_disabled(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[
            {"name": "query_order", "description": "查订单"},
            {"name": "query_payment", "description": "查支付"},
        ])
        fake_llm = MagicMock()
        fake_llm.bind_tools.return_value = fake_llm
        fake_llm.ainvoke = AsyncMock(return_value=ai_with_tool_call(
            "query_order", {"order_id": "202606100001"}
        ))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "deepseek")

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="查订单 202606100001")],
            route_task_type="order_query",
            route_mode="controlled",
            route_required_tools=["query_order"],
            route_authorized_tools=["query_order"],
            route_next_tool="query_order",
            route_missing_fields=[],
        ))

        bound_tools = fake_llm.bind_tools.call_args.args[0]
        names = {
            tool["name"] if isinstance(tool, dict) else tool.name
            for tool in bound_tools
        }
        assert names == {"query_order"}
        assert fake_llm.bind_tools.call_args.kwargs == {
            "tool_choice": "query_order",
            "extra_body": {"thinking": {"type": "disabled"}},
        }
        assert result["final_answer"] is None

    @pytest.mark.asyncio
    async def test_later_controlled_turn_keeps_retained_payment_tool(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[
            {"name": "query_order", "description": "查订单"},
            {"name": "query_payment", "description": "查支付"},
        ])
        fake_llm = FakeLLM(ai_with_tool_call(
            "query_payment", {"order_id": "202606100001"}
        ))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "openai")

        result = await nodes.llm_node(make_state(
            [
                HumanMessage(content="查询订单 202606100001 的支付状态"),
                ai_with_tool_call("query_order", {"order_id": "202606100001"}),
                ToolMessage(
                    content='{"order_status":"PAID"}',
                    tool_call_id="c1",
                    name="query_order",
                ),
            ],
            user_role="admin",
            route_task_type="payment_diagnosis",
            route_mode="controlled",
            route_required_tools=["query_order", "query_payment"],
            route_authorized_tools=["query_order", "query_payment"],
            route_next_tool="query_payment",
        ))

        names = {
            tool["name"] if isinstance(tool, dict) else tool.name
            for tool in fake_llm.bound_tools
        }
        assert names == {"query_payment"}
        assert fake_llm.tool_choice == "query_payment"
        assert result["messages"][0].tool_calls[0]["name"] == "query_payment"

    @pytest.mark.asyncio
    async def test_deepseek_refund_handoff_uses_structured_order_payload(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[{
            "name": "execute_refund",
            "description": "提交退款审批",
        }])
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "deepseek")

        result = await nodes.llm_node(make_state(
            [
                HumanMessage(content="给订单 202606100003 退款 20 元"),
                ai_with_tool_call(
                    "query_order",
                    {"order_id": "202606100003"},
                ),
                ToolMessage(
                    content=json.dumps({
                        "order_no": "202606100003",
                        "order_status": "PAID",
                        "user_id": "9000000001",
                        "payment": {"paid_amount": 2990},
                    }),
                    tool_call_id="c1",
                    name="query_order",
                ),
            ],
            user_role="cs",
            route_task_type="refund_action",
            route_mode="controlled",
            route_required_tools=["query_order", "execute_refund"],
            route_authorized_tools=["query_order", "execute_refund"],
            route_next_tool="execute_refund",
            route_target_order_hash=order_target_hash("202606100003"),
            route_requested_amount_minor=2000,
            evidence_collected={
                "query_order": {
                    "status": "success",
                    "attempts": 1,
                    "facts": {"found": True, "order_status": "PAID"},
                },
            },
        ))

        tool_call = result["messages"][0].tool_calls[0]
        assert tool_call["name"] == "execute_refund"
        assert tool_call["args"] == {
            "order_id": "202606100003",
            "amount": 2000,
            "reason": "订单状态满足退款前置条件，等待人工审批",
        }
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_deepseek_high_risk_handoff_fails_closed_without_amount(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[{
            "name": "execute_refund",
            "description": "提交退款审批",
        }])
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "deepseek")

        result = await nodes.llm_node(make_state(
            [
                HumanMessage(content="给订单退款"),
                ai_with_tool_call(
                    "query_order",
                    {"order_id": "202606100003"},
                ),
                ToolMessage(
                    content=json.dumps({
                        "order_no": "202606100003",
                        "order_status": "PAID",
                        "payment": {},
                    }),
                    tool_call_id="c1",
                    name="query_order",
                ),
            ],
            user_role="cs",
            route_task_type="refund_action",
            route_mode="controlled",
            route_required_tools=["query_order", "execute_refund"],
            route_authorized_tools=["query_order", "execute_refund"],
            route_next_tool="execute_refund",
            route_target_order_hash=order_target_hash("202606100003"),
            route_requested_amount_minor=2000,
            evidence_collected={
                "query_order": {
                    "status": "success",
                    "attempts": 1,
                    "facts": {"found": True, "order_status": "PAID"},
                },
            },
        ))

        assert result["messages"][0].tool_calls == []
        assert result["evidence_stop_reason"] == "internal_error"
        assert result["route_next_tool"] is None
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_deepseek_compensation_handoff_uses_confirmed_order_payload(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[{
            "name": "issue_compensation_coupon",
            "description": "提交补偿券审批",
        }])
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "deepseek")

        result = await nodes.llm_node(make_state(
            [
                HumanMessage(content="给订单 202606100003 补偿券 20 元"),
                ToolMessage(
                    content=json.dumps({
                        "order_no": "202606100003",
                        "order_status": "PAID",
                        "user_id": "9000000001",
                        "payment": {"paid_amount": 2990},
                    }),
                    tool_call_id="c1",
                    name="query_order",
                ),
                ToolMessage(
                    content=json.dumps({
                        "order_id": "202606100003",
                        "order_status": "PAID",
                        "outbox_messages": [{"status": "FAILED"}],
                    }),
                    tool_call_id="c2",
                    name="query_coupon_issue_log",
                ),
            ],
            user_role="admin",
            route_task_type="compensation_action",
            route_mode="controlled",
            route_required_tools=[
                "query_order",
                "query_coupon_issue_log",
                "issue_compensation_coupon",
            ],
            route_authorized_tools=[
                "query_order",
                "query_coupon_issue_log",
                "issue_compensation_coupon",
            ],
            route_next_tool="issue_compensation_coupon",
            route_target_order_hash=order_target_hash("202606100003"),
            route_requested_amount_minor=2000,
            evidence_collected={
                "query_order": {
                    "status": "success",
                    "attempts": 1,
                    "facts": {"found": True, "order_status": "PAID"},
                },
                "query_coupon_issue_log": {
                    "status": "success",
                    "attempts": 1,
                    "facts": {
                        "coupon_issue_status": "FAILED",
                        "coupon_failure_confirmed": True,
                    },
                },
            },
        ))

        tool_call = result["messages"][0].tool_calls[0]
        assert tool_call["name"] == "issue_compensation_coupon"
        assert tool_call["args"] == {
            "user_id": "9000000001",
            "order_id": "202606100003",
            "compensation_amount": 2000,
            "reason": "优惠券发放失败已确认，等待人工审批",
        }
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_deepseek_compensation_handoff_rejects_cross_order_evidence(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[{
            "name": "issue_compensation_coupon",
            "description": "提交补偿券审批",
        }])
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "deepseek")

        result = await nodes.llm_node(make_state(
            [
                HumanMessage(content="给订单补偿券"),
                ToolMessage(
                    content=json.dumps({
                        "order_no": "202606100003",
                        "order_status": "PAID",
                        "user_id": "9000000001",
                        "payment": {"paid_amount": 2990},
                    }),
                    tool_call_id="c1",
                    name="query_order",
                ),
                ToolMessage(
                    content=json.dumps({
                        "order_id": "202606100099",
                        "order_status": "PAID",
                        "outbox_messages": [{"status": "FAILED"}],
                    }),
                    tool_call_id="c2",
                    name="query_coupon_issue_log",
                ),
            ],
            user_role="admin",
            route_task_type="compensation_action",
            route_mode="controlled",
            route_required_tools=[
                "query_order",
                "query_coupon_issue_log",
                "issue_compensation_coupon",
            ],
            route_authorized_tools=[
                "query_order",
                "query_coupon_issue_log",
                "issue_compensation_coupon",
            ],
            route_next_tool="issue_compensation_coupon",
            route_target_order_hash=order_target_hash("202606100003"),
            route_requested_amount_minor=2000,
            evidence_collected={
                "query_order": {
                    "status": "success",
                    "attempts": 1,
                    "facts": {"found": True, "order_status": "PAID"},
                },
                "query_coupon_issue_log": {
                    "status": "success",
                    "attempts": 1,
                    "facts": {
                        "coupon_issue_status": "FAILED",
                        "coupon_failure_confirmed": True,
                    },
                },
            },
        ))

        assert result["messages"][0].tool_calls == []
        assert result["evidence_stop_reason"] == "internal_error"
        assert result["route_next_tool"] is None
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_deepseek_compensation_handoff_rejects_stale_order_status(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[{
            "name": "issue_compensation_coupon",
            "description": "提交补偿券审批",
        }])
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "deepseek")

        result = await nodes.llm_node(make_state(
            [
                HumanMessage(content="给订单补偿券"),
                ToolMessage(
                    content=json.dumps({
                        "order_no": "202606100003",
                        "order_status": "PAID",
                        "user_id": "9000000001",
                        "payment": {"paid_amount": 2990},
                    }),
                    tool_call_id="c1",
                    name="query_order",
                ),
                ToolMessage(
                    content=json.dumps({
                        "order_id": "202606100003",
                        "order_status": "CANCELLED",
                        "outbox_messages": [{"status": "FAILED"}],
                    }),
                    tool_call_id="c2",
                    name="query_coupon_issue_log",
                ),
            ],
            user_role="admin",
            route_task_type="compensation_action",
            route_mode="controlled",
            route_required_tools=[
                "query_order",
                "query_coupon_issue_log",
                "issue_compensation_coupon",
            ],
            route_authorized_tools=[
                "query_order",
                "query_coupon_issue_log",
                "issue_compensation_coupon",
            ],
            route_next_tool="issue_compensation_coupon",
            route_target_order_hash=order_target_hash("202606100003"),
            route_requested_amount_minor=2000,
            evidence_collected={
                "query_order": {
                    "status": "success",
                    "attempts": 1,
                    "facts": {"found": True, "order_status": "PAID"},
                },
                "query_coupon_issue_log": {
                    "status": "success",
                    "attempts": 1,
                    "facts": {
                        "coupon_issue_status": "FAILED",
                        "coupon_failure_confirmed": True,
                    },
                },
            },
        ))

        assert result["messages"][0].tool_calls == []
        assert result["evidence_stop_reason"] == "internal_error"
        assert result["route_next_tool"] is None
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_synthesis_only_binds_no_tools(self, monkeypatch):
        from rag import knowledge_tool

        mcp_factory = MagicMock()
        fake_llm = FakeLLM(AIMessage(content="订单已支付。"))
        native_factory = MagicMock()
        monkeypatch.setattr(nodes, "McpClient", mcp_factory)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(knowledge_tool, "make_knowledge_search_tool", native_factory)
        monkeypatch.setattr(nodes.settings, "llm_provider", "openai")

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="查订单"), ToolMessage(
                content='{"order_status":"PAID"}',
                tool_call_id="c1",
                name="query_order",
            )],
            synthesis_only=True,
            evidence_complete=True,
            route_next_tool=None,
        ))

        assert fake_llm.bound_tools == []
        assert result["final_answer"] == "订单已支付。"
        mcp_factory.assert_not_called()
        native_factory.assert_not_called()

    @pytest.mark.asyncio
    async def test_deepseek_synthesis_keeps_nonthinking_mode_without_tools(
        self, monkeypatch
    ):
        fake_llm = MagicMock()
        fake_llm.bind.return_value = fake_llm
        fake_llm.ainvoke = AsyncMock(
            return_value=AIMessage(content="订单已支付。")
        )
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "deepseek")

        result = await nodes.llm_node(make_state(
            [
                HumanMessage(content="查订单"),
                ToolMessage(
                    content='{"order_status":"PAID"}',
                    tool_call_id="c1",
                    name="query_order",
                ),
            ],
            synthesis_only=True,
            evidence_complete=True,
            route_next_tool=None,
        ))

        fake_llm.bind.assert_called_once_with(
            extra_body={"thinking": {"type": "disabled"}}
        )
        fake_llm.bind_tools.assert_not_called()
        assert result["final_answer"] == "订单已支付。"

    @pytest.mark.asyncio
    async def test_synthesis_only_tool_call_is_normalized_before_persistence(
        self, monkeypatch
    ):
        import session.manager as manager_module

        response = AIMessage(
            content="",
            tool_calls=[{
                "name": "query_order",
                "args": {"order_id": "202606100001"},
                "id": "escape",
                "type": "tool_call",
            }],
            usage_metadata={
                "input_tokens": 4,
                "output_tokens": 3,
                "total_tokens": 7,
            },
        )
        fake_llm = FakeLLM(response)
        mock_manager = MagicMock()
        mock_manager.save_message = AsyncMock()
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(nodes.settings, "llm_provider", "openai")
        monkeypatch.setattr(manager_module, "session_manager", mock_manager)

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="总结已收集的证据")],
            session_id=99,
            synthesis_only=True,
            evidence_complete=True,
            route_next_tool=None,
        ))

        assert result["messages"][0].tool_calls == []
        assert result["final_answer"] == (
            "依赖工具返回异常，本次任务未生成未经证实的结论。"
        )
        assert result["evidence_stop_reason"] == "internal_error"
        assert result["route_next_tool"] is None
        assert result["token_count"] == 107
        persisted = mock_manager.save_message.await_args.kwargs
        assert persisted["tool_calls"] is None
        assert persisted["tokens"] == 7

    @pytest.mark.asyncio
    async def test_clarification_skips_mcp_rag_and_llm(self, monkeypatch):
        from rag import knowledge_tool

        mcp_factory = MagicMock()
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        native_factory = MagicMock()
        monkeypatch.setattr(nodes, "McpClient", mcp_factory)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(knowledge_tool, "make_knowledge_search_tool", native_factory)

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="帮我查一下")],
            route_mode="clarification",
            route_missing_fields=["order_id"],
            route_next_tool=None,
        ))

        assert "具体订单号" in result["final_answer"]
        mcp_factory.assert_not_called()
        native_factory.assert_not_called()
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_amount_clarification_uses_business_label(self, monkeypatch):
        mcp_factory = MagicMock()
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        monkeypatch.setattr(nodes, "McpClient", mcp_factory)
        monkeypatch.setattr(nodes, "_llm", fake_llm)

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="给订单 202606100001 退款")],
            route_task_type="refund_action",
            route_mode="clarification",
            route_missing_fields=["amount"],
            route_next_tool=None,
        ))

        assert "明确的退款或补偿金额" in result["final_answer"]
        assert "amount" not in result["final_answer"]
        mcp_factory.assert_not_called()
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_initial_permission_denial_skips_mcp_rag_and_llm(self, monkeypatch):
        from rag import knowledge_tool

        mcp_factory = MagicMock()
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        native_factory = MagicMock()
        monkeypatch.setattr(nodes, "McpClient", mcp_factory)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(knowledge_tool, "make_knowledge_search_tool", native_factory)

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="查询支付状态")],
            evidence_stop_reason="permission_denied",
            evidence_collected={},
        ))

        assert "当前角色无法获取" in result["final_answer"]
        mcp_factory.assert_not_called()
        native_factory.assert_not_called()
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_controlled_mcp_list_failure_returns_internal_error(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(side_effect=Exception("MCP down"))
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="查订单 202606100001")],
            route_task_type="order_query",
            route_mode="controlled",
            route_required_tools=["query_order"],
            route_authorized_tools=["query_order"],
            route_next_tool="query_order",
        ))

        assert "内部错误" in result["final_answer"]
        assert result["evidence_stop_reason"] == "internal_error"
        assert result["stop_reason"] == "internal_error"
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_controlled_missing_mcp_tool_returns_internal_error(
        self, monkeypatch
    ):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[
            {"name": "query_order", "description": "查订单"},
        ])
        fake_llm = MagicMock()
        fake_llm.ainvoke = AsyncMock(return_value=AIMessage(content="不应调用模型"))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="查询订单 202606100001 的支付状态")],
            user_role="admin",
            route_task_type="payment_diagnosis",
            route_mode="controlled",
            route_required_tools=["query_order", "query_payment"],
            route_authorized_tools=["query_order", "query_payment"],
            route_next_tool="query_payment",
        ))

        assert result["final_answer"] == (
            "抱歉，发生内部错误，完成该请求所需的工具暂时不可用，请稍后重试。"
        )
        assert result["evidence_stop_reason"] == "internal_error"
        assert result["stop_reason"] == "internal_error"
        fake_llm.bind_tools.assert_not_called()
        fake_llm.ainvoke.assert_not_awaited()

    @pytest.mark.asyncio
    async def test_controlled_native_knowledge_route_survives_mcp_failure(
        self, monkeypatch
    ):
        from rag import knowledge_tool

        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(side_effect=Exception("MCP down"))
        fake_llm = FakeLLM(ai_with_tool_call("knowledge_search", {"query": "规则"}))
        native_tool = SimpleNamespace(name="knowledge_search")
        native_factory = MagicMock(return_value=native_tool)
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(knowledge_tool, "make_knowledge_search_tool", native_factory)
        monkeypatch.setattr(nodes.settings, "llm_provider", "openai")

        result = await nodes.llm_node(make_state(
            [HumanMessage(content="平台规则是什么")],
            route_task_type="knowledge",
            route_mode="controlled",
            route_required_tools=["knowledge_search"],
            route_authorized_tools=["knowledge_search"],
            route_next_tool="knowledge_search",
        ))

        assert fake_llm.bound_tools == [native_tool]
        assert fake_llm.tool_choice == "knowledge_search"
        assert result["final_answer"] is None

    @pytest.mark.asyncio
    async def test_cs_prompt_and_bindings_exclude_native_knowledge_tool(
        self, monkeypatch
    ):
        from rag import knowledge_tool

        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[])
        fake_llm = FakeLLM(AIMessage(content="请升级给管理员处理。"))
        native_factory = MagicMock()
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(knowledge_tool, "make_knowledge_search_tool", native_factory)

        await nodes.llm_node(
            make_state(
                [HumanMessage(content="平台规则是什么")],
                user_role="cs",
                merchant_id=None,
            )
        )

        bound_names = {
            tool["name"] if isinstance(tool, dict) else tool.name
            for tool in fake_llm.bound_tools
        }
        assert "knowledge_search" not in bound_names
        assert "knowledge_search" not in fake_llm.seen_messages[0].content
        native_factory.assert_not_called()

    @pytest.mark.asyncio
    @pytest.mark.parametrize("role", ["merchant", "admin"])
    async def test_authorized_role_prompt_and_bindings_share_native_tool(
        self, monkeypatch, role
    ):
        from rag import knowledge_tool

        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[])
        fake_llm = FakeLLM(AIMessage(content="规则说明"))
        native_tool = SimpleNamespace(name="knowledge_search")
        native_factory = MagicMock(return_value=native_tool)
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", fake_llm)
        monkeypatch.setattr(knowledge_tool, "make_knowledge_search_tool", native_factory)

        await nodes.llm_node(
            make_state(
                [HumanMessage(content="平台账户使用限制是什么")],
                user_role=role,
            )
        )

        bound_names = {
            tool["name"] if isinstance(tool, dict) else tool.name
            for tool in fake_llm.bound_tools
        }
        assert "knowledge_search" in bound_names
        assert "knowledge_search" in fake_llm.seen_messages[0].content
        native_factory.assert_called_once()

    @pytest.mark.asyncio
    async def test_final_answer_when_no_tool_calls(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[])
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", FakeLLM(AIMessage(content="您好，有什么可以帮您？")))

        result = await nodes.llm_node(make_state([HumanMessage(content="在吗")]))

        assert result["final_answer"] == "您好，有什么可以帮您？"
        assert result["step_count"] == 2          # 1 → 2
        assert result["last_tool_failed"] is False

    @pytest.mark.asyncio
    async def test_tool_call_decision_no_final_answer(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(return_value=[])
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        decision = AIMessage(
            content="",
            tool_calls=[{"name": "query_order", "args": {"order_no": "1"}, "id": "c1", "type": "tool_call"}],
        )
        monkeypatch.setattr(nodes, "_llm", FakeLLM(decision))

        result = await nodes.llm_node(make_state([HumanMessage(content="查订单1")]))

        # 决定调用工具时不产生 final_answer，交给 route → tool_node
        assert result["final_answer"] is None
        assert result["messages"][0].tool_calls
        assert result["step_count"] == 2

    @pytest.mark.asyncio
    async def test_mcp_list_tools_failure_degrades_gracefully(self, monkeypatch):
        # 取工具列表失败时降级为纯 LLM 回答，不应抛异常
        mock_mcp = MagicMock()
        mock_mcp.list_tools = AsyncMock(side_effect=Exception("MCP down"))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)
        monkeypatch.setattr(nodes, "_llm", FakeLLM(AIMessage(content="抱歉，工具暂不可用，我直接回答…")))

        result = await nodes.llm_node(make_state([HumanMessage(content="问题")]))
        assert result["final_answer"].startswith("抱歉")
