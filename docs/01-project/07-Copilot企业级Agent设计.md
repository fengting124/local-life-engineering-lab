# Copilot 企业级 Agent 设计

## 1. 文档目标

本文档将 `LocalLife Copilot` 从普通 AI 问答应用升级为企业级 Agent 应用，明确它在业务系统中的定位、架构、数据分工、工具协议、RAG、HITL、评测和治理方案。

该文档先作为第二阶段设计蓝图，不立刻影响当前 `LocalLife` 主项目编码节奏。当前优先级仍然是完成传统后端主链路。

## 2. 战略定位

`LocalLife Copilot` 是基于 `LocalLife` 业务数据底座构建的企业级自主决策 Agent，服务对象是商家、客服和平台运营。

它的价值不在于接入大模型 API，而在于把 `LocalLife` 的订单、支付、券、门店、知识库和运营工具包装成可治理、可审计、可评测的 Agent 工具体系。

### 2.1 服务对象

| 角色 | 核心场景 |
| --- | --- |
| 商家 | 查询经营数据、解释活动规则、生成活动草稿、排查订单问题 |
| 客服 | 查询订单、定位异常、辅助回复用户、申请退款或补券 |
| 运营 | 审批高风险动作、查看 Agent 执行轨迹、复盘异常样本 |

### 2.2 与普通聊天机器人的区别

| 维度 | 普通聊天机器人 | LocalLife Copilot |
| --- | --- | --- |
| 数据来源 | 主要依赖模型内部知识 | 依赖 LocalLife 业务数据和知识库 |
| 工具调用 | 无或少量固定工具 | 通过 MCP 暴露标准化工具 |
| 执行链路 | 单轮问答为主 | 多步 ReAct 循环 |
| 失败处理 | 通常给出泛化回答 | 工具返回结构化错误，Agent 可调整下一步 |
| 高风险动作 | 缺少治理 | 退款、补券、发布活动必须 HITL |
| 质量评估 | 人工主观判断 | Evals 指标化评估 |

## 3. 总体架构

### 3.1 五层架构

```text
Layer 5  应用入口层
         Chat UI / 审批工作台 / SSE API

Layer 4  Agent 编排层
         LangGraph / ReAct / Self-Reflection / HITL

Layer 3  Runtime 层
         Session / Memory / Checkpoint / Budget

Layer 2  工具与数据层
         Java MCP Server / RAG / LocalLife API / Milvus / Elasticsearch

Layer 1  治理层
         IAM / RBAC / Audit / Trace / Evals / Guardrails / Rate Limit
```

### 3.2 部署形态

```text
Chat UI / Approval UI
        |
        | HTTP + SSE
        v
copilot-agent-service
        |
        | MCP Client
        v
locallife-mcp-server
        |
        | Java Service Call
        v
LocalLife 主服务
        |
        | MySQL / Redis / RocketMQ / Elasticsearch
        v
基础设施
```

### 3.3 关键边界

| 组件 | 边界 |
| --- | --- |
| `LocalLife` 主服务 | 保存业务事实，执行订单、支付、券、门店等真实业务规则 |
| `locallife-mcp-server` | 将主服务能力包装成 MCP 工具，负责权限校验、参数校验、审计 |
| `copilot-agent-service` | 负责 Agent 编排、工具选择、上下文管理、RAG、HITL、Evals |
| Chat UI | 负责会话展示、流式输出、用户输入 |
| Approval UI | 负责退款、补券、活动发布等高风险动作审批 |

工程原则：

1. 业务规则留在 Java 主服务。
2. Agent 服务只做编排、检索和决策。
3. 工具调用统一走 MCP，不让 Agent 直接绕过业务权限访问数据库。
4. 高风险写操作必须进入 HITL。

## 4. 技术选型

