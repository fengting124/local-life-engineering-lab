# LocalLife 当前测试能力审计

- Status: Active
- Type: Reference
- Owners: Project maintainers
- Last verified: 2026-07-22
- Source of truth: `performance-tests/`, `scripts/run-backend-perf-baseline.sh`, `scripts/run-agent-deepseek-baseline.sh`, `.github/workflows/`

> 本文记录第一轮“后端与 Agent 性能基线测试”的阶段 0 审计结果。审计只记录能力、缺口和环境事实，不包含 API Key、Prompt 原文或敏感业务数据。

## 1. 基本信息

| 项 | 当前值 |
| --- | --- |
| 仓库 | `fengting124/local-life-engineering-lab` |
| 审计分支 | `test/performance-agent-baseline` |
| main 基线 SHA | `659b2427178a07567f978541d94770407bed2b70` |
| 测试日期 | 2026-07-22 |
| CPU | 16 cores |
| 内存 | 11 GiB total, 5.1 GiB available at audit start |
| 磁盘 | `/dev/sdd`, 1007 GiB total, 943 GiB available |
| Docker | 28.1.1 |
| Docker Compose | v2.35.1-desktop.1 |
| Java | OpenJDK 17.0.19 |
| Python | 3.10.12 |

## 2. DeepSeek 与 Agent 配置

| 配置项 | 当前项目变量 | 审计结论 |
| --- | --- | --- |
| Provider | `LLM_PROVIDER` | 默认 `deepseek` |
| API Key | `LLM_API_KEY` | 必须从环境变量注入；不得写入仓库文件 |
| 模型 | `LLM_MODEL` | 默认 `deepseek-v4-flash` |
| Base URL | `LLM_BASE_URL` | 为空时使用 provider 默认值 |
| 兼容旧 Key | `ANTHROPIC_API_KEY` | 仅作为旧配置回退，不用于本轮 DeepSeek 基线 |
| Agent workers | Dockerfile 默认 2；Lite override 为 1 | Lite 为避免 Milvus Lite 文件锁使用 1 worker；完整环境默认 2 worker |
| MCP URL | `MCP_SERVER_URL` | Docker 完整环境指向 `http://locallife-copilot:8081` |
| Milvus | `MILVUS_URI` | 完整环境为 `http://milvus:19530`；Lite 为 `/app/data/local_life_kb.db` |
| Embedding | `EMBEDDING_SERVICE_URL` | Docker 完整环境指向 `http://embedding-service:8100` |
| Reranker | `RERANKER_SERVICE_URL` | Docker 完整环境指向 `http://reranker-service:8101` |

## 3. 已有测试入口

| 路径 | 类型 | 已覆盖能力 | 备注 |
| --- | --- | --- | --- |
| `performance-tests/locustfile_locallife_server.py` | Locust | 普通浏览、订单查询、点赞、搜索、秒杀用户模型 | 已有用户模型，但缺统一分轮 runner 和业务一致性汇总 |
| `performance-tests/locustfile_copilot.py` | Locust | MCP 工具调用、Agent SSE 简单对话 | 能测 MCP/Agent 基础延迟，但缺真实 DeepSeek 质量门槛汇总 |
| `performance-tests/k6/seckill.js` | k6 | 秒杀突发争抢、claimed 计数、超卖初判 | 已安装 k6 并完成 DB/Redis/Outbox/券包事后核对 |
| `scripts/seed-perf-data.sh` | 数据准备 | 2000 压测用户、2 个秒杀场次、Redis 验证码和库存 | 数据号段固定，可重复执行 |
| `scripts/e2e-smoke.sh` | Smoke | Java Server、Copilot MCP 最小链路 | 可作为压测前置门禁 |
| `scripts/demo-smoke.sh` | Demo Smoke | 演示链路、RAG 检查 | 可作为端到端演示验收 |
| `scripts/run-agent-evals.sh` | Agent eval | 50 条 AgentOps 用例，支持 mock/real | 已有报告输出，真实模式依赖本地 Agent 服务 |
| `scripts/run-rag-benchmark.sh` | RAG benchmark | Recall@5、citation、refusal、rerank delta | 支持 offline/real，两类报告已存在 |
| `copilot-agent-service/evals/` | Agent/RAG 评测 | EvalCase、真实 SSE client、RAG benchmark | 已有确定性指标，但真实 DeepSeek 并发和成本基线仍需统一入口 |

