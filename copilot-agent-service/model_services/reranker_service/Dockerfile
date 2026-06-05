# ── Reranker Service Dockerfile ──

FROM python:3.11-slim

ENV PYTHONDONTWRITEBYTECODE=1 \
    PYTHONUNBUFFERED=1

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
EXPOSE 8101

CMD ["uvicorn", "app:app", "--host", "0.0.0.0", "--port", "8101"]
