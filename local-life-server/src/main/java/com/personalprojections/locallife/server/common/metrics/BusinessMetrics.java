package com.personalprojections.locallife.server.common.metrics;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.time.Duration;

/**
 * 业务 Metrics 采集组件，封装核心业务事件的计数器和计时器。
 *
 * <h2>为什么需要业务 Metrics</h2>
 * <p>Spring Boot Actuator 自动采集 JVM、HTTP 等通用指标，
 * 但业务层面的「抢券成功率」「订单创建失败次数」「支付回调平均耗时」
 * 需要我们手动埋点，这就是 BusinessMetrics 的职责。
 *
 * <h2>Counter vs Timer</h2>
 * <ul>
 *   <li>{@link Counter}：只增不减，用于统计「发生了多少次」
 *       → 抢券总次数、订单创建次数、支付成功次数</li>
 *   <li>{@link Timer}：记录「每次耗时多少」，内置 count/sum/max/percentile
 *       → 支付回调处理耗时（P99 耗时超过 2s 可触发告警）</li>
 * </ul>
 *
 * <h2>Prometheus 查询示例</h2>
 * <pre>
 *   // 最近 5 分钟抢券 QPS
 *   rate(seckill_attempts_total[5m])
 *
 *   // 抢券成功率（按门店）
 *   rate(seckill_success_total{shopId="123"}[5m])
 *   /
 *   rate(seckill_attempts_total{shopId="123"}[5m])
 *
 *   // 支付回调 P99 耗时
 *   histogram_quantile(0.99, rate(payment_callback_duration_seconds_bucket[5m]))
 *
 *   // 订单创建失败次数（最近 1 分钟）
 *   increase(order_create_failure_total[1m])
 * </pre>
 *
 * <h2>使用方式</h2>
 * <pre>{@code
 *   // 在 SeckillService 中：
 *   @Autowired
 *   private BusinessMetrics businessMetrics;
 *
 *   public void seckill(SeckillRequest req) {
 *       businessMetrics.recordSeckillAttempt(req.getShopId());
 *       try {
 *           // ... 秒杀逻辑
 *           businessMetrics.recordSeckillSuccess(req.getShopId());
 *       } catch (BizException e) {
 *           businessMetrics.recordSeckillFailure(req.getShopId(), e.getCode());
 *           throw e;
 *       }
 *   }
 * }</pre>
 */
@Slf4j
@Component
public class BusinessMetrics {

    private final MeterRegistry meterRegistry;

    /**
     * 构造时注入 MeterRegistry。
     * 不使用字段注入，遵循 Spring 推荐的构造器注入方式。
     */
    public BusinessMetrics(MeterRegistry meterRegistry) {
        this.meterRegistry = meterRegistry;
    }

    // ===== 秒杀模块 Metrics =====

    /**
     * 记录一次秒杀请求（无论成功与否）。
     *
     * <p>Prometheus metric name: {@code seckill_attempts_total}
     * Tags: {@code templateId}（券模板 ID，用于按活动分析秒杀热度）
     *
     * @param couponTemplateId 券模板 ID（SeckillSession 通过 couponTemplateId 标识活动）
     */
    public void recordSeckillAttempt(Long couponTemplateId) {
        meterRegistry.counter("seckill.attempts",
                "templateId", String.valueOf(couponTemplateId)
        ).increment();
    }

    /**
     * 记录一次秒杀成功（Redis 预扣库存成功）。
     *
     * <p>Prometheus metric name: {@code seckill_success_total}
     *
     * @param couponTemplateId 券模板 ID
     */
    public void recordSeckillSuccess(Long couponTemplateId) {
        meterRegistry.counter("seckill.success",
                "templateId", String.valueOf(couponTemplateId)
        ).increment();
    }

    /**
     * 记录一次秒杀失败（库存耗尽、已参与等）。
     *
     * <p>Prometheus metric name: {@code seckill_failure_total}
     * Tags: {@code templateId}, {@code reason}（失败原因，来自 ErrorCode）
     *
     * @param couponTemplateId 券模板 ID
     * @param reason           失败原因错误码，如 "COUPON_STOCK_EXHAUSTED"
     */
    public void recordSeckillFailure(Long couponTemplateId, String reason) {
        meterRegistry.counter("seckill.failure",
                "templateId", String.valueOf(couponTemplateId),
                "reason", reason
        ).increment();
    }

    // ===== 订单模块 Metrics =====

    /**
     * 记录一次订单创建（成功时调用）。
     *
     * <p>Prometheus metric name: {@code order_created_total}
     *
     * @param shopId 门店 ID
     */
    public void recordOrderCreated(Long shopId) {
        meterRegistry.counter("order.created",
                "shopId", String.valueOf(shopId)
        ).increment();
    }

    /**
     * 记录一次订单创建失败（含幂等重复、库存不足等）。
     *
     * <p>Tags: reason（失败的 ErrorCode 枚举名）
     *
     * @param reason 失败原因
     */
    public void recordOrderCreateFailure(String reason) {
        meterRegistry.counter("order.create.failure",
                "reason", reason
        ).increment();
    }

    // ===== 支付模块 Metrics =====

    /**
     * 记录支付回调处理耗时。
     *
     * <p>Prometheus metric name: {@code payment_callback_duration_seconds}
     * 此 Timer 同时记录：
     * <ul>
     *   <li>{@code _count}：回调总次数</li>
     *   <li>{@code _sum}：所有回调的总耗时</li>
     *   <li>{@code _bucket}：各延迟区间的请求数（用于 histogram_quantile）</li>
     * </ul>
     *
     * @param durationMs 回调处理耗时（毫秒）
     * @param channel    支付渠道（如 "MOCK"、"ALIPAY"、"WECHAT_PAY"）
     * @param success    回调是否处理成功
     */
    public void recordPaymentCallback(long durationMs, String channel, boolean success) {
        meterRegistry.timer("payment.callback.duration",
                "channel", channel,
                "success", String.valueOf(success)
        ).record(Duration.ofMillis(durationMs));
    }

    /**
     * 记录一次支付成功。
     *
     * @param channel 支付渠道
     */
    public void recordPaymentSuccess(String channel) {
        meterRegistry.counter("payment.success",
                "channel", channel
        ).increment();
    }

    // ===== 搜索模块 Metrics =====

    /**
     * 记录一次门店搜索（用于分析搜索热度和 ES 负载）。
     *
     * <p>Prometheus metric name: {@code search_shop_total}
     *
     * @param hasKeyword 是否有关键词（纯地理位置搜索 vs 关键词搜索）
     * @param hasGeo     是否有地理位置过滤
     */
    public void recordShopSearch(boolean hasKeyword, boolean hasGeo) {
        meterRegistry.counter("search.shop",
                "hasKeyword", String.valueOf(hasKeyword),
                "hasGeo", String.valueOf(hasGeo)
        ).increment();
    }
}
