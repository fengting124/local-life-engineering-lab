# 复习卡片：PostService 第一轮

- Status: Active
- Type: Interview
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: 项目文档

> Q/A 尽量短，适合背诵。每张卡片只解决一个问题。

## 本轮重点背诵 5 张

1. Q：为什么事务放在 Service 层？
   A：Service 是业务边界。PostService 发评论要插评论并更新评论数，事务放 Service 才能覆盖多个 Mapper。

2. Q：点赞为什么不用每次写 MySQL？
   A：点赞是高频热点更新，直接写 MySQL 会有行锁竞争。项目用 Redis Set 记用户、String 记计数。

3. Q：PostService 的 Redis 和 MySQL 能强一致吗？
   A：不能。Spring 本地事务只管 MySQL，Redis 要靠最终一致、定时同步和补偿。

4. Q：发布笔记后为什么同步 ES？
   A：MySQL 是主数据，ES 是搜索索引。发布后写 ES，用户才能通过标题、正文、门店名搜到笔记。

5. Q：点赞当前实现有什么并发风险？
   A：`SISMEMBER + SADD + INCR` 不是原子操作，并发重复点赞可能多加计数，应该改 Lua。

## Spring

## Java 基础

Q：JVM、JDK、JRE 怎么区分？
A：JVM 执行字节码，JRE 是运行环境，JDK 是开发工具包。local-life-server 用 JDK 编译，用 JVM 运行。

Q：为什么 VO 里的 ID 返回 String？
A：数据库雪花 ID 是 Long，前端 JS Number 可能丢精度，所以 `PostVO.postId` 返回 String。

Q：Integer 和 int 在项目里有什么风险？
A：Integer 可以为 null，拆箱可能 NPE。`likeCount` 读取时要做 null 兜底。

Q：注解在项目里怎么生效？
A：框架读取注解执行逻辑。`@Valid` 做校验，`@Transactional` 做事务，`@TableLogic` 做逻辑删除，`@RateLimit` 做限流。

Q：业务异常为什么继承 RuntimeException？
A：让异常穿透到 `GlobalExceptionHandler`，避免每层 throws/try-catch。

Q：String 在 PostService 里用在哪里？
A：Redis key、JSON 图片字段、VO 字符串 ID 都是 String。

## Java 集合

Q：List、Set、Map 在 PostService 里分别干什么？
A：List 返回有序列表，Set 表示点赞去重，Map 用 userId 快速组装作者信息。

Q：HashMap 的 equals/hashCode 怎么映射到项目？
A：`Map<Long, User>` 用 Long 做 key，Long 的 equals/hashCode 稳定，适合按 userId 查作者。

Q：Stream API 在项目里用在哪里？
A：`listPostsByShop` 和 `listComments` 用 stream 提取 userId、去重、转 Map、转 VO。

Q：为什么不用 List 判断用户是否点赞？
A：List 查找是 O(n)，Set 判断成员接近 O(1)，更适合点赞关系。

## Java 并发和 JVM

Q：ThreadLocal 在项目里解决什么？
A：`UserContext` 用 ThreadLocal 保存当前用户，Service 不用层层传 userId。

Q：ThreadLocal 为什么必须清理？
A：Tomcat 线程池会复用线程，不清理会串用户，也有内存泄漏风险。

Q：为什么点赞不能用 synchronized 解决？
A：synchronized 只锁当前 JVM，项目多实例时无效；点赞应靠 Redis Lua 或 DB 约束。

Q：Redis Lua 和 Java 锁有什么区别？
A：Java 锁管单进程，Lua 在 Redis 内原子执行，能覆盖多个应用实例的并发请求。

Q：PostService 创建的 VO 对 JVM 有什么影响？
A：VO 是堆对象。列表不分页会创建大量对象，增加内存和 GC 压力。

## Spring

Q：Controller、Service、Mapper 各负责什么？
A：Controller 接 HTTP，Service 写业务和事务，Mapper 做 DB CRUD。PostService 是业务编排中心。

Q：`@Service` 在 PostService 里有什么作用？
A：让 PostService 成为 Spring Bean，能被注入，也能被事务 AOP 代理。

Q：`@Transactional` 是什么？
A：Spring 声明式事务。方法成功提交，抛异常回滚。PostService 的发布、删除、评论写操作用了它。

Q：`@Transactional` 什么时候会失效？
A：自调用、非 public、异常被吞、checked exception 默认不回滚、类不是 Spring Bean 都可能失效。

Q：为什么 PostService 用构造器注入？
A：依赖显式、不可变、必填，测试也方便。项目用 `@RequiredArgsConstructor` 注入 final 字段。

Q：统一异常处理怎么做？
A：Service 抛 `BizException`，`GlobalExceptionHandler` 统一转 `Result.fail` 和 HTTP 状态码。

