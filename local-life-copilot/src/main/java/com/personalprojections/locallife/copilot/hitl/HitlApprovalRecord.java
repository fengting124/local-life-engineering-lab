package com.personalprojections.locallife.copilot.hitl;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;

/** Database projection for the HITL approval execution state machine. */
@Data
@Builder(toBuilder = true)
public class HitlApprovalRecord {
    private Long id;
    private String threadId;
    private String checkpointId;
    private String actionType;
    private String actionPayload;
    private Integer payloadVersion;
    private String payloadDigest;
    private Long merchantId;
    private Long requestedUserId;
    private String requestedRole;
    private String status;
    private LocalDateTime expireAt;
    private String executionId;
    private LocalDateTime executionLeaseUntil;
    private LocalDateTime executingAt;
    private LocalDateTime executedAt;
    private String executionResult;
    private String executionError;
}
