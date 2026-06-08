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
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link IssueCompensationCouponTool} 单元测试。
 *
 * <h2>核心动机：compensation_amount 空指针回归测试</h2>
 * <p>修复前的实现是 {@code arguments.get("compensation_amount").asInt(0)}——
 * {@link JsonNode#get(String)} 在键缺失时返回 Java {@code null}（不是 {@code MissingNode}），
 * 对 {@code null} 调用 {@code .asInt(0)} 会直接抛出 {@link NullPointerException}，
 * {@code asInt(0)} 名义上的"缺省值"机制形同虚设——Agent 拿到的不是清晰的
 * {@code parameter_error}（可修正重试），而是一个无法理解、无法恢复的 500。
 * 本类第 1 节的三条用例就是把"键缺失 / JSON 字面量 null / 合法但 <= 0"
 * 三种边界依次摆出来，钉住修复后的行为：永远是结构化的 {@link ToolParameterException}，
 * 绝不是 {@link NullPointerException}。
 *
 * <h2>为什么不在这里测 RBAC / HITL</h2>
 * <p>与 {@code ExecuteRefundTool} 同理：{@code execute()} 不直接读取 RbacContext
 * （角色放行 {@code xAllowedRoles=[cs,admin]} 在 {@code McpController} 层完成），
 * HITL 拦截是 Python Agent Service 看到 {@code xRequiresHitl=true} 后的编排行为。
 */
class IssueCompensationCouponToolTest {

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalLifeInternalClient internalClient = mock(LocalLifeInternalClient.class);
    private final IssueCompensationCouponTool tool = new IssueCompensationCouponTool(objectMapper, internalClient);

    private JsonNode args(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    private static final String VALID_USER_ID = "20001";
    private static final String VALID_ORDER_ID = "1234567890123456789";
    private static final String VALID_REASON = "库存不足，发放等额补偿券";
    private static final String VALID_APPROVAL_ID = "APPROVAL_001";

    private String validArgsWithAmount(Object amountLiteral) {
        return "{\"user_id\":\"" + VALID_USER_ID + "\",\"order_id\":\"" + VALID_ORDER_ID + "\","
                + "\"compensation_amount\":" + amountLiteral + ","
                + "\"reason\":\"" + VALID_REASON + "\",\"approval_id\":\"" + VALID_APPROVAL_ID + "\"}";
    }

    // =====================================================================
    // 1. compensation_amount —— NPE 修复点的回归测试（原先这里会抛 NullPointerException）
    // =====================================================================

    @Test
    void execute_missingCompensationAmount_throwsParameterException_insteadOfNullPointerException() throws Exception {
        String argsWithoutAmount = "{\"user_id\":\"" + VALID_USER_ID + "\",\"order_id\":\"" + VALID_ORDER_ID + "\","
                + "\"reason\":\"" + VALID_REASON + "\",\"approval_id\":\"" + VALID_APPROVAL_ID + "\"}";

        assertThatThrownBy(() -> tool.execute(args(argsWithoutAmount)))
                .as("修复前：arguments.get(\"compensation_amount\") 返回 Java null（键缺失，不是 MissingNode），"
                        + "对 null 调用 .asInt(0) 直接 NPE，asInt(0) 的默认值机制完全没有生效的机会")
                .isInstanceOf(ToolParameterException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessage("compensation_amount 不能为空")
                .extracting(ex -> ((ToolParameterException) ex).getHint())
                .isNull();

        verifyNoInteractions(internalClient);
    }

    @Test
    void execute_explicitJsonNullCompensationAmount_alsoThrowsParameterException_notNullPointerException() throws Exception {
        // node.isNull()：JSON 显式写了字面量 null（区别于"键缺失"的 node == null），
        // 必须落进同一条 null 防护，而不是被 asInt(0) 当成 0 悄悄放过、绕开下面的业务校验
        assertThatThrownBy(() -> tool.execute(args(validArgsWithAmount("null"))))
                .isInstanceOf(ToolParameterException.class)
                .isNotInstanceOf(NullPointerException.class)
                .hasMessage("compensation_amount 不能为空");

        verifyNoInteractions(internalClient);
    }

    @ParameterizedTest(name = "[{index}] compensation_amount={0} 时报业务校验错误（说明 NPE 防护已经放行到了这一层）")
    @ValueSource(ints = {0, -1, -2000})
    void execute_nonPositiveCompensationAmount_throwsBusinessValidationException_withOriginalActionableHint(int badAmount) throws Exception {
        assertThatThrownBy(() -> tool.execute(args(validArgsWithAmount(String.valueOf(badAmount)))))
                .as("这条校验在 execute() 里早已存在（修复只新增了它前面的 null 防护），"
                        + "保留原本更具体的提示文案——\"不能为空\"那条通用 hint=null 不该覆盖它")
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("compensation_amount 必须大于 0")
                .extracting(ex -> ((ToolParameterException) ex).getHint())
                .isEqualTo("单位为分，如 2000 表示 20 元");

        verifyNoInteractions(internalClient);
    }

    // =====================================================================
    // 2. 其余必填字符串参数 —— extractRequiredString 的统一护栏
    // =====================================================================

    @ParameterizedTest(name = "[{index}] 缺失 {0} 时报„{0} 不能为空“，且不触达 internalClient")
    @ValueSource(strings = {"user_id", "order_id", "reason", "approval_id"})
    void execute_missingRequiredStringParam_throwsParameterException_beforeCallingInternalClient(String missingKey) throws Exception {
        Map<String, Object> complete = Map.of(
                "user_id", VALID_USER_ID,
                "order_id", VALID_ORDER_ID,
                "compensation_amount", 2000,
                "reason", VALID_REASON,
                "approval_id", VALID_APPROVAL_ID);
        java.util.Map<String, Object> partial = new java.util.HashMap<>(complete);
        partial.remove(missingKey);

        assertThatThrownBy(() -> tool.execute(objectMapper.valueToTree(partial)))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage(missingKey + " 不能为空");

        verifyNoInteractions(internalClient);
    }

    // =====================================================================
    // 3. 成功路径：透传 internalClient.compensateCoupon(...) 的返回值
    // =====================================================================

    @Test
    void execute_validRequest_delegatesToInternalClientWithExtractedArgs_andReturnsRawResultUnwrapped() throws Exception {
        Map<String, Object> compensateResult = Map.of(
                "couponId", "COUPON_5566",
                "status", "ISSUED");
        when(internalClient.compensateCoupon(VALID_ORDER_ID, VALID_USER_ID, 2000, VALID_APPROVAL_ID, VALID_REASON))
                .thenReturn(compensateResult);

        Object result = tool.execute(args(validArgsWithAmount(2000)));

        assertThat(result)
                .as("execute() 直接 return internalClient 的结果——同一个 Map 实例，不做二次包装")
                .isSameAs(compensateResult);
        verify(internalClient).compensateCoupon(
                eq(VALID_ORDER_ID), eq(VALID_USER_ID), eq(2000), eq(VALID_APPROVAL_ID), eq(VALID_REASON));
    }

    @Test
    void execute_amountExactlyAtLowerBound_oneCent_passesValidationAndDelegates() throws Exception {
        // compensationAmount <= 0 才拒绝——1 分钱属于"大于 0"，应该放行（边界值探测，不是业务上的合理输入）
        when(internalClient.compensateCoupon(org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyInt(), org.mockito.ArgumentMatchers.anyString(), org.mockito.ArgumentMatchers.anyString()))
                .thenReturn(Map.of("status", "ISSUED"));

        tool.execute(args(validArgsWithAmount(1)));

        verify(internalClient).compensateCoupon(eq(VALID_ORDER_ID), eq(VALID_USER_ID), eq(1), eq(VALID_APPROVAL_ID), eq(VALID_REASON));
    }
}
