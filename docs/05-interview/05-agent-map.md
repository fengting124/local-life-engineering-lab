# Agent / RAG / MCP 面试地图

- Status: Active
- Type: Interview
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: Agent 文档

> 第一轮只覆盖 Copilot / Agent 主链路：用户输入、LangGraph Agent、Java MCP 工具、RAG、HITL、Memory、SSE、可观测性。知识点来自 xiaolinnote AI 面试体系，答案做项目化提炼，不复制原文。

## 本轮范围

代码范围：

- `copilot-agent-service/api/chat.py`
- `copilot-agent-service/agent/graph.py`
- `copilot-agent-service/agent/nodes.py`
- `copilot-agent-service/agent/state.py`
- `copilot-agent-service/agent/tool_router.py`
- `copilot-agent-service/mcp/mcp_client.py`
- `copilot-agent-service/rag/pipeline.py`
- `copilot-agent-service/rag/knowledge_tool.py`
- `copilot-agent-service/rag/vector_store.py`
- `copilot-agent-service/rag/bm25_store.py`
- `copilot-agent-service/session/checkpointer.py`
- `copilot-agent-service/session/hitl.py`
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/mcp/McpController.java`
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/mcp/McpTool.java`
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/mcp/ToolRegistry.java`
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/security/RbacFilter.java`
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/tool/QueryOrderTool.java`
- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/tool/ExecuteRefundTool.java`

对应 xiaolinnote 知识点：

- Agent：Agent 和普通 LLM 调用、ReAct、Workflow/Agent/Tools、Reflection、任务分解、Memory、Multi-Agent、手写 Agent 和框架取舍。
- RAG：RAG 流程、Chunking、Embedding、向量数据库、向量检索和关键词检索、Query Rewrite、多路召回、Rerank、幻觉控制、RAG 评估。
- 工具调用：Function Calling、MCP、FC 和 MCP 区别、Tool、SSE、LLM Gateway、Skill。
- LangChain/LangGraph：xiaolinnote 的 LangChain 专页当前仍是更新中；本项目用 LangGraph 落地状态机，用手写节点保留业务控制力。

## Agent 角色

| 角色 | 项目含义 | 权限边界 | 对应知识点 |
| --- | --- | --- | --- |
| `merchant` | 商家用户，查询自己的门店、订单、指标和知识库 | 只能访问自己 `merchant_id` 下的数据 | Agent 权限控制、RAG 权限过滤、工具权限 |
| `cs` | 客服，处理订单、支付、补偿、退款诊断 | 可访问客服工具，L4 操作需 HITL | Tool Calling、HITL、人审 |
| `admin` | 管理员，排查平台级问题 | 权限最高，但仍要审计和限流 | MCP Server 权限、审计、限流 |

面试讲法：项目不是把所有工具直接给模型。`ToolRouter` 先按角色过滤工具，Java MCP Server 再用 `RbacFilter` 和 `RbacContext` 二次校验，防止模型越权调用。

## 用户输入入口

| 入口 | 代码 | 作用 | 对应知识点 |
| --- | --- | --- | --- |
| `POST /chat` | `api/chat.py` | 接收用户问题，创建会话，走 Fast Path 或 LangGraph Agent | Agent 主入口、SSE 流式响应 |
| `POST /chat/resume` | `api/chat.py` | HITL 审批后恢复执行 | Human-in-the-loop、Checkpoint |
| SSE 事件 | `api/chat.py` | 输出 `agent_step`、`stream`、`tool_call`、`tool_result`、`hitl_request`、`final_answer` | 流式响应、Agent 可解释过程 |
| 输入防护 | `guardrails/input_checker.py` | 拦截越权、提示词注入、泄露系统提示等请求 | Prompt Injection、防幻觉、防越权 |

面试讲法：用户不是直接访问 Java 业务接口，而是进入 Python Agent Service。Agent 负责理解意图、选择工具、流式返回；Java MCP Server 负责真正的业务查询和高风险操作。

## Agent 主链路

