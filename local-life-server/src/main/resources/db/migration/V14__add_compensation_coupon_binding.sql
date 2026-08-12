-- =====================================================
-- V14: real compensation coupon bindings and issuance identity
--
-- Deployment contract: pause every user_coupon writer before applying V14.
-- Old Server versions cannot populate the new non-null issuance columns.
-- =====================================================

CREATE TABLE IF NOT EXISTS `compensation_coupon_binding`
(
    `id`                 BIGINT UNSIGNED NOT NULL COMMENT 'binding ID',
    `shop_id`            BIGINT UNSIGNED NOT NULL COMMENT 'shop scope',
    `merchant_id`        BIGINT UNSIGNED NOT NULL COMMENT 'denormalized merchant for audit validation',
    `face_value_minor`   INT UNSIGNED    NOT NULL COMMENT 'approved CASH face value in minor units',
    `coupon_template_id` BIGINT UNSIGNED NOT NULL COMMENT 'deterministically selected coupon template',
    `enabled`            TINYINT(1)      NOT NULL DEFAULT 1 COMMENT '1 enabled, 0 disabled',
    `created_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`         DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_comp_binding_shop_face` (`shop_id`, `face_value_minor`),
    UNIQUE KEY `uk_comp_binding_shop_template` (`shop_id`, `coupon_template_id`),
    KEY `idx_comp_binding_merchant` (`merchant_id`, `enabled`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'shop-scoped deterministic compensation coupon mapping';

ALTER TABLE `user_coupon`
    MODIFY COLUMN `seckill_session_id` BIGINT UNSIGNED NULL COMMENT 'seckill session; NULL for compensation',
    ADD COLUMN `source_type` VARCHAR(24) NULL COMMENT 'SECKILL / COMPENSATION' AFTER `seckill_session_id`,
    ADD COLUMN `source_approval_id` VARCHAR(64) NULL COMMENT 'HITL approval for compensation' AFTER `source_type`,
    ADD COLUMN `issuance_key` VARCHAR(192) NULL COMMENT 'database-level issuance idempotency key' AFTER `source_approval_id`;

UPDATE `user_coupon`
SET `source_type` = 'SECKILL',
    `issuance_key` = CONCAT('SECKILL:', `user_id`, ':', `coupon_template_id`)
WHERE `source_type` IS NULL;

ALTER TABLE `user_coupon`
    MODIFY COLUMN `source_type` VARCHAR(24) NOT NULL COMMENT 'SECKILL / COMPENSATION',
    MODIFY COLUMN `issuance_key` VARCHAR(192) NOT NULL COMMENT 'database-level issuance idempotency key',
    DROP INDEX `uk_user_coupon_template`,
    ADD UNIQUE KEY `uk_user_coupon_issuance` (`issuance_key`),
    ADD UNIQUE KEY `uk_user_coupon_source_approval` (`source_approval_id`);
