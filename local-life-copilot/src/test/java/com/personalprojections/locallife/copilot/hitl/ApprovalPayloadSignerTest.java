package com.personalprojections.locallife.copilot.hitl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.function.UnaryOperator;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ApprovalPayloadSignerTest {

    private static final String TEST_SECRET = "test-only-hitl-key";
    private static final String EXPECTED_CANONICAL_JSON = "{\"payload_version\":1,\"tool_name\":\"execute_refund\",\"order_id\":\"202606100003\",\"amount_minor\":2000,\"target_user_id\":\"\",\"merchant_id\":\"42\",\"requested_user_id\":\"1001\",\"requested_role\":\"admin\",\"reason\":\"订单状态满足退款前置条件，等待人工审批\"}";
    private static final String EXPECTED_HMAC = "e951df4e681338c555d54c2acf5f46a058dcf2be1c6beaca8c92dab32028d81a";
    private static final String EXPECTED_COMPENSATION_JSON = "{\"payload_version\":2,\"tool_name\":\"issue_compensation_coupon\",\"order_id\":\"202606100003\",\"amount_minor\":2000,\"target_user_id\":\"9001\",\"shop_id\":\"101\",\"merchant_id\":\"42\",\"coupon_template_id\":\"7001\",\"coupon_discount_type\":\"CASH\",\"coupon_min_order_amount\":5000,\"coupon_valid_days\":30,\"coupon_terms_digest\":\"0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef\",\"requested_user_id\":\"1001\",\"requested_role\":\"admin\",\"reason\":\"订单异常，补发20元优惠券\"}";
    private static final String EXPECTED_COMPENSATION_HMAC = "1da8c4f042c4908ab68162d6182f8911a580dbe9e83cf6c5f6a0e2326a6b9be7";

    private ApprovalPayloadSigner signer;

    @BeforeEach
    void setUp() {
        signer = new ApprovalPayloadSigner(new ObjectMapper(), TEST_SECRET);
    }

    @Test
    void canonicalPayloadAndHmacMatchPythonContractVector() {
        ApprovalPayload payload = refundPayload();

        assertThat(signer.canonicalJson(payload)).isEqualTo(EXPECTED_CANONICAL_JSON);
        assertThat(signer.sign(payload)).isEqualTo(EXPECTED_HMAC);
        assertThat(signer.matches(payload, EXPECTED_HMAC)).isTrue();
    }

    @Test
    void compensationV2MatchesPythonContractVector() {
        ApprovalPayload payload = compensationPayload();

        assertThat(signer.canonicalJson(payload)).isEqualTo(EXPECTED_COMPENSATION_JSON);
        assertThat(signer.sign(payload)).isEqualTo(EXPECTED_COMPENSATION_HMAC);
        assertThat(signer.matches(payload, EXPECTED_COMPENSATION_HMAC)).isTrue();
    }

    @Test
    void payloadNormalizesStringEdgesBeforeSigning() {
        ApprovalPayload payload = new ApprovalPayload(
                1,
                " execute_refund ",
                " 202606100003 ",
                2000,
                " ",
                " 42 ",
                " 1001 ",
                " admin ",
                " 订单状态满足退款前置条件，等待人工审批 "
        );

        assertThat(signer.canonicalJson(payload)).isEqualTo(EXPECTED_CANONICAL_JSON);
    }

    @ParameterizedTest
    @MethodSource("signedFieldMutations")
    void digestRejectsEverySignedFieldChange(UnaryOperator<ApprovalPayload> mutation) {
        assertThat(signer.matches(mutation.apply(refundPayload()), EXPECTED_HMAC)).isFalse();
    }

    static Stream<Arguments> signedFieldMutations() {
        return Stream.of(
                Arguments.of((UnaryOperator<ApprovalPayload>) p -> copy(p, null, "202606100004", null, null, null, null, null, null)),
                Arguments.of((UnaryOperator<ApprovalPayload>) p -> copy(p, null, null, 2001, null, null, null, null, null)),
                Arguments.of((UnaryOperator<ApprovalPayload>) p -> copy(p, null, null, null, "9001", null, null, null, null)),
                Arguments.of((UnaryOperator<ApprovalPayload>) p -> copy(p, null, null, null, null, "43", null, null, null)),
                Arguments.of((UnaryOperator<ApprovalPayload>) p -> copy(p, null, null, null, null, null, "1002", null, null)),
                Arguments.of((UnaryOperator<ApprovalPayload>) p -> copy(p, null, null, null, null, null, null, "cs", null)),
                Arguments.of((UnaryOperator<ApprovalPayload>) p -> copy(p, null, null, null, null, null, null, null, "changed reason"))
        );
    }

    @Test
    void legacyV1CompensationFailsClosed() {
        assertThatThrownBy(() -> copy(
                refundPayload(), "issue_compensation_coupon", null, null,
                "9001", null, null, null, null
        )).isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadVersion");
    }

    @Test
    void payloadRejectsUnsupportedVersionToolAndInvalidAmount() {
        assertThatThrownBy(() -> copy(refundPayload(), null, null, 0, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("amountMinor");
        assertThatThrownBy(() -> new ApprovalPayload(
                2, "execute_refund", "202606100003", 2000,
                "", "42", "1001", "admin", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("payloadVersion");
        assertThatThrownBy(() -> new ApprovalPayload(
                1, "unknown_tool", "202606100003", 2000,
                "", "42", "1001", "admin", "reason"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("toolName");
    }

    @Test
    void compensationPayloadRequiresTargetUser() {
        ApprovalPayload valid = compensationPayload();
        assertThatThrownBy(() -> new ApprovalPayload(
                valid.payloadVersion(), valid.toolName(), valid.orderId(), valid.amountMinor(),
                "", valid.shopId(), valid.merchantId(), valid.couponTemplateId(),
                valid.couponDiscountType(), valid.couponMinOrderAmount(), valid.couponValidDays(),
                valid.couponTermsDigest(), valid.requestedUserId(), valid.requestedRole(), valid.reason()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("targetUserId");
    }

    @Test
    void signerRejectsBlankSecretAndMalformedDigest() {
        assertThatThrownBy(() -> new ApprovalPayloadSigner(new ObjectMapper(), " "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("secret");
        assertThat(signer.matches(refundPayload(), "not-a-digest")).isFalse();
    }

    private static ApprovalPayload refundPayload() {
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

    private static ApprovalPayload compensationPayload() {
        return new ApprovalPayload(
                2, "issue_compensation_coupon", "202606100003", 2000,
                "9001", "101", "42", "7001", "CASH", 5000, 30,
                "0123456789abcdef0123456789abcdef0123456789abcdef0123456789abcdef",
                "1001", "admin", "订单异常，补发20元优惠券"
        );
    }

    private static ApprovalPayload copy(
            ApprovalPayload source,
            String toolName,
            String orderId,
            Integer amountMinor,
            String targetUserId,
            String merchantId,
            String requestedUserId,
            String requestedRole,
            String reason
    ) {
        return new ApprovalPayload(
                source.payloadVersion(),
                toolName != null ? toolName : source.toolName(),
                orderId != null ? orderId : source.orderId(),
                amountMinor != null ? amountMinor : source.amountMinor(),
                targetUserId != null ? targetUserId : source.targetUserId(),
                merchantId != null ? merchantId : source.merchantId(),
                requestedUserId != null ? requestedUserId : source.requestedUserId(),
                requestedRole != null ? requestedRole : source.requestedRole(),
                reason != null ? reason : source.reason()
        );
    }
}
