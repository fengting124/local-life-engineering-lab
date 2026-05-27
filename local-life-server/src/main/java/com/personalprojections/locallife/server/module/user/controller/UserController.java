package com.personalprojections.locallife.server.module.user.controller;

import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.user.dto.UserProfileVO;
import com.personalprojections.locallife.server.module.user.service.UserService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 用户 Controller，处理用户信息查询相关请求。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   GET /api/v1/users/me         查询当前登录用户自己的信息（需要登录）
 *   GET /api/v1/users/{userId}   查询指定用户的主页信息（需要登录）
 * </pre>
 *
 * <h2>鉴权说明</h2>
 * <p>/api/v1/users/** 不在白名单内，所有请求都必须携带有效 Token。
 * AuthInterceptor 会验证 Token 并将用户信息写入 UserContext，
 * UserService 通过 UserContext.getUserId() 获取当前用户 ID。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/users")
@RequiredArgsConstructor
public class UserController {

    private final UserService userService;

    /**
     * 查询当前登录用户自己的信息。
     *
     * <p>返回完整信息，包含脱敏手机号（138****8000）。
     * 当前用户 ID 从 {@link com.personalprojections.locallife.server.common.context.UserContext}
     * 获取，不需要前端传 userId，防止越权查询他人信息。
     *
     * <p>请求示例：
     * <pre>
     *   GET /api/v1/users/me
     *   Authorization: Bearer a1b2c3d4...
     * </pre>
     *
     * <p>响应示例：
     * <pre>
     *   {
     *     "code": "OK",
     *     "data": {
     *       "userId": "101234567890123456",
     *       "nickname": "用户_456789",
     *       "avatar": "",
     *       "bio": "",
     *       "mobile": "138****8000"
     *     }
     *   }
     * </pre>
     *
     * @return 当前用户主页 VO（含脱敏手机号）
     */
    @GetMapping("/me")
    public Result<UserProfileVO> getMyProfile() {
        return Result.ok(userService.getMyProfile());
    }

    /**
     * 查询指定用户的主页信息（查他人）。
     *
     * <p>不返回手机号（他人隐私），只返回公开信息（昵称、头像、简介）。
     *
     * <p>请求示例：
     * <pre>
     *   GET /api/v1/users/101234567890123456
     *   Authorization: Bearer a1b2c3d4...
     * </pre>
     *
     * @param userId 目标用户 ID（路径变量，由 @PathVariable 注入）
     * @return 目标用户主页 VO（不含手机号）
     */
    @GetMapping("/{userId}")
    public Result<UserProfileVO> getUserProfile(@PathVariable Long userId) {
        return Result.ok(userService.getUserProfile(userId));
    }
}
