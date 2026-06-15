package com.personalprojections.locallife.server.module.auth.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.interceptor.AuthInterceptor;
import com.personalprojections.locallife.server.common.ratelimit.RateLimitInterceptor;
import com.personalprojections.locallife.server.module.auth.dto.LoginResponse;
import com.personalprojections.locallife.server.module.auth.service.AuthService;
import io.micrometer.tracing.Tracer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * {@link AuthController} 切片测试：只加载 Web 层（{@code @WebMvcTest}），
 * Service 用 {@code @MockitoBean} 替身，验证「请求绑定 -> 参数校验 -> 委托 Service ->
 * 包装 Result -> 异常映射」这条链路，不关心 Service 内部业务逻辑（那是 Service 单测的职责）。
 *
 * <h2>这一层测什么、为什么</h2>
 * <ul>
 *   <li><b>请求 / 响应映射</b>：URL、HTTP 方法、JSON 请求体绑定到 DTO 字段是否正确。
 *       这正是 Bug #1（缺少 {@code -parameters} 编译参数导致 Spring 无法按参数名绑定）
 *       会现身的层——{@code MockMvc} 走的是真实的 {@code DispatcherServlet} + 参数解析器，
 *       直接调用 Java 方法测不出这类问题。</li>
 *   <li><b>参数校验</b>：{@code @Valid} + Bean Validation 注解触发后，
 *       {@link com.personalprojections.locallife.server.common.exception.GlobalExceptionHandler}
 *       是否正确转换成 {@code 400 SYS_PARAM_INVALID}。</li>
 *   <li><b>响应包装</b>：{@code Result<T>} 的 JSON 形状（code / message / data / timestamp，
 *       且 {@code data} 为 null 时不出现在 JSON 里）。</li>
 * </ul>
 *
 * <p>注意：和直觉相反，{@code @WebMvcTest} 其实会把 {@code @Configuration} 类
 * （{@code WebMvcConfig}，因为它实现了 {@code WebMvcConfigurer}）一起装进切片上下文，
 * 而 {@code WebMvcConfig} 在 {@code addInterceptors()} 里把 {@code AuthInterceptor}
 * 和 {@code RateLimitInterceptor} 注册到了 {@code "/**"}——也就是说这里的请求
 * <strong>真的会经过鉴权 / 限流拦截器链</strong>，不会被自动跳过（这是写这批测试时
 * 实测踩到的坑：一开始按"切片测试不含拦截器"的假设来写，结果受保护端点的用例
 * 全部收到 401，公开端点反而一切正常——现象本身就是最直接的反证）。
 * 本类把两个拦截器整体替换成 {@code @MockitoBean} 放行型替身（见
 * {@link #allowAllRequestsThroughInterceptors()}），鉴权判断本身的正确性
 * 仍然完全由 {@code AuthInterceptorTest} 的完整端点清单独立覆盖，两层不重复判断。
 */
@WebMvcTest(AuthController.class)
class AuthControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @MockitoBean
    private AuthService authService;

    // @WebMvcTest 切片会把 WebMvcConfig（@Configuration + WebMvcConfigurer）和 TraceIdFilter
    // （@Component + Filter）一并装进上下文——前者在 addInterceptors() 里把 AuthInterceptor /
    // RateLimitInterceptor 注册到 "/**"，后者的构造函数依赖 io.micrometer.tracing.Tracer。
    // 也就是说切片测试并不会像直觉认为的那样"只剩 Controller、天然跳过拦截器链"：
    // 这两个拦截器在这里是真实存在、会拦截每一个请求的（详见上面类注释里记录的踩坑过程）。
    //
    // AuthInterceptor 的鉴权判断本身已经由 AuthInterceptorTest 用完整端点清单详尽覆盖，
    // 这里没必要也不应该重新搭一套"伪造合法 Redis 会话"的重型基础设施去蒙混过关——
    // 那样会让两层测试在"鉴权该不该通过"这件事上重复判断，且一旦 AuthInterceptor 的
    // Redis key 格式或 LoginUserDTO 形状变化，这边也要跟着改。更干净的隔离方式：
    // 把两个拦截器整体替换成"放行型"替身，让请求畅通无阻地抵达 Controller——
    // 具体见下面 {@link #allowAllRequestsThroughInterceptors()}。
    @MockitoBean
    private Tracer tracer;

    @MockitoBean
    private AuthInterceptor authInterceptor;

    @MockitoBean
    private RateLimitInterceptor rateLimitInterceptor;

    // 第三类"意外住户"更隐蔽：LocalLifeServerApplication 上的 @MapperScan 会注册一个
    // BeanDefinitionRegistryPostProcessor，把 domain.mapper 包下全部 13 个 @Mapper 接口
    // 注册成 MapperFactoryBean——这一步是 @Import 级别的注册，不受 @WebMvcTest 切片
    // 过滤器影响，每个 MapperFactoryBean 在容器刷新时都会被立即实例化并执行
    // checkDaoConfig()，要求存在 SqlSessionFactory/SqlSessionTemplate
    // （而切片上下文不会自动配置 MyBatis，二者都没有）。
    // RETURNS_DEEP_STUBS 让 mock.getConfiguration() 返回另一个深层 stub 而不是 null，
    // 使 checkDaoConfig 内部的 hasMapper()/addMapper() 都能安全地空跑过去，
    // 不触发任何真实 SQL 或数据库连接。
    @MockitoBean(answers = Answers.RETURNS_DEEP_STUBS)
    private SqlSessionTemplate sqlSessionTemplate;

    @BeforeEach
    void allowAllRequestsThroughInterceptors() throws Exception {
        // mock 的 boolean 方法默认返回 false——对 HandlerInterceptor 来说就是"拦截每一个请求"，
        // 必须显式放行两者：AuthInterceptor 先于 RateLimitInterceptor 注册，链上任何一个
        // 返回 false 都会在不写响应体的情况下直接短路整条链，让断言落在空 200 响应上
        // （而不是预期的业务响应），现象会比 401 更难定位。
        //
        // 本类测的全是白名单端点，理论上 preHandle 不放行也会因为白名单短路而通过——
        // 但那是 AuthInterceptor 内部的真实判断逻辑在生效，而不是本类主动控制的前提条件。
        // 统一在这里放行，让"白名单端点必然可达"这件事不再依赖 AuthInterceptor 的内部实现，
        // 与 Shop/PostControllerTest 保持同一套可复制的模式，方便后续替其余 8 个 Controller
        // 写切片测试时直接照搬。
        when(authInterceptor.preHandle(any(), any(), any())).thenReturn(true);
        when(rateLimitInterceptor.preHandle(any(), any(), any())).thenReturn(true);
    }

    // =====================================================================
    // POST /api/v1/auth/code —— 发送验证码
    // =====================================================================

    @Test
    void sendCode_validMobile_delegatesToServiceAndReturnsOkWithoutData() throws Exception {
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"13900001234\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data").doesNotExist());

        verify(authService).sendCode(argThat(req -> "13900001234".equals(req.getMobile())));
    }

    @Test
    void sendCode_blankMobile_returns400ParamInvalidWithNotBlankMessage() throws Exception {
        // mobile 缺省（null）：只触发 @NotBlank（@Pattern 对 null 值不校验），消息确定、不受多重校验拼接顺序影响
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("手机号不能为空"));

        verifyNoInteractions(authService);
    }

    @Test
    void sendCode_malformedMobile_returns400ParamInvalidWithPatternMessage() throws Exception {
        // "12345" 非空但不满足 1[0-9]{10}：只触发 @Pattern，消息同样确定
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"12345\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("手机号格式不正确"));

        verifyNoInteractions(authService);
    }

    // =====================================================================
    // POST /api/v1/auth/login —— 验证码登录
    // =====================================================================

    @Test
    void login_validRequest_returnsTokenPayloadFromService() throws Exception {
        when(authService.login(argThat(req ->
                "13900001234".equals(req.getMobile()) && "123456".equals(req.getCode()))))
                .thenReturn(LoginResponse.builder()
                        .token("bca75de27d924a97937313424269bad5")
                        .userId("2063843737552814082")
                        .nickname("测试用户")
                        .build());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"13900001234\", \"code\": \"123456\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.token").value("bca75de27d924a97937313424269bad5"))
                .andExpect(jsonPath("$.data.userId").value("2063843737552814082"))
                .andExpect(jsonPath("$.data.nickname").value("测试用户"));
    }

    @Test
    void login_malformedCode_returns400ParamInvalidWithoutCallingService() throws Exception {
        // "12a456" 非空但不是 6 位数字：只触发 @Pattern
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"13900001234\", \"code\": \"12a456\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("验证码格式不正确，请输入6位数字"));

        verifyNoInteractions(authService);
    }

    @Test
    void login_missingCode_returns400ParamInvalidWithNotBlankMessage() throws Exception {
        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"13900001234\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SYS_PARAM_INVALID"))
                .andExpect(jsonPath("$.message").value("验证码不能为空"));

        verifyNoInteractions(authService);
    }

    // =====================================================================
    // POST /api/v1/auth/logout —— 退出登录（白名单端点，Controller 自行解析 Header）
    //
    //  这条路径很特殊：它故意留在白名单里（preHandle 不会预先解析 Token 并校验有效性），
    //  Controller 自己读 Authorization Header、自己决定要不要调用 Service——
    //  所以这部分手写的解析逻辑必须有自己的切片测试来兜底，不能假设它和拦截器行为一致。
    // =====================================================================

    @Test
    void logout_withBearerToken_extractsTokenAndDelegatesToService() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer bca75de27d924a97937313424269bad5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        verify(authService).logout(eq("bca75de27d924a97937313424269bad5"));
    }

    @Test
    void logout_withoutAuthorizationHeader_silentlySucceedsWithoutCallingService() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        verify(authService, never()).logout(org.mockito.ArgumentMatchers.anyString());
    }

    @Test
    void logout_withMalformedHeader_silentlySucceedsWithoutCallingService() throws Exception {
        // 没有 "Bearer " 前缀——按 Controller 里的判断条件，应当被忽略而不是报错
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "bca75de27d924a97937313424269bad5"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        verify(authService, never()).logout(org.mockito.ArgumentMatchers.anyString());
    }
}
