# Agent Runtime 企业化落地计划

- Status: Draft
- Type: Plan
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: 当前代码和外部规范

## 1. 文档目标

这份文档回答两个问题：

1. `LocalLife Copilot` 下一步最该补什么，优先级怎么排。
2. 这些工作要分别落到哪些代码目录、接口、表结构、测试和验收标准上。

它不是重写 [Copilot 企业级 Agent 设计](./07-Copilot企业级Agent设计.md)，而是站在当前 `main` 分支实际代码之上，给出从“能跑 Demo”走向“可控、可审计、可恢复、可上线”的下一阶段工程路线。

时间基线：`2026-07-11`  
代码基线：`main` 分支

## 2. 当前状态判断

### 2.1 已经具备的能力

当前项目已经不是“玩具聊天壳子”，而是具备了企业 Agent 的基本骨架：

1. `local-life-server` 保存订单、支付、券、门店等业务事实。
2. `local-life-copilot` 把业务能力包装为 MCP 工具，并补了基础 RBAC、审计、HITL 工具约束。
3. `copilot-agent-service` 负责 FastAPI、LangGraph、RAG、HITL、SSE 和会话持久化。
4. 已经有日志栈、Swagger、测试门禁、RAG benchmark、Guardrails 和 GenAI/MCP trace。

这意味着下一步的关键，不再是“再接一个模型”或“再加几个工具”，而是把运行时边界做实。

### 2.2 当前已经确认的主要缺口

下面这些问题已经能在仓库里直接定位到代码入口：

| 主题 | 当前现象 | 代码入口 |
| --- | --- | --- |
| 身份信任链过弱 | Agent 和 MCP 仍直接信任 `X-User-Id/X-User-Role/X-Merchant-Id` | `copilot-agent-service/api/chat.py`、`api/session.py`、`mcp/mcp_client.py`、`local-life-copilot/.../RbacFilter.java` |
| HITL 恢复绑定 | 当前分支已改为服务端按 `approval_id` 反查 `thread_id`，但还未迁到 LangGraph 官方 `interrupt()/Command(resume=...)` 模式 | `copilot-agent-service/api/chat.py`、`api/hitl.py`、`session/hitl.py` |
| Checkpoint pending writes | 当前分支已新增 `langgraph_checkpoint_write` 并实现 `aput_writes()` 持久化；仍需真 MySQL 重启恢复 smoke | `copilot-agent-service/session/checkpointer.py`、`local-life-copilot/src/main/resources/db/migration/V102__add_langgraph_checkpoint_writes.sql` |
| 高风险副作用缺少完整幂等账本 | 退款、补券依赖 `approval_id`，但没有统一 side-effect ledger | `local-life-copilot/.../LocalLifeInternalClient.java`、`local-life-server/.../InternalService.java` |
| SSE / 错误输出仍有泄露面 | 前端可见工具参数、结果片段和异常文本 | `copilot-agent-service/api/chat.py` |
| RAG 故障降级 | 当前分支已移除向量检索的 “Mock 文档” 兜底；Milvus 不可用时返回空候选，上层在无 BM25/真实候选时拒答 | `copilot-agent-service/rag/*` |
| CORS 策略 | 当前分支已改为 `CORS_ALLOWED_ORIGINS` 环境变量驱动，默认只允许本地开发前端；生产需配置真实前端域名 | `copilot-agent-service/main.py`、`copilot-agent-service/config/settings.py` |
| 审批队列隔离不够 | 待审批查询仍偏全局化，缺少明确的商家/租户/审批组视角 | `copilot-agent-service/api/hitl.py` |

### 2.3 总体优先级判断

优先级应该是：

1. 先补安全边界和审批恢复一致性。
2. 再补运行时持久化和副作用幂等。
3. 然后把几个核心业务场景做成可演示、可压测、可审计的标准工作流。
4. 最后再做长期记忆和更复杂的 Agent 能力。

不要反过来。否则很容易做出“记忆很花、模型很强，但退款恢复不可靠”的系统。

## 3. 下一阶段的总原则

### 3.1 设计原则

1. 业务事实永远以 Java 主服务为准，Agent 不直接改业务数据库。
2. 高风险写操作必须经过审批、审计、幂等和恢复控制。
3. SSE 只是展示通道，不能承担关键结果持久化职责。
4. 失败时优先 fail closed，不返回看起来像真的假结果。
5. 新增能力优先落在“运行时、审计、恢复、权限、评测”这些企业底座上。

### 3.2 暂缓项

在完成本路线图前，以下方向不建议扩张：

1. 多 Agent 编排和 A2A 全协议化接入。
2. 更复杂的自反思链、自动计划树、工具自治。
3. 长对话原文直接写长期记忆。
4. 更多模型接入和模型切换 UI。

