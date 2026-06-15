package com.personalprojections.locallife.copilot.client;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.slf4j.MDC;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientResponseException;

import java.util.Map;

/**
 * LocalLife 主服务内部 HTTP 客户端。
 *
 * <h2>调用链</h2>
 * 
 * <pre>
 *   Python Agent
 *     → tools/call(execute_refund)
 *     → Java MCP Server (ExecuteRefundTool)
 *     → LocalLifeInternalClient.refund()
 *     → LocalLife Server POST /internal/orders/{orderNo}/refund
 *     → InternalService.executeRefund()
 * </pre>
 *
 * <h2>安全机制</h2>
 * <p>
 * 所有请求携带 {@code X-Internal-Key} Header（与 LocalLife Server 配置的密钥一致）。
 * 网络层：生产环境仅允许内网访问 LocalLife Server 的 /internal/** 端点。
 *
 * <h2>错误处理</h2>
 * <p>
 * LocalLife Server 返回的业务错误（4xx）被转换为工具的结构化错误：
 * <ul>
 * <li>ORDER_NOT_FOUND → ToolNotFoundException</li>
 * <li>ORDER_STATUS_ILLEGAL → ToolBusinessException</li>
 * <li>PAYMENT_AMOUNT_MISMATCH → ToolParameterException</li>
 * <li>SYS_BUSY / 5xx → RuntimeException（触发 tool_timeout 错误类型）</li>
 * </ul>
 */
@Slf4j
@Component
@RequiredArgsConstructor
public class LocalLifeInternalClient {

    private final RestClient localLifeRestClient;
    private final ObjectMapper objectMapper;

    @Value("${locallife.server.url:http://localhost:8080}")
    private String localLifeServerUrl;

    @Value("${locallife.server.internal-key:local-life-internal-secret}")
    private String internalKey;

    // =========================================================
    // 退款
    // =========================================================

    /**
     * 调用 LocalLife Server 执行退款。
     *
     * @param orderNo    订单号
     * @param amount     退款金额（分）
     * @param approvalId HITL 审批 ID
     * @param reason     退款原因
     * @return 退款结果（含 refundNo / status）
     */
    public Map<String, Object> refund(String orderNo, int amount, String approvalId, String reason) {
        String url = localLifeServerUrl + "/internal/orders/" + orderNo + "/refund";
        Map<String, Object> body = Map.of(
                "amount", amount,
                "approvalId", approvalId,
                "reason", reason);
        log.info("[InternalClient] 调用退款 API: orderNo={}, amount={}分", orderNo, amount);
        return post(url, body, "refund");
    }

    // =========================================================
    // 补偿券
    // =========================================================

    /**
     * 调用 LocalLife Server 发放补偿优惠券。
     *
     * @param orderNo            关联订单号
     * @param userId             目标用户 ID
     * @param compensationAmount 补偿券面值（分）
     * @param approvalId         HITL 审批 ID
     * @param reason             补偿原因
     * @return 补偿结果（含 couponId / status）
     */
    public Map<String, Object> compensateCoupon(
            String orderNo, String userId, int compensationAmount,
            String approvalId, String reason) {
        String url = localLifeServerUrl + "/internal/orders/" + orderNo + "/compensate-coupon";
        Map<String, Object> body = Map.of(
                "userId", userId,
                "compensationAmount", compensationAmount,
                "approvalId", approvalId,
                "reason", reason);
        log.info("[InternalClient] 调用补偿券 API: orderNo={}, userId={}, amount={}分", orderNo, userId, compensationAmount);
        return post(url, body, "compensate-coupon");
    }

    // =========================================================
    // 私有 HTTP 工具方法
    // =========================================================

    private Map<String, Object> post(String url, Map<String, Object> body, String operation) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.set("X-Internal-Key", internalKey);
        String traceId = MDC.get("traceId");
        if (traceId != null && !traceId.isBlank()) {
            headers.set("X-Trace-Id", traceId);
        }

