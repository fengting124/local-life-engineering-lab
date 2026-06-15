#!/usr/bin/env bash
# =======================================================
# LocalLife 业务演示数据模拟脚本
#
# 用途：
#   - 为面试演示准备一组可复现的门店、用户、订单、支付、券、异常数据。
#   - 支持 LocalLife Server、MCP Server、Agent 共同读取同一批 demo 数据。
#
# 运行：
#   bash scripts/business-simulate.sh
#
# 可调环境变量：
#   MYSQL_CONTAINER=local-life-mysql
#   REDIS_CONTAINER=local-life-redis
#   MYSQL_PWD=123456
#   DB=local_life
#   REDIS_DB=0
#
# 关键演示 ID：
#   商家用户: 880000000001
#   商家 ID : 880000100001
#   门店 ID : 880000200001
#   正常订单: 202606100001
#   异常订单: 202606100002  支付 SUCCESS 但订单 WAIT_PAY
#   券异常单: 202606100003  订单 PAID + 支付 SUCCESS + 券 UNUSED
# =======================================================
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-local-life-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-local-life-redis}"
MYSQL_PWD="${MYSQL_PWD:-123456}"
DB="${DB:-local_life}"
REDIS_DB="${REDIS_DB:-0}"

check_container() {
    local name="$1"
    if ! docker ps --format '{{.Names}}' | grep -qx "$name"; then
        echo "ERROR: 容器 '$name' 没在运行。请先启动开发环境："
        echo "       cd infra && docker compose -f docker-compose.dev.yml up -d"
        exit 1
    fi
}

echo "==> [0/3] 检查 MySQL / Redis 容器"
check_container "$MYSQL_CONTAINER"
check_container "$REDIS_CONTAINER"

echo "==> [1/3] 写入 MySQL demo 业务数据"
docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_PWD" "$DB" <<'SQL'
USE `local_life`;

SET @merchant_user_id := 880000000001;
SET @buyer_user_id    := 880000000101;
SET @abnormal_user_id := 880000000102;
SET @coupon_user_id   := 880000000103;
SET @merchant_id      := 880000100001;
SET @shop_id          := 880000200001;
SET @coupon_id        := 880000300001;
SET @session_id       := 880000400001;
SET @user_coupon_id   := 880000500001;
SET @post_id          := 880000600001;
SET @comment_id       := 880000700001;

-- 1. 用户、商家、门店、内容
INSERT INTO `user` (`id`, `mobile`, `nickname`, `avatar`, `bio`, `status`, `deleted`)
VALUES
    (@merchant_user_id, '18800000001', 'demo_商家_西湖茶餐厅', '', 'LocalLife demo merchant', 'ENABLED', 0),
    (@buyer_user_id,    '18800000101', 'demo_正常买家',       '', 'paid order buyer',          'ENABLED', 0),
    (@abnormal_user_id, '18800000102', 'demo_支付异常买家',   '', 'payment callback anomaly',  'ENABLED', 0),
    (@coupon_user_id,   '18800000103', 'demo_券异常买家',     '', 'coupon issue anomaly',      'ENABLED', 0)
ON DUPLICATE KEY UPDATE
    `nickname` = VALUES(`nickname`),
    `status` = 'ENABLED',
    `deleted` = 0;

INSERT INTO `merchant`
    (`id`, `user_id`, `merchant_name`, `logo`, `description`, `contact_mobile`, `status`, `deleted`)
VALUES
    (@merchant_id, @merchant_user_id, 'LocalLife Demo 餐饮商家', '',
     '用于面试演示的餐饮商家，覆盖订单、支付、券和 Agent 排查链路。',
     '18800000001', 'APPROVED', 0)
ON DUPLICATE KEY UPDATE
    `merchant_name` = VALUES(`merchant_name`),
    `status` = 'APPROVED',
    `deleted` = 0;

INSERT INTO `shop`
    (`id`, `merchant_id`, `shop_name`, `category_id`, `cover_image`, `description`,
     `address`, `longitude`, `latitude`, `phone`, `business_hours`, `score`, `status`, `deleted`)
