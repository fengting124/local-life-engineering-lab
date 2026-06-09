-- =======================================================
-- 压测数据准备（MySQL 部分）
-- 配合 performance-tests/locustfile_locallife_server.py 的 SeckillUser 场景。
--
-- 造三类数据：
--   1. 2000 个测试用户（手机号 13900000000 ~ 13900001999）
--      —— 登录其实会「不存在则自动注册」，但 2000 并发登录同时触发注册写入有竞争，
--         预先 seed 好更干净、更快。
--   2. 2 个 coupon_template（id=1 / id=2）
--   3. 2 个 seckill_session（id=1→模板1，id=2→模板2，均 ACTIVE，时间窗覆盖一年）
--      —— 注意 seckill_session.coupon_template_id 是「一场次对一模板」，
--         所以 Locust 的 SECKILL_SESSIONS 必须用合法配对 (1,1) / (2,2)。
--
-- 幂等：可重复执行（INSERT IGNORE / ON DUPLICATE KEY UPDATE）。
--
-- 执行：
--   docker exec -i local-life-mysql mysql -uroot -p123456 local_life < scripts/seed-perf-data.sql
-- 或直接用 scripts/seed-perf-data.sh（连 Redis 预热一起做）。
-- =======================================================

USE `local_life`;

-- 递归 CTE 默认最大深度 1000，造 2000 行要调高
SET SESSION cte_max_recursion_depth = 5000;

-- -------------------------------------------------------
-- 1. 2000 个测试用户
--    id 用 9_000_000_000 + n，与雪花算法生成的真实 ID（~19 位）不冲突
-- -------------------------------------------------------
INSERT IGNORE INTO `user` (`id`, `mobile`, `nickname`, `status`)
WITH RECURSIVE seq(n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < 1999
)
SELECT 9000000000 + n,
       CAST(13900000000 + n AS CHAR),
       CONCAT('perf_user_', n),
       'ENABLED'
FROM seq;

-- -------------------------------------------------------
-- 2. 2 个优惠券模板
--    discount_value：CASH 单位为分（2000=20元）；PERCENT 单位为百分比（80=8折）
--    total_stock / remain_stock 设大值，DB 这层不是瓶颈（真实库存看 Redis）
-- -------------------------------------------------------
INSERT INTO `coupon_template`
    (`id`, `shop_id`, `coupon_name`, `discount_type`, `discount_value`,
     `min_order_amount`, `total_stock`, `remain_stock`, `per_user_limit`, `valid_days`, `status`)
VALUES
    (1, 1, '压测券A-满100减20', 'CASH',    2000, 0, 1000000, 1000000, 1, 7, 'ACTIVE'),
    (2, 2, '压测券B-8折',       'PERCENT',   80, 0, 1000000, 1000000, 1, 7, 'ACTIVE')
ON DUPLICATE KEY UPDATE
    `status` = 'ACTIVE',
    `remain_stock` = VALUES(`remain_stock`),
    `coupon_name` = VALUES(`coupon_name`);

-- -------------------------------------------------------
-- 3. 2 个秒杀场次（ACTIVE，时间窗：昨天 ~ 一年后，保证长期可压测）
--    session 1 → 模板 1，session 2 → 模板 2
--    seckill_stock 仅作记录；Lua 实际读的是 Redis 的 seckill:stock:{sid}:{tid}
-- -------------------------------------------------------
INSERT INTO `seckill_session`
    (`id`, `coupon_template_id`, `seckill_stock`, `begin_time`, `end_time`, `session_status`)
VALUES
    (1, 1, 1000000, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 365 DAY, 'ACTIVE'),
    (2, 2, 1000000, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 365 DAY, 'ACTIVE')
ON DUPLICATE KEY UPDATE
    `session_status` = 'ACTIVE',
    `coupon_template_id` = VALUES(`coupon_template_id`),
    `begin_time` = VALUES(`begin_time`),
    `end_time` = VALUES(`end_time`);

-- 结果自检
SELECT '已 seed 用户数' AS item, COUNT(*) AS cnt FROM `user` WHERE `mobile` LIKE '139000%'
UNION ALL
SELECT '可抢券模板数', COUNT(*) FROM `coupon_template` WHERE `id` IN (1, 2)
UNION ALL
SELECT 'ACTIVE 场次数', COUNT(*) FROM `seckill_session` WHERE `id` IN (1, 2) AND `session_status` = 'ACTIVE';
