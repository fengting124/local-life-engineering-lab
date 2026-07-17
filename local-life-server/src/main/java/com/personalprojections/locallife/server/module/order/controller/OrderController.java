package com.personalprojections.locallife.server.module.order.controller;

import com.personalprojections.locallife.server.common.ratelimit.RateLimit;
import com.personalprojections.locallife.server.common.result.Result;
import com.personalprojections.locallife.server.module.order.dto.CreateOrderRequest;
import com.personalprojections.locallife.server.module.order.dto.OrderVO;
import com.personalprojections.locallife.server.module.order.service.OrderIdempotencyService;
import com.personalprojections.locallife.server.module.order.service.OrderService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Positive;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RestController;

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
 * <p>创建订单支持 {@code X-Idempotency-Key}。数据库账本通过
 * {@code UNIQUE(user_id, idempotency_key)} 形成最终约束，并保存首次成功响应。
 * 相同 Key 的并发请求不会重复创建订单；相同 Key 携带不同请求体会被拒绝。</p>
 */
@Slf4j
@RestController
@RequiredArgsConstructor
public class OrderController {

    private final OrderService orderService;
    private final OrderIdempotencyService orderIdempotencyService;

    /**
     * 创建订单（需登录）。
     *
     * <p>Header 示例：</p>
     * <pre>
     *   X-Idempotency-Key: 2bc7be65-24d7-4a55-a5f7-cb772690c231
     * </pre>
     */
    @RateLimit(key = "order:create", limit = 2, window = 10, keyType = RateLimit.KeyType.USER)
    @PostMapping("/api/v1/orders")
    public Result<OrderVO> createOrder(
            @Valid @RequestBody CreateOrderRequest request,
            @RequestHeader(value = "X-Idempotency-Key", required = false) String idempotencyKey) {
        OrderVO vo = orderIdempotencyService.createOrder(request, idempotencyKey);
        return Result.ok(vo);
    }

    /** 查询当前用户的订单列表。 */
    @GetMapping("/api/v1/orders")
    public Result<List<OrderVO>> listMyOrders() {
        List<OrderVO> list = orderService.listMyOrders();
        return Result.ok(list);
    }

    /** 查询订单详情，只允许查看自己的订单。 */
    @GetMapping("/api/v1/orders/{orderId}")
    public Result<OrderVO> getOrderDetail(@PathVariable @Positive Long orderId) {
        OrderVO vo = orderService.getOrderDetail(orderId);
        return Result.ok(vo);
    }

    /** 取消待支付订单，并在同一事务中退回已核销优惠券。 */
    @DeleteMapping("/api/v1/orders/{orderId}")
    public Result<Void> cancelOrder(@PathVariable @Positive Long orderId) {
        orderService.cancelOrder(orderId);
        return Result.ok(null);
    }
}
