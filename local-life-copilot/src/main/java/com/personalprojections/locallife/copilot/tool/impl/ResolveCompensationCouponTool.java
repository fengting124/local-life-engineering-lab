package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.domain.dto.CompensationCouponResolution;
import com.personalprojections.locallife.copilot.domain.mapper.CompensationCouponMapper;
import com.personalprojections.locallife.copilot.mcp.dto.ToolDefinition;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

@Component
@RequiredArgsConstructor
public class ResolveCompensationCouponTool implements McpTool {

    private final CompensationCouponMapper mapper;
    private final ObjectMapper objectMapper;

    @Override
    public String getName() {
        return "resolve_compensation_coupon";
    }

    @Override
    public ToolDefinition getDefinition() {
        ObjectNode properties = objectMapper.createObjectNode();
        properties.set("order_id", stringProperty("业务订单号；不得传数据库主键"));
        ObjectNode amount = objectMapper.createObjectNode();
        amount.put("type", "integer");
        amount.put("minimum", 1);
        amount.put("description", "用户明确要求的补偿面值，单位分");
        properties.set("amount_minor", amount);
        ObjectNode schema = objectMapper.createObjectNode();
        schema.put("type", "object");
        schema.set("properties", properties);
        schema.putArray("required").add("order_id").add("amount_minor");
        return ToolDefinition.builder()
                .name(getName())
                .description("按订单门店和明确面值唯一解析补偿券模板及审批条款。")
                .inputSchema(schema)
                .xBusinessHint("只允许将返回的订单派生目标和券条款用于补偿审批；不得自行选择模板。")
                .xRequiresHitl(false)
                .xAllowedRoles(List.of("admin"))
                .build();
    }

    @Override
    public Object execute(JsonNode arguments) {
        RbacContext context = RbacContext.get();
        if (context == null || !context.isAdmin()) {
            throw new ToolPermissionException("仅管理员可解析补偿券配置");
        }
        String orderNo = requiredText(arguments, "order_id");
        int amountMinor = requiredPositiveInt(arguments, "amount_minor");
        CompensationCouponResolution resolution = mapper.resolve(orderNo, amountMinor);
        if (resolution == null
                || !"ACTIVE".equals(resolution.getTemplateStatus())
                || !"CASH".equals(resolution.getDiscountType())
                || !Integer.valueOf(amountMinor).equals(resolution.getDiscountValue())
                || !Integer.valueOf(1).equals(resolution.getBindingEnabled())) {
            throw new ToolNotFoundException("未找到唯一且可用的门店补偿券配置");
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("order_id", String.valueOf(resolution.getOrderId()));
        result.put("order_no", resolution.getOrderNo());
        result.put("target_user_id", String.valueOf(resolution.getTargetUserId()));
        result.put("shop_id", String.valueOf(resolution.getShopId()));
        result.put("merchant_id", String.valueOf(resolution.getMerchantId()));
        result.put("amount_minor", amountMinor);
        result.put("coupon_template_id", String.valueOf(resolution.getCouponTemplateId()));
        result.put("coupon_discount_type", resolution.getDiscountType());
        result.put("coupon_min_order_amount", resolution.getMinOrderAmount());
        result.put("coupon_valid_days", resolution.getValidDays());
        result.put("coupon_terms_digest", digest(resolution));
        return result;
    }

    private ObjectNode stringProperty(String description) {
        ObjectNode property = objectMapper.createObjectNode();
        property.put("type", "string");
        property.put("description", description);
        return property;
    }

    private String requiredText(JsonNode arguments, String key) {
        JsonNode value = arguments.get(key);
        if (value == null || value.asText().isBlank()) {
            throw new ToolParameterException(key + " 不能为空", "提供业务订单号和明确补偿金额");
        }
        return value.asText().trim();
    }

    private int requiredPositiveInt(JsonNode arguments, String key) {
        JsonNode value = arguments.get(key);
        if (value == null || !value.canConvertToInt() || value.asInt() <= 0) {
            throw new ToolParameterException(key + " 必须是正整数", "金额单位为分");
        }
        return value.asInt();
    }

    private String digest(CompensationCouponResolution value) {
        Map<String, Object> terms = new LinkedHashMap<>();
        terms.put("terms_version", 1);
        terms.put("coupon_template_id", String.valueOf(value.getCouponTemplateId()));
        terms.put("shop_id", String.valueOf(value.getShopId()));
        terms.put("merchant_id", String.valueOf(value.getMerchantId()));
        terms.put("discount_type", value.getDiscountType());
        terms.put("discount_value", value.getDiscountValue());
        terms.put("min_order_amount", value.getMinOrderAmount());
        terms.put("valid_days", value.getValidDays());
        try {
            byte[] canonical = objectMapper.writeValueAsString(terms).getBytes(StandardCharsets.UTF_8);
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical));
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("无法生成补偿券条款摘要", error);
        }
    }
}
