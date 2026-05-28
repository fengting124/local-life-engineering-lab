package com.personalprojections.locallife.server.module.order.controller;

import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.order.dto.CreatePaymentRequest;
import com.personalprojections.locallife.server.module.order.dto.PaymentCallbackRequest;
import com.personalprojections.locallife.server.module.order.dto.PaymentVO;
import com.personalprojections.locallife.server.module.order.service.PaymentService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

/**
 * 支付 Controller。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   POST /api/v1/payments              发起支付（需登录）
 *   POST /api/v1/payments/callback     支付回调（不需登录，需验签）
 *   GET  /api/v1/payments/mock-pay     Mock 支付触发（测试用，不需登录）
 * </pre>
 *
 * <h2>关于 /callback 接口的特殊性</h2>
 * <p>/callback 由支付渠道服务器调用，不是用户调用，因此：
 * <ul>
 *   <li>不需要 JWT Token（渠道服务器没有用户 Token）</li>
 *   <li>在 WebMvcConfig 白名单中加入此路径（跳过鉴权拦截器）</li>
 *   <li>改用「验签」机制确认请求来自合法渠道（非验签请求拒绝处理）</li>
 * </ul>
 *
 * <h2>关于 /mock-pay 接口</h2>
 * <p>仅用于开发测试，生产环境应该关闭（通过 @Profile 或配置开关控制）。
 * 访问此接口 = 模拟「用户在支付宝完成付款 + 支付宝向我方发送成功通知」。
 * 这样可以在没有真实支付环境的情况下，完整演示整个支付链路。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PaymentController {

    private final PaymentService paymentService;

    // =========================================================
    // 发起支付（需登录）
    // =========================================================

    /**
     * 发起支付（POST /api/v1/payments，需登录）。
     *
     * <p>用户确认下单后，点击「去支付」触发此接口。
     * 服务端创建 payment_order（PENDING），返回支付跳转 URL。
     * 前端根据 channel 字段决定跳转行为（跳支付宝/微信/Mock测试）。
     *
     * <p>请求示例：
     * <pre>{@code
     * POST /api/v1/payments
     * Authorization: Bearer {token}
     *
     * {
     *   "orderId": "1234567890123456789",
     *   "channel": "MOCK"
     * }
     * }</pre>
     *
     * <p>响应示例：
     * <pre>{@code
     * {
     *   "code": "OK",
     *   "data": {
     *     "paymentNo": "9876543210987654321",
     *     "orderNo": "1234567890123456789",
     *     "payAmount": 8900,
     *     "channel": "MOCK",
     *     "payUrl": "/api/v1/payments/mock-pay?paymentNo=9876543210987654321"
     *   }
     * }
     * }</pre>
     *
     * @param request 发起支付请求体（orderId + channel）
     * @return 支付 VO（含 payUrl）
     */
    @PostMapping("/api/v1/payments")
    public Result<PaymentVO> createPayment(@Valid @RequestBody CreatePaymentRequest request) {
        PaymentVO vo = paymentService.createPayment(request);
        return Result.ok(vo);
    }

    // =========================================================
    // 支付回调（不需登录，需验签）
    // =========================================================

    /**
     * 支付渠道回调接口（POST /api/v1/payments/callback）。
     *
     * <p>此接口由支付宝/微信/Mock 渠道服务器主动调用，通知「某笔支付已成功」。
     * 前端不调用此接口，也不需要登录。
     *
     * <p>处理完成后，服务端应向渠道返回特定响应：
     * <ul>
     *   <li>支付宝：返回字符串 "success"（否则支付宝会重试回调）</li>
     *   <li>微信：返回 XML {@code <return_code>SUCCESS</return_code>}</li>
     *   <li>Mock：返回我们统一的 Result.ok() 即可</li>
     * </ul>
     * 当前 Mock 渠道统一返回 Result.ok()，面试时说清楚「生产时按渠道要求响应」即可。
     *
     * <h2>为什么回调接口特别重要（面试高频）</h2>
     * <p>支付成功后，渠道通过回调通知我方更新订单状态，这是订单变为「已支付」的唯一触发点。
     * 如果回调丢失或处理失败，订单永远是 WAIT_PAY，用户支付了钱但订单没变 PAID。
     * 所以回调处理必须：
     * <ol>
     *   <li>幂等（防止重复处理同一笔支付）</li>
     *   <li>验签（防止伪造回调）</li>
     *   <li>金额核对（防止金额篡改）</li>
     * </ol>
     *
     * @param callback 回调请求体
     * @return 处理结果（向渠道确认已接收）
     */
    @PostMapping("/api/v1/payments/callback")
    public Result<Void> handleCallback(@RequestBody PaymentCallbackRequest callback) {
        paymentService.handleCallback(callback);
        // 真实支付宝：需要返回 "success"（纯文本）
        // 真实微信：需要返回 XML 格式的成功标志
        // Mock 渠道：统一 Result.ok() 即可
        return Result.ok(null);
    }

    // =========================================================
    // Mock 支付触发（测试用，不需登录）
    // =========================================================

    /**
     * Mock 支付触发接口（GET /api/v1/payments/mock-pay，不需登录）。
     *
     * <p>访问此接口 = 模拟「用户在支付宝完成付款」。
     * 服务端内部构造一个回调请求，走完整个 handleCallback 流程。
     *
     * <p>使用流程：
     * <ol>
     *   <li>POST /payments → 收到 payUrl = "/api/v1/payments/mock-pay?paymentNo=xxx"</li>
     *   <li>GET /payments/mock-pay?paymentNo=xxx → 触发支付成功</li>
     *   <li>GET /orders/{orderId} → 查看订单状态已变为 PAID</li>
     * </ol>
     *
     * @param paymentNo 支付流水号（Query 参数）
     * @return 触发结果
     */
    @GetMapping("/api/v1/payments/mock-pay")
    public Result<Void> triggerMockPay(@RequestParam String paymentNo) {
        paymentService.triggerMockPay(paymentNo);
        return Result.ok(null);
    }
}
