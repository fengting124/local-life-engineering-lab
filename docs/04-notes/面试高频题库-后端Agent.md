# 后端 + Agent 面试高频题库

这份文档面向“Java 后端实习 + AI Agent 项目”面试准备。它不重复讲完整实现细节，而是把近期面经高频问题映射到本项目的代码、文档和答题套路。

阅读方式：

1. 先看每个模块的“面试官真正想确认什么”。
2. 再按“本项目怎么答”回到代码里验证。
3. 最后用“追问链”逼自己从业务讲到原理、取舍和故障兜底。

## 资料来源

本轮整理使用了三类来源：

- 本仓库已有抓取数据：`data/nowcoder_posts.jsonl`、`data/nowcoder_research_posts.jsonl`、`data/nowcoder_resume_posts.jsonl`，以及 `scripts/nowcoder_search_urls.py`、`scripts/nowcoder_fetch_posts.py`。
- 2026-06-11 最新公开抓取：`data/nowcoder_latest_candidate_urls_2026-06-11.txt` 共 80 个候选链接，`data/nowcoder_latest_posts_2026-06-11.jsonl` 共 80 条渲染结果，其中 69 条可用正文，正文约 19 万字。抓取正文属于本地研究数据，按 `.gitignore` 不提交进仓库。
- 公开面经和题库资料：
  - [牛客：大模型 Agent 面试全攻略](https://www.nowcoder.com/discuss/871718560224112640)
  - [牛客：2025 Java 面试题（美团、快手方向）](https://www.nowcoder.com/discuss/722397808745033728)
  - [KAMACODER：大模型 Agent 面试题](https://notes.kamacoder.com/interview/llm/agent_interview.html)
  - [JavaGuide：AI 面试指南](https://javaguide.cn/ai/interview-questions/ai-interview-guide.html)
  - [JavaGuide：后端面试复习路线](https://javaguide.cn/interview-preparation/backend-interview-plan.html)

最新抓取的关键词频次与题库判断一致：`项目`、`Agent`、`Java`、`Redis`、`MySQL`、`RAG`、`缓存`、`索引`、`八股`、`算法` 都是高频项。后续如果要继续抓登录态帖子全文，见本文末尾“继续拉取最新面经需要什么”。

## 高频趋势

本地 JSONL 和公开资料共同指向一个趋势：面试不是只问“用了什么技术”，而是连续追问“为什么这么设计、失败了怎么办、怎么验证”。后端实习仍然绕不开 Java、MySQL、Redis、MQ、Spring、并发、JVM 和算法；带 Agent 项目的候选人会额外被追问 RAG、Tool Calling、MCP、ReAct、Memory、HITL、安全和评测。

本项目最适合主打 5 张项目卡：

| 项目卡 | 面试价值 | 入口 |
| --- | --- | --- |
| 秒杀抢券 | Redis Lua、限流、异步削峰、幂等、防超卖 | `SeckillService.java`、`seckill.lua`、`SeckillServiceTest.java` |
| 支付和 Outbox | 事务一致性、MQ 可靠投递、幂等消费、故障恢复 | `PaymentService.java`、`OutboxService.java`、`PaymentServiceTest.java` |
| 门店缓存和搜索 | 缓存穿透、缓存一致性、ES 检索、限流 | `ShopService.java`、`rate_limit.lua`、`ShopServiceTest.java` |
| MCP 工具网关 | Agent 调业务能力的安全边界、RBAC、审计、JSON-RPC | `McpController.java`、`McpControllerTest.java` |
| Python Agent | ReAct、Fast Path、RAG、HITL、Checkpoint、Evals | `agent/graph.py`、`rag/pipeline.py`、`session/checkpointer.py` |

## 1. 项目介绍

### 面试官真正想确认什么

面试官不是听你报技术栈，而是确认你是不是理解业务、边界、数据流和故障模型。

### 60 秒版本

我做的是一个本地生活内容交易平台，加了一个企业级 Copilot。Java 主服务负责真实业务，比如用户、门店、笔记、优惠券、秒杀、订单、支付和退款；Java MCP Server 把部分业务能力封装成可审计、可限流、带 RBAC 的工具；Python Agent 负责对话、RAG、ReAct 推理、HITL 审批和状态持久化。三者共用真实业务数据，但 Agent 不直连业务库，而是通过 MCP 工具访问受控能力。

这个项目的核心价值是双线能力：传统后端可以讲 Redis、MySQL、MQ、分表、幂等、压测和测试门禁；Agent 线可以讲 MCP、RAG、Tool Calling、HITL、Evals 和安全治理。

### 追问链

| 追问 | 你的回答重点 |
| --- | --- |
| 为什么不是做一个纯 CRUD 项目？ | 本项目有高并发秒杀、支付一致性、缓存一致性、Agent 工具安全，能体现后端和 AI 应用工程能力。 |
| Java 和 Python 是否强耦合？ | 不是。Java 通过 HTTP/MCP 暴露工具，Python 只依赖工具协议和 OpenAPI/MCP schema，不依赖 Java 内部类。 |
| Agent 为什么不直接查数据库？ | 数据库缺少业务权限、审计、限流和操作边界；MCP Server 可以把工具输入输出、RBAC、HITL 和审计统一收口。 |
| 如果线上故障，怎么定位？ | Java 看 traceId、Actuator、Prometheus/Grafana、业务表；Agent 看 SSE step、tool trace、eval log、checkpoint。 |

## 2. Redis 和秒杀

### 高频问题

- Redis 为什么快？
- 缓存穿透、击穿、雪崩分别是什么？
- 秒杀怎么防超卖和重复领取？
- Lua 为什么能保证原子性？
- 分布式锁能不能替代 Lua？
- 热 key、大 key、库存 key 丢失怎么办？

### 本项目怎么答

入口：

- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/seckill/service/SeckillService.java`
- `local-life-server/src/main/resources/lua/seckill.lua`
- `local-life-server/src/test/java/com/personalprojections/locallife/server/module/seckill/SeckillServiceTest.java`
- [深度拷打与面试指南：秒杀抢券](./深度拷打与面试指南.md#3-秒杀抢券redis-lua-原子操作)

答题框架：

1. 业务问题：秒杀瞬间请求集中，不能让 MySQL 承担实时扣库存，否则会锁竞争、超卖或大量重试。
2. Redis 预扣：库存和用户领取记录放 Redis，Lua 一次性做判重、判库存、扣减、记录用户。
3. 原子性：Redis 单线程执行 Lua 脚本，脚本执行期间不会被其他命令打断。
4. 最终一致：预扣成功后异步写 DB，配合 Outbox、消费者幂等和结果轮询。
5. 兜底：DB 唯一索引、防重复消费、库存 key 丢失时宁可少卖不超卖。

### 标准追问

| 追问 | 答题要点 |
| --- | --- |
| 为什么不用数据库乐观锁？ | 乐观锁可以保证正确性，但高并发下大量失败重试会打爆 DB；秒杀要把第一层判断前移到 Redis。 |
| Lua 脚本会阻塞 Redis 吗？ | 会，所以脚本必须短小，只做 O(1) 操作；不能在 Lua 里做复杂循环或大 key 扫描。 |
| Redis 预扣成功，DB 写失败怎么办？ | 本项目用 Outbox 记录可重放事件，失败不是“丢失”，而是进入重试、死信或人工恢复。 |
| 每人限领 2 张怎么改？ | Set 改 Hash，记录 userId 到领取数量，Lua 里判断数量上限后再扣库存和 `HINCRBY`。 |
| 怎么证明没超卖？ | 单元测试覆盖重复领取和库存边界；压测用 k6/Locust 跑并发请求，最后校验 Redis/DB 库存和用户券数量。 |

## 3. MySQL、事务和索引

### 高频问题

- B+ 树为什么适合数据库索引？
- 聚簇索引和二级索引区别是什么？
- 什么情况下索引失效？
- MVCC、ReadView、可重复读怎么实现？
- MySQL 锁有哪些？间隙锁解决什么？
- 支付回调和订单状态流转如何保证幂等？

### 本项目怎么答

入口：

- `local-life-server/src/main/resources/db/migration/`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/order/service/PaymentService.java`
- `local-life-server/src/main/resources/sharding.yaml`
- [ER 图文档](../01-project/05-ER图文档.md)
- [代码级实现地图](../01-project/02-代码级实现地图.md)

答题框架：

1. 先从表结构讲：用户、门店、券、订单、支付单、Outbox 为什么拆开。
2. 再讲索引：登录按手机号查、订单按用户查、支付按渠道流水号幂等。
3. 再讲事务：支付成功时更新支付单、订单状态、写 Outbox 必须在同一事务。
4. 再讲并发：状态更新用 CAS 条件，例如 `WHERE pay_status = PENDING`。
5. 最后讲分片：订单表按 `user_id % 4`，用户订单列表可以命中单分片。

### 标准追问

| 追问 | 答题要点 |
| --- | --- |
| 为什么订单和支付单分表？ | 订单表达交易业务状态，支付单表达渠道支付状态；一笔订单可能有多次支付尝试，生命周期不同。 |
| 支付回调重复来了怎么办？ | 先查支付单，再用条件更新从 PENDING 到 SUCCESS；更新影响行数为 0 说明已处理，直接幂等返回。 |
| Outbox 为什么必须和业务同事务？ | 如果业务提交而消息没写，会丢事件；如果消息先提交而业务回滚，会出现幽灵消息。 |
| 二级索引回表是什么？ | 二级索引叶子节点存主键，查非索引覆盖字段要再按主键回聚簇索引。可以用覆盖索引减少回表。 |
| 可重复读如何避免幻读？ | 普通快照读依赖 ReadView；当前读在需要范围锁时用 next-key lock 约束插入。 |

## 4. MQ 和一致性

### 高频问题

- MQ 为什么能削峰？
- 消息丢失、重复、乱序分别怎么处理？
- RocketMQ 重试和死信是什么？
- 事务消息和 Outbox 怎么选？
- 消费者幂等怎么做？

### 本项目怎么答

入口：

- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/mq/service/OutboxService.java`
- `local-life-server/src/main/java/com/personalprojections/locallife/server/module/order/service/PaymentService.java`
- [深度拷打与面试指南：支付与 Outbox](./深度拷打与面试指南.md#4-支付与-outbox-消息可靠性)

答题框架：

1. 业务问题：支付成功后要发券、通知、更新统计，不能因为 MQ 短暂失败导致事件永久丢失。
2. Outbox：业务事务内写业务表和 `outbox_message`。
3. Relay：后台任务扫描 PENDING 消息，发送 MQ，成功后标记 SENT。
4. 重试：失败指数退避，超过次数转 FAILED，后续自动恢复或人工重放。
5. 幂等：消费者用 eventId、唯一索引或 Redis SETNX 防重复。

### 标准追问

| 追问 | 答题要点 |
| --- | --- |
| Outbox 和 RocketMQ 事务消息谁更好？ | Outbox 更通用、可查询、可人工重放；事务消息实时性更好但绑定 MQ 特性。 |
| Relay 多实例会重复发吗？ | 可能并发扫描，但状态更新用 CAS，消费者还要幂等；系统按 at-least-once 设计。 |
| 消息顺序怎么保证？ | 同一业务 key 发到同一队列才能保证局部有序；本项目核心关注可靠和幂等，订单状态用 CAS 兜底。 |
| 为什么订单超时关闭不一定走 Outbox？ | 关单消息可丢，因为定时任务按 `expire_at` 兜底；支付成功事件不可丢，才需要 Outbox。 |

## 5. Java 并发、Spring 和 JVM

### 高频问题

- ThreadLocal 用在哪里，有什么内存泄漏风险？
- 线程池参数怎么设置？
- synchronized、ReentrantLock、CAS、AQS 的区别？
- Spring Bean 生命周期是什么？
- 事务什么时候会失效？
- JVM 内存区域和 GC 怎么讲？

### 本项目怎么答

入口：

- 登录鉴权拦截器和用户上下文：看 [代码级实现地图](../01-project/02-代码级实现地图.md) 的登录鉴权链路。
- 限流脚本：`local-life-server/src/main/resources/lua/rate_limit.lua`
- 测试和 CI：`docs/04-notes/测试总览与结果汇总.md`

答题框架：

1. ThreadLocal：登录拦截器把当前用户放到线程上下文，Service 层不用重复传 userId；请求结束必须清理，避免线程池复用导致串用户。
2. Spring 事务：支付、Outbox、订单状态必须同事务；自调用、非 public 方法、异常被吞、异步线程都会导致事务预期失效。
3. 线程池：IO 密集型和 CPU 密集型参数不同，关键是有界队列、拒绝策略、监控和隔离。
4. JVM：秒杀请求对象多是短生命周期，主要影响 Young GC；生产用 G1/ZGC 关注 P99。

### 标准追问

| 追问 | 答题要点 |
| --- | --- |
| ThreadLocal 为什么会泄漏？ | 线程池线程长期存活，若请求结束不 remove，value 会挂在线程上，造成内存和用户上下文污染。 |
| Spring 事务为什么自调用失效？ | 事务基于代理，类内部 `this.method()` 不经过代理对象，事务增强不会生效。 |
| CAS 有什么 ABA 问题？ | 值从 A 变 B 又变 A，CAS 看不出中间变化；可用版本号或 `AtomicStampedReference`。 |
| AQS 是什么？ | 抽象队列同步器，用 state 和 FIFO 队列支撑 ReentrantLock、Semaphore、CountDownLatch 等。 |

## 6. MCP、Tool Calling 和 Agent

### 高频问题

- Agent 和普通 Chatbot、Workflow、Chain 有什么区别？
- ReAct 循环如何设计，如何防死循环？
- Function Calling 和 MCP 有什么区别？
- MCP 的 Host、Client、Server 分别是什么？
- Tool schema 怎么保证可靠？
- 高风险工具怎么做人审和审计？

### 本项目怎么答

入口：

- `local-life-copilot/src/main/java/com/personalprojections/locallife/copilot/mcp/McpController.java`
- `local-life-copilot/src/test/java/com/personalprojections/locallife/copilot/mcp/McpControllerTest.java`
- `copilot-agent-service/agent/graph.py`
- `copilot-agent-service/agent/tool_router.py`
- `copilot-agent-service/session/hitl.py`
- `copilot-agent-service/session/checkpointer.py`
- [Copilot 企业级 Agent 设计](../01-project/07-Copilot企业级Agent设计.md)
- [后端与 Agent 链路架构图](../01-project/12-后端与Agent链路架构图.md)

答题框架：

1. Agent 不只是一次问答，而是能规划、调用工具、观察结果、继续推理。
2. ReAct 循环是 Thought、Action、Observation 的迭代；必须有最大步数、重复工具检测、超时和终止条件。
3. Function Calling 是模型到函数的调用能力；MCP 是工具协议层，解决工具发现、schema、权限、资源和多工具接入标准化。
4. 本项目让 Python Agent 作为 MCP Client，Java MCP Server 暴露 tools/list 和 tools/call，业务能力仍在 Java 服务内。
5. 高风险动作如退款、补券要进入 HITL，checkpoint 保存中断状态，审批后恢复执行。

### 标准追问

| 追问 | 答题要点 |
| --- | --- |
| MCP 相比直接 REST 有什么价值？ | REST 只是接口；MCP 额外规范工具发现、schema、资源、权限边界、审计和跨模型/跨 Agent 可复用。 |
| ReAct 怎么防止无限循环？ | 限制最大 step、检测重复 tool+args、维护已观察结果、低收益时终止或转人工。 |
| Tool Calling 参数错了怎么办？ | 用 JSON schema、强类型校验、错误可恢复提示、重试次数限制；高风险工具不自动执行。 |
| Agent 调错工具怎么办？ | 工具路由前置、工具描述清晰、权限过滤、执行后校验 Observation，必要时 HITL。 |
| Java 和 Python 如何解耦？ | Python 只依赖 MCP HTTP/JSON-RPC 协议和工具 schema；Java 内部 Controller/Service/Mapper 可独立演进。 |

## 7. RAG、Memory 和安全

### 高频问题

- RAG 为什么能减少幻觉？
- 向量检索召回差怎么排查？
- Hybrid Search 和 Rerank 分别解决什么？
- Memory 和 Checkpoint 有什么区别？
- Prompt Injection 怎么防？
- Agent 怎么评测？

### 本项目怎么答

入口：

- `copilot-agent-service/rag/pipeline.py`
- `copilot-agent-service/rag/reranker.py`
- `copilot-agent-service/tests/test_rag_pipeline.py`
- `copilot-agent-service/evals/eval_cases.py`
- `copilot-agent-service/evals/metrics.py`
- [RAG 模型选型说明](../01-project/RAG模型选型说明.md)
- [模型解耦设计](../01-project/模型解耦设计.md)

答题框架：

1. RAG 把回答约束在可检索知识上，适合平台规则、客服 SOP、排障案例。
2. 召回差先查文档解析、chunk、embedding 模型、metadata、query rewrite，再查 hybrid search 和 rerank。
3. Rerank 用更贵但更准的模型对 TopK 候选重排，提升最终上下文质量。
4. Memory 分短期对话记忆和长期业务记忆；Checkpoint 是执行状态快照，用于 HITL 和故障恢复，不等同聊天记录。
5. 安全上要做权限过滤、引用来源、拒答阈值、prompt injection 检测、工具白名单和审计。

### 标准追问

| 追问 | 答题要点 |
| --- | --- |
| RAG 一定能防幻觉吗？ | 不能。它只能提供可依据上下文；还要有低置信拒答、引用来源、eval 和人工审核。 |
| 召回不到正确文档怎么办？ | 从解析、切片、embedding、查询改写、关键词召回、rerank、metadata 过滤逐层排查。 |
| Memory 为什么不能只存聊天记录？ | 聊天记录是历史文本；Checkpoint 还要保存当前节点、工具结果、待审批状态，才能恢复执行。 |
| Prompt Injection 怎么处理？ | 外部文档只作为数据，不允许覆盖系统指令；工具调用前做权限和 schema 校验，高危动作进 HITL。 |
| Agent Evals 怎么做？ | 建 golden cases，记录期望工具、期望字段、拒答场景、延迟和成本；CI 中跑稳定的离线样例。 |

## 8. 系统设计题

### 题 1：设计一个优惠券秒杀系统

回答结构：

1. 流量入口：网关限流、用户维度频控、防刷。
2. 库存层：Redis 预热库存，Lua 原子扣减和判重。
3. 异步层：抢到名额后写 Outbox/MQ，消费者落 DB。
4. 数据层：用户券唯一索引防重复，库存最终校验。
5. 查询层：结果轮询，PENDING/SUCCESS/FAILED。
6. 压测验证：并发请求数、成功数、失败原因、DB 最终券数。

### 题 2：设计一个支付回调可靠处理系统

回答结构：

1. 回调验签和幂等 key。
2. 支付单状态 CAS 更新。
3. 订单状态流转同事务。
4. Outbox 同事务记录支付成功事件。
5. Relay 投递 MQ，消费者幂等。
6. 失败告警、自动恢复和人工重放。

### 题 3：设计一个企业业务 Agent

回答结构：

1. 用户入口：FastAPI/SSE，携带用户身份和角色。
2. 编排层：Fast Path 处理简单查询，ReAct 处理多步任务。
3. 工具层：MCP Server 暴露 tools/list 和 tools/call。
4. 知识层：RAG 检索业务规则和 SOP。
5. 安全层：RBAC、HITL、审计、限流、prompt injection 防护。
6. 评测层：golden cases、trace replay、工具命中率、拒答率、延迟和成本。

## 9. 一定不要这样答

| 不推荐说法 | 更好的说法 |
| --- | --- |
| “我用了 Redis 做缓存。” | “门店详情读多写少，用 Caffeine+Redis 做两级缓存；不存在 ID 先经 BloomFilter 拦截，写路径通过删除缓存和 binlog/TTL 兜底控制不一致窗口。” |
| “秒杀用了 Lua，所以不会超卖。” | “Lua 只是第一层原子预扣；还要解释用户判重、库存 key、异步落库、DB 唯一索引、压测后如何校验最终一致。” |
| “用了 MQ 解耦。” | “支付成功事件不能丢，所以用 Outbox 同事务落表，Relay at-least-once 投递，消费者幂等；订单关单这类可丢消息不一定需要 Outbox。” |
| “Agent 会调用工具。” | “Python Agent 通过 MCP Client 调 Java MCP Server，工具有 schema、RBAC、审计、限流；退款补券进入 HITL，不让模型直接执行高风险写操作。” |
| “RAG 防幻觉。” | “RAG 降低幻觉但不能消灭幻觉，还需要召回评测、rerank、低置信拒答、引用来源和 prompt injection 防护。” |

## 10. 备考优先级

### P0：必须能手写或讲清

- Redis：缓存穿透/击穿/雪崩、Lua 原子性、分布式锁、热 key。
- MySQL：B+ 树、索引失效、事务隔离、MVCC、锁、慢 SQL 排查。
- MQ：可靠投递、重复消费、顺序消息、死信、Outbox。
- Java：集合、并发、线程池、ThreadLocal、CAS/AQS。
- Spring：Bean 生命周期、AOP、事务失效、拦截器。
- Agent：ReAct、Function Calling、MCP、RAG、HITL、Evals。
- 项目：秒杀、支付、缓存、MCP、RAG 五张项目卡。

### P1：能回答追问

- ShardingSphere 分片键选择和跨分片查询。
- Canal/binlog 缓存失效。
- ES 检索和 Geo 查询。
- Prompt Injection、权限过滤、工具审计。
- Docker Compose、CI、JaCoCo、PIT、k6/Locust。

### P2：补短板

- JVM GC 调优、线上 OOM 排查。
- Linux 常用命令、网络排查。
- 算法：数组、链表、哈希、二分、栈队列、树、动态规划基础。

## 11. 继续拉取最新面经需要什么

当前脚本在 `scripts/` 下已经具备搜索和正文抓取能力，本机已安装 Playwright Python 包和 Chromium 浏览器缓存。若在新机器上重建环境，执行：

```bash
pip install playwright
python -m playwright install chromium
```

如果 Linux 缺少 Chromium 运行库且不能 sudo，可以像本次一样把缺失的 `.deb` 解包到用户缓存目录，再通过 `LD_LIBRARY_PATH` 启动脚本。

如果要抓牛客登录态或仅登录可见的帖子，需要提供以下任一方式：

1. 已登录浏览器的 CDP 地址，例如先启动 Chrome 远程调试，再用脚本参数 `--cdp http://127.0.0.1:9222`。
2. 牛客 Cookie，放到环境变量 `NOWCODER_COOKIE` 后执行：

```bash
python3 scripts/nowcoder_fetch_posts.py \
  --urls-file data/nowcoder_latest_candidate_urls_2026-06-11.txt \
  --out data/nowcoder_latest_posts_with_cookie.jsonl \
  --cookie-env NOWCODER_COOKIE
```

这不是当前题库更新的阻塞项；公开可访问部分已经完成最新抓取。
