-- Durable, append-only audit for merchant compensation binding changes.
CREATE TABLE IF NOT EXISTS `compensation_coupon_binding_audit`
(
    `id`               BIGINT UNSIGNED NOT NULL COMMENT 'audit row ID',
    `binding_id`       BIGINT UNSIGNED NOT NULL COMMENT 'compensation binding ID',
    `shop_id`          BIGINT UNSIGNED NOT NULL COMMENT 'shop scope',
    `merchant_id`      BIGINT UNSIGNED NOT NULL COMMENT 'approved merchant scope',
    `face_value_minor` INT UNSIGNED    NOT NULL COMMENT 'configured CASH face value',
    `action`           VARCHAR(16)     NOT NULL COMMENT 'CREATE / REPLACE / ENABLE / DISABLE',
    `operator_user_id` BIGINT UNSIGNED NOT NULL COMMENT 'authenticated operator user',
    `request_id`       VARCHAR(64)     NULL COMMENT 'trace-correlated HTTP request ID',
    `before_snapshot`  JSON            NULL COMMENT 'stable terms before mutation',
    `after_snapshot`   JSON            NULL COMMENT 'stable terms after mutation',
    `created_at`       DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_comp_binding_audit_shop_created` (`shop_id`, `created_at`),
    KEY `idx_comp_binding_audit_binding_created` (`binding_id`, `created_at`),
    CONSTRAINT `chk_comp_binding_audit_action`
        CHECK (`action` IN ('CREATE', 'REPLACE', 'ENABLE', 'DISABLE'))
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'durable compensation coupon binding change audit';

