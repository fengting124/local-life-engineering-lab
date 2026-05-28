# LocalLife 项目接口全链路教程

> 目标：读完这篇文档，你应该能对着面试官把任何一个接口从头讲到尾，不用背，靠真正理解。

---

## 目录

1. [一次请求是怎么走的？先把地图画出来](#1-一次请求是怎么走的先把地图画出来)
2. [统一响应结构：Result 是什么，为什么要它](#2-统一响应结构result-是什么为什么要它)
3. [错误码：为什么不用数字，要用字符串](#3-错误码为什么不用数字要用字符串)
4. [登录模块：从发验证码到拿 Token 的完整链路](#4-登录模块从发验证码到拿-token-的完整链路)
5. [鉴权拦截器：每次请求背后发生了什么](#5-鉴权拦截器每次请求背后发生了什么)
6. [门店模块：状态机和权限双校验](#6-门店模块状态机和权限双校验)
7. [笔记模块：Redis 点赞计数与 N+1 问题](#7-笔记模块redis-点赞计数与-n1-问题)
8. [关注模块：ZSet 与共同关注](#8-关注模块zset-与共同关注)
9. [关键词卡片：用自己的话解释这 10 个词](#9-关键词卡片用自己的话解释这-10-个词)
10. [链路复述练习题：面试前必做](#10-链路复述练习题面试前必做)
11. [优惠券 & 秒杀模块：Redis 原子操作与异步结果查询](#11-优惠券--秒杀模块redis-原子操作与异步结果查询)
12. [订单 & 支付模块：状态机与幂等设计](#第-6-章订单--支付模块)
13. [MQ 可靠消息：Transactional Outbox 模式](#第-7-章消息可靠性transactional-outbox--指数退避--消费者幂等)
14. [Elasticsearch 全文搜索：门店和笔记怎么搜出来的](#第-8-章elasticsearch-全文搜索门店和笔记怎么搜出来的)
15. [接口限流：Redis 滑动窗口怎么防刷](#第-10-章接口限流redis-滑动窗口怎么防刷)

---

## 1. 一次请求是怎么走的？先把地图画出来

在讲任何接口之前，先搞清楚一件事：**一个 HTTP 请求从前端发出到拿到响应，到底经过了哪些地方？**

```
前端（浏览器/App）
    ↓  发出 HTTP 请求（GET/POST/PUT/DELETE）
    ↓  Header 里带着 Authorization: Bearer {token}
    
DispatcherServlet（Spring MVC 的大门卫）
    ↓  判断这个请求该交给哪个 Controller 处理
    
AuthInterceptor（鉴权拦截器，大门卫的助手）
    ↓  在 Controller 之前先检查 Token
    ↓  从 Redis 验证 Token → 读用户信息 → 存到 ThreadLocal
    
Controller（接待员）
    ↓  接收请求参数，做基础格式校验（@Valid）
    ↓  调用 Service
    
Service（业务逻辑核心）
    ↓  检查权限、执行业务规则
    ↓  调用 Mapper 查数据库
    ↓  读写 Redis
    ↓  返回 VO（View Object，给前端看的对象）
    
Mapper（数据库操作员）
    ↓  执行 SQL，操作 MySQL
    
Controller 拿到 VO
    ↓  包装成 Result<T> 统一格式
    
前端收到 JSON 响应
```

**记住这张图。** 面试官问「讲一下登录流程」，你脑子里要马上出现这条线，然后把每一层发生的事说清楚。

---

## 2. 统一响应结构：Result 是什么，为什么要它

### 2.1 先讲人话

假设你开了一家餐厅，顾客点菜后服务员回来告诉你结果。有两种回答方式：

**方式 A（没有统一格式）**：
- 上菜：直接端上来一盘菜
- 没有：只说「没有」
- 出错：说「厨房着火了」
- 付钱：说「298块」

**方式 B（统一格式）**：
- 成功：「状态：成功，菜：一盘鱼香肉丝，时间：20:00」
- 没有：「状态：失败，原因：食材卖完了，时间：20:00」
- 出错：「状态：失败，原因：系统繁忙，时间：20:00」

**方式 B 的优点**：前端只需要写一套处理逻辑，判断 `code == "OK"` 就行，不用对每种接口写不同的解析代码。

### 2.2 我们项目的 Result 长什么样

```json
// 成功的时候
{
  "code": "OK",
  "message": "操作成功",
  "data": {
    "userId": "10001",
    "nickname": "小明"
  },
  "timestamp": "2026-05-26T20:00:00+08:00"
}

// 失败的时候
{
  "code": "SHOP_NOT_FOUND",
  "message": "门店不存在",
  "data": null,
  "timestamp": "2026-05-26T20:00:00+08:00"
}
```

### 2.3 代码在哪里，干了什么

看 [Result.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/common/result/Result.java)，核心是三个静态工厂方法：

```java
// 有数据的成功响应 → 比如返回用户信息
Result.ok(vo)         // {"code":"OK","data":{...}}

// 没有数据的成功响应 → 比如删除、点赞
Result.ok()           // {"code":"OK","data":null}

// 失败响应 → 比如门店不存在
Result.fail(ErrorCode.SHOP_NOT_FOUND)  // {"code":"SHOP_NOT_FOUND","message":"门店不存在","data":null}
```

**Controller 里是这样用的**（以查门店详情为例）：

```java
// ShopController.java
@GetMapping("/{shopId}")
public Result<ShopVO> getShopDetail(@PathVariable Long shopId) {
    ShopVO vo = shopService.getShopDetail(shopId);  // Service 返回 VO
    return Result.ok(vo);                            // 包装成 Result 返回
}
```

**面试怎么说**：
> 我们项目用 `Result<T>` 统一包装所有接口的响应。成功时 code 是 `OK`，失败时 code 是具体的业务错误码，data 为 null。前端只需要判断 code 字段，不需要对每个接口写不同的解析逻辑。

---

## 3. 错误码：为什么不用数字，要用字符串

### 3.1 先讲人话

想象你收到了一封报错邮件，上面写着：
- 版本 A：「错误代码：40009」
- 版本 B：「错误代码：COUPON_STOCK_EXHAUSTED（优惠券已抢完）」

哪个一眼就懂？显然是 B。

### 3.2 我们的错误码命名规则

格式：`{模块}_{错误描述}`，全大写，下划线分隔。

```
AUTH_CODE_INVALID          → 验证码错误
AUTH_TOKEN_MISSING         → 未携带 Token
SHOP_NOT_FOUND             → 门店不存在
SHOP_FORBIDDEN             → 无权操作该门店
COUPON_STOCK_EXHAUSTED     → 优惠券已抢完
ORDER_STATUS_ILLEGAL       → 订单状态不允许此操作
POST_PUBLISH_TOO_FREQUENT  → 发布过于频繁
```

### 3.3 错误码和 HTTP 状态码的关系

很多人会混淆这两个。记住：**HTTP 状态码是快速分类，业务错误码是精确原因**。

| 场景 | HTTP 状态码 | 业务错误码 |
|---|---|---|
| 发布成功 | 200 | `OK` |
| 验证码错误 | 400 | `AUTH_CODE_INVALID` |
| 没登录 | 401 | `AUTH_TOKEN_MISSING` |
| 无权限 | 403 | `SHOP_FORBIDDEN` |
| 限流了 | 429 | `POST_PUBLISH_TOO_FREQUENT` |
| 系统崩了 | 500 | `SYS_BUSY` |

**注意**：我们项目不用 404！资源不存在统一用 400 + 业务码（`SHOP_NOT_FOUND`），原因是让前端只判断 `code` 字段，不用再分支判断 HTTP 状态码。

### 3.4 全局异常处理器：错误码是怎么变成响应的

当 Service 层抛出异常，Controller 不需要 try-catch，由 [GlobalExceptionHandler.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/common/exception/GlobalExceptionHandler.java) 统一兜底：

```java
// Service 层这样抛异常
throw new BizException(ErrorCode.SHOP_NOT_FOUND);

// GlobalExceptionHandler 捕获，自动转成响应
// → HTTP 400，body: {"code":"SHOP_NOT_FOUND","message":"门店不存在","data":null}
```

**链路**：Service 抛 BizException → GlobalExceptionHandler 捕获 → 读取 ErrorCode 里的 httpStatus 设置响应状态码 → 调 `Result.fail(errorCode)` 构建响应体 → 返回给前端。

**面试怎么说**：
> 业务异常统一用 `BizException` 抛出，携带 `ErrorCode` 枚举。`GlobalExceptionHandler` 用 `@RestControllerAdvice` 全局捕获，根据 ErrorCode 里的 httpStatus 设置 HTTP 状态码，body 里返回字符串格式的业务码，这样前端只判断 code 字段就能知道具体原因。

---

## 4. 登录模块：从发验证码到拿 Token 的完整链路

### 4.1 整个登录流程

```
第一步：用户输入手机号，点「获取验证码」
    前端 → POST /api/v1/auth/code { "mobile": "13800138000" }
    
后端 AuthController.sendCode()
    → AuthService.sendCode()
        → 限流检查（60秒内只能发1次，Redis Key: login:sms:mobile:{mobile}）
        → 生成6位随机数验证码
        → 存入 Redis（Key: login:code:{mobile}，TTL 5分钟）
        → 调短信服务发送（当前是 mock，只打 log）
    ← 返回 {"code":"OK"}

----------------------------------------------

第二步：用户收到验证码，输入后点「登录」
    前端 → POST /api/v1/auth/login { "mobile": "13800138000", "code": "123456" }
    
后端 AuthController.login()
    → AuthService.login()
        → 从 Redis 取验证码，比对（不匹配 → AUTH_CODE_INVALID）
        → 验证码用完即删（一次性，防止重放攻击）
        → 查数据库 user 表（手机号存不存在？）
            → 不存在 → 自动注册（手机号即账户，INSERT user 记录）
            → 存在 → 检查状态（DISABLED → USER_ACCOUNT_DISABLED）
        → 生成 UUID 作为 Token
        → 把用户摘要（userId、nickname 等）存入 Redis
          （Key: login:token:{uuid}，TTL 7天）
        → 返回 Token 给前端
    ← 返回 {"code":"OK","data":{"token":"xxx","userId":"10001","nickname":"小明"}}
```

### 4.2 关键设计决策：为什么不用 JWT？

JWT（JSON Web Token）是另一种常见的登录 Token 方案。我们选 UUID + Redis，原因：

| 对比点 | UUID + Redis | JWT |
|---|---|---|
| 主动踢出登录 | ✅ 删 Redis Key 即可 | ❌ JWT 不能服务端主动失效 |
| 账号封禁即时生效 | ✅ Redis 标记，下次请求立即拒绝 | ❌ JWT 要等到过期才失效 |
| Token 大小 | 小（UUID 36字符） | 大（包含载荷，通常 200+ 字符） |
| 服务器状态 | 有状态（依赖 Redis） | 无状态 |

**面试追问**：「JWT 有什么优势？」
> JWT 的优势是无状态，不需要 Redis，天然适合微服务（每个服务自己校验签名）。但对于需要主动踢用户下线的场景（封号、账号异常），UUID + Redis 更安全。这个项目选 Redis 方案是因为当前阶段有账号禁用需求，且还没到分布式微服务阶段。

### 4.3 验证码防刷：Redis 怎么做限流

```
第一次发验证码（13800138000）：
    Redis 里没有 Key → 允许发送
    SET login:sms:mobile:13800138000 1 EX 3600  （写入，TTL 1小时）
    → 短信发出

60秒内再发：
    Key: login:sms:mobile:13800138000 的 TTL 是否 > 3540 秒？
    （3600 - 60 = 3540，说明刚刚设置）→ 拒绝，返回 AUTH_CODE_SEND_TOO_FREQUENT
    
1小时内发第6次：
    INCR 计数器，发现 count > 5 → 拒绝
```

这里同时用了两个维度的限流：
- **60 秒内最多 1 次**（防止用户手抖连续点）
- **1 小时内最多 5 次**（防止批量爆破）

---

## 5. 鉴权拦截器：每次请求背后发生了什么

### 5.1 先讲人话

你进公司大楼要刷门禁卡，门禁系统会：
1. 检查你有没有带卡（没卡 → 拒绝）
2. 刷一下卡（卡失效 → 拒绝）
3. 确认你是正式员工（被开除 → 拒绝）
4. 放你进去，记录你的身份信息

鉴权拦截器干的事情完全一样。

### 5.2 每次请求的执行流程

看 [AuthInterceptor.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/common/interceptor/AuthInterceptor.java)：

```
前端发请求，Header 里有：Authorization: Bearer abc123xxx

AuthInterceptor.preHandle() 执行：

1. 生成 requestId（UUID），存入 MDC（用于日志追踪，同一次请求的所有日志都有这个 ID）

2. 从 Header 提取 Token
   → 没有 Authorization Header → 抛 AUTH_TOKEN_MISSING

3. Redis GET login:token:{token}
   → Redis 里没有这个 Key → Token 过期或无效 → 抛 AUTH_TOKEN_EXPIRED
   → 有 → 反序列化成 LoginUserDTO（含 userId、nickname、status）

4. 检查用户状态
   → status == "DISABLED" → 抛 USER_ACCOUNT_DISABLED

5. 把 LoginUserDTO 存入 ThreadLocal（UserContext.set(dto)）
   → Service 层可以通过 UserContext.getUserId() 随时取到当前用户 ID

6. 刷新 Token 的 TTL（续期 7 天）
   → 活跃用户不会因为 7 天没登录就被踢出

7. 放行 → 继续执行 Controller
```

```
请求处理完毕（不管成功还是报错），afterCompletion() 执行：

1. UserContext.clear()（清除 ThreadLocal，防止线程池复用时读到上一个请求的用户信息）
2. MDC.remove("requestId")（清除 MDC）
```

### 5.3 ThreadLocal 是什么，为什么用它

**人话解释**：ThreadLocal 是每个线程自己的「私人储物柜」。每个请求在 Tomcat 里由一个线程处理，这个线程的储物柜里存着当前用户的信息，只有这个线程能拿到，其他线程看不见。

**为什么不用全局变量？** 因为服务器同时处理成千上万个请求，如果用全局变量存用户信息，不同请求会互相覆盖，出现「A 用户的操作用了 B 用户的身份」这种灾难性 bug。

**为什么 afterCompletion 里必须 clear？** Tomcat 用线程池，线程处理完请求 A 后会被复用去处理请求 B。如果 A 没有 clear，B 开始时 ThreadLocal 里还有 A 的用户信息，这个 bug 极难复现（只在线程复用时才出现）。

### 5.4 白名单：哪些接口不需要 Token

```java
// WebMvcConfig.java — 以下路径跳过 AuthInterceptor
.excludePathPatterns(
    "/api/v1/auth/**",         // 登录相关（发验证码、登录）
    "/api/v1/shops",           // 搜索门店列表（游客可以浏览）
    "/api/v1/shops/*",         // 门店详情（游客可以浏览）
    "/api/v1/posts/*",         // 笔记详情（游客可以浏览）
    "/api/v1/payments/callback" // 支付回调（支付渠道服务器调用，没有用户 Token）
)
```

**面试追问**：「/api/v1/auth/logout 也在白名单里，那 Token 过期的用户也能退出？」
> 对，这是故意设计的。退出登录的动作应该永远能执行，即使 Token 已经过期。否则 Token 过期的用户就会陷入「无法退出」的死循环。退出时前端把 Token 发过来，后端直接删 Redis Key，没有 Key 删也无所谓，幂等处理。

---

## 6. 门店模块：状态机和权限双校验

### 6.1 门店状态机

门店的状态不是随意变的，有严格的合法路径：

```
DRAFT（草稿）
  ↓  上线（PUT /shops/{id}/status/online）
ONLINE（已上线）
  ↓  下线（PUT /shops/{id}/status/offline）   ↗  恢复上线
OFFLINE（已下线）
  ↓  永久关闭（当前接口未暴露，预留）
CLOSED（永久关闭，终态，不可逆）
```

**非法流转举例**：
- CLOSED → ONLINE：不行，CLOSED 是终态，代码里明确拒绝
- ONLINE → ONLINE：不行，已经上线了，没必要再上线

```java
// ShopService.java — 上线接口
public ShopVO onlineShop(Long shopId) {
    Shop shop = requireOwnShop(shopId);  // ← 先做权限校验

    // 状态机保护：只有 DRAFT 或 OFFLINE 才能上线
    if (!"DRAFT".equals(shop.getStatus()) && !"OFFLINE".equals(shop.getStatus())) {
        throw new BizException(ErrorCode.SHOP_STATUS_ILLEGAL);
    }

    shop.setStatus("ONLINE");
    shopMapper.updateById(shop);
    return toVO(shop);
}
```

### 6.2 权限双校验：防越权操作

商家 A 不应该能修改商家 B 的门店。我们在 `requireOwnShop` 方法里同时做两件事：

```java
// ShopService.java — 私有辅助方法
private Shop requireOwnShop(Long shopId) {
    // 第一层：校验当前用户是 APPROVED 商家
    Merchant merchant = merchantService.requireApprovedMerchant();

    // 第二层：校验这个门店是你的
    Shop shop = shopMapper.selectById(shopId);
    if (shop == null || !merchant.getId().equals(shop.getMerchantId())) {
        // 关键设计：「门店不存在」和「不是你的门店」返回同一个错误码 SHOP_FORBIDDEN
        // 为什么？防止枚举攻击：攻击者无法通过错误码的差异判断这个 shopId 是否存在
        throw new BizException(ErrorCode.SHOP_FORBIDDEN);
    }
    return shop;
}
```

**面试追问**：「为什么不返回 SHOP_NOT_FOUND 和 SHOP_FORBIDDEN 两个不同的错误码？」
> 这是信息安全的考量。如果我们返回「门店不存在」，攻击者就能通过遍历 shopId 知道哪些 ID 是有效的门店（枚举攻击）。统一返回 SHOP_FORBIDDEN，攻击者什么都猜不到。

### 6.3 C 端查询为什么返回 SHOP_NOT_FOUND 而不是 OFFLINE

```java
// ShopService.java — C 端查门店详情
public ShopVO getShopDetail(Long shopId) {
    Shop shop = shopMapper.selectById(shopId);
    if (shop == null || !"ONLINE".equals(shop.getStatus())) {
        // DRAFT/OFFLINE/CLOSED 状态的门店也返回 SHOP_NOT_FOUND
        // 不让用户知道「这个门店存在但没上线」，防止竞品爬取未上线门店的情报
        throw new BizException(ErrorCode.SHOP_NOT_FOUND);
    }
    return toVO(shop);
}
```

---

## 7. 笔记模块：Redis 点赞计数与 N+1 问题

### 7.1 点赞计数方案

点赞是高频操作，不能每次点赞都写数据库（MySQL 写性能有限，高并发会把数据库打垮）。

我们用 **Redis 双 Key 方案**：

```
Key 1：post:like:count:{postId}  → String 类型，存点赞总数
Key 2：post:like:users:{postId}  → Set 类型，存所有点赞用户的 ID
```

**点赞操作**：
```
用户 A 点赞笔记 123：
    SISMEMBER post:like:users:123 "10001"  → false（还没点过）
    SADD post:like:users:123 "10001"       → 把用户 ID 加入 Set
    INCR post:like:count:123               → 点赞数 +1
```

**取消点赞**：
```
    SISMEMBER post:like:users:123 "10001"  → true（点过了）
    SREM post:like:users:123 "10001"       → 从 Set 移除
    DECR post:like:count:123               → 点赞数 -1
```

**查「我有没有点赞」**：
```
    SISMEMBER post:like:users:123 "10001"  → O(1) 直接返回 true/false
```

**面试追问**：「为什么用两个 Key，用一个 Set 不行吗？SCARD 也能取总数。」
> SCARD 确实是 O(1)，但 Set 随着点赞人数增长会越来越大，占用大量 Redis 内存（热门内容可能有几十万点赞用户）。String 计数只需要存一个整数，内存占用是固定的，更轻量。两个 Key 并存是计数轻量 + 判断高效的平衡方案。

### 7.2 Redis 故障降级

如果 Redis 挂了，点赞数读不出来怎么办？

```java
// PostService.java — 读取实时点赞数
private int getRealTimeLikeCount(Post post) {
    try {
        String val = stringRedisTemplate.opsForValue().get(countKey);
        if (val != null) return Integer.parseInt(val);
    } catch (Exception e) {
        // Redis 不可用：记 WARN 日志，不中断请求（降级策略）
        log.warn("读取 Redis 点赞数失败，降级到 DB 快照，postId: {}", post.getId(), e);
    }
    // 降级到数据库快照值（可能和真实值有几秒误差，可以接受）
    return post.getLikeCount() != null ? post.getLikeCount() : 0;
}
```

**关键设计原则**：Redis 出问题不应该导致接口报错。点赞数展示不准确是可以接受的（最终一致性），但接口挂掉是不能接受的。

### 7.3 N+1 查询问题和解决方案

**什么是 N+1 问题？**

假设查一个门店下的 20 篇笔记，需要展示每篇笔记的作者昵称：

```
错误做法（N+1 查询）：
    查 20 篇笔记 → 1 次 SQL
    循环 20 篇，每篇查一次作者 → 20 次 SQL
    总计：21 次 SQL （这就是 N+1）
```

随着数据量增长，性能会线性下降。10 条数据 11 次查询，100 条数据 101 次查询。

**正确做法（批量查询）**：

```java
// PostService.java — listPostsByShop 方法
// 1. 先查 20 篇笔记
List<Post> posts = postMapper.selectList(wrapper);

// 2. 提取所有 userId，去重
List<Long> userIds = posts.stream()
    .map(Post::getUserId)
    .distinct()
    .collect(Collectors.toList());

// 3. 一次 IN 查询，查所有作者（只有 1 次 SQL）
List<User> users = userMapper.selectBatchIds(userIds);
// 对应的 SQL：SELECT * FROM user WHERE id IN (1001, 1002, 1003, ...)

// 4. 转 Map，方便按 userId 快速查找作者
Map<Long, User> userMap = users.stream()
    .collect(Collectors.toMap(User::getId, u -> u));

// 5. 组装 VO（从 Map 取，O(1)，不再查数据库）
posts.stream().map(post -> {
    User user = userMap.get(post.getUserId());  // ← 从内存 Map 取，不查 DB
    return toVO(post, user, ...);
})
```

总计：2 次 SQL（查笔记 + 批量查用户）。

**面试怎么说**：
> 我们在查笔记列表时，需要展示每个作者的昵称和头像。如果循环 N 篇笔记各查一次 user 表，就是 N+1 问题。解决方式是先从所有笔记中提取 userId 列表，一次 IN 查询拿到所有作者，再转成 Map 按 ID 索引，组装 VO 时直接从内存取，总共 2 次 SQL，不随数据量线性增长。

### 7.4 发布频率限流

```java
// PostService.java — checkPublishRateLimit
String limitKey = String.format("post:publish:limit:%d", userId);
// setIfAbsent = Redis SETNX：Key 不存在才设置，同时设 TTL
Boolean allowed = stringRedisTemplate.opsForValue()
    .setIfAbsent(limitKey, "1", 60, TimeUnit.SECONDS);

if (!Boolean.TRUE.equals(allowed)) {
    // SETNX 返回 false → Key 已存在 → 60 秒内已发过 → 拒绝
    throw new BizException(ErrorCode.POST_PUBLISH_TOO_FREQUENT);
}
```

**为什么这样能限流？**
- SETNX（Set if Not eXists）只在 Key 不存在时才设置
- 第一次发布：Key 不存在 → 设置成功（返回 true）→ Key 的 TTL 是 60 秒
- 60 秒内再发：Key 已存在 → 设置失败（返回 false）→ 拒绝
- 60 秒后：Key 自动过期 → 允许再发

---

## 8. 关注模块：ZSet 与共同关注

### 8.1 为什么用 ZSet（有序集合）而不是 Set

| 数据结构 | 判断是否关注 | 查关注列表（按时间） | 求共同关注 |
|---|---|---|---|
| Set | ✅ SISMEMBER O(1) | ❌ 无序 | ✅ SINTER |
| ZSet | ✅ ZSCORE O(log N) | ✅ ZREVRANGE | ✅ ZINTERSTORE |

我们选 ZSet，Score 存关注时间戳：
- 关注时：ZADD follow:set:{userId} {timestamp} "{targetUserId}"
- 取关时：ZREM follow:set:{userId} "{targetUserId}"
- 查关注列表（按时间倒序）：ZREVRANGE follow:set:{userId} 0 -1
- 判断是否关注：ZSCORE follow:set:{userId} "{targetUserId}"（不为 null 则已关注）

### 8.2 共同关注计算

「我和用户 B 的共同关注」= 我关注的人 ∩ B 关注的人

```
我的关注集合：follow:set:1001 = {2001, 2002, 2003, 2004}
B 的关注集合：follow:set:1002 = {2002, 2003, 2005}

ZINTERSTORE temp_key 2 follow:set:1001 follow:set:1002
→ 交集 = {2002, 2003}  ← 这就是共同关注的用户 ID

ZRANGE temp_key 0 -1   → 读取交集
DEL temp_key           → 删掉临时 Key（避免垃圾数据）

再批量查 user 表（selectBatchIds）→ 返回用户信息
```

### 8.3 冷启动处理

用户登录后第一次查共同关注，Redis 里可能没有这个用户的关注集合（Redis 重启了，或者用户是第一次用）：

```java
// FollowService.java — ensureFollowSetLoaded
private void ensureFollowSetLoaded(Long userId, String followSetKey) {
    Long size = stringRedisTemplate.opsForZSet().zCard(followSetKey);
    if (size != null && size > 0) return;  // Redis 已有数据，不用加载

    // 从数据库加载该用户的全量关注列表，写入 Redis ZSet
    List<FollowRelation> relations = followRelationMapper.selectList(
        new LambdaQueryWrapper<FollowRelation>()
            .eq(FollowRelation::getFollowerUserId, userId)
    );
    // 批量 ZADD
    for (FollowRelation r : relations) {
        stringRedisTemplate.opsForZSet().add(followSetKey, String.valueOf(r.getFollowedUserId()), score);
    }
}
```

这个模式叫 **Cache Rebuild on Miss**（缓存未命中时重建），是缓存使用的标准策略之一。

---

## 9. 关键词卡片：用自己的话解释这 10 个词

> 要求：不准背定义，必须加上「在我们项目里，它用来做___」。

### DTO（Data Transfer Object，数据传输对象）
**你的解释模板**：DTO 是在层与层之间传递数据的容器。在我们项目里，`CreateShopRequest` 是 DTO，它接收前端发来的「创建门店」的 JSON，里面有 `@NotBlank` 等校验注解，用于限制格式。

### VO（View Object，视图对象）
**你的解释模板**：VO 是专门给前端返回的对象，只包含前端需要的字段，敏感字段不放进来。在我们项目里，`ShopVO` 里 shopId 是 String 类型（不是 Long），因为 JS 处理不了超大 Long，这是有意识的设计决定。

### Entity（实体）
**你的解释模板**：Entity 和数据库表一一对应，用于 Mapper 层的 SQL 操作。在我们项目里，`Shop.java` 里有 `@TableLogic` 注解的 deleted 字段，这个字段普通查询时被自动过滤，用户感知不到。

### ThreadLocal
**你的解释模板**：ThreadLocal 是每个线程自己的私有变量，线程之间互不干扰。在我们项目里，`UserContext` 用 ThreadLocal 存当前请求的用户信息，拦截器写入、Service 随时取用、请求结束后必须 clear，否则线程池复用时会带入上个请求的用户身份。

### 拦截器（Interceptor）
**你的解释模板**：拦截器在请求到达 Controller 之前和之后执行额外逻辑。在我们项目里，`AuthInterceptor` 在 preHandle 里做 Token 校验和用户信息注入，在 afterCompletion 里做 ThreadLocal 清理。

### 逻辑删除（Logical Delete）
**你的解释模板**：逻辑删除不是真正把数据从数据库删掉，而是把 deleted 字段设为 1，查询时自动过滤 deleted=1 的记录。在我们项目里，用 MyBatis-Plus 的 `@TableLogic` 注解实现，deleteById 会被改写为 `UPDATE SET deleted = 1`，数据保留用于分析。

### 幂等（Idempotent）
**你的解释模板**：幂等是指同一个操作执行多次，结果和执行一次相同。在我们项目里，发布验证码后 60 秒内再发，会直接拒绝（不是报错而是友好提示）；点赞已点过的笔记，直接返回成功而不是报错，这都是幂等设计。

### 雪花 ID（Snowflake ID）
**你的解释模板**：雪花 ID 是一种分布式唯一 ID 生成算法，生成的是 64 位 Long 整数，趋势递增（按时间）、全局唯一。在我们项目里，所有主键用 MyBatis-Plus 的 `ASSIGN_ID` 策略，但这个 ID 返回给前端时要转成 String，因为 JS 的 Number 类型最大只支持 2^53-1，雪花 ID 可能超过这个范围导致精度丢失。

### 状态机（State Machine）
**你的解释模板**：状态机定义了对象状态的合法流转路径，阻止非法状态转换。在我们项目里，门店有 DRAFT→ONLINE↔OFFLINE→CLOSED 的状态机，代码里用 if 判断当前状态是否合法，非法流转抛 SHOP_STATUS_ILLEGAL，不是靠数据库约束而是在 Service 层保护。

### N+1 查询问题
**你的解释模板**：N+1 是指查询 N 条主数据时，又对每条主数据单独发了 1 次关联查询，共 N+1 次 SQL。在我们项目里，查笔记列表需要展示作者信息，我们用 `selectBatchIds` 一次查所有作者，再转成 Map 按 ID 索引，把 N+1 次查询变成 2 次。

---

## 10. 链路复述练习题：面试前必做

> 方法：关掉代码，试着用口语讲出来，再去对照代码检查有没有遗漏。

### 练习题 1：完整讲清手机号验证码登录的链路

提示词：前端发什么请求？→ Controller 调了什么？→ Service 做了哪几件事？→ Redis 里发生了什么变化？→ 数据库发生了什么变化？→ 前端拿到什么？

**答案检查点**（对照 [AuthService.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/module/auth/service/AuthService.java)）：
- [ ] 验证码对比，比完即删（一次性，防重放）
- [ ] 手机号不存在则自动注册（手机号即账户）
- [ ] 检查用户状态（DISABLED 拒绝）
- [ ] 生成 UUID Token，存入 Redis，TTL 7 天
- [ ] 返回 token + userId + nickname

---

### 练习题 2：讲清一次需要鉴权的请求（比如「创建门店」）

提示词：Token 在哪？→ 拦截器做了什么？→ Controller 怎么知道当前用户是谁？→ Service 里做了哪两层校验？

**答案检查点**（对照 [AuthInterceptor.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/common/interceptor/AuthInterceptor.java) 和 [ShopService.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/module/shop/service/ShopService.java)）：
- [ ] Header: Authorization: Bearer {token}
- [ ] 拦截器从 Redis 取用户信息，存入 UserContext（ThreadLocal）
- [ ] Service 通过 UserContext.getUserId() 取当前用户
- [ ] 第一层：requireApprovedMerchant 校验商家身份
- [ ] 门店创建后状态是 DRAFT，不对 C 端可见

---

### 练习题 3：讲清点赞笔记的 Redis 操作

提示词：用到了哪两个 Redis Key？→ 点赞时 Redis 发生什么变化？→ 取消点赞时呢？→ 查「我有没有点赞」用的哪个命令？→ Redis 挂了怎么办？

**答案检查点**（对照 [PostService.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/module/post/service/PostService.java)）：
- [ ] 两个 Key：count（String）和 users（Set）
- [ ] 点赞：SADD users + INCR count
- [ ] 幂等：先 SISMEMBER 检查，已点赞直接返回
- [ ] Redis 降级：try-catch，失败时用 DB 快照值

---

### 练习题 4：讲清共同关注的计算原理

提示词：用的什么 Redis 数据结构？→ 为什么不用普通 Set？→ ZINTERSTORE 是什么操作？→ 临时 Key 为什么要删掉？

**答案检查点**（对照 [FollowService.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/module/follow/service/FollowService.java)）：
- [ ] ZSet，Score 是关注时间戳
- [ ] ZSet 比 Set 多了排序能力（按关注时间展示）
- [ ] ZINTERSTORE 把两个 ZSet 取交集，写入临时 Key
- [ ] 用完删临时 Key，防止垃圾数据积累
- [ ] 冷启动：Redis 没数据时从 DB 加载

---

### 练习题 5：讲清门店上线/下线的状态机保护

提示词：门店有哪几个状态？→ 合法流转路径是什么？→ 代码在哪里做保护？→ 权限校验分几层？

**答案检查点**（对照 [ShopService.java](../local-life-server/src/main/java/com/personalprojections/locallife/server/module/shop/service/ShopService.java)）：
- [ ] DRAFT → ONLINE ↔ OFFLINE → CLOSED
- [ ] CLOSED 是终态，不可再次上线
- [ ] requireOwnShop 同时校验商家身份 + 门店归属
- [ ] 不区分「不存在」和「无权限」→ 统一 SHOP_FORBIDDEN（防枚举攻击）

---

## 第六章：订单与支付模块

> **本章主线**：用户用优惠券在门店下单，到支付完成的完整链路。
> 这是技术含量最高的一章——涉及订单状态机、支付单与订单分离设计、支付回调幂等、延迟关单等经典面试话题。

---

### 6.1 为什么订单（order_info）和支付单（payment_order）要分开两张表

很多人第一次做项目，会把订单和支付放在一张表里（`is_paid = true/false`）。这个问题在面试里一定被问到：**为什么分两张表？**

**一笔订单可能对应多次支付行为：**

```
用户下单 → order_info 创建（WAIT_PAY）
    ↓ 点「去支付」
payment_order #1 创建（PENDING）← 第一次发起支付
    ↓ 用户支付宝没钱，超时
payment_order #1（FAILED）
    ↓ 用户充了钱，重新点「去支付」
payment_order #2 创建（PENDING）← 第二次发起支付
    ↓ 支付成功，渠道回调
payment_order #2（SUCCESS）
order_info（PAID）             ← 订单才变为「已支付」
```

如果合并在一张表：「支付失败后重新支付」的历史就丢失了。分开后，每次支付行为都有完整记录，方便对账和排查问题。

---

### 6.2 下单流程详解（createOrder）

```
POST /api/v1/orders
Authorization: Bearer {token}
X-Idempotency-Key: {UUID}（可选，防双击）

{
  "shopId": "1234567890",
  "userCouponId": "9876543210",   // 不用券时省略
  "remark": "不要辣"
}
```

服务端处理步骤（对应 OrderService.createOrder 的 7 个步骤）：

**Step 1：幂等检查（防双击）**

用户快速点两次「确认下单」，或网络抖动导致前端重试，可能发两条一模一样的请求。
前端每次下单时生成一个 UUID（`X-Idempotency-Key`），重试时带同一个 Key。
服务端查 Redis：`idempotent:order:{key}` → 已有 orderNo → 直接返回原订单（不重复创建）。
5 分钟 TTL，超时后认为是新请求。

```
第1次请求: Key=abc → Redis 没有 → 创建订单 → Redis 写 abc=ORD001 → 返回 ORD001
第2次请求: Key=abc → Redis 有 abc=ORD001 → 直接返回 ORD001（幂等）
```

**Step 2：校验门店**

```java
// 门店必须存在且 status = ONLINE 才能下单
if (!"ONLINE".equals(shop.getStatus())) {
    throw new BizException(ErrorCode.SHOP_NOT_ONLINE);
}
```

**Step 3：校验优惠券（5个子检查）**

```
a. 券属于当前用户（防跨用户使用他人券）
b. 状态 = UNUSED（已使用 or 已过期的券不能用）
c. 未超过 expire_at（即使状态是 UNUSED，也可能已过期）
d. 券模板 status = ACTIVE（模板被停用了也不能用）
e. 订单金额 ≥ 券的最低使用门槛 minOrderAmount
```

统一返回 `ORDER_COUPON_INVALID`，不区分哪种原因（防信息泄露）。
门槛不足时返回 `ORDER_COUPON_BELOW_MIN_AMOUNT`（前端可以显示具体提示）。

**Step 4：计算金额（三个字段都由服务端算，客户端不传金额）**

```
originalAmount = shop.price（门店价格，单位：分）
couponDiscount = CASH 券：直接取 discountValue（分）
                 PERCENT 券：originalAmount × (1 - discountValue/100)
orderAmount    = max(originalAmount - couponDiscount, 0)
```

为什么不让客户端传金额？**安全性**——客户端传的金额不可信，可能被抓包篡改。

**Step 5：核销券（与创建订单在同一个 @Transactional）**

```java
// WHERE couponStatus = 'UNUSED'：并发保护，只核销 UNUSED 的券
int updated = userCouponMapper.update(...WHERE status='UNUSED');
if (updated == 0) {
    throw new BizException(ErrorCode.ORDER_COUPON_INVALID); // 并发下被抢先核销
}
```

**关键点**：先核销券，再创建订单，都在同一个事务里。
如果创建订单失败 → 事务回滚 → 券状态也回滚到 UNUSED。
这避免了「券被核销但订单没创建成功」的悬空状态。

**Step 6：INSERT order_info**

```java
OrderInfo order = OrderInfo.builder()
    .userId(userId)
    .shopId(shopId)
    .orderStatus("WAIT_PAY")
    .expireAt(now.plusMinutes(30))  // 30分钟后过期
    // ...
    .build();
orderInfoMapper.insert(order);
// @TableId(ASSIGN_ID) 自动填充雪花 ID 到 order.id
String orderNo = String.valueOf(order.getId()); // 用 id 作为业务单号
```

**Step 7：写幂等 Key**

```
Redis SET idempotent:order:{key} = orderNo, EX 300
```

---

### 6.3 发起支付（createPayment）

```
POST /api/v1/payments
Authorization: Bearer {token}

{ "orderId": "1234567890", "channel": "MOCK" }
```

处理流程：

```
1. 校验订单：存在 + 属于当前用户 + status = WAIT_PAY
2. 创建 payment_order（PENDING）
3. 生成 paymentNo = 雪花 ID（作为「商户订单号」传给渠道）
4. 生成 payUrl：
   MOCK → "/api/v1/payments/mock-pay?paymentNo=xxx"（测试用）
   Alipay → 调用支付宝 SDK，返回收银台 URL
5. 返回 PaymentVO { paymentNo, orderNo, payAmount, channel, payUrl }
```

前端拿到 `payUrl` 后，根据 `channel` 决定如何处理：
- MOCK：直接访问 payUrl（GET 请求）→ 触发支付成功
- Alipay：跳转到支付宝收银台
- Wechat：调用微信 JSAPI 或展示二维码

---

### 6.4 支付回调处理（handleCallback）——面试必讲

**支付回调是什么**：用户在支付宝/微信完成付款后，这些平台的服务器会向我们的服务器发送一个 POST 请求，告知「某笔支付成功了」。这个机制叫**回调（Callback）**，也叫**通知（Notify）**。

**为什么回调接口不需要用户登录？**

因为不是用户在调用，是支付宝/微信的服务器在调用。它们没有用户的 Token。
所以这个接口在 `WebMvcConfig` 白名单中跳过了 JWT 鉴权，
改用**验签（Sign Verification）**来确认请求来自合法渠道。

**验签逻辑**：
```
支付宝用私钥 → 签名参数 → 生成 sign 字符串
我们用支付宝公钥 → 重新验证 sign → 签名匹配 = 合法请求
Mock 渠道 → sign 固定 "mock-sign" → 直接字符串比对
```

**五步核心处理**：

```
Step 1: 根据 paymentNo 查 payment_order（渠道回调会携带我们当初给的「商户订单号」）
Step 2: 验签（防伪造回调）
Step 3: 金额核对（paidAmount == payment_order.payAmount，防篡改金额攻击）
Step 4: 幂等更新支付单（WHERE pay_status='PENDING'，防重复处理）
Step 5: 同步更新 order_info 为 PAID（WHERE order_status='WAIT_PAY'，同样防重复）
```

**Step 4 的幂等机制（面试核心）**：

```sql
-- 这条 SQL 是防重复回调的关键
UPDATE payment_order
SET pay_status = 'SUCCESS', trade_no = ?, paid_amount = ?, ...
WHERE id = ? AND pay_status = 'PENDING'  -- 关键：只处理 PENDING 状态
```

```
第1次回调（正常）: status=PENDING → UPDATE 成功，affected=1 → 继续处理
第2次回调（重复）: status=SUCCESS → UPDATE affected=0 → 直接 return，幂等跳过
```

**Step 3 的金额核对（防篡改）**：

假设黑客伪造了一个回调，把 `paidAmount` 从 9900 改成 1（只付了1分钱）。
服务端拿 `paidAmount`（1）和 `payment_order.payAmount`（9900）比对：
```java
if (!callback.getPaidAmount().equals(paymentOrder.getPayAmount())) {
    throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH); // 拒绝！
}
```

---

### 6.5 延迟关单（closeExpiredOrders）

**问题**：下单后 30 分钟不支付，订单应该自动关闭（释放资源，让用户重新操作）。
但 HTTP 接口是「请求-响应」模式，不能自己定时触发。

**当前方案：定时任务轮询**

```java
@Scheduled(fixedDelay = 60_000)  // 每 60 秒执行一次
public void closeExpiredOrders() {
    // 查所有 WAIT_PAY 且 expire_at < NOW() 的订单
    List<OrderInfo> expiredOrders = orderInfoMapper.selectList(
        WHERE order_status='WAIT_PAY' AND expire_at < NOW() LIMIT 200
    );
    for (OrderInfo order : expiredOrders) {
        // 原子更新，WHERE status='WAIT_PAY'，幂等
        orderInfoMapper.updateStatusFromWaitPay(order.getId(), "CANCELLED");
        // 如有券，回退券状态 USED → UNUSED
        if (order.getUserCouponId() != null) { 回退券; }
    }
}
```

**需要 `@EnableScheduling` 在启动类上才会生效！**（LocalLifeServerApplication 已添加）

**升级路径（面试亮点）**：

当前定时轮询的精度是 1 分钟（扫描间隔）。
更优方案：**RocketMQ 延时消息**
- 下单时投递一条「30分钟后投递」的延时消息
- 消费时检查订单状态：若已 PAID 忽略，若仍 WAIT_PAY 则关闭
- 好处：精度到秒，不依赖轮询，数据库压力从「每分钟全表扫」→「事件驱动」

---

### 6.6 取消订单（cancelOrder）

用户主动取消时的并发安全：

```java
// WHERE status='WAIT_PAY'：原子更新，防止并发下重复取消
int affected = orderInfoMapper.updateStatusFromWaitPay(orderId, "CANCELLED");
if (affected == 0) {
    // 订单已非 WAIT_PAY：可能已支付或已被关闭
    throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
}
// 回退优惠券（如有），让用户可以下次重用
if (order.getUserCouponId() != null) {
    userCouponMapper.update(... SET couponStatus='UNUSED' WHERE id=? AND status='USED');
}
```

**为什么 UPDATE WHERE status='WAIT_PAY' 可以防并发？**

数据库 UPDATE 是原子操作。两个请求同时调用 cancelOrder：
- 请求 A：WHERE status='WAIT_PAY' → 命中 → affected=1 → 更新为 CANCELLED
- 请求 B：WHERE status='WAIT_PAY' → 已被 A 改为 CANCELLED → 不命中 → affected=0 → 抛异常

---

### 6.7 关键词卡片（订单支付模块）

| 关键词 | 一句话解释 |
|--------|-----------|
| **订单/支付单分离** | 一个 order_info 对应多条 payment_order，支持失败重试，历史完整保留 |
| **支付回调** | 渠道服务器主动通知我方「支付成功」，不是用户调用 |
| **验签** | 用渠道公钥校验签名，防止伪造回调 |
| **幂等回调** | `UPDATE WHERE status='PENDING'`，重复回调 affected=0 直接跳过 |
| **金额核对** | paidAmount == 应付金额，防止篡改金额攻击 |
| **延迟关单** | 定时任务每分钟扫描过期未支付订单批量关闭 |
| **券核销事务** | 核销券 + 创建订单在同一 @Transactional，失败回滚，无悬空状态 |
| **下单幂等** | X-Idempotency-Key + Redis SETNX，防双击创建重复订单 |
| **状态机保护** | `UPDATE WHERE status='WAIT_PAY'` 实现原子状态流转 + 并发安全 |
| **Mock 渠道** | 内部测试接口，访问 mock-pay URL = 模拟支付宝通知 |

---

### 6.8 完整链路复述练习题

**题目：从用户点击「下单」到订单变为「已支付」，完整说一遍链路**

> 参考答案：
> 1. 用户点「确认下单」→ POST /orders，前端可附 X-Idempotency-Key 防双击
> 2. 服务端校验门店 ONLINE + 券合法（属于我、UNUSED、未过期、满足门槛）
> 3. 服务端计算 originalAmount（门店价）、couponDiscount、orderAmount
> 4. 在一个事务内：先把券标记 USED，再 INSERT order_info（WAIT_PAY）
> 5. 用户点「去支付」→ POST /payments，创建 payment_order（PENDING），返回 payUrl
> 6. 用户在支付宝完成付款 → 支付宝向我方 POST /payments/callback
> 7. 回调处理：验签 → 金额核对 → `UPDATE payment_order WHERE status='PENDING'` → `UPDATE order_info WHERE status='WAIT_PAY'`
> 8. 前端轮询 GET /orders/{id}，看到 orderStatus = PAID，展示「支付成功」

---

## 附录：面试高频追问和推荐回答

### Q：你们项目的接口是 RESTful 风格吗？

> 是的，我们遵循 RESTful 风格：资源名用复数小写（/shops、/posts），HTTP 方法表达操作语义（GET 查询、POST 创建、PUT 全量更新、DELETE 删除），版本号放路径前缀（/api/v1/）。状态变更接口用 `/shops/{id}/status/online` 这种子资源风格，表达「把门店状态设置为 online」。

### Q：你们为什么不用 404 状态码？

> 我们只用 6 个 HTTP 状态码（200、400、401、403、429、500），资源不存在统一走 400 + 业务错误码（如 SHOP_NOT_FOUND）。这样做的好处是前端只需要判断响应体里的 code 字段，不用再分支判断 HTTP 状态码，减少了前端的开发复杂度。

### Q：雪花 ID 的主键为什么要转成 String 返回给前端？

> 雪花 ID 是 64 位 Long 整数，可能超过 JavaScript Number 类型的安全整数范围（2^53-1 ≈ 9 千万亿）。如果直接传 Long，JSON 序列化后在浏览器 JS 里解析时可能出现精度丢失，导致最后几位变 0，ID 错乱。转成 String 就不存在这个问题。

### Q：ThreadLocal 在你们项目里有没有可能产生内存泄漏？

> 有可能。如果在 afterCompletion 里没有 clear，线程池中的线程处理完一个请求后，ThreadLocal 里的引用不会被 GC 回收（因为 ThreadLocalMap 持有弱引用 key 但强引用 value），在长时间运行的服务器进程中可能累积。我们在 AuthInterceptor.afterCompletion 里强制调用 UserContext.clear()，这是防止内存泄漏和信息串流的双重保障。

### Q：发布笔记的限流和验证码限流有什么区别？

> 验证码限流用了两层（60秒维度 + 1小时总量），用计数器实现。笔记发布限流只用了单层（60秒 1 篇），用 SETNX 实现，更简单。区别在于验证码是安全敏感操作，需要更严格的多维度限制；笔记发布是内容操作，频率适当限制即可。更细粒度的限流（滑动窗口计数）后续可以用 Lua 脚本实现。

---

## 第七章：消息可靠性——Transactional Outbox + RocketMQ

> **本章主线**：支付成功后如何保证消息不丢，同时保证下游不重复处理。
> 这是分布式系统中「最终一致性」的经典实现，面试必考。

---

### 7.1 问题：为什么不能直接在支付成功后发 MQ？

```java
// ❌ 危险的做法（不要这样写）
@Transactional
public void handleCallback(PaymentCallbackRequest callback) {
    // Step 1: 更新数据库
    paymentOrderMapper.updateStatusOnSuccess(...);
    orderService.markOrderAsPaid(...);
    // 数据库事务提交 ↑

    // Step 2: 发 MQ
    rocketMQTemplate.send("payment-success-topic", event); // ← 如果这里失败？
}
```

**问题场景**：
```
1. 数据库事务提交成功（payment_order = PAID，order_info = PAID）
2. rocketMQTemplate.send 网络超时 → 抛异常
3. MQ 消息丢失！下游永远收不到「支付成功」通知
4. 用户明明付了钱，但短信没发、统计没更新、积分没发放
```

**根本原因**：数据库事务和 MQ 发送是**两个独立的操作**，无法合并成一个原子操作。

---

### 7.2 解决方案：Transactional Outbox Pattern（事务性发件箱）

**核心思路**：把「发 MQ」变成「写数据库」，利用数据库的事务原子性保证消息一定被记录。

```
原来的问题：DB 提交成功 → MQ 发送失败 → 消息丢失

Outbox 方案：
  BEGIN 同一事务
    UPDATE payment_order → PAID
    UPDATE order_info    → PAID
    INSERT outbox_message (PENDING)  ← 和业务数据同一事务！
  COMMIT
```

只要事务提交成功，`outbox_message` 一定在数据库里。
然后由独立的 **Relay 任务**（定时扫描）异步投递到 RocketMQ：

```
每 10 秒：
  SELECT * FROM outbox_message WHERE status='PENDING' AND next_retry_at <= NOW()
  rocketMQTemplate.syncSend(topic, payload)
  UPDATE outbox_message SET status='SENT'
```

**保证**：消息要么在事务里一起回滚（不写），要么写入后 Relay 任务最终投递出去（不丢）。

---

### 7.3 outbox_message 表设计

```sql
CREATE TABLE outbox_message (
    id          BIGINT UNSIGNED  NOT NULL,   -- 雪花 ID
    event_id    VARCHAR(64)      NOT NULL,   -- 业务事件唯一 ID（消费者幂等用）
    topic       VARCHAR(64)      NOT NULL,   -- RocketMQ Topic
    tag         VARCHAR(64)      NOT NULL,   -- RocketMQ Tag
    payload     TEXT             NOT NULL,   -- 消息体 JSON
    status      VARCHAR(16)      NOT NULL,   -- PENDING / SENT / FAILED
    retry_count TINYINT UNSIGNED NOT NULL,   -- 已重试次数
    next_retry_at DATETIME       NOT NULL,   -- 下次重试时间（指数退避）
    ...
    UNIQUE KEY uk_event_id (event_id),       -- 防重复写入
    KEY idx_outbox_status_retry (status, next_retry_at)  -- Relay 扫描索引
)
```

**关键字段**：
- `event_id`：全局唯一，消费者用它做幂等判重（`{paymentOrderId}_paid`）
- `status`：PENDING → SENT（正常）or PENDING → FAILED（超限）
- `retry_count + next_retry_at`：支持指数退避重试

---

### 7.4 指数退避重试（Exponential Backoff）

```
第 1 次失败 → 10 秒后重试
第 2 次失败 → 30 秒后重试
第 3 次失败 → 60 秒后重试
第 4 次（retryCount ≥ 3） → status = FAILED，停止重试
```

为什么用指数退避而不是固定间隔？
- 固定 10s 重试：MQ 宕机 30 分钟，发起了 180 次无效请求，白白消耗资源
- 指数退避：随着失败次数增加，等待时间拉长，减少对故障 MQ 的冲击

FAILED 后运维监控告警，人工处理（重置为 PENDING 重新投递，或补偿脚本）。

---

### 7.5 消费者幂等（防重复消费）

Relay 任务保证「至少一次投递（AT LEAST ONCE）」，同一条消息可能被投递多次。
消费者必须保证**幂等**。

**当前方案：Redis SETNX**

```java
// 消费时先判重
String idempotentKey = "consume:payment_success:" + event.getEventId();
Boolean isNew = redis.setIfAbsent(idempotentKey, "1", 24小时);

if (!isNew) {
    log.info("幂等跳过：已消费过 eventId={}", event.getEventId());
    return; // 直接 ACK，不重复处理
}

// 执行业务逻辑（记录日志、发通知等）
processPaymentSuccess(event);
```

**为什么用 Redis 而不是 DB 唯一索引？**

| 方案 | 优点 | 缺点 |
|------|------|------|
| Redis SETNX | 内存操作，性能高（微秒级） | Redis 宕机时失效（极低概率） |
| DB 唯一索引 | 持久化，绝对可靠 | 每次消费都要 INSERT，有写入压力 |

业务逻辑本身幂等（重复记录日志无副作用）时，Redis 方案已足够。

---

### 7.6 完整链路（加入 MQ 后）

```
支付回调
    ↓
PaymentService.handleCallback（@Transactional）
    ├── UPDATE payment_order → PAID
    ├── UPDATE order_info    → PAID
    └── INSERT outbox_message (PENDING) ← 同一事务
         ↓（事务提交）
         [Relay 任务，每 10s 扫描]
         ↓
    rocketMQTemplate.syncSend(payment-success-topic)
         ↓
    UPDATE outbox_message → SENT
         ↓
    [PaymentSuccessConsumer 消费]
         ├── Redis SETNX 判重（幂等）
         ├── processPaymentSuccess（记日志/发通知等）
         └── ACK
```

若 Relay 投递失败 → 指数退避重试 → 超限 FAILED → 运维告警人工补偿。
若消费者处理失败 → 抛异常 → RocketMQ 自动重试（最多 16 次）→ 超限进死信。

---

### 7.7 死信队列（DLQ）

RocketMQ 消费者默认重试 **16 次**，每次间隔递增（10s、30s、1min、2min...）。
16 次全失败后，消息进入死信 Topic：`%DLQ%{消费者组}`。

```
死信 Topic：%DLQ%payment-success-consumer-group
```

处理死信的方案：
1. **监控告警**：死信 Topic 有消息 → 触发告警
2. **人工重放**：排查问题后，从死信 Topic 重新投递
3. **自动补偿**：专门的死信消费者，执行降级逻辑（如只记录日志，不重试完整流程）

---

### 7.8 关键词卡片（消息可靠性模块）

| 关键词 | 一句话解释 |
|--------|-----------|
| **Transactional Outbox** | 把「发 MQ」变成「写 DB」，利用 DB 事务原子性保证消息不丢 |
| **Relay 任务** | 定时扫描 outbox_message，将 PENDING 消息投递到 MQ |
| **AT LEAST ONCE** | 至少投递一次，可能重复，下游必须幂等 |
| **指数退避** | 失败后等待时间指数增长，减少对故障系统的冲击 |
| **消费者幂等** | Redis SETNX 判重，防止重复消费同一条消息 |
| **死信队列 DLQ** | 重试超限后的消息最终去处，需人工介入 |
| **eventId** | 消费者幂等判重的全局唯一 Key（`{paymentOrderId}_paid`） |
| **syncSend** | 同步发送，Broker 确认收到才返回，保证 Relay 任务知道是否成功 |

---

### 7.9 面试复述练习

**题目：支付成功后如何保证消息不丢、下游不重复处理？**

> 参考答案：
>
> 消息不丢用 **Transactional Outbox**：
> 支付回调处理中，更新 payment_order、order_info 和写 outbox_message 在**同一个事务**里。
> 事务提交后，独立的 Relay 任务每 10 秒扫描 PENDING 消息投递到 RocketMQ。
> 失败则指数退避重试，超限标 FAILED 触发告警。
>
> 不重复处理用**消费者幂等**：
> 每条消息携带全局唯一 eventId，消费时 Redis SETNX 判重。
> 已处理过则直接 ACK 跳过，保证同一事件只处理一次。
>
> 这套方案保证：**AT LEAST ONCE 投递 + 消费者幂等 = EXACTLY ONCE 效果**。

---

---

## 第 8 章：Elasticsearch 全文搜索——门店和笔记怎么搜出来的

### 8.1 为什么 MySQL LIKE 不够用？

面试官常常问：「你的搜索功能是怎么实现的？为什么用 ES？」

先理解 MySQL LIKE 的问题：

```sql
-- 用户搜索「饺子」，MySQL 的做法：
SELECT * FROM shop WHERE shop_name LIKE '%饺子%' AND status = 'ONLINE';
```

这有三个致命问题：

1. **全表扫描**：`%饺子%` 两端都是 `%`，索引失效，数据多了就卡死
2. **中文分词差**：MySQL 按字符匹配，搜「饺子」不会匹配「小明饺子馆」里的「饺」和「子」两个字
3. **无相关性排序**：所有结果同等对待，「饺子专卖店」和「偶尔卖过一次饺子的店」排名一样

Elasticsearch 的解法：
- **倒排索引**：先把文档切成词，建立「词 → 文档 ID 列表」的映射，搜索时直接查词表，O(1) 查找
- **IK 中文分词**：「小明饺子馆」→「小明」「饺子馆」，搜「饺子」能精准匹配「饺子馆」
- **相关性评分（_score）**：词频、文档频率等因素综合计算，相关的排前面

---

### 8.2 核心概念：什么是索引、文档、字段映射？

ES 的概念对应 MySQL 的概念：

| ES 概念 | MySQL 对应 | 说明 |
|---------|-----------|------|
| Index（索引） | Table（表） | `shop_index` 对应 `shop` 表 |
| Document（文档） | Row（行） | 一个门店 = 一个文档 |
| Field（字段） | Column（列） | `shopName`、`location` 等 |
| Mapping（映射） | Schema（表结构） | 定义每个字段的类型 |

**重要区别**：MySQL 是主数据，ES 是搜索索引（派生数据）。

```
MySQL shop 表        ←   业务写入主数据
       ↓
ES shop_index        ←   双写同步（当前方案）
                     ←   Canal Binlog 方案（后续增强）
```

ES 文档里只存**搜索需要的字段**，不是 MySQL 表的全量拷贝。

---

### 8.3 ShopDocument 的字段设计——每个字段用什么类型，为什么？

```java
@Document(indexName = "shop_index")
@Setting(settingPath = "es/shop-index-settings.json")
public class ShopDocument {
    @Id
    private String id;  // 雪花 Long → String，ES 的文档 ID 是字符串

    @MultiField(
        mainField = @Field(type = Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
        otherFields = { @InnerField(suffix = "raw", type = Keyword) }
    )
    private String shopName;  // 双字段：搜索用 text，聚合用 keyword.raw

    @GeoPointField
    private String location;  // "纬度,经度" 格式，支持 geo_distance 查询

    @Field(type = Double)
    private Double score;  // 评分，用于 function_score 排序

    @Field(type = Keyword)
    private String status;  // 过滤用，不分词，term query
}
```

**字段类型选择原则：**

| 类型 | 适用场景 | 示例 |
|------|---------|------|
| `Text` + IK分词 | 全文搜索（模糊匹配） | shopName、description、content |
| `Keyword` | 精确匹配、过滤、聚合 | status、categoryId、userId |
| `GeoPoint` | 地理位置查询 | location（"lat,lon"） |
| `Double/Integer` | 数值范围过滤、排序 | score、price、likeCount |
| `Date` | 时间范围过滤、时间排序 | createdAt、syncAt |

**双字段策略（shopName 为什么要 `@MultiField`）：**

同一字段既要全文搜索，又要聚合统计，两种需求对应不同的存储方式：

```
shopName        → text（IK 分词后存倒排索引，全文搜索用）
shopName.raw    → keyword（原始字符串，精确匹配 / 聚合用）
```

搜索时用 `shopName` 字段，排行榜聚合时用 `shopName.raw` 字段。

---

### 8.4 IK 分词器——中文搜索的核心

**标准分析器（ES 默认）** 对中文一字一切：

```
「小明饺子馆」→ 「小」「明」「饺」「子」「馆」
搜「饺子」→ 不能准确匹配
```

**IK 分词器** 按词语切分：

```
ik_max_word（索引时，细粒度）：
「小明饺子馆」→ 「小明」「饺子馆」「饺子」「子馆」（切出所有可能的词）

ik_smart（搜索时，粗粒度）：
「小明饺子馆」→ 「小明」「饺子馆」（切出最合理的词）
```

**为什么索引和搜索用不同的 IK 模式？**

- 索引用 `ik_max_word`：切词细，召回率高（能搜到更多相关文档）
- 搜索用 `ik_smart`：切词准，精确率高（减少误召回）

这是常见的「宽进严出」策略：**索引宽**（存更多词，不遗漏），**搜索严**（精准切词，不误匹配）。

---

### 8.5 门店搜索 Query 构建流程

接口：`GET /api/v1/search/shops?keyword=饺子&lat=30.27&lon=120.15&distanceKm=3`

`ShopSearchService.searchShops()` 内部构建 ES 查询的 5 个步骤：

```
Step 1  必须：filter status = ONLINE
        ↓ 只搜上线门店，下线门店永远不出现
Step 2  可选：filter categoryId = X
        ↓ 分类过滤（用 filter 不影响相关性评分，结果可缓存）
Step 3  可选：range filter price in [min, max]
        ↓ 价格区间过滤
Step 4  可选：geo_distance filter location within 3km of (30.27, 120.15)
        ↓ 地理位置过滤（"3km" 字符串格式，Criteria.within(GeoPoint, "3km")）
Step 5  可选：multi_match keyword on [shopName, description, address]
        ↓ 关键词全文搜索（OR 语义：有一个字段匹配就算命中）
```

**filter vs query 的区别：**

- `filter`：不计算相关性分数，结果可以缓存（适合固定条件，如 status=ONLINE）
- `query`：计算相关性分数（_score），不缓存（适合全文搜索关键词）

CriteriaQuery 会自动把 `.is()`、`.within()` 等条件转为 filter，把 `.matches()` 转为 query。

---

### 8.6 地理位置搜索——附近 3km 的门店

这是 ES 的 `geo_distance` 查询，MySQL 实现起来非常麻烦（需要数学公式），ES 原生支持。

**数据存储**：ShopDocument 的 `location` 字段是 `@GeoPointField`，存储格式：

```
location = "30.273820,120.153559"  // "纬度,经度"（注意是纬度在前）
```

**查询方式**：

```java
GeoPoint center = new GeoPoint(30.27, 120.15);
criteria.and(new Criteria("location").within(center, "3km"));
```

ES 内部执行：找出所有 location 字段与 center 点的球面距离 ≤ 3km 的文档。

**距离值返回**：ES 在 sort by geo 时会把距离值放在 hit 的 `sortValues` 里，直接读取，不用应用层重新计算。

**面试常问：GeoPoint 字符串里纬度和经度谁在前？**

答：ES GeoPoint 字符串格式是 `"lat,lon"`（纬度在前，经度在后）。这和地图 API 常见的 `lon,lat` 顺序相反，容易踩坑。我们在 `shopToDocument()` 方法里做了正确处理：`latitude + "," + longitude`。

---

### 8.7 双写架构——MySQL 写完怎么同步到 ES？

**当前实现（双写）：**

```
用户调用创建/更新/上线/下线门店接口
         ↓
ShopService.createShop()    ← 写 MySQL（主数据）
         ↓
ShopSearchService.syncShop()   ← 写 ES（搜索索引）
         ↓
shopSearchRepository.save(doc) ← Spring Data ES
```

**双写的问题：**
- MySQL 写成功、ES 写失败 → 数据不一致（短暂窗口内搜索不到新门店）
- ES 是搜索系统，短暂不一致是可以接受的（最终一致性）
- 如果需要强一致，可以将 MySQL 和 ES 写操作放在同一 `@Transactional` 里，但 ES 不支持分布式事务

**后续 Canal 方案（增强项）：**

```
MySQL binlog
    ↓ Canal（监听 binlog 变更）
    ↓ 解析 INSERT/UPDATE/DELETE 事件
RocketMQ（shop-sync-topic）
    ↓ ES 同步消费者
ES shop_index（更新文档）
```

Canal 方案解耦了 ShopService 和 ES 同步逻辑，ShopService 不再需要知道 ES 的存在。

---

### 8.8 笔记搜索的特殊点——冗余字段与排序策略

**冗余 shopName 字段：**

PostDocument 里存了 `shopName`，这是从 MySQL `shop.shop_name` 冗余过来的字段。

为什么需要冗余？

```
ES 的文档是非关系型，不支持 JOIN。
用户搜「小明饺子馆」时，需要在笔记的门店名中也能匹配，
如果没有冗余字段，搜索结果只能匹配笔记 title 和 content。
```

冗余的代价：门店改名时，需要同步更新所有关联该门店的笔记文档的 shopName 字段（Canal 方案自动处理）。

**三种排序策略：**

| sortBy | ES 排序方式 | 适用场景 |
|--------|-----------|---------|
| `relevance`（默认） | 按 _score 降序（ES 默认） | 搜关键词时，最相关的排前面 |
| `latest` | 按 createdAt 降序 | 看最新笔记 |
| `popular` | 按 likeCount 降序 | 看最热笔记 |

---

### 8.9 关键词卡片（Elasticsearch 搜索模块）

| 关键词 | 一句话解释 |
|--------|-----------|
| **倒排索引** | ES 的核心数据结构：词 → 文档 ID 列表，搜索时直接查词表而非全表扫描 |
| **IK 分词器** | 中文分词插件：「小明饺子馆」→「小明」「饺子馆」，搜「饺子」能匹配 |
| **ik_max_word vs ik_smart** | 索引用 max_word（细分，召回率高），搜索用 smart（粗分，精确率高） |
| **Text vs Keyword** | Text 分词可全文搜索，Keyword 不分词用于精确匹配/过滤/聚合 |
| **@MultiField** | 同一字段两种存储：text 供全文搜索，keyword.raw 供聚合排序 |
| **GeoPoint** | ES 地理坐标类型，支持 geo_distance（附近 X km）查询 |
| **filter vs query** | filter 不计算评分可缓存（适合固定条件），query 计算评分（适合全文搜索） |
| **双写** | MySQL 写完立刻写 ES，简单但强一致难保证 |
| **Canal** | MySQL Binlog 监听工具，实现 MySQL → ES 的异步解耦同步 |
| **_score** | ES 相关性评分，由词频、文档频率等综合计算，数值越高越相关 |

---

### 8.10 面试复述练习

**题目：用户搜「附近 3 公里内好吃的饺子馆」，你的系统是怎么处理的？**

> 参考答案：
>
> 前端发请求：`GET /api/v1/search/shops?keyword=饺子&lat=30.27&lon=120.15&distanceKm=3`
>
> SearchController 接收参数，调用 ShopSearchService.searchShops()。
>
> Service 层动态构建 ES CriteriaQuery：
> - filter: status = ONLINE（只搜上线门店）
> - filter: geo_distance location within "3km" of (30.27, 120.15)（附近 3km）
> - query: multi_match "饺子" on [shopName, description, address]（关键词搜索）
>
> ES 执行查询：先用 filter 缩小范围（GEO + 状态），再用 query 计算相关性评分，
> 最终按 score（综合评分）排序返回。
>
> 之所以用 ES 而不用 MySQL LIKE：
> - MySQL LIKE '%饺子%' 全表扫描，性能差
> - MySQL 不支持地理距离查询（需要复杂数学公式）
> - ES 倒排索引 + IK 分词，搜「饺子」能精准匹配「饺子馆」，且性能好

---

---

## 第 9 章：可观测性——Micrometer、Prometheus、Zipkin 是怎么配合的

### 9.1 为什么需要可观测性？

一个系统上了生产，你不能靠「感觉」判断健不健康。可观测性（Observability）的核心是三件事：

| 维度 | 工具 | 能回答的问题 |
|------|------|------------|
| **Metrics（指标）** | Micrometer + Prometheus + Grafana | 接口 QPS 多少？P99 延迟多少？秒杀成功率多少？ |
| **Tracing（链路追踪）** | Micrometer Tracing + Brave + Zipkin | 这个请求经过了哪些方法？慢在哪里？ |
| **Logging（日志）** | SLF4J + MDC（traceId） | 出问题时能把同一请求的所有日志串起来 |

三者缺一不可：有 Metrics 知道「有问题」，有 Tracing 知道「哪慢」，有 Logging 知道「为什么」。

---

### 9.2 Micrometer 是什么？它和 Prometheus 是什么关系？

**类比**：Micrometer 之于监控 = SLF4J 之于日志。

- SLF4J 是日志门面，背后可以接 Logback / Log4j2
- Micrometer 是 Metrics 门面，背后可以接 Prometheus / Grafana / DataDog

**工作流程**：

```
业务代码（Counter / Timer / Gauge）
         ↓ Micrometer API
Prometheus Registry（把指标格式化为 Prometheus text）
         ↓ /actuator/prometheus 端点
Prometheus Server（每 15 秒 scrape 一次）
         ↓ PromQL 查询
Grafana（可视化仪表盘 + 告警）
```

Spring Boot Actuator 自动注册了 JVM、HTTP、数据库连接池等指标，我们只需要：
1. 在 `pom.xml` 加 `micrometer-registry-prometheus`
2. 在 `application.yml` 暴露 `/actuator/prometheus` 端点
3. 业务关键点手动埋点（BusinessMetrics）

---

### 9.3 BusinessMetrics——自定义业务指标

框架 Metrics 只能告诉你「HTTP 请求量」，业务 Metrics 告诉你「秒杀成功率」：

```java
// 秒杀尝试次数（Counter）
meterRegistry.counter("seckill.attempts", "templateId", "123").increment();

// 支付回调耗时（Timer）
meterRegistry.timer("payment.callback.duration", "channel", "MOCK")
    .record(Duration.ofMillis(costMs));
```

**Prometheus 查询示例**：

```promql
# 最近 5 分钟秒杀成功率
rate(seckill_success_total[5m]) / rate(seckill_attempts_total[5m])

# 支付回调 P99 延迟
histogram_quantile(0.99, rate(payment_callback_duration_seconds_bucket[5m]))
```

---

### 9.4 分布式链路追踪——一个请求经过多少方法？

当一个请求触发了「Controller → Service → Mapper → Redis → ES」多层调用，需要知道每层耗时。

**核心概念**：
- **Trace**：一个完整请求的全链路，有唯一 traceId
- **Span**：链路中的一个操作单元（如一次 SQL 查询），有 spanId
- **Parent Span**：调用方的 Span，子操作的 Span 的 parentSpanId 指向它

**我们的技术栈**：
```
Micrometer Tracing（门面）
    ↕ Bridge
Brave（Zipkin 生态的追踪库）
    ↕ Reporter
Zipkin Server（本地 9411 端口）
```

**TraceIdFilter 的作用**：

```java
@Order(1)  // 最先执行的 Filter
public class TraceIdFilter implements Filter {
    public void doFilter(...) {
        String traceId = tracer.currentSpan().context().traceId();
        MDC.put("traceId", traceId);          // 写入日志 MDC
        response.setHeader("X-Trace-Id", traceId);  // 写入响应头
        chain.doFilter(request, response);
        MDC.remove("traceId");                // 必须清理，线程池复用！
    }
}
```

**日志效果**：

```
2026-05-28 20:00:01 [INFO] [http-nio-8080-3] [abc123/span001] OrderService - 创建订单 orderId=999
2026-05-28 20:00:01 [INFO] [http-nio-8080-3] [abc123/span002] PaymentService - 发起支付 orderId=999
```

同一请求所有日志都有相同的 `abc123`，`grep traceId=abc123` 即可找出完整链路。

---

### 9.5 关键词卡片（可观测性模块）

| 关键词 | 一句话解释 |
|--------|-----------|
| **Micrometer** | JVM Metrics 门面，一套 API 对接 Prometheus / DataDog 等多种后端 |
| **Counter** | 只增不减的计数器，统计「发生了多少次」（请求数、秒杀次数） |
| **Timer** | 记录耗时分布，内置 count/sum/P99（支付回调耗时） |
| **Gauge** | 瞬时值，随时升降（队列深度、活跃连接数） |
| **Prometheus** | 时序数据库，定期 scrape /actuator/prometheus 拉指标 |
| **Grafana** | 查询 Prometheus 数据的可视化面板 |
| **Trace / Span** | Trace=全链路唯一 ID；Span=链路中一个操作单元 |
| **Brave** | Zipkin 生态的追踪库，负责生成和传播 traceId/spanId |
| **MDC** | SLF4J 的线程本地存储，日志格式里 %X{traceId} 从这里读 |
| **X-Trace-Id** | 响应头，前端错误上报时带上，运维可按此快速定位 |

---

## 第 10 章：接口限流——Redis 滑动窗口怎么防刷

### 10.1 为什么要限流？

先讲一个不限流的后果：

**场景**：秒杀优惠券，用户 A 写了个脚本每秒发 1000 个请求。
- 没有限流 → 1000 个请求全打到 Redis，竞争同一把锁，Redis 变慢，影响所有用户
- 有限流（5 秒内最多 1 次）→ 第 2 个请求起直接返回 429，Redis 只处理第 1 个

**限流的三个典型场景：**

| 场景 | 业务问题 | 限流策略 |
|------|---------|---------|
| 发短信验证码 | 防短信轰炸（每条约 0.05 元成本） | 同 IP 60 秒内最多 1 次 |
| 参与秒杀抢券 | 防重复点击、防脚本刷券 | 同用户 5 秒内最多 1 次 |
| 全文搜索 | ES 查询比读 DB 开销大 10 倍 | 同 IP 每秒最多 10 次 |

---

### 10.2 限流算法对比：为什么选「滑动窗口」

三种常见限流算法：

**方案 A：固定窗口计数**
```
规则：每分钟最多 10 次

[0:00 - 1:00)  请求 10 次  → 到上限
[1:00 - 2:00)  重置计数    → 又可以 10 次

问题：0:59 发 10 次 + 1:00 发 10 次 = 2 秒内 20 次，窗口边界突刺
```

**方案 B：令牌桶**
- 以固定速率往桶里放令牌，请求消耗令牌
- 实现复杂，需要记录上次填充时间

**方案 C：滑动窗口**（本项目选择）
```
规则：任意 60 秒内最多 10 次

每次请求都看「过去 60 秒」的请求数，没有固定边界，平滑限流。
没有固定窗口的突刺问题，实现也不复杂。
```

---

### 10.3 Redis ZSet 实现滑动窗口的核心原理

**数据结构**：Redis 有序集合（Sorted Set / ZSet）

```
Key:   rate_limit:seckill:do:10001   ← 用户 10001 的秒杀限流桶
Value: {member=时间戳, score=时间戳}
```

每次请求来了，往 ZSet 里塞一条记录，score 是当前时间戳（毫秒）。
查「过去 5000ms 内有多少条记录」= ZCARD 当前 ZSet 去掉过期记录后的大小。

**三步原子操作（Lua 脚本保证）：**

```lua
-- Step 1：删掉窗口之外的旧记录（ZREMRANGEBYSCORE）
redis.call('ZREMRANGEBYSCORE', key, 0, now - window)

-- Step 2：数当前窗口内有多少条（ZCARD）
local count = redis.call('ZCARD', key)

-- Step 3：未超限则写入本次请求
if count < limit then
    redis.call('ZADD', key, now, now)
    redis.call('EXPIRE', key, expire)
    return 0   -- 放行
end
return 1       -- 拒绝
```

**为什么要 Lua 脚本？**

如果三步分开执行：
```
线程 A：ZREMRANGEBYSCORE → ZCARD（返回 4，limit=5，可以）
线程 B：ZREMRANGEBYSCORE → ZCARD（也返回 4，limit=5，可以）
线程 A：ZADD（count=5）
线程 B：ZADD（count=6）← 超限了！
```

Redis 是单线程，但网络来回之间可能被其他请求插队。Lua 脚本在 Redis 端原子执行，中间不会被打断。

---

### 10.4 `@RateLimit` 注解的设计

自定义注解，声明在 Controller 方法上：

```java
@RateLimit(
    key = "seckill:do",     // 业务 key（标识哪个接口）
    limit = 1,              // 窗口内最多 N 次
    window = 5,             // 窗口大小
    unit = TimeUnit.SECONDS,// 单位（默认秒）
    keyType = KeyType.USER  // 限流维度：USER（按用户）或 IP（按 IP）
)
@PostMapping("/api/v1/seckill")
public Result<SeckillResultVO> doSeckill(...)
```

**keyType 的选择原则：**
- **需登录的接口** → `USER`（按用户 ID 限流，精准到人）
- **公开接口（发验证码、搜索）** → `IP`（未登录没有用户 ID，用 IP 兜底）

---

### 10.5 拦截器：在 Controller 之前拦截

限流逻辑放在 `RateLimitInterceptor`（实现 `HandlerInterceptor`），在 `AuthInterceptor` 之后执行：

```
请求到达
    ↓
AuthInterceptor（鉴权：Token 合法吗？用户是谁？）
    ↓ 通过才往下
RateLimitInterceptor（限流：频率超了吗？）
    ↓ 未超限才往下
Controller（真正的业务处理）
```

**为什么限流在鉴权之后？**

非法 Token 的请求不应该消耗限流计数 —— 限流是对合法用户的保护，不是对攻击请求的计数器。

**拦截器核心逻辑：**

```java
public boolean preHandle(HttpServletRequest request, ...) {
    // 1. 不是 Controller 方法（静态资源等），跳过
    if (!(handler instanceof HandlerMethod handlerMethod)) return true;

    // 2. 方法上没有 @RateLimit 注解，跳过
    RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
    if (rateLimit == null) return true;

    // 3. 构建 Redis Key：rate_limit:{key}:{userId 或 IP}
    String limitKey = buildLimitKey(request, rateLimit);

    // 4. 执行 Lua 脚本
    Long result = redisTemplate.execute(script, keys, now, window, limit, expire);

    // 5. 返回 1 = 超限 → 返回 HTTP 429
    if (result == 1L) {
        response.setStatus(429);
        response.getWriter().write("{\"code\":\"AUTH_CODE_SEND_TOO_FREQUENT\",...}");
        return false;
    }
    return true;  // 放行
}
```

---

### 10.6 实际接口限流配置汇总

| 接口 | 注解参数 | 含义 |
|------|---------|------|
| `POST /api/v1/auth/code` | `limit=1, window=60, IP` | 同 IP 60 秒内最多发 1 次验证码 |
| `POST /api/v1/auth/login` | `limit=10, window=60, IP` | 同 IP 60 秒内最多登录尝试 10 次 |
| `POST /api/v1/seckill` | `limit=1, window=5, USER` | 同用户 5 秒内最多抢 1 次 |
| `POST /api/v1/posts` | `limit=3, window=60, USER` | 同用户 60 秒内最多发 3 篇笔记 |
| `POST /api/v1/posts/.../comments` | `limit=5, window=10, USER` | 同用户 10 秒内最多评论 5 次 |
| `POST /api/v1/orders` | `limit=2, window=10, USER` | 同用户 10 秒内最多下单 2 次 |
| `GET /api/v1/search/shops` | `limit=10, window=1, IP` | 同 IP 每秒最多搜索 10 次 |
| `GET /api/v1/search/posts` | `limit=10, window=1, IP` | 同 IP 每秒最多搜索 10 次 |

---

### 10.7 面试常问：限流被触发时返回什么？

**HTTP 状态码**：`429 Too Many Requests`（专属状态码，前端统一处理）

**响应 Body**：与普通错误格式一致（`Result<Void>`）

```json
{
  "code": "AUTH_CODE_SEND_TOO_FREQUENT",
  "message": "请求过于频繁，请稍后再试",
  "data": null,
  "timestamp": "2026-05-28T20:00:00+08:00"
}
```

前端拦截到 429 后弹一个 Toast 提示「操作太频繁，请稍后再试」，不需要区分是哪个接口触发的。

---

### 10.8 关键词卡片（限流模块）

| 关键词 | 一句话解释 |
|--------|-----------|
| **滑动窗口** | 任意时间段内控制请求数，无固定边界突刺问题 |
| **固定窗口** | 把时间切成固定格子计数，有窗口边界突刺问题 |
| **令牌桶** | 以固定速率放令牌，请求拿令牌通过，突发流量有缓冲 |
| **ZSet** | Redis 有序集合，score 存时间戳实现时间窗口滑动 |
| **ZREMRANGEBYSCORE** | 删除 score 在某区间内的成员（清理过期请求记录） |
| **Lua 脚本** | Redis 端原子执行多条命令，解决 ZCARD + ZADD 之间的并发问题 |
| **HandlerInterceptor** | Spring MVC 拦截器接口，`preHandle` 在 Controller 执行前触发 |
| **429 Too Many Requests** | HTTP 标准限流状态码 |
| **keyType=USER** | 按用户 ID 限流，需登录接口用 |
| **keyType=IP** | 按客户端 IP 限流，公开接口用 |

---

*教程版本：2026-05-28 | 对应代码版本：feat: add observability*
