"""
Agent Service 全局配置。

所有敏感值（API Key / DB 密码）从环境变量读取，不硬编码。
开发环境可以在 .env 文件中设置，由 python-dotenv 加载。
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用配置（Pydantic Settings 自动从环境变量读取）。"""

    # ===== LLM =====
    # Claude API Key（推荐 Claude 3.5 Sonnet，工具调用能力强）
    anthropic_api_key: str = ""
    # 默认使用的模型
    llm_model: str = "claude-sonnet-4-6"
    # 最大输出 token（单次 LLM 调用）
    llm_max_tokens: int = 4096

    # ===== Java MCP Server =====
    # local-life-copilot 服务地址
    mcp_server_url: str = "http://localhost:8081"
    # MCP 请求超时（秒）
    mcp_timeout_seconds: int = 30

    # ===== MySQL（会话/消息/checkpoint 存储）=====
    db_url: str = "mysql+aiomysql://root:123456@localhost:3306/local_life"

    # ===== Redis =====
    redis_url: str = "redis://localhost:6379/2"  # DB 2（MCP Server 用 DB 1，主服务用 DB 0）

    # ===== Milvus（RAG 向量检索）=====
    milvus_uri: str = "http://localhost:19530"
    milvus_collection: str = "locallife_knowledge"

    # ===== Agent 运行参数 =====
    # ReAct 最大步数（超过则终止并说明证据不足）
    agent_max_steps: int = 15
    # 会话最大 token 预算（超过则终止）
    session_token_budget: int = 50_000
    # Self-Reflection 触发间隔（每 N 步触发一次）
    reflection_interval: int = 5

    # ===== 服务 =====
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    debug: bool = True

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"


# 全局单例
settings = Settings()
