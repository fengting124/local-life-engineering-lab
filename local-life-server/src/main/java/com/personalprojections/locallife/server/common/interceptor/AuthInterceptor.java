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
import org.springframework.util.AntPathMatcher;
import org.springframework.util.StringUtils;
import org.springframework.web.servlet.HandlerInterceptor;

import java.util.List;
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

    private static final AntPathMatcher PATH_MATCHER = new AntPathMatcher();

    /**
     * 公开端点白名单：按 (HTTP 方法, 路径模式) 匹配，命中则跳过鉴权直接放行。
     *
     * <h3>为什么白名单要按「方法 + 路径」匹配，而不是像以前那样在
     * {@link com.personalprojections.locallife.server.config.WebMvcConfig} 里用
     * {@code excludePathPatterns} 按路径排除？</h3>
     * <p>excludePathPatterns 只能按路径排除，无法区分 HTTP 方法——而本项目大量接口
     * 是典型 RESTful 设计：同一资源路径下「读」公开、「增删改」需要登录。例如：
     * <ul>
     *   <li>{@code GET /api/v1/shops/{shopId}} 公开（门店详情），
     *       但 {@code PUT /api/v1/shops/{shopId}} 需要商家登录（更新门店）</li>
     *   <li>{@code GET /api/v1/posts/{postId}} 公开（笔记详情），
     *       但 {@code DELETE /api/v1/posts/{postId}} 需要作者登录（删除笔记）</li>
     *   <li>{@code GET .../comments} 公开（评论列表），
     *       但 {@code POST .../comments} 需要登录（发评论）</li>
     * </ul>
     * 旧版本在 WebMvcConfig 用纯路径排除（如 {@code "/api/v1/shops/*"}），
     * 结果把同路径的 PUT/POST/DELETE 也一并放过了拦截器——UserContext 从未被写入，
     * Service 层 {@code UserContext.get().getUserId()} 直接抛 NullPointerException
     * （历史 Bug：对未登录调用方表现为「鉴权被静默绕过」，对已登录调用方表现为
     * 「带着合法 Token 也 500」）。把白名单收敛到此处、按 (方法, 路径) 精确匹配，
     * 才能表达「只放行 GET，不放行同路径写操作」这个真实意图。
     *
     * <p>{@code method} 为 {@code null} 表示不限方法（整条路径都公开，如登录接口、内部接口）。
     */
    private static final List<PublicEndpoint> PUBLIC_ENDPOINTS = List.of(
            // 登录相关：登录前显然没有 Token，不限方法。
            // （/logout 语义上需要登录态，但它故意留在白名单里——由 Controller 自行解析
            //  Authorization Header 调用 authService.logout(token)，见 AuthController#logout
            //  的注释「/logout 在白名单，拦截器不会填充 UserContext」，是有意为之、非疏漏）
            PublicEndpoint.anyMethod("/api/v1/auth/**"),

            // 公开内容：仅放行 GET，同路径的写操作仍需登录
            PublicEndpoint.of("GET", "/api/v1/shops"),
            PublicEndpoint.of("GET", "/api/v1/shops/*"),
            PublicEndpoint.of("GET", "/api/v1/shops/*/posts"),
            PublicEndpoint.of("GET", "/api/v1/posts/*"),
            PublicEndpoint.of("GET", "/api/v1/posts/*/comments"),

            // 可抢券列表：当前只有 GET，语义上就是公开列表
            PublicEndpoint.anyMethod("/api/v1/coupons/templates"),

            // 支付回调 / Mock 支付触发：渠道方调用，用验签代替鉴权（mock-pay 生产环境通过 @Profile 关闭）
            PublicEndpoint.anyMethod("/api/v1/payments/callback"),
            PublicEndpoint.anyMethod("/api/v1/payments/mock-pay"),

            // 全文搜索：游客可搜索
            PublicEndpoint.anyMethod("/api/v1/search/shops"),
            PublicEndpoint.anyMethod("/api/v1/search/posts"),

            // 内部服务接口：仅 Copilot MCP Server 调用，走 X-Internal-Key 验证，不用 JWT
            PublicEndpoint.anyMethod("/internal/**"),

            // 运维 / 文档
            PublicEndpoint.anyMethod("/actuator/**"),
            PublicEndpoint.anyMethod("/swagger-ui/**"),
            PublicEndpoint.anyMethod("/v3/api-docs/**")
    );

    /**
     * 白名单条目：HTTP 方法 + Ant 风格路径模式。
     *
     * @param method      限定的 HTTP 方法（大小写不敏感）；{@code null} 表示不限方法
     * @param pathPattern Ant 风格路径模式，如 {@code "/api/v1/shops/*"}
     */
    private record PublicEndpoint(String method, String pathPattern) {

        static PublicEndpoint of(String method, String pathPattern) {
            return new PublicEndpoint(method, pathPattern);
        }

        static PublicEndpoint anyMethod(String pathPattern) {
            return new PublicEndpoint(null, pathPattern);
        }

        boolean matches(String requestMethod, String requestPath) {
            return (method == null || method.equalsIgnoreCase(requestMethod))
                    && PATH_MATCHER.match(pathPattern, requestPath);
        }
    }

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
        // 0. 命中公开端点白名单 → 直接放行，不生成 requestId、不查 Token、不写 UserContext
        //    （与旧版 excludePathPatterns 的可观测行为保持一致：白名单请求的日志中不会
        //    出现本拦截器的 requestId，只是判断条件从「路径」改成了「方法 + 路径」，
        //    详见 PUBLIC_ENDPOINTS 的注释——这正是本次修复的核心）
        if (isPublicEndpoint(request)) {
            return true;
        }

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
     * 判断当前请求是否命中 {@link #PUBLIC_ENDPOINTS} 白名单（按 HTTP 方法 + 路径精确匹配）。
     *
     * @param request HTTP 请求
     * @return true = 公开端点，无需鉴权
     */
    private boolean isPublicEndpoint(HttpServletRequest request) {
        String method = request.getMethod();
        String path = request.getRequestURI();
        return PUBLIC_ENDPOINTS.stream().anyMatch(endpoint -> endpoint.matches(method, path));
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
