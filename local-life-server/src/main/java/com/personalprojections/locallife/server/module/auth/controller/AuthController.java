package com.personalprojections.locallife.server.module.auth.controller;

import com.personalprojections.locallife.server.common.ratelimit.RateLimit;
import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.auth.dto.LoginRequest;
import com.personalprojections.locallife.server.module.auth.dto.LoginResponse;
import com.personalprojections.locallife.server.module.auth.dto.SendCodeRequest;
import com.personalprojections.locallife.server.module.auth.service.AuthService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.util.StringUtils;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 鉴权 Controller，处理登录、验证码、退出登录相关请求。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   POST /api/v1/auth/code    发送验证码（无需登录）
 *   POST /api/v1/auth/login   验证码登录（无需登录）
 *   POST /api/v1/auth/logout  退出登录（需要登录 Token）
 * </pre>
 *
 * <h2>Controller 的职责边界</h2>
 * <p>Controller 只做三件事：
 * <ol>
 *   <li>接收 HTTP 请求，用 @Valid 触发参数校验</li>
 *   <li>调用 Service 执行业务逻辑</li>
 *   <li>将 Service 返回值包装成 {@link Result} 返回给前端</li>
 * </ol>
 * 所有业务判断（验证码比对、用户注册、Token 生成）都在 Service 中，Controller 不写业务逻辑。
 *
 * <h2>白名单说明</h2>
 * <p>/api/v1/auth/** 路径已在 WebMvcConfig 中配置为白名单，
 * 不经过 AuthInterceptor，无需携带 Token 即可访问。
 * /auth/logout 需要 Token，但通过请求体或 Header 手动取，
 * 因为它也在白名单内（设计取舍：/logout 要支持 Token 已失效的情况也能调用，不报 401）。
 */
@Slf4j
@RestController
@RequestMapping("/api/v1/auth")
@RequiredArgsConstructor
public class AuthController {

    private final AuthService authService;

    /**
     * 发送短信验证码。
     *
     * <p>请求示例：
     * <pre>
     *   POST /api/v1/auth/code
     *   Content-Type: application/json
     *   {"mobile": "13800138000"}
     * </pre>
     *
     * <p>响应示例（成功）：
     * <pre>
     *   {"code":"OK","message":"操作成功","timestamp":"..."}
     * </pre>
     *
     * @param request 请求体，包含 mobile（@Valid 触发校验）
     * @return 无数据的成功响应（data 字段不返回，避免把验证码暴露在响应中）
     */
    // 同一用户/IP 60 秒内最多发 1 次验证码（防短信轰炸）
    @RateLimit(key = "auth:code", limit = 1, window = 60, keyType = RateLimit.KeyType.IP)
    @PostMapping("/code")
    public Result<Void> sendCode(@Valid @RequestBody SendCodeRequest request) {
        authService.sendCode(request);
        return Result.ok();
    }

    /**
     * 验证码登录（首次登录自动注册）。
     *
     * <p>请求示例：
     * <pre>
     *   POST /api/v1/auth/login
     *   Content-Type: application/json
     *   {"mobile": "13800138000", "code": "123456"}
     * </pre>
     *
     * <p>响应示例（成功）：
     * <pre>
     *   {
     *     "code": "OK",
     *     "message": "操作成功",
     *     "data": {
     *       "token": "a1b2c3d4e5f67890a1b2c3d4e5f67890",
     *       "userId": "101234567890123456",
     *       "nickname": "用户_456789"
     *     },
     *     "timestamp": "2026-05-27T10:00:00+08:00"
     *   }
     * </pre>
     *
     * @param request 请求体，包含 mobile 和 code（@Valid 触发校验）
     * @return 包含 token、userId、nickname 的登录响应
     */
    // 同一 IP 1 分钟内最多尝试登录 10 次（防暴力破解）
    @RateLimit(key = "auth:login", limit = 10, window = 60, keyType = RateLimit.KeyType.IP)
    @PostMapping("/login")
    public Result<LoginResponse> login(@Valid @RequestBody LoginRequest request) {
        LoginResponse response = authService.login(request);
        return Result.ok(response);
    }

    /**
     * 退出登录，删除 Redis 中的 Token。
     *
     * <p>从 Authorization Header 中取出 Token（与鉴权拦截器的解析逻辑相同），
     * 调用 Service 删除 Redis 中的记录。
     *
     * <p>设计说明：/auth/logout 在白名单内，即 Token 已过期时仍可调用，
     * 不会返回 401。Token 不存在时静默成功（幂等），不返回错误。
     *
     * <p>请求示例：
     * <pre>
     *   POST /api/v1/auth/logout
     *   Authorization: Bearer a1b2c3d4e5f67890a1b2c3d4e5f67890
     * </pre>
     *
     * @param httpRequest HTTP 请求，用于取 Authorization Header
     * @return 无数据的成功响应
     */
    @PostMapping("/logout")
    public Result<Void> logout(HttpServletRequest httpRequest) {
        // 从 Header 取 Token（/logout 在白名单，拦截器不会填充 UserContext）
        String authHeader = httpRequest.getHeader("Authorization");
        if (StringUtils.hasText(authHeader) && authHeader.startsWith("Bearer ")) {
            String token = authHeader.substring(7).trim();
            authService.logout(token);
        }
        // Token 不存在或格式错误时静默成功，退出就是退出，幂等操作
        return Result.ok();
    }
}
