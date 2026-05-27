-- =============================================================
-- V1__init_user.sql
-- 用户表初始化脚本
--
-- 命名规范：V{版本号}__{描述}.sql
-- 本项目暂不引入 Flyway（后续可接入），先用手动脚本管理 DDL。
-- 执行方式：首次搭建环境时在 MySQL 中手动执行此文件。
-- =============================================================

-- 创建数据库（如不存在）
CREATE DATABASE IF NOT EXISTS `local_life`
    DEFAULT CHARACTER SET utf8mb4
    DEFAULT COLLATE utf8mb4_unicode_ci;

USE `local_life`;

-- =============================================================
-- 用户表 user
--
-- 设计说明：
--   1. 主键 id 使用雪花算法（MyBatis-Plus 的 ASSIGN_ID 策略），
--      BIGINT UNSIGNED，不使用 AUTO_INCREMENT。
--      原因：雪花 ID 趋势递增，适合 B+ 树索引；
--            分布式环境下不依赖数据库自增，扩展性更好。
--
--   2. 手机号 mobile 设为唯一索引（UK），是用户的登录标识。
--      mobile 不存在 status 字段里，物理上就不允许重复注册。
--
--   3. 状态字段 status 使用 TINYINT：
--      0 = ENABLED（正常），1 = DISABLED（被禁用）
--      使用 TINYINT 而不是 VARCHAR 的原因：节省空间、查询更快、
--      状态值有限（枚举场景）。
--      代码层面用 String 枚举（"ENABLED"/"DISABLED"）映射，
--      数据库层面用数字，两者在 MyBatis-Plus 中通过 @EnumValue 或 TypeHandler 转换。
--      当前简化处理：直接存 VARCHAR，避免引入 Enum 转换复杂度。
--
--   4. deleted 是逻辑删除字段（soft delete）：
--      0 = 未删除，1 = 已删除。
--      MyBatis-Plus 全局配置了逻辑删除，所有查询自动追加 WHERE deleted = 0。
--      原因：用户数据不能物理删除（需要保留关联的订单、笔记等历史记录）。
--
--   5. created_at / updated_at 使用 DATETIME(0)（精确到秒）。
--      不使用 TIMESTAMP 的原因：TIMESTAMP 有 2038 年溢出问题；
--      DATETIME 存储时不做时区转换，应用层统一处理时区。
-- =============================================================
CREATE TABLE IF NOT EXISTS `user`
(
    -- 主键：雪花算法生成的 BIGINT，不使用 AUTO_INCREMENT
    `id`          BIGINT UNSIGNED    NOT NULL COMMENT '用户ID，雪花算法',

    -- 手机号：登录唯一标识，存明文（生产环境可考虑加密存储）
    `mobile`      VARCHAR(11)        NOT NULL COMMENT '手机号，登录唯一标识',

    -- 昵称：初始值由系统生成（user_{userId 后6位}），用户可修改
    `nickname`    VARCHAR(32)        NOT NULL DEFAULT '' COMMENT '用户昵称',

    -- 头像：存 URL，初始为空（前端展示默认头像）
    `avatar`      VARCHAR(256)       NOT NULL DEFAULT '' COMMENT '头像图片URL',

    -- 个人简介：可选，最多 128 个字符
    `bio`         VARCHAR(128)       NOT NULL DEFAULT '' COMMENT '个人简介',

    -- 账号状态：ENABLED = 正常，DISABLED = 被禁用
    -- 使用 VARCHAR 方便阅读，同时与 LoginUserDTO.status 直接对应，无需转换
    `status`      VARCHAR(16)        NOT NULL DEFAULT 'ENABLED' COMMENT '账号状态：ENABLED/DISABLED',

    -- 逻辑删除：MyBatis-Plus 全局配置，0=未删除，1=已删除
    `deleted`     TINYINT UNSIGNED   NOT NULL DEFAULT 0 COMMENT '逻辑删除：0未删除，1已删除',

    -- 记录创建时间，由 MyBatis-Plus 的 @TableField(fill = INSERT) 自动填充
    `created_at`  DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP COMMENT '创建时间',

    -- 记录最后更新时间，由 MyBatis-Plus 的 @TableField(fill = INSERT_UPDATE) 自动填充
    `updated_at`  DATETIME           NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP COMMENT '更新时间',

    PRIMARY KEY (`id`),

    -- 唯一索引：手机号全局唯一，防止重复注册
    -- 注意：逻辑删除后手机号仍占用唯一索引，这是预期行为
    --       （防止注销后同手机号被其他人重新注册，导致历史数据关联混乱）
    UNIQUE KEY `uk_user_mobile` (`mobile`)

) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '用户表';
