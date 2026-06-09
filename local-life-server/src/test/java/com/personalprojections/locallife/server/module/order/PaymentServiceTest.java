package com.personalprojections.locallife.server.module.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.OrderInfo;
import com.personalprojections.locallife.server.domain.entity.PaymentOrder;
import com.personalprojections.locallife.server.domain.mapper.PaymentOrderMapper;
import com.personalprojections.locallife.server.module.mq.service.OutboxService;
import com.personalprojections.locallife.server.module.order.dto.CreatePaymentRequest;
import com.personalprojections.locallife.server.module.order.dto.PaymentCallbackRequest;
import com.personalprojections.locallife.server.module.order.dto.PaymentVO;
import com.personalprojections.locallife.server.module.order.service.OrderService;
import com.personalprojections.locallife.server.module.order.service.PaymentService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link PaymentService} 单元测试。
 *
 * <h2>测试重点</h2>
 * <ul>
 *   <li>createPayment：订单归属校验、状态校验、渠道校验</li>
 *   <li>handleCallback：验签失败、金额篡改检测、幂等跳过（已处理的回调不重复计费）、成功路径</li>
 *   <li>安全核心：回调接口不依赖 JWT 而依赖验签——任何人都可以调此接口，
 *       只有带正确签名的请求才被接受</li>
 * </ul>
 */
@ExtendWith(MockitoExtension.class)
class PaymentServiceTest {

    @Mock private PaymentOrderMapper paymentOrderMapper;
    @Mock private OrderService orderService;
    @Mock private OutboxService outboxService;
    @Mock private BusinessMetrics businessMetrics;

    @InjectMocks
    private PaymentService paymentService;

