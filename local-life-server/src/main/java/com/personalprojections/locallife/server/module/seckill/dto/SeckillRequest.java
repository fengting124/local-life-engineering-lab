package com.personalprojections.locallife.server.module.seckill.dto;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import lombok.Data;

/**
 * 秒杀抢券请求体。
 *
 * <h2>接口约定</h2>
 * <pre>
 *   POST /api/v1/seckill
 *   Authorization: Bearer {token}
 *   Content-Type: application/json
 * </pre>
 *
 * <h2>为什么同时传 sessionId 和 couponTemplateId</h2>
 * <p>sessionId 确定「哪场秒杀」（时间窗 + Redis 库存 Key 的一部分），
 * couponTemplateId 确定「哪张券」（从模板读取券规则）。
 * 两者共同构成 Redis Key：{@code seckill:stock:{sessionId}:{templateId}}。
 * Service 层会校验 session.couponTemplateId == 请求的 couponTemplateId，
 * 防止参数篡改（用 A 场次 ID 搭配 B 券模板 ID 来抢 B 场次的券）。
 */
@Data
public class SeckillRequest {

    /**
     * 秒杀场次 ID，必传。
     * 用于定位 Redis 库存 Key 和校验时间窗。
     */
    @NotNull(message = "秒杀场次 ID 不能为空")
    @Positive(message = "秒杀场次 ID 不合法")
    private Long sessionId;

    /**
     * 优惠券模板 ID，必传。
     * 用于定位 Redis Key、创建 user_coupon 记录。
     */
    @NotNull(message = "优惠券模板 ID 不能为空")
    @Positive(message = "优惠券模板 ID 不合法")
    private Long couponTemplateId;
}
