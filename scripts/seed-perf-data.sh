#!/usr/bin/env bash
# =======================================================
# 压测数据一键准备：MySQL seed + Redis 预热
# 配合 performance-tests/locustfile_locallife_server.py 的 SeckillUser 场景。
#
# 做三件事：
#   1. 灌 MySQL：2000 测试用户 + 2 券模板 + 2 ACTIVE 秒杀场次（scripts/seed-perf-data.sql）
#   2. 灌 Redis：
#        - 每个测试手机号一条登录验证码  login:code:{mobile} = 123456
#          （verifyCode 一次性使用，登录后即删；所以每跑一轮压测前要重新执行本脚本）
#        - 秒杀库存  seckill:stock:1:1 / seckill:stock:2:2 = $STOCK
#   3. 清理上一轮残留：seckill:user:* 抢购集合、seckill:result:* 结果态
#
# 用法：
#   bash scripts/seed-perf-data.sh                 # 用默认参数
#   STOCK=500 REDIS_DB=1 bash scripts/seed-perf-data.sh
#
# 可调环境变量（含默认值）：
#   MYSQL_CONTAINER=local-life-mysql
#   REDIS_CONTAINER=local-life-redis
#   MYSQL_PWD=123456   DB=local_life
#   REDIS_DB=0         # ⚠️ 本地直跑 server（application.yml）用 0；docker-compose 跑用 1
#   MOBILE_BASE=13900000000   MOBILE_COUNT=2000
#   STOCK=1000         # 写进 Redis 的秒杀库存（claimed 总数应 <= 它，否则超卖）
# =======================================================
set -euo pipefail

MYSQL_CONTAINER="${MYSQL_CONTAINER:-local-life-mysql}"
REDIS_CONTAINER="${REDIS_CONTAINER:-local-life-redis}"
MYSQL_PWD="${MYSQL_PWD:-123456}"
DB="${DB:-local_life}"
REDIS_DB="${REDIS_DB:-0}"
MOBILE_BASE="${MOBILE_BASE:-13900000000}"
MOBILE_COUNT="${MOBILE_COUNT:-2000}"
STOCK="${STOCK:-1000}"

SCRIPT_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"

echo "==> [0/3] 前置检查：容器是否在运行"
for c in "$MYSQL_CONTAINER" "$REDIS_CONTAINER"; do
    if ! docker ps --format '{{.Names}}' | grep -qx "$c"; then
        echo "ERROR: 容器 '$c' 没在运行。请先启动它："
        echo "       docker start $c     （或 cd infra && docker compose -f docker-compose.dev.yml up -d $([ "$c" = "$REDIS_CONTAINER" ] && echo redis || echo mysql)）"
        exit 1
    fi
done

echo "==> [1/3] 灌 MySQL（用户 / 券模板 / 秒杀场次）"
docker exec -i "$MYSQL_CONTAINER" mysql -uroot -p"$MYSQL_PWD" "$DB" < "$SCRIPT_DIR/seed-perf-data.sql"

echo "==> [2/3] 预热 Redis（验证码 + 库存）  REDIS_DB=$REDIS_DB  STOCK=$STOCK"
# 生成一批 inline 命令，一次性管道喂给容器里的 redis-cli（比 2000 次 docker exec 快几个数量级）
{
    # 登录验证码：login:code:{mobile} = 123456，给 30 分钟 TTL
    end=$((MOBILE_COUNT - 1))
    for i in $(seq 0 "$end"); do
        mobile=$((MOBILE_BASE + i))
        echo "SET login:code:${mobile} 123456 EX 1800"
    done
    # 秒杀库存（session 1→模板1，session 2→模板2）
    echo "SET seckill:stock:1:1 ${STOCK}"
    echo "SET seckill:stock:2:2 ${STOCK}"
} | docker exec -i "$REDIS_CONTAINER" redis-cli -n "$REDIS_DB" > /dev/null

echo "==> [3/3] 清理上一轮残留（抢购集合 / 结果态）"
# DEL 抢购集合
docker exec -i "$REDIS_CONTAINER" redis-cli -n "$REDIS_DB" DEL seckill:user:1:1 seckill:user:2:2 > /dev/null
# 结果态 key 是按用户散开的，用 SCAN 批量删（避免 KEYS 阻塞）
docker exec -i "$REDIS_CONTAINER" sh -c \
    "redis-cli -n ${REDIS_DB} --scan --pattern 'seckill:result:*' | xargs -r redis-cli -n ${REDIS_DB} DEL" > /dev/null 2>&1 || true

echo ""
echo "✅ 压测数据就绪："
echo "   - MySQL：${MOBILE_COUNT} 用户 + 2 券模板 + 2 ACTIVE 场次"
echo "   - Redis(db=${REDIS_DB})：seckill:stock:1:1 = seckill:stock:2:2 = ${STOCK}，验证码已就位"
echo ""
echo "下一步跑秒杀压测（注意 Locust 用户数别超过 ${MOBILE_COUNT}）："
echo "   cd performance-tests && pip install -r requirements.txt"
echo "   locust -f locustfile_locallife_server.py --host http://localhost:8080 \\"
echo "     --users 200 --spawn-rate 50 --run-time 30s --headless SeckillUser"
echo ""
echo "结束看汇总里的「抢到券 claimed」总数：应 <= ${STOCK}（×2 个场次），超过即为超卖。"
