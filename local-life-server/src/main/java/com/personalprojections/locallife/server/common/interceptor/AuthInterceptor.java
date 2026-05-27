package com.personalprojections.locallife.server.common.interceptor;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * 鉴权拦截器，拦截所有需要登录的请求。
 *
 * <h2>执行时机（Spring MVC 请求处理流程）</h2>
 * <pre>
 *   HTTP 请求
 *      ↓
 *   DispatcherServlet
 *      ↓
 *   HandlerInterceptor.preHandle()   ← 本类在这里执行鉴权
 *      ↓
 *   Controller 方法执行
 *      ↓
 *   HandlerInterceptor.postHandle()
 *      ↓
 *   视图渲染（REST 项目跳过）
 *      ↓
 *   HandlerInterceptor.afterCompletion()  ← 本类在这里清除 ThreadLocal
 * </pre>
 *
 * <h2>鉴权逻辑</h2>
 * <ol>
 *   <li>从 Header 中读取 {@code Authorization: Bearer {token}}</li>
 *   <li>到 Redis 查询 {@code login:token:{token}} 是否存在</li>
 *   <li>反序列化为 {@link LoginUserDTO}，检查账号状态</li>
 *   <li>写入 {@link UserContext}（ThreadLocal），供后续业务代码使用</li>
 *   <li>刷新 Token TTL（滑动过期：只要用户活跃，就不需要重新登录）</li>
 * </ol>
 *
 * <h2>白名单说明</h2>
 * <p>白名单路径（如登录接口）通过 {@link WebMvcConfig#addInterceptors} 配置排除，
 * 不会进入本拦截器，因此本类无需判断路径。
 *
 * <h2>RequestId 追踪</h2>
 * <p>每次请求在 preHandle 时生成一个 UUID 作为 requestId，
 * 写入 MDC（Mapped Diagnostic Context），使得该请求的所有日志都带上这个 ID，
 * 方便在日志系统中按请求过滤。
 */
@Slf4j
@Component
@RequiredArgsConstructor  // Lombok：为所有 final 字段生成构造函数，实现构造器注入
public class AuthInterceptor implements HandlerInterceptor {

    /**
     * Authorization Header 的名称，固定为 "Authorization"。
     */
    private static final String AUTHORIZATION_HEADER = "Authorization";

    /**
     * Bearer Token 的前缀，格式为 "Bearer {token}"。
     */
    private static final String BEARER_PREFIX = "Bearer ";

    /**
     * Redis 中 Token 的 Key 前缀，完整 Key 为 "login:token:{token}"。
     */
    private static final String TOKEN_KEY_PREFIX = "login:token:";

    /**
     * Token 的 TTL（天），每次请求后刷新（滑动过期）。
     */
    private static final long TOKEN_TTL_DAYS = 7L;

    /**
     * MDC 中存放 requestId 的键名，与 application.yml 中日志格式的 %X{requestId} 对应。
     */
    private static final String REQUEST_ID_KEY = "requestId";

    // StringRedisTemplate：存 Token 时 Value 是 JSON 字符串，用 String 模板读取
    private final StringRedisTemplate stringRedisTemplate;

    // Jackson ObjectMapper：将 Redis 中的 JSON 字符串反序列化为 LoginUserDTO
    private final ObjectMapper objectMapper;

    /**
     * 请求进入 Controller 前执行：鉴权 + 写入用户上下文 + 生成 requestId。
     *
     * <p>返回 true 表示放行，继续执行后续拦截器和 Controller。
     * 鉴权失败时直接抛出 {@link BizException}，由 {@link com.personalprojections.locallife.server.common.exception.GlobalExceptionHandler} 处理，
     * 不需要在这里手动写响应。
     *
     * @param request  HTTP 请求
     * @param response HTTP 响应
     * @param handler  目标 Controller 方法
     * @return true = 放行；false = 拦截（本项目通过抛异常拦截，不用 false）
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) {
        // 1. 生成本次请求的唯一 ID，写入 MDC，使该请求所有日志都携带此 ID
        String requestId = UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        MDC.put(REQUEST_ID_KEY, requestId);

        log.debug("请求进入 [{} {}]", request.getMethod(), request.getRequestURI());

        // 2. 从 Header 中解析 Token
        String token = extractToken(request);

        // 3. 从 Redis 查询 Token 对应的用户信息
        String userJson = stringRedisTemplate.opsForValue().get(TOKEN_KEY_PREFIX + token);

        // 4. Redis 中不存在该 Token（已过期或无效）
        if (!StringUtils.hasText(userJson)) {
            throw new BizException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }

        // 5. 反序列化为 LoginUserDTO
        LoginUserDTO loginUser;
        try {
            loginUser = objectMapper.readValue(userJson, LoginUserDTO.class);
        } catch (Exception e) {
            // JSON 反序列化失败，说明 Redis 中的数据损坏，按 Token 失效处理
            log.warn("Token 对应的用户数据反序列化失败，token 截断: {}...", token.substring(0, 8));
            throw new BizException(ErrorCode.AUTH_TOKEN_EXPIRED);
        }

        // 6. 检查账号状态（账号被禁用后，下次请求立即生效，不需要等 TTL）
        if ("DISABLED".equals(loginUser.getStatus())) {
            throw new BizException(ErrorCode.USER_ACCOUNT_DISABLED);
        }

        // 7. 写入 UserContext（ThreadLocal），后续 Service 层通过 UserContext.getUserId() 取用
        UserContext.set(loginUser);

        // 8. 刷新 Token TTL（滑动过期）：只要用户在 7 天内有请求，就不需要重新登录
        //    如果不刷新，用户每 7 天就会被强制下线，体验较差
        stringRedisTemplate.expire(TOKEN_KEY_PREFIX + token, TOKEN_TTL_DAYS, TimeUnit.DAYS);

        log.debug("鉴权通过，用户 ID: {}", loginUser.getUserId());
        return true;
    }

    /**
     * 请求处理完毕（Controller 执行后、响应返回前）执行：清除 ThreadLocal 和 MDC。
     *
     * <p><strong>必须在此处清除 ThreadLocal 和 MDC。</strong>
     * Tomcat 线程池会复用线程，不清除会导致下一个请求读到上一个请求的用户信息，
     * 这是严重的安全漏洞。
     *
     * <p>注意：afterCompletion 即使 Controller 抛出异常也会执行（类似 finally），
     * 因此这里是清除的最佳位置。
     *
     * @param request   HTTP 请求
     * @param response  HTTP 响应
     * @param handler   目标 Controller 方法
     * @param ex        Controller 执行过程中抛出的异常（已被异常处理器处理），可为 null
     */
    @Override
    public void afterCompletion(HttpServletRequest request, HttpServletResponse response,
                                Object handler, Exception ex) {
        // 清除 ThreadLocal，防止线程复用时数据污染
        UserContext.clear();
        // 清除 MDC，防止 requestId 污染下一个请求的日志
        MDC.remove(REQUEST_ID_KEY);
        log.debug("请求结束 [{} {}]，已清除上下文", request.getMethod(), request.getRequestURI());
    }

    /**
     * 从 HTTP Header 中提取 Token。
     *
     * <p>Header 格式：{@code Authorization: Bearer eyJhbGci...}
     * <ol>
     *   <li>Header 缺失或为空 → 抛 AUTH_TOKEN_MISSING</li>
     *   <li>Header 格式不是 "Bearer " 开头 → 抛 AUTH_TOKEN_MISSING</li>
     *   <li>去掉 "Bearer " 前缀后得到纯 Token 字符串</li>
     * </ol>
     *
     * @param request HTTP 请求
     * @return 纯 Token 字符串（去掉 "Bearer " 前缀）
     * @throws BizException AUTH_TOKEN_MISSING，当 Header 缺失或格式错误
     */
    private String extractToken(HttpServletRequest request) {
        String authHeader = request.getHeader(AUTHORIZATION_HEADER);

        // Header 缺失或为空
        if (!StringUtils.hasText(authHeader)) {
            throw new BizException(ErrorCode.AUTH_TOKEN_MISSING);
        }

        // Header 格式错误（不是 "Bearer " 开头）
        if (!authHeader.startsWith(BEARER_PREFIX)) {
            throw new BizException(ErrorCode.AUTH_TOKEN_MISSING);
        }

        // 截取 "Bearer " 之后的部分，得到纯 Token
        String token = authHeader.substring(BEARER_PREFIX.length()).trim();

        if (!StringUtils.hasText(token)) {
            throw new BizException(ErrorCode.AUTH_TOKEN_MISSING);
        }

        return token;
    }
}
