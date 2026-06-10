#!/usr/bin/env bash
# =======================================================
# LocalLife 批量业务数据模拟脚本
#
# 用途：
#   - 为本地真实链路准备 synthetic business data。
#   - 模拟用户、商家、门店、券、秒杀场次、订单、支付、outbox 异常分布。
#   - 支持 Server / MCP / Agent 查询同一批 MySQL + Redis 数据。
#
# 运行：
#   bash scripts/business-bulk-simulate.sh
#
# 常用参数：
#   USER_COUNT=2000 MERCHANT_COUNT=50 SHOPS_PER_MERCHANT=2 ORDER_COUNT=10000 \
#     bash scripts/business-bulk-simulate.sh
#
# 可调环境变量：
#   MYSQL_CONTAINER=local-life-mysql
#   REDIS_CONTAINER=local-life-redis
#   MYSQL_PWD=123456
#   DB=local_life
#   REDIS_DB=0
#   RESET_BULK=1                # 1=先清理本脚本生成的数据，0=只 upsert
#   USER_COUNT=2000
#   MERCHANT_COUNT=50
#   SHOPS_PER_MERCHANT=2
#   COUPON_COUNT=100
#   ORDER_COUNT=10000
#   ANOMALY_PER_THOUSAND=20     # 20=约 2% 支付成功但订单仍 WAIT_PAY
#   SECKILL_STOCK=1000
#
# 说明：
#   这里生成的是 synthetic data，不使用真实用户隐私数据。
#   脚本直连 MySQL/Redis 容器，适合本地/预发演示，不应该放进应用启动流程。
# =======================================================
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-local-life-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-local-life-redis}"
MYSQL_PWD="${MYSQL_PWD:-123456}"
DB="${DB:-local_life}"
REDIS_DB="${REDIS_DB:-0}"
RESET_BULK="${RESET_BULK:-1}"

USER_COUNT="${USER_COUNT:-2000}"
MERCHANT_COUNT="${MERCHANT_COUNT:-50}"
SHOPS_PER_MERCHANT="${SHOPS_PER_MERCHANT:-2}"
COUPON_COUNT="${COUPON_COUNT:-100}"
ORDER_COUNT="${ORDER_COUNT:-10000}"
ANOMALY_PER_THOUSAND="${ANOMALY_PER_THOUSAND:-20}"
SECKILL_STOCK="${SECKILL_STOCK:-1000}"

USER_BASE=881000000000
MERCHANT_BASE=881100000000
SHOP_BASE=881200000000
COUPON_BASE=881300000000
SESSION_BASE=881400000000
USER_COUPON_BASE=881500000000
ORDER_BASE=881600000000
PAYMENT_BASE=881700000000
OUTBOX_BASE=881800000000
RANGE_SIZE=99999999

check_container() {
    local name="$1"
    if ! docker ps --format '{{.Names}}' | grep -qx "$name"; then
        echo "ERROR: 容器 '$name' 没在运行。请先启动开发环境："
        echo "       cd infra && docker compose -f docker-compose.dev.yml --profile app up -d"
        exit 1
    fi
}

require_positive() {
    local name="$1"
    local value="$2"
    if ! [[ "$value" =~ ^[0-9]+$ ]] || [ "$value" -le 0 ]; then
        echo "ERROR: $name 必须是正整数，当前值：$value"
        exit 1
    fi
}

for pair in \
    "USER_COUNT:$USER_COUNT" \
    "MERCHANT_COUNT:$MERCHANT_COUNT" \
    "SHOPS_PER_MERCHANT:$SHOPS_PER_MERCHANT" \
    "COUPON_COUNT:$COUPON_COUNT" \
    "ORDER_COUNT:$ORDER_COUNT" \
    "SECKILL_STOCK:$SECKILL_STOCK"; do
    require_positive "${pair%%:*}" "${pair#*:}"
done

if ! [[ "$ANOMALY_PER_THOUSAND" =~ ^[0-9]+$ ]] || [ "$ANOMALY_PER_THOUSAND" -gt 1000 ]; then
    echo "ERROR: ANOMALY_PER_THOUSAND 必须是 0..1000 的整数，当前值：$ANOMALY_PER_THOUSAND"
    exit 1
