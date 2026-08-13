package com.personalprojections.locallife.server.module.internal;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.domain.entity.CompensationCouponBinding;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import com.personalprojections.locallife.server.domain.entity.OrderInfo;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.entity.SideEffectLedger;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.CompensationCouponBindingMapper;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.OrderInfoMapper;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.domain.mapper.SideEffectLedgerMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.module.internal.InternalController.CompensateRequest;
import com.personalprojections.locallife.server.module.internal.InternalController.CompensateResult;
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
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

@ExtendWith(MockitoExtension.class)
class InternalServiceTest {

    @Mock private OrderInfoMapper orderInfoMapper;
    @Mock private SideEffectLedgerMapper sideEffectLedgerMapper;
    @Mock private ShopMapper shopMapper;
    @Mock private CompensationCouponBindingMapper compensationCouponBindingMapper;
    @Mock private CouponTemplateMapper couponTemplateMapper;
    @Mock private UserCouponMapper userCouponMapper;

    private InternalService internalService;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, OrderInfo.class);
        TableInfoHelper.initTableInfo(assistant, SideEffectLedger.class);
        TableInfoHelper.initTableInfo(assistant, Shop.class);
        TableInfoHelper.initTableInfo(assistant, CouponTemplate.class);
        TableInfoHelper.initTableInfo(assistant, UserCoupon.class);
        TableInfoHelper.initTableInfo(assistant, CompensationCouponBinding.class);
    }

    @BeforeEach
    void setUp() {
        internalService = new InternalService(
                orderInfoMapper,
                sideEffectLedgerMapper,
                shopMapper,
                compensationCouponBindingMapper,
                couponTemplateMapper,
                userCouponMapper,
                new ObjectMapper());
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

    @Test
    void issueCompensationCoupon_successPersistsRealCouponAndSnapshot() {
        CompensateRequest request = validCompensateRequest("APPROVAL_001");
        stubValidCompensationEvidence(request);
        when(sideEffectLedgerMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SideEffectLedger candidate = invocation.getArgument(0);
            when(sideEffectLedgerMapper.selectForUpdate(
                    "issue_compensation_coupon", "APPROVAL_001"))
                    .thenReturn(candidate);
            return 1;
        }).when(sideEffectLedgerMapper).claim(any(SideEffectLedger.class));
        when(couponTemplateMapper.decrementActiveStock(4001L)).thenReturn(1);
        doAnswer(invocation -> {
            UserCoupon coupon = invocation.getArgument(0);
            coupon.setId(9001L);
            return 1;
        }).when(userCouponMapper).insert(any(UserCoupon.class));

        CompensateResult result = internalService.issueCompensationCoupon("ORDER_1", request);

        assertThat(result.getCouponId()).isEqualTo("9001");
        assertThat(result.getStatus()).isEqualTo("SUCCESS");
        ArgumentCaptor<UserCoupon> couponCaptor = ArgumentCaptor.forClass(UserCoupon.class);
        verify(userCouponMapper).insert(couponCaptor.capture());
        assertThat(couponCaptor.getValue()).satisfies(coupon -> {
            assertThat(coupon.getUserId()).isEqualTo(5001L);
            assertThat(coupon.getCouponTemplateId()).isEqualTo(4001L);
            assertThat(coupon.getSeckillSessionId()).isNull();
            assertThat(coupon.getSourceType()).isEqualTo("COMPENSATION");
            assertThat(coupon.getSourceApprovalId()).isEqualTo("APPROVAL_001");
            assertThat(coupon.getIssuanceKey()).isEqualTo("COMPENSATION:APPROVAL_001");
        });
        verify(couponTemplateMapper).decrementActiveStock(4001L);
        verify(sideEffectLedgerMapper).updateById(argThat((SideEffectLedger ledger) ->
                "SUCCESS".equals(ledger.getStatus())
                        && ledger.getResultSnapshot().contains("\"couponId\":\"9001\"")));
    }

    @Test
    void issueCompensationCoupon_duplicateLedgerRaceReplaysWinnerWithoutIssuing() {
        CompensateRequest request = validCompensateRequest("APPROVAL_RACE");
        stubValidCompensationEvidence(request);
        SideEffectLedger winner = SideEffectLedger.builder()
                .operationType("issue_compensation_coupon")
                .idempotencyKey("APPROVAL_RACE")
                .status("SUCCESS")
                .resultSnapshot("""
                        {"couponId":"9100","userId":"5001","faceValue":2000,"status":"SUCCESS"}
                        """)
                .build();
        when(sideEffectLedgerMapper.selectOne(any())).thenReturn(null);
        when(sideEffectLedgerMapper.claim(any(SideEffectLedger.class))).thenReturn(2);
        when(sideEffectLedgerMapper.selectForUpdate("issue_compensation_coupon", "APPROVAL_RACE"))
                .thenReturn(winner);

        CompensateResult result = internalService.issueCompensationCoupon("ORDER_1", request);

        assertThat(result.getCouponId()).isEqualTo("9100");
        verify(couponTemplateMapper, never()).decrementActiveStock(anyLong());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void issueCompensationCoupon_staleSignedTermsFailBeforeLedgerOrStock() {
        CompensateRequest request = validCompensateRequest("APPROVAL_STALE");
        request.setCouponValidDays(3);
        stubValidCompensationEvidence(request);

        assertThatThrownBy(() -> internalService.issueCompensationCoupon("ORDER_1", request))
                .isInstanceOf(BizException.class)
                .hasMessageContaining("条款");
        verify(sideEffectLedgerMapper, never()).insert(any(SideEffectLedger.class));
        verify(couponTemplateMapper, never()).decrementActiveStock(anyLong());
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    @Test
    void issueCompensationCoupon_outOfStockDoesNotInsertCoupon() {
        CompensateRequest request = validCompensateRequest("APPROVAL_EMPTY");
        stubValidCompensationEvidence(request);
        when(sideEffectLedgerMapper.selectOne(any())).thenReturn(null);
        doAnswer(invocation -> {
            SideEffectLedger candidate = invocation.getArgument(0);
            when(sideEffectLedgerMapper.selectForUpdate(
                    "issue_compensation_coupon", "APPROVAL_EMPTY"))
                    .thenReturn(candidate);
            return 1;
        }).when(sideEffectLedgerMapper).claim(any(SideEffectLedger.class));
        when(couponTemplateMapper.decrementActiveStock(4001L)).thenReturn(0);

        assertThatThrownBy(() -> internalService.issueCompensationCoupon("ORDER_1", request))
                .isInstanceOf(BizException.class);
        verify(userCouponMapper, never()).insert(any(UserCoupon.class));
    }

    private void stubValidCompensationEvidence(CompensateRequest request) {
        when(orderInfoMapper.selectOne(any())).thenReturn(OrderInfo.builder()
                .id(1001L)
                .orderNo("ORDER_1")
                .userId(5001L)
                .shopId(2001L)
                .deleted(0)
                .build());
        when(shopMapper.selectById(2001L)).thenReturn(Shop.builder()
                .id(2001L)
                .merchantId(3001L)
                .deleted(0)
                .build());
        when(compensationCouponBindingMapper.selectEnabled(2001L, 2000))
                .thenReturn(CompensationCouponBinding.builder()
                        .id(6001L)
                        .shopId(2001L)
                        .merchantId(3001L)
                        .faceValueMinor(2000)
                        .couponTemplateId(4001L)
                        .enabled(1)
                        .build());
        when(couponTemplateMapper.selectById(4001L)).thenReturn(CouponTemplate.builder()
                .id(4001L)
                .shopId(2001L)
                .discountType("CASH")
                .discountValue(2000)
                .minOrderAmount(0)
                .validDays(30)
                .status("ACTIVE")
                .deleted(0)
                .build());
    }

    private CompensateRequest validCompensateRequest(String approvalId) {
        CouponTerms terms = new CouponTerms(1, "4001", "2001", "3001", "CASH", 2000, 0, 30);
        CompensateRequest request = new CompensateRequest();
        request.setUserId("5001");
        request.setShopId("2001");
        request.setMerchantId("3001");
        request.setCompensationAmount(2000);
        request.setCouponTemplateId("4001");
        request.setCouponDiscountType("CASH");
        request.setCouponMinOrderAmount(0);
        request.setCouponValidDays(30);
        request.setCouponTermsDigest(terms.digest());
        request.setApprovalId(approvalId);
        request.setReason("service recovery");
        return request;
    }
}
