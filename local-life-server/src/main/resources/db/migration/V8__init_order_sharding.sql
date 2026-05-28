-- =======================================================
-- V8：订单表分片迁移
-- =======================================================
--
-- 背景：
--   V5 创建了单表 order_info，适合初期数据量较小的阶段。
--   当订单量超过 1000 万行时，单表查询性能下降（B+ 树层数增加，IO 放大）。
--   此版本引入 ShardingSphere 水平分表，将 order_info 拆分为 4 个物理分片。
--
-- 分片策略（面试核心）：
--   逻辑表   → order_info
--   物理表   → order_info_0 / order_info_1 / order_info_2 / order_info_3
--   分片键   → user_id
--   分片算法 → MOD：user_id % 4
--
--   选 user_id 作为分片键的理由：
--   1. 核心查询「查某用户的订单列表」完全命中单分片，无跨片开销
--   2. 订单数据按用户聚集，单分片数据量均匀（用户分布均匀时）
--   3. 同一事务内的操作（下单、支付、取消）都只涉及单个 user_id，无跨片事务
--
--   不选 order_id 作为分片键的理由：
--   用 order_id 分片后，「我的订单」列表查询需广播所有分片，效率低；
--   而用 user_id 分片，「我的订单」只查 1/4 的数据。
--
-- 物理表和逻辑表的关系：
--   应用层（MyBatis-Plus）操作的是逻辑表 order_info，
--   ShardingSphere 在 JDBC 层拦截并改写 SQL，路由到对应物理表。
--   应用代码无需修改，分片对上层完全透明。
--
-- 与 V5（原 order_info）的关系：
--   V5 创建的 order_info 表保留，但 ShardingSphere 启动后不再路由到该表。
--   生产迁移步骤（本项目为演示，跳过数据迁移）：
--   1. 开启双写（同时写 order_info 和 order_info_{0..3}）
--   2. 存量数据迁移：将 order_info 中的数据按 user_id % 4 写入对应分片
--   3. 开启只读迁移：确认数据一致性
--   4. 切换到 ShardingSphere 路由
--   5. 下线老的 order_info 表（归档或删除）
--
-- 注意事项：
--   · 4 张物理表的结构完全相同，仅表名不同
--   · 所有索引保持与 V5 一致（user_id 索引现在等同于分片内全表索引）
--   · UNIQUE KEY uk_order_no：order_no 全局唯一，但 ShardingSphere 的 MOD 分片下
--     只在同一分片内校验唯一性，无法跨片保证。实际生产中通过雪花 ID 生成器保证全局唯一。
-- =======================================================

