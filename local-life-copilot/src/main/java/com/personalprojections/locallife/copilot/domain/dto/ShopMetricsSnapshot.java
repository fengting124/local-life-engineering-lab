package com.personalprojections.locallife.copilot.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 门店经营指标只读快照。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopMetricsSnapshot {

    private Long orderCount;
    private Long gmv;
    private Long cancelCount;
    private Long couponUsedCount;
    private Long totalCouponDiscount;
}
