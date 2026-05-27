-- =======================================================
-- V4：营销模块核心表
-- 包含：coupon_template（优惠券模板）、seckill_session（秒杀场次）、user_coupon（用户已领优惠券）
--
-- 建表顺序约束：
--   coupon_template 依赖 shop（V2 已建）
--   seckill_session 依赖 coupon_template
--   user_coupon 依赖 user（V1）+ coupon_template
--
-- 核心业务链路（秒杀抢券）：
--   1. 管理员创建 coupon_template + seckill_session，并预加载库存到 Redis
--   2. 秒杀开始时，用户发起请求：Redis Lua 脚本原子性预扣库存 + 判重
--   3. 预扣成功：发 MQ 消息，异步创建 user_coupon 记录（数据库写入解耦）
--   4. 预扣失败（库存不足 / 重复领取）：直接拒绝，不写 DB，Redis 压力最小化
--
-- 这种「Redis 预扣 + MQ 异步落库」的方案是秒杀场景的经典架构，
-- 核心价值在于把数据库写压力从「高并发尖峰」变成「平稳异步」。
-- =======================================================

-- -------------------------------------------------------
-- 1. coupon_template 表 —— 优惠券模板
-- -------------------------------------------------------
-- 用途：定义优惠券的基本规则，是秒杀活动的核心载体。
-- 一个模板可以关联多个秒杀场次（比如周一场、周三场）。
--
-- discount_type（折扣类型）枚举：
--   CASH    → 现金抵扣（如满 100 减 20）
--   PERCENT → 折扣券（如 8 折）
--
-- status 枚举：
--   ACTIVE   → 正常可用
--   INACTIVE → 已停用（终态，不可再抢）
--
-- 库存字段说明：
--   total_stock   → 总发行量（不变）
--   remain_stock  → 剩余库存（变化，每次领取 -1）
--   remain_stock 初始等于 total_stock，领完后为 0
--   真实秒杀中 remain_stock 的原子性操作在 Redis，此字段定期同步
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `coupon_template`
(
    `id`              BIGINT UNSIGNED  NOT NULL COMMENT '优惠券模板 ID，雪花算法生成',
    `shop_id`         BIGINT UNSIGNED  NOT NULL COMMENT '所属门店 ID，逻辑外键 -> shop.id',
    `coupon_name`     VARCHAR(64)      NOT NULL COMMENT '优惠券名称，如「五月五折券」',
    `discount_type`   VARCHAR(16)      NOT NULL DEFAULT 'CASH' COMMENT '折扣类型：CASH（现金）/ PERCENT（折扣）',
    `discount_value`  INT UNSIGNED     NOT NULL COMMENT '折扣值：CASH 类型单位为分（如 2000 = 20元），PERCENT 单位为百分比（如 80 = 8折）',
    `min_order_amount` INT UNSIGNED    NOT NULL DEFAULT 0 COMMENT '最低使用金额（分），0 表示无限制',
    `total_stock`     INT UNSIGNED     NOT NULL COMMENT '总发行数量（不变，记录原始库存）',
    `remain_stock`    INT UNSIGNED     NOT NULL COMMENT '剩余库存（Redis 预扣后定期同步到此字段）',
    `per_user_limit`  TINYINT UNSIGNED NOT NULL DEFAULT 1 COMMENT '每人限领次数，通常为 1（一人一单）',
    `valid_days`      INT UNSIGNED     NOT NULL DEFAULT 7 COMMENT '领券后有效天数，从领取时间起算',
    `status`          VARCHAR(16)      NOT NULL DEFAULT 'ACTIVE' COMMENT '状态：ACTIVE（正常）/ INACTIVE（已停用）',
    `deleted`         TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`      DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 查某个门店下的所有优惠券（商家管理 / C 端展示）
    KEY `idx_coupon_shop_id` (`shop_id`),
    -- 查可抢的券列表（只展示 ACTIVE 状态）
    KEY `idx_coupon_status` (`status`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '优惠券模板表（定义规则，不是用户领到的券）';


-- -------------------------------------------------------
-- 2. seckill_session 表 —— 秒杀场次
-- -------------------------------------------------------
-- 用途：定义某张券的「秒杀时间窗」。
-- 一个 coupon_template 可以关联多个 seckill_session（如每天一场）。
--
-- session_status 枚举：
--   PENDING  → 未开始（当前时间 < begin_time）
--   ACTIVE   → 进行中（begin_time <= 当前时间 <= end_time）
--   ENDED    → 已结束（当前时间 > end_time）
--   CANCELLED → 已取消
--
-- seckill_stock 说明：
--   这是本场次的秒杀量，可以 ≤ coupon_template.total_stock。
--   例如：总发行 1000 张，但第一场只放出 100 张。
--   Redis 里存的是 seckill_stock 而不是 total_stock。
--   Key：seckill:stock:{sessionId}:{couponTemplateId}
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `seckill_session`
(
    `id`                  BIGINT UNSIGNED  NOT NULL COMMENT '秒杀场次 ID，雪花算法生成',
    `coupon_template_id`  BIGINT UNSIGNED  NOT NULL COMMENT '关联的优惠券模板 ID，逻辑外键 -> coupon_template.id',
    `seckill_stock`       INT UNSIGNED     NOT NULL COMMENT '本场次秒杀库存（可以 < 模板总库存）',
    `begin_time`          DATETIME         NOT NULL COMMENT '秒杀开始时间',
    `end_time`            DATETIME         NOT NULL COMMENT '秒杀结束时间（必须 > begin_time）',
    `session_status`      VARCHAR(16)      NOT NULL DEFAULT 'PENDING' COMMENT '场次状态：PENDING/ACTIVE/ENDED/CANCELLED',
    `deleted`             TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 按模板查所有场次（管理后台）
    KEY `idx_session_coupon_id` (`coupon_template_id`),
    -- 查正在进行的秒杀场次（定时任务 + C 端展示）
    KEY `idx_session_status_time` (`session_status`, `begin_time`, `end_time`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '秒杀场次表（定义某张券在某个时间窗的秒杀活动）';


-- -------------------------------------------------------
-- 3. user_coupon 表 —— 用户已领优惠券
-- -------------------------------------------------------
-- 用途：记录用户实际领到的每一张券，是最终落库的结果。
-- 这张表由 MQ 消费者异步写入（不在秒杀主链路同步写，避免 DB 成为瓶颈）。
--
-- coupon_status 枚举：
--   UNUSED  → 未使用（已领取，未核销）
--   USED    → 已使用（核销后）
--   EXPIRED → 已过期（valid_until < 当前时间）
--
-- 唯一索引 uk_user_coupon_template 说明：
--   (user_id, coupon_template_id) 唯一，防止同一用户重复领同一张券。
--   这是数据库层面的最终兜底，即使 Redis 判重失效，此索引也能防止数据双写。
--   （一人一单的核心保证）
--
-- expire_at 说明：
--   = 领取时间 + coupon_template.valid_days
--   在 MQ 消费时计算填入。
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `user_coupon`
(
    `id`                  BIGINT UNSIGNED  NOT NULL COMMENT '用户券 ID，雪花算法生成',
    `user_id`             BIGINT UNSIGNED  NOT NULL COMMENT '领取用户 ID，逻辑外键 -> user.id',
    `coupon_template_id`  BIGINT UNSIGNED  NOT NULL COMMENT '券模板 ID，逻辑外键 -> coupon_template.id',
    `seckill_session_id`  BIGINT UNSIGNED  NOT NULL COMMENT '来自哪个秒杀场次，逻辑外键 -> seckill_session.id',
    `coupon_status`       VARCHAR(16)      NOT NULL DEFAULT 'UNUSED' COMMENT '券状态：UNUSED / USED / EXPIRED',
    `received_at`         DATETIME         NOT NULL COMMENT '领取时间（MQ 消费时填入）',
    `expire_at`           DATETIME         NOT NULL COMMENT '到期时间（= received_at + valid_days 天）',
    `used_at`             DATETIME         NULL     COMMENT '使用时间，UNUSED 时为 NULL',
    `deleted`             TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`          DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 核心唯一约束：一人一张模板券（数据库兜底，防止重复领取）
    UNIQUE KEY `uk_user_coupon_template` (`user_id`, `coupon_template_id`),
    -- 查某用户的所有券（「我的券包」）
    KEY `idx_user_coupon_user_id` (`user_id`),
    -- 查某张券被哪些用户领取（数据分析）
    KEY `idx_user_coupon_template_id` (`coupon_template_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '用户已领优惠券表（MQ 异步写入，包含实际领取状态）';
