package com.personalprojections.locallife.server.module.seckill.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 秒杀结果 VO，用于两个场景：
 * <ol>
 *   <li>秒杀主接口（POST /api/v1/seckill）的同步响应</li>
 *   <li>查询抢券结果接口（GET /api/v1/seckill/result）的轮询响应</li>
 * </ol>
 *
 * <h2>字段说明</h2>
 * <ul>
 *   <li>{@code success}：是否抢到券（true / false）</li>
 *   <li>{@code message}：面向用户的提示文案，前端直接展示</li>
 * </ul>
 *
 * <h2>升级到异步 MQ 后的 message 含义变化</h2>
 * <ul>
 *   <li>同步阶段：success=true 表示已写入 DB，确认抢到</li>
 *   <li>MQ 阶段：POST 接口 success=true 表示「预扣成功，处理中」；
 *       轮询接口 success=true 才表示「DB 已确认写入，真正抢到」</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class SeckillResultVO {

    /** 是否成功。 */
    private Boolean success;

    /** 面向用户的提示文案，直接展示给用户。 */
    private String message;
}
