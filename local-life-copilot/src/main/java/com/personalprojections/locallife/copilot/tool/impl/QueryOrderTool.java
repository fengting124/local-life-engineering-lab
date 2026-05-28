package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：query_order —— 查询订单完整状态。
 *
 * <h2>使用场景（来自设计文档 10.3）</h2>
 * <p>订单异常排查第一步：判断「订单已支付 + 支付成功 + 券未发放」的不一致状态，
 * 再根据结果决定下一步调用哪个工具（query_coupon_issue_log / query_mq_dead_letter）。
 *
 * <h2>x-business-hint 的价值</h2>
 * <p>工具描述中嵌入了业务语义提示，让 Agent 不需要纯靠 LLM 推理就知道：
 * 「order_status=PAID 且 coupon_status=NOT_ISSUED → 续调 query_coupon_issue_log」
 * 减少 ReAct 循环步数，提升任务完成率。
 *
 * <h2>RBAC</h2>
 * <p>merchant 角色只能查自己门店（shop_id in 当前 merchant 的门店列表）的订单，
 * 实际过滤在 SQL WHERE 中加 merchant_id 限制。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class QueryOrderTool implements McpTool {

    private final ObjectMapper objectMapper;

    // 直接操作与 LocalLife Server 共享的同一个 MySQL（开发阶段）
    // 使用原生 MyBatis-Plus 查询，共享 application.yml 中的 datasource 配置
    // 注：实际项目中，这些 Mapper 可以单独在 copilot 模块中重新声明（只读查询）
    // 或通过 HTTP 调用 LocalLife Server 的内部 API（生产推荐方式）

    @Override
    public String getName() {
        return "query_order";
    }

    @Override
    public ToolDefinition getDefinition() {
        // 构建 inputSchema（JSON Schema 格式）
        ObjectNode properties = objectMapper.createObjectNode();
        ObjectNode orderIdProp = objectMapper.createObjectNode();
        orderIdProp.put("type", "string");
        orderIdProp.put("description", "订单号，格式为纯数字字符串（雪花 ID），如 '1234567890123456789'");
        properties.set("order_id", orderIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("order_id");

        return ToolDefinition.builder()
                .name("query_order")
                .description("查询订单完整状态，包括订单状态、支付状态、券状态和金额信息。" +
                        "用于判断订单、支付、优惠券三者是否一致，是订单异常排查的第一步。")
                .inputSchema(inputSchema)
                .xBusinessHint("返回结果解读：" +
                        "order_status=PAID 且 payment_status=SUCCESS 且 coupon_status=NOT_ISSUED → 券发放异常，继续调用 query_coupon_issue_log。" +
                        "order_status=WAIT_PAY 且 payment_status=SUCCESS → 支付回调未处理，继续调用 query_payment。" +
                        "order_status=CANCELLED → 订单已关闭，无需进一步处理。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("merchant", "cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        // ---- 参数提取与校验 ----
        String orderId = extractString(arguments, "order_id");

        log.info("[QueryOrderTool] 查询订单: orderId={}", orderId);

        // ---- RBAC 校验 ----
        // merchant 角色：需要校验订单是否属于自己的门店（此处简化，生产需加 merchantId 过滤）
        RbacContext ctx = RbacContext.get();
        if (ctx.isMerchant() && ctx.getMerchantId() == null) {
            throw new ToolPermissionException("merchant 角色必须提供 merchantId");
        }

        // ---- 查询数据库 ----
        // 简化实现：直接返回模拟数据结构（真实实现注入 OrderInfoMapper）
        // 完整实现见 README 中的「工具实现扩展指南」
        return queryOrderFromDb(orderId, ctx);
    }

    /**
     * 查询订单数据。
     *
     * <p>当前实现：返回结构化结果（实际项目中注入 OrderInfoMapper + PaymentOrderMapper + UserCouponMapper）。
     * 结构与设计文档中的 outputSchema 一致，Agent 可根据字段值推断下一步动作。
     */
    private Map<String, Object> queryOrderFromDb(String orderId, RbacContext ctx) {
        // 实际项目中的实现示例（伪代码）：
        // OrderInfo order = orderInfoMapper.selectOne(
        //     new LambdaQueryWrapper<OrderInfo>()
        //         .eq(OrderInfo::getOrderNo, orderId)
        //         .eq(ctx.isMerchant(), OrderInfo::getShopMerchantId, ctx.getMerchantId())
        // );
        // if (order == null) throw new ToolNotFoundException("订单不存在: " + orderId);
        // PaymentOrder payment = paymentOrderMapper.selectLatestByOrderId(order.getId());
        // UserCoupon coupon = order.getUserCouponId() != null
        //     ? userCouponMapper.selectById(order.getUserCouponId()) : null;
        //
        // return Map.of(
        //     "order_id", orderId,
        //     "order_status", order.getOrderStatus(),
        //     "original_amount", order.getOriginalAmount(),
        //     "order_amount", order.getOrderAmount(),
        //     "coupon_discount", order.getCouponDiscount(),
        //     "payment_status", payment != null ? payment.getPayStatus() : "NO_PAYMENT",
        //     "coupon_status", coupon != null ? coupon.getCouponStatus() : "NOT_USED",
        //     "shop_id", String.valueOf(order.getShopId()),
        //     "created_at", order.getCreatedAt().toString()
        // );

        // 当前返回占位响应，避免 Bean 循环依赖（完整 Mapper 注入在后续迭代补充）
        throw new ToolNotFoundException("订单不存在或无权查询: " + orderId +
                "（提示：MCP Server 当前版本的 OrderInfoMapper 注入在开发迭代中完善）");
    }

    private String extractString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", key + " 格式：纯数字字符串（雪花 ID）");
        }
        return node.asText().trim();
    }
}
