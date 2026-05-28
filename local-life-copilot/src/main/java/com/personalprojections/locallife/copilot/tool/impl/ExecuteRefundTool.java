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
 * 工具：execute_refund —— 执行退款（L4 高风险，必须 HITL）。
 *
 * <h2>HITL 设计</h2>
 * <p>此工具标注 {@code x-requires-hitl: true}，Python Agent Service 看到此标记后：
 * <ol>
 *   <li>不直接调用此工具</li>
 *   <li>生成 HITL Request 写入 hitl_approval 表（PENDING）</li>
 *   <li>LangGraph interrupt 挂起当前 thread</li>
 *   <li>运营在审批工作台看到 PENDING 请求并审批</li>
 *   <li>审批通过后恢复 thread，此时才真正调用 execute_refund</li>
 * </ol>
 *
 * <h2>执行流程（来自设计文档 10.3 Step 5）</h2>
 * <pre>
 * Action: request_human_approval(action_type="refund", order_id="ORDER_12345", amount=2990, reason="券库存不足")
 * [运营审批通过]
 * Action: execute_refund(order_id="ORDER_12345", amount=2990, approval_id="APPROVAL_001")
 * Observation: {refund_status: "SUCCESS", refund_id: "REFUND_998877"}
 * </pre>
 *
 * <h2>幂等保障</h2>
 * <p>退款调用 LocalLife Server 的内部 API，后者通过 refund_no 唯一索引保证幂等。
 * 即使 Agent 重复调用，只有第一次生效，后续返回原退款单号。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ExecuteRefundTool implements McpTool {

    private final ObjectMapper objectMapper;
    private final com.personalprojections.locallife.copilot.client.LocalLifeInternalClient internalClient;

    @Override
    public String getName() {
        return "execute_refund";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode orderIdProp = objectMapper.createObjectNode();
        orderIdProp.put("type", "string");
        orderIdProp.put("description", "订单号");
        properties.set("order_id", orderIdProp);

        ObjectNode amountProp = objectMapper.createObjectNode();
        amountProp.put("type", "integer");
        amountProp.put("description", "退款金额（分），必须 <= 订单实付金额");
        properties.set("amount", amountProp);

        ObjectNode approvalIdProp = objectMapper.createObjectNode();
        approvalIdProp.put("type", "string");
        approvalIdProp.put("description", "HITL 审批 ID，由审批通过后自动填入，不需要手动构造");
        properties.set("approval_id", approvalIdProp);

        ObjectNode reasonProp = objectMapper.createObjectNode();
        reasonProp.put("type", "string");
        reasonProp.put("description", "退款原因（用于审计和用户通知）");
        properties.set("reason", reasonProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("order_id").add("amount").add("approval_id").add("reason");

        return ToolDefinition.builder()
                .name("execute_refund")
                .description("【L4 高风险】对已支付订单执行退款。影响资金，必须人工审批通过后才能调用。" +
                        "调用前必须完成 HITL 审批流程并获得 approval_id。")
                .inputSchema(inputSchema)
                .xBusinessHint("此工具执行后不可逆。退款成功后主动告知用户退款单号和预计到账时间（3-5 个工作日）。" +
                        "退款失败时（status=FAILED）记录原因并上报，不要重试，联系技术支持。")
                .xRequiresHitl(true)   // 标记：Python Agent 看到此标志后必须先走审批流程
                .xAllowedRoles(List.of("cs", "admin"))  // merchant 不能自行退款
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        String orderId    = extractString(arguments, "order_id");
        int amount        = extractInt(arguments, "amount");
        String approvalId = extractString(arguments, "approval_id");
        String reason     = extractString(arguments, "reason");

        log.info("[ExecuteRefundTool] 执行退款: orderId={}, amount={}分, approvalId={}, reason={}",
                orderId, amount, approvalId, reason);

        // 调用 LocalLife Server 内部退款 API（POST /internal/orders/{orderNo}/refund）
        // 内部 API 会：
        // 1. 校验订单存在且状态为 PAID
        // 2. 校验退款金额 <= 实付金额
        // 3. 更新订单状态
        // 4. 返回退款单号
        java.util.Map<String, Object> result = internalClient.refund(orderId, amount, approvalId, reason);
        return result;
    }

    private String extractString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", null);
        }
        return node.asText().trim();
    }

    private int extractInt(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) {
            throw new ToolParameterException(key + " 不能为空", key + " 为整数（分）");
        }
        int val = node.asInt(0);
        if (val <= 0) {
            throw new ToolParameterException(key + " 必须大于 0", null);
        }
        return val;
    }
}
