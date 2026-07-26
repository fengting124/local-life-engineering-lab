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
        )

        result = await nodes.tool_node(state)

        assert result["last_tool_failed"] is False
        mock_mcp.call_tool.assert_awaited_once()
        _, kwargs = mock_mcp.call_tool.await_args
        assert kwargs["arguments"]["approval_id"] == "APPROVAL_1"

    @pytest.mark.asyncio
    async def test_success_returns_tool_message(self, monkeypatch, capsys):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="订单状态：已支付")
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
        assert tm.content == "订单状态：已支付"
        mock_mcp.call_tool.assert_awaited_once()
        assert "ORDER_SECRET_123" not in capsys.readouterr().out

    @pytest.mark.asyncio
    async def test_mcp_tool_error_marks_failed(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(side_effect=McpToolError("not_found", "订单不存在", "确认订单号"))
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        state = make_state([ai_with_tool_call("query_order", {"order_no": "X1"})])
        result = await nodes.tool_node(state)

        assert result["last_tool_failed"] is True
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


# =========================================================
# llm_node（fake LLM + mocked MCP）
# =========================================================

class FakeLLM:
    """最小可用的假 LLM：bind_tools 返回自身，ainvoke 返回预设响应。"""
    def __init__(self, response):
        self._response = response
        self.bound_tools = []
        self.seen_messages = []

    def bind_tools(self, tools):
        self.bound_tools = tools
        return self

    async def ainvoke(self, messages):
        self.seen_messages = messages
        return self._response


class TestLlmNode:
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