业务场景：商家问“今天销售额怎么样？为什么某个订单退款失败？”

实现链路：

1. `api/chat.py` 接收请求，先做 `check_input`。
2. 简单指标问题命中 Fast Path，直接调用 `shop_metrics_query`，不走 LLM。
3. 复杂问题构造 `AgentState`，进入 `agent/graph.py` 的 LangGraph 状态机。
4. `llm_node` 读取 MCP 工具列表和 Python 原生 `knowledge_search`，绑定给 LLM。
5. LLM 产出工具调用后，`tool_node` 执行 Java MCP 工具或 RAG 工具。
6. 工具结果回到 LLM，必要时进入 `reflection_node` 反思修正。
7. 如果触发退款、补偿券等 L4 操作，进入 `hitl_node`，等待 `/chat/resume`。
8. `final_node` 保存最终答案和会话指标。

对应知识点：

- Agent 和普通 LLM：普通 LLM 只生成文本；本项目 Agent 有状态、有工具、有反思、有审批、有观测。
- ReAct：`llm_node -> tool_node -> llm_node` 就是 Thought/Action/Observation 循环的工程实现。
- Workflow vs Agent：LangGraph 是可控 Workflow，LLM 只在节点里做决策。
- Memory：`AgentState.messages` 是短期上下文，`conversation_summary` 是压缩摘要，MySQL checkpoint 支持恢复。

面试讲法：我项目的 Agent 不是单次 prompt。它是 LangGraph 状态机，LLM 负责决策，工具节点负责执行，状态里保存消息、步数、token、HITL 和摘要，所以能做多步推理和可恢复执行。

## Tool Calling / MCP

### Java MCP Server

| 实现 | 代码 | 作用 | 对应知识点 |
| --- | --- | --- | --- |
| MCP 入口 | `McpController.handle()` | JSON-RPC over HTTP，处理 `initialize`、`tools/list`、`tools/call` | MCP 协议、工具发现、工具执行 |
| 工具抽象 | `McpTool` | 统一 `getName`、`getDefinition`、`execute` | Tool 接口设计 |
| 工具注册 | `ToolRegistry.init()` | Spring 收集所有工具，按名称注册，重复名启动失败 | Spring Bean、工具注册中心 |
| 工具定义 | `ToolDefinition` | 描述 name、description、inputSchema、业务提示、HITL、角色 | Function Calling Schema、MCP Tool Schema |
| RBAC | `RbacFilter`、`RbacContext` | 从 Header 读取身份，绑定 ThreadLocal，工具执行前校验 | 权限隔离、安全边界 |
| 限流 | `ToolRateLimiter` | Redis `INCR + EXPIRE` 做用户级和工具级固定窗口限流 | 高并发限流、Redis 原子命令 |
| 审计 | `ToolAuditService` | 异步记录工具调用结果 | AgentOps、审计 |

### Python MCP Client

| 实现 | 代码 | 作用 | 对应知识点 |
| --- | --- | --- | --- |
| 初始化 | `McpClient.initialize()` | 握手确认 Java MCP Server 可用 | MCP initialize |
| 工具列表 | `McpClient.list_tools()` | 获取工具 Schema，300 秒 TTL 缓存 | 工具发现、Prompt 成本优化 |
| 工具调用 | `McpClient.call_tool()` | 发起 `tools/call`，提取 `content[0].text` | Tool Calling |
| 身份透传 | `McpClient._headers()` | 传 `X-User-Id`、`X-User-Role`、`X-Merchant-Id`、trace | 安全上下文、链路追踪 |
| 错误结构 | `McpToolError` | 区分参数错误、超时、内部错误 | Agent 失败恢复 |

面试讲法：Function Calling 更像模型输出一个函数名和参数；MCP 更像独立工具服务协议。本项目 Python Agent 通过 MCP Client 调 Java MCP Server，Java 侧统一做工具注册、权限、限流、审计和业务访问。

当前不足：

