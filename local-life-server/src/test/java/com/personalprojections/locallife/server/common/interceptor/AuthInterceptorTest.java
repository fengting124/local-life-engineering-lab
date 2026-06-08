package com.personalprojections.locallife.server.common.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

/**
 * {@link AuthInterceptor} 鉴权拦截器测试 —— Bug #3 的回归测试主体。
 *
 * <h2>背景：Bug #3「按路径排除」导致同路径写操作被连坐放行</h2>
 * <p>历史版本在 {@link WebMvcConfig} 用 {@code excludePathPatterns("/api/v1/shops/*")}
 * 之类的纯路径规则把请求排除在拦截器之外。但 {@code excludePathPatterns} 无法区分
 * HTTP 方法——{@code GET /api/v1/shops/{id}}（应当公开）和
 * {@code PUT /api/v1/shops/{id}}（应当登录才能改）共享同一路径模式，
 * 结果两者一起被放行：未登录也能创建/修改门店、删笔记、发评论；
 * 已登录用户带着合法 Token 访问反而触发 NPE / 500（因为 UserContext 从未被写入）。
 *
 * <p>修复方式是把白名单收敛到 {@link AuthInterceptor} 内部的
 * {@code PUBLIC_ENDPOINTS}，按「HTTP 方法 + Ant 路径模式」二元组精确匹配。
 * 本测试类的核心目标——也是 Bug#3 当初能潜伏的根本原因——是把当前系统中
 * 全部 11 个 Controller 的每一个端点都列出来，与白名单逐一交叉核对，
 * 而不是只抽查几个接口。任何人以后修改 {@code PUBLIC_ENDPOINTS} 或新增/
 * 修改端点，只要破坏了「该公开的公开、该保护的保护」这条不变量，本测试必须失败。
 *
 * <h2>为什么不用 {@code @WebMvcTest}</h2>
 * <p>{@code @WebMvcTest(XxxController.class)} 默认不会加载 {@code @Component}
 * 拦截器和 {@code @Configuration} 类（{@link AuthInterceptor}、{@link WebMvcConfig}
 * 都不会出现在切片上下文中），所以鉴权链路天然就不在控制器切片测试的覆盖范围内。
 * 好在 {@link AuthInterceptor} 用 {@code @RequiredArgsConstructor} 做构造器注入，
 * 不依赖 Spring 容器即可直接 {@code new}，用 Mockito 替身掉 Redis 依赖，
 * 这是覆盖鉴权决策逻辑最直接、最快、最不脆弱的方式。
 */
class AuthInterceptorTest {

    private StringRedisTemplate stringRedisTemplate;
    private ValueOperations<String, String> valueOperations;
    private AuthInterceptor interceptor;

