-- =======================================================
-- V9：Outbox 死信自动恢复支持
-- =======================================================
--
-- 背景：
--   V7 建立的 outbox_message 表中，消息投递失败超过 3 次（MAX_RETRY_COUNT）
--   后会被标记为 FAILED，当前只能人工扫表重置。
--
--   在生产中常见的故障场景：RocketMQ Broker 短暂不可用（重启/网络抖动），
--   导致几分钟内的消息全部变 FAILED。Broker 恢复后，需要自动将这些消息
--   重新投递，不能完全依赖人工干预。
--
-- 解决方案：
--   新增 auto_retry_count 字段，记录「系统自动恢复」的次数（区别于
--   Relay 任务的 retry_count）。
--
--   OutboxService 新增定时任务（每小时执行一次）：
--   - 扫描 status='FAILED' AND auto_retry_count < 3 的消息
--   - 重置为 status='PENDING'，auto_retry_count +1
--   - retry_count 归零（给消息再次尝试 3 次的机会）
--   - next_retry_at = NOW()（立即投递）
--
--   这样 MQ 短暂不可用时最多自动恢复 3 次，超过 3 次仍需人工介入
--   （说明是持续性故障，不是临时抖动）。
--
-- 面试价值：
--   Q: MQ 挂了 outbox_message 积压了 10 万条怎么办？
--   A: 短暂不可用时（<3 次）系统自动恢复；持久故障后人工重置
--      auto_retry_count=0 即可。同时有告警：监控 FAILED 消息数量 > 阈值。
-- =======================================================

ALTER TABLE `outbox_message`
ADD COLUMN `auto_retry_count` TINYINT UNSIGNED NOT NULL DEFAULT 0
    COMMENT '系统自动恢复次数（区别于 Relay 任务的 retry_count）；超过阈值 3 次需人工介入，说明是持久性故障而非临时抖动'
AFTER `retry_count`;

-- 新增索引：定时任务扫描 FAILED 消息时用到
-- 只有 status=FAILED AND auto_retry_count < 3 的消息才会被自动恢复
-- 注意：原有 idx_outbox_status_retry 索引只包含 (status, next_retry_at)，
--       不覆盖 auto_retry_count，需要新增联合索引
CREATE INDEX `idx_outbox_status_auto_retry`
    ON `outbox_message` (`status`, `auto_retry_count`)
    COMMENT '死信自动恢复扫描索引：WHERE status=FAILED AND auto_retry_count < 3';
