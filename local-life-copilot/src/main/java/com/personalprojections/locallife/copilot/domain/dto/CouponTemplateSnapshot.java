package com.personalprojections.locallife.copilot.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 优惠券模板只读快照，用于 Agent 查询券策略和活动规则。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponTemplateSnapshot {

    private Long couponTemplateId;
    private String couponName;
    private String discountType;
    private Integer discountValue;
    private Integer minOrderAmount;
    private Integer totalStock;
    private Integer remainingStock;
    private String status;
    private LocalDateTime startTime;
    private LocalDateTime endTime;
    private Long shopId;
    private String shopName;
    private Long merchantId;
}
