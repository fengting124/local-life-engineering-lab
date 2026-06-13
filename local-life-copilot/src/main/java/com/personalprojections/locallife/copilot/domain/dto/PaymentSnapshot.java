package com.personalprojections.locallife.copilot.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 支付单只读快照，用于 query_payment 工具返回支付流水状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PaymentSnapshot {

    private Long paymentId;
    private String paymentNo;
    private String payStatus;
    private String channel;
    private String tradeNo;
    private Integer payAmount;
    private Integer paidAmount;
    private LocalDateTime paidAt;
    private LocalDateTime createdAt;
}