## 4. 分阶段实施计划

## 阶段 A：统一身份与权限边界

目标：让 Agent、MCP、Java 主服务之间的调用身份从“客户端自报”切到“服务端签发和校验”。

### A.1 业务目标

1. 用户不能伪造他人身份查订单、查店铺、做退款。
2. 客服只能查订单和诊断，不能直接执行高风险写操作。
3. 商家只能查自己店铺和与自己有关的数据。
4. 高风险动作必须经审批后获得一次性的提升权限。

### A.2 代码改造范围

**Python Agent Service**

- `copilot-agent-service/api/chat.py`
- `copilot-agent-service/api/session.py`
- `copilot-agent-service/api/hitl.py`
- `copilot-agent-service/mcp/mcp_client.py`
- `copilot-agent-service/static/index.html`
- `copilot-agent-service/static/approval.html`

**Java MCP Server**

- `local-life-copilot/src/main/java/.../rbac/RbacFilter.java`
- `local-life-copilot/src/main/java/.../rbac/RbacContext.java`
- `local-life-copilot/src/main/java/.../mcp/McpController.java`
- 相关 Tool 实现和鉴权测试

**Java 主服务**

- `local-life-server` 内部接口鉴权入口
- `module/internal/*`

### A.3 具体动作

1. 前端不再直接传业务身份头作为真实身份来源。
2. Agent 服务接入统一登录态解析，得到 `PrincipalContext`。
3. Agent 调 MCP 时改为短时 bearer token，而不是透传 `X-User-*`。
4. token 至少带 `sub`、`tenant_id`、`merchant_id`、`role`、`scopes`、`aud`、`exp`、`run_id`。
5. MCP 服务端按 scope 和资源归属校验权限。
6. 退款、补券类写操作通过审批后签发一次性临时 scope，例如 `refund.execute.approval:<id>`。

### A.4 测试与验收

1. 新增安全回归：
   - 商家伪造别家 `merchant_id` 被拒绝。
   - 客服直接调用退款工具被拒绝。
   - 用户诱导泄露 internal key 被拦截并写审计。
2. 保留现有 MCP security smoke，并扩展为权限矩阵回归。
3. 文档同步更新：
   - [Swagger在线接口文档](../04-notes/Swagger在线接口文档.md)
   - [面试高频题库](../05-interview/面试高频题库-后端Agent.md)

完成标志：身份由服务端解析，MCP 不再把客户端 header 当作最终可信来源。

## 阶段 B：把 HITL 和执行恢复做成真正的 Durable Runtime

目标：让审批、暂停、恢复、断线、重试、服务重启都不会把任务状态搞乱。

### B.1 业务目标

1. 审批暂停后服务重启，任务可以安全恢复。
2. 同一个审批链接不能重复触发副作用。
3. 浏览器断开 SSE，不会导致任务状态丢失。

### B.2 代码改造范围

- `copilot-agent-service/agent/graph.py`
- `copilot-agent-service/agent/nodes.py`
- `copilot-agent-service/api/chat.py`
- `copilot-agent-service/api/hitl.py`
- `copilot-agent-service/session/hitl.py`
- `copilot-agent-service/session/checkpointer.py`
- 新增运行时持久化表及 DAO/Service

### B.3 具体动作

1. 从当前“手工挂起 + END”迁到 LangGraph 官方 `interrupt()`/`Command(resume=...)` 模式。
2. `approval_id` 绑定真实 `interrupt_id`、`checkpoint_id`、`thread_id`，恢复时只允许服务端反查。
3. 实现 `agent_run` 和 `agent_event` 两张表：
   - `agent_run` 管状态：`SUBMITTED/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELED/EXPIRED`
   - `agent_event` 管事件流：tool call、interrupt、resume、error、final answer
4. 补齐 `aput_writes()`，让 pending writes 真正持久化。（当前分支已完成代码、迁移和单元测试）
5. 捕获 `CancelledError` 和流式断连场景，确保状态和消息成对落盘。
6. 审批接口改成“条件更新”或版本号更新，避免并发 approve/reject 竞态。

### B.4 测试与验收

1. 新增恢复测试：
   - 审批前服务重启后可恢复。
   - 审批通过后重复调用 resume 不会重复执行。
   - 客户端伪造 `thread_id` 无法恢复他人任务。
2. 集成测试至少覆盖：
   - `interrupt -> approval -> resume`
   - `disconnect -> reconnect -> event replay`
3. 运行结果以 `agent_run/agent_event` 为准，SSE 只是消费这些事件。

完成标志：系统不再依赖“前端记住 thread_id 并正确传回来”这类脆弱前提。

## 阶段 C：给有副作用的工具补幂等账本和恢复语义

