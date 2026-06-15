package com.personalprojections.locallife.copilot.domain.dto;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.time.LocalDateTime;

/**
 * Outbox 消息只读快照，用于 Agent 排查异步消息投递状态。
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class OutboxMessageSnapshot {

    private String eventId;
    private String topic;
    private String tag;
    private String status;
    private Integer retryCount;
    private LocalDateTime nextRetryAt;
    private LocalDateTime createdAt;
    private String payload;
}
