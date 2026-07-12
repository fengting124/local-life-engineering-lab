# 高频题映射：PostService 第一轮

- Status: Active
- Type: Interview
- Owners: Project maintainers
- Last verified: 2026-07-12
- Source of truth: 项目文档

> 题目来自 xiaolincoding 高频方向，答案为项目化提炼，不复制原文。

## Java 基础

### 29. JVM、JDK、JRE 三者关系怎么理解？

标准答案：JVM 是运行 Java 字节码的虚拟机；JRE 是运行环境，包含 JVM 和基础类库；JDK 是开发工具包，包含 JRE、编译器和调试工具。

项目中的具体实现：`local-life-server` 用 Java 17 + Spring Boot 开发，源码编译成字节码后在 JVM 上运行；Docker runtime 运行的是打包后的 Java 应用。

涉及类名和方法名：`LocalLifeServerApplication`、`PostService`。

为什么这样设计：项目选择 Java 是为了使用 Spring、MyBatis Plus、Redis、ES 等成熟生态，后端服务依赖 JVM 运行时。

可能被追问什么：Java 跨平台跨的是 JVM 还是字节码？

项目当前不足：文档暂未记录生产 JVM 参数和 GC 策略。

后续优化方案：在 infra 阶段补 JVM 启动参数、堆大小、GC 日志和容器内存限制。

面试回答模板：我项目用 Java 17。开发编译需要 JDK，运行 jar 至少需要 JRE，真正执行字节码的是 JVM。Java 跨平台依赖的是同一份字节码在不同平台 JVM 上执行。

### 30. int、Integer、Long、String 在项目里怎么选？

标准答案：基本类型不能为 null，包装类型可以表达缺失值；Long 常用于大 ID；String 适合文本和跨语言传输。

项目中的具体实现：`Post.id` 是 Long 雪花 ID；`Post.likeCount`、`commentCount` 是 Integer；`PostVO.postId`、`CommentVO.commentId` 返回 String。

涉及类名和方法名：`Post`、`PostVO`、`CommentVO`、`PostService.toVO()`。

为什么这样设计：数据库 ID 用 Long；前端 JS 可能丢 Long 精度，所以 VO 转 String；Integer 读取时做 null 兜底。

可能被追问什么：为什么不用 long/int？拆箱有什么风险？

项目当前不足：不同 VO 都手写 `String.valueOf(id)`，有重复。

后续优化方案：抽一个 ID 转换工具，或用统一 Jackson Long 转 String 策略。

面试回答模板：Entity 面向数据库，所以 ID 是 Long；VO 面向前端，所以 ID 是 String；计数字段用 Integer 时要小心 null，读取时要兜底。

### 31. Java 注解在项目里怎么落地？

标准答案：注解本身是元数据，框架通过反射、代理或处理器读取注解并执行逻辑。

项目中的具体实现：`@Valid` 触发参数校验，`@Transactional` 触发事务 AOP，`@TableLogic` 触发逻辑删除，`@RateLimit` 被拦截器读取后执行 Redis Lua 限流。

涉及类名和方法名：`CreatePostRequest`、`PostService.publishPost()`、`Post.deleted`、`RateLimitInterceptor.preHandle()`。

为什么这样设计：把通用规则声明在代码边界上，业务方法只关注核心流程。

可能被追问什么：注解不配处理器会不会生效？

项目当前不足：`@RateLimit` 的错误码当前复用 `AUTH_CODE_SEND_TOO_FREQUENT`，语义不够精确。

后续优化方案：给通用限流增加独立错误码。

面试回答模板：项目里注解不是装饰文字。比如 `@RateLimit` 由 `RateLimitInterceptor` 读取，`@Transactional` 由 Spring AOP 代理，`@TableLogic` 由 MyBatis Plus 改写 SQL。

### 32. Java 异常体系在项目里怎么用？

标准答案：Java 异常分 checked exception 和 unchecked exception。业务拒绝常用运行时异常向上抛，再统一处理。

项目中的具体实现：`BizException extends RuntimeException`；`GlobalExceptionHandler` 捕获并返回统一 `Result`。

涉及类名和方法名：`PostService.requirePublishedPost()`、`GlobalExceptionHandler.handleBizException()`。

为什么这样设计：Service 不需要层层 throws 或 try-catch，Controller 也不写重复错误处理。

可能被追问什么：为什么 `@Transactional` 要写 `rollbackFor = Exception.class`？

项目当前不足：拦截器限流直接写响应，不走异常处理器。

后续优化方案：保持响应格式统一，补充通用限流错误码。

面试回答模板：项目里业务异常用 unchecked 的 `BizException`，让异常穿透到全局处理器。事务方法写了 `rollbackFor=Exception.class`，避免 checked exception 不回滚的问题。

### 33. String、StringBuilder、StringBuffer 怎么结合项目讲？

标准答案：String 不可变，适合少量字符串和线程安全共享；StringBuilder 适合单线程大量拼接；StringBuffer 方法同步但一般少用。

项目中的具体实现：Redis key 使用静态模板常量和 `String.format`；图片列表持久化为 JSON String；VO 的 ID 使用 String。

涉及类名和方法名：`PostService.LIKE_COUNT_KEY`、`serializeImages()`、`toVO()`。

为什么这样设计：PostService 的字符串拼接量很小，`String.format` 可读性优先；JSON String 是存储边界。

可能被追问什么：高频 key 拼接要不要优化？

项目当前不足：点赞接口高频调用时 `String.format` 有一定开销。

后续优化方案：热点路径可改为直接 `LIKE_COUNT_PREFIX + postId`。

面试回答模板：项目里 Redis key 是 String，因为跨系统可读。少量拼接用 `String.format` 足够；如果点赞成为极热路径，可以改成前缀加 ID，减少格式化开销。

## Java 集合

### 34. List、Set、Map 在 PostService 里分别解决什么？

标准答案：List 表示有序列表，Set 表示不重复集合，Map 表示 key-value 快速查找。

项目中的具体实现：`List<PostVO>` 返回笔记列表；Redis Set 存点赞用户；`Map<Long, User>` 批量组装作者信息。

涉及类名和方法名：`PostService.listPostsByShop()`、`PostService.listComments()`、`PostService.isLikedByCurrentUser()`。

为什么这样设计：列表要保持排序；点赞关系要去重；用户信息组装要 O(1) 查找。

可能被追问什么：为什么不用 List 判断点赞？

项目当前不足：列表中逐条查 Redis，集合批量能力还没用满。

后续优化方案：Redis pipeline 批量读 count 和 liked。

面试回答模板：PostService 里 List、Set、Map 都有明确语义。List 返回有序结果，Set 表示点赞去重，Map 用 userId 快速组装 VO。

### 35. HashMap 的 equals 和 hashCode 在项目里怎么体现？

标准答案：HashMap 通过 hashCode 定位桶，再用 equals 判断 key 是否相等；作为 key 的对象必须正确实现二者。

项目中的具体实现：`Collectors.toMap(User::getId, u -> u)` 生成 `Map<Long, User>`，后续 `userMap.get(post.getUserId())` 查作者。

涉及类名和方法名：`PostService.listPostsByShop()`、`PostService.listComments()`。

为什么这样设计：Long 作为 key 已正确实现 equals/hashCode，适合作 Map key。

可能被追问什么：如果 key 是自定义对象要注意什么？

