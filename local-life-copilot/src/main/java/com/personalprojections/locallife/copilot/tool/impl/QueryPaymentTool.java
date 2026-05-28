package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.domain.mapper.CopilotOrderMapper;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.tool.McpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：query_payment —— 查询支付单状态（L1 只读）。
 *
 * <h2>支付单与订单的关系</h2>
 * <p>一笔订单可以有多条支付单（每次点「去支付」都创建一条新的 payment_order）。
 * 常见的异常场景：
 * <ul>
 *   <li>用户支付了但订单状态仍为 WAIT_PAY → 支付回调未到（网络问题或渠道延迟）</li>
 *   <li>payment_order.pay_status=SUCCESS 但 order_info.order_status=WAIT_PAY → 回调处理失败</li>
 *   <li>用户声称扣款了 → 查 trade_no 确认渠道是否真实到账</li>
 * </ul>
 *
 * <h2>使用顺序</h2>
 * <p>通常在 query_order 发现「订单待支付但用户声称已付款」时调用：
 * <pre>
 *   Observation(query_order): {order_status: "WAIT_PAY"}
 *   Thought: 用户说已支付，检查支付单状态。
 *   Action: query_payment(order_id="ORDER_12345")
 *   Observation: {pay_status: "SUCCESS", trade_no: "渠道流水号"}
 *   Thought: 支付成功但订单未更新，可能是回调没处理，联系技术支持。
 * </pre>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryPaymentTool implements McpTool {

    private final CopilotOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "query_payment";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode orderIdProp = objectMapper.createObjectNode();
        orderIdProp.put("type", "string");
        orderIdProp.put("description", "订单号（与 query_order 相同的 order_no）");

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("order_id", orderIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("order_id");

        return ToolDefinition.builder()
                .name("query_payment")
                .description(
                        "查询订单的所有支付单记录，包括支付状态、渠道流水号（trade_no）、" +
                        "实付金额和支付时间。适用：用户反映已付款但订单状态异常时使用。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "pay_status=SUCCESS 但 order_status=WAIT_PAY → 支付回调处理失败，" +
                        "需技术团队重触发回调或手动更新订单状态。" +
                        "pay_status=FAILED 且只有一条记录 → 用户支付失败，让用户重新支付。" +
                        "trade_no 非空 → 渠道有真实扣款记录，可以用 trade_no 去渠道后台核对。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        String orderNo = extractRequiredString(arguments, "order_id");
        log.info("[QueryPaymentTool] 查询支付单: orderNo={}", orderNo);

        // 先查订单拿到 order_id（数据库主键）
        Map<String, Object> order = orderMapper.selectOrderByOrderNo(orderNo);
        if (order == null || order.isEmpty()) {
            throw new ToolNotFoundException("订单不存在: " + orderNo);
        }

        Long orderId = toLong(order.get("order_id"));
        List<Map<String, Object>> payments = orderMapper.selectPaymentsByOrderId(orderId);

        return Map.of(
                "order_no",    orderNo,
                "order_status", order.getOrDefault("order_status", "UNKNOWN"),
                "payments",    payments.isEmpty()
                        ? List.of(Map.of("note", "该订单从未发起过支付"))
                        : payments,
                "payment_count", payments.size()
        );
    }

    private Long toLong(Object val) {
        if (val == null) return null;
        if (val instanceof Number n) return n.longValue();
        return Long.parseLong(val.toString());
    }

    private String extractRequiredString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", null);
        }
        return node.asText().trim();
    }
}
