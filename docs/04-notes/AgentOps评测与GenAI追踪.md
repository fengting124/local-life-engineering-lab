# AgentOps 评测与 GenAI 追踪

本文记录 LocalLife Copilot 的两个生产化增强：AgentOps Eval Center 和 GenAI Trace。

## 1. AgentOps Eval Center

入口：

```bash
./scripts/run-agent-evals.sh
```

默认使用 mock Agent，只验证评测框架、指标计算和报告生成。报告输出到：

```text
copilot-agent-service/evals/reports/
```

真实 Agent 评测：

```bash
./scripts/run-agent-evals.sh --real
```

常用参数：

```bash
./scripts/run-agent-evals.sh --category diagnosis
./scripts/run-agent-evals.sh --case-id 16
./scripts/run-agent-evals.sh --real --fail-under-task-rate 0.8
```

报告产物：

- `*.json`：给 CI、脚本和后续趋势统计读取。
- `*.md`：给面试演示、复盘和文档归档阅读。

核心指标：

| 指标 | 项目含义 | 面试表达 |
| --- | --- | --- |
| Tool Call Accuracy | 工具选择是否正确 | Agent 不是只会聊天，而是能选对业务工具 |
| Task Completion Rate | 任务是否完成 | 用例集衡量端到端成功率 |
| Recall@5 | RAG 召回质量 | Milvus + reranker 不是摆设，有检索质量指标 |
| Factual Consistency | 回答是否有工具结果支撑 | 降低幻觉，避免编造业务状态 |
| P50/P99 Latency | 响应速度 | 可以定位慢在 LLM、MCP 还是 DB |
| Avg Tokens | 成本 | 能解释 prompt caching、fast path 的成本收益 |

## 2. GenAI Trace

本项目先走最小可用实现：不新增 OpenTelemetry SDK 依赖，直接把 GenAI/MCP span 作为结构化 JSON 日志写到 stdout，由 Promtail 收集到 Loki。

关键字段：

| 字段 | 含义 |
| --- | --- |
| `trace_id` | 一次用户请求的全链路 ID |
| `span_id` | 单个 LLM / Tool / MCP RPC 调用 ID |
| `span_name` | `llm.invoke`、`tool.query_order`、`mcp.rpc` |
| `span_kind` | `llm`、`tool`、`client` |
| `duration_ms` | 单个 span 耗时 |
| `status` | `ok` 或 `error` |

链路：

```text
POST /chat
  -> trace_id 绑定到 Python structlog
  -> llm.invoke span
  -> tool.xxx span
  -> mcp.rpc span
  -> X-Trace-Id 传给 Java MCP Server
  -> Java MDC traceId 写入 MCP 日志和 tool_audit_log
  -> Java 内部 API 继续透传 X-Trace-Id 到 local-life-server
```

排障步骤：

1. 前端从 `session_started` SSE 事件里拿 `trace_id`。
2. Grafana/Loki 搜索：

```logql
{service="copilot-agent"} |= "trace_id"
```

3. 过滤具体 trace：

```logql
{service=~"copilot-agent|local-life-copilot|local-life-server"} | json | trace_id="替换为实际 trace_id"
```

4. 看 `duration_ms` 最大的 span，判断慢在 LLM、RAG、MCP 还是 Java 业务查询。

## 3. 面试常问

**为什么没有一开始就接 OpenTelemetry SDK？**

因为项目已经有 Promtail/Loki/Grafana，先用结构化 JSON span 打通 traceId、spanId、duration、status，成本最低。真正多服务上云或接 Collector 时，再把 `agent/trace.py` 替换成 OTel SDK 导出即可。

**Evals 和单元测试有什么区别？**

单元测试验证函数逻辑；Evals 验证 Agent 在真实业务问题下是否选对工具、是否完成任务、回答是否基于证据。

**为什么要保存 Markdown 和 JSON 两种报告？**

JSON 给 CI 门禁和趋势统计，Markdown 给面试演示和人工复盘。一个给机器，一个给人。
