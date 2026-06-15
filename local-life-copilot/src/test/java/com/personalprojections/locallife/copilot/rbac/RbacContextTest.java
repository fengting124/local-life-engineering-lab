package com.personalprojections.locallife.copilot.rbac;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * {@link RbacContext} 单元测试：覆盖 {@code canAccessMerchant()} 的角色判定逻辑、
 * 角色判断方法的健壮性，以及 ThreadLocal 存取/清理的往返语义。
 *
 * <h2>为什么 canAccessMerchant() 值得专门测</h2>
 * <p>这是 Copilot 里唯一一处「商家数据隔离」的判定入口——merchant 角色能否看到
 * 别家门店的数据，全靠这一个方法的返回值。条件分支写错一个，就是真实的越权漏洞。
 */
class RbacContextTest {

    @AfterEach
    void tearDown() {
        RbacContext.clear();
    }

    // =====================================================================
    // 1. canAccessMerchant —— 商家数据隔离的核心判定
    // =====================================================================

    private static Stream<Arguments> accessDecisions() {
        return Stream.of(
                Arguments.of("admin", 20001L, 99999L, true, "admin 可跨商家访问任意数据"),
                Arguments.of("admin", 20001L, null, true, "admin 甚至可以访问『目标商家未知』的数据（如平台级统计）"),
                Arguments.of("cs", null, 20001L, true, "cs 可跨商家访问任意数据，自身不持有 merchantId"),
                Arguments.of("cs", null, null, true, "cs 访问『目标商家未知』的数据同样放行"),
                Arguments.of("merchant", 20001L, 20001L, true, "merchant 访问自己门店的数据：放行"),
                Arguments.of("merchant", 20001L, 20002L, false, "merchant 访问别家门店的数据：拒绝——这是防止商家越权看到竞对数据的关键防线"),
                Arguments.of("merchant", 20001L, null, false, "merchant 试图访问『目标商家未知』的数据：拒绝（不能因目标不明确就放宽）"),
                Arguments.of("merchant", null, 20001L, false, "merchant 自身 merchantId 缺失（理论上 RbacFilter 已拦截，这里是纵深防御）：拒绝")
        );
    }

    @ParameterizedTest(name = "[{index}] role={0}, selfMerchantId={1}, targetMerchantId={2} -> {3} ({4})")
    @MethodSource("accessDecisions")
    void canAccessMerchant_decidesByRoleAndMerchantIdMatch(
            String role, Long selfMerchantId, Long targetMerchantId, boolean expected, String description) {
        RbacContext ctx = RbacContext.builder().userId(1L).role(role).merchantId(selfMerchantId).build();

        assertThat(ctx.canAccessMerchant(targetMerchantId))
                .as(description)
                .isEqualTo(expected);
    }

    // =====================================================================
    // 2. isMerchant / isCs / isAdmin —— 角色判定的边界情况
    // =====================================================================

    @Test
    void roleCheckers_areCaseSensitiveExactMatches_withNoNormalization() {
        // 角色字符串是和 ToolDefinition.xAllowedRoles 做精确比较的协议值
        // （来自 X-User-Role Header，RbacFilter 不做大小写归一化），
        // 这里把"大小写不同就视为不同角色"这个容易被想当然的前提钉死成断言。
        RbacContext weirdCase = RbacContext.builder().role("Merchant").build();

        assertThat(weirdCase.isMerchant()).as("『Merchant』≠『merchant』，角色比较大小写敏感").isFalse();
        assertThat(weirdCase.isCs()).isFalse();
        assertThat(weirdCase.isAdmin()).isFalse();
    }

    @Test
    void roleCheckers_returnFalseForNullRole_insteadOfThrowingNpe() {
        // isXxx() 实现用的是 "merchant".equals(role)（常量在左），而不是 role.equals("merchant")。
        // 这个顺序选择本身就是为了让 role=null 时安全返回 false 而不是 NPE——
        // 这条用例把"顺序不能被颠倒"这个隐含正确性约束变成可执行的回归保护。
        RbacContext ctx = RbacContext.builder().role(null).build();

        assertThat(ctx.isMerchant()).as("null role 不应触发 NPE，应安全返回 false").isFalse();
        assertThat(ctx.isCs()).isFalse();
        assertThat(ctx.isAdmin()).isFalse();
        assertThat(ctx.canAccessMerchant(20001L)).as("role=null 时落入『非 admin/cs』分支，再按 merchantId 匹配判断").isFalse();
    }

    // =====================================================================
    // 3. ThreadLocal 存取与清理往返
    // =====================================================================

    @Test
    void setGetClear_roundTripCorrectly() {
        assertThat(RbacContext.get()).as("测试开始前不应该有残留上下文（@AfterEach 已清理上一条用例）").isNull();

        RbacContext ctx = RbacContext.builder().userId(10001L).role("merchant").merchantId(20001L).build();
        RbacContext.set(ctx);
        assertThat(RbacContext.get()).as("set 之后 get 应返回同一个实例").isSameAs(ctx);

        RbacContext.clear();
        assertThat(RbacContext.get())
                .as("clear 之后必须读到 null —— RbacFilter 在 finally 块里依赖这一点防止线程池复用时的身份串号")
                .isNull();
    }
}
