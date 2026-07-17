package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.OutboxMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;
import java.util.List;

/** Database operations for leased Outbox delivery. */
@Mapper
public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {

    /**
     * Selects claimable rows while skipping rows locked by another Relay instance.
     * Must run inside a transaction.
     */
    @Select("SELECT * FROM outbox_message " +
            "WHERE status = 'PENDING' AND next_retry_at <= NOW() " +
            "ORDER BY created_at ASC, id ASC " +
            "LIMIT #{limit} FOR UPDATE SKIP LOCKED")
    List<OutboxMessage> selectClaimCandidates(@Param("limit") int limit);

    @Update("UPDATE outbox_message SET status = 'PROCESSING', worker_id = #{workerId}, " +
            "claimed_at = NOW(), lease_until = #{leaseUntil}, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int markProcessing(@Param("id") Long id,
                       @Param("workerId") String workerId,
                       @Param("leaseUntil") LocalDateTime leaseUntil);

    @Update("UPDATE outbox_message SET status = 'SENT', worker_id = NULL, " +
            "lease_until = NULL, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'PROCESSING' AND worker_id = #{workerId}")
    int markAsSent(@Param("id") Long id, @Param("workerId") String workerId);

    @Update("UPDATE outbox_message SET status = #{newStatus}, retry_count = retry_count + 1, " +
            "next_retry_at = #{nextRetryAt}, worker_id = NULL, lease_until = NULL, updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'PROCESSING' AND worker_id = #{workerId}")
    int markAsRetry(@Param("id") Long id,
                    @Param("workerId") String workerId,
                    @Param("newStatus") String newStatus,
                    @Param("nextRetryAt") LocalDateTime nextRetryAt);

    /** Returns abandoned PROCESSING rows to PENDING after their lease expires. */
    @Update("UPDATE outbox_message SET status = 'PENDING', worker_id = NULL, lease_until = NULL, " +
            "next_retry_at = NOW(), updated_at = NOW() " +
            "WHERE status = 'PROCESSING' AND lease_until < NOW()")
    int requeueExpiredLeases();

    @Update("UPDATE outbox_message " +
            "SET status = 'PENDING', retry_count = 0, " +
            "auto_retry_count = #{currentAutoRetryCount} + 1, " +
            "worker_id = NULL, lease_until = NULL, next_retry_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'FAILED'")
    int resetFailedMessageForAutoRecovery(@Param("id") Long id,
                                           @Param("currentAutoRetryCount") int currentAutoRetryCount);
}
