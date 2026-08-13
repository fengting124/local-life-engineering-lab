package com.personalprojections.locallife.copilot.hitl;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;
import java.util.Iterator;
import java.util.Locale;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

/** Validates, atomically claims, and idempotently completes HITL approvals. */
@Component
public class ApprovalExecutionGuard {

    private static final Set<String> SENSITIVE_KEY_FRAGMENTS = Set.of(
            "key", "token", "secret", "password", "authorization", "cookie"
    );

    private final HitlApprovalMapper mapper;
    private final ApprovalPayloadSigner signer;
    private final ObjectMapper objectMapper;
    private final Clock clock;
    private final Duration leaseDuration;

    @Autowired
    public ApprovalExecutionGuard(
            HitlApprovalMapper mapper,
            ObjectMapper objectMapper,
            @Value("${hitl.payload-signing.secret}") String signingSecret
    ) {
        this(
                mapper,
                new ApprovalPayloadSigner(objectMapper, signingSecret),
                objectMapper,
                Clock.systemUTC(),
                Duration.ofMinutes(2)
        );
    }

    ApprovalExecutionGuard(
            HitlApprovalMapper mapper,
            ApprovalPayloadSigner signer,
            ObjectMapper objectMapper,
            Clock clock,
            Duration leaseDuration
    ) {
        this.mapper = mapper;
        this.signer = signer;
        this.objectMapper = objectMapper;
        this.clock = clock;
        this.leaseDuration = leaseDuration;
    }

    public ExecutionDecision claim(
            String approvalId,
            String suppliedDigest,
            ApprovalPayload payload,
            RbacContext caller
    ) {
        long id = parseApprovalId(approvalId);
        HitlApprovalRecord approval = mapper.selectApproval(id);
        validate(approval, suppliedDigest, payload, caller);

        if ("EXECUTED".equals(approval.getStatus())) {
            return ExecutionDecision.replay(readStoredResult(approval.getExecutionResult()));
        }

        LocalDateTime now = LocalDateTime.now(clock);
        if ("EXECUTING".equals(approval.getStatus())) {
            if (approval.getExecutionLeaseUntil() == null
                    || !approval.getExecutionLeaseUntil().isBefore(now)) {
                return ExecutionDecision.inProgress();
            }
            return recoverOrReload(approval, suppliedDigest, now);
        }
        if (!"APPROVED".equals(approval.getStatus())) {
            throw denied("invalid_status");
        }

        String executionId = newExecutionId();
        int claimed = mapper.claimApproved(
                id,
                suppliedDigest,
                executionId,
                now,
                now.plus(leaseDuration)
        );
        if (claimed == 1) {
            return ExecutionDecision.claimed(
                    new ExecutionClaim(id, executionId, suppliedDigest)
            );
        }
        return decisionAfterLostRace(id, suppliedDigest, payload, caller);
    }

    public void complete(ExecutionClaim claim, Object result) {
        String sanitizedResult = serializeSanitized(result);
        if (mapper.completeExecution(
                claim.approvalId(),
                claim.executionId(),
                sanitizedResult,
                null
        ) != 1) {
            throw new IllegalStateException("HITL execution completion was not accepted");
        }
    }

    public void failExecution(ExecutionClaim claim, String reason) {
        String sanitizedReason = sanitizeReason(reason);
        if (mapper.failExecution(
                claim.approvalId(), claim.executionId(), sanitizedReason
        ) != 1) {
            throw new IllegalStateException("HITL execution failure was not accepted");
        }
    }

    private String sanitizeReason(String reason) {
        String sanitized = reason == null ? "business_rejected" : reason;
        sanitized = sanitized.replaceAll(
                "(?i)(key|token|secret|password|authorization|cookie)\\s*[=:]\\s*[^\\s,;]+",
                "[REDACTED]"
        );
        return sanitized.length() > 500 ? sanitized.substring(0, 500) : sanitized;
    }

    private ExecutionDecision recoverOrReload(
            HitlApprovalRecord approval,
            String digest,
            LocalDateTime now
    ) {
        String executionId = newExecutionId();
        int recovered = mapper.recoverExpiredLease(
                approval.getId(),
                digest,
                executionId,
                now,
                now.plus(leaseDuration)
        );
        if (recovered == 1) {
            return ExecutionDecision.claimed(
                    new ExecutionClaim(approval.getId(), executionId, digest)
            );
        }
        return ExecutionDecision.inProgress();
    }

    private ExecutionDecision decisionAfterLostRace(
            long approvalId,
            String digest,
            ApprovalPayload payload,
            RbacContext caller
    ) {
        HitlApprovalRecord current = mapper.selectApproval(approvalId);
        validate(current, digest, payload, caller);
        if ("EXECUTED".equals(current.getStatus())) {
            return ExecutionDecision.replay(readStoredResult(current.getExecutionResult()));
        }
        if ("EXECUTING".equals(current.getStatus())) {
            return ExecutionDecision.inProgress();
        }
        throw denied("claim_lost");
    }

