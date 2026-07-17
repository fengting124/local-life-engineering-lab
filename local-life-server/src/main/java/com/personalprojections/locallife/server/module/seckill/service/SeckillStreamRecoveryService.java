package com.personalprojections.locallife.server.module.seckill.service;

import com.personalprojections.locallife.server.domain.entity.SeckillReservation;
import com.personalprojections.locallife.server.domain.mapper.SeckillReservationMapper;
import com.personalprojections.locallife.server.module.mq.constant.MqTopics;
import com.personalprojections.locallife.server.module.mq.event.SeckillSuccessEvent;
import com.personalprojections.locallife.server.module.mq.service.OutboxService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.connection.stream.MapRecord;
import org.springframework.data.redis.connection.stream.ReadOffset;
import org.springframework.data.redis.connection.stream.StreamOffset;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Redis Stream 秒杀预扣恢复器。
 *
 * <p>Lua 成功后会先把预扣事件 XADD 到 {@value #STREAM_KEY}。即使 Java 进程在写
 * outbox 前崩溃，重启后本服务仍可从 Stream 重放事件，补写 reservation 账本和
 * outbox_message，最终由既有 Relay/MQ/Consumer 完成出券。</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillStreamRecoveryService {

    public static final String STREAM_KEY = "seckill:stream";
    private static final int RECOVER_BATCH_SIZE = 100;

    private final SeckillReservationMapper reservationMapper;
    private final OutboxService outboxService;
    private final StringRedisTemplate redisTemplate;

    private String lastSeenId = "0-0";

    @Scheduled(fixedDelay = 5_000)
    @Transactional(rollbackFor = Exception.class)
    public void recoverStream() {
        if (redisTemplate == null) {
            return;
        }
        List<MapRecord<String, Object, Object>> records = redisTemplate.opsForStream().read(
                StreamOffset.create(STREAM_KEY, ReadOffset.from(lastSeenId)));
        if (records == null || records.isEmpty()) {
            return;
        }
        int handled = 0;
        for (MapRecord<String, Object, Object> record : records) {
            Map<String, String> values = stringify(record.getValue());
            recoverOne(values);
            lastSeenId = record.getId().getValue();
            handled++;
            if (handled >= RECOVER_BATCH_SIZE) {
                break;
            }
        }
        log.info("[SeckillStream] recovered {} stream events, lastSeenId={}", handled, lastSeenId);
    }

    @Transactional(rollbackFor = Exception.class)
    public void recoverOne(Map<String, String> values) {
        String eventId = required(values, "eventId");
        Long sessionId = Long.valueOf(required(values, "sessionId"));
        Long couponTemplateId = Long.valueOf(required(values, "couponTemplateId"));
        Long userId = Long.valueOf(required(values, "userId"));
        Integer validDays = Integer.valueOf(required(values, "validDays"));
        LocalDateTime reservedAt = LocalDateTime.parse(required(values, "reservedAt"));

        SeckillReservation reservation = SeckillReservation.builder()
                .reservationId(eventId)
                .sessionId(sessionId)
                .couponTemplateId(couponTemplateId)
                .userId(userId)
                .status("PENDING")
                .reservedAt(reservedAt)
                .build();
        try {
            reservationMapper.insert(reservation);
        } catch (DuplicateKeyException duplicate) {
            log.debug("[SeckillStream] reservation already exists: eventId={}", eventId);
        }

        SeckillSuccessEvent event = SeckillSuccessEvent.builder()
                .eventId(eventId)
                .sessionId(sessionId)
                .couponTemplateId(couponTemplateId)
                .userId(userId)
                .validDays(validDays)
                .succeededAt(reservedAt)
                .eventAt(LocalDateTime.now())
                .build();
        try {
            outboxService.saveToOutbox(
                    event,
                    eventId,
                    MqTopics.SECKILL_SUCCESS_TOPIC,
                    MqTopics.TAG_SECKILL_SUCCESS);
        } catch (DuplicateKeyException duplicate) {
            log.debug("[SeckillStream] outbox already exists: eventId={}", eventId);
        }
    }

    private Map<String, String> stringify(Map<Object, Object> raw) {
        Map<String, String> values = new HashMap<>();
        raw.forEach((key, value) -> values.put(String.valueOf(key), String.valueOf(value)));
        return values;
    }

    private String required(Map<String, String> values, String key) {
        String value = values.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("Missing seckill stream field: " + key);
        }
        return value;
    }
}
