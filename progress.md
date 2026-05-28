# 项目进度与任务追踪

> 此文件记录所有已完成模块、当前任务和待办事项，方便跨会话继续工作。
> 最后更新：2026-05-28

---

## 快速状态总览

| 线 | 模块 | 状态 | 对应提交 |
|----|------|------|---------|
| LocalLife Server | 鉴权（登录/Token/AuthInterceptor） | ✅ | `feat: add project foundation and auth module` |
| LocalLife Server | 门店（CRUD/状态机/Geo） | ✅ | `feat: add merchant & shop module` |
| LocalLife Server | 内容（笔记/评论/点赞/Feed流） | ✅ | `feat: add post, comment & follow module` |
| LocalLife Server | 优惠券 & 秒杀（Redis Lua 预扣） | ✅ | `feat: add coupon & seckill module` |
| LocalLife Server | 订单 & 支付（状态机/幂等/延迟关单） | ✅ | `feat: add order & payment module` |
| LocalLife Server | RocketMQ（Transactional Outbox） | ✅ | `feat: add RocketMQ message reliability` |
| LocalLife Server | Elasticsearch 全文搜索 | ✅ | `feat: add Elasticsearch search module` |
| LocalLife Server | 可观测性（Micrometer + Prometheus + Zipkin） | ✅ | `feat: add observability` |
| LocalLife Server | 接口限流（Redis 滑动窗口 + @RateLimit） | ✅ | `feat: add rate limiting module` |
| LocalLife Server | ShardingSphere 订单分表 | ✅ | `feat: add ShardingSphere order table sharding` |
| LocalLife Copilot | Java MCP Server（完整工具 + RBAC + Audit） | ✅ | 见下方提交 |
| LocalLife Copilot | Python Agent Service（完整 HITL + Evals + Guardrails） | ✅ | 见下方提交 |
| 文档 | 接口规范文档（12章） | ✅ | `docs: finalize interface spec` |
| 文档 | 接口教程（11章） | ✅ | 含于各模块提交 |
| 文档 | Copilot 架构设计文档 | ✅ | 未提交 |

---

## LocalLife Server — 各模块完成详情

### 模块一：鉴权（Auth）

**核心文件**：
- `local-life-server/src/main/java/.../module/auth/controller/AuthController.java`
- `local-life-server/src/main/java/.../module/auth/service/AuthService.java`
- `local-life-server/src/main/java/.../common/interceptor/AuthInterceptor.java`
- `local-life-server/src/main/java/.../common/context/UserContext.java`

**关键设计决策**：
- Token = 随机 UUID，存 Redis（TTL 7天）—— 选择 Redis Token 而非 JWT 的原因：支持主动踢出
- AuthInterceptor 鉴权失败返回 401，通过 `response.getWriter().write()` 直接写响应
- `/api/v1/auth/**` 等白名单在 WebMvcConfig.excludePathPatterns 配置

### 模块二：门店（Shop）

**核心文件**：
- `local-life-server/src/main/java/.../module/shop/service/ShopService.java`
- `local-life-server/src/main/resources/db/migration/V2__init_merchant_shop.sql`

**关键设计决策**：
- 门店状态机：PENDING → ONLINE ⇄ OFFLINE（商家/平台手动切换）
- 价格以「分」存储（INT 类型），禁止 float
- Geo 查询：Shop 实体含 latitude/longitude，Redis GEO 辅助

### 模块三：内容（Post/Comment/Follow/Feed）

**核心文件**：
- `local-life-server/src/main/java/.../module/post/service/PostService.java`
- `local-life-server/src/main/java/.../module/post/controller/PostController.java`

**关键设计决策**：
- 点赞数存 Redis（`post:like:count:{postId}`），不实时写 DB，定时快照
- 共同关注：Redis Set SINTER 操作
- Feed 流游标分页：ZSet score=时间戳，按 `lastId` 滚动

### 模块四：优惠券 & 秒杀

**核心文件**：
- `local-life-server/src/main/java/.../module/seckill/service/SeckillService.java`
- `local-life-server/src/main/resources/lua/seckill.lua`

**关键设计决策**：
- Lua 脚本三步原子操作：SADD 判重 + DECRBY 判库存 + 条件 ZADD 记录成功者
- 秒杀场次 Redis Key：`seckill:stock:{sessionId}:{couponTemplateId}`
- 预扣成功后同步写 DB（未来升级为 MQ 异步，面试时可以说清升级路径）

### 模块五：订单 & 支付

**核心文件**：
- `local-life-server/src/main/java/.../module/order/service/OrderService.java`
- `local-life-server/src/main/java/.../module/order/service/PaymentService.java`
- `local-life-server/src/main/java/.../domain/mapper/OrderInfoMapper.java`