- `ToolAuditService` 是 `@Async`，但普通 `ThreadLocal` 的 `RbacContext` 不会自动传播到异步线程，审计里用户信息可能为空。
- MCP Server 依赖 Python Agent 注入身份 Header，生产上不能暴露公网，最好加 HMAC 或网关内网认证。

优化方案：

- 在进入异步审计前显式捕获 userId/role/merchantId，作为参数传入。
- 给 MCP 请求加内部签名、时间戳和重放保护。
- 将工具错误码和重试策略标准化，帮助 Agent 判断是否重试。

## Tool Router

| 实现 | 代码 | 对应知识点 | 面试表达 |
| --- | --- | --- | --- |
| 角色过滤 | `ToolRouter._filter_by_role()` | 工具权限、最小权限原则 | 不把无权工具暴露给模型，降低越权和误调概率。 |
| 任务过滤 | `ToolRouter._classify_task()` | Agent 工具选择、意图识别 | 根据诊断、指标、营销、知识问答等任务类型缩小工具集合。 |
| 上下文过滤 | `ToolRouter._filter_by_context()` | 安全工具调用 | 只有上下文显示订单已支付，才暴露退款工具。 |
| 并发安全表 | `TOOL_CONCURRENCY_SAFE` | 工具副作用、并发控制 | 只读工具可以并发，高风险写工具串行执行。 |

面试讲法：我没有把所有工具塞给 LLM，因为工具越多越容易误选、越权、token 成本也高。`ToolRouter` 先做角色、任务、上下文三层过滤，再把少量候选工具交给模型。

## RAG 知识库

### RAG 流程

1. `rag/ingest.py` 扫描 `rag/knowledge_base/**/*.md`。
2. `rag/pipeline.py` 分块，默认按字符窗口和 overlap 切 chunk。
3. `embedding_client.embed_documents_batch()` 调 embedding-service 生成向量。
4. `MilvusVectorStore.upsert()` 写入 Milvus，保存 `scope`、`merchant_id`、`source`、`title`。
5. `bm25_store.build_index()` 或 `add_document()` 构建关键词索引。
6. 用户问知识类问题时，`knowledge_search` 调 `retrieve()`。
7. 查询先做简单 Query Rewrite，再走向量检索和 BM25 检索。
8. RRF 融合多路召回结果。
9. `reranker_client.rerank()` 调 CrossEncoder 精排。
10. 分数低于阈值就拒答，避免编造。

### 显式知识点映射

| 功能/实现 | 项目代码 | 对应 xiaolinnote 知识点 | 面试回答 |
| --- | --- | --- | --- |
| 文档分块 | `rag/pipeline.py ingest_document()` | Chunking | 文档不能整篇入库，按 chunk 检索能提高召回粒度，overlap 防止语义被切断。 |
| Embedding | `embedding_client.py` | Embedding | 把 query 和 passage 转成向量，项目用 `query:` 和 `passage:` 前缀区分语义空间。 |
| 向量数据库 | `vector_store.py` | 向量数据库 | Milvus 存 chunk 向量和元数据，按相似度召回语义相关内容。 |
| 权限过滤 | `MilvusVectorStore.search()` | RAG 权限控制 | filter 只允许查 public 或当前 merchant_private 文档，防止跨商家泄露。 |
| BM25 | `bm25_store.py` | 向量检索 vs 关键词检索 | BM25 补足订单号、专有名词、短关键词这类向量检索弱点。 |
| RRF | `rag/pipeline.py` | 多路召回 | 向量和 BM25 分数尺度不同，RRF 按排名融合更稳。 |
| Rerank | `reranker_client.py` | 精排 | CrossEncoder 对 query-doc 相关性重新打分，提高最终上下文质量。 |
| 阈值拒答 | `rag/pipeline.py` | 防幻觉 | 相关性低就返回 found=false，让 Agent 承认不知道。 |
| 维度保护 | `vector_store.py` | Embedding 工程化 | Collection 记录维度，模型切换后维度不一致会拒绝写入，避免脏数据。 |
| RAG 工具 | `knowledge_tool.py` | Agent + RAG | RAG 被包装成 LangChain Tool，和 Java MCP 工具一样由 Agent 调用。 |

