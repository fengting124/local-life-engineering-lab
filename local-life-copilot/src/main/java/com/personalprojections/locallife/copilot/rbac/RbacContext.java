package com.personalprojections.locallife.copilot.rbac;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * RBAC 身份上下文（线程本地存储）。
 *
 * <p>Python Agent Service 在调用 MCP 工具前，将用户身份信息放入 HTTP Header：
 * <pre>
 *   X-User-Id:     10001
 *   X-User-Role:   merchant
 *   X-Merchant-Id: 20001
 * </pre>
 *
 * <p>MCP Server 的 {@link RbacFilter} 拦截请求，解析 Header 后填充此对象，
 * 存入 {@code ThreadLocal}。工具实现通过 {@link RbacContext#get()} 获取调用者身份。
 *
 * <h2>三个角色的权限范围</h2>
 * <table border="1">
 *   <tr><th>角色</th><th>权限</th></tr>
 *   <tr><td>merchant</td><td>只能查询自己门店的数据（merchant_id 过滤）</td></tr>
 *   <tr><td>cs</td><td>可查询所有商家数据，写操作需 HITL</td></tr>
 *   <tr><td>admin</td><td>全量查询，高风险写操作仍需审计</td></tr>
 * </table>
 *
 * <h2>核心安全约定</h2>
 * <p>Agent 不能自行生成或覆盖 {@code merchantId}。
 * 服务端根据登录态（JWT → userId → DB 查询 merchantId）注入，防止权限越界。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class RbacContext {

    /** 当前调用者的用户 ID */
    private Long userId;

    /** 当前调用者的角色：merchant / cs / admin */
    private String role;

    /**
     * 当前调用者的商家 ID。
     * merchant 角色时必填，工具查询时强制过滤此 merchantId 的数据。
     * cs / admin 角色时为 null（可跨商家查询）。
     */
    private Long merchantId;

    // =========================================================
    // ThreadLocal 管理
    // =========================================================

    private static final ThreadLocal<RbacContext> HOLDER = new ThreadLocal<>();

    /** 将身份上下文绑定到当前线程。由 RbacFilter 在请求开始时调用。 */
    public static void set(RbacContext ctx) {
        HOLDER.set(ctx);
    }

    /** 获取当前线程的身份上下文。工具实现在此获取调用者信息做权限过滤。 */
    public static RbacContext get() {
        return HOLDER.get();
    }

    /** 清理当前线程的上下文。由 RbacFilter 在请求结束时调用（防止内存泄漏）。 */
    public static void clear() {
        HOLDER.remove();
    }

    // =========================================================
    // 便捷判断方法
    // =========================================================

    public boolean isMerchant() { return "merchant".equals(role); }
    public boolean isCs()       { return "cs".equals(role); }
    public boolean isAdmin()    { return "admin".equals(role); }

    /**
     * 检查调用者是否有权限查询指定商家的数据。
     * merchant 只能查自己，cs / admin 可查所有。
     */
    public boolean canAccessMerchant(Long targetMerchantId) {
        if (isAdmin() || isCs()) return true;
        return merchantId != null && merchantId.equals(targetMerchantId);
    }
}
