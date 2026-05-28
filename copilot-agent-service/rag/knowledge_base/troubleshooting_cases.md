# 订单异常故障复盘案例库

> 适用范围：客服、运营 | 权限范围：public

---

## 案例 1：支付成功但券未发放（最常见）

### 现象
用户反馈：「我已经支付了，扣款短信都收到了，但订单状态显示『已支付』，券却没有出现在我的卡包里。」

### 根因分析

调用 `query_order` 查询订单：
- order_status = PAID（订单已支付）
- payment_status = SUCCESS（支付确实成功）
- coupon_status = NOT_ISSUED（券未发放）→ **异常点**

继续调用 `query_coupon_issue_log` 查询券发放日志，看到 outbox_message 状态：
- status = FAILED
- error = "MQ 消费失败：coupon_template_id=789 stock not enough"

**结论**：券模板库存耗尽，无法发券。订单资金已到账，但券池见底。

### 处理方案

**方案 A：退款**（推荐用于一次性活动）
- 调用 `execute_refund`（必须 HITL 审批）
- 优点：用户拿回资金
- 缺点：平台收入减少

**方案 B：补发等额优惠券**（推荐用于持续运营场景）
- 调用 `issue_compensation_coupon`（必须 HITL 审批）
- 补偿券面值通常等于或略高于原券（体现诚意）
- 优点：资金不回流，用户得到补偿
- 缺点：需要明确告知用户原因

### 预防措施

- 活动开始前预估流量，库存设置 ≥ 预期领取量的 1.2 倍
- 监控 `coupon_template.remaining_stock < 100` 告警
- 大促前与营销团队对齐库存上限

---

## 案例 2：用户已支付但订单仍显示「待支付」

### 现象
用户反馈：「我已经支付了，但订单还是显示待支付状态，担心钱白付了。」

### 根因分析

调用 `query_order` 查询订单：
- order_status = WAIT_PAY（订单待支付）→ **异常点**

调用 `query_payment` 查询支付单：
- pay_status = SUCCESS
- trade_no = "2026052812345"
- paid_at = "2026-05-28 14:30:00"

**结论**：支付回调未正确处理订单状态更新（支付成功但订单未更新）。
通常原因：
- 网络抖动导致支付渠道回调丢失
- 回调处理代码异常（如 Redis 不可用导致幂等判重失败）

### 处理方案

1. 联系技术团队手动触发重试支付回调：
   - 找到 payment_order 的 `trade_no`
   - 调用 LocalLife 内部 API：`POST /admin/payment/retry-callback`

2. 临时方案：手动更新订单状态为 PAID
   - 需要先核对 `paid_amount == order_amount`
   - 走数据库直接更新（仅限技术团队执行）

### 预防措施

- 监控「pay_status=SUCCESS 但 order_status=WAIT_PAY」的订单数（应为 0）
- 设置定时任务每 10 分钟扫描一次，自动修复（已实现）

---

## 案例 3：双 11 期间 MQ 消息堆积

### 现象
2025 年双 11，秒杀活动开始后 1 分钟内，发现：
- `outbox_message` 表 PENDING 数量飙升到 5000+
- 用户反馈「下单成功但收不到券」
- 支付回调成功但发券慢

### 根因分析

- RocketMQ Broker 单实例配置（4C8G），峰值 QPS 超过承载能力
- Outbox Relay 任务串行执行（一次处理 100 条），无法跟上消息生成速度
- 消费者并发数太低（默认 20）

### 处理方案

应急处理：
1. 临时增加 RocketMQ 消费者并发到 64
2. 扩容 Broker 到 3 节点集群
3. 暂停其他低优先级消息生产

事后改进：
- Outbox Relay 任务改为多线程并行（每线程处理一个分片）
- 关键消息（支付成功）走 RocketMQ 事务消息，不依赖 Outbox

---

## 案例 4：用户重复下单（前端重试问题）

### 现象
用户反馈：「我点了一次确认下单，怎么有两笔相同的订单？」

### 根因分析

调用 `query_order` 查询其中一笔，看到：
- 两笔订单的 shop_id / order_amount / created_at 几乎一致（间隔 < 2 秒）
- 都已支付

**结论**：前端在用户网络不稳定时自动重试，未携带 `X-Idempotency-Key` Header。

### 处理方案

- 退款其中一笔（推荐退款较晚的那笔）
- 调用 `execute_refund`（需 HITL 审批）

### 预防措施

前端改造：
- 所有下单请求强制携带 `X-Idempotency-Key`（UUID，单次提交固定）
- 网络重试时使用同一个 Key
- 后端 Redis SETNX 防重，相同 Key 返回原订单 ID

---

## 案例 5：ES 索引延迟导致搜索缺漏

### 现象
商家反馈：「我刚上架的新店，怎么搜索不到？」

### 根因分析

- 门店上架后写入 MySQL 立即成功
- ES 索引通过双写同步，但 Service 层 try-catch 吞掉了 ES 异常
- 检查日志发现 ES 写入失败（连接超时）

### 处理方案

- 触发全量重建索引：调用 LocalLife 内部 API `POST /admin/search/reindex/shops`

### 预防措施

- ES 异步同步改用 Canal + MQ，由消费者重试机制保证最终一致性
- 监控 MySQL row count vs ES doc count，超过 1% 偏差告警
