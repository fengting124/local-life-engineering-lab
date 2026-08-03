package com.personalprojections.locallife.copilot.hitl;

import java.util.Set;

/** Immutable, normalized business payload authorized by a HITL approval. */
public record ApprovalPayload(
        int payloadVersion,
        String toolName,
        String orderId,
        int amountMinor,
        String targetUserId,
        String merchantId,
        String requestedUserId,
        String requestedRole,
        String reason
) {
    public static final int SUPPORTED_VERSION = 1;
    private static final Set<String> SUPPORTED_TOOLS = Set.of(
            "execute_refund",
            "issue_compensation_coupon"
    );

    public ApprovalPayload {
        if (payloadVersion != SUPPORTED_VERSION) {
            throw new IllegalArgumentException("payloadVersion is unsupported");
        }
        toolName = required("toolName", toolName);
        if (!SUPPORTED_TOOLS.contains(toolName)) {
            throw new IllegalArgumentException("toolName is unsupported");
        }
        orderId = required("orderId", orderId);
        if (amountMinor <= 0) {
            throw new IllegalArgumentException("amountMinor must be positive");
        }
        targetUserId = optional(targetUserId);
        if ("issue_compensation_coupon".equals(toolName) && targetUserId.isEmpty()) {
            throw new IllegalArgumentException("targetUserId is required");
        }
        merchantId = optional(merchantId);
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
