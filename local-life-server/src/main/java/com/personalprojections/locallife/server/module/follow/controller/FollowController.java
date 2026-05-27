package com.personalprojections.locallife.server.module.follow.controller;

import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.follow.dto.FollowUserVO;
import com.personalprojections.locallife.server.module.follow.service.FollowService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 关注关系模块 Controller。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   POST   /api/v1/follows/{userId}            关注用户（需登录）
 *   DELETE /api/v1/follows/{userId}            取消关注（需登录）
 *   GET    /api/v1/follows/common/{userId}     查询共同关注列表（需登录）
 *   GET    /api/v1/follows/status/{userId}     查询是否关注了某人（需登录）
 * </pre>
 *
 * <h2>路径设计说明</h2>
 * <p>{@code /follows/{userId}} 表示「对 userId 这个用户的关注资源」：
 * <ul>
 *   <li>POST = 创建关注关系（关注）</li>
 *   <li>DELETE = 删除关注关系（取关）</li>
 * </ul>
 * <p>所有接口都需要登录（全部不在白名单），鉴权由 AuthInterceptor 统一处理。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/follows")
@RequiredArgsConstructor
public class FollowController {

    private final FollowService followService;

    /**
     * 关注用户（需登录）。
     *
     * <p>幂等接口：已关注的情况下重复调用直接返回成功，不报错。
     *
     * <p>错误场景：
     * <ul>
     *   <li>关注自己 → FOLLOW_SELF_NOT_ALLOWED (400)</li>
     *   <li>目标用户不存在 → USER_NOT_FOUND (400)</li>
     * </ul>
     *
     * @param userId 被关注的用户 ID
     * @return 空 data
     */
    @PostMapping("/{userId}")
    public Result<Void> follow(@PathVariable Long userId) {
        followService.follow(userId);
        return Result.ok();
    }

    /**
     * 取消关注（需登录）。
     *
     * <p>幂等接口：未关注时调用直接返回成功。
     *
     * @param userId 被取关的用户 ID
     * @return 空 data
     */
    @DeleteMapping("/{userId}")
    public Result<Void> unfollow(@PathVariable Long userId) {
        followService.unfollow(userId);
        return Result.ok();
    }

    /**
     * 查询「我和目标用户」的共同关注列表（需登录）。
     *
     * <p>基于 Redis ZSet ZINTERSTORE 计算两个用户的关注集合交集。
     * 结果是双方都关注的用户列表（简要信息：userId、昵称、头像）。
     *
     * <p>典型使用场景：用户主页 → 「你们共同关注了 3 人」入口。
     *
     * @param userId 目标用户 ID
     * @return 共同关注的用户 VO 列表
     */
    @GetMapping("/common/{userId}")
    public Result<List<FollowUserVO>> getCommonFollows(@PathVariable Long userId) {
        List<FollowUserVO> list = followService.getCommonFollows(userId);
        return Result.ok(list);
    }

    /**
     * 查询当前用户是否关注了指定用户（需登录）。
     *
     * <p>用于前端渲染「关注 / 已关注」按钮状态，O(log N) 复杂度（Redis ZSCORE）。
     *
     * @param userId 目标用户 ID
     * @return true 已关注 / false 未关注
     */
    @GetMapping("/status/{userId}")
    public Result<Boolean> isFollowing(@PathVariable Long userId) {
        boolean following = followService.isFollowing(userId);
        return Result.ok(following);
    }
}
