package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 秒杀场次实体，对应数据库表 {@code seckill_session}。
 *
 * <h2>场次 vs 模板的关系</h2>
 * <p>一个 CouponTemplate 可以有多个 SeckillSession（不同时间的多场秒杀）。
 * 例如：「双十一五折券」模板 → 11月11日 10:00 场 + 11月11日 20:00 场。
 *
 * <h2>Redis 库存 Key 设计</h2>
 * <p>秒杀库存存入 Redis 时使用的 Key 格式：
 * <pre>
 *   seckill:stock:{sessionId}:{couponTemplateId}
 *   seckill:user:{sessionId}:{couponTemplateId}（Set，存已抢到的 userId，用于判重）
 * </pre>
 * Key 里同时含 sessionId 和 couponTemplateId：
 * <ul>
 *   <li>sessionId 是时间窗口维度，不同场次的库存互相独立</li>
 *   <li>couponTemplateId 是券种维度，方便反查券信息</li>
 * </ul>
 *
 * <h2>sessionStatus 状态机</h2>
 * <pre>
 *   PENDING → ACTIVE（定时任务在 beginTime 时触发，同步加载 Redis 库存）
 *   ACTIVE  → ENDED  （定时任务在 endTime 时触发，同步 Redis 库存回 DB）
 *   PENDING → CANCELLED（管理员手动取消）
 *   ACTIVE  → CANCELLED（管理员在进行中取消，需清理 Redis）
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("seckill_session")
public class SeckillSession {

    /** 秒杀场次 ID，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 关联的优惠券模板 ID，逻辑外键 → coupon_template.id。 */
    private Long couponTemplateId;

    /**
     * 本场次秒杀库存（可以 < 模板 totalStock）。
     * 秒杀开始前由定时任务加载到 Redis。
     * 示例：模板总量 1000，本场次只放 100。
     */
    private Integer seckillStock;

    /** 秒杀开始时间。 */
    private LocalDateTime beginTime;

    /** 秒杀结束时间（必须晚于 beginTime）。 */
    private LocalDateTime endTime;

    /**
     * 场次状态：PENDING / ACTIVE / ENDED / CANCELLED。
     * 由定时任务（ScheduledTask）在 beginTime 和 endTime 时自动流转。
     * 当前阶段 CANCELLED 只预留，不实现取消接口。
     */
    private String sessionStatus;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
