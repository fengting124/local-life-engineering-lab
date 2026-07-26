# 后端与 Agent 性能基线报告

- Status: Active
- Type: Reference
- Owners: Project maintainers
- Last verified: 2026-07-26
- Source of truth: `artifacts/performance/`, `docs/performance/baseline-summary.json`, Docker and test command output

> 后端与 RAG 基线执行日期：2026-07-22
>
> Agent 合同基线执行日期：2026-07-26
>
> 分支：`fix/agent-eval-failure-classification`
>
> PR 基线：`main@659b242`
>
> 数据约束：只提交脱敏统计，不提交 API Key、完整 Prompt、原始回答或压测产物。

## 1. 执行环境

| 项 | 结果 |
| --- | --- |
| Docker / Compose | 28.1.1 / 2.35.1 |
| Java / Python | OpenJDK 17.0.19 / Python 3.10.12 |
| k6 | v2.1.0，安装于用户目录 |
| LLM | DeepSeek `deepseek-v4-flash`，OpenAI-compatible API |
| 完整依赖 | MySQL、Redis、Elasticsearch、RocketMQ、Milvus、Embedding、Reranker |

API Key 仅从被 Git 忽略的 `infra/.env` 注入。报告、Git diff 和日志检查均不得出现密钥。

## 2. Docker 构建与运行

标准 `local-life-server/Dockerfile` 已从当前工作区源码完成真实构建和容器替换，不是 runtime Dockerfile 复用旧 JAR。

| 验证 | 结果 |
| --- | --- |
| 冷依赖构建 | 548.55 s，成功 |
| 修改源码后的增量构建 | Maven 20.03 s，整镜像 42.51 s |
| 源码不变重建 | 4.72 s，全层命中缓存 |
| 最终业务修复镜像 | Maven 22.98 s，成功并健康启动 |
| Server health | `UP`，MySQL/Redis/Elasticsearch 均 `UP` |
| Agent health | healthy，实际加载 DeepSeek flash、Milvus、Embedding、Reranker |

原先所谓“标准 Docker build 卡住”不是 Docker daemon 故障，而是 ShardingSphere、RocketMQ 等冷依赖较大，网络下载慢且 Maven 长时间缺少可见输出。修复包括：缩小 build context、只复制 server 源码、使用 BuildKit Maven cache、输出 Maven 版本和阶段、构件下载重试，以及在当前 Docker 网络中改用实测更稳定的 Maven Central。

## 3. 后端性能基线

最终统一产物位于本地忽略目录：

- `artifacts/performance/backend-verified-20260722-215902/`
- `artifacts/performance/seckill-recovery-fix-20260722/`

Locust 场景：

| 场景 | 请求数 | 失败 | P50 | P95 | P99 | RPS |
| --- | ---: | ---: | ---: | ---: | ---: | ---: |
| 混合读写 | 154 | 0 | 11 ms | 22 ms | 52 ms | 5.34 |
| 搜索热查询 | 230 | 0 | 15 ms | 20 ms | 43 ms | 7.94 |
| MCP 工具 | 424 | 0 | 5 ms | 9 ms | 13 ms | 14.63 |
| 秒杀持续流量 | 1273 | 0 | 5 ms | 14 ms | 57 ms | 67.03 |

秒杀 spike 使用 50 VU、每 VU 一次领取，避免把同一用户无限循环误当成独立抢购：

| 指标 | 最终热身后结果 |
| --- | ---: |
| claimed | 50 |
| unexpected error | 0 |
| oversold | NO |
| 秒杀 P50 / P95 / max | 144.79 / 190.32 / 268.48 ms |
| k6 thresholds | 通过：P95 < 200 ms、P99 < 500 ms |

标准镜像首次启动后的两轮 P95 分别为 235.61 ms 和 224.56 ms，业务均成功但未过 200 ms 门槛；第三轮 JVM/JIT 热身后为 190.32 ms。正式容量结论不能只引用热身值，生产压测应包含预热阶段和持续稳态阶段。

### 秒杀一致性缺陷与验证

真实压测发现 `SeckillStreamRecoveryService` 捕获唯一键异常后，事务仍被 Spring 标记为 rollback-only，导致日志显示 recovered、reservation 实际全部回滚。修复为 recovery 专用的原子 `INSERT ... ON DUPLICATE KEY UPDATE` no-op，不再用异常表达幂等；正常业务 Outbox 写入仍保留严格插入。

修复后 50 次并发领取的事实核对：

- Redis 两个场次库存合计精确减少 50，用户集合合计 50。
- Redis Stream 写入 50 条压测事件。
- `seckill_reservation` 写入 50 条，不再出现 `UnexpectedRollbackException`。
- `outbox_message` 最终 `SENT=50`，`user_coupon=50`。
- MQ 先于 recovery 时 reservation 会短暂为 PENDING，由 5 分钟 reconciliation 根据已存在用户券转为 CONFIRMED；这是可观测的最终一致性窗口，不是立即一致。

## 4. DeepSeek Agent 基线

最终真实产物：

- `artifacts/performance/agent-eval-rbac-20260726-091015/deepseek-flash-eval-contract.json`

本轮只修复评测合同、失败分类和脱敏工具轨迹，不修改模型、Prompt、LangGraph 或生产 RBAC。完整依赖恢复健康后，使用 24 条用例、每条 2 轮、并发 1，共执行 48 次真实请求：