    @SuppressWarnings("unchecked")
    @BeforeEach
    void setUp() {
        stringRedisTemplate = mock(StringRedisTemplate.class);
        valueOperations = mock(ValueOperations.class);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOperations);
        interceptor = new AuthInterceptor(stringRedisTemplate, new ObjectMapper());
    }

    @AfterEach
    void tearDown() {
        // preHandle 在鉴权通过分支会写 UserContext / MDC（ThreadLocal），
        // afterCompletion 才负责清理。测试不会逐个调用 afterCompletion，
        // 这里手动清场，避免同一线程跑下一个测试方法时读到脏数据。
        UserContext.clear();
        MDC.clear();
    }

    // =====================================================================
    // 1. 全量端点清单 vs 白名单 —— Bug#3 的直接回归测试
    //
    //    数据来源：对 src/main/.../module 下全部 11 个 *Controller.java 的
    //    @xxxMapping 注解做的全量梳理（一个不漏）。"shouldBePublic" 列编码的是
    //    「当前代码的真实期望行为」，依据是 Javadoc 与 Service 实现，不是猜测：
    //      - 公开:   Javadoc 写明「游客可访问 / 查他人 / 渠道回调验签代替鉴权」，
    //                或 Service 方法不依赖 UserContext
    //      - 需登录: Javadoc 写明「需登录」「我的 xxx」「当前用户」，
    //                或 Service 方法内部读取 UserContext.getUserId()
    // =====================================================================

    private static Stream<Arguments> allEndpoints() {
        return Stream.of(
                // ---------- AuthController /api/v1/auth —— 不限方法整体公开 ----------
                Arguments.of("POST", "/api/v1/auth/code", true, "发送登录验证码"),
                Arguments.of("POST", "/api/v1/auth/login", true, "登录"),
                Arguments.of("POST", "/api/v1/auth/logout", true, "登出（故意留在白名单里，由 Controller 自行解析 Authorization Header）"),

                // ---------- FollowController /api/v1/follows —— 全部需登录 ----------
                Arguments.of("POST", "/api/v1/follows/2063843737552814082", false, "关注用户"),
                Arguments.of("DELETE", "/api/v1/follows/2063843737552814082", false, "取消关注"),
                Arguments.of("GET", "/api/v1/follows/common/2063843737552814082", false, "我和目标用户的共同关注列表（需要知道「我」是谁）"),
                Arguments.of("GET", "/api/v1/follows/status/2063843737552814082", false, "我是否关注了目标用户（需要知道「我」是谁）"),

                // ---------- InternalController /internal —— 不限方法整体公开（走 X-Internal-Key，不用 JWT） ----------
                Arguments.of("POST", "/internal/orders/ORDER20260608001/refund", true, "内部退款回调（仅 Copilot MCP Server 调用）"),
                Arguments.of("POST", "/internal/orders/ORDER20260608001/compensate-coupon", true, "内部补偿券回调"),

                // ---------- MerchantController /api/v1/merchants —— 全部需登录 ----------
                Arguments.of("POST", "/api/v1/merchants/apply", false, "申请成为商家"),
                Arguments.of("GET", "/api/v1/merchants/me", false, "查询我的商家信息"),
                Arguments.of("GET", "/api/v1/merchants/my-shops", false, "查询我名下门店列表"),

                // ---------- OrderController /api/v1/orders —— 全部需登录 ----------
                Arguments.of("POST", "/api/v1/orders", false, "创建订单"),
                Arguments.of("GET", "/api/v1/orders", false, "我的订单列表"),
                Arguments.of("GET", "/api/v1/orders/2063900000000000001", false, "订单详情"),
                Arguments.of("DELETE", "/api/v1/orders/2063900000000000001", false, "取消订单"),

                // ---------- PaymentController /api/v1/payments —— 创建需登录，回调 / Mock 公开 ----------
                Arguments.of("POST", "/api/v1/payments", false, "发起支付"),
                Arguments.of("POST", "/api/v1/payments/callback", true, "支付渠道回调（验签代替鉴权）"),
                Arguments.of("GET", "/api/v1/payments/mock-pay", true, "Mock 支付触发（生产环境通过 @Profile 关闭）"),

                // ---------- PostController（Bug#3 真实发生地之一）：GET 公开、写操作需登录 ----------
                Arguments.of("POST", "/api/v1/posts", false, "发布笔记"),
                Arguments.of("GET", "/api/v1/posts/2063848808399368193", true, "笔记详情"),
                Arguments.of("DELETE", "/api/v1/posts/2063848808399368193", false, "删除笔记 —— Bug#3 曾在这条路径上被静默放行"),
                Arguments.of("POST", "/api/v1/posts/2063848808399368193/likes", false, "点赞笔记"),
                Arguments.of("DELETE", "/api/v1/posts/2063848808399368193/likes", false, "取消点赞"),
                Arguments.of("GET", "/api/v1/shops/2063848616358965249/posts", true, "门店笔记列表"),
                Arguments.of("POST", "/api/v1/posts/2063848808399368193/comments", false, "发表评论 —— Bug#3 曾在这条路径上被静默放行"),
                Arguments.of("GET", "/api/v1/posts/2063848808399368193/comments", true, "评论列表"),
                Arguments.of("DELETE", "/api/v1/posts/2063848808399368193/comments/1", false, "删除评论"),

                // ---------- SearchController /api/v1/search —— 全部公开（游客可搜索）----------
                Arguments.of("GET", "/api/v1/search/shops", true, "搜索门店"),
                Arguments.of("GET", "/api/v1/search/posts", true, "搜索笔记"),

                // ---------- SeckillController /api/v1/seckill, /api/v1/coupons —— 模板公开，参与 / 我的券需登录 ----------
                Arguments.of("GET", "/api/v1/coupons/templates", true, "可抢券模板列表"),
                Arguments.of("POST", "/api/v1/seckill", false, "参与秒杀"),
                Arguments.of("GET", "/api/v1/seckill/result", false, "查询我的秒杀结果"),
                Arguments.of("GET", "/api/v1/coupons", false, "我的优惠券列表"),

                // ---------- ShopController（Bug#3 真实发生地之一）：GET 公开、写操作需登录 ----------
                Arguments.of("GET", "/api/v1/shops", true, "门店列表 / 搜索"),
                Arguments.of("GET", "/api/v1/shops/2063848616358965249", true, "门店详情"),
                Arguments.of("POST", "/api/v1/shops", false, "创建门店 —— Bug#3 曾在这条路径上被静默放行"),
                Arguments.of("PUT", "/api/v1/shops/2063848616358965249", false, "更新门店 —— Bug#3 曾在这条路径上被静默放行"),
                Arguments.of("PUT", "/api/v1/shops/2063848616358965249/status/online", false, "门店上线"),
                Arguments.of("PUT", "/api/v1/shops/2063848616358965249/status/offline", false, "门店下线"),

                // ---------- UserController /api/v1/users —— 全部需登录（含查看他人主页）----------
                Arguments.of("GET", "/api/v1/users/me", false, "我的信息"),
                Arguments.of("GET", "/api/v1/users/2063843737552814082", false, "查看他人主页"),

                // ---------- 运维 / 文档：不限方法整体公开 ----------
                Arguments.of("GET", "/actuator/health", true, "健康检查"),
                Arguments.of("GET", "/actuator/prometheus", true, "Prometheus 指标抓取"),
                Arguments.of("GET", "/swagger-ui/index.html", true, "Swagger UI"),
                Arguments.of("GET", "/v3/api-docs", true, "OpenAPI 文档")
        );
    }

    @ParameterizedTest(name = "[{index}] {0} {1} -> shouldBePublic={2} ({3})")
    @MethodSource("allEndpoints")
    void whitelistDecisionMatchesRealEndpointInventory(String method, String path,
                                                        boolean shouldBePublic, String description) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, path);
        MockHttpServletResponse response = new MockHttpServletResponse();

        if (shouldBePublic) {
            assertThat(interceptor.preHandle(request, response, new Object()))
                    .as("[%s %s] %s：应当是公开端点，没有 Token 也必须放行", method, path, description)
                    .isTrue();
        } else {
            assertThatThrownBy(() -> interceptor.preHandle(request, response, new Object()))
                    .as("[%s %s] %s：应当需要登录，缺少 Token 时必须被拦截——" +
                                    "如果这里没有抛异常，说明白名单又把这个写操作误放行了（Bug#3 复发）",
                            method, path, description)
                    .isInstanceOf(BizException.class)
                    .extracting(ex -> ((BizException) ex).getErrorCode())
                    .isEqualTo(ErrorCode.AUTH_TOKEN_MISSING);
        }
    }

    // =====================================================================
    // 2. 白名单短路：公开端点必须在「判断是否公开」这一步就返回 true，
    //    不能继续往下走到 Redis 查询 —— 否则不仅压力会全部打到 Redis，
    //    白名单请求的可观测行为（不生成 requestId）也会变化。
    // =====================================================================

    @Test
    void publicEndpoint_shortCircuitsBeforeTouchingRedisOrGeneratingRequestId() {
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/api/v1/shops/2063848616358965249");

        boolean passed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(passed).isTrue();
        verifyNoInteractions(stringRedisTemplate);
        assertThat(MDC.get("requestId")).as("白名单请求不应该生成 requestId").isNull();
    }

    // =====================================================================
    // 3. 同路径不同方法的精确区分 —— Bug#3 的本质就在这里
    //    （旧版 excludePathPatterns("/api/v1/shops/*") 会把 GET 和 PUT 一起放行）
    // =====================================================================

    @Test
    void sameResourcePath_readIsPublic_writeRequiresLogin() {
        String shopDetailPath = "/api/v1/shops/2063848616358965249";

        assertThat(interceptor.preHandle(
                new MockHttpServletRequest("GET", shopDetailPath), new MockHttpServletResponse(), new Object()))
                .as("GET 门店详情：公开")
                .isTrue();

        assertThatThrownBy(() -> interceptor.preHandle(
                new MockHttpServletRequest("PUT", shopDetailPath), new MockHttpServletResponse(), new Object()))
                .as("PUT 同一资源路径（更新门店）：必须需要登录。" +
                        "纯路径白名单无法区分 GET / PUT，这正是 Bug#3 被引入的根因")
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_MISSING);
    }

    // =====================================================================
    // 4. Authorization Header 解析（extractToken）的边界场景
    // =====================================================================

    @Test
    void protectedEndpoint_missingAuthorizationHeader_throwsTokenMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        // 不设置 Authorization Header

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_MISSING);
    }

    @Test
    void protectedEndpoint_headerWithoutBearerPrefix_throwsTokenMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        request.addHeader("Authorization", "abc123tokenwithoutbearerprefix");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_MISSING);

        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void protectedEndpoint_bearerWithBlankToken_throwsTokenMissing() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        request.addHeader("Authorization", "Bearer    ");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_MISSING);

        verifyNoInteractions(stringRedisTemplate);
    }

    // =====================================================================
    // 5. Token 校验链路：Redis 查询 -> 反序列化 -> 状态检查 -> 写 UserContext -> 续期
    // =====================================================================

    @Test
    void protectedEndpoint_tokenNotInRedis_throwsTokenExpired() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        request.addHeader("Authorization", "Bearer expired-or-unknown-token");
        when(valueOperations.get("login:token:expired-or-unknown-token")).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .as("Redis 中找不到该 Token（已过期或伪造）应当报 AUTH_TOKEN_EXPIRED，而不是 AUTH_TOKEN_MISSING")
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    void protectedEndpoint_corruptedJsonInRedis_throwsTokenExpired() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        request.addHeader("Authorization", "Bearer corrupted-token");
        when(valueOperations.get("login:token:corrupted-token")).thenReturn("{not-valid-json...");

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .as("Redis 中的数据反序列化失败时应当按 Token 失效处理（AUTH_TOKEN_EXPIRED），而不是抛 500")
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED);
    }

    @Test
    void protectedEndpoint_disabledAccount_throwsAccountDisabled() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        request.addHeader("Authorization", "Bearer disabled-user-token");

        LoginUserDTO disabledUser = LoginUserDTO.builder()
                .userId(2063843737552814082L)
                .mobile("139****1234")
                .nickname("被禁用的用户")
                .status("DISABLED")
                .build();
        when(valueOperations.get("login:token:disabled-user-token"))
                .thenReturn(new ObjectMapper().writeValueAsString(disabledUser));

        assertThatThrownBy(() -> interceptor.preHandle(request, new MockHttpServletResponse(), new Object()))
                .as("账号被禁用时应当立即拒绝（USER_ACCOUNT_DISABLED = 403），不需要等 Token TTL 自然过期")
                .isInstanceOf(BizException.class)
                .extracting(ex -> ((BizException) ex).getErrorCode())
                .isEqualTo(ErrorCode.USER_ACCOUNT_DISABLED);
    }

    @Test
    void protectedEndpoint_validToken_passesAndPopulatesUserContextAndRefreshesTtl() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        request.addHeader("Authorization", "Bearer bca75de27d924a97937313424269bad5");

        LoginUserDTO activeUser = LoginUserDTO.builder()
                .userId(2063843737552814082L)
                .mobile("139****1234")
                .nickname("测试商家")
                .status("ENABLED")
                .build();
        when(valueOperations.get("login:token:bca75de27d924a97937313424269bad5"))
                .thenReturn(new ObjectMapper().writeValueAsString(activeUser));

        boolean passed = interceptor.preHandle(request, new MockHttpServletResponse(), new Object());

        assertThat(passed).as("合法 Token 应当放行").isTrue();
        assertThat(UserContext.getUserId())
                .as("放行后 UserContext 必须写入正确的用户 ID —— Bug#3 修复前，被误放行的写请求里这里永远是 null，" +
                        "导致 Service 层 UserContext.getUserId() 直接 NullPointerException（带着合法 Token 也 500）")
                .isEqualTo(2063843737552814082L);
        assertThat(MDC.get("requestId")).as("受保护端点应当生成 requestId 便于按请求过滤日志").isNotBlank();

        verify(stringRedisTemplate).expire(
                eq("login:token:bca75de27d924a97937313424269bad5"), eq(7L), eq(TimeUnit.DAYS));
    }

    // =====================================================================
    // 6. afterCompletion：必须清理 ThreadLocal 和 MDC
    //    （Tomcat 线程池复用线程，不清理会导致下一个请求读到上一个用户的身份）
    // =====================================================================

    @Test
    void afterCompletion_clearsUserContextAndMdc_preventingThreadReuseLeakage() throws Exception {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/api/v1/shops");
        request.addHeader("Authorization", "Bearer bca75de27d924a97937313424269bad5");

        LoginUserDTO activeUser = LoginUserDTO.builder()
                .userId(2063843737552814082L)
                .mobile("139****1234")
                .nickname("测试商家")
                .status("ENABLED")
                .build();
        when(valueOperations.get("login:token:bca75de27d924a97937313424269bad5"))
                .thenReturn(new ObjectMapper().writeValueAsString(activeUser));

        MockHttpServletResponse response = new MockHttpServletResponse();
        interceptor.preHandle(request, response, new Object());
        assertThat(UserContext.getUserId()).isNotNull();
        assertThat(MDC.get("requestId")).isNotBlank();

        interceptor.afterCompletion(request, response, new Object(), null);

        assertThat(UserContext.get())
                .as("afterCompletion 后必须清空 UserContext，否则线程复用时下一个请求会读到上一个用户的身份——这是严重的安全漏洞")
                .isNull();
        assertThat(MDC.get("requestId")).as("afterCompletion 后必须清空 MDC 中的 requestId").isNull();
    }
}
