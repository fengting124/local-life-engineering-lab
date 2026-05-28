package com.personalprojections.locallife.copilot.tool;

import com.fasterxml.jackson.databind.JsonNode;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;

/**
 * MCP 工具接口（所有工具实现此接口）。
 *
 * <h2>工具分级（来自设计文档）</h2>
 * <ul>
 *   <li>L1 只读查询：query_order / shop_metrics_query 等 → RBAC + 审计</li>
 *   <li>L2 草稿生成：campaign_draft_generate → 审计 + 用户确认</li>
 *   <li>L3 低风险写：create_campaign_draft → 审计 + 幂等</li>
 *   <li>L4 高风险执行：execute_refund / issue_compensation_coupon → HITL + 审批 + 审计</li>
 * </ul>
 *
 * <h2>工具调用流程</h2>
 * <pre>
 *   McpController.toolsCall(request)
 *     → ToolRegistry.get(name)
 *     → RBAC 权限校验
 *     → tool.execute(arguments)
 *     → ToolAuditService.record(...)
 *     → 返回 MCP result
 * </pre>
 */
public interface McpTool {

    /**
     * 工具名称（snake_case，与 Python Agent 调用的工具名对应）。
     * 示例：query_order / shop_metrics_query / execute_refund
     */
    String getName();

    /**
     * 工具定义（Schema），返回给 tools/list 端点。
     * 包含 name / description / inputSchema / x-business-hint / x-requires-hitl。
     */
    ToolDefinition getDefinition();

    /**
     * 执行工具调用。
     *
     * <p>工具实现：
     * <ol>
     *   <li>从 arguments 提取参数（配合校验）</li>
     *   <li>从 {@link com.personalprojections.locallife.copilot.rbac.RbacContext} 获取身份做权限过滤</li>
     *   <li>执行业务查询或操作</li>
     *   <li>返回结构化结果（JSON 字符串，或 Map/DTO）</li>
     * </ol>
     *
     * <p>执行过程中的错误应抛出：
     * <ul>
     *   <li>{@link ToolParameterException} — 参数错误，Agent 可修正重试</li>
     *   <li>{@link ToolPermissionException} — 权限拒绝，Agent 停止调用</li>
     *   <li>{@link ToolNotFoundException} — 资源不存在，Agent 换工具查询</li>
     *   <li>{@link ToolBusinessException} — 业务冲突，Agent 先查询再决策</li>
     * </ul>
     *
     * @param arguments 工具入参（来自 tools/call params.arguments）
     * @return 工具执行结果（将被包装成 MCP content[0].text 返回给 Agent）
     */
    Object execute(JsonNode arguments);

    // =========================================================
    // 工具异常类（静态内部类，避免过多文件）
    // =========================================================

    class ToolParameterException extends RuntimeException {
        private final String hint;
        public ToolParameterException(String detail, String hint) {
            super(detail);
            this.hint = hint;
        }
        public String getHint() { return hint; }
    }

    class ToolPermissionException extends RuntimeException {
        public ToolPermissionException(String detail) { super(detail); }
    }

    class ToolNotFoundException extends RuntimeException {
        public ToolNotFoundException(String detail) { super(detail); }
    }

    class ToolBusinessException extends RuntimeException {
        public ToolBusinessException(String detail) { super(detail); }
    }
}
