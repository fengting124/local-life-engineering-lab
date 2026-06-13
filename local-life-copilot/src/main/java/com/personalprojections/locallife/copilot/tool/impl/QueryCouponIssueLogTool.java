package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.domain.dto.OrderSnapshot;
import com.personalprojections.locallife.copilot.domain.dto.OutboxMessageSnapshot;
import com.personalprojections.locallife.copilot.domain.mapper.CopilotOrderMapper;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.tool.McpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：query_coupon_issue_log —— 查询优惠券发放记录（L1 只读）。
 *
 * <h2>使用场景（设计文档 10.3 Step 2）</h2>
 * <p>订单异常排查第二步：
 * 当 {@code query_order} 返回「order_status=PAID + coupon_status=UNUSED」时，
 * 说明订单支付成功但券未发放。此工具查询 outbox_message 和 user_coupon 表，
 * 确认是 MQ 消费失败、库存不足还是其他原因。
 *
 * <h2>数据来源</h2>
 * <p>Transactional Outbox 模式：支付成功后，PaymentService 在同一事务内
 * 写入 outbox_message（PENDING），Relay 任务异步投递到 RocketMQ。
 * 消费者处理后更新 user_coupon.coupon_status = USED。
 *
 * <p>此工具查询 outbox_message 表，可以看到投递状态（PENDING/SENT/FAILED）
 * 和重试次数，从而判断是投递失败还是消费失败。
 *
 * <h2>权限</h2>
 * <p>此工具只对 cs 和 admin 开放（商家无权查看 MQ 内部状态），
 * 对应订单异常排查是客服/运营的工作场景。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryCouponIssueLogTool implements McpTool {

    private final CopilotOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "query_coupon_issue_log";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode orderIdProp = objectMapper.createObjectNode();
        orderIdProp.put("type", "string");
        orderIdProp.put("description", "订单号（与 query_order 的 order_id 参数格式相同）");

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("order_id", orderIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("order_id");

        return ToolDefinition.builder()
                .name("query_coupon_issue_log")
                .description(
                        "查询指定订单的优惠券发放日志，包括 MQ 投递状态、失败原因和重试次数。" +
                        "通常在 query_order 返回 coupon_status=UNUSED（且 order_status=PAID）后调用，" +
                        "用于定位券发放异常的根因。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "outbox_status=FAILED 且 error 含 'MQ 消费失败' → 继续调用 query_mq_dead_letter 查死信原因。" +
                        "outbox_status=PENDING 且 retry_count > 0 → MQ Broker 可能不可用，检查 RocketMQ 状态。" +
                        "outbox_status=SENT 且 coupon_status=UNUSED → 消费者处理失败，可能是库存不足或逻辑异常。" +
                        "没有 outbox 记录 → 支付回调事务未完成，Outbox 未写入，需排查 PaymentService 日志。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("cs", "admin"))  // 商家无权查看 MQ 内部状态
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        // ---- Step 1：参数提取 ----
        String orderNo = extractRequiredString(arguments, "order_id");
        log.info("[QueryCouponIssueLogTool] 查询券发放日志: orderNo={}", orderNo);

        // ---- Step 2：先查订单确认存在 ----
        OrderSnapshot order = orderMapper.selectOrderByOrderNo(orderNo);
        if (order == null || order.getOrderId() == null) {
            throw new ToolNotFoundException("订单不存在: " + orderNo);
        }

        String orderId = String.valueOf(order.getOrderId());

        // ---- Step 3：查询 Outbox 投递状态 ----
        // outbox_message.event_id 格式："{orderId}_paid"（PaymentService 写入时设置）
        List<OutboxMessageSnapshot> outboxLogs = orderMapper.selectOutboxByOrderId(orderId);

        // ---- Step 4：整理结果 ----
        Map<String, Object> couponInfo = new java.util.LinkedHashMap<>();
        couponInfo.put("coupon_status", order.getCouponStatus() != null ? order.getCouponStatus() : "NOT_USED");
        couponInfo.put("coupon_name", order.getCouponName() != null ? order.getCouponName() : "");
        couponInfo.put("coupon_discount", order.getCouponDiscount() != null ? order.getCouponDiscount() : 0);

        return Map.of(
                "order_id",         orderNo,
                "order_status",     order.getOrderStatus() != null ? order.getOrderStatus() : "UNKNOWN",
                "coupon",           couponInfo,
                "outbox_messages",  outboxLogs.isEmpty()
                        ? List.of(Map.of("status", "NO_RECORD",
                                "hint", "未找到 Outbox 记录，支付回调事务可能未完成"))
                        : outboxLogs.stream().map(this::toOutboxMap).toList(),
                "diagnosis",        diagnose(order, outboxLogs)
        );
    }

    /**
     * 根据 outbox 状态和券状态自动诊断问题根因，给 Agent 一个直接结论。
     *
     * <p>减少 Agent 推理步数：Agent 看到 diagnosis 字段后可以直接决策下一步，
     * 而不需要再次调用 LLM 分析原始数据。
     */
    private String diagnose(OrderSnapshot order, List<OutboxMessageSnapshot> outboxLogs) {
        String couponStatus = order.getCouponStatus() != null ? order.getCouponStatus() : "";
        String orderStatus  = order.getOrderStatus() != null ? order.getOrderStatus() : "";

        if (!"PAID".equals(orderStatus)) {
            return "订单状态为 " + orderStatus + "，不是 PAID，无需查券发放。";
        }
        if ("USED".equals(couponStatus) || "null".equals(couponStatus) || couponStatus.isEmpty()) {
            return "券已核销或未使用券，无异常。";
        }
        if (outboxLogs.isEmpty()) {
            return "未找到 Outbox 记录，可能是支付回调事务未提交，建议查看 PaymentService 日志。";
        }

        OutboxMessageSnapshot latest = outboxLogs.get(0);
        String outboxStatus = latest.getStatus() != null ? latest.getStatus() : "";
        int retryCount = latest.getRetryCount() != null ? latest.getRetryCount() : 0;

        if ("FAILED".equals(outboxStatus)) {
            return "Outbox 消息投递失败（重试 " + retryCount + " 次后放弃），" +
                   "建议调用 query_mq_dead_letter 查看死信详情，或直接人工补发券（issue_compensation_coupon）。";
        }
        if ("PENDING".equals(outboxStatus) && retryCount > 0) {
            return "Outbox 消息投递中但重试 " + retryCount + " 次，MQ Broker 可能不可用，建议检查 RocketMQ 状态。";
        }
        if ("SENT".equals(outboxStatus) && "UNUSED".equals(couponStatus)) {
            return "Outbox 消息已投递但券仍未核销，消费者处理失败（可能是库存不足）。" +
                   "建议调用 query_mq_dead_letter 确认死信原因。";
        }

        return "状态异常：outbox=" + outboxStatus + ", coupon=" + couponStatus + "，需人工排查。";
    }

    private Map<String, Object> toOutboxMap(OutboxMessageSnapshot message) {
        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("event_id", message.getEventId());
        result.put("topic", message.getTopic());
        result.put("tag", message.getTag());
        result.put("status", message.getStatus());
        result.put("retry_count", message.getRetryCount() != null ? message.getRetryCount() : 0);
        result.put("next_retry_at", message.getNextRetryAt() != null ? message.getNextRetryAt().toString() : null);
        result.put("created_at", message.getCreatedAt() != null ? message.getCreatedAt().toString() : null);
        return result;
    }

    private String extractRequiredString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", key + " 格式同订单号");
        }
        return node.asText().trim();
    }
}
