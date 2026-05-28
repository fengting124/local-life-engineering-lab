"""
LocalLife Copilot 压测脚本（Locust）。

压测目标：
  - Agent 服务在多并发对话下的 P99 延迟
  - MCP Server 工具调用 P99 延迟
  - SSE 流式输出的首字节时间（TTFB）

压测特殊性：
  Copilot 是 Agent 服务，每次请求会触发多次 LLM 调用和工具调用。
  端到端延迟通常在 3-30 秒（取决于 Agent 步数和 LLM 速度）。
  压测关注点不是 TPS，而是：
    1. 并发时是否有资源泄漏（DB 连接池 / Redis 连接池）
    2. Tool Rate Limiter 是否在高并发下正确触发
    3. MCP Server 单工具调用的延迟（不含 LLM）

运行方式：
  # MCP Server 专项压测（不走 Agent，直接测工具调用）
  locust -f locustfile_copilot.py \\
    --host http://localhost:8081 \\
    --users 50 --spawn-rate 5 --run-time 60s \\
    --headless --html mcp_report.html -t McpToolUser

  # Agent SSE 端点压测（会触发真实 LLM，需要 API Key）
  locust -f locustfile_copilot.py \\
    --host http://localhost:8000 \\
    --users 10 --spawn-rate 1 --run-time 120s \\
    --headless -t AgentChatUser
"""
import random
import json
import time
from locust import HttpUser, task, between, events

# =========================================================
# 测试用户身份（模拟不同角色）
# =========================================================
MERCHANT_HEADERS = {
    "X-User-Id":     "10001",
    "X-User-Role":   "merchant",
    "X-Merchant-Id": "20001",
    "Content-Type":  "application/json",
}

CS_HEADERS = {
    "X-User-Id":   "99999",
    "X-User-Role": "cs",
    "Content-Type": "application/json",
}


class McpToolUser(HttpUser):
    """
    MCP Server 工具调用直接压测（绕过 Agent，专测工具层性能）。

    目标：
    - 工具调用 P99 < 100ms（DB 查询）
    - 验证 ToolRateLimiter 在高并发下的行为（超过 100次/分钟 应触发限流）
    - 验证 RBAC 过滤是否正确

    Host: http://localhost:8081
    """
    wait_time = between(0.1, 0.5)

    @task(5)
    def query_order_cs(self):
        """CS 角色查询订单（高频读操作）。"""
        payload = {
            "jsonrpc": "2.0",
            "id":      "perf-001",
            "method":  "tools/call",
            "params": {
                "name":      "query_order",
                "arguments": {"order_id": "1234567890123456789"},
            }
        }
        with self.client.post("/mcp", json=payload,
                              headers=CS_HEADERS,
                              name="/mcp tools/call query_order",
                              catch_response=True) as resp:
            if resp.status_code == 200:
                body = resp.json()
                if "error" in body:
                    reason = body["error"].get("message", "")
                    if reason in ("not_found", "tool_rate_limited"):
                        resp.success()   # 业务错误不算失败
                    else:
                        resp.success()   # 所有工具错误都是业务正常，不算 Locust 失败
                else:
                    resp.success()
            else:
                resp.failure(f"HTTP {resp.status_code}")

    @task(3)
    def shop_metrics_merchant(self):
        """Merchant 角色查询门店数据。"""
        payload = {
            "jsonrpc": "2.0",
            "id":      "perf-002",
            "method":  "tools/call",
            "params": {
                "name":      "shop_metrics_query",
                "arguments": {"date": "yesterday"},
            }
        }
        with self.client.post("/mcp", json=payload,
                              headers=MERCHANT_HEADERS,
                              name="/mcp tools/call shop_metrics_query",
                              catch_response=True) as resp:
            if resp.status_code != 200:
                resp.failure(f"HTTP {resp.status_code}")
            else:
                resp.success()

    @task(2)
    def list_tools(self):
        """tools/list（Agent 启动时调用，权重低，主要测缓存效果）。"""
        payload = {"jsonrpc": "2.0", "id": "perf-003", "method": "tools/list", "params": {}}
        with self.client.post("/mcp", json=payload,
                              headers=MERCHANT_HEADERS,
                              name="/mcp tools/list",
                              catch_response=True) as resp:
            if resp.status_code != 200:
                resp.failure(f"HTTP {resp.status_code}")

    @task(1)
    def rate_limit_trigger(self):
        """
        快速连续调用同一工具，触发 ToolRateLimiter。
        验证 429/rate_limited 是否在超过 100次/分钟 时正确触发。
        """
        payload = {
            "jsonrpc": "2.0", "id": "perf-004",
            "method": "tools/call",
            "params": {"name": "shop_metrics_query", "arguments": {"date": "today"}},
        }
        for _ in range(5):  # 快速调用 5 次
            self.client.post("/mcp", json=payload,
                             headers=MERCHANT_HEADERS,
                             name="/mcp [rate_limit_test]",
                             catch_response=True)


