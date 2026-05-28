package com.personalprojections.locallife.server.module.search.repository;

import com.personalprojections.locallife.server.module.search.document.ShopDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * 门店 Elasticsearch Repository，继承 {@link ElasticsearchRepository}。
 *
 * <h2>ElasticsearchRepository 提供的内置方法</h2>
 * <ul>
 *   <li>{@code save(ShopDocument)} — 保存/更新单个文档（双写时调用）</li>
 *   <li>{@code saveAll(Iterable)} — 批量保存（数据初始化/全量同步时调用）</li>
 *   <li>{@code findById(String)} — 按 ID 查询单文档</li>
 *   <li>{@code deleteById(String)} — 删除单个文档</li>
 *   <li>{@code count()} — 统计索引文档总数</li>
 * </ul>
 *
 * <h2>为什么不把复杂查询放在 Repository 里</h2>
 * <p>ElasticsearchRepository 支持 Spring Data 的方法命名查询（如 findByShopName），
 * 但复杂查询（多字段 multi_match、geo_distance、function_score）用命名方法很难表达，
 * 容易出错且不直观。
 *
 * <p>解决方案：Repository 只做简单的 CRUD，复杂搜索逻辑放在 {@code ShopSearchService}
 * 中使用 {@link org.springframework.data.elasticsearch.core.ElasticsearchOperations}
 * 直接构建 Query 对象，灵活且可读性强。
 *
 * <h2>双写架构示意</h2>
 * <pre>
 *   用户创建/更新门店
 *         ↓
 *   ShopService.createShop()   ← 写 MySQL（主数据）
 *         ↓
 *   ShopSearchService.syncShop()  ← 写 ES（搜索索引）
 *         ↓
 *   ShopSearchRepository.save()
 * </pre>
 *
 * <p>后续 Canal 方案上线后，ShopService 不再主动调用 ShopSearchService，
 * 改由 Canal 监听 MySQL Binlog → MQ → ES 消费者写入，彻底解耦。
 */
@Repository
public interface ShopSearchRepository extends ElasticsearchRepository<ShopDocument, String> {

    // 简单查询方法由 ElasticsearchRepository 父类提供（save/findById/deleteById 等）
    // 复杂查询（multi_match + geo_distance + function_score）在 ShopSearchService 中实现
}
