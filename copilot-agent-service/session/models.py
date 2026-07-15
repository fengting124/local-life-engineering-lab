"""
会话与消息的 SQLAlchemy 模型，对应 agent_session / agent_message 表。

这两张表与 LocalLife Server 共享同一个 MySQL（local_life 数据库），
由 Copilot DB Migration V1 创建。
"""
from datetime import datetime
from sqlalchemy import (
    BigInteger, DateTime, Integer, String, Text,
    func, JSON
)
from sqlalchemy.orm import DeclarativeBase, Mapped, mapped_column


class Base(DeclarativeBase):
    pass


class AgentSession(Base):
    """
    Agent 会话表（agent_session）。

    生命周期：
      1. 用户发起第一条消息时创建（status=ACTIVE）
      2. Agent 给出最终回答后更新（status=COMPLETED）
      3. 发生错误或超时时（status=ABORTED）
      4. 等待人工审批时（status=PENDING_APPROVAL）

    cost 字段用于监控 AI 成本，token 数 × 单价 / 1000 = 美元成本。
    当前简化为 total_cost_cents（分），便于整数存储。
    """
    __tablename__ = "agent_session"

    id:               Mapped[int] = mapped_column(BigInteger, primary_key=True)
    user_id:          Mapped[int] = mapped_column(BigInteger, nullable=False)
    user_role:        Mapped[str] = mapped_column(String(20), nullable=False)
    merchant_id:      Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    title:            Mapped[str | None] = mapped_column(String(200), nullable=True)
    status:           Mapped[str] = mapped_column(String(20), default="ACTIVE")
    total_tokens:     Mapped[int] = mapped_column(Integer, default=0)
    total_cost_cents: Mapped[int] = mapped_column(Integer, default=0)
    created_at:       Mapped[datetime] = mapped_column(DateTime, default=func.now())
    updated_at:       Mapped[datetime] = mapped_column(DateTime, default=func.now(), onupdate=func.now())


class AgentMessage(Base):
    """
    Agent 消息表（agent_message）。

    role 字段对应 LangGraph / LangChain 的消息类型：
      user      → HumanMessage（用户输入）
      assistant → AIMessage（Agent 回答 / 工具调用决策）
      tool      → ToolMessage（工具执行结果 / Observation）

    tool_calls / tool_results 以 JSON 格式存储，结构：
      tool_calls:   [{"id":"call_001","name":"query_order","args":{...}}]
      tool_results: [{"call_id":"call_001","content":"...", "status":"success"}]

    step_index 是 ReAct 循环的步数计数器：
      0 = 用户输入
      1 = 第一次 LLM 推理（Thought + Action）
      2 = 第一次工具调用结果（Observation）
      3 = 第二次 LLM 推理
      ... 依此类推
    用于 Evals 评测时按步骤重放和分析 Agent 的决策路径。
    """
    __tablename__ = "agent_message"

    id:           Mapped[int] = mapped_column(BigInteger, primary_key=True)
    session_id:   Mapped[int] = mapped_column(BigInteger, nullable=False)
    role:         Mapped[str] = mapped_column(String(20), nullable=False)
    content:      Mapped[str | None] = mapped_column(Text, nullable=True)
    tool_calls:   Mapped[dict | None] = mapped_column(JSON, nullable=True)
    tool_results: Mapped[dict | None] = mapped_column(JSON, nullable=True)
    tokens:       Mapped[int | None] = mapped_column(Integer, nullable=True)
    step_index:   Mapped[int] = mapped_column(Integer, default=0)
    created_at:   Mapped[datetime] = mapped_column(DateTime, default=func.now())


class AgentRun(Base):
    """
    Agent 运行实例表（agent_run）。

    一次 run 对应一次用户请求触发的 Agent 执行，而不是整个聊天会话。
    它是企业排障入口：从 run_id 可以追到 session、thread、trace、审批和事件流。
    """
    __tablename__ = "agent_run"

    id:            Mapped[str] = mapped_column(String(64), primary_key=True)
    session_id:    Mapped[int] = mapped_column(BigInteger, nullable=False)
    thread_id:     Mapped[str] = mapped_column(String(64), nullable=False)
    trace_id:      Mapped[str | None] = mapped_column(String(64), nullable=True)
    user_id:       Mapped[int] = mapped_column(BigInteger, nullable=False)
    user_role:     Mapped[str] = mapped_column(String(20), nullable=False)
    merchant_id:   Mapped[int | None] = mapped_column(BigInteger, nullable=True)
    status:        Mapped[str] = mapped_column(String(32), nullable=False, default="SUBMITTED")
    input_summary: Mapped[str | None] = mapped_column(String(255), nullable=True)
    error_message: Mapped[str | None] = mapped_column(Text, nullable=True)
    started_at:    Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    finished_at:   Mapped[datetime | None] = mapped_column(DateTime, nullable=True)
    created_at:    Mapped[datetime] = mapped_column(DateTime, default=func.now())
    updated_at:    Mapped[datetime] = mapped_column(DateTime, default=func.now(), onupdate=func.now())


class AgentEvent(Base):
    """
    Agent 运行事件表（agent_event）。

    SSE 是展示通道，agent_event 是可重放事实流。每个事件记录 run 内的顺序、
    事件类型、安全摘要 payload 和 trace 关联。
    """
    __tablename__ = "agent_event"

    id:             Mapped[int] = mapped_column(BigInteger, primary_key=True)
    run_id:         Mapped[str] = mapped_column(String(64), nullable=False)
    session_id:     Mapped[int] = mapped_column(BigInteger, nullable=False)
    thread_id:      Mapped[str] = mapped_column(String(64), nullable=False)
    sequence_index: Mapped[int] = mapped_column(Integer, nullable=False)
    event_type:     Mapped[str] = mapped_column(String(50), nullable=False)
    event_name:     Mapped[str | None] = mapped_column(String(100), nullable=True)
    payload:        Mapped[dict | None] = mapped_column(JSON, nullable=True)
    trace_id:       Mapped[str | None] = mapped_column(String(64), nullable=True)
    created_at:     Mapped[datetime] = mapped_column(DateTime, default=func.now())
