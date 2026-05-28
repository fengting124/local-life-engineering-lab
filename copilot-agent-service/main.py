"""
LocalLife Copilot Agent Service 入口。

架构位置：
  Chat UI → [SSE] → 本服务（:8000）→ [MCP HTTP] → Java MCP Server（:8081）→ MySQL

启动：
  uvicorn main:app --host 0.0.0.0 --port 8000 --reload
"""
import structlog
from contextlib import asynccontextmanager
from fastapi import FastAPI
from fastapi.middleware.cors import CORSMiddleware

from api.chat import router as chat_router
from config.settings import settings

log = structlog.get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """应用生命周期：启动时初始化，关闭时清理。"""
    log.info("copilot_agent_starting",
             mcp_server=settings.mcp_server_url,
             llm_model=settings.llm_model)
    yield
    log.info("copilot_agent_stopped")


app = FastAPI(
    title="LocalLife Copilot Agent Service",
    description="""
企业级 Agent 服务，服务商家运营和平台客服。

## 架构
- **Agent 编排**：LangGraph ReAct + Self-Reflection
- **工具调用**：MCP over HTTP（Java MCP Server）
- **流式输出**：SSE（Server-Sent Events）
- **高风险动作**：HITL（人工审批）
- **知识检索**：Milvus 向量 RAG

## 接口
- `POST /chat`        — 发起对话（SSE 流）
- `POST /chat/resume` — 审批后恢复（HITL）
""",
    version="1.0.0",
    lifespan=lifespan,
)

# CORS（开发阶段放开，生产限制为前端域名）
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
)

# 注册路由
app.include_router(chat_router)


@app.get("/health")
async def health():
    """健康检查端点（负载均衡器探活）。"""
    return {"status": "ok", "service": "copilot-agent-service"}
