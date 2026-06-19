"""
agent/graph.py + agent/nodes.py 的「纯逻辑」单元测试 —— ReAct 循环的控制流。

这一层不碰 LLM、不碰 MCP，全是确定性纯函数：
  - route_after_llm：LLM 节点之后往哪走（终止/HITL/压缩/反思/工具/收尾），
    这是整个 ReAct Agent 的「大脑调度表」，优先级写错一条 Agent 行为就跑偏。
  - _partition_tool_calls：并发安全分批（安全工具并发、高风险工具串行隔离）
  - _detect_loop：同工具同参 ≥3 次的死循环熔断
  - _find_safe_compact_split：上下文压缩的安全切分点（不切断工具链）
  - _convert_to_lc_tools：MCP 工具 → LangChain 格式
  - build_graph：图能编译，节点齐全
"""
import types
import pytest

from agent.graph import route_after_llm, route_after_tool, build_graph, agent_graph
from agent import nodes
from config.settings import settings


def base_state(**over) -> dict:
    """构造一个「什么特殊条件都没触发」的中性状态，按需覆盖字段。"""
    s = dict(
        step_count=1,
        token_count=100,
        messages=[],
        pending_hitl=False,
        final_answer=None,
        compact_failures=0,
        needs_reflection=False,
        last_tool_failed=False,
    )
    s.update(over)
    return s


def msg_with_tool_calls():
    return types.SimpleNamespace(tool_calls=[{"name": "query_order", "args": {}, "id": "c1"}])


def msg_without_tool_calls():
    return types.SimpleNamespace(tool_calls=[])


# =========================================================
# route_after_llm —— 各分支
# =========================================================

class TestRouteAfterLlm:
    def test_max_steps_reached_goes_final(self):
        assert route_after_llm(base_state(step_count=settings.agent_max_steps)) == "final_node"

    def test_token_budget_exceeded_goes_final(self):
        assert route_after_llm(base_state(token_count=settings.session_token_budget)) == "final_node"

    def test_pending_hitl_goes_hitl(self):
        assert route_after_llm(base_state(pending_hitl=True)) == "hitl_node"

    def test_final_answer_present_goes_final(self):
        assert route_after_llm(base_state(final_answer="答案已就绪")) == "final_node"

    def test_compact_triggered_near_budget(self):
        # token 接近预算阈值 + 熔断未触发 + 消息够多 → 压缩
        threshold = settings.session_token_budget - settings.compact_buffer_tokens
        state = base_state(
            token_count=threshold,
            messages=[msg_without_tool_calls()] * (settings.compact_keep_recent_messages + 2),
        )
        assert route_after_llm(state) == "compact_node"

    def test_compact_skipped_when_circuit_breaker_open(self):
        # 压缩连续失败达上限 → 不再压缩，回退到后续判断（最终 final）
        threshold = settings.session_token_budget - settings.compact_buffer_tokens
        state = base_state(
            token_count=threshold,
            compact_failures=settings.compact_max_consecutive_failures,
            messages=[msg_without_tool_calls()] * (settings.compact_keep_recent_messages + 2),
        )
        assert route_after_llm(state) == "final_node"


class TestRouteAfterTool:
    def test_pending_hitl_goes_hitl(self):
        assert route_after_tool(base_state(pending_hitl=True)) == "hitl_node"

    def test_default_goes_llm(self):
        assert route_after_tool(base_state()) == "llm_node"

    def test_reflection_when_needs_reflection_flag(self):
        assert route_after_llm(base_state(needs_reflection=True)) == "reflection_node"

    def test_reflection_when_last_tool_failed(self):
        assert route_after_llm(base_state(last_tool_failed=True)) == "reflection_node"

    def test_reflection_every_n_steps(self):
        # step_count 是 reflection_interval 的整数倍 → 周期性反思
        assert route_after_llm(base_state(step_count=settings.reflection_interval)) == "reflection_node"

    def test_tool_calls_present_goes_tool(self):
        state = base_state(step_count=1, messages=[msg_with_tool_calls()])
        assert route_after_llm(state) == "tool_node"

    def test_default_goes_final(self):
        state = base_state(step_count=1, messages=[msg_without_tool_calls()])
        assert route_after_llm(state) == "final_node"

    # ---- 优先级 ----

    def test_max_steps_beats_hitl(self):
        # 终止条件优先级最高：即便有 HITL 也先收尾
        state = base_state(step_count=settings.agent_max_steps, pending_hitl=True)
        assert route_after_llm(state) == "final_node"

    def test_hitl_beats_final_answer(self):
        state = base_state(pending_hitl=True, final_answer="x")
        assert route_after_llm(state) == "hitl_node"

    def test_final_answer_beats_tool_calls(self):
        # 有 final_answer 时不再执行新的 tool_calls
        state = base_state(final_answer="done", messages=[msg_with_tool_calls()])
        assert route_after_llm(state) == "final_node"


# =========================================================
# _partition_tool_calls —— 并发安全分批
# =========================================================

