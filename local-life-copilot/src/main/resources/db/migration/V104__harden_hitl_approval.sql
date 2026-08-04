-- Bind high-risk approvals to immutable payloads and exact LangGraph checkpoints.
-- Existing rows remain readable but have NULL contract fields and are not executable.
ALTER TABLE `hitl_approval`
    MODIFY COLUMN `checkpoint_id` VARCHAR(64) NULL COMMENT 'Exact persisted HITL checkpoint; NULL until checkpoint commit',
    ADD COLUMN `payload_version` INT NULL COMMENT 'Canonical approval payload version' AFTER `action_payload`,
    ADD COLUMN `payload_digest` CHAR(64) NULL COMMENT 'HMAC-SHA-256 of canonical approval payload' AFTER `payload_version`,
    ADD COLUMN `order_target_hash` CHAR(64) NULL COMMENT 'SHA-256 correlation hash of normalized order ID' AFTER `payload_digest`,
    ADD COLUMN `merchant_id` BIGINT UNSIGNED NULL COMMENT 'Original merchant scope' AFTER `order_target_hash`,
    ADD COLUMN `requested_user_id` BIGINT UNSIGNED NULL COMMENT 'Original Agent caller user ID' AFTER `merchant_id`,
    ADD COLUMN `requested_role` VARCHAR(32) NULL COMMENT 'Original Agent caller role' AFTER `requested_user_id`,
    ADD COLUMN `execution_id` VARCHAR(64) NULL COMMENT 'Current atomic execution claim ID' AFTER `approved_at`,
    ADD COLUMN `execution_lease_until` DATETIME NULL COMMENT 'Execution claim recovery boundary' AFTER `execution_id`,
    ADD COLUMN `executing_at` DATETIME NULL COMMENT 'Latest execution claim time' AFTER `execution_lease_until`,
    ADD COLUMN `executed_at` DATETIME NULL COMMENT 'Successful execution completion time' AFTER `executing_at`,
    ADD COLUMN `execution_result` JSON NULL COMMENT 'Sanitized result retained for idempotent replay' AFTER `executed_at`,
    ADD COLUMN `execution_error` TEXT NULL COMMENT 'Sanitized last execution failure' AFTER `execution_result`,
    ADD KEY `idx_hitl_status_lease` (`status`, `execution_lease_until`),
    ADD KEY `idx_hitl_payload_digest` (`payload_digest`);
