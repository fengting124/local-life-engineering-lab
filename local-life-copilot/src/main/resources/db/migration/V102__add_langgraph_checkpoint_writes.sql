-- =======================================================
-- V102: LangGraph pending writes persistence
-- =======================================================
-- Why:
--   langgraph_checkpoint stores complete checkpoint snapshots.
--   LangGraph can also emit pending writes before they are merged into a
--   checkpoint, especially around interrupt/resume and concurrent node flows.
--   Persisting them makes HITL recovery safer after process restarts.

CREATE TABLE IF NOT EXISTS `langgraph_checkpoint_write`
(
    `thread_id`     VARCHAR(64)  NOT NULL COMMENT 'LangGraph thread ID',
    `checkpoint_id` VARCHAR(64)  NOT NULL COMMENT 'Related checkpoint ID',
    `task_id`       VARCHAR(128) NOT NULL COMMENT 'LangGraph task ID that produced this write',
    `task_path`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'LangGraph task path',
    `write_index`   INT          NOT NULL COMMENT 'Write order within the task',
    `channel`       VARCHAR(128) NOT NULL COMMENT 'LangGraph channel name',
    `value`         LONGTEXT     NOT NULL COMMENT 'JsonPlusSerializer serialized write value',
    `created_at`    DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`thread_id`, `checkpoint_id`, `task_id`, `write_index`),
    KEY `idx_ckpt_write_checkpoint` (`thread_id`, `checkpoint_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LangGraph pending writes table for durable HITL and interrupt recovery';