| 模块 | 选型 | 理由 |
| --- | --- | --- |
| Agent 编排 | LangGraph | 支持状态图、checkpoint、interrupt、长任务恢复 |
| Agent 范式 | ReAct + Self-Reflection | 适合工具调用、观察反馈和多步修正 |
| Agent 服务 | Python + FastAPI | 生态成熟，便于接入 LangGraph、RAG、评测工具 |
| 工具协议 | MCP | 将业务能力标准化为可复用工具 |
| 业务服务 | Java + Spring Boot | 保持主项目后端工程含量 |
| 流式返回 | SSE | 浏览器原生支持，适合文本流式输出 |
| RAG 向量库 | Milvus | 支持向量检索和 metadata 过滤 |
| 全文检索 | Elasticsearch | 复用主项目搜索能力 |
| 会话与审计 | MySQL | 结构化、可追溯、可事务化 |
| 热数据与限流 | Redis | TTL、计数、缓存、预算控制 |
| 评测 | 自建 Evals + LangSmith | 自建指标可控，LangSmith 用于开发期观察 |

暂缓项：

| 技术 | 暂缓原因 |
| --- | --- |
| 多 Agent A2A | 当前业务单 Agent 足够，过早引入会增加复杂度 |
| Sandbox | 当前工具集受控，不执行任意代码 |
| Seata 类分布式事务 | Agent 侧不直接改业务事务，写操作交给 Java 主服务 |
| 自研 Workflow Engine | LangGraph 已覆盖状态编排、checkpoint、HITL |

## 5. Agent 决策机制

### 5.1 主循环

```text
User Input
   |
   v
Load Session + Context
   |
   v
Route Available Tools
   |
   v
LLM Decide Next Action
   |
   +--> Final Answer
   |
   +--> Tool Call
   |
   +--> HITL Request
   |
   +--> Self-Reflection
   |
   v
Execute Action
   |
   v
Observe Result
   |
   v
Check Stop Conditions
   |
   +-- Stop --> Final Answer / Human Handoff / Refusal
   |
   +-- Continue --> Persist Checkpoint
                    |
                    v
              Back to LLM Decide Next Action
```

### 5.2 ReAct 状态模型

```text
Thought
  Agent 分析当前任务、已有证据和下一步动作。

Action
  Agent 选择工具、生成参数、发起工具调用。

Observation
  工具返回结构化结果或结构化错误。

Reflection
  Agent 判断当前路径是否有效，是否需要换工具、补充查询或终止。

Final Answer
  Agent 基于已收集证据输出结果。
```

### 5.3 六种终止条件

| 条件 | 触发方式 | 处理 |
| --- | --- | --- |
| 任务完成 | LLM 输出 final answer | 返回最终结果 |
| 最大步数 | `step_count >= 15` | 停止并说明证据不足 |
| token 预算耗尽 | `session_tokens > limit` | 停止并记录预算耗尽 |
| 重复工具调用 | 同一工具和参数重复 3 次 | 触发反思或终止 |
| 请求人工审批 | 命中高风险动作 | checkpoint 持久化并挂起 |
| 无可用工具 | 工具路由结果为空 | 拒答或转人工 |

### 5.4 Self-Reflection 触发策略

| 触发点 | 反思内容 |
| --- | --- |
| 每 5 步 | 是否已经收集足够证据 |
| 工具失败 | 参数是否错误，是否需要换工具 |
| 重复调用 | 是否陷入无效循环 |
| 高风险动作前 | 是否需要 HITL |
| 最终回答前 | 事实是否有工具结果支撑 |

## 6. 工具协议设计

### 6.1 工具分级

| 级别 | 类型 | 示例 | 治理策略 |
| --- | --- | --- | --- |
| L1 | 只读查询 | `query_order`、`shop_metrics_query` | RBAC + 审计 |
| L2 | 草稿生成 | `campaign_draft_generate` | 审计 + 用户确认 |
| L3 | 低风险写操作 | `create_campaign_draft` | 审计 + 幂等 |
| L4 | 高风险执行 | `execute_refund`、`issue_compensation_coupon` | HITL + 审批 + 审计 |

### 6.2 首批工具

