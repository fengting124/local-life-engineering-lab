package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.SideEffectLedger;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/**
 * 高风险副作用账本 Mapper。
 */
@Mapper
public interface SideEffectLedgerMapper extends BaseMapper<SideEffectLedger> {

    @Insert("""
            INSERT INTO side_effect_ledger(
                id, operation_type, idempotency_key, approval_id,
                resource_id, request_payload, status, created_at, updated_at
            ) VALUES (
                #{ledger.id}, #{ledger.operationType}, #{ledger.idempotencyKey},
                #{ledger.approvalId}, #{ledger.resourceId}, #{ledger.requestPayload},
                #{ledger.status}, NOW(), NOW()
            ) ON DUPLICATE KEY UPDATE id = LAST_INSERT_ID(id)
            """)
    int claim(@Param("ledger") SideEffectLedger ledger);

    @Select("""
            SELECT id, operation_type, idempotency_key, approval_id,
                   resource_id, request_payload, status, result_snapshot,
                   error_message, created_at, updated_at
            FROM side_effect_ledger
            WHERE operation_type = #{operationType}
              AND idempotency_key = #{idempotencyKey}
            FOR UPDATE
            """)
    SideEffectLedger selectForUpdate(
            @Param("operationType") String operationType,
            @Param("idempotencyKey") String idempotencyKey
    );
}
