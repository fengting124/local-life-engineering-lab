package com.personalprojections.locallife.server.module.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.module.mq.constant.MqTopics;
import com.personalprojections.locallife.server.module.mq.event.PaymentSuccessEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.concurrent.TimeUnit;

/**
 * 支付成功事件消费者。
 *
 * <h2>消费者的职责</h2>
 * <p>接收「支付成功事件」，执行后续的异步处理逻辑：
 * <ul>
 *   <li>当前阶段：记录日志（演示消费链路，实际业务逻辑留扩展点）</li>
 *   <li>后续迭代可在此添加：用户通知（短信/Push）、营业额统计、积分发放等</li>
 * </ul>
 *
 * <h2>幂等消费（面试核心）</h2>
 * <p>OutboxService 保证「至少一次投递」（AT_LEAST_ONCE），
 * 意味着同一条消息可能被投递多次（网络问题、Broker 重试、多实例 Relay）。
 * 消费者必须保证：消费同一条消息多次，效果与消费一次相同（幂等）。
 *
 * <p>当前实现：Redis SETNX 判重
 * <pre>
 *   消费时：SETNX consume:payment_success:{eventId} 1 EX 86400
 *   返回 true  → 未消费过，执行业务逻辑
 *   返回 false → 已消费过，直接 ACK 返回（幂等跳过）
 * </pre>
 *
 * <p>Redis 判重 vs 数据库唯一索引：
 * <ul>
 *   <li>Redis：性能更好（内存操作），但有极低概率在 Redis 宕机时失效</li>
 *   <li>DB 唯一索引：更可靠，但每次消费都要 INSERT，有写入压力</li>
 *   <li>当前方案：Redis 判重 + 日志（业务逻辑本身幂等），平衡性能和可靠性</li>
 * </ul>
 *
 * <h2>消费失败与重试</h2>
 * <p>方法抛出异常 → RocketMQ 标记消费失败 → 自动重试（RocketMQ 默认 16 次）。
 * 每次重试间隔递增（10s、30s、1min、2min...）。
 * 16 次重试全部失败 → 消息进入死信 Topic：{@code %DLQ%local-life-consumer-group}。
 * 死信后需人工处理（查日志、补偿逻辑、或删除）。
 *
 * <h2>消费模式：集群消费</h2>
 * <p>{@code consumeMode = ConsumeMode.CONCURRENTLY}（默认）：
 * 同一消费组内多个实例，每条消息只被一个实例消费（负载均衡）。
 * 如果需要广播（每个实例都消费每条消息），改为 {@code BROADCASTING}。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.PAYMENT_SUCCESS_TOPIC,
        selectorExpression = MqTopics.TAG_PAYMENT_SUCCESS, // 只消费带此 Tag 的消息
        consumerGroup = "payment-success-consumer-group"   // 消费者组，独立于生产者组
)
public class PaymentSuccessConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * 幂等判重 Key 前缀，格式：consume:payment_success:{eventId}。
     * TTL 24 小时（覆盖 MQ 最大重试窗口），24 小时后同一 eventId 可重新消费（极少发生）。
     */
    private static final String CONSUME_IDEMPOTENT_KEY = "consume:payment_success:%s";

    /** 幂等 Key TTL（秒）：24 小时。 */
    private static final long IDEMPOTENT_TTL_SECONDS = 86_400L;

    /**
     * 消费支付成功事件消息（消息体为 JSON 字符串）。
     *
     * <p>RocketMQ Spring Starter 会自动反序列化消息体调用此方法。
     * 方法正常返回 → ACK，消息消费成功。
     * 方法抛出异常 → NACK，RocketMQ 重试。
     *
     * @param messageBody 消息体 JSON 字符串
     */
    @Override
    public void onMessage(String messageBody) {
        PaymentSuccessEvent event;
        try {
            event = objectMapper.readValue(messageBody, PaymentSuccessEvent.class);
        } catch (Exception e) {
            // JSON 解析失败：通常是消息格式 bug，不重试（重试也是失败）
            // 记录错误后返回（相当于 ACK），避免消息进入无限重试
            log.error("[PaymentConsumer] 消息体解析失败，忽略消息: body={}", messageBody, e);
            return;
        }

        String eventId = event.getEventId();
        log.info("[PaymentConsumer] 收到支付成功事件: eventId={}, orderId={}, paidAmount={}分",
                eventId, event.getOrderId(), event.getPaidAmount());

        // ---- 幂等判重（防重复消费）----
        // SETNX：如果 Key 不存在则设置（返回 true），已存在则不设置（返回 false）
        String idempotentKey = String.format(CONSUME_IDEMPOTENT_KEY, eventId);
        Boolean isNew = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", IDEMPOTENT_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(isNew)) {
            // 已消费过（或者 Redis 异常时保守跳过），直接 ACK
            log.info("[PaymentConsumer] 幂等跳过（eventId 已消费）: eventId={}", eventId);
            return;
        }

        // ---- 执行业务逻辑 ----
        try {
            processPaymentSuccess(event);
        } catch (Exception e) {
            // 业务处理失败：清除幂等 Key，让 RocketMQ 重试时重新执行
            // 注意：如果业务逻辑本身是幂等的，可以不清除 Key（避免重复处理副作用）
            stringRedisTemplate.delete(idempotentKey);
            log.error("[PaymentConsumer] 业务处理失败，将重试: eventId={}", eventId, e);
            throw e; // 重新抛出，触发 RocketMQ 重试
        }
    }

    /**
     * 支付成功后的业务处理逻辑。
     *
     * <h2>当前阶段实现</h2>
     * <p>记录关键日志，供对账和排查问题用。
     *
     * <h2>后续可在此扩展的业务</h2>
     * <ul>
     *   <li>给用户发「支付成功」短信/Push 通知</li>
     *   <li>更新门店的订单统计（今日营业额）</li>
     *   <li>触发积分发放流程</li>
     *   <li>通知运营后台：新订单完成</li>
     * </ul>
     * 扩展时只需在此方法中添加调用，不影响支付主链路（解耦）。
     *
     * @param event 支付成功事件
     */
    private void processPaymentSuccess(PaymentSuccessEvent event) {
        // 当前阶段：记录关键日志（对账 / 监控 / 排查用）
        log.info("[PaymentConsumer] 支付成功处理完毕 | orderId={} | orderNo={} | userId={} | " +
                        "shopId={} | paidAmount={}分 | channel={} | tradeNo={} | paidAt={}",
                event.getOrderId(),
                event.getOrderNo(),
                event.getUserId(),
                event.getShopId(),
                event.getPaidAmount(),
                event.getChannel(),
                event.getTradeNo(),
                event.getPaidAt());

        // 【扩展点 1】发用户通知
        // notificationService.sendPaymentSuccessNotification(event.getUserId(), event.getOrderNo(), event.getPaidAmount());

        // 【扩展点 2】更新门店统计
        // shopStatsService.incrementDailySales(event.getShopId(), event.getPaidAmount());

        // 【扩展点 3】积分发放
        // pointsService.grantPoints(event.getUserId(), event.getOrderId(), event.getPaidAmount());
    }
}
