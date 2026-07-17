package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.OrderIdempotency;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** Database operations for the order request idempotency ledger. */
@Mapper
public interface OrderIdempotencyMapper extends BaseMapper<OrderIdempotency> {

    @Insert("INSERT IGNORE INTO order_idempotency " +
            "(user_id, idempotency_key, request_hash, status, lease_until, expires_at, created_at, updated_at) " +
            "VALUES (#{userId}, #{idempotencyKey}, #{requestHash}, 'PROCESSING', " +
            "#{leaseUntil}, #{expiresAt}, NOW(), NOW())")
    int tryClaim(@Param("userId") Long userId,
                 @Param("idempotencyKey") String idempotencyKey,
                 @Param("requestHash") String requestHash,
                 @Param("leaseUntil") LocalDateTime leaseUntil,
                 @Param("expiresAt") LocalDateTime expiresAt);

    @Select("SELECT * FROM order_idempotency " +
            "WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} LIMIT 1")
    OrderIdempotency selectByUserAndKey(@Param("userId") Long userId,
                                        @Param("idempotencyKey") String idempotencyKey);

    @Update("UPDATE order_idempotency SET status = 'PROCESSING', failure_reason = NULL, " +
            "lease_until = #{leaseUntil}, expires_at = #{expiresAt}, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} " +
            "AND request_hash = #{requestHash} " +
            "AND (status = 'FAILED' OR (status = 'PROCESSING' AND lease_until < NOW()))")
    int reclaim(@Param("userId") Long userId,
                @Param("idempotencyKey") String idempotencyKey,
                @Param("requestHash") String requestHash,
                @Param("leaseUntil") LocalDateTime leaseUntil,
                @Param("expiresAt") LocalDateTime expiresAt);

    @Update("UPDATE order_idempotency SET status = 'SUCCESS', response_json = #{responseJson}, " +
            "failure_reason = NULL, lease_until = NULL, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} " +
            "AND request_hash = #{requestHash} AND status = 'PROCESSING'")
    int markSuccess(@Param("userId") Long userId,
                    @Param("idempotencyKey") String idempotencyKey,
                    @Param("requestHash") String requestHash,
                    @Param("responseJson") String responseJson);

    @Update("UPDATE order_idempotency SET status = 'FAILED', failure_reason = #{failureReason}, " +
            "lease_until = NULL, updated_at = NOW() " +
            "WHERE user_id = #{userId} AND idempotency_key = #{idempotencyKey} " +
            "AND request_hash = #{requestHash} AND status = 'PROCESSING'")
    int markFailed(@Param("userId") Long userId,
                   @Param("idempotencyKey") String idempotencyKey,
                   @Param("requestHash") String requestHash,
                   @Param("failureReason") String failureReason);
}