    private void validate(
            HitlApprovalRecord approval,
            String suppliedDigest,
            ApprovalPayload payload,
            RbacContext caller
    ) {
        if (approval == null) {
            throw denied("approval_not_found");
        }
        if (isBlank(approval.getCheckpointId())) {
            throw denied("unbound_approval");
        }
        if (approval.getExpireAt() == null
                || approval.getExpireAt().isBefore(LocalDateTime.now(clock))) {
            throw denied("expired_approval");
        }
        if (caller == null || caller.getUserId() == null || caller.getRole() == null) {
            throw denied("identity_missing");
        }
        if (payload == null
                || !Objects.equals(approval.getActionType(), payload.toolName())
                || !Integer.valueOf(payload.payloadVersion()).equals(approval.getPayloadVersion())
                || !jsonEquals(approval.getActionPayload(), signer.canonicalJson(payload))) {
            throw denied("payload_mismatch");
        }
        if (!signer.matches(payload, approval.getPayloadDigest())
                || !signer.matches(payload, suppliedDigest)) {
            throw denied("digest_mismatch");
        }
        if (!String.valueOf(approval.getRequestedUserId()).equals(payload.requestedUserId())
                || !Objects.equals(approval.getRequestedRole(), payload.requestedRole())
                || !optionalId(approval.getMerchantId()).equals(payload.merchantId())
                || !String.valueOf(caller.getUserId()).equals(payload.requestedUserId())
                || !caller.getRole().equals(payload.requestedRole())
                || !optionalId(caller.getMerchantId()).equals(payload.merchantId())) {
            throw denied("identity_mismatch");
        }
    }

    private boolean jsonEquals(String stored, String expected) {
        try {
            return objectMapper.readTree(stored).equals(objectMapper.readTree(expected));
        } catch (JsonProcessingException | IllegalArgumentException error) {
            return false;
        }
    }

    private Object readStoredResult(String stored) {
        if (isBlank(stored)) {
            throw denied("replay_result_missing");
        }
        try {
            return objectMapper.readValue(stored, Object.class);
        } catch (JsonProcessingException error) {
            throw denied("replay_result_invalid");
        }
    }

    private String serializeSanitized(Object result) {
        try {
            JsonNode sanitized = sanitize(objectMapper.valueToTree(result));
            return objectMapper.writeValueAsString(sanitized);
        } catch (JsonProcessingException | IllegalArgumentException error) {
            throw new IllegalStateException("failed to serialize HITL execution result");
        }
    }

    private JsonNode sanitize(JsonNode node) {
        if (node instanceof ObjectNode object) {
            Iterator<String> fieldNames = object.fieldNames();
            while (fieldNames.hasNext()) {
                String fieldName = fieldNames.next();
                String normalized = fieldName.toLowerCase(Locale.ROOT);
                if (SENSITIVE_KEY_FRAGMENTS.stream().anyMatch(normalized::contains)) {
                    fieldNames.remove();
                } else {
                    sanitize(object.get(fieldName));
                }
            }
        } else if (node instanceof ArrayNode array) {
            array.forEach(this::sanitize);
        }
        return node;
    }

    private long parseApprovalId(String approvalId) {
        try {
            long parsed = Long.parseLong(approvalId);
            if (parsed <= 0) {
                throw denied("approval_id_invalid");
            }
            return parsed;
        } catch (NumberFormatException error) {
            throw denied("approval_id_invalid");
        }
    }

    private String newExecutionId() {
        return UUID.randomUUID().toString().replace("-", "");
    }

    private String optionalId(Long value) {
        return value == null ? "" : String.valueOf(value);
    }

    private boolean isBlank(String value) {
        return value == null || value.isBlank();
    }

    private ApprovalExecutionDeniedException denied(String reason) {
        return new ApprovalExecutionDeniedException(reason);
    }

    public enum ExecutionStatus {
        CLAIMED,
        IN_PROGRESS,
        REPLAY
    }

    public record ExecutionClaim(long approvalId, String executionId, String digest) {
    }

    public record ExecutionDecision(
            ExecutionStatus status,
            ExecutionClaim claim,
            Object result,
            String reason
    ) {
        static ExecutionDecision claimed(ExecutionClaim claim) {
            return new ExecutionDecision(ExecutionStatus.CLAIMED, claim, null, null);
        }

        static ExecutionDecision inProgress() {
            return new ExecutionDecision(
                    ExecutionStatus.IN_PROGRESS,
                    null,
                    null,
                    "approval_execution_in_progress"
            );
        }

        static ExecutionDecision replay(Object result) {
            return new ExecutionDecision(ExecutionStatus.REPLAY, null, result, null);
        }

        public boolean isClaimed() {
            return status == ExecutionStatus.CLAIMED;
        }

        public boolean isReplay() {
            return status == ExecutionStatus.REPLAY;
        }
    }

    public static class ApprovalExecutionDeniedException extends RuntimeException {
        private final String reason;

        ApprovalExecutionDeniedException(String reason) {
            super("HITL approval denied: " + reason);
            this.reason = reason;
        }

        public String reason() {
            return reason;
        }
    }
}
