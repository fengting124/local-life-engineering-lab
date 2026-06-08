package com.personalprojections.locallife.copilot.tool.impl;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.copilot.domain.mapper.CopilotOrderMapper;
import com.personalprojections.locallife.copilot.rbac.RbacContext;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolNotFoundException;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolParameterException;
import com.personalprojections.locallife.copilot.tool.McpTool.ToolPermissionException;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/**
 * {@link QueryOrderTool} 单元测试：覆盖「参数提取 -> RBAC 上下文检查 -> 数据库查询 ->
 * 结果整理」这条 L1 只读工具的标准链路，以及它在本代码库里作为模板的两个关键设计：
 * <i>"不存在" 与 "无权限" 统一返回 not_found（防枚举攻击）</i>，和
 * {@code safeStr()} 对数据库 null 值的防御性处理。
 *
 * <h2>为什么直接 new，不走 Spring 容器</h2>
 * <p>{@link QueryOrderTool} 的两个构造依赖（{@link CopilotOrderMapper} / {@link ObjectMapper}）
 * 都可以轻量替身：前者用 Mockito mock，后者直接 {@code new}（构造 {@link JsonNode} 入参
 * 不需要真实的 Bean 生命周期）。{@link RbacContext} 通过其 {@code ThreadLocal} 直接注入，
 * 不需要 {@link com.personalprojections.locallife.copilot.rbac.RbacFilter} 参与——
 * 后者的 Header 解析逻辑已由 {@code RbacFilterTest} 独立覆盖。
 */
class QueryOrderToolTest {

    private final CopilotOrderMapper orderMapper = mock(CopilotOrderMapper.class);
    private final ObjectMapper objectMapper = new ObjectMapper();
    private final QueryOrderTool tool = new QueryOrderTool(orderMapper, objectMapper);

    @AfterEach
    void tearDown() {
        RbacContext.clear();
    }

    private JsonNode args(String json) throws Exception {
        return objectMapper.readTree(json);
    }

    // =====================================================================
    // 1. 参数校验：order_id 缺失/空白 —— 在查库和 RBAC 检查之前短路
    // =====================================================================