class TestPartitionToolCalls:
    def _tc(self, name):
        return {"name": name, "args": {}, "id": name}

    def test_all_safe_merged_into_one_concurrent_batch(self):
        calls = [self._tc("query_order"), self._tc("query_payment"), self._tc("shop_metrics_query")]
        batches = nodes._partition_tool_calls(calls)
        assert batches == [("concurrent", [0, 1, 2])]

    def test_high_risk_isolated_as_sequential(self):
        # execute_refund 不安全，必须单独串行隔离
        calls = [self._tc("query_order"), self._tc("execute_refund"), self._tc("query_payment")]
        batches = nodes._partition_tool_calls(calls)
        assert batches == [("concurrent", [0]), ("sequential", [1]), ("concurrent", [2])]

    def test_unknown_tool_is_failclosed_sequential(self):
        # 未登记工具按不安全处理（fail-closed）
        batches = nodes._partition_tool_calls([self._tc("brand_new_tool")])
        assert batches == [("sequential", [0])]

    def test_empty_returns_empty(self):
        assert nodes._partition_tool_calls([]) == []


# =========================================================
# _detect_loop —— 死循环熔断
# =========================================================

class TestDetectLoop:
    def _ai(self, name, args):
        return types.SimpleNamespace(tool_calls=[{"name": name, "args": args, "id": "x"}])

    def test_no_loop_when_first_time(self):
        messages = [self._ai("query_order", {"order_no": "1"})]
        # 历史里只有 1 次相同调用 → 还不算循环
        assert nodes._detect_loop(messages, "query_order", {"order_no": "1"}) is False

    def test_loop_when_called_twice_before(self):
        # 历史已有 2 次相同 (工具+参数) → 当前是第 3 次 → 判定循环
        messages = [
            self._ai("query_order", {"order_no": "1"}),
            self._ai("query_order", {"order_no": "1"}),
        ]
        assert nodes._detect_loop(messages, "query_order", {"order_no": "1"}) is True

    def test_different_args_not_loop(self):
        # 改了参数的重试不算循环
        messages = [
            self._ai("query_order", {"order_no": "1"}),
            self._ai("query_order", {"order_no": "2"}),
        ]
        assert nodes._detect_loop(messages, "query_order", {"order_no": "3"}) is False


# =========================================================
# _find_safe_compact_split —— 上下文压缩安全切分
# =========================================================

class TestFindSafeCompactSplit:
    def test_keeps_recent_n_when_boundary_clean(self):
        from langchain_core.messages import HumanMessage
        msgs = [HumanMessage(content=f"m{i}") for i in range(10)]
        # 边界干净（都是 Human），直接保留最近 4 条 → split = 6
        assert nodes._find_safe_compact_split(msgs, keep_n=4) == 6

    def test_retreats_past_dangling_tool_message(self):
        from langchain_core.messages import HumanMessage, AIMessage, ToolMessage
        # 切分点正好落在孤立 ToolMessage 上 → 必须向前回退，避免保留区以 tool_result 开头
        msgs = [
            HumanMessage(content="q"),                                   # 0
            AIMessage(content="", tool_calls=[{"name": "t", "args": {}, "id": "1", "type": "tool_call"}]),  # 1
            ToolMessage(content="r", tool_call_id="1", name="t"),        # 2
            HumanMessage(content="next"),                                # 3
        ]
        # keep_n=2 → 初始 candidate=2（cur 是孤立 ToolMessage，prev 是带 tool_calls 的 AI）→
        # 回退到 candidate=1：此时 prev=Human(干净边界)、cur=AI(非 ToolMessage) → 停。
        # split=1：摘要区[0:1]=Human，保留区[1:]= AI(tool_calls)+Tool+Human 是完整工具链，安全。
        split = nodes._find_safe_compact_split(msgs, keep_n=2)
        assert split == 1

    def test_short_history_returns_zero(self):
        from langchain_core.messages import HumanMessage
        msgs = [HumanMessage(content="only")]
        assert nodes._find_safe_compact_split(msgs, keep_n=6) == 0


# =========================================================
# _convert_to_lc_tools —— MCP → LangChain 格式
# =========================================================

class TestConvertToLcTools:
    def test_maps_name_description_and_schema(self):
        mcp_tools = [{"name": "query_order", "description": "查订单",
                      "inputSchema": {"type": "object", "properties": {"order_no": {"type": "string"}}}}]
        result = nodes._convert_to_lc_tools(mcp_tools)
        assert result[0]["name"] == "query_order"
        assert result[0]["description"] == "查订单"
        assert result[0]["parameters"]["properties"]["order_no"]["type"] == "string"

    def test_missing_input_schema_defaults_to_empty_object(self):
        result = nodes._convert_to_lc_tools([{"name": "ping", "description": "d"}])
        assert result[0]["parameters"] == {"type": "object", "properties": {}}

    def test_empty_list(self):
        assert nodes._convert_to_lc_tools([]) == []


# =========================================================
# build_graph —— 图编译 & 结构
# =========================================================

class TestBuildGraph:
    def test_global_graph_compiled(self):
        # 模块导入时即编译了单例 agent_graph（MySQL 不可用则回退 MemorySaver）
        assert agent_graph is not None

    def test_build_graph_contains_all_nodes(self):
        compiled = build_graph()
        node_names = set(compiled.get_graph().nodes.keys())
        for expected in ["llm_node", "tool_node", "reflection_node", "compact_node", "hitl_node", "final_node"]:
            assert expected in node_names
