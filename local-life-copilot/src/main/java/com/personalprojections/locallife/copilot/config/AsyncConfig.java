package com.personalprojections.locallife.copilot.config;

import com.personalprojections.locallife.copilot.rbac.RbacContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.AsyncConfigurer;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * {@code @Async} 执行器配置——实现 {@link AsyncConfigurer} 接口，
 * 让 Spring 的 {@code @Async} 代理使用本类返回的执行器，
 * 而不是 Spring Boot 默认的 {@code applicationTaskExecutor}。
 *
 * <h2>核心目的：通过 TaskDecorator 跨线程传播 RbacContext</h2>
 * <p>{@link RbacContext} 使用普通 {@code ThreadLocal}：
 * 提交线程（HTTP 请求处理线程）上 {@code set()} 进去的值，
 * 执行线程（线程池复用的工作线程）上 {@code get()} 只会读到 {@code null}——
 * 因为读写根本不是同一条线程，{@code ThreadLocal} 的槽位互不相通。
 *
 * <p>解决方案：在 {@code decorate(Runnable)} 内部（这一步在<b>提交线程</b>上同步执行）
 * 先把当前 {@link RbacContext} 快照捕获下来；返回的包装 {@code Runnable} 在执行线程上运行时，
 * 先把快照 {@code set()} 进去，执行完保证在 {@code finally} 里 {@code clear()} 掉——
 * 对称清理，不会把一次请求的身份信息泄漏到下一次请求的线程复用中。
 *
 * <p>这正是 Micrometer Tracing 传播 {@code TraceContext}、
 * Spring Security 传播 {@code SecurityContext} 的标准做法，
 * 是 Spring 官方推荐的跨 {@code @Async} 边界上下文传播机制。
 */
@Configuration
public class AsyncConfig implements AsyncConfigurer {

    @Override
    public Executor getAsyncExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(2);
        executor.setMaxPoolSize(8);
        executor.setQueueCapacity(100);
        executor.setThreadNamePrefix("copilot-async-");
        executor.setTaskDecorator(runnable -> {
            // 在提交线程上捕获 RbacContext 快照（此时 ThreadLocal 还能读到）
            RbacContext ctx = RbacContext.get();
            return () -> {
                try {
                    RbacContext.set(ctx);
                    runnable.run();
                } finally {
                    // 对称清理：不论成功/异常，都清掉执行线程上的 ThreadLocal，
                    // 防止线程被池复用时把这次请求的身份带到下次请求里
                    RbacContext.clear();
                }
            };
        });
        executor.initialize();
        return executor;
    }
}
