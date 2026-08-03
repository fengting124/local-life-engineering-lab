package com.personalprojections.locallife.server.module.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.domain.entity.OrderInfo;
import com.personalprojections.locallife.server.domain.entity.SideEffectLedger;
import com.personalprojections.locallife.server.domain.mapper.OrderInfoMapper;
import com.personalprojections.locallife.server.domain.mapper.SideEffectLedgerMapper;
import com.personalprojections.locallife.server.module.internal.InternalController.RefundResult;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalServiceTest {

    @Mock private OrderInfoMapper orderInfoMapper;
    @Mock private SideEffectLedgerMapper sideEffectLedgerMapper;

    private InternalService internalService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderInfo.class);
        TableInfoHelper.initTableInfo(assistant, SideEffectLedger.class);
    }

    @BeforeEach
    void setUp() {
        internalService = new InternalService(orderInfoMapper, sideEffectLedgerMapper, new ObjectMapper());
    }

    @Test
    void executeRefund_existingLedgerHit_returnsStoredResultWithoutMutatingOrder() {
        SideEffectLedger existing = SideEffectLedger.builder()
                .operationType("execute_refund")
                .idempotencyKey("APPROVAL_001")
                .approvalId("APPROVAL_001")
                .resourceId("ORDER_1")
                .status("SUCCESS")
                .resultSnapshot("""
                        {"refundNo":"REFUND_FIRST","orderNo":"ORDER_1","refundAmount":2990,"status":"SUCCESS"}
                        """)
                .build();
        when(sideEffectLedgerMapper.selectOne(any())).thenReturn(existing);

        RefundResult result = internalService.executeRefund(
                "ORDER_1", 2990, "APPROVAL_001", "库存不足");

        assertThat(result.getRefundNo()).isEqualTo("REFUND_FIRST");
        assertThat(result.getOrderNo()).isEqualTo("ORDER_1");
        assertThat(result.getRefundAmount()).isEqualTo(2990);
        verify(orderInfoMapper, never()).selectOne(any());
        verify(orderInfoMapper, never()).update(any(), any());
        verify(sideEffectLedgerMapper, never()).insert((SideEffectLedger) any());
    }

    @Test
    void executeRefund_success_insertsLedgerSnapshot() {
        when(sideEffectLedgerMapper.selectOne(any())).thenReturn(null);
        when(orderInfoMapper.selectOne(any())).thenReturn(OrderInfo.builder()
                .id(1001L)
                .orderNo("ORDER_1")
                .orderStatus("PAID")
                .orderAmount(2990)
                .deleted(0)
                .build());
        doAnswer(invocation -> {
            SideEffectLedger ledger = invocation.getArgument(0);
            assertThat(ledger.getStatus()).isEqualTo("RUNNING");
            assertThat(ledger.getRequestPayload()).contains("\"amount\":2990");
            return 1;
        }).when(sideEffectLedgerMapper).insert((SideEffectLedger) any());

        RefundResult result = internalService.executeRefund(
                "ORDER_1", 2990, "APPROVAL_001", "库存不足");

        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<SideEffectLedger> insertCaptor = ArgumentCaptor.forClass(SideEffectLedger.class);
        verify(sideEffectLedgerMapper).insert(insertCaptor.capture());
        SideEffectLedger inserted = insertCaptor.getValue();
        assertThat(inserted.getOperationType()).isEqualTo("execute_refund");
        assertThat(inserted.getIdempotencyKey()).isEqualTo("APPROVAL_001");
        assertThat(inserted.getApprovalId()).isEqualTo("APPROVAL_001");
        assertThat(inserted.getResourceId()).isEqualTo("ORDER_1");

        ArgumentCaptor<SideEffectLedger> updateCaptor = ArgumentCaptor.forClass(SideEffectLedger.class);
        verify(sideEffectLedgerMapper).updateById(updateCaptor.capture());
        SideEffectLedger updated = updateCaptor.getValue();
        assertThat(updated.getStatus()).isEqualTo("SUCCESS");
        assertThat(updated.getResultSnapshot()).contains("\"refundNo\"");
    }

    @Test
    void executeRefund_responseLostAfterCommit_retryReplaysWithoutSecondOrderMutation() {
        AtomicReference<SideEffectLedger> committedLedger = new AtomicReference<>();
        when(sideEffectLedgerMapper.selectOne(any()))
                .thenAnswer(invocation -> committedLedger.get());
        when(orderInfoMapper.selectOne(any())).thenReturn(OrderInfo.builder()
                .id(1001L)
                .orderNo("ORDER_1")
                .orderStatus("PAID")
                .orderAmount(2990)
                .deleted(0)
                .build());
        doAnswer(invocation -> {
            SideEffectLedger ledger = invocation.getArgument(0);
            ledger.setId(7001L);
            return 1;
        }).when(sideEffectLedgerMapper).insert((SideEffectLedger) any());
        doAnswer(invocation -> {
            committedLedger.set(invocation.getArgument(0));
            return 1;
        }).when(sideEffectLedgerMapper).updateById((SideEffectLedger) any());

        RefundResult committed = internalService.executeRefund(
                "ORDER_1", 2990, "APPROVAL_001", "库存不足");
        RefundResult replayed = internalService.executeRefund(
                "ORDER_1", 2990, "APPROVAL_001", "库存不足");

        assertThat(replayed.getRefundNo()).isEqualTo(committed.getRefundNo());
        assertThat(replayed.getStatus()).isEqualTo("SUCCESS");
        verify(orderInfoMapper, times(1)).update(any(), any());
        verify(sideEffectLedgerMapper, times(1)).insert((SideEffectLedger) any());
        verify(sideEffectLedgerMapper, times(1)).updateById((SideEffectLedger) any());
    }
}
