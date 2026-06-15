package com.personalprojections.locallife.server.config;

import com.personalprojections.locallife.server.common.interceptor.AuthInterceptor;
import com.personalprojections.locallife.server.common.ratelimit.RateLimitInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置类，注册拦截器。
 *
 * <h2>拦截器注册原理</h2>
 * <p>实现 {@link WebMvcConfigurer} 接口并覆盖 {@code addInterceptors} 方法，
 * Spring MVC 启动时会调用此方法，将 {@link AuthInterceptor} 加入拦截器链。
 * 所有进入 DispatcherServlet 的请求都会经过注册的拦截器。
 *
 * <h2>公开端点白名单在哪里？刻意不在这里</h2>
 * <p>注意下面 {@code addInterceptors} 对 {@link AuthInterceptor} 只调用了
 * {@code addPathPatterns("/**")}，<strong>没有</strong> {@code excludePathPatterns}。
 * 这是有意为之：公开端点（登录、门店详情、笔记详情等访客可访问的接口）的判断
 * 收敛到了 {@link AuthInterceptor} 内部，按「HTTP 方法 + 路径」匹配
 * （详见其 {@code PUBLIC_ENDPOINTS} 字段的注释）。
 *
 * <p>原因：{@code excludePathPatterns} 只能按路径排除，无法区分 HTTP 方法，
 * 而本项目大量接口同路径下「读公开、写需登录」（如
 * {@code GET /api/v1/shops/{id}} 公开，但 {@code PUT /api/v1/shops/{id}} 需要商家登录）。
 * 旧版本在此处用纯路径排除（如 {@code "/api/v1/shops/*"}），结果把同路径的
 * PUT/POST/DELETE 也一并放过了拦截器——UserContext 从未被填充，Service 层
 * {@code UserContext.get().getUserId()} 直接抛 NullPointerException
 * （一个「未登录可绕过鉴权、已登录反而 500」的真实 Bug，已修复）。
 * 只有在拦截器内部才能拿到 HTTP 方法，因此<strong>不要在此处重新引入
 * excludePathPatterns</strong>，新增公开端点请去修改
 * {@link AuthInterceptor#PUBLIC_ENDPOINTS}。
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    // 注入鉴权拦截器 Bean（AuthInterceptor 标注了 @Component，由 Spring 管理）
    private final AuthInterceptor authInterceptor;

    /**
     * 限流拦截器：解析 {@link com.personalprojections.locallife.server.common.ratelimit.RateLimit}
     * 注解，执行 Redis 滑动窗口限流。
     * 注册在 AuthInterceptor 之后，鉴权通过才做限流（避免非法请求消耗计数）。
     */
    private final RateLimitInterceptor rateLimitInterceptor;

    /**
     * 注册拦截器。
     *
     * <p>拦截器执行顺序：多个拦截器按 addInterceptor 的顺序执行。
     * 如果后续新增如「接口限流拦截器」，应在 AuthInterceptor 之后注册，
     * 这样限流逻辑只对已通过鉴权的请求生效。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        // 故意不写 excludePathPatterns——公开端点判断收敛在 AuthInterceptor 内部，
        // 按「HTTP 方法 + 路径」精确匹配，原因见本类的注释
        registry.addInterceptor(authInterceptor)
                .addPathPatterns("/**");

        // 限流拦截器：在 AuthInterceptor 之后注册，拦截所有路径
        // 实际生效范围由 @RateLimit 注解决定，无注解的方法直接跳过
        registry.addInterceptor(rateLimitInterceptor)
                .addPathPatterns("/**");
    }
}
