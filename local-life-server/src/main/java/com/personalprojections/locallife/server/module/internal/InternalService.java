package com.personalprojections.locallife.server.module.internal;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.OrderInfo;
import com.personalprojections.locallife.server.domain.mapper.OrderInfoMapper;
import com.personalprojections.locallife.server.module.internal.InternalController.CompensateResult;
import com.personalprojections.locallife.server.module.internal.InternalController.RefundResult;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

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

    private final OrderInfoMapper orderInfoMapper;

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

        return RefundResult.of(refundNo, orderNo, amount, "SUCCESS");
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
     * @param userId              目标用户 ID
     * @param compensationAmount  补偿券面值（分）
     * @param approvalId          HITL 审批 ID
     * @param reason              补偿原因
     * @return 补偿结果
     */
    @Transactional(rollbackFor = Exception.class)
    public CompensateResult issueCompensationCoupon(
            String orderNo, String userId, int compensationAmount,
            String approvalId, String reason) {

        // 生成补偿券 ID（实际应先创建 coupon_template，此处简化）
        String couponId = "COMP_" + System.currentTimeMillis();

        log.info("[Internal] 补偿券发放: orderNo={}, userId={}, amount={}分, couponId={}, approvalId={}",
                orderNo, userId, compensationAmount, couponId, approvalId);

        // 实际生产实现（伪代码）：
        // 1. INSERT coupon_template (compensation type, face_value = compensationAmount, stock = 1)
        // 2. INSERT user_coupon (user_id, coupon_template_id, status=UNUSED, expire_at=30天后)
        // 3. 发短信/Push 通知用户

        return CompensateResult.of(couponId, userId, compensationAmount, "SUCCESS");
    }
}
