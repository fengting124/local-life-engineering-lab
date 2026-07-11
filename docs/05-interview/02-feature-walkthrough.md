# 功能实现链路：PostService

- Status: Active
- Type: Interview
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: 项目文档

> 本轮只分析 `PostService`。每个功能都绑定具体代码、业务动作、事务边界、缓存和 ES 风险。

## 0. 显式知识点映射矩阵

> 读功能链路前先看这张表。以后每分析一个模块，都先补这张“实现点 -> 小林知识点 -> 面试表达”矩阵。

| 功能或实现点 | 项目代码 | 对应 xiaolincoding 知识点 | 面试表达 |
|---|---|---|---|
| PostService 被 Controller 注入 | `PostController` final 字段、`PostService @Service`、`@RequiredArgsConstructor` | Spring Bean、依赖注入、构造器注入、final 关键字 | `PostService` 是 Spring 管理的业务 Bean，构造器注入让依赖显式且不可变，事务 AOP 也能代理它。 |
| 发布笔记参数校验 | `CreatePostRequest` 的 `@NotNull`、`@NotBlank`、`@Size`，Controller 的 `@Valid` | Java 注解、反射、SpringMVC 参数绑定、统一异常处理 | 参数规则写在 DTO 上，SpringMVC 通过注解和校验器在进入业务前拦住非法请求。 |
| 图片列表转 JSON 字符串 | `serializeImages(List<String>)`、`deserializeImages(String)`、`ObjectMapper` | Java 序列化/反序列化、String 不可变、List 集合 | 接口层用 `List<String>` 表达图片列表，DB 用 JSON 字符串持久化，Service 负责转换边界。 |
| Long ID 返回 String | `PostVO.postId`、`CommentVO.commentId`、`String.valueOf(post.getId())` | Java 基本类型/包装类、Long、String、类型转换 | 数据库存 Long 雪花 ID，返回给前端转 String，避免 JS Number 精度丢失。 |
| 计数字段用 Integer | `Post.likeCount`、`Post.commentCount`、`getRealTimeLikeCount` 空值兜底 | int vs Integer、装箱拆箱、null 风险 | Entity 用 Integer 能表达数据库空值，但业务读取时必须做 null 兜底，避免拆箱 NPE。 |
| 批量组装作者信息 | `userMapper.selectBatchIds(userIds)`、`Collectors.toMap(User::getId, u -> u)` | Java 集合 List/Map、HashMap key、equals/hashCode、Stream API | 列表场景先批量查 user，再用 Map 按 userId O(1) 组装，避免 N+1 查询。 |
| liked 状态判断 | `post:like:users:{postId}` Redis Set、`isMember` | Set 去重、Redis Set、幂等 | Set 天然去重，适合表达“用户是否点赞过”。 |
| 点赞实时计数 | `post:like:count:{postId}` Redis String、`increment/decrement` | Redis String、原子自增、并发安全边界 | Redis 单命令自增是原子的，但当前跨 Set 和 String 的多命令组合不是原子。 |
| 点赞并发风险 | `likePost()` 的 `SISMEMBER -> SADD -> INCR` | Java 并发原子性、CAS/锁思想、Redis Lua | 先查后写有并发窗口，不能只靠 Java 锁，分布式场景应把 Redis 多命令合成 Lua 原子脚本。 |
| 请求级用户上下文 | `UserContext`、`ThreadLocal<LoginUserDTO>`、`AuthInterceptor.afterCompletion()` | Java 并发 ThreadLocal、内存泄漏、线程池复用、JVM 内存 | ThreadLocal 适合请求内传 userId，但 Tomcat 线程复用，必须在请求结束 remove，避免用户串号和内存泄漏。 |
| 发布/评论接口限流 | `@RateLimit`、`RateLimitInterceptor`、`rate_limit.lua` | Java 注解、SpringMVC HandlerInterceptor、Redis Lua、滑动窗口限流 | 限流注解只是声明规则，拦截器读取注解后用 Lua 保证 Redis 窗口计数原子。 |
| Service 事务边界 | `@Transactional` 标在 `publishPost/addComment/deleteComment/deletePost` | Spring AOP、动态代理、事务失效、MySQL ACID | 事务放 Service，因为 Service 覆盖完整业务动作；自调用或异常被吞会让事务失效。 |
| 评论数更新 | `LambdaUpdateWrapper.setSql("comment_count = comment_count + 1")` | MyBatis Plus Wrapper、SQL 原子更新、并发一致性 | 评论数直接在 DB 做原子加减，低频场景比 Redis 计数更简单一致。 |
| 逻辑删除 | `Post.deleted`、`Comment.deleted`、`@TableLogic` | MyBatis Plus 逻辑删除、Java 注解、数据保留 | MySQL 不物理删内容，`deleteById` 改写成 `deleted=1`，查询自动过滤。 |
| ES 搜索同步 | `PostSearchService.syncPost/removePost`、`PostDocument` | ES 倒排索引、MySQL vs ES、最终一致性 | MySQL 是主数据，ES 是搜索投影；同步双写有风险，后续用 Outbox/MQ 补偿。 |
| JVM 和对象生命周期 | `Post.builder()`、`Comment.builder()`、`PostVO.builder()` | JVM 堆/栈、对象创建、GC | 请求中构建的 Entity/VO 是普通 Java 对象，引用在栈帧里，对象在堆上，请求结束后无引用则等待 GC。 |

