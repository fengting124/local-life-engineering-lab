package com.personalprojections.locallife.copilot.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Copilot 订单排障视图。
 *
 * <p>这是只读查询 DTO，不是主服务领域实体。它把订单、最新支付单、优惠券模板三类表
 * 聚合成工具调用所需的稳定字段，避免工具层直接依赖 {@code Map<String, Object>}。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderSnapshot {

    private Long orderId;
    private String orderNo;
    private Long userId;
    private Long shopId;
    private Integer originalAmount;
    private Integer couponDiscount;
    private Integer orderAmount;
    private String orderStatus;
    private LocalDateTime expireAt;
    private LocalDateTime payAt;
    private LocalDateTime orderCreatedAt;

    private String payStatus;
    private String channel;
    private String tradeNo;
    private Integer paidAmount;

    private String couponStatus;
    private LocalDateTime couponExpireAt;
    private String couponName;
    private String discountType;
    private Integer discountValue;
}
