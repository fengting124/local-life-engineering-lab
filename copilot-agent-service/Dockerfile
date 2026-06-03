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
COPY requirements.txt .
# 安装依赖（不含 sentence-transformers 重量级包，生产可选注释掉以减小镜像）
RUN pip install --no-cache-dir -r requirements.txt

# ───── 代码层 ─────
COPY --chown=appuser:appgroup . .

USER appuser
EXPOSE 8000

# 生产：4 workers；开发：1 worker + reload
CMD ["uvicorn", "main:app", "--host", "0.0.0.0", "--port", "8000", "--workers", "2"]