## 1. 笔记发布

功能名称：发布探店笔记。

业务场景：用户对一家 ONLINE 门店发布图文笔记，发布后详情可见，搜索可召回。

接口入口：`POST /api/v1/posts`

Controller 方法：`PostController.publishPost(@Valid @RequestBody CreatePostRequest request)`

Service 方法：`PostService.publishPost(CreatePostRequest request)`

Mapper / Repository 操作：

- `ShopMapper.selectById(request.getShopId())`
- `UserMapper.selectById(userId)`
- `PostMapper.insert(post)`
- `PostSearchRepository.save(doc)`，由 `PostSearchService.syncPost()` 间接调用。

数据库动作：

- 插入 `post` 表。
- `Post` 使用 `IdType.ASSIGN_ID` 生成 ID。
- `createdAt`、`updatedAt` 由 `MetaObjectHandler` 自动填充。

Redis 动作：

- `checkPublishRateLimit(userId)` 对 `post:publish:limit:{userId}` 执行 `setIfAbsent`，TTL 60 秒。
- 写入 `post:like:count:{postId} = 0`。
- Controller 还有 `@RateLimit(key="post:publish", limit=3, window=60)`，走 `RateLimitInterceptor` 的 ZSet Lua 滑动窗口。

ES 动作：

- `PostSearchService.syncPost(post, shop.getShopName())` 把 `Post` 转成 `PostDocument` 写入 `post_index`。

Agent 动作：无。

事务边界：

- `publishPost()` 标注 `@Transactional(rollbackFor = Exception.class)`。
- MySQL insert 在事务内。
- Redis 和 ES 不受本地事务管理。

权限校验：

- 写接口不在公开白名单，`AuthInterceptor` 必须先写入 `UserContext`。
- Service 取 `UserContext.getUserId()`。

异常处理：

- 门店不存在或未上线：`BizException(ErrorCode.SHOP_NOT_ONLINE)`。
- 发布太频繁：`BizException(ErrorCode.POST_PUBLISH_TOO_FREQUENT)`。
- 参数非法：DTO 的 `@NotNull`、`@Positive`、`@NotBlank`、`@Size` 触发统一参数异常。

返回对象：

- `PostVO`，包含字符串 ID、作者昵称头像、门店名、图片列表、点赞数、评论数、状态、创建时间。

哪些是 CRUD：

- `PostMapper.insert(post)` 是新增。
- `ShopMapper.selectById`、`UserMapper.selectById` 是查询。

哪些是业务动作：

- 发布频率限制。
- 门店必须 ONLINE。
- 图片列表 JSON 序列化。
- 发布即 `PUBLISHED`。

哪些是缓存操作：

- Service 内 `SETNX` 发布限流。
- 初始化点赞计数 String。
- Controller 上 `@RateLimit` 是接口级限流。

哪些是搜索索引同步：

- `syncPost()` 同步 ES。

哪些需要事务：

- MySQL 插入 post 是核心数据，失败要回滚。
- 如果后续还有发动态、发通知、扣内容额度等 DB 动作，应放在同一业务事务。

哪些不适合用数据库事务：

