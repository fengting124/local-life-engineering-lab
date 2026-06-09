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
    // 秒杀域
    // =========================================================

    /**
     * 秒杀成功事件 Topic。
     * 生产者：{@code OutboxService.relayMessages()} 扫描 outbox 后投递。
     * 消费者：{@link com.personalprojections.locallife.server.module.mq.consumer.SeckillSuccessConsumer}
     *
     * <p>对应 {@code SeckillService} 类注释中「后续升级为 MQ」的目标架构：
     * Lua 预扣库存成功后，不再同步写 user_coupon，而是写 outbox_message，
     * 由 Relay 投递到此 Topic，下游消费者异步落库，把 DB 写入从秒杀主链路解耦。
     */
    public static final String SECKILL_SUCCESS_TOPIC = "seckill-success-topic";

    /**
     * 秒杀成功事件 Tag。
     */
    public static final String TAG_SECKILL_SUCCESS = "SECKILL_SUCCESS";

    // =========================================================
    // 订单域
    // =========================================================

    /**
     * 订单关闭延时通知 Topic。
     * 生产者：{@code OrderService.createOrder()} 下单成功后直接投递
     * （注意：不经过 Outbox，是 best-effort 发送，详见 {@link #TAG_ORDER_CLOSE_NOTIFY} 的说明）。
     * 消费者：{@link com.personalprojections.locallife.server.module.mq.consumer.OrderCloseConsumer}
     */
    public static final String ORDER_CLOSE_TOPIC = "order-close-topic";

    /**
     * 订单关闭延时通知 Tag。
     *
     * <h2>为什么这条消息不走 Outbox（与支付成功 / 秒杀成功不同）</h2>
     * <p>Outbox 解决的是「事件必须可靠投递、不能丢」的问题（如支付成功、秒杀成功——
     * 丢了就会导致数据不一致）。而订单关闭延时消息属于「锦上添花」的优化路径：
     * <ul>
     *   <li>它不是触发关单的唯一途径——{@code OrderService.closeExpiredOrders()}
     *       定时任务仍然兜底扫描 expire_at 已过期的订单，MQ 消息丢失也不会导致订单永远不关闭，
     *       只是会退化回「按定时任务节奏扫描」的旧行为</li>
     *   <li>消费侧天然幂等（{@code updateStatusFromWaitPay} 内部
     *       {@code WHERE status='WAIT_PAY'} 的 CAS 更新），重复投递也不会产生副作用，
     *       不需要再加一层 Redis SETNX 幂等 Key（与 PaymentSuccessConsumer / SeckillSuccessConsumer
     *       的关键区别——业务操作本身幂等时，没必要叠加幂等基础设施）</li>
     * </ul>
     * <p>因此用 Outbox 的「落库 + Relay」两段式反而是过度设计：多了一次 DB 写入和轮询延迟，
     * 却没有换来必须的可靠性收益。直接同步发送（best-effort，失败仅记录日志）更简单，
     * 且已经有定时任务兜底——这正是「按场景做技术选型，而不是无脑套用同一套基础设施」的体现。
     */
    public static final String TAG_ORDER_CLOSE_NOTIFY = "ORDER_CLOSE_NOTIFY";

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
