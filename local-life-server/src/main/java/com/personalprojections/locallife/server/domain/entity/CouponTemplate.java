package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 优惠券模板实体，对应数据库表 {@code coupon_template}。
 *
 * <h2>模板 vs 用户券的区别</h2>
 * <p>CouponTemplate 是「券的规则定义」，相当于印章。
 * UserCoupon 是「用户实际领到的券」，相当于用印章盖出来的印记。
 * 一个模板可以发行 N 张（total_stock 个 UserCoupon）。
 *
 * <h2>金额字段单位说明</h2>
 * <p>所有金额字段（discountValue、minOrderAmount）以「分」为单位（Integer 类型），
 * 不使用浮点数，避免精度问题。
 * 示例：20 元 → 2000，最低消费 100 元 → 10000。
 *
 * <h2>remainStock 的同步策略</h2>
 * <p>秒杀时真实库存操作在 Redis（原子性保证），此字段是「快照」：
 * <ul>
 *   <li>定时任务每隔 N 分钟把 Redis 库存同步到 remainStock</li>
 *   <li>秒杀结束后做一次最终同步</li>
 *   <li>不依赖此字段做实时库存判断，只用于展示和分析</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("coupon_template")
public class CouponTemplate {

    /** 优惠券模板 ID，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属门店 ID，逻辑外键 → shop.id。 */
    private Long shopId;

    /** 优惠券名称，如「双十一五折券」。 */
    private String couponName;

    /**
     * 折扣类型：CASH（现金抵扣）/ PERCENT（折扣比例）。
     * 与 discountValue 配合使用：
     *   CASH + discountValue=2000 → 减 20 元
     *   PERCENT + discountValue=80 → 打 8 折
     */
    private String discountType;

    /**
     * 折扣值，单位根据 discountType 决定：
     *   CASH：分（2000 = 20 元）
     *   PERCENT：百分比整数（80 = 80% = 8折）
     */
    private Integer discountValue;

    /**
     * 最低使用金额，单位：分。0 表示无使用门槛。
     * 示例：10000 = 消费满 100 元才能使用。
     */
    private Integer minOrderAmount;

    /** 总发行数量（不变，记录原始库存，用于对账）。 */
    private Integer totalStock;

    /**
     * 剩余库存快照（定期从 Redis 同步，非实时）。
     * 实时库存在 Redis：seckill:stock:{sessionId}:{couponTemplateId}
     */
    private Integer remainStock;

    /**
     * 每人限领次数，通常为 1（一人一单）。
     * 数据库唯一索引 uk_user_coupon_template 是最终兜底。
     * Redis Set 是快速判重（O(1)）的第一道防线。
     */
    private Integer perUserLimit;

    /**
     * 领券后有效天数，从领取时刻起算。
     * 过期时间 = 领取时间 + validDays 天，写入 user_coupon.expire_at。
     */
    private Integer validDays;

    /**
     * 状态：ACTIVE（正常）/ INACTIVE（已停用）。
     * INACTIVE 是终态，停用后不能再抢。
     */
    private String status;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
