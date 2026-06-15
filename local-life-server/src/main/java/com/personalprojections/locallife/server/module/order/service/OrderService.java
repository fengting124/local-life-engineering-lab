package com.personalprojections.locallife.server.module.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.CouponTemplate;
import com.personalprojections.locallife.server.domain.entity.OrderInfo;
import com.personalprojections.locallife.server.domain.entity.Shop;
import com.personalprojections.locallife.server.domain.entity.UserCoupon;
import com.personalprojections.locallife.server.domain.mapper.CouponTemplateMapper;
import com.personalprojections.locallife.server.domain.mapper.OrderInfoMapper;
import com.personalprojections.locallife.server.domain.mapper.ShopMapper;
import com.personalprojections.locallife.server.domain.mapper.UserCouponMapper;
import com.personalprojections.locallife.server.module.mq.constant.MqTopics;
import com.personalprojections.locallife.server.module.mq.constant.RocketMqDelayLevel;
import com.personalprojections.locallife.server.module.mq.event.OrderCloseDelayMessage;
import com.personalprojections.locallife.server.module.order.dto.CreateOrderRequest;
import com.personalprojections.locallife.server.module.order.dto.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.messaging.support.MessageBuilder;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/**
 * 订单 Service，负责订单创建、查询、取消和过期关单。
 *
 * <h2>订单状态机</h2>
 * <pre>
 *   [下单]
 *      ↓ createOrder()
 *   WAIT_PAY ──────────────────────→ CANCELLED
 *      │          ↑取消(用户主动)         ↑
 *      │          └ cancelOrder()    expire_at 到期，两条链路赛跑触发：
 *      │                             ① OrderCloseConsumer（MQ 延时消息，秒级，主链路）
 *      │                             ② closeExpiredOrders()（定时任务，分钟级，兜底）
 *      ↓ 支付回调成功（PaymentService 调用）
 *    PAID
 *      │
 *      ↓（预留，下一迭代实现）
 *   COMPLETED
 * </pre>
 *
 * <h2>金额计算逻辑</h2>
 * <p>三个金额字段均由服务端计算，客户端不传金额：
 * <pre>
 *   originalAmount = shop.price（门店价格）
 *   couponDiscount = couponTemplate.discountValue（现金券时为分，如 2000 = 减20元）
 *                    0（未使用券）
 *   orderAmount    = originalAmount - couponDiscount
 * </pre>
 *
 * <h2>下单时的优惠券核销</h2>
 * <p>使用券下单时，{@code createOrder} 内直接核销券（UNUSED → USED），
 * 与 INSERT order_info 在同一个事务（@Transactional）中。
 * 如果后续创建订单失败，事务回滚，券的状态也回滚到 UNUSED，
 * 不会出现「券被核销但订单没创建成功」的悬空状态。
 *
 * <h2>延迟关单方案：MQ 延时消息（主链路）+ 定时任务（兜底）</h2>
 * <p>这一节记录的是一次「从 TODO 到落地」的真实架构升级——本类曾经只有
 * 「每分钟定时轮询」一种关单机制，类注释里留了一句「升级方向：RocketMQ 延时消息」。
 * 现在的实现（参考了 siam-cloud 开源外卖平台 {@code OrderConsumer}/{@code closeOverdueOrder}
 * 的「下单发延时消息 + 消费时复检状态再关单」两段式经典模式）：
 * <pre>
 *   下单成功（事务提交后）
 *        │ registerOrderCloseDelayMessageAfterCommit()
 *        ↓
 *   投递 30 分钟延时消息（best-effort，syncSend + delayLevel=16）
 *        │
 *        ↓ Broker 在 30 分钟后精确投递（秒级精度）
 *   OrderCloseConsumer.onMessage()
 *        │ 重新查库，复检「是否仍待支付且已过期」
 *        ↓
 *   handleOrderCloseDelayMessage() → closeOrderIfExpired()（与兜底任务共用）
 * </pre>
 * <p>关键设计取舍（详见各方法 Javadoc 的展开论证）：
 * <ul>
 *   <li><b>为什么不用 Outbox</b>：见 {@link com.personalprojections.locallife.server.module.mq.constant.MqTopics#TAG_ORDER_CLOSE_NOTIFY}——
 *       这条消息允许丢、允许 best-effort，Outbox 的可靠投递保障在此处是过度设计</li>
 *   <li><b>为什么不在事务内直接发送</b>：见 {@link #registerOrderCloseDelayMessageAfterCommit}——
 *       afterCommit 钩子从根上消除「孤儿消息」与「消费者查不到订单」的竞态</li>
 *   <li><b>为什么消费者要重新查库复检</b>：见 {@link #handleOrderCloseDelayMessage}——
 *       30 分钟窗口内订单状态可能已变化，消息只是「触发器」不是「事实」</li>
 *   <li><b>为什么定时任务还要保留（只是降频+改名为兜底）</b>：见 {@link #closeExpiredOrders}——
 *       双保险让 MQ 链路可以放心 best-effort，互为安全网</li>
 * </ul>
 * <p>关单精度：从「最多延迟 1 分钟（轮询间隔）」提升到「秒级（Broker 精确投递）」，
 * 且 99% 的订单完全不需要再被轮询任务扫描，显著降低 DB 扫描压力。
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderService {

    // =========================================================
    // 依赖注入
    // =========================================================

    private final OrderInfoMapper orderInfoMapper;
    private final ShopMapper shopMapper;
    private final UserCouponMapper userCouponMapper;
    private final CouponTemplateMapper couponTemplateMapper;
    private final StringRedisTemplate stringRedisTemplate;
    private final BusinessMetrics businessMetrics;
    private final RocketMQTemplate rocketMQTemplate;
    private final ObjectMapper objectMapper;

    // =========================================================
    // Redis Key 常量
    // =========================================================

    /**
     * 下单幂等 Key：idempotent:order:{idempotencyKey}。
     * TTL 5 分钟，防止用户快速双击「确认下单」产生重复订单。
     * value 存储已创建的 orderNo，第二次请求时直接返回原订单号（幂等）。
     */
    private static final String ORDER_IDEMPOTENT_KEY = "idempotent:order:%s";

    /** 幂等 Key 的 TTL（分钟），5 分钟内重复请求视为重复。 */
    private static final long IDEMPOTENT_TTL_MINUTES = 5;

    /** 订单超时时间（分钟），30 分钟未支付自动关闭。 */
    private static final int ORDER_EXPIRE_MINUTES = 30;

    // =========================================================
    // 创建订单
    // =========================================================

    /**
     * 创建订单（POST /api/v1/orders）。
     *
     * <p>完整流程：
     * <ol>
     *   <li>幂等检查：如果携带了 X-Idempotency-Key，先查 Redis 是否已创建过</li>
     *   <li>校验门店：门店存在且 status = ONLINE</li>
     *   <li>校验优惠券（如有）：券存在 + 属于当前用户 + 状态 UNUSED + 未过期</li>
     *   <li>计算金额：originalAmount、couponDiscount、orderAmount</li>
     *   <li>核销券（如有）：UNUSED → USED，与创建订单在同一事务</li>
     *   <li>INSERT order_info</li>
     *   <li>写幂等 Key（如有）</li>
     * </ol>
     *
     * @param request        下单请求体
     * @param idempotencyKey 幂等 Key（从 Header X-Idempotency-Key 读取，可为 null）
     * @return 创建好的订单 VO
     */
    @Transactional(rollbackFor = Exception.class)
    public OrderVO createOrder(CreateOrderRequest request, String idempotencyKey) {
        Long userId = UserContext.getUserId();

        // ---- Step 1: 幂等检查 ----
        // 如果前端传了幂等 Key，先查 Redis 是否已处理过（防止双击下单）
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            String cachedOrderNo = stringRedisTemplate.opsForValue()
                    .get(String.format(ORDER_IDEMPOTENT_KEY, idempotencyKey));
            if (cachedOrderNo != null) {
                // 已存在：直接查数据库返回原订单（幂等，不重复创建）
                log.info("[Order] 幂等命中，orderNo={}, idempotencyKey={}", cachedOrderNo, idempotencyKey);
                OrderInfo existing = orderInfoMapper.selectOne(
                        new LambdaQueryWrapper<OrderInfo>()
                                .eq(OrderInfo::getOrderNo, cachedOrderNo)
                                .eq(OrderInfo::getUserId, userId));
                if (existing != null) {
                    return toVO(existing, null);
                }
            }
        }

        // ---- Step 2: 校验门店 ----
        // 门店必须存在且处于 ONLINE 状态才能下单
        Shop shop = shopMapper.selectById(request.getShopId());
        if (shop == null || shop.getDeleted() == 1) {
            throw new BizException(ErrorCode.SHOP_NOT_FOUND);
        }
        if (!"ONLINE".equals(shop.getStatus())) {
            throw new BizException(ErrorCode.SHOP_NOT_ONLINE);
        }

        // ---- Step 3: 校验优惠券（可选）----
        // 如果用户选择了用券，校验券的合法性
        UserCoupon userCoupon = null;
        CouponTemplate couponTemplate = null;
        if (request.getUserCouponId() != null) {
            // 3a. 查券是否存在且属于当前用户
            userCoupon = userCouponMapper.selectOne(
                    new LambdaQueryWrapper<UserCoupon>()
                            .eq(UserCoupon::getId, request.getUserCouponId())
                            .eq(UserCoupon::getUserId, userId));
            if (userCoupon == null) {
                // 券不存在或不属于当前用户（防枚举攻击：统一返回「券不可用」）
                throw new BizException(ErrorCode.ORDER_COUPON_INVALID);
            }
            // 3b. 券必须是 UNUSED 状态
            if (!"UNUSED".equals(userCoupon.getCouponStatus())) {
                throw new BizException(ErrorCode.ORDER_COUPON_INVALID);
            }
            // 3c. 券必须未过期
            if (userCoupon.getExpireAt() != null
                    && userCoupon.getExpireAt().isBefore(LocalDateTime.now())) {
                throw new BizException(ErrorCode.ORDER_COUPON_INVALID);
            }
            // 3d. 查券模板，获取折扣信息
            couponTemplate = couponTemplateMapper.selectById(userCoupon.getCouponTemplateId());
            if (couponTemplate == null || !"ACTIVE".equals(couponTemplate.getStatus())) {
                throw new BizException(ErrorCode.ORDER_COUPON_INVALID);
            }
            // 3e. 校验最低使用门槛（金额单位：分，shop.price 已经是分）
            int shopPriceInFen = shop.getPrice() == null ? 0 : shop.getPrice();
            if (shopPriceInFen < couponTemplate.getMinOrderAmount()) {
                throw new BizException(ErrorCode.ORDER_COUPON_BELOW_MIN_AMOUNT);
            }
        }

        // ---- Step 4: 计算金额 ----
        // 金额全部以「分」为单位，shop.price 直接就是分（INT UNSIGNED）
        int originalAmount = shop.getPrice() == null ? 0 : shop.getPrice();
        int couponDiscount = 0;
        if (couponTemplate != null && "CASH".equals(couponTemplate.getDiscountType())) {
            // 现金抵扣券：直接用 discountValue（单位：分）
            couponDiscount = couponTemplate.getDiscountValue();
        } else if (couponTemplate != null && "PERCENT".equals(couponTemplate.getDiscountType())) {
            // 折扣券：originalAmount × (1 - discountValue/100)，取整（向下）
            // 例：100元 × (1 - 80/100) = 20元折扣
            couponDiscount = originalAmount - (int) (originalAmount * couponTemplate.getDiscountValue() / 100.0);
        }
        // 确保实付金额不为负（防止折扣超过原价的异常数据）
        int orderAmount = Math.max(originalAmount - couponDiscount, 0);

        // ---- Step 5: 核销优惠券（与 INSERT 在同一事务）----
        // 先核销券，再创建订单。事务保证：若后续操作失败，券状态回滚到 UNUSED
        if (userCoupon != null) {
            LocalDateTime now = LocalDateTime.now();
            int updated = userCouponMapper.update(null,
                    new LambdaUpdateWrapper<UserCoupon>()
                            .eq(UserCoupon::getId, userCoupon.getId())
                            .eq(UserCoupon::getCouponStatus, "UNUSED") // 并发保护：只核销 UNUSED 状态的券
                            .set(UserCoupon::getCouponStatus, "USED")
                            .set(UserCoupon::getUsedAt, now));
            if (updated == 0) {
                // 并发下另一个请求已经核销了这张券
                throw new BizException(ErrorCode.ORDER_COUPON_INVALID);
            }
        }

        // ---- Step 6: 生成订单号 ----
        // orderNo 是对外展示的业务单号，用雪花 ID 字符串形式
        // MyBatis-Plus @TableId(ASSIGN_ID) 会自动填充 id（雪花 Long），
        // 我们用它来生成 orderNo（转成字符串，语义上是"订单流水号"）
        // 注意：orderNo 和 id 是不同的，id 不对外暴露
        LocalDateTime now = LocalDateTime.now();
        LocalDateTime expireAt = now.plusMinutes(ORDER_EXPIRE_MINUTES);

        OrderInfo order = OrderInfo.builder()
                .userId(userId)
                .shopId(shop.getId())
                .couponTemplateId(couponTemplate != null ? couponTemplate.getId() : null)
                .userCouponId(userCoupon != null ? userCoupon.getId() : null)
                .originalAmount(originalAmount)
                .couponDiscount(couponDiscount)
                .orderAmount(orderAmount)
                .orderStatus("WAIT_PAY")
                .remark(request.getRemark() != null ? request.getRemark() : "")
                .expireAt(expireAt)
                .build();

        // INSERT，@TableId(ASSIGN_ID) 自动生成雪花 ID 填到 order.id
        orderInfoMapper.insert(order);

        // 用雪花 ID 作为订单号（toString），全局唯一
        // 生产级实践：可以加前缀+日期，如 "ORD20260528" + id，更可读
        String orderNo = String.valueOf(order.getId());
        orderInfoMapper.update(null,
                new LambdaUpdateWrapper<OrderInfo>()
                        .eq(OrderInfo::getId, order.getId())
                        .set(OrderInfo::getOrderNo, orderNo));
        order.setOrderNo(orderNo);

        // ---- Step 7: 注册「事务提交后」投递订单关闭延时消息的回调 ----
        // 见 registerOrderCloseDelayMessageAfterCommit 的 Javadoc：
        // 这是「事务内发 MQ 消息」经典问题（消息发出但事务回滚 → 孤儿消息；
        // 或消费者在事务提交前消费 → 查不到订单）的标准解法之一。
        registerOrderCloseDelayMessageAfterCommit(order.getId(), userId);

        // ---- Step 8: 写幂等 Key ----
        if (idempotencyKey != null && !idempotencyKey.isBlank()) {
            stringRedisTemplate.opsForValue().set(
                    String.format(ORDER_IDEMPOTENT_KEY, idempotencyKey),
                    orderNo,
                    IDEMPOTENT_TTL_MINUTES, TimeUnit.MINUTES);
        }

        log.info("[Order] 创建订单成功, userId={}, orderId={}, orderNo={}, orderAmount={}分",
                userId, order.getId(), orderNo, orderAmount);

        // Metrics：记录订单创建成功（按门店统计下单量）
        businessMetrics.recordOrderCreated(shop.getId());

        return toVO(order, shop.getShopName());
    }

    // =========================================================
    // 订单关闭延时消息（下单时投递，详见类注释「延迟关单方案」）
    // =========================================================

    /**
     * 注册「事务提交后」投递订单关闭延时消息的回调。
     *
     * <h2>解决的经典问题：事务内发 MQ 消息</h2>
     * <p>如果在 {@code @Transactional} 方法内部直接调用
     * {@code rocketMQTemplate.syncSend(...)}，会面临两难：
     * <ul>
     *   <li>消息发送成功，但事务后续步骤抛异常回滚 → DB 里没有这个订单，
     *       消息却已经发出去了（孤儿消息）</li>
     *   <li>更隐蔽的问题：即使事务最终提交成功，由于 MQ 的投递速度可能快于本地事务提交
     *       （网络抖动、GC 暂停等），{@code OrderCloseConsumer} 拿到消息去查订单时，
     *       事务可能还没提交——查到的是"不存在"，从而漏判</li>
     * </ul>
     * <p>标准解法：用 {@link TransactionSynchronizationManager} 注册一个
     * {@code afterCommit} 回调，把发送动作推迟到事务<b>确定提交成功之后</b>再执行——
     * 这样消息可见时，DB 里的订单数据必然已经可查，从根上消除上述竞态。
     *
     * <h2>与 Outbox 模式的取舍（面试常问：为什么这里不直接用现成的 Outbox？）</h2>
     * <p>Outbox 模式（落库 + Relay 轮询投递）同样能解决"事务内发消息"的问题，
     * 而且可靠性更强（消息落了库，即使 MQ 长时间不可用也不会丢）。
     * 但它的代价是引入"轮询延迟"（本项目 Relay 任务每 10 秒扫描一次）——
     * 对于"30 分钟后才需要消费"的延时消息而言，这点轮询延迟完全可以忽略，
     * 用 Outbox 纯属杀鸡用牛刀，反而多了一次 DB 写入和一条状态流转链路。
     * {@code afterCommit} 回调则是"刚好够用"的轻量级方案：
     * 不持久化、不轮询，事务一提交立刻同步发送，足够应付"有兜底任务兜底、
     * 偶尔丢一条也无伤大雅"的场景。详见 {@link MqTopics#TAG_ORDER_CLOSE_NOTIFY}。
     *
     * @param orderId 订单 ID
     * @param userId  用户 ID（ShardingSphere 分片键）
     */
    private void registerOrderCloseDelayMessageAfterCommit(Long orderId, Long userId) {
        TransactionSynchronizationManager.registerSynchronization(new TransactionSynchronization() {
            @Override
            public void afterCommit() {
                publishOrderCloseDelayMessage(orderId, userId);
            }
        });
    }

    /**
     * 同步投递订单关闭延时消息（best-effort，发送失败不抛异常）。
     *
     * <h2>为什么是 best-effort（失败只记日志，不重试不报错）</h2>
     * <p>这条消息只是「让关单更快发生」的优化路径，不是「关单能否发生」的必要条件——
     * {@code closeExpiredOrders()} 定时任务始终兜底扫描过期订单。
     * 即使 Broker 暂时不可用导致发送失败，订单也只是退化为「等定时任务扫到」，
     * 不会出现"订单永远不关闭"的数据问题。在这个前提下：
     * <ul>
     *   <li>不值得为了这条消息引入重试机制（重试在事务外，状态不可控，反而复杂）</li>
     *   <li>不应该让 MQ 的临时故障影响下单这个核心链路的成功率</li>
     * </ul>
     * <p>这与 {@code OutboxService} 对支付成功 / 秒杀成功事件的「至少一次投递 + 指数退避重试」
     * 形成鲜明对比——体现的是「可靠性投入应该和事件的重要程度匹配」，
     * 不是所有消息都值得上最高等级的可靠性保障。
     *
     * @param orderId 订单 ID
     * @param userId  用户 ID（ShardingSphere 分片键，写入消息体供消费者精准路由查询）
     */
    private void publishOrderCloseDelayMessage(Long orderId, Long userId) {
        try {
            OrderCloseDelayMessage payload = OrderCloseDelayMessage.builder()
                    .orderId(orderId)
                    .userId(userId)
                    .build();
            String body = objectMapper.writeValueAsString(payload);
            String destination = MqTopics.ORDER_CLOSE_TOPIC + ":" + MqTopics.TAG_ORDER_CLOSE_NOTIFY;

            // 关键 API：syncSend(destination, message, timeout, delayLevel)
            // delayLevel 见 RocketMqDelayLevel 类注释：开源版 RocketMQ 只能传「档位序号」，
            // 不能传任意秒数；ORDER_CLOSE_DELAY_LEVEL = 16 对应固定的 30 分钟档位
            rocketMQTemplate.syncSend(destination,
                    MessageBuilder.withPayload(body).build(),
                    3000,
                    RocketMqDelayLevel.ORDER_CLOSE_DELAY_LEVEL);

            log.debug("[Order] 已投递关单延时消息: orderId={}, delayLevel={}（30分钟后到达）",
                    orderId, RocketMqDelayLevel.ORDER_CLOSE_DELAY_LEVEL);
        } catch (Exception e) {
            // best-effort：发送失败仅记录日志，不影响下单主流程，由定时任务兜底
            log.warn("[Order] 关单延时消息投递失败（不影响下单，定时任务会兜底关闭）: orderId={}, error={}",
                    orderId, e.getMessage());
        }
    }

    // =========================================================
    // 查询订单
    // =========================================================

    /**
     * 查询当前用户的订单列表（GET /api/v1/orders）。
     *
     * <p>按创建时间倒序，最新的订单排在前面。
     * 简化版：不分页，直接返回全部（生产级应该分页）。
     *
     * @return 订单列表（按创建时间倒序）
     */
    public List<OrderVO> listMyOrders() {
        Long userId = UserContext.getUserId();
        List<OrderInfo> orders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getUserId, userId)
                        .orderByDesc(OrderInfo::getCreatedAt));

        // N+1 优化：批量查门店名称
        // （此处简化：不批量查店名，避免依赖过重；生产级可以先 selectBatchIds(shopIds)）
        return orders.stream()
                .map(o -> toVO(o, null))
                .collect(Collectors.toList());
    }

    /**
     * 查询单个订单详情（GET /api/v1/orders/{orderId}）。
     *
     * <p>只允许查看自己的订单（userId 匹配），不允许查看他人订单。
     * 不区分「不存在」和「无权限」，统一返回 ORDER_NOT_FOUND，防止信息泄露。
     *
     * @param orderId 订单 ID
     * @return 订单 VO
     */
    public OrderVO getOrderDetail(Long orderId) {
        Long userId = UserContext.getUserId();
        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getId, orderId)
                        .eq(OrderInfo::getUserId, userId)); // 只查属于自己的订单
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }
        // 查门店名称（填充 VO 的 shopName 字段）
        Shop shop = shopMapper.selectById(order.getShopId());
        String shopName = shop != null ? shop.getShopName() : null;
        return toVO(order, shopName);
    }

    // =========================================================
    // 取消订单
    // =========================================================

    /**
     * 用户主动取消订单（DELETE /api/v1/orders/{orderId}）。
     *
     * <p>只有 WAIT_PAY 状态的订单可以被取消，已支付不可取消。
     * 如果取消时使用了优惠券，需要回退券（USED → UNUSED）。
     *
     * <p>并发安全：
     * {@code updateStatusFromWaitPay} 内部 WHERE status='WAIT_PAY'，
     * 并发下只有一个请求能成功，其余返回 ORDER_STATUS_ILLEGAL。
     *
     * @param orderId 要取消的订单 ID
     */
    @Transactional(rollbackFor = Exception.class)
    public void cancelOrder(Long orderId) {
        Long userId = UserContext.getUserId();

        // 先查订单，确认存在且属于当前用户
        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getId, orderId)
                        .eq(OrderInfo::getUserId, userId));
        if (order == null) {
            throw new BizException(ErrorCode.ORDER_NOT_FOUND);
        }

        // 原子性更新：WHERE status='WAIT_PAY'，防止并发双重取消
        // 传入 userId 让 ShardingSphere 精准路由到目标分片，避免广播
        int affected = orderInfoMapper.updateStatusFromWaitPay(orderId, userId, "CANCELLED");
        if (affected == 0) {
            // 状态不是 WAIT_PAY（可能已被支付、已被关闭），不允许取消
            throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
        }

        // 如果使用了优惠券，回退券的状态（USED → UNUSED）
        // 这样用户可以在下次下单时重新使用这张券
        if (order.getUserCouponId() != null) {
            userCouponMapper.update(null,
                    new LambdaUpdateWrapper<UserCoupon>()
                            .eq(UserCoupon::getId, order.getUserCouponId())
                            .eq(UserCoupon::getCouponStatus, "USED") // 只回退 USED 状态的券
                            .set(UserCoupon::getCouponStatus, "UNUSED")
                            .set(UserCoupon::getUsedAt, (Object) null));
        }

        log.info("[Order] 取消订单, userId={}, orderId={}", userId, orderId);
    }

    // =========================================================
    // 延迟关单：MQ 延时消息（主链路）+ 定时任务（兜底）双保险
    // =========================================================

    /**
     * 批量关闭过期未支付订单（定时任务，每 5 分钟执行一次，作为「兜底」）。
     *
     * <h2>定位变化：从「主链路」降级为「安全网」</h2>
     * <p>本方法曾经是关单的唯一触发途径（每分钟扫描一次，1 分钟级精度）。
     * 现在 {@code createOrder()} 下单时会投递 30 分钟延时消息，由
     * {@code OrderCloseConsumer} 在到期那一刻（秒级精度）主动关单——
     * 绝大多数订单根本不会被这个任务扫到（已经被 MQ 链路提前关闭）。
     *
     * <p>它继续存在，是为了兜住 MQ 链路覆盖不到的缝隙：
     * <ul>
     *   <li>{@code publishOrderCloseDelayMessage} 投递失败（Broker 临时不可用，best-effort 不重试）</li>
     *   <li>消息消费异常且重试耗尽，进入死信队列</li>
     *   <li>历史遗留订单（上线本机制之前创建、未携带延时消息的订单）</li>
     * </ul>
     * <p>正是这种「双保险」让 MQ 链路可以放心地选择 best-effort（不必为小概率丢失焦虑——
     * 丢了也只是退化为等这个任务扫到，最终一定会关闭），这是本方案设计上的核心权衡。
     *
     * <h2>触发条件 与 执行频率的调整</h2>
     * <p>订单状态为 WAIT_PAY 且 expire_at &lt; NOW()。
     * 执行间隔从 60 秒放宽到 5 分钟：作为兜底任务，正常情况下应该「无事可做」
     * （查询结果为空），没必要保持高频轮询去消耗 DB 资源；即使因为兜底而多等几分钟，
     * 用户体验影响也可以忽略（订单本来就已经过期未支付）。
     *
     * <h2>并发安全</h2>
     * <p>多实例部署时，多个节点可能同时执行此任务，对同一订单重复关闭。
     * 但 {@code updateStatusFromWaitPay} 的 WHERE status='WAIT_PAY' 保证幂等性：
     * 第一次关闭成功（affected=1），第二次 status 已是 CANCELLED，affected=0，
     * 直接跳过，不会出现重复关闭的副作用——与 {@code OrderCloseConsumer}
     * 重复消费时的兜底逻辑完全一致（同一段 {@link #closeOrderIfExpired} 代码复用）。
     *
     * <p>生产环境升级：用 Redis 分布式锁或 XXL-Job 的单节点模式，防止多实例重复执行。
     */
    @Scheduled(fixedDelay = 300_000) // 每 5 分钟执行一次（兜底任务，无需高频轮询）
    public void closeExpiredOrders() {
        // 查所有 WAIT_PAY 且已过期的订单
        List<OrderInfo> expiredOrders = orderInfoMapper.selectList(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getOrderStatus, "WAIT_PAY")
                        .lt(OrderInfo::getExpireAt, LocalDateTime.now()) // expire_at < NOW()
                        .last("LIMIT 200")); // 每批最多处理 200 条，防止一次扫描过多

        if (expiredOrders.isEmpty()) {
            return;
        }

        // 正常情况下不应该走到这里——能扫到东西，说明 MQ 链路有缝隙被兜住了，
        // 用 warn 级别打印，方便运维监控「兜底任务实际生效次数」（间接反映 MQ 链路健康度）。
        log.warn("[Order] 兜底任务发现 {} 笔过期订单（说明 MQ 关单链路存在遗漏，建议排查），开始兜底关闭",
                expiredOrders.size());
        int closedCount = 0;

        for (OrderInfo order : expiredOrders) {
            // 每笔订单单独处理，某一笔失败不影响其他笔
            try {
                if (closeOrderIfExpired(order)) {
                    closedCount++;
                }
            } catch (Exception e) {
                // 单条失败不影响整批，记录错误后继续
                log.error("[Order] 兜底关闭过期订单失败, orderId={}", order.getId(), e);
            }
        }

        log.info("[Order] 兜底关单任务完成：成功关闭 {} 笔", closedCount);
    }

    /**
     * 关闭单笔订单（若其确实仍处于「待支付且已过期」状态），并退回已使用的优惠券。
     *
     * <h2>「先查询、传入 OrderInfo」而不是「内部按 orderId 查询」的设计</h2>
     * <p>调用方（兜底任务、{@code OrderCloseConsumer}）已经各自拿到了最新的
     * {@code OrderInfo}（兜底任务批量查询得到；消费者复检时单独查询得到），
     * 复用同一个对象既避免了二次查询，也让两条链路的关单口径完全一致——
     * 这是抽取本方法的核心目的：<b>两条触发路径，一套关单逻辑</b>，
     * 杜绝「以后改了一处的判断条件、忘了改另一处」的漂移风险。
     *
     * <h2>幂等保证</h2>
     * <p>{@code updateStatusFromWaitPay} 内部 {@code WHERE status='WAIT_PAY'} 的 CAS 更新
     * 保证：无论被调用多少次（兜底任务和消费者都可能处理同一笔订单），
     * 只有第一次真正生效（affected=1），后续全部空操作（affected=0）。
     * 这正是 {@code OrderCloseConsumer} 不需要额外叠加 Redis SETNX 幂等层的原因。
     *
     * <h2>为什么这里还要再判断一次 expire_at（消费者复检的核心）</h2>
     * <p>「消息已送达」不等于「确实已过期」——
     * 不能假设 MQ 一定会精确地在 30 分钟整投递（重试、积压、Broker 配置差异
     * 都可能导致提前或延后）。在真正执行不可逆的「关单」动作之前，
     * 重新核对一次「触发条件」（{@code expire_at <= NOW()}）本身，
     * 而不是盲目相信「收到通知就等于条件成立」——这是消息驱动系统里
     * 一条朴素但容易被忽视的纪律：<b>消费侧永远要把消息当作「触发器」而不是「事实」</b>。
     *
     * @param order 待关闭的订单（调用方查询得到的最新快照）
     * @return 本次调用是否真正执行了关闭（false 表示订单已不是 WAIT_PAY 或尚未到期，幂等跳过）
     */
    private boolean closeOrderIfExpired(OrderInfo order) {
        // 复检触发条件：还没到期就不关（防止消息提前到达导致误关闭）
        if (order.getExpireAt() == null || order.getExpireAt().isAfter(LocalDateTime.now())) {
            return false;
        }

        // 传入 userId 精准路由到目标分片（order 对象已含 userId）
        int affected = orderInfoMapper.updateStatusFromWaitPay(order.getId(), order.getUserId(), "CANCELLED");
        if (affected == 0) {
            // 订单已不是 WAIT_PAY（已支付/已被取消/已被另一条链路关闭），幂等跳过
            return false;
        }

        // 如果使用了优惠券，回退券（USED → UNUSED，让用户可以再次使用）
        if (order.getUserCouponId() != null) {
            userCouponMapper.update(null,
                    new LambdaUpdateWrapper<UserCoupon>()
                            .eq(UserCoupon::getId, order.getUserCouponId())
                            .eq(UserCoupon::getCouponStatus, "USED")
                            .set(UserCoupon::getCouponStatus, "UNUSED")
                            .set(UserCoupon::getUsedAt, (Object) null));
        }
        return true;
    }

    /**
     * 处理「订单关闭延时消息」（由 {@code OrderCloseConsumer} 调用）。
     *
     * <h2>消费者复检：先查最新状态，再决定是否关单</h2>
     * <p>这是模仿 siam-cloud（外卖 O2O 平台）开源实现中
     * {@code OrderConsumer → closeOverdueOrder} 的经典两段式处理：
     * <pre>
     *   1. 重新查库，拿到订单的「此刻」状态（而不是消息体里 30 分钟前的快照）
     *   2. 复检「是否仍处于待支付且已过期」，是则关闭，否则直接忽略
     * </pre>
     * <p>这一步「重新查库复检」正是整个延时消息方案能够正确工作的关键——
     * 它消除了「下单 → 30 分钟后到期」这段时间窗口里订单状态变化
     * （用户已支付 / 已主动取消）带来的竞态：消息只是「到期了，去看看要不要关」的提醒，
     * 真正"是否关闭"的决定权永远在查询到的最新数据手里。
     *
     * <h2>本质上是“提前触发”而非“替代”兜底任务</h2>
     * <p>本方法与 {@code closeExpiredOrders()} 调用的是完全相同的
     * {@link #closeOrderIfExpired}，唯一区别是「谁先发现这笔订单到期了」——
     * 正常情况下消息会快几分钟（甚至几十分钟）抢先关闭，兜底任务扫到时已经是
     * affected=0 的空操作。两者的关系不是主备切换，而是「赛跑，谁先谁处理」。
     *
     * @param orderId 订单 ID（来自延时消息体）
     * @param userId  用户 ID（ShardingSphere 分片键，来自延时消息体）
     */
    public void handleOrderCloseDelayMessage(Long orderId, Long userId) {
        // userId 是分片键，selectOne 精准路由到目标物理表，避免广播查询全分片
        OrderInfo order = orderInfoMapper.selectOne(
                new LambdaQueryWrapper<OrderInfo>()
                        .eq(OrderInfo::getId, orderId)
                        .eq(OrderInfo::getUserId, userId));
        if (order == null) {
            // 理论上不应该发生（订单是创建成功后才发的延时消息）；
            // 防御性处理：记录日志后直接返回（视为消费成功，不重试——重试也查不到）
            log.warn("[Order] 关单延时消息复检：订单不存在，忽略, orderId={}, userId={}", orderId, userId);
            return;
        }

        boolean closed = closeOrderIfExpired(order);
        if (closed) {
            log.info("[Order] MQ 延时消息触发关单成功（先于兜底任务发现）: orderId={}, userId={}", orderId, userId);
        } else {
            log.debug("[Order] 关单延时消息复检：订单已不需要关闭（已支付/已取消/未到期），幂等跳过: " +
                    "orderId={}, userId={}, orderStatus={}", orderId, userId, order.getOrderStatus());
        }
    }

    // =========================================================
    // 内部方法：支付成功后更新订单状态（由 PaymentService 调用）
    // =========================================================

    /**
     * 将订单标记为已支付（由 PaymentService 在支付回调成功后调用）。
     *
     * <p>此方法不对外暴露（没有对应的 HTTP 接口），
     * 只由 PaymentService 在验证签名 + 金额核对都通过后调用。
     *
     * @param orderId 订单 ID
     * @param payAt   支付成功时间（来自渠道回调，非本地时间）
     * @return 是否更新成功（false = 幂等跳过，订单已是 PAID 状态）
     */
    public boolean markOrderAsPaid(Long orderId, Long userId, LocalDateTime payAt) {
        // userId 是 ShardingSphere 分片键，确保 UPDATE 路由到正确物理表而非广播
        int affected = orderInfoMapper.markAsPaid(orderId, userId, payAt);
        if (affected == 0) {
            // affected = 0 说明订单不是 WAIT_PAY 状态（已处理过），幂等跳过
            log.warn("[Order] markOrderAsPaid 幂等跳过：orderId={} 状态已非 WAIT_PAY", orderId);
            return false;
        }
        log.info("[Order] 订单标记为已支付：orderId={}, payAt={}", orderId, payAt);
        return true;
    }

    /**
     * 根据订单 ID 查询订单（供 PaymentService 调用，不做用户权限校验）。
     *
     * <p>注意：此方法是内部方法，不经过用户权限校验。
     * 调用方（PaymentService）需要自行保证调用场景的合法性（如支付回调是渠道调用，无需用户鉴权）。
     *
     * @param orderId 订单 ID
     * @return 订单实体，不存在时返回 null
     */
    public OrderInfo getOrderById(Long orderId) {
        return orderInfoMapper.selectById(orderId);
    }

    // =========================================================
    // 私有工具：Entity → VO 转换
    // =========================================================

    /**
     * 将 OrderInfo 实体转换为 OrderVO。
     *
     * <p>主要转换点：
     * <ul>
     *   <li>Long id → String（JS 安全整数问题）</li>
     *   <li>LocalDateTime → OffsetDateTime（加上 +08:00 时区信息，ISO 8601 标准）</li>
     * </ul>
     *
     * @param order    订单实体
     * @param shopName 门店名称（可为 null，列表接口中用于避免 N+1 时可省略）
     * @return OrderVO
     */
    private OrderVO toVO(OrderInfo order, String shopName) {
        ZoneOffset cst = ZoneOffset.ofHours(8); // 中国标准时间 UTC+8
        return OrderVO.builder()
                .orderId(String.valueOf(order.getId()))
                .orderNo(order.getOrderNo())
                .shopId(String.valueOf(order.getShopId()))
                .shopName(shopName)
                .originalAmount(order.getOriginalAmount())
                .couponDiscount(order.getCouponDiscount())
                .orderAmount(order.getOrderAmount())
                .orderStatus(order.getOrderStatus())
                .remark(order.getRemark())
                .expireAt(order.getExpireAt() != null
                        ? order.getExpireAt().atOffset(cst) : null)
                .payAt(order.getPayAt() != null
                        ? order.getPayAt().atOffset(cst) : null)
                .createdAt(order.getCreatedAt() != null
                        ? order.getCreatedAt().atOffset(cst) : null)
                .build();
    }
}
