# LocalLife 性能测试指南

> 基于 Locust + k6 的端到端性能测试，覆盖 LocalLife Server 和 LocalLife Copilot 两条链路。

---

## 1. 测试目标

| 维度 | 目标 |
|------|------|
| **业务正确性** | 高并发下无超卖、无重复下单、无数据不一致 |
| **延迟** | 核心读接口 P99 < 50ms，写接口 P99 < 200ms |
| **吞吐量** | 单实例 1000 QPS（读路径 Redis 命中场景） |
| **稳定性** | 持续压测 30 分钟无内存泄漏、连接池耗尽、GC 风暴 |
| **限流有效性** | 超过限流阈值时正确返回 429，不影响其他用户 |

---

## 2. 测试场景

### LocalLife Server（`locustfile_locallife_server.py`）

| 用户类 | 模拟行为 | 关注点 |
|--------|---------|-------|
| `LocalLifeUser` | 浏览门店、笔记、下单、点赞 | 综合 P99、缓存命中率 |
| `SeckillUser` | 高并发抢券 | Redis Lua 原子性、超卖防护 |
| `SearchUser` | ES 关键词 + Geo 搜索 | ES 单节点吞吐、查询延迟 |

### LocalLife Copilot（`locustfile_copilot.py`）

| 用户类 | 模拟行为 | 关注点 |
|--------|---------|-------|
| `McpToolUser` | 直接调用 MCP 工具 | 工具层延迟、限流触发 |
| `AgentChatUser` | 完整 Agent 对话（SSE） | 端到端延迟、SSE 稳定性 |

---

## 3. 安装与运行

### 3.1 安装依赖

```bash
cd performance-tests
pip install -r requirements.txt
```

### 3.2 启动被测服务

```bash
# 必须先启动以下服务：
# 1. LocalLife Server      (端口 8080)
# 2. local-life-copilot    (端口 8081，仅压测 Copilot 时需要)
# 3. copilot-agent-service (端口 8000，仅压测 Agent 时需要)
# 4. MySQL / Redis / Elasticsearch（按场景需要）
```

### 3.3 执行压测

```bash
# 推荐：Web UI 模式（可实时查看 RPS、延迟分布、错误率）
locust -f locustfile_locallife_server.py --host http://localhost:8080
# 浏览器打开 http://localhost:8089

# 无头模式（CI 自动化）
locust -f locustfile_locallife_server.py \
  --host http://localhost:8080 \
  --users 100 --spawn-rate 10 --run-time 60s \
  --headless --html report.html

# 只跑秒杀场景（验证 Lua 原子性）
locust -f locustfile_locallife_server.py \
  --host http://localhost:8080 \
  --users 500 --spawn-rate 50 --run-time 30s \
  --headless --only-summary SeckillUser
```

### 3.4 k6 秒杀专项

```bash
# 安装 k6 后再运行；WSL/Ubuntu 可参考：
# sudo gpg -k || true
# curl https://dl.k6.io/key.gpg | sudo gpg --dearmor -o /usr/share/keyrings/k6-archive-keyring.gpg
# echo "deb [signed-by=/usr/share/keyrings/k6-archive-keyring.gpg] https://dl.k6.io/deb stable main" | sudo tee /etc/apt/sources.list.d/k6.list
# sudo apt update && sudo apt install k6

# 先准备压测账号、验证码和 Redis 秒杀库存
bash scripts/seed-perf-data.sh

# 从仓库根目录运行，报告会写到 performance-tests/reports/
k6 run performance-tests/k6/seckill.js

# 常用参数：用户数、运行时间、库存、场次
USERS=500 RUN_TIME=60s EXPECTED_STOCK=1000 \
SECKILL_SESSIONS=1:1,2:2 \
k6 run performance-tests/k6/seckill.js
```

k6 脚本会给登录请求带不同的 `X-Forwarded-For`，避免 `/auth/login` 的 IP 限流把压测入口先打红；秒杀接口本身仍然按真实用户限流执行。报告中的 `claimed_success` 必须小于等于 `EXPECTED_STOCK * 场次数`，否则就是超卖。

---

## 4. 性能基准（参考值）

> 基准环境：MacBook M2 Pro，本地 MySQL 8 + Redis 7 + ES 8 单节点

### 4.1 LocalLife Server

| 接口 | P50 | P99 | RPS（单实例） |
|------|-----|-----|--------------|
| `GET /api/v1/shops/{id}`（Redis 命中） | 5ms | 18ms | 3000 |
| `GET /api/v1/shops/{id}`（Redis miss） | 12ms | 35ms | 1500 |
| `GET /api/v1/posts/{id}` | 8ms | 25ms | 2500 |
| `GET /api/v1/coupons/templates` | 6ms | 20ms | 2800 |
| `GET /api/v1/orders`（ShardingSphere） | 15ms | 45ms | 1200 |
| `POST /api/v1/seckill`（Lua 主路径） | 8ms | 28ms | 4000+ |
| `POST /api/v1/posts/{id}/likes`（Redis INCR） | 4ms | 12ms | 5000+ |
| `GET /api/v1/search/shops?keyword=X` | 35ms | 120ms | 600 |
| `GET /api/v1/search/shops?lat&lon`（Geo） | 50ms | 180ms | 400 |

