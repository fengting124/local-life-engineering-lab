# LocalLife 性能指标契约

> 本文定义后端与 Agent 第一轮性能基线统一指标。所有指标禁止包含 API Key、完整手机号、完整订单敏感信息、完整 Prompt、未脱敏工具返回和用户私密数据。

## 标签约定

| 标签 | 说明 | 示例 | 基数约束 |
| --- | --- | --- | --- |
| `service` | 服务名 | `local-life-server`, `local-life-copilot`, `copilot-agent` | 低 |
| `env` | 环境 | `dev`, `lite`, `perf` | 低 |
| `scenario` | 测试场景 | `mixed`, `seckill_spike`, `search_hot`, `agent_real_c3` | 低 |
| `endpoint` | 归一化接口 | `/api/v1/orders`, `/chat` | 中 |
| `method` | HTTP 方法 | `GET`, `POST` | 低 |
| `status` | 结果 | `ok`, `error`, `timeout`, `fallback` | 低 |
| `role` | 用户角色 | `merchant`, `cs`, `admin` | 低 |
| `provider` | LLM Provider | `deepseek` | 低 |
| `model` | 模型名 | `deepseek-v4-flash` | 低 |

## 后端接口指标

| 指标 | 定义 | 单位 | 数据来源 | 标签维度 | 采集位置 | 已实现 | 本轮新增 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `request_count` | 请求总数 | count | Locust/k6, Micrometer `http_server_requests_seconds_count` | service, scenario, endpoint, method, status | 压测报告、Prometheus | 是 | 否 |
| `requests_per_second` | 每秒请求数 | req/s | Locust/k6, PromQL `rate()` | service, scenario, endpoint | 压测报告、Prometheus | 是 | 否 |
| `error_count` | 非预期错误数 | count | Locust/k6 failures, HTTP 5xx/非预期 4xx | scenario, endpoint, status | 压测报告 | 是 | 否 |
| `error_rate` | `error_count / request_count` | ratio | Locust/k6 | scenario, endpoint | 压测报告 | 是 | 否 |
| `timeout_count` | 超时请求数 | count | Locust/k6, client timeout exception | scenario, endpoint | 压测 runner | 部分 | 是 |
| `rate_limited_count` | 限流响应数 | count | HTTP 429, 业务限流 metric | scenario, endpoint | Locust/k6, Prometheus | 部分 | 是 |
| `latency_p50_ms` | 请求延迟 P50 | ms | Locust/k6, histogram_quantile | scenario, endpoint | 压测报告 | 是 | 否 |
| `latency_p95_ms` | 请求延迟 P95 | ms | Locust/k6, histogram_quantile | scenario, endpoint | 压测报告 | 是 | 否 |
| `latency_p99_ms` | 请求延迟 P99 | ms | Locust/k6, histogram_quantile | scenario, endpoint | 压测报告 | 是 | 否 |
| `latency_max_ms` | 最大请求延迟 | ms | Locust/k6 | scenario, endpoint | 压测报告 | 是 | 否 |

## 业务正确性指标

| 指标 | 定义 | 单位 | 数据来源 | 标签维度 | 采集位置 | 已实现 | 本轮新增 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `duplicate_order_count` | 同一幂等 Key 产生的额外订单数 | count | SQL 聚合 | scenario | MySQL `order_info`, `order_idempotency` | 部分 | 是 |
| `idempotency_conflict_count` | 相同 Key 不同请求体被拒绝次数 | count | HTTP/SQL | scenario | API 响应、`order_idempotency` | 部分 | 是 |
| `duplicate_payment_success_count` | 同一订单多笔正常 SUCCESS 支付数 | count | SQL 聚合 | scenario | `payment_order` | 部分 | 是 |
| `duplicate_paid_count` | 被归类为 `DUPLICATE_PAID` 的支付数 | count | SQL | scenario | `payment_order` | 是 | 是 |
| `oversell_count` | 秒杀成功数超过库存的数量 | count | k6 summary + SQL/Redis | session_id | Redis, `user_coupon` | 部分 | 是 |
| `duplicate_coupon_claim_count` | 同用户同场次重复领券数 | count | SQL 聚合 | session_id | `user_coupon` | 部分 | 是 |
| `outbox_pending_count` | PENDING Outbox 数 | count | SQL | topic | `outbox_message` | 是 | 是 |
| `outbox_processing_count` | PROCESSING Outbox 数 | count | SQL | topic | `outbox_message` | 是 | 是 |
| `outbox_sent_count` | SENT Outbox 数 | count | SQL | topic | `outbox_message` | 是 | 是 |
| `outbox_failed_count` | FAILED Outbox 数 | count | SQL | topic | `outbox_message` | 是 | 是 |
| `outbox_oldest_age_seconds` | 最老未完成 Outbox 年龄 | seconds | SQL `TIMESTAMPDIFF` | topic | `outbox_message` | 否 | 是 |
| `expired_outbox_lease_count` | 租约已过期但仍 PROCESSING 的消息数 | count | SQL | topic | `outbox_message` | 否 | 是 |
| `seckill_stream_length` | Redis Stream 长度 | count | Redis `XLEN` | stream | Redis | 部分 | 是 |
| `reservation_pending_count` | PENDING reservation 数 | count | SQL | session_id | `seckill_reservation` | 是 | 是 |
| `reservation_confirmed_count` | CONFIRMED reservation 数 | count | SQL | session_id | `seckill_reservation` | 是 | 是 |
| `reservation_compensated_count` | COMPENSATED reservation 数 | count | SQL | session_id | `seckill_reservation` | 是 | 是 |
| `consumer_duplicate_effect_count` | 重复消费导致的重复业务副作用数 | count | SQL/业务核对 | topic | `user_coupon`, `payment_order` | 部分 | 是 |

