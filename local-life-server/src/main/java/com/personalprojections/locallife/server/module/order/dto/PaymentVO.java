package com.personalprojections.locallife.server.module.order.dto;

import lombok.Builder;
import lombok.Data;

/**
 * 发起支付响应 VO（POST /api/v1/payments 的响应体）。
 *
 * <h2>这个 VO 返回什么</h2>
 * <p>用户点击「去支付」，服务端创建 PaymentOrder 并返回：
 * <ul>
 *   <li>paymentNo：我方支付流水号，前端需要保存，支付状态轮询时用</li>
 *   <li>payUrl：跳转到支付渠道的 URL（真实场景为支付宝/微信的收银台 URL）</li>
 *   <li>payAmount：应付金额（分），前端展示给用户确认</li>
 *   <li>channel：支付渠道，前端根据此字段决定跳转行为</li>
 * </ul>
 *
 * <h2>Mock 渠道说明</h2>
 * <p>当前实现使用 MOCK 渠道，payUrl 是一个内部测试 URL，
 * 访问后直接触发支付回调（模拟支付宝/微信的成功回调）。
 * 生产环境替换为真实支付宝/微信 URL 即可，VO 结构不变。
 *
 * <h2>支付流程图</h2>
 * <pre>
 *   前端 → POST /payments → 收到 payUrl
 *      ↓
 *   前端跳转 payUrl（支付宝/微信收银台）
 *      ↓（用户完成支付）
 *   渠道回调 → POST /payments/callback → 服务端更新订单状态
 *      ↓
 *   前端轮询订单状态 → GET /orders/{orderId} → 检查 orderStatus == PAID
 * </pre>
 */
@Data
@Builder
public class PaymentVO {

    /**
     * 我方系统的支付流水号（paymentNo），全局唯一。
     * 前端可以用此号查询支付状态，或展示给客服核对。
     * String 类型，因为底层是雪花 ID Long，前端用 String 接收安全。
     */
    private String paymentNo;

    /**
     * 关联的订单号（orderNo，业务流水号）。
     * 方便前端在支付页面展示「正在支付订单：xxxxxxx」。
     */
    private String orderNo;

    /**
     * 应付金额（分）。
     * 前端展示给用户：「需支付 ¥89.00」（分 ÷ 100 转元）。
     */
    private Integer payAmount;

    /**
     * 支付渠道：MOCK / ALIPAY / WECHAT。
     * 前端根据渠道决定跳转方式：
     *   ALIPAY → window.location.href = payUrl（网页支付）
     *   WECHAT → 调用微信 SDK（Native / JSAPI）
     *   MOCK   → 直接调用 payUrl（测试用）
     */
    private String channel;

    /**
     * 支付跳转 URL。
     * MOCK 渠道：内部 URL，访问后直接触发支付成功回调（测试用）
     * ALIPAY：支付宝收银台 URL（form 表单跳转或直接 GET）
     * WECHAT：微信支付二维码 URL（Native 支付）
     */
    private String payUrl;
}
