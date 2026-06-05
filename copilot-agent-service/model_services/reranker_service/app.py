"""
Reranker Service — BAAI/bge-reranker-base (default)

设计原则：
- 启动时加载模型，请求时不重复加载
- 自动检测 CUDA，支持 CPU fallback
- 结果按 score 降序返回
- Agent Service 通过 HTTP 调用，不直接 import 模型
"""

import os
import time
import logging
from contextlib import asynccontextmanager
from typing import Optional

import torch
from fastapi import FastAPI, HTTPException
from pydantic import BaseModel, Field
from sentence_transformers import CrossEncoder

logging.basicConfig(level=logging.INFO, format="%(asctime)s %(levelname)s %(message)s")
logger = logging.getLogger(__name__)

# ── 配置 ──
MODEL_NAME    = os.getenv("RERANKER_MODEL_NAME", "BAAI/bge-reranker-base")
DEVICE_CFG    = os.getenv("RERANKER_DEVICE", "auto")
CACHE_DIR     = os.getenv("MODEL_CACHE_DIR", "/models")
MAX_DOCS      = int(os.getenv("MAX_DOCUMENTS", "50"))
MAX_Q_LEN     = int(os.getenv("MAX_QUERY_LENGTH", "512"))
MAX_DOC_LEN   = int(os.getenv("MAX_DOCUMENT_LENGTH", "2048"))

_model: Optional[CrossEncoder] = None
_device: str = "cpu"


def _resolve_device() -> str:
    if DEVICE_CFG == "auto":
        return "cuda" if torch.cuda.is_available() else "cpu"
    return DEVICE_CFG


def _load_model() -> CrossEncoder:
    global _model, _device
    _device = _resolve_device()
    logger.info(f"Loading reranker {MODEL_NAME} on {_device} (cache: {CACHE_DIR})")
    start = time.time()
    model = CrossEncoder(MODEL_NAME, device=_device, cache_dir=CACHE_DIR)
    elapsed = time.time() - start
    logger.info(f"Reranker loaded in {elapsed:.1f}s")
    return model


@asynccontextmanager
async def lifespan(app: FastAPI):
    global _model
    _model = _load_model()
    yield
    logger.info("Reranker service shutting down")


app = FastAPI(title="Reranker Service", version="1.0.0", lifespan=lifespan)


# ── 请求 / 响应结构 ──

class DocumentInput(BaseModel):
    id: str
    text: str


class RerankRequest(BaseModel):
    query: str = Field(..., min_length=1)
    documents: list[DocumentInput] = Field(..., min_length=1)
    top_k: int = Field(default=5, ge=1, le=50)


class RankedDocument(BaseModel):
    id: str
    score: float
    text: str


class RerankResponse(BaseModel):
    model: str
    device: str
    latency_ms: float
    results: list[RankedDocument]


class HealthResponse(BaseModel):
    status: str
    model: str
    device: str


# ── 路由 ──

@app.get("/health", response_model=HealthResponse)
def health():
    if _model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")
    return HealthResponse(status="ok", model=MODEL_NAME, device=_device)


@app.post("/rerank", response_model=RerankResponse)
def rerank(req: RerankRequest):
    if _model is None:
        raise HTTPException(status_code=503, detail="Model not loaded")

    if len(req.documents) > MAX_DOCS:
        raise HTTPException(
            status_code=400,
            detail=f"Too many documents: {len(req.documents)} > MAX_DOCUMENTS={MAX_DOCS}"
        )

    if len(req.query) > MAX_Q_LEN:
        raise HTTPException(
            status_code=400,
            detail=f"Query too long: {len(req.query)} > MAX_QUERY_LENGTH={MAX_Q_LEN}"
        )

    for i, doc in enumerate(req.documents):
        if len(doc.text) > MAX_DOC_LEN:
            raise HTTPException(
                status_code=400,
                detail=f"Document[{i}] (id={doc.id}) too long: {len(doc.text)} > MAX_DOCUMENT_LENGTH={MAX_DOC_LEN}"
            )

    pairs = [(req.query, doc.text) for doc in req.documents]

    start = time.time()
    scores = _model.predict(pairs, show_progress_bar=False)
    latency_ms = (time.time() - start) * 1000

    ranked = sorted(
        zip(req.documents, scores),
        key=lambda x: float(x[1]),
        reverse=True,
    )

    top_k = min(req.top_k, len(ranked))
    results = [
        RankedDocument(id=doc.id, score=round(float(score), 6), text=doc.text)
        for doc, score in ranked[:top_k]
    ]

    return RerankResponse(
        model=MODEL_NAME,
        device=_device,
        latency_ms=round(latency_ms, 2),
        results=results,
    )
