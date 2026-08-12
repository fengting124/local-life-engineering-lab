package com.personalprojections.locallife.server.module.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.toolkit.IdWorker;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.OrderInfo;
import com.personalprojections.locallife.server.domain.entity.CompensationCouponBinding;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
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
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Map;

/**
 * 内部服务业务逻辑。
 *
 * <p>为 Copilot MCP Server（通过 InternalController）提供：
 * <ol>
 *   <li>退款执行（支持 MOCK 渠道）</li>
 *   <li>补偿优惠券发放</li>
 * </ol>
 *
 * <h2>安全边界</h2>
 * <p>所有操作都在这里做最终校验（订单状态、金额合法性），
 * 即使 Copilot 绕过 HITL 直接调用，业务规则也能兜底拦截。
 * 「业务规则留在 Java 主服务」的核心原则在此处体现。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class InternalService {

    private static final String OP_REFUND = "execute_refund";
    private static final String OP_COMPENSATE_COUPON = "issue_compensation_coupon";

    private final OrderInfoMapper orderInfoMapper;
    private final SideEffectLedgerMapper sideEffectLedgerMapper;
    private final ShopMapper shopMapper;
    private final CompensationCouponBindingMapper compensationCouponBindingMapper;
    private final CouponTemplateMapper couponTemplateMapper;
    private final UserCouponMapper userCouponMapper;
    private final ObjectMapper objectMapper;

    // =========================================================
    // 退款
    // =========================================================

    /**
     * 执行退款。
     *
     * <p>当前实现：MOCK 渠道直接成功（用于演示）。
     * 生产实现：调用支付渠道退款 API（Alipay refund / WeChat refund）。
     *
     * @param orderNo    订单号
     * @param amount     退款金额（分），必须 <= 实付金额
     * @param approvalId HITL 审批 ID（用于审计，当前简化不校验）
     * @param reason     退款原因
     * @return 退款结果
     */
    @Transactional(rollbackFor = Exception.class)
    public RefundResult executeRefund(String orderNo, int amount, String approvalId, String reason) {
        SideEffectLedger existing = findLedger(OP_REFUND, approvalId);
        if (existing != null) {
            return replayRefundLedger(existing);
        }
        SideEffectLedger ledger = beginLedger(
                OP_REFUND,
                approvalId,
                approvalId,
                orderNo,
                Map.of("orderNo", orderNo, "amount", amount, "approvalId", approvalId, "reason", reason));

        // ---- 1. 查订单 ----
        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getOrderNo, orderNo)
                        .eq(OrderInfo::getDeleted, 0));

        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }

        // ---- 2. 校验订单状态 ----
        if (!"PAID".equals(order.getOrderStatus()) && !"COMPLETED".equals(order.getOrderStatus())) {
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL,
                    "只有 PAID 或 COMPLETED 状态的订单才能退款，当前状态：" + order.getOrderStatus());
        }

        // ---- 3. 校验金额 ----
        if (amount > order.getOrderAmount()) {
            throw new BizException(ErrorCode.PAYMENT_AMOUNT_MISMATCH,
                    "退款金额 " + amount + " 分超过实付金额 " + order.getOrderAmount() + " 分");
        }

        // ---- 4. 更新订单状态（当前简化：直接改为 CANCELLED，生产走 REFUNDING 状态）----
        orderInfoMapper.update(null,
                new com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper<OrderInfo>()
                        .eq(OrderInfo::getId, order.getId())
                        .set(OrderInfo::getOrderStatus, "CANCELLED")
                        .set(OrderInfo::getUpdatedAt, LocalDateTime.now()));

        // ---- 5. 生成退款单号 ----
        String refundNo = "REFUND_" + System.currentTimeMillis();

        log.info("[Internal] 退款执行成功: orderNo={}, refundNo={}, amount={}分, approvalId={}, reason={}",
                orderNo, refundNo, amount, approvalId, reason);

        RefundResult result = RefundResult.of(refundNo, orderNo, amount, "SUCCESS");
        completeLedger(ledger, result);
        return result;
    }

    // =========================================================
    // 补偿优惠券
    // =========================================================

    /**
     * 发放补偿优惠券。
     *
     * <p>当原券因库存不足无法发放时，为用户发放等额补偿券。
     * 当前实现：直接创建 UserCoupon 记录（简化版，无券模板）。
     * 生产实现：先创建临时 CouponTemplate，再发 UserCoupon，并通知用户。
     *
     * @param orderNo             关联订单号
     * @param request             已签名并批准的补偿目标和券条款
     * @return 补偿结果
     */
    @Transactional(rollbackFor = Exception.class)
    public CompensateResult issueCompensationCoupon(String orderNo, CompensateRequest request) {
        SideEffectLedger existing = findLedger(OP_COMPENSATE_COUPON, request.getApprovalId());
        if (existing != null) {
            return replayCompensateLedger(existing);
        }

        long expectedUserId = parseId("userId", request.getUserId());
        long expectedShopId = parseId("shopId", request.getShopId());
        long expectedMerchantId = parseId("merchantId", request.getMerchantId());
        long expectedTemplateId = parseId("couponTemplateId", request.getCouponTemplateId());

        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getOrderNo, orderNo)
                        .eq(OrderInfo::getDeleted, 0));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        requireEqual("目标用户", expectedUserId, order.getUserId());
        requireEqual("门店", expectedShopId, order.getShopId());

        Shop shop = shopMapper.selectById(order.getShopId());
        if (shop == null || Integer.valueOf(1).equals(shop.getDeleted())) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        requireEqual("商家", expectedMerchantId, shop.getMerchantId());

        CompensationCouponBinding binding = compensationCouponBindingMapper.selectEnabled(
                order.getShopId(), request.getCompensationAmount());
        if (binding == null) {
            throw invalidCompensation("补偿券配置不存在或已停用");
        }
        requireEqual("配置门店", order.getShopId(), binding.getShopId());
        requireEqual("配置商家", shop.getMerchantId(), binding.getMerchantId());
        requireEqual("券模板", expectedTemplateId, binding.getCouponTemplateId());

        CouponTemplate template = couponTemplateMapper.selectById(binding.getCouponTemplateId());
        if (template == null || Integer.valueOf(1).equals(template.getDeleted())) {
            throw invalidCompensation("补偿券模板不存在");
        }
        requireEqual("模板门店", order.getShopId(), template.getShopId());
        if (!"ACTIVE".equals(template.getStatus())
                || !"CASH".equals(template.getDiscountType())
                || !template.getDiscountValue().equals(request.getCompensationAmount())) {
            throw invalidCompensation("补偿券模板状态、类型或面值不符合审批");
        }

        CouponTerms currentTerms = new CouponTerms(
                1,
                String.valueOf(template.getId()),
                String.valueOf(order.getShopId()),
                String.valueOf(shop.getMerchantId()),
                template.getDiscountType(),
                template.getDiscountValue(),
                template.getMinOrderAmount(),
                template.getValidDays());
        if (!currentTerms.discountType().equals(request.getCouponDiscountType())
                || currentTerms.minOrderAmount() != request.getCouponMinOrderAmount()
                || currentTerms.validDays() != request.getCouponValidDays()
                || !currentTerms.digest().equals(request.getCouponTermsDigest())) {
            throw invalidCompensation("补偿券条款已变化，需要重新审批");
        }

        LedgerClaim claim = beginCompensationLedger(orderNo, request);
        if (!claim.claimed()) {
            return replayCompensateLedger(claim.ledger());
        }
        SideEffectLedger ledger = claim.ledger();

        if (couponTemplateMapper.decrementActiveStock(template.getId()) != 1) {
            throw new BizException(ErrorCode.COUPON_STOCK_EXHAUSTED);
        }

        LocalDateTime now = LocalDateTime.now();
        UserCoupon coupon = UserCoupon.builder()
                .userId(order.getUserId())
                .couponTemplateId(template.getId())
                .seckillSessionId(null)
                .sourceType("COMPENSATION")
                .sourceApprovalId(request.getApprovalId())
                .issuanceKey("COMPENSATION:" + request.getApprovalId())
                .couponStatus("UNUSED")
                .receivedAt(now)
                .expireAt(now.plusDays(template.getValidDays()))
                .build();
        userCouponMapper.insert(coupon);

        CompensateResult result = CompensateResult.of(
                String.valueOf(coupon.getId()),
                String.valueOf(order.getUserId()),
                request.getCompensationAmount(),
                "SUCCESS");
        completeLedger(ledger, result);
        log.info("[Internal] 补偿券发放成功: orderNo={}, userId={}, templateId={}, couponId={}, approvalId={}",
                orderNo, order.getUserId(), template.getId(), coupon.getId(), request.getApprovalId());
        return result;
    }

    private LedgerClaim beginCompensationLedger(String orderNo, CompensateRequest request) {
        SideEffectLedger candidate = SideEffectLedger.builder()
                .id(IdWorker.getId())
                .operationType(OP_COMPENSATE_COUPON)
                .idempotencyKey(request.getApprovalId())
                .approvalId(request.getApprovalId())
                .resourceId(orderNo)
                .requestPayload(writeJson(Map.ofEntries(
                        Map.entry("orderNo", orderNo),
                        Map.entry("userId", request.getUserId()),
                        Map.entry("shopId", request.getShopId()),
                        Map.entry("merchantId", request.getMerchantId()),
                        Map.entry("compensationAmount", request.getCompensationAmount()),
                        Map.entry("couponTemplateId", request.getCouponTemplateId()),
                        Map.entry("couponTermsDigest", request.getCouponTermsDigest()),
                        Map.entry("approvalId", request.getApprovalId()),
                        Map.entry("reason", request.getReason()))))
                .status("RUNNING")
                .build();
        sideEffectLedgerMapper.claim(candidate);
        SideEffectLedger winner = sideEffectLedgerMapper.selectForUpdate(
                OP_COMPENSATE_COUPON, request.getApprovalId());
        if (winner == null) {
            throw new BizException(ErrorCode.SYS_BUSY, "补偿券账本竞争结果不可见，请稍后重试");
        }
        return new LedgerClaim(winner, candidate.getId().equals(winner.getId()));
    }

    private record LedgerClaim(SideEffectLedger ledger, boolean claimed) {
    }

    private long parseId(String field, String value) {
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException error) {
            throw invalidCompensation(field + " 格式不合法");
        }
    }

    private void requireEqual(String field, Long expected, Long actual) {
        if (actual == null || !expected.equals(actual)) {
            throw invalidCompensation(field + " 与订单或审批不一致");
        }
    }

    private BizException invalidCompensation(String message) {
        return new BizException(ErrorCode.SYS_PARAM_INVALID, message);
    }

    private SideEffectLedger findLedger(String operationType, String idempotencyKey) {
        return sideEffectLedgerMapper.selectOne(
                new LambdaQueryWrapper<SideEffectLedger>()
                        .eq(SideEffectLedger::getOperationType, operationType)
                        .eq(SideEffectLedger::getIdempotencyKey, idempotencyKey));
    }

    private SideEffectLedger beginLedger(
            String operationType,
            String idempotencyKey,
            String approvalId,
            String resourceId,
            Map<String, Object> requestPayload) {
        SideEffectLedger ledger = SideEffectLedger.builder()
                .operationType(operationType)
                .idempotencyKey(idempotencyKey)
                .approvalId(approvalId)
                .resourceId(resourceId)
                .requestPayload(writeJson(requestPayload))
                .status("RUNNING")
                .build();
        try {
            sideEffectLedgerMapper.insert(ledger);
            return ledger;
        } catch (DuplicateKeyException e) {
            throw new BizException(ErrorCode.SYS_BUSY, "高风险操作已提交，请勿重复提交");
        }
    }

    private void completeLedger(SideEffectLedger ledger, Object result) {
        ledger.setStatus("SUCCESS");
        ledger.setResultSnapshot(writeJson(result));
        sideEffectLedgerMapper.updateById(ledger);
    }

    private RefundResult replayRefundLedger(SideEffectLedger ledger) {
        if ("SUCCESS".equals(ledger.getStatus())) {
            return readJson(ledger.getResultSnapshot(), RefundResult.class);
        }
        throw new BizException(ErrorCode.SYS_BUSY, "退款操作已在处理中或失败，请勿重复提交");
    }

    private CompensateResult replayCompensateLedger(SideEffectLedger ledger) {
        if ("SUCCESS".equals(ledger.getStatus())) {
            return readJson(ledger.getResultSnapshot(), CompensateResult.class);
        }
        throw new BizException(ErrorCode.SYS_BUSY, "补偿券操作已在处理中或失败，请勿重复提交");
    }

    private String writeJson(Object value) {
        try {
            return objectMapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("序列化副作用账本失败", e);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        try {
            return objectMapper.readValue(json, type);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("反序列化副作用账本失败", e);
        }
    }
}
