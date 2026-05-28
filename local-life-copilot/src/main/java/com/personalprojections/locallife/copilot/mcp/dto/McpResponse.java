package com.personalprojections.locallife.copilot.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP JSON-RPC 2.0 响应体。
 *
 * <h2>成功响应（tools/call）</h2>
 * <pre>{@code
 * {
 *   "jsonrpc": "2.0",
 *   "id": "req-001",
 *   "result": {
 *     "content": [{ "type": "text", "text": "{\"order_status\":\"PAID\",...}" }]
 *   }
 * }
 * }</pre>
 *
 * <h2>错误响应</h2>
 * <pre>{@code
 * {
 *   "jsonrpc": "2.0",
 *   "id": "req-001",
 *   "error": {
 *     "code": -32602,
 *     "message": "parameter_error",
 *     "data": { "reason": "parameter_error", "detail": "order_id 不能为空", "hint": "..." }
 *   }
 * }
 * }</pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpResponse {

    @Builder.Default
    private String jsonrpc = "2.0";

    private String id;

    /** 成功结果，与 error 互斥 */
    private Object result;

    /** 错误信息，与 result 互斥 */
    private McpError error;

    // =========================================================
    // 工厂方法
    // =========================================================

    public static McpResponse success(String id, Object result) {
        return McpResponse.builder()
                .id(id)
                .result(result)
                .build();
    }

    public static McpResponse error(String id, McpError mcpError) {
        return McpResponse.builder()
                .id(id)
                .error(mcpError)
                .build();
    }
}
