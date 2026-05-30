# local-life-copilot（Java MCP Server）

> 将 LocalLife 业务能力封装为标准化 MCP 工具 | 端口 8081

---

## 职责定位

```
Python Agent Service（:8000）
    ↓ MCP JSON-RPC 2.0  POST /mcp
local-life-copilot MCP Server（:8081）  ← 本服务
    ↓ JDBC（共享同一 MySQL）
LocalLife 主数据库（local_life）
```

**本服务只做三件事**：
1. 把业务数据（订单/支付/门店/券）封装成 Agent 可调用的工具
2. 执行 RBAC 校验，防止跨商家越权
3. 记录工具调用审计日志，并对高频调用限流

---

## 快速启动

```bash
# 前提：local-life-server 已在 8080 运行（共享 MySQL + Redis）
cd local-life-copilot
mvn spring-boot:run

# 验证健康
curl http://localhost:8081/actuator/health

# 测试工具列表
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 10001" \
  -H "X-User-Role: merchant" \
  -H "X-Merchant-Id: 20001" \
  -d '{"jsonrpc":"2.0","id":"1","method":"tools/list","params":{}}'

# 测试查询订单
curl -X POST http://localhost:8081/mcp \
  -H "Content-Type: application/json" \
  -H "X-User-Id: 99999" \
  -H "X-User-Role: cs" \
  -d '{"jsonrpc":"2.0","id":"2","method":"tools/call","params":{"name":"query_order","arguments":{"order_id":"1234567890"}}}'
```

---

## MCP 协议说明

所有请求发到 `POST /mcp`，Body 是 JSON-RPC 2.0 格式。

### 支持的方法

| method | 说明 |
|--------|------|
| `initialize` | 握手，返回服务能力声明 |
| `tools/list` | 返回所有可用工具及 Schema |
| `tools/call` | 执行工具调用 |

### 必须携带的 Header

| Header | 必填 | 说明 |
|--------|------|------|
| `X-User-Id` | ✅ | 调用者用户 ID |
| `X-User-Role` | ✅ | 角色：`merchant` / `cs` / `admin` |
| `X-Merchant-Id` | merchant 角色必填 | 商家 ID，服务端强制注入，Agent 不能伪造 |
| `X-Session-Id` | 可选 | 会话 ID，写入审计日志 |
| `X-Thread-Id` | 可选 | LangGraph thread ID，写入审计日志 |

### 结构化错误响应

```json
{
  "jsonrpc": "2.0",
  "id": "req-001",
  "error": {
    "code": -32602,
    "message": "parameter_error",
    "data": {
      "reason": "parameter_error",
      "detail": "order_id 不能为空",
      "hint": "order_id 格式：纯数字字符串（雪花 ID）"
    }
  }
}
```

| reason | Agent 行为 |
|--------|----------|
| `parameter_error` | 根据 hint 修正参数后重试 |
| `permission_denied` | 停止调用，说明权限边界 |
| `not_found` | 换工具补充查询 |
| `tool_timeout` | 重试一次，仍失败则降级 |
| `business_conflict` | 先查询确认状态再决策 |

---

## 工具清单（10 个）

### L1 只读查询（merchant/cs/admin 均可调用）

| 工具名 | 说明 | 主要查询表 |
|--------|------|---------|
| `query_order` | 订单+支付+券三合一状态 | order_info + payment_order + user_coupon |
| `query_payment` | 订单所有支付单历史 | payment_order |
| `query_coupon_issue_log` | 券发放 Outbox 状态 + 自动诊断 | outbox_message |
| `query_mq_dead_letter` | MQ 死信分析 | outbox_message (FAILED) |
| `shop_metrics_query` | 门店 GMV/订单数/核销量 | order_info + shop |
| `coupon_policy_lookup` | 券模板规则详情 | coupon_template + shop |

### L1 只读查询（仅 cs/admin）

| 工具名 | 说明 |
|--------|------|
| `query_coupon_issue_log` | 含 Outbox 状态，商家无权看 MQ 内部状态 |
| `query_mq_dead_letter` | 同上 |
| `query_payment` | 支付渠道流水，商家无权查 |

### L2 草稿生成（merchant/admin）

| 工具名 | 说明 |
|--------|------|
| `campaign_draft_generate` | 根据经营数据自动生成活动草稿（不写库，需用户确认） |

### L1 知识检索（merchant/cs/admin）

| 工具名 | 实现位置 | 说明 |
|--------|---------|------|
| `coupon_policy_lookup` | Java MCP | DB 查询券规则 |
| `knowledge_search` | Python Agent（RAG） | Milvus 向量检索，不经 MCP |

### L4 高风险执行（仅 cs/admin，必须 HITL）

| 工具名 | 说明 | 调用链 |
|--------|------|-------|
| `execute_refund` | 退款 | Agent → MCP → LocalLifeInternalClient → LocalLife Server /internal/orders/{n}/refund |
| `issue_compensation_coupon` | 补偿券 | Agent → MCP → LocalLifeInternalClient → LocalLife Server /internal/orders/{n}/compensate-coupon |

---

## 限流规则

| 维度 | 阈值 | 实现 |
|------|------|------|
| 每用户每分钟工具调用总量 | 30 次 | `ToolRateLimiter.java` Redis INCR |
| 每用户每工具每分钟 | 100 次 | 同上 |

超限时工具调用返回 `reason: tool_rate_limited` 错误（不是 HTTP 429，是 JSON-RPC 错误）。

---

## 代码结构

```
src/main/java/.../copilot/
├── mcp/
│   ├── McpController.java          POST /mcp 路由入口
│   └── dto/                        McpRequest/Response/Error/ToolDefinition
├── rbac/
│   ├── RbacFilter.java             解析 Header → ThreadLocal
│   └── RbacContext.java            身份上下文
├── tool/
│   ├── McpTool.java                工具接口（含 4 种异常类）
│   ├── ToolRegistry.java           @Component 自动注册
│   └── impl/                       9 个工具实现
├── ratelimit/
│   └── ToolRateLimiter.java        Redis 计数限流
├── client/
│   └── LocalLifeInternalClient.java  调用 LocalLife Server /internal/*
├── audit/
│   └── ToolAuditService.java       @Async 异步写 tool_audit_log
└── domain/mapper/
    ├── CopilotOrderMapper.java     只读订单查询（JOIN）
    └── CopilotCouponMapper.java    只读券查询（JOIN）
```