## 4. 已有指标

| 服务 | 已有指标/日志 | 数据来源 |
| --- | --- | --- |
| local-life-server | HTTP/JVM/Hikari/Redis 由 Actuator/Micrometer 暴露；业务指标含 seckill、order、payment、search | `/actuator/prometheus` |
| local-life-copilot | 工具审计表记录 tool、traceId、durationMs、status；Micrometer 暴露 Spring 指标 | `/actuator/prometheus` 和 `tool_audit_log` |
| copilot-agent-service | FastAPI `/metrics`，`copilot_agent_sessions_total`、`copilot_llm_tokens_total`、`copilot_tool_duration_seconds`、`copilot_rag_latency_seconds`、HITL/guardrails 指标 | `/metrics` |
| Agent trace | `agent.trace.genai_span` 已记录 `span_id`、`span_name`、`span_kind`、`duration_ms`、`status` | 结构化日志 |
| MCP trace | `McpClient._headers()` 透传 `X-Trace-Id`；Java Copilot 有 `RequestTraceFilter` | Header + MDC |
| runtime 事件 | `agent_run` 和 `agent_event` 存储 `trace_id`、事件序列和状态 | MySQL |

## 5. 缺失指标和可观测性盲区

| 缺口 | 影响 | 本轮处理策略 |
| --- | --- | --- |
| RAG 内部阶段未统一输出 `embedding/vector/bm25/reranker` 独立 span | 难以定位 RAG 瓶颈 | 本轮补轻量 span，不引入新平台 |
| Agent TTFT 未形成独立汇总指标 | SSE 首包体验不可量化 | 由 Agent baseline runner 消费 SSE 并计算 |
| 真实 DeepSeek 的 token/cost 汇总不完整 | 无法估算成本基线 | SSE 当前不返回可信 usage，明确标记不可得，不推算成本 |
| 后端业务一致性核对没有统一产物 | 压测结果可信度不足 | 已补固定数据分区和 Redis/MySQL/Outbox/券包一致性核对 |
| Outbox `PENDING/PROCESSING/SENT` 与租约年龄没有统一快照 | 消息链路积压难定位 | 报告中定义 SQL/指标快照，完整 MQ 不可用时标记 BLOCKED |
| Full Docker 环境可能受本机内存约束 | 重型组件可能无法全部健康 | 不静默降级；记录资源和失败组件 |

## 6. 当前环境快照

审计开始时只有部分容器健康；完成阶段已重新构建并启动完整测试依赖：

| 服务 | 审计观察 |
| --- | --- |
| `copilot-agent-service` | healthy，真实 DeepSeek flash 基线已执行 |
| `local-life-server` | 标准 Dockerfile 当前源码构建，healthy |
| `local-life-copilot` | healthy，MCP Locust 0 failure |
| `embedding-service` / `reranker-service` | healthy，24 条真实 RAG 基线已执行 |
| MySQL / Redis / RocketMQ / Elasticsearch / Milvus | 已参与本轮完整后端或 Agent 验证 |

当前结果仍是短基线，不等同于生产容量证明。长稳压测、故障注入、Agent 质量优化仍需后续执行。

## 7. 已有报告产物

| 路径 | 内容 |
| --- | --- |
| `performance-tests/reports/秒杀压测报告.md` | 既有秒杀压测说明 |
| `copilot-agent-service/evals/reports/*.json` | 历史 Agent/RAG eval JSON |
| `copilot-agent-service/evals/reports/*.md` | 历史 Agent/RAG eval Markdown |
| `.codex/DOCKER_SMOKE_REPORT.md` | Docker Lite runtime 验收报告 |
| `docs/04-notes/测试总览与结果汇总.md` | 测试体系总览 |
| `docs/04-notes/企业级测试实践-集成测试与压测.md` | 集成测试和压测说明 |
| `docs/04-notes/AgentOps评测与GenAI追踪.md` | AgentOps 和 GenAI trace 说明 |

## 8. 本轮不重复实现的能力

- 不重写已有 Locust/k6 用户模型。
- 不替换现有 Prometheus/Grafana/Loki 栈。
- 不用另一个 LLM 对真实 Agent 输出做主观打分。
- 不把 Lite 环境的结果冒充完整 RocketMQ/Milvus/Elasticsearch 链路。
- 不提交大型 Locust HTML、原始日志、数据库文件或 Milvus `.db` 文件。
