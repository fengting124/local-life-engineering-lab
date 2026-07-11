# 项目地图：第一轮 PostService 视角

- Status: Active
- Type: Interview
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: 项目文档

> 本文先建立全局骨架，再只展开 PostService 相关对象。后续每分析一个模块再补细节。

## 全局模块

| 模块 | 职责 | 本轮关系 |
|---|---|---|
| `local-life-server` | Java Spring Boot 主后端，承载用户、门店、笔记、订单、秒杀、搜索、内部接口 | 本轮重点 |
| `local-life-copilot` | Java MCP Server，把后端业务能力包装成 Agent 可调用工具 | 本轮只识别边界，不分析 |
| `copilot-agent-service` | Python FastAPI Agent，包含 RAG、LangGraph、Memory、HITL、Evals | 本轮只识别边界，不分析 |
| `infra` | MySQL、Redis、ES、RocketMQ、Prometheus、Grafana、Loki、Nginx 等本地基础设施 | 本轮涉及 Redis 和 ES |

## local-life-server 包结构

| 包 | 作用 | PostService 相关代码 |
|---|---|---|
| `module/post/controller` | HTTP 入口，接收参数，调用 Service，返回 `Result<T>` | `PostController` |
| `module/post/service` | 业务编排、事务边界、权限校验、Redis 操作、ES 同步触发 | `PostService` |
| `module/post/dto` | 请求 DTO 和返回 VO | `CreatePostRequest`、`CreateCommentRequest`、`PostVO`、`CommentVO` |
| `domain/entity` | 数据库表实体，映射 MySQL 表 | `Post`、`Comment`、`User`、`Shop` |
| `domain/mapper` | MyBatis Plus Mapper，提供 CRUD | `PostMapper`、`CommentMapper`、`UserMapper`、`ShopMapper` |
| `module/search` | ES 文档、Repository、搜索 Service | `PostSearchService`、`PostDocument`、`PostSearchRepository` |
| `common/interceptor` | 登录态解析、白名单、ThreadLocal 清理 | `AuthInterceptor` |
| `common/ratelimit` | 接口限流注解和 Redis Lua 滑动窗口拦截器 | `@RateLimit`、`RateLimitInterceptor` |
| `common/context` | 请求级用户上下文 | `UserContext`、`LoginUserDTO` |
| `common/exception` | 业务异常和全局异常处理 | `BizException`、`GlobalExceptionHandler` |
| `common/result` | 统一响应和错误码 | `Result`、`ErrorCode` |
| `config` | Spring MVC、Redis、ES、MyBatis Plus 配置 | `WebMvcConfig`、`MybatisPlusConfig` |

## PostService 核心入口

Controller：

- `POST /api/v1/posts` -> `PostController.publishPost()` -> `PostService.publishPost()`
- `GET /api/v1/posts/{postId}` -> `PostController.getPostDetail()` -> `PostService.getPostDetail()`
- `GET /api/v1/shops/{shopId}/posts` -> `PostController.listPostsByShop()` -> `PostService.listPostsByShop()`
- `POST /api/v1/posts/{postId}/likes` -> `PostController.likePost()` -> `PostService.likePost()`
- `DELETE /api/v1/posts/{postId}/likes` -> `PostController.unlikePost()` -> `PostService.unlikePost()`
- `DELETE /api/v1/posts/{postId}` -> `PostController.deletePost()` -> `PostService.deletePost()`
- `POST /api/v1/posts/{postId}/comments` -> `PostController.addComment()` -> `PostService.addComment()`
- `GET /api/v1/posts/{postId}/comments` -> `PostController.listComments()` -> `PostService.listComments()`
- `DELETE /api/v1/posts/{postId}/comments/{commentId}` -> `PostController.deleteComment()` -> `PostService.deleteComment()`

## Controller、Service、Mapper、DTO、VO、Entity、Config 分工

