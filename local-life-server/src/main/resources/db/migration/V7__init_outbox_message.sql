-- =======================================================
-- V7：本地消息表（Outbox Pattern）
-- =======================================================
--
-- 背景：
--   支付回调成功后，需要发 MQ 消息通知下游（如核销统计、对账服务、用户通知等）。
--   但「更新 payment_order + UPDATE order_info + 发 MQ」三步无法原子完成：
--     · 数据库操作在事务内（原子），但 MQ 发送在事务外
--     · 如果 DB 提交成功后 MQ 发送失败 → 下游丢失「支付成功」事件
--     · 如果 MQ 发送后 DB 提交失败 → 重复发送（虽然消费者幂等，但增加噪音）
--
-- 解决方案：Transactional Outbox Pattern（事务性发件箱模式）
--   1. 业务操作（更新支付单、订单）和「写 outbox_message」放在同一个 DB 事务
--      → 要么都成功，要么都回滚，消除「消息丢失」的可能
--   2. 独立的 Relay 任务定时扫描 outbox_message，将 PENDING 消息投递到 RocketMQ
--   3. 投递成功 → 更新 outbox_message 状态为 SENT
--   4. 消费者保证幂等（通过 eventId + 业务唯一索引）
--
-- 面试价值：
--   Q: MQ 发送失败怎么办？
--   A: 本地消息表记录，定时任务重发（AT_LEAST_ONCE 保证）
--   Q: 消息重复怎么办？
--   A: 消费者通过 eventId 幂等处理（消费成功则记录，重复消费直接跳过）
--   Q: 本地事务和消息发送怎么保持一致？
--   A: 事务内同步写 outbox_message（和业务数据同一事务），Relay 任务异步投递
--
-- 状态机：
--   PENDING  → SENT     （Relay 任务投递成功）
--   PENDING  → FAILED   （投递重试超限，需人工处理或死信补偿）
--   SENT     → （终态，不再变更）
-- =======================================================

CREATE TABLE IF NOT EXISTS `outbox_message`
(
    `id`          BIGINT UNSIGNED  NOT NULL COMMENT '消息 ID，雪花算法生成',
    `event_id`    VARCHAR(64)      NOT NULL COMMENT '业务事件唯一 ID，消费者幂等判重用（雪花 ID 字符串）',
    `topic`       VARCHAR(64)      NOT NULL COMMENT 'RocketMQ Topic，如 payment-success-topic',
    `tag`         VARCHAR(64)      NOT NULL DEFAULT '' COMMENT 'RocketMQ Tag，用于消费者过滤',
    `payload`     TEXT             NOT NULL COMMENT '消息体 JSON（PaymentSuccessEvent 等）',
    `status`      VARCHAR(16)      NOT NULL DEFAULT 'PENDING' COMMENT '投递状态：PENDING / SENT / FAILED',
    `retry_count` TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '已重试次数（超过阈值标记 FAILED）',
    `next_retry_at` DATETIME       NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '下次重试时间，支持指数退避',
    `created_at`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- eventId 全局唯一，保证每个业务事件只有一条消息记录（防业务代码 bug 重复插入）
    UNIQUE KEY `uk_event_id` (`event_id`),
    -- Relay 任务扫描索引：找 PENDING 且到了重试时间的消息
    KEY `idx_outbox_status_retry` (`status`, `next_retry_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '本地消息表（Transactional Outbox Pattern），保证业务操作和 MQ 发送的最终一致性';
