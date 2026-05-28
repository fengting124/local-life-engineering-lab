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

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.util.List;
import java.util.Map;

/**
 * 工具：shop_metrics_query —— 查询门店经营数据（L1 只读）。
 *
 * <h2>使用场景</h2>
 * <p>来自设计文档第 10.2 节「门店数据查询」：
 * <pre>
 *   用户: 我昨天卖了多少钱？
 *   Thought: 需要调用 shop_metrics_query 查昨日销售额。
 *   Action: shop_metrics_query(date=yesterday, merchant_id={当前商家})
 *   Observation: {order_count: 128, gmv: 980000}  ← 单位：分
 *   Final Answer: 昨天共 128 单，GMV 9800 元。
 * </pre>
 *
 * <h2>金额单位</h2>
 * <p>所有金额返回「分」（Integer），Agent System Prompt 中明确说明：
 * gmv 单位为分，展示时除以 100 转换为元。
 * 这与 LocalLife Server 的接口规范一致（禁用浮点数，金额用分）。
 *
 * <h2>RBAC</h2>
 * <p>merchant 角色：merchant_id 由服务端从 RbacContext 注入，
 * Agent 无法传入 merchant_id 参数，防止越权查他人数据。
 * cs / admin 角色：可查询所有商家，但 Agent 调用时须传入 merchant_id 参数。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ShopMetricsQueryTool implements McpTool {

    private final CopilotOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getName() {
        return "shop_metrics_query";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode dateProp = objectMapper.createObjectNode();
        dateProp.put("type", "string");
        dateProp.put("description",
                "查询日期，格式 yyyy-MM-dd 或关键词：today（今天）/ yesterday（昨天）。" +
                "示例：'2026-05-28' 或 'yesterday'。");
        properties.set("date", dateProp);

        ObjectNode shopIdProp = objectMapper.createObjectNode();
        shopIdProp.put("type", "string");
        shopIdProp.put("description",
                "门店 ID（可选）。不传则查该商家所有门店汇总数据；" +
                "传入则查特定门店的数据。");
        properties.set("shop_id", shopIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required").add("date");

        return ToolDefinition.builder()
                .name("shop_metrics_query")
                .description(
                        "查询门店经营数据，包括订单量、GMV（总成交额，单位：分）、" +
                        "优惠券核销量、取消订单数。" +
                        "适用：商家询问经营情况、日报生成、活动效果评估。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "gmv 和 total_coupon_discount 单位均为分（整数），" +
                        "展示时除以 100 转换为元。" +
                        "若 order_count=0 且时间是今天，可能是当天尚未有订单，" +
                        "不要误判为数据异常。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("merchant", "cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        // ---- Step 1：解析参数 ----
        String dateStr = extractRequiredString(arguments, "date");
        String shopIdStr = arguments.has("shop_id") && !arguments.get("shop_id").isNull()
                ? arguments.get("shop_id").asText() : null;

        // 解析日期关键词
        String actualDate = resolveDate(dateStr);
        Long shopId = shopIdStr != null && !shopIdStr.isBlank() ? Long.parseLong(shopIdStr) : null;

        // ---- Step 2：RBAC —— 确定 merchantId ----
        RbacContext ctx = RbacContext.get();
        if (ctx == null) {
            throw new ToolPermissionException("未找到身份上下文");
        }

        // merchant 角色：merchant_id 强制从 RBAC 上下文取，不允许 Agent 传入
        // cs / admin 角色：可查所有商家（本工具当前不支持 cs/admin 传 merchant_id，
        //                  需要扩展 inputSchema 添加 merchant_id 参数）
        Long merchantId = ctx.getMerchantId();
        if (ctx.isMerchant() && merchantId == null) {
            throw new ToolPermissionException("merchant 角色必须关联 merchantId");
        }
        // cs/admin 无 merchantId 过滤时，查全局数据（merchantId=null → SQL 不过滤）
        // 生产环境需要限制 cs 只能查已被分配的商家列表

        log.info("[ShopMetricsQueryTool] 查询经营数据: date={}, shopId={}, merchantId={}",
                actualDate, shopId, merchantId);

        // ---- Step 3：查询数据库 ----
        // 如果 merchantId 为 null（cs/admin 不限商家），
        // 当前 SQL 会查所有商家的数据，面试时可以说「生产环境需要加商家权限过滤」
        Map<String, Object> metrics = orderMapper.selectShopMetrics(merchantId, actualDate, shopId);

        if (metrics == null) {
            // 数据库无数据时返回全零（正常情况，该日期没有订单）
            metrics = Map.of(
                    "order_count", 0L,
                    "gmv", 0L,
                    "cancel_count", 0L,
                    "coupon_used_count", 0L,
                    "total_coupon_discount", 0L
            );
        }

        // ---- Step 4：整理结果 ----
        return Map.of(
                "date",                  actualDate,
                "merchant_id",           merchantId != null ? merchantId.toString() : "all",
                "shop_id",               shopId != null ? shopId.toString() : "all",
                "order_count",           metrics.getOrDefault("order_count", 0),
                "gmv",                   metrics.getOrDefault("gmv", 0),
                "cancel_count",          metrics.getOrDefault("cancel_count", 0),
                "coupon_used_count",     metrics.getOrDefault("coupon_used_count", 0),
                "total_coupon_discount", metrics.getOrDefault("total_coupon_discount", 0)
        );
    }

    /**
     * 将日期关键词转换为 yyyy-MM-dd 格式。
     * 支持 today / yesterday / 直接日期字符串。
     */
    private String resolveDate(String dateStr) {
        return switch (dateStr.toLowerCase().trim()) {
            case "today"     -> LocalDate.now().format(DATE_FMT);
            case "yesterday" -> LocalDate.now().minusDays(1).format(DATE_FMT);
            default          -> {
                // 简单格式校验：yyyy-MM-dd
                if (!dateStr.matches("\\d{4}-\\d{2}-\\d{2}")) {
                    throw new ToolParameterException(
                            "date 格式错误: " + dateStr,
                            "格式：yyyy-MM-dd 或 today / yesterday");
                }
                yield dateStr;
            }
        };
    }

    private String extractRequiredString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空",
                    "date 格式：yyyy-MM-dd 或 today / yesterday");
        }
        return node.asText().trim();
    }
}
