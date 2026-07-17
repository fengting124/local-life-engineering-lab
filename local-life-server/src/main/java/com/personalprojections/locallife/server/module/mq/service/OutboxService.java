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
import java.util.UUID;

/**
 * Transactional Outbox writer and leased Relay.
 *
 * <p>Business transactions call {@link #saveToOutbox}. Relay instances claim PENDING rows in a
 * short transaction, release database locks, send to RocketMQ, then complete the row with an
 * owner-guarded update. Delivery remains at least once because a process may stop after Broker ACK
 * and before the SENT update. Consumers must therefore remain idempotent by eventId.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OutboxService {

    private static final int MAX_RETRY_COUNT = 3;
    private static final int[] RETRY_BACKOFF_SECONDS = {10, 30, 60};
    private static final int RELAY_BATCH_SIZE = 100;
    private static final int RELAY_LEASE_SECONDS = 60;
    private static final int MAX_AUTO_RETRY_COUNT = 3;
    private static final int RECOVER_BATCH_SIZE = 100;

    private final OutboxMessageMapper outboxMessageMapper;
    private final OutboxClaimService outboxClaimService;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    /** Writes an event in the caller's existing business transaction. */
    @Transactional(propagation = Propagation.MANDATORY)
    public void saveToOutbox(Object event, String eventId, String topic, String tag) {
        String payload;
        try {
            payload = objectMapper.writeValueAsString(event);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Outbox payload serialization failed", error);
        }

        OutboxMessage message = OutboxMessage.builder()
                .eventId(eventId)
                .topic(topic)
                .tag(tag == null ? "" : tag)
                .payload(payload)
                .status("PENDING")
                .retryCount(0)
                .nextRetryAt(LocalDateTime.now())
                .build();
        outboxMessageMapper.insert(message);
        log.debug("[Outbox] event persisted: eventId={}, topic={}", eventId, topic);
    }

    /** Claims and sends one batch. MQ I/O occurs outside the claim transaction. */
    @Scheduled(fixedDelay = 10_000)
    public void relayMessages() {
        String workerId = UUID.randomUUID().toString();
        LocalDateTime leaseUntil = LocalDateTime.now().plusSeconds(RELAY_LEASE_SECONDS);
        List<OutboxMessage> claimed = outboxClaimService.claimBatch(
                RELAY_BATCH_SIZE,
                workerId,
                leaseUntil);
        if (claimed.isEmpty()) {
            return;
        }

        log.info("[Outbox] worker {} claimed {} messages", workerId, claimed.size());
        int sentCount = 0;
        int failCount = 0;

        for (OutboxMessage message : claimed) {
            try {
                sendToBroker(message);
                int completed = outboxMessageMapper.markAsSent(message.getId(), workerId);
                if (completed == 1) {
                    sentCount++;
                    log.debug("[Outbox] sent: eventId={}", message.getEventId());
                } else {
                    // Broker may already have accepted the event. Losing the lease means the row can
                    // be delivered again, which is why the consumer eventId guard remains mandatory.
                    log.warn("[Outbox] Broker accepted event but worker no longer owns lease: eventId={}",
                            message.getEventId());
                }
            } catch (Exception error) {
                failCount++;
                handleSendFailure(message, workerId, error);
            }
        }

        log.info("[Outbox] Relay completed: worker={}, sent={}, failed={}",
                workerId, sentCount, failCount);
    }

    private void sendToBroker(OutboxMessage message) {
        String destination = message.getTag() != null && !message.getTag().isEmpty()
                ? message.getTopic() + ":" + message.getTag()
                : message.getTopic();
        rocketMQTemplate.syncSend(
                destination,
                MessageBuilder.withPayload(message.getPayload())
                        .setHeader("eventId", message.getEventId())
                        .build());
    }

    private void handleSendFailure(OutboxMessage message, String workerId, Exception error) {
        int previousRetries = message.getRetryCount() == null ? 0 : message.getRetryCount();
        int newRetryCount = previousRetries + 1;
        String newStatus;
        LocalDateTime nextRetryAt;

        if (newRetryCount >= MAX_RETRY_COUNT) {
            newStatus = "FAILED";
            nextRetryAt = LocalDateTime.now();
            log.error("[Outbox] delivery exhausted retries: eventId={}, error={}",
                    message.getEventId(), error.getMessage());
        } else {
            int backoffSeconds = RETRY_BACKOFF_SECONDS[
                    Math.min(newRetryCount - 1, RETRY_BACKOFF_SECONDS.length - 1)];
            newStatus = "PENDING";
            nextRetryAt = LocalDateTime.now().plusSeconds(backoffSeconds);
            log.warn("[Outbox] delivery failed, retry in {}s: eventId={}, attempt={}",
                    backoffSeconds, message.getEventId(), newRetryCount);
        }

        int updated = outboxMessageMapper.markAsRetry(
                message.getId(),
                workerId,
                newStatus,
                nextRetryAt);
        if (updated == 0) {
            log.warn("[Outbox] failed worker no longer owns lease: eventId={}", message.getEventId());
        }
    }

    /** Recovers rows whose process stopped while holding a PROCESSING lease. */
    @Scheduled(fixedDelay = 30_000)
    public void recoverExpiredLeases() {
        int recovered = outboxMessageMapper.requeueExpiredLeases();
        if (recovered > 0) {
            log.warn("[Outbox] recovered {} expired PROCESSING leases", recovered);
        }
    }

    /** Gives transient FAILED rows another bounded retry cycle after infrastructure recovery. */
    @Scheduled(fixedDelay = 3_600_000)
    public void autoRecoverFailedMessages() {
        List<OutboxMessage> failedMessages = outboxMessageMapper.selectList(
                new LambdaQueryWrapper<OutboxMessage>()
                        .eq(OutboxMessage::getStatus, "FAILED")
                        .lt(OutboxMessage::getAutoRetryCount, MAX_AUTO_RETRY_COUNT)
                        .orderByAsc(OutboxMessage::getCreatedAt)
                        .last("LIMIT " + RECOVER_BATCH_SIZE));
        if (failedMessages.isEmpty()) {
            return;
        }

        int recovered = 0;
        for (OutboxMessage message : failedMessages) {
            int currentAutoRetries = message.getAutoRetryCount() == null
                    ? 0
                    : message.getAutoRetryCount();
            recovered += outboxMessageMapper.resetFailedMessageForAutoRecovery(
                    message.getId(),
                    currentAutoRetries);
        }
        log.info("[Outbox] reset {}/{} FAILED messages", recovered, failedMessages.size());
    }
}