- Redis 限流、Redis 点赞计数、ES 索引写入不在 MySQL 事务资源内。
- 用数据库事务包 Redis/ES 没意义，只会给人强一致错觉。

潜在问题：

- ES 写在事务提交前，可能 DB 回滚但 ES 已有文档。
- ES 失败会抛异常导致 DB 回滚，但 Redis 点赞计数 Key 已写入，可能残留孤儿 Key。
- Controller 限流是 60 秒 3 篇，Service 限流是 60 秒 1 篇，规则不一致，面试时要解释“接口防刷”和“业务限频”的差别。

优化方案：

- 用事务提交后事件或 Outbox 记录 `POST_PUBLISHED`，异步同步 ES，失败重试。
- Redis 初始化可放到事务提交后，或允许查询时按 DB 快照兜底。
- 统一限流策略文案，区分“网关/接口限流”和“业务限流”。

面试讲法：

> 发笔记不是单纯 insert。我的链路是 Controller 做参数校验，Service 做登录用户获取、Redis 发布限流、门店 ONLINE 校验、Post 入库、点赞计数初始化、ES 同步。事务放在 Service，因为一次发布跨越多个 Mapper 操作，业务上要么创建成功，要么整体失败。但我也会说明本地事务只管 MySQL，Redis 和 ES 需要最终一致方案，后续会用 Outbox 异步补偿。

## 2. 笔记查询

功能名称：查询笔记详情、查询某门店笔记列表。

业务场景：游客或用户查看笔记内容，门店详情页展示该门店下的探店内容。

接口入口：

- `GET /api/v1/posts/{postId}`
- `GET /api/v1/shops/{shopId}/posts`

Controller 方法：

- `PostController.getPostDetail(Long postId)`
- `PostController.listPostsByShop(Long shopId)`

Service 方法：

- `PostService.getPostDetail(Long postId)`
- `PostService.listPostsByShop(Long shopId)`

Mapper / Repository 操作：

- `PostMapper.selectById(postId)`
- `PostMapper.selectList(new LambdaQueryWrapper<Post>()...)`
- `UserMapper.selectById(...)`
- `UserMapper.selectBatchIds(userIds)`
- `ShopMapper.selectById(shopId)`

数据库动作：

- 查询 `post` 表，MyBatis Plus 自动过滤逻辑删除数据。
- 查询 `user`、`shop` 组装冗余展示字段。

Redis 动作：

- `getRealTimeLikeCount(post)` 读取 `post:like:count:{postId}`。
- `isLikedByCurrentUser(postId)` 对 `post:like:users:{postId}` 执行 `SISMEMBER`。

ES 动作：无，当前按门店查列表走 MySQL，不走 ES。

Agent 动作：无。

事务边界：无显式事务。只读查询不需要开启业务事务。

权限校验：

- 公开接口在 `AuthInterceptor.PUBLIC_ENDPOINTS` 中。
- 当前公开接口命中白名单后不解析 token，因此 `liked` 对登录用户的识别存在不足。

异常处理：

- 笔记不存在或非 PUBLISHED：`POST_NOT_FOUND`。

返回对象：

- `PostVO` 列表或单个对象。

哪些是 CRUD：

- `selectById`、`selectList`、`selectBatchIds` 都是查询。

哪些是业务动作：

- 只允许展示 `PUBLISHED`。
- 组装作者、门店、图片列表、liked 状态。
- 列表批量查用户，避免 N+1。

哪些是缓存操作：

- 读 Redis 点赞实时数。
- Redis 失败时降级 DB 快照 `post.likeCount`。

哪些是搜索索引同步：无。

哪些需要事务：无。读场景不修改数据，不需要事务。

哪些不适合用数据库事务：

- Redis 点赞数读取只是展示增强，不需要和 MySQL 强一致。

潜在问题：

- `listPostsByShop` 没分页，门店笔记多时响应体和 DB 压力会变大。
- 对列表中每篇笔记逐个读 Redis 点赞数和 liked 状态，可能产生 Redis N 次访问。
- 公开读接口无法识别已登录用户的 token，`liked` 可能一直是 false。

优化方案：

- 加游标分页或 page/pageSize。
- Redis 使用 pipeline 批量读取点赞计数和点赞状态。
- 增加可选登录解析拦截器：公开接口带 token 时填充 `UserContext`，不带 token 仍放行。