## 资源指标

| 指标 | 定义 | 单位 | 数据来源 | 标签维度 | 采集位置 | 已实现 | 本轮新增 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `process_cpu_percent` | 容器/进程 CPU 使用率 | percent | `docker stats`, Prometheus cAdvisor 可选 | service | runner snapshot | 部分 | 是 |
| `process_memory_bytes` | 容器/进程内存使用 | bytes | `docker stats` | service | runner snapshot | 部分 | 是 |
| `jvm_heap_used_bytes` | JVM Heap 已用 | bytes | Micrometer JVM metrics | service | `/actuator/prometheus` | 是 | 否 |
| `jvm_gc_pause_seconds` | GC pause 分布 | seconds | Micrometer JVM metrics | service, action | `/actuator/prometheus` | 是 | 否 |
| `jvm_threads` | JVM 线程数 | count | Micrometer JVM metrics | service, state | `/actuator/prometheus` | 是 | 否 |
| `http_server_active_requests` | 当前活跃 HTTP 请求 | count | Micrometer | service, endpoint | `/actuator/prometheus` | 是 | 否 |
| `hikari_active_connections` | Hikari 活跃连接 | count | Micrometer Hikari | pool | `/actuator/prometheus` | 是 | 否 |
| `hikari_pending_connections` | 等待连接线程数 | count | Micrometer Hikari | pool | `/actuator/prometheus` | 是 | 否 |
| `mysql_query_duration` | SQL 查询耗时 | seconds/ms | 慢日志、应用 span、Prometheus 可选 | statement_type | MySQL/app logs | 部分 | 是 |
| `mysql_lock_wait` | MySQL 锁等待 | count/time | InnoDB status/performance_schema | table | MySQL snapshot | 否 | 是 |
| `redis_command_duration` | Redis 命令耗时 | seconds/ms | Lettuce/Micrometer 或 Redis slowlog | command | Prometheus/Redis | 部分 | 是 |
| `redis_connected_clients` | Redis 客户端连接数 | count | Redis `INFO clients` | instance | Redis snapshot | 否 | 是 |
| `redis_memory_used` | Redis 内存使用 | bytes | Redis `INFO memory` | instance | Redis snapshot | 否 | 是 |
| `elasticsearch_query_duration` | ES 查询耗时 | ms | 应用日志/Micrometer | index, query_type | search service | 部分 | 是 |
| `elasticsearch_rejected_count` | ES 线程池拒绝数 | count | ES `_nodes/stats/thread_pool` | pool | ES snapshot | 否 | 是 |
| `rocketmq_consumer_lag` | MQ 消费滞后 | count | RocketMQ admin/API | topic, group | MQ snapshot | 否 | 是 |

## Agent 质量指标

| 指标 | 定义 | 单位 | 数据来源 | 标签维度 | 采集位置 | 已实现 | 本轮新增 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `task_completion_rate` | 完成任务的用例比例 | ratio | Eval runner | category, concurrency | `copilot-agent-service/evals` | 是 | 是 |
| `tool_call_accuracy` | 期望工具集合/序列匹配度 | ratio | Eval runner | category | `evals.metrics` | 是 | 是 |
| `tool_argument_accuracy` | 工具参数与期望事实匹配比例 | ratio | Baseline runner | category, tool | 新增 Agent baseline 汇总 | 部分 | 是 |
| `factual_consistency_rate` | 回答事实与 DB/工具结果一致比例 | ratio | 确定性核对 | category | Eval report | 部分 | 是 |
| `citation_accuracy` | RAG 引用命中文档比例 | ratio | RAG benchmark | case_type | `evals.rag_benchmark` | 是 | 是 |
| `recall_at_5` | 期望文档出现在前 5 候选的比例 | ratio | RAG benchmark | retriever_mode | `evals.rag_benchmark` | 是 | 是 |
| `refusal_accuracy` | 应拒答场景正确拒答比例 | ratio | Eval/RAG benchmark | category | Eval report | 是 | 是 |
| `hitl_trigger_accuracy` | 高风险动作正确触发 HITL 比例 | ratio | Eval runner/runtime events | action_type | Agent eval | 是 | 是 |
| `permission_test_pass_rate` | 权限/注入用例通过比例 | ratio | Eval runner | role | Agent eval | 是 | 是 |
| `duplicate_side_effect_count` | Agent 重试/并发导致重复业务副作用数 | count | SQL/工具审计 | tool | MySQL + audit | 部分 | 是 |

