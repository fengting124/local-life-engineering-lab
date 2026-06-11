"""
LocalLife Copilot Agent Service 入口。

服务职责：
  - 接收用户对话请求，以 SSE 流式返回 Agent 执行过程
  - 管理会话（agent_session / agent_message 持久化）
  - 支持 HITL 审批工作台 API

架构位置：
  Chat UI → [SSE] → 本服务（:8000）
               → [MCP HTTP] → Java MCP Server（:8081）→ MySQL
               → [async SQL]  → MySQL（agent_session / hitl_approval）
               → [Redis]      → 限流 / RAG 缓存

启动方式：
  # 开发环境
  uvicorn main:app --host 0.0.0.0 --port 8000 --reload

  # 生产环境
  uvicorn main:app --host 0.0.0.0 --port 8000 --workers 4
"""
import structlog
import uuid
from contextlib import asynccontextmanager
from pathlib import Path
from fastapi import FastAPI
from structlog.contextvars import bind_contextvars, clear_contextvars
from fastapi.middleware.cors import CORSMiddleware
from fastapi.staticfiles import StaticFiles
from fastapi.responses import FileResponse, RedirectResponse

from config.logging import configure_logging
from api.chat import router as chat_router
from api.hitl import router as hitl_router
from api.session import router as session_router
from config.settings import settings

configure_logging()
log = structlog.get_logger(__name__)


@asynccontextmanager
async def lifespan(app: FastAPI):
    """
    应用生命周期钩子（启动 / 关闭）。

    启动时：
    - 验证 MCP Server 可达性（可选，失败不阻止启动）
    - 预热数据库连接池

    关闭时：
    - 关闭数据库引擎，释放连接池
    """
    log.info(
        "copilot_agent_starting",
        mcp_server=settings.mcp_server_url,
        llm_model=settings.llm_model,
        db=settings.db_url.split("@")[-1],   # 只打印 host/db，不打印密码
    )

    # ---- 自动创建 Copilot 数据库表（幂等，CREATE TABLE IF NOT EXISTS）----
    # 包含：agent_session / agent_message / langgraph_checkpoint /
    #        tool_audit_log / hitl_approval
    try:
        from session.manager import engine
        from session.models import Base as SessionBase
        from session.hitl import Base as HitlBase
        import sqlalchemy
        async with engine.begin() as conn:
            await conn.run_sync(SessionBase.metadata.create_all)
            await conn.run_sync(HitlBase.metadata.create_all)
            # langgraph_checkpoint 表（checkpointer 需要）
            await conn.execute(sqlalchemy.text("""
                CREATE TABLE IF NOT EXISTS `langgraph_checkpoint` (
                  `thread_id` VARCHAR(64) NOT NULL,
                  `checkpoint_id` VARCHAR(64) NOT NULL,
                  `parent_checkpoint_id` VARCHAR(64),
                  `state` LONGTEXT NOT NULL,
                  `metadata` JSON,
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (`thread_id`, `checkpoint_id`),
                  KEY `idx_ckpt_thread_time` (`thread_id`, `created_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """))
            # tool_audit_log 表
            await conn.execute(sqlalchemy.text("""
                CREATE TABLE IF NOT EXISTS `tool_audit_log` (
                  `id` BIGINT UNSIGNED NOT NULL,
                  `session_id` BIGINT UNSIGNED,
                  `thread_id` VARCHAR(64),
                  `trace_id` VARCHAR(64),
                  `tool_name` VARCHAR(100) NOT NULL,
                  `tool_input` JSON NOT NULL,
                  `tool_output` JSON,
                  `duration_ms` INT UNSIGNED,
                  `status` VARCHAR(20) NOT NULL,
                  `error_msg` TEXT,
                  `user_id` BIGINT UNSIGNED,
                  `user_role` VARCHAR(20),
                  `created_at` DATETIME NOT NULL DEFAULT CURRENT_TIMESTAMP,
                  PRIMARY KEY (`id`),
                  KEY `idx_audit_session_time` (`session_id`, `created_at`),
                  KEY `idx_audit_tool_time` (`tool_name`, `created_at`)
                ) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_unicode_ci;
            """))
        log.info("copilot_db_tables_ready")
    except Exception as e:
        log.warning("copilot_db_init_failed", error=str(e),
                    hint="MySQL 可能未就绪，重启服务后会重试")

    # 验证 MCP Server 是否可达（不阻塞启动）
    try:
        import httpx
        # trust_env=False：内部服务调用不走 WSL 代理
        async with httpx.AsyncClient(timeout=3.0, trust_env=False) as client:
            resp = await client.get(f"{settings.mcp_server_url}/actuator/health")
            if resp.status_code == 200:
                log.info("mcp_server_healthy", url=settings.mcp_server_url)
            else:
                log.warning("mcp_server_unhealthy", status=resp.status_code)
    except Exception as e:
        log.warning("mcp_server_unreachable", error=str(e),
                    hint="确认 local-life-copilot 已在端口 8081 启动")

    # ---- 自动入库知识库（RAG 必须有数据才能检索）----
    # 扫描 rag/knowledge_base/ 目录，将所有 Markdown 文档向量化写入 Milvus + BM25
    # 使用幂等 upsert：重复运行只更新变更文档，不重复写入
    try:
        from rag.ingest import ingest_all
        kb_result = await ingest_all()
        if kb_result["chunks"] > 0:
            log.info("knowledge_base_ingested",
                     files=kb_result["files"], chunks=kb_result["chunks"])
        else:
            log.warning("knowledge_base_empty",
                        hint="Milvus 可能未启动，或 rag/knowledge_base/ 目录为空")
    except Exception as e:
        # 知识库入库失败不阻止服务启动（Milvus 可能未启动）
        log.warning("knowledge_base_ingest_failed", error=str(e),
                    hint="knowledge_search 工具将降级为空结果，其他功能不受影响")

    yield

    # 关闭数据库连接池
    from session.manager import engine
    await engine.dispose()
    log.info("copilot_agent_stopped")


