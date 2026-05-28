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

/**
 * 工具：issue_compensation_coupon —— 发放补偿优惠券（L4 高风险，必须 HITL）。
 *
 * <h2>业务场景</h2>
 * <p>当订单支付成功但原券无法发放（库存耗尽）时，
 * 可以为用户发放一张等额或更高价值的补偿券，作为退款的替代方案。
 *
 * <p>优势：
 * <ul>
 *   <li>对平台：资金不回流（避免退款损失）</li>
 *   <li>对用户：拿到的券可能面值更高，体验补偿</li>
 * </ul>
 *
 * <h2>HITL 必要性</h2>
 * <p>发放补偿券影响平台权益（相当于额外成本），
 * 且需要确认补偿方案（补多少面值的券）由人工决定，不能完全交给 AI。
 * 此工具标注 {@code x-requires-hitl: true}，
 * Agent 在 hitl_node 中生成审批请求后挂起，人工确认后才真正调用。
 *
 * <h2>与 execute_refund 的选择逻辑</h2>
 * <p>Agent 判断应该退款还是补券的逻辑：
 * <ul>
 *   <li>用户明确要求退款 → execute_refund</li>
 *   <li>死信原因是「库存不足」且补偿券面值 >= 订单金额 → issue_compensation_coupon</li>
 *   <li>死信原因是「系统错误」→ 先尝试修复，再重发，最后才退款/补券</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class IssueCompensationCouponTool implements McpTool {

    private final ObjectMapper objectMapper;
    private final com.personalprojections.locallife.copilot.client.LocalLifeInternalClient internalClient;

    @Override
    public String getName() {
        return "issue_compensation_coupon";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode userIdProp = objectMapper.createObjectNode();
        userIdProp.put("type", "string");
        userIdProp.put("description", "需要补偿的用户 ID");
        properties.set("user_id", userIdProp);

        ObjectNode orderIdProp = objectMapper.createObjectNode();
        orderIdProp.put("type", "string");
        orderIdProp.put("description", "关联订单号（用于审计追踪）");
        properties.set("order_id", orderIdProp);

        ObjectNode amountProp = objectMapper.createObjectNode();
        amountProp.put("type", "integer");
        amountProp.put("description", "补偿券面值（分），通常等于原订单金额");
        properties.set("compensation_amount", amountProp);

        ObjectNode reasonProp = objectMapper.createObjectNode();
        reasonProp.put("type", "string");
        reasonProp.put("description", "补偿原因（写入用户通知和审计日志）");
        properties.set("reason", reasonProp);

        ObjectNode approvalIdProp = objectMapper.createObjectNode();
        approvalIdProp.put("type", "string");
        approvalIdProp.put("description", "HITL 审批 ID（审批通过后自动填入）");
        properties.set("approval_id", approvalIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required")
                .add("user_id").add("order_id").add("compensation_amount").add("reason").add("approval_id");

        return ToolDefinition.builder()
                .name("issue_compensation_coupon")
                .description(
                        "【L4 高风险】为用户发放补偿优惠券，作为退款的替代方案。" +
                        "适用场景：订单支付成功但原券因库存不足无法发放。" +
                        "调用前必须完成 HITL 审批并获取 approval_id。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "此工具执行成功后，用户会收到券通知。" +
                        "执行后需要更新订单备注，标记「已补偿」，避免后续重复补偿。" +
                        "compensation_amount 建议等于或略高于原订单金额，体现诚意。")
                .xRequiresHitl(true)
                .xAllowedRoles(List.of("cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        String userId            = extractRequiredString(arguments, "user_id");
        String orderId           = extractRequiredString(arguments, "order_id");
        int    compensationAmount = arguments.get("compensation_amount").asInt(0);
        String reason            = extractRequiredString(arguments, "reason");
        String approvalId        = extractRequiredString(arguments, "approval_id");

        if (compensationAmount <= 0) {
            throw new ToolParameterException("compensation_amount 必须大于 0", "单位为分，如 2000 表示 20 元");
        }

        log.info("[IssueCompensationCouponTool] 发放补偿券: userId={}, orderId={}, amount={}分, approvalId={}",
                userId, orderId, compensationAmount, approvalId);

        // 调用 LocalLife Server 内部补偿券 API（POST /internal/orders/{orderNo}/compensate-coupon）
        return internalClient.compensateCoupon(orderId, userId, compensationAmount, approvalId, reason);
    }

    private String extractRequiredString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", null);
        }
        return node.asText().trim();
    }
}
