package com.personalprojections.locallife.server.module.coupon;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.CompensationCouponBinding;
import com.personalprojections.locallife.server.domain.entity.CompensationCouponBindingAudit;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import com.personalprojections.locallife.server.domain.entity.Merchant;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.mapper.CompensationCouponBindingAuditMapper;
import com.personalprojections.locallife.server.domain.mapper.CompensationCouponBindingMapper;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.module.coupon.dto.CompensationCouponBindingVO;
import com.personalprojections.locallife.server.module.coupon.service.CompensationCouponBindingService;
import com.personalprojections.locallife.server.module.merchant.service.MerchantService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.slf4j.MDC;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class CompensationCouponBindingServiceTest {

    private static final long USER_ID = 1001L;
    private static final long MERCHANT_ID = 3001L;
    private static final long SHOP_ID = 2001L;
    private static final int FACE_VALUE = 2000;
    private static final long TEMPLATE_ID = 4001L;

    @Mock
    private MerchantService merchantService;
    @Mock
    private ShopMapper shopMapper;
    @Mock
    private CompensationCouponBindingMapper bindingMapper;
    @Mock
    private CouponTemplateMapper templateMapper;
    @Mock
    private CompensationCouponBindingAuditMapper auditMapper;

    private CompensationCouponBindingService service;

    @BeforeEach
    void setUp() {
        service = new CompensationCouponBindingService(
                merchantService, shopMapper, bindingMapper, templateMapper,
                auditMapper, new ObjectMapper().findAndRegisterModules());
        UserContext.set(LoginUserDTO.builder().userId(USER_ID).status("ENABLED").build());
        MDC.put("requestId", "request-123");
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        MDC.clear();
    }

    @Test
    void listDerivesApprovedMerchantAndReturnsOwnedShopBindings() {
        approveOwnerForRead();
        when(bindingMapper.selectByShopId(SHOP_ID)).thenReturn(List.of(binding(1)));
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(validTemplate());

        List<CompensationCouponBindingVO> result = service.list(SHOP_ID);

        assertThat(result).singleElement().satisfies(vo -> {
            assertThat(vo.getMerchantId()).isEqualTo(String.valueOf(MERCHANT_ID));
            assertThat(vo.getCouponTemplateId()).isEqualTo(String.valueOf(TEMPLATE_ID));
            assertThat(vo.getConfigurationStatus()).isEqualTo("READY");
        });
        verify(merchantService).requireApprovedMerchant();
        verify(shopMapper).selectById(SHOP_ID);
    }

    @Test
    void listRejectsForeignOrMissingShopWithoutLeakingExistence() {
        when(merchantService.requireApprovedMerchant()).thenReturn(merchant());
        when(shopMapper.selectById(SHOP_ID)).thenReturn(
                Shop.builder().id(SHOP_ID).merchantId(9999L).build());

        assertBizCode(() -> service.list(SHOP_ID), ErrorCode.SHOP_FORBIDDEN);
        verify(bindingMapper, never()).selectByShopId(any(Long.class));
    }

    @Test
    void getKeepsDisabledAndInvalidHistoricalBindingVisible() {
        approveOwnerForRead();
        CompensationCouponBinding disabled = binding(0);
        when(bindingMapper.selectByShopAndFace(SHOP_ID, FACE_VALUE)).thenReturn(disabled);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(validTemplate());

        CompensationCouponBindingVO result = service.get(SHOP_ID, FACE_VALUE);

        assertThat(result.getEnabled()).isFalse();
        assertThat(result.getConfigurationStatus()).isEqualTo("DISABLED");
    }

    @Test
    void getRejectsMissingBinding() {
        approveOwnerForRead();
        when(bindingMapper.selectByShopAndFace(SHOP_ID, FACE_VALUE)).thenReturn(null);

        assertBizCode(() -> service.get(SHOP_ID, FACE_VALUE),
                ErrorCode.COUPON_COMPENSATION_BINDING_NOT_FOUND);
    }

    @Test
    void upsertCreatesBindingFromServerDerivedMerchantAndAuditsCreate() {
        approveOwnerForWrite();
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(validTemplate());
        when(bindingMapper.selectByShopAndFace(SHOP_ID, FACE_VALUE)).thenReturn(null);
        when(bindingMapper.selectByShopAndTemplate(SHOP_ID, TEMPLATE_ID)).thenReturn(null);
        when(bindingMapper.insert(any(CompensationCouponBinding.class))).thenAnswer(invocation -> {
            CompensationCouponBinding value = invocation.getArgument(0);
            value.setId(6001L);
            return 1;
        });
        when(auditMapper.insert(any(CompensationCouponBindingAudit.class))).thenReturn(1);

        CompensationCouponBindingVO result = service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID);

        ArgumentCaptor<CompensationCouponBinding> bindingCaptor =
                ArgumentCaptor.forClass(CompensationCouponBinding.class);
        verify(bindingMapper).insert(bindingCaptor.capture());
        assertThat(bindingCaptor.getValue().getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(bindingCaptor.getValue().getEnabled()).isEqualTo(1);
        assertThat(result.getConfigurationStatus()).isEqualTo("READY");
        assertAudit("CREATE", null, "\"couponTemplateId\":\"4001\"");
    }

    @Test
    void identicalActiveUpsertIsNoOpWithoutAudit() {
        approveOwnerForWrite();
        CompensationCouponBinding existing = binding(1);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(validTemplate());
        when(bindingMapper.selectByShopAndFace(SHOP_ID, FACE_VALUE)).thenReturn(existing);
        when(bindingMapper.selectByShopAndTemplate(SHOP_ID, TEMPLATE_ID)).thenReturn(existing);

        CompensationCouponBindingVO result = service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID);

        assertThat(result.getConfigurationStatus()).isEqualTo("READY");
        verify(bindingMapper, never()).updateById(any(CompensationCouponBinding.class));
        verify(auditMapper, never()).insert(any(CompensationCouponBindingAudit.class));
    }

    @Test
    void replaceReusesBindingIdentityAndAuditsBeforeAndAfter() {
        approveOwnerForWrite();
        CompensationCouponBinding existing = binding(1);
        existing.setCouponTemplateId(4000L);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(validTemplate());
        when(templateMapper.selectById(4000L)).thenReturn(template(4000L, "CASH", FACE_VALUE, "ACTIVE"));
        when(bindingMapper.selectByShopAndFace(SHOP_ID, FACE_VALUE)).thenReturn(existing);
        when(bindingMapper.selectByShopAndTemplate(SHOP_ID, TEMPLATE_ID)).thenReturn(null);
        when(bindingMapper.updateById(existing)).thenReturn(1);
        when(auditMapper.insert(any(CompensationCouponBindingAudit.class))).thenReturn(1);

        service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID);

        assertThat(existing.getId()).isEqualTo(6001L);
        assertThat(existing.getCouponTemplateId()).isEqualTo(TEMPLATE_ID);
        assertAudit("REPLACE", "\"couponTemplateId\":\"4000\"", "\"couponTemplateId\":\"4001\"");
    }

    @Test
    void reenableAuditsEnableButRepeatedDisableIsNoOp() {
        approveOwnerForWrite();
        CompensationCouponBinding existing = binding(0);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(validTemplate());
        when(bindingMapper.selectByShopAndFace(SHOP_ID, FACE_VALUE)).thenReturn(existing);
        when(bindingMapper.selectByShopAndTemplate(SHOP_ID, TEMPLATE_ID)).thenReturn(existing);
        when(bindingMapper.updateById(existing)).thenReturn(1);
        when(auditMapper.insert(any(CompensationCouponBindingAudit.class))).thenReturn(1);

        service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID);

        assertThat(existing.getEnabled()).isEqualTo(1);
        assertAudit("ENABLE", "\"enabled\":false", "\"enabled\":true");
    }

    @Test
    void disableWritesOneAuditAndAlreadyDisabledIsNoOp() {
        approveOwnerForWrite();
        CompensationCouponBinding existing = binding(1);
        when(bindingMapper.selectByShopAndFace(SHOP_ID, FACE_VALUE)).thenReturn(existing);
        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(validTemplate());
        when(bindingMapper.updateById(existing)).thenReturn(1);
        when(auditMapper.insert(any(CompensationCouponBindingAudit.class))).thenReturn(1);

        CompensationCouponBindingVO disabled = service.disable(SHOP_ID, FACE_VALUE);

        assertThat(disabled.getEnabled()).isFalse();
        assertAudit("DISABLE", "\"enabled\":true", "\"enabled\":false");

        org.mockito.Mockito.clearInvocations(bindingMapper, auditMapper);
        service.disable(SHOP_ID, FACE_VALUE);
        verify(bindingMapper, never()).updateById(any(CompensationCouponBinding.class));
        verify(auditMapper, never()).insert(any(CompensationCouponBindingAudit.class));
    }

    @Test
    void upsertRejectsInvalidTemplateVariantsAndConflicts() {
        approveOwnerForWrite();

        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(null);
        assertBizCode(() -> service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID),
                ErrorCode.COUPON_TEMPLATE_NOT_FOUND);

        when(templateMapper.selectById(TEMPLATE_ID))
                .thenReturn(template(TEMPLATE_ID, "PERCENT", FACE_VALUE, "ACTIVE"));
        assertBizCode(() -> service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID),
                ErrorCode.COUPON_COMPENSATION_TEMPLATE_INVALID);

        when(templateMapper.selectById(TEMPLATE_ID)).thenReturn(validTemplate());
        when(bindingMapper.selectByShopAndFace(SHOP_ID, FACE_VALUE)).thenReturn(null);
        when(bindingMapper.selectByShopAndTemplate(SHOP_ID, TEMPLATE_ID))
                .thenReturn(CompensationCouponBinding.builder()
                        .id(7001L).shopId(SHOP_ID).merchantId(MERCHANT_ID)
                        .faceValueMinor(5000).couponTemplateId(TEMPLATE_ID).enabled(1).build());
        assertBizCode(() -> service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID),
                ErrorCode.COUPON_COMPENSATION_BINDING_CONFLICT);
    }

    @Test
    void upsertRejectsCrossShopWrongValueAndInactiveTemplates() {
        approveOwnerForWrite();
        CouponTemplate crossShop = validTemplate();
        crossShop.setShopId(9999L);
        CouponTemplate wrongValue = template(TEMPLATE_ID, "CASH", 5000, "ACTIVE");
        CouponTemplate inactive = template(TEMPLATE_ID, "CASH", FACE_VALUE, "INACTIVE");
        when(templateMapper.selectById(TEMPLATE_ID))
                .thenReturn(crossShop, wrongValue, inactive);

        assertBizCode(() -> service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID),
                ErrorCode.COUPON_COMPENSATION_TEMPLATE_INVALID);
        assertBizCode(() -> service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID),
                ErrorCode.COUPON_COMPENSATION_TEMPLATE_INVALID);
        assertBizCode(() -> service.upsert(SHOP_ID, FACE_VALUE, TEMPLATE_ID),
                ErrorCode.COUPON_COMPENSATION_TEMPLATE_INVALID);

        verify(bindingMapper, never()).insert(any(CompensationCouponBinding.class));
    }

    @Test
    void nonPositiveFaceValueFailsBeforeDatabaseMutation() {
        assertBizCode(() -> service.upsert(SHOP_ID, 0, TEMPLATE_ID), ErrorCode.SYS_PARAM_INVALID);
        verify(templateMapper, never()).selectById(any(Long.class));
        verify(bindingMapper, never()).insert(any(CompensationCouponBinding.class));
    }

    private void approveOwnerForRead() {
        when(merchantService.requireApprovedMerchant()).thenReturn(merchant());
        when(shopMapper.selectById(SHOP_ID)).thenReturn(ownedShop());
    }

    private void approveOwnerForWrite() {
        when(merchantService.requireApprovedMerchant()).thenReturn(merchant());
        when(shopMapper.selectByIdForUpdate(SHOP_ID)).thenReturn(ownedShop());
    }

    private Merchant merchant() {
        return Merchant.builder().id(MERCHANT_ID).userId(USER_ID).status("APPROVED").build();
    }

    private Shop ownedShop() {
        return Shop.builder().id(SHOP_ID).merchantId(MERCHANT_ID).status("ONLINE").build();
    }

    private CompensationCouponBinding binding(int enabled) {
        return CompensationCouponBinding.builder()
                .id(6001L)
                .shopId(SHOP_ID)
                .merchantId(MERCHANT_ID)
                .faceValueMinor(FACE_VALUE)
                .couponTemplateId(TEMPLATE_ID)
                .enabled(enabled)
                .build();
    }

    private CouponTemplate validTemplate() {
        return template(TEMPLATE_ID, "CASH", FACE_VALUE, "ACTIVE");
    }

    private CouponTemplate template(long id, String type, int value, String status) {
        return CouponTemplate.builder()
                .id(id)
                .shopId(SHOP_ID)
                .discountType(type)
                .discountValue(value)
                .minOrderAmount(0)
                .validDays(30)
                .remainStock(10)
                .status(status)
                .deleted(0)
                .build();
    }

    private void assertAudit(String action, String beforeContains, String afterContains) {
        ArgumentCaptor<CompensationCouponBindingAudit> captor =
                ArgumentCaptor.forClass(CompensationCouponBindingAudit.class);
        verify(auditMapper).insert(captor.capture());
        CompensationCouponBindingAudit audit = captor.getValue();
        assertThat(audit.getAction()).isEqualTo(action);
        assertThat(audit.getOperatorUserId()).isEqualTo(USER_ID);
        assertThat(audit.getMerchantId()).isEqualTo(MERCHANT_ID);
        assertThat(audit.getRequestId()).isEqualTo("request-123");
        if (beforeContains == null) {
            assertThat(audit.getBeforeSnapshot()).isNull();
        } else {
            assertThat(audit.getBeforeSnapshot()).contains(beforeContains);
        }
        assertThat(audit.getAfterSnapshot()).contains(afterContains);
    }

    private static void assertBizCode(Runnable action, ErrorCode expected) {
        assertThatThrownBy(action::run)
                .isInstanceOf(BizException.class)
                .extracting(error -> ((BizException) error).getErrorCode())
                .isEqualTo(expected);
    }
}