-- -------------------------------------------------------
-- order_info_0：user_id % 4 = 0 的用户的订单
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_info_0`
(
    `id`               BIGINT UNSIGNED  NOT NULL COMMENT '订单 ID，雪花算法生成（MyBatis-Plus @TableId ASSIGN_ID）',
    `order_no`         VARCHAR(32)      NOT NULL COMMENT '订单号，全局唯一，由应用层雪花 ID 生成',
    `user_id`          BIGINT UNSIGNED  NOT NULL COMMENT '下单用户 ID（分片键，user_id % 4 = 0 落此表）',
    `shop_id`          BIGINT UNSIGNED  NOT NULL COMMENT '门店 ID',
    `coupon_template_id` BIGINT UNSIGNED NULL COMMENT '使用的优惠券模板 ID（NULL 表示未使用）',
    `user_coupon_id`   BIGINT UNSIGNED  NULL COMMENT '使用的用户券 ID（NULL 表示未使用）',
    `original_amount`  INT UNSIGNED     NOT NULL COMMENT '原价金额（分）',
    `coupon_discount`  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '优惠券抵扣（分），0 表示未使用',
    `order_amount`     INT UNSIGNED     NOT NULL COMMENT '实付金额（分）= original_amount - coupon_discount',
    `order_status`     VARCHAR(16)      NOT NULL DEFAULT 'WAIT_PAY' COMMENT 'WAIT_PAY / PAID / CANCELLED / COMPLETED / REFUNDING',
    `remark`           VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '买家备注',
    `expire_at`        DATETIME         NOT NULL COMMENT '订单过期时间（30 分钟后自动关闭）',
    `pay_at`           DATETIME         NULL COMMENT '支付成功时间',
    `deleted`          TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    `created_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_status` (`user_id`, `order_status`),
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_status_expire` (`order_status`, `expire_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '订单分片表（order_info_0）：存储 user_id % 4 = 0 的用户订单';

-- -------------------------------------------------------
-- order_info_1：user_id % 4 = 1 的用户的订单
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_info_1`
(
    `id`               BIGINT UNSIGNED  NOT NULL COMMENT '订单 ID，雪花算法生成',
    `order_no`         VARCHAR(32)      NOT NULL COMMENT '订单号，全局唯一',
    `user_id`          BIGINT UNSIGNED  NOT NULL COMMENT '下单用户 ID（分片键，user_id % 4 = 1 落此表）',
    `shop_id`          BIGINT UNSIGNED  NOT NULL COMMENT '门店 ID',
    `coupon_template_id` BIGINT UNSIGNED NULL COMMENT '使用的优惠券模板 ID',
    `user_coupon_id`   BIGINT UNSIGNED  NULL COMMENT '使用的用户券 ID',
    `original_amount`  INT UNSIGNED     NOT NULL COMMENT '原价金额（分）',
    `coupon_discount`  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '优惠券抵扣（分）',
    `order_amount`     INT UNSIGNED     NOT NULL COMMENT '实付金额（分）',
    `order_status`     VARCHAR(16)      NOT NULL DEFAULT 'WAIT_PAY' COMMENT 'WAIT_PAY / PAID / CANCELLED / COMPLETED / REFUNDING',
    `remark`           VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '买家备注',
    `expire_at`        DATETIME         NOT NULL COMMENT '订单过期时间',
    `pay_at`           DATETIME         NULL COMMENT '支付成功时间',
    `deleted`          TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    `created_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_status` (`user_id`, `order_status`),
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_status_expire` (`order_status`, `expire_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '订单分片表（order_info_1）：存储 user_id % 4 = 1 的用户订单';

-- -------------------------------------------------------
-- order_info_2：user_id % 4 = 2 的用户的订单
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_info_2`
(
    `id`               BIGINT UNSIGNED  NOT NULL COMMENT '订单 ID，雪花算法生成',
    `order_no`         VARCHAR(32)      NOT NULL COMMENT '订单号，全局唯一',
    `user_id`          BIGINT UNSIGNED  NOT NULL COMMENT '下单用户 ID（分片键，user_id % 4 = 2 落此表）',
    `shop_id`          BIGINT UNSIGNED  NOT NULL COMMENT '门店 ID',
    `coupon_template_id` BIGINT UNSIGNED NULL COMMENT '使用的优惠券模板 ID',
    `user_coupon_id`   BIGINT UNSIGNED  NULL COMMENT '使用的用户券 ID',
    `original_amount`  INT UNSIGNED     NOT NULL COMMENT '原价金额（分）',
    `coupon_discount`  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '优惠券抵扣（分）',
    `order_amount`     INT UNSIGNED     NOT NULL COMMENT '实付金额（分）',
    `order_status`     VARCHAR(16)      NOT NULL DEFAULT 'WAIT_PAY' COMMENT 'WAIT_PAY / PAID / CANCELLED / COMPLETED / REFUNDING',
    `remark`           VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '买家备注',
    `expire_at`        DATETIME         NOT NULL COMMENT '订单过期时间',
    `pay_at`           DATETIME         NULL COMMENT '支付成功时间',
    `deleted`          TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    `created_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_status` (`user_id`, `order_status`),
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_status_expire` (`order_status`, `expire_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '订单分片表（order_info_2）：存储 user_id % 4 = 2 的用户订单';

-- -------------------------------------------------------
-- order_info_3：user_id % 4 = 3 的用户的订单
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_info_3`
(
    `id`               BIGINT UNSIGNED  NOT NULL COMMENT '订单 ID，雪花算法生成',
    `order_no`         VARCHAR(32)      NOT NULL COMMENT '订单号，全局唯一',
    `user_id`          BIGINT UNSIGNED  NOT NULL COMMENT '下单用户 ID（分片键，user_id % 4 = 3 落此表）',
    `shop_id`          BIGINT UNSIGNED  NOT NULL COMMENT '门店 ID',
    `coupon_template_id` BIGINT UNSIGNED NULL COMMENT '使用的优惠券模板 ID',
    `user_coupon_id`   BIGINT UNSIGNED  NULL COMMENT '使用的用户券 ID',
    `original_amount`  INT UNSIGNED     NOT NULL COMMENT '原价金额（分）',
    `coupon_discount`  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '优惠券抵扣（分）',
    `order_amount`     INT UNSIGNED     NOT NULL COMMENT '实付金额（分）',
    `order_status`     VARCHAR(16)      NOT NULL DEFAULT 'WAIT_PAY' COMMENT 'WAIT_PAY / PAID / CANCELLED / COMPLETED / REFUNDING',
    `remark`           VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '买家备注',
    `expire_at`        DATETIME         NOT NULL COMMENT '订单过期时间',
    `pay_at`           DATETIME         NULL COMMENT '支付成功时间',
    `deleted`          TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除标记',
    `created_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_order_no` (`order_no`),
    KEY `idx_user_id` (`user_id`),
    KEY `idx_user_status` (`user_id`, `order_status`),
    KEY `idx_shop_id` (`shop_id`),
    KEY `idx_status_expire` (`order_status`, `expire_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '订单分片表（order_info_3）：存储 user_id % 4 = 3 的用户订单';
