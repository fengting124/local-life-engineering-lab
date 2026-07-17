package com.personalprojections.locallife.server.module.order.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.common.context.UserContext;
import com.personalprojections.locallife.server.common.exception.BizException;
import com.personalprojections.locallife.server.common.result.ErrorCode;
import com.personalprojections.locallife.server.domain.entity.OrderIdempotency;
import com.personalprojections.locallife.server.module.order.dto.CreateOrderRequest;
import com.personalprojections.locallife.server.module.order.dto.OrderVO;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.LocalDateTime;
import java.util.HexFormat;

/**
 * 创建订单入口的数据库幂等编排器。
 *
 * <p>处理顺序：</p>
 * <ol>
 *   <li>用唯一键领取 PROCESSING 记录。</li>
 *   <li>领取者在业务事务中创建订单并写 SUCCESS 响应。</li>
 *   <li>并发重试等待首个请求完成，然后回放同一响应。</li>
 *   <li>相同 Key 携带不同请求体时拒绝，防止错误复用。</li>
 * </ol>
 */
@Slf4j
@Service
@RequiredArgsConstructor
public class OrderIdempotencyService {

    private static final int MAX_KEY_LENGTH = 64;
    private static final long WAIT_TIMEOUT_MILLIS = 2_000L;
    private static final long POLL_INTERVAL_MILLIS = 50L;

    private final OrderService orderService;
    private final OrderIdempotencyClaimService claimService;
    private final OrderIdempotencyExecutionService executionService;
    private final ObjectMapper objectMapper;

    public OrderVO createOrder(CreateOrderRequest request, String rawIdempotencyKey) {
        String key = normalizeKey(rawIdempotencyKey);
        if (key == null) {
            return orderService.createOrder(request, null);
        }

        Long userId = UserContext.getUserId();
        String requestHash = hashRequest(request);

        if (claimService.tryClaim(userId, key, requestHash)) {
            return executeClaimed(request, userId, key, requestHash);
        }

        long deadline = System.currentTimeMillis() + WAIT_TIMEOUT_MILLIS;
        while (System.currentTimeMillis() <= deadline) {
            OrderIdempotency record = claimService.get(userId, key);
            if (record == null) {
                sleepBeforeRetry();
                continue;
            }

            if (!requestHash.equals(record.getRequestHash())) {
                log.warn("幂等 Key 被不同请求体复用, userId={}, key={}", userId, key);
                throw new BizException(ErrorCode.SYS_PARAM_INVALID);
            }

            if ("SUCCESS".equals(record.getStatus())) {
                return deserialize(record.getResponseJson());
            }

            boolean leaseExpired = "PROCESSING".equals(record.getStatus())
                    && record.getLeaseUntil() != null
                    && record.getLeaseUntil().isBefore(LocalDateTime.now());
            if ("FAILED".equals(record.getStatus()) || leaseExpired) {
                if (claimService.reclaimFailedOrExpired(userId, key, requestHash)) {
                    return executeClaimed(request, userId, key, requestHash);
                }
            }

            sleepBeforeRetry();
        }

        // 首个请求仍在执行。客户端使用相同 Key 重试即可，不能绕过账本再创建一笔订单。
        throw new BizException(ErrorCode.SYS_BUSY);
    }

    private OrderVO executeClaimed(
            CreateOrderRequest request,
            Long userId,
            String key,
            String requestHash) {
        try {
            return executionService.execute(request, userId, key, requestHash);
        } catch (RuntimeException error) {
            claimService.markFailed(userId, key, requestHash, error);
            throw error;
        }
    }

    private String normalizeKey(String rawKey) {
        if (rawKey == null || rawKey.isBlank()) {
            return null;
        }
        String key = rawKey.trim();
        if (key.length() > MAX_KEY_LENGTH) {
            throw new BizException(ErrorCode.SYS_PARAM_INVALID);
        }
        return key;
    }

    private String hashRequest(CreateOrderRequest request) {
        try {
            byte[] payload = objectMapper.writeValueAsBytes(request);
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(payload);
            return HexFormat.of().formatHex(digest);
        } catch (JsonProcessingException | NoSuchAlgorithmException error) {
            throw new IllegalStateException("Failed to hash order request", error);
        }
    }

    private OrderVO deserialize(String responseJson) {
        if (responseJson == null || responseJson.isBlank()) {
            throw new IllegalStateException("Successful idempotency record has no response");
        }
        try {
            return objectMapper.readValue(responseJson, OrderVO.class);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Failed to deserialize idempotent order response", error);
        }
    }

    private void sleepBeforeRetry() {
        try {
            Thread.sleep(POLL_INTERVAL_MILLIS);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new BizException(ErrorCode.SYS_BUSY);
        }
    }
}
