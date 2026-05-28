package com.personalprojections.locallife.server.module.internal;

import com.personalprojections.locallife.server.common.result.Result;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Positive;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.web.bind.annotation.*;

/**
 * 内部服务接口（Internal API），仅供 Copilot MCP Server 调用。
 *
 * <h2>安全设计</h2>
 * <p>此 Controller 的接口不对用户开放，通过以下机制保护：
 * <ol>
 *   <li>在 WebMvcConfig 中白名单（不走 AuthInterceptor）</li>
 *   <li>通过 X-Internal-Key Header 验证共享密钥（简单 Token 验证）</li>
 *   <li>生产环境：将 /internal/** 仅绑定内网网卡，或通过 API Gateway 限制来源 IP</li>
 * </ol>
 *
 * <h2>接口列表</h2>
 * <pre>
 *   POST /internal/orders/{orderNo}/refund          执行退款（需 HITL approval_id）
 *   POST /internal/orders/{orderNo}/compensate-coupon  发放补偿券（需 HITL approval_id）
 * </pre>
 *
 * <h2>面试说法</h2>
 * <p>「高风险动作（退款/补券）由 Java 主服务执行，而不是 Agent 直接写数据库。
 * Agent 通过 MCP → Java MCP Server → 调用 LocalLife Server 内部 API 完成操作。
 * 这样业务规则（退款金额校验、订单状态校验）都在 Java 侧保证，Agent 无法绕过。」
 */
@Slf4j
@RestController
@RequestMapping("/internal")
@RequiredArgsConstructor
public class InternalController {

    private final InternalService internalService;

    /** 内部 API 密钥（从 application.yml 注入，生产环境从密钥管理服务读取）。 */
    @Value("${internal.api-key:local-life-internal-secret}")
    private String expectedApiKey;

    // =========================================================
    // 退款接口
    // =========================================================

    /**
     * 执行退款（Copilot MCP Server 审批通过后调用）。
     *
     * <p>执行前验证：
     * <ol>
     *   <li>X-Internal-Key 合法</li>
     *   <li>订单存在且状态为 PAID</li>
     *   <li>退款金额 <= 实付金额</li>
     *   <li>approval_id 有效（对接 Copilot hitl_approval 表，当前简化验证）</li>
     * </ol>
     *
     * @param orderNo    订单号
     * @param request    退款请求体
     * @param httpRequest HTTP 请求（取 Header 验签）
     */
    @PostMapping("/orders/{orderNo}/refund")
    public Result<RefundResult> refund(
            @PathVariable @NotBlank String orderNo,
            @Valid @RequestBody RefundRequest request,
            HttpServletRequest httpRequest) {

        verifyInternalKey(httpRequest);

        log.info("[Internal] 执行退款请求: orderNo={}, amount={}分, approvalId={}, reason={}",
                orderNo, request.getAmount(), request.getApprovalId(), request.getReason());

        RefundResult result = internalService.executeRefund(
                orderNo,
                request.getAmount(),
                request.getApprovalId(),
                request.getReason());

        return Result.ok(result);
    }

    // =========================================================
    // 补偿券接口
    // =========================================================

    /**
     * 发放补偿优惠券（Copilot MCP Server 审批通过后调用）。
     *
     * @param orderNo    关联订单号（用于审计追踪）
     * @param request    补偿请求体
     * @param httpRequest HTTP 请求
     */
    @PostMapping("/orders/{orderNo}/compensate-coupon")
    public Result<CompensateResult> compensateCoupon(
            @PathVariable @NotBlank String orderNo,
            @Valid @RequestBody CompensateRequest request,
            HttpServletRequest httpRequest) {

        verifyInternalKey(httpRequest);

        log.info("[Internal] 发放补偿券: orderNo={}, userId={}, amount={}分, approvalId={}",
                orderNo, request.getUserId(), request.getCompensationAmount(), request.getApprovalId());

        CompensateResult result = internalService.issueCompensationCoupon(
                orderNo,
                request.getUserId(),
                request.getCompensationAmount(),
                request.getApprovalId(),
                request.getReason());

        return Result.ok(result);
    }

    // =========================================================
    // 私有方法
    // =========================================================

    /** 验证内部 API 密钥（简单 Token 验证）。 */
    private void verifyInternalKey(HttpServletRequest request) {
        String key = request.getHeader("X-Internal-Key");
        if (!expectedApiKey.equals(key)) {
            log.warn("[Internal] 非法内部调用，X-Internal-Key 不匹配");
            throw new com.personalprojections.locallife.server.common.exception.BizException(
                    com.personalprojections.locallife.server.common.result.ErrorCode.SYS_BUSY);
        }
    }

    // =========================================================
    // DTO 定义（内联，避免过多文件）
    // =========================================================

    @Data
    public static class RefundRequest {
        @Positive(message = "退款金额必须大于 0")
        private Integer amount;        // 退款金额（分）

        @NotBlank(message = "approvalId 不能为空")
        private String approvalId;    // HITL 审批 ID

        @NotBlank(message = "退款原因不能为空")
        private String reason;        // 退款原因（写入审计和用户通知）
    }

    @Data
    public static class CompensateRequest {
        @NotBlank(message = "userId 不能为空")
        private String userId;

        @Positive(message = "补偿金额必须大于 0")
        private Integer compensationAmount;   // 补偿券面值（分）

        @NotBlank(message = "approvalId 不能为空")
        private String approvalId;

        @NotBlank(message = "原因不能为空")
        private String reason;
    }

    @Data
    public static class RefundResult {
        private String refundNo;
        private String orderNo;
        private Integer refundAmount;
        private String status;

        public static RefundResult of(String refundNo, String orderNo, int amount, String status) {
            RefundResult r = new RefundResult();
            r.setRefundNo(refundNo);
            r.setOrderNo(orderNo);
            r.setRefundAmount(amount);
            r.setStatus(status);
            return r;
        }
    }

    @Data
    public static class CompensateResult {
        private String couponId;
        private String userId;
        private Integer faceValue;
        private String status;

        public static CompensateResult of(String couponId, String userId, int faceValue, String status) {
            CompensateResult r = new CompensateResult();
            r.setCouponId(couponId);
            r.setUserId(userId);
            r.setFaceValue(faceValue);
            r.setStatus(status);
            return r;
        }
    }
}
