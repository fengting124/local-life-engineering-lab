# 学习进度

- Status: Historical
- Type: Interview
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: 历史记录

## 2026-06-23：第一轮，PostService

本次分析了什么：

- `PostService` 的 9 个公开方法：`publishPost`、`getPostDetail`、`listPostsByShop`、`likePost`、`unlikePost`、`deletePost`、`addComment`、`listComments`、`deleteComment`。
- PostService 的 10 个业务组：笔记发布、查询、点赞/取消、删除、评论发布、评论查询、评论删除、Redis 限流和点赞计数、ES 同步、事务和一致性。
- 周边代码：`PostController`、`Post`、`Comment`、`PostMapper`、`CommentMapper`、`PostSearchService`、`PostDocument`、`AuthInterceptor`、`RateLimitInterceptor`、`GlobalExceptionHandler`、`MybatisPlusConfig`。
- xiaolincoding 对应主题：Java 基础、Java 集合、Java 并发、JVM、Spring 分层/事务/Bean/拦截器，MySQL 事务，Redis 数据结构/缓存问题/一致性，ES 倒排索引，AI Agent 面试体系入口。

学到了什么：

- CRUD 和业务动作不同。`insert/select/update/deleteById` 是 CRUD；发布、点赞、评论、删除是业务动作。
- Service 层是事务边界。评论发布和评论删除都跨 comment 表和 post 表，所以事务放在 `PostService`。
- Redis 点赞不是缓存普通对象，而是业务状态：Set 保存点赞关系，String 保存实时计数。
- 本地事务管不到 Redis 和 ES。PostService 当前同步双写能跑通，但一致性风险要主动说。
- ES 是搜索索引，不是主数据。MySQL 逻辑删除后，ES 要物理删除。
- `@RateLimit` 是接口级滑动窗口限流，`checkPublishRateLimit` 是业务级发布限频。
- 当前公开 GET 接口跳过 AuthInterceptor，`liked` 字段的“已登录用户识别”存在实现差异。
- Java 基础也能压到 PostService：Long ID 转 String、Integer 空值兜底、注解驱动校验/事务/逻辑删除、异常统一处理、JSON 序列化。
- Java 集合也能压到 PostService：List 返回列表、Set 表示点赞关系、Map 批量组装用户信息、Stream 做集合转换。
- Java 并发和 JVM 也能压到 PostService：ThreadLocal 传用户上下文，Redis Lua 解决分布式原子性，列表无分页会增加堆对象和 GC 压力。

还没理解什么：

- Post 点赞数从 Redis 定期同步回 MySQL/ES 的完整任务目前没有在本轮展开。
- PostService 是否已有集成测试覆盖 Redis/ES 失败场景，本轮未系统梳理。
- Agent 如何调用内容社区能力，本轮不展开，后续放 `05-agent-map.md`。
- ES 同步是否应该统一接入项目已有 Outbox 模块，需要在订单/MQ 阶段再深入。
- JVM 参数、GC 日志、容器内存限制还没有和项目部署结合，后续放 infra 阶段。

下次从哪里继续：

- 建议继续 PostService 的测试与改进点，不急着换模块。
- 优先看 `PostControllerTest` 和是否存在 PostService 单元/集成测试。
- 如果要进入新模块，建议从 `ShopService` 开始，因为它能补齐 Redis Cache Aside、Canal/binlog 和布隆过滤器。

已掌握的问题：

- Controller、Service、Mapper 分层意义。
- 为什么事务放 Service。
- `@Transactional` 作用和常见失效场景。
- BaseMapper、Wrapper、逻辑删除、MetaObjectHandler 在项目里的用法。
- Long/String/Integer、注解、异常、序列化、Stream 在 PostService 里的用法。
- List/Set/Map、HashMap key、ThreadLocal、Java 锁和 Redis Lua 的边界。
- Redis String、Set、SETNX、Lua 在 PostService 中的用途。
- 点赞为什么要幂等。
- Redis/MySQL/ES 为什么只能做最终一致。
- ES 和 MySQL 的分工。
- 删除笔记为什么 MySQL 逻辑删、ES 物理删。

待复习的问题：

- 并发点赞为什么 `SISMEMBER + SADD + INCR` 不安全。
- 取消点赞为什么也需要 Lua。
- Outbox 模式如何迁移 Post 的 ES 同步。
- 公开接口如何做可选登录态。
- 评论数和点赞数为什么采用不同存储策略。
- 为什么公开接口的可选登录态和 ThreadLocal 清理要分开讲。
- 为什么列表全量返回会带来 JVM 堆对象和 GC 压力。

本次重点背哪 5 张卡片：

1. 为什么事务放在 Service 层？
2. 点赞为什么不用每次写 MySQL？
3. PostService 的 Redis 和 MySQL 能强一致吗？
4. 发布笔记后为什么同步 ES？
5. 点赞当前实现有什么并发风险？

本次补充说明：

- 已确认可以读取 `https://www.xiaolincoding.com/interview/java.html`，并能继续打开集合、并发、JVM、Spring 子页。
- 已在 `02-feature-walkthrough.md` 增加“显式知识点映射矩阵”，把功能/实现逐条对应到 Java 基础、集合、并发、JVM、Spring、MyBatis Plus、Redis、ES。
- 已在 `03-question-map.md` 补充 Java 基础、集合、并发、JVM 的项目化问答。
- 已在 `04-review-cards.md` 补充对应复习卡片。

