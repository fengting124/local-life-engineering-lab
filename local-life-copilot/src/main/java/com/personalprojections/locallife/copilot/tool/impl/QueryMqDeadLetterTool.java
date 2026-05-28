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
 * 工具：query_mq_dead_letter —— 查询 MQ 死信消息（L1 只读）。
 *
 * <h2>死信队列的业务含义</h2>
 * <p>RocketMQ 默认重试 16 次后将消息移入死信队列（DLQ）。
 * 对于支付成功事件消费失败的场景，死信队列的消息包含了消费失败的原因：
 * <ul>
 *   <li>「券库存不足」→ 根因：在秒杀场景中，券库存设置过少，或并发超卖了</li>
 *   <li>「coupon_template_id 不存在」→ 根因：券模板被删除，但 Outbox 事件还未消费</li>
 *   <li>「Redis 不可用」→ 根因：消费者依赖 Redis 幂等判重，Redis 故障时抛异常</li>
 * </ul>
 *
 * <h2>当前实现说明</h2>
 * <p>死信消息存储在 RocketMQ Broker 的 DLQ Topic 中（%DLQ%payment-success-consumer-group）。
 * 直接查询 RocketMQ 需要 RocketMQ Admin 客户端，增加复杂度。
 * 当前实现通过查 outbox_message 表的 FAILED 状态来近似代替：
 * outbox_message.status=FAILED 意味着 Relay 任务投递失败（未到 MQ），
 * 而真正的死信场景（投递成功但消费失败）在生产中需要对接 RocketMQ Admin API。
 *
 * <h2>面试说法</h2>
 * <p>「当前通过查 outbox_message 近似表示消息失败状态，
 * 生产环境中对接 RocketMQ Admin REST API 拉取真实死信消息。
 * 设计上已经预留了这个扩展点，只需替换 execute() 的数据源即可。」
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryMqDeadLetterTool implements McpTool {

    private final CopilotOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "query_mq_dead_letter";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode orderIdProp = objectMapper.createObjectNode();
        orderIdProp.put("type", "string");
        orderIdProp.put("description", "订单号（用于关联查询该订单相关的死信消息）");

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("order_id", orderIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("order_id");

        return ToolDefinition.builder()
                .name("query_mq_dead_letter")
                .description(
                        "查询与订单关联的死信消息记录，包含投递失败原因和重试次数。" +
                        "在 query_coupon_issue_log 发现 MQ 消费失败时调用，用于定位根因。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "error 含 'stock not enough' → 券库存不足，订单资金已到账，需要退款或人工补券。" +
                        "error 含 'Redis' → Redis 不可用时幂等判重失败，等 Redis 恢复后重置 Outbox 重发。" +
                        "error 含 'template not found' → 券模板已被删除，无法补发，需退款。" +
                        "没有死信记录 → MQ 消费尚未失败或已成功，检查其他环节。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        String orderNo = extractRequiredString(arguments, "order_id");
        log.info("[QueryMqDeadLetterTool] 查询死信消息: orderNo={}", orderNo);

        // 先查订单确认存在
        Map<String, Object> order = orderMapper.selectOrderByOrderNo(orderNo);
        if (order == null || order.isEmpty()) {
            throw new ToolNotFoundException("订单不存在: " + orderNo);
        }

        String orderId = String.valueOf(order.get("order_id"));

        // 查 FAILED 状态的 Outbox 消息（近似死信）
        List<Map<String, Object>> failedMessages = orderMapper.selectOutboxByOrderId(orderId);
        List<Map<String, Object>> deadLetters = failedMessages.stream()
                .filter(m -> "FAILED".equals(String.valueOf(m.get("status"))))
                .toList();

        String diagnosis = deadLetters.isEmpty()
                ? "未发现 FAILED 状态的消息记录，消息可能还在重试中或已成功投递"
                : analyzeDl(deadLetters.get(0));

        return Map.of(
                "order_id",     orderNo,
                "dead_letters", deadLetters,
                "count",        deadLetters.size(),
                "diagnosis",    diagnosis,
                "note",         "当前基于 outbox_message 近似，生产应对接 RocketMQ Admin API 获取真实死信"
        );
    }

    private String analyzeDl(Map<String, Object> dl) {
        String eventId  = String.valueOf(dl.getOrDefault("event_id", ""));
        int retryCount  = ((Number) dl.getOrDefault("retry_count", 0)).intValue();
        String payload  = String.valueOf(dl.getOrDefault("payload", ""));

        if (payload.contains("stock")) {
            return "【库存不足】券库存耗尽，无法发券。建议：退款 或 issue_compensation_coupon（补发等额优惠）。";
        }
        return "消息投递失败（重试 " + retryCount + " 次），eventId=" + eventId + "。需人工介入处理。";
    }

    private String extractRequiredString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", null);
        }
        return node.asText().trim();
    }
}
