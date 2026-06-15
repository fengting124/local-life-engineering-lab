package com.personalprojections.locallife.copilot.rbac;

import jakarta.servlet.Filter;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletRequest;
import jakarta.servlet.ServletResponse;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import lombok.extern.slf4j.Slf4j;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.util.StringUtils;

import java.io.IOException;

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
 * </pre>
 *
 * <h2>信任边界</h2>
 * <p>这些 Header 由 Python Agent Service 在转发 MCP 请求时设置。
 * Agent Service 负责验证用户 JWT Token，并从 Token 中提取 userId，
 * 再从数据库查 merchantId，然后设置这些 Header。
 * MCP Server 信任这些 Header（走内网调用，不对外暴露端口）。
 *
 * <h2>安全说明</h2>
 * <p>生产环境需要：
 * <ol>
 *   <li>MCP Server 端口不对外暴露（只允许 Agent Service 内网访问）</li>
 *   <li>或在 Header 中加 HMAC 签名，MCP Server 验签</li>
 * </ol>
 * 当前开发阶段信任来自 Agent Service 的 Header。
 */
@Slf4j
@Component
@Order(1)
public class RbacFilter implements Filter {

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
}
