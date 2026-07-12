-- =======================================================
-- V103: Agent runtime run/event persistence
-- =======================================================
-- Why:
--   SSE is a display channel, not a durable source of truth.
--   agent_run records one concrete Agent execution; agent_event records the
--   replayable event stream for HITL recovery, incident triage, and demos.

CREATE TABLE IF NOT EXISTS `agent_run`
(
    `id`            VARCHAR(64)     NOT NULL COMMENT 'Agent run ID, UUID string',
    `session_id`    BIGINT UNSIGNED NOT NULL COMMENT 'Related agent_session ID',
    `thread_id`     VARCHAR(64)     NOT NULL COMMENT 'LangGraph thread ID',
    `trace_id`      VARCHAR(64)     NULL COMMENT 'Trace ID propagated through logs and tools',
    `user_id`       BIGINT UNSIGNED NOT NULL COMMENT 'Requester user ID',
    `user_role`     VARCHAR(20)     NOT NULL COMMENT 'Requester role: merchant / cs / admin',
    `merchant_id`   BIGINT UNSIGNED NULL COMMENT 'Merchant scope when applicable',
    `status`        VARCHAR(32)     NOT NULL DEFAULT 'SUBMITTED' COMMENT 'SUBMITTED/RUNNING/WAITING_APPROVAL/COMPLETED/FAILED/CANCELED/EXPIRED',
    `input_summary` VARCHAR(255)    NULL COMMENT 'Short sanitized summary of user input',
    `error_message` TEXT            NULL COMMENT 'Internal error summary for triage',
    `started_at`    DATETIME        NULL COMMENT 'Execution start time',
    `finished_at`   DATETIME        NULL COMMENT 'Terminal status time',
    `created_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`    DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_agent_run_session_time` (`session_id`, `created_at`),
    KEY `idx_agent_run_thread` (`thread_id`),
    KEY `idx_agent_run_status_time` (`status`, `created_at`),
    KEY `idx_agent_run_trace` (`trace_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent run table: durable execution status for one user request';

CREATE TABLE IF NOT EXISTS `agent_event`
(
    `id`             BIGINT UNSIGNED NOT NULL COMMENT 'Event ID, snowflake',
    `run_id`         VARCHAR(64)     NOT NULL COMMENT 'Related agent_run ID',
    `session_id`     BIGINT UNSIGNED NOT NULL COMMENT 'Related agent_session ID',
    `thread_id`      VARCHAR(64)     NOT NULL COMMENT 'LangGraph thread ID',
    `sequence_index` INT UNSIGNED    NOT NULL COMMENT 'Event order within run',
    `event_type`     VARCHAR(50)     NOT NULL COMMENT 'session_started/tool_call/tool_result/hitl_request/final_answer/error/etc',
    `event_name`     VARCHAR(100)    NULL COMMENT 'Node or tool name when applicable',
    `payload`        JSON            NULL COMMENT 'Browser-safe event payload',
    `trace_id`       VARCHAR(64)     NULL COMMENT 'Trace ID propagated through logs and tools',
    `created_at`     DATETIME        NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    UNIQUE KEY `uk_agent_event_run_seq` (`run_id`, `sequence_index`),
    KEY `idx_agent_event_session_time` (`session_id`, `created_at`),
    KEY `idx_agent_event_thread_time` (`thread_id`, `created_at`),
    KEY `idx_agent_event_type_time` (`event_type`, `created_at`),
    KEY `idx_agent_event_trace` (`trace_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent event table: durable replayable Agent execution event stream';
