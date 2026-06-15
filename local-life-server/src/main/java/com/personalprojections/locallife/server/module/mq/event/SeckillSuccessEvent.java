package com.personalprojections.locallife.server.module.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 秒杀抢券成功事件（MQ 消息体），序列化为 JSON 存入 outbox_message.payload，
 * 并投递到 RocketMQ Topic {@code seckill-success-topic}。
 *
 * <h2>对应的架构升级</h2>
 * <p>{@code SeckillService} 类注释中标记的「后续升级为 MQ」目标架构：
 * Lua 预扣库存成功后不再同步 INSERT user_coupon，而是把「写券」这件事
 * 封装为本事件，经 Outbox 可靠投递，由 {@code SeckillSuccessConsumer}
 * 异步执行真正的 DB 写入，把 DB 写入从秒杀主链路解耦出去——
 * 与 {@link PaymentSuccessEvent} 走的是同一套已验证过的可靠消息基础设施
 * （Outbox 落库 → Relay 投递 → 幂等消费），无需另起一套机制。
 *
 * <h2>eventId 的作用</h2>
 * <p>格式：{sessionId}_{userId}_seckill（一个场次唯一对应一个券模板，
 * couponTemplateId 不参与拼接也能保证唯一，同时避免三个雪花 ID 拼接超出
 * outbox_message.event_id 的 VARCHAR(64) 长度上限），全局唯一。
 * 与「Lua 预扣判重」共同构成两层幂等防线：
 * <ul>
 *   <li>Redis Set（SISMEMBER）：秒杀入口处拦截重复请求，性能优先</li>
 *   <li>eventId 幂等消费 + user_coupon 唯一索引：MQ 链路兜底，可靠性优先</li>
 * </ul>
 *
 * <h2>字段冗余设计</h2>
 * <p>冗余 sessionId、couponTemplateId、userId、validDays 等字段，
 * 消费者直接用消息体数据写入 user_coupon，无需回查 SeckillSession/CouponTemplate，
 * 减少异步链路上的 DB 查询（与 {@link PaymentSuccessEvent} 同样的设计取舍）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillSuccessEvent {

    /**
     * 事件唯一 ID，格式：{sessionId}_{userId}_seckill
     * （一个场次唯一对应一个券模板，couponTemplateId 不参与拼接也能保证唯一，
     * 同时避免三个雪花 ID 拼接超出 outbox_message.event_id 的 VARCHAR(64) 长度上限）。
     * 消费者幂等判重依据，存入 outbox_message.event_id。
     * 数据库唯一索引 uk_event_id 保证不重复写入。
     */
    private String eventId;

    /** 秒杀场次 ID（对应 seckill_session.id）。 */
    private Long sessionId;

    /** 券模板 ID（对应 coupon_template.id）。 */
    private Long couponTemplateId;

    /** 抢到券的用户 ID。 */
    private Long userId;

    /**
     * 券有效天数（冗余自 coupon_template.valid_days）。
     * 消费者据此计算 expire_at = receivedAt + validDays，无需回查券模板表。
     */
    private Integer validDays;

    /** Lua 预扣成功时间（本地时间，秒杀主链路记录）。 */
    private LocalDateTime succeededAt;

    /** 事件产生时间（本地时间，Relay 投递时记录）。 */
    private LocalDateTime eventAt;
}
