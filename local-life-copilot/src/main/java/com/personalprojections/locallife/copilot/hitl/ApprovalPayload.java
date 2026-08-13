package com.personalprojections.locallife.copilot.hitl;

/** Immutable, normalized business payload authorized by a HITL approval. */
public record ApprovalPayload(
        int payloadVersion,
        String toolName,
        String orderId,
        int amountMinor,
        String targetUserId,
        String shopId,
        String merchantId,
        String couponTemplateId,
        String couponDiscountType,
        int couponMinOrderAmount,
        int couponValidDays,
        String couponTermsDigest,
        String requestedUserId,
        String requestedRole,
        String reason
) {
    public static final int REFUND_VERSION = 1;
    public static final int COMPENSATION_VERSION = 2;
    public static final int SUPPORTED_VERSION = REFUND_VERSION;

    public ApprovalPayload(
            int payloadVersion, String toolName, String orderId, int amountMinor,
            String targetUserId, String merchantId, String requestedUserId,
            String requestedRole, String reason
    ) {
        this(payloadVersion, toolName, orderId, amountMinor, targetUserId, "",
                merchantId, "", "", 0, 0, "", requestedUserId,
                requestedRole, reason);
    }

    public ApprovalPayload {
        toolName = required("toolName", toolName);
        orderId = required("orderId", orderId);
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        targetUserId = optional(targetUserId);
        shopId = optional(shopId);
        merchantId = optional(merchantId);
        couponTemplateId = optional(couponTemplateId);
        couponDiscountType = optional(couponDiscountType);
        couponTermsDigest = optional(couponTermsDigest).toLowerCase();
        if (payloadVersion == REFUND_VERSION) {
            if (!"execute_refund".equals(toolName)) {
                throw new IllegalArgumentException("payloadVersion does not support toolName");
            }
        } else if (payloadVersion == COMPENSATION_VERSION) {
            if (!"issue_compensation_coupon".equals(toolName)) {
                throw new IllegalArgumentException("payloadVersion does not support toolName");
            }
            targetUserId = required("targetUserId", targetUserId);
            shopId = required("shopId", shopId);
            merchantId = required("merchantId", merchantId);
            couponTemplateId = required("couponTemplateId", couponTemplateId);
            if (!"CASH".equals(couponDiscountType)) {
                throw new IllegalArgumentException("couponDiscountType must be CASH");
            }
            if (couponMinOrderAmount < 0) {
                throw new IllegalArgumentException("couponMinOrderAmount must be non-negative");
            }
            if (couponValidDays <= 0) {
                throw new IllegalArgumentException("couponValidDays must be positive");
            }
            if (!couponTermsDigest.matches("(?i)[0-9a-f]{64}")) {
                throw new IllegalArgumentException("couponTermsDigest must be SHA-256 hex");
            }
        } else {
            throw new IllegalArgumentException("payloadVersion is unsupported");
        }
        requestedUserId = required("requestedUserId", requestedUserId);
        requestedRole = required("requestedRole", requestedRole);
        reason = required("reason", reason);
    }

    private static String required(String field, String value) {
        String normalized = optional(value);
        if (normalized.isEmpty()) {
            throw new IllegalArgumentException(field + " is required");
        }
        return normalized;
    }

    private static String optional(String value) {
        return value == null ? "" : value.trim();
    }
}
