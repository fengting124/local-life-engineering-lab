package com.personalprojections.locallife.server.config;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Tag;
import io.micrometer.core.instrument.config.MeterFilter;
import org.springframework.boot.actuate.autoconfigure.metrics.MeterRegistryCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

import java.util.List;

/**
 * 可观测性配置类，定制 Micrometer MeterRegistry。
 *
 * <h2>什么是 Micrometer？</h2>
 * <p>Micrometer 是 JVM 应用的监控门面（类比 SLF4J 之于日志），
 * 一套 API 对接多种监控后端（Prometheus、Grafana、DataDog、InfluxDB 等）。
 * Spring Boot Actuator 内置 Micrometer，自动收集：
 * <ul>
 *   <li>JVM 指标：heap 大小、GC 次数、线程数</li>
 *   <li>HTTP 指标：请求总数、延迟分布、错误率</li>
 *   <li>数据库连接池：HikariCP 活跃连接、等待时间</li>
 *   <li>Redis：命令执行时间、连接数</li>
 * </ul>
 *
 * <h2>本类做了什么</h2>
 * <ol>
 *   <li>全局 Tag：所有 metrics 附加 {@code application=local-life-server} 标签，
 *       Prometheus 多服务时可按 application 过滤</li>
 *   <li>过滤无用 metrics（未来可扩展）：如过滤 Actuator 自身的内部 metrics，
 *       减少 Prometheus 存储压力</li>
 * </ol>
 *
 * <h2>Prometheus 数据流</h2>
 * <pre>
 *   JVM + HTTP 请求发生
 *        ↓
 *   Micrometer 采集（Counter / Timer / Gauge）
 *        ↓
 *   /actuator/prometheus 端点（Prometheus text format）
 *        ↓
 *   Prometheus Server scrape（每 15s 拉取一次）
 *        ↓
 *   Grafana 查询 + 展示（仪表盘、告警）
 * </pre>
 *
 * <h2>三种 Metrics 类型简介</h2>
 * <ul>
 *   <li>{@code Counter}：只增不减，如请求总数、错误总数</li>
 *   <li>{@code Timer}：记录耗时分布，如 HTTP 请求延迟（含 P50/P95/P99）</li>
 *   <li>{@code Gauge}：瞬时值，如当前活跃线程数、队列深度</li>
 * </ul>
 */
@Configuration
public class ObservabilityConfig {

    /**
     * 全局 MeterRegistry 定制器。
     *
     * <p>给所有 metrics 添加 {@code application} 公共标签。
     * 示例：
     * <pre>
     *   http_server_requests_seconds_count{
     *     application="local-life-server",
     *     method="POST",
     *     uri="/api/v1/orders",
     *     status="200"
     *   } 42
     * </pre>
     *
     * <p>在 Prometheus + Grafana 中，可以用
     * {@code sum by (uri) (rate(http_server_requests_seconds_count{application="local-life-server"}[5m]))}
     * 查询各接口的 QPS。
     *
     * @return MeterRegistry 定制器 Bean（Spring Boot Actuator 自动应用）
     */
    @Bean
    public MeterRegistryCustomizer<MeterRegistry> metricsCommonTags() {
        return registry -> registry.config()
                // 公共标签：区分来自哪个服务的指标（多服务部署时必须）
                .commonTags(List.of(
                        Tag.of("application", "local-life-server"),
                        Tag.of("env", "dev")  // 生产环境通过配置文件覆盖为 "prod"
                ));
    }

    /**
     * Metrics 过滤器：过滤掉 Spring Boot Actuator 内部的 /actuator/** 请求指标。
     *
     * <p>原因：Prometheus scrape 本身会触发 /actuator/prometheus 请求，
     * 如果不过滤，这些请求会混入业务接口的 metrics，干扰 QPS 和延迟统计。
     *
     * <p>过滤逻辑：
     * <ul>
     *   <li>uri 标签值为 "/actuator/**" 的 Timer 记录 → deny（丢弃）</li>
     *   <li>其他所有 metrics → accept（保留）</li>
     * </ul>
     *
     * @return MeterFilter Bean，由 Micrometer 自动注册到 MeterRegistry
     */
    @Bean
    public MeterFilter excludeActuatorMetrics() {
        return MeterFilter.deny(id -> {
            // MeterRegistry 中 HTTP 请求 Timer 的 metric name 是 "http.server.requests"
            // 通过 uri 标签过滤 /actuator/ 路径
            String uri = id.getTag("uri");
            return uri != null && uri.startsWith("/actuator");
        });
    }
}