面试讲法：RAG 在项目里不是“把知识库塞进 prompt”。它先分块、向量化、写 Milvus，同时建 BM25。查询时走向量召回和关键词召回，RRF 融合后 rerank，低分拒答，最后把带来源的上下文交给 LLM。

当前不足：

- Query Rewrite 目前是简单规则，不是强语义改写。
- BM25 是进程内索引，服务重启需要重新 ingest，不适合大规模知识库。
- Embedding 服务失败时返回零向量，开发方便，但生产可能造成低质量召回。

优化方案：

- 接入更强的 Query Rewrite 和同义词词典。
- BM25 换成 Elasticsearch/OpenSearch 或持久化索引。
- Embedding 失败时直接降级为 BM25，避免零向量污染召回。
- 增加 RAG 引用强制校验，最终回答必须引用检索来源。

## Memory / Checkpoint

| 实现 | 代码 | 作用 | 对应知识点 |
| --- | --- | --- | --- |
| 短期消息 | `AgentState.messages` | 保存当前会话上下文 | Agent Memory |
| 会话持久化 | `session/manager.py` | 保存用户消息、助手消息、工具消息 | 长会话、可追溯 |
| LangGraph checkpoint | `AsyncMySQLCheckpointer` | 保存状态快照，支持 HITL 后恢复 | Checkpoint、Human-in-the-loop |
| 自动压缩 | `compact_node` | token 接近预算时摘要旧消息 | 上下文管理、Prompt 压缩 |
| 熔断 | `compact_failures` | 连续压缩失败后停止尝试 | 工程稳定性 |

面试讲法：项目里的 Memory 分两层。运行时用 `AgentState.messages` 保持短期上下文；持久层用 MySQL 保存消息和 LangGraph checkpoint。长会话超过 token 预算时，`compact_node` 会摘要旧消息并保留最近消息。

## HITL 高风险操作

业务场景：客服让 Agent 执行退款或补偿券。

实现链路：

1. `ExecuteRefundTool.getDefinition()` 标记 `xRequiresHitl=true`。
2. `tool_node` 识别高风险工具，先进入 `hitl_node`。
3. `hitl_node` 创建 `hitl_approval`，返回 `hitl_request` SSE。
4. 人工审批后调用 `/chat/resume`。
5. 审批通过时把 `approval_id` 注入工具参数，再执行 Java MCP 工具。
6. 审批拒绝时直接返回最终答案，不执行副作用。

对应知识点：

- Tool Calling：模型只提出工具调用意图，不直接执行高风险副作用。
- Human-in-the-loop：退款和补偿券必须人审。
- Checkpoint：等待审批期间需要保存 Agent 状态。
- 幂等和审计：高风险操作需要 `approval_id`、审计日志和失败可追踪。

面试讲法：高风险工具不是 LLM 说调用就调用。工具 Schema 标记 `xRequiresHitl`，Agent 先生成审批单并暂停，审批通过后才恢复执行，而且把 `approval_id` 传给 Java 后端做审计。

## Java 后端和 Agent 如何交互

完整链路：

`用户 -> Python /chat -> LangGraph -> McpClient.tools/call -> Java McpController -> ToolRegistry -> McpTool.execute -> Mapper 或 LocalLifeInternalClient -> local-life-server 内部接口 -> MySQL/Redis/ES -> 工具结果 -> LLM -> SSE final_answer`

典型例子：

- 查订单：`query_order` -> `QueryOrderTool.execute()` -> `CopilotOrderMapper.selectOrderByOrderNo()` -> 返回结构化订单信息。
- 执行退款：`execute_refund` -> HITL -> `ExecuteRefundTool.execute()` -> `LocalLifeInternalClient.refund()` -> local-life-server 内部退款接口。
- 查知识库：`knowledge_search` -> Python RAG，不走 Java MCP Server。

