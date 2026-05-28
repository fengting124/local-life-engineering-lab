package com.personalprojections.locallife.server.common.ratelimit;

import java.lang.annotation.*;
import java.util.concurrent.TimeUnit;

/**
 * 接口限流注解，基于 Redis 滑动窗口实现。
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // 同一用户 60 秒内最多发 1 次验证码
 *   @RateLimit(key = "sms:code", limit = 1, window = 60, unit = TimeUnit.SECONDS)
 *   @PostMapping("/api/v1/auth/code")
 *   public Result<Void> sendCode(...) { ... }
 *
 *   // 同一 IP 1 分钟内最多搜索 30 次
 *   @RateLimit(key = "search:shop", limit = 30, window = 60, keyType = RateLimit.KeyType.IP)
 *   @GetMapping("/api/v1/search/shops")
 *   public Result<PageResult<ShopSearchVO>> searchShops(...) { ... }
 * }</pre>
 *
 * <h2>限流策略说明</h2>
 * <p>使用 Redis ZSET 实现滑动窗口限流：
 * <ol>
 *   <li>Key：{@code rate_limit:{key}:{userId/IP}}</li>
 *   <li>ZADD Key {nowMs} {nowMs}（以时间戳为 score 和 member 写入当前请求）</li>
 *   <li>ZREMRANGEBYSCORE Key 0 {nowMs - windowMs}（移除窗口外的旧请求）</li>
 *   <li>ZCARD Key → 当前窗口内的请求数</li>
 *   <li>若 count > limit → 触发限流，返回 HTTP 429</li>
 * </ol>
 *
 * <p>整个逻辑用 Lua 脚本原子执行，避免并发下的计数误差。
 *
 * <h2>滑动窗口 vs 固定窗口</h2>
 * <ul>
 *   <li>固定窗口：每分钟 00 秒重置，边界处可能瞬间通过 2× limit 的流量</li>
 *   <li>滑动窗口：始终看「过去 N 秒」内的请求数，无边界突刺问题</li>
 * </ul>
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface RateLimit {

    /**
     * 限流业务 Key 前缀（标识哪个接口/场景），全局唯一。
     * 最终 Redis Key = "rate_limit:{key}:{userId 或 IP}"
     */
    String key();

    /**
     * 时间窗口内最大请求次数。
     */
    int limit();

    /**
     * 时间窗口大小，与 {@link #unit()} 配合使用。
     */
    long window();

    /**
     * 时间窗口单位，默认秒。
     */
    TimeUnit unit() default TimeUnit.SECONDS;

    /**
     * 限流维度：按用户 ID 还是按 IP 地址。
     * <ul>
     *   <li>{@code USER}（默认）：已登录用户按 userId 限流，未登录按 IP 兜底</li>
     *   <li>{@code IP}：始终按客户端 IP 限流（适合公开接口）</li>
     * </ul>
     */
    KeyType keyType() default KeyType.USER;

    /**
     * 限流 Key 的维度枚举。
     */
    enum KeyType {
        /** 按用户 ID 限流（需要登录态） */
        USER,
        /** 按客户端 IP 限流（适合公开接口或匿名用户） */
        IP
    }
}
