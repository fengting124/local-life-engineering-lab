package com.personalprojections.locallife.server.module.order.service;

import com.baomidou.mybatisplus.core.conditions.query.LambdaQueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.LambdaUpdateWrapper;
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
import com.personalprojections.locallife.server.module.order.dto.CreateOrderRequest;
import com.personalprojections.locallife.server.module.order.dto.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.OffsetDateTime;
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
 *      │          └ cancelOrder()    expire_at 到期
 *      │                          closeExpiredOrders()（定时任务）
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
 * <h2>延迟关单方案说明</h2>
 * <p>当前：每分钟定时任务扫描 WAIT_PAY + expire_at < NOW() 的订单批量关闭。
 * 精度：最多延迟 1 分钟（扫描间隔），对用户体验影响可接受。
 * 升级路径（面试时可以说）：
 *   用 RocketMQ 延时消息，下单时投递一条 30 分钟后投递的消息，
 *   消费时检查状态（若已支付则忽略，否则关闭），精度到秒，且无需轮询数据库。
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

        // ---- Step 7: 写幂等 Key ----
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
        int affected = orderInfoMapper.updateStatusFromWaitPay(orderId, "CANCELLED");
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
    // 延迟关单（定时任务）
    // =========================================================

    /**
     * 批量关闭过期未支付订单（定时任务，每分钟执行一次）。
     *
     * <h2>触发条件</h2>
     * <p>订单状态为 WAIT_PAY 且 expire_at &lt; NOW()，说明用户在规定时间内未完成支付。
     *
     * <h2>为什么用定时任务而不是实时关闭</h2>
     * <p>实时关闭需要「到期事件触发」机制（如 Redis Key 过期通知、MQ 延时消息）。
     * 当前阶段用简单的定时轮询，精度 1 分钟，实现简单。
     * 升级方向：RocketMQ 延时消息（面试时说清楚升级路径即可）。
     *
     * <h2>并发安全</h2>
     * <p>多实例部署时，多个节点可能同时执行此任务，对同一订单重复关闭。
     * 但 {@code updateStatusFromWaitPay} 的 WHERE status='WAIT_PAY' 保证幂等性：
     * 第一次关闭成功（affected=1），第二次 status 已是 CANCELLED，affected=0，
     * 直接跳过，不会出现重复关闭的副作用。
     *
     * <p>生产环境升级：用 Redis 分布式锁或 XXL-Job 的单节点模式，防止多实例重复执行。
     */
    @Scheduled(fixedDelay = 60_000) // 每 60 秒执行一次，fixedDelay 从上次执行完毕后算
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

        log.info("[Order] 延迟关单任务：发现 {} 笔过期订单，开始关闭", expiredOrders.size());
        int closedCount = 0;

        for (OrderInfo order : expiredOrders) {
            // 每笔订单单独处理，某一笔失败不影响其他笔
            try {
                int affected = orderInfoMapper.updateStatusFromWaitPay(order.getId(), "CANCELLED");
                if (affected > 0) {
                    closedCount++;
                    // 如果使用了优惠券，回退券（让用户可以再次使用）
                    if (order.getUserCouponId() != null) {
                        userCouponMapper.update(null,
                                new LambdaUpdateWrapper<UserCoupon>()
                                        .eq(UserCoupon::getId, order.getUserCouponId())
                                        .eq(UserCoupon::getCouponStatus, "USED")
                                        .set(UserCoupon::getCouponStatus, "UNUSED")
                                        .set(UserCoupon::getUsedAt, (Object) null));
                    }
                }
            } catch (Exception e) {
                // 单条失败不影响整批，记录错误后继续
                log.error("[Order] 关闭过期订单失败, orderId={}", order.getId(), e);
            }
        }

        log.info("[Order] 延迟关单任务完成：成功关闭 {} 笔", closedCount);
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
    public boolean markOrderAsPaid(Long orderId, LocalDateTime payAt) {
        int affected = orderInfoMapper.markAsPaid(orderId, payAt);
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
