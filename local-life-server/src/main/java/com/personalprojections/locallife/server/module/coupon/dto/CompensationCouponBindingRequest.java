package com.personalprojections.locallife.server.module.coupon.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/** Request to select an existing coupon template for one shop compensation value. */
@Data
public class CompensationCouponBindingRequest {

    @NotNull(message = "优惠券模板 ID 不能为空")
    @Positive(message = "优惠券模板 ID 必须是正整数")
    private Long couponTemplateId;
}

