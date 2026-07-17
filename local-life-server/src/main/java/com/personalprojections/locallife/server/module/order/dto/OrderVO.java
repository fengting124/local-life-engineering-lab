package com.personalprojections.locallife.server.module.order.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

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
 * <p>该类型同时作为订单幂等账本的响应快照，需要支持 Jackson 序列化和反序列化，
 * 因此显式提供无参构造函数。</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderVO {

    /** 订单 ID（String 类型，避免 JS Long 精度丢失）。 */
    private String orderId;

    /** 业务订单号（对外展示的流水号）。 */
    private String orderNo;

    /** 门店 ID（String）。 */
    private String shopId;

    /** 门店名称。 */
    private String shopName;

    /** 原价金额（分）。 */
    private Integer originalAmount;

    /** 优惠券抵扣金额（分）。 */
    private Integer couponDiscount;

    /** 实付金额（分）。 */
    private Integer orderAmount;

    /** 订单状态：WAIT_PAY / PAID / CANCELLED / COMPLETED。 */
    private String orderStatus;

    /** 买家备注。 */
    private String remark;

    /** 订单过期时间（ISO 8601 +08:00）。 */
    private OffsetDateTime expireAt;

    /** 支付成功时间。 */
    private OffsetDateTime payAt;

    /** 订单创建时间。 */
    private OffsetDateTime createdAt;
}
