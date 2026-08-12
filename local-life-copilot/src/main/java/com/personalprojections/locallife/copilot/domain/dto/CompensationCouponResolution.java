package com.personalprojections.locallife.copilot.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CompensationCouponResolution {
    private Long orderId;
    private String orderNo;
    private Long targetUserId;
    private Long shopId;
    private Long merchantId;
    private Integer faceValueMinor;
    private Long couponTemplateId;
    private String discountType;
    private Integer discountValue;
    private Integer minOrderAmount;
    private Integer validDays;
    private String templateStatus;
    private Integer bindingEnabled;
}
