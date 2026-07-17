package com.personalprojections.locallife.server.module.order;

import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.domain.entity.PaymentOrder;
import com.personalprojections.locallife.server.domain.mapper.PaymentOrderMapper;
import com.personalprojections.locallife.server.module.mq.service.OutboxService;
import com.personalprojections.locallife.server.module.order.dto.PaymentCallbackRequest;
import com.personalprojections.locallife.server.module.order.service.OrderService;
import com.personalprojections.locallife.server.module.order.service.PaymentService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class PaymentDuplicatePaidTest {

    @Mock private PaymentOrderMapper paymentOrderMapper;
    @Mock private OrderService orderService;
    @Mock private OutboxService outboxService;
    @Mock private BusinessMetrics businessMetrics;

    @Test
    void anotherPaymentAlreadyWonOrderCas_marksDuplicateWithoutNormalEvent() {
        PaymentService service = service();
        PaymentOrder payment = paymentOrder();
        when(paymentOrderMapper.selectByPaymentNo("p-2")).thenReturn(payment);
        when(paymentOrderMapper.updateStatusOnSuccess(anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(1);
        when(orderService.markOrderAsPaid(100L, 7L, callback().getPaidAt())).thenReturn(false);
        when(paymentOrderMapper.markAsDuplicatePaid(202L)).thenReturn(1);

        service.handleCallback(callback());

        verify(paymentOrderMapper).markAsDuplicatePaid(202L);
        verifyNoInteractions(outboxService);
        verify(businessMetrics, never()).recordPaymentSuccess(anyString());
    }

    @Test
    void duplicateStateWriteFailure_abortsCallbackTransaction() {
        PaymentService service = service();
        when(paymentOrderMapper.selectByPaymentNo("p-2")).thenReturn(paymentOrder());
        when(paymentOrderMapper.updateStatusOnSuccess(anyLong(), anyString(), any(), anyString(), any()))
                .thenReturn(1);
        when(orderService.markOrderAsPaid(anyLong(), anyLong(), any())).thenReturn(false);
        when(paymentOrderMapper.markAsDuplicatePaid(202L)).thenReturn(0);

        assertThatThrownBy(() -> service.handleCallback(callback()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("duplicate paid state");

        verifyNoInteractions(outboxService);
    }

    private PaymentService service() {
        return new PaymentService(paymentOrderMapper, orderService, outboxService, businessMetrics);
    }

    private PaymentOrder paymentOrder() {
        return PaymentOrder.builder()
                .id(202L)
                .paymentNo("p-2")
                .orderId(100L)
                .orderNo("order-100")
                .userId(7L)
                .payAmount(9900)
                .payStatus("PENDING")
                .channel("MOCK")
                .build();
    }

    private PaymentCallbackRequest callback() {
        PaymentCallbackRequest callback = new PaymentCallbackRequest();
        callback.setPaymentNo("p-2");
        callback.setTradeNo("trade-2");
        callback.setPaidAmount(9900);
        callback.setChannel("MOCK");
        callback.setPaidAt(LocalDateTime.of(2026, 7, 17, 12, 0));
        callback.setSign("mock-sign");
        return callback;
    }
}
