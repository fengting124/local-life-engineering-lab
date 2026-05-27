package com.personalprojections.locallife.server.module.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 优惠券模板 VO，用于 C 端展示可抢的券。
 *
 * <h2>金额字段单位</h2>
 * <p>discountValue 和 minOrderAmount 都以「分」返回，由前端格式化展示：
 * <ul>
 *   <li>CASH 类型：2000 → 「减20元」</li>
 *   <li>PERCENT 类型：80 → 「8折」</li>
 *   <li>minOrderAmount 0 → 「无门槛」，10000 → 「满100元可用」</li>
 * </ul>
 *
 * <h2>库存展示策略</h2>
 * <p>remainStock 展示数据库快照值（非 Redis 实时值）。
 * 原因：C 端展示不需要精确实时库存，快照值减少 Redis 查询压力。
 * 只有在秒杀主链路（doSeckill）时才读 Redis 精确库存。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CouponTemplateVO {

    /** 模板 ID，字符串。 */
    private String templateId;

    /** 所属门店 ID，字符串。 */
    private String shopId;

    /** 门店名称（冗余，减少前端查询）。 */
    private String shopName;

    /** 优惠券名称。 */
    private String couponName;

    /** 折扣类型：CASH / PERCENT。 */
    private String discountType;

    /** 折扣值（分或百分比整数）。 */
    private Integer discountValue;

    /** 最低使用金额（分），0 表示无门槛。 */
    private Integer minOrderAmount;

    /** 剩余库存快照（非实时，仅供展示参考）。 */
    private Integer remainStock;

    /** 每人限领次数。 */
    private Integer perUserLimit;

    /** 领券后有效天数。 */
    private Integer validDays;

    /** 秒杀场次 ID（前端发起秒杀时需要传回）。 */
    private String sessionId;

    /** 秒杀开始时间。 */
    private OffsetDateTime beginTime;

    /** 秒杀结束时间。 */
    private OffsetDateTime endTime;
}
