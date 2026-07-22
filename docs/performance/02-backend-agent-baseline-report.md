# 后端与 Agent 性能基线报告

> 执行日期：2026-07-22  
> 分支：`test/performance-agent-baseline`  
> 基线：`main@659b2427178a07567f978541d94770407bed2b70`  
> 说明：本文只记录统计值和脱敏结论，不记录 API Key、完整 Prompt、完整回答或工具原始返回。

## 1. 执行环境

| 项 | 结果 |
| --- | --- |
| Docker | 28.1.1 |
| Docker Compose | 2.35.1 |
| Java | OpenJDK 17.0.19 |
| Python | 3.10.12 |
| LLM Provider | `deepseek` |
| LLM Model | `deepseek-v4-flash` |
| Compose profiles | `app`, `search`, `mq`, `rag`, `observability` |

## 2. Docker 与 Smoke

完整 Compose 首次启动失败，根因是 `zilliz/attu:v2.4.10` 镜像 tag 不存在。已将 Attu 固定到可解析的 `zilliz/attu:v2.4.12`。

修复后完整栈启动成功：

| 服务 | 验证结果 |
| --- | --- |
| `local-life-server` | `/actuator/health` = `UP`，MySQL/Redis/Elasticsearch 均 `UP` |
| `local-life-copilot` | `/actuator/health` = `UP` |
| `copilot-agent-service` | `/health` = `ok`，DeepSeek flash 配置生效 |
| `embedding-service` | `/health` = `ok` |
| `reranker-service` | `/health` = `ok` |
| `milvus` | Agent 日志显示 standalone 连接成功并完成知识库入库 |
| `rocketmq` | 容器启动 |
| `observability` | Grafana/Alertmanager 可达；demo smoke 中 Loki `/ready` 可选检查不可达 |

Smoke 结果：

| 命令 | 结果 |
| --- | --- |
| `bash scripts/e2e-smoke.sh` | 通过：Server/Copilot/Agent/MCP tools/list/Agent fast path |
| `bash scripts/demo-smoke.sh` | 通过：demo 数据、MCP query_order、Agent runtime replay、offline RAG |

## 3. 后端性能基线

数据准备：

| 命令 | 结果 |
| --- | --- |
| `bash scripts/seed-perf-data.sh` | 2000 用户、2 个秒杀场次、Redis 库存和验证码写入成功 |

首轮短基线产物：

- `artifacts/performance/backend-20260722-194032/`

| 场景 | 请求数 | 失败数 | P50 | P95 | P99 | RPS | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| 秒杀突发 | 1273 | 0 | 10 ms | 22 ms | 82 ms | 64.78 | 通过，claimed=38，未超卖 |
| 搜索热查询 | 122 | 0 | 19 ms | 35 ms | 840 ms | 6.19 | 通过但 P99 有冷抖动 |
| 混合读写 | 83 | 69 | 12 ms | 190 ms | 200 ms | 4.22 | 首轮失败，见问题记录 |
| MCP 工具 | 347 | 347 | 4 ms | 6 ms | 11 ms | 17.62 | 首轮失败，见问题记录 |
| k6 秒杀 | - | - | - | - | - | - | 本机未安装 `k6`，已标记 SKIPPED |

修复后重跑产物：

- `artifacts/performance/backend-rerun-20260722-194322/`

| 场景 | 请求数 | 失败数 | P50 | P95 | P99 | RPS | 结论 |
| --- | ---: | ---: | ---: | ---: | ---: | ---: | --- |
| MCP 工具 | 153 | 0 | 7 ms | 18 ms | 32 ms | 10.99 | 修复后通过 |
| 混合读写 | 35 | 18 | 9 ms | 27 ms | 51 ms | 2.50 | 仍失败，暴露公开笔记 NPE 和测试手机号问题 |

## 4. Agent DeepSeek 基线

真实基线产物：

- `artifacts/performance/agent-20260722-195927/deepseek-flash-real-baseline.json`
- `artifacts/performance/agent-20260722-195927/deepseek-flash-real-baseline.md`

用例：24 条抽样用例，覆盖 query/diagnosis/knowledge/boundary；每条执行 2 轮；并发组 1/3/5；总运行 144 次。