项目当前不足：`toMap` 遇到重复 key 会抛异常，虽然 userId 去重后正常。

后续优化方案：需要更稳时给 `Collectors.toMap` 增加 merge 函数。

面试回答模板：我批量查 user 后转成 `Map<Long, User>`，Long 适合作 HashMap key，因为 equals/hashCode 稳定。自定义对象做 key 时必须配套重写。

### 36. Stream API 在项目里怎么用？

标准答案：Stream 用声明式方式处理集合，常见操作有 map、distinct、collect、filter。

项目中的具体实现：`posts.stream().map(Post::getUserId).distinct().collect(...)` 提取 userId；评论列表也用 Stream 转 VO。

涉及类名和方法名：`PostService.listPostsByShop()`、`PostService.listComments()`。

为什么这样设计：集合转换逻辑短、清楚，避免手写循环样板。

可能被追问什么：Stream 并行流能不能随便用？

项目当前不足：当前无复杂问题。

后续优化方案：业务 IO 密集查询不随便用 parallelStream，避免抢公共线程池。

面试回答模板：PostService 用 Stream 做集合转换和去重，不用并行流。因为这里主要是内存内转换，IO 查询仍由 Mapper 明确执行。

## Java 并发

### 37. ThreadLocal 在项目里解决了什么？有什么风险？

标准答案：ThreadLocal 给每个线程一份独立变量，适合请求内传上下文；线程池复用时必须清理，否则会数据串扰或内存泄漏。

项目中的具体实现：`AuthInterceptor` 鉴权后 `UserContext.set(loginUser)`；Service 用 `UserContext.getUserId()`；`afterCompletion` 调 `UserContext.clear()`。

涉及类名和方法名：`UserContext`、`AuthInterceptor.preHandle()`、`AuthInterceptor.afterCompletion()`。

为什么这样设计：不需要在 Controller 到 Service 的每个方法参数里传 userId。

可能被追问什么：为什么必须 remove？

项目当前不足：公开接口跳过鉴权时没有可选登录态。

后续优化方案：增加 OptionalAuthInterceptor，带 token 就解析，不带 token 放行。

面试回答模板：我用 ThreadLocal 保存当前登录用户，Service 能直接拿 userId。因为 Tomcat 线程会复用，所以必须在 `afterCompletion` 里 remove。

### 38. 怎么保证多线程或高并发下的数据安全？

标准答案：单机内可以用 synchronized、ReentrantLock、CAS、线程安全集合；分布式系统还要用 Redis Lua、数据库条件更新、唯一索引、MQ 幂等等。

项目中的具体实现：`RateLimitInterceptor` 用 Redis Lua 保证滑动窗口原子；评论数用 SQL 原子加减；点赞当前跨 Redis 两个 key 还不是原子。

涉及类名和方法名：`rate_limit.lua`、`PostService.addComment()`、`PostService.likePost()`。

为什么这样设计：项目是多实例后端，Java 本地锁只能锁本 JVM，不能锁住所有服务实例。

可能被追问什么：为什么不用 synchronized 包点赞？

项目当前不足：点赞需要 Lua 化。

后续优化方案：用 Lua 根据 SADD/SREM 返回值决定 INCR/DECR。

面试回答模板：单机 Java 锁不适合分布式点赞。我的限流用 Redis Lua，评论数用 DB 原子 SQL，点赞后续也要 Lua 化。

## JVM

### 39. JVM 堆和栈怎么结合 PostService 讲？

标准答案：方法调用的局部变量和引用在栈帧中，对象实例一般在堆上；对象没有可达引用后等待 GC。

项目中的具体实现：`Post.builder().build()`、`Comment.builder().build()`、`PostVO.builder().build()` 都会创建请求内临时对象。

涉及类名和方法名：`PostService.publishPost()`、`PostService.addComment()`、`PostService.toVO()`。

为什么这样设计：Entity/VO 是请求处理中的普通对象，生命周期短，适合由 JVM 自动管理。

可能被追问什么：高流量下对象创建会有什么影响？

项目当前不足：列表接口全量返回会创建大量 VO，增加堆压力。

后续优化方案：分页、精简 `PostSummaryVO`、减少大列表对象创建。

面试回答模板：PostService 每次请求会创建 Entity 和 VO，对象在堆上，引用在栈帧里。列表无分页会放大对象数量，增加 GC 压力，所以后续要分页和拆 SummaryVO。

### 40. JVM 内存泄漏和 ThreadLocal 有什么关系？

标准答案：ThreadLocal 在线程池中如果不 remove，线程长期存活会导致旧 value 一直可达，造成数据污染和内存泄漏风险。

项目中的具体实现：`AuthInterceptor.afterCompletion()` 调 `UserContext.clear()`。

涉及类名和方法名：`UserContext.clear()`、`AuthInterceptor.afterCompletion()`。

为什么这样设计：Tomcat 工作线程会处理多个请求，必须清理上一个用户信息。

可能被追问什么：ThreadLocal 的 key/value 在内部怎么存？

项目当前不足：公开接口没有 requestId 追踪一致性，本轮不展开。

后续优化方案：可观测性阶段统一梳理 TraceIdFilter、MDC 和 ThreadLocal 清理。

面试回答模板：ThreadLocal 用完必须 remove。项目里 `afterCompletion` 无论成功异常都会执行清理，避免下个请求读到上个用户。

## Spring

### 1. Controller、Service、Mapper 分层的意义是什么？

标准答案：分层是为了职责清晰。Controller 处理 HTTP，Service 处理业务和事务，Mapper 处理数据访问。这样业务规则集中，代码可测试、可复用、可维护。

项目中的具体实现：`PostController` 只做路由、`@Valid` 和 `Result.ok`；`PostService` 编排发布、点赞、评论、删除；`PostMapper`、`CommentMapper` 继承 BaseMapper 做 CRUD。

涉及类名和方法名：`PostController.publishPost()`、`PostService.publishPost()`、`PostMapper.insert()`。

为什么这样设计：发笔记涉及限流、门店校验、图片序列化、DB insert、Redis 初始化、ES 同步，不是 Controller 或 Mapper 能单独承载的。

可能被追问什么：如果把事务放 Controller 可以吗？如果 Mapper 里写业务可以吗？

项目当前不足：`PostService` 同时处理 Redis、ES、VO 组装，后续功能多了可能变重。

后续优化方案：把 ES 同步改成领域事件/Outbox，把 VO 组装抽成 assembler。

面试回答模板：我项目里 Controller 只接 HTTP，Service 是业务用例边界，Mapper 是数据访问。比如发笔记，Controller 接 `CreatePostRequest`，Service 校验门店和限流并写 MySQL/Redis/ES，Mapper 只负责 insert。

### 2. `@Service` 是什么？

标准答案：`@Service` 是 Spring 组件注解，表示业务服务类，会被扫描成 Bean，交给 Spring 容器管理，也方便 AOP 事务代理生效。

项目中的具体实现：`PostService` 标注 `@Service`，被 Controller 构造器注入。

涉及类名和方法名：`PostService`、`PostController` 构造器注入字段 `private final PostService postService`。

为什么这样设计：Service 需要注入 Mapper、Redis、ES，也需要 `@Transactional` 通过 Spring 代理生效。

可能被追问什么：不加 `@Service` 会怎样？`@Component` 和 `@Service` 区别是什么？

项目当前不足：无明显问题。

后续优化方案：业务复杂后按领域拆小 Service。

