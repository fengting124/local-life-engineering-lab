package com.personalprojections.locallife.server.module.seckill;

import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import com.personalprojections.locallife.server.domain.entity.SeckillSession;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.SeckillSessionMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.module.mq.service.OutboxService;
import com.personalprojections.locallife.server.module.seckill.dto.SeckillRequest;
import com.personalprojections.locallife.server.module.seckill.service.SeckillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.connection.RedisStandaloneConfiguration;
import org.springframework.data.redis.connection.lettuce.LettuceConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import java.time.LocalDateTime;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 秒杀「防超卖 / 防重复领取」并发集成测试 —— 整个项目最有技术含量的测试。
 *
 * <h2>为什么这是集成测试而不是单元测试</h2>
 * <p>{@link SeckillServiceTest}（单元测试）把 {@code stringRedisTemplate.execute(...)} 整个
 * Mock 掉，只验证「Lua 返回 0/1/2 时 Service 怎么分支」——它<b>无法</b>验证 Lua 脚本本身在
 * 高并发下是否真的原子、是否真的不超卖。要验证这一点，必须有一个<b>真实的 Redis</b> 来跑
 * 真实的 {@code seckill.lua}。这正是 Testcontainers 的价值：拉起一个真 Redis 容器，
 * 让 1000 个线程并发抢 100 个库存，断言「恰好 100 个成功，一个不多一个不少」。
 *
 * <h2>测试设计（最大化并发争用）</h2>
 * <pre>
 *   1. 真实 Redis 容器（redis:7.4-alpine）
 *   2. 真实 StringRedisTemplate（Lettuce 连真容器）
 *   3. 真实 SeckillService + 真实 seckill.lua（DB 类依赖 Mock 掉，聚焦 Redis 原子性）
 *   4. 预置库存 = 100
 *   5. 1000 个线程，全部 await 同一个 startLatch，countDown 瞬间「同时」发起抢购
 *   6. 断言：成功数 == 100，Redis 最终库存 == 0，已抢用户集合大小 == 100
 * </pre>
 *
 * <h2>如果 Lua 不是原子的会怎样</h2>
 * <p>把判重 + 判库存 + 扣减拆成多条普通 Redis 命令，这个测试会<b>红</b>：
 * 多个线程同时读到「库存 > 0」，同时 DECR，成功数 > 100（超卖）。
 * Lua 在 Redis 单线程模型里整段原子执行，从根上消除了这个竞态——本测试就是它的证明。
 */
@Testcontainers
@DisplayName("秒杀并发集成测试（真实 Redis + 真实 Lua）")
class SeckillConcurrencyIT {

    /**
     * 真实 Redis 容器。static + @Container：整个测试类共享一个容器，类结束时销毁。
     * 用 redis:7.4-alpine（与 infra/docker-compose.dev.yml 的生产同款），保证测试环境与线上一致。
     */
    @Container
    static final GenericContainer<?> REDIS = new GenericContainer<>(DockerImageName.parse("redis:7.4-alpine"))
            .withExposedPorts(6379);

    /** 固定的场次 / 券模板 / 用户上下文常量 */
    private static final long SESSION_ID = 8001L;
    private static final long TEMPLATE_ID = 9001L;
    private static final int STOCK = 100;

    private LettuceConnectionFactory connectionFactory;
    private StringRedisTemplate redisTemplate;
    private SeckillService seckillService;

    @BeforeEach
    void setUp() {
        // 1. 用容器映射出来的 host:port 建真实 Redis 连接
        RedisStandaloneConfiguration cfg =
                new RedisStandaloneConfiguration(REDIS.getHost(), REDIS.getMappedPort(6379));
        connectionFactory = new LettuceConnectionFactory(cfg);
        connectionFactory.afterPropertiesSet();
        connectionFactory.start();

        redisTemplate = new StringRedisTemplate(connectionFactory);
        redisTemplate.afterPropertiesSet();

        // 每个测试前清库，保证隔离
        redisTemplate.getConnectionFactory().getConnection().serverCommands().flushAll();

        // 2. Mock 掉所有 DB 类依赖：本测试只验证 Redis 原子性，不关心异步落库
        SeckillSessionMapper seckillSessionMapper = mock(SeckillSessionMapper.class);
        CouponTemplateMapper couponTemplateMapper = mock(CouponTemplateMapper.class);
        UserCouponMapper userCouponMapper = mock(UserCouponMapper.class);
        BusinessMetrics businessMetrics = mock(BusinessMetrics.class);
        OutboxService outboxService = mock(OutboxService.class);

        // 场次：ACTIVE，券模板匹配，当前时间落在 [begin, end] 窗口内
        SeckillSession session = SeckillSession.builder()
                .id(SESSION_ID)
                .couponTemplateId(TEMPLATE_ID)
                .seckillStock(STOCK)
                .beginTime(LocalDateTime.now().minusHours(1))
                .endTime(LocalDateTime.now().plusHours(1))
                .sessionStatus("ACTIVE")
                .build();
        when(seckillSessionMapper.selectById(SESSION_ID)).thenReturn(session);

        // 券模板：异步事件需要 validDays 字段
        CouponTemplate template = CouponTemplate.builder()
                .id(TEMPLATE_ID)
                .validDays(7)
                .build();
        when(couponTemplateMapper.selectById(TEMPLATE_ID)).thenReturn(template);

        // 3. 真实 SeckillService（手动 new，不走 Spring 代理；@Transactional 在此无副作用，
        //    因为我们把落库的 outboxService Mock 成 no-op，真正被测的是 Redis Lua 这一段）
        seckillService = new SeckillService(
                couponTemplateMapper, seckillSessionMapper, userCouponMapper,
                redisTemplate, businessMetrics, outboxService);
    }

