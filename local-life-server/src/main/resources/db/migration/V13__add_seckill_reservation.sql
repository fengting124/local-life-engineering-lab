CREATE TABLE IF NOT EXISTS `seckill_reservation`
(
    `reservation_id`      VARCHAR(64)     NOT NULL COMMENT '预扣事件 ID：{sessionId}_{userId}_seckill',
    `session_id`          BIGINT UNSIGNED NOT NULL COMMENT '秒杀场次 ID',
    `coupon_template_id`  BIGINT UNSIGNED NOT NULL COMMENT '券模板 ID',
    `user_id`             BIGINT UNSIGNED NOT NULL COMMENT '领取用户 ID',
    `status`              VARCHAR(20)     NOT NULL COMMENT 'PENDING / CONFIRMED / COMPENSATED',
    `reserved_at`         DATETIME        NOT NULL COMMENT 'Redis Lua 预扣成功时间',
    `created_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`reservation_id`),
    KEY `idx_seckill_reservation_pending` (`status`, `reserved_at`),
    KEY `idx_seckill_reservation_user` (`user_id`, `coupon_template_id`, `session_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COMMENT = '秒杀 Redis 预扣补偿账本';
