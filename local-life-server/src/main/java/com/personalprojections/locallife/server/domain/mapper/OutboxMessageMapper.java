package com.personalprojections.locallife.server.domain.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.personalprojections.locallife.server.domain.entity.OutboxMessage;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Update;

/**
 * 本地消息表 Mapper。
 *
 * <h2>自定义方法说明</h2>
 * <p>{@code markAsSent} 使用乐观更新（WHERE status='PENDING'），防止并发 Relay 任务重复处理：
 * <ul>
 *   <li>多实例部署时，多个节点可能同时扫描到同一条 PENDING 消息</li>
 *   <li>通过 WHERE status='PENDING' + UPDATE 原子性，确保只有一个节点成功标记为 SENT</li>
 *   <li>affected = 0 的节点直接跳过（消息已被其他节点处理）</li>
 * </ul>
 *
 * <p>生产级升级：用 SELECT ... FOR UPDATE 或 Redis 分布式锁做更严格的互斥，
 * 或者使用 XXL-Job 的分片广播确保每条消息只被一个节点处理。
 */
@Mapper
public interface OutboxMessageMapper extends BaseMapper<OutboxMessage> {

    /**
     * 将 PENDING 消息原子性地标记为 SENT（投递成功）。
     *
     * <p>WHERE status='PENDING' 防止多实例并发重复处理：
     * 第一个节点 affected=1（成功标记），第二个节点 affected=0（已处理，跳过）。
     *
     * @param id 消息 ID
     * @return 受影响行数：1=成功，0=已被处理（幂等跳过）
     */
    @Update("UPDATE outbox_message SET status = 'SENT', updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'PENDING'")
    int markAsSent(@Param("id") Long id);

    /**
     * 投递失败：重试次数 +1，更新下次重试时间（指数退避），超限标记 FAILED。
     *
     * <p>指数退避策略（在 OutboxService 中计算 nextRetryAt 后传入）。
     * 此方法只做 DB 更新，退避逻辑在 Service 层。
     *
     * @param id          消息 ID
     * @param newStatus   新状态（PENDING=还能重试 / FAILED=超限）
     * @param nextRetryAt 下次重试时间
     * @return 受影响行数
     */
    @Update("UPDATE outbox_message SET status = #{newStatus}, retry_count = retry_count + 1, " +
            "next_retry_at = #{nextRetryAt}, updated_at = NOW() WHERE id = #{id}")
    int markAsRetry(@Param("id") Long id,
                    @Param("newStatus") String newStatus,
                    @Param("nextRetryAt") java.time.LocalDateTime nextRetryAt);

    /**
     * 将 FAILED 消息重置为 PENDING，用于死信自动恢复。
     *
     * <p>操作语义：
     * <ul>
     *   <li>status = 'PENDING'：让 Relay 任务重新投递</li>
     *   <li>retry_count = 0：归零，让消息再次享有 3 次 Relay 重试机会</li>
     *   <li>auto_retry_count = currentAutoRetryCount + 1：记录自动恢复次数</li>
     *   <li>next_retry_at = NOW()：立即可投递</li>
     * </ul>
     *
     * <p>WHERE status = 'FAILED'：防止并发场景下重复重置（幂等保护）。
     *
     * @param id                   消息 ID
     * @param currentAutoRetryCount 当前自动恢复次数（自增前的值）
     * @return 受影响行数：1=成功，0=消息已不是 FAILED 状态（幂等跳过）
     */
    @Update("UPDATE outbox_message " +
            "SET status = 'PENDING', retry_count = 0, " +
            "auto_retry_count = #{currentAutoRetryCount} + 1, " +
            "next_retry_at = NOW(), updated_at = NOW() " +
            "WHERE id = #{id} AND status = 'FAILED'")
    int resetFailedMessageForAutoRecovery(@Param("id") Long id,
                                          @Param("currentAutoRetryCount") int currentAutoRetryCount);
}
