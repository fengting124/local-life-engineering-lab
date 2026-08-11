package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.domain.dto.ShopMetricsSnapshot;
import com.personalprojections.locallife.copilot.domain.mapper.CopilotOrderMapper;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.LinkedHashMap;
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

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ISO_LOCAL_DATE;

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

        ObjectNode startDateProp = objectMapper.createObjectNode();
        startDateProp.put("type", "string");
        startDateProp.put("description", "查询开始日期（含），格式 yyyy-MM-dd。");
        properties.set("start_date", startDateProp);

        ObjectNode endDateProp = objectMapper.createObjectNode();
        endDateProp.put("type", "string");
        endDateProp.put("description", "查询结束日期（含），格式 yyyy-MM-dd。");
        properties.set("end_date", endDateProp);

        ObjectNode shopIdProp = objectMapper.createObjectNode();
        shopIdProp.put("type", "string");
        shopIdProp.put("description",
                "门店 ID（可选）。不传则查该商家所有门店汇总数据；" +
                "传入则查特定门店的数据。");
        properties.set("shop_id", shopIdProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        var oneOf = inputSchema.putArray("oneOf");
        var singleDate = oneOf.addObject();
        singleDate.putArray("required").add("date");
        var forbiddenRangeFields = singleDate.putObject("not").putArray("anyOf");
        forbiddenRangeFields.addObject().putArray("required").add("start_date");
        forbiddenRangeFields.addObject().putArray("required").add("end_date");
        var dateRange = oneOf.addObject();
        var rangeRequired = dateRange.putArray("required");
        rangeRequired.add("start_date");
        rangeRequired.add("end_date");
        dateRange.putObject("not").putArray("required").add("date");

        return ToolDefinition.builder()
                .name("shop_metrics_query")
                .description(
                        "查询门店经营数据，包括订单量、GMV（总成交额，单位：分）、" +
                        "优惠券核销量、取消订单数。" +
                        "支持单日 date 或 start_date/end_date 日期范围。" +
                        "适用：商家询问经营情况、日报生成、活动效果评估。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "gmv 和 total_coupon_discount 单位均为分（整数），" +
                        "展示时除以 100 转换为元。" +
                        "若 order_count=0 且时间是今天，可能是当天尚未有订单，" +
                        "不要误判为数据异常。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("merchant", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        return execute(arguments, LocalDate.now());
    }

    Object execute(JsonNode arguments, LocalDate today) {
        // ---- Step 1：解析参数 ----
        DateRange range = resolveRange(arguments, today);
        String shopIdStr = arguments.has("shop_id") && !arguments.get("shop_id").isNull()
                ? arguments.get("shop_id").asText() : null;

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

        log.info("[ShopMetricsQueryTool] 查询经营数据: startDate={}, endDate={}, shopId={}, merchantId={}",
                range.startDate(), range.endDate(), shopId, merchantId);

        // ---- Step 3：查询数据库 ----
        // 如果 merchantId 为 null（cs/admin 不限商家），
        // 当前 SQL 会查所有商家的数据，面试时可以说「生产环境需要加商家权限过滤」
        ShopMetricsSnapshot metrics = orderMapper.selectShopMetrics(
                merchantId,
                range.startDate().format(DATE_FMT),
                range.endDate().format(DATE_FMT),
                shopId);

        if (metrics == null) {
            // 数据库无数据时返回全零（正常情况，该日期没有订单）
            metrics = new ShopMetricsSnapshot(0L, 0L, 0L, 0L, 0L);
        }

        // ---- Step 4：整理结果 ----
        Map<String, Object> result = new LinkedHashMap<>();
        if (range.singleDate()) {
            result.put("date", range.startDate().format(DATE_FMT));
        }
        result.put("start_date", range.startDate().format(DATE_FMT));
        result.put("end_date", range.endDate().format(DATE_FMT));
        result.put("merchant_id", merchantId != null ? merchantId.toString() : "all");
        result.put("shop_id", shopId != null ? shopId.toString() : "all");
        result.put("order_count", metrics.getOrderCount() != null ? metrics.getOrderCount() : 0L);
        result.put("gmv", metrics.getGmv() != null ? metrics.getGmv() : 0L);
        result.put("cancel_count", metrics.getCancelCount() != null ? metrics.getCancelCount() : 0L);
        result.put("coupon_used_count", metrics.getCouponUsedCount() != null ? metrics.getCouponUsedCount() : 0L);
        result.put("total_coupon_discount", metrics.getTotalCouponDiscount() != null ? metrics.getTotalCouponDiscount() : 0L);
        return result;
    }

    /**
     * 将日期关键词转换为 yyyy-MM-dd 格式。
     * 支持 today / yesterday / 直接日期字符串。
     */
    private DateRange resolveRange(JsonNode arguments, LocalDate today) {
        String date = textValue(arguments, "date");
        String startDate = textValue(arguments, "start_date");
        String endDate = textValue(arguments, "end_date");
        if (date != null && (startDate != null || endDate != null)) {
            throw new ToolParameterException(
                    "date 与 start_date/end_date 不能同时提供",
                    "单日使用 date；范围使用完整的 start_date 和 end_date");
        }
        if (date != null) {
            LocalDate resolved = resolveDate(date, today);
            return new DateRange(resolved, resolved, true);
        }
        if (startDate == null || endDate == null) {
            throw new ToolParameterException(
                    "start_date 和 end_date 必须同时提供",
                    "单日使用 date；范围使用完整的 start_date 和 end_date");
        }
        LocalDate start = parseDate(startDate, "start_date");
        LocalDate end = parseDate(endDate, "end_date");
        if (start.isAfter(end)) {
            throw new ToolParameterException(
                    "start_date 不能晚于 end_date",
                    "请提供有效的闭区间日期范围");
        }
        return new DateRange(start, end, false);
    }

    private LocalDate resolveDate(String date, LocalDate today) {
        return switch (date.toLowerCase().trim()) {
            case "today" -> today;
            case "yesterday" -> today.minusDays(1);
            default -> parseDate(date, "date");
        };
    }

    private LocalDate parseDate(String value, String field) {
        if (!value.matches("\\d{4}-\\d{2}-\\d{2}")) {
            throw invalidDate(field, value);
        }
        try {
            return LocalDate.parse(value, DATE_FMT);
        } catch (DateTimeParseException exception) {
            throw invalidDate(field, value);
        }
    }

    private ToolParameterException invalidDate(String field, String value) {
        return new ToolParameterException(
                field + " 格式错误: " + value,
                "格式：yyyy-MM-dd；date 还支持 today / yesterday");
    }

    private String textValue(JsonNode arguments, String key) {
        JsonNode node = arguments.get(key);
        return node == null || node.isNull() || node.asText().isBlank()
                ? null
                : node.asText().trim();
    }

    private record DateRange(LocalDate startDate, LocalDate endDate, boolean singleDate) {}
}