### 4.2 LocalLife Copilot

| 接口 | P50 | P99 | 备注 |
|------|-----|-----|------|
| `POST /mcp tools/list` | 8ms | 20ms | 工具 Schema 列表 |
| `POST /mcp tools/call query_order` | 15ms | 45ms | 单表查询 + JOIN |
| `POST /mcp tools/call shop_metrics_query` | 25ms | 80ms | 聚合查询 |
| `POST /chat`（简单查询，1 步） | 2.5s | 6s | 包含 1 次 LLM + 1 次工具 |
| `POST /chat`（订单排查，3-5 步） | 8s | 25s | 多步 ReAct 循环 |
| `POST /chat` TTFB（首字节） | 0.8s | 2s | LLM 第一次推理 |

---

## 5. 瓶颈定位指南

### 5.1 MySQL 瓶颈

**症状**：P99 突然飙升到 500ms+，CPU 高但 QPS 上不去。

**排查步骤**：
```bash
# 1. 查看 MySQL 慢查询
SELECT * FROM mysql.slow_log ORDER BY query_time DESC LIMIT 10;

# 2. 看锁等待
SHOW ENGINE INNODB STATUS;

# 3. 看连接池
SELECT * FROM information_schema.processlist;
```

**常见原因**：
- 缺索引（用 `EXPLAIN` 确认走索引）
- 长事务持有行锁（订单状态机更新）
- 连接池满（HikariCP `maximumPoolSize` 太小）

### 5.2 Redis 瓶颈

**症状**：缓存命中率正常但接口慢。

**排查**：
```bash
# Redis 慢日志
redis-cli SLOWLOG GET 10

# 看是否有大 Key
redis-cli --bigkeys
```

**常见原因**：
- 大 Key（单 Key > 10KB）
- Lua 脚本执行时间过长（>10ms）
- 连接池满（Lettuce `max-active` 太小）

### 5.3 JVM 瓶颈

**症状**：周期性 P99 抖动，GC 时间长。

**排查**：
```bash
# 看 GC 日志（启动时加 -Xlog:gc*）
# 看堆内存
jstat -gcutil <pid> 1000 10
```

**常见原因**：
- 老年代频繁 Full GC（缓存对象太大）
- Eden 区太小（年轻代 GC 频繁）
- 工具调用结果未释放（ThreadLocal 泄漏）

### 5.4 LLM 瓶颈（Copilot）

**症状**：Agent 端到端延迟超过 30 秒。

**排查**：
- 检查 LLM 响应时间（`structlog` 输出 `llm_response.token_usage`）
- 检查 ReAct 步数（超过 10 步通常是循环）
- 检查 Tool Router 过滤效果（工具太多导致 LLM 推理慢）

**优化方向**：
- Tool Router 缩小工具集（5-8 个工具最优）
- System Prompt 精简（每 100 token 增加 ~50ms 延迟）
- 切换更快模型（claude-haiku 用于简单查询）

---

## 6. 调优建议

### 6.1 LocalLife Server

| 调优项 | 建议值 | 影响 |
|--------|--------|------|
| HikariCP `maximumPoolSize` | CPU 核数 × 2 + 1 | DB 连接数上限 |
| Lettuce `max-active` | 16-32 | Redis 并发数上限 |
| Tomcat `max-threads` | 200-400 | HTTP 并发上限 |
| JVM `-Xmx` | 容器内存 × 75% | 堆大小 |
| JVM `-XX:+UseG1GC` | 启用 | G1 适合低延迟场景 |

### 6.2 LocalLife Copilot

| 调优项 | 建议值 | 影响 |
|--------|--------|------|
| `agent_max_steps` | 12-15 | 单会话最大推理步数 |
| `session_token_budget` | 50000 | 单会话 Token 上限 |
| `tool_rate_limit_per_minute` | 100 | 工具调用频率 |
| `mcp_timeout_seconds` | 5-10 | 工具单次超时 |
| uvicorn `workers` | CPU 核数 | FastAPI 进程数 |

---

## 7. 持续性能监控

### 7.1 Prometheus 关键指标

```promql
# HTTP P99 延迟
histogram_quantile(0.99, sum(rate(http_server_requests_seconds_bucket[5m])) by (le, uri))

# 工具调用次数
sum(rate(mcp_tool_calls_total[5m])) by (tool_name)

# 限流触发次数
sum(rate(rate_limit_triggered_total[5m]))

# Agent 完成率（六项指标之一）
sum(rate(agent_session_completed_total[5m])) / sum(rate(agent_session_started_total[5m]))
```

### 7.2 Grafana Dashboard

详见 `infra/grafana/dashboards/`（待补充）。

核心面板：
- P99 延迟趋势（按接口分组）
- RPS 和错误率
- DB 连接池使用率
- Redis 命中率
- LLM Token 消耗
- Agent 步数分布
