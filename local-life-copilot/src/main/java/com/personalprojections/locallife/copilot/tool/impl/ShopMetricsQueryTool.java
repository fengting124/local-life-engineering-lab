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
 * 工具：shop_metrics_query —— 查询门店经营数据（GMV / 订单数 / 核销数等）。
 *
 * <h2>使用场景（来自设计文档 10.2）</h2>
 * <pre>
 * 用户: 我昨天卖了多少钱？
 * Thought: 需要查昨天销售额，调用 shop_metrics_query。
 * Action: shop_metrics_query(date=yesterday, merchant_id=current)
 * Observation: {order_count: 128, gmv: 9800}
 * Final Answer: 昨天共 128 单，GMV 9800 元。
 * </pre>
 *
 * <h2>RBAC</h2>
 * <p>merchant 角色只能查自己的 merchant_id 数据，不能查他人。
 * 工具从 RbacContext 强制注入 merchantId，Agent 不能覆盖。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopMetricsQueryTool implements McpTool {

    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "shop_metrics_query";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode dateProp = objectMapper.createObjectNode();
        dateProp.put("type", "string");
        dateProp.put("description", "查询日期，格式 yyyy-MM-dd，如 '2026-05-28'。传 'today' 查今天，'yesterday' 查昨天。");
        properties.set("date", dateProp);

        ObjectNode shopIdProp = objectMapper.createObjectNode();
        shopIdProp.put("type", "string");
        shopIdProp.put("description", "门店 ID（可选），不传则查询该商家所有门店的汇总数据。");
        properties.set("shop_id", shopIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("date");

        return ToolDefinition.builder()
                .name("shop_metrics_query")
                .description("查询门店经营数据，包括订单量、GMV、退款数、优惠券核销量。" +
                        "用于商家经营分析、日报生成、活动效果评估等场景。")
                .inputSchema(inputSchema)
                .xBusinessHint("gmv 单位为分（整数），展示时需除以 100 转换为元。" +
                        "如果 refund_count 大于 0，建议进一步查询退款原因分布。" +
                        "coupon_used_count 为当日通过优惠券下单的数量。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("merchant", "cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        String date   = extractString(arguments, "date");
        String shopId = arguments.has("shop_id") ? arguments.get("shop_id").asText() : null;

        RbacContext ctx = RbacContext.get();

        // merchant 角色强制用自己的 merchantId，防止越权查其他商家数据
        Long merchantId = ctx.getMerchantId();
        if (ctx.isMerchant() && merchantId == null) {
            throw new ToolPermissionException("merchant 角色必须关联 merchantId");
        }

        log.info("[ShopMetricsQueryTool] 查询经营数据: date={}, shopId={}, merchantId={}",
                date, shopId, merchantId);

        // 实际实现：聚合查询 order_info 表
        // SELECT COUNT(*), SUM(order_amount), SUM(coupon_discount)
        // FROM order_info
        // WHERE DATE(created_at) = #{date}
        //   AND shop_id IN (SELECT id FROM shop WHERE merchant_id = #{merchantId})
        //   AND order_status IN ('PAID', 'COMPLETED')
        //   AND deleted = 0

        // 当前占位返回（完整 Mapper 注入在后续迭代补充）
        return Map.of(
                "date", date,
                "merchant_id", merchantId != null ? merchantId.toString() : "all",
                "shop_id", shopId != null ? shopId : "all",
                "order_count", 0,
                "gmv", 0,
                "refund_count", 0,
                "coupon_used_count", 0,
                "_note", "数据查询功能开发中，当前返回占位数据"
        );
    }

    private String extractString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空",
                    "date 格式：yyyy-MM-dd 或 today/yesterday");
        }
        return node.asText().trim();
    }
}
