package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Append-only audit row for a compensation coupon binding state change. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("compensation_coupon_binding_audit")
public class CompensationCouponBindingAudit {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long bindingId;
    private Long shopId;
    private Long merchantId;
    private Integer faceValueMinor;
    private String action;
    private Long operatorUserId;
    private String requestId;
    private String beforeSnapshot;
    private String afterSnapshot;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
