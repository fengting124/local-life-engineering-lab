package com.personalprojections.locallife.server.module.search.document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import org.springframework.data.annotation.Id;
import org.springframework.data.elasticsearch.annotations.*;

import java.time.LocalDateTime;

/**
 * 探店笔记 Elasticsearch 索引文档，对应索引名 {@code post_index}。
 *
 * <h2>ES 索引的作用</h2>
 * <p>MySQL 的 post 表是主数据，ES 的 post_index 是搜索索引（派生数据）。
 * 用户搜索「西湖区好吃的」时，从 ES 的 title + content + shopName 中全文召回，
 * 而不是走 MySQL LIKE '%西湖区%'（全表扫描，性能差）。
 *
 * <h2>笔记搜索的核心场景</h2>
 * <ol>
 *   <li>关键词搜索：「杭州火锅」→ 从 title、content、shopName 中全文匹配</li>
 *   <li>门店维度筛选：查看某门店的所有笔记（term query: shopId = xxx）</li>
 *   <li>用户维度筛选：查看某用户的所有笔记（term query: userId = xxx）</li>
 *   <li>热度排序：点赞数高的排前面（function_score: likeCount 加权）</li>
 *   <li>时间排序：最新发布的排前面（sort: createdAt DESC）</li>
 * </ol>
 *
 * <h2>冗余 shopName 字段的必要性</h2>
 * <p>ES 文档是非关系型，join 代价极高。
 * 把 shopName 冗余存入 PostDocument，用户搜索「小明饺子馆」时
 * 一次查询就能同时匹配笔记 content 和 shopName，不需要再关联门店表。
 * 代价：shop.shopName 更新时需要同步更新 ES（通过双写或 Canal 异步同步）。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@Document(indexName = "post_index")
@Setting(settingPath = "es/post-index-settings.json")
public class PostDocument {

    /**
     * 文档 ID，对应 MySQL post.id（Long 转 String）。
     * 原因与 ShopDocument 相同：ES 文档 ID 是字符串，雪花算法 Long 需转 String。
     */
    @Id
    private String id;

    /**
     * 作者用户 ID（Keyword，精确匹配）。
     * 用途：查看「我的笔记列表」时 term query: userId = current。
     * 不需要全文搜索，所以用 Keyword 而不是 Text。
     */
    @Field(type = FieldType.Keyword)
    private String userId;

    /**
     * 关联门店 ID（Keyword，精确匹配）。
     * 用途：查看「某门店的所有笔记」时 term query: shopId = xxx。
     */
    @Field(type = FieldType.Keyword)
    private String shopId;

    /**
     * 笔记标题，双字段策略：
     * - title（text，IK 分词）：全文搜索，「饺子」可以匹配「小明饺子馆探店」
     * - title.raw（keyword）：精确匹配，用于聚合或精确排序（当前暂不使用）
     *
     * <p>searchAnalyzer = ik_smart：搜索时用细粒度分词（ik_smart 比 ik_max_word 更准），
     * 避免过度切词导致召回不准确。
     * analyzer = ik_max_word：索引时用粗粒度分词（切出尽可能多的词），提高召回率。
     */
    @MultiField(
            mainField = @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart"),
            otherFields = {
                    @InnerField(suffix = "raw", type = FieldType.Keyword)
            }
    )
    private String title;

    /**
     * 笔记正文（全文检索，IK 分词）。
     * 正文是最重要的搜索字段——用户的详细描述（「菜品好吃、服务热情」）
     * 往往藏在 content 里，搜索「菜品好吃」能从 content 中精准召回。
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String content;

    /**
     * 冗余门店名（全文检索，IK 分词）。
     * 用途：用户搜索「小明饺子馆」时，同时在笔记标题/内容/门店名中搜索，
     * 让搜索结果更丰富（不是只有内容里提到门店名的笔记才出现）。
     * 冗余的代价：门店改名时需要同步更新 ES（Canal 方案会异步处理）。
     */
    @Field(type = FieldType.Text, analyzer = "ik_max_word", searchAnalyzer = "ik_smart")
    private String shopName;

    /**
     * 点赞数（Integer），用于 function_score 排序加权。
     * 搜索结果排序规则：score = 文本相关性得分 × log(1 + likeCount)
     * → 相关性相同时，点赞多的排前面
     * → 相关性高但点赞少时，仍然能排前面
     *
     * <p>此字段是「快照值」，不要求绝对实时，ES 同步时会更新。
     */
    @Field(type = FieldType.Integer)
    private Integer likeCount;

    /**
     * 笔记状态（Keyword）。
     * 搜索时 filter：只返回 status = PUBLISHED 的笔记，草稿不出现在搜索结果中。
     * 与 ShopDocument.status 同理：用 filter 不影响相关性评分，且结果可缓存。
     */
    @Field(type = FieldType.Keyword)
    private String status;

    /**
     * 发布时间，支持时间范围过滤和时间倒序排列。
     * DateFormat.date_hour_minute_second：对应 ES 日期格式 "yyyy-MM-dd'T'HH:mm:ss"
     *
     * <p>常见用法：
     * - sort: createdAt DESC（最新发布的排前面）
     * - range query: createdAt >= 7 天前（只看最近一周的笔记）
     */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime createdAt;

    /**
     * 最后同步时间，用于增量同步。
     * 与 ShopDocument.syncAt 同理，Canal 方案中用于只同步增量变更。
     */
    @Field(type = FieldType.Date, format = DateFormat.date_hour_minute_second)
    private LocalDateTime syncAt;
}
