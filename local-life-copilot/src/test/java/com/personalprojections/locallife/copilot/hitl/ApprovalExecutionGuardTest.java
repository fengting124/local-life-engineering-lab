package com.personalprojections.locallife.copilot.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.junit.jupiter.api.extension.ExtendWith;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ApprovalExecutionGuardTest {

    private static final String SECRET = "test-only-hitl-key";
    private static final Instant NOW = Instant.parse("2026-08-03T08:00:00Z");

    @Mock
    private HitlApprovalMapper mapper;

    private ObjectMapper objectMapper;
    private ApprovalPayloadSigner signer;
    private ApprovalExecutionGuard guard;

    @BeforeEach
    void setUp() {
        objectMapper = new ObjectMapper();
        signer = new ApprovalPayloadSigner(objectMapper, SECRET);
        guard = new ApprovalExecutionGuard(
                mapper,
                signer,
                objectMapper,
                Clock.fixed(NOW, ZoneOffset.UTC),
                Duration.ofMinutes(2)
        );
    }

    @Test
    void exactApprovedPayloadClaimsOnce() {
        ApprovalPayload payload = payload();
        when(mapper.selectApproval(7001L)).thenReturn(record(payload, "APPROVED"));
        when(mapper.claimApproved(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);

        ApprovalExecutionGuard.ExecutionDecision decision = guard.claim(
                "7001",
                signer.sign(payload),
                payload,
                caller()
        );

        assertThat(decision.status())
                .isEqualTo(ApprovalExecutionGuard.ExecutionStatus.CLAIMED);
        assertThat(decision.claim()).isNotNull();
        verify(mapper).claimApproved(
                anyLong(),
                anyString(),
                anyString(),
                any(),
                any()
        );
    }

    @ParameterizedTest(name = "{0}")
    @MethodSource("recordMutations")
    void changedApprovalFieldsAreDeniedBeforeCas(
            String label,
            UnaryOperator<HitlApprovalRecord> mutation
    ) {
        ApprovalPayload payload = payload();
        when(mapper.selectApproval(7001L))
                .thenReturn(mutation.apply(record(payload, "APPROVED")));

        assertThatThrownBy(() -> guard.claim(
                "7001", signer.sign(payload), payload, caller()
        )).isInstanceOf(ApprovalExecutionGuard.ApprovalExecutionDeniedException.class);

        verify(mapper, never()).claimApproved(
                anyLong(), anyString(), anyString(), any(), any()
        );
    }

    static Stream<Arguments> recordMutations() {
        return Stream.of(
                Arguments.of("tool", mutate(b -> b.actionType("issue_compensation_coupon"))),
                Arguments.of("order", mutate(b -> b.actionPayload("{}"))),
                Arguments.of("amount", mutate(b -> b.actionPayload("{\"amount_minor\":2001}"))),
                Arguments.of("target user", mutate(b -> b.actionPayload("{\"target_user_id\":\"9\"}"))),
                Arguments.of("reason", mutate(b -> b.actionPayload("{\"reason\":\"changed\"}"))),
                Arguments.of("role", mutate(b -> b.requestedRole("merchant"))),
                Arguments.of("user", mutate(b -> b.requestedUserId(1002L))),
                Arguments.of("merchant", mutate(b -> b.merchantId(43L))),
                Arguments.of("digest", mutate(b -> b.payloadDigest("b".repeat(64)))),
                Arguments.of("expiry", mutate(b -> b.expireAt(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC)))),
                Arguments.of("checkpoint", mutate(b -> b.checkpointId(null)))
        );
    }

    private static UnaryOperator<HitlApprovalRecord> mutate(
            UnaryOperator<HitlApprovalRecord.HitlApprovalRecordBuilder> mutation
    ) {
        return record -> mutation.apply(record.toBuilder()).build();
    }

    @Test
    void suppliedDigestAndCallerMustMatch() {
        ApprovalPayload payload = payload();
        when(mapper.selectApproval(7001L)).thenReturn(record(payload, "APPROVED"));

        assertThatThrownBy(() -> guard.claim(
                "7001", "b".repeat(64), payload, caller()
        )).isInstanceOf(ApprovalExecutionGuard.ApprovalExecutionDeniedException.class);
        assertThatThrownBy(() -> guard.claim(
                "7001",
                signer.sign(payload),
                payload,
                RbacContext.builder().userId(1002L).role("admin").merchantId(42L).build()
        )).isInstanceOf(ApprovalExecutionGuard.ApprovalExecutionDeniedException.class);
    }

    @Test
    void liveLeaseIsInProgressAndExpiredLeaseCanBeRecovered() {
        ApprovalPayload payload = payload();
        HitlApprovalRecord live = record(payload, "EXECUTING").toBuilder()
                .executionLeaseUntil(LocalDateTime.ofInstant(NOW.plusSeconds(30), ZoneOffset.UTC))
                .build();
        when(mapper.selectApproval(7001L)).thenReturn(live);

        assertThat(guard.claim("7001", signer.sign(payload), payload, caller()).status())
                .isEqualTo(ApprovalExecutionGuard.ExecutionStatus.IN_PROGRESS);

        HitlApprovalRecord expired = live.toBuilder()
                .executionLeaseUntil(LocalDateTime.ofInstant(NOW.minusSeconds(1), ZoneOffset.UTC))
                .build();
        when(mapper.selectApproval(7001L)).thenReturn(expired);
        when(mapper.recoverExpiredLease(anyLong(), anyString(), anyString(), any(), any()))
                .thenReturn(1);

        assertThat(guard.claim("7001", signer.sign(payload), payload, caller()).status())
                .isEqualTo(ApprovalExecutionGuard.ExecutionStatus.CLAIMED);
    }

    @Test
    void executedApprovalReplaysStoredResultAndCompletionUsesClaimId() {
        ApprovalPayload payload = payload();
        HitlApprovalRecord executed = record(payload, "EXECUTED").toBuilder()
                .executionResult("{\"status\":\"SUCCESS\"}")
                .build();
        when(mapper.selectApproval(7001L)).thenReturn(executed);

        ApprovalExecutionGuard.ExecutionDecision replay = guard.claim(
                "7001", signer.sign(payload), payload, caller()
        );

        assertThat(replay.status()).isEqualTo(ApprovalExecutionGuard.ExecutionStatus.REPLAY);
        assertThat(replay.result()).isEqualTo(Map.of("status", "SUCCESS"));
        verify(mapper, never()).claimApproved(anyLong(), anyString(), anyString(), any(), any());

        ApprovalExecutionGuard.ExecutionClaim claim =
                new ApprovalExecutionGuard.ExecutionClaim(7001L, "execution-1", signer.sign(payload));
        when(mapper.completeExecution(7001L, "execution-1", "{\"status\":\"SUCCESS\"}", null))
                .thenReturn(1);
        guard.complete(claim, Map.of("status", "SUCCESS", "internal_key", "secret"));

        verify(mapper).completeExecution(
                7001L,
                "execution-1",
                "{\"status\":\"SUCCESS\"}",
                null
        );
    }

    private ApprovalPayload payload() {
        return new ApprovalPayload(
                1,
                "execute_refund",
                "202606100003",
                2000,
                "",
                "42",
                "1001",
                "admin",
                "订单状态满足退款前置条件，等待人工审批"
        );
    }

    private HitlApprovalRecord record(ApprovalPayload payload, String status) {
        return HitlApprovalRecord.builder()
                .id(7001L)
                .threadId("thread-1")
                .checkpointId("checkpoint-1")
                .actionType(payload.toolName())
                .actionPayload(signer.canonicalJson(payload))
                .payloadVersion(payload.payloadVersion())
                .payloadDigest(signer.sign(payload))
                .merchantId(42L)
                .requestedUserId(1001L)
                .requestedRole("admin")
                .status(status)
                .expireAt(LocalDateTime.ofInstant(NOW.plusSeconds(3600), ZoneOffset.UTC))
                .build();
    }

    private RbacContext caller() {
        return RbacContext.builder()
                .userId(1001L)
                .role("admin")
                .merchantId(42L)
                .build();
    }
}
