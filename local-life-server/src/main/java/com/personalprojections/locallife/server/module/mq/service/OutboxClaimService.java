package com.personalprojections.locallife.server.module.mq.service;

import com.personalprojections.locallife.server.domain.entity.OutboxMessage;
import com.personalprojections.locallife.server.domain.mapper.OutboxMessageMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

/**
 * Claims Outbox rows in a short transaction.
 *
 * <p>The row locks are released before RocketMQ is called. This keeps database lock duration
 * independent from network latency while preventing concurrent Relay instances from sending the
 * same row during a valid lease.</p>
 */
@Service
@RequiredArgsConstructor
public class OutboxClaimService {

    private final OutboxMessageMapper mapper;

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public List<OutboxMessage> claimBatch(int limit, String workerId, LocalDateTime leaseUntil) {
        List<OutboxMessage> candidates = mapper.selectClaimCandidates(limit);
        if (candidates.isEmpty()) {
            return List.of();
        }

        List<OutboxMessage> claimed = new ArrayList<>(candidates.size());
        for (OutboxMessage message : candidates) {
            if (mapper.markProcessing(message.getId(), workerId, leaseUntil) == 1) {
                message.setStatus("PROCESSING");
                message.setWorkerId(workerId);
                message.setClaimedAt(LocalDateTime.now());
                message.setLeaseUntil(leaseUntil);
                claimed.add(message);
            }
        }
        return claimed;
    }
}