Q：拦截器和过滤器区别是什么？
A：Filter 属于 Servlet，更靠前；Interceptor 属于 Spring MVC，围绕 Controller，能注入 Bean，适合鉴权限流。

Q：UserContext 是怎么来的？
A：`AuthInterceptor` 校验 token 后把 `LoginUserDTO` 写入 ThreadLocal，请求结束清理。

## MyBatis Plus

Q：BaseMapper 提供什么？
A：通用 CRUD。PostMapper 继承后直接用 `insert`、`selectById`、`selectList`、`deleteById`。

Q：`selectById` 对逻辑删除数据会怎样？
A：MyBatis Plus 会自动过滤 `deleted=1`，查不到已逻辑删除数据。

Q：LambdaQueryWrapper 用在哪里？
A：`listPostsByShop` 按 shopId、status 过滤，并按 createdAt 倒序。

Q：LambdaUpdateWrapper 用在哪里？
A：评论发布和删除时更新 `post.comment_count`。

Q：逻辑删除在项目里怎么做？
A：Post 和 Comment 的 `deleted` 加 `@TableLogic`，`deleteById` 改成更新 deleted。

Q：MetaObjectHandler 有什么用？
A：插入和更新时自动填充 `createdAt`、`updatedAt`。

## Redis

Q：PostService 用了哪些 Redis 数据结构？
A：String 做点赞计数和 SETNX 限流，Set 存点赞用户，ZSet + Lua 做接口滑动窗口限流。

Q：SETNX 怎么做发布限流？
A：写 `post:publish:limit:{userId}`，TTL 60 秒。写成功放行，写失败说明 60 秒内发过。

Q：Redis Set 如何判断是否点赞？
A：`SISMEMBER post:like:users:{postId} userId`，存在就是已点赞。

Q：Redis String 如何做点赞计数？
A：`post:like:count:{postId}` 存整数，点赞 INCR，取消 DECR，查询 GET。

Q：为什么点赞要幂等？
A：前端重复点击、网络重试都可能重复请求，多次点赞结果应等于一次。

Q：重复取消点赞怎么处理？
A：如果用户不在点赞 Set 中，直接返回成功，不再 DECR。

Q：Redis 失败时查询点赞数怎么办？
A：`getRealTimeLikeCount` 捕获异常，降级返回 DB 快照 `post.likeCount`。

Q：Lua 能优化点赞什么问题？
A：把 `SADD + INCR` 或 `SREM + DECR` 合成原子脚本，避免并发计数错。

Q：缓存穿透是什么？
A：请求的数据 Redis 没有、DB 也没有，大量请求会直接打 DB。

Q：缓存击穿是什么？
A：热点 Key 过期，大量请求同时打 DB。

Q：缓存雪崩是什么？
A：大量 Key 同时失效或 Redis 故障，流量集中压到 DB。

## 事务和一致性

Q：事务和业务有什么区别？
A：业务是用户动作，事务是数据库 ACID 手段。发笔记是业务，MySQL insert 只是其中的事务部分。

Q：本地事务能保证 ES 一致吗？
A：不能。ES 不参与 MySQL 事务，发布和删除的 ES 同步要靠最终一致补偿。

Q：什么是最终一致性？
A：短时间可不一致，但通过重试、对账、补偿，最终达到正确状态。

Q：什么是补偿机制？
A：外部同步失败后，系统重新执行或修正状态，比如重试删除 ES 文档。

Q：什么是 Outbox 模式？
A：业务事务内写业务表和消息表，事务提交后异步投递，失败可重试。

Q：什么时候需要 MQ？
A：跨系统副作用需要异步解耦、失败重试、削峰时，比如 MySQL 提交后同步 ES。

Q：什么时候用同步双写？
A：简单场景、低流量、失败可接受时可以先用。PostService 当前发布和删除就是同步双写。

Q：什么时候用异步重试？
A：外部系统不稳定、需要最终一致、不能阻塞主链路时用，比如 ES 同步。

## Elasticsearch

Q：为什么用 ES？
A：ES 用倒排索引做全文检索，适合搜标题、正文、门店名；MySQL 负责事务主数据。

Q：ES 和 MySQL 查询区别是什么？
A：MySQL 擅长结构化查询和事务，ES 擅长分词搜索、相关性排序和搜索过滤。

Q：PostDocument 为什么冗余 shopName？
A：ES 不适合 join，冗余门店名能直接按门店名召回笔记。

Q：删除笔记为什么要删 ES？
A：MySQL 逻辑删除后，搜索结果不能再出现该笔记，所以 ES 文档要物理删除。

Q：ES 双写失败怎么办？
A：当前是风险点，后续用 Outbox/MQ 重试，ES 操作做幂等。

## 高并发和幂等

Q：评论数为什么不用 Redis？
A：评论频率比点赞低，直接 MySQL 更新可接受，且和评论插入放同一事务更一致。

