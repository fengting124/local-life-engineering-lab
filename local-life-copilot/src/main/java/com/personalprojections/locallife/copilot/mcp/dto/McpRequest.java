package com.personalprojections.locallife.copilot.mcp.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;
import lombok.Data;

/**
 * MCP JSON-RPC 2.0 请求体。
 *
 * <p>MCP（Model Context Protocol）使用 JSON-RPC 2.0 作为传输协议。
 * Python Agent 通过 POST /mcp 发送此格式的请求，MCP Server 解析并路由到对应处理逻辑。
 *
 * <h2>支持的 method</h2>
 * <ul>
 *   <li>{@code initialize} — 握手，返回服务器能力声明</li>
 *   <li>{@code tools/list} — 列出所有可用工具及其 Schema</li>
 *   <li>{@code tools/call} — 调用指定工具</li>
 * </ul>
 *
 * <h2>请求示例（tools/call）</h2>
 * <pre>{@code
 * {
 *   "jsonrpc": "2.0",
 *   "id": "req-001",
 *   "method": "tools/call",
 *   "params": {
 *     "name": "query_order",
 *     "arguments": { "order_id": "ORDER_12345" }
 *   }
 * }
 * }</pre>
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class McpRequest {

    /** JSON-RPC 版本，固定为 "2.0"。 */
    private String jsonrpc = "2.0";

    /**
     * 请求 ID，用于将响应与请求对应。
     * 可以是 String 或 Integer，这里用 String 兼容两种情况。
     * Notification（无需响应的请求）不包含 id 字段。
     */
    private String id;

    /**
     * 方法名，决定执行什么操作。
     * 支持：initialize / tools/list / tools/call
     */
    private String method;

    /**
     * 方法参数，结构随 method 不同而不同。
     * 用 JsonNode 保持灵活性（不强制绑定到固定结构）。
     */
    private JsonNode params;
}