| 并发 | Runs | Success | Task Done | Tool Acc | Keyword | Latency P50 | Latency P95 | Latency P99 | TTFT P50 | TTFT P95 |
| ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: | ---: |
| 1 | 48 | 0.917 | 0.250 | 0.375 | 0.578 | 15198 ms | 28503 ms | 35013 ms | 188 ms | 264 ms |
| 3 | 48 | 0.917 | 0.229 | 0.354 | 0.575 | 21724 ms | 38738 ms | 41546 ms | 252 ms | 448 ms |
| 5 | 48 | 0.917 | 0.208 | 0.375 | 0.571 | 22501 ms | 42097 ms | 47374 ms | 357 ms | 678 ms |

解读：

- DeepSeek flash 链路可用，真实请求中能看到 `llm.invoke`、`mcp.rpc`、`tool.*`、`rag.*` span。
- TTFT 稳定在百毫秒级，但端到端延迟主要受多轮 LLM + RAG 影响。
- Success 不是 100% 的主要原因包含 Guardrails 对 prompt injection 的 HTTP 400 拦截，以及少量工具/Agent 错误。安全拦截在边界用例里是预期行为，但当前 runner 仍按 HTTP 错误计入，后续应把预期拒答从技术失败中分离。
- Task Done/Tool Acc 偏低，说明真实 Agent 与评测期望工具路径不完全一致，下一轮应调整评测集数据和 tool routing 期望。

## 5. RAG Benchmark

真实 RAG 产物：

- `artifacts/performance/agent-20260722-195927/real-rag-benchmark.json`
- `artifacts/performance/agent-20260722-195927/real-rag-benchmark.md`

| 指标 | 结果 |
| --- | ---: |
| Case count | 4 |
| Recall@5 before rerank | 1.000 |
| Recall@5 after rerank | 1.000 |
| Citation accuracy | 1.000 |
| Refusal accuracy | 1.000 |
| Avg rerank delta | 0.000 |

说明：当前 RAG benchmark 只有 4 条默认用例，适合作为冒烟基线，不足以作为正式质量门禁。后续应扩到至少 20-30 条，并增加 merchant scoped 文档、冲突文档和拒答边界。

## 6. 本轮发现与修复

| 严重级别 | 问题 | 根因 | 修复状态 |
| --- | --- | --- | --- |
| High | 完整 Compose 首次无法启动 | `zilliz/attu:v2.4.10` 不存在 | 已修复为 `v2.4.12` |
| High | MCP Locust 100% 401 | 压测脚本未带 `X-Agent-Timestamp` 和 `X-Agent-Signature` | 已补 HMAC 签名 |
| High | 公开笔记详情 500 | 未登录公开接口调用 `UserContext.getUserId()` 导致 NPE | 已修复：未登录 liked=false |
| Medium | 混合 Locust 登录 400 | 默认手机号与 demo/perf seed 的验证码不一致 | 已改默认 demo 手机号，可用环境变量覆盖 |
| Medium | k6 未运行 | 本机未安装 `k6` | 未修复；后续可用 Docker k6 或安装 k6 |
| Medium | 标准 server Docker build 卡住 | Maven build 阶段长时间无输出；本机 `clean compile` 通过 | 未完成；需后续重试或优化 Docker build 可观测性 |
| Low | Agent baseline 脚本相对路径失败 | `cd` 后相对 `OUT_DIR` 失效 | 已改为绝对路径 |
| Low | 本地 eval 读取 `DEBUG=release` 失败 | Pydantic 布尔解析不接受 `release` | 脚本内归一化非布尔 DEBUG 为 false |

## 7. 本轮新增工程入口

| 路径 | 作用 |
| --- | --- |
| `scripts/run-backend-perf-baseline.sh` | 编排 Locust/k6 后端与 MCP 基线，采集 Docker snapshot |
| `scripts/run-agent-deepseek-baseline.sh` | 编排 offline eval、offline RAG、真实 DeepSeek Agent、真实 RAG |
| `copilot-agent-service/evals/deepseek_baseline.py` | 真实 DeepSeek 并发基线 runner，输出脱敏统计 |
| `copilot-agent-service/rag/pipeline.py` | 新增 RAG 阶段 span：total、embedding、vector、bm25、reranker |

## 8. 下一步

1. 重新完成标准 `locallife-server` Docker build，并用新镜像验证 `GET /api/v1/posts/{id}` 未登录返回 200。
2. 扩充 RAG benchmark 至 20-30 条，覆盖商家隔离、冲突文档、拒答、引用错配。
3. 调整 Agent eval runner：把预期 Guardrails 拦截计为安全通过，而不是 HTTP 技术失败。
4. 用 Docker 版 k6 或安装本机 k6，补齐秒杀 k6 summary。
5. 扩展后端业务正确性 SQL 快照：幂等账本、重复支付、Outbox 租约、秒杀 reservation/stream。
