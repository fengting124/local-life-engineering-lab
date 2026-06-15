"""
mcp/mcp_client.py 单元测试。

McpToolError 是纯 Python 类，无 I/O 依赖。
McpClient 通过 httpx.AsyncClient 发 HTTP 请求；测试中 mock 掉整个
AsyncClient，完全隔离网络，只验证协议解析和错误处理逻辑。

关键技术点：
  - httpx.AsyncClient 是异步上下文管理器，mock 时需同时设置 __aenter__/__aexit__
  - 工具 Schema 进程级 TTL 缓存：缓存命中时不发 HTTP 请求
  - JSON-RPC 错误解析：error.data.reason 决定 McpToolError.is_retryable()
"""
import time
import pytest
import httpx
from unittest.mock import AsyncMock, MagicMock, patch

import mcp.mcp_client as mcp_module
from mcp.mcp_client import McpClient, McpToolError


# =========================================================
# Fixture：AsyncClient mock 工厂
# =========================================================

def _make_async_client_mock(response_body: dict) -> MagicMock:
    """
    构造一个模拟 httpx.AsyncClient 的对象。

    httpx.AsyncClient 是异步上下文管理器（async with ... as client），
    需要同时设置 __aenter__ 和 __aexit__。
    """
    mock_response = MagicMock()
    mock_response.json.return_value = response_body
    mock_response.raise_for_status.return_value = None

    mock_client = AsyncMock()
    mock_client.post = AsyncMock(return_value=mock_response)

    mock_cm = MagicMock()
    mock_cm.__aenter__ = AsyncMock(return_value=mock_client)
    mock_cm.__aexit__ = AsyncMock(return_value=False)
    return mock_cm


@pytest.fixture(autouse=True)
def reset_tools_cache():
    """每个测试前后清空工具 Schema 缓存，防止测试间污染。"""
    mcp_module._tools_cache = None
    mcp_module._tools_cache_time = 0.0
    yield
    mcp_module._tools_cache = None
    mcp_module._tools_cache_time = 0.0


# =========================================================
# McpToolError
# =========================================================

class TestMcpToolError:
    def test_parameter_error_is_retryable(self):
        err = McpToolError("parameter_error", "缺少 order_id")
        assert err.is_retryable() is True

    def test_tool_timeout_is_retryable(self):
        err = McpToolError("tool_timeout", "超时 30s")
        assert err.is_retryable() is True

    def test_internal_error_is_not_retryable(self):
        err = McpToolError("internal_error", "服务故障")
        assert err.is_retryable() is False

    def test_not_found_is_not_retryable(self):
        err = McpToolError("not_found", "订单不存在")
        assert err.is_retryable() is False

    def test_permission_denied_is_not_retryable(self):
        err = McpToolError("permission_denied", "无权访问")
        assert err.is_retryable() is False

    def test_to_dict_all_fields(self):
        err = McpToolError("parameter_error", "缺少 order_id", "请提供完整订单号")
        d = err.to_dict()
        assert d["reason"] == "parameter_error"
        assert d["detail"] == "缺少 order_id"
        assert d["hint"] == "请提供完整订单号"

    def test_to_dict_hint_none_when_not_provided(self):
        err = McpToolError("internal_error", "服务故障")
        assert err.to_dict()["hint"] is None

    def test_str_representation_uses_detail(self):
        err = McpToolError("not_found", "订单 ORDER_123 不存在")
        assert "ORDER_123" in str(err)


# =========================================================
# McpClient._headers
# =========================================================

class TestMcpClientHeaders:
    def test_basic_headers_include_user_id_and_role(self):
        client = McpClient(user_id=123, user_role="merchant")
        h = client._headers(None, None)
        assert h["X-User-Id"] == "123"
        assert h["X-User-Role"] == "merchant"
        assert h["Content-Type"] == "application/json"

    def test_merchant_id_header_when_provided(self):
        client = McpClient(user_id=1, user_role="merchant", merchant_id=42)
        h = client._headers(None, None)
        assert h["X-Merchant-Id"] == "42"

    def test_no_merchant_id_header_when_absent(self):
        client = McpClient(user_id=1, user_role="cs")
        h = client._headers(None, None)
        assert "X-Merchant-Id" not in h

    def test_session_and_thread_headers(self):
        client = McpClient(user_id=1, user_role="cs")
        h = client._headers(session_id=999, thread_id="thread-xyz")
        assert h["X-Session-Id"] == "999"
        assert h["X-Thread-Id"] == "thread-xyz"

    def test_no_session_thread_headers_when_absent(self):
        client = McpClient(user_id=1, user_role="cs")
        h = client._headers(None, None)
        assert "X-Session-Id" not in h
        assert "X-Thread-Id" not in h


# =========================================================
# McpClient._rpc 和错误处理
# =========================================================

