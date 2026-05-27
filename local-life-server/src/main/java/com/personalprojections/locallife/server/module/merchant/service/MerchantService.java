package com.personalprojections.locallife.server.module.merchant.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.Merchant;
import com.personalprojections.locallife.server.domain.mapper.MerchantMapper;
import com.personalprojections.locallife.server.module.merchant.dto.ApplyMerchantRequest;
import com.personalprojections.locallife.server.module.merchant.dto.MerchantVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 商家 Service，负责商家申请与信息查询。
 *
 * <h2>主要职责</h2>
 * <ul>
 *   <li>申请成为商家（当前阶段提交即自动 APPROVED，模拟审核通过）</li>
 *   <li>查询当前用户的商家信息</li>
 *   <li>为其他 Service（ShopService）提供「校验当前用户是否是 APPROVED 商家」的能力</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class MerchantService {

    private final MerchantMapper merchantMapper;

    /**
     * 申请成为商家。
     *
     * <p>业务规则：
     * <ol>
     *   <li>同一用户只能申请一次（uk_merchant_user_id 唯一索引）</li>
     *   <li>当前阶段：提交后自动设为 APPROVED（模拟审核通过，后续可改为 PENDING + 审核流程）</li>
     * </ol>
     *
     * @param request 申请信息
     * @return 创建后的商家 VO
     */
    public MerchantVO apply(ApplyMerchantRequest request) {
        Long userId = UserContext.getUserId();

        // 1. 检查是否已申请过
        Merchant existing = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, userId)
        );
        if (existing != null) {
            // 已申请过，直接返回当前状态（幂等），不报错
            log.info("用户已是商家，userId: {}, merchantId: {}", userId, existing.getId());
            return toVO(existing, true);
        }

        // 2. 创建商家记录，当前直接置为 APPROVED（模拟审核通过）
        Merchant merchant = Merchant.builder()
                .userId(userId)
                .merchantName(request.getMerchantName())
                .description(request.getDescription() != null ? request.getDescription() : "")
                .contactMobile(request.getContactMobile())
                .logo("")
                // 当前阶段：提交即通过，后续可改为 PENDING + 审核流程
                .status("APPROVED")
                .build();

        merchantMapper.insert(merchant);
        log.info("商家申请成功，userId: {}, merchantId: {}", userId, merchant.getId());
        return toVO(merchant, true);
    }

    /**
     * 查询当前登录用户的商家信息。
     *
     * @return 当前用户的商家 VO（含脱敏联系手机号）
     * @throws BizException MERCHANT_NOT_FOUND，当前用户没有商家身份
     */
    public MerchantVO getMyMerchant() {
        Long userId = UserContext.getUserId();
        Merchant merchant = requireMerchantByUserId(userId);
        return toVO(merchant, true);
    }

    /**
     * 校验当前登录用户是已通过审核的商家，并返回其 merchantId。
     *
     * <p>供 ShopService 调用：创建/修改门店前先调此方法，
     * 确保操作者是 APPROVED 状态的商家，否则直接抛业务异常。
     *
     * @return 当前用户对应的 Merchant 实体
     * @throws BizException MERCHANT_NOT_FOUND，当前用户没有商家身份
     * @throws BizException MERCHANT_NOT_APPROVED，商家审核未通过或已被禁用
     */
    public Merchant requireApprovedMerchant() {
        Long userId = UserContext.getUserId();
        Merchant merchant = requireMerchantByUserId(userId);
        if (!"APPROVED".equals(merchant.getStatus())) {
            throw new BizException(ErrorCode.MERCHANT_NOT_APPROVED);
        }
        return merchant;
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    /**
     * 按 userId 查商家，不存在则抛业务异常。
     */
    private Merchant requireMerchantByUserId(Long userId) {
        Merchant merchant = merchantMapper.selectOne(
                new LambdaQueryWrapper<Merchant>().eq(Merchant::getUserId, userId)
        );
        if (merchant == null) {
            throw new BizException(ErrorCode.MERCHANT_NOT_FOUND);
        }
        return merchant;
    }

    /**
     * Merchant 实体转 MerchantVO。
     *
     * @param merchant       商家实体
     * @param includeSensitive 是否包含敏感字段（联系手机号），查自己时为 true
     */
    private MerchantVO toVO(Merchant merchant, boolean includeSensitive) {
        return MerchantVO.builder()
                .merchantId(String.valueOf(merchant.getId()))
                .merchantName(merchant.getMerchantName())
                .logo(merchant.getLogo())
                .description(merchant.getDescription())
                .contactMobile(includeSensitive ? desensitizeMobile(merchant.getContactMobile()) : null)
                .status(merchant.getStatus())
                .build();
    }

    /**
     * 手机号脱敏：138****8000。
     */
    private String desensitizeMobile(String mobile) {
        if (mobile == null || mobile.length() != 11) return mobile;
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }
}
