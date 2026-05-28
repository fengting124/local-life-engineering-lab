# LocalLife Copilot 架构设计文档

> 版本：1.0.0 | 日期：2026-05-28 | 状态：编码中（第 7 周）

---

## 1. 项目定位

LocalLife Copilot 是基于 LocalLife 业务数据底座构建的企业级自主决策 Agent，服务对象是**商家、客服和平台运营**。

它的价值不在于接入大模型 API，而在于把 LocalLife 的订单、支付、券、门店、知识库包装成**可治理、可审计、可评测**的 Agent 工具体系。

### 与普通 AI 问答的区别

| 维度 | 普通聊天机器人 | LocalLife Copilot |
|------|---------|---------|
| 数据来源 | 模型内部知识 | LocalLife 实时业务数据 |
| 工具调用 | 无或固定工具 | MCP 标准化工具体系 |
| 执行链路 | 单轮问答 | 多步 ReAct 循环 |
| 失败处理 | 泛化回答 | 结构化错误 + Agent 自动修正 |
| 高风险动作 | 缺少治理 | HITL 人工审批 |
| 质量评估 | 主观判断 | Evals 指标化评测 |

---

## 2. 整体架构

### 2.1 五层架构

```
Layer 5  应用入口层
         Chat UI / 审批工作台 / SSE API

Layer 4  Agent 编排层
         LangGraph ReAct + Self-Reflection + HITL

Layer 3  Runtime 层
         Session / Memory / Checkpoint / Budget 控制

Layer 2  工具与数据层
         Java MCP Server / RAG（Milvus）/ LocalLife API / ES

Layer 1  治理层
         RBAC / Audit / Trace / Evals / Guardrails / Rate Limit
```

### 2.2 部署拓扑

```
用户（商家/客服）
       ↓ HTTP + SSE
copilot-agent-service（Python FastAPI，端口 8000）
  - LangGraph ReAct Agent
  - MCP Client
  - RAG Pipeline（Milvus）
  - SSE 流式输出
       ↓ MCP over HTTP（POST /mcp）
local-life-copilot MCP Server（Java Spring Boot，端口 8081）
  - MCP JSON-RPC 2.0 协议层
  - 工具注册表（ToolRegistry）
  - RBAC 身份校验
  - 工具调用审计
       ↓ JDBC（共享同一 MySQL）
LocalLife 主服务数据库（MySQL 8，local_life）
  - order_info / payment_order
  - coupon_template / user_coupon
  - shop / merchant
  - agent_session / agent_message
  - tool_audit_log / hitl_approval
```

### 2.3 关键组件边界

| 组件 | 职责边界 |
|------|---------|
| `LocalLife Server` | 保存业务事实，执行业务规则，不感知 AI |
| `local-life-copilot` | 将业务能力包装为 MCP 工具，RBAC + 审计 |
| `copilot-agent-service` | Agent 编排、工具选择、RAG、HITL、Evals |
| `Chat UI` | SSE 接收展示，HITL 审批入口 |

**核心原则**：
1. 业务规则留在 Java 主服务
2. Agent 只做编排和决策，不直接访问数据库
3. 工具调用统一走 MCP，不绕过权限
4. 高风险写操作必须 HITL

---

## 3. MCP Server 设计

### 3.1 协议实现

MCP（Model Context Protocol）= JSON-RPC 2.0 over HTTP

```
POST /mcp
Content-Type: application/json
X-User-Id: 10001
X-User-Role: merchant
X-Merchant-Id: 20001

{
  "jsonrpc": "2.0",
  "id": "req-001",
  "method": "tools/call",
  "params": {
    "name": "query_order",
    "arguments": { "order_id": "ORDER_12345" }
  }
}
```

支持的方法：
- `initialize` — 握手，返回服务能力声明
- `tools/list` — 列出所有工具及 Schema
- `tools/call` — 执行工具调用

### 3.2 工具分级

| 级别 | 类型 | 工具 | 治理策略 |
|------|------|------|---------|
| L1 | 只读查询 | `query_order` / `shop_metrics_query` / `query_coupon_issue_log` | RBAC + 审计 |
| L2 | 草稿生成 | `campaign_draft_generate` | 审计 + 用户确认 |
| L3 | 低风险写 | `create_campaign_draft` | 审计 + 幂等 |
| L4 | 高风险执行 | `execute_refund` / `issue_compensation_coupon` | HITL + 审批 + 审计 |

