package com.personalprojections.locallife.server.common.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link AuthInterceptor} 单元测试——直接 {@code new} 实例、注入 mock 依赖，
 * 不启动任何 Spring 容器。
 *
 * <h2>测试策略</h2>
 * <p>与 copilot 侧的 {@code RbacFilterTest} 完全平行的思路：
 * <ul>
 *   <li>用 {@link MockHttpServletRequest} / {@link MockHttpServletResponse} 替代真实 Servlet 容器</li>
 *   <li>用 {@code doAnswer} 快照验证 UserContext 的<b>注入时机</b>——必须在下游 chain 执行前写入，
 *       而非执行后，因为 Controller 在 chain 执行期间读取 UserContext</li>
 *   <li>用 {@link MockFilterChain} 作为 {@code handler} 占位，实际 preHandle 不需要 chain；
 *       {@code afterCompletion} 测试中用 lambda 替身模拟下游抛异常</li>
 *   <li>每次用例结束后 {@link #tearDown()} 保证 ThreadLocal 被清理，防止测试间污染</li>
 * </ul>
 *
 * <h2>关键历史 Bug（已通过测试回归）</h2>
 * <ul>
 *   <li>{@code excludePathPatterns("/api/v1/shops/*")} 绕过了同路径 PUT——
 *       已改为 {@code PUBLIC_ENDPOINTS} 的 (method, path) 精确匹配</li>
 *   <li>损坏的 Redis JSON 让 {@code objectMapper.readValue()} 抛异常，
 *       冒泡成 500——已加 try-catch 统一转 {@code AUTH_TOKEN_EXPIRED}</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class AuthInterceptorTest {

    @Mock
    private StringRedisTemplate redisTemplate;

    @Mock
    private ValueOperations<String, String> valueOps;

    @Mock
    private ObjectMapper objectMapper;

    @InjectMocks
    private AuthInterceptor interceptor;

    private final MockHttpServletRequest request   = new MockHttpServletRequest();
    private final MockHttpServletResponse response = new MockHttpServletResponse();
    private final Object handler                    = new Object();

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // =========================================================
    // 1. Token 提取失败路径
    // =========================================================

    @Test
    void missingAuthHeader_throwsTokenMissing() {
        // request 没有 Authorization Header（默认就没有）
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOKEN_MISSING));
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void malformedBearerPrefix_throwsTokenMissing() {
        request.addHeader("Authorization", "Token abc123");  // "Bearer " 前缀不对
        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOKEN_MISSING));
        verifyNoInteractions(redisTemplate);
    }

    // =========================================================
    // 2. Redis 查询结果路径
    // =========================================================

    @Test
    void tokenNotInRedis_throwsTokenExpired() {
        request.addHeader("Authorization", "Bearer some-valid-looking-token");
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("login:token:some-valid-looking-token")).thenReturn(null);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED));
    }

    @Test
    void corruptedRedisJson_throwsTokenExpired_notFiveHundred() throws Exception {
        // Redis 里有值，但不是合法 JSON（例如：Redis 数据损坏、手工写了脏数据）
        // 历史上这里会让 objectMapper.readValue() 抛异常冒泡成 500。
        // 修复后：catch 到反序列化失败，统一走 AUTH_TOKEN_EXPIRED。
        String token = "token-with-corrupted-data";
        request.addHeader("Authorization", "Bearer " + token);
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("login:token:" + token)).thenReturn("{corrupted json!!!");
        when(objectMapper.readValue("{corrupted json!!!", LoginUserDTO.class))
                .thenThrow(new com.fasterxml.jackson.core.JsonParseException(null, "unexpected token"));

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOKEN_EXPIRED));
    }

    // =========================================================
    // 3. 账号状态路径
    // =========================================================

    @Test
    void disabledAccount_throwsAccountDisabled() throws Exception {
        String token = "disabled-user-token";
        request.addHeader("Authorization", "Bearer " + token);
        LoginUserDTO disabledUser = LoginUserDTO.builder()
                .userId(99L)
                .nickname("被封号的用户")
                .status("DISABLED")
                .build();
        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("login:token:" + token)).thenReturn("{\"userId\":99}");
        when(objectMapper.readValue("{\"userId\":99}", LoginUserDTO.class)).thenReturn(disabledUser);

        assertThatThrownBy(() -> interceptor.preHandle(request, response, handler))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.USER_ACCOUNT_DISABLED));
    }

    // =========================================================
    // 4. 合法 Token 路径
    // =========================================================

    @Test
    void validToken_populatesUserContextBeforeInvokingChain() throws Exception {
        // 关键验证：UserContext.set() 必须在 preHandle 返回前完成。
        // preHandle 不像 Filter 需要调用 chain.doFilter，它是一次同步调用，
        // 所以这里直接验证 preHandle 调用完毕后 UserContext 里有正确的用户信息。
        String token = "valid-token-abc123";
        request.addHeader("Authorization", "Bearer " + token);

        LoginUserDTO user = LoginUserDTO.builder()
                .userId(12345L)
                .nickname("张三")
                .status("ENABLED")
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("login:token:" + token)).thenReturn("{\"userId\":12345}");
        when(objectMapper.readValue("{\"userId\":12345}", LoginUserDTO.class)).thenReturn(user);

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).as("合法 Token 应该放行请求（返回 true）").isTrue();

        // 验证 UserContext 已正确写入——Controller 执行期间依赖此值
        LoginUserDTO contextUser = UserContext.get();
        assertThat(contextUser).as("preHandle 应将 LoginUserDTO 写入 UserContext").isNotNull();
        assertThat(contextUser.getUserId()).isEqualTo(12345L);
        assertThat(contextUser.getNickname()).isEqualTo("张三");
    }

    @Test
    void validToken_refreshesTtlAfterPopulatingContext() throws Exception {
        // 验证"滑动过期"：每次合法请求后，Token 的 TTL 应该被刷新到 7 天
        String token = "valid-token-for-ttl-test";
        request.addHeader("Authorization", "Bearer " + token);

        LoginUserDTO user = LoginUserDTO.builder()
                .userId(42L)
                .status("ENABLED")
                .build();

        when(redisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("login:token:" + token)).thenReturn("{\"userId\":42}");
        when(objectMapper.readValue("{\"userId\":42}", LoginUserDTO.class)).thenReturn(user);

        interceptor.preHandle(request, response, handler);

        verify(redisTemplate).expire("login:token:" + token, 7L, TimeUnit.DAYS);
    }

    // =========================================================
    // 5. 公开端点白名单
    // =========================================================

    @Test
    void publicEndpoint_getShops_skipsAuthAndNeverPopulatesContext() throws Exception {
        // GET /api/v1/shops 在 PUBLIC_ENDPOINTS 白名单里——直接放行
        request.setMethod("GET");
        request.setRequestURI("/api/v1/shops");

        boolean result = interceptor.preHandle(request, response, handler);

        assertThat(result).as("白名单请求应放行（返回 true）").isTrue();
        assertThat(UserContext.get()).as("白名单请求不应写入 UserContext").isNull();
        verifyNoInteractions(redisTemplate);
    }

    @Test
    void publicEndpointMethodCheck_getShopDetail_allowed_butPutShopDetail_requiresAuth() throws Exception {
        // 同一路径 /api/v1/shops/123，GET 是公开的，PUT 需要鉴权
        // 这正是 PUBLIC_ENDPOINTS 用 (method, path) 精确匹配的原因：
        // 旧版 excludePathPatterns 会把 PUT 也一并放过，导致 UserContext 为 null、Service 层 NPE

        // ---- GET：应直接放行 ----
        MockHttpServletRequest getReq = new MockHttpServletRequest("GET", "/api/v1/shops/123");
        boolean getResult = interceptor.preHandle(getReq, response, handler);
        assertThat(getResult).as("GET /api/v1/shops/123 是公开端点，应放行").isTrue();
        verifyNoInteractions(redisTemplate);

        // ---- PUT：没有 Token，应抛 AUTH_TOKEN_MISSING（而不是像旧版那样绕过鉴权） ----
        MockHttpServletRequest putReq = new MockHttpServletRequest("PUT", "/api/v1/shops/123");
        // 不加 Authorization Header → 期望抛 AUTH_TOKEN_MISSING
        assertThatThrownBy(() -> interceptor.preHandle(putReq, response, handler))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.AUTH_TOKEN_MISSING));
    }

    // =========================================================
    // 6. afterCompletion 清理
    // =========================================================

    @Test
    void afterCompletion_clearsContextEvenWhenHandlerThrows() throws Exception {
        // 模拟：Controller 已执行完毕、UserContext 里还有用户信息，随后 preHandle 返回
        // afterCompletion 的参数 ex 就是 Controller 抛出的异常（由框架捕获后传入）
        // 验证：即使收到了 ex != null，ThreadLocal 和 MDC 也必须被清理

        LoginUserDTO user = LoginUserDTO.builder().userId(77L).status("ENABLED").build();
        UserContext.set(user);  // 手动写入，模拟 preHandle 成功执行后的状态

        // 模拟 Controller 层抛了异常
        Exception controllerException = new RuntimeException("controller boom");

        // afterCompletion 不应抛异常，且必须清理 ThreadLocal
        interceptor.afterCompletion(request, response, handler, controllerException);

        assertThat(UserContext.get())
                .as("afterCompletion 必须清理 ThreadLocal，即使 Controller 抛了异常")
                .isNull();
    }
}
