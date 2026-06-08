package com.personalprojections.locallife.copilot.rbac;

import jakarta.servlet.FilterChain;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * {@link RbacFilter} 单元测试：覆盖 Header 校验、错误响应、{@link RbacContext}
 * 的注入时机，以及 ThreadLocal 在 {@code finally} 块中的清理保证。
 *
 * <h2>为什么直接 new，不用 @WebMvcTest</h2>
 * <p>{@link RbacFilter} 是无依赖的 {@code @Component}（没有构造器参数），
 * 直接 {@code new} 即可拿到完全真实的实例——覆盖其内部判断逻辑最快、最不脆弱
 * 的方式，与 local-life-server 的 {@code AuthInterceptorTest} 同一思路。
 *
 * <h2>验证「注入时机」而不是「注入后的残留状态」</h2>
 * <p>多个用例没有满足于断言"doFilter 返回后 RbacContext 是什么"，而是用
 * {@link FilterChain} 替身在 {@code chain.doFilter()} 真正执行的瞬间拍快照——
 * 因为 {@code McpController} 正是在请求处理过程中（链路下游）读取
 * {@link RbacContext#get()} 的。只看"之后"的状态，无法区分"在 chain 执行前已正确
 * 注入"和"chain 执行后才注入、下游其实读到 null"这两种南辕北辙的实现。
 *
 * <h2>关于 X-User-Id / X-Merchant-Id 非数字格式的修复</h2>
 * <p>原实现对 {@code Long.parseLong(...)} 没有任何保护——格式错误的 Header
 * （比如 Agent Service 传错、或运维手工 curl 时手滑）会让 {@code NumberFormatException}
 * 直接冒泡成容器层 500，而不是这个过滤器本该负责的、结构清晰的 401。
 * 已在 {@link RbacFilter} 里补一个 {@code try/catch}，与"缺失 Header"统一走
 * 401 + JSON 错误体；下面 {@code nonNumericXxx} 两条用例就是这个修复的回归测试。
 */
class RbacFilterTest {

    private final RbacFilter filter = new RbacFilter();

    @AfterEach
    void tearDown() {
        RbacContext.clear();
    }

    // =====================================================================
    // 1. 缺失必填 Header —— 401，且不放行到下游
    // =====================================================================

    @Test
    void missingUserIdHeader_returns401_andNeverInvokesChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Role", "merchant");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"missing X-User-Id or X-User-Role header\"}");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void missingUserRoleHeader_returns401_andNeverInvokesChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "10001");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(401);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"missing X-User-Id or X-User-Role header\"}");
        verify(chain, never()).doFilter(any(), any());
    }

    // =====================================================================
    // 2. merchant 角色缺 X-Merchant-Id —— 403；cs/admin 则不需要
    // =====================================================================

    @Test
    void merchantRoleWithoutMerchantId_returns403_andNeverInvokesChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "10001");
        request.addHeader("X-User-Role", "merchant");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).isEqualTo(403);
        assertThat(response.getContentAsString()).isEqualTo("{\"error\":\"merchant role requires X-Merchant-Id header\"}");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void csRoleWithoutMerchantId_isAllowed_becauseOnlyMerchantRoleRequiresIt() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "30001");
        request.addHeader("X-User-Role", "cs");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).as("cs/admin 没有 merchantId 是正常状态，不应被当成错误拦下").isEqualTo(200);
        verify(chain).doFilter(any(), any());
    }

    // =====================================================================
    // 3. 非数字 Header —— 修复点的回归测试（原先会 500）
    // =====================================================================

    @Test
    void nonNumericUserId_returns401_insteadOfLetting500Escape() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "not-a-number");
        request.addHeader("X-User-Role", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus())
                .as("X-User-Id 不是合法数字时应返回干净的 401，而不是让 NumberFormatException 冒泡成容器层 500")
                .isEqualTo(401);
        assertThat(response.getContentAsString()).contains("numeric");
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    void nonNumericMerchantId_returns401_insteadOfLetting500Escape() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "10001");
        request.addHeader("X-User-Role", "merchant");
        request.addHeader("X-Merchant-Id", "abc");
        MockHttpServletResponse response = new MockHttpServletResponse();
        FilterChain chain = mock(FilterChain.class);

        filter.doFilter(request, response, chain);

        assertThat(response.getStatus()).as("X-Merchant-Id 同理，格式错误也应该是干净的 401").isEqualTo(401);
        assertThat(response.getContentAsString()).contains("numeric");
        verify(chain, never()).doFilter(any(), any());
    }

    // =====================================================================
    // 4. /actuator 路径整体放行，且不解析 / 注入身份
    // =====================================================================

    @Test
    void actuatorPath_skipsIdentityCheck_andNeverPopulatesContext() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/actuator/health");
        // 故意不带任何身份 Header —— 健康检查探针不应该需要身份
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<RbacContext> seenDuringChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seenDuringChain.set(RbacContext.get());
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        verify(chain).doFilter(any(), any());
        assertThat(seenDuringChain.get())
                .as("/actuator 路径直接跳过身份解析，链路下游不应该看到任何 RbacContext")
                .isNull();
    }

    // =====================================================================
    // 5. 合法请求：在 chain 执行的瞬间，RbacContext 必须已经就位且字段正确
    // =====================================================================

    @Test
    void validMerchantRequest_populatesContextBeforeInvokingChain() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "10001");
        request.addHeader("X-User-Role", "merchant");
        request.addHeader("X-Merchant-Id", "20001");
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<RbacContext> seenDuringChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seenDuringChain.set(RbacContext.get());
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        RbacContext seen = seenDuringChain.get();
        assertThat(seen)
                .as("McpController 正是在 chain 执行期间读取 RbacContext —— 必须在调用 chain 之前完成注入")
                .isNotNull();
        assertThat(seen.getUserId()).isEqualTo(10001L);
        assertThat(seen.getRole()).isEqualTo("merchant");
        assertThat(seen.getMerchantId()).isEqualTo(20001L);
    }

    @Test
    void validAdminRequest_populatesContextWithNullMerchantId() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "90001");
        request.addHeader("X-User-Role", "admin");
        // 不带 X-Merchant-Id —— admin 角色不需要
        MockHttpServletResponse response = new MockHttpServletResponse();

        AtomicReference<RbacContext> seenDuringChain = new AtomicReference<>();
        FilterChain chain = mock(FilterChain.class);
        doAnswer(inv -> {
            seenDuringChain.set(RbacContext.get());
            return null;
        }).when(chain).doFilter(any(), any());

        filter.doFilter(request, response, chain);

        RbacContext seen = seenDuringChain.get();
        assertThat(seen.getRole()).isEqualTo("admin");
        assertThat(seen.getMerchantId()).as("admin 角色的 merchantId 应为 null，不应被错误地解析成 0 或抛异常").isNull();
    }

    // =====================================================================
    // 6. finally 块的清理保证：下游抛异常时 ThreadLocal 也不能残留
    // =====================================================================

    @Test
    void context_isClearedInFinally_evenWhenDownstreamChainThrows() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/mcp");
        request.addHeader("X-User-Id", "10001");
        request.addHeader("X-User-Role", "admin");
        MockHttpServletResponse response = new MockHttpServletResponse();

        FilterChain chain = mock(FilterChain.class);
        doThrow(new RuntimeException("downstream boom")).when(chain).doFilter(any(), any());

        assertThatThrownBy(() -> filter.doFilter(request, response, chain))
                .as("异常应当继续向上传播，过滤器不应该吞掉它")
                .isInstanceOf(RuntimeException.class)
                .hasMessage("downstream boom");

        assertThat(RbacContext.get())
                .as("即便链路下游抛异常，finally 块也必须清理 ThreadLocal —— 否则线程池复用时，" +
                        "下一个落在同一线程上的请求会读到这次失败请求残留的身份，是严重的越权风险")
                .isNull();
    }
}