### 3.3 结构化错误协议

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

Agent 根据 `reason` 字段决定策略：

| reason | Agent 行为 |
|--------|----------|
| `parameter_error` | 根据 hint 修正参数后重试 |
| `permission_denied` | 停止调用，说明权限边界 |
| `not_found` | 换工具补充查询 |
| `tool_timeout` | 重试一次，仍失败则降级 |
| `business_conflict` | 先查询确认状态再决策 |

### 3.4 RBAC 设计

身份由 Agent Service 注入 HTTP Header，MCP Server 信任（内网通信）：

```
X-User-Id:     10001      必填
X-User-Role:   merchant   必填（merchant / cs / admin）
X-Merchant-Id: 20001      merchant 角色必填
```

| 角色 | 权限范围 |
|------|---------|
| `merchant` | 只能查询自己门店的数据（SQL 强制 merchant_id 过滤） |
| `cs` | 可查询所有商家数据，写操作必须 HITL |
| `admin` | 全量查询，高风险写操作仍需审计 |

---

## 4. Agent 编排设计

### 4.1 LangGraph 状态图

```
                    ┌─────────────┐
    入口 ─────────▶ │  llm_node   │
                    └──────┬──────┘
                           │ 条件路由
              ┌────────────┼────────────┬──────────────┐
              ▼            ▼            ▼              ▼
        tool_node   reflection_node  hitl_node    final_node
              │            │            │              │
              └────────────┘            └──────────────┘
                    │                          │
              回到 llm_node                  END
```

### 4.2 ReAct 循环

```
用户输入
   ↓
llm_node: Thought + Action（工具调用 or Final Answer）
   ↓ 若有 tool_calls
tool_node: 执行 MCP 工具，获取 Observation
   ↓
llm_node: 分析 Observation，决定下一步
   ↓ 重复（最多 15 步）
final_node: 生成最终回答
```

### 4.3 六种终止条件

| 条件 | 触发 | 处理 |
|------|------|------|
| 任务完成 | LLM 无 tool_calls | Final Answer |
| 最大步数 | step_count >= 15 | 说明证据不足 |
| Token 预算 | token_count >= 50000 | 返回已有信息 |
| 重复工具调用 | 同工具同参数 3 次 | 触发 Reflection |
| HITL 审批 | 高风险动作 | 挂起等待审批 |
| 无可用工具 | ToolRegistry 空 | 拒答或转人工 |

### 4.4 Self-Reflection 触发条件

| 触发点 | 反思内容 |
|--------|---------|
| 每 5 步 | 是否已收集足够证据 |
| 工具失败 | 参数是否错误，是否需要换工具 |
| 重复调用 | 是否陷入无效循环 |
| 高风险动作前 | 是否需要 HITL |

---

## 5. HITL 设计

### 5.1 必须审批的动作

| 动作 | 工具 | 原因 |
|------|------|------|
| 退款 | `execute_refund` | 影响资金 |
| 补券 | `issue_compensation_coupon` | 影响平台权益 |
| 发布活动 | `create_campaign` | 影响商家经营 |

### 5.2 挂起与恢复流程

```
Agent 决定执行高风险动作
   ↓
写 hitl_approval（status=PENDING）
   ↓
LangGraph interrupt → checkpoint 持久化
   ↓
返回 SSE event: hitl_request（含 thread_id）
   ↓
运营在审批工作台审批
   ↓
POST /chat/resume（approval_id + approved=true）
   ↓
从 checkpoint 恢复 Agent thread
   ↓
执行工具 + 记录审计日志
   ↓
继续 SSE 流式输出
```

---

## 6. 数据库表

### 6.1 Agent 核心表

| 表 | 用途 |
|---|---|
| `agent_session` | 会话（含 user_id / role / merchant_id / token 统计） |
| `agent_message` | 消息（user / assistant / tool 三种角色） |
| `langgraph_checkpoint` | LangGraph 状态快照（HITL 挂起/恢复） |
| `tool_audit_log` | 工具调用审计（入参/出参/耗时/状态） |
| `hitl_approval` | 人工审批记录（高风险动作待审批/审批结果） |