面试回答模板：`@Service` 让 `PostService` 成为 Spring Bean。这样 Controller 能注入它，事务 AOP 也能代理它。它语义上表示业务层，比普通 `@Component` 更清楚。

### 3. `@Transactional` 是什么？

标准答案：`@Transactional` 是 Spring 声明式事务，基于 AOP 在方法前开启事务，方法成功提交，抛出符合规则的异常则回滚。

项目中的具体实现：`publishPost()`、`deletePost()`、`addComment()`、`deleteComment()` 标注 `@Transactional(rollbackFor = Exception.class)`。

涉及类名和方法名：`PostService.addComment()` 覆盖 `CommentMapper.insert()` 和 `PostMapper.update()`。

为什么这样设计：发评论需要评论插入和评论数 +1 同时成功，否则数据不一致。

可能被追问什么：RuntimeException 和 checked exception 回滚有什么区别？

项目当前不足：事务方法里调用 Redis/ES，容易让人误解这些也被事务保护。

后续优化方案：把 Redis/ES 同步移动到事务提交后事件或 Outbox。

面试回答模板：`@Transactional` 在我项目里用于一次业务动作涉及多条 MySQL 写操作的场景，比如发评论要插评论并更新评论数，任何一步失败都回滚。

### 4. 为什么事务一般放在 Service 层？

标准答案：Service 是业务用例边界，一个业务通常会调用多个 Mapper。事务放 Service 才能覆盖完整业务动作。Controller 太靠外，Mapper 太细。

项目中的具体实现：`PostService.addComment()` 同时调用 `CommentMapper` 和 `PostMapper`；`PostService.deleteComment()` 同时删除评论和更新评论数。

涉及类名和方法名：`PostService.addComment()`、`PostService.deleteComment()`。

为什么这样设计：保证“评论存在”和“评论数快照”一致。

可能被追问什么：查询要不要加事务？只调用一个 Mapper 要不要加事务？

项目当前不足：`publishPost()` 把 ES 同步放在事务方法内。

后续优化方案：MySQL 事务只包主数据，外部副作用走事务后事件。

面试回答模板：事务放 Service，因为 Service 才知道完整业务边界。比如删除评论不是单表 delete，还要 post 评论数 -1，两个 DB 动作需要一起提交。

### 5. `@Transactional` 什么时候失效？

标准答案：常见失效有自调用、方法不是 public、异常被 catch 吞掉、默认不回滚 checked exception、类没有被 Spring 管理、数据库引擎不支持事务。

项目中的具体实现：PostService 的事务方法是 public，并由 Controller 调用 Spring Bean，所以基本满足生效条件。

涉及类名和方法名：`PostController.addComment()` 调用 `PostService.addComment()`。

为什么这样设计：通过 Spring 代理对象进入事务方法。

可能被追问什么：同类里一个方法调用另一个 `@Transactional` 方法会不会生效？

项目当前不足：目前没有自调用事务问题，但文档要提醒后续维护者。

后续优化方案：避免在同类内部绕过代理调用事务方法；必要时拆 Service 或通过代理调用。

面试回答模板：我项目里的事务方法都是 public，并且由 Controller 注入的 Spring Bean 调用，所以能走代理。要注意如果同类 `this.xxx()` 调事务方法，事务不会生效。

### 6. Spring Bean 是什么？

标准答案：Bean 是由 Spring 容器创建、装配、管理生命周期的对象。

项目中的具体实现：`PostService`、`PostController`、`RateLimitInterceptor`、`AuthInterceptor`、`PostSearchService` 都是 Bean。

涉及类名和方法名：`PostService`、`PostSearchService`。

为什么这样设计：交给容器管理后可以统一注入依赖、应用 AOP、加载配置。

可能被追问什么：Bean 默认作用域是什么？

项目当前不足：无。

后续优化方案：无。

面试回答模板：Bean 就是 Spring 管的对象。我的 `PostService` 是 Bean，所以它可以注入 Mapper、RedisTemplate，也能被事务 AOP 代理。

### 7. 依赖注入是什么？构造器注入有什么好处？

标准答案：依赖注入是由容器把对象需要的依赖传进来。构造器注入能让依赖不可变、必填、便于单元测试，也避免字段注入隐藏依赖。

项目中的具体实现：`PostService` 使用 `@RequiredArgsConstructor` 注入所有 `final` 依赖。

涉及类名和方法名：`PostService` 字段 `postMapper`、`stringRedisTemplate`、`postSearchService`。

为什么这样设计：PostService 的依赖在构造时必须齐全，运行中不应被替换。

可能被追问什么：字段注入有什么缺点？

项目当前不足：依赖较多，提示类职责可能偏重。

后续优化方案：按职责拆分点赞服务、评论服务、搜索同步服务。

面试回答模板：我用构造器注入，`final` 字段配合 `@RequiredArgsConstructor`。好处是依赖显式、不可变，测试时也能直接 new 或 mock。

### 8. 统一异常处理怎么做？

标准答案：用 `@RestControllerAdvice` 和 `@ExceptionHandler` 统一捕获异常，返回统一响应体和状态码。

项目中的具体实现：`PostService` 抛 `BizException(ErrorCode.POST_NOT_FOUND)`，`GlobalExceptionHandler` 统一转成 `Result.fail`。

涉及类名和方法名：`GlobalExceptionHandler.handleBizException()`、`PostService.requirePublishedPost()`。

为什么这样设计：Controller 不需要到处 try-catch，错误码集中维护。

可能被追问什么：业务异常和系统异常日志级别有什么区别？

项目当前不足：`RateLimitInterceptor` 在拦截器层直接写响应，没有走全局异常处理。

后续优化方案：统一限流错误码文案，保持响应格式一致。

面试回答模板：Service 只抛业务异常，Controller 不 catch。`GlobalExceptionHandler` 捕获后设置 HTTP 状态码并返回统一 `Result`。

### 9. 拦截器和过滤器区别是什么？

标准答案：Filter 属于 Servlet 规范，范围更全，在 DispatcherServlet 前后执行；Interceptor 属于 Spring MVC，围绕 Controller 执行，能直接注入 Spring Bean，适合鉴权、限流等业务逻辑。

项目中的具体实现：`AuthInterceptor` 做 token 鉴权和 `UserContext`；`RateLimitInterceptor` 读取 `@RateLimit` 并执行 Lua 限流。

涉及类名和方法名：`WebMvcConfig.addInterceptors()`、`AuthInterceptor.preHandle()`、`RateLimitInterceptor.preHandle()`。

为什么这样设计：鉴权和限流依赖 Redis、ObjectMapper、Controller 方法注解，更适合 Interceptor。

可能被追问什么：拦截器执行顺序怎么控制？

项目当前不足：公开接口跳过鉴权后无法做可选登录态识别。

后续优化方案：增加 OptionalAuthInterceptor 或调整 AuthInterceptor 支持公开接口可选解析 token。

面试回答模板：我项目用 Interceptor 做鉴权和限流，因为它能拿到 HandlerMethod 和 Spring Bean。`AuthInterceptor` 先执行，鉴权通过后 `RateLimitInterceptor` 再限流。

## MyBatis Plus

### 10. BaseMapper 提供了什么？

标准答案：BaseMapper 提供通用 CRUD，如 insert、selectById、selectList、update、deleteById，减少重复 Mapper XML。

项目中的具体实现：`PostMapper extends BaseMapper<Post>`，`CommentMapper extends BaseMapper<Comment>`。

