package com.personalprojections.locallife.server.module.order.service;

import com.personalprojections.locallife.server.domain.entity.OrderIdempotency;
import com.personalprojections.locallife.server.domain.mapper.OrderIdempotencyMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 幂等请求的短事务领取服务。
 *
 * <p>领取事务必须先提交，后续业务事务才能让并发请求观察到 PROCESSING 状态。
 * 业务执行期间不持有数据库行锁。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderIdempotencyClaimService {

    private static final int PROCESSING_LEASE_SECONDS = 30;
    private static final int RETENTION_HOURS = 24;

    private final OrderIdempotencyMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean tryClaim(Long userId, String key, String requestHash) {
        LocalDateTime now = LocalDateTime.now();
        return mapper.tryClaim(
                userId,
                key,
                requestHash,
                now.plusSeconds(PROCESSING_LEASE_SECONDS),
                now.plusHours(RETENTION_HOURS)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public boolean reclaimFailedOrExpired(Long userId, String key, String requestHash) {
        LocalDateTime now = LocalDateTime.now();
        return mapper.reclaim(
                userId,
                key,
                requestHash,
                now.plusSeconds(PROCESSING_LEASE_SECONDS),
                now.plusHours(RETENTION_HOURS)) == 1;
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW, readOnly = true)
    public OrderIdempotency get(Long userId, String key) {
        return mapper.selectByUserAndKey(userId, key);
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(Long userId, String key, String requestHash, Throwable error) {
        String reason = error == null ? "unknown" : error.getClass().getSimpleName();
        mapper.markFailed(userId, key, requestHash, reason);
    }
}
