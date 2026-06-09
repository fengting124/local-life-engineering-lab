package com.personalprojections.locallife.server.module.seckill;

import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import com.personalprojections.locallife.server.domain.entity.SeckillSession;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.SeckillSessionMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.module.mq.service.OutboxService;
import com.personalprojections.locallife.server.module.seckill.dto.SeckillRequest;
import com.personalprojections.locallife.server.module.seckill.dto.SeckillResultVO;
import com.personalprojections.locallife.server.module.seckill.service.SeckillService;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.SetOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

import java.time.LocalDateTime;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link SeckillService} 单元测试。
 *
 * <h2>测试重点</h2>
 * <ul>
 *   <li>Lua 脚本三种返回值（0/1/2/null）对应的业务分支</li>
 *   <li>场次时间窗和状态的校验逻辑（validateSession 所有路径）</li>
 *   <li>querySeckillResult 多层兜底逻辑（DB → RESULT_KEY → USER_SET_KEY）</li>
 * </ul>
 *
 * <h2>Lua 脚本 mock 策略</h2>
 * <p>{@code SECKILL_SCRIPT} 是静态 final 字段，真正的 Lua 逻辑在 Redis 服务器执行。
 * 测试中 {@code stringRedisTemplate.execute()} 整个方法被 mock，
 * 直接控制返回值（0/1/2/null），与真实脚本内容解耦。
 */
@ExtendWith(MockitoExtension.class)
class SeckillServiceTest {

    @Mock private SeckillSessionMapper seckillSessionMapper;
    @Mock private CouponTemplateMapper couponTemplateMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private BusinessMetrics businessMetrics;
    @Mock private OutboxService outboxService;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private SetOperations<String, String> setOps;

    @InjectMocks
    private SeckillService seckillService;

    private static final long SESSION_ID  = 100L;
    private static final long TEMPLATE_ID = 200L;
    private static final long USER_ID     = 9999L;

    @BeforeEach
    void setUp() {
        UserContext.set(LoginUserDTO.builder().userId(USER_ID).status("ENABLED").build());
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
    }

    // =========================================================
    // 1. validateSession — 场次校验逻辑
    // =========================================================

