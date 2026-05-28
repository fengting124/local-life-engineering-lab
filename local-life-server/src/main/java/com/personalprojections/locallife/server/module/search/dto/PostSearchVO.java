package com.personalprojections.locallife.server.module.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.OffsetDateTime;

/**
 * 笔记搜索结果单条 VO。
 *
 * <h2>与 PostVO 的区别</h2>
 * <p>PostVO 是笔记详情接口的完整响应（含 content 全文）。
 * PostSearchVO 是搜索结果列表展示用的精简 VO，content 只显示摘要（前 150 字符），
 * 减少网络传输量。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class PostSearchVO {

    /**
     * 笔记 ID（String 类型）。
     */
    private String postId;

    /**
     * 作者用户 ID（String 类型）。
     */
    private String userId;

    /**
     * 关联门店 ID（String 类型）。
     */
    private String shopId;

    /** 笔记标题（完整展示）。 */
    private String title;

    /**
     * 笔记内容摘要（最多 150 字符），避免搜索结果列表传输大量正文。
     * Service 层截取，超过 150 字符时在末尾加「...」。
     */
    private String contentSummary;

    /** 冗余门店名，搜索结果中展示「关联门店」信息。 */
    private String shopName;

    /**
     * 点赞数，搜索结果列表的热度指示器。
     */
    private Integer likeCount;

    /**
     * 发布时间（ISO 8601 + 东八区），前端显示「X 天前」。
     * OffsetDateTime 序列化为 "2026-05-26T20:00:00+08:00" 格式，与接口规范一致。
     */
    private OffsetDateTime createdAt;

    /**
     * ES 相关性评分（调试用），正式版可以去掉。
     */
    private Float relevanceScore;
}