### 6.2 Redis Key

| Key | 用途 | TTL |
|-----|------|-----|
| `agent:session:active:{userId}` | 当前活跃会话 | 24h |
| `agent:rate:user:{userId}` | 用户请求限流 | 60s |
| `agent:rate:tool:{toolName}` | 工具调用限流 | 60s |
| `agent:cache:rag:{queryHash}` | RAG 结果缓存 | 5min |
| `agent:budget:session:{sessionId}` | Token 预算计数 | 24h |

---

## 7. RAG 设计

### 7.1 知识来源

| 来源 | 示例 |
|------|------|
| 平台规则 | 活动规则、券使用规则、退款规则 |
| 商家手册 | 门店运营、活动配置、内容规范 |
| 客服 FAQ | 常见订单问题、支付问题、券问题 |
| 故障复盘 | MQ 异常、支付回调异常案例 |

### 7.2 检索流程

```
用户查询 → Query Rewrite（改写扩充）
         → Milvus 向量搜索 Top 20
         → metadata 权限过滤（merchant_id 隔离）
         → Reranker Top 5
         → 上下文压缩
         → LLM 生成含引用来源的回答
```

### 7.3 权限感知

```
scope=public     → 所有商家可见
scope=private    → 只对 merchant_id=X 可见
检索分数 < 阈值  → 拒答，不编造规则
```

---

## 8. Evals 体系

### 8.1 评测集（50 条）

| 类别 | 数量 | 示例 |
|------|------|------|
| 简单数据查询 | 15 | 查昨日 GMV |
| 订单异常排查 | 15 | 支付成功未发券 |
| 知识问答 | 15 | 活动规则解释 |
| 边界场景 | 5 | 权限越界、无答案、恶意提示 |

### 8.2 六项指标

| 指标 | 计算方式 |
|------|---------|
| 工具调用准确率 | 标注序列 vs 实际序列匹配度 |
| 任务完成率 | 是否达成预期业务结果 |
| Recall@5 | Top 5 检索结果包含正确证据 |
| 事实一致性 | 答案是否有工具/文档支撑 |
| 平均延迟 | 端到端 P50/P99 |
| Token 成本 | 单会话 token 消耗 |

---

## 9. 代码结构

### 9.1 Java MCP Server（local-life-copilot/）

```
src/main/java/.../copilot/
├── LocalLifeCopilotApplication.java   启动类（@EnableAsync + @MapperScan）
├── mcp/
│   ├── McpController.java             POST /mcp（JSON-RPC 路由）
│   └── dto/
│       ├── McpRequest.java            JSON-RPC 请求
│       ├── McpResponse.java           JSON-RPC 响应
│       ├── McpError.java              结构化错误（5 种 reason）
│       └── ToolDefinition.java        工具 Schema 定义
├── rbac/
│   ├── RbacContext.java               ThreadLocal 身份上下文
│   └── RbacFilter.java                Header 解析 + 身份注入
├── tool/
│   ├── McpTool.java                   工具接口（+ 4 种异常类）
│   ├── ToolRegistry.java              自动注册工具 @Component
│   └── impl/
│       ├── QueryOrderTool.java        L1：查询订单
│       ├── ShopMetricsQueryTool.java  L1：门店经营数据
│       ├── QueryCouponIssueLogTool.java  L1：券发放日志
│       └── ExecuteRefundTool.java     L4：退款（HITL）
└── audit/
    ├── ToolAuditLog.java              审计日志实体
    ├── ToolAuditMapper.java           Mapper
    └── ToolAuditService.java          @Async 异步写审计
```

### 9.2 Python Agent Service（copilot-agent-service/）

```
copilot-agent-service/
├── main.py                            FastAPI 入口
├── requirements.txt                   Python 依赖
├── config/settings.py                 Pydantic Settings 配置
├── mcp/mcp_client.py                  MCP HTTP 客户端（异步）
├── agent/
│   ├── state.py                       AgentState TypedDict
│   ├── graph.py                       LangGraph 状态图
│   └── nodes.py                       5 个节点（llm/tool/reflection/hitl/final）
└── api/
    └── chat.py                        POST /chat（SSE）/ POST /chat/resume（HITL 恢复）
```

