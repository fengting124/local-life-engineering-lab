# 后端链路与 Agent 链路架构图

本文给面试、交接和设计评审使用，重点展示 LocalLife 如何把传统后端能力安全地提供给 AI Agent。

## 1. 总体架构

```mermaid
flowchart LR
    user[用户 / 商家 / 客服] --> nginx[Nginx 网关]
    nginx --> server[LocalLife Server<br/>Spring Boot 3]
    nginx --> agent[Copilot Agent Service<br/>FastAPI + LangGraph]
    agent --> mcp[LocalLife Copilot MCP Server<br/>Spring Boot 3]
    mcp --> mysql[(MySQL<br/>业务库 + 审计库)]
    server --> mysql
    server --> redis[(Redis<br/>缓存 / 秒杀库存 / 登录态)]
    server --> es[(Elasticsearch<br/>门店 / 笔记搜索)]
    server --> mq[RocketMQ<br/>交易事件]
    server --> outbox[(outbox_message<br/>最终一致性)]
    agent --> milvus[(Milvus<br/>RAG 向量库)]
    agent --> embedding[Embedding Service]
    agent --> reranker[Reranker Service]
    agent --> llm[DeepSeek flash API]
    server --> zipkin[Zipkin]
    server --> prometheus[Prometheus + Grafana]
    mcp --> prometheus
    agent --> prometheus
```

## 2. 后端交易链路

```mermaid
sequenceDiagram
    participant C as Client
    participant S as LocalLife Server
    participant R as Redis
    participant DB as MySQL / ShardingSphere
    participant MQ as RocketMQ
    participant O as Outbox Relay

    C->>S: POST /api/v1/orders
    S->>R: 校验幂等 Key / 用户态
    S->>DB: 写 order_info 逻辑表
    Note over DB: 按 user_id % 4 路由到 order_info_0..3
    S-->>C: 返回 WAIT_PAY 订单

    C->>S: POST /api/v1/payments
    S->>DB: 写 payment_order(PENDING)
    S-->>C: 返回支付单

    C->>S: POST /api/v1/payments/callback
    S->>DB: 同一事务更新 payment_order / order_info / outbox_message
    O->>DB: 扫描 PENDING outbox
    O->>MQ: 投递 payment-success-topic
    MQ-->>S: 下游消费支付成功事件
```

讲解重点：

- 订单写入走逻辑表 `order_info`，ShardingSphere 路由到 4 张物理表。
- 支付回调使用唯一索引和状态机保证幂等。
- DB 事务只覆盖业务表和 outbox 表，MQ 发送由 Relay 异步完成。
- MQ 临时不可用时消息留在 outbox，避免“支付成功但事件丢失”。

## 3. 秒杀链路

```mermaid
sequenceDiagram
    participant C as Client
    participant S as LocalLife Server
    participant R as Redis Lua
    participant DB as MySQL
    participant MQ as RocketMQ

    C->>S: POST /api/v1/seckill
    S->>R: Lua 原子校验库存 + 判重
    alt 库存充足且未领过
        R-->>S: 预扣成功
        S->>DB: 写 user_coupon 或后续改为 MQ 异步落库
        S-->>C: success=true
    else 库存不足或重复领取
        R-->>S: 业务失败码
        S-->>C: 400 / success=false
    end
```

讲解重点：

- Lua 将“查库存、扣库存、写用户集合”合成一次原子操作。
- `user_coupon` 唯一索引是最终兜底。
- 压测验收只看系统是否超卖，不把售罄、重复领取、限流当系统错误。

## 4. Agent 工具调用链路

```mermaid
sequenceDiagram
    participant U as 用户
    participant A as Agent Service<br/>LangGraph/ReAct
    participant L as DeepSeek flash
    participant K as RAG<br/>Milvus + BM25 + Reranker
    participant M as MCP Server
    participant DB as MySQL
    participant H as HITL Approval

    U->>A: /chat 订单异常排查
    A->>K: knowledge_search 查询业务规则
    K-->>A: 召回排查流程
    A->>L: 推理下一步工具
    L-->>A: tool_call=query_order
    A->>M: POST /mcp tools/call
    M->>M: RBAC / 限流 / 审计
    M->>DB: 查询订单、支付、券状态
    DB-->>M: 结构化结果
    M-->>A: tool_result
    A->>L: 汇总工具结果并生成建议
    alt 需要退款或补券
        A->>H: 创建 PENDING 审批
        H-->>A: 人工审批后恢复 thread
        A->>M: execute_refund / issue_compensation_coupon
    else 只读排查
        A-->>U: final_answer
    end
```

讲解重点：

- Agent 不直接操作数据库，所有业务能力通过 MCP 工具暴露。
- MCP Server 是安全边界：RBAC、审计、限流、结构化错误都在这里收敛。
- RAG 负责提供业务规则和排查 SOP，避免 LLM 编造流程。
- HITL 让高风险动作先挂起，人工审批后再恢复执行。

## 5. 面试答辩抓手

| 方向 | 可以强调 |
| --- | --- |
| 后端能力 | 分片、缓存、搜索、MQ、outbox、幂等、限流、可观测性。 |
| Agent 能力 | LangGraph/ReAct、MCP 工具、Tool Router、RAG、HITL、会话持久化。 |
| 工程能力 | Docker Compose、本地 smoke、CI 质量门禁、Swagger、压测脚本。 |
| 上线意识 | Secret 管理、灰度发布、生产关闭 Swagger、告警与回滚预案。 |