| 工具名 | 类型 | 作用 |
| --- | --- | --- |
| `query_order` | 查询 | 查询订单状态、金额、券状态 |
| `query_payment` | 查询 | 查询支付状态、渠道流水，当前仅 `admin` 可直接调用 |
| `query_coupon_issue_log` | 查询 | 查询券发放日志，当前仅 `admin` 可直接调用 |
| `query_mq_dead_letter` | 查询 | 查询订单相关死信消息，当前仅 `admin` 可直接调用 |
| `shop_metrics_query` | 查询 | 查询门店经营数据 |
| `coupon_policy_lookup` | 查询 | 查询券规则和活动限制 |
| `knowledge_search` | RAG | 检索商家知识库 |
| `campaign_draft_generate` | 生成 | 生成活动草稿 |
| `execute_refund` | 执行 | 执行退款，必须 HITL |
| `issue_compensation_coupon` | 执行 | 发放补偿券，必须 HITL |

### 6.3 工具 Schema 示例

```json
{
  "name": "query_order",
  "description": "查询订单完整状态。用于判断订单、支付、券发放是否一致。",
  "inputSchema": {
    "type": "object",
    "properties": {
      "order_id": {
        "type": "string",
        "description": "订单号，例如 ORDER_12345"
      }
    },
    "required": ["order_id"]
  },
  "outputSchema": {
    "type": "object",
    "properties": {
      "order_status": {"type": "string"},
      "payment_status": {"type": "string"},
      "coupon_status": {"type": "string"},
      "pay_amount": {"type": "integer"}
    }
  },
  "x-business-hint": "order_status=PAID 且 coupon_status=NOT_ISSUED 表示券发放异常，应继续调用 query_coupon_issue_log。"
}
```

说明：

1. `inputSchema` 使用 MCP 的字段风格。
2. `outputSchema` 是项目内部约定，用于约束工具返回结构。
3. `x-business-hint` 是自定义扩展字段，不属于 MCP 标准字段，用于给 Agent 提供业务语义提示。

### 6.4 结构化错误

```json
{
  "status": "error",
  "reason": "parameter_error",
  "detail": "order_id 格式错误",
  "hint": "order_id 应为 ORDER_ 开头的字符串"
}
```

错误类型：

| `reason` | 含义 | Agent 行为 |
| --- | --- | --- |
| `parameter_error` | 参数错误 | 根据 hint 修正参数 |
| `permission_denied` | 权限不足 | 停止调用并说明权限边界 |
| `not_found` | 数据不存在 | 换工具补充查询或告知无结果 |
| `tool_timeout` | 工具超时 | 重试一次，仍失败则降级 |
| `business_conflict` | 业务状态冲突 | 调用查询工具补充证据 |

## 7. RAG 设计

### 7.1 知识来源

| 来源 | 示例 |
| --- | --- |
| 平台规则 | 活动规则、券使用规则、退款规则 |
| 商家手册 | 门店运营、活动配置、内容规范 |
| 客服 FAQ | 常见订单问题、支付问题、券问题 |
| 平台公告 | 大促规则、临时政策 |
| 故障复盘 | MQ 异常、支付回调异常、券发放异常案例 |

### 7.2 索引流程

```text
Document Collect
   |
   v
Parse
   |
   v
Semantic Chunk, 500 tokens, overlap 50
   |
   v
Embedding
   |
   v
Milvus Upsert with Metadata
```

### 7.3 检索流程

```text
Query Rewrite
   |
   v
Milvus Vector Search Top 20
   |
   v
Metadata Permission Filter
   |
   v
Rerank Top 5
   |
   v
Context Compression
   |
   v
LLM Answer with Citations
```

### 7.4 权限感知 RAG

Milvus metadata：

```json
{
  "scope": "public",
  "merchant_id": 0,
  "source": "platform_rule",
  "doc_id": "DOC_10001"
}
```

私有文档：

```json
{
  "scope": "merchant_private",
  "merchant_id": 20001,
  "source": "merchant_manual",
  "doc_id": "DOC_20001"
}
```

过滤规则：

```text
scope == "public"
OR
(scope == "merchant_private" AND merchant_id == current_merchant_id)
```

设计要点：

1. 公共文档对所有商家可见。
2. 商家私有文档只对本商家可见。
3. 客服可见范围由 RBAC 控制。
4. 检索分数低于阈值时拒答，避免编造规则。

## 8. HITL 设计

### 8.1 必须审批的动作

