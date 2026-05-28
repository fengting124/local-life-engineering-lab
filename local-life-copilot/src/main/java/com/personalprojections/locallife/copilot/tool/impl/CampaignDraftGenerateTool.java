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
 * 工具：campaign_draft_generate —— 生成活动草稿（L2 草稿生成，需用户确认）。
 *
 * <h2>设计文档 10.4 使用场景</h2>
 * <pre>
 *   用户: 帮我配一个国庆 5 折券，限 1000 张。
 *
 *   Thought: 先查商家当前经营数据和已有券策略，再生成活动草稿。
 *   Action: shop_metrics_query(date=yesterday)
 *   Observation: {gmv: 980000, order_count: 128}
 *
 *   Action: coupon_policy_lookup()
 *   Observation: [{coupon_name: "8折券", remaining_stock: 200}]
 *
 *   Action: campaign_draft_generate(campaign_type=PERCENT, discount_value=50,
 *                                   total_stock=1000, name="国庆5折活动")
 *   Observation: {draft: {...草稿内容...}}
 *
 *   [HITL：让商家确认活动草稿]
 * </pre>
 *
 * <h2>工具级别</h2>
 * <p>L2（草稿生成）：只生成草稿数据，不直接写库。草稿返回给 Agent，
 * Agent 展示给用户确认后（HITL），才调用活动创建 API 真正写入。
 *
 * <h2>草稿内容</h2>
 * <p>返回结构化的活动草稿（JSON），包含：
 * <ul>
 *   <li>券名称、折扣类型、折扣值</li>
 *   <li>建议的使用门槛（基于近期订单均价自动推算）</li>
 *   <li>建议的有效期（7天、14天等常见选项）</li>
 *   <li>预估成本（total_stock × 折扣面值）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CampaignDraftGenerateTool implements McpTool {

    private final CopilotOrderMapper orderMapper;
    private final ObjectMapper objectMapper;

    private static final DateTimeFormatter DATE_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd");

    @Override
    public String getName() {
        return "campaign_draft_generate";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();

        addProp(properties, "campaign_name", "string", "活动名称，如「国庆感恩节活动」");
        addProp(properties, "discount_type", "string",
                "折扣类型：CASH（现金券）/ PERCENT（折扣券）");
        addProp(properties, "discount_value", "integer",
                "折扣值：CASH 类型时为分（如 2000=减20元）；PERCENT 类型时为比例（如 80=8折）");
        addProp(properties, "total_stock", "integer", "发行总量（张），如 1000");
        addProp(properties, "valid_days", "integer", "有效期天数（从活动开始算），默认 14 天");
        addProp(properties, "min_order_amount", "integer",
                "最低使用门槛（分），0 表示无门槛，不传时自动推算");

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        inputSchema.putArray("required")
                .add("campaign_name").add("discount_type").add("discount_value").add("total_stock");

        return ToolDefinition.builder()
                .name("campaign_draft_generate")
                .description(
                        "根据商家提供的参数生成活动草稿，包含券规则、预估成本、建议有效期。" +
                        "生成的草稿需要商家确认后才会真正创建活动（需人工确认）。" +
                        "通常在 shop_metrics_query 查完经营数据后调用，基于真实数据给出建议。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "草稿生成后必须展示给商家确认（包含预估成本），" +
                        "得到商家明确同意后才能提交创建活动。" +
                        "预估成本 = total_stock × discount_value（CASH 类型）" +
                        "或 total_stock × avg_order_amount × (1 - discount_value/100)（PERCENT 类型）。")
                .xRequiresHitl(false)   // 草稿生成本身无需 HITL，提交创建时才需要
                .xAllowedRoles(List.of("merchant", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        RbacContext ctx = RbacContext.get();

        String campaignName   = extractRequiredString(arguments, "campaign_name");
        String discountType   = extractRequiredString(arguments, "discount_type");
        int    discountValue  = extractRequiredInt(arguments, "discount_value");
        int    totalStock     = extractRequiredInt(arguments, "total_stock");
        int    validDays      = arguments.has("valid_days") ? arguments.get("valid_days").asInt(14) : 14;

        // 验证折扣类型
        if (!"CASH".equals(discountType) && !"PERCENT".equals(discountType)) {
            throw new ToolParameterException(
                    "discount_type 只能是 CASH 或 PERCENT，当前值：" + discountType,
                    "CASH=现金券（减多少元），PERCENT=折扣券（打几折）");
        }
        if ("PERCENT".equals(discountType) && (discountValue <= 0 || discountValue >= 100)) {
            throw new ToolParameterException(
                    "折扣比例必须在 1-99 之间，当前值：" + discountValue,
                    "示例：80 = 8折，50 = 5折");
        }

        Long merchantId = ctx.getMerchantId();

        log.info("[CampaignDraftGenerateTool] 生成活动草稿: merchant={}, name={}, type={}, value={}",
                merchantId, campaignName, discountType, discountValue);

        // 查近期经营数据（自动推算使用门槛）
        int suggestedMinAmount = 0;
        if (!arguments.has("min_order_amount") || arguments.get("min_order_amount").isNull()) {
            Map<String, Object> metrics = orderMapper.selectShopMetrics(
                    merchantId, LocalDate.now().minusDays(7).format(DATE_FMT), null);
            if (metrics != null) {
                Number orderCount = (Number) metrics.getOrDefault("order_count", 0L);
                Number gmv        = (Number) metrics.getOrDefault("gmv", 0L);
                long avgOrder = orderCount.longValue() > 0
                        ? gmv.longValue() / orderCount.longValue() : 0L;
                // 建议门槛 = 近7天平均订单金额的 80%（取整到百）
                suggestedMinAmount = (int) (avgOrder * 0.8 / 100) * 100;
            }
        } else {
            suggestedMinAmount = arguments.get("min_order_amount").asInt(0);
        }

        // 计算预估成本
        long estimatedCostCents = estimateCost(discountType, discountValue, totalStock);

        // 生成草稿
        LocalDate startDate = LocalDate.now().plusDays(1); // 建议明天开始
        LocalDate endDate   = startDate.plusDays(validDays);

        // Map.of() 最多支持 10 个 entry，超出时用 LinkedHashMap
        java.util.Map<String, Object> draft = new java.util.LinkedHashMap<>();
        draft.put("draft_status",         "PENDING_CONFIRM");
        draft.put("campaign_name",         campaignName);
        draft.put("discount_type",         discountType);
        draft.put("discount_value",        discountValue);
        draft.put("discount_desc",         formatDiscount(discountType, discountValue));
        draft.put("total_stock",           totalStock);
        draft.put("min_order_amount",      suggestedMinAmount);
        draft.put("min_order_desc",        suggestedMinAmount == 0 ? "无门槛" : "满" + (suggestedMinAmount / 100) + "元可用");
        draft.put("valid_days",            validDays);
        draft.put("suggested_start",       startDate.format(DATE_FMT));
        draft.put("suggested_end",         endDate.format(DATE_FMT));
        draft.put("estimated_cost_cents",  estimatedCostCents);
        draft.put("estimated_cost_desc",   "预估成本：约 " + (estimatedCostCents / 100) + " 元（按全部发放计算）");
        draft.put("next_step",             "请确认以上活动方案，确认后将正式创建活动。如需修改请说明。");
        return draft;
    }

    private long estimateCost(String discountType, int discountValue, int totalStock) {
        if ("CASH".equals(discountType)) {
            // 现金券：每张面值 × 发行量
            return (long) discountValue * totalStock;
        }
        // 折扣券：成本估算 = 折扣率 × 假设平均订单金额（5000分=50元）× 发行量
        // 简化估算，实际应基于历史平均订单金额
        long assumedAvgOrder = 5000L;
        return (long)(assumedAvgOrder * (100 - discountValue) / 100.0 * totalStock);
    }

    private String formatDiscount(String discountType, int discountValue) {
        if ("CASH".equals(discountType)) {
            return "满减券：减 " + (discountValue / 100) + " 元";
        }
        return "折扣券：打 " + (discountValue / 10.0) + " 折";
    }

    private ObjectNode addProp(ObjectNode parent, String key, String type, String desc) {
        ObjectNode prop = objectMapper.createObjectNode();
        prop.put("type", type);
        prop.put("description", desc);
        parent.set(key, prop);
        return prop;
    }

    private String extractRequiredString(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull() || node.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", null);
        }
        return node.asText().trim();
    }

    private int extractRequiredInt(JsonNode args, String key) {
        JsonNode node = args.get(key);
        if (node == null || node.isNull()) {
            throw new ToolParameterException(key + " 不能为空", null);
        }
        return node.asInt(0);
    }
}
