package com.personalprojections.locallife.server.module.order.dto;

import lombok.Data;

import java.time.LocalDateTime;

/**
 * 支付回调请求体（POST /api/v1/payments/callback）。
 *
 * <h2>此接口由支付渠道（支付宝/微信/Mock）主动调用，不是用户主动调用</h2>
 * <p>用户在支付渠道完成付款后，渠道服务器会向我方服务器发送 POST 请求，
 * 通知「某笔支付成功了」。这个过程叫「支付回调」（也叫「支付通知」）。
 *
 * <h2>安全机制（为什么不能相信任何人都能调这个接口）</h2>
 * <p>此接口没有 JWT 鉴权（支付渠道没有登录 Token），
 * 但必须通过「验签」来确认请求来自合法的支付渠道：
 * <ol>
 *   <li>渠道用私钥对回调参数做 RSA/MD5 签名，附在请求中（sign 字段）</li>
 *   <li>我方用渠道公钥重新计算签名，与 sign 比对</li>
 *   <li>签名不一致 → PAYMENT_VERIFY_FAILED，拒绝处理</li>
 * </ol>
 * 当前 Mock 渠道简化处理：sign 固定为 "mock-sign"，服务端直接放行。
 *
 * <h2>幂等性（为什么同一笔支付可能收到多次回调）</h2>
 * <p>网络不稳定时，渠道在等待我方「200 OK」响应超时后，会重试回调（通常重试 3~5 次）。
 * 我方必须保证：收到同一笔支付的第 N 次回调时，效果与第 1 次相同（不重复计费）。
 * 幂等保障：
 *   1. {@code UPDATE payment_order SET status='SUCCESS' WHERE id=? AND status='PENDING'}
 *      第二次回调时 status 已是 SUCCESS，UPDATE 影响行数为 0，直接返回成功
 *   2. 数据库唯一索引 uk_payment_channel_trade_no(channel, trade_no)
 *      兜底防止重复 INSERT（如果业务逻辑有漏洞时的最后防线）
 */
@Data
public class PaymentCallbackRequest {

    /**
     * 我方支付流水号（商户订单号），由我方在发起支付时生成并传给渠道。
     * 渠道回调时原样返回，用于找到对应的 payment_order 记录。
     */
    private String paymentNo;

    /**
     * 渠道侧的交易流水号，全局唯一（支付宝/微信生成）。
     * 与 channel 组成唯一键：uk_payment_channel_trade_no(channel, trade_no)。
     * 用于幂等判重：同一笔支付第二次回调时，此字段相同，UPDATE WHERE PENDING 不命中，直接跳过。
     */
    private String tradeNo;

    /**
     * 渠道返回的实际支付金额（分）。
     * 必须与 payment_order.pay_amount（即 order_info.order_amount）一致，
     * 不一致说明支付金额被篡改，返回 PAYMENT_AMOUNT_MISMATCH。
     *
     * <p>这是「防止支付金额篡改攻击」的核心校验：
     * 攻击者如果修改了请求体中的 paidAmount（比如把 9900 改成 100），
     * 服务端一比对，发现与订单金额不符，直接拒绝。
     */
    private Integer paidAmount;

    /**
     * 支付渠道（MOCK / ALIPAY / WECHAT），与 tradeNo 组合成唯一键。
     * 不同渠道的 tradeNo 可能相同（都是数字串），加上 channel 才能全局唯一。
     */
    private String channel;

    /**
     * 渠道侧的支付成功时间（不用本地 NOW()）。
     * 使用渠道时间的原因：与渠道账单的时间戳完全一致，对账时无歧义。
     * 本地时间可能比渠道时间晚几秒（网络延迟），用渠道时间更准确。
     */
    private LocalDateTime paidAt;

    /**
     * 渠道签名（用于验签）。
     * 真实场景：RSA 签名的 Base64 字符串，或 MD5(参数排序 + 密钥) 的十六进制串。
     * Mock 渠道：固定为 "mock-sign"，服务端直接比对此字符串。
     */
    private String sign;
}
