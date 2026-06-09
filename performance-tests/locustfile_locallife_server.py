"""
LocalLife Server 压测脚本（Locust）。

压测目标：
  - 验证核心业务链路在高并发下的 P99 延迟
  - 定位性能瓶颈（DB / Redis / JVM GC）
  - 验证限流、熔断是否按预期生效

运行方式：
  # 交互式 Web UI（推荐，可实时查看图表）
  locust -f locustfile_locallife_server.py --host http://localhost:8080

  # 无头模式（CI/CD 用）
  locust -f locustfile_locallife_server.py \\
    --host http://localhost:8080 \\
    --users 100 --spawn-rate 10 --run-time 60s \\
    --headless --html report.html

  # 专项压测秒杀接口
  locust -f locustfile_locallife_server.py \\
    --host http://localhost:8080 \\
    --users 500 --spawn-rate 50 --run-time 30s \\
    --headless --only-summary -t SeckillUser

场景说明：
  LocalLifeUser   → 模拟普通用户（登录 + 浏览 + 搜索 + 下单）
  SeckillUser     → 模拟抢券用户（专项并发压测秒杀 Lua 原子操作）
  SearchUser      → 模拟搜索用户（ES 查询 + Geo 距离过滤）

性能基准（开发机 MacBook M2，MySQL 8 + Redis 7 本地）：
  /api/v1/shops/{id}     P99 < 20ms   （Caffeine + Redis 二级缓存）
  /api/v1/posts/{id}     P99 < 30ms
  /api/v1/seckill        P99 < 50ms   （Redis Lua，DB 异步）
  /api/v1/search/shops   P99 < 200ms  （ES 单节点）
"""
import random
import json
import itertools
import threading
from locust import HttpUser, task, between, events
from locust.runners import MasterRunner

# =========================================================
# 测试数据（真实环境应从 DB 查询，此处用固定值演示）
# =========================================================
SHOP_IDS         = list(range(1, 20))
POST_IDS         = list(range(1, 50))
# (sessionId, couponTemplateId)：必须是合法配对——seckill_session 一场次只绑一个券模板。
# 与 scripts/seed-perf-data.sql 对齐：场次 1→模板 1，场次 2→模板 2。
SECKILL_SESSIONS = [(1, 1), (2, 2)]
TEST_MOBILE      = "13800000001"
TEST_CODE        = "123456"  # 测试验证码（测试环境固定值）

# 秒杀专项压测需要「不同用户」才能真实争抢库存：
# 如果所有虚拟用户都用同一个 mobile 登录，第一个抢到后其余全是「已领取(400)」，
# 测不出超卖。这里用一段连号手机号池，每个虚拟用户取一个不同号。
#   ⚠️ 前提：这些账号必须已在 DB 里存在（见 docs 里的「压测数据准备」），否则登录失败。
SECKILL_MOBILE_BASE  = 13900000000
SECKILL_MOBILE_COUNT = 2000
_mobile_seq = itertools.count(0)   # 线程安全的自增（gevent 协作式调度下安全）

# 秒杀结果计数器（HTTP 层校验超卖：claimed 总数应 <= 库存）
_seckill_lock = threading.Lock()
_seckill_outcomes = {"claimed": 0, "sold_out": 0, "duplicated": 0, "rate_limited": 0, "error": 0}


def _record_seckill(outcome: str):
    with _seckill_lock:
        _seckill_outcomes[outcome] = _seckill_outcomes.get(outcome, 0) + 1