| 层 | 项目代码 | 负责什么 | 不负责什么 |
|---|---|---|---|
| Controller | `PostController` | 路由、`@Valid` 参数校验、路径参数绑定、统一响应包装 | 不写业务规则，不直接操作 DB/Redis/ES |
| Service | `PostService` | 业务编排、权限、事务、Redis 点赞和限流、触发 ES 同步、组装 VO | 不暴露 HTTP 细节，不写 SQL 字符串查询主体 |
| Mapper | `PostMapper`、`CommentMapper` | MyBatis Plus 通用 CRUD，接收 Wrapper 条件 | 不做业务判断 |
| DTO | `CreatePostRequest`、`CreateCommentRequest` | 接收前端输入，做字段级校验 | 不返回给前端展示 |
| VO | `PostVO`、`CommentVO` | 返回前端展示，包含冗余昵称、头像、门店名、字符串 ID | 不直接映射数据库表 |
| Entity | `Post`、`Comment` | 映射 MySQL 表，声明主键、逻辑删除、自动填充 | 不承载接口展示语义 |
| Config | `WebMvcConfig`、`MybatisPlusConfig` | 注册拦截器、分页插件、自动填充处理器 | 不写具体业务流程 |

面试讲法：

> 我这个项目按 Controller、Service、Mapper 分层。Controller 只处理 HTTP 和参数校验，Service 放业务编排和事务，Mapper 只负责数据库访问。比如发笔记时，Controller 只接收 `CreatePostRequest`，真正的门店状态校验、Redis 限流、MySQL 写入和 ES 同步都在 `PostService.publishPost()`，这样事务边界和业务规则是集中的。

## PostService 依赖图

`PostService` 构造器注入这些 Bean：

- `PostMapper`：写入、查询、逻辑删除 post 表。
- `ShopMapper`：发布时校验门店是否 ONLINE，组装 VO 时取门店名。
- `UserMapper`：组装作者和评论者昵称、头像。
- `CommentMapper`：评论新增、查询、逻辑删除。
- `PostSearchService`：发布后同步 ES，删除后移除 ES 文档。
- `StringRedisTemplate`：点赞计数、点赞用户 Set、发布限流 Key。
- `ObjectMapper`：图片列表和 JSON 字符串互转。

构造器注入来自 Lombok `@RequiredArgsConstructor`。好处是依赖不可变、便于测试、避免字段注入隐藏依赖。

## 数据模型

`Post` 对应 `post` 表：

- 主键：`id`，MyBatis Plus `IdType.ASSIGN_ID` 生成雪花 ID。
- 逻辑外键：`userId`、`shopId`。
- 内容字段：`title`、`content`、`images`。`images` 是 JSON 数组字符串。
- 计数字段：`likeCount` 是 DB 快照，实时值在 Redis；`commentCount` 由评论动作同步更新 DB。
- 状态字段：`status = PUBLISHED`。
- 逻辑删除：`@TableLogic deleted`。
- 自动填充：`createdAt`、`updatedAt`。

`Comment` 对应 `comment` 表：

- 主键：`id`。
- 逻辑外键：`postId`、`userId`。
- `parentId = 0` 表示一级评论。
- `content` 最多 512 字符。
- `@TableLogic deleted`。
- `createdAt`、`updatedAt` 自动填充。

## Redis Key

| Key | 类型 | 使用方法 | 业务含义 |
|---|---|---|---|
| `post:like:count:{postId}` | String | `set`、`get`、`increment`、`decrement` | 实时点赞数 |
| `post:like:users:{postId}` | Set | `isMember`、`add`、`remove` | 某用户是否点过赞 |
| `post:publish:limit:{userId}` | String | `setIfAbsent` with TTL | Service 内发布频率限制 |
| `rate_limit:post:publish:{userId/IP}` | ZSet | Lua 脚本 | Controller 注解滑动窗口限流 |
| `rate_limit:post:comment:{userId/IP}` | ZSet | Lua 脚本 | 评论接口限流 |

面试讲法：

> 点赞用了两个 Redis Key：String 存计数，Set 存用户集合。String 适合 O(1) 自增自减，Set 适合用 `SISMEMBER` 判断“我是否点赞”。但现在 `SADD + INCR` 不是 Lua 原子脚本，存在并发重复点赞导致计数偏差的风险，后续应该用 Lua 以 `SADD` 返回值决定是否 `INCR`。

## ES 边界

Post 搜索索引：

- `PostDocument` 对应 `post_index`。
- `title`、`content`、`shopName` 使用 IK 分词，支持全文搜索。
- `userId`、`shopId`、`status` 使用 Keyword，支持精确过滤。
- `likeCount` 是排序热度信号。

PostService 触发点：

- `publishPost()`：MySQL insert 后调用 `PostSearchService.syncPost(post, shop.getShopName())`。
- `deletePost()`：MySQL 逻辑删除后调用 `PostSearchService.removePost(postId)`。
- 点赞不实时同步 ES，`PostSearchService.updateLikeCount()` 预留定期同步。

风险：