| 动作 | 原因 |
| --- | --- |
| 退款 | 影响资金 |
| 补券 | 影响平台权益 |
| 发布活动 | 影响商家经营和用户权益 |
| 批量修改规则 | 影响范围大 |

### 8.2 挂起和恢复流程

```text
Agent 决定执行高风险动作
   |
   v
生成 approval request
   |
   v
LangGraph interrupt
   |
   v
Checkpoint 持久化
   |
   v
运营或商家审批
   |
   v
恢复 thread
   |
   v
执行工具
   |
   v
记录审计日志
```

### 8.3 审批表

```sql
CREATE TABLE hitl_approval (
  id BIGINT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  thread_id VARCHAR(64) NOT NULL,
  checkpoint_id VARCHAR(64) NOT NULL,
  action_type VARCHAR(50) NOT NULL,
  action_payload JSON,
  reason TEXT,
  status VARCHAR(20) DEFAULT 'PENDING',
  approver_id BIGINT,
  approver_comment TEXT,
  approved_at DATETIME,
  created_at DATETIME,
  INDEX idx_status_time (status, created_at)
);
```

## 9. 数据库设计

### 9.1 会话表

```sql
CREATE TABLE agent_session (
  id BIGINT PRIMARY KEY,
  user_id BIGINT NOT NULL,
  user_role VARCHAR(20) NOT NULL,
  merchant_id BIGINT,
  title VARCHAR(200),
  status VARCHAR(20) DEFAULT 'ACTIVE',
  total_tokens INT DEFAULT 0,
  total_cost_cents INT DEFAULT 0,
  created_at DATETIME,
  updated_at DATETIME,
  INDEX idx_user_time (user_id, created_at)
);
```

### 9.2 消息表

```sql
CREATE TABLE agent_message (
  id BIGINT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  role VARCHAR(20) NOT NULL,
  content TEXT,
  tool_calls JSON,
  tool_results JSON,
  tokens INT,
  created_at DATETIME,
  INDEX idx_session_time (session_id, created_at)
);
```

### 9.3 Checkpoint 表

表名采用项目自定义单数命名 `langgraph_checkpoint`。如果后续直接使用 LangGraph 官方 MySQL Checkpointer，需要按官方默认表结构适配或迁移。

```sql
CREATE TABLE langgraph_checkpoint (
  thread_id VARCHAR(64) NOT NULL,
  checkpoint_id VARCHAR(64) NOT NULL,
  parent_checkpoint_id VARCHAR(64),
  state JSON,
  metadata JSON,
  created_at DATETIME,
  PRIMARY KEY (thread_id, checkpoint_id)
);
```

### 9.4 工具调用审计表

```sql
CREATE TABLE tool_audit_log (
  id BIGINT PRIMARY KEY,
  session_id BIGINT NOT NULL,
  trace_id VARCHAR(64),
  tool_name VARCHAR(100) NOT NULL,
  tool_input JSON,
  tool_output JSON,
  duration_ms INT,
  status VARCHAR(20),
  error_msg TEXT,
  created_at DATETIME,
  INDEX idx_trace (trace_id),
  INDEX idx_session_time (session_id, created_at),
  INDEX idx_tool_time (tool_name, created_at)
);
```

### 9.5 Redis Key

| Key | Value | TTL | 用途 |
| --- | --- | --- | --- |
| `agent:session:active:{userId}` | sessionId | 24 小时 | 当前活跃会话 |
| `agent:rate:user:{userId}` | 计数 | 60 秒 | 用户限流 |
| `agent:rate:tool:{toolName}` | 计数 | 60 秒 | 工具限流 |
| `agent:cache:rag:{queryHash}` | RAG 结果 | 5 分钟 | RAG 缓存 |
| `agent:budget:session:{sessionId}` | token 计数 | 24 小时 | 会话预算 |

## 10. 核心场景

### 10.1 商家知识问答

输入示例：

```text
双 11 活动券规则是什么？
```

流程：

1. Query 改写，提取关键词。
2. Milvus 向量召回 top 20。
3. metadata 权限过滤。
4. Reranker 取 top 5。
5. 压缩上下文。
6. LLM 生成答案并附来源。
7. 检索分数过低时拒答。

