package com.personalprojections.locallife.server.module.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.LocalDateTime;

/**
 * 门店 Elasticsearch 索引文档，对应索引名 {@code shop_index}。
 *
 * <h2>ES 索引 vs 数据库表</h2>
 * <p>MySQL 的 shop 表是业务主数据，ES 的 shop_index 是搜索索引（派生数据）。
 * 两者保持最终一致性（当前：业务写 MySQL 后双写 ES；
 * 后续 Canal 方案：MySQL Binlog → Canal → MQ → ES 消费者，解耦双写）。
 *
 * <h2>字段映射策略</h2>
 * <ul>
 *   <li>{@code @Field(type = FieldType.Text)} + {@code analyzer}：
 *       全文检索字段，用 IK 分词器（中文分词），支持「饺子」匹配「小明饺子馆」</li>
 *   <li>{@code @Field(type = FieldType.Keyword)}：
 *       精确匹配字段（不分词），用于 term query、filter、聚合</li>
 *   <li>{@code @Field(type = FieldType.GeoPoint)}：
 *       地理坐标，支持「附近 3km 的门店」geo_distance 查询</li>
 *   <li>{@code @Field(type = FieldType.Double)}：
 *       评分字段，用于 function_score 排序</li>
 * </ul>
 *
 * <h2>IK 分词器说明</h2>
 * <p>ES 默认 Standard Analyzer 对中文按字符切分（「小明饺子馆」→「小」「明」「饺」「子」「馆」），
 * 搜索「饺子」可能召回不准确。
 * IK 分词器（需要 ES 插件）按词语切分（「小明饺子馆」→「小明」「饺子馆」），
 * 搜索体验更好。
 * 当前使用标准 IK 分析器名称（ik_max_word/ik_smart），
 * 开发环境需要在 ES 容器中安装 IK 插件。
 *
 * <h2>双字段策略（text + keyword）</h2>
 * <p>shopName 同时有全文检索需求（模糊搜索）和精确匹配需求（排序/聚合），
 * 通过 {@code @InnerField} 配置多字段：
 * <pre>
 *   shopName       → text（IK 分词，全文搜索用）
 *   shopName.raw   → keyword（不分词，精确匹配 / 聚合用）
 * </pre>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "shop_index")   // ES 索引名
@Setting(settingPath = "es/shop-index-settings.json") // 自定义分析器配置
public class ShopDocument {

    /**
     * 文档 ID，对应 MySQL shop.id（Long 转 String）。
     * ES 文档 ID 是字符串类型，所以雪花 Long 要转 String 存储。
     */
    @Id
    private String id;

    /**
     * 门店名称，双字段：
     * - shopName（text，IK 分词）：全文搜索
     * - shopName.raw（keyword）：精确匹配、聚合
     */
    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = {
                    @InnerField(suffix = "raw", type = FieldType.Keyword)
            }
    )
    private String shopName;

    /**
     * 门店分类 ID（精确匹配，用于按分类过滤）。
     * Keyword 类型：不分词，支持 term query 精确过滤（如 categoryId = 1）。
     */
    @Field(type = FieldType.Keyword)
    private Integer categoryId;

    /**
     * 门店描述（全文检索，IK 分词）。
     * 用户搜索「川菜」「火锅」等关键词时，从 description 中召回相关门店。
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String description;

    /**
     * 详细地址（全文检索，IK 分词）。
     * 用户搜索「西湖区」「文三路」时，从 address 中召回附近门店。
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String address;

    /**
     * 地理坐标（经纬度），格式：{lat: 30.27, lon: 120.15}。
     * 支持 geo_distance 查询（「附近 3km 的门店」）和 geo_bounding_box（矩形范围）。
     * 与 longitude + latitude 字段对应，ES 侧合并为 GeoPoint。
     */
    @GeoPointField
    private String location; // "lat,lon" 格式：如 "30.273820,120.153559"

    /**
     * 综合评分（0.0~5.0），用于搜索结果排序。
     * function_score query：score = 文本相关性 × f(score)，评分高的排前面。
     */
    @Field(type = FieldType.Double)
    private Double score;

    /**
     * 门店价格（分），用于价格区间过滤（range query）。
     * 示例：只看 50-100 元的门店。
     */
    @Field(type = FieldType.Integer)
    private Integer price;

    /**
     * 门店状态（Keyword）。
     * 搜索时 filter：只返回 status = ONLINE 的门店，下线门店不出现在搜索结果中。
     * 用 filter 而不是 query：filter 结果可缓存，性能更好（不影响相关性评分）。
     */
    @Field(type = FieldType.Keyword)
    private String status;

    /**
     * 关联商家 ID（Keyword，精确匹配）。
     * 用于商家管理后台搜索自己的门店（term query: merchantId = current）。
     */
    @Field(type = FieldType.Keyword)
    private Long merchantId;

    /**
     * 最后同步时间（ES 文档更新时间戳）。
     * 用于增量同步：Canal 方案中，只同步比此时间更新的 MySQL 记录。
     */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime syncAt;
}
