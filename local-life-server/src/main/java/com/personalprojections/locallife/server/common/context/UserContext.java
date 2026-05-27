package com.personalprojections.locallife.server.common.context;

/**
 * 用户上下文，基于 ThreadLocal 实现请求级别的用户信息传递。
 *
 * <h2>工作原理</h2>
 * <p>HTTP 请求在 Tomcat 线程池中由一个线程独占处理（从进入 Filter/Interceptor 到返回响应）。
 * ThreadLocal 为每个线程维护一份独立的数据副本，线程之间互不干扰，
 * 因此可以安全地在同一线程的 Interceptor → Controller → Service 之间传递登录用户信息，
 * 而不需要在每个方法签名里传 userId 参数。
 *
 * <h2>生命周期</h2>
 * <pre>
 *   请求进入 → AuthInterceptor.preHandle() 写入用户信息
 *      ↓
 *   Controller / Service 中通过 UserContext.get() 读取
 *      ↓
 *   请求结束 → AuthInterceptor.afterCompletion() 清除（必须！）
 * </pre>
 *
 * <h2>为什么必须在 afterCompletion 清除</h2>
 * <p>Tomcat 线程池会复用线程。如果不清除，线程处理下一个请求时，
 * ThreadLocal 中还残留着上一个请求的用户信息，导致数据错乱（严重安全漏洞）。
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // Service 层获取当前用户 ID（已保证登录，不会为 null）
 *   Long userId = UserContext.getUserId();
 *
 *   // Service 层获取完整摘要
 *   LoginUserDTO user = UserContext.get();
 * }</pre>
 */
public class UserContext {

    /**
     * ThreadLocal 存储当前线程的登录用户信息。
     * 使用 static，因为整个应用共享同一个 ThreadLocal 实例，
     * 但每个线程的数据是隔离的。
     */
    private static final ThreadLocal<LoginUserDTO> USER_HOLDER = new ThreadLocal<>();

    /** 工具类，禁止实例化。 */
    private UserContext() {}

    /**
     * 存入当前登录用户信息（由 AuthInterceptor 在请求进入时调用）。
     *
     * @param loginUser 从 Redis 读取的用户摘要，不能为 null
     */
    public static void set(LoginUserDTO loginUser) {
        USER_HOLDER.set(loginUser);
    }

    /**
     * 获取当前登录用户完整摘要。
     *
     * <p>注意：只有经过鉴权拦截器的请求才有值。
     * 如果在未登录的接口（白名单）中调用此方法，返回 null。
     * 建议通过 {@link #getUserId()} 直接获取 ID，更常用。
     *
     * @return 登录用户摘要，未登录时为 null
     */
    public static LoginUserDTO get() {
        return USER_HOLDER.get();
    }

    /**
     * 获取当前登录用户的 ID（最常用的方法）。
     *
     * <p>在需要鉴权的接口中，AuthInterceptor 已保证此值不为 null。
     * 如果因误用在白名单接口中调用，会抛出 NullPointerException，
     * 这是一个开发期的 bug，应当在 code review 中发现。
     *
     * @return 当前登录用户 ID
     */
    public static Long getUserId() {
        return get().getUserId();
    }

    /**
     * 清除当前线程的用户信息（由 AuthInterceptor.afterCompletion 调用）。
     *
     * <p><strong>必须调用</strong>，否则线程池复用线程时会出现用户信息泄露。
     */
    public static void clear() {
        USER_HOLDER.remove();
    }
}
