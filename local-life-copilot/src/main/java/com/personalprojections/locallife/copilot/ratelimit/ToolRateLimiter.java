package com.personalprojections.locallife.copilot.ratelimit;

import com.personalprojections.locallife.copilot.rbac.RbacContext;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

import java.util.concurrent.TimeUnit;

/**
 * 工具级别限流器（Redis 计数器）。
 *
 * <h2>设计文档 11.2 要求</h2>
 * <pre>
 *   工具调用：100 次 / 分钟 / 工具（防止 Agent 失控循环）
 *   用户请求：10 次 / 分钟（防止用户刷接口）
 * </pre>
 *
 * <h2>为什么工具也需要限流</h2>
 * <p>Agent 在某些异常状态下可能陷入无限循环（如工具返回错误后反复重试）。
 * 工具级限流是最后一道防线，保护 LocalLife 主服务不被 Copilot 意外打满。
 * 与 Agent 层的「最大步数限制」互补：
 * <ul>
 *   <li>最大步数：防止单次会话循环过久</li>
 *   <li>工具限流：防止多个并发会话集中请求同一工具</li>
 * </ul>
 *
 * <h2>Redis Key 设计</h2>
 * <pre>
 *   mcp:tool_rate:{toolName}:{userId}    → 该用户在该工具的调用计数（TTL 60s）
 *   mcp:user_rate:{userId}               → 该用户总请求计数（TTL 60s）
 * </pre>
 *
 * <h2>限流结果</h2>
 * <p>超限时 McpController 返回 JSON-RPC 错误（-32603 internal_error），
 * Agent 会把它当作工具错误处理，而不是崩溃。
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class ToolRateLimiter {

    private final StringRedisTemplate redisTemplate;

    @Value("${mcp.server.tool-rate-limit-per-minute:100}")
    private int toolLimitPerMinute;

    /** 用户级别：每分钟最多 30 次工具调用总量（防止单用户高频请求） */
    private static final int USER_LIMIT_PER_MINUTE = 30;

    private static final String TOOL_KEY_PREFIX = "mcp:tool_rate:";
    private static final String USER_KEY_PREFIX  = "mcp:user_rate:";

    /**
     * 检查是否触发限流。
     *
     * <p>先检查用户级别限流，再检查工具级别限流，两个维度都要通过才放行。
     *
     * @param toolName 工具名称
     * @return {@code true} = 放行；{@code false} = 触发限流
     */
    public boolean isAllowed(String toolName) {
        RbacContext ctx = RbacContext.get();
        String userId = ctx != null ? String.valueOf(ctx.getUserId()) : "anonymous";

        // ---- 检查用户级别总量限流 ----
        String userKey = USER_KEY_PREFIX + userId;
        Long userCount = increment(userKey, 60);
        if (userCount != null && userCount > USER_LIMIT_PER_MINUTE) {
            log.warn("[ToolRateLimiter] 用户级别限流触发: userId={}, count={}/{}",
                    userId, userCount, USER_LIMIT_PER_MINUTE);
            return false;
        }

        // ---- 检查工具级别限流 ----
        String toolKey = TOOL_KEY_PREFIX + toolName + ":" + userId;
        Long toolCount = increment(toolKey, 60);
        if (toolCount != null && toolCount > toolLimitPerMinute) {
            log.warn("[ToolRateLimiter] 工具级别限流触发: tool={}, userId={}, count={}/{}",
                    toolName, userId, toolCount, toolLimitPerMinute);
            return false;
        }

        return true;
    }

    /**
     * Redis INCR + EXPIRE（原子操作：INCR 后第一次 SET 过期时间）。
     *
     * <p>使用 INCR 而不是 ZSet 的原因：
     * 工具调用限流精度要求低（分钟级窗口），简单计数足够，比滑动窗口开销小。
     * 代价：固定窗口在窗口边界有突刺（连续两个窗口可以 200 次），可接受。
     */
    private Long increment(String key, int ttlSeconds) {
        Long count = redisTemplate.opsForValue().increment(key);
        if (count != null && count == 1L) {
            // 第一次 INCR，设置过期时间（之后的 INCR 不需要再 EXPIRE）
            redisTemplate.expire(key, ttlSeconds, TimeUnit.SECONDS);
        }
        return count;
    }
}
