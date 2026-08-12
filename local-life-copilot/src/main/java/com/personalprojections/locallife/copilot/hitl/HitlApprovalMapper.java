package com.personalprojections.locallife.copilot.hitl;

import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** Atomic database operations for high-risk approval consumption. */
@Mapper
public interface HitlApprovalMapper {

    @Select("""
            SELECT id, thread_id, checkpoint_id, action_type, action_payload,
                   payload_version, payload_digest, merchant_id,
                   requested_user_id, requested_role, status, expire_at,
                   execution_id, execution_lease_until, executing_at,
                   executed_at, execution_result, execution_error
            FROM hitl_approval
            WHERE id = #{id}
            """)
    HitlApprovalRecord selectApproval(@Param("id") long id);

    @Update("""
            UPDATE hitl_approval
            SET status = 'EXECUTING', execution_id = #{executionId},
                executing_at = #{now}, execution_lease_until = #{leaseUntil},
                execution_error = NULL, updated_at = #{now}
            WHERE id = #{id} AND status = 'APPROVED'
              AND expire_at >= #{now} AND payload_digest = #{digest}
            """)
    int claimApproved(
            @Param("id") long id,
            @Param("digest") String digest,
            @Param("executionId") String executionId,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Update("""
            UPDATE hitl_approval
            SET execution_id = #{executionId}, executing_at = #{now},
                execution_lease_until = #{leaseUntil}, execution_error = NULL,
                updated_at = #{now}
            WHERE id = #{id} AND status = 'EXECUTING'
              AND execution_lease_until < #{now}
              AND expire_at >= #{now} AND payload_digest = #{digest}
            """)
    int recoverExpiredLease(
            @Param("id") long id,
            @Param("digest") String digest,
            @Param("executionId") String executionId,
            @Param("now") LocalDateTime now,
            @Param("leaseUntil") LocalDateTime leaseUntil
    );

    @Update("""
            UPDATE hitl_approval
            SET status = 'EXECUTED', executed_at = NOW(),
                execution_lease_until = NULL, execution_result = #{result},
                execution_error = #{error}, updated_at = NOW()
            WHERE id = #{id} AND status = 'EXECUTING'
              AND execution_id = #{executionId}
            """)
    int completeExecution(
            @Param("id") long id,
            @Param("executionId") String executionId,
            @Param("result") String result,
            @Param("error") String error
    );

    @Update("""
            UPDATE hitl_approval
            SET status = 'EXECUTION_FAILED', execution_lease_until = NULL,
                execution_error = #{error}, updated_at = NOW()
            WHERE id = #{id} AND status = 'EXECUTING'
              AND execution_id = #{executionId}
            """)
    int failExecution(
            @Param("id") long id,
            @Param("executionId") String executionId,
            @Param("error") String error
    );
}
