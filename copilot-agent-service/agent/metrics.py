"""
Agent 业务指标（Prometheus）。

提供 Counter / Histogram / Gauge 三类指标，由各个节点和接口埋点。
所有指标自动暴露在 /metrics 端点，Prometheus 定期抓取。

指标命名规范（Prometheus 标准）：
  copilot_<subject>_<verb>_<unit>
  示例：copilot_agent_sessions_total / copilot_llm_tokens_total

标签（labels）选择原则：
  - 高基数（如 user_id）禁止作为 label（Prometheus 内存爆炸）
  - 低基数（角色、工具名、终止原因）适合作为 label

Grafana 查询示例：
  # Agent 完成率
  sum(rate(copilot_agent_sessions_total{status="completed"}[5m]))
    / sum(rate(copilot_agent_sessions_total[5m]))

  # 工具调用 P99 延迟
  histogram_quantile(0.99,
    sum(rate(copilot_tool_duration_seconds_bucket[5m])) by (le, tool_name))

  # 单会话平均 Token 消耗
  rate(copilot_llm_tokens_total[5m]) / rate(copilot_agent_sessions_total[5m])
"""
from prometheus_client import Counter, Histogram, Gauge

# =========================================================
# Agent 会话指标
# =========================================================

agent_sessions_total = Counter(
    "copilot_agent_sessions_total",
    "Agent 会话总数（按终止原因和角色分组）",
    ["status", "role"],   # status: completed/max_steps/token_budget/pending_approval
)

agent_steps_histogram = Histogram(
    "copilot_agent_steps",
    "Agent 单会话执行步数分布",
    buckets=(1, 2, 3, 5, 8, 12, 15, 20),
)

# =========================================================
# LLM 指标
# =========================================================

llm_tokens_total = Counter(
    "copilot_llm_tokens_total",
    "LLM 累计消耗 token 数（按角色和类型分组）",
    ["role", "token_type"],   # token_type: input / output
)

llm_latency_seconds = Histogram(
    "copilot_llm_latency_seconds",
    "单次 LLM 调用耗时",
    ["role"],
    buckets=(0.5, 1, 2, 5, 10, 20, 30, 60),
)

# =========================================================
# 工具调用指标
# =========================================================

tool_calls_total = Counter(
    "copilot_tool_calls_total",
    "工具调用总次数（按工具名和结果分组）",
    ["tool_name", "result"],   # result: success / parameter_error / not_found / etc.
)

tool_duration_seconds = Histogram(
    "copilot_tool_duration_seconds",
    "工具调用耗时分布",
    ["tool_name"],
    buckets=(0.01, 0.05, 0.1, 0.5, 1, 2, 5, 10),
)

# =========================================================
# RAG 指标
# =========================================================

rag_queries_total = Counter(
    "copilot_rag_queries_total",
    "RAG 检索总次数（按结果分组）",
    ["result"],   # result: hit / refused / error
)

rag_latency_seconds = Histogram(
    "copilot_rag_latency_seconds",
    "RAG 检索端到端延迟（embedding + search + rerank）",
    buckets=(0.05, 0.1, 0.2, 0.5, 1, 2, 5),
)

# =========================================================
# Auto-Compact 指标
# =========================================================

compact_events_total = Counter(
    "copilot_compact_events_total",
    "上下文自动压缩事件计数（按结果分组）",
    ["result"],   # result: success / skipped_no_split / failed / circuit_broken
)

# =========================================================
# HITL 指标
# =========================================================

hitl_approvals_total = Counter(
    "copilot_hitl_approvals_total",
    "HITL 审批请求总数（按动作类型和最终状态分组）",
    ["action_type", "status"],   # status: pending / approved / rejected / expired
)

hitl_pending_gauge = Gauge(
    "copilot_hitl_pending_count",
    "当前 PENDING 状态的审批请求数",
)

# =========================================================
# Guardrails 指标
# =========================================================

guardrails_triggered_total = Counter(
    "copilot_guardrails_triggered_total",
    "Guardrails 拦截/警告事件计数",
    ["level", "rule"],   # level: block / warn
)

# =========================================================
# 限流指标（MCP Server 也用同名指标，跨服务聚合）
# =========================================================

rate_limit_triggered_total = Counter(
    "copilot_rate_limit_triggered_total",
    "限流触发次数",
    ["limit_type"],   # tool / user
)


# =========================================================
# 辅助函数
# =========================================================

def record_session_end(status: str, role: str, step_count: int):
    """会话结束时统一调用此函数。"""
    agent_sessions_total.labels(status=status, role=role).inc()
    agent_steps_histogram.observe(step_count)


def record_compact_event(result: str):
    """上下文自动压缩尝试结束时统一调用（无论成功/跳过/失败/熔断）。"""
    compact_events_total.labels(result=result).inc()


def record_tool_call(tool_name: str, result: str, duration_seconds: float):
    """工具调用完成时（无论成功失败）统一调用。"""
    tool_calls_total.labels(tool_name=tool_name, result=result).inc()
    tool_duration_seconds.labels(tool_name=tool_name).observe(duration_seconds)


def record_llm_call(role: str, input_tokens: int, output_tokens: int, duration_seconds: float):
    """LLM 调用完成时统一调用。"""
    llm_tokens_total.labels(role=role, token_type="input").inc(input_tokens)
    llm_tokens_total.labels(role=role, token_type="output").inc(output_tokens)
    llm_latency_seconds.labels(role=role).observe(duration_seconds)
