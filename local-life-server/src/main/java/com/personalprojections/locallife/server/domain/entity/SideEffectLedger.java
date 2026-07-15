package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 高风险副作用账本。
 *
 * <p>用于退款、补偿券等操作的幂等与审计：审批 ID 只表示“授权”，
 * 账本记录“副作用是否已经执行、执行结果是什么”。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("side_effect_ledger")
public class SideEffectLedger {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String operationType;

    private String idempotencyKey;

    private String approvalId;

    private String resourceId;

    private String requestPayload;

    private String status;

    private String resultSnapshot;

    private String errorMessage;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
