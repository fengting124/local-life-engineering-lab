package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.domain.dto.CompensationCouponResolution;
import com.personalprojections.locallife.copilot.domain.mapper.CompensationCouponMapper;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolNotFoundException;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolPermissionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ResolveCompensationCouponToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final CompensationCouponMapper mapper = mock(CompensationCouponMapper.class);
    private final ResolveCompensationCouponTool tool =
            new ResolveCompensationCouponTool(mapper, objectMapper);

    @AfterEach
    void clearContext() {
        RbacContext.clear();
    }

    @Test
    void definitionIsAdminOnlyAndReadOnly() {
        assertThat(tool.getDefinition().getXAllowedRoles()).containsExactly("admin");
        assertThat(tool.getDefinition().isXRequiresHitl()).isFalse();
        assertThat(tool.getDefinition().getInputSchema().path("required").toString())
                .contains("order_id", "amount_minor");
    }

    @Test
    void csCannotResolveInternalCompensationConfiguration() throws Exception {
        RbacContext.set(RbacContext.builder().userId(1L).role("cs").build());

        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"order_id":"1234567890123456789","amount_minor":2000}
                """)))
                .isInstanceOf(ToolPermissionException.class);
        verify(mapper, never()).resolve("1234567890123456789", 2000);
    }

    @Test
    void adminGetsOrderDerivedTargetAndCanonicalTermsDigest() throws Exception {
        RbacContext.set(RbacContext.builder().userId(1L).role("admin").build());
        when(mapper.resolve("1234567890123456789", 2000)).thenReturn(
                CompensationCouponResolution.builder()
                        .orderId(1001L)
                        .orderNo("1234567890123456789")
                        .targetUserId(5001L)
                        .shopId(2001L)
                        .merchantId(3001L)
                        .faceValueMinor(2000)
                        .couponTemplateId(1001L)
                        .discountType("CASH")
                        .discountValue(2000)
                        .minOrderAmount(0)
                        .validDays(30)
                        .templateStatus("ACTIVE")
                        .bindingEnabled(1)
                        .build());

        @SuppressWarnings("unchecked")
        Map<String, Object> result = (Map<String, Object>) tool.execute(objectMapper.readTree("""
                {"order_id":"1234567890123456789","amount_minor":2000}
                """));

        assertThat(result)
                .containsEntry("target_user_id", "5001")
                .containsEntry("shop_id", "2001")
                .containsEntry("merchant_id", "3001")
                .containsEntry("coupon_template_id", "1001")
                .containsEntry("coupon_discount_type", "CASH")
                .containsEntry("coupon_min_order_amount", 0)
                .containsEntry("coupon_valid_days", 30)
                .containsEntry("coupon_terms_digest",
                        "049b5d9612aadb285038e35642b0ab499e8a98dd8800906500415edf6d97f1c7");
    }

    @Test
    void missingOrInvalidConfigurationFailsClosed() throws Exception {
        RbacContext.set(RbacContext.builder().userId(1L).role("admin").build());
        when(mapper.resolve("1234567890123456789", 2000)).thenReturn(null);

        assertThatThrownBy(() -> tool.execute(objectMapper.readTree("""
                {"order_id":"1234567890123456789","amount_minor":2000}
                """)))
                .isInstanceOf(ToolNotFoundException.class);
    }
}
