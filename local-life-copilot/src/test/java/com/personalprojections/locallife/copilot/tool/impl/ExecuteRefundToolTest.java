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
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
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
 * <h2>HITL 执行边界</h2>
 * <p>{@code McpController} 仍负责角色粗粒度放行；本工具还会把业务参数和当前
 * {@link RbacContext} 组成不可变审批载荷，由 {@link ApprovalExecutionGuard} 在调用
 * Server 前完成签名、身份、状态和原子消费校验。
 */
class ExecuteRefundToolTest {

    private static final String APPROVAL_DIGEST = "a".repeat(64);

    private final ObjectMapper objectMapper = new ObjectMapper();
    private final LocalLifeInternalClient internalClient = mock(LocalLifeInternalClient.class);
    private final ApprovalExecutionGuard guard = mock(ApprovalExecutionGuard.class);
    private final ApprovalExecutionGuard.ExecutionClaim claim =
            new ApprovalExecutionGuard.ExecutionClaim(1001L, "execution-1", APPROVAL_DIGEST);
    private final ExecuteRefundTool tool = new ExecuteRefundTool(objectMapper, internalClient, guard);

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
        var parsed = (com.fasterxml.jackson.databind.node.ObjectNode) objectMapper.readTree(json);
        parsed.put("approval_digest", APPROVAL_DIGEST);
        return parsed;
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
                "approval_digest", APPROVAL_DIGEST,
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
        verify(guard).claim(
                eq("APPROVAL_001"),
                eq(APPROVAL_DIGEST),
                eq(new ApprovalPayload(
                        1,
                        "execute_refund",
                        "1234567890123456789",
                        2990,
                        "",
                        "42",
                        "30001",
                        "cs",
                        "券库存不足"
                )),
                any(RbacContext.class)
        );
        verify(guard).complete(claim, refundResult);
        verify(internalClient, never()).compensateCoupon(org.mockito.ArgumentMatchers.any());
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

    @Test
    void execute_missingApprovalDigest_failsBeforeGuardAndInternalClient() throws Exception {
        JsonNode arguments = objectMapper.readTree(
                "{\"order_id\":\"1234567890123456789\",\"amount\":2990,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}"
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

        assertThatThrownBy(() -> tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":2990,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}"
        ))).isInstanceOf(ToolPermissionException.class);

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

        assertThatThrownBy(() -> tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":2990,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}"
        ))).isInstanceOf(ToolBusinessException.class);

        verifyNoInteractions(internalClient);
    }

    @Test
    void execute_replayReturnsStoredResultWithoutCallingInternalClient() throws Exception {
        Map<String, Object> stored = Map.of("refund_status", "SUCCESS", "refund_id", "REFUND_1");
        when(guard.claim(anyString(), anyString(), any(), any()))
                .thenReturn(new ApprovalExecutionGuard.ExecutionDecision(
                        ApprovalExecutionGuard.ExecutionStatus.REPLAY,
                        null,
                        stored,
                        null
                ));

        Object result = tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":2990,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}"
        ));

        assertThat(result).isSameAs(stored);
        verifyNoInteractions(internalClient);
        verify(guard, never()).complete(any(), any());
    }

    @Test
    void execute_missingCallerIdentityFailsBeforeClaimAndInternalClient() throws Exception {
        RbacContext.clear();

        assertThatThrownBy(() -> tool.execute(args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":2990,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}"
        ))).isInstanceOf(ToolPermissionException.class)
                .hasMessage("调用者身份缺失");

        verify(guard, never()).claim(anyString(), anyString(), any(), any());
        verifyNoInteractions(internalClient);
    }

    @Test
    void execute_transportTimeoutLeavesClaimIncompleteAndRetryCanFinish() throws Exception {
        ApprovalExecutionGuard.ExecutionClaim recoveredClaim =
                new ApprovalExecutionGuard.ExecutionClaim(1001L, "execution-2", APPROVAL_DIGEST);
        when(guard.claim(anyString(), anyString(), any(), any()))
                .thenReturn(
                        new ApprovalExecutionGuard.ExecutionDecision(
                                ApprovalExecutionGuard.ExecutionStatus.CLAIMED,
                                claim,
                                null,
                                null
                        ),
                        new ApprovalExecutionGuard.ExecutionDecision(
                                ApprovalExecutionGuard.ExecutionStatus.CLAIMED,
                                recoveredClaim,
                                null,
                                null
                        )
                );
        Map<String, Object> committedResult = Map.of(
                "refund_status", "SUCCESS",
                "refund_id", "REFUND_FIRST"
        );
        when(internalClient.refund(
                "1234567890123456789", 2990, "APPROVAL_001", "协商一致"
        )).thenThrow(new RuntimeException("simulated timeout after Server commit"))
                .thenReturn(committedResult);
        JsonNode arguments = args(
                "{\"order_id\":\"1234567890123456789\",\"amount\":2990,"
                        + "\"approval_id\":\"APPROVAL_001\",\"reason\":\"协商一致\"}"
        );

        assertThatThrownBy(() -> tool.execute(arguments.deepCopy()))
                .isInstanceOf(RuntimeException.class)
                .hasMessageContaining("simulated timeout");
        verify(guard, never()).complete(eq(claim), any());

        Object retried = tool.execute(arguments.deepCopy());

        assertThat(retried).isSameAs(committedResult);
        verify(internalClient, times(2)).refund(
                "1234567890123456789", 2990, "APPROVAL_001", "协商一致"
        );
        verify(guard).complete(recoveredClaim, committedResult);
    }
}
