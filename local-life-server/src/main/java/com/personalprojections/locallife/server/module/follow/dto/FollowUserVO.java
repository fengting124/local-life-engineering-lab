package com.personalprojections.locallife.server.module.follow.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 关注用户 VO，用于展示关注列表、粉丝列表、共同关注列表中的用户简要信息。
 *
 * <h2>为什么只返回这三个字段</h2>
 * <p>关注列表场景只需要展示用户头像和昵称，不需要完整 UserProfile（bio、mobile 等）。
 * 精简字段减少传输量，也避免不必要的隐私字段暴露。
 * 前端如需跳转完整主页，用 userId 调 GET /api/v1/users/{userId} 即可。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class FollowUserVO {

    /** 用户 ID，字符串（防 JS 精度截断）。 */
    private String userId;

    /** 用户昵称。 */
    private String nickname;

    /** 用户头像 URL。 */
    private String avatar;
}
