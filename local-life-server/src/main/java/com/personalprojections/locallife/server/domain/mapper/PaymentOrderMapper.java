package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.PaymentOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/**
 * 支付单 Mapper，继承 MyBatis-Plus BaseMapper，提供基础 CRUD。
 *
 * <h2>支付回调处理的并发场景</h2>
 * <p>支付渠道（如支付宝）可能因网络原因，对同一笔支付发送 2~3 次回调。
 * 如果不做幂等处理，同一笔支付会被记录两次，导致重复到账。
 *
 * <p>防重复方案（双层保护）：
 * <ol>
 *   <li>数据库唯一索引 {@code uk_payment_channel_trade_no(channel, trade_no)}：
 *       任何时候同一渠道+交易号只能有一条记录，INSERT 时天然防重。</li>
 *   <li>{@code updateStatusOnSuccess} 的 WHERE 条件加 {@code pay_status = 'PENDING'}：
 *       只有 PENDING 状态才能成功更新，已 SUCCESS 的订单直接返回 0（幂等跳过）。</li>
 * </ol>
 */
@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {

    /**
     * 根据支付流水号（我方系统生成）查询支付单。
     *
     * <p>支付回调时渠道会携带我方的「商户订单号」（即 paymentNo），
     * 通过此接口找到对应的支付单，再校验金额和签名。
     *
     * @param paymentNo 支付流水号（应用层生成的雪花 ID 字符串）
     * @return 对应的支付单，不存在返回 null
     */
    @Select("SELECT * FROM payment_order WHERE payment_no = #{paymentNo}")
    PaymentOrder selectByPaymentNo(@Param("paymentNo") String paymentNo);

    /**
     * 将 PENDING 状态的支付单原子性地更新为 SUCCESS，同时写入渠道信息。
     *
     * <p>WHERE 条件 {@code pay_status = 'PENDING'} 确保幂等性：
     * <ul>
     *   <li>第一次回调：affected = 1，正常处理</li>
     *   <li>第二次重复回调：状态已是 SUCCESS，WHERE 不命中，affected = 0，
     *       服务层检测到 0 直接返回「已处理」，不再重复更新</li>
     * </ul>
     *
     * @param paymentOrderId 支付单 ID
     * @param tradeNo        渠道交易流水号（支付宝 / 微信的唯一单号）
     * @param paidAmount     渠道实际到账金额（分）
     * @param callbackBody   渠道原始回调报文（JSON 字符串），保留用于审计
     * @param paidAt         渠道返回的支付成功时间（非本地时间，与账单保持一致）
     * @return               受影响行数：1 = 成功，0 = 已处理（幂等跳过）
     */
    @Update("UPDATE payment_order " +
            "SET pay_status = 'SUCCESS', " +
            "    trade_no = #{tradeNo}, " +
            "    paid_amount = #{paidAmount}, " +
            "    callback_body = #{callbackBody}, " +
            "    paid_at = #{paidAt}, " +
            "    updated_at = NOW() " +
            "WHERE id = #{paymentOrderId} AND pay_status = 'PENDING'")
    int updateStatusOnSuccess(@Param("paymentOrderId") Long paymentOrderId,
                              @Param("tradeNo") String tradeNo,
                              @Param("paidAmount") Integer paidAmount,
                              @Param("callbackBody") String callbackBody,
                              @Param("paidAt") LocalDateTime paidAt);
}
