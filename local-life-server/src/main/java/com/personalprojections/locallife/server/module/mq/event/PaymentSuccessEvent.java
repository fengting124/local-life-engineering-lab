package com.personalprojections.locallife.server.module.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 支付成功事件（MQ 消息体），序列化为 JSON 存入 outbox_message.payload，
 * 并投递到 RocketMQ Topic {@code payment-success-topic}。
 *
 * <h2>事件驱动设计原则</h2>
 * <p>支付回调成功后，PaymentService 不直接调用下游逻辑（如统计、通知），
 * 而是发布一个「支付成功事件」。下游服务各自订阅并独立处理，互不耦合。
 * 好处：
 * <ul>
 *   <li>解耦：PaymentService 不需要知道有哪些下游，新增下游不改 PaymentService</li>
 *   <li>可靠性：通过 outbox 保证事件不丢，消费者幂等保证不重复</li>
 *   <li>可扩展：未来新增「支付成功发短信」「支付成功更新排行榜」，只需新增消费者</li>
 * </ul>
 *
 * <h2>eventId 的作用</h2>
 * <p>eventId 是消费者做幂等判重的 Key，格式为：{paymentOrderId}_paid，全局唯一。
 * 消费者消费时先查：该 eventId 是否已处理过？
 * <ul>
 *   <li>未处理 → 执行业务逻辑 → 标记该 eventId 已处理</li>
 *   <li>已处理 → 直接 ACK，跳过（幂等，防止重复消费）</li>
 * </ul>
 *
 * <h2>字段冗余设计</h2>
 * <p>事件中冗余了 orderNo、userId、shopId、paidAmount 等字段，
 * 消费者不需要再回查数据库，直接用消息体里的数据处理业务，减少 DB 查询。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSuccessEvent {

    /**
     * 事件唯一 ID，格式：{paymentOrderId}_paid。
     * 消费者幂等判重依据，存入 outbox_message.event_id。
     * 数据库唯一索引 uk_event_id 保证不重复写入。
     */
    private String eventId;

    /** 支付单 ID（对应 payment_order.id）。 */
    private Long paymentOrderId;

    /** 支付流水号（对应 payment_order.payment_no）。 */
    private String paymentNo;

    /** 关联的订单 ID（对应 order_info.id）。 */
    private Long orderId;

    /** 关联的订单号（对应 order_info.order_no，对外展示的业务单号）。 */
    private String orderNo;

    /** 支付用户 ID。 */
    private Long userId;

    /** 门店 ID（冗余，下游统计时按门店汇总用）。 */
    private Long shopId;

    /** 实际支付金额（分），已通过金额核对，与应付金额一致。 */
    private Integer paidAmount;

    /** 支付渠道：MOCK / ALIPAY / WECHAT。 */
    private String channel;

    /** 渠道交易流水号（支付宝/微信的唯一单号）。 */
    private String tradeNo;

    /**
     * 支付成功时间（渠道时间，非本地时间）。
     * 与渠道账单时间戳一致，对账用。
     */
    private LocalDateTime paidAt;

    /** 事件产生时间（本地时间，Relay 投递时记录）。 */
    private LocalDateTime eventAt;
}