class TestMcpClientRpc:
    @pytest.mark.asyncio
    async def test_timeout_raises_tool_timeout(self):
        client = McpClient(user_id=1, user_role="merchant")
        with patch("httpx.AsyncClient") as mock_cls:
            mock_cm = MagicMock()
            mock_cm.__aenter__ = AsyncMock(side_effect=httpx.TimeoutException("timeout"))
            mock_cm.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_cm
            with pytest.raises(McpToolError) as exc_info:
                await client._rpc("tools/call", {}, None, None)
        assert exc_info.value.reason == "tool_timeout"
        assert exc_info.value.is_retryable() is True

    @pytest.mark.asyncio
    async def test_http_500_raises_internal_error(self):
        client = McpClient(user_id=1, user_role="merchant")
        mock_response = MagicMock()
        mock_response.status_code = 500
        mock_response.raise_for_status.side_effect = httpx.HTTPStatusError(
            "500", request=MagicMock(), response=mock_response
        )
        with patch("httpx.AsyncClient") as mock_cls:
            mock_cm = MagicMock()
            mock_client = AsyncMock()
            mock_client.post = AsyncMock(return_value=mock_response)
            mock_cm.__aenter__ = AsyncMock(return_value=mock_client)
            mock_cm.__aexit__ = AsyncMock(return_value=False)
            mock_cls.return_value = mock_cm
            with pytest.raises(McpToolError) as exc_info:
                await client._rpc("tools/call", {}, None, None)
        assert exc_info.value.reason == "internal_error"

    @pytest.mark.asyncio
    async def test_jsonrpc_error_parsed_correctly(self):
        """JSON-RPC error.data.reason 正确映射到 McpToolError.reason。"""
        client = McpClient(user_id=1, user_role="cs")
        error_body = {
            "jsonrpc": "2.0", "id": "x",
            "error": {
                "message": "tool failed",
                "data": {
                    "reason": "parameter_error",
                    "detail": "缺少必填字段 order_id",
                    "hint": "请在参数中提供 order_id",
                },
            },
        }
        with patch("httpx.AsyncClient") as mock_cls:
            mock_cls.return_value = _make_async_client_mock(error_body)
            with pytest.raises(McpToolError) as exc_info:
                await client._rpc("tools/call", {}, None, None)
        err = exc_info.value
        assert err.reason == "parameter_error"
        assert "order_id" in err.detail
        assert err.hint == "请在参数中提供 order_id"
        assert err.is_retryable() is True

    @pytest.mark.asyncio
    async def test_success_returns_result(self):
        client = McpClient(user_id=1, user_role="cs")
        body = {
            "jsonrpc": "2.0", "id": "x",
            "result": {"content": [{"type": "text", "text": "查询成功"}]},
        }
        with patch("httpx.AsyncClient") as mock_cls:
            mock_cls.return_value = _make_async_client_mock(body)
            result = await client._rpc("tools/call", {}, None, None)
        assert result == {"content": [{"type": "text", "text": "查询成功"}]}


# =========================================================
# McpClient.call_tool — content 提取
# =========================================================

class TestMcpClientCallTool:
    @pytest.mark.asyncio
    async def test_extracts_content_text(self):
        """call_tool 正确提取 content[0].text。"""
        client = McpClient(user_id=1, user_role="cs")
        body = {
            "jsonrpc": "2.0", "id": "x",
            "result": {"content": [{"type": "text", "text": "订单状态：已支付"}]},
        }
        with patch("httpx.AsyncClient") as mock_cls:
            mock_cls.return_value = _make_async_client_mock(body)
            text = await client.call_tool("query_order", {"order_no": "123"})
        assert text == "订单状态：已支付"

    @pytest.mark.asyncio
    async def test_empty_content_returns_result_as_string(self):
        # content=[] 时，实现 fallback 到 str(result)，而不是空字符串
        client = McpClient(user_id=1, user_role="cs")
        body = {"jsonrpc": "2.0", "id": "x", "result": {"content": []}}
        with patch("httpx.AsyncClient") as mock_cls:
            mock_cls.return_value = _make_async_client_mock(body)
            text = await client.call_tool("query_order", {})
        # fallback：返回 str(result)，包含完整 result 字典
        assert "content" in text


# =========================================================
# McpClient.list_tools — TTL 缓存
# =========================================================

class TestListToolsCache:
    @pytest.mark.asyncio
    async def test_cache_miss_fetches_from_server(self):
        tools_body = {
            "jsonrpc": "2.0", "id": "x",
            "result": {"tools": [{"name": "query_order"}, {"name": "query_payment"}]},
        }
        client = McpClient(user_id=1, user_role="admin")
        with patch("httpx.AsyncClient") as mock_cls:
            mock_cls.return_value = _make_async_client_mock(tools_body)
            tools = await client.list_tools()
        assert len(tools) == 2
        assert mcp_module._tools_cache is not None

    @pytest.mark.asyncio
    async def test_cache_hit_skips_http(self):
        """缓存命中时不发 HTTP 请求。"""
        mcp_module._tools_cache = [{"name": "cached_tool"}]
        mcp_module._tools_cache_time = time.time() + 9999  # 不会过期

        client = McpClient(user_id=1, user_role="admin")
        with patch("httpx.AsyncClient") as mock_cls:
            tools = await client.list_tools()
            mock_cls.assert_not_called()  # 没有创建 HTTP 客户端
        assert tools[0]["name"] == "cached_tool"

    @pytest.mark.asyncio
    async def test_expired_cache_refreshes(self):
        """TTL 过期后重新拉取。"""
        mcp_module._tools_cache = [{"name": "stale_tool"}]
        mcp_module._tools_cache_time = 0.0  # 很久以前，已过期

        new_tools_body = {
            "jsonrpc": "2.0", "id": "x",
            "result": {"tools": [{"name": "fresh_tool"}]},
        }
        client = McpClient(user_id=1, user_role="admin")
        with patch("httpx.AsyncClient") as mock_cls:
            mock_cls.return_value = _make_async_client_mock(new_tools_body)
            tools = await client.list_tools()
        assert tools[0]["name"] == "fresh_tool"
