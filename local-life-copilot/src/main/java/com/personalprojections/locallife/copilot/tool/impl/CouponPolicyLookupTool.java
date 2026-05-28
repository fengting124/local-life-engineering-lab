package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.domain.mapper.CopilotCouponMapper;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.List;
import java.util.Map;

/**
 * 工具：coupon_policy_lookup —— 查询优惠券策略和活动规则（L1 只读）。
 *
 * <h2>使用场景</h2>
 * <p>当商家询问「某张券的规则是什么」或「活动限购是怎么设置的」时，
 * Agent 调用此工具查询 coupon_template 表获取规则详情。
 * 通常配合 knowledge_search 使用（一个查数据库，一个查文档）。
 *
 * <h2>返回结构说明</h2>
 * <ul>
 *   <li>{@code discount_type} = CASH → {@code discount_value} 单位为分（如 2000 = 减20元）</li>
 *   <li>{@code discount_type} = PERCENT → {@code discount_value} 为折扣比例（如 80 = 8折）</li>
 *   <li>{@code min_order_amount} 为使用门槛（分），0 表示无门槛</li>
 *   <li>{@code status} = ACTIVE 表示可抢，INACTIVE/SOLD_OUT 不可抢</li>
 * </ul>
 *
 * <h2>RBAC</h2>
 * <p>merchant 角色只能查询自己门店关联的券模板，admin/cs 可查所有。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class CouponPolicyLookupTool implements McpTool {

    private final CopilotCouponMapper couponMapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "coupon_policy_lookup";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();

        ObjectNode couponIdProp = objectMapper.createObjectNode();
        couponIdProp.put("type", "string");
        couponIdProp.put("description", "券模板 ID（可选，不传则查该商家所有活动券）");
        properties.set("coupon_template_id", couponIdProp);

        ObjectNode statusProp = objectMapper.createObjectNode();
        statusProp.put("type", "string");
        statusProp.put("description", "按状态过滤（可选）：ACTIVE / INACTIVE / SOLD_OUT");
        properties.set("status", statusProp);

        ObjectNode inputSchema = objectMapper.createObjectNode();
        inputSchema.put("type", "object");
        inputSchema.set("properties", properties);
        // 无必填参数（全部可选）

        return ToolDefinition.builder()
                .name("coupon_policy_lookup")
                .description(
                        "查询优惠券模板的规则和配置，包括折扣类型、面值、使用门槛、" +
                        "数量上限、有效期。用于解释券规则、验证活动配置、辅助活动规划。")
                .inputSchema(inputSchema)
                .xBusinessHint(
                        "discount_type=CASH 时 discount_value 单位为分（2000=减20元）。" +
                        "discount_type=PERCENT 时 discount_value 为折扣比例（80=8折）。" +
                        "min_order_amount=0 表示无使用门槛。" +
                        "total_stock 和 remaining_stock 之差为已发放数量。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("merchant", "cs", "admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        RbacContext ctx = RbacContext.get();

        String couponTemplateId = arguments.has("coupon_template_id")
                && !arguments.get("coupon_template_id").isNull()
                ? arguments.get("coupon_template_id").asText() : null;

        String status = arguments.has("status") && !arguments.get("status").isNull()
                ? arguments.get("status").asText() : null;

        Long merchantId = ctx.getMerchantId();

        log.info("[CouponPolicyLookupTool] 查询券策略: merchantId={}, couponTemplateId={}, status={}",
                merchantId, couponTemplateId, status);

        if (couponTemplateId != null && !couponTemplateId.isBlank()) {
            // 查询单个券模板
            Map<String, Object> coupon = couponMapper.selectCouponTemplateById(Long.parseLong(couponTemplateId));
            if (coupon == null) {
                throw new ToolNotFoundException("券模板不存在: " + couponTemplateId);
            }
            // merchant 角色：校验该券是否属于自己（通过关联门店的 merchant_id）
            if (ctx.isMerchant() && merchantId != null) {
                Object cmid = coupon.get("merchant_id");
                if (cmid != null && !merchantId.toString().equals(cmid.toString())) {
                    throw new ToolPermissionException("无权查询该券模板（不属于当前商家）");
                }
            }
            return coupon;
        } else {
            // 查询商家所有券模板（merchant 角色强制过滤自己的）
            List<Map<String, Object>> coupons = couponMapper.selectCouponTemplatesByMerchant(merchantId, status);
            return Map.of(
                    "merchant_id", merchantId != null ? merchantId.toString() : "all",
                    "status_filter", status != null ? status : "all",
                    "count",        coupons.size(),
                    "coupons",      coupons
            );
        }
    }
}
