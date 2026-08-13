-- =======================================================
-- 集成测试专用 DDL（UserCouponRepositoryIT 用）
-- 取自 V14 迁移后的 user_coupon 表，
-- 单独拎出来给 Testcontainers MySQL 的 withInitScript 加载。
-- 核心验证对象：唯一索引 uk_user_coupon_issuance（按业务来源幂等）。
-- =======================================================
CREATE TABLE IF NOT EXISTS `user_coupon`
(
    `id`                  BIGINT UNSIGNED  NOT NULL COMMENT '用户券 ID',
    `user_id`             BIGINT UNSIGNED  NOT NULL COMMENT '领取用户 ID',
    `coupon_template_id`  BIGINT UNSIGNED  NOT NULL COMMENT '券模板 ID',
    `seckill_session_id`  BIGINT UNSIGNED  NULL     COMMENT '来自哪个秒杀场次，补偿券为空',
    `source_type`         VARCHAR(24)      NOT NULL COMMENT '发券来源：SECKILL / COMPENSATION',
    `source_approval_id`  VARCHAR(64)      NULL     COMMENT '补偿审批 ID',
    `issuance_key`        VARCHAR(192)     NOT NULL COMMENT '发券幂等键',
    `coupon_status`       VARCHAR(16)      NOT NULL DEFAULT 'UNUSED' COMMENT '券状态：UNUSED / USED / EXPIRED',
    `received_at`         DATETIME         NOT NULL COMMENT '领取时间',
    `expire_at`           DATETIME         NOT NULL COMMENT '到期时间',
    `used_at`             DATETIME         NULL     COMMENT '使用时间',
    `deleted`             TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_user_coupon_issuance` (`issuance_key`),
    UNIQUE KEY `uk_user_coupon_source_approval` (`source_approval_id`),
    KEY `idx_user_coupon_user_id` (`user_id`),
    KEY `idx_user_coupon_template_id` (`coupon_template_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '用户已领优惠券表（集成测试用）';