面试讲法：

> 笔记查询是读链路，不开事务。详情先查 MySQL 主数据，再从 Redis 取实时点赞数，Redis 失败就降级用 DB 快照。列表查询用 `LambdaQueryWrapper` 按 shopId 和 PUBLISHED 过滤，并批量查 user 避免 N+1。这个链路的优化点是分页、Redis pipeline，以及公开接口的可选登录态识别。

## 3. 点赞和取消点赞

功能名称：点赞、取消点赞。

业务场景：用户对笔记表达喜欢，点赞数需要高频实时展示。

接口入口：

- `POST /api/v1/posts/{postId}/likes`
- `DELETE /api/v1/posts/{postId}/likes`

Controller 方法：

- `PostController.likePost(Long postId)`
- `PostController.unlikePost(Long postId)`

Service 方法：

- `PostService.likePost(Long postId)`
- `PostService.unlikePost(Long postId)`

Mapper / Repository 操作：

- `requirePublishedPost(postId)` 内部使用 `PostMapper.selectById(postId)`。

数据库动作：

- 只校验笔记存在，不更新 `post.like_count`。
- DB 的 `likeCount` 是快照，等待后续定期同步。

Redis 动作：

- 点赞：`SISMEMBER` -> `SADD post:like:users:{postId}` -> `INCR post:like:count:{postId}`。
- 取消：`SISMEMBER` -> `SREM post:like:users:{postId}` -> `DECR post:like:count:{postId}`。

ES 动作：

- 点赞不实时同步 ES。`PostSearchService.updateLikeCount()` 预留定期同步。

Agent 动作：无。

事务边界：

- 无 `@Transactional`。
- 这是 Redis 状态变更，不适合用 MySQL 本地事务。

权限校验：

- 写接口需要登录，`AuthInterceptor` 填充 `UserContext`。

异常处理：

- 笔记不存在或非 PUBLISHED：`POST_NOT_FOUND`。
- 重复点赞和重复取消当前按幂等返回成功。

返回对象：

- `Result<Void>`。

哪些是 CRUD：

- `PostMapper.selectById` 是查询。

哪些是业务动作：

- 点赞关系创建或删除。
- 重复点赞、重复取消点赞要幂等。

哪些是缓存操作：

- Redis Set 存用户点赞关系。
- Redis String 存点赞计数。

哪些是搜索索引同步：

- 当前无实时同步。

哪些需要事务：

- 当前不需要数据库事务。
- 如果未来点赞要写 MySQL 点赞明细表，则点赞明细和计数快照可能需要事务或异步一致性。

哪些不适合用数据库事务：

- 高频点赞不适合每次直接更新 MySQL 热点行，容易造成行锁竞争。
- MySQL 事务也无法保证 Redis Set 和 String 两步原子。

潜在问题：

- 并发重复点赞：两个请求都先读到未点赞，都会 `INCR`，但 Set 只有一个成员，计数可能多 1。
- 并发重复取消：两个请求都先读到已点赞，都会 `DECR`，计数可能少 1，甚至负数。
- Redis 宕机时点赞接口不可用，目前没有降级策略。

优化方案：

- 用 Lua 合并 `SADD + INCR`：只有 `SADD` 返回 1 才 `INCR`。
- 用 Lua 合并 `SREM + DECR`：只有 `SREM` 返回 1 才 `DECR`，并保护不小于 0。
- 定时任务把 Redis 点赞数同步回 MySQL 和 ES。
- 对超热点笔记可以做分片计数，降低单 Key 热点。

面试讲法：

> 点赞是业务动作，不是普通 CRUD。为了抗高并发，我没有每次写 MySQL，而是用 Redis Set 记录点赞用户，用 String 做计数。Set 解决幂等判断，String 解决实时计数。但我也会主动说当前实现还有并发窗口，`SISMEMBER`、`SADD`、`INCR` 三步不是原子的，生产上要改成 Lua，以 SADD/SREM 的返回值决定是否改计数。

## 4. 笔记删除

功能名称：删除笔记。

业务场景：用户删除自己发布的笔记，前台不可见，搜索结果也不能出现。

接口入口：`DELETE /api/v1/posts/{postId}`

