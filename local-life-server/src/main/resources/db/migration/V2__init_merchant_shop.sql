-- =============================================================
-- V2__init_merchant_shop.sql
-- 商家表 + 门店表初始化脚本
--
-- 执行顺序：V1（user 表）之后
-- =============================================================

USE `local_life`;

-- =============================================================
-- 商家表 merchant
--
-- 设计说明：
--   1. 商家（Merchant）与用户（User）是一对一关系。
--      C 端用户注册后，若想成为商家，需要申请商家资质，
--      通过后在 merchant 表中创建一条记录，关联到对应的 user_id。
--      一个用户只能有一个商家身份（unique key uk_merchant_user_id）。
--
--   2. 商家与门店是一对多关系：一个商家可以拥有多家门店（连锁场景）。
--      shop 表通过 merchant_id 外键关联。
--
--   3. status 字段表示商家审核/经营状态：
--      PENDING  = 待审核（刚申请，运营审核中）
--      APPROVED = 已通过（可以开门店、发活动）
--      REJECTED = 已拒绝（审核不通过）
--      DISABLED = 已禁用（违规下线）
--
--   4. contact_mobile 是商家运营联系手机号，与 user.mobile 可以不同
--      （例如法人用个人号注册，运营联系号是店内手机）。
-- =============================================================
CREATE TABLE IF NOT EXISTS `merchant`
(
    `id`             BIGINT UNSIGNED  NOT NULL COMMENT '商家ID，雪花算法',

    -- 关联用户：一个 User 只能有一个商家身份
    `user_id`        BIGINT UNSIGNED  NOT NULL COMMENT '关联用户ID，外键 user.id',

    -- 商家名称：对外展示，如「小明餐饮有限公司」
    `merchant_name`  VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '商家名称',

    -- 商家 logo URL
    `logo`           VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '商家logo URL',

    -- 商家简介
    `description`    VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '商家简介',

    -- 运营联系手机号（不要求唯一，连锁品牌可能共用一个联系方式）
    `contact_mobile` VARCHAR(11)      NOT NULL DEFAULT '' COMMENT '运营联系手机号',

    -- 商家状态：PENDING/APPROVED/REJECTED/DISABLED
    `status`         VARCHAR(16)      NOT NULL DEFAULT 'PENDING' COMMENT '商家状态',

    -- 逻辑删除
    `deleted`        TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',

    `created_at`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),

    -- 一个用户只能申请一个商家
    UNIQUE KEY `uk_merchant_user_id` (`user_id`),

    -- 按状态查询（运营后台审核列表经常按状态筛选）
    KEY `idx_merchant_status` (`status`)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '商家表';


-- =============================================================
-- 门店表 shop
--
-- 设计说明：
--   1. 门店属于商家，merchant_id 是外键。
--      一个商家可以有多家门店（1:N）。
--
--   2. 地理位置存储：
--      使用 DECIMAL(10,7) 分别存储经度（longitude）和纬度（latitude）。
--      精度 7 位小数约等于 0.01 米精度，足够门店定位使用。
--      同时创建空间索引（SPATIAL INDEX），支持后续接入 Elasticsearch Geo 搜索。
--      当前阶段直接用经纬度字段，ES 接入后由 ES 承载 Geo 搜索，MySQL 只做主数据存储。
--      注意：MySQL 空间索引要求字段类型为 GEOMETRY，这里先用 DECIMAL 保持简单，
--            ES 会作为搜索的主力，MySQL 经纬度字段只用于数据同步。
--
--   3. 门店状态流转：
--      DRAFT   → ONLINE（商家提交上线申请）
--      ONLINE  → OFFLINE（商家主动下线，或平台下线）
--      OFFLINE → ONLINE（恢复上线）
--      ONLINE  → CLOSED（永久关店）
--      任何状态 → CLOSED（平台强制关闭）
--
--   4. category_id：门店分类（餐饮/酒店/娱乐/购物等），当前只存 ID，
--      分类表（category）后续迭代再建，第一阶段用整型占位即可。
--
--   5. 索引设计：
--      idx_shop_merchant_id：商家查看自己的门店列表
--      idx_shop_status：按状态筛选（运营后台、用户端只展示 ONLINE 的门店）
--      idx_shop_category_status：按分类 + 状态搜索（最常见的组合查询）
-- =============================================================
CREATE TABLE IF NOT EXISTS `shop`
(
    `id`            BIGINT UNSIGNED  NOT NULL COMMENT '门店ID，雪花算法',

    -- 所属商家
    `merchant_id`   BIGINT UNSIGNED  NOT NULL COMMENT '所属商家ID，外键 merchant.id',

    -- 门店名称，对用户展示，如「小明饺子馆（西湖店）」
    `shop_name`     VARCHAR(64)      NOT NULL DEFAULT '' COMMENT '门店名称',

    -- 门店分类 ID（1=餐饮 2=酒店 3=娱乐 等，分类表后续迭代）
    `category_id`   INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '门店分类ID',

    -- 封面图 URL
    `cover_image`   VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '门店封面图URL',

    -- 门店简介
    `description`   VARCHAR(512)     NOT NULL DEFAULT '' COMMENT '门店简介',

    -- 详细地址，如「浙江省杭州市西湖区文三路 138 号」
    `address`       VARCHAR(256)     NOT NULL DEFAULT '' COMMENT '详细地址',

    -- 经度，范围 -180.0000000 ~ 180.0000000
    -- DECIMAL(10,7)：整数部分最多 3 位（-180），小数部分 7 位
    `longitude`     DECIMAL(10, 7)   NOT NULL DEFAULT 0.0000000 COMMENT '经度',

    -- 纬度，范围 -90.0000000 ~ 90.0000000
    `latitude`      DECIMAL(10, 7)   NOT NULL DEFAULT 0.0000000 COMMENT '纬度',

    -- 联系电话（门店电话，非商家手机号）
    `phone`         VARCHAR(20)      NOT NULL DEFAULT '' COMMENT '门店联系电话',

    -- 营业时间描述，如「周一至周日 10:00-22:00」，纯文本，不做结构化
    `business_hours` VARCHAR(128)    NOT NULL DEFAULT '' COMMENT '营业时间描述',

    -- 门店评分（冗余字段，由笔记/评论汇总计算，存在 shop 表方便排序）
    -- DECIMAL(2,1)：一位整数 + 一位小数，如 4.8，范围 0.0 ~ 9.9
    `score`         DECIMAL(2, 1)    NOT NULL DEFAULT 0.0 COMMENT '综合评分，0.0~5.0',

    -- 状态：DRAFT / ONLINE / OFFLINE / CLOSED
    `status`        VARCHAR(16)      NOT NULL DEFAULT 'DRAFT' COMMENT '门店状态',

    -- 逻辑删除
    `deleted`       TINYINT UNSIGNED NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',

    `created_at`    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',
    `updated_at`    DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),

    -- 商家查自己的门店列表
    KEY `idx_shop_merchant_id` (`merchant_id`),

    -- 按状态筛选（只展示 ONLINE 的门店）
    KEY `idx_shop_status` (`status`),

    -- 按分类 + 状态组合查询（用户搜索「附近餐饮」时的核心索引）
    KEY `idx_shop_category_status` (`category_id`, `status`)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '门店表';
