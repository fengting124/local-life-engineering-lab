package com.personalprojections.locallife.server.module.seckill.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import com.personalprojections.locallife.server.domain.entity.SeckillSession;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.SeckillSessionMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.module.mq.constant.MqTopics;
import com.personalprojections.locallife.server.module.mq.event.SeckillSuccessEvent;
import com.personalprojections.locallife.server.module.mq.service.OutboxService;
import com.personalprojections.locallife.server.module.seckill.dto.SeckillRequest;
import com.personalprojections.locallife.server.module.seckill.dto.SeckillResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.TimeUnit;

/**
 * 秒杀抢券 Service，是整个项目最有技术深度的模块。
 *
 * <h2>秒杀核心流程（面试必讲）</h2>
 * <pre>
 * 用户发起秒杀请求
 *     ↓
 * 1. 校验场次存在且状态为 ACTIVE，校验秒杀时间窗
 * 2. Redis Lua 脚本（原子性执行以下操作）：
 *    a. SISMEMBER seckill:user:{sessionId}:{templateId} {userId}
 *       → 已在集合中 → 返回 2（已领取，重复请求）
 *    b. GET seckill:stock:{sessionId}:{templateId}
 *       → 库存 ≤ 0 → 返回 1（库存不足）
 *    c. DECR stock Key（库存 -1）
 *       SADD user Set（记录此用户已抢到）
 *       → 返回 0（成功）
 * 3. Lua 脚本返回非 0 → 直接拒绝，不进数据库
 *    Lua 脚本返回 0 → 写本地消息表 outbox_message（与「预扣成功」语义同一事务），
 *                     由 Relay 异步投递 MQ，消费者异步 INSERT user_coupon
 * 4. 返回秒杀结果给用户（无需等待 DB 写入完成）
 * </pre>
 *
 * <h2>为什么必须用 Lua 脚本？</h2>
 * <p>如果不用 Lua，判重 + 扣库存需要多个 Redis 命令：
 * <pre>
 *   SISMEMBER → 判断是否已抢
 *   GET → 读库存
 *   DECR → 扣库存
 *   SADD → 记录用户
 * </pre>
 * <p>这 4 个命令之间存在「时间差」，在高并发下会出现竞争：
 * <ul>
 *   <li>线程 A 读到库存 = 1，线程 B 也读到库存 = 1</li>
 *   <li>两个线程都认为「有库存」，都 DECR，库存变成 -1（超卖！）</li>
 * </ul>
 * <p>Lua 脚本在 Redis 里是原子执行的（单线程模型），执行期间不会被其他命令打断，
 * 从根本上消除了竞争条件。这是 Redis 秒杀去并发的核心原理。
 *
 * <h2>异步写库架构（Outbox 复用）</h2>
 * <p>生产级秒杀应该是「Lua 预扣 → 发 MQ → MQ 消费者写 DB」的异步架构，
 * 把 DB 写入从秒杀主链路上解耦出去。本模块没有为此另起一套 MQ 机制，
 * 而是直接复用项目里已经验证过的 {@link OutboxService} 可靠消息基础设施
 * （与 {@code PaymentService} 走的是同一条路径：业务事务内写 outbox_message
 * → Relay 任务异步投递 RocketMQ → 消费者幂等消费）：
 * <pre>
 * Lua 预扣成功（原子防超卖/防重复）
 *     ↓
 * 同事务内写 outbox_message（PENDING，事务提交即保证不丢）
 *     ↓
 * OutboxService.relayMessages() 异步投递到 seckill-success-topic
 *     ↓
 * SeckillSuccessConsumer 幂等消费 → 异步 INSERT user_coupon
 * </pre>
 * <p>好处：秒杀主链路里只剩「Redis 原子操作 + 写一行 outbox_message」，
 * 响应极快；DB 写入压力被 Relay+消费者削峰填谷，不会在抢购瞬间打满连接池。
 *
 * <h2>Lua 脚本位置</h2>
 * <p>脚本文件：resources/lua/seckill.lua
 * 通过 {@link DefaultRedisScript} 加载，应用启动时编译缓存，运行时不再重新加载。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class SeckillService {

    private final CouponTemplateMapper couponTemplateMapper;
    private final SeckillSessionMapper seckillSessionMapper;
    private final UserCouponMapper userCouponMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final BusinessMetrics businessMetrics;
    private final OutboxService outboxService;

    /** Redis Key 模板 */
    private static final String STOCK_KEY = "seckill:stock:%d:%d";   // sessionId:templateId
    private static final String USER_SET_KEY = "seckill:user:%d:%d"; // sessionId:templateId

    /**
     * 秒杀结果 Key：seckill:result:{sessionId}:{templateId}:{userId}（按用户区分，
     * 同一场次/同一券模板下不同用户的结果各不相同，必须带上 userId 才能定位）。
     *
     * <p>异步写库流程中标记「领取请求」当前所处的状态，供 {@link #querySeckillResult} 轮询：
     * <ul>
     *   <li>doSeckill 预扣成功 → 写入「PENDING」</li>
     *   <li>SeckillSuccessConsumer 异步写库成功 → 更新为「SUCCESS」</li>
     * </ul>
     * TTL 与幂等消费 Key 保持一致（24 小时），覆盖 MQ 最大重试窗口。
     */
    private static final String RESULT_KEY = "seckill:result:%d:%d:%d"; // sessionId:templateId:userId

    /** 秒杀结果 Key 的取值。 */
    private static final String RESULT_PENDING = "PENDING";
    private static final String RESULT_SUCCESS = "SUCCESS";

    /** 结果 Key TTL（秒）：24 小时，与 {@code SeckillSuccessConsumer} 幂等 Key TTL 保持一致。 */
    private static final long RESULT_KEY_TTL_SECONDS = 86_400L;

    /**
     * Lua 脚本对象，应用启动时加载，运行时复用。
     * 脚本返回值：0=成功，1=库存不足，2=已领取过
     */
    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        // 静态初始化块：类加载时执行，只执行一次
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        // 加载 resources/lua/seckill.lua 文件
        SECKILL_SCRIPT.setScriptSource(
                new ResourceScriptSource(new ClassPathResource("lua/seckill.lua"))
        );
        // 声明返回值类型（Redis 返回的 Long）
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    // =========================================================
    // 秒杀抢券主流程
    // =========================================================

    /**
     * 参与秒杀抢券（核心接口）。
     *
     * <p>执行步骤（详见类注释）：
     * <ol>
     *   <li>校验场次合法性（存在、ACTIVE、在时间窗内）</li>
     *   <li>执行 Lua 脚本（原子性预扣库存 + 判重）</li>
     *   <li>预扣成功后同步写 DB（后续升级为 MQ 异步）</li>
     * </ol>
     *
     * @param request 秒杀请求（sessionId + couponTemplateId）
     * @return 秒杀结果 VO
     */
    @Transactional(rollbackFor = Exception.class)
    public SeckillResultVO doSeckill(SeckillRequest request) {
        Long userId = UserContext.getUserId();
        Long sessionId = request.getSessionId();
        Long couponTemplateId = request.getCouponTemplateId();

        // 1. 校验场次（存在性 + 状态 + 时间窗）
        // 返回值此前用于同步创建 user_coupon 时计算 expire_at；
        // 现已改为发布事件异步写库，expire_at 改由 validDays 在事件中冗余传递（见 publishSeckillSuccessEvent），
        // 此处只需校验场次合法性，不再需要持有 session 对象
        validateSession(sessionId, couponTemplateId);

        // Metrics：记录一次秒杀尝试（无论成功与否，帮助分析热度）
        // Metrics：使用 couponTemplateId 区分不同秒杀活动（SeckillSession 无 shopId 字段）
        businessMetrics.recordSeckillAttempt(couponTemplateId);

        // 2. 执行 Lua 脚本（关键：原子性防超卖 + 防重复）
        String stockKey = String.format(STOCK_KEY, sessionId, couponTemplateId);
        String userSetKey = String.format(USER_SET_KEY, sessionId, couponTemplateId);
        List<String> keys = Arrays.asList(stockKey, userSetKey);

        Long result = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                keys,
                String.valueOf(userId)  // ARGV[1]：当前用户 ID
        );

        // 3. 判断 Lua 脚本返回值
        if (result == null || result == 1L) {
            // 库存不足（返回 1）
            log.info("秒杀失败：库存不足，userId: {}, sessionId: {}", userId, sessionId);
            businessMetrics.recordSeckillFailure(couponTemplateId, "COUPON_STOCK_EXHAUSTED");
            throw new BizException(ErrorCode.COUPON_STOCK_EXHAUSTED);
        }
        if (result == 2L) {
            // 已领取过（返回 2）
            log.info("秒杀失败：重复领取，userId: {}, sessionId: {}", userId, sessionId);
            businessMetrics.recordSeckillFailure(couponTemplateId, "COUPON_ALREADY_RECEIVED");
            throw new BizException(ErrorCode.COUPON_ALREADY_RECEIVED);
        }

        // 4. Lua 脚本返回 0：预扣成功（防超卖/防重复已在 Redis 层原子保证）
        //    发布「秒杀成功事件」到 Outbox（与本方法同一事务），交由 Relay 异步投递 MQ，
        //    SeckillSuccessConsumer 异步完成真正的 user_coupon INSERT —— DB 写入
        //    从秒杀主链路上彻底解耦，响应只剩 Redis 操作 + 一行 outbox_message 写入
        publishSeckillSuccessEvent(userId, couponTemplateId, sessionId);

        // 标记「领取请求」当前状态为 PENDING：消费者写库成功后会更新为 SUCCESS，
        // 前端轮询 GET /seckill/result 据此判断是否已真正出券
        String resultKey = String.format(RESULT_KEY, sessionId, couponTemplateId, userId);
        stringRedisTemplate.opsForValue().set(resultKey, RESULT_PENDING, RESULT_KEY_TTL_SECONDS, TimeUnit.SECONDS);

        // Metrics：记录秒杀成功（用于计算成功率：success / attempts；
        // 此处「成功」指 Redis 预扣成功——用户已经锁定名额，DB 落库是异步保证，不影响这个口径）
        businessMetrics.recordSeckillSuccess(couponTemplateId);

        log.info("秒杀预扣成功（已发布异步写库事件），userId: {}, sessionId: {}, couponTemplateId: {}",
                userId, sessionId, couponTemplateId);
        return SeckillResultVO.builder()
                .success(true)
                .message("抢券成功！正在为您出券，请稍后到「我的券包」查看")
                .build();
    }

    // =========================================================
    // 查询抢券结果（异步场景使用）
    // =========================================================

    /**
     * 查询秒杀结果（异步写库架构下，前端据此轮询「是否已真正出券」）。
     *
     * <p>判断顺序（由「最终一致」到「过程态」分层兜底）：
     * <ol>
     *   <li>查数据库 user_coupon：有记录 → 已最终落库，直接返回成功（最权威依据）</li>
     *   <li>查 Redis RESULT_KEY：
     *     <ul>
     *       <li>PENDING → 预扣已成功，消费者正在异步写库，让前端稍后重试</li>
     *       <li>SUCCESS → 消费者已标记完成，但 DB 查询因主从延迟暂未读到（极少见），按成功返回</li>
     *     </ul>
     *   </li>
     *   <li>RESULT_KEY 不存在（如 TTL 过期）→ 退回判断 USER_SET_KEY（SISMEMBER）兜底，
     *     仍命中说明预扣成功但状态 Key 已过期，提示「处理中」；否则视为未抢到</li>
     * </ol>
     *
     * @param sessionId         秒杀场次 ID
     * @param couponTemplateId  券模板 ID
     * @return 是否已抢到
     */
    public SeckillResultVO querySeckillResult(Long sessionId, Long couponTemplateId) {
        Long userId = UserContext.getUserId();

        // 1. 查数据库：该用户是否已有这张券（最终一致的权威依据）
        UserCoupon coupon = userCouponMapper.selectOne(
                new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, userId)
                        .eq(UserCoupon::getCouponTemplateId, couponTemplateId)
                        .eq(UserCoupon::getSeckillSessionId, sessionId)
        );

        if (coupon != null) {
            return SeckillResultVO.builder()
                    .success(true)
                    .message("已抢到！请到「我的券包」查看")
                    .build();
        }

        // 2. DB 还没有记录：读 Redis RESULT_KEY 判断当前处于哪个过程态
        String resultKey = String.format(RESULT_KEY, sessionId, couponTemplateId, userId);
        String resultStatus = stringRedisTemplate.opsForValue().get(resultKey);

        if (RESULT_PENDING.equals(resultStatus)) {
            // 预扣已成功，消费者正在异步写库（Outbox Relay 投递 / MQ 消费中）
            return SeckillResultVO.builder()
                    .success(false)
                    .message("抢券成功！正在为您出券，请稍后刷新查看")
                    .build();
        }
        if (RESULT_SUCCESS.equals(resultStatus)) {
            // 消费者已标记完成，但本次 DB 查询暂未读到（主从延迟等），按成功处理
            return SeckillResultVO.builder()
                    .success(true)
                    .message("已抢到！请到「我的券包」查看")
                    .build();
        }

        // 3. RESULT_KEY 不存在（如 TTL 过期）：退回判断 USER_SET_KEY 兜底
        String userSetKey = String.format(USER_SET_KEY, sessionId, couponTemplateId);
        Boolean inSet = stringRedisTemplate.opsForSet()
                .isMember(userSetKey, String.valueOf(userId));

        if (Boolean.TRUE.equals(inSet)) {
            // Redis 判重 Set 里有记录，但 RESULT_KEY 已过期且 DB 还没写入（理论上极少出现）
            return SeckillResultVO.builder()
                    .success(false)
                    .message("正在处理中，请稍后刷新")
                    .build();
        }

        return SeckillResultVO.builder()
                .success(false)
                .message("未抢到，本次活动已结束或库存不足")
                .build();
    }

    // =========================================================
    // 用户券包查询
    // =========================================================

    /**
     * 查询当前用户的所有优惠券（我的券包）。
     *
     * @return 用户持有的所有券列表
     */
    public List<UserCoupon> listMyCoupons() {
        Long userId = UserContext.getUserId();
        return userCouponMapper.selectList(
                new LambdaQueryWrapper<UserCoupon>()
                        .eq(UserCoupon::getUserId, userId)
                        .orderByDesc(UserCoupon::getCreatedAt)
        );
    }

    // =========================================================
    // 私有辅助方法
    // =========================================================

    /**
     * 校验秒杀场次合法性。
     *
     * <p>校验顺序：
     * <ol>
     *   <li>场次存在</li>
     *   <li>场次关联的 couponTemplateId 与请求一致（防止参数篡改）</li>
     *   <li>场次状态为 ACTIVE</li>
     *   <li>当前时间在 beginTime ~ endTime 之间</li>
     * </ol>
     *
     * @param sessionId        秒杀场次 ID
     * @param couponTemplateId 券模板 ID
     * @return 校验通过的场次实体
     */
    private SeckillSession validateSession(Long sessionId, Long couponTemplateId) {
        SeckillSession session = seckillSessionMapper.selectById(sessionId);

        if (session == null || !couponTemplateId.equals(session.getCouponTemplateId())) {
            // 场次不存在，或请求的 couponTemplateId 与场次不符（参数篡改）
            throw new BizException(ErrorCode.SECKILL_NOT_STARTED);
        }

        LocalDateTime now = LocalDateTime.now();

        // 检查时间窗（用 beginTime / endTime 做精确判断，不依赖 session_status 字段）
        // 原因：status 字段由定时任务更新，可能有几秒延迟
        if (now.isBefore(session.getBeginTime())) {
            throw new BizException(ErrorCode.SECKILL_NOT_STARTED);
        }
        if (now.isAfter(session.getEndTime())) {
            throw new BizException(ErrorCode.SECKILL_EXPIRED);
        }

        // 状态也检查一下（双保险，防止定时任务把已取消的场次漏更新）
        if (!"ACTIVE".equals(session.getSessionStatus())) {
            if ("PENDING".equals(session.getSessionStatus())) {
                throw new BizException(ErrorCode.SECKILL_NOT_STARTED);
            }
            throw new BizException(ErrorCode.SECKILL_EXPIRED);
        }

        return session;
    }

    /**
     * 发布「秒杀成功事件」到本地消息表（Lua 预扣成功后、同一事务内调用）。
     *
     * <p>复用 {@link OutboxService}：与 outbox_message 同一事务写入，保证
     * 「Redis 预扣成功」与「事件落表」原子绑定——事务提交后 Relay 任务负责
     * 异步投递到 RocketMQ，真正的 user_coupon INSERT 由
     * {@link com.personalprojections.locallife.server.module.mq.consumer.SeckillSuccessConsumer}
     * 异步幂等执行（即使 MQ 当时不可用，消息也不会丢，Relay 会在恢复后重试）。
     *
     * <p>事件中冗余了 validDays（来自 coupon_template），消费者据此直接计算
     * expire_at，无需在异步链路上再回查券模板表。
     *
     * @param userId           领取用户 ID
     * @param couponTemplateId 券模板 ID
     * @param sessionId        秒杀场次 ID
     */
    private void publishSeckillSuccessEvent(Long userId, Long couponTemplateId, Long sessionId) {
        // 查询券模板，获取有效天数（事件冗余字段，供消费者直接使用，无需异步链路回查）
        CouponTemplate template = couponTemplateMapper.selectById(couponTemplateId);
        if (template == null) {
            log.error("券模板不存在，couponTemplateId: {}", couponTemplateId);
            throw new BizException(ErrorCode.SYS_BUSY);
        }

        LocalDateTime now = LocalDateTime.now();
        // eventId 全局唯一，格式：{sessionId}_{userId}_seckill
        // 注：couponTemplateId 不参与拼接——validateSession 已校验「一个场次唯一对应一个券模板」，
        // 所以 (sessionId, userId) 已足以唯一标识一次领取，无需再加 couponTemplateId。
        // 这样做还有现实考量：三个雪花 ID（各最长 19 位）全部拼接可能超过
        // outbox_message.event_id 的 VARCHAR(64) 长度上限，去掉冗余字段更安全。
        // 与 outbox_message.uk_event_id 唯一索引配合，杜绝重复落表
        String eventId = sessionId + "_" + userId + "_seckill";

        SeckillSuccessEvent event = SeckillSuccessEvent.builder()
                .eventId(eventId)
                .sessionId(sessionId)
                .couponTemplateId(couponTemplateId)
                .userId(userId)
                .validDays(template.getValidDays())
                .succeededAt(now)
                .eventAt(now)
                .build();

        outboxService.saveToOutbox(event, eventId, MqTopics.SECKILL_SUCCESS_TOPIC, MqTopics.TAG_SECKILL_SUCCESS);
        log.debug("[Seckill] 已写入 Outbox，等待异步落库: eventId={}, userId={}, couponTemplateId={}",
                eventId, userId, couponTemplateId);
    }
}
