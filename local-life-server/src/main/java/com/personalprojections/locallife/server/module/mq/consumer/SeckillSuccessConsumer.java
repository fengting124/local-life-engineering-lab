package com.personalprojections.locallife.server.module.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.module.mq.constant.MqTopics;
import com.personalprojections.locallife.server.module.mq.event.SeckillSuccessEvent;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀成功事件消费者：异步完成 user_coupon 的真正落库。
 *
 * <h2>消费者的职责</h2>
 * <p>承接 {@code SeckillService} 类注释中「Lua 预扣 → 发 MQ → MQ 消费者写 DB」
 * 异步架构的最后一棒：接收「秒杀成功事件」，执行 INSERT user_coupon，
 * 并把 {@code seckill:result:{sessionId}:{templateId}:{userId}} 标记为 SUCCESS，
 * 供前端轮询 {@code GET /seckill/result} 感知「真正出券」的时刻。
 *
 * <p>本消费者与 {@link PaymentSuccessConsumer} 共享同一套 Outbox 可靠消息基础设施
 * （Outbox 落库 → Relay 投递 → 幂等消费），是「基础设施一次建好、到处复用」的范例：
 * 秒杀域不需要再发明一套独立的可靠消息机制。
 *
 * <h2>三层幂等防线（层层兜底，互为补充）</h2>
 * <ol>
 *   <li>Redis Set 判重（SISMEMBER）：秒杀入口拦截重复请求，性能优先，发生在最前端</li>
 *   <li>Redis SETNX 幂等消费 Key（本类）：防止 MQ 重复投递导致重复处理，详见下文</li>
 *   <li>user_coupon 唯一索引 uk_user_coupon_template：DB 层最终兜底，
 *       即使前两层都失效（如 Redis 宕机重启丢数据），也不会产生重复券</li>
 * </ol>
 *
 * <h2>幂等消费（与 PaymentSuccessConsumer 同一模式）</h2>
 * <pre>
 *   消费时：SETNX consume:seckill_success:{eventId} 1 EX 86400
 *   返回 true  → 未消费过，执行业务逻辑
 *   返回 false → 已消费过，直接 ACK 返回（幂等跳过）
 * </pre>
 *
 * <h2>消费失败与重试</h2>
 * <p>方法抛出异常 → RocketMQ 标记消费失败 → 自动重试（默认 16 次，间隔递增）。
 * 16 次全部失败 → 进入死信 Topic：{@code %DLQ%seckill-success-consumer-group}，需人工处理。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.SECKILL_SUCCESS_TOPIC,
        selectorExpression = MqTopics.TAG_SECKILL_SUCCESS,
        consumerGroup = "seckill-success-consumer-group"
)
public class SeckillSuccessConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final UserCouponMapper userCouponMapper;

    /**
     * 幂等判重 Key 前缀，格式：consume:seckill_success:{eventId}。
     * TTL 24 小时（覆盖 MQ 最大重试窗口），与 {@link PaymentSuccessConsumer} 保持同一约定。
     */
    private static final String CONSUME_IDEMPOTENT_KEY = "consume:seckill_success:%s";

    /** 幂等 Key TTL（秒）：24 小时。 */
    private static final long IDEMPOTENT_TTL_SECONDS = 86_400L;

    /**
     * 秒杀结果 Key 模板，需与 {@code SeckillService.RESULT_KEY} 保持一致：
     * seckill:result:{sessionId}:{templateId}:{userId}。
     */
    private static final String RESULT_KEY = "seckill:result:%d:%d:%d";

    /** 结果 Key 取值：消费成功后更新为 SUCCESS。 */
    private static final String RESULT_SUCCESS = "SUCCESS";

    /** 结果 Key TTL（秒），与写入时保持一致：24 小时。 */
    private static final long RESULT_KEY_TTL_SECONDS = 86_400L;

    /**
     * 消费秒杀成功事件消息（消息体为 JSON 字符串）。
     *
     * @param messageBody 消息体 JSON 字符串
     */
    @Override
    public void onMessage(String messageBody) {
        SeckillSuccessEvent event;
        try {
            event = objectMapper.readValue(messageBody, SeckillSuccessEvent.class);
        } catch (Exception e) {
            // JSON 解析失败：消息格式 bug，重试也无意义，记录后直接 ACK，避免无限重试
            log.error("[SeckillConsumer] 消息体解析失败，忽略消息: body={}", messageBody, e);
            return;
        }

        String eventId = event.getEventId();
        log.info("[SeckillConsumer] 收到秒杀成功事件: eventId={}, userId={}, sessionId={}, couponTemplateId={}",
                eventId, event.getUserId(), event.getSessionId(), event.getCouponTemplateId());

        // ---- 幂等判重（防重复消费）----
        String idempotentKey = String.format(CONSUME_IDEMPOTENT_KEY, eventId);
        Boolean isNew = stringRedisTemplate.opsForValue()
                .setIfAbsent(idempotentKey, "1", IDEMPOTENT_TTL_SECONDS, TimeUnit.SECONDS);

        if (!Boolean.TRUE.equals(isNew)) {
            // 已消费过（或 Redis 异常时保守跳过），直接 ACK
            log.info("[SeckillConsumer] 幂等跳过（eventId 已消费）: eventId={}", eventId);
            return;
        }

        // ---- 执行业务逻辑：异步写库 + 标记结果 ----
        try {
            createUserCoupon(event);
            markResultAsSuccess(event);
        } catch (Exception e) {
            // 业务处理失败：清除幂等 Key，让 RocketMQ 重试时重新执行
            stringRedisTemplate.delete(idempotentKey);
            log.error("[SeckillConsumer] 业务处理失败，将重试: eventId={}", eventId, e);
            throw e; // 重新抛出，触发 RocketMQ 重试
        }
    }

    /**
     * 异步写入用户券记录（真正的 INSERT user_coupon，从秒杀主链路解耦出来的部分）。
     *
     * <p>幂等兜底：捕获 DuplicateKeyException（uk_user_coupon_template 唯一索引冲突），
     * 说明该用户已经领过这张券（如 SETNX 幂等 Key 因 Redis 异常失效导致重复消费），
     * 直接忽略，不是真正的错误——三层幂等防线中的最后一道。
     *
     * <p>事件已冗余 validDays，直接计算 expireAt，无需回查 coupon_template。
     *
     * @param event 秒杀成功事件
     */
    private void createUserCoupon(SeckillSuccessEvent event) {
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plusDays(event.getValidDays());

        UserCoupon userCoupon = UserCoupon.builder()
                .userId(event.getUserId())
                .couponTemplateId(event.getCouponTemplateId())
                .seckillSessionId(event.getSessionId())
                .couponStatus("UNUSED")
                .receivedAt(now)
                .expireAt(expireAt)
                .build();

        try {
            userCouponMapper.insert(userCoupon);
            log.info("[SeckillConsumer] 异步出券成功: eventId={}, userId={}, couponTemplateId={}",
                    event.getEventId(), event.getUserId(), event.getCouponTemplateId());
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突：用户已经领过这张券，幂等忽略
            log.warn("[SeckillConsumer] 用户券重复创建（唯一索引冲突，幂等处理）: eventId={}, userId={}, couponTemplateId={}",
                    event.getEventId(), event.getUserId(), event.getCouponTemplateId());
        }
    }

    /**
     * 把 Redis 结果 Key 更新为 SUCCESS，供 {@code GET /seckill/result} 轮询读取。
     *
     * <p>注意：即使本步骤因 Redis 异常失败，也不影响最终一致性——
     * {@code SeckillService.querySeckillResult} 的第一判断依据始终是 DB 记录是否存在，
     * 本 Key 只是加速「用户更快感知到结果」的体验优化。
     *
     * @param event 秒杀成功事件
     */
    private void markResultAsSuccess(SeckillSuccessEvent event) {
        String resultKey = String.format(RESULT_KEY,
                event.getSessionId(), event.getCouponTemplateId(), event.getUserId());
        stringRedisTemplate.opsForValue().set(resultKey, RESULT_SUCCESS, RESULT_KEY_TTL_SECONDS, TimeUnit.SECONDS);
    }
}