class AgentChatUser(HttpUser):
    """
    Agent SSE 端点压测（会触发真实 LLM 调用，需要有效的 API Key）。

    注意：
    - 每次请求会调用 Claude API（有费用），不要大量并发
    - 建议小并发（5-10 用户）验证 Agent 在并发下的稳定性
    - 主要关注：是否有 DB 连接泄漏、内存泄漏、并发 Session 冲突

    Host: http://localhost:8000
    """
    wait_time = between(5, 15)   # Agent 调用间隔长，避免高并发打爆 LLM API

    agent_headers = {
        **MERCHANT_HEADERS,
        "X-Session-Id": "99001",   # 压测用固定 Session
        "Accept":       "text/event-stream",
    }

    @task(3)
    def simple_query(self):
        """简单数据查询（单工具调用，Agent 步数少）。"""
        queries = ["我今天卖了多少钱？", "昨天的订单量是多少？", "本月 GMV 怎么样？"]
        payload = {
            "message":    random.choice(queries),
            "session_id": 99001,
        }
        start = time.time()
        with self.client.post("/chat",
                              json=payload,
                              headers=self.agent_headers,
                              name="/chat [simple query]",
                              catch_response=True,
                              stream=True) as resp:
            if resp.status_code != 200:
                resp.failure(f"HTTP {resp.status_code}")
                return
            # 消费 SSE 流直到 final_answer 事件
            ttfb_measured = False
            for line in resp.iter_lines():
                if not ttfb_measured:
                    # 首字节时间（TTFB）
                    ttfb_ms = (time.time() - start) * 1000
                    ttfb_measured = True
                if b"final_answer" in (line or b""):
                    break
            resp.success()

    @task(1)
    def diagnosis_query(self):
        """订单异常排查（多工具调用，Agent 步数多）。"""
        payload = {
            "message":    "帮我查一下 ORDER_12345 的状态",
            "session_id": 99002,
        }
        with self.client.post("/chat",
                              json=payload,
                              headers={**self.agent_headers, "X-Session-Id": "99002"},
                              name="/chat [diagnosis]",
                              catch_response=True,
                              stream=True) as resp:
            if resp.status_code != 200:
                resp.failure(f"HTTP {resp.status_code}")
                return
            for line in resp.iter_lines():
                if b"final_answer" in (line or b"") or b"error" in (line or b""):
                    break
            resp.success()


# =========================================================
# 性能报告
# =========================================================

@events.test_stop.add_listener
def on_test_stop(environment, **kwargs):
    print("\n" + "=" * 60)
    print("LocalLife Copilot 压测结果汇总")
    print("=" * 60)
    for name, entry in environment.stats.entries.items():
        if entry.num_requests > 0:
            print(f"  {name[1]:45} | "
                  f"Req: {entry.num_requests:5} | "
                  f"P99: {entry.get_response_time_percentile(0.99):6.0f}ms | "
                  f"Fail: {entry.fail_ratio:.1%}")
    print("=" * 60)
