package com.personalprojections.locallife.server.module.follow.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.FollowRelation;
import com.personalprojections.locallife.server.domain.entity.User;
import com.personalprojections.locallife.server.domain.mapper.FollowRelationMapper;
import com.personalprojections.locallife.server.domain.mapper.UserMapper;
import com.personalprojections.locallife.server.module.follow.dto.FollowUserVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

/**
 * 用户关注关系 Service，负责关注、取关、关注列表查询和共同关注计算。
 *
 * <h2>Redis 数据结构设计</h2>
 * <pre>
 *   关注集合   ZSet（有序集合）  Key: follow:set:{userId}
 *                               Member: followedUserId（字符串）
 *                               Score: 关注时间戳（毫秒）
 *                               用途：
 *                                 - ZADD：关注时加入
 *                                 - ZREM：取关时移除
 *                                 - ZSCORE：判断是否关注（不为 null 即已关注）
 *                                 - ZINTERSTORE：计算共同关注（两个 ZSet 取交集）
 *                                 - ZREVRANGE：按关注时间倒序展示关注列表
 * </pre>
 *
 * <h2>为什么用 ZSet 而不是 Set</h2>
 * <p>普通 Set 只能判断成员是否存在，ZSet 多了 Score（时间戳）：
 * <ol>
 *   <li>关注列表可以按「关注时间倒序」排列（ZREVRANGE），用户体验更好</li>
 *   <li>共同关注用 ZINTERSTORE 取两个 ZSet 的交集，结果还是 ZSet，可以直接排序</li>
 *   <li>ZSet 的 ZSCORE 判断成员存在性同样是 O(log N)，比 Set 的 SISMEMBER O(1) 略慢，
 *       但在关注人数规模（通常 < 10000）下可以接受</li>
 * </ol>
 *
 * <h2>共同关注计算原理</h2>
 * <p>「我和用户 B 的共同关注」= 我关注的集合 ∩ B 关注的集合：
 * <pre>
 *   ZINTERSTORE temp_key 2 follow:set:{myId} follow:set:{targetId}
 *   ZRANGE temp_key 0 -1  → 交集成员（共同关注的用户 ID 列表）
 *   DEL temp_key          → 删除临时 Key
 * </pre>
 *
 * <h2>冷启动处理（Redis 中没有该用户关注集合）</h2>
 * <p>用户首次使用或 Redis 重启后，关注集合 Key 不存在。
 * 此时从数据库加载关注列表，写入 Redis ZSet，再执行操作。
 * 这是「缓存重建」策略（Cache Rebuild on Miss）。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class FollowService {

    private final FollowRelationMapper followRelationMapper;
    private final UserMapper userMapper;
    private final StringRedisTemplate stringRedisTemplate;

    /** 关注集合 ZSet Key，参数：userId */
    private static final String FOLLOW_SET_KEY = "follow:set:%d";

    // =========================================================
    // 关注 / 取关
    // =========================================================

    /**
     * 关注用户（需登录）。
     *
     * <p>操作步骤：
     * <ol>
     *   <li>校验目标用户存在</li>
     *   <li>不能关注自己</li>
     *   <li>幂等校验：已关注则直接返回（不报错）</li>
     *   <li>数据库写入 follow_relation 记录</li>
     *   <li>Redis ZSet ZADD（Score = 当前时间戳）</li>
     * </ol>
     *
     * @param targetUserId 被关注的用户 ID
     */
    public void follow(Long targetUserId) {
        Long myUserId = UserContext.getUserId();

        // 1. 不能关注自己
        if (myUserId.equals(targetUserId)) {
            throw new BizException(ErrorCode.FOLLOW_SELF_NOT_ALLOWED);
        }

        // 2. 校验目标用户存在
        User targetUser = userMapper.selectById(targetUserId);
        if (targetUser == null) {
            throw new BizException(ErrorCode.USER_NOT_FOUND);
        }

        // 3. 幂等校验：查 Redis ZSet（ZSCORE 不为 null 表示已关注）
        String myFollowKey = String.format(FOLLOW_SET_KEY, myUserId);
        Double score = stringRedisTemplate.opsForZSet()
                .score(myFollowKey, String.valueOf(targetUserId));
        if (score != null) {
            // 已关注，幂等直接返回
            log.debug("已关注该用户，幂等返回，myUserId: {}, targetUserId: {}", myUserId, targetUserId);
            return;
        }

        // 4. 数据库写入
        FollowRelation relation = FollowRelation.builder()
                .followerUserId(myUserId)
                .followedUserId(targetUserId)
                .build();
        followRelationMapper.insert(relation);

        // 5. Redis ZSet ZADD（Score = 当前时间戳，用于按关注时间排序）
        stringRedisTemplate.opsForZSet().add(
                myFollowKey,
                String.valueOf(targetUserId),
                System.currentTimeMillis()
        );

        log.info("关注成功，myUserId: {}, targetUserId: {}", myUserId, targetUserId);
    }

    /**
     * 取消关注（需登录）。
     *
     * <p>操作步骤：
     * <ol>
     *   <li>幂等校验：未关注则直接返回</li>
     *   <li>数据库物理删除 follow_relation 记录</li>
     *   <li>Redis ZSet ZREM</li>
     * </ol>
     *
     * @param targetUserId 被取关的用户 ID
     */
    public void unfollow(Long targetUserId) {
        Long myUserId = UserContext.getUserId();

        // 1. 幂等校验（ZSCORE 为 null 表示未关注）
        String myFollowKey = String.format(FOLLOW_SET_KEY, myUserId);
        Double score = stringRedisTemplate.opsForZSet()
                .score(myFollowKey, String.valueOf(targetUserId));
        if (score == null) {
            log.debug("未关注该用户，幂等返回，myUserId: {}, targetUserId: {}", myUserId, targetUserId);
            return;
        }

        // 2. 数据库物理删除（follow_relation 不使用逻辑删除）
        followRelationMapper.delete(
                new LambdaQueryWrapper<FollowRelation>()
                        .eq(FollowRelation::getFollowerUserId, myUserId)
                        .eq(FollowRelation::getFollowedUserId, targetUserId)
        );

        // 3. Redis ZSet ZREM
        stringRedisTemplate.opsForZSet().remove(myFollowKey, String.valueOf(targetUserId));

        log.info("取关成功，myUserId: {}, targetUserId: {}", myUserId, targetUserId);
    }

    // =========================================================
    // 关注状态查询
    // =========================================================

    /**
     * 判断当前用户是否关注了指定用户。
     *
     * @param targetUserId 目标用户 ID
     * @return true 已关注 / false 未关注
     */
    public boolean isFollowing(Long targetUserId) {
        Long myUserId = UserContext.getUserId();
        String myFollowKey = String.format(FOLLOW_SET_KEY, myUserId);
        Double score = stringRedisTemplate.opsForZSet()
                .score(myFollowKey, String.valueOf(targetUserId));
        return score != null;
    }

    // =========================================================
    // 共同关注
    // =========================================================

    /**
     * 查询「我和目标用户」的共同关注列表。
     *
     * <p>实现方案：Redis ZINTERSTORE 取两个 ZSet 的交集。
     * 步骤：
     * <ol>
     *   <li>确保两个用户的关注 ZSet 都存在（冷启动时从 DB 加载）</li>
     *   <li>ZINTERSTORE 写入临时 Key</li>
     *   <li>ZRANGE 读取交集成员（共同关注的用户 ID）</li>
     *   <li>DEL 临时 Key</li>
     *   <li>批量查 user 表，返回 VO</li>
     * </ol>
     *
     * <p>面试追问：「ZINTERSTORE 是 O(N*K*log(N*K)) 的复杂度，N 是关注人数，
     * 如果两个用户各关注 10000 人，性能怎么样？」
     * 答：普通用户关注人数有上限（如 2000），10000 是极端情况。
     * 极端情况下可以先从两个 Set 取较小的那个，再逐个 ZSCORE 查另一个，
     * 时间复杂度降到 O(min(M,N) * log(max(M,N)))。当前阶段先用 ZINTERSTORE。
     *
     * @param targetUserId 目标用户 ID
     * @return 共同关注的用户 VO 列表
     */
    public List<FollowUserVO> getCommonFollows(Long targetUserId) {
        Long myUserId = UserContext.getUserId();

        String myFollowKey = String.format(FOLLOW_SET_KEY, myUserId);
        String targetFollowKey = String.format(FOLLOW_SET_KEY, targetUserId);
        // 临时存储交集结果的 Key（查完即删）
        String tempKey = String.format("follow:common:%d:%d", myUserId, targetUserId);

        // 确保两个用户的关注 ZSet 已加载到 Redis（冷启动处理）
        ensureFollowSetLoaded(myUserId, myFollowKey);
        ensureFollowSetLoaded(targetUserId, targetFollowKey);

        // 取交集（写入临时 Key）
        stringRedisTemplate.opsForZSet().intersectAndStore(
                myFollowKey, targetFollowKey, tempKey
        );

        // 读取交集成员（从分数最低到最高，取全部）
        Set<String> commonIdStrs = stringRedisTemplate.opsForZSet().range(tempKey, 0, -1);

        // 删除临时 Key（避免垃圾数据积累）
        stringRedisTemplate.delete(tempKey);

        if (commonIdStrs == null || commonIdStrs.isEmpty()) {
            return Collections.emptyList();
        }

        // 将 String 形式的 userId 转回 Long，批量查 user 表
        List<Long> commonUserIds = commonIdStrs.stream()
                .map(Long::parseLong)
                .collect(Collectors.toList());
        List<User> users = userMapper.selectBatchIds(commonUserIds);

        return users.stream()
                .map(this::toFollowUserVO)
                .collect(Collectors.toList());
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    /**
     * 冷启动处理：确保指定用户的关注 ZSet 已在 Redis 中。
     *
     * <p>判断方式：ZSet Key 的成员数 = 0 且数据库有数据时，说明 Redis 是冷数据，需要加载。
     * 注意：如果用户本来就没有关注任何人，ZSet 也为空，无需加载（size = 0 且 DB 也无数据）。
     * 这里简化处理：只要 Key 不存在（zCard 返回 null 或 0）且数据库有数据，就加载。
     *
     * @param userId       用户 ID
     * @param followSetKey Redis ZSet Key
     */
    private void ensureFollowSetLoaded(Long userId, String followSetKey) {
        // 检查 ZSet Key 是否存在（zCard 返回成员数，Key 不存在时返回 0）
        Long size = stringRedisTemplate.opsForZSet().zCard(followSetKey);
        if (size != null && size > 0) {
            // Key 已有数据，无需重新加载
            return;
        }

        // 从数据库加载该用户的全量关注列表
        List<FollowRelation> relations = followRelationMapper.selectList(
                new LambdaQueryWrapper<FollowRelation>()
                        .eq(FollowRelation::getFollowerUserId, userId)
        );

        if (relations.isEmpty()) {
            return;
        }

        // 批量写入 Redis ZSet（Score = createdAt 时间戳）
        for (FollowRelation relation : relations) {
            double score = relation.getCreatedAt() != null
                    ? relation.getCreatedAt().toEpochSecond(java.time.ZoneOffset.ofHours(8)) * 1000.0
                    : System.currentTimeMillis();
            stringRedisTemplate.opsForZSet().add(
                    followSetKey,
                    String.valueOf(relation.getFollowedUserId()),
                    score
            );
        }
        log.info("关注集合冷启动加载完成，userId: {}, count: {}", userId, relations.size());
    }

    /**
     * User 实体转 FollowUserVO。
     */
    private FollowUserVO toFollowUserVO(User user) {
        return FollowUserVO.builder()
                .userId(String.valueOf(user.getId()))
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .build();
    }
}
