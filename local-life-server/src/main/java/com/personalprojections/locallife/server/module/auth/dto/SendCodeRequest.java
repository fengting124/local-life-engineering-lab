package com.personalprojections.locallife.server.module.auth.dto;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import lombok.Data;

/**
 * 发送验证码请求体。
 *
 * <h2>校验注解说明</h2>
 * <ul>
 *   <li>{@code @NotBlank}：字段不能为 null，也不能是空字符串或纯空格。
 *       比 @NotNull 更严格，适合 String 类型字段。</li>
 *   <li>{@code @Pattern}：正则校验，确保 mobile 是 11 位数字且以 1 开头。</li>
 * </ul>
 *
 * <p>校验失败时 Spring 抛出 {@code MethodArgumentNotValidException}，
 * 由 {@link com.personalprojections.locallife.server.common.exception.GlobalExceptionHandler}
 * 统一捕获，返回 {@code SYS_PARAM_INVALID}。
 *
 * <h2>接口约定</h2>
 * <pre>
 *   POST /api/v1/auth/code
 *   Content-Type: application/json
 *   Body: {"mobile": "13800138000"}
 * </pre>
 */
@Data
public class SendCodeRequest {

    /**
     * 手机号，11 位，以 1 开头。
     * 校验规则：必须是 1 开头的 11 位纯数字。
     * 注意：当前是简化校验，不做运营商号段细分（如 130-139、150-159 等），
     * 生产环境可以补充更严格的正则或借助第三方手机号验证服务。
     */
    @NotBlank(message = "手机号不能为空")
    @Pattern(regexp = "^1[0-9]{10}$", message = "手机号格式不正确")
    private String mobile;
}
