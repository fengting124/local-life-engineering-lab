-- =======================================================
-- V11: database-backed order request idempotency
-- =======================================================
--
-- Redis remains useful as a cache, but it cannot be the final correctness
-- boundary because two requests may both miss before either writes the key.
-- This table serializes requests with a unique (user_id, idempotency_key)
-- constraint and persists the first successful response.

CREATE TABLE IF NOT EXISTS `order_idempotency`
(
    `id`              BIGINT UNSIGNED NOT NULL AUTO_INCREMENT,
    `user_id`         BIGINT UNSIGNED NOT NULL COMMENT 'request owner',
    `idempotency_key` VARCHAR(64)      NOT NULL COMMENT 'client generated request key',
    `request_hash`    CHAR(64)         NOT NULL COMMENT 'SHA-256 of normalized request body',
    `status`          VARCHAR(16)      NOT NULL DEFAULT 'PROCESSING' COMMENT 'PROCESSING / SUCCESS / FAILED',
    `response_json`   LONGTEXT         NULL COMMENT 'serialized OrderVO for deterministic replay',
    `failure_reason`  VARCHAR(256)     NULL COMMENT 'sanitized failure category',
    `lease_until`     DATETIME         NULL COMMENT 'PROCESSING owner lease; expired claims may be recovered',
    `expires_at`      DATETIME         NOT NULL COMMENT 'ledger retention boundary',
    `created_at`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_idempotency_user_key` (`user_id`, `idempotency_key`),
    KEY `idx_order_idempotency_lease` (`status`, `lease_until`),
    KEY `idx_order_idempotency_expire` (`expires_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Order creation idempotency ledger';
