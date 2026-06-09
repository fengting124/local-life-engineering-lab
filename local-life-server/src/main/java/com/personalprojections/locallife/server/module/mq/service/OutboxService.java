package com.personalprojections.locallife.server.module.mq.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.domain.entity.OutboxMessage;
import com.personalprojections.locallife.server.domain.mapper.OutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;

/**
 * 本地消息表（Outbox）Service。
 *
 * <h2>职责拆分</h2>
 * <ul>
 *   <li>{@code saveToOutbox}：在业务事务内写 outbox_message（同一事务，原子性保证）</li>
 *   <li>{@code relayMessages}：定时扫描 PENDING 消息，投递到 RocketMQ（独立事务，与业务解耦）</li>
 * </ul>
 *
 * <h2>为什么 relayMessages 不用 @Transactional</h2>
 * <p>Relay 任务扫描的消息已经写入 DB（commitd），不需要 DB 事务。
 * 每条消息独立发送，发送成功立即更新状态（减少锁持有时间）。
 * 如果整批加一个大事务，任何一条失败都会回滚所有状态更新，反而更复杂。
 *
 * <h2>指数退避策略</h2>
 * <pre>
 *   第 1 次失败：nextRetryAt = NOW() + 10s
 *   第 2 次失败：nextRetryAt = NOW() + 30s
 *   第 3 次失败：nextRetryAt = NOW() + 60s
 *   第 4 次（retryCount ≥ 3）：status = FAILED，停止重试，等待人工干预
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private final OutboxMessageMapper outboxMessageMapper;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /** 最大重试次数，超过后标记 FAILED。 */
    private static final int MAX_RETRY_COUNT = 3;

    /** 指数退避各阶段等待秒数（第 1 次 10s，第 2 次 30s，第 3 次 60s）。 */
    private static final int[] RETRY_BACKOFF_SECONDS = {10, 30, 60};

    /** 每批最多处理的消息数，防止一次扫描太多堵塞线程。 */
    private static final int RELAY_BATCH_SIZE = 100;

    // =========================================================
    // 写入本地消息表（在业务事务内调用）
    // =========================================================

    /**
     * 将业务事件写入本地消息表（需要在业务事务内调用）。
     *
     * <p>此方法使用 {@code Propagation.MANDATORY}：强制要求调用方已有事务。
     * 如果没有外层事务就调用此方法，会抛 {@code IllegalTransactionStateException}。
     * 这样确保 outbox_message 和业务数据（如 payment_order/order_info、user_coupon）
     * 在同一个事务里，要么都提交，要么都回滚。
     *
     * <p>泛化为 {@code Object event}：本方法不关心事件的具体类型（PaymentSuccessEvent /
     * SeckillSuccessEvent / ...），只负责「序列化 + 落表」，事件类型相关的语义
     * （如 eventId 的生成规则）由调用方决定并显式传入，保持本方法职责单一、可复用。
     *
     * <p>调用场景示例：
     * <ul>
     *   <li>PaymentService.handleCallback → orderService.markOrderAsPaid → 成功后调此方法</li>
     *   <li>SeckillService.participateSeckill → Lua 预扣成功后调此方法</li>
     * </ul>
     * （调用方需自带 {@code @Transactional}，满足 MANDATORY 要求）
     *
     * @param event     业务事件对象（任意可被 Jackson 序列化的事件 DTO）
     * @param eventId   事件全局唯一 ID（用于下游幂等消费，如 "{businessId}_{action}"）
     * @param topic     目标 RocketMQ Topic
     * @param tag       消息 Tag（可为空字符串）
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveToOutbox(Object event, String eventId, String topic, String tag) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException e) {
            // 序列化失败是 bug，抛出让事务回滚
            throw new RuntimeException("Outbox payload 序列化失败: " + e.getMessage(), e);
        }

        OutboxMessage message = OutboxMessage.builder()
                .eventId(eventId)
                .topic(topic)
                .tag(tag != null ? tag : "")
                .payload(payload)
                .status("PENDING")
                .retryCount(0)
                .nextRetryAt(LocalDateTime.now()) // 立即可投递
                .build();

        outboxMessageMapper.insert(message);
        log.debug("[Outbox] 写入消息: eventId={}, topic={}", eventId, topic);
    }

    // =========================================================
    // Relay 任务：扫描 PENDING 消息并投递到 RocketMQ
    // =========================================================

    /**
     * 定时 Relay 任务，每 10 秒扫描一次本地消息表，将 PENDING 消息投递到 RocketMQ。
     *
     * <p>执行逻辑：
     * <ol>
     *   <li>查 PENDING 且 next_retry_at &lt;= NOW() 的消息（限 100 条）</li>
     *   <li>逐条调用 rocketMQTemplate.send 发送到对应 Topic</li>
     *   <li>发送成功 → markAsSent（PENDING → SENT）</li>
     *   <li>发送失败 → markAsRetry（retryCount+1，更新 nextRetryAt，超限标 FAILED）</li>
     * </ol>
     *
     * <h2>多实例并发安全</h2>
     * <p>多个节点同时扫描，可能扫到同一条消息：
     * <ul>
     *   <li>{@code markAsSent} 内部 WHERE status='PENDING'，只有一个节点能 affected=1</li>
     *   <li>另一个节点 affected=0，跳过（幂等）</li>
     * </ul>
     * 生产级升级：用 SELECT ... FOR UPDATE（悲观锁）或 Redis 分布式锁做更严格互斥。
     *
     * <h2>RocketMQ 不可用时的行为</h2>
     * <p>发送失败 → retryCount+1 + 指数退避 → 最终 FAILED。
     * 运维告警：监控 outbox_message WHERE status='FAILED' COUNT(*) > 0。
     * 恢复后人工重置为 PENDING 重新投递，或写自动化脚本补偿。
     */
    @Scheduled(fixedDelay = 10_000) // 每 10 秒执行一次（上次完成后计时）
    public void relayMessages() {
        // 查 PENDING 且已到重试时间的消息
        List<OutboxMessage> pending = outboxMessageMapper.selectList(
                new LambdaQueryWrapper<OutboxMessage>()
                        .eq(OutboxMessage::getStatus, "PENDING")
                        .le(OutboxMessage::getNextRetryAt, LocalDateTime.now()) // next_retry_at <= NOW()
                        .orderByAsc(OutboxMessage::getCreatedAt)  // 按创建时间顺序投递，先进先出
                        .last("LIMIT " + RELAY_BATCH_SIZE));

        if (pending.isEmpty()) {
            return;
        }

        log.info("[Outbox] Relay 任务：发现 {} 条待投递消息", pending.size());
        int sentCount = 0;
        int failCount = 0;

        for (OutboxMessage msg : pending) {
            try {
                // 构造 RocketMQ 消息，topic:tag 格式
                String destination = msg.getTag() != null && !msg.getTag().isEmpty()
                        ? msg.getTopic() + ":" + msg.getTag()
                        : msg.getTopic();

                // 同步发送，确保 Broker 收到消息后才返回
                // （异步发送性能更好，但需要回调处理失败，当前用同步更简单可靠）
                rocketMQTemplate.syncSend(destination,
                        MessageBuilder.withPayload(msg.getPayload())
                                .setHeader("eventId", msg.getEventId()) // 消费者可从 Header 读取 eventId
                                .build());

                // 发送成功：原子标记为 SENT（WHERE status='PENDING' 防并发重复处理）
                int affected = outboxMessageMapper.markAsSent(msg.getId());
                if (affected > 0) {
                    sentCount++;
                    log.debug("[Outbox] 投递成功: eventId={}, topic={}", msg.getEventId(), msg.getTopic());
                } else {
                    // affected=0：另一个节点已处理，本节点幂等跳过
                    log.debug("[Outbox] 幂等跳过（已被其他节点处理）: eventId={}", msg.getEventId());
                }

            } catch (Exception e) {
                // 发送失败：指数退避重试
                failCount++;
                handleSendFailure(msg, e);
            }
        }

        if (sentCount > 0 || failCount > 0) {
            log.info("[Outbox] Relay 完成：成功={}, 失败={}", sentCount, failCount);
        }
    }

    // =========================================================
    // 死信自动恢复（定时任务）
    // =========================================================

    /**
     * 自动恢复死信消息（每小时执行一次）。
     *
     * <h2>解决的问题</h2>
     * <p>RocketMQ Broker 短暂不可用（重启/网络抖动）时，Relay 任务会因为
     * 发送失败而把消息标记为 FAILED（重试 3 次后放弃）。
     * Broker 恢复后，这些消息无法自动重新投递，只能人工干预。
     * 对于运维来说，凌晨 3 点 MQ 抖动导致的 FAILED 消息是常见场景，
     * 自动恢复可以大幅降低人工 on-call 频率。
     *
     * <h2>恢复逻辑</h2>
     * <ol>
     *   <li>扫描 status=FAILED AND autoRetryCount &lt; MAX_AUTO_RETRY_COUNT 的消息</li>
     *   <li>重置为 status=PENDING，retryCount 归零（让消息再次享有 3 次重试机会）</li>
     *   <li>autoRetryCount +1（记录已自动恢复次数）</li>
     *   <li>next_retry_at = NOW()（立即投递，不等待）</li>
     *   <li>超过 MAX_AUTO_RETRY_COUNT 次仍 FAILED → 需人工介入（说明是持久性故障）</li>
     * </ol>
     *
     * <h2>告警建议（面试加分）</h2>
     * <p>生产中应监控 outbox_message WHERE status='FAILED' AND auto_retry_count >= 3 的数量。
     * 超过阈值（如 > 10）时触发 PagerDuty/飞书告警，通知 on-call 人工排查 MQ 状态。
     *
     * <h2>Tradeoff</h2>
     * <ul>
     *   <li>消费者幂等保证：消息被重新投递，消费者必须幂等（eventId + Redis SETNX 已保证）</li>
     *   <li>自动恢复上限 3 次：防止 MQ 持久故障时无限循环，超过 3 次需人工判断</li>
     *   <li>批量处理 100 条：防止一次锁太多行；分批可以降低对 DB 的冲击</li>
     * </ul>
     */
    @Scheduled(fixedDelay = 3_600_000) // 每小时执行一次（上次完成后计时）
    public void autoRecoverFailedMessages() {
        // MAX_AUTO_RETRY_COUNT = 3：最多自动恢复 3 次，超过说明是持久故障
        final int MAX_AUTO_RETRY_COUNT = 3;
        final int RECOVER_BATCH_SIZE = 100;

        // 扫描 FAILED 且 autoRetryCount < 3 的消息（可以自动恢复的）
        List<OutboxMessage> failedMessages = outboxMessageMapper.selectList(
                new com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper<OutboxMessage>()
                        .eq(OutboxMessage::getStatus, "FAILED")
                        .lt(OutboxMessage::getAutoRetryCount, MAX_AUTO_RETRY_COUNT)
                        .orderByAsc(OutboxMessage::getCreatedAt)
                        .last("LIMIT " + RECOVER_BATCH_SIZE)
        );

        if (failedMessages.isEmpty()) {
            return;
        }

        log.info("[Outbox] 死信自动恢复：发现 {} 条 FAILED 消息，开始恢复", failedMessages.size());
        int recoveredCount = 0;

        for (OutboxMessage msg : failedMessages) {
            try {
                // 重置为 PENDING：retryCount 归零 + autoRetryCount +1 + next_retry_at = NOW()
                outboxMessageMapper.resetFailedMessageForAutoRecovery(
                        msg.getId(),
                        msg.getAutoRetryCount() == null ? 0 : msg.getAutoRetryCount()
                );
                recoveredCount++;
                log.info("[Outbox] 死信消息已重置为 PENDING（第 {} 次自动恢复）: eventId={}",
                        (msg.getAutoRetryCount() == null ? 0 : msg.getAutoRetryCount()) + 1,
                        msg.getEventId());
            } catch (Exception e) {
                log.error("[Outbox] 死信消息重置失败: eventId={}, error={}", msg.getEventId(), e.getMessage());
            }
        }

        log.info("[Outbox] 死信自动恢复完成：成功 {}/{} 条", recoveredCount, failedMessages.size());
    }

    // =========================================================
    // 私有工具方法
    // =========================================================

    /**
     * 处理消息发送失败：更新重试次数，计算下次重试时间（指数退避）。
     * 超过最大重试次数时标记为 FAILED（需人工介入）。
     *
     * @param msg 失败的消息
     * @param e   发送时的异常
     */
    private void handleSendFailure(OutboxMessage msg, Exception e) {
        int newRetryCount = msg.getRetryCount() + 1;
        String newStatus;
        LocalDateTime nextRetryAt;

        if (newRetryCount >= MAX_RETRY_COUNT) {
            // 超过最大重试次数，标记为 FAILED
            newStatus = "FAILED";
            nextRetryAt = LocalDateTime.now(); // 已失败，next_retry_at 无意义
            log.error("[Outbox] 消息投递失败（已达最大重试次数，标记 FAILED）: eventId={}, error={}",
                    msg.getEventId(), e.getMessage());
        } else {
            // 还可以重试：指数退避计算下次时间
            int backoffSeconds = RETRY_BACKOFF_SECONDS[Math.min(newRetryCount - 1, RETRY_BACKOFF_SECONDS.length - 1)];
            newStatus = "PENDING";
            nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
            log.warn("[Outbox] 消息投递失败（{}秒后重试，第{}/{}次）: eventId={}, error={}",
                    backoffSeconds, newRetryCount, MAX_RETRY_COUNT, msg.getEventId(), e.getMessage());
        }

        outboxMessageMapper.markAsRetry(msg.getId(), newStatus, nextRetryAt);
    }
}