| 合同预检 | 结果 |
| --- | ---: |
| invalid_eval_contract | 0 |
| fixture 解析 | 47 / 47（100%） |
| 工具存在、角色权限与高风险 HITL 校验 | 通过 |

| 指标 | 结果 |
| --- | ---: |
| Transport success | 97.9% |
| Task completion | 50.0% |
| First-tool accuracy | 87.5% |
| Tool-argument accuracy | 100% |
| Trajectory accuracy | 60.4% |
| Final-fact accuracy | 95.8% |
| Permission accuracy | 89.6% |
| HITL accuracy | 95.8% |
| Refusal accuracy | 91.7% |
| Latency P50 / P95 / P99 | 13.68 / 36.16 / 58.61 s |
| Time to first SSE P50 / P95 | 114 / 181 ms |

`time_to_first_sse_ms` 只记录客户端收到第一行 SSE 的时间，不是模型首 token 延迟，报告不再把它标为 LLM TTFT。当前 SSE 仍不返回可信 usage，因此 token 和费用保持不可得。

逐 case 失败矩阵：

| Case | 第 1 轮 | 第 2 轮 |
| ---: | --- | --- |
| 2 | routing_failure | routing_failure |
| 3 | PASS | routing_failure |
| 16 | routing_failure | routing_failure |
| 18 | routing_failure | routing_failure |
| 19 | permission_failure | permission_failure |
| 20 | routing_failure | routing_failure |
| 21 | routing_failure | routing_failure |
| 31 | routing_failure | PASS |
| 32 | routing_failure | routing_failure |
| 33 | transport_failure | PASS |
| 37 | routing_failure | routing_failure |
| 47 | routing_failure | routing_failure |
| 49 | permission_failure | PASS |
| 50 | permission_failure | permission_failure |

其余 10 条用例两轮均通过。失败共 24 次：`routing_failure=18`、`permission_failure=5`、`transport_failure=1`，没有 `timeout`、`tool_execution_failure` 或 `invalid_eval_contract`。

新分类不再把 EvalCase allowlist/forbidlist 偏差记为权限失败：合同外但角色允许的工具属于路由质量，只有违反生产 `TOOL_ROLE_MAP` 才记为 permission。5 次权限失败均为 CS 调用 `knowledge_search`（Case 19 两次、49 一次、50 两次）。代码核对发现原生 `knowledge_search` 在 `ToolRouter` 完成过滤后被无条件追加，且本地执行路径没有二次角色校验；这是本轮评测暴露的生产权限缺口，应在独立修复 PR 中处理。

脱敏轨迹还暴露出 Case 3 第 2 轮共调用 28 次工具，其中 `shop_metrics_query` 连续出现 27 次；Case 31 第 1 轮重复检索并额外调用策略工具。P95/P99 的增长与这些额外轨迹一致。报告只保存工具名序列，不保存参数、Prompt、回答或工具返回。

旧报告的 25%-27% 来自关键词覆盖和单一工具匹配，且包含占位 ID 与角色冲突，不能与本轮 50.0% 直接比较。**本 PR 只建立可信的评测基准，不对外宣称 Agent 质量提升。**

## 5. RAG Benchmark

最终真实产物：

- `artifacts/performance/agent-retry-final-20260722-212514/real-rag-benchmark.json`

| 指标 | 结果 |
| --- | ---: |
| Case count | 24（含 4 条拒答） |
| Recall@5 before rerank | 1.000 |
| Recall@5 after rerank | 1.000 |
| Citation accuracy | 0.917 |
| Refusal accuracy | 1.000 |
| Avg rerank delta | 0.094 |

拒答指标来自真实 retrieval 结果，不是根据标签强行置空。两条 citation miss 暴露了证据跨 chunk 的问题；Recall@5 已满分时，下一步重点应是 chunk 边界和引用归因，而不是继续调大 top-k。

## 6. 测试与结论

| 验证 | 结果 |
| --- | --- |
| Agent 主测试套件 | 315 passed，覆盖率 65.19% |
| Agent mutation gate | 110 / 216 killed，50.9%，other=0 |
| Embedding 镜像测试 | 1 passed |
| Eval 合同、fixture、评分回归 | 新增并纳入主测试套件 |
| 标准 Java 镜像 | 构建成功、真实容器 healthy |
| 后端四场景 | 0 HTTP failure |
| k6 spike | 通过阈值、无超卖 |

整体状态为 **PARTIAL**：评测合同与 fixture 已通过预检，后端短基线和 24 条 RAG 基线保持有效；Agent 轨迹与生产角色权限仍不具备发布门槛，且本轮出现一次真实 API 传输失败。本轮是并发 1 的质量基线，不替代容量压测。

## 7. 下一轮优先级

1. 在独立 PR 中让 Python 原生工具进入与 MCP 工具相同的 RBAC 过滤，并在执行前再次 fail-closed 校验；增加 CS 无权调用 `knowledge_search` 的回归测试。
2. 为 Agent 增加全局工具调用预算和重复轨迹终止，优先复盘 Case 3 的 27 次连续查询与 18 次 routing failure。
3. 为 SSE 增加脱敏 token usage 统计，再建立单请求成本门槛。
4. 跑 10-30 分钟稳态压测，采集 CPU、内存、Hikari、Redis、MQ backlog，而不是只看短峰值。
5. 为 Stream recovery 增加真实数据库集成测试，覆盖“Outbox 已存在但 reservation 不存在”的事务场景。
