package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.*;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * 支付单实体，对应数据库表 {@code payment_order}。
 *
 * <h2>支付单 vs 订单的关系</h2>
 * <p>一笔 order_info 对应 1~N 条 payment_order：
 * <ul>
 *   <li>第一次发起支付 → 创建 payment_order #1（PENDING）</li>
 *   <li>支付超时/失败 → payment_order #1 变 FAILED</li>
 *   <li>用户重新发起支付 → 创建 payment_order #2（PENDING）</li>
 *   <li>支付成功 → payment_order #2 变 SUCCESS，同时更新 order_info 状态为 PAID</li>
 * </ul>
 * <p>这种设计的好处：支付历史完整保留，对账时可以追溯每次支付行为。
 *
 * <h2>支付回调幂等机制</h2>
 * <p>支付渠道（支付宝/微信/Mock）在支付成功后会主动回调我方接口（POST /payments/callback）。
 * 但网络不稳定时，回调可能重复发送（同一笔支付收到 2~3 次回调）。
 * 防重复处理方案：
 * <ol>
 *   <li>数据库唯一索引 {@code uk_payment_channel_trade_no(channel, trade_no)}：
 *       第二次回调 UPDATE 时因唯一索引冲突，直接返回「已处理」（幂等）</li>
 *   <li>UPDATE WHERE pay_status = 'PENDING'：
 *       只有 PENDING 状态的支付单才处理回调，已 SUCCESS 的直接跳过</li>
 * </ol>
 *
 * <h2>验签说明</h2>
 * <p>支付渠道回调时携带签名，服务端重新计算签名并比对。
 * 验签失败说明请求可能被篡改，返回 PAYMENT_VERIFY_FAILED，拒绝处理。
 * 当前 Mock 渠道用固定签名（"mock-sign"），生产时替换为 RSA/MD5 签名逻辑。
 *
 * <h2>金额核对</h2>
 * <p>回调中的 paidAmount 必须 == order_info.orderAmount，否则返回 PAYMENT_AMOUNT_MISMATCH。
 * 这是防止「篡改支付金额」攻击的最后一道防线。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("payment_order")
public class PaymentOrder {

    /** 支付单 ID，雪花算法生成。 */
    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    /**
     * 我方系统的支付流水号，全局唯一，由应用层生成（雪花 ID 字符串）。
     * 发起支付时生成，传给支付渠道作为「商户订单号」。
     */
    private String paymentNo;

    /** 关联的订单 ID，逻辑外键 → order_info.id。 */
    private Long orderId;

    /**
     * 关联的订单号（冗余字段）。
     * 支付回调通常携带「商户订单号」（即 paymentNo），
     * 需要根据 paymentNo 查到 orderId，再查 order_info 做金额核对。
     * 冗余 orderNo 可以在回调日志里直接看到关联的业务单号，排查更方便。
     */
    private String orderNo;

    /** 支付用户 ID，冗余字段，方便快速查用户的支付记录。 */
    private Long userId;

    /** 应付金额（分），= order_info.orderAmount，发起支付时填入。 */
    private Integer payAmount;

    /**
     * 实际支付金额（分），支付回调时由渠道返回，填入此字段。
     * 校验：paidAmount 必须 == payAmount，否则 PAYMENT_AMOUNT_MISMATCH。
     * PENDING / FAILED 状态时为 NULL。
     */
    private Integer paidAmount;

    /**
     * 支付状态：PENDING / SUCCESS / FAILED / REFUNDED。
     * 状态流转由 PaymentService 管理，非法流转记 WARN 日志并拒绝。
     */
    private String payStatus;

    /**
     * 支付渠道：MOCK / ALIPAY / WECHAT。
     * 当前实现：MOCK 渠道（不对接真实支付，面试时说「已预留 Alipay/Wechat 接口」）。
     */
    private String channel;

    /**
     * 支付渠道的交易流水号（渠道侧的唯一 ID）。
     * 支付成功后由回调填入，PENDING/FAILED 时为 NULL。
     * 与 channel 组成唯一键，用于幂等判重。
     */
    private String tradeNo;

    /**
     * 渠道原始回调报文（JSON 字符串），存储完整回调内容。
     * 用途：
     *   1. 排查问题：出现金额争议时，调取原始报文核对
     *   2. 对账：批量导出与渠道账单对比
     *   3. 审计：法务要求保留原始支付凭证
     */
    private String callbackBody;

    /**
     * 支付成功时间，由渠道回调携带（取渠道时间，不用本地时间）。
     * 使用渠道时间的原因：与渠道账单的时间戳保持一致，方便对账。
     */
    private LocalDateTime paidAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