VALUES
    (@shop_id, @merchant_id, '西湖边边茶餐厅 Demo 店', 1, '',
     '演示用门店：适合展示缓存查询、交易订单、商家经营指标和 Agent 排查。',
     '浙江省杭州市西湖区文三路 138 号', 120.1551000, 30.2741000,
     '0571-88000001', '周一至周日 10:00-22:00', 4.8, 'ONLINE', 0)
ON DUPLICATE KEY UPDATE
    `shop_name` = VALUES(`shop_name`),
    `description` = VALUES(`description`),
    `status` = 'ONLINE',
    `deleted` = 0;

INSERT INTO `post`
    (`id`, `user_id`, `shop_id`, `title`, `content`, `images`, `like_count`, `comment_count`, `status`, `deleted`)
VALUES
    (@post_id, @buyer_user_id, @shop_id, '西湖边边茶餐厅体验',
     '环境安静，套餐出餐稳定，适合演示门店详情、笔记和搜索链路。',
     '[]', 18, 1, 'PUBLISHED', 0)
ON DUPLICATE KEY UPDATE
    `title` = VALUES(`title`),
    `content` = VALUES(`content`),
    `status` = 'PUBLISHED',
    `deleted` = 0;

INSERT INTO `comment` (`id`, `post_id`, `user_id`, `parent_id`, `content`, `deleted`)
VALUES
    (@comment_id, @post_id, @abnormal_user_id, 0, '这家店的套餐不错，支付异常处理也很快。', 0)
ON DUPLICATE KEY UPDATE
    `content` = VALUES(`content`),
    `deleted` = 0;

-- 2. 优惠券和秒杀场次
INSERT INTO `coupon_template`
    (`id`, `shop_id`, `coupon_name`, `discount_type`, `discount_value`,
     `min_order_amount`, `total_stock`, `remain_stock`, `per_user_limit`, `valid_days`, `status`, `deleted`)
VALUES
    (@coupon_id, @shop_id, 'Demo 满100减20券', 'CASH', 2000,
     10000, 1000, 996, 1, 7, 'ACTIVE', 0)
ON DUPLICATE KEY UPDATE
    `coupon_name` = VALUES(`coupon_name`),
    `remain_stock` = VALUES(`remain_stock`),
    `status` = 'ACTIVE',
    `deleted` = 0;

INSERT INTO `seckill_session`
    (`id`, `coupon_template_id`, `seckill_stock`, `begin_time`, `end_time`, `session_status`, `deleted`)
VALUES
    (@session_id, @coupon_id, 1000, NOW() - INTERVAL 1 DAY, NOW() + INTERVAL 30 DAY, 'ACTIVE', 0)
ON DUPLICATE KEY UPDATE
    `seckill_stock` = VALUES(`seckill_stock`),
    `begin_time` = VALUES(`begin_time`),
    `end_time` = VALUES(`end_time`),
    `session_status` = 'ACTIVE',
    `deleted` = 0;

INSERT INTO `user_coupon`
    (`id`, `user_id`, `coupon_template_id`, `seckill_session_id`, `coupon_status`,
     `received_at`, `expire_at`, `used_at`, `deleted`)
VALUES
    (@user_coupon_id, @coupon_user_id, @coupon_id, @session_id, 'UNUSED',
     NOW() - INTERVAL 2 HOUR, NOW() + INTERVAL 7 DAY, NULL, 0)
ON DUPLICATE KEY UPDATE
    `coupon_status` = 'UNUSED',
    `received_at` = VALUES(`received_at`),
    `expire_at` = VALUES(`expire_at`),
    `deleted` = 0;

-- 3. 三类订单：正常、支付回调异常、券发放/核销异常
CREATE TEMPORARY TABLE IF NOT EXISTS demo_orders (
    id BIGINT UNSIGNED NOT NULL,
    order_no VARCHAR(32) NOT NULL,
    user_id BIGINT UNSIGNED NOT NULL,
    shop_id BIGINT UNSIGNED NOT NULL,
    coupon_template_id BIGINT UNSIGNED NULL,
    user_coupon_id BIGINT UNSIGNED NULL,
    original_amount INT UNSIGNED NOT NULL,
    coupon_discount INT UNSIGNED NOT NULL,
    order_amount INT UNSIGNED NOT NULL,
    order_status VARCHAR(16) NOT NULL,
    remark VARCHAR(256) NOT NULL,
    expire_at DATETIME NOT NULL,
    pay_at DATETIME NULL
);