**关键设计决策**：
- 订单状态机 CAS：`UPDATE ... WHERE order_status = 'WAIT_PAY'`，affected=0 则幂等跳过
- 一订单多支付单设计：每次点「去支付」创建新 payment_order
- 支付回调幂等：DB 唯一索引 `uk_payment_channel_trade_no`
- 延迟关单：@Scheduled 60s 轮询（升级路径：RocketMQ 延时消息）
- ShardingSphere 后所有 UPDATE 带 userId 精准路由（在 `markAsPaid`/`updateStatusFromWaitPay` 中）

### 模块六：RocketMQ 消息可靠性

**核心文件**：
- `local-life-server/src/main/java/.../module/mq/service/OutboxService.java`
- `local-life-server/src/main/java/.../module/mq/consumer/PaymentSuccessConsumer.java`
- `local-life-server/src/main/resources/db/migration/V7__init_outbox_message.sql`

**关键设计决策**：
- Transactional Outbox：payment_order 更新 + outbox_message 写入同一事务
- Relay 任务：10s 定时扫描 PENDING，syncSend 到 RocketMQ
- 指数退避：10s → 30s → 60s，超 3 次标记 FAILED 人工处理
- 消费者幂等：Redis SETNX `consume:payment_success:{eventId}` TTL 24h

### 模块七：Elasticsearch 全文搜索

**核心文件**：
- `local-life-server/src/main/java/.../module/search/service/ShopSearchService.java`
- `local-life-server/src/main/java/.../module/search/service/PostSearchService.java`
- `local-life-server/src/main/resources/es/shop-index-settings.json`

**关键设计决策**：
- Geo 查询用 `criteria.within(GeoPoint, "5km")` 字符串格式（非 Distance 对象）
- GeoPoint 坐标：`"纬度,经度"` 顺序（lat first）
- 双写策略：Service 层手动 syncShop/syncPost（未来 Canal 替换）

### 模块八：可观测性

**核心文件**：
- `local-life-server/src/main/java/.../common/metrics/BusinessMetrics.java`
- `local-life-server/src/main/java/.../common/filter/TraceIdFilter.java`
- `local-life-server/src/main/java/.../config/ObservabilityConfig.java`

**关键设计决策**：
- TraceIdFilter → MDC.put("traceId", ...) → 日志 `%X{traceId}` 格式
- Prometheus 用 `access: unrestricted`（Spring Boot 3.4+ 替换 enabled: true）
- Zipkin 配置：`management.zipkin.tracing.endpoint`（原 spring.zipkin 已废弃）

### 模块九：接口限流

**核心文件**：
- `local-life-server/src/main/java/.../common/ratelimit/RateLimit.java`
- `local-life-server/src/main/java/.../common/ratelimit/RateLimitInterceptor.java`
- `local-life-server/src/main/resources/lua/rate_limit.lua`

**关键设计决策**：
- ZSet 滑动窗口：score=时间戳（毫秒），ZREMRANGEBYSCORE 清过期记录
- Lua 脚本原子性：ZREMRANGEBYSCORE + ZCARD + ZADD 不被并发打断
- keyType=USER 时 `UserContext.get()` 可能为 null（白名单接口降级为 IP）

### 模块十：ShardingSphere 订单分表

**核心文件**：
- `local-life-server/src/main/resources/sharding.yaml`
- `local-life-server/src/main/resources/application.yml`（url 改为 jdbc:shardingsphere:...）
- `local-life-server/src/main/resources/db/migration/V8__init_order_sharding.sql`

**关键设计决策**：
- 分片键 user_id 而不是 order_id：「我的订单」查询只命中单分片
- ShardingSphere 5.5.0 排除 `shardingsphere-test-util`（打包 Bug，不在 Maven Central）
- 所有 UPDATE 语句加 `user_id = #{userId}` 条件精准路由，避免广播

---

## LocalLife Copilot — 当前进度

### 已完成

**Java MCP Server（local-life-copilot/）**：
- MCP JSON-RPC 2.0 协议层（McpController + McpRequest/Response/Error + ToolDefinition）
- RBAC 身份注入（RbacFilter + RbacContext / ThreadLocal）
- 工具基础设施（McpTool 接口 + ToolRegistry 自动注册）
- 工具（QueryOrderTool / ShopMetricsQueryTool / QueryCouponIssueLogTool / ExecuteRefundTool）
- 审计（ToolAuditLog + ToolAuditMapper + ToolAuditService / @Async）
- DB 迁移（V1：agent_session / agent_message / langgraph_checkpoint / tool_audit_log / hitl_approval）

