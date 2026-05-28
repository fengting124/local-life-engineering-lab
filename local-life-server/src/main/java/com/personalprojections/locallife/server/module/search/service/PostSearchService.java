package com.personalprojections.locallife.server.module.search.service;

import com.personalprojections.locallife.server.common.result.PageResult;
import com.personalprojections.locallife.server.domain.entity.Post;
import com.personalprojections.locallife.server.module.search.document.PostDocument;
import com.personalprojections.locallife.server.module.search.dto.PostSearchRequest;
import com.personalprojections.locallife.server.module.search.dto.PostSearchVO;
import com.personalprojections.locallife.server.module.search.repository.PostSearchRepository;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
import org.springframework.data.elasticsearch.core.query.Criteria;
import org.springframework.data.elasticsearch.core.query.CriteriaQuery;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;

/**
 * 笔记搜索 Service，封装 Elasticsearch 搜索逻辑。
 *
 * <h2>搜索字段权重设计</h2>
 * <p>笔记搜索命中多个字段时，不同字段的权重不同：
 * <ul>
 *   <li>title（标题）权重最高 — 标题匹配说明内容高度相关</li>
 *   <li>shopName（门店名）权重次之 — 按门店维度搜索时很重要</li>
 *   <li>content（正文）权重最低 — 正文命中可能只是顺带提到</li>
 * </ul>
 * <p>当前 CriteriaQuery 实现中，多字段 OR 条件不支持精确设置权重（需要 NativeQuery function_score）。
 * 简化方案：title 和 shopName 用 matches，content 用 matches，统一权重，
 * 后续优化时切换为 NativeQuery + function_score（参考 ShopSearchService 注释）。
 *
 * <h2>双写同步方法</h2>
 * <p>{@code syncPost()} 和 {@code removePost()} 供 PostService 调用，
 * 在 MySQL 写入后立刻同步更新 ES 索引。
 *
 * <h2>内容摘要截取</h2>
 * <p>搜索结果只展示 content 的前 150 字符（摘要），避免传输大量正文文字。
 * 截取逻辑在 {@code toSearchVO()} 中处理，ES 文档存的是完整 content。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostSearchService {

    private final ElasticsearchOperations elasticsearchOperations;
    private final PostSearchRepository postSearchRepository;

    /** 内容摘要最大长度（字符数） */
    private static final int CONTENT_SUMMARY_MAX_LENGTH = 150;

    // ===== 双写同步方法 =====

    /**
     * 将笔记数据同步到 ES 索引（双写场景：发布笔记后调用）。
     *
     * @param post      MySQL post 实体
     * @param shopName  冗余字段：调用方传入门店名（PostService 知道 shopName）
     */
    public void syncPost(Post post, String shopName) {
        PostDocument doc = postToDocument(post, shopName);
        postSearchRepository.save(doc);
        log.debug("[ES] 笔记同步成功: postId={}, title={}", post.getId(), post.getTitle());
    }

    /**
     * 从 ES 索引中删除笔记（笔记删除后调用）。
     *
     * <p>MySQL 逻辑删除（deleted=1），ES 物理删除文档。
     * 搜索结果中不应出现已删除的笔记。
     *
     * @param postId MySQL post ID
     */
    public void removePost(Long postId) {
        postSearchRepository.deleteById(String.valueOf(postId));
        log.debug("[ES] 笔记从索引中删除: postId={}", postId);
    }

    /**
     * 更新笔记的点赞数快照到 ES（定期或实时同步点赞数）。
     *
     * <p>方案说明：
     * 点赞数实时存在 Redis，不需要每次点赞都同步 ES（性能代价太高）。
     * 通过 {@code @Scheduled} 定期任务，批量将 Redis 点赞数同步到 ES，
     * 让搜索排序使用的 likeCount 保持相对准确（允许有几分钟的延迟）。
     *
     * <p>当前实现：通过 Repository.save 更新整个文档（简单实现）。
     * 后续优化：通过 ES Update API 只更新 likeCount 字段（部分更新，减少 IO）。
     *
     * @param postId   MySQL post ID
     * @param likeCount 最新点赞数
     */
    public void updateLikeCount(Long postId, int likeCount) {
        postSearchRepository.findById(String.valueOf(postId)).ifPresent(doc -> {
            doc.setLikeCount(likeCount);
            doc.setSyncAt(LocalDateTime.now());
            postSearchRepository.save(doc);
            log.debug("[ES] 笔记点赞数更新: postId={}, likeCount={}", postId, likeCount);
        });
    }

    // ===== 搜索方法 =====

    /**
     * 笔记全文搜索。
     *
     * <p>搜索入口：{@code GET /api/v1/posts/search}，参数见 {@link PostSearchRequest}。
     *
     * <h3>Query 构建流程</h3>
     * <ol>
     *   <li>必须：filter status = PUBLISHED（草稿不出现在搜索结果）</li>
     *   <li>可选：filter shopId（某门店的所有笔记）</li>
     *   <li>可选：filter userId（某用户的所有笔记）</li>
     *   <li>可选：multi_match keyword（关键词全文搜索）</li>
     *   <li>排序：relevance（相关性）/ latest（最新）/ popular（最热）</li>
     * </ol>
     *
     * @param req 搜索请求参数
     * @return 分页搜索结果
     */
    public PageResult<PostSearchVO> searchPosts(PostSearchRequest req) {
        // Step 1: 基础过滤 — 只搜已发布的笔记
        Criteria criteria = new Criteria("status").is("PUBLISHED");

        // Step 2: 按门店过滤（term query，精确匹配）
        if (StringUtils.hasText(req.getShopId())) {
            criteria = criteria.and(new Criteria("shopId").is(req.getShopId()));
        }

        // Step 3: 按用户过滤（term query，精确匹配）
        if (StringUtils.hasText(req.getUserId())) {
            criteria = criteria.and(new Criteria("userId").is(req.getUserId()));
        }

        // Step 4: 关键词全文搜索（三字段 OR 匹配）
        if (StringUtils.hasText(req.getKeyword())) {
            Criteria keywordCriteria = new Criteria("title").matches(req.getKeyword())
                    .or(new Criteria("content").matches(req.getKeyword()))
                    .or(new Criteria("shopName").matches(req.getKeyword()));
            criteria = criteria.and(keywordCriteria);
        }

        // Step 5: 构建分页和排序
        Sort sort = buildSort(req.getSortBy());
        PageRequest pageRequest = PageRequest.of(req.getPageNumber() - 1, req.getPageSize(), sort);
        CriteriaQuery query = new CriteriaQuery(criteria).setPageable(pageRequest);

        // Step 6: 执行搜索
        SearchHits<PostDocument> hits = elasticsearchOperations.search(query, PostDocument.class);

        // Step 7: 转换结果
        List<PostSearchVO> items = new ArrayList<>();
        for (SearchHit<PostDocument> hit : hits.getSearchHits()) {
            items.add(toSearchVO(hit));
        }

        return PageResult.of(hits.getTotalHits(), req.getPageNumber(), req.getPageSize(), items);
    }

    // ===== 私有工具方法 =====

    /**
     * 根据 sortBy 参数构建 Sort 对象。
     *
     * <ul>
     *   <li>{@code relevance}：ES 默认按 _score 排序（不需要显式指定）</li>
     *   <li>{@code latest}：按 createdAt 倒序</li>
     *   <li>{@code popular}：按 likeCount 倒序</li>
     * </ul>
     *
     * <p>注意：CriteriaQuery 不支持直接按 ES _score 排序的 Sort 对象，
     * 省略 sort 时 ES 默认就是按相关性评分（_score DESC）排序，
     * 所以 "relevance" 情况直接返回 Sort.unsorted()。
     */
    private Sort buildSort(String sortBy) {
        return switch (sortBy == null ? "relevance" : sortBy) {
            case "latest" -> Sort.by(Sort.Direction.DESC, "createdAt");
            case "popular" -> Sort.by(Sort.Direction.DESC, "likeCount");
            // relevance 或其他未知值：ES 默认按 _score（相关性）排序
            default -> Sort.unsorted();
        };
    }

    /**
     * 将 ES SearchHit 转换为前端展示的 VO。
     *
     * <p>关键处理：
     * <ul>
     *   <li>内容截取：content 最多取前 {@value CONTENT_SUMMARY_MAX_LENGTH} 字符</li>
     *   <li>时间转换：LocalDateTime → OffsetDateTime（+08:00），与接口规范一致</li>
     *   <li>relevanceScore：从 SearchHit._score 中获取，方便调试</li>
     * </ul>
     */
    private PostSearchVO toSearchVO(SearchHit<PostDocument> hit) {
        PostDocument doc = hit.getContent();

        // 内容摘要：超过 MAX_LENGTH 时截取并加省略号
        String contentSummary = doc.getContent();
        if (contentSummary != null && contentSummary.length() > CONTENT_SUMMARY_MAX_LENGTH) {
            contentSummary = contentSummary.substring(0, CONTENT_SUMMARY_MAX_LENGTH) + "...";
        }

        // LocalDateTime → OffsetDateTime(+08:00)
        OffsetDateTime createdAt = null;
        if (doc.getCreatedAt() != null) {
            createdAt = doc.getCreatedAt().atOffset(ZoneOffset.ofHours(8));
        }

        return PostSearchVO.builder()
                .postId(doc.getId())
                .userId(doc.getUserId())
                .shopId(doc.getShopId())
                .title(doc.getTitle())
                .contentSummary(contentSummary)
                .shopName(doc.getShopName())
                .likeCount(doc.getLikeCount())
                .createdAt(createdAt)
                .relevanceScore(hit.getScore())
                .build();
    }

    /**
     * 将 MySQL Post 实体转换为 ES PostDocument。
     *
     * @param post     MySQL post 实体
     * @param shopName 冗余字段（调用方提供）
     * @return ES 文档
     */
    private PostDocument postToDocument(Post post, String shopName) {
        return PostDocument.builder()
                .id(String.valueOf(post.getId()))
                .userId(String.valueOf(post.getUserId()))
                .shopId(String.valueOf(post.getShopId()))
                .title(post.getTitle())
                .content(post.getContent())
                .shopName(shopName)
                .likeCount(post.getLikeCount() != null ? post.getLikeCount() : 0)
                .status(post.getStatus())
                .createdAt(post.getCreatedAt())
                .syncAt(LocalDateTime.now())
                .build();
    }
}