### 10.2 门店数据查询

输入示例：

```text
我昨天卖了多少钱？
```

ReAct 示例：

```text
Thought: 用户要查昨天销售额，需要调用 shop_metrics_query。
Action: shop_metrics_query(date=yesterday, merchant_id=current)
Observation: {orderCount: 128, gmv: 9800}
Final Answer: 昨天共 128 单，GMV 9800 元。
```

### 10.3 订单异常排查

输入示例：

```text
ORDER_12345 用户说支付了但没收到券。
```

执行链路：

1. 调用 `query_order` 查询订单、支付、券状态。
2. 发现 `PAID + SUCCESS + NOT_ISSUED`。
3. 调用 `query_coupon_issue_log` 查询券发放日志。
4. 如发现 MQ 消费失败，继续调用 `query_mq_dead_letter`。
5. 判断根因。
6. 如需要退款或补券，生成 HITL 审批。
7. 审批通过后调用 `execute_refund` 或 `issue_compensation_coupon`。
8. 返回结论、证据链和处理结果。

完整 ReAct trace 示例：

```text
Step 1
  Thought: 用户反馈支付成功但没有收到券，需要先确认订单、支付、券发放状态。
  Action: query_order(order_id="ORDER_12345")
  Observation: {order_status: "PAID", payment_status: "SUCCESS", coupon_status: "NOT_ISSUED", pay_amount: 2990}

Step 2
  Thought: 订单已支付且支付成功，但券未发放。根据 query_order 工具描述，这属于券发放异常，需要查询券发放日志。
  Action: query_coupon_issue_log(order_id="ORDER_12345")
  Observation: {status: "FAILED", error: "MQ 消费失败", retry_count: 3, last_error_time: "2026-05-27T20:15:00"}

Step 3
  Thought: 券发放日志显示 MQ 消费失败 3 次，需要继续查询死信消息，确认失败根因。
  Action: query_mq_dead_letter(order_id="ORDER_12345")
  Observation: {message_status: "DLQ", error_msg: "coupon_template_id=789 stock not enough"}

Step 4
  Thought: 根因是券库存不足，订单资金已经成功。补券可能失败，退款属于资金动作，必须进入人工审批。
  Action: request_human_approval(action_type="refund", order_id="ORDER_12345", amount=2990, reason="券库存不足导致已支付订单未发券")
  Observation: {approval_status: "PENDING", checkpoint_id: "ckpt_001"}

Step 5
  Thought: 人工审批已通过，可以执行退款。
  Action: execute_refund(order_id="ORDER_12345", amount=2990, approval_id="APPROVAL_001")
  Observation: {refund_status: "SUCCESS", refund_id: "REFUND_998877"}

Step 6
  Final Answer: 已定位到订单异常原因：支付成功后券发放 MQ 消费失败，根因是券模板库存不足。人工审批通过后已执行退款，退款单号 REFUND_998877。
```

### 10.4 活动配置辅助

输入示例：

```text
帮我配一个国庆 5 折券，限 1000 张。
```

执行链路：

1. 调用 `coupon_policy_lookup` 查询当前商家可用券规则。
2. 调用 `shop_metrics_query` 查询近期经营数据。
3. 调用 `campaign_draft_generate` 生成活动草稿。
4. 进入 HITL，让商家确认。
5. 确认后调用活动创建工具。

## 11. 治理设计

### 11.1 RBAC

| 角色 | 工具权限 |
| --- | --- |
| `merchant` | 可查自身门店/经营数据/订单/活动规则，订单查询必须受 `merchant_id` 约束 |
| `cs` | 普通只读工具只开放 `query_order`；退款/补券不是普通直通能力，必须进入 HITL |
| `admin` | 可使用内部排障读工具；高风险动作仍需 HITL 和审计 |

当前代码落点：