- Spring 本地事务只能管 MySQL，管不到 Redis 和 ES。
- 当前 ES 同步在事务方法内部调用，可能出现 DB 回滚但 ES 已写入，或 DB 已提交但 ES 失败。
- 后续更稳的方案是事务提交后发 Outbox 事件，由消费者重试同步 ES。

## 权限和异常

权限入口：

- `AuthInterceptor` 从 `Authorization: Bearer {token}` 读取 token。
- Redis `login:token:{token}` 取 `LoginUserDTO`。
- 写入 `UserContext`，Service 通过 `UserContext.getUserId()` 取当前用户。
- `afterCompletion()` 清理 ThreadLocal。

公开读接口：

- `GET /api/v1/posts/{postId}`、`GET /api/v1/posts/{postId}/comments`、`GET /api/v1/shops/{shopId}/posts` 在 `AuthInterceptor.PUBLIC_ENDPOINTS` 中是公开接口。
- 当前实现命中公开白名单时不会解析 token，所以即使请求带 token，`getPostDetail()` 中 `isLikedByCurrentUser()` 也可能拿不到登录用户。这和 Controller 注释中的“已登录用户返回 liked”存在实现差异，是后续优化点。

异常处理：

- Service 抛 `BizException(ErrorCode.X)`。
- `GlobalExceptionHandler` 统一转换为 `Result.fail(...)`，并设置 HTTP 状态码。
- 参数校验失败由 `MethodArgumentNotValidException` 统一处理。

## Agent / Copilot 项目地图

本轮只建立 Agent 主链路地图，细节见 `docs/05-interview/05-agent-map.md`。

| 模块 | 主要代码 | 职责 | 对应知识点 |
|---|---|---|---|
| Python Agent API | `copilot-agent-service/api/chat.py` | `/chat`、`/chat/resume`、SSE 流式事件、Fast Path | Agent 入口、SSE、HITL |
| LangGraph Agent | `copilot-agent-service/agent/graph.py`、`agent/nodes.py`、`agent/state.py` | LLM 节点、工具节点、反思、压缩、审批、最终回答 | ReAct、Workflow、Memory |
| Tool Router | `copilot-agent-service/agent/tool_router.py` | 按角色、任务、上下文过滤工具 | 工具选择、最小权限 |
| MCP Client | `copilot-agent-service/mcp/mcp_client.py` | 调 Java MCP Server 的 `initialize`、`tools/list`、`tools/call` | MCP、Function Calling |
| RAG | `copilot-agent-service/rag/pipeline.py`、`knowledge_tool.py`、`vector_store.py`、`bm25_store.py` | 知识库分块、Embedding、Milvus、BM25、RRF、Rerank、拒答 | RAG、Embedding、向量数据库 |
| Session / Checkpoint | `copilot-agent-service/session` | 保存会话、消息、LangGraph checkpoint、HITL 审批 | Memory、可恢复 Agent |
| Evals / Metrics | `copilot-agent-service/evals`、`agent/metrics.py`、`agent/trace.py` | 评估工具准确率、RAG 召回、事实一致性、延迟、成本 | AgentOps、RAG 评估 |
| Java MCP Server | `local-life-copilot/src/main/java/.../mcp` | JSON-RPC MCP 入口、工具注册、工具执行 | MCP Server |
| Java Tool | `local-life-copilot/src/main/java/.../tool` | 订单查询、退款、补偿券、指标查询等业务工具 | Tool Calling、HITL |
| Java Security | `RbacFilter`、`RbacContext`、`ToolRateLimiter`、`ToolAuditService` | 身份上下文、RBAC、Redis 限流、审计 | 安全、限流、可观测性 |

Agent 主链路：

`用户 -> /chat -> LangGraph -> ToolRouter -> MCP Client -> Java MCP Server -> Java Tool -> Mapper 或 local-life-server 内部接口 -> 工具结果 -> LLM -> SSE final_answer`

面试讲法：

> Copilot 不是一个简单 ChatGPT 包装。Python 侧负责 Agent 状态、工具选择、RAG 和流式输出；Java MCP Server 负责把后端业务能力安全地暴露成工具；local-life-server 仍然是业务事务和数据一致性的核心。

## 本轮不展开

- 订单、支付、秒杀、门店缓存一致性。
- Agent 的所有业务工具逐个细拆。
- Multi-Agent、A2A 和 LLM Gateway 生产化方案。

这些放在后续阶段，避免一次性把项目讲散。
