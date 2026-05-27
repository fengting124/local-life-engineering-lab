package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 用户关注关系实体，对应数据库表 {@code follow_relation}。
 *
 * <h2>关系语义</h2>
 * <p>一条记录表示「followerUserId 关注了 followedUserId」：
 * <ul>
 *   <li>{@code followerUserId}：主动方（粉丝，「我」关注了别人）</li>
 *   <li>{@code followedUserId}：被动方（博主，别人关注了「我」）</li>
 * </ul>
 *
 * <h2>关注/取关不用逻辑删除</h2>
 * <p>与其他实体不同，follow_relation 关注时 INSERT，取关时物理 DELETE，原因：
 * <ol>
 *   <li>关注关系变化频繁，保留历史记录会持续增大表体积</li>
 *   <li>历史关注数据（什么时候关注过谁）属于行为数据，应归属数仓而非业务库</li>
 *   <li>物理删除更简洁，取关后 SELECT 自然查不到，无需 WHERE deleted = 0</li>
 * </ol>
 * 因此此实体不添加 {@code deleted} 字段和 {@code @TableLogic}。
 *
 * <h2>共同关注计算</h2>
 * <p>「我和用户 B 的共同关注」= 我关注的人 ∩ B 关注的人。
 * 当前实现方案：从 Redis Set 取交集（SINTERSTORE），Set Key 为 {@code follow:set:{userId}}。
 * 关注/取关时同步更新 Redis Set（SADD / SREM）。
 * 如果 Redis 没有该用户的 Set（冷数据），从数据库加载后写入。
 *
 * <h2>updatedAt 说明</h2>
 * <p>关注关系是不可变的（关注后不能「修改」，只能取关再关注），
 * 理论上不需要 updatedAt。但保留此字段作为统一规范（所有实体都有 INSERT_UPDATE fill）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("follow_relation")
public class FollowRelation {

    /** 关注关系 ID，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 关注者 ID（粉丝），即「主动发起关注的用户」。
     * 逻辑外键 → user.id。
     */
    private Long followerUserId;

    /**
     * 被关注者 ID（博主），即「被关注的用户」。
     * 逻辑外键 → user.id。
     */
    private Long followedUserId;

    /**
     * 关注时间，INSERT 时由 MetaObjectHandler 自动填充。
     * 面试用途：这个字段可以回答「怎么知道什么时候关注的」。
     */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;
}