Q：评论数为什么防负数？
A：并发或重复删除可能多扣，`GREATEST(comment_count - 1, 0)` 防止负数。

Q：listPostsByShop 当前有什么性能风险？
A：不分页，且每篇笔记逐个读 Redis 点赞状态，数据多时压力大。

Q：如何优化 listPostsByShop？
A：加分页，并用 Redis pipeline 批量读点赞数和 liked 状态。

Q：公开接口 liked 字段有什么问题？
A：AuthInterceptor 对公开 GET 直接放行，不解析 token，已登录用户也可能显示 `liked=false`。

## Agent 本轮重点背诵 5 张

1. Q：Agent 和普通 LLM 调用有什么区别？
   A：普通 LLM 只生成文本；Agent 有状态、会选工具、会观察结果、能反思、审批和恢复。

2. Q：本项目 MCP 是怎么落地的？
   A：Python Agent 通过 `McpClient` 调 Java `McpController`，Java 统一做工具发现、调用、权限、限流和审计。

3. Q：RAG 在项目里的完整链路是什么？
   A：文档分块、Embedding、写 Milvus 和 BM25；查询时向量召回 + BM25 + RRF + rerank，低分拒答。

4. Q：为什么 ToolRouter 不暴露所有工具？
   A：工具越多越容易误选、越权、浪费 token。项目按角色、任务和上下文过滤工具。

5. Q：高风险工具为什么要 HITL？
   A：退款和补偿券有资金风险。LLM 只提出调用意图，审批通过后才执行工具。

## Agent / RAG / MCP

Q：ReAct 在项目里怎么体现？
A：`llm_node` 产出工具调用，`tool_node` 执行工具并写回结果，再回到 `llm_node` 继续推理。

Q：Workflow、Agent、Tools 分别是什么？
A：LangGraph 是 Workflow，LLM 节点和状态组成 Agent，Java MCP 和 `knowledge_search` 是 Tools。

Q：Function Calling 是什么？
A：模型输出函数名和 JSON 参数，应用侧校验并执行，结果再返回给模型。

Q：MCP 和 Function Calling 有什么区别？
A：Function Calling 是模型输出调用意图；MCP 是工具服务的发现和调用协议。

Q：Agent 如何调用 Java 后端？
A：Python 通过 MCP 调 Java 工具，Java 工具再查 Mapper 或调用 local-life-server 内部接口。

Q：Java MCP Server 做了哪些安全控制？
A：`RbacFilter` 做身份上下文，工具按角色校验，`ToolRateLimiter` 限流，`ToolAuditService` 审计。

Q：Tool Schema 为什么要缓存？
A：工具 Schema 变化少，`McpClient.list_tools` 做 300 秒 TTL 缓存，减少每轮 Agent 的 HTTP 开销。

Q：RAG 为什么不用微调解决？
A：业务知识变化快，RAG 更新知识库即可生效；微调成本高且不适合频繁更新事实。

Q：Chunking 解决什么问题？
A：把长文档拆成可检索片段，召回更精确；overlap 防止语义被切断。

Q：Embedding 在项目里做什么？
A：把 query 和文档 chunk 转成向量，交给 Milvus 做语义相似度搜索。

Q：Milvus 的权限过滤怎么做？
A：检索时 filter 只允许 public 或当前 `merchant_id` 的 merchant_private 文档。

Q：为什么要 BM25？
A：向量检索擅长语义，BM25 擅长订单号、活动名、专有词等精确匹配。

Q：RRF 解决什么问题？
A：向量和 BM25 分数尺度不同，RRF 按排名融合，结果更稳。

Q：Rerank 解决什么问题？
A：召回先求不漏，rerank 再精排，把最相关 chunk 放前面。

Q：RAG 如何防幻觉？
A：低相关性拒答，回答基于检索上下文，并返回 sources 方便校验。

Q：Memory 在 Agent 里是什么？
A：`AgentState.messages` 是短期记忆，MySQL message 和 checkpoint 是持久记忆。

Q：Auto Compact 解决什么？
A：上下文接近 token 预算时摘要旧消息，保留最近消息，避免上下文爆掉。

Q：HITL 链路怎么走？
A：高风险工具先创建审批并暂停，`/chat/resume` 审批通过后带 `approval_id` 恢复执行。

Q：Agent 失败怎么降级？
A：工具超时可重试，RAG 低分拒答，reranker 失败 fallback，checkpoint 失败退 MemorySaver。

Q：Agent 如何做可观测性？
A：Prometheus 记录会话、工具、LLM、RAG、HITL 指标，`genai_span` 记录 LLM/MCP span。

Q：LangGraph 和手写 Agent 怎么取舍？
A：LangGraph 管状态机，业务安全、ToolRouter、HITL、MCP 和 RAG 由项目手写控制。
