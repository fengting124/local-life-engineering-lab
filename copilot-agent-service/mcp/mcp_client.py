"""
MCP HTTP Client：调用 Java MCP Server（local-life-copilot）。

MCP 协议：JSON-RPC 2.0 over HTTP，POST /mcp。
此客户端封装了三种 RPC 方法：
  - initialize()   → 握手确认服务可用
  - list_tools()   → 获取工具列表（Agent 启动时调用一次，缓存工具 Schema）
  - call_tool()    → 执行工具调用（ReAct Action 节点调用）
"""
import uuid
import httpx
import structlog
from typing import Any

from config.settings import settings

log = structlog.get_logger(__name__)


class McpToolError(Exception):
    """工具执行错误，含结构化错误信息（reason / detail / hint）。"""

    def __init__(self, reason: str, detail: str, hint: str | None = None):
        super().__init__(detail)
        self.reason = reason
        self.detail = detail
        self.hint = hint

    def is_retryable(self) -> bool:
        """Agent 判断是否可以重试（修正参数后重试 vs 直接放弃）。"""
        return self.reason in ("parameter_error", "tool_timeout")

    def to_dict(self) -> dict:
        return {"reason": self.reason, "detail": self.detail, "hint": self.hint}


class McpClient:
    """
    MCP HTTP 客户端（异步）。

    Agent Service 通过此类与 Java MCP Server 通信。
    每次工具调用携带 session_id / thread_id / RBAC Header，
    MCP Server 据此做权限校验和审计日志。
    """

    def __init__(
        self,
        user_id: int,
        user_role: str,
        merchant_id: int | None = None,
    ):
        self.user_id = user_id
        self.user_role = user_role
        self.merchant_id = merchant_id
        self._base_url = settings.mcp_server_url
        self._timeout = settings.mcp_timeout_seconds

    def _headers(self, session_id: int | None, thread_id: str | None) -> dict:
        """构建 MCP 请求 Header（身份注入 + 会话追踪）。"""
        h = {
            "Content-Type": "application/json",
            "X-User-Id": str(self.user_id),
            "X-User-Role": self.user_role,
        }
        if self.merchant_id:
            h["X-Merchant-Id"] = str(self.merchant_id)
        if session_id:
            h["X-Session-Id"] = str(session_id)
        if thread_id:
            h["X-Thread-Id"] = thread_id
        return h

    async def initialize(self) -> dict:
        """MCP 握手，确认 Server 可用并获取能力声明。"""
        result = await self._rpc("initialize", {}, None, None)
        log.info("mcp_initialized", server_info=result.get("serverInfo"))
        return result

    async def list_tools(self) -> list[dict]:
        """获取所有工具定义（供 Agent 构建 System Prompt 工具列表）。"""
        result = await self._rpc("tools/list", {}, None, None)
        tools = result.get("tools", [])
        log.info("mcp_tools_listed", count=len(tools))
        return tools

    async def call_tool(
        self,
        tool_name: str,
        arguments: dict,
        session_id: int | None = None,
        thread_id: str | None = None,
    ) -> str:
        """
        调用指定工具，返回工具执行结果（文本格式）。

        MCP 返回格式：{"content": [{"type": "text", "text": "..."}]}
        此方法提取 content[0].text 直接返回给 Agent。

        :raises McpToolError: 工具执行失败时（含 reason / detail / hint）
        """
        params = {"name": tool_name, "arguments": arguments}
        result = await self._rpc("tools/call", params, session_id, thread_id)

        # 提取 content[0].text
        content = result.get("content", [])
        if content:
            return content[0].get("text", "")
        return str(result)

    async def _rpc(
        self,
        method: str,
        params: dict,
        session_id: int | None,
        thread_id: str | None,
    ) -> dict:
        """发送 JSON-RPC 2.0 请求，处理协议级错误和业务级错误。"""
        request_id = str(uuid.uuid4())
        payload = {
            "jsonrpc": "2.0",
            "id": request_id,
            "method": method,
            "params": params,
        }

        try:
            async with httpx.AsyncClient(timeout=self._timeout) as client:
                response = await client.post(
                    f"{self._base_url}/mcp",
                    json=payload,
                    headers=self._headers(session_id, thread_id),
                )
                response.raise_for_status()
                body = response.json()

        except httpx.TimeoutException:
            raise McpToolError("tool_timeout", f"MCP Server 请求超时（{self._timeout}s）", "可重试一次")
        except httpx.HTTPStatusError as e:
            raise McpToolError("internal_error", f"MCP Server HTTP 错误: {e.response.status_code}", None)
        except Exception as e:
            raise McpToolError("internal_error", f"MCP 请求异常: {str(e)}", None)

        # 处理 JSON-RPC 错误
        if "error" in body:
            err = body["error"]
            data = err.get("data", {}) or {}
            reason  = data.get("reason", err.get("message", "internal_error"))
            detail  = data.get("detail", err.get("message", "未知错误"))
            hint    = data.get("hint")
            log.warning("mcp_tool_error", method=method, reason=reason, detail=detail)
            raise McpToolError(reason, detail, hint)

        return body.get("result", {})