    @AfterEach
    void tearDown() {
        if (connectionFactory != null) {
            connectionFactory.destroy();
        }
        UserContext.clear();
    }

    // =========================================================
    // 核心：高并发不超卖
    // =========================================================

    @Test
    @DisplayName("1000 线程抢 100 库存：恰好 100 成功，零超卖")
    void noOversell_under1000ConcurrentRequests() throws InterruptedException {
        // 预置库存 = 100（模拟运营把场次库存预热进 Redis）
        String stockKey = String.format("seckill:stock:%d:%d", SESSION_ID, TEMPLATE_ID);
        String userSetKey = String.format("seckill:user:%d:%d", SESSION_ID, TEMPLATE_ID);
        redisTemplate.opsForValue().set(stockKey, String.valueOf(STOCK));

        int threadCount = 1000;
        ExecutorService pool = Executors.newFixedThreadPool(64);
        CountDownLatch startLatch = new CountDownLatch(1);     // 发令枪：所有线程同时起跑
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger stockExhausted = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            final long userId = 10_000L + i;   // 每个线程一个独立用户（排除「重复领取」干扰，纯测超卖）
            pool.submit(() -> {
                try {
                    startLatch.await();        // 等发令枪
                    bindUser(userId);
                    seckillService.doSeckill(request());
                    successCount.incrementAndGet();
                } catch (BizException e) {
                    stockExhausted.incrementAndGet();   // 库存不足，预期内
                } catch (Exception ignored) {
                    // 连接抖动等：不计入成功
                } finally {
                    UserContext.clear();
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();                                 // 1000 个线程「同时」冲
        boolean finished = doneLatch.await(60, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).as("所有线程应在 60s 内完成").isTrue();

        // 核心断言：成功数恰好等于库存，绝不超卖
        assertThat(successCount.get())
                .as("成功抢到的人数必须恰好等于库存 100，多一个就是超卖")
                .isEqualTo(STOCK);
        // 失败的都是库存不足
        assertThat(stockExhausted.get()).isEqualTo(threadCount - STOCK);

        // Redis 侧的最终状态自证清白：库存归零、已抢集合恰好 100 人
        assertThat(redisTemplate.opsForValue().get(stockKey))
                .as("最终库存必须为 0").isEqualTo("0");
        assertThat(redisTemplate.opsForSet().size(userSetKey))
                .as("已抢用户集合大小必须等于 100").isEqualTo(100L);
    }

    // =========================================================
    // 核心：同一用户并发只能领一张
    // =========================================================

    @Test
    @DisplayName("同一用户 50 并发请求：恰好 1 次成功，防重复领取")
    void noDoubleClaim_forSameUserConcurrent() throws InterruptedException {
        String stockKey = String.format("seckill:stock:%d:%d", SESSION_ID, TEMPLATE_ID);
        redisTemplate.opsForValue().set(stockKey, "100");  // 库存充足，唯一的限制是「一人一张」

        int threadCount = 50;
        final long sameUserId = 77_777L;
        ExecutorService pool = Executors.newFixedThreadPool(32);
        CountDownLatch startLatch = new CountDownLatch(1);
        CountDownLatch doneLatch = new CountDownLatch(threadCount);
        AtomicInteger successCount = new AtomicInteger(0);

        for (int i = 0; i < threadCount; i++) {
            pool.submit(() -> {
                try {
                    startLatch.await();
                    bindUser(sameUserId);     // 所有线程都是同一个用户
                    seckillService.doSeckill(request());
                    successCount.incrementAndGet();
                } catch (BizException ignored) {
                    // 重复领取，预期内
                } catch (Exception ignored) {
                } finally {
                    UserContext.clear();
                    doneLatch.countDown();
                }
            });
        }

        startLatch.countDown();
        boolean finished = doneLatch.await(30, TimeUnit.SECONDS);
        pool.shutdownNow();

        assertThat(finished).isTrue();
        assertThat(successCount.get())
                .as("同一用户并发抢券，无论几次请求，只能成功 1 次")
                .isEqualTo(1);
        // 只扣了 1 个库存
        assertThat(redisTemplate.opsForValue().get(stockKey)).isEqualTo("99");
    }

    // =========================================================
    // 辅助
    // =========================================================

    private void bindUser(long userId) {
        UserContext.set(LoginUserDTO.builder().userId(userId).build());
    }

    private SeckillRequest request() {
        SeckillRequest req = new SeckillRequest();
        req.setSessionId(SESSION_ID);
        req.setCouponTemplateId(TEMPLATE_ID);
        return req;
    }
}