## Agent 性能指标

| 指标 | 定义 | 单位 | 数据来源 | 标签维度 | 采集位置 | 已实现 | 本轮新增 |
| --- | --- | --- | --- | --- | --- | --- | --- |
| `ttft_ms` | `/chat` 请求到首个 SSE 事件的时间 | ms | SSE client | category, concurrency | Agent baseline runner | 否 | 是 |
| `end_to_end_latency_ms` | `/chat` 请求到 final/error/HITL 结束 | ms | SSE client | category, concurrency | Agent baseline runner | 是 | 是 |
| `guardrail_duration_ms` | 输入安全检查耗时 | ms | span/log | status | Agent API | 部分 | 是 |
| `llm_duration_ms` | 单次 LLM 调用耗时 | ms | `genai_span` | provider, model | `agent.trace` | 是 | 否 |
| `embedding_duration_ms` | embedding 调用耗时 | ms | span/log | status | RAG pipeline/client | 部分 | 是 |
| `vector_search_duration_ms` | 向量检索耗时 | ms | span/log | backend | RAG pipeline | 部分 | 是 |
| `bm25_duration_ms` | BM25 检索耗时 | ms | span/log | status | RAG pipeline | 部分 | 是 |
| `reranker_duration_ms` | reranker 调用耗时 | ms | span/log | status, fallback | RAG pipeline/client | 部分 | 是 |
| `rag_total_duration_ms` | RAG 总耗时 | ms | `copilot_rag_latency_seconds`, span | status | Agent metrics/log | 是 | 是 |
| `mcp_rpc_duration_ms` | MCP JSON-RPC 耗时 | ms | `genai_span` | rpc_method | `McpClient` | 是 | 否 |
| `tool_duration_ms` | 单工具耗时 | ms | Prometheus/audit/SSE | tool_name, status | Agent/Copilot | 是 | 否 |
| `java_api_duration_ms` | Copilot 调 Java internal API 耗时 | ms | Spring HTTP metrics/log | endpoint | local-life-copilot/server | 部分 | 是 |
| `checkpoint_duration_ms` | LangGraph checkpoint 写入耗时 | ms | checkpointer span/log | status | Agent session | 部分 | 是 |
| `event_persist_duration_ms` | agent_event 写入耗时 | ms | runtime_store span/log | event_type | Agent session | 部分 | 是 |
| `sse_duration_ms` | SSE 流持续时间 | ms | SSE client/API | category | Agent baseline runner | 否 | 是 |
| `retry_count` | 重试次数 | count | Runner/log | stage, reason | Agent baseline runner | 部分 | 是 |
| `model_call_count` | 模型调用次数 | count | Span/runtime | provider, model | Agent logs | 部分 | 是 |
| `tool_call_count` | 工具调用次数 | count | SSE/eval | tool_name | Agent eval | 是 | 否 |
| `input_tokens` | 输入 token 数 | tokens | LLM usage metadata | provider, model | `llm_response` | 部分 | 是 |
| `output_tokens` | 输出 token 数 | tokens | LLM usage metadata | provider, model | `llm_response` | 部分 | 是 |
| `total_tokens` | 输入+输出 token 数 | tokens | LLM usage metadata | provider, model | Eval report | 部分 | 是 |
| `estimated_cost` | 估算费用；无可靠单价时为 null | currency | 单价配置 + token | provider, model | baseline summary | 否 | 是 |

## 采集禁区

- 不采集或输出真实 API Key。
- 不采集完整手机号；只允许测试号段计数或脱敏前缀。
- 不保存完整订单敏感信息；只保存计数、状态和脱敏 ID。
- 不保存完整 Prompt 和完整 Agent 响应正文到性能汇总。
- 不把未脱敏工具返回写入报告。
- 不把真实 Key 注入 GitHub Actions、`.env.example`、Compose 文件或测试数据。

