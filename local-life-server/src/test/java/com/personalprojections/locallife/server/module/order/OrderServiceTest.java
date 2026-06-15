package com.personalprojections.locallife.server.module.order;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.core.metadata.TableInfoHelper;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.apache.ibatis.builder.MapperBuilderAssistant;
import com.personalprojections.locallife.server.common.context.LoginUserDTO;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.metrics.BusinessMetrics;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.*;
import com.personalprojections.locallife.server.domain.mapper.*;
import com.personalprojections.locallife.server.module.order.dto.CreateOrderRequest;
import com.personalprojections.locallife.server.module.order.dto.OrderVO;
import com.personalprojections.locallife.server.module.order.service.OrderService;
import org.apache.rocketmq.spring.core.RocketMQTemplate;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/**
 * {@link OrderService} 单元测试。
 *
 * <h2>测试重点</h2>
 * <ul>
 *   <li>createOrder：幂等、门店校验、券校验（5 个拒绝路径）、金额计算（CASH/PERCENT/无券）</li>
 *   <li>cancelOrder：订单不存在、状态机保护、取消时退券</li>
 *   <li>closeOrderIfExpired / handleOrderCloseDelayMessage：兜底关单幂等性</li>
 * </ul>
 *
 * <h2>TransactionSynchronizationManager</h2>
 * <p>{@code createOrder} 内部调用 {@code registerSynchronization()}，
 * 若当前线程无活跃事务同步则抛 {@link IllegalStateException}。
 * 测试中用 {@code initSynchronization()} 激活同步（不启动真实事务），
 * afterCommit 钩子注册成功但不会被触发——这正好验证了"注册发生了"而不污染业务断言。
 */
@ExtendWith(MockitoExtension.class)
class OrderServiceTest {

    @Mock private OrderInfoMapper orderInfoMapper;
    @Mock private ShopMapper shopMapper;
    @Mock private UserCouponMapper userCouponMapper;
    @Mock private CouponTemplateMapper couponTemplateMapper;
    @Mock private StringRedisTemplate stringRedisTemplate;
    @Mock private ValueOperations<String, String> valueOps;
    @Mock private BusinessMetrics businessMetrics;
    @Mock private RocketMQTemplate rocketMQTemplate;
    @Mock private ObjectMapper objectMapper;

    @InjectMocks
    private OrderService orderService;

    private static final long USER_ID  = 7777L;
    private static final long SHOP_ID  = 1L;
    private static final long ORDER_ID = 555L;

    @BeforeAll
    static void initMybatisPlusLambdaCache() {
        // LambdaUpdateWrapper resolves field→column names via TableInfoHelper at build time,
        // even when the mapper is a mock and no SQL is actually executed.
        // MP 3.5.7 requires a non-null MapperBuilderAssistant; MybatisConfiguration.defaults() suffices.
        MapperBuilderAssistant assistant = new MapperBuilderAssistant(new MybatisConfiguration(), "");
        TableInfoHelper.initTableInfo(assistant, UserCoupon.class);
        TableInfoHelper.initTableInfo(assistant, OrderInfo.class);
    }

    @BeforeEach
    void setUp() {
        UserContext.set(LoginUserDTO.builder().userId(USER_ID).status("ENABLED").build());
        // 激活事务同步，让 registerSynchronization() 不抛 IllegalStateException
        TransactionSynchronizationManager.initSynchronization();
    }

    @AfterEach
    void tearDown() {
        UserContext.clear();
        if (TransactionSynchronizationManager.isSynchronizationActive()) {
            TransactionSynchronizationManager.clearSynchronization();
        }
    }

    // =========================================================
    // 1. createOrder — 幂等 Key
    // =========================================================

    @Test
    void createOrder_idempotencyKeyHit_returnsExistingOrderWithoutInserting() {
        when(stringRedisTemplate.opsForValue()).thenReturn(valueOps);
        when(valueOps.get("idempotent:order:idem-key-001")).thenReturn("existing-order-no");

        OrderInfo existing = OrderInfo.builder()
                .id(ORDER_ID).orderNo("existing-order-no").userId(USER_ID)
                .shopId(SHOP_ID).originalAmount(10000).couponDiscount(0).orderAmount(10000)
                .orderStatus("WAIT_PAY").remark("").build();
        when(orderInfoMapper.selectOne(any())).thenReturn(existing);

        OrderVO vo = orderService.createOrder(request(null), "idem-key-001");

        assertThat(vo.getOrderNo()).isEqualTo("existing-order-no");
        // 幂等命中时不应再 INSERT（cast 解决 insert(T) vs insert(Collection<T>) 重载歧义）
        verify(orderInfoMapper, never()).insert((OrderInfo) any());
    }

