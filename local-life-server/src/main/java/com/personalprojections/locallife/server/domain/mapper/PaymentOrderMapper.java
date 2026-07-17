package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.PaymentOrder;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

import java.time.LocalDateTime;

/** Payment order state transitions. */
@Mapper
public interface PaymentOrderMapper extends BaseMapper<PaymentOrder> {

    @Select("SELECT * FROM payment_order WHERE payment_no = #{paymentNo}")
    PaymentOrder selectByPaymentNo(@Param("paymentNo") String paymentNo);

    /** Claims one external success callback for a PENDING payment order. */
    @Update("UPDATE payment_order " +
            "SET pay_status = 'SUCCESS', trade_no = #{tradeNo}, paid_amount = #{paidAmount}, " +
            "callback_body = #{callbackBody}, paid_at = #{paidAt}, updated_at = NOW() " +
            "WHERE id = #{paymentOrderId} AND pay_status = 'PENDING'")
    int updateStatusOnSuccess(@Param("paymentOrderId") Long paymentOrderId,
                              @Param("tradeNo") String tradeNo,
                              @Param("paidAmount") Integer paidAmount,
                              @Param("callbackBody") String callbackBody,
                              @Param("paidAt") LocalDateTime paidAt);

    /**
     * Reclassifies a real external success when another payment already won the order CAS.
     * The callback facts remain stored, while downstream normal payment-success processing is blocked.
     */
    @Update("UPDATE payment_order SET pay_status = 'DUPLICATE_PAID', updated_at = NOW() " +
            "WHERE id = #{paymentOrderId} AND pay_status = 'SUCCESS'")
    int markAsDuplicatePaid(@Param("paymentOrderId") Long paymentOrderId);
}