Controller 方法：`PostController.deletePost(Long postId)`

Service 方法：`PostService.deletePost(Long postId)`

Mapper / Repository 操作：

- `PostMapper.selectById(postId)`
- `PostMapper.deleteById(postId)`
- `PostSearchRepository.deleteById(String.valueOf(postId))`

数据库动作：

- MyBatis Plus `deleteById` 对 `@TableLogic` 实体执行逻辑删除。
- `post.deleted` 置为 1，数据保留。

Redis 动作：

- 删除 `post:like:count:{postId}`。
- 删除 `post:like:users:{postId}`。

ES 动作：

- `PostSearchService.removePost(postId)` 从 ES 物理删除文档。

Agent 动作：无。

事务边界：

- `@Transactional(rollbackFor = Exception.class)`。
- MySQL 逻辑删除在事务内。
- Redis 删除和 ES 删除不受 MySQL 事务管理。

权限校验：

- 只能删除自己的笔记。
- `post == null` 或 `post.userId != currentUserId` 都返回 `POST_FORBIDDEN`，避免枚举他人笔记。

异常处理：

- 无权限或不存在：`POST_FORBIDDEN`。

返回对象：

- `Result<Void>`。

哪些是 CRUD：

- `selectById` 是查询。
- `deleteById` 是逻辑删除。

哪些是业务动作：

- 防越权删除。
- MySQL 逻辑删除，ES 物理删除。

哪些是缓存操作：

- 删除点赞计数和点赞用户集合。

哪些是搜索索引同步：

- 删除 ES 文档。

哪些需要事务：

- MySQL 逻辑删除需要事务。
- 如果后续要级联处理评论状态或发布删除事件，也应以 Service 作为事务边界。

哪些不适合用数据库事务：

- Redis Key 删除和 ES 文档删除不能靠 MySQL 本地事务保证。

潜在问题：

- DB 删除成功但 Redis 删除失败，会留下点赞缓存垃圾。
- DB 删除成功但 ES 删除失败，搜索结果会出现已删除笔记。
- ES 删除成功但 DB 事务回滚，会导致笔记仍存在但搜索不到。

优化方案：

- 删除后写 Outbox 事件 `POST_DELETED`，由消费者删除 ES 和 Redis，失败可重试。
- Redis Key 可设置合理 TTL，减少删除失败后的长期垃圾。
- ES 删除做幂等，重复 delete 不影响结果。

面试讲法：

> 删除笔记不是物理删 MySQL，而是 MyBatis Plus 逻辑删除，保证数据留存。搜索索引不同，ES 里不应该出现已删除内容，所以要物理删除 ES 文档。这里我会主动指出双写风险：MySQL 事务管不到 ES 和 Redis，所以更稳的做法是 Outbox 事件加重试补偿。

## 5. 评论发布

功能名称：发表评论。

业务场景：用户对某篇 PUBLISHED 笔记发布一级评论，同时笔记评论数加 1。

接口入口：`POST /api/v1/posts/{postId}/comments`

Controller 方法：`PostController.addComment(Long postId, CreateCommentRequest request)`

Service 方法：`PostService.addComment(Long postId, CreateCommentRequest request)`

Mapper / Repository 操作：

- `PostMapper.selectById(postId)`
- `UserMapper.selectById(userId)`
- `CommentMapper.insert(comment)`
- `PostMapper.update(null, new LambdaUpdateWrapper<Post>()...)`

数据库动作：

- 插入 `comment` 表。
- 更新 `post.comment_count = comment_count + 1`。

Redis 动作：

- Controller 上 `@RateLimit(key="post:comment", limit=5, window=10)` 走 Redis ZSet Lua 限流。
- Service 内不写 Redis。

ES 动作：无。

Agent 动作：无。

事务边界：

- `@Transactional(rollbackFor = Exception.class)`。
- 评论插入和评论数 +1 要在同一个 MySQL 事务内。

权限校验：

- 写接口需要登录。

异常处理：

- 笔记不存在或非 PUBLISHED：`POST_NOT_FOUND`。
- 评论内容为空或超长：`@Valid` 统一参数异常。

返回对象：

- `CommentVO`。

哪些是 CRUD：

- `CommentMapper.insert` 是新增。
- `PostMapper.update` 是更新。
- `UserMapper.selectById` 是查询。

