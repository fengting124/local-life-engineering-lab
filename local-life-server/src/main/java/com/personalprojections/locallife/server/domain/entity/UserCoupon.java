package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户已领优惠券实体，对应数据库表 {@code user_coupon}。
 *
 * <h2>这张表的数据从哪里来</h2>
 * <p>不是在秒杀主链路里同步写入，而是通过 MQ 消费者异步写入：
 * <ol>
 *   <li>用户秒杀请求打到 SeckillService</li>
 *   <li>Redis Lua 脚本预扣库存成功 → 发 MQ 消息（含 userId、sessionId、couponTemplateId）</li>
 *   <li>MQ 消费者收到消息 → 执行业务逻辑 → INSERT user_coupon</li>
 * </ol>
 * 好处：数据库写入从「高并发尖峰」变成「平稳异步」，秒杀主链路不阻塞在 DB 写入上。
 *
 * <h2>一人一单的双层保证</h2>
 * <ol>
 *   <li>Redis Set 判重（seckill:user:{sessionId}:{couponTemplateId}）：
 *       Lua 脚本里原子性检查，快速拒绝重复请求（第一道防线，毫秒级）</li>
 *   <li>数据库唯一索引 uk_user_coupon_template (user_id, coupon_template_id)：
 *       即使 Redis 数据丢失或异常，INSERT 时也会因唯一索引冲突而失败（最终兜底）</li>
 * </ol>
 *
 * <h2>couponStatus 状态机</h2>
 * <pre>
 *   UNUSED → USED   （用券核销，在下单模块完成）
 *   UNUSED → EXPIRED（定时任务在 expireAt 到期时更新，或下单时检查）
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("user_coupon")
public class UserCoupon {

    /** 用户券 ID，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 领取用户 ID，逻辑外键 → user.id。 */
    private Long userId;

    /** 券模板 ID，逻辑外键 → coupon_template.id。 */
    private Long couponTemplateId;

    /** 来自哪个秒杀场次，逻辑外键 → seckill_session.id。 */
    private Long seckillSessionId;

    /**
     * 券状态：UNUSED（未使用）/ USED（已使用）/ EXPIRED（已过期）。
     * 创建时为 UNUSED。
     */
    private String couponStatus;

    /**
     * 领取时间（MQ 消费时 = 当前时间，非秒杀请求的时间）。
     * 有轻微延迟（MQ 投递 + 消费耗时），可接受。
     */
    private LocalDateTime receivedAt;

    /**
     * 到期时间 = receivedAt + couponTemplate.validDays 天。
     * 在 MQ 消费者里计算填入。
     */
    private LocalDateTime expireAt;

    /**
     * 使用时间，UNUSED 状态时为 NULL。
     * 下单核销时更新此字段，同时把 couponStatus 改为 USED。
     */
    private LocalDateTime usedAt;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
