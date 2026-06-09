"""
端到端（E2E）测试：驱动「真实编译出来的」LangGraph ReAct 图跑完整一轮。

与 test_agent_nodes.py（单节点）不同，这里测的是<b>整张图的接线</b>：
    llm_node → (决定调工具) → tool_node → llm_node → (给出答案) → final_node → END
即把 route_after_llm 的条件路由、节点间的 edge、状态累积串起来跑一遍真实闭环。

隔离策略（只换掉「外部世界」，图的接线和节点逻辑全是真的）：
  - LLM：用 ScriptedLLM 按剧本先返回 tool_call、再返回 Final Answer
  - MCP：mock 掉 list_tools / call_tool
  - Checkpointer：强制走 MemorySaver（不依赖 MySQL），保证可重复
  - 会话持久化：session_id=0 短路跳过 DB

这是「前端→agent→MCP→DB 完整链路」里 **agent 大脑** 这一段的自动化 E2E；
跨进程的真·全链路 E2E（真实 LLM + 真实 MCP Server + 真实 DB）见
docs/04-notes 的「E2E 测试」一节，依赖多服务同时在线，作为手动/CI 流程。
"""
import pytest
from unittest.mock import AsyncMock, MagicMock
from langchain_core.messages import AIMessage, HumanMessage

import session.checkpointer as ckpt_mod
from agent import nodes


class ScriptedLLM:
    """按剧本逐次返回响应的假 LLM：第 1 次要求调工具，第 2 次给最终答案。"""
    def __init__(self, responses):
        self._responses = responses
        self._i = 0

    def bind_tools(self, tools):
        return self

    async def ainvoke(self, messages):
        r = self._responses[min(self._i, len(self._responses) - 1)]
        self._i += 1
        return r


def _force_memory_saver(monkeypatch):
    """让 build_graph 的 AsyncMySQLCheckpointer 构造失败，从而回退到 MemorySaver。"""
    def _boom(*a, **k):
        raise RuntimeError("force MemorySaver in test")
    monkeypatch.setattr(ckpt_mod, "AsyncMySQLCheckpointer", _boom)


@pytest.mark.asyncio
async def test_full_react_loop_llm_tool_llm_final(monkeypatch):
    _force_memory_saver(monkeypatch)

    # ---- mock MCP：提供一个工具，并让工具调用返回观测 ----
    mock_mcp = MagicMock()
    mock_mcp.list_tools = AsyncMock(return_value=[{
        "name": "shop_metrics_query",
        "description": "查询门店经营指标",
        "inputSchema": {"type": "object", "properties": {"date": {"type": "string"}}},
    }])
    mock_mcp.call_tool = AsyncMock(return_value='{"gmv": 50000, "order_count": 10}')
    monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

    # ---- scripted LLM：第 1 步调用工具，第 2 步给出最终回答 ----
    scripted = ScriptedLLM([
        AIMessage(
            content="",
            tool_calls=[{"name": "shop_metrics_query", "args": {"date": "today"},
                         "id": "call_1", "type": "tool_call"}],
        ),
        AIMessage(content="今天 GMV 500 元，共 10 单。"),
    ])
    monkeypatch.setattr(nodes, "_llm", scripted)

    # ---- 编译真实的图（此时才 import，确保上面的 monkeypatch 生效）----
    from agent.graph import build_graph
    graph = build_graph()

    initial_state = {
        "messages": [HumanMessage(content="今天卖了多少？")],
        "step_count": 0,
        "token_count": 0,
        "session_id": 0,          # 跳过 DB 持久化
        "thread_id": "e2e-thread-1",
        "user_id": 1,
        "user_role": "merchant",
        "merchant_id": 42,
        "pending_hitl": False,
        "final_answer": None,
        "compact_failures": 0,
        "needs_reflection": False,
        "last_tool_failed": False,
    }
    config = {"configurable": {"thread_id": "e2e-thread-1"}}

    final_state = await graph.ainvoke(initial_state, config=config)

    # ---- 断言整轮闭环的产出 ----
    # 1) 工具确实被调用了一次（ReAct 真的走了 tool_node）
    mock_mcp.call_tool.assert_awaited_once()
    # 2) LLM 被调用两次（决策 + 总结）
    assert scripted._i == 2
    # 3) 最终答案正确收口
    assert final_state["final_answer"] == "今天 GMV 500 元，共 10 单。"
    assert final_state["stop_reason"] == "completed"
    # 4) 走了至少 2 步（llm → tool → llm）
    assert final_state["step_count"] >= 2
    # 5) 消息历史里出现过工具观测（ToolMessage）
    from langchain_core.messages import ToolMessage
    assert any(isinstance(m, ToolMessage) for m in final_state["messages"])


@pytest.mark.asyncio
async def test_react_loop_direct_answer_no_tool(monkeypatch):
    """闲聊类问题：LLM 直接给答案，不调用任何工具，一步收口。"""
    _force_memory_saver(monkeypatch)

    mock_mcp = MagicMock()
    mock_mcp.list_tools = AsyncMock(return_value=[])
    mock_mcp.call_tool = AsyncMock()
    monkeypatch.setattr(nodes, "McpClient", lambda **kw: mock_mcp)

    monkeypatch.setattr(nodes, "_llm", ScriptedLLM([AIMessage(content="你好，我是经营助手。")]))

    from agent.graph import build_graph
    graph = build_graph()

    state = {
        "messages": [HumanMessage(content="你好")],
        "step_count": 0, "token_count": 0, "session_id": 0, "thread_id": "e2e-2",
        "user_id": 1, "user_role": "merchant", "merchant_id": 42,
        "pending_hitl": False, "final_answer": None, "compact_failures": 0,
        "needs_reflection": False, "last_tool_failed": False,
    }
    final_state = await graph.ainvoke(state, config={"configurable": {"thread_id": "e2e-2"}})

    assert final_state["final_answer"] == "你好，我是经营助手。"
    assert final_state["stop_reason"] == "completed"
    mock_mcp.call_tool.assert_not_awaited()   # 没有触发任何工具调用
