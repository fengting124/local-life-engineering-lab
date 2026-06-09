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
  LLM_MODEL=deepseek-v4-flash
  # LLM_BASE_URL 不填则用各 provider 默认地址
"""
from pydantic_settings import BaseSettings


class Settings(BaseSettings):
    """应用配置（Pydantic Settings 自动从环境变量读取）。"""

    # ===== LLM Provider 配置 =====
    # 支持：anthropic | deepseek | openai | qwen | local
    # 日常开发推荐 deepseek（¥2/百万 token），面试演示可切 anthropic
    #
    # 统一用下面四个字段配置——_create_llm() 只读这四个（按 llm_provider 套用
    # 各家默认 base_url/model），不要拆成 deepseek_xxx/qwen_xxx 等专属字段，
    # 那样配了也不会被读取（历史上踩过这个坑）。
    llm_provider: str = "deepseek"

    anthropic_api_key: str = ""   # llm_api_key 留空时的兼容回退（早期仅支持 Claude 时遗留的字段名）
    llm_api_key: str = ""
    llm_model: str = ""
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

    # ===== Auto-Compact（上下文自动压缩，参考 Claude Code 的 autoCompact 设计）=====
    # 触发缓冲带：token_count 达到「预算 - 缓冲」时就提前压缩，而不是等顶满才动手，
    # 给生成摘要本身预留空间，避免摘要请求自己撞上下文墙。
    compact_buffer_tokens: int = 8_000
    # 压缩时强制保留最近 N 条原始消息不摘要，保证近期上下文完整可用、可追溯。
    compact_keep_recent_messages: int = 6
    # 连续压缩失败（含「无安全可压缩内容」）达到此阈值后熔断，
    # 不再尝试压缩，直接走 token_budget 硬终止，防止死循环浪费 token。
    compact_max_consecutive_failures: int = 2

    # ===== 服务 =====
    app_host: str = "0.0.0.0"
    app_port: int = 8000
    debug: bool = True

    class Config:
        env_file = ".env"
        env_file_encoding = "utf-8"
        # 忽略 .env 中未在此类声明的字段（RAG 配置由 rag/config.py 单独读取）
        extra = "ignore"


# 全局单例
settings = Settings()
