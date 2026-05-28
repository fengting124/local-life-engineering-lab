package com.personalprojections.locallife.server.module.mq.constant;

/**
 * RocketMQ Topic 和 Tag 常量集中管理。
 *
 * <h2>Topic 设计原则</h2>
 * <ul>
 *   <li>一个 Topic 代表一类业务事件，同一 Topic 下的消息结构相同</li>
 *   <li>不同业务域用不同 Topic，消费者按 Topic 独立订阅</li>
 *   <li>命名规范：{业务域}-{事件类型}-topic，全小写中划线</li>
 * </ul>
 *
 * <h2>Tag 设计原则</h2>
 * <ul>
 *   <li>Tag 是 Topic 内的二级过滤，消费者可以只订阅特定 Tag</li>
 *   <li>命名规范：全大写下划线，与事件类名对应</li>
 *   <li>同一 Topic 内的不同事件类型用 Tag 区分（如以后扩展退款成功事件）</li>
 * </ul>
 *
 * <h2>集中管理的原因</h2>
 * <p>Topic/Tag 字符串分散在代码各处，修改时容易遗漏。
 * 集中到此类，所有生产者和消费者引用常量，一处修改全局生效。
 */
public final class MqTopics {

    /** 工具类，禁止实例化。 */
    private MqTopics() {}

    // =========================================================
    // 支付域
    // =========================================================

    /**
     * 支付成功事件 Topic。
     * 生产者：{@code OutboxService.relayMessages()} 扫描 outbox 后投递。
     * 消费者：{@link com.personalprojections.locallife.server.module.mq.consumer.PaymentSuccessConsumer}
     */
    public static final String PAYMENT_SUCCESS_TOPIC = "payment-success-topic";

    /**
     * 支付成功事件 Tag。
     * 当前 Topic 下只有一种事件类型，使用 Tag 方便未来扩展：
     * 如果后续增加「支付失败事件」，可以用不同 Tag 在同一 Topic 内区分，
     * 不同消费者按 Tag 订阅各自关心的事件。
     */
    public static final String TAG_PAYMENT_SUCCESS = "PAYMENT_SUCCESS";

    // =========================================================
    // 死信域（Dead Letter Queue）
    // =========================================================

    /**
     * RocketMQ 死信 Topic 命名规则：%DLQ%{消费者组}。
     * 当消费者重试超过 maxReconsumeTimes 后，消息自动进入此 Topic。
     * 当前仅作常量声明，死信监听器后续根据需要实现。
     */
    public static final String DLQ_CONSUMER_GROUP_PREFIX = "%DLQ%";
}
