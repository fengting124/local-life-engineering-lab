package com.personalprojections.locallife.copilot.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * MCP JSON-RPC 2.0 错误对象。
 *
 * <p>符合 JSON-RPC 2.0 规范的错误结构：
 * <pre>{@code
 * {
 *   "code": -32602,
 *   "message": "parameter_error",
 *   "data": {
 *     "reason": "parameter_error",
 *     "detail": "order_id 不能为空",
 *     "hint": "order_id 格式：ORDER_{数字}"
 *   }
 * }
 * }</pre>
 *
 * <h2>code 含义（JSON-RPC 标准）</h2>
 * <ul>
 *   <li>{@code -32700} Parse error</li>
 *   <li>{@code -32600} Invalid Request</li>
 *   <li>{@code -32601} Method not found</li>
 *   <li>{@code -32602} Invalid params（参数错误，最常用）</li>
 *   <li>{@code -32603} Internal error</li>
 * </ul>
 *
 * <h2>data 字段约定（MCP 业务错误扩展）</h2>
 * <p>data 字段遵循设计文档中的结构化错误协议：
 * <pre>{@code
 * {
 *   "reason": "parameter_error | permission_denied | not_found | tool_timeout | business_conflict",
 *   "detail": "具体错误描述",
 *   "hint": "Agent 修正提示（可选）"
 * }
 * }</pre>
 * Agent 可根据 {@code reason} 字段决定下一步策略：
 * 参数错误 → 修正参数重试；权限拒绝 → 停止调用；超时 → 重试一次。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class McpError {

    /** JSON-RPC 错误码（标准整数） */
    private int code;

    /** 错误类型（machine-readable reason，如 parameter_error / permission_denied） */
    private String message;

    /** 结构化错误详情（StructuredError），可为 null */
    private Object data;

    // =========================================================
    // 工厂方法：对应设计文档中的 5 种错误类型
    // =========================================================

    /** 参数错误：Agent 根据 hint 修正参数后可重试 */
    public static McpError parameterError(String detail, String hint) {
        return McpError.builder()
                .code(-32602)
                .message("parameter_error")
                .data(StructuredError.of("parameter_error", detail, hint))
                .build();
    }

    /** 权限拒绝：Agent 停止调用并告知权限边界 */
    public static McpError permissionDenied(String detail) {
        return McpError.builder()
                .code(-32602)
                .message("permission_denied")
                .data(StructuredError.of("permission_denied", detail, null))
                .build();
    }

    /** 资源不存在：Agent 换工具补充查询或告知无结果 */
    public static McpError notFound(String detail) {
        return McpError.builder()
                .code(-32602)
                .message("not_found")
                .data(StructuredError.of("not_found", detail, null))
                .build();
    }

    /** 工具超时：Agent 重试一次，仍失败则降级 */
    public static McpError toolTimeout(String toolName) {
        return McpError.builder()
                .code(-32603)
                .message("tool_timeout")
                .data(StructuredError.of("tool_timeout", toolName + " 执行超时", "可重试一次"))
                .build();
    }

    /** 业务状态冲突：Agent 调用查询工具补充证据 */
    public static McpError businessConflict(String detail) {
        return McpError.builder()
                .code(-32602)
                .message("business_conflict")
                .data(StructuredError.of("business_conflict", detail, "建议先调用查询工具确认当前状态"))
                .build();
    }

    /** 内部错误 */
    public static McpError internalError(String detail) {
        return McpError.builder()
                .code(-32603)
                .message("internal_error")
                .data(StructuredError.of("internal_error", detail, null))
                .build();
    }

    /**
     * 结构化错误详情（data 字段的具体类型）。
     * Agent 解析此结构决定下一步动作（修正参数、换工具、停止调用等）。
     */
    @Data
    @Builder
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class StructuredError {
        /** 错误原因标识（machine-readable） */
        private String reason;
        /** 错误详情（human-readable） */
        private String detail;
        /** Agent 修正提示（可选，帮助 Agent 自行修正并重试） */
        private String hint;

        public static StructuredError of(String reason, String detail, String hint) {
            return StructuredError.builder().reason(reason).detail(detail).hint(hint).build();
        }
    }
}
