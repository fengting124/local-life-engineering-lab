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
import com.personalprojections.locallife.server.module.seckill.dto.SeckillRequest;
import com.personalprojections.locallife.server.module.seckill.dto.SeckillResultVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.io.ClassPathResource;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scripting.support.ResourceScriptSource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.List;

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
 *    Lua 脚本返回 0 → 执行数据库写入（同步，后续升级为 MQ）
 * 4. 返回秒杀结果给用户
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
 * <h2>当前阶段简化说明</h2>
 * <p>生产级秒杀应该是「Lua 预扣 → 发 MQ → MQ 消费者写 DB」的异步架构，
 * 把 DB 写入从秒杀主链路上解耦出去。当前阶段为了简化，Lua 预扣成功后
 * 直接同步写 DB（SeckillService 里调 userCouponMapper.insert），
 * 代码注释里标记了「后续升级为 MQ」的位置和方法。
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

    /** Redis Key 模板 */
    private static final String STOCK_KEY = "seckill:stock:%d:%d";   // sessionId:templateId
    private static final String USER_SET_KEY = "seckill:user:%d:%d"; // sessionId:templateId
    // RESULT_KEY = "seckill:result:%d:%d" 预留给 MQ 异步场景：
    //   POST 接口发 MQ 后写入「PENDING」，消费者写 DB 后更新为「SUCCESS」，
    //   前端轮询 GET /seckill/result 读此 Key。当前同步写 DB，暂不需要。

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
        SeckillSession session = validateSession(sessionId, couponTemplateId);

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

        // 4. Lua 脚本返回 0：预扣成功，同步写 DB
        // 【后续升级点】这里应该改为：发 MQ 消息，由消费者异步 INSERT user_coupon
        // 改法：stringRedisTemplate 存一个 result Key 标记「等待处理」，
        //       MQ 消费成功后更新为「已完成」，「查询结果」接口读这个 Key
        createUserCoupon(userId, couponTemplateId, sessionId, session);

        // Metrics：记录秒杀成功（用于计算成功率：success / attempts）
        businessMetrics.recordSeckillSuccess(couponTemplateId);

        log.info("秒杀成功，userId: {}, sessionId: {}, couponTemplateId: {}",
                userId, sessionId, couponTemplateId);
        return SeckillResultVO.builder()
                .success(true)
                .message("抢券成功！请到「我的券包」查看")
                .build();
    }

    // =========================================================
    // 查询抢券结果（异步场景使用）
    // =========================================================

    /**
     * 查询秒杀结果（当前简化：直接查 DB，异步 MQ 场景用 Redis 缓存状态）。
     *
     * <p>生产级异步流程（升级后）：
     * <ol>
     *   <li>秒杀成功 → 发 MQ → 存 Redis result Key 为「PENDING」</li>
     *   <li>MQ 消费成功 → 更新 Redis result Key 为「SUCCESS」</li>
     *   <li>前端轮询此接口 → 读 Redis result Key → 返回当前状态</li>
     * </ol>
     * <p>当前阶段简化：直接查数据库判断是否有记录。
     *
     * @param sessionId         秒杀场次 ID
     * @param couponTemplateId  券模板 ID
     * @return 是否已抢到
     */
    public SeckillResultVO querySeckillResult(Long sessionId, Long couponTemplateId) {
        Long userId = UserContext.getUserId();

        // 查数据库：该用户是否已有这张券
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

        // 还没有记录：要么还在 MQ 处理中，要么没抢到
        // 判断 Redis 里有没有这个用户（SISMEMBER）
        String userSetKey = String.format(USER_SET_KEY, sessionId, couponTemplateId);
        Boolean inSet = stringRedisTemplate.opsForSet()
                .isMember(userSetKey, String.valueOf(userId));

        if (Boolean.TRUE.equals(inSet)) {
            // Redis 里有记录但 DB 还没写入（MQ 处理中）
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
     * 同步创建用户券记录（Lua 预扣成功后调用）。
     *
     * <p>幂等处理：捕获 DuplicateKeyException（唯一索引冲突），说明 MQ 重复消费或并发重试，
     * 直接忽略，不是真正的错误（用户已经领到了）。
     *
     * <p>【升级方向】此方法应该改为：发一条 MQ 消息，由 CouponConsumer 异步执行 INSERT。
     * 消费者里同样需要捕获 DuplicateKeyException 做幂等处理。
     *
     * @param userId           领取用户 ID
     * @param couponTemplateId 券模板 ID
     * @param sessionId        秒杀场次 ID
     * @param session          场次实体（用于计算 expire_at）
     */
    private void createUserCoupon(Long userId, Long couponTemplateId,
                                  Long sessionId, SeckillSession session) {
        // 查询券模板，获取有效天数（用于计算过期时间）
        CouponTemplate template = couponTemplateMapper.selectById(couponTemplateId);
        if (template == null) {
            log.error("券模板不存在，couponTemplateId: {}", couponTemplateId);
            throw new BizException(ErrorCode.SYS_BUSY);
        }

        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plusDays(template.getValidDays());

        UserCoupon userCoupon = UserCoupon.builder()
                .userId(userId)
                .couponTemplateId(couponTemplateId)
                .seckillSessionId(sessionId)
                .couponStatus("UNUSED")
                .receivedAt(now)
                .expireAt(expireAt)
                .build();

        try {
            userCouponMapper.insert(userCoupon);
        } catch (DuplicateKeyException e) {
            // 唯一索引冲突：说明该用户已经领过这张券（Redis Set 判重漏了或重试导致）
            // 幂等处理：直接忽略，用户已经有这张券了，不算错误
            log.warn("用户券重复创建（唯一索引冲突，幂等处理），userId: {}, couponTemplateId: {}",
                    userId, couponTemplateId);
        }
    }
}
