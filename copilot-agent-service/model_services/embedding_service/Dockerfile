# ── Embedding Service Dockerfile ──
# 默认 CPU；如需 GPU，改 FROM 为 pytorch/pytorch:2.x-cuda12.x-cudnn9-runtime

FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

# 代理 build args
# WSL mirrored networking：容器内用 host.docker.internal 访问宿主机代理
ENV HF_ENDPOINT=https://hf-mirror.com

RUN groupadd -r appgroup && useradd -r -g appgroup appuser

WORKDIR /app

COPY requirements.txt .
RUN pip install --no-cache-dir \
        -i https://pypi.tuna.tsinghua.edu.cn/simple \
        --extra-index-url https://download.pytorch.org/whl/cpu \
        -r requirements.txt

COPY app.py .

USER appuser
EXPOSE 8100

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8100"]
