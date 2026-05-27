package com.personalprojections.locallife.server.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 登录请求体（手机号 + 验证码）。
 *
 * <h2>接口约定</h2>
 * <pre>
 *   POST /api/v1/auth/login
 *   Content-Type: application/json
 *   Body: {"mobile": "13800138000", "code": "123456"}
 * </pre>
 *
 * <h2>登录流程说明</h2>
 * <ol>
 *   <li>前端先调 /auth/code 获取验证码（存入 Redis 5 分钟）</li>
 *   <li>用户输入验证码后调此接口</li>
 *   <li>Service 从 Redis 取出验证码比对，一致则登录成功</li>
 *   <li>验证码使用后立即从 Redis 删除（一次性，防重放）</li>
 *   <li>生成 Token（UUID），存入 Redis，TTL 7 天</li>
 *   <li>返回 Token 给前端，前端后续请求携带在 Authorization Header</li>
 * </ol>
 */
@Data
public class LoginRequest {

    /**
     * 手机号，与发送验证码时使用的手机号保持一致。
     */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[0-9]{10}$", message = "手机号格式不正确")
    private String mobile;

    /**
     * 短信验证码，6 位数字。
     * 校验规则：必须是 6 位纯数字。
     * 注意：不做复杂校验，主要依赖 Redis 中存储的值比对来保证正确性。
     */
    @NotBlank(message = "验证码不能为空")
    @Pattern(regexp = "^[0-9]{6}$", message = "验证码格式不正确，请输入6位数字")
    private String code;
}
