-- =======================================================
-- V10：高风险副作用账本
-- =======================================================
--
-- 背景：
--   HITL approval_id 只表示“人类已经授权”，不等于退款/补券一定执行成功。
--   网络超时、Agent resume 重试、服务重启后重复调用，都可能造成重复退款或重复发券。
--
-- 设计：
--   side_effect_ledger 以 (operation_type, idempotency_key) 做唯一约束。
--   当前 idempotency_key 使用 approval_id；同一个审批恢复多次时只能有一条副作用记录。
--   记录 request_payload / result_snapshot，便于排障和对账。
-- =======================================================

CREATE TABLE IF NOT EXISTS `side_effect_ledger` (
    `id` BIGINT UNSIGNED NOT NULL COMMENT '雪花 ID',
    `operation_type` VARCHAR(50) NOT NULL COMMENT '副作用类型：execute_refund / issue_compensation_coupon',
    `idempotency_key` VARCHAR(128) NOT NULL COMMENT '幂等键；当前使用 approval_id',
    `approval_id` VARCHAR(64) NOT NULL COMMENT 'HITL 审批 ID',
    `resource_id` VARCHAR(128) NOT NULL COMMENT '业务资源 ID，如订单号',
    `request_payload` JSON NOT NULL COMMENT '请求快照',
    `status` VARCHAR(20) NOT NULL COMMENT 'RUNNING / SUCCESS / FAILED',
    `result_snapshot` JSON NULL COMMENT '成功结果快照',
    `error_message` TEXT NULL COMMENT '失败原因摘要',
    `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_side_effect_operation_idem` (`operation_type`, `idempotency_key`),
    KEY `idx_side_effect_approval` (`approval_id`),
    KEY `idx_side_effect_resource` (`operation_type`, `resource_id`),
    KEY `idx_side_effect_status_time` (`status`, `created_at`)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci
COMMENT='高风险副作用幂等账本';
