package com.personalprojections.locallife.server.module.post.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 评论信息 VO，返回给前端。
 *
 * <h2>冗余字段设计原则</h2>
 * <p>VO 中包含 nickname、avatar 等来自 user 表的冗余字段，
 * 避免前端拿到 userId 后再调「查用户」接口（减少 RTT，降低接口依赖）。
 * 权衡：评论列表一次最多 20 条，在 Service 层批量查 user 信息再组装（N+1 查询变批量查询）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class CommentVO {

    /** 评论 ID，字符串（防 JS 精度截断）。 */
    private String commentId;

    /** 评论者用户 ID，字符串。 */
    private String userId;

    /** 评论者昵称（冗余，避免前端再查用户接口）。 */
    private String nickname;

    /** 评论者头像 URL（冗余）。 */
    private String avatar;

    /** 评论内容。 */
    private String content;

    /**
     * 评论发布时间，ISO 8601 + 东八区。
     * 前端格式化为「5 分钟前」等友好格式。
     */
    private OffsetDateTime createdAt;
}