    @Test
    void doSeckill_sessionNotFound_throwsSeckillNotStarted() {
        when(seckillSessionMapper.selectById(SESSION_ID)).thenReturn(null);
        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SECKILL_NOT_STARTED));
        verifyNoInteractions(stringRedisTemplate);
    }

    @Test
    void doSeckill_couponTemplateIdMismatch_throwsSeckillNotStarted() {
        // 攻击者用 session_A 的 ID 搭配 template_B 的 ID 来请求——应被拒绝
        SeckillSession session = activeSession();
        session.setCouponTemplateId(999L);  // session 关联的是另一个模板
        when(seckillSessionMapper.selectById(SESSION_ID)).thenReturn(session);

        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SECKILL_NOT_STARTED));
    }

    @Test
    void doSeckill_beforeBeginTime_throwsSeckillNotStarted() {
        SeckillSession session = activeSession();
        session.setBeginTime(LocalDateTime.now().plusMinutes(10));  // 还没到开始时间
        when(seckillSessionMapper.selectById(SESSION_ID)).thenReturn(session);

        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SECKILL_NOT_STARTED));
    }

    @Test
    void doSeckill_afterEndTime_throwsSeckillExpired() {
        SeckillSession session = activeSession();
        session.setEndTime(LocalDateTime.now().minusMinutes(5));  // 已超过结束时间
        when(seckillSessionMapper.selectById(SESSION_ID)).thenReturn(session);

        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SECKILL_EXPIRED));
    }

    @Test
    void doSeckill_statusPending_throwsSeckillNotStarted() {
        SeckillSession session = activeSession();
        session.setSessionStatus("PENDING");  // 还没切到 ACTIVE
        when(seckillSessionMapper.selectById(SESSION_ID)).thenReturn(session);

        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SECKILL_NOT_STARTED));
    }

    @Test
    void doSeckill_statusEnded_throwsSeckillExpired() {
        SeckillSession session = activeSession();
        session.setSessionStatus("ENDED");
        when(seckillSessionMapper.selectById(SESSION_ID)).thenReturn(session);

        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SECKILL_EXPIRED));
    }

    // =========================================================
    // 2. doSeckill — Lua 脚本返回值分支
    //
    // 关键技术点：Lua 脚本在 Redis 中原子执行，单元测试通过 mock
    // execute() 返回值来覆盖三种结果，与脚本内容完全解耦。
    // =========================================================

    @Test
    void doSeckill_luaReturns1_stockExhausted_throwsCouponStockExhausted() {
        stubValidSession();
        stubLuaResult(1L);  // Lua 返回 1：库存不足

        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COUPON_STOCK_EXHAUSTED));
    }

    @Test
    void doSeckill_luaReturnsNull_treatedAsStockExhausted() {
        // null 是 Redis 执行异常或网络问题时的降级返回，和「库存不足」一样拒绝
        stubValidSession();
        stubLuaResult(null);

        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COUPON_STOCK_EXHAUSTED));
    }

    @Test
    void doSeckill_luaReturns2_alreadyReceived_throwsCouponAlreadyReceived() {
        stubValidSession();
        stubLuaResult(2L);  // Lua 返回 2：用户已抢过

        assertThatThrownBy(() -> seckillService.doSeckill(request()))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.COUPON_ALREADY_RECEIVED));
    }

    @Test
    void doSeckill_luaReturns0_success_publishesEventAndSetsPendingKey() {
        // Lua 返回 0：预扣成功——应发布 Outbox 事件、写 PENDING 结果 Key
        stubValidSession();
        stubLuaResult(0L);

        CouponTemplate template = CouponTemplate.builder()
                .id(TEMPLATE_ID).validDays(7).status("ACTIVE").build();
        when(couponTemplateMapper.selectById(TEMPLATE_ID)).thenReturn(template);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);

        SeckillResultVO result = seckillService.doSeckill(request());

        assertThat(result.getSuccess()).isTrue();

        // 验证已写入 Outbox（交由 Relay 异步投递 MQ，触发真正的 user_coupon INSERT）
        verify(outboxService, times(1)).saveToOutbox(any(), anyString(), anyString(), anyString());

        // 验证 RESULT_KEY 被写为 PENDING（供前端轮询使用）
        verify(valueOps, times(1)).set(
                eq(String.format("seckill:result:%d:%d:%d", SESSION_ID, TEMPLATE_ID, USER_ID)),
                eq("PENDING"),
                eq(86_400L),
                eq(TimeUnit.SECONDS));

        // 验证 Metrics 记录
        verify(businessMetrics).recordSeckillSuccess(TEMPLATE_ID);
    }

    // =========================================================
    // 3. querySeckillResult — 多层兜底查询
    // =========================================================

    @Test
    void querySeckillResult_dbHasRecord_returnsSuccess() {
        // DB 有记录 → 最权威，直接返回成功
        when(userCouponMapper.selectOne(any())).thenReturn(new UserCoupon());
        // DB 命中时不应查 Redis
        verifyNoInteractions(stringRedisTemplate);

        SeckillResultVO result = seckillService.querySeckillResult(SESSION_ID, TEMPLATE_ID);
        assertThat(result.getSuccess()).isTrue();
    }

    @Test
    void querySeckillResult_dbEmpty_resultKeyPending_returnsProcessing() {
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("PENDING");

        SeckillResultVO result = seckillService.querySeckillResult(SESSION_ID, TEMPLATE_ID);

        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getMessage()).contains("出券");
    }

    @Test
    void querySeckillResult_dbEmpty_resultKeySuccess_returnsSuccess() {
        // 消费者已标记 SUCCESS 但本次 DB 查询（主从延迟）暂未读到
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn("SUCCESS");

        SeckillResultVO result = seckillService.querySeckillResult(SESSION_ID, TEMPLATE_ID);
        assertThat(result.getSuccess()).isTrue();
    }

    @Test
    void querySeckillResult_resultKeyMissing_butInUserSet_returnsProcessing() {
        // RESULT_KEY TTL 已过期，退回 USER_SET_KEY 兜底判断。
        // isMember 有两个重载：isMember(K,Object)→Boolean 和 isMember(K,Object...)→Map。
        // 用 (Object) 显式转型让编译器选择单参数版本，避免 varargs 重载歧义。
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        doReturn(Boolean.TRUE).when(setOps).isMember(anyString(), (Object) any());

        SeckillResultVO result = seckillService.querySeckillResult(SESSION_ID, TEMPLATE_ID);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getMessage()).contains("处理中");
    }

    @Test
    void querySeckillResult_nothingFound_returnsNotWon() {
        when(userCouponMapper.selectOne(any())).thenReturn(null);
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get(any())).thenReturn(null);
        when(stringRedisTemplate.opsForSet()).thenReturn(setOps);
        doReturn(Boolean.FALSE).when(setOps).isMember(anyString(), (Object) any());

        SeckillResultVO result = seckillService.querySeckillResult(SESSION_ID, TEMPLATE_ID);
        assertThat(result.getSuccess()).isFalse();
        assertThat(result.getMessage()).contains("未抢到");
    }

    // =========================================================
    // 辅助方法
    // =========================================================

    private SeckillRequest request() {
        SeckillRequest req = new SeckillRequest();
        req.setSessionId(SESSION_ID);
        req.setCouponTemplateId(TEMPLATE_ID);
        return req;
    }

    private SeckillSession activeSession() {
        return SeckillSession.builder()
                .id(SESSION_ID)
                .couponTemplateId(TEMPLATE_ID)
                .sessionStatus("ACTIVE")
                .beginTime(LocalDateTime.now().minusMinutes(5))
                .endTime(LocalDateTime.now().plusMinutes(55))
                .build();
    }

    private void stubValidSession() {
        when(seckillSessionMapper.selectById(SESSION_ID)).thenReturn(activeSession());
    }

    @SuppressWarnings("unchecked")
    private void stubLuaResult(Long returnValue) {
        doReturn(returnValue).when(stringRedisTemplate).execute(any(), anyList(), any(Object.class));
    }
}
