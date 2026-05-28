package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 本地消息表实体，对应数据库表 {@code outbox_message}。
 *
 * <h2>Transactional Outbox Pattern（事务性发件箱模式）</h2>
 *
 * <p>场景问题：支付回调成功后，需要向 RocketMQ 发送「支付成功事件」通知下游。
 * 但数据库操作（事务）和 MQ 发送不能合并为一个原子操作：
 * <pre>
 *   ❌ 不安全的做法：
 *   BEGIN TX
 *     UPDATE payment_order → PAID
 *     UPDATE order_info    → PAID
 *   COMMIT TX
 *   rocketMQTemplate.send(...)  ← 如果这里失败，消息永久丢失！
 *
 *   ✅ Outbox 方案：
 *   BEGIN TX
 *     UPDATE payment_order → PAID
 *     UPDATE order_info    → PAID
 *     INSERT outbox_message (PENDING)  ← 和业务操作同一事务
 *   COMMIT TX
 *
 *   [Relay 任务，每 10 秒扫描一次]
 *     SELECT * FROM outbox_message WHERE status='PENDING' AND next_retry_at <= NOW()
 *     rocketMQTemplate.send(message)
 *     UPDATE outbox_message SET status='SENT'
 * </pre>
 *
 * <h2>为什么这个方案能保证不丢消息</h2>
 * <p>因为 outbox_message 和业务数据在同一个数据库事务里，
 * 要么都写成功，要么都回滚。消息记录一旦写入，Relay 任务最终一定会把它发出去。
 * 即使 Relay 任务崩溃，重启后扫描到 PENDING 消息，继续发送（AT LEAST ONCE 保证）。
 *
 * <h2>指数退避重试（防止频繁冲击）</h2>
 * <pre>
 *   第 1 次失败 → next_retry_at = NOW() + 10s（10 秒后重试）
 *   第 2 次失败 → next_retry_at = NOW() + 30s（30 秒后重试）
 *   第 3 次失败 → next_retry_at = NOW() + 60s（1 分钟后重试）
 *   第 4 次失败 → status = FAILED（人工介入 / 死信补偿）
 * </pre>
 *
 * <h2>消费者幂等（配合 eventId）</h2>
 * <p>Relay 任务保证「至少一次投递」，可能重复投递同一条消息。
 * 消费者通过 eventId（全局唯一）做幂等处理：
 * <pre>
 *   消费时：先查是否已处理过（Redis SETNX or DB 唯一索引）
 *           已处理 → 直接 ACK，跳过（幂等）
 *           未处理 → 执行业务逻辑 → 标记已处理
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("outbox_message")
public class OutboxMessage {

    /** 消息 ID，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务事件唯一 ID，消费者幂等判重的依据。
     * 格式：雪花 ID 字符串，全局唯一。
     * 不能用 id 作为 eventId：id 是存储 ID，可能被重试覆盖；
     * eventId 是业务语义 ID，与对应的业务操作绑定（如 paymentOrderId + "_paid"）。
     * 数据库唯一索引 uk_event_id 保证同一事件不重复写入。
     */
    private String eventId;

    /**
     * RocketMQ Topic 名称。
     * 示例：payment-success-topic（支付成功事件）
     * 建议 Topic 命名：{业务域}-{事件类型}-topic，全小写中划线。
     */
    private String topic;

    /**
     * RocketMQ Tag，用于消费者订阅过滤。
     * 示例：PAYMENT_SUCCESS（Tag）
     * 空字符串表示不过滤（所有消费者都能收到）。
     */
    private String tag;

    /**
     * 消息体，JSON 字符串（序列化的事件对象）。
     * 示例：{"orderId":123,"paymentNo":"xxx","paidAmount":9900}
     * TEXT 类型，最大 65535 字节，足够大多数业务 payload。
     */
    private String payload;

    /**
     * 投递状态：PENDING（待投递）/ SENT（已投递）/ FAILED（投递失败，超过重试阈值）。
     * 状态机：PENDING → SENT（正常链路）/ PENDING → FAILED（重试超限）。
     */
    private String status;

    /**
     * 已重试次数，初始为 0。
     * 每次投递失败后 +1，超过阈值（当前设为 3）标记为 FAILED。
     * 阈值 3 次是经验值：网络抖动通常在 1~2 次重试内恢复。
     */
    private Integer retryCount;

    /**
     * 下次重试时间，支持指数退避策略。
     * 初始值 = 当前时间（立即可投递）。
     * 每次失败后按指数延长：10s → 30s → 60s → FAILED。
     * Relay 任务 WHERE next_retry_at &lt;= NOW() 过滤，不扫描未到时间的消息。
     */
    private LocalDateTime nextRetryAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