目标：把退款、补偿券、补发等高风险操作从“能调通”升级为“重试安全”。

### C.1 业务目标

1. 网络超时、重试、服务重启都不能导致重复退款或重复发券。
2. 审批只是授权，不等于副作用一定执行成功。
3. 每次副作用都能追溯到 run、approval、operator 和结果。

### C.2 代码改造范围

**Java MCP / 主服务**

- `local-life-copilot/.../LocalLifeInternalClient.java`
- `local-life-server/.../InternalController.java`
- `local-life-server/.../InternalService.java`
- 相关 SQL migration

**Python Agent**

- `copilot-agent-service/agent/nodes.py`
- `copilot-agent-service/api/chat.py`

### C.3 具体动作

1. 引入 `side_effect_ledger` 或同类表，记录：
   - `operation_type`
   - `idempotency_key`
   - `approval_id`
   - `run_id`
   - `resource_id`
   - `request_payload`
   - `status`
   - `result_snapshot`
2. 退款、补券执行前先查账本，再决定是否继续。
3. Java 内部执行接口支持幂等键，而不是只传 `approval_id`。
4. 审计日志与 trace、run、approval 串起来。

### C.4 测试与验收

1. 重复提交同一审批恢复请求，不重复退款。
2. 工具超时后人工重试，最终最多执行一次副作用。
3. 对账可以从日志或账本反推出“这笔退款为什么发生、是否成功、是否重复尝试过”。

完成标志：资金类和补偿类动作具备企业级最小可托底能力。

## 阶段 D：把核心场景收敛成受控工作流

目标：把当前偏自由的 ReAct 链路，收敛成几条可演示、可压测、可审计的真实业务流程。

### D.1 建议优先做的四条场景

1. 客服查订单和售后状态。
2. 商家查自己门店经营数据。
3. 支付成功但优惠券未到账的诊断与补偿建议。
4. 退款/补偿券申请、审批和执行。

### D.2 具体动作

1. 对高频场景优先走 fast path 或显式 workflow。
2. 只有开放问题或长尾问题才进入通用 ReAct。
3. 对每条流程定义：
   - 输入 schema
   - 工具白名单
   - 权限边界
   - 失败分支
   - 拒答分支
   - 审批分支
4. 配套批量造数和异常注入脚本，覆盖真实演示与回归。

### D.3 验收

1. 每条链路都能跑 happy path。
2. 每条链路都至少有一条 failure path。
3. 每条高风险链路都能在审批、恢复、审计、日志里闭环。

完成标志：项目从“Agent 很灵活”升级为“真实业务最关键的几条链路很稳定”。

## 阶段 E：做可治理的 RAG 和长期记忆

目标：让知识能力不仅“接上了”，还“测得出、控得住、不串租户”。

### E.1 RAG 侧工作

1. 保留现有 benchmark，并扩充数据集：
   - 正常问答
   - 期望引用
   - 期望拒答
   - 越权问答
2. 固化四个指标：
   - `Recall@5`
   - reranker 前后对比
   - citation accuracy
   - refusal accuracy
3. 检索失败时 fail closed，不再返回 “Mock 文档”（当前分支已覆盖 Milvus 客户端不可用路径）。

### E.2 记忆侧工作

1. 长期记忆分为：
   - `profile memory`
   - `episodic memory`
   - `procedural memory`
   - `feedback memory`
2. 命名空间必须至少带：
   - `tenant_id`
   - `merchant_id`
   - `principal_id`
   - `agent_id`
   - `environment`
3. 加治理字段：
   - `source_type`
   - `source_id`
   - `confidence`
   - `ttl`
   - `supersedes`
   - `pii_level`
4. 写入前做 fingerprint、相似去重和污染检测。

### E.3 验收

1. 记忆不跨租户、不跨商家串数据。
2. 被提示注入的内容不会进入长期记忆。
3. benchmark 报告能定量说明 reranker 和权限过滤确实有效。

完成标志：RAG 和 memory 不再只是“有这个模块”，而是有质量指标和治理边界。

## 阶段 F：补齐上线前的平台化能力

目标：让这个项目在简历、演示和真实运行层面都更像成熟系统。

### F.1 具体动作

1. 统一 OpenAPI/Swagger 鉴权说明、错误码和审批接口说明。
2. 把 `trace_id/run_id/approval_id/session_id` 贯通到日志、审计、事件表和业务表。
3. 补 Agent 指标面板：
   - 完成率
   - 审批等待时长
   - 工具失败率
   - RAG 命中率
   - 拒答率
   - P95/P99 延迟
4. 完善 dev/test/prod 多环境配置和 release checklist。
5. 固化正式镜像、smoke、业务模拟、RAG benchmark 的统一脚本入口。

### F.2 验收

