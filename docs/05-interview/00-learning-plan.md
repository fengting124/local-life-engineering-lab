# LocalLife 面试学习计划

- Status: Active
- Type: Interview
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: 项目文档

> 目标：把 xiaolincoding 的 Java 后端与 AI Agent 高频题压到本项目代码里。每次只吃透一个小模块，能讲功能、链路、取舍、风险和优化。

## 资料来源

- xiaolincoding Java 基础面试题：`https://www.xiaolincoding.com/interview/java.html`
- xiaolincoding Java 集合面试题：`https://www.xiaolincoding.com/interview/collections.html`
- xiaolincoding Java 并发编程面试题：`https://www.xiaolincoding.com/interview/juc.html`
- xiaolincoding Java 虚拟机面试题：`https://www.xiaolincoding.com/interview/jvm.html`
- xiaolincoding Spring 面试题：`https://www.xiaolincoding.com/interview/spring.html`
- xiaolincoding MySQL 面试题：`https://xiaolincoding.com/interview/mysql.html`
- xiaolincoding Redis 数据结构：`https://www.xiaolincoding.com/redis/data_struct/command.html`
- xiaolincoding Redis 缓存三大问题：`https://www.xiaolincoding.com/redis/cluster/cache_problem.html`
- xiaolincoding Redis 和 MySQL 一致性：`https://www.xiaolincoding.com/redis/architecture/mysql_redis_consistency.html`
- xiaolincoding 快手 Java 面经中的 ES 倒排索引：`https://xiaolincoding.com/backend_interview/internet_giants/kuaishou.html`
- xiaolincoding AI 大模型面试题合集入口：`https://xiaolincoding.com/other/ai.html`
- xiaolinnote AI 面试题合集入口：`https://xiaolinnote.com/ai/`
- xiaolinnote Agent 面试专题：`https://xiaolinnote.com/ai/agent/`
- xiaolinnote RAG 面试专题：`https://xiaolinnote.com/ai/rag/rag_info.html`
- xiaolinnote 工具调用/MCP 面试专题：`https://xiaolinnote.com/ai/tools/tools_info.html`
- xiaolinnote LangChain 面试专题：`https://xiaolinnote.com/ai/langchain/langchain_info.html`

## 阶段 1：PostService 内容社区闭环

状态：进行中，本轮已建立第一版文档。

目标：

- 讲清楚笔记发布、查询、点赞、删除、评论的业务链路。
- 区分 CRUD、业务动作、缓存操作、搜索索引同步。
- 解释 Service 层事务边界、Redis/MySQL 一致性、ES 双写风险。
- 显式说明每个功能或实现点对应的小林 Java 基础、集合、并发、JVM、Spring、MyBatis Plus、Redis、ES 知识点。

涉及代码：

- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/post/controller/PostController.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/post/service/PostService.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/entity/Post.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/entity/Comment.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/mapper/PostMapper.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/domain/mapper/CommentMapper.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/search/service/PostSearchService.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/common/interceptor/AuthInterceptor.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/common/ratelimit/RateLimitInterceptor.java`

对应高频题：

- Java 基础：JVM/JDK/JRE；Long、Integer、String；装箱拆箱；面向对象封装；final/static；注解；反射；异常；Stream；JSON 序列化。
- Java 集合：List、Set、Map；HashMap 的 key、equals/hashCode；ArrayList 非线程安全；集合遍历和 Stream 收集。
- Java 并发：ThreadLocal；线程安全；原子性；锁和 CAS 的边界；为什么分布式场景不能只靠 Java 锁。
- JVM：堆/栈对象引用；对象生命周期；GC；内存泄漏和 OOM；ThreadLocal 使用后必须清理。
- Spring：Controller、Service、Mapper 分层；`@Service`；`@Transactional`；事务为什么放 Service；事务失效；Bean；依赖注入；构造器注入；统一异常处理；拦截器和过滤器。
- MyBatis Plus：BaseMapper；select/insert/update/delete；LambdaQueryWrapper；LambdaUpdateWrapper；逻辑删除；MetaObjectHandler。
- Redis：String、Set、SETNX、Lua、限流、点赞幂等、缓存一致性、缓存穿透/击穿/雪崩。
- 事务和一致性：本地事务、业务动作、最终一致性、补偿、MQ、Outbox、同步双写、异步重试。
- Elasticsearch：倒排索引、MySQL 和 ES 查询差异、发布同步、删除同步、双写失败。
- 高并发和幂等：重复点赞、重复取消、评论数不为负、限流和分布式锁边界。

产出文档：

- `docs/05-interview/01-project-map.md`
- `docs/05-interview/02-feature-walkthrough.md`
- `docs/05-interview/03-question-map.md`
- `docs/05-interview/04-review-cards.md`
- `docs/05-interview/06-progress.md`

复习任务：

- 背诵 `04-review-cards.md` 中本轮重点 5 张卡片。
- 用 `02-feature-walkthrough.md` 复述 `publishPost`、`likePost`、`addComment` 三条链路。
- 用 `03-question-map.md` 复述“事务管不到 Redis 和 ES”的回答。
- 用 `02-feature-walkthrough.md` 的“显式知识点映射矩阵”逐行说明：这个实现点为什么能回答小林的哪类题。

## 阶段 2：ShopService 缓存和搜索

状态：待开始。

目标：

- 讲清楚门店查询、创建、状态流转、缓存模式、Canal/binlog 缓存失效、ES 门店搜索。
- 对照 Redis 缓存一致性、缓存穿透、缓存击穿、缓存雪崩。

涉及代码：

- `module/shop/controller`
- `module/shop/service`
- `module/shop/canal`
- `module/search/service/ShopSearchService.java`
- `common/bloom/BloomFilterService.java`

对应高频题：

- Cache Aside 怎么做。
- 先更新 DB 再删缓存有什么风险。
- 为什么用 Canal 订阅 binlog。
- 布隆过滤器解决什么问题。
- ES 为什么适合全文检索和 Geo 检索。

产出文档：

- 继续更新 `02-feature-walkthrough.md`、`03-question-map.md`、`04-review-cards.md`、`06-progress.md`。

复习任务：

- 用“门店详情查询”讲缓存一致性。
- 用“门店搜索”讲 ES 和 MySQL 的分工。

## 阶段 3：SeckillService 高并发和 Lua

状态：待开始。

目标：

- 讲清楚秒杀资格校验、库存预扣、一人一单、Redis Lua 原子性、Redisson/DB 兜底。

涉及代码：

- `module/seckill/service/SeckillService.java`
- `src/main/resources/lua/seckill.lua`
- `config/RedissonConfig.java`

对应高频题：

- 秒杀为什么不能只靠 MySQL。
- Lua 为什么能保证 Redis 多操作原子性。
- 分布式锁适合什么场景。
- 数据库唯一索引如何做幂等兜底。

产出文档：

- 新增秒杀 feature walkthrough 和卡片。

复习任务：

- 能用 2 分钟讲清“Redis 预扣 + DB 兜底”的完整方案。

## 阶段 4：Order/Payment/Outbox 一致性

状态：待开始。

目标：

- 讲清楚订单创建、支付回调、状态机、CAS、Transactional Outbox、RocketMQ、补偿和幂等。

涉及代码：

- `module/order/service`
- `module/mq/service`
- `domain/entity/OutboxMessage.java`

对应高频题：

- 什么是本地事务。
- 什么时候需要 MQ。
- Outbox 模式解决什么。
- 消息重复消费如何幂等。

产出文档：

- 更新一致性专题和面试卡片。

复习任务：

- 能用项目代码回答“本地事务能不能保证 MQ 消息不丢”。

## 阶段 5：Copilot / Agent / Python Agent

状态：进行中，本轮已建立 Agent / RAG / MCP 第一版文档。

目标：

- 建立 `docs/05-interview/05-agent-map.md`。
- 讲清 Java MCP Server、Python Agent、RAG、Tool Calling、HITL、Memory、SSE、AgentOps。
- 显式说明每个 Agent 功能对应 xiaolinnote 的哪个知识点。
- 先覆盖 `/chat` 主链路、MCP 工具、RAG 知识库、HITL、Memory/Checkpoint 和可观测性，不扩展全部业务工具。

涉及代码：

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
- `copilot-agent-service/evals`
- `local-life-copilot/src/main/java/.../mcp/McpController.java`
- `local-life-copilot/src/main/java/.../mcp/McpTool.java`
- `local-life-copilot/src/main/java/.../mcp/ToolRegistry.java`
- `local-life-copilot/src/main/java/.../security/RbacFilter.java`
- `local-life-copilot/src/main/java/.../tool/QueryOrderTool.java`
- `local-life-copilot/src/main/java/.../tool/ExecuteRefundTool.java`

对应高频题：

- Agent 和普通 LLM 调用区别。
- ReAct、Workflow/Agent/Tools、Reflection、任务分解、Memory。
- Tool Calling / Function Calling、MCP、MCP 和 Function Calling 区别。
- RAG、Chunking、Embedding、向量数据库、BM25、RRF、Rerank、RAG 幻觉控制和评估。
- Agent 如何调用 Java 后端业务接口。
- Agent 失败如何降级、如何观测、如何防幻觉。
- LangChain/LangGraph 和手写 Agent 的取舍。

产出文档：

- `docs/05-interview/05-agent-map.md`
- 更新 `03-question-map.md` 和 `04-review-cards.md`。
- 更新 `06-progress.md`。

复习任务：

- 能从“商家问今天卖了多少钱”讲完整 Agent 工具调用链路。
- 能从“商家问活动规则”讲完整 RAG 链路。
- 能从“客服要求退款”讲清 HITL 链路。
- 背诵 Agent 本轮重点 5 张卡片。

## 阶段 6：Infra 和可观测性

状态：待开始。

目标：

- 讲清 Prometheus、Grafana、Loki、Zipkin、Nginx、Docker Compose、健康检查和压测。

涉及代码：

- `infra`
- `performance-tests`
- `common/metrics`
- `config/ObservabilityConfig.java`

对应高频题：

- 如何排查接口慢。
- 如何做日志和 trace 关联。
- 如何看 Redis/DB/ES/MQ 故障。

产出文档：

- 运维面试专题卡片。

复习任务：

- 能讲“一个 PostService 请求慢了，怎么定位”。
