# LocalLife × LocalLife Copilot

> 本地生活内容交易平台（传统后端）+ 企业级 AI Agent（Copilot）双线项目

---

## 项目定位

| 线 | 项目 | 目标 |
|---|------|------|
| 传统后端 | `local-life-server` | 证明 Java 后端工程能力：MySQL、Redis、RocketMQ、ES、高并发、一致性 |
| AI 应用 | `local-life-copilot` + `copilot-agent-service` | 证明企业级 Agent 能力：MCP、LangGraph、RAG、HITL、Evals、Guardrails |

两条线共用同一套业务数据，不是两个孤立项目。

---

## 仓库结构

```
.
├── local-life-server/          Java Spring Boot 主服务（端口 8080）
├── local-life-copilot/         Java MCP Server，封装业务工具（端口 8081）
├── copilot-agent-service/      Python FastAPI Agent Service（端口 8000）
├── infra/                      Docker Compose 基础设施
│   ├── docker-compose.dev.yml  一键启动所有中间件
│   └── prometheus/             Prometheus 抓取配置
├── performance-tests/          Locust 压测脚本
└── docs/                       项目文档中心
    ├── 01-project/             架构/设计/规范文档（11 篇）
    ├── 02-environment/         环境搭建指南
    └── 04-notes/               深度教程（LocalLife + Copilot）
```

---

## 一键部署（Docker Compose，推荐）

```bash
cd infra
cp .env.example .env          # 填写 ANTHROPIC_API_KEY（唯一必填项）

# Linux/macOS
bash scripts/start.sh         # 自动完成：启动中间件→DB迁移→构建→等待就绪→打印地址

# Windows PowerShell
.\scripts\start.ps1           # 效果相同
```

**启动后访问：**
- 💬 Chat UI（商家/客服）：`http://localhost:8000/`
- ⚠️ HITL 审批工作台：`http://localhost:8000/approval`
- 📖 API 文档：`http://localhost:8000/docs`
- 📊 Grafana 监控：`http://localhost:3000`（admin/admin）

**可选中间件（按需启动）：**
```bash
docker compose -f infra/docker-compose.dev.yml --profile search up -d        # ES 搜索
docker compose -f infra/docker-compose.dev.yml --profile mq up -d            # RocketMQ
docker compose -f infra/docker-compose.dev.yml --profile observability up -d # Prometheus+Grafana+Zipkin
docker compose -f infra/docker-compose.dev.yml --profile rag up -d           # Milvus（知识库搜索）
docker compose -f infra/docker-compose.dev.yml --profile nginx up -d         # Nginx反向代理(:80)
```

---

## 手动启动（开发调试）

### 第一步：启动基础中间件

```bash
cd infra

# 核心中间件（MySQL + Redis）
docker compose -f docker-compose.dev.yml up -d

# 搜索（ES）
docker compose -f docker-compose.dev.yml --profile search up -d

# 消息队列（RocketMQ）
docker compose -f docker-compose.dev.yml --profile mq up -d

# 可观测性（Prometheus + Grafana + Zipkin）
docker compose -f docker-compose.dev.yml --profile observability up -d

# RAG 向量库（Milvus，仅 Copilot 需要）
docker compose -f docker-compose.dev.yml --profile rag up -d
```

### 第二步：初始化数据库

```bash
# 在 MySQL 中执行所有迁移脚本（按版本顺序）
mysql -uroot -p123456 local_life < local-life-server/src/main/resources/db/migration/V1__init_user.sql
mysql -uroot -p123456 local_life < local-life-server/src/main/resources/db/migration/V2__init_merchant_shop.sql
mysql -uroot -p123456 local_life < local-life-server/src/main/resources/db/migration/V3__init_post_comment_follow.sql
mysql -uroot -p123456 local-life-server/src/main/resources/db/migration/V4__init_coupon_seckill.sql
mysql -uroot -p123456 local_life < local-life-server/src/main/resources/db/migration/V5__init_order_payment.sql
mysql -uroot -p123456 local_life < local-life-server/src/main/resources/db/migration/V7__init_outbox_message.sql
mysql -uroot -p123456 local_life < local-life-server/src/main/resources/db/migration/V8__init_order_sharding.sql
# Copilot 专用表
mysql -uroot -p123456 local_life < local-life-copilot/src/main/resources/db/migration/V1__init_copilot_tables.sql
```

### 第三步：启动服务（顺序很重要）

```bash
# 1. 主服务（端口 8080）
cd local-life-server
mvn spring-boot:run

# 2. MCP Server（端口 8081，需主服务先启动）
cd local-life-copilot
mvn spring-boot:run

# 3. Agent Service（端口 8000，需 MCP Server 先启动）
cd copilot-agent-service
cp .env.example .env        # 填写 ANTHROPIC_API_KEY
pip install -r requirements.txt
uvicorn main:app --port 8000 --reload
```

### 第四步：验证启动

