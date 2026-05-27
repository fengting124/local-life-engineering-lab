package com.personalprojections.locallife.server.module.user.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 用户主页信息 VO（View Object），返回给前端展示的用户数据。
 *
 * <h2>VO vs Entity 的区别</h2>
 * <ul>
 *   <li>Entity（User）：与数据库一一对应，包含所有字段（含敏感字段、内部字段）</li>
 *   <li>VO（UserProfileVO）：只包含前端需要展示的字段，敏感字段脱敏或不返回</li>
 * </ul>
 *
 * <h2>脱敏说明</h2>
 * <ul>
 *   <li>手机号：138****8000（中间 4 位替换为 ****）</li>
 *   <li>不返回 status、deleted、createdAt、updatedAt 等内部字段</li>
 * </ul>
 *
 * <h2>使用场景</h2>
 * <ul>
 *   <li>{@code GET /api/v1/users/me}     - 当前登录用户自己的信息（可见完整脱敏手机号）</li>
 *   <li>{@code GET /api/v1/users/{id}}   - 查看其他用户主页（手机号不返回）</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class UserProfileVO {

    /**
     * 用户 ID，字符串类型（雪花 ID 超过 JS 安全整数范围，必须用字符串传递）。
     */
    private String userId;

    /**
     * 用户昵称。
     */
    private String nickname;

    /**
     * 头像 URL，为空时前端展示默认头像。
     */
    private String avatar;

    /**
     * 个人简介，为空时前端不展示。
     */
    private String bio;

    /**
     * 脱敏手机号，格式：138****8000。
     * 只在 /users/me（查自己）时返回，查他人主页时此字段为 null（不序列化）。
     * Jackson 配置了 non_null，null 字段不会出现在响应 JSON 中。
     */
    private String mobile;
}
