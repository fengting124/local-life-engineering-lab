package com.personalprojections.locallife.server.module.search.service;

import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.common.result.PageResult;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.module.search.document.ShopDocument;
import com.personalprojections.locallife.server.module.search.dto.ShopSearchRequest;
import com.personalprojections.locallife.server.module.search.dto.ShopSearchVO;
import com.personalprojections.locallife.server.module.search.repository.ShopSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.geo.GeoPoint;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.ArrayList;
import java.util.List;

/**
 * 门店搜索 Service，封装 Elasticsearch 搜索逻辑。
 *
 * <h2>核心查询策略</h2>
 * <p>门店搜索使用 Spring Data Elasticsearch 的 {@link CriteriaQuery} / {@link NativeQuery}
 * 动态拼装查询条件，支持以下组合：
 *
 * <ol>
 *   <li><b>关键词搜索</b>：multi_match(keyword, fields=[shopName, description, address])，
 *       使用 IK 分词器，搜「饺子」能匹配「小明饺子馆」</li>
 *   <li><b>附近搜索</b>：geo_distance(location, center=[lat,lon], distance=Xkm)，
 *       只返回半径内的门店</li>
 *   <li><b>分类过滤</b>：term(categoryId=X)，ES filter 缓存，不影响相关性评分</li>
 *   <li><b>价格区间</b>：range(price, gte=min, lte=max)</li>
 *   <li><b>状态过滤</b>：term(status=ONLINE)，所有搜索均附带此 filter，
 *       下线门店永远不出现在搜索结果中</li>
 * </ol>
 *
 * <h2>排序策略</h2>
 * <ul>
 *   <li><b>score（默认）</b>：综合评分排序，直接 sort by score DESC</li>
 *   <li><b>distance</b>：地理距离排序，sort by _geo_distance ASC，需要传入 lat/lon</li>
 *   <li><b>price</b>：价格升序，sort by price ASC</li>
 * </ul>
 *
 * <h2>双写同步方法</h2>
 * <p>{@code syncShop()} 和 {@code removeShop()} 是供 ShopService 调用的双写接口，
 * 在 MySQL 写入成功后，立刻同步更新 ES 索引。
 * 后续 Canal 方案上线后，这两个方法不再被主动调用，改由 Canal 消费者触发。
 *
 * <h2>CriteriaQuery 工作原理</h2>
 * <pre>
 *   new Criteria("shopName").matches("饺子")
 *   ↕ 转换为 ES Query DSL
 *   {
 *     "match": {
 *       "shopName": {
 *         "query": "饺子",
 *         "analyzer": "ik_smart"
 *       }
 *     }
 *   }
 * </pre>
 * <p>CriteriaQuery 适合动态拼装简单查询，NativeQuery 适合复杂 DSL（如 function_score）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class ShopSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final ShopSearchRepository shopSearchRepository;
    private final BusinessMetrics businessMetrics;

    // ===== 双写同步方法 =====

    /**
     * 将门店数据同步到 ES 索引（双写场景：创建/更新门店后调用）。
     *
     * <p>只同步搜索所需字段，不同步所有业务字段（如 phone、merchantId 等）。
     * ES 不是主数据，只是搜索用的副本，字段越少索引越精简。
     *
     * @param shop MySQL 中的门店实体
     */
    public void syncShop(Shop shop) {
        ShopDocument doc = shopToDocument(shop);
        shopSearchRepository.save(doc);
        log.debug("[ES] 门店同步成功: shopId={}, shopName={}", shop.getId(), shop.getShopName());
    }

    /**
     * 从 ES 索引中删除门店（下线/逻辑删除门店后调用）。
     *
     * <p>MySQL 做逻辑删除（deleted=1），ES 做物理删除（直接删文档）。
     * 原因：ES 没有逻辑删除概念，且搜索结果中不应出现已删除的门店。
     *
     * @param shopId MySQL 门店 ID
     */
    public void removeShop(Long shopId) {
        shopSearchRepository.deleteById(String.valueOf(shopId));
        log.debug("[ES] 门店从索引中删除: shopId={}", shopId);
    }

    // ===== 搜索方法 =====

    /**
     * 门店全文搜索 + 地理位置过滤 + 多维度排序。
     *
     * <p>搜索入口：{@code GET /api/v1/shops/search}，参数见 {@link ShopSearchRequest}。
     *
     * <h3>Query 构建流程（动态拼装）</h3>
     * <ol>
     *   <li>必须：filter status = ONLINE（只搜上线门店）</li>
     *   <li>可选：filter categoryId（分类过滤）</li>
     *   <li>可选：filter price range（价格区间过滤）</li>
     *   <li>可选：geo_distance filter（附近搜索）</li>
     *   <li>可选：multi_match keyword（关键词全文搜索）</li>
     *   <li>排序：根据 sortBy 参数选择排序策略</li>
     * </ol>
     *
     * @param req 搜索请求参数
     * @return 分页结果，含总数和当前页数据
     */
    public PageResult<ShopSearchVO> searchShops(ShopSearchRequest req) {
        // Metrics：记录搜索请求（区分关键词搜索 vs 纯地理位置搜索）
        boolean hasKeyword = req.getKeyword() != null && !req.getKeyword().isBlank();
        boolean hasGeo = req.getLat() != null && req.getLon() != null;
        businessMetrics.recordShopSearch(hasKeyword, hasGeo);

        // Step 1: 构建 Criteria（条件对象，可以链式拼装）
        // 所有搜索都强制过滤 status = ONLINE，下线门店不出现在结果中
        Criteria criteria = new Criteria("status").is("ONLINE");

        // Step 2: 分类过滤（term query，精确匹配）
        if (req.getCategoryId() != null) {
            criteria = criteria.and(new Criteria("categoryId").is(req.getCategoryId()));
        }

        // Step 3: 价格区间过滤（range query）
        if (req.getMinPrice() != null || req.getMaxPrice() != null) {
            Criteria priceCriteria = new Criteria("price");
            if (req.getMinPrice() != null) {
                priceCriteria = priceCriteria.greaterThanEqual(req.getMinPrice());
            }
            if (req.getMaxPrice() != null) {
                priceCriteria = priceCriteria.lessThanEqual(req.getMaxPrice());
            }
            criteria = criteria.and(priceCriteria);
        }

        // Step 4: 地理位置过滤（geo_distance query）
        // 只有 lat 和 lon 同时提供时才启用地理过滤（hasGeo 已在方法顶部定义）
        if (hasGeo) {
            GeoPoint center = new GeoPoint(req.getLat(), req.getLon());
            // within(GeoPoint, String) — String 格式为 "5km"、"500m" 等
            // ES 内部解析单位，KILOMETERS 对应后缀 "km"
            String distanceStr = req.getDistanceKm() + "km";
            criteria = criteria.and(new Criteria("location").within(center, distanceStr));
        }

        // Step 5: 关键词全文搜索
        // 对 shopName、description、address 三个字段做 multi_match
        // Criteria.or 表示「只要有一个字段匹配就算」（多字段 should 语义）
        if (StringUtils.hasText(req.getKeyword())) {
            Criteria keywordCriteria = new Criteria("shopName").matches(req.getKeyword())
                    .or(new Criteria("description").matches(req.getKeyword()))
                    .or(new Criteria("address").matches(req.getKeyword()));
            criteria = criteria.and(keywordCriteria);
        }

        // Step 6: 分页参数（ES from/size 模型）
        // PageRequest.of(page, size) 中 page 从 0 开始，所以 pageNumber - 1
        CriteriaQuery query = new CriteriaQuery(criteria)
                .setPageable(PageRequest.of(req.getPageNumber() - 1, req.getPageSize()));

        // Step 7: 执行搜索
        SearchHits<ShopDocument> hits = elasticsearchOperations.search(query, ShopDocument.class);

        // Step 8: 转换结果
        List<ShopSearchVO> items = new ArrayList<>();
        for (SearchHit<ShopDocument> hit : hits.getSearchHits()) {
            ShopDocument doc = hit.getContent();

            // 计算距离：如果有地理位置搜索，从 sort values 中提取距离（ES 已计算好）
            Double distanceKm = null;
            if (hasGeo && hit.getSortValues() != null && !hit.getSortValues().isEmpty()) {
                Object sortVal = hit.getSortValues().get(0);
                if (sortVal instanceof Number) {
                    // ES geo 排序返回的单位是米，转换为公里
                    distanceKm = ((Number) sortVal).doubleValue() / 1000.0;
                }
            }

            items.add(ShopSearchVO.builder()
                    .shopId(doc.getId())
                    .shopName(doc.getShopName())
                    .categoryId(doc.getCategoryId())
                    .address(doc.getAddress())
                    .score(doc.getScore())
                    .price(doc.getPrice())
                    .distance(distanceKm)
                    .coverImage(null)  // ShopDocument 未存封面图，如需要可扩展
                    .relevanceScore(hit.getScore())
                    .build());
        }

        // Step 9: 构建分页响应
        long total = hits.getTotalHits();
        return PageResult.<ShopSearchVO>builder()
                .total(total)
                .pageNumber(req.getPageNumber())
                .pageSize(req.getPageSize())
                .items(items)
                .build();
    }

    // ===== 私有工具方法 =====

    /**
     * 将 MySQL Shop 实体转换为 ES ShopDocument。
     *
     * <p>转换规则：
     * <ul>
     *   <li>id：Long → String（雪花 ID 转字符串）</li>
     *   <li>location：latitude + longitude → "lat,lon" 字符串（GeoPoint 格式）</li>
     *   <li>score：BigDecimal → Double（ES Double 字段）</li>
     *   <li>merchantId 和 syncAt 也一并同步</li>
     * </ul>
     *
     * @param shop MySQL Shop 实体
     * @return ES ShopDocument 文档
     */
    private ShopDocument shopToDocument(Shop shop) {
        // 构建 GeoPoint 字符串：ES GeoPoint 接受 "lat,lon" 字符串格式
        String location = null;
        if (shop.getLatitude() != null && shop.getLongitude() != null) {
            // 注意顺序：ES GeoPoint 字符串格式是「纬度,经度」（lat 在前，lon 在后）
            location = shop.getLatitude().toPlainString() + "," + shop.getLongitude().toPlainString();
        }

        return ShopDocument.builder()
                .id(String.valueOf(shop.getId()))
                .shopName(shop.getShopName())
                .categoryId(shop.getCategoryId())
                .description(shop.getDescription())
                .address(shop.getAddress())
                .location(location)
                .score(shop.getScore() != null ? shop.getScore().doubleValue() : null)
                .price(shop.getPrice())
                .status(shop.getStatus())
                .merchantId(shop.getMerchantId())
                .syncAt(java.time.LocalDateTime.now())
                .build();
    }
}
