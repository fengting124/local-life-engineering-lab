package com.personalprojections.locallife.server.module.coupon.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/** Merchant-facing representation of a shop compensation coupon binding. */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompensationCouponBindingVO {

    private String bindingId;
    private String shopId;
    private String merchantId;
    private Integer faceValueMinor;
    private String couponTemplateId;
    private String discountType;
    private Integer discountValue;
    private Integer minOrderAmount;
    private Integer validDays;
    private String templateStatus;
    private Integer remainStock;
    private Boolean enabled;
    private String configurationStatus;
    private LocalDateTime createdAt;
    private LocalDateTime updatedAt;
}
