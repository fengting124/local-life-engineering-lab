package com.personalprojections.locallife.server.module.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 笔记搜索请求参数。
 *
 * <h2>笔记搜索场景</h2>
 * <ol>
 *   <li>全局关键词搜索：「火锅」→ 匹配所有笔记的 title + content + shopName</li>
 *   <li>按门店搜索：查看某门店的所有笔记（shopId filter）</li>
 *   <li>按用户搜索：查看某用户的所有笔记（userId filter）</li>
 *   <li>排序：最新发布 or 最多点赞</li>
 * </ol>
 *
 * <h2>游标分页 vs 传统分页</h2>
 * <p>笔记搜索（关键词搜索）使用传统翻页，笔记 Feed 流使用游标分页（cursor-based）。
 * 两者是不同的接口（/api/v1/posts/search vs /api/v1/feed），此 DTO 用于搜索接口。
 */
@Data
public class PostSearchRequest {

    /**
     * 搜索关键词，对 title、content、shopName 做 multi_match。
     * 为空时不加文本检索条件（退化为按 shopId 或 userId 的列表查询）。
     */
    private String keyword;

    /**
     * 按门店 ID 过滤，查看某门店的所有笔记。
     * 可选：不传时搜索全量笔记索引。
     */
    private String shopId;

    /**
     * 按用户 ID 过滤，查看某用户的所有笔记。
     * 可选：不传时搜索全量笔记索引。
     * 注意：String 类型，与 PostDocument.userId 的 Keyword 类型对应。
     */
    private String userId;

    /**
     * 排序方式：
     * <ul>
     *   <li>{@code relevance}（默认）：按文本相关性 + 点赞数 function_score 排序</li>
     *   <li>{@code latest}：按发布时间倒序（最新笔记排前面）</li>
     *   <li>{@code popular}：按点赞数倒序（最热笔记排前面）</li>
     * </ul>
     */
    private String sortBy = "relevance";

    /**
     * 页码，从 1 开始。
     */
    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNumber = 1;

    /**
     * 每页条数，默认 20，最大 100。
     */
    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 20;
}