app = FastAPI(
    title="LocalLife Copilot Agent Service",
    description="""
**LocalLife Copilot** —— 企业级 Agent 服务，服务商家运营和平台客服。

## 五层架构
```
Chat UI / Approval UI
       ↓ HTTP + SSE
copilot-agent-service  ← 本服务
       ↓ MCP over HTTP
locallife-mcp-server（Java）
       ↓ JDBC
MySQL（local_life 数据库）
```

## 核心能力
- **ReAct + Self-Reflection**：LangGraph 多步推理，每 5 步自我反思
- **MCP 工具调用**：10 个标准化工具（L1 只读 → L4 高风险）
- **Tool Router**：按角色和任务类型动态过滤工具子集
- **HITL 人工审批**：退款/补券等高风险动作强制审批
- **SSE 流式输出**：实时推送每个 Agent 步骤
- **Guardrails**：Prompt Injection 检测 + 输出越权检查
- **Evals**：50 条用例 + 6 项指标自动化评测

## 接口列表
- `POST /chat`              — 发起对话（SSE 流）
- `POST /chat/resume`       — 审批通过后恢复 Agent（HITL）
- `GET  /hitl/pending`      — 查看待审批列表（运营工作台）
- `GET  /hitl/{id}`         — 查看审批详情
- `POST /hitl/{id}/approve` — 通过审批
- `POST /hitl/{id}/reject`  — 拒绝审批
""",
    version="1.0.0",
    lifespan=lifespan,
)

# ---- CORS ----
# 开发阶段放开，生产环境限制为前端域名
app.add_middleware(
    CORSMiddleware,
    allow_origins=["*"],
    allow_methods=["*"],
    allow_headers=["*"],
    expose_headers=["X-Session-Id", "X-Thread-Id", "X-Trace-Id"],  # 让前端能读取这些响应头
)


@app.middleware("http")
async def observability_context(request, call_next):
    """Bind request-scoped fields to every structlog line and response header."""
    trace_id = request.headers.get("X-Trace-Id") or request.headers.get("X-Request-Id") or uuid.uuid4().hex
    clear_contextvars()
    bind_contextvars(
        trace_id=trace_id,
        http_method=request.method,
        http_path=request.url.path,
    )
    try:
        response = await call_next(request)
        response.headers["X-Trace-Id"] = trace_id
        return response
    finally:
        clear_contextvars()

# ---- 路由注册 ----
app.include_router(session_router)
app.include_router(chat_router)
app.include_router(hitl_router)

# ---- Prometheus /metrics 端点 ----
# 自动埋点：HTTP 请求 count / latency 等基础指标
# 业务指标通过 agent/metrics.py 中的 Counter/Histogram 显式上报
try:
    from prometheus_fastapi_instrumentator import Instrumentator
    # 必须导入指标模块才能注册到默认 registry
    import agent.metrics  # noqa: F401
    Instrumentator(
        should_group_status_codes=False,
        excluded_handlers=["/health", "/metrics"],
    ).instrument(app).expose(app, endpoint="/metrics", include_in_schema=False)
    log.info("prometheus_metrics_enabled", endpoint="/metrics")
except ImportError:
    log.warning(
        "prometheus_instrumentator_missing",
        hint="pip install prometheus-fastapi-instrumentator prometheus-client",
    )


@app.get("/health", tags=["system"])
async def health():
    """健康检查端点（负载均衡器 / k8s liveness probe 用）。"""
    return {
        "status":  "ok",
        "service": "copilot-agent-service",
        "version": "1.0.0",
    }


@app.get("/swagger-ui.html", include_in_schema=False)
async def swagger_ui_alias():
    """兼容 Java/Springdoc 的 Swagger UI 入口，实际跳转到 FastAPI /docs。"""
    return RedirectResponse(url="/docs")


@app.get("/v3/api-docs", include_in_schema=False)
async def openapi_json_alias():
    """兼容 Java/Springdoc 的 OpenAPI JSON 入口，实际返回 FastAPI schema。"""
    return app.openapi()


# ---- 静态文件服务（Chat UI + Approval UI）----
# 挂载在 /static/，前端页面可直接通过 http://localhost:8000/ 访问
_static_dir = Path(__file__).parent / "static"
if _static_dir.exists():
    app.mount("/static", StaticFiles(directory=str(_static_dir)), name="static")

    @app.get("/", include_in_schema=False)
    async def serve_index():
        """根路径返回 Chat UI 主页。"""
        return FileResponse(str(_static_dir / "index.html"))

    @app.get("/approval", include_in_schema=False)
    async def serve_approval():
        """HITL 审批工作台。"""
        return FileResponse(str(_static_dir / "approval.html"))
