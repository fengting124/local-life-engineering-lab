package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.client.LocalLifeInternalClient;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolParameterException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link ExecuteRefundTool} 单元测试：覆盖 L4 高风险/HITL 工具的参数提取链路
 * （{@code extractString}/{@code extractInt} 两个私有 helper 的全部分支），
 * 以及成功路径下「直接透传 {@link LocalLifeInternalClient} 返回的 Map」这一约定
 * ——这正是 {@code McpControllerTest} 里 {@code buildContentResult} 的
 * 「JSON 序列化包装」分支在真实工具上的唯一输入来源，两层测试在这里精确衔接、不重复。
 *
 * <h2>为什么不在这里测 RBAC / HITL</h2>
 * <p>{@code execute()} 本身不读取 {@link com.personalprojections.locallife.copilot.rbac.RbacContext}——
 * 角色放行（{@code xAllowedRoles=[cs,admin]}）由 {@code McpController} 在调用前完成校验
 * （见 {@code McpControllerTest} 的 RBAC 用例），HITL 拦截则是 Python Agent Service
 * 看到 {@code xRequiresHitl=true} 后的编排行为，都不属于这个工具自身的职责范围。
 */
class ExecuteRefundToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalLifeInternalClient internalClient = mock(LocalLifeInternalClient.class);
    private final ExecuteRefundTool tool = new ExecuteRefundTool(objectMapper, internalClient);

    private JsonNode args(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    // =====================================================================
    // 1. extractString —— order_id / approval_id / reason 共用同一条护栏
    // =====================================================================

    @ParameterizedTest(name = "[{index}] 缺失 {0} 时短路并报„{0} 不能为空“，不触达 internalClient")
    @ValueSource(strings = {"order_id", "approval_id", "reason"})
    void execute_missingRequiredStringParam_throwsParameterException_beforeCallingInternalClient(String missingKey) throws Exception {
        // 一次构造一份"全部必填字段都在、唯独缺 missingKey"的合法参数，
        // 确保抛出的确实是 missingKey 自己的校验，不是被其它字段的校验抢先短路
        Map<String, Object> complete = Map.of(
                "order_id", "1234567890123456789",
                "amount", 2990,
                "approval_id", "APPROVAL_001",
                "reason", "券库存不足");
        java.util.Map<String, Object> partial = new java.util.HashMap<>(complete);
        partial.remove(missingKey);

        assertThatThrownBy(() -> tool.execute(objectMapper.valueToTree(partial)))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage(missingKey + " 不能为空")
                .extracting(ex -> ((ToolParameterException) ex).getHint())
                .as("extractString 的 hint 固定传 null（与 extractInt 不同，后者按字段定制提示）")
                .isNull();

        verifyNoInteractions(internalClient);
    }

    @Test
    void execute_blankReason_treatedAsAbsent_byIsBlankGuard() throws Exception {
        assertThatThrownBy(() -> tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":2990,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"   \"}")))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("reason 不能为空");

        verifyNoInteractions(internalClient);
    }

    // =====================================================================
    // 2. extractInt —— amount 的三种校验分支：缺失 / <= 0 / 合法
    // =====================================================================

    @Test
    void execute_missingAmount_throwsParameterException_withFieldSpecificHint() throws Exception {
        assertThatThrownBy(() -> tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}")))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("amount 不能为空")
                .extracting(ex -> ((ToolParameterException) ex).getHint())
                .as("extractInt 在『缺失』分支上比 extractString 多给了一句字段级提示")
                .isEqualTo("amount 为整数（分）");

        verifyNoInteractions(internalClient);
    }

    @Test
    void execute_explicitJsonNullAmount_alsoTreatedAsMissing_notAsZero() throws Exception {
        // node.isNull() 分支：JSON 里写的是字面量 null（不是缺省字段），必须和"缺失"走同一条报错，
        // 而不是被 asInt(0) 悄悄当成 0 放过去
        assertThatThrownBy(() -> tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":null,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}")))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("amount 不能为空");

        verifyNoInteractions(internalClient);
    }

    @ParameterizedTest(name = "[{index}] amount={0} 时报„amount 必须大于 0“且不带 hint")
    @ValueSource(ints = {0, -1, -2990})
    void execute_nonPositiveAmount_throwsParameterException_withNullHint(int badAmount) throws Exception {
        assertThatThrownBy(() -> tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":" + badAmount + ","
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}")))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("amount 必须大于 0")
                .extracting(ex -> ((ToolParameterException) ex).getHint())
                .isNull();

        verifyNoInteractions(internalClient);
    }

    // =====================================================================
    // 3. 成功路径：原样透传 internalClient.refund(...) 的返回值
    // =====================================================================

    @Test
    void execute_validRequest_delegatesToInternalClientWithExtractedArgs_andReturnsRawResultUnwrapped() throws Exception {
        Map<String, Object> refundResult = Map.of(
                "refund_status", "SUCCESS",
                "refund_id", "REFUND_998877");
        when(internalClient.refund("1234567890123456789", 2990, "APPROVAL_001", "券库存不足"))
                .thenReturn(refundResult);

        Object result = tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":2990,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"券库存不足\"}"));

        assertThat(result)
                .as("execute() 直接 return internalClient 的结果——同一个 Map 实例，不做二次包装或字段过滤")
                .isSameAs(refundResult);
        verify(internalClient).refund(eq("1234567890123456789"), eq(2990), eq("APPROVAL_001"), eq("券库存不足"));
        verify(internalClient, never()).compensateCoupon(
                org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.any(), org.mockito.ArgumentMatchers.any());
    }

    @Test
    void execute_trimsStringArguments_beforeDelegating() throws Exception {
        // extractString 里的 .trim()：Agent / 上游传参难免带前后空白，这里确认确实做了清洗
        when(internalClient.refund(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyInt(),
                org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("refund_status", "SUCCESS"));

        tool.execute(args(
                "{\"order_id\":\"  1234567890123456789  \",\"amount\":2990,"
                        + "\"approval_id\":\" APPROVAL_001 \",\"reason\":\" 协商一致 \"}"));

        verify(internalClient).refund(eq("1234567890123456789"), eq(2990), eq("APPROVAL_001"), eq("协商一致"));
    }
}