涉及类名和方法名：`PostMapper.insert()`、`PostMapper.selectById()`、`CommentMapper.deleteById()`。

为什么这样设计：PostService 当前都是单表 CRUD 和 Wrapper 查询，不需要自定义 SQL。

可能被追问什么：复杂联表怎么办？

项目当前不足：VO 组装需要多次查询 user/shop。

后续优化方案：列表场景可按需要自定义批量查询或引入 read model。

面试回答模板：BaseMapper 让我不用写简单 CRUD SQL。比如发布笔记直接 `postMapper.insert(post)`，逻辑删除直接 `deleteById`。

### 11. LambdaQueryWrapper 和 LambdaUpdateWrapper 的作用是什么？

标准答案：Wrapper 用链式方式构造 SQL 条件，Lambda 版本通过方法引用绑定字段，避免硬编码列名。

项目中的具体实现：`listPostsByShop()` 用 `LambdaQueryWrapper` 按 shopId、status、createdAt 查询；`addComment()` 用 `LambdaUpdateWrapper` 做评论数 +1。

涉及类名和方法名：`PostService.listPostsByShop()`、`PostService.addComment()`。

为什么这样设计：字段重构更安全，条件表达清楚。

可能被追问什么：`setSql` 有什么风险？

项目当前不足：`setSql("comment_count = comment_count + 1")` 是字符串 SQL，字段名重构不安全。

后续优化方案：保留这种原子自增写法，但加强测试和常量管理。

面试回答模板：查询条件我用 `LambdaQueryWrapper`，更新评论数用 `LambdaUpdateWrapper`。Lambda 写法不会手写列名，字段改名时更安全。

### 12. 逻辑删除怎么实现？

标准答案：实体字段加 `@TableLogic`，delete 操作会改写成 update 标记删除，select 自动过滤未删除数据。

项目中的具体实现：`Post.deleted`、`Comment.deleted` 标注 `@TableLogic`。

涉及类名和方法名：`PostService.deletePost()` 调 `postMapper.deleteById(postId)`；`PostService.deleteComment()` 调 `commentMapper.deleteById(commentId)`。

为什么这样设计：内容数据保留，便于审计和分析，前台不可见。

可能被追问什么：ES 也逻辑删除吗？

项目当前不足：MySQL 逻辑删除和 ES 物理删除存在双写风险。

后续优化方案：删除事件异步同步 ES，失败重试。

面试回答模板：MySQL 笔记和评论用逻辑删除，`deleteById` 实际改 `deleted=1`。但 ES 是搜索索引，删除后应该物理删文档。

### 13. MetaObjectHandler 有什么用？

标准答案：MyBatis Plus 自动填充处理器，在 insert/update 时自动设置公共字段，如创建时间和更新时间。

项目中的具体实现：`MybatisPlusConfig.metaObjectHandler()` 填充 `createdAt`、`updatedAt`。

涉及类名和方法名：`Post.createdAt`、`Comment.updatedAt`。

为什么这样设计：业务代码不用每次手动设置时间，避免遗漏和不一致。

可能被追问什么：客户端能不能传 createdAt？

项目当前不足：无明显问题。

后续优化方案：如需审计字段，可扩展填充 `createdBy`、`updatedBy`。

面试回答模板：`MetaObjectHandler` 统一填充时间字段。Post 和 Comment 的 `createdAt/updatedAt` 都不用 Service 手动 set。

## Redis

### 14. Redis 在项目里解决了什么问题？

标准答案：Redis 用内存和丰富数据结构解决高频读写、限流、计数、会话等问题。

项目中的具体实现：PostService 用 Redis 做发布限流、点赞用户集合、实时点赞计数。

涉及类名和方法名：`PostService.checkPublishRateLimit()`、`likePost()`、`getRealTimeLikeCount()`。

为什么这样设计：点赞是高频操作，直接写 MySQL 会造成热点行竞争。

可能被追问什么：Redis 宕机怎么办？

项目当前不足：点赞写操作没有降级，查询点赞数有 DB 快照兜底。

后续优化方案：定时同步点赞数到 DB，Redis 故障时读 DB 快照，写操作短暂熔断。

面试回答模板：PostService 里 Redis 主要做三件事：SETNX 限流，Set 判断用户是否点赞，String 保存实时点赞数。

### 15. SETNX 如何做限流？

标准答案：用 `setIfAbsent` 只在 Key 不存在时写入，并设置 TTL。成功表示放行，失败表示窗口内已经操作过。

项目中的具体实现：`checkPublishRateLimit(userId)` 写 `post:publish:limit:{userId}`，TTL 60 秒。

涉及类名和方法名：`PostService.checkPublishRateLimit()`。

为什么这样设计：发布笔记频率低，简单 60 秒 1 篇足够。

可能被追问什么：如果要做 1 小时 5 次怎么办？

项目当前不足：只能固定限制 60 秒 1 次，不支持滑动窗口配额。

后续优化方案：改成 ZSet Lua 滑动窗口，和 `@RateLimit` 统一。

面试回答模板：发布限频用 SETNX，Key 不存在就写入并设置 60 秒 TTL；如果 Key 已存在，说明 60 秒内发过，直接拒绝。

### 16. Redis Set 如何判断是否点赞？

标准答案：把点赞过的 userId 放进 Set，用 `SISMEMBER` 判断是否存在，天然去重。

项目中的具体实现：`post:like:users:{postId}` 保存点赞用户。

涉及类名和方法名：`PostService.isLikedByCurrentUser()`、`PostService.likePost()`。

为什么这样设计：判断是否点赞是 O(1)，前端展示 liked 字段很快。

可能被追问什么：只用 Set 的 `SCARD` 做计数可以吗？

项目当前不足：Set 和 String 计数可能不一致。

后续优化方案：Lua 原子更新，定时对账。

面试回答模板：我用 Set 存点赞用户，`SISMEMBER post:like:users:{postId} userId` 就知道当前用户是否点赞。

### 17. Redis String 如何做点赞计数？

标准答案：String 可以存整数，使用 `INCR/DECR` 原子自增自减，适合计数。

项目中的具体实现：`post:like:count:{postId}`。

涉及类名和方法名：`PostService.likePost()`、`unlikePost()`、`getRealTimeLikeCount()`。

为什么这样设计：读点赞数只需 GET，比每次聚合 DB 明细更轻。

可能被追问什么：为什么不直接写 MySQL？

项目当前不足：没有保证和 Set 的跨 Key 原子一致。

后续优化方案：Lua 或 Redis 事务脚本。

面试回答模板：点赞数用 String，因为 Redis 对整数 String 支持 `INCR/DECR`，非常适合高频计数。

### 18. Redis 和 MySQL 如何保证一致性？

标准答案：缓存和数据库很难强一致，通常用 Cache Aside、TTL、消息重试、binlog/Canal、定时对账实现最终一致。

项目中的具体实现：PostService 查询点赞数优先 Redis，失败降级 DB 快照；点赞不实时写 DB，等待后续定期同步。

涉及类名和方法名：`PostService.getRealTimeLikeCount()`、`Post.likeCount`。

为什么这样设计：点赞数允许短暂不一致，换取高并发性能。

可能被追问什么：哪些业务不能接受最终一致？

项目当前不足：点赞数同步任务还未在 PostService 这一轮看到完整实现。

