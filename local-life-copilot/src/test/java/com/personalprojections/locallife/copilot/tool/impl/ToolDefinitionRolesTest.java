package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class ToolDefinitionRolesTest {

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    void csReadToolsOnlyExposeQueryOrder() {
        assertThat(new QueryOrderTool(null, objectMapper).getDefinition().getXAllowedRoles())
                .containsExactly("merchant", "cs", "admin");
        assertThat(new QueryPaymentTool(null, objectMapper).getDefinition().getXAllowedRoles())
                .containsExactly("admin");
        assertThat(new QueryCouponIssueLogTool(null, objectMapper).getDefinition().getXAllowedRoles())
                .containsExactly("admin");
        assertThat(new QueryMqDeadLetterTool(null, objectMapper).getDefinition().getXAllowedRoles())
                .containsExactly("admin");
    }

    @Test
    void highRiskToolsRequireHitlAndAllowCsAfterApproval() {
        var refund = new ExecuteRefundTool(objectMapper, null).getDefinition();
        var coupon = new IssueCompensationCouponTool(objectMapper, null).getDefinition();

        assertThat(refund.isXRequiresHitl()).isTrue();
        assertThat(coupon.isXRequiresHitl()).isTrue();
        assertThat(refund.getXAllowedRoles()).isEqualTo(List.of("cs", "admin"));
        assertThat(coupon.getXAllowedRoles()).isEqualTo(List.of("cs", "admin"));
    }
}