```bash
curl http://localhost:8080/actuator/health    # LocalLife Server
curl http://localhost:8081/actuator/health    # MCP Server
curl http://localhost:8000/health             # Agent Service

# 测试发送验证码
curl -X POST http://localhost:8080/api/v1/auth/code \
  -H "Content-Type: application/json" \
  -d '{"mobile":"13800138000"}'

# 测试 MCP tools/list
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" -H "X-User-Role: merchant" -H "X-Merchant-Id: 20001" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'

# 测试 Agent 对话（SSE）
curl -X POST http://localhost:8000/chat \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" -H "X-User-Role: merchant" -H "X-Merchant-Id: 20001" \
  -H "Accept: text/event-stream" \
  -d '{"message":"我今天卖了多少钱？","session_id":0}'
```

---

## 核心技术栈

### LocalLife Server

| 层 | 技术 | 用途 |
|---|------|------|
| 框架 | Spring Boot 3.5 + Java 17 | Web、DI、配置 |
| ORM | MyBatis-Plus 3.5.7 | CRUD、雪花 ID、逻辑删除 |
| 数据库 | MySQL 8（ShardingSphere 分表） | 主存储、订单分 4 片 |
| 缓存 | Redis 7 + Caffeine | L2 + L1 两级缓存、点赞计数、限流 |
| 消息 | RocketMQ 5 | Transactional Outbox、支付事件 |
| 搜索 | Elasticsearch 8 + IK 分词 | 门店/笔记全文检索、Geo 距离 |
| 可观测 | Micrometer + Prometheus + Zipkin | 指标、追踪、日志 traceId |
| 限流 | Redis ZSet 滑动窗口（Lua） | 8 个接口，防刷防暴力 |
| 分表 | ShardingSphere-JDBC 5.5 | order_info 按 user_id % 4 分片 |

### LocalLife Copilot

| 层 | 技术 | 用途 |
|---|------|------|
| 协议 | MCP JSON-RPC 2.0 | Agent 调用业务工具的标准协议 |
| Agent 编排 | LangGraph 0.2 + Python | ReAct 主循环、Checkpoint、中断 |
| LLM | Claude Sonnet 4.6（Anthropic） | 推理决策 |
| 向量库 | Milvus 2.4 | 知识库语义检索 |
| Embedding | multilingual-e5-base | 768 维中英双语向量 |
| Reranker | cross-encoder ms-marco-MiniLM | 精排 Top 20→Top 5 |
| Checkpoint | MySQL（自实现 AsyncMySQLSaver） | HITL 挂起后持久化，重启可恢复 |
| 流式输出 | SSE（Server-Sent Events） | 实时推送 Agent 执行每一步 |

---

## 文档导航

| 文档 | 说明 | 适合谁读 |
|------|------|---------|
| [项目总路线](docs/01-project/双线项目路线.md) | 双线整体规划 | 第一次看项目 |
| [接口规范文档](docs/01-project/10-接口规范文档.md) | 统一响应结构、错误码、鉴权、分页 | 开发时参照 |
| [Copilot 架构设计](docs/01-project/11-Copilot架构设计文档.md) | MCP+Agent+RAG+HITL 完整设计 | 理解 Copilot |
| [LocalLife 接口教程](docs/04-notes/LocalLife项目接口教程.md) | 11 章从请求链路到分表的深度教程 | 面试准备 |
| [Copilot 全链路教程](docs/04-notes/LocalLifeCopilot项目教程.md) | 12 章 MCP/LangGraph/RAG/HITL 教程 | 面试准备 |
| [环境搭建](docs/02-environment/01-环境搭建.md) | 工具链版本固定 | 初次配置 |
| [增强路线图](docs/01-project/09-项目增强路线图.md) | P0/P1/P2 增强项规划 | 后续迭代 |
| [PROGRESS.md](PROGRESS.md) | 所有已完成模块 + 关键决策 | 跨会话继续工作 |

---

## 面试价值点速查

### Java 高频追问

| 问题 | 答案入口 |
|------|---------|
| 秒杀怎么防超卖 | `SeckillService.java` + `seckill.lua` |
| MQ 消息怎么防丢失 | `OutboxService.java` + `V7__init_outbox_message.sql` |
| 支付回调怎么防重复 | `PaymentService.handleCallback()` 的 CAS UPDATE |
| 缓存和数据库怎么保持一致 | `ShopService.java` 的 Cache-Aside 模式 |
| 分库分表为什么选 user_id | `sharding.yaml` + `docs/04-notes/...` 第 11 章 |
| 限流滑动窗口怎么实现 | `rate_limit.lua` + `RateLimitInterceptor.java` |

### AI Agent 高频追问

| 问题 | 答案入口 |
|------|---------|
| 为什么选 MCP 而不是直接调 API | `docs/01-project/11-Copilot架构设计文档.md` 第 3 章 |
| HITL 怎么实现挂起和恢复 | `session/hitl.py` + `session/checkpointer.py` |
| Tool Router 的意义是什么 | `agent/tool_router.py` 注释 |
| RAG 怎么防止编造内容 | `rag/reranker.py` 的 0.3 阈值拒答 |
| 怎么评测 Agent 质量 | `evals/eval_cases.py` + `evals/metrics.py` |
