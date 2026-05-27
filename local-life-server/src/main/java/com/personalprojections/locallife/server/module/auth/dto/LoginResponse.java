package com.personalprojections.locallife.server.module.auth.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 登录响应体，登录成功后返回给前端。
 *
 * <h2>字段设计说明</h2>
 * <ul>
 *   <li>{@code token}    - 前端后续所有请求都需要携带此 Token，
 *                          放在 Header: {@code Authorization: Bearer {token}}</li>
 *   <li>{@code userId}   - 以字符串形式返回，防止 JS 精度丢失（雪花 ID 超过 JS 安全整数范围）</li>
 *   <li>{@code nickname} - 前端可以直接展示，无需再次请求用户信息接口</li>
 * </ul>
 *
 * <h2>不返回哪些字段</h2>
 * <ul>
 *   <li>手机号：敏感信息，登录响应中不必要</li>
 *   <li>状态、deleted：内部字段，不暴露给前端</li>
 *   <li>createdAt / updatedAt：登录场景不需要</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class LoginResponse {

    /**
     * 登录 Token（UUID 格式），前端存储在 localStorage 或内存中，
     * 后续请求放在 Header: {@code Authorization: Bearer {token}}。
     */
    private String token;

    /**
     * 用户 ID，字符串类型。
     * 接口规范要求所有 ID 以字符串传递，防止 JS Number 精度截断。
     */
    private String userId;

    /**
     * 用户昵称，用于登录后立即展示欢迎信息，无需额外调用用户信息接口。
     */
    private String nickname;
}
