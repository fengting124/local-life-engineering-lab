package com.personalprojections.locallife.server.module.mq.constant;

/**
 * RocketMQ 延时消息级别常量。
 *
 * <h2>面试高频考点：开源版 RocketMQ 不支持任意精度的延时</h2>
 * <p>开源版 RocketMQ（4.x，本项目使用的版本）的延时消息只能从 Broker 预设的
 * 18 个固定档位中选择，{@code Message.setDelayTimeLevel(int level)} 传入的是
 * <b>档位序号</b>而不是秒数。档位与时长的对应关系由 Broker 配置
 * {@code messageDelayLevel}（默认如下）决定，所有 Producer/Consumer 共享同一套：
 * <pre>
 *   级别:  1   2   3    4    5   6   7   8   9   10  11  12  13  14   15   16   17  18
 *   时长: 1s  5s  10s  30s  1m  2m  3m  4m  5m  6m  7m  8m  9m  10m  20m  30m  1h  2h
 * </pre>
 * <p>这意味着如果业务需要的延时不在这 18 档之内（如 23 分钟），只能向上取最接近的档位，
 * 存在「精度损失」——这是开源版 RocketMQ 的已知限制（商业版 RocketMQ 5.x
 * 才支持 {@code MessageConst.PROPERTY_TIMER_DELIVER_MS} 任意时间戳级别的精确延时）。
 *
 * <p>本项目订单超时时间 {@code ORDER_EXPIRE_MINUTES = 30}，恰好对应
 * {@link #ORDER_CLOSE_DELAY_LEVEL}（30 分钟整），不存在精度损失，是有意选择的「整数对齐」。
 *
 * <h2>集中管理的原因</h2>
 * <p>与 {@link MqTopics} 同样的考虑：延时级别是「魔法数字」，分散在代码里既难懂又难改，
 * 集中到此类并写明对应表，调用方只需引用语义化常量（如 {@link #ORDER_CLOSE_DELAY_LEVEL}）。
 */
public final class RocketMqDelayLevel {

    /** 工具类，禁止实例化。 */
    private RocketMqDelayLevel() {}

    // =========================================================
    // Broker 预设的 18 个延时档位（序号即 setDelayTimeLevel 的入参）
    // =========================================================

    public static final int LEVEL_1S = 1;
    public static final int LEVEL_5S = 2;
    public static final int LEVEL_10S = 3;
    public static final int LEVEL_30S = 4;
    public static final int LEVEL_1M = 5;
    public static final int LEVEL_2M = 6;
    public static final int LEVEL_3M = 7;
    public static final int LEVEL_4M = 8;
    public static final int LEVEL_5M = 9;
    public static final int LEVEL_6M = 10;
    public static final int LEVEL_7M = 11;
    public static final int LEVEL_8M = 12;
    public static final int LEVEL_9M = 13;
    public static final int LEVEL_10M = 14;
    public static final int LEVEL_20M = 15;
    public static final int LEVEL_30M = 16;
    public static final int LEVEL_1H = 17;
    public static final int LEVEL_2H = 18;

    // =========================================================
    // 业务语义常量：按使用场景命名，调用方不用记档位表
    // =========================================================

    /**
     * 订单关闭延时消息的档位：{@link #LEVEL_30M}（30 分钟）。
     *
     * <p>对应 {@code OrderService.ORDER_EXPIRE_MINUTES = 30}：下单时投递一条
     * 携带此档位的延时消息，Broker 在 30 分钟后准确投递给
     * {@code OrderCloseConsumer}，由其复检订单状态后决定是否关闭。
     *
     * <p>选择 30 分钟而非「29 分钟」「31 分钟」等更贴近业务直觉的数字，
     * 正是为了与开源版 RocketMQ 的固定档位对齐，避免引入精度损失。
     */
    public static final int ORDER_CLOSE_DELAY_LEVEL = LEVEL_30M;
}
