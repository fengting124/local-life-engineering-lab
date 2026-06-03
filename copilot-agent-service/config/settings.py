"""
Agent Service 全局配置。

所有敏感值（API Key / DB 密码）从环境变量读取，不硬编码。
开发环境可以在 .env 文件中设置，由 python-dotenv 加载。

支持的 LLM Provider（通过 LLM_PROVIDER 环境变量切换）：
  anthropic   → Claude Sonnet/Haiku，工具调用能力最强，推荐
  deepseek    → DeepSeek-V3，性价比极高（¥2/百万token），OpenAI 兼容接口
  openai      → GPT-4o / o1，需要 OpenAI Key
  qwen        → 通义千问，国内可用，阿里云提供，OpenAI 兼容接口
  local       → 本地 Ollama 服务（如 Qwen2.5-7B），完全离线

切换示例（.env）：
  LLM_PROVIDER=deepseek
  LLM_API_KEY=sk-xxxxxxxx
  LLM_MODEL=deepseek-chat
  # LLM_BASE_URL 不填则用各 provider 默认地址
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用配置（Pydantic Settings 自动从环境变量读取）。"""

    # ===== LLM Provider 配置 =====
    # 支持：anthropic | deepseek | openai | qwen | local
    llm_provider: str = "anthropic"

    # 统一的 API Key 字段（不同 provider 填不同 key）
    # anthropic → 填 Claude API Key
    # deepseek  → 填 DeepSeek API Key
    # openai    → 填 OpenAI API Key
    # qwen      → 填阿里云 DashScope API Key
    # local     → 填任意字符串（Ollama 不需要 key）
    llm_api_key: str = ""

    # 兼容旧配置（优先读 llm_api_key，没有则读 anthropic_api_key）
    anthropic_api_key: str = ""

    # 模型名称（不同 provider 格式不同，见下方注释）
    # anthropic:  claude-sonnet-4-6 | claude-opus-4-7 | claude-haiku-4-5-20251001
    # deepseek:   deepseek-chat | deepseek-reasoner
    # openai:     gpt-4o | gpt-4o-mini | o1
    # qwen:       qwen-max | qwen-turbo | qwen-plus
    # local:      qwen2.5:7b | llama3.2:3b（Ollama 里 pull 的模型名）
    llm_model: str = "claude-sonnet-4-6"

    # API Base URL（可选，留空则用各 provider 默认地址）
    # deepseek: https://api.deepseek.com/v1
    # qwen:     https://dashscope.aliyuncs.com/compatible-mode/v1
    # local:    http://localhost:11434/v1（Ollama）
    # openai:   https://api.openai.com/v1（默认，可不填）
    llm_base_url: str = ""

    # 最大输出 token（单次 LLM 调用）
    llm_max_tokens: int = 4096

    # ===== Java MCP Server =====
    mcp_server_url: str = "http://localhost:8081"
    mcp_timeout_seconds: int = 30

    # ===== MySQL（会话/消息/checkpoint 存储）=====
    db_url: str = "mysql+aiomysql://root:123456@localhost:3306/local_life"

    # ===== Redis =====
    redis_url: str = "redis://localhost:6379/2"

    # ===== Milvus（RAG 向量检索）=====
    milvus_uri: str = "http://localhost:19530"
    milvus_collection: str = "locallife_knowledge"

    # ===== Agent 运行参数 =====
    agent_max_steps: int = 15
    session_token_budget: int = 50_000
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