后续优化方案：定时同步 Redis count 到 MySQL 和 ES，失败重试并告警。

面试回答模板：点赞数我接受最终一致。实时值在 Redis，DB 保存快照用于兜底，后续通过定时任务或 MQ 把 Redis 同步回 MySQL。

### 19. 缓存穿透、击穿、雪崩分别是什么？

标准答案：穿透是查缓存和 DB 都不存在的数据；击穿是热点 Key 过期导致大量请求打 DB；雪崩是大量 Key 同时过期或 Redis 故障导致流量压到 DB。

项目中的具体实现：PostService 不是典型 Cache Aside 缓存，但点赞数读 Redis 失败会降级 DB 快照；评论/列表未做缓存。

涉及类名和方法名：`PostService.getRealTimeLikeCount()`。

为什么这样设计：点赞数是展示数据，Redis 异常不能让详情页不可用。

可能被追问什么：Post 详情要不要缓存？

项目当前不足：详情未做空值缓存或布隆过滤器；大量不存在 postId 查询会打 DB。

后续优化方案：对热点详情做缓存、空值短 TTL、BloomFilter 判断 postId 是否存在。

面试回答模板：PostService 当前点赞数有 Redis 降级，但详情本身还没做 Cache Aside。后续如果详情流量大，会做详情缓存、空值缓存和布隆过滤器防穿透。

### 20. Lua 脚本可以优化什么？

标准答案：Lua 可以把多条 Redis 命令放到 Redis 单线程中原子执行，避免并发打断。

项目中的具体实现：`RateLimitInterceptor` 调 `rate_limit.lua` 原子执行滑动窗口限流。

涉及类名和方法名：`RateLimitInterceptor.preHandle()`、`src/main/resources/lua/rate_limit.lua`。

为什么这样设计：限流的删除旧窗口、计数、写入当前请求必须原子。

可能被追问什么：点赞为什么也该用 Lua？

项目当前不足：点赞 `SADD + INCR`、取消点赞 `SREM + DECR` 还没 Lua 化。

后续优化方案：新增 like/unlike Lua 脚本。

面试回答模板：限流已经用 Lua 保证原子。点赞也应该改 Lua，因为只有 Set 真的新增成员时才应该加计数。

## 事务和一致性

### 21. 事务和业务的区别是什么？

标准答案：业务是用户目标和规则，事务是数据库保证一组操作 ACID 的技术手段。一个业务可能包含非事务资源，事务不等于业务全部一致性。

项目中的具体实现：发布笔记是业务，里面有 MySQL insert、Redis 初始化、ES 同步。`@Transactional` 只保护 MySQL insert。

涉及类名和方法名：`PostService.publishPost()`。

为什么这样设计：业务动作跨多个系统，必须区分本地强一致和跨系统最终一致。

可能被追问什么：本地事务能不能保证 Redis 和 ES 一致？

项目当前不足：发布和删除还在同步双写阶段。

后续优化方案：Outbox + MQ 或 binlog 订阅。

面试回答模板：业务是“发一篇笔记”，事务只是保证 MySQL 写入原子。Redis 和 ES 不是同一个事务资源，要用最终一致方案。

### 22. 本地事务能不能保证 Redis 和 ES 一致？

标准答案：不能。Spring 本地事务一般绑定数据库连接，Redis 和 ES 不会自动参与提交和回滚。

项目中的具体实现：`publishPost()` 里 DB insert、Redis set、ES save 在一个方法里，但只有 DB 被事务管理。

涉及类名和方法名：`PostService.publishPost()`、`PostSearchService.syncPost()`。

为什么这样设计：当前阶段实现简单，但要知道风险。

可能被追问什么：如果 ES 写成功但 DB 回滚怎么办？

项目当前不足：可能产生 ES 脏文档或 Redis 残留 Key。

后续优化方案：事务提交后事件、Outbox、重试补偿。

面试回答模板：不能。本地事务只能管 MySQL。我的当前实现是同步双写，能跑通但有风险，生产要改成事务内写 Outbox，事务外异步同步 Redis/ES。

### 23. 什么是最终一致性和补偿机制？

标准答案：最终一致性是短时间允许不一致，但通过重试、对账、补偿让系统最终达到正确状态。补偿机制是失败后重新执行或反向修正。

项目中的具体实现：点赞数 Redis 实时、DB 快照延迟同步；ES 同步失败应重试。

涉及类名和方法名：`PostService.getRealTimeLikeCount()`、`PostSearchService.updateLikeCount()`。

为什么这样设计：点赞和搜索排序不要求强实时。

可能被追问什么：怎么发现和修复不一致？

项目当前不足：Post 相关补偿任务还不完整。

后续优化方案：定时对账 Redis/MySQL/ES，失败记录补偿表。

面试回答模板：点赞数和 ES 索引我会设计成最终一致。用户看到短暂延迟可以接受，但系统要有重试、对账和告警。

### 24. 什么是 Outbox 模式？

标准答案：在业务数据库同一事务里写业务表和消息表，提交后由 Relay 投递 MQ 或执行外部同步，保证“业务成功就一定有消息可补偿”。

项目中的具体实现：当前 PostService 还没用 Outbox，但项目有 `OutboxMessage` 和 MQ 模块，后续可用于 `POST_PUBLISHED`、`POST_DELETED`。

涉及类名和方法名：`domain/entity/OutboxMessage.java`、未来 `PostService.publishPost()`。

为什么这样设计：解决 DB 和 ES/Redis/MQ 的跨系统一致性。

可能被追问什么：Outbox 会不会重复投递？

项目当前不足：PostService 当前同步调用 ES，没有 Outbox。

后续优化方案：写 outbox 事件，消费者同步 ES，消费端幂等。

面试回答模板：Outbox 是在 MySQL 事务里同时写业务数据和消息表。Post 发布成功后一定有一条待同步 ES 的事件，后续 Relay 失败可重试。

## Elasticsearch

### 25. 为什么要用 ES？ES 和 MySQL 查询区别是什么？

标准答案：MySQL 适合事务和结构化查询，ES 适合全文搜索和相关性排序。ES 基于倒排索引，能快速按词召回文档。

项目中的具体实现：`PostDocument` 把 `title`、`content`、`shopName` 建成 text 字段并使用 IK 分词。

涉及类名和方法名：`PostDocument`、`PostSearchService.searchPosts()`。

为什么这样设计：用户搜“火锅”“小明饺子馆”时，不适合 MySQL `LIKE '%keyword%'` 扫描。

可能被追问什么：ES 是主库吗？

项目当前不足：PostService 按门店列表仍走 MySQL 且不分页。

后续优化方案：搜索和大分页走 ES，详情回查 MySQL。

面试回答模板：MySQL 是主数据，ES 是搜索索引。MySQL 保证事务，ES 用倒排索引做全文检索和排序。

### 26. 发布笔记后为什么要同步 ES？删除为什么要删除 ES 索引？

标准答案：发布后同步 ES 是为了搜索能召回新内容；删除后删 ES 是为了搜索结果不出现已删除内容。

项目中的具体实现：`publishPost()` 调 `syncPost()`，`deletePost()` 调 `removePost()`。

涉及类名和方法名：`PostService.publishPost()`、`PostService.deletePost()`。

为什么这样设计：ES 文档是 MySQL post 的搜索投影。

可能被追问什么：ES 同步失败怎么办？

项目当前不足：同步双写无重试。

