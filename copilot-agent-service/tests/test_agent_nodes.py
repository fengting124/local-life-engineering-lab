"""
agent/nodes.py 的异步节点行为测试（mock 掉 LLM 和 MCP）。

这一层验证 ReAct 三个关键节点的「副作用与产出」：
  - tool_node：调 MCP 取 Observation；成功/工具错误/死循环三条路径
  - llm_node：调 LLM 决策；产出 tool_calls 或 Final Answer
  - final_node：三种终止原因（completed / max_steps / token_budget）

会话持久化（session_manager）通过 session_id=0 短路跳过，不依赖 DB。
"""
import pytest
from unittest.mock import AsyncMock, MagicMock
from langchain_core.messages import AIMessage, HumanMessage, ToolMessage

from agent import nodes
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
    )
    s.update(over)
    return s


def ai_with_tool_call(name, args, call_id="c1"):
    return AIMessage(content="", tool_calls=[{"name": name, "args": args, "id": call_id, "type": "tool_call"}])


# =========================================================
# tool_node
# =========================================================

class TestToolNode:
    @pytest.mark.asyncio
    async def test_success_returns_tool_message(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="订单状态：已支付")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        state = make_state([ai_with_tool_call("query_order", {"order_no": "X1"})])
        result = await nodes.tool_node(state)

        assert result["last_tool_failed"] is False
        assert len(result["messages"]) == 1
        tm = result["messages"][0]
        assert isinstance(tm, ToolMessage)
        assert tm.content == "订单状态：已支付"
        mock_mcp.call_tool.assert_awaited_once()

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

    @pytest.mark.asyncio
    async def test_loop_detected_short_circuits_without_calling_mcp(self, monkeypatch):
        mock_mcp = MagicMock()
        mock_mcp.call_tool = AsyncMock(return_value="不该被调用")
        monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

        args = {"order_no": "DEAD"}
        # 历史里同一 (工具+参数) 已出现 2 次 + 当前这次 = 第 3 次 → 触发循环熔断
        msgs = [
            ai_with_tool_call("query_order", args, "a"),
            ai_with_tool_call("query_order", args, "b"),
            ai_with_tool_call("query_order", args, "c"),
        ]
        result = await nodes.tool_node(make_state(msgs))

        content = str(result["messages"][0].content)
        assert "[循环检测]" in content
        mock_mcp.call_tool.assert_not_awaited()   # 熔断后绝不再真正调用工具

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


# =========================================================
# llm_node（fake LLM + mocked MCP）
# =========================================================

class FakeLLM:
    """最小可用的假 LLM：bind_tools 返回自身，ainvoke 返回预设响应。"""
    def __init__(self, response):
        self._response = response

    def bind_tools(self, tools):
        return self

    async def ainvoke(self, messages):
        return self._response


class TestLlmNode:
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