class LocalLifeUser(HttpUser):
    """
    普通用户场景：模拟真实用户的浏览 + 搜索 + 下单行为。

    wait_time：每次任务之间等待 1~3 秒（模拟人工操作间隔）。
    实际并发能力 ≈ 用户数 × (1 / avg_wait_time) × 任务执行频率。
    """
    wait_time = between(1, 3)
    token: str = ""

    def on_start(self):
        """每个虚拟用户启动时登录。"""
        # 先发验证码（开发环境可跳过，但测试接口限流时需要）
        self.client.post("/api/v1/auth/code", json={"mobile": TEST_MOBILE},
                         name="/auth/code", catch_response=True)
        # 登录
        with self.client.post("/api/v1/auth/login",
                              json={"mobile": TEST_MOBILE, "code": TEST_CODE},
                              name="/auth/login", catch_response=True) as resp:
            if resp.status_code == 200:
                data = resp.json().get("data", {})
                self.token = data.get("token", "")
            else:
                resp.failure(f"登录失败: {resp.status_code}")

    def _auth_headers(self):
        return {"Authorization": f"Bearer {self.token}"} if self.token else {}

    # =========================================================
    # 读接口（权重高，模拟真实流量分布：读多写少）
    # =========================================================

    @task(5)
    def browse_shop(self):
        """浏览门店详情（公开接口，命中 Redis 缓存）。"""
        shop_id = random.choice(SHOP_IDS)
        with self.client.get(f"/api/v1/shops/{shop_id}",
                             name="/api/v1/shops/{shopId}",
                             catch_response=True) as resp:
            if resp.status_code not in (200, 400):
                resp.failure(f"Unexpected: {resp.status_code}")

    @task(4)
    def browse_post(self):
        """浏览笔记详情（公开接口，含点赞数 Redis 读取）。"""
        post_id = random.choice(POST_IDS)
        with self.client.get(f"/api/v1/posts/{post_id}",
                             name="/api/v1/posts/{postId}",
                             catch_response=True) as resp:
            if resp.status_code not in (200, 400):
                resp.failure(f"Unexpected: {resp.status_code}")

    @task(3)
    def view_coupon_templates(self):
        """查看可抢券列表（公开接口）。"""
        self.client.get("/api/v1/coupons/templates", name="/api/v1/coupons/templates")

    @task(2)
    def list_my_orders(self):
        """查看我的订单（需登录，ShardingSphere 路由）。"""
        self.client.get("/api/v1/orders",
                        headers=self._auth_headers(),
                        name="/api/v1/orders")

    # =========================================================
    # 写接口（权重低）
    # =========================================================

    @task(1)
    def like_post(self):
        """点赞笔记（Redis INCR，不写 DB）。"""
        post_id = random.choice(POST_IDS)
        self.client.post(f"/api/v1/posts/{post_id}/likes",
                         headers=self._auth_headers(),
                         name="/api/v1/posts/{postId}/likes")


class SeckillUser(HttpUser):
    """
    秒杀专项压测：并发抢券，验证 Redis Lua 原子操作在高并发下的正确性。

    预期结果：
    - 无超卖（成功数 <= 库存总量）
    - P99 < 50ms（主路径在 Redis，不触 DB）
    - 4xx（已抢完/已领取）不计入错误率

    压测建议：
      --users 200 --spawn-rate 50（模拟200人同时抢）
      观察成功率和总成功数（应 <= 券库存）
    """
    wait_time = between(0.1, 0.5)  # 秒杀场景用户等待时间短
    token: str = ""
    mobile: str = ""

    def on_start(self):
        # 每个虚拟用户取一个不同的手机号，模拟「不同的人」同时抢同一批库存
        idx = next(_mobile_seq) % SECKILL_MOBILE_COUNT
        self.mobile = str(SECKILL_MOBILE_BASE + idx)
        with self.client.post("/api/v1/auth/login",
                              json={"mobile": self.mobile, "code": TEST_CODE},
                              name="/auth/login [seckill]", catch_response=True) as resp:
            if resp.status_code == 200:
                self.token = resp.json().get("data", {}).get("token", "")
            else:
                resp.failure(f"登录失败（账号是否已 seed？）: {resp.status_code}")

    @task
    def seckill(self):
        """参与秒杀（核心测试接口）。"""
        session_id, coupon_template_id = random.choice(SECKILL_SESSIONS)
        with self.client.post(
                "/api/v1/seckill",
                json={"sessionId": session_id, "couponTemplateId": coupon_template_id},
                headers={"Authorization": f"Bearer {self.token}"},
                name="/api/v1/seckill",
                catch_response=True) as resp:
            if resp.status_code == 200:
                # 解析业务结果：success=true 才是真正「抢到一张」（用于 HTTP 层超卖核对）
                try:
                    success = resp.json().get("data", {}).get("success", False)
                except (ValueError, AttributeError):
                    success = False
                _record_seckill("claimed" if success else "sold_out")
                resp.success()
            elif resp.status_code == 400:
                # 库存不足 / 已领取 → 正常业务 4xx，不算错误
                body = resp.text
                _record_seckill("duplicated" if "ALREADY" in body else "sold_out")
                resp.success()
            elif resp.status_code == 429:
                # 限流触发 → 正常，不算错误
                _record_seckill("rate_limited")
                resp.success()
            else:
                _record_seckill("error")
                resp.failure(f"Unexpected: {resp.status_code} {resp.text[:100]}")


