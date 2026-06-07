package com.personalprojections.locallife.server.module.search.service;

import co.elastic.clients.elasticsearch._types.query_dsl.BoolQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.FieldValueFactorModifier;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionBoostMode;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScore;
import co.elastic.clients.elasticsearch._types.query_dsl.FunctionScoreQuery;
import co.elastic.clients.elasticsearch._types.query_dsl.Query;
import co.elastic.clients.elasticsearch._types.query_dsl.TextQueryType;
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
import org.springframework.data.elasticsearch.client.elc.NativeQuery;
import org.springframework.data.elasticsearch.core.ElasticsearchOperations;
import org.springframework.data.elasticsearch.core.SearchHit;
import org.springframework.data.elasticsearch.core.SearchHits;
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
 * <h2>搜索字段权重设计：NativeQuery + multi_match boost（已完成升级）</h2>
 * <p>笔记搜索命中多个字段时，不同字段的权重不同：
 * <ul>
 *   <li>title（标题）权重最高（^3）— 标题匹配说明内容高度相关</li>
 *   <li>shopName（门店名）权重次之（^2）— 按门店维度搜索时很重要</li>
 *   <li>content（正文）权重最低（^1，默认值）— 正文命中可能只是顺带提到</li>
 * </ul>
 * <p><b>为什么 CriteriaQuery 做不到，必须换 NativeQuery</b>：CriteriaQuery 的
 * {@code Criteria("title").matches(kw).or(Criteria("content").matches(kw))} 编译为多个独立的
 * {@code match} 子句用 {@code should} 拼接，每个子句各自的字段权重无法跨子句统一调节——
 * 本质上是「转换层表达力不足」，必须直接构造 ES 原生 DSL。
 * 升级方案是 {@code multi_match} 的字段级 boost 语法：{@code fields("title^3", "shopName^2", "content")}，
 * 单条 multi_match 子句里对每个字段分别加权，是 ES 官方推荐的「多字段差异化权重」标准写法
 * （而不是 function_score——见下一节的辨析）。
 *
 * <h2>function_score 的正确用法：融合「文本相关性」与「业务热度」，而不是字段加权</h2>
 * <p>{@code PostDocument} 类注释中写的目标是「热度排序：点赞数高的排前面（function_score: likeCount 加权）」——
 * 这正是 function_score 该出场的地方：它的设计目的是把 {@code _score}（文本相关性）
 * 与一个额外的数值信号（热度、新鲜度、距离……）融合成最终排序分，公式为
 * <pre>最终得分 = combine(_score「文本相关性」, function(likeCount)「业务热度」)  // combine 方式由 boost_mode 决定</pre>
 * 本实现采用 {@code field_value_factor(likeCount, modifier=log1p) + boost_mode=sum}，
 * 即 {@code score = _score + log10(1 + likeCount)}——
 * 同等相关性下，点赞越多排名越靠前，且用 log1p 压缩量级避免大 V 笔记的高赞数一家独大；
 * 用「加法」而不是 PostDocument 旧注释里写的「乘法」融合，是因为乘法在 likeCount=0
 * （绝大多数笔记的常态）时会把相关性直接乘成 0，详见 {@link #boostByPopularity}。
 * <p><b>关键设计判断（容易被问到的点）</b>：function_score 不适合用来做"标题 vs 正文"这种字段级权重——
 * 它工作在"已经算出 _score 之后"的层面，没法深入到 multi_match 内部去调节单个字段的匹配权重；
 * 反过来 multi_match 的字段 boost 也没法引入 likeCount 这种文档级数值信号。
 * <b>"哪个字段更重要"用 multi_match boost，"业务信号如何影响排序"用 function_score——
 * 两者解决的是搜索排序的两个不同维度，不是二选一的替代关系。</b>
 * <p>另外只有「关键词搜索」时才叠加 function_score——按门店/按用户浏览（无 keyword）时
 * 没有文本相关性可言，_score 恒为常数，叠加 function_score 纯属浪费一次额外算分开销。
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

    /**
     * 关键词搜索的多字段权重（multi_match boost 语法 {@code field^权重}）。
     * title > shopName > content，权重比例参考 ES 官方文档「字段加权」示例的常见取值，
     * content 用默认权重 1（省略 {@code ^1} 后缀，写法上更接近"基准字段"的语义）。
     */
    private static final String KEYWORD_FIELD_TITLE = "title^3";
    private static final String KEYWORD_FIELD_SHOP_NAME = "shopName^2";
    private static final String KEYWORD_FIELD_CONTENT = "content";

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
        // Step 1: 构建 filter 子句 —— filter 上下文不参与算分，且 ES 自动走 filter cache，
        // 精确匹配类条件（status/shopId/userId）都应该放 filter 而不是 must
        BoolQuery.Builder bool = new BoolQuery.Builder()
                .filter(f -> f.term(t -> t.field("status").value("PUBLISHED")));
        if (StringUtils.hasText(req.getShopId())) {
            bool.filter(f -> f.term(t -> t.field("shopId").value(req.getShopId())));
        }
        if (StringUtils.hasText(req.getUserId())) {
            bool.filter(f -> f.term(t -> t.field("userId").value(req.getUserId())));
        }

        // Step 2: 关键词全文搜索 —— 单条 multi_match 子句 + 字段级 boost，
        // 一次性表达 title^3 > shopName^2 > content 的差异化权重（CriteriaQuery 做不到，详见类注释）
        boolean hasKeyword = StringUtils.hasText(req.getKeyword());
        if (hasKeyword) {
            bool.must(m -> m.multiMatch(mm -> mm
                    .query(req.getKeyword())
                    .fields(KEYWORD_FIELD_TITLE, KEYWORD_FIELD_SHOP_NAME, KEYWORD_FIELD_CONTENT)
                    .type(TextQueryType.BestFields)));
        }

        // Step 3: 只有「关键词搜索」才需要叠加热度加权 —— 纯过滤浏览没有文本相关性，_score 恒为常量，
        // 套 function_score 只会多一次无意义的算分开销（详见类注释「function_score 的正确用法」）
        Query esQuery = hasKeyword
                ? boostByPopularity(bool.build()._toQuery())
                : bool.build()._toQuery();

        // Step 4: 构建分页和排序
        Sort sort = buildSort(req.getSortBy());
        NativeQuery query = NativeQuery.builder()
                .withQuery(esQuery)
                .withPageable(PageRequest.of(req.getPageNumber() - 1, req.getPageSize(), sort))
                .build();

        // Step 5: 执行搜索
        SearchHits<PostDocument> hits = elasticsearchOperations.search(query, PostDocument.class);

        // Step 6: 转换结果
        List<PostSearchVO> items = new ArrayList<>();
        for (SearchHit<PostDocument> hit : hits.getSearchHits()) {
            items.add(toSearchVO(hit));
        }

        return PageResult.of(hits.getTotalHits(), req.getPageNumber(), req.getPageSize(), items);
    }

    // ===== 私有工具方法 =====

    /**
     * 用 function_score 把「点赞数」融合进相关性算分。
     *
     * <h3>没有照抄 PostDocument 注释里 "score = 文本相关性得分 × log(1 + likeCount)" 的原因</h3>
     * <p>那个公式有一个容易被忽视的陷阱：{@code likeCount = 0} 时 {@code log(1 + 0) = 0}，
     * 如果用「乘法」（{@code boost_mode = MULTIPLY}）组合，最终得分会被直接乘成 0——
     * 而"零赞"恰恰是绝大多数笔记的常态（新发布、小众内容），相当于让这些笔记的文本相关性
     * 完全作废、永远沉底，这显然不是产品想要的"轻度热度加权"。
     * <p>本实现改用 {@code boost_mode = SUM}（加法融合）：
     * <pre>最终得分 = 文本相关性得分 _score + log10(1 + likeCount)</pre>
     * <ul>
     *   <li>{@code field_value_factor(likeCount, modifier=log1p)}：把数值字段转成评分因子，
     *       {@code log1p} 即 {@code log10(1 + value)}——取对数压缩量级
     *       （1000 赞和 10000 赞不应该有 10 倍的排序差距），{@code +1} 避免 {@code log(0)} 无定义</li>
     *   <li>{@code missing(0.0)}：likeCount 字段缺失时的兜底值，避免算分异常</li>
     *   <li>「加法」保证 likeCount=0 的笔记仍然 100% 按文本相关性正常参与排序——
     *       热度只是「在相关性基本盘之上的加分项」，不会反过来吞掉相关性，
     *       这才是 PostDocument 注释里"点赞数高的排前面"想表达的真实产品意图
     *       （已同步修正该处注释，见 PostDocument 类）</li>
     * </ul>
     *
     * @param relevanceQuery 已经构建好的相关性查询（filter + multi_match）
     * @return 包裹了热度加权的最终查询
     */
    private Query boostByPopularity(Query relevanceQuery) {
        FunctionScoreQuery functionScore = FunctionScoreQuery.of(fs -> fs
                .query(relevanceQuery)
                .functions(FunctionScore.of(f -> f
                        .fieldValueFactor(fvf -> fvf
                                .field("likeCount")
                                .modifier(FieldValueFactorModifier.Log1p)
                                .missing(0.0))))
                .boostMode(FunctionBoostMode.Sum));
        return functionScore._toQuery();
    }

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