- Python 侧工具可见性：`copilot-agent-service/agent/tool_router.py` 的 `TOOL_ROLE_MAP`。
- Java MCP 执行边界：各 `McpTool#getDefinition().xAllowedRoles` + `McpController` RBAC。
- 商家隔离：`QueryOrderTool` 使用 `RbacContext.merchantId` 校验订单所属商家，不信任模型传入的参数。
- 高风险动作：`agent/nodes.py` 在调用 `execute_refund` / `issue_compensation_coupon` 前生成 `pending_action`，无审批不调用 MCP；Java 工具 schema 也要求 `approval_id`。

工具调用前必须注入身份上下文：

```json
{
  "user_id": 10001,
  "role": "merchant",
  "merchant_id": 20001
}
```

Agent 不能自行生成或覆盖 `merchant_id`。服务端根据登录态注入。

### 11.2 限流和预算

| 维度 | 阈值 | 存储 |
| --- | --- | --- |
| 用户请求 | 10 次 / 分钟 | Redis |
| 工具调用 | 100 次 / 分钟 / 工具 | Redis |
| 会话步数 | 15 步 | Runtime State |
| 会话 token | 50000 / 24 小时 | Redis |
| 高风险动作 | 每次审批 | MySQL |

### 11.3 Trace

每次会话生成 `trace_id`，贯穿：

1. 用户输入。
2. LLM 调用。
3. 工具调用。
4. RAG 检索。
5. HITL 审批。
6. 最终回答。

工具审计必须记录：

| 字段 | 说明 |
| --- | --- |
| `trace_id` | 全链路追踪 |
| `session_id` | 会话 |
| `tool_name` | 工具名 |
| `tool_input` | 入参 |
| `tool_output` | 出参 |
| `duration_ms` | 耗时 |
| `status` | 成功或失败 |

`tool_audit_log` 是自建 Trace 表的第一阶段实现，先覆盖工具调用审计。后续如果需要记录 LLM 调用、RAG 检索和 HITL 事件，可扩展为 `agent_trace_log` 或增加独立明细表。

### 11.4 Guardrails

| 风险 | 处理 |
| --- | --- |
| Prompt Injection | 检测用户要求忽略系统规则、泄露工具结果等模式 |
| 权限越界 | 工具层强制 RBAC |
| 私有信息泄露 | 输出前检查 merchant_id 边界 |
| 编造规则 | RAG 分数低于阈值时拒答 |
| 高风险动作误执行 | HITL |

已覆盖的注入场景：

- 诱导 Agent 跳过 RBAC/HITL 直接退款或补券。
- 要求泄露 `X-Internal-Key` / internal key / 内部密钥。
- 要求跳过审批、伪造审批或绕过权限。

拦截后 `POST /chat` 返回 `BLOCKED_BY_GUARDRAILS`，并写结构化 `security_audit` 日志，可在 Loki 中按 `trace_id` 追踪。

## 12. Evals 设计

### 12.1 评测集

| 类别 | 数量 | 示例 |
| --- | --- | --- |
| 简单数据查询 | 15 | 查询昨日 GMV |
| 订单异常排查 | 15 | 支付成功但未发券 |
| 知识问答 | 15 | 活动规则解释 |
| 边界场景 | 5 | 权限越界、无答案、恶意提示 |

### 12.2 指标

| 指标 | 计算 |
| --- | --- |
| 工具调用准确率 | 标注工具序列与实际工具序列匹配程度 |
| 任务完成率 | 是否达成预期业务结果 |
| Recall@5 | top 5 检索结果是否包含正确证据 |
| 回答事实一致性 | 答案是否被工具或文档证据支撑 |
| 平均延迟 | 端到端中位数 |
| 平均 token 成本 | 单会话 token 消耗 |

该 6 项指标是 Copilot 评测口径，后续所有设计文档保持一致。

RAG 质量专项评测已经单独落地为 `copilot-agent-service/evals/rag_benchmark.py`：

```bash
./scripts/run-rag-benchmark.sh --run-name rag-quality-offline
cd copilot-agent-service
DEBUG=false ./.venv/bin/python -m evals.rag_benchmark --real --run-name rag-quality-real
```

当前真实 Docker 链路（Milvus + embedding-service + reranker-service）实测：