    private static final long USER_ID       = 8888L;
    private static final long ORDER_ID      = 100L;
    private static final long PAYMENT_ID    = 200L;
    private static final String PAYMENT_NO  = "200";
    private static final int    PAY_AMOUNT  = 9900;  // 99 元

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // LambdaUpdateWrapper for PaymentOrder resolves field→column at build time,
        // even when the mapper is a mock. MP 3.5.7 requires a non-null MapperBuilderAssistant.
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, PaymentOrder.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.set(LoginUserDTO.builder().userId(USER_ID).status("ENABLED").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // =========================================================
    // 1. createPayment — 下单前的合法性校验
    // =========================================================

    @Test
    void createPayment_orderNotFound_throwsOrderNotFound() {
        when(orderService.getOrderById(ORDER_ID)).thenReturn(null);

        assertThatThrownBy(() -> paymentService.createPayment(paymentRequest()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    void createPayment_orderBelongsToOtherUser_throwsOrderNotFound() {
        // 不区分「不存在」和「不属于当前用户」——防枚举攻击
        OrderInfo otherUsersOrder = order("WAIT_PAY");
        otherUsersOrder.setUserId(USER_ID + 1);  // 别人的订单
        when(orderService.getOrderById(ORDER_ID)).thenReturn(otherUsersOrder);

        assertThatThrownBy(() -> paymentService.createPayment(paymentRequest()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    void createPayment_orderAlreadyPaid_throwsOrderStatusIllegal() {
        when(orderService.getOrderById(ORDER_ID)).thenReturn(order("PAID"));

        assertThatThrownBy(() -> paymentService.createPayment(paymentRequest()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_STATUS_ILLEGAL));
    }

    @Test
    void createPayment_unsupportedChannel_throwsSysBusy() {
        when(orderService.getOrderById(ORDER_ID)).thenReturn(order("WAIT_PAY"));
        CreatePaymentRequest req = paymentRequest();
        req.setChannel("ALIPAY");  // 还没接入真实渠道

        assertThatThrownBy(() -> paymentService.createPayment(req))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SYS_BUSY));
    }

    @Test
    void createPayment_success_returnsPaymentVoWithMockPayUrl() {
        when(orderService.getOrderById(ORDER_ID)).thenReturn(order("WAIT_PAY"));
        // Cast resolves BaseMapper#insert(T) vs insert(Collection<T>) ambiguity in MP 3.5.7
        doAnswer(inv -> {
            PaymentOrder po = (PaymentOrder) inv.getArgument(0);
            po.setId(PAYMENT_ID);
            return 1;
        }).when(paymentOrderMapper).insert((PaymentOrder) any());
        when(paymentOrderMapper.update(any(), any())).thenReturn(1);

        PaymentVO vo = paymentService.createPayment(paymentRequest());

        assertThat(vo.getPaymentNo()).isEqualTo(String.valueOf(PAYMENT_ID));
        assertThat(vo.getPayUrl())
                .as("Mock 渠道的 payUrl 格式应为 /api/v1/payments/mock-pay?paymentNo=xxx")
                .startsWith("/api/v1/payments/mock-pay?paymentNo=");
        assertThat(vo.getPayAmount()).isEqualTo(PAY_AMOUNT);
    }

    // =========================================================
    // 2. handleCallback — 验签、金额校验、幂等、成功路径
    //
    // 关键安全模型：
    // - 此接口无 JWT 鉴权（支付渠道没有 Token）
    // - 安全靠「验签」：篡改任何字段 → 签名不匹配 → 拒绝
    // - 不存在的 paymentNo → 拒绝（防止伪造）
    // =========================================================

    @Test
    void handleCallback_paymentNoNotFound_throwsVerifyFailed() {
        // 不存在的 paymentNo → 可能是伪造请求
        when(paymentOrderMapper.selectByPaymentNo(PAYMENT_NO)).thenReturn(null);

        assertThatThrownBy(() -> paymentService.handleCallback(callback("mock-sign", PAY_AMOUNT)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_VERIFY_FAILED));
    }

    @Test
    void handleCallback_wrongSign_throwsVerifyFailed() {
        // Mock 渠道的 sign 必须是 "mock-sign"，其他值一律拒绝
        when(paymentOrderMapper.selectByPaymentNo(PAYMENT_NO)).thenReturn(paymentOrder(PAY_AMOUNT));

        assertThatThrownBy(() -> paymentService.handleCallback(callback("wrong-sign", PAY_AMOUNT)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_VERIFY_FAILED));
    }

    @Test
    void handleCallback_paidAmountMismatch_throwsAmountMismatch() {
        // 支付金额被篡改（如用 1 分钱完成了 9900 分的支付）
        when(paymentOrderMapper.selectByPaymentNo(PAYMENT_NO)).thenReturn(paymentOrder(PAY_AMOUNT));

        // paidAmount=1 vs paymentOrder.payAmount=9900
        assertThatThrownBy(() -> paymentService.handleCallback(callback("mock-sign", 1)))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.PAYMENT_AMOUNT_MISMATCH));
    }

    @Test
    void handleCallback_alreadyProcessed_idempotentReturn_doesNotDoubleCount() {
        // 渠道重复回调：updateStatusOnSuccess WHERE pay_status='PENDING' 不命中（affected=0）
        // 应直接返回，不再更新订单状态、不再写 Outbox——防止重复计费
        when(paymentOrderMapper.selectByPaymentNo(PAYMENT_NO)).thenReturn(paymentOrder(PAY_AMOUNT));
        when(paymentOrderMapper.updateStatusOnSuccess(any(), any(), any(), any(), any())).thenReturn(0);

        paymentService.handleCallback(callback("mock-sign", PAY_AMOUNT));

        // 幂等跳过：OrderService 和 OutboxService 都不应被调用
        verify(orderService, never()).markOrderAsPaid(any(), any(), any());
        verifyNoInteractions(outboxService);
    }

    @Test
    void handleCallback_success_updatesOrderStatusAndWritesOutbox() {
        // 完整成功路径：验签通过 → 金额匹配 → 更新支付单 → 更新订单 → 写 Outbox
        when(paymentOrderMapper.selectByPaymentNo(PAYMENT_NO)).thenReturn(paymentOrder(PAY_AMOUNT));
        when(paymentOrderMapper.updateStatusOnSuccess(any(), any(), any(), any(), any())).thenReturn(1);
        when(orderService.markOrderAsPaid(anyLong(), anyLong(), any())).thenReturn(true);
        when(orderService.getOrderById(anyLong())).thenReturn(order("WAIT_PAY"));

        paymentService.handleCallback(callback("mock-sign", PAY_AMOUNT));

        // 验证订单状态被更新
        verify(orderService, times(1)).markOrderAsPaid(eq(ORDER_ID), eq(USER_ID), any());

        // 验证 Outbox 写入（Transactional Outbox 模式保证「支付成功事件」不丢失）
        verify(outboxService, times(1)).saveToOutbox(any(), anyString(), anyString(), anyString());

        // 验证 Metrics 上报（用于监控支付成功率）
        verify(businessMetrics, times(1)).recordPaymentSuccess(eq("MOCK"));
    }

    // =========================================================
    // 3. triggerMockPay
    // =========================================================

    @Test
    void triggerMockPay_paymentNoNotFound_throwsOrderNotFound() {
        when(paymentOrderMapper.selectByPaymentNo(PAYMENT_NO)).thenReturn(null);

        assertThatThrownBy(() -> paymentService.triggerMockPay(PAYMENT_NO))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }

    // =========================================================
    // 辅助方法
    // =========================================================

    private CreatePaymentRequest paymentRequest() {
        CreatePaymentRequest req = new CreatePaymentRequest();
        req.setOrderId(ORDER_ID);
        req.setChannel("MOCK");
        return req;
    }

    private OrderInfo order(String status) {
        return OrderInfo.builder()
                .id(ORDER_ID).userId(USER_ID).shopId(1L)
                .orderNo("order-no-001")
                .orderStatus(status)
                .orderAmount(PAY_AMOUNT)
                .originalAmount(PAY_AMOUNT)
                .couponDiscount(0)
                .build();
    }

    private PaymentOrder paymentOrder(int payAmount) {
        return PaymentOrder.builder()
                .id(PAYMENT_ID)
                .paymentNo(PAYMENT_NO)
                .orderId(ORDER_ID)
                .orderNo("order-no-001")
                .userId(USER_ID)
                .payAmount(payAmount)
                .payStatus("PENDING")
                .channel("MOCK")
                .build();
    }

    private PaymentCallbackRequest callback(String sign, int paidAmount) {
        PaymentCallbackRequest cb = new PaymentCallbackRequest();
        cb.setPaymentNo(PAYMENT_NO);
        cb.setTradeNo("MOCK_TRADE_" + PAYMENT_NO);
        cb.setPaidAmount(paidAmount);
        cb.setChannel("MOCK");
        cb.setPaidAt(LocalDateTime.now());
        cb.setSign(sign);
        return cb;
    }
}
