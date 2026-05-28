package com.personalprojections.locallife.server.module.post.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.Comment;
import com.personalprojections.locallife.server.domain.entity.Post;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.entity.User;
import com.personalprojections.locallife.server.domain.mapper.CommentMapper;
import com.personalprojections.locallife.server.domain.mapper.PostMapper;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.domain.mapper.UserMapper;
import com.personalprojections.locallife.server.module.post.dto.CommentVO;
import com.personalprojections.locallife.server.module.post.dto.CreateCommentRequest;
import com.personalprojections.locallife.server.module.post.dto.CreatePostRequest;
import com.personalprojections.locallife.server.module.post.dto.PostVO;
import com.personalprojections.locallife.server.module.search.service.PostSearchService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 探店笔记 Service，负责笔记的发布、查询、点赞和删除，以及笔记评论。
 *
 * <h2>Redis 数据结构设计</h2>
 * <pre>
 *   点赞数（实时）  String   Key: post:like:count:{postId}
 *                            Value: 点赞总数（整数字符串）
 *
 *   点赞用户集合    Set      Key: post:like:users:{postId}
 *                            Members: userId 字符串
 *                            用途：O(1) 判断「我是否点赞」（SISMEMBER）
 *
 *   发布频率限流    String   Key: post:publish:limit:{userId}
 *                            Value: 无关紧要（只看 Key 存不存在）
 *                            TTL: 60 秒（1分钟最多发 1 篇）
 * </pre>
 *
 * <h2>点赞计数方案说明</h2>
 * <p>用 Redis String 存计数、Redis Set 存点赞用户，两个 Key 配合使用：
 * <ol>
 *   <li>点赞：INCR count Key + SADD users Key</li>
 *   <li>取消点赞：DECR count Key + SREM users Key</li>
 *   <li>查是否点赞：SISMEMBER users Key，O(1)</li>
 *   <li>查点赞数：GET count Key，O(1)</li>
 * </ol>
 * <p>为什么不只用 Set（SCARD 取数量）？
 * SCARD 是 O(1)，理论上也可以。但 Set 存用户 ID 随点赞人数增长变大，
 * 高流量场景下大 Key 会占用大量内存。如果只需要计数，String 更轻量。
 * 两个 Key 并存是「计数精度 + 判断效率」的平衡方案，面试时能说清即可。
 *
 * <h2>发布频率限流</h2>
 * <p>用 Redis Key 的存在性控制发布频率：
 * <ol>
 *   <li>发布前 SETNX limit Key，TTL 60 秒</li>
 *   <li>SETNX 返回 false → Key 已存在 → 60 秒内已发过 → 拒绝</li>
 *   <li>SETNX 返回 true → 首次发布 → 放行</li>
 * </ol>
 * 优点：无需计数，简单可靠；缺点：只能限制「60秒 1 篇」，不支持更细粒度（如每小时 5 篇）。
 * 更细的限流后续用 Lua 脚本 + 滑动窗口实现。
 *
 * <h2>N+1 查询问题处理</h2>
 * <p>评论列表需要展示评论者的昵称和头像，但 comment 表只存 userId。
 * 解决方案：先查 comment 列表，再批量查 user（IN 查询），最后 Map 组装。
 * 避免逐条查询（N+1 问题）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class PostService {

    private final PostMapper postMapper;
    private final ShopMapper shopMapper;
    private final UserMapper userMapper;
    private final CommentMapper commentMapper;

    /**
     * ES 双写服务：笔记发布/删除后立刻同步更新 ES 索引。
     * 后续 Canal 方案上线后可移除此依赖。
     */
    private final PostSearchService postSearchService;

    /**
     * 使用 StringRedisTemplate（Key 和 Value 都是 String）操作点赞计数和限流 Key。
     * 之所以不用 RedisTemplate<Object, Object>，是因为：
     * 1. 计数值是纯字符串整数，不需要序列化开销
     * 2. StringRedisTemplate 的 Key 格式和其他系统（监控、运维）一致，可读性好
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Jackson ObjectMapper，用于 images 字段的 JSON 序列化/反序列化。
     * List<String> ↔ JSON 数组字符串。
     * Spring Boot 自动注册了一个 ObjectMapper Bean，直接注入即可。
     */
    private final ObjectMapper objectMapper;

    // =========================================================
    // Redis Key 模板（集中管理，避免 Key 散落在各方法里）
    // =========================================================

    /** 点赞数计数 Key，参数：postId */
    private static final String LIKE_COUNT_KEY = "post:like:count:%d";
    /** 点赞用户集合 Key，参数：postId */
    private static final String LIKE_USERS_KEY = "post:like:users:%d";
    /** 发布频率限流 Key，参数：userId，TTL 60 秒 */
    private static final String PUBLISH_LIMIT_KEY = "post:publish:limit:%d";

    // =========================================================
    // 笔记发布（需登录）
    // =========================================================

    /**
     * 发布探店笔记。
     *
     * <p>发布流程：
     * <ol>
     *   <li>频率限流：1 分钟最多发 1 篇（Redis SETNX）</li>
     *   <li>校验门店：shopId 对应的门店必须存在且为 ONLINE 状态</li>
     *   <li>构建 Post 实体，images 列表序列化为 JSON 字符串</li>
     *   <li>写入数据库</li>
     *   <li>初始化 Redis 点赞计数为 0</li>
     * </ol>
     *
     * @param request 发布请求体（已通过 @Valid 校验）
     * @return 发布后的笔记 VO
     */
    @Transactional(rollbackFor = Exception.class)
    public PostVO publishPost(CreatePostRequest request) {
        Long userId = UserContext.getUserId();

        // 1. 发布频率限流（1分钟内只允许发 1 篇）
        checkPublishRateLimit(userId);

        // 2. 校验门店存在且已上线
        Shop shop = shopMapper.selectById(request.getShopId());
        if (shop == null || !"ONLINE".equals(shop.getStatus())) {
            // 门店不存在或未上线，统一返回 SHOP_NOT_ONLINE
            // 不区分「不存在」和「未上线」，因为 C 端用户不应该知道有未上线门店
            throw new BizException(ErrorCode.SHOP_NOT_ONLINE);
        }

        // 3. 序列化图片列表为 JSON 字符串
        String imagesJson = serializeImages(request.getImages());

        // 4. 查询当前用户信息（笔记 VO 需要冗余作者昵称和头像）
        User user = userMapper.selectById(userId);

        // 5. 构建笔记实体，发布即为 PUBLISHED 状态
        Post post = Post.builder()
                .userId(userId)
                .shopId(request.getShopId())
                .title(request.getTitle() != null ? request.getTitle() : "")
                .content(request.getContent())
                .images(imagesJson)
                .likeCount(0)
                .commentCount(0)
                .status("PUBLISHED")
                .build();

        postMapper.insert(post);

        // 6. 初始化 Redis 点赞计数（新笔记从 0 开始）
        // 不一定要初始化（getOrDefault 也行），但提前初始化可以避免首次读时走数据库
        String likeCountKey = String.format(LIKE_COUNT_KEY, post.getId());
        stringRedisTemplate.opsForValue().set(likeCountKey, "0");

        log.info("笔记发布成功，userId: {}, postId: {}, shopId: {}", userId, post.getId(), request.getShopId());

        // 双写 ES（笔记发布后立即同步，用户搜索时能找到）
        // shopName 冗余存入 PostDocument，避免 ES 查询时关联门店表
        postSearchService.syncPost(post, shop.getShopName());

        return toVO(post, user, shop, false);
    }

    // =========================================================
    // 笔记查询（公开，C 端）
    // =========================================================

    /**
     * 查询笔记详情（公开接口，无需登录）。
     *
     * <p>处理逻辑：
     * <ol>
     *   <li>查数据库获取笔记（逻辑删除自动过滤）</li>
     *   <li>从 Redis 取实时点赞数（Redis 不可用时降级到数据库快照值）</li>
     *   <li>判断当前登录用户是否点赞（未登录时返回 false）</li>
     *   <li>组装 VO（冗余作者信息 + 门店信息）</li>
     * </ol>
     *
     * @param postId 笔记 ID
     * @return 笔记详情 VO
     */
    public PostVO getPostDetail(Long postId) {
        // 1. 查笔记（MyBatis-Plus 自动过滤 deleted = 1）
        Post post = postMapper.selectById(postId);
        if (post == null || !"PUBLISHED".equals(post.getStatus())) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }

        // 2. 批量查作者和门店（避免 N+1）
        User user = userMapper.selectById(post.getUserId());
        Shop shop = shopMapper.selectById(post.getShopId());

        // 3. 从 Redis 获取实时点赞数（降级到 DB 快照）
        int likeCount = getRealTimeLikeCount(post);

        // 4. 判断当前登录用户是否已点赞
        boolean liked = isLikedByCurrentUser(postId);

        // 5. 组装 VO（点赞数用 Redis 实时值覆盖 DB 快照值）
        PostVO vo = toVO(post, user, shop, liked);
        vo.setLikeCount(likeCount);
        return vo;
    }

    /**
     * 查询某家门店下的笔记列表（公开，门店详情页-探店内容 Tab）。
     *
     * <p>按创建时间倒序，最新发布的排前面。
     * 当前阶段全量返回，不分页（门店笔记量小）；后续接入 ES 后用游标分页。
     *
     * @param shopId 门店 ID
     * @return 该门店下 PUBLISHED 状态的笔记列表
     */
    public List<PostVO> listPostsByShop(Long shopId) {
        List<Post> posts = postMapper.selectList(
                new LambdaQueryWrapper<Post>()
                        .eq(Post::getShopId, shopId)
                        .eq(Post::getStatus, "PUBLISHED")
                        .orderByDesc(Post::getCreatedAt)
        );

        if (posts.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查作者信息（避免 N+1）
        // 1. 从 posts 里提取所有 userId，去重
        List<Long> userIds = posts.stream()
                .map(Post::getUserId)
                .distinct()
                .collect(Collectors.toList());
        // 2. IN 查询，一次查所有作者
        List<User> users = userMapper.selectBatchIds(userIds);
        // 3. 转为 Map<userId, User>，方便后续按 userId 查找
        Map<Long, User> userMap = users.stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        // 门店信息是已知的，查一次即可
        Shop shop = shopMapper.selectById(shopId);

        return posts.stream()
                .map(post -> {
                    User user = userMap.get(post.getUserId());
                    boolean liked = isLikedByCurrentUser(post.getId());
                    int likeCount = getRealTimeLikeCount(post);
                    PostVO vo = toVO(post, user, shop, liked);
                    vo.setLikeCount(likeCount);
                    return vo;
                })
                .collect(Collectors.toList());
    }

    // =========================================================
    // 点赞 / 取消点赞
    // =========================================================

    /**
     * 点赞笔记（需登录）。
     *
     * <p>操作步骤（两个 Redis Key 原子性问题）：
     * <ol>
     *   <li>校验笔记存在</li>
     *   <li>检查是否已点赞（SISMEMBER users Key）</li>
     *   <li>已点赞 → 抛业务异常（前端应禁用点赞按钮，但后端依然校验）</li>
     *   <li>未点赞 → SADD users Key + INCR count Key</li>
     * </ol>
     *
     * <p>为什么不用事务保证 SADD + INCR 原子性？
     * 因为即使两步中间崩溃，count 和 set 有 1 的误差，
     * 后台定时任务会做「Redis ↔ DB 一致性修复」，误差可接受。
     * 引入 Lua 脚本可以保证原子性，后续高并发优化时替换。
     *
     * @param postId 笔记 ID
     */
    public void likePost(Long postId) {
        Long userId = UserContext.getUserId();

        // 1. 笔记必须存在且已发布
        requirePublishedPost(postId);

        // 2. 检查是否已点赞
        String usersKey = String.format(LIKE_USERS_KEY, postId);
        Boolean isMember = stringRedisTemplate.opsForSet()
                .isMember(usersKey, String.valueOf(userId));

        if (Boolean.TRUE.equals(isMember)) {
            // 已点赞，幂等处理：直接返回，不报错（前端防重复点击，后端友好兜底）
            log.debug("用户已点赞该笔记，userId: {}, postId: {}", userId, postId);
            return;
        }

        // 3. 执行点赞：SADD 记录用户 + INCR 计数
        stringRedisTemplate.opsForSet().add(usersKey, String.valueOf(userId));
        String countKey = String.format(LIKE_COUNT_KEY, postId);
        stringRedisTemplate.opsForValue().increment(countKey);

        log.info("点赞成功，userId: {}, postId: {}", userId, postId);
    }

    /**
     * 取消点赞（需登录）。
     *
     * <p>与点赞对称：SREM users Key + DECR count Key。
     * 防止计数降到负数：只有在 isMember = true 时才 DECR。
     *
     * @param postId 笔记 ID
     */
    public void unlikePost(Long postId) {
        Long userId = UserContext.getUserId();

        requirePublishedPost(postId);

        String usersKey = String.format(LIKE_USERS_KEY, postId);
        Boolean isMember = stringRedisTemplate.opsForSet()
                .isMember(usersKey, String.valueOf(userId));

        if (!Boolean.TRUE.equals(isMember)) {
            // 未点赞，幂等处理：直接返回
            log.debug("用户未点赞该笔记，无需取消，userId: {}, postId: {}", userId, postId);
            return;
        }

        // 执行取消点赞
        stringRedisTemplate.opsForSet().remove(usersKey, String.valueOf(userId));
        String countKey = String.format(LIKE_COUNT_KEY, postId);
        stringRedisTemplate.opsForValue().decrement(countKey);

        log.info("取消点赞成功，userId: {}, postId: {}", userId, postId);
    }

    // =========================================================
    // 笔记删除（需登录，只能删自己的）
    // =========================================================

    /**
     * 删除笔记（逻辑删除，需登录）。
     *
     * <p>只能删除自己的笔记，不能删除别人的（防越权）。
     * 逻辑删除后 C 端不可见，但数据仍在数据库（利于数据分析）。
     * 同时清理 Redis 点赞相关 Key（避免数据残留）。
     *
     * @param postId 目标笔记 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deletePost(Long postId) {
        Long userId = UserContext.getUserId();

        // 1. 校验笔记属于当前用户
        Post post = postMapper.selectById(postId);
        if (post == null || !userId.equals(post.getUserId())) {
            // 不区分「不存在」和「无权限」，防枚举攻击
            throw new BizException(ErrorCode.POST_FORBIDDEN);
        }

        // 2. 逻辑删除（MyBatis-Plus deleteById = UPDATE SET deleted = 1）
        postMapper.deleteById(postId);

        // 3. 清理 Redis 点赞 Key（异步也可以，当前同步删除保持简单）
        stringRedisTemplate.delete(String.format(LIKE_COUNT_KEY, postId));
        stringRedisTemplate.delete(String.format(LIKE_USERS_KEY, postId));

        // 4. 从 ES 索引中删除（MySQL 逻辑删除，ES 物理删除）
        // ES 无逻辑删除概念，且搜索结果中不应出现已删除笔记
        postSearchService.removePost(postId);

        log.info("笔记删除成功，userId: {}, postId: {}", userId, postId);
    }

    // =========================================================
    // 评论相关
    // =========================================================

    /**
     * 发表评论（需登录）。
     *
     * <p>发表评论后同步更新 post.comment_count（+1）。
     * 因为评论数相对低频，不走 Redis，直接更新数据库（对比点赞高并发场景）。
     *
     * @param postId  目标笔记 ID
     * @param request 评论内容
     * @return 评论 VO
     */
    @Transactional(rollbackFor = Exception.class)
    public CommentVO addComment(Long postId, CreateCommentRequest request) {
        Long userId = UserContext.getUserId();

        // 1. 校验笔记存在且已发布（返回值不需要，只做存在性校验）
        requirePublishedPost(postId);

        // 2. 查询评论者信息（VO 需要昵称和头像）
        User user = userMapper.selectById(userId);

        // 3. 创建评论
        Comment comment = Comment.builder()
                .postId(postId)
                .userId(userId)
                .parentId(0L)   // 当前阶段只支持一级评论
                .content(request.getContent())
                .build();
        commentMapper.insert(comment);

        // 4. 更新笔记的评论数快照（+1）
        // 使用 LambdaUpdateWrapper 做条件更新，避免全字段覆盖
        postMapper.update(null,
                new LambdaUpdateWrapper<Post>()
                        .eq(Post::getId, postId)
                        .setSql("comment_count = comment_count + 1")
        );

        log.info("评论发表成功，userId: {}, postId: {}, commentId: {}", userId, postId, comment.getId());
        return toCommentVO(comment, user);
    }

    /**
     * 查询笔记的评论列表（公开，无需登录）。
     *
     * <p>只查一级评论（parent_id = 0），按创建时间升序（最早的评论排前面，符合阅读习惯）。
     * 当前返回全量，不分页（评论量小时可接受）；后续加游标分页。
     *
     * @param postId 笔记 ID
     * @return 一级评论列表
     */
    public List<CommentVO> listComments(Long postId) {
        // 校验笔记存在（防止查一个不存在笔记的评论）
        Post post = postMapper.selectById(postId);
        if (post == null || !"PUBLISHED".equals(post.getStatus())) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }

        // 查一级评论（parent_id = 0，按时间升序）
        List<Comment> comments = commentMapper.selectList(
                new LambdaQueryWrapper<Comment>()
                        .eq(Comment::getPostId, postId)
                        .eq(Comment::getParentId, 0L)
                        .orderByAsc(Comment::getCreatedAt)
        );

        if (comments.isEmpty()) {
            return Collections.emptyList();
        }

        // 批量查评论者信息（IN 查询，避免 N+1）
        List<Long> userIds = comments.stream()
                .map(Comment::getUserId)
                .distinct()
                .collect(Collectors.toList());
        Map<Long, User> userMap = userMapper.selectBatchIds(userIds)
                .stream()
                .collect(Collectors.toMap(User::getId, u -> u));

        return comments.stream()
                .map(comment -> toCommentVO(comment, userMap.get(comment.getUserId())))
                .collect(Collectors.toList());
    }

    /**
     * 删除评论（需登录，只能删自己的）。
     *
     * <p>删除后同步减少 post.comment_count（-1，但不允许小于 0）。
     *
     * @param postId    笔记 ID（用于校验评论归属）
     * @param commentId 目标评论 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void deleteComment(Long postId, Long commentId) {
        Long userId = UserContext.getUserId();

        // 校验评论存在且属于当前用户（防越权）
        Comment comment = commentMapper.selectById(commentId);
        if (comment == null
                || !postId.equals(comment.getPostId())
                || !userId.equals(comment.getUserId())) {
            throw new BizException(ErrorCode.COMMENT_FORBIDDEN);
        }

        // 逻辑删除评论
        commentMapper.deleteById(commentId);

        // 更新评论数（-1，用 GREATEST 防止降到负数）
        postMapper.update(null,
                new LambdaUpdateWrapper<Post>()
                        .eq(Post::getId, postId)
                        .setSql("comment_count = GREATEST(comment_count - 1, 0)")
        );

        log.info("评论删除成功，userId: {}, postId: {}, commentId: {}", userId, postId, commentId);
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    /**
     * 校验发布频率限流：1 分钟内只允许发 1 篇。
     *
     * <p>使用 Redis SETNX（setIfAbsent）实现：
     * Key 不存在 → 设置 Key（TTL 60 秒）→ 放行
     * Key 已存在 → 说明 60 秒内已发过 → 拒绝
     *
     * @param userId 当前用户 ID
     * @throws BizException POST_PUBLISH_TOO_FREQUENT
     */
    private void checkPublishRateLimit(Long userId) {
        String limitKey = String.format(PUBLISH_LIMIT_KEY, userId);
        // setIfAbsent = SETNX：Key 不存在时设置，返回 true；Key 已存在返回 false
        Boolean allowed = stringRedisTemplate.opsForValue()
                .setIfAbsent(limitKey, "1", 60, TimeUnit.SECONDS);
        if (!Boolean.TRUE.equals(allowed)) {
            throw new BizException(ErrorCode.POST_PUBLISH_TOO_FREQUENT);
        }
    }

    /**
     * 校验笔记存在且为 PUBLISHED 状态，返回笔记实体。
     *
     * @param postId 笔记 ID
     * @return 笔记实体
     * @throws BizException POST_NOT_FOUND
     */
    private Post requirePublishedPost(Long postId) {
        Post post = postMapper.selectById(postId);
        if (post == null || !"PUBLISHED".equals(post.getStatus())) {
            throw new BizException(ErrorCode.POST_NOT_FOUND);
        }
        return post;
    }

    /**
     * 判断当前登录用户是否已点赞指定笔记。
     *
     * <p>未登录时（UserContext.getUserId() 返回 null），直接返回 false，
     * 不抛异常（点赞状态查询是公开接口的一部分）。
     *
     * @param postId 笔记 ID
     * @return true 已点赞 / false 未点赞或未登录
     */
    private boolean isLikedByCurrentUser(Long postId) {
        Long userId = UserContext.getUserId();
        if (userId == null) {
            return false;
        }
        String usersKey = String.format(LIKE_USERS_KEY, postId);
        return Boolean.TRUE.equals(
                stringRedisTemplate.opsForSet().isMember(usersKey, String.valueOf(userId))
        );
    }

    /**
     * 获取笔记实时点赞数。
     *
     * <p>优先从 Redis 取，Redis 不可用或 Key 不存在时降级到数据库快照值。
     * 降级策略：Redis 故障不应导致笔记不可用，只是点赞数显示为快照（可能有延迟）。
     *
     * @param post 笔记实体（包含 DB 快照值 likeCount）
     * @return 实时点赞数
     */
    private int getRealTimeLikeCount(Post post) {
        try {
            String countKey = String.format(LIKE_COUNT_KEY, post.getId());
            String val = stringRedisTemplate.opsForValue().get(countKey);
            if (val != null) {
                return Integer.parseInt(val);
            }
        } catch (Exception e) {
            // Redis 不可用，降级到 DB 快照值，记录 WARN 日志但不中断请求
            log.warn("读取 Redis 点赞数失败，降级到 DB 快照，postId: {}", post.getId(), e);
        }
        // Redis 无数据 → 返回 DB 快照值（可能是 0 或上次同步的值）
        return post.getLikeCount() != null ? post.getLikeCount() : 0;
    }

    /**
     * 序列化图片列表为 JSON 字符串。
     *
     * <p>images 为 null 或空列表时返回 "[]"，保证字段不存 null。
     *
     * @param images 图片 URL 列表
     * @return JSON 数组字符串
     */
    private String serializeImages(List<String> images) {
        if (images == null || images.isEmpty()) {
            return "[]";
        }
        try {
            return objectMapper.writeValueAsString(images);
        } catch (Exception e) {
            // 序列化不应失败（List<String> 是最简单的类型），打 ERROR 日志后降级返回空数组
            log.error("图片列表序列化失败，降级为空数组", e);
            return "[]";
        }
    }

    /**
     * 反序列化 JSON 字符串为图片列表。
     *
     * @param imagesJson 数据库中存储的 JSON 字符串
     * @return 图片 URL 列表
     */
    private List<String> deserializeImages(String imagesJson) {
        if (imagesJson == null || imagesJson.isBlank() || "[]".equals(imagesJson)) {
            return Collections.emptyList();
        }
        try {
            return objectMapper.readValue(imagesJson, new TypeReference<List<String>>() {});
        } catch (Exception e) {
            log.warn("图片列表反序列化失败，返回空列表，raw: {}", imagesJson, e);
            return Collections.emptyList();
        }
    }

    /**
     * Post 实体 + User + Shop + liked 状态 → PostVO。
     *
     * <p>冗余字段（昵称、头像、门店名）在此组装，避免前端多次调用接口。
     * user 或 shop 为 null 时（理论上不应发生，但防御性处理）用空字符串代替。
     *
     * @param post  笔记实体
     * @param user  作者用户实体（可为 null，降级显示）
     * @param shop  关联门店实体（可为 null）
     * @param liked 当前用户是否已点赞
     * @return PostVO
     */
    private PostVO toVO(Post post, User user, Shop shop, boolean liked) {
        return PostVO.builder()
                .postId(String.valueOf(post.getId()))
                .userId(String.valueOf(post.getUserId()))
                .nickname(user != null ? user.getNickname() : "")
                .avatar(user != null ? user.getAvatar() : "")
                .shopId(String.valueOf(post.getShopId()))
                .shopName(shop != null ? shop.getShopName() : "")
                .title(post.getTitle())
                .content(post.getContent())
                .images(deserializeImages(post.getImages()))
                .likeCount(post.getLikeCount() != null ? post.getLikeCount() : 0)
                .commentCount(post.getCommentCount() != null ? post.getCommentCount() : 0)
                .liked(liked)
                .status(post.getStatus())
                // LocalDateTime → OffsetDateTime（加上东八区时区信息）
                .createdAt(post.getCreatedAt() != null
                        ? post.getCreatedAt().atOffset(ZoneOffset.ofHours(8))
                        : null)
                .build();
    }

    /**
     * Comment 实体 + User → CommentVO。
     *
     * @param comment 评论实体
     * @param user    评论者用户实体
     * @return CommentVO
     */
    private CommentVO toCommentVO(Comment comment, User user) {
        return CommentVO.builder()
                .commentId(String.valueOf(comment.getId()))
                .userId(String.valueOf(comment.getUserId()))
                .nickname(user != null ? user.getNickname() : "")
                .avatar(user != null ? user.getAvatar() : "")
                .content(comment.getContent())
                .createdAt(comment.getCreatedAt() != null
                        ? comment.getCreatedAt().atOffset(ZoneOffset.ofHours(8))
                        : null)
                .build();
    }
}
