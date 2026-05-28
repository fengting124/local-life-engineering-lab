package com.personalprojections.locallife.copilot.mcp.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import com.fasterxml.jackson.databind.node.ObjectNode;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;

/**
 * MCP 工具定义（tools/list 响应中的每个工具描述）。
 *
 * <p>Python Agent 通过 tools/list 获取工具列表，
 * 将这些 Schema 嵌入 LLM System Prompt，让模型知道有哪些工具可用。
 *
 * <h2>Schema 示例</h2>
 * <pre>{@code
 * {
 *   "name": "query_order",
 *   "description": "查询订单完整状态。用于判断订单、支付、券发放是否一致。",
 *   "inputSchema": {
 *     "type": "object",
 *     "properties": {
 *       "order_id": { "type": "string", "description": "订单号，格式：ORDER_{数字}" }
 *     },
 *     "required": ["order_id"]
 *   },
 *   "x-business-hint": "order_status=PAID 且 coupon_status=NOT_ISSUED 表示券发放异常，继续调用 query_coupon_issue_log。",
 *   "x-requires-hitl": false,
 *   "x-allowed-roles": ["merchant", "cs", "admin"]
 * }
 * }</pre>
 *
 * <h2>x-business-hint 的作用</h2>
 * <p>这是自定义扩展字段，不属于 MCP 标准字段。
 * 用于给 Agent 提供业务语义提示，帮助模型在工具返回结果后知道「下一步应该做什么」。
 * 比普通 description 更具体，减少 Agent 推理步数。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class ToolDefinition {

    /** 工具名称（snake_case，全局唯一） */
    private String name;

    /**
     * 工具描述（面向模型，必须清晰说明：什么时候用 + 用来做什么）。
     * Agent 根据此字段决定是否调用这个工具。
     */
    private String description;

    /**
     * 入参 JSON Schema（遵循 MCP inputSchema 规范）。
     * 模型会按此 Schema 生成工具调用参数。
     */
    private ObjectNode inputSchema;

    /**
     * 业务语义提示（MCP 扩展字段，非标准）。
     * 工具返回后，Agent 应根据此提示决定下一步动作（避免 Agent 推理迷失）。
     */
    private String xBusinessHint;

    /**
     * 是否需要 HITL 审批（true = 高风险动作，必须人工确认才能执行）。
     * Agent 检查此字段决定是否生成 hitl_request。
     */
    private boolean xRequiresHitl;

    /**
     * 允许调用此工具的角色列表。
     * MCP Server 在执行前做 RBAC 校验，角色不在列表中返回 permission_denied。
     */
    private List<String> xAllowedRoles;
}
