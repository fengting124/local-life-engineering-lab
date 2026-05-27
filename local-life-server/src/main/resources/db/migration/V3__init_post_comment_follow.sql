-- =======================================================
-- V3：内容社区核心表
-- 包含：post（探店笔记）、comment（评论）、follow_relation（关注关系）
--
-- 建表顺序约束：
--   post 依赖 user、shop（V1、V2 已建）
--   comment 依赖 post、user
--   follow_relation 依赖 user（自引用）
--
-- 字段命名规范：
--   - 主键：id (BIGINT UNSIGNED)，雪花算法由应用层生成
--   - 外键：xxx_id，逻辑外键不加数据库约束（分布式场景不推荐强约束）
--   - 时间：created_at / updated_at，由应用层 MetaObjectHandler 自动填充
--   - 逻辑删除：deleted TINYINT(1) DEFAULT 0，MyBatis-Plus @TableLogic
--   - 状态：status VARCHAR(16)，约定值见各表注释
-- =======================================================

-- -------------------------------------------------------
-- 1. post 表 —— 探店笔记主体
-- -------------------------------------------------------
-- 用途：用户发布的探店内容，关联门店，携带图片/文字。
--
-- 状态机（status 字段）：
--   DRAFT    → PUBLISHED  （发布）
--   PUBLISHED → OFFLINE   （管理员下架或用户自行删除，使用逻辑删除代替改状态）
--
-- 目前阶段只有 PUBLISHED 状态（发布即上线），DRAFT 预留后续草稿功能。
--
-- 点赞数（like_count）和收藏数（favorite_count）：
--   实时点赞数存 Redis，此字段作为「持久化快照」定期同步，防止 Redis 故障丢数据。
--   不做实时写库，避免高并发热点更新瓶颈。
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `post`
(
    `id`             BIGINT UNSIGNED  NOT NULL COMMENT '笔记 ID，雪花算法生成',
    `user_id`        BIGINT UNSIGNED  NOT NULL COMMENT '作者用户 ID，逻辑外键 -> user.id',
    `shop_id`        BIGINT UNSIGNED  NOT NULL COMMENT '关联门店 ID，逻辑外键 -> shop.id',
    `title`          VARCHAR(128)     NOT NULL DEFAULT '' COMMENT '笔记标题，最多 128 字符',
    `content`        TEXT             NOT NULL COMMENT '笔记正文，富文本（当前阶段纯文本）',
    `images`         VARCHAR(2048)    NOT NULL DEFAULT '' COMMENT '图片 URL 列表，JSON 数组字符串，最多 9 张',
    `like_count`     INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '点赞数快照（实时数在 Redis，此处定期同步）',
    `comment_count`  INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '评论数快照（同上）',
    `status`         VARCHAR(16)      NOT NULL DEFAULT 'PUBLISHED' COMMENT '状态：DRAFT（草稿）/ PUBLISHED（已发布）',
    `deleted`        TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 已删除',
    `created_at`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间，由 MetaObjectHandler 填充',
    `updated_at`     DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    -- 查当前用户的所有笔记（「我的笔记」列表）
    KEY `idx_post_user_id` (`user_id`),
    -- 查某家门店下的所有笔记（门店详情页-探店内容 Tab）
    KEY `idx_post_shop_id` (`shop_id`),
    -- 按状态 + 时间查询（Feed 流或后台审核列表），status 低基数放前利用前缀特性
    KEY `idx_post_status_created` (`status`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '探店笔记表';


-- -------------------------------------------------------
-- 2. comment 表 —— 笔记评论
-- -------------------------------------------------------
-- 用途：用户对探店笔记的评论，支持一级评论（当前阶段不做嵌套回复）。
--
-- 嵌套回复（Reply）说明：
--   `parent_id` 字段预留，当前阶段所有评论 parent_id = 0（顶层评论）。
--   后续如需支持「回复某条评论」，设置 parent_id = 被回复评论 ID 即可。
--   这样设计可以用同一张表存储两级结构，不需要新建 reply 表。
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `comment`
(
    `id`         BIGINT UNSIGNED  NOT NULL COMMENT '评论 ID，雪花算法生成',
    `post_id`    BIGINT UNSIGNED  NOT NULL COMMENT '所属笔记 ID，逻辑外键 -> post.id',
    `user_id`    BIGINT UNSIGNED  NOT NULL COMMENT '评论者用户 ID，逻辑外键 -> user.id',
    `parent_id`  BIGINT UNSIGNED  NOT NULL DEFAULT 0 COMMENT '父评论 ID，0 表示顶层评论（一级评论），非 0 表示回复某条评论',
    `content`    VARCHAR(512)     NOT NULL COMMENT '评论内容，最多 512 字符',
    `deleted`    TINYINT(1)       NOT NULL DEFAULT 0 COMMENT '逻辑删除：0 正常 / 1 已删除（被用户或管理员删除）',
    `created_at` DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '评论时间',
    `updated_at` DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '最后更新时间',
    PRIMARY KEY (`id`),
    -- 查某篇笔记下的评论列表（最高频查询）
    KEY `idx_comment_post_id` (`post_id`),
    -- 查某用户发出的所有评论（用户中心 / 后台审核）
    KEY `idx_comment_user_id` (`user_id`),
    -- 联合索引：按笔记 + 创建时间排序，支持高效分页（覆盖 idx_comment_post_id）
    KEY `idx_comment_post_created` (`post_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '笔记评论表';


-- -------------------------------------------------------
-- 3. follow_relation 表 —— 关注关系（用户 → 用户）
-- -------------------------------------------------------
-- 用途：记录「用户 A 关注了用户 B」的关系。
--   follower_user_id：关注者（主动方，「粉丝」视角）
--   followed_user_id：被关注者（「博主」视角）
--
-- 设计决策：只存「用户关注用户」的关系。
--   ER 图中曾提到 target_type 多态关联（用户也可以关注商家），
--   但当前阶段只实现「用户关注用户」，多态扩展留后续版本。
--   好处：表结构更简单，查询效率更高，面试时可以说「当前先做单一场景，预留多态扩展」。
--
-- 唯一约束 uk_follow 防止重复关注（db 层兜底，Service 层也会提前校验）。
--
-- 关注/取关时不走逻辑删除，直接 INSERT / DELETE：
--   原因：关注关系变化频繁，逻辑删除会积累大量历史记录，占用存储空间。
--   如需关注历史统计，后续用 Canal 同步到数仓，不在业务库保留。
-- -------------------------------------------------------
CREATE TABLE IF NOT EXISTS `follow_relation`
(
    `id`               BIGINT UNSIGNED  NOT NULL COMMENT '关注关系 ID，雪花算法生成',
    `follower_user_id` BIGINT UNSIGNED  NOT NULL COMMENT '关注者 ID（粉丝），逻辑外键 -> user.id',
    `followed_user_id` BIGINT UNSIGNED  NOT NULL COMMENT '被关注者 ID（博主），逻辑外键 -> user.id',
    `created_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '关注时间',
    PRIMARY KEY (`id`),
    -- 业务唯一约束：同一用户不能重复关注同一人
    UNIQUE KEY `uk_follow` (`follower_user_id`, `followed_user_id`),
    -- 正向查询：我关注了哪些人（用于 Feed 流拉取、我的关注列表）
    KEY `idx_follower` (`follower_user_id`),
    -- 反向查询：哪些人关注了我（粉丝列表）
    KEY `idx_followed` (`followed_user_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
    COMMENT = '用户关注关系表';
