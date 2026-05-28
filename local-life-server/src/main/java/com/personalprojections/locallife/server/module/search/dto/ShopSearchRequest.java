package com.personalprojections.locallife.server.module.search.dto;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import lombok.Data;

/**
 * 门店搜索请求参数。
 *
 * <h2>搜索入口设计说明</h2>
 * <p>门店搜索是用户最高频的操作之一，入参设计需要兼顾：
 * <ol>
 *   <li>关键词搜索（keyword）：「西湖区好吃的」→ ES multi_match</li>
 *   <li>附近搜索（lat/lon + distance）：「附近 3km 的餐厅」→ ES geo_distance</li>
 *   <li>分类过滤（categoryId）：「只看餐饮」→ ES term filter</li>
 *   <li>价格过滤（minPrice/maxPrice）：「50-100 元」→ ES range filter</li>
 *   <li>排序方式（sortBy）：「按评分排」or「按距离排」→ sort 参数</li>
 * </ol>
 *
 * <p>以上参数全部可选 —— 用户可以只填关键词，或者只开位置搜索，
 * Service 层会根据传入哪些参数动态拼装 ES Query DSL。
 *
 * <h2>分页参数</h2>
 * <p>使用传统翻页（pageNumber + pageSize），不用游标分页。
 * 原因：门店搜索结果有限（通常几十至几百条），不需要 Feed 流的无限加载场景。
 */
@Data
public class ShopSearchRequest {

    /**
     * 搜索关键词，对 shopName、description、address 做 multi_match。
     * 为空时不加文本检索条件（只靠 filter + 评分排序）。
     */
    private String keyword;

    /**
     * 用户当前纬度，配合 lon 做 geo_distance 查询。
     * 可选：不传时不加地理位置过滤。
     * 取值范围：-90.0 ~ 90.0
     */
    private Double lat;

    /**
     * 用户当前经度，配合 lat 做 geo_distance 查询。
     * 可选：不传时不加地理位置过滤。
     * 取值范围：-180.0 ~ 180.0
     */
    private Double lon;

    /**
     * 搜索半径（公里），仅 lat/lon 同时存在时有效。
     * 默认 5km，最大允许 50km（防止请求范围太大导致 ES 查询太慢）。
     */
    @Min(value = 1, message = "搜索半径最小 1 公里")
    @Max(value = 50, message = "搜索半径最大 50 公里")
    private Double distanceKm = 5.0;

    /**
     * 门店分类 ID 过滤。
     * 可选：不传时不加分类过滤，返回所有分类的门店。
     * 使用 ES term filter（精确匹配，结果可缓存）。
     */
    private Integer categoryId;

    /**
     * 价格下限（分）。
     * 可选：不传时不加价格下限过滤。
     * 示例：minPrice = 5000 → 只看 50 元以上的门店。
     */
    private Integer minPrice;

    /**
     * 价格上限（分）。
     * 可选：不传时不加价格上限过滤。
     * 示例：maxPrice = 10000 → 只看 100 元以下的门店。
     */
    private Integer maxPrice;

    /**
     * 排序方式，可选值：
     * <ul>
     *   <li>{@code score}（默认）：按综合评分 + 文本相关性排序（function_score）</li>
     *   <li>{@code distance}：按距离由近到远排序（需要 lat/lon）</li>
     *   <li>{@code price}：按价格由低到高排序</li>
     * </ul>
     */
    private String sortBy = "score";

    /**
     * 页码，从 1 开始（接口规范文档 §5.1）。
     * ES 查询时转为 from = (pageNumber - 1) * pageSize。
     */
    @Min(value = 1, message = "页码从 1 开始")
    private Integer pageNumber = 1;

    /**
     * 每页条数，默认 20，最大 100（接口规范文档 §5.1）。
     */
    @Min(value = 1, message = "每页至少 1 条")
    @Max(value = 100, message = "每页最多 100 条")
    private Integer pageSize = 20;
}