| 指标 | 结果 |
| --- | ---: |
| Recall@5 before rerank | 1.000 |
| Recall@5 after rerank | 1.000 |
| Citation accuracy | 1.000 |
| Refusal accuracy | 1.000 |
| Avg rerank delta | 0.000 |

这次评测暴露并修复了一个真实问题：原始 500 字符 chunk 会把“退款/补券处理方案”切断，导致引用准确率下降；当前 `rag.ingest` 将知识库入库 chunk size 调整为 900，保留 50 字符 overlap。

### 12.3 优化闭环

```text
构建评测集
   |
   v
跑基线
   |
   v
分析失败样本
   |
   v
优化工具描述、Prompt、RAG、Router
   |
   v
再次评测
   |
   v
记录提升数据
```

## 13. 交付顺序

Copilot 必须在 `LocalLife` 主项目核心链路完成后启动。

### 13.1 第 7 周

| 任务 | 输出 |
| --- | --- |
| Python Agent 服务骨架 | FastAPI + SSE |
| Java MCP Server 骨架 | 暴露只读查询工具 |
| MCP Client 接入 | Agent 可调用 Java 工具 |
| ReAct 单 Agent | 跑通门店数据查询 |
| 会话和消息表 | MySQL 落地 |

### 13.2 第 8 周

| 任务 | 输出 |
| --- | --- |
| LangGraph 主循环 | ReAct + 状态管理 |
| Checkpoint | MySQL checkpoint |
| 终止条件 | 六种终止机制 |
| 结构化错误 | 工具错误协议 |
| 订单异常排查骨架 | 查询链路跑通 |

### 13.3 第 9 周

| 任务 | 输出 |
| --- | --- |
| Milvus 接入 | 文档向量化入库 |
| 权限感知 RAG | metadata 过滤 |
| Reranker | top 20 到 top 5 |
| HITL | interrupt + 审批恢复 |
| 执行类工具 | 退款和补券 |

### 13.4 第 10 周

| 任务 | 输出 |
| --- | --- |
| 评测集 | 50 条用例 |
| 评测脚本 | 6 项指标 |
| Trace | LangSmith + 自建审计 |
| Guardrails | 输入、输出、工具、权限 |
| 简历数据 | 基线与优化后对比 |

## 14. 简历表达

```text
LocalLife Copilot 企业级 Agent

基于 LocalLife 本地生活交易平台构建企业级 Agent 应用，服务商家运营和平台客服，覆盖订单异常排查、券规则解释、商家知识问答、活动配置辅助等场景。

设计 Java MCP Server + Python LangGraph Agent 架构，将订单、支付、券、门店、知识库等业务能力标准化为 Agent 工具，业务规则保留在 Java 主服务，Agent 侧负责 ReAct 编排、工具选择、RAG、HITL 和评测。

实现 ReAct + Self-Reflection 主循环，设计最大步数、token 预算、重复工具检测、HITL、无工具可用、任务完成 6 种终止机制。工具返回结构化错误，Agent 可基于错误类型调整参数或切换工具。

构建权限感知 RAG，基于 Milvus metadata 实现公共文档和商家私有文档隔离，并通过 Recall@5、工具调用准确率、任务完成率、事实一致性、平均延迟和 token 成本建立评测体系。
```

## 15. 当前项目执行原则

1. Copilot 先作为设计蓝图沉淀。
2. 当前阶段继续推进 `LocalLife` 主项目。
3. 只有订单、支付、券、搜索、对账补偿具备基础能力后，Copilot 才进入编码。
4. Copilot 的第一个演示场景固定为订单异常排查。
5. Copilot 的第一个量化指标固定为工具调用准确率和 Recall@5。

## 16. 官方参考

| 方向 | 参考 |
| --- | --- |
| MCP | https://modelcontextprotocol.io/docs/getting-started/intro |
| LangGraph Persistence | https://docs.langchain.com/oss/python/langgraph/persistence |
| Milvus Filtered Search | https://milvus.io/docs/filtered-search.md |
| LangSmith Evaluation | https://docs.langchain.com/langsmith/evaluation-concepts |
| SSE EventSource | https://developer.mozilla.org/en-US/docs/Web/API/EventSource |