        try {
            // 用 String 接收再手动解析，避免 raw Map 类型警告，并保留统一响应格式处理。
            String responseBody = localLifeRestClient.post()
                    .uri(url)
                    .contentType(MediaType.APPLICATION_JSON)
                    .headers(httpHeaders -> httpHeaders.addAll(headers))
                    .body(body)
                    .retrieve()
                    .body(String.class);

            if (responseBody == null || responseBody.isBlank()) {
                throw new RuntimeException(operation + " 返回空响应");
            }
            Map<String, Object> result = parseJsonToMap(responseBody);
            if (result == null) {
                throw new RuntimeException(operation + " 返回空响应");
            }

            // 解析 LocalLife 统一响应格式 { "code": "OK", "data": {...} }
            String code = (String) result.get("code");
            if (!"OK".equals(code)) {
                String message = (String) result.getOrDefault("message", operation + " 失败");
                translateError(code, message);
            }

            Object data = result.get("data");
            if (data instanceof Map<?, ?> dataMap) {
                // 用 Map<?, ?> 模式匹配 + new HashMap 拷贝，类型安全且无 unchecked 警告
                java.util.Map<String, Object> typed = new java.util.HashMap<>();
                dataMap.forEach((k, v) -> typed.put(String.valueOf(k), v));
                return typed;
            }
            return Map.of("status", "SUCCESS", "raw", String.valueOf(data));

        } catch (RestClientResponseException e) {
            // 4xx/5xx 错误：优先解析 LocalLife 统一错误码，解析失败再作为内部调用失败。
            Map<String, Object> errorBody;
            try {
                errorBody = parseJsonToMap(e.getResponseBodyAsString());
            } catch (RuntimeException parseEx) {
                throw new RuntimeException(operation + " 请求失败: " + e.getMessage(), e);
            }
            if (errorBody != null) {
                String code = (String) errorBody.getOrDefault("code", "UNKNOWN");
                String msg  = (String) errorBody.getOrDefault("message", e.getMessage());
                translateError(code, msg);
            }
            throw new RuntimeException(operation + " 请求失败: " + e.getMessage(), e);
        } catch (com.personalprojections.locallife.copilot.tool.McpTool.ToolNotFoundException
                | com.personalprojections.locallife.copilot.tool.McpTool.ToolParameterException
                | com.personalprojections.locallife.copilot.tool.McpTool.ToolBusinessException e) {
            // 业务错误透传给上层（McpController 会转为 MCP 结构化错误）
            throw e;
        } catch (Exception e) {
            log.error("[InternalClient] {} 调用失败: {}", operation, e.getMessage(), e);
            throw new RuntimeException(operation + " 内部服务调用失败: " + e.getMessage());
        }
    }

    /**
     * 将 JSON 字符串解析为 Map。
     *
     * <p>把受检的 {@code JsonProcessingException} 包装为 {@code RuntimeException}，
     * 避免调用方层层声明 {@code throws}，也便于 try-catch 统一处理。
     *
     * @param json JSON 字符串
     * @return 解析后的 Map，null 或空串返回 null
     */
    private Map<String, Object> parseJsonToMap(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> map = objectMapper.readValue(json, Map.class);
            return map;
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new RuntimeException("JSON 解析失败: " + e.getOriginalMessage(), e);
        }
    }

    /** 将 LocalLife 业务错误码转换为 MCP 工具异常。 */
    private void translateError(String code, String message) {
        switch (code) {
            case "ORDER_NOT_FOUND" ->
                throw new com.personalprojections.locallife.copilot.tool.McpTool.ToolNotFoundException(message);
            case "ORDER_STATUS_ILLEGAL" ->
                throw new com.personalprojections.locallife.copilot.tool.McpTool.ToolBusinessException(message);
            case "PAYMENT_AMOUNT_MISMATCH" ->
                throw new com.personalprojections.locallife.copilot.tool.McpTool.ToolParameterException(message,
                        "退款金额不能超过实付金额");
            default -> throw new RuntimeException("业务错误 [" + code + "]: " + message);
        }
    }
}
