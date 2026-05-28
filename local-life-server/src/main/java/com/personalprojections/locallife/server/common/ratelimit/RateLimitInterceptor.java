package com.personalprojections.locallife.server.common.ratelimit;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Component;
import org.springframework.web.method.HandlerMethod;
import org.springframework.web.servlet.HandlerInterceptor;

import java.nio.charset.StandardCharsets;
import java.util.Collections;

/**
 * 基于 Redis 滑动窗口的接口限流拦截器。
 *
 * <h2>工作原理</h2>
 * <ol>
 *   <li>检查 Handler 方法上是否有 {@link RateLimit} 注解</li>
 *   <li>根据注解的 {@code keyType} 决定限流维度（userId 或 IP）</li>
 *   <li>构建 Redis Key：{@code rate_limit:{key}:{userId/IP}}</li>
 *   <li>执行 Lua 脚本（滑动窗口计数 + 判断是否超限）</li>
 *   <li>超限时直接返回 HTTP 429，不进入 Controller</li>
 * </ol>
 *
 * <h2>Lua 脚本保证原子性</h2>
 * <p>ZREMRANGEBYSCORE + ZCARD + ZADD 三步操作必须原子执行，
 * 否则并发时两个请求可能同时读到 count = limit - 1，都通过，实际超限。
 * Redis 单线程 + Lua 脚本保证原子性，无需分布式锁。
 *
 * <h2>HTTP 429 响应格式</h2>
 * <p>限流时不走 GlobalExceptionHandler（拦截器层面），
 * 直接写 JSON 响应体，格式与 {@link Result} 一致：
 * <pre>
 *   {
 *     "code": "AUTH_CODE_SEND_TOO_FREQUENT",
 *     "message": "请求过于频繁，请稍后再试",
 *     "timestamp": "2026-05-28T20:00:00+08:00"
 *   }
 * </pre>
 *
 * <h2>注册顺序</h2>
 * <p>此拦截器在 {@code WebMvcConfig} 中注册在 AuthInterceptor 之后，
 * 确保鉴权通过后才执行限流（避免非法请求也消耗限流计数）。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class RateLimitInterceptor implements HandlerInterceptor {

    private final StringRedisTemplate stringRedisTemplate;
    private final ObjectMapper objectMapper;

    /** Redis Key 前缀，与其他 Key 隔离 */
    private static final String KEY_PREFIX = "rate_limit:";

    /**
     * 滑动窗口限流 Lua 脚本（懒加载，只初始化一次）。
     * 使用 {@code DefaultRedisScript} 包装，Spring Data Redis 会自动 SHA1 缓存脚本，
     * 避免每次执行都传输完整脚本（EVALSHA vs EVAL）。
     */
    private DefaultRedisScript<Long> rateLimitScript;

    /**
     * 懒加载 Lua 脚本（避免在 Bean 构造时就读取文件，更健壮）。
     */
    private DefaultRedisScript<Long> getRateLimitScript() {
        if (rateLimitScript == null) {
            rateLimitScript = new DefaultRedisScript<>();
            rateLimitScript.setScriptSource(
                    new ResourceScriptSource(new ClassPathResource("lua/rate_limit.lua")));
            rateLimitScript.setResultType(Long.class);
        }
        return rateLimitScript;
    }

    /**
     * 拦截请求，检查限流注解并执行限流逻辑。
     *
     * @return {@code true} 放行；{@code false} 触发限流，直接返回 429
     */
    @Override
    public boolean preHandle(HttpServletRequest request, HttpServletResponse response,
                             Object handler) throws Exception {
        // 只处理 Controller 方法（跳过静态资源等）
        if (!(handler instanceof HandlerMethod handlerMethod)) {
            return true;
        }

        // 检查方法上是否有 @RateLimit 注解（先查方法，再查类）
        RateLimit rateLimit = handlerMethod.getMethodAnnotation(RateLimit.class);
        if (rateLimit == null) {
            return true;  // 无限流注解，直接放行
        }

        // 构建限流 Redis Key
        String limitKey = buildLimitKey(request, rateLimit);

        // 计算时间窗口（毫秒）
        long windowMs = rateLimit.unit().toMillis(rateLimit.window());
        long nowMs = System.currentTimeMillis();
        // Key 过期时间 = 窗口的 2 倍（确保窗口滑走后 Key 自动清理，单位：秒）
        long expireSeconds = rateLimit.unit().toSeconds(rateLimit.window()) * 2 + 1;

        // 执行 Lua 脚本：返回 0 = 放行，返回 1 = 超限
        Long result = stringRedisTemplate.execute(
                getRateLimitScript(),
                Collections.singletonList(limitKey),
                String.valueOf(nowMs),
                String.valueOf(windowMs),
                String.valueOf(rateLimit.limit()),
                String.valueOf(expireSeconds)
        );

        if (result != null && result == 1L) {
            // 触发限流：记录日志 + 返回 429
            log.warn("[RateLimit] 触发限流: key={}, limit={}/{} 次/{}{}",
                    limitKey, rateLimit.limit(), rateLimit.window(),
                    rateLimit.unit().name().toLowerCase());

            writeRateLimitResponse(response);
            return false;
        }

        return true;
    }

    /**
     * 构建 Redis 限流 Key。
     *
     * <p>格式：{@code rate_limit:{key}:{userId 或 IP}}
     * 示例：
     * <ul>
     *   <li>短信验证码：{@code rate_limit:sms:code:13800138000}（按手机号/userId）</li>
     *   <li>搜索接口：{@code rate_limit:search:shop:192.168.1.1}（按 IP）</li>
     * </ul>
     */
    private String buildLimitKey(HttpServletRequest request, RateLimit rateLimit) {
        String identifier;

        if (rateLimit.keyType() == RateLimit.KeyType.IP) {
            // 按 IP 限流（支持 X-Forwarded-For 代理场景）
            identifier = getClientIp(request);
        } else {
            // 按 userId 限流（已登录用户），未登录时降级为 IP
            // UserContext.get() 在白名单接口（未登录）中返回 null，安全降级为 IP 限流
            var loginUser = UserContext.get();
            identifier = loginUser != null ? String.valueOf(loginUser.getUserId()) : getClientIp(request);
        }

        return KEY_PREFIX + rateLimit.key() + ":" + identifier;
    }

    /**
     * 获取客户端真实 IP（兼容 Nginx 代理的 X-Forwarded-For 头）。
     *
     * <p>Nginx 反向代理场景：客户端 IP 在 X-Forwarded-For 第一个值中，
     * 而不是 {@code request.getRemoteAddr()}（那是 Nginx 的 IP）。
     */
    private String getClientIp(HttpServletRequest request) {
        String xff = request.getHeader("X-Forwarded-For");
        if (xff != null && !xff.isBlank() && !"unknown".equalsIgnoreCase(xff)) {
            // X-Forwarded-For 可能是逗号分隔的多个 IP（代理链），取第一个
            return xff.split(",")[0].trim();
        }
        String xri = request.getHeader("X-Real-IP");
        if (xri != null && !xri.isBlank()) {
            return xri.trim();
        }
        return request.getRemoteAddr();
    }

    /**
     * 写入 HTTP 429 限流响应。
     *
     * <p>响应体格式与 {@link Result} 一致（JSON），确保前端统一处理。
     */
    private void writeRateLimitResponse(HttpServletResponse response) throws Exception {
        response.setStatus(HttpStatus.TOO_MANY_REQUESTS.value());  // 429
        response.setContentType(MediaType.APPLICATION_JSON_VALUE);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());

        Result<Void> body = Result.fail(ErrorCode.AUTH_CODE_SEND_TOO_FREQUENT, "请求过于频繁，请稍后再试");
        response.getWriter().write(objectMapper.writeValueAsString(body));
    }
}
