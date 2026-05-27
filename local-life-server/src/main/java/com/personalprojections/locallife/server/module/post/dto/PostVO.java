package com.personalprojections.locallife.server.module.post.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;
import java.util.List;

/**
 * 探店笔记 VO，返回给前端展示。
 *
 * <h2>展示场景</h2>
 * <ul>
 *   <li>笔记详情页：全字段展示</li>
 *   <li>Feed 流列表：展示 postId、title、coverImage（第一张图）、likeCount</li>
 *   <li>门店详情页笔记 Tab：同 Feed 流列表</li>
 * </ul>
 * <p>当前用同一个 VO，后续流量大了可拆出 PostSummaryVO（精简字段）减少传输量。
 *
 * <h2>ID 为 String 的原因</h2>
 * <p>雪花算法 ID 是 Long（最大 2^63-1），超过 JavaScript 安全整数范围（2^53-1）。
 * JSON 序列化时如果传 Long，前端 JS 解析后可能丢失精度（最后几位变 0）。
 * 因此所有 ID 字段都以 String 形式返回。
 *
 * <h2>liked 字段</h2>
 * <p>当前登录用户是否已点赞该笔记，布尔值。
 * 查询时从 Redis Set {@code post:like:users:{postId}} 判断（SISMEMBER），
 * 未登录时该字段为 false（公开接口不要求登录）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostVO {

    /** 笔记 ID，字符串（防 JS 精度截断）。 */
    private String postId;

    /** 作者用户 ID，字符串。 */
    private String userId;

    /** 作者昵称（冗余字段，避免前端再次查用户接口）。 */
    private String nickname;

    /** 作者头像 URL。 */
    private String avatar;

    /** 关联门店 ID，字符串。 */
    private String shopId;

    /** 关联门店名称（冗余字段）。 */
    private String shopName;

    /** 笔记标题。 */
    private String title;

    /** 笔记正文内容。 */
    private String content;

    /**
     * 图片 URL 列表，已从 JSON 字符串解析为 Java List。
     * 前端直接迭代渲染，不需要自己再解析 JSON。
     */
    private List<String> images;

    /**
     * 点赞数，优先从 Redis 实时取，Redis 不可用时降级为数据库快照值。
     * 前端展示时取整显示，如 「1.2万」需要前端格式化。
     */
    private Integer likeCount;

    /** 评论数快照。 */
    private Integer commentCount;

    /**
     * 当前登录用户是否已点赞该笔记。
     * 从 Redis Set 查询（SISMEMBER），未登录时为 false。
     */
    private Boolean liked;

    /** 笔记状态：PUBLISHED / DRAFT。 */
    private String status;

    /**
     * 发布时间（createdAt），格式 ISO 8601 + 东八区。
     * 前端按需格式化为「3 小时前」「2026-05-26」等。
     */
    private OffsetDateTime createdAt;
}