哪些是业务动作：

- 评论必须挂在已发布笔记下。
- 当前只支持一级评论，`parentId = 0`。
- 评论后同步维护 `comment_count` 快照。

哪些是缓存操作：

- 接口限流是 Redis 操作。
- 评论数当前直接写 MySQL，不走 Redis。

哪些是搜索索引同步：无。

哪些需要事务：

- 评论插入和评论数 +1 必须一致，否则会出现有评论但数量不对。

哪些不适合用数据库事务：

- 接口限流不属于业务数据一致性，不应放到 DB 事务里。

潜在问题：

- `postMapper.update` 只按 `id` 更新，没有附带 `status = PUBLISHED`，极端情况下笔记状态变化后仍可能更新计数。
- 评论列表无分页，后续评论多会有压力。

优化方案：

- 更新评论数时加 `eq(Post::getStatus, "PUBLISHED")` 并检查影响行数。
- 加评论分页。
- 如果评论量升高，可改 Redis 计数加异步落库。

面试讲法：

> 评论发布是典型需要事务的业务动作，因为它不是只插一条评论，还要同步维护笔记评论数。我的事务放在 `PostService.addComment()`，覆盖 `commentMapper.insert()` 和 `postMapper.update()`。相比点赞，评论频率低，所以直接更新 MySQL 可以接受。

## 6. 评论查询

功能名称：查询评论列表。

业务场景：用户打开笔记详情页，按时间查看一级评论。

接口入口：`GET /api/v1/posts/{postId}/comments`

Controller 方法：`PostController.listComments(Long postId)`

Service 方法：`PostService.listComments(Long postId)`

Mapper / Repository 操作：

- `PostMapper.selectById(postId)`
- `CommentMapper.selectList(new LambdaQueryWrapper<Comment>()...)`
- `UserMapper.selectBatchIds(userIds)`

数据库动作：

- 校验 post 存在且 PUBLISHED。
- 查询 `comment` 表中 `post_id = ? and parent_id = 0` 的评论，按 `createdAt` 升序。
- 批量查评论者信息。

Redis 动作：无。

ES 动作：无。

Agent 动作：无。

事务边界：无显式事务。

权限校验：公开接口，无需登录。

异常处理：

- 笔记不存在或非 PUBLISHED：`POST_NOT_FOUND`。

返回对象：

- `List<CommentVO>`。

哪些是 CRUD：

- `selectById`、`selectList`、`selectBatchIds` 都是查询。

哪些是业务动作：

- 只展示一级评论。
- 批量查用户避免 N+1。

哪些是缓存操作：无。

哪些是搜索索引同步：无。

哪些需要事务：无。

哪些不适合用数据库事务：纯读接口不需要为了“看起来安全”加事务。

潜在问题：

- 当前全量返回，评论多时不适合。
- 只查一级评论，回复能力未开放。

优化方案：

- 加游标分页：`lastId + pageSize` 或基于 `createdAt + id`。
- 支持二级回复时，先分页查一级评论，再批量查回复摘要。

面试讲法：

> 评论查询是读链路，重点是避免 N+1。代码先查评论列表，再提取 userId 批量查 user，最后 Map 组装 `CommentVO`。当前不足是没分页，后续会加游标分页。

## 7. 评论删除

功能名称：删除评论。

业务场景：用户删除自己在某篇笔记下的评论，评论数减少但不能为负。

接口入口：`DELETE /api/v1/posts/{postId}/comments/{commentId}`

Controller 方法：`PostController.deleteComment(Long postId, Long commentId)`

Service 方法：`PostService.deleteComment(Long postId, Long commentId)`

Mapper / Repository 操作：

- `CommentMapper.selectById(commentId)`
- `CommentMapper.deleteById(commentId)`
- `PostMapper.update(null, new LambdaUpdateWrapper<Post>()...)`

数据库动作：

- 逻辑删除 comment。
- 更新 `post.comment_count = GREATEST(comment_count - 1, 0)`。

Redis 动作：无。

ES 动作：无。

Agent 动作：无。

事务边界：

- `@Transactional(rollbackFor = Exception.class)`。
- 评论删除和评论数 -1 要在同一 MySQL 事务。

权限校验：

