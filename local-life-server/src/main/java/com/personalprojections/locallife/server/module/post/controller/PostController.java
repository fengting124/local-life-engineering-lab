package com.personalprojections.locallife.server.module.post.controller;

import com.personalprojections.locallife.server.common.ratelimit.RateLimit;
import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.post.dto.CommentVO;
import com.personalprojections.locallife.server.module.post.dto.CreateCommentRequest;
import com.personalprojections.locallife.server.module.post.dto.CreatePostRequest;
import com.personalprojections.locallife.server.module.post.dto.PostVO;
import com.personalprojections.locallife.server.module.post.service.PostService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 探店笔记模块 Controller。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   POST   /api/v1/posts                          发布笔记（需登录）
 *   GET    /api/v1/posts/{postId}                 笔记详情（公开）
 *   DELETE /api/v1/posts/{postId}                 删除笔记（需登录，只能删自己的）
 *   POST   /api/v1/posts/{postId}/likes           点赞笔记（需登录）
 *   DELETE /api/v1/posts/{postId}/likes           取消点赞（需登录）
 *   GET    /api/v1/shops/{shopId}/posts           查门店下的笔记列表（公开，在 ShopController 中）
 *   POST   /api/v1/posts/{postId}/comments        发表评论（需登录）
 *   GET    /api/v1/posts/{postId}/comments        评论列表（公开）
 *   DELETE /api/v1/posts/{postId}/comments/{id}  删除评论（需登录，只能删自己的）
 * </pre>
 *
 * <h2>白名单配置</h2>
 * <p>公开接口（笔记详情、评论列表）已在 {@code WebMvcConfig} 中排除鉴权。
 * 写操作接口（发布、点赞、评论）需要登录，鉴权由 AuthInterceptor 保证。
 *
 * <h2>点赞接口用 POST/DELETE 而不是 PUT 的原因</h2>
 * <p>点赞 = 「创建一个点赞关系资源」→ POST；取消点赞 = 「删除该资源」→ DELETE。
 * 比 PUT /posts/{id}?like=true 语义更清晰，符合 RESTful 资源设计。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class PostController {

    private final PostService postService;

    // =========================================================
    // 笔记 CRUD
    // =========================================================

    /**
     * 发布探店笔记（需登录）。
     *
     * <p>前置校验（Service 层执行）：
     * <ol>
     *   <li>1 分钟发布频率限制（Redis SETNX）</li>
     *   <li>关联的门店必须存在且为 ONLINE 状态</li>
     * </ol>
     *
     * <p>请求体示例：
     * <pre>{@code
     * POST /api/v1/posts
     * Authorization: Bearer {token}
     *
     * {
     *   "shopId": 1234567890,
     *   "title": "宝藏火锅店发现！",
     *   "content": "这家店的毛肚超级嫩，蘸料也很丰富...",
     *   "images": [
     *     "https://cdn.example.com/img1.jpg",
     *     "https://cdn.example.com/img2.jpg"
     *   ]
     * }
     * }</pre>
     *
     * @param request 发布请求体
     * @return 发布后的笔记 VO
     */
    // 同一用户 60 秒内最多发 3 篇（防刷内容）
    @RateLimit(key = "post:publish", limit = 3, window = 60, keyType = RateLimit.KeyType.USER)
    @PostMapping("/api/v1/posts")
    public Result<PostVO> publishPost(@Valid @RequestBody CreatePostRequest request) {
        PostVO vo = postService.publishPost(request);
        return Result.ok(vo);
    }

    /**
     * 查询笔记详情（公开，无需登录）。
     *
     * <p>已登录用户会返回 liked 字段（是否已点赞）；未登录用户 liked = false。
     * 点赞数实时从 Redis 获取，Redis 不可用时降级到 DB 快照值。
     *
     * @param postId 笔记 ID
     * @return 笔记详情 VO
     */
    @GetMapping("/api/v1/posts/{postId}")
    public Result<PostVO> getPostDetail(@PathVariable Long postId) {
        PostVO vo = postService.getPostDetail(postId);
        return Result.ok(vo);
    }

    /**
     * 删除笔记（需登录，只能删自己的笔记）。
     *
     * <p>逻辑删除：数据库中 deleted = 1，C 端不再可见，数据保留用于分析。
     * 同时清理 Redis 点赞相关 Key。
     *
     * <p>错误场景：
     * <ul>
     *   <li>笔记不存在或不属于当前用户 → POST_FORBIDDEN (403)</li>
     * </ul>
     *
     * @param postId 笔记 ID
     * @return 空 data
     */
    @DeleteMapping("/api/v1/posts/{postId}")
    public Result<Void> deletePost(@PathVariable Long postId) {
        postService.deletePost(postId);
        return Result.ok();
    }

    // =========================================================
    // 点赞 / 取消点赞
    // =========================================================

    /**
     * 点赞笔记（需登录）。
     *
     * <p>幂等接口：重复点赞直接返回成功，不报错（前端防重，后端兜底）。
     * 点赞数实时写入 Redis（INCR），不同步写 DB（定期任务做快照同步）。
     *
     * @param postId 笔记 ID
     * @return 空 data
     */
    @PostMapping("/api/v1/posts/{postId}/likes")
    public Result<Void> likePost(@PathVariable Long postId) {
        postService.likePost(postId);
        return Result.ok();
    }

    /**
     * 取消点赞（需登录）。
     *
     * <p>幂等接口：未点赞时调用直接返回成功，不报错。
     *
     * @param postId 笔记 ID
     * @return 空 data
     */
    @DeleteMapping("/api/v1/posts/{postId}/likes")
    public Result<Void> unlikePost(@PathVariable Long postId) {
        postService.unlikePost(postId);
        return Result.ok();
    }

    // =========================================================
    // 门店下的笔记列表（公开）
    // =========================================================

    /**
     * 查询某家门店下的探店笔记列表（公开）。
     *
     * <p>路径放在 /shops/{shopId}/posts 表达「门店的笔记子资源」，
     * 语义比 /posts?shopId={shopId} 更清晰。
     *
     * @param shopId 门店 ID
     * @return 该门店下 PUBLISHED 状态的笔记列表（按时间倒序）
     */
    @GetMapping("/api/v1/shops/{shopId}/posts")
    public Result<List<PostVO>> listPostsByShop(@PathVariable Long shopId) {
        List<PostVO> posts = postService.listPostsByShop(shopId);
        return Result.ok(posts);
    }

    // =========================================================
    // 评论
    // =========================================================

    /**
     * 发表评论（需登录）。
     *
     * <p>当前只支持一级评论（parent_id = 0），嵌套回复后续版本开放。
     * 发表评论后同步更新笔记的 comment_count（数据库直接 +1）。
     *
     * @param postId  目标笔记 ID
     * @param request 评论内容
     * @return 评论 VO（含评论者昵称和头像）
     */
    // 同一用户 10 秒内最多评论 5 次（防评论刷屏）
    @RateLimit(key = "post:comment", limit = 5, window = 10, keyType = RateLimit.KeyType.USER)
    @PostMapping("/api/v1/posts/{postId}/comments")
    public Result<CommentVO> addComment(
            @PathVariable Long postId,
            @Valid @RequestBody CreateCommentRequest request) {
        CommentVO vo = postService.addComment(postId, request);
        return Result.ok(vo);
    }

    /**
     * 查询笔记评论列表（公开，无需登录）。
     *
     * <p>只返回一级评论，按创建时间升序（最早的评论排前面）。
     * 当前全量返回，后续加游标分页（lastId + pageSize）。
     *
     * @param postId 笔记 ID
     * @return 一级评论列表
     */
    @GetMapping("/api/v1/posts/{postId}/comments")
    public Result<List<CommentVO>> listComments(@PathVariable Long postId) {
        List<CommentVO> comments = postService.listComments(postId);
        return Result.ok(comments);
    }

    /**
     * 删除评论（需登录，只能删自己的评论）。
     *
     * <p>逻辑删除，同时更新笔记 comment_count（-1，最小为 0）。
     *
     * <p>错误场景：
     * <ul>
     *   <li>评论不存在、不属于该笔记，或不是当前用户的 → COMMENT_FORBIDDEN (403)</li>
     * </ul>
     *
     * @param postId    笔记 ID
     * @param commentId 评论 ID
     * @return 空 data
     */
    @DeleteMapping("/api/v1/posts/{postId}/comments/{commentId}")
    public Result<Void> deleteComment(
            @PathVariable Long postId,
            @PathVariable Long commentId) {
        postService.deleteComment(postId, commentId);
        return Result.ok();
    }
}
