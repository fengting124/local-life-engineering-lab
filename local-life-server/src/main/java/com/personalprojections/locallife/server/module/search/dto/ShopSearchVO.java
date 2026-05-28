package com.personalprojections.locallife.server.module.search.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 门店搜索结果单条 VO（Value Object）。
 *
 * <h2>与 ShopVO 的区别</h2>
 * <p>ShopVO 是门店详情接口的响应，字段完整（含 merchantId、businessHours 等）。
 * ShopSearchVO 是搜索结果列表中每一条的展示数据，字段精简，只含列表展示所需字段，
 * 且增加了搜索特有字段（如 distance、matchedField）。
 *
 * <p>字段设计原则：「最小化传输数据」，搜索列表不需要的字段不传，点击进详情再加载完整数据。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class ShopSearchVO {

    /**
     * 门店 ID（String 类型，雪花 Long → String，防止 JS 精度丢失）。
     */
    private String shopId;

    /** 门店名称。 */
    private String shopName;

    /**
     * 门店分类 ID。
     * 前端根据分类 ID 显示图标或标签（如「餐饮」「酒店」）。
     */
    private Integer categoryId;

    /** 详细地址，搜索结果列表中显示地址摘要。 */
    private String address;

    /**
     * 综合评分（0.0~5.0），显示星级组件。
     * 从 ES 的 score 字段获取（Double 类型）。
     */
    private Double score;

    /**
     * 价格（分）。
     * 前端显示时除以 100 转为元，并格式化（如「¥99」）。
     */
    private Integer price;

    /**
     * 与用户当前位置的距离（公里），保留两位小数。
     * 仅当请求中传入了 lat/lon 时才有值，否则为 null。
     * 示例：「0.85 km」→ distance = 0.85
     *
     * <p>距离值从 ES geo_distance 查询的 sort value 中提取，
     * 不需要应用层再计算（ES 已经算好了）。
     */
    private Double distance;

    /**
     * 门店封面图 URL，搜索结果列表的缩略图。
     */
    private String coverImage;

    /**
     * ES 相关性评分（调试用），ES 计算的 _score 值。
     * 生产环境可以不返回（或过滤掉），开发阶段方便调试搜索质量。
     */
    private Float relevanceScore;
}
