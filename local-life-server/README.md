# local-life-server

> LocalLife 传统后端主服务 | Java 17 + Spring Boot 3.5 | 端口 8080

---

## 快速启动

```bash
# 前提：MySQL 8 + Redis 7 已启动（见 infra/docker-compose.dev.yml）
cd local-life-server
mvn spring-boot:run

# 验证
curl http://localhost:8080/actuator/health
```

**ShardingSphere 注意**：`application.yml` 的 datasource.url 指向 `sharding.yaml`，
ShardingSphere 接管数据源。如需直连 MySQL 调试，临时改回 `jdbc:mysql://localhost:3306/local_life...`。

---

## 模块地图

```
src/main/java/.../server/
├── module/
│   ├── auth/           登录：发验证码 → 验证码登录 → Token
│   ├── shop/           门店：创建/上线/下线/搜索（含 Geo）
│   ├── post/           笔记：发布/点赞/评论/Feed 流
│   ├── seckill/        秒杀：场次管理 + Redis Lua 预扣
│   ├── order/          订单：下单/支付/取消/延迟关单
│   ├── mq/             消息：Outbox + Relay + PaymentConsumer
│   ├── search/         搜索：ES 全文检索 + Geo 距离
│   └── internal/       内部 API：退款/补券（供 Copilot 调用）
├── common/
│   ├── interceptor/    AuthInterceptor（Token 鉴权）
│   ├── ratelimit/      @RateLimit + RateLimitInterceptor（Redis 滑动窗口）
│   ├── metrics/        BusinessMetrics（秒杀/订单/支付自定义指标）
│   ├── filter/         TraceIdFilter（MDC traceId 注入）
│   ├── context/        UserContext（ThreadLocal 用户摘要）
│   ├── exception/      GlobalExceptionHandler + BizException
│   └── result/         Result<T> + ErrorCode + PageResult<T>
├── config/
│   ├── WebMvcConfig    拦截器注册 + 白名单路径
│   ├── ElasticsearchConfig  ES 索引自动初始化
│   └── ObservabilityConfig  Prometheus 公共标签
└── domain/
    ├── entity/         16 张表的实体类
    └── mapper/         MyBatis-Plus Mapper（含自定义 SQL）
```

---

## 数据库迁移（手动执行，按版本顺序）

| 文件 | 内容 |
|------|------|
| `V1__init_user.sql` | user 表 |
| `V2__init_merchant_shop.sql` | merchant、shop 表 |
| `V3__init_post_comment_follow.sql` | post、comment、follow_relation 表 |
| `V4__init_coupon_seckill.sql` | coupon_template、seckill_session、user_coupon 表 |
| `V5__init_order_payment.sql` | order_info、payment_order 表（未分片版） |
| `V6__add_shop_price.sql` | shop.price 字段补充 |
| `V7__init_outbox_message.sql` | outbox_message 表（Transactional Outbox） |
| `V8__init_order_sharding.sql` | order_info_0 ~ order_info_3（ShardingSphere 分片表） |

---

## 核心设计决策（快速索引）

### 1. Token 方案选 Redis 而非 JWT
`AuthService.login()` → 随机 UUID → Redis `login:token:{token}` TTL 7 天。
原因：支持主动踢出（封号、异常登录），JWT 无法服务端作废。

### 2. 秒杀：Redis Lua 三步原子操作
`SeckillService.doSeckill()` → `seckill.lua`：SADD 判重 + DECRBY 判库存 + 预扣。
原因：三步分开就会被并发打断，Lua 在 Redis 单线程内原子执行。

### 3. 支付回调幂等：CAS UPDATE
`PaymentService.handleCallback()` → `UPDATE WHERE pay_status='PENDING'`。
第二次回调时 status 已是 SUCCESS，affected=0，直接跳过。

### 4. 消息不丢：Transactional Outbox
`OutboxService.saveToOutbox()`（Propagation.MANDATORY，和业务同一事务）→
`relayMessages()` 每 10s 扫描投递 → 消费者 Redis SETNX 幂等。

### 5. 订单分表：ShardingSphere user_id % 4
`sharding.yaml`：order_info → order_info_{0..3}，按 user_id 分片。
所有 UPDATE 语句携带 user_id 精准路由，避免广播。

### 6. 限流：Redis ZSet 滑动窗口
`rate_limit.lua`：ZREMRANGEBYSCORE + ZCARD + ZADD 三步 Lua 原子。
触发时返回 HTTP 429，响应体与 `Result<T>` 格式一致。

---

## 接口白名单（无需 Token）

```
POST /api/v1/auth/code          发验证码
POST /api/v1/auth/login         登录
GET  /api/v1/shops              搜索门店列表
GET  /api/v1/shops/{shopId}     门店详情
GET  /api/v1/search/shops       ES 搜索门店
GET  /api/v1/search/posts       ES 搜索笔记
GET  /api/v1/coupons/templates  可抢券列表
POST /api/v1/payments/callback  支付回调（渠道验签）
/internal/**                    内部 API（X-Internal-Key 验证）
/actuator/**                    健康检查
```

---

## 环境变量（application.yml 默认值）

| 配置项 | 默认值 | 说明 |
|--------|--------|------|
| MySQL | `localhost:3306/local_life` | 通过 sharding.yaml 连接 |
| Redis | `localhost:6379 db:0` | Token、缓存、限流、Lua |
| ES | `localhost:9200` | 搜索索引（无认证） |
| RocketMQ | `localhost:9876` | Outbox Relay |
| Zipkin | `localhost:9411` | Trace 上报 |
| internal.api-key | `local-life-internal-secret` | Copilot 调用内部 API 的密钥 |
