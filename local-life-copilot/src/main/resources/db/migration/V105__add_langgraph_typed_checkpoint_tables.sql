-- =======================================================
-- V105: LangGraph 4.x typed checkpoint persistence
-- =======================================================
-- Legacy tables remain untouched as migration and rollback sources.

CREATE TABLE IF NOT EXISTS `langgraph_checkpoint_v2`
(
    `thread_id`            VARCHAR(64)  NOT NULL COMMENT 'LangGraph thread ID',
    `checkpoint_ns`        VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'Subgraph checkpoint namespace',
    `checkpoint_id`        VARCHAR(64)  NOT NULL COMMENT 'Checkpoint ID',
    `parent_checkpoint_id` VARCHAR(64)  NULL COMMENT 'Parent checkpoint in the same namespace',
    `state_type`           VARCHAR(32)  NOT NULL COMMENT 'Serializer type tag',
    `state_blob`           LONGBLOB     NOT NULL COMMENT 'Typed serializer payload',
    `metadata`             JSON         NULL COMMENT 'Checkpoint metadata',
    `created_at`           DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`thread_id`, `checkpoint_ns`, `checkpoint_id`),
    KEY `idx_ckpt_v2_thread_time` (`thread_id`, `checkpoint_ns`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LangGraph 4.x typed checkpoints; legacy table retained for rollback';

CREATE TABLE IF NOT EXISTS `langgraph_checkpoint_write_v2`
(
    `thread_id`     VARCHAR(64)  NOT NULL COMMENT 'LangGraph thread ID',
    `checkpoint_ns` VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'Subgraph checkpoint namespace',
    `checkpoint_id` VARCHAR(64)  NOT NULL COMMENT 'Related checkpoint ID',
    `task_id`       VARCHAR(128) NOT NULL COMMENT 'LangGraph task ID',
    `task_path`     VARCHAR(255) NOT NULL DEFAULT '' COMMENT 'LangGraph task path',
    `write_index`   INT          NOT NULL COMMENT 'Reserved or positional write index',
    `channel`       VARCHAR(128) NOT NULL COMMENT 'LangGraph channel name',
    `value_type`    VARCHAR(32)  NOT NULL COMMENT 'Serializer type tag',
    `value_blob`    LONGBLOB     NOT NULL COMMENT 'Typed serializer payload',
    `created_at`    DATETIME(6)  NOT NULL DEFAULT CURRENT_TIMESTAMP(6),
    PRIMARY KEY (`thread_id`, `checkpoint_ns`, `checkpoint_id`, `task_id`, `write_index`),
    KEY `idx_ckpt_write_v2_checkpoint` (`thread_id`, `checkpoint_ns`, `checkpoint_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LangGraph 4.x typed pending writes; legacy table retained for rollback';
