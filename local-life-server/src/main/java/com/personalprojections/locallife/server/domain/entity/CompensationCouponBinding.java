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

/** Deterministic shop and face-value mapping for compensation coupons. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("compensation_coupon_binding")
public class CompensationCouponBinding {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private Long shopId;

    private Long merchantId;

    private Integer faceValueMinor;

    private Long couponTemplateId;

    private Integer enabled;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
