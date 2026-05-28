package com.personalprojections.locallife.server.config;

import com.personalprojections.locallife.server.module.search.document.PostDocument;
import com.personalprojections.locallife.server.module.search.document.ShopDocument;
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.IndexOperations;

/**
 * Elasticsearch 配置类，负责在应用启动时自动创建索引（如果不存在）。
 *
 * <h2>为什么需要这个配置类</h2>
 * <p>Spring Data Elasticsearch 默认不会自动创建索引（与 Hibernate DDL auto-create 不同）。
 * 如果不手动创建，第一次调用 save/search 方法时会报「index_not_found_exception」。
 *
 * <p>这里在 {@code @PostConstruct} 中初始化索引，确保应用启动后索引已存在。
 *
 * <h2>索引创建逻辑</h2>
 * <ol>
 *   <li>检查索引是否存在（{@code indexOps.exists()}）</li>
 *   <li>不存在时创建索引（{@code indexOps.create()}）</li>
 *   <li>写入字段映射（{@code indexOps.putMapping()}），让 ES 知道各字段的类型</li>
 * </ol>
 *
 * <p>已存在的索引不会重复创建，所以重启应用是安全的。
 *
 * <h2>生产环境注意事项</h2>
 * <p>生产环境不推荐在启动时自动创建索引，应由运维/CI 脚本提前创建好，
 * 防止应用启动时因 ES 暂时不可用而导致启动失败。
 * 当前开发阶段为了便利性，保留自动创建逻辑。
 *
 * <h2>ElasticsearchOperations vs ElasticsearchRepository</h2>
 * <ul>
 *   <li>{@code ElasticsearchOperations} — 底层操作 API，可以精确控制查询 DSL</li>
 *   <li>{@code ElasticsearchRepository} — 高层封装，适合简单 CRUD 和命名查询</li>
 *   <li>两者可以共存，Repository 底层也是调用 Operations</li>
 * </ul>
 */
@Slf4j
@Configuration
@RequiredArgsConstructor
public class ElasticsearchConfig {

    /**
     * Spring Data Elasticsearch 核心操作接口。
     * 提供 indexOps（索引管理）、search（搜索）、save（保存）等操作，
     * 比 Repository 更灵活，适合复杂查询场景。
     */
    private final ElasticsearchOperations elasticsearchOperations;

    /**
     * 应用启动时自动初始化 ES 索引。
     *
     * <p>{@code @PostConstruct} 在 Spring 依赖注入完成后、Bean 可用前执行，
     * 确保 ES 客户端已经初始化好了再执行索引创建。
     *
     * <p>异常处理策略：
     * 索引初始化失败不会阻断应用启动（catch 后只打 warn 日志），
     * 原因是 ES 可能是后起的，等 ES 就绪后 Relay 任务或第一次写操作时再重试。
     * 如果需要严格保证启动时 ES 可用，可以将 catch 改为 throw。
     */
    @PostConstruct
    public void initEsIndices() {
        // 初始化门店索引
        initIndex(ShopDocument.class, "shop_index");
        // 初始化笔记索引
        initIndex(PostDocument.class, "post_index");
    }

    /**
     * 通用索引初始化方法：检查存在性 → 创建索引 → 写入 Mapping。
     *
     * @param documentClass ES 文档类，带 @Document / @Field 等注解
     * @param indexName     索引名称（用于日志打印）
     */
    private void initIndex(Class<?> documentClass, String indexName) {
        try {
            // 获取该文档类对应的索引操作接口
            IndexOperations indexOps = elasticsearchOperations.indexOps(documentClass);

            if (!indexOps.exists()) {
                // 创建索引（同时应用 @Setting 指定的分析器配置）
                indexOps.create();
                // 写入字段映射（@Field / @MultiField / @GeoPointField 等注解解析为 Mapping JSON）
                indexOps.putMapping(indexOps.createMapping());
                log.info("[ES] 索引 {} 创建成功", indexName);
            } else {
                log.debug("[ES] 索引 {} 已存在，跳过创建", indexName);
            }
        } catch (Exception e) {
            // 警告级别：不阻断启动，开发环境 ES 可能还没起来
            log.warn("[ES] 索引 {} 初始化失败，原因：{}。应用继续启动，等 ES 就绪后手动重建索引。",
                    indexName, e.getMessage());
        }
    }
}
