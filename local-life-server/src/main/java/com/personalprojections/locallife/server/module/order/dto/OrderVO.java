package com.personalprojections.locallife.server.module.order.dto;

import lombok.Builder;
import lombok.Data;

import java.time.OffsetDateTime;

/**
 * 订单详情 VO（View Object），用于接口响应。
 *
 * <h2>VO vs Entity 的区别</h2>
 * <p>Entity（OrderInfo）是数据库映射对象，字段对应表列，不能直接返回给前端：
 * <ul>
 *   <li>Long 类型的 ID 超出 JS 安全整数范围，需转成 String</li>
 *   <li>LocalDateTime 没有时区信息，需转成 OffsetDateTime（ISO 8601 +08:00）</li>
 *   <li>deleted 字段是内部字段，不应暴露给前端</li>
 *   <li>金额（分）可能需要换算成元（展示用）—— 本 VO 保留分，由前端换算</li>
 * </ul>
 *
 * <h2>金额字段说明（全部以「分」为单位）</h2>
 * <ul>
 *   <li>{@code originalAmount}：原价，门店价格</li>
 *   <li>{@code couponDiscount}：优惠券抵扣金额，0 = 未使用券</li>
 *   <li>{@code orderAmount}：实付金额 = originalAmount - couponDiscount</li>
 * </ul>
 * 示例：原价 9900 分（99元），券抵扣 1000 分（10元），实付 8900 分（89元）
 */
@Data
@Builder
public class OrderVO {

    /** 订单 ID（String 类型，避免 JS Long 精度丢失）。 */
    private String orderId;

    /**
     * 业务订单号（对外展示的流水号）。
     * 格式：雪花 ID 转 String，全局唯一，与 orderId 不同。
     * orderId 是数据库主键（内部使用），orderNo 是业务单号（展示、客服查询用）。
     */
    private String orderNo;

    /** 门店 ID（String）。 */
    private String shopId;

    /** 门店名称（冗余字段，避免前端再查一次门店接口）。 */
    private String shopName;

    /** 原价金额（分）。 */
    private Integer originalAmount;

    /** 优惠券抵扣金额（分），0 = 未使用券。 */
    private Integer couponDiscount;

    /** 实付金额（分）= originalAmount - couponDiscount。 */
    private Integer orderAmount;

    /**
     * 订单状态：WAIT_PAY / PAID / CANCELLED / COMPLETED。
     * 前端根据此字段展示不同操作按钮：
     *   WAIT_PAY → 展示「去支付」「取消订单」
     *   PAID     → 展示「订单详情」
     *   CANCELLED → 展示「已取消」
     */
    private String orderStatus;

    /** 买家备注，可为空字符串。 */
    private String remark;

    /**
     * 订单过期时间（ISO 8601 +08:00）。
     * WAIT_PAY 状态时，前端用此字段计算倒计时：expireAt - 当前时间。
     * 超时后订单会被自动关闭（定时任务轮询）。
     */
    private OffsetDateTime expireAt;

    /**
     * 支付成功时间（ISO 8601 +08:00），WAIT_PAY 时为 null。
     * PAID 状态时展示支付时间。
     */
    private OffsetDateTime payAt;

    /** 订单创建时间（ISO 8601 +08:00）。 */
    private OffsetDateTime createdAt;
}
