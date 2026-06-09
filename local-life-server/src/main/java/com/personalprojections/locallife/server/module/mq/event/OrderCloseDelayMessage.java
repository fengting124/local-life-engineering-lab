package com.personalprojections.locallife.server.module.mq.event;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * 订单关闭延时消息体，序列化为 JSON 投递到 RocketMQ Topic {@code order-close-topic}，
 * 携带 {@link com.personalprojections.locallife.server.module.mq.constant.RocketMqDelayLevel#ORDER_CLOSE_DELAY_LEVEL}
 * 延时级别（30 分钟），由 Broker 在到期后准确投递给
 * {@code OrderCloseConsumer}。
 *
 * <h2>为什么只携带 orderId/userId，不携带订单快照</h2>
 * <p>与 {@code SeckillSuccessEvent} 「冗余字段减少回查」的设计取舍相反——
 * 这里特意只携带最小必要信息，让消费者重新查库取最新状态。
 * 原因：本消息存在「发送时刻」与「消费时刻」之间长达 30 分钟的间隔，
 * 这期间订单状态完全可能发生变化（用户已支付/已取消）。
 * 如果携带下单时刻的快照，消费者可能基于过期数据做出错误判断；
 * 重新查库虽然多一次 DB 查询，但保证了「按最新状态决策」的正确性——
 * 这正是 {@code OrderCloseConsumer} 必须「复检状态」的原因。
 *
 * <h2>字段选择</h2>
 * <ul>
 *   <li>{@code orderId}：定位订单</li>
 *   <li>{@code userId}：ShardingSphere 分片键，确保
 *       {@code orderInfoMapper.selectOne}/{@code updateStatusFromWaitPay}
 *       精准路由到目标物理表，避免广播查询全分片</li>
 * </ul>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OrderCloseDelayMessage {

    /** 订单 ID（雪花 ID）。 */
    private Long orderId;

    /** 下单用户 ID，同时也是 ShardingSphere 的分片键。 */
    private Long userId;
}
