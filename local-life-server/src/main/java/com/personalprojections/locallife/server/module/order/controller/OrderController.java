package com.personalprojections.locallife.server.module.order.controller;

import com.personalprojections.locallife.server.common.ratelimit.RateLimit;
import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.order.dto.CreateOrderRequest;
import com.personalprojections.locallife.server.module.order.dto.OrderVO;
import com.personalprojections.locallife.server.module.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.*;

import java.util.List;

/**
 * 订单 Controller。
 *
 * <h2>接口列表</h2>
 * <pre>
 *   POST   /api/v1/orders              创建订单（需登录）
 *   GET    /api/v1/orders              我的订单列表（需登录）
 *   GET    /api/v1/orders/{orderId}    订单详情（需登录）
 *   DELETE /api/v1/orders/{orderId}    取消订单（需登录）
 * </pre>
 *
 * <h2>幂等性说明</h2>
 * <p>POST /api/v1/orders 支持客户端幂等 Key，通过 Header {@code X-Idempotency-Key} 传入。
 * 同一个 Key 在 5 分钟内重复请求，返回第一次创建的订单（不重复创建）。
 * 适用于：网络抖动导致客户端重试、用户快速双击「确认下单」等场景。
 *
 * <h2>权限说明</h2>
 * <p>所有接口都需要登录（JWT Token），由 AuthInterceptor 统一鉴权。
 * 非本人订单返回 ORDER_NOT_FOUND（不区分「不存在」和「无权限」）。
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;

    // =========================================================
    // 创建订单
    // =========================================================

    /**
     * 创建订单（需登录）。
     *
     * <p>前端触发场景：
     * <ul>
     *   <li>用户在门店详情页点「立即购买」</li>
     *   <li>用户在优惠券详情页点「去使用 → 选择门店 → 确认下单」</li>
     * </ul>
     *
     * <p>幂等 Header（可选）：
     * <pre>
     *   X-Idempotency-Key: {UUID}   ← 前端在首次请求时生成，重试时携带相同 Key
     * </pre>
     *
     * <p>成功响应示例：
     * <pre>{@code
     * {
     *   "code": "OK",
     *   "data": {
     *     "orderId": "1234567890123456789",
     *     "orderNo": "1234567890123456789",
     *     "shopId": "9876543210",
     *     "shopName": "小明饺子馆",
     *     "originalAmount": 9900,
     *     "couponDiscount": 1000,
     *     "orderAmount": 8900,
     *     "orderStatus": "WAIT_PAY",
     *     "expireAt": "2026-05-28T21:30:00+08:00"
     *   }
     * }
     * }</pre>
     *
     * @param request        下单请求体（shopId 必填，userCouponId 选填）
     * @param idempotencyKey 幂等 Key（从 Header X-Idempotency-Key 读取，可为 null）
     * @return 创建好的订单 VO
     */
    // 同一用户 10 秒内最多下单 2 次（防重复点击，配合幂等 Key 双重保护）
    @RateLimit(key = "order:create", limit = 2, window = 10, keyType = RateLimit.KeyType.USER)
    @PostMapping("/api/v1/orders")
    public Result<OrderVO> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        OrderVO vo = orderService.createOrder(request, idempotencyKey);
        return Result.ok(vo);
    }

    // =========================================================
    // 查询订单
    // =========================================================

    /**
     * 查询我的订单列表（需登录）。
     *
     * <p>返回当前用户所有订单（含 WAIT_PAY / PAID / CANCELLED / COMPLETED），
     * 按创建时间倒序（最新的排前面）。
     * 简化版：未分页，直接返回全部。
     * 生产级：应分页（pageNumber + pageSize），建议初始 pageSize=10。
     *
     * @return 订单列表
     */
    @GetMapping("/api/v1/orders")
    public Result<List<OrderVO>> listMyOrders() {
        List<OrderVO> list = orderService.listMyOrders();
        return Result.ok(list);
    }

    /**
     * 查询订单详情（需登录）。
     *
     * <p>只能查看自己的订单，查他人订单返回 ORDER_NOT_FOUND。
     *
     * @param orderId 订单 ID（Path 变量）
     * @return 订单详情 VO
     */
    @GetMapping("/api/v1/orders/{orderId}")
    public Result<OrderVO> getOrderDetail(
            @PathVariable @Positive Long orderId) {
        OrderVO vo = orderService.getOrderDetail(orderId);
        return Result.ok(vo);
    }

    // =========================================================
    // 取消订单
    // =========================================================

    /**
     * 取消订单（需登录）。
     *
     * <p>只有 WAIT_PAY 状态的订单可以取消，已支付订单不可取消。
     * 如果下单时使用了优惠券，取消后券会回退到 UNUSED 状态，可重新使用。
     *
     * <p>并发安全：数据库 UPDATE WHERE status='WAIT_PAY' 保证只有一个请求成功取消。
     *
     * @param orderId 要取消的订单 ID
     * @return 无业务数据，成功返回 code=OK
     */
    @DeleteMapping("/api/v1/orders/{orderId}")
    public Result<Void> cancelOrder(
            @PathVariable @Positive Long orderId) {
        orderService.cancelOrder(orderId);
        return Result.ok(null);
    }
}
