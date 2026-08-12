package com.personalprojections.locallife.copilot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.client.LocalLifeInternalClient.CompensationCommand;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolBusinessException;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolParameterException;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.client.ExpectedCount.once;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withBadRequest;

class LocalLifeInternalClientTest {

    @Test
    void stockExhaustionIsADefiniteBusinessRejection() {
        Fixture fixture = fixture("COUPON_STOCK_EXHAUSTED", "优惠券已抢完");

        assertThatThrownBy(() -> fixture.client().compensateCoupon(command()))
                .isInstanceOf(ToolBusinessException.class)
                .hasMessage("优惠券已抢完");
        fixture.server().verify();
    }

    @Test
    void staleCompensationTermsAreADefiniteParameterRejection() {
        Fixture fixture = fixture("SYS_PARAM_INVALID", "补偿券条款已变化，需要重新审批");

        assertThatThrownBy(() -> fixture.client().compensateCoupon(command()))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("补偿券条款已变化，需要重新审批");
        fixture.server().verify();
    }

    private Fixture fixture(String code, String message) {
        RestClient.Builder builder = RestClient.builder();
        MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();
        LocalLifeInternalClient client = new LocalLifeInternalClient(
                builder.build(), new ObjectMapper()
        );
        ReflectionTestUtils.setField(client, "localLifeServerUrl", "http://server.test");
        ReflectionTestUtils.setField(client, "internalKey", "test-key");
        server.expect(once(), requestTo(
                        "http://server.test/internal/orders/202606100003/compensate-coupon"))
                .andRespond(withBadRequest()
                        .contentType(MediaType.APPLICATION_JSON)
                        .body("{\"code\":\"" + code + "\",\"message\":\"" + message + "\"}"));
        return new Fixture(client, server);
    }

    private CompensationCommand command() {
        return new CompensationCommand(
                "202606100003", "9001", 2000, "101", "42", "7001",
                "CASH", 5000, 30, "a".repeat(64), "approval-1", "test"
        );
    }

    private record Fixture(
            LocalLifeInternalClient client,
            MockRestServiceServer server
    ) {
    }
}
