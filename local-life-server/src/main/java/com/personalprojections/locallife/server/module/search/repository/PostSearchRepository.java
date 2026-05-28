package com.personalprojections.locallife.server.module.search.repository;

import com.personalprojections.locallife.server.module.search.document.PostDocument;
import org.springframework.data.elasticsearch.repository.ElasticsearchRepository;
import org.springframework.stereotype.Repository;

/**
 * 探店笔记 Elasticsearch Repository。
 *
 * <h2>继承结构</h2>
 * <p>{@code ElasticsearchRepository<PostDocument, String>}
 * 第一个泛型参数是文档类型，第二个是文档 ID 类型（String，因为 ES 文档 ID 是字符串）。
 *
 * <h2>双写场景</h2>
 * <pre>
 *   PostService.createPost()
 *         ↓ 写 MySQL post 表（主数据）
 *   PostSearchService.syncPost()
 *         ↓ 写 ES post_index（搜索索引）
 *   PostSearchRepository.save(postDocument)
 * </pre>
 *
 * <h2>删除场景（双删）</h2>
 * <pre>
 *   PostService.deletePost()
 *         ↓ MySQL 逻辑删除（deleted = 1）
 *   PostSearchRepository.deleteById(postId)
 *         ↓ ES 物理删除文档（搜索结果中不再出现）
 * </pre>
 *
 * <p>注意：ES 没有逻辑删除概念，需要物理删除 ES 文档，
 * 但 MySQL 依然保留逻辑删除记录供数据分析使用。
 */
@Repository
public interface PostSearchRepository extends ElasticsearchRepository<PostDocument, String> {
    // 复杂搜索查询（multi_match + likeCount 加权排序）在 PostSearchService 中实现
}