    @Test
    void execute_missingOrderId_throwsParameterExceptionWithFormatHint_beforeTouchingDbOrRbac() throws Exception {
        RbacContext.set(RbacContext.builder().userId(10001L).role("admin").build());

        assertThatThrownBy(() -> tool.execute(args("{}")))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("order_id 不能为空")
                .extracting(ex -> ((ToolParameterException) ex).getHint())
                .isEqualTo("order_id 格式：纯数字字符串（雪花 ID），如 '1234567890123456789'");

        verify(orderMapper, never()).selectOrderByOrderNo(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void execute_blankOrderId_throwsSameParameterException_asExtractRequiredStringTreatsBlankAsAbsent() throws Exception {
        assertThatThrownBy(() -> tool.execute(args("{\"order_id\": \"   \"}")))
                .isInstanceOf(ToolParameterException.class)
                .hasMessage("order_id 不能为空");

        verify(orderMapper, never()).selectOrderByOrderNo(org.mockito.ArgumentMatchers.anyString());
    }

    // =====================================================================
    // 2. RBAC 上下文缺失 —— 纵深防御：理论上 RbacFilter 已经拦截，这里是兜底
    // =====================================================================

    @Test
    void execute_noRbacContext_throwsPermissionException_asDefenseInDepth() throws Exception {
        // 故意不调用 RbacContext.set(...)，模拟"过滤器没有注入身份"这一理论上不该发生的状态
        assertThatThrownBy(() -> tool.execute(args("{\"order_id\": \"1234567890123456789\"}")))
                .isInstanceOf(ToolPermissionException.class)
                .hasMessage("未找到身份上下文，请检查 X-User-Id / X-User-Role Header");

        verify(orderMapper, never()).selectOrderByOrderNo(org.mockito.ArgumentMatchers.anyString());
    }

    // =====================================================================
    // 3. 订单不存在 / 无权限 —— 统一返回 not_found，不区分两种情况（防枚举攻击）
    // =====================================================================

    @Test
    void execute_orderNotFound_throwsNotFoundException_withOrderNoInDetail() throws Exception {
        RbacContext.set(RbacContext.builder().userId(20001L).role("merchant").merchantId(30001L).build());
        when(orderMapper.selectOrderByOrderNo("1234567890123456789")).thenReturn(null);

        assertThatThrownBy(() -> tool.execute(args("{\"order_id\": \"1234567890123456789\"}")))
                .as("无论是『订单真不存在』还是『merchant 查询了别家门店的订单』，"
                        + "对外都必须是同一个 not_found——区别对待就是给攻击者提供了订单号是否存在的探测信道")
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessage("订单不存在或无权查询: 1234567890123456789");
    }

    @Test
    void execute_orderRowEmpty_alsoTreatedAsNotFound() throws Exception {
        RbacContext.set(RbacContext.builder().userId(90001L).role("admin").build());
        when(orderMapper.selectOrderByOrderNo("1234567890123456789")).thenReturn(new HashMap<>());

        assertThatThrownBy(() -> tool.execute(args("{\"order_id\": \"1234567890123456789\"}")))
                .as("空 Map（row.isEmpty()）和 null 一样被当作『查无此单』，SQL 层 JOIN 过滤后两者都可能出现")
                .isInstanceOf(ToolNotFoundException.class)
                .hasMessage("订单不存在或无权查询: 1234567890123456789");
    }

    // =====================================================================
    // 4. 成功路径：整理结果 —— 验证 buildResult() 的整体形状与 safeStr() 的 null 防护
    // =====================================================================

    @Test
    void execute_orderFound_returnsStructuredResult_withNestedPaymentAndCouponMaps() throws Exception {
        RbacContext.set(RbacContext.builder().userId(90001L).role("admin").build());

        Map<String, Object> row = new HashMap<>();
        row.put("order_id", 1L);
        row.put("order_no", "1234567890123456789");
        row.put("user_id", 20001L);
        row.put("shop_id", 30001L);
        row.put("original_amount", 5990);
        row.put("coupon_discount", 1000);
        row.put("order_amount", 4990);
        row.put("order_status", "PAID");
        row.put("pay_status", "SUCCESS");
        row.put("channel", "wechat");
        row.put("trade_no", "TRADE_001");
        row.put("paid_amount", 4990);
        row.put("paid_at", "2026-06-01T10:00:00");
        row.put("coupon_status", "UNUSED");
        row.put("coupon_name", "满50减10");
        row.put("discount_type", "FIXED");
        row.put("discount_value", 1000);
        when(orderMapper.selectOrderByOrderNo("1234567890123456789")).thenReturn(row);

        Object result = tool.execute(args("{\"order_id\": \"1234567890123456789\"}"));

        assertThat(result).isInstanceOf(Map.class);
        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result;
        assertThat(structured)
                .as("顶层字段：safeStr() 把数据库返回的非 String 类型（Long/Integer）统一转换成字符串")
                .containsEntry("order_id", "1")
                .containsEntry("order_no", "1234567890123456789")
                .containsEntry("user_id", "20001")
                .containsEntry("order_status", "PAID")
                .containsEntry("original_amount", 5990);

        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) structured.get("payment");
        assertThat(payment)
                .containsEntry("pay_status", "SUCCESS")
                .containsEntry("channel", "wechat")
                .containsEntry("paid_amount", 4990);

        @SuppressWarnings("unchecked")
        Map<String, Object> coupon = (Map<String, Object>) structured.get("coupon");
        assertThat(coupon)
                .containsEntry("coupon_status", "UNUSED")
                .containsEntry("discount_value", 1000);
    }

    @Test
    void execute_orderWithoutPaymentOrCoupon_safeStrConvertsMissingFields_toNullInsteadOfThrowing() throws Exception {
        // 情况4：pay_status=null —— 该订单从未发起过支付，行里完全没有 payment/coupon 相关列
        RbacContext.set(RbacContext.builder().userId(90001L).role("cs").build());

        Map<String, Object> row = new HashMap<>();
        row.put("order_id", 2L);
        row.put("order_no", "9999999999999999999");
        row.put("user_id", 20002L);
        row.put("shop_id", 30002L);
        row.put("order_status", "WAIT_PAY");
        // 不填 pay_status / channel / trade_no / paid_at / coupon_status 等——模拟数据库里这些列为 NULL
        when(orderMapper.selectOrderByOrderNo("9999999999999999999")).thenReturn(row);

        Object result = tool.execute(args("{\"order_id\": \"9999999999999999999\"}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result;
        @SuppressWarnings("unchecked")
        Map<String, Object> payment = (Map<String, Object>) structured.get("payment");
        @SuppressWarnings("unchecked")
        Map<String, Object> coupon = (Map<String, Object>) structured.get("coupon");

        assertThat(payment)
                .as("safeStr(null) 必须返回 null 而不是抛 NPE 或字符串 \"null\"——"
                        + "这是 x-business-hint 里『情况4：pay_status=null → 未发起过支付』判断的数据基础")
                .containsEntry("pay_status", null)
                .containsEntry("channel", null)
                .containsEntry("paid_amount", 0);
        assertThat(coupon).containsEntry("coupon_status", null);
    }

    // =====================================================================
    // 5. merchant 角色成功查询 —— 确认应用层不会因为多出的 ctx.isMerchant() 分支而出错
    // =====================================================================

    @Test
    void execute_merchantRoleWithOwnShop_succeedsAndLogsWithoutAlteringResult() throws Exception {
        // SQL 已经用 JOIN shop + merchant_id 做了过滤，应用层这里只是多一层日志记录（见源码 Step 4 注释），
        // 不应改变返回结果——这条用例确认那个分支不会意外抛出或修改数据
        RbacContext.set(RbacContext.builder().userId(20001L).role("merchant").merchantId(30001L).build());

        Map<String, Object> row = new HashMap<>();
        row.put("order_id", 3L);
        row.put("order_no", "1111111111111111111");
        row.put("shop_id", 40001L);
        row.put("order_status", "PAID");
        when(orderMapper.selectOrderByOrderNo("1111111111111111111")).thenReturn(row);

        Object result = tool.execute(args("{\"order_id\": \"1111111111111111111\"}"));

        @SuppressWarnings("unchecked")
        Map<String, Object> structured = (Map<String, Object>) result;
        assertThat(structured).containsEntry("order_status", "PAID").containsEntry("shop_id", "40001");
        verify(orderMapper).selectOrderByOrderNo(eq("1111111111111111111"));
    }
}