TRUNCATE TABLE demo_orders;
INSERT INTO demo_orders VALUES
    (880001000001, '202606100001', @buyer_user_id,    @shop_id, NULL,       NULL,            12800, 0,    12800, 'PAID',     'demo 正常支付订单',                 NOW() + INTERVAL 30 MINUTE, NOW() - INTERVAL 1 HOUR),
    (880001000002, '202606100002', @abnormal_user_id, @shop_id, NULL,       NULL,             9900, 0,     9900, 'WAIT_PAY', 'demo 支付成功但订单仍待支付',        NOW() + INTERVAL 30 MINUTE, NULL),
    (880001000003, '202606100003', @coupon_user_id,   @shop_id, @coupon_id, @user_coupon_id, 15800, 2000, 13800, 'PAID',     'demo 已支付但优惠券仍未核销/需排查', NOW() + INTERVAL 30 MINUTE, NOW() - INTERVAL 30 MINUTE);

INSERT INTO `order_info`
    (`id`, `order_no`, `user_id`, `shop_id`, `coupon_template_id`, `user_coupon_id`,
     `original_amount`, `coupon_discount`, `order_amount`, `order_status`, `remark`, `expire_at`, `pay_at`, `deleted`)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM demo_orders
ON DUPLICATE KEY UPDATE
    `order_status` = VALUES(`order_status`),
    `remark` = VALUES(`remark`),
    `pay_at` = VALUES(`pay_at`),
    `deleted` = 0;

INSERT INTO `order_info_1`
    (`id`, `order_no`, `user_id`, `shop_id`, `coupon_template_id`, `user_coupon_id`,
     `original_amount`, `coupon_discount`, `order_amount`, `order_status`, `remark`, `expire_at`, `pay_at`, `deleted`)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM demo_orders WHERE MOD(user_id, 4) = 1
ON DUPLICATE KEY UPDATE
    `order_status` = VALUES(`order_status`),
    `remark` = VALUES(`remark`),
    `pay_at` = VALUES(`pay_at`),
    `deleted` = 0;

INSERT INTO `order_info_2`
    (`id`, `order_no`, `user_id`, `shop_id`, `coupon_template_id`, `user_coupon_id`,
     `original_amount`, `coupon_discount`, `order_amount`, `order_status`, `remark`, `expire_at`, `pay_at`, `deleted`)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM demo_orders WHERE MOD(user_id, 4) = 2
ON DUPLICATE KEY UPDATE
    `order_status` = VALUES(`order_status`),
    `remark` = VALUES(`remark`),
    `pay_at` = VALUES(`pay_at`),
    `deleted` = 0;

INSERT INTO `order_info_3`
    (`id`, `order_no`, `user_id`, `shop_id`, `coupon_template_id`, `user_coupon_id`,
     `original_amount`, `coupon_discount`, `order_amount`, `order_status`, `remark`, `expire_at`, `pay_at`, `deleted`)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM demo_orders WHERE MOD(user_id, 4) = 3
ON DUPLICATE KEY UPDATE
    `order_status` = VALUES(`order_status`),
    `remark` = VALUES(`remark`),
    `pay_at` = VALUES(`pay_at`),
    `deleted` = 0;

INSERT INTO `order_info_0`
    (`id`, `order_no`, `user_id`, `shop_id`, `coupon_template_id`, `user_coupon_id`,
     `original_amount`, `coupon_discount`, `order_amount`, `order_status`, `remark`, `expire_at`, `pay_at`, `deleted`)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM demo_orders WHERE MOD(user_id, 4) = 0
ON DUPLICATE KEY UPDATE
    `order_status` = VALUES(`order_status`),
    `remark` = VALUES(`remark`),
    `pay_at` = VALUES(`pay_at`),
    `deleted` = 0;

INSERT INTO `payment_order`
    (`id`, `payment_no`, `order_id`, `order_no`, `user_id`, `pay_amount`, `paid_amount`,
     `pay_status`, `channel`, `trade_no`, `callback_body`, `paid_at`)