1. 任何一次高风险任务都能从 Grafana/Loki/审计表/运行时事件表追到全链路。
2. 新同学按文档能独立跑通镜像、造数、查询、审批和排障。
3. 面试演示可以稳定复现，不依赖手工拼参数。

## 5. 推荐的执行顺序

建议按下面顺序推进，而不是并行发散：

1. 阶段 A：身份与权限边界。
2. 阶段 B：Durable HITL 与 Runtime。
3. 阶段 C：副作用幂等账本。
4. 阶段 D：业务工作流收敛。
5. 阶段 E：RAG 与长期记忆治理。
6. 阶段 F：平台化、文档化、上线前封板。

原因很直接：前四步解决的是“系统会不会出大事”，后两步解决的是“系统看起来够不够完整”。

## 6. 建议的分支与里程碑

可以按工作流拆成下面几条分支：

1. `feat/agent-auth-runtime-hardening`
2. `feat/agent-hitl-durable-runtime`
3. `feat/agent-side-effect-idempotency`
4. `feat/agent-business-workflows`
5. `feat/agent-rag-memory-governance`
6. `feat/agent-release-readiness`

每个分支都要附带：

1. 代码改动。
2. 测试结果。
3. 文档同步。
4. 回滚点和风险说明。

## 7. 完成定义

满足下面这些条件，才能说 Agent Runtime 基本进入“可上线准备态”：

1. 客户端不能伪造身份、商家或审批上下文。
2. 审批中断、恢复、断连、重试、重启都不会导致状态错乱或重复副作用。
3. 高风险动作有完整的权限、审批、幂等和审计闭环。
4. RAG 检索失败时保守拒答，不生成带假引用的答案。
5. 四条核心业务工作流可稳定复现，并可通过日志和 trace 排障。
6. 文档、脚本、镜像、测试、监控和演示路径已经统一。

## 8. 外部参考与为什么值得采纳

下面这些资料直接支撑了上面的路线判断：

1. LangGraph 官方将 `interrupt()` 和 `Command(resume=...)` 作为标准 HITL 恢复模式，且明确 checkpoint 是 thread 级短期状态，不等同长期记忆。  
   参考：<https://docs.langchain.com/oss/python/langgraph/interrupts>  
   参考：<https://docs.langchain.com/oss/python/langgraph/persistence>  
   参考：<https://docs.langchain.com/oss/python/langgraph/graph-api>

2. LangChain 官方前端 HITL 示例也强调：关键是“可持久化中断 + 可恢复提交”，而不是前端手拼上下文。  
   参考：<https://docs.langchain.com/oss/python/langchain/frontend/human-in-the-loop>

3. MCP 官方授权规范要求资源绑定 token、audience 校验和最小权限 scope，并明确不建议 token passthrough。  
   参考：<https://modelcontextprotocol.io/specification/2025-11-25/basic/authorization>  
   参考：<https://modelcontextprotocol.io/docs/tutorials/security/authorization>

4. LangGraph 社区已经有真实 issue 暴露 cancel、pending writes、并行 interrupt 的坑，这些问题和本项目当前运行时设计高度相关。  
   参考：<https://github.com/langchain-ai/langgraph/issues/5672>  
   参考：<https://github.com/langchain-ai/langgraph/issues/5682>  
   参考：<https://github.com/langchain-ai/langgraph/issues/6626>

5. A2A 的 Task/Artifact 思路说明：长任务的关键结果不应只靠瞬时流式消息承载，持久化事件和任务状态更适合企业运行时。  
   参考：<https://a2a-protocol.org/v0.2.0/specification/>  
   参考：<https://a2a-protocol.org/latest/topics/streaming-and-async/>

6. 近期 memory 社区的真实反馈表明：没有治理的长期记忆很容易重复、过期、矛盾和污染。  
   参考：<https://github.com/mem0ai/mem0/issues/4573>  
   参考：<https://github.com/mem0ai/mem0/issues/4896>  
   参考：<https://mem0.ai/blog/programmatic-memory-management-for-ai-agents-with-mem0>

## 9. 这份文档和现有文档怎么配合看

推荐阅读顺序：

1. 先看 [Copilot 企业级 Agent 设计](./07-Copilot企业级Agent设计.md)，理解项目为什么这样拆层。
2. 再看本文，理解当前实现距离真正企业化还差哪几步。
3. 然后回到 [LocalLife Copilot 全链路教程](../04-notes/LocalLifeCopilot项目教程.md) 对照代码。
4. 最后看 [AgentOps 评测与 GenAI 追踪](../04-notes/AgentOps评测与GenAI追踪.md)、[企业级日志系统](../04-notes/企业级日志系统.md) 和 [面试高频题库](../05-interview/面试高频题库-后端Agent.md)，把运行时、排障和面试表达串起来。
