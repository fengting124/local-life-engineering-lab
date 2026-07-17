package com.personalprojections.locallife.server.module.order.service;

import com.baomidou.mybatisplus.core.toolkit.IdWorker;
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
 * Payment initiation and callback processing.
 *
 * <p>Multiple payment orders may exist for one business order. The first callback that wins the
 * order {@code WAIT_PAY -> PAID} CAS becomes the normal SUCCESS payment. A different payment that
 * was also paid externally is preserved as {@code DUPLICATE_PAID} and cannot emit a second normal
 * payment-success event.</p>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PaymentService {

    private final PaymentOrderMapper paymentOrderMapper;
    private final OrderService orderService;
    private final OutboxService outboxService;
    private final BusinessMetrics businessMetrics;

    @Transactional(rollbackFor = Exception.class)
    public PaymentVO createPayment(CreatePaymentRequest request) {
        Long userId = UserContext.getUserId();
        OrderInfo order = orderService.getOrderById(request.getOrderId());
        if (order == null || !order.getUserId().equals(userId)) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        if (!"WAIT_PAY".equals(order.getOrderStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
        }

        String channel = request.getChannel().toUpperCase();
        if (!"MOCK".equals(channel)) {
            throw new BizException(ErrorCode.SYS_BUSY);
        }

        Long paymentId = IdWorker.getId();
        String paymentNo = String.valueOf(paymentId);
        PaymentOrder paymentOrder = PaymentOrder.builder()
                .id(paymentId)
                .paymentNo(paymentNo)
                .orderId(order.getId())
                .orderNo(order.getOrderNo())
                .userId(userId)
                .payAmount(order.getOrderAmount())
                .payStatus("PENDING")
                .channel(channel)
                .build();
        paymentOrderMapper.insert(paymentOrder);

        return PaymentVO.builder()
                .paymentNo(paymentNo)
                .orderNo(order.getOrderNo())
                .payAmount(order.getOrderAmount())
                .channel(channel)
                .payUrl(buildPayUrl(channel, paymentNo, order.getOrderAmount()))
                .build();
    }

    @Transactional(rollbackFor = Exception.class)
    public void triggerMockPay(String paymentNo) {
        PaymentOrder paymentOrder = paymentOrderMapper.selectByPaymentNo(paymentNo);
        if (paymentOrder == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }

        PaymentCallbackRequest callback = new PaymentCallbackRequest();
        callback.setPaymentNo(paymentNo);
        callback.setTradeNo("MOCK_TRADE_" + paymentNo);
        callback.setPaidAmount(paymentOrder.getPayAmount());
        callback.setChannel("MOCK");
        callback.setPaidAt(LocalDateTime.now());
        callback.setSign("mock-sign");
        handleCallback(callback);
    }

    @Transactional(rollbackFor = Exception.class)
    public void handleCallback(PaymentCallbackRequest callback) {
        long startMs = System.currentTimeMillis();
        PaymentOrder paymentOrder = paymentOrderMapper.selectByPaymentNo(callback.getPaymentNo());
        if (paymentOrder == null) {
            throw new BizException(ErrorCode.PAYMENT_VERIFY_FAILED);
        }

        verifySign(callback);
        if (!callback.getPaidAmount().equals(paymentOrder.getPayAmount())) {
            throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }

        LocalDateTime paidAt = callback.getPaidAt() == null
                ? LocalDateTime.now()
                : callback.getPaidAt();
        int claimed = paymentOrderMapper.updateStatusOnSuccess(
                paymentOrder.getId(),
                callback.getTradeNo(),
                callback.getPaidAmount(),
                buildCallbackBodyJson(callback),
                paidAt);
        if (claimed == 0) {
            log.info("[Payment] callback already processed: paymentNo={}", callback.getPaymentNo());
            return;
        }

        boolean orderUpdated = orderService.markOrderAsPaid(
                paymentOrder.getOrderId(),
                paymentOrder.getUserId(),
                paidAt);
        if (!orderUpdated) {
            int reclassified = paymentOrderMapper.markAsDuplicatePaid(paymentOrder.getId());
            if (reclassified != 1) {
                throw new IllegalStateException("Failed to persist duplicate paid state");
            }
            businessMetrics.recordPaymentCallback(
                    System.currentTimeMillis() - startMs,
                    callback.getChannel(),
                    true);
            log.error("[Payment] external payment succeeded after order was already closed or paid: " +
                            "paymentNo={}, orderId={}, status=DUPLICATE_PAID",
                    callback.getPaymentNo(), paymentOrder.getOrderId());
            return;
        }

        OrderInfo orderInfo = orderService.getOrderById(paymentOrder.getOrderId());
        PaymentSuccessEvent event = PaymentSuccessEvent.builder()
                .eventId(paymentOrder.getId() + "_paid")
                .paymentOrderId(paymentOrder.getId())
                .paymentNo(paymentOrder.getPaymentNo())
                .orderId(paymentOrder.getOrderId())
                .orderNo(paymentOrder.getOrderNo())
                .userId(paymentOrder.getUserId())
                .shopId(orderInfo == null ? null : orderInfo.getShopId())
                .paidAmount(callback.getPaidAmount())
                .channel(callback.getChannel())
                .tradeNo(callback.getTradeNo())
                .paidAt(paidAt)
                .eventAt(LocalDateTime.now())
                .build();
        outboxService.saveToOutbox(
                event,
                event.getEventId(),
                MqTopics.PAYMENT_SUCCESS_TOPIC,
                MqTopics.TAG_PAYMENT_SUCCESS);

        businessMetrics.recordPaymentCallback(
                System.currentTimeMillis() - startMs,
                callback.getChannel(),
                true);
        businessMetrics.recordPaymentSuccess(callback.getChannel());
        log.info("[Payment] success committed: paymentNo={}, orderId={}",
                callback.getPaymentNo(), paymentOrder.getOrderId());
    }

    private void verifySign(PaymentCallbackRequest callback) {
        if ("MOCK".equals(callback.getChannel())) {
            if (!"mock-sign".equals(callback.getSign())) {
                throw new BizException(ErrorCode.PAYMENT_VERIFY_FAILED);
            }
            return;
        }
        throw new BizException(ErrorCode.PAYMENT_VERIFY_FAILED);
    }

    private String buildPayUrl(String channel, String paymentNo, Integer payAmount) {
        if ("MOCK".equals(channel)) {
            return "/api/v1/payments/mock-pay?paymentNo=" + paymentNo;
        }
        throw new BizException(ErrorCode.SYS_BUSY);
    }

    private String buildCallbackBodyJson(PaymentCallbackRequest callback) {
        return String.format(
                "{\"paymentNo\":\"%s\",\"tradeNo\":\"%s\",\"paidAmount\":%d," +
                        "\"channel\":\"%s\",\"paidAt\":\"%s\"}",
                callback.getPaymentNo(),
                callback.getTradeNo(),
                callback.getPaidAmount(),
                callback.getChannel(),
                callback.getPaidAt() == null ? "null" : callback.getPaidAt().toString());
    }
}