VALUES
    (880002000001, 'PAY202606100001', 880001000001, '202606100001', @buyer_user_id,
     12800, 12800, 'SUCCESS', 'MOCK', 'TRADE202606100001', '{"demo":"normal_paid"}', NOW() - INTERVAL 1 HOUR),
    (880002000002, 'PAY202606100002', 880001000002, '202606100002', @abnormal_user_id,
      9900,  9900, 'SUCCESS', 'MOCK', 'TRADE202606100002', '{"demo":"callback_success_order_wait_pay"}', NOW() - INTERVAL 20 MINUTE),
    (880002000003, 'PAY202606100003', 880001000003, '202606100003', @coupon_user_id,
     13800, 13800, 'SUCCESS', 'MOCK', 'TRADE202606100003', '{"demo":"coupon_issue_check"}', NOW() - INTERVAL 30 MINUTE)
ON DUPLICATE KEY UPDATE
    `pay_status` = 'SUCCESS',
    `paid_amount` = VALUES(`paid_amount`),
    `callback_body` = VALUES(`callback_body`),
    `paid_at` = VALUES(`paid_at`);

-- 4. Outbox 异常样本：模拟支付成功事件投递失败，便于讲最终一致性和死信恢复
INSERT INTO `outbox_message`
    (`id`, `event_id`, `topic`, `tag`, `payload`, `status`, `retry_count`, `auto_retry_count`, `next_retry_at`)
VALUES
    (880003000001, 'EVT202606100002', 'payment-success-topic', 'PAYMENT_SUCCESS',
     '{"orderNo":"202606100002","paymentNo":"PAY202606100002","reason":"demo callback processed but downstream event failed"}',
     'FAILED', 3, 1, NOW() - INTERVAL 10 MINUTE)
ON DUPLICATE KEY UPDATE
    `status` = 'FAILED',
    `retry_count` = 3,
    `auto_retry_count` = 1,
    `payload` = VALUES(`payload`),
    `next_retry_at` = VALUES(`next_retry_at`);

SELECT 'demo users' AS item, COUNT(*) AS cnt FROM `user` WHERE `id` IN (@merchant_user_id, @buyer_user_id, @abnormal_user_id, @coupon_user_id)
UNION ALL
SELECT 'demo shop', COUNT(*) FROM `shop` WHERE `id` = @shop_id
UNION ALL
SELECT 'demo orders logical', COUNT(*) FROM `order_info` WHERE `order_no` IN ('202606100001','202606100002','202606100003')
UNION ALL
SELECT 'demo payments', COUNT(*) FROM `payment_order` WHERE `order_no` IN ('202606100001','202606100002','202606100003')
UNION ALL
SELECT 'demo failed outbox', COUNT(*) FROM `outbox_message` WHERE `event_id` = 'EVT202606100002';
SQL

echo "==> [2/3] 写入 Redis demo 键"
{
    echo "SET login:code:18800000001 123456 EX 1800"
    echo "SET login:code:18800000101 123456 EX 1800"
    echo "SET login:code:18800000102 123456 EX 1800"
    echo "SET login:code:18800000103 123456 EX 1800"
    echo "SET seckill:stock:880000400001:880000300001 1000"
    echo "DEL seckill:user:880000400001:880000300001"
    echo "DEL seckill:result:880000400001:880000300001:880000000101"
    echo "DEL seckill:result:880000400001:880000300001:880000000102"
} | docker exec -i "$REDIS_CONTAINER" redis-cli -n "$REDIS_DB" > /dev/null

echo "==> [3/3] 完成"
cat <<'EOF'

✅ 业务演示数据已准备好。

可演示数据：
  商家用户: 880000000001 / mobile=18800000001 / code=123456
  商家 ID : 880000100001
  门店 ID : 880000200001

  正常订单: 202606100001
  异常订单: 202606100002  支付 SUCCESS 但订单 WAIT_PAY
  券异常单: 202606100003  订单 PAID + 支付 SUCCESS + 券 UNUSED

MCP 查询示例：
  curl -s http://localhost:8081/mcp \
    -H 'Content-Type: application/json' \
    -H 'X-User-Id: 880000000001' \
    -H 'X-User-Role: merchant' \
    -H 'X-Merchant-Id: 880000100001' \
    -d '{"jsonrpc":"2.0","id":"demo","method":"tools/call","params":{"name":"query_order","arguments":{"order_id":"202606100002"}}}'

Agent 演示问题：
  帮我排查订单 202606100002 为什么用户支付成功但订单还显示待支付，并给出处理建议
EOF
