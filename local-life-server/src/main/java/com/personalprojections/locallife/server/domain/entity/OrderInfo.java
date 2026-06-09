package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 交易订单主单实体，对应数据库表 {@code order_info}。
 *
 * <h2>命名为 OrderInfo 而不是 Order 的原因</h2>
 * <p>Java 中 {@code Order} 本身没有冲突，但数据库表命名为 {@code order_info}，
 * 是为了避免与 SQL 保留关键字 {@code ORDER} 冲突（MySQL 写 {@code SELECT * FROM order}
 * 会报语法错误，需要写反引号 {@code `order`}）。实体类与表名保持语义一致，命名为 OrderInfo。
 *
 * <h2>订单状态机</h2>
 * <pre>
 *   WAIT_PAY → PAID       （支付回调触发）
 *   WAIT_PAY → CANCELLED  （用户主动取消 / expire_at 到期自动关闭）
 *   PAID     → COMPLETED  （核销确认，当前阶段预留，下一个迭代实现）
 *   CANCELLED / COMPLETED 是终态，不可再流转
 * </pre>
 *
 * <h2>金额字段说明</h2>
 * <p>三个金额字段共同描述一笔交易的金融视图：
 * <ul>
 *   <li>{@code originalAmount}：下单时的原始总价（分）</li>
 *   <li>{@code couponDiscount}：优惠券抵扣（分），0 表示无优惠</li>
 *   <li>{@code orderAmount}：实付金额（分）= originalAmount - couponDiscount</li>
 * </ul>
 * <p>之所以存三个而不是算出来，是因为：
 * <ol>
 *   <li>退款时需要知道原价和抵扣分别是多少</li>
 *   <li>对账时需要与支付单的 paid_amount 核对</li>
 *   <li>营销分析时需要统计优惠券的实际抵扣效果</li>
 * </ol>
 *
 * <h2>expireAt 和延迟关单</h2>
 * <p>创建订单时写入 expireAt = createdAt + 30 分钟。
 * 延迟关单方案（已完成升级：MQ 延时消息为主链路 + 定时任务兜底，详见
 * {@link com.personalprojections.locallife.server.module.order.service.OrderService} 类注释）：
 * <ul>
 *   <li>主链路：下单时投递一条 30 分钟后到达的 RocketMQ 延时消息，
 *       {@code OrderCloseConsumer} 收到后重新查库复检状态，确认仍 WAIT_PAY 且已过期才关闭——
 *       精确到秒，不依赖轮询间隔</li>
 *   <li>兜底：{@code OrderService.closeExpiredOrders()} 定时任务（每 5 分钟）继续扫
 *       {@code WHERE order_status='WAIT_PAY' AND expire_at < NOW()}，
 *       兜住消息丢失/消费失败的极端情况，保证最终一致</li>
 *   <li>双链路共用同一段判断 + 关闭逻辑（{@code OrderService.closeOrderIfExpired}），
 *       不会出现"两条路径各信各的"的口径漂移</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("order_info")
public class OrderInfo {

    /** 订单 ID，雪花算法生成（数据库主键）。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 业务订单号（对外展示的流水号），全局唯一，由应用层生成。
     * 通常用雪花 ID 转 String，与数据库主键不同（主键不对外暴露）。
     * 这是「内部 ID」和「业务单号」分离的常见设计。
     */
    private String orderNo;

    /** 下单用户 ID，逻辑外键 → user.id。 */
    private Long userId;

    /** 门店 ID，逻辑外键 → shop.id。 */
    private Long shopId;

    /**
     * 使用的优惠券模板 ID，可为 NULL（未使用券）。
     * 逻辑外键 → coupon_template.id。
     */
    private Long couponTemplateId;

    /**
     * 使用的用户券实例 ID，可为 NULL（未使用券）。
     * 下单时核销 user_coupon（UNUSED → USED），此字段记录被核销的那张券。
     */
    private Long userCouponId;

    /** 原价金额（分），下单时的原始总价。 */
    private Integer originalAmount;

    /** 优惠券抵扣金额（分），0 表示未使用优惠券。 */
    private Integer couponDiscount;

    /** 实付金额（分）= originalAmount - couponDiscount。 */
    private Integer orderAmount;

    /**
     * 订单状态：WAIT_PAY / PAID / CANCELLED / COMPLETED / REFUNDING。
     * 由 Service 层状态机保护，非法流转抛 ORDER_STATUS_ILLEGAL。
     */
    private String orderStatus;

    /** 买家备注，可为空字符串。 */
    private String remark;

    /**
     * 订单过期时间，到期未支付则自动关闭。
     * = createdAt + 30 分钟（当前固定值，后续可配置）。
     */
    private LocalDateTime expireAt;

    /**
     * 支付成功时间，WAIT_PAY 状态时为 NULL。
     * 由支付回调 Handler 写入，不接受客户端传入。
     */
    private LocalDateTime payAt;

    @TableLogic
    private Integer deleted;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