后续优化方案：Outbox/MQ 异步同步，失败重试和死信补偿。

面试回答模板：ES 里存的是派生索引。发布要写索引，删除要删索引，否则用户搜索会看不到新笔记或看到已删除笔记。

## 高并发和幂等

### 27. 点赞为什么要做幂等？

标准答案：网络重试、前端重复点击、接口重放都可能导致同一用户重复点赞。幂等保证多次请求结果和一次一致。

项目中的具体实现：`likePost()` 如果 `SISMEMBER` 为 true 直接返回；`unlikePost()` 如果不是成员直接返回。

涉及类名和方法名：`PostService.likePost()`、`PostService.unlikePost()`。

为什么这样设计：接口对前端更友好，也避免业务重复计数。

可能被追问什么：当前实现并发下真的幂等吗？

项目当前不足：先查再写不是原子，并发下仍可能计数错误。

后续优化方案：Lua 根据 `SADD/SREM` 返回值决定是否改计数。

面试回答模板：点赞要幂等，因为重复点击和重试很常见。当前代码做了逻辑幂等，但并发原子性还要用 Lua 补齐。

### 28. 评论数为什么要防止负数？

标准答案：删除重试、并发删除、数据修复都可能导致重复扣减，计数字段要保护下界。

项目中的具体实现：`deleteComment()` 用 `GREATEST(comment_count - 1, 0)`。

涉及类名和方法名：`PostService.deleteComment()`。

为什么这样设计：展示计数不能出现负数，DB 层表达更可靠。

可能被追问什么：重复删除是否幂等？

项目当前不足：重复删除当前返回 `COMMENT_FORBIDDEN`，不是严格幂等成功。

后续优化方案：根据产品语义决定重复删除返回成功还是错误。

面试回答模板：评论数是冗余快照，删除时用 SQL 的 `GREATEST` 保证不小于 0，避免并发或重试把计数扣成负数。
## Agent / RAG / MCP / 大模型工程

### 41. Agent 和普通 LLM 调用有什么区别？

标准答案：普通 LLM 调用通常是输入 prompt、输出文本；Agent 会维护状态，按目标循环推理，选择工具，观察结果，必要时反思、审批和恢复。

项目中的具体实现：`copilot-agent-service` 用 LangGraph 构建 `llm_node -> tool_node -> reflection_node -> final_node` 状态机；`AgentState` 保存消息、步数、token、HITL、摘要和最终答案。

涉及类名和方法名：`agent/graph.py build_graph()`、`agent/nodes.py llm_node()`、`tool_node()`、`reflection_node()`、`final_node()`、`agent/state.py AgentState`。

为什么这样设计：本项目需要查订单、查支付、查知识库、执行退款等多步动作，单次 LLM 文本生成无法保证业务执行和可追踪。

可能被追问什么：Agent 会不会不可控？如何防止乱调用工具？

项目当前不足：复杂任务规划主要依赖 ReAct 循环，缺少显式 Plan-and-Execute 计划节点。

后续优化方案：为复杂诊断增加计划节点，先生成步骤，再逐步执行和校验。

面试回答模板：我项目里的 Agent 不是一次 LLM 调用。它用 LangGraph 保存状态，LLM 决定下一步，工具节点执行 Java MCP 或 RAG，失败时反思，高风险操作走人审。

### 42. 本项目 ReAct 是怎么实现的？

标准答案：ReAct 把推理和行动交替进行，模型先判断下一步动作，执行工具后再根据观察结果继续推理，直到得到答案。

项目中的具体实现：`llm_node` 绑定工具并让模型输出 tool_calls；`tool_node` 执行工具并把结果写回 messages；路由再回到 `llm_node`。

涉及类名和方法名：`agent/nodes.py llm_node()`、`tool_node()`、`agent/graph.py route_after_llm()`、`route_after_tool()`。

为什么这样设计：订单诊断、补偿券排查、知识问答都需要边查边判断，不适合一次性生成答案。

可能被追问什么：如何避免 ReAct 死循环？

项目当前不足：重复工具调用只做了简单检测。

后续优化方案：引入更强的循环检测、工具调用预算和诊断计划。

面试回答模板：项目的 ReAct 就是 LLM 节点和 Tool 节点循环。LLM 决定查什么，Tool 节点拿到真实结果，再让 LLM 基于 observation 继续判断。

### 43. Workflow、Agent、Tools 在项目里分别是什么？

标准答案：Workflow 是固定流程控制，Agent 是能基于状态做决策的执行体，Tools 是 Agent 可调用的外部能力。

项目中的具体实现：LangGraph 是 Workflow；`llm_node` 和状态路由组成 Agent；Java MCP 工具和 `knowledge_search` 是 Tools。

涉及类名和方法名：`build_graph()`、`ToolRouter.filter_tools()`、`McpClient.call_tool()`、`make_knowledge_search_tool()`。

为什么这样设计：用 Workflow 限制流程边界，用 Agent 处理自然语言决策，用 Tools 访问真实业务系统。

可能被追问什么：为什么不完全让 LLM 自由决定？

项目当前不足：任务分类还是关键词规则。

后续优化方案：把 ToolRouter 升级为轻量分类模型或规则+模型混合。

面试回答模板：我把 LangGraph 当可控流程，把 LLM 放在节点里决策，把 Java MCP 和 RAG 包成工具。这样既有灵活性，也不会让模型绕过流程。

### 44. Tool Calling / Function Calling 是什么？

标准答案：模型不直接执行函数，而是生成函数名和结构化参数，由应用侧校验并执行，执行结果再返回给模型。

项目中的具体实现：`llm_node` 把 MCP Tool Schema 转成 LangChain 工具格式并 `bind_tools`；模型输出 `tool_calls`，`tool_node` 再执行。

涉及类名和方法名：`agent/nodes.py _convert_to_lc_tools()`、`llm_node()`、`tool_node()`。

为什么这样设计：让模型负责选择能力，让业务系统负责执行和校验，避免模型直接操作数据库。

可能被追问什么：工具参数错怎么办？

项目当前不足：参数修复主要依赖 LLM 根据错误提示自我修正。

后续优化方案：对核心工具增加 Pydantic 参数校验和自动纠错。

面试回答模板：Function Calling 本质是模型输出结构化调用意图。项目里 LLM 只产生 tool name 和 JSON 参数，真正执行在 `tool_node` 和 Java MCP Server。

### 45. MCP 是什么？本项目怎么落地？

标准答案：MCP 是把外部工具、资源以统一协议暴露给模型应用的方式，常见能力包括初始化、工具发现、工具调用和结构化返回。

项目中的具体实现：Java `McpController` 提供 JSON-RPC over HTTP，支持 `initialize`、`tools/list`、`tools/call`；Python `McpClient` 负责调用。

涉及类名和方法名：`McpController.handle()`、`ToolRegistry.getTool()`、`McpClient.initialize()`、`list_tools()`、`call_tool()`。

为什么这样设计：Java 后端保留业务数据访问和安全控制，Python Agent 只通过协议调用工具。

可能被追问什么：MCP 和 Function Calling 区别？

项目当前不足：当前是 HTTP JSON-RPC，没有独立的 MCP SDK 生命周期管理。

后续优化方案：补协议兼容测试、工具版本管理和 schema 变更检测。

面试回答模板：本项目 Java 侧是 MCP Server，Python 侧是 MCP Client。模型看到的是工具 Schema，真正执行要经过 Java 侧权限、限流、审计和业务逻辑。

