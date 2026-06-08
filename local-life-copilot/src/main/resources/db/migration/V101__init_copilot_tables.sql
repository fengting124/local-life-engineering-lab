-- =======================================================
-- Copilot V1：Agent 核心表
-- =======================================================
--
-- 这 5 张表支撑 LocalLife Copilot 的完整生命周期：
--
--   agent_session       → 会话（一次商家/客服与 Agent 的对话）
--   agent_message       → 消息（会话内每条消息，含工具调用记录）
--   langgraph_checkpoint → LangGraph 状态快照（支持 HITL 挂起 + 恢复）
--   tool_audit_log      → 工具调用审计（可追溯每次工具调用的入参/出参/耗时）
--   hitl_approval       → 人工审批记录（退款/补券等高风险动作必须审批）
--
-- 设计原则：
--   1. 所有写操作可追溯（session_id / trace_id / user_id 三个维度）
--   2. LangGraph checkpoint 持久化保证 HITL 挂起后可以从断点恢复
--   3. 高风险动作在执行前必须写 hitl_approval，且状态为 APPROVED 才能执行
--   4. 工具审计独立于业务表，不影响主链路
-- =======================================================

-- -------------------------------------------------------
-- 1. agent_session —— Agent 会话
-- -------------------------------------------------------
-- 一次会话 = 用户与 Agent 从「发问」到「得到回答」的完整交互。
-- 会话可以包含多个问答轮次（agent_message）。
-- token / cost 字段用于成本监控和用户预算控制。
CREATE TABLE IF NOT EXISTS `agent_session`
(
    `id`               BIGINT UNSIGNED  NOT NULL COMMENT '会话 ID，雪花算法',
    `user_id`          BIGINT UNSIGNED  NOT NULL COMMENT '发起会话的用户 ID（商家/客服/运营）',
    `user_role`        VARCHAR(20)      NOT NULL COMMENT '用户角色：merchant / cs / admin',
    `merchant_id`      BIGINT UNSIGNED  NULL COMMENT '商家 ID（merchant 角色时必填，cs/admin 可为 NULL）',
    `title`            VARCHAR(200)     NULL COMMENT '会话标题（由第一条消息自动生成，或用户手动命名）',
    `status`           VARCHAR(20)      NOT NULL DEFAULT 'ACTIVE' COMMENT '会话状态：ACTIVE / COMPLETED / ABORTED / PENDING_APPROVAL',
    `total_tokens`     INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '会话累计消耗 token 数（用于预算控制）',
    `total_cost_cents` INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT '会话累计费用（分），基于 token 换算',
    `created_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_session_user_time` (`user_id`, `created_at`),
    KEY `idx_session_status` (`status`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 会话表：记录每次商家/客服与 Copilot 的完整对话会话';

-- -------------------------------------------------------
-- 2. agent_message —— 会话消息
-- -------------------------------------------------------
-- 每条消息对应 ReAct 循环中的一个节点：
--   role=user      → 用户输入
--   role=assistant → Agent 回答（可能含 Final Answer 或工具调用决策）
--   role=tool      → 工具调用结果（Observation）
--
-- tool_calls / tool_results 以 JSON 存储，格式遵循 OpenAI function-calling 风格：
--   tool_calls: [{"id":"call_001","name":"query_order","arguments":{"order_id":"ORDER_12345"}}]
--   tool_results: [{"call_id":"call_001","content":"..."}]
CREATE TABLE IF NOT EXISTS `agent_message`
(
    `id`           BIGINT UNSIGNED  NOT NULL COMMENT '消息 ID，雪花算法',
    `session_id`   BIGINT UNSIGNED  NOT NULL COMMENT '所属会话 ID',
    `role`         VARCHAR(20)      NOT NULL COMMENT '消息角色：user / assistant / tool',
    `content`      TEXT             NULL COMMENT '消息文本内容（Final Answer 或中间推理步骤）',
    `tool_calls`   JSON             NULL COMMENT '工具调用列表（assistant 决定调用工具时填写）',
    `tool_results` JSON             NULL COMMENT '工具调用结果列表（role=tool 时填写）',
    `tokens`       INT UNSIGNED     NULL COMMENT '本条消息消耗的 token 数',
    `step_index`   INT UNSIGNED     NOT NULL DEFAULT 0 COMMENT 'ReAct 循环步数（0=用户输入，1/2/...=推理步骤）',
    `created_at`   DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_msg_session_time` (`session_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'Agent 消息表：记录会话内每条消息（用户/助手/工具三种角色）';

-- -------------------------------------------------------
-- 3. langgraph_checkpoint —— LangGraph 状态快照
-- -------------------------------------------------------
-- LangGraph 使用 checkpoint 机制持久化 Agent 状态，支持：
--   - HITL 挂起：Agent 遇到高风险动作时 interrupt，state 序列化存储
--   - 断点恢复：审批通过后，从 checkpoint_id 恢复 Agent 状态继续执行
--   - 错误恢复：服务重启后，未完成的任务可以从最新 checkpoint 继续
--
-- thread_id = LangGraph 的线程标识（对应一次 Agent 执行任务，不等于会话）
-- 一个 session 可能产生多个 thread（用户每次提问启动新 thread）
CREATE TABLE IF NOT EXISTS `langgraph_checkpoint`
(
    `thread_id`            VARCHAR(64)  NOT NULL COMMENT 'LangGraph 线程 ID，一次 Agent 执行的唯一标识',
    `checkpoint_id`        VARCHAR(64)  NOT NULL COMMENT 'Checkpoint ID，一个 thread 可有多个快照',
    `parent_checkpoint_id` VARCHAR(64)  NULL COMMENT '父 Checkpoint ID（形成快照链，支持回滚）',
    `state`                LONGTEXT     NOT NULL COMMENT 'Agent 状态快照（JSON，含消息历史、工具调用队列等）',
    `metadata`             JSON         NULL COMMENT 'Checkpoint 元数据（step_count / session_id / pending_action 等）',
    `created_at`           DATETIME     NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`thread_id`, `checkpoint_id`),
    KEY `idx_ckpt_thread_time` (`thread_id`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = 'LangGraph Checkpoint 表：持久化 Agent 状态，支持 HITL 挂起/恢复和断点续跑';

-- -------------------------------------------------------
-- 4. tool_audit_log —— 工具调用审计
-- -------------------------------------------------------
-- 每次 MCP 工具调用都记录一条审计日志，包含：
--   - 完整的入参和出参（方便问题排查和合规审计）
--   - 耗时（监控 P99 延迟，发现慢工具）
--   - 状态（success / error，统计工具成功率）
--
-- 这是 Agent 可观测性的核心数据源，支撑：
--   - 工具调用准确率评测
--   - 任务完成率统计
--   - 异常工具调用告警
CREATE TABLE IF NOT EXISTS `tool_audit_log`
(
    `id`          BIGINT UNSIGNED  NOT NULL COMMENT '审计记录 ID，雪花算法',
    `session_id`  BIGINT UNSIGNED  NOT NULL COMMENT '所属会话 ID',
    `thread_id`   VARCHAR(64)      NULL COMMENT 'LangGraph thread_id（关联 checkpoint）',
    `trace_id`    VARCHAR(64)      NULL COMMENT '全链路追踪 ID（与 LocalLife Server 链路打通）',
    `tool_name`   VARCHAR(100)     NOT NULL COMMENT '工具名称，如 query_order / execute_refund',
    `tool_input`  JSON             NOT NULL COMMENT '工具入参（完整 JSON，方便复盘）',
    `tool_output` JSON             NULL COMMENT '工具出参（成功时为结果 JSON，失败时为错误 JSON）',
    `duration_ms` INT UNSIGNED     NULL COMMENT '工具执行耗时（毫秒）',
    `status`      VARCHAR(20)      NOT NULL COMMENT '执行状态：success / error / timeout',
    `error_msg`   TEXT             NULL COMMENT '错误信息（status=error 时填写）',
    `user_id`     BIGINT UNSIGNED  NOT NULL COMMENT '调用者 user_id（来自 RBAC 上下文）',
    `user_role`   VARCHAR(20)      NOT NULL COMMENT '调用者角色（来自 RBAC 上下文）',
    `created_at`  DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_audit_session_time` (`session_id`, `created_at`),
    KEY `idx_audit_trace` (`trace_id`),
    KEY `idx_audit_tool_time` (`tool_name`, `created_at`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '工具调用审计表：记录每次 MCP 工具调用的完整输入/输出/耗时/状态';

-- -------------------------------------------------------
-- 5. hitl_approval —— 人工审批记录
-- -------------------------------------------------------
-- 高风险动作（退款/补券/活动发布）必须经过人工审批：
--   1. Agent 决定执行高风险动作时，写 hitl_approval（PENDING）
--   2. LangGraph interrupt + checkpoint 持久化（Agent 挂起）
--   3. 运营在审批工作台看到 PENDING 记录，审批通过或拒绝
--   4. 系统根据审批结果恢复 LangGraph thread，继续或终止执行
--
-- checkpoint_id 是关键：恢复 Agent 时用它找到挂起前的状态
CREATE TABLE IF NOT EXISTS `hitl_approval`
(
    `id`               BIGINT UNSIGNED  NOT NULL COMMENT '审批记录 ID，雪花算法',
    `session_id`       BIGINT UNSIGNED  NOT NULL COMMENT '所属会话 ID',
    `thread_id`        VARCHAR(64)      NOT NULL COMMENT 'LangGraph thread_id（恢复时用）',
    `checkpoint_id`    VARCHAR(64)      NOT NULL COMMENT '挂起前的 checkpoint_id（恢复起点）',
    `action_type`      VARCHAR(50)      NOT NULL COMMENT '高风险动作类型：execute_refund / issue_compensation_coupon / create_campaign',
    `action_payload`   JSON             NOT NULL COMMENT '动作参数（如：order_id / amount / reason 等）',
    `agent_reason`     TEXT             NOT NULL COMMENT 'Agent 给出的申请理由（根因分析，方便审批者判断）',
    `status`           VARCHAR(20)      NOT NULL DEFAULT 'PENDING' COMMENT '审批状态：PENDING / APPROVED / REJECTED / EXPIRED',
    `approver_id`      BIGINT UNSIGNED  NULL COMMENT '审批者 user_id（审批后填写）',
    `approver_comment` TEXT             NULL COMMENT '审批者备注（可选）',
    `approved_at`      DATETIME         NULL COMMENT '审批完成时间',
    `expire_at`        DATETIME         NOT NULL COMMENT '审批过期时间（默认 24 小时，过期自动拒绝）',
    `created_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP,
    `updated_at`       DATETIME         NOT NULL DEFAULT CURRENT_TIMESTAMP ON UPDATE CURRENT_TIMESTAMP,
    PRIMARY KEY (`id`),
    KEY `idx_hitl_status_time` (`status`, `created_at`),
    KEY `idx_hitl_session` (`session_id`),
    KEY `idx_hitl_thread` (`thread_id`)
) ENGINE = InnoDB
  DEFAULT CHARSET = utf8mb4
  COLLATE = utf8mb4_unicode_ci
  COMMENT = '人工审批表：高风险动作（退款/补券）执行前必须审批，通过后 LangGraph 继续执行';