- 评论必须存在。
- 评论必须属于路径里的 postId。
- 评论必须属于当前用户。

异常处理：

- 不满足上述条件：`COMMENT_FORBIDDEN`。

返回对象：

- `Result<Void>`。

哪些是 CRUD：

- `selectById` 查询。
- `deleteById` 逻辑删除。
- `update` 更新评论数。

哪些是业务动作：

- 防止删除他人评论。
- 防止路径 postId 和 commentId 不匹配。
- 评论数不能降到负数。

哪些是缓存操作：无。

哪些是搜索索引同步：无。

哪些需要事务：

- 逻辑删除评论和评论数减少需要原子提交。

哪些不适合用数据库事务：

- 当前没有外部中间件操作。

潜在问题：

- 没有校验笔记当前是否仍存在或 PUBLISHED。
- 如果并发重复删除同一评论，逻辑删除后的第二次 `selectById` 因 `@TableLogic` 应查不到，返回 `COMMENT_FORBIDDEN`，不完全幂等。

优化方案：

- 根据产品语义决定重复删除是否直接返回成功。
- 更新评论数时加影响行数检查。

面试讲法：

> 删除评论也放 Service 事务，因为它包含两张表动作：comment 逻辑删除和 post 评论数减少。权限上我用 commentId 查评论，再校验它属于路径 postId 和当前 userId，避免越权和路径伪造。评论数用 `GREATEST(comment_count - 1, 0)` 防止负数。

## 8. Redis 限流和点赞计数

功能名称：接口限流、业务限流、点赞计数。

业务场景：防刷发布、评论刷屏、高频点赞展示。

接口入口：

- `PostController.publishPost()` 的 `@RateLimit`
- `PostController.addComment()` 的 `@RateLimit`
- `PostService.checkPublishRateLimit()`
- `PostService.likePost()` / `unlikePost()`

Controller 方法：

- `publishPost`
- `addComment`

Service 方法：

- `checkPublishRateLimit`
- `likePost`
- `unlikePost`
- `getRealTimeLikeCount`
- `isLikedByCurrentUser`

Mapper / Repository 操作：

- 点赞前 `PostMapper.selectById` 校验笔记。

数据库动作：

- 点赞不实时写 DB。

Redis 动作：

- ZSet Lua 滑动窗口：`rate_limit:{key}:{userId/IP}`。
- SETNX 限流：`post:publish:limit:{userId}`。
- String 计数：`post:like:count:{postId}`。
- Set 点赞关系：`post:like:users:{postId}`。

ES 动作：

- 点赞数不实时同步 ES。

Agent 动作：无。

事务边界：

- Redis Lua 保证脚本内原子。
- 点赞当前没有 Lua，存在多命令并发窗口。

权限校验：

- `@RateLimit` 按用户或 IP 构建 Key。
- 点赞、评论、发布需要登录。

异常处理：

- `RateLimitInterceptor` 超限直接返回 HTTP 429。
- Service 发布限流抛 `POST_PUBLISH_TOO_FREQUENT`。

返回对象：

- 限流失败返回统一 `Result.fail`。

潜在问题：

- `rate_limit.lua` 使用 `now` 作为 ZSet member，同一毫秒多请求可能覆盖，计数略低。
- 点赞/取消未用 Lua，计数和 Set 可能不一致。

优化方案：

- ZSet member 使用 `now + requestId` 避免同毫秒覆盖。
- 点赞和取消点赞使用 Lua 原子脚本。
- 定时对账 Redis Set 的 `SCARD`、String count、MySQL 快照。

面试讲法：

> 项目里有两类限流：Controller 的 `@RateLimit` 是通用接口限流，用 Redis ZSet 和 Lua 做滑动窗口；PostService 的 `SETNX` 是发布业务限频，限制用户 60 秒内只能发布一次。点赞计数用 Redis 是为了避开 MySQL 热点行，但当前点赞两步不是原子，后续用 Lua 改。

## 9. ES 同步

功能名称：发布同步 ES、删除移除 ES。

业务场景：用户搜索笔记时，需要按标题、正文、门店名全文召回。

接口入口：

- `POST /api/v1/posts`
- `DELETE /api/v1/posts/{postId}`

Controller 方法：

- `publishPost`
- `deletePost`

Service 方法：

