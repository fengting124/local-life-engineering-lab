package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.domain.dto.ShopMetricsSnapshot;
import com.personalprojections.locallife.copilot.domain.mapper.CopilotOrderMapper;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolParameterException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class ShopMetricsQueryToolTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 8, 11);

    private final CopilotOrderMapper orderMapper = mock(CopilotOrderMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final ShopMetricsQueryTool tool = new ShopMetricsQueryTool(orderMapper, objectMapper);

    @AfterEach
    void tearDown() {
        RbacContext.clear();
    }

    @Test
    void singleDateRemainsBackwardCompatibleAndUsesInjectedToday() throws Exception {
        RbacContext.set(RbacContext.builder()
                .userId(1L).role("merchant").merchantId(42L).build());
        when(orderMapper.selectShopMetrics(42L, "2026-08-11", "2026-08-11", null))
                .thenReturn(new ShopMetricsSnapshot(2L, 3000L, 0L, 0L, 0L));

        Object result = tool.execute(args("{\"date\":\"today\"}"), TODAY);

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result;
        assertThat(payload)
                .containsEntry("date", "2026-08-11")
                .containsEntry("start_date", "2026-08-11")
                .containsEntry("end_date", "2026-08-11")
                .containsEntry("gmv", 3000L);
        verify(orderMapper).selectShopMetrics(42L, "2026-08-11", "2026-08-11", null);
    }

    @Test
    void completeRangeUsesOneAggregateQuery() throws Exception {
        RbacContext.set(RbacContext.builder()
                .userId(1L).role("merchant").merchantId(42L).build());
        when(orderMapper.selectShopMetrics(42L, "2026-08-01", "2026-08-11", null))
                .thenReturn(new ShopMetricsSnapshot(7L, 9900L, 1L, 2L, 500L));

        Object result = tool.execute(args(
                "{\"start_date\":\"2026-08-01\",\"end_date\":\"2026-08-11\"}"
        ), TODAY);

        @SuppressWarnings("unchecked")
        Map<String, Object> payload = (Map<String, Object>) result;
        assertThat(payload)
                .doesNotContainKey("date")
                .containsEntry("start_date", "2026-08-01")
                .containsEntry("end_date", "2026-08-11")
                .containsEntry("order_count", 7L);
        verify(orderMapper).selectShopMetrics(42L, "2026-08-01", "2026-08-11", null);
    }

    @Test
    void incompleteOrReversedRangeFailsBeforeDatabaseAccess() throws Exception {
        RbacContext.set(RbacContext.builder()
                .userId(1L).role("merchant").merchantId(42L).build());

        assertThatThrownBy(() -> tool.execute(
                args("{\"start_date\":\"2026-08-01\"}"), TODAY
        )).isInstanceOf(ToolParameterException.class)
                .hasMessageContaining("start_date 和 end_date");
        assertThatThrownBy(() -> tool.execute(args(
                "{\"start_date\":\"2026-08-12\",\"end_date\":\"2026-08-11\"}"
        ), TODAY)).isInstanceOf(ToolParameterException.class)
                .hasMessageContaining("不能晚于");
        assertThatThrownBy(() -> tool.execute(args(
                "{\"date\":\"today\",\"start_date\":\"2026-08-01\",\"end_date\":\"2026-08-11\"}"
        ), TODAY)).isInstanceOf(ToolParameterException.class)
                .hasMessageContaining("不能同时提供");

        verify(orderMapper, never()).selectShopMetrics(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void malformedDateFailsBeforeDatabaseAccess() throws Exception {
        RbacContext.set(RbacContext.builder()
                .userId(1L).role("merchant").merchantId(42L).build());

        assertThatThrownBy(() -> tool.execute(args("{\"date\":\"2026-8-1\"}"), TODAY))
                .isInstanceOf(ToolParameterException.class)
                .hasMessageContaining("格式错误");
        assertThatThrownBy(() -> tool.execute(args("{\"date\":\"2026-02-30\"}"), TODAY))
                .isInstanceOf(ToolParameterException.class)
                .hasMessageContaining("格式错误");

        verify(orderMapper, never()).selectShopMetrics(
                org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.any());
    }

    @Test
    void schemaOffersSingleDateOrCompleteRange() {
        JsonNode schema = tool.getDefinition().getInputSchema();

        assertThat(schema.path("properties").has("date")).isTrue();
        assertThat(schema.path("properties").has("start_date")).isTrue();
        assertThat(schema.path("properties").has("end_date")).isTrue();
        assertThat(schema.path("oneOf").size()).isEqualTo(2);
        assertThat(schema.path("oneOf").path(0).path("not").path("anyOf").size())
                .isEqualTo(2);
        assertThat(schema.path("oneOf").path(1).path("not").path("required").path(0).asText())
                .isEqualTo("date");
    }

    private JsonNode args(String json) throws Exception {
        return objectMapper.readTree(json);
    }
}
