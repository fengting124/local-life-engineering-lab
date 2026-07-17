# ── Copilot Agent Service Dockerfile (Python FastAPI) ──
# 使用轻量级 Python 镜像，分层缓存依赖

FROM python:3.11-slim AS base

# 不生成 .pyc 文件，直接输出日志（不缓冲）
ENV PYTHONDONTWRITEBYTECODE=1
ENV PYTHONUNBUFFERED=1

# 安全：非 root 用户
RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

# ───── 依赖层（变化最少，最先复制）─────
COPY requirements.txt requirements-rag-local.txt ./
# 默认镜像安装 Agent 基础依赖 + RAG 本地向量检索依赖。
# sentence-transformers/torch 保持在 requirements-rag-models.txt 中，由独立模型服务
# 或需要进程内模型推理的环境按需安装，避免普通 Agent 镜像被大模型依赖拖慢。
RUN pip install --no-cache-dir \
    -r requirements.txt \
    -r requirements-rag-local.txt

# ───── 代码层 ─────
COPY --chown=appuser:appgroup . .

RUN mkdir -p /app/data && chown -R appuser:appgroup /app/data

USER appuser
EXPOSE 8000

# 生产：4 workers；开发：1 worker + reload
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
