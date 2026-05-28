-- =======================================================
-- V5：交易模块核心表
-- 包含：order_info（订单主单）、payment_order（支付单）
--
-- 建表顺序约束：
--   order_info 依赖 user（V1）、shop（V2）、coupon_template（V4）
--   payment_order 依赖 order_info
--
-- 设计核心思路：
--   1. 订单和支付单分离（order_info ≠ payment_order）
--      原因：一笔订单可能经历「支付失败 → 重新支付」，每次发起支付都产生一条 payment_order，
--            但 order_info 只有一条。解耦后互不影响，对账也更清晰。
--
--   2. 金额全部以「分」（Integer/Long）存储，杜绝浮点数精度问题。
--
--   3. 延迟关单：order_info 里有 expire_at，到期未支付的订单由延时任务自动关闭。
--      当前阶段用定时任务轮询，后续升级为 RocketMQ 延时消息（面试时能说清升级路径）。
--
--   4. 幂等设计：
--      · uk_order_no（order_no 全局唯一）防止订单重复创建
--      · uk_payment_channel_trade_no（channel + trade_no）防止回调重复处理
-- =======================================================

-- -------------------------------------------------------
-- 1. order_info 表 —— 交易主单
-- -------------------------------------------------------
-- 命名为 order_info 而不是 order：
--   因为 ORDER 是多种 SQL 方言的保留关键字（如 MySQL 的 ORDER BY），
--   避免 SQL 语句里需要反引号转义的麻烦。
--
-- order_status 状态机：
--   WAIT_PAY → PAID       （支付成功，由支付回调触发）
--   WAIT_PAY → CANCELLED  （用户主动取消 或 expire_at 到期自动关闭）
--   PAID     → COMPLETED  （核销确认，当前阶段预留）
--   PAID     → REFUNDING  （申请退款，当前阶段预留）
--   注意：CANCELLED 和 COMPLETED 是终态，不可流转
--
-- order_amount 字段说明：
--   original_amount → 原价（分）
--   coupon_discount → 优惠券抵扣金额（分），0 表示未使用券
--   order_amount    → 实付金额（= original_amount - coupon_discount）
--   三个字段并存，方便对账和退款计算
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `order_info`
(
    `id`               BIGINT UNSIGNED  NOT NULL COMMENT '订单 ID，雪花算法生成',
    `order_no`         VARCHAR(32)      NOT NULL COMMENT '订单号（业务流水号），全局唯一，由应用层生成（雪花 ID 字符串）',
    `user_id`          BIGINT UNSIGNED  NOT NULL COMMENT '下单用户 ID，逻辑外键 -> user.id',
    `shop_id`          BIGINT UNSIGNED  NOT NULL COMMENT '门店 ID，逻辑外键 -> shop.id',
    `coupon_template_id` BIGINT UNSIGNED NULL COMMENT '使用的优惠券模板 ID（NULL 表示未使用券），逻辑外键 -> coupon_template.id',
    `user_coupon_id`   BIGINT UNSIGNED  NULL COMMENT '使用的用户券 ID，逻辑外键 -> user_coupon.id（NULL 表示未使用券）',
    `original_amount`  INT UNSIGNED     NOT NULL COMMENT '原价金额（分），下单时门店/商品的原价',
    `coupon_discount`  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '优惠券抵扣金额（分），0 表示未使用券',
    `order_amount`     INT UNSIGNED     NOT NULL COMMENT '实付金额（分）= original_amount - coupon_discount',
    `order_status`     VARCHAR(16)      NOT NULL DEFAULT 'WAIT_PAY' COMMENT '订单状态：WAIT_PAY / PAID / CANCELLED / COMPLETED / REFUNDING',
    `remark`           VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '买家备注，可为空',
    `expire_at`        DATETIME         NOT NULL COMMENT '订单过期时间，到期未支付自动取消（默认 30 分钟后）',
    `pay_at`           DATETIME         NULL COMMENT '支付成功时间，WAIT_PAY 时为 NULL',
    `deleted`          TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除',
    `created_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 全局唯一订单号（业务流水号，防止重复创建订单）
    UNIQUE KEY `uk_order_no` (`order_no`),
    -- 查某用户的订单列表（「我的订单」入口，高频查询）
    KEY `idx_order_user_id` (`user_id`),
    -- 按用户 + 状态查订单（「待支付」列表）
    KEY `idx_order_user_status` (`user_id`, `order_status`),
    -- 查某个门店的所有订单（商家管理后台，次高频）
    KEY `idx_order_shop_id` (`shop_id`),
    -- 延迟关单任务扫描：找所有 WAIT_PAY 且已过期的订单
    KEY `idx_order_status_expire` (`order_status`, `expire_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '交易订单主单表（order_info，避免与 SQL 关键字 ORDER 冲突）';


-- -------------------------------------------------------
-- 2. payment_order 表 —— 支付单
-- -------------------------------------------------------
-- 与 order_info 分离的原因：
--   · 一笔订单可以多次发起支付（首次失败、重试、超时重新支付）
--   · 每次发起支付生成一条 payment_order，有自己的生命周期
--   · 支付单和订单解耦，方便对账（按 channel + trade_no 核对支付渠道账单）
--
-- pay_status 状态机：
--   PENDING → SUCCESS  （支付成功，由渠道回调触发）
--   PENDING → FAILED   （支付超时 / 渠道通知失败）
--   SUCCESS → REFUNDED （退款成功，当前阶段预留）
--
-- trade_no 说明：
--   支付渠道（支付宝/微信）的交易流水号，唯一标识本次支付。
--   回调时用 (channel, trade_no) 做幂等判断（唯一索引兜底）。
--
-- paid_amount 说明：
--   支付渠道实际到账金额（分），回调时填入。
--   应与 order_info.order_amount 一致，若不一致说明支付金额被篡改。
--   PAYMENT_AMOUNT_MISMATCH 错误码处理这种场景。
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `payment_order`
(
    `id`           BIGINT UNSIGNED  NOT NULL COMMENT '支付单 ID，雪花算法生成',
    `payment_no`   VARCHAR(32)      NOT NULL COMMENT '支付单号（我方系统的支付流水号），全局唯一',
    `order_id`     BIGINT UNSIGNED  NOT NULL COMMENT '关联的订单 ID，逻辑外键 -> order_info.id',
    `order_no`     VARCHAR(32)      NOT NULL COMMENT '关联的订单号（冗余，方便回调时不 JOIN 查 order）',
    `user_id`      BIGINT UNSIGNED  NOT NULL COMMENT '支付用户 ID，冗余字段（快速查用户支付记录）',
    `pay_amount`   INT UNSIGNED     NOT NULL COMMENT '应付金额（分），= order_info.order_amount',
    `paid_amount`  INT UNSIGNED     NULL COMMENT '实际支付金额（分），支付回调时填入，用于金额核对',
    `pay_status`   VARCHAR(16)      NOT NULL DEFAULT 'PENDING' COMMENT '支付状态：PENDING / SUCCESS / FAILED / REFUNDED',
    `channel`      VARCHAR(16)      NOT NULL DEFAULT 'MOCK' COMMENT '支付渠道：MOCK / ALIPAY / WECHAT',
    `trade_no`     VARCHAR(64)      NULL COMMENT '支付渠道的交易流水号，成功后由回调填入',
    `callback_body` TEXT            NULL COMMENT '支付渠道原始回调报文（JSON），用于排查问题 / 对账',
    `paid_at`      DATETIME         NULL COMMENT '支付成功时间，PENDING/FAILED 时为 NULL',
    `created_at`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    -- 我方支付单号全局唯一
    UNIQUE KEY `uk_payment_no` (`payment_no`),
    -- 渠道 + 渠道流水号唯一（防止同一笔渠道支付被重复回调处理）
    -- 这是支付幂等的数据库最终兜底，Lua 脚本/Redis 是第一道防线
    UNIQUE KEY `uk_payment_channel_trade_no` (`channel`, `trade_no`),
    -- 查某笔订单的所有支付记录
    KEY `idx_payment_order_id` (`order_id`),
    -- 查某用户的支付记录
    KEY `idx_payment_user_id` (`user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '支付单表（一笔订单可能多条，每次发起支付生成一条）';