**Python Agent Service（copilot-agent-service/）**：
- FastAPI + uvicorn 骨架（main.py / api/chat.py）
- 配置（config/settings.py）
- MCP HTTP Client（mcp/mcp_client.py / httpx 异步 / McpToolError 结构化）
- LangGraph 状态（agent/state.py / AgentState TypedDict 含全部字段）
- LangGraph 图（agent/graph.py / 路由条件 + 6 种终止条件）
- 节点（agent/nodes.py / llm_node/tool_node/reflection_node/hitl_node/final_node）
- SSE 端点（astream_events → SSE 事件类型：agent_step/tool_call/tool_result/final_answer）

### 已完成（生产级）

**Java MCP Server**：
- ✅ MCP JSON-RPC 2.0 协议层（McpController + DTOs + 结构化错误）
- ✅ RBAC（RbacFilter + ThreadLocal）+ ToolRegistry 自动注册
- ✅ 9 个生产级工具（query_order/payment/coupon_issue_log/mq_dead_letter/
  shop_metrics/coupon_policy/campaign_draft/execute_refund/issue_compensation_coupon）
- ✅ CopilotOrderMapper + CopilotCouponMapper（真实 DB JOIN 查询）
- ✅ ToolRateLimiter（Redis 计数：100次/分钟/工具 + 30次/分钟/用户）
- ✅ LocalLifeInternalClient（HTTP 调用主服务 /internal/* API）
- ✅ ToolAuditService（@Async 异步写 tool_audit_log）

**Python Agent Service**：
- ✅ LangGraph StateGraph + 5 节点 + 6 种终止条件
- ✅ AsyncMySQLCheckpointer（替换 MemorySaver，支持服务重启后恢复 HITL）
- ✅ ToolRouter（按 role + task type + context 三层过滤）
- ✅ SSE 端点 + Session/Message 持久化
- ✅ HITL 完整流程（DB 写 + /chat/resume 恢复 + 审批工作台 API）
- ✅ Guardrails（12+ 输入注入规则 + 输出脱敏）
- ✅ RAG 完整流水线（multilingual-e5 + Milvus + Cross-Encoder Reranker + 权限过滤）
- ✅ knowledge_search Python 原生工具（绕过 MCP，本地向量检索）
- ✅ Evals 50 条评测集 + 6 项指标自动化脚本

**LocalLife Server 主服务配套**：
- ✅ InternalController + InternalService（/internal/orders/{n}/refund + compensate-coupon）
- ✅ X-Internal-Key 验证（防止外部调用）

**基础设施 & 测试**：
- ✅ Docker Compose 完整环境（MySQL/Redis/ES/MQ + Milvus/Prometheus/Grafana/Zipkin）
- ✅ Locust 性能测试（LocalLifeServer + Copilot 两套场景）
- ✅ 性能基准 + 瓶颈定位 + 调优指南

---

## 文档目录

| 文档 | 路径 | 说明 |
|------|------|------|
| 文档索引 | `docs/01-project/00-文档索引.md` | 所有文档入口 |
| 项目边界 | `docs/01-project/03-项目边界文档.md` | 功能边界定义 |
| 领域模型 | `docs/01-project/04-领域模型文档.md` | 核心实体关系 |
| ER 图 | `docs/01-project/05-ER图文档.md` | 数据库表结构 |
| 时序图 | `docs/01-project/06-核心时序图文档.md` | 关键流程时序 |
| Copilot 设计蓝图 | `docs/01-project/07-Copilot企业级Agent设计.md` | AI 应用原始设计 |
| 技术选型 | `docs/01-project/08-技术选型文档.md` | 技术栈选择依据 |
| 增强路线图 | `docs/01-project/09-项目增强路线图.md` | P0/P1/P2 优先级 |
| 接口规范 | `docs/01-project/10-接口规范文档.md` | 统一接口约定 |
| Copilot 架构 | `docs/01-project/11-Copilot架构设计文档.md` | 详细架构 + 代码结构 |
| 接口教程 | `docs/04-notes/LocalLife项目接口教程.md` | 11 章全链路教程 |
| 进度追踪 | `PROGRESS.md`（本文件） | 任务状态 + 关键决策 |

---

## 常见 Bug 备忘

| Bug | 原因 | 修复方式 |
|-----|------|---------|
| `shardingsphere-test-util` Missing artifact | SS 5.5.0 打包 Bug | `<exclusion>` 排除 |
| ES `within(GeoPoint, Distance)` 编译失败 | 实际 API 签名是 `within(GeoPoint, String)` | 用 `"5km"` 字符串 |
| `UserContext.getUserIdOrNull()` 找不到 | 方法不存在 | 用 `UserContext.get()` + null 判断 |
| Redis ZSet 同一毫秒两个请求计数只有 1 | ZADD member=时间戳会覆盖 | 加随机后缀或 UUID |
| Spring Boot 3.4+ Prometheus `enabled` deprecated | API 变更 | 改为 `access: unrestricted` |
| `management.zipkin` unknown property | `spring.zipkin` 已废弃 | 用 `management.zipkin.tracing.endpoint` |
