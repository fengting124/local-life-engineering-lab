package com.personalprojections.locallife.server.module.auth.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.User;
import com.personalprojections.locallife.server.domain.mapper.UserMapper;
import com.personalprojections.locallife.server.module.auth.dto.LoginRequest;
import com.personalprojections.locallife.server.module.auth.dto.LoginResponse;
import com.personalprojections.locallife.server.module.auth.dto.SendCodeRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Service;

import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;

/**
 * 鉴权 Service，负责验证码发送和登录逻辑。
 *
 * <h2>登录链路主流程</h2>
 * <pre>
 * ┌────────────────────────────────────────────────────────────────┐
 * │  发送验证码 sendCode(mobile)                                    │
 * │                                                                │
 * │  1. 频控检查                                                   │
 * │     Redis Key: login:sms:mobile:{mobile}                       │
 * │     1小时内超过 5 次 → 返回 AUTH_CODE_SEND_TOO_FREQUENT         │
 * │                                                                │
 * │  2. 生成 6 位随机验证码                                         │
 * │                                                                │
 * │  3. 存入 Redis                                                 │
 * │     Key: login:code:{mobile}  Value: 123456  TTL: 5分钟        │
 * │                                                                │
 * │  4. 发送短信（当前 Mock：打印到日志）                            │
 * └────────────────────────────────────────────────────────────────┘
 *
 * ┌────────────────────────────────────────────────────────────────┐
 * │  登录 login(mobile, code)                                      │
 * │                                                                │
 * │  1. 从 Redis 取验证码，比对                                     │
 * │     不存在 / 不一致 → AUTH_CODE_INVALID                        │
 * │                                                                │
 * │  2. 验证码使用后立即删除（一次性，防重放攻击）                   │
 * │                                                                │
 * │  3. 查询用户是否已注册                                          │
 * │     存在 → 直接登录                                             │
 * │     不存在 → 自动注册（手机号注册即用户，无需额外注册接口）      │
 * │                                                                │
 * │  4. 检查账号状态（DISABLED → USER_ACCOUNT_DISABLED）           │
 * │                                                                │
 * │  5. 生成 Token（UUID），构建 LoginUserDTO                       │
 * │     Key: login:token:{token}  Value: LoginUserDTO JSON         │
 * │     TTL: 7 天                                                  │
 * │                                                                │
 * │  6. 返回 LoginResponse（token + userId + nickname）            │
 * └────────────────────────────────────────────────────────────────┘
 * </pre>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class AuthService {

    // ===== Redis Key 常量 =====

    /**
     * 验证码 Key 前缀，完整 Key：login:code:{mobile}
     * Value 是 6 位数字字符串，TTL 5 分钟。
     */
    private static final String CODE_KEY_PREFIX = "login:code:";

    /**
     * 短信频控 Key 前缀（手机号维度），完整 Key：login:sms:mobile:{mobile}
     * Value 是发送次数计数，TTL 1 小时。
     */
    private static final String SMS_LIMIT_MOBILE_KEY_PREFIX = "login:sms:mobile:";

    /**
     * Token Key 前缀，完整 Key：login:token:{token}
     * Value 是 LoginUserDTO 的 JSON 字符串，TTL 7 天。
     */
    private static final String TOKEN_KEY_PREFIX = "login:token:";

    // ===== 业务常量 =====

    /** 验证码有效期，5 分钟。 */
    private static final long CODE_TTL_MINUTES = 5L;

    /** 手机号维度频控时间窗口，1 小时。 */
    private static final long SMS_LIMIT_WINDOW_HOURS = 1L;

    /** 1 小时内最多发送验证码次数。 */
    private static final int SMS_LIMIT_MAX_COUNT = 5;

    /** Token 有效期，7 天。 */
    private static final long TOKEN_TTL_DAYS = 7L;

    // ===== 依赖注入 =====

    /**
     * StringRedisTemplate：存字符串（验证码、Token），Key 和 Value 都是纯字符串。
     * 不用 RedisTemplate<String, Object> 的原因：
     * 验证码和 Token 本身就是字符串，不需要 JSON 序列化，StringRedisTemplate 更轻量。
     */
    private final StringRedisTemplate stringRedisTemplate;

    /**
     * Jackson ObjectMapper：将 LoginUserDTO 序列化为 JSON 字符串存入 Redis，
     * 以及从 Redis 取出 JSON 字符串反序列化为 LoginUserDTO。
     * Spring Boot 自动注册了一个配置好的 ObjectMapper Bean，直接注入即可。
     */
    private final ObjectMapper objectMapper;

    /** User Mapper，负责用户表的 DB 操作。 */
    private final UserMapper userMapper;

    // =========================================================
    // 发送验证码
    // =========================================================

    /**
     * 发送短信验证码。
     *
     * <p>完整流程见类注释。
     * 当前短信发送是 Mock（打印到日志），后续可替换为真实短信服务（阿里云 / 腾讯云）。
     *
     * @param request 请求体，包含 mobile（已通过 @Valid 校验格式）
     */
    public void sendCode(SendCodeRequest request) {
        String mobile = request.getMobile();

        // 1. 频控检查：1 小时内发送次数不能超过 SMS_LIMIT_MAX_COUNT 次
        checkSmsLimit(mobile);

        // 2. 生成 6 位随机验证码
        //    ThreadLocalRandom 比 Random 性能更好（无竞争），在多线程场景下推荐使用
        String code = String.format("%06d", ThreadLocalRandom.current().nextInt(1000000));

        // 3. 存入 Redis，TTL 5 分钟
        //    Key: login:code:{mobile}  →  Value: "123456"
        stringRedisTemplate.opsForValue().set(
                CODE_KEY_PREFIX + mobile,
                code,
                CODE_TTL_MINUTES,
                TimeUnit.MINUTES
        );

        // 4. 发送短信（当前 Mock，开发阶段直接打印到日志，方便测试）
        //    生产环境替换此处为真实短信 SDK 调用，同时：
        //    a) 不在日志中打印验证码（防止日志泄露）
        //    b) 短信发送失败时要删除 Redis 中的验证码，并返回错误
        log.info("[Mock 短信] 手机号: {} ，验证码: {}，有效期 {} 分钟",
                desensitizeMobile(mobile), code, CODE_TTL_MINUTES);

        // 5. 更新频控计数
        incrementSmsCount(mobile);
    }

    // =========================================================
    // 登录（手机号 + 验证码，不存在则自动注册）
    // =========================================================

    /**
     * 登录或自动注册。
     *
     * <p>本平台采用「手机号即账户」策略：用户首次登录时自动创建账号，
     * 不需要单独的注册接口。这在国内 C 端应用中是标准做法（参考美团、大众点评）。
     *
     * @param request 请求体，包含 mobile 和 code（已通过 @Valid 校验格式）
     * @return 登录响应，包含 Token、userId、nickname
     */
    public LoginResponse login(LoginRequest request) {
        String mobile = request.getMobile();
        String inputCode = request.getCode();

        // 1. 从 Redis 取出验证码并比对
        verifyCode(mobile, inputCode);

        // 2. 查询用户（自动注册）
        User user = getOrRegister(mobile);

        // 3. 检查账号状态（DISABLED 的用户不允许登录）
        if ("DISABLED".equals(user.getStatus())) {
            throw new BizException(ErrorCode.USER_ACCOUNT_DISABLED);
        }

        // 4. 生成 Token 并写入 Redis
        String token = generateAndStoreToken(user);

        // 5. 构建并返回响应
        log.info("用户登录成功，userId: {}", user.getId());
        return LoginResponse.builder()
                .token(token)
                .userId(String.valueOf(user.getId()))  // Long → String，防止 JS 精度丢失
                .nickname(user.getNickname())
                .build();
    }

    // =========================================================
    // 退出登录
    // =========================================================

    /**
     * 退出登录，删除 Redis 中的 Token。
     *
     * <p>退出后 Token 立即失效，下次携带该 Token 请求时鉴权拦截器会返回 401。
     * 这是选择 Redis Token 而不是 JWT 的核心优势之一：可以主动使 Token 失效。
     *
     * @param token 当前登录 Token（从 AuthInterceptor 解析并存入 UserContext，
     *              Controller 从 Header 中取出传入）
     */
    public void logout(String token) {
        Boolean deleted = stringRedisTemplate.delete(TOKEN_KEY_PREFIX + token);
        log.info("用户退出登录，token 删除结果: {}", deleted);
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    /**
     * 检查短信发送频控（手机号维度）。
     *
     * <p>频控规则：1 小时内同一手机号最多发送 5 次。
     * 实现方式：Redis 计数器，Key 带 1 小时 TTL，超过阈值抛异常。
     *
     * @param mobile 手机号
     * @throws BizException AUTH_CODE_SEND_TOO_FREQUENT，当超过频控阈值
     */
    private void checkSmsLimit(String mobile) {
        String limitKey = SMS_LIMIT_MOBILE_KEY_PREFIX + mobile;
        String countStr = stringRedisTemplate.opsForValue().get(limitKey);

        if (countStr != null && Integer.parseInt(countStr) >= SMS_LIMIT_MAX_COUNT) {
            log.warn("验证码发送过于频繁，手机号: {}", desensitizeMobile(mobile));
            throw new BizException(ErrorCode.AUTH_CODE_SEND_TOO_FREQUENT);
        }
    }

    /**
     * 更新短信频控计数（发送成功后调用）。
     *
     * <p>使用 INCR 命令原子性地递增计数。
     * 如果 Key 不存在，INCR 会先创建再加 1，但不会自动设置 TTL，
     * 需要在 Key 首次创建时设置 TTL（1 小时）。
     *
     * @param mobile 手机号
     */
    private void incrementSmsCount(String mobile) {
        String limitKey = SMS_LIMIT_MOBILE_KEY_PREFIX + mobile;
        Long count = stringRedisTemplate.opsForValue().increment(limitKey);
        // 只在第一次（count == 1）时设置 TTL，避免后续 INCR 重置过期时间
        if (count != null && count == 1) {
            stringRedisTemplate.expire(limitKey, SMS_LIMIT_WINDOW_HOURS, TimeUnit.HOURS);
        }
    }

    /**
     * 从 Redis 取出验证码并与用户输入比对。
     * 验证通过后立即删除（一次性，防重放攻击）。
     *
     * <p>以下情况都返回 AUTH_CODE_INVALID：
     * <ol>
     *   <li>Redis 中不存在该手机号的验证码（未发送或已过期）</li>
     *   <li>验证码不一致（用户输入错误）</li>
     * </ol>
     * 不区分"未发送"和"已过期"和"错误"，统一返回同一个错误码，
     * 防止攻击者通过不同错误信息推断系统状态。
     *
     * @param mobile    手机号
     * @param inputCode 用户输入的验证码
     * @throws BizException AUTH_CODE_INVALID，验证码无效
     */
    private void verifyCode(String mobile, String inputCode) {
        String codeKey = CODE_KEY_PREFIX + mobile;
        String storedCode = stringRedisTemplate.opsForValue().get(codeKey);

        // 验证码不存在（未发送或已过期）
        if (storedCode == null) {
            throw new BizException(ErrorCode.AUTH_CODE_INVALID);
        }

        // 验证码不一致（用户输入错误）
        if (!storedCode.equals(inputCode)) {
            throw new BizException(ErrorCode.AUTH_CODE_INVALID);
        }

        // 验证通过，立即删除（一次性使用）
        // 注意：delete 是原子操作，不会出现并发删除问题
        stringRedisTemplate.delete(codeKey);
    }

    /**
     * 根据手机号查询用户，不存在则自动注册。
     *
     * <p>「手机号即账户」策略：用户首次登录时自动创建，无需独立注册流程。
     *
     * @param mobile 手机号
     * @return 已存在或刚创建的用户实体
     */
    private User getOrRegister(String mobile) {
        // 查询是否已注册（mobile 有唯一索引，selectOne 不会返回多条）
        User user = userMapper.selectOne(
                new LambdaQueryWrapper<User>()
                        .eq(User::getMobile, mobile)
        );

        if (user != null) {
            // 已注册，直接返回
            return user;
        }

        // 未注册，自动创建新用户
        return registerNewUser(mobile);
    }

    /**
     * 注册新用户（首次登录时调用）。
     *
     * <p>初始数据说明：
     * <ul>
     *   <li>nickname：系统生成，格式 "用户_{userId后6位}"，用户后续可修改</li>
     *   <li>avatar / bio：初始为空，前端展示默认值</li>
     *   <li>status：ENABLED（正常状态）</li>
     * </ul>
     *
     * @param mobile 手机号
     * @return 新创建的用户实体（包含雪花 ID）
     */
    private User registerNewUser(String mobile) {
        // MyBatis-Plus ASSIGN_ID 策略在 insert 时自动生成雪花 ID
        // createdAt / updatedAt 由 MybatisPlusConfig 中的 MetaObjectHandler 自动填充
        User newUser = User.builder()
                .mobile(mobile)
                .status("ENABLED")
                // 先设置一个临时昵称，insert 后可以用 userId 后 6 位更新
                .nickname("")
                .avatar("")
                .bio("")
                .build();

        userMapper.insert(newUser);

        // 用 userId 后 6 位生成初始昵称（insert 后 newUser.getId() 才有值）
        String defaultNickname = "用户_" + String.valueOf(newUser.getId()).substring(
                Math.max(0, String.valueOf(newUser.getId()).length() - 6)
        );
        newUser.setNickname(defaultNickname);
        userMapper.updateById(newUser);

        log.info("新用户注册，userId: {}, mobile: {}", newUser.getId(), desensitizeMobile(mobile));
        return newUser;
    }

    /**
     * 生成登录 Token 并写入 Redis。
     *
     * <p>Token 格式：UUID 去掉中划线（32位十六进制字符串），例如：
     * {@code a1b2c3d4e5f67890a1b2c3d4e5f67890}
     *
     * <p>Redis 存储：
     * <pre>
     *   Key:   login:token:{token}
     *   Value: {"userId":10001,"mobile":"138****8000","nickname":"用户_001001","status":"ENABLED"}
     *   TTL:   7 天
     * </pre>
     *
     * @param user 用户实体
     * @return 生成的 Token 字符串
     */
    private String generateAndStoreToken(User user) {
        String token = UUID.randomUUID().toString().replace("-", "");

        // 构建 LoginUserDTO（只存鉴权必要字段，不存完整 User）
        LoginUserDTO loginUserDTO = LoginUserDTO.builder()
                .userId(user.getId())
                .mobile(desensitizeMobile(user.getMobile()))  // 存脱敏手机号
                .nickname(user.getNickname())
                .status(user.getStatus())
                .build();

        // 序列化为 JSON 字符串存入 Redis
        String userJson;
        try {
            userJson = objectMapper.writeValueAsString(loginUserDTO);
        } catch (JsonProcessingException e) {
            // 序列化失败是系统级错误，不应该发生，打 ERROR 日志
            log.error("LoginUserDTO 序列化失败，userId: {}", user.getId(), e);
            throw new BizException(ErrorCode.SYS_BUSY);
        }

        stringRedisTemplate.opsForValue().set(
                TOKEN_KEY_PREFIX + token,
                userJson,
                TOKEN_TTL_DAYS,
                TimeUnit.DAYS
        );

        return token;
    }

    /**
     * 手机号脱敏，用于日志输出和 Redis 存储。
     * 格式：138****8000（保留前 3 位和后 4 位，中间替换为 ****）。
     *
     * @param mobile 原始 11 位手机号
     * @return 脱敏后的手机号
     */
    private String desensitizeMobile(String mobile) {
        if (mobile == null || mobile.length() != 11) {
            return "***";
        }
        // 保留前 3 位 + 4 个星号 + 后 4 位
        return mobile.substring(0, 3) + "****" + mobile.substring(7);
    }
}
