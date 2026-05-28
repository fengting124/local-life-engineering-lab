package com.personalprojections.locallife.server.module.order.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 创建订单请求体（POST /api/v1/orders）。
 *
 * <h2>请求体字段说明</h2>
 * <ul>
 *   <li>{@code shopId}：必填，要购买的门店 ID。Service 层会查 shop 表验证门店状态是否 ONLINE。</li>
 *   <li>{@code userCouponId}：可选，使用的优惠券实例 ID（user_coupon.id）。
 *       为 null 表示不使用优惠券，原价下单。</li>
 * </ul>
 *
 * <h2>金额由服务端计算，不由客户端传入</h2>
 * <p>请求体中没有金额字段，这是刻意的设计：
 * <ul>
 *   <li>安全性：客户端传入的金额不可信（可能被篡改）</li>
 *   <li>一致性：金额由服务端从数据库读取并计算，确保与展示价一致</li>
 *   <li>逻辑：originalAmount 从 shop 表读取，couponDiscount 从 coupon 表读取，
 *       orderAmount = originalAmount - couponDiscount</li>
 * </ul>
 *
 * <h2>示例请求</h2>
 * <pre>{@code
 * POST /api/v1/orders
 * Authorization: Bearer {token}
 * Content-Type: application/json
 *
 * {
 *   "shopId": "1234567890",
 *   "userCouponId": "9876543210"
 * }
 * }</pre>
 */
@Data
public class CreateOrderRequest {

    /**
     * 要购买的门店 ID（必填）。
     * Service 层会：
     *   1. 查 shop 表，验证门店存在且 status = ONLINE
     *   2. 从 shop.price 读取门店原价作为 originalAmount
     */
    @NotNull(message = "shopId 不能为空")
    @Positive(message = "shopId 必须是正整数")
    private Long shopId;

    /**
     * 使用的用户券实例 ID（选填）。
     * 为 null 表示不使用优惠券，原价下单。
     * Service 层会：
     *   1. 查 user_coupon 表，验证券归属当前用户且状态为 UNUSED
     *   2. 查 coupon_template 表，读取抵扣金额 discountAmount
     *   3. 核销券（状态从 UNUSED → USED）
     */
    private Long userCouponId;

    /**
     * 买家备注（选填）。
     * 可为空字符串，最长 256 字符（与数据库字段长度一致）。
     */
    private String remark;
}
