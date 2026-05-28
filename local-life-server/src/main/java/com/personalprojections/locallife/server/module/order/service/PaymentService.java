package com.personalprojections.locallife.server.module.order.service;

import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.OrderInfo;
import com.personalprojections.locallife.server.domain.entity.PaymentOrder;
import com.personalprojections.locallife.server.domain.mapper.PaymentOrderMapper;
import com.personalprojections.locallife.server.module.mq.constant.MqTopics;
import com.personalprojections.locallife.server.module.mq.event.PaymentSuccessEvent;
import com.personalprojections.locallife.server.module.mq.service.OutboxService;
import com.personalprojections.locallife.server.module.order.dto.CreatePaymentRequest;
import com.personalprojections.locallife.server.module.order.dto.PaymentCallbackRequest;
import com.personalprojections.locallife.server.module.order.dto.PaymentVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/**
 * 支付 Service，负责发起支付和处理支付回调。
 *
 * <h2>支付单与订单的关系（面试高频）</h2>
 * <p>一笔 order_info 对应 1~N 条 payment_order。每次用户点「去支付」都创建一条新的 payment_order。
 * <pre>
 *   用户下单 → order_info 创建（WAIT_PAY）
 *      ↓ 点「去支付」
 *   payment_order #1（PENDING）← 发起支付
 *      ↓ 支付超时
 *   payment_order #1（FAILED）
 *      ↓ 用户重新点「去支付」
 *   payment_order #2（PENDING）← 重新发起支付
 *      ↓ 支付成功（渠道回调）
 *   payment_order #2（SUCCESS）
 *   order_info（PAID）         ← 同步更新订单状态
 * </pre>
 *
 * <h2>Mock 支付流程</h2>
 * <p>当前实现 MOCK 渠道，不对接真实支付宝/微信。流程：
 * <ol>
 *   <li>客户端 POST /payments → 创建 payment_order（PENDING），返回 payUrl</li>
 *   <li>payUrl = "/api/v1/payments/mock-pay?paymentNo=xxx"（内部模拟接口）</li>
 *   <li>客户端访问 payUrl → 服务端内部触发支付回调逻辑（模拟渠道通知）</li>
 *   <li>支付回调处理完成后，order_info 状态变为 PAID</li>
 * </ol>
 *
 * <h2>支付回调幂等机制（面试高频）</h2>
 * <p>支付渠道在网络不稳定时会重复回调。防重复处理方案：
 * <ul>
 *   <li>第一道防线：{@code UPDATE WHERE pay_status='PENDING'}，
 *       第二次回调时 status 已是 SUCCESS，UPDATE affected=0，直接跳过（幂等）</li>
 *   <li>最终兜底：数据库唯一索引 uk_payment_channel_trade_no(channel, trade_no)，
 *       防止同一渠道+交易号被重复写入</li>
 * </ul>
 *
 * <h2>验签说明</h2>
 * <p>生产环境：用渠道公钥验证签名（RSA/MD5），防止伪造回调。
 * Mock 渠道：sign 固定为 "mock-sign"，直接字符串比对，满足面试演示需求。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    // =========================================================
    // 依赖注入
    // =========================================================

    private final PaymentOrderMapper paymentOrderMapper;
    private final OrderService orderService;
    private final OutboxService outboxService;
    private final BusinessMetrics businessMetrics;

    // =========================================================
    // 发起支付
    // =========================================================

    /**
     * 发起支付（POST /api/v1/payments）。
     *
     * <p>完整流程：
     * <ol>
     *   <li>查订单：验证存在 + 属于当前用户 + 状态为 WAIT_PAY</li>
     *   <li>创建 payment_order（PENDING），持久化到数据库</li>
     *   <li>生成支付跳转 URL（Mock 渠道 or 真实渠道）</li>
     *   <li>返回 PaymentVO 给客户端</li>
     * </ol>
     *
     * <p>注意：同一笔订单可以多次发起支付（上次 PENDING/FAILED 都可以再发）。
     * 每次发起都创建一条新的 payment_order，历史记录完整保留。
     *
     * @param request 发起支付请求
     * @return 支付 VO（含 payUrl 跳转地址）
     */
    @Transactional(rollbackFor = Exception.class)
    public PaymentVO createPayment(CreatePaymentRequest request) {
        Long userId = UserContext.getUserId();

        // ---- Step 1: 查订单，校验合法性 ----
        OrderInfo order = orderService.getOrderById(request.getOrderId());
        if (order == null || !order.getUserId().equals(userId)) {
            // 不区分「不存在」和「不属于当前用户」，防枚举攻击
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!"WAIT_PAY".equals(order.getOrderStatus())) {
            // 只有待支付的订单可以发起支付
            // 已支付 → 告知状态不合法；已关闭 → 同样
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
        }

        // ---- Step 2: 校验支付渠道 ----
        // 当前只支持 MOCK，后续实现 ALIPAY/WECHAT 时在此处扩展
        String channel = request.getChannel().toUpperCase();
        if (!"MOCK".equals(channel)) {
            // 生产环境：根据 channel 路由到对应的 PaymentChannelAdapter（策略模式）
            // 当前阶段面试时说「预留了渠道路由接口，接 Alipay/Wechat 时实现 Adapter 即可」
            throw new BizException(ErrorCode.SYS_BUSY); // 暂以 SYS_BUSY 占位
        }

        // ---- Step 3: 生成支付流水号 ----
        // paymentNo = 雪花 ID 字符串，全局唯一，传给渠道作为「商户订单号」
        // 渠道回调时原样返回，我方用它找到对应的 payment_order
        PaymentOrder paymentOrder = PaymentOrder.builder()
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .userId(userId)
                .payAmount(order.getOrderAmount())
                .payStatus("PENDING")
                .channel(channel)
                .build();

        // INSERT，MyBatis-Plus @TableId(ASSIGN_ID) 自动生成雪花 ID
        paymentOrderMapper.insert(paymentOrder);

        // 将雪花 ID 作为 paymentNo（后续更新到字段）
        String paymentNo = String.valueOf(paymentOrder.getId());
        paymentOrderMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<PaymentOrder>()
                        .eq(PaymentOrder::getId, paymentOrder.getId())
                        .set(PaymentOrder::getPaymentNo, paymentNo));
        paymentOrder.setPaymentNo(paymentNo);

        // ---- Step 4: 生成支付 URL ----
        // MOCK 渠道：内部 URL，访问后模拟渠道回调
        // 生产环境（Alipay）：调用支付宝 SDK 生成收银台 URL，前端跳转
        String payUrl = buildPayUrl(channel, paymentNo, order.getOrderAmount());

        log.info("[Payment] 创建支付单, userId={}, orderId={}, paymentNo={}, channel={}, payAmount={}分",
                userId, order.getId(), paymentNo, channel, order.getOrderAmount());

        return PaymentVO.builder()
                .paymentNo(paymentNo)
                .orderNo(order.getOrderNo())
                .payAmount(order.getOrderAmount())
                .channel(channel)
                .payUrl(payUrl)
                .build();
    }

    // =========================================================
    // Mock 支付触发（仅测试用）
    // =========================================================

    /**
     * Mock 支付触发接口（GET /api/v1/payments/mock-pay）。
     *
     * <p>仅用于测试/演示，访问此接口等同于「模拟用户在支付宝完成付款，
     * 支付宝向我方发送支付回调」。
     *
     * <p>触发逻辑：构造一个 Mock 回调请求，直接调用 handleCallback。
     * 这样 handleCallback 的全部逻辑（验签、金额核对、幂等）都会被真实走一遍。
     *
     * @param paymentNo 支付流水号
     */
    public void triggerMockPay(String paymentNo) {
        // 查 payment_order，获取金额等信息
        PaymentOrder paymentOrder = paymentOrderMapper.selectByPaymentNo(paymentNo);
        if (paymentOrder == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 构造 Mock 回调请求（模拟支付宝/微信的回调报文）
        PaymentCallbackRequest mockCallback = new PaymentCallbackRequest();
        mockCallback.setPaymentNo(paymentNo);
        mockCallback.setTradeNo("MOCK_TRADE_" + paymentNo); // Mock 渠道流水号
        mockCallback.setPaidAmount(paymentOrder.getPayAmount()); // 原价支付（无篡改）
        mockCallback.setChannel("MOCK");
        mockCallback.setPaidAt(LocalDateTime.now()); // 使用当前时间作为「渠道时间」
        mockCallback.setSign("mock-sign"); // Mock 签名

        log.info("[Payment] Mock 支付触发, paymentNo={}", paymentNo);
        handleCallback(mockCallback);
    }

    // =========================================================
    // 支付回调处理
    // =========================================================

    /**
     * 处理支付渠道回调（POST /api/v1/payments/callback）。
     *
     * <p>此接口由支付渠道服务器主动调用，不是用户调用，因此：
     * <ul>
     *   <li>不在 JWT 鉴权白名单也不在鉴权链路中（WebMvcConfig 加白名单）</li>
     *   <li>通过「验签」替代 JWT 鉴权，确认请求来自合法渠道</li>
     * </ul>
     *
     * <p>核心处理步骤：
     * <ol>
     *   <li>根据 paymentNo 查找 payment_order（我方记录）</li>
     *   <li>验签（MOCK：比对 "mock-sign"；生产：RSA 验签）</li>
     *   <li>金额核对：paidAmount == payment_order.payAmount</li>
     *   <li>幂等更新：UPDATE WHERE pay_status='PENDING'（防重复回调）</li>
     *   <li>同步更新 order_info 状态为 PAID</li>
     * </ol>
     *
     * <p>返回 void：成功时调用方（Mock 触发接口）直接返回 200 OK；
     * 生产环境应返回渠道要求的特定响应（如 Alipay 需要返回 "success" 字符串）。
     *
     * @param callback 回调请求体
     */
    @Transactional(rollbackFor = Exception.class)
    public void handleCallback(PaymentCallbackRequest callback) {
        long startMs = System.currentTimeMillis();
        log.info("[Payment] 收到支付回调, paymentNo={}, channel={}, tradeNo={}",
                callback.getPaymentNo(), callback.getChannel(), callback.getTradeNo());

        // ---- Step 1: 查 payment_order ----
        PaymentOrder paymentOrder = paymentOrderMapper.selectByPaymentNo(callback.getPaymentNo());
        if (paymentOrder == null) {
            // paymentNo 不存在，拒绝处理（可能是伪造请求）
            log.warn("[Payment] 回调中 paymentNo 不存在: {}", callback.getPaymentNo());
            throw new BizException(ErrorCode.PAYMENT_VERIFY_FAILED);
        }

        // ---- Step 2: 验签 ----
        // 验证请求确实来自合法的支付渠道，防止伪造回调
        verifySign(callback);

        // ---- Step 3: 金额核对 ----
        // 渠道实际到账金额必须与我方记录的应付金额一致
        // 不一致说明支付金额被篡改，拒绝处理
        if (!callback.getPaidAmount().equals(paymentOrder.getPayAmount())) {
            log.error("[Payment] 金额不符! paymentNo={}, 应付={}分, 实付={}分",
                    callback.getPaymentNo(), paymentOrder.getPayAmount(), callback.getPaidAmount());
            throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        // ---- Step 4: 幂等更新支付单状态 ----
        // WHERE pay_status='PENDING'：只有 PENDING 状态才处理，SUCCESS 直接跳过
        // 这是防止重复回调的核心逻辑
        String callbackBodyJson = buildCallbackBodyJson(callback); // 原始报文转 JSON 字符串
        int affected = paymentOrderMapper.updateStatusOnSuccess(
                paymentOrder.getId(),
                callback.getTradeNo(),
                callback.getPaidAmount(),
                callbackBodyJson,
                callback.getPaidAt() != null ? callback.getPaidAt() : LocalDateTime.now());

        if (affected == 0) {
            // affected = 0 说明 pay_status 已非 PENDING（已处理过），幂等跳过
            log.info("[Payment] 回调幂等跳过：paymentNo={} 已处理", callback.getPaymentNo());
            return; // 直接返回，不继续更新订单状态（避免重复）
        }

        // ---- Step 5: 同步更新 order_info 状态 ----
        // payment_order 已 SUCCESS → order_info 从 WAIT_PAY → PAID
        // 同在一个事务中，任何一步失败都会整体回滚
        LocalDateTime payAt = callback.getPaidAt() != null
                ? callback.getPaidAt()
                : LocalDateTime.now();
        // paymentOrder 冗余存储了 userId，可直接传入作为分片键，避免 ShardingSphere 广播查询
        boolean orderUpdated = orderService.markOrderAsPaid(paymentOrder.getOrderId(), paymentOrder.getUserId(), payAt);

        if (!orderUpdated) {
            // 订单状态已非 WAIT_PAY（理论上不应出现，因为和 payment_order 同步更新）
            // 记录 WARN 日志，但不抛异常（payment_order 已成功，优先保证支付记录完整性）
            log.warn("[Payment] 支付单已成功但订单状态非 WAIT_PAY, orderId={}", paymentOrder.getOrderId());
        }

        // ---- Step 6: 写本地消息表（与上面的 DB 操作同一事务）----
        // Transactional Outbox：在同一事务内写 outbox_message（PENDING），
        // 事务提交后 Relay 任务负责异步投递到 RocketMQ。
        // 保证：payment_order / order_info 更新成功 <=> outbox_message 写入成功（原子）
        // 即使后续 RocketMQ 不可用，消息也不会丢失，Relay 任务会在 MQ 恢复后重试投递。
        OrderInfo orderInfo = orderService.getOrderById(paymentOrder.getOrderId());
        PaymentSuccessEvent event = PaymentSuccessEvent.builder()
                .eventId(paymentOrder.getId() + "_paid") // 全局唯一事件 ID
                .paymentOrderId(paymentOrder.getId())
                .paymentNo(paymentOrder.getPaymentNo())
                .orderId(paymentOrder.getOrderId())
                .orderNo(paymentOrder.getOrderNo())
                .userId(paymentOrder.getUserId())
                .shopId(orderInfo != null ? orderInfo.getShopId() : null)
                .paidAmount(callback.getPaidAmount())
                .channel(callback.getChannel())
                .tradeNo(callback.getTradeNo())
                .paidAt(payAt)
                .eventAt(LocalDateTime.now())
                .build();
        outboxService.saveToOutbox(event, MqTopics.PAYMENT_SUCCESS_TOPIC, MqTopics.TAG_PAYMENT_SUCCESS);

        log.info("[Payment] 支付成功处理完毕（含 Outbox 写入）, paymentNo={}, orderId={}, paidAmount={}分",
                callback.getPaymentNo(), paymentOrder.getOrderId(), callback.getPaidAmount());

        // Metrics：记录回调处理耗时和成功次数（用于监控支付渠道稳定性和 P99 延迟）
        long costMs = System.currentTimeMillis() - startMs;
        businessMetrics.recordPaymentCallback(costMs, callback.getChannel(), true);
        businessMetrics.recordPaymentSuccess(callback.getChannel());
    }

    // =========================================================
    // 私有工具方法
    // =========================================================

    /**
     * 验证支付渠道的签名。
     *
     * <p>当前 Mock 渠道：sign 固定为 "mock-sign"，直接字符串比对。
     * 生产环境替换方案：
     * <ul>
     *   <li>支付宝：将回调参数按 ASCII 排序拼接，用支付宝公钥做 RSA2（SHA256withRSA）验签</li>
     *   <li>微信：对回调 XML 做 MD5(key=APIKey) 或 HMAC-SHA256 验签</li>
     * </ul>
     * 验签逻辑通过接口/策略模式隔离：{@code PaymentChannelAdapter.verify(callback)}
     *
     * @param callback 回调请求体
     * @throws BizException PAYMENT_VERIFY_FAILED 验签失败
     */
    private void verifySign(PaymentCallbackRequest callback) {
        // MOCK 渠道：固定签名 "mock-sign"
        if ("MOCK".equals(callback.getChannel())) {
            if (!"mock-sign".equals(callback.getSign())) {
                log.warn("[Payment] Mock 渠道验签失败, sign={}", callback.getSign());
                throw new BizException(ErrorCode.PAYMENT_VERIFY_FAILED);
            }
            return;
        }
        // 【预留扩展点】生产渠道在此根据 channel 路由到对应的验签逻辑
        // ALIPAY: AlipaySignVerifier.verify(callback)
        // WECHAT: WechatSignVerifier.verify(callback)
        throw new BizException(ErrorCode.PAYMENT_VERIFY_FAILED);
    }

    /**
     * 生成支付跳转 URL。
     *
     * <p>Mock 渠道：内部测试接口 URL，前端访问后自动触发支付成功回调。
     * Alipay：调用支付宝 SDK 生成 form HTML（PC 端）或收银台 URL（移动端）。
     * Wechat：调用微信支付 API 获取 code_url（Native 扫码支付）。
     *
     * @param channel   支付渠道
     * @param paymentNo 支付流水号
     * @param payAmount 应付金额（分）
     * @return 支付跳转 URL
     */
    private String buildPayUrl(String channel, String paymentNo, Integer payAmount) {
        if ("MOCK".equals(channel)) {
            // 访问此 URL = 模拟用户完成支付 + 渠道发回调
            return "/api/v1/payments/mock-pay?paymentNo=" + paymentNo;
        }
        // 【预留扩展点】生产渠道 URL 生成
        throw new BizException(ErrorCode.SYS_BUSY);
    }

    /**
     * 将回调请求体序列化为 JSON 字符串，存入 payment_order.callback_body。
     *
     * <p>存储原始回调报文的用途：
     * <ul>
     *   <li>排查问题：出现争议时，调取原始报文核对</li>
     *   <li>对账：批量导出与渠道账单对比</li>
     *   <li>审计：法务要求保留原始支付凭证</li>
     * </ul>
     *
     * <p>当前简化实现：手动拼接 JSON 字符串（避免额外的 JSON 库依赖）。
     * 生产环境使用 ObjectMapper.writeValueAsString(callback)。
     *
     * @param callback 回调请求体
     * @return JSON 字符串
     */
    private String buildCallbackBodyJson(PaymentCallbackRequest callback) {
        // 简化拼接（生产用 Jackson ObjectMapper 更好）
        return String.format(
                "{\"paymentNo\":\"%s\",\"tradeNo\":\"%s\",\"paidAmount\":%d," +
                "\"channel\":\"%s\",\"paidAt\":\"%s\"}",
                callback.getPaymentNo(),
                callback.getTradeNo(),
                callback.getPaidAmount(),
                callback.getChannel(),
                callback.getPaidAt() != null ? callback.getPaidAt().toString() : "null"
        );
    }
}
