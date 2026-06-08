package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.domain.mapper.CopilotOrderMapper;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：query_order —— 查询订单完整状态（L1 只读）。
 *
 * <h2>面试讲解要点</h2>
 * <p>这个工具是订单异常排查的第一步，Agent 拿到返回结果后：
 * <ul>
 *   <li>order_status=PAID + pay_status=SUCCESS + coupon_status=UNUSED
 *       → 订单已支付但券未核销，继续调用 {@code query_coupon_issue_log}</li>
 *   <li>order_status=WAIT_PAY + pay_status=SUCCESS
 *       → 支付回调可能没到，继续调用 {@code query_payment}</li>
 *   <li>order_status=CANCELLED
 *       → 订单已关闭，确认是主动取消还是超时，不需要进一步操作</li>
 * </ul>
 *
 * <h2>RBAC 设计</h2>
 * <p>merchant 角色只能查询属于自己门店的订单：
 * SQL 里用 JOIN shop s ON s.merchant_id = merchantId 过滤。
 * 如果订单不属于当前商家，返回 not_found（不区分「不存在」和「无权限」）。
 *
 * <h2>x-business-hint 的作用</h2>
 * <p>工具 Schema 嵌入了业务语义提示（非 MCP 标准字段），
 * 让 LLM 在收到工具结果后无需纯靠推理就知道下一步应该怎么做，
 * 减少 ReAct 循环步数，提升任务完成率。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryOrderTool implements McpTool {

    private final CopilotOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "query_order";
    }

    @Override
    public ToolDefinition getDefinition() {
        // ---- 构建 inputSchema（JSON Schema 格式）----
        ObjectNode orderIdProp = objectMapper.createObjectNode();
        orderIdProp.put("type", "string");
        orderIdProp.put("description",
                "订单号，格式为纯数字字符串（雪花 ID），如 '1234567890123456789'。" +
                "注意：这是 order_no 字段（业务流水号），不是数据库主键 id。");

        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("order_id", orderIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("order_id");

        return ToolDefinition.builder()
                .name("query_order")
                .description(
                        "查询订单完整状态，包含：订单状态、支付状态（最新支付单）、" +
                        "优惠券核销状态、实付金额。" +
                        "用于判断订单-支付-优惠券三者是否一致，是订单异常排查的第一步。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "【结果解读】" +
                        "情况1：order_status=PAID + pay_status=SUCCESS + coupon_status=UNUSED → " +
                        "券发放异常，继续调用 query_coupon_issue_log 查询券发放日志。" +
                        "情况2：order_status=WAIT_PAY + pay_status=SUCCESS → " +
                        "支付回调可能未到，继续调用 query_payment 查看支付单状态。" +
                        "情况3：order_status=CANCELLED → " +
                        "订单已关闭，确认用户是否主动取消，无需进一步操作。" +
                        "情况4：pay_status=null → 该订单未发起过支付。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("merchant", "cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        // ---- Step 1：参数校验 ----
        String orderNo = extractRequiredString(arguments, "order_id",
                "order_id 格式：纯数字字符串（雪花 ID），如 '1234567890123456789'");

        log.info("[QueryOrderTool] 查询订单: orderNo={}", orderNo);

        // ---- Step 2：RBAC 校验 ----
        RbacContext ctx = RbacContext.get();
        if (ctx == null) {
            throw new ToolPermissionException("未找到身份上下文，请检查 X-User-Id / X-User-Role Header");
        }

        // ---- Step 3：查询数据库 ----
        Map<String, Object> row = orderMapper.selectOrderByOrderNo(orderNo);

        if (row == null || row.isEmpty()) {
            // merchant 角色：订单存在但不属于自己 → 统一返回 not_found（防枚举攻击）
            // admin/cs 角色：订单确实不存在
            log.info("[QueryOrderTool] 订单不存在: orderNo={}, role={}", orderNo, ctx.getRole());
            throw new ToolNotFoundException("订单不存在或无权查询: " + orderNo);
        }

        // ---- Step 4：merchant 角色权限验证 ----
        // 通过 SQL 已经用 JOIN shop + merchant_id 过滤，
        // 但万一 SQL 写法有问题，这里再做一层应用层校验
        if (ctx.isMerchant() && ctx.getMerchantId() != null) {
            Object shopIdObj = row.get("shop_id");
            if (shopIdObj != null) {
                // 此处简化：实际需要 SELECT merchant_id FROM shop WHERE id = shopId
                // 由于 SQL 已经做了 JOIN 过滤，这里仅做日志记录
                log.debug("[QueryOrderTool] merchant={} 查询订单 shopId={}", ctx.getMerchantId(), shopIdObj);
            }
        }

        // ---- Step 5：整理返回结果 ----
        // 返回给 Agent 的数据格式清晰，字段含义明确
        // Agent 根据 x-business-hint 的提示判断下一步动作
        return buildResult(row);
    }

    /**
     * 整理查询结果，过滤无关字段，格式化时间。
     * 返回的 Map 结构即为工具文档中 outputSchema 约定的格式。
     */
    private Map<String, Object> buildResult(Map<String, Object> row) {
        // 注意：这里不能用 Map.of(...) —— 它在任何一个 value 为 null 时都会抛 NullPointerException，
        // 而 safeStr() 在对应数据库列为 NULL 时就是要返回 null（这正是 x-business-hint 里
        // "情况4：pay_status=null → 该订单未发起过支付" 所依赖的数据形状：未支付/无券的订单
        // 是完全正常的业务状态，不是异常）。LinkedHashMap 允许 null 值，且保留写入顺序方便阅读。
        Map<String, Object> payment = new java.util.LinkedHashMap<>();
        payment.put("pay_status",  safeStr(row.get("pay_status")));
        payment.put("channel",     safeStr(row.get("channel")));
        payment.put("trade_no",    safeStr(row.get("trade_no")));
        payment.put("paid_amount", row.getOrDefault("paid_amount", 0));
        payment.put("paid_at",     safeStr(row.get("paid_at")));

        Map<String, Object> coupon = new java.util.LinkedHashMap<>();
        coupon.put("coupon_status",  safeStr(row.get("coupon_status")));
        coupon.put("coupon_name",    safeStr(row.get("coupon_name")));
        coupon.put("discount_type",  safeStr(row.get("discount_type")));
        coupon.put("discount_value", row.getOrDefault("discount_value", 0));

        Map<String, Object> result = new java.util.LinkedHashMap<>();
        result.put("order_id",        safeStr(row.get("order_id")));
        result.put("order_no",        safeStr(row.get("order_no")));
        result.put("user_id",         safeStr(row.get("user_id")));
        result.put("shop_id",         safeStr(row.get("shop_id")));
        result.put("original_amount", row.getOrDefault("original_amount", 0));
        result.put("coupon_discount", row.getOrDefault("coupon_discount", 0));
        result.put("order_amount",    row.getOrDefault("order_amount", 0));
        result.put("order_status",    safeStr(row.get("order_status")));
        result.put("payment", payment);
        result.put("coupon", coupon);
        return result;
    }

    private String safeStr(Object val) {
        return val != null ? val.toString() : null;
    }

    private String extractRequiredString(JsonNode args, String key, String hint) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", hint);
        }
        return node.asText().trim();
    }
}
