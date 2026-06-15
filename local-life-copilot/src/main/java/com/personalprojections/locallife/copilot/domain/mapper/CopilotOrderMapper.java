package com.personalprojections.locallife.copilot.domain.mapper;

import com.personalprojections.locallife.copilot.domain.dto.OrderSnapshot;
import com.personalprojections.locallife.copilot.domain.dto.OutboxMessageSnapshot;
import com.personalprojections.locallife.copilot.domain.dto.PaymentSnapshot;
import com.personalprojections.locallife.copilot.domain.dto.ShopMetricsSnapshot;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

import java.util.List;

/**
 * Copilot 专用只读 Order Mapper。
 *
 * <p>MCP 工具需要查询订单、支付、券数据，但不能修改业务数据。
 * 此 Mapper 只声明 SELECT 操作，且映射到与 LocalLife Server 相同的表（共享 DB）。
 *
 * <p>返回 Copilot 自己定义的只读 DTO，而不是 LocalLife 主服务领域实体。
 * 这样既避免跨模块复用实体造成强耦合，又比 {@code Map<String, Object>} 更类型安全。
 *
 * <h2>为什么不复用 LocalLife 的 Mapper</h2>
 * <p>两个模块是独立的 Spring Boot 应用，无法跨 JVM 注入 Bean。
 * 共享同一个 MySQL 实例，但各自维护自己的 Mapper 定义。
 * Copilot 侧只读，不修改业务数据，符合最小权限原则。
 */
@Mapper
public interface CopilotOrderMapper {

    /**
     * 查询订单完整信息（含最新支付单和券状态）。
     *
     * <p>返回字段：
     * <ul>
     *   <li>order_status / order_no / order_amount / coupon_discount / shop_id / user_id</li>
     *   <li>pay_status（最新支付单状态）/ trade_no（渠道流水）/ paid_at</li>
     *   <li>coupon_status（用券状态：UNUSED / USED / EXPIRED / null=未用券）</li>
     * </ul>
     *
     * @param orderNo 订单号
     * @return 订单聚合信息，不存在时返回 null
     */
    @Select("""
            SELECT
                o.id             AS order_id,
                o.order_no,
                o.user_id,
                o.shop_id,
                o.original_amount,
                o.coupon_discount,
                o.order_amount,
                o.order_status,
                o.expire_at,
                o.pay_at,
                o.created_at     AS order_created_at,
                p.pay_status,
                p.channel,
                p.trade_no,
                p.paid_amount,
                uc.coupon_status,
                uc.expire_at     AS coupon_expire_at,
                ct.coupon_name,
                ct.discount_type,
                ct.discount_value
            FROM order_info o
            LEFT JOIN payment_order p
                ON p.order_id = o.id
                AND p.id = (
                    SELECT MAX(pp.id) FROM payment_order pp WHERE pp.order_id = o.id
                )
            LEFT JOIN user_coupon uc ON uc.id = o.user_coupon_id
            LEFT JOIN coupon_template ct ON ct.id = uc.coupon_template_id
            WHERE o.order_no = #{orderNo}
              AND o.deleted = 0
            """)
    OrderSnapshot selectOrderByOrderNo(@Param("orderNo") String orderNo);

    /**
     * 按订单 ID 查询（PaymentService 回调场景用，支持 user_id 精准路由）。
     *
     * @param orderId 订单 ID
     * @return 订单聚合信息
     */
    @Select("""
            SELECT
                o.id AS order_id, o.order_no, o.user_id, o.shop_id,
                o.order_amount, o.order_status, o.pay_at,
                p.pay_status, p.channel, p.trade_no, p.paid_amount
            FROM order_info o
            LEFT JOIN payment_order p ON p.order_id = o.id
                AND p.id = (SELECT MAX(pp.id) FROM payment_order pp WHERE pp.order_id = o.id)
            WHERE o.id = #{orderId} AND o.deleted = 0
            """)
    OrderSnapshot selectOrderById(@Param("orderId") Long orderId);

    /**
     * 查询门店经营数据（GMV / 订单数 / 退款数 / 券核销数）。
     *
     * @param merchantId 商家 ID（用于过滤本商家门店）
     * @param date       查询日期（yyyy-MM-dd）
     * @param shopId     指定门店 ID（null 时查该商家所有门店汇总）
     * @return 经营汇总数据
     */
    @Select("""
            SELECT
                COUNT(*) AS order_count,
                COALESCE(SUM(o.order_amount), 0) AS gmv,
                COALESCE(SUM(CASE WHEN o.order_status = 'CANCELLED' THEN 1 ELSE 0 END), 0) AS cancel_count,
                COALESCE(SUM(CASE WHEN o.coupon_discount > 0 THEN 1 ELSE 0 END), 0) AS coupon_used_count,
                COALESCE(SUM(o.coupon_discount), 0) AS total_coupon_discount
            FROM order_info o
            JOIN shop s ON s.id = o.shop_id AND s.merchant_id = #{merchantId}
            WHERE DATE(o.created_at) = #{date}
              AND o.order_status IN ('PAID', 'COMPLETED')
              AND o.deleted = 0
              AND (#{shopId} IS NULL OR o.shop_id = #{shopId})
            """)
    ShopMetricsSnapshot selectShopMetrics(
            @Param("merchantId") Long merchantId,
            @Param("date") String date,
            @Param("shopId") Long shopId);

    /**
     * 查询 Outbox 消息状态（用于分析券发放 MQ 是否失败）。
     *
     * @param orderId 订单 ID（拼接到 eventId 前缀过滤）
     * @return outbox_message 记录
     */
    @Select("""
            SELECT event_id, topic, tag, status, retry_count, payload,
                   next_retry_at, created_at
            FROM outbox_message
            WHERE event_id LIKE CONCAT(#{orderId}, '%')
            ORDER BY created_at DESC
            LIMIT 5
            """)
    List<OutboxMessageSnapshot> selectOutboxByOrderId(@Param("orderId") String orderId);

    /**
     * 查询支付单列表（按订单 ID，倒序）。
     *
     * @param orderId 订单 ID
     * @return 该订单所有支付单列表
     */
    @Select("""
            SELECT id AS payment_id, payment_no, pay_status, channel,
                   trade_no, pay_amount, paid_amount, paid_at, created_at
            FROM payment_order
            WHERE order_id = #{orderId}
            ORDER BY created_at DESC
            """)
    List<PaymentSnapshot> selectPaymentsByOrderId(@Param("orderId") Long orderId);
}
