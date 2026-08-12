package com.personalprojections.locallife.server.module.internal;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.Map;

/** Stable, human-readable coupon terms protected by the approval digest. */
public record CouponTerms(
        int termsVersion,
        String couponTemplateId,
        String shopId,
        String merchantId,
        String discountType,
        int discountValue,
        int minOrderAmount,
        int validDays
) {
    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    public CouponTerms {
        if (termsVersion != 1) {
            throw new IllegalArgumentException("termsVersion is unsupported");
        }
        couponTemplateId = required("couponTemplateId", couponTemplateId);
        shopId = required("shopId", shopId);
        merchantId = required("merchantId", merchantId);
        discountType = required("discountType", discountType);
        if (discountValue <= 0 || minOrderAmount < 0 || validDays <= 0) {
            throw new IllegalArgumentException("coupon terms contain invalid numeric values");
        }
    }

    public String canonicalJson() {
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("terms_version", termsVersion);
        fields.put("coupon_template_id", couponTemplateId);
        fields.put("shop_id", shopId);
        fields.put("merchant_id", merchantId);
        fields.put("discount_type", discountType);
        fields.put("discount_value", discountValue);
        fields.put("min_order_amount", minOrderAmount);
        fields.put("valid_days", validDays);
        try {
            return OBJECT_MAPPER.writeValueAsString(fields);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("failed to canonicalize coupon terms", error);
        }
    }

    public String digest() {
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonicalJson().getBytes(StandardCharsets.UTF_8))
            );
        } catch (NoSuchAlgorithmException error) {
            throw new IllegalStateException("SHA-256 is unavailable", error);
        }
    }

    private static String required(String field, String value) {
        String normalized = value == null ? "" : value.trim();
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }
}
