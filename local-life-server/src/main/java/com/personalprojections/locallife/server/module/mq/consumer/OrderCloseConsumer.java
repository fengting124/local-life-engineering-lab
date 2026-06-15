package com.personalprojections.locallife.server.module.mq.consumer;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.personalprojections.locallife.server.module.mq.constant.MqTopics;
import com.personalprojections.locallife.server.module.mq.event.OrderCloseDelayMessage;
import com.personalprojections.locallife.server.module.order.service.OrderService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.apache.rocketmq.spring.annotation.RocketMQMessageListener;
import org.apache.rocketmq.spring.core.RocketMQListener;
import org.springframework.stereotype.Service;

/**
 * 订单关闭延时消息消费者：到期后主动触发关单复检（关单的「主链路」）。
 *
 * <h2>对应的架构升级</h2>
 * <p>承接 {@code OrderService} 类注释中标记的「升级方向：RocketMQ 延时消息」目标——
 * 把订单超时关闭从「定时轮询发现」升级为「到期主动触发」，
 * 关单精度从分钟级（轮询间隔）提升到秒级（Broker 精确投递延时消息）。
 * 设计上模仿了 siam-cloud（暹罗外卖，开源 O2O 平台）
 * {@code OrderConsumer + OrderServiceImpl.closeOverdueOrder} 的两段式经典实现：
 * 「按 tag 分发到具体处理方法」+「处理前重新查库复检状态」。
 *
 * <h2>本消费者为什么不需要 Redis SETNX 幂等层（与 PaymentSuccessConsumer 的关键差异）</h2>
 * <p>{@code PaymentSuccessConsumer}/{@code SeckillSuccessConsumer} 之所以需要
 * Redis SETNX 幂等 Key，是因为它们执行的是 <b>INSERT</b>（写入用户券、流水等），
 * 重复执行会产生重复数据，必须在「执行业务逻辑之前」拦截重复消费。
 * <p>而本消费者执行的是 {@code OrderService.handleOrderCloseDelayMessage}——
 * 内部归根结底是一次 {@code WHERE status='WAIT_PAY'} 的 CAS 更新，
 * 业务操作本身就是幂等的：第一次执行让状态从 WAIT_PAY 变为 CANCELLED（affected=1），
 * 后续无论被调用多少次，affected 永远是 0，不会产生任何副作用。
 * <p>额外加一层 Redis 幂等 Key 不会让系统更正确，只会多一次 Redis 往返——
 * <b>幂等保障应该长在离「写操作」最近的地方（这里是 DB 的 CAS 更新），
 * 而不是机械地给每个消费者套同一个模板</b>，这正是判断「什么时候需要幂等层、
 * 什么时候不需要」的核心依据。
 *
 * <h2>消费失败与重试</h2>
 * <p>方法抛出异常 → RocketMQ 标记消费失败 → 自动重试（默认 16 次，间隔递增）。
 * 16 次全部失败 → 进入死信 Topic：{@code %DLQ%order-close-consumer-group}。
 * 即使消息最终进入死信队列彻底丢失，{@code OrderService.closeExpiredOrders()}
 * 兜底任务仍会按 expire_at 扫到这笔订单并关闭——不会造成「订单永远关不掉」的后果，
 * 这也是本消息允许 best-effort 发送、消费失败无需特殊补偿的底气所在。
 */
@Slf4j
@Service
@RequiredArgsConstructor
@RocketMQMessageListener(
        topic = MqTopics.ORDER_CLOSE_TOPIC,
        selectorExpression = MqTopics.TAG_ORDER_CLOSE_NOTIFY,
        consumerGroup = "order-close-consumer-group"
)
public class OrderCloseConsumer implements RocketMQListener<String> {

    private final ObjectMapper objectMapper;
    private final OrderService orderService;

    /**
     * 消费订单关闭延时消息（消息体为 JSON 字符串）。
     *
     * @param messageBody 消息体 JSON 字符串，反序列化为 {@link OrderCloseDelayMessage}
     */
    @Override
    public void onMessage(String messageBody) {
        OrderCloseDelayMessage message;
        try {
            message = objectMapper.readValue(messageBody, OrderCloseDelayMessage.class);
        } catch (Exception e) {
            // JSON 解析失败：消息格式 bug，重试也无意义，记录后直接 ACK，避免无限重试
            log.error("[OrderCloseConsumer] 消息体解析失败，忽略消息: body={}", messageBody, e);
            return;
        }

        log.info("[OrderCloseConsumer] 收到订单关闭延时消息（到期复检）: orderId={}, userId={}",
                message.getOrderId(), message.getUserId());

        // 把「是否真的要关、怎么关」完全交给 OrderService——
        // 消费者只负责「消息分发」，业务判断和操作收敛在 Service 层，
        // 与 closeExpiredOrders() 共用同一套关单逻辑（详见 OrderService.closeOrderIfExpired）
        orderService.handleOrderCloseDelayMessage(message.getOrderId(), message.getUserId());
    }
}