### 46. MCP 和 Function Calling 有什么区别？

标准答案：Function Calling 是模型输出函数调用的交互格式；MCP 更像工具服务协议，负责统一暴露工具、资源和调用方式。

项目中的具体实现：LLM 用 Function Calling 形式产生 tool call；Python Agent 再通过 MCP Client 调 Java MCP Server。

涉及类名和方法名：`_convert_to_lc_tools()`、`McpClient._rpc()`、`McpController.tools/call` 分支。

为什么这样设计：Function Calling 解决模型到应用，MCP 解决应用到工具服务。

可能被追问什么：为什么不让 Python 直接查 Java 数据库？

项目当前不足：跨服务身份 Header 需要加强可信认证。

后续优化方案：MCP 请求加 HMAC、内网网关和重放保护。

面试回答模板：Function Calling 是 LLM 说“我要调用哪个函数”，MCP 是工具服务怎么被发现和调用。项目里两者一起用：模型出 tool call，Python 通过 MCP 调 Java。

### 47. 为什么 ToolRouter 不把所有工具暴露给模型？

标准答案：工具越多，模型误选概率、token 成本和越权风险越高，应按角色、任务和上下文过滤。

项目中的具体实现：`ToolRouter` 按角色过滤，再按任务类型过滤，最后按上下文决定是否暴露退款、补偿券等高风险工具。

涉及类名和方法名：`ToolRouter.filter_tools()`、`_filter_by_role()`、`_classify_task()`、`_filter_by_context()`。

为什么这样设计：让模型只看到当前最相关、最安全的工具集合。

可能被追问什么：如果过滤错了怎么办？

项目当前不足：任务分类基于关键词，复杂表达可能误判。

后续优化方案：用轻量意图分类器或 LLM router，并保留规则兜底。

面试回答模板：我不会把全部工具给模型。项目先按用户角色、问题类型和上下文筛工具，比如没有支付成功上下文就不暴露退款工具。

### 48. RAG 的完整流程是什么？项目里怎么做？

标准答案：RAG 先把知识分块、向量化、入库；查询时召回相关内容，必要时重排，再把上下文交给 LLM 生成答案。

项目中的具体实现：`ingest.py` 扫描知识库；`pipeline.py` 分块、embedding、写 Milvus、建 BM25；查询时向量检索 + BM25 + RRF + rerank，低分拒答。

涉及类名和方法名：`ingest_all()`、`ingest_document()`、`retrieve()`、`MilvusVectorStore.search()`、`BM25Store.search()`、`rerank()`。

为什么这样设计：业务规则和运营知识经常变化，RAG 比微调更适合动态知识。

可能被追问什么：RAG 和微调怎么选？

项目当前不足：知识更新后 BM25 是进程内索引，需要重建。

后续优化方案：BM25 持久化到 Elasticsearch/OpenSearch，增加增量更新。

面试回答模板：项目 RAG 不是直接拼 prompt。先分块入 Milvus 和 BM25，查询时多路召回、RRF 融合、rerank 精排，相关性不足就拒答。

### 49. Chunking 为什么重要？

标准答案：分块粒度影响召回准确率。太大噪声多，太小语义不完整，overlap 可以减少上下文被切断。

项目中的具体实现：`rag/pipeline.py` 将文档切成 chunk，保存 chunk_id、doc_id、content、source、title 等元数据。

涉及类名和方法名：`ingest_document()`。

为什么这样设计：知识问答需要召回最相关片段，而不是整篇文档。

可能被追问什么：如何选择 chunk 大小？

项目当前不足：当前分块策略较简单，未按 Markdown 标题和语义结构切分。

后续优化方案：按标题、段落、表格做结构化切分，并做 chunk 质量评估。

面试回答模板：Chunking 决定 RAG 召回质量。项目先用固定窗口和 overlap，后续会按 Markdown 标题和语义段落优化。

### 50. Embedding 和向量数据库解决什么问题？

标准答案：Embedding 把文本转成向量，向量数据库按相似度检索语义相关内容。

项目中的具体实现：`embedding_client.py` 调 embedding-service；`vector_store.py` 用 Milvus 存 `embedding` 和权限元数据。

涉及类名和方法名：`embed_query()`、`embed_document()`、`MilvusVectorStore.upsert()`、`search()`。

为什么这样设计：用户问法和知识库措辞可能不同，语义检索能找到同义或近义内容。

可能被追问什么：模型切换后向量维度变了怎么办？

项目当前不足：Embedding 失败时返回零向量，生产不够稳。

后续优化方案：Embedding 失败时降级 BM25 或直接失败快返，并加告警。

面试回答模板：Embedding 解决语义匹配，Milvus 负责向量相似度搜索。项目还做了维度保护，避免换模型后旧 collection 被写脏。

### 51. 向量检索和 BM25 有什么区别？

标准答案：向量检索擅长语义相似，BM25 擅长精确关键词、专有名词和编号检索。

项目中的具体实现：`MilvusVectorStore.search()` 做语义召回；`BM25Store.search()` 做关键词召回；`retrieve()` 用 RRF 融合。

涉及类名和方法名：`rag/vector_store.py`、`rag/bm25_store.py`、`rag/pipeline.py`。

为什么这样设计：订单号、活动名、专有词只靠向量可能召回不稳。

可能被追问什么：两个召回分数尺度不同怎么融合？

项目当前不足：中文分词目前比较简单。

后续优化方案：接入 jieba 或搜索引擎分词器，并扩展同义词词典。

面试回答模板：向量搜语义，BM25 搜关键词。项目把两者都召回，再用 RRF 按排名融合，避免分数尺度不一致。

### 52. Rerank 在 RAG 里解决什么？

标准答案：召回阶段更关注不漏，Rerank 阶段更关注精排，把最相关的上下文放到前面。

项目中的具体实现：`reranker_client.py` 调 reranker-service，对候选文档按 query-doc 相关性重新打分。

涉及类名和方法名：`rerank()`、`retrieve()`。

为什么这样设计：向量召回和 BM25 召回可能带噪声，精排能提高最终上下文质量。

可能被追问什么：Reranker 挂了怎么办？

项目当前不足：reranker 失败 fallback 后质量会下降。

后续优化方案：对 fallback 结果加标记和监控，必要时减少回答置信度。

面试回答模板：召回先找候选，rerank 再精排。项目 reranker 挂了会按原始分数 fallback，但会记录 warning。

### 53. RAG 如何防止幻觉？

标准答案：限制模型只基于检索上下文回答，检索不到要拒答，并保留来源用于校验。

项目中的具体实现：`retrieve()` 对低于阈值的结果返回 refused；`knowledge_search` 返回 found、context、sources；Guardrails 拦截越权和提示词注入。

涉及类名和方法名：`rag/pipeline.py retrieve()`、`rag/knowledge_tool.py make_knowledge_search_tool()`、`guardrails/input_checker.py`。

为什么这样设计：知识库问答不能让模型凭训练记忆编平台规则。

可能被追问什么：如果检索错了但模型回答得很自信怎么办？

项目当前不足：最终答案还需要更严格的引用校验。

后续优化方案：要求关键结论绑定 source，输出前做事实一致性检查。

面试回答模板：项目 RAG 检索不到就拒答，检索到会带 sources。后续会做到关键结论必须有来源，否则不输出。

### 54. Memory 在 Agent 中有什么作用？