---

## 10. 启动顺序

```bash
# 1. 启动 LocalLife Server（提供 MySQL 数据底座）
cd local-life-server && mvn spring-boot:run  # 端口 8080

# 2. 启动 MCP Server（封装业务工具）
cd local-life-copilot && mvn spring-boot:run  # 端口 8081

# 3. 启动 Python Agent Service（LangGraph Agent）
cd copilot-agent-service
cp .env.example .env  # 填写 ANTHROPIC_API_KEY 等
pip install -r requirements.txt
uvicorn main:app --port 8000 --reload
```

---

## 11. 实现进度

### ✅ 已完成（生产级）

**Java MCP Server**
- [x] McpController + JSON-RPC 2.0 协议层（initialize / tools/list / tools/call）
- [x] RBAC：RbacFilter 解析 X-User-Id/Role/Merchant-Id Header → ThreadLocal
- [x] ToolRegistry：自动发现所有 `@Component McpTool` 注册
- [x] 9 个生产级工具：
  - L1 只读：query_order / query_payment / query_coupon_issue_log /
    query_mq_dead_letter / shop_metrics_query / coupon_policy_lookup
  - L2 草稿：campaign_draft_generate
  - L4 高风险：execute_refund / issue_compensation_coupon（HITL + 内部 API）
- [x] CopilotOrderMapper + CopilotCouponMapper：只读 SQL（含 JOIN/聚合）
- [x] ToolRateLimiter：Redis 计数（100次/分钟/工具 + 30次/分钟/用户）
- [x] LocalLifeInternalClient：调用主服务 /internal/* API 执行退款/补券
- [x] ToolAuditService：@Async 异步写 tool_audit_log

**Python Agent Service**
- [x] LangGraph StateGraph + 5 个节点（llm/tool/reflection/hitl/final）
- [x] AsyncMySQLCheckpointer：持久化 checkpoint（服务重启可恢复 HITL）
- [x] ToolRouter：按 role + task type + context 三层动态过滤工具
- [x] Self-Reflection：每 5 步或工具失败时触发
- [x] 6 种终止条件（completed / max_steps / token_budget / hitl / repeat / no_tools）
- [x] SSE 端点：astream_events → agent_step / tool_call / tool_result / final_answer
- [x] Session + Message 持久化（agent_session / agent_message）
- [x] HITL 完整流程：DB 写入 + POST /chat/resume + 审批工作台 API
- [x] Guardrails：12+ 输入注入规则（BLOCK/WARN/ALLOW）+ 输出脱敏检查
- [x] RAG 完整流水线：embedding（multilingual-e5）+ Milvus + Reranker（Cross-Encoder）
- [x] knowledge_search Python 原生工具（绕过 MCP，本地向量检索）
- [x] Evals 评测集 50 条用例 + 6 项指标自动化脚本

**主服务（LocalLife Server）配套**
- [x] InternalController + InternalService：/internal/orders/{n}/refund + compensate-coupon
- [x] X-Internal-Key 验证（防止外部恶意调用）
- [x] BizException 业务错误码转换为 MCP 结构化错误

**Docker Compose 完整环境**
- [x] MySQL 8 / Redis 7 / Elasticsearch 8 / RocketMQ 5
- [x] Milvus 2.4（+ etcd + MinIO + Attu UI）
- [x] Prometheus + Grafana + Zipkin（可观测性套件）

**性能测试**
- [x] Locust 压测脚本（locallife_server + copilot 两个文件）
- [x] 性能基准文档（performance-tests/README.md）
- [x] 瓶颈定位指南（MySQL / Redis / JVM / LLM）

### 🚧 后续增强（可选）

| 项 | 优先级 | 说明 |
|---|--------|------|
| LangSmith 集成 | P2 | 开发期可视化 Agent trace |
| 模型路由（Haiku for simple, Sonnet for hard） | P2 | 降低 token 成本 |
| Streaming Tool Call | P2 | SSE 推送工具调用进度更细 |
| 智能运营日报（Proactive Agent） | P3 | 定时任务自动生成运营建议 |
| Hybrid Search（向量 + BM25） | P2 | 提升专有名词召回率 |（开发期观察）
