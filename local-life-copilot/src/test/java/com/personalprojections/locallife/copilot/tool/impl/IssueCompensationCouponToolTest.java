package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.client.LocalLifeInternalClient;
import com.personalprojections.locallife.copilot.hitl.ApprovalExecutionGuard;
import com.personalprojections.locallife.copilot.hitl.ApprovalPayload;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolParameterException;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolBusinessException;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolPermissionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
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
 * <h2>HITL 执行边界</h2>
 * <p>角色粗粒度放行由 {@code McpController} 完成；本工具仍会将订单、补偿金额、
 * 目标用户和当前 {@link RbacContext} 交给 {@link ApprovalExecutionGuard} 做不可变
 * 载荷校验与原子消费，守卫放行前不会调用 Server。
 */
class IssueCompensationCouponToolTest {

    private static final String APPROVAL_DIGEST = "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalLifeInternalClient internalClient = mock(LocalLifeInternalClient.class);
    private final ApprovalExecutionGuard guard = mock(ApprovalExecutionGuard.class);
    private final ApprovalExecutionGuard.ExecutionClaim claim =
            new ApprovalExecutionGuard.ExecutionClaim(1001L, "execution-1", APPROVAL_DIGEST);
    private final IssueCompensationCouponTool tool =
            new IssueCompensationCouponTool(objectMapper, internalClient, guard);

    @BeforeEach
    void setUp() {
        RbacContext.set(RbacContext.builder()
                .userId(30001L)
                .role("cs")
                .merchantId(42L)
                .build());
        when(guard.claim(anyString(), anyString(), any(ApprovalPayload.class), any(RbacContext.class)))
                .thenReturn(new ApprovalExecutionGuard.ExecutionDecision(
                        ApprovalExecutionGuard.ExecutionStatus.CLAIMED,
                        claim,
                        null,
                        null
                ));
    }

    @AfterEach
    void tearDown() {
        RbacContext.clear();
    }

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
                + "\"reason\":\"" + VALID_REASON + "\",\"approval_id\":\"" + VALID_APPROVAL_ID + "\","
                + "\"approval_digest\":\"" + APPROVAL_DIGEST + "\"}";
    }

    // =====================================================================
    // 1. compensation_amount —— NPE 修复点的回归测试（原先这里会抛 NullPointerException）
    // =====================================================================

    @Test
    void execute_missingCompensationAmount_throwsParameterException_insteadOfNullPointerException() throws Exception {
        String argsWithoutAmount = "{\"user_id\":\"" + VALID_USER_ID + "\",\"order_id\":\"" + VALID_ORDER_ID + "\","
                + "\"reason\":\"" + VALID_REASON + "\",\"approval_id\":\"" + VALID_APPROVAL_ID + "\","
                + "\"approval_digest\":\"" + APPROVAL_DIGEST + "\"}";

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
                "approval_id", VALID_APPROVAL_ID,
                "approval_digest", APPROVAL_DIGEST);
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
        verify(guard).claim(
                eq(VALID_APPROVAL_ID),
                eq(APPROVAL_DIGEST),
                eq(new ApprovalPayload(
                        1,
                        "issue_compensation_coupon",
                        VALID_ORDER_ID,
                        2000,
                        VALID_USER_ID,
                        "42",
                        "30001",
                        "cs",
                        VALID_REASON
                )),
                any(RbacContext.class)
        );
        verify(guard).complete(claim, compensateResult);
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

    @Test
    void execute_missingApprovalDigest_failsBeforeGuardAndInternalClient() throws Exception {
        JsonNode arguments = objectMapper.readTree(
                "{\"user_id\":\"" + VALID_USER_ID + "\",\"order_id\":\"" + VALID_ORDER_ID + "\","
                        + "\"compensation_amount\":2000,\"reason\":\"" + VALID_REASON + "\","
                        + "\"approval_id\":\"" + VALID_APPROVAL_ID + "\"}"
        );

        assertThatThrownBy(() -> tool.execute(arguments))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("approval_digest 不能为空");

        verifyNoInteractions(internalClient);
        verify(guard, never()).claim(anyString(), anyString(), any(), any());
    }

    @Test
    void execute_deniedApprovalNeverCallsInternalClient() throws Exception {
        when(guard.claim(anyString(), anyString(), any(), any()))
                .thenThrow(mock(ApprovalExecutionGuard.ApprovalExecutionDeniedException.class));

        assertThatThrownBy(() -> tool.execute(args(validArgsWithAmount(2000))))
                .isInstanceOf(ToolPermissionException.class);

        verifyNoInteractions(internalClient);
    }

    @Test
    void execute_inProgressApprovalNeverCallsInternalClient() throws Exception {
        when(guard.claim(anyString(), anyString(), any(), any()))
                .thenReturn(new ApprovalExecutionGuard.ExecutionDecision(
                        ApprovalExecutionGuard.ExecutionStatus.IN_PROGRESS,
                        null,
                        null,
                        "approval_execution_in_progress"
                ));

        assertThatThrownBy(() -> tool.execute(args(validArgsWithAmount(2000))))
                .isInstanceOf(ToolBusinessException.class);

        verifyNoInteractions(internalClient);
    }

    @Test
    void execute_replayReturnsStoredResultWithoutCallingInternalClient() throws Exception {
        Map<String, Object> stored = Map.of("status", "ISSUED", "couponId", "COUPON_1");
        when(guard.claim(anyString(), anyString(), any(), any()))
                .thenReturn(new ApprovalExecutionGuard.ExecutionDecision(
                        ApprovalExecutionGuard.ExecutionStatus.REPLAY,
                        null,
                        stored,
                        null
                ));

        Object result = tool.execute(args(validArgsWithAmount(2000)));

        assertThat(result).isSameAs(stored);
        verifyNoInteractions(internalClient);
        verify(guard, never()).complete(any(), any());
    }

    @Test
    void execute_missingCallerIdentityFailsBeforeClaimAndInternalClient() throws Exception {
        RbacContext.clear();

        assertThatThrownBy(() -> tool.execute(args(validArgsWithAmount(2000))))
                .isInstanceOf(ToolPermissionException.class)
                .hasMessage("调用者身份缺失");

        verify(guard, never()).claim(anyString(), anyString(), any(), any());
        verifyNoInteractions(internalClient);
    }
}
