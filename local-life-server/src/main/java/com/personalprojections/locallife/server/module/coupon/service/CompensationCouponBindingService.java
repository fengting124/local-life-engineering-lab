package com.personalprojections.locallife.server.module.coupon.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
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
import com.personalprojections.locallife.server.module.merchant.service.MerchantService;
import lombok.RequiredArgsConstructor;
import org.slf4j.MDC;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;

/** Merchant management service for deterministic shop compensation coupon mappings. */
@Service
@RequiredArgsConstructor
public class CompensationCouponBindingService {

    private final MerchantService merchantService;
    private final ShopMapper shopMapper;
    private final CompensationCouponBindingMapper bindingMapper;
    private final CouponTemplateMapper templateMapper;
    private final CompensationCouponBindingAuditMapper auditMapper;
    private final ObjectMapper objectMapper;

    public List<CompensationCouponBindingVO> list(long shopId) {
        Shop shop = requireOwnedShop(shopId, false);
        return bindingMapper.selectByShopId(shopId).stream()
                .map(binding -> toVO(shop, binding, templateMapper.selectById(
                        binding.getCouponTemplateId())))
                .toList();
    }

    public CompensationCouponBindingVO get(long shopId, int faceValueMinor) {
        validateFaceValue(faceValueMinor);
        Shop shop = requireOwnedShop(shopId, false);
        CompensationCouponBinding binding = requireBinding(shopId, faceValueMinor);
        return toVO(shop, binding, templateMapper.selectById(binding.getCouponTemplateId()));
    }

