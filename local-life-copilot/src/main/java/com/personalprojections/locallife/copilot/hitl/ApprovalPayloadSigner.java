package com.personalprojections.locallife.copilot.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Canonical JSON and HMAC-SHA-256 contract shared with the Python Agent. */
public class ApprovalPayloadSigner {

    private final ObjectMapper objectMapper;
    private final byte[] signingSecret;

    public ApprovalPayloadSigner(
            ObjectMapper objectMapper,
            String signingSecret
    ) {
        String normalized = signingSecret == null ? "" : signingSecret.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException("hitl payload signing secret is required");
        }
        this.objectMapper = objectMapper;
        this.signingSecret = normalized.getBytes(StandardCharsets.UTF_8);
    }

    String canonicalJson(ApprovalPayload payload) {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("payload_version", payload.payloadVersion());
        fields.put("tool_name", payload.toolName());
        fields.put("order_id", payload.orderId());
        fields.put("amount_minor", payload.amountMinor());
        fields.put("target_user_id", payload.targetUserId());
        if (payload.payloadVersion() == ApprovalPayload.COMPENSATION_VERSION) {
            fields.put("shop_id", payload.shopId());
            fields.put("merchant_id", payload.merchantId());
            fields.put("coupon_template_id", payload.couponTemplateId());
            fields.put("coupon_discount_type", payload.couponDiscountType());
            fields.put("coupon_min_order_amount", payload.couponMinOrderAmount());
            fields.put("coupon_valid_days", payload.couponValidDays());
            fields.put("coupon_terms_digest", payload.couponTermsDigest());
        } else {
            fields.put("merchant_id", payload.merchantId());
        }
        fields.put("requested_user_id", payload.requestedUserId());
        fields.put("requested_role", payload.requestedRole());
        fields.put("reason", payload.reason());
        try {
            return objectMapper.writeValueAsString(fields);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("failed to canonicalize HITL approval payload", e);
        }
    }

    public String sign(ApprovalPayload payload) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(signingSecret, "HmacSHA256"));
            return HexFormat.of().formatHex(
                    mac.doFinal(canonicalJson(payload).getBytes(StandardCharsets.UTF_8))
            );
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign HITL approval payload", e);
        }
    }

    public boolean matches(ApprovalPayload payload, String suppliedDigest) {
        if (suppliedDigest == null || !suppliedDigest.matches("(?i)[0-9a-f]{64}")) {
            return false;
        }
        byte[] expected = HexFormat.of().parseHex(sign(payload));
        byte[] supplied = HexFormat.of().parseHex(suppliedDigest.toLowerCase());
        return MessageDigest.isEqual(expected, supplied);
    }
}
