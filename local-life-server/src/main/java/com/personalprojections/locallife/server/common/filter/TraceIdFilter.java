package com.personalprojections.locallife.server.common.filter;

import io.micrometer.tracing.Span;
import io.micrometer.tracing.Tracer;
import jakarta.servlet.*;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * 链路追踪 ID 注入过滤器。
 *
 * <h2>解决的问题</h2>
 * <p>当一个请求经过多个服务、多个异步步骤时，我们需要能把所有相关日志串起来。
 * 关键工具：{@code traceId}（全链路唯一 ID）和 {@code spanId}（单个操作唯一 ID）。
 *
 * <h2>本过滤器做了三件事</h2>
 * <ol>
 *   <li><b>从 Micrometer Tracer 读取 traceId 和 spanId</b>：
 *       每个请求进来时，Brave 已经生成了 Span，通过 {@link Tracer#currentSpan()} 读取</li>
 *   <li><b>写入 MDC（Mapped Diagnostic Context）</b>：
 *       MDC 是 SLF4J 提供的线程本地存储，日志格式里的 {@code %X{traceId}} 就从这里读</li>
 *   <li><b>写入响应头</b>：
 *       把 traceId 放进响应头 {@code X-Trace-Id}，方便前端在错误时上报，运维按 traceId 快速定位</li>
 * </ol>
 *
 * <h2>MDC 的工作原理</h2>
 * <pre>
 *   请求进入过滤器
 *        ↓
 *   MDC.put("traceId", "abc123")
 *        ↓
 *   日志输出：[abc123/span001] OrderService - 创建订单 orderId=999
 *        ↓
 *   请求结束
 *        ↓
 *   MDC.remove("traceId")  ← 必须清理！线程池复用时否则 MDC 值会串
 * </pre>
 *
 * <h2>与 AuthInterceptor 的区别</h2>
 * <p>Filter（Servlet 层）比 Interceptor（Spring MVC 层）先执行，
 * 所以 traceId 在 DispatcherServlet 处理前就已写入 MDC，
 * 后续所有日志（含 Spring Security、MyBatis 等框架日志）都会带上 traceId。
 *
 * <h2>@Order(1) 说明</h2>
 * <p>多个 Filter 的执行顺序由 {@code @Order} 决定，数值越小越先执行。
 * TraceIdFilter 应该最先执行，确保所有后续日志都有 traceId。
 */
@Slf4j
@Component
@Order(1)
@RequiredArgsConstructor
public class TraceIdFilter implements Filter {

    /**
     * Micrometer Tracing 的核心 API，提供当前 Span 的访问能力。
     * Spring Boot Actuator 自动注册此 Bean（通过 micrometer-tracing-bridge-brave）。
     */
    private final Tracer tracer;

    /** 写入 MDC 的 Key 名称（与 application.yml 日志格式中的 %X{traceId} 对应）。 */
    private static final String MDC_TRACE_ID = "traceId";

    /** 写入 MDC 的 SpanId Key（日志格式 %X{spanId}）。 */
    private static final String MDC_SPAN_ID = "spanId";

    /** 写入响应头的 Key，前端可读取用于问题上报。 */
    private static final String RESPONSE_HEADER_TRACE_ID = "X-Trace-Id";

    /**
     * 过滤器核心逻辑：
     * 1. 读取 Brave 生成的 traceId/spanId
     * 2. 写入 MDC（影响此线程后续所有日志）
     * 3. 写入响应头（方便前端问题上报）
     * 4. 放行请求
     * 5. 无论成功失败，finally 清理 MDC（防止线程池复用时 MDC 值污染下一个请求）
     */
    @Override
    public void doFilter(ServletRequest servletRequest, ServletResponse servletResponse,
                         FilterChain chain) throws IOException, ServletException {
        HttpServletRequest request = (HttpServletRequest) servletRequest;
        HttpServletResponse response = (HttpServletResponse) servletResponse;

        // 读取当前 Span（Brave 在 Filter 链进入时已自动创建）
        Span currentSpan = tracer.currentSpan();
        String traceId = "";
        String spanId = "";

        if (currentSpan != null) {
            traceId = currentSpan.context().traceId();
            spanId = currentSpan.context().spanId();
        }

        try {
            // Step 1: 写入 MDC，此线程后续所有 log.info/debug/error 都会携带 traceId
            MDC.put(MDC_TRACE_ID, traceId);
            MDC.put(MDC_SPAN_ID, spanId);

            // Step 2: 写入响应头，前端错误上报时带上此值，运维可以快速定位
            // 示例：curl -i /api/v1/orders → 响应头里有 X-Trace-Id: abc123
            if (!traceId.isEmpty()) {
                response.setHeader(RESPONSE_HEADER_TRACE_ID, traceId);
            }

            // Step 3: 放行请求，后续由其他 Filter → DispatcherServlet → Controller 处理
            chain.doFilter(request, response);

        } finally {
            // Step 4: 必须清理 MDC！
            // 原因：Web 容器使用线程池（Tomcat NIO），线程会被复用。
            // 如果不清理，下一个请求复用此线程时会读到上一个请求的 traceId，日志错乱。
            MDC.remove(MDC_TRACE_ID);
            MDC.remove(MDC_SPAN_ID);
        }
    }
}