- `PostService.publishPost`
- `PostService.deletePost`
- `PostSearchService.syncPost`
- `PostSearchService.removePost`

Mapper / Repository 操作：

- `PostSearchRepository.save(PostDocument)`
- `PostSearchRepository.deleteById(String postId)`

数据库动作：

- 发布时 MySQL 是主数据。
- 删除时 MySQL 逻辑删除。

Redis 动作：

- 发布初始化点赞计数。
- 删除清理点赞 Key。

ES 动作：

- `post_index` 写入或删除文档。
- `PostDocument` 冗余 `shopName`，避免搜索时 join。

Agent 动作：无。

事务边界：

- ES 不受 MySQL 事务控制。

权限校验：

- 发布和删除都需要登录。
- 删除只能删自己的。

异常处理：

- 当前 ES 异常可能向上抛，影响接口结果。

返回对象：

- 发布返回 `PostVO`，删除返回 `Void`。

潜在问题：

- 同步双写没有重试。
- 事务提交前写 ES，有脏索引风险。
- 删除 ES 失败会造成搜索结果脏数据。

优化方案：

- Outbox：MySQL 事务内只写业务数据和 outbox 事件。
- 事务提交后异步消费事件，同步 ES。
- ES 操作幂等，失败重试，超过次数进入死信或补偿表。

面试讲法：

> ES 是搜索索引，不是主数据。主数据在 MySQL，ES 是派生数据。发布笔记后同步 ES 是为了搜索立刻可见，删除笔记后删除 ES 是为了搜索不出现已删除内容。但同步双写有一致性风险，我会用 Outbox 或 binlog/MQ 做最终一致。

## 10. 事务和一致性

功能名称：PostService 的事务边界和一致性策略。

业务场景：一次用户动作可能同时涉及 MySQL、Redis、ES，需要区分强一致和最终一致。

接口入口：

- 需要事务：发布、删除笔记、发评论、删评论。
- 不需要 MySQL 事务：查询、点赞、取消点赞。

Controller 方法：全部 PostController 方法。

Service 方法：

- 有事务：`publishPost`、`deletePost`、`addComment`、`deleteComment`。
- 无事务：`getPostDetail`、`listPostsByShop`、`likePost`、`unlikePost`、`listComments`。

Mapper / Repository 操作：

- MySQL：BaseMapper CRUD。
- Redis：StringRedisTemplate。
- ES：PostSearchRepository。

数据库动作：

- MySQL 本地事务只能保证同一个数据源内的原子性。

Redis 动作：

- Redis 操作不参与 MySQL 事务。

ES 动作：

- ES 操作不参与 MySQL 事务。

Agent 动作：无。

事务边界：

- Service 层承载事务，因为 Service 编排多个 Mapper 和外部组件，是业务用例边界。
- Controller 只是 HTTP 边界，不适合放事务。
- Mapper 只是单表数据访问，放事务粒度太小。

权限校验：

- 由 `AuthInterceptor` 和 Service 内 owner 校验共同完成。

异常处理：

- `BizException` 会触发 RuntimeException 回滚。
- `rollbackFor = Exception.class` 覆盖 checked exception。

返回对象：

- 由 Service 组装 VO 或返回空。

潜在问题：

- `@Transactional` 通过 Spring AOP 实现，自调用、非 public 方法、异常被吞、非 Spring Bean 调用等会失效。
- 本项目 PostService 的事务方法是 public，并由 Controller 调用 Spring Bean，基本满足生效条件。
- 外部 Redis/ES 失败不能靠本地事务统一回滚。

优化方案：

- Redis/ES 用最终一致：Outbox、MQ 重试、binlog 订阅、定时对账。
- 点赞用 Redis Lua 保证 Redis 内原子性。
- 对需要跨系统同步的动作定义事件表和补偿任务。

面试讲法：

> 我把事务放在 Service 层，因为 Service 是业务用例边界。比如发评论要插 comment，还要更新 post.comment_count，这两个 Mapper 操作必须一起提交或一起回滚。Controller 不放事务，因为它只处理 HTTP；Mapper 不放事务，因为它只管单表 CRUD。再强调一点，Spring 本地事务只管 MySQL 连接，管不到 Redis 和 ES，所以这部分要用最终一致和补偿。