class SearchUser(HttpUser):
    """
    搜索压测：ES 全文搜索 + Geo 距离过滤。
    ES 单节点吞吐量通常低于 MySQL，此场景专门测 ES 的瓶颈。

    预期结果：
    - P99 < 200ms（ES 查询 + 结果序列化）
    - 注意：ES 冷启动第一次查询较慢（缓存 miss）
    """
    wait_time = between(0.5, 2)

    @task(3)
    def search_shops_by_keyword(self):
        """关键词搜索门店。"""
        keywords = ["火锅", "咖啡", "甜品", "烧烤", "西餐"]
        self.client.get(
            "/api/v1/search/shops",
            params={"keyword": random.choice(keywords), "pageSize": 10},
            name="/api/v1/search/shops?keyword")

    @task(2)
    def search_shops_by_geo(self):
        """Geo 距离搜索门店（测 ES Geo Query）。"""
        # 模拟杭州西湖区附近坐标（小幅随机偏移模拟不同用户位置）
        lat = 30.2741 + random.uniform(-0.05, 0.05)
        lon = 120.1551 + random.uniform(-0.05, 0.05)
        self.client.get(
            "/api/v1/search/shops",
            params={"lat": lat, "lon": lon, "distanceKm": 3, "pageSize": 10},
            name="/api/v1/search/shops?geo")

    @task(1)
    def search_posts(self):
        """笔记全文搜索（测 ES IK 分词器）。"""
        keywords = ["好吃", "推荐", "必打卡", "隐藏", "宝藏"]
        self.client.get(
            "/api/v1/search/posts",
            params={"keyword": random.choice(keywords), "pageSize": 10},
            name="/api/v1/search/posts")


# =========================================================
# 自定义性能指标收集（Locust 事件钩子）
# =========================================================

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    """压测结束时输出性能汇总（输出到控制台，方便快速判断是否达标）。"""
    print("\n" + "=" * 60)
    print("LocalLife Server 压测结果汇总")
    print("=" * 60)
    stats = environment.stats
    for name, entry in stats.entries.items():
        if entry.num_requests > 0:
            print(f"  {name[1]:40} | "
                  f"RPS: {entry.current_rps:6.1f} | "
                  f"P50: {entry.get_response_time_percentile(0.50):5.0f}ms | "
                  f"P99: {entry.get_response_time_percentile(0.99):5.0f}ms | "
                  f"Fail: {entry.fail_ratio:.1%}")
    print("=" * 60)

    # 秒杀专项：HTTP 层的超卖核对
    total_seckill = sum(_seckill_outcomes.values())
    if total_seckill > 0:
        print("秒杀结果分布（HTTP 层）")
        print("-" * 60)
        print(f"  抢到券 claimed     : {_seckill_outcomes['claimed']:>6}  "
              f"← 这个数必须 ≤ Redis 预置库存总量，否则就是超卖")
        print(f"  售罄  sold_out      : {_seckill_outcomes['sold_out']:>6}")
        print(f"  重复  duplicated    : {_seckill_outcomes['duplicated']:>6}  （同一用户重复抢）")
        print(f"  限流  rate_limited  : {_seckill_outcomes['rate_limited']:>6}")
        print(f"  异常  error         : {_seckill_outcomes['error']:>6}  ← 应为 0")
        print("=" * 60)
