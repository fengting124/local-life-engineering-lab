package com.personalprojections.locallife.server.config;

import com.personalprojections.locallife.server.common.interceptor.AuthInterceptor;
import lombok.RequiredArgsConstructor;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.servlet.config.annotation.InterceptorRegistry;
import org.springframework.web.servlet.config.annotation.WebMvcConfigurer;

/**
 * Spring MVC 配置类，注册拦截器并配置白名单路径。
 *
 * <h2>拦截器注册原理</h2>
 * <p>实现 {@link WebMvcConfigurer} 接口并覆盖 {@code addInterceptors} 方法，
 * Spring MVC 启动时会调用此方法，将 {@link AuthInterceptor} 加入拦截器链。
 * 所有进入 DispatcherServlet 的请求都会经过注册的拦截器。
 *
 * <h2>白名单路径说明</h2>
 * <p>{@code excludePathPatterns} 配置的路径不经过 {@link AuthInterceptor}，
 * 这些是不需要登录就能访问的公开接口：
 * <ul>
 *   <li>登录相关：发验证码、登录（登录前显然没有 Token）</li>
 *   <li>公开内容：门店详情、笔记详情（访客可以浏览）</li>
 *   <li>支付回调：由支付渠道服务器主动调用，没有用户 Token，用渠道验签代替鉴权</li>
 *   <li>运维端点：Actuator health 检查，负载均衡器需要访问</li>
 * </ul>
 *
 * <h2>路径匹配规则</h2>
 * <ul>
 *   <li>{@code /api/v1/auth/**}  - 匹配 /api/v1/auth/ 下所有路径（含子路径）</li>
 *   <li>{@code /api/v1/shops/*} - 匹配 /api/v1/shops/{shopId}（单层通配）</li>
 *   <li>{@code /**}             - 匹配所有路径（慎用）</li>
 * </ul>
 */
@Configuration
@RequiredArgsConstructor
public class WebMvcConfig implements WebMvcConfigurer {

    // 注入鉴权拦截器 Bean（AuthInterceptor 标注了 @Component，由 Spring 管理）
    private final AuthInterceptor authInterceptor;

    /**
     * 注册拦截器并配置白名单。
     *
     * <p>拦截器执行顺序：多个拦截器按 addInterceptor 的顺序执行。
     * 如果后续新增如「接口限流拦截器」，应在 AuthInterceptor 之后注册，
     * 这样限流逻辑只对已通过鉴权的请求生效。
     *
     * @param registry 拦截器注册表
     */
    @Override
    public void addInterceptors(InterceptorRegistry registry) {
        registry.addInterceptor(authInterceptor)
                // 拦截所有路径
                .addPathPatterns("/**")
                // 白名单：以下路径跳过鉴权
                .excludePathPatterns(
                        // 登录相关（登录前没有 Token）
                        "/api/v1/auth/**",

                        // 公开内容（访客可浏览，不强制登录）
                        "/api/v1/shops",              // 搜索门店列表
                        "/api/v1/shops/*",            // 门店详情（* 匹配单层路径变量）
                        "/api/v1/shops/*/posts",      // 门店下的笔记列表
                        "/api/v1/posts/*",            // 笔记详情（GET /posts/{postId}）
                        "/api/v1/posts/*/comments",   // 笔记评论列表（GET /posts/{postId}/comments）
                        "/api/v1/coupons/templates",  // 可抢券列表

                        // 支付回调（支付渠道服务器调用，用渠道验签代替 Token 鉴权）
                        "/api/v1/payments/callback",

                        // Spring Boot Actuator 运维端点（健康检查）
                        "/actuator/**",

                        // 静态资源（如果后续有 Swagger UI 等）
                        "/swagger-ui/**",
                        "/v3/api-docs/**"
                );
    }
}
