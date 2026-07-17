package com.personalprojections.locallife.server.module.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.domain.mapper.OrderIdempotencyMapper;
import com.personalprojections.locallife.server.module.order.dto.CreateOrderRequest;
import com.personalprojections.locallife.server.module.order.dto.OrderVO;
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 在同一个 MySQL 事务中创建订单并固化幂等响应。
 *
 * <p>该事务失败时，订单、优惠券核销和 SUCCESS 状态一起回滚。Redis 幂等 Key 不参与
 * 此入口，避免数据库回滚后留下指向不存在订单的缓存记录。</p>
 */
@Service
@RequiredArgsConstructor
public class OrderIdempotencyExecutionService {

    private final OrderService orderService;
    private final OrderIdempotencyMapper idempotencyMapper;
    private final ObjectMapper objectMapper;

    @Transactional(rollbackFor = Exception.class)
    public OrderVO execute(
            CreateOrderRequest request,
            Long userId,
            String idempotencyKey,
            String requestHash) {

        // 传 null，关闭旧的 Redis 先读后写幂等路径。最终正确性由数据库账本承担。
        OrderVO result = orderService.createOrder(request, null);
        String responseJson = serialize(result);

        int updated = idempotencyMapper.markSuccess(
                userId,
                idempotencyKey,
                requestHash,
                responseJson);
        if (updated != 1) {
            throw new IllegalStateException("Idempotency claim lost before order commit");
        }
        return result;
    }

    private String serialize(OrderVO result) {
        try {
            return objectMapper.writeValueAsString(result);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Failed to serialize idempotent order response", e);
        }
    }
}
