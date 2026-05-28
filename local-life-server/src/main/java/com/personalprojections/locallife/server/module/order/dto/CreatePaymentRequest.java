package com.personalprojections.locallife.server.module.order.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 发起支付请求体（POST /api/v1/payments）。
 *
 * <h2>为什么需要单独的「发起支付」接口</h2>
 * <p>「创建订单」和「发起支付」是两个独立的操作，不能合并：
 * <ol>
 *   <li>用户可能创建订单后不立即支付（后续从「待支付」列表进入支付）</li>
 *   <li>支付失败后需要重新发起支付（一个订单对应多个 payment_order）</li>
 *   <li>未来可以扩展「货到付款」「账期付款」等不同支付时机</li>
 * </ol>
 *
 * <h2>示例请求</h2>
 * <pre>{@code
 * POST /api/v1/payments
 * Authorization: Bearer {token}
 * Content-Type: application/json
 *
 * {
 *   "orderId": "1234567890123456789",
 *   "channel": "MOCK"
 * }
 * }</pre>
 */
@Data
public class CreatePaymentRequest {

    /**
     * 要支付的订单 ID（必填）。
     * Service 层会校验：订单存在 + 属于当前用户 + 状态为 WAIT_PAY。
     */
    @NotNull(message = "orderId 不能为空")
    @Positive(message = "orderId 必须是正整数")
    private Long orderId;

    /**
     * 支付渠道（必填）：MOCK / ALIPAY / WECHAT。
     * 当前实现只支持 MOCK，面试时说「已预留 ALIPAY/WECHAT 接口，
     * 接入时只需实现对应 PaymentChannelAdapter」。
     */
    @NotBlank(message = "channel 不能为空")
    private String channel;
}
