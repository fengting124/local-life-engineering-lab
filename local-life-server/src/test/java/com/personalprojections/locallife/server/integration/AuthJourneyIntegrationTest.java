package com.personalprojections.locallife.server.integration;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.domain.entity.User;
import com.personalprojections.locallife.server.domain.mapper.UserMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 鉴权全链路集成测试：用真实的 Spring 容器、真实的 MySQL（经 ShardingSphere 路由）、
 * 真实的 Redis、真实的拦截器链，把"发验证码 → 登录（含自动注册）→ 用 Token 访问受保护
 * 资源 → 退出登录 → Token 立即失效"这条最核心的用户旅程完整跑一遍。
 *
 * <h2>和 @WebMvcTest 切片测试的分工</h2>
 * <p>{@code AuthControllerTest} / {@code AuthInterceptorTest} 已经用 {@code @MockitoBean}
 * 把 Service、Redis、Mapper 都换成了"听话的"替身，详尽覆盖了"controller 层映射对不对"
 * 和"鉴权决策本身对不对"。但 mock 有一个本质局限：它只能验证"被测代码是否调用了
 * mock，并按 mock 预设的返回值往下走"，无法验证"两段独立编写的真实代码是否真的
 * 在用同一套约定说话"——例如：
 * <ul>
 *   <li>{@code AuthService.sendCode} 写验证码用的 Redis Key，和
 *       {@code AuthService.login} 读验证码用的 Redis Key，是不是同一个拼接结果；</li>
 *   <li>{@code AuthService.generateAndStoreToken} 写入 {@code login:token:{token}} 的
 *       {@link LoginUserDTO} JSON 形状，和 {@code AuthInterceptor} 读取后反序列化期望的
 *       形状，是否完全一致；</li>
 *   <li>MyBatis-Plus 的雪花 ID、{@code @TableName}/驼峰映射、ShardingSphere 的 {@code !SINGLE}
 *       路由规则，组合在一起能否把一个新用户真正写进 MySQL 并原样读出来。</li>
 * </ul>
 * 这类"两端约定是否一致"的问题，只有真正用同一个 Redis、同一个数据库走一遍全链路
 * 才能暴露——这正是本类存在的意义，也是它和切片测试不重复、互补的地方。
 *
 * <h2>运行环境要求</h2>
 * <p>需要 {@code infra/docker-compose.dev.yml} 中的 {@code mysql}、{@code redis} 处于运行
 * 状态——和应用本地开发联调时依赖的是同一套基础设施（{@code sharding.yaml} 里硬编码的
 * {@code jdbcUrl} 指向 {@code localhost:3306/local_life}，{@code application.yml} 里的
 * Redis 地址指向 {@code localhost:6379}）。这也是本项目至今没有引入 Testcontainers 的
 * 直接原因：ShardingSphere 的数据源是通过 {@code classpath:sharding.yaml} 这个静态文件
 * 配置的，文件内的 jdbcUrl 是硬编码字符串，不是 Spring {@code spring.datasource.*}
 * 属性——Testcontainers 惯用的 {@code @DynamicPropertySource} 改的是 Spring 属性，
 * 根本"够不着"分片配置文件内部的连接串，要接入就必须先解决"运行时把容器的随机端口
 * 写回一份临时 sharding YAML 再让 ShardingSphere 读取它"这类基础设施改造，
 * 已超出"给现有代码补测试"的范畴。
 *
 * <h2>测试数据隔离策略</h2>
 * <ul>
 *   <li><b>MySQL</b>：类级别 {@code @Transactional} 让整个测试方法——包括它驱动的
 *       Controller → Service → Mapper 调用链——运行在同一个事务里，方法结束后自动
 *       回滚，新注册的 user 行不会真正落库。<strong>前提是 MockMvc 和测试方法运行在
 *       同一线程</strong>（从而共享同一个事务绑定的 Connection）：如果改用
 *       {@code TestRestTemplate} 发起真实 HTTP 请求，请求会在 Tomcat 的另一个线程上
 *       处理，事务边界不会传播过去，回滚也就不会生效——这是这类全链路测试最容易
 *       踩、也最隐蔽的坑之一，选择 MockMvc 而不是 TestRestTemplate 正是为了绕开它。</li>
 *   <li><b>Redis</b>：不在 JDBC 事务范围内，{@code @Transactional} 管不到，必须在
 *       {@link #cleanUpRedisState()} 里手动删除验证码 / 频控 / 限流 / Token 各个 Key——
 *       尤其是 Token（7 天 TTL）和短信频控计数（1 小时 TTL），不清理会在 Redis 里
 *       累积残留，且可能影响后续重复运行。</li>
 *   <li><b>手机号</b>：每个测试方法在 {@link #setUpUniqueIdentifiersAndClearRateLimit()}
 *       里随机生成，既确保稳定走入"自动注册"分支（不会撞上种子数据中已存在的手机号
 *       而改走"已注册直接登录"分支），也避免带 TTL 的频控类 Redis 状态跨测试方法、
 *       跨多次运行互相累积干扰。</li>
 *   <li><b>发验证码接口的 IP 限流</b>：{@code @RateLimit(key = "auth:code", limit = 1,
 *       window = 60, keyType = IP)}——按 IP 而不是手机号维度限流，MockMvc 请求固定
 *       来自 127.0.0.1，本类两个测试方法各发一次验证码就会撞到同一个 Key，必须在
 *       每个方法开始前显式清空，否则后跑的方法会直接收到 429。</li>
 * </ul>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Transactional
@ActiveProfiles("test")
class AuthJourneyIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private StringRedisTemplate stringRedisTemplate;

    @Autowired
    private UserMapper userMapper;

    /** sendCode 按 IP 维度限流，MockMvc 发出的请求 remoteAddr 固定是 127.0.0.1。*/
    private static final String SEND_CODE_RATE_LIMIT_KEY = "rate_limit:auth:code:127.0.0.1";

    private String mobile;
    private String issuedToken;

    @BeforeEach
    void setUpUniqueIdentifiersAndClearRateLimit() {
        // ^1[0-9]{10}$ 只要求"1 开头 + 10 位数字"，不关心号段是否真实分配。
        // 用随机数生成：(a) 不会撞上种子数据里的固定手机号，从而稳定走入本测试要
        // 覆盖的"自动注册"分支；(b)"1 小时短信频控计数"这类带 TTL 的 Redis 状态
        // 不会在多次重复运行之间累积干扰。
        mobile = "1" + String.format("%010d", ThreadLocalRandom.current().nextLong(10_000_000_000L));

        // 按 IP 维度限流的 Key 不含手机号，两个测试方法各发一次验证码就会共用同一个
        // Key——必须在每个方法开始前清空，相当于模拟"距上次发送已经过去了 60 秒"。
        stringRedisTemplate.delete(SEND_CODE_RATE_LIMIT_KEY);
    }

    @AfterEach
    void cleanUpRedisState() {
        // login:code 通常已被 verifyCode() 在登录成功时主动删除，这里是兜底
        // （例如断言在拿到验证码之后、登录成功之前就失败的场景）。
        // login:sms:mobile 有 1 小时 TTL，必须显式清理，否则下次用同一手机号
        // （理论上极小概率重复）跑测试会在频控计数上看到非 0 起始值。
        stringRedisTemplate.delete(List.of(
                "login:code:" + mobile,
                "login:sms:mobile:" + mobile,
                SEND_CODE_RATE_LIMIT_KEY
        ));
        // login:token 有 7 天 TTL，是这里面最需要主动清理的一个——
        // 不清理的话，残留的有效 Token 会一直躺在 Redis 里。
        if (issuedToken != null) {
            stringRedisTemplate.delete("login:token:" + issuedToken);
        }
    }

    @Test
    void happyPath_sendCodeThenLoginThenAccessProtectedResourceThenLogout() throws Exception {
        // ===== 1. 发送验证码（/api/v1/auth/** 在白名单内，未登录可直接访问）=====
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"" + mobile + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        // sendCode 的"发短信"目前是 Mock 实现——只打日志，不真的对接短信网关。
        // 集成测试拿到验证码的唯一正确方式就是直接读 Redis：这恰好直接验证了
        // AuthService.sendCode（写入方）和 AuthService.login（读取方）拼出来的
        // Key 是不是同一个——如果两端字符串拼接的前缀或手机号字段不一致，这里
        // 读到的就是 null，断言会立刻失败并指向真正的问题，而不是在 mock 世界里
        // "因为两边都是我手写的 stub，所以永远一致"。
        String code = stringRedisTemplate.opsForValue().get("login:code:" + mobile);
        assertThat(code)
                .as("AuthService.sendCode 写入验证码的 Key，必须和 AuthService.login 读取验证码的 Key 完全一致")
                .isNotNull()
                .matches("\\d{6}");

        // ===== 2. 登录（手机号此前未注册过 → 触发自动注册分支）=====
        MvcResult loginResult = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"" + mobile + "\", \"code\": \"" + code + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"))
                .andExpect(jsonPath("$.data.token").isNotEmpty())
                .andExpect(jsonPath("$.data.userId").isNotEmpty())
                .andExpect(jsonPath("$.data.nickname").isNotEmpty())
                .andReturn();

        JsonNode loginData = objectMapper.readTree(loginResult.getResponse().getContentAsString()).path("data");
        issuedToken = loginData.path("token").asText();
        String userId = loginData.path("userId").asText();
        String nickname = loginData.path("nickname").asText();

        assertThat(issuedToken)
                .as("Token 格式应为去掉中划线的 UUID（32 位十六进制字符串），见 AuthService#generateAndStoreToken")
                .matches("[0-9a-f]{32}");
        assertThat(nickname)
                .as("自动注册新用户时，昵称必须是「用户_」+ ID 后 6 位这个约定格式（见 AuthService#registerNewUser）")
                .startsWith("用户_");

        // ===== 3. 落库验证：自动注册不是只在内存里造了个临时对象，而是真的经
        //          MyBatis-Plus + ShardingSphere(!SINGLE 路由) 写进了 MySQL 的 user 表 =====
        User persisted = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getMobile, mobile));
        assertThat(persisted)
                .as("login() 在用户不存在时必须真正完成自动注册并落库")
                .isNotNull();
        assertThat(String.valueOf(persisted.getId())).isEqualTo(userId);
        assertThat(persisted.getNickname()).isEqualTo(nickname);
        assertThat(persisted.getStatus()).isEqualTo("ENABLED");

        // ===== 4. Redis 落地验证：login:token:{token} 存的 LoginUserDTO 形状，正是
        //          AuthInterceptor 反序列化时期望的形状（两端"约定一致性"的直接证据）=====
        String storedJson = stringRedisTemplate.opsForValue().get("login:token:" + issuedToken);
        assertThat(storedJson)
                .as("AuthService.generateAndStoreToken 必须把 Token 写到 AuthInterceptor 读取的同一个 Key 下")
                .isNotNull();
        LoginUserDTO storedLoginUser = objectMapper.readValue(storedJson, LoginUserDTO.class);
        assertThat(storedLoginUser.getUserId()).isEqualTo(persisted.getId());
        assertThat(storedLoginUser.getNickname()).isEqualTo(nickname);
        assertThat(storedLoginUser.getStatus()).isEqualTo("ENABLED");

        // ===== 5. 用拿到的 Token 访问受保护资源 GET /api/v1/users/me =====
        // 这一步必须穿过真实的 AuthInterceptor.preHandle：解析 Header → 查 Redis →
        // 反序列化为 LoginUserDTO → 写入 UserContext；再由 UserService 从
        // UserContext.getUserId() 取出 ID 查库、组装 VO。链路上任何一环 Key 名 /
        // DTO 字段 / 序列化格式对不上，这里得到的都会是 401 而不是 200 + 正确数据。
        String desensitizedMobile = mobile.substring(0, 3) + "****" + mobile.substring(7);
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.userId").value(userId))
                .andExpect(jsonPath("$.data.nickname").value(nickname))
                .andExpect(jsonPath("$.data.mobile").value(desensitizedMobile));

        // ===== 6. 退出登录：Token 必须被真正从 Redis 删除，而不仅仅是返回了"成功" =====
        mockMvc.perform(post("/api/v1/auth/logout")
                        .header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value("OK"));

        assertThat(stringRedisTemplate.hasKey("login:token:" + issuedToken))
                .as("logout() 必须真正从 Redis 删除 Token，否则旧 Token 在 TTL 到期前依然有效——是更严重的安全问题")
                .isFalse();

        // ===== 7. 退出登录后，同一个 Token 必须立即失效（401），而不是继续被放行 =====
        // 这正是类注释里提到的"选 Redis Token 而不是无状态 JWT 的核心优势"——
        // 必须用一次端到端请求才能验证"主动失效"真的生效了，纯单测/切片测试中
        // Redis 永远是 mock 出来的，"删除"和"过期校验"这两步根本不会真正连通。
        mockMvc.perform(get("/api/v1/users/me")
                        .header("Authorization", "Bearer " + issuedToken))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("AUTH_TOKEN_EXPIRED"));
    }

    @Test
    void login_withWrongCode_rejectsWithoutIssuingTokenOrCreatingUser() throws Exception {
        // 先正常发一次验证码，建立"确实存在一个有效验证码，只是用户输错了"这个前提——
        // 否则测的就是"根本没发过验证码"那条分支（AuthControllerTest 已经用 mock 覆盖过
        // "请求格式校验"这一层，这里要测的是真实 Redis 比对逻辑本身）。
        mockMvc.perform(post("/api/v1/auth/code")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"" + mobile + "\"}"))
                .andExpect(status().isOk());

        String correctCode = stringRedisTemplate.opsForValue().get("login:code:" + mobile);
        assertThat(correctCode).isNotNull();
        // 构造一个确定不同于正确验证码的 6 位数字（排除随机数恰好撞上正确验证码的极小概率）
        String wrongCode = "000000".equals(correctCode) ? "999999" : "000000";

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"mobile\": \"" + mobile + "\", \"code\": \"" + wrongCode + "\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("AUTH_CODE_INVALID"))
                .andExpect(jsonPath("$.data").doesNotExist());

        // 验证码错误必须在 verifyCode() 这一步就短路，不能继续往下走到自动注册——
        // 一个素未谋面的手机号，不应该仅仅因为"调用过一次登录接口"就在数据库里多出一行。
        // 这是 mock 测试无法验证的：mock 版本的 verifyCode 不管返回什么，都不会
        // 真的去检查"后面到底有没有继续执行下去"在数据库里留下痕迹。
        User shouldNotExist = userMapper.selectOne(new LambdaQueryWrapper<User>().eq(User::getMobile, mobile));
        assertThat(shouldNotExist)
                .as("验证码错误时必须在 verifyCode() 短路退出，不能继续执行到自动注册并落库")
                .isNull();

        // 验证码只在"验证通过"时才会被消费删除——错误输入不应该消耗它，
        // 这样用户多打错一次也不必重新发送短信，是对体验的保护。
        assertThat(stringRedisTemplate.opsForValue().get("login:code:" + mobile))
                .as("验证码错误不应该消耗掉验证码本身（用户改正后应该还能用同一个验证码登录成功）")
                .isEqualTo(correctCode);
    }
}
