package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.tool.McpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：query_coupon_issue_log —— 查询优惠券发放记录。
 *
 * <h2>使用场景（来自设计文档 10.3 Step 2）</h2>
 * <p>订单异常排查第二步：当 query_order 返回「支付成功但券未发放」时，
 * 调用此工具查询券发放日志，确认是 MQ 失败、库存不足还是其他原因。
 *
 * <pre>
 * Observation(query_order): {order_status: "PAID", coupon_status: "NOT_ISSUED"}
 * Thought: 需要查券发放日志确认失败原因。
 * Action: query_coupon_issue_log(order_id="ORDER_12345")
 * Observation: {status: "FAILED", error: "MQ 消费失败", retry_count: 3}
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryCouponIssueLogTool implements McpTool {

    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "query_coupon_issue_log";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode orderIdProp = objectMapper.createObjectNode();
        orderIdProp.put("type", "string");
        orderIdProp.put("description", "订单号（与 query_order 的 order_id 格式相同）");
        properties.set("order_id", orderIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("order_id");

        return ToolDefinition.builder()
                .name("query_coupon_issue_log")
                .description("查询指定订单的优惠券发放记录，包括发放状态、失败原因和重试次数。" +
                        "通常在 query_order 返回 coupon_status=NOT_ISSUED 后调用，用于定位券发放异常根因。")
                .inputSchema(inputSchema)
                .xBusinessHint("status=FAILED 且 error 包含 'MQ 消费失败' → 继续调用 query_mq_dead_letter 确认死信原因。" +
                        "status=FAILED 且 error 包含 'stock not enough' → 券库存不足，需人工处理（退款或补券）。" +
                        "status=SUCCESS 但 query_order 显示 NOT_ISSUED → 数据不一致，记录并上报。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        String orderId = extractString(arguments, "order_id");
        log.info("[QueryCouponIssueLogTool] 查询券发放日志: orderId={}", orderId);

        // 实际实现：查询 user_coupon 发放流水
        // SELECT uc.*, ct.coupon_name, ct.discount_type, ct.discount_value
        // FROM user_coupon uc
        // JOIN coupon_template ct ON uc.coupon_template_id = ct.id
        // WHERE uc.order_id = #{orderId} OR uc.used_at IS NOT NULL
        // 以及 outbox_message 的投递状态

        return Map.of(
                "order_id", orderId,
                "issue_status", "UNKNOWN",
                "retry_count", 0,
                "_note", "券发放日志查询功能开发中"
        );
    }

    private String extractString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", key + " 格式同订单号");
        }
        return node.asText().trim();
    }
}
