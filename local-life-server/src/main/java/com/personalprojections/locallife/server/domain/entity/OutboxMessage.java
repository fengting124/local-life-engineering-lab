package com.personalprojections.locallife.server.domain.entity;

import com.baomidou.mybatisplus.annotation.FieldFill;
import com.baomidou.mybatisplus.annotation.IdType;
import com.baomidou.mybatisplus.annotation.TableField;
import com.baomidou.mybatisplus.annotation.TableId;
import com.baomidou.mybatisplus.annotation.TableName;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Transactional Outbox message.
 *
 * <p>Relay delivery is at least once. A worker first claims a row as PROCESSING in a short
 * database transaction, performs MQ I/O after the transaction commits, then completes the row
 * with the same worker id. An expired lease can be recovered by another instance.</p>
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@TableName("outbox_message")
public class OutboxMessage {

    @TableId(type = IdType.ASSIGN_ID)
    private Long id;

    private String eventId;

    private String topic;

    private String tag;

    private String payload;

    /** PENDING / PROCESSING / SENT / FAILED. */
    private String status;

    /** Owner of the current PROCESSING lease. */
    private String workerId;

    /** Expiration time of the current PROCESSING lease. */
    private LocalDateTime leaseUntil;

    /** Time at which the current worker claimed the row. */
    private LocalDateTime claimedAt;

    private Integer retryCount;

    private Integer autoRetryCount;

    private LocalDateTime nextRetryAt;

    @TableField(fill = FieldFill.INSERT)
    private LocalDateTime createdAt;

    @TableField(fill = FieldFill.INSERT_UPDATE)
    private LocalDateTime updatedAt;
}
