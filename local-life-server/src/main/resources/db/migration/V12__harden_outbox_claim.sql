-- =======================================================
-- V12: leased claiming for multi-instance Outbox Relay
-- =======================================================
--
-- A status CAS after MQ send cannot prevent two Relay instances from sending
-- the same PENDING row concurrently. This migration adds a PROCESSING claim
-- with owner and lease fields. The database lock is held only while claiming;
-- MQ I/O runs after the transaction commits.

ALTER TABLE `outbox_message`
    ADD COLUMN `worker_id` VARCHAR(64) NULL AFTER `status`,
    ADD COLUMN `lease_until` DATETIME NULL AFTER `worker_id`,
    ADD COLUMN `claimed_at` DATETIME NULL AFTER `lease_until`;

ALTER TABLE `outbox_message`
    DROP INDEX `idx_outbox_status_retry`,
    ADD KEY `idx_outbox_claim` (`status`, `next_retry_at`, `lease_until`, `id`);
