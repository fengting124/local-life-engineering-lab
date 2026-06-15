"""
消费者驱动契约测试（Consumer-Driven Contract, Pact）。

角色：
  - Consumer = copilot-agent-service 的 McpClient（本服务）
  - Provider = local-life-copilot 的 MCP Server（Java，JSON-RPC 2.0 over HTTP）

为什么需要契约测试：
  两个服务跨语言（Python ↔ Java）、跨仓库演进。单测各自 mock 对方，谁也不知道
  「我以为的接口」和「对方真正的接口」是否还一致。契约测试用一个 Pact mock 充当 Provider，
  让真实的 McpClient 去调用——验证「我们发出的请求」和「我们假设的响应结构」严丝合缝，
  并生成机器可读的 pact 文件（tests/contract/pacts/*.json）。

  这份 pact 之后可交给 Provider 侧（Java）做 provider verification：
  用真实 MCP Server 重放 pact 里的请求，确认响应仍满足契约——任何一方改坏接口都会红。
  本测试只做 Consumer 侧（生成契约）；Provider 侧验证步骤见
  docs/04-notes 的「契约测试」一节（需 MCP Server 运行）。

  每个 test 一个 interaction + 一次 serve（Pact 推荐用法，避免单 mock 多 interaction 的匹配歧义）。
"""
import asyncio
import os

import pytest
from pact import Pact, match

from mcp.mcp_client import McpClient, McpToolError

PACT_DIR = os.path.join(os.path.dirname(__file__), "pacts")


def _new_pact() -> Pact:
    return Pact("copilot-agent-service", "local-life-mcp-server").with_specification("V3")


def _client_at(server) -> McpClient:
    client = McpClient(user_id=1, user_role="merchant")
    client._base_url = str(server.url).rstrip("/")  # 指向 Pact mock，而非真实 MCP Server
    return client


def test_tools_call_success_contract():
    """契约 1：tools/call 成功 → result.content[0].text 被正确提取。"""
    pact = _new_pact()
    (
        pact.upon_receiving("a tools/call for query_order that succeeds")
        .with_request("POST", "/mcp")
        .with_header("Content-Type", "application/json")
        .with_header("X-User-Id", "1")
        .with_header("X-User-Role", "merchant")
        .with_body(
            {
                "jsonrpc": "2.0",
                "id": match.uuid(),                 # McpClient 每次生成随机 UUID
                "method": "tools/call",
                "params": {"name": "query_order", "arguments": {"order_no": "ORD1"}},
            },
            content_type="application/json",
        )
        .will_respond_with(200)
        .with_body(
            {
                "jsonrpc": "2.0",
                "id": match.uuid(),
                "result": {"content": [{"type": "text", "text": "订单状态：已支付"}]},
            },
            content_type="application/json",
        )
    )

    with pact.serve() as srv:
        client = _client_at(srv)
        text = asyncio.run(client.call_tool("query_order", {"order_no": "ORD1"}))
        assert text == "订单状态：已支付"

    pact.write_file(PACT_DIR)


def test_tools_call_business_error_contract():
    """契约 2：tools/call 业务错误 → error.data.reason 映射为 McpToolError。"""
    pact = _new_pact()
    (
        pact.upon_receiving("a tools/call for query_order that returns not_found")
        .with_request("POST", "/mcp")
        .with_header("Content-Type", "application/json")
        .with_header("X-User-Id", "1")
        .with_header("X-User-Role", "merchant")
        .with_body(
            {
                "jsonrpc": "2.0",
                "id": match.uuid(),
                "method": "tools/call",
                "params": {"name": "query_order", "arguments": {"order_no": "NOTFOUND"}},
            },
            content_type="application/json",
        )
        .will_respond_with(200)
        .with_body(
            {
                "jsonrpc": "2.0",
                "id": match.uuid(),
                "error": {
                    "message": "tool failed",
                    "data": {
                        "reason": "not_found",
                        "detail": "订单不存在: NOTFOUND",
                        "hint": "请确认订单号",
                    },
                },
            },
            content_type="application/json",
        )
    )

    with pact.serve() as srv:
        client = _client_at(srv)
        with pytest.raises(McpToolError) as exc:
            asyncio.run(client.call_tool("query_order", {"order_no": "NOTFOUND"}))
        assert exc.value.reason == "not_found"
        assert "NOTFOUND" in exc.value.detail
        assert exc.value.hint == "请确认订单号"
        assert exc.value.is_retryable() is False

    pact.write_file(PACT_DIR)