本次产出：

- `docs/05-interview/00-learning-plan.md`
- `docs/05-interview/01-project-map.md`
- `docs/05-interview/02-feature-walkthrough.md`
- `docs/05-interview/03-question-map.md`
- `docs/05-interview/04-review-cards.md`
- `docs/05-interview/06-progress.md`

## 2026-06-24：Agent / RAG / MCP 第一轮

本次分析了什么：

- xiaolinnote AI 面试题入口、Agent 专题、RAG 专题、工具调用/MCP 专题、LangChain 专题。
- Python Agent 主链路：`/chat`、`/chat/resume`、LangGraph、ReAct、ToolRouter、HITL、Memory、Auto Compact、SSE。
- Java MCP Server 主链路：`McpController`、`McpTool`、`ToolRegistry`、`RbacFilter`、`ToolRateLimiter`、`ToolAuditService`。
- RAG 链路：知识库 ingest、Chunking、Embedding、Milvus、BM25、RRF、Rerank、低相关性拒答。
- 可观测性和评估：Prometheus metrics、`genai_span`、Tool Audit、eval cases 和 RAG/Agent 指标。

学到了什么：

- Agent 和普通 LLM 的区别可以直接绑定 `agent/graph.py`：项目有状态机、工具节点、反思节点、人审节点和最终节点。
- MCP 和 Function Calling 是两层概念：模型输出 tool call，Python 再用 MCP 调 Java 工具服务。
- ToolRouter 是 Agent 安全的第一层：按角色、任务、上下文过滤工具，不把所有工具暴露给模型。
- Java MCP Server 是 Agent 和 Java 后端之间的安全边界：工具注册、RBAC、限流、审计都在这里做。
- RAG 不是简单拼 prompt：项目做了分块、向量检索、BM25、RRF、rerank、阈值拒答和来源返回。
- Memory 分运行时和持久化：`AgentState.messages` 是短期上下文，MySQL message/checkpoint 支持恢复，Auto Compact 控制 token。
- HITL 用在退款和补偿券：LLM 只提出调用意图，人审通过后才执行高风险工具。
- Agent 可观测性要看工具成功率、RAG 命中、LLM token、延迟、HITL 状态和评估集结果。

还没理解什么：

- 每个 Java MCP Tool 的业务细节还没有逐个展开，比如支付查询、死信查询、补偿券策略、活动草稿生成。
- `ToolAuditService` 异步审计的 ThreadLocal 上下文传播需要后续验证和修复。
- 输出侧 Guardrail 是否已经覆盖最终答案路径，需要继续查代码。
- Multi-Agent、A2A、LLM Gateway 目前只做概念映射，还没有项目落地实现。
- LangChain 专题页面当前仍是更新中，本轮主要结合 xiaolinnote 的手写 Agent/框架取舍内容和项目 LangGraph 实现。

下次从哪里继续：

- 建议继续 Agent 小范围，不急着扩展整个 Copilot。
- 下一轮优先拆 `knowledge_search`：从 ingest 到 retrieve 到最终回答，专门把 RAG 面试题吃透。
- 再下一轮拆 `execute_refund` HITL：审批、恢复、幂等、审计、失败补偿。
- 之后逐个拆 Java MCP Tool：`query_order`、`query_payment`、`query_mq_dead_letter`、`shop_metrics_query`。

已掌握的问题：

- Agent 和普通 LLM 调用区别。
- ReAct 在项目里的实现。
- Workflow、Agent、Tools 的项目边界。
- Function Calling 和 MCP 的区别。
- MCP Server 在 Java 中如何落地。
- ToolRouter 为什么要过滤工具。
- RAG 完整链路。
- Chunking、Embedding、向量数据库、BM25、RRF、Rerank 在项目里的作用。
- RAG 如何防幻觉。
- Memory、Checkpoint、Auto Compact 的作用。
- HITL 为什么用于退款和补偿券。
- Agent 如何调用 Java 后端。
- Agent 如何降级和观测。

待复习的问题：

- MCP 请求为什么需要内部签名和重放保护。
- 异步审计为什么拿不到普通 ThreadLocal。
- Embedding 服务失败为什么不能生产环境返回零向量。
- BM25 进程内索引有什么局限。
- RAG 低相关性拒答怎么和用户体验平衡。
- ToolRouter 关键词分类可能误判时怎么兜底。
- LangGraph 和纯手写 Agent 的取舍。

本次重点背哪 5 张卡片：

1. Agent 和普通 LLM 调用有什么区别？
2. 本项目 MCP 是怎么落地的？
3. RAG 在项目里的完整链路是什么？
4. 为什么 ToolRouter 不暴露所有工具？
5. 高风险工具为什么要 HITL？

本次产出：

- `docs/05-interview/05-agent-map.md`
- `docs/05-interview/00-learning-plan.md`
- `docs/05-interview/01-project-map.md`
- `docs/05-interview/03-question-map.md`
- `docs/05-interview/04-review-cards.md`
- `docs/05-interview/06-progress.md`
