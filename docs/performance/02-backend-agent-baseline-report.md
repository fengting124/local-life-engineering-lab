# 后端与 Agent 性能基线报告

- Status: Active
- Type: Reference
- Owners: Project maintainers
- Last verified: 2026-07-22
- Source of truth: `artifacts/performance/`, `docs/performance/baseline-summary.json`, Docker and test command output

> 执行日期：2026-07-22  
> 分支：`test/performance-agent-baseline`  
> 基线：`main@659b2427178a07567f978541d94770407bed2b70`  
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

- `artifacts/performance/agent-retry-final-20260722-212514/deepseek-flash-real-baseline.json`

24 条用例、每条 2 轮、并发 1/3/5，共 144 次真实请求：

| 并发 | Runs | Success | Task Done | Tool Acc | Keyword | P50 | P95 | P99 | TTFT P50 | TTFT P95 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 48 | 100% | 25.0% | 37.5% | 61.5% | 15.33 s | 28.49 s | 35.62 s | 190 ms | 262 ms |
| 3 | 48 | 100% | 27.1% | 37.5% | 63.7% | 19.71 s | 38.20 s | 46.02 s | 323 ms | 449 ms |
| 5 | 48 | 100% | 27.1% | 37.5% | 64.6% | 20.14 s | 48.28 s | 59.39 s | 490 ms | 830 ms |

DeepSeek 的两个真实问题及修复：

1. DeepSeek 的 OpenAI-compatible 接口要求 assistant `tool_calls` 后紧跟对应 ToolMessage。图在 reflection/compaction 时可能打断该序列，产生 HTTP 400；现先处理 pending tool calls。
2. 基线中复现 5 次 `RemoteProtocolError/incomplete chunked read`。`llm_node` 只对 `httpx.TransportError` 做最多 3 次重试，不重试鉴权、Guardrail 或业务错误；5 次均恢复。

预期的 prompt injection/越权拒绝现在按稳定错误码计为安全通过，不再误算成技术失败。每个 run 使用独立评测用户，避免评测器自身触发用户限流。

**重要结论：100% success 只代表传输和预期拒绝分类稳定，不代表 Agent 质量达标。** Task Done 仅 25%-27%，Tool Acc 仅 37.5%，端到端 P95 在并发 5 时达到 48.28 s。下一阶段应优化工具路由、减少无效反思轮次，并逐条校准评测期望。

当前 SSE 不返回可信 usage，因此 token 和费用仍标记为不可得，不能估算或虚构。

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
| Agent 主镜像测试 | 277 passed |
| Embedding 镜像测试 | 1 passed |
| DeepSeek 错误分类回归 | 3 passed |
| 标准 Java 镜像 | 构建成功、真实容器 healthy |
| 后端四场景 | 0 HTTP failure |
| k6 spike | 通过阈值、无超卖 |

整体状态为 **PARTIAL**：Docker 构建、后端短基线、DeepSeek 传输恢复和 24 条 RAG 基线均已闭环；Agent 任务完成率与工具准确率仍不具备生产发布门槛，且本轮是轻量基线，不替代长稳压测、故障注入和容量规划。

## 7. 下一轮优先级

1. 将失败 Agent case 按“未调用工具、调用错工具、答案缺关键事实、循环过长”聚类并逐类修复。
2. 为 SSE 增加脱敏 token usage 统计，再建立单请求成本门槛。
3. 跑 10-30 分钟稳态压测，采集 CPU、内存、Hikari、Redis、MQ backlog，而不是只看短峰值。
4. 为 Stream recovery 增加真实数据库集成测试，覆盖“Outbox 已存在但 reservation 不存在”的事务场景。
