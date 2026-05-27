package com.personalprojections.locallife.server.module.user.service;

import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.User;
import com.personalprojections.locallife.server.domain.mapper.UserMapper;
import com.personalprojections.locallife.server.module.user.dto.UserProfileVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

/**
 * 用户 Service，负责用户信息查询和修改。
 *
 * <h2>与 AuthService 的职责划分</h2>
 * <ul>
 *   <li>{@code AuthService}：负责「身份认证」——发验证码、登录、退出</li>
 *   <li>{@code UserService}：负责「用户信息」——查主页、改昵称、改头像</li>
 * </ul>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class UserService {

    private final UserMapper userMapper;

    /**
     * 查询当前登录用户自己的个人信息。
     *
     * <p>从 {@link UserContext} 取当前用户 ID，查询数据库返回完整脱敏信息。
     * 包含脱敏手机号（自己可以看到）。
     *
     * @return 当前用户的主页 VO，含脱敏手机号
     */
    public UserProfileVO getMyProfile() {
        Long userId = UserContext.getUserId();
        User user = getUserOrThrow(userId);
        return buildVO(user, true);  // true = 包含手机号（自己查自己）
    }

    /**
     * 查询指定用户的主页信息（查他人）。
     *
     * <p>不返回手机号（他人隐私）。
     *
     * @param userId 目标用户 ID
     * @return 目标用户的主页 VO，不含手机号
     */
    public UserProfileVO getUserProfile(Long userId) {
        User user = getUserOrThrow(userId);
        return buildVO(user, false);  // false = 不包含手机号（查他人）
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    /**
     * 按 userId 查询用户，不存在则抛业务异常。
     *
     * <p>使用统一的「用户不存在」处理：无论是真的不存在，还是 deleted=1，
     * 都返回 ORDER_NOT_FOUND（这里复用了同样的 404 语义处理策略）。
     * 实际上 MyBatis-Plus 配置了逻辑删除，selectById 会自动过滤 deleted=1 的记录。
     *
     * @param userId 用户 ID
     * @return 用户实体
     * @throws BizException USER_ACCOUNT_DISABLED，当用户不存在时
     */
    private User getUserOrThrow(Long userId) {
        User user = userMapper.selectById(userId);
        if (user == null) {
            // 用户不存在（正常情况下不应发生，userId 来自 Token，Token 来自登录，登录时用户已存在）
            // 如果发生，可能是数据被物理删除或 Token 与用户数据不一致，记录 WARN
            log.warn("用户不存在，userId: {}", userId);
            throw new BizException(ErrorCode.USER_ACCOUNT_DISABLED);
        }
        return user;
    }

    /**
     * 将 User 实体转换为 UserProfileVO。
     *
     * <p>转换规则：
     * <ul>
     *   <li>userId：Long → String（防 JS 精度丢失）</li>
     *   <li>手机号：根据 includeMobile 决定是否包含，包含时脱敏</li>
     *   <li>敏感字段（status、deleted 等）：不暴露给前端</li>
     * </ul>
     *
     * @param user          用户实体
     * @param includeMobile 是否包含手机号（自己查自己时为 true）
     * @return 用户主页 VO
     */
    private UserProfileVO buildVO(User user, boolean includeMobile) {
        return UserProfileVO.builder()
                .userId(String.valueOf(user.getId()))
                .nickname(user.getNickname())
                .avatar(user.getAvatar())
                .bio(user.getBio())
                // 手机号脱敏：138****8000，只在查自己时包含
                .mobile(includeMobile ? desensitizeMobile(user.getMobile()) : null)
                .build();
    }

    /**
     * 手机号脱敏，保留前 3 位和后 4 位，中间替换为 ****。
     *
     * <p>注意：AuthService 中也有相同逻辑，后续可以提取到公共工具类
     * {@code com.personalprojections.locallife.server.common.util.SensitiveUtil}。
     * 当前先各自维护，等需求稳定后再统一重构。
     *
     * @param mobile 原始手机号
     * @return 脱敏手机号
     */
    private String desensitizeMobile(String mobile) {
        if (mobile == null || mobile.length() != 11) {
            return "***";
        }
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }
}
