package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 笔记评论实体，对应数据库表 {@code comment}。
 *
 * <h2>当前阶段能力</h2>
 * <ul>
 *   <li>支持对笔记发表一级评论（parent_id = 0）</li>
 *   <li>用户可以删除自己的评论（逻辑删除）</li>
 *   <li>不支持嵌套回复（预留 parent_id 字段，后续版本实现）</li>
 * </ul>
 *
 * <h2>parent_id 设计</h2>
 * <p>当前所有评论 parent_id = 0（顶层评论）。
 * 后续如需「回复某条评论」，设置 parent_id = 被回复的 comment.id。
 * 通过同一张表存两级结构（一级评论 + 回复），查询时：
 * <ul>
 *   <li>查一级评论：WHERE post_id = ? AND parent_id = 0</li>
 *   <li>查某条评论的回复：WHERE parent_id = {commentId}</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("comment")
public class Comment {

    /** 评论 ID，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /** 所属笔记 ID，逻辑外键 → post.id。 */
    private Long postId;

    /** 评论者用户 ID，逻辑外键 → user.id。 */
    private Long userId;

    /**
     * 父评论 ID，0 表示顶层一级评论，非 0 表示回复某条评论。
     * 当前阶段业务逻辑只允许 parent_id = 0，嵌套回复预留接口但不开放。
     */
    private Long parentId;

    /** 评论内容，最多 512 字符，不能为空。 */
    private String content;

    /**
     * 逻辑删除标志：0 正常 / 1 已删除。
     * 评论删除后 comment_count 会相应减 1（post 表的快照字段同步更新）。
     */
    @TableLogic
    private Integer deleted;

    /** 评论创建时间，INSERT 时自动填充。 */
    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    /** 最后更新时间，INSERT/UPDATE 时自动填充。 */
    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
