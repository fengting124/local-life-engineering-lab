package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.OrderInfo;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 订单 Mapper，继承 MyBatis-Plus 的 BaseMapper，自动获得 CRUD 方法。
 *
 * <h2>自定义 SQL 的必要性</h2>
 * <p>BaseMapper 提供了 updateById，但「订单状态机」需要在 UPDATE 时加 WHERE 条件：
 * {@code WHERE order_status = 'WAIT_PAY'}，以实现：
 * <ol>
 *   <li>并发安全：多个线程同时取消同一订单，只有一个 UPDATE 成功（affected = 1），
 *       其余 affected = 0 直接被服务层捕获并返回 ORDER_STATUS_ILLEGAL。</li>
 *   <li>状态机保护：防止将非 WAIT_PAY 状态的订单更新为 CANCELLED。</li>
 * </ol>
 *
 * <h2>为什么不用 updateById + 业务层校验</h2>
 * <p>如果先 SELECT 状态，再 UPDATE，两步之间存在「先读后写」竞争条件。
 * 即使加乐观锁注解，逻辑也更复杂。
 * 直接在 UPDATE WHERE 中加状态条件，用数据库的原子性保证并发安全，更简洁。
 */
@Mapper
public interface OrderInfoMapper extends BaseMapper<OrderInfo> {

    /**
     * 将订单从 WAIT_PAY 状态原子性地更新为目标状态。
     *
     * <p>WHERE 条件包含 {@code order_status = 'WAIT_PAY'}，确保：
     * <ul>
     *   <li>只有待支付状态的订单才能被修改（状态机约束）</li>
     *   <li>并发下多个更新只有一个成功（数据库原子保证）</li>
     * </ul>
     *
     * <p>使用方式：
     * <pre>{@code
     *   int affected = orderInfoMapper.updateStatusFromWaitPay(orderId, "CANCELLED");
     *   if (affected == 0) {
     *       throw new BizException(ErrorCode.ORDER_STATUS_ILLEGAL);
     *   }
     * }</pre>
     *
     * @param orderId       订单 ID
     * @param targetStatus  目标状态（"CANCELLED" 或其他从 WAIT_PAY 流转的状态）
     * @return              受影响行数：1 = 更新成功，0 = 状态不符合或记录不存在
     */
    @Update("UPDATE order_info SET order_status = #{targetStatus}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND order_status = 'WAIT_PAY' AND deleted = 0")
    int updateStatusFromWaitPay(@Param("orderId") Long orderId,
                                @Param("targetStatus") String targetStatus);

    /**
     * 将订单从 WAIT_PAY 状态更新为 PAID，同时写入支付成功时间。
     *
     * <p>支付成功是特殊的状态流转，需要额外写入 pay_at 字段。
     * 使用渠道时间（非数据库 NOW()），所以用参数传入。
     * 这样与支付渠道的账单时间保持一致，方便对账。
     *
     * <p>同样加 WHERE order_status = 'WAIT_PAY'，防止重复回调重复处理：
     * 第一次回调更新成功（affected=1），第二次回调时状态已是 PAID，affected=0，
     * 服务层直接返回「已处理」（幂等）。
     *
     * @param orderId   订单 ID
     * @param payAt     支付成功时间（从渠道回调中获取）
     * @return          受影响行数：1 = 成功，0 = 状态不符（已处理或已关闭）
     */
    @Update("UPDATE order_info SET order_status = 'PAID', pay_at = #{payAt}, updated_at = NOW() " +
            "WHERE id = #{orderId} AND order_status = 'WAIT_PAY' AND deleted = 0")
    int markAsPaid(@Param("orderId") Long orderId,
                   @Param("payAt") java.time.LocalDateTime payAt);
}