fi

SHOP_COUNT=$((MERCHANT_COUNT * SHOPS_PER_MERCHANT))
if [ "$COUPON_COUNT" -gt "$SHOP_COUNT" ]; then
    echo "ERROR: COUPON_COUNT 不能大于门店数 SHOP_COUNT=$SHOP_COUNT，否则券会引用不存在门店。"
    exit 1
fi

echo "==> [0/4] 检查 MySQL / Redis 容器"
check_container "$MYSQL_CONTAINER"
check_container "$REDIS_CONTAINER"

echo "==> [1/4] 写入 MySQL 批量业务数据"
docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_PWD" "$DB" <<SQL
USE \`${DB}\`;

SET SESSION cte_max_recursion_depth = GREATEST(${ORDER_COUNT}, ${USER_COUNT}, ${SHOP_COUNT}, ${COUPON_COUNT}) + 10;

SET @user_base := ${USER_BASE};
SET @merchant_base := ${MERCHANT_BASE};
SET @shop_base := ${SHOP_BASE};
SET @coupon_base := ${COUPON_BASE};
SET @session_base := ${SESSION_BASE};
SET @user_coupon_base := ${USER_COUPON_BASE};
SET @order_base := ${ORDER_BASE};
SET @payment_base := ${PAYMENT_BASE};
SET @outbox_base := ${OUTBOX_BASE};
SET @range_size := ${RANGE_SIZE};
SET @user_count := ${USER_COUNT};
SET @merchant_count := ${MERCHANT_COUNT};
SET @shops_per_merchant := ${SHOPS_PER_MERCHANT};
SET @shop_count := ${SHOP_COUNT};
SET @coupon_count := ${COUPON_COUNT};
SET @order_count := ${ORDER_COUNT};
SET @anomaly_per_thousand := ${ANOMALY_PER_THOUSAND};
SET @reset_bulk := ${RESET_BULK};

DROP TEMPORARY TABLE IF EXISTS bulk_seq;
CREATE TEMPORARY TABLE bulk_seq (
    n INT UNSIGNED NOT NULL PRIMARY KEY
) ENGINE = MEMORY;

INSERT INTO bulk_seq (n)
WITH RECURSIVE seq(n) AS (
    SELECT 0
    UNION ALL
    SELECT n + 1 FROM seq WHERE n < GREATEST(@user_count, @merchant_count, @shop_count, @coupon_count, @order_count) - 1
)
SELECT n FROM seq;

SET FOREIGN_KEY_CHECKS = 0;

-- 可重复运行：默认清理本脚本 ID 段的数据，不影响 demo/perf/人工数据。
DELETE FROM payment_order WHERE @reset_bulk = 1 AND id BETWEEN @payment_base AND @payment_base + @range_size;
DELETE FROM outbox_message WHERE @reset_bulk = 1 AND id BETWEEN @outbox_base AND @outbox_base + @range_size;
DELETE FROM order_info WHERE @reset_bulk = 1 AND id BETWEEN @order_base AND @order_base + @range_size;
DELETE FROM order_info_0 WHERE @reset_bulk = 1 AND id BETWEEN @order_base AND @order_base + @range_size;
DELETE FROM order_info_1 WHERE @reset_bulk = 1 AND id BETWEEN @order_base AND @order_base + @range_size;
DELETE FROM order_info_2 WHERE @reset_bulk = 1 AND id BETWEEN @order_base AND @order_base + @range_size;
DELETE FROM order_info_3 WHERE @reset_bulk = 1 AND id BETWEEN @order_base AND @order_base + @range_size;
DELETE FROM user_coupon WHERE @reset_bulk = 1 AND id BETWEEN @user_coupon_base AND @user_coupon_base + @range_size;
DELETE FROM seckill_session WHERE @reset_bulk = 1 AND id BETWEEN @session_base AND @session_base + @range_size;
DELETE FROM coupon_template WHERE @reset_bulk = 1 AND id BETWEEN @coupon_base AND @coupon_base + @range_size;
DELETE FROM shop WHERE @reset_bulk = 1 AND id BETWEEN @shop_base AND @shop_base + @range_size;
DELETE FROM merchant WHERE @reset_bulk = 1 AND id BETWEEN @merchant_base AND @merchant_base + @range_size;
DELETE FROM \`user\` WHERE @reset_bulk = 1 AND id BETWEEN @user_base AND @user_base + @range_size;

SET FOREIGN_KEY_CHECKS = 1;

INSERT INTO \`user\`
    (id, mobile, nickname, avatar, bio, status, deleted)
SELECT
    @user_base + n,
    CAST(17700000000 + n AS CHAR),
    CONCAT('bulk_user_', LPAD(n, 6, '0')),
    '',
    'synthetic local-life user',
    'ENABLED',
    0
FROM bulk_seq
WHERE n < @user_count
ON DUPLICATE KEY UPDATE
    nickname = VALUES(nickname),
    status = 'ENABLED',
    deleted = 0;

INSERT INTO merchant
    (id, user_id, merchant_name, logo, description, contact_mobile, status, deleted)
SELECT
    @merchant_base + n,
    @user_base + n,
    CONCAT('bulk_merchant_', LPAD(n, 4, '0')),
    '',
    'synthetic merchant for bulk business simulation',
    CAST(17780000000 + n AS CHAR),
    'APPROVED',
    0
FROM bulk_seq
WHERE n < @merchant_count
ON DUPLICATE KEY UPDATE
    merchant_name = VALUES(merchant_name),
    status = 'APPROVED',
    deleted = 0;

INSERT INTO shop
    (id, merchant_id, shop_name, category_id, cover_image, description,
     address, longitude, latitude, phone, business_hours, score, status, deleted)
SELECT
    @shop_base + n,
    @merchant_base + FLOOR(n / @shops_per_merchant),
    CONCAT('bulk_shop_', LPAD(n, 4, '0')),
    1 + (n % 6),
    '',
    'synthetic shop for browse/order/payment/agent scenarios',
    CONCAT('Hangzhou Synthetic Street ', n),
    120.1000000 + ((n % 100) / 10000),
    30.2000000 + ((n % 100) / 10000),
    CONCAT('0571-', LPAD(n, 8, '0')),
    '10:00-22:00',
    3.8 + ((n % 12) / 10),
    'ONLINE',
    0
FROM bulk_seq
WHERE n < @shop_count
ON DUPLICATE KEY UPDATE
    shop_name = VALUES(shop_name),
    status = 'ONLINE',
    deleted = 0;

INSERT INTO coupon_template
    (id, shop_id, coupon_name, discount_type, discount_value,
     min_order_amount, total_stock, remain_stock, per_user_limit, valid_days, status, deleted)
SELECT
    @coupon_base + n,
    @shop_base + n,
    CONCAT('bulk_coupon_', LPAD(n, 4, '0')),
    CASE WHEN n % 5 = 0 THEN 'PERCENT' ELSE 'CASH' END,
    CASE WHEN n % 5 = 0 THEN 85 ELSE 500 + (n % 6) * 500 END,
    5000 + (n % 5) * 1000,
    100000,
    100000 - (n % 100),
    1,
    7 + (n % 21),
    'ACTIVE',
    0
FROM bulk_seq
WHERE n < @coupon_count
ON DUPLICATE KEY UPDATE
    coupon_name = VALUES(coupon_name),
    remain_stock = VALUES(remain_stock),
    status = 'ACTIVE',
    deleted = 0;

INSERT INTO seckill_session
    (id, coupon_template_id, seckill_stock, begin_time, end_time, session_status, deleted)
SELECT
    @session_base + n,
    @coupon_base + n,
    ${SECKILL_STOCK},
    NOW() - INTERVAL 1 DAY,
    NOW() + INTERVAL 30 DAY,
    'ACTIVE',
    0
FROM bulk_seq
WHERE n < @coupon_count
ON DUPLICATE KEY UPDATE
    seckill_stock = VALUES(seckill_stock),
    begin_time = VALUES(begin_time),
    end_time = VALUES(end_time),
    session_status = 'ACTIVE',
    deleted = 0;

INSERT INTO user_coupon
    (id, user_id, coupon_template_id, seckill_session_id, coupon_status,
     received_at, expire_at, used_at, deleted)
SELECT
    @user_coupon_base + n,
    @user_base + n,
    @coupon_base + (n % @coupon_count),
    @session_base + (n % @coupon_count),
    CASE
        WHEN n % 10 = 0 THEN 'EXPIRED'
        WHEN n % 3 = 0 THEN 'USED'
        ELSE 'UNUSED'
    END,
    NOW() - INTERVAL (n % 72) HOUR,
    NOW() + INTERVAL (7 + (n % 21)) DAY,
    CASE WHEN n % 3 = 0 THEN NOW() - INTERVAL (n % 48) HOUR ELSE NULL END,
    0
FROM bulk_seq
WHERE n < @user_count
ON DUPLICATE KEY UPDATE
    coupon_status = VALUES(coupon_status),
    received_at = VALUES(received_at),
    expire_at = VALUES(expire_at),
    used_at = VALUES(used_at),
    deleted = 0;

DROP TEMPORARY TABLE IF EXISTS bulk_orders;
CREATE TEMPORARY TABLE bulk_orders AS
SELECT
    @order_base + n AS id,
    CONCAT('BULK', DATE_FORMAT(NOW(), '%Y%m%d'), LPAD(n, 8, '0')) AS order_no,
    @user_base + (n % @user_count) AS user_id,
    @shop_base + (n % @shop_count) AS shop_id,
    CASE WHEN n % 4 = 0 THEN @coupon_base + ((n % @user_count) % @coupon_count) ELSE NULL END AS coupon_template_id,
    CASE WHEN n % 4 = 0 THEN @user_coupon_base + (n % @user_count) ELSE NULL END AS user_coupon_id,
    3000 + (n % 170) * 100 AS original_amount,
    CASE WHEN n % 4 = 0 THEN 500 + (n % 4) * 500 ELSE 0 END AS coupon_discount,
    (3000 + (n % 170) * 100) - CASE WHEN n % 4 = 0 THEN 500 + (n % 4) * 500 ELSE 0 END AS order_amount,
    CASE
        WHEN n % 1000 < @anomaly_per_thousand THEN 'WAIT_PAY'
        WHEN n % 100 < 70 THEN 'PAID'
        WHEN n % 100 < 85 THEN 'COMPLETED'
        WHEN n % 100 < 95 THEN 'WAIT_PAY'
        ELSE 'CANCELLED'
    END AS order_status,
    CASE
        WHEN n % 1000 < @anomaly_per_thousand THEN 'bulk anomaly: payment success but order remains WAIT_PAY'
        ELSE CONCAT('bulk synthetic order ', n)
    END AS remark,
    NOW() + INTERVAL 30 MINUTE AS expire_at,
    CASE
        WHEN n % 1000 < @anomaly_per_thousand THEN NULL
        WHEN n % 100 < 85 THEN NOW() - INTERVAL (n % 720) MINUTE
        ELSE NULL
    END AS pay_at,
    n % 1000 < @anomaly_per_thousand AS is_anomaly
FROM bulk_seq
WHERE n < @order_count;

INSERT INTO order_info
    (id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
     original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, deleted)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM bulk_orders
ON DUPLICATE KEY UPDATE
    order_status = VALUES(order_status),
    remark = VALUES(remark),
    pay_at = VALUES(pay_at),
    deleted = 0;

INSERT INTO order_info_0
    (id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
     original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, deleted)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM bulk_orders WHERE MOD(user_id, 4) = 0
ON DUPLICATE KEY UPDATE order_status = VALUES(order_status), remark = VALUES(remark), pay_at = VALUES(pay_at), deleted = 0;

INSERT INTO order_info_1
    (id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
     original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, deleted)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM bulk_orders WHERE MOD(user_id, 4) = 1
ON DUPLICATE KEY UPDATE order_status = VALUES(order_status), remark = VALUES(remark), pay_at = VALUES(pay_at), deleted = 0;

INSERT INTO order_info_2
    (id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
     original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, deleted)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM bulk_orders WHERE MOD(user_id, 4) = 2
ON DUPLICATE KEY UPDATE order_status = VALUES(order_status), remark = VALUES(remark), pay_at = VALUES(pay_at), deleted = 0;

INSERT INTO order_info_3
    (id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
     original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, deleted)
SELECT id, order_no, user_id, shop_id, coupon_template_id, user_coupon_id,
       original_amount, coupon_discount, order_amount, order_status, remark, expire_at, pay_at, 0
FROM bulk_orders WHERE MOD(user_id, 4) = 3
ON DUPLICATE KEY UPDATE order_status = VALUES(order_status), remark = VALUES(remark), pay_at = VALUES(pay_at), deleted = 0;

INSERT INTO payment_order
    (id, payment_no, order_id, order_no, user_id, pay_amount, paid_amount,
     pay_status, channel, trade_no, callback_body, paid_at)
SELECT
    @payment_base + (id - @order_base),
    CONCAT('BULKPAY', LPAD(id - @order_base, 8, '0')),
    id,
    order_no,
    user_id,
    order_amount,
    CASE
        WHEN is_anomaly OR order_status IN ('PAID', 'COMPLETED') THEN order_amount
        ELSE NULL
    END,
    CASE
        WHEN is_anomaly OR order_status IN ('PAID', 'COMPLETED') THEN 'SUCCESS'
        WHEN order_status = 'CANCELLED' THEN 'FAILED'
        ELSE 'PENDING'
    END,
    'MOCK',
    CASE
        WHEN is_anomaly OR order_status IN ('PAID', 'COMPLETED') THEN CONCAT('BULKTRADE', LPAD(id - @order_base, 8, '0'))
        ELSE NULL
    END,
    CASE
        WHEN is_anomaly THEN '{"bulk":"payment_success_order_wait_pay"}'
        ELSE '{"bulk":"synthetic_payment"}'
    END,
    CASE
        WHEN is_anomaly THEN NOW() - INTERVAL 5 MINUTE
        WHEN order_status IN ('PAID', 'COMPLETED') THEN COALESCE(pay_at, NOW())
        ELSE NULL
    END
FROM bulk_orders
ON DUPLICATE KEY UPDATE
    pay_status = VALUES(pay_status),
    paid_amount = VALUES(paid_amount),
    callback_body = VALUES(callback_body),
    paid_at = VALUES(paid_at);

INSERT INTO outbox_message
    (id, event_id, topic, tag, payload, status, retry_count, auto_retry_count, next_retry_at)
SELECT
    @outbox_base + (id - @order_base),
    CONCAT('BULKEVT', LPAD(id - @order_base, 8, '0')),
    'payment-success-topic',
    'PAYMENT_SUCCESS',
    JSON_OBJECT('orderNo', order_no, 'paymentNo', CONCAT('BULKPAY', LPAD(id - @order_base, 8, '0')), 'bulk', TRUE),
    CASE WHEN is_anomaly THEN 'FAILED' ELSE 'SENT' END,
    CASE WHEN is_anomaly THEN 3 ELSE 0 END,
    CASE WHEN is_anomaly THEN 1 ELSE 0 END,
    CASE WHEN is_anomaly THEN NOW() - INTERVAL 1 MINUTE ELSE NOW() END
FROM bulk_orders
WHERE is_anomaly OR order_status IN ('PAID', 'COMPLETED')
ON DUPLICATE KEY UPDATE
    payload = VALUES(payload),
    status = VALUES(status),
    retry_count = VALUES(retry_count),
    auto_retry_count = VALUES(auto_retry_count),
    next_retry_at = VALUES(next_retry_at);

SELECT 'bulk users' AS item, COUNT(*) AS cnt FROM \`user\` WHERE id BETWEEN @user_base AND @user_base + @range_size
UNION ALL
SELECT 'bulk merchants', COUNT(*) FROM merchant WHERE id BETWEEN @merchant_base AND @merchant_base + @range_size
UNION ALL
SELECT 'bulk shops', COUNT(*) FROM shop WHERE id BETWEEN @shop_base AND @shop_base + @range_size
UNION ALL
SELECT 'bulk coupons', COUNT(*) FROM coupon_template WHERE id BETWEEN @coupon_base AND @coupon_base + @range_size
UNION ALL
SELECT 'bulk logical orders', COUNT(*) FROM order_info WHERE id BETWEEN @order_base AND @order_base + @range_size
UNION ALL
SELECT 'bulk shard orders', (
    SELECT COUNT(*) FROM order_info_0 WHERE id BETWEEN @order_base AND @order_base + @range_size
) + (
    SELECT COUNT(*) FROM order_info_1 WHERE id BETWEEN @order_base AND @order_base + @range_size
) + (
    SELECT COUNT(*) FROM order_info_2 WHERE id BETWEEN @order_base AND @order_base + @range_size
) + (
    SELECT COUNT(*) FROM order_info_3 WHERE id BETWEEN @order_base AND @order_base + @range_size
)
UNION ALL
SELECT 'bulk payments', COUNT(*) FROM payment_order WHERE id BETWEEN @payment_base AND @payment_base + @range_size
UNION ALL
SELECT 'bulk anomaly orders', COUNT(*) FROM order_info o JOIN payment_order p ON p.order_id = o.id
    WHERE o.id BETWEEN @order_base AND @order_base + @range_size
      AND o.order_status = 'WAIT_PAY'
      AND p.pay_status = 'SUCCESS'
UNION ALL
SELECT 'bulk failed outbox', COUNT(*) FROM outbox_message WHERE id BETWEEN @outbox_base AND @outbox_base + @range_size AND status = 'FAILED';
SQL

echo "==> [2/4] 写入 Redis 批量验证码和秒杀库存"
{
    end=$((USER_COUNT - 1))
    for i in $(seq 0 "$end"); do
        mobile=$((17700000000 + i))
        echo "SET login:code:${mobile} 123456 EX 3600"
    done

    coupon_end=$((COUPON_COUNT - 1))
    for i in $(seq 0 "$coupon_end"); do
        session_id=$((SESSION_BASE + i))
        coupon_id=$((COUPON_BASE + i))
        echo "SET seckill:stock:${session_id}:${coupon_id} ${SECKILL_STOCK}"
        echo "DEL seckill:user:${session_id}:${coupon_id}"
    done
} | docker exec -i "$REDIS_CONTAINER" redis-cli -n "$REDIS_DB" --pipe >/dev/null

echo "==> [3/4] 冒烟查询 MCP 可读订单异常"
sample_order_no=$(docker exec -i "$MYSQL_CONTAINER" mysql -N -uroot -p"$MYSQL_PWD" "$DB" -e \
    "SELECT order_no FROM order_info WHERE id BETWEEN ${ORDER_BASE} AND ${ORDER_BASE}+${RANGE_SIZE} AND order_status='WAIT_PAY' ORDER BY id LIMIT 1;" 2>/dev/null | tr -d '\r')

echo "==> [4/4] 完成"
echo ""
echo "✅ 批量 synthetic business data 已准备好。"
echo "   users=${USER_COUNT}, merchants=${MERCHANT_COUNT}, shops=${SHOP_COUNT}, coupons=${COUPON_COUNT}, orders=${ORDER_COUNT}"
echo "   anomaly_per_thousand=${ANOMALY_PER_THOUSAND}, redis_db=${REDIS_DB}"
echo ""
if [ -n "$sample_order_no" ]; then
    echo "示例异常订单号：${sample_order_no}"
    echo "注意：query_order 当前参数名为 order_id，实际传业务订单号 order_no。"
    echo "MCP 查询："
    echo "  curl -s http://localhost:8081/mcp \\"
    echo "    -H 'Content-Type: application/json' \\"
    echo "    -H 'X-User-Id: ${USER_BASE}' \\"
    echo "    -H 'X-User-Role: merchant' \\"
    echo "    -H 'X-Merchant-Id: ${MERCHANT_BASE}' \\"
    echo "    -d '{\"jsonrpc\":\"2.0\",\"id\":\"bulk\",\"method\":\"tools/call\",\"params\":{\"name\":\"query_order\",\"arguments\":{\"order_id\":\"${sample_order_no}\"}}}'"
fi