对应知识点：

- MCP：Java 服务作为工具提供者，Python Agent 作为工具消费者。
- Function Calling：LLM 产出工具名和 JSON 参数。
- RBAC：身份从 Python Header 传到 Java，Java 再校验。
- 可观测性：trace_id 从 Python 透传到 Java。

## 失败降级

| 失败点 | 当前处理 | 对应知识点 | 优化方向 |
| --- | --- | --- | --- |
| MCP 超时 | `McpToolError("tool_timeout")`，可提示重试 | 工具失败恢复 | 按工具设置超时、重试和熔断 |
| 工具参数错 | 结构化返回 reason/detail/hint | Function Calling 参数修正 | 让 LLM 根据 hint 自动修正一次 |
| Reranker 服务失败 | fallback 到向量/BM25 分数排序 | RAG 降级 | 增加降级指标和告警 |
| Milvus 不可用 | 返回 mock 或空结果 | RAG 降级 | 生产应返回不可用或仅 BM25 |
| Embedding 失败 | 返回零向量 | 工程降级 | 生产改为失败快返或 BM25 only |
| Checkpointer 失败 | fallback `MemorySaver` | Memory 降级 | 生产启动时强校验 MySQL checkpoint |
| HITL 拒绝 | 不执行工具，返回拒绝结果 | 安全降级 | 增加拒绝原因沉淀 |
| 简单指标问题 | Fast Path 直接查工具 | LLM 成本优化 | 扩展更多确定性 Fast Path |

## 可观测性

| 实现 | 代码 | 作用 | 对应知识点 |
| --- | --- | --- | --- |
| Trace | `trace.py genai_span()` | 记录 LLM、MCP RPC span | AgentOps、链路追踪 |
| Metrics | `agent/metrics.py` | 统计会话、LLM、工具、RAG、HITL、Guardrails 指标 | Prometheus/Grafana |
| Tool Audit | `ToolAuditService` | 记录工具调用审计 | Agent 安全审计 |
| Evals | `evals/metrics.py`、`evals/eval_cases.py` | 评估工具准确率、完成率、Recall@5、事实一致性、延迟和成本 | Agent/RAG 评估 |
| SSE 过程事件 | `api/chat.py` | 前端可看到步骤、工具调用、审批请求 | 可解释 Agent |

面试讲法：Agent 不能只看最终答案。项目记录工具调用次数、延迟、RAG 命中、HITL 状态、token 成本和 eval case，通过 Prometheus/Grafana 和日志追踪定位问题。

## 防幻觉

项目做法：

- RAG 相关性不足时拒答，而不是让模型自由编。
- `knowledge_search` 返回来源和 chunk 数，最终回答可引用来源。
- 工具结果结构化传回 LLM，减少靠猜。
- `guardrails/input_checker.py` 拦截提示词注入、越权查询和绕过审批。
- `ToolRouter` 限制模型可见工具，降低误调概率。
- `evals/metrics.py` 提供 factual consistency 和 hallucination 评估。

当前不足：

- 输出侧 `check_output` 需要确认是否在最终答案路径强制调用。
- RAG 引用还可以更严格，比如答案每个关键结论必须绑定 source。

优化方案：

- 最终输出前统一跑输出 Guardrail。
- 对知识问答增加“无来源不回答”策略。
- 建立黄金评测集，覆盖工具调用、知识问答和越权攻击。

## 面试重点功能

### 1. 商家指标快查

- 业务场景：商家问“今天营业额/订单量怎么样”。
- 接口入口：`POST /chat`。
- Agent 动作：`api/chat.py` Fast Path 命中后跳过 LLM。
- 工具动作：直接调用 `shop_metrics_query` MCP 工具。
- 事务边界：只读查询，无事务。
- 权限校验：Python 传 `merchant_id`，Java MCP Server 二次校验。
- 返回对象：SSE `final_answer`。
- 潜在问题：规则命中有限，表达稍复杂就走 LLM。
- 优化方案：扩展意图分类器，把高频确定性查询都做 Fast Path。
- 对应知识点：Agent 成本优化、工具调用、MCP、权限控制。
- 面试讲法：简单指标问题没必要走 LLM，我用 Fast Path 直接调用工具，降低延迟和 token 成本；复杂问题再交给 ReAct Agent。

