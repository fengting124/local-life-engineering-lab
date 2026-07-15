package com.personalprojections.locallife.copilot.rbac;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * RBAC 身份注入过滤器。
 *
 * <p>从 HTTP Header 解析身份信息，填充到 {@link RbacContext}（ThreadLocal）。
 * 所有 MCP 工具调用都依赖此上下文做权限过滤。
 *
 * <h2>Header 约定</h2>
 * <pre>
 *   X-User-Id:     10001       （必填，Long 型用户 ID）
 *   X-User-Role:   merchant    （必填，角色：merchant / cs / admin）
 *   X-Merchant-Id: 20001       （merchant 角色时必填）
 *   X-Agent-Timestamp: 1710000000 （必填，Epoch 秒）
 *   X-Agent-Signature: HMAC-SHA256(userId + "\n" + role + "\n" + merchantId + "\n" + timestamp)
 * </pre>
 *
 * <h2>信任边界</h2>
 * <p>这些 Header 由 Python Agent Service 在转发 MCP 请求时设置。
 * Agent Service 负责验证用户 JWT Token，并从 Token 中提取 userId，
 * 再从数据库查 merchantId，然后设置这些 Header。MCP Server 使用共享密钥
 * 校验 HMAC 签名，避免外部请求伪造 {@code X-User-*}。
 *
 * <h2>安全说明</h2>
 * <p>生产环境需要：
 * <ol>
 *   <li>MCP Server 端口不对外暴露（只允许 Agent Service 内网访问）</li>
 *   <li>共享密钥走环境变量/Secret Manager，不进入代码仓库</li>
 *   <li>进一步升级可加 mTLS，让网络层也确认调用方身份</li>
 * </ol>
 */
@Slf4j
@Component
@Order(1)
public class RbacFilter implements Filter {

    private String contextSigningSecret = "local-life-mcp-context-secret";
    private long maxClockSkewSeconds = 300;

    @Value("${mcp.context-signing.secret:local-life-mcp-context-secret}")
    void setContextSigningSecret(String contextSigningSecret) {
        this.contextSigningSecret = contextSigningSecret;
    }

    @Value("${mcp.context-signing.max-clock-skew-seconds:300}")
    void setMaxClockSkewSeconds(long maxClockSkewSeconds) {
        this.maxClockSkewSeconds = maxClockSkewSeconds;
    }

    @Override
    public void doFilter(ServletRequest req, ServletResponse resp, FilterChain chain)
            throws IOException, jakarta.servlet.ServletException {
        HttpServletRequest request = (HttpServletRequest) req;
        HttpServletResponse response = (HttpServletResponse) resp;

        try {
            // 解析身份 Header
            String userIdStr   = request.getHeader("X-User-Id");
            String role        = request.getHeader("X-User-Role");
            String merchantStr = request.getHeader("X-Merchant-Id");
            String timestamp   = request.getHeader("X-Agent-Timestamp");
            String signature   = request.getHeader("X-Agent-Signature");

            // 健康检查、Swagger 文档等非 MCP 端点跳过身份校验
            String path = request.getRequestURI();
            if (isPublicEndpoint(path)) {
                chain.doFilter(req, resp);
                return;
            }

            // /mcp 端点必须有身份信息
            if (!StringUtils.hasText(userIdStr) || !StringUtils.hasText(role)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"missing X-User-Id or X-User-Role header\"}");
                return;
            }

            if (!hasValidAgentSignature(userIdStr, role, merchantStr, timestamp, signature)) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"invalid agent identity signature\"}");
                return;
            }

            Long userId;
            Long merchantId;
            try {
                userId     = Long.parseLong(userIdStr);
                merchantId = StringUtils.hasText(merchantStr) ? Long.parseLong(merchantStr) : null;
            } catch (NumberFormatException e) {
                response.setStatus(HttpServletResponse.SC_UNAUTHORIZED);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"X-User-Id and X-Merchant-Id must be numeric\"}");
                return;
            }

            // merchant 角色必须提供 merchantId，防止权限越界
            if ("merchant".equals(role) && merchantId == null) {
                response.setStatus(HttpServletResponse.SC_FORBIDDEN);
                response.setContentType("application/json;charset=UTF-8");
                response.getWriter().write("{\"error\":\"merchant role requires X-Merchant-Id header\"}");
                return;
            }

            // 绑定到 ThreadLocal
            RbacContext ctx = RbacContext.builder()
                    .userId(userId)
                    .role(role)
                    .merchantId(merchantId)
                    .build();
            RbacContext.set(ctx);

            log.debug("[RBAC] 身份注入: userId={}, role={}, merchantId={}", userId, role, merchantId);
            chain.doFilter(req, resp);

        } finally {
            // 必须清理，防止线程池复用时 ThreadLocal 污染
            RbacContext.clear();
        }
    }

    private boolean isPublicEndpoint(String path) {
        return path.startsWith("/actuator")
                || path.equals("/swagger-ui.html")
                || path.startsWith("/swagger-ui/")
                || path.startsWith("/v3/api-docs");
    }

    private boolean hasValidAgentSignature(
            String userId,
            String role,
            String merchantId,
            String timestamp,
            String signature
    ) {
        if (!StringUtils.hasText(timestamp) || !StringUtils.hasText(signature)) {
            return false;
        }
        long requestEpochSeconds;
        try {
            requestEpochSeconds = Long.parseLong(timestamp);
        } catch (NumberFormatException e) {
            return false;
        }
        long nowEpochSeconds = System.currentTimeMillis() / 1000;
        if (Math.abs(nowEpochSeconds - requestEpochSeconds) > maxClockSkewSeconds) {
            return false;
        }

        String canonical = userId + "\n" + role + "\n"
                + (StringUtils.hasText(merchantId) ? merchantId : "") + "\n" + timestamp;
        String expected = hmacSha256Hex(canonical);
        return constantTimeEquals(expected, signature);
    }

    private String hmacSha256Hex(String canonical) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(contextSigningSecret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            byte[] digest = mac.doFinal(canonical.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException("failed to sign MCP identity context", e);
        }
    }

    private boolean constantTimeEquals(String expected, String actual) {
        byte[] expectedBytes = expected.getBytes(StandardCharsets.UTF_8);
        byte[] actualBytes = actual.getBytes(StandardCharsets.UTF_8);
        if (expectedBytes.length != actualBytes.length) {
            return false;
        }
        int result = 0;
        for (int i = 0; i < expectedBytes.length; i++) {
            result |= expectedBytes[i] ^ actualBytes[i];
        }
        return result == 0;
    }
}