    @Transactional(rollbackFor = Exception.class)
    public CompensationCouponBindingVO upsert(long shopId, int faceValueMinor, long templateId) {
        validateFaceValue(faceValueMinor);
        if (templateId <= 0) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID);
        }

        Shop shop = requireOwnedShop(shopId, true);
        CouponTemplate template = requireValidTemplate(shop, faceValueMinor, templateId);
        CompensationCouponBinding existing =
                bindingMapper.selectByShopAndFace(shopId, faceValueMinor);
        CompensationCouponBinding templateBinding =
                bindingMapper.selectByShopAndTemplate(shopId, templateId);

        if (templateBinding != null
                && (existing == null || !templateBinding.getId().equals(existing.getId()))) {
            throw new BizException(ErrorCode.COUPON_COMPENSATION_BINDING_CONFLICT);
        }

        if (existing == null) {
            CompensationCouponBinding created = CompensationCouponBinding.builder()
                    .shopId(shopId)
                    .merchantId(shop.getMerchantId())
                    .faceValueMinor(faceValueMinor)
                    .couponTemplateId(templateId)
                    .enabled(1)
                    .build();
            requireSingleWrite(bindingMapper.insert(created));
            appendAudit("CREATE", created, null, snapshot(created, template));
            return toVO(shop, created, template);
        }

        CouponTemplate previousTemplate = templateMapper.selectById(existing.getCouponTemplateId());
        BindingAuditSnapshot before = snapshot(existing, previousTemplate);
        boolean sameTemplate = existing.getCouponTemplateId().equals(templateId);
        boolean enabled = Integer.valueOf(1).equals(existing.getEnabled());
        if (sameTemplate && enabled) {
            return toVO(shop, existing, template);
        }

        String action = sameTemplate ? "ENABLE" : "REPLACE";
        existing.setMerchantId(shop.getMerchantId());
        existing.setCouponTemplateId(templateId);
        existing.setEnabled(1);
        requireSingleWrite(bindingMapper.updateById(existing));
        appendAudit(action, existing, before, snapshot(existing, template));
        return toVO(shop, existing, template);
    }

    @Transactional(rollbackFor = Exception.class)
    public CompensationCouponBindingVO disable(long shopId, int faceValueMinor) {
        validateFaceValue(faceValueMinor);
        Shop shop = requireOwnedShop(shopId, true);
        CompensationCouponBinding binding = requireBinding(shopId, faceValueMinor);
        CouponTemplate template = templateMapper.selectById(binding.getCouponTemplateId());
        if (!Integer.valueOf(1).equals(binding.getEnabled())) {
            return toVO(shop, binding, template);
        }

        BindingAuditSnapshot before = snapshot(binding, template);
        binding.setEnabled(0);
        requireSingleWrite(bindingMapper.updateById(binding));
        appendAudit("DISABLE", binding, before, snapshot(binding, template));
        return toVO(shop, binding, template);
    }

    private Shop requireOwnedShop(long shopId, boolean lock) {
        if (shopId <= 0) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID);
        }
        Merchant merchant = merchantService.requireApprovedMerchant();
        Shop shop = lock ? shopMapper.selectByIdForUpdate(shopId) : shopMapper.selectById(shopId);
        if (shop == null || !merchant.getId().equals(shop.getMerchantId())) {
            throw new BizException(ErrorCode.SHOP_FORBIDDEN);
        }
        return shop;
    }

    private CouponTemplate requireValidTemplate(Shop shop, int faceValueMinor, long templateId) {
        CouponTemplate template = templateMapper.selectById(templateId);
        if (template == null) {
            throw new BizException(ErrorCode.COUPON_TEMPLATE_NOT_FOUND);
        }
        if (!shop.getId().equals(template.getShopId())
                || !"CASH".equals(template.getDiscountType())
                || !Integer.valueOf(faceValueMinor).equals(template.getDiscountValue())
                || !"ACTIVE".equals(template.getStatus())) {
            throw new BizException(ErrorCode.COUPON_COMPENSATION_TEMPLATE_INVALID);
        }
        return template;
    }

    private CompensationCouponBinding requireBinding(long shopId, int faceValueMinor) {
        CompensationCouponBinding binding =
                bindingMapper.selectByShopAndFace(shopId, faceValueMinor);
        if (binding == null) {
            throw new BizException(ErrorCode.COUPON_COMPENSATION_BINDING_NOT_FOUND);
        }
        return binding;
    }

    private void appendAudit(
            String action,
            CompensationCouponBinding binding,
            BindingAuditSnapshot before,
            BindingAuditSnapshot after
    ) {
        CompensationCouponBindingAudit audit = CompensationCouponBindingAudit.builder()
                .bindingId(binding.getId())
                .shopId(binding.getShopId())
                .merchantId(binding.getMerchantId())
                .faceValueMinor(binding.getFaceValueMinor())
                .action(action)
                .operatorUserId(UserContext.getUserId())
                .requestId(MDC.get("requestId"))
                .beforeSnapshot(toJson(before))
                .afterSnapshot(toJson(after))
                .build();
        requireSingleWrite(auditMapper.insert(audit));
    }

    private CompensationCouponBindingVO toVO(
            Shop shop,
            CompensationCouponBinding binding,
            CouponTemplate template
    ) {
        return CompensationCouponBindingVO.builder()
                .bindingId(String.valueOf(binding.getId()))
                .shopId(String.valueOf(binding.getShopId()))
                .merchantId(String.valueOf(binding.getMerchantId()))
                .faceValueMinor(binding.getFaceValueMinor())
                .couponTemplateId(String.valueOf(binding.getCouponTemplateId()))
                .discountType(template == null ? null : template.getDiscountType())
                .discountValue(template == null ? null : template.getDiscountValue())
                .minOrderAmount(template == null ? null : template.getMinOrderAmount())
                .validDays(template == null ? null : template.getValidDays())
                .templateStatus(template == null ? null : template.getStatus())
                .remainStock(template == null ? null : template.getRemainStock())
                .enabled(Integer.valueOf(1).equals(binding.getEnabled()))
                .configurationStatus(configurationStatus(shop, binding, template))
                .createdAt(binding.getCreatedAt())
                .updatedAt(binding.getUpdatedAt())
                .build();
    }

    private String configurationStatus(
            Shop shop,
            CompensationCouponBinding binding,
            CouponTemplate template
    ) {
        if (!shop.getMerchantId().equals(binding.getMerchantId())) {
            return "MERCHANT_MISMATCH";
        }
        if (!Integer.valueOf(1).equals(binding.getEnabled())) {
            return "DISABLED";
        }
        if (template == null) {
            return "TEMPLATE_MISSING";
        }
        if (!shop.getId().equals(template.getShopId())
                || !"CASH".equals(template.getDiscountType())
                || !binding.getFaceValueMinor().equals(template.getDiscountValue())
                || !"ACTIVE".equals(template.getStatus())) {
            return "TEMPLATE_INVALID";
        }
        return "READY";
    }

    private BindingAuditSnapshot snapshot(
            CompensationCouponBinding binding,
            CouponTemplate template
    ) {
        return new BindingAuditSnapshot(
                String.valueOf(binding.getId()),
                String.valueOf(binding.getShopId()),
                String.valueOf(binding.getMerchantId()),
                binding.getFaceValueMinor(),
                String.valueOf(binding.getCouponTemplateId()),
                Integer.valueOf(1).equals(binding.getEnabled()),
                template == null ? null : template.getDiscountType(),
                template == null ? null : template.getDiscountValue(),
                template == null ? null : template.getMinOrderAmount(),
                template == null ? null : template.getValidDays());
    }

    private String toJson(BindingAuditSnapshot snapshot) {
        if (snapshot == null) {
            return null;
        }
        try {
            return objectMapper.writeValueAsString(snapshot);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("cannot serialize compensation binding audit", error);
        }
    }

    private static void validateFaceValue(int faceValueMinor) {
        if (faceValueMinor <= 0) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID);
        }
    }

    private static void requireSingleWrite(int affectedRows) {
        if (affectedRows != 1) {
            throw new IllegalStateException("compensation binding persistence failed");
        }
    }

    private record BindingAuditSnapshot(
            String bindingId,
            String shopId,
            String merchantId,
            Integer faceValueMinor,
            String couponTemplateId,
            Boolean enabled,
            String discountType,
            Integer discountValue,
            Integer minOrderAmount,
            Integer validDays
    ) {
    }
}