### 2. 订单诊断工具调用

- 业务场景：客服问某订单为什么退款失败。
- 接口入口：`POST /chat`。
- Controller/节点：`llm_node` 选择工具，`tool_node` 执行工具。
- Java 工具：`QueryOrderTool.execute()`、`query_payment`、`query_mq_dead_letter`。
- 数据库动作：Mapper 查询订单、支付、死信消息。
- Redis 动作：MCP 工具限流使用 Redis。
- ES 动作：本链路无。
- Agent 动作：多步工具调用后生成诊断结论。
- 事务边界：只读诊断，无业务事务。
- 权限校验：`ToolRouter` 过滤 + `RbacFilter` 校验。
- 异常处理：`McpToolError` 结构化返回给 Agent。
- 潜在问题：工具结果多时容易超 token。
- 优化方案：工具返回摘要化，必要时让 Agent 分阶段诊断。
- 对应知识点：ReAct、Tool Calling、MCP、权限、可观测性。
- 面试讲法：订单诊断是典型 ReAct，模型先决定查订单，再根据结果查支付或死信，最后把多个工具结果合成结论。

### 3. 知识库问答

- 业务场景：商家问活动规则、平台政策或运营建议。
- 接口入口：`POST /chat`。
- Agent 动作：LLM 选择 Python 原生 `knowledge_search`。
- RAG 动作：Query Rewrite、Embedding、Milvus、BM25、RRF、Rerank。
- 数据库动作：知识库索引不查业务 MySQL，session/message 会写 MySQL。
- Redis 动作：本链路无核心 Redis。
- ES 动作：本链路不用 ES，BM25 当前为进程内索引。
- 事务边界：知识检索只读，无事务。
- 权限校验：Milvus/BM25 按 `scope` 和 `merchant_id` 过滤。
- 异常处理：低相关性返回 found=false。
- 潜在问题：知识库更新后 BM25 进程内索引依赖重新 ingest。
- 优化方案：持久化关键词索引，增强 Query Rewrite 和引用约束。
- 对应知识点：RAG、Embedding、向量数据库、混合检索、防幻觉。
- 面试讲法：知识库问答用 RAG，不让模型凭记忆回答。检索不到就拒答，检索到才基于上下文回答。

### 4. 退款 HITL

- 业务场景：客服要求 Agent 退款。
- 接口入口：`POST /chat`，恢复入口 `POST /chat/resume`。
- Agent 动作：识别 `execute_refund` 高风险工具，创建审批并暂停。
- Java 工具：`ExecuteRefundTool.execute()`。
- 数据库动作：写 `hitl_approval`、session、message；业务退款在 local-life-server 内部接口处理。
- Redis 动作：MCP 限流。
- ES 动作：无。
- 事务边界：Agent 审批记录和业务退款不是同一个本地事务。
- 权限校验：角色必须 `cs/admin`，且需要 `approval_id`。
- 异常处理：审批拒绝直接 final，不调用工具。
- 潜在问题：跨服务退款和审批需要更强幂等保证。
- 优化方案：用审批 ID 做业务幂等键，退款结果写审计和补偿任务。
- 对应知识点：HITL、Tool Calling 安全、Checkpoint、幂等。
- 面试讲法：高风险动作一定人审。LLM 只提出意图，系统暂停并发审批事件，审批通过后才执行工具。

## 本轮重点背诵 5 张

1. Agent 和普通 LLM 调用有什么区别？
2. 本项目 MCP 是怎么落地的？
3. RAG 在项目里的完整链路是什么？
4. 为什么 ToolRouter 不把所有工具都暴露给模型？
5. 高风险工具为什么要 HITL？