    // =========================================================
    // 2. createOrder — 门店校验
    // =========================================================

    @Test
    void createOrder_shopNotFound_throwsShopNotFound() {
        // idempotencyKey=null → Step 1 (幂等检查) 被跳过，无 Redis 调用
        when(shopMapper.selectById(SHOP_ID)).thenReturn(null);

        assertThatThrownBy(() -> orderService.createOrder(request(null), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SHOP_NOT_FOUND));
    }

    @Test
    void createOrder_shopNotOnline_throwsShopNotOnline() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(
                shop("OFFLINE"));

        assertThatThrownBy(() -> orderService.createOrder(request(null), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.SHOP_NOT_ONLINE));
    }

    // =========================================================
    // 3. createOrder — 券校验（5 条拒绝路径）
    // =========================================================

    @Test
    void createOrder_couponNotOwnedByCurrentUser_throwsCouponInvalid() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE"));
        when(userCouponMapper.selectOne(any())).thenReturn(null);  // 查不到 → 不属于当前用户

        CreateOrderRequest req = request(888L);
        assertThatThrownBy(() -> orderService.createOrder(req, null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_COUPON_INVALID));
    }

    @Test
    void createOrder_couponAlreadyUsed_throwsCouponInvalid() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE"));
        when(userCouponMapper.selectOne(any())).thenReturn(coupon("USED", null));

        assertThatThrownBy(() -> orderService.createOrder(request(888L), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_COUPON_INVALID));
    }

    @Test
    void createOrder_couponExpired_throwsCouponInvalid() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE"));
        LocalDateTime yesterday = LocalDateTime.now().minusDays(1);
        when(userCouponMapper.selectOne(any())).thenReturn(coupon("UNUSED", yesterday));

        assertThatThrownBy(() -> orderService.createOrder(request(888L), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_COUPON_INVALID));
    }

    @Test
    void createOrder_couponTemplateInactive_throwsCouponInvalid() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE"));
        when(userCouponMapper.selectOne(any())).thenReturn(coupon("UNUSED", null));
        // 模板 status=INACTIVE（已下架）
        when(couponTemplateMapper.selectById(anyLong())).thenReturn(
                cashTemplate(2000, 0, "INACTIVE"));

        assertThatThrownBy(() -> orderService.createOrder(request(888L), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_COUPON_INVALID));
    }

    @Test
    void createOrder_orderAmountBelowCouponMinAmount_throwsBelowMinAmount() {
        // 门店价格 5000 分（50元），券最低使用门槛 10000 分（100元）
        when(shopMapper.selectById(SHOP_ID)).thenReturn(
                Shop.builder().id(SHOP_ID).shopName("小店").status("ONLINE").price(5000).deleted(0).build());
        when(userCouponMapper.selectOne(any())).thenReturn(coupon("UNUSED", null));
        when(couponTemplateMapper.selectById(anyLong())).thenReturn(
                cashTemplate(2000, 10000, "ACTIVE"));  // minOrderAmount=10000

        assertThatThrownBy(() -> orderService.createOrder(request(888L), null))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_COUPON_BELOW_MIN_AMOUNT));
    }

    // =========================================================
    // 4. createOrder — 金额计算（核心业务逻辑，服务端计算、不信任客户端）
    // =========================================================

    @Test
    void createOrder_withCashCoupon_subtractsDiscountFromOriginalAmount() {
        // 门店原价 10000 分（100元），现金抵扣券 2000 分（减 20 元）→ 实付 8000 分
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE"));  // price=10000
        when(userCouponMapper.selectOne(any())).thenReturn(coupon("UNUSED", null));
        when(couponTemplateMapper.selectById(anyLong())).thenReturn(
                cashTemplate(2000, 0, "ACTIVE"));  // CASH, discountValue=2000
        when(userCouponMapper.update(any(), any())).thenReturn(1);

        stubInsertWithId(ORDER_ID);
        when(orderInfoMapper.update(any(), any())).thenReturn(1);

        OrderVO vo = orderService.createOrder(request(888L), null);

        assertThat(vo.getOriginalAmount()).isEqualTo(10000);
        assertThat(vo.getCouponDiscount()).isEqualTo(2000);
        assertThat(vo.getOrderAmount()).isEqualTo(8000);
    }

    @Test
    void createOrder_withPercentCoupon_computesDiscountByMultiplication() {
        // 门店原价 10000 分，8折（discountValue=80）→ 折扣 = 10000×(1-80/100) = 2000 → 实付 8000
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE"));
        when(userCouponMapper.selectOne(any())).thenReturn(coupon("UNUSED", null));

        CouponTemplate template = CouponTemplate.builder()
                .id(100L).discountType("PERCENT").discountValue(80)
                .minOrderAmount(0).status("ACTIVE").build();
        when(couponTemplateMapper.selectById(anyLong())).thenReturn(template);
        when(userCouponMapper.update(any(), any())).thenReturn(1);

        stubInsertWithId(ORDER_ID);
        when(orderInfoMapper.update(any(), any())).thenReturn(1);

        OrderVO vo = orderService.createOrder(request(888L), null);

        assertThat(vo.getOrderAmount())
                .as("8折 on 10000 分 = 实付 8000 分")
                .isEqualTo(8000);
        assertThat(vo.getCouponDiscount()).isEqualTo(2000);
    }

    @Test
    void createOrder_noCoupon_fullPrice() {
        when(shopMapper.selectById(SHOP_ID)).thenReturn(shop("ONLINE"));
        stubInsertWithId(ORDER_ID);
        when(orderInfoMapper.update(any(), any())).thenReturn(1);

        OrderVO vo = orderService.createOrder(request(null), null);

        assertThat(vo.getOrderAmount()).isEqualTo(10000);
        assertThat(vo.getCouponDiscount()).isEqualTo(0);
    }

    // =========================================================
    // 5. cancelOrder — 订单状态机保护
    // =========================================================

    @Test
    void cancelOrder_orderNotFound_throwsOrderNotFound() {
        when(orderInfoMapper.selectOne(any())).thenReturn(null);

        assertThatThrownBy(() -> orderService.cancelOrder(ORDER_ID))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_NOT_FOUND));
    }

    @Test
    void cancelOrder_alreadyPaid_updateStatusReturnsZero_throwsOrderStatusIllegal() {
        // 并发场景：订单已被支付，WHERE status='WAIT_PAY' 不命中，affected=0
        when(orderInfoMapper.selectOne(any())).thenReturn(orderEntity("PAID", null));
        when(orderInfoMapper.updateStatusFromWaitPay(ORDER_ID, USER_ID, "CANCELLED")).thenReturn(0);

        assertThatThrownBy(() -> orderService.cancelOrder(ORDER_ID))
                .isInstanceOf(BizException.class)
                .satisfies(ex -> assertThat(((BizException) ex).getErrorCode())
                        .isEqualTo(ErrorCode.ORDER_STATUS_ILLEGAL));
    }

    @Test
    void cancelOrder_withCoupon_rollsBackCoupon() {
        // 取消订单时，如果使用了优惠券，应回退券状态（USED → UNUSED），供用户下次使用
        long couponId = 77L;
        when(orderInfoMapper.selectOne(any())).thenReturn(orderEntity("WAIT_PAY", couponId));
        when(orderInfoMapper.updateStatusFromWaitPay(ORDER_ID, USER_ID, "CANCELLED")).thenReturn(1);
        when(userCouponMapper.update(any(), any())).thenReturn(1);

        orderService.cancelOrder(ORDER_ID);

        // 验证 userCouponMapper.update() 被调用（退券）
        verify(userCouponMapper, times(1)).update(any(), any());
    }

    @Test
    void cancelOrder_noCoupon_doesNotTouchCouponMapper() {
        when(orderInfoMapper.selectOne(any())).thenReturn(orderEntity("WAIT_PAY", null));
        when(orderInfoMapper.updateStatusFromWaitPay(ORDER_ID, USER_ID, "CANCELLED")).thenReturn(1);

        orderService.cancelOrder(ORDER_ID);

        verifyNoInteractions(userCouponMapper);
    }

    // =========================================================
    // 6. handleOrderCloseDelayMessage — MQ 延时消息触发的关单
    // =========================================================

    @Test
    void handleOrderCloseDelayMessage_orderNotFound_returnsGracefully() {
        // 理论上不应发生，但防御性处理：订单不存在时记录日志直接返回，不抛异常
        when(orderInfoMapper.selectOne(any())).thenReturn(null);

        // 不应抛异常（如果抛了，MQ 会重试，但查不到的订单重试也没用）
        orderService.handleOrderCloseDelayMessage(ORDER_ID, USER_ID);
        verifyNoInteractions(userCouponMapper);
    }

    @Test
    void handleOrderCloseDelayMessage_orderNotExpiredYet_skipsClose() {
        // 消息提前到达（MQ 精度偏差）：expireAt 还在未来，不应关单
        OrderInfo order = orderEntity("WAIT_PAY", null);
        order.setExpireAt(LocalDateTime.now().plusMinutes(5));  // 还没到期
        when(orderInfoMapper.selectOne(any())).thenReturn(order);

        orderService.handleOrderCloseDelayMessage(ORDER_ID, USER_ID);

        verify(orderInfoMapper, never()).updateStatusFromWaitPay(any(), any(), any());
    }

    @Test
    void handleOrderCloseDelayMessage_expiredOrder_closesSuccessfully() {
        // 正常路径：到期的 WAIT_PAY 订单被关闭
        OrderInfo order = orderEntity("WAIT_PAY", null);
        order.setExpireAt(LocalDateTime.now().minusMinutes(1));  // 已过期
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        when(orderInfoMapper.updateStatusFromWaitPay(ORDER_ID, USER_ID, "CANCELLED")).thenReturn(1);

        orderService.handleOrderCloseDelayMessage(ORDER_ID, USER_ID);

        verify(orderInfoMapper, times(1)).updateStatusFromWaitPay(ORDER_ID, USER_ID, "CANCELLED");
    }

    @Test
    void handleOrderCloseDelayMessage_alreadyCancelled_idempotentSkip() {
        // 并发场景：兜底任务和 MQ 消费者赛跑，另一条链路已经关单了
        // updateStatusFromWaitPay 返回 0（affected=0），幂等跳过，不报错
        OrderInfo order = orderEntity("WAIT_PAY", null);
        order.setExpireAt(LocalDateTime.now().minusMinutes(1));
        when(orderInfoMapper.selectOne(any())).thenReturn(order);
        when(orderInfoMapper.updateStatusFromWaitPay(ORDER_ID, USER_ID, "CANCELLED")).thenReturn(0);

        orderService.handleOrderCloseDelayMessage(ORDER_ID, USER_ID);  // 不应抛异常

        verify(orderInfoMapper, times(1)).updateStatusFromWaitPay(ORDER_ID, USER_ID, "CANCELLED");
    }

    // =========================================================
    // 辅助方法
    // =========================================================

    private CreateOrderRequest request(Long userCouponId) {
        CreateOrderRequest req = new CreateOrderRequest();
        req.setShopId(SHOP_ID);
        req.setUserCouponId(userCouponId);
        return req;
    }

    private Shop shop(String status) {
        return Shop.builder()
                .id(SHOP_ID).shopName("测试门店").status(status)
                .price(10000).deleted(0).build();
    }

    private UserCoupon coupon(String status, LocalDateTime expireAt) {
        return UserCoupon.builder()
                .id(888L).userId(USER_ID).couponTemplateId(100L)
                .couponStatus(status).expireAt(expireAt).build();
    }

    private CouponTemplate cashTemplate(int discountValue, int minOrderAmount, String status) {
        return CouponTemplate.builder()
                .id(100L).discountType("CASH")
                .discountValue(discountValue)
                .minOrderAmount(minOrderAmount)
                .status(status).build();
    }

    private OrderInfo orderEntity(String status, Long userCouponId) {
        return OrderInfo.builder()
                .id(ORDER_ID).userId(USER_ID).shopId(SHOP_ID)
                .orderStatus(status).userCouponId(userCouponId)
                .originalAmount(10000).couponDiscount(0).orderAmount(10000)
                .expireAt(LocalDateTime.now().plusMinutes(30))
                .build();
    }

    private void stubInsertWithId(long id) {
        // Cast to OrderInfo resolves the ambiguity between BaseMapper#insert(T) and insert(Collection<T>)
        doAnswer(inv -> {
            OrderInfo o = (OrderInfo) inv.getArgument(0);
            o.setId(id);
            return 1;
        }).when(orderInfoMapper).insert((OrderInfo) any());
    }
}