标准答案：Memory 让 Agent 保持上下文、跨步骤推理、恢复中断任务，并管理长上下文成本。

项目中的具体实现：`AgentState.messages` 保存短期消息，`session/manager.py` 持久化消息，`AsyncMySQLCheckpointer` 保存 LangGraph checkpoint，`compact_node` 做摘要压缩。

涉及类名和方法名：`AgentState`、`AsyncMySQLCheckpointer.aput()`、`compact_node()`。

为什么这样设计：HITL 审批和多轮诊断都需要恢复之前状态。

可能被追问什么：Memory 会不会越来越大？

项目当前不足：摘要质量依赖 LLM，缺少摘要评估。

后续优化方案：给摘要做结构化模板和回归测试。

面试回答模板：项目 Memory 分运行时和持久化两层。运行时在 `AgentState`，持久化在 MySQL checkpoint，长会话靠 compact 摘要控制 token。

### 55. HITL 是什么？项目哪里用？

标准答案：HITL 是 Human-in-the-loop，人参与关键决策，常用于高风险或不可逆操作。

项目中的具体实现：`execute_refund`、`issue_compensation_coupon` 这类 L4 工具标记 `xRequiresHitl`；`hitl_node` 创建审批，`/chat/resume` 审批通过后继续。

涉及类名和方法名：`ExecuteRefundTool.getDefinition()`、`hitl_node()`、`api/chat.py resume_chat()`、`session/hitl.py`。

为什么这样设计：退款和补偿券有资金风险，不能让 LLM 直接执行。

可能被追问什么：审批通过后如何保证状态没丢？

项目当前实现：审批和业务执行跨服务，Java 主服务用 `side_effect_ledger` 记录 `operation_type + approval_id`，重复恢复同一审批时直接返回第一次成功结果。

后续优化方案：接入 `agent_run/agent_event` 后，把 `run_id/trace_id/operator_id` 也写入账本，并把外部支付渠道返回码纳入对账。

面试回答模板：高风险工具必须 HITL。模型只提出调用意图，系统暂停并发审批事件，审批通过后带 `approval_id` 恢复执行；Java 主服务再用 `side_effect_ledger` 做最终幂等和结果追踪。

### 56. Prompt Engineering 如何落到项目里？

标准答案：Prompt Engineering 不是只写提示词，还包括角色、约束、工具说明、输出格式、安全规则和上下文管理。

项目中的具体实现：`_build_system_prompt()` 注入角色、工具、ReAct 规则、安全约束、HITL 要求和压缩摘要。

涉及类名和方法名：`agent/nodes.py _build_system_prompt()`、`llm_node()`。

为什么这样设计：Agent 要稳定调用工具，必须让模型理解业务边界和工具约束。

可能被追问什么：工具说明太长怎么办？

项目当前不足：工具描述和系统提示增长后会增加 token 成本。

后续优化方案：工具 Schema TTL 缓存、ToolRouter 缩小工具集合、必要时用 Prompt Caching。

面试回答模板：项目 Prompt 包含角色、工具、约束和安全规则，不是简单问答。ToolRouter 还会减少工具数量，降低 prompt 成本和误调概率。

### 57. Agent 如何调用 Java 后端业务接口？

标准答案：Agent 不应直接操作数据库，而是通过受控工具或内部 API 调用后端能力。

项目中的具体实现：Python `McpClient.call_tool()` 调 Java MCP Server；Java 工具再用 Mapper 查询或 `LocalLifeInternalClient` 调 local-life-server 内部接口。

涉及类名和方法名：`McpClient.call_tool()`、`McpController.handle()`、`QueryOrderTool.execute()`、`ExecuteRefundTool.execute()`、`LocalLifeInternalClient.refund()`。

为什么这样设计：Java 后端继续负责权限、事务、审计和业务规则。

可能被追问什么：Python Agent 为什么不直接连业务库？

项目当前不足：内部调用需要更完善的签名和服务间鉴权。

后续优化方案：MCP 内网隔离、HMAC 签名、统一内部网关。

面试回答模板：Agent 通过 MCP 工具访问 Java 后端。Python 负责决策，Java 负责业务执行，这样不会绕过后端的权限、事务和审计。

### 58. Agent 失败如何降级？

标准答案：Agent 失败要区分 LLM、工具、检索、审批、状态存储等环节，分别做重试、fallback、拒答或人审。

项目中的具体实现：MCP 超时返回 `tool_timeout`；Reranker 失败 fallback 原始排序；Checkpointer 失败 fallback MemorySaver；RAG 低分拒答；Fast Path 绕过 LLM。

涉及类名和方法名：`McpToolError.is_retryable()`、`rerank()`、`build_graph()`、`retrieve()`、`api/chat.py` Fast Path。

为什么这样设计：Agent 链路长，单点失败不能直接让用户看到混乱答案。

可能被追问什么：哪些降级不适合生产？

项目当前不足：Embedding 失败返回零向量，生产上不够合理。

后续优化方案：Embedding 失败改 BM25 only 或失败快返，并加告警。

面试回答模板：项目按失败点降级。工具超时可重试，RAG 低分拒答，reranker 挂了用召回分数，checkpoint 挂了退内存，但生产会加强关键路径失败快返。

### 59. Agent 如何做可观测性？

标准答案：需要观测会话完成率、工具调用、LLM token、延迟、RAG 命中、HITL 状态、错误和评估指标。

项目中的具体实现：`agent/metrics.py` 暴露 Prometheus 指标；`trace.py genai_span()` 记录 LLM/MCP span；`ToolAuditService` 记录工具审计；`evals` 定义评估指标和用例。

涉及类名和方法名：`record_tool_call()`、`record_llm_call()`、`genai_span()`、`ToolAuditService.recordSuccess()`、`evals/metrics.py`。

为什么这样设计：Agent 问题通常不是一个接口报错，而是工具选择、检索质量、模型输出等多环节问题。

可能被追问什么：Prometheus label 为什么不能放 user_id？

项目当前不足：Java 异步审计读取 ThreadLocal 可能拿不到用户上下文。

后续优化方案：异步前捕获 RBAC 上下文，或用 TaskDecorator 传播上下文。

面试回答模板：我会看 Agent 完成率、工具成功率、P99 延迟、RAG 命中和 token 成本。项目有 Prometheus 指标、genai span、工具审计和 eval case。

### 60. LangChain/LangGraph 和手写 Agent 怎么取舍？

标准答案：框架能快速搭建工具调用、状态机和模型适配；手写能减少抽象成本，便于控制安全、审计和业务流程。

项目中的具体实现：项目用 LangGraph 管流程，用 LangChain 模型和工具绑定；但 ToolRouter、HITL、MCP Client、RAG Pipeline、Guardrails 都是业务手写。

涉及类名和方法名：`agent/graph.py`、`agent/nodes.py`、`agent/tool_router.py`、`mcp/mcp_client.py`、`rag/pipeline.py`。

为什么这样设计：既利用框架的状态机能力，又保留业务安全和工程控制。

可能被追问什么：如果不用 LangGraph 行不行？

项目当前不足：LangChain 专页资料还在更新中，项目文档需要持续跟进。

后续优化方案：补 LangSmith 或自研 trace 对比，沉淀框架选型依据。

面试回答模板：我项目不是纯手写也不是全托管框架。LangGraph 管状态流转，业务关键点如工具路由、HITL、MCP、RAG 和安全规则都自己控制。
